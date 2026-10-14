package dev.kizuna.backpack3d.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

/** 读取模型的内嵌或相对 PNG，并在创建游戏纹理之前验证图片。 */
final class ModelTextures {
    private static final int MAX_TEXTURE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TEXTURE_PIXELS = 4096;
    private static final String PNG_DATA_PREFIX = "data:image/png;base64,";

    private ModelTextures() {}

    static Map<Identifier, byte[]> read(Path directory, ModelConfig.Entry entry, JsonObject json)
            throws IOException {
        // 纹理序号由模型中的顺序决定，返回目录必须保持插入顺序。
        Map<Identifier, byte[]> images = new LinkedHashMap<>();
        for (JsonElement element : JsonHelper.getArray(json, "textures")) {
            JsonObject texture = element.getAsJsonObject();
            byte[] bytes = readImage(directory, entry.file(), texture);
            validatePng(bytes);
            Identifier id =
                    new Identifier(
                            "bs3d",
                            "imports/"
                                    + entry.id().getNamespace()
                                    + "/"
                                    + entry.id().getPath()
                                    + "/"
                                    + images.size()
                                    + ".png");
            images.put(id, bytes);
        }
        return images;
    }

    private static byte[] readImage(Path directory, Path modelFile, JsonObject texture)
            throws IOException {
        String source = JsonHelper.getString(texture, "source", "");
        if (source.startsWith(PNG_DATA_PREFIX)) {
            return Base64.getDecoder().decode(source.substring(PNG_DATA_PREFIX.length()));
        }
        // 编辑器绝对路径不可移植，外部贴图必须通过 relative_path 随模型一起分发。
        String relative = JsonHelper.getString(texture, "relative_path", "");
        Path location = modelFile.getParent().resolve(relative);
        Path root = directory.toAbsolutePath().normalize();
        String withinRoot = root.relativize(location).toString().replace('\\', '/');
        Path file = ModelConfig.localPath(directory, withinRoot, ".png");
        if (Files.size(file) > MAX_TEXTURE_BYTES) {
            throw new IllegalArgumentException("外部 PNG 超过 8 MiB");
        }
        return Files.readAllBytes(file);
    }

    private static void validatePng(byte[] bytes) throws IOException {
        // 先读取图片头限制像素尺寸，再让游戏解码，避免巨大图片分配纹理内存。
        if (bytes.length > MAX_TEXTURE_BYTES) {
            throw new IllegalArgumentException("PNG 超过 8 MiB");
        }
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("贴图不是有效 PNG");
            }
            var reader = readers.next();
            try {
                reader.setInput(input);
                if (!reader.getFormatName().equalsIgnoreCase("png")
                        || reader.getWidth(0) > MAX_TEXTURE_PIXELS
                        || reader.getHeight(0) > MAX_TEXTURE_PIXELS) {
                    throw new IllegalArgumentException("贴图必须为不超过 4096×4096 的 PNG");
                }
                reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }
}
