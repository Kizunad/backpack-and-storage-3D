package dev.kizuna.backpack3d.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.ARGB;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class SliceVertexConsumerTest {
    @Test
    void rotatedSceneClipsAtContainerHeightAndInterpolatesTexture() {
        var scene =
                new Matrix4f()
                        .translate(40, 80, 100)
                        .scale(20, -20, 2)
                        .rotateX(0.5f)
                        .rotateY(-0.6f);
        var inverse = new Matrix4f(scene).invert();
        var output = new RecordingConsumer();
        var clipper = new SliceVertexConsumer(output, inverse, 1, 2);
        quad(clipper, scene, 0, 3);
        clipper.finish();
        assertEquals(4, output.vertices.size());
        for (var vertex : output.vertices) {
            var local = inverse.transformPosition(new Vector3f(vertex.position()));
            assertTrue(local.y >= 0.999f && local.y <= 2.001f);
            assertEquals(local.y / 3, vertex.textureV(), 0.001f);
            assertEquals(240, vertex.light());
            assertEquals(0.5f, vertex.alpha(), 0.01f);
        }
    }

    @Test
    void hiddenQuadsDoNotLeakIntoTheNextVisibleQuad() {
        var output = new RecordingConsumer();
        var clipper = new SliceVertexConsumer(output, new Matrix4f(), 1, 2);
        // 同一次几何提交里先写两个整块隐藏的四边形，它们的顶点不能残留到后续四边形。
        quad(clipper, new Matrix4f(), -2, -1);
        quad(clipper, new Matrix4f(), 3, 4);
        clipper.finish();
        assertTrue(output.vertices.isEmpty());
        quad(clipper, new Matrix4f(), 1, 2);
        clipper.finish();
        assertEquals(4, output.vertices.size());
    }

    @Test
    void clippedPolygonRemainsAValidQuadBatch() {
        var output = new RecordingConsumer();
        var clipper = new SliceVertexConsumer(output, new Matrix4f(), 0, 2);
        for (var point :
                List.of(
                        new Vector3f(-1, 1, 0),
                        new Vector3f(1, 3, 0),
                        new Vector3f(3, 1, 0),
                        new Vector3f(1, -1, 0))) {
            clipper.addVertex(point.x, point.y, point.z).setUv(0, 0);
        }
        clipper.finish();
        assertEquals(16, output.vertices.size());
        assertTrue(
                output.vertices.stream()
                        .allMatch(vertex -> vertex.position().y >= 0 && vertex.position().y <= 2));
    }

    private static void quad(VertexConsumer consumer, Matrix4f scene, float bottom, float top) {
        // 使用跨越两个裁剪面的真实四边形，验证边交点的属性，而非复述裁剪实现。
        for (var point :
                List.of(
                        new Vector3f(0, bottom, 0),
                        new Vector3f(1, bottom, 0),
                        new Vector3f(1, top, 0),
                        new Vector3f(0, top, 0))) {
            var transformed = scene.transformPosition(new Vector3f(point));
            consumer.addVertex(transformed.x, transformed.y, transformed.z)
                    .setColor(255, 255, 255, 128)
                    .setUv(point.x, point.y / 3)
                    .setUv1(0, 0)
                    .setUv2(240, 240)
                    .setNormal(0, 0, 1);
        }
    }

    /** 26.2 顶点属性在 addVertex 之后逐项写入，记录器按可变顶点保存，读取时属性已写齐。 */
    private static final class Vertex {
        final Vector3f position;
        float textureV;
        int light;
        float alpha;

        Vertex(Vector3f position) {
            this.position = position;
        }

        Vector3f position() {
            return position;
        }

        float textureV() {
            return textureV;
        }

        int light() {
            return light;
        }

        float alpha() {
            return alpha;
        }
    }

    private static class RecordingConsumer implements VertexConsumer {
        final List<Vertex> vertices = new ArrayList<>();

        private Vertex last() {
            return vertices.get(vertices.size() - 1);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertices.add(new Vertex(new Vector3f(x, y, z)));
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            last().alpha = a / 255f;
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            last().alpha = ARGB.alpha(argb) / 255f;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            last().textureV = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            last().light = u;
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
