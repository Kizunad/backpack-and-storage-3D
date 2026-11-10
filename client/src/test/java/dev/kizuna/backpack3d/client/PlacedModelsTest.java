package dev.kizuna.backpack3d.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kizuna.backpack3d.registry.pb.ItemRegistryProto.ModelRenderMode;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

class PlacedModelsTest {
    private static final float EPSILON = 1.0e-5f;
    private static final float HALF_BLOCK_PIXELS = 8;
    private static final float BLOCK_CENTER = 0.5f;
    private static final float MODEL_PIXELS_PER_BLOCK = 16;

    @Test
    void registeredModelsLoadAsRawSegmentsWithoutAnimationsOrSounds() {
        // 放置模型沿用导入格式，按原坐标加载；空字符串表示不自动找开合动画。
        Identifier id = Identifier.parse("placed_test:brazier");
        Path file = Path.of("models/brazier.bbmodel");
        PlacedModels.add(new PlacedModels.Registration(id, file, new Vector3f()));
        var entry = PlacedModels.entries().stream().filter(candidate -> candidate.id().equals(id)).findFirst()
                .orElseThrow();
        assertEquals(file, entry.file());
        assertEquals("", entry.openAnimation());
        assertEquals("", entry.closeAnimation());
        assertEquals(Map.of(), entry.sounds());
        assertEquals(ModelRenderMode.MODEL_RENDER_MODE_RAW_SEGMENTS, entry.mode());
    }

    @Test
    void sameIdCannotBeRegisteredTwice() {
        Identifier id = Identifier.parse("placed_test:crate");
        PlacedModels.add(new PlacedModels.Registration(id, Path.of("a.bbmodel"), new Vector3f()));
        assertThrows(IllegalArgumentException.class,
                () -> PlacedModels.add(new PlacedModels.Registration(id, Path.of("b.bbmodel"), new Vector3f())));
    }

    @Test
    void originLandsOnBlockBottomCenterAndFrontTurnsToFacing() {
        // 原点落在方块底面中心；模型正面（-Z）朝方块朝向。
        var centered = new PlacedModels.Placed(null, new Vector3f());
        assertPlaced(centered, Direction.NORTH, new Vector3f(), new Vector3f(BLOCK_CENTER, 0, BLOCK_CENTER));
        Vector3f front = new Vector3f(0, 0, -HALF_BLOCK_PIXELS);
        assertPlaced(centered, Direction.NORTH, front, new Vector3f(BLOCK_CENTER, 0, 0));
        assertPlaced(centered, Direction.EAST, front, new Vector3f(1, 0, BLOCK_CENTER));
        assertPlaced(centered, Direction.SOUTH, front, new Vector3f(BLOCK_CENTER, 0, 1));
        assertPlaced(centered, Direction.WEST, front, new Vector3f(0, 0, BLOCK_CENTER));
        // 以方块格为坐标画的模型把原点登记在 (8, 0, 8)，放置后与原坐标除以 16 相同。
        var blockFrame = new PlacedModels.Placed(null, new Vector3f(HALF_BLOCK_PIXELS, 0, HALF_BLOCK_PIXELS));
        Vector3f pixel = new Vector3f(2, 4, 6);
        assertPlaced(blockFrame, Direction.NORTH, pixel, new Vector3f(pixel).div(MODEL_PIXELS_PER_BLOCK));
    }

    private static void assertPlaced(PlacedModels.Placed placed, Direction facing, Vector3fc model,
            Vector3fc expected) {
        var matrices = new PoseStack();
        PlacedModels.place(matrices, placed, facing);
        Vector3f actual = matrices.last().pose().transformPosition(new Vector3f(model));
        assertEquals(expected.x(), actual.x, EPSILON, facing + " x");
        assertEquals(expected.y(), actual.y, EPSILON, facing + " y");
        assertEquals(expected.z(), actual.z, EPSILON, facing + " z");
    }
}
