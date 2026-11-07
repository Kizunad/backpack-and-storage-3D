package dev.kizuna.backpack3d.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.backpack3d.TestContainers;
import dev.kizuna.backpack3d.TestRegistries;
import dev.kizuna.backpack3d.TestWearables;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.WearableItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.PlayerSlots;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 验证真实栈穿脱、收纳、重启、光标与失败回滚共用同一事务。 */
class WearableTransferTest {
    private static final Size STORED_SIZE = Volumes.size(1, 2, 1);
    private static final int LEGACY_SAVE_VERSION = 3;
    private final TransferService service = new TransferService(stack -> STORED_SIZE);
    private final MemoryAccount account = new MemoryAccount();

    @BeforeAll
    static void bootstrap() {
        TestRegistries.bootstrap();
        TestWearables.register();
    }

    @Test
    void freshHandEquipmentUsesOriginalFingerprintAndInitializesOnlyCandidate() throws IOException {
        // 来源指纹对应服务器原栈，不能在候选初始化 UUID 之后再用新指纹拒绝自己的请求。
        var fresh = new ItemStack(BuiltInRegistries.ITEM.getValue(TestWearables.HEAD));
        account.inventory.set(0, fresh);
        MoveRequest request = wearRequest("head");
        service.prepare(account, request);
        assertFalse(fresh.has(DataComponents.CUSTOM_DATA));
        service.commit(account, request);
        assertTrue(account.inventory.getFirst().isEmpty());
        assertEquals(1, account.data.getWornWearablesCount());
        assertFalse(account.data.getWornWearables(0).getId().isEmpty());
    }

    @Test
    void corruptWearableIdentityIsRejectedWithoutReplacingData() {
        // 类型错误的持久子标签不能被“首次初始化”静默覆盖。
        var corrupt = new ItemStack(BuiltInRegistries.ITEM.getValue(TestWearables.HEAD));
        CustomData.update(DataComponents.CUSTOM_DATA, corrupt, root -> root.putString("bs3d_wearable", "broken"));
        var before = corrupt.copy();
        account.inventory.set(0, corrupt);
        MoveRequest request = wearRequest("head");
        assertThrows(IllegalArgumentException.class, () -> service.prepare(account, request));
        assertTrue(ItemStack.matches(before, account.inventory.getFirst()));
        assertEquals(0, account.data.getWornWearablesCount());
    }

    @Test
    void wearSaveReplayAndRemoveKeepOneRealStackAndItsStorageSize() throws Exception {
        ItemStack original = WearableItems.create(TestWearables.HEAD);
        original.set(DataComponents.CUSTOM_NAME, Component.literal("保留的头巾"));
        String id = WearableItems.identity(original);
        account.inventory.set(0, original);
        MoveRequest request = wearRequest("head");
        service.prepare(account, request);
        assertTrue(ItemStack.matches(original, account.inventory.getFirst()));
        assertEquals(0, account.data.getWornWearablesCount());
        service.commit(account, request);
        service.commit(account, request);
        assertEquals(1, account.commits);
        assertTrue(account.inventory.getFirst().isEmpty());
        account.data = SavedInventory.parseFrom(account.data.toByteArray());
        assertEquals(id, account.data.getWornWearables(0).getId());
        assertEquals(STORED_SIZE, account.data.getWornWearables(0).getBaseSize());
        assertTrue(tree().containers().isEmpty());
        assertEquals(id, tree().wearables().getFirst().getId());
        assertEquals(STORED_SIZE, tree().items().getFirst().getBaseSize());
        service.commit(account, move(id, Volumes.PLAYER).setTargetPlayerSlot(Inventory.SLOT_OFFHAND).build());
        assertTrue(ItemStack.matches(original, account.inventory.get(Inventory.SLOT_OFFHAND)));
        assertEquals(0, account.data.getWornWearablesCount());
    }

    @Test
    void storedWearableCanBeEquippedAgainAfterContainerSerialization() throws IOException {
        ItemStack bag = ContainerItems.create(TestContainers.BASKET);
        String bagId = ContainerItems.identity(bag);
        account.inventory.set(1, bag);
        String id = wear(TestWearables.WRISTS, "right_wrist");
        service.commit(account, move(id, bagId).build());
        assertEquals(0, account.data.getWornWearablesCount());
        ItemStack packed = account.inventory.get(1);
        account.inventory.set(1, StackCodec.decode(StackCodec.encode(packed, account.registries()),
                account.registries()));
        Placement placement = placement("left_wrist");
        service.commit(account, move(id, Volumes.WORN).setWornTarget(placement).build());
        assertEquals(1, account.data.getWornWearablesCount());
        assertEquals(2, account.data.getWornWearables(0).getSlotsCount());
        assertEquals(STORED_SIZE, account.data.getWornWearables(0).getBaseSize());
        assertEquals(0, ContainerItems.contents(account.inventory.get(1)).getItemsCount());
    }

    @Test
    void headEyesFaceAndCapeStackAlongsideBackpack() throws IOException {
        ItemStack bag = ContainerItems.create(TestContainers.BASKET);
        account.inventory.set(0, bag);
        Placement back = Placement.newBuilder().setMount(Mount.TORSO_BACK).build();
        service.commit(account, fromHand(Volumes.WORN).setWornTarget(back).build());
        wear(TestWearables.HEAD, "head");
        wear(TestWearables.EYES, "eyes");
        wear(TestWearables.FACE, "face");
        wear(TestWearables.CAPE, "cape");
        assertEquals(4, account.data.getWornWearablesCount());
        assertEquals(1, account.data.getWornCount());
        assertEquals(4, tree().wearables().size());
        assertEquals(1, tree().containers().size());
    }

    @Test
    void layerConflictsRejectWholeMultiSlotItemAndKeepReceipts() throws IOException {
        wear(TestWearables.LEFT_WRIST, "left_wrist");
        account.inventory.set(0, WearableItems.create(TestWearables.WRISTS));
        SavedInventory before = account.data;
        ItemStack held = account.inventory.getFirst().copy();
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, wearRequest("right_wrist")));
        assertSame(before, account.data);
        assertTrue(ItemStack.matches(held, account.inventory.getFirst()));
        assertEquals(1, tree().wearables().size());
    }

    @Test
    void shoulderAndBodyArmorConflictInBothOrders() throws IOException {
        String shoulder = wear(TestWearables.SHOULDER, "left_shoulder");
        account.inventory.set(0, WearableItems.create(TestWearables.ARMOR));
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, wearRequest("armor_body")));
        service.commit(account, move(shoulder, Volumes.PLAYER).setTargetPlayerSlot(Inventory.SLOT_OFFHAND).build());
        service.commit(account, wearRequest("armor_body"));
        account.inventory.set(0, account.inventory.get(Inventory.SLOT_OFFHAND));
        account.inventory.set(Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, wearRequest("left_shoulder")));
    }

    @Test
    void vanillaArmorRejectsWearableBeforeInventoryOrReceiptsChange() {
        int chest = PlayerSlots.armorSlot(EquipmentSlot.CHEST);
        account.inventory.set(chest, new ItemStack(Items.IRON_CHESTPLATE));
        account.inventory.set(0, WearableItems.create(TestWearables.ARMOR));
        SavedInventory before = account.data;
        var original = account.inventory.stream().map(ItemStack::copy).toList();
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, wearRequest("armor_body")));
        assertSame(before, account.data);
        assertInventoryMatches(original);
        assertEquals(0, account.commits);
    }

    @Test
    void wearableArmorRejectsVanillaTargetAndAllowsOtherRegions() throws IOException {
        wear(TestWearables.ARMOR, "armor_body");
        account.inventory.set(0, new ItemStack(Items.IRON_CHESTPLATE));
        SavedInventory before = account.data;
        var original = account.inventory.stream().map(ItemStack::copy).toList();
        int chest = PlayerSlots.armorSlot(EquipmentSlot.CHEST);
        MoveRequest request = fromHand(Volumes.PLAYER).setTargetPlayerSlot(chest).build();
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, request));
        assertSame(before, account.data);
        assertInventoryMatches(original);
        account.inventory.set(0, new ItemStack(Items.IRON_HELMET));
        int head = PlayerSlots.armorSlot(EquipmentSlot.HEAD);
        service.commit(account, fromHand(Volumes.PLAYER).setTargetPlayerSlot(head).build());
        assertTrue(account.inventory.get(head).is(Items.IRON_HELMET));
        assertEquals(1, account.data.getWornWearablesCount());
    }

    @Test
    void restoredMixedArmorIsRejectedWithoutMutatingEitherSource() throws IOException {
        wear(TestWearables.ARMOR, "armor_body");
        account.inventory.set(PlayerSlots.armorSlot(EquipmentSlot.CHEST), new ItemStack(Items.IRON_CHESTPLATE));
        SavedInventory before = account.data;
        var original = account.inventory.stream().map(ItemStack::copy).toList();
        assertThrows(IllegalArgumentException.class, this::tree);
        assertSame(before, account.data);
        assertInventoryMatches(original);
    }

    private void assertInventoryMatches(List<ItemStack> expected) {
        for (int index = 0; index < expected.size(); index++) {
            assertTrue(ItemStack.matches(expected.get(index), account.inventory.get(index)), "slot " + index);
        }
    }

    @Test
    void forgedMountLayerAndOrdinaryItemCannotEnterWearableSlots() {
        account.inventory.set(0, WearableItems.create(TestWearables.HEAD));
        assertThrows(IllegalArgumentException.class, () -> service.prepare(account, wearRequest("cape")));
        Placement invalid = placement("head").toBuilder().setMount(Mount.TORSO_BACK).build();
        MoveRequest invalidMount = fromHand(Volumes.WORN).setWornTarget(invalid).build();
        assertThrows(IllegalArgumentException.class, () -> service.prepare(account, invalidMount));
        account.inventory.set(0, new ItemStack(Items.STONE));
        assertThrows(IllegalArgumentException.class, () -> service.prepare(account, wearRequest("head")));
        assertEquals(0, account.commits);
    }

    @Test
    void failedCommitAndFullDestinationLeaveWearableAtOriginalLocation() throws IOException {
        String id = wear(TestWearables.HEAD, "head");
        SavedInventory before = account.data;
        account.failCommit = true;
        MoveRequest request = move(id, Volumes.PLAYER).build();
        assertThrows(IOException.class, () -> service.commit(account, request));
        assertSame(before, account.data);
        account.failCommit = false;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack full = new ItemStack(Items.STONE);
            full.setCount(full.getMaxStackSize());
            account.inventory.set(slot, full);
        }
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, request));
        assertSame(before, account.data);
    }

    @Test
    void cursorTakeRemovesWornRootAndKeepsIdentity() throws IOException {
        String id = wear(TestWearables.CAPE, "cape");
        var request = CursorTransfer.Request.take(account.data.getRevision(), id);
        var candidate = CursorTransfer.prepare(account, ItemStack.EMPTY, request, stack -> STORED_SIZE);
        assertEquals(0, candidate.data().getWornWearablesCount());
        assertEquals(id, WearableItems.identity(candidate.cursor()));
        assertEquals(1, account.data.getWornWearablesCount());
    }

    @Test
    void cursorCanWearAWholePairAndTakeItBackWithoutMutatingTheInput() {
        ItemStack cursor = WearableItems.create(TestWearables.WRISTS);
        cursor.set(DataComponents.CUSTOM_NAME, Component.literal("成对护腕"));
        var request = CursorTransfer.Request.wear(account.data.getRevision(), placement("left_wrist"));
        var candidate = CursorTransfer.prepare(account, cursor, request, stack -> STORED_SIZE);
        assertEquals(0, account.data.getWornWearablesCount());
        assertFalse(cursor.isEmpty());
        assertTrue(candidate.cursor().isEmpty());
        assertEquals(2, candidate.data().getWornWearables(0).getSlotsCount());
        account.data = candidate.data();
        account.inventory = new ArrayList<>(candidate.inventory());
        String id = WearableItems.identity(cursor);
        var take = CursorTransfer.Request.take(account.data.getRevision(), id);
        var removed = CursorTransfer.prepare(account, ItemStack.EMPTY, take, stack -> STORED_SIZE);
        assertTrue(ItemStack.matches(cursor, removed.cursor()));
        assertEquals(0, removed.data().getWornWearablesCount());
    }

    @Test
    void cursorWearRejectsOccupiedLayerAndVanillaArmorWithoutConsumingCursor() throws IOException {
        wear(TestWearables.HEAD, "head");
        ItemStack cursor = WearableItems.create(TestWearables.HEAD);
        var saved = account.data;
        var occupied = CursorTransfer.Request.wear(saved.getRevision(), placement("head"));
        assertThrows(IllegalArgumentException.class,
                () -> CursorTransfer.prepare(account, cursor, occupied, stack -> STORED_SIZE));
        assertSame(saved, account.data);
        assertFalse(cursor.isEmpty());
        account.inventory.set(PlayerSlots.armorSlot(EquipmentSlot.CHEST), new ItemStack(Items.IRON_CHESTPLATE));
        ItemStack armor = WearableItems.create(TestWearables.ARMOR);
        var conflict = CursorTransfer.Request.wear(saved.getRevision(), placement("armor_body"));
        assertThrows(IllegalArgumentException.class,
                () -> CursorTransfer.prepare(account, armor, conflict, stack -> STORED_SIZE));
        assertSame(saved, account.data);
        assertFalse(armor.isEmpty());
    }

    @Test
    void duplicateIdentityAndForgedSavedLayersAreRejected() throws IOException {
        wear(TestWearables.HEAD, "head");
        WornWearable root = account.data.getWornWearables(0);
        account.inventory.set(0, StackCodec.decode(root.getStackNbt(), account.registries()));
        assertThrows(IllegalArgumentException.class, this::tree);
        WearableSlot invalid = root.getSlots(0).toBuilder().setLayer("cape").build();
        WornWearable forged = root.toBuilder().setSlots(0, invalid).build();
        SavedInventory invalidSave = account.data.toBuilder().setWornWearables(0, forged).build();
        assertThrows(IllegalArgumentException.class, () -> TransferService.validate(invalidSave, account.registries()));
    }

    @Test
    void legacyContainerSaveRemainsReadableAndUpgradesOnTransfer() throws IOException {
        account.data = TestContainers.account().toBuilder().setFormatVersion(LEGACY_SAVE_VERSION).build();
        TransferService.validate(account.data, account.registries());
        wear(TestWearables.HEAD, "head");
        assertEquals(TransferService.SAVE_VERSION, account.data.getFormatVersion());
        assertEquals(2, account.data.getWornCount());
    }

    private String wear(Identifier type, String layer) throws IOException {
        ItemStack stack = WearableItems.create(type);
        account.inventory.set(0, stack);
        String id = WearableItems.identity(stack);
        service.commit(account, wearRequest(layer));
        return id;
    }

    private MoveRequest wearRequest(String layer) {
        return fromHand(Volumes.WORN).setWornTarget(placement(layer)).build();
    }

    private static Placement placement(String layer) {
        return Placement.newBuilder().setWearableLayer(layer).build();
    }

    private MoveRequest.Builder fromHand(String target) {
        return move("", target)
                .setPlayerSlot(0)
                .setStackFingerprint(StackCodec.fingerprint(StackCodec.encode(
                        account.inventory.getFirst(), account.registries())));
    }

    private MoveRequest.Builder move(String id, String target) {
        return MoveRequest.newBuilder()
                .setOperation(UUID.randomUUID().toString())
                .setRevision(account.data.getRevision())
                .setItem(id)
                .setTarget(target)
                .setAutoPlace(true /* autoPlace */);
    }

    private ContainerTree tree() {
        return new ContainerTree(account.registries(), account.data, new ArrayList<>(account.inventory));
    }

    private static final class MemoryAccount implements Account {
        private SavedInventory data = TransferService.empty();
        private List<ItemStack> inventory = new ArrayList<>(
                Collections.nCopies(Inventory.SLOT_OFFHAND + 1, ItemStack.EMPTY));
        private boolean failCommit;
        private int commits;

        public SavedInventory data() { return data; }
        public List<ItemStack> inventory() { return inventory; }
        public int selectedSlot() { return 0; }
        public boolean creative() { return false; }
        public HolderLookup.Provider registries() { return TestRegistries.VANILLA; }

        public void commit(SavedInventory next, List<ItemStack> vanilla) throws IOException {
            // 模拟磁盘失败时，候选与原栈不得提前生效。
            if (failCommit) {
                throw new IOException("simulated disk failure");
            }
            data = next;
            inventory = new ArrayList<>(vanilla);
            commits++;
        }
    }
}
