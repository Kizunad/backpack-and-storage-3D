package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kizuna.backpack3d.item.ContainerItems;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;

/** 绘制权威外观缓存中的已装备容器，不使用本地库存推测其他玩家的装备。 */
final class WorldEquipmentRenderer {
    private final EquipmentAppearances appearances;
    private final Map<ResourceLocation, ContainerItems.Definition> types = new HashMap<>();

    WorldEquipmentRenderer(EquipmentAppearances appearances) {
        this.appearances = appearances;
        for (var type : ContainerItems.types().values()) {
            types.put(type.id(), type);
        }
    }

    void render(
            AbstractClientPlayer player,
            PoseStack body,
            MultiBufferSource buffers,
            int light) {
        // 隐身及旁观者不显示悬空背包；正常玩家继承原版的光照、受伤叠色和躯干动作。
        if (player.isInvisible() || player.isSpectator()) {
            return;
        }
        String dimension = player.level().dimension().location().toString();
        for (var worn : appearances.models(player.getUUID(), player.getId(), dimension)) {
            var type = types.get(ResourceLocation.tryParse(worn.getItemType()));
            if (type == null || type.mounts().isEmpty()) {
                continue;
            }
            var asset = ModelResources.find(type.model());
            if (asset == null) {
                continue;
            }
            body.pushPose();
            try {
                WornModelTransform.apply(
                        body, asset, type.waist(), worn.getMount(), worn.getWaistAngleDegrees());
                asset.render(
                        body, buffers, 0, null, light, LivingEntityRenderer.getOverlayCoords(player, 0));
            } finally {
                body.popPose();
            }
        }
    }
}
