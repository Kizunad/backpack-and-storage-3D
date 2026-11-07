package dev.kizuna.backpack3d.mixin;

import dev.kizuna.backpack3d.server.WearableEquipment;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Inventory.class)
public abstract class ArmorInventoryMixin {
    @Shadow @Final public Player player;

    @Inject(method = "setItem", at = @At("HEAD"))
    private void bs3d$validateEquipment(int index, ItemStack stack, CallbackInfo callback) {
        // Inventory.setItem 直接写 EntityEquipment，不经过 LivingEntity.setItemSlot。
        var equipment = Inventory.EQUIPMENT_SLOT_MAPPING.get(index);
        if (equipment != null && player instanceof ServerPlayer serverPlayer) {
            WearableEquipment.validateVanillaInsertion(serverPlayer, equipment, stack);
        }
    }
}
