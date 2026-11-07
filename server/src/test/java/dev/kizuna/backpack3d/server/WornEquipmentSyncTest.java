package dev.kizuna.backpack3d.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.backpack3d.TestRegistries;
import dev.kizuna.backpack3d.TestWearables;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.WearableItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;
import dev.kizuna.backpack3d.service.TransferService;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class WornEquipmentSyncTest {
    // Bong 九件中的背后骨架背篓与腰挂灵草囊。
    private static final Identifier BASKET = Identifier.fromNamespaceAndPath("bs3d", "gu_jia_bei_lou");
    private static final Identifier POUCH = Identifier.fromNamespaceAndPath("bs3d", "herb_pouch");
    @BeforeAll
    static void bootstrap() {
        TestRegistries.bootstrap();
        TestWearables.register();
    }

    @Test
    void wearableSegmentsSyncWithoutIdentityOrPrivateStackAndClearOnRemoval() {
        ItemStack stack = WearableItems.create(TestWearables.CAPE);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("private-cloak-name"));
        String identity = WearableItems.identity(stack);
        var type = WearableItems.definition(stack);
        WornWearable worn = WornWearable.newBuilder()
                .setId(identity)
                .setStackNbt(StackCodec.encode(stack, TestRegistries.VANILLA))
                .addAllSlots(type.slots())
                .setBaseSize(Volumes.UNIT)
                .build();
        SavedInventory saved = TransferService.empty().toBuilder().addWornWearables(worn).build();
        UUID player = UUID.randomUUID();
        var appearance = WornEquipmentSync.appearance(player, 1, "minecraft:overworld", saved,
                TestRegistries.VANILLA);
        assertEquals(2, appearance.getModelsCount());
        assertEquals("hood", appearance.getModels(0).getWearableSlot().getSegment());
        assertEquals("back", appearance.getModels(1).getWearableSlot().getSegment());
        assertEquals(TestWearables.CAPE.toString(), appearance.getModels(0).getItemType());
        assertFalse(appearance.toString().contains(identity));
        assertFalse(appearance.toString().contains("private-cloak-name"));
        SavedInventory removed = saved.toBuilder().clearWornWearables().build();
        assertEquals(0, WornEquipmentSync.appearance(player, 1, "minecraft:overworld", removed,
                TestRegistries.VANILLA).getModelsCount());
    }

    @Test
    void appearanceContainsOnlyWornRootsAndDoesNotLeakContents() {
        var basket = ContainerItems.create(BASKET);
        var pouch = ContainerItems.create(POUCH);
        var privateItem = new ItemStack(Items.DIAMOND);
        privateItem.set(DataComponents.CUSTOM_NAME, Component.literal("private-name-must-not-be-broadcast"));
        ContainerItems.setContents(
                basket,
                List.of(
                        Item.newBuilder()
                                .setId(UUID.randomUUID().toString())
                                .setStackNbt(StackCodec.encode(pouch, TestRegistries.VANILLA))
                                .build(),
                        Item.newBuilder()
                                .setId(UUID.randomUUID().toString())
                                .setStackNbt(StackCodec.encode(privateItem, TestRegistries.VANILLA))
                                .build()));
        var saved =
                TransferService.empty().toBuilder()
                        .addWorn(
                                WornContainer.newBuilder()
                                        .setStackNbt(StackCodec.encode(basket, TestRegistries.VANILLA))
                                        .setMount(Mount.TORSO_BACK))
                        .build();
        var player = UUID.randomUUID();
        var appearance = WornEquipmentSync.appearance(player, 12, "minecraft:overworld", saved, TestRegistries.VANILLA);
        assertEquals(1, appearance.getModelsCount());
        assertEquals(BASKET.toString(), appearance.getModels(0).getItemType());
        assertEquals(Mount.TORSO_BACK, appearance.getModels(0).getMount());
        assertFalse(appearance.toString().contains(ContainerItems.identity(basket)));
        assertFalse(appearance.toString().contains("private-name"));
        // 内容发生变化但挂点不变时，网络外观应完全相同，不触发额外广播。
        ContainerItems.setContents(basket, List.of());
        var emptied =
                saved.toBuilder()
                        .setWorn(
                                0,
                                saved.getWorn(0).toBuilder().setStackNbt(StackCodec.encode(basket, TestRegistries.VANILLA)))
                        .build();
        assertEquals(
                appearance,
                WornEquipmentSync.appearance(player, 12, "minecraft:overworld", emptied, TestRegistries.VANILLA));
    }

    @Test
    void waistAngleAndUnequipAreAuthoritativeSnapshots() {
        var saved =
                TransferService.empty().toBuilder()
                        .addWorn(
                                WornContainer.newBuilder()
                                        .setStackNbt(
                                                StackCodec.encode(
                                                        ContainerItems.create(POUCH), TestRegistries.VANILLA))
                                        .setMount(Mount.WAIST_LEFT_FRONT)
                                        .setWaistAngleDegrees(117.5f))
                        .build();
        var player = UUID.randomUUID();
        var appearance = WornEquipmentSync.appearance(player, 1, "minecraft:overworld", saved, TestRegistries.VANILLA);
        assertEquals(117.5f, appearance.getModels(0).getWaistAngleDegrees());
        assertEquals(
                0,
                WornEquipmentSync.appearance(
                                player,
                                1,
                                "minecraft:overworld",
                                saved.toBuilder().clearWorn().build(), TestRegistries.VANILLA)
                        .getModelsCount());
    }
}
