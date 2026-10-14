package dev.kizuna.backpack3d.server;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.Messages;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.EquipmentAppearance;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornModel;
import dev.kizuna.inventoryui.server.ServerConnection;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

/** 每条观察者连接只缓存其能看见的玩家外观；库存内容始终留在本人会话内。 */
final class WornEquipmentSync {
    private final ServerConnection<ServerPlayerEntity> connection;
    private final Map<UUID, Observed> known = new HashMap<>();

    WornEquipmentSync(ServerConnection<ServerPlayerEntity> connection) {
        this.connection = connection;
    }

    void tick() {
        // 握手未结束时禁止读取连接上下文；世界外观不要求玩家先打开背包 UI。
        if (!connection.supports(Messages.APPEARANCE)) {
            return;
        }
        ServerPlayerEntity viewer = connection.context();
        var visible = new HashSet<UUID>();
        for (ServerPlayerEntity player : PlayerLookup.world(viewer.getServerWorld())) {
            if (player != viewer && !PlayerLookup.tracking(player).contains(viewer)) {
                continue;
            }
            visible.add(player.getUuid());
            update(player);
        }

        // 离开距离、维度或服务器都撤销这位观察者的缓存，避免重新出现时沿用旧挂点。
        var entries = known.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (!visible.contains(entry.getKey())) {
                connection.send(
                        Messages.APPEARANCE,
                        entry.getValue().appearance().toBuilder()
                                .clearModels()
                                .setRemoved(true)
                                .build());
                entries.remove();
            }
        }
    }

    private void update(ServerPlayerEntity player) {
        SavedInventory saved = ((PlayerStorage) player).bs3d$getInventory();
        Observed previous = known.get(player.getUuid());
        String dimension = player.getWorld().getRegistryKey().getValue().toString();
        // protobuf 存档不可变；未变更时不反复解码 NBT，重生实体仍会触发完整同步。
        if (previous != null
                && previous.saved() == saved
                && previous.appearance().getEntityId() == player.getId()
                && previous.appearance().getDimension().equals(dimension)) {
            return;
        }
        EquipmentAppearance next = appearance(player.getUuid(), player.getId(), dimension, saved);
        if (previous == null || !previous.appearance().equals(next)) {
            connection.send(Messages.APPEARANCE, next);
        }
        known.put(player.getUuid(), new Observed(saved, next));
    }

    static EquipmentAppearance appearance(
            UUID player, int entityId, String dimension, SavedInventory saved) {
        var appearance =
                EquipmentAppearance.newBuilder()
                        .setPlayerUuid(player.toString())
                        .setEntityId(entityId)
                        .setDimension(dimension);
        for (var worn : saved.getWornList()) {
            // 只投影身体上的根容器；收纳在其中的草包、背篓不能重复穿到身上。
            var type = ContainerItems.definition(StackCodec.decode(worn.getStackNbt()));
            if (type != null && !type.mounts().isEmpty()) {
                appearance.addModels(
                        WornModel.newBuilder()
                                .setItemType(type.id().toString())
                                .setMount(worn.getMount())
                                .setWaistAngleDegrees(worn.getWaistAngleDegrees()));
            }
        }
        return appearance.build();
    }

    /** saved 是不可变存档引用；appearance 只保留可公开的装备外观。 */
    private record Observed(SavedInventory saved, EquipmentAppearance appearance) {}
}
