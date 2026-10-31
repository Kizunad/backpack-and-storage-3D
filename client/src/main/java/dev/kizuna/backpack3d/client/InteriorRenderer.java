package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.kizuna.backpack3d.protocol.OrthogonalRotation;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Cell;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Container;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Size;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import org.joml.Matrix3f;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;

/**
 * 三维格子、真实物品和鼠标命中共用相机及可见层范围。
 *
 * <p>GUI 提取阶段计算投影与命中；格子和物品由 {@link SceneRenderer} 在画中画纹理里绘制，纹理自带深度缓冲，
 * 不再需要在 GUI 深度缓冲上清理和隔离。
 */
public final class InteriorRenderer {
    private static final int RGB_MASK = 0x00FFFFFF;
    private static final int PENDING_ALPHA = 0x70000000;
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
    private static final int[][] CUBE_FACES = {
        {0, 4, 6, 2}, {5, 1, 3, 7}, {1, 0, 2, 3},
        {4, 5, 7, 6}, {2, 6, 7, 3}, {1, 5, 4, 0}
    };
    private final List<CellHit> hits = new ArrayList<>();
    private final Map<String, CellHit> itemHits = new HashMap<>();
    private final LayerRange layers = new LayerRange();
    // 本帧画中画贴图的线宽换算，提取阶段按视口尺寸更新。
    private BoxShapes.LineScale lines;
    private float yaw = INITIAL_YAW_DEGREES;
    private float pitch = INITIAL_PITCH_DEGREES;
    private float zoom = 1;

    static void register() {
        // 经 Fabric 注册：同一帧有多个内景窗口时，Fabric 为每个状态各分配一个渲染器和纹理。
        PictureInPictureRendererRegistry.register(context -> new SceneRenderer());
    }

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
            GuiGraphicsExtractor context,
            VolumeModule module,
            Container container,
            Rect bounds,
            int mouseX,
            int mouseY,
            String selected,
            Item preview,
            int orientation) {
        // 坐标均为当前 GUI 矩阵下的局部坐标；画中画状态带上该矩阵，嵌入工作台时由 GUI 渲染器平移贴图。
        hits.clear();
        itemHits.clear();
        var size = container.getSize();
        layers.resize(size.getHeight());
        lines = BoxShapes.LineScale.texture(bounds.width(), bounds.height());
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
        var world = new Matrix4f(matrices.last().pose());
        collectCellHits(world, size);
        collectItemHits(world, module, container.getId());
        Cell hover =
                placementAt(
                        module.state().getItemsList(),
                        container.getId(),
                        container.getSize(),
                        preview,
                        orientation,
                        mouseX,
                        mouseY);
        var frame =
                new Frame(
                        module, container.getId(), size, world, scene.normal(), selected, preview, hover, orientation);
        context.guiRenderState.addPicturesInPictureState(
                SceneState.of(
                        this, frame, bounds, new Matrix3x2f(context.pose()), context.scissorStack.peek()));
        drawCounts(context, world, module, container.getId(), bounds);
    }

    private void collectCellHits(Matrix4f world, Size size) {
        // 命中只生成可见层，与网格绘制使用同一组内缩格框。
        for (int y = layers.lower(); y < layers.upper(); y++) {
            for (int z = 0; z < size.getDepth(); z++) {
                for (int x = 0; x < size.getWidth(); x++) {
                    var points = ModelAsset.vertices(cellFrom(x, y, z), cellTo(x, y, z));
                    for (var point : points) {
                        world.transformPosition(point);
                    }
                    hits.add(new CellHit(Volumes.cell(x, y, z), points));
                }
            }
        }
    }

    private void collectItemHits(Matrix4f world, VolumeModule module, String container) {
        // 绘制与命中共用同一占格投影，旋转场景后仍按实际可见位置选择。
        for (var item : module.state().getItemsList()) {
            if (!item.getContainer().equals(container)
                    || !layers.intersects(item.getCell().getY(), item.getSize().getHeight())) {
                continue;
            }
            var itemPoints = ModelAsset.vertices(visibleFrom(item), visibleTo(item));
            for (var point : itemPoints) {
                world.transformPosition(point);
            }
            itemHits.put(item.getId(), new CellHit(item.getCell(), itemPoints));
        }
    }

    private void paint(Frame frame, PoseStack matrices, SubmitNodeCollector collector) {
        // 渲染阶段：矩阵已对齐到提取时的 GUI 局部坐标，再乘场景矩阵，与命中投影完全一致。
        matrices.mulPose(frame.world());
        matrices.last().normal().set(frame.normal());
        SubmitNodeCollector items = collector;
        if (!layers.full()) {
            // 切片只裁物品四边形，网格线已按层范围生成；裁切按容器坐标的 Y 轴进行。
            Matrix4f inverse = new Matrix4f(matrices.last().pose()).invert();
            items = new SlicingSubmitCollector(collector, inverse, layers.lower(), layers.upper());
        }
        drawGrid(matrices, collector, frame.size());
        drawItems(matrices, items, frame.module(), frame.container(), frame.selected());
        drawOccupiedOutline(matrices, collector, frame.module().state().getItemsList(), frame.container());
        if (frame.preview() == null) {
            drawCellHover(matrices, collector, frame.hover());
        }
        drawPreview(
                matrices,
                items,
                collector,
                frame.module(),
                frame.container(),
                frame.preview(),
                frame.hover(),
                frame.orientation());
    }

    private void drawGrid(PoseStack matrices, SubmitNodeCollector collector, Size size) {
        // 网格只画可见层；底板跟随剖面的下界，相机中心和缩放不发生跳变。
        for (int y = layers.lower(); y < layers.upper(); y++) {
            for (int z = 0; z < size.getDepth(); z++) {
                for (int x = 0; x < size.getWidth(); x++) {
                    var from = cellFrom(x, y, z);
                    var to = cellTo(x, y, z);
                    int floor =
                            color(
                                    ((x + z) & 1) == 0
                                            ? ThemeTokens.INVENTORY_CELL_EVEN
                                            : ThemeTokens.INVENTORY_CELL_ODD);
                    if (y == layers.lower()) {
                        solid(
                                matrices,
                                collector,
                                new Vector3f(from.x, y - FLOOR_THICKNESS, from.z),
                                new Vector3f(to.x, y, to.z),
                                floor);
                    }
                    wire(matrices, collector, from, to, color(ThemeTokens.INVENTORY_BORDER));
                }
            }
        }
    }

    private void drawCellHover(PoseStack matrices, SubmitNodeCollector collector, Cell hover) {
        // 未携带物品时只提示当前格；携带时由候选体积统一显示可放置或拒绝颜色。
        if (hover != null) {
            wire(
                    matrices,
                    collector,
                    new Vector3f(hover.getX(), hover.getY(), hover.getZ()),
                    new Vector3f(hover.getX() + 1, hover.getY() + 1, hover.getZ() + 1),
                    color(ThemeTokens.ACCENT));
        }
    }

    private void drawItems(
            PoseStack matrices,
            SubmitNodeCollector collector,
            VolumeModule module,
            String container,
            String selected) {
        // 与命中使用同一可见体积；未收到回执的物品保留原占格，用半透明实体占位，不预测移动库存。
        for (var item : module.state().getItemsList()) {
            if (!item.getContainer().equals(container)
                    || !layers.intersects(item.getCell().getY(), item.getSize().getHeight())) {
                continue;
            }
            var from = visibleFrom(item);
            var to = visibleTo(item);
            if (module.pendingItem(item.getId())) {
                int tint = color(ThemeTokens.ACCENT) & RGB_MASK | PENDING_ALPHA;
                solid(matrices, collector, from, to, tint);
            } else {
                renderItem(matrices, collector, module, item, item.getCell(), item.getOrientation());
            }
            if (selected.equals(item.getId())) {
                wire(matrices, collector, from, to, color(ThemeTokens.ACCENT));
            }
        }
    }

    private void drawPreview(
            PoseStack matrices,
            SubmitNodeCollector itemCollector,
            SubmitNodeCollector collector,
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
                collector,
                new Vector3f(hover.getX(), Math.max(hover.getY(), layers.lower()), hover.getZ()),
                new Vector3f(
                        hover.getX() + footprint.getWidth(),
                        Math.min(hover.getY() + footprint.getHeight(), layers.upper()),
                        hover.getZ() + footprint.getDepth()),
                color(fits ? ThemeTokens.ACCENT : ThemeTokens.INVENTORY_DROP_INVALID));
        if (fits) {
            renderItem(matrices, itemCollector, module, preview, hover, orientation);
        }
    }

    private void drawCounts(
            GuiGraphicsExtractor context,
            Matrix4f world,
            VolumeModule module,
            String container,
            Rect bounds) {
        // 数量放在投影坐标旁，作为二维文字叠在画中画纹理之上。
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
            world.transformPosition(projected);
            var stack = module.stack(item.getId());
            if (stack.getCount() > 1 && bounds.contains(projected.x, projected.y)) {
                context.text(
                        Minecraft.getInstance().font,
                        "×" + stack.getCount(),
                        (int) projected.x + TEXT_OFFSET_PIXELS,
                        (int) projected.y,
                        color(ThemeTokens.TEXT));
            }
        }
    }

    private static void renderItem(
            PoseStack matrices,
            SubmitNodeCollector collector,
            VolumeModule module,
            Item item,
            Cell cell,
            int orientation) {
        // 模型姿态与占格使用同一离散旋转；旋转顺序不会被欧拉角重建改变。
        var footprint = OrthogonalRotation.size(Volumes.baseSize(item), orientation);
        var base = Volumes.baseSize(item);
        matrices.pushPose();
        matrices.translate(
                cell.getX() + footprint.getWidth() / 2f,
                cell.getY() + footprint.getHeight() / 2f,
                cell.getZ() + footprint.getDepth() / 2f);
        var m = OrthogonalRotation.matrix(orientation);
        matrices.mulPose(
                new Quaternionf()
                        .setFromNormalized(
                                new Matrix3f(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])));
        float displaySize =
                Math.max(base.getWidth(), Math.max(base.getHeight(), base.getDepth())) * ITEM_SCALE;
        matrices.scale(displaySize, displaySize, displaySize);
        var client = Minecraft.getInstance();
        var state = new ItemStackRenderState();
        client.getItemModelResolver()
                .updateForTopItem(
                        state,
                        module.stack(item.getId()),
                        ItemDisplayContext.FIXED,
                        client.level,
                        null,
                        item.getId().hashCode());
        state.submit(matrices, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        matrices.popPose();
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

    private static Vector3f cellFrom(int x, int y, int z) {
        return new Vector3f(x + CELL_INSET, y + CELL_INSET, z + CELL_INSET);
    }

    private static Vector3f cellTo(int x, int y, int z) {
        return new Vector3f(x + 1 - CELL_INSET, y + 1 - CELL_INSET, z + 1 - CELL_INSET);
    }

    private Vector3f visibleFrom(Item item) {
        // 物品体积按可见层裁剪；单位为格，容器坐标。
        var position = item.getCell();
        return new Vector3f(position.getX(), Math.max(position.getY(), layers.lower()), position.getZ());
    }

    private Vector3f visibleTo(Item item) {
        var position = item.getCell();
        var size = item.getSize();
        return new Vector3f(
                position.getX() + size.getWidth(),
                Math.min(position.getY() + size.getHeight(), layers.upper()),
                position.getZ() + size.getDepth());
    }

    private void drawOccupiedOutline(
            PoseStack matrices, SubmitNodeCollector collector, List<Item> items, String container) {
        // 只强调已占用区域的轮廓；相邻格连接处仍可见暗色参考网格，但不画高亮接缝。
        int highlight = color(ThemeTokens.ACCENT);
        var edges = OccupiedOutline.build(items, container, layers);
        collector.submitCustomGeometry(
                matrices,
                RenderTypes.lines(),
                (pose, consumer) -> {
                    for (var edge : edges) {
                        BoxShapes.line(
                                pose,
                                consumer,
                                new Vector3f(edge.x(), edge.y(), edge.z()),
                                new Vector3f(edge.endX(), edge.endY(), edge.endZ()),
                                highlight,
                                lines);
                    }
                });
    }

    private static void solid(
            PoseStack matrices, SubmitNodeCollector collector, Vector3f from, Vector3f to, int color) {
        // 半透明实体保留来源体积，不用不透明物品遮住待确认状态。
        var vertices = ModelAsset.vertices(from, to);
        collector.submitCustomGeometry(
                matrices,
                RenderTypes.entityTranslucent(ModelAsset.WHITE),
                (pose, consumer) -> {
                    for (var face : CUBE_FACES) {
                        for (int index : face) {
                            consumer.addVertex(pose, vertices[index])
                                    .setColor(color)
                                    .setUv(0, 0)
                                    .setOverlay(OverlayTexture.NO_OVERLAY)
                                    .setLight(LightCoordsUtil.FULL_BRIGHT)
                                    .setNormal(pose, 0, 1, 0);
                        }
                    }
                });
    }

    private void wire(
            PoseStack matrices, SubmitNodeCollector collector, Vector3f from, Vector3f to, int color) {
        // 包围线与物品模型共享同一场景矩阵。
        collector.submitCustomGeometry(
                matrices,
                RenderTypes.lines(),
                (pose, consumer) -> BoxShapes.outline(pose, consumer, from, to, color, lines));
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

    /** 一帧内景的绘制输入；world 为容器坐标（格）到 GUI 局部坐标的变换。 */
    private record Frame(
            VolumeModule module,
            String container,
            Size size,
            Matrix4f world,
            Matrix3f normal,
            String selected,
            Item preview,
            Cell hover,
            int orientation) {}

    /** 内景画中画状态：(x0, y0)–(x1, y1) 为视口的 GUI 局部坐标，pose 为提交时的 GUI 矩阵。 */
    private record SceneState(
            InteriorRenderer interior,
            Frame frame,
            int x0,
            int y0,
            int x1,
            int y1,
            float scale,
            Matrix3x2fc pose,
            ScreenRectangle scissorArea,
            ScreenRectangle bounds)
            implements PictureInPictureRenderState {
        static SceneState of(
                InteriorRenderer interior,
                Frame frame,
                Rect view,
                Matrix3x2fc pose,
                ScreenRectangle scissorArea) {
            // 外框按 GUI 矩阵变换后与裁剪求交；GUI 渲染器据此决定层叠，无交集时不绘制。
            var bounds = new ScreenRectangle(view.x(), view.y(), view.width(), view.height()).transformMaxBounds(pose);
            if (scissorArea != null) {
                bounds = scissorArea.intersection(bounds);
            }
            return new SceneState(
                    interior, frame, view.x(), view.y(), view.right(), view.bottom(), 1, pose, scissorArea, bounds);
        }
    }

    /** 内景画中画渲染器：在独立纹理和深度缓冲里画格子与物品，再由 GUI 渲染器贴回窗口。 */
    private static final class SceneRenderer extends PictureInPictureRenderer<SceneState> {
        @Override
        public Class<SceneState> getRenderStateClass() {
            return SceneState.class;
        }

        @Override
        protected void renderToTexture(SceneState state, PoseStack matrices, SubmitNodeCollector collector) {
            // 基础矩阵已把原点移到纹理中心、按 GUI 缩放放大并翻转 Z；
            // 翻回 Z 并把原点移回视口左上角所在的 GUI 局部坐标，之后与提取阶段的投影一致。
            // 26.2 的 ITEMS_FLAT 主光正对观察者、副光来自屏幕上方，与 1.20.1 setupFor3DItems 的屏幕方向一致。
            Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_FLAT);
            matrices.scale(1, 1, -1);
            matrices.translate(-(state.x0() + state.x1()) / 2f, -(state.y0() + state.y1()) / 2f, 0);
            state.interior().paint(state.frame(), matrices, collector);
        }

        @Override
        protected float getTranslateY(int height, int guiScale) {
            // 默认原点在纹理底边；场景按视口中心对齐。
            return height / 2f;
        }

        @Override
        protected String getTextureLabel() {
            return "bs3d interior";
        }
    }
}
