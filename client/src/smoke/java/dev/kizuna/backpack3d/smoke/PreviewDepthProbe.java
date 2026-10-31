package dev.kizuna.backpack3d.smoke;

import com.mojang.blaze3d.platform.NativeImage;
import dev.kizuna.backpack3d.client.BagAnimation;
import dev.kizuna.backpack3d.client.InteriorRenderer;
import dev.kizuna.backpack3d.client.VolumeModule;
import dev.kizuna.backpack3d.client.WornPreview;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 在真实渲染环境对照干净与先画了遮挡面板的画面，验证预览不会被界面残留吞掉。
 *
 * <p>26.2 预览在独立画中画纹理和深度缓冲里绘制，旧版预填深度缓存的对照不再适用；
 * 现在检查的是界面层叠：同一帧先画的不透明面板必须留在预览下面。
 */
final class PreviewDepthProbe extends Screen {
    private final VolumeModule module;
    private final WornPreview reference;
    private final WornPreview obstructed;
    private final Map<String, BagAnimation> animations = new LinkedHashMap<>();
    private final FrameCapture capture = new FrameCapture(Minecraft.getInstance());
    private int pose;
    private boolean done;

    PreviewDepthProbe(VolumeModule module) {
        super(Component.literal("Preview depth regression"));
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
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (done || !capture.ready()) {
            return;
        }
        if (pose == 3) {
            // 三维内景命中在提取阶段同步计算，三种姿态比对完后的下一帧直接校验。
            verifyInteriorPlacement(context);
            verifySlicedPicking(context);
            done = true;
            return;
        }
        // 26.2 游戏内界面背后是模糊世界；用不透明的预览底色铺满，左上角像素才能代表背景。
        int background = InventoryUiClient.runtime().color(ThemeTokens.PREVIEW_BACKGROUND);
        context.fill(0, 0, width, height, background);
        // 干净与遮挡两份预览在同一帧左右并排：人物待机摆臂随游戏刻变化，分帧绘制无法逐像素对照。
        // 两份使用相同的局部尺寸与坐标，只整体平移半屏，避免盔甲纹理在不同投影平移下的浮点采样差异。
        int half = width / 2;
        drawProbe(context, reference, half, false);
        context.pose().pushMatrix();
        context.pose().translate(half, 0);
        drawProbe(context, obstructed, half, true);
        context.pose().popMatrix();
        int offset = half * Minecraft.getInstance().getWindow().getGuiScale();
        capture.expect(image -> compare(image, offset));
    }

    private void compare(NativeImage image, int offset) {
        // 只比较左侧干净画面里属于模型的像素：先画的面板只能露在模型以外，不能盖住预览。
        save(image);
        int[] pixels = image.getPixels();
        int imageWidth = image.getWidth();
        int backgroundPixel = pixels[0];
        int modelPixels = 0;
        int differences = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < offset; x++) {
                int clean = pixels[y * imageWidth + x];
                if (clean == backgroundPixel) {
                    continue;
                }
                modelPixels++;
                if (clean != pixels[y * imageWidth + x + offset]) {
                    differences++;
                }
            }
        }
        if (modelPixels < 1000 || differences > 20) {
            throw new IllegalStateException(
                    "预览被先画的界面遮挡：pose="
                            + pose
                            + ", modelPixels="
                            + modelPixels
                            + ", differences="
                            + differences);
        }
        // 覆盖正面、转侧面及大俯仰角，不能只验证一个恰好没有穿过底板的姿态。
        reference.rotate(110, pose == 0 ? 60 : -100);
        obstructed.rotate(110, pose == 0 ? 60 : -100);
        pose++;
        if (pose == 3) {
            System.out.println("BS3D_SMOKE preview-layering: 3 poses unobstructed");
        }
    }

    private void save(NativeImage image) {
        // 与原版截图同目录，供人工复核左侧干净、右侧遮挡的并排画面。
        var directory = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
        try {
            Files.createDirectories(directory);
            image.writeToFile(directory.resolve("preview-layering-" + pose + ".png"));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private void drawProbe(
            GuiGraphicsExtractor context, WornPreview preview, int areaWidth, boolean obstruct) {
        if (obstruct) {
            // 先画一块覆盖人物中部的不透明面板；画中画须按提交顺序叠在它上面。
            int accent = InventoryUiClient.runtime().color(ThemeTokens.ACCENT);
            context.fill(areaWidth / 3, height / 4, areaWidth * 2 / 3, height * 3 / 4, 0xFF000000 | accent);
        }
        preview.draw(
                context, areaWidth, height, areaWidth / 2, height * 4 / 5, height / 3, -1, -1, false, animations);
    }

    private void verifyInteriorPlacement(GuiGraphicsExtractor context) {
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
    public boolean isPauseScreen() {
        return false;
    }

    private void verifySlicedPicking(GuiGraphicsExtractor context) {
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
