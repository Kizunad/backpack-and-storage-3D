package dev.kizuna.backpack3d.smoke;

import dev.kizuna.backpack3d.client.VolumeModule;
import dev.kizuna.backpack3d.client.VolumeScreen;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Phase;
import dev.kizuna.backpack3d.world.GroundBagBlockEntity;
import dev.kizuna.backpack3d.world.GroundBags;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.Files;

/** 使用原版交互包放置、打开和收回，并验证计时存取、取消与重启后的内容。 */
final class GroundSmokeClient {
    private int step;
    private int ticks;
    private String id;
    private BlockPos pos;
    private String stone;

    String tick(MinecraftClient client, VolumeModule module, boolean restart) throws IOException {
        if (id == null) {
            id =
                    Files.readString(client.runDirectory.toPath().resolve("../ground-test-id.txt"))
                            .strip();
            if (restart) {
                pos =
                        BlockPos.fromLong(
                                Long.parseLong(
                                        Files.readString(
                                                        client.runDirectory
                                                                .toPath()
                                                                .resolve("../ground-test-pos.txt"))
                                                .strip()));
            }
        }
        ticks++;
        if (ticks > 300) {
            throw new IllegalStateException(
                    "地面验收停滞：step=" + step + ", restart=" + restart + ", status=" + module.status());
        }
        if (restart) {
            return restart(client, module);
        }
        switch (step) {
            case 0 -> {
                if (!client.player.isOnGround()
                        || !client.player
                                .getInventory()
                                .getStack(0)
                                .isOf(
                                        net.minecraft.registry.Registries.ITEM.get(
                                                ContainerItems.POUCH))) {
                    return null;
                }
                client.player.getInventory().selectedSlot = 0;
                client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(0));
                pos = client.player.getBlockPos().east(2);
                Files.writeString(
                        client.runDirectory.toPath().resolve("../ground-test-pos.txt"),
                        Long.toString(pos.asLong()));
                sneak(client, true);
                var support = pos.down();
                client.interactionManager.interactBlock(
                        client.player,
                        Hand.MAIN_HAND,
                        new BlockHitResult(
                                new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5),
                                Direction.UP,
                                support,
                                false));
                next();
            }
            case 1 -> {
                if (client.world.getBlockEntity(pos) instanceof GroundBagBlockEntity bag
                        && bag.container().equals(id)
                        && client.player.getMainHandStack().isEmpty()
                        && module.state().getRevision() == 17) {
                    sneak(client, false);
                    use(client);
                    next();
                }
            }
            case 2 -> {
                if (!(client.currentScreen instanceof VolumeScreen screen)
                        || !screen.openContainers().contains(id)) {
                    return null;
                }
                require(module.container(id).getGrounded(), "地面容器被当作穿戴容器");
                require(
                        module.state().getItemsList().stream()
                                        .filter(item -> item.getContainer().equals(id))
                                        .count()
                                == 2,
                        "放置丢失了包内物品");
                stone =
                        module.state().getItemsList().stream()
                                .filter(
                                        item ->
                                                item.getContainer().equals(id)
                                                        && module.stack(item.getId())
                                                                .isOf(Items.STONE))
                                .findFirst()
                                .orElseThrow()
                                .getId();
                ScreenshotRecorder.saveScreenshot(
                        client.runDirectory,
                        "ground-open.png",
                        client.getFramebuffer(),
                        text -> {});
                module.withdraw(stone, PlayerInventory.OFF_HAND_SLOT);
                next();
            }
            case 3 -> {
                if (module.state().getRevision() == 18 && !module.busy()) {
                    require(client.player.getOffHandStack().getCount() == 8, "地面取出数量错误");
                    module.deposit(
                            module.playerSlot(PlayerInventory.OFF_HAND_SLOT),
                            id,
                            Volumes.cell(0, 0, 1),
                            false,
                            0);
                    next();
                }
            }
            case 4 -> {
                if (module.state().getRevision() == 19 && !module.busy()) {
                    stone =
                            module.state().getItemsList().stream()
                                    .filter(
                                            item ->
                                                    item.getContainer().equals(id)
                                                            && module.stack(item.getId())
                                                                    .isOf(Items.STONE))
                                    .findFirst()
                                    .orElseThrow()
                                    .getId();
                    module.withdraw(stone, PlayerInventory.OFF_HAND_SLOT);
                    next();
                }
            }
            case 5 -> {
                if (module.state().getTransfer().getPhase() == Phase.RUNNING) {
                    ((VolumeScreen) client.currentScreen).close();
                    next();
                }
            }
            case 6 -> {
                if (client.currentScreen == null
                        && module.state().getGroundRevisionsCount() == 0
                        && !module.busy()) {
                    require(module.state().getRevision() == 19, "关闭地面背包后仍提交取物");
                    require(client.player.getOffHandStack().isEmpty(), "取消地面取物失败");
                    return "revision=19; native-shift-place; native-open; ground-transfer;"
                               + " close-cancel; contents-preserved";
                }
            }
            default -> throw new IllegalStateException("未知地面验收步骤");
        }
        return null;
    }

    private String restart(MinecraftClient client, VolumeModule module) throws IOException {
        switch (step) {
            case 0 -> {
                if (!(client.world.getBlockEntity(pos) instanceof GroundBagBlockEntity bag)
                        || !bag.container().equals(id)
                        || !client.player.isOnGround()) {
                    return null;
                }
                sneak(client, false);
                use(client);
                next();
            }
            case 1 -> {
                if (!(client.currentScreen instanceof VolumeScreen screen)
                        || !screen.openContainers().contains(id)) {
                    return null;
                }
                var contents =
                        module.state().getItemsList().stream()
                                .filter(item -> item.getContainer().equals(id))
                                .toList();
                require(contents.size() == 2, "重启地面容器内容不一致");
                var sword =
                        contents.stream()
                                .filter(item -> module.stack(item.getId()).isOf(Items.IRON_SWORD))
                                .findFirst()
                                .orElseThrow();
                require(module.stack(sword.getId()).getDamage() == 17, "重启丢失物品 NBT");
                Files.writeString(
                        client.runDirectory.toPath().resolve("../ground-access-check.txt"), id);
                if (!Files.exists(
                        client.runDirectory.toPath().resolve("../ground-access-ok.txt"))) {
                    return null;
                }
                screen.close();
                next();
            }
            case 2 -> {
                if (client.currentScreen == null && module.state().getGroundRevisionsCount() == 0) {
                    sneak(client, true);
                    use(client);
                    next();
                }
            }
            case 3 -> {
                if (module.state().getRevision() == 20
                        && !client.world.getBlockState(pos).isOf(GroundBags.BLOCK)) {
                    sneak(client, false);
                    var held = client.player.getMainHandStack();
                    require(ContainerItems.identity(held).equals(id), "收回背包改变了身份");
                    require(ContainerItems.contents(held).getItemsCount() == 2, "收回背包丢失内容");
                    return "revision=20; ground-restart; original-NBT; native-shift-pickup;"
                               + " same-identity";
                }
            }
            default -> throw new IllegalStateException("未知地面重启步骤");
        }
        return null;
    }

    private void use(MinecraftClient client) {
        client.interactionManager.interactBlock(
                client.player,
                Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
    }

    private static void sneak(MinecraftClient client, boolean pressed) {
        client.options.sneakKey.setPressed(pressed);
        client.player.setSneaking(pressed);
        client.getNetworkHandler()
                .sendPacket(
                        new ClientCommandC2SPacket(
                                client.player,
                                pressed
                                        ? ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY
                                        : ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
    }

    private void next() {
        step++;
        ticks = 0;
        System.out.println("BS3D_GROUND step=" + step);
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalStateException(detail);
        }
    }
}
