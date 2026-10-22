package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.kizuna.backpack3d.protocol.*;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.*;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/** 三维格子、真实物品和鼠标命中共用相机及可见层范围。 */
public final class InteriorRenderer {
    private static final float ITEM_SCALE = 0.82f;
    private static final float CELL_INSET = 0.04f;
    private static final float FLOOR_THICKNESS = 0.025f;
    private static final float PROJECTION_PADDING_CELLS = 1.5f;
    private static final int TEXT_OFFSET_PIXELS = 4;
    private static final float INITIAL_YAW_DEGREES = -32;
    private static final float INITIAL_PITCH_DEGREES = 26;
    private static final float MIN_PITCH_DEGREES = 10;
    private static final float MAX_PITCH_DEGREES = 75;
    private static final float MIN_ZOOM = 0.65f;
    private static final float MAX_ZOOM = 1.6f;
    private static final float ZOOM_PER_SCROLL_STEP = 0.08f;
    private static final float COLOR_CHANNEL_MAX = 255f;
    private static final int[][] CUBE_FACES = {
        {0, 4, 6, 2}, {5, 1, 3, 7}, {1, 0, 2, 3},
        {4, 5, 7, 6}, {2, 6, 7, 3}, {1, 5, 4, 0}
    };
    private final List<CellHit> hits = new ArrayList<>();
    private final Map<String, CellHit> itemHits = new HashMap<>();
    private final LayerRange layers = new LayerRange();
    private float yaw = INITIAL_YAW_DEGREES;
    private float pitch = INITIAL_PITCH_DEGREES;
    private float zoom = 1;

    public void rotate(double dx, double dy) {
        // 俯仰角不越过水平面，保持底层与前后方向可辨认。
        yaw -= (float) dx;
        pitch = Math.max(MIN_PITCH_DEGREES, Math.min(MAX_PITCH_DEGREES, pitch - (float) dy));
    }

    public void zoom(double delta) {
        // 缩放限制在可操作范围，避免格子缩成无法命中的点。
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + (float) delta * ZOOM_PER_SCROLL_STEP));
    }

    public LayerRange layers() {
        return layers;
    }

    public void invalidateHits() {
        // 范围条事件可能先于下一帧绘制到达，旧投影不能用于紧接着发生的库存点击。
        hits.clear();
        itemHits.clear();
    }

    public void draw(
            DrawContext context,
            VolumeModule module,
            Container container,
            Rect bounds,
            int mouseX,
            int mouseY,
            String selected,
            Item preview,
            int orientation) {
        // 独立页面的屏幕原点为零，嵌入场景共用下一重载的渲染与命中规则。
        draw(context, module, container, bounds, mouseX, mouseY, selected, preview, orientation, 0, 0);
    }

    public void draw(
            DrawContext context, VolumeModule module, Container container, Rect bounds,
            int mouseX, int mouseY, String selected, Item preview, int orientation,
            int scissorX, int scissorY) {
        // 保存 UI 批次后启用独立深度场，结束时恢复裁剪和光照，避免影响其他窗口。
        hits.clear();
        itemHits.clear();
        var size = container.getSize();
        layers.resize(size.getHeight());
        float scale =
                Math.min(bounds.width(), bounds.height())
                        / (Math.max(size.getHeight(), Math.max(size.getWidth(), size.getDepth()))
                                + PROJECTION_PADDING_CELLS)
                        * zoom;
        var scene =
                new SceneTransform(
                        bounds.x() + bounds.width() / 2f,
                        bounds.y() + bounds.height() / 2f,
                        scale,
                        yaw,
                        pitch);
        var matrices = scene.matrices();
        matrices.translate(-size.getWidth() / 2f, -size.getHeight() / 2f, -size.getDepth() / 2f);
        var buffers = MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();
        context.draw();
        context.enableScissor(bounds.x() + scissorX, bounds.y() + scissorY,
                bounds.right() + scissorX, bounds.bottom() + scissorY);
        GuiSceneDepth.clear(context);
        RenderSystem.enableDepthTest();
        DiffuseLighting.enableGuiDepthLighting();
        try {
            drawGrid(matrices, buffers, size);
            buffers.draw();
            VertexConsumerProvider itemBuffers = clippedItems(buffers, matrices);
            drawItems(matrices, itemBuffers, module, container.getId(), selected);
            drawOccupiedOutline(
                    matrices, buffers, module.state().getItemsList(), container.getId());
            Cell hover =
                    placementAt(
                            module.state().getItemsList(),
                            container.getId(),
                            container.getSize(),
                            preview,
                            orientation,
                            mouseX,
                            mouseY);
            if (preview == null) {
                drawCellHover(matrices, buffers, hover);
            }
            drawPreview(
                    matrices,
                    itemBuffers,
                    buffers,
                    module,
                    container.getId(),
                    preview,
                    hover,
                    orientation);
            buffers.draw();
        } finally {
            // 保留内景颜色，清掉它的深度后再交回二维 UI，避免拖窗时盖住后绘制的控件。
            GuiSceneDepth.clear(context);
            context.disableScissor();
            RenderSystem.disableDepthTest();
            DiffuseLighting.enableGuiDepthLighting();
        }
        drawCounts(context, matrices, module, container.getId(), bounds);
    }

    private void drawGrid(
            MatrixStack matrices, VertexConsumerProvider.Immediate buffers, Size size) {
        // 网格和命中只生成可见层；底板跟随剖面的下界，相机中心和缩放不发生跳变。
        for (int y = layers.lower(); y < layers.upper(); y++) {
            for (int z = 0; z < size.getDepth(); z++) {
                for (int x = 0; x < size.getWidth(); x++) {
                    var cell = Volumes.cell(x, y, z);
                    var from = new Vector3f(x + CELL_INSET, y + CELL_INSET, z + CELL_INSET);
                    var to =
                            new Vector3f(
                                    x + 1 - CELL_INSET, y + 1 - CELL_INSET, z + 1 - CELL_INSET);
                    var points = ModelAsset.vertices(from, to);
                    for (var point : points) {
                        matrices.peek().getPositionMatrix().transformPosition(point);
                    }
                    hits.add(new CellHit(cell, points));
                    int floor =
                            color(
                                    ((x + z) & 1) == 0
                                            ? ThemeTokens.INVENTORY_CELL_EVEN
                                            : ThemeTokens.INVENTORY_CELL_ODD);
                    if (y == layers.lower()) {
                        solid(
                                matrices,
                                buffers,
                                new Vector3f(from.x, y - FLOOR_THICKNESS, from.z),
                                new Vector3f(to.x, y, to.z),
                                floor);
                    }
                    wire(matrices, buffers, from, to, color(ThemeTokens.INVENTORY_BORDER));
                }
            }
        }
    }

    private void drawCellHover(MatrixStack matrices, VertexConsumerProvider buffers, Cell hover) {
        // 未携带物品时只提示当前格；携带时由候选体积统一显示可放置或拒绝颜色。
        if (hover != null) {
            wire(
                    matrices,
                    buffers,
                    new Vector3f(hover.getX(), hover.getY(), hover.getZ()),
                    new Vector3f(hover.getX() + 1, hover.getY() + 1, hover.getZ() + 1),
                    color(ThemeTokens.ACCENT));
        }
    }

    private void drawItems(
            MatrixStack matrices,
            VertexConsumerProvider buffers,
            VolumeModule module,
            String container,
            String selected) {
        // 绘制与命中共用同一占格投影，旋转场景后仍按实际可见位置选择。
        for (var item : module.state().getItemsList()) {
            if (!item.getContainer().equals(container)) {
                continue;
            }
            var position = item.getCell();
            var size = item.getSize();
            if (!layers.intersects(position.getY(), size.getHeight())) {
                continue;
            }
            float bottom = Math.max(position.getY(), layers.lower());
            float top = Math.min(position.getY() + size.getHeight(), layers.upper());
            var itemPoints =
                    ModelAsset.vertices(
                            new Vector3f(position.getX(), bottom, position.getZ()),
                            new Vector3f(
                                    position.getX() + size.getWidth(),
                                    top,
                                    position.getZ() + size.getDepth()));
            for (var point : itemPoints) {
                matrices.peek().getPositionMatrix().transformPosition(point);
            }
            itemHits.put(item.getId(), new CellHit(position, itemPoints));
            renderItem(matrices, buffers, module, item, position, item.getOrientation());
            if (selected.equals(item.getId())) {
                wire(
                        matrices,
                        buffers,
                        new Vector3f(position.getX(), bottom, position.getZ()),
                        new Vector3f(
                                position.getX() + size.getWidth(),
                                top,
                                position.getZ() + size.getDepth()),
                        color(ThemeTokens.ACCENT));
            }
        }
    }

    private void drawPreview(
            MatrixStack matrices,
            VertexConsumerProvider itemBuffers,
            VertexConsumerProvider buffers,
            VolumeModule module,
            String container,
            Item preview,
            Cell hover,
            int orientation) {
        // 悬停只画候选，真实库存仍由服务器快照决定；非法落点不发送请求。
        if (preview == null || hover == null) {
            return;
        }
        var footprint = OrthogonalRotation.size(Volumes.baseSize(preview), orientation);
        boolean fits =
                layers.contains(hover.getY(), footprint.getHeight())
                        && Volumes.fits(
                                module.state().getItemsList(),
                                container,
                                module.container(container).getSize(),
                                hover,
                                footprint,
                                preview.getId());
        wire(
                matrices,
                buffers,
                new Vector3f(hover.getX(), Math.max(hover.getY(), layers.lower()), hover.getZ()),
                new Vector3f(
                        hover.getX() + footprint.getWidth(),
                        Math.min(hover.getY() + footprint.getHeight(), layers.upper()),
                        hover.getZ() + footprint.getDepth()),
                color(fits ? ThemeTokens.ACCENT : ThemeTokens.INVENTORY_DROP_INVALID));
        if (fits) {
            renderItem(matrices, itemBuffers, module, preview, hover, orientation);
        }
    }

    private void drawCounts(
            DrawContext context,
            MatrixStack matrices,
            VolumeModule module,
            String container,
            Rect bounds) {
        // 数量放在投影坐标旁，模型始终走原版三维渲染而不是 drawItem 的 GUI 图标。
        for (var item : module.state().getItemsList()) {
            if (!item.getContainer().equals(container)
                    || !layers.intersects(item.getCell().getY(), item.getSize().getHeight())) {
                continue;
            }
            var position = item.getCell();
            var projected =
                    new Vector3f(
                            position.getX() + item.getSize().getWidth() / 2f,
                            Math.min(position.getY() + item.getSize().getHeight(), layers.upper()),
                            position.getZ() + item.getSize().getDepth() / 2f);
            matrices.peek().getPositionMatrix().transformPosition(projected);
            var stack = module.stack(item.getId());
            if (stack.getCount() > 1 && bounds.contains(projected.x, projected.y)) {
                context.drawTextWithShadow(
                        MinecraftClient.getInstance().textRenderer,
                        "×" + stack.getCount(),
                        (int) projected.x + TEXT_OFFSET_PIXELS,
                        (int) projected.y,
                        color(ThemeTokens.TEXT));
            }
        }
    }

    private static void renderItem(
            MatrixStack matrices,
            VertexConsumerProvider buffers,
            VolumeModule module,
            Item item,
            Cell cell,
            int orientation) {
        // 模型姿态与占格使用同一离散旋转；旋转顺序不会被欧拉角重建改变。
        var footprint = OrthogonalRotation.size(Volumes.baseSize(item), orientation);
        var base = Volumes.baseSize(item);
        matrices.push();
        try {
            matrices.translate(
                    cell.getX() + footprint.getWidth() / 2f,
                    cell.getY() + footprint.getHeight() / 2f,
                    cell.getZ() + footprint.getDepth() / 2f);
            var m = OrthogonalRotation.matrix(orientation);
            matrices.multiply(
                    new Quaternionf()
                            .setFromNormalized(
                                    new Matrix3f(
                                            m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])));
            float displaySize =
                    Math.max(base.getWidth(), Math.max(base.getHeight(), base.getDepth()))
                            * ITEM_SCALE;
            matrices.scale(displaySize, displaySize, displaySize);
            var client = MinecraftClient.getInstance();
            client.getItemRenderer()
                    .renderItem(
                            module.stack(item.getId()),
                            ModelTransformationMode.FIXED,
                            LightmapTextureManager.MAX_LIGHT_COORDINATE,
                            OverlayTexture.DEFAULT_UV,
                            matrices,
                            buffers,
                            client.world,
                            item.getId().hashCode());
        } finally {
            matrices.pop();
        }
    }

    public Cell cellAt(double x, double y) {
        // 多个格子投影重叠时选择靠近镜头的格，旋转场景后重新计算落点。
        return hits.stream()
                .filter(hit -> hit.contains(x, y))
                .max(Comparator.comparingDouble(CellHit::depth))
                .map(CellHit::cell)
                .orElse(null);
    }

    public Cell placementAt(
            List<Item> items,
            String container,
            Size bounds,
            Item preview,
            int orientation,
            double x,
            double y) {
        // 仅在可见格层内寻找完整放得下的候选；隐藏层的占用仍参与真实碰撞检查。
        if (preview == null) {
            return cellAt(x, y);
        }
        Size footprint = OrthogonalRotation.size(Volumes.baseSize(preview), orientation);
        return hits.stream()
                .filter(hit -> layers.contains(hit.cell().getY(), footprint.getHeight()))
                .filter(hit -> hit.contains(x, y))
                .filter(
                        hit ->
                                Volumes.fits(
                                        items,
                                        container,
                                        bounds,
                                        hit.cell(),
                                        footprint,
                                        preview.getId()))
                .max(Comparator.comparingDouble(CellHit::depth))
                .map(CellHit::cell)
                .orElseGet(() -> cellAt(x, y));
    }

    public String itemAt(double x, double y) {
        // 空格的线框不遮住真实物品；优先选择射线上的最近物品，再选择空格落点。
        return itemHits.entrySet().stream()
                .filter(entry -> entry.getValue().contains(x, y))
                .max(Comparator.comparingDouble(entry -> entry.getValue().depth()))
                .map(Map.Entry::getKey)
                .orElse("");
    }

    private static int color(String token) {
        return InventoryUiClient.runtime().color(token);
    }

    private VertexConsumerProvider clippedItems(
            VertexConsumerProvider buffers, MatrixStack matrices) {
        // 全景直接使用原版批次。切片只裁物品 QUADS，网格线已按层范围生成。
        if (layers.full()) {
            return buffers;
        }
        Matrix4f inverse = new Matrix4f(matrices.peek().getPositionMatrix()).invert();
        return layer -> {
            VertexConsumer consumer = buffers.getBuffer(layer);
            if (layer.getDrawMode() != VertexFormat.DrawMode.QUADS) {
                return consumer;
            }
            return new SliceVertexConsumer(consumer, inverse, layers.lower(), layers.upper());
        };
    }

    private void drawOccupiedOutline(
            MatrixStack matrices,
            VertexConsumerProvider buffers,
            List<Item> items,
            String container) {
        // 只强调已占用区域的轮廓；相邻格连接处仍可见暗色参考网格，但不画高亮接缝。
        var consumer = buffers.getBuffer(RenderLayer.getLines());
        int highlight = color(ThemeTokens.ACCENT);
        for (var edge : OccupiedOutline.build(items, container, layers)) {
            float nx = edge.endX() - edge.x();
            float ny = edge.endY() - edge.y();
            float nz = edge.endZ() - edge.z();
            consumer.vertex(matrices.peek().getPositionMatrix(), edge.x(), edge.y(), edge.z())
                    .color(highlight)
                    .normal(matrices.peek().getNormalMatrix(), nx, ny, nz)
                    .next();
            consumer.vertex(
                            matrices.peek().getPositionMatrix(),
                            edge.endX(),
                            edge.endY(),
                            edge.endZ())
                    .color(highlight)
                    .normal(matrices.peek().getNormalMatrix(), nx, ny, nz)
                    .next();
        }
    }

    private static void solid(
            MatrixStack matrices,
            VertexConsumerProvider buffers,
            Vector3f from,
            Vector3f to,
            int color) {
        var vertices = ModelAsset.vertices(from, to);
        var consumer = buffers.getBuffer(RenderLayer.getEntityCutoutNoCull(ModelAsset.WHITE));
        for (var face : CUBE_FACES) {
            for (int index : face) {
                var point = vertices[index];
                consumer.vertex(matrices.peek().getPositionMatrix(), point.x, point.y, point.z)
                        .color(color)
                        .texture(0, 0)
                        .overlay(OverlayTexture.DEFAULT_UV)
                        .light(LightmapTextureManager.MAX_LIGHT_COORDINATE)
                        .normal(matrices.peek().getNormalMatrix(), 0, 1, 0)
                        .next();
            }
        }
    }

    private static void wire(
            MatrixStack matrices,
            VertexConsumerProvider buffers,
            Vector3f from,
            Vector3f to,
            int color) {
        // RenderLayer 自带线宽与深度设置，包围线与物品模型共享同一世界矩阵。
        WorldRenderer.drawBox(
                matrices,
                buffers.getBuffer(RenderLayer.getLines()),
                from.x,
                from.y,
                from.z,
                to.x,
                to.y,
                to.z,
                net.minecraft.util.math.ColorHelper.Argb.getRed(color) / COLOR_CHANNEL_MAX,
                net.minecraft.util.math.ColorHelper.Argb.getGreen(color) / COLOR_CHANNEL_MAX,
                net.minecraft.util.math.ColorHelper.Argb.getBlue(color) / COLOR_CHANNEL_MAX,
                1);
    }

    /** x/y 为左上角坐标，width/height 为尺寸，均使用 GUI 逻辑像素；右边和下边不含端点。 */
    public record Rect(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public boolean contains(double px, double py) {
            return px >= x && py >= y && px < right() && py < bottom();
        }
    }

    private record CellHit(Cell cell, Vector3f[] vertices) {
        boolean contains(double x, double y) {
            for (var face : CUBE_FACES) {
                if (ModelAsset.inside(vertices[face[0]], vertices[face[1]], vertices[face[2]], x, y)
                        || ModelAsset.inside(
                                vertices[face[0]], vertices[face[2]], vertices[face[3]], x, y)) {
                    return true;
                }
            }
            return false;
        }

        double depth() {
            return Arrays.stream(vertices).mapToDouble(v -> v.z).average().orElse(0);
        }
    }
}
