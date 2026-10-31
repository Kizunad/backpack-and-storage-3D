package dev.kizuna.backpack3d.protocol;

import java.util.List;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
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
        // 26.2 的绑定诅咒是附魔效果组件，判断方式与原版盔甲槽相同。
        return isArmor(slot) && !creative && EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
    }

    public static int armorSlot(EquipmentSlot equipment) {
        // 手持装备的实体索引与盔甲重叠，必须先区分类型再换算原版槽号。
        if (equipment.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
            throw new IllegalArgumentException("不是盔甲部位：" + equipment);
        }
        return equipment.getIndex(Inventory.INVENTORY_SIZE);
    }

    public static EquipmentSlot armorType(int slot) {
        // 调用方使用原版槽号，不能把快捷栏下标误当成盔甲索引。
        if (!isArmor(slot)) {
            throw new IllegalArgumentException("不是盔甲槽位：" + slot);
        }
        // 26.2 去掉了按类型和索引查部位的方法，用同一换算反查四个盔甲部位。
        return ARMOR.stream().filter(equipment -> armorSlot(equipment) == slot).findFirst().orElseThrow();
    }
}
