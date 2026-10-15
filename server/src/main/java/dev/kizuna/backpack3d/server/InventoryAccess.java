package dev.kizuna.backpack3d.server;

import com.google.protobuf.ByteString;

import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.service.CursorTransfer;

import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.Map;

/** 附属读取快照及与原版光标交换物品的边界，不允许直接覆写库存。 */
public final class InventoryAccess {
    private InventoryAccess() {}

    public static CursorTransfer.Candidate previewCursor(
            ServerPlayerEntity player, CursorTransfer.Request request, ByteString fingerprint) {
        // 调用者必须持有当前原版页面；所有检查在主线程读取真实栈，客户端不能传 NBT。
        var server = player.getServer();
        if (server == null || !server.isOnThread()) {
            throw new IllegalStateException("光标交换必须在服务器主线程执行");
        }
        if (!player.isAlive() || player.isSpectator() || VolumeServer.locked(player)) {
            throw new IllegalArgumentException("玩家当前无法操作物品");
        }
        var handler = player.currentScreenHandler;
        if (!handler.canUse(player)) {
            throw new IllegalArgumentException("原版容器已不可用");
        }
        var cursor = handler.getCursorStack();
        if (!StackCodec.fingerprint(cursor).equals(fingerprint)) {
            throw new IllegalArgumentException("光标物品已变化");
        }
        return CursorTransfer.prepare(
                new PlayerAccount(player),
                cursor,
                request,
                stack -> VolumeServer.RULES.material(stack).size());
    }

    public static void exchangeCursor(
            ServerPlayerEntity player, CursorTransfer.Request request, ByteString fingerprint) {
        // 提交时重新生成候选，不能把计时开始时的副本覆盖到当前库存。
        var candidate = previewCursor(player, request, fingerprint);
        ((PlayerStorage) player).bs3d$setInventory(candidate.data());
        for (int slot = 0; slot < candidate.inventory().size(); slot++) {
            player.getInventory().setStack(slot, candidate.inventory().get(slot));
        }
        player.currentScreenHandler.setCursorStack(candidate.cursor());
        player.getInventory().markDirty();
        // 光标由原版关闭页面流程归还、丢弃及保存，不另建一份持久化物品。
        player.currentScreenHandler.syncState();
        player.playerScreenHandler.sendContentUpdates();
    }

    public static Snapshot snapshot(ServerPlayerEntity player) {
        // 所有栈都是本次读取的副本；附属修改副本不会影响玩家或地面权威库存。
        var server = player.getServer();
        if (server == null || !server.isOnThread()) {
            throw new IllegalStateException("库存快照必须在服务器主线程读取");
        }
        var personal = new PlayerAccount(player);
        var session = VolumeServer.findSession(player);
        var ground = session == null ? Map.<String, ItemStack>of() : session.accessibleGround();
        return new Snapshot(personal.data(), List.copyOf(personal.inventory()), Map.copyOf(ground));
    }

    public record Snapshot(
            // 不可变的穿戴容器存档，包含业务修订号。
            SavedInventory equipment,
            // 原版槽号顺序的栈副本，包含盔甲及副手。
            List<ItemStack> playerSlots,
            // 已打开且仍持有访问权的地面根容器；键为实例 UUID，不属于随身库存。
            Map<String, ItemStack> ground) {}
}
