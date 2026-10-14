package dev.kizuna.backpack3d.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;
import dev.kizuna.backpack3d.service.TransferService;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class WornEquipmentSyncTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        ContainerItems.initialize();
    }

    @Test
    void appearanceContainsOnlyWornRootsAndDoesNotLeakContents() {
        var basket = ContainerItems.create(ContainerItems.BASKET);
        var pouch = ContainerItems.create(ContainerItems.POUCH);
        var privateItem = new ItemStack(Items.DIAMOND);
        privateItem.setCustomName(Text.literal("private-name-must-not-be-broadcast"));
        ContainerItems.setContents(
                basket,
                List.of(
                        Item.newBuilder()
                                .setId(UUID.randomUUID().toString())
                                .setStackNbt(StackCodec.encode(pouch))
                                .build(),
                        Item.newBuilder()
                                .setId(UUID.randomUUID().toString())
                                .setStackNbt(StackCodec.encode(privateItem))
                                .build()));
        var saved =
                TransferService.empty().toBuilder()
                        .addWorn(
                                WornContainer.newBuilder()
                                        .setStackNbt(StackCodec.encode(basket))
                                        .setMount(Mount.TORSO_BACK))
                        .build();
        var player = UUID.randomUUID();
        var appearance = WornEquipmentSync.appearance(player, 12, "minecraft:overworld", saved);
        assertEquals(1, appearance.getModelsCount());
        assertEquals("bs3d:back_basket", appearance.getModels(0).getItemType());
        assertEquals(Mount.TORSO_BACK, appearance.getModels(0).getMount());
        assertFalse(appearance.toString().contains(ContainerItems.identity(basket)));
        assertFalse(appearance.toString().contains("private-name"));
        // 内容发生变化但挂点不变时，网络外观应完全相同，不触发额外广播。
        ContainerItems.setContents(basket, List.of());
        var emptied =
                saved.toBuilder()
                        .setWorn(
                                0,
                                saved.getWorn(0).toBuilder().setStackNbt(StackCodec.encode(basket)))
                        .build();
        assertEquals(
                appearance,
                WornEquipmentSync.appearance(player, 12, "minecraft:overworld", emptied));
    }

    @Test
    void waistAngleAndUnequipAreAuthoritativeSnapshots() {
        var saved =
                TransferService.empty().toBuilder()
                        .addWorn(
                                WornContainer.newBuilder()
                                        .setStackNbt(
                                                StackCodec.encode(
                                                        ContainerItems.create(
                                                                ContainerItems.POUCH)))
                                        .setMount(Mount.WAIST_LEFT_FRONT)
                                        .setWaistAngleDegrees(117.5f))
                        .build();
        var player = UUID.randomUUID();
        var appearance = WornEquipmentSync.appearance(player, 1, "minecraft:overworld", saved);
        assertEquals(117.5f, appearance.getModels(0).getWaistAngleDegrees());
        assertEquals(
                0,
                WornEquipmentSync.appearance(
                                player,
                                1,
                                "minecraft:overworld",
                                saved.toBuilder().clearWorn().build())
                        .getModelsCount());
    }
}
