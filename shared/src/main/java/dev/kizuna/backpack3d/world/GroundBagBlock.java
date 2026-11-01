package dev.kizuna.backpack3d.world;

import com.mojang.serialization.MapCodec;
import dev.kizuna.backpack3d.item.ContainerItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 地面背包通过空手 Shift+右键收回，不产生普通掉落物的自动拾取与销毁行为。 */
final class GroundBagBlock extends BaseEntityBlock {
    // 交互与碰撞共用半径，单位为原版模型像素（每方块 16 像素）。
    private static final double EDGE_INSET_PIXELS = 3;
    private static final double TOP_PIXELS = 18;
    private static final double BLOCK_PIXELS = 16;
    private static final double WAIST_INSET_PIXELS = 5;
    private static final double WAIST_TOP_PIXELS = 7;
    private static final VoxelShape SHAPE =
            box(
                    EDGE_INSET_PIXELS,
                    0,
                    EDGE_INSET_PIXELS,
                    BLOCK_PIXELS - EDGE_INSET_PIXELS,
                    TOP_PIXELS,
                    BLOCK_PIXELS - EDGE_INSET_PIXELS);
    private static final VoxelShape WAIST_SHAPE =
            box(
                    WAIST_INSET_PIXELS,
                    0,
                    WAIST_INSET_PIXELS,
                    BLOCK_PIXELS - WAIST_INSET_PIXELS,
                    WAIST_TOP_PIXELS,
                    BLOCK_PIXELS - WAIST_INSET_PIXELS);
    private static final MapCodec<GroundBagBlock> CODEC = simpleCodec(GroundBagBlock::new);

    GroundBagBlock(Properties settings) {
        super(settings);
    }

    @Override
    protected MapCodec<GroundBagBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GroundBagBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public VoxelShape getShape(
            BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        // 腰挂容器使用更小的碰撞与命中体积，不能在小模型上方留下不可见的大障碍。
        if (world.getBlockEntity(pos) instanceof GroundBagBlockEntity bag) {
            Identifier id = Identifier.tryParse(bag.itemType());
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                var type = ContainerItems.definition(new ItemStack(BuiltInRegistries.ITEM.getValue(id)));
                if (type != null && type.waist()) {
                    return WAIST_SHAPE;
                }
            }
        }
        return SHAPE;
    }

    @Override
    protected InteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level world,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit) {
        // 原版交互包提供位置与手，真正的身份、距离和访问权由业务服务器复核。
        // 26.2 的 useItemOn 按手调用、手里为空也调用，与 1.20.1 的 use 时机相同，所以不另覆盖 useWithoutItem。
        if (world.getBlockEntity(pos) instanceof GroundBagBlockEntity bag) {
            return GroundBags.use(player, hand, bag);
        }
        return InteractionResult.FAIL;
    }
}
