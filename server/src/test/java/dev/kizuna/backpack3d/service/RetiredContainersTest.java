package dev.kizuna.backpack3d.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.backpack3d.TestContainers;
import dev.kizuna.backpack3d.TestRegistries;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 2026-11-01 移除的草包、背篓、收纳箱：旧存档里的实例原样保留，不崩、不丢内容、不能取出。 */
class RetiredContainersTest {
    private static final Size POUCH_OUTSIDE = Volumes.size(2, 2, 2);

    @BeforeAll
    static void bootstrap() {
        TestRegistries.bootstrap();
    }

    @Test
    void wornRetiredContainerIsKeptVerbatimAndNotEquipped() {
        var retired =
                WornContainer.newBuilder()
                        .setStackNbt(TestContainers.retired("bs3d:grass_pouch", List.of(diamond("pouch"))))
                        .setMount(Mount.WAIST_LEFT_FRONT)
                        .setWaistAngleDegrees(45)
                        .build();
        var data = TestContainers.account().toBuilder().addWorn(0, retired).build();
        TransferService.validate(data, TestRegistries.VANILLA);
        var tree = new ContainerTree(TestRegistries.VANILLA, data, new ArrayList<>());
        // 只有夹具里的灵草囊与测试背篓进树；旧草包不出现在客户端视图里。
        assertEquals(2, tree.containers().size());
        assertTrue(tree.items().stream().noneMatch(item -> item.getStackNbt().equals(retired.getStackNbt())));
        var worn = tree.worn();
        assertEquals(3, worn.size());
        assertEquals(retired, worn.get(0));
    }

    @Test
    void nestedRetiredContainerKeepsItsCellsAndCannotBeTaken() {
        var basket = ContainerItems.create(TestContainers.BASKET);
        String basketId = ContainerItems.identity(basket);
        var old =
                Item.newBuilder()
                        .setId(UUID.randomUUID().toString())
                        .setContainer(basketId)
                        .setCell(Volumes.cell(0, 0, 0))
                        .setBaseSize(POUCH_OUTSIDE)
                        .setSize(POUCH_OUTSIDE)
                        .setStackNbt(TestContainers.retired("bs3d:storage_box", List.of(diamond("box"))))
                        .build();
        ContainerItems.setContents(basket, List.of(old));
        var data =
                TransferService.empty().toBuilder()
                        .addWorn(
                                WornContainer.newBuilder()
                                        .setStackNbt(StackCodec.encode(basket, TestRegistries.VANILLA))
                                        .setMount(Mount.TORSO_BACK))
                        .build();
        var inventory = new ArrayList<>(List.of(new ItemStack(Items.STONE)));
        var tree = new ContainerTree(TestRegistries.VANILLA, data, inventory);

        // 客户端看到的是同一格位上的屏障占位，名字带旧物品 ID。
        var shown = tree.items().stream().filter(item -> item.getId().equals(old.getId())).findFirst().orElseThrow();
        assertEquals(old.getCell(), shown.getCell());
        var placeholder = StackCodec.decode(shown.getStackNbt(), TestRegistries.VANILLA);
        assertTrue(placeholder.is(Items.BARRIER));
        assertTrue(placeholder.get(DataComponents.CUSTOM_NAME).toString().contains("bs3d:storage_box"));
        assertThrows(IllegalArgumentException.class, () -> tree.take(old.getId()));

        // 旧容器继续占格：手动放到它的格子被拒绝，自动放入落在别处。
        var stone = tree.takePlayer(0, inventory.get(0), UUID.randomUUID().toString(), Volumes.UNIT);
        var occupied = MoveRequest.newBuilder().setTarget(basketId).setCell(Volumes.cell(1, 1, 1)).build();
        assertThrows(IllegalArgumentException.class, () -> tree.place(stone, occupied));
        tree.place(stone, MoveRequest.newBuilder().setTarget(basketId).setAutoPlace(true).build());
        assertFalse(Volumes.overlaps(old.getCell(), old.getSize(), stone.item().getCell(), stone.item().getSize()));

        // 保存时旧容器的字节（连同其中的内容物）原样写回。
        var saved = ContainerItems.contents(StackCodec.decode(tree.worn().get(0).getStackNbt(), TestRegistries.VANILLA));
        assertEquals(2, saved.getItemsCount());
        assertEquals(old, saved.getItems(0));
    }

    @Test
    void otherUnregisteredItemsAreStillRejected() {
        var basket = ContainerItems.create(TestContainers.BASKET);
        ContainerItems.setContents(
                basket,
                List.of(
                        Item.newBuilder()
                                .setId(UUID.randomUUID().toString())
                                .setContainer(ContainerItems.identity(basket))
                                .setCell(Volumes.cell(0, 0, 0))
                                .setBaseSize(Volumes.UNIT)
                                .setSize(Volumes.UNIT)
                                .setStackNbt(TestContainers.retired("bs3d:not_a_container", List.of()))
                                .build()));
        var data =
                TransferService.empty().toBuilder()
                        .addWorn(
                                WornContainer.newBuilder()
                                        .setStackNbt(StackCodec.encode(basket, TestRegistries.VANILLA))
                                        .setMount(Mount.TORSO_BACK))
                        .build();
        assertThrows(IllegalArgumentException.class, () -> TransferService.validate(data, TestRegistries.VANILLA));
        assertFalse(RetiredContainers.is("bs3d:herb_pouch", "测试"));
    }

    private static Item diamond(String container) {
        return Item.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setContainer(container)
                .setBaseSize(Volumes.UNIT)
                .setSize(Volumes.UNIT)
                .setStackNbt(StackCodec.encode(new ItemStack(Items.DIAMOND, 5), TestRegistries.VANILLA))
                .build();
    }
}
