package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.kizuna.backpack3d.client.BbModel.Cube;
import dev.kizuna.backpack3d.client.BbModel.Face;
import org.joml.Vector3f;

import java.util.List;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;

/** 逐面 UV 的原始立方体渲染器，保留 Blockbench 的旋转与分组。 */
public final class ModelAsset {
    public static final ResourceLocation WHITE = new ResourceLocation("bs3d", "textures/white.png");
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
    private final float centerY;
    private final float groundOffset;

    ModelAsset(BbModel model) {
        // 只接收已校验的登记资源，构造及逐帧渲染均不再按模型名隐式读文件。
        this.model = model;
        cubes = model.cubes();
        float top = Float.NEGATIVE_INFINITY;
        for (Cube cube : cubes) {
            top = Math.max(top, cube.to().y);
        }
        centerY = top / 2;
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
        // 落地高度使用关闭姿态的真实最低点，保留低于编辑原点的绑带几何。
        groundOffset = -lowest;
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
            PoseStack matrices, MultiBufferSource buffers, float lid, Integer tint) {
        // GUI 使用固定光照；世界和原版物品通过重载传入环境光。
        render(
                matrices,
                buffers,
                lid,
                tint,
                LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY);
    }

    public void render(
            PoseStack matrices,
            MultiBufferSource buffers,
            float lid,
            Integer tint,
            int light,
            int overlay) {
        // 环境光与覆盖纹理作为同一组渲染参数传递。
        render(matrices, buffers, new BagAnimation.Sample(false /* closing */, lid), tint,
                new Lighting(light, overlay));
    }

    public void render(
            PoseStack matrices,
            MultiBufferSource buffers,
            BagAnimation.Sample pose,
            Integer tint) {
        // GUI 固定光照与世界渲染共用同一几何路径。
        render(
                matrices,
                buffers,
                pose,
                tint,
                new Lighting(LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY));
    }

    private void render(
            PoseStack matrices,
            MultiBufferSource buffers,
            BagAnimation.Sample pose,
            Integer tint,
            Lighting lighting) {
        // 高亮轮廓使用同一几何，普通绘制保留模型原有贴图和逐面光照。
        var context = new RenderContext(matrices, buffers, Paint.from(tint), lighting);
        for (var cube : cubes) {
            matrices.pushPose();
            try {
                transform(matrices, cube, pose);
                var vertices = vertices(cube.from(), cube.to());
                for (int face = 0; face < FACE_VERTICES.length; face++) {
                    Face uv = cube.faces().get(face);
                    if (uv == null) {
                        continue;
                    }
                    renderFace(context, vertices, face, uv);
                }
            } finally {
                matrices.popPose();
            }
        }
    }

    private static void renderFace(RenderContext context, Vector3f[] vertices, int face, Face uv) {
        // 半透明占位保留原模型几何，UV 旋转仅改变贴图四角的对应关系。
        var paint = context.paint();
        ResourceLocation texture = WHITE;
        if (paint.tint() == null) {
            texture = uv.texture();
        }
        RenderType layer = RenderType.entityCutoutNoCull(texture);
        if (paint.alpha() < OPAQUE) {
            layer = RenderType.entityTranslucent(texture);
        }
        var consumer = context.buffers().getBuffer(layer);
        float[][] coords = {
            {uv.u0(), uv.v1()}, {uv.u1(), uv.v1()}, {uv.u1(), uv.v0()}, {uv.u0(), uv.v0()}
        };
        var normal = NORMALS[face];
        var entry = context.matrices().last();
        for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++) {
            var point = vertices[FACE_VERTICES[face][vertex]];
            int uvCorner = Math.floorMod(vertex - uv.rotation(), VERTICES_PER_FACE);
            consumer.vertex(entry.pose(), point.x, point.y, point.z)
                    .color(paint.red(), paint.green(), paint.blue(), paint.alpha())
                    .uv(coords[uvCorner][0], coords[uvCorner][1])
                    .overlayCoords(context.lighting().overlay())
                    .uv2(context.lighting().light())
                    .normal(entry.normal(), normal.x, normal.y, normal.z)
                    .endVertex();
        }
    }

    private record Lighting(int light, int overlay) {}

    private record RenderContext(PoseStack matrices, MultiBufferSource buffers,
                                 Paint paint, Lighting lighting) {}

    private record Paint(Integer tint, float red, float green, float blue, float alpha) {
        private static Paint from(Integer tint) {
            // 无染色时保留贴图颜色，有染色时统一拆出 ARGB 通道。
            if (tint == null) {
                return new Paint(null, OPAQUE, OPAQUE, OPAQUE, OPAQUE);
            }
            return new Paint(tint,
                    FastColor.ARGB32.red(tint) / (float) COLOR_CHANNEL_MASK,
                    FastColor.ARGB32.green(tint) / (float) COLOR_CHANNEL_MASK,
                    FastColor.ARGB32.blue(tint) / (float) COLOR_CHANNEL_MASK,
                    FastColor.ARGB32.alpha(tint) / (float) COLOR_CHANNEL_MASK);
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
        copy.last().pose().set(source.last().pose());
        copy.last().normal().set(source.last().normal());
        return copy;
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
        matrices.translate(-MODEL_CENTER_XZ, -centerY, -MODEL_CENTER_XZ);
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
