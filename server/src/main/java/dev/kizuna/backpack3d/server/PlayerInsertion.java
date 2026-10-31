package dev.kizuna.backpack3d.server;

import dev.kizuna.backpack3d.mixin.ScreenHandlerAccess;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** 在临时候选上运行原版 insertItem，失败或返回前恢复玩家原库存。 */
final class PlayerInsertion {
    private PlayerInsertion() {
        // 候选由调用方提交，这里不保存和同步物品。
    }

    static int insert(ServerPlayer player, List<ItemStack> candidate, ItemStack remaining) {
        // 只借用主线程真实菜单校验插入能力，不触发原版点击或配方回调。
        List<ItemStack> original = new PlayerAccount(player).inventory();
        try {
            for (int index = 0; index < candidate.size(); index++) {
                player.getInventory().setItem(index, candidate.get(index).copy());
            }
            var slots = player.inventoryMenu.slots.stream()
                    .filter(slot -> slot.container == player.getInventory())
                    .filter(slot -> slot.getContainerSlot() < Inventory.INVENTORY_SIZE)
                    .sorted(Comparator.comparingInt(Slot::getContainerSlot))
                    .toList();
            int merged = insertSlots(player, slots, remaining, Destination.MERGE);
            int inserted;
            if (!remaining.isEmpty()) {
                inserted = insertSlots(player, slots, remaining, Destination.EMPTY);
            } else {
                inserted = merged;
            }
            if (!remaining.isEmpty()) {
                throw new IllegalArgumentException("原版背包已满");
            }
            for (int index = 0; index < candidate.size(); index++) {
                candidate.set(index, player.getInventory().getItem(index).copy());
            }
            return inserted;
        } finally {
            for (int index = 0; index < original.size(); index++) {
                player.getInventory().setItem(index, original.get(index));
            }
        }
    }

    private static int insertSlots(ServerPlayer player, List<Slot> slots,
                                   ItemStack remaining, Destination destination) {
        // 先合并再找空位，保留既有快捷栏优先顺序，实际数量和槽位权限交给原版。
        var handler = (ScreenHandlerAccess) player.inventoryMenu;
        int lastSlot = -1;
        for (Slot slot : slots) {
            boolean empty = slot.getItem().isEmpty();
            if (empty != (destination == Destination.EMPTY) || !slot.mayPlace(remaining)) {
                continue;
            }
            if (handler.bs3d$insertItem(remaining, slot.index, slot.index + 1, false /* fromLast */)) {
                lastSlot = slot.getContainerSlot();
            }
            if (remaining.isEmpty()) {
                break;
            }
        }
        return lastSlot;
    }

    private enum Destination { MERGE, EMPTY }
}
