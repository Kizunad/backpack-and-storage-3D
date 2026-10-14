package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import org.lwjgl.opengl.GL11;

/** GUI 场景之间保留颜色，只清理深度；场景内部仍使用正常的模型遮挡。 */
final class GuiSceneDepth {
    private GuiSceneDepth() {}

    static void clear(DrawContext context) {
        // 必须先提交批次，否则排队中的界面底板会在清理之后重新写入深度。
        // 调用者若启用了裁剪，清理只作用于该内景，不触及其他窗口。
        context.draw();
        RenderSystem.depthMask(true);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
    }
}
