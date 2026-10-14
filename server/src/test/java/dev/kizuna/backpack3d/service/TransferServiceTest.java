package dev.kizuna.backpack3d.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.*;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.*;

class TransferServiceTest {
    private final TransferService service =
            new TransferService(
                    stack -> stack.isOf(Items.IRON_SWORD) ? Volumes.size(1, 2, 1) : Volumes.UNIT);
    private final MemoryAccount account = new MemoryAccount();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        ContainerItems.initialize();
    }

    @Test
    void preparationAndCancellationLeaveBothInventoriesUntouched() {
        account.inventory.set(0, new ItemStack(Items.IRON_SWORD));
        var before = account.data;
        var plan = service.prepare(account, deposit(Volumes.POUCH));
        assertEquals(1, stored(plan.data()).size());
        assertSame(before, account.data);
        assertTrue(account.inventory.get(0).isOf(Items.IRON_SWORD));
        // 丢弃候选即取消，不需要回滚已经挪动的栈。
        assertEquals(0, account.commits);
    }

    @Test
    void completionPreservesNbtAndReplayDoesNotDuplicate() throws IOException {
        var sword = new ItemStack(Items.IRON_SWORD);
        sword.setCustomName(Text.literal("带名字的剑"));
        sword.setDamage(17);
        account.inventory.set(0, sword);
        var request = deposit(Volumes.POUCH);
        service.commit(account, request);
        service.commit(account, request);
        assertEquals(1, account.commits);
        assertEquals(1, account.data.getRevision());
        assertTrue(account.inventory.get(0).isEmpty());
        assertTrue(
                ItemStack.areEqual(
                        sword, StackCodec.decode(stored(account.data).get(0).getStackNbt())));
    }

    @Test
    void stackChangedDuringWaitRejectsBeforeCommit() {
        account.inventory.set(0, new ItemStack(Items.STONE, 16));
        var request = deposit(Volumes.BASKET);
        service.prepare(account, request);
        account.inventory.set(0, new ItemStack(Items.DIRT));
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, request));
        assertEquals(0, account.commits);
        assertTrue(account.inventory.get(0).isOf(Items.DIRT));
    }

    @Test
    void unrelatedVanillaChangeDuringWaitIsPreserved() throws IOException {
        account.inventory.set(0, new ItemStack(Items.STONE));
        var request = deposit(Volumes.BASKET);
        service.prepare(account, request);
        account.inventory.set(1, new ItemStack(Items.APPLE, 7));
        service.commit(account, request);
        assertEquals(7, account.inventory.get(1).getCount());
    }

    @Test
    void crossContainerMoveMaintainsInstanceIdentity() throws IOException {
        account.inventory.set(0, new ItemStack(Items.IRON_SWORD));
        service.commit(account, deposit(Volumes.POUCH));
        var item = stored(account.data).get(0);
        var move = move(item.getId(), Volumes.BASKET);
        service.commit(account, move);
        assertEquals(item.getId(), stored(account.data).get(0).getId());
        assertEquals(bag(Volumes.BASKET), stored(account.data).get(0).getContainer());
        assertEquals(1, stored(account.data).size());
    }

    @Test
    void threeAxesConstrainCapacityAndTouchingEdgesAreAllowed() {
        var item =
                Item.newBuilder()
                        .setId("a")
                        .setContainer(Volumes.POUCH)
                        .setSize(Volumes.size(1, 2, 1))
                        .setCell(Volumes.cell(0, 0, 0))
                        .build();
        assertTrue(
                Volumes.fits(
                        List.of(item), Volumes.POUCH, Volumes.cell(0, 0, 1), item.getSize(), ""));
        assertFalse(
                Volumes.fits(
                        List.of(item), Volumes.POUCH, Volumes.cell(0, 1, 1), item.getSize(), ""));
        assertFalse(
                Volumes.fits(
                        List.of(item), Volumes.POUCH, Volumes.cell(1, 0, 0), Volumes.UNIT, ""));
        assertFalse(
                Volumes.fits(
                        List.of(item), Volumes.POUCH, Volumes.cell(0, 0, 2), Volumes.UNIT, ""));
        assertFalse(
                Volumes.fits(
                        List.of(item),
                        Volumes.POUCH,
                        Volumes.cell(Integer.MAX_VALUE, 0, 0),
                        Volumes.UNIT,
                        ""));
    }

    @Test
    void fullTargetNeverRemovesSource() throws IOException {
        for (int index = 0; index < 4; index++) {
            account.inventory.set(0, new ItemStack(Items.STONE));
            service.commit(account, deposit(Volumes.POUCH));
        }
        account.inventory.set(0, new ItemStack(Items.APPLE));
        var request = deposit(Volumes.POUCH);
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, request));
        assertEquals(4, stored(account.data).size());
        assertTrue(account.inventory.get(0).isOf(Items.APPLE));
    }

    @Test
    void failedPersistenceLeavesMemoryAndReceiptUnchanged() {
        account.inventory.set(0, new ItemStack(Items.STONE));
        account.failCommit = true;
        assertThrows(IOException.class, () -> service.commit(account, deposit(Volumes.POUCH)));
        assertEquals(0, account.data.getRevision());
        assertEquals(0, account.data.getReceiptsCount());
        assertTrue(account.inventory.get(0).isOf(Items.STONE));
    }

    @Test
    void fullVanillaInventoryRejectsWithdrawal() throws IOException {
        account.inventory.set(0, new ItemStack(Items.IRON_SWORD));
        service.commit(account, deposit(Volumes.POUCH));
        Collections.fill(account.inventory, new ItemStack(Items.DIRT, 64));
        var request = move(stored(account.data).get(0).getId(), Volumes.PLAYER);
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, request));
        assertEquals(1, stored(account.data).size());
    }

    @Test
    void sameOperationWithDifferentIntentIsRejected() throws IOException {
        account.inventory.set(0, new ItemStack(Items.STONE));
        var request = deposit(Volumes.POUCH);
        service.commit(account, request);
        var changed = request.toBuilder().setTarget(Volumes.BASKET).build();
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, changed));
    }

    @Test
    void malformedReceiptIsNotTreatedAsCommitted() {
        var invalid =
                TransferService.empty().toBuilder()
                        .addReceipts(Receipt.newBuilder().setOperation("x"))
                        .build();
        assertThrows(IllegalArgumentException.class, () -> TransferService.validate(invalid));
    }

    @Test
    void allRotationsPreserveVolumeAndHaveInverse() {
        Set<String> orientations = new HashSet<>();
        for (int index = 0; index < OrthogonalRotation.COUNT; index++) {
            orientations.add(Arrays.toString(OrthogonalRotation.matrix(index)));
            var size = OrthogonalRotation.size(Volumes.size(1, 2, 3), index);
            assertEquals(6, size.getWidth() * size.getHeight() * size.getDepth());
            for (boolean vertical : List.of(false, true)) {
                assertEquals(
                        index,
                        OrthogonalRotation.turn(
                                OrthogonalRotation.turn(index, vertical, false), vertical, true));
                int full = index;
                for (int step = 0; step < 4; step++) {
                    full = OrthogonalRotation.turn(full, vertical, false);
                }
                assertEquals(index, full);
            }
        }
        assertEquals(24, orientations.size());
        assertNotEquals(
                OrthogonalRotation.turn(OrthogonalRotation.turn(0, true, false), false, false),
                OrthogonalRotation.turn(OrthogonalRotation.turn(0, false, false), true, false));
        assertThrows(IllegalArgumentException.class, () -> OrthogonalRotation.require(-1));
        assertThrows(IllegalArgumentException.class, () -> OrthogonalRotation.require(24));
    }

    @Test
    void rotationCommitsAtSameCellAndRetainsNbt() throws IOException {
        account.inventory.set(0, new ItemStack(Items.IRON_SWORD));
        service.commit(account, deposit(Volumes.POUCH));
        var before = stored(account.data).get(0);
        int horizontal = OrthogonalRotation.turn(0, true, false);
        var move =
                move(before.getId(), Volumes.POUCH).toBuilder()
                        .setAutoPlace(false)
                        .setCell(before.getCell())
                        .setOrientation(horizontal)
                        .build();
        var candidate = service.prepare(account, move);
        assertEquals(Volumes.size(1, 1, 2), stored(candidate.data()).get(0).getSize());
        assertEquals(0, stored(account.data).get(0).getOrientation());
        service.commit(account, move);
        service.commit(account, move);
        var after = stored(account.data).get(0);
        assertEquals(horizontal, after.getOrientation());
        assertEquals(before.getStackNbt(), after.getStackNbt());
        assertEquals(2, account.commits);
    }

    @Test
    void rotatedBoundaryAndCollisionRejectWithoutMutation() throws IOException {
        account.inventory.set(0, new ItemStack(Items.IRON_SWORD));
        service.commit(account, deposit(Volumes.POUCH));
        var sword = stored(account.data).get(0);
        account.inventory.set(0, new ItemStack(Items.STONE));
        service.commit(account, deposit(Volumes.POUCH));
        int pitch = OrthogonalRotation.turn(0, true, false);
        int sideways = OrthogonalRotation.turn(pitch, false, false);
        var before = account.data;
        var collision =
                move(sword.getId(), Volumes.POUCH).toBuilder()
                        .setAutoPlace(false)
                        .setCell(Volumes.cell(0, 0, 0))
                        .setOrientation(pitch)
                        .build();
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, collision));
        var boundary = collision.toBuilder().setOrientation(sideways).build();
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, boundary));
        assertSame(before, account.data);
    }

    @Test
    void legacySaveUpgradesOnFirstRotationAndInvalidGeometryIsRejected() throws IOException {
        account.inventory.set(0, new ItemStack(Items.IRON_SWORD));
        service.commit(account, deposit(Volumes.POUCH));
        var old =
                stored(account.data).get(0).toBuilder()
                        .clearBaseSize()
                        .setContainer(Volumes.POUCH)
                        .build();
        var legacy = SavedInventory.newBuilder().setFormatVersion(1).addItems(old).build();
        account.data = TransferService.upgrade(legacy);
        service.commit(
                account,
                move(old.getId(), Volumes.POUCH).toBuilder()
                        .setOrientation(OrthogonalRotation.turn(0, true, false))
                        .build());
        assertEquals(TransferService.SAVE_VERSION, account.data.getFormatVersion());
        var bad = legacy.toBuilder().setItems(0, old.toBuilder().setOrientation(1)).build();
        assertThrows(IllegalArgumentException.class, () -> TransferService.validate(bad));
    }

    @Test
    void equipmentValidatesMountRevisionAndPersistsWithoutMovingItems() throws IOException {
        account.inventory.set(0, new ItemStack(Items.STONE));
        service.commit(account, deposit(Volumes.BASKET));
        var items = stored(account.data);
        var equip =
                EquipRequest.newBuilder()
                        .setRevision(account.data.getRevision())
                        .setPlacement(
                                Placement.newBuilder()
                                        .setContainer(bag(Volumes.BASKET))
                                        .setMount(Mount.TORSO_FRONT))
                        .build();
        service.equip(account, equip);
        service.equip(account, equip);
        assertEquals(2, account.commits);
        assertEquals(items, stored(account.data));
        assertEquals(
                Mount.TORSO_FRONT,
                new ContainerTree(account.data, new ArrayList<>())
                        .containers().stream()
                                .filter(value -> value.getId().equals(bag(Volumes.BASKET)))
                                .findFirst()
                                .orElseThrow()
                                .getMount());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.equip(
                                account,
                                equip.toBuilder()
                                        .setPlacement(
                                                equip.getPlacement().toBuilder()
                                                        .setMount(Mount.WAIST_LEFT_BACK))
                                        .build()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.equip(
                                account,
                                equip.toBuilder()
                                        .setPlacement(
                                                equip.getPlacement().toBuilder()
                                                        .setMount(Mount.TORSO_BACK))
                                        .build()));
    }

    @Test
    void offhandRoundTripPreservesNbtAndOtherEquipment() throws IOException {
        var sword = new ItemStack(Items.IRON_SWORD);
        sword.setDamage(23);
        sword.setCustomName(Text.literal("左手的剑"));
        account.inventory.set(PlayerInventory.OFF_HAND_SLOT, sword);
        account.inventory.set(PlayerInventory.MAIN_SIZE, new ItemStack(Items.IRON_BOOTS));
        account.inventory.set(2, new ItemStack(Items.APPLE, 5));
        var deposit =
                deposit(Volumes.POUCH, PlayerInventory.OFF_HAND_SLOT).toBuilder()
                        .setAutoPlace(false)
                        .setCell(Volumes.cell(0, 0, 1))
                        .build();
        service.prepare(account, deposit);
        assertTrue(ItemStack.areEqual(sword, account.inventory.get(PlayerInventory.OFF_HAND_SLOT)));
        service.commit(account, deposit);
        assertTrue(account.inventory.get(PlayerInventory.OFF_HAND_SLOT).isEmpty());
        var stored = stored(account.data).get(0);
        assertEquals(Volumes.cell(0, 0, 1), stored.getCell());
        var withdraw =
                move(stored.getId(), Volumes.PLAYER).toBuilder()
                        .setTargetPlayerSlot(PlayerInventory.OFF_HAND_SLOT)
                        .build();
        service.commit(account, withdraw);
        service.commit(account, withdraw);
        assertTrue(ItemStack.areEqual(sword, account.inventory.get(PlayerInventory.OFF_HAND_SLOT)));
        assertEquals(0, stored(account.data).size());
        assertEquals(2, account.commits);
        assertTrue(account.inventory.get(PlayerInventory.MAIN_SIZE).isOf(Items.IRON_BOOTS));
        assertEquals(5, account.inventory.get(2).getCount());
    }

    @Test
    void withdrawalUsesChosenHandInsteadOfFirstFreeSlot() throws IOException {
        account.inventory.set(0, new ItemStack(Items.IRON_SWORD));
        service.commit(account, deposit(Volumes.POUCH));
        account.selectedSlot = 4;
        service.commit(
                account,
                move(stored(account.data).get(0).getId(), Volumes.PLAYER).toBuilder()
                        .setTargetPlayerSlot(4)
                        .build());
        assertTrue(account.inventory.get(0).isEmpty());
        assertTrue(account.inventory.get(4).isOf(Items.IRON_SWORD));
        assertTrue(account.inventory.get(PlayerInventory.OFF_HAND_SLOT).isEmpty());
    }

    @Test
    void occupiedHandRejectsWithoutFallingBackToFreeInventory() throws IOException {
        account.inventory.set(0, new ItemStack(Items.IRON_SWORD));
        service.commit(account, deposit(Volumes.POUCH));
        account.inventory.set(PlayerInventory.OFF_HAND_SLOT, new ItemStack(Items.APPLE));
        var before = account.data;
        var request =
                move(stored(before).get(0).getId(), Volumes.PLAYER).toBuilder()
                        .setTargetPlayerSlot(PlayerInventory.OFF_HAND_SLOT)
                        .build();
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, request));
        assertSame(before, account.data);
        assertTrue(account.inventory.get(0).isEmpty());
        assertTrue(account.inventory.get(PlayerInventory.OFF_HAND_SLOT).isOf(Items.APPLE));
    }

    @Test
    void handOnlyMergesWhenEntireStackFits() throws IOException {
        account.inventory.set(0, new ItemStack(Items.STONE, 16));
        service.commit(account, deposit(Volumes.BASKET));
        var request =
                move(stored(account.data).get(0).getId(), Volumes.PLAYER).toBuilder()
                        .setTargetPlayerSlot(PlayerInventory.OFF_HAND_SLOT)
                        .build();
        account.inventory.set(PlayerInventory.OFF_HAND_SLOT, new ItemStack(Items.STONE, 50));
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, request));
        assertEquals(50, account.inventory.get(PlayerInventory.OFF_HAND_SLOT).getCount());
        account.inventory.set(PlayerInventory.OFF_HAND_SLOT, new ItemStack(Items.STONE, 48));
        service.commit(account, request);
        assertEquals(64, account.inventory.get(PlayerInventory.OFF_HAND_SLOT).getCount());
        assertEquals(0, stored(account.data).size());
    }

    @Test
    void handChangesDuringTimerRejectAndPreserveSource() throws IOException {
        account.inventory.set(0, new ItemStack(Items.STONE));
        service.commit(account, deposit(Volumes.BASKET));
        var request =
                move(stored(account.data).get(0).getId(), Volumes.PLAYER).toBuilder()
                        .setTargetPlayerSlot(0)
                        .build();
        service.prepare(account, request);
        account.selectedSlot = 1;
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, request));
        assertEquals(1, stored(account.data).size());
        account.selectedSlot = 0;
        account.inventory.set(0, new ItemStack(Items.DIRT));
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, request));
        assertTrue(account.inventory.get(0).isOf(Items.DIRT));
    }

    @Test
    void offhandFingerprintAndDiskFailureNeverRemoveRealStack() {
        account.inventory.set(PlayerInventory.OFF_HAND_SLOT, new ItemStack(Items.STONE, 16));
        var request = deposit(Volumes.BASKET, PlayerInventory.OFF_HAND_SLOT);
        service.prepare(account, request);
        account.inventory.set(PlayerInventory.OFF_HAND_SLOT, new ItemStack(Items.STONE, 15));
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, request));
        account.failCommit = true;
        var current = deposit(Volumes.BASKET, PlayerInventory.OFF_HAND_SLOT);
        assertThrows(IOException.class, () -> service.commit(account, current));
        assertEquals(15, account.inventory.get(PlayerInventory.OFF_HAND_SLOT).getCount());
        assertEquals(0, account.data.getRevision());
    }

    @Test
    void nonEquipmentTargetsCannotBeUsed() throws IOException {
        account.inventory.set(0, new ItemStack(Items.STONE));
        service.commit(account, deposit(Volumes.BASKET));
        var request = move(stored(account.data).get(0).getId(), Volumes.PLAYER);
        for (int slot : List.of(-1, 1, 35, 36, 39, 41)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            service.commit(
                                    account,
                                    request.toBuilder().setTargetPlayerSlot(slot).build()));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.prepare(
                                account,
                                request.toBuilder()
                                        .setTarget(Volumes.POUCH)
                                        .setTargetPlayerSlot(0)
                                        .build()));
        assertEquals(1, stored(account.data).size());
    }

    @Test
    void automaticWithdrawalDoesNotUseOffhandOrArmor() throws IOException {
        account.inventory.set(0, new ItemStack(Items.STONE));
        service.commit(account, deposit(Volumes.POUCH));
        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            account.inventory.set(slot, new ItemStack(Items.DIRT, 64));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.commit(
                                account,
                                move(stored(account.data).get(0).getId(), Volumes.PLAYER)));
        assertTrue(account.inventory.get(PlayerInventory.OFF_HAND_SLOT).isEmpty());
        assertTrue(account.inventory.get(PlayerInventory.MAIN_SIZE).isEmpty());
    }

    private MoveRequest deposit(String target) {
        return deposit(target, 0);
    }

    private MoveRequest deposit(String target, int slot) {
        return move("", target).toBuilder()
                .setPlayerSlot(slot)
                .setStackFingerprint(
                        StackCodec.fingerprint(StackCodec.encode(account.inventory.get(slot))))
                .build();
    }

    private MoveRequest move(String item, String target) {
        return MoveRequest.newBuilder()
                .setOperation(UUID.randomUUID().toString())
                .setRevision(account.data.getRevision())
                .setItem(item)
                .setTarget(
                        target.equals(Volumes.POUCH) || target.equals(Volumes.BASKET)
                                ? bag(target)
                                : target)
                .setAutoPlace(true)
                .build();
    }

    private static List<Item> stored(SavedInventory data) {
        return new ContainerTree(data, new ArrayList<>())
                .items().stream()
                        .filter(item -> !item.getContainer().equals(Volumes.WORN))
                        .toList();
    }

    private String bag(String type) {
        String model = type.equals(Volumes.POUCH) ? "grass_pouch" : "back_basket";
        return new ContainerTree(account.data, new ArrayList<>())
                .containers().stream()
                        .filter(container -> container.getModel().equals(model))
                        .findFirst()
                        .orElseThrow()
                        .getId();
    }

    private static class MemoryAccount implements Account {
        SavedInventory data =
                TransferService.upgrade(SavedInventory.newBuilder().setFormatVersion(2).build());
        List<ItemStack> inventory =
                new ArrayList<>(
                        Collections.nCopies(PlayerInventory.OFF_HAND_SLOT + 1, ItemStack.EMPTY));
        int selectedSlot;
        boolean creative;
        int commits;
        boolean failCommit;

        public SavedInventory data() {
            return data;
        }

        public List<ItemStack> inventory() {
            return inventory;
        }

        public int selectedSlot() {
            return selectedSlot;
        }

        public boolean creative() {
            return creative;
        }

        public void commit(SavedInventory next, List<ItemStack> vanilla) throws IOException {
            if (failCommit) {
                throw new IOException("simulated disk failure");
            }
            data = next;
            inventory = new ArrayList<>(vanilla);
            commits++;
        }
    }
}
