package dev.kizuna.backpack3d.smoke;

import java.io.IOException;

/** 仅向当前 Xvfb DISPLAY 注入 Shift，不触碰用户桌面。 */
final class SmokeShift {
    private static final String SCRIPT = """
            import ctypes, sys
            x11 = ctypes.CDLL("libX11.so.6")
            xtest = ctypes.CDLL("libXtst.so.6")
            x11.XOpenDisplay.restype = ctypes.c_void_p
            x11.XKeysymToKeycode.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
            x11.XKeysymToKeycode.restype = ctypes.c_uint
            x11.XFlush.argtypes = [ctypes.c_void_p]
            x11.XCloseDisplay.argtypes = [ctypes.c_void_p]
            xtest.XTestFakeKeyEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]
            display = x11.XOpenDisplay(None)
            if not display:
                raise RuntimeError("Xvfb DISPLAY unavailable")
            key = x11.XKeysymToKeycode(display, 0xffe1)
            xtest.XTestFakeKeyEvent(display, key, int(sys.argv[1] == "press"), 0)
            x11.XFlush(display)
            x11.XCloseDisplay(display)
            """;

    private SmokeShift() {
        // 夹具不保存任何全局键盘钩子。
    }

    static void press() {
        // 使用 XTest 设置 GLFW 真实读取的按键状态。
        send("press");
    }

    static void release() {
        // 操作结束后立即释放，避免下一次拖放意外保留修饰键。
        send("release");
    }

    private static void send(String action) {
        // 子进程继承本次烟测 DISPLAY；不用 AWT，兼容 Minecraft 的 headless 设置。
        try {
            int result = new ProcessBuilder("python3", "-c", SCRIPT, action).inheritIO().start().waitFor();
            if (result != 0) {
                throw new IllegalStateException("Shift 注入失败");
            }
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}

