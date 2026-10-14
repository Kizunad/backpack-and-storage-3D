package dev.kizuna.backpack3d.item;

import com.google.gson.JsonObject;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 物品类型的展示信息和附属字段，不属于单件物品 NBT 或容器实例身份。 */
public final class ItemMetadata {
    private static final Map<Item, ItemMetadata> ITEMS = new HashMap<>();
    private static final ItemMetadata EMPTY = new ItemMetadata("", Map.of());

    // 空字符串表示没有补充简介；非空值由客户端语言文件翻译。
    private final String descriptionTranslationKey;
    // 键为附属定义的完整资源 ID，值的结构与校验由对应附属负责。
    private final Map<Identifier, JsonObject> optional;

    public ItemMetadata(String descriptionTranslationKey, Map<Identifier, JsonObject> optional) {
        // optional 必须显式提供，允许空表；复制 JSON，防止注册方之后改写服务端配置。
        this.descriptionTranslationKey = Objects.requireNonNull(
                descriptionTranslationKey, "descriptionTranslationKey 不能为 null");
        Objects.requireNonNull(optional, "optional 必须显式提供；无附属字段时传 Map.of()");
        var fields = new LinkedHashMap<Identifier, JsonObject>();
        for (var entry : optional.entrySet()) {
            Identifier key = Objects.requireNonNull(entry.getKey(), "optional 字段 ID 不能为 null");
            JsonObject value = Objects.requireNonNull(entry.getValue(), "optional 字段值不能为 null");
            fields.put(key, value.deepCopy());
        }
        this.optional = Map.copyOf(fields);
    }

    public static void register(
            Item item, String descriptionTranslationKey, Map<Identifier, JsonObject> optional) {
        // 原版与第三方物品复用同一入口；只补充元数据，不重新注册或替换真实物品。
        register(item, new ItemMetadata(descriptionTranslationKey, optional));
    }

    static void register(Item item, ItemMetadata metadata) {
        // 初始化阶段每个物品只登记一次，避免加载顺序决定最终配置。
        Objects.requireNonNull(item, "item 不能为 null");
        if (ITEMS.putIfAbsent(item, metadata) != null) {
            throw new IllegalArgumentException("物品元数据重复注册：" + Registries.ITEM.getId(item));
        }
    }

    public static ItemMetadata of(ItemStack stack) {
        return ITEMS.getOrDefault(stack.getItem(), EMPTY);
    }

    public String descriptionTranslationKey() {
        return descriptionTranslationKey;
    }

    public Optional<JsonObject> optional(Identifier field) {
        // 附属只能拿到副本；校验或解析字段不能改写其他调用者看到的注册信息。
        JsonObject value = optional.get(field);
        return value == null ? Optional.empty() : Optional.of(value.deepCopy());
    }
}
