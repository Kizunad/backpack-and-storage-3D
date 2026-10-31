package dev.kizuna.backpack3d.client;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.ARGB;

/** 将四边形裁到可见格层；旋转相机后仍使用容器的 Y 轴，而非屏幕的上下方向。 */
final class SliceVertexConsumer implements VertexConsumer {
    private static final int QUAD_VERTICES = 4;
    private static final float COLOR_CHANNEL_MAX = 255f;
    private final VertexConsumer output;
    private final Matrix4f sceneInverse;
    private final float lower;
    private final float upper;
    private final List<Vertex> quad = new ArrayList<>(QUAD_VERTICES);
    private final Vector3f position = new Vector3f();
    // 颜色通道按 0–255 保存，插值后再取整写出。
    private final Vector4f color = new Vector4f(COLOR_CHANNEL_MAX);
    private final Vector2f texture = new Vector2f();
    private final Vector3f normal = new Vector3f();
    private int overlayU;
    private int overlayV;
    private int lightU;
    private int lightV;
    private boolean pending;

    SliceVertexConsumer(VertexConsumer output, Matrix4f sceneInverse, float lower, float upper) {
        this.output = output;
        this.sceneInverse = sceneInverse;
        this.lower = lower;
        this.upper = upper;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        // 26.2 没有 endVertex：开始下一个顶点时，上一个顶点的属性才算写齐。
        commit();
        position.set(x, y, z);
        pending = true;
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        color.set(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer setColor(int argb) {
        return setColor(ARGB.red(argb), ARGB.green(argb), ARGB.blue(argb), ARGB.alpha(argb));
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        texture.set(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        overlayU = u;
        overlayV = v;
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        lightU = u;
        lightV = v;
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        normal.set(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        throw new UnsupportedOperationException("切层只处理四边形，线段不应经过这里");
    }

    /** 一次几何提交写完后调用，补交最后一个顶点；残缺的四边形说明调用方写错了图元。 */
    void finish() {
        commit();
        if (!quad.isEmpty()) {
            throw new IllegalStateException("切层收到不完整的四边形：" + quad.size() + " 个顶点");
        }
    }

    private void commit() {
        if (!pending) {
            return;
        }
        pending = false;
        // 提交时顶点已变换到画中画坐标；逆变换只用于求它所在的容器高度。
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
            consumer.addVertex(position.x, position.y, position.z)
                    .setColor(
                            Math.round(color.x),
                            Math.round(color.y),
                            Math.round(color.z),
                            Math.round(color.w))
                    .setUv(texture.x, texture.y)
                    .setUv1(overlayU, overlayV)
                    .setUv2(lightU, lightV)
                    .setNormal(normal.x, normal.y, normal.z);
        }

        private static int mix(int from, int to, float fraction) {
            return Math.round(from + (to - from) * fraction);
        }
    }
}
