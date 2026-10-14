package dev.kizuna.backpack3d.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.EquipmentAppearance;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornModel;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class EquipmentAppearancesTest {
    private final EquipmentAppearances cache = new EquipmentAppearances();
    private final UUID player = UUID.randomUUID();

    @Test
    void differentPlayersNeverShareEquipment() {
        cache.accept(appearance(7, "minecraft:overworld"));
        assertEquals(1, cache.models(player, 7, "minecraft:overworld").size());
        assertTrue(cache.models(UUID.randomUUID(), 7, "minecraft:overworld").isEmpty());
    }

    @Test
    void respawnAndDimensionChangeCannotUseOldEntityAppearance() {
        cache.accept(appearance(7, "minecraft:overworld"));
        assertTrue(cache.models(player, 8, "minecraft:overworld").isEmpty());
        assertTrue(cache.models(player, 7, "minecraft:the_nether").isEmpty());
        cache.accept(appearance(8, "minecraft:the_nether"));
        assertEquals(1, cache.models(player, 8, "minecraft:the_nether").size());
        assertTrue(cache.models(player, 7, "minecraft:overworld").isEmpty());
    }

    @Test
    void unequipAndStopTrackingRemoveOldModels() {
        var equipped = appearance(7, "minecraft:overworld");
        cache.accept(equipped);
        cache.accept(equipped.toBuilder().clearModels().build());
        assertTrue(cache.models(player, 7, "minecraft:overworld").isEmpty());
        cache.accept(equipped);
        cache.accept(equipped.toBuilder().clearModels().setRemoved(true).build());
        assertTrue(cache.models(player, 7, "minecraft:overworld").isEmpty());
        cache.accept(equipped);
        assertEquals(1, cache.models(player, 7, "minecraft:overworld").size());
    }

    @Test
    void disconnectClearsAllPlayers() {
        cache.accept(appearance(7, "minecraft:overworld"));
        cache.clear();
        assertTrue(cache.models(player, 7, "minecraft:overworld").isEmpty());
    }

    private EquipmentAppearance appearance(int entityId, String dimension) {
        return EquipmentAppearance.newBuilder()
                .setPlayerUuid(player.toString())
                .setEntityId(entityId)
                .setDimension(dimension)
                .addModels(
                        WornModel.newBuilder()
                                .setItemType("bs3d:back_basket")
                                .setMount(Mount.TORSO_BACK))
                .build();
    }
}
