package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kizuna.backpack3d.protocol.PlayerSlots;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.FastColor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Inventory;

/** 从真实玩家骨架捕获双手与盔甲部位，绘制轮廓和鼠标命中共用同一体积。 */
final class PlayerSlotTargets {
    private static final float MODEL_UNITS_PER_BLOCK = 16;
    private static final float COLOR_CHANNEL_MAX = 255f;
    private static final float PENDING_OPACITY = 0.45f;
    // 以下边界以皮肤像素为单位；盔甲外扩一像素，裤腿与靴子在腿部八像素处分界。
    private static final Bounds HEAD = bounds(-5, -9, -5, 5, 1, 5);
    private static final Bounds CHEST = bounds(-5, 0, -3, 5, 12, 3);
    private static final Bounds SLEEVE = bounds(-3, -2, -3, 3, 7.5f, 3);
    private static final Bounds HAND = bounds(-3, 7.5f, -3, 3, 12.5f, 3);
    private static final Bounds LEGGINGS = bounds(-3, 0, -3, 3, 8, 3);
    private static final Bounds BOOTS = bounds(-3, 8, -3, 3, 13, 3);
    private final List<Target> targets = new ArrayList<>();

    void capture(
            PoseStack matrices,
            PlayerModel<AbstractClientPlayer> model,
            AbstractClientPlayer player) {
        // 每帧从模型姿态重建，不保存随人物旋转失效的屏幕矩形。
        targets.clear();
        addPart(matrices, model.head, EquipmentSlot.HEAD, HEAD);
        addPart(matrices, model.body, EquipmentSlot.CHEST, CHEST);
        for (ModelPart leg : List.of(model.leftLeg, model.rightLeg)) {
            addPart(matrices, leg, EquipmentSlot.LEGS, LEGGINGS);
            addPart(matrices, leg, EquipmentSlot.FEET, BOOTS);
        }
        for (HumanoidArm arm : HumanoidArm.values()) {
            PoseStack transform = ModelAsset.copy(matrices);
            model.translateToHand(arm, transform);
            targets.add(new Target(PlayerSlots.armorSlot(EquipmentSlot.CHEST), transform, SLEEVE));
            int slot =
                    arm == player.getMainArm()
                            ? player.getInventory().selected
                            : Inventory.SLOT_OFFHAND;
            targets.add(new Target(slot, transform, HAND));
        }
    }

    private void addPart(PoseStack matrices, ModelPart part, EquipmentSlot slot, Bounds bounds) {
        // 各部位分别乘上局部骨架变换，腿、头和手臂的姿态不能套用躯干矩阵。
        PoseStack transform = ModelAsset.copy(matrices);
        part.translateAndRotate(transform);
        targets.add(new Target(PlayerSlots.armorSlot(slot), transform, bounds));
    }

    double depth(double x, double y) {
        double nearest = Double.NEGATIVE_INFINITY;
        for (Target target : targets) {
            nearest = Math.max(nearest, target.depth(x, y));
        }
        return nearest;
    }

    int slotAt(double x, double y, double occluderDepth) {
        // 容器和其他身体部位都会遮挡；只返回光标下最靠前的一个槽位。
        int slot = -1;
        double nearest = occluderDepth;
        for (Target target : targets) {
            double depth = target.depth(x, y);
            if (depth > nearest) {
                nearest = depth;
                slot = target.slot();
            }
        }
        return slot;
    }

    float[] bounds(int slot) {
        // 护腿和靴子各有两块命中体积，对外返回它们的投影包围范围。
        float[] result = {
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY
        };
        boolean found = false;
        for (Target target : targets) {
            if (target.slot() != slot) {
                continue;
            }
            found = true;
            for (Vector3f point :
                    ModelAsset.vertices(target.bounds().from(), target.bounds().to())) {
                target.transform().last().pose().transformPosition(point);
                result[0] = Math.min(result[0], point.x);
                result[1] = Math.min(result[1], point.y);
                result[2] = Math.max(result[2], point.x);
                result[3] = Math.max(result[3], point.y);
            }
        }
        return found ? result : null;
    }

    void renderHover(int slot, MultiBufferSource buffers) {
        // 一个装备槽可能覆盖多块骨架，同时显示轮廓，页面无需增加二维装备栏。
        int color = InventoryUiClient.runtime().color(ThemeTokens.ACCENT);
        for (Target target : targets) {
            if (target.slot() != slot) {
                continue;
            }
            Vector3f from = target.bounds().from();
            Vector3f to = target.bounds().to();
            LevelRenderer.renderLineBox(
                    target.transform(),
                    buffers.getBuffer(RenderType.lines()),
                    from.x,
                    from.y,
                    from.z,
                    to.x,
                    to.y,
                    to.z,
                    FastColor.ARGB32.red(color) / COLOR_CHANNEL_MAX,
                    FastColor.ARGB32.green(color) / COLOR_CHANNEL_MAX,
                    FastColor.ARGB32.blue(color) / COLOR_CHANNEL_MAX,
                    1);
        }
    }

    void renderPending(VolumeModule module, MultiBufferSource buffers) {
        // 手持和装备的来源保留半透明占位，拒绝回执后恢复真实模型。
        int color = InventoryUiClient.runtime().color(ThemeTokens.ACCENT);
        for (Target target : targets) {
            if (!module.pendingItem(VolumeModule.playerSlotPreviewId(target.slot()))) {
                continue;
            }
            Vector3f from = target.bounds().from();
            Vector3f to = target.bounds().to();
            LevelRenderer.addChainedFilledBoxVertices(target.transform(), buffers.getBuffer(RenderType.debugFilledBox()),
                    from.x, from.y, from.z, to.x, to.y, to.z,
                    FastColor.ARGB32.red(color) / COLOR_CHANNEL_MAX,
                    FastColor.ARGB32.green(color) / COLOR_CHANNEL_MAX,
                    FastColor.ARGB32.blue(color) / COLOR_CHANNEL_MAX, PENDING_OPACITY);
        }
    }

    private static Bounds bounds(float x1, float y1, float z1, float x2, float y2, float z2) {
        return new Bounds(
                new Vector3f(x1, y1, z1).div(MODEL_UNITS_PER_BLOCK),
                new Vector3f(x2, y2, z2).div(MODEL_UNITS_PER_BLOCK));
    }

    private record Bounds(Vector3f from, Vector3f to) {}

    private record Target(int slot, PoseStack transform, Bounds bounds) {
        double depth(double x, double y) {
            return ModelAsset.boxDepth(transform, bounds.from(), bounds.to(), x, y);
        }
    }
}
