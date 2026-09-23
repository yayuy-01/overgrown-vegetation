package com.yayuy.overgrownvegetation.data;

/**
 * {@code climax.json} — 極相({@code engulfed})バイオームと浸食度の設計値(2026-09-22)。
 *
 * <ul>
 *     <li>{@code biome}: 過繁茂 → 極相の昇格条件。{@code neighborRadiusChunks} の半径にある
 *     チャンクが全て<b>過繁茂以上</b>(過繁茂 or 既に極相)で、かつ自分が過繁茂になってから
 *     {@code minAgeDays} 日経っていること。判定は過繁茂チャンク台帳
 *     ({@code PhaseData#canBecomeClimax})を引くだけなので、チャンクの読み込みは要らない。
 *     {@code naturalSpawns} が false の間、極相には通常の Mob が一切湧かない
 *     (極相に湧くのは G-EX だけ、という設計)。</li>
 *     <li>{@code erosion}: 極相に居ると溜まる汚染(0〜100%)。効果はすべて
 *     <b>「100% のときの値 × 現在の %」で線形に効く</b> —— 最大体力 -50% は 34% のとき -17%
 *     (体力 20 なら -3.4)。</li>
 *     <li>{@code erosion.carrier}: 浸食度を極相の外へ持ち出している間の副作用。
 *     {@code thresholdPercent} まで下がると消える。</li>
 *     <li>{@code erosion.render}: プレイヤーの見た目(色かぶりと苔のオーバーレイ)。</li>
 * </ul>
 *
 * <p>{@code /ov design reload} で即反映。
 */
public final class ClimaxTable {

    public static final class Json {
        public Biome biome;
        public LeyVeinJson leyVein;
        public OvergrownLayer overgrownLayer;
        public DeepConversion deepConversion;
        public Erosion erosion;
    }

    public static final class Biome {
        public Integer neighborRadiusChunks;
        public Double minAgeDays;
        public Integer checkIntervalTicks;
        public Double daylightBrightness;
        public String plantTint;
        public Boolean naturalSpawns;
    }

    /** {@code deepConversion} 節の生の形。 */
    public static final class DeepConversion {
        public Boolean enabled;
        public Integer blocksPerTick;
    }

    /** {@code overgrownLayer} 節の生の形。 */
    public static final class OvergrownLayer {
        public Double chance;
        public Boolean encase;
    }

    /** {@code leyVein} 節の生の形。 */
    public static final class LeyVeinJson {
        public Double cellSize;
        public Double width;
        public Double warp;
        public Integer depth;
    }

    /**
     * 地脈の場の設計値。{@code corruption/LeyVeinField} が読む。
     *
     * @param cellSize 網目の粗さ(ボロノイのセル 1 辺、ブロック)。大きいほど網目が粗い
     * @param width    地脈の幅(ブロック)
     * @param warp     境界線をうねらせる強さ(セルの大きさに対する割合)。0 で直線的な多角形になる
     * @param depth    地表から何ブロック下まで地脈ブロックにするか
     */
    public record LeyVein(double cellSize, double width, double warp, int depth) {
    }

    public static final class Erosion {
        public Integer gainIntervalTicks;
        public Double gainPerInterval;
        public Integer decayIntervalTicks;
        public Double decayPerInterval;
        public Double maxPercent;
        public Effects effects;
        public Carrier carrier;
        public Render render;
    }

    public static final class Effects {
        public Double maxHealthReduction;
        public Double maxFoodReduction;
        public Double xpReduction;
        public Double gearContaminationBonus;
        public Double foodRestoreReduction;
        public Double regenFoodFraction;
    }

    public static final class Carrier {
        public Double thresholdPercent;
        public Double overgrownSpawnMultiplier;
        public Double sporeConcentrationBonus;
    }

    public static final class Render {
        public String tintColor;
        public Double tintMaxAlpha;
        public Integer overlayStages;
        public Double overlayScale;
    }

    private final int neighborRadiusChunks;
    private final long minAgeTicks;
    private final int checkIntervalTicks;
    private final boolean naturalSpawns;
    private final float daylightBrightness;
    private final LeyVein leyVein;
    private final double overgrownLayerChance;
    private final boolean encase;
    private final boolean deepConversion;
    private final int deepConversionBlocksPerTick;
    private final int plantTint;

    private final int gainIntervalTicks;
    private final double gainPerInterval;
    private final int decayIntervalTicks;
    private final double decayPerInterval;
    private final double maxPercent;

    private final double maxHealthReduction;
    private final double maxFoodReduction;
    private final double xpReduction;
    private final double gearContaminationBonus;
    private final double foodRestoreReduction;
    private final double regenFoodFraction;

    private final double carrierThresholdPercent;
    private final double carrierOvergrownSpawnMultiplier;
    private final double carrierSporeConcentrationBonus;

    private final int tintColor;
    private final double tintMaxAlpha;
    private final int overlayStages;
    private final double overlayScale;

    private ClimaxTable(Json j) {
        Json src = j != null ? j : new Json();
        Biome biome = src.biome != null ? src.biome : new Biome();
        Erosion deep = src.erosion != null ? src.erosion : new Erosion();
        Effects fx = deep.effects != null ? deep.effects : new Effects();
        Carrier carrier = deep.carrier != null ? deep.carrier : new Carrier();
        Render render = deep.render != null ? deep.render : new Render();

        this.neighborRadiusChunks = biome.neighborRadiusChunks != null
                ? Math.max(0, Math.min(32, biome.neighborRadiusChunks)) : 8;
        double days = biome.minAgeDays != null ? Math.max(0.0D, biome.minAgeDays) : 14.0D;
        this.minAgeTicks = (long) (days * 24000.0D);
        this.checkIntervalTicks = biome.checkIntervalTicks != null
                ? Math.max(1, biome.checkIntervalTicks) : 200;
        this.naturalSpawns = biome.naturalSpawns != null && biome.naturalSpawns;
        this.daylightBrightness = (float) clamp01(biome.daylightBrightness, 0.2D);
        this.plantTint = parseHex(biome.plantTint, 0xAEBDB6);

        LeyVeinJson ley = src.leyVein != null ? src.leyVein : new LeyVeinJson();
        this.leyVein = new LeyVein(
                ley.cellSize != null ? Math.max(16.0D, ley.cellSize) : 192.0D,
                ley.width != null ? Math.max(0.0D, ley.width) : 5.0D,
                ley.warp != null ? Math.max(0.0D, Math.min(1.0D, ley.warp)) : 0.35D,
                ley.depth != null ? Math.max(1, Math.min(16, ley.depth)) : 2);

        OvergrownLayer layer = src.overgrownLayer != null ? src.overgrownLayer : new OvergrownLayer();
        this.overgrownLayerChance = clamp01(layer.chance, 0.7D);
        this.encase = layer.encase == null || layer.encase;

        DeepConversion deepConv = src.deepConversion != null ? src.deepConversion : new DeepConversion();
        this.deepConversion = deepConv.enabled == null || deepConv.enabled;
        this.deepConversionBlocksPerTick = deepConv.blocksPerTick != null
                ? Math.max(256, Math.min(65536, deepConv.blocksPerTick)) : 4096;

        this.gainIntervalTicks = deep.gainIntervalTicks != null ? Math.max(1, deep.gainIntervalTicks) : 1200;
        this.gainPerInterval = deep.gainPerInterval != null ? Math.max(0.0D, deep.gainPerInterval) : 1.0D;
        this.decayIntervalTicks = deep.decayIntervalTicks != null ? Math.max(1, deep.decayIntervalTicks) : 600;
        this.decayPerInterval = deep.decayPerInterval != null ? Math.max(0.0D, deep.decayPerInterval) : 1.0D;
        this.maxPercent = deep.maxPercent != null ? Math.max(1.0D, deep.maxPercent) : 100.0D;

        this.maxHealthReduction = clamp01(fx.maxHealthReduction, 0.5D);
        this.maxFoodReduction = clamp01(fx.maxFoodReduction, 0.5D);
        this.xpReduction = clamp01(fx.xpReduction, 0.5D);
        this.gearContaminationBonus = fx.gearContaminationBonus != null
                ? Math.max(0.0D, fx.gearContaminationBonus) : 5.0D;
        this.foodRestoreReduction = clamp01(fx.foodRestoreReduction, 0.5D);
        this.regenFoodFraction = clamp01(fx.regenFoodFraction, 0.9D);

        this.carrierThresholdPercent = carrier.thresholdPercent != null
                ? Math.max(0.0D, carrier.thresholdPercent) : 25.0D;
        this.carrierOvergrownSpawnMultiplier = carrier.overgrownSpawnMultiplier != null
                ? Math.max(1.0D, carrier.overgrownSpawnMultiplier) : 2.0D;
        this.carrierSporeConcentrationBonus = carrier.sporeConcentrationBonus != null
                ? Math.max(0.0D, carrier.sporeConcentrationBonus) : 15.0D;

        this.tintColor = parseHex(render.tintColor, 0x1D2714);
        this.tintMaxAlpha = clamp01(render.tintMaxAlpha, 0.25D);
        this.overlayStages = render.overlayStages != null ? Math.max(1, Math.min(8, render.overlayStages)) : 3;
        this.overlayScale = render.overlayScale != null
                ? Math.max(1.0D, Math.min(1.5D, render.overlayScale)) : 1.02D;
    }

    private static double clamp01(Double value, double fallback) {
        return value != null ? Math.max(0.0D, Math.min(1.0D, value)) : fallback;
    }

    /** {@code "#1D2714"} / {@code "1D2714"} のどちらでも読む。読めなければ {@code fallback}。 */
    private static int parseHex(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        String s = value.startsWith("#") ? value.substring(1) : value;
        try {
            return Integer.parseInt(s, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // --- biome ---

    /** 極相になるために「周囲がすべて過繁茂以上」である必要のある半径(チャンク)。既定 8 = 17×17。 */
    public int neighborRadiusChunks() {
        return neighborRadiusChunks;
    }

    /** 過繁茂になってから極相になるまでに必要な時間(tick)。既定 14 日 = 336000。 */
    public long minAgeTicks() {
        return minAgeTicks;
    }

    /** 極相化の判定を回す間隔(tick)。 */
    public int checkIntervalTicks() {
        return checkIntervalTicks;
    }

    /** 極相に通常の Mob を湧かせるか。既定 false(極相に湧くのは G-EX だけ)。 */
    public boolean naturalSpawns() {
        return naturalSpawns;
    }

    /** 地脈の場の設計値。 */
    public LeyVein leyVein() {
        return leyVein;
    }

    /**
     * 対応表に変換先が無いブロックを {@code overgrown_layer} で覆う確率(0〜1、既定 0.7)。
     *
     * <p>覆うかどうかは<b>座標だけの決定的な関数</b>で決まる(記録も同期もしない)ので、
     * この値を変えると<b>既に覆われている場所も変わる</b>。
     * 値を変えたらチャンクを描き直さないと反映されない(F3+A)。
     * 0 にするとオーバーレイの処理そのものを止める(セクション描画に一切触らなくなる)。
     */
    public double overgrownLayerChance() {
        return overgrownLayerChance;
    }

    /**
     * 覆われた<b>中身を持つブロック</b>(他 MOD の機械など)を、
     * 「見た目は同じ・機能は無い」ブロック({@code overgrown_husk})へ差し替えるか(既定 true)。
     *
     * <p><b>{@code corruptBlockEntities}(config の toml)とは別物。</b> あちらは
     * 「チェストなどが中身ごと消えるのを防ぐ」ための設定。こちらは<b>消さない</b> ——
     * 見た目も中身も残して機能だけ止めるので、別の切り替えにしてある。
     * false にすると他 MOD の機械は覆われず、そのまま動き続ける。
     */
    public boolean encase() {
        return encase;
    }

    /**
     * 極相になったチャンクを<b>岩盤まで丸ごと</b>過繫茂ブロックへ置き換えるか(2026-09-22、要望)。
     * 「地下の見えないブロックの部分まで完全に過繫茂ブロックに置き換えられる」ための工程。
     */
    public boolean deepConversion() {
        return deepConversion;
    }

    /**
     * 全層置換で 1 tick に<b>調べてよい</b>ブロック数(既定 4096 = 1 セクションぶん)。
     * 1 チャンクは 16×16×384 ≒ 98000 マスあるので、既定値だと 1 チャンクに 24 tick かかる。
     * 大きくすると早く終わるが、その tick が重くなる。
     */
    public int deepConversionBlocksPerTick() {
        return deepConversionBlocksPerTick;
    }

    /**
     * 極相の中で空の光がどれだけ届くか(0 = 真っ暗、0.2 = バニラの夜と同じ、1 = 昼のまま)。
     * <b>見た目だけを変える</b> —— サーバー側の明るさは変わらないので、湧き判定・作物の成長・
     * 日光による炎上には影響しない。
     */
    public float daylightBrightness() {
        return daylightBrightness;
    }

    /**
     * 極相の中で草ブロック・下草・茂みに掛ける色(0xRRGGBB)。テクスチャに<b>乗算</b>されるので、
     * { 0xFFFFFF} なら変化なし。既定 { #AEBDB6} = 明るさ 7 割ほど + わずかに寒色へ。
     *
     * <p>この色はチャンクのメッシュに焼き込まれるので、{ /ov design reload} だけでは
     * <b>既に描かれているチャンクには反映されない</b>(F3+A で読み込み直す)。
     */
    public int plantTint() {
        return plantTint;
    }

    // --- 浸食度 ---

    /** 極相に居るとき、この tick ごとに {@link #gainPerInterval} % 増える。 */
    public int gainIntervalTicks() {
        return gainIntervalTicks;
    }

    public double gainPerInterval() {
        return gainPerInterval;
    }

    /** 極相の外に居るとき、この tick ごとに {@link #decayPerInterval} % 減る。 */
    public int decayIntervalTicks() {
        return decayIntervalTicks;
    }

    public double decayPerInterval() {
        return decayPerInterval;
    }

    public double maxPercent() {
        return maxPercent;
    }

    /** 浸食度 {@code percent} % のときの効き具合(0〜1)。すべての効果はこれを掛けて使う。 */
    public double scale(double percent) {
        if (percent <= 0.0D) {
            return 0.0D;
        }
        return Math.min(1.0D, percent / maxPercent);
    }

    /** 最大体力の減少率(100% のとき)。0.5 = 半減。 */
    public double maxHealthReduction() {
        return maxHealthReduction;
    }

    /** 食料ゲージ最大値の減少率(100% のとき)。 */
    public double maxFoodReduction() {
        return maxFoodReduction;
    }

    /** 経験値獲得量の減少率(100% のとき)。 */
    public double xpReduction() {
        return xpReduction;
    }

    /** 装備の胞子汚染速度の追加倍率(100% のとき)。5.0 = +500% = 6 倍。 */
    public double gearContaminationBonus() {
        return gearContaminationBonus;
    }

    /**
     * 食べ物で回復する量の減少率(100% のとき)。最大値の減り方と同じにしておくと、
     * <b>ゲージを満たすのに要る食料の数が変わらない</b>。
     */
    public double foodRestoreReduction() {
        return foodRestoreReduction;
    }

    /**
     * 自然回復が始まる食料ゲージの割合(上限に対する比)。0.9 = バニラの 18/20 と同じ比。
     * 上限が減っても<b>その上限に対して</b>判定するので、減った上限を満たせば回復する。
     */
    public double regenFoodFraction() {
        return regenFoodFraction;
    }

    // --- 持ち出しの副作用 ---

    /** この % まで下がると、持ち出しの副作用が消える。 */
    public double carrierThresholdPercent() {
        return carrierThresholdPercent;
    }

    /** 副作用が効いている間、過繁茂バイオームでの周囲の湧きがこの倍率になる。 */
    public double carrierOvergrownSpawnMultiplier() {
        return carrierOvergrownSpawnMultiplier;
    }

    /** 副作用が効いている間、過繁茂でも極相でもない場所で居るチャンクの胞子濃度に足す値(%)。 */
    public double carrierSporeConcentrationBonus() {
        return carrierSporeConcentrationBonus;
    }

    // --- 見た目 ---

    /** スキンにかける色(0xRRGGBB)。既定 {@code #1D2714}。 */
    public int tintColor() {
        return tintColor;
    }

    /** 浸食度 100% のときの色かぶりの濃さ(0〜1)。 */
    public double tintMaxAlpha() {
        return tintMaxAlpha;
    }

    /** 苔のオーバーレイの段階数。 */
    public int overlayStages() {
        return overlayStages;
    }

    /** オーバーレイを描くときの拡大率(z ファイティング除け)。 */
    public double overlayScale() {
        return overlayScale;
    }

    static ClimaxTable fromJson(Json j) {
        return new ClimaxTable(j);
    }

    public static final ClimaxTable DEFAULT = new ClimaxTable(null);
}
