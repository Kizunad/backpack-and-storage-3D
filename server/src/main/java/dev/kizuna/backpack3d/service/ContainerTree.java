package dev.kizuna.backpack3d.service;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.*;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;

import net.minecraft.item.ItemStack;

import java.util.*;

/** 一次事务的容器树副本；所有内容最终写回所属的真实容器物品，禁止跨物品悬挂库存。 */
public final class ContainerTree {
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Set<String> itemIds = new HashSet<>();
    private final List<Node> roots = new ArrayList<>();
    private final List<ItemStack> inventory;
    private final Map<String, ItemStack> ground;
    private final int waistLimit;

    public ContainerTree(SavedInventory data, List<ItemStack> inventory) {
        this(data, inventory, new LinkedHashMap<>());
    }

    public ContainerTree(
            SavedInventory data, List<ItemStack> inventory, Map<String, ItemStack> ground) {
        // 调用者提供的槽位列表属于本次候选，解析期间不修改玩家真实库存。
        this.inventory = inventory;
        this.ground = ground;
        waistLimit = waistLimit(data);
        for (WornContainer worn : data.getWornList()) {
            Node root = read(StackCodec.decode(worn.getStackNbt()), null, 0);
            root.mount = worn.getMount();
            root.angle = worn.getWaistAngleDegrees();
            roots.add(root);
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.get(slot);
            if (ContainerItems.definition(stack) != null) {
                Node root = read(stack, null, 0);
                root.playerSlot = slot;
                roots.add(root);
            }
        }
        for (var entry : ground.entrySet()) {
            Node root = read(entry.getValue(), null, 0);
            if (!root.id.equals(entry.getKey())) {
                throw new IllegalArgumentException("地面容器身份不一致");
            }
            root.grounded = true;
            roots.add(root);
        }
        validateEquipment();
    }

    public static int waistLimit(SavedInventory data) {
        // 缺省和显式零含义不同：零表示禁用腰挂，不能用零值回退默认数量。
        int limit =
                data.hasWaistLimit() ? data.getWaistLimit() : ContainerItems.DEFAULT_WAIST_LIMIT;
        if (limit < 0 || limit > ContainerItems.MAX_WAIST_LIMIT) {
            throw new IllegalArgumentException("腰部携带数量上限无效");
        }
        return limit;
    }

    private Node read(ItemStack stack, Node parent, int depth) {
        // 同一棵树中容器身份必须唯一；同时限制深度和数量，避免循环或嵌套数据耗尽资源。
        var type = ContainerItems.definition(stack);
        if (type == null || stack.getCount() != 1) {
            throw new IllegalArgumentException("不是有效的单件容器物品");
        }
        if (depth > ContainerItems.MAX_NESTING_DEPTH
                || nodes.size() >= ContainerItems.MAX_CONTAINERS) {
            throw new IllegalArgumentException("容器嵌套层数或数量超过上限");
        }
        String id = ContainerItems.identity(stack);
        if (nodes.containsKey(id)) {
            throw new IllegalArgumentException("容器身份重复，无法确定唯一库存");
        }
        Node node = new Node(id, stack.copy(), type, parent);
        nodes.put(id, node);
        var contents = ContainerItems.contents(stack);
        for (Item item : contents.getItemsList()) {
            if (!TransferService.validId(item.getId()) || !itemIds.add(item.getId())) {
                throw new IllegalArgumentException("容器物品实例身份无效或重复");
            }
            if (!item.getContainer().equals(id)
                    || !Volumes.fits(
                            contents.getItemsList(),
                            id,
                            type.inside(),
                            item.getCell(),
                            item.getSize(),
                            item.getId())) {
                throw new IllegalArgumentException("容器内物品越界或重叠");
            }
            Size expected = OrthogonalRotation.size(Volumes.baseSize(item), item.getOrientation());
            if (!expected.equals(item.getSize())) {
                throw new IllegalArgumentException("物品朝向和尺寸不一致");
            }
            ItemStack childStack = StackCodec.decode(item.getStackNbt());
            Node child =
                    ContainerItems.definition(childStack) == null
                            ? null
                            : read(childStack, node, depth + 1);
            if (child != null
                    && (!item.getId().equals(child.id)
                            || !Volumes.baseSize(item).equals(child.type.outside()))) {
                throw new IllegalArgumentException("容器实物身份或外部占格不一致");
            }
            node.entries.add(new Entry(item, child));
        }
        return node;
    }

    private Node node(String id) {
        // 快照之后目标可能被原版槽位操作移走，事务必须重新确认它仍属于玩家。
        Node node = nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException("目标容器不存在或已移走");
        }
        return node;
    }

    public Entry take(String itemId) {
        // 装备根物品与容器内部物品走同一移动流程，取走容器会携带整个子树。
        for (Node root : List.copyOf(roots)) {
            if (!root.grounded && root.playerSlot < 0 && root.id.equals(itemId)) {
                roots.remove(root);
                return new Entry(display(root, Volumes.WORN), root);
            }
        }
        for (Node node : nodes.values()) {
            for (int index = 0; index < node.entries.size(); index++) {
                Entry entry = node.entries.get(index);
                if (entry.item.getId().equals(itemId)) {
                    node.entries.remove(index);
                    return entry;
                }
            }
        }
        throw new IllegalArgumentException("物品已不存在");
    }

    public Entry takePlayer(int slot, ItemStack stack, String itemId, Size size) {
        // 原版来源的完整内容已由指纹验证，容器保留自己的 UUID，不用操作号改写身份。
        Node container = null;
        if (ContainerItems.definition(stack) != null) {
            container = node(ContainerItems.identity(stack));
            roots.remove(container);
            itemId = container.id;
            size = container.type.outside();
        }
        inventory.set(slot, ItemStack.EMPTY);
        Item item =
                Item.newBuilder()
                        .setId(itemId)
                        .setContainer(Volumes.PLAYER)
                        .setStackNbt(StackCodec.encode(stack))
                        .setBaseSize(size)
                        .setSize(size)
                        .build();
        return new Entry(item, container);
    }

    public ItemStack stack(Entry entry) {
        return entry.container == null
                ? StackCodec.decode(entry.item.getStackNbt())
                : pack(entry.container);
    }

    public void place(Entry entry, MoveRequest request) {
        // 先沿目标父链拒绝自身和子孙，再计算真实三维占格；体积更大时不能靠嵌套绕过。
        Node target = node(request.getTarget());
        for (Node ancestor = target; ancestor != null; ancestor = ancestor.parent) {
            if (ancestor == entry.container) {
                throw new IllegalArgumentException("不能把容器放进自身或它的子容器");
            }
        }
        Size base = Volumes.baseSize(entry.item);
        Size rotated = OrthogonalRotation.size(base, request.getOrientation());
        List<Item> items = target.entries.stream().map(value -> value.item).toList();
        Cell cell =
                request.getAutoPlace()
                        ? Volumes.firstFree(items, target.id, target.type.inside(), rotated, "")
                        : request.getCell();
        if (!Volumes.fits(items, target.id, target.type.inside(), cell, rotated, "")) {
            throw new IllegalArgumentException("目标格被占用或空间不足");
        }
        if (entry.item.getContainer().equals(target.id)
                && entry.item.getCell().equals(cell)
                && entry.item.getOrientation() == request.getOrientation()) {
            throw new IllegalArgumentException("物品已经位于此处");
        }
        entry.item =
                entry.item.toBuilder()
                        .setContainer(target.id)
                        .setCell(cell)
                        .setBaseSize(base)
                        .setSize(rotated)
                        .setOrientation(request.getOrientation())
                        .build();
        if (entry.container != null) {
            entry.container.parent = target;
            entry.container.playerSlot = -1;
        }
        target.entries.add(entry);
    }

    public void wear(Entry entry, Placement placement) {
        // 可装备部位由物品类型限定；腰环角度和数量属于玩家服务器状态。
        Node node = entry.container;
        if (node == null || !node.type.mounts().contains(placement.getMount())) {
            throw new IllegalArgumentException("这个物品不能装备到该部位");
        }
        node.parent = null;
        node.playerSlot = -1;
        node.mount = placement.getMount();
        node.angle =
                placement.hasWaistAngleDegrees()
                        ? placement.getWaistAngleDegrees()
                        : defaultAngle(placement.getMount());
        roots.add(node);
        validateEquipment();
    }

    public void putPlayer(Entry entry, int slot) {
        // 子树重新成为原版物品后，打包时把最新内容写入同一个槽位候选。
        if (entry.container != null) {
            Node node = entry.container;
            node.parent = null;
            node.playerSlot = slot;
            roots.add(node);
        }
    }

    public void reposition(Placement placement) {
        Node node = node(placement.getContainer());
        if (node.grounded || node.parent != null || node.playerSlot >= 0 || !roots.contains(node)) {
            throw new IllegalArgumentException("容器当前没有装备在身上");
        }
        roots.remove(node);
        wear(new Entry(display(node, Volumes.WORN), node), placement);
    }

    private void validateEquipment() {
        // 胸前、背后各一个独立位置；腰部按数量限制，连续角度不折回四个固定槽位。
        Set<Mount> torso = new HashSet<>();
        int waistCount = 0;
        for (Node node : roots) {
            if (node.grounded || node.playerSlot >= 0) {
                continue;
            }
            if (!node.type.mounts().contains(node.mount)) {
                throw new IllegalArgumentException("装备挂点不适合这个容器");
            }
            if (node.type.waist()) {
                if (!Float.isFinite(node.angle)
                        || node.angle < 0
                        || node.angle >= ContainerItems.FULL_CIRCLE_DEGREES) {
                    throw new IllegalArgumentException("腰部角度必须位于 [0,360)");
                }
                waistCount++;
            } else if (!torso.add(node.mount)) {
                throw new IllegalArgumentException("这个胸背位置已有容器");
            }
        }
        if (waistCount > waistLimit) {
            throw new IllegalArgumentException("腰部最多装备 " + waistLimit + " 件物品");
        }
    }

    public static float defaultAngle(Mount mount) {
        // 腰环以正前为零，旧四点挂法迁移为四个相应的连续角度，单位为度。
        return switch (mount) {
            case WAIST_LEFT_FRONT -> 45;
            case WAIST_LEFT_BACK -> 135;
            case WAIST_RIGHT_BACK -> 225;
            case WAIST_RIGHT_FRONT -> 315;
            default -> 0;
        };
    }

    private ItemStack pack(Node node) {
        // 从叶子向上更新内容，只写本次副本。原版槽位与装备物品最终一起落盘。
        List<Item> items = new ArrayList<>();
        for (Entry entry : node.entries) {
            Item item = entry.item;
            if (entry.container != null) {
                item =
                        item.toBuilder()
                                .setStackNbt(StackCodec.encode(pack(entry.container)))
                                .build();
            }
            items.add(item);
        }
        ItemStack stack = node.stack.copy();
        ContainerItems.setContents(stack, items);
        return stack;
    }

    public List<WornContainer> worn() {
        // 由根向外输出保存候选，同时把原版根容器的最新内容写回候选槽位。
        List<WornContainer> result = new ArrayList<>();
        for (Node node : roots) {
            ItemStack stack = pack(node);
            if (node.grounded) {
                ground.put(node.id, stack);
            } else if (node.playerSlot >= 0) {
                inventory.set(node.playerSlot, stack);
            } else {
                result.add(
                        WornContainer.newBuilder()
                                .setStackNbt(StackCodec.encode(stack))
                                .setMount(node.mount)
                                .setWaistAngleDegrees(node.angle)
                                .build());
            }
        }
        return result;
    }

    public List<Container> containers() {
        // 从根递归展开当前可访问的容器，不把已移出这棵树的节点发给客户端。
        List<Container> result = new ArrayList<>();
        for (Node root : roots) {
            collectContainers(root, result);
        }
        return result;
    }

    private void collectContainers(Node node, List<Container> result) {
        // 身体、原版槽位和父容器三种位置互斥，客户端据此决定外观与窗口身份。
        var value =
                Container.newBuilder()
                        .setId(node.id)
                        .setTitle(node.type.title())
                        .setSize(node.type.inside())
                        .setOuterSize(node.type.outside())
                        .setModel(node.type.model());
        if (node.parent != null) {
            value.setParent(node.parent.id);
        } else if (node.grounded) {
            value.setGrounded(true);
        } else if (node.playerSlot >= 0) {
            value.setPlayerSlot(node.playerSlot);
        } else {
            value.setWorn(true).setMount(node.mount).setWaistAngleDegrees(node.angle);
        }
        result.add(value.build());
        for (Entry entry : node.entries) {
            if (entry.container != null) {
                collectContainers(entry.container, result);
            }
        }
    }

    public List<Item> items() {
        // 身体容器也提供物品条目，原版槽位则通过单独的槽位快照供拖动引用。
        List<Item> result = new ArrayList<>();
        for (Node root : roots) {
            if (!root.grounded && root.playerSlot < 0) {
                result.add(display(root, Volumes.WORN));
            }
            collectItems(root, result);
        }
        return result;
    }

    private void collectItems(Node node, List<Item> result) {
        // 展开的条目供内景绘制，容器实物的 NBT 仍携带完整子树以支持再次取出。
        for (Entry entry : node.entries) {
            result.add(
                    entry.container == null
                            ? entry.item
                            : entry.item.toBuilder()
                                    .setStackNbt(StackCodec.encode(pack(entry.container)))
                                    .build());
            if (entry.container != null) {
                collectItems(entry.container, result);
            }
        }
    }

    private Item display(Node node, String location) {
        return Item.newBuilder()
                .setId(node.id)
                .setContainer(location)
                .setBaseSize(node.type.outside())
                .setSize(node.type.outside())
                .setStackNbt(StackCodec.encode(pack(node)))
                .build();
    }

    public static final class Entry {
        private Item item;
        private final Node container;

        Entry(Item item, Node container) {
            this.item = item;
            this.container = container;
        }

        public Item item() {
            return item;
        }
    }

    private static final class Node {
        private final String id;
        private final ItemStack stack;
        private final ContainerItems.Definition type;
        private final List<Entry> entries = new ArrayList<>();
        private Node parent;
        private int playerSlot = -1;
        private boolean grounded;
        private Mount mount = Mount.MOUNT_DEFAULT;
        private float angle;

        Node(String id, ItemStack stack, ContainerItems.Definition type, Node parent) {
            this.id = id;
            this.stack = stack;
            this.type = type;
            this.parent = parent;
        }
    }
}
