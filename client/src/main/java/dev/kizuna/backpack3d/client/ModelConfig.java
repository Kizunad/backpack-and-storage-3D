package dev.kizuna.backpack3d.client;

import dev.kizuna.backpack3d.item.ContainerSounds;
import dev.kizuna.backpack3d.item.ContainerSounds.Action;

import net.minecraft.util.Identifier;

import org.tomlj.Toml;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** 本地资源白名单。路径相对于 config/bs3d，不读取编辑器记录的绝对路径。 */
final class ModelConfig {
    static final int FORMAT_VERSION = 1;
    private static final int MAX_CONFIG_BYTES = 256 * 1024;
    private static final Set<String> MODEL_KEYS =
            Set.of("id", "file", "open_animation", "close_animation", "sounds");

    record Entry(
            Identifier id,
            Path file,
            // null 表示自动寻找同名 open/close，空字符串表示主动禁用。
            String openAnimation,
            String closeAnimation,
            Map<Action, Path> sounds) {}

    private ModelConfig() {}

    static List<Entry> read(Path directory, Consumer<String> warning) throws IOException {
        // 先验证整个注册表；语法、重复身份及未知字段错误不能导致半份配置生效。
        Path file = directory.resolve("config.toml");
        if (Files.size(file) > MAX_CONFIG_BYTES) {
            throw new IllegalArgumentException("config.toml 超过 256 KiB");
        }
        var document = Toml.parse(file);
        if (document.hasErrors()) {
            throw new IllegalArgumentException("config.toml: " + document.errors());
        }
        requireKeys(document, Set.of("version", "models"), "config.toml");
        if (!Long.valueOf(FORMAT_VERSION).equals(document.getLong("version"))) {
            throw new IllegalArgumentException("config.toml: version 必须为 " + FORMAT_VERSION);
        }
        var entries = new ArrayList<Entry>();
        var identities = new HashSet<Identifier>();
        var registeredFiles = new HashSet<Path>();
        var models = document.getArray("models");
        if (models != null) {
            for (int index = 0; index < models.size(); index++) {
                var entry = readEntry(directory, models.getTable(index));
                if (!identities.add(entry.id())) {
                    throw new IllegalArgumentException("重复模型 ID：" + entry.id());
                }
                entries.add(entry);
                registeredFiles.add(entry.file());
            }
        }
        // 只扫描文件名，不解析未登记的 bbmodel；警告每次重载输出一次。
        try (var paths = Files.walk(directory)) {
            paths.filter(path -> path.toString().endsWith(".bbmodel"))
                    .filter(path -> !registeredFiles.contains(path.toAbsolutePath().normalize()))
                    .sorted()
                    .forEach(path -> warning.accept("未注册模型，跳过加载：" + path));
        }
        return List.copyOf(entries);
    }

    private static Entry readEntry(Path directory, TomlTable table) throws IOException {
        // 先校验模型身份和文件，再收集可选动画及音效，资源内容留给各自的加载器验证。
        requireKeys(table, MODEL_KEYS, "models");
        String name = required(table, "id");
        if (!name.contains(":")) {
            throw new IllegalArgumentException("models.id 必须包含命名空间：" + name);
        }
        Identifier id = ContainerSounds.modelId(name);
        Path file = localPath(directory, required(table, "file"), ".bbmodel");
        Map<Action, Path> sounds = readSounds(directory, table.getTable("sounds"), name);
        return new Entry(
                id,
                file,
                table.getString("open_animation"),
                table.getString("close_animation"),
                sounds);
    }

    private static Map<Action, Path> readSounds(Path directory, TomlTable soundTable, String name)
            throws IOException {
        // 可选表仅登记文件路径，音频解码放在模型加载阶段，保持错误作用范围清楚。
        if (soundTable == null) {
            return Map.of();
        }
        var sounds = new LinkedHashMap<Action, Path>();
        Set<String> keys = new HashSet<>();
        for (Action action : Action.values()) {
            keys.add(action.key());
            String value = soundTable.getString(action.key());
            if (value != null) {
                sounds.put(action, localPath(directory, value, ".ogg"));
            }
        }
        requireKeys(soundTable, keys, name + ".sounds");
        return Map.copyOf(sounds);
    }

    static Path localPath(Path directory, String value, String suffix) throws IOException {
        // 配置统一使用正斜杠；禁止路径穿越及符号链接逃出资源目录。
        Path root = directory.toAbsolutePath().normalize();
        Path relative = Path.of(value);
        Path result = root.resolve(relative).normalize();
        if (value.contains("\\")
                || value.contains(":")
                || relative.isAbsolute()
                || !value.endsWith(suffix)
                || !result.startsWith(root)) {
            throw new IllegalArgumentException(
                    "资源路径必须在 config/bs3d 内并以 " + suffix + " 结尾：" + value);
        }
        Path existing = result;
        while (!Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (!existing.toRealPath().startsWith(root.toRealPath())) {
            throw new IllegalArgumentException("资源符号链接越过 config/bs3d：" + value);
        }
        return result;
    }

    private static String required(TomlTable table, String field) {
        String value = table.getString(field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("models." + field + " 必须为非空字符串");
        }
        return value;
    }

    private static void requireKeys(TomlTable table, Set<String> allowed, String location) {
        // 拼错字段直接定位，避免用户误以为写入的选项已经生效。
        for (String key : table.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(location + " 含未知字段：" + key);
            }
        }
    }
}
