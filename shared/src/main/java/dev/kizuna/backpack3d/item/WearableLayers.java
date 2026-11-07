package dev.kizuna.backpack3d.item;

import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WearableSlot;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 穿戴层与挂骨契约；层名决定互斥，骨名及偏移只用于展示。 */
public final class WearableLayers {
    public static final String ARMOR_HEAD = "armor_head";
    public static final String ARMOR_BODY = "armor_body";
    public static final String ARMOR_LEGS = "armor_legs";
    public static final String ARMOR_FEET = "armor_feet";
    public static final String LEFT_SHOULDER = "left_shoulder";
    public static final int MAX_SLOTS = 32;
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("head", "头饰"), Map.entry("eyes", "眼饰"), Map.entry("face", "面饰"),
            Map.entry("neck", "颈饰"), Map.entry(LEFT_SHOULDER, "左肩"), Map.entry("right_shoulder", "右肩"),
            Map.entry("left_wrist", "左腕"), Map.entry("right_wrist", "右腕"),
            Map.entry("left_leg", "左腿"), Map.entry("right_leg", "右腿"),
            Map.entry("left_foot", "左脚"), Map.entry("right_foot", "右脚"), Map.entry("cape", "披风"),
            Map.entry(ARMOR_HEAD, "头甲"), Map.entry(ARMOR_BODY, "身甲"),
            Map.entry(ARMOR_LEGS, "腿甲"), Map.entry(ARMOR_FEET, "脚甲"));

    private WearableLayers() {}

    public static String label(String layer) {
        return LABELS.getOrDefault(layer, layer);
    }

    public static boolean conflicts(String first, String second) {
        // 左肩外观与身甲互斥，其他外观层独立；披风不占用背包的胸背挂点。
        return first.equals(second)
                || first.equals(LEFT_SHOULDER) && second.equals(ARMOR_BODY)
                || first.equals(ARMOR_BODY) && second.equals(LEFT_SHOULDER);
    }

    public static void validate(List<WearableSlot> slots) {
        // 清单和 Java 注册共用校验，有限坐标与有界数量避免非法挂点进入存档及渲染。
        if (slots.isEmpty() || slots.size() > MAX_SLOTS) {
            throw new IllegalArgumentException("wearable.slots 数量必须为 1.." + MAX_SLOTS);
        }
        Set<String> bindings = new HashSet<>();
        for (WearableSlot slot : slots) {
            String layer = slot.getLayer();
            String bone = slot.getBone();
            if (!layer.matches("[a-z][a-z0-9_:./-]*") || !bone.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("wearable.slots 必须声明合法 layer 和小写骨名 bone");
            }
            String binding = layer + "/" + slot.getSegment();
            if (!bindings.add(binding)) {
                throw new IllegalArgumentException("wearable.slots 重复层内骨段：" + binding);
            }
            var offset = slot.getOffset();
            if (!Float.isFinite(offset.getX()) || !Float.isFinite(offset.getY())
                    || !Float.isFinite(offset.getZ())) {
                throw new IllegalArgumentException("wearable.slots.offset 必须为有限数值");
            }
        }
    }
}
