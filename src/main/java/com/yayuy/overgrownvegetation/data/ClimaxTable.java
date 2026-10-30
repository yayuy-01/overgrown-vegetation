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
        public CrystalJson crystal;
        public OresJson ores;
    }

    /** {@code crystal} 節の生の形(結晶地帯、順 1、2026-10-03)。 */
    public static final class CrystalJson {
        public Double regionChance;
        public Integer spireSpacing;
        public Double spireChance;
        public Integer spireMinHeight;
        public Integer spireMaxHeight;
        public Double spireMaxRadius;
        public Double spireMaxTiltDegrees;
    }

    /**
     * 結晶地帯の設計値。{@code corruption/OvergrowthRegionMap#isCrystalRegion} と
     * {@code corruption/EnergyCrystalPlacer} が読む。
     *
     * @param regionChance        密林の地域のうち結晶地帯になる割合(0〜1)。地域のくじ(密林・湿地・菌湿地)とは
     *                            別の乱数で引くので、この値を変えても既存の地域の環境は入れ替わらない
     * @param spireSpacing        地脈に沿った結晶の候補点の間隔(ブロック)。この大きさの格子に 1 点ずつ候補を置く
     * @param spireChance         候補点が地脈の上にあったとき、そこに結晶を立てる確率
     * @param spireMinHeight      結晶の高さの下限(ブロック)
     * @param spireMaxHeight      結晶の高さの上限(ブロック)。地脈の中心に近いほど高くなる
     * @param spireMaxRadius      結晶の根元の半径の上限(ブロック)
     * @param spireMaxTiltDegrees 結晶の傾きの上限(度)
     */
    public record Crystal(double regionChance, int spireSpacing, double spireChance, int spireMinHeight,
                          int spireMaxHeight, double spireMaxRadius, double spireMaxTiltDegrees) {
    }

    /** {@code ores} 節の生の形(極相の地下の鉱石、順 1、2026-10-03)。 */
    public static final class OresJson {
        public OreJson phloemite;
        public OreJson thylakite;
        public Integer phloemiteChunkRadius;
    }

    /** 鉱石 1 種の生の形。 */
    public static final class OreJson {
        public Integer veinsPerChunk;
        public Integer veinSize;
        public Integer minY;
        public Integer maxY;
        public Double peakY;
    }

    /**
     * 極相の地下の鉱石 1 種の設計値。{@code corruption/ClimaxOrePlacer} が読む。
     *
     * @param veinsPerChunk 1 チャンクに置く鉱脈の数
     * @param veinSize      鉱脈 1 つの大きさ(置こうとするマス数)
     * @param minY          高さの下限
     * @param maxY          高さの上限
     * @param peakY         いちばん多く出る高さ(三角分布の頂点)
     */
    public record Ore(int veinsPerChunk, int veinSize, int minY, int maxY, double peakY) {
    }

    public static final class Biome {
        public Integer neighborRadiusChunks;
        public Double minAgeDays;
        /** 極相化が起き始めるフェーズ(2026-09-26 ユーザー指定 5)。齢からは F1 開始からこのフェーズまでの日数を差し引く。 */
        public Integer minPhase;
        public Integer checkIntervalTicks;
        public Double daylightBrightness;
        public String plantTint;
        public Boolean naturalSpawns;
        /** 縁からの深さ d で湧きの重みを変えるときの下限(順 1 の着手順 10。{@code phase/OvergrowthDepth})。 */
        public Double depthSpawnFloor;
    }

    /** {@code deepConversion} 節の生の形。 */
    public static final class DeepConversion {
        public Boolean enabled;
        public Integer blocksPerTick;
        public Boolean plants;
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
    private final int minPhase;
    private final int checkIntervalTicks;
    private final boolean naturalSpawns;
    private final double depthSpawnFloor;
    private final float daylightBrightness;
    private final LeyVein leyVein;
    private final double overgrownLayerChance;
    private final boolean encase;
    private final boolean deepConversion;
    private final int deepConversionBlocksPerTick;
    private final boolean deepConversionPlants;
    private final int plantTint;
    private final Crystal crystal;
    private final Ore phloemiteOre;
    private final Ore thylakiteOre;
    private final int phloemiteChunkRadius;

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

        // 12(順 1 の着手順 10 で 8 → 12、正方形 → 円。ユーザー指定 2026-10-03)。縁からの深さ d の上限も同じ数
        this.neighborRadiusChunks = biome.neighborRadiusChunks != null
                ? Math.max(0, Math.min(32, biome.neighborRadiusChunks)) : 12;
        double days = biome.minAgeDays != null ? Math.max(0.0D, biome.minAgeDays) : 14.0D;
        this.minAgeTicks = (long) (days * 24000.0D);
        this.minPhase = biome.minPhase != null ? Math.max(0, Math.min(PhaseTable.MAX_PHASE, biome.minPhase)) : 5;
        this.checkIntervalTicks = biome.checkIntervalTicks != null
                ? Math.max(1, biome.checkIntervalTicks) : 200;
        this.naturalSpawns = biome.naturalSpawns != null && biome.naturalSpawns;
        this.depthSpawnFloor = clamp01(biome.depthSpawnFloor, 0.15D);
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
        this.deepConversionPlants = deepConv.plants == null || deepConv.plants;

        // 結晶地帯と極相の地下の鉱石(順 1、2026-10-03)。既定値は design/climax.json と揃えること
        // (run/config の写しには古い climax.json が残るので、そこに無い節はここの既定値がそのまま効く)。
        CrystalJson cry = src.crystal != null ? src.crystal : new CrystalJson();
        int minH = cry.spireMinHeight != null ? Math.max(2, Math.min(48, cry.spireMinHeight)) : 6;
        int maxH = cry.spireMaxHeight != null ? Math.max(minH, Math.min(48, cry.spireMaxHeight)) : Math.max(minH, 20);
        this.crystal = new Crystal(
                clamp01(cry.regionChance, 0.25D),
                cry.spireSpacing != null ? Math.max(3, Math.min(32, cry.spireSpacing)) : 7,
                clamp01(cry.spireChance, 0.55D),
                minH, maxH,
                cry.spireMaxRadius != null ? Math.max(0.6D, Math.min(6.0D, cry.spireMaxRadius)) : 2.5D,
                cry.spireMaxTiltDegrees != null ? Math.max(0.0D, Math.min(60.0D, cry.spireMaxTiltDegrees)) : 25.0D);
        OresJson ores = src.ores != null ? src.ores : new OresJson();
        this.phloemiteOre = ore(ores.phloemite);
        this.thylakiteOre = ore(ores.thylakite);
        this.phloemiteChunkRadius = ores.phloemiteChunkRadius != null
                ? Math.max(0, Math.min(4, ores.phloemiteChunkRadius)) : 1;

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

    /** 鉱石 1 種。既定は「鉄と同じくらい」(ユーザー指定)= 1 チャンク 10 脈 × 9 マス、y −30〜40、y 17.5 が最多。 */
    private static Ore ore(OreJson o) {
        OreJson src = o != null ? o : new OreJson();
        int minY = src.minY != null ? Math.max(-64, Math.min(319, src.minY)) : -30;
        int maxY = src.maxY != null ? Math.max(minY, Math.min(319, src.maxY)) : Math.max(minY, 40);
        double peak = src.peakY != null ? src.peakY : 17.5D;
        return new Ore(
                src.veinsPerChunk != null ? Math.max(0, Math.min(64, src.veinsPerChunk)) : 10,
                src.veinSize != null ? Math.max(1, Math.min(32, src.veinSize)) : 9,
                minY, maxY, Math.max(minY, Math.min(maxY, peak)));
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

    /**
     * 極相になるために「周囲がすべて過繁茂以上」である必要のある半径(チャンク)。既定 12。<b>円で測る</b>
     * ({@code dx² + dz² <= 半径²}。12 なら自分を含めて 441 チャンク)。縁からの深さ d の上限も同じ数。
     */
    public int neighborRadiusChunks() {
        return neighborRadiusChunks;
    }

    /**
     * 過繁茂になってから極相になるまでに必要な時間(tick)。既定 14 日 = 336000。
     * 実際の判定では、ここに「F1 開始から {@link #minPhase} に着くまでの日数」が足される
     * ({@code PhaseData#climaxAgeOffsetTicks}、2026-09-26)。
     */
    public long minAgeTicks() {
        return minAgeTicks;
    }

    /**
     * 極相化が起き始めるフェーズ。既定 5(2026-09-26 ユーザー指定)。1 以下にすると差し引きも無くなり、以前と同じ
     * 「過繁茂になってから {@link #minAgeTicks}」になる。
     */
    public int minPhase() {
        return minPhase;
    }

    /** 極相化の判定を回す間隔(tick)。 */
    public int checkIntervalTicks() {
        return checkIntervalTicks;
    }

    /** 極相に通常の Mob を湧かせるか。既定 false(極相に湧くのは G-EX だけ)。 */
    public boolean naturalSpawns() {
        return naturalSpawns;
    }

    /**
     * 縁からの深さ d による湧きの重みの下限(0〜1、既定 0.15。ユーザー承認 2026-10-03)。
     * 0 にすると縁が「侵略者だけの土地」、奥が「環境生物だけの土地」になる。{@code phase/OvergrowthDepth}。
     */
    public double depthSpawnFloor() {
        return depthSpawnFloor;
    }

    /** 地脈の場の設計値。 */
    public LeyVein leyVein() {
        return leyVein;
    }

    /** 結晶地帯の設計値(順 1、2026-10-03)。 */
    public Crystal crystal() {
        return crystal;
    }

    /** フロエマイト(地脈の鉱石)の設計値。 */
    public Ore phloemiteOre() {
        return phloemiteOre;
    }

    /** チラコイト(結晶地帯の鉱石)の設計値。 */
    public Ore thylakiteOre() {
        return thylakiteOre;
    }

    /**
     * フロエマイトを置く範囲: 地脈のあるチャンクから何チャンク以内か(既定 1 = 地脈のチャンクを中心に 3×3、
     * ユーザー指定)。
     */
    public int phloemiteChunkRadius() {
        return phloemiteChunkRadius;
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
     * 全層置換で<b>植物も生やす</b>か(2026-09-28、要望)。置き換えたブロックのうち空気に接している面へ、
     * 通常の汚染とまったく同じ経路({@code CorruptionSpreader#growSurfacePlants})で下草・蔦・苔、
     * 洞窟なら洞窟植物と構造物を生やす。
     *
     * <p>これが無いと、地下の洞窟が<b>のっぺりした過繫茂ブロックの空洞</b>になる ——
     * 地表の汚染や深部浸食で汚染された洞窟には植物が生えるので、同じ極相の地下で見た目が食い違っていた。
     */
    public boolean deepConversionPlants() {
        return deepConversionPlants;
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

    public static ClimaxTable fromJson(Json j) {
        return new ClimaxTable(j);
    }

    public static final ClimaxTable DEFAULT = new ClimaxTable(null);
}
