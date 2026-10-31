package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ARGB;
import org.joml.Vector3f;

/** 26.2 去掉了 LevelRenderer 的线框盒与实心盒工具；内景和人物预览共用这里的顶点写法。 */
final class BoxShapes {
    // 十二条棱的端点下标，下标规则同 ModelAsset.vertices：第 0、1、2 位分别取 x、y、z 的上界。
    private static final int[][] EDGES = {
        {0, 1}, {2, 3}, {4, 5}, {6, 7},
        {0, 2}, {1, 3}, {4, 6}, {5, 7},
        {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };
    private static final int[][] FACES = {
        {0, 4, 6, 2}, {5, 1, 3, 7}, {1, 0, 2, 3},
        {4, 5, 7, 6}, {2, 6, 7, 3}, {1, 5, 4, 0}
    };

    private BoxShapes() {}

    /** 向 {@code RenderTypes.lines()} 写线框盒；与 1.20.1 的 renderLineBox 相同，线框始终不透明。 */
    static void outline(
            PoseStack.Pose pose, VertexConsumer consumer, Vector3f from, Vector3f to, int color, LineScale scale) {
        var points = ModelAsset.vertices(from, to);
        for (int[] edge : EDGES) {
            line(pose, consumer, points[edge[0]], points[edge[1]], ARGB.opaque(color), scale);
        }
    }

    /** 向 {@code RenderTypes.lines()} 写一条线段，线宽取窗口推荐值，与原版线段一致。 */
    static void line(
            PoseStack.Pose pose, VertexConsumer consumer, Vector3f start, Vector3f end, int color, LineScale scale) {
        // 线段着色器沿法线在屏幕上展开线宽，法线取线段方向。
        float width = Minecraft.getInstance().getWindow().getAppropriateLineWidth() * scale.factor(pose, start, end);
        var direction = new Vector3f(end).sub(start).normalize();
        consumer.addVertex(pose, start).setColor(color).setNormal(pose, direction).setLineWidth(width);
        consumer.addVertex(pose, end).setColor(color).setNormal(pose, direction).setLineWidth(width);
    }

    /**
     * 画中画贴图里的线宽换算：原版线段着色器按窗口像素（Globals 的 ScreenSize）展开线宽，
     * 画进比窗口小的贴图时线会按尺寸比例变细。x、y 为窗口与贴图在两个方向上的像素比。
     */
    record LineScale(float x, float y) {
        /** width、height 为画中画区域的 GUI 像素尺寸，贴图像素数为其乘以 GUI 缩放。 */
        static LineScale texture(int width, int height) {
            var window = Minecraft.getInstance().getWindow();
            float guiScale = window.getGuiScale();
            return new LineScale(window.getWidth() / (width * guiScale), window.getHeight() / (height * guiScale));
        }

        float factor(PoseStack.Pose pose, Vector3f start, Vector3f end) {
            // 画中画为正交投影，位姿输出即贴图像素。着色器在窗口像素里求方向与偏移，
            // 换回贴图后垂直于线段的宽度变为原值 × |d·k| / (kx·ky·|d|)，这里乘其倒数补回。
            var from = pose.pose().transformPosition(start, new Vector3f());
            var to = pose.pose().transformPosition(end, new Vector3f());
            float dx = to.x - from.x;
            float dy = to.y - from.y;
            float projected = (float) Math.hypot(dx * x, dy * y);
            if (projected == 0) {
                // 沿视线方向的线段投影成一点，着色器不会展开它。
                return 1;
            }
            return x * y * (float) Math.hypot(dx, dy) / projected;
        }
    }

    /** 向只含位置和颜色的四边形渲染类型（如 {@code RenderTypes.debugFilledBox()}）写实心盒。 */
    static void filled(PoseStack.Pose pose, VertexConsumer consumer, Vector3f from, Vector3f to, int color) {
        var points = ModelAsset.vertices(from, to);
        for (int[] face : FACES) {
            for (int index : face) {
                consumer.addVertex(pose, points[index]).setColor(color);
            }
        }
    }
}
