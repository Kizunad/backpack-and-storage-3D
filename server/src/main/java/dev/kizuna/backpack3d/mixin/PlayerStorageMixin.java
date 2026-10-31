package dev.kizuna.backpack3d.mixin;

import dev.kizuna.backpack3d.protocol.Messages;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.server.PlayerStorage;
import dev.kizuna.backpack3d.service.TransferService;
import com.mojang.serialization.Codec;
import java.nio.ByteBuffer;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
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

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void bs3d$read(ValueInput input, CallbackInfo callback) {
        // 缺字段的新玩家从空装备开始，错误类型与损坏存档必须拒绝，不能清空后覆盖。
        // ValueInput 解码失败与缺字段都返回空，因此直通取出原始标签再判断类型。
        var stored = input.read(NBT_KEY, Codec.PASSTHROUGH).map(value -> value.convert(NbtOps.INSTANCE).getValue());
        if (stored.isEmpty()) {
            bs3d$inventory = TransferService.empty();
            return;
        }
        if (!(stored.get() instanceof ByteArrayTag bytes) || bytes.size() > Messages.MAX_SAVE_BYTES) {
            throw new IllegalStateException("无效 3D 库存存档");
        }
        // 物品编解码使用玩家所在服务器的注册表，与原版库存读取同一份数据包内容。
        var registries = ((ServerPlayer) (Object) this).registryAccess();
        try {
            bs3d$inventory =
                    TransferService.upgrade(SavedInventory.parseFrom(bytes.getAsByteArray()), registries);
            TransferService.validate(bs3d$inventory, registries);
        } catch (com.google.protobuf.InvalidProtocolBufferException failure) {
            throw new IllegalStateException("3D 库存存档损坏", failure);
        }
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void bs3d$write(ValueOutput output, CallbackInfo callback) {
        // 业务与原版库存共用同一次 playerdata 写入；字节缓冲在 NBT 中仍写成字节数组。
        output.store(NBT_KEY, Codec.BYTE_BUFFER, ByteBuffer.wrap(bs3d$inventory.toByteArray()));
    }

    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void bs3d$copy(ServerPlayer previous, boolean alive, CallbackInfo callback) {
        // 初版的个人容器死亡保留；转移过程由会话取消，不复制运行中的事务。
        bs3d$inventory = ((PlayerStorage) previous).bs3d$getInventory();
    }
}
