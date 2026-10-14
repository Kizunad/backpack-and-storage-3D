package dev.kizuna.backpack3d.item;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/** 两端按容器模型身份注册事件；客户端配置只替换声音资源，不改变事件身份。 */
public final class ContainerSounds {
    public enum Action {
        OPEN("open", "item.bundle.insert"),
        CLOSE("close", "item.armor.equip_leather"),
        PLACE("place", "block.wood.place"),
        PICKUP("pickup", "block.wool.break"),
        TOUCH("touch", "item.bundle.insert");

        private final String key;
        private final String fallback;

        Action(String key, String fallback) {
            this.key = key;
            this.fallback = fallback;
        }

        public String key() {
            return key;
        }

        public String fallback(boolean waist) {
            // 软质腰包保留原来的放置声，其他动作使用统一默认值。
            if (this == PLACE && waist) {
                return "minecraft:block.wool.place";
            }
            return "minecraft:" + fallback;
        }
    }

    private ContainerSounds() {}

    public static Identifier modelId(String model) {
        // 保留早期无命名空间模型名的兼容性，新接入方应使用完整 ID。
        return model.contains(":") ? new Identifier(model) : new Identifier("bs3d", model);
    }

    public static Identifier id(String model, Action action) {
        Identifier asset = modelId(model);
        return new Identifier(
                "bs3d",
                "container/" + asset.getNamespace() + "/" + asset.getPath() + "/" + action.key());
    }

    public static void register(String model) {
        // 多个物品允许共用一套外观和音效，因此同一个事件只注册一次。
        for (Action action : Action.values()) {
            Identifier id = id(model, action);
            if (!Registries.SOUND_EVENT.containsId(id)) {
                Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
            }
        }
    }

    public static SoundEvent event(String model, Action action) {
        return Registries.SOUND_EVENT.get(id(model, action));
    }
}
