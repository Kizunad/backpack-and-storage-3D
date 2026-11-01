package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.PlayerSlots;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Placement;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;

import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 与世界外观共用躯干挂点；UI 预览额外启用开盖动画、轮廓与精确命中。
 *
 * <p>26.2 先抽取玩家渲染快照，再在原版实体画中画贴图中提交；快照带上本预览，渲染层据此区分 UI 与世界。
 * 命中缓存在渲染时按场景局部 GUI 坐标重建，界面抽取和输入读取的是上一次绘制的结果。
 */
public final class WornPreview {
    // 标记由预览抽取的快照；世界中每帧新建的玩家快照不带这个值。
    private static final RenderStateDataKey<WornPreview> PREVIEW =
            RenderStateDataKey.create(() -> "bs3d worn preview");
    private static final float INITIAL_YAW_DEGREES = 145;
    private static final float MAX_PITCH_DEGREES = 55;
    private static final float DRAG_DEGREES_PER_PIXEL = 0.7f;
    private static final int OUTLINE_PIXELS = 1;
    private static final float OUTLINE_DEPTH = 2;
    private static final float GROUND_MODEL_SPACING_BLOCKS = 0.8f;
    private static final float GROUND_MODEL_Y_BLOCKS = 1.2f;
    private static final float GROUND_MODEL_SCALE = 0.04f;
    private static final int RGB_MASK = 0x00FFFFFF;
    private static final int PENDING_ALPHA = 0x70000000;
    private final VolumeModule module;
    private final Map<String, ModelAsset> assets = new LinkedHashMap<>();
    private final Map<String, PoseStack> transforms = new LinkedHashMap<>();
    private final PlayerSlotTargets slots = new PlayerSlotTargets();
    private Map<String, BagAnimation> animations;
    private float yaw = INITIAL_YAW_DEGREES;
    private float pitch;
    private int mouseX;
    private int mouseY;
    private boolean interactive;
    // 本帧人物画中画贴图的线宽换算，绘制时按场景尺寸更新。
    private BoxShapes.LineScale lines;
    private String hovered = "";
    private String dropSource = "";
    private PoseStack torso;
    // 装备拖放体积单位为方块，略超出躯干，方便落在腰侧和胸背表面。
    private static final Vector3f EQUIP_FROM = new Vector3f(-0.5f, 0, -0.4f);
    private static final Vector3f EQUIP_TO = new Vector3f(0.5f, 0.85f, 0.4f);
    private static final float WAIST_MIN_Y = 0.45f;

    public WornPreview(VolumeModule module) {
        // 预览只读取模块快照，物品归属始终由服务器维护。
        this.module = module;
    }

    public void dropSource(String source) {
        // 携物悬停使用独立颜色，不能与单击开盖的轮廓混淆。
        dropSource = source;
    }

    public void drawDropTarget(GuiGraphicsExtractor context, Font font, int x, int y) {
        // 名称来自服务器容器目录，模型合盖时同样提供自动放置目标提示。
        if (!dropSource.isEmpty() && !hovered.isEmpty() && !dropSource.equals(hovered)) {
            context.text(font, module.container(hovered).getTitle(), x, y + font.lineHeight,
                    InventoryUiClient.runtime().color(ThemeTokens.INVENTORY_DROP_VALID));
        }
    }

    public static void register(EquipmentAppearances appearances) {
        // 通过 Fabric 的渲染层入口使用真实 body 变换，不再把容器单独摆在人物两侧。
        var worldRenderer = new WorldEquipmentRenderer(appearances);
        LivingEntityRenderLayerRegistrationCallback.EVENT.register(
                (type, renderer, helper, context) -> {
                    if (renderer instanceof AvatarRenderer<?> avatarRenderer) {
                        helper.register(new Attachment(avatarRenderer, worldRenderer));
                    }
                });
    }

    public void rotate(double dx, double dy) {
        // 拖动方向按用户习惯反转，水平与垂直轴使用相同约定。
        yaw -= (float) dx * DRAG_DEGREES_PER_PIXEL;
        pitch =
                Math.max(
                        -MAX_PITCH_DEGREES,
                        Math.min(MAX_PITCH_DEGREES, pitch - (float) dy * DRAG_DEGREES_PER_PIXEL));
    }

    public String hovered() {
        return hovered;
    }

    public float[] bounds(String id) {
        var transform = transforms.get(id);
        return transform == null
                ? null
                : assets.get(id).bounds(transform, animations.get(id).sample());
    }

    public boolean hit(String id, double x, double y) {
        return id.equals(pick(x, y));
    }

    public String pick(double x, double y) {
        // 躯干遮挡的背后容器不能穿透选中；旋转人物后即可看到并选择背后。
        double depth = slots.depth(x, y);
        String result = "";
        for (var entry : transforms.entrySet()) {
            double candidate =
                    assets.get(entry.getKey())
                            .hitDepth(
                                    entry.getValue(),
                                    animations.get(entry.getKey()).sample(),
                                    x,
                                    y);
            if (candidate > depth) {
                depth = candidate;
                result = entry.getKey();
            }
        }
        return result;
    }

    public int slotAt(double x, double y) {
        // 身上容器参与同一深度比较，不能隔着背后容器抓取胸甲或背后的手。
        double depth = Double.NEGATIVE_INFINITY;
        for (var entry : transforms.entrySet()) {
            depth =
                    Math.max(
                            depth,
                            assets.get(entry.getKey())
                                    .hitDepth(
                                            entry.getValue(),
                                            animations.get(entry.getKey()).sample(),
                                            x,
                                            y));
        }
        return slots.slotAt(x, y, depth);
    }

    public int handAt(double x, double y) {
        int slot = slotAt(x, y);
        return PlayerSlots.isArmor(slot) ? -1 : slot;
    }

    public float[] handBounds(int slot) {
        return slots.bounds(slot);
    }

    public float[] slotBounds(int slot) {
        return slots.bounds(slot);
    }

    /**
     * @param width 场景宽度，GUI 像素；预览画中画铺满场景，人物和地面背包不会被裁在小框内
     * @param height 场景高度，GUI 像素
     * @param x 人物脚底的场景局部横坐标，GUI 像素
     * @param baseline 人物脚底的场景局部纵坐标，GUI 像素
     * @param scale 每方块对应的 GUI 像素
     */
    public void draw(
            GuiGraphicsExtractor context,
            int width,
            int height,
            int x,
            int baseline,
            int scale,
            int mx,
            int my,
            boolean input,
            Map<String, BagAnimation> animation) {
        // 朝向写在快照上，不再借用并恢复玩家的真实姿态，世界中的玩家不会转身。
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        animations = animation;
        mouseX = mx;
        mouseY = my;
        interactive = input;
        lines = BoxShapes.LineScale.texture(width, height);
        var state = extract(player);
        state.bodyRot = yaw;
        state.yRot = 0;
        state.xRot = 0;
        // 与原版物品栏人物相同：固定按标准体型取景，阴影和发光描边不进入界面。
        state.scale = 1;
        state.shadowPieces.clear();
        state.outlineColor = 0;
        state.setData(PREVIEW, this);
        // 实体画中画不带 GUI 变换，按当前平移换算成屏幕绝对坐标；贴图铺满场景，局部原点即贴图左上角。
        var origin = context.pose().transformPosition(new Vector2f());
        int x0 = Math.round(origin.x);
        int y0 = Math.round(origin.y);
        // 平移在缩放后、旋转前生效，单位为方块；让人物脚底落在 (x, baseline)。
        var translation = new Vector3f((x - width / 2f) / scale, (baseline - height / 2f) / scale, 0);
        var tilt = Axis.XP.rotationDegrees(pitch);
        context.entity(
                state,
                scale,
                translation,
                new Quaternionf().rotateZ((float) Math.PI).mul(tilt),
                tilt,
                x0,
                y0,
                x0 + width,
                y0 + height);
    }

    private AvatarRenderState extract(LocalPlayer player) {
        // 仅在抽取快照期间隐藏待回执部位；快照已复制装备与手持物，finally 恢复同一栈引用，不预测修改客户端库存。
        var pendingStacks = hidePendingStacks(player);
        try {
            return Minecraft.getInstance()
                    .getEntityRenderDispatcher()
                    .getPlayerRenderer(player)
                    .createRenderState(player, 1.0F);
        } finally {
            pendingStacks.forEach((slot, stack) -> player.getInventory().setItem(slot, stack));
        }
    }

    private Map<Integer, ItemStack> hidePendingStacks(LocalPlayer player) {
        Map<Integer, ItemStack> hidden = new LinkedHashMap<>();
        for (var snapshot : module.state().getPlayerSlotsList()) {
            int slot = snapshot.getPlayerSlot();
            if (module.pendingItem(VolumeModule.playerSlotPreviewId(slot))) {
                hidden.put(slot, player.getInventory().getItem(slot));
                player.getInventory().setItem(slot, ItemStack.EMPTY);
            }
        }
        return hidden;
    }

    public Placement wearAt(double x, double y, ContainerItems.Definition type) {
        // 在躯干局部空间判定落点，转动人物后同一规则自然区分前后与腰环角度。
        if (torso == null || type.mounts().isEmpty()) {
            return null;
        }
        double depth = ModelAsset.boxDepth(torso, EQUIP_FROM, EQUIP_TO, x, y);
        if (!Double.isFinite(depth)) {
            return null;
        }
        Vector3f local =
                new Matrix4f(torso.last().pose())
                        .invert()
                        .transformPosition(new Vector3f((float) x, (float) y, (float) depth));
        if (type.waist()) {
            if (local.y < WAIST_MIN_Y) {
                return null;
            }
            float angle =
                    (float)
                            Math.toDegrees(
                                    Math.atan2(
                                            local.x / WornModelTransform.WAIST_X,
                                            -local.z / WornModelTransform.WAIST_Z));
            angle =
                    (angle + ContainerItems.FULL_CIRCLE_DEGREES)
                            % ContainerItems.FULL_CIRCLE_DEGREES;
            return Placement.newBuilder()
                    .setMount(Mount.WAIST_RIGHT_FRONT)
                    .setWaistAngleDegrees(angle)
                    .build();
        }
        Mount mount = local.z < 0 ? Mount.TORSO_FRONT : Mount.TORSO_BACK;
        // 背负件和胸挂可能只允许一侧；预览与服务端使用同一注册挂点约束。
        return type.mounts().contains(mount) ? Placement.newBuilder().setMount(mount).build() : null;
    }

    private void renderPreview(
            PoseStack texture, SubmitNodeCollector collector, AvatarRenderState state, PlayerModel model) {
        // 画中画按贴图像素绘制；命中缓存换算成场景局部 GUI 坐标，与鼠标坐标直接比较。
        float textureScale = Minecraft.getInstance().getWindow().getGuiScale();
        var local = ModelAsset.scaled(texture, 1 / textureScale);
        int selectedSlot = Minecraft.getInstance().player.getInventory().getSelectedSlot();
        slots.capture(local, model, state, selectedSlot);
        slots.renderPending(module, collector, textureScale);
        var body = ModelAsset.copy(local);
        model.body.translateAndRotate(body);
        renderAttachments(body, collector, textureScale);
        slots.renderHover(interactive ? slotAt(mouseX, mouseY) : -1, collector, textureScale, lines);
    }

    private void renderAttachments(PoseStack body, SubmitNodeCollector collector, float textureScale) {
        // 每帧使用当前目录，F3+T 删除注册后不能保留旧模型及其命中区。
        assets.clear();
        transforms.clear();
        torso = ModelAsset.copy(body);
        int groundIndex = 0;
        for (var container : module.state().getContainersList()) {
            if (!container.getWorn() && !container.getGrounded()) {
                continue;
            }
            String id = container.getId();
            var asset = ModelResources.find(container.getModel());
            if (asset == null) {
                continue;
            }
            assets.put(id, asset);
            var type = ContainerItems.definition(module.stack(id));
            boolean pouch = type != null && type.waist();
            var matrices = ModelAsset.copy(body);
            if (container.getGrounded()) {
                // 已授权地面背包绘制在人物旁，独立于开盖窗口提供模型落点。
                groundIndex++;
                matrices.translate(groundIndex * GROUND_MODEL_SPACING_BLOCKS, GROUND_MODEL_Y_BLOCKS, 0);
                matrices.scale(GROUND_MODEL_SCALE, -GROUND_MODEL_SCALE, -GROUND_MODEL_SCALE);
            } else {
                WornModelTransform.apply(
                        matrices, asset, pouch, container.getMount(), container.getWaistAngleDegrees());
            }
            transforms.put(id, matrices);
        }
        renderAttachmentModels(collector, textureScale);
    }

    private void renderAttachmentModels(SubmitNodeCollector collector, float textureScale) {
        // 模型轮廓与半透明来源占位使用同一投影，避免视觉位置和命中位置错开。
        hovered = interactive ? pick(mouseX, mouseY) : "";
        for (var entry : transforms.entrySet()) {
            String id = entry.getKey();
            var matrices = ModelAsset.scaled(entry.getValue(), textureScale);
            var asset = assets.get(id);
            BagAnimation.Sample lid = animations.get(id).sample();
            if (id.equals(hovered)) {
                renderOutline(asset, matrices, collector, lid, textureScale);
            }
            Integer tint = null;
            if (module.pendingItem(id)) {
                tint = InventoryUiClient.runtime().color(ThemeTokens.ACCENT) & RGB_MASK | PENDING_ALPHA;
            }
            asset.render(matrices, collector, lid, tint);
        }
    }

    private void renderOutline(
            ModelAsset asset,
            PoseStack matrices,
            SubmitNodeCollector collector,
            BagAnimation.Sample lid,
            float textureScale) {
        // 先绘制向周围偏移一像素的着色模型，再由原模型覆盖中心，留下边缘；偏移按 GUI 像素换算成贴图像素。
        int tint = InventoryUiClient.runtime().color(ThemeTokens.ACCENT);
        if (!dropSource.isEmpty() && !dropSource.equals(hovered)) {
            tint = InventoryUiClient.runtime().color(ThemeTokens.INVENTORY_DROP_VALID);
        }
        float offset = OUTLINE_PIXELS * textureScale;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                var outline = ModelAsset.copy(matrices);
                var position = outline.last().pose();
                position.m30(position.m30() + dx * offset);
                position.m31(position.m31() + dy * offset);
                position.m32(position.m32() - OUTLINE_DEPTH * textureScale);
                asset.render(outline, collector, lid, tint);
            }
        }
    }

    private static final class Attachment extends RenderLayer<AvatarRenderState, PlayerModel> {
        private final WorldEquipmentRenderer worldRenderer;

        Attachment(RenderLayerParent<AvatarRenderState, PlayerModel> context, WorldEquipmentRenderer worldRenderer) {
            super(context);
            this.worldRenderer = worldRenderer;
        }

        @Override
        public void submit(
                PoseStack matrices,
                SubmitNodeCollector collector,
                int light,
                AvatarRenderState state,
                float yRot,
                float xRot) {
            // UI 专用动画和世界外观互斥，同一玩家不会被重复绘制两套背包。
            WornPreview preview = state.getData(PREVIEW);
            if (preview != null) {
                preview.renderPreview(matrices, collector, state, getParentModel());
                return;
            }
            var body = ModelAsset.copy(matrices);
            getParentModel().body.translateAndRotate(body);
            worldRenderer.render(state, body, collector, light);
        }
    }
}
