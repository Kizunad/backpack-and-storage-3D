package dev.kizuna.backpack3d.service;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.*;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;

/** 先构造完整候选，完成计时后重新对拍并原子提交。 */
public final class TransferService {
    public static final int SAVE_VERSION = 3;
    private static final int PLACEMENT_SAVE_VERSION = 2;
    private static final int LEGACY_SAVE_VERSION = 1;
    private final Function<ItemStack, Size> sizes;

    public TransferService(Function<ItemStack, Size> sizes) {
        this.sizes = sizes;
    }

    public static SavedInventory empty() {
        return SavedInventory.newBuilder().setFormatVersion(SAVE_VERSION).build();
    }

    public static void validate(SavedInventory data) {
        // 先拒绝不支持或过大的存档，再逐类检查身份、装备和物品内容。
        int version = data.getFormatVersion();
        if (version != SAVE_VERSION
                && version != PLACEMENT_SAVE_VERSION
                && version != LEGACY_SAVE_VERSION) {
            throw new IllegalArgumentException("不支持的库存保存版本：" + version);
        }
        if (data.getRevision() < 0) {
            throw new IllegalArgumentException("库存修订号不能为负数");
        }
        if (data.getSerializedSize() > Messages.MAX_SAVE_BYTES) {
            throw new IllegalArgumentException("库存存档超过大小限制");
        }
        validateReceipts(data.getReceiptsList());
        if (version < SAVE_VERSION) {
            validatePlacements(data.getPlacementsList());
            validateItems(data.getItemsList());
            return;
        }
        if (data.getItemsCount() != 0 || data.getPlacementsCount() != 0) {
            throw new IllegalArgumentException("新版存档不能同时包含旧固定库存");
        }
        new ContainerTree(data, new ArrayList<>());
    }

    private static void validatePlacements(List<Placement> placements) {
        // 每个容器只能装备一次，并且只能选择该容器类型允许的挂点。
        Set<String> containers = new HashSet<>();
        for (Placement placement : placements) {
            String container = placement.getContainer();
            if (!containers.add(container)) {
                throw new IllegalArgumentException("容器挂点重复：" + container);
            }
            if (!Volumes.mounts(container).contains(placement.getMount())) {
                throw new IllegalArgumentException("容器不支持该挂点：" + container);
            }
        }
    }

    private static void validateReceipts(List<Receipt> receipts) {
        // 收据必须可唯一标识一个已提交操作，不能靠重复或残缺收据跳过扣物品。
        if (receipts.size() > Messages.RECEIPT_LIMIT) {
            throw new IllegalArgumentException("存档转移收据超过数量限制");
        }
        Set<String> operations = new HashSet<>();
        for (Receipt receipt : receipts) {
            String operation = receipt.getOperation();
            if (!validId(operation) || !operations.add(operation)) {
                throw new IllegalArgumentException("转移收据操作号无效或重复：" + operation);
            }
            if (receipt.getFingerprint().size() != StackCodec.SHA256_BYTES) {
                throw new IllegalArgumentException("转移收据指纹长度无效：" + operation);
            }
        }
    }

    private static void validateItems(List<Item> items) {
        // 先验证实例身份和占格，再解码真实栈，不能把损坏项静默当成空物品。
        Set<String> ids = new HashSet<>();
        for (Item item : items) {
            String id = item.getId();
            if (!validId(id) || !ids.add(id)) {
                throw new IllegalArgumentException("物品实例 ID 无效或重复：" + id);
            }
            if (!Volumes.fits(items, item.getContainer(), item.getCell(), item.getSize(), id)) {
                throw new IllegalArgumentException("物品位置越界或重叠：" + id);
            }
            // 旧存档缺少原始尺寸时，只能解释为未旋转的物品。
            if (!item.hasBaseSize() && item.getOrientation() != 0) {
                throw new IllegalArgumentException("旋转物品缺少原始尺寸：" + id);
            }
            Size expectedSize =
                    OrthogonalRotation.size(Volumes.baseSize(item), item.getOrientation());
            if (!expectedSize.equals(item.getSize())) {
                throw new IllegalArgumentException("物品朝向与占格不一致：" + id);
            }
            StackCodec.decode(item.getStackNbt());
        }
    }

    public static boolean validId(String id) {
        // UUID 的规范格式能限制标识长度，禁止任意字符串进入幂等收据。
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static com.google.protobuf.ByteString fingerprint(MoveRequest request) {
        return StackCodec.fingerprint(request.toBuilder().clearSession().build().toByteString());
    }

    public boolean committed(Account account, MoveRequest request) {
        // 同一个操作号只能对应相同意图，重发成功事务不会再次扣物品。
        for (var receipt : account.data().getReceiptsList()) {
            if (receipt.getOperation().equals(request.getOperation())) {
                if (!receipt.getFingerprint().equals(fingerprint(request))) {
                    throw new IllegalArgumentException("操作号已被另一项转移使用");
                }
                return true;
            }
        }
        return false;
    }

    public Candidate prepare(Account account, MoveRequest request) {
        // 先验证操作身份与版本，再构造完整物品树副本，计时期间不触碰真实库存。
        OrthogonalRotation.require(request.getOrientation());
        SavedInventory previous = account.data();
        if (!validId(request.getOperation())) {
            throw new IllegalArgumentException("转移操作号不是有效 UUID");
        }
        if (request.getRevision() != previous.getRevision()) {
            throw new IllegalArgumentException("库存版本已变化，请重新选择物品");
        }
        if (!request.getGroundRevisionsMap().equals(account.groundRevisions())) {
            throw new IllegalArgumentException("地面背包已变化或访问已结束，请重新打开");
        }
        if (request.hasTargetPlayerSlot() && !request.getTarget().equals(Volumes.PLAYER)) {
            throw new IllegalArgumentException("只有原版槽位目标才能指定 player_slot");
        }
        if (request.hasWornTarget() && !request.getTarget().equals(Volumes.WORN)) {
            throw new IllegalArgumentException("只有装备容器时才能指定身体挂点");
        }
        List<ItemStack> vanilla = new ArrayList<>();
        for (ItemStack stack : account.inventory()) {
            vanilla.add(stack.copy());
        }
        Map<String, ItemStack> ground = new LinkedHashMap<>();
        account.ground().forEach((id, stack) -> ground.put(id, stack.copy()));
        ContainerTree tree = new ContainerTree(previous, vanilla, ground);
        ContainerTree.Entry source;
        if (request.getItem().isEmpty()) {
            int slot = request.getPlayerSlot();
            if (request.getTarget().equals(Volumes.PLAYER)) {
                requireInteractiveSlot(account, slot);
                if (!request.hasTargetPlayerSlot() || slot == request.getTargetPlayerSlot()) {
                    throw new IllegalArgumentException("请指定不同的目标部位");
                }
            }
            ItemStack stack = readVanillaStack(account, request, vanilla);
            source = tree.takePlayer(slot, stack, request.getOperation(), sizeOf(stack));
        } else {
            source = tree.take(request.getItem());
        }
        ItemStack moved = tree.stack(source);
        String sourceContainer = source.item().getContainer();
        switch (request.getTarget()) {
            case Volumes.PLAYER -> {
                int slot;
                if (request.hasTargetPlayerSlot()) {
                    slot = request.getTargetPlayerSlot();
                    requireInteractiveSlot(account, slot);
                    insertInPlayerSlot(vanilla, slot, moved);
                } else {
                    // 自动取回仅搜索主背包，副手和盔甲必须显式指定。
                    slot = insert(vanilla.subList(0, PlayerInventory.MAIN_SIZE), moved.copy());
                }
                tree.putPlayer(source, slot);
            }
            case Volumes.WORN -> {
                if (!request.hasWornTarget()) {
                    throw new IllegalArgumentException("请选择容器装备部位");
                }
                tree.wear(source, request.getWornTarget());
            }
            default -> tree.place(source, request);
        }
        SavedInventory next = withReceipt(previous, tree.worn(), request);
        // 重新解析候选同时验证嵌套深度、唯一身份和原版槽位里的整个子树。
        validate(next);
        new ContainerTree(next, vanilla, ground);
        return new Candidate(next, vanilla, moved, sourceContainer, ground);
    }

    private Size sizeOf(ItemStack stack) {
        var type = ContainerItems.definition(stack);
        return type == null ? sizes.apply(stack) : type.outside();
    }

    private static void requireInteractiveSlot(Account account, int slot) {
        // 开始与提交都按当前主手槽检查，计时期间切换快捷栏不能操作旧手持槽。
        if (!PlayerSlots.isInteractive(slot, account.selectedSlot())) {
            throw new IllegalArgumentException("目标必须是当前双手或盔甲部位");
        }
    }

    private static ItemStack readVanillaStack(
            Account account, MoveRequest request, List<ItemStack> vanilla) {
        // 先验证来源和卸装限制，再对拍指纹；客户端只引用服务器已有的真实栈。
        int slot = request.getPlayerSlot();
        if (slot < 0 || slot > PlayerInventory.OFF_HAND_SLOT || slot >= vanilla.size()) {
            throw new IllegalArgumentException("原版槽位无效：" + slot);
        }
        ItemStack stack = vanilla.get(slot);
        if (PlayerSlots.bindingLocked(slot, stack, account.creative())) {
            throw new IllegalArgumentException("绑定诅咒装备无法取下");
        }
        var fingerprint = StackCodec.fingerprint(StackCodec.encode(stack));
        if (!fingerprint.equals(request.getStackFingerprint())) {
            throw new IllegalArgumentException("来源物品已变化，转移已取消");
        }
        return stack;
    }

    private static SavedInventory withReceipt(
            SavedInventory previous, List<WornContainer> worn, MoveRequest request) {
        // 库存和幂等收据写入同一个候选；提交失败时两者都不会生效。
        var next =
                previous.toBuilder()
                        .setFormatVersion(SAVE_VERSION)
                        .setRevision(Math.addExact(previous.getRevision(), 1))
                        .clearItems()
                        .clearPlacements()
                        .clearWorn()
                        .addAllWorn(worn);
        if (next.getReceiptsCount() == Messages.RECEIPT_LIMIT) {
            next.removeReceipts(0);
        }
        Receipt receipt =
                Receipt.newBuilder()
                        .setOperation(request.getOperation())
                        .setFingerprint(fingerprint(request))
                        .build();
        return next.addReceipts(receipt).build();
    }

    public void equip(Account account, EquipRequest request) throws IOException {
        // 程序接入的绝对挂点调整也使用物品树校验，不允许修改已被收纳的容器。
        List<ItemStack> vanilla = new ArrayList<>(account.inventory());
        ContainerTree tree = new ContainerTree(account.data(), vanilla);
        var placement = request.getPlacement();
        for (Container current : tree.containers()) {
            if (current.getWorn()
                    && current.getId().equals(placement.getContainer())
                    && current.getMount() == placement.getMount()
                    && (!placement.hasWaistAngleDegrees()
                            || current.getWaistAngleDegrees()
                                    == placement.getWaistAngleDegrees())) {
                return;
            }
        }
        if (request.getRevision() != account.data().getRevision()) {
            throw new IllegalArgumentException("库存版本已变化，请重试");
        }
        tree.reposition(request.getPlacement());
        SavedInventory next =
                account.data().toBuilder()
                        .clearWorn()
                        .addAllWorn(tree.worn())
                        .setRevision(Math.addExact(account.data().getRevision(), 1))
                        .build();
        validate(next);
        account.commit(next, vanilla);
    }

    public static SavedInventory upgrade(SavedInventory previous) {
        // 旧固定容器各转换为一个真实物品，原有内容、朝向和挂点随物品保存。
        validate(previous);
        if (previous.getFormatVersion() == SAVE_VERSION) {
            return previous;
        }
        var next =
                previous.toBuilder().setFormatVersion(SAVE_VERSION).clearItems().clearPlacements();
        for (Container legacy : Volumes.CONTAINERS) {
            ItemStack stack =
                    ContainerItems.create(
                            legacy.getId().equals(Volumes.POUCH)
                                    ? ContainerItems.POUCH
                                    : ContainerItems.BASKET);
            String id = ContainerItems.identity(stack);
            List<Item> contents =
                    previous.getItemsList().stream()
                            .filter(item -> item.getContainer().equals(legacy.getId()))
                            .map(
                                    item ->
                                            item.toBuilder()
                                                    .setContainer(id)
                                                    .setBaseSize(Volumes.baseSize(item))
                                                    .build())
                            .toList();
            ContainerItems.setContents(stack, contents);
            Mount mount = Volumes.mount(previous, legacy.getId());
            next.addWorn(
                    WornContainer.newBuilder()
                            .setStackNbt(StackCodec.encode(stack))
                            .setMount(mount)
                            .setWaistAngleDegrees(ContainerTree.defaultAngle(mount)));
        }
        SavedInventory upgraded = next.build();
        validate(upgraded);
        return upgraded;
    }

    public void commit(Account account, MoveRequest request) throws IOException {
        // 计时结束时再次取当前原版库存，避免覆盖期间来自其他系统的无关物品变化。
        if (!committed(account, request)) {
            var candidate = prepare(account, request);
            account.commit(candidate.data(), candidate.vanilla(), candidate.ground());
        }
    }

    private static void insertInHand(List<ItemStack> inventory, int slot, ItemStack source) {
        // 精确落到用户指向的手：只接受空手或能完整合并的同种栈，不交换、不溢出到背包。
        ItemStack held = inventory.get(slot);
        if (held.isEmpty()) {
            inventory.set(slot, source.copy());
            return;
        }
        if (!ItemStack.canCombine(held, source)) {
            throw new IllegalArgumentException("这只手已有其他物品，请先存入或换另一只手");
        }
        if (held.getCount() + source.getCount() > held.getMaxCount()) {
            throw new IllegalArgumentException("这只手放不下整组物品");
        }
        held.increment(source.getCount());
    }

    private static void insertInPlayerSlot(List<ItemStack> inventory, int slot, ItemStack source) {
        // 盔甲按原版认可的部位接收一件物品，包含鞘翅、雕刻南瓜与头颅。
        if (!PlayerSlots.isArmor(slot)) {
            insertInHand(inventory, slot, source);
            return;
        }
        if (LivingEntity.getPreferredEquipmentSlot(source) != PlayerSlots.armorType(slot)) {
            throw new IllegalArgumentException("物品不适合这个装备部位");
        }
        if (source.getCount() != 1) {
            throw new IllegalArgumentException("装备时只能放入一件物品");
        }
        if (!inventory.get(slot).isEmpty()) {
            throw new IllegalArgumentException("这个部位已有装备，请先取下");
        }
        inventory.set(slot, source.copy());
    }

    private static int insert(List<ItemStack> inventory, ItemStack remaining) {
        // 先合并同 NBT 栈，再找空槽；全部容纳不下就丢弃候选，不进行部分转移。
        for (var stack : inventory) {
            if (!stack.isEmpty() && ItemStack.canCombine(stack, remaining)) {
                int count = Math.min(stack.getMaxCount() - stack.getCount(), remaining.getCount());
                stack.increment(count);
                remaining.decrement(count);
                if (remaining.isEmpty()) {
                    return inventory.indexOf(stack);
                }
            }
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.get(slot).isEmpty()) {
                inventory.set(slot, remaining.copy());
                return slot;
            }
        }
        throw new IllegalArgumentException("原版背包已满");
    }

    /** data/vanilla 是待提交副本；moved 用于材质规则，source 是计时展示的来源 ID。 */
    public record Candidate(
            // 业务库存与收据的完整候选。
            SavedInventory data,
            // 原版完整槽位副本，主背包、盔甲和副手与 data 一起提交。
            List<ItemStack> vanilla,
            // 被移动的真实栈，用来选择声音和耗时。
            ItemStack moved,
            // 来源容器 ID，原版库存使用 player。
            String source,
            // 已访问地面根容器的候选内容，与玩家候选共同提交。
            Map<String, ItemStack> ground) {}
}
