package com.yayuy.overgrownvegetation.td;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.ecosystem.EcosystemData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;

/**
 * {@code /ov td …} — TD イベント(森の侵攻)の確認と操作(OP、2026-09-25)。
 * <ul>
 *     <li>(引数なし)/ {@code status} … 状態・ターゲット・予算・仮想の群れ・生きている攻め手・始まりの条件</li>
 *     <li>{@code start [向き] [予算]} … 打った場所にいちばん近い、読み込まれているターゲットブロックで今すぐ侵攻を始める
 *     (予告を飛ばす。条件も見ない)。予算を省くと世界のバイオマスを全部切り出す(中止・負けで返す)。予算を決めたときは
 *     世界のバイオマスに触らない(返さない)。向き({@code north} / {@code northeast} / {@code east} / … / {@code northwest} の 8 方位か、
 *     全方位の {@code around}。2026-09-26 のユーザー指定「特定の方向から攻めてくるのをコマンドで指定できるように。
 *     例えば /ov td start north」)を省くと、自動の侵攻と同じく振る(全方位 4 割・一方向 6 割)</li>
 *     <li>{@code warn} … 打った場所にいちばん近い、読み込まれているターゲットブロックで<b>予告</b>から始める(条件は見ない。
 *     あとは自動の侵攻と同じ: 予告の知らせ・近くの基地の見張り台の知らせ・{@code warningTicks} 後に侵攻。2026-10-08 順 2 の着手順 8)</li>
 *     <li>{@code stop} … 進行中のイベントを中止する(払い戻す)</li>
 *     <li>{@code budget <n>} … 侵攻中のイベントの予算を決める(勝ちを試すときに 0 など。中止・負けのときはこの値が返る)</li>
 * </ul>
 *
 * <p>出力は開発用なので {@code Component.literal}(翻訳キーを作らない。{@code EcosystemCommand} と同じ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class TdCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> start = Commands.literal("start")
                .executes(ctx -> start(ctx, null, null));
        for (TdDirection direction : TdDirection.values()) {
            start.then(Commands.literal(direction.key())
                    .executes(ctx -> start(ctx, null, direction)));
        }
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("td")
                        .requires(source -> source.hasPermission(2))
                        .then(start)
                        .then(Commands.literal("stop").executes(TdCommand::stop))));
    }

    /**
     * @param direction 攻めてくる向き(null = 自動の侵攻と同じく振る)
     */
    private static int start(CommandContext<CommandSourceStack> ctx, @Nullable Double budget, @Nullable TdDirection direction) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        if (com.yayuy.overgrownvegetation.difficulty.Peaceful.is(level)) {
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.peaceful_blocked"));
            return 0;
        }
        if (TdData.get(source.getServer()).live() != null) {
            source.sendFailure(Component.literal("[TD] もう侵攻が進行中です(/ov td stop で止める)"));
            return 0;
        }
        BlockPos target = nearestTarget(level, source.getPosition());
        if (target == null) {
            source.sendFailure(Component.literal("[TD] このディメンションに読み込まれているターゲットブロックがありません"));
            return 0;
        }
        if (!TdTicker.forceStart(level, target, budget, direction)) {
            source.sendFailure(Component.literal("[TD] 始められませんでした"));
            return 0;
        }
        TdData.Event live = TdData.get(source.getServer()).live();
        TdDirection chosen = live != null ? live.direction() : TdDirection.AROUND;
        send(source, "§a[TD]§r " + target.getX() + ", " + target.getY() + ", " + target.getZ() + " で侵攻を始めた(予算 "
                + (budget != null ? String.format("%.0f", budget) + "・払い戻しなし" : "= 世界のバイオマス全部")
                + "・向き " + describe(chosen) + (direction == null ? "(振った)" : "") + ")");
        return 1;
    }

    /** 打った場所にいちばん近い、読み込まれているターゲットブロックで予告から始める(条件は見ない。あとは自動の侵攻と同じ)。 */

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!TdTicker.forceStop(source.getServer())) {
            source.sendFailure(Component.literal("[TD] 進行中の侵攻はありません"));
            return 0;
        }
        send(source, "§a[TD]§r 侵攻を中止した");
        return 1;
    }

    @Nullable
    private static BlockPos nearestTarget(ServerLevel level, Vec3 from) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : TdTargets.loaded(level)) {
            double sq = pos.distToCenterSqr(from);
            if (sq < bestSq) {
                bestSq = sq;
                best = pos;
            }
        }
        return best;
    }

    /** 攻めてくる向きの説明(全方位 /「北から」)。 */
    private static String describe(TdDirection direction) {
        return direction.around() ? "全方位" : direction.japanese() + "から";
    }

    private static void send(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    private TdCommand() {
    }
}
