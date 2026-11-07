package dev.kizuna.backpack3d.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kizuna.backpack3d.registry.pb.ItemRegistryProto.ModelRenderMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class ModelSegmentsTest {
    private static final Identifier TEXTURE = Identifier.parse("test:segments");
    private static final float SECOND_SEGMENT_X = 10;
    private static final float PROBE_CENTER = 0.5f;

    @Test
    void rawSegmentsPreserveCoordinatesTextureAndMatchingHitBounds() {
        // 两段相距十像素，分别选段后不能居中、缩放或命中另一段。
        BbModel model = model(List.of(cube("left", "left-id", 0), cube("right", "right-id", SECOND_SEGMENT_X)));
        ModelAsset raw = new ModelAsset(model, ModelRenderMode.MODEL_RENDER_MODE_RAW_SEGMENTS);
        assertEquals(Set.of("left", "right"), raw.segments());
        ModelAsset left = raw.segment("left");
        ModelAsset right = raw.segment("right");
        assertSame(left, raw.segment("left"));
        assertArrayEquals(new float[] {0, 0, 1, 1}, left.bounds(new PoseStack(), BagAnimation.Sample.CLOSED));
        assertArrayEquals(new float[] {SECOND_SEGMENT_X, 0, SECOND_SEGMENT_X + 1, 1},
                right.bounds(new PoseStack(), BagAnimation.Sample.CLOSED));
        assertTrue(Double.isFinite(left.hitDepth(new PoseStack(), BagAnimation.Sample.CLOSED,
                PROBE_CENTER, PROBE_CENTER)));
        assertEquals(Double.NEGATIVE_INFINITY, right.hitDepth(new PoseStack(), BagAnimation.Sample.CLOSED,
                PROBE_CENTER, PROBE_CENTER));
        assertThrows(IllegalArgumentException.class, () -> raw.segment("missing"));
        assertThrows(IllegalArgumentException.class, () -> new ModelAsset(model).segment("left"));
    }

    @Test
    void rawModeRejectsAmbiguousNamesAndUnboundGeometry() {
        BbModel duplicate = model(List.of(cube("same", "first-id", 0), cube("same", "second-id", 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelAsset(duplicate, ModelRenderMode.MODEL_RENDER_MODE_RAW_SEGMENTS));
        BbModel unnamed = model(List.of(cube("", "first-id", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelAsset(unnamed, ModelRenderMode.MODEL_RENDER_MODE_RAW_SEGMENTS));
    }

    private static BbModel model(List<BbModel.Cube> cubes) {
        return new BbModel(cubes, Map.of(), null, null);
    }

    private static BbModel.Cube cube(String name, String id, float x) {
        var bone = new BbModel.Bone(id, name, new Vector3f(), new Vector3f());
        var face = new BbModel.Face(TEXTURE, 0, 0, 1, 1, 0);
        return new BbModel.Cube(new Vector3f(x, 0, 0), new Vector3f(x + 1, 1, 1),
                new Vector3f(), new Vector3f(), List.of(bone), List.of(face, face, face, face, face, face));
    }
}
