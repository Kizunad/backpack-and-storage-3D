package dev.kizuna.backpack3d.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.backpack3d.TestContainers;
import dev.kizuna.backpack3d.TestRegistries;
import dev.kizuna.backpack3d.item.ContainerItems;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

class GroundInventoryTest {
    @BeforeAll
    static void bootstrap() {
        TestRegistries.bootstrap();
    }

    @Test
    void retiredGroundRecordIsKeptVerbatimBesideUsableBags() throws IOException {
        var stack = ContainerItems.create(TestContainers.POUCH);
        String id = ContainerItems.identity(stack);
        var entry = new GroundInventory.Entry(id, "minecraft:overworld", BlockPos.ZERO, 90, 3, stack);
        var root = GroundInventory.write(Map.of(id, entry), List.of(), TestRegistries.VANILLA);
        // 同一份记录改成已移除的背篓 ID、换个位置，模拟旧版本留在地上的背篓。
        var old = root.getListOrEmpty("bags").getCompoundOrEmpty(0).copy();
        old.getCompoundOrEmpty("stack").putString("id", "bs3d:back_basket");
        old.putLong("pos", new BlockPos(4, 64, 4).asLong());
        root.getListOrEmpty("bags").add(old);

        var catalog = GroundInventory.read(root, TestRegistries.VANILLA);
        assertEquals(List.of(id), List.copyOf(catalog.entries().keySet()));
        assertEquals(List.of(old), catalog.retired());
        ListTag bags = GroundInventory.write(catalog.entries(), catalog.retired(), TestRegistries.VANILLA)
                .getListOrEmpty("bags");
        assertEquals(2, bags.size());
        assertEquals(old, bags.get(1));
    }

    @Test
    void otherUnregisteredGroundItemsStillFailToLoad() {
        var stack = ContainerItems.create(TestContainers.POUCH);
        String id = ContainerItems.identity(stack);
        var entry = new GroundInventory.Entry(id, "minecraft:overworld", BlockPos.ZERO, 0, 0, stack);
        CompoundTag root = GroundInventory.write(Map.of(id, entry), List.of(), TestRegistries.VANILLA);
        root.getListOrEmpty("bags").getCompoundOrEmpty(0).getCompoundOrEmpty("stack").putString("id", "bs3d:missing");
        assertThrows(IOException.class, () -> GroundInventory.read(root, TestRegistries.VANILLA));
    }
}
