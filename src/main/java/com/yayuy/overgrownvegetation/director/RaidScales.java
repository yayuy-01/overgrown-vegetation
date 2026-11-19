package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.RaidScaleTable;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * 襲撃の規模(2026-11-19 ユーザー決定「見送りと貯め」。正本は Vault「F5 の襲撃(11-16)」の末尾)。
 *
 * <ul>
 *   <li><b>機会</b> = 拠点への送り込みの時({@link BaseMemory#wantsWave}。1〜2 日おき)。最初の襲撃では引かない。</li>
 *   <li>機会ごとに {@code skipChance[フェーズ]} で<b>見送る</b>。見送った間は、拠点の「貯めの財布」に
 *   1 日の生産量 × 森の健康 × {@code purseShare} × たった日数(認識した拠点の数で割る)を足す。財布は森の予算の上限の外に別に持つ(上乗せ。予算からは抜かない)。</li>
 *   <li>貯めた日数(見送り始めてから)が {@code maxSaveDays[フェーズ]} に届いたら必ず起こす(次の機会をその日に合わせる)。</li>
 *   <li>起きたら、貯めた日数とフェーズで段を選ぶ({@link #tierFor})。小 = ふだんの送り込み・中 = {@link HoardRaids}・大 = 静かな前触れ {@link LargeRaids#startScaledOmen}(2 日【仮】)の後に {@link LargeRaids#beginScaled}。</li>
 *   <li>見送って貯めている間(貯めの 2 日目から = {@code holdOffFromDays} 日たった後)は、G1 が遠巻きにうろつく({@link #holdingOff})。大の静かな前触れの間はうろつかない。</li>
 *    <li>襲撃はどれも、財布 → 森の予算 の順に払う({@link ForestDirector} の財布の口)。使い残した財布は後の送り込みへ持ち越す。</li>
 * </ul>
 * 超大(0.01% ほど・Mob の値段 −80%・前触れの地平線の嵐)は 0.2.0 では入れない(11-19 ユーザー)。足すときは
 * {@link RaidScale} に値を 1 つ・{@code raidScale.tiers} に行を 1 つ・{@link #start} に作りを 1 つ足す。
 *
 * <p><b>設定のページから掛ける口</b>は {@link Knobs}(どれも既定 1 / true。サーバーの中だけ・保存しない。設定を読む側が起動時と変更時に書く)。
 */
public final class RaidScales {

    private RaidScales() {
    }

    /** 規模の段。名前は {@code raidScale.tiers} の name。 */
    public enum RaidScale {
        SMALL("small", "小"), MEDIUM("medium", "中"), LARGE("large", "大");

        public final String key;
        public final String label;

        RaidScale(String key, String label) {
            this.key = key;
            this.label = label;
        }

        @Nullable
        public static RaidScale byKey(String key) {
            for (RaidScale s : values()) {
                if (s.key.equals(key)) return s;
            }
            return null;
        }
    }

    /**
     * 設定のページ(mod-settings)から掛ける倍率と切り替え。設計データ({@code director.json} の {@code raidScale})に掛ける。
     * <ul>
     *   <li>{@link #raidsEnabled}: false で送り込み(小・中・大とも)を起こさない(最初の襲撃も)。</li>
     *   <li>{@link #intervalFactor}: 機会の間(送り込みの間)に掛ける。2 で半分の頻度。</li>
     *   <li>{@link #skipChanceFactor}: 見送る確率に掛ける(0 で見送らない = いつも小。上は 1 で頭打ち)。</li>
     *   <li>{@link #purseFactor}: 見送った間に貯める量に掛ける(中・大の大きさ)。</li>
     *   <li>{@link #maxSaveDaysFactor}: 貯められる日数の上限に掛ける。</li>
     *   <li>{@link #countFactor}: 段ごとの出る数の倍率(Mob の値段をこれで割る。添字は {@link RaidScale#ordinal})。</li>
     *   <li>{@link #maxAlive}: 段ごとの同時の上限(0 = 設計データ)。今効くのは中だけ。大の同時の上限は大きな襲撃と同じ口(LargeRaids.maxAlive = ワールドの設定 → largeRaid.maxAlive)。</li>
     * </ul>
     */
    public static final class Knobs {
        public static volatile boolean raidsEnabled = true;
        public static volatile double intervalFactor = 1.0D;
        public static volatile double skipChanceFactor = 1.0D;
        public static volatile double purseFactor = 1.0D;
        public static volatile double maxSaveDaysFactor = 1.0D;
        public static final double[] countFactor = {1.0D, 1.0D, 1.0D};
        public static final int[] maxAlive = {0, 0, 0};
        /** 段ごとの 1 回の襲撃の合計の上限(0 = 設計データ。−1 = 上限なし)。小 = 1 回の送り込みの上限(assault.maxWave)・中 = 継ぎ足しの合計(80)・大 = 湧かせた合計(設計は上限なし)。 */
        public static final int[] maxTotal = {0, 0, 0};

        private Knobs() {
        }

        /** テスト用: 既定へ戻す。 */
        public static void reset() {
            raidsEnabled = true;
            intervalFactor = skipChanceFactor = purseFactor = maxSaveDaysFactor = 1.0D;
            java.util.Arrays.fill(countFactor, 1.0D);
            java.util.Arrays.fill(maxAlive, 0);
            java.util.Arrays.fill(maxTotal, 0);
        }
    }

    public static RaidScaleTable table() {
        return DesignData.director().raidScale;
    }

    /** この仕組みが入っているか(切ると前の形: 送り込みは毎回・貯めた襲撃と大きな襲撃は前の起こし方)。 */
    public static boolean enabled() {
        return table().enabled;
    }

    public static double skipChance(int phase) {
        return Math.max(0.0D, Math.min(1.0D, table().skipChance(phase) * Knobs.skipChanceFactor));
    }

    public static double maxSaveDays(int phase) {
        return Math.max(0.0D, table().maxSaveDays(phase) * Knobs.maxSaveDaysFactor);
    }

    public static double purseShare() {
        return Math.max(0.0D, table().purseShare * Knobs.purseFactor);
    }

    @Nullable
    public static RaidScaleTable.Tier tier(RaidScale s) {
        return table().tier(s.key);
    }

    /** その段の Mob の値段の倍率(設計の costFactor ÷ 設定の数の倍率)。 */
    public static double costFactor(RaidScale s) {
        RaidScaleTable.Tier t = tier(s);
        double f = t == null ? 1.0D : t.costFactor();
        return f / Math.max(0.01D, Knobs.countFactor[s.ordinal()]);
    }

    /** その段の同時の上限(設定 → 設計データ → fallback)。 */
    public static int maxAlive(RaidScale s, int fallback) {
        int k = Knobs.maxAlive[s.ordinal()];
        if (k > 0) return k;
        RaidScaleTable.Tier t = tier(s);
        return t != null && t.maxAlive() > 0 ? t.maxAlive() : fallback;
    }

    /** その段の 1 回の合計の上限(設定 → 設計データ → fallback)。0 以下 = 上限なし。 */
    public static int maxTotal(RaidScale s, int fallback) {
        int k = Knobs.maxTotal[s.ordinal()];
        if (k != 0) return k;
        RaidScaleTable.Tier t = tier(s);
        return t != null && t.maxTotal() > 0 ? t.maxTotal() : fallback;
    }

    /** 大の前触れ(森の湧きが止まって静かになる)の長さ(日)。 */
    public static double omenDays(RaidScale s) {
        if (!com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.onCurrent(com.yayuy.overgrownvegetation.difficulty.DifficultySetting.RAID_OMEN)) return 0.0D; // MOD の設定「大きな襲撃の前触れ」(11-19)。0 日 = すぐ来る
        RaidScaleTable.Tier t = tier(s);
        return t == null ? 0.0D : t.omenDays();
    }

    // ---------------------------------------------------------------- 見送って貯めている間の手がかり(11-19 ユーザー)

    /** 認識した拠点のどれかで見送って貯めている最中か(見張りが 20 秒ごとに付け直す。保存しない)。 */
    private static volatile boolean savingNow;

    /** G1 が遠巻きにうろつくか: 前の「貯める」の気分の間と、見送って貯めている間(貯めの 2 日目から)。大の静かな前触れ・本番の間はうろつかない(静かになる = 大が来る)。 */
    public static boolean holdingOff() {
        return ForestDirector.hoarding() || savingNow;
    }

    /** 見送って貯め始めてから、G1 が遠巻きにうろつき出すまでの日数(既定 1 日 = 貯めの 2 日目から。11-19 ユーザー。1 は【仮】)。 */
    public static double holdOffFromDays() {
        return table().holdOffFromDays;
    }

    public static void refreshSaving(ServerLevel level, BaseMemory memory, double day) {
        boolean any = false;
        ForestDirector.Mood mood = ForestDirector.get(level).mood();
        if (enabled() && mood != ForestDirector.Mood.LARGE_OMEN && mood != ForestDirector.Mood.LARGE && !DirectorGates.peaceful(level)) {
            // 貯めの 1 日目は出さない: どの見送りでも出ると F5 で時間の約 4 割・F7 で約 6 割うろつき、合図として薄まる(11-19 ユーザー)
            double from = holdOffFromDays();
            for (BaseMemory.Base b : memory.bases()) {
                if (b.stage() == BaseMemory.Stage.RECOGNIZED && b.saving() && day - b.saveStart() >= from - 1.0E-9D) any = true;
            }
        }
        savingNow = any;
    }

    public static double maxDays(RaidScale s, double fallback) {
        RaidScaleTable.Tier t = tier(s);
        return t != null && t.maxDays() > 0.0D ? t.maxDays() : fallback;
    }

    // ---------------------------------------------------------------- 決め方(純粋な計算)

    /**
     * 貯めた日数とフェーズで段を選ぶ: fromDays ≤ 貯めた日数 で、minPhase ≤ フェーズ の段のうち いちばん上。
     * 貯めた日数は小数(見送り始めてからの日数)。表に無い名前の段は飛ばす。どれにも当たらなければ小。
     */
    public static RaidScale tierFor(int phase, double savedDays) {
        RaidScale best = RaidScale.SMALL;
        for (RaidScaleTable.Tier t : table().tiers) {
            RaidScale s = RaidScale.byKey(t.name());
            if (s == null || phase < t.minPhase() || savedDays + 1.0E-6D < t.fromDays()) continue;
            if (s.ordinal() > best.ordinal()) best = s;
        }
        return best;
    }

    /** 機会での決め方の結果: 見送るか、どの段で起こすか。 */
    public record Decision(boolean skip, RaidScale scale) {
    }

    /**
     * 機会で見送るか起こすか(純粋な計算)。上限の日数に届いていれば必ず起こす。roll は 0〜1 の乱数。
     *
     * @param savedDays 見送り始めてからの日数(貯めていなければ 0)
     */
    public static Decision decide(int phase, double savedDays, double roll) {
        double max = maxSaveDays(phase);
        if (savedDays + 1.0E-6D < max && roll < skipChance(phase)) return new Decision(true, null);
        return new Decision(false, tierFor(phase, savedDays));
    }

    /** 見送ったときの次の機会(純粋な計算): 今 + 間、ただし 貯め始め + 上限 を越えない。 */
    public static double nextOpportunity(double day, double interval, double saveStart, double maxSaveDays) {
        return Math.min(day + interval, saveStart + maxSaveDays);
    }

    /** 見送った間に財布へ足す量(純粋な計算)。 */
    public static double credit(double incomePerDay, double healthFactor, double share, double days, int bases) {
        return Math.max(0.0D, incomePerDay * healthFactor * share * Math.max(0.0D, days) / Math.max(1, bases));
    }

    // ---------------------------------------------------------------- 機会で呼ぶ

    /** 確かめ用: 最後の機会の結果。 */
    @Nullable
    public static String lastNote;

    /**
     * 拠点 b の機会(最初の襲撃が済んでいて、送り込みの時が来た)。見送るなら財布に貯めて次の機会を決め、true を返す。
     * 起こすなら、段を選んで {@link #start} し、false を返す(呼ぶ側が {@code waveSent} する)。
     */
    public static boolean skipOrStart(ServerLevel level, BaseMemory memory, BaseMemory.Base b, double day, double roll,
                                      net.minecraft.util.RandomSource random) {
        int phase = DirectorGates.phase(level);
        creditPurse(level, memory, b, day);
        double saved = b.saving() ? Math.max(0.0D, day - b.saveStart()) : 0.0D;
        // 導入の段では見送り始めない = いつも小(11-14 ユーザー「貯めた襲撃・大きな襲撃は定着と最盛だけ、導入では起こさない」を引き継ぐ(11-19 全体管理が了承))。
        // 前のフェーズから貯めている最中なら、そのまま続ける
        boolean intro = DirectorGates.stage(level) == com.yayuy.overgrownvegetation.phase.PhaseStage.INTRO;
        Decision dec = intro && !b.saving() ? new Decision(false, RaidScale.SMALL) : decide(phase, saved, roll);
        ForestDirector d = ForestDirector.get(level);
        if (dec.skip()) {
            double start = b.saving() ? b.saveStart() : day;
            double interval = memory.nextInterval(random) * Math.max(0.05D, Knobs.intervalFactor);
            memory.skipWave(b, day, start, nextOpportunity(day, interval, start, maxSaveDays(phase)));
            lastNote = String.format(java.util.Locale.ROOT, "拠点 #%d: 見送った(貯め %.2f 日・財布 %.1f・次の機会まで %.2f 日)",
                    b.id(), Math.max(0.0D, day - start), b.purse(), b.nextWaveDay() - day);
            d.note(lastNote);
            return true;
        }
        memory.endSaving(b);
        lastNote = String.format(java.util.Locale.ROOT, "拠点 #%d: %s規模の襲撃(貯め %.2f 日・財布 %.1f)", b.id(), dec.scale().label, saved, b.purse());
        d.note(lastNote);
        lastScale = dec.scale();
        start(level, b, day, dec.scale(), random);
        return false;
    }

    /** 確かめ用: 最後に起こした段。 */
    @Nullable
    public static RaidScale lastScale;

    /** 見送っている間の分を財布へ足す(最後に足した日から今日まで)。 */
    static void creditPurse(ServerLevel level, BaseMemory memory, BaseMemory.Base b, double day) {
        if (!b.saving()) return;
        int n = 0;
        for (BaseMemory.Base c : memory.bases()) {
            if (c.stage() == BaseMemory.Stage.RECOGNIZED) n++;
        }
        // 貯めるのは 貯め始め + 上限日数 まで(ピースフル・ほかの拠点の襲撃・前触れで機会が止まっていた間に上限を越えて貯めない)
        double until = Math.min(day, b.saveStart() + maxSaveDays(DirectorGates.phase(level)));
        // 森の健康に MOD の設定「森の予算の生産量」(11-19、難易度の段で変わる)も掛ける = 予算の貯まり方(ForestDirector#accrue)と同じ倍率
        double add = credit(ForestDirector.incomePerDay(level), com.yayuy.overgrownvegetation.ecosystem.Ecosystem.incomeFactor(level)
                        * com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.forestIncome(level.getServer()),
                purseShare(), until - b.purseCreditedAt(), n);
        memory.addPurse(b, add, day);
    }

    /** 段の作りで起こす。 */
    public static void start(ServerLevel level, BaseMemory.Base b, double day, RaidScale scale, net.minecraft.util.RandomSource random) {
        switch (scale) {
            case SMALL -> BaseRaids.sendWave(level, b, day, random);
            case MEDIUM -> HoardRaids.begin(level, b, day, random);
            case LARGE -> {
                // 11-19: 大は決まってから静かな前触れ(omenDays)の後に来る。0 ならすぐ
                double omen = omenDays(RaidScale.LARGE);
                if (omen > 0.0D) LargeRaids.startScaledOmen(level, b, day, omen);
                else LargeRaids.beginScaled(level, b, day);
            }
        }
    }
}
