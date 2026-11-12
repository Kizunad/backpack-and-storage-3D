package dev.kizuna.backpack3d.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kizuna.backpack3d.TestContainers;
import dev.kizuna.backpack3d.TestRegistries;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.PlayerSlots;
import dev.kizuna.backpack3d.service.ContainerTree;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class VolumeTreeViewTest {
    private static final int TOOL_SLOT = 0;
    private static final int POUCH_SLOT = 3;

    @BeforeAll
    static void bootstrap() {
        TestRegistries.bootstrap();
    }

    private static List<ItemStack> inventory() {
        List<ItemStack> stacks = new ArrayList<>(Collections.nCopies(Inventory.SLOT_OFFHAND + 1, ItemStack.EMPTY));
        stacks.set(TOOL_SLOT, new ItemStack(Items.IRON_PICKAXE));
        stacks.set(POUCH_SLOT, ContainerItems.create(TestContainers.POUCH));
        return stacks;
    }

    @Test
    void unrelatedSlotChangesReuseTheTreeAndMatchAFreshBuild() {
        // 工具掉耐久、普通物品进出背包都不是树的输入：复用同一份结果，内容和现建的一样。
        var view = new VolumeTreeView();
        var data = TestContainers.account();
        List<ItemStack> inventory = inventory();
        var first = view.lists(TestRegistries.VANILLA, data, inventory, Map.of());
        assertTrue(first.built());
        assertFalse(first.items().isEmpty());

        List<ItemStack> worn = new ArrayList<>(inventory);
        ItemStack damaged = inventory.get(TOOL_SLOT).copy();
        damaged.setDamageValue(5);
        worn.set(TOOL_SLOT, damaged);
        worn.set(TOOL_SLOT + 1, new ItemStack(Items.COBBLESTONE, 12));
        var reused = view.lists(TestRegistries.VANILLA, data, worn, Map.of());
        assertSame(first, reused);

        var fresh = new ContainerTree(TestRegistries.VANILLA, data, new ArrayList<>(worn));
        assertEquals(fresh.items(), reused.items());
        assertEquals(fresh.containers(), reused.containers());
        assertEquals(fresh.wearables(), reused.wearables());
    }

    @Test
    void everyTreeInputRebuilds() {
        // 存档换了、容器栈进出或换内容、盔甲槽从空变有，树都要重建。
        var view = new VolumeTreeView();
        var data = TestContainers.account();
        List<ItemStack> inventory = inventory();
        var first = view.lists(TestRegistries.VANILLA, data, inventory, Map.of());

        var bumped = data.toBuilder().setRevision(data.getRevision() + 1).build();
        var afterSave = view.lists(TestRegistries.VANILLA, bumped, inventory, Map.of());
        assertNotSame(first, afterSave);

        List<ItemStack> withoutPouch = new ArrayList<>(inventory);
        withoutPouch.set(POUCH_SLOT, ItemStack.EMPTY);
        var afterTake = view.lists(TestRegistries.VANILLA, bumped, withoutPouch, Map.of());
        assertNotSame(afterSave, afterTake);
        assertEquals(afterSave.containers().size() - 1, afterTake.containers().size());

        List<ItemStack> otherPouch = new ArrayList<>(inventory);
        otherPouch.set(POUCH_SLOT, ContainerItems.create(TestContainers.POUCH));
        var afterSwap = view.lists(TestRegistries.VANILLA, bumped, otherPouch, Map.of());
        assertNotSame(afterTake, afterSwap);

        List<ItemStack> armored = new ArrayList<>(otherPouch);
        armored.set(PlayerSlots.armorSlot(EquipmentSlot.HEAD), new ItemStack(Items.IRON_HELMET));
        var afterArmor = view.lists(TestRegistries.VANILLA, bumped, armored, Map.of());
        assertNotSame(afterSwap, afterArmor);
        assertSame(afterArmor, view.lists(TestRegistries.VANILLA, bumped, armored, Map.of()));
    }

    @Test
    void brokenTreeIsReportedTheSameWayEachTime() {
        // 同一个身份的容器出现两次，树建不出来：原因照旧给出，复用时也一样。
        var view = new VolumeTreeView();
        var data = TestContainers.account();
        List<ItemStack> inventory = inventory();
        inventory.set(POUCH_SLOT + 1, inventory.get(POUCH_SLOT).copy());
        var broken = view.lists(TestRegistries.VANILLA, data, inventory, Map.of());
        assertFalse(broken.built());
        assertFalse(broken.invalid().isEmpty());
        assertSame(broken, view.lists(TestRegistries.VANILLA, data, inventory, Map.of()));
    }
}
