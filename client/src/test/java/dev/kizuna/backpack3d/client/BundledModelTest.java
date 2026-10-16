package dev.kizuna.backpack3d.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.backpack3d.item.ItemRegistrations.BundledModel;

import net.minecraft.util.Identifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

class BundledModelTest {
    @TempDir Path directory;

    @Test
    void addonJarModelLoadsGeometryTexturesAndAnimations() throws Exception {
        // 与生产一致：模型位于附属 JAR 的归档文件系统内，按清单目录解析。
        Path jar = directory.resolve("addon.jar");
        try (var zip = FileSystems.newFileSystem(jar, Map.of("create", "true"));
                var source =
                        BundledModelTest.class.getResourceAsStream(
                                "/bs3d/defaults/models/GrassPouch.bbmodel")) {
            Path root = zip.getPath("/bs3d");
            Files.createDirectories(root.resolve("models"));
            Path file = root.resolve("models/pouch.bbmodel");
            Files.copy(source, file);
            var bundled =
                    new BundledModel(
                            new Identifier("example", "pouch"),
                            "test",
                            root,
                            file,
                            null,
                            null,
                            Map.of());
            BbModel model = BbModel.read(root, ModelConfig.bundled(bundled));
            assertFalse(model.cubes().isEmpty());
            assertEquals(1, model.textures().size());
            assertTrue(
                    model.textures()
                            .containsKey(new Identifier("bs3d", "imports/example/pouch/0.png")));
            assertNotNull(model.open());
            assertNotNull(model.close());
        }
    }

    @Test
    void archivePathsCannotEscapeManifestDirectory() throws Exception {
        Path jar = directory.resolve("escape.jar");
        try (var zip = FileSystems.newFileSystem(jar, Map.of("create", "true"))) {
            Path root = zip.getPath("/bs3d");
            Files.createDirectories(root);
            for (String value : new String[] {"../other.bbmodel", "/x.bbmodel", "a\\b.bbmodel"}) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ModelConfig.localPath(root, value, ".bbmodel"));
            }
            assertEquals(
                    root.resolve("models/a.bbmodel"),
                    ModelConfig.localPath(root, "models/a.bbmodel", ".bbmodel"));
        }
    }
}
