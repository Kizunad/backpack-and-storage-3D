package dev.kizuna.backpack3d.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kizuna.backpack3d.protocol.Volumes;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

class HandlingRulesTest {
    private TagKey<Item> testTag;
    private final HandlingRules rules = new HandlingRules();
    private Map<TagKey<Item>, List<Holder<Item>>> originalTags;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void bindTestTag() {
        // 测试通过真实 ItemStack tag 匹配规则，结束时恢复注册表，避免污染其他测试。
        testTag = TagKey.create(Registries.ITEM, new ResourceLocation("test", "handling"));
        originalTags = new HashMap<>();
        BuiltInRegistries.ITEM
                .getTags()
                .forEach(
                        entry ->
                                originalTags.put(
                                        entry.getFirst(), entry.getSecond().stream().toList()));
        var tags = new HashMap<>(originalTags);
        tags.put(testTag, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.APPLE)));
        BuiltInRegistries.ITEM.bindTags(tags);
    }

    @AfterEach
    void restoreTags() {
        BuiltInRegistries.ITEM.bindTags(originalTags);
    }

    @Test
    void omittedSizeAndPriorityUseDefaults() {
        rules.onResourceManagerReload(resources(Map.of("default", rule("minecraft:block.chain.hit", ""))));
        var material = rules.material(new ItemStack(Items.APPLE));
        assertEquals("minecraft:block.chain.hit", material.sound());
        assertEquals(Volumes.UNIT, material.size());
    }

    @Test
    void priorityWinsAndEqualPrioritiesUseResourceOrder() {
        // 故意按反向插入，证明同级规则的顺序不依赖 ResourceManager 的 Map 顺序。
        var files = new LinkedHashMap<String, String>();
        files.put("z_high", rule("minecraft:block.chain.hit", ",\"priority\":10"));
        files.put("b_high", rule("minecraft:entity.item.pickup", ",\"priority\":10"));
        files.put("a_default", rule("minecraft:block.chain.hit", ""));
        rules.onResourceManagerReload(resources(files));
        assertEquals("minecraft:entity.item.pickup", rules.material(new ItemStack(Items.APPLE)).sound());
    }

    @Test
    void sizeAxesAreAccepted() {
        rules.onResourceManagerReload(
                resources(
                        Map.of("min", rule("minecraft:block.chain.hit", ",\"size\":[1,2,3]"))));
        assertEquals(Volumes.size(1, 2, 3), rules.material(new ItemStack(Items.APPLE)).size());
        rules.onResourceManagerReload(resources(Map.of("max", rule("minecraft:block.chain.hit", ""))));
    }

    @Test
    void invalidFieldsReportResourceAndField() {
        assertInvalid(rule("test:missing", ""), "sound");
        assertInvalid(rule("bad sound", ""), "sound");
        assertInvalid(rule("minecraft:block.chain.hit", ",\"size\":[0,1,1]"), "size.宽");
        assertInvalid(rule("minecraft:block.chain.hit", ",\"size\":[1,4,1]"), "size.高");
        assertInvalid(rule("minecraft:block.chain.hit", ",\"size\":[1,1,-1]"), "size.深");
        assertInvalid(rule("minecraft:block.chain.hit", ",\"size\":[1,1]"), "size");
        assertInvalid(rule("minecraft:block.chain.hit", ",\"size\":[1,1,1,1]"), "size");
        assertInvalid(
                rule("minecraft:block.chain.hit", "").replace("test:handling", "invalid tag"),
                "tag");
        assertInvalid("{\"tag\":\"test:handling\"}", "sound");
    }

    @Test
    void numericFieldsRejectStringValues() {
        assertInvalid(rule("minecraft:block.chain.hit", ",\"priority\":\"10\""), "priority");
        assertInvalid(rule("minecraft:block.chain.hit", ",\"size\":[1,\"2\",1]"), "size.高");
    }

    @Test
    void failedReloadKeepsEntirePreviousCatalog() {
        rules.onResourceManagerReload(resources(Map.of("old", rule("minecraft:block.chain.hit", ""))));
        var replacement =
                resources(
                        Map.of(
                                "a_valid", rule("minecraft:entity.item.pickup", ""),
                                "z_invalid", rule("test:missing", "")));
        assertThrows(IllegalArgumentException.class, () -> rules.onResourceManagerReload(replacement));
        assertEquals(
                "minecraft:block.chain.hit", rules.material(new ItemStack(Items.APPLE)).sound());
    }

    @Test
    void retiredDurationRejectsEntireReload() {
        // 旧字段即使为零也必须明确报废弃，且不能发布部分重载的新目录。
        rules.onResourceManagerReload(resources(Map.of("old", rule("minecraft:block.chain.hit", ""))));
        var replacement = resources(Map.of(
                "a_valid", rule("minecraft:entity.item.pickup", ""),
                "z_retired", rule("minecraft:entity.item.pickup", ",\"duration_ticks\":0")));
        var failure = assertThrows(IllegalArgumentException.class, () -> rules.onResourceManagerReload(replacement));
        assertTrue(failure.getMessage().contains("duration_ticks 已废弃"));
        assertEquals("minecraft:block.chain.hit", rules.material(new ItemStack(Items.APPLE)).sound());
    }

    @Test
    void inputFailureIncludesFileAndKeepsCause() {
        var readFailure = new IOException("模拟读取失败");
        // Resource.getReader 只需要输入流供应器，此夹具不访问资源包元数据。
        var resource =
                new Resource(
                        null,
                        () -> {
                            throw readFailure;
                        });
        var manager = new TestResources(Map.of(resourceId("broken"), resource));
        var failure = assertThrows(IllegalArgumentException.class, () -> rules.onResourceManagerReload(manager));
        assertTrue(failure.getMessage().contains(resourceId("broken").toString()));
        assertSame(readFailure, failure.getCause());
    }

    @Test
    void untaggedItemsKeepVanillaFallbacks() {
        rules.onResourceManagerReload(resources(Map.of("tagged", rule("minecraft:block.chain.hit", ""))));
        var block = rules.material(new ItemStack(Items.STONE));
        var vanillaSound =
                net.minecraft.world.level.block.Blocks.STONE
                        .defaultBlockState()
                        .getSoundType()
                        .getPlaceSound()
                        .getLocation()
                        .toString();
        assertEquals(vanillaSound, block.sound());
        assertEquals(
                "minecraft:entity.item.pickup", rules.material(new ItemStack(Items.STICK)).sound());
        assertEquals(Volumes.UNIT, block.size());
    }

    private void assertInvalid(String json, String field) {
        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> rules.onResourceManagerReload(resources(Map.of("invalid", json))));
        assertTrue(
                failure.getMessage().contains(resourceId("invalid").toString()),
                failure.getMessage());
        assertTrue(failure.getMessage().contains(field), failure.getMessage());
    }

    private static String rule(String sound, String optionalFields) {
        return "{\"tag\":\"test:handling\",\"sound\":\""
                + sound
                + "\""
                + optionalFields
                + "}";
    }

    private static ResourceLocation resourceId(String name) {
        return new ResourceLocation("test", "bs3d/handling/" + name + ".json");
    }

    private static ResourceManager resources(Map<String, String> files) {
        // 使用内存资源走公开 reload 入口，测试不依赖私有解析方法。
        var resources = new LinkedHashMap<ResourceLocation, Resource>();
        files.forEach(
                (name, json) ->
                        resources.put(
                                resourceId(name),
                                new Resource(
                                        null,
                                        () ->
                                                new ByteArrayInputStream(
                                                        json.getBytes(StandardCharsets.UTF_8)))));
        return new TestResources(resources);
    }

    private record TestResources(Map<ResourceLocation, Resource> files) implements ResourceManager {
        @Override
        public Map<ResourceLocation, Resource> listResources(
                String prefix, Predicate<ResourceLocation> filter) {
            var found = new LinkedHashMap<ResourceLocation, Resource>();
            files.forEach(
                    (id, resource) -> {
                        if (id.getPath().startsWith(prefix + "/") && filter.test(id)) {
                            found.put(id, resource);
                        }
                    });
            return found;
        }

        @Override
        public Optional<Resource> getResource(ResourceLocation id) {
            return Optional.ofNullable(files.get(id));
        }

        @Override
        public Set<String> getNamespaces() {
            return Set.of("test");
        }

        @Override
        public List<Resource> getResourceStack(ResourceLocation id) {
            return getResource(id).stream().toList();
        }

        @Override
        public Stream<PackResources> listPacks() {
            return Stream.empty();
        }

        @Override
        public Map<ResourceLocation, List<Resource>> listResourceStacks(
                String prefix, Predicate<ResourceLocation> filter) {
            throw new UnsupportedOperationException("规则加载不使用资源包堆叠");
        }
    }
}
