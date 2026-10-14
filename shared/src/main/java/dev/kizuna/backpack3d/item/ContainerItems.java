package dev.kizuna.backpack3d.item;

import com.google.gson.JsonObject;
import dev.kizuna.backpack3d.protocol.Messages;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;
import dev.kizuna.backpack3d.world.GroundBags;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 容器是不可堆叠的真实物品，内部容量、外部占格与可装备部位分别注册。 */
public final class ContainerItems implements ModInitializer {
    public static final Identifier POUCH = new Identifier("bs3d", "grass_pouch");
    public static final Identifier BASKET = new Identifier("bs3d", "back_basket");
    public static final Identifier BOX = new Identifier("bs3d", "storage_box");
    public static final int DEFAULT_WAIST_LIMIT = 2;
    public static final int MAX_NESTING_DEPTH = 4;
    public static final int MAX_CONTAINERS = 32;
    public static final int MAX_WAIST_LIMIT = 16;
    public static final float FULL_CIRCLE_DEGREES = 360;
    private static final String DATA_KEY = "bs3d_container";
    private static final String ID_KEY = "id";
    private static final String CONTENTS_KEY = "contents";
    private static final Map<Item, Definition> TYPES = new LinkedHashMap<>();
    private static boolean initialized;

    @Override
    public void onInitialize() {
        initialize();
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS)
                .register(entries -> TYPES.keySet().forEach(item -> entries.add(item)));
    }

    public static void initialize() {
        // 客户端和专用服务器加载同一目录；测试夹具也可在注册表冻结前调用。
        if (initialized) {
            return;
        }
        initialized = true;
        register(
                POUCH,
                "草包",
                "item.bs3d.grass_pouch.description",
                "grass_pouch",
                Volumes.size(1, 2, 2),
                Volumes.size(2, 2, 2),
                List.of(
                        Mount.WAIST_RIGHT_FRONT, Mount.WAIST_LEFT_FRONT,
                        Mount.WAIST_LEFT_BACK, Mount.WAIST_RIGHT_BACK),
                Map.of());
        register(
                BASKET,
                "背篓",
                "item.bs3d.back_basket.description",
                "back_basket",
                Volumes.size(3, 3, 3),
                Volumes.size(3, 3, 4),
                List.of(Mount.TORSO_FRONT, Mount.TORSO_BACK),
                Map.of());
        register(
                BOX,
                "收纳箱",
                "item.bs3d.storage_box.description",
                "storage_box",
                Volumes.size(5, 3, 2),
                Volumes.size(5, 3, 2),
                List.of(),
                Map.of());
        GroundBags.initialize();
    }

    public static Item register(
            Identifier id,
            String title,
            String descriptionTranslationKey,
            String model,
            Size inside,
            Size outside,
            List<Mount> mounts,
            Map<Identifier, JsonObject> optional) {
        // 尺寸由两端共同注册的类型决定，物品 NBT 和客户端请求不能扩大容量。
        requireSize(inside);
        requireSize(outside);
        var metadata = new ItemMetadata(descriptionTranslationKey, optional);
        var definition = new Definition(id, title, model, inside, outside, List.copyOf(mounts));
        if (Registries.ITEM.containsId(id)) {
            throw new IllegalArgumentException("容器物品重复注册：" + id);
        }
        ContainerSounds.register(model);
        Item item = Registry.register(Registries.ITEM, id, new ContainerItem());
        TYPES.put(item, definition);
        ItemMetadata.register(item, metadata);
        return item;
    }

    private static void requireSize(Size size) {
        if (size.getWidth() < 1 || size.getHeight() < 1 || size.getDepth() < 1) {
            throw new IllegalArgumentException("容器尺寸必须为正整数");
        }
    }

    public static Map<Item, Definition> types() {
        return Map.copyOf(TYPES);
    }

    public static Definition definition(ItemStack stack) {
        return TYPES.get(stack.getItem());
    }

    public static ItemStack create(Identifier type) {
        // 创建实例时即分配身份；复制 NBT 保留身份，移动物品不会创建第二个库存。
        ItemStack stack = new ItemStack(Registries.ITEM.get(type));
        if (definition(stack) == null) {
            throw new IllegalArgumentException("未注册的容器物品：" + type);
        }
        initializeStack(stack);
        return stack;
    }

    public static void initializeStack(ItemStack stack) {
        // 只初始化全新的空容器，已有但损坏的标识不能被静默替换。
        if (definition(stack) == null || stack.getSubNbt(DATA_KEY) != null) {
            return;
        }
        NbtCompound data = stack.getOrCreateSubNbt(DATA_KEY);
        data.putUuid(ID_KEY, UUID.randomUUID());
        data.putByteArray(CONTENTS_KEY, ContainerContents.getDefaultInstance().toByteArray());
    }

    public static String identity(ItemStack stack) {
        NbtCompound data = stack.getSubNbt(DATA_KEY);
        if (data == null || !data.containsUuid(ID_KEY)) {
            throw new IllegalArgumentException("容器物品缺少有效身份");
        }
        return data.getUuid(ID_KEY).toString();
    }

    public static ContainerContents contents(ItemStack stack) {
        // 子容器以有界 protobuf 存在物品 NBT，读取失败保留原栈并拒绝操作。
        identity(stack);
        NbtCompound data = stack.getSubNbt(DATA_KEY);
        if (!data.contains(CONTENTS_KEY, NbtElement.BYTE_ARRAY_TYPE)) {
            throw new IllegalArgumentException("容器内容字段缺失或类型错误");
        }
        byte[] bytes = data.getByteArray(CONTENTS_KEY);
        if (bytes.length > Messages.MAX_SAVE_BYTES) {
            throw new IllegalArgumentException("容器内容超过大小限制");
        }
        try {
            return ContainerContents.parseFrom(bytes);
        } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
            throw new IllegalArgumentException("容器内容损坏", invalid);
        }
    }

    public static void setContents(
            ItemStack stack, List<dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item> items) {
        // 身份不变，只替换候选栈中的内容，真实栈由玩家事务统一提交。
        identity(stack);
        byte[] bytes = ContainerContents.newBuilder().addAllItems(items).build().toByteArray();
        if (bytes.length > Messages.MAX_SAVE_BYTES) {
            throw new IllegalArgumentException("容器内容超过大小限制");
        }
        stack.getSubNbt(DATA_KEY).putByteArray(CONTENTS_KEY, bytes);
    }

    public record Definition(
            // 注册物品类型 ID，与每件容器的实例 UUID 不同。
            Identifier id,
            String title,
            // config/bs3d/config.toml 中的模型 ID；旧的短名称等价于 bs3d:<名称>。
            String model,
            // 内部可用容量，单位为格，按宽、高、深排列。
            Size inside,
            // 整件物品放进另一容器时的占格，单位为格。
            Size outside,
            // 可装备部位；空列表表示不可穿戴。
            List<Mount> mounts) {
        public boolean waist() {
            return mounts.contains(Mount.WAIST_RIGHT_FRONT);
        }
    }

    private static final class ContainerItem extends Item {
        ContainerItem() {
            super(new Item.Settings().maxCount(1));
        }

        @Override
        public ActionResult useOnBlock(ItemUsageContext context) {
            // 普通右键保持原有行为；明确潜行时才把手中的真实容器放到地面。
            if (context.getPlayer() == null || !context.getPlayer().isSneaking()) {
                return ActionResult.PASS;
            }
            return GroundBags.place(context);
        }

        @Override
        public void inventoryTick(
                ItemStack stack, World world, Entity owner, int slot, boolean selected) {
            // /give、合成或创造物品首次进入服务器库存时生成持久身份，客户端不生成 UUID。
            if (!world.isClient) {
                initializeStack(stack);
            }
        }
    }
}
