package dev.kizuna.backpack3d.client;

import dev.kizuna.backpack3d.client.InteriorRenderer.Rect;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/** 内景上方的双滑块范围条；只修改本窗口的显示范围，不提交库存操作。 */
public final class LayerRangeSlider {
    // 尺寸单位为 GUI 逻辑像素，命中范围与绘制使用同一布局。
    public static final int HEIGHT = 24;
    private static final int SIDE_INSET = 8;
    private static final int LABEL_WIDTH = 34;
    private static final int TRACK_HEIGHT = 2;
    private static final int HANDLE_HALF_WIDTH = 3;
    private static final int HANDLE_HEIGHT = 7;
    private final LayerRange range;
    private Rect bounds;
    private boolean draggingLower;

    public LayerRangeSlider(LayerRange range) {
        this.range = range;
    }

    public void layout(Rect bounds) {
        this.bounds = bounds;
    }

    public boolean contains(double x, double y) {
        return bounds != null && bounds.contains(x, y);
    }

    public void press(double x, double y) {
        // 点击轨道选择较近的端点；等距时按上下半区区分两个手柄。
        double lowerDistance = Math.abs(x - position(range.lower()));
        double upperDistance = Math.abs(x - position(range.upper()));
        draggingLower =
                lowerDistance < upperDistance || lowerDistance == upperDistance && y >= centerY();
        drag(x);
    }

    public void drag(double x) {
        // 拖出控件后仍由原滑块捕获，吸附到整数格层并交给范围模型约束上下界。
        int boundary = (int) Math.round((x - trackLeft()) * range.height() / trackWidth());
        if (draggingLower) {
            range.moveLower(boundary);
        } else {
            range.moveUpper(boundary);
        }
    }

    public void draw(DrawContext context, TextRenderer font) {
        int center = centerY();
        int lowerX = position(range.lower());
        int upperX = position(range.upper());
        context.fill(
                trackLeft(),
                center,
                trackLeft() + trackWidth(),
                center + TRACK_HEIGHT,
                color(ThemeTokens.PROGRESS_TRACK));
        context.fill(
                lowerX, center, upperX, center + TRACK_HEIGHT, color(ThemeTokens.PROGRESS_FILL));
        // 下界手柄在轨道下方，上界在上方，单层范围时也能清楚区分和抓取。
        handle(context, lowerX, center + TRACK_HEIGHT, color(ThemeTokens.ACCENT));
        handle(context, upperX, center - HANDLE_HEIGHT, color(ThemeTokens.ACCENT));
        String label = (range.lower() + 1) + "–" + range.upper();
        context.drawText(
                font,
                label,
                bounds.right() - LABEL_WIDTH,
                center - font.fontHeight / 2,
                color(ThemeTokens.MUTED_TEXT),
                false);
    }

    private void handle(DrawContext context, int x, int y, int color) {
        context.fill(x - HANDLE_HALF_WIDTH, y, x + HANDLE_HALF_WIDTH + 1, y + HANDLE_HEIGHT, color);
    }

    private int position(int boundary) {
        return trackLeft() + Math.round(trackWidth() * boundary / (float) range.height());
    }

    private int trackLeft() {
        return bounds.x() + SIDE_INSET;
    }

    private int trackWidth() {
        return Math.max(1, bounds.width() - SIDE_INSET * 2 - LABEL_WIDTH);
    }

    private int centerY() {
        return bounds.y() + bounds.height() / 2;
    }

    private static int color(String token) {
        return InventoryUiClient.runtime().color(token);
    }
}
