package dev.kizuna.backpack3d.item;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Size;
import dev.kizuna.backpack3d.registry.pb.ItemRegistryProto.ContainerItem;
import dev.kizuna.backpack3d.registry.pb.ItemRegistryProto.ItemEntry;
import dev.kizuna.backpack3d.registry.pb.ItemRegistryProto.ItemRegistry;
import dev.kizuna.backpack3d.registry.pb.ItemRegistryProto.ModelAsset;
import dev.kizuna.backpack3d.registry.pb.ItemRegistryProto.ModelSounds;
import dev.kizuna.backpack3d.registry.pb.ItemRegistryProto.OptionalField;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 附属模组以 protobuf 清单登记物品，规则与 config.toml 一致：带格式版本、整份校验后才注册、
 * 拒绝未知字段与重复 ID。两端必须在物品注册表冻结前登记相同清单。
 */
public final class ItemRegistrations {
    public static final int FORMAT_VERSION = 1;
    // fabric.mod.json 的 custom 字段，值为一个或多个模组内资源路径。
    public static final String CUSTOM_KEY = "bs3d:item_registries";
    public static final int MAX_REGISTRY_BYTES = 256 * 1024;
    public static final int MAX_STACK_COUNT = 64;
    private static final String TEXT_SUFFIX = ".txtpb";
    private static final String BINARY_SUFFIX = ".binpb";
    private static final Logger LOG = LoggerFactory.getLogger("bs3d/item-registry");
    private static final Map<Identifier, BundledModel> BUNDLED = new LinkedHashMap<>();

    private ItemRegistrations() {}

    /**
     * 附属在自己的 main 入口调用；返回按清单顺序注册的物品。source 仅用于错误定位。
     * 不带清单目录，因此不能声明 container.asset，随 JAR 分发模型请使用 registerResource。
     */
    public static Map<Identifier, Item> register(String source, ItemRegistry registry) {
        return register(source, registry, null);
    }

    public static Map<Identifier, Item> registerBinary(String source, byte[] bytes) {
        return register(source, parseBinary(source, bytes), null);
    }

    public static Map<Identifier, Item> registerText(String source, String text) {
        return register(source, parseText(source, text), null);
    }

    /** 读取模组 JAR 内的清单；.txtpb 为文本格式，.binpb 为二进制格式。 */
    public static Map<Identifier, Item> registerResource(ModContainer mod, String path) {
        String source = mod.getMetadata().getId() + ":" + path;
        requireResourcePath(source, path);
        Path file = mod.findPath(path)
                .orElseThrow(() -> new IllegalArgumentException(source + "：模组内找不到清单文件"));
        return registerManifest(source, file);
    }

    /** 已随附属 JAR 登记的模型；客户端资源加载器读取，服务端不使用。 */
    public static List<BundledModel> bundledModels() {
        return List.copyOf(BUNDLED.values());
    }

    static Map<Identifier, Item> registerManifest(String source, Path file) {
        // 模型资产路径相对于清单所在目录，与 config.toml 相对于 config/bs3d 相同。
        try {
            requireLength(source, Files.size(file));
            byte[] bytes = Files.readAllBytes(file);
            ItemRegistry registry = file.toString().endsWith(BINARY_SUFFIX)
                    ? parseBinary(source, bytes)
                    : parseText(source, new String(bytes, StandardCharsets.UTF_8));
            return register(source, registry, file.toAbsolutePath().getParent());
        } catch (IOException failed) {
            throw new IllegalArgumentException(source + "：读取失败", failed);
        }
    }

    private static Map<Identifier, Item> register(String source, ItemRegistry registry, Path assetRoot) {
        // 先校验整份清单；任一条目失败都不注册任何物品，避免加载顺序留下半份目录。
        requireKnownFields(registry, source);
        List<Prepared> prepared = prepare(source, registry, assetRoot);
        var items = new LinkedHashMap<Identifier, Item>();
        for (Prepared entry : prepared) {
            items.put(entry.id(), registerEntry(entry));
            if (entry.bundled() != null) {
                BUNDLED.put(entry.bundled().model(), entry.bundled());
            }
        }
        LOG.info("{}：已注册 {} 个物品", source, items.size());
        return Map.copyOf(items);
    }

    private static ItemRegistry parseBinary(String source, byte[] bytes) {
        requireLength(source, bytes.length);
        try {
            return ItemRegistry.parseFrom(bytes);
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalArgumentException(source + "：protobuf 解析失败", invalid);
        }
    }

    private static ItemRegistry parseText(String source, String text) {
        // 默认解析器拒绝未知字段和重复的非 repeated 字段，拼写错误会直接定位。
        requireLength(source, text.getBytes(StandardCharsets.UTF_8).length);
        var builder = ItemRegistry.newBuilder();
        try {
            TextFormat.getParser().merge(text, builder);
        } catch (TextFormat.ParseException invalid) {
            throw new IllegalArgumentException(source + "：" + invalid.getMessage(), invalid);
        }
        return builder.build();
    }

    static void discover() {
        // 按模组 ID 排序，保证两端和多次启动的注册顺序一致；清单错误直接中止启动。
        var mods = new ArrayList<>(FabricLoader.getInstance().getAllMods());
        mods.sort(Comparator.comparing(mod -> mod.getMetadata().getId()));
        for (ModContainer mod : mods) {
            if (!mod.getMetadata().containsCustomValue(CUSTOM_KEY)) {
                continue;
            }
            for (String path : customPaths(mod)) {
                registerResource(mod, path);
            }
        }
    }

    private static List<String> customPaths(ModContainer mod) {
        // 允许单个字符串或字符串数组，其他类型说明作者写错了字段。
        String location = mod.getMetadata().getId() + " 的 " + CUSTOM_KEY;
        CustomValue value = mod.getMetadata().getCustomValue(CUSTOM_KEY);
        if (value.getType() == CustomValue.CvType.STRING) {
            return List.of(value.getAsString());
        }
        if (value.getType() != CustomValue.CvType.ARRAY) {
            throw new IllegalArgumentException(location + " 必须为字符串或字符串数组");
        }
        var paths = new ArrayList<String>();
        for (CustomValue element : value.getAsArray()) {
            if (element.getType() != CustomValue.CvType.STRING) {
                throw new IllegalArgumentException(location + " 只能包含字符串");
            }
            paths.add(element.getAsString());
        }
        return paths;
    }

    static List<Prepared> prepare(String source, ItemRegistry registry, Path assetRoot) {
        // 校验阶段只读注册表，不注册声音、物品或元数据。
        if (registry.getVersion() != FORMAT_VERSION) {
            throw new IllegalArgumentException(source + "：version 必须为 " + FORMAT_VERSION);
        }
        var prepared = new ArrayList<Prepared>();
        var identities = new HashSet<Identifier>();
        var models = new HashSet<Identifier>();
        for (int index = 0; index < registry.getItemsCount(); index++) {
            String location = source + " items[" + index + "]";
            Prepared entry = prepareEntry(location, registry.getItems(index), assetRoot);
            if (!identities.add(entry.id())) {
                throw new IllegalArgumentException(location + "：清单内重复物品 ID：" + entry.id());
            }
            // 同一模型只能由一个条目提供资产；其他共用外观的物品省略 asset 即可。
            BundledModel bundled = entry.bundled();
            if (bundled != null
                    && (BUNDLED.containsKey(bundled.model()) || !models.add(bundled.model()))) {
                throw new IllegalArgumentException(location + "：模型资产重复登记：" + bundled.model());
            }
            prepared.add(entry);
        }
        return List.copyOf(prepared);
    }

    private static Prepared prepareEntry(String location, ItemEntry entry, Path assetRoot) {
        Identifier id = identifier(location, "id", entry.getId());
        if (id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
            throw new IllegalArgumentException(location + "：id 不能使用 minecraft 命名空间");
        }
        if (Registries.ITEM.containsId(id)) {
            throw new IllegalArgumentException(location + "：物品 ID 已被注册：" + id);
        }
        Identifier tab = entry.getCreativeTab().isEmpty()
                ? null
                : identifier(location, "creative_tab", entry.getCreativeTab());
        var optional = optionalFields(location, entry.getOptionalFieldsList());
        BundledModel bundled = null;
        switch (entry.getKindCase()) {
            case PLAIN -> {
                int maxCount = entry.getPlain().getMaxCount();
                if (maxCount > MAX_STACK_COUNT) {
                    throw new IllegalArgumentException(location + "：plain.max_count 必须为 0..64");
                }
            }
            case CONTAINER -> {
                requireContainer(location, entry.getContainer());
                if (entry.getContainer().hasAsset()) {
                    bundled = bundledModel(location, entry.getContainer(), assetRoot);
                }
            }
            default -> throw new IllegalArgumentException(location + "：必须指定 plain 或 container");
        }
        return new Prepared(id, entry, optional, tab, bundled);
    }

    private static void requireContainer(
            String location, ContainerItem container) {
        // 与 ContainerItems.register 的约束一致，并提前报告字段位置。
        if (container.getTitle().isBlank()) {
            throw new IllegalArgumentException(location + "：container.title 必须为非空字符串");
        }
        identifier(location, "container.model", container.getModel());
        requireSize(location, "container.inside", container.hasInside(), container.getInside());
        requireSize(location, "container.outside", container.hasOutside(), container.getOutside());
        var mounts = new HashSet<Mount>();
        for (Mount mount : container.getMountsList()) {
            if (mount == Mount.MOUNT_DEFAULT || mount == Mount.UNRECOGNIZED) {
                throw new IllegalArgumentException(location + "：container.mounts 含无效挂点");
            }
            if (!mounts.add(mount)) {
                throw new IllegalArgumentException(location + "：container.mounts 重复挂点：" + mount);
            }
        }
    }

    private static BundledModel bundledModel(String location, ContainerItem container, Path root) {
        // 启动时只确认文件存在且位于清单目录内；几何、贴图和音频由客户端加载时校验。
        if (root == null) {
            throw new IllegalArgumentException(
                    location + "：container.asset 只能在模组清单文件中声明，请使用 registerResource");
        }
        ModelAsset asset = container.getAsset();
        Path file = bundledPath(location, "container.asset.file", root, asset.getFile(), ".bbmodel");
        var sounds = new LinkedHashMap<ContainerSounds.Action, Path>();
        ModelSounds configured = asset.getSounds();
        putSound(location, root, sounds, ContainerSounds.Action.OPEN, configured.getOpen());
        putSound(location, root, sounds, ContainerSounds.Action.CLOSE, configured.getClose());
        putSound(location, root, sounds, ContainerSounds.Action.PLACE, configured.getPlace());
        putSound(location, root, sounds, ContainerSounds.Action.PICKUP, configured.getPickup());
        putSound(location, root, sounds, ContainerSounds.Action.TOUCH, configured.getTouch());
        return new BundledModel(
                identifier(location, "container.model", container.getModel()),
                location,
                root,
                file,
                asset.hasOpenAnimation() ? asset.getOpenAnimation() : null,
                asset.hasCloseAnimation() ? asset.getCloseAnimation() : null,
                Map.copyOf(sounds));
    }

    private static void putSound(
            String location,
            Path root,
            Map<ContainerSounds.Action, Path> sounds,
            ContainerSounds.Action action,
            String value) {
        if (!value.isEmpty()) {
            String field = "container.asset.sounds." + action.key();
            sounds.put(action, bundledPath(location, field, root, value, ".ogg"));
        }
    }

    private static Path bundledPath(
            String location, String field, Path root, String value, String suffix) {
        Path file;
        try {
            file = ResourcePaths.local(root, value, suffix);
        } catch (IOException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException(location + "：" + field + " " + invalid.getMessage(), invalid);
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException(location + "：" + field + " 指向的文件不存在：" + value);
        }
        return file;
    }

    private static void requireSize(String location, String field, boolean present, Size size) {
        if (!present || size.getWidth() < 1 || size.getHeight() < 1 || size.getDepth() < 1) {
            throw new IllegalArgumentException(location + "：" + field + " 的宽、高、深必须为正整数");
        }
    }

    private static Map<Identifier, JsonObject> optionalFields(
            String location, List<OptionalField> fields) {
        // JSON 只检查为对象；字段含义、单位与回退由对应附属负责。
        var optional = new LinkedHashMap<Identifier, JsonObject>();
        for (OptionalField field : fields) {
            Identifier id = identifier(location, "optional_fields.id", field.getId());
            JsonObject value;
            try {
                var parsed = JsonParser.parseString(field.getJson());
                if (!parsed.isJsonObject()) {
                    throw new IllegalArgumentException(location + "：optional_fields " + id + " 必须为 JSON 对象");
                }
                value = parsed.getAsJsonObject();
            } catch (JsonParseException invalid) {
                throw new IllegalArgumentException(location + "：optional_fields " + id + " 不是合法 JSON", invalid);
            }
            if (optional.put(id, value) != null) {
                throw new IllegalArgumentException(location + "：optional_fields 重复字段：" + id);
            }
        }
        return Map.copyOf(optional);
    }

    private static Item registerEntry(Prepared prepared) {
        ItemEntry entry = prepared.entry();
        Item item;
        if (entry.hasContainer()) {
            var container = entry.getContainer();
            item = ContainerItems.register(
                    prepared.id(),
                    container.getTitle(),
                    entry.getDescriptionTranslationKey(),
                    container.getModel(),
                    container.getInside(),
                    container.getOutside(),
                    container.getMountsList(),
                    prepared.optional());
        } else {
            int maxCount = entry.getPlain().getMaxCount();
            var settings = new Item.Settings().maxCount(maxCount == 0 ? MAX_STACK_COUNT : maxCount);
            item = Registry.register(Registries.ITEM, prepared.id(), new Item(settings));
            ItemMetadata.register(item, entry.getDescriptionTranslationKey(), prepared.optional());
        }
        if (prepared.tab() != null) {
            Item registered = item;
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, prepared.tab()))
                    .register(entries -> entries.add(registered));
        }
        return item;
    }

    private static Identifier identifier(String location, String field, String value) {
        // 与 config.toml 相同，必须写完整命名空间，不接受旧短名称。
        Identifier id = value.contains(":") ? Identifier.tryParse(value) : null;
        if (id == null) {
            throw new IllegalArgumentException(location + "：" + field + " 必须为 namespace:path：" + value);
        }
        return id;
    }

    private static void requireKnownFields(Message message, String location) {
        // 二进制清单的未知字段会被 protobuf 保留，这里与文本格式、TOML 一样直接拒绝。
        if (!message.getUnknownFields().asMap().isEmpty()) {
            throw new IllegalArgumentException(location + "：含未知字段，请使用匹配版本的 item_registry.proto");
        }
        for (Map.Entry<FieldDescriptor, Object> field : message.getAllFields().entrySet()) {
            if (field.getKey().getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
                continue;
            }
            if (field.getKey().isRepeated()) {
                for (Object element : (List<?>) field.getValue()) {
                    requireKnownFields((Message) element, location);
                }
            } else {
                requireKnownFields((Message) field.getValue(), location);
            }
        }
        // 未知枚举值在 proto3 中保留为 UNRECOGNIZED，由挂点校验报告。
    }

    private static void requireLength(String source, long bytes) {
        if (bytes > MAX_REGISTRY_BYTES) {
            throw new IllegalArgumentException(source + "：清单超过 256 KiB");
        }
    }

    private static void requireResourcePath(String source, String path) {
        // 只读取模组自身资源，禁止绝对路径、反斜杠和路径穿越。
        List<String> parts = Arrays.asList(path.split("/"));
        if (path.isEmpty()
                || path.startsWith("/")
                || path.contains("\\")
                || parts.contains("..")
                || !(path.endsWith(TEXT_SUFFIX) || path.endsWith(BINARY_SUFFIX))) {
            throw new IllegalArgumentException(
                    source + "：清单路径必须为模组内相对路径，并以 .txtpb 或 .binpb 结尾");
        }
    }

    record Prepared(
            Identifier id,
            ItemEntry entry,
            Map<Identifier, JsonObject> optional,
            // null 表示不额外加入创造模式物品栏。
            Identifier tab,
            // null 表示没有随 JAR 分发的模型资产。
            BundledModel bundled) {}

    /** 随附属 JAR 分发的模型，路径已校验并解析为清单所在文件系统内的绝对路径。 */
    public record BundledModel(
            // 对应 ContainerItem.model，与 config.toml 的模型 ID 同一命名空间。
            Identifier model,
            // 声明位置，用于日志定位。
            String source,
            // 清单所在目录；外部贴图的 relative_path 也不能越过它。
            Path root,
            Path file,
            // null 表示自动寻找 open/close，空字符串表示禁用。
            String openAnimation,
            String closeAnimation,
            Map<ContainerSounds.Action, Path> sounds) {}
}
