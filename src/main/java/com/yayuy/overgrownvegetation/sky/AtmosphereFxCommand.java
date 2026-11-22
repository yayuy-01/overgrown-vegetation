package com.yayuy.overgrownvegetation.sky;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.AtmosphereFxPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * {@code /ov fx …} — 雰囲気の出来事を自分のゲームで確かめる(2026-11-08。G4 胞子の風・G5 光る胞子の夜)。見た目はクライアントだけなので、
 * 打ったプレイヤーのクライアントへ {@link AtmosphereFxPayload} を送る。OP 専用。
 * <ul>
 *     <li>{@code /ov fx gale} — 風上に胞子の風を 1 つ起こす(約 10 秒後に届く。雲の影の前触れはその 4 秒前に通る)。</li>
 *     <li>{@code /ov fx glownight on|off|auto} — 光る胞子の夜を 夜なら起こす / 起こさない / 自然に任せる。</li>
 *     <li>{@code /ov fx status} — 今の様子(チャット)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class AtmosphereFxCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("fx")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("gale").executes(ctx -> send(ctx.getSource(), AtmosphereFxPayload.GALE, 0, "胞子の風を風上に起こした(約 10 秒後に届く)")))
                        .then(Commands.literal("glownight")
                                .then(Commands.literal("on").executes(ctx -> send(ctx.getSource(), AtmosphereFxPayload.GLOW_NIGHT, 1,
                                        "光る胞子の夜: 夜なら起こす(雲の切れ目が無ければ月の柱は出ない)")))
                                .then(Commands.literal("off").executes(ctx -> send(ctx.getSource(), AtmosphereFxPayload.GLOW_NIGHT, 2, "光る胞子の夜: 起こさない")))
                                .then(Commands.literal("auto").executes(ctx -> send(ctx.getSource(), AtmosphereFxPayload.GLOW_NIGHT, 0, "光る胞子の夜: 自然に任せる"))))
                        // フェーズが上がったときの合図だけを流す(フェーズは変えない。11-16)。打った人のクライアントだけ。
                        .then(Commands.literal("phasecue")
                                .then(Commands.argument("phase", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 10))
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayer();
                                            if (player == null) {
                                                ctx.getSource().sendFailure(Component.literal("プレイヤーとして実行してください"));
                                                return 0;
                                            }
                                            int phase = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "phase");
                                            com.yayuy.overgrownvegetation.network.PhaseCuePayload payload =
                                                    com.yayuy.overgrownvegetation.phase.PhaseCues.payloadFor(player,
                                                            com.yayuy.overgrownvegetation.phase.PhaseData.get(ctx.getSource().getServer().overworld()), phase, false);
                                            PacketDistributor.sendToPlayer(player, payload);
                                            ctx.getSource().sendSuccess(() -> Component.literal("フェーズ " + phase + " の合図を流した(森の方角: "
                                                    + (payload.hasForest() ? String.format("%.2f, %.2f", payload.dirX(), payload.dirZ()) : "なし") + ")"), false);
                                            return 1;
                                        })))
                        // 怖い現象 A・D・E の確かめ(11-16。打った人のクライアントだけ・条件を見ずに 1 回)。
                        .then(Commands.literal("shadow").executes(ctx -> send(ctx.getSource(), AtmosphereFxPayload.SCARY_SHADOW, 0,
                                "林冠の影を通す(約 6 秒後に真上あたりを通る。昼・雲を描く設定のときだけ見える)")))
                        .then(Commands.literal("figure").executes(ctx -> send(ctx.getSource(), AtmosphereFxPayload.SCARY_FIGURE, 0,
                                "霧の影を 1 体、見ている向きの霧の縁に出す(濃霧は /ov weather fog 1)")))
                        .then(Commands.literal("farsound").executes(ctx -> send(ctx.getSource(), AtmosphereFxPayload.SCARY_FAR_SOUND, 0,
                                "遠くの音を 1 回鳴らす(見ていない向きから)")))
                        .then(Commands.literal("status").executes(ctx -> send(ctx.getSource(), AtmosphereFxPayload.STATUS, 0, null)))));
    }

    private static int send(CommandSourceStack source, int action, int arg, String message) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("プレイヤーとして実行してください(見た目はそのプレイヤーのクライアントで決まる)"));
            return 0;
        }
        PacketDistributor.sendToPlayer(player, new AtmosphereFxPayload(action, arg));
        if (message != null) {
            source.sendSuccess(() -> Component.literal(message), false);
        }
        return 1;
    }

    private AtmosphereFxCommand() {
    }
}
