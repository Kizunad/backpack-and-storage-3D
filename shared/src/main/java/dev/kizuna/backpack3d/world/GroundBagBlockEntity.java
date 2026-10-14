package dev.kizuna.backpack3d.world;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;

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
        markDirty();
        if (world != null) {
            world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_ALL);
        }
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putString("container", container);
        nbt.putString("item_type", itemType);
        nbt.putFloat("yaw", yaw);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        container = nbt.getString("container");
        itemType = nbt.getString("item_type");
        yaw = nbt.getFloat("yaw");
    }

    @Override
    public NbtCompound toInitialChunkDataNbt() {
        return createNbt();
    }

    @Override
    public BlockEntityUpdateS2CPacket toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }
}
