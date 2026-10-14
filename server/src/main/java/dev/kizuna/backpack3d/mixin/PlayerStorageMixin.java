package dev.kizuna.backpack3d.mixin;

import dev.kizuna.backpack3d.protocol.Messages;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.server.PlayerStorage;
import dev.kizuna.backpack3d.service.TransferService;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.server.network.ServerPlayerEntity;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public abstract class PlayerStorageMixin implements PlayerStorage {
    @Unique private SavedInventory bs3d$inventory = TransferService.empty();

    @Override
    public SavedInventory bs3d$getInventory() {
        return bs3d$inventory;
    }

    @Override
    public void bs3d$setInventory(SavedInventory value) {
        bs3d$inventory = value;
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void bs3d$read(NbtCompound nbt, CallbackInfo callback) {
        // 缺字段的新玩家从空装备开始，错误类型与损坏存档必须拒绝，不能清空后覆盖。
        if (!nbt.contains(NBT_KEY)) {
            bs3d$inventory = TransferService.empty();
            return;
        }
        if (!nbt.contains(NBT_KEY, NbtElement.BYTE_ARRAY_TYPE)
                || nbt.getByteArray(NBT_KEY).length > Messages.MAX_SAVE_BYTES) {
            throw new IllegalStateException("无效 3D 库存存档");
        }
        try {
            bs3d$inventory =
                    TransferService.upgrade(SavedInventory.parseFrom(nbt.getByteArray(NBT_KEY)));
            TransferService.validate(bs3d$inventory);
        } catch (com.google.protobuf.InvalidProtocolBufferException failure) {
            throw new IllegalStateException("3D 库存存档损坏", failure);
        }
    }

    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void bs3d$write(NbtCompound nbt, CallbackInfo callback) {
        // 业务与原版库存共用同一次 playerdata 写入。
        nbt.putByteArray(NBT_KEY, bs3d$inventory.toByteArray());
    }

    @Inject(method = "copyFrom", at = @At("TAIL"))
    private void bs3d$copy(ServerPlayerEntity previous, boolean alive, CallbackInfo callback) {
        // 初版的个人容器死亡保留；转移过程由会话取消，不复制运行中的事务。
        bs3d$inventory = ((PlayerStorage) previous).bs3d$getInventory();
    }
}
