package dev.kizuna.backpack3d.server;

import com.google.protobuf.ByteString;

import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.service.CursorTransfer;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.io.IOException;

/** 附属读取快照及与原版光标交换物品的边界，不允许直接覆写库存。 */
public final class InventoryAccess {
    private InventoryAccess() {}

    public static boolean tryStoreCarried(ServerPlayer player, ItemStack stack) throws IOException {
        // 登录整理只访问玩家已装备的容器，不要求客户端握手，也不授权地面库存。
        if (!player.level().getServer().isSameThread()) {
            throw new IllegalStateException("随身容器整理必须在服务器主线程执行");
        }
        var account = new PlayerAccount(player);
        var request = CursorTransfer.Request.autoStore(account.data().getRevision(), Volumes.CARRIED, 0);
        CursorTransfer.Candidate candidate;
        try {
            candidate = CursorTransfer.prepare(account, stack, request,
                    value -> VolumeServer.RULES.material(value).size());
        } catch (IllegalArgumentException unavailable) {
            return false;
        }
        account.commit(candidate.data(), candidate.inventory());
        stack.setCount(0);
        return true;
    }

    public static CursorTransfer.Candidate previewCursor(
            ServerPlayer player, CursorTransfer.Request request, ByteString fingerprint) {
        // 调用者必须持有当前原版页面；所有检查在主线程读取真实栈，客户端不能传 NBT。
        if (!player.level().getServer().isSameThread()) {
            throw new IllegalStateException("光标交换必须在服务器主线程执行");
        }
        if (!player.isAlive() || player.isSpectator()) {
            throw new IllegalArgumentException("玩家当前无法操作物品");
        }
        var handler = player.containerMenu;
        if (!handler.stillValid(player)) {
            throw new IllegalArgumentException("原版容器已不可用");
        }
        var cursor = handler.getCarried();
        if (!StackCodec.fingerprint(cursor, player.registryAccess()).equals(fingerprint)) {
            throw new IllegalArgumentException("光标物品已变化");
        }
        return CursorTransfer.prepare(
                cursorAccount(player),
                cursor,
                request,
                stack -> VolumeServer.RULES.material(stack).size());
    }

    public static void exchangeCursor(
            ServerPlayer player, CursorTransfer.Request request, ByteString fingerprint) {
        // 提交时重新生成候选，不能用旧副本覆盖当前库存。
        var candidate = previewCursor(player, request, fingerprint);
        if (!sameGround(candidate.ground(), snapshot(player).ground())) {
            throw new IllegalArgumentException("地面物品必须通过完整拖放选择最终落点");
        }
        applyCursor(player, candidate);
        player.containerMenu.sendAllDataToRemote();
        player.inventoryMenu.broadcastChanges();
    }

    private static void applyCursor(ServerPlayer player, CursorTransfer.Candidate candidate) {
        // 仅供同一服务器调用内组合原版点击；调用者完成整笔事务后才同步。
        if (!player.level().getServer().isSameThread()) {
            throw new IllegalStateException("光标交换必须在服务器主线程执行");
        }
        ((PlayerStorage) player).bs3d$setInventory(candidate.data());
        for (int slot = 0; slot < candidate.inventory().size(); slot++) {
            player.getInventory().setItem(slot, candidate.inventory().get(slot));
        }
        player.containerMenu.setCarried(candidate.cursor());
        player.getInventory().setChanged();
        // 光标由原版关闭页面流程归还、丢弃及保存，不另建一份持久化物品。
    }

    public static CursorTransaction cursorTransaction(ServerPlayer player) {
        // 地面候选只在完整原版点击成功后落盘，中途失败由调用方恢复玩家与菜单快照。
        return new CursorTransaction(player);
    }

    public static void openScene(ServerPlayer player) {
        // 原版页面也通过同一会话取得距离内地面容器的独占访问权。
        VolumeServer.session(player).openNearbyGround();
    }

    private static GroundAccount cursorAccount(ServerPlayer player) {
        // 只使用当前连接已授权的根，不能根据客户端提交的容器 UUID 扩大访问范围。
        var session = VolumeServer.session(player);
        return new GroundAccount(player, session.accessibleGround().keySet());
    }

    private static boolean sameGround(Map<String, ItemStack> left, Map<String, ItemStack> right) {
        // ItemStack 没有按内容实现 equals，比较时必须使用原版 NBT 与数量语义。
        return left.keySet().equals(right.keySet())
                && left.entrySet().stream().allMatch(entry -> ItemStack.matches(entry.getValue(),
                right.get(entry.getKey())));
    }

    public static final class CursorTransaction {
        private final ServerPlayer player;
        private final GroundAccount account;
        private final Map<String, ItemStack> originalGround;
        private Map<String, ItemStack> ground;

        private CursorTransaction(ServerPlayer player) {
            // 租约在建立候选和最终提交时分别重新验证，不保存跨 tick 的事务。
            this.player = player;
            account = cursorAccount(player);
            originalGround = account.ground();
            ground = originalGround;
        }

        public ItemStack exchange(CursorTransfer.Request request) {
            // 每次交换使用前一步的地面候选，避免同一拖放跨容器时读回旧内容。
            var cursor = player.containerMenu.getCarried();
            var candidate = CursorTransfer.prepare(account, cursor, request,
                    stack -> VolumeServer.RULES.material(stack).size(), ground);
            applyCursor(player, candidate);
            ground = candidate.ground();
            return candidate.moved();
        }

        public void commit() {
            // 原版菜单先完成最终落点，再用现有地面日志同时提交玩家与地面库存。
            if (sameGround(ground, originalGround)) {
                return;
            }
            if (!player.containerMenu.getCarried().isEmpty()) {
                throw new IllegalArgumentException("请为地面物品选择最终落点");
            }
            try {
                account.commit(account.data(), account.inventory(), ground);
            } catch (IOException failure) {
                throw new IllegalArgumentException("地面背包保存失败", failure);
            }
        }
    }

    public static Snapshot snapshot(ServerPlayer player) {
        // 所有栈都是本次读取的副本；附属修改副本不会影响玩家或地面权威库存。
        if (!player.level().getServer().isSameThread()) {
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
