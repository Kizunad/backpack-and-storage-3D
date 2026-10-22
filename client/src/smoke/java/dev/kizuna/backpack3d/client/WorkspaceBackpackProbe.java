package dev.kizuna.backpack3d.client;

import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.client.WorkspaceScreen;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Phase;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.ScreenshotRecorder;
import org.lwjgl.glfw.GLFW;

/** 从实际菜单进入工作台场景，并验证窗口平移后的三维鼠标命中。 */
public final class WorkspaceBackpackProbe {
    private int step;
    private int ticks;
    private VolumeComponent component;
    private long revisionBeforeTransfer;
    private com.google.protobuf.ByteString handBeforeTransfer;

    public boolean tick(MinecraftClient client, VolumeModule module, String pouch) {
        // 每次交互后等待一帧生成投影，避免直接调用场景方法绕过工作台输入路由。
        if (++ticks < 3) {
            return false;
        }
        ticks = 0;
        var runtime = InventoryUiClient.runtime();
        switch (step) {
            case 0 -> runtime.openWorkspace();
            case 1 -> clickButton(client, "功能");
            case 2 -> {
                clickButton(client, "库存 / 3D 背包");
                if (!(client.currentScreen instanceof WorkspaceScreen)) {
                    throw new IllegalStateException("菜单打开背包必须留在 I 工作台");
                }
                try {
                    // 夹具读取现有视图，不为自动验收在生产 API 增加公开调试入口。
                    var field = VolumeModule.class.getDeclaredField("embedded");
                    field.setAccessible(true);
                    component = (VolumeComponent) field.get(module);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure);
                }
            }
            case 3 -> {
                if (component.scene().bagBounds(pouch) == null) {
                    throw new IllegalStateException("工作台没有绘制装备模型");
                }
                var window = runtime.workspace().windowBar().stream()
                        .filter(value -> value.key().windowType().equals("bs3d:launcher")).findFirst().orElseThrow();
                var bounds = window.bounds();
                // 实际拖动外框，后续使用新坐标寻找背包，覆盖嵌入偏移与命中一致性。
                drag(client, bounds.x() + 30, bounds.y() + 6, bounds.x() + 48, bounds.y() + 22);
            }
            case 4 -> {
                if (!clickBag(client, pouch)) {
                    drag(client, component.x() + 20, component.y() + 70,
                            component.x() + 35, component.y() + 70);
                    return false;
                }
            }
            case 5 -> {
                if (!component.scene().openContainers().contains(pouch)) {
                    return false;
                }
                ScreenshotRecorder.saveScreenshot(client.runDirectory, "workspace-backpack.png",
                        client.getFramebuffer(), message -> {});
            }
            case 6 -> {
                if (!dragHandIntoContainer(client, module, pouch)) {
                    return false;
                }
            }
            case 7 -> {
                if (module.state().getTransfer().getPhase() != Phase.RUNNING) {
                    return false;
                }
                if (module.state().getRevision() != revisionBeforeTransfer) {
                    throw new IllegalStateException("工作台拖放计时结束前改变库存");
                }
                ScreenshotRecorder.saveScreenshot(client.runDirectory, "workspace-transfer.png",
                        client.getFramebuffer(), message -> {});
                // I 退出必须取消实际权威转移；随后等待超过原转移时长再检查库存。
                client.setScreen(null);
            }
            case 8 -> {
                if (module.state().getTransfer().getPhase() != Phase.CANCELLED) {
                    return false;
                }
                ticks = -module.state().getTransfer().getDurationTicks();
            }
            case 9 -> {
                if (module.state().getRevision() != revisionBeforeTransfer
                        || !module.playerSlot(client.player.getInventory().selectedSlot)
                                .getStackNbt().equals(handBeforeTransfer)) {
                    throw new IllegalStateException("退出工作台取消后库存或手持 NBT 变化");
                }
                runtime.openWorkspace();
                runtime.open("bs3d:open", null);
            }
            case 10 -> {
                if (!component.scene().openContainers().isEmpty()) {
                    throw new IllegalStateException("退出工作台后不能保留旧容器访问");
                }
                var window = runtime.workspace().windowBar().stream()
                        .filter(value -> value.key().windowType().equals("bs3d:launcher")).findFirst().orElseThrow();
                runtime.workspace().windows().close(window.key());
                client.setScreen(null);
                return true;
            }
            default -> throw new IllegalStateException("未知工作台验收步骤");
        }
        step++;
        return false;
    }

    private boolean dragHandIntoContainer(MinecraftClient client, VolumeModule module, String container) {
        // 来源和落点都从本帧投影取样，输入通过外层工作台，覆盖偏移、焦点与拖动捕获。
        var scene = component.scene();
        int slot = client.player.getInventory().selectedSlot;
        float[] bounds = scene.handBounds(slot);
        var view = scene.viewport(container);
        if (bounds != null && view != null) {
            for (int y = Math.max(0, (int) Math.ceil(bounds[1])); y < Math.min(component.height(), bounds[3]); y++) {
                for (int x = Math.max(0, (int) Math.ceil(bounds[0])); x < Math.min(component.width(), bounds[2]); x++) {
                    if (scene.handAt(x, y) != slot || scene.window(container).bounds().contains(x, y)) {
                        continue;
                    }
                    client.currentScreen.mouseClicked(component.x() + x, component.y() + y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    if (!scene.selectedItem().equals(VolumeModule.playerSlotPreviewId(slot))) {
                        throw new IllegalStateException("工作台没有捕获手持物品");
                    }
                    var hand = module.playerSlot(slot);
                    for (int targetY = view.y(); targetY < view.bottom(); targetY += 2) {
                        for (int targetX = view.x(); targetX < view.right(); targetX += 2) {
                            var cell = scene.cellAt(container, targetX, targetY);
                            if (cell == null || !Volumes.fits(module.state().getItemsList(), container,
                                    module.container(container).getSize(), cell, hand.getBaseSize(), "")) {
                                continue;
                            }
                            revisionBeforeTransfer = module.state().getRevision();
                            handBeforeTransfer = hand.getStackNbt();
                            client.currentScreen.mouseDragged(component.x() + targetX, component.y() + targetY,
                                    GLFW.GLFW_MOUSE_BUTTON_LEFT, targetX - x, targetY - y);
                            client.currentScreen.mouseReleased(component.x() + targetX, component.y() + targetY,
                                    GLFW.GLFW_MOUSE_BUTTON_LEFT);
                            if (!module.busy()) {
                                throw new IllegalStateException("工作台从手掌拖入内景没有发起转移");
                            }
                            return true;
                        }
                    }
                    throw new IllegalStateException("工作台找不到合法存入格位");
                }
            }
        }
        // 容器或身体可能遮住手掌；通过人物空白区域右拖，让下一帧重新投影。
        int x = component.x() + 20;
        int y = component.y() + 40;
        client.currentScreen.mouseClicked(x, y, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        client.currentScreen.mouseDragged(x + 30, y, GLFW.GLFW_MOUSE_BUTTON_RIGHT, 30, 0);
        client.currentScreen.mouseReleased(x + 30, y, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        return false;
    }

    private boolean clickBag(MinecraftClient client, String id) {
        // 只从绘制出的模型表面取点，再通过真正工作台发送按下与释放。
        float[] bounds = component.scene().bagBounds(id);
        for (int y = (int) bounds[1]; y <= bounds[3]; y += 2) {
            for (int x = (int) bounds[0]; x <= bounds[2]; x += 2) {
                if (component.scene().bagHit(id, x, y)) {
                    client.currentScreen.mouseClicked(component.x() + x, component.y() + y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    client.currentScreen.mouseReleased(component.x() + x, component.y() + y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    return true;
                }
            }
        }
        return false;
    }

    private static void clickButton(MinecraftClient client, String title) {
        // 菜单点击覆盖实际入口工厂，不能由测试直接创建 VolumeScreen 替代。
        var button = client.currentScreen.children().stream().filter(ButtonWidget.class::isInstance)
                .map(ButtonWidget.class::cast).filter(value -> value.getMessage().getString().equals(title))
                .findFirst().orElseThrow(() -> new IllegalStateException("缺少按钮: " + title));
        client.currentScreen.mouseClicked(button.getX() + button.getWidth() / 2.0,
                button.getY() + button.getHeight() / 2.0, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        client.currentScreen.mouseReleased(button.getX(), button.getY(), GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    private static void drag(MinecraftClient client, double x, double y, double targetX, double targetY) {
        // 捕获、拖动与释放全部交给顶层工作台处理。
        client.currentScreen.mouseClicked(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        client.currentScreen.mouseDragged(targetX, targetY, GLFW.GLFW_MOUSE_BUTTON_LEFT, targetX - x, targetY - y);
        client.currentScreen.mouseReleased(targetX, targetY, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
}
