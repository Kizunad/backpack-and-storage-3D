package dev.kizuna.backpack3d.mixin;

import dev.kizuna.backpack3d.server.PlayerStorage;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.datafixer.Schemas;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/** 单机房主也以事务提交的 playerdata 为准，避免较旧的 level.dat 回滚库存。 */
@Mixin(PlayerManager.class)
public abstract class HostPlayerStorageMixin {
    @Inject(method = "loadPlayerData", at = @At("HEAD"), cancellable = true)
    private void bs3d$loadHost(ServerPlayerEntity player, CallbackInfoReturnable<NbtCompound> result) {
        // 首次安装及没有业务字段的旧世界保留原版加载路径；仅接管已有业务玩家文件。
        var server = player.getServer();
        if (server == null || !server.isHost(player.getGameProfile())) {
            return;
        }
        var file = server.getSavePath(WorldSavePath.PLAYERDATA).resolve(player.getUuidAsString() + ".dat");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            var saved = NbtIo.readCompressed(file.toFile());
            if (!saved.contains(PlayerStorage.NBT_KEY)) {
                return;
            }
            // 沿用原版 DataFixer，再一次性读取整份玩家状态，不拼接两个不同时间的库存。
            var updated = DataFixTypes.PLAYER.update(
                    Schemas.getFixer(), saved, NbtHelper.getDataVersion(saved, -1));
            player.readNbt(updated);
            result.setReturnValue(updated);
        } catch (IOException failure) {
            throw new UncheckedIOException("无法读取单机玩家库存，拒绝回退到旧存档", failure);
        }
    }
}
