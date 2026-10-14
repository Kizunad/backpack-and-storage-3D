package dev.kizuna.backpack3d.client;

import static dev.kizuna.backpack3d.client.ModelJson.number;
import static dev.kizuna.backpack3d.client.ModelJson.vector;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;

import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 解析已登记的 Blockbench 4.x 立方体模型，几何解析不依赖 OpenGL。 */
record BbModel(
        // 可见立方体保留完整骨骼链，绘制与命中使用同一份几何。
        List<Cube> cubes,
        // 资源包内的纹理路径及 PNG 内容，不包含编辑器本机路径。
        Map<Identifier, byte[]> textures,
        // null 表示该方向没有动画片段，播放层决定静态展示或反放另一方向。
        ModelClip open,
        ModelClip close) {
    static final List<String> FACE_NAMES = List.of("west", "east", "north", "south", "up", "down");
    private static final int MAX_MODEL_BYTES = 16 * 1024 * 1024;
    private static final int MAX_CUBES = 4096;
    private static final int MAX_BONE_DEPTH = 32;
    private static final int QUARTER_TURN_DEGREES = 90;

    record Bone(String id, Vector3f origin, Vector3f rotation) {}

    /** 面纹理及 UV 已归一化；rotation 表示顺时针四分之一圈的数量。 */
    record Face(Identifier texture, float u0, float v0, float u1, float v1, int rotation) {}

    /** 几何、枢轴用模型像素计量，旋转用度；bones 从外层到内层排列。 */
    record Cube(
            Vector3f from,
            Vector3f to,
            Vector3f origin,
            Vector3f rotation,
            List<Bone> bones,
            List<Face> faces) {}

    static BbModel read(Path directory, ModelConfig.Entry entry) throws IOException {
        // 依次读取文档、纹理、几何层级，最后绑定配置选择的动画。
        JsonObject json = readDocument(entry.file());
        Map<Identifier, byte[]> images = ModelTextures.read(directory, entry, json);
        List<Identifier> textureIds = List.copyOf(images.keySet());
        JsonObject resolution = JsonHelper.getObject(json, "resolution");
        float width = number(resolution.get("width"), "resolution.width");
        float height = number(resolution.get("height"), "resolution.height");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("纹理分辨率必须大于零");
        }
        Map<String, JsonObject> elements = readElements(json);
        // 几何阶段只处理层级与可见性，动画随后按同一套骨骼身份绑定。
        var geometry = new GeometryReader(elements, textureIds, width, height);
        geometry.read(JsonHelper.getArray(json, "outliner"));
        Map<String, JsonObject> animations = readAnimations(json);
        ModelClip open = selectClip(animations, geometry.bones, entry.openAnimation(), "open");
        ModelClip close = selectClip(animations, geometry.bones, entry.closeAnimation(), "close");
        return new BbModel(List.copyOf(geometry.cubes), Map.copyOf(images), open, close);
    }

    private static JsonObject readDocument(Path file) throws IOException {
        JsonObject json;
        // 先限制输入大小再读取，错误由目录层补上模型 ID 和文件路径。
        if (Files.size(file) > MAX_MODEL_BYTES) {
            throw new IllegalArgumentException("bbmodel 超过 16 MiB");
        }
        try (var reader = Files.newBufferedReader(file)) {
            json = JsonParser.parseReader(reader).getAsJsonObject();
        }
        String version = JsonHelper.getString(JsonHelper.getObject(json, "meta"), "format_version");
        if (!version.startsWith("4.")) {
            throw new IllegalArgumentException("仅支持 Blockbench 4.x bbmodel，当前为 " + version);
        }
        return json;
    }

    private static Map<String, JsonObject> readElements(JsonObject json) {
        // 按元素 UUID 建立索引，outliner 只负责指定父子关系。
        Map<String, JsonObject> elements = new LinkedHashMap<>();
        for (JsonElement element : JsonHelper.getArray(json, "elements")) {
            JsonObject cube = element.getAsJsonObject();
            String id = JsonHelper.getString(cube, "uuid");
            if (elements.putIfAbsent(id, cube) != null) {
                throw new IllegalArgumentException("重复立方体 UUID：" + id);
            }
        }
        if (elements.size() > MAX_CUBES) {
            throw new IllegalArgumentException("模型超过 4096 个元素");
        }
        return elements;
    }

    private static Map<String, JsonObject> readAnimations(JsonObject json) {
        // 名称是配置的引用身份，重复名称不能悄悄覆盖前一个片段。
        Map<String, JsonObject> animations = new LinkedHashMap<>();
        for (JsonElement element : JsonHelper.getArray(json, "animations", new JsonArray())) {
            JsonObject animation = element.getAsJsonObject();
            String name = JsonHelper.getString(animation, "name");
            if (animations.putIfAbsent(name, animation) != null) {
                throw new IllegalArgumentException("重复动画名：" + name);
            }
        }
        return animations;
    }

    private static ModelClip selectClip(
            Map<String, JsonObject> clips,
            Set<String> bones,
            String configured,
            String conventional) {
        // 未配置时仅自动寻找约定名称；显式写错名称则报错，空字符串可禁用该方向。
        String name = configured == null ? conventional : configured;
        if (name.isEmpty()) {
            return null;
        }
        JsonObject clip = clips.get(name);
        if (clip == null && configured != null) {
            throw new IllegalArgumentException("找不到配置的动画：" + name);
        }
        return clip == null ? null : ModelClip.read(clip, bones);
    }

    /** 单次解析的状态集中保存，递归时只传递当前骨骼链和继承可见性。 */
    private static final class GeometryReader {
        private final Map<String, JsonObject> elements;
        private final List<Identifier> textures;
        private final float width;
        private final float height;
        private final Set<String> bones = new HashSet<>();
        private final Set<String> visited = new HashSet<>();
        private final List<Cube> cubes = new ArrayList<>();

        GeometryReader(
                Map<String, JsonObject> elements,
                List<Identifier> textures,
                float width,
                float height) {
            this.elements = elements;
            this.textures = textures;
            this.width = width;
            this.height = height;
        }

        void read(JsonArray roots) {
            // 元素必须恰好属于一个层级位置，避免重复绘制或静默丢失模型部件。
            readTree(roots, List.of(), true);
            if (!visited.equals(elements.keySet()) || cubes.isEmpty()) {
                throw new IllegalArgumentException("outliner 缺少元素或模型没有可见立方体");
            }
        }

        private void readTree(JsonArray nodes, List<Bone> parents, boolean visible) {
            // 隐藏组仍校验身份，但不生成可渲染几何。
            if (parents.size() > MAX_BONE_DEPTH) {
                throw new IllegalArgumentException("分组嵌套超过 32 层");
            }
            for (JsonElement node : nodes) {
                if (node.isJsonPrimitive()) {
                    readElement(node.getAsString(), parents, visible);
                } else {
                    readGroup(node.getAsJsonObject(), parents, visible);
                }
            }
        }

        private void readElement(String id, List<Bone> parents, boolean visible) {
            JsonObject element = elements.get(id);
            if (element == null || !visited.add(id)) {
                throw new IllegalArgumentException("outliner 引用缺失或重复元素：" + id);
            }
            // 显示条件从根组传入；每个立方体保留完整父链，供动画和命中共用。
            if (visible && shown(element)) {
                cubes.add(readCube(element, parents, textures, width, height));
            }
        }

        private void readGroup(JsonObject group, List<Bone> parents, boolean visible) {
            String id = JsonHelper.getString(group, "uuid");
            if (!bones.add(id)) {
                throw new IllegalArgumentException("重复骨骼 UUID：" + id);
            }
            // 复制链条后再加入当前组，兄弟节点不会共享可变的父链。
            var chain = new ArrayList<>(parents);
            chain.add(new Bone(id, vector(group, "origin"), vector(group, "rotation")));
            readTree(
                    JsonHelper.getArray(group, "children"),
                    List.copyOf(chain),
                    visible && shown(group));
        }
    }

    private static boolean shown(JsonObject element) {
        return JsonHelper.getBoolean(element, "visibility", true)
                && JsonHelper.getBoolean(element, "export", true);
    }

    private static Cube readCube(
            JsonObject element,
            List<Bone> bones,
            List<Identifier> textures,
            float width,
            float height) {
        // 网格、box UV 及 rescale 需要其他渲染语义，拒绝比错画更容易定位资源问题。
        if (!JsonHelper.getString(element, "type", "cube").equals("cube")
                || JsonHelper.getBoolean(element, "box_uv", false)
                || JsonHelper.getBoolean(element, "rescale", false)
                || JsonHelper.getFloat(element, "inflate", 0) != 0) {
            throw new IllegalArgumentException("仅支持逐面 UV 的 cube，不支持 mesh/box_uv/rescale/inflate");
        }
        Vector3f from = vector(element, "from");
        Vector3f to = vector(element, "to");
        if (from.x > to.x || from.y > to.y || from.z > to.z) {
            throw new IllegalArgumentException("cube.from 不能大于 cube.to");
        }
        JsonObject faces = JsonHelper.getObject(element, "faces");
        List<Face> result = new ArrayList<>();
        for (String name : FACE_NAMES) {
            JsonObject face = JsonHelper.getObject(faces, name, new JsonObject());
            JsonElement texture = face.get("texture");
            if (texture == null || texture.isJsonNull()) {
                result.add(null);
                continue;
            }
            float index = number(texture, name + ".texture");
            if (index != (int) index || index < 0 || index >= textures.size()) {
                throw new IllegalArgumentException("面引用未知纹理：" + name);
            }
            var uv = JsonHelper.getArray(face, "uv");
            if (uv.size() != 4) {
                throw new IllegalArgumentException(name + ".uv 必须包含四个数值");
            }
            int rotation = JsonHelper.getInt(face, "rotation", 0);
            if (rotation % QUARTER_TURN_DEGREES != 0) {
                throw new IllegalArgumentException("UV 旋转必须为 90 度的整数倍");
            }
            result.add(
                    new Face(
                            textures.get((int) index),
                            number(uv.get(0), "uv.u0") / width,
                            number(uv.get(1), "uv.v0") / height,
                            number(uv.get(2), "uv.u1") / width,
                            number(uv.get(3), "uv.v1") / height,
                            Math.floorMod(rotation / QUARTER_TURN_DEGREES, 4)));
        }
        return new Cube(
                from,
                to,
                vector(element, "origin"),
                vector(element, "rotation"),
                bones,
                Collections.unmodifiableList(result));
    }
}
