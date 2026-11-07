package dev.kizuna.backpack3d.client;

import dev.kizuna.backpack3d.item.ContainerSounds;
import dev.kizuna.backpack3d.item.ContainerSounds.Action;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.client.sounds.JOrbisAudioStream;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.WearableItems;
import dev.kizuna.backpack3d.item.ItemRegistrations;

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
            "herb_pouch", "ore_sack", "water_skin", "moisture_guard", "coin_box", "ku_teng_yao_dai");
    private static final Identifier HERB_POUCH = Identifier.fromNamespaceAndPath("bs3d", "herb_pouch");
    // 玩家导入：同 ID 覆盖灵草囊自带模型并换上五种动作音效；坏模型只影响自己。
    private static final String IMPORTED =
            """
            version = 1
            [[models]]
            id = "bs3d:herb_pouch"
            file = "models/HerbPouch.bbmodel"
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
    private static final long TIMEOUT_MILLIS = 120_000;
    private final Path directory = FabricLoader.getInstance().getConfigDir().resolve("bs3d");
    private CompletableFuture<Void> reload;
    private Identifier importedSound;
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
                // 不再随模组附带默认 config.toml；首次启动只有各模组 JAR 自带的模型。
                require(!Files.exists(directory.resolve("config.toml")), "首次启动仍生成了 config.toml");
                verifyBongGear(client);
                require(
                        ModelResources.find("bs3d_smoke:bundled_pouch") != null,
                        "附属 JAR 自带模型没有加载");
                // 玩家导入：把灵草囊自带的 bbmodel 复制进 config/bs3d，用 config.toml 覆盖同 ID 模型并换上自定义音效。
                var bundled =
                        ItemRegistrations.bundledModels().stream()
                                .filter(model -> model.model().equals(HERB_POUCH))
                                .findFirst()
                                .orElseThrow();
                Files.createDirectories(directory.resolve("models"));
                Files.write(directory.resolve("models/HerbPouch.bbmodel"), Files.readAllBytes(bundled.file()));
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
                reload(client, IMPORTED);
            }
            case 1 -> {
                require(ModelResources.find("herb_pouch") != null, "合法资源被坏资源一起移除");
                require(ModelResources.find("broken") == null, "坏模型被加载");
                for (Action action : Action.values()) {
                    var sound =
                            client.getSoundManager()
                                    .getSoundEvent(ContainerSounds.id("herb_pouch", action))
                                    .getSound(RandomSource.create());
                    require(
                            sound.getLocation().getPath().startsWith("imports/"),
                            "自定义动作音效未生效：" + action);
                    importedSound = sound.getPath();
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
                // 撤销玩家登记后，灵草囊回到 JAR 自带模型与默认音效，导入的声音文件也不再留在资源里。
                verifyBongGear(client);
                require(
                        ModelResources.find("bs3d_smoke:bundled_pouch") != null,
                        "清空 config.toml 后附属自带模型被移除");
                require(client.getResourceManager().getResource(importedSound).isEmpty(), "旧导入音效仍在资源目录");
                require(defaultPlaceSound(client), "移除自定义声后未恢复默认");
                reload(client, "version = 1\n[[models]\n");
            }
            case 3 -> {
                verifyBongGear(client);
                require(defaultPlaceSound(client), "损坏配置没有关闭加载");
                require(
                        ModelResources.find("bs3d_smoke:bundled_pouch") != null,
                        "损坏的 config.toml 连带停用了附属自带模型");
                reload(client, IMPORTED);
            }
            case 4 -> {
                require(!defaultPlaceSound(client), "恢复配置后导入资源没有恢复");
                String result =
                        "no-default-config; registered-only; invalid-file-isolation; override-bundled;"
                                + " five-custom-ogg-actions; decode; revoke; malformed-config; restore;"
                                + " bundled-addon-model; ten-bong-containers; raw-segments";
                Files.writeString(client.gameDirectory.toPath().resolve("resources-ok.txt"), result);
                System.out.println("BS3D_RESOURCES " + result);
                step++;
                client.stop();
            }
            default -> {}
        }
    }

    private static boolean defaultPlaceSound(Minecraft client) {
        var sound =
                client.getSoundManager()
                        .getSoundEvent(ContainerSounds.id("herb_pouch", Action.PLACE))
                        .getSound(RandomSource.create());
        return sound.getLocation().getNamespace().equals("minecraft");
    }

    private void reload(Minecraft client, String config) throws Exception {
        // 调用 F3+T 使用的同一入口，下一 tick 等原版纹理与声音重载完成。
        Files.writeString(directory.resolve("config.toml"), config);
        ModelAsset beforeScan = ModelResources.find("herb_pouch");
        client.getResourcePackRepository().reload();
        require(beforeScan == ModelResources.find("herb_pouch"), "仅扫描资源包就提前切换了模型");
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
        verifyWearableSegments();
    }

    private static void verifyWearableSegments() {
        // 注册清单的原坐标模式须经过真实资源包加载与重载，不能只在内存几何测试里生效。
        var id = Identifier.fromNamespaceAndPath("bs3d_smoke", "segmented_wearable");
        var type = WearableItems.types().get(BuiltInRegistries.ITEM.getValue(id));
        require(type != null, "非容器穿戴清单没有注册");
        var asset = ModelResources.find(type.model());
        require(asset != null, "分段模型未加载");
        for (var slot : type.slots()) {
            require(asset.segment(slot.getSegment()) != null, "分段模型没有对应顶层组");
        }
    }
}
