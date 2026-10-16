package dev.kizuna.backpack3d.item;

import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.registry.pb.ItemRegistryProto.ContainerItem;
import dev.kizuna.backpack3d.registry.pb.ItemRegistryProto.ItemEntry;
import dev.kizuna.backpack3d.registry.pb.ItemRegistryProto.ItemRegistry;
import dev.kizuna.backpack3d.registry.pb.ItemRegistryProto.PlainItem;
import com.google.protobuf.UnknownFieldSet;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ItemRegistrationsTest {
    @TempDir Path directory;
    private static final Identifier WEIGHT = new Identifier("bs3d_weights", "weight");

    @BeforeAll
    static void bootstrap() {
        // 与附属一样在注册表冻结前登记，使用真实物品注册表验证两种条目。
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        ContainerItems.initialize();
    }

    @Test
    void textRegistryRegistersPlainAndContainerItems() {
        var items = ItemRegistrations.registerText("test:text", """
                version: 1
                items {
                  id: "test_botany:end_seed"
                  description_translation_key: "item.test_botany.end_seed.description"
                  creative_tab: "minecraft:natural"
                  optional_fields { id: "bs3d_weights:weight" json: '{"grams": 3}' }
                  plain { max_count: 16 }
                }
                items {
                  id: "test_botany:seed_pouch"
                  container {
                    title: "种子袋"
                    model: "test_botany:seed_pouch"
                    inside { width: 1 height: 1 depth: 2 }
                    outside { width: 1 height: 2 depth: 2 }
                    mounts: WAIST_RIGHT_FRONT
                    mounts: WAIST_LEFT_FRONT
                  }
                }
                """);
        assertEquals(2, items.size());

        var seed = new ItemStack(Registries.ITEM.get(new Identifier("test_botany", "end_seed")));
        assertEquals(16, seed.getMaxCount());
        assertNull(ContainerItems.definition(seed));
        var metadata = ItemMetadata.of(seed);
        assertEquals("item.test_botany.end_seed.description", metadata.descriptionTranslationKey());
        assertEquals(3, metadata.optional(WEIGHT).orElseThrow().get("grams").getAsInt());

        var pouch = ContainerItems.create(new Identifier("test_botany", "seed_pouch"));
        var definition = ContainerItems.definition(pouch);
        assertEquals(Volumes.size(1, 1, 2), definition.inside());
        assertEquals(List.of(Mount.WAIST_RIGHT_FRONT, Mount.WAIST_LEFT_FRONT), definition.mounts());
        assertEquals("test_botany:seed_pouch", definition.model());
    }

    @Test
    void binaryRegistryUsesDefaultStackSize() {
        var registry = ItemRegistry.newBuilder()
                .setVersion(ItemRegistrations.FORMAT_VERSION)
                .addItems(ItemEntry.newBuilder()
                        .setId("test_binary:end_soil")
                        .setPlain(PlainItem.getDefaultInstance()))
                .build();
        ItemRegistrations.registerBinary("test:binary", registry.toByteArray());
        var stack = new ItemStack(Registries.ITEM.get(new Identifier("test_binary", "end_soil")));
        assertEquals(ItemRegistrations.MAX_STACK_COUNT, stack.getMaxCount());
        assertEquals("", ItemMetadata.of(stack).descriptionTranslationKey());
    }

    @Test
    void invalidEntryRegistersNothing() {
        // 第二个条目非法时，第一个合法条目也不能进入注册表。
        var registry = ItemRegistry.newBuilder()
                .setVersion(ItemRegistrations.FORMAT_VERSION)
                .addItems(plain("test_atomic:valid"))
                .addItems(ItemEntry.newBuilder()
                        .setId("test_atomic:broken")
                        .setContainer(ContainerItem.newBuilder()
                                .setTitle("坏容器")
                                .setModel("test_atomic:broken")
                                .setInside(Volumes.size(0, 1, 1))
                                .setOutside(Volumes.UNIT)))
                .build();
        var error = assertThrows(IllegalArgumentException.class,
                () -> ItemRegistrations.register("test:atomic", registry));
        assertTrue(error.getMessage().contains("items[1]"));
        assertFalse(Registries.ITEM.containsId(new Identifier("test_atomic", "valid")));
    }

    @Test
    void rejectsVersionDuplicatesAndExistingIds() {
        assertThrows(IllegalArgumentException.class, () -> ItemRegistrations.register("test:version",
                ItemRegistry.newBuilder().addItems(plain("test_version:item")).build()));
        assertThrows(IllegalArgumentException.class, () -> ItemRegistrations.register("test:duplicate",
                registry(plain("test_duplicate:item"), plain("test_duplicate:item"))));
        assertThrows(IllegalArgumentException.class, () -> ItemRegistrations.register("test:existing",
                registry(plain("bs3d:grass_pouch"))));
        assertThrows(IllegalArgumentException.class, () -> ItemRegistrations.register("test:vanilla",
                registry(plain("minecraft:end_seed"))));
        assertThrows(IllegalArgumentException.class, () -> ItemRegistrations.register("test:short",
                registry(plain("end_seed"))));
        assertThrows(IllegalArgumentException.class, () -> ItemRegistrations.register("test:kind",
                registry(ItemEntry.newBuilder().setId("test_kind:item").build())));
    }

    @Test
    void rejectsUnknownFieldsInvalidJsonAndMounts() {
        assertThrows(IllegalArgumentException.class, () -> ItemRegistrations.registerText(
                "test:unknown_text", "version: 1\nitems { id: \"test_unknown:a\" plain { size: 2 } }"));
        var unknown = ItemRegistry.newBuilder()
                .setVersion(ItemRegistrations.FORMAT_VERSION)
                .addItems(plain("test_unknown:b").toBuilder().setUnknownFields(UnknownFieldSet.newBuilder()
                        .mergeVarintField(99, 1).build()))
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> ItemRegistrations.registerBinary("test:unknown_binary", unknown.toByteArray()));
        assertThrows(IllegalArgumentException.class, () -> ItemRegistrations.registerText("test:json", """
                version: 1
                items { id: "test_json:a" optional_fields { id: "x:y" json: "[1]" } plain {} }
                """));
        assertThrows(IllegalArgumentException.class, () -> ItemRegistrations.registerText("test:mount", """
                version: 1
                items {
                  id: "test_mount:a"
                  container {
                    title: "袋" model: "test_mount:a"
                    inside { width: 1 height: 1 depth: 1 } outside { width: 1 height: 1 depth: 1 }
                    mounts: MOUNT_DEFAULT
                  }
                }
                """));
        assertFalse(Registries.ITEM.containsId(new Identifier("test_json", "a")));
    }

    @Test
    void manifestInZipBundlesModelAssetRelativeToManifest() throws Exception {
        // 生产环境的模组清单位于 JAR 文件系统内，路径必须按该文件系统解析。
        Path jar = directory.resolve("addon.jar");
        try (var zip = FileSystems.newFileSystem(jar, Map.of("create", "true"))) {
            Files.createDirectories(zip.getPath("bs3d/models"));
            Files.createDirectories(zip.getPath("bs3d/sounds"));
            Files.writeString(zip.getPath("bs3d/models/pouch.bbmodel"), "{}");
            Files.writeString(zip.getPath("bs3d/sounds/open.ogg"), "ogg");
            Files.writeString(zip.getPath("bs3d/items.txtpb"), containerManifest(
                    "test_zip:pouch", "file: \"models/pouch.bbmodel\" close_animation: \"\""
                            + " sounds { open: \"sounds/open.ogg\" }"));
            ItemRegistrations.registerManifest("test:zip", zip.getPath("bs3d/items.txtpb"));
        }
        var bundled = ItemRegistrations.bundledModels().stream()
                .filter(model -> model.model().equals(new Identifier("test_zip", "pouch")))
                .findFirst()
                .orElseThrow();
        assertEquals("/bs3d/models/pouch.bbmodel", bundled.file().toString());
        assertNull(bundled.openAnimation());
        assertEquals("", bundled.closeAnimation());
        assertEquals("/bs3d/sounds/open.ogg", bundled.sounds().get(ContainerSounds.Action.OPEN).toString());
        assertTrue(Registries.ITEM.containsId(new Identifier("test_zip", "pouch")));
    }

    @Test
    void invalidModelAssetsRegisterNothing() throws Exception {
        Path manifest = directory.resolve("bs3d/items.txtpb");
        Files.createDirectories(manifest.getParent());
        Files.writeString(directory.resolve("outside.bbmodel"), "{}");
        for (String asset : new String[] {
                "file: \"models/missing.bbmodel\"",
                "file: \"../outside.bbmodel\"",
                "file: \"/tmp/x.bbmodel\"",
                "file: \"models/pouch.json\""}) {
            Files.writeString(manifest, containerManifest("test_asset:bad", asset));
            assertThrows(IllegalArgumentException.class,
                    () -> ItemRegistrations.registerManifest("test:asset", manifest));
        }
        // 不带清单目录的 Java 消息入口不能声明资产。
        assertThrows(IllegalArgumentException.class, () -> ItemRegistrations.registerText(
                "test:asset_text", containerManifest("test_asset:bad", "file: \"a.bbmodel\"")));
        assertFalse(Registries.ITEM.containsId(new Identifier("test_asset", "bad")));
    }

    @Test
    void sameModelAssetCannotBeBundledTwice() throws Exception {
        Path manifest = directory.resolve("twice/items.txtpb");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest.resolveSibling("m.bbmodel"), "{}");
        Files.writeString(manifest, containerManifest("test_twice:a", "file: \"m.bbmodel\"", "test_twice:shared"));
        ItemRegistrations.registerManifest("test:twice_a", manifest);
        Files.writeString(manifest, containerManifest("test_twice:b", "file: \"m.bbmodel\"", "test_twice:shared"));
        assertThrows(IllegalArgumentException.class,
                () -> ItemRegistrations.registerManifest("test:twice_b", manifest));
        assertFalse(Registries.ITEM.containsId(new Identifier("test_twice", "b")));
    }

    private static String containerManifest(String id, String asset) {
        return containerManifest(id, asset, id);
    }

    private static String containerManifest(String id, String asset, String model) {
        return "version: 1\nitems { id: \"" + id + "\" container { title: \"袋\" model: \"" + model + "\""
                + " inside { width: 1 height: 1 depth: 1 } outside { width: 1 height: 1 depth: 1 }"
                + " asset { " + asset + " } } }";
    }

    private static ItemEntry plain(String id) {
        return ItemEntry.newBuilder().setId(id).setPlain(PlainItem.getDefaultInstance()).build();
    }

    private static ItemRegistry registry(ItemEntry... entries) {
        return ItemRegistry.newBuilder()
                .setVersion(ItemRegistrations.FORMAT_VERSION)
                .addAllItems(List.of(entries))
                .build();
    }
}
