package dev.kizuna.backpack3d.smoke;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.kizuna.backpack3d.client.VolumeModule;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.EquipmentAppearance;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornModel;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EntityPose;
import net.minecraft.text.Text;

import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/** 不激活 WornPreview，直接验证玩家 FeatureRenderer 的世界分支和远端权威外观。 */
final class WorldEquipmentProbe extends Screen {
    private static final UUID SUBJECT = UUID.fromString("f270cae7-a374-4d87-8531-d049efaf90bc");
    private final VolumeModule module;
    private int step;
    private int subjectEntityId;
    private boolean done;

    WorldEquipmentProbe(VolumeModule module) {
        super(Text.literal("World equipment probe"));
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
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (done || client.world == null) {
            return;
        }
        var player = (AbstractClientPlayerEntity) client.world.getPlayerByUuid(SUBJECT);
        if (player != null) {
            subjectEntityId = player.getId();
        }
        String dimension = client.world.getRegistryKey().getValue().toString();
        List<WornModel> models = module.appearances().models(SUBJECT, subjectEntityId, dimension);
        switch (step) {
            case 0 -> {
                if (player == null || models.size() != 2) {
                    return;
                }
                verifyRendered(context, player, models, EntityPose.STANDING);
                verifyRendered(context, player, models, EntityPose.CROUCHING);
                var self = client.player;
                verifyRendered(
                        context,
                        self,
                        module.appearances().models(self.getUuid(), self.getId(), dimension),
                        EntityPose.STANDING);
                command(1);
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

    private void verifyRendered(
            DrawContext context,
            AbstractClientPlayerEntity player,
            List<WornModel> models,
            EntityPose pose) {
        var snapshot =
                EquipmentAppearance.newBuilder()
                        .setPlayerUuid(player.getUuidAsString())
                        .setEntityId(player.getId())
                        .setDimension(player.getWorld().getRegistryKey().getValue().toString())
                        .addAllModels(models)
                        .build();
        EntityPose previous = player.getPose();
        float oldBodyYaw = player.bodyYaw;
        float oldHeadYaw = player.headYaw;
        float oldPreviousHeadYaw = player.prevHeadYaw;
        float oldYaw = player.getYaw();
        float oldPitch = player.getPitch();
        try {
            // 同一位置前后对拍，差别只能来自外观缓存；站姿与蹲姿都走真实骨架。
            player.setPose(pose);
            player.bodyYaw = 145;
            player.headYaw = 145;
            player.prevHeadYaw = 145;
            player.setYaw(145);
            player.setPitch(0);
            drawPlayer(context, player);
            try (var equipped = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
                module.appearances().accept(snapshot.toBuilder().clearModels().build());
                drawPlayer(context, player);
                try (var bare = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
                    int changed = 0;
                    for (int y = 0; y < bare.getHeight(); y++) {
                        for (int x = 0; x < bare.getWidth(); x++) {
                            if (bare.getColor(x, y) != equipped.getColor(x, y)) {
                                changed++;
                            }
                        }
                    }
                    if (changed < 500) {
                        throw new IllegalStateException(
                                "世界玩家装备没有绘制："
                                        + player.getName().getString()
                                        + ", pose="
                                        + pose
                                        + ", pixels="
                                        + changed);
                    }
                }
            }
            module.appearances().accept(snapshot);
            drawPlayer(context, player);
            ScreenshotRecorder.saveScreenshot(
                    client.runDirectory,
                    "world-equipment-" + player.getName().getString() + "-" + pose + ".png",
                    client.getFramebuffer(),
                    text -> {});
        } finally {
            module.appearances().accept(snapshot);
            player.setPose(previous);
            player.bodyYaw = oldBodyYaw;
            player.headYaw = oldHeadYaw;
            player.prevHeadYaw = oldPreviousHeadYaw;
            player.setYaw(oldYaw);
            player.setPitch(oldPitch);
        }
    }

    private void drawPlayer(DrawContext context, AbstractClientPlayerEntity player) {
        // 每次独立对拍都先清掉前一模型的深度，背景和实体分别提交。
        context.draw();
        RenderSystem.depthMask(true);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
        context.fill(
                0, 0, width, height, InventoryUiClient.runtime().color(ThemeTokens.BACKGROUND));
        context.draw();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
        InventoryScreen.drawEntity(
                context,
                width / 2,
                height * 4 / 5,
                height / 3,
                new Quaternionf().rotateZ((float) Math.PI),
                null,
                player);
        context.draw();
    }

    private void command(int next) {
        // 本地标记仅驱动 smoke 假玩家；生产协议没有这个控制入口。
        try {
            Files.writeString(
                    client.runDirectory.toPath().resolve("../world-appearance-step.txt"),
                    Integer.toString(next));
            step = next;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
