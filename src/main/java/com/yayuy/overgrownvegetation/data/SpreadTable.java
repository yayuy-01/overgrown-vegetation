package com.yayuy.overgrownvegetation.data;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 台帳の広がり({@code spread.json}、2026-09-26、2026-09-28 に規則を変更)の設計値。
 *
 * <p>過繁茂バイオームを<b>台帳だけで</b>広げる({@code corruption/LedgerSpread})。<b>{@code intervalTicks} ごとに</b>
 * (汚染の暦がその倍数の境目を越えるたびに。既定 6000 tick = 実時間 5 分 = ゲーム内 1/4 日)、過繁茂と<b>辺で接している</b>
 * チャンクごとに、接している面の数に応じた確率({@code chanceByContacts}、1 面 25%・2 面 50%・3 面 75%・4 面 100%)で過繁茂の印を付ける。
 * 読み込まれていないチャンクは台帳の印だけで、読み込まれたとき(または読み込まれていればすぐ)にバイオームと地表を書き換える。
 *
 * <p><b>ユーザー指定</b>: 5 分おき(2026-09-28、1 日 1 回では遅すぎた)/ 過繁茂に隣接するチャンクへ、1 面 25%・2 面 50%・3 面 75%・
 * 4 面 100% の確率で印 / 胞子除去装置の効果範囲には印を付けない / 読み込まれている範囲も対象 / 寝たときは飛ばした夜のぶんも広げる /
 * 広がった分もフェーズ進行に数える。
 * <b>仮決め</b>: 睡眠でポイントも入れる・地表の塗り方。
 * 2026-09-26〜27 は「1 日 190〜380 チャンクの予定表」、2026-09-28 の午前は「1 日 1 回(日付が変わるとき)」だった。
 */
public final class SpreadTable {

    public static final class Json {
        public Boolean enabled;
        /** 判定の間隔(汚染の暦の tick)。既定 6000 = 実時間 5 分。 */
        public Integer intervalTicks;
        /** 辺で接している過繁茂の数 1〜4 ごとの、1 回の判定で過繁茂になる確率(0〜1)。 */
        public List<Double> chanceByContacts;
        /** 読み込まれているチャンクにも印を付けるか。 */
        public Boolean includeLoadedChunks;
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

    private final boolean enabled;
    private final int intervalTicks;
    private final double[] chanceByContacts;
    private final boolean includeLoadedChunks;
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

        this.enabled = src.enabled == null || src.enabled;
        this.intervalTicks = src.intervalTicks != null ? Math.max(20, src.intervalTicks) : 6000;
        this.chanceByContacts = chances(src.chanceByContacts, new double[]{0.25D, 0.5D, 0.75D, 1.0D});
        this.includeLoadedChunks = src.includeLoadedChunks == null || src.includeLoadedChunks;
        this.sleepAdvancesClock = src.sleepAdvancesClock == null || src.sleepAdvancesClock;
        this.sleepGivesPhasePoints = src.sleepGivesPhasePoints == null || src.sleepGivesPhasePoints;
        this.surfaceCoverage = c.surfaceCoverage != null ? Math.max(0.0D, Math.min(1.0D, c.surfaceCoverage)) : 0.75D;
        this.patchSize = c.patchSize != null ? Math.max(1.0D, c.patchSize) : 6.0D;
        this.surfaceDepth = c.surfaceDepth != null ? Math.max(1, Math.min(16, c.surfaceDepth)) : 4;
        this.placementsPerTick = c.placementsPerTick != null ? Math.max(1, c.placementsPerTick) : 512;
        this.columnsPerTick = c.columnsPerTick != null ? Math.max(1, c.columnsPerTick) : 2048;
    }

    private static double[] chances(List<Double> list, double[] fallback) {
        if (list == null || list.isEmpty()) {
            return fallback;
        }
        double[] out = new double[Math.min(4, list.size())];
        for (int i = 0; i < out.length; i++) {
            Double v = list.get(i);
            out[i] = v != null ? Math.max(0.0D, Math.min(1.0D, v)) : 0.0D;
        }
        return out;
    }

    /** 台帳の広がりを動かすか。 */
    public boolean enabled() {
        return enabled;
    }

    /** 判定の間隔(汚染の暦の tick)。ユーザー指定 6000 = 実時間 5 分(ゲーム内 1/4 日、1 日 4 回)。 */
    public int intervalTicks() {
        return intervalTicks;
    }

    /**
     * 辺で接している過繁茂の数 1〜4 ごとの、1 回の判定で過繁茂になる確率。
     * ユーザー指定 1 面 0.25 / 2 面 0.5 / 3 面 0.75 / 4 面 1.0。
     */
    public double[] chanceByContacts() {
        return chanceByContacts;
    }

    /** 読み込まれているチャンクにも印を付けるか(ユーザー指定 true)。 */
    public boolean includeLoadedChunks() {
        return includeLoadedChunks;
    }

    /** 睡眠で飛ばした夜を汚染の暦に足すか(ユーザー指定)。飛ばした時間に入っていた判定のぶん、起きた直後に広がる。 */
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
        out.put("enabled", enabled);
        out.put("intervalTicks", intervalTicks);
        out.put("chanceByContacts", Arrays.toString(chanceByContacts));
        out.put("includeLoadedChunks", includeLoadedChunks);
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
