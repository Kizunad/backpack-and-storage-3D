package dev.kizuna.backpack3d.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.TextFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.registry.pb.ItemRegistryProto.ItemEntry;
import dev.kizuna.backpack3d.registry.pb.ItemRegistryProto.ItemRegistry;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;

/** 用真实导入资产覆盖归档读取、贴图、人体尺度与静态开包，而不是简化几何夹具。 */
class BongGearModelsTest {
    private static final int BONG_CONTAINER_COUNT = 10;
    private static final Path SOURCE = Path.of(System.getProperty("bs3d.projectRoot"),
            "server/src/main/resources/bs3d/bong-gear");
    private static final String BELT = "bs3d:ku_teng_yao_dai";
    private static final float[] WAIST_ANGLES = {0, 90, 180, 270};
    private static final float MODEL_UNITS_PER_BLOCK = 16;
    // 原人体模型肩顶高 24 像素；躯干半宽 4 像素、半厚 2 像素，腰线在肩顶下 12 像素。
    private static final float BODY_TOP_PIXELS = 24;
    private static final float BODY_HALF_WIDTH = 4 / MODEL_UNITS_PER_BLOCK;
    private static final float BODY_HALF_DEPTH = 2 / MODEL_UNITS_PER_BLOCK;
    private static final float WAIST_LINE = 12 / MODEL_UNITS_PER_BLOCK;
    // 原模型最长的挂件垂到脚底上方约 4.7 像素，不能更低。
    private static final float LOWEST_PENDANT = (BODY_TOP_PIXELS - 4.5f) / MODEL_UNITS_PER_BLOCK;
    private static final float TOLERANCE = 1e-5f;
    @TempDir Path directory;

    @Test
    void allBundledModelsLoadFromJarAndStayWithinWearableBounds() throws Exception {
        var registry = registry();
        assertEquals(BONG_CONTAINER_COUNT, registry.getItemsCount());
        try (var zip = jar()) {
            for (var item : registry.getItemsList()) {
                var type = item.getContainer();
                var asset = load(zip, item);
                var animation = new BagAnimation();
                animation.configure(asset);
                animation.open();
                animation.advance(0);
                assertTrue(animation.ready(), item.getId());
                assertTrue(Float.isFinite(asset.groundOffset()));
                var fit = WornModelTransform.Fit.of(definition(item));
                for (float angle : WAIST_ANGLES) {
                    var worn = new PoseStack();
                    WornModelTransform.apply(worn, asset, fit, type.getMounts(0), angle);
                    float[] bounds = asset.bounds(worn, BagAnimation.Sample.CLOSED);
                    // 躯干高 0.75 格。未转换的 Bong 人体原点会把装备移到身体下方或远离腰侧。
                    assertTrue(bounds[0] > -1.1f && bounds[2] < 1.1f, item.getId());
                    assertTrue(bounds[1] > -0.65f && bounds[3] < 1.6f, item.getId());
                    assertTrue(bounds[2] - bounds[0] > 0.1f, item.getId());
                    assertTrue(bounds[3] - bounds[1] > 0.1f, item.getId());
                }
                if (fit == WornModelTransform.Fit.TORSO) {
                    var side = new PoseStack();
                    side.mulPose(Axis.YP.rotationDegrees(90));
                    WornModelTransform.apply(side, asset, fit, type.getMounts(0), 0);
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

    @Test
    void witheredVineBeltWrapsTheWaistOneToOneFacingFront() throws Exception {
        // 腰带是唯一的整圈腰带；其余腰挂仍是挂包。
        var registry = registry();
        for (var item : registry.getItemsList()) {
            var fit = WornModelTransform.Fit.of(definition(item));
            assertEquals(item.getId().equals(BELT), fit == WornModelTransform.Fit.WAIST_RING, item.getId());
        }
        var belt = registry.getItemsList().stream().filter(item -> item.getId().equals(BELT)).findFirst().orElseThrow();
        try (var zip = jar()) {
            var asset = load(zip, belt);
            List<Vector3f> centered = corners(asset, new PoseStack());
            for (float angle : WAIST_ANGLES) {
                var worn = new PoseStack();
                WornModelTransform.apply(
                        worn, asset, WornModelTransform.Fit.WAIST_RING, Mount.WAIST_RIGHT_FRONT, angle);
                List<Vector3f> body = corners(asset, worn);
                // 每个角点都落在原人体坐标 (-x, 24-y, z)/16 上：1:1、正面朝前，腰角不起作用。
                for (int index = 0; index < body.size(); index++) {
                    assertBodyPoint(asset, centered.get(index), body.get(index));
                }
            }
            float[] box = box(corners(asset, beltOnBody(asset)));
            // 绕身体一圈：左右、前后都包住躯干，腰线穿过腰带，挂件不低于原模型。
            assertTrue(box[0] < -BODY_HALF_WIDTH && box[3] > BODY_HALF_WIDTH);
            assertTrue(box[2] < -BODY_HALF_DEPTH && box[5] > BODY_HALF_DEPTH);
            assertTrue(box[1] < WAIST_LINE && box[4] > WAIST_LINE);
            assertTrue(box[4] < LOWEST_PENDANT);
        }
    }

    private static PoseStack beltOnBody(ModelAsset asset) {
        var worn = new PoseStack();
        WornModelTransform.apply(worn, asset, WornModelTransform.Fit.WAIST_RING, Mount.WAIST_RIGHT_FRONT, 0);
        return worn;
    }

    private static void assertBodyPoint(ModelAsset asset, Vector3f centered, Vector3f body) {
        // centered 是 ModelAsset 居中后的像素坐标：原点在模型中心，y 向上。
        float heightPixels = centered.y() + asset.topOffset();
        assertEquals(-centered.x() / MODEL_UNITS_PER_BLOCK, body.x(), TOLERANCE);
        assertEquals((BODY_TOP_PIXELS - heightPixels) / MODEL_UNITS_PER_BLOCK, body.y(), TOLERANCE);
        assertEquals(centered.z() / MODEL_UNITS_PER_BLOCK, body.z(), TOLERANCE);
    }

    private static List<Vector3f> corners(ModelAsset asset, PoseStack view) {
        var points = new ArrayList<Vector3f>();
        asset.corners(view, BagAnimation.Sample.CLOSED, point -> points.add(new Vector3f(point)));
        return points;
    }

    private static float[] box(List<Vector3f> points) {
        // 依次为 x、y、z 的最小值和最大值。
        float[] box = {
            Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY
        };
        for (var point : points) {
            box[0] = Math.min(box[0], point.x());
            box[1] = Math.min(box[1], point.y());
            box[2] = Math.min(box[2], point.z());
            box[3] = Math.max(box[3], point.x());
            box[4] = Math.max(box[4], point.y());
            box[5] = Math.max(box[5], point.z());
        }
        return box;
    }

    private static ItemRegistry registry() throws Exception {
        var registry = ItemRegistry.newBuilder();
        TextFormat.getParser().merge(Files.readString(SOURCE.resolve("items.txtpb")), registry);
        return registry.build();
    }

    private FileSystem jar() throws Exception {
        return FileSystems.newFileSystem(directory.resolve("gear.jar"), Map.of("create", "true"));
    }

    private static ModelAsset load(FileSystem zip, ItemEntry item) throws Exception {
        // 按 JAR 里的路径读模型，与附属随包分发时的读取方式一致。
        var type = item.getContainer();
        Path root = zip.getPath("/bs3d/bong-gear");
        Path file = root.resolve(type.getAsset().getFile());
        Files.createDirectories(file.getParent());
        Files.copy(SOURCE.resolve(type.getAsset().getFile()), file);
        BbModel model = BbModel.read(root,
                new ModelConfig.Entry(Identifier.parse(type.getModel()), file, null, null, Map.of()));
        assertTrue(model.cubes().size() > 100, item.getId());
        assertEquals(1, model.textures().size(), item.getId());
        assertNull(model.open());
        assertNull(model.close());
        return new ModelAsset(model);
    }

    private static ContainerItems.Definition definition(ItemEntry item) {
        var type = item.getContainer();
        return new ContainerItems.Definition(Identifier.parse(item.getId()), type.getTitle(), type.getModel(),
                type.getInside(), type.getOutside(), type.getMountsList(), type.getWaistFit());
    }
}
