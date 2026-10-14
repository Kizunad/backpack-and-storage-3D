package dev.kizuna.backpack3d.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.util.JsonHelper;

import org.joml.Vector3f;

/** 几何和动画共用的 Blockbench 数值读取规则，不执行表达式。 */
final class ModelJson {
    private ModelJson() {}

    static float number(JsonElement value, String field) {
        // Blockbench 会把关键帧轴值保存为数字字符串，但布尔值及表达式不属于数值。
        String message = field + " 必须是有限数值常量";
        if (value == null || !value.isJsonPrimitive() || value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(message);
        }
        float parsed;
        try {
            parsed = Float.parseFloat(value.getAsString());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(message, invalid);
        }
        if (!Float.isFinite(parsed)) {
            throw new IllegalArgumentException(message);
        }
        return parsed;
    }

    static Vector3f vector(JsonObject json, String field) {
        // 省略的旋转和枢轴采用零向量，实际数组必须完整含三个有限数值。
        if (!json.has(field) && (field.equals("origin") || field.equals("rotation"))) {
            return new Vector3f();
        }
        JsonArray values = JsonHelper.getArray(json, field);
        if (values.size() != 3) {
            throw new IllegalArgumentException(field + " 必须包含三个数值");
        }
        return new Vector3f(
                number(values.get(0), field + ".x"),
                number(values.get(1), field + ".y"),
                number(values.get(2), field + ".z"));
    }
}
