package dev.kizuna.backpack3d.mixin;

import dev.kizuna.backpack3d.server.WearableEquipment;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CreativeArmorMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;hasInfiniteMaterials()Z"), cancellable = true)
    private void bs3d$rejectOccupiedArmor(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo callback) {
        // 原版创造包跳过 mayPlace；这里已过线程切换，拒绝时仅重发权威库存，不改来物或光标。
        int index = packet.slotNum();
        if (packet.itemStack().isEmpty() || index < 0 || index >= player.inventoryMenu.slots.size()) {
            return;
        }
        var menuSlot = player.inventoryMenu.getSlot(index);
        var equipment = Inventory.EQUIPMENT_SLOT_MAPPING.get(menuSlot.getContainerSlot());
        if (menuSlot.container == player.getInventory() && equipment != null
                && WearableEquipment.blocks(player, equipment)) {
            player.inventoryMenu.sendAllDataToRemote();
            callback.cancel();
        }
    }
}
