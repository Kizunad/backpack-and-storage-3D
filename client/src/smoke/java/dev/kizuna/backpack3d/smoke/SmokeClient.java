package dev.kizuna.backpack3d.smoke;

import dev.kizuna.backpack3d.client.*;
import dev.kizuna.backpack3d.protocol.OrthogonalRotation;
import dev.kizuna.backpack3d.protocol.PlayerSlots;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;
import dev.kizuna.inventoryui.client.*;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.*;
import net.minecraft.client.network.*;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.util.Arm;

import org.lwjgl.glfw.GLFW;

import java.nio.file.*;

/** 真实渲染、服务器计时、移动锁、ESC 撤销及重启恢复的集成验收。 */
public final class SmokeClient implements ClientModInitializer {
    private static final String ADDRESS = "127.0.0.1:25579";
    private static final long TIMEOUT_MILLIS = 240_000;
    private static final int STEP_TIMEOUT_TICKS = 400;

    private enum Step {
        WAIT_GROUND,
        VERIFY_WORLD_EQUIPMENT,
        VERIFY_PREVIEW_DEPTH,
        VERIFY_WORKSPACE,
        ROTATE_PLAYER,
        OPEN_POUCH,
        START_CANCEL_TEST,
        VERIFY_MOVEMENT_LOCK,
        VERIFY_ESCAPE_CANCEL,
        DEPOSIT_SWORD,
        VERIFY_SWORD,
        DEPOSIT_STONE,
        SELECT_PICKAXE,
        DEPOSIT_PICKAXE,
        OPEN_BASKET,
        DRAG_WINDOW,
        VERIFY_LAYER_RANGE,
        RESTORE_LAYER_RANGE,
        DRAG_ROTATED_ITEM,
        VERIFY_EMPTY_DRAG,
        VERIFY_HELP,
        CAPTURE_TRANSFER,
        VERIFY_TRANSFER,
        EQUIP_POUCH,
        EQUIP_BASKET,
        CLOSE_SCREEN,
        FINISH_FIRST,
        WITHDRAW_SWORD,
        DEPOSIT_OFFHAND_SWORD,
        DRAG_OFFHAND_SWORD,
        WITHDRAW_MAINHAND_SWORD,
        HAND_TO_OFFHAND,
        HAND_TO_MAINHAND,
        ARMOR_TO_OFFHAND,
        ARMOR_TO_HEAD,
        BAG_TO_BASKET,
        BAG_TO_WAIST,
        BASKET_TO_BACK,
        CANCEL_BEFORE_ACK,
        VERIFY_EARLY_CANCEL,
        DISCONNECT_DURING_TRANSFER,
        FINISHED
    }

    private long started;
    private boolean connecting;
    private Step step = Step.WAIT_GROUND;
    private int ticks;
    private double anchorX;
    private boolean movementAttempted;
    private String phase;
    private String sword;
    private String pouch;
    private String basket;
    private boolean rotatedScene;
    private float[] initialBagBounds;
    private java.util.List<Cell> cellsBeforeDrag;
    private float[] basketBeforeOpening;
    private int itemPickAttempts;
    private final GroundSmokeClient groundSmoke = new GroundSmokeClient();
    private final WorkspaceBackpackProbe workspaceProbe = new WorkspaceBackpackProbe();

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("bs3d.smoke")) {
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    if (started == 0) {
                        started = System.currentTimeMillis();
                    }
                    if (System.currentTimeMillis() - started > TIMEOUT_MILLIS) {
                        throw new IllegalStateException(
                                "3D smoke timeout, phase="
                                        + phase
                                        + ", step="
                                        + step
                                        + ", protocol="
                                        + InventoryUiClient.runtime().protocolError());
                    }
                    if (client.currentScreen instanceof AccessibilityOnboardingScreen) {
                        client.setScreen(new TitleScreen());
                    }
                    if (!connecting
                            && client.currentScreen instanceof TitleScreen
                            && client.getOverlay() == null) {
                        connecting = true;
                        ConnectScreen.connect(
                                new TitleScreen(),
                                client,
                                ServerAddress.parse(ADDRESS),
                                new ServerInfo("3D smoke", ADDRESS, false),
                                false);
                    }
                    var module =
                            FabricLoader.getInstance()
                                    .getEntrypoints("kizuna_inventory_ui", ClientModule.class)
                                    .stream()
                                    .filter(value -> value instanceof VolumeModule)
                                    .map(VolumeModule.class::cast)
                                    .findFirst()
                                    .orElseThrow();
                    if (client.player == null || !module.ready()) {
                        return;
                    }
                    if (pouch == null) {
                        pouch =
                                module.state().getContainersList().stream()
                                        .filter(
                                                value ->
                                                        value.getWorn()
                                                                && value.getModel()
                                                                        .equals("grass_pouch"))
                                        .findFirst()
                                        .orElseThrow()
                                        .getId();
                        basket =
                                module.state().getContainersList().stream()
                                        .filter(
                                                value ->
                                                        value.getWorn()
                                                                && value.getModel()
                                                                        .equals("back_basket"))
                                        .findFirst()
                                        .orElseThrow()
                                        .getId();
                    }
                    ticks++;
                    if (ticks > STEP_TIMEOUT_TICKS) {
                        shot(client, "stalled-" + step);
                        throw new IllegalStateException(
                                "验收步骤停滞："
                                        + step
                                        + ", revision="
                                        + module.state().getRevision()
                                        + ", phase="
                                        + module.state().getTransfer().getPhase()
                                        + ", status="
                                        + module.status());
                    }
                    // 隐藏验收物品触发的成就浮层，让截图只记录实际背包界面。
                    client.getToastManager().clear();
                    try {
                        if (phase == null) {
                            phase =
                                    Files.readString(
                                                    client.runDirectory
                                                            .toPath()
                                                            .resolve("phase.txt"))
                                            .strip();
                        }
                        if (phase.startsWith("ground")) {
                            String result =
                                    groundSmoke.tick(
                                            client, module, phase.equals("ground_restart"));
                            if (result != null) {
                                finish(client, result);
                            }
                        } else if (phase.equals("first")) {
                            first(client, module);
                        } else {
                            second(client, module);
                        }
                    } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                });
    }

    private void first(MinecraftClient client, VolumeModule module) throws java.io.IOException {
        var state = module.state();
        var transfer = state.getTransfer();
        switch (step) {
            case WAIT_GROUND -> {
                if (!client.player.isOnGround()
                        || !client.player.getMainHandStack().isOf(Items.IRON_SWORD)) {
                    return;
                }
                client.setScreen(new WorldEquipmentProbe(module));
                next(Step.VERIFY_WORLD_EQUIPMENT);
            }
            case VERIFY_WORLD_EQUIPMENT -> {
                if (!((WorldEquipmentProbe) client.currentScreen).done()) {
                    return;
                }
                client.setScreen(new PreviewDepthProbe(module));
                next(Step.VERIFY_PREVIEW_DEPTH);
            }
            case VERIFY_PREVIEW_DEPTH -> {
                if (((PreviewDepthProbe) client.currentScreen).done()) {
                    // 工作台入口先通过真实菜单和三维命中，随后继续原有完整库存流程。
                    next(Step.VERIFY_WORKSPACE);
                }
            }
            case VERIFY_WORKSPACE -> {
                if (workspaceProbe.tick(client, module, pouch)) {
                    module.open();
                    next(Step.ROTATE_PLAYER);
                }
            }
            case ROTATE_PLAYER -> {
                var screen = (VolumeScreen) client.currentScreen;
                if (screen.bagBounds(pouch) == null) {
                    return;
                }
                if (!rotatedScene) {
                    initialBagBounds = screen.bagBounds(pouch);
                    rotatedScene = true;
                    ticks = 0;
                    screen.mouseClicked(20, 100, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                    screen.mouseDragged(75, 108, GLFW.GLFW_MOUSE_BUTTON_RIGHT, 55, 8);
                    screen.mouseReleased(75, 108, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                }
                if (ticks >= 20) {
                    // 连续腰环的默认角度可能被手臂遮挡，先转到可见侧再验证开盖不改变视角。
                    if (visibleBag(screen, pouch) == null) {
                        rotatePreview(screen);
                        ticks = 0;
                        return;
                    }
                    require(
                            !java.util.Arrays.equals(initialBagBounds, screen.bagBounds(pouch)),
                            "拖动人物未改变装备投影");
                    shot(client, "01-overview");
                    basketBeforeOpening = screen.bagBounds(basket);
                    clickBag(client, screen, pouch);
                    next(Step.OPEN_POUCH);
                }
            }
            case OPEN_POUCH -> {
                // 实际鼠标命中必须触发完整动画，不直接强行设置窗口状态。
                if (ticks % 3 == 0 && ticks <= 36) {
                    shot(client, String.format("open-%02d", ticks));
                }
                if (((VolumeScreen) client.currentScreen).openContainers().contains(pouch)
                        && ticks > 25) {
                    require(
                            java.util.Arrays.equals(
                                    basketBeforeOpening,
                                    ((VolumeScreen) client.currentScreen).bagBounds(basket)),
                            "打开草包改变了人物取景或缩放");
                    shot(client, "02-open-pouch");
                    module.deposit(pouch);
                    next(Step.START_CANCEL_TEST);
                }
            }
            case START_CANCEL_TEST -> {
                if (transfer.getPhase() == Phase.RUNNING && transfer.getElapsedTicks() >= 6) {
                    require(state.getRevision() == 0 && storedItems(state).size() == 0, "提前挪动物品");
                    require(client.player.getMainHandStack().isOf(Items.IRON_SWORD), "计时中手持物品丢失");
                    require(
                            transfer.getContactSound().equals("minecraft:block.chain.hit"),
                            "tag 音效规则未加载");
                    anchorX = client.player.getX();
                    client.getNetworkHandler()
                            .sendPacket(
                                    new PlayerMoveC2SPacket.PositionAndOnGround(
                                            anchorX + 3,
                                            client.player.getY(),
                                            client.player.getZ(),
                                            true));
                    movementAttempted = true;
                    next(Step.VERIFY_MOVEMENT_LOCK);
                }
            }
            case VERIFY_MOVEMENT_LOCK -> {
                if (transfer.getPhase() == Phase.RUNNING && transfer.getElapsedTicks() >= 14) {
                    require(
                            movementAttempted && Math.abs(client.player.getX() - anchorX) < 0.05,
                            "服务器位置锁失败");
                    shot(client, "03-transfer-progress");
                    client.currentScreen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
                    next(Step.VERIFY_ESCAPE_CANCEL);
                }
            }
            case VERIFY_ESCAPE_CANCEL -> {
                if (ticks > 25 && transfer.getPhase() == Phase.CANCELLED) {
                    require(
                            state.getRevision() == 0 && storedItems(state).size() == 0,
                            "ESC 取消后仍提交");
                    require(client.player.getMainHandStack().isOf(Items.IRON_SWORD), "取消丢物品");
                    module.open();
                    ((VolumeScreen) client.currentScreen).openContainer(pouch);
                    next(Step.DEPOSIT_SWORD);
                }
            }
            case DEPOSIT_SWORD -> {
                if (((VolumeScreen) client.currentScreen).openContainers().contains(pouch)
                        && ticks > 30) {
                    if (dragHandIntoContainer(
                            client, module, client.player.getInventory().selectedSlot, pouch)) {
                        next(Step.VERIFY_SWORD);
                    }
                }
            }
            case VERIFY_SWORD -> {
                if (state.getRevision() == 1 && !module.busy()) {
                    sword = storedItems(state).get(0).getId();
                    require(storedItems(state).get(0).getSize().getHeight() == 2, "工具三维尺寸失效");
                    require(module.stack(sword).getDamage() == 17, "存入丢失 NBT");
                    slot(client, 1);
                    next(Step.DEPOSIT_STONE);
                }
            }
            case DEPOSIT_STONE -> {
                if (ticks > 3) {
                    module.deposit(basket);
                    next(Step.SELECT_PICKAXE);
                }
            }
            case SELECT_PICKAXE -> {
                if (state.getRevision() == 2 && !module.busy()) {
                    slot(client, 2);
                    next(Step.DEPOSIT_PICKAXE);
                }
            }
            case DEPOSIT_PICKAXE -> {
                if (ticks > 3) {
                    module.deposit(basket);
                    next(Step.OPEN_BASKET);
                }
            }
            case OPEN_BASKET -> {
                if (state.getRevision() == 3 && !module.busy()) {
                    ((VolumeScreen) client.currentScreen).openContainer(basket);
                    next(Step.DRAG_WINDOW);
                }
            }
            case DRAG_WINDOW -> {
                var screen = (VolumeScreen) client.currentScreen;
                if (screen.openContainers().size() == 2 && ticks > 30) {
                    var bounds = screen.window(pouch).bounds();
                    screen.mouseClicked(
                            bounds.x() + 8, bounds.y() + 8, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    screen.mouseDragged(
                            bounds.x() + 8, bounds.y() + 24, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0, 16);
                    screen.mouseReleased(
                            bounds.x() + 8, bounds.y() + 24, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    require(screen.window(pouch).bounds().y() == bounds.y() + 16, "拖窗失效");
                    dragLayerHandle(screen, basket, true, 0, 1);
                    dragLayerHandle(screen, basket, false, 3, 2);
                    next(Step.VERIFY_LAYER_RANGE);
                }
            }
            case VERIFY_LAYER_RANGE -> {
                if (ticks > 3) {
                    var screen = (VolumeScreen) client.currentScreen;
                    require(
                            java.util.Arrays.equals(screen.visibleLayers(basket), new int[] {1, 2}),
                            "范围条没有选中中间层");
                    var cells =
                            sampleCells(screen, basket).stream()
                                    .filter(java.util.Objects::nonNull)
                                    .toList();
                    require(
                            !cells.isEmpty() && cells.stream().allMatch(cell -> cell.getY() == 1),
                            "切片后仍能命中隐藏层");
                    require(
                            java.util.Arrays.equals(screen.visibleLayers(pouch), new int[] {0, 2}),
                            "范围条影响了另一容器");
                    require(state.getRevision() == 3 && !module.busy(), "范围条拖动意外提交库存操作");
                    shot(client, "layer-range-middle");
                    dragLayerHandle(screen, basket, true, 1, 0);
                    dragLayerHandle(screen, basket, false, 2, 3);
                    next(Step.RESTORE_LAYER_RANGE);
                }
            }
            case RESTORE_LAYER_RANGE -> {
                if (ticks > 3) {
                    var screen = (VolumeScreen) client.currentScreen;
                    require(
                            java.util.Arrays.equals(screen.visibleLayers(basket), new int[] {0, 3}),
                            "范围条无法恢复全部格层");
                    require(
                            sampleCells(screen, basket).stream()
                                    .anyMatch(cell -> cell != null && cell.getY() != 1),
                            "恢复全景后命中仍滞留在中间层");
                    System.out.println(
                            "BS3D_SMOKE layer-range: middle-only picking; independent windows; no"
                                + " transfer; restored");
                    next(Step.DRAG_ROTATED_ITEM);
                }
            }
            case DRAG_ROTATED_ITEM -> {
                var screen = (VolumeScreen) client.currentScreen;
                if (ticks == 5) {
                    var b = screen.window(pouch).bounds();
                    screen.mouseClicked(
                            b.x() + b.width() - 27, b.y() + 8, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    require(screen.window(pouch).minimized(), "最小化失效");
                }
                if (ticks == 8) {
                    // 恢复按钮与标题分开，点击小卡片右侧的独立恢复区域。
                    screen.mouseClicked(90, screen.height - 42, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    require(!screen.window(pouch).minimized(), "恢复窗口失效");
                }
                if (ticks > 12) {
                    shot(client, "04-two-windows-interior");
                    selectSword(screen);
                    screen.mouseReleased(0, 0, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    cellsBeforeDrag = sampleCells(screen, basket);
                    dragEmptySpace(screen, basket);
                    require(!module.busy(), "拖动空白处意外启动物品转移");
                    next(Step.VERIFY_EMPTY_DRAG);
                }
            }
            case VERIFY_EMPTY_DRAG -> {
                if (ticks > 3) {
                    var screen = (VolumeScreen) client.currentScreen;
                    require(!cellsBeforeDrag.equals(sampleCells(screen, basket)), "左拖空白处没有旋转内景");
                    require(
                            screen.selectedItem().equals(sword) && !module.busy(),
                            "旋转场景改变了选中物品或库存");
                    screen.mouseClicked(screen.width - 22, 22, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    next(Step.VERIFY_HELP);
                }
            }
            case VERIFY_HELP -> {
                if (ticks > 3) {
                    var screen = (VolumeScreen) client.currentScreen;
                    int previousOrientation = screen.selectedOrientation();
                    screen.keyPressed(GLFW.GLFW_KEY_T, 0, 0);
                    require(screen.selectedOrientation() == previousOrientation, "帮助面板没有拦截旋转输入");
                    screen.mouseClicked(screen.width - 100, 100, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    require(!module.busy(), "帮助面板点击穿透到了物品");
                    shot(client, "08-controls-help");
                    screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
                    require(client.currentScreen == screen, "ESC 关闭帮助时意外关闭背包");
                    selectSword(screen);
                    int orientation = rotateWithReboundKey(screen, module);
                    dropSwordIntoBasket(client, screen, module, orientation);
                    next(Step.CAPTURE_TRANSFER);
                }
            }
            case CAPTURE_TRANSFER -> {
                if (transfer.getPhase() == Phase.RUNNING && transfer.getElapsedTicks() >= 24) {
                    shot(client, "05-cross-container");
                    next(Step.VERIFY_TRANSFER);
                }
            }
            case VERIFY_TRANSFER -> {
                if (state.getRevision() == 4 && !module.busy()) {
                    require(storedItems(state).size() == 3, "跨容器发生复制或丢失");
                    require(
                            state.getItemsList().stream()
                                    .filter(item -> item.getId().equals(sword))
                                    .allMatch(item -> item.getContainer().equals(basket)),
                            "物品仍在来源容器");
                    var moved =
                            state.getItemsList().stream()
                                    .filter(i -> i.getId().equals(sword))
                                    .findFirst()
                                    .orElseThrow();
                    require(
                            moved.getSize().equals(Volumes.size(2, 1, 1))
                                    && moved.getOrientation() != 0,
                            "旋转未由服务端保存");
                    shot(client, "06-basket-filled");
                    next(Step.EQUIP_POUCH);
                }
            }
            case EQUIP_POUCH -> {
                if (ticks > 10) {
                    module.cycleMount(pouch);
                    next(Step.EQUIP_BASKET);
                }
            }
            case EQUIP_BASKET -> {
                if (state.getRevision() == 5) {
                    require(module.mount(pouch) == Mount.WAIST_LEFT_FRONT, "草包挂点未同步");
                    module.cycleMount(basket);
                    next(Step.CLOSE_SCREEN);
                }
            }
            case CLOSE_SCREEN -> {
                if (state.getRevision() == 6 && ticks > 10) {
                    require(module.mount(basket) == Mount.TORSO_FRONT, "背篓前后挂点未同步");
                    shot(client, "07-equipment-front");
                    ((VolumeScreen) client.currentScreen).close();
                    next(Step.FINISH_FIRST);
                }
            }
            case FINISH_FIRST -> {
                if (ticks % 3 == 0 && ticks <= 36) {
                    shot(client, String.format("close-%02d", ticks));
                }
                if (ticks > 40) {
                    finish(
                            client,
                            "revision=6; world-equipment; worn-model; scene-rotation;"
                                + " model-picking; two-windows; drag-window; minimize-restore;"
                                + " rotated-drag-drop; remapped-keys; equipment; timed-transfer;"
                                + " movement-lock; ESC-cancel; tags; NBT");
                }
            }
            default -> {}
        }
    }

    private void second(MinecraftClient client, VolumeModule module) throws java.io.IOException {
        var state = module.state();
        switch (step) {
            case WAIT_GROUND -> {
                if (!client.player.isOnGround()) {
                    return;
                }
                require(state.getRevision() == 6 && storedItems(state).size() == 3, "重启恢复失败");
                sword =
                        state.getItemsList().stream()
                                .filter(item -> module.stack(item.getId()).isOf(Items.IRON_SWORD))
                                .findFirst()
                                .orElseThrow()
                                .getId();
                require(module.stack(sword).getDamage() == 17, "重启 NBT 丢失");
                require(
                        module.mount(pouch) == Mount.WAIST_LEFT_FRONT
                                && module.mount(basket) == Mount.TORSO_FRONT,
                        "装备挂点重启丢失");
                require(
                        state.getItemsList().stream()
                                .filter(i -> i.getId().equals(sword))
                                .findFirst()
                                .orElseThrow()
                                .getSize()
                                .equals(Volumes.size(2, 1, 1)),
                        "朝向重启丢失");
                module.open();
                ((VolumeScreen) client.currentScreen).openContainer(basket);
                next(Step.WITHDRAW_SWORD);
            }
            case WITHDRAW_SWORD -> {
                if (ticks > 35) {
                    if (dragItemToHand(client, sword, basket, PlayerInventory.OFF_HAND_SLOT)) {
                        next(Step.DEPOSIT_OFFHAND_SWORD);
                    }
                }
            }
            case DEPOSIT_OFFHAND_SWORD -> {
                if (state.getRevision() == 7 && !module.busy()) {
                    require(
                            client.player.getOffHandStack().isOf(Items.IRON_SWORD)
                                    && client.player.getOffHandStack().getDamage() == 17,
                            "拖到副手后真实栈或 NBT 丢失");
                    require(client.player.getMainHandStack().isEmpty(), "指定副手却放进主手");
                    shot(client, "09-offhand-withdraw");
                    // 切换惯用手后仍按真实骨架拖物品，验证左右手没有硬编码为主副手。
                    client.options.getMainArm().setValue(Arm.LEFT);
                    client.player.setMainArm(Arm.LEFT);
                    next(Step.DRAG_OFFHAND_SWORD);
                }
            }
            case DRAG_OFFHAND_SWORD -> {
                if (ticks > 4
                        && dragHandIntoContainer(
                                client, module, PlayerInventory.OFF_HAND_SLOT, basket)) {
                    next(Step.WITHDRAW_MAINHAND_SWORD);
                }
            }
            case WITHDRAW_MAINHAND_SWORD -> {
                if (state.getRevision() == 8 && !module.busy()) {
                    require(client.player.getOffHandStack().isEmpty(), "从副手存入后仍持有同一栈");
                    sword =
                            state.getItemsList().stream()
                                    .filter(
                                            item ->
                                                    module.stack(item.getId())
                                                            .isOf(Items.IRON_SWORD))
                                    .findFirst()
                                    .orElseThrow()
                                    .getId();
                    if (dragItemToHand(
                            client, sword, basket, client.player.getInventory().selectedSlot)) {
                        next(Step.HAND_TO_OFFHAND);
                    }
                }
            }
            case HAND_TO_OFFHAND -> {
                if (state.getRevision() == 9
                        && !module.busy()
                        && dragPlayerSlot(
                                client,
                                client.player.getInventory().selectedSlot,
                                PlayerInventory.OFF_HAND_SLOT)) {
                    next(Step.HAND_TO_MAINHAND);
                }
            }
            case HAND_TO_MAINHAND -> {
                if (state.getRevision() == 10 && !module.busy()) {
                    require(
                            client.player.getMainHandStack().isEmpty()
                                    && client.player.getOffHandStack().isOf(Items.IRON_SWORD),
                            "左手到右手转移未完成");
                    if (dragPlayerSlot(
                            client,
                            PlayerInventory.OFF_HAND_SLOT,
                            client.player.getInventory().selectedSlot)) {
                        next(Step.ARMOR_TO_OFFHAND);
                    }
                }
            }
            case ARMOR_TO_OFFHAND -> {
                if (state.getRevision() == 11 && !module.busy()) {
                    require(
                            client.player.getMainHandStack().getDamage() == 17
                                    && client.player.getOffHandStack().isEmpty(),
                            "换手往返丢失 NBT");
                    if (dragPlayerSlot(
                            client,
                            PlayerSlots.armorSlot(EquipmentSlot.HEAD),
                            PlayerInventory.OFF_HAND_SLOT)) {
                        next(Step.ARMOR_TO_HEAD);
                    }
                }
            }
            case ARMOR_TO_HEAD -> {
                if (state.getRevision() == 12 && !module.busy()) {
                    require(
                            client.player.getEquippedStack(EquipmentSlot.HEAD).isEmpty()
                                    && client.player.getOffHandStack().isOf(Items.IRON_HELMET),
                            "头盔拖到手中失败");
                    if (dragPlayerSlot(
                            client,
                            PlayerInventory.OFF_HAND_SLOT,
                            PlayerSlots.armorSlot(EquipmentSlot.HEAD))) {
                        next(Step.BAG_TO_BASKET);
                    }
                }
            }
            case BAG_TO_BASKET -> {
                if (state.getRevision() == 13 && !module.busy()) {
                    require(
                            client.player
                                    .getEquippedStack(EquipmentSlot.HEAD)
                                    .isOf(Items.IRON_HELMET),
                            "头盔重新穿戴失败");
                    if (dragWornToContainer(client, module, pouch, basket)) {
                        next(Step.BAG_TO_WAIST);
                    }
                }
            }
            case BAG_TO_WAIST -> {
                if (state.getRevision() == 14 && !module.busy()) {
                    require(
                            module.container(pouch).getParent().equals(basket)
                                    && !module.container(pouch).getWorn(),
                            "草包收纳后仍装备在身体上");
                    if (dragContainerToBody(client, pouch, basket, null)) {
                        next(Step.BASKET_TO_BACK);
                    }
                }
            }
            case BASKET_TO_BACK -> {
                if (state.getRevision() == 15 && !module.busy()) {
                    require(module.container(pouch).getWorn(), "草包没有重新挂上腰部");
                    require(module.container(pouch).getWaistAngleDegrees() != 45, "腰部位置仍锁定旧挂点");
                    if (dragContainerToBody(client, basket, null, Mount.TORSO_BACK)) {
                        next(Step.CANCEL_BEFORE_ACK);
                    }
                }
            }
            case CANCEL_BEFORE_ACK -> {
                if (state.getRevision() == 16 && !module.busy()) {
                    require(
                            client.player.getInventory().main.stream()
                                    .anyMatch(
                                            stack ->
                                                    stack.isOf(Items.IRON_SWORD)
                                                            && stack.getDamage() == 17),
                            "取回丢失真实栈");
                    var block =
                            state.getItemsList().stream()
                                    .filter(item -> module.stack(item.getId()).isOf(Items.STONE))
                                    .findFirst()
                                    .orElseThrow();
                    module.move(block.getId(), pouch, Cell.getDefaultInstance(), true);
                    // 尚未收到开始回执时关闭屏幕，验证取消不会漏过请求排队窗口。
                    client.currentScreen.close();
                    next(Step.VERIFY_EARLY_CANCEL);
                }
            }
            case VERIFY_EARLY_CANCEL -> {
                if (ticks > 60) {
                    require(
                            state.getRevision() == 16
                                    && state.getTransfer().getPhase() == Phase.CANCELLED,
                            "开始回执前关闭未得到取消结果：revision="
                                    + state.getRevision()
                                    + ", phase="
                                    + state.getTransfer().getPhase()
                                    + ", detail="
                                    + state.getTransfer().getDetail());
                    module.open();
                    var block =
                            state.getItemsList().stream()
                                    .filter(item -> module.stack(item.getId()).isOf(Items.STONE))
                                    .findFirst()
                                    .orElseThrow();
                    module.move(block.getId(), pouch, Cell.getDefaultInstance(), true);
                    next(Step.DISCONNECT_DURING_TRANSFER);
                }
            }
            case DISCONNECT_DURING_TRANSFER -> {
                if (state.getTransfer().getPhase() == Phase.RUNNING
                        && state.getTransfer().getElapsedTicks() >= 6) {
                    // 运行中直接断线，脚本核对服务器最终保存的 revision 仍为 16。
                    finish(
                            client,
                            "revision=16; restart-persistence; hand-to-hand; armor-drag;"
                                    + " portable-bag; waist-drag; torso-drag;"
                                    + " hand-drag-both-directions; left-handed; early-close-cancel;"
                                    + " disconnect-cancel; gravity-preserved");
                }
            }
            default -> {}
        }
    }

    private static java.util.List<Item> storedItems(State state) {
        return state.getItemsList().stream()
                .filter(value -> !value.getContainer().equals(Volumes.WORN))
                .toList();
    }

    private static boolean dragPlayerSlot(MinecraftClient client, int sourceSlot, int targetSlot) {
        var screen = (VolumeScreen) client.currentScreen;
        int[] source = visibleSlot(screen, sourceSlot);
        int[] target = visibleSlot(screen, targetSlot);
        if (source == null || target == null) {
            rotatePreview(screen);
            return false;
        }
        screen.mouseClicked(source[0], source[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
        require(
                screen.selectedItem().equals(VolumeModule.playerSlotPreviewId(sourceSlot)),
                "身体部位没有捕获来源：" + sourceSlot);
        screen.mouseDragged(
                target[0],
                target[1],
                GLFW.GLFW_MOUSE_BUTTON_LEFT,
                target[0] - source[0],
                target[1] - source[1]);
        screen.mouseReleased(target[0], target[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
        return true;
    }

    private static int[] visibleSlot(VolumeScreen screen, int slot) {
        var bounds = screen.playerSlotBounds(slot);
        if (bounds != null) {
            for (int y = (int) Math.ceil(bounds[1]); y < bounds[3]; y++) {
                for (int x = (int) Math.ceil(bounds[0]); x < bounds[2]; x++) {
                    if (screen.playerSlotAt(x, y) == slot) {
                        return new int[] {x, y};
                    }
                }
            }
        }
        return null;
    }

    private static void rotatePreview(VolumeScreen screen) {
        screen.mouseClicked(20, 40, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        screen.mouseDragged(60, 40, GLFW.GLFW_MOUSE_BUTTON_RIGHT, 40, 0);
        screen.mouseReleased(60, 40, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
    }

    private static void drag(VolumeScreen screen, int[] source, int[] target) {
        screen.mouseClicked(source[0], source[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
        screen.mouseDragged(
                target[0],
                target[1],
                GLFW.GLFW_MOUSE_BUTTON_LEFT,
                target[0] - source[0],
                target[1] - source[1]);
        screen.mouseReleased(target[0], target[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    private static int[] visibleBag(VolumeScreen screen, String item) {
        var bounds = screen.bagBounds(item);
        if (bounds != null) {
            for (int y = (int) Math.ceil(bounds[1]); y < bounds[3]; y++) {
                for (int x = (int) Math.ceil(bounds[0]); x < bounds[2]; x++) {
                    if (screen.bagHit(item, x, y)) {
                        return new int[] {x, y};
                    }
                }
            }
        }
        return null;
    }

    private static boolean dragWornToContainer(
            MinecraftClient client, VolumeModule module, String item, String target) {
        var screen = (VolumeScreen) client.currentScreen;
        int[] source = visibleBag(screen, item);
        if (source == null) {
            rotatePreview(screen);
            return false;
        }
        screen.mouseClicked(source[0], source[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
        screen.mouseDragged(source[0] + 5, source[1], GLFW.GLFW_MOUSE_BUTTON_LEFT, 5, 0);
        require(screen.selectedItem().equals(item), "身上容器未捕获物品拖动");
        var view = screen.viewport(target);
        for (int y = view.y(); y < view.bottom(); y += 2) {
            for (int x = view.x(); x < view.right(); x += 2) {
                var cell = screen.cellAt(target, x, y);
                if (cell != null
                        && Volumes.fits(
                                module.state().getItemsList(),
                                target,
                                module.container(target).getSize(),
                                cell,
                                module.container(item).getOuterSize(),
                                item)) {
                    screen.mouseDragged(
                            x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT, x - source[0], y - source[1]);
                    screen.mouseReleased(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    require(module.busy(), "身上容器拖入另一容器未提交");
                    return true;
                }
            }
        }
        throw new IllegalStateException("没有可收纳草包的空间");
    }

    private static boolean dragContainerToBody(
            MinecraftClient client, String item, String sourceContainer, Mount mount) {
        var screen = (VolumeScreen) client.currentScreen;
        int[] source = null;
        if (sourceContainer == null) {
            source = visibleBag(screen, item);
        } else {
            var view = screen.viewport(sourceContainer);
            for (int y = view.y(); y < view.bottom() && source == null; y += 2) {
                for (int x = view.x(); x < view.right(); x += 2) {
                    if (screen.itemAt(sourceContainer, x, y).equals(item)) {
                        source = new int[] {x, y};
                        break;
                    }
                }
            }
        }
        if (source == null) {
            rotatePreview(screen);
            return false;
        }
        for (int y = 40; y < screen.height - 50; y += 2) {
            for (int x = 25; x < screen.width / 3; x += 2) {
                var placement = screen.containerPlacementAt(x, y, item);
                int slot = screen.playerSlotAt(x, y);
                if (placement != null
                        && (slot < 0 || PlayerSlots.isArmor(slot))
                        && (mount == null
                                ? placement.getWaistAngleDegrees() != 45
                                : placement.getMount() == mount)) {
                    drag(screen, source, new int[] {x, y});
                    return true;
                }
            }
        }
        rotatePreview(screen);
        return false;
    }

    private static int[] visibleHand(VolumeScreen screen, int slot) {
        // 沿真正投影的手掌查找可见像素；被身体挡住时先旋转人物，下一帧再命中。
        var bounds = screen.handBounds(slot);
        if (bounds != null) {
            for (int y = (int) Math.ceil(bounds[1]); y < bounds[3]; y++) {
                for (int x = (int) Math.ceil(bounds[0]); x < bounds[2]; x++) {
                    if (screen.handAt(x, y) == slot) {
                        return new int[] {x, y};
                    }
                }
            }
        }
        screen.mouseClicked(20, 40, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        screen.mouseDragged(60, 40, GLFW.GLFW_MOUSE_BUTTON_RIGHT, 40, 0);
        screen.mouseReleased(60, 40, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        return null;
    }

    private boolean dragItemToHand(
            MinecraftClient client, String item, String container, int slot) {
        var screen = (VolumeScreen) client.currentScreen;
        var target = visibleHand(screen, slot);
        if (target == null) {
            return false;
        }
        var view = screen.viewport(container);
        for (int y = view.y(); y < view.bottom(); y += 2) {
            for (int x = view.x(); x < view.right(); x += 2) {
                if (screen.itemAt(container, x, y).equals(item)) {
                    screen.mouseClicked(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    require(screen.selectedItem().equals(item), "手部拖放没有选中来源物品");
                    cursor(client, target[0], target[1]);
                    screen.mouseDragged(
                            target[0],
                            target[1],
                            GLFW.GLFW_MOUSE_BUTTON_LEFT,
                            target[0] - x,
                            target[1] - y);
                    screen.mouseReleased(target[0], target[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    return true;
                }
            }
        }
        // 权威回执可能早于下一帧投影；先等一帧，仍被其他物品遮住时旋转内景再拾取。
        itemPickAttempts++;
        if (itemPickAttempts > 12) {
            throw new IllegalStateException("旋转内景后仍找不到拖向手部的物品");
        }
        if (itemPickAttempts > 1) {
            int x = view.x() + view.width() / 2;
            int y = view.y() + view.height() / 2;
            screen.mouseClicked(x, y, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            screen.mouseDragged(x + 30, y, GLFW.GLFW_MOUSE_BUTTON_RIGHT, 30, 0);
            screen.mouseReleased(x + 30, y, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        }
        return false;
    }

    private static boolean dragHandIntoContainer(
            MinecraftClient client, VolumeModule module, int slot, String container) {
        var screen = (VolumeScreen) client.currentScreen;
        var source = visibleHand(screen, slot);
        if (source == null) {
            return false;
        }
        var hand = module.playerSlot(slot);
        require(hand != null && !hand.getStackNbt().isEmpty(), "手持快照缺失");
        screen.mouseClicked(source[0], source[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
        require(screen.selectedItem().equals(VolumeModule.playerSlotPreviewId(slot)), "手掌未捕获拖动");
        var view = screen.viewport(container);
        for (int y = view.y(); y < view.bottom(); y += 2) {
            for (int x = view.x(); x < view.right(); x += 2) {
                var cell = screen.cellAt(container, x, y);
                if (cell != null
                        && Volumes.fits(
                                module.state().getItemsList(),
                                container,
                                module.container(container).getSize(),
                                cell,
                                hand.getBaseSize(),
                                "")) {
                    cursor(client, x, y);
                    screen.mouseDragged(
                            x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT, x - source[0], y - source[1]);
                    screen.mouseReleased(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    require(module.busy(), "从手掌拖到内景没有开始转移");
                    return true;
                }
            }
        }
        throw new IllegalStateException("找不到容纳手持物品的三维格位");
    }

    private void selectSword(VolumeScreen screen) {
        // 从可见内景寻找剑的投影，走鼠标点击而不是直接写选中状态。
        var source = screen.viewport(pouch);
        boolean picked = false;
        outer:
        for (int y = source.y(); y < source.bottom(); y += 2) {
            for (int x = source.x(); x < source.right(); x += 2) {
                if (screen.itemAt(pouch, x, y).equals(sword)) {
                    screen.mouseClicked(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    picked = true;
                    break outer;
                }
            }
        }
        require(picked && screen.selectedItem().equals(sword), "三维物品命中失败");
    }

    private static java.util.List<Cell> sampleCells(VolumeScreen screen, String container) {
        // 对实际投影命中采样，验证相机操作确实改变了几何，而非只改某个状态变量。
        var bounds = screen.viewport(container);
        var cells = new java.util.ArrayList<Cell>();
        for (int y = bounds.y(); y < bounds.bottom(); y += 8) {
            for (int x = bounds.x(); x < bounds.right(); x += 8) {
                cells.add(screen.cellAt(container, x, y));
            }
        }
        return cells;
    }

    private static void dragLayerHandle(
            VolumeScreen screen, String container, boolean lower, int from, int to) {
        // 从真实窗口坐标拖动两个滑块，连同捕获和释放一起验收，不直接设置显示状态。
        var bounds = screen.layerRangeBounds(container);
        int left = bounds.x() + 8;
        int width = bounds.width() - 16 - 34;
        int y = bounds.y() + bounds.height() / 2 + (lower ? 5 : -4);
        int start = left + Math.round(width * from / 3f);
        int end = left + Math.round(width * to / 3f);
        screen.mouseClicked(start, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        screen.mouseDragged(end, y, GLFW.GLFW_MOUSE_BUTTON_LEFT, end - start, 0);
        screen.mouseReleased(end, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    private static void dragEmptySpace(VolumeScreen screen, String container) {
        // 当前仍选中剑；拖空白处只能转相机，不能沿用点击放置的提交路径。
        var bounds = screen.viewport(container);
        for (int y = bounds.y(); y < bounds.bottom() - 20; y += 2) {
            for (int x = bounds.x(); x < bounds.right() - 40; x += 2) {
                if (screen.itemAt(container, x, y).isEmpty()) {
                    screen.mouseClicked(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    screen.mouseDragged(x + 40, y + 20, GLFW.GLFW_MOUSE_BUTTON_LEFT, 40, 20);
                    screen.mouseReleased(x + 40, y + 20, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    return;
                }
            }
        }
        throw new IllegalStateException("找不到可拖动的内景空白处");
    }

    private int rotateWithReboundKey(VolumeScreen screen, VolumeModule module) {
        screen.keyPressed(GLFW.GLFW_KEY_T, 0, 0);
        // 重绑定后只响应新键，证明并非在屏幕中硬编码 R。
        module.rotateYaw()
                .setBoundKey(
                        net.minecraft.client.util.InputUtil.Type.KEYSYM.createFromCode(
                                GLFW.GLFW_KEY_Y));
        screen.keyPressed(GLFW.GLFW_KEY_Y, 0, 0);
        module.rotateYaw()
                .setBoundKey(
                        net.minecraft.client.util.InputUtil.Type.KEYSYM.createFromCode(
                                GLFW.GLFW_KEY_R));
        int orientation =
                OrthogonalRotation.turn(OrthogonalRotation.turn(0, true, false), false, false);
        require(screen.selectedOrientation() == orientation, "可配置旋转键未应用");
        return orientation;
    }

    private void dropSwordIntoBasket(
            MinecraftClient client, VolumeScreen screen, VolumeModule module, int orientation) {
        // 在目标内景寻找能容纳旋转物品的真实格位，再拖动并释放。
        var state = module.state();
        var item =
                state.getItemsList().stream()
                        .filter(i -> i.getId().equals(sword))
                        .findFirst()
                        .orElseThrow();
        var size = OrthogonalRotation.size(Volumes.baseSize(item), orientation);
        var target = screen.viewport(basket);
        boolean dropped = false;
        outer:
        for (int y = target.y(); y < target.bottom(); y += 2) {
            for (int x = target.x(); x < target.right(); x += 2) {
                var cell = screen.cellAt(basket, x, y);
                if (cell != null
                        && Volumes.fits(
                                state.getItemsList(),
                                basket,
                                module.container(basket).getSize(),
                                cell,
                                size,
                                sword)) {
                    cursor(client, x, y);
                    screen.mouseDragged(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT, 100, 0);
                    screen.mouseReleased(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    dropped = true;
                    break outer;
                }
            }
        }
        require(dropped && module.busy(), "旋转后跨窗口拖放未启动");
    }

    private static void clickBag(MinecraftClient client, VolumeScreen screen, String id) {
        // 使用实际几何命中，窗口布局和玩家旋转变化时不依赖旧版固定坐标。
        var bounds = screen.bagBounds(id);
        require(bounds != null, "没有装备模型变换");
        for (int y = (int) bounds[1]; y < bounds[3]; y++) {
            for (int x = (int) bounds[0]; x < bounds[2]; x++) {
                if (screen.bagHit(id, x, y)) {
                    cursor(client, x, y);
                    screen.mouseClicked(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    screen.mouseReleased(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    return;
                }
            }
        }
        throw new IllegalStateException("装备模型不能命中");
    }

    private void next(Step nextStep) {
        // 等待帧数按步骤重新计数，日志用步骤名定位停在哪个交互。
        step = nextStep;
        ticks = 0;
        itemPickAttempts = 0;
        System.out.println("BS3D_SMOKE step=" + step);
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalStateException(detail);
        }
    }

    private static void slot(MinecraftClient client, int index) {
        client.player.getInventory().selectedSlot = index;
        client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(index));
    }

    private static void cursor(MinecraftClient client, double x, double y) {
        double scale = client.getWindow().getScaleFactor();
        GLFW.glfwSetCursorPos(client.getWindow().getHandle(), x * scale, y * scale);
    }

    private static void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(
                client.runDirectory, name + ".png", client.getFramebuffer(), message -> {});
    }

    private void finish(MinecraftClient client, String text) throws java.io.IOException {
        Files.writeString(client.runDirectory.toPath().resolve(phase + "-ok.txt"), text);
        step = Step.FINISHED;
        client.world.disconnect();
        client.disconnect();
        client.scheduleStop();
    }
}
