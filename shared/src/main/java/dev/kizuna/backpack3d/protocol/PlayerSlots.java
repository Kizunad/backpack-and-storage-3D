package dev.kizuna.backpack3d.protocol;

import java.util.List;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/** 玩家展示槽位与原版装备部位的映射，统一使用 Inventory 槽号。 */
public final class PlayerSlots {
    public static final List<EquipmentSlot> ARMOR =
            List.of(
                    EquipmentSlot.HEAD,
                    EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS,
                    EquipmentSlot.FEET);

    private PlayerSlots() {}

    public static boolean isArmor(int slot) {
        return slot >= Inventory.INVENTORY_SIZE && slot < Inventory.SLOT_OFFHAND;
    }

    public static boolean isInteractive(int slot, int selectedSlot) {
        return slot == selectedSlot || slot == Inventory.SLOT_OFFHAND || isArmor(slot);
    }

    public static boolean bindingLocked(int slot, ItemStack stack, boolean creative) {
        return isArmor(slot) && !creative && EnchantmentHelper.hasBindingCurse(stack);
    }

    public static int armorSlot(EquipmentSlot equipment) {
        // 手持装备的实体索引与盔甲重叠，必须先区分类型再换算原版槽号。
        if (equipment.getType() != EquipmentSlot.Type.ARMOR) {
            throw new IllegalArgumentException("不是盔甲部位：" + equipment);
        }
        return equipment.getIndex(Inventory.INVENTORY_SIZE);
    }

    public static EquipmentSlot armorType(int slot) {
        // 调用方使用原版槽号，不能把快捷栏下标误当成盔甲索引。
        if (!isArmor(slot)) {
            throw new IllegalArgumentException("不是盔甲槽位：" + slot);
        }
        return EquipmentSlot.byTypeAndIndex(
                EquipmentSlot.Type.ARMOR, slot - Inventory.INVENTORY_SIZE);
    }
}
