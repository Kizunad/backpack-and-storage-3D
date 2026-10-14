package dev.kizuna.backpack3d.server;

import dev.kizuna.backpack3d.protocol.Messages;
import dev.kizuna.backpack3d.service.TransferService;
import dev.kizuna.inventoryui.server.ServerModuleRegistry;
import dev.kizuna.inventoryui.server.fabric.FabricServerModule;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resource.ResourceType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.*;

/** Framework 独占通道，业务只管理每连接计时事务与物品规则。 */
public final class VolumeServer implements ModInitializer, FabricServerModule {
    public static final HandlingRules RULES = new HandlingRules();
    private static final Set<VolumeSession> SESSIONS = new HashSet<>();

    @Override
    public void onInitialize() {
        PlayerContainerLimits.register();
        GroundInventory.register();
        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(RULES);
        ServerTickEvents.END_SERVER_TICK.register(
                server -> {
                    // 关闭回调可能移除会话，遍历快照避免同 tick 的回收影响其他玩家。
                    for (var session : List.copyOf(SESSIONS)) {
                        if (session.server() == server) {
                            session.tick();
                        }
                    }
                });
    }

    @Override
    public void register(
            MinecraftServer server, ServerModuleRegistry<ServerPlayerEntity> registry) {
        var service = new TransferService(stack -> RULES.material(stack).size());
        registry.register(
                Messages.MODULE,
                Messages.capabilities(),
                connection -> {
                    // 工厂此时尚未完成握手，构造阶段不读取玩家或发送状态。
                    var session = new VolumeSession(server, connection, service, RULES);
                    SESSIONS.add(session);
                    return session;
                });
    }

    static void forget(VolumeSession session) {
        SESSIONS.remove(session);
    }

    public static boolean locked(ServerPlayerEntity player) {
        return SESSIONS.stream().anyMatch(session -> session.locks(player));
    }

    public static VolumeSession session(ServerPlayerEntity player) {
        // 原版右键也必须通过已协商的业务连接打开，缺少客户端模块时明确报错。
        var session = findSession(player);
        if (session == null) {
            throw new IllegalArgumentException("请等待背包模块连接就绪");
        }
        return session;
    }

    static VolumeSession findSession(ServerPlayerEntity player) {
        // 附属可以在 BS3D 握手前读取个人库存，但不能提前取得地面容器访问权。
        return SESSIONS.stream()
                .filter(session -> session.belongsTo(player))
                .findFirst()
                .orElse(null);
    }
}
