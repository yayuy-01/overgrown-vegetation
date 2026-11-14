package com.yayuy.overgrownvegetation.data;

import java.util.Map;
import java.util.TreeMap;

/**
 * 台帳の書き換え待ちと睡眠({@code spread.json}、2026-09-26)の設計値。
 *
 * <p>広がりそのものは 2026-10-23 から胞子の場の発芽が担う({@code spore/field/})。発芽したチャンクは台帳に載り、読み込まれたとき
 * (または読み込まれていればすぐ)に {@code corruption/LedgerSpreadCatchUp} がバイオームを書き換える。
 * 止まっていた「5 分おきに縁を面の数 × 25%」の値({@code enabled}・{@code intervalTicks}・{@code chanceByContacts}・
 * {@code includeLoadedChunks})は 2026-10-30 に消した(古い json に残っていても読み飛ばす)。
 *
 * <p><b>ユーザー指定</b>: 寝たときは飛ばした夜のぶんも汚染の暦を進める。
 * <b>仮決め</b>: 睡眠でポイントも入れる・1 tick の予算。
 */
public final class SpreadTable {

    public static final class Json {
        public Boolean sleepAdvancesClock;
        public Boolean sleepGivesPhasePoints;
        public CatchUp catchUp;
        public WallPlantsJson wallPlants;
    }

    /**
     * {@code wallPlants} 節(壁の側面の植物を場所で選ぶ、2026-11-08。{@code corruption/WallPlantGrowth})。数はすべて仮決め。
     * 重みは場所ごとの相対の重み。引いたものが置けない所(垂れ苔は真上が固体の面だけ)では何も置かない。
     */
    public static final class WallPlantsJson {
        /** 空の光がこれ以上なら地上(湿っていないとき)。 */
        public Integer skyLight;
        /** 湿った壁とみなす水の探す半径(ブロック。上下は -2〜+1)。 */
        public Integer wetRadius;
        public Double surfaceLycopsid, surfaceMoss, surfaceFern;
        public Double wetMoss, wetFern, wetFernLarge;
        public Double caveMoss, caveSporeSacs, caveBracket;
        /** 地上の壁の小葉類のうちヒカゲノカズラ(form 2)にする割合。 */
        public Double clubmossShare;
    }

    /** 壁の植物の値(仮決め)。 */
    public record WallPlants(int skyLight, int wetRadius, double surfaceLycopsid, double surfaceMoss, double surfaceFern,
                             double wetMoss, double wetFern, double wetFernLarge, double caveMoss, double caveSporeSacs, double caveBracket,
                             double clubmossShare) {
        static WallPlants of(WallPlantsJson j) {
            WallPlantsJson s = j != null ? j : new WallPlantsJson();
            return new WallPlants(
                    s.skyLight != null ? Math.max(0, Math.min(15, s.skyLight)) : 10,
                    s.wetRadius != null ? Math.max(0, Math.min(4, s.wetRadius)) : 2,
                    w(s.surfaceLycopsid, 5), w(s.surfaceMoss, 2), w(s.surfaceFern, 1),
                    w(s.wetMoss, 4), w(s.wetFern, 2), w(s.wetFernLarge, 1),
                    w(s.caveMoss, 5), w(s.caveSporeSacs, 2), w(s.caveBracket, 1),
                    s.clubmossShare != null ? Math.max(0.0D, Math.min(1.0D, s.clubmossShare)) : 0.3D);
        }

        private static double w(Double v, double def) {
            return v != null ? Math.max(0.0D, v) : def;
        }
    }

    /** {@code catchUp} 節(読み込まれたチャンクを書き換える)。 */
    public static final class CatchUp {
        /** 1 tick の予算(列の数。1 チャンクの書き換えを 16 列に数える)。古い json の surfaceCoverage・patchSize・surfaceDepth・placementsPerTick(2026-11-14 に四角い塗りと一緒に消した)は読み飛ばす。 */
        public Integer columnsPerTick;
    }

    private final boolean sleepAdvancesClock;
    private final boolean sleepGivesPhasePoints;
    private final int columnsPerTick;
    private final WallPlants wallPlants;

    private SpreadTable(Json j) {
        Json src = j != null ? j : new Json();
        CatchUp c = src.catchUp != null ? src.catchUp : new CatchUp();

        this.sleepAdvancesClock = src.sleepAdvancesClock == null || src.sleepAdvancesClock;
        this.sleepGivesPhasePoints = src.sleepGivesPhasePoints == null || src.sleepGivesPhasePoints;
        this.columnsPerTick = c.columnsPerTick != null ? Math.max(1, c.columnsPerTick) : 2048;
        this.wallPlants = WallPlants.of(src.wallPlants);
    }

    /** 睡眠で飛ばした夜を汚染の暦に足すか(ユーザー指定)。 */
    public boolean sleepAdvancesClock() {
        return sleepAdvancesClock;
    }

    /** 睡眠で飛ばした夜ぶんの時間経過ポイントも入れるか(仮決め: フェーズの日数を画面の「何日目」に合わせる)。 */
    public boolean sleepGivesPhasePoints() {
        return sleepGivesPhasePoints;
    }

    /** 書き換えで 1 tick に使う予算(列の数。1 チャンク = 16 列)。仮決め 2048(= 128 チャンクぶん)。 */
    public int columnsPerTick() {
        return columnsPerTick;
    }

    /** 壁の植物を場所で選ぶ値(2026-11-08、仮決め)。 */
    public WallPlants wallPlants() {
        return wallPlants;
    }

    /** 全部の値を決まった順に並べた文字列(同梱の json と Java の既定値を比べるテスト用)。 */
    public String describe() {
        Map<String, Object> out = new TreeMap<>();
        out.put("sleepAdvancesClock", sleepAdvancesClock);
        out.put("sleepGivesPhasePoints", sleepGivesPhasePoints);
        out.put("columnsPerTick", columnsPerTick);
        out.put("wallPlants", wallPlants.toString());
        return out.toString();
    }

    /** {@link DesignData} と、値を差し替えた表を作るテストから。 */
    public static SpreadTable fromJson(Json j) {
        return new SpreadTable(j);
    }

    public static final SpreadTable DEFAULT = new SpreadTable(null);
}
