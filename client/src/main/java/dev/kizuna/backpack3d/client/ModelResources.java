package dev.kizuna.backpack3d.client;

import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** 所有模型入口共享注册目录，未登记或导入失败的模型不会按文件名绕过白名单。 */
public final class ModelResources implements SimpleSynchronousResourceReloadListener {
    private static final Logger LOG = LoggerFactory.getLogger("bs3d/resources");
    private static final String PACK_NAME = "bs3d/imported";
    private static Map<Identifier, ModelAsset> models = Map.of();
    private static final Set<String> missing = new HashSet<>();

    private ModelResources() {}

    static void initialize() {
        // 监听器在资源实际切换时发布模型，资源包列表扫描只准备候选资源。
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES)
                .registerReloadListener(new ModelResources());
    }

    @Override
    public Identifier getFabricId() {
        return new Identifier("bs3d", "imported_models");
    }

    @Override
    public void reload(ResourceManager manager) {
        // 几何与同一次加载的纹理保持一致；原版恢复旧资源包时也恢复对应模型。
        models = Map.of();
        try (var packs = manager.streamResourcePacks()) {
            for (var pack : packs.toList()) {
                if (pack instanceof ImportedResourcePack imported) {
                    models = imported.models();
                    break;
                }
            }
        }
        missing.clear();
    }

    public static void registerPack(Consumer<ResourcePackProfile> consumer) {
        // 扫描不修改当前模型目录，候选资源要等原版重载完成后才生效。
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("bs3d").toAbsolutePath();
        var assets = new LinkedHashMap<Identifier, ModelAsset>();
        var files = new LinkedHashMap<Identifier, byte[]>();
        JsonObject sounds = ModelSounds.defaults();
        for (ModelConfig.Entry entry : readRegistrations(directory)) {
            loadModel(directory, entry, assets, files, sounds);
        }
        files.put(
                new Identifier("bs3d", "sounds.json"),
                sounds.toString().getBytes(StandardCharsets.UTF_8));

        ResourcePackProfile profile =
                ResourcePackProfile.create(
                        PACK_NAME,
                        Text.literal("Backpack and Storage 3D · 导入资源"),
                        true,
                        name -> new ImportedResourcePack(name, files, assets),
                        ResourceType.CLIENT_RESOURCES,
                        ResourcePackProfile.InsertionPosition.TOP,
                        ResourcePackSource.BUILTIN);
        if (profile != null) {
            consumer.accept(profile);
        }
    }

    private static List<ModelConfig.Entry> readRegistrations(Path directory) {
        // 配置整体校验失败时停用全部导入，不能让前半份注册表意外生效。
        try {
            installDefaults(directory);
            return ModelConfig.read(directory, LOG::warn);
        } catch (IOException | RuntimeException invalid) {
            LOG.warn(
                    "无法加载 {}，全部导入模型已停用：{}", directory.resolve("config.toml"), invalid.getMessage());
            return List.of();
        }
    }

    private static void loadModel(
            Path directory,
            ModelConfig.Entry entry,
            Map<Identifier, ModelAsset> assets,
            Map<Identifier, byte[]> files,
            JsonObject sounds) {
        // 每个模型独立处理；单个文件失败不妨碍其余已登记资源加载。
        try {
            BbModel model = BbModel.read(directory, entry);
            ModelAsset asset = new ModelAsset(model);
            assets.put(entry.id(), asset);
            files.putAll(model.textures());
            ModelSounds.addOverrides(entry, sounds, files);
            LOG.info("已加载模型 {}：{}，{} 个立方体", entry.id(), entry.file(), model.cubes().size());
        } catch (IOException | RuntimeException invalid) {
            LOG.warn("模型 {} 导入失败，跳过 {}：{}", entry.id(), entry.file(), invalid.getMessage());
        }
    }

    public static ModelAsset find(String name) {
        // 未登记的服务器模型只警告一次，不每帧刷屏，也不在绘制线程尝试读文件。
        ModelAsset asset = optional(name);
        if (asset == null) {
            warnMissing(name);
        }
        return asset;
    }

    static ModelAsset optional(String name) {
        // 原版收纳箱等类型允许保留标准物品模型，不为这种回退产生缺失警告。
        Identifier id = Identifier.tryParse(name.contains(":") ? name : "bs3d:" + name);
        return id == null ? null : models.get(id);
    }

    private static void warnMissing(String name) {
        if (missing.add(name)) {
            LOG.warn("模型 {} 未注册或导入失败，跳过绘制；请检查 config/bs3d/config.toml", name);
        }
    }

    private static void installDefaults(Path directory) throws IOException {
        // 仅首次创建模板，不覆盖用户已经登记的路径或编辑过的模型。
        Files.createDirectories(directory);
        if (Files.exists(directory.resolve("config.toml"))) {
            return;
        }
        for (String file :
                List.of("models/GrassPouch.bbmodel", "models/BackBasket.bbmodel", "config.toml")) {
            Path target = directory.resolve(file);
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                try (var input =
                        ModelResources.class.getResourceAsStream("/bs3d/defaults/" + file)) {
                    if (input == null) {
                        throw new IOException("缺少内置模板：" + file);
                    }
                    Files.copy(input, target);
                }
            }
        }
    }
}
