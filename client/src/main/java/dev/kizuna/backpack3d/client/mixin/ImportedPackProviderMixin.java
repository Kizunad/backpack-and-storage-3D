package dev.kizuna.backpack3d.client.mixin;

import dev.kizuna.backpack3d.client.ModelResources;

import net.minecraft.client.resource.DefaultClientResourcePackProvider;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.VanillaResourcePackProvider;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/** 将登记资源接入原版扫描流程，使纹理和声音在同一次重载中更新。 */
@Mixin(VanillaResourcePackProvider.class)
abstract class ImportedPackProviderMixin {
    @Inject(method = "register", at = @At("RETURN"))
    private void registerImportedPack(
            Consumer<ResourcePackProfile> consumer, CallbackInfo callback) {
        // 同名父类也服务于数据包，这里只给客户端资源管理器加入导入包。
        if ((Object) this instanceof DefaultClientResourcePackProvider) {
            ModelResources.registerPack(consumer);
        }
    }
}
