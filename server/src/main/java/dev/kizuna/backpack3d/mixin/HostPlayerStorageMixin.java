package dev.kizuna.backpack3d.mixin;

import dev.kizuna.backpack3d.server.PlayerStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.LevelResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/** 单机房主也以事务提交的 playerdata 为准，避免较旧的 level.dat 回滚库存。 */
@Mixin(PlayerList.class)
public abstract class HostPlayerStorageMixin {
    @Inject(method = "load", at = @At("HEAD"), cancellable = true)
    private void bs3d$loadHost(ServerPlayer player, CallbackInfoReturnable<CompoundTag> result) {
        // 首次安装及没有业务字段的旧世界保留原版加载路径；仅接管已有业务玩家文件。
        var server = player.getServer();
        if (server == null || !server.isSingleplayerOwner(player.getGameProfile())) {
            return;
        }
        var file = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player.getStringUUID() + ".dat");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            var saved = NbtIo.readCompressed(file.toFile());
            if (!saved.contains(PlayerStorage.NBT_KEY)) {
                return;
            }
            // 沿用原版 DataFixer，再一次性读取整份玩家状态，不拼接两个不同时间的库存。
            var updated = DataFixTypes.PLAYER.updateToCurrentVersion(
                    DataFixers.getDataFixer(), saved, NbtUtils.getDataVersion(saved, -1));
            player.load(updated);
            result.setReturnValue(updated);
        } catch (IOException failure) {
            throw new UncheckedIOException("无法读取单机玩家库存，拒绝回退到旧存档", failure);
        }
    }
}
