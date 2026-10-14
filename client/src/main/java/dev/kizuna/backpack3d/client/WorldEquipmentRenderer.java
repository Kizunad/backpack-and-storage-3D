package dev.kizuna.backpack3d.client;

import dev.kizuna.backpack3d.item.ContainerItems;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/** 绘制权威外观缓存中的已装备容器，不使用本地库存推测其他玩家的装备。 */
final class WorldEquipmentRenderer {
    private final EquipmentAppearances appearances;
    private final Map<Identifier, ContainerItems.Definition> types = new HashMap<>();

    WorldEquipmentRenderer(EquipmentAppearances appearances) {
        this.appearances = appearances;
        for (var type : ContainerItems.types().values()) {
            types.put(type.id(), type);
        }
    }

    void render(
            AbstractClientPlayerEntity player,
            MatrixStack body,
            VertexConsumerProvider buffers,
            int light) {
        // 隐身及旁观者不显示悬空背包；正常玩家继承原版的光照、受伤叠色和躯干动作。
        if (player.isInvisible() || player.isSpectator()) {
            return;
        }
        String dimension = player.getWorld().getRegistryKey().getValue().toString();
        for (var worn : appearances.models(player.getUuid(), player.getId(), dimension)) {
            var type = types.get(Identifier.tryParse(worn.getItemType()));
            if (type == null || type.mounts().isEmpty()) {
                continue;
            }
            var asset = ModelResources.find(type.model());
            if (asset == null) {
                continue;
            }
            body.push();
            try {
                WornModelTransform.apply(
                        body, asset, type.waist(), worn.getMount(), worn.getWaistAngleDegrees());
                asset.render(
                        body, buffers, 0, null, light, LivingEntityRenderer.getOverlay(player, 0));
            } finally {
                body.pop();
            }
        }
    }
}
