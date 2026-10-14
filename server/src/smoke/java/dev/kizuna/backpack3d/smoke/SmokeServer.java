package dev.kizuna.backpack3d.smoke;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.PlayerSlots;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.server.PlayerStorage;
import dev.kizuna.backpack3d.service.TransferService;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.nio.file.*;

/** 仅运行夹具提供真实原版测试物品，生产 JAR 不含赠送物品逻辑。 */
public final class SmokeServer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        if (!Boolean.getBoolean("bs3d.smoke")) {
            return;
        }
        write("fixture.pid", Long.toString(ProcessHandle.current().pid()));
        WorldEquipmentFixture.register();
        GroundSmokeFixture.register();
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> {
                    var player = handler.player;
                    GroundSmokeFixture.prepare(player);
                    if (player.hasNoGravity()) {
                        throw new IllegalStateException("临时位置锁错误地改变了重力存档");
                    }
                    if (((PlayerStorage) player).bs3d$getInventory().getRevision() == 0) {
                        // 夹具同时覆盖旧固定容器升级；生产中新玩家不会自动获赠背包。
                        ((PlayerStorage) player)
                                .bs3d$setInventory(
                                        TransferService.upgrade(
                                                SavedInventory.newBuilder()
                                                        .setFormatVersion(2)
                                                        .build()));
                        var sword = new ItemStack(Items.IRON_SWORD);
                        sword.setCustomName(Text.literal("旧铁剑"));
                        sword.setDamage(17);
                        player.getInventory().main.set(0, sword);
                        player.getInventory().main.set(1, new ItemStack(Items.STONE, 16));
                        player.getInventory().main.set(2, new ItemStack(Items.IRON_PICKAXE));
                        player.getInventory()
                                .main
                                .set(3, ContainerItems.create(ContainerItems.POUCH));
                        player.getInventory()
                                .main
                                .set(4, ContainerItems.create(ContainerItems.BOX));
                        player.getInventory()
                                .setStack(
                                        PlayerSlots.armorSlot(EquipmentSlot.HEAD),
                                        new ItemStack(Items.IRON_HELMET));
                        player.playerScreenHandler.sendContentUpdates();
                    }
                });
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> {
                    // 每阶段完全停止服务器，下一阶段从磁盘重新加载；网络线程只调度，不等待自己。
                    server.execute(
                            () -> {
                                write(
                                        "server-result.txt",
                                        "revision="
                                                + ((PlayerStorage) handler.player)
                                                        .bs3d$getInventory()
                                                        .getRevision());
                                server.stop(false);
                            });
                });
    }

    private static void write(String name, String contents) {
        try {
            Files.writeString(Path.of(name), contents);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }
}
