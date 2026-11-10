package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kizuna.backpack3d.client.mixin.SpecialModelRenderersAccess;

import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * 放置模型在手中、物品栏和掉落物上的绘制。
 *
 * <p>物品定义 {@code assets/<ns>/items/<id>.json} 用 {@code minecraft:special} 引用 {@code bs3d:placed}，
 * 字段 {@code model} 是 {@link PlacedModels#register} 登记的 ID；{@code base} 模型提供各视角的显示变换。
 * 绘制与方块里朝北放置时相同，所以物品看到的是模型正面。
 */
final class PlacedItemModels implements SpecialModelRenderer<PlacedModels.Placed> {
    static final Identifier TYPE = Identifier.fromNamespaceAndPath("bs3d", "placed");
    private static final MapCodec<Unbaked> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Identifier.CODEC.fieldOf("model").forGetter(Unbaked::model))
            .apply(instance, Unbaked::new));
    private final Identifier model;

    private PlacedItemModels(Identifier model) {
        this.model = model;
    }

    static void register() {
        // 与 bs3d:container 同样经访问器写进原版类型表，必须在首次资源加载解析 items/ 定义之前注册。
        SpecialModelRenderersAccess.idMapper().put(TYPE, CODEC);
    }

    @Override
    public PlacedModels.Placed extractArgument(ItemStack stack) {
        return PlacedModels.find(model);
    }

    @Override
    public void submit(
            PlacedModels.Placed placed,
            PoseStack matrices,
            SubmitNodeCollector collector,
            int light,
            int overlay,
            boolean hasFoil,
            int outlineColor) {
        // 原版物品局部系与方块模型同为 0 到 1 的一格，放法与方块朝北时一致。
        if (placed == null) {
            return;
        }
        matrices.pushPose();
        PlacedModels.place(matrices, placed, Direction.NORTH);
        placed.asset().render(matrices, collector, 0, null, light, overlay);
        matrices.popPose();
    }

    @Override
    public void getExtents(Consumer<Vector3fc> output) {
        // 烘焙时模型可能还没加载，按一格方块给出包围盒。
        for (var corner : ModelAsset.vertices(new Vector3f(0), new Vector3f(1))) {
            output.accept(corner);
        }
    }

    record Unbaked(Identifier model) implements SpecialModelRenderer.Unbaked<PlacedModels.Placed> {
        @Override
        public SpecialModelRenderer<PlacedModels.Placed> bake(BakingContext context) {
            return new PlacedItemModels(model);
        }

        @Override
        public MapCodec<Unbaked> type() {
            return CODEC;
        }
    }
}
