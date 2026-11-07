package dev.kizuna.backpack3d.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class ModelAssetGeometryTest {
    private static final Identifier TEXTURE = Identifier.parse("test:geometry");
    private static final int FACE_COUNT = 6;
    private static final int VERTICES_PER_FACE = 4;
    private static final float EPSILON = 0.00001f;
    private static final float PIXEL_SCALE = 1 / 16f;
    private static final float ROTATION_RADIANS = 0.7f;
    private static final float DEPTH_SCALE = 0.3f;
    private static final float GUI_SCALE = 2;

    @Test
    void copiedAndGuiScaledPosesPreserveNormalNormalization() {
        var source = new PoseStack();
        source.mulPose(new Matrix4f().scaling(PIXEL_SCALE, -PIXEL_SCALE, DEPTH_SCALE));
        var normal = new Vector3f(1, 1, 1).normalize();
        var expected = source.last().transformNormal(normal, new Vector3f());
        for (var copy : List.of(ModelAsset.copy(source), ModelAsset.scaled(source, GUI_SCALE))) {
            var actual = copy.last().transformNormal(normal, new Vector3f());
            assertEquals(1, actual.length(), EPSILON);
            assertTrue(expected.equals(actual, EPSILON));
        }
    }

    @Test
    void reflectedPoseKeepsFaceWindingAndTransformedNormalAligned() {
        for (int rotation = 0; rotation < VERTICES_PER_FACE; rotation++) {
            var asset = new ModelAsset(cubeModel(rotation));
            var baseline = vertices(asset, new Matrix4f());
            for (var transform : List.of(new Matrix4f(), new Matrix4f().scaling(1, -1, -1),
                    new Matrix4f().scaling(PIXEL_SCALE, -PIXEL_SCALE, PIXEL_SCALE),
                    new Matrix4f().rotateY(ROTATION_RADIANS).scale(1, -1, DEPTH_SCALE))) {
                var actual = vertices(asset, transform);
                assertEquals(FACE_COUNT * VERTICES_PER_FACE, actual.positions.size());
                verifyFaces(actual);
                verifyUvs(baseline, actual, new Matrix4f(transform).invert());
            }
        }
    }

    private static RecordingConsumer vertices(ModelAsset asset, Matrix4f transform) {
        var collector = new GeometryCollector();
        var matrices = new PoseStack();
        matrices.mulPose(transform);
        asset.render(matrices, collector, 0, null);
        var vertices = new RecordingConsumer();
        collector.geometry().render(collector.pose(), vertices);
        return vertices;
    }

    private static void verifyFaces(RecordingConsumer vertices) {
        var center = center(vertices.positions);
        for (int face = 0; face < FACE_COUNT; face++) {
            int start = face * VERTICES_PER_FACE;
            Vector3f first = vertices.positions.get(start);
            Vector3f second = vertices.positions.get(start + 1);
            Vector3f third = vertices.positions.get(start + 2);
            Vector3f winding = new Vector3f(second).sub(first).cross(new Vector3f(third).sub(first)).normalize();
            for (int vertex = start; vertex < start + VERTICES_PER_FACE; vertex++) {
                Vector3f normal = vertices.normals.get(vertex);
                assertEquals(1, normal.length(), EPSILON, "提交法线必须归一化");
                assertEquals(1, winding.dot(normal), EPSILON, "六个面的绕序必须与提交法线同向");
                assertTrue(new Vector3f(first).sub(center).dot(normal) > 0, "提交法线必须朝模型外侧");
            }
        }
    }

    private static Vector3f center(List<Vector3f> positions) {
        var sum = new Vector3f();
        for (var position : positions) {
            sum.add(position);
        }
        return sum.div(positions.size());
    }

    private static void verifyUvs(RecordingConsumer baseline, RecordingConsumer actual, Matrix4f inverse) {
        for (int index = 0; index < actual.positions.size(); index++) {
            var original = inverse.transformPosition(actual.positions.get(index), new Vector3f());
            int faceStart = index / VERTICES_PER_FACE * VERTICES_PER_FACE;
            int matched = -1;
            for (int vertex = faceStart; vertex < faceStart + VERTICES_PER_FACE; vertex++) {
                if (original.equals(baseline.positions.get(vertex), EPSILON)) {
                    matched = vertex;
                    break;
                }
            }
            assertTrue(matched >= 0, "反射不能改变面顶点");
            assertEquals(baseline.uvs.get(matched), actual.uvs.get(index), "绕序变化不能翻转贴图");
        }
    }

    private static BbModel cubeModel(int rotation) {
        var face = new BbModel.Face(TEXTURE, 0, 0, 1, 1, rotation);
        var bone = new BbModel.Bone("cube", "cube", new Vector3f(), new Vector3f());
        return new BbModel(
                List.of(new BbModel.Cube(
                        new Vector3f(0, 0, 0),
                        new Vector3f(1, 1, 1),
                        new Vector3f(),
                        new Vector3f(),
                        List.of(bone),
                        List.of(face, face, face, face, face, face))),
                java.util.Map.of(),
                null,
                null);
    }

    private static final class GeometryCollector extends SubmitNodeStorage {
        private PoseStack.Pose pose;
        private SubmitNodeCollector.CustomGeometryRenderer geometry;

        @Override
        public void submitCustomGeometry(
                PoseStack matrices, RenderType type, SubmitNodeCollector.CustomGeometryRenderer geometry) {
            pose = matrices.last().copy();
            this.geometry = geometry;
        }

        PoseStack.Pose pose() {
            return pose;
        }

        SubmitNodeCollector.CustomGeometryRenderer geometry() {
            return geometry;
        }
    }

    private static final class RecordingConsumer implements VertexConsumer {
        private final List<Vector3f> positions = new ArrayList<>();
        private final List<Vector3f> normals = new ArrayList<>();
        private final List<Vector2f> uvs = new ArrayList<>();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            positions.add(new Vector3f(x, y, z));
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            uvs.add(new Vector2f(u, v));
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            normals.add(new Vector3f(x, y, z));
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

    }
}
