package dev.kizuna.backpack3d.smoke;

import dev.kizuna.backpack3d.client.VolumeModule;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.EquipmentAppearance;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornModel;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Pose;

/** 不激活 WornPreview，直接验证玩家 RenderLayer 的世界分支和远端权威外观；对拍经 FrameCapture 逐帧截图。 */
final class WorldEquipmentProbe extends Screen {
    private static final UUID SUBJECT = UUID.fromString("f270cae7-a374-4d87-8531-d049efaf90bc");
    private final VolumeModule module;
    private int step;
    private final FrameCapture capture = new FrameCapture(Minecraft.getInstance());
    private final Deque<Consumer<GuiGraphicsExtractor>> shots = new ArrayDeque<>();
    private int subjectEntityId;
    private boolean verified;
    private boolean done;

    WorldEquipmentProbe(VolumeModule module) {
        super(Component.literal("World equipment probe"));
        this.module = module;
    }

    @Override
    protected void init() {
        command(0);
    }

    boolean done() {
        return done;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (done || minecraft.level == null || !capture.ready()) {
            return;
        }
        if (!shots.isEmpty()) {
            // 对拍逐帧进行：每帧画一张，截图回调到达后才画下一张。
            shots.poll().accept(context);
            return;
        }
        var player = (AbstractClientPlayer) minecraft.level.getPlayerByUUID(SUBJECT);
        if (player != null) {
            subjectEntityId = player.getId();
        }
        String dimension = minecraft.level.dimension().identifier().toString();
        List<WornModel> models = module.appearances().models(SUBJECT, subjectEntityId, dimension);
        switch (step) {
            case 0 -> {
                if (verified) {
                    command(1);
                    return;
                }
                if (player == null || models.size() != 2) {
                    return;
                }
                queueRendered(player, models, Pose.STANDING);
                queueRendered(player, models, Pose.CROUCHING);
                var self = minecraft.player;
                queueRendered(
                        self,
                        module.appearances().models(self.getUUID(), self.getId(), dimension),
                        Pose.STANDING);
                verified = true;
            }
            case 1 -> {
                if (models.stream().anyMatch(model -> model.getMount() == Mount.TORSO_FRONT)
                        && models.stream().anyMatch(model -> model.getWaistAngleDegrees() == 135)) {
                    command(2);
                }
            }
            case 2 -> {
                if (models.isEmpty()) {
                    command(3);
                }
            }
            case 3 -> {
                if (models.size() == 2) {
                    command(4);
                }
            }
            case 4 -> {
                if (player == null && models.isEmpty()) {
                    command(5);
                }
            }
            case 5 -> {
                if (player != null && models.size() == 2) {
                    command(6);
                }
            }
            case 6 -> {
                if (player == null && models.isEmpty()) {
                    done = true;
                    System.out.println(
                            "BS3D_SMOKE world-equipment: self/remote render, crouch, remount,"
                                + " unequip, untrack/retrack, remove passed");
                }
            }
            default -> throw new IllegalStateException("未知外观验证步骤");
        }
    }

    private void queueRendered(AbstractClientPlayer player, List<WornModel> models, Pose pose) {
        // 同一位置前后对拍，差别只能来自外观缓存；站姿与蹲姿都走真实骨架。
        var snapshot =
                EquipmentAppearance.newBuilder()
                        .setPlayerUuid(player.getStringUUID())
                        .setEntityId(player.getId())
                        .setDimension(player.level().dimension().identifier().toString())
                        .addAllModels(models)
                        .build();
        var bare = snapshot.toBuilder().clearModels().build();
        String name = player.getName().getString();
        int[][] equipped = new int[1][];
        shots.add(
                context -> {
                    module.appearances().accept(snapshot);
                    drawPlayer(context, player, pose);
                    capture.expect(image -> equipped[0] = image.getPixels());
                });
        shots.add(
                context -> {
                    // 外观在提交阶段才读取，缓存须保持到本帧绘制完成，下一张再恢复。
                    module.appearances().accept(bare);
                    drawPlayer(context, player, pose);
                    capture.expect(
                            image -> {
                                int[] pixels = image.getPixels();
                                int changed = 0;
                                for (int index = 0; index < pixels.length; index++) {
                                    if (pixels[index] != equipped[0][index]) {
                                        changed++;
                                    }
                                }
                                if (changed < 500) {
                                    throw new IllegalStateException(
                                            "世界玩家装备没有绘制："
                                                    + name
                                                    + ", pose="
                                                    + pose
                                                    + ", pixels="
                                                    + changed);
                                }
                            });
                });
        shots.add(
                context -> {
                    module.appearances().accept(snapshot);
                    drawPlayer(context, player, pose);
                    capture.expect(
                            image -> {
                                // 与原版截图同目录，供人工复核站姿、蹲姿与自身外观。
                                var directory = minecraft.gameDirectory.toPath().resolve("screenshots");
                                try {
                                    Files.createDirectories(directory);
                                    image.writeToFile(
                                            directory.resolve("world-equipment-" + name + "-" + pose + ".png"));
                                } catch (IOException failure) {
                                    throw new UncheckedIOException(failure);
                                }
                            });
                });
    }

    private void drawPlayer(GuiGraphicsExtractor context, AbstractClientPlayer player, Pose pose) {
        // 背景铺满后以整屏为画中画区域；姿态与朝向只在提取快照时临时改写，随即恢复实体。
        // 26.2 游戏内界面背后是模糊世界，主题背景令牌默认透明，须用不透明的预览底色盖住。
        context.fill(
                0, 0, width, height, InventoryUiClient.runtime().color(ThemeTokens.PREVIEW_BACKGROUND));
        Pose previous = player.getPose();
        float oldBodyYaw = player.yBodyRot;
        float oldHeadYaw = player.yHeadRot;
        float oldPreviousHeadYaw = player.yHeadRotO;
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        EntityRenderState state;
        try {
            player.setPose(pose);
            player.yBodyRot = 145;
            player.yHeadRot = 145;
            player.yHeadRotO = 145;
            player.setYRot(145);
            player.setXRot(0);
            state = minecraft.getEntityRenderDispatcher().getRenderer(player).createRenderState(player, 1.0F);
        } finally {
            player.setPose(previous);
            player.yBodyRot = oldBodyYaw;
            player.yHeadRot = oldHeadYaw;
            player.yHeadRotO = oldPreviousHeadYaw;
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
        state.shadowPieces.clear();
        state.outlineColor = 0;
        // 与旧版 renderEntityInInventory(width / 2, height * 4 / 5, height / 3) 同位：脚底在 4/5 高处，每格 height / 3 像素。
        float scale = height / 3f;
        var translation = new Vector3f(0, (height * 4 / 5 - height / 2f) / scale, 0);
        context.entity(
                state,
                scale,
                translation,
                new Quaternionf().rotateZ((float) Math.PI),
                null,
                0,
                0,
                width,
                height);
    }

    private void command(int next) {
        // 本地标记仅驱动 smoke 假玩家；生产协议没有这个控制入口。
        try {
            Files.writeString(
                    minecraft.gameDirectory.toPath().resolve("../world-appearance-step.txt"),
                    Integer.toString(next));
            step = next;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
