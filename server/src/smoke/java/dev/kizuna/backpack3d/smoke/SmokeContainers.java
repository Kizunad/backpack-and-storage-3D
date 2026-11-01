package dev.kizuna.backpack3d.smoke;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornContainer;
import dev.kizuna.backpack3d.service.ContainerTree;
import dev.kizuna.backpack3d.service.TransferService;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;

/**
 * 验收用容器。腰挂用 Bong 灵草囊；Bong 九件没有胸前、背后都能挂的容器，前后切换改用
 * smoke_items.txtpb 登记的验收背篓。
 */
final class SmokeContainers {
    static final Identifier POUCH = Identifier.fromNamespaceAndPath("bs3d", "herb_pouch");
    static final Identifier BASKET = Identifier.fromNamespaceAndPath("bs3d_smoke", "lid_basket");
    static final Identifier COIN_BOX = Identifier.fromNamespaceAndPath("bs3d", "coin_box");

    private SmokeContainers() {}

    /** 验收玩家初始装备：腰部右前一个空灵草囊、背后一个空验收背篓，修订号为零。 */
    static SavedInventory account(HolderLookup.Provider registries) {
        return TransferService.empty().toBuilder()
                .addWorn(worn(POUCH, Mount.WAIST_RIGHT_FRONT, registries))
                .addWorn(worn(BASKET, Mount.TORSO_BACK, registries))
                .build();
    }

    private static WornContainer worn(Identifier type, Mount mount, HolderLookup.Provider registries) {
        return WornContainer.newBuilder()
                .setStackNbt(StackCodec.encode(ContainerItems.create(type), registries))
                .setMount(mount)
                .setWaistAngleDegrees(ContainerTree.defaultAngle(mount))
                .build();
    }
}
