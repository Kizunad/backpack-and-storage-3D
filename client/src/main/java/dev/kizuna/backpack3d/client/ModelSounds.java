package dev.kizuna.backpack3d.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.ContainerSounds;
import dev.kizuna.backpack3d.item.ContainerSounds.Action;

import net.minecraft.client.sound.OggAudioStream;
import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 为导入资源包生成声音目录，校验可选 OGG 并保留失败动作的默认声音。 */
final class ModelSounds {
    private static final Logger LOG = LoggerFactory.getLogger("bs3d/resources");
    private static final int MAX_SOUND_BYTES = 8 * 1024 * 1024;
    private static final int OGG_IDENTIFICATION_BYTES = 64;
    private static final int AUDIO_CHUNK_BYTES = 32 * 1024;
    private static final int MAX_SOUND_SECONDS = 60;

    private ModelSounds() {}

    static JsonObject defaults() {
        var sounds = new JsonObject();
        // 所有注册物品都拥有默认事件，即使它的外观配置缺失，放置音效仍然可用。
        for (var type : ContainerItems.types().values()) {
            for (Action action : Action.values()) {
                putSound(
                        sounds,
                        ContainerSounds.id(type.model(), action),
                        action.fallback(type.waist()),
                        true);
            }
        }
        return sounds;
    }

    static void addOverrides(
            ModelConfig.Entry entry, JsonObject sounds, Map<Identifier, byte[]> files) {
        for (var configured : entry.sounds().entrySet()) {
            Path path = configured.getValue();
            try {
                byte[] bytes = readOgg(path);
                Identifier event = ContainerSounds.id(entry.id().toString(), configured.getKey());
                Identifier sound = new Identifier("bs3d", "imports/" + event.getPath());
                files.put(
                        new Identifier(sound.getNamespace(), "sounds/" + sound.getPath() + ".ogg"),
                        bytes);
                putSound(sounds, event, sound.toString(), false);
            } catch (IOException | IllegalArgumentException invalid) {
                // 单个可选声音错误不会隐藏整个背包；日志定位到动作并保留该动作的默认声。
                LOG.warn(
                        "模型 {} 的 {} 音效无效，沿用默认声：{}：{}",
                        entry.id(),
                        configured.getKey().key(),
                        path,
                        invalid.getMessage());
            }
        }
    }

    static byte[] readOgg(Path path) throws IOException {
        if (Files.size(path) > MAX_SOUND_BYTES) {
            throw new IllegalArgumentException("OGG 超过 8 MiB");
        }
        byte[] bytes = Files.readAllBytes(path);
        // Minecraft 使用 Vorbis 解码器，改后缀的文件或 Ogg Opus 不能作为音效。
        String header =
                new String(
                        bytes,
                        0,
                        Math.min(bytes.length, OGG_IDENTIFICATION_BYTES),
                        StandardCharsets.ISO_8859_1);
        if (!header.startsWith("OggS") || !header.contains("\u0001vorbis")) {
            throw new IllegalArgumentException("需要 Ogg Vorbis 音频");
        }
        // 用游戏的解码器验证整段音频，坏文件在导入时回退，而非点击背包时才报错。
        try (var audio = new OggAudioStream(new ByteArrayInputStream(bytes))) {
            var format = audio.getFormat();
            long limit = (long) (format.getFrameRate() * format.getFrameSize() * MAX_SOUND_SECONDS);
            long decoded = 0;
            int count;
            while ((count = audio.getBuffer(AUDIO_CHUNK_BYTES).remaining()) > 0) {
                decoded += count;
                if (decoded > limit) {
                    throw new IllegalArgumentException("动作音效不能超过 60 秒");
                }
            }
            if (decoded == 0) {
                throw new IllegalArgumentException("OGG 不含可播放音频");
            }
        }
        return bytes;
    }

    private static void putSound(
            JsonObject catalog, Identifier event, String sound, boolean reference) {
        var definition = new JsonObject();
        definition.addProperty("replace", true);
        var source = new JsonObject();
        source.addProperty("name", sound);
        source.addProperty("type", reference ? "event" : "file");
        var values = new JsonArray();
        values.add(source);
        definition.add("sounds", values);
        catalog.add(event.getPath(), definition);
    }
}
