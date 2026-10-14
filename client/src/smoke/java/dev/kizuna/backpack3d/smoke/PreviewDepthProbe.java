package dev.kizuna.backpack3d.smoke;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.kizuna.backpack3d.client.BagAnimation;
import dev.kizuna.backpack3d.client.InteriorRenderer;
import dev.kizuna.backpack3d.client.VolumeModule;
import dev.kizuna.backpack3d.client.WornPreview;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;

import org.lwjgl.opengl.GL11;

import java.util.LinkedHashMap;
import java.util.Map;

/** 在真实 GL 环境对照干净与被遮挡的深度缓存，验证预览不会被界面残留深度吞掉。 */
final class PreviewDepthProbe extends Screen {
    private final VolumeModule module;
    private final WornPreview reference;
    private final WornPreview obstructed;
    private final Map<String, BagAnimation> animations = new LinkedHashMap<>();
    private int pose;
    private boolean done;

    PreviewDepthProbe(VolumeModule module) {
        super(Text.literal("Preview depth regression"));
        this.module = module;
        reference = new WornPreview(module);
        obstructed = new WornPreview(module);
        for (var container : module.state().getContainersList()) {
            animations.put(container.getId(), new BagAnimation());
        }
    }

    boolean done() {
        return done;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (done) {
            return;
        }
        int background = InventoryUiClient.runtime().color(ThemeTokens.BACKGROUND);
        drawProbe(context, reference, background, false);
        try (var clean = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
            drawProbe(context, obstructed, background, true);
            try (var covered = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
                int backgroundPixel = clean.getColor(0, 0);
                int modelPixels = 0;
                int differences = 0;
                for (int y = 0; y < clean.getHeight(); y++) {
                    for (int x = 0; x < clean.getWidth(); x++) {
                        int pixel = clean.getColor(x, y);
                        if (pixel != backgroundPixel) {
                            modelPixels++;
                        }
                        if (pixel != covered.getColor(x, y)) {
                            differences++;
                        }
                    }
                }
                if (modelPixels < 1000 || differences > 20) {
                    ScreenshotRecorder.saveScreenshot(
                            client.runDirectory,
                            "depth-probe-failed.png",
                            client.getFramebuffer(),
                            message -> {});
                    throw new IllegalStateException(
                            "模型深度隔离失败：pose="
                                    + pose
                                    + ", modelPixels="
                                    + modelPixels
                                    + ", differences="
                                    + differences);
                }
            }
        }
        // 覆盖正面、转侧面及大俯仰角，不能只验证一个恰好没有穿过底板的姿态。
        reference.rotate(110, pose == 0 ? 60 : -100);
        obstructed.rotate(110, pose == 0 ? 60 : -100);
        pose++;
        done = pose == 3;
        if (done) {
            System.out.println("BS3D_SMOKE preview-depth: 3 poses matched");
            verifyInteriorPlacement(context);
            verifySlicedPicking(context);
        }
    }

    private void drawProbe(
            DrawContext context, WornPreview preview, int background, boolean obstructDepth) {
        // 两次使用完全相同的屏幕坐标，避免盔甲纹理在不同投影平移下的浮点采样差异。
        context.fill(0, 0, width, height, background);
        context.draw();
        RenderSystem.depthMask(true);
        RenderSystem.clearDepth(obstructDepth ? 0 : 1);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
        RenderSystem.clearDepth(1);
        preview.draw(context, width / 2, height * 4 / 5, height / 3, -1, -1, false, animations);
        context.draw();
    }

    private void verifyInteriorPlacement(DrawContext context) {
        // 仅中央格为空时，必须能够从完整三维场景命中它，不能依赖已移除的层按钮。
        var interior = new InteriorRenderer();
        var bounds = new InteriorRenderer.Rect(0, 0, 180, 180);
        interior.draw(
                context, module, Volumes.container(Volumes.BASKET), bounds, -1, -1, "", null, 0);
        var center = Volumes.cell(1, 1, 1);
        var occupied = new java.util.ArrayList<Item>();
        for (int y = 0; y < 3; y++) {
            for (int z = 0; z < 3; z++) {
                for (int x = 0; x < 3; x++) {
                    var cell = Volumes.cell(x, y, z);
                    if (!cell.equals(center)) {
                        occupied.add(
                                Item.newBuilder()
                                        .setId("occupied-" + x + y + z)
                                        .setContainer(Volumes.BASKET)
                                        .setCell(cell)
                                        .setSize(Volumes.UNIT)
                                        .build());
                    }
                }
            }
        }
        var preview = Item.newBuilder().setId("preview").setSize(Volumes.UNIT).build();
        for (int y = 0; y < bounds.height(); y += 2) {
            for (int x = 0; x < bounds.width(); x += 2) {
                if (center.equals(
                        interior.placementAt(
                                occupied,
                                Volumes.BASKET,
                                Volumes.size(3, 3, 3),
                                preview,
                                0,
                                x,
                                y))) {
                    System.out.println("BS3D_SMOKE interior-placement: enclosed center reachable");
                    return;
                }
            }
        }
        throw new IllegalStateException("三维内景中央空格无法放置");
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void verifySlicedPicking(DrawContext context) {
        // 旋转前后只允许命中中间层，同时验证截面仍保留足够的可操作投影。
        var interior = new InteriorRenderer();
        interior.layers().resize(3);
        interior.layers().moveLower(1);
        interior.layers().moveUpper(2);
        var bounds = new InteriorRenderer.Rect(0, 0, 180, 180);
        for (int pose = 0; pose < 3; pose++) {
            interior.draw(
                    context,
                    module,
                    Volumes.container(Volumes.BASKET),
                    bounds,
                    -1,
                    -1,
                    "",
                    null,
                    0);
            int hits = 0;
            for (int y = 0; y < bounds.height(); y += 2) {
                for (int x = 0; x < bounds.width(); x += 2) {
                    var cell = interior.cellAt(x, y);
                    if (cell == null) {
                        continue;
                    }
                    if (cell.getY() != 1) {
                        throw new IllegalStateException("旋转后的切片命中了隐藏格层");
                    }
                    hits++;
                }
            }
            if (hits < 100) {
                throw new IllegalStateException("中间层的可用投影消失");
            }
            interior.rotate(97, -15);
        }
        System.out.println("BS3D_SMOKE slice-picking: 3 rotated poses use only middle layer");
    }
}
