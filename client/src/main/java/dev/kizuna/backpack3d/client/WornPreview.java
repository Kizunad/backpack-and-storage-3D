package dev.kizuna.backpack3d.client;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.PlayerSlots;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Placement;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;

import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;

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
    private static WornPreview drawing;
    private final VolumeModule module;
    private final Map<String, ModelAsset> assets = new LinkedHashMap<>();
    private final Map<String, MatrixStack> transforms = new LinkedHashMap<>();
    private final PlayerSlotTargets slots = new PlayerSlotTargets();
    private Map<String, BagAnimation> animations;
    private float yaw = INITIAL_YAW_DEGREES;
    private float pitch;
    private int mouseX;
    private int mouseY;
    private boolean interactive;
    private String hovered = "";
    private MatrixStack torso;
    // 装备拖放体积单位为方块，略超出躯干，方便落在腰侧和胸背表面。
    private static final Vector3f EQUIP_FROM = new Vector3f(-0.5f, 0, -0.4f);
    private static final Vector3f EQUIP_TO = new Vector3f(0.5f, 0.85f, 0.4f);
    private static final float WAIST_MIN_Y = 0.45f;

    public WornPreview(VolumeModule module) {
        this.module = module;
    }

    public static void register(EquipmentAppearances appearances) {
        // 通过 Fabric 的模型特征入口使用真实 body 变换，不再把容器单独摆在人物两侧。
        var worldRenderer = new WorldEquipmentRenderer(appearances);
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register(
                (type, renderer, helper, context) -> {
                    if (renderer instanceof PlayerEntityRenderer playerRenderer) {
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
            DrawContext context,
            int x,
            int baseline,
            int scale,
            int mx,
            int my,
            boolean input,
            Map<String, BagAnimation> animation) {
        var player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        // 暂存并恢复朝向，拖动预览不会让世界中的玩家转身或改变下一次网络输入。
        float oldBody = player.bodyYaw;
        float oldHead = player.headYaw;
        float oldPreviousHead = player.prevHeadYaw;
        float oldYaw = player.getYaw();
        float oldPitch = player.getPitch();
        animations = animation;
        mouseX = mx;
        mouseY = my;
        interactive = input;
        hovered = "";
        transforms.clear();
        drawing = this;
        // 放大的模型可能越过 GUI 的 z=0 平面；清掉此前界面深度，不能靠关闭深度测试绘制。
        GuiSceneDepth.clear(context);
        try {
            player.bodyYaw = yaw;
            player.headYaw = yaw;
            player.prevHeadYaw = yaw;
            player.setYaw(yaw);
            player.setPitch(0);
            var tilt = RotationAxis.POSITIVE_X.rotationDegrees(pitch);
            InventoryScreen.drawEntity(
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
            player.bodyYaw = oldBody;
            player.headYaw = oldHead;
            player.prevHeadYaw = oldPreviousHead;
            player.setYaw(oldYaw);
            player.setPitch(oldPitch);
        }
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
                new Matrix4f(torso.peek().getPositionMatrix())
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

    private void renderAttachments(MatrixStack body, VertexConsumerProvider buffers) {
        // 每帧使用当前目录，F3+T 删除注册后不能保留旧模型及其命中区。
        assets.clear();
        torso = ModelAsset.copy(body);
        for (var container : module.state().getContainersList()) {
            if (!container.getWorn()) {
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
            WornModelTransform.apply(
                    matrices, asset, pouch, container.getMount(), container.getWaistAngleDegrees());
            transforms.put(id, ModelAsset.copy(matrices));
        }
        hovered = interactive ? pick(mouseX, mouseY) : "";
        for (var entry : transforms.entrySet()) {
            String id = entry.getKey();
            var matrices = entry.getValue();
            var asset = assets.get(id);
            BagAnimation.Sample lid = animations.get(id).sample();
            if (id.equals(hovered)) {
                renderOutline(asset, matrices, buffers, lid);
            }
            asset.render(matrices, buffers, lid, null);
        }
    }

    private void renderOutline(
            ModelAsset asset,
            MatrixStack matrices,
            VertexConsumerProvider buffers,
            BagAnimation.Sample lid) {
        // 先绘制向周围偏移一像素的着色模型，再由原模型覆盖中心，留下边缘。
        int tint = InventoryUiClient.runtime().color(ThemeTokens.ACCENT);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                var outline = ModelAsset.copy(matrices);
                var position = outline.peek().getPositionMatrix();
                position.m30(position.m30() + dx * OUTLINE_PIXELS);
                position.m31(position.m31() + dy * OUTLINE_PIXELS);
                position.m32(position.m32() - OUTLINE_DEPTH);
                asset.render(outline, buffers, lid, tint);
            }
        }
    }

    private static final class Attachment
            extends FeatureRenderer<
                    AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> {
        private final WorldEquipmentRenderer worldRenderer;

        Attachment(
                FeatureRendererContext<
                                AbstractClientPlayerEntity,
                                PlayerEntityModel<AbstractClientPlayerEntity>>
                        context,
                WorldEquipmentRenderer worldRenderer) {
            super(context);
            this.worldRenderer = worldRenderer;
        }

        @Override
        public void render(
                MatrixStack matrices,
                VertexConsumerProvider buffers,
                int light,
                AbstractClientPlayerEntity player,
                float limbAngle,
                float limbDistance,
                float tickDelta,
                float progress,
                float headYaw,
                float headPitch) {
            // UI 专用动画和世界外观互斥，同一玩家不会被重复绘制两套背包。
            if (drawing != null && player == MinecraftClient.getInstance().player) {
                drawing.slots.capture(matrices, getContextModel(), player);
                matrices.push();
                try {
                    getContextModel().body.rotate(matrices);
                    drawing.renderAttachments(matrices, buffers);
                } finally {
                    matrices.pop();
                }
                drawing.slots.renderHover(
                        drawing.interactive ? drawing.slotAt(drawing.mouseX, drawing.mouseY) : -1,
                        buffers);
            } else {
                matrices.push();
                try {
                    getContextModel().body.rotate(matrices);
                    worldRenderer.render(player, matrices, buffers, light);
                } finally {
                    matrices.pop();
                }
            }
        }
    }
}
