package dev.kizuna.backpack3d.smoke;

import com.mojang.authlib.GameProfile;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item;
import dev.kizuna.backpack3d.server.GroundInventory;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** 仅地面验收阶段提供带内容的实物，下一阶段直接读取生产持久化结果。 */
final class GroundSmokeFixture {
    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(
                server -> {
                    Path request = Path.of("../ground-access-check.txt");
                    Path result = Path.of("../ground-access-ok.txt");
                    if (!Files.isRegularFile(request)
                            || Files.exists(result)
                            || server.getPlayerList().getPlayers().isEmpty()) {
                        return;
                    }
                    try {
                        String id = Files.readString(request).strip();
                        if (id.isEmpty()) {
                            return;
                        }
                        var viewer = server.getPlayerList().getPlayers().get(0);
                        var other =
                                new ServerPlayer(
                                        server,
                                        viewer.level(),
                                        new GameProfile(UUID.randomUUID(), "GroundObserver"),
                                        ClientInformation.createDefault());
                        other.setPos(viewer.position());
                        var ground = GroundInventory.get(server);
                        try {
                            ground.acquire(other, id);
                            throw new IllegalStateException("第二位玩家错误取得了地面背包访问权");
                        } catch (IllegalArgumentException expected) {
                            if (!expected.getMessage().contains("另一位玩家")) {
                                throw new IllegalStateException("未走到独占访问校验", expected);
                            }
                        }
                        other.setPos(viewer.position().add(20, 0, 0));
                        try {
                            ground.requireAccess(other, id);
                            throw new IllegalStateException("远处玩家仍能访问地面背包");
                        } catch (IllegalArgumentException expected) {
                            Files.writeString(result, "exclusive-access; out-of-range-rejected");
                        }
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                });
    }

    static void prepare(ServerPlayer player) {
        try {
            if (!Files.readString(Path.of("../client/phase.txt")).strip().equals("ground")) {
                return;
            }
            var bag = ContainerItems.create(SmokeContainers.POUCH);
            String id = ContainerItems.identity(bag);
            var sword = new ItemStack(Items.IRON_SWORD);
            sword.setDamageValue(17);
            sword.set(DataComponents.CUSTOM_NAME, Component.literal("地面测试铁剑"));
            ContainerItems.setContents(
                    bag,
                    List.of(
                            stored(player, id, sword, 0, 0, 0, 1, 2, 1),
                            stored(player, id, new ItemStack(Items.STONE, 8), 0, 0, 1, 1, 1, 1)));
            player.getInventory().setSelectedSlot(0);
            player.getInventory().setItem(0, bag);
            player.getInventory().setItem(Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
            player.inventoryMenu.broadcastChanges();
            Files.writeString(Path.of("../ground-test-id.txt"), id);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Item stored(
            ServerPlayer player,
            String container,
            ItemStack stack,
            int x,
            int y,
            int z,
            int width,
            int height,
            int depth) {
        return Item.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setContainer(container)
                .setCell(Volumes.cell(x, y, z))
                .setSize(Volumes.size(width, height, depth))
                .setBaseSize(Volumes.size(width, height, depth))
                .setStackNbt(StackCodec.encode(stack, player.registryAccess()))
                .build();
    }
}
