package dev.kizuna.backpack3d.service;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.ArmorLayers;
import dev.kizuna.backpack3d.protocol.PlayerSlots;
import dev.kizuna.backpack3d.item.WearableItems;
import dev.kizuna.backpack3d.item.WearableLayers;
import dev.kizuna.backpack3d.protocol.OrthogonalRotation;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Cell;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Container;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.MoveRequest;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Placement;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Size;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornContainer;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornWearable;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Wearable;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WearableSlot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/** 一次事务的容器树副本；所有内容最终写回所属的真实容器物品，禁止跨物品悬挂库存。 */
public final class ContainerTree {
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Set<String> itemIds = new HashSet<>();
    private final List<Node> roots = new ArrayList<>();
    private final List<WearableNode> wearableRoots = new ArrayList<>();
    private final Map<String, WearableNode> wearableNodes = new LinkedHashMap<>();
    private final Set<String> rootIds = new HashSet<>();
    private final List<ItemStack> inventory;
    private final Map<String, ItemStack> ground;
    private final int waistLimit;
    private final HolderLookup.Provider registries;

    public ContainerTree(HolderLookup.Provider registries, SavedInventory data, List<ItemStack> inventory) {
        this(registries, data, inventory, new LinkedHashMap<>());
    }

    public ContainerTree(
            HolderLookup.Provider registries,
            SavedInventory data,
            List<ItemStack> inventory,
            Map<String, ItemStack> ground) {
        // 调用者提供的槽位列表属于本次候选，解析期间不修改玩家真实库存。
        this.registries = registries;
        this.inventory = inventory;
        this.ground = ground;
        waistLimit = waistLimit(data);
        readWorn(data);
        readInventory();
        for (var entry : ground.entrySet()) {
            Node root = read(entry.getValue(), null, 0);
            if (!root.id.equals(entry.getKey())) {
                throw new IllegalArgumentException("地面容器身份不一致");
            }
            requireRootIdentity(root.id);
            root.grounded = true;
            roots.add(root);
        }
        validateEquipment();
    }

    private void readWorn(SavedInventory data) {
        // 两种装备都从真实栈恢复；外观层必须与服务端注册类型完全一致。
        for (WornContainer worn : data.getWornList()) {
            Node root = read(StackCodec.decode(worn.getStackNbt(), registries), null, 0);
            requireRootIdentity(root.id);
            root.mount = worn.getMount();
            root.angle = worn.getWaistAngleDegrees();
            roots.add(root);
        }
        for (WornWearable worn : data.getWornWearablesList()) {
            ItemStack stack = StackCodec.decode(worn.getStackNbt(), registries);
            WearableNode root = readWearable(stack, worn.getBaseSize());
            if (!root.id.equals(worn.getId()) || !root.type.slots().equals(worn.getSlotsList())) {
                throw new IllegalArgumentException("穿戴物品身份或外观层与真实物品不一致");
            }
            requireRootIdentity(root.id);
            wearableRoots.add(root);
        }
    }

    private void readInventory() {
        // 仅初始化候选副本，失败或放弃候选不会给原库存改写身份。
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.get(slot);
            if (ContainerItems.definition(stack) != null) {
                ItemStack initialized = stack.copy();
                ContainerItems.initializeStack(initialized);
                inventory.set(slot, initialized);
                Node root = read(initialized, null, 0);
                requireRootIdentity(root.id);
                root.playerSlot = slot;
                roots.add(root);
            } else if (WearableItems.definition(stack) != null) {
                ItemStack initialized = stack.copy();
                WearableItems.initializeStack(initialized);
                inventory.set(slot, initialized);
                WearableNode root = readWearable(initialized, Volumes.UNIT);
                requireRootIdentity(root.id);
                root.playerSlot = slot;
                wearableRoots.add(root);
            }
        }
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
        if (nodes.containsKey(id) || wearableNodes.containsKey(id)) {
            throw new IllegalArgumentException("容器身份重复，无法确定唯一库存");
        }
        Node node = new Node(id, stack.copy(), type, parent);
        nodes.put(id, node);
        readContents(node, depth);
        return node;
    }

    private void readContents(Node node, int depth) {
        // 内景普通物品、容器与穿戴物共享实例身份校验，穿戴物收纳后仍保留原类型。
        String id = node.id;
        var type = node.type;
        ItemStack stack = node.stack;
        var contents = ContainerItems.contents(stack);
        for (Item item : contents.getItemsList()) {
            if (!TransferService.validId(item.getId()) || rootIds.contains(item.getId())
                    || !itemIds.add(item.getId())) {
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
            node.entries.add(readEntry(item, node, depth));
        }
    }

    private Entry readEntry(Item item, Node parent, int depth) {
        // 嵌套物品的实例 UUID 必须与真实栈一致，不能用内景条目伪装另一件装备。
        ItemStack stack = StackCodec.decode(item.getStackNbt(), registries);
        if (ContainerItems.definition(stack) != null) {
            Node child = read(stack, parent, depth + 1);
            if (!item.getId().equals(child.id) || !Volumes.baseSize(item).equals(child.type.outside())) {
                throw new IllegalArgumentException("容器实物身份或外部占格不一致");
            }
            return new Entry(item, child);
        }
        if (WearableItems.definition(stack) != null) {
            WearableNode child = readWearable(stack, Volumes.baseSize(item));
            if (!item.getId().equals(child.id)) {
                throw new IllegalArgumentException("穿戴物品实物身份不一致");
            }
            return new Entry(item, child);
        }
        return new Entry(item, null, null);
    }

    private WearableNode readWearable(ItemStack stack, Size size) {
        // 穿戴物没有内部容器；收纳尺寸沿用进入装备层前的服务器尺寸。
        var type = WearableItems.definition(stack);
        if (type == null || stack.getCount() != 1) {
            throw new IllegalArgumentException("不是有效的单件穿戴物品");
        }
        if (size.getWidth() < 1 || size.getHeight() < 1 || size.getDepth() < 1) {
            throw new IllegalArgumentException("穿戴物品收纳占格必须为正整数");
        }
        String id = WearableItems.identity(stack);
        var node = new WearableNode(id, stack.copy(), type, size);
        if (nodes.containsKey(id) || wearableNodes.putIfAbsent(id, node) != null) {
            throw new IllegalArgumentException("穿戴物品身份重复");
        }
        return node;
    }

    private void requireRootIdentity(String id) {
        if (!TransferService.validId(id) || itemIds.contains(id) || !rootIds.add(id)) {
            throw new IllegalArgumentException("装备物品身份无效或重复");
        }
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
        for (WearableNode root : List.copyOf(wearableRoots)) {
            if (root.playerSlot < 0 && root.id.equals(itemId)) {
                wearableRoots.remove(root);
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
        PlayerSource source = takePlayerSource(slot, stack, itemId, size);
        Size storedSize = source.size();
        inventory.set(slot, ItemStack.EMPTY);
        Item item = Item.newBuilder()
                .setId(source.id())
                .setContainer(Volumes.PLAYER)
                .setStackNbt(StackCodec.encode(source.stack(), registries))
                .setBaseSize(storedSize)
                .setSize(storedSize)
                .build();
        return new Entry(item, source.container(), source.wearable());
    }

    private PlayerSource takePlayerSource(int slot, ItemStack stack, String itemId, Size size) {
        // 已初始化的候选栈保留真实身份，普通物品才使用本次操作分配的实例 ID。
        if (ContainerItems.definition(stack) != null) {
            ItemStack initialized = inventory.get(slot);
            Node container = node(ContainerItems.identity(initialized));
            roots.remove(container);
            return new PlayerSource(container.id, container.type.outside(), initialized, container, null);
        }
        if (WearableItems.definition(stack) != null) {
            ItemStack initialized = inventory.get(slot);
            String wearableId = WearableItems.identity(initialized);
            WearableNode wearable = wearableNode(wearableId);
            wearableRoots.remove(wearable);
            wearable.size = size;
            return new PlayerSource(wearable.id, size, initialized, null, wearable);
        }
        return new PlayerSource(itemId, size, stack, null, null);
    }

    private record PlayerSource(String id, Size size, ItemStack stack, Node container, WearableNode wearable) {}

    public ItemStack stack(Entry entry) {
        return entry.container == null
                ? StackCodec.decode(entry.item.getStackNbt(), registries)
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
        Cell cell = request.getCell();
        if (request.getAutoPlace()) {
            try {
                cell = Volumes.firstFree(items, target.id, target.type.inside(), rotated, "");
            } catch (IllegalArgumentException full) {
                throw new IllegalArgumentException(target.type.title() + "里放不下");
            }
        }
        if (!Volumes.fits(items, target.id, target.type.inside(), cell, rotated, "")) {
            if (request.getAutoPlace()) {
                throw new IllegalArgumentException(target.type.title() + "里放不下");
            }
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
        if (entry.wearable != null) {
            entry.wearable.playerSlot = -1;
        }
        target.entries.add(entry);
    }

    public void placeCarried(Entry entry, int orientation) {
        // 只搜索身上根容器，腰挂优先，顺序相同则沿用装备顺序；不读取客户端切片范围。
        var targets = roots.stream()
                .filter(node -> node.playerSlot < 0 && !node.grounded && node != entry.container)
                .sorted(Comparator.comparingInt(node -> {
                    if (node.type.waist()) {
                        return 0;
                    }
                    return 1;
                }))
                .toList();
        Size rotated = OrthogonalRotation.size(Volumes.baseSize(entry.item), orientation);
        for (Node target : targets) {
            List<Item> items = target.entries.stream().map(value -> value.item).toList();
            Cell cell;
            try {
                cell = Volumes.firstFree(items, target.id, target.type.inside(), rotated, "");
            } catch (IllegalArgumentException full) {
                continue;
            }
            var request = MoveRequest.newBuilder()
                    .setTarget(target.id)
                    .setOrientation(orientation)
                    .setCell(cell)
                    .build();
            place(entry, request);
            return;
        }
        throw new IllegalArgumentException("随身容器里放不下");
    }

    public void wear(Entry entry, Placement placement) {
        // 可装备部位由物品类型限定；腰环角度和数量属于玩家服务器状态。
        if (entry.container != null) {
            wearContainer(entry.container, placement);
        } else {
            wearWearable(entry.wearable, placement);
        }
        validateEquipment();
    }

    private void wearContainer(Node node, Placement placement) {
        // 容器只接受自身声明的挂点，外观层落点不能被混作腰环请求。
        if (!placement.getWearableLayer().isEmpty() || !node.type.mounts().contains(placement.getMount())) {
            throw new IllegalArgumentException("这个物品不能装备到该部位");
        }
        node.parent = null;
        node.playerSlot = -1;
        node.mount = placement.getMount();
        node.angle = defaultAngle(placement.getMount());
        if (placement.hasWaistAngleDegrees()) {
            node.angle = placement.getWaistAngleDegrees();
        }
        roots.add(node);
    }

    private void wearWearable(WearableNode wearable, Placement placement) {
        // 落点只选择一层，真正占用的全部层来自服务端类型，不能拆开双腕等成对装备。
        String targetLayer = placement.getWearableLayer();
        if (wearable == null || placement.getMount() != Mount.MOUNT_DEFAULT
                || placement.hasWaistAngleDegrees()
                || wearable.type.slots().stream()
                        .noneMatch(slot -> slot.getLayer().equals(targetLayer))) {
            throw new IllegalArgumentException("这个物品不能装备到该部位");
        }
        wearable.playerSlot = -1;
        wearableRoots.add(wearable);
    }

    public void putPlayer(Entry entry, int slot) {
        // 子树重新成为原版物品后，打包时把最新内容写入同一个槽位候选。
        if (entry.container != null) {
            Node node = entry.container;
            node.parent = null;
            node.playerSlot = slot;
            roots.add(node);
        }
        if (entry.wearable != null) {
            WearableNode node = entry.wearable;
            node.playerSlot = slot;
            wearableRoots.add(node);
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
        validateWearableLayers();
    }

    private void validateWearableLayers() {
        // 一件物品占用的所有层一起校验；同件物品的多骨段不与自身发生跨层冲突。
        Set<String> occupied = new HashSet<>();
        for (WearableNode node : wearableRoots) {
            if (node.playerSlot >= 0) {
                continue;
            }
            List<WearableSlot> slots = node.type.slots();
            for (WearableSlot slot : slots) {
                String layer = slot.getLayer();
                if (occupied.stream().anyMatch(other -> WearableLayers.conflicts(layer, other))) {
                    throw new IllegalArgumentException("穿戴层已占用或互斥：" + layer);
                }
            }
            slots.forEach(slot -> occupied.add(slot.getLayer()));
        }
        validateVanillaArmor(occupied);
    }

    private void validateVanillaArmor(Set<String> occupied) {
        // 候选树同时持有原版槽和所有甲层；正反向转移在提交前执行同一校验。
        for (String layer : occupied) {
            var equipment = ArmorLayers.equipment(layer);
            if (equipment.isEmpty()) {
                continue;
            }
            int index = PlayerSlots.armorSlot(equipment.get());
            if (index < inventory.size() && !inventory.get(index).isEmpty()) {
                throw new IllegalArgumentException("甲层与原版装备占用同一身体部位：" + layer);
            }
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
                                .setStackNbt(StackCodec.encode(pack(entry.container), registries))
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
                                .setStackNbt(StackCodec.encode(stack, registries))
                                .setMount(node.mount)
                                .setWaistAngleDegrees(node.angle)
                                .build());
            }
        }
        return result;
    }

    public List<WornWearable> wornWearables() {
        List<WornWearable> result = new ArrayList<>();
        for (WearableNode node : wearableRoots) {
            if (node.playerSlot >= 0) {
                continue;
            }
            result.add(
                    WornWearable.newBuilder()
                            .setId(node.id)
                            .setStackNbt(StackCodec.encode(node.stack, registries))
                            .addAllSlots(node.type.slots())
                            .setBaseSize(node.size)
                            .build());
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

    public List<Wearable> wearables() {
        // 独立目录只描述装备，不伪造容量或可打开的容器。
        List<Wearable> result = new ArrayList<>();
        for (WearableNode root : wearableRoots) {
            if (root.playerSlot < 0) {
                Wearable worn = Wearable.newBuilder()
                        .setId(root.id)
                        .setTitle(root.type.title())
                        .setModel(root.type.model())
                        .addAllSlots(root.type.slots())
                        .build();
                result.add(worn);
            }
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
            value.setWorn(true /* worn */)
                    .setMount(node.mount)
                    .setWaistAngleDegrees(node.angle);
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
        for (WearableNode root : wearableRoots) {
            if (root.playerSlot < 0) {
                result.add(display(root, Volumes.WORN));
            }
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
                                    .setStackNbt(StackCodec.encode(pack(entry.container), registries))
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
                .setStackNbt(StackCodec.encode(pack(node), registries))
                .build();
    }

    private Item display(WearableNode node, String location) {
        return Item.newBuilder()
                .setId(node.id)
                .setContainer(location)
                .setBaseSize(node.size)
                .setSize(node.size)
                .setStackNbt(StackCodec.encode(node.stack, registries))
                .build();
    }

    private WearableNode wearableNode(String id) {
        WearableNode node = wearableNodes.get(id);
        if (node != null) {
            return node;
        }
        throw new IllegalArgumentException("穿戴物品不存在或已移走");
    }

    public static final class Entry {
        private Item item;
        private final Node container;
        private final WearableNode wearable;

        Entry(Item item, Node container) {
            this(item, container, null);
        }

        Entry(Item item, WearableNode wearable) {
            this(item, null, wearable);
        }

        Entry(Item item, Node container, WearableNode wearable) {
            this.item = item;
            this.container = container;
            this.wearable = wearable;
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

    private static final class WearableNode {
        private final String id;
        private final ItemStack stack;
        private final WearableItems.Definition type;
        private Size size;
        private int playerSlot = -1;

        WearableNode(
                String id,
                ItemStack stack,
                WearableItems.Definition type,
                Size size) {
            this.id = id;
            this.stack = stack;
            this.type = type;
            this.size = size;
        }
    }
}
