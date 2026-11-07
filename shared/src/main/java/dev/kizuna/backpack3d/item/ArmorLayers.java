package dev.kizuna.backpack3d.item;

import java.util.Optional;
import net.minecraft.world.entity.EquipmentSlot;

/** BS3D 甲层与原版人体装备部位的唯一映射。 */
public final class ArmorLayers {
    private ArmorLayers() {}

    public static Optional<EquipmentSlot> equipment(String layer) {
        return switch (layer) {
            case WearableLayers.ARMOR_HEAD -> Optional.of(EquipmentSlot.HEAD);
            case WearableLayers.ARMOR_BODY -> Optional.of(EquipmentSlot.CHEST);
            case WearableLayers.ARMOR_LEGS -> Optional.of(EquipmentSlot.LEGS);
            case WearableLayers.ARMOR_FEET -> Optional.of(EquipmentSlot.FEET);
            default -> Optional.empty();
        };
    }
}
