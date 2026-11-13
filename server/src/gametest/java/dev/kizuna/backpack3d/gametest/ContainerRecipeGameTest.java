package dev.kizuna.backpack3d.gametest;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.advancements.triggers.Criterion;
import net.minecraft.advancements.triggers.InventoryChangeTrigger;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;

/**
 * 十件收纳的生存来源：每件都有一份原版工作台配方，拿到主材料时进配方书。
 *
 * <p>配方和解锁进度都从真服务器加载的数据包里取，主材料读自进度本身，改料只改 JSON。</p>
 */
public final class ContainerRecipeGameTest {
    private static final String NAMESPACE = "bs3d";
    private static final String ADVANCEMENT_FOLDER = "recipes/tools/";
    private static final int CONTAINER_COUNT = 10;
    private static final int GRID_SIDE = 3;

    @GameTest
    public void everyContainerCraftsFromVanillaMaterialsAndUnlocksOnItsMainMaterial(GameTestHelper context) {
        // 每件：工作台按配方摆料选中的就是这份配方并产出本物品；新玩家拿到主材料后配方书里出现它。
        List<Identifier> items = items();
        context.assertTrue(items.size() == CONTAINER_COUNT, "收纳物品数量不符：" + items);
        for (Identifier item : items) {
            ResourceKey<Recipe<?>> recipe = ResourceKey.create(Registries.RECIPE, item);
            assertCrafts(context, recipe, item);
            assertUnlocks(context, recipe);
        }
        context.succeed();
    }

    private static List<Identifier> items() {
        // 本模组登记的全部物品；地上背包只是方块，不算物品。
        List<Identifier> items = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            if (id.getNamespace().equals(NAMESPACE)) {
                items.add(id);
            }
        }
        return items;
    }

    private static void assertCrafts(GameTestHelper context, ResourceKey<Recipe<?>> recipe, Identifier item) {
        // 用配方自己每格的第一种材料摆满工作台：原版选中的必须是这份配方，不能被原版配方抢走。
        var level = context.getLevel();
        var manager = level.getServer().getRecipeManager();
        RecipeHolder<?> declared = manager.byKey(recipe).orElseThrow(() -> new IllegalStateException("没有配方：" + item));
        CraftingInput grid = grid(declared.value());
        RecipeHolder<CraftingRecipe> chosen = manager.getRecipeFor(RecipeType.CRAFTING, grid, level)
                .orElseThrow(() -> new IllegalStateException("工作台摆好料也合不出：" + item));
        context.assertTrue(chosen.id().equals(recipe), item + " 的料被别的配方选中：" + chosen.id());
        ItemStack result = chosen.value().assemble(grid);
        context.assertTrue(BuiltInRegistries.ITEM.getKey(result.getItem()).equals(item), item + " 合出了 " + result);
    }

    private static CraftingInput grid(Recipe<?> recipe) {
        // 有序配方照原形状摆；无序配方把各样材料依次排进三乘三格子。
        if (recipe instanceof ShapedRecipe shaped) {
            List<ItemStack> slots = shaped.getIngredients().stream()
                    .map(ingredient -> ingredient.map(ContainerRecipeGameTest::firstItem).orElse(ItemStack.EMPTY))
                    .toList();
            return CraftingInput.of(shaped.getWidth(), shaped.getHeight(), slots);
        }
        List<ItemStack> slots = new ArrayList<>(recipe.placementInfo().ingredients().stream()
                .map(ContainerRecipeGameTest::firstItem).toList());
        while (slots.size() < GRID_SIDE * GRID_SIDE) {
            slots.add(ItemStack.EMPTY);
        }
        return CraftingInput.of(GRID_SIDE, GRID_SIDE, slots);
    }

    private static ItemStack firstItem(Ingredient ingredient) {
        // 标签材料取标签里的第一种，比如任意羊毛取白色羊毛。
        Holder<Item> item = ingredient.items().findFirst().orElseThrow(() -> new IllegalStateException("空材料"));
        return new ItemStack(item);
    }

    private static void assertUnlocks(GameTestHelper context, ResourceKey<Recipe<?>> recipe) {
        // 照原版 recipes/ 进度：一个从没见过这份配方的新玩家，背包里进了主材料就解锁。
        MinecraftServer server = context.getLevel().getServer();
        Identifier id = recipe.identifier();
        AdvancementHolder advancement = server.getAdvancements().get(id.withPrefix(ADVANCEMENT_FOLDER));
        context.assertTrue(advancement != null, "没有解锁进度：" + id);
        context.assertTrue(advancement.value().rewards().recipes().contains(recipe), id + " 的进度不发这份配方");
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        try {
            context.assertTrue(!player.getRecipeBook().contains(recipe), "新玩家一开始就有配方：" + id);
            player.getInventory().add(new ItemStack(mainMaterial(advancement)));
            player.inventoryMenu.broadcastChanges();
            context.assertTrue(player.getRecipeBook().contains(recipe), "拿到主材料后配方书里没有：" + id);
        } finally {
            server.getPlayerList().remove(player);
        }
    }

    private static Holder<Item> mainMaterial(AdvancementHolder advancement) {
        // 解锁进度里「背包进了某物」那一条写的材料。
        for (Criterion<?> criterion : advancement.value().criteria().values()) {
            if (criterion.trigger() == CriteriaTriggers.INVENTORY_CHANGED) {
                var instance = (InventoryChangeTrigger.TriggerInstance) criterion.triggerInstance();
                return instance.items().getFirst().items().orElseThrow().get(0);
            }
        }
        throw new IllegalStateException("解锁进度没有写主材料：" + advancement.id());
    }
}
