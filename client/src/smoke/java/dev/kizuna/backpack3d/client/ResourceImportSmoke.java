package dev.kizuna.backpack3d.client;

import dev.kizuna.backpack3d.item.ContainerSounds;
import dev.kizuna.backpack3d.item.ContainerSounds.Action;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.client.sounds.JOrbisAudioStream;
import dev.kizuna.backpack3d.item.ContainerItems;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.List;

/** 只在隔离验收入口执行：真实客户端白名单重载及自定义 OGG 解码。 */
public final class ResourceImportSmoke implements ClientModInitializer {
    private static final List<String> BONG_GEAR = List.of(
            "gu_jia_bei_lou", "xiu_kuang_bei_jia", "za_pi_xing_nang", "pi_nang_xiong_gua",
            "herb_pouch", "ore_sack", "water_skin", "moisture_guard", "coin_box");
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

    private void tick(Minecraft client) throws Exception {
        // 标题页也完成资源加载，无需连接服务器或创建玩家世界。
        if (started == 0) {
            started = System.currentTimeMillis();
        }
        require(System.currentTimeMillis() - started < TIMEOUT_MILLIS, "资源重载超时");
        if (client.gui.screen() instanceof AccessibilityOnboardingScreen) {
            client.gui.setScreen(new TitleScreen());
        }
        if (!(client.gui.screen() instanceof TitleScreen) || client.gui.overlay() != null) {
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
                verifyBongGear(client);
                require(ModelResources.find("grass_pouch") != null, "默认草包没有登记");
                require(ModelResources.find("back_basket") != null, "默认背篓没有登记");
                require(
                        ModelResources.find("bs3d_smoke:bundled_pouch") != null,
                        "附属 JAR 自带模型没有加载");
                original = Files.readString(directory.resolve("config.toml"));
                var vanilla =
                        client.getSoundManager()
                                .getSoundEvent(SoundEvents.UI_BUTTON_CLICK.value().location())
                                .getSound(RandomSource.create());
                try (var input =
                        client.getResourceManager()
                                .getResource(vanilla.getPath())
                                .orElseThrow()
                                .open()) {
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
                                    .getSoundEvent(ContainerSounds.id("grass_pouch", action))
                                    .getSound(RandomSource.create());
                    require(
                            sound.getLocation().getPath().startsWith("imports/"),
                            "自定义动作音效未生效：" + action);
                    try (var input =
                                    client.getResourceManager()
                                            .getResource(sound.getPath())
                                            .orElseThrow()
                                            .open();
                            var audio = new JOrbisAudioStream(input)) {
                        require(audio.read(4096).hasRemaining(), "OGG 无法解码：" + action);
                    }
                }
                reload(client, "version = 1\nmodels = []\n");
            }
            case 2 -> {
                verifyBongGear(client);
                require(ModelResources.find("grass_pouch") == null, "空白名单仍有模型");
                // 附属自带模型不属于玩家白名单，清空 config.toml 不影响它。
                require(
                        ModelResources.find("bs3d_smoke:bundled_pouch") != null,
                        "清空 config.toml 后附属自带模型被移除");
                Identifier texture = Identifier.fromNamespaceAndPath("bs3d", "imports/bs3d/grass_pouch/0.png");
                require(client.getResourceManager().getResource(texture).isEmpty(), "旧贴图仍在资源目录");
                var sound =
                        client.getSoundManager()
                                .getSoundEvent(ContainerSounds.id("grass_pouch", Action.PLACE))
                                .getSound(RandomSource.create());
                require(sound.getLocation().getNamespace().equals("minecraft"), "移除自定义声后未恢复默认");
                reload(client, "version = 1\n[[models]\n");
            }
            case 3 -> {
                verifyBongGear(client);
                require(ModelResources.find("grass_pouch") == null, "损坏配置没有关闭加载");
                require(
                        ModelResources.find("bs3d_smoke:bundled_pouch") != null,
                        "损坏的 config.toml 连带停用了附属自带模型");
                reload(client, original);
            }
            case 4 -> {
                require(
                        ModelResources.find("grass_pouch") != null
                                && ModelResources.find("back_basket") != null,
                        "恢复配置后模型没有恢复");
                String result =
                        "registered-only; invalid-file-isolation; five-custom-ogg-actions; decode;"
                                + " revoke; malformed-config; restore; bundled-addon-model; nine-bong-containers";
                Files.writeString(client.gameDirectory.toPath().resolve("resources-ok.txt"), result);
                System.out.println("BS3D_RESOURCES " + result);
                step++;
                client.stop();
            }
            default -> {}
        }
    }

    private void reload(Minecraft client, String config) throws Exception {
        // 调用 F3+T 使用的同一入口，下一 tick 等原版纹理与声音重载完成。
        Files.writeString(directory.resolve("config.toml"), config);
        ModelAsset beforeScan = ModelResources.find("grass_pouch");
        client.getResourcePackRepository().reload();
        require(beforeScan == ModelResources.find("grass_pouch"), "仅扫描资源包就提前切换了模型");
        step++;
        reload = client.reloadResourcePacks();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void verifyBongGear(Minecraft client) {
        // 同时验证真实注册、物品动态渲染模型和资源包中的内嵌贴图，覆盖已有空配置的升级。
        // 26.2 标题界面尚未加载世界数据，物品组件未绑定、不能构造物品栈；未覆盖时物品模型 ID 即注册 ID。
        for (String name : BONG_GEAR) {
            var id = Identifier.fromNamespaceAndPath("bs3d", name);
            var definition = ContainerItems.types().get(BuiltInRegistries.ITEM.getValue(id));
            require(definition != null, "Bong 容器未注册：" + name);
            var asset = ModelResources.find(definition.model());
            require(asset != null, "Bong 模型未加载：" + name);
            require(client.getModelManager().getItemModel(id) instanceof SpecialModelWrapper<?>,
                    "未启用三维物品渲染：" + name);
            require(client.getResourceManager()
                    .getResource(Identifier.fromNamespaceAndPath("bs3d", "imports/bs3d/" + name + "/0.png")).isPresent(),
                    "Bong 贴图未加载：" + name);
            var animation = new BagAnimation();
            animation.configure(asset);
            animation.open();
            animation.advance(0);
            require(animation.ready(), "静态容器无法直接打开：" + name);
        }
    }
}
