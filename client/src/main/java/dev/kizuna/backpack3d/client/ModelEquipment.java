package dev.kizuna.backpack3d.client;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.WearableItems;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Placement;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.State;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/** 模型穿脱的共享目录；客户端只选择目标，服务器决定是否能转移。 */
public final class ModelEquipment {
    private ModelEquipment() {}

    public static Placement placement(ItemStack stack) {
        // 多骨段与多层装备只提交一个声明层，不能在客户端拆开一件装备。
        var wearable = WearableItems.definition(stack);
        if (wearable != null) {
            return Placement.newBuilder().setWearableLayer(wearable.slots().getFirst().getLayer()).build();
        }
        var container = ContainerItems.definition(stack);
        if (container == null) {
            return null;
        }
        var mounts = container.mounts();
        if (mounts.isEmpty()) {
            return null;
        }
        return Placement.newBuilder().setMount(mounts.getFirst()).build();
    }

    public static List<Entry> worn(State state) {
        // 目录只收身上根装备，排除手持、地面和容器内部物品；套装按实例去重。
        var entries = new LinkedHashMap<String, Entry>();
        for (var container : state.getContainersList()) {
            if (container.getWorn()) {
                entries.put(container.getId(), new Entry(container.getId(), container.getTitle()));
            }
        }
        for (var wearable : state.getWearablesList()) {
            entries.put(wearable.getId(), new Entry(wearable.getId(), wearable.getTitle()));
        }
        return List.copyOf(entries.values());
    }

    public record Entry(String id, String title) {}
}
