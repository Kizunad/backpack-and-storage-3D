package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.kizuna.backpack3d.registry.pb.ItemRegistryProto.ModelRenderMode;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * 其他模组随 JAR 分发、画在方块位置或物品上的 .bbmodel。
 *
 * <p>附属在客户端入口里用 {@link #register} 登记模型，再用 {@link #renderBlockEntity} 把方块实体接到模型上；
 * 物品定义用 {@code minecraft:special} 引用 {@code bs3d:placed}。模型按原坐标绘制，不居中、不缩放，
 * 登记时给出的 {@code origin} 是落在方块底面中心的模型点（单位为模型像素）。格式限制与 config.toml 导入相同；
 * 玩家在 config.toml 里用同一 ID 登记的模型会覆盖几何和贴图，原点仍用这里登记的。
 */
public final class PlacedModels {
    private static final float BLOCK_CENTER = 0.5f;
    private static final float MODEL_PIXELS_PER_BLOCK = 16;
    private static final float FRONT_YAW_DEGREES = 180;
    private static final String MODEL_SUFFIX = ".bbmodel";
    private static final Map<Identifier, Registration> REGISTERED = new LinkedHashMap<>();

    record Registration(Identifier id, Path file, Vector3fc origin) {}

    /** 一次绘制要用的几何与原点；模型随资源重载整体替换，每帧重新查。 */
    record Placed(ModelAsset asset, Vector3fc origin) {}

    private PlacedModels() {}

    /**
     * 登记模组 JAR 内的模型。path 相对 JAR 根目录；外部贴图要和模型放在同一目录下。
     * 必须在客户端入口里调用，首次扫描资源包时一并加载。
     */
    public static void register(ModContainer mod, Identifier id, String path, Vector3fc origin) {
        String source = mod.getMetadata().getId() + ":" + path;
        if (!path.endsWith(MODEL_SUFFIX)) {
            throw new IllegalArgumentException(source + "：模型必须是 .bbmodel");
        }
        Path file = mod.findPath(path)
                .orElseThrow(() -> new IllegalArgumentException(source + "：模组内找不到模型文件"));
        add(new Registration(id, file, new Vector3f(origin)));
    }

    /** 让一种方块实体按方块状态画对应的放置模型；有水平朝向属性时随朝向转动，模型正面朝 -Z。 */
    public static <E extends BlockEntity> void renderBlockEntity(
            BlockEntityType<E> type, Function<BlockState, Identifier> model) {
        BlockEntityRendererRegistry.register(type, context -> new PlacedBlockRenderer<E>(model));
    }

    static void add(Registration registration) {
        // 同一 ID 只能由一个模组登记，不能静默顶掉别人的模型。
        Registration previous = REGISTERED.putIfAbsent(registration.id(), registration);
        if (previous != null) {
            throw new IllegalArgumentException("重复登记放置模型：" + registration.id());
        }
    }

    static List<ModelConfig.Entry> entries() {
        // 放置模型没有开合动画和动作音效，按原坐标分段模式加载。
        return REGISTERED.values().stream()
                .map(registration -> new ModelConfig.Entry(registration.id(), registration.file(), "", "", Map.of(),
                        ModelRenderMode.MODEL_RENDER_MODE_RAW_SEGMENTS))
                .toList();
    }

    static Placed find(Identifier id) {
        // 只画经 register 登记过的模型；没登记或导入失败都只警告一次，不画。
        Registration registration = REGISTERED.get(id);
        if (registration == null) {
            ModelResources.warnMissing(id.toString());
            return null;
        }
        ModelAsset asset = ModelResources.find(id.toString());
        if (asset == null) {
            return null;
        }
        return new Placed(asset, registration.origin());
    }

    static void place(PoseStack matrices, Placed placed, Direction facing) {
        // 先把模型转到方块朝向，再把登记的原点放到方块底面中心。
        matrices.translate(BLOCK_CENTER, 0, BLOCK_CENTER);
        matrices.mulPose(Axis.YP.rotationDegrees(FRONT_YAW_DEGREES - facing.toYRot()));
        float scale = 1 / MODEL_PIXELS_PER_BLOCK;
        matrices.scale(scale, scale, scale);
        Vector3fc origin = placed.origin();
        matrices.translate(-origin.x(), -origin.y(), -origin.z());
    }
}
