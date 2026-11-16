package com.yayuy.overgrownvegetation.worldevent;

import java.util.EnumMap;
import java.util.Map;

/**
 * ワールドイベントの設計値({@code world_events.json}、2026-11-16)。サーバー(日程・地脈の大潮の効き目)とクライアント(見た目の側が長さを読む)が読む。
 * <b>ユーザーが決めたのは</b>: 7 つ・どれか 1 つが 7〜15 日に 1 回【仮】・同時に 1 つ【仮】・どれが起きるかはくじ・オーロラと光る潮は F3 から・
 * 効き目は地脈の大潮だけ(汲み上げ器が少し速くなる【仮】)・日食は約 6 分で暗いのは約 1.5 分・影は朝か夕の約 10 分で霞か霧の日だけ。<b>ほかは【仮】</b>。
 *
 * <p>時刻は 1 日の中の tick(0 = 6 時・6000 = 正午・12000 = 18 時・18000 = 真夜中)。窓 {@code [from, to]} は「始まりの時刻をこの間からくじで選ぶ」。
 * from > to は真夜中をまたぐ。長さは tick(実時間 1 分 = 1200 tick)。
 */
public final class WorldEventTable {

    public static final WorldEventTable DEFAULT = new WorldEventTable(null);

    public static final class Json {
        public Double intervalMinDays;
        public Double intervalMaxDays;
        public Double fixedWaitDays;
        public Double fogWaitDays;
        public Double carrySeen;
        public Integer maxCarry;
        public Double leyTidePumpMultiplier;
        public Double shadowMinFog;
        public Integer shadowMinTicks;
        public Integer omenTod;
        public Map<String, OmenJson> omens;
        public Map<String, EventJson> events;
    }

    /** フェーズの合図 1 つ(上がるフェーズと長さ)。 */
    public static final class OmenJson {
        public Integer phase;
        public Integer length;
    }

    public static final class EventJson {
        public Double weight;
        public Integer minPhase;
        public int[][] windows;
        public Integer length;
        public Boolean skipInRain;
        public Boolean needFog;
        public Integer totality;
    }

    /** 1 つのイベントの値。 */
    public record Spec(double weight, int minPhase, int[][] windows, int length, boolean skipInRain, boolean needFog, int totality) {
    }

    /** 前のイベントの始まりの日から次のくじまで(日。ユーザー「7〜15 日に 1 回」【仮】)。新しいワールドの最初も同じ。 */
    public final double intervalMinDays, intervalMaxDays;
    /** 時刻の決まったイベントが雨・眠りで見送られ続けたとき、くじを引き直すまで(日)【仮】。 */
    public final double fixedWaitDays;
    /** 地平線を歩く影が霞・霧を待つ日数。過ぎたら影を外して引き直す【仮】。 */
    public final double fogWaitDays;
    /** 眠りで飛ばされたとき、見えた割合がこれ未満なら次の同じ時間帯へ持ち越す【仮】。持ち越しは {@link #maxCarry} 回まで。 */
    public final double carrySeen;
    public final int maxCarry;
    /** 地脈の大潮の間の汲み上げ器の汲む量の倍率【仮】。 */
    public final double leyTidePumpMultiplier;
    /** 影が始まるのに要る濃霧の度合い(0〜1)と、霧の残りがこれより短ければ始めない(tick)【仮】。 */
    public final double shadowMinFog;
    public final int shadowMinTicks;

    /** フェーズの合図(くじとは別枠。11-16 全体管理・event-look の頼み): 上がった後の次の日没のこの時刻に始める【仮】。 */
    public final int omenTod;
    private final EnumMap<WorldEventKind, int[]> omens = new EnumMap<>(WorldEventKind.class);

    private final EnumMap<WorldEventKind, Spec> specs = new EnumMap<>(WorldEventKind.class);

    private WorldEventTable(Json j) {
        Json s = j != null ? j : new Json();
        this.intervalMinDays = Math.max(0.0D, nn(s.intervalMinDays, 7.0D));
        this.intervalMaxDays = Math.max(this.intervalMinDays, nn(s.intervalMaxDays, 15.0D));
        this.fixedWaitDays = nn(s.fixedWaitDays, 3.0D);
        this.fogWaitDays = nn(s.fogWaitDays, 10.0D);
        this.carrySeen = Math.min(1.0D, nn(s.carrySeen, 0.5D));
        this.maxCarry = Math.max(0, s.maxCarry != null ? s.maxCarry : 0);
        this.leyTidePumpMultiplier = nn(s.leyTidePumpMultiplier, 1.25D);
        this.shadowMinFog = Math.min(1.0D, nn(s.shadowMinFog, 0.3D));
        this.shadowMinTicks = Math.max(1, s.shadowMinTicks != null ? s.shadowMinTicks : 2400);
        this.omenTod = s.omenTod != null ? Math.floorMod(s.omenTod, 24000) : 12500;
        // F3: その夜にオーロラが短く出る(2400 tick = 2 分【仮】)・F7: 一夜だけ胞子の柱(日没から夜明けまで)
        omens.put(WorldEventKind.AURORA, new int[]{3, 2400});
        omens.put(WorldEventKind.SPORE_PILLARS, new int[]{7, 10500});
        if (s.omens != null) {
            for (Map.Entry<String, OmenJson> e : s.omens.entrySet()) {
                WorldEventKind k = WorldEventKind.byId(e.getKey());
                if (k == null || e.getValue() == null) continue;
                int[] def = omens.getOrDefault(k, new int[]{-1, 2400});
                omens.put(k, new int[]{e.getValue().phase != null ? e.getValue().phase : def[0],
                        e.getValue().length != null ? Math.max(20, e.getValue().length) : def[1]});
            }
        }
        for (WorldEventKind k : WorldEventKind.values()) {
            Spec def = defaults(k);
            EventJson e = s.events != null ? s.events.get(k.id) : null;
            if (e == null) {
                specs.put(k, def);
                continue;
            }
            int[][] w = validWindows(e.windows) ? e.windows : def.windows();
            specs.put(k, new Spec(e.weight != null && e.weight >= 0 ? e.weight : def.weight(),
                    e.minPhase != null ? Math.max(0, e.minPhase) : def.minPhase(), w,
                    e.length != null ? Math.max(20, e.length) : def.length(),
                    e.skipInRain != null ? e.skipInRain : def.skipInRain(),
                    e.needFog != null ? e.needFog : def.needFog(),
                    e.totality != null ? Math.max(0, e.totality) : def.totality()));
        }
    }

    /** Java の既定値(design json と同じ値)。 */
    static Spec defaults(WorldEventKind k) {
        return switch (k) {
            // 日没(12000〜13000)の途中から 1 時間かけて出て、夜明け前(23000 = 5 時)に消える
            case AURORA -> new Spec(1, 3, new int[][]{{12500, 12500}}, 10500, true, false, 0);
            case GLOWING_TIDE -> new Spec(1, 3, new int[][]{{13000, 13000}}, 10000, false, false, 0);
            // 約 6 分(7200 tick)、真っ暗なのは約 1.5 分(1800)。日没前に終わるよう 7:30〜10:30 に始める
            case ECLIPSE -> new Spec(1, 0, new int[][]{{1500, 4500}}, 7200, true, false, 1800);
            // 半日(昼でも夜でも)
            case LEY_TIDE -> new Spec(1, 0, new int[][]{{0, 23999}}, 12000, false, false, 0);
            // 昼の数時間(3 時間 = 3000)
            case HALO -> new Spec(1, 0, new int[][]{{1000, 5000}}, 3000, true, false, 0);
            case METEOR_SHOWER -> new Spec(1, 0, new int[][]{{13000, 13000}}, 10000, true, false, 0);
            // 朝(4:30〜7:30)か夕(16:30〜18:00)の約 10 分。霧の残りで切る
            case HORIZON_SHADOW -> new Spec(1, 0, new int[][]{{22500, 1500}, {10500, 12000}}, 12000, false, true, 0);
            // F7 の合図だけ(くじには出ない。重さ 0)
            case SPORE_PILLARS -> new Spec(0, 7, new int[][]{{12500, 12500}}, 10500, false, false, 0);
        };
    }

    private static boolean validWindows(int[][] w) {
        if (w == null || w.length == 0) return false;
        for (int[] p : w) {
            if (p == null || p.length != 2 || p[0] < 0 || p[0] >= 24000 || p[1] < 0 || p[1] >= 24000) return false;
        }
        return true;
    }

    private static double nn(Double v, double fallback) {
        return v != null && Double.isFinite(v) ? Math.max(0.0D, v) : fallback;
    }

    /** この種がフェーズの合図になるフェーズ(ならなければ -1)。 */
    public int omenPhase(WorldEventKind k) {
        int[] o = omens.get(k);
        return o != null ? o[0] : -1;
    }

    /** フェーズの合図の長さ(tick)。 */
    public int omenLength(WorldEventKind k) {
        int[] o = omens.get(k);
        return o != null ? o[1] : spec(k).length();
    }

    public Spec spec(WorldEventKind k) {
        return specs.get(k);
    }

    public static WorldEventTable fromJson(Json j) {
        return j == null ? DEFAULT : new WorldEventTable(j);
    }
}
