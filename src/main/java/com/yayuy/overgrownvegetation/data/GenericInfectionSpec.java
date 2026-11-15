package com.yayuy.overgrownvegetation.data;

import java.util.List;

/**
 * 汎用の感染体(専用の G1 が無い生き物が、生きたまま植物側に変わる。2026-11-15 ユーザー決定)の設計値
 * ({@code spore_field.json} の {@code genericInfection})。読むのは {@code infect/GenericInfection}。正本は Vault「G1 媒介種(寄生された動物)」の「汎用の感染体」節。
 *
 * <h2>ユーザー指定(★)</h2>
 * <ul>
 *     <li>対象は専用の G1 が無い生き物すべて(バニラ + ほかの MOD。ボス・飛ぶ・泳ぐものも)。</li>
 *     <li>ボスは変わると最大体力が元の {@code bossMaxHealthFactor} 0.6 倍(ほかのステータスはそのまま)。</li>
 * </ul>
 *
 * <h2>仮決め【仮】</h2>
 * <ul>
 *     <li>変わる動き(立ち止まって震える → 植物が突き出る → 動き出す)の長さ {@code transformTicks} 40(約 2 秒)。</li>
 *     <li>ボス = タグ {@code c:bosses} に入っている種と、{@code bossEntities} に書いた種。</li>
 *     <li>変わらない種 {@code excludedEntities}: ウォーデン(感染の道は 0.7.0 の地下と一緒に決める。11-09 ユーザー)。</li>
 * </ul>
 */
public record GenericInfectionSpec(
        boolean enabled,
        int transformTicks,
        double bossMaxHealthFactor,
        List<String> bossEntities,
        List<String> excludedEntities) {

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
    }

    public static final GenericInfectionSpec DEFAULT = from(null);

    public static GenericInfectionSpec from(Json j) {
        Json s = j != null ? j : new Json();
        return new GenericInfectionSpec(
                s.enabled == null || s.enabled,
                s.transformTicks == null ? 40 : Math.max(0, Math.min(1200, s.transformTicks)),
                s.bossMaxHealthFactor == null || !Double.isFinite(s.bossMaxHealthFactor) ? 0.6D
                        : Math.max(0.05D, Math.min(1.0D, s.bossMaxHealthFactor)),
                s.bossEntities == null ? List.of() : List.copyOf(s.bossEntities),
                s.excludedEntities == null ? List.of("minecraft:warden") : List.copyOf(s.excludedEntities));
    }
}
