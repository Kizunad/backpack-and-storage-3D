package dev.kizuna.backpack3d.client;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;

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

    public MatrixStack matrices() {
        // GUI 的 y 轴朝下；压缩深度以保留前后遮挡，并让绘制和命中使用同一矩阵。
        var matrices = new MatrixStack();
        matrices.translate(x, y, DEPTH);
        matrices.scale(scale, -scale, scale * DEPTH_SCALE);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
        return matrices;
    }

    public Vector3f project(float px, float py, float pz) {
        return matrices().peek().getPositionMatrix().transformPosition(new Vector3f(px, py, pz));
    }
}
