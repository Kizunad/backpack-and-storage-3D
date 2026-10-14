package dev.kizuna.backpack3d.client;

import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 按面相邻的占用格合并轮廓，去掉共面接缝，保留外角、凹角和空洞。 */
final class OccupiedOutline {
    private OccupiedOutline() {}

    static List<Edge> build(List<Item> items, String container, LayerRange range) {
        // 先裁出本窗口可见的占格，跨物品实例的相邻格也使用同一份集合判断连接。
        Set<Voxel> occupied = new HashSet<>();
        Set<Edge> candidates = new HashSet<>();
        for (Item item : items) {
            if (!item.getContainer().equals(container)) {
                continue;
            }
            var cell = item.getCell();
            var size = item.getSize();
            int bottom = Math.max(cell.getY(), range.lower());
            int top = Math.min(cell.getY() + size.getHeight(), range.upper());
            for (int y = bottom; y < top; y++) {
                for (int z = cell.getZ(); z < cell.getZ() + size.getDepth(); z++) {
                    for (int x = cell.getX(); x < cell.getX() + size.getWidth(); x++) {
                        occupied.add(new Voxel(x, y, z));
                        addCellEdges(candidates, x, y, z);
                    }
                }
            }
        }
        List<Edge> outline = new ArrayList<>();
        for (Edge edge : candidates) {
            if (isContour(edge, occupied)) {
                outline.add(edge);
            }
        }
        return outline;
    }

    private static void addCellEdges(Set<Edge> edges, int x, int y, int z) {
        // 每条边用正轴方向的起点表示，相邻格贡献的同一条边只保存一次。
        for (int first = 0; first <= 1; first++) {
            for (int second = 0; second <= 1; second++) {
                edges.add(new Edge(x, y + first, z + second, Axis.X));
                edges.add(new Edge(x + first, y, z + second, Axis.Y));
                edges.add(new Edge(x + first, y + second, z, Axis.Z));
            }
        }
    }

    private static boolean isContour(Edge edge, Set<Voxel> occupied) {
        // 垂直于边看周围四格：一格是外角，三格是凹角；两格只在对角相接时保留边。
        boolean first = adjacent(edge, occupied, -1, -1);
        boolean second = adjacent(edge, occupied, 0, -1);
        boolean third = adjacent(edge, occupied, -1, 0);
        boolean fourth = adjacent(edge, occupied, 0, 0);
        int count = (first ? 1 : 0) + (second ? 1 : 0) + (third ? 1 : 0) + (fourth ? 1 : 0);
        if (count == 1 || count == 3) {
            return true;
        }
        return count == 2 && (first && fourth || second && third);
    }

    private static boolean adjacent(Edge edge, Set<Voxel> occupied, int first, int second) {
        return occupied.contains(
                switch (edge.axis()) {
                    case X -> new Voxel(edge.x(), edge.y() + first, edge.z() + second);
                    case Y -> new Voxel(edge.x() + first, edge.y(), edge.z() + second);
                    case Z -> new Voxel(edge.x() + first, edge.y() + second, edge.z());
                });
    }

    enum Axis {
        X,
        Y,
        Z
    }

    /** 起点为容器坐标，单位为格；沿指定正轴延伸一格。 */
    record Edge(int x, int y, int z, Axis axis) {
        int endX() {
            return x + (axis == Axis.X ? 1 : 0);
        }

        int endY() {
            return y + (axis == Axis.Y ? 1 : 0);
        }

        int endZ() {
            return z + (axis == Axis.Z ? 1 : 0);
        }
    }

    private record Voxel(int x, int y, int z) {}
}
