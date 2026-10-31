package dev.kizuna.backpack3d.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 宿主快捷取回复用原版槽位插入规则，包括其他附属对槽位的限制。 */
@Mixin(AbstractContainerMenu.class)
public interface ScreenHandlerAccess {
    @Invoker("moveItemStackTo")
    boolean bs3d$insertItem(ItemStack stack, int start, int end, boolean fromLast);
}
