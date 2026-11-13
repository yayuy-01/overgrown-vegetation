package com.yayuy.overgrownvegetation.data;

import com.yayuy.overgrownvegetation.entity.SpecialPacing;
import com.yayuy.overgrownvegetation.entity.action.ActionDef;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mob の技の調整値({@code design/actions.json}、2026-11-05。層 2 の共通部品 {@link ActionDef} の受け皿)。
 *
 * <p>形: {@code { "<種の id>": { "pacing": {...}, "actions": { "<技>": {...} } } }}。書いていない値は Java の既定値
 * ({@link #DEFAULT})。技の時刻(前触れ・実行・隙)はアニメと対なので json に置かない(種のコードの {@code ActionTiming})。
 *
 * <p><b>値は 3 か所</b>(記憶「config の値は3か所」): ここの既定値・{@code src/main/resources/overgrown_vegetation/design/actions.json}・
 * {@code run/config/overgrown_vegetation/actions.json}(実行時の写し。既定値や design を変えても古いまま)。
 *
 * <p>ファルキムネスの数値はすべて<b>仮</b>(2026-11-05 Claude 案、ユーザー「概ね良い」)。ユーザー指定は 掴みの止めは即死でなく特大ダメージ・
 * 前触れ 0.7 / 1.2 / 2.5 秒(横薙ぎ・連撃・掴み)・特殊技の出し方(交戦 6 秒 + 通常技 2 回で解禁、35%)。
 */
public final class ActionTable {

    /** Gson の受け皿。null は既定値。 */
    public static final class Json extends LinkedHashMap<String, MobJson> {
    }

    public static final class MobJson {
        public PacingJson pacing;
        public Map<String, DefJson> actions;
    }

    public static final class PacingJson {
        public Integer warmupTicks;
        public Integer normalsBeforeFirst;
        public Integer normalsBetween;
        public Double chance;
    }

    public static final class DefJson {
        public Integer cooldown;
        public Double minRange;
        public Double maxRange;
        public Double score;
        public Double recency;
        public Double chance;
        public Double damage;
        public Map<String, Double> extra;
    }

    record Pacing(int warmup, int before, int between, float chance) {
    }

    private static final Map<String, Map<String, ActionDef>> DEFAULT_ACTIONS = new LinkedHashMap<>();
    private static final Map<String, Pacing> DEFAULT_PACING = new LinkedHashMap<>();

    static {
        Map<String, ActionDef> f = new LinkedHashMap<>();
        // 横薙ぎ(近)。届く距離は種が決める(maxRange 0)。クールダウンは始めてから(振り 36 tick + 間 10)。
        f.put("sweep", new ActionDef("sweep", 46, 0.0D, 0.0D, 1.0D, 0.5D, 1.0D, 0.0D, m("cautious", 0.8D, "aggressive", 1.2D)));
        // 連撃(近)。1 振り = 攻撃力 × damageFactor。
        f.put("flurry", new ActionDef("flurry", 390, 0.0D, 0.0D, 0.45D, 0.25D, 1.0D, 0.0D,
                m("damageFactor", 0.3D, "moveSpeed", 0.55D, "finishFactor", 1.0D, "cautious", 0.4D, "aggressive", 2.5D)));   // finishFactor = 締めの X 字(段 2、仮)
        // 掴み(近〜中、間隔は長め)。damage = 止め(11-05 即死をやめた。防具は効く)。噛みつき・振りほどきの量・突進の速さ。
        f.put("grab", new ActionDef("grab", 600, 9.0D, 21.6D, 0.6D, 0.3D, 0.05D, 30.0D,
                m("bite", 8.0D, "breakFree", 15.0D, "dashSpeed", 0.95D, "bites", 3.0D, "cautious", 0.7D, "aggressive", 1.5D)));
        // 飛ぶ斬撃(遠、11-05 新規)。毎秒 20 ブロック・16 ブロックで消える・横 3 × 厚み 0.5。盾で防げる。
        f.put("wave", new ActionDef("wave", 144, 6.0D, 16.0D, 0.8D, 0.3D, 0.06D, 10.0D,
                m("speed", 1.0D, "range", 16.0D, "width", 3.0D, "height", 0.5D, "cautious", 1.5D, "aggressive", 0.8D)));
        // 地走りの光の帯(特殊、11-05 新規)。筋は毎秒 24 ブロック・相手の足元の 2 先まで(最大 18)・幅 1.5。
        // 筋が伸びきって 12 tick 後に高さ 3 の帯が 8 tick 立つ。打ち上げ 0.6。盾では防げない。
        // 11-05 夜(ユーザー指定「近くでも出す」「刺した所で円く噴き出す」): 刺した所から前へ 2 未満の相手には、刺した所を中心に半径 4 の円で噴き出す(4 と 2 は仮)。
        Map<String, Double> rift = new LinkedHashMap<>();
        rift.put("lineSpeed", 1.2D);
        rift.put("lineMax", 18.0D);
        rift.put("overshoot", 2.0D);
        rift.put("width", 1.5D);
        rift.put("bandDelay", 12.0D);
        rift.put("bandTicks", 8.0D);
        rift.put("bandHeight", 3.0D);
        rift.put("lift", 0.6D);
        rift.put("burstRadius", 4.0D);
        rift.put("burstUnder", 2.0D);
        f.put("rift", new ActionDef("rift", 400, 0.0D, 22.0D, 1.0D, 1.0D, 1.0D, 14.0D, rift));
        // 段 2(11-07 夜)の小さな通常技。全部【仮】。damage 0 は種の攻撃力。maxRange 0 は種が決める届く距離(交差斬りだけ短く)。
        f.put("kiriage", new ActionDef("kiriage", 70, 0.0D, 0.0D, 0.7D, 0.4D, 1.0D, 14.0D, m("lift", 0.5D, "shieldBreak", 40.0D, "cautious", 2.0D, "counter", 1.0D, "comboCut", 0.6D,
                "next.hit.kozuki", 1.0D, "next.hit.tsukikaki", 0.5D, "next.blocked.kosa", 1.0D, "next.missed.fumikomi", 0.5D)));
        f.put("tsukikaki", new ActionDef("tsukikaki", 90, 0.0D, 0.0D, 0.6D, 0.4D, 1.0D, 6.0D, m("pull", 0.6D, "cautious", 2.0D, "counter", 1.0D, "comboCut", 0.55D,
                "next.hit.kozuki", 1.0D, "next.hit.kosa", 1.0D, "next.blocked.kiriage", 1.0D, "next.missed.fumikomi", 1.0D)));
        f.put("kosa", new ActionDef("kosa", 80, 0.0D, 5.5D, 0.7D, 0.4D, 1.0D, 12.0D, m("cautious", 0.6D, "aggressive", 2.5D, "comboCut", 0.8D,
                "next.hit.kozuki", 1.0D, "next.blocked.kiriage", 1.0D, "next.missed.tsukikaki", 1.0D, "next.missed.fumikomi", 1.0D)));
        f.put("fumikomi", new ActionDef("fumikomi", 80, 0.0D, 0.0D, 0.6D, 0.4D, 1.0D, 12.0D, m("sidestep", 0.16D, "flank", 3.0D, "comboCut", 0.8D,
                "next.hit.kozuki", 1.0D, "next.hit.kosa", 0.5D, "next.blocked.kiriage", 1.0D, "next.missed.kosa", 0.5D)));
        f.put("kozuki", new ActionDef("kozuki", 30, 0.0D, 0.0D, 0.9D, 0.4D, 1.0D, 5.0D, m("cautious", 2.5D, "aggressive", 0.8D, "counter", 1.0D,
                "next.hit.kiriage", 1.0D, "next.hit.tsukikaki", 0.5D, "next.blocked.kiriage", 2.0D, "next.missed.tsukikaki", 1.0D, "next.missed.fumikomi", 0.5D)));
        // 段 3(11-08)個体の性格の数値(選ばれない擬似の技。extra だけ)。全部【仮】。FalcimnesPersonality の javadoc。
        f.put("personality", new ActionDef("personality", 0, 0.0D, 0.0D, 0.0D, 1.0D, 0.0D, 0.0D, m(
                "comboChance", 0.35D, "comboAggressive", 2.2D, "comboCautious", 0.7D, "comboCut", 0.35D,
                "comboOnHit", 1.0D, "comboOnBlocked", 1.3D, "comboOnMissed", 0.6D,
                "keepDistance", 0.8D, "backpedal", 0.5D, "patienceTicks", 160.0D, "commitTicks", 60.0D, "flankAngle", 60.0D)));
        DEFAULT_ACTIONS.put("falcimnes", Collections.unmodifiableMap(f));
        DEFAULT_PACING.put("falcimnes", new Pacing(120, 2, 2, 0.35F));

        // アルボラ(11-05、すべて仮)。鎌は届けば毎回(始めてから 1.1 秒で次)。飛びつきは 3 ブロックより遠い相手へ
        // 「たまに」(11-04 ユーザー「たまに跳んで飛びついてくる」→ 間合いに入った 1 tick ごとに 8%)、始めてから 3.5 秒に 1 回まで(前と同じ)。
        // 跳ぶ勢い 横 1.15 / 上 0.55 は前と同じ(約 9.7 ブロック先・高さ 2 まで届く、LeapArc)。maxRange はその内側。
        Map<String, ActionDef> a = new LinkedHashMap<>();
        a.put("scythe", new ActionDef("scythe", 22, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D, 0.0D, Map.of()));
        a.put("leap", new ActionDef("leap", 70, 3.0D, 9.5D, 1.0D, 1.0D, 0.08D, 0.0D,
                Map.of("horizontalSpeed", 1.15D, "lift", 0.55D)));
        DEFAULT_ACTIONS.put("arbora", Collections.unmodifiableMap(a));
    }

    /** extra の表(鍵と値を交互に)。Map.of は 10 組までなので。 */
    private static Map<String, Double> m(Object... kv) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        }
        return out;
    }

    public static final ActionTable DEFAULT = new ActionTable(null);

    private final Map<String, Map<String, ActionDef>> actions = new LinkedHashMap<>();
    private final Map<String, Pacing> pacing = new LinkedHashMap<>();

    private ActionTable(Json j) {
        this.actions.putAll(DEFAULT_ACTIONS);
        this.pacing.putAll(DEFAULT_PACING);
        if (j == null) {
            return;
        }
        for (Map.Entry<String, MobJson> e : j.entrySet()) {
            String mob = e.getKey();
            MobJson mj = e.getValue();
            if (mob.startsWith("_") || mj == null) {
                continue;
            }
            if (mj.pacing != null) {
                Pacing base = this.pacing.getOrDefault(mob, new Pacing(120, 2, 2, 0.35F));
                PacingJson p = mj.pacing;
                this.pacing.put(mob, new Pacing(
                        p.warmupTicks != null ? Math.max(0, p.warmupTicks) : base.warmup(),
                        p.normalsBeforeFirst != null ? Math.max(0, p.normalsBeforeFirst) : base.before(),
                        p.normalsBetween != null ? Math.max(0, p.normalsBetween) : base.between(),
                        p.chance != null ? (float) Math.max(0.0D, Math.min(1.0D, p.chance)) : base.chance()));
            }
            if (mj.actions != null) {
                Map<String, ActionDef> merged = new LinkedHashMap<>(this.actions.getOrDefault(mob, Map.of()));
                for (Map.Entry<String, DefJson> a : mj.actions.entrySet()) {
                    if (a.getValue() == null || a.getKey().startsWith("_")) {
                        continue;
                    }
                    merged.put(a.getKey(), merge(a.getKey(), merged.get(a.getKey()), a.getValue()));
                }
                this.actions.put(mob, Collections.unmodifiableMap(merged));
            }
        }
    }

    private static ActionDef merge(String id, ActionDef base, DefJson d) {
        ActionDef b = base != null ? base : new ActionDef(id, 0, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D, 0.0D, Map.of());
        Map<String, Double> extra = new LinkedHashMap<>(b.extra());
        if (d.extra != null) {
            d.extra.forEach((k, v) -> {
                if (v != null) extra.put(k, v);
            });
        }
        return new ActionDef(id,
                d.cooldown != null ? Math.max(0, d.cooldown) : b.cooldown(),
                d.minRange != null ? Math.max(0.0D, d.minRange) : b.minRange(),
                d.maxRange != null ? d.maxRange : b.maxRange(),
                d.score != null ? Math.max(0.0D, d.score) : b.score(),
                d.recency != null ? Math.max(0.0D, Math.min(1.0D, d.recency)) : b.recency(),
                d.chance != null ? Math.max(0.0D, Math.min(1.0D, d.chance)) : b.chance(),
                d.damage != null ? Math.max(0.0D, d.damage) : b.damage(),
                extra);
    }

    public static ActionTable fromJson(Json j) {
        return j == null ? DEFAULT : new ActionTable(j);
    }

    /** 種の技(無ければ空)。 */
    public Map<String, ActionDef> actions(String mob) {
        return this.actions.getOrDefault(mob, Map.of());
    }

    /** 種の技を 1 つ(無ければ例外 —— 既定値に必ずある名前で引く)。 */
    public ActionDef def(String mob, String id) {
        ActionDef d = actions(mob).get(id);
        if (d == null) {
            throw new IllegalArgumentException("技が無い: " + mob + "/" + id);
        }
        return d;
    }

    /** 種の特殊技の出し方(新しい個体ごとに 1 つ作る)。 */
    public SpecialPacing newPacing(String mob) {
        Pacing p = this.pacing.getOrDefault(mob, new Pacing(120, 2, 2, 0.35F));
        return new SpecialPacing(p.warmup(), p.before(), p.between(), p.chance());
    }
}
