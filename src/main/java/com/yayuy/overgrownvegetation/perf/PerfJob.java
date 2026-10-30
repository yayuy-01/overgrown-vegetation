package com.yayuy.overgrownvegetation.perf;

/**
 * {@link TickBudget} が時間を測る周期処理(2026-10-06)。{@code /ov perf} の表の行になる。
 *
 * <p>{@code deferrable} が true のものは<b>後回しにできる処理</b>(チャンクの書き換え・成長の待ち行列など。遅れても結果は同じ)で、
 * サーバーが重いときは予算の番人が 1 tick の仕事を縮める。false のものは測るだけ(レイド・TD・湧きなど、時間どおりに動かすもの)。
 *
 * <p>入れ子にしないこと: 外側の処理(例 {@code LedgerSpread#onLevelTick})と内側(例 {@code LedgerSpreadCatchUp#tick})を
 * 両方測ると、内側の時間が 2 回数えられて予算を食う。測るのは実際に仕事をする内側だけ。
 */
public enum PerfJob {
    LEDGER_CATCH_UP("台帳の書き換え(地表)", true),
    SPORE_SPROUTS("胞子の芽(発芽したチャンクの汚染)", true),
    SPORE_DEPOSIT_SWEEP("降り積もりによる繁茂化", true),
    LEDGER_PERIODS("台帳の広がりの判定", true),
    CLIMAX_MATERIALIZE("極相の反映(バイオーム)", true),
    CLIMAX_DEEP("極相の全層置換", true),
    BIOME_CREEP("過繁茂バイオームの浸食", true),
    GIANT_TREE("巨大樹の成長", true),
    GRAMMAR_GROWTH("構造物の成長", true),
    SAPLING_OFFLINE("苗木の追いつき成長", true),
    SWAMP_TREE_FALL("湿地・密林の木の片付け", true),
    SWAMP_FLOOD("湿地の水没", true),
    BUILD_CORROSION("建物の侵食", true),
    END_OVERGROWTH("エンドの侵食", true),
    NETHER_LEDGER("ネザーの書き換え", true),
    BASE_CONSTRUCTION("基地の工事", true),
    BASE_CHUNK_JOBS("基地のチャンク作業", true),
    BASE_ROADS("基地の道を敷く", true),
    BASE_UPKEEP_COMPARE("基地の整備の見比べ", true),
    BURNT_LAND("焼けた荒地を元に戻す", true),
    TREE_BURN("焼け切れた木が燃え落ちる", true),
    /** F1 の前の前兆(汲み上げ器の近くへの知らせ・軽い地鳴り。2026-10-24)。時間どおりに動かすので測るだけ。 */
    LEY_OMEN("地脈の前兆(知らせ・地鳴り)", false);

    /** {@code /ov perf} に出す名前。 */
    public final String label;
    /** サーバーが重いときに後回しにしてよいか。 */
    public final boolean deferrable;

    PerfJob(String label, boolean deferrable) {
        this.label = label;
        this.deferrable = deferrable;
    }
}
