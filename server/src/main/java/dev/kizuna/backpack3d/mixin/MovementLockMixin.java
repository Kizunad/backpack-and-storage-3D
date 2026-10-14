package dev.kizuna.backpack3d.mixin;

import dev.kizuna.backpack3d.server.VolumeServer;

import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class MovementLockMixin {
    // 位置误差平方，单位为方块²；浮点编码噪声不触发传送纠正。
    @Unique private static final double bs3d$POSITION_EPSILON_SQUARED = 0.000001;
    @Shadow public ServerPlayerEntity player;

    @Inject(method = "onPlayerMove", at = @At("HEAD"), cancellable = true)
    private void bs3d$lockMovement(PlayerMoveC2SPacket packet, CallbackInfo callback) {
        // 网络线程上的首次分派照常进原版线程门；只在主线程查询业务会话。
        var server = player.getServer();
        if (server == null || !server.isOnThread()) {
            return;
        }
        if (!packet.changesPosition() || !VolumeServer.locked(player)) {
            return;
        }
        double x = packet.getX(player.getX());
        double y = packet.getY(player.getY());
        double z = packet.getZ(player.getZ());
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            // 非法坐标继续走原版断线校验，不能被位置锁静默掩盖。
            return;
        }
        // 静止包不反复制造传送。纠正真实移动时保留落地标志，避免下次操作误判腾空。
        if (player.squaredDistanceTo(x, y, z) > bs3d$POSITION_EPSILON_SQUARED) {
            boolean grounded = player.isOnGround();
            player.networkHandler.requestTeleport(
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    player.getYaw(),
                    player.getPitch());
            player.setOnGround(grounded);
        }
        callback.cancel();
    }
}
