package dev.kizuna.backpack3d.mixin;

import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 宿主快捷取回复用原版槽位插入规则，包括其他附属对槽位的限制。 */
@Mixin(ScreenHandler.class)
public interface ScreenHandlerAccess {
    @Invoker("insertItem")
    boolean bs3d$insertItem(ItemStack stack, int start, int end, boolean fromLast);
}
