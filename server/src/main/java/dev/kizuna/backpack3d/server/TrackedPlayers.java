package dev.kizuna.backpack3d.server;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

/**
 * 每名玩家当前追踪着哪些玩家，由实体追踪事件维护。
 * 穿戴同步每 tick 都要知道「这位观察者看得见谁」，逐人现查追踪集合是玩家数的平方，这里只在追踪开始和结束时记一笔。
 */
final class TrackedPlayers {
    private static final Map<UUID, Set<UUID>> SEEN = new HashMap<>();

    private TrackedPlayers() {
        // 只有静态登记表。
    }

    static void register() {
        EntityTrackingEvents.START_TRACKING.register((entity, viewer) -> {
            if (entity instanceof ServerPlayer target) {
                started(viewer.getUUID(), target.getUUID());
            }
        });
        EntityTrackingEvents.STOP_TRACKING.register((entity, viewer) -> {
            if (entity instanceof ServerPlayer target) {
                stopped(viewer.getUUID(), target.getUUID());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> server.execute(
                () -> left(handler.player.getUUID())));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SEEN.clear());
    }

    static void started(UUID viewer, UUID target) {
        SEEN.computeIfAbsent(viewer, ignored -> new HashSet<>()).add(target);
    }

    static void stopped(UUID viewer, UUID target) {
        Set<UUID> targets = SEEN.get(viewer);
        if (targets == null) {
            return;
        }
        targets.remove(target);
        if (targets.isEmpty()) {
            SEEN.remove(viewer);
        }
    }

    static void left(UUID player) {
        // 下线的人不再看任何人，也不再被任何人看；原版会补发停止追踪，这里再清一遍不留残项。
        SEEN.remove(player);
        SEEN.values().forEach(targets -> targets.remove(player));
        SEEN.values().removeIf(Set::isEmpty);
    }

    /** viewer 当前追踪着的玩家；没有时为空集。返回的是只读视图，遍历期间不要触发追踪变化。 */
    static Set<UUID> seenBy(UUID viewer) {
        Set<UUID> targets = SEEN.get(viewer);
        if (targets == null) {
            return Set.of();
        }
        return java.util.Collections.unmodifiableSet(targets);
    }
}
