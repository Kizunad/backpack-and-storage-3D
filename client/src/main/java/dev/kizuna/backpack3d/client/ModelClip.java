package dev.kizuna.backpack3d.client;

import static dev.kizuna.backpack3d.client.ModelJson.number;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.util.JsonHelper;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Blockbench 骨骼关键帧：位置为模型像素，旋转为度，缩放为倍率，时间为秒。 */
record ModelClip(
        // 动画片段时长，单位为秒。
        float seconds,
        // 骨骼 UUID → 通道名称 → 按时间排列的关键帧。
        Map<String, Map<String, List<ModelClip.Key>>> tracks) {
    private static final float MAX_CLIP_SECONDS = 60;
    private static final Set<String> CHANNELS = Set.of("position", "rotation", "scale");

    record Key(float time, Vector3f value, boolean step) {}

    static ModelClip read(JsonObject json, Set<String> bones) {
        float seconds = number(json.get("length"), "animation.length");
        if (seconds <= 0 || seconds > MAX_CLIP_SECONDS) {
            throw new IllegalArgumentException("animation.length 必须为 (0, 60] 秒");
        }
        // 开合是一次性状态转换；循环动画、表达式及效果轨道不作静默近似。
        String loop = JsonHelper.getString(json, "loop", "once");
        if (!Set.of("once", "hold").contains(loop)) {
            throw new IllegalArgumentException("开合动画只支持 once/hold：" + loop);
        }
        for (String field :
                List.of("anim_time_update", "blend_weight", "start_delay", "loop_delay")) {
            String expression = JsonHelper.getString(json, field, "");
            if (!expression.isBlank()) {
                throw new IllegalArgumentException("不支持动画表达式：" + field);
            }
        }
        Map<String, Map<String, List<Key>>> tracks = new LinkedHashMap<>();
        var animators = JsonHelper.getObject(json, "animators", new JsonObject());
        for (var entry : animators.entrySet()) {
            String bone = entry.getKey();
            // 先确认目标骨骼，效果轨道或未知身份不进入关键帧解析。
            if (!bones.contains(bone)) {
                throw new IllegalArgumentException("动画引用未知骨骼或效果轨道：" + bone);
            }
            JsonObject animator = entry.getValue().getAsJsonObject();
            tracks.put(bone, readBoneTrack(bone, animator, seconds));
        }
        return new ModelClip(seconds, Map.copyOf(tracks));
    }

    private static Map<String, List<Key>> readBoneTrack(
            String bone, JsonObject animator, float seconds) {
        // 每个骨骼按位置、旋转和缩放通道分别收集关键帧，随后统一排序。
        if (!JsonHelper.getString(animator, "type", "bone").equals("bone")) {
            throw new IllegalArgumentException("仅支持 bone 动画轨道：" + bone);
        }
        Map<String, List<Key>> channels = new LinkedHashMap<>();
        for (JsonElement element : JsonHelper.getArray(animator, "keyframes")) {
            JsonObject frame = element.getAsJsonObject();
            String channel = JsonHelper.getString(frame, "channel");
            if (!CHANNELS.contains(channel)) {
                throw new IllegalArgumentException("不支持动画通道：" + channel);
            }
            Key key = readKey(frame, seconds, channel);
            channels.computeIfAbsent(channel, ignored -> new ArrayList<>()).add(key);
        }
        Map<String, List<Key>> sorted = new LinkedHashMap<>();
        for (var channel : channels.entrySet()) {
            sorted.put(channel.getKey(), sortedKeys(channel.getValue(), bone));
        }
        return Map.copyOf(sorted);
    }

    private static List<Key> sortedKeys(List<Key> keys, String bone) {
        // 采样依赖严格递增时间；同一时刻的两个值没有明确的先后关系。
        keys.sort(Comparator.comparingDouble(Key::time));
        for (int index = 1; index < keys.size(); index++) {
            if (keys.get(index - 1).time() == keys.get(index).time()) {
                throw new IllegalArgumentException("同一骨骼通道存在重复时间：" + bone);
            }
        }
        return List.copyOf(keys);
    }

    private static Key readKey(JsonObject frame, float duration, String channel) {
        // 时间、插值和轴值都有效才接纳关键帧，避免播放时才出现无法采样的轨道。
        float time = number(frame.get("time"), "keyframe.time");
        if (time < 0 || time > duration) {
            throw new IllegalArgumentException("关键帧时间越出动画时长");
        }
        String interpolation = JsonHelper.getString(frame, "interpolation", "linear");
        if (!interpolation.equals("linear") && !interpolation.equals("step")) {
            throw new IllegalArgumentException("只支持 linear/step 插值：" + interpolation);
        }
        var points = JsonHelper.getArray(frame, "data_points");
        if (points.size() != 1) {
            throw new IllegalArgumentException("每个关键帧需要一个 data_point");
        }
        var point = points.get(0).getAsJsonObject();
        Vector3f value =
                new Vector3f(
                        number(point.get("x"), channel + ".x"),
                        number(point.get("y"), channel + ".y"),
                        number(point.get("z"), channel + ".z"));
        if (channel.equals("scale") && (value.x <= 0 || value.y <= 0 || value.z <= 0)) {
            throw new IllegalArgumentException("动画缩放必须大于零");
        }
        return new Key(time, value, interpolation.equals("step"));
    }

    Vector3f sample(String bone, String channel, float progress) {
        // 边界保持首末帧；单帧轨道也能表示固定姿态。
        var channels = tracks.get(bone);
        List<Key> keys = channels == null ? null : channels.get(channel);
        if (keys == null || keys.isEmpty()) {
            return new Vector3f(channel.equals("scale") ? 1 : 0);
        }
        float time = Math.max(0, Math.min(1, progress)) * seconds;
        Key previous = keys.get(0);
        if (time <= previous.time()) {
            return new Vector3f(previous.value());
        }
        for (int index = 1; index < keys.size(); index++) {
            Key next = keys.get(index);
            if (time < next.time()) {
                if (previous.step()) {
                    return new Vector3f(previous.value());
                }
                float factor = (time - previous.time()) / (next.time() - previous.time());
                return new Vector3f(previous.value()).lerp(next.value(), factor);
            }
            previous = next;
        }
        return new Vector3f(previous.value());
    }
}
