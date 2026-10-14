package dev.kizuna.backpack3d.protocol;

import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;

import java.util.List;

/** 体积规则不依赖渲染和宿主线程，客户端预测与服务端校验复用同一实现。 */
public final class Volumes {
    public static final String POUCH = "pouch";
    public static final String BASKET = "basket";
    public static final String PLAYER = "player";
    public static final String WORN = "worn";
    public static final Size UNIT = size(1, 1, 1);
    public static final List<Container> CONTAINERS =
            List.of(
                    Container.newBuilder()
                            .setId(POUCH)
                            .setTitle("草包")
                            .setSize(size(1, 2, 2))
                            .setModel("grass_pouch")
                            .build(),
                    Container.newBuilder()
                            .setId(BASKET)
                            .setTitle("背篓")
                            .setSize(size(3, 3, 3))
                            .setModel("back_basket")
                            .build());

    private Volumes() {}

    public static Size size(int width, int height, int depth) {
        return Size.newBuilder().setWidth(width).setHeight(height).setDepth(depth).build();
    }

    public static Cell cell(int x, int y, int z) {
        return Cell.newBuilder().setX(x).setY(y).setZ(z).build();
    }

    public static Container container(String id) {
        // 身份只来自注册目录，不能用客户端尺寸扩大容量。
        return CONTAINERS.stream()
                .filter(value -> value.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知容器"));
    }

    public static List<Mount> mounts(String container) {
        // 挂点由容器类型约束；客户端不能把大容器塞到腰侧。
        return switch (container) {
            case POUCH ->
                    List.of(
                            Mount.WAIST_RIGHT_FRONT,
                            Mount.WAIST_LEFT_FRONT,
                            Mount.WAIST_LEFT_BACK,
                            Mount.WAIST_RIGHT_BACK);
            case BASKET -> List.of(Mount.TORSO_BACK, Mount.TORSO_FRONT);
            default -> throw new IllegalArgumentException("未知容器");
        };
    }

    public static Mount mount(SavedInventory data, String container) {
        // 先查保存的挂点；旧存档没有挂点时使用该容器的默认位置。
        Mount defaultMount = mounts(container).get(0);
        for (Placement placement : data.getPlacementsList()) {
            if (placement.getContainer().equals(container)) {
                return placement.getMount();
            }
        }
        return defaultMount;
    }

    public static Size baseSize(Item item) {
        // 旧存档无 base_size 且朝向为零，可以直接复用原始占格。
        return item.hasBaseSize() ? item.getBaseSize() : item.getSize();
    }

    public static boolean fits(
            List<Item> items, String target, Cell position, Size size, String ignoredItem) {
        return fits(items, target, container(target).getSize(), position, size, ignoredItem);
    }

    public static boolean fits(
            List<Item> items,
            String target,
            Size bounds,
            Cell position,
            Size size,
            String ignoredItem) {
        // 先检查每个轴的边界，再检查已有物品；贴边不算重叠。
        if (!axisFits(position.getX(), size.getWidth(), bounds.getWidth())) {
            return false;
        }
        if (!axisFits(position.getY(), size.getHeight(), bounds.getHeight())) {
            return false;
        }
        if (!axisFits(position.getZ(), size.getDepth(), bounds.getDepth())) {
            return false;
        }
        for (Item item : items) {
            if (!item.getContainer().equals(target) || item.getId().equals(ignoredItem)) {
                continue;
            }
            if (overlaps(position, size, item.getCell(), item.getSize())) {
                return false;
            }
        }
        return true;
    }

    private static boolean axisFits(int start, int length, int capacity) {
        // 用减法检查剩余空间，避免客户端传入极大坐标后加法溢出。
        if (start < 0 || length <= 0 || length > capacity) {
            return false;
        }
        return start <= capacity - length;
    }

    public static boolean overlaps(Cell first, Size firstSize, Cell second, Size secondSize) {
        boolean overlapsX =
                first.getX() < second.getX() + secondSize.getWidth()
                        && second.getX() < first.getX() + firstSize.getWidth();
        boolean overlapsY =
                first.getY() < second.getY() + secondSize.getHeight()
                        && second.getY() < first.getY() + firstSize.getHeight();
        boolean overlapsZ =
                first.getZ() < second.getZ() + secondSize.getDepth()
                        && second.getZ() < first.getZ() + firstSize.getDepth();
        return overlapsX && overlapsY && overlapsZ;
    }

    public static Cell firstFree(List<Item> items, String target, Size size, String ignored) {
        return firstFree(items, target, container(target).getSize(), size, ignored);
    }

    public static Cell firstFree(
            List<Item> items, String target, Size bounds, Size size, String ignored) {
        // 从底层开始摆放；格子数很少，确定性的扫描比额外索引更容易保持一致。
        for (int y = 0; y < bounds.getHeight(); y++) {
            for (int z = 0; z < bounds.getDepth(); z++) {
                for (int x = 0; x < bounds.getWidth(); x++) {
                    var cell = cell(x, y, z);
                    if (fits(items, target, bounds, cell, size, ignored)) {
                        return cell;
                    }
                }
            }
        }
        throw new IllegalArgumentException("目标容器没有足够的连续空间");
    }
}
