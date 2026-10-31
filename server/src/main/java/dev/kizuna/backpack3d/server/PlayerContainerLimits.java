package dev.kizuna.backpack3d.server;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.service.TransferService;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
                            Commands.argument(
                                    "limit",
                                    IntegerArgumentType.integer(0, ContainerItems.MAX_WAIST_LIMIT));
                    limitArgument.executes(PlayerContainerLimits::execute);
                    var playersArgument =
                            Commands.argument("players", EntityArgument.players());
                    playersArgument.then(limitArgument);
                    var command =
                            Commands.literal("bs3d")
                                    .requires(
                                            source ->
                                                    source.hasPermission(
                                                            ADMIN_PERMISSION_LEVEL));
                    command.then(Commands.literal("waist-limit").then(playersArgument));
                    dispatcher.register(command);
                });
    }

    private static int execute(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        // 每位玩家独立原子保存，某位失败时明确报告，不修改其已有装备。
        int limit = IntegerArgumentType.getInteger(context, "limit");
        int changed = 0;
        for (ServerPlayer player : EntityArgument.getPlayers(context, "players")) {
            try {
                set(player, limit);
                changed++;
            } catch (IOException | IllegalArgumentException failure) {
                context.getSource()
                        .sendFailure(
                                Component.literal(
                                        player.getName().getString() + "：" + failure.getMessage()));
            }
        }
        int updatedPlayers = changed;
        context.getSource()
                .sendSuccess(
                        () -> Component.literal("已将 " + updatedPlayers + " 位玩家的腰部上限设为 " + limit + " 件"),
                        false);
        return changed;
    }

    public static void set(ServerPlayer player, int limit) throws IOException {
        // 降低到现有装备数以下时拒绝，避免凭空卸装或丢物。
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
