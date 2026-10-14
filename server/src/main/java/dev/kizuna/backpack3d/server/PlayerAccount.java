package dev.kizuna.backpack3d.server;

import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.service.Account;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** 在逻辑服务器线程将完整玩家 NBT 原子替换到标准 playerdata。 */
public final class PlayerAccount implements Account {
    private final ServerPlayerEntity player;

    public PlayerAccount(ServerPlayerEntity player) {
        this.player = player;
    }

    @Override
    public SavedInventory data() {
        return ((PlayerStorage) player).bs3d$getInventory();
    }

    @Override
    public List<ItemStack> inventory() {
        // 使用原版统一槽号，主背包、盔甲与副手一起参与事务。
        var inventory = player.getInventory();
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            stacks.add(inventory.getStack(slot).copy());
        }
        return stacks;
    }

    @Override
    public int selectedSlot() {
        return player.getInventory().selectedSlot;
    }

    @Override
    public boolean creative() {
        return player.isCreative();
    }

    NbtCompound snapshot(SavedInventory next, List<ItemStack> inventory) throws IOException {
        var server = player.getServer();
        if (server == null || !server.isOnThread()) {
            throw new IOException("库存事务必须在服务器主线程执行");
        }
        var previous = data();
        var previousInventory = inventory();
        try {
            // 仅为生成完整 NBT 暂时应用候选；同一服务器调用内恢复，不发网络快照。
            apply(next, inventory);
            return NbtHelper.putDataVersion(player.writeNbt(new NbtCompound()));
        } finally {
            apply(previous, previousInventory);
        }
    }

    void applyCommitted(SavedInventory next, List<ItemStack> inventory) {
        // 跨文件日志已持久化之后才能让客户端看到新库存。
        apply(next, inventory);
        player.getInventory().markDirty();
        player.playerScreenHandler.sendContentUpdates();
    }

    @Override
    public void commit(SavedInventory next, List<ItemStack> inventory) throws IOException {
        // 不跨原版存档与独立业务文件提交，消除二者保存先后造成的复制或丢物窗口。
        var server = player.getServer();
        if (server == null || !server.isOnThread()) {
            throw new IOException("库存事务必须在服务器主线程执行");
        }
        var previous = data();
        var previousInventory = inventory().stream().map(ItemStack::copy).toList();
        Path temporary = null;
        boolean committed = false;
        try {
            apply(next, inventory);
            var tag = NbtHelper.putDataVersion(player.writeNbt(new NbtCompound()));
            var directory = server.getSavePath(WorldSavePath.PLAYERDATA);
            Files.createDirectories(directory);
            temporary =
                    Files.createTempFile(directory, player.getUuidAsString() + "-bs3d-", ".dat");
            NbtIo.writeCompressed(tag, temporary.toFile());
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            // 不支持原子替换的文件系统必须拒绝本次交易，不能悄悄降级为非原子写入。
            Files.move(
                    temporary,
                    directory.resolve(player.getUuidAsString() + ".dat"),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            committed = true;
            player.getInventory().markDirty();
        } finally {
            if (!committed) {
                apply(previous, previousInventory);
            }
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    LoggerFactory.getLogger("backpack_storage_3d")
                            .warn("无法清理事务临时文件", cleanupFailure);
                }
            }
        }
        // 只有提交成功后才让原版容器向客户端同步；磁盘失败时 UI 看不到候选栈。
        player.playerScreenHandler.sendContentUpdates();
    }

    private void apply(SavedInventory data, List<ItemStack> inventory) {
        // 主背包、盔甲与副手从同一候选恢复，其他玩家字段不变。
        ((PlayerStorage) player).bs3d$setInventory(data);
        for (int index = 0; index < inventory.size(); index++) {
            player.getInventory().setStack(index, inventory.get(index).copy());
        }
    }
}
