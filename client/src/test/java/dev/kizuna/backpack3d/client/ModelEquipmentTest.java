package dev.kizuna.backpack3d.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Container;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.State;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Wearable;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WearableSlot;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelEquipmentTest {
    @Test
    void listsAllMountedRootsButExcludesGroundInventoryAndNestedContainers() {
        // 根级穿戴容器和成组装备各列一次，嵌套及地面容器不属于卸装目标。
        var back = wornContainer("back", "背篓");
        var belt = wornContainer("belt", "腰带");
        var ground = Container.newBuilder()
                .setId("ground")
                .setGrounded(true /* grounded */)
                .build();
        var packed = Container.newBuilder().setId("nested").build();
        var left = WearableSlot.newBuilder()
                .setLayer("left_wrist")
                .setBone("l_forearm")
                .build();
        var right = WearableSlot.newBuilder()
                .setLayer("right_wrist")
                .setBone("r_forearm")
                .build();
        var pair = Wearable.newBuilder()
                .setId("pair")
                .setTitle("护腕")
                .addSlots(left)
                .addSlots(right)
                .build();
        var state = State.newBuilder()
                .addAllContainers(List.of(back, belt, ground, packed))
                .addWearables(pair)
                .build();
        assertEquals(List.of("back", "belt", "pair"),
                ModelEquipment.worn(state).stream().map(ModelEquipment.Entry::id).toList());
        var removed = state.toBuilder().clearWearables().removeContainers(0).build();
        assertEquals(List.of(new ModelEquipment.Entry("belt", "腰带")), ModelEquipment.worn(removed));
    }

    private static Container wornContainer(String id, String title) {
        return Container.newBuilder()
                .setId(id)
                .setTitle(title)
                .setWorn(true /* worn */)
                .build();
    }
}
