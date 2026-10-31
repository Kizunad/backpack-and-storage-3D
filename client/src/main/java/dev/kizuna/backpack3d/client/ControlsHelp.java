package dev.kizuna.backpack3d.client;

import dev.kizuna.backpack3d.client.InteriorRenderer.Rect;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** 右上角的按需帮助；展开后独占输入，避免点说明时穿透到模型或物品。 */
final class ControlsHelp {
    // 尺寸均为 GUI 逻辑像素。
    private static final int MARGIN = 12;
    private static final int RADIUS = 10;
    private static final int BORDER = 1;
    private static final int GAP = 6;
    private static final int PADDING = 10;
    private static final int PANEL_WIDTH = 290;
    private static final int LINE_GAP = 4;
    private final VolumeModule module;
    private boolean visible;
    private Rect button;
    private Rect panel;
    private int scroll;
    private int maxScroll;

    ControlsHelp(VolumeModule module) {
        this.module = module;
    }

    void resize(int width) {
        button = new Rect(width - MARGIN - RADIUS * 2, MARGIN, RADIUS * 2, RADIUS * 2);
    }

    boolean visible() {
        return visible;
    }

    void close() {
        visible = false;
    }

    boolean captures(double x, double y) {
        return visible || containsButton(x, y);
    }

    boolean mouseClicked(double x, double y, int mouseButton) {
        // 图标切换帮助；展开时点外面也只关闭帮助，不执行背后的库存动作。
        if (containsButton(x, y)) {
            if (mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                visible = !visible;
            }
            return true;
        }
        if (visible) {
            if (mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT
                    && (panel == null || !panel.contains(x, y))) {
                visible = false;
            }
            return true;
        }
        return false;
    }

    void scroll(double amount, Font font) {
        // 小窗口中仍能读完整说明，滚轮只滚帮助，不影响背后的内景缩放。
        int lineHeight = font.lineHeight + LINE_GAP;
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (amount * lineHeight)));
    }

    void draw(
            GuiGraphicsExtractor context,
            Font font,
            int width,
            int mouseX,
            int mouseY,
            String status) {
        // 面板文字跟随实际按键绑定；正常页面只保留 i，不重复展示操作说明。
        if (visible) {
            drawPanel(context, font, width, status);
        }
        int centerX = button.x() + RADIUS;
        int centerY = button.y() + RADIUS;
        int borderColor =
                color(
                        containsButton(mouseX, mouseY) || visible
                                ? ThemeTokens.ACCENT
                                : ThemeTokens.BORDER);
        circle(context, centerX, centerY, RADIUS, borderColor);
        circle(context, centerX, centerY, RADIUS - BORDER, color(ThemeTokens.PANEL));
        context.centeredText(
                font, "i", centerX, centerY - font.lineHeight / 2, color(ThemeTokens.TEXT));
    }

    private void drawPanel(GuiGraphicsExtractor context, Font font, int width, String status) {
        int panelWidth = Math.min(PANEL_WIDTH, width - MARGIN * 2);
        int textWidth = panelWidth - PADDING * 2;
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (String paragraph :
                List.of(
                        "操作提示",
                        "右拖人物：旋转预览；左键单击容器：开合盖与内景；左拖容器：搬动实物。",
                        "拖动内景空白处：旋转场景；右键拖动也可旋转；滚轮：缩放。",
                        "内景上方范围条：下方滑块控制最低层，上方滑块控制最高层；至少保留一层，各窗口独立。",
                        "已占用格以连接轮廓高亮，相邻格不显示内部亮线。手动放置须让物品完整占格处于可见层内。",
                        "拖动物品：放入目标格；拖到容器模型：自动找空位，合盖时也可用。",
                        "Shift 点击原版物品：放入随身容器，腰挂优先；Shift 点击内景物品：取回原版背包。",
                        module.rotateYaw().getDisplayName().getString()
                                + "：左右旋转物品；"
                                + module.rotatePitch().getDisplayName().getString()
                                + "：上下旋转；Shift：反向。",
                        "拖动角色手中的物品到内景格位存入；从内景拖到左手或右手取出。双手也可互相拖放，目标手为空或能整组叠放同种物品时接收。",
                        "盔甲可在身体部位、双手与容器之间拖放；装备部位须匹配且为空。",
                        "草包沿腰部拖动，默认最多 2 件；背篓只接受胸前或背后。容器内容随物品保存。",
                        "右键手中或内景里的容器物品：打开它的内景。容器不可放入自身或子孙。",
                        "世界中手持背包 Shift＋右键：放到地面；右键地面背包：打开；关闭内景后空手 Shift＋右键：收回整包。",
                        "拖动细标题：移动窗口；−：最小化；×：关闭。",
                        "转移即时完成；ESC 只关闭帮助或合盖退出。拒绝时物品保留原位。",
                        "旋转键在 config/bs3d/config.toml 的 controls 表修改，F3+T 重载；打开背包键在游戏设置中修改。",
                        "当前状态：" + status)) {
            lines.addAll(font.split(Component.literal(paragraph), textWidth));
        }
        int left = width - MARGIN - panelWidth;
        int top = button.bottom() + GAP;
        int lineHeight = font.lineHeight + LINE_GAP;
        int contentHeight = lines.size() * lineHeight;
        int panelHeight =
                Math.min(
                        PADDING * 2 + contentHeight,
                        context.guiHeight() - top - MARGIN);
        panel = new Rect(left, top, panelWidth, panelHeight);
        maxScroll = Math.max(0, contentHeight - (panelHeight - PADDING * 2));
        scroll = Math.min(scroll, maxScroll);
        context.fill(left, top, left + panelWidth, top + panelHeight, color(ThemeTokens.PANEL));
        context.outline(left, top, panelWidth, panelHeight, color(ThemeTokens.BORDER));
        context.enableScissor(
                left + PADDING, top + PADDING, panel.right() - PADDING, panel.bottom() - PADDING);
        try {
            int y = top + PADDING - scroll;
            for (FormattedCharSequence line : lines) {
                context.text(font, line, left + PADDING, y, color(ThemeTokens.TEXT), false);
                y += lineHeight;
            }
        } finally {
            context.disableScissor();
        }
    }

    private boolean containsButton(double x, double y) {
        if (button == null) {
            return false;
        }
        double dx = x - (button.x() + RADIUS);
        double dy = y - (button.y() + RADIUS);
        return dx * dx + dy * dy <= RADIUS * RADIUS;
    }

    private static void circle(
            GuiGraphicsExtractor context, int centerX, int centerY, int radius, int color) {
        // 每行填充圆内弦段，绘制和点击都使用同一半径，不依赖额外贴图。
        for (int y = -radius; y <= radius; y++) {
            int halfWidth = (int) Math.sqrt(radius * radius - y * y);
            context.fill(
                    centerX - halfWidth,
                    centerY + y,
                    centerX + halfWidth + 1,
                    centerY + y + 1,
                    color);
        }
    }

    private static int color(String token) {
        return InventoryUiClient.runtime().color(token);
    }
}
