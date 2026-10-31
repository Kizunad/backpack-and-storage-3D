package dev.kizuna.backpack3d.client;

import com.google.protobuf.Message;
import com.mojang.blaze3d.platform.InputConstants;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.ContainerSounds;
import dev.kizuna.backpack3d.item.ContainerSounds.Action;
import dev.kizuna.backpack3d.protocol.Messages;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.CancelRequest;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Cell;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.CloseGroundRequest;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Container;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.EquipRequest;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.EquipmentAppearance;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.GroundOpened;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.MoveRequest;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.OpenRequest;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Phase;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Placement;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.PlayerSlotStack;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.State;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Transfer;
import dev.kizuna.inventoryui.client.ClientBindings;
import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.client.WorkspaceScreen;
import dev.kizuna.inventoryui.protocol.UiMessageRouter;
import dev.kizuna.inventoryui.protocol.UiProtocolSession;
import dev.kizuna.inventoryui.protocol.UiWire;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowDefinition;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** 业务客户端共享权威快照；屏幕关闭后仍接收即时回执，避免连接状态悬空。 */
public final class VolumeModule implements ClientModule {
    private static final String WINDOW = "bs3d:launcher";
    private static final int WINDOW_MIN_WIDTH_PIXELS = 280;
    private static final int WINDOW_MIN_HEIGHT_PIXELS = 220;
    private static final int WINDOW_PREFERRED_WIDTH_PIXELS = 640;
    private static final int WINDOW_PREFERRED_HEIGHT_PIXELS = 420;
    private static final float CONTAINER_SOUND_VOLUME = 0.5f;
    private static final float CONTAINER_SOUND_PITCH = 0.8f;
    private State state = State.getDefaultInstance();
    private final EquipmentAppearances appearances = new EquipmentAppearances();
    private ClientRuntime runtime;
    private String requestedOperation = "";
    private String completionPlayed = "";
    private String pendingItem = "";
    private String pendingPreview = "";
    private String error = "等待服务器同步";
    private Map<String, ItemStack> stacks = Map.of();
    private long generation;
    private KeyMapping openKey;
    private VolumeComponent embedded;
    private boolean workspaceVisible;

    @Override
    public FrameworkCatalog.Module definition() {
        // 工作台入口直接承载完整背包场景，推荐尺寸给人物与容器内景留足空间。
        var launcher =
                new UiWindowDefinition(
                        WINDOW,
                        "3D 背包",
                        WINDOW_MIN_WIDTH_PIXELS,
                        WINDOW_MIN_HEIGHT_PIXELS,
                        WINDOW_PREFERRED_WIDTH_PIXELS,
                        WINDOW_PREFERRED_HEIGHT_PIXELS,
                        Set.of(UiWindowDefinition.Capability.WINDOW));
        return FrameworkCatalog.Module.of(
                Messages.MODULE,
                Set.of("kiui:core"),
                new FrameworkCatalog.Window(launcher),
                new FrameworkCatalog.Feature(
                        "bs3d:open", "3D 背包", "库存", "minecraft:chest", WINDOW));
    }

    @Override
    public void register(ClientBindings bindings) {
        // 输入与屏幕是业务层，主题和网络继续由框架统一提供。
        outgoing(bindings, Messages.OPEN, OpenRequest.getDefaultInstance());
        outgoing(bindings, Messages.MOVE, MoveRequest.getDefaultInstance());
        outgoing(bindings, Messages.EQUIP, EquipRequest.getDefaultInstance());
        outgoing(bindings, Messages.CANCEL, CancelRequest.getDefaultInstance());
        outgoing(bindings, Messages.CLOSE_GROUND, CloseGroundRequest.getDefaultInstance());
        message(
                bindings,
                Messages.GROUND_OPENED,
                GroundOpened.getDefaultInstance(),
                UiMessageRouter.Direction.SERVER_TO_CLIENT,
                this::openGround);
        message(
                bindings,
                Messages.STATE,
                State.getDefaultInstance(),
                UiMessageRouter.Direction.SERVER_TO_CLIENT,
                this::accept);
        message(
                bindings,
                Messages.APPEARANCE,
                EquipmentAppearance.getDefaultInstance(),
                UiMessageRouter.Direction.SERVER_TO_CLIENT,
                appearances::accept);
        WornPreview.register(appearances);
        ModelResources.initialize();
        ContainerItemModels.register();
        ItemDescriptions.register();
        GroundBagRenderer.register();
        registerWindow(bindings);
        registerOpenKey();
    }

    private void registerWindow(ClientBindings bindings) {
        // 工作台窗口的访问权随作用域释放，最小化仍保留场景。
        bindings.window(
                WINDOW,
                (host, window) -> {
                    // 窗口工厂只挂载场景；清理和地面访问释放与这个窗口的 scope 一起结束。
                    var component = new VolumeComponent(this, () -> host.workspace().windows().close(window.key()));
                    embedded = component;
                    window.scope().addCleanup(() -> {
                        component.close();
                        if (embedded == component) {
                            embedded = null;
                        }
                    });
                    return component;
                });
    }

    private void registerOpenKey() {
        // 世界快捷键和工作台场景推进共享客户端 tick，不引入转移计时。
        openKey =
                KeyBindingHelper.registerKeyBinding(
                        new KeyMapping(
                                "key.bs3d.open",
                                InputConstants.Type.KEYSYM,
                                GLFW.GLFW_KEY_B,
                                "category.bs3d"));
        ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    // 工作台退出时结束背包访问；仅最小化窗口仍保留原有转移语义。
                    boolean visible = client.screen instanceof WorkspaceScreen;
                    if (embedded != null) {
                        if (workspaceVisible && !visible) {
                            embedded.scene().removed();
                        } else if (visible) {
                            embedded.scene().tick();
                        }
                    }
                    workspaceVisible = visible;
                    while (openKey.consumeClick()) {
                        if (client.player != null && client.screen == null) {
                            open();
                        }
                    }
                });
    }

    private static <T extends Message> void outgoing(ClientBindings bindings, String id, T sample) {
        message(bindings, id, sample, UiMessageRouter.Direction.CLIENT_TO_SERVER, null);
    }

    private static <T extends Message> void message(
            ClientBindings bindings,
            String id,
            T sample,
            UiMessageRouter.Direction direction,
            Consumer<T> handler) {
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) sample.getClass();
        var contract =
                new UiMessageRouter.Contract<>(
                        Messages.MODULE, id, Messages.VERSION, direction, type, handler);
        UiWire.Codec<T> codec = UiWire.Codec.protobuf(sample, value -> value, Messages::validatePayload);
        bindings.message(new UiWire.Contract<>(contract, codec, true /* required */));
    }

    public void open() {
        // 工作台已拥有场景时恢复同一个实例，避免两套界面同时处理背包会话。
        if (embedded != null) {
            InventoryUiClient.runtime().open("bs3d:open", "");
            InventoryUiClient.runtime().openWorkspace();
        } else {
            Minecraft.getInstance().setScreen(new VolumeScreen(this));
        }
    }

    void openScene() {
        // 页面真正可见时才授权附近地面模型，普通同步请求不取得租约。
        var request = OpenRequest.newBuilder()
                .setIncludeNearbyGround(true /* includeNearbyGround */)
                .build();
        send(Messages.OPEN, request);
    }

    private void openGround(GroundOpened opened) {
        // 服务器先发完整快照再发打开事件，客户端不猜测地面背包的内容。
        var client = Minecraft.getInstance();
        if (embedded != null) {
            InventoryUiClient.runtime().open("bs3d:open", "");
            if (!(client.screen instanceof WorkspaceScreen)) {
                InventoryUiClient.runtime().openWorkspace();
            }
            embedded.scene().openContainer(opened.getContainer());
            return;
        }
        if (!(client.screen instanceof VolumeScreen)) {
            client.setScreen(new VolumeScreen(this));
        }
        ((VolumeScreen) client.screen).openContainer(opened.getContainer());
    }

    public void closeGround(String id) {
        if (!ready()
                || state.getGroundRevisionsCount() == 0
                || (!id.isEmpty() && !state.containsGroundRevisions(id))) {
            return;
        }
        var request = CloseGroundRequest.newBuilder().setSession(state.getSession());
        if (!id.isEmpty()) {
            request.addContainers(id);
        }
        send(Messages.CLOSE_GROUND, request.build());
    }

    public State state() {
        return state;
    }

    public EquipmentAppearances appearances() {
        return appearances;
    }

    public ItemStack stack(String id) {
        return stacks.getOrDefault(id, ItemStack.EMPTY).copy();
    }

    public boolean busy() {
        // 只阻止同一客户端重复提交，服务器不存在跨 tick 转移锁。
        return !requestedOperation.isEmpty();
    }

    public boolean ready() {
        return runtime != null && !state.getSession().isEmpty();
    }

    public String status() {
        if (!ready()) {
            String protocol = InventoryUiClient.runtime().protocolError();
            return protocol == null || protocol.isBlank() ? error : "服务器缺少 3D 背包模块：" + protocol;
        }
        if (!error.isEmpty()) {
            return error;
        }
        return state.getTransfer().getDetail().isBlank() ? "就绪" : state.getTransfer().getDetail();
    }

    private void accept(State next) {
        // 只缓存服务器的展示栈，物品移动不做本地预测写入。
        if (next.getRevision() < state.getRevision()
                && next.getSession().equals(state.getSession())) {
            return;
        }
        var decoded = new HashMap<String, ItemStack>();
        for (var item : next.getItemsList()) {
            decoded.put(item.getId(), StackCodec.decode(item.getStackNbt()));
        }
        for (PlayerSlotStack hand : next.getPlayerSlotsList()) {
            if (!hand.getStackNbt().isEmpty()) {
                decoded.put(
                        playerSlotPreviewId(hand.getPlayerSlot()),
                        StackCodec.decode(hand.getStackNbt()));
            }
        }
        stacks = Map.copyOf(decoded);
        state = next;
        var transfer = next.getTransfer();
        error = "";
        if (transfer.getOperation().equals(requestedOperation)) {
            // OPEN 的较早快照不能抹掉已经发出的 MOVE。
            requestedOperation = "";
            pendingItem = "";
        }
        playTransferSounds(transfer);
    }

    private void playTransferSounds(Transfer transfer) {
        // 同一操作的重复快照只播放一次材质完成声，不再播放开始和中段声音。
        if (transfer.getPhase() == Phase.COMPLETED
                && !transfer.getOperation().equals(completionPlayed)) {
            completionPlayed = transfer.getOperation();
            sound(transfer.getContactSound(), transfer.getSoundVolume(), transfer.getSoundPitch());
        }
    }

    public boolean pendingItem(String id) {
        // 权威回执前保留原位置，仅把该物品绘制成半透明占位。
        return !id.isEmpty() && (pendingItem.equals(id) || pendingPreview.equals(id));
    }

    public void showPendingPreview(String id) {
        // 原版页面与独立页面共用来源占位渲染，库存仍由各自权威回执更新。
        pendingPreview = id;
    }

    public void clearPendingPreview() {
        // 附属的终态回执或断线只清理展示，不修改库存。
        pendingPreview = "";
    }

    public void quickStore(PlayerSlotStack source) {
        // 自动目标由服务端按腰挂优先顺序选择，客户端不使用显示切片计算容量。
        deposit(source, Volumes.CARRIED, Cell.getDefaultInstance(), true /* automatic */, 0);
    }

    public KeyMapping openKey() {
        return openKey;
    }

    public InputConstants.Key rotateYaw() {
        // 只提供场景局部键值，不注册或更新全局 KeyMapping 映射。
        return ModelResources.rotationControls().yaw();
    }

    public InputConstants.Key rotatePitch() {
        // 与左右旋转共用同一次配置重载结果。
        return ModelResources.rotationControls().pitch();
    }

    public Mount mount(String id) {
        // 未收到装备快照时采用该容器的默认挂点。
        for (Container container : state.getContainersList()) {
            if (container.getId().equals(id) && container.getMount() != Mount.MOUNT_DEFAULT) {
                return container.getMount();
            }
        }
        return Mount.MOUNT_DEFAULT;
    }

    public Container container(String id) {
        // 容器目录来自当前玩家物品树，类型名称不再充当实例身份。
        return state.getContainersList().stream()
                .filter(value -> value.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("容器已移走"));
    }

    public void cycleMount(String id) {
        // 装备切换只发绝对挂点，实际模型在权威快照到达后更新。
        if (!ready() || busy()) {
            return;
        }
        var type = ContainerItems.definition(stack(id));
        if (type == null || type.mounts().isEmpty()) {
            return;
        }
        var allowed = type.mounts();
        int nextIndex = (allowed.indexOf(mount(id)) + 1) % allowed.size();
        var placement = Placement.newBuilder().setContainer(id).setMount(allowed.get(nextIndex));
        var request =
                EquipRequest.newBuilder()
                        .setSession(state.getSession())
                        .setRevision(state.getRevision())
                        .setPlacement(placement)
                        .build();
        send(Messages.EQUIP, request);
    }

    public void move(String item, String target, Cell cell, boolean automatic) {
        // 旧入口保留已有朝向；取回原版背包时朝向只影响展示。
        int orientation = 0;
        for (Item stored : state.getItemsList()) {
            if (stored.getId().equals(item)) {
                orientation = stored.getOrientation();
                break;
            }
        }
        move(item, target, cell, automatic, orientation);
    }

    public void move(String item, String target, Cell cell, boolean automatic, int orientation) {
        // 一次只允许一个在途操作，等待服务端回执后才能再次放置。
        if (!ready() || busy()) {
            return;
        }
        var request =
                base().setItem(item)
                        .setTarget(target)
                        .setCell(cell)
                        .setAutoPlace(automatic)
                        .setOrientation(orientation)
                        .build();
        request(request);
    }

    public PlayerSlotStack playerSlot(int slot) {
        // 只暴露服务器确认的双手和盔甲，旧快捷栏槽不能继续作为拖动来源。
        for (PlayerSlotStack snapshot : state.getPlayerSlotsList()) {
            if (snapshot.getPlayerSlot() == slot) {
                return snapshot;
            }
        }
        return null;
    }

    public static String playerSlotPreviewId(int slot) {
        return "player-slot:" + slot;
    }

    public void deposit(String target) {
        // 自动放置入口保留给程序接入；页面拖动使用带有精确格位的重载。
        var player = Minecraft.getInstance().player;
        if (player != null) {
            deposit(
                    playerSlot(player.getInventory().selected),
                    target,
                    Cell.getDefaultInstance(),
                    true,
                    0);
        }
    }

    public void deposit(
            PlayerSlotStack source, String target, Cell cell, boolean automatic, int orientation) {
        // 指纹固定为按下鼠标时的快照；服务器在校验和提交时都读取真实槽位。
        if (!canMovePlayerStack(source)) {
            return;
        }
        var request =
                base().setTarget(target)
                        .setCell(cell)
                        .setAutoPlace(automatic)
                        .setOrientation(orientation)
                        .setPlayerSlot(source.getPlayerSlot())
                        .setStackFingerprint(StackCodec.fingerprint(source.getStackNbt()))
                        .build();
        request(request);
    }

    public void withdraw(String item, int targetSlot) {
        // 按落点请求指定的手或盔甲部位；失败由服务器保留原库存。
        if (!ready() || busy() || playerSlot(targetSlot) == null) {
            return;
        }
        request(
                base().setItem(item)
                        .setTarget(Volumes.PLAYER)
                        .setTargetPlayerSlot(targetSlot)
                        .build());
    }

    public void movePlayerStack(PlayerSlotStack source, int targetSlot) {
        // 拖回原部位不发请求；换手和穿脱装备都通过服务器即时转移。
        if (!canMovePlayerStack(source) || playerSlot(targetSlot) == null) {
            return;
        }
        if (source.getPlayerSlot() == targetSlot) {
            return;
        }
        request(
                base().setTarget(Volumes.PLAYER)
                        .setPlayerSlot(source.getPlayerSlot())
                        .setStackFingerprint(StackCodec.fingerprint(source.getStackNbt()))
                        .setTargetPlayerSlot(targetSlot)
                        .build());
    }

    public void wear(String item, PlayerSlotStack source, Placement placement) {
        // 装备实物与容器间转移共用即时请求，不通过本地换挂点提前改变服务器状态。
        if (!ready() || busy()) {
            return;
        }
        var request = base().setTarget(Volumes.WORN).setWornTarget(placement);
        if (source != null) {
            if (!canMovePlayerStack(source)) {
                return;
            }
            request.setPlayerSlot(source.getPlayerSlot())
                    .setStackFingerprint(StackCodec.fingerprint(source.getStackNbt()));
        } else {
            request.setItem(item);
        }
        request(request.build());
    }

    private boolean canMovePlayerStack(PlayerSlotStack source) {
        // 所有身上来源共用可用性检查，客户端提示与服务器快照中的卸装限制一致。
        if (!ready() || busy() || source == null) {
            return false;
        }
        if (!source.getUnavailableReason().isEmpty()) {
            error = source.getUnavailableReason();
            return false;
        }
        if (source.getStackNbt().isEmpty()) {
            error = "这个部位没有物品";
            return false;
        }
        return true;
    }

    private MoveRequest.Builder base() {
        return MoveRequest.newBuilder()
                .setSession(state.getSession())
                .setRevision(state.getRevision())
                .putAllGroundRevisions(state.getGroundRevisionsMap())
                .setOperation(UUID.randomUUID().toString());
    }

    private void request(MoveRequest value) {
        // 仅防止同一来源重复提交，不限制角色移动或页面关闭。
        requestedOperation = value.getOperation();
        pendingItem = value.getItem();
        if (pendingItem.isEmpty()) {
            pendingItem = playerSlotPreviewId(value.getPlayerSlot());
        }
        send(Messages.MOVE, value);
    }

    private void send(String id, Message value) {
        // 等待认证连接后发送意图，失败只恢复占位并请求权威快照。
        if (runtime == null || runtime.protocol().state() != UiProtocolSession.State.READY) {
            return;
        }
        long current = generation;
        runtime.protocol()
                .request(id, value, System.currentTimeMillis())
                .thenAccept(
                        outcome -> {
                            // 重连前的回调不能覆盖新会话；超时只重同步，不重放转移。
                            if (current == generation && !outcome.accepted()) {
                                if (value instanceof MoveRequest move
                                        && state.getTransfer().getOperation().equals(move.getOperation())) {
                                    return;
                                }
                                requestedOperation = "";
                                pendingItem = "";
                                error = "操作未完成：" + outcome.code();
                                if (!id.equals(Messages.OPEN)) {
                                    send(Messages.OPEN, OpenRequest.getDefaultInstance());
                                }
                            }
                        });
    }

    @Override
    public void protocolReady(ClientRuntime host) {
        runtime = host;
        send(Messages.OPEN, OpenRequest.getDefaultInstance());
    }

    @Override
    public void disconnected(ClientRuntime host) {
        // 所有会话内的来源占位、音效去重键和 NBT 缓存都随断线失效。
        generation++;
        runtime = null;
        state = State.getDefaultInstance();
        appearances.clear();
        stacks = Map.of();
        requestedOperation = "";
        pendingItem = "";
        pendingPreview = "";
        completionPlayed = "";
        error = "连接已断开";
    }

    public void containerSound(String containerId, Action action) {
        // 快照中找实例对应的模型，实例 UUID 不能被误当作资源 ID。
        for (Container container : state.getContainersList()) {
            if (container.getId().equals(containerId)) {
                sound(
                        ContainerSounds.id(container.getModel(), action).toString(),
                        CONTAINER_SOUND_VOLUME,
                        CONTAINER_SOUND_PITCH);
                return;
            }
        }
    }

    private static void sound(String id, float volume, float pitch) {
        // 音效 ID 来自已注册 SoundEvent，允许数据包选择已有模组音效。
        var identifier = ResourceLocation.tryParse(id);
        if (identifier != null && BuiltInRegistries.SOUND_EVENT.containsKey(identifier)) {
            Minecraft.getInstance()
                    .getSoundManager()
                    .play(
                            SimpleSoundInstance.forUI(
                                    BuiltInRegistries.SOUND_EVENT.get(identifier), pitch, volume));
        }
    }
}
