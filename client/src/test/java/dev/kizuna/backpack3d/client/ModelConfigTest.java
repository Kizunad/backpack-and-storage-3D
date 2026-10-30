package dev.kizuna.backpack3d.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.backpack3d.item.ContainerSounds.Action;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

class ModelConfigTest {
    @TempDir Path directory;

    @Test
    void onlyRegisteredFilesAreReturnedAndStrayModelsAreNotParsed() throws Exception {
        Files.writeString(directory.resolve("registered.bbmodel"), "parsed later");
        Files.writeString(directory.resolve("stray.bbmodel"), "not even json");
        config(
                """
                version = 1
                [[models]]
                id = "example:bag"
                file = "registered.bbmodel"
                [models.sounds]
                open = "sounds/open.ogg"
                place = "sounds/place.ogg"
                """);
        var warnings = new ArrayList<String>();
        var entries = ModelConfig.read(directory, warnings::add).models();
        assertEquals(1, entries.size());
        assertEquals("example:bag", entries.get(0).id().toString());
        assertNull(entries.get(0).openAnimation());
        assertEquals(
                directory.resolve("sounds/place.ogg"), entries.get(0).sounds().get(Action.PLACE));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("stray.bbmodel"));
    }

    @Test
    void duplicateIdsAndMistypedFieldsRejectTheWholeCatalog() throws Exception {
        config(
                """
                version = 1
                [[models]]
                id = "example:bag"
                file = "a.bbmodel"
                [[models]]
                id = "example:bag"
                file = "b.bbmodel"
                """);
        assertThrows(
                IllegalArgumentException.class, () -> ModelConfig.read(directory, ignored -> {}));
        config(
                """
                version = 1
                [[models]]
                id = "example:bag"
                file = "a.bbmodel"
                open_aniamtion = "open"
                """);
        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ModelConfig.read(directory, ignored -> {}));
        assertTrue(failure.getMessage().contains("open_aniamtion"));
    }

    @Test
    void emptyRegistryIsIntentionalAndDoesNotAutoDiscoverModels() throws Exception {
        config("version = 1\nmodels = []\n");
        Files.writeString(directory.resolve("bag.bbmodel"), "{}");
        var warnings = new ArrayList<String>();
        assertTrue(ModelConfig.read(directory, warnings::add).models().isEmpty());
        assertEquals(1, warnings.size());
    }

    @Test
    void legacyConfigKeepsDefaultSceneRotationKeys() throws Exception {
        // 没有 controls 表的已有配置仍然可用，默认 R/T 不依赖全局按键表。
        config("version = 1\nmodels = []\n");
        var controls = ModelConfig.read(directory, ignored -> {}).controls();
        assertEquals("key.keyboard.r", controls.yaw().getTranslationKey());
        assertEquals("key.keyboard.t", controls.pitch().getTranslationKey());
    }

    @Test
    void sceneRotationKeysCanBeConfiguredIndependently() throws Exception {
        // 只覆盖一个轴时，另一个轴保留默认键。
        config("version = 1\n[controls]\nrotate_yaw = 'key.keyboard.y'\n");
        var controls = ModelConfig.read(directory, ignored -> {}).controls();
        assertEquals("key.keyboard.y", controls.yaw().getTranslationKey());
        assertEquals("key.keyboard.t", controls.pitch().getTranslationKey());
        config("version = 1\n[controls]\nrotate_pitch = 'key.mouse.4'\n");
        var pitchControls = ModelConfig.read(directory, ignored -> {}).controls();
        assertEquals("key.keyboard.r", pitchControls.yaw().getTranslationKey());
        assertEquals("key.mouse.4", pitchControls.pitch().getTranslationKey());
    }

    @Test
    void invalidSceneRotationBindingsRejectConfig() throws Exception {
        // 未知键名和拼错字段不能生成界面无法接收的绑定。
        for (String controls : new String[] {
                "rotate_yaw = 'key.keyboard.unknown'",
                "rotate_yaw = 'not-a-key'",
                "rotate_yaw = 82",
                "rotate_ywa = 'key.keyboard.y'"
        }) {
            config("version = 1\n[controls]\n" + controls + "\n");
            assertThrows(RuntimeException.class, () -> ModelConfig.read(directory, ignored -> {}));
        }
    }

    @Test
    void traversalAbsolutePathsAndSymlinkEscapesAreRejected() throws Exception {
        // 读取本地资源也必须限定目录，不能随 bbmodel 导入编辑者机器的绝对路径。
        for (String value :
                new String[] {
                    "../escape.bbmodel",
                    "/tmp/model.bbmodel",
                    "D:/model.bbmodel",
                    "models\\bag.bbmodel"
                }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ModelConfig.localPath(directory, value, ".bbmodel"));
        }
        Path link = directory.resolve("outside");
        Files.createSymbolicLink(link, directory.getParent());
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelConfig.localPath(directory, "outside/a.bbmodel", ".bbmodel"));
    }

    @Test
    void versionAndFieldTypesAreStrict() throws Exception {
        for (String value :
                new String[] {
                    "version = 2",
                    "version = '1'",
                    "version = 1\nmodels = 'x'",
                    "version = 1\nunknown = true"
                }) {
            config(value);
            assertThrows(RuntimeException.class, () -> ModelConfig.read(directory, ignored -> {}));
        }
    }

    private void config(String value) throws Exception {
        Files.writeString(directory.resolve("config.toml"), value);
    }
}
