package dev.kizuna.backpack3d.server;

import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.service.Account;

import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 一次请求可访问的玩家与地面库存；不把地面物品伪装成原版额外槽位。 */
final class GroundAccount implements Account {
    private final ServerPlayerEntity player;
    private final PlayerAccount personal;
    private final GroundInventory storage;
    private final Set<String> accessible;

    GroundAccount(ServerPlayerEntity player, Set<String> accessible) {
        this.player = player;
        personal = new PlayerAccount(player);
        storage = GroundInventory.get(player.getServer());
        this.accessible = Set.copyOf(accessible);
    }

    @Override
    public SavedInventory data() {
        return personal.data();
    }

    @Override
    public List<ItemStack> inventory() {
        return personal.inventory();
    }

    @Override
    public int selectedSlot() {
        return personal.selectedSlot();
    }

    @Override
    public boolean creative() {
        return personal.creative();
    }

    @Override
    public Map<String, ItemStack> ground() {
        // 始终重新读取权威栈，不能复用打开页面时的库存副本。
        var result = new LinkedHashMap<String, ItemStack>();
        for (String id : accessible) {
            result.put(id, storage.leased(player, id).stack().copy());
        }
        return result;
    }

    @Override
    public Map<String, Long> groundRevisions() {
        var result = new LinkedHashMap<String, Long>();
        for (String id : accessible) {
            result.put(id, storage.leased(player, id).revision());
        }
        return result;
    }

    @Override
    public void commit(SavedInventory data, List<ItemStack> inventory) throws IOException {
        personal.commit(data, inventory);
    }

    @Override
    public void commit(
            SavedInventory data, List<ItemStack> inventory, Map<String, ItemStack> ground)
            throws IOException {
        if (!ground.keySet().equals(accessible)) {
            throw new IllegalArgumentException("地面背包访问范围已变化");
        }
        if (ground.isEmpty()) {
            personal.commit(data, inventory);
        } else {
            storage.commitContents(player, data, inventory, ground);
        }
    }
}
