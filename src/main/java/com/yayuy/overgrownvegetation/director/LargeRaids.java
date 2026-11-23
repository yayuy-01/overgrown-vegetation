package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * <b>大きな襲撃</b>(2026-11-14、司令塔 第 4 段)の気分の移り変わり。司令塔の襲撃の大・中・小(小 = 送り込み・中 = 貯めた襲撃・大 = これ)の「大」。
 *
 * <ul>
 *   <li><b>起こる</b>: 襲撃の規模の「見送りと貯め」({@link RaidScales})が大を選んだとき。</li>
 *   <li><b>前触れ</b>({@code omenDays}): 植物側の湧きが止まる({@link DirectorGates#plantSpawnsSuppressed})。偵察は続ける。</li>
 *   <li><b>本番</b>: 前線から集まる部隊が拠点を襲う({@code front/FrontRaids})。長さは大の段の {@code maxDays}。拠点を忘れたら終わり。</li>
 * </ul>
 * 2026-11-23 片付け E: 本番で攻め手を途切れずに湧かせる作り(step・湧かせる所・仮想の群れの送り出し)と、前の「1 日 2 割で起こす」起こし方は消した
 * (11-21 から部隊は前線が出す)。{@link #ATTACKER_TAG} は前の版の保存に残る群れの印として残す(終わるときに森へ引き返させる)。
 */
public final class LargeRaids {

    /** 攻め手の印(前の版の湧かせ方で付けた印。仮想の群れ・突破・遠くで消さないが見る)。 */
    public static final String ATTACKER_TAG = "ov_large_raid";

    private LargeRaids() {
    }

    /** 見張り(20 秒ごと)から: 前触れから本番へ・本番の終わり。 */
    static void tick(ServerLevel level, double day, int sampleTicks) {
        if (DirectorGates.peaceful(level)) return; // ピースフルの間は時計を進めない(見直し ②9)
        ForestDirector d = ForestDirector.get(level);
        switch (d.mood()) {
            case LARGE_OMEN -> {
                if (day >= d.moodUntil()) {
                    if (scaled(d) && target(level) != null) beginScaled(level, target(level), day); // 11-19 大: 静かな前触れの後に本番
                    else end(level, day, "拠点を忘れた");
                }
            }
            case LARGE -> {
                if (target(level) == null) end(level, day, "拠点を忘れた");
                else if (!scaled(d)) end(level, day, "前の版の大きな襲撃");
                else if (day - d.largeRaidScaledSince() > RaidScales.maxDays(RaidScales.RaidScale.LARGE, 1.0D)) end(level, day, "時間切れ(使い残しは財布に残す)");
            }
            default -> {
            }
        }
    }

    /**
     * 11-19 襲撃の規模の「大」: 前触れなしで、拠点 b へ本番を始める(見送りの静けさが前兆。11-16「前触れは何も出さない」)。
     * 払うのは その拠点の財布 → 森の予算。同時の上限は大の段の maxAlive(300【仮】)、長さは maxDays(1 日【仮】)。予算が尽きるか時間で終わる。
     */
    public static void beginScaled(ServerLevel level, BaseMemory.Base b, double day) {
        EconomySoak.large(false); // 計測の間だけ
        ForestDirector d = ForestDirector.get(level);
        d.setLargeRaidTarget(b.id());
        d.setLargeRaidScaledSince(day);
        d.setMood(ForestDirector.Mood.LARGE, day, Double.MAX_VALUE);
        d.note(String.format(Locale.ROOT, "大規模の襲撃が始まった(拠点 #%d。財布 %.1f・予算 %.1f)", b.id(), b.purse(), d.available(level)));
    }

    /**
     * 11-19 全体管理・ユーザー: 大に決まったら、すぐには来ず {@code omenDays}(2 日【仮】)森の湧きが止まって静かになる(前の大きな襲撃の前触れと同じ作り:
     * {@link DirectorGates#plantSpawnsSuppressed}・送り込みと偵察以外の司令塔の湧きが止まる・G1 は遠巻きにうろつかない)。その間 財布は増えも減りもしない。
     */
    public static void startScaledOmen(ServerLevel level, BaseMemory.Base b, double day, double days) {
        EconomySoak.large(true); // 計測の間だけ
        ForestDirector d = ForestDirector.get(level);
        d.setLargeRaidTarget(b.id());
        d.setLargeRaidScaledSince(day);
        d.setMood(ForestDirector.Mood.LARGE_OMEN, day, day + days);
        d.note(String.format(Locale.ROOT, "大規模の襲撃に決まった(拠点 #%d。%.2f 日静かになってから来る。財布 %.1f)", b.id(), days, b.purse()));
    }

    /** 今の本番が、襲撃の規模の「大」で始めたものか。 */
    public static boolean scaled(ForestDirector d) {
        return !Double.isNaN(d.largeRaidScaledSince());
    }

    /** 終わる(ふだんへ。次まで cooldownDays)。 */
    public static void end(ServerLevel level, double day, String why) {
        ForestDirector d = ForestDirector.get(level);
        if (scaled(d)) {
            // 11-19 大の後: 次の機会は終わってから 1 間あける(前触れ + 本番の間に過ぎた機会が、終わった途端に来ないように)
            BaseMemory memory = BaseMemory.get(level.getServer());
            BaseMemory.Base tb = memory.byId(d.largeRaidTarget());
            if (tb != null) memory.setNextWaveDay(tb, day + memory.nextInterval(level.getRandom()) * Math.max(0.05D, RaidScales.Knobs.intervalFactor));
        }
        d.setLargeRaidScaledSince(Double.NaN);
        d.setMood(ForestDirector.Mood.NORMAL, day, day);
        d.setLargeRaidCooldownUntil(day + DesignData.director().largeRaid.cooldownDays);
        com.yayuy.overgrownvegetation.horde.VirtualHordes.retreat(level, ATTACKER_TAG); // 歩いている途中の仮想の群れは森へ引き返して消える(11-18 horde-flow-a・ユーザー決定)
        d.note("大きな襲撃が終わった(" + why + "。予算 " + String.format(Locale.ROOT, "%.1f", d.available(level)) + ")");
    }

    @Nullable
    static BaseMemory.Base strongestBase(ServerLevel level) {
        BaseMemory.Base best = null;
        for (BaseMemory.Base b : BaseMemory.get(level.getServer()).bases()) {
            if (b.stage() != BaseMemory.Stage.RECOGNIZED) continue;
            if (best == null || b.score() > best.score()) best = b;
        }
        return best;
    }

    /** 本番の的(認識した拠点のまま残っていれば)。 */
    @Nullable
    public static BaseMemory.Base target(ServerLevel level) {
        BaseMemory.Base b = BaseMemory.get(level.getServer()).byId(ForestDirector.get(level).largeRaidTarget());
        return b != null && b.stage() == BaseMemory.Stage.RECOGNIZED ? b : null;
    }

    /** 設定画面の「ゲーム内容」で変えられる上限の下限と上限(重くなりすぎないように)【仮】。 */
    public static final int MAX_ALIVE_MIN = 20, MAX_ALIVE_MAX = 500;

    /**
     * 同時に出ている攻め手の上限: MOD の設定(COMMON の {@code gameplay.largeRaidMaxAlive})があればそれ(20〜500)、−1 なら設計データ
     * ({@code director.json} の {@code largeRaid.maxAlive})。設定がまだ読めないとき(起動の途中)は設計データ。
     */
    public static int maxAlive() {
        // 11-19: 値はワールドごと(MOD の設定ページ)。ワールドの値が無ければ下の COMMON の設定・設計データ
        net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            return Math.max(MAX_ALIVE_MIN, Math.min(MAX_ALIVE_MAX,
                    com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.largeRaidMaxAlive(server)));
        }
        return configuredMaxAlive();
    }

    /** ワールドの値が無いときの上限(COMMON の設定、−1 なら設計データ)。設定のページの既定値。 */
    public static int configuredMaxAlive() {
        int fallback = DesignData.director().largeRaid.maxAlive;
        try {
            int c = com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.largeRaidMaxAlive.get();
            return c < 0 ? fallback : Math.max(MAX_ALIVE_MIN, Math.min(MAX_ALIVE_MAX, c));
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

}
