package dev.kizuna.backpack3d.server;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.ContainerSounds;
import dev.kizuna.backpack3d.item.ContainerSounds.Action;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.service.ContainerTree;
import dev.kizuna.backpack3d.world.GroundBagBlockEntity;
import dev.kizuna.backpack3d.world.GroundBags;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 服务端地面实物目录；区块中的模型只是标记，玩家和地面库存由恢复日志共同提交。 */
public final class GroundInventory implements GroundBags.Actions {
    private static final int SAVE_VERSION = 1;
    private static final double REACH_BLOCKS = 5;
    private static final float SOUND_VOLUME = 0.8f;
    private static final float SOUND_PITCH = 1;
    private static final Map<MinecraftServer, GroundInventory> SERVERS = new HashMap<>();
    private final MinecraftServer server;
    private final GroundJournal journal;
    private final Map<String, UUID> leases = new HashMap<>();
    private final Map<ServerLevel, Set<ChunkPos>> loadedChunks = new HashMap<>();
    private Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean recoveryRequired;

    private GroundInventory(MinecraftServer server) throws IOException {
        this.server = server;
        journal =
                new GroundJournal(
                        server.getWorldPath(LevelResource.ROOT).resolve("data/bs3d"),
                        server.getWorldPath(LevelResource.PLAYER_DATA_DIR));
        journal.recover();
        if (Files.exists(journal.inventoryFile())) {
            read(NbtIo.readCompressed(journal.inventoryFile(), NbtAccounter.unlimitedHeap()));
        }
    }

    public static void register() {
        // 创造模式也通过收回操作移动实物，不能绕过库存事务直接破坏标记方块。
        PlayerBlockBreakEvents.BEFORE.register(
                (world, player, pos, state, entity) -> !state.is(GroundBags.BLOCK));
        // 目录恢复先于玩家登录，区块载入时按权威目录补齐或移除落地模型。
        ServerLifecycleEvents.SERVER_STARTING.register(
                server -> {
                    try {
                        SERVERS.put(server, new GroundInventory(server));
                    } catch (IOException failure) {
                        throw new UncheckedIOException("地面背包恢复失败", failure);
                    }
                });
        ServerLifecycleEvents.SERVER_STARTED.register(
                server -> {
                    var inventory = get(server);
                    for (Entry entry : inventory.entries.values()) {
                        var world = inventory.world(entry);
                        if (world != null && world.hasChunkAt(entry.pos())) {
                            inventory.show(entry, world);
                        }
                    }
                });
        ServerLifecycleEvents.SERVER_STOPPED.register(SERVERS::remove);
        ServerChunkEvents.CHUNK_LOAD.register(
                (world, chunk, generated) -> {
                    var inventory = SERVERS.get(world.getServer());
                    if (inventory != null) {
                        // 此回调位于区块加载 future 内，不能调用 world.setBlockState 再次等待自己。
                        inventory
                                .loadedChunks
                                .computeIfAbsent(world, ignored -> new HashSet<>())
                                .add(chunk.getPos());
                    }
                });
        ServerTickEvents.END_SERVER_TICK.register(
                server -> {
                    var inventory = SERVERS.get(server);
                    if (inventory != null && !inventory.recoveryRequired) {
                        inventory.reconcileLoadedChunks();
                    }
                });
        // 共享代码不引用服务端类，两端仅注册一次物品和方块。
        GroundBags.actions(
                new GroundBags.Actions() {
                    @Override
                    public InteractionResult place(UseOnContext context) {
                        return get(context.getLevel().getServer()).place(context);
                    }

                    @Override
                    public InteractionResult use(
                            Player player, InteractionHand hand, GroundBagBlockEntity bag) {
                        return get(player.level().getServer()).use(player, hand, bag);
                    }
                });
    }

    public static GroundInventory get(MinecraftServer server) {
        var inventory = SERVERS.get(server);
        if (inventory == null || inventory.recoveryRequired) {
            throw new IllegalStateException("地面库存当前不可用");
        }
        return inventory;
    }

    @Override
    public InteractionResult place(UseOnContext context) {
        var player = (ServerPlayer) context.getPlayer();
        if (player == null) {
            return InteractionResult.FAIL;
        }
        try {
            requireActor(player);
            var world = player.level();
            var placement = new BlockPlaceContext(context);
            BlockPos pos = placement.getClickedPos();
            // 只在有支撑的上表面落地，不替换实体方块，也不允许隔墙或超距放置。
            if (context.getClickedFace() != Direction.UP
                    || !player.isShiftKeyDown()
                    || !world.getBlockState(pos).canBeReplaced(placement)
                    || !world.getBlockState(pos.below())
                            .isFaceSturdy(world, pos.below(), Direction.UP)
                    || !world.isUnobstructed(
                            GroundBags.BLOCK.defaultBlockState(), pos, CollisionContext.empty())
                    || !world.noCollision(
                            GroundBags.BLOCK
                                    .defaultBlockState()
                                    .getCollisionShape(world, pos)
                                    .bounds()
                                    .move(pos))
                    || world.isOutsideBuildHeight(pos)
                    || !world.getWorldBorder().isWithinBounds(pos)
                    || !world.mayInteract(player, pos)
                    || !world.mayInteract(player, context.getClickedPos())
                    || player.getEyePosition().distanceToSqr(context.getClickLocation())
                            > REACH_BLOCKS * REACH_BLOCKS) {
                throw new IllegalArgumentException("请在身边有支撑的空地上放置背包");
            }
            var hit =
                    world.clip(
                            new ClipContext(
                                    player.getEyePosition(),
                                    context.getClickLocation(),
                                    ClipContext.Block.COLLIDER,
                                    ClipContext.Fluid.NONE,
                                    player));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS
                    && !hit.getBlockPos().equals(context.getClickedPos())) {
                throw new IllegalArgumentException("不能隔着方块放置背包");
            }
            var account = new PlayerAccount(player);
            var vanilla = account.inventory();
            int slot = handSlot(player, context.getHand());
            ItemStack stack = vanilla.get(slot);
            ContainerItems.initializeStack(stack);
            var type = ContainerItems.definition(stack);
            if (type == null || stack.getCount() != 1) {
                throw new IllegalArgumentException("请手持一件背包");
            }
            new ContainerTree(account.registries(), account.data(), new ArrayList<>(vanilla));
            String id = ContainerItems.identity(stack);
            StackCodec.encode(stack, account.registries());
            if (entries.containsKey(id)) {
                throw new IllegalArgumentException("该背包身份已在地面存在");
            }
            String dimension = world.dimension().identifier().toString();
            for (Entry existing : entries.values()) {
                if (existing.dimension().equals(dimension) && existing.pos().equals(pos)) {
                    throw new IllegalArgumentException("这里已有地面背包");
                }
            }
            var entry =
                    new Entry(id, dimension, pos.immutable(), player.getYRot(), 0, stack.copy());
            var next = new LinkedHashMap<>(entries);
            next.put(id, entry);
            vanilla.set(slot, ItemStack.EMPTY);
            // 创造模式也移动同一件实物，不能复制其 UUID 和内容。
            commit(player, nextRevision(account.data()), vanilla, next);
            show(entry, world);
            world.playSound(
                    null,
                    pos,
                    ContainerSounds.event(type.model(), Action.PLACE),
                    SoundSource.BLOCKS,
                    SOUND_VOLUME,
                    SOUND_PITCH);
            return InteractionResult.CONSUME;
        } catch (IOException | IllegalArgumentException invalid) {
            player.sendOverlayMessage(Component.literal(invalid.getMessage()));
            return InteractionResult.FAIL;
        }
    }

    @Override
    public InteractionResult use(Player actor, InteractionHand hand, GroundBagBlockEntity bag) {
        var player = (ServerPlayer) actor;
        try {
            requireActor(player);
            Entry entry = requireAccess(player, bag.container());
            if (!entry.pos().equals(bag.getBlockPos())) {
                throw new IllegalArgumentException("地面背包位置不一致");
            }
            if (player.isShiftKeyDown() && player.getItemInHand(hand).isEmpty()) {
                pickup(player, hand, entry);
            } else {
                VolumeServer.session(player).openGround(entry.id());
            }
            return InteractionResult.CONSUME;
        } catch (IOException | IllegalArgumentException invalid) {
            player.sendOverlayMessage(Component.literal(invalid.getMessage()));
            return InteractionResult.FAIL;
        }
    }

    private void pickup(ServerPlayer player, InteractionHand hand, Entry entry) throws IOException {
        if (leases.containsKey(entry.id())) {
            throw new IllegalArgumentException("这个背包正在使用，请先关闭内景");
        }
        var account = new PlayerAccount(player);
        var vanilla = account.inventory();
        vanilla.set(handSlot(player, hand), entry.stack().copy());
        var data = nextRevision(account.data());
        new ContainerTree(account.registries(), data, vanilla);
        var next = new LinkedHashMap<>(entries);
        next.remove(entry.id());
        commit(player, data, vanilla, next);
        player.level()
                .setBlock(entry.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        player.level()
                .playSound(
                        null,
                        entry.pos(),
                        ContainerSounds.event(
                                ContainerItems.definition(entry.stack()).model(), Action.PICKUP),
                        SoundSource.BLOCKS,
                        SOUND_VOLUME,
                        SOUND_PITCH);
    }

    public Entry requireAccess(ServerPlayer player, String id) {
        Entry entry = entries.get(id);
        if (entry == null
                || !entry.dimension()
                        .equals(player.level().dimension().identifier().toString())
                || !player.level().hasChunkAt(entry.pos())
                || player.getEyePosition().distanceToSqr(Vec3.atCenterOf(entry.pos()))
                        > REACH_BLOCKS * REACH_BLOCKS
                || !player.level().mayInteract(player, entry.pos())
                || !(player.level().getBlockEntity(entry.pos())
                        instanceof GroundBagBlockEntity bag)
                || !bag.container().equals(id)) {
            throw new IllegalArgumentException("地面背包已不可访问");
        }
        // 每次开始与完成操作都重新检查遮挡，不允许打开后穿墙继续取物。
        Vec3 target =
                player.level()
                        .getBlockState(entry.pos())
                        .getShape(player.level(), entry.pos())
                        .bounds()
                        .getCenter()
                        .add(Vec3.atLowerCornerOf(entry.pos()));
        var hit =
                player.level()
                        .clip(
                                new ClipContext(
                                        player.getEyePosition(),
                                        target,
                                        ClipContext.Block.COLLIDER,
                                        ClipContext.Fluid.NONE,
                                        player));
        if (!hit.getBlockPos().equals(entry.pos())) {
            throw new IllegalArgumentException("地面背包被遮挡");
        }
        return entry;
    }

    public void acquire(ServerPlayer player, String id) {
        requireAccess(player, id);
        UUID owner = leases.get(id);
        if (owner != null && !owner.equals(player.getUUID())) {
            throw new IllegalArgumentException("另一位玩家正在使用这个背包");
        }
        leases.put(id, player.getUUID());
    }

    List<String> acquireNearby(ServerPlayer player, Set<String> current, int limit) {
        // 计入已有访问权，补齐当前可见且未被他人使用的地面根，不突破页面上限。
        var result = new ArrayList<>(current);
        for (String id : entries.keySet()) {
            if (result.size() == limit) {
                break;
            }
            if (current.contains(id)) {
                continue;
            }
            try {
                acquire(player, id);
                result.add(id);
            } catch (IllegalArgumentException unavailable) {
                // 不可见、超距或被占用的容器不出现在当前页面。
            }
        }
        return List.copyOf(result);
    }

    public void release(UUID player, String id) {
        leases.remove(id, player);
    }

    public Entry leased(ServerPlayer player, String id) {
        if (!player.getUUID().equals(leases.get(id))) {
            throw new IllegalArgumentException("地面背包访问权已失效");
        }
        return requireAccess(player, id);
    }

    void commitContents(
            ServerPlayer player,
            SavedInventory data,
            List<ItemStack> vanilla,
            Map<String, ItemStack> ground)
            throws IOException {
        var next = new LinkedHashMap<>(entries);
        for (var candidate : ground.entrySet()) {
            Entry old = leased(player, candidate.getKey());
            StackCodec.encode(candidate.getValue(), player.registryAccess());
            if (!ContainerItems.identity(candidate.getValue()).equals(old.id())) {
                throw new IllegalArgumentException("不能通过转移替换地面背包身份");
            }
            next.put(
                    old.id(),
                    new Entry(
                            old.id(),
                            old.dimension(),
                            old.pos(),
                            old.yaw(),
                            Math.addExact(old.revision(), 1),
                            candidate.getValue().copy()));
        }
        commit(player, data, vanilla, next);
    }

    private void commit(
            ServerPlayer player,
            SavedInventory data,
            List<ItemStack> vanilla,
            Map<String, Entry> next)
            throws IOException {
        var account = new PlayerAccount(player);
        try {
            journal.commit(player.getUUID(), account.snapshot(data, vanilla), write(next));
        } catch (GroundJournal.RecoveryRequiredException committed) {
            // 已过日志提交点不能回滚或继续新事务；停服后先重放日志，再允许玩家读档。
            recoveryRequired = true;
            server.halt(false);
            throw new IllegalStateException(committed.getMessage(), committed);
        }
        entries = next;
        account.applyCommitted(data, vanilla);
    }

    private static void requireActor(ServerPlayer player) {
        if (!player.isAlive()
                || player.isSpectator()
                || player.isPassenger()
                || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()) {
            throw new IllegalArgumentException("当前不能操作地面背包");
        }
    }

    private static int handSlot(ServerPlayer player, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND
                ? player.getInventory().getSelectedSlot()
                : Inventory.SLOT_OFFHAND;
    }

    private static SavedInventory nextRevision(SavedInventory data) {
        return data.toBuilder().setRevision(Math.addExact(data.getRevision(), 1)).build();
    }

    private ServerLevel world(Entry entry) {
        return server.getLevel(
                ResourceKey.create(Registries.DIMENSION, Identifier.parse(entry.dimension())));
    }

    private void show(Entry entry, ServerLevel world) {
        // 区块标记不是库存来源；崩溃前未保存的模型可从权威目录恢复。
        world.setBlock(entry.pos(), GroundBags.BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        if (world.getBlockEntity(entry.pos()) instanceof GroundBagBlockEntity bag) {
            bag.appearance(
                    entry.id(),
                    ContainerItems.definition(entry.stack()).id().toString(),
                    entry.yaw());
        }
    }

    private void reconcile(ServerLevel world, LevelChunk chunk) {
        for (var entity : List.copyOf(chunk.getBlockEntities().values())) {
            if (entity instanceof GroundBagBlockEntity bag) {
                Entry entry = entries.get(bag.container());
                if (entry == null
                        || !entry.pos().equals(bag.getBlockPos())
                        || !entry.dimension()
                                .equals(world.dimension().identifier().toString())) {
                    world.setBlock(
                            bag.getBlockPos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        for (Entry entry : entries.values()) {
            if (entry.dimension().equals(world.dimension().identifier().toString())
                    && ChunkPos.containing(entry.pos()).equals(chunk.getPos())) {
                show(entry, world);
            }
        }
    }

    private void reconcileLoadedChunks() {
        // 正常 tick 开始时加载流程已退出；只处理仍已加载的区块，不为背包强制加载世界。
        var pending = new HashMap<>(loadedChunks);
        loadedChunks.clear();
        for (var entry : pending.entrySet()) {
            ServerLevel world = entry.getKey();
            for (ChunkPos pos : entry.getValue()) {
                if (world.hasChunk(pos.x(), pos.z())) {
                    reconcile(world, world.getChunk(pos.x(), pos.z()));
                }
            }
        }
    }

    private CompoundTag write(Map<String, Entry> values) {
        // 物品组件可能引用附魔等数据驱动注册表，必须带服务器注册表上下文编码。
        var ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var root = new CompoundTag();
        root.putInt("version", SAVE_VERSION);
        var list = new ListTag();
        for (Entry entry : values.values()) {
            var nbt = new CompoundTag();
            nbt.putString("id", entry.id());
            nbt.putString("dimension", entry.dimension());
            nbt.putLong("pos", entry.pos().asLong());
            nbt.putFloat("yaw", entry.yaw());
            nbt.putLong("revision", entry.revision());
            nbt.store("stack", ItemStack.CODEC, ops, entry.stack());
            list.add(nbt);
        }
        root.put("bags", list);
        return root;
    }

    private void read(CompoundTag root) throws IOException {
        var registries = server.registryAccess();
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        var bags = root.getList("bags");
        if (root.getIntOr("version", 0) != SAVE_VERSION || bags.isEmpty()) {
            throw new IOException("不支持或损坏的地面背包存档");
        }
        for (var value : bags.get()) {
            // 26.2 的列表可混合类型，逐项要求复合标签，不能跳过损坏项。
            if (!(value instanceof CompoundTag nbt)) {
                throw new IOException("地面背包记录类型错误");
            }
            ItemStack stack = nbt.read("stack", ItemStack.CODEC, ops)
                    .orElseThrow(() -> new IOException("地面背包物品损坏"));
            String id = ContainerItems.identity(stack);
            // 完整解析内容再接受存档，禁止用损坏项覆盖为一个空包。
            StackCodec.encode(stack, registries);
            new ContainerTree(
                    registries,
                    dev.kizuna.backpack3d.service.TransferService.empty(),
                    new ArrayList<>(List.of(stack)));
            String dimension = nbt.getStringOr("dimension", "");
            BlockPos pos = BlockPos.of(nbt.getLongOr("pos", 0));
            float yaw = nbt.getFloatOr("yaw", 0);
            long revision = nbt.getLongOr("revision", 0);
            if (!id.equals(nbt.getStringOr("id", ""))
                    || entries.containsKey(id)
                    || Identifier.tryParse(dimension) == null
                    || !Float.isFinite(yaw)
                    || revision < 0) {
                throw new IOException("地面背包身份或位置记录无效");
            }
            for (Entry existing : entries.values()) {
                if (existing.dimension().equals(dimension) && existing.pos().equals(pos)) {
                    throw new IOException("同一位置存在重复地面背包");
                }
            }
            entries.put(
                    id,
                    new Entry(
                            id,
                            dimension,
                            pos,
                            yaw,
                            revision,
                            stack));
        }
    }

    /** stack 只在目录内部持有；调用者构造候选时必须 copy，位置单位为方块，yaw 单位为度。 */
    public record Entry(
            String id, String dimension, BlockPos pos, float yaw, long revision, ItemStack stack) {}
}
