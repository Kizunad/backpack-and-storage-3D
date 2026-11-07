package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.kizuna.backpack3d.client.BbModel.Cube;
import dev.kizuna.backpack3d.client.BbModel.Face;
import dev.kizuna.backpack3d.registry.pb.ItemRegistryProto.ModelRenderMode;
import org.joml.Vector3f;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;

/** 逐面 UV 的原始立方体渲染器，保留 Blockbench 的旋转与分组。 */
public final class ModelAsset {
    public static final Identifier WHITE = Identifier.fromNamespaceAndPath("bs3d", "textures/white.png");
    private static final float OPAQUE = 1;
    private static final int COLOR_CHANNEL_MASK = 255;
    private static final float MODEL_CENTER_XZ = 8;
    private static final int VERTICES_PER_FACE = 4;
    private static final double TRIANGLE_AREA_EPSILON_PIXELS_SQUARED = 0.001;
    private static final int[][] FACE_VERTICES = {
        {0, 4, 6, 2}, {5, 1, 3, 7}, {1, 0, 2, 3},
        {4, 5, 7, 6}, {2, 6, 7, 3}, {1, 5, 4, 0}
    };
    private static final Vector3f[] NORMALS = {
        new Vector3f(-1, 0, 0), new Vector3f(1, 0, 0), new Vector3f(0, 0, -1),
        new Vector3f(0, 0, 1), new Vector3f(0, 1, 0), new Vector3f(0, -1, 0)
    };
    private final BbModel model;
    private final List<Cube> cubes;
    private final List<Identifier> textures;
    private final float centerY;
    private final float groundOffset;
    private final ModelRenderMode mode;
    private final Map<String, ModelAsset> segmentAssets = new LinkedHashMap<>();

    ModelAsset(BbModel model) {
        this(model, ModelRenderMode.MODEL_RENDER_MODE_DEFAULT);
    }

    ModelAsset(BbModel model, ModelRenderMode mode) {
        // 只接收已校验的登记资源，构造及逐帧渲染均不再按模型名隐式读文件。
        this.model = model;
        this.mode = mode;
        cubes = model.cubes();
        validateSegments();
        textures = textures(cubes);
        centerY = centerY(cubes);
        groundOffset = closedGroundOffset();
    }

    private static List<Identifier> textures(List<Cube> cubes) {
        // 每张贴图对应一次几何提交，按首次出现顺序收集，避免逐帧遍历去重。
        var used = new LinkedHashSet<Identifier>();
        for (Cube cube : cubes) {
            for (Face face : cube.faces()) {
                if (face != null) {
                    used.add(face.texture());
                }
            }
        }
        return List.copyOf(used);
    }

    private static float centerY(List<Cube> cubes) {
        // 默认容器沿用原有最高点的一半作为纵向中心，原坐标模式不使用该平移。
        float top = Float.NEGATIVE_INFINITY;
        for (Cube cube : cubes) {
            top = Math.max(top, cube.to().y);
        }
        return top / 2;
    }

    private float closedGroundOffset() {
        // 落地高度使用关闭姿态的真实最低点，保留低于编辑原点的绑带几何。
        float lowest = Float.POSITIVE_INFINITY;
        for (Cube cube : cubes) {
            var matrices = new PoseStack();
            transform(matrices, cube, BagAnimation.Sample.CLOSED);
            for (var vertex : vertices(cube.from(), cube.to())) {
                lowest =
                        Math.min(
                                lowest,
                                matrices.last().pose().transformPosition(vertex).y);
            }
        }
        return -lowest;
    }

    float openSeconds() {
        return duration(model.open(), model.close());
    }

    float closeSeconds() {
        return duration(model.close(), model.open());
    }

    private static float duration(ModelClip preferred, ModelClip fallback) {
        // 缺少本方向片段时复用另一方向，静态模型无需等待。
        if (preferred != null) {
            return preferred.seconds();
        }
        if (fallback != null) {
            return fallback.seconds();
        }
        return 0;
    }

    public void render(
            PoseStack matrices, SubmitNodeCollector collector, float lid, Integer tint) {
        // GUI 使用固定光照；世界和原版物品通过重载传入环境光。
        render(
                matrices,
                collector,
                lid,
                tint,
                LightCoordsUtil.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY);
    }

    public void render(
            PoseStack matrices,
            SubmitNodeCollector collector,
            float lid,
            Integer tint,
            int light,
            int overlay) {
        // 环境光与覆盖纹理作为同一组渲染参数传递。
        render(matrices, collector, new BagAnimation.Sample(false /* closing */, lid), tint,
                new Lighting(light, overlay));
    }

    public void render(
            PoseStack matrices,
            SubmitNodeCollector collector,
            BagAnimation.Sample pose,
            Integer tint) {
        // GUI 固定光照与世界渲染共用同一几何路径。
        render(
                matrices,
                collector,
                pose,
                tint,
                new Lighting(LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY));
    }

    private void render(
            PoseStack matrices,
            SubmitNodeCollector collector,
            BagAnimation.Sample pose,
            Integer tint,
            Lighting lighting) {
        // 26.2 的几何先进提交队列、在绘制阶段才写顶点；同一贴图的面合成一次提交，
        // 回调从提交时的矩阵重建各立方体变换。染色轮廓统一用白色贴图，只提交一次。
        var paint = Paint.from(tint);
        List<Identifier> passes = textures;
        if (paint.tint() != null) {
            passes = List.of(WHITE);
        }
        for (Identifier texture : passes) {
            RenderType layer = RenderTypes.entityCutout(texture);
            if (paint.alpha() < OPAQUE) {
                layer = RenderTypes.entityTranslucent(texture);
            }
            collector.submitCustomGeometry(matrices, layer,
                    (entry, consumer) -> emit(entry, consumer, new DrawPass(texture, pose, paint, lighting)));
        }
    }

    private void emit(
            PoseStack.Pose base,
            VertexConsumer consumer,
            DrawPass pass) {
        // 高亮轮廓使用同一几何，普通绘制只写出属于本次贴图的面。
        var matrices = new PoseStack();
        matrices.last().set(base);
        for (var cube : cubes) {
            matrices.pushPose();
            transform(matrices, cube, pass.pose());
            var vertices = vertices(cube.from(), cube.to());
            for (int face = 0; face < FACE_VERTICES.length; face++) {
                Face uv = cube.faces().get(face);
                if (uv == null || (pass.paint().tint() == null && !uv.texture().equals(pass.texture()))) {
                    continue;
                }
                emitFace(matrices.last(), consumer, new FacePass(vertices, face, uv, pass.paint(), pass.lighting()));
            }
            matrices.popPose();
        }
    }

    private static String segment(Cube cube) {
        if (cube.bones().isEmpty()) {
            return "";
        }
        return cube.bones().getFirst().name();
    }

    public Set<String> segments() {
        var result = new LinkedHashSet<String>();
        for (Cube cube : cubes) {
            result.add(segment(cube));
        }
        return Set.copyOf(result);
    }

    public ModelAsset segment(String name) {
        // 段视图复用全部动画和贴图；绘制、环境光、包围盒及命中走同一套几何入口。
        if (mode != ModelRenderMode.MODEL_RENDER_MODE_RAW_SEGMENTS || !segments().contains(name)) {
            throw new IllegalArgumentException("模型不存在原坐标骨段：" + name);
        }
        return segmentAssets.computeIfAbsent(name, selected -> {
            List<Cube> selectedCubes = cubes.stream().filter(cube -> segment(cube).equals(selected)).toList();
            var selectedModel = new BbModel(selectedCubes, model.textures(), model.open(), model.close());
            return new ModelAsset(selectedModel, mode);
        });
    }

    private void validateSegments() {
        // 原坐标模式必须能以顶层组名唯一选段；动画仍按原始 UUID 查找。
        if (mode == ModelRenderMode.UNRECOGNIZED) {
            throw new IllegalArgumentException("未知模型渲染模式");
        }
        if (mode != ModelRenderMode.MODEL_RENDER_MODE_RAW_SEGMENTS) {
            return;
        }
        Map<String, String> roots = new LinkedHashMap<>();
        for (Cube cube : cubes) {
            String name = segment(cube);
            if (name.isBlank()) {
                throw new IllegalArgumentException("raw_segments 的可见立方体必须位于具名顶层组");
            }
            String id = cube.bones().getFirst().id();
            String previous = roots.putIfAbsent(name, id);
            if (previous != null && !previous.equals(id)) {
                throw new IllegalArgumentException("raw_segments 顶层组名重复：" + name);
            }
        }
    }

    private record DrawPass(Identifier texture, BagAnimation.Sample pose, Paint paint, Lighting lighting) {}

    private record FacePass(Vector3f[] vertices, int face, Face uv, Paint paint, Lighting lighting) {}

    private static void emitFace(
            PoseStack.Pose entry,
            VertexConsumer consumer,
            FacePass pass) {
        // 半透明占位保留原模型几何，UV 旋转仅改变贴图四角的对应关系。
        Face uv = pass.uv();
        Paint paint = pass.paint();
        Lighting lighting = pass.lighting();
        float[][] coords = {
            {uv.u0(), uv.v1()}, {uv.u1(), uv.v1()}, {uv.u1(), uv.v0()}, {uv.u0(), uv.v0()}
        };
        int face = pass.face();
        var normal = NORMALS[face];
        // 反射会改变面的正反；UV 随原顶点反序，法线仍按逆转置矩阵变换。
        boolean mirrored = entry.pose().determinant3x3() < 0;
        for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++) {
            int windingVertex = vertex;
            if (mirrored) {
                windingVertex = VERTICES_PER_FACE - 1 - vertex;
            }
            var point = pass.vertices()[FACE_VERTICES[face][windingVertex]];
            int uvCorner = Math.floorMod(windingVertex - uv.rotation(), VERTICES_PER_FACE);
            consumer.addVertex(entry, point.x, point.y, point.z)
                    .setColor(paint.red(), paint.green(), paint.blue(), paint.alpha())
                    .setUv(coords[uvCorner][0], coords[uvCorner][1])
                    .setOverlay(lighting.overlay())
                    .setLight(lighting.light())
                    .setNormal(entry, normal.x, normal.y, normal.z);
        }
    }

    private record Lighting(int light, int overlay) {}

    private record Paint(Integer tint, float red, float green, float blue, float alpha) {
        private static Paint from(Integer tint) {
            // 无染色时保留贴图颜色，有染色时统一拆出 ARGB 通道。
            if (tint == null) {
                return new Paint(null, OPAQUE, OPAQUE, OPAQUE, OPAQUE);
            }
            return new Paint(tint,
                    ARGB.red(tint) / (float) COLOR_CHANNEL_MASK,
                    ARGB.green(tint) / (float) COLOR_CHANNEL_MASK,
                    ARGB.blue(tint) / (float) COLOR_CHANNEL_MASK,
                    ARGB.alpha(tint) / (float) COLOR_CHANNEL_MASK);
        }
    }

    public double hitDepth(
            PoseStack view, BagAnimation.Sample pose, double mouseX, double mouseY) {
        // 按射线在真实三角面片上的深度排序，叠在一起的容器只命中可见的前一个。
        double closest = Double.NEGATIVE_INFINITY;
        for (var cube : cubes) {
            var matrices = copy(view);
            transform(matrices, cube, pose);
            var points = vertices(cube.from(), cube.to());
            for (var point : points) {
                matrices.last().pose().transformPosition(point);
            }
            // 被模型禁用的面没有几何，不能遮挡其后本来可点击的装备。
            for (int face = 0; face < FACE_VERTICES.length; face++) {
                if (cube.faces().get(face) == null) {
                    continue;
                }
                closest = Math.max(closest, faceDepth(points, FACE_VERTICES[face], mouseX, mouseY));
            }
        }
        return closest;
    }

    public static double boxDepth(
            PoseStack matrices, Vector3f from, Vector3f to, double x, double y) {
        var points = vertices(from, to);
        for (var point : points) {
            matrices.last().pose().transformPosition(point);
        }
        double closest = Double.NEGATIVE_INFINITY;
        for (var face : FACE_VERTICES) {
            closest = Math.max(closest, faceDepth(points, face, x, y));
        }
        return closest;
    }

    private static double faceDepth(Vector3f[] points, int[] face, double x, double y) {
        // 四边形拆成两个三角形，模型命中与装备槽命中使用同一深度计算。
        double first = triangleDepth(points[face[0]], points[face[1]], points[face[2]], x, y);
        double second = triangleDepth(points[face[0]], points[face[2]], points[face[3]], x, y);
        return Math.max(first, second);
    }

    private static double triangleDepth(Vector3f a, Vector3f b, Vector3f c, double x, double y) {
        // 正交投影的重心坐标可以直接插值屏幕深度，不用三角形中心近似斜面。
        if (!inside(a, b, c, x, y)) {
            return Double.NEGATIVE_INFINITY;
        }
        double area = edge(a, b, c.x, c.y);
        return (edge(b, c, x, y) * a.z + edge(c, a, x, y) * b.z + edge(a, b, x, y) * c.z) / area;
    }

    public float[] bounds(PoseStack view, BagAnimation.Sample pose) {
        // 动画后的实际几何决定取景边界，盖子掀起时也必须留在可见区域。
        float[] bounds = {
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY
        };
        for (var cube : cubes) {
            var matrices = copy(view);
            transform(matrices, cube, pose);
            for (var point : vertices(cube.from(), cube.to())) {
                matrices.last().pose().transformPosition(point);
                bounds[0] = Math.min(bounds[0], point.x);
                bounds[1] = Math.min(bounds[1], point.y);
                bounds[2] = Math.max(bounds[2], point.x);
                bounds[3] = Math.max(bounds[3], point.y);
            }
        }
        return bounds;
    }

    public static PoseStack copy(PoseStack source) {
        var copy = new PoseStack();
        // Pose 同时保存法线是否需要归一化，复制两块矩阵会丢失这个状态。
        copy.last().set(source.last());
        return copy;
    }

    /** 复制变换并在最外层叠加等比缩放，用于画中画贴图像素与 GUI 坐标互换；等比缩放不改变法线方向。 */
    static PoseStack scaled(PoseStack source, float factor) {
        var scaled = copy(source);
        scaled.last().pose().scaling(factor).mul(source.last().pose());
        return scaled;
    }

    /** 模型中心到顶部挂带的距离，单位为模型像素，与 transform 的居中方式一致。 */
    public float topOffset() {
        return centerY;
    }

    /** 关闭模型的真实最低点到模型中心的距离，单位为模型像素。 */
    public float groundOffset() {
        return groundOffset;
    }

    private void transform(PoseStack matrices, Cube cube, BagAnimation.Sample pose) {
        if (mode == ModelRenderMode.MODEL_RENDER_MODE_DEFAULT) {
            matrices.translate(-MODEL_CENTER_XZ, -centerY, -MODEL_CENTER_XZ);
        }
        ModelClip clip = pose.closing() ? model.close() : model.open();
        float progress = pose.progress();
        if (clip == null) {
            // 只有单向动画时反放另一方向；两者都没有就保持模型原始姿态。
            clip = pose.closing() ? model.open() : model.close();
            progress = 1 - progress;
        }
        for (BbModel.Bone bone : cube.bones()) {
            Vector3f rotation = new Vector3f(bone.rotation());
            Vector3f scale = new Vector3f(1);
            if (clip != null) {
                Vector3f offset = clip.sample(bone.id(), "position", progress);
                matrices.translate(offset.x, offset.y, offset.z);
                rotation.add(clip.sample(bone.id(), "rotation", progress));
                scale = clip.sample(bone.id(), "scale", progress);
            }
            rotateAt(matrices, bone.origin(), rotation, scale);
        }
        // 骨骼链先从父到子应用，最后处理立方体自身枢轴，绘制与命中共用同一变换。
        rotateAt(matrices, cube.origin(), cube.rotation(), new Vector3f(1));
    }

    private static void rotateAt(
            PoseStack matrices, Vector3f origin, Vector3f rotation, Vector3f scale) {
        matrices.translate(origin.x, origin.y, origin.z);
        matrices.mulPose(Axis.ZP.rotationDegrees(rotation.z));
        matrices.mulPose(Axis.YP.rotationDegrees(rotation.y));
        matrices.mulPose(Axis.XP.rotationDegrees(rotation.x));
        matrices.scale(scale.x, scale.y, scale.z);
        matrices.translate(-origin.x, -origin.y, -origin.z);
    }

    public static Vector3f[] vertices(Vector3f from, Vector3f to) {
        Vector3f[] points = new Vector3f[8];
        for (int index = 0; index < points.length; index++) {
            points[index] =
                    new Vector3f(
                            (index & 1) == 0 ? from.x : to.x,
                            (index & 2) == 0 ? from.y : to.y,
                            (index & 4) == 0 ? from.z : to.z);
        }
        return points;
    }

    public static boolean inside(Vector3f a, Vector3f b, Vector3f c, double x, double y) {
        double area = edge(a, b, c.x, c.y);
        if (Math.abs(area) < TRIANGLE_AREA_EPSILON_PIXELS_SQUARED) {
            return false;
        }
        // 点必须位于三条有向边的同一侧；退化三角形已在上面排除。
        double u = edge(a, b, x, y);
        double v = edge(b, c, x, y);
        double w = edge(c, a, x, y);
        return area > 0 ? u >= 0 && v >= 0 && w >= 0 : u <= 0 && v <= 0 && w <= 0;
    }

    private static double edge(Vector3f a, Vector3f b, double x, double y) {
        return (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x);
    }
}
