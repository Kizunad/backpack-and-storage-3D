package dev.kizuna.backpack3d.item;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/bs3d 与附属 JAR 内清单目录共用的资源路径约束，支持本地目录和归档文件系统。 */
public final class ResourcePaths {
    private ResourcePaths() {}

    public static Path local(Path directory, String value, String suffix) throws IOException {
        // 统一使用正斜杠；禁止绝对路径、路径穿越及符号链接逃出资源目录。
        Path root = directory.toAbsolutePath().normalize();
        // 用根目录所在的文件系统解析，JAR 内路径不能与默认文件系统混用。
        Path relative = root.getFileSystem().getPath(value);
        Path result = root.resolve(relative).normalize();
        if (value.contains("\\")
                || value.contains(":")
                || relative.isAbsolute()
                || !value.endsWith(suffix)
                || !result.startsWith(root)) {
            throw new IllegalArgumentException(
                    "资源路径必须在资源目录 " + root + " 内并以 " + suffix + " 结尾：" + value);
        }
        if (root.getFileSystem() != FileSystems.getDefault()) {
            // 模组归档内没有符号链接，部分归档实现也不支持 toRealPath。
            return result;
        }
        Path existing = result;
        while (!Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (!existing.toRealPath().startsWith(root.toRealPath())) {
            throw new IllegalArgumentException("资源符号链接越过资源目录：" + value);
        }
        return result;
    }
}
