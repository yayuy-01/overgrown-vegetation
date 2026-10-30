package com.yayuy.overgrownvegetation.corruption;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.ClimaxTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.spore.Erosion;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /ov age <日数>} — <b>汚染の暦(台帳の齢)だけを進めて</b>そのまま 1 回昇格を回す(OP)。{@code /tick sprint} では縮められない
 * 「そのチャンクが過繁茂になってから」の待ち時間を飛ばす。
 *
 * <p>2026-10-10 にユーザー指定でコマンドを整理し、旧 {@code /ov climax} の status・scan・force・ley・crystal は消して、
 * {@code age} だけを {@code /ov age} として残した。
 *
 * <p>出力は開発用なので {@code Component.literal}(翻訳キーを作らない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ClimaxCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                // 2026-10-10 ユーザー指定でコマンドを整理: 極相の関係は /ov age <日数>(旧 /ov climax age)だけ残す。
                .then(Commands.literal("age")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("days", DoubleArgumentType.doubleArg(0.0D))
                                .executes(ClimaxCommand::age))));
    }

    /**
     * <b>汚染の暦を進める。</b> {@code /ov age 14} で「14 日眠った」のと同じだけ暦を先へ送り、
     * そのまま 1 回だけ昇格を回す。世界の {@code gameTime} も画面の「何日目」も動かない。
     *
     * <p>{@code /tick sprint 14d} で早送りしても極相が出ないのは、齢を<b>チャンクごとに
     * 「そこが過繁茂になった瞬間」から</b>数えているため —— まっさらなワールドでは、汚染がそこへ届くまでの
     * 日数がそのまま足りない。早送りで縮まらないのはこの待ち時間だけなので、ここだけを飛ばす。
     *
     * <p><b>台帳の時刻ではなく「今」を動かす</b>(2026-09-26 に作り直し)。前は台帳の
     * 「過繁茂になった時刻」を過去へずらしていたが、時刻は 0 より前へは行けない(負の値は極相の印)ので、
     * 一度 0 まで下げると<b>何度撃っても何も起こらなくなる</b>。しきい値が
     * {@link ClimaxTicker#effectiveMinAgeTicks}(= {@code minAgeDays} + F1→F{@code minPhase} の暦)で
     * ワールドの齢より長くなると、その状態に必ず入る。暦を進める側なら頭打ちが無く、
     * 台帳の時刻も壊さない。
     *
     * <p><b>周囲の円の条件は飛ばさない。</b> 汚染が実際に広がっていない場所は、これを撃っても
     * 昇格しない(そこは {@code force} の出番)。条件のうち「時間」だけを消す、という切り分け。
     *
     * <p>暦は台帳の広がり({@code LedgerSpread})も見ているので、進めたぶんの汚染も追って広がる
     * (越えた区切り(5 分 = 6000 tick)の数だけ判定が走る。1 tick に 1 回ずつ処理するので一度には跳ねない)。
     */
    private static int age(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        PhaseData data = PhaseData.get(level);
        ClimaxTable table = DesignData.climax();
        double days = DoubleArgumentType.getDouble(ctx, "days");
        long ticks = (long) (days * 24000.0D);
        data.skipCalendar(ticks);

        long now = data.clock(level.getServer());
        long minAge = ClimaxTicker.effectiveMinAgeTicks(level, table);
        send(source, "§a[極相]§r 汚染の暦を " + String.format("%.1f", days) + " 日ぶん進めた → "
                + String.format("%.2f", now / 24000.0D) + " 日(gameTime と画面の「何日目」は動かしていない)");
        int[] gates = data.climaxGateCounts(table.neighborRadiusChunks(), minAge, now);
        send(source, "  必要な齢 " + String.format("%.2f", minAge / 24000.0D) + " 日 → 齢を満たす "
                + gates[0] + " / 周囲が埋まっている " + gates[1] + " / §a両方 " + gates[2] + "§r");
        int promoted = ClimaxTicker.promoteNow(level);
        send(source, "  続けて 1 回昇格を回した → " + promoted + " チャンク"
                + (promoted > 0 ? "(1 回の上限。残りは " + table.checkIntervalTicks() + " tick ごとに勝手に続く)"
                        : gates[0] == 0 ? " §e齢がまだ足りない(いちばん古いチャンクで "
                                + String.format("%.2f", data.oldestOvergrownAge(now) / 24000.0D) + " 日)。あと "
                                + String.format("%.2f", (minAge - data.oldestOvergrownAge(now)) / 24000.0D)
                                + " 日ぶん進める§r"
                        : " §e周囲 " + table.neighborRadiusChunks()
                                + " チャンクがまだ埋まっていない(暦を進めても汚染が広がるのを待つ必要がある)§r"));
        return promoted;
    }

    private static void send(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private ClimaxCommand() {
    }
}
