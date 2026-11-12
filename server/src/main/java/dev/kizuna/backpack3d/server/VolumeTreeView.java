package dev.kizuna.backpack3d.server;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.WearableItems;
import dev.kizuna.backpack3d.protocol.PlayerSlots;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Container;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Wearable;
import dev.kizuna.backpack3d.service.ContainerTree;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/**
 * 状态包里容器树那一部分。建树要把穿戴存档和每个容器里的物品全部解码一遍，
 * 而换手持、掉耐久这类变化并不动它的输入；输入没变就复用上一次的结果。
 */
final class VolumeTreeView {
    /**
     * 一次建树的结果。built 为 false 表示树本身建不出来；三份清单按产出顺序填，
     * 中途出错时后面的留 null，invalid 是出错原因，没出错为 null。
     */
    record Lists(boolean built, List<Item> items, List<Container> containers, List<Wearable> wearables,
                 String invalid) { }

    private SavedInventory saved;
    // 按原版槽号排列：放着容器或穿戴物品的槽记整栈，其余记空栈。
    private List<ItemStack> stored = List.of();
    private List<Boolean> armorOccupied = List.of();
    private Lists lists;

    /** 这个栈会不会进容器树：登记过的容器或穿戴物品。 */
    static boolean stores(ItemStack stack) {
        return ContainerItems.definition(stack) != null || WearableItems.definition(stack) != null;
    }

    Lists lists(HolderLookup.Provider registries, SavedInventory data, List<ItemStack> inventory,
                Map<String, ItemStack> ground) {
        // 打开着地面容器时每次都重建：地面栈由别人也能改，不在这里判断它变没变。
        if (!ground.isEmpty()) {
            lists = null;
            return build(registries, data, inventory, ground);
        }
        List<ItemStack> nextStored = new ArrayList<>();
        for (ItemStack stack : inventory) {
            nextStored.add(stores(stack) ? stack : ItemStack.EMPTY);
        }
        List<Boolean> nextArmor = new ArrayList<>();
        for (var equipment : PlayerSlots.ARMOR) {
            // 树只关心盔甲槽有没有东西：甲层不能和原版装备占同一个部位。
            int slot = PlayerSlots.armorSlot(equipment);
            nextArmor.add(slot < inventory.size() && !inventory.get(slot).isEmpty());
        }
        // 存档是不可变消息，引用相同就是内容相同。
        if (lists != null && saved == data && nextArmor.equals(armorOccupied) && sameStacks(stored, nextStored)) {
            return lists;
        }
        saved = data;
        stored = nextStored;
        armorOccupied = nextArmor;
        lists = build(registries, data, inventory, ground);
        return lists;
    }

    private static boolean sameStacks(List<ItemStack> before, List<ItemStack> now) {
        if (before.size() != now.size()) {
            return false;
        }
        for (int slot = 0; slot < now.size(); slot++) {
            if (!ItemStack.matches(before.get(slot), now.get(slot))) {
                return false;
            }
        }
        return true;
    }

    private static Lists build(HolderLookup.Provider registries, SavedInventory data, List<ItemStack> inventory,
                               Map<String, ItemStack> ground) {
        ContainerTree tree;
        try {
            // 树会把候选槽位换成初始化过的副本，给它一份自己的列表。
            tree = new ContainerTree(registries, data, new ArrayList<>(inventory), ground);
        } catch (IllegalArgumentException invalid) {
            return new Lists(false, null, null, null, invalid.getMessage());
        }
        List<Item> items = null;
        List<Container> containers = null;
        List<Wearable> wearables = null;
        String invalid = null;
        try {
            items = tree.items();
            containers = tree.containers();
            wearables = tree.wearables();
        } catch (IllegalArgumentException failure) {
            invalid = failure.getMessage();
        }
        return new Lists(true, items, containers, wearables, invalid);
    }
}
