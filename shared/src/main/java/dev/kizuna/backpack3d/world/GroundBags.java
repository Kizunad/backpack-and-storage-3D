package dev.kizuna.backpack3d.world;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

/** 两端只共享方块与外观注册，权威物品操作由服务端注入。 */
public final class GroundBags {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("bs3d", "placed_bag");
    private static final float UNBREAKABLE_HARDNESS = -1;
    private static final float EXPLOSION_RESISTANCE = 3_600_000;
    public static final Block BLOCK =
            new GroundBagBlock(
                    BlockBehaviour.Properties.of()
                            .setId(ResourceKey.create(Registries.BLOCK, ID))
                            .strength(UNBREAKABLE_HARDNESS, EXPLOSION_RESISTANCE)
                            .noOcclusion()
                            .dynamicShape()
                            .pushReaction(PushReaction.BLOCK)
                            .sound(SoundType.WOOL));
    public static BlockEntityType<GroundBagBlockEntity> ENTITY_TYPE;
    private static Actions actions;

    private GroundBags() {}

    public static void initialize() {
        // 不注册第二种背包物品；放置与收回始终使用 ContainerItems 的真实容器。
        Registry.register(BuiltInRegistries.BLOCK, ID, BLOCK);
        ENTITY_TYPE =
                Registry.register(
                        BuiltInRegistries.BLOCK_ENTITY_TYPE,
                        ID,
                        FabricBlockEntityTypeBuilder.create(GroundBagBlockEntity::new, BLOCK)
                                .build());
    }

    public static void actions(Actions serverActions) {
        actions = serverActions;
    }

    public static InteractionResult place(UseOnContext context) {
        // 客户端只挥手，不能扣物品或创建容器身份。
        if (context.getLevel().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        return actions == null ? InteractionResult.FAIL : actions.place(context);
    }

    static InteractionResult use(Player player, InteractionHand hand, GroundBagBlockEntity bag) {
        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        return actions == null ? InteractionResult.FAIL : actions.use(player, hand, bag);
    }

    public interface Actions {
        InteractionResult place(UseOnContext context);

        InteractionResult use(Player player, InteractionHand hand, GroundBagBlockEntity bag);
    }
}
