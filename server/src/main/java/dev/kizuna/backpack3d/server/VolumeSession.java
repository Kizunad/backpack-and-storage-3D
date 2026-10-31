package dev.kizuna.backpack3d.server;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.Messages;
import dev.kizuna.backpack3d.protocol.PlayerSlots;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.CancelRequest;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.CloseGroundRequest;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.EquipRequest;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.GroundOpened;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.MoveRequest;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.OpenRequest;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Phase;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.PlayerSlotStack;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.State;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Transfer;
import dev.kizuna.backpack3d.service.ContainerTree;
import dev.kizuna.backpack3d.service.TransferService;
import dev.kizuna.inventoryui.server.ServerConnection;
import dev.kizuna.inventoryui.server.ServerModuleSession;
import dev.kizuna.inventoryui.server.ServerRequest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 每次请求在同一服务器调用内校验并提交，快照只公布终态。 */
public final class VolumeSession implements ServerModuleSession<ServerPlayer> {
    private static final int MAX_OPEN_GROUND_BAGS = 8;
    private final MinecraftServer server;
    private final ServerConnection<ServerPlayer> connection;
    private final TransferService service;
    private final HandlingRules rules;
    private final WornEquipmentSync appearances;
    private final Set<String> ground = new LinkedHashSet<>();
    private UUID groundOwner;
    private boolean opened;
    private Transfer transfer = Transfer.getDefaultInstance();
    private int lastSelectedSlot = -1;
    private List<ItemStack> lastInventory = List.of();
    private boolean lastCreative;
    private long lastRevision = -1;

    public VolumeSession(
            MinecraftServer server,
            ServerConnection<ServerPlayer> connection,
            TransferService service,
            HandlingRules rules) {
        this.server = server;
        this.connection = connection;
        this.service = service;
        this.rules = rules;
        appearances = new WornEquipmentSync(connection);
    }

    public MinecraftServer server() {
        return server;
    }

    public boolean belongsTo(ServerPlayer player) {
        return connection.supports(Messages.GROUND_OPENED) && connection.context() == player;
    }

    public void openGround(String id) {
        // 地面访问先取得独占租约，再验证整树并公布快照。
        var player = connection.context();
        if (!available(player)
                || (ground.size() >= MAX_OPEN_GROUND_BAGS && !ground.contains(id))) {
            throw new IllegalArgumentException("请结束当前操作或关闭多余的地面背包窗口");
        }
        // 先拿到独占访问权，再把地面树加入本人快照；失败时不会向客户端发出打开事件。
        GroundInventory.get(server).acquire(player, id);
        groundOwner = player.getUUID();
        boolean added = ground.add(id);
        try {
            var candidate = account();
            // 构造整树会校验嵌套和唯一身份，损坏时抛出 IllegalArgumentException。
            new ContainerTree(candidate.data(), candidate.inventory(), candidate.ground());
        } catch (IllegalArgumentException invalid) {
            if (added) {
                releaseGround(List.of(id));
            }
            throw invalid;
        }
        opened = true;
        send();
        connection.send(Messages.GROUND_OPENED, GroundOpened.newBuilder().setContainer(id).build());
    }

    private GroundAccount account() {
        return new GroundAccount(connection.context(), ground);
    }

    void openNearbyGround() {
        // 附属页面与独立场景共用授权入口，随后向客户端公布完整状态。
        acquireNearbyGround();
        opened = true;
        send();
    }

    private void acquireNearbyGround() {
        // 页面打开才取得地面访问权，登录时的空 OPEN 不占用附近容器。
        var player = connection.context();
        if (!actorAvailable(player)) {
            throw new IllegalArgumentException("角色当前状态不允许转移");
        }
        pruneGround();
        groundOwner = player.getUUID();
        ground.addAll(GroundInventory.get(server).acquireNearby(player, ground, MAX_OPEN_GROUND_BAGS));
    }

    Map<String, ItemStack> accessibleGround() {
        // 按读取时的玩家、维度和距离重新验证；失效访问不提供给附属。
        var result = new LinkedHashMap<String, ItemStack>();
        var storage = GroundInventory.get(server);
        for (String id : ground) {
            try {
                result.put(id, storage.leased(connection.context(), id).stack().copy());
            } catch (IllegalArgumentException unavailable) {
                // 原会话下一 tick 会清理访问权，这里只跳过已失效的容器。
            }
        }
        return result;
    }

    private void releaseGround(List<String> ids) {
        // 关闭 UI 或失去距离后立即释放，合盖动画不能延长物品锁。
        var release = ids.isEmpty() ? List.copyOf(ground) : ids;
        for (String id : release) {
            if (ground.remove(id)) {
                GroundInventory.get(server).release(groundOwner, id);
            }
        }
    }

    private boolean pruneGround() {
        // 失去角色状态、距离或遮挡条件时释放旧访问权。
        boolean changed = false;
        var player = connection.context();
        for (String id : List.copyOf(ground)) {
            try {
                if (!actorAvailable(player)) {
                    throw new IllegalArgumentException("角色状态已变化");
                }
                GroundInventory.get(server).leased(player, id);
            } catch (IllegalArgumentException invalid) {
                releaseGround(List.of(id));
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public boolean handle(ServerRequest<ServerPlayer> request) {
        // 身份由 Framework 认证连接提供，任何请求都不能选择别人的容器。
        var player = connection.context();
        try {
            applyRequest(player, request);
            send();
            return true;
        } catch (IllegalArgumentException | IOException invalid) {
            // 回执携带原操作号，客户端据此恢复来源占位和输入。
            String operation = "";
            if (request.message().equals(Messages.MOVE)) {
                operation = request.payload(MoveRequest.getDefaultInstance()).getOperation();
            }
            transfer = Transfer.newBuilder()
                    .setOperation(operation)
                    .setPhase(Phase.REJECTED)
                    .setDetail(invalid.getMessage())
                    .build();
            send();
            return false;
        }
    }

    private void applyRequest(ServerPlayer player, ServerRequest<ServerPlayer> request)
            throws IOException {
        // OPEN 建立业务会话，后续请求才允许携带会话 ID 修改状态。
        if (request.message().equals(Messages.OPEN)) {
            var open = request.payload(OpenRequest.getDefaultInstance());
            if (open.getIncludeNearbyGround()) {
                acquireNearbyGround();
            }
            opened = true;
            return;
        }
        if (!opened) {
            throw new IllegalArgumentException("请先同步库存");
        }

        switch (request.message()) {
            case Messages.CLOSE_GROUND -> {
                var close = request.payload(CloseGroundRequest.getDefaultInstance());
                requireSession(close.getSession());
                releaseGround(close.getContainersList());
            }
            case Messages.CANCEL -> {
                CancelRequest cancelRequest = request.payload(CancelRequest.getDefaultInstance());
                requireSession(cancelRequest.getSession());
                // 即时事务没有可取消的阶段，关闭页面不会撤销已提交操作。
            }
            case Messages.EQUIP -> {
                EquipRequest equipRequest = request.payload(EquipRequest.getDefaultInstance());
                requireSession(equipRequest.getSession());
                if (!available(player)) {
                    throw new IllegalArgumentException("当前不能切换装备位置");
                }
                service.equip(new PlayerAccount(player), equipRequest);
                transfer = Transfer.getDefaultInstance();
            }
            case Messages.MOVE -> {
                MoveRequest moveRequest = request.payload(MoveRequest.getDefaultInstance());
                requireSession(moveRequest.getSession());
                start(player, moveRequest);
            }
            default -> throw new IllegalArgumentException("未知业务消息");
        }
    }

    private void requireSession(String session) {
        if (!session.equals(connection.id())) {
            throw new IllegalArgumentException("连接会话已失效");
        }
    }

    private void start(ServerPlayer player, MoveRequest move) throws IOException {
        // 幂等重试只回执；新请求在同一调用内准备和提交，期间不会推进服务器 tick。
        if (!available(player)) {
            throw new IllegalArgumentException("角色当前状态不允许转移");
        }
        transfer = new ImmediateTransfer(service, rules).execute(account(), move);
    }

    public void tick() {
        // 世界外观与原版槽位变化独立同步，不保留跨 tick 的转移事务。
        appearances.tick();
        boolean groundChanged = pruneGround();
        var player = connection.context();
        long revision = ((PlayerStorage) player).bs3d$getInventory().getRevision();
        if (opened && (groundChanged
                || lastSelectedSlot != player.getInventory().selected
                || inventoryChanged(player)
                || lastCreative != player.isCreative()
                || lastRevision != revision)) {
            send();
        }
    }

    private static boolean available(ServerPlayer player) {
        // 连接和角色状态允许操作之后，还要确认原版没有正在处理的容器或鼠标栈。
        if (!actorAvailable(player)) {
            return false;
        }
        if (player.containerMenu != player.inventoryMenu) {
            return false;
        }
        return player.inventoryMenu.getCarried().isEmpty();
    }

    private static boolean actorAvailable(ServerPlayer player) {
        // 菜单与光标由各自事务验证，租约只受角色生存状态、距离和遮挡约束。
        return player.connection.isAcceptingMessages() && player.isAlive()
                && !player.isSpectator() && !player.isPassenger();
    }

    private boolean inventoryChanged(ServerPlayer player) {
        // 手持、穿戴和便携容器内容均由真实原版栈决定，其他模组修改后也重新同步。
        var inventory = player.getInventory();
        if (lastInventory.size() != inventory.getContainerSize()) {
            return true;
        }
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            if (!ItemStack.matches(lastInventory.get(index), inventory.getItem(index))) {
                return true;
            }
        }
        return false;
    }

    private void send() {
        // 回执与库存来自同一时刻的服务器状态，客户端不需要猜测转移后的库存。
        if (!opened) {
            return;
        }
        var storage = (PlayerStorage) connection.context();
        SavedInventory saved = storage.bs3d$getInventory();
        lastRevision = saved.getRevision();
        var player = connection.context();
        // 新获得的容器在第一次快照前生成真实身份，后续同步不重复分配。
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ContainerItems.initializeStack(player.getInventory().getItem(slot));
        }
        lastInventory = new PlayerAccount(player).inventory();
        var state =
                State.newBuilder()
                        .setSession(connection.id())
                        .setRevision(saved.getRevision())
                        .setWaistLimit(ContainerTree.waistLimit(saved))
                        .setTransfer(transfer);
        try {
            var account = account();
            var tree = new ContainerTree(saved, new ArrayList<>(lastInventory), account.ground());
            state.putAllGroundRevisions(account.groundRevisions());
            state.addAllItems(tree.items());
            state.addAllContainers(tree.containers());
        } catch (IllegalArgumentException invalid) {
            // 外部获得的损坏或重复身份物品只禁用本次业务视图，不让同步异常终止服务器 tick。
            transfer =
                    Transfer.newBuilder()
                            .setPhase(Phase.REJECTED)
                            .setDetail(invalid.getMessage())
                            .build();
            state.setTransfer(transfer);
        }
        addPlayerSlots(state, player);
        connection.send(Messages.STATE, state.build());
    }

    private void addPlayerSlots(State.Builder state, ServerPlayer player) {
        // 双手与盔甲即使为空也提供可拖入的身体落点。
        lastSelectedSlot = player.getInventory().selected;
        lastCreative = player.isCreative();
        state.addPlayerSlots(playerSlot(lastSelectedSlot, player.getMainHandItem()));
        state.addPlayerSlots(playerSlot(Inventory.SLOT_OFFHAND, player.getOffhandItem()));
        for (var equipment : PlayerSlots.ARMOR) {
            state.addPlayerSlots(
                    playerSlot(
                            PlayerSlots.armorSlot(equipment), player.getItemBySlot(equipment)));
        }
    }

    private PlayerSlotStack playerSlot(int slot, ItemStack stack) {
        // 空槽也公布落点；绑定装备保留展示栈并说明限制，非法 NBT 则不发送给客户端。
        var snapshot = PlayerSlotStack.newBuilder().setPlayerSlot(slot);
        if (!stack.isEmpty()) {
            try {
                snapshot.setStackNbt(StackCodec.encode(stack));
                var type = ContainerItems.definition(stack);
                if (type != null) {
                    // 损坏容器可以留在原版槽位供处理，但不能继续作为可打开、可拖动的预览栈。
                    ContainerItems.contents(stack);
                }
                snapshot.setBaseSize(type == null ? rules.material(stack).size() : type.outside());
                if (PlayerSlots.bindingLocked(slot, stack, lastCreative)) {
                    snapshot.setUnavailableReason("绑定诅咒装备无法取下");
                }
            } catch (IllegalArgumentException invalid) {
                snapshot.clearStackNbt().setUnavailableReason(invalid.getMessage());
            }
        }
        return snapshot.build();
    }

    @Override
    public void close() {
        // 断线回调已由 Framework 调度到服务器主线程，此时不能继续发包。
        releaseGround(List.of());
        opened = false;
        VolumeServer.forget(this);
    }
}
