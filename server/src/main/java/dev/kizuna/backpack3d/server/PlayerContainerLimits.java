package dev.kizuna.backpack3d.server;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.service.TransferService;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.IOException;

/** 服务端管理玩家腰部上限；普通客户端协议没有改写入口。 */
public final class PlayerContainerLimits {
    private static final int ADMIN_PERMISSION_LEVEL = 2;

    private PlayerContainerLimits() {}

    public static void register() {
        // 按参数从内向外组装命令，执行逻辑单独处理，避免深层 lambda 掩盖错误处理。
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registries, environment) -> {
                    var limitArgument =
                            CommandManager.argument(
                                    "limit",
                                    IntegerArgumentType.integer(0, ContainerItems.MAX_WAIST_LIMIT));
                    limitArgument.executes(PlayerContainerLimits::execute);
                    var playersArgument =
                            CommandManager.argument("players", EntityArgumentType.players());
                    playersArgument.then(limitArgument);
                    var command =
                            CommandManager.literal("bs3d")
                                    .requires(
                                            source ->
                                                    source.hasPermissionLevel(
                                                            ADMIN_PERMISSION_LEVEL));
                    command.then(CommandManager.literal("waist-limit").then(playersArgument));
                    dispatcher.register(command);
                });
    }

    private static int execute(CommandContext<ServerCommandSource> context)
            throws CommandSyntaxException {
        // 每位玩家独立原子保存，某位失败时明确报告，不修改其已有装备。
        int limit = IntegerArgumentType.getInteger(context, "limit");
        int changed = 0;
        for (ServerPlayerEntity player : EntityArgumentType.getPlayers(context, "players")) {
            try {
                set(player, limit);
                changed++;
            } catch (IOException | IllegalArgumentException failure) {
                context.getSource()
                        .sendError(
                                Text.literal(
                                        player.getName().getString() + "：" + failure.getMessage()));
            }
        }
        int updatedPlayers = changed;
        context.getSource()
                .sendFeedback(
                        () -> Text.literal("已将 " + updatedPlayers + " 位玩家的腰部上限设为 " + limit + " 件"),
                        false);
        return changed;
    }

    public static void set(ServerPlayerEntity player, int limit) throws IOException {
        // 不抢占计时事务；降低到低于现有装备数时拒绝，避免凭空卸装或丢物。
        if (VolumeServer.locked(player)) {
            throw new IllegalArgumentException("请等待当前转移结束");
        }
        var account = new PlayerAccount(player);
        var next =
                account.data().toBuilder()
                        .setWaistLimit(limit)
                        .setRevision(Math.addExact(account.data().getRevision(), 1))
                        .build();
        TransferService.validate(next);
        account.commit(next, account.inventory());
    }
}
