package dev.kizuna.backpack3d.smoke;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.kizuna.backpack3d.client.VolumeModule;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.EquipmentAppearance;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornModel;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Pose;

/** 不激活 WornPreview，直接验证玩家 RenderLayer 的世界分支和远端权威外观。 */
final class WorldEquipmentProbe extends Screen {
    private static final UUID SUBJECT = UUID.fromString("f270cae7-a374-4d87-8531-d049efaf90bc");
    private final VolumeModule module;
    private int step;
    private int subjectEntityId;
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
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        if (done || minecraft.level == null) {
            return;
        }
        var player = (AbstractClientPlayer) minecraft.level.getPlayerByUUID(SUBJECT);
        if (player != null) {
            subjectEntityId = player.getId();
        }
        String dimension = minecraft.level.dimension().location().toString();
        List<WornModel> models = module.appearances().models(SUBJECT, subjectEntityId, dimension);
        switch (step) {
            case 0 -> {
                if (player == null || models.size() != 2) {
                    return;
                }
                verifyRendered(context, player, models, Pose.STANDING);
                verifyRendered(context, player, models, Pose.CROUCHING);
                var self = minecraft.player;
                verifyRendered(
                        context,
                        self,
                        module.appearances().models(self.getUUID(), self.getId(), dimension),
                        Pose.STANDING);
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
            GuiGraphics context,
            AbstractClientPlayer player,
            List<WornModel> models,
            Pose pose) {
        var snapshot =
                EquipmentAppearance.newBuilder()
                        .setPlayerUuid(player.getStringUUID())
                        .setEntityId(player.getId())
                        .setDimension(player.level().dimension().location().toString())
                        .addAllModels(models)
                        .build();
        Pose previous = player.getPose();
        float oldBodyYaw = player.yBodyRot;
        float oldHeadYaw = player.yHeadRot;
        float oldPreviousHeadYaw = player.yHeadRotO;
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        try {
            // 同一位置前后对拍，差别只能来自外观缓存；站姿与蹲姿都走真实骨架。
            player.setPose(pose);
            player.yBodyRot = 145;
            player.yHeadRot = 145;
            player.yHeadRotO = 145;
            player.setYRot(145);
            player.setXRot(0);
            drawPlayer(context, player);
            try (var equipped = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                module.appearances().accept(snapshot.toBuilder().clearModels().build());
                drawPlayer(context, player);
                try (var bare = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                    int changed = 0;
                    for (int y = 0; y < bare.getHeight(); y++) {
                        for (int x = 0; x < bare.getWidth(); x++) {
                            if (bare.getPixelRGBA(x, y) != equipped.getPixelRGBA(x, y)) {
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
            Screenshot.grab(
                    minecraft.gameDirectory,
                    "world-equipment-" + player.getName().getString() + "-" + pose + ".png",
                    minecraft.getMainRenderTarget(),
                    text -> {});
        } finally {
            module.appearances().accept(snapshot);
            player.setPose(previous);
            player.yBodyRot = oldBodyYaw;
            player.yHeadRot = oldHeadYaw;
            player.yHeadRotO = oldPreviousHeadYaw;
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
    }

    private void drawPlayer(GuiGraphics context, AbstractClientPlayer player) {
        // 每次独立对拍都先清掉前一模型的深度，背景和实体分别提交。
        context.flush();
        RenderSystem.depthMask(true);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        context.fill(
                0, 0, width, height, InventoryUiClient.runtime().color(ThemeTokens.BACKGROUND));
        context.flush();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        InventoryScreen.renderEntityInInventory(
                context,
                width / 2,
                height * 4 / 5,
                height / 3,
                new Quaternionf().rotateZ((float) Math.PI),
                null,
                player);
        context.flush();
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
