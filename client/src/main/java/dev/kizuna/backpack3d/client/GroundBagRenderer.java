package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.world.GroundBagBlockEntity;
import dev.kizuna.backpack3d.world.GroundBags;

import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** 地面外观读取公开类型；不需要收到完整物品 NBT 就能显示背包。 */
final class GroundBagRenderer implements BlockEntityRenderer<GroundBagBlockEntity> {
    private static final float BLOCK_CENTER = 0.5f;
    private static final float MODEL_PIXELS_PER_BLOCK = 16;
    private static final float POUCH_SCALE = 0.5f;
    private static final float BASKET_SCALE = 0.8f;

    static void register() {
        BlockEntityRendererRegistry.register(
                GroundBags.ENTITY_TYPE, context -> new GroundBagRenderer());
    }

    @Override
    public void render(
            GroundBagBlockEntity bag,
            float delta,
            PoseStack matrices,
            MultiBufferSource buffers,
            int light,
            int overlay) {
        ResourceLocation id = ResourceLocation.tryParse(bag.itemType());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return;
        }
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
        var type = ContainerItems.definition(stack);
        if (type == null) {
            return;
        }
        matrices.pushPose();
        try {
            matrices.translate(BLOCK_CENTER, 0, BLOCK_CENTER);
            matrices.mulPose(Axis.YP.rotationDegrees(-bag.yaw()));
            ModelAsset asset = ModelResources.optional(type.model());
            if (asset == null && type.mounts().isEmpty()) {
                // 收纳箱沿用注册物品模型；草包和背篓按几何底部贴地，不悬空。
                matrices.translate(0, BLOCK_CENTER, 0);
                Minecraft.getInstance()
                        .getItemRenderer()
                        .renderStatic(
                                stack,
                                ItemDisplayContext.FIXED,
                                light,
                                overlay,
                                matrices,
                                buffers,
                                bag.getLevel(),
                                0);
            } else {
                if (asset == null) {
                    ModelResources.find(type.model());
                    return;
                }
                float scale = (type.waist() ? POUCH_SCALE : BASKET_SCALE) / MODEL_PIXELS_PER_BLOCK;
                matrices.translate(0, asset.groundOffset() * scale, 0);
                matrices.scale(scale, scale, scale);
                asset.render(matrices, buffers, 0, null, light, overlay);
            }
        } finally {
            matrices.popPose();
        }
    }
}
