package dev.kizuna.backpack3d.mixin;

import dev.kizuna.backpack3d.server.WearableEquipment;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class ArmorEquipmentMixin {
    @Inject(method = "canUseSlot", at = @At("HEAD"), cancellable = true)
    private void bs3d$canUseSlot(EquipmentSlot slot, CallbackInfoReturnable<Boolean> callback) {
        // 原版右键、菜单放入/快捷转移和发射器共用这个入口。
        if ((Object) this instanceof ServerPlayer player && WearableEquipment.blocks(player, slot)) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "setItemSlot", at = @At("HEAD"))
    private void bs3d$validateEquipment(EquipmentSlot slot, ItemStack stack, CallbackInfo callback) {
        if ((Object) this instanceof ServerPlayer player) {
            WearableEquipment.validateVanillaInsertion(player, slot, stack);
        }
    }

    @Inject(method = "createEquipmentSlotAccess", at = @At("HEAD"), cancellable = true)
    private static void bs3d$equipmentAccess(LivingEntity entity, EquipmentSlot slot,
                                            CallbackInfoReturnable<SlotAccess> callback) {
        // 原版头部 SlotAccess 允许任意物品而不问 canUseSlot；命令仍应返回失败，不能抛到写入阶段。
        if (entity instanceof ServerPlayer player && WearableEquipment.blocks(player, slot)) {
            callback.setReturnValue(SlotAccess.forEquipmentSlot(entity, slot, ItemStack::isEmpty));
        }
    }
}
