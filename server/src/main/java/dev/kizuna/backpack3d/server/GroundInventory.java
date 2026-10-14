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
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.chunk.WorldChunk;

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
    private final Map<ServerWorld, Set<ChunkPos>> loadedChunks = new HashMap<>();
    private Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean recoveryRequired;

    private GroundInventory(MinecraftServer server) throws IOException {
        this.server = server;
        journal =
                new GroundJournal(
                        server.getSavePath(WorldSavePath.ROOT).resolve("data/bs3d"),
                        server.getSavePath(WorldSavePath.PLAYERDATA));
        journal.recover();
        if (Files.exists(journal.inventoryFile())) {
            read(NbtIo.readCompressed(journal.inventoryFile().toFile()));
        }
    }

    public static void register() {
        // 创造模式也通过收回操作移动实物，不能绕过库存事务直接破坏标记方块。
        PlayerBlockBreakEvents.BEFORE.register(
                (world, player, pos, state, entity) -> !state.isOf(GroundBags.BLOCK));
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
                        if (world != null && world.isChunkLoaded(entry.pos())) {
                            inventory.show(entry, world);
                        }
                    }
                });
        ServerLifecycleEvents.SERVER_STOPPED.register(SERVERS::remove);
        ServerChunkEvents.CHUNK_LOAD.register(
                (world, chunk) -> {
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
                    public ActionResult place(ItemUsageContext context) {
                        return get(context.getWorld().getServer()).place(context);
                    }

                    @Override
                    public ActionResult use(
                            PlayerEntity player, Hand hand, GroundBagBlockEntity bag) {
                        return get(player.getServer()).use(player, hand, bag);
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
    public ActionResult place(ItemUsageContext context) {
        var player = (ServerPlayerEntity) context.getPlayer();
        if (player == null) {
            return ActionResult.FAIL;
        }
        try {
            requireActor(player);
            var world = player.getServerWorld();
            var placement = new ItemPlacementContext(context);
            BlockPos pos = placement.getBlockPos();
            // 只在有支撑的上表面落地，不替换实体方块，也不允许隔墙或超距放置。
            if (context.getSide() != Direction.UP
                    || !player.isSneaking()
                    || !world.getBlockState(pos).canReplace(placement)
                    || !world.getBlockState(pos.down())
                            .isSideSolidFullSquare(world, pos.down(), Direction.UP)
                    || !world.canPlace(
                            GroundBags.BLOCK.getDefaultState(), pos, ShapeContext.absent())
                    || !world.isSpaceEmpty(
                            GroundBags.BLOCK
                                    .getDefaultState()
                                    .getCollisionShape(world, pos)
                                    .getBoundingBox()
                                    .offset(pos))
                    || world.isOutOfHeightLimit(pos)
                    || !world.getWorldBorder().contains(pos)
                    || !world.canPlayerModifyAt(player, pos)
                    || !world.canPlayerModifyAt(player, context.getBlockPos())
                    || player.getEyePos().squaredDistanceTo(context.getHitPos())
                            > REACH_BLOCKS * REACH_BLOCKS) {
                throw new IllegalArgumentException("请在身边有支撑的空地上放置背包");
            }
            var hit =
                    world.raycast(
                            new RaycastContext(
                                    player.getEyePos(),
                                    context.getHitPos(),
                                    RaycastContext.ShapeType.COLLIDER,
                                    RaycastContext.FluidHandling.NONE,
                                    player));
            if (hit.getType() != net.minecraft.util.hit.HitResult.Type.MISS
                    && !hit.getBlockPos().equals(context.getBlockPos())) {
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
            new ContainerTree(account.data(), new ArrayList<>(vanilla));
            String id = ContainerItems.identity(stack);
            StackCodec.encode(stack);
            if (entries.containsKey(id)) {
                throw new IllegalArgumentException("该背包身份已在地面存在");
            }
            String dimension = world.getRegistryKey().getValue().toString();
            for (Entry existing : entries.values()) {
                if (existing.dimension().equals(dimension) && existing.pos().equals(pos)) {
                    throw new IllegalArgumentException("这里已有地面背包");
                }
            }
            var entry =
                    new Entry(id, dimension, pos.toImmutable(), player.getYaw(), 0, stack.copy());
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
                    SoundCategory.BLOCKS,
                    SOUND_VOLUME,
                    SOUND_PITCH);
            return ActionResult.CONSUME;
        } catch (IOException | IllegalArgumentException invalid) {
            player.sendMessage(Text.literal(invalid.getMessage()), true);
            return ActionResult.FAIL;
        }
    }

    @Override
    public ActionResult use(PlayerEntity actor, Hand hand, GroundBagBlockEntity bag) {
        var player = (ServerPlayerEntity) actor;
        try {
            requireActor(player);
            Entry entry = requireAccess(player, bag.container());
            if (!entry.pos().equals(bag.getPos())) {
                throw new IllegalArgumentException("地面背包位置不一致");
            }
            if (player.isSneaking() && player.getStackInHand(hand).isEmpty()) {
                pickup(player, hand, entry);
            } else {
                VolumeServer.session(player).openGround(entry.id());
            }
            return ActionResult.CONSUME;
        } catch (IOException | IllegalArgumentException invalid) {
            player.sendMessage(Text.literal(invalid.getMessage()), true);
            return ActionResult.FAIL;
        }
    }

    private void pickup(ServerPlayerEntity player, Hand hand, Entry entry) throws IOException {
        if (leases.containsKey(entry.id())) {
            throw new IllegalArgumentException("这个背包正在使用，请先关闭内景");
        }
        var account = new PlayerAccount(player);
        var vanilla = account.inventory();
        vanilla.set(handSlot(player, hand), entry.stack().copy());
        var data = nextRevision(account.data());
        new ContainerTree(data, vanilla);
        var next = new LinkedHashMap<>(entries);
        next.remove(entry.id());
        commit(player, data, vanilla, next);
        player.getServerWorld()
                .setBlockState(entry.pos(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        player.getServerWorld()
                .playSound(
                        null,
                        entry.pos(),
                        ContainerSounds.event(
                                ContainerItems.definition(entry.stack()).model(), Action.PICKUP),
                        SoundCategory.BLOCKS,
                        SOUND_VOLUME,
                        SOUND_PITCH);
    }

    public Entry requireAccess(ServerPlayerEntity player, String id) {
        Entry entry = entries.get(id);
        if (entry == null
                || !entry.dimension()
                        .equals(player.getWorld().getRegistryKey().getValue().toString())
                || !player.getServerWorld().isChunkLoaded(entry.pos())
                || player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(entry.pos()))
                        > REACH_BLOCKS * REACH_BLOCKS
                || !player.getWorld().canPlayerModifyAt(player, entry.pos())
                || !(player.getWorld().getBlockEntity(entry.pos())
                        instanceof GroundBagBlockEntity bag)
                || !bag.container().equals(id)) {
            throw new IllegalArgumentException("地面背包已不可访问");
        }
        // 每次开始与完成操作都重新检查遮挡，不允许打开后穿墙继续取物。
        Vec3d target =
                player.getWorld()
                        .getBlockState(entry.pos())
                        .getOutlineShape(player.getWorld(), entry.pos())
                        .getBoundingBox()
                        .getCenter()
                        .add(Vec3d.of(entry.pos()));
        var hit =
                player.getWorld()
                        .raycast(
                                new RaycastContext(
                                        player.getEyePos(),
                                        target,
                                        RaycastContext.ShapeType.COLLIDER,
                                        RaycastContext.FluidHandling.NONE,
                                        player));
        if (!hit.getBlockPos().equals(entry.pos())) {
            throw new IllegalArgumentException("地面背包被遮挡");
        }
        return entry;
    }

    public void acquire(ServerPlayerEntity player, String id) {
        requireAccess(player, id);
        UUID owner = leases.get(id);
        if (owner != null && !owner.equals(player.getUuid())) {
            throw new IllegalArgumentException("另一位玩家正在使用这个背包");
        }
        leases.put(id, player.getUuid());
    }

    public void release(UUID player, String id) {
        leases.remove(id, player);
    }

    public Entry leased(ServerPlayerEntity player, String id) {
        if (!player.getUuid().equals(leases.get(id))) {
            throw new IllegalArgumentException("地面背包访问权已失效");
        }
        return requireAccess(player, id);
    }

    void commitContents(
            ServerPlayerEntity player,
            SavedInventory data,
            List<ItemStack> vanilla,
            Map<String, ItemStack> ground)
            throws IOException {
        var next = new LinkedHashMap<>(entries);
        for (var candidate : ground.entrySet()) {
            Entry old = leased(player, candidate.getKey());
            StackCodec.encode(candidate.getValue());
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
            ServerPlayerEntity player,
            SavedInventory data,
            List<ItemStack> vanilla,
            Map<String, Entry> next)
            throws IOException {
        var account = new PlayerAccount(player);
        try {
            journal.commit(player.getUuid(), account.snapshot(data, vanilla), write(next));
        } catch (GroundJournal.RecoveryRequiredException committed) {
            // 已过日志提交点不能回滚或继续新事务；停服后先重放日志，再允许玩家读档。
            recoveryRequired = true;
            server.stop(false);
            throw new IllegalStateException(committed.getMessage(), committed);
        }
        entries = next;
        account.applyCommitted(data, vanilla);
    }

    private static void requireActor(ServerPlayerEntity player) {
        if (!player.isAlive()
                || player.isSpectator()
                || player.hasVehicle()
                || VolumeServer.locked(player)
                || player.currentScreenHandler != player.playerScreenHandler
                || !player.playerScreenHandler.getCursorStack().isEmpty()) {
            throw new IllegalArgumentException("当前不能操作地面背包");
        }
    }

    private static int handSlot(ServerPlayerEntity player, Hand hand) {
        return hand == Hand.MAIN_HAND
                ? player.getInventory().selectedSlot
                : PlayerInventory.OFF_HAND_SLOT;
    }

    private static SavedInventory nextRevision(SavedInventory data) {
        return data.toBuilder().setRevision(Math.addExact(data.getRevision(), 1)).build();
    }

    private ServerWorld world(Entry entry) {
        return server.getWorld(
                RegistryKey.of(RegistryKeys.WORLD, new Identifier(entry.dimension())));
    }

    private void show(Entry entry, ServerWorld world) {
        // 区块标记不是库存来源；崩溃前未保存的模型可从权威目录恢复。
        world.setBlockState(entry.pos(), GroundBags.BLOCK.getDefaultState(), Block.NOTIFY_ALL);
        if (world.getBlockEntity(entry.pos()) instanceof GroundBagBlockEntity bag) {
            bag.appearance(
                    entry.id(),
                    ContainerItems.definition(entry.stack()).id().toString(),
                    entry.yaw());
        }
    }

    private void reconcile(ServerWorld world, WorldChunk chunk) {
        for (var entity : List.copyOf(chunk.getBlockEntities().values())) {
            if (entity instanceof GroundBagBlockEntity bag) {
                Entry entry = entries.get(bag.container());
                if (entry == null
                        || !entry.pos().equals(bag.getPos())
                        || !entry.dimension()
                                .equals(world.getRegistryKey().getValue().toString())) {
                    world.setBlockState(
                            bag.getPos(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        for (Entry entry : entries.values()) {
            if (entry.dimension().equals(world.getRegistryKey().getValue().toString())
                    && new net.minecraft.util.math.ChunkPos(entry.pos()).equals(chunk.getPos())) {
                show(entry, world);
            }
        }
    }

    private void reconcileLoadedChunks() {
        // 正常 tick 开始时加载流程已退出；只处理仍已加载的区块，不为背包强制加载世界。
        var pending = new HashMap<>(loadedChunks);
        loadedChunks.clear();
        for (var entry : pending.entrySet()) {
            ServerWorld world = entry.getKey();
            for (ChunkPos pos : entry.getValue()) {
                if (world.isChunkLoaded(pos.x, pos.z)) {
                    reconcile(world, world.getChunk(pos.x, pos.z));
                }
            }
        }
    }

    private NbtCompound write(Map<String, Entry> values) {
        var root = new NbtCompound();
        root.putInt("version", SAVE_VERSION);
        var list = new NbtList();
        for (Entry entry : values.values()) {
            var nbt = new NbtCompound();
            nbt.putString("id", entry.id());
            nbt.putString("dimension", entry.dimension());
            nbt.putLong("pos", entry.pos().asLong());
            nbt.putFloat("yaw", entry.yaw());
            nbt.putLong("revision", entry.revision());
            nbt.put("stack", entry.stack().writeNbt(new NbtCompound()));
            list.add(nbt);
        }
        root.put("bags", list);
        return root;
    }

    private void read(NbtCompound root) throws IOException {
        if (root.getInt("version") != SAVE_VERSION
                || !root.contains("bags", NbtElement.LIST_TYPE)) {
            throw new IOException("不支持或损坏的地面背包存档");
        }
        for (var value : root.getList("bags", NbtElement.COMPOUND_TYPE)) {
            var nbt = (NbtCompound) value;
            ItemStack stack = ItemStack.fromNbt(nbt.getCompound("stack"));
            String id = ContainerItems.identity(stack);
            // 完整解析内容再接受存档，禁止用损坏项覆盖为一个空包。
            StackCodec.encode(stack);
            new ContainerTree(
                    dev.kizuna.backpack3d.service.TransferService.empty(),
                    new ArrayList<>(List.of(stack)));
            String dimension = nbt.getString("dimension");
            BlockPos pos = BlockPos.fromLong(nbt.getLong("pos"));
            if (!id.equals(nbt.getString("id"))
                    || entries.containsKey(id)
                    || Identifier.tryParse(dimension) == null
                    || !Float.isFinite(nbt.getFloat("yaw"))
                    || nbt.getLong("revision") < 0) {
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
                            nbt.getFloat("yaw"),
                            nbt.getLong("revision"),
                            stack));
        }
    }

    /** stack 只在目录内部持有；调用者构造候选时必须 copy，位置单位为方块，yaw 单位为度。 */
    public record Entry(
            String id, String dimension, BlockPos pos, float yaw, long revision, ItemStack stack) {}
}
