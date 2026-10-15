package dev.kizuna.backpack3d.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class CursorTransferTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        ContainerItems.initialize();
    }

    @Test
    void emptySlotHasStableFingerprintButCannotBecomeStoredItem() {
        assertEquals(StackCodec.SHA256_BYTES, StackCodec.fingerprint(ItemStack.EMPTY).size());
        assertThrows(IllegalArgumentException.class, () -> StackCodec.encode(ItemStack.EMPTY));
    }

    @Test
    void previewLeavesSourceUntouchedAndPreservesCompleteNbt() {
        var account = new MemoryAccount();
        var sword = new ItemStack(Items.IRON_SWORD);
        sword.setCustomName(Text.literal("工作站旁的剑"));
        sword.setDamage(12);
        var before = account.data;
        var candidate =
                CursorTransfer.prepare(account, sword, store(account), stack -> Volumes.UNIT);
        assertSame(before, account.data);
        assertTrue(ItemStack.areEqual(sword, candidate.moved()));
        assertTrue(candidate.cursor().isEmpty());
        var tree = new ContainerTree(candidate.data(), new ArrayList<>(candidate.inventory()));
        assertTrue(
                ItemStack.areEqual(
                        sword,
                        StackCodec.decode(
                                tree.items().stream()
                                        .filter(item -> item.getContainer().equals(account.bag))
                                        .findFirst()
                                        .orElseThrow()
                                        .getStackNbt())));
    }

    @Test
    void cursorRoundTripDoesNotDuplicateOrLoseItems() {
        var account = new MemoryAccount();
        var source = new ItemStack(Items.OAK_PLANKS, 4);
        var stored = CursorTransfer.prepare(account, source, store(account), stack -> Volumes.UNIT);
        account.apply(stored);
        var tree = new ContainerTree(account.data, account.slots);
        var item =
                tree.items().stream()
                        .filter(value -> value.getContainer().equals(account.bag))
                        .findFirst()
                        .orElseThrow();
        var taken =
                CursorTransfer.prepare(
                        account,
                        ItemStack.EMPTY,
                        CursorTransfer.Request.take(account.data.getRevision(), item.getId()),
                        stack -> Volumes.UNIT);
        assertTrue(ItemStack.areEqual(source, taken.cursor()));
        assertEquals(2, taken.data().getRevision());
        assertTrue(
                new ContainerTree(taken.data(), new ArrayList<>(taken.inventory()))
                        .items().stream()
                                .noneMatch(value -> value.getContainer().equals(account.bag)));
    }

    @Test
    void staleRevisionAndOutOfBoundsNeverAlterSource() {
        var account = new MemoryAccount();
        var source = new ItemStack(Items.STONE, 7);
        var stale = CursorTransfer.Request.store(100, account.bag, Volumes.cell(0, 0, 0), 0);
        var outside = CursorTransfer.Request.store(0, account.bag, Volumes.cell(2, 0, 0), 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> CursorTransfer.prepare(account, source, stale, stack -> Volumes.UNIT));
        assertThrows(
                IllegalArgumentException.class,
                () -> CursorTransfer.prepare(account, source, outside, stack -> Volumes.UNIT));
        assertEquals(0, account.data.getRevision());
        assertEquals(7, source.getCount());
    }

    @Test
    void takingIntoOccupiedCursorAndForeignIdentityAreRejected() {
        var account = new MemoryAccount();
        var request = CursorTransfer.Request.take(0, account.bag);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CursorTransfer.prepare(
                                account,
                                new ItemStack(Items.STONE),
                                request,
                                stack -> Volumes.UNIT));
        var foreign = CursorTransfer.Request.take(0, UUID.randomUUID().toString());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CursorTransfer.prepare(
                                account, ItemStack.EMPTY, foreign, stack -> Volumes.UNIT));
    }

    @Test
    void nestedContainerKeepsIdentityAndCannotBePutIntoItself() {
        var account = new MemoryAccount();
        var taken =
                CursorTransfer.prepare(
                        account,
                        ItemStack.EMPTY,
                        CursorTransfer.Request.take(0, account.bag),
                        stack -> Volumes.UNIT);
        account.apply(taken);
        assertEquals(account.bag, ContainerItems.identity(taken.cursor()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CursorTransfer.prepare(
                                account, taken.cursor(), store(account), stack -> Volumes.UNIT));
        assertEquals(account.bag, ContainerItems.identity(taken.cursor()));
    }

    @Test
    void fullVolumeRejectsWithoutConsumingCursorAndKeepsUnrelatedSlot() {
        var account = new MemoryAccount();
        account.slots.set(0, new ItemStack(Items.APPLE, 3));
        var stored =
                CursorTransfer.prepare(
                        account,
                        new ItemStack(Items.STONE),
                        store(account),
                        stack -> Volumes.size(1, 2, 2));
        account.apply(stored);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CursorTransfer.prepare(
                                account,
                                new ItemStack(Items.DIRT),
                                store(account),
                                stack -> Volumes.UNIT));
        assertEquals(3, account.slots.get(0).getCount());
        assertEquals(1, account.data.getRevision());
    }

    private static CursorTransfer.Request store(MemoryAccount account) {
        return CursorTransfer.Request.store(
                account.data.getRevision(), account.bag, Volumes.cell(0, 0, 0), 0);
    }

    private static final class MemoryAccount implements Account {
        SavedInventory data;
        List<ItemStack> slots = new ArrayList<>();
        final String bag;

        MemoryAccount() {
            for (int slot = 0;
                    slot <= net.minecraft.entity.player.PlayerInventory.OFF_HAND_SLOT;
                    slot++) {
                slots.add(ItemStack.EMPTY);
            }
            var pouch = ContainerItems.create(ContainerItems.POUCH);
            bag = ContainerItems.identity(pouch);
            data =
                    TransferService.empty().toBuilder()
                            .clearWorn()
                            .addWorn(
                                    WornContainer.newBuilder()
                                            .setMount(Mount.WAIST_RIGHT_FRONT)
                                            .setStackNbt(StackCodec.encode(pouch)))
                            .build();
        }

        void apply(CursorTransfer.Candidate candidate) {
            data = candidate.data();
            slots = new ArrayList<>(candidate.inventory());
        }

        @Override
        public SavedInventory data() {
            return data;
        }

        @Override
        public List<ItemStack> inventory() {
            return slots;
        }

        @Override
        public int selectedSlot() {
            return 0;
        }

        @Override
        public boolean creative() {
            return false;
        }

        @Override
        public void commit(SavedInventory next, List<ItemStack> inventory) {
            throw new AssertionError("预览不能提交真实账户");
        }
    }
}
