package dev.kizuna.backpack3d.service;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.Messages;
import dev.kizuna.backpack3d.protocol.OrthogonalRotation;
import dev.kizuna.backpack3d.protocol.PlayerSlots;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Container;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.EquipRequest;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.MoveRequest;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Receipt;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Size;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornContainer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

/** 先构造完整候选，再在同一调用内核对来源并原子提交。 */
public final class TransferService {
    public static final int SAVE_VERSION = 3;
    private final Function<ItemStack, Size> sizes;

    public TransferService(Function<ItemStack, Size> sizes) {
        this.sizes = sizes;
    }

    public static SavedInventory empty() {
        return SavedInventory.newBuilder().setFormatVersion(SAVE_VERSION).build();
    }

    public static void validate(SavedInventory data, HolderLookup.Provider registries) {
        // 先拒绝不支持或过大的存档，再逐类检查身份、装备和物品内容。
        // 第 1、2 版固定容器存档没有任何已提交版本写出过，升级路径已随旧容器物品删除。
        int version = data.getFormatVersion();
        if (version != SAVE_VERSION) {
            throw new IllegalArgumentException("不支持的库存保存版本：" + version);
        }
        if (data.getRevision() < 0) {
            throw new IllegalArgumentException("库存修订号不能为负数");
        }
        if (data.getSerializedSize() > Messages.MAX_SAVE_BYTES) {
            throw new IllegalArgumentException("库存存档超过大小限制");
        }
        validateReceipts(data.getReceiptsList());
        if (data.getItemsCount() != 0 || data.getPlacementsCount() != 0) {
            throw new IllegalArgumentException("存档不能包含已废弃的旧固定库存字段");
        }
        new ContainerTree(registries, data, new ArrayList<>());
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
        // 先验证操作身份与版本，再构造完整物品树副本，提交前不触碰真实库存。
        OrthogonalRotation.require(request.getOrientation());
        SavedInventory previous = account.data();
        validateMoveIdentity(account, request, previous);
        List<ItemStack> vanilla = new ArrayList<>();
        for (ItemStack stack : account.inventory()) {
            vanilla.add(stack.copy());
        }
        Map<String, ItemStack> ground = new LinkedHashMap<>();
        account.ground().forEach((id, stack) -> ground.put(id, stack.copy()));
        ContainerTree tree = new ContainerTree(account.registries(), previous, vanilla, ground);
        ContainerTree.Entry source = takeSource(account, request, tree, vanilla);
        ItemStack moved = tree.stack(source);
        String sourceContainer = source.item().getContainer();
        placeTarget(account, request, tree, new PlayerDestination(vanilla, moved, source));
        SavedInventory next = withReceipt(previous, tree.worn(), request);
        // 重新解析候选同时验证嵌套深度、唯一身份和原版槽位里的整个子树。
        validate(next, account.registries());
        new ContainerTree(account.registries(), next, vanilla, ground);
        return new Candidate(next, vanilla, moved, sourceContainer, ground);
    }

    private static void validateMoveIdentity(Account account, MoveRequest request, SavedInventory previous) {
        // 操作身份、修订号与目标类型必须在读取来源前一致。
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
    }

    private ContainerTree.Entry takeSource(Account account, MoveRequest request,
                                            ContainerTree tree, List<ItemStack> vanilla) {
        // 身体来源先验证当前槽位，内景来源由容器树按实例身份取出。
        if (request.getItem().isEmpty()) {
            int slot = request.getPlayerSlot();
            if (request.getTarget().equals(Volumes.PLAYER)) {
                requireInteractiveSlot(account, slot);
                if (!request.hasTargetPlayerSlot() || slot == request.getTargetPlayerSlot()) {
                    throw new IllegalArgumentException("请指定不同的目标部位");
                }
            }
            ItemStack stack = readVanillaStack(account, request, vanilla);
            return tree.takePlayer(slot, stack, request.getOperation(), sizeOf(stack));
        }
        return tree.take(request.getItem());
    }

    private static void placeTarget(Account account, MoveRequest request,
                                     ContainerTree tree, PlayerDestination destination) {
        // 目标规则统一作用于候选树，原版快捷取回交给宿主 insertItem。
        var source = destination.source();
        var vanilla = destination.inventory();
        var moved = destination.moved();
        switch (request.getTarget()) {
            case Volumes.PLAYER -> {
                int slot;
                if (request.hasTargetPlayerSlot()) {
                    slot = request.getTargetPlayerSlot();
                    requireInteractiveSlot(account, slot);
                    insertInPlayerSlot(vanilla, slot, moved);
                } else {
                    // 自动取回仅搜索主背包，副手和盔甲必须显式指定。
                    slot = account.insertPlayer(vanilla, moved.copy());
                }
                tree.putPlayer(source, slot);
            }
            case Volumes.WORN -> {
                if (!request.hasWornTarget()) {
                    throw new IllegalArgumentException("请选择容器装备部位");
                }
                tree.wear(source, request.getWornTarget());
            }
            case Volumes.CARRIED -> tree.placeCarried(source, request.getOrientation());
            default -> tree.place(source, request);
        }
    }

    private record PlayerDestination(List<ItemStack> inventory, ItemStack moved, ContainerTree.Entry source) {}

    private Size sizeOf(ItemStack stack) {
        var type = ContainerItems.definition(stack);
        return type == null ? sizes.apply(stack) : type.outside();
    }

    private static void requireInteractiveSlot(Account account, int slot) {
        // 按当前主手槽检查，快捷栏切换后不能操作旧手持槽。
        if (!PlayerSlots.isInteractive(slot, account.selectedSlot())) {
            throw new IllegalArgumentException("目标必须是当前双手或盔甲部位");
        }
    }

    private static ItemStack readVanillaStack(
            Account account, MoveRequest request, List<ItemStack> vanilla) {
        // 先验证来源和卸装限制，再对拍指纹；客户端只引用服务器已有的真实栈。
        int slot = request.getPlayerSlot();
        if (slot < 0 || slot > Inventory.SLOT_OFFHAND || slot >= vanilla.size()) {
            throw new IllegalArgumentException("原版槽位无效：" + slot);
        }
        ItemStack stack = vanilla.get(slot);
        if (PlayerSlots.bindingLocked(slot, stack, account.creative())) {
            throw new IllegalArgumentException("绑定诅咒装备无法取下");
        }
        var fingerprint = StackCodec.fingerprint(StackCodec.encode(stack, account.registries()));
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
        ContainerTree tree = new ContainerTree(account.registries(), account.data(), vanilla);
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
        validate(next, account.registries());
        account.commit(next, vanilla);
    }

    public void commit(Account account, MoveRequest request) throws IOException {
        // 提交前重新读取真实库存，来源指纹、修订号和地面租约仍需全部匹配。
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
        if (!ItemStack.isSameItemSameComponents(held, source)) {
            throw new IllegalArgumentException("这只手已有其他物品，请先存入或换另一只手");
        }
        if (held.getCount() + source.getCount() > held.getMaxStackSize()) {
            throw new IllegalArgumentException("这只手放不下整组物品");
        }
        held.grow(source.getCount());
    }

    private static void insertInPlayerSlot(List<ItemStack> inventory, int slot, ItemStack source) {
        // 盔甲按原版认可的部位接收一件物品，包含鞘翅、雕刻南瓜与头颅。
        if (!PlayerSlots.isArmor(slot)) {
            insertInHand(inventory, slot, source);
            return;
        }
        // 26.2 的可穿戴部位来自 equippable 组件，与原版盔甲槽一样同时检查部位和允许穿戴的实体类型。
        Equippable equippable = source.get(DataComponents.EQUIPPABLE);
        if (equippable == null
                || equippable.slot() != PlayerSlots.armorType(slot)
                || !equippable.canBeEquippedBy(BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(EntityTypes.PLAYER))) {
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

    static int insert(List<ItemStack> vanilla, ItemStack remaining) {
        // 先合并同组件栈，再找空槽；全部容纳不下就丢弃候选，不进行部分转移。
        List<ItemStack> inventory = vanilla.subList(0, Inventory.INVENTORY_SIZE);
        for (var stack : inventory) {
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, remaining)) {
                int count = Math.min(stack.getMaxStackSize() - stack.getCount(), remaining.getCount());
                stack.grow(count);
                remaining.shrink(count);
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

    /** data/vanilla 是待提交副本；moved 用于材质规则，source 是回执中的来源 ID。 */
    public record Candidate(
            // 业务库存与收据的完整候选。
            SavedInventory data,
            // 原版完整槽位副本，主背包、盔甲和副手与 data 一起提交。
            List<ItemStack> vanilla,
            // 被移动的真实栈，用来选择完成声音。
            ItemStack moved,
            // 来源容器 ID，原版库存使用 player。
            String source,
            // 已访问地面根容器的候选内容，与玩家候选共同提交。
            Map<String, ItemStack> ground) {}
}
