package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import dev.kizuna.backpack3d.client.mixin.SpecialModelRenderersAccess;
import dev.kizuna.backpack3d.item.ContainerItems;

import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * 背包在手中、原版库存和内景里复用同一份三维模型。
 *
 * <p>物品定义 {@code assets/<ns>/items/<id>.json} 用 {@code minecraft:special} 引用 {@code bs3d:container}，
 * 与 1.20.1 由 {@code builtin/entity} 父模型选择自定义渲染的语义相同。
 */
final class ContainerItemModels implements SpecialModelRenderer<ModelAsset> {
    static final Identifier TYPE = Identifier.fromNamespaceAndPath("bs3d", "container");
    private static final MapCodec<Unbaked> CODEC = MapCodec.unit(new Unbaked());
    private static final float MODEL_UNITS_PER_BLOCK = 16;
    private static final float ITEM_CENTER = 0.5f;

    static void register() {
        // Fabric 0.161 没有公开的特殊物品渲染器注册入口，经访问器写进原版类型表；有公开入口后换掉。
        // 必须在首次资源加载解析 items/ 定义之前注册。
        SpecialModelRenderersAccess.idMapper().put(TYPE, CODEC);
    }

    @Override
    public ModelAsset extractArgument(ItemStack stack) {
        // 模型随资源重载整体替换；未登记的模型返回空、不绘制，与 1.20.1 相同。
        return ModelResources.find(ContainerItems.definition(stack).model());
    }

    @Override
    public void submit(
            ModelAsset asset,
            PoseStack matrices,
            SubmitNodeCollector collector,
            int light,
            int overlay,
            boolean hasFoil,
            int outlineColor) {
        // 原版物品局部系中心为半格；资产以模型像素计量，绘制后恢复调用方矩阵。
        if (asset == null) {
            return;
        }
        matrices.pushPose();
        matrices.translate(ITEM_CENTER, ITEM_CENTER, ITEM_CENTER);
        float scale = 1 / MODEL_UNITS_PER_BLOCK;
        matrices.scale(scale, scale, scale);
        asset.render(matrices, collector, 0, null, light, overlay);
        matrices.popPose();
    }

    @Override
    public void getExtents(Consumer<Vector3fc> output) {
        // 烘焙时不知道具体物品，导入模型也可能随后重载，按一格方块给出包围盒。
        for (var corner : ModelAsset.vertices(new Vector3f(0), new Vector3f(1))) {
            output.accept(corner);
        }
    }

    record Unbaked() implements SpecialModelRenderer.Unbaked<ModelAsset> {
        @Override
        public SpecialModelRenderer<ModelAsset> bake(BakingContext context) {
            return new ContainerItemModels();
        }

        @Override
        public MapCodec<Unbaked> type() {
            return CODEC;
        }
    }
}
