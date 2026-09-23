package com.yayuy.overgrownvegetation.data;

import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /ov design reload} — {@code config/overgrown_vegetation/*.json} を読み直す。
 *
 * <p>実行時に参照される捕食表・フェーズ内容は即反映。Mob ステータス・ヒットボックス・デバフの
 * 数値(属性/MobEffect は起動時に確定)は再起動が必要 — その旨を出力する。OP 専用。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class DesignCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("design")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("reload")
                                .executes(DesignCommand::reload))));
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        DesignData.reload();
        // 研究ツリー定義はクライアントにキャッシュされるため、オンライン全員へ再同期する。
        if (ctx.getSource().getServer() != null) {
            com.yayuy.overgrownvegetation.research.ResearchLogic.syncAll(ctx.getSource().getServer());
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.design.reloaded"), true);
        return 1;
    }

    private DesignCommand() {
    }
}
