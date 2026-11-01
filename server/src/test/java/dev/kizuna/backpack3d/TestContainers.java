package dev.kizuna.backpack3d;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornContainer;
import dev.kizuna.backpack3d.service.ContainerTree;
import dev.kizuna.backpack3d.service.TransferService;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;

/**
 * 测试夹具容器。腰挂袋用 Bong 灵草囊；胸前背后两用的背篓与不可穿戴的收纳箱 Bong 九件里没有，
 * 按测试专用 ID 注册，几何与 2026-11-01 移除的同名旧物品相同，单位为格。
 */
public final class TestContainers {
    public static final Identifier POUCH = Identifier.fromNamespaceAndPath("bs3d", "herb_pouch");
    public static final Identifier BASKET = Identifier.fromNamespaceAndPath("bs3d_test", "basket");
    public static final Identifier BOX = Identifier.fromNamespaceAndPath("bs3d_test", "box");
    private static boolean registered;

    private TestContainers() {}

    static void register() {
        // 与 ContainerItems.initialize 一样只登记一次；须在注册表冻结前、组件绑定前调用。
        if (registered) {
            return;
        }
        registered = true;
        ContainerItems.register(BASKET, "测试背篓", "", BASKET.toString(),
                Volumes.size(3, 3, 3), Volumes.size(3, 3, 4),
                List.of(Mount.TORSO_FRONT, Mount.TORSO_BACK), Map.of());
        ContainerItems.register(BOX, "测试收纳箱", "", BOX.toString(),
                Volumes.size(5, 3, 2), Volumes.size(5, 3, 2), List.of(), Map.of());
    }

    /** 新玩家夹具：腰部右前一个空灵草囊、背后一个空测试背篓。 */
    public static SavedInventory account() {
        return TransferService.empty().toBuilder()
                .addWorn(worn(POUCH, Mount.WAIST_RIGHT_FRONT))
                .addWorn(worn(BASKET, Mount.TORSO_BACK))
                .build();
    }

    private static WornContainer worn(Identifier type, Mount mount) {
        return WornContainer.newBuilder()
                .setStackNbt(StackCodec.encode(ContainerItems.create(type), TestRegistries.VANILLA))
                .setMount(mount)
                .setWaistAngleDegrees(ContainerTree.defaultAngle(mount))
                .build();
    }
}
