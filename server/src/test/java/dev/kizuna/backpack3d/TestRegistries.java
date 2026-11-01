package dev.kizuna.backpack3d;

import dev.kizuna.backpack3d.item.ContainerItems;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;

/**
 * 单元测试没有服务器实例：引导原版注册表，并补上 26.2 服务器数据重载时才做的物品组件绑定。
 *
 * <p>物品编解码与附魔查找使用原版内置注册表 {@link #VANILLA}。
 */
public final class TestRegistries {
    public static final HolderLookup.Provider VANILLA;

    static {
        // 原版查找表依赖已引导的内置注册表；放在类初始化里，任何测试首次引用都按此顺序。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        VANILLA = VanillaRegistries.createLookup();
    }

    private TestRegistries() {}

    /** 登记本模组容器物品与测试夹具容器并绑定组件，测试即可直接构造 ItemStack。 */
    public static void bootstrap() {
        ContainerItems.initialize();
        TestContainers.register();
        bindComponents();
    }

    /** 26.2 物品默认组件在服务器数据重载时才写入注册项；测试新登记物品后须重新绑定。 */
    public static void bindComponents() {
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                .build(VANILLA)
                .forEach(DataComponentInitializers.PendingComponents::apply);
    }
}
