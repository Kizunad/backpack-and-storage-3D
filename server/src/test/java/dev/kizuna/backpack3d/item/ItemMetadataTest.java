package dev.kizuna.backpack3d.item;

import com.google.gson.JsonObject;
import dev.kizuna.backpack3d.protocol.Volumes;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ItemMetadataTest {
    private static final ResourceLocation FIELD = new ResourceLocation("example", "details");

    @BeforeAll
    static void bootstrap() {
        // 使用真实物品注册表，确保补充元数据不会替换原版物品或生成实例 NBT。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ContainerItems.initialize();
    }

    @Test
    void copiesInputAndReturnedJson() {
        var field = new JsonObject();
        field.addProperty("value", 12);
        var nested = new JsonObject();
        nested.addProperty("label", "original");
        field.add("nested", nested);
        var optional = new HashMap<ResourceLocation, JsonObject>();
        optional.put(FIELD, field);
        var metadata = new ItemMetadata("item.example.description", optional);

        // 注册方和读取方拿到的 JSON 均不能悄悄改变后续匹配结果，包括嵌套对象。
        optional.clear();
        nested.addProperty("label", "changed");
        metadata.optional(FIELD).orElseThrow().addProperty("value", 99);
        var stored = metadata.optional(FIELD).orElseThrow();
        assertEquals(12, stored.get("value").getAsInt());
        assertEquals("original", stored.getAsJsonObject("nested").get("label").getAsString());
    }

    @Test
    void emptyOptionalIsExplicitButNullIsRejected() {
        assertTrue(new ItemMetadata("", Map.of()).optional(FIELD).isEmpty());
        assertThrows(NullPointerException.class, () -> new ItemMetadata("", null));
        assertThrows(NullPointerException.class, () -> new ItemMetadata(null, Map.of()));
    }

    @Test
    void vanillaMetadataDoesNotChangeStackOrAllowReplacement() {
        var stack = new ItemStack(Items.FLINT, 3);
        var original = stack.copy();
        ItemMetadata.register(Items.FLINT, "item.example.flint.description", Map.of());
        assertSame(Items.FLINT, BuiltInRegistries.ITEM.get(new ResourceLocation("minecraft", "flint")));
        assertTrue(ItemStack.matches(original, stack));
        assertEquals("item.example.flint.description", ItemMetadata.of(stack).descriptionTranslationKey());
        assertThrows(IllegalArgumentException.class,
                () -> ItemMetadata.register(Items.FLINT, "replacement", Map.of()));
        assertEquals("item.example.flint.description", ItemMetadata.of(stack).descriptionTranslationKey());
    }

    @Test
    void containerRegistrationIncludesDescriptionAndAddonFields() {
        var id = new ResourceLocation("test", "metadata_container");
        var payload = new JsonObject();
        payload.addProperty("value", 42);
        ContainerItems.register(id, "测试容器", "item.test.container.description", "bs3d:grass_pouch",
                Volumes.UNIT, Volumes.UNIT, List.of(), Map.of(FIELD, payload));
        var stack = ContainerItems.create(id);
        assertEquals("item.test.container.description", ItemMetadata.of(stack).descriptionTranslationKey());
        assertEquals(42, ItemMetadata.of(stack).optional(FIELD).orElseThrow().get("value").getAsInt());
        assertEquals(Volumes.UNIT, ContainerItems.definition(stack).inside());
        assertFalse(ContainerItems.identity(stack).isEmpty());
    }

    @Test
    void invalidMetadataDoesNotPartiallyRegisterContainer() {
        var id = new ResourceLocation("test", "invalid_metadata_container");
        assertThrows(NullPointerException.class, () -> ContainerItems.register(
                id, "测试容器", "", "bs3d:grass_pouch", Volumes.UNIT, Volumes.UNIT, List.of(), null));
        assertFalse(BuiltInRegistries.ITEM.containsKey(id));
    }

    @Test
    void unregisteredItemsHaveNoDescriptionOrAddonFields() {
        var metadata = ItemMetadata.of(new ItemStack(Items.DIRT));
        assertEquals("", metadata.descriptionTranslationKey());
        assertTrue(metadata.optional(FIELD).isEmpty());
    }
}
