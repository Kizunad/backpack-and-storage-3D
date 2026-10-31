package dev.kizuna.backpack3d.smoke;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * 逐帧截图：26.2 界面先提取渲染状态、再统一绘制，同一次提取里画完立刻截图拿到的是上一帧。
 *
 * <p>探针在某帧提取时画一张，下一帧提取开始时截取主渲染目标（此时仍是上一帧的完整画面），
 * GPU 拷贝完成回调后才允许画下一张。
 */
final class FrameCapture {
    private final Minecraft minecraft;
    private Consumer<NativeImage> drawn;
    private boolean waiting;
    private RuntimeException failure;

    FrameCapture(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    /** 本帧能否绘制下一张；上一帧画过的画面会在这里截取，截取帧本身不再绘制。 */
    boolean ready() {
        // 回调由 GPU 任务队列执行，校验失败带回探针所在的界面提取流程再抛出。
        if (failure != null) {
            throw failure;
        }
        if (drawn != null) {
            Consumer<NativeImage> consumer = drawn;
            drawn = null;
            waiting = true;
            Screenshot.takeScreenshot(
                    minecraft.gameRenderer.mainRenderTarget(),
                    image -> {
                        // 回调获得图像所有权，处理完立即释放本机内存。
                        try (image) {
                            consumer.accept(image);
                        } catch (RuntimeException error) {
                            failure = error;
                        } finally {
                            waiting = false;
                        }
                    });
            return false;
        }
        return !waiting;
    }

    /** 本帧已提交画面，下一帧截取后交给 consumer。 */
    void expect(Consumer<NativeImage> consumer) {
        drawn = consumer;
    }
}
