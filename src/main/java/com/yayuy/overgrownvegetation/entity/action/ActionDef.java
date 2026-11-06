package com.yayuy.overgrownvegetation.entity.action;

import java.util.Map;

/**
 * 1 つの技の<b>調整できる数値</b>(2026-11-05、層 2 の共通部品)。設計データ {@code design/actions.json} から読む
 * ({@link com.yayuy.overgrownvegetation.data.ActionTable})。時刻(前触れ・実行・隙)はアニメと対なので {@link ActionTiming} として種のコードが持つ。
 *
 * <p>間合いは<b>ブロック</b>で書く。使う側は体格(SCALE 属性)を {@link ActionPicker#pick} の {@code rangeScale} に渡す
 * (ファルキムネスは SCALE 1 なので書いた値がそのまま。geo に焼き込んだ 1.8 は掛けない)。
 *
 * @param id         技の名前(種の中で一意。json の鍵)
 * @param cooldown   使ってから次に選べるまで(tick、<b>技を始めた時から</b>数える)
 * @param minRange   この距離以上の相手にだけ使う(ブロック)
 * @param maxRange   この距離以下の相手にだけ使う(ブロック)。0 以下なら「種が決める届く距離」(近接技)
 * @param score      選ぶ重み(大きいほど出やすい)
 * @param recency    直前に使った技なら重みにこれを掛ける(0〜1。小さいほど同じ技が続かない)
 * @param chance     間合いに入った 1 tick ごとに候補に入る確率(0〜1)。近接技は 1、遠い技は小さくして「間合いに入った瞬間に必ず撃つ」を避ける
 * @param damage     ダメージ(技ごとの意味。0 なら種の攻撃力を使う)
 * @param extra      技ごとのほかの数値(速さ・幅など。種が名前で引く)
 */
public record ActionDef(String id, int cooldown, double minRange, double maxRange, double score, double recency,
                        double chance, double damage, Map<String, Double> extra) {

    public ActionDef {
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    /** {@link #extra} から引く(無ければ {@code fallback})。 */
    public double get(String key, double fallback) {
        Double v = this.extra.get(key);
        return v != null ? v : fallback;
    }
}
