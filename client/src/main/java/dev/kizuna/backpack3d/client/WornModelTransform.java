package dev.kizuna.backpack3d.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;

/** UI 命中、预览和世界外观共用身体挂点，所有坐标均在 body 骨架局部系内。 */
public final class WornModelTransform {
    private static final float MODEL_UNITS_PER_BLOCK = 16;
    // 挂点与半径单位为方块；body 的 y 轴朝下。
    static final float WAIST_X = 0.42f;
    static final float WAIST_Z = 0.29f;
    private static final float WAIST_Y = 0.65f;
    private static final float TORSO_Y = 0.38f;
    private static final float TORSO_Z = 0.36f;
    // 整圈腰带按人体坐标建模：脚底 y=0，肩顶即 body 原点，高 24 模型像素。
    private static final float BODY_TOP_PIXELS = 24;
    private static final float FRONT_DEGREES = 180;

    private WornModelTransform() {}

    /** 容器在身上的贴合方式，由类型决定，与具体挂点和腰角无关。 */
    public enum Fit {
        // 胸挂、背负：按挂点贴在躯干前后。
        TORSO(0.8f),
        // 挂包：挂带顶贴腰、包体下垂，沿腰环按角度摆放。
        WAIST_HANGING(0.5f),
        // 整圈腰带：1:1 绕腰一圈、正面朝前，忽略腰角。
        WAIST_RING(1);

        private final float blocksPerModelUnit;

        Fit(float modelScale) {
            blocksPerModelUnit = modelScale / MODEL_UNITS_PER_BLOCK;
        }

        public static Fit of(ContainerItems.Definition type) {
            if (!type.waist()) {
                return TORSO;
            }
            if (type.waistRing()) {
                return WAIST_RING;
            }
            return WAIST_HANGING;
        }

        /** 每个模型像素画成多少格；地面与身上用同一比例，导入时已按它补偿，画出来是原尺寸。 */
        public float blocksPerModelUnit() {
            return blocksPerModelUnit;
        }
    }

    /** 附属复用同一容器挂点来计算共穿边界，输入矩阵须已处于身体挂点空间。 */
    public static void apply(
            PoseStack matrices, ModelAsset asset, ContainerItems.Definition type, Mount mount, float angleDegrees) {
        apply(matrices, asset, Fit.of(type), mount, angleDegrees);
    }

    static void apply(PoseStack matrices, ModelAsset asset, Fit fit, Mount mount, float angleDegrees) {
        float scale = fit.blocksPerModelUnit();
        switch (fit) {
            case TORSO -> torso(matrices, mount);
            case WAIST_HANGING -> hanging(matrices, asset.topOffset() * scale, angleDegrees);
            case WAIST_RING -> ring(matrices, asset.topOffset() * scale);
        }
        // 导入资产 y 向上，玩家骨架 y 向下；同时翻转 z 保持右手坐标系。
        matrices.scale(scale, -scale, -scale);
    }

    private static void torso(PoseStack matrices, Mount mount) {
        boolean front = mount == Mount.TORSO_FRONT;
        matrices.translate(0, TORSO_Y, front ? -TORSO_Z : TORSO_Z);
        matrices.mulPose(Axis.YP.rotationDegrees(front ? FRONT_DEGREES : 0));
    }

    private static void hanging(PoseStack matrices, float topOffsetBlocks, float angleDegrees) {
        // 挂带顶部贴腰，包体自然下垂；不可把模型中心塞进腰部。
        double angle = Math.toRadians(angleDegrees);
        matrices.translate(Math.sin(angle) * WAIST_X, WAIST_Y + topOffsetBlocks, -Math.cos(angle) * WAIST_Z);
        matrices.mulPose(Axis.YP.rotationDegrees(FRONT_DEGREES - angleDegrees));
    }

    private static void ring(PoseStack matrices, float topOffsetBlocks) {
        // 模型中心离脚底 topOffset 像素，放到肩顶下方 24−topOffset 像素；
        // 转 180° 再经下面的缩放，x 取反、z 不变，与原人体模型逐点重合。
        matrices.translate(0, BODY_TOP_PIXELS / MODEL_UNITS_PER_BLOCK - topOffsetBlocks, 0);
        matrices.mulPose(Axis.YP.rotationDegrees(FRONT_DEGREES));
    }
}
