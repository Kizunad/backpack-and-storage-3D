package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.vertex.PoseStack;

import java.util.function.Function;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** 按方块状态选放置模型，随水平朝向转动后画在方块格里。 */
final class PlacedBlockRenderer<E extends BlockEntity> implements BlockEntityRenderer<E, PlacedBlockRenderer.State> {
    private final Function<BlockState, Identifier> model;

    PlacedBlockRenderer(Function<BlockState, Identifier> model) {
        this.model = model;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(
            E blockEntity,
            State state,
            float partialTicks,
            Vec3 cameraPosition,
            ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        // 26.2 先抽取渲染快照再提交；模型和朝向都在这里按当前方块状态定下。
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
        BlockState block = blockEntity.getBlockState();
        state.placed = PlacedModels.find(model.apply(block));
        state.facing = Direction.NORTH;
        if (block.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            state.facing = block.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
    }

    @Override
    public void submit(State state, PoseStack matrices, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.placed == null) {
            return;
        }
        matrices.pushPose();
        PlacedModels.place(matrices, state.placed, state.facing);
        state.placed.asset().render(matrices, collector, 0, null, state.lightCoords, OverlayTexture.NO_OVERLAY);
        matrices.popPose();
    }

    static final class State extends BlockEntityRenderState {
        PlacedModels.Placed placed;
        Direction facing;
    }
}
