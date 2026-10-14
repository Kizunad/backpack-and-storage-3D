package dev.kizuna.backpack3d.protocol;

import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 24 种无镜像的正交朝向；行主序矩阵将物品局部轴变换到容器轴。 */
public final class OrthogonalRotation {
    public static final int COUNT = 24;
    private static final int AXES = 3;
    private static final int FULL_TURN_STEPS = 4;
    private static final int[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    private static final int[] YAW = {0, 0, 1, 0, 1, 0, -1, 0, 0};
    private static final int[] PITCH = {1, 0, 0, 0, 0, -1, 0, 1, 0};
    // 协议索引固定为从单位阵开始、按 Y 然后 X 的广度优先遍历，禁止重排。
    private static final List<int[]> ROTATIONS = build();

    private OrthogonalRotation() {}

    private static List<int[]> build() {
        var result = new ArrayList<int[]>();
        result.add(IDENTITY);
        for (int index = 0; index < result.size(); index++) {
            for (var turn : List.of(YAW, PITCH)) {
                var next = multiply(turn, result.get(index));
                if (result.stream().noneMatch(value -> Arrays.equals(value, next))) {
                    result.add(next);
                }
            }
        }
        return List.copyOf(result);
    }

    public static void require(int orientation) {
        // protobuf uint32 在 Java 中可能表现为负数，必须同时检查下界。
        if (orientation < 0 || orientation >= COUNT) {
            throw new IllegalArgumentException("物品朝向无效");
        }
    }

    public static int[] matrix(int orientation) {
        require(orientation);
        return ROTATIONS.get(orientation).clone();
    }

    public static int turn(int orientation, boolean vertical, boolean reverse) {
        // 左乘表示沿容器轴旋转，交替操作 R/T 不会丢失之前的朝向。
        int[] value = matrix(orientation);
        int steps = reverse ? FULL_TURN_STEPS - 1 : 1;
        for (int step = 0; step < steps; step++) {
            value = multiply(vertical ? PITCH : YAW, value);
        }
        for (int index = 0; index < ROTATIONS.size(); index++) {
            if (Arrays.equals(value, ROTATIONS.get(index))) {
                return index;
            }
        }
        throw new IllegalStateException("非正交旋转");
    }

    public static Size size(Size base, int orientation) {
        // 仅置换轴，不改变体积；有符号方向影响模型，绝对值影响占格。
        var m = matrix(orientation);
        int[] source = {base.getWidth(), base.getHeight(), base.getDepth()};
        int[] result = new int[AXES];
        for (int row = 0; row < AXES; row++) {
            for (int column = 0; column < AXES; column++) {
                result[row] += Math.abs(m[row * AXES + column]) * source[column];
            }
        }
        return Volumes.size(result[0], result[1], result[2]);
    }

    private static int[] multiply(int[] a, int[] b) {
        // 使用整数矩阵避免连续旋转累积浮点误差，索引可稳定写入存档。
        int[] result = new int[AXES * AXES];
        for (int row = 0; row < AXES; row++) {
            for (int column = 0; column < AXES; column++) {
                for (int axis = 0; axis < AXES; axis++) {
                    result[row * AXES + column] += a[row * AXES + axis] * b[axis * AXES + column];
                }
            }
        }
        return result;
    }
}
