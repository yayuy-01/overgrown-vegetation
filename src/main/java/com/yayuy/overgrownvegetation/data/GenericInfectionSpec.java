package com.yayuy.overgrownvegetation.data;

import java.util.List;

/**
 * 汎用の感染体(専用の G1 が無い生き物が、生きたまま植物側に変わる。2026-11-15 ユーザー決定)の設計値
 * ({@code spore_field.json} の {@code genericInfection})。読むのは {@code infect/GenericInfection}・{@code infect/GenericInfectionAi}。
 * 正本は Vault「G1 媒介種(寄生された動物)」の「汎用の感染体」節。
 *
 * <h2>ユーザー指定(★ 11-15)</h2>
 * <ul>
 *     <li>対象は専用の G1 が無い生き物すべて(バニラ + ほかの MOD。ボス・飛ぶ・泳ぐもの・表の 13 種の子ども・ウォーデンも)。</li>
 *     <li>感染の死(胞子の 20 ダメージ・G1 に殴り殺された)の所で、死なずに変わる。胞子の傷を外して元の最大体力・満タンで変わる。</li>
 *     <li>ボス = タグ {@code c:bosses} + {@code bossEntities}。変わると最大体力が元の {@code bossMaxHealthFactor} 0.6 倍(ほかはそのまま)。</li>
 *     <li>攻撃を持たない種と Brain で動く Mob は、種を問わず一定の体当たり({@code ramDamage})。狙いは視線を問わない。</li>
 * </ul>
 *
 * <h2>仮決め【仮】</h2>
 * <ul>
 *     <li>変わる動き(立ち止まって震える → 植物が突き出る → 動き出す)の長さ {@code transformTicks} 40(約 2 秒)。</li>
 *     <li>体当たり: ダメージ {@code ramDamage} 3・冷却 {@code ramCooldownTicks} 20・追う速さの倍率 {@code ramSpeed} 1.2。</li>
 *     <li>狙う範囲: 追跡範囲の属性(FOLLOW_RANGE)、無ければ {@code targetRange} 16。</li>
 *     <li>変わらない種 {@code excludedEntities}: 既定は空。</li>
 *     <li>元の攻撃が当たらないときの予備: 狙って {@code fallbackTicks} 60 当たらなければ、その相手の間は体当たり。</li>
 * </ul>
 */
public record GenericInfectionSpec(
        boolean enabled,
        int transformTicks,
        double bossMaxHealthFactor,
        List<String> bossEntities,
        List<String> excludedEntities,
        double ramDamage,
        int ramCooldownTicks,
        double ramSpeed,
        double targetRange,
        int fallbackTicks) {

    /** Gson の受け皿(null は既定値)。 */
    public static final class Json {
        public String _comment;
        /** false で汎用の感染体を作らない(今までどおり死ぬ)。 */
        public Boolean enabled;
        /** 変わる動きの長さ(tick)。この間は動きと攻撃を止める。 */
        public Integer transformTicks;
        /** ボスが変わったときの最大体力の倍率(★ 0.6)。 */
        public Double bossMaxHealthFactor;
        /** タグ c:bosses のほかにボスとして扱う種(登録名)。 */
        public List<String> bossEntities;
        /** 変わらない種(登録名)。 */
        public List<String> excludedEntities;
        /** 汎用の体当たりのダメージ・冷却(tick)・追う速さの倍率。 */
        public Double ramDamage;
        public Integer ramCooldownTicks;
        public Double ramSpeed;
        /** 追跡範囲の属性が無い Mob の狙う範囲(ブロック)。 */
        public Double targetRange;
        /** 元の攻撃を持つ種が、狙った相手に攻撃を当てられないまま この tick 過ぎたら体当たりの予備に切り替える(11-15 不具合の直し)。 */
        public Integer fallbackTicks;
    }

    public static final GenericInfectionSpec DEFAULT = from(null);

    public static GenericInfectionSpec from(Json j) {
        Json s = j != null ? j : new Json();
        return new GenericInfectionSpec(
                s.enabled == null || s.enabled,
                s.transformTicks == null ? 40 : Math.max(0, Math.min(1200, s.transformTicks)),
                d(s.bossMaxHealthFactor, 0.6D, 0.05D, 1.0D),
                s.bossEntities == null ? List.of() : List.copyOf(s.bossEntities),
                s.excludedEntities == null ? List.of() : List.copyOf(s.excludedEntities),
                d(s.ramDamage, 3.0D, 0.0D, 1000.0D),
                s.ramCooldownTicks == null ? 20 : Math.max(1, Math.min(1200, s.ramCooldownTicks)),
                d(s.ramSpeed, 1.2D, 0.1D, 5.0D),
                d(s.targetRange, 16.0D, 1.0D, 256.0D),
                s.fallbackTicks == null ? 60 : Math.max(1, Math.min(6000, s.fallbackTicks)));
    }

    private static double d(Double v, double def, double lo, double hi) {
        return v == null || !Double.isFinite(v) ? def : Math.max(lo, Math.min(hi, v));
    }
}
