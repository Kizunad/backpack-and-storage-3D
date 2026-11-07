package dev.kizuna.backpack3d.server;

import dev.kizuna.backpack3d.item.ArmorLayers;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** 甲层占用和装备变更通知；防御数值由穿搭附属解释。 */
public final class WearableEquipment {
    public static final Event<Changed> CHANGED = EventFactory.createArrayBacked(Changed.class,
            listeners -> player -> {
                for (Changed listener : listeners) {
                    listener.changed(player);
                }
            });

    private WearableEquipment() {}

    public static boolean blocks(ServerPlayer player, EquipmentSlot equipment) {
        return blocks(((PlayerStorage) player).bs3d$getInventory(), equipment);
    }

    public static boolean blocks(SavedInventory data, EquipmentSlot equipment) {
        // 存档的绑定已由 ContainerTree 与服务端类型登记校验；同一部位的多个段只作一次占用判断。
        return data.getWornWearablesList().stream()
                .flatMap(worn -> worn.getSlotsList().stream())
                .anyMatch(slot -> ArmorLayers.equipment(slot.getLayer()).orElse(null) == equipment);
    }

    public static void validateVanillaInsertion(ServerPlayer player, EquipmentSlot equipment, ItemStack stack) {
        // void 写入入口不能静默吞掉来物。正常交互先经 canUseSlot 拒绝；直接调用者必须保留其来源栈。
        if (!stack.isEmpty() && blocks(player, equipment)) {
            throw new IllegalArgumentException("该身体部位已有 BS3D 甲层，请先脱下");
        }
    }

    @FunctionalInterface
    public interface Changed {
        void changed(ServerPlayer player);
    }
}
