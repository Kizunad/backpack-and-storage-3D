package dev.kizuna.backpack3d.item;

import com.google.gson.JsonObject;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WearableSlot;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 穿戴物是真实、不可堆叠的 ItemStack，没有内部容量。 */
public final class WearableItems {
    private static final String DATA_KEY = "bs3d_wearable";
    private static final String ID_KEY = "id";
    private static final Map<Item, Definition> TYPES = new LinkedHashMap<>();
    private WearableItems() {}

    public static Item register(
            Definition definition,
            String descriptionTranslationKey,
            Map<Identifier, JsonObject> optional) {
        // 注册表冻结前由两端共用清单调用；模型、层和身份不接受客户端请求改写。
        var metadata = new ItemMetadata(descriptionTranslationKey, optional);
        Identifier id = definition.id();
        if (BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalArgumentException("穿戴物品重复注册：" + id);
        }
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        Item item = Registry.register(BuiltInRegistries.ITEM, key, new WearableItem(key));
        TYPES.put(item, definition);
        ItemMetadata.register(item, metadata);
        return item;
    }

    public static Map<Item, Definition> types() {
        return Map.copyOf(TYPES);
    }

    public static Definition definition(ItemStack stack) {
        return TYPES.get(stack.getItem());
    }

    public static ItemStack create(Identifier type) {
        Item item = BuiltInRegistries.ITEM.getValue(type);
        ItemStack stack = new ItemStack(item);
        if (definition(stack) == null) {
            throw new IllegalArgumentException("未注册的穿戴物品：" + type);
        }
        initializeStack(stack);
        return stack;
    }

    public static void initializeStack(ItemStack stack) {
        // 只为全新物品分配身份；已有但损坏的数据必须在读取时报告，不能静默重建。
        if (definition(stack) == null
                || stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().contains(DATA_KEY)) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.store(ID_KEY, UUIDUtil.CODEC, UUID.randomUUID());
        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> root.put(DATA_KEY, data));
    }

    public static String identity(ItemStack stack) {
        var id = data(stack).flatMap(value -> value.read(ID_KEY, UUIDUtil.CODEC));
        if (id.isEmpty()) {
            throw new IllegalArgumentException("穿戴物品缺少有效身份");
        }
        return id.get().toString();
    }

    private static Optional<CompoundTag> data(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound(DATA_KEY);
    }

    public record Definition(
            Identifier id,
            String title,
            String model,
            List<WearableSlot> slots) {
        public Definition(Identifier id, String title, String model, List<WearableSlot> slots) {
            // Java 调用与文本清单遵守同一约束，保存副本防止注册后改变占层规则。
            if (title.isBlank() || Identifier.tryParse(model) == null || !model.contains(":")) {
                throw new IllegalArgumentException("穿戴物品必须声明 title 和完整 model ID");
            }
            WearableLayers.validate(slots);
            this.id = id;
            this.title = title;
            this.model = model;
            this.slots = List.copyOf(slots);
        }
    }

    private static final class WearableItem extends Item {
        WearableItem(ResourceKey<Item> key) {
            super(new Item.Properties().setId(key).stacksTo(1));
        }

        @Override
        public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot) {
            initializeStack(stack);
        }
    }
}
