package dev.kizuna.backpack3d.server;

import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.Map;

/** 附属模组的只读边界；不暴露事务对象，也不提供写库存入口。 */
public final class InventoryAccess {
    private InventoryAccess() {}

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
