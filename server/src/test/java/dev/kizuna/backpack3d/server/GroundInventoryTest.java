package dev.kizuna.backpack3d.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.backpack3d.TestContainers;
import dev.kizuna.backpack3d.TestRegistries;
import dev.kizuna.backpack3d.item.ContainerItems;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

class GroundInventoryTest {
    @BeforeAll
    static void bootstrap() {
        TestRegistries.bootstrap();
    }

    @Test
    void removedContainerOnGroundRejectsTheWholeCatalog() {
        // 已移除的背篓和其他未注册物品一样按损坏数据处理，整个地面目录拒绝加载。
        var stack = ContainerItems.create(TestContainers.POUCH);
        String id = ContainerItems.identity(stack);
        var entry = new GroundInventory.Entry(id, "minecraft:overworld", BlockPos.ZERO, 0, 0, stack);
        CompoundTag root = GroundInventory.write(Map.of(id, entry), TestRegistries.VANILLA);
        root.getListOrEmpty("bags").getCompoundOrEmpty(0).getCompoundOrEmpty("stack").putString("id", "bs3d:back_basket");
        assertThrows(IOException.class, () -> GroundInventory.read(root, TestRegistries.VANILLA));
    }
}
