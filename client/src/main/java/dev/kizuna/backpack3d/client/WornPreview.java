package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.PlayerSlots;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Placement;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;

import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.Map;

/** 与世界外观共用躯干挂点；UI 绘制期间额外启用开盖动画、轮廓与精确命中。 */
public final class WornPreview {
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
    private static WornPreview drawing;
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

    public void drawDropTarget(GuiGraphics context, Font font, int x, int y) {
        // 名称来自服务器容器目录，模型合盖时同样提供自动放置目标提示。
        if (!dropSource.isEmpty() && !hovered.isEmpty() && !dropSource.equals(hovered)) {
            context.drawString(font, module.container(hovered).getTitle(), x, y + font.lineHeight,
                    InventoryUiClient.runtime().color(ThemeTokens.INVENTORY_DROP_VALID));
        }
    }

    public static void register(EquipmentAppearances appearances) {
        // 通过 Fabric 的模型特征入口使用真实 body 变换，不再把容器单独摆在人物两侧。
        var worldRenderer = new WorldEquipmentRenderer(appearances);
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register(
                (type, renderer, helper, context) -> {
                    if (renderer instanceof PlayerRenderer playerRenderer) {
                        helper.register(new Attachment(playerRenderer, worldRenderer));
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
        // 身上容器参与同一深度比较，不能隔着背篓抓取胸甲或背后的手。
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

    public void draw(
            GuiGraphics context,
            int x,
            int baseline,
            int scale,
            int mx,
            int my,
            boolean input,
            Map<String, BagAnimation> animation) {
        // 渲染只借用玩家姿态，结束后恢复真实朝向与暂时隐藏的栈。
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        // 暂存并恢复朝向，拖动预览不会让世界中的玩家转身或改变下一次网络输入。
        var previousPose = new PlayerPose(player.yBodyRot, player.yHeadRot, player.yHeadRotO,
                player.getYRot(), player.getXRot());
        animations = animation;
        mouseX = mx;
        mouseY = my;
        interactive = input;
        hovered = "";
        transforms.clear();
        drawing = this;
        // 放大的模型可能越过 GUI 的 z=0 平面；清掉此前界面深度，不能靠关闭深度测试绘制。
        GuiSceneDepth.clear(context);
        var pendingStacks = hidePendingStacks(player);
        try {
            player.yBodyRot = yaw;
            player.yHeadRot = yaw;
            player.yHeadRotO = yaw;
            player.setYRot(yaw);
            player.setXRot(0);
            var tilt = Axis.XP.rotationDegrees(pitch);
            InventoryScreen.renderEntityInInventory(
                    context,
                    x,
                    baseline,
                    scale,
                    new Quaternionf().rotateZ((float) Math.PI).mul(tilt),
                    new Quaternionf(tilt),
                    player);
        } finally {
            // 模型的深度也不能挡住后续窗口、按钮和帮助面板。
            GuiSceneDepth.clear(context);
            drawing = null;
            previousPose.restore(player);
            pendingStacks.forEach((slot, stack) -> player.getInventory().setItem(slot, stack));
        }
    }

    private record PlayerPose(float bodyYaw, float headYaw, float previousHeadYaw, float yaw, float pitch) {
        void restore(AbstractClientPlayer player) {
            // 展示期间借用的姿态恢复到原值，不影响下一次玩家输入。
            player.yBodyRot = bodyYaw;
            player.yHeadRot = headYaw;
            player.yHeadRotO = previousHeadYaw;
            player.setYRot(yaw);
            player.setXRot(pitch);
        }
    }

    private Map<Integer, ItemStack> hidePendingStacks(AbstractClientPlayer player) {
        // 仅绘制期间隐藏待回执部位，finally 恢复同一栈引用，不预测修改客户端库存。
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

    private void renderAttachments(PoseStack body, MultiBufferSource buffers) {
        // 每帧使用当前目录，F3+T 删除注册后不能保留旧模型及其命中区。
        assets.clear();
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
            transforms.put(id, ModelAsset.copy(matrices));
        }
        renderAttachmentModels(buffers);
    }

    private void renderAttachmentModels(MultiBufferSource buffers) {
        // 模型轮廓与半透明来源占位使用同一投影，避免视觉位置和命中位置错开。
        hovered = interactive ? pick(mouseX, mouseY) : "";
        for (var entry : transforms.entrySet()) {
            String id = entry.getKey();
            var matrices = entry.getValue();
            var asset = assets.get(id);
            BagAnimation.Sample lid = animations.get(id).sample();
            if (id.equals(hovered)) {
                renderOutline(asset, matrices, buffers, lid);
            }
            Integer tint = null;
            if (module.pendingItem(id)) {
                tint = InventoryUiClient.runtime().color(ThemeTokens.ACCENT) & RGB_MASK | PENDING_ALPHA;
            }
            asset.render(matrices, buffers, lid, tint);
        }
    }

    private void renderOutline(
            ModelAsset asset,
            PoseStack matrices,
            MultiBufferSource buffers,
            BagAnimation.Sample lid) {
        // 先绘制向周围偏移一像素的着色模型，再由原模型覆盖中心，留下边缘。
        int tint = InventoryUiClient.runtime().color(ThemeTokens.ACCENT);
        if (!dropSource.isEmpty() && !dropSource.equals(hovered)) {
            tint = InventoryUiClient.runtime().color(ThemeTokens.INVENTORY_DROP_VALID);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                var outline = ModelAsset.copy(matrices);
                var position = outline.last().pose();
                position.m30(position.m30() + dx * OUTLINE_PIXELS);
                position.m31(position.m31() + dy * OUTLINE_PIXELS);
                position.m32(position.m32() - OUTLINE_DEPTH);
                asset.render(outline, buffers, lid, tint);
            }
        }
    }

    private static final class Attachment
            extends RenderLayer<
                    AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        private final WorldEquipmentRenderer worldRenderer;

        Attachment(
                RenderLayerParent<
                                AbstractClientPlayer,
                                PlayerModel<AbstractClientPlayer>>
                        context,
                WorldEquipmentRenderer worldRenderer) {
            super(context);
            this.worldRenderer = worldRenderer;
        }

        @Override
        public void render(
                PoseStack matrices,
                MultiBufferSource buffers,
                int light,
                AbstractClientPlayer player,
                float limbAngle,
                float limbDistance,
                float tickDelta,
                float progress,
                float headYaw,
                float headPitch) {
            // UI 专用动画和世界外观互斥，同一玩家不会被重复绘制两套背包。
            if (drawing != null && player == Minecraft.getInstance().player) {
                drawing.slots.capture(matrices, getParentModel(), player);
                drawing.slots.renderPending(drawing.module, buffers);
                matrices.pushPose();
                try {
                    getParentModel().body.translateAndRotate(matrices);
                    drawing.renderAttachments(matrices, buffers);
                } finally {
                    matrices.popPose();
                }
                drawing.slots.renderHover(
                        drawing.interactive ? drawing.slotAt(drawing.mouseX, drawing.mouseY) : -1,
                        buffers);
            } else {
                matrices.pushPose();
                try {
                    getParentModel().body.translateAndRotate(matrices);
                    worldRenderer.render(player, matrices, buffers, light);
                } finally {
                    matrices.popPose();
                }
            }
        }
    }
}
