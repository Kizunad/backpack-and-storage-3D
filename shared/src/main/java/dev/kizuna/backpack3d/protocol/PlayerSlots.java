package dev.kizuna.backpack3d.protocol;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;

import java.util.List;

/** 玩家展示槽位与原版装备部位的映射，统一使用 PlayerInventory 槽号。 */
public final class PlayerSlots {
    public static final List<EquipmentSlot> ARMOR =
            List.of(
                    EquipmentSlot.HEAD,
                    EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS,
                    EquipmentSlot.FEET);

    private PlayerSlots() {}

    public static boolean isArmor(int slot) {
        return slot >= PlayerInventory.MAIN_SIZE && slot < PlayerInventory.OFF_HAND_SLOT;
    }

    public static boolean isInteractive(int slot, int selectedSlot) {
        return slot == selectedSlot || slot == PlayerInventory.OFF_HAND_SLOT || isArmor(slot);
    }

    public static boolean bindingLocked(int slot, ItemStack stack, boolean creative) {
        return isArmor(slot) && !creative && EnchantmentHelper.hasBindingCurse(stack);
    }

    public static int armorSlot(EquipmentSlot equipment) {
        // 手持装备的实体索引与盔甲重叠，必须先区分类型再换算原版槽号。
        if (equipment.getType() != EquipmentSlot.Type.ARMOR) {
            throw new IllegalArgumentException("不是盔甲部位：" + equipment);
        }
        return equipment.getOffsetEntitySlotId(PlayerInventory.MAIN_SIZE);
    }

    public static EquipmentSlot armorType(int slot) {
        // 调用方使用原版槽号，不能把快捷栏下标误当成盔甲索引。
        if (!isArmor(slot)) {
            throw new IllegalArgumentException("不是盔甲槽位：" + slot);
        }
        return EquipmentSlot.fromTypeIndex(
                EquipmentSlot.Type.ARMOR, slot - PlayerInventory.MAIN_SIZE);
    }
}
