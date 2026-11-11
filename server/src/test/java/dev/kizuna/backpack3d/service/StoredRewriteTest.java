package dev.kizuna.backpack3d.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kizuna.backpack3d.TestContainers;
import dev.kizuna.backpack3d.TestRegistries;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Cell;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornContainer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StoredRewriteTest {
    private static final int FULL = 64;
    private static final int LEFT = 58;
    private static final int SHULKER_SLOTS = 27;
    private static final int POCKET_SLOT = 9;

    @BeforeAll
    static void bootstrap() {
        TestRegistries.bootstrap();
    }

    @Test
    void fewerOfTheSameItemKeepsIdentityCellAndOrientation() {
        // 扣掉一部分：同一实例、同一格位和朝向，只有数量变少，修订号加一。
        var account = new MemoryAccount();
        String id = account.store(account.basket, new ItemStack(Items.COBBLESTONE, FULL), Volumes.cell(1, 0, 0));
        Item before = account.item(id);
        var previous = account.data;
        var candidate = StoredRewrite.prepare(account, previous.getRevision(),
                Map.of(id, new ItemStack(Items.COBBLESTONE, LEFT)));
        assertSame(previous, account.data, "预览不能改真实账户");
        assertEquals(previous.getRevision() + 1, candidate.data().getRevision());
        account.apply(candidate);
        Item after = account.item(id);
        assertEquals(before.getCell(), after.getCell());
        assertEquals(before.getOrientation(), after.getOrientation());
        assertEquals(before.getBaseSize(), after.getBaseSize());
        assertEquals(LEFT, account.decode(after).getCount());
    }

    @Test
    void emptyReplacementTakesTheWholeEntry() {
        var account = new MemoryAccount();
        String id = account.store(account.basket, new ItemStack(Items.COBBLESTONE, FULL), Volumes.cell(0, 0, 0));
        account.apply(StoredRewrite.prepare(account, account.data.getRevision(), Map.of(id, ItemStack.EMPTY)));
        assertTrue(account.items().stream().noneMatch(item -> item.getId().equals(id)));
    }

    @Test
    void shulkerWithFewerContentsStaysInPlace() {
        // 背包里的潜影盒在 BS3D 看来是普通物品；内容少了以后格位、朝向、UUID 都不变。
        var account = new MemoryAccount();
        var shulker = shulker(new ItemStack(Items.COBBLESTONE, FULL));
        String id = account.store(account.basket, shulker, Volumes.cell(2, 0, 0));
        Item before = account.item(id);
        var fewer = shulker(new ItemStack(Items.COBBLESTONE, LEFT));
        account.apply(StoredRewrite.prepare(account, account.data.getRevision(), Map.of(id, fewer)));
        Item after = account.item(id);
        assertEquals(before.getCell(), after.getCell());
        assertEquals(before.getOrientation(), after.getOrientation());
        var contents = account.decode(after).get(DataComponents.CONTAINER);
        assertEquals(LEFT, contents.nonEmptyItemCopyStream().mapToInt(ItemStack::getCount).sum());
    }

    @Test
    void containerHeldInVanillaSlotIsRewrittenThere() {
        // 原版槽里拿着的 BS3D 容器：子树改写后，候选槽位上的根容器栈随之更新，身上的装备不变。
        var account = new MemoryAccount();
        var box = ContainerItems.create(TestContainers.BOX);
        String boxId = ContainerItems.identity(box);
        account.slots.set(POCKET_SLOT, box);
        String id = account.store(boxId, new ItemStack(Items.COBBLESTONE, FULL), Volumes.cell(0, 0, 0));
        var worn = account.data.getWornList();
        var candidate = StoredRewrite.prepare(account, account.data.getRevision(),
                Map.of(id, new ItemStack(Items.COBBLESTONE, LEFT)));
        assertEquals(worn, candidate.data().getWornList());
        var held = candidate.inventory().get(POCKET_SLOT);
        assertEquals(boxId, ContainerItems.identity(held));
        var stored = ContainerItems.contents(held).getItems(0);
        assertEquals(id, stored.getId());
        assertEquals(LEFT, account.decode(stored).getCount());
    }

    @Test
    void invalidRewritesAreRejectedWithoutTouchingTheAccount() {
        var account = new MemoryAccount();
        String id = account.store(account.basket, new ItemStack(Items.COBBLESTONE, FULL), Volumes.cell(0, 0, 0));
        var pouch = ContainerItems.create(TestContainers.POUCH);
        String pouchId = account.store(account.basket, pouch, Volumes.cell(1, 0, 0));
        long revision = account.data.getRevision();
        var previous = account.data;
        var slots = List.copyOf(account.slots);
        var fewer = new ItemStack(Items.COBBLESTONE, LEFT);
        rejects(account, revision - 1, Map.of(id, fewer));
        rejects(account, revision, Map.of());
        rejects(account, revision, Map.of(id, new ItemStack(Items.STONE, LEFT)));
        rejects(account, revision, Map.of(id, new ItemStack(Items.COBBLESTONE, FULL + 1)));
        rejects(account, revision, Map.of(pouchId, ItemStack.EMPTY));
        rejects(account, revision, Map.of(account.basket, ItemStack.EMPTY));
        rejects(account, revision, Map.of("00000000-0000-0000-0000-000000000000", ItemStack.EMPTY));
        var mixed = new LinkedHashMap<String, ItemStack>();
        mixed.put(id, fewer);
        mixed.put(pouchId, ItemStack.EMPTY);
        rejects(account, revision, mixed);
        assertSame(previous, account.data);
        assertEquals(slots, account.slots);
        assertEquals(FULL, account.decode(account.item(id)).getCount());
    }

    @Test
    void groundBagItemsAreNotPartOfTheCarriedTree() {
        // 地面背包要走租约和地面日志，改写接口只认随身树。
        var account = new MemoryAccount();
        var groundBag = ContainerItems.create(TestContainers.POUCH);
        String groundId = ContainerItems.identity(groundBag);
        var request = CursorTransfer.Request.autoStore(account.data.getRevision(), groundId, 0);
        var stored = CursorTransfer.prepare(account, new ItemStack(Items.COBBLESTONE, FULL), request,
                stack -> Volumes.UNIT, Map.of(groundId, groundBag));
        account.apply(stored);
        account.ground = stored.ground();
        String id = ContainerItems.contents(account.ground.get(groundId)).getItems(0).getId();
        rejects(account, account.data.getRevision(), Map.of(id, new ItemStack(Items.COBBLESTONE, LEFT)));
    }

    private static void rejects(MemoryAccount account, long revision, Map<String, ItemStack> replacements) {
        assertThrows(IllegalArgumentException.class, () -> StoredRewrite.prepare(account, revision, replacements));
    }

    private static ItemStack shulker(ItemStack content) {
        var stack = new ItemStack(Items.SHULKER_BOX);
        var items = new ArrayList<ItemStack>();
        for (int slot = 0; slot < SHULKER_SLOTS; slot++) {
            items.add(slot == 0 ? content : ItemStack.EMPTY);
        }
        stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        return stack;
    }

    private static final class MemoryAccount implements Account {
        SavedInventory data;
        List<ItemStack> slots = new ArrayList<>();
        Map<String, ItemStack> ground = Map.of();
        final String basket;

        MemoryAccount() {
            for (int slot = 0; slot <= Inventory.SLOT_OFFHAND; slot++) {
                slots.add(ItemStack.EMPTY);
            }
            var stack = ContainerItems.create(TestContainers.BASKET);
            basket = ContainerItems.identity(stack);
            var worn = WornContainer.newBuilder()
                    .setMount(Mount.TORSO_BACK)
                    .setStackNbt(StackCodec.encode(stack, TestRegistries.VANILLA))
                    .build();
            data = TransferService.empty().toBuilder().addWorn(worn).build();
        }

        String store(String container, ItemStack stack, Cell cell) {
            // 用现有光标存入把夹具物品放进容器，再按格位找回它的实例 UUID。
            var request = CursorTransfer.Request.store(data.getRevision(), container, cell, 0);
            var candidate = CursorTransfer.prepare(this, stack, request, value -> Volumes.UNIT);
            data = candidate.data();
            slots = new ArrayList<>(candidate.inventory());
            return items().stream()
                    .filter(item -> item.getContainer().equals(container) && item.getCell().equals(cell))
                    .findFirst()
                    .orElseThrow()
                    .getId();
        }

        void apply(StoredRewrite.Candidate candidate) {
            data = candidate.data();
            slots = new ArrayList<>(candidate.inventory());
        }

        void apply(CursorTransfer.Candidate candidate) {
            data = candidate.data();
            slots = new ArrayList<>(candidate.inventory());
        }

        List<Item> items() {
            return new ContainerTree(TestRegistries.VANILLA, data, new ArrayList<>(slots)).items();
        }

        Item item(String id) {
            return items().stream().filter(item -> item.getId().equals(id)).findFirst().orElseThrow();
        }

        ItemStack decode(Item item) {
            return StackCodec.decode(item.getStackNbt(), TestRegistries.VANILLA);
        }

        @Override
        public HolderLookup.Provider registries() {
            return TestRegistries.VANILLA;
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
        public Map<String, ItemStack> ground() {
            return ground;
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
            throw new AssertionError("改写只生成候选，不能提交真实账户");
        }
    }
}
