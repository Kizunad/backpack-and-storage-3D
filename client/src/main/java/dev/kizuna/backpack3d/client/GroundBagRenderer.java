package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.world.GroundBagBlockEntity;
import dev.kizuna.backpack3d.world.GroundBags;

import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** 地面外观读取公开类型；不需要收到完整物品数据就能显示背包。 */
final class GroundBagRenderer implements BlockEntityRenderer<GroundBagBlockEntity, GroundBagRenderer.State> {
    private static final float BLOCK_CENTER = 0.5f;
    private static final float MODEL_PIXELS_PER_BLOCK = 16;
    private static final float POUCH_SCALE = 0.5f;
    private static final float BASKET_SCALE = 0.8f;
    private final ItemModelResolver items;

    private GroundBagRenderer(BlockEntityRendererProvider.Context context) {
        items = context.itemModelResolver();
    }

    static void register() {
        BlockEntityRendererRegistry.register(GroundBags.ENTITY_TYPE, GroundBagRenderer::new);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(
            GroundBagBlockEntity bag,
            State state,
            float partialTicks,
            Vec3 cameraPosition,
            ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        // 26.2 先抽取渲染快照再提交；类型、模型和收纳箱的物品外观都在这里定下。
        BlockEntityRenderer.super.extractRenderState(bag, state, partialTicks, cameraPosition, breakProgress);
        state.yaw = bag.yaw();
        state.type = null;
        state.asset = null;
        state.item.clear();
        Identifier id = Identifier.tryParse(bag.itemType());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return;
        }
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(id));
        state.type = ContainerItems.definition(stack);
        if (state.type == null) {
            return;
        }
        state.asset = ModelResources.optional(state.type.model());
        if (state.asset != null) {
            return;
        }
        if (state.type.mounts().isEmpty()) {
            // 收纳箱沿用注册物品模型。
            items.updateForTopItem(
                    state.item, stack, ItemDisplayContext.FIXED, bag.getLevel(), null, (int) bag.getBlockPos().asLong());
        } else {
            // 可穿戴容器缺少登记模型时只警告一次，不画。
            ModelResources.find(state.type.model());
        }
    }

    @Override
    public void submit(State state, PoseStack matrices, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.asset == null && state.item.isEmpty()) {
            return;
        }
        matrices.pushPose();
        matrices.translate(BLOCK_CENTER, 0, BLOCK_CENTER);
        matrices.mulPose(Axis.YP.rotationDegrees(-state.yaw));
        if (state.asset == null) {
            matrices.translate(0, BLOCK_CENTER, 0);
            state.item.submit(matrices, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        } else {
            // 草包和背篓按几何底部贴地，不悬空。
            float scale = (state.type.waist() ? POUCH_SCALE : BASKET_SCALE) / MODEL_PIXELS_PER_BLOCK;
            matrices.translate(0, state.asset.groundOffset() * scale, 0);
            matrices.scale(scale, scale, scale);
            state.asset.render(matrices, collector, 0, null, state.lightCoords, OverlayTexture.NO_OVERLAY);
        }
        matrices.popPose();
    }

    static final class State extends BlockEntityRenderState {
        // 朝向，单位为度，与方块实体保存的偏航角相同。
        float yaw;
        ContainerItems.Definition type;
        ModelAsset asset;
        final ItemStackRenderState item = new ItemStackRenderState();
    }
}
