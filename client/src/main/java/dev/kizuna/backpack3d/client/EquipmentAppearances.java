package dev.kizuna.backpack3d.client;

import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.EquipmentAppearance;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 连接内的公开外观缓存；实体尚未生成时也可收包，绘制时再核对实体与维度。 */
public final class EquipmentAppearances {
    private final Map<UUID, EquipmentAppearance> players = new HashMap<>();

    public void accept(EquipmentAppearance appearance) {
        // 空 models 是卸装快照；removed 才表示不再跟踪这个玩家。
        UUID player = UUID.fromString(appearance.getPlayerUuid());
        if (appearance.getRemoved()) {
            players.remove(player);
        } else {
            players.put(player, appearance);
        }
    }

    public List<WornModel> models(UUID player, int entityId, String dimension) {
        var appearance = players.get(player);
        // 原版实体 ID 会复用，切维度/重生时也不能将旧外观套给新实体。
        if (appearance == null
                || appearance.getEntityId() != entityId
                || !appearance.getDimension().equals(dimension)) {
            return List.of();
        }
        return appearance.getModelsList();
    }

    public void clear() {
        players.clear();
    }
}
