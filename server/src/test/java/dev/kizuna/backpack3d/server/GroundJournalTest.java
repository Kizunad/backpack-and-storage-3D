package dev.kizuna.backpack3d.server;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

class GroundJournalTest {
    @TempDir Path root;

    @Test
    void completedJournalWritesBothSnapshots() throws Exception {
        var journal = new GroundJournal(root.resolve("ground"), root.resolve("players"));
        UUID player = UUID.randomUUID();
        journal.commit(player, value("hand", "empty"), value("ground", "bag"));
        assertEquals(
                "empty",
                NbtIo.readCompressed(root.resolve("players/" + player + ".dat").toFile())
                        .getString("hand"));
        assertEquals(
                "bag", NbtIo.readCompressed(journal.inventoryFile().toFile()).getString("ground"));
        assertFalse(Files.exists(root.resolve("ground/pending.dat")));
    }

    @Test
    void failedFinalWriteRecoversBothSidesOnRestart() throws Exception {
        Path blockedPlayers = root.resolve("players");
        Files.writeString(blockedPlayers, "not a directory");
        var journal = new GroundJournal(root.resolve("ground"), blockedPlayers);
        UUID player = UUID.randomUUID();
        assertThrows(
                GroundJournal.RecoveryRequiredException.class,
                () -> journal.commit(player, value("hand", "bag"), value("ground", "empty")));
        assertTrue(Files.exists(root.resolve("ground/pending.dat")));
        Files.delete(blockedPlayers);
        // 重启使用新对象，且重复恢复不会再次扣物品或更改最终快照。
        var restarted = new GroundJournal(root.resolve("ground"), blockedPlayers);
        restarted.recover();
        restarted.recover();
        assertEquals(
                "bag",
                NbtIo.readCompressed(root.resolve("players/" + player + ".dat").toFile())
                        .getString("hand"));
        assertEquals(
                "empty",
                NbtIo.readCompressed(journal.inventoryFile().toFile()).getString("ground"));
    }

    @Test
    void failureBeforeJournalLeavesExistingInventoryUntouched() throws Exception {
        Path blockedGround = root.resolve("ground");
        Files.writeString(blockedGround, "blocked");
        UUID player = UUID.randomUUID();
        Path playerFile = root.resolve("players/" + player + ".dat");
        AtomicNbtFiles.write(playerFile, value("hand", "original"));
        var journal = new GroundJournal(blockedGround, root.resolve("players"));
        assertThrows(
                java.io.IOException.class,
                () -> journal.commit(player, value("hand", "changed"), value("ground", "bag")));
        assertEquals("original", NbtIo.readCompressed(playerFile.toFile()).getString("hand"));
    }

    private static NbtCompound value(String key, String value) {
        var nbt = new NbtCompound();
        nbt.putString(key, value);
        return nbt;
    }
}
