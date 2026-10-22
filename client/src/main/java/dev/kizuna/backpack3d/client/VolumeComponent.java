package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.systems.RenderSystem;
import io.wispforest.owo.ui.base.BaseComponent;
import io.wispforest.owo.ui.core.OwoUIDrawContext;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.client.MinecraftClient;

/** 工作台中的完整背包场景，复用独立页面的相机、命中和权威操作。 */
final class VolumeComponent extends BaseComponent implements AutoCloseable {
    private final VolumeScreen scene;
    private boolean disposed;

    VolumeComponent(VolumeModule module, Runnable closeWindow) {
        // 工厂只创建组件，不切换 Minecraft 屏幕；窗口 scope 负责最终释放。
        scene = new VolumeScreen(module, closeWindow);
        sizing(Sizing.fill(100), Sizing.fill(100));
        scene.init(MinecraftClient.getInstance(), 1, 1);
    }

    VolumeScreen scene() {
        // 模块沿用场景生命周期和服务器打开事件，避免维护第二套容器状态。
        return scene;
    }

    @Override
    public void draw(OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 三维命中保留场景局部坐标；统一模型视图平移同时移动二维控件与独立顶点批次。
        if (disposed) {
            return;
        }
        if (scene.width != width || scene.height != height) {
            scene.resize(MinecraftClient.getInstance(), Math.max(1, width), Math.max(1, height));
        }
        context.draw();
        context.enableScissor(x, y, x + width, y + height);
        var modelView = RenderSystem.getModelViewStack();
        modelView.push();
        modelView.translate(x, y, 0);
        RenderSystem.applyModelViewMatrix();
        scene.scissorOrigin(x, y);
        try {
            scene.render(context, mouseX - x, mouseY - y, partialTicks);
            context.draw();
        } finally {
            // 渲染失败也恢复全局变换，避免其他业务窗口沿用背包的坐标或裁剪。
            modelView.pop();
            RenderSystem.applyModelViewMatrix();
            context.disableScissor();
        }
    }

    @Override
    public boolean canFocus(FocusSource source) {
        // 三维区域没有子控件，组件本身接收物品旋转与退出按键。
        return true;
    }

    @Override
    public boolean onMouseDown(double x, double y, int button) {
        // owo 已将事件转为组件局部坐标，与场景的命中缓存一致。
        return scene.mouseClicked(x, y, button);
    }

    @Override
    public boolean onMouseDrag(double x, double y, double dx, double dy, int button) {
        // 抓取后的越界拖动仍由原场景保持同一输入目标。
        return scene.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean onMouseUp(double x, double y, int button) {
        // 原场景检查真实落点，窗口外释放不提交库存转移。
        if (x < 0 || y < 0 || x >= width || y >= height) {
            scene.cancelInput();
            return true;
        }
        return scene.mouseReleased(x, y, button);
    }

    @Override
    public boolean onMouseScroll(double x, double y, double amount) {
        // 缩放与帮助滚动由命中的场景区域决定。
        return scene.mouseScrolled(x, y, amount);
    }

    @Override
    public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
        // 旋转、取消和帮助继续使用同一绑定；场景退出只关闭所属窗口。
        return scene.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onFocusLost() {
        // 工作台切换焦点会取消捕获，不把后续释放当作一次存取。
        scene.cancelInput();
        super.onFocusLost();
    }

    @Override
    public void close() {
        // scope 可能因关窗或断线释放；幂等清理不会再操作已释放的场景。
        if (!disposed) {
            disposed = true;
            scene.removed();
        }
    }
}
