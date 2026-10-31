package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/** 绘制和命中使用相同投影，避免鼠标热区与旋转后的模型分离。 */
public record SceneTransform(
        // 投影中心在 GUI 中的逻辑像素坐标。
        float x,
        float y,
        // 每个容器格占用的逻辑像素数。
        float scale,
        // 水平与垂直旋转角，单位为度。
        float yaw,
        float pitch) {
    public static final float DEPTH = 100;
    private static final float DEPTH_SCALE = 0.1f;

    public PoseStack matrices() {
        // GUI 的 y 轴朝下；压缩深度以保留前后遮挡，并让绘制和命中使用同一矩阵。
        var matrices = new PoseStack();
        matrices.translate(x, y, DEPTH);
        matrices.scale(scale, -scale, scale * DEPTH_SCALE);
        matrices.mulPose(Axis.XP.rotationDegrees(pitch));
        matrices.mulPose(Axis.YP.rotationDegrees(yaw));
        return matrices;
    }

    public Matrix3f normal() {
        // 法线只取场景旋转与 GUI 的 y 翻转；深度压缩若进入法线，各面会几乎都朝 ±Z，物品亮度趋同发灰。
        return new Matrix3f()
                .scaling(1, -1, 1)
                .rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw));
    }

    public Vector3f project(float px, float py, float pz) {
        return matrices().last().pose().transformPosition(new Vector3f(px, py, pz));
    }
}
