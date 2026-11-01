package dev.kizuna.backpack3d.smoke;

import java.util.UUID;
import dev.kizuna.backpack3d.client.WorkspaceBackpackProbe;
import dev.kizuna.backpack3d.client.ModelResources;
import dev.kizuna.backpack3d.client.VolumeModule;
import dev.kizuna.backpack3d.client.VolumeScreen;
import dev.kizuna.backpack3d.protocol.OrthogonalRotation;
import dev.kizuna.backpack3d.protocol.PlayerSlots;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Cell;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Phase;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.State;
import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InventoryUiClient;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;

/** 真实渲染、即时转移、拒绝恢复、模型落点及重启恢复的集成验收。 */
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
    private int instantStep;
    private int instantTicks;

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
                    if (client.gui.screen() instanceof AccessibilityOnboardingScreen) {
                        client.gui.setScreen(new TitleScreen());
                    }
                    if (!connecting
                            && client.gui.screen() instanceof TitleScreen
                            && client.gui.overlay() == null) {
                        connecting = true;
                        ConnectScreen.startConnecting(
                                new TitleScreen(),
                                client,
                                ServerAddress.parseString(ADDRESS),
                                new ServerData("3D smoke", ADDRESS, ServerData.Type.OTHER),
                                false,
                                null);
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
                                                                        .equals("bs3d:herb_pouch"))
                                        .findFirst()
                                        .orElseThrow()
                                        .getId();
                        basket =
                                module.state().getContainersList().stream()
                                        .filter(
                                                value ->
                                                        value.getWorn()
                                                                && value.getModel()
                                                                        .equals("bs3d_smoke:lid_basket"))
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
                    client.gui.toastManager().clear();
                    try {
                        if (phase == null) {
                            phase =
                                    Files.readString(
                                                    client.gameDirectory
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

    private void first(Minecraft client, VolumeModule module) throws java.io.IOException {
        var state = module.state();
        var transfer = state.getTransfer();
        switch (step) {
            case WAIT_GROUND -> {
                if (!client.player.onGround()
                        || !client.player.getMainHandItem().is(Items.IRON_SWORD)) {
                    return;
                }
                client.gui.setScreen(new WorldEquipmentProbe(module));
                next(Step.VERIFY_WORLD_EQUIPMENT);
            }
            case VERIFY_WORLD_EQUIPMENT -> {
                if (!((WorldEquipmentProbe) client.gui.screen()).done()) {
                    return;
                }
                client.gui.setScreen(new PreviewDepthProbe(module));
                next(Step.VERIFY_PREVIEW_DEPTH);
            }
            case VERIFY_PREVIEW_DEPTH -> {
                if (((PreviewDepthProbe) client.gui.screen()).done()) {
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
                var screen = (VolumeScreen) client.gui.screen();
                if (screen.bagBounds(pouch) == null) {
                    return;
                }
                if (!rotatedScene) {
                    require(screen.selectedItem().isEmpty(), "未选物品验收前已有选中物品");
                    require(!screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_R, 0, 0)), "未选物品时背包仍消费 R 键");
                    require(!screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_T, 0, 0)), "未选物品时背包仍消费 T 键");
                    initialBagBounds = screen.bagBounds(pouch);
                    rotatedScene = true;
                    ticks = 0;
                    screen.mouseClicked(mouse(20, 100, GLFW.GLFW_MOUSE_BUTTON_RIGHT), false);
                    screen.mouseDragged(mouse(75, 108, GLFW.GLFW_MOUSE_BUTTON_RIGHT), 55, 8);
                    screen.mouseReleased(mouse(75, 108, GLFW.GLFW_MOUSE_BUTTON_RIGHT));
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
                if (((VolumeScreen) client.gui.screen()).openContainers().contains(pouch)
                        && ticks > 25) {
                    require(
                            java.util.Arrays.equals(
                                    basketBeforeOpening,
                                    ((VolumeScreen) client.gui.screen()).bagBounds(basket)),
                            "打开灵草囊改变了人物取景或缩放");
                    shot(client, "02-open-pouch");
                    module.move(UUID.randomUUID().toString(), pouch, Cell.getDefaultInstance(),
                            true /* automatic */);
                    next(Step.START_CANCEL_TEST);
                }
            }
            case START_CANCEL_TEST -> {
                if (transfer.getPhase() == Phase.REJECTED && !module.busy()) {
                    require(state.getRevision() == 2 && storedItems(state).isEmpty(), "拒绝改变了库存");
                    require(client.player.getMainHandItem().is(Items.IRON_SWORD), "拒绝未恢复来源");
                    client.gui.screen().keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
                    next(Step.VERIFY_ESCAPE_CANCEL);
                }
            }
            case VERIFY_ESCAPE_CANCEL -> {
                if (ticks > 25 && client.gui.screen() == null) {
                    require(state.getRevision() == 2, "关闭页面改变了被拒绝的操作");
                    module.open();
                    ((VolumeScreen) client.gui.screen()).openContainer(pouch);
                    next(Step.DEPOSIT_SWORD);
                }
            }
            case DEPOSIT_SWORD -> {
                if (((VolumeScreen) client.gui.screen()).openContainers().contains(pouch)
                        && ticks > 30) {
                    if (dragHandIntoContainer(
                            client, module, client.player.getInventory().getSelectedSlot(), pouch)) {
                        next(Step.VERIFY_SWORD);
                    }
                }
            }
            case VERIFY_SWORD -> {
                if (state.getRevision() == 3 && !module.busy()) {
                    sword = storedItems(state).get(0).getId();
                    require(storedItems(state).get(0).getSize().getHeight() == 2, "工具三维尺寸失效");
                    require(module.stack(sword).getDamageValue() == 17, "存入丢失 NBT");
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
                if (state.getRevision() == 4 && !module.busy()) {
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
                if (state.getRevision() == 5 && !module.busy()) {
                    ((VolumeScreen) client.gui.screen()).openContainer(basket);
                    next(Step.DRAG_WINDOW);
                }
            }
            case DRAG_WINDOW -> {
                var screen = (VolumeScreen) client.gui.screen();
                if (screen.openContainers().size() == 2 && ticks > 30) {
                    var bounds = screen.window(pouch).bounds();
                    screen.mouseClicked(mouse(
                            bounds.x() + 8, bounds.y() + 8, GLFW.GLFW_MOUSE_BUTTON_LEFT), false);
                    screen.mouseDragged(mouse(
                            bounds.x() + 8, bounds.y() + 24, GLFW.GLFW_MOUSE_BUTTON_LEFT), 0, 16);
                    screen.mouseReleased(mouse(
                            bounds.x() + 8, bounds.y() + 24, GLFW.GLFW_MOUSE_BUTTON_LEFT));
                    require(screen.window(pouch).bounds().y() == bounds.y() + 16, "拖窗失效");
                    dragLayerHandle(screen, basket, true, 0, 1);
                    dragLayerHandle(screen, basket, false, 3, 2);
                    next(Step.VERIFY_LAYER_RANGE);
                }
            }
            case VERIFY_LAYER_RANGE -> {
                if (ticks > 3) {
                    var screen = (VolumeScreen) client.gui.screen();
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
                    require(state.getRevision() == 5 && !module.busy(), "范围条拖动意外提交库存操作");
                    shot(client, "layer-range-middle");
                    dragLayerHandle(screen, basket, true, 1, 0);
                    dragLayerHandle(screen, basket, false, 2, 3);
                    next(Step.RESTORE_LAYER_RANGE);
                }
            }
            case RESTORE_LAYER_RANGE -> {
                if (ticks > 3) {
                    var screen = (VolumeScreen) client.gui.screen();
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
                var screen = (VolumeScreen) client.gui.screen();
                if (ticks == 5) {
                    var b = screen.window(pouch).bounds();
                    screen.mouseClicked(mouse(
                            b.x() + b.width() - 27, b.y() + 8, GLFW.GLFW_MOUSE_BUTTON_LEFT), false);
                    require(screen.window(pouch).minimized(), "最小化失效");
                }
                if (ticks == 8) {
                    // 恢复按钮与标题分开，点击小卡片右侧的独立恢复区域。
                    screen.mouseClicked(mouse(90, screen.height - 42, GLFW.GLFW_MOUSE_BUTTON_LEFT), false);
                    require(!screen.window(pouch).minimized(), "恢复窗口失效");
                }
                if (ticks > 12) {
                    shot(client, "04-two-windows-interior");
                    selectSword(screen);
                    screen.mouseReleased(mouse(0, 0, GLFW.GLFW_MOUSE_BUTTON_LEFT));
                    cellsBeforeDrag = sampleCells(screen, basket);
                    dragEmptySpace(screen, basket);
                    require(!module.busy(), "拖动空白处意外启动物品转移");
                    next(Step.VERIFY_EMPTY_DRAG);
                }
            }
            case VERIFY_EMPTY_DRAG -> {
                if (ticks > 3) {
                    var screen = (VolumeScreen) client.gui.screen();
                    require(!cellsBeforeDrag.equals(sampleCells(screen, basket)), "左拖空白处没有旋转内景");
                    require(
                            screen.selectedItem().equals(sword) && !module.busy(),
                            "旋转场景改变了选中物品或库存");
                    screen.mouseClicked(mouse(screen.width - 22, 22, GLFW.GLFW_MOUSE_BUTTON_LEFT), false);
                    next(Step.VERIFY_HELP);
                }
            }
            case VERIFY_HELP -> {
                if (ticks > 3) {
                    var screen = (VolumeScreen) client.gui.screen();
                    int previousOrientation = screen.selectedOrientation();
                    screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_T, 0, 0));
                    require(screen.selectedOrientation() == previousOrientation, "帮助面板没有拦截旋转输入");
                    screen.mouseClicked(mouse(screen.width - 100, 100, GLFW.GLFW_MOUSE_BUTTON_LEFT), false);
                    require(!module.busy(), "帮助面板点击穿透到了物品");
                    shot(client, "08-controls-help");
                    screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
                    require(client.gui.screen() == screen, "ESC 关闭帮助时意外关闭背包");
                    selectSword(screen);
                    int orientation = rotateWithReboundKey(screen, module);
                    dropSwordIntoBasket(client, screen, module, orientation);
                    next(Step.CAPTURE_TRANSFER);
                }
            }
            case CAPTURE_TRANSFER -> {
                if (transfer.getPhase() == Phase.COMPLETED && !module.busy()) {
                    shot(client, "05-cross-container");
                    next(Step.VERIFY_TRANSFER);
                }
            }
            case VERIFY_TRANSFER -> {
                if (state.getRevision() == 6 && !module.busy()) {
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
                if (state.getRevision() == 7) {
                    require(module.mount(pouch) == Mount.WAIST_LEFT_FRONT, "灵草囊挂点未同步");
                    module.cycleMount(basket);
                    next(Step.CLOSE_SCREEN);
                }
            }
            case CLOSE_SCREEN -> {
                if (state.getRevision() == 8 && ticks > 10) {
                    require(module.mount(basket) == Mount.TORSO_FRONT, "背篓前后挂点未同步");
                    shot(client, "07-equipment-front");
                    ((VolumeScreen) client.gui.screen()).onClose();
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
                            "revision=8; world-equipment; worn-model; scene-rotation;"
                                + " model-picking; two-windows; drag-window; minimize-restore;"
                                + " rotated-drag-drop; remapped-keys; equipment; instant-transfer;"
                                + " rejected-unchanged; close-after-commit; tags; NBT");
                }
            }
            default -> {}
        }
    }

    private void second(Minecraft client, VolumeModule module) throws java.io.IOException {
        var state = module.state();
        switch (step) {
            case WAIT_GROUND -> {
                if (!client.player.onGround()) {
                    return;
                }
                require(state.getRevision() == 8 && storedItems(state).size() == 3, "重启恢复失败");
                sword =
                        state.getItemsList().stream()
                                .filter(item -> module.stack(item.getId()).is(Items.IRON_SWORD))
                                .findFirst()
                                .orElseThrow()
                                .getId();
                require(module.stack(sword).getDamageValue() == 17, "重启 NBT 丢失");
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
                ((VolumeScreen) client.gui.screen()).openContainer(basket);
                next(Step.WITHDRAW_SWORD);
            }
            case WITHDRAW_SWORD -> {
                if (ticks > 35) {
                    if (dragItemToHand(client, sword, basket, Inventory.SLOT_OFFHAND)) {
                        next(Step.DEPOSIT_OFFHAND_SWORD);
                    }
                }
            }
            case DEPOSIT_OFFHAND_SWORD -> {
                if (state.getRevision() == 9 && !module.busy()) {
                    require(
                            client.player.getOffhandItem().is(Items.IRON_SWORD)
                                    && client.player.getOffhandItem().getDamageValue() == 17,
                            "拖到副手后真实栈或 NBT 丢失");
                    require(client.player.getMainHandItem().isEmpty(), "指定副手却放进主手");
                    shot(client, "09-offhand-withdraw");
                    // 切换惯用手后仍按真实骨架拖物品，验证左右手没有硬编码为主副手。
                    client.options.mainHand().set(HumanoidArm.LEFT);
                    client.player.setMainArm(HumanoidArm.LEFT);
                    next(Step.DRAG_OFFHAND_SWORD);
                }
            }
            case DRAG_OFFHAND_SWORD -> {
                if (ticks > 4
                        && dragHandIntoContainer(
                                client, module, Inventory.SLOT_OFFHAND, basket)) {
                    next(Step.WITHDRAW_MAINHAND_SWORD);
                }
            }
            case WITHDRAW_MAINHAND_SWORD -> {
                if (state.getRevision() == 10 && !module.busy()) {
                    require(client.player.getOffhandItem().isEmpty(), "从副手存入后仍持有同一栈");
                    sword =
                            state.getItemsList().stream()
                                    .filter(
                                            item ->
                                                    module.stack(item.getId())
                                                            .is(Items.IRON_SWORD))
                                    .findFirst()
                                    .orElseThrow()
                                    .getId();
                    if (dragItemToHand(
                            client, sword, basket, client.player.getInventory().getSelectedSlot())) {
                        next(Step.HAND_TO_OFFHAND);
                    }
                }
            }
            case HAND_TO_OFFHAND -> {
                if (state.getRevision() == 11
                        && !module.busy()
                        && dragPlayerSlot(
                                client,
                                client.player.getInventory().getSelectedSlot(),
                                Inventory.SLOT_OFFHAND)) {
                    next(Step.HAND_TO_MAINHAND);
                }
            }
            case HAND_TO_MAINHAND -> {
                if (state.getRevision() == 12 && !module.busy()) {
                    require(
                            client.player.getMainHandItem().isEmpty()
                                    && client.player.getOffhandItem().is(Items.IRON_SWORD),
                            "左手到右手转移未完成");
                    if (dragPlayerSlot(
                            client,
                            Inventory.SLOT_OFFHAND,
                            client.player.getInventory().getSelectedSlot())) {
                        next(Step.ARMOR_TO_OFFHAND);
                    }
                }
            }
            case ARMOR_TO_OFFHAND -> {
                if (state.getRevision() == 13 && !module.busy()) {
                    require(
                            client.player.getMainHandItem().getDamageValue() == 17
                                    && client.player.getOffhandItem().isEmpty(),
                            "换手往返丢失 NBT");
                    if (dragPlayerSlot(
                            client,
                            PlayerSlots.armorSlot(EquipmentSlot.HEAD),
                            Inventory.SLOT_OFFHAND)) {
                        next(Step.ARMOR_TO_HEAD);
                    }
                }
            }
            case ARMOR_TO_HEAD -> {
                if (state.getRevision() == 14 && !module.busy()) {
                    require(
                            client.player.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                                    && client.player.getOffhandItem().is(Items.IRON_HELMET),
                            "头盔拖到手中失败");
                    if (dragPlayerSlot(
                            client,
                            Inventory.SLOT_OFFHAND,
                            PlayerSlots.armorSlot(EquipmentSlot.HEAD))) {
                        next(Step.BAG_TO_BASKET);
                    }
                }
            }
            case BAG_TO_BASKET -> {
                if (state.getRevision() == 15 && !module.busy()) {
                    require(
                            client.player
                                    .getItemBySlot(EquipmentSlot.HEAD)
                                    .is(Items.IRON_HELMET),
                            "头盔重新穿戴失败");
                    if (dragWornToContainer(client, module, pouch, basket)) {
                        next(Step.BAG_TO_WAIST);
                    }
                }
            }
            case BAG_TO_WAIST -> {
                if (state.getRevision() == 16 && !module.busy()) {
                    require(
                            module.container(pouch).getParent().equals(basket)
                                    && !module.container(pouch).getWorn(),
                            "灵草囊收纳后仍装备在身体上");
                    if (dragContainerToBody(client, pouch, basket, null)) {
                        next(Step.BASKET_TO_BACK);
                    }
                }
            }
            case BASKET_TO_BACK -> {
                if (state.getRevision() == 17 && !module.busy()) {
                    require(module.container(pouch).getWorn(), "灵草囊没有重新挂上腰部");
                    require(module.container(pouch).getWaistAngleDegrees() != 45, "腰部位置仍锁定旧挂点");
                    if (dragContainerToBody(client, basket, null, Mount.TORSO_BACK)) {
                        next(Step.CANCEL_BEFORE_ACK);
                    }
                }
            }
            case CANCEL_BEFORE_ACK -> {
                if (state.getRevision() == 18 && !module.busy()) {
                    require(
                            client.player.getInventory().getNonEquipmentItems().stream()
                                    .anyMatch(
                                            stack ->
                                                    stack.is(Items.IRON_SWORD)
                                                            && stack.getDamageValue() == 17),
                            "取回丢失真实栈");
                    var block =
                            state.getItemsList().stream()
                                    .filter(item -> module.stack(item.getId()).is(Items.STONE))
                                    .findFirst()
                                    .orElseThrow();
                    module.move(block.getId(), pouch, Cell.getDefaultInstance(), true);
                    // 发送后立即关窗，验证已发送请求仍即时完成。
                    client.gui.screen().onClose();
                    next(Step.VERIFY_EARLY_CANCEL);
                }
            }
            case VERIFY_EARLY_CANCEL -> {
                if (ticks > 30 && !module.busy()) {
                    require(state.getRevision() == 19 && state.getTransfer().getPhase() == Phase.COMPLETED,
                            "关窗撤销了已经提交的即时转移");
                    module.open();
                    var block = state.getItemsList().stream()
                            .filter(item -> module.stack(item.getId()).is(Items.STONE))
                            .findFirst()
                            .orElseThrow();
                    module.move(block.getId(), basket, Cell.getDefaultInstance(), true /* automatic */);
                    next(Step.DISCONNECT_DURING_TRANSFER);
                }
            }
            case DISCONNECT_DURING_TRANSFER -> {
                if (verifyInstantInputs(client, module)) {
                    finish(client, "revision=24; restart-persistence; hand-to-hand; armor-drag;"
                            + " portable-bag; waist-drag; torso-drag; instant-transfer; close-after-commit;"
                            + " hand-drag-both-directions; left-handed; shift-both-directions;"
                            + " closed-model-drop; gravity-preserved");
                }
            }
            default -> {}
        }
    }

    private boolean verifyInstantInputs(Minecraft client, VolumeModule module) {
        // 使用 Xvfb 的真实 Shift 键状态，鼠标仍通过实际 Screen 分派。
        if (module.busy()) {
            return false;
        }
        var screen = (VolumeScreen) client.gui.screen();
        instantTicks++;
        try {
            boolean progressed;
            if (instantStep < 5) {
                progressed = verifyShiftInputs(client, module, screen);
            } else if (instantStep < 7) {
                progressed = verifyModelDrag(client, module, screen);
            } else {
                progressed = verifyModelResult(client, module, screen);
                if (instantStep == 8) {
                    return progressed;
                }
            }
            if (!progressed) {
                return false;
            }
        } finally {
            // 未按住 Shift 的步骤不留下上一操作的按键状态。
            if (instantStep > 4) {
                SmokeShift.release();
            }
        }
        instantStep++;
        instantTicks = 0;
        return false;
    }


    private boolean verifyShiftInputs(Minecraft client, VolumeModule module, VolumeScreen screen) {
        // 通过真实 Shift 键状态验证腰挂优先存入及原版快捷取回。
        switch (instantStep) {
            case 0 -> {
                if (module.state().getRevision() != 20) {
                    return false;
                }
                SmokeShift.press();
            }
            case 1 -> {
                if (!client.hasShiftDown()) {
                    return false;
                }
                int[] source = visibleSlot(screen, client.player.getInventory().getSelectedSlot());
                if (source == null) {
                    rotatePreview(screen);
                    return false;
                }
                screen.mouseClicked(mouse(source[0], source[1], GLFW.GLFW_MOUSE_BUTTON_LEFT), false);
                screen.mouseReleased(mouse(source[0], source[1], GLFW.GLFW_MOUSE_BUTTON_LEFT));
                SmokeShift.release();
            }
            case 2 -> {
                if (module.state().getRevision() != 21) {
                    return false;
                }
                var stored = storedItems(module.state()).stream()
                        .filter(item -> module.stack(item.getId()).is(Items.IRON_SWORD))
                        .findFirst().orElseThrow();
                require(stored.getContainer().equals(pouch), "Shift 未优先选择腰挂容器");
                sword = stored.getId();
                screen.openContainer(pouch);
            }
            case 3 -> {
                if (instantTicks < 30 || screen.viewport(pouch) == null) {
                    return false;
                }
                SmokeShift.press();
            }
            case 4 -> {
                if (!client.hasShiftDown()) {
                    return false;
                }
                selectSword(screen);
                SmokeShift.release();
            }
            default -> throw new IllegalStateException("未知即时输入验收步骤");
        }
        return true;
    }


    private boolean verifyModelDrag(Minecraft client, VolumeModule module, VolumeScreen screen) {
        // 等待模型合盖后，以真实命中坐标提交自动放入。
        switch (instantStep) {
            case 5 -> {
                if (module.state().getRevision() != 22) {
                    return false;
                }
                int swordSlot = -1;
                for (int index = 0; index < Inventory.INVENTORY_SIZE; index++) {
                    if (client.player.getInventory().getItem(index).is(Items.IRON_SWORD)) {
                        swordSlot = index;
                        break;
                    }
                }
                require(swordSlot >= 0, "Shift 取出未进入原版槽位");
                slot(client, swordSlot);
                screen.closeContainer(pouch);
            }
            case 6 -> {
                if (instantTicks < 30) {
                    return false;
                }
                int[] source = visibleSlot(screen, client.player.getInventory().getSelectedSlot());
                int[] target = visibleBag(screen, basket);
                if (source == null || target == null) {
                    rotatePreview(screen);
                    return false;
                }
                require(!screen.openContainers().contains(basket), "模型自动放置验收要求合盖");
                screen.mouseClicked(mouse(source[0], source[1], GLFW.GLFW_MOUSE_BUTTON_LEFT), false);
                screen.mouseDragged(mouse(target[0], target[1], GLFW.GLFW_MOUSE_BUTTON_LEFT),
                        target[0] - source[0], target[1] - source[1]);
                screen.mouseReleased(mouse(target[0], target[1], GLFW.GLFW_MOUSE_BUTTON_LEFT));
            }
            default -> throw new IllegalStateException("未知即时输入验收步骤");
        }
        return true;
    }


    private boolean verifyModelResult(Minecraft client, VolumeModule module, VolumeScreen screen) {
        // 权威回执必须保留目标容器并允许立即进行下一次操作。
        switch (instantStep) {
            case 7 -> {
                if (module.state().getRevision() != 23) {
                    return false;
                }
                var stored = storedItems(module.state()).stream()
                        .filter(item -> module.stack(item.getId()).is(Items.IRON_SWORD))
                        .findFirst().orElseThrow();
                require(stored.getContainer().equals(basket), "拖到合盖模型未自动放入");
                shot(client, "instant-closed-model-drop");
                module.move(stored.getId(), Volumes.PLAYER, Cell.getDefaultInstance(), true /* automatic */);
            }
            case 8 -> {
                return module.state().getRevision() == 24;
            }
            default -> throw new IllegalStateException("未知即时输入验收步骤");
        }
        return true;
    }

    private static java.util.List<Item> storedItems(State state) {
        return state.getItemsList().stream()
                .filter(value -> !value.getContainer().equals(Volumes.WORN))
                .toList();
    }

    private static boolean dragPlayerSlot(Minecraft client, int sourceSlot, int targetSlot) {
        var screen = (VolumeScreen) client.gui.screen();
        int[] source = visibleSlot(screen, sourceSlot);
        int[] target = visibleSlot(screen, targetSlot);
        if (source == null || target == null) {
            rotatePreview(screen);
            return false;
        }
        screen.mouseClicked(mouse(source[0], source[1], GLFW.GLFW_MOUSE_BUTTON_LEFT), false);
        require(
                screen.selectedItem().equals(VolumeModule.playerSlotPreviewId(sourceSlot)),
                "身体部位没有捕获来源：" + sourceSlot);
        screen.mouseDragged(mouse(
                target[0],
                target[1],
                GLFW.GLFW_MOUSE_BUTTON_LEFT),
                target[0] - source[0],
                target[1] - source[1]);
        screen.mouseReleased(mouse(target[0], target[1], GLFW.GLFW_MOUSE_BUTTON_LEFT));
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
        screen.mouseClicked(mouse(20, 40, GLFW.GLFW_MOUSE_BUTTON_RIGHT), false);
        screen.mouseDragged(mouse(60, 40, GLFW.GLFW_MOUSE_BUTTON_RIGHT), 40, 0);
        screen.mouseReleased(mouse(60, 40, GLFW.GLFW_MOUSE_BUTTON_RIGHT));
    }

    private static void drag(VolumeScreen screen, int[] source, int[] target) {
        screen.mouseClicked(mouse(source[0], source[1], GLFW.GLFW_MOUSE_BUTTON_LEFT), false);
        screen.mouseDragged(mouse(
                target[0],
                target[1],
                GLFW.GLFW_MOUSE_BUTTON_LEFT),
                target[0] - source[0],
                target[1] - source[1]);
        screen.mouseReleased(mouse(target[0], target[1], GLFW.GLFW_MOUSE_BUTTON_LEFT));
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
            Minecraft client, VolumeModule module, String item, String target) {
        var screen = (VolumeScreen) client.gui.screen();
        int[] source = visibleBag(screen, item);
        if (source == null) {
            rotatePreview(screen);
            return false;
        }
        screen.mouseClicked(mouse(source[0], source[1], GLFW.GLFW_MOUSE_BUTTON_LEFT), false);
        screen.mouseDragged(mouse(source[0] + 5, source[1], GLFW.GLFW_MOUSE_BUTTON_LEFT), 5, 0);
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
                    screen.mouseDragged(mouse(
                            x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT), x - source[0], y - source[1]);
                    screen.mouseReleased(mouse(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT));
                    require(module.busy(), "身上容器拖入另一容器未提交");
                    return true;
                }
            }
        }
        throw new IllegalStateException("没有可收纳灵草囊的空间");
    }

    private static boolean dragContainerToBody(
            Minecraft client, String item, String sourceContainer, Mount mount) {
        var screen = (VolumeScreen) client.gui.screen();
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
        screen.mouseClicked(mouse(20, 40, GLFW.GLFW_MOUSE_BUTTON_RIGHT), false);
        screen.mouseDragged(mouse(60, 40, GLFW.GLFW_MOUSE_BUTTON_RIGHT), 40, 0);
        screen.mouseReleased(mouse(60, 40, GLFW.GLFW_MOUSE_BUTTON_RIGHT));
        return null;
    }

    private boolean dragItemToHand(
            Minecraft client, String item, String container, int slot) {
        var screen = (VolumeScreen) client.gui.screen();
        var target = visibleHand(screen, slot);
        if (target == null) {
            return false;
        }
        var view = screen.viewport(container);
        for (int y = view.y(); y < view.bottom(); y += 2) {
            for (int x = view.x(); x < view.right(); x += 2) {
                if (screen.itemAt(container, x, y).equals(item)) {
                    screen.mouseClicked(mouse(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT), false);
                    require(screen.selectedItem().equals(item), "手部拖放没有选中来源物品");
                    cursor(client, target[0], target[1]);
                    screen.mouseDragged(mouse(
                            target[0],
                            target[1],
                            GLFW.GLFW_MOUSE_BUTTON_LEFT),
                            target[0] - x,
                            target[1] - y);
                    screen.mouseReleased(mouse(target[0], target[1], GLFW.GLFW_MOUSE_BUTTON_LEFT));
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
            screen.mouseClicked(mouse(x, y, GLFW.GLFW_MOUSE_BUTTON_RIGHT), false);
            screen.mouseDragged(mouse(x + 30, y, GLFW.GLFW_MOUSE_BUTTON_RIGHT), 30, 0);
            screen.mouseReleased(mouse(x + 30, y, GLFW.GLFW_MOUSE_BUTTON_RIGHT));
        }
        return false;
    }

    private static boolean dragHandIntoContainer(
            Minecraft client, VolumeModule module, int slot, String container) {
        var screen = (VolumeScreen) client.gui.screen();
        var source = visibleHand(screen, slot);
        if (source == null) {
            return false;
        }
        var hand = module.playerSlot(slot);
        require(hand != null && !hand.getStackNbt().isEmpty(), "手持快照缺失");
        screen.mouseClicked(mouse(source[0], source[1], GLFW.GLFW_MOUSE_BUTTON_LEFT), false);
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
                    screen.mouseDragged(mouse(
                            x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT), x - source[0], y - source[1]);
                    screen.mouseReleased(mouse(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT));
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
                    screen.mouseClicked(mouse(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT), false);
                    picked = true;
                    break outer;
                }
            }
        }
        require(picked && (Minecraft.getInstance().hasShiftDown()
                || screen.selectedItem().equals(sword)), "三维物品命中失败");
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
        screen.mouseClicked(mouse(start, y, GLFW.GLFW_MOUSE_BUTTON_LEFT), false);
        screen.mouseDragged(mouse(end, y, GLFW.GLFW_MOUSE_BUTTON_LEFT), end - start, 0);
        screen.mouseReleased(mouse(end, y, GLFW.GLFW_MOUSE_BUTTON_LEFT));
    }

    private static void dragEmptySpace(VolumeScreen screen, String container) {
        // 当前仍选中剑；拖空白处只能转相机，不能沿用点击放置的提交路径。
        var bounds = screen.viewport(container);
        for (int y = bounds.y(); y < bounds.bottom() - 20; y += 2) {
            for (int x = bounds.x(); x < bounds.right() - 40; x += 2) {
                if (screen.itemAt(container, x, y).isEmpty()) {
                    screen.mouseClicked(mouse(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT), false);
                    screen.mouseDragged(mouse(x + 40, y + 20, GLFW.GLFW_MOUSE_BUTTON_LEFT), 40, 20);
                    screen.mouseReleased(mouse(x + 40, y + 20, GLFW.GLFW_MOUSE_BUTTON_LEFT));
                    return;
                }
            }
        }
        throw new IllegalStateException("找不到可拖动的内景空白处");
    }

    private int rotateWithReboundKey(VolumeScreen screen, VolumeModule module) throws java.io.IOException {
        // 旋转键不进入原版全局表；打开背包键仍可在控制设置里改键。
        var client = Minecraft.getInstance();
        for (var binding : client.options.keyMappings) {
            String id = binding.getName();
            require(!id.equals("key.bs3d.rotate_yaw"), "左右旋转仍注册为全局按键");
            require(!id.equals("key.bs3d.rotate_pitch"), "上下旋转仍注册为全局按键");
        }
        require(java.util.Arrays.asList(client.options.keyMappings).contains(module.openKey()), "全局背包键丢失");
        screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_T, 0, 0));
        verifyConfiguredRotation(screen);
        int orientation =
                OrthogonalRotation.turn(OrthogonalRotation.turn(0, true, false), false, false);
        require(screen.selectedOrientation() == orientation, "可配置旋转键未应用");
        return orientation;
    }

    private void verifyConfiguredRotation(VolumeScreen screen) throws java.io.IOException {
        // 从真实 TOML 重读新键，旧键不再旋转；隔离世界首次启动没有 config.toml，结束后删掉恢复默认按键。
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("bs3d");
        Path config = directory.resolve("config.toml");
        require(!Files.exists(config), "首次启动仍生成了 config.toml");
        String rebound =
                """
                version = 1
                [controls]
                rotate_yaw = "key.keyboard.y"
                rotate_pitch = "key.keyboard.t"
                """;
        try {
            Files.createDirectories(directory);
            Files.writeString(config, rebound);
            ModelResources.registerPack(ignored -> {});
            int before = screen.selectedOrientation();
            screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_R, 0, 0));
            require(screen.selectedOrientation() == before, "改键后旧 R 键仍旋转物品");
            screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_Y, 0, 0));
            require(screen.selectedOrientation() != before, "TOML 新旋转键未生效");
        } finally {
            Files.delete(config);
            ModelResources.registerPack(ignored -> {});
        }
    }

    private void dropSwordIntoBasket(
            Minecraft client, VolumeScreen screen, VolumeModule module, int orientation) {
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
                    screen.mouseDragged(mouse(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT), 100, 0);
                    screen.mouseReleased(mouse(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT));
                    dropped = true;
                    break outer;
                }
            }
        }
        require(dropped && module.busy(), "旋转后跨窗口拖放未启动");
    }

    private static void clickBag(Minecraft client, VolumeScreen screen, String id) {
        // 使用实际几何命中，窗口布局和玩家旋转变化时不依赖旧版固定坐标。
        var bounds = screen.bagBounds(id);
        require(bounds != null, "没有装备模型变换");
        for (int y = (int) bounds[1]; y < bounds[3]; y++) {
            for (int x = (int) bounds[0]; x < bounds[2]; x++) {
                if (screen.bagHit(id, x, y)) {
                    cursor(client, x, y);
                    screen.mouseClicked(mouse(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT), false);
                    screen.mouseReleased(mouse(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT));
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

    private static void slot(Minecraft client, int index) {
        client.player.getInventory().setSelectedSlot(index);
        client.getConnection().send(new ServerboundSetCarriedItemPacket(index));
    }

    private static void cursor(Minecraft client, double x, double y) {
        double scale = client.getWindow().getGuiScale();
        GLFW.glfwSetCursorPos(client.getWindow().handle(), x * scale, y * scale);
    }

    private static void shot(Minecraft client, String name) {
        Screenshot.grab(
                client.gameDirectory, name + ".png", client.gameRenderer.mainRenderTarget(), 1, message -> {});
    }

    private void finish(Minecraft client, String text) throws java.io.IOException {
        Files.writeString(client.gameDirectory.toPath().resolve(phase + "-ok.txt"), text);
        step = Step.FINISHED;
        client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        client.stop();
    }

    private static MouseButtonEvent mouse(double x, double y, int button) {
        // 合成输入不带修饰键，坐标为 GUI 逻辑像素。
        return new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
    }
}
