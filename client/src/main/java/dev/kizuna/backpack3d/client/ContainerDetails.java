package dev.kizuna.backpack3d.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 附属为总览和容器补充短文本；布局与主题仍由 BS3D 控制。 */
public final class ContainerDetails {
    private static final Map<ResourceLocation, Provider> PROVIDERS = new LinkedHashMap<>();

    private ContainerDetails() {}

    public static void register(ResourceLocation id, Provider provider) {
        // 注册发生在客户端初始化阶段，重复身份明确失败，避免一个附属覆盖另一个。
        Objects.requireNonNull(id);
        Objects.requireNonNull(provider);
        if (PROVIDERS.putIfAbsent(id, provider) != null) {
            throw new IllegalArgumentException("重复容器信息提供者：" + id);
        }
    }

    static Component summary() {
        // 没有信息时返回空文本，基础模组不因此增加面板或占位。
        var text = Component.empty();
        for (var provider : PROVIDERS.values()) {
            append(text, provider.summary());
        }
        return text;
    }

    static Component container(String id) {
        // 只聚合指定实例的信息，同类型的两个背包不能共用一份明细。
        var text = Component.empty();
        for (var provider : PROVIDERS.values()) {
            append(text, provider.container(id));
        }
        return text;
    }

    private static void append(net.minecraft.network.chat.MutableComponent target, Component detail) {
        // 附属返回短文本，由宿主统一分隔，空文本不留下空白条。
        if (!detail.getString().isBlank()) {
            if (!target.getString().isEmpty()) {
                target.append(" · ");
            }
            target.append(detail);
        }
    }

    public interface Provider {
        Component summary();

        /** 参数是容器实例 UUID；未知或尚未同步时返回空文本。 */
        Component container(String containerId);
    }
}
