package dev.kizuna.backpack3d.smoke;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 把 Xvfb 里的画面录成视频，给报告附录像用。只在环境变量 BS3D_SMOKE_RECORD 指了输出目录时才录，平时什么都不做。
 * 每到一步在日志里记一行「SMOKE_RECORD 开录后第几秒 步骤名」，事后照这份时间表给视频配说明。
 */
final class SmokeRecorder {
    private static final String OUTPUT = System.getenv("BS3D_SMOKE_RECORD");
    private static final int FRAMES_PER_SECOND = 20;
    private static final long STOP_WAIT_SECONDS = 10;
    private static final int RECORDED_STEP_TICKS = 25;
    private static final double NANOS_PER_SECOND = 1e9;
    private static Process ffmpeg;
    private static long startedNanos;

    private SmokeRecorder() {
        // 只有静态入口。
    }

    static void start(String name, int width, int height) {
        // 抓当前 DISPLAY（就是这次验收自己的 Xvfb），不碰用户桌面。
        if (OUTPUT == null || ffmpeg != null) {
            return;
        }
        try {
            ffmpeg = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "x11grab",
                    "-framerate", Integer.toString(FRAMES_PER_SECOND), "-video_size", width + "x" + height,
                    "-i", System.getenv("DISPLAY"), "-c:v", "libx264", "-preset", "ultrafast",
                    new File(OUTPUT, name + ".mkv").getPath())
                    .redirectErrorStream(true)
                    .redirectOutput(new File(OUTPUT, name + "-ffmpeg.log"))
                    .start();
        } catch (IOException failure) {
            throw new IllegalStateException("录屏起不来", failure);
        }
        startedNanos = System.nanoTime();
        mark("START");
    }

    /** 录屏时每一小步至少停这么多 tick，人眼才跟得上；不录的时候是 0，验收照常全速走。 */
    static int stepTicks() {
        return ffmpeg == null ? 0 : RECORDED_STEP_TICKS;
    }

    static void mark(String step) {
        if (ffmpeg == null) {
            return;
        }
        double seconds = (System.nanoTime() - startedNanos) / NANOS_PER_SECOND;
        System.out.println(String.format(Locale.ROOT, "SMOKE_RECORD %.2f %s", seconds, step));
    }

    static void stop() {
        // 给 ffmpeg 发 q 让它把文件收好尾；等不到就直接结束这一个进程。
        if (ffmpeg == null) {
            return;
        }
        mark("STOP");
        try {
            ffmpeg.getOutputStream().write('q');
            ffmpeg.getOutputStream().flush();
            if (!ffmpeg.waitFor(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) {
                ffmpeg.destroy();
            }
        } catch (IOException failure) {
            ffmpeg.destroy();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            ffmpeg.destroy();
        }
        ffmpeg = null;
    }
}
