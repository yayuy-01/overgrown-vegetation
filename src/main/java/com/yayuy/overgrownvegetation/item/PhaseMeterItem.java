package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 「フェーズ計測器」— 右クリックで、今のフェーズと次のフェーズまでの進み具合
 * ({@link PhaseData} のポイント)をアクションバーとチャットに表示する計測アイテム。
 * {@link SporeMeterItem}(胞子濃度計)と同じ位置づけの道具。
 *
 * <h2>表示するもの</h2>
 * <ul>
 *     <li>アクションバー: 現在フェーズと、次のフェーズまでの達成率。</li>
 *     <li>チャット 1 行目: 次のフェーズの昇格条件({@code phases.json} の {@code pointsToAdvance})。</li>
 *     <li>チャット 2 行目以降: ポイントの現在値 / 必要値、過繫茂系チャンクの累計(= ポイントの
 *     貯まる速さの元)、今の 1 分あたりの収入と「今の収入のままなら残り何日」の目安。</li>
 *     <li>フェーズ0 のときは、代わりにフェーズ1 の 2 つのゲート(ワールド経過時刻・解除した実績の種類数)の
 *     進み具合。</li>
 * </ul>
 *
 * <p>Shift + 右クリックで、ポイントの獲得内訳・累計汚染ブロック数・凍結状態も出す。
 *
 * <p>フェーズはサーバ側にしか無いので、値の読み出しと表示はすべてサーバ側で行う
 * ({@code displayClientMessage} がクライアントへ送る)。
 */
public class PhaseMeterItem extends Item {

    /** ゲーム内 1 日 = 24000 tick = 1200 秒 = 20 分(実時間)。 */
    private static final double TICKS_PER_DAY = 24000.0D;
    private static final double TICKS_PER_MINUTE = 1200.0D;

    public PhaseMeterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel serverLevel) {
            ServerLevel overworld = serverLevel.getServer().overworld();
            PhaseData data = PhaseData.get(serverLevel);
            PhaseTable phases = DesignData.phases();
            int phase = data.getPhase();

            if (phase < 1) {
                showPhase1Gate(player, overworld, data, phases);
            } else {
                showAdvanceProgress(player, data, phases, phase);
            }

            if (data.isFrozen()) {
                player.displayClientMessage(Component.translatable(
                        "item.overgrown_vegetation.phase_meter.frozen").withStyle(ChatFormatting.AQUA), false);
            }
            if (player.isShiftKeyDown()) {
                showBreakdown(player, data, phases);
            }

            serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.7F, 0.9F);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /** フェーズ0: フェーズ1 の 2 つのゲート(時刻・実績)の進み具合。 */
    private static void showPhase1Gate(Player player, ServerLevel overworld, PhaseData data, PhaseTable phases) {
        long age = overworld.getGameTime();
        int needTicks = phases.startWorldAgeTicks();
        int adv = data.getStartAdvancementCount();
        int needAdv = phases.startAdvancements();
        boolean timeOk = age >= needTicks;
        boolean advOk = adv >= needAdv;

        player.displayClientMessage(Component.translatable(
                "item.overgrown_vegetation.phase_meter.phase0",
                mark(timeOk && advOk)).withStyle(ChatFormatting.GRAY), true);
        player.displayClientMessage(Component.translatable(
                        "item.overgrown_vegetation.phase_meter.gate_time",
                        num(age), num(needTicks), days(Math.max(0L, needTicks - age)), mark(timeOk))
                .withStyle(timeOk ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        player.displayClientMessage(Component.translatable(
                        "item.overgrown_vegetation.phase_meter.gate_advancements",
                        adv, needAdv, mark(advOk))
                .withStyle(advOk ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
    }

    /** フェーズ1 以降: 次のフェーズの必要ポイントと、その進み具合。 */
    private static void showAdvanceProgress(Player player, PhaseData data, PhaseTable phases, int phase) {
        int next = phase + 1;
        long points = data.getPoints();
        long chunksTotal = data.getOvergrownChunks();
        long needPoints = phases.pointsToAdvance(next);
        boolean hasPoints = needPoints != Long.MAX_VALUE;

        if (!hasPoints) {
            // 昇格条件が設定されていないフェーズ(既定ではフェーズ6以降)。
            player.displayClientMessage(Component.translatable(
                    "item.overgrown_vegetation.phase_meter.reading_max", phase).withStyle(ChatFormatting.LIGHT_PURPLE), true);
            player.displayClientMessage(Component.translatable(
                    "item.overgrown_vegetation.phase_meter.no_condition", phase).withStyle(ChatFormatting.GRAY), false);
            player.displayClientMessage(pointsLine(points, Long.MAX_VALUE).withStyle(ChatFormatting.GRAY), false);
            player.displayClientMessage(chunksLine(chunksTotal).withStyle(ChatFormatting.GRAY), false);
            return;
        }

        double ratio = (double) points / needPoints;
        ChatFormatting color = ratio >= 0.75D ? ChatFormatting.RED
                : ratio >= 0.4D ? ChatFormatting.YELLOW
                : ChatFormatting.GREEN;
        player.displayClientMessage(Component.translatable(
                "item.overgrown_vegetation.phase_meter.reading",
                phase, next, pct(ratio)).withStyle(color), true);

        player.displayClientMessage(Component.translatable(
                        "item.overgrown_vegetation.phase_meter.condition_points", next, num(needPoints))
                .withStyle(ChatFormatting.WHITE), false);
        player.displayClientMessage(pointsLine(points, needPoints)
                .withStyle(points >= needPoints ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
        player.displayClientMessage(chunksLine(chunksTotal).withStyle(ChatFormatting.GRAY), false);

        // 今の収入と、そのまま推移した場合の残り時間。汚染がさらに広がれば収入も増えるので、
        // 実際にはこの目安より早くなる。
        double perTick = phases.pointsPerTickWithChunks(chunksTotal);
        double perMinute = perTick * TICKS_PER_MINUTE;
        Component income = points < needPoints && perMinute > 0.0D
                ? Component.translatable("item.overgrown_vegetation.phase_meter.income_eta",
                        num(Math.round(perMinute)), trim(perTick), num(chunksTotal),
                        days(Math.round((needPoints - points) / perMinute * TICKS_PER_MINUTE)))
                : Component.translatable("item.overgrown_vegetation.phase_meter.income",
                        num(Math.round(perMinute)), trim(perTick), num(chunksTotal));
        player.displayClientMessage(income.copy().withStyle(ChatFormatting.DARK_GRAY), false);
    }

    /** Shift + 右クリック: ポイントの獲得内訳と、その他の累計。 */
    private static void showBreakdown(Player player, PhaseData data, PhaseTable phases) {
        player.displayClientMessage(Component.translatable(
                "item.overgrown_vegetation.phase_meter.breakdown",
                trim(phases.pointsPerTick()), trim(phases.pointsPerTickPerOvergrownChunk()),
                phases.pointsPerAdvancement(), phases.pointsPerGroupMobDeath(),
                phases.pointsPerGroupMobKill(), phases.pointsPerOvergrownChunk())
                .withStyle(ChatFormatting.DARK_GRAY), false);
        player.displayClientMessage(Component.translatable(
                "item.overgrown_vegetation.phase_meter.totals",
                num(data.getOvergrownChunks()), num(data.getCorruptedBlocks()))
                .withStyle(ChatFormatting.DARK_GRAY), false);
    }

    /** 「現在値 / 必要値 (達成率)」のポイント行。必要値が無い(条件なし)ときは現在値だけ。 */
    private static MutableComponent pointsLine(long points, long need) {
        return need == Long.MAX_VALUE
                ? Component.translatable("item.overgrown_vegetation.phase_meter.points_only", num(points))
                : Component.translatable("item.overgrown_vegetation.phase_meter.points",
                        num(points), num(need), pct((double) points / need));
    }

    /**
     * 過繫茂系チャンクの累計行。昇格条件ではなく<b>ポイントの貯まる速さ</b>の元
     * ({@code points.perTickPerOvergrownChunk})なので、累計だけを出す。
     */
    private static MutableComponent chunksLine(long total) {
        return Component.translatable("item.overgrown_vegetation.phase_meter.chunks_total", num(total));
    }

    /**
     * tick 数 → 「4.5d (1:30)」。ゲーム内日数(d)と実時間(時:分)を併記する。
     * 言語ファイルを跨いで同じ表記になるよう、単位は記号だけにしてある。
     */
    private static String days(long ticks) {
        double d = ticks / TICKS_PER_DAY;
        long minutes = Math.round(ticks / TICKS_PER_MINUTE);
        return minutes >= 60
                ? String.format("%.1fd (%d:%02d)", d, minutes / 60, minutes % 60)
                : String.format("%.1fd (%dm)", d, minutes);
    }

    private static String num(long v) {
        return String.format("%,d", v);
    }

    private static String pct(double ratio) {
        return String.format("%.1f%%", Math.min(1.0D, Math.max(0.0D, ratio)) * 100.0D);
    }

    /** 小数を「1」「0.01」のように余計な 0 を付けずに出す。 */
    private static String trim(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        return new java.math.BigDecimal(String.valueOf(v)).stripTrailingZeros().toPlainString();
    }

    private static String mark(boolean ok) {
        return ok ? "✔" : "✘";
    }
}
