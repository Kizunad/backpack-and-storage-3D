package dev.kizuna.backpack3d.server;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** 地面库存与玩家文件跨文件提交：先持久化可重放日志，再替换两份最终快照。 */
final class GroundJournal {
    private static final int FORMAT_VERSION = 1;
    private final Path directory;
    private final Path playerDirectory;

    GroundJournal(Path directory, Path playerDirectory) {
        this.directory = directory;
        this.playerDirectory = playerDirectory;
    }

    Path inventoryFile() {
        return directory.resolve("ground.dat");
    }

    void commit(UUID player, NbtCompound playerNbt, NbtCompound groundNbt) throws IOException {
        // 此文件写入成功即为提交点；之后的 I/O 错误不能当作未提交继续接受操作。
        var transaction = new NbtCompound();
        transaction.putInt("version", FORMAT_VERSION);
        transaction.putUuid("player", player);
        transaction.put("player_data", playerNbt.copy());
        transaction.put("ground_data", groundNbt.copy());
        AtomicNbtFiles.write(directory.resolve("pending.dat"), transaction);
        try {
            recover();
        } catch (IOException failure) {
            throw new RecoveryRequiredException(failure);
        }
    }

    void recover() throws IOException {
        Path pending = directory.resolve("pending.dat");
        if (!Files.exists(pending)) {
            return;
        }
        // 启服、玩家读档之前重放，不依赖上次区块保存到哪一步；重复重放结果相同。
        var transaction = NbtIo.readCompressed(pending.toFile());
        if (transaction.getInt("version") != FORMAT_VERSION
                || !transaction.containsUuid("player")
                || !transaction.contains("player_data", NbtElement.COMPOUND_TYPE)
                || !transaction.contains("ground_data", NbtElement.COMPOUND_TYPE)) {
            throw new IOException("地面背包恢复日志损坏，已停止加载");
        }
        AtomicNbtFiles.write(inventoryFile(), transaction.getCompound("ground_data"));
        Path playerFile = playerDirectory.resolve(transaction.getUuid("player") + ".dat");
        AtomicNbtFiles.write(playerFile, transaction.getCompound("player_data"));
        Files.delete(pending);
    }

    /** 日志已提交但最终文件未全部写完，宿主必须停止写入并在下次启动恢复。 */
    static final class RecoveryRequiredException extends IOException {
        RecoveryRequiredException(IOException cause) {
            super("地面背包事务已记录，需要停止服务器并恢复", cause);
        }
    }
}
