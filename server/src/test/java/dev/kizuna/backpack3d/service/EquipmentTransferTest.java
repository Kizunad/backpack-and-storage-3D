package dev.kizuna.backpack3d.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.*;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.*;

/** 覆盖双手、盔甲、便携内容和装备上限；所有断言都观察真实候选与提交后的库存。 */
class EquipmentTransferTest {
    private final TransferService service = new TransferService(stack -> Volumes.UNIT);
    private final MemoryAccount account = new MemoryAccount();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        ContainerItems.initialize();
    }

    @Test
    void bothHandsTransferNbtAfterCommitAndReplayOnlyOnce() throws IOException {
        var sword = new ItemStack(Items.IRON_SWORD);
        sword.setDamage(17);
        sword.setCustomName(Text.literal("旧剑"));
        account.inventory.set(0, sword);
        var request = toSlot(0, PlayerInventory.OFF_HAND_SLOT);
        service.prepare(account, request);
        assertTrue(ItemStack.areEqual(sword, account.inventory.get(0)));
        assertTrue(account.inventory.get(PlayerInventory.OFF_HAND_SLOT).isEmpty());
        service.commit(account, request);
        service.commit(account, request);
        assertEquals(1, account.commits);
        assertTrue(account.inventory.get(0).isEmpty());
        assertTrue(ItemStack.areEqual(sword, account.inventory.get(PlayerInventory.OFF_HAND_SLOT)));
        service.commit(account, toSlot(PlayerInventory.OFF_HAND_SLOT, 0));
        assertTrue(ItemStack.areEqual(sword, account.inventory.get(0)));
        assertEquals(2, account.commits);
    }

    @Test
    void handMergeIsWholeStackAndDoesNotDisplaceOtherItems() throws IOException {
        account.inventory.set(0, new ItemStack(Items.STONE, 16));
        account.inventory.set(PlayerInventory.OFF_HAND_SLOT, new ItemStack(Items.DIRT));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.commit(account, toSlot(0, PlayerInventory.OFF_HAND_SLOT)));
        account.inventory.set(PlayerInventory.OFF_HAND_SLOT, new ItemStack(Items.STONE, 50));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.commit(account, toSlot(0, PlayerInventory.OFF_HAND_SLOT)));
        assertEquals(16, account.inventory.get(0).getCount());
        account.inventory.set(PlayerInventory.OFF_HAND_SLOT, new ItemStack(Items.STONE, 48));
        service.commit(account, toSlot(0, PlayerInventory.OFF_HAND_SLOT));
        assertEquals(64, account.inventory.get(PlayerInventory.OFF_HAND_SLOT).getCount());
        assertTrue(account.inventory.get(0).isEmpty());
    }

    @Test
    void switchedHandAndChangedSourceRejectAtCompletion() {
        account.inventory.set(0, new ItemStack(Items.STONE));
        var request = toSlot(0, PlayerInventory.OFF_HAND_SLOT);
        service.prepare(account, request);
        account.selectedSlot = 1;
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, request));
        account.selectedSlot = 0;
        account.inventory.set(0, new ItemStack(Items.DIRT));
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, request));
        assertEquals(0, account.commits);
    }

    @Test
    void allArmorPartsCanBeEquippedRemovedAndStored() throws IOException {
        String bag = wearNew(ContainerItems.BASKET, Mount.TORSO_BACK);
        var pieces =
                Map.of(
                        EquipmentSlot.HEAD,
                        Items.IRON_HELMET,
                        EquipmentSlot.CHEST,
                        Items.IRON_CHESTPLATE,
                        EquipmentSlot.LEGS,
                        Items.IRON_LEGGINGS,
                        EquipmentSlot.FEET,
                        Items.IRON_BOOTS);
        for (var piece : pieces.entrySet()) {
            int armorSlot = PlayerSlots.armorSlot(piece.getKey());
            ItemStack stack = new ItemStack(piece.getValue());
            stack.setDamage(7);
            account.inventory.set(0, stack);
            service.commit(account, toSlot(0, armorSlot));
            assertTrue(ItemStack.areEqual(stack, account.inventory.get(armorSlot)));
            service.commit(account, toSlot(armorSlot, PlayerInventory.OFF_HAND_SLOT));
            assertTrue(account.inventory.get(armorSlot).isEmpty());
            service.commit(account, fromPlayer(PlayerInventory.OFF_HAND_SLOT, bag).build());
            Item stored =
                    tree().items().stream()
                            .filter(value -> value.getContainer().equals(bag))
                            .filter(
                                    value ->
                                            StackCodec.decode(value.getStackNbt())
                                                    .isOf(piece.getValue()))
                            .findFirst()
                            .orElseThrow();
            service.commit(
                    account,
                    move(stored.getId(), Volumes.PLAYER).setTargetPlayerSlot(armorSlot).build());
            assertTrue(ItemStack.areEqual(stack, account.inventory.get(armorSlot)));
        }
    }

    @Test
    void wrongArmorAndOccupiedBodyPartNeverReplaceEquipment() throws IOException {
        int head = PlayerSlots.armorSlot(EquipmentSlot.HEAD);
        account.inventory.set(0, new ItemStack(Items.IRON_BOOTS));
        assertThrows(
                IllegalArgumentException.class, () -> service.commit(account, toSlot(0, head)));
        account.inventory.set(0, new ItemStack(Items.IRON_HELMET));
        account.inventory.set(head, new ItemStack(Items.DIAMOND_HELMET));
        assertThrows(
                IllegalArgumentException.class, () -> service.commit(account, toSlot(0, head)));
        assertEquals(0, account.commits);
        assertTrue(account.inventory.get(head).isOf(Items.DIAMOND_HELMET));
    }

    @Test
    void bindingCurseCannotBeBypassedThroughHandsOrContainers() throws IOException {
        String bag = wearNew(ContainerItems.BASKET, Mount.TORSO_BACK);
        int head = PlayerSlots.armorSlot(EquipmentSlot.HEAD);
        ItemStack helmet = new ItemStack(Items.IRON_HELMET);
        helmet.addEnchantment(Enchantments.BINDING_CURSE, 1);
        account.inventory.set(head, helmet);
        assertThrows(
                IllegalArgumentException.class, () -> service.commit(account, toSlot(head, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.commit(account, fromPlayer(head, bag).build()));
        account.creative = true;
        service.commit(account, toSlot(head, 0));
        assertTrue(ItemStack.areEqual(helmet, account.inventory.get(0)));
    }

    @Test
    void vanillaHeadAndChestEquipmentFollowVanillaSlotRules() throws IOException {
        for (var entry :
                Map.of(Items.ELYTRA, EquipmentSlot.CHEST, Items.CARVED_PUMPKIN, EquipmentSlot.HEAD)
                        .entrySet()) {
            account.inventory.set(0, new ItemStack(entry.getKey()));
            int slot = PlayerSlots.armorSlot(entry.getValue());
            service.commit(account, toSlot(0, slot));
            assertTrue(account.inventory.get(slot).isOf(entry.getKey()));
        }
    }

    @Test
    void filledPouchMovesIntoBasketOutToHandAndBackOntoWaist() throws IOException {
        String basket = wearNew(ContainerItems.BASKET, Mount.TORSO_BACK);
        String pouch = wearNew(ContainerItems.POUCH, Mount.WAIST_LEFT_FRONT);
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.setCustomName(Text.literal("随包保存"));
        account.inventory.set(0, sword);
        service.commit(account, fromPlayer(0, pouch).build());
        service.commit(account, move(pouch, basket).build());
        assertEquals(1, account.data.getWornCount());
        assertEquals(
                basket,
                tree().containers().stream()
                        .filter(value -> value.getId().equals(pouch))
                        .findFirst()
                        .orElseThrow()
                        .getParent());
        service.commit(
                account,
                move(pouch, Volumes.PLAYER)
                        .setTargetPlayerSlot(PlayerInventory.OFF_HAND_SLOT)
                        .build());
        ItemStack carried = account.inventory.get(PlayerInventory.OFF_HAND_SLOT);
        assertEquals(pouch, ContainerItems.identity(carried));
        assertTrue(
                ItemStack.areEqual(
                        sword,
                        StackCodec.decode(
                                ContainerItems.contents(carried).getItems(0).getStackNbt())));
        // 经过真实序列化再取出，验证内容依附于物品 NBT，而不是仅存在内存中的节点。
        account.inventory.set(
                PlayerInventory.OFF_HAND_SLOT, StackCodec.decode(StackCodec.encode(carried)));
        service.commit(
                account,
                fromPlayer(PlayerInventory.OFF_HAND_SLOT, Volumes.WORN)
                        .setWornTarget(
                                Placement.newBuilder()
                                        .setMount(Mount.WAIST_RIGHT_FRONT)
                                        .setWaistAngleDegrees(12.75f))
                        .build());
        var worn =
                tree().containers().stream()
                        .filter(value -> value.getId().equals(pouch))
                        .findFirst()
                        .orElseThrow();
        assertTrue(worn.getWorn());
        assertEquals(12.75f, worn.getWaistAngleDegrees());
        assertTrue(
                tree().items().stream()
                        .anyMatch(
                                value ->
                                        value.getContainer().equals(pouch)
                                                && ItemStack.areEqual(
                                                        sword,
                                                        StackCodec.decode(value.getStackNbt()))));
    }

    @Test
    void selfAndDescendantContainmentRejectWithoutRemovingSource() throws IOException {
        String basket = wearNew(ContainerItems.BASKET, Mount.TORSO_BACK);
        String pouch = wearNew(ContainerItems.POUCH, Mount.WAIST_LEFT_FRONT);
        assertThrows(
                IllegalArgumentException.class,
                () -> service.commit(account, move(basket, basket).build()));
        service.commit(account, move(pouch, basket).build());
        var before = account.data;
        assertThrows(
                IllegalArgumentException.class,
                () -> service.commit(account, move(basket, pouch).build()));
        assertSame(before, account.data);
    }

    @Test
    void basketCannotFitInThirtyCellBoxInAnyOrientation() throws IOException {
        String basket = wearNew(ContainerItems.BASKET, Mount.TORSO_BACK);
        account.inventory.set(0, ContainerItems.create(ContainerItems.BOX));
        String box = ContainerItems.identity(account.inventory.get(0));
        for (int orientation = 0; orientation < OrthogonalRotation.COUNT; orientation++) {
            var request = move(basket, box).setOrientation(orientation).build();
            assertThrows(IllegalArgumentException.class, () -> service.commit(account, request));
        }
        assertEquals(1, account.data.getWornCount());
        assertEquals(
                Volumes.size(3, 3, 4),
                ContainerItems.definition(StackCodec.decode(account.data.getWorn(0).getStackNbt()))
                        .outside());
    }

    @Test
    void waistLimitDefaultsToTwoAndPlayerOverridePersists() throws IOException {
        wearNew(ContainerItems.POUCH, Mount.WAIST_LEFT_FRONT);
        wearNew(ContainerItems.POUCH, Mount.WAIST_RIGHT_FRONT);
        account.inventory.set(0, ContainerItems.create(ContainerItems.POUCH));
        var request =
                fromPlayer(0, Volumes.WORN)
                        .setWornTarget(
                                Placement.newBuilder()
                                        .setMount(Mount.WAIST_LEFT_BACK)
                                        .setWaistAngleDegrees(123.5f))
                        .build();
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, request));
        account.data = account.data.toBuilder().setWaistLimit(3).build();
        service.commit(account, request);
        assertEquals(3, account.data.getWornCount());
        assertEquals(
                3, ContainerTree.waistLimit(SavedInventory.parseFrom(account.data.toByteArray())));
        assertThrows(
                IllegalArgumentException.class,
                () -> TransferService.validate(account.data.toBuilder().setWaistLimit(2).build()));
    }

    @Test
    void torsoSlotsAreExclusiveAndContainersCannotUseWrongMounts() throws IOException {
        wearNew(ContainerItems.BASKET, Mount.TORSO_BACK);
        account.inventory.set(0, ContainerItems.create(ContainerItems.BASKET));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.commit(
                                account,
                                fromPlayer(0, Volumes.WORN)
                                        .setWornTarget(
                                                Placement.newBuilder().setMount(Mount.TORSO_BACK))
                                        .build()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.commit(
                                account,
                                fromPlayer(0, Volumes.WORN)
                                        .setWornTarget(
                                                Placement.newBuilder()
                                                        .setMount(Mount.WAIST_RIGHT_FRONT))
                                        .build()));
        service.commit(
                account,
                fromPlayer(0, Volumes.WORN)
                        .setWornTarget(Placement.newBuilder().setMount(Mount.TORSO_FRONT))
                        .build());
        assertEquals(2, account.data.getWornCount());
    }

    @Test
    void discardedAndFailedContainerCandidatesLeaveContentsAndReceiptUntouched()
            throws IOException {
        String basket = wearNew(ContainerItems.BASKET, Mount.TORSO_BACK);
        String pouch = wearNew(ContainerItems.POUCH, Mount.WAIST_LEFT_FRONT);
        account.inventory.set(0, new ItemStack(Items.APPLE, 7));
        service.commit(account, fromPlayer(0, pouch).build());
        var before = account.data;
        var request = move(pouch, basket).build();
        service.prepare(account, request);
        assertSame(before, account.data);
        account.failCommit = true;
        assertThrows(IOException.class, () -> service.commit(account, request));
        assertSame(before, account.data);
    }

    private ContainerTree tree() {
        return new ContainerTree(account.data, new ArrayList<>(account.inventory));
    }

    @Test
    void tooDeepNestingAndDuplicateContainerIdentityAreRejected() throws IOException {
        List<String> boxes = new ArrayList<>();
        for (int slot = 0; slot < ContainerItems.MAX_NESTING_DEPTH + 2; slot++) {
            ItemStack box = ContainerItems.create(ContainerItems.BOX);
            account.inventory.set(slot, box);
            boxes.add(ContainerItems.identity(box));
        }
        // 同规格的箱子恰好能装入另一个箱子，逐层放入以触及深度边界。
        for (int slot = 0; slot < ContainerItems.MAX_NESTING_DEPTH; slot++) {
            account.selectedSlot = slot;
            service.commit(account, fromPlayer(slot, boxes.get(slot + 1)).build());
        }
        int source = ContainerItems.MAX_NESTING_DEPTH;
        account.selectedSlot = source;
        var request = fromPlayer(source, boxes.get(source + 1)).build();
        assertThrows(IllegalArgumentException.class, () -> service.commit(account, request));
        assertFalse(account.inventory.get(source).isEmpty());
        account.inventory.set(source + 1, account.inventory.get(source).copy());
        assertThrows(IllegalArgumentException.class, this::tree);
    }

    private String wearNew(Identifier type, Mount mount) throws IOException {
        account.inventory.set(0, ContainerItems.create(type));
        String id = ContainerItems.identity(account.inventory.get(0));
        service.commit(
                account,
                fromPlayer(0, Volumes.WORN)
                        .setWornTarget(Placement.newBuilder().setMount(mount))
                        .build());
        return id;
    }

    private MoveRequest.Builder move(String item, String target) {
        return MoveRequest.newBuilder()
                .setOperation(UUID.randomUUID().toString())
                .setRevision(account.data.getRevision())
                .setItem(item)
                .setTarget(target)
                .setAutoPlace(true);
    }

    private MoveRequest.Builder fromPlayer(int slot, String target) {
        return move("", target)
                .setPlayerSlot(slot)
                .setStackFingerprint(
                        StackCodec.fingerprint(StackCodec.encode(account.inventory.get(slot))));
    }

    private MoveRequest toSlot(int source, int target) {
        return fromPlayer(source, Volumes.PLAYER).setTargetPlayerSlot(target).build();
    }

    private static class MemoryAccount implements Account {
        SavedInventory data = TransferService.empty();
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
            // 模拟原子保存失败，确认服务层没有提前改动旧候选以外的任何库存。
            if (failCommit) {
                throw new IOException("simulated disk failure");
            }
            data = next;
            inventory = new ArrayList<>(vanilla);
            commits++;
        }
    }
}
