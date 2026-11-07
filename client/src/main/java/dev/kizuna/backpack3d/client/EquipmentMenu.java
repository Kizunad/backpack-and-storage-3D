package dev.kizuna.backpack3d.client;

import dev.kizuna.backpack3d.client.InteriorRenderer.Rect;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.State;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.glfw.GLFW;

/** 按需显示全部挂载装备，所有宿主共用同一卸装动作与权威列表。 */
final class EquipmentMenu {
    private static final int WIDTH_PIXELS = 210;
    private static final int MARGIN_PIXELS = 8;
    private static final int ROW_PIXELS = 20;
    private static final int TEXT_INSET_PIXELS = 5;
    private static final int MAX_ROWS = 8;
    private static final int RESERVED_ROWS = 3;
    private final VolumeModule module;
    private final WornPreview.EquipmentActions actions;
    private List<ModelEquipment.Entry> entries = List.of();
    private State displayedState;
    private Rect panel;
    private long revision;
    private int scroll;
    private int rows;
    private boolean visible;

    EquipmentMenu(VolumeModule module, WornPreview.EquipmentActions actions) {
        this.module = module;
        this.actions = actions;
    }

    void open() {
        visible = true;
        displayedState = null;
        scroll = 0;
        refresh();
    }

    boolean visible() {
        return visible;
    }

    void close() {
        visible = false;
        panel = null;
    }

    private void refresh() {
        // 只在权威快照更新时重建目录；渲染帧沿用同一份列表和物品名。
        var state = module.state();
        if (state == displayedState) {
            return;
        }
        entries = ModelEquipment.worn(state).stream().map(this::namedEntry).toList();
        displayedState = state;
        revision = state.getRevision();
    }

    private ModelEquipment.Entry namedEntry(ModelEquipment.Entry entry) {
        var stack = module.stack(entry.id());
        if (stack.isEmpty()) {
            return entry;
        }
        return new ModelEquipment.Entry(entry.id(), stack.getHoverName().getString());
    }

    void draw(GuiGraphicsExtractor context, Rect viewport, int mouseX, int mouseY) {
        if (!visible) {
            return;
        }
        refresh();
        var font = net.minecraft.client.Minecraft.getInstance().font;
        int width = Math.min(WIDTH_PIXELS, viewport.width() - MARGIN_PIXELS * 2);
        rows = Math.min(MAX_ROWS, Math.max(1, viewport.height() / ROW_PIXELS - RESERVED_ROWS));
        scroll = Math.min(scroll, Math.max(0, entries.size() - rows));
        int height = (Math.min(rows, Math.max(1, entries.size())) + 2) * ROW_PIXELS;
        panel = new Rect(viewport.x() + MARGIN_PIXELS, viewport.y() + MARGIN_PIXELS, width, height);
        context.fill(panel.x(), panel.y(), panel.right(), panel.bottom(), color(ThemeTokens.PANEL));
        context.outline(panel.x(), panel.y(), width, height, color(ThemeTokens.BORDER));
        text(context, font, "已穿戴装备 · 滚轮翻页", 0, ThemeTokens.MUTED_TEXT);
        drawRows(context, font, mouseX, mouseY);
        String status = actions.status();
        if (entries.isEmpty()) {
            text(context, font, "没有可脱下的装备", 1, ThemeTokens.MUTED_TEXT);
        }
        text(context, font, status, height / ROW_PIXELS - 1, ThemeTokens.ACCENT);
    }

    private void drawRows(GuiGraphicsExtractor context, Font font, int mouseX, int mouseY) {
        for (int row = 0; row < rows && row + scroll < entries.size(); row++) {
            var entry = entries.get(row + scroll);
            int top = panel.y() + (row + 1) * ROW_PIXELS;
            if (panel.contains(mouseX, mouseY) && mouseY >= top && mouseY < top + ROW_PIXELS) {
                context.fill(panel.x(), top, panel.right(), top + ROW_PIXELS, color(ThemeTokens.CONTROL_HOVER));
            }
            String token = ThemeTokens.TEXT;
            if (actions.busy()) {
                token = ThemeTokens.MUTED_TEXT;
            }
            text(context, font, "脱掉 " + entry.title(), row + 1, token);
        }
    }

    private void text(GuiGraphicsExtractor context, Font font, String value, int row, String token) {
        String label = font.plainSubstrByWidth(value, panel.width() - TEXT_INSET_PIXELS * 2);
        context.text(font, label, panel.x() + TEXT_INSET_PIXELS,
                panel.y() + row * ROW_PIXELS + TEXT_INSET_PIXELS, color(token));
    }

    boolean click(double x, double y, int button) {
        if (!visible) {
            return false;
        }
        // 回执或外部换装改变列表后，旧画面坐标不能误卸另一件装备。
        if (revision != module.state().getRevision()) {
            return true;
        }
        if (panel == null || !panel.contains(x, y) || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            close();
            return true;
        }
        int row = (int) (y - panel.y()) / ROW_PIXELS - 1;
        int index = row + scroll;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && row >= 0 && row < rows
                && index < entries.size() && !actions.busy()) {
            actions.unequip(entries.get(index).id(), revision);
        }
        return true;
    }

    boolean scroll(double amount) {
        if (!visible) {
            return false;
        }
        scroll = Math.max(0, Math.min(Math.max(0, entries.size() - rows), scroll - (int) amount));
        return true;
    }

    private static int color(String token) {
        return InventoryUiClient.runtime().color(token);
    }
}
