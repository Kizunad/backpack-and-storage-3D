package dev.kizuna.backpack3d.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** 区块只保存位置标记和公开外观，完整物品保存在服务端地面库存中。 */
public final class GroundBagBlockEntity extends BlockEntity {
    private String container = "";
    private String itemType = "";
    private float yaw;

    public GroundBagBlockEntity(BlockPos pos, BlockState state) {
        super(GroundBags.ENTITY_TYPE, pos, state);
    }

    public String container() {
        return container;
    }

    public String itemType() {
        return itemType;
    }

    public float yaw() {
        return yaw;
    }

    public void appearance(String id, String type, float angleDegrees) {
        // 通知附近客户端更新模型；容器内容不进入区块同步包。
        container = id;
        itemType = type;
        yaw = angleDegrees;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("container", container);
        output.putString("item_type", itemType);
        output.putFloat("yaw", yaw);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        // 缺字段时与 1.20.1 的 NBT 读取一致：字符串取空、角度取 0。
        super.loadAdditional(input);
        container = input.getStringOr("container", "");
        itemType = input.getStringOr("item_type", "");
        yaw = input.getFloatOr("yaw", 0);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
