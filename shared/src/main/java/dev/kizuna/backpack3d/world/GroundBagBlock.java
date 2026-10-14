package dev.kizuna.backpack3d.world;

import dev.kizuna.backpack3d.item.ContainerItems;

import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/** 地面背包通过空手 Shift+右键收回，不产生普通掉落物的自动拾取与销毁行为。 */
final class GroundBagBlock extends BlockWithEntity {
    // 交互与碰撞共用半径，单位为原版模型像素（每方块 16 像素）。
    private static final double EDGE_INSET_PIXELS = 3;
    private static final double TOP_PIXELS = 18;
    private static final double BLOCK_PIXELS = 16;
    private static final double POUCH_INSET_PIXELS = 5;
    private static final double POUCH_TOP_PIXELS = 7;
    private static final VoxelShape SHAPE =
            createCuboidShape(
                    EDGE_INSET_PIXELS,
                    0,
                    EDGE_INSET_PIXELS,
                    BLOCK_PIXELS - EDGE_INSET_PIXELS,
                    TOP_PIXELS,
                    BLOCK_PIXELS - EDGE_INSET_PIXELS);
    private static final VoxelShape POUCH_SHAPE =
            createCuboidShape(
                    POUCH_INSET_PIXELS,
                    0,
                    POUCH_INSET_PIXELS,
                    BLOCK_PIXELS - POUCH_INSET_PIXELS,
                    POUCH_TOP_PIXELS,
                    BLOCK_PIXELS - POUCH_INSET_PIXELS);

    GroundBagBlock(Settings settings) {
        super(settings);
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new GroundBagBlockEntity(pos, state);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    @Override
    public VoxelShape getOutlineShape(
            BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        // 草包使用更小的碰撞与命中体积，不能在小模型上方留下不可见的大障碍。
        if (world.getBlockEntity(pos) instanceof GroundBagBlockEntity bag) {
            Identifier id = Identifier.tryParse(bag.itemType());
            if (id != null && Registries.ITEM.containsId(id)) {
                var type = ContainerItems.definition(new ItemStack(Registries.ITEM.get(id)));
                if (type != null && type.waist()) {
                    return POUCH_SHAPE;
                }
            }
        }
        return SHAPE;
    }

    @Override
    public ActionResult onUse(
            BlockState state,
            World world,
            BlockPos pos,
            PlayerEntity player,
            Hand hand,
            BlockHitResult hit) {
        // 原版交互包提供位置与手，真正的身份、距离和访问权由业务服务器复核。
        if (world.getBlockEntity(pos) instanceof GroundBagBlockEntity bag) {
            return GroundBags.use(player, hand, bag);
        }
        return ActionResult.FAIL;
    }
}
