package dev.kizuna.backpack3d.client;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.client.render.VertexConsumer;

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
        quad(clipper, new Matrix4f(), -2, -1);
        assertTrue(output.vertices.isEmpty());
        quad(clipper, new Matrix4f(), 3, 4);
        assertTrue(output.vertices.isEmpty());
        quad(clipper, new Matrix4f(), 1, 2);
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
            clipper.vertex(point.x, point.y, point.z).texture(0, 0).next();
        }
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
            consumer.vertex(transformed.x, transformed.y, transformed.z)
                    .color(255, 255, 255, 128)
                    .texture(point.x, point.y / 3)
                    .overlay(0, 0)
                    .light(240, 240)
                    .normal(0, 0, 1)
                    .next();
        }
    }

    private record Vertex(Vector3f position, float textureV, int light, float alpha) {}

    private static class RecordingConsumer implements VertexConsumer {
        final List<Vertex> vertices = new ArrayList<>();
        private Vector3f position;
        private float textureV;
        private int light;
        private float alpha;

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            position = new Vector3f((float) x, (float) y, (float) z);
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            alpha = a / 255f;
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            textureV = v;
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            light = u;
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void next() {
            vertices.add(new Vertex(position, textureV, light, alpha));
        }

        @Override
        public void fixedColor(int r, int g, int b, int a) {}

        @Override
        public void unfixColor() {}
    }
}
