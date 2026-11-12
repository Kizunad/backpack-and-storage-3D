package dev.kizuna.backpack3d.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrackedPlayersTest {
    @Test
    void trackingIsRecordedPerViewerAndClearedWhenEitherSideLeaves() {
        // 追踪是有方向的；任一方下线后两边都不留残项。
        UUID viewer = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        TrackedPlayers.started(viewer, first);
        TrackedPlayers.started(viewer, second);
        TrackedPlayers.started(first, viewer);
        assertEquals(Set.of(first, second), TrackedPlayers.seenBy(viewer));
        assertEquals(Set.of(viewer), TrackedPlayers.seenBy(first));
        assertTrue(TrackedPlayers.seenBy(second).isEmpty());

        TrackedPlayers.stopped(viewer, second);
        assertEquals(Set.of(first), TrackedPlayers.seenBy(viewer));

        TrackedPlayers.left(first);
        assertTrue(TrackedPlayers.seenBy(viewer).isEmpty());
        assertTrue(TrackedPlayers.seenBy(first).isEmpty());
        TrackedPlayers.stopped(viewer, first);
        assertTrue(TrackedPlayers.seenBy(viewer).isEmpty());
    }
}
