package dev.kizuna.backpack3d.item;

import com.google.gson.JsonObject;
import dev.kizuna.backpack3d.protocol.Messages;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;
import dev.kizuna.backpack3d.world.GroundBags;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 容器是不可堆叠的真实物品，内部容量、外部占格与可装备部位分别注册。 */
public final class ContainerItems implements ModInitializer {
    // bs3d 专属创造模式物品栏；物品清单省略 creative_tab 时进入此栏。
    public static final ResourceKey<CreativeModeTab> TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath("bs3d", "containers"));
    private static final Identifier TAB_ICON = Identifier.fromNamespaceAndPath("bs3d", "gu_jia_bei_lou");
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
        // 物品栏本身不列物品，各条目由 ItemRegistrations 按清单的 creative_tab 加入；图标是 Bong 九件之一。
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TAB, FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup.bs3d.containers"))
                .icon(() -> new ItemStack(BuiltInRegistries.ITEM.getValue(TAB_ICON)))
                .build());
    }

    public static void initialize() {
        // 客户端和专用服务器加载同一目录；测试夹具也可在注册表冻结前调用。
        if (initialized) {
            return;
        }
        initialized = true;
        GroundBags.initialize();
        // 容器类型全部来自各模组 fabric.mod.json 声明的 protobuf 物品清单，本模组的 Bong 九件也走这条路。
        ItemRegistrations.discover();
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
        if (BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalArgumentException("容器物品重复注册：" + id);
        }
        ContainerSounds.register(model);
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        Item item = Registry.register(BuiltInRegistries.ITEM, key, new ContainerItem(key));
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
        // 创建实例时即分配身份；复制物品数据保留身份，移动物品不会创建第二个库存。
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(type));
        if (definition(stack) == null) {
            throw new IllegalArgumentException("未注册的容器物品：" + type);
        }
        initializeStack(stack);
        return stack;
    }

    public static void initializeStack(ItemStack stack) {
        // 只初始化全新的空容器，已有但损坏的标识不能被静默替换。
        if (definition(stack) == null
                || stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().contains(DATA_KEY)) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.store(ID_KEY, UUIDUtil.CODEC, UUID.randomUUID());
        data.putByteArray(CONTENTS_KEY, ContainerContents.getDefaultInstance().toByteArray());
        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> root.put(DATA_KEY, data));
    }

    private static Optional<CompoundTag> data(ItemStack stack) {
        // 26.2 的物品没有 NBT，容器身份与内容放在原版 custom_data 组件的同名子标签下，结构与 1.20.1 相同。
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound(DATA_KEY);
    }

    public static String identity(ItemStack stack) {
        var id = data(stack).flatMap(data -> data.read(ID_KEY, UUIDUtil.CODEC));
        if (id.isEmpty()) {
            throw new IllegalArgumentException("容器物品缺少有效身份");
        }
        return id.get().toString();
    }

    public static ContainerContents contents(ItemStack stack) {
        // 子容器以有界 protobuf 存在物品数据，读取失败保留原栈并拒绝操作。
        identity(stack);
        var stored = data(stack).orElseThrow().getByteArray(CONTENTS_KEY);
        if (stored.isEmpty()) {
            throw new IllegalArgumentException("容器内容字段缺失或类型错误");
        }
        byte[] bytes = stored.get();
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
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
                root -> root.getCompound(DATA_KEY).orElseThrow().putByteArray(CONTENTS_KEY, bytes));
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
        ContainerItem(ResourceKey<Item> key) {
            super(new Item.Properties().setId(key).stacksTo(1));
        }

        @Override
        public InteractionResult useOn(UseOnContext context) {
            // 普通右键保持原有行为；明确潜行时才把手中的真实容器放到地面。
            if (context.getPlayer() == null || !context.getPlayer().isShiftKeyDown()) {
                return InteractionResult.PASS;
            }
            return GroundBags.place(context);
        }

        @Override
        public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot) {
            // /give、合成或创造物品首次进入服务器库存时生成持久身份；26.2 只在服务端调用，客户端不生成 UUID。
            initializeStack(stack);
        }
    }
}
