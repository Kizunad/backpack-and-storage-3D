package dev.kizuna.backpack3d.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.TextFormat;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.registry.pb.ItemRegistryProto.ItemRegistry;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 用真实导入资产覆盖归档读取、贴图、人体尺度与静态开包，而不是简化几何夹具。 */
class BongGearModelsTest {
    private static final Path SOURCE = Path.of("../server/src/main/resources/bs3d/bong-gear");
    @TempDir Path directory;

    @Test
    void allNineModelsLoadFromJarAndStayWithinWearableBounds() throws Exception {
        var registry = ItemRegistry.newBuilder();
        TextFormat.getParser().merge(Files.readString(SOURCE.resolve("items.txtpb")), registry);
        assertEquals(9, registry.getItemsCount());
        try (var zip = FileSystems.newFileSystem(directory.resolve("gear.jar"), Map.of("create", "true"))) {
            Path root = zip.getPath("/bs3d/bong-gear");
            for (var item : registry.getItemsList()) {
                var type = item.getContainer();
                Path file = root.resolve(type.getAsset().getFile());
                Files.createDirectories(file.getParent());
                Files.copy(SOURCE.resolve(type.getAsset().getFile()), file);
                BbModel model = BbModel.read(root,
                        new ModelConfig.Entry(new Identifier(type.getModel()), file, null, null, Map.of()));
                assertTrue(model.cubes().size() > 100, item.getId());
                assertEquals(1, model.textures().size(), item.getId());
                assertNull(model.open());
                assertNull(model.close());
                var asset = new ModelAsset(model);
                var animation = new BagAnimation();
                animation.configure(asset);
                animation.open();
                animation.advance(0);
                assertTrue(animation.ready(), item.getId());
                assertTrue(Float.isFinite(asset.groundOffset()));

                boolean waist = type.getMountsList().contains(Mount.WAIST_RIGHT_FRONT);
                for (float angle : new float[] {0, 90, 180, 270}) {
                    var worn = new MatrixStack();
                    WornModelTransform.apply(worn, asset, waist, type.getMounts(0), angle);
                    float[] bounds = asset.bounds(worn, BagAnimation.Sample.CLOSED);
                    // 躯干高 0.75 格。未转换的 Bong 人体原点会把装备移到身体下方或远离腰侧。
                    assertTrue(bounds[0] > -1.1f && bounds[2] < 1.1f, item.getId());
                    assertTrue(bounds[1] > -0.65f && bounds[3] < 1.6f, item.getId());
                    assertTrue(bounds[2] - bounds[0] > 0.1f, item.getId());
                    assertTrue(bounds[3] - bounds[1] > 0.1f, item.getId());
                }
                if (!waist) {
                    var side = new MatrixStack();
                    side.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(90));
                    WornModelTransform.apply(side, asset, false, type.getMounts(0), 0);
                    float[] bounds = asset.bounds(side, BagAnimation.Sample.CLOSED);
                    if (type.getMounts(0) == Mount.TORSO_BACK) {
                        assertTrue(bounds[2] > 0.3f, item.getId());
                        assertTrue(bounds[0] > -0.3f, item.getId());
                    } else {
                        assertTrue(bounds[0] < -0.2f, item.getId());
                        assertTrue(bounds[2] < 0.3f, item.getId());
                    }
                }
            }
        }
    }
}
