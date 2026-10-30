package com.yayuy.overgrownvegetation.siege;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 攻城戦の動作確認用コマンド(OP 専用)。
 *
 * {@code /ov siege start} — 実行者の現在位置を中心に攻城戦を開始する。
 * {@code /ov siege stop} — 進行中の攻城戦を終了する。
 * {@code /ov siege status} — 中心・半径・現在位置・距離・体力を表示する。
 * {@code /ov siege damage <量>} — ボスに直接ダメージを与える(動作確認用)。
 * {@code /ov siege mortar} — 実行地点へ胞子爆弾を 1 斉射させる。
 * {@code /ov siege barrage} — 実行地点の方角へ触手の小ビームの乱射を始めさせる。
 * {@code /ov siege shoot} — 視線の向きへ砲弾を撃つ(砲台なしの動作確認用)。
 * {@code /ov siege snipe} — ボスへ偏差射撃で砲弾を撃つ(命中判定の確認用)。
 *
 * <p>{@code RaidCommand} と同じく最上位の {@code overgrownvegetation} リテラルを別途 register するが、
 * Brigadier は同名リテラルの子ノードをマージするので共存する。
 * {@code overgrownvegetation siege ...} は OP 専用({@code hasPermission(2)})。
 *
 * <p>以前あった権限なしの {@code /siege}(フェーズ7突入の仮トリガー、2026-09-17)は、F7 以降もポイントで
 * 自動昇格するようになったので 2026-09-26 に削除した(ユーザー指定)。攻城戦は
 * {@code PhaseData#advanceToPhase} → {@link SiegeData#onPhaseAdvanced} の経路だけで始まる。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SiegeCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("siege")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("start").executes(SiegeCommand::start))
                        .then(Commands.literal("stop").executes(SiegeCommand::stop))));
    }

    /** {@code siege beam} — 射程の条件を無視して、実行地点へ花のビーム(遠距離砲撃)を撃ち始める。 */

    /** {@code siege tentacle} — 実エンティティが居れば、実行地点へ触手ビームを撃ち始める。 */

    /** {@code siege mortar} — 間隔や頭のビームの条件を無視して、実行地点へ胞子爆弾を 1 斉射させる。 */

    /** {@code siege barrage} — 実行地点の方角へ、触手の小ビームの乱射を始めさせる。 */

    private static int start(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel overworld = source.getServer().overworld();
        if (com.yayuy.overgrownvegetation.difficulty.Peaceful.is(overworld)) {
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.peaceful_blocked"));
            return 0;
        }
        BlockPos center = BlockPos.containing(source.getPosition());
        if (SiegeData.get(overworld).start(overworld, center)) {
            source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.siege.started",
                    center.getX(), center.getY(), center.getZ()), true);
            return 1;
        }
        source.sendFailure(Component.translatable("commands.overgrown_vegetation.siege.already_active"));
        return 0;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        SiegeData data = SiegeData.get(overworld);
        if (!data.isActive()) {
            ctx.getSource().sendFailure(Component.translatable("commands.overgrown_vegetation.siege.not_active"));
            return 0;
        }
        data.stop(overworld, false);
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.siege.stopped"), true);
        return 1;
    }

    private SiegeCommand() {
    }
}
