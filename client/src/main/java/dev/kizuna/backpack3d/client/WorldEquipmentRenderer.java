package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kizuna.backpack3d.item.ContainerItems;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;

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

    void render(AvatarRenderState state, PoseStack body, SubmitNodeCollector collector, int light) {
        // 隐身及旁观者不显示悬空背包；正常玩家继承原版的光照、受伤叠色和躯干动作。
        if (state.isInvisible || state.isSpectator) {
            return;
        }
        // 渲染快照只带实体 ID；外观按 UUID 缓存，同一帧内从客户端世界取回实体核对。
        var level = Minecraft.getInstance().level;
        var player = level.getEntity(state.id);
        if (player == null) {
            // 不在世界中的展示用人物（例如其他界面自建的快照）没有权威外观。
            return;
        }
        String dimension = level.dimension().identifier().toString();
        int overlay = LivingEntityRenderer.getOverlayCoords(state, 0);
        for (var worn : appearances.models(player.getUUID(), state.id, dimension)) {
            var type = types.get(Identifier.tryParse(worn.getItemType()));
            if (type == null || type.mounts().isEmpty()) {
                continue;
            }
            var asset = ModelResources.find(type.model());
            if (asset == null) {
                continue;
            }
            var matrices = ModelAsset.copy(body);
            WornModelTransform.apply(matrices, asset, type.waist(), worn.getMount(), worn.getWaistAngleDegrees());
            asset.render(matrices, collector, 0, null, light, overlay);
        }
    }
}
