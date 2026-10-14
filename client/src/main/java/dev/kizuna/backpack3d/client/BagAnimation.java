package dev.kizuna.backpack3d.client;

/** 只推进开合盖动画，人物取景始终固定。时间单位为秒。 */
public final class BagAnimation {
    public static final float LID_SECONDS = 0.45f;
    private static final float MAX_FRAME_SECONDS = 0.05f;
    private float lid;
    private boolean opened;
    private boolean closingClip;
    private float openSeconds = LID_SECONDS;
    private float closeSeconds = LID_SECONDS;

    /** closing 为播放方向，progress 为当前方向从起点到终点的归一化时间。 */
    public record Sample(boolean closing, float progress) {
        public static final Sample CLOSED = new Sample(false, 0);
    }

    public Sample sample() {
        return new Sample(closingClip, closingClip ? 1 - lid : lid);
    }

    public boolean opened() {
        return opened;
    }

    void configure(ModelAsset asset) {
        // 重载后立即使用新资源时长；静态模型不需要等待虚构的开盖时间。
        openSeconds = asset == null ? 0 : asset.openSeconds();
        closeSeconds = asset == null ? 0 : asset.closeSeconds();
    }

    public void open() {
        // 中途反向沿当前片段退回，避免切换两条不同关键帧轨道时模型跳动。
        if (lid == 0) {
            closingClip = false;
        }
        opened = true;
    }

    public void close() {
        // 完全打开后才使用独立 close 动画，未开完时反放正在播放的 open。
        if (lid == 1) {
            closingClip = true;
        }
        opened = false;
    }

    public float lid() {
        return smooth(lid);
    }

    public boolean ready() {
        return opened && lid >= 1;
    }

    public boolean atRest() {
        return !opened && lid <= 0;
    }

    public void advance(float seconds) {
        // 限制卡顿后的单帧步长；取消开盖时从当前进度合上，不跳到端点。
        float delta = Math.min(Math.max(0, seconds), MAX_FRAME_SECONDS);
        float duration = closingClip ? closeSeconds : openSeconds;
        if (opened) {
            lid = duration == 0 ? 1 : Math.min(1, lid + delta / duration);
        } else {
            lid = duration == 0 ? 0 : Math.max(0, lid - delta / duration);
        }
    }

    private static float smooth(float value) {
        return value * value * (3 - 2 * value);
    }
}
