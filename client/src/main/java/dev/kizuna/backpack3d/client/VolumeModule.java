package dev.kizuna.backpack3d.client;

import com.google.protobuf.Message;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.ContainerSounds;
import dev.kizuna.backpack3d.item.ContainerSounds.Action;
import dev.kizuna.backpack3d.protocol.*;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;
import dev.kizuna.inventoryui.client.*;
import dev.kizuna.inventoryui.protocol.*;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowDefinition;

import io.wispforest.owo.ui.component.Components;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.Consumer;

/** 业务客户端共享权威快照；屏幕关闭后仍接收取消回执，避免连接状态悬空。 */
public final class VolumeModule implements ClientModule {
    private static final String WINDOW = "bs3d:launcher";
    private static final int LAUNCHER_WIDTH_PIXELS = 220;
    private static final int LAUNCHER_HEIGHT_PIXELS = 100;
    private static final float CONTAINER_SOUND_VOLUME = 0.5f;
    private static final float CONTAINER_SOUND_PITCH = 0.8f;
    private State state = State.getDefaultInstance();
    private final EquipmentAppearances appearances = new EquipmentAppearances();
    private ClientRuntime runtime;
    private String requestedOperation = "";
    private String contactPlayed = "";
    private String beginPlayed = "";
    private String completionPlayed = "";
    private String error = "等待服务器同步";
    private Map<String, ItemStack> stacks = Map.of();
    private long generation;
    private KeyBinding openKey;
    private KeyBinding rotateYaw;
    private KeyBinding rotatePitch;

    @Override
    public FrameworkCatalog.Module definition() {
        var launcher =
                new UiWindowDefinition(
                        WINDOW,
                        "3D 背包",
                        LAUNCHER_WIDTH_PIXELS,
                        LAUNCHER_HEIGHT_PIXELS,
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
        bindings.window(
                WINDOW,
                (host, window) ->
                        Components.button(Text.literal("打开 3D 背包 · B"), button -> open()));
        openKey =
                KeyBindingHelper.registerKeyBinding(
                        new KeyBinding(
                                "key.bs3d.open",
                                InputUtil.Type.KEYSYM,
                                GLFW.GLFW_KEY_B,
                                "category.bs3d"));
        rotateYaw =
                KeyBindingHelper.registerKeyBinding(
                        new KeyBinding(
                                "key.bs3d.rotate_yaw",
                                InputUtil.Type.KEYSYM,
                                GLFW.GLFW_KEY_R,
                                "category.bs3d"));
        rotatePitch =
                KeyBindingHelper.registerKeyBinding(
                        new KeyBinding(
                                "key.bs3d.rotate_pitch",
                                InputUtil.Type.KEYSYM,
                                GLFW.GLFW_KEY_T,
                                "category.bs3d"));
        ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    while (openKey.wasPressed()) {
                        if (client.player != null && client.currentScreen == null) {
                            open();
                        }
                    }
                    if (busy() && client.player != null) {
                        // 客户端阻止继续行走；服务端另有位置锁，不能只依赖界面拦截。
                        client.player.input.movementForward = 0;
                        client.player.input.movementSideways = 0;
                        client.player.input.jumping = false;
                        client.player.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
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
        bindings.message(new UiWire.Contract<>(contract, UiWire.Codec.protobuf(sample), true));
    }

    public void open() {
        // 本地模型即使服务器缺失也可查看，但库存操作保持禁用并显示协商原因。
        MinecraftClient.getInstance().setScreen(new VolumeScreen(this));
        if (runtime != null) {
            send(Messages.OPEN, OpenRequest.getDefaultInstance());
        }
    }

    private void openGround(GroundOpened opened) {
        // 服务器先发完整快照再发打开事件，客户端不猜测地面背包的内容。
        var client = MinecraftClient.getInstance();
        if (!(client.currentScreen instanceof VolumeScreen)) {
            client.setScreen(new VolumeScreen(this));
        }
        ((VolumeScreen) client.currentScreen).openContainer(opened.getContainer());
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
        return !requestedOperation.isEmpty() || state.getTransfer().getPhase() == Phase.RUNNING;
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
            // OPEN 的较早快照不能抹掉已经发出的 MOVE，否则关闭界面会漏发 CANCEL。
            requestedOperation = "";
        }
        playTransferSounds(transfer);
    }

    private void playTransferSounds(Transfer transfer) {
        // 进度快照会重复到达，每个操作的开始、接触和完成音效分别去重。
        boolean touchesContainer =
                !transfer.getSource().equals(Volumes.PLAYER)
                        || !transfer.getTarget().equals(Volumes.PLAYER);
        if (transfer.getPhase() == Phase.RUNNING) {
            if (!transfer.getOperation().equals(beginPlayed)) {
                beginPlayed = transfer.getOperation();
                if (touchesContainer) {
                    transferContainerSound(transfer);
                }
            }
            if (transfer.getElapsedTicks() >= transfer.getDurationTicks() / 2
                    && !transfer.getOperation().equals(contactPlayed)) {
                contactPlayed = transfer.getOperation();
                sound(
                        transfer.getContactSound(),
                        transfer.getSoundVolume(),
                        transfer.getSoundPitch());
            }
        } else if (transfer.getPhase() == Phase.COMPLETED
                && !transfer.getOperation().equals(completionPlayed)) {
            completionPlayed = transfer.getOperation();
            if (touchesContainer) {
                transferContainerSound(transfer);
            }
        }
    }

    public KeyBinding openKey() {
        return openKey;
    }

    public KeyBinding rotateYaw() {
        return rotateYaw;
    }

    public KeyBinding rotatePitch() {
        return rotatePitch;
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
        var player = MinecraftClient.getInstance().player;
        if (player != null) {
            deposit(
                    playerSlot(player.getInventory().selectedSlot),
                    target,
                    Cell.getDefaultInstance(),
                    true,
                    0);
        }
    }

    public void deposit(
            PlayerSlotStack source, String target, Cell cell, boolean automatic, int orientation) {
        // 指纹固定为按下鼠标时的快照；服务器在开始和完成时都重新读取真实槽位。
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
        // 拖回原部位不发请求；换手和穿脱装备都通过服务器计时转移。
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
        // 装备实物与容器间转移共用计时请求，不通过本地换挂点提前改变服务器状态。
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
        if (!source.equals(playerSlot(source.getPlayerSlot()))) {
            error = "物品已变化，请重新选择";
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
        // 发起后立即锁定输入，等待服务端开始计时；取消请求可越过此等待状态。
        requestedOperation = value.getOperation();
        send(Messages.MOVE, value);
    }

    public void cancel() {
        String operation =
                requestedOperation.isEmpty()
                        ? state.getTransfer().getOperation()
                        : requestedOperation;
        if (ready() && busy() && !operation.isEmpty()) {
            send(
                    Messages.CANCEL,
                    CancelRequest.newBuilder()
                            .setSession(state.getSession())
                            .setOperation(operation)
                            .build());
        }
    }

    private void send(String id, Message value) {
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
                                requestedOperation = "";
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
        // 所有会话内的进度、音效去重键和 NBT 缓存都随断线失效。
        generation++;
        runtime = null;
        state = State.getDefaultInstance();
        appearances.clear();
        stacks = Map.of();
        requestedOperation = "";
        beginPlayed = "";
        contactPlayed = "";
        completionPlayed = "";
        error = "连接已断开";
    }

    private void transferContainerSound(Transfer transfer) {
        // 优先使用目标容器的触碰声；取出到手上时使用来源容器。
        String id = transfer.getTarget();
        if (id.equals(Volumes.PLAYER) || id.equals(Volumes.WORN)) {
            id = transfer.getSource();
        }
        containerSound(id, Action.TOUCH);
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
        var identifier = Identifier.tryParse(id);
        if (identifier != null && Registries.SOUND_EVENT.containsId(identifier)) {
            MinecraftClient.getInstance()
                    .getSoundManager()
                    .play(
                            PositionedSoundInstance.master(
                                    Registries.SOUND_EVENT.get(identifier), pitch, volume));
        }
    }
}
