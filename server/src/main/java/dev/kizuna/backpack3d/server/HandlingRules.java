package dev.kizuna.backpack3d.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Size;

import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 数据包用原版 item tag 选择材质声音、处理时长与占用体积。 */
public final class HandlingRules implements SimpleSynchronousResourceReloadListener {
    public static final int DEFAULT_DURATION_TICKS = 40;
    private static final int MIN_DURATION_TICKS = 1;
    private static final int MAX_DURATION_TICKS = 200;
    private static final int DIMENSION_COUNT = 3;
    private static final int MIN_SIZE_CELLS = 1;
    private static final int MAX_SIZE_CELLS = 3;
    private static final int DEFAULT_PRIORITY = 0;
    private static final float DEFAULT_VOLUME = 0.4f;
    private static final float DEFAULT_PITCH = 1.0f;
    private List<Rule> rules = List.of();

    @Override
    public Identifier getFabricId() {
        return new Identifier("bs3d", "handling_rules");
    }

    @Override
    public void reload(ResourceManager resources) {
        // 先按资源 ID 读取。后面的稳定排序让同优先级规则保持这个顺序。
        var files = resources.findResources("bs3d/handling", id -> id.getPath().endsWith(".json"));
        var entries = new ArrayList<>(files.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        List<Rule> loadedRules = new ArrayList<>();
        for (var entry : entries) {
            Rule rule = readRule(entry.getKey(), entry.getValue());
            loadedRules.add(rule);
        }

        // 任一文件失败都会保留旧目录，不能只应用重载成功的那一部分。
        loadedRules.sort(Comparator.comparingInt(Rule::priority).reversed());
        rules = List.copyOf(loadedRules);
    }

    private static Rule readRule(Identifier resourceId, Resource resource) {
        // 文件边界负责关闭 reader 和补充文件名；字段错误由下面的解析步骤说明。
        try (var reader = resource.getReader()) {
            JsonObject json = JsonHelper.asObject(JsonParser.parseReader(reader), "规则根节点");

            Identifier tagId = readIdentifier(json, "tag");
            TagKey<Item> tag = TagKey.of(RegistryKeys.ITEM, tagId);

            Identifier soundId = readIdentifier(json, "sound");
            if (!Registries.SOUND_EVENT.containsId(soundId)) {
                throw new IllegalArgumentException("sound 未注册：" + soundId);
            }

            int durationTicks = JsonHelper.getInt(json, "duration_ticks");
            if (durationTicks < MIN_DURATION_TICKS || durationTicks > MAX_DURATION_TICKS) {
                throw new IllegalArgumentException(
                        "duration_ticks 必须在 "
                                + MIN_DURATION_TICKS
                                + ".."
                                + MAX_DURATION_TICKS
                                + " tick 之间");
            }

            Size size = readSize(json);
            int priority = JsonHelper.getInt(json, "priority", DEFAULT_PRIORITY);
            return new Rule(tag, soundId.toString(), durationTicks, size, priority);
        } catch (IOException | JsonParseException | IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "无法加载规则 " + resourceId + "：" + failure.getMessage(), failure);
        }
    }

    private static Identifier readIdentifier(JsonObject json, String field) {
        // 保留字段名，让无效命名空间与无效路径都能直接定位到配置项。
        String value = JsonHelper.getString(json, field);
        Identifier identifier = Identifier.tryParse(value);
        if (identifier == null) {
            throw new IllegalArgumentException(field + " 不是有效的资源 ID：" + value);
        }
        return identifier;
    }

    private static Size readSize(JsonObject json) {
        // 未声明尺寸时使用一格；声明后按宽、高、深的顺序校验，不能漏轴或多轴。
        if (!json.has("size")) {
            return Volumes.UNIT;
        }
        JsonArray dimensions = JsonHelper.getArray(json, "size");
        if (dimensions.size() != DIMENSION_COUNT) {
            throw new IllegalArgumentException("size 必须包含宽、高、深三个尺寸");
        }
        int width = readDimension(dimensions, 0, "宽");
        int height = readDimension(dimensions, 1, "高");
        int depth = readDimension(dimensions, 2, "深");
        return Volumes.size(width, height, depth);
    }

    private static int readDimension(JsonArray dimensions, int index, String axis) {
        // 单独检查每个轴，错误应指出宽、高或深，而不是笼统报告尺寸无效。
        int cells = JsonHelper.asInt(dimensions.get(index), "size." + axis);
        if (cells < MIN_SIZE_CELLS || cells > MAX_SIZE_CELLS) {
            throw new IllegalArgumentException(
                    "size." + axis + " 必须在 " + MIN_SIZE_CELLS + ".." + MAX_SIZE_CELLS + " 格之间");
        }
        return cells;
    }

    public Material material(ItemStack stack) {
        // 显式 tag 规则优先；目录已经排序，第一个匹配项就是最终规则。
        for (Rule rule : rules) {
            if (stack.isIn(rule.tag())) {
                return new Material(
                        rule.sound(), rule.ticks(), DEFAULT_VOLUME, DEFAULT_PITCH, rule.size());
            }
        }

        // 没有规则时，方块沿用自己的放置声，其他物品使用通用轻触声。
        String sound = "minecraft:entity.item.pickup";
        if (stack.getItem() instanceof BlockItem blockItem) {
            var soundGroup = blockItem.getBlock().getDefaultState().getSoundGroup();
            sound = soundGroup.getPlaceSound().getId().toString();
        }
        return new Material(
                sound, DEFAULT_DURATION_TICKS, DEFAULT_VOLUME, DEFAULT_PITCH, Volumes.UNIT);
    }

    /** 一次物品转移采用的材质规则。 */
    public record Material(
            // 服务器注册的 SoundEvent ID。
            String sound,
            // 处理时长，单位为服务器 tick。
            int ticks,
            // 播放音量与音高倍率。
            float volume,
            float pitch,
            // 物品未旋转的宽、高、深，单位为格。
            Size size) {}

    private record Rule(TagKey<Item> tag, String sound, int ticks, Size size, int priority) {}
}
