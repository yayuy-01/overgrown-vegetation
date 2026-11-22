package com.yayuy.overgrownvegetation.util;

/**
 * 「{@code interval} tick に 1 回」の重い周期処理を、<b>どの tick に走らせるか</b>(位相)の一覧
 * (2026-09-24 軽量化、報告 589 / 1516)。
 *
 * <p>以前はどれも {@code getGameTime() % interval == 0} で、位相が揃っていた。60・100・40・200 tick の処理が
 * 600 tick ごとに<b>同じ tick に全部重なり</b>、その tick だけ地形の書き換えがまとめて走っていた。
 * 処理ごとに違う位相を足して、重なりをばらす。走る頻度も 1 回の仕事量も変わらない —— 走る tick が
 * 1 周期の中でずれるだけ。
 *
 * <p>値はどれも仮決め 2026-09-24。既定の間隔(どれも 20 の倍数)どうしで重ならないよう、実際に走る tick を
 * 20 で割った余りが処理ごとに違う値になるものを選んである(浸食 13・建築浸食 3・湿地化 11・繁茂化 19・極相 9・
 * 外の侵攻種の湧き 10・胞子濃度のシダの湧き 5・生態系の防衛の湧き 17。浸食の深部パスは 13 から 4 tick に分けて回るので
 * 13〜16 を使う。位相 0 のままの過繁茂バイオームの湧き・フェーズ判定・レイドの 0 とも重ならない)。
 * 新しい周期処理(TD のウェーブなど)を足すときも、ここに載せて位相を選ぶこと。
 */
public final class TickPhases {

    /** 過繁茂バイオームの浸食({@code OvergrownBiomeCreep})。3 つのパスはさらに 1/3 周期ずつずらす。仮決め 2026-09-24。 */
    public static final int BIOME_CREEP = 7;
    /** 建築浸食({@code BuildCorrosion})。仮決め 2026-09-24。 */
    public static final int BUILD_CORROSION = 17;
    /** 湿地化({@code SwampFlooder})。仮決め 2026-09-24。 */
    public static final int SWAMP_FLOODER = 29;
    /** 降り積もりによる繁茂バイオーム化({@code DepositBiomeSweep}、もとは SporeBiomeSweep)。仮決め 2026-09-24。 */
    public static final int SPORE_BIOME_SWEEP = 41;
    /**
     * 過繁茂バイオームの外の侵攻種の湧き({@code OutsideInvaderSpawner})。同じ間隔の設定
     * ({@code overgrownSpawnIntervalTicks})を使う過繁茂バイオームの湧き({@code OvergrownBiomeSpawner}、位相 0)と
     * 同じ tick に重なっていたので、半周期ずらす(既定 60 tick なら 30 tick 後)。仮決め 2026-09-24。
     */
    public static final int OUTSIDE_INVADER_SPAWN = 30;
    /** 生態系の防衛の湧き({@code EcosystemDefense})。仮決め 2026-09-24。 */
    public static final int ECOSYSTEM_DEFENSE = 143;
    /**
     * TD イベントの帳簿(20 tick ごと: 始まり・一時停止・仮想の群れの生成と実体化・勝ち負け)と行進(5 tick ごと)
     * ({@code td/TdTicker})。20 で割った余り 7 の tick に走る(ほかのどれとも重ならない)。仮決め 2026-09-25。
     */
    public static final int TD_EVENT = 13;

    /**
     * {@code gameTime} が「{@code interval} tick 周期の、位相 {@code phase} の tick」か。
     * {@code (gameTime + phase) % interval == 0} と同じ(負にならない剰余で計算する)。
     * {@code interval} は 1 以上を渡すこと。
     */
    public static boolean isDue(long gameTime, int phase, long interval) {
        return Math.floorMod(gameTime + phase, interval) == 0L;
    }

    /**
     * 1 周期の中の位置({@code (gameTime + phase) mod interval}、0 〜 {@code interval - 1})。
     * 1 つの周期処理を、周期の中の別々の tick に分けて走らせるときに使う。
     */
    public static long offsetInCycle(long gameTime, int phase, long interval) {
        return Math.floorMod(gameTime + phase, interval);
    }

    private TickPhases() {
    }
}
