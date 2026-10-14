package dev.kizuna.backpack3d.client;

import net.minecraft.client.render.VertexConsumer;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/** 将物品四边形裁到可见格层；旋转相机后仍使用容器的 Y 轴，而非屏幕的上下方向。 */
final class SliceVertexConsumer implements VertexConsumer {
    private static final int QUAD_VERTICES = 4;
    private static final float COLOR_CHANNEL_MAX = 255f;
    private final VertexConsumer output;
    private final Matrix4f sceneInverse;
    private final float lower;
    private final float upper;
    private final List<Vertex> quad = new ArrayList<>(QUAD_VERTICES);
    private final Vector3f position = new Vector3f();
    private final Vector4f color = new Vector4f(1);
    private final Vector2f texture = new Vector2f();
    private final Vector3f normal = new Vector3f();
    private int overlayU;
    private int overlayV;
    private int lightU;
    private int lightV;
    private boolean fixedColor;

    SliceVertexConsumer(VertexConsumer output, Matrix4f sceneInverse, float lower, float upper) {
        this.output = output;
        this.sceneInverse = sceneInverse;
        this.lower = lower;
        this.upper = upper;
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        position.set((float) x, (float) y, (float) z);
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        // 与原版消费者一致：固定颜色期间不能逐顶点改色。
        if (fixedColor) {
            throw new IllegalStateException("固定颜色期间不能设置顶点颜色");
        }
        color.set(red, green, blue, alpha).div(COLOR_CHANNEL_MAX);
        return this;
    }

    @Override
    public VertexConsumer texture(float u, float v) {
        texture.set(u, v);
        return this;
    }

    @Override
    public VertexConsumer overlay(int u, int v) {
        overlayU = u;
        overlayV = v;
        return this;
    }

    @Override
    public VertexConsumer light(int u, int v) {
        lightU = u;
        lightV = v;
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        normal.set(x, y, z);
        return this;
    }

    @Override
    public void fixedColor(int red, int green, int blue, int alpha) {
        color.set(red, green, blue, alpha).div(COLOR_CHANNEL_MAX);
        fixedColor = true;
    }

    @Override
    public void unfixColor() {
        fixedColor = false;
    }

    @Override
    public void next() {
        // 物品渲染器已把顶点变换到 GUI 坐标；逆变换只用于求它所在的容器高度。
        float layerY = sceneInverse.transformPosition(new Vector3f(position)).y;
        quad.add(
                new Vertex(
                        new Vector3f(position),
                        new Vector4f(color),
                        new Vector2f(texture),
                        overlayU,
                        overlayV,
                        lightU,
                        lightV,
                        new Vector3f(normal),
                        layerY));
        if (quad.size() < QUAD_VERTICES) {
            return;
        }
        List<Vertex> polygon = clip(quad, lower, true);
        polygon = clip(polygon, upper, false);
        emit(polygon);
        quad.clear();
    }

    private static List<Vertex> clip(List<Vertex> input, float boundary, boolean keepAbove) {
        // 沿多边形边界依次保留可见顶点，并在穿越切面时插入带完整属性的交点。
        if (input.isEmpty()) {
            return input;
        }
        List<Vertex> result = new ArrayList<>();
        Vertex previous = input.get(input.size() - 1);
        boolean previousInside = inside(previous, boundary, keepAbove);
        for (Vertex current : input) {
            boolean currentInside = inside(current, boundary, keepAbove);
            if (previousInside != currentInside) {
                float fraction =
                        (boundary - previous.layerY()) / (current.layerY() - previous.layerY());
                result.add(previous.interpolate(current, fraction, boundary));
            }
            if (currentInside) {
                result.add(current);
            }
            previous = current;
            previousInside = currentInside;
        }
        return result;
    }

    private static boolean inside(Vertex vertex, float boundary, boolean keepAbove) {
        return keepAbove ? vertex.layerY() >= boundary : vertex.layerY() <= boundary;
    }

    private void emit(List<Vertex> polygon) {
        // 原四边形直接提交；裁成其他凸多边形时用三角扇，重复末点适配原版 QUADS 批次。
        if (polygon.size() == QUAD_VERTICES) {
            for (Vertex vertex : polygon) {
                vertex.write(output);
            }
            return;
        }
        for (int index = 1; index + 1 < polygon.size(); index++) {
            polygon.get(0).write(output);
            polygon.get(index).write(output);
            polygon.get(index + 1).write(output);
            polygon.get(index + 1).write(output);
        }
    }

    /** 顶点属性保持原渲染空间；layerY 单独记录容器坐标，单位为格。 */
    private record Vertex(
            Vector3f position,
            Vector4f color,
            Vector2f texture,
            int overlayU,
            int overlayV,
            int lightU,
            int lightV,
            Vector3f normal,
            float layerY) {
        Vertex interpolate(Vertex other, float fraction, float boundary) {
            // 同步插值纹理、颜色和光照，切片不拉伸贴图，也不丢失附魔渲染使用的属性。
            return new Vertex(
                    new Vector3f(position).lerp(other.position, fraction),
                    new Vector4f(color).lerp(other.color, fraction),
                    new Vector2f(texture).lerp(other.texture, fraction),
                    mix(overlayU, other.overlayU, fraction),
                    mix(overlayV, other.overlayV, fraction),
                    mix(lightU, other.lightU, fraction),
                    mix(lightV, other.lightV, fraction),
                    new Vector3f(normal).lerp(other.normal, fraction),
                    boundary);
        }

        void write(VertexConsumer consumer) {
            consumer.vertex(position.x, position.y, position.z)
                    .color(color.x, color.y, color.z, color.w)
                    .texture(texture.x, texture.y)
                    .overlay(overlayU, overlayV)
                    .light(lightU, lightV)
                    .normal(normal.x, normal.y, normal.z)
                    .next();
        }

        private static int mix(int from, int to, float fraction) {
            return Math.round(from + (to - from) * fraction);
        }
    }
}
