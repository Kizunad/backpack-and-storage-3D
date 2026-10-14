package dev.kizuna.backpack3d.client;

import dev.kizuna.backpack3d.item.ContainerSounds;
import dev.kizuna.backpack3d.item.ContainerSounds.Action;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.sound.OggAudioStream;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** 只在隔离验收入口执行：真实客户端白名单重载及自定义 OGG 解码。 */
public final class ResourceImportSmoke implements ClientModInitializer {
    private static final long TIMEOUT_MILLIS = 120_000;
    private final Path directory = FabricLoader.getInstance().getConfigDir().resolve("bs3d");
    private CompletableFuture<Void> reload;
    private String original;
    private int step;
    private long started;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("bs3d.resourcesSmoke")) {
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    try {
                        tick(client);
                    } catch (Exception failure) {
                        throw new IllegalStateException("资源导入验收失败，step=" + step, failure);
                    }
                });
    }

    private void tick(MinecraftClient client) throws Exception {
        // 标题页也完成资源加载，无需连接服务器或创建玩家世界。
        if (started == 0) {
            started = System.currentTimeMillis();
        }
        require(System.currentTimeMillis() - started < TIMEOUT_MILLIS, "资源重载超时");
        if (client.currentScreen instanceof AccessibilityOnboardingScreen) {
            client.setScreen(new TitleScreen());
        }
        if (!(client.currentScreen instanceof TitleScreen) || client.getOverlay() != null) {
            return;
        }
        if (reload != null) {
            if (!reload.isDone()) {
                return;
            }
            reload.join();
            reload = null;
        }
        switch (step) {
            case 0 -> {
                require(ModelResources.find("grass_pouch") != null, "默认草包没有登记");
                require(ModelResources.find("back_basket") != null, "默认背篓没有登记");
                original = Files.readString(directory.resolve("config.toml"));
                var vanilla =
                        client.getSoundManager()
                                .get(SoundEvents.UI_BUTTON_CLICK.value().getId())
                                .getSound(Random.create());
                try (var input =
                        client.getResourceManager()
                                .getResource(vanilla.getLocation())
                                .orElseThrow()
                                .getInputStream()) {
                    Files.write(directory.resolve("test.ogg"), input.readAllBytes());
                }
                Files.writeString(
                        directory.resolve("stray.bbmodel"), "not JSON and must never be parsed");
                Files.writeString(directory.resolve("broken.bbmodel"), "invalid registered model");
                String config =
                        """
                        version = 1
                        [[models]]
                        id = "bs3d:grass_pouch"
                        file = "models/GrassPouch.bbmodel"
                        [models.sounds]
                        open = "test.ogg"
                        close = "test.ogg"
                        place = "test.ogg"
                        pickup = "test.ogg"
                        touch = "test.ogg"
                        [[models]]
                        id = "bs3d:broken"
                        file = "broken.bbmodel"
                        """;
                reload(client, config);
            }
            case 1 -> {
                require(ModelResources.find("grass_pouch") != null, "合法资源被坏资源一起移除");
                require(ModelResources.find("back_basket") == null, "撤销注册后仍保留旧模型");
                require(ModelResources.find("broken") == null, "坏模型被加载");
                for (Action action : Action.values()) {
                    var sound =
                            client.getSoundManager()
                                    .get(ContainerSounds.id("grass_pouch", action))
                                    .getSound(Random.create());
                    require(
                            sound.getIdentifier().getPath().startsWith("imports/"),
                            "自定义动作音效未生效：" + action);
                    try (var input =
                                    client.getResourceManager()
                                            .getResource(sound.getLocation())
                                            .orElseThrow()
                                            .getInputStream();
                            var audio = new OggAudioStream(input)) {
                        require(audio.getBuffer(4096).hasRemaining(), "OGG 无法解码：" + action);
                    }
                }
                reload(client, "version = 1\nmodels = []\n");
            }
            case 2 -> {
                require(ModelResources.find("grass_pouch") == null, "空白名单仍有模型");
                Identifier texture = new Identifier("bs3d", "imports/bs3d/grass_pouch/0.png");
                require(client.getResourceManager().getResource(texture).isEmpty(), "旧贴图仍在资源目录");
                var sound =
                        client.getSoundManager()
                                .get(ContainerSounds.id("grass_pouch", Action.PLACE))
                                .getSound(Random.create());
                require(sound.getIdentifier().getNamespace().equals("minecraft"), "移除自定义声后未恢复默认");
                reload(client, "version = 1\n[[models]\n");
            }
            case 3 -> {
                require(ModelResources.find("grass_pouch") == null, "损坏配置没有关闭加载");
                reload(client, original);
            }
            case 4 -> {
                require(
                        ModelResources.find("grass_pouch") != null
                                && ModelResources.find("back_basket") != null,
                        "恢复配置后模型没有恢复");
                String result =
                        "registered-only; invalid-file-isolation; five-custom-ogg-actions; decode;"
                                + " revoke; malformed-config; restore";
                Files.writeString(client.runDirectory.toPath().resolve("resources-ok.txt"), result);
                System.out.println("BS3D_RESOURCES " + result);
                step++;
                client.scheduleStop();
            }
            default -> {}
        }
    }

    private void reload(MinecraftClient client, String config) throws Exception {
        // 调用 F3+T 使用的同一入口，下一 tick 等原版纹理与声音重载完成。
        Files.writeString(directory.resolve("config.toml"), config);
        ModelAsset beforeScan = ModelResources.find("grass_pouch");
        client.getResourcePackManager().scanPacks();
        require(beforeScan == ModelResources.find("grass_pouch"), "仅扫描资源包就提前切换了模型");
        step++;
        reload = client.reloadResources();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
