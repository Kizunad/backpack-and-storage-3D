package dev.kizuna.backpack3d.client;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 一次导入生成一份只读资源包；PNG 和 OGG 走原版资源及音频生命周期。 */
final class ImportedResourcePack extends AbstractPackResources {
    private final Map<Identifier, byte[]> files;
    private final Map<Identifier, ModelAsset> models;

    ImportedResourcePack(
            PackLocationInfo location, Map<Identifier, byte[]> files, Map<Identifier, ModelAsset> models) {
        super(location);
        this.files = Map.copyOf(files);
        this.models = Map.copyOf(models);
    }

    Map<Identifier, ModelAsset> models() {
        return models;
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... segments) {
        // 元数据随当前游戏版本生成，无需在用户目录维护中间资源包。
        if (segments.length != 1 || !segments[0].equals(PACK_META)) {
            return null;
        }
        // 26.2 的资源包格式带次版本号，新格式只认 min_format/max_format。
        var format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
        String version = "[" + format.major() + "," + format.minor() + "]";
        byte[] metadata =
                ("{\"pack\":{\"min_format\":"
                                + version
                                + ",\"max_format\":"
                                + version
                                + ",\"description\":\"Backpack and Storage 3D imported"
                                + " resources\"}}")
                        .getBytes(StandardCharsets.UTF_8);
        return () -> new ByteArrayInputStream(metadata);
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
        // 本资源包没有服务端数据，防止被误用作修改容量或库存的入口。
        byte[] data = type == PackType.CLIENT_RESOURCES ? files.get(id) : null;
        return data == null ? null : () -> new ByteArrayInputStream(data);
    }

    @Override
    public void listResources(
            PackType type, String namespace, String prefix, ResourceOutput consumer) {
        if (type != PackType.CLIENT_RESOURCES) {
            return;
        }
        for (var entry : files.entrySet()) {
            Identifier id = entry.getKey();
            if (id.getNamespace().equals(namespace) && id.getPath().startsWith(prefix + "/")) {
                consumer.accept(id, () -> new ByteArrayInputStream(entry.getValue()));
            }
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.CLIENT_RESOURCES
                ? files.keySet().stream().map(Identifier::getNamespace).collect(Collectors.toSet())
                : Set.of();
    }

    @Override
    public void close() {
        // 文件已在导入时读成不可变快照，没有悬挂的文件句柄或原生纹理。
    }
}
