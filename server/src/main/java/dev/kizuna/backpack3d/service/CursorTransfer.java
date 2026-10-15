package dev.kizuna.backpack3d.service;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Cell;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.MoveRequest;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Size;

import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** 原版光标栈与随身容器之间的候选变更；不读写世界方块或触发配方。 */
public final class CursorTransfer {
    private CursorTransfer() {}

    public static Candidate prepare(
            Account account, ItemStack cursor, Request request, Function<ItemStack, Size> sizes) {
        // 光标作为临时根参与整棵树的唯一身份、嵌套和占格校验，不能绕过容器规则。
        SavedInventory previous = account.data();
        if (request.revision() != previous.getRevision()) {
            throw new IllegalArgumentException("背包已变化，请重新选择物品");
        }
        boolean taking = !request.item().isEmpty();
        if (taking == !request.container().isEmpty()) {
            throw new IllegalArgumentException("必须只指定取出的物品或存入的容器");
        }
        List<ItemStack> inventory = new ArrayList<>();
        for (ItemStack stack : account.inventory()) {
            inventory.add(stack.copy());
        }
        int cursorIndex = inventory.size();
        inventory.add(cursor.copy());
        var tree = new ContainerTree(previous, inventory);
        ItemStack moved;
        if (taking) {
            if (!cursor.isEmpty()) {
                throw new IllegalArgumentException("请先放下光标中的物品");
            }
            var entry = tree.take(request.item());
            moved = tree.stack(entry);
            inventory.set(cursorIndex, moved.copy());
            tree.putPlayer(entry, cursorIndex);
        } else {
            if (cursor.isEmpty()) {
                throw new IllegalArgumentException("光标没有物品");
            }
            var type = ContainerItems.definition(cursor);
            Size size = type == null ? sizes.apply(cursor) : type.outside();
            var entry = tree.takePlayer(cursorIndex, cursor, UUID.randomUUID().toString(), size);
            moved = tree.stack(entry);
            var destination =
                    MoveRequest.newBuilder()
                            .setTarget(request.container())
                            .setCell(request.cell())
                            .setOrientation(request.orientation())
                            .build();
            tree.place(entry, destination);
        }
        var next =
                previous.toBuilder()
                        .setRevision(Math.addExact(previous.getRevision(), 1))
                        .clearWorn()
                        .addAllWorn(tree.worn())
                        .build();
        // 再解析候选，特别检查从光标存入的整个嵌套子树；失败不产生任何真实写入。
        TransferService.validate(next);
        new ContainerTree(next, inventory);
        ItemStack nextCursor = inventory.remove(cursorIndex);
        return new Candidate(next, List.copyOf(inventory), nextCursor, moved);
    }

    public record Request(
            // BS3D 保存修订号，与原版菜单 syncId 无关。
            long revision,
            // 取出来源实例 UUID；存入时为空。
            String item,
            // 存入目标容器 UUID；取出时为空。
            String container,
            // 目标格坐标，X/宽、Y/自底向上高度、Z/深度。
            Cell cell,
            // 正交朝向的稳定索引 0..23。
            int orientation) {
        public static Request take(long revision, String item) {
            return new Request(revision, item, "", Cell.getDefaultInstance(), 0);
        }

        public static Request store(long revision, String container, Cell cell, int orientation) {
            return new Request(revision, "", container, cell, orientation);
        }
    }

    /** 候选仅供同一服务器调用使用；其中的 ItemStack 都是副本。 */
    public record Candidate(
            SavedInventory data, List<ItemStack> inventory, ItemStack cursor, ItemStack moved) {}
}
