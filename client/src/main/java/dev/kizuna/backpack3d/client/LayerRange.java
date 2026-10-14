package dev.kizuna.backpack3d.client;

/** 容器 Y 轴的可见格层，使用零起点半开区间 [lower, upper)，始终至少保留一层。 */
public final class LayerRange {
    private int height = 1;
    private int lower;
    private int upper = 1;

    public void resize(int height) {
        // 容量变化时保留用户的范围；原本贴着顶层的上界继续跟随容器顶部。
        if (height < 1) {
            throw new IllegalArgumentException("容器至少需要一层");
        }
        boolean followsTop = upper == this.height;
        this.height = height;
        upper = followsTop ? height : Math.min(upper, height);
        lower = Math.min(lower, upper - 1);
    }

    public void moveLower(int boundary) {
        // 两个滑块不能越过彼此，也不能把可见层数压到零。
        lower = Math.max(0, Math.min(upper - 1, boundary));
    }

    public void moveUpper(int boundary) {
        // 上界是最后可见格的顶面，因此最大值正好等于容器高度。
        upper = Math.max(lower + 1, Math.min(height, boundary));
    }

    public boolean intersects(int bottom, int itemHeight) {
        return bottom < upper && bottom + itemHeight > lower;
    }

    public boolean contains(int bottom, int itemHeight) {
        return bottom >= lower && bottom + itemHeight <= upper;
    }

    public boolean full() {
        return lower == 0 && upper == height;
    }

    public int lower() {
        return lower;
    }

    public int upper() {
        return upper;
    }

    public int height() {
        return height;
    }
}
