package dev.kizuna.backpack3d;

import dev.kizuna.backpack3d.item.ItemRegistrations;
import net.minecraft.resources.Identifier;

/** 以附属公开清单登记非容器装备，覆盖叠穿、多部位和甲层规则。 */
public final class TestWearables {
    public static final Identifier HEAD = id("head");
    public static final Identifier EYES = id("eyes");
    public static final Identifier FACE = id("face");
    public static final Identifier CAPE = id("cape");
    public static final Identifier WRISTS = id("wrists");
    public static final Identifier LEFT_WRIST = id("left_wrist");
    public static final Identifier SHOULDER = id("shoulder");
    public static final Identifier ARMOR = id("armor");
    private static boolean registered;

    private TestWearables() {}

    public static void register() {
        // 与生产附属一样先校验整份 protobuf，再绑定真实物品的默认组件。
        if (registered) {
            return;
        }
        registered = true;
        ItemRegistrations.registerText("wearable-fixture", """
                version: 1
                items { id: "bs3d_test_wear:head" wearable {
                  title: "头巾" model: "bs3d_test_wear:head" slots { layer: "head" bone: "head" }
                } }
                items { id: "bs3d_test_wear:eyes" wearable {
                  title: "护目" model: "bs3d_test_wear:eyes" slots { layer: "eyes" bone: "head" }
                } }
                items { id: "bs3d_test_wear:face" wearable {
                  title: "面巾" model: "bs3d_test_wear:face" slots { layer: "face" bone: "head" }
                } }
                items { id: "bs3d_test_wear:cape" wearable {
                  title: "披风" model: "bs3d_test_wear:cape"
                  slots { layer: "cape" bone: "head" segment: "hood" }
                  slots { layer: "cape" bone: "chest" segment: "back" }
                } }
                items { id: "bs3d_test_wear:wrists" wearable {
                  title: "双腕" model: "bs3d_test_wear:wrists"
                  slots { layer: "left_wrist" bone: "l_forearm" }
                  slots { layer: "right_wrist" bone: "r_forearm" }
                } }
                items { id: "bs3d_test_wear:left_wrist" wearable {
                  title: "左腕" model: "bs3d_test_wear:left_wrist"
                  slots { layer: "left_wrist" bone: "l_forearm" }
                } }
                items { id: "bs3d_test_wear:shoulder" wearable {
                  title: "肩甲" model: "bs3d_test_wear:shoulder"
                  slots { layer: "left_shoulder" bone: "l_upper_arm" }
                } }
                items { id: "bs3d_test_wear:armor" wearable {
                  title: "身甲" model: "bs3d_test_wear:armor" slots { layer: "armor_body" bone: "chest" }
                } }
                """);
        TestRegistries.bindComponents();
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("bs3d_test_wear", path);
    }
}
