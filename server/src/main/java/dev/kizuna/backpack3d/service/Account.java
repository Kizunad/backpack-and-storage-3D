package dev.kizuna.backpack3d.service;

import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/** 一次提交同时保存业务与原版库存，不能分开持久化。 */
public interface Account {
    SavedInventory data();

    /** 原版槽位顺序：主背包、盔甲、副手；全部参与同一次玩家存档事务。 */
    List<ItemStack> inventory();

    int selectedSlot();

    /** 物品栈编解码所用的注册表；真实玩家取所在服务器，测试账户取原版内置注册表。 */
    HolderLookup.Provider registries();

    /** 创造模式允许取下绑定诅咒装备；由真实玩家状态提供，客户端不能声明。 */
    boolean creative();

    default int insertPlayer(List<ItemStack> inventory, ItemStack stack) {
        // 纯服务账户使用同样的整栈合并语义，Minecraft 宿主覆盖为真实 insertItem。
        return TransferService.insert(inventory, stack);
    }

    /** 仅包含当前已获准访问的地面根容器；不是额外的原版槽位。 */
    default Map<String, ItemStack> ground() {
        return Map.of();
    }

    default Map<String, Long> groundRevisions() {
        return Map.of();
    }

    default void commit(
            SavedInventory data, List<ItemStack> inventory, Map<String, ItemStack> ground)
            throws IOException {
        // 原有个人库存实现无需改变；世界库存实现必须显式支持跨存档事务。
        if (!ground.isEmpty()) {
            throw new IOException("当前账户不支持地面容器事务");
        }
        commit(data, inventory);
    }

    void commit(SavedInventory data, List<ItemStack> inventory) throws IOException;
}
