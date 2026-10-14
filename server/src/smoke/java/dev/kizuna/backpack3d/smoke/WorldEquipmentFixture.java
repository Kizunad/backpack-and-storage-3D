package dev.kizuna.backpack3d.smoke;

import com.mojang.authlib.GameProfile;

import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.server.PlayerStorage;
import dev.kizuna.backpack3d.service.TransferService;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** 隔离世界中的另一位玩家实体；通过真实原版跟踪和 Framework 路由验收外观广播。 */
final class WorldEquipmentFixture {
    static final UUID SUBJECT = UUID.fromString("f270cae7-a374-4d87-8531-d049efaf90bc");
    private final Path command = Path.of("../world-appearance-step.txt");
    private int applied = -1;
    private ServerPlayerEntity subject;
    private SavedInventory equipped;
    private double homeX;
    private double homeY;
    private double homeZ;

    static void register() {
        var fixture = new WorldEquipmentFixture();
        ServerTickEvents.END_SERVER_TICK.register(fixture::tick);
    }

    private void tick(MinecraftServer server) {
        // 只在夹具显式写入步骤后操作；不改变测试者自身库存及两阶段修订号。
        if (!Files.isRegularFile(command) || server.getPlayerManager().getPlayerList().isEmpty()) {
            return;
        }
        try {
            String requested = Files.readString(command).strip();
            if (requested.isEmpty()) {
                return;
            }
            int step = Integer.parseInt(requested);
            if (applied == -1 && step != 0) {
                return;
            }
            if (step == applied) {
                return;
            }
            var viewer = server.getPlayerManager().getPlayerList().get(0);
            switch (step) {
                case 0 -> spawn(server, viewer);
                case 1 -> {
                    var moved = equipped.toBuilder();
                    for (int index = 0; index < moved.getWornCount(); index++) {
                        var worn = moved.getWorn(index).toBuilder();
                        if (worn.getMount() == Mount.TORSO_BACK) {
                            worn.setMount(Mount.TORSO_FRONT);
                        } else {
                            worn.setWaistAngleDegrees(135);
                        }
                        moved.setWorn(index, worn);
                    }
                    equipped = moved.build();
                    ((PlayerStorage) subject).bs3d$setInventory(equipped);
                }
                case 2 ->
                        ((PlayerStorage) subject)
                                .bs3d$setInventory(equipped.toBuilder().clearWorn().build());
                case 3 -> ((PlayerStorage) subject).bs3d$setInventory(equipped);
                case 4 -> subject.setPosition(homeX + 1024, homeY, homeZ);
                case 5 -> subject.setPosition(homeX, homeY, homeZ);
                case 6 ->
                        subject.getServerWorld()
                                .removePlayer(subject, Entity.RemovalReason.DISCARDED);
                default -> throw new IllegalArgumentException("未知世界外观验收步骤");
            }
            applied = step;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private void spawn(MinecraftServer server, ServerPlayerEntity viewer) {
        // 观察者仍是实际联网客户端；假玩家只作为被观察的服务器实体，没有额外游戏窗口。
        subject =
                new ServerPlayerEntity(
                        server, viewer.getServerWorld(), new GameProfile(SUBJECT, "WornSubject"));
        subject.networkHandler =
                new ServerPlayNetworkHandler(
                        server, new ClientConnection(NetworkSide.SERVERBOUND), subject);
        equipped = TransferService.upgrade(SavedInventory.newBuilder().setFormatVersion(2).build());
        ((PlayerStorage) subject).bs3d$setInventory(equipped);
        homeX = viewer.getX() + 2;
        homeY = viewer.getY();
        homeZ = viewer.getZ() + 2;
        subject.refreshPositionAndAngles(homeX, homeY, homeZ, 0, 0);
        subject.setNoGravity(true);
        viewer.networkHandler.sendPacket(
                new PlayerListS2CPacket(PlayerListS2CPacket.Action.ADD_PLAYER, subject));
        subject.getServerWorld().onPlayerConnected(subject);
    }
}
