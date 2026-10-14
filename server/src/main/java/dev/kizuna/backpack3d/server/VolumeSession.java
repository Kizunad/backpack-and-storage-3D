package dev.kizuna.backpack3d.server;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.*;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;
import dev.kizuna.backpack3d.service.ContainerTree;
import dev.kizuna.backpack3d.service.TransferService;
import dev.kizuna.inventoryui.server.*;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 连接内只允许一个计时转移；完成前库存完全不变。 */
public final class VolumeSession implements ServerModuleSession<ServerPlayerEntity> {
    private static final int PROGRESS_INTERVAL_TICKS = 2;
    private static final int MAX_OPEN_GROUND_BAGS = 8;
    private static final double MAX_POSITION_DRIFT_SQUARED = 0.0025;
    // 地面支撑探测距离，单位为方块；锁定期间 onGround 瞬态标志不作为唯一依据。
    private static final double GROUND_PROBE_BLOCKS = 0.05;
    private final MinecraftServer server;
    private final ServerConnection<ServerPlayerEntity> connection;
    private final TransferService service;
    private final HandlingRules rules;
    private final WornEquipmentSync appearances;
    private final Set<String> cancelled = new HashSet<>();
    private final Set<String> ground = new java.util.LinkedHashSet<>();
    private UUID groundOwner;
    private boolean opened;
    private MoveRequest pending;
    private ServerPlayerEntity actor;
    private Vec3d anchor;
    private net.minecraft.registry.RegistryKey<net.minecraft.world.World> dimension;
    private float startingHealth;
    private int elapsed;
    private Transfer transfer = Transfer.getDefaultInstance();
    private int lastSelectedSlot = -1;
    private List<ItemStack> lastInventory = List.of();
    private boolean lastCreative;
    private long lastRevision = -1;

    public VolumeSession(
            MinecraftServer server,
            ServerConnection<ServerPlayerEntity> connection,
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

    public boolean locks(ServerPlayerEntity player) {
        return pending != null && actor == player;
    }

    public boolean belongsTo(ServerPlayerEntity player) {
        return connection.supports(Messages.GROUND_OPENED) && connection.context() == player;
    }

    public void openGround(String id) {
        var player = connection.context();
        if (pending != null
                || !available(player)
                || (ground.size() >= MAX_OPEN_GROUND_BAGS && !ground.contains(id))) {
            throw new IllegalArgumentException("请结束当前操作或关闭多余的地面背包窗口");
        }
        // 先拿到独占访问权，再把地面树加入本人快照；失败时不会向客户端发出打开事件。
        GroundInventory.get(server).acquire(player, id);
        groundOwner = player.getUuid();
        boolean added = ground.add(id);
        try {
            var candidate = account();
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

    java.util.Map<String, net.minecraft.item.ItemStack> accessibleGround() {
        // 按读取时的玩家、维度和距离重新验证；失效访问不提供给附属。
        var result = new java.util.LinkedHashMap<String, net.minecraft.item.ItemStack>();
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
        boolean changed = false;
        var player = connection.context();
        for (String id : List.copyOf(ground)) {
            try {
                if (!available(player)) {
                    throw new IllegalArgumentException("角色状态已变化");
                }
                GroundInventory.get(server).leased(player, id);
            } catch (IllegalArgumentException invalid) {
                cancel("地面背包已不可访问，转移已取消");
                releaseGround(List.of(id));
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public boolean handle(ServerRequest<ServerPlayerEntity> request) {
        // 身份由 Framework 认证连接提供，任何请求都不能选择别人的容器。
        var player = connection.context();
        try {
            applyRequest(player, request);
            send();
            return true;
        } catch (IllegalArgumentException | IOException invalid) {
            // 拒绝第二次操作不能清掉正在运行的第一个事务或解除它的位置锁。
            if (pending == null) {
                transfer =
                        Transfer.newBuilder()
                                .setPhase(Phase.REJECTED)
                                .setDetail(invalid.getMessage())
                                .build();
            }
            send();
            return false;
        }
    }

    private void applyRequest(ServerPlayerEntity player, ServerRequest<ServerPlayerEntity> request)
            throws IOException {
        // OPEN 建立业务会话，后续请求才允许携带会话 ID 修改状态。
        if (request.message().equals(Messages.OPEN)) {
            request.payload(OpenRequest.getDefaultInstance());
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
                cancel("地面背包窗口已关闭");
                releaseGround(close.getContainersList());
            }
            case Messages.CANCEL -> {
                CancelRequest cancelRequest = request.payload(CancelRequest.getDefaultInstance());
                requireSession(cancelRequest.getSession());
                if (pending != null
                        && pending.getOperation().equals(cancelRequest.getOperation())) {
                    cancel("操作已取消，物品保留原位");
                }
            }
            case Messages.EQUIP -> {
                EquipRequest equipRequest = request.payload(EquipRequest.getDefaultInstance());
                requireSession(equipRequest.getSession());
                if (pending != null || !available(player)) {
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

    private void start(ServerPlayerEntity player, MoveRequest move) {
        // 相同运行中请求只回当前进度；不同请求不会抢占锁定物品。
        if (pending != null) {
            if (pending.equals(move)) {
                return;
            }
            throw new IllegalArgumentException("请等待当前转移完成");
        }
        if (!available(player)) {
            throw new IllegalArgumentException("角色当前状态不允许转移");
        }
        if (player.getWorld()
                .isSpaceEmpty(player, player.getBoundingBox().offset(0, -GROUND_PROBE_BLOCKS, 0))) {
            // 直接检查脚下碰撞体。静止且速度为零时，原版可能清掉 onGround，但支撑面依然存在。
            throw new IllegalArgumentException("请站稳后操作");
        }
        if (cancelled.contains(move.getOperation()) || cancelled.size() >= Messages.RECEIPT_LIMIT) {
            throw new IllegalArgumentException("操作已取消或本次会话取消次数过多，请重新连接");
        }
        var account = account();
        if (service.committed(account, move)) {
            transfer =
                    Transfer.newBuilder()
                            .setOperation(move.getOperation())
                            .setPhase(Phase.COMPLETED)
                            .setDetail("转移已完成")
                            .build();
            return;
        }
        var candidate = service.prepare(account, move);
        var material = rules.material(candidate.moved());
        transfer =
                Transfer.newBuilder()
                        .setOperation(move.getOperation())
                        .setPhase(Phase.RUNNING)
                        .setDurationTicks(material.ticks())
                        .setContactSound(material.sound())
                        .setSoundVolume(material.volume())
                        .setSoundPitch(material.pitch())
                        .setSource(candidate.source())
                        .setTarget(move.getTarget())
                        .setDetail("正在转移 · ESC 可中断")
                        .build();
        pending = move;
        actor = player;
        anchor = player.getPos();
        dimension = player.getWorld().getRegistryKey();
        startingHealth = player.getHealth();
        elapsed = 0;
        player.setVelocity(Vec3d.ZERO);
    }

    public void tick() {
        // 即使 UI 未打开也同步世界外观；转移候选未提交前不会出现在玩家存档中。
        appearances.tick();
        boolean groundChanged = pruneGround();
        // 死亡、换维度、传送、受伤与原版界面切换立即取消，不把玩家拉回旧世界。
        if (pending == null) {
            // 原版切换快捷栏、交换副手不会增加业务修订号，另行推送手持变化。
            var player = connection.context();
            if (opened
                    && (groundChanged
                            || lastSelectedSlot != player.getInventory().selectedSlot
                            || inventoryChanged(player)
                            || lastCreative != player.isCreative()
                            || lastRevision
                                    != ((PlayerStorage) player)
                                            .bs3d$getInventory()
                                            .getRevision())) {
                send();
            }
            return;
        }
        var player = connection.context();
        boolean changedDimension = !player.getWorld().getRegistryKey().equals(dimension);
        boolean movedFromAnchor =
                player.getPos().squaredDistanceTo(anchor) > MAX_POSITION_DRIFT_SQUARED;
        boolean tookDamage = player.getHealth() < startingHealth;
        if (player != actor
                || !available(player)
                || changedDimension
                || movedFromAnchor
                || tookDamage) {
            cancel("角色状态变化，转移已中断");
            send();
            return;
        }
        player.setVelocity(Vec3d.ZERO);
        elapsed++;
        transfer = transfer.toBuilder().setElapsedTicks(elapsed).build();
        if (elapsed >= transfer.getDurationTicks()) {
            try {
                service.commit(account(), pending);
                transfer = transfer.toBuilder().setPhase(Phase.COMPLETED).setDetail("转移完成").build();
                unlock();
            } catch (IOException | IllegalArgumentException failure) {
                cancel("转移未提交：" + failure.getMessage());
            }
            send();
        } else if (elapsed % PROGRESS_INTERVAL_TICKS == 0) {
            send();
        }
    }

    private static boolean available(ServerPlayerEntity player) {
        // 连接和角色状态允许操作之后，还要确认原版没有正在处理的容器或鼠标栈。
        if (!player.networkHandler.isConnectionOpen() || !player.isAlive()) {
            return false;
        }
        if (player.isSpectator() || player.hasVehicle()) {
            return false;
        }
        if (player.currentScreenHandler != player.playerScreenHandler) {
            return false;
        }
        return player.playerScreenHandler.getCursorStack().isEmpty();
    }

    private boolean inventoryChanged(ServerPlayerEntity player) {
        // 手持、穿戴和便携容器内容均由真实原版栈决定，其他模组修改后也重新同步。
        var inventory = player.getInventory();
        if (lastInventory.size() != inventory.size()) {
            return true;
        }
        for (int index = 0; index < inventory.size(); index++) {
            if (!ItemStack.areEqual(lastInventory.get(index), inventory.getStack(index))) {
                return true;
            }
        }
        return false;
    }

    private void send() {
        // 进度与库存来自同一时刻的服务器状态，客户端不需要猜测转移后的库存。
        if (!opened) {
            return;
        }
        var storage = (PlayerStorage) connection.context();
        SavedInventory saved = storage.bs3d$getInventory();
        lastRevision = saved.getRevision();
        var player = connection.context();
        // 新获得的容器在第一次快照前生成真实身份，后续同步不重复分配。
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ContainerItems.initializeStack(player.getInventory().getStack(slot));
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
            var tree =
                    new ContainerTree(
                            saved, new java.util.ArrayList<>(lastInventory), account.ground());
            state.putAllGroundRevisions(account.groundRevisions());
            state.addAllItems(tree.items()).addAllContainers(tree.containers());
        } catch (IllegalArgumentException invalid) {
            // 外部获得的损坏或重复身份物品只禁用本次业务视图，不让同步异常终止服务器 tick。
            cancel("容器数据无效，转移已取消");
            transfer =
                    Transfer.newBuilder()
                            .setPhase(Phase.REJECTED)
                            .setDetail(invalid.getMessage())
                            .build();
            state.setTransfer(transfer);
        }
        lastSelectedSlot = player.getInventory().selectedSlot;
        lastCreative = player.isCreative();
        state.addPlayerSlots(playerSlot(lastSelectedSlot, player.getMainHandStack()));
        state.addPlayerSlots(playerSlot(PlayerInventory.OFF_HAND_SLOT, player.getOffHandStack()));
        for (var equipment : PlayerSlots.ARMOR) {
            state.addPlayerSlots(
                    playerSlot(
                            PlayerSlots.armorSlot(equipment), player.getEquippedStack(equipment)));
        }
        connection.send(Messages.STATE, state.build());
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

    private void cancel(String reason) {
        if (pending != null) {
            // 会话取消列表只需要覆盖这次连接；重连会换 session，旧请求不能再次生效。
            cancelled.add(pending.getOperation());
            transfer = transfer.toBuilder().setPhase(Phase.CANCELLED).setDetail(reason).build();
            unlock();
        }
    }

    private void unlock() {
        // 只释放本会话锁，不改玩家重力与飞行属性，避免把临时状态写进玩家存档。
        pending = null;
        actor = null;
    }

    @Override
    public void close() {
        // 断线回调已由 Framework 调度到服务器主线程，此时不能继续发包。
        cancel("连接已关闭");
        releaseGround(List.of());
        opened = false;
        VolumeServer.forget(this);
    }
}
