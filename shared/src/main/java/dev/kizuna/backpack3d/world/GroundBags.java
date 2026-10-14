package dev.kizuna.backpack3d.world;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

/** 两端只共享方块与外观注册，权威物品操作由服务端注入。 */
public final class GroundBags {
    public static final Identifier ID = new Identifier("bs3d", "placed_bag");
    private static final float UNBREAKABLE_HARDNESS = -1;
    private static final float EXPLOSION_RESISTANCE = 3_600_000;
    public static final Block BLOCK =
            new GroundBagBlock(
                    AbstractBlock.Settings.create()
                            .strength(UNBREAKABLE_HARDNESS, EXPLOSION_RESISTANCE)
                            .nonOpaque()
                            .dynamicBounds()
                            .pistonBehavior(PistonBehavior.BLOCK)
                            .sounds(BlockSoundGroup.WOOL));
    public static BlockEntityType<GroundBagBlockEntity> ENTITY_TYPE;
    private static Actions actions;

    private GroundBags() {}

    public static void initialize() {
        // 不注册第二种背包物品；放置与收回始终使用 ContainerItems 的真实容器。
        Registry.register(Registries.BLOCK, ID, BLOCK);
        ENTITY_TYPE =
                Registry.register(
                        Registries.BLOCK_ENTITY_TYPE,
                        ID,
                        FabricBlockEntityTypeBuilder.create(GroundBagBlockEntity::new, BLOCK)
                                .build());
    }

    public static void actions(Actions serverActions) {
        actions = serverActions;
    }

    public static ActionResult place(ItemUsageContext context) {
        // 客户端只挥手，不能扣物品或创建容器身份。
        if (context.getWorld().isClient) {
            return ActionResult.SUCCESS;
        }
        return actions == null ? ActionResult.FAIL : actions.place(context);
    }

    static ActionResult use(PlayerEntity player, Hand hand, GroundBagBlockEntity bag) {
        if (player.getWorld().isClient) {
            return ActionResult.SUCCESS;
        }
        return actions == null ? ActionResult.FAIL : actions.use(player, hand, bag);
    }

    public interface Actions {
        ActionResult place(ItemUsageContext context);

        ActionResult use(PlayerEntity player, Hand hand, GroundBagBlockEntity bag);
    }
}
