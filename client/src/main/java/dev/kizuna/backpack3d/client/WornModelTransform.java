package dev.kizuna.backpack3d.client;

import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;

/** UI 命中、预览和世界外观共用身体挂点，所有坐标均在 body 骨架局部系内。 */
final class WornModelTransform {
    private static final float MODEL_UNITS_PER_BLOCK = 16;
    private static final float POUCH_SCALE = 0.5f;
    private static final float BASKET_SCALE = 0.8f;
    // 挂点与半径单位为方块；body 的 y 轴朝下。
    static final float WAIST_X = 0.42f;
    static final float WAIST_Z = 0.29f;
    private static final float WAIST_Y = 0.65f;
    private static final float TORSO_Y = 0.38f;
    private static final float TORSO_Z = 0.36f;
    private static final float FRONT_DEGREES = 180;

    private WornModelTransform() {}

    static void apply(
            MatrixStack matrices,
            ModelAsset asset,
            boolean waist,
            Mount mount,
            float angleDegrees) {
        float scale = (waist ? POUCH_SCALE : BASKET_SCALE) / MODEL_UNITS_PER_BLOCK;
        if (waist) {
            // 挂带顶部贴腰，包体自然下垂；不可把模型中心塞进腰部。
            double angle = Math.toRadians(angleDegrees);
            float centerY = WAIST_Y + asset.topOffset() * scale;
            matrices.translate(Math.sin(angle) * WAIST_X, centerY, -Math.cos(angle) * WAIST_Z);
            matrices.multiply(
                    RotationAxis.POSITIVE_Y.rotationDegrees(FRONT_DEGREES - angleDegrees));
        } else {
            boolean front = mount == Mount.TORSO_FRONT;
            matrices.translate(0, TORSO_Y, front ? -TORSO_Z : TORSO_Z);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(front ? FRONT_DEGREES : 0));
        }
        // 导入资产 y 向上，玩家骨架 y 向下；同时翻转 z 保持右手坐标系。
        matrices.scale(scale, -scale, -scale);
    }
}
