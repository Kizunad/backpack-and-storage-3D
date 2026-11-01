package dev.kizuna.backpack3d.smoke;

import dev.kizuna.backpack3d.client.VolumeModule;
import dev.kizuna.backpack3d.client.VolumeScreen;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Phase;
import dev.kizuna.backpack3d.world.GroundBagBlockEntity;
import dev.kizuna.backpack3d.world.GroundBags;
import java.io.IOException;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** 使用原版交互包放置、打开和收回，并验证即时存取、模型落点与重启后的内容。 */
final class GroundSmokeClient {
    // 服务器夹具放进第 0 格的 Bong 灵草囊。
    private static final Identifier POUCH = Identifier.fromNamespaceAndPath("bs3d", "herb_pouch");
    private int step;
    private int ticks;
    private String id;
    private BlockPos pos;
    private String stone;
    private boolean modelOpened;

    String tick(Minecraft client, VolumeModule module, boolean restart) throws IOException {
        if (id == null) {
            id =
                    Files.readString(client.gameDirectory.toPath().resolve("../ground-test-id.txt"))
                            .strip();
            if (restart) {
                pos =
                        BlockPos.of(
                                Long.parseLong(
                                        Files.readString(
                                                        client.gameDirectory
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
                if (!client.player.onGround()
                        || !client.player
                                .getInventory()
                                .getItem(0)
                                .is(
                                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                                                POUCH))) {
                    return null;
                }
                client.player.getInventory().setSelectedSlot(0);
                client.getConnection().send(new ServerboundSetCarriedItemPacket(0));
                pos = client.player.blockPosition().east(2);
                Files.writeString(
                        client.gameDirectory.toPath().resolve("../ground-test-pos.txt"),
                        Long.toString(pos.asLong()));
                sneak(client, true);
                var support = pos.below();
                client.gameMode.useItemOn(
                        client.player,
                        InteractionHand.MAIN_HAND,
                        new BlockHitResult(
                                new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5),
                                Direction.UP,
                                support,
                                false));
                next();
            }
            case 1 -> {
                if (client.level.getBlockEntity(pos) instanceof GroundBagBlockEntity bag
                        && bag.container().equals(id)
                        && client.player.getMainHandItem().isEmpty()
                        && module.state().getRevision() == 25) {
                    sneak(client, false);
                    module.open();
                    next();
                }
            }
            case 2 -> {
                if (!(client.gui.screen() instanceof VolumeScreen screen)
                        || !module.state().containsGroundRevisions(id)) {
                    return null;
                }
                if (!modelOpened) {
                    require(!screen.openContainers().contains(id), "附近地面背包不应自动开盖");
                    modelOpened = openGroundModel(screen);
                    return null;
                }
                if (!screen.openContainers().contains(id)) {
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
                                                                .is(Items.STONE))
                                .findFirst()
                                .orElseThrow()
                                .getId();
                Screenshot.grab(
                        client.gameDirectory,
                        "ground-open.png",
                        client.gameRenderer.mainRenderTarget(),
                        1,
                        text -> {});
                module.withdraw(stone, Inventory.SLOT_OFFHAND);
                next();
            }
            case 3 -> {
                if (module.state().getRevision() == 26 && !module.busy()) {
                    require(client.player.getOffhandItem().getCount() == 8, "地面取出数量错误");
                    var screen = (VolumeScreen) client.gui.screen();
                    screen.closeContainer(id);
                    if (!dropOnGroundModel(screen)) {
                        return null;
                    }
                    next();
                }
            }
            case 4 -> {
                if (module.state().getRevision() == 27 && !module.busy()) {
                    stone =
                            module.state().getItemsList().stream()
                                    .filter(
                                            item ->
                                                    item.getContainer().equals(id)
                                                            && module.stack(item.getId())
                                                                    .is(Items.STONE))
                                    .findFirst()
                                    .orElseThrow()
                                    .getId();
                    module.move(stone, id, Volumes.cell(-1, 0, 0), false /* automatic */);
                    next();
                }
            }
            case 5 -> {
                if (module.state().getTransfer().getPhase() == Phase.REJECTED) {
                    ((VolumeScreen) client.gui.screen()).onClose();
                    next();
                }
            }
            case 6 -> {
                if (client.gui.screen() == null
                        && module.state().getGroundRevisionsCount() == 0
                        && !module.busy()) {
                    require(module.state().getRevision() == 27, "拒绝地面落点后仍提交取物");
                    require(client.player.getOffhandItem().isEmpty(), "拒绝未保持地面原库存");
                    return "revision=27; native-shift-place; nearby-closed-model; ground-closed-model-drop;"
                               + " rejected-unchanged; contents-preserved";
                }
            }
            default -> throw new IllegalStateException("未知地面验收步骤");
        }
        return null;
    }

    private boolean dropOnGroundModel(VolumeScreen screen) {
        // 从真实副手命中拖到人物旁的地面模型，落点无需指定内景格位。
        int[] source = null;
        int[] target = null;
        for (int y = 0; y < screen.height; y++) {
            for (int x = 0; x < screen.width; x++) {
                if (source == null && screen.playerSlotAt(x, y) == Inventory.SLOT_OFFHAND) {
                    source = new int[] {x, y};
                }
                if (target == null && screen.bagHit(id, x, y)) {
                    target = new int[] {x, y};
                }
            }
        }
        if (source == null || target == null) {
            return false;
        }
        screen.mouseClicked(mouse(source[0], source[1], 0), false);
        screen.mouseDragged(mouse(target[0], target[1], 0), target[0] - source[0], target[1] - source[1]);
        screen.mouseReleased(mouse(target[0], target[1], 0));
        return true;
    }

    private boolean openGroundModel(VolumeScreen screen) {
        // 场景直接发现合盖地面模型，真实鼠标点击后才打开其内景。
        for (int y = 0; y < screen.height; y++) {
            for (int x = 0; x < screen.width; x++) {
                if (screen.bagHit(id, x, y)) {
                    screen.mouseClicked(mouse(x, y, 0), false);
                    screen.mouseReleased(mouse(x, y, 0));
                    return true;
                }
            }
        }
        return false;
    }

    private String restart(Minecraft client, VolumeModule module) throws IOException {
        switch (step) {
            case 0 -> {
                if (!(client.level.getBlockEntity(pos) instanceof GroundBagBlockEntity bag)
                        || !bag.container().equals(id)
                        || !client.player.onGround()) {
                    return null;
                }
                sneak(client, false);
                use(client);
                next();
            }
            case 1 -> {
                if (!(client.gui.screen() instanceof VolumeScreen screen)
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
                                .filter(item -> module.stack(item.getId()).is(Items.IRON_SWORD))
                                .findFirst()
                                .orElseThrow();
                require(module.stack(sword.getId()).getDamageValue() == 17, "重启丢失物品 NBT");
                Files.writeString(
                        client.gameDirectory.toPath().resolve("../ground-access-check.txt"), id);
                if (!Files.exists(
                        client.gameDirectory.toPath().resolve("../ground-access-ok.txt"))) {
                    return null;
                }
                screen.onClose();
                next();
            }
            case 2 -> {
                if (client.gui.screen() == null && module.state().getGroundRevisionsCount() == 0) {
                    sneak(client, true);
                    use(client);
                    next();
                }
            }
            case 3 -> {
                if (module.state().getRevision() == 28
                        && !client.level.getBlockState(pos).is(GroundBags.BLOCK)) {
                    sneak(client, false);
                    var held = client.player.getMainHandItem();
                    require(ContainerItems.identity(held).equals(id), "收回背包改变了身份");
                    require(ContainerItems.contents(held).getItemsCount() == 2, "收回背包丢失内容");
                    return "revision=28; ground-restart; original-NBT; native-shift-pickup;"
                               + " same-identity";
                }
            }
            default -> throw new IllegalStateException("未知地面重启步骤");
        }
        return null;
    }

    private void use(Minecraft client) {
        client.gameMode.useItemOn(
                client.player,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    private static void sneak(Minecraft client, boolean pressed) {
        client.options.keyShift.setDown(pressed);
        client.player.setShiftKeyDown(pressed);
        // 26.2 的潜行随整组移动输入上报；立即补发一次，不等下一 tick 的输入比较。
        client.getConnection()
                .send(new ServerboundPlayerInputPacket(new Input(false, false, false, false, false, pressed, false)));
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

    private static MouseButtonEvent mouse(double x, double y, int button) {
        // 合成输入不带修饰键，坐标为 GUI 逻辑像素。
        return new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
    }
}
