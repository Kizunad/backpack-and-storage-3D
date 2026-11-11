package dev.kizuna.backpack3d.service;

import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.ItemStack;

/**
 * 按修订号改写随身树里的若干普通物品，供附属从容器里扣掉一部分物品。
 * 只生成候选，不写真实库存；地面背包不在随身树里，不能经此改写。
 */
public final class StoredRewrite {
    private StoredRewrite() {}

    public static Candidate prepare(Account account, long revision, Map<String, ItemStack> replacements) {
        // 整笔检查都通过才返回候选；任何一项不合法就抛出，调用方的库存保持原样。
        SavedInventory previous = account.data();
        if (revision != previous.getRevision()) {
            throw new IllegalArgumentException("背包已变化，请重新读取");
        }
        if (replacements.isEmpty()) {
            throw new IllegalArgumentException("没有要改写的物品");
        }
        List<ItemStack> inventory = new ArrayList<>();
        for (ItemStack stack : account.inventory()) {
            inventory.add(stack.copy());
        }
        var tree = new ContainerTree(account.registries(), previous, inventory);
        replacements.forEach((itemId, replacement) -> tree.replace(itemId, replacement.copy()));
        var next = CursorTransfer.saveCandidate(previous, tree);
        TransferService.validate(next, account.registries());
        // 重建整树，原版槽位里的容器根和身体上的根一起再校验一次身份、容量和嵌套。
        new ContainerTree(account.registries(), next, inventory);
        return new Candidate(next, List.copyOf(inventory));
    }

    /** 候选仅供同一服务器调用使用；其中的 ItemStack 都是副本。 */
    public record Candidate(SavedInventory data, List<ItemStack> inventory) {}
}
