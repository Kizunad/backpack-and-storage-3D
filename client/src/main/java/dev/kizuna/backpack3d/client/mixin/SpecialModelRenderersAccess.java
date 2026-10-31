package dev.kizuna.backpack3d.client.mixin;

import com.mojang.serialization.MapCodec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;

/** 原版特殊物品渲染器的类型表是私有的，Fabric 0.161 也没有注册入口，只能经访问器加入 bs3d:container。 */
@Mixin(SpecialModelRenderers.class)
public interface SpecialModelRenderersAccess {
    @Accessor("ID_MAPPER")
    static ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends SpecialModelRenderer.Unbaked<?>>> idMapper() {
        throw new AssertionError("由 Mixin 生成");
    }
}
