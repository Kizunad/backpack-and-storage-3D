package dev.kizuna.backpack3d.client;

import net.minecraft.client.util.InputUtil;

import org.tomlj.TomlTable;

import java.util.Set;

/** 背包场景独享的旋转键，不加入原版全局按键注册表。 */
record RotationControls(InputUtil.Key yaw, InputUtil.Key pitch) {
    static final RotationControls DEFAULTS =
            new RotationControls(
                    InputUtil.fromTranslationKey("key.keyboard.r"),
                    InputUtil.fromTranslationKey("key.keyboard.t"));
    private static final Set<String> KEYS = Set.of("rotate_yaw", "rotate_pitch");

    static RotationControls read(TomlTable table) {
        // 旧配置没有 controls 表时继续使用默认键；拼错选项明确拒绝。
        if (table == null) {
            return DEFAULTS;
        }
        for (String key : table.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("controls 含未知字段：" + key);
            }
        }
        return new RotationControls(
                readKey(table, "rotate_yaw", DEFAULTS.yaw()),
                readKey(table, "rotate_pitch", DEFAULTS.pitch()));
    }

    private static InputUtil.Key readKey(TomlTable table, String field, InputUtil.Key fallback) {
        // 使用原版键名解析，键盘和鼠标绑定都只由背包场景消费。
        String value = table.getString(field);
        if (value == null) {
            return fallback;
        }
        InputUtil.Key key = InputUtil.fromTranslationKey(value);
        if (key.equals(InputUtil.UNKNOWN_KEY)) {
            throw new IllegalArgumentException("controls." + field + " 必须为有效按键");
        }
        return key;
    }
}
