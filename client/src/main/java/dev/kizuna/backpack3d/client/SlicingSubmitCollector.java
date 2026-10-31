package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * 内景只显示部分格层时包住提交收集器：物品和实体模型的四边形改成自定义几何，经 {@link SliceVertexConsumer} 裁到可见层。
 *
 * <p>其余图元（文字、线段等）原样转交，与 1.20.1 只包 QUADS 批次的行为相同；方块模型提交没有物品渲染会用到，直接报错。
 */
final class SlicingSubmitCollector implements SubmitNodeCollector {
    // 与原版物品特性渲染器的特殊附魔光效贴图比例相同。
    private static final float SPECIAL_FOIL_TEXTURE_SCALE = 1 / 128f;
    private final SubmitNodeCollector root;
    private final OrderedSubmitNodeCollector target;
    private final Matrix4f sceneInverse;
    private final float lower;
    private final float upper;

    /**
     * @param sceneInverse 画中画坐标到容器坐标的逆变换
     * @param lower 可见范围下界，容器坐标，单位为格
     * @param upper 可见范围上界，容器坐标，单位为格
     */
    SlicingSubmitCollector(SubmitNodeCollector root, Matrix4f sceneInverse, float lower, float upper) {
        this(root, root, sceneInverse, lower, upper);
    }

    private SlicingSubmitCollector(
            SubmitNodeCollector root,
            OrderedSubmitNodeCollector target,
            Matrix4f sceneInverse,
            float lower,
            float upper) {
        this.root = root;
        this.target = target;
        this.sceneInverse = sceneInverse;
        this.lower = lower;
        this.upper = upper;
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return new SlicingSubmitCollector(root, root.order(order), sceneInverse, lower, upper);
    }

    @Override
    public void submitCustomGeometry(PoseStack matrices, RenderType renderType, CustomGeometryRenderer renderer) {
        // 只裁四边形；线段等其他图元原样提交。
        if (renderType.primitiveTopology() != PrimitiveTopology.QUADS) {
            target.submitCustomGeometry(matrices, renderType, renderer);
            return;
        }
        target.submitCustomGeometry(
                matrices,
                renderType,
                (pose, consumer) -> {
                    var slicer = new SliceVertexConsumer(consumer, sceneInverse, lower, upper);
                    renderer.render(pose, slicer);
                    slicer.finish();
                });
    }

    @Override
    public void submitItem(
            PoseStack matrices,
            ItemDisplayContext displayContext,
            int lightCoords,
            int overlayCoords,
            int outlineColor,
            int[] tintLayers,
            List<BakedQuad> quads,
            ItemStackRenderState.FoilType foilType) {
        // 照原版物品特性渲染器写顶点：按材质渲染类型分组逐面写入，附魔光效再覆盖一遍；内景物品不画描边。
        Map<RenderType, List<BakedQuad>> groups =
                quads.stream()
                        .collect(
                                Collectors.groupingBy(
                                        quad -> quad.materialInfo().itemRenderType(),
                                        LinkedHashMap::new,
                                        Collectors.toList()));
        for (var group : groups.entrySet()) {
            submitCustomGeometry(
                    matrices,
                    group.getKey(),
                    (pose, consumer) -> {
                        var instance = quadInstance(lightCoords, overlayCoords);
                        for (BakedQuad quad : group.getValue()) {
                            instance.setColor(tint(tintLayers, quad.materialInfo()));
                            consumer.putBakedQuad(pose, quad, instance);
                        }
                    });
        }
        if (foilType == ItemStackRenderState.FoilType.NONE) {
            return;
        }
        submitCustomGeometry(
                matrices,
                RenderTypes.glint(),
                (pose, consumer) -> {
                    // 特殊光效按顶点位置投影贴图，包在切层外层，裁出的交点也能取到正确坐标。
                    VertexConsumer foil = consumer;
                    if (foilType == ItemStackRenderState.FoilType.SPECIAL) {
                        foil = new SheetedDecalTextureGenerator(consumer, pose.copy(), SPECIAL_FOIL_TEXTURE_SCALE);
                    }
                    var instance = quadInstance(lightCoords, overlayCoords);
                    for (BakedQuad quad : quads) {
                        foil.putBakedQuad(pose, quad, instance);
                    }
                });
    }

    @Override
    public <S> void submitModel(
            Model<? super S> model,
            S state,
            PoseStack matrices,
            RenderType renderType,
            int lightCoords,
            int overlayCoords,
            int tintedColor,
            TextureAtlasSprite sprite,
            int outlineColor,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        // 箱子、盾牌等特殊物品经实体模型提交；照原版模型特性渲染器写顶点，内景没有描边和破坏裂纹。
        submitCustomGeometry(
                matrices,
                renderType,
                (pose, consumer) -> {
                    var local = new PoseStack();
                    local.last().set(pose);
                    VertexConsumer buffer = consumer;
                    if (sprite != null) {
                        buffer = sprite.wrap(consumer);
                    }
                    model.setupAnim(state);
                    model.renderToBuffer(local, buffer, lightCoords, overlayCoords, tintedColor);
                });
    }

    @Override
    public void submitShadow(PoseStack matrices, float radius, List<EntityRenderState.ShadowPiece> pieces) {
        target.submitShadow(matrices, radius, pieces);
    }

    @Override
    public void submitNameTag(
            PoseStack matrices,
            Vec3 attachment,
            int offset,
            Component name,
            boolean seeThrough,
            int lightCoords,
            CameraRenderState camera) {
        target.submitNameTag(matrices, attachment, offset, name, seeThrough, lightCoords, camera);
    }

    @Override
    public void submitText(
            PoseStack matrices,
            float x,
            float y,
            FormattedCharSequence text,
            boolean dropShadow,
            Font.DisplayMode displayMode,
            int lightCoords,
            int color,
            int backgroundColor,
            int outlineColor) {
        target.submitText(
                matrices, x, y, text, dropShadow, displayMode, lightCoords, color, backgroundColor, outlineColor);
    }

    @Override
    public void submitFlame(PoseStack matrices, EntityRenderState state, Quaternionf rotation) {
        target.submitFlame(matrices, state, rotation);
    }

    @Override
    public void submitLeash(PoseStack matrices, EntityRenderState.LeashState leash) {
        target.submitLeash(matrices, leash);
    }

    @Override
    public void submitMovingBlock(PoseStack matrices, MovingBlockRenderState state, int outlineColor) {
        throw unsupported("submitMovingBlock");
    }

    @Override
    public void submitBlockModel(
            PoseStack matrices,
            RenderType renderType,
            List<BlockStateModelPart> parts,
            int[] tintLayers,
            int lightCoords,
            int overlayCoords,
            int outlineColor) {
        throw unsupported("submitBlockModel");
    }

    @Override
    public void submitBreakingBlockModel(PoseStack matrices, List<BlockStateModelPart> parts, int progress) {
        throw unsupported("submitBreakingBlockModel");
    }

    @Override
    public void submitShapeOutline(
            PoseStack matrices, VoxelShape shape, RenderType renderType, int color, float width, boolean afterTerrain) {
        target.submitShapeOutline(matrices, shape, renderType, color, width, afterTerrain);
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {
        target.submitQuadParticleGroup(particles);
    }

    @Override
    public void submitGizmoPrimitives(
            DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
        target.submitGizmoPrimitives(group, camera, onTop);
    }

    private static QuadInstance quadInstance(int lightCoords, int overlayCoords) {
        var instance = new QuadInstance();
        instance.setLightCoords(lightCoords);
        instance.setOverlayCoords(overlayCoords);
        return instance;
    }

    private static int tint(int[] tintLayers, BakedQuad.MaterialInfo material) {
        // 与原版相同：未着色或越界的图层用白色。
        int layer = material.tintIndex();
        if (!material.isTinted() || layer < 0 || layer >= tintLayers.length) {
            return -1;
        }
        return tintLayers[layer];
    }

    private static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException("内景切层只裁物品与实体模型的四边形，不支持 " + method);
    }
}
