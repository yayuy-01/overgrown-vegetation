package com.yayuy.overgrownvegetation.entity.action;

import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 通常技の選び方(2026-11-05、層 2 の共通部品。ユーザー決定: 「点数で選び、直前に使った技の点数を下げる」)。
 *
 * <p>1 tick ごとに {@link #pick} を呼ぶ。候補は次のすべてを満たす技:
 * <ul>
 *     <li>クールダウンが明けている({@link #used} で技を始めた時から {@link ActionDef#cooldown} tick)</li>
 *     <li>相手との距離が {@link ActionDef#minRange}〜{@link ActionDef#maxRange}(× 体格。maxRange が 0 以下なら種の届く距離 {@code reach})</li>
 *     <li>種の条件({@code allowed}。向き・高さの差・地面に居るか など)</li>
 *     <li>{@link ActionDef#chance} の乱数に勝つ(遠い技を「間合いに入った瞬間に必ず」撃たないため)</li>
 * </ul>
 * 候補の中から、重み = {@link ActionDef#score} × (直前の技なら {@link ActionDef#recency}) で 1 つ引く。
 * 技と技の<b>間</b>に乱数を入れるのはよいが、<b>技の中の時間</b>は揺らさない(教科書 15-4)。
 *
 * <p>特殊技は数えない —— 特殊技の出し方は {@link com.yayuy.overgrownvegetation.entity.SpecialPacing}(交戦から 6 秒 + 通常技 2 回で解禁、35%)のまま。
 * 保存しない(読み込み直したら、クールダウンも直前の技も無い状態から)。
 */
public final class ActionPicker {

    private final Map<String, Integer> cooldowns = new HashMap<>();
    @Nullable
    private String last;

    /** 毎 tick(サーバー)。クールダウンを進める。 */
    public void tick() {
        cooldowns.replaceAll((k, v) -> Math.max(0, v - 1));
    }

    /** 技を始めた(選んで出した・特殊技・GameTest で出した)。クールダウンを付け、直前の技として覚える。 */
    public void used(ActionDef def) {
        this.cooldowns.put(def.id(), Math.max(0, def.cooldown()));
        this.last = def.id();
    }

    /** クールダウンだけ付け直す(技の途中で長めの間を足すときなど)。 */
    public void setCooldown(String id, int ticks) {
        this.cooldowns.put(id, Math.max(0, ticks));
    }

    public int cooldown(String id) {
        return this.cooldowns.getOrDefault(id, 0);
    }

    public boolean ready(String id) {
        return cooldown(id) <= 0;
    }

    @Nullable
    public String last() {
        return this.last;
    }

    /** 間合いに入っているか(乱数・クールダウンは見ない)。 */
    public static boolean inRange(ActionDef def, double distance, double rangeScale, double reach) {
        double lo = def.minRange() * rangeScale;
        double hi = def.maxRange() > 0.0D ? def.maxRange() * rangeScale : reach;
        return distance >= lo && distance <= hi;
    }

    /**
     * 候補から 1 つ選ぶ。無ければ null(この tick は技を出さない)。
     *
     * @param defs       種の通常技
     * @param distance   相手までの水平距離(ブロック)
     * @param rangeScale 間合いに掛ける体格
     * @param reach      maxRange が 0 以下の技の届く距離(ブロック。近接技の「振り始める距離」)
     * @param allowed    種の条件
     */
    @Nullable
    public ActionDef pick(List<ActionDef> defs, double distance, double rangeScale, double reach,
                          Predicate<ActionDef> allowed, RandomSource random) {
        return pick(defs, distance, rangeScale, reach, allowed, d -> 1.0D, random);
    }

    /**
     * {@link #pick} に、技ごとの重みの掛け数 {@code tilt}(個体の性格など。1 で変わらない)を足したもの(2026-11-08、ファルキムネスの段 3)。
     */
    @Nullable
    public ActionDef pick(List<ActionDef> defs, double distance, double rangeScale, double reach,
                          Predicate<ActionDef> allowed, java.util.function.ToDoubleFunction<ActionDef> tilt, RandomSource random) {
        List<ActionDef> cands = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double sum = 0.0D;
        for (ActionDef d : defs) {
            if (!ready(d.id()) || !inRange(d, distance, rangeScale, reach) || !allowed.test(d)) {
                continue;
            }
            if (d.chance() < 1.0D && random.nextDouble() >= d.chance()) {
                continue;
            }
            double w = Math.max(0.0D, d.score()) * (d.id().equals(this.last) ? Math.max(0.0D, d.recency()) : 1.0D)
                    * Math.max(0.0D, tilt.applyAsDouble(d));
            if (w <= 0.0D) {
                continue;
            }
            cands.add(d);
            weights.add(w);
            sum += w;
        }
        if (cands.isEmpty()) {
            return null;
        }
        double r = random.nextDouble() * sum;
        for (int i = 0; i < cands.size(); i++) {
            r -= weights.get(i);
            if (r < 0.0D) {
                return cands.get(i);
            }
        }
        return cands.get(cands.size() - 1);
    }
}
