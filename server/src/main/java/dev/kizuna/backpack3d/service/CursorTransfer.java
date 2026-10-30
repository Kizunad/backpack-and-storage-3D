package dev.kizuna.backpack3d.service;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Cell;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.MoveRequest;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Size;

import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** 原版光标栈与已授权容器之间的候选变更；不读写世界方块或触发配方。 */
public final class CursorTransfer {
    private CursorTransfer() {}

    public static Candidate prepare(
            Account account, ItemStack cursor, Request request, Function<ItemStack, Size> sizes) {
        // 普通调用从账户重新读取地面租约，组合事务可以传入本次调用内的候选树。
        return prepare(account, cursor, request, sizes, account.ground());
    }

    public static Candidate prepare(Account account, ItemStack cursor, Request request,
                                    Function<ItemStack, Size> sizes, Map<String, ItemStack> ground) {
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
        var candidateGround = new LinkedHashMap<String, ItemStack>();
        ground.forEach((id, stack) -> candidateGround.put(id, stack.copy()));
        var tree = new ContainerTree(previous, inventory, candidateGround);
        var context = new CursorContext(tree, inventory, cursorIndex, cursor);
        ItemStack moved;
        if (taking) {
            moved = takeCursor(context, request.item());
        } else {
            moved = storeCursor(context, request, sizes);
        }
        var next =
                previous.toBuilder()
                        .setRevision(Math.addExact(previous.getRevision(), 1))
                        .clearWorn()
                        .addAllWorn(tree.worn())
                        .build();
        // 再解析候选，特别检查从光标存入的整个嵌套子树；失败不产生任何真实写入。
        TransferService.validate(next);
        // 重建整树校验唯一身份、容量和嵌套；非法候选在此抛出，不写入真实库存。
        new ContainerTree(next, inventory, candidateGround);
        ItemStack nextCursor = inventory.remove(cursorIndex);
        return new Candidate(next, List.copyOf(inventory), nextCursor, moved, Map.copyOf(candidateGround));
    }

    private record CursorContext(ContainerTree tree, List<ItemStack> inventory,
                                 int cursorIndex, ItemStack cursor) {}

    private static ItemStack takeCursor(CursorContext context, String item) {
        // 取出只生成唯一光标候选，原库存保持不变。
        var cursor = context.cursor();
        var tree = context.tree();
        var inventory = context.inventory();
        int cursorIndex = context.cursorIndex();
        if (!cursor.isEmpty()) {
            throw new IllegalArgumentException("请先放下光标中的物品");
        }
        var entry = tree.take(item);
        ItemStack moved = tree.stack(entry);
        inventory.set(cursorIndex, moved.copy());
        tree.putPlayer(entry, cursorIndex);
        return moved;
    }

    private static ItemStack storeCursor(CursorContext context, Request request,
                                        Function<ItemStack, Size> sizes) {
        // 手动格位和模型自动寻位共用身份与容量验证。
        var cursor = context.cursor();
        var tree = context.tree();
        int cursorIndex = context.cursorIndex();
        if (cursor.isEmpty()) {
            throw new IllegalArgumentException("光标没有物品");
        }
        var type = ContainerItems.definition(cursor);
        Size size;
        if (type == null) {
            size = sizes.apply(cursor);
        } else {
            size = type.outside();
        }
        var entry = tree.takePlayer(cursorIndex, cursor, UUID.randomUUID().toString(), size);
        ItemStack moved = tree.stack(entry);
        var destination =
                MoveRequest.newBuilder()
                        .setTarget(request.container())
                        .setCell(request.cell())
                        .setOrientation(request.orientation())
                        .setAutoPlace(request.placement() == Placement.AUTOMATIC)
                        .build();
        if (request.container().equals(Volumes.CARRIED)) {
            tree.placeCarried(entry, request.orientation());
        } else {
            tree.place(entry, destination);
        }
        return moved;
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
            int orientation,
            Placement placement) {
        public static Request take(long revision, String item) {
            return new Request(revision, item, "", Cell.getDefaultInstance(), 0, Placement.MANUAL);
        }

        public static Request store(long revision, String container, Cell cell, int orientation) {
            return new Request(revision, "", container, cell, orientation, Placement.MANUAL);
        }

        public static Request autoStore(long revision, String container, int orientation) {
            // 模型落点不带格坐标，服务端在完整容量里确定最终位置。
            return new Request(revision, "", container, Cell.getDefaultInstance(), orientation, Placement.AUTOMATIC);
        }
    }

    public enum Placement { MANUAL, AUTOMATIC }

    /** 候选仅供同一服务器调用使用；其中的 ItemStack 都是副本。 */
    public record Candidate(
            SavedInventory data, List<ItemStack> inventory, ItemStack cursor,
            ItemStack moved, Map<String, ItemStack> ground) {}
}
