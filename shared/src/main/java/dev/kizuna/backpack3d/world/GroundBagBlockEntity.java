package dev.kizuna.backpack3d.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

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
    protected void saveAdditional(CompoundTag nbt) {
        super.saveAdditional(nbt);
        nbt.putString("container", container);
        nbt.putString("item_type", itemType);
        nbt.putFloat("yaw", yaw);
    }

    @Override
    public void load(CompoundTag nbt) {
        super.load(nbt);
        container = nbt.getString("container");
        itemType = nbt.getString("item_type");
        yaw = nbt.getFloat("yaw");
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
