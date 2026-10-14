package dev.kizuna.backpack3d.client;

import dev.kizuna.backpack3d.protocol.PlayerSlots;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.util.Arm;
import net.minecraft.util.math.ColorHelper;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** 从真实玩家骨架捕获双手与盔甲部位，绘制轮廓和鼠标命中共用同一体积。 */
final class PlayerSlotTargets {
    private static final float MODEL_UNITS_PER_BLOCK = 16;
    private static final float COLOR_CHANNEL_MAX = 255f;
    // 以下边界以皮肤像素为单位；盔甲外扩一像素，裤腿与靴子在腿部八像素处分界。
    private static final Bounds HEAD = bounds(-5, -9, -5, 5, 1, 5);
    private static final Bounds CHEST = bounds(-5, 0, -3, 5, 12, 3);
    private static final Bounds SLEEVE = bounds(-3, -2, -3, 3, 7.5f, 3);
    private static final Bounds HAND = bounds(-3, 7.5f, -3, 3, 12.5f, 3);
    private static final Bounds LEGGINGS = bounds(-3, 0, -3, 3, 8, 3);
    private static final Bounds BOOTS = bounds(-3, 8, -3, 3, 13, 3);
    private final List<Target> targets = new ArrayList<>();

    void capture(
            MatrixStack matrices,
            PlayerEntityModel<AbstractClientPlayerEntity> model,
            AbstractClientPlayerEntity player) {
        // 每帧从模型姿态重建，不保存随人物旋转失效的屏幕矩形。
        targets.clear();
        addPart(matrices, model.head, EquipmentSlot.HEAD, HEAD);
        addPart(matrices, model.body, EquipmentSlot.CHEST, CHEST);
        for (ModelPart leg : List.of(model.leftLeg, model.rightLeg)) {
            addPart(matrices, leg, EquipmentSlot.LEGS, LEGGINGS);
            addPart(matrices, leg, EquipmentSlot.FEET, BOOTS);
        }
        for (Arm arm : Arm.values()) {
            MatrixStack transform = ModelAsset.copy(matrices);
            model.setArmAngle(arm, transform);
            targets.add(new Target(PlayerSlots.armorSlot(EquipmentSlot.CHEST), transform, SLEEVE));
            int slot =
                    arm == player.getMainArm()
                            ? player.getInventory().selectedSlot
                            : PlayerInventory.OFF_HAND_SLOT;
            targets.add(new Target(slot, transform, HAND));
        }
    }

    private void addPart(MatrixStack matrices, ModelPart part, EquipmentSlot slot, Bounds bounds) {
        // 各部位分别乘上局部骨架变换，腿、头和手臂的姿态不能套用躯干矩阵。
        MatrixStack transform = ModelAsset.copy(matrices);
        part.rotate(transform);
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
                target.transform().peek().getPositionMatrix().transformPosition(point);
                result[0] = Math.min(result[0], point.x);
                result[1] = Math.min(result[1], point.y);
                result[2] = Math.max(result[2], point.x);
                result[3] = Math.max(result[3], point.y);
            }
        }
        return found ? result : null;
    }

    void renderHover(int slot, VertexConsumerProvider buffers) {
        // 一个装备槽可能覆盖多块骨架，同时显示轮廓，页面无需增加二维装备栏。
        int color = InventoryUiClient.runtime().color(ThemeTokens.ACCENT);
        for (Target target : targets) {
            if (target.slot() != slot) {
                continue;
            }
            Vector3f from = target.bounds().from();
            Vector3f to = target.bounds().to();
            WorldRenderer.drawBox(
                    target.transform(),
                    buffers.getBuffer(RenderLayer.getLines()),
                    from.x,
                    from.y,
                    from.z,
                    to.x,
                    to.y,
                    to.z,
                    ColorHelper.Argb.getRed(color) / COLOR_CHANNEL_MAX,
                    ColorHelper.Argb.getGreen(color) / COLOR_CHANNEL_MAX,
                    ColorHelper.Argb.getBlue(color) / COLOR_CHANNEL_MAX,
                    1);
        }
    }

    private static Bounds bounds(float x1, float y1, float z1, float x2, float y2, float z2) {
        return new Bounds(
                new Vector3f(x1, y1, z1).div(MODEL_UNITS_PER_BLOCK),
                new Vector3f(x2, y2, z2).div(MODEL_UNITS_PER_BLOCK));
    }

    private record Bounds(Vector3f from, Vector3f to) {}

    private record Target(int slot, MatrixStack transform, Bounds bounds) {
        double depth(double x, double y) {
            return ModelAsset.boxDepth(transform, bounds.from(), bounds.to(), x, y);
        }
    }
}
