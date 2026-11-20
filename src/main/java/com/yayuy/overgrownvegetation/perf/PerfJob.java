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
    LEDGER_CATCH_UP("台帳の書き換え(バイオーム・芽)", true),
    SPORE_SPROUTS("胞子の芽(発芽したチャンクの汚染)", true),
    /** 洞窟の口の多さを測る(胞子の場の地下の層、2026-11-04)。 */
    SPORE_CAVE_MOUTHS("洞窟の口の測り(胞子の地下の層)", true),
    /** 洞窟の壁を這う汚染(2026-11-04)。 */
    CAVE_WALL_CREEP("洞窟の壁を這う汚染", true),
    /** 菌糸の染み下り(過繁茂の地下を地表から底へ。読み込んだときの追いつきも、2026-11-07)。 */
    MYCELIUM_SEEP("菌糸の染み下り(地下)", true),
    /** 走り待ちの芽の点(上限超え・読み込まれていない点を後で走らせる。2026-11-04)。 */
    SPORE_SPROUT_QUEUE("芽の点の待ち行列", true),
    SPORE_DEPOSIT_SWEEP("降り積もりによる繁茂化", true),
    BIOME_CREEP("過繁茂バイオームの浸食", true),
    GIANT_TREE("巨大樹の成長", true),
    GRAMMAR_GROWTH("構造物の成長", true),
    SAPLING_OFFLINE("苗木の追いつき成長", true),
    SWAMP_TREE_FALL("湿地・密林の木の片付け", true),
    /** 湿地の水没(湿地は 10-30 に退避。0.2.1 で作り直すので枠は残す。2026-11-14)。 */
    SWAMP_FLOOD("湿地の水没", true),
    BUILD_CORROSION("建物の侵食", true),
    /** 這い上がる苔が最終段を過ぎたら覆う(CoverCreepChain、2026-11-03)。 */
    COVER_CREEP("這い上がる苔の連鎖", true),
    END_OVERGROWTH("エンドの侵食", true),
    NETHER_LEDGER("ネザーの書き換え", true),
    /** 森の器官を育てる・枯らす(organ.OrganWorld、2026-11-17)。 */
    ORGAN_BUILD("森の器官の成長", true),
    /** 押し返したチャンクの数え直し(台帳から外す。2026-11-18)。 */
    LEDGER_SHRINK("押し返したチャンクの数え直し", true),
    /** 汚染された崖・急斜面に根の縄と嚢の房を生やす(corruption.cliff、2026-11-20)。 */
    CLIFF_CORRUPTION("崖の汚染", true);
    // 2026-11-14: 退避したもの(基地の工事・チャンク作業・道・整備の見比べ = BASE_*、焼けた荒地 BURNT_LAND、燃え落ちる木 TREE_BURN、
    // 地脈の前兆 LEY_OMEN)の行を消した(どこからも測っていなかった)。戻すときに足し直す。

    /** {@code /ov perf} に出す名前。 */
    public final String label;
    /** サーバーが重いときに後回しにしてよいか。 */
    public final boolean deferrable;

    PerfJob(String label, boolean deferrable) {
        this.label = label;
        this.deferrable = deferrable;
    }
}
