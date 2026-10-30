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
 * <b>仮決め</b>: 睡眠でポイントも入れる・地表の塗り方・1 tick の予算。
 */
public final class SpreadTable {

    public static final class Json {
        public Boolean sleepAdvancesClock;
        public Boolean sleepGivesPhasePoints;
        public CatchUp catchUp;
    }

    /** {@code catchUp} 節(読み込まれたチャンクを書き換える)。 */
    public static final class CatchUp {
        /** 地表の列のうち汚染する割合(0〜1)。残りはまだらな塗り残し。 */
        public Double surfaceCoverage;
        /** 塗り残しの模様の大きさ(ブロック)。 */
        public Double patchSize;
        /** 地表から何マス下まで汚染できるマスを探すか(過繁茂バイオームの浸食と同じ既定 4)。 */
        public Integer surfaceDepth;
        /** 1 tick に置き換えるブロックの上限。 */
        public Integer placementsPerTick;
        /** 1 tick に見る列の上限。 */
        public Integer columnsPerTick;
    }

    private final boolean sleepAdvancesClock;
    private final boolean sleepGivesPhasePoints;
    private final double surfaceCoverage;
    private final double patchSize;
    private final int surfaceDepth;
    private final int placementsPerTick;
    private final int columnsPerTick;

    private SpreadTable(Json j) {
        Json src = j != null ? j : new Json();
        CatchUp c = src.catchUp != null ? src.catchUp : new CatchUp();

        this.sleepAdvancesClock = src.sleepAdvancesClock == null || src.sleepAdvancesClock;
        this.sleepGivesPhasePoints = src.sleepGivesPhasePoints == null || src.sleepGivesPhasePoints;
        this.surfaceCoverage = c.surfaceCoverage != null ? Math.max(0.0D, Math.min(1.0D, c.surfaceCoverage)) : 0.75D;
        this.patchSize = c.patchSize != null ? Math.max(1.0D, c.patchSize) : 6.0D;
        this.surfaceDepth = c.surfaceDepth != null ? Math.max(1, Math.min(16, c.surfaceDepth)) : 4;
        this.placementsPerTick = c.placementsPerTick != null ? Math.max(1, c.placementsPerTick) : 512;
        this.columnsPerTick = c.columnsPerTick != null ? Math.max(1, c.columnsPerTick) : 2048;
    }

    /** 睡眠で飛ばした夜を汚染の暦に足すか(ユーザー指定)。 */
    public boolean sleepAdvancesClock() {
        return sleepAdvancesClock;
    }

    /** 睡眠で飛ばした夜ぶんの時間経過ポイントも入れるか(仮決め: フェーズの日数を画面の「何日目」に合わせる)。 */
    public boolean sleepGivesPhasePoints() {
        return sleepGivesPhasePoints;
    }

    /** 読み込まれたチャンクの地表を汚染する割合。仮決め 0.75。 */
    public double surfaceCoverage() {
        return surfaceCoverage;
    }

    /** 塗り残しの模様の大きさ(ブロック)。仮決め 6。 */
    public double patchSize() {
        return patchSize;
    }

    /** 地表から何マス下まで汚染できるマスを探すか。 */
    public int surfaceDepth() {
        return surfaceDepth;
    }

    /** 書き換えで 1 tick に置き換えるブロックの上限。仮決め 512。 */
    public int placementsPerTick() {
        return placementsPerTick;
    }

    /** 書き換えで 1 tick に見る列の上限。仮決め 2048(= 8 チャンクぶん)。 */
    public int columnsPerTick() {
        return columnsPerTick;
    }

    /** 全部の値を決まった順に並べた文字列(同梱の json と Java の既定値を比べるテスト用)。 */
    public String describe() {
        Map<String, Object> out = new TreeMap<>();
        out.put("sleepAdvancesClock", sleepAdvancesClock);
        out.put("sleepGivesPhasePoints", sleepGivesPhasePoints);
        out.put("surfaceCoverage", surfaceCoverage);
        out.put("patchSize", patchSize);
        out.put("surfaceDepth", surfaceDepth);
        out.put("placementsPerTick", placementsPerTick);
        out.put("columnsPerTick", columnsPerTick);
        return out.toString();
    }

    /** {@link DesignData} と、値を差し替えた表を作るテストから。 */
    public static SpreadTable fromJson(Json j) {
        return new SpreadTable(j);
    }

    public static final SpreadTable DEFAULT = new SpreadTable(null);
}
