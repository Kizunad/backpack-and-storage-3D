package dev.kizuna.backpack3d.client;

import dev.kizuna.backpack3d.item.ContainerItems;

import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;

/** 背包在手中、原版库存和内景里复用同一份三维模型。 */
final class ContainerItemModels {
    private static final float MODEL_UNITS_PER_BLOCK = 16;
    private static final float ITEM_CENTER = 0.5f;

    private ContainerItemModels() {}

    static void register() {
        // 仅 parent=builtin/entity 的物品会调用此入口，标准方块模型不受影响。
        for (var entry : ContainerItems.types().entrySet()) {
            var type = entry.getValue();
            BuiltinItemRendererRegistry.INSTANCE.register(
                    entry.getKey(),
                    (stack, mode, matrices, buffers, light, overlay) ->
                            render(type.model(), matrices, buffers, light, overlay));
        }
    }

    private static void render(
            String model,
            MatrixStack matrices,
            VertexConsumerProvider buffers,
            int light,
            int overlay) {
        // 原版物品局部系中心为半格；资产以模型像素计量，绘制后恢复调用方矩阵。
        ModelAsset asset = ModelResources.find(model);
        if (asset == null) {
            return;
        }
        matrices.push();
        try {
            matrices.translate(ITEM_CENTER, ITEM_CENTER, ITEM_CENTER);
            float scale = 1 / MODEL_UNITS_PER_BLOCK;
            matrices.scale(scale, scale, scale);
            asset.render(matrices, buffers, 0, null, light, overlay);
        } finally {
            matrices.pop();
        }
    }
}
