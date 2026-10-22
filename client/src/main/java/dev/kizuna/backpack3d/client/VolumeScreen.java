package dev.kizuna.backpack3d.client;

import dev.kizuna.backpack3d.client.InteriorRenderer.Rect;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.ContainerSounds;
import dev.kizuna.backpack3d.protocol.*;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.client.VanillaHudHidden;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.inventoryui.window.UiWindowDefinition;
import dev.kizuna.inventoryui.window.UiWindowManager;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.stream.Collectors;

/** 人物装备预览与多个轻量容器窗口；窗口管理能力复用 Framework。 */
public final class VolumeScreen extends Screen implements VanillaHudHidden {
    // 以下尺寸单位为 GUI 逻辑像素；绘制和输入始终复用同一矩形。
    private static final int MARGIN = 12;
    private static final int HEADER_HEIGHT = 18;
    private static final int CONTROL_HEIGHT = 18;
    private static final int GAP = 4;
    private static final int TEXT_INSET = 5;
    private static final int FOOTER_HEIGHT = 50;
    private static final int TOP = 42;
    private static final int MIN_WINDOW_WIDTH = 158;
    private static final int MIN_WINDOW_HEIGHT = 180;
    private static final int RESTORE_WIDTH = 70;
    private static final int PROGRESS_HEIGHT = 3;
    private static final int DRAG_THRESHOLD_SQUARED_PIXELS = 9;
    private static final float NANOS_PER_SECOND = 1_000_000_000f;
    private static final float MAX_FRAME_SECONDS = 0.05f;
    private static final float PLAYER_CENTER_RATIO = 0.18f;
    private static final float PLAYER_BASELINE_RATIO = 0.77f;
    private static final float PLAYER_SCALE_RATIO = 0.3f;
    private static final float WINDOW_WIDTH_RATIO = 0.32f;
    private static final float WINDOW_START_RATIO = 0.34f;
    private static final String WINDOW_TYPE = "bs3d:interior";
    private static final UiWindowDefinition WINDOW =
            new UiWindowDefinition(
                    WINDOW_TYPE,
                    "bs3d:volume",
                    MIN_WINDOW_WIDTH,
                    MIN_WINDOW_HEIGHT,
                    Set.of(UiWindowDefinition.Capability.WINDOW));
    private final VolumeModule module;
    private final WornPreview player;
    private final ControlsHelp help;
    private final Map<String, BagAnimation> animations = new LinkedHashMap<>();
    private final Map<String, InteriorRenderer> interiors = new HashMap<>();
    private final Map<String, LayerRangeSlider> layerSliders = new HashMap<>();
    private final Map<String, Rect> viewports = new HashMap<>();
    private final List<Button> buttons = new ArrayList<>();
    private UiWindowManager windows;
    private String active = "";
    private String selected = "";
    private PlayerSlotStack selectedPlayerStack;
    private String pressedItem = "";
    private String camera = "";
    private String layerDrag = "";
    private String pendingDropContainer = "";
    private boolean cameraDragged;
    private String pressedBag = "";
    private String hint = "";
    private boolean scenePressed;
    private boolean sceneDragged;
    private boolean dragging;
    private boolean closing;
    private int orientation;
    private long selectionRevision;
    private double pressX;
    private double pressY;
    private long frameTime;
    private final Runnable exit;
    private int scissorX;
    private int scissorY;

    public VolumeScreen(VolumeModule module) {
        // 独立快捷键入口沿用 Minecraft 屏幕生命周期。
        this(module, () -> net.minecraft.client.MinecraftClient.getInstance().setScreen(null));
    }

    VolumeScreen(VolumeModule module, Runnable exit) {
        super(Text.literal("Backpack and Storage 3D"));
        // 工作台提供所属窗口的关闭动作，场景本身不决定全局屏幕去向。
        this.module = module;
        this.exit = exit;
        player = new WornPreview(module);
        help = new ControlsHelp(module);
    }

    void scissorOrigin(int x, int y) {
        // 顶点与命中采用局部坐标，OpenGL 裁剪仍需要屏幕绝对坐标。
        scissorX = x;
        scissorY = y;
    }

    void cancelInput() {
        // 场景被其他工作台窗口遮挡或失焦时撤销未完成的鼠标手势。
        releaseCapture();
        clearSelection();
    }

    @Override
    public void tick() {
        // 鼠标在窗口外松开时游戏可能收不到释放事件，失焦立即撤销残留捕获。
        if (!client.isWindowFocused()) {
            releaseCapture();
        }
    }

    @Override
    protected void init() {
        // 改分辨率时保留窗口身份和相机，框架将外框限制在新视口内。
        if (windows == null) {
            windows = new UiWindowManager(width, height);
        } else {
            windows.resizeViewport(width, height);
        }
        help.resize(width);
        releaseCapture();
        syncContainers();
    }

    private UiWindowManager.WindowKey key(String id) {
        return windows.key(WINDOW_TYPE, id);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 动画按时间推进；服务端计时不受渲染速度和窗口是否最小化影响。
        long now = System.nanoTime();
        float seconds =
                frameTime == 0
                        ? 0
                        : Math.min(MAX_FRAME_SECONDS, (now - frameTime) / NANOS_PER_SECOND);
        frameTime = now;
        syncContainers();
        advanceAnimations(seconds);
        if (closing && animations.values().stream().allMatch(BagAnimation::atRest)) {
            exit.run();
            return;
        }
        if (!selected.isEmpty() && selectionRevision != module.state().getRevision()) {
            clearSelection();
        }
        if (selectedPlayerStack != null
                && !selectedPlayerStack.equals(
                        module.playerSlot(selectedPlayerStack.getPlayerSlot()))) {
            // 原版换手或栈内容变化会使按下时的来源失效，不悄悄换成另一件物品。
            clearSelection();
        }
        buttons.clear();
        viewports.clear();
        // 页面直接保留世界画面；打开、关闭或最小化容器都不移动人物取景。
        player.draw(
                context,
                (int) (width * PLAYER_CENTER_RATIO),
                (int) (height * PLAYER_BASELINE_RATIO),
                (int) (height * PLAYER_SCALE_RATIO),
                mouseX,
                mouseY,
                !closing
                        && !help.captures(mouseX, mouseY)
                        && windows.hitTest(mouseX, mouseY) == null,
                animations);
        for (var window : windows.snapshot()) {
            if (!window.minimized()) {
                drawWindow(context, window, mouseX, mouseY);
            }
        }
        drawRestores(context, mouseX, mouseY);
        drawProgress(context);
        // 附属只提供文本，不接管场景、窗口输入或原版 HUD。
        context.drawTextWithShadow(textRenderer, ContainerDetails.summary(), MARGIN, MARGIN,
                color(ThemeTokens.MUTED_TEXT));
        if (dragging) {
            // 鼠标只携带名称，三维候选绘制在目标窗口的真实格位，避免图标遮住落点。
            context.drawTextWithShadow(
                    textRenderer,
                    module.stack(selected).getName(),
                    mouseX + GAP,
                    mouseY - CONTROL_HEIGHT,
                    color(ThemeTokens.ACCENT));
        }
        help.draw(context, textRenderer, width, mouseX, mouseY, status());
        super.render(context, mouseX, mouseY, delta);
        drawItemTooltip(context, mouseX, mouseY);
    }

    private void drawItemTooltip(DrawContext context, int mouseX, int mouseY) {
        // 操作期间不遮挡落点；只查询最上层窗口，不能透过窗口命中人物或被遮住的物品。
        if (closing || dragging || scenePressed || !camera.isEmpty() || !layerDrag.isEmpty()
                || !pressedItem.isEmpty() || help.captures(mouseX, mouseY)) {
            return;
        }
        String itemId;
        var window = windows.hitTest(mouseX, mouseY);
        if (window != null) {
            String containerId = window.key().identity();
            Rect view = viewports.get(containerId);
            if (window.minimized() || view == null || !view.contains(mouseX, mouseY)) {
                return;
            }
            itemId = interiors.get(containerId).itemAt(mouseX, mouseY);
        } else {
            itemId = player.pick(mouseX, mouseY);
            if (itemId.isEmpty()) {
                int slot = player.slotAt(mouseX, mouseY);
                itemId = slot < 0 ? "" : VolumeModule.playerSlotPreviewId(slot);
            }
        }
        var stack = module.stack(itemId);
        if (!stack.isEmpty()) {
            context.drawItemTooltip(textRenderer, stack, mouseX, mouseY);
        }
    }

    private void advanceAnimations(float seconds) {
        // 盖子打开到位后才创建内景窗口；关闭界面期间不能被动画重新打开。
        for (var entry : animations.entrySet()) {
            String id = entry.getKey();
            BagAnimation animation = entry.getValue();
            animation.advance(seconds);
            if (closing || !animation.ready() || windows.contains(key(id))) {
                continue;
            }
            int index = windows.snapshot().size() % 2;
            int windowWidth = Math.max(MIN_WINDOW_WIDTH, (int) (width * WINDOW_WIDTH_RATIO) - GAP);
            int windowHeight =
                    Math.max(MIN_WINDOW_HEIGHT, height - TOP - FOOTER_HEIGHT - CONTROL_HEIGHT);
            int left = (int) (width * WINDOW_START_RATIO) + index * (windowWidth + GAP);
            var bounds = new UiWindowManager.Rect(left, TOP, windowWidth, windowHeight);
            windows.openOrFocus(WINDOW, key(id), bounds);
        }
    }

    private void syncContainers() {
        // 收纳、取出或卸下容器后，按实例身份保留仍存在的窗口，关闭已离开玩家的实例。
        Set<String> present = new HashSet<>();
        for (Container container : module.state().getContainersList()) {
            String id = container.getId();
            present.add(id);
            BagAnimation animation = animations.computeIfAbsent(id, ignored -> new BagAnimation());
            animation.configure(ModelResources.optional(container.getModel()));
            interiors.computeIfAbsent(id, ignored -> new InteriorRenderer());
            InteriorRenderer interior = interiors.get(id);
            interior.layers().resize(container.getSize().getHeight());
            layerSliders.computeIfAbsent(id, ignored -> new LayerRangeSlider(interior.layers()));
        }
        for (String id : List.copyOf(animations.keySet())) {
            if (!present.contains(id)) {
                windows.close(key(id));
                animations.remove(id);
                interiors.remove(id);
                layerSliders.remove(id);
            }
        }
        if (!camera.isEmpty() && !present.contains(camera)
                || !layerDrag.isEmpty() && !present.contains(layerDrag)) {
            // 原版槽位变化可能让正在拖动的内景消失，旧鼠标捕获不能继续引用已移除的相机。
            releaseCapture();
        }
        if (!present.contains(active)) {
            active = "";
        }
    }

    private void drawWindow(
            DrawContext context, UiWindowManager.WindowState window, int mx, int my) {
        String id = window.key().identity();
        var bounds = window.bounds();
        int right = bounds.x() + bounds.width();
        int bottom = bounds.y() + bounds.height();
        context.fill(bounds.x(), bounds.y(), right, bottom, color(ThemeTokens.PANEL));
        context.drawBorder(
                bounds.x(), bounds.y(), bounds.width(), bounds.height(), color(ThemeTokens.BORDER));
        var container = module.container(id);
        // 顶部只有细标题、最小化和关闭按钮，不再绘制容器页签条。
        context.drawText(
                textRenderer,
                textRenderer.trimToWidth(
                        container.getTitle() + " " + ContainerDetails.container(id).getString(),
                        Math.max(0, bounds.width() - CONTROL_HEIGHT * 2 - TEXT_INSET * 2)),
                bounds.x() + TEXT_INSET,
                bounds.y() + TEXT_INSET,
                color(ThemeTokens.MUTED_TEXT),
                false);
        button(
                context,
                id,
                new Rect(right - CONTROL_HEIGHT * 2, bounds.y(), CONTROL_HEIGHT, HEADER_HEIGHT),
                "−",
                mx,
                my,
                () -> windows.minimize(window.key()),
                true);
        button(
                context,
                id,
                new Rect(right - CONTROL_HEIGHT, bounds.y(), CONTROL_HEIGHT, HEADER_HEIGHT),
                "×",
                mx,
                my,
                () -> closeContainer(id),
                true);
        LayerRangeSlider slider = layerSliders.get(id);
        slider.layout(layerRangeBounds(id));
        slider.draw(context, textRenderer);
        var view =
                new Rect(
                        bounds.x() + GAP,
                        bounds.y() + HEADER_HEIGHT + LayerRangeSlider.HEIGHT,
                        bounds.width() - GAP * 2,
                        Math.max(
                                1,
                                bounds.height() - HEADER_HEIGHT - LayerRangeSlider.HEIGHT - GAP));
        viewports.put(id, view);
        boolean top = !help.visible() && windows.hitTest(mx, my) == window;
        interiors
                .get(id)
                .draw(
                        context,
                        module,
                        container,
                        view,
                        top ? mx : -1,
                        top ? my : -1,
                        selected,
                        top && !module.busy() ? selectedModel() : null,
                        orientation, scissorX, scissorY);
    }

    private void drawRestores(DrawContext context, int mx, int my) {
        // 仅最小化容器留下小卡片，恢复和关闭使用独立命中区；关闭仍走取消转移及合盖流程。
        int x = MARGIN;
        int y = height - FOOTER_HEIGHT;
        int cardWidth = RESTORE_WIDTH + CONTROL_HEIGHT * 2;
        for (var window : windows.snapshot()) {
            if (window.minimized()) {
                String id = window.key().identity();
                context.fill(x, y, x + cardWidth, y + CONTROL_HEIGHT, color(ThemeTokens.PANEL));
                context.drawBorder(x, y, cardWidth, CONTROL_HEIGHT, color(ThemeTokens.BORDER));
                context.drawText(
                        textRenderer,
                        textRenderer.trimToWidth(
                                module.container(id).getTitle(), RESTORE_WIDTH - GAP * 2),
                        x + GAP,
                        y + TEXT_INSET,
                        color(ThemeTokens.TEXT),
                        false);
                button(
                        context,
                        "",
                        new Rect(x + RESTORE_WIDTH, y, CONTROL_HEIGHT, CONTROL_HEIGHT),
                        "↗",
                        mx,
                        my,
                        () -> windows.restore(window.key()),
                        true);
                button(
                        context,
                        "",
                        new Rect(
                                x + RESTORE_WIDTH + CONTROL_HEIGHT,
                                y,
                                CONTROL_HEIGHT,
                                CONTROL_HEIGHT),
                        "×",
                        mx,
                        my,
                        () -> closeContainer(id),
                        true);
                x += cardWidth + GAP;
            }
        }
    }

    private String status() {
        // 说明和操作结果只在帮助面板内展示，主页面保留转移进度条。
        if (module.busy()) {
            return "转移中，角色与物品已锁定；ESC 取消";
        }
        return hint.isEmpty() ? module.status() : hint;
    }

    private void drawProgress(DrawContext context) {
        if (!module.busy()) {
            return;
        }
        var transfer = module.state().getTransfer();
        float progress =
                transfer.getDurationTicks() <= 0
                        ? 0
                        : Math.min(
                                1,
                                transfer.getElapsedTicks() / (float) transfer.getDurationTicks());
        int top = height - MARGIN;
        int availableWidth = width - MARGIN * 2;
        context.fill(
                MARGIN,
                top,
                width - MARGIN,
                top + PROGRESS_HEIGHT,
                color(ThemeTokens.PROGRESS_TRACK));
        context.fill(
                MARGIN,
                top,
                MARGIN + (int) (availableWidth * progress),
                top + PROGRESS_HEIGHT,
                color(ThemeTokens.PROGRESS_FILL));
    }

    private void button(
            DrawContext context,
            String owner,
            Rect rect,
            String label,
            int mx,
            int my,
            Runnable action,
            boolean always) {
        // 标题控件使用透明底，仅悬停时出现背景，保留轻量窗口外观。
        if (rect.contains(mx, my)) {
            context.fill(
                    rect.x(),
                    rect.y(),
                    rect.right(),
                    rect.bottom(),
                    color(ThemeTokens.CONTROL_HOVER));
        }
        context.drawText(
                textRenderer,
                textRenderer.trimToWidth(label, Math.max(1, rect.width() - GAP * 2)),
                rect.x() + GAP,
                rect.y() + TEXT_INSET,
                color(ThemeTokens.TEXT),
                false);
        buttons.add(new Button(owner, rect, action, always));
    }

    public void openContainer(String id) {
        if (closing || module.busy()) {
            return;
        }
        // 快照可能先于下一帧到达；打开入口自己同步实例，不依赖 render 已经执行。
        syncContainers();
        BagAnimation animation = animations.get(id);
        if (animation == null) {
            return;
        }
        active = id;
        boolean alreadyOpen = animation.opened();
        animation.open();
        if (windows.contains(key(id))) {
            windows.restore(key(id));
        }
        if (!alreadyOpen) {
            module.containerSound(id, ContainerSounds.Action.OPEN);
        }
    }

    public void closeContainer(String id) {
        // 关闭即取消，合盖仅为视觉过渡，不能延迟解锁服务器中的玩家。
        module.cancel();
        module.closeGround(id);
        windows.close(key(id));
        BagAnimation animation = animations.get(id);
        if (animation != null && animation.opened()) {
            module.containerSound(id, ContainerSounds.Action.CLOSE);
            animation.close();
        }
        clearSelection();
        releaseCapture();
    }

    private Item selectedModel() {
        // 手持栈只有预览身份；存入后由服务器分配业务实例，客户端不能创建库存。
        if (selectedPlayerStack != null) {
            return Item.newBuilder()
                    .setId(selected)
                    .setStackNbt(selectedPlayerStack.getStackNbt())
                    .setBaseSize(selectedPlayerStack.getBaseSize())
                    .setSize(selectedPlayerStack.getBaseSize())
                    .build();
        }
        return module.state().getItemsList().stream()
                .filter(i -> i.getId().equals(selected))
                .findFirst()
                .orElse(null);
    }

    private void select(String id) {
        selectedPlayerStack = null;
        selected = id;
        var item = selectedModel();
        orientation = item == null ? 0 : item.getOrientation();
        selectionRevision = module.state().getRevision();
        hint = "";
    }

    private void turn(boolean vertical) {
        // 只更改待放置姿态，点击或松开鼠标才会发起计时转移。
        Item item = selectedModel();
        if (item == null || module.busy()) {
            return;
        }
        orientation = OrthogonalRotation.turn(orientation, vertical, hasShiftDown());
        Size size = OrthogonalRotation.size(Volumes.baseSize(item), orientation);
        hint =
                "待放置 "
                        + size.getWidth()
                        + "×"
                        + size.getHeight()
                        + "×"
                        + size.getDepth()
                        + " · 点击目标格确认";
    }

    private void drop(String target, double x, double y) {
        var item = selectedModel();
        var cell = cellAt(target, x, y);
        if (item == null || cell == null || module.busy()) {
            return;
        }
        var size = OrthogonalRotation.size(Volumes.baseSize(item), orientation);
        if (!interiors.get(target).layers().contains(cell.getY(), size.getHeight())) {
            // 手动放置要求完整占格可见，避免切片时把部分物品悄悄放到隐藏层。
            hint = "物品超出可见层，请先扩大上方范围条";
            return;
        }
        if (!Volumes.fits(
                module.state().getItemsList(),
                target,
                module.container(target).getSize(),
                cell,
                size,
                selected)) {
            hint = "目标格被占用或旋转后超出边界";
            return;
        }
        if (selectedPlayerStack != null) {
            module.deposit(selectedPlayerStack, target, cell, false, orientation);
        } else {
            module.move(selected, target, cell, false, orientation);
        }
        hint = "";
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (closing) {
            return true;
        }
        if (help.mouseClicked(x, y, button)) {
            releaseCapture();
            return true;
        }
        if (!selected.isEmpty() && module.rotateYaw().matchesMouse(button)) {
            turn(false);
            return true;
        }
        if (!selected.isEmpty() && module.rotatePitch().matchesMouse(button)) {
            turn(true);
            return true;
        }
        var window = windows.hitTest(x, y);
        String owner = window == null ? "" : window.key().identity();
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            // 只分派给最上层窗口，遮住的按钮不能穿透触发。
            for (var control : List.copyOf(buttons)) {
                if (control.owner().equals(owner) && control.rect().contains(x, y)) {
                    if (!module.busy() || control.always()) {
                        control.action().run();
                    }
                    return true;
                }
            }
        }
        if (window != null) {
            clickWindow(window, x, y, button);
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            int slot = player.slotAt(x, y);
            if (slot >= 0 && openItemContainer(VolumeModule.playerSlotPreviewId(slot))) {
                return true;
            }
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && !module.busy()) {
            int slot = player.slotAt(x, y);
            PlayerSlotStack snapshot = module.playerSlot(slot);
            if (slot >= 0
                    && (!PlayerSlots.isArmor(slot)
                            || snapshot != null
                                    && (!snapshot.getStackNbt().isEmpty()
                                            || !snapshot.getUnavailableReason().isEmpty()))) {
                // 已穿盔甲和手持栈可直接抓取；没有装备的身体区域仍用于旋转人物。
                clearSelection();
                if (snapshot != null && !snapshot.getUnavailableReason().isEmpty()) {
                    hint = snapshot.getUnavailableReason();
                } else if (snapshot != null && !snapshot.getStackNbt().isEmpty()) {
                    selectedPlayerStack = snapshot;
                    selected = VolumeModule.playerSlotPreviewId(slot);
                    selectionRevision = module.state().getRevision();
                    pressedItem = selected;
                    pressX = x;
                    pressY = y;
                    hint = "";
                }
                return true;
            }
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            scenePressed = true;
            sceneDragged = false;
            pressX = x;
            pressY = y;
            pressedBag = "";
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                pressedBag = player.pick(x, y);
            }
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    private void clickWindow(UiWindowManager.WindowState window, double x, double y, int button) {
        // 窗口已经通过顶层命中检查，范围条独占本次拖动，不能穿透到相机或库存。
        String id = window.key().identity();
        active = id;
        windows.focus(window.key());
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && y < window.bounds().y() + HEADER_HEIGHT) {
            windows.beginDrag(window.key(), x, y);
            return;
        }
        LayerRangeSlider slider = layerSliders.get(id);
        if (slider.contains(x, y)) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                releaseCapture();
                layerDrag = id;
                slider.press(x, y);
                interiors.get(id).invalidateHits();
            }
            return;
        }
        Rect view = viewports.get(id);
        if (view == null || !view.contains(x, y)) {
            return;
        }
        String hitItem = interiors.get(id).itemAt(x, y);
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT
                && !hitItem.isEmpty()
                && openItemContainer(hitItem)) {
            return;
        }
        boolean emptySpace = hitItem.isEmpty();
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT
                || (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && emptySpace)) {
            // 空白处按下先捕获相机；单击放置延后到释放，拖动不会意外发起转移。
            camera = id;
            cameraDragged = false;
            pressX = x;
            pressY = y;
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && !selected.isEmpty() && !module.busy()) {
                pendingDropContainer = id;
            }
            return;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && !module.busy()) {
            clickItem(id, x, y);
        }
    }

    private void clickItem(String container, double x, double y) {
        // 有选中物品时，空格或修改过的姿态表示确认放置；否则选择鼠标下的物品。
        String hitItem = interiors.get(container).itemAt(x, y);
        Item selectedItem = selectedModel();
        boolean rotated = selectedItem != null && orientation != selectedItem.getOrientation();
        if (!selected.isEmpty() && (hitItem.isEmpty() || rotated)) {
            drop(container, x, y);
            return;
        }
        if (hitItem.isEmpty()) {
            return;
        }
        select(hitItem);
        pressedItem = hitItem;
        pressX = x;
        pressY = y;
        if (hasShiftDown()) {
            // 快速转移只选择另一个已打开窗口，不再根据草包/背篓的固定名称猜目标。
            for (var target : windows.snapshot()) {
                if (!target.minimized() && !target.key().identity().equals(container)) {
                    module.move(
                            selected,
                            target.key().identity(),
                            Cell.getDefaultInstance(),
                            true,
                            orientation);
                    break;
                }
            }
        }
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        // 每次按下只捕获一个动作；跨越其他窗口不会从拖物品变为旋转或拖窗。
        if (help.visible()) {
            return true;
        }
        if (!layerDrag.isEmpty()) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                layerSliders.get(layerDrag).drag(x);
                interiors.get(layerDrag).invalidateHits();
            }
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && windows.dragTo(x, y)) {
            return true;
        }
        if (!camera.isEmpty()) {
            cameraDragged |= movedPastThreshold(x, y);
            if (cameraDragged) {
                interiors.get(camera).rotate(dx, dy);
            }
            return true;
        }
        if (!pressedItem.isEmpty() && !module.busy()) {
            dragging |=
                    (x - pressX) * (x - pressX) + (y - pressY) * (y - pressY)
                            >= DRAG_THRESHOLD_SQUARED_PIXELS;
            return true;
        }
        if (scenePressed) {
            sceneDragged |=
                    (x - pressX) * (x - pressX) + (y - pressY) * (y - pressY)
                            >= DRAG_THRESHOLD_SQUARED_PIXELS;
            if (sceneDragged) {
                if (!pressedBag.isEmpty()
                        && button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                        && !module.busy()) {
                    select(pressedBag);
                    pressedItem = selected;
                    dragging = true;
                    scenePressed = false;
                } else {
                    player.rotate(dx, dy);
                }
            }
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        // 范围条释放后只结束捕获，即使光标跨到另一个容器也不会提交物品转移。
        if (!layerDrag.isEmpty()) {
            releaseCapture();
            return true;
        }
        // 释放时按当前最上层目标窗口解析落点，最小化和空白处不会自动吞入物品。
        if (!pendingDropContainer.isEmpty()
                && !cameraDragged
                && !movedPastThreshold(x, y)
                && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            var window = windows.hitTest(x, y);
            if (window != null && window.key().identity().equals(pendingDropContainer)) {
                drop(pendingDropContainer, x, y);
            }
        }
        if (dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            var window = windows.hitTest(x, y);
            if (window != null) {
                String id = window.key().identity();
                var view = viewports.get(id);
                if (view != null && view.contains(x, y)) {
                    drop(id, x, y);
                }
            } else if (!help.captures(x, y) && !selected.isEmpty()) {
                int targetSlot = player.slotAt(x, y);
                var type = ContainerItems.definition(module.stack(selected));
                Placement placement = type == null ? null : player.wearAt(x, y, type);
                if (placement != null && (targetSlot < 0 || PlayerSlots.isArmor(targetSlot))) {
                    module.wear(selected, selectedPlayerStack, placement);
                    releaseCapture();
                    return true;
                }
                if (targetSlot >= 0) {
                    // 身体落点包括双手和盔甲部位，服务端验证穿戴类型和卸装限制。
                    if (selectedPlayerStack != null) {
                        module.movePlayerStack(selectedPlayerStack, targetSlot);
                    } else {
                        module.withdraw(selected, targetSlot);
                    }
                }
            }
        } else if (scenePressed && !sceneDragged && !pressedBag.isEmpty()) {
            openContainer(pressedBag);
        }
        releaseCapture();
        return true;
    }

    private boolean openItemContainer(String item) {
        // 容器物品仍可被正常拖动；右键只打开其自身内景，收纳中的包也保留独立窗口。
        var stack = module.stack(item);
        if (ContainerItems.definition(stack) == null) {
            return false;
        }
        String id = ContainerItems.identity(stack);
        openContainer(id);
        return true;
    }

    private boolean movedPastThreshold(double x, double y) {
        double dx = x - pressX;
        double dy = y - pressY;
        return dx * dx + dy * dy >= DRAG_THRESHOLD_SQUARED_PIXELS;
    }

    private void releaseCapture() {
        // 一次捕获结束后同时清除窗口、内景和人物的拖动状态。
        windows.cancelCapture();
        camera = "";
        layerDrag = "";
        cameraDragged = false;
        pendingDropContainer = "";
        pressedItem = "";
        pressedBag = "";
        dragging = false;
        scenePressed = false;
        sceneDragged = false;
    }

    private void clearSelection() {
        selectedPlayerStack = null;
        selected = "";
        pressedItem = "";
        dragging = false;
        orientation = 0;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        if (help.captures(x, y)) {
            help.scroll(amount, textRenderer);
            return true;
        }
        var window = windows.hitTest(x, y);
        if (window != null) {
            // 范围条上滚轮不缩放背后的模型，缩放只属于三维视口。
            String id = window.key().identity();
            Rect view = viewports.get(id);
            if (view != null && view.contains(x, y)) {
                interiors.get(id).zoom(amount);
            }
            return true;
        }
        return super.mouseScrolled(x, y, amount);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        // ESC 始终保留取消语义，即使用户把旋转动作绑定到同一个键。
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (help.visible()) {
                module.cancel();
                help.close();
                releaseCapture();
                return true;
            }
            close();
            return true;
        }
        if (help.visible()) {
            if (module.openKey().matchesKey(key, scan)
                    || client.options.inventoryKey.matchesKey(key, scan)) {
                close();
            }
            return true;
        }
        // 旋转使用配置后的按键；只在选中物品时消耗，支持 Shift 反向。
        if (!selected.isEmpty() && module.rotateYaw().matchesKey(key, scan)) {
            turn(false);
            return true;
        }
        if (!selected.isEmpty() && module.rotatePitch().matchesKey(key, scan)) {
            turn(true);
            return true;
        }
        if (module.openKey().matchesKey(key, scan)
                || client.options.inventoryKey.matchesKey(key, scan)) {
            close();
            return true;
        }
        if (key == GLFW.GLFW_KEY_W && hasControlDown() && !active.isEmpty()) {
            closeContainer(active);
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void close() {
        // 先取消权威转移，再播放合盖；结束时仅调用宿主提供的退出动作。
        module.cancel();
        module.closeGround("");
        clearSelection();
        releaseCapture();
        closing = true;
        windows.reset();
        for (var entry : animations.entrySet()) {
            if (entry.getValue().opened()) {
                module.containerSound(entry.getKey(), ContainerSounds.Action.CLOSE);
                entry.getValue().close();
            }
        }
    }

    @Override
    public void removed() {
        // 外部替换屏幕、断线和分辨率变化之外的销毁均释放所有窗口作用域。
        module.cancel();
        module.closeGround("");
        releaseCapture();
        windows.reset();
        closing = false;
        clearSelection();
        animations.clear();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public Set<String> openContainers() {
        return windows.snapshot().stream()
                .map(w -> w.key().identity())
                .collect(Collectors.toUnmodifiableSet());
    }

    public String selectedItem() {
        return selected;
    }

    public int selectedOrientation() {
        return orientation;
    }

    public Rect viewport(String id) {
        return viewports.get(id);
    }

    public Rect layerRangeBounds(String id) {
        var bounds = window(id).bounds();
        return new Rect(
                bounds.x() + GAP,
                bounds.y() + HEADER_HEIGHT,
                bounds.width() - GAP * 2,
                LayerRangeSlider.HEIGHT);
    }

    /** 返回零起点的可见格层半开区间，便于验收绘制与命中是否一致。 */
    public int[] visibleLayers(String id) {
        var layers = interiors.get(id).layers();
        return new int[] {layers.lower(), layers.upper()};
    }

    public int handAt(double x, double y) {
        return player.handAt(x, y);
    }

    public int playerSlotAt(double x, double y) {
        return player.slotAt(x, y);
    }

    public float[] playerSlotBounds(int slot) {
        return player.slotBounds(slot);
    }

    public Placement containerPlacementAt(double x, double y, String item) {
        var type = ContainerItems.definition(module.stack(item));
        return type == null ? null : player.wearAt(x, y, type);
    }

    public float[] handBounds(int slot) {
        return player.handBounds(slot);
    }

    public String itemAt(String container, double x, double y) {
        return interiors.get(container).itemAt(x, y);
    }

    public Cell cellAt(String container, double x, double y) {
        // 预览和释放复用同一个落点选择，深处空位也可直接从三维画面放入。
        return interiors
                .get(container)
                .placementAt(
                        module.state().getItemsList(),
                        container,
                        module.container(container).getSize(),
                        selectedModel(),
                        orientation,
                        x,
                        y);
    }

    public float[] bagBounds(String id) {
        return player.bounds(id);
    }

    public boolean bagHit(String id, double x, double y) {
        return player.hit(id, x, y);
    }

    public UiWindowManager.WindowState window(String id) {
        return windows.snapshot().stream()
                .filter(w -> w.key().identity().equals(id))
                .findFirst()
                .orElse(null);
    }

    private int color(String token) {
        return InventoryUiClient.runtime().color(token);
    }

    private record Button(String owner, Rect rect, Runnable action, boolean always) {}
}
