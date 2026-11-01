package dev.kizuna.backpack3d.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.backpack3d.TestContainers;
import dev.kizuna.backpack3d.TestRegistries;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;

class GroundTransferTest {
    private final GroundMemory account = new GroundMemory();
    private final TransferService service = new TransferService(stack -> Volumes.UNIT);

    @BeforeAll
    static void bootstrap() {
        TestRegistries.bootstrap();
    }

    @Test
    void groundAndHandMoveTogetherAfterCommitAndReplayIsIdempotent() throws IOException {
        String id = groundBag();
        account.inventory.set(0, new ItemStack(Items.DIAMOND, 3));
        var deposit = deposit(id);
        service.prepare(account, deposit);
        assertEquals(3, account.inventory.get(0).getCount());
        assertEquals(0, contents(id).getItemsCount());
        service.commit(account, deposit);
        service.commit(account, deposit);
        assertTrue(account.inventory.get(0).isEmpty());
        assertEquals(1, contents(id).getItemsCount());
        var item = contents(id).getItems(0);
        service.commit(
                account,
                base().setItem(item.getId())
                        .setTarget(Volumes.PLAYER)
                        .setTargetPlayerSlot(Inventory.SLOT_OFFHAND)
                        .build());
        assertEquals(3, account.inventory.get(Inventory.SLOT_OFFHAND).getCount());
        assertEquals(0, contents(id).getItemsCount());
        assertEquals(2, account.data.getRevision());
    }

    @Test
    void changedGroundVersionOrLostAccessCannotCommit() {
        String id = groundBag();
        account.inventory.set(0, new ItemStack(Items.STONE));
        var request = deposit(id);
        service.prepare(account, request);
        account.versions.put(id, 1L);
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, request));
        account.versions.clear();
        account.ground.clear();
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, request));
        assertEquals(1, account.inventory.get(0).getCount());
    }

    @Test
    void saveFailureKeepsGroundAndPlayerUnchanged() {
        String id = groundBag();
        account.inventory.set(0, new ItemStack(Items.STONE));
        account.fail = true;
        assertThrows(IOException.class, () -> service.commit(account, deposit(id)));
        assertFalse(account.inventory.get(0).isEmpty());
        assertEquals(0, contents(id).getItemsCount());
        assertEquals(0, account.data.getRevision());
    }

    @Test
    void groundRootIsNotAWornOrVanillaSlotAndCannotBeTakenThroughMove() {
        String id = groundBag();
        var tree = new ContainerTree(TestRegistries.VANILLA, account.data, account.inventory, account.ground);
        var root = tree.containers().get(0);
        assertTrue(root.getGrounded());
        assertFalse(root.getWorn());
        assertFalse(root.hasPlayerSlot());
        assertTrue(tree.worn().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> tree.take(id));
    }

    @Test
    void portableContainerCanMoveIntoGroundBagAndReturnWithItsIdentity() throws IOException {
        var box = ContainerItems.create(TestContainers.BOX);
        String id = ContainerItems.identity(box);
        account.ground.put(id, box);
        account.versions.put(id, 0L);
        var pouch = ContainerItems.create(TestContainers.POUCH);
        String pouchId = ContainerItems.identity(pouch);
        account.inventory.set(0, pouch);
        service.commit(account, deposit(id));
        assertEquals(pouchId, contents(id).getItems(0).getId());
        service.commit(
                account,
                base().setItem(pouchId).setTarget(Volumes.PLAYER).setTargetPlayerSlot(0).build());
        assertEquals(pouchId, ContainerItems.identity(account.inventory.get(0)));
        assertTrue(contents(id).getItemsList().isEmpty());
    }

    private String groundBag() {
        var stack = ContainerItems.create(TestContainers.POUCH);
        String id = ContainerItems.identity(stack);
        account.ground.put(id, stack);
        account.versions.put(id, 0L);
        return id;
    }

    private ContainerContents contents(String id) {
        return ContainerItems.contents(account.ground.get(id));
    }

    private MoveRequest.Builder base() {
        return MoveRequest.newBuilder()
                .setOperation(UUID.randomUUID().toString())
                .setRevision(account.data.getRevision())
                .putAllGroundRevisions(account.versions);
    }

    private MoveRequest deposit(String id) {
        return base().setTarget(id)
                .setAutoPlace(true)
                .setPlayerSlot(0)
                .setStackFingerprint(
                        StackCodec.fingerprint(StackCodec.encode(account.inventory.get(0), TestRegistries.VANILLA)))
                .build();
    }

    private static final class GroundMemory implements Account {
        @Override
        public HolderLookup.Provider registries() {
            return TestRegistries.VANILLA;
        }

        SavedInventory data = TransferService.empty();
        List<ItemStack> inventory =
                new ArrayList<>(
                        Collections.nCopies(Inventory.SLOT_OFFHAND + 1, ItemStack.EMPTY));
        Map<String, ItemStack> ground = new LinkedHashMap<>();
        Map<String, Long> versions = new LinkedHashMap<>();
        boolean fail;

        public SavedInventory data() {
            return data;
        }

        public List<ItemStack> inventory() {
            return inventory;
        }

        public int selectedSlot() {
            return 0;
        }

        public boolean creative() {
            return false;
        }

        public Map<String, ItemStack> ground() {
            return ground;
        }

        public Map<String, Long> groundRevisions() {
            return versions;
        }

        public void commit(SavedInventory data, List<ItemStack> inventory) {
            throw new AssertionError("未提交地面库存");
        }

        public void commit(
                SavedInventory next, List<ItemStack> vanilla, Map<String, ItemStack> nextGround)
                throws IOException {
            if (fail) {
                throw new IOException("simulated write failure");
            }
            data = next;
            inventory = vanilla;
            ground = nextGround;
            versions.replaceAll((id, version) -> version + 1);
        }
    }
}
