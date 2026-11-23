package com.yayuy.overgrownvegetation.client.sky;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.atmosphere.SunLight;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SkyTable;
import com.yayuy.overgrownvegetation.sky.CloudField;
import com.yayuy.overgrownvegetation.sky.CloudSky;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * ボクセル調の雲(2026-10-17 の土台を 2026-11-08 雲の段階 1 で parked から戻した。元は parked の {@code client/VoxelClouds}、1851 行)。
 * バニラの雲の代わりに、{@link CloudField} の下の層(y 300〜350。ユーザー指定)を 8 × 4 × 8 ブロックの箱の柱で描く。
 * 雲の形と量は {@link CloudField} だけが決める(ここは描き方だけ。{@link CloudRenderer} を差し替えれば、将来のリアルな雲に替えられる)。
 *
 * <ul>
 *   <li>カメラのまわり(高 80・中 64・低 48 升 = 640・512・384 ブロック)の柱を別スレッド({@link CloudThread})で場から引き、外から見える面だけを
 *       形にする(STATIC の VBO)。形は場の座標で持ち、風で流れた分とカメラの位置は描くときの平行移動(uniform)で足す。作り直すのはカメラ
 *       (場の上での位置)が {@link #REBUILD_CELLS} 升動いたときと、量が変わったときだけ。</li>
 *   <li>頂点には太陽と関係ない値だけを焼く(色の r = 面の向き、g = 層の中の高さ、b = 薄さ)。光はシェーダー({@code voxel_clouds})が決める。</li>
 *   <li>バニラと同じ 2 回描き(奥行きだけ → 色)で、一番手前の面だけが空と混ざる(並べ替えが要らない)。</li>
 *   <li>雲の上(y {@link #FAR_ON_Y} より上)では、近くの窓の外に 7.7 km まで(y 950 より上は 18 km まで)雲の海を粗い柱で描く(遠景)。
 *       遠景は空と一緒に地形より先に、雲だけの奥行きの幅で描く({@link #onRenderLevel})。</li>
 * </ul>
 *
 * <p>2026-11-09 雲の段階 2a で戻したもの: 積乱雲・入道雲・乳房雲・雲の橋の形(塊を箱に詰める {@link #stormTiles}・遠景 {@link #farStormTiles})、
 * 雲が雲に落とす影({@link CloudSelfShadow})、巻雲({@link Cirrus})。まだ元の版に残っているもの: 雷の光(シェーダーの Flash0〜2 は口だけ・いつも 0)・
 * 雲の穴・雲を引く・吐いた雲・尾流雲・雲の切れ目の下の霞んだ地面(FarGround。代わりに {@link HazeFloor})・森の中で遠くの雲を地形の後に描く近道(cloud_sky_mask)。
 *
 * <p>シェーダーパック使用中は描かない(パックの雲に任せる。10-23 決定)。Distant Horizons があるときはバニラの雲の設定を見ない
 * (DH が自分のために切るので)。数値はユーザー指定の高さ以外すべて仮決め。
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = net.neoforged.fml.common.EventBusSubscriber.Bus.GAME,
        value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class VoxelClouds implements CloudRenderer {

    public static final VoxelClouds INSTANCE = new VoxelClouds();

    /** 重さの段ごとのカメラのまわりの柱の数(片側、升。高・中・低)。 */
    static final int[] RADIUS_BY_TIER = {80, 64, 48};
    /** カメラ(場の上)がこれだけ升を動いたら作り直す。 */
    static final int REBUILD_CELLS = 6;
    /** 量がこれだけ変わったら作り直す。 */
    /** 11-09 段階 2a-2: 0.01 → 0.02(雨の降り始め・止み際に、1 回数百 ms の作り直しが続いた)。 */
    public static final float REBUILD_COVERAGE = 0.02F;

    private static final class Window {
        int originX;
        int originZ;
        int centerX;
        int centerZ;
        int radius;
        float coverage;
        int style;
        int quads;
        long seed = CloudField.seed();
        com.yayuy.overgrownvegetation.sky.CloudShape shape = com.yayuy.overgrownvegetation.sky.CloudShape.current();
        ByteBufferBuilder bytes;
        MeshData mesh;
        /** 遠景の窓: 番号と穴の真ん中(場の座標、ブロック)。近くの窓: 作ったときの遠景の番号(0 = 遠景なし)。 */
        long id;
        int holeX;
        int holeZ;
        long farId;
        boolean outer;
        boolean coarse;
        /** 作ったときの積乱雲の形の値({@link #stormKey}・{@link #farStormKey})。 */
        int stormKey;
        int storms;
    }

    // ---------------------------------------------------------------- 積乱雲(11-09 段階 2a。parked の版の値。数値は仮決め)

    /** カメラのまわりの積乱雲を探す範囲(場の座標、ブロック)。巨大な雲はここまで、ふつうの雲は {@link #STORM_NEAR_BY_TIER} まで形にする。 */
    static final double STORM_RANGE = 2600.0;
    static final double[] STORM_NEAR_BY_TIER = {1800.0, 1500.0, 1200.0};
    /** 積乱雲の消え始めと消え終わり(水平距離。雲の海と同じ)。 */
    static final float STORM_FADE_FROM = 1450.0F;
    static final float STORM_FADE_TO = 1780.0F;
    /** 積乱雲の箱の大きさ(ブロック。重さの段ごと・巨大な雲を含むタイル)。TILE を割り切る値。 */
    static final int[] STORM_VOXEL_BY_TIER = {16, 24, 32};
    static final int GIANT_VOXEL = 48;
    /** 頂点の色の a: 巨大な積乱雲・積乱雲・遠景の積乱雲(シェーダーが消え方と陰の付け方を分ける)。 */
    private static final float GIANT_TAG = 0.8F;
    private static final float STORM_TAG = 1.0F;
    private static final float FAR_STORM_TAG = 0.15F;
    /** 嵐の巣の雲(11-18): 積乱雲と同じ扱いで、シェーダーが暗い嵐の雲の色にする(近く 0.95・遠景 0.19)。 */
    private static final float NEST_TAG = 0.95F;
    /**
     * 近くの窓の床の柱のうち積乱雲に食い込んだもの(11-23。{@link #stormColumnTags}): シェーダーは積乱雲・嵐の巣・巨大な雲として塗るが、
     * 雲の海の床と同じく遠景の穴の外では捨てる(voxel_clouds.fsh の FarHole)。8 ビットで 232・249・217 になり、ほかの印とぶつからない。
     */
    private static final float FLOOR_STORM_TAG = 0.91F;
    private static final float FLOOR_NEST_TAG = 0.976F;
    private static final float FLOOR_GIANT_TAG = 0.851F;
    /** 遠景の窓の同じ柱(積乱雲 0.165 = 42・嵐の巣 0.2 = 51)。どの床の印も奥行きは寄せない(voxel_clouds.vsh。寄せると積乱雲の面と同じ所で再び取り合う)。 */
    private static final float FAR_FLOOR_STORM_TAG = 0.165F;
    private static final float FAR_FLOOR_NEST_TAG = 0.2F;
    private static final float FAR_NEST_TAG = 0.19F;
    /** 積乱雲を形にするタイルの大きさ(ブロック)。 */
    static final int TILE = 768;
    /** 外の輪で形にする積乱雲の天辺の下限(巨大な雲は全部)。 */
    static final int FAR3_STORM_TOP = 1100;
    /** 積乱雲が育った(形の値が変わった)ときに作り直す間隔の下限(tick。近く・遠景)。 */
    public static final int STORM_REBUILD_TICKS = 200;
    static final int FAR_REBUILD_TICKS = 1200;
    private static long lastBuildTick;
    private static long lastFarBuildTick;
    private static long lastFarCheck;
    private static int farStormKeyNow;

    private static ClientLevel lastLevel;
    private static Window shown;
    private static CompletableFuture<Window> pending;
    private static VertexBuffer vbo;

    /*
     * 形の差し替えを溶け合わせる(2026-11-12、ユーザー報告「雲と雲の影が数秒ごとに瞬間移動しているみたい」)。
     * 雲の形は積乱雲の育ち(約 10 秒ごと)・量の変化(天気の変わり目は 1〜2 秒ごと)で作り直し、前は 1 フレームで丸ごと差し替えていた。
     * 今は前の形(prev)を残し、SWAP_TICKS の間だけ新旧の両方を描いて、シェーダーの Dissolve で場の座標の雑音に沿って入れ替える。
     * 入れ替えの間は次の作り直しを始めない(作り直しが続いても、1 回ずつ溶け合わせる)。VBO は 2 つを使い回す(spare)。数値は仮決め。
     */
    static final double SWAP_TICKS = 40.0;
    /** 積乱雲の一番下の段の底を上げる高さ(ブロック。雲の層の底からの高さ。2.5 未満なら雨の前触れで一緒に下がる)。 */
    static final float STORM_BASE_LIFT = 2.0F;
    private static Window prevShown;
    private static VertexBuffer prevVbo;
    private static VertexBuffer spareVbo;
    private static double swapStart;
    private static Window prevFarShown;
    private static VertexBuffer prevFarVbo;
    private static VertexBuffer spareFarVbo;
    private static double farSwapStart;
    /** 今のフレームの時刻(gameTime + partialTick)。差し替えの始まりに使う。 */
    private static double nowTicks;

    /*
     * 遠景(2026-10-18、ユーザー「高度 400 を超えて雲より上に入ったとき、読み込み範囲外の雲も見えるように」):
     * 雲の上(高さ FAR_ON_Y より上)で、近くの窓の外に 7680 ブロックまでの雲の海を、遠いほど大きな柱で描く(別の VBO・同じシェーダー)。
     * 遠景には真ん中に四角い穴(FAR_HOLE)があり、その中は近くの窓が受け持つ(近くの雲の床は穴の外をシェーダーで捨てる)。
     * 持ち分が食い違わないよう、遠景を作り直すときは近くの窓も同じ遠景で一緒に作り、2 つ同時に差し替える。数値は仮決め。
     */
    static final double FAR_ON_Y = 410.0;
    static final double FAR_OFF_Y = 395.0;
    static final int FAR_HOLE = 1344;
    static final int FAR_MID = 3840;
    static final int FAR_RANGE = 7680;
    static final int FAR_SNAP = 192;
    static final int FAR_CELL_1 = 48;
    static final int FAR_CELL_2 = 96;
    /** 外の輪(y 950 より上、2026-10-18 夜): FAR3_RANGE まで FAR_CELL_3 の柱の雲の海(雲の海の地平線が FAR_RANGE より遠くなるので)。低の段では作らない。 */
    static final int FAR3_RANGE = 18432;
    static final int FAR_CELL_3 = 192;
    static final double FAR3_ON_Y = 950.0;
    static final double FAR3_OFF_Y = 850.0;
    static final double FAR_MOVE = 240.0;
    static final float FAR_FADE_FROM = 6400.0F;
    static final float FAR_FADE_TO = 7600.0F;
    /** 遠景の切れ目と谷を閉じ始める・閉じ終わる距離(穴の真ん中から。地面は描画距離の外には無いので、遠くの切れ目の下は空の色になる)。 */
    static final double FAR_CLOSE_FROM = 3000.0;
    static final double FAR_CLOSE_TO = 6000.0;
    private static final float FAR_SEA_TAG = 0.3F;
    /** 丸み: この距離より遠い雲を (d − 始まり)² / (2 R) 下げる。 */
    static final float CURVE_FROM = 1200.0F;
    static final float CURVE_RADIUS = 40000.0F;
    /** 頂点の色の a: 下の層(シェーダーが消え方と陰の付け方を分ける)。 */
    private static final float LOW_TAG = 0.5F;

    private static Window farShown;
    private static VertexBuffer farVbo;
    private static CompletableFuture<Window[]> pendingPair;
    private static boolean farWanted;
    private static float farAlpha;
    private static boolean outerWanted;
    private static float farReach = FAR_FADE_TO;
    private static double farAlphaTime;
    private static long farIdSeq;
    private static boolean broken;
    private static long lastLog;

    /** 遠くの雲を描くときの雲だけの奥行きの幅(ブロック)。 */
    static final float FAR_NEAR = 64.0F;
    static final float FAR_FAR = 28000.0F;

    /** 風の筋の模様をシェーダーへ渡すときの場の座標の折り返し(float の細かさを保つ)。 */
    static final double FIELD_PERIOD = 32768.0;

    /** 開発用: 形づくりの CPU 時間(作業スレッドは優先度が低く、ほかの処理で待たされると壁時計の時間が延びるので、分けて記録する)。 */
    private static final java.lang.management.ThreadMXBean CPU = java.lang.management.ManagementFactory.getThreadMXBean();

    private static final boolean DH_LOADED = ModList.get().isLoaded("distanthorizons");
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    /** 夜の雲の色の下限(満月のとき)・新月の割合・青い時間の雲の色と寄せる割合(元は parked の {@code SkyLight}。10-18 ユーザー選択)。 */
    static final float[] NIGHT_CLOUD = {0.17F, 0.21F, 0.36F};
    static final float NEW_MOON = 0.55F;
    static final float[] BLUE_CLOUD = {0.36F, 0.44F, 0.74F};
    static final float BLUE_CLOUD_MIX = 0.6F;

    private VoxelClouds() {
    }

    // ---------------------------------------------------------------- 公開の口

    @Override
    public boolean active() {
        return isActive();
    }

    /**
     * ボクセルの雲を描いているか(設定・シェーダー・バニラの雲の設定)。雲の影・薄明光線もこれに従う(見えている雲と影が食い違わないように)。
     * 11-08 改善 ⑲: 空の色・雨の強さの Mixin から 1 フレームに何百回も呼ばれ、Iris があると毎回 Iris へ名前で問い合わせるので、
     * フレームの初めに 1 回だけ求めて覚える({@link #onRenderFrame})。
     */
    public static boolean isActive() {
        return activeCache;
    }

    private static volatile boolean activeCache;

    @SubscribeEvent
    public static void onRenderFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        activeCache = computeActive();
        HighSky.refresh();
    }

    private static boolean computeActive() {
        return !broken && VisualQuality.clouds() && SkyShaders.voxelClouds() != null && !ShaderPacks.active()
                && (DH_LOADED || Minecraft.getInstance().options.getCloudsType() != net.minecraft.client.CloudStatus.OFF);
    }

    /** 今の雲の量({@link CloudSky})。 */
    public static float coverage(ClientLevel level, float partialTick) {
        return CloudSky.coverage(level, partialTick);
    }

    /** 尾根の途中の峰をどれだけ描くか(11-09 段階 2a-4 ユーザー決定「中・低の段だけ峰を減らす」: 高 = 全部・中 = 半分・低 = なし。{@link CloudField#puffs(CloudField.Storm, int)})。 */
    public static int peakLevel() {
        return tier();
    }

    /** 雨の前触れで雲の底を下げる高さ(ブロック。11-09 段階 2a-6。仮決め)。見た目だけ(サーバーの答え・雲の形は変えない)。 */
    public static final float PRE_RAIN_DROP = 20.0F;

    /** 今の雲の底の下がり(ブロック)。雲の中の霧も同じだけ下げる({@link CloudInterior})。 */
    public static float baseDrop(ClientLevel level, float partialTick) {
        return com.yayuy.overgrownvegetation.sky.CloudWeather.preRain(level, partialTick) * PRE_RAIN_DROP;
    }

    /** 重さの段(0 = 高、1 = 中、2 = 低)。 */
    static int tier() {
        return Mth.clamp(VisualQuality.atmosphereTier(), 0, 2);
    }

    /** 荒天の度合い(0〜1。本当の雨は 6 割・雷雨で 1 × 設計値 roughWeather)。雲の上では雨を見せないが、雲の色は本当の天気で決める。 */
    static float roughness(ClientLevel level, float partialTick) {
        float rain = CloudSky.rawRain(level, partialTick);
        float thunder = CloudSky.rawThunder(level, partialTick);
        // 雨の前にだんだん荒天の色へ(11-09 段階 2a-5。予定の雨の前触れで半分まで)。
        float pre = com.yayuy.overgrownvegetation.sky.CloudWeather.preRain(level, partialTick) * 0.5F;
        // 11-12 雨の種類: 小雨は明るい雨空(0.35)、普通は今まで(0.6)、大雨は雷雨に近い暗さ(0.9)。
        float rainDark = Mth.lerp(com.yayuy.overgrownvegetation.client.atmosphere.RainFeel.intensity(), 0.2F, 0.95F);
        return Math.max(Math.max(rain * rainDark, thunder), pre) * DesignData.sky().roughWeather;
    }

    /** 天気を入れない時刻だけの雲の色(バニラの {@code ClientLevel#getCloudColor} から雨・雷を除いた式)。 */
    static float[] clearCloudColor(ClientLevel level, float partialTick) {
        float f = Mth.clamp(Mth.cos(level.getTimeOfDay(partialTick) * Mth.TWO_PI) * 2.0F + 0.5F, 0.0F, 1.0F);
        return new float[]{f * 0.9F + 0.1F, f * 0.9F + 0.1F, f * 0.85F + 0.15F};
    }

    /** 青い時間の度合い(0〜1)。太陽の高さ(向きの y)から。 */
    static float blueHour(ClientLevel level, float partialTick) {
        float e = (float) SunLight.sunDirection(level, partialTick).y;
        float in = SunLight.smooth(Mth.clamp((e + 0.24F) / 0.18F, 0.0F, 1.0F));
        float out = 1.0F - SunLight.smooth(Mth.clamp(e / 0.12F, 0.0F, 1.0F));
        return in * out;
    }

    /** 雲の色(バニラの {@code getCloudColor})に、月明かりの夜の下限と青い時間を入れる(10-18 ユーザー選択)。 */
    static Vec3 cloudColor(ClientLevel level, float partialTick, Vec3 cloud) {
        float night = DesignData.sky().nightClouds;
        if (night <= 0.0F) {
            return cloud;
        }
        float rain = CloudSky.rawRain(level, partialTick);
        float moon = NEW_MOON + (1.0F - NEW_MOON) * level.getMoonBrightness();
        float k = night * moon * (1.0F - 0.5F * rain);
        double r = Math.max(cloud.x, NIGHT_CLOUD[0] * k);
        double g = Math.max(cloud.y, NIGHT_CLOUD[1] * k);
        double b = Math.max(cloud.z, NIGHT_CLOUD[2] * k);
        float blue = blueHour(level, partialTick) * BLUE_CLOUD_MIX * night * (1.0F - 0.6F * rain);
        if (blue > 0.0F) {
            r = Mth.lerp(blue, r, BLUE_CLOUD[0]);
            g = Mth.lerp(blue, g, BLUE_CLOUD[1]);
            b = Mth.lerp(blue, b, BLUE_CLOUD[2]);
        }
        return new Vec3(r, g, b);
    }

    /**
     * 下から見た雲の底の色(2026-11-12、水面の映り込み {@code client.atmosphere.WaterGlint} が読む)。雲の色にシェーダーの雲の床の底の明るさ
     * (0.62 + 空の明るさの分)を掛け、雨の間は暗い灰へ寄せる。仮決め。
     */
    public static Vec3 undersideColor(ClientLevel level, float partialTick) {
        // 11-12: 0.74 では水に映ると灰茶色の汚れに見えたので、明るい底の色に(映る雲は水の色と混ざって暗くなる)。
        Vec3 c = cloudColor(level, partialTick, level.getCloudColor(partialTick)).scale(0.95);
        float rough = roughness(level, partialTick);
        return c.scale(1.0 - 0.3 * rough);
    }

    /** 雲を描く({@link OverworldCloudEffects#renderClouds} から。近くの雲)。false ならバニラの雲を描かせる。 */
    @Override
    public boolean render(ClientLevel level, float partialTick, Matrix4f modelView, Matrix4f projection, double camX, double camY, double camZ) {
        if (!isActive()) {
            return false;
        }
        try {
            draw(level, partialTick, modelView, projection, camX, camY, camZ, false);
            return true;
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("ボクセルの雲で例外が出たので、このセッションではバニラの雲に戻す", e);
            return false;
        }
    }

    /**
     * 遠くの雲(奥の面の 0.9 倍より遠い所)は空と一緒に、地形より先に描く(雲だけの奥行きの幅 {@link #FAR_NEAR}〜{@link #FAR_FAR} で 2 回描き、
     * 描いたら奥行きを消す)。近くの雲は {@link #render} でいつもの奥行きのまま地形の後に描く。
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || CloudRenderers.current() != INSTANCE || !isActive()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimensionType().hasSkyLight()) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        try {
            // 雲の海の下の霞の床(改善 ①、{@link HazeFloor})。遠くの雲より先に、背景として。
            HazeFloor.draw(level, partialTick, event.getModelViewMatrix(), event.getProjectionMatrix(), cam.x, cam.y, cam.z);
            draw(level, partialTick, event.getModelViewMatrix(), event.getProjectionMatrix(), cam.x, cam.y, cam.z, true);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("ボクセルの雲(遠く)で例外が出たので、このセッションではバニラの雲に戻す", e);
        }
    }

    // ---------------------------------------------------------------- 積乱雲を引く

    /** 場の座標の範囲にかかる積乱雲({@link CloudSky#storms})。同じフレームに同じ範囲で何度も引かれる(雲の影・2 回の描き分け)ので、その間だけ使い回す。 */
    public static List<CloudField.Storm> storms(ClientLevel level, float partialTick, float coverage, double minX, double minZ, double maxX, double maxZ) {
        if (!RenderSystem.isOnRenderThread()) {
            return CloudSky.storms(level, coverage, minX, minZ, maxX, maxZ);
        }
        StormsKey key = new StormsKey(level, level.getGameTime(), level.getDayTime(), partialTick, coverage, minX, minZ, maxX, maxZ,
                com.yayuy.overgrownvegetation.sky.CloudShape.current(), CloudField.seed());
        if (!STORMS_MEMO.isEmpty() && !STORMS_MEMO.keySet().iterator().next().sameFrame(key)) {
            STORMS_MEMO.clear();
        }
        List<CloudField.Storm> memo = STORMS_MEMO.get(key);
        if (memo == null) {
            memo = sharedStorms ? shared(level, coverage, minX, minZ, maxX, maxZ) : CloudSky.storms(level, coverage, minX, minZ, maxX, maxZ);
            if (STORMS_MEMO.size() < 8) {
                STORMS_MEMO.put(key, memo);
            }
        }
        return memo;
    }

    /**
     * 11-18 ユーザー決定「新しい雲はまあ大丈夫そう」で既定は入(切るのは wx shared off か -Dov.cloudsShared=false): 積乱雲の一覧を、範囲を {@link #SHARED_SNAP} の倍数へ広げて引き、同じ時刻の間は使い回して、
     * 各所はそこから絞る。カメラが動いても広げた範囲はほとんど変わらないので、引き直しは 1 tick に 1 回ほどで済む。じかに引くのとは、まれに尾根 1 本が違いうる。
     */
    public static volatile boolean sharedStorms = !"false".equals(System.getProperty("ov.cloudsShared"));
    static final double SHARED_SNAP = 1024.0;

    private record SharedKey(ClientLevel level, long dayTime, float coverage, double minX, double minZ, double maxX, double maxZ,
                             com.yayuy.overgrownvegetation.sky.CloudShape shape, long seed, boolean real, int dev) {
    }

    private static final java.util.Map<SharedKey, List<CloudField.Storm>> SHARED = new java.util.LinkedHashMap<>();

    private static List<CloudField.Storm> shared(ClientLevel level, float coverage, double minX, double minZ, double maxX, double maxZ) {
        SharedKey key = new SharedKey(level, level.getDayTime(), CloudSky.stormCoverage(coverage), Math.floor(minX / SHARED_SNAP) * SHARED_SNAP,
                Math.floor(minZ / SHARED_SNAP) * SHARED_SNAP, Math.ceil(maxX / SHARED_SNAP) * SHARED_SNAP, Math.ceil(maxZ / SHARED_SNAP) * SHARED_SNAP,
                com.yayuy.overgrownvegetation.sky.CloudShape.current(), CloudField.seed(), CloudField.realSky(), CloudField.devKey()); // real: 確かめ用の切り替えで覚えが古くならないよう
        if (!SHARED.isEmpty()) {
            SharedKey first = SHARED.keySet().iterator().next();
            if (first.level != level || first.dayTime != key.dayTime || SHARED.size() > 8) {
                SHARED.clear();
            }
        }
        List<CloudField.Storm> wide = SHARED.get(key);
        if (wide == null) {
            wide = CloudSky.storms(level, coverage, key.minX, key.minZ, key.maxX, key.maxZ);
            SHARED.put(key, wide);
        }
        return CloudField.within(wide, minX, minZ, maxX, maxZ);
    }

    private record StormsKey(ClientLevel level, long gameTime, long dayTime, float partialTick, float coverage, double minX, double minZ,
                             double maxX, double maxZ, com.yayuy.overgrownvegetation.sky.CloudShape shape, long seed) {
        boolean sameFrame(StormsKey o) {
            return level == o.level && gameTime == o.gameTime && dayTime == o.dayTime && partialTick == o.partialTick;
        }
    }

    private static final java.util.Map<StormsKey, List<CloudField.Storm>> STORMS_MEMO = new java.util.LinkedHashMap<>();

    /** 積乱雲の形が変わったかを見る値(育ちを 32 段・カメラからの距離を 300 ブロックで)。 */
    public static int stormKey(List<CloudField.Storm> storms, double fx, double fz) {
        int key = storms.size();
        for (CloudField.Storm s : storms) {
            key = key * 31 + Math.round(s.maturity() * 32.0F);
            key = key * 31 + (int) (Math.hypot(s.x() - fx, s.z() - fz) / 300.0);
            key = key * 31 + (int) Math.floor(s.x() / 32.0) * 7 + (int) Math.floor(s.z() / 32.0) * 13; // 嵐の巣が回ったら作り直す
            key = key * 31 + (int) (s.id() ^ (s.id() >>> 32));
        }
        return key;
    }

    private static List<CloudField.Storm> inHoleAt(List<CloudField.Storm> storms, int holeX, int holeZ) {
        List<CloudField.Storm> list = new java.util.ArrayList<>();
        for (CloudField.Storm s : storms) {
            if (holeOwns(holeX, holeZ, s)) {
                list.add(s);
            }
        }
        return list;
    }

    /** 真ん中が遠景の穴の中の積乱雲(近くの窓の持ち分)。 */
    private static List<CloudField.Storm> inHole(List<CloudField.Storm> storms, Window far) {
        List<CloudField.Storm> list = new java.util.ArrayList<>();
        for (CloudField.Storm s : storms) {
            if (holeOwns(far.holeX, far.holeZ, s)) {
                list.add(s);
            }
        }
        return list;
    }

    private static boolean holeOwns(int holeX, int holeZ, CloudField.Storm s) {
        return Math.abs(s.x() - holeX) < FAR_HOLE && Math.abs(s.z() - holeZ) < FAR_HOLE;
    }

    /** 遠景が形にする積乱雲か(穴の外・届く範囲。外の輪では巨大な雲と天辺 {@link #FAR3_STORM_TOP} 以上の塔だけ、入道雲は無し)。 */
    private static boolean farMeshed(int holeX, int holeZ, CloudField.Storm s, boolean outer) {
        if (holeOwns(holeX, holeZ, s)) {
            return false;
        }
        double d = Math.max(Math.abs(s.x() - holeX), Math.abs(s.z() - holeZ));
        // 浮いた雲は 3500 ブロックまで(11-09 段階 2a-2。遠くでは 64 ブロック以上の箱 1〜2 つになり、数だけ多い)。
        if (s.kind() == CloudField.KIND_FLOAT && d > 3500.0) {
            return false;
        }
        if (d < FAR_RANGE + s.reach()) {
            return true;
        }
        return outer && d < FAR3_RANGE + s.reach() && !s.congestus() && (s.giant() || s.top() >= FAR3_STORM_TOP);
    }

    /** 遠景の積乱雲の形が変わったかを見る値(穴の外の雲の育ちを 8 段で)。 */
    private static int farStormKey(List<CloudField.Storm> storms, int holeX, int holeZ, boolean outer) {
        int key = 17;
        for (CloudField.Storm s : storms) {
            if (!farMeshed(holeX, holeZ, s, outer)) {
                continue;
            }
            key = key * 31 + Math.round(s.maturity() * 8.0F);
            // 位置(11-18: 嵐の巣はゆっくり回る。ほかの雲は場の座標で動かないので、鍵は変わらない)。
            key = key * 31 + (int) Math.floor(s.x() / 32.0) * 7 + (int) Math.floor(s.z() / 32.0) * 13;
            key = key * 31 + (int) (s.id() ^ (s.id() >>> 32));
        }
        return key;
    }

    /** 開発用: アーチの真ん中の下(雲の床と稜線の間)が空いているか。 */
    private static boolean archOpen(List<CloudField.Storm> all, CloudField.Storm a) {
        double mx = (a.x() + a.x2()) * 0.5;
        double mz = (a.z() + a.z2()) * 0.5;
        double y = CloudField.LOW_BASE + (a.top() - CloudField.LOW_BASE) * 0.45;
        for (CloudField.Storm s : all) {
            double r = s.reach();
            if (Math.abs(mx - s.x()) > r + 400.0 || Math.abs(mz - s.z()) > r + 400.0) {
                continue;
            }
            if (CloudField.inside(CloudField.puffs(s), mx, y, mz)) {
                return false;
            }
        }
        return true;
    }

    /** 今描いている近くの窓の積乱雲の数(開発用)。 */
    private static int stormCount() {
        return shown == null ? -1 : shown.storms;
    }

    // ---------------------------------------------------------------- 描く

    private static void draw(ClientLevel level, float partialTick, Matrix4f modelView, Matrix4f projection, double camX, double camY, double camZ,
                             boolean far) {
        SkyDev.camY = camY;
        if (level != lastLevel) {
            lastLevel = level;
            shown = null;
            endSwap();
            endFarSwap();
            discardPending();
        }
        // 11-13(水まわり ⑤ ユーザー選択「水中から雲を見せない」): 潜っている間は雲・巻雲を描かない(雲だけ水の霧に沈まず、くっきり見えていた)。
        if (Minecraft.getInstance().gameRenderer.getMainCamera().getFluidInCamera() == net.minecraft.world.level.material.FogType.WATER) {
            return;
        }
        SkyTable sky = DesignData.sky();
        long gameTime = level.getGameTime();
        nowTicks = gameTime + (double) partialTick;
        if (prevVbo != null && swapProgress(swapStart) >= 1.0F) {
            endSwap();
        }
        if (prevFarVbo != null && swapProgress(farSwapStart) >= 1.0F) {
            endFarSwap();
        }
        float coverage = coverage(level, partialTick);
        SkyDev.coverage = coverage;
        int style = CloudSky.style();
        int radius = RADIUS_BY_TIER[tier()];
        double fx = CloudField.fieldX(camX, gameTime, partialTick);
        double fz = CloudField.fieldZ(camZ, gameTime, partialTick);
        int cx = Mth.floor(fx / CloudField.CELL_XZ);
        int cz = Mth.floor(fz / CloudField.CELL_XZ);

        if (pending != null && pending.isDone()) {
            Window w = pending.join();
            pending = null;
            upload(w);
        }
        if (pendingPair != null && pendingPair.isDone()) {
            Window[] pair = pendingPair.join();
            pendingPair = null;
            uploadFar(pair[0]);
            upload(pair[1]);
        }
        // 積乱雲(11-09 段階 2a): 巨大な雲は STORM_RANGE まで、ふつうの雲は段ごとの近さまで。
        List<CloudField.Storm> all = storms(level, partialTick, coverage, fx - STORM_RANGE, fz - STORM_RANGE, fx + STORM_RANGE, fz + STORM_RANGE);
        double stormNear = STORM_NEAR_BY_TIER[tier()];
        List<CloudField.Storm> stormsHere = new java.util.ArrayList<>();
        for (CloudField.Storm s : all) {
            if (s.giant() || Math.hypot(s.x() - fx, s.z() - fz) < stormNear + s.reach()) {
                stormsHere.add(s);
            }
        }
        if (far) {
            updateFar(gameTime + partialTick, camY);
        }
        Window farNow = farShown != null && (farWanted || farAlpha > 0.0F) ? farShown : null;
        long farId = farNow == null ? 0L : farNow.id;
        // 遠景が見えている間は、近くの窓の積乱雲は穴の中のものだけ、雲の床の裾野は近くの全部の積乱雲から。
        final List<CloudField.Storm> meshStorms = farNow == null ? stormsHere : inHole(all, farNow);
        final List<CloudField.Storm> floorStorms = farNow == null ? stormsHere : all;
        int key = stormKey(meshStorms, fx, fz) * 31 + floorStorms.size();
        boolean moved = shown == null || Math.abs(cx - shown.centerX) > REBUILD_CELLS || Math.abs(cz - shown.centerZ) > REBUILD_CELLS
                || Math.abs(coverage - shown.coverage) > REBUILD_COVERAGE || shown.farId != farId || shown.radius != radius || shown.style != style || shown.seed != CloudField.seed()
                || !shown.shape.equals(com.yayuy.overgrownvegetation.sky.CloudShape.current());
        boolean grown = shown != null && key != shown.stormKey && gameTime - lastBuildTick >= STORM_REBUILD_TICKS;
        boolean busy = pending != null || pendingPair != null || prevVbo != null || prevFarVbo != null;
        final int voxel = STORM_VOXEL_BY_TIER[tier()];
        if (!busy && far && farWanted && needFar(level, partialTick, gameTime, fx, fz, coverage, style)) {
            final float c = coverage;
            final int hx = Math.round((float) (fx / FAR_SNAP)) * FAR_SNAP;
            final int hz = Math.round((float) (fz / FAR_SNAP)) * FAR_SNAP;
            final boolean outer = outerWanted;
            final boolean coarse = tier() >= 1;
            final int range = outer ? FAR3_RANGE : FAR_RANGE;
            final List<CloudField.Storm> farStorms = storms(level, partialTick, coverage, hx - range - 400.0, hz - range - 400.0, hx + range + 400.0, hz + range + 400.0);
            final int farKey = farStormKey(farStorms, hx, hz, outer);
            final long id = ++farIdSeq;
            lastBuildTick = gameTime;
            lastFarBuildTick = gameTime;
            // 遠景と近くの窓は別々の作業スレッドで同時に作る(11-09 段階 2a-2)。穴の持ち分は穴の真ん中だけで決まるので、遠景を待たずに分けられる。
            final List<CloudField.Storm> mine = inHoleAt(all, hx, hz);
            CompletableFuture<Window> farF = CompletableFuture.supplyAsync(() -> buildFar(hx, hz, c, style, id, outer, coarse, farStorms, farKey), CloudThread.EXECUTOR);
            CompletableFuture<Window> nearF = CompletableFuture.supplyAsync(() -> build(cx, cz, radius, c, style, id, all, mine, stormKey(mine, fx, fz) * 31 + all.size(), voxel),
                    CloudThread.EXECUTOR);
            pendingPair = farF.thenCombine(nearF, (fw, nw) -> new Window[]{fw, nw});
        } else if (!busy && (moved || grown)) {
            final float c = coverage;
            final int k = key;
            lastBuildTick = gameTime;
            pending = CompletableFuture.supplyAsync(() -> build(cx, cz, radius, c, style, farId, floorStorms, meshStorms, k, voxel), CloudThread.EXECUTOR);
        }
        if (DEV_LOG && far && gameTime - lastLog >= 200) {
            lastLog = gameTime;
            // 開発用(11-09 段階 2a-2): 近いアーチ(トンネル)の真ん中と、近い浮いた雲(撮る所を探す)。世界の座標。
            CloudField.Storm arch = null;
            CloudField.Storm floater = null;
            for (CloudField.Storm s : all) {
                double sx = (s.x() + s.x2()) * 0.5;
                double sz = (s.z() + s.z2()) * 0.5;
                if (s.kind() == CloudField.KIND_RIDGE && s.anvil() && archOpen(all, s)
                        && (arch == null || Math.hypot(sx - fx, sz - fz) < Math.hypot((arch.x() + arch.x2()) * 0.5 - fx, (arch.z() + arch.z2()) * 0.5 - fz))) {
                    arch = s;
                }
                if (s.kind() == CloudField.KIND_FLOAT && (floater == null || Math.hypot(s.x() - fx, s.z() - fz) < Math.hypot(floater.x() - fx, floater.z() - fz))) {
                    floater = s;
                }
            }
            int archAll = 0;
            int archOpenN = 0;
            int ridgeN = 0;
            for (CloudField.Storm s : all) {
                if (s.kind() == CloudField.KIND_RIDGE) {
                    ridgeN++;
                    if (s.anvil()) {
                        archAll++;
                        if (archOpen(all, s)) {
                            archOpenN++;
                        }
                    }
                }
            }
            OvergrownVegetation.LOGGER.info("VoxelClouds 尾根 {} アーチ {} 抜けている {}(まわり {} ブロック)", ridgeN, archAll, archOpenN, (int) STORM_RANGE);
            if (arch != null) {
                OvergrownVegetation.LOGGER.info("VoxelClouds 近いアーチ 真ん中 世界 {} {} 稜線 {} 端 {} {} → {} {}", Math.round((arch.x() + arch.x2()) * 0.5 + camX - fx),
                        Math.round((arch.z() + arch.z2()) * 0.5 + camZ - fz), arch.top(), Math.round(arch.x() + camX - fx), Math.round(arch.z() + camZ - fz),
                        Math.round(arch.x2() + camX - fx), Math.round(arch.z2() + camZ - fz));
            }
            if (floater != null) {
                OvergrownVegetation.LOGGER.info("VoxelClouds 近い浮いた雲 世界 {} {} {} 半径 {}", Math.round(floater.x() + camX - fx), floater.top(),
                        Math.round(floater.z() + camZ - fz), Math.round(floater.radius()));
            }
            OvergrownVegetation.LOGGER.info("VoxelClouds 量 {} 並び {} 面 {} 遠景 {}(見え {}) 段 {} 風 {} {}", String.format("%.2f", coverage), style,
                    shown == null ? -1 : shown.quads, farShown == null ? -1 : farShown.quads, String.format("%.2f", farAlpha), tier(),
                    String.format("%.3f", CloudDriftClient.velX()), String.format("%.3f", CloudDriftClient.velZ()));
        }
        boolean nearOk = shown != null && shown.quads > 0 && vbo != null;
        float farShow = sky.farClouds * farAlpha;
        boolean farOk = farShown != null && farShown.quads > 0 && farVbo != null && farShow > 0.002F;
        boolean cut = farOk && shown != null && shown.farId == farShown.id;
        boolean prevOk = prevVbo != null && prevShown != null && prevShown.quads > 0;
        boolean prevFarOk = prevFarVbo != null && prevFarShown != null && prevFarShown.quads > 0 && farShow > 0.002F;
        if (!nearOk && !farOk && !prevOk && !prevFarOk) {
            // 下の層・積乱雲が無くても巻雲は描く(11-09 段階 2a、{@link Cirrus})。
            Cirrus.draw(level, partialTick, modelView, projection, camX, camY, camZ, far, Minecraft.getInstance().gameRenderer.getDepthFar() * 0.9F);
            return;
        }

        ShaderInstance shader = SkyShaders.voxelClouds();
        double dy = CloudField.LOW_BASE - camY;
        float depthFar = Minecraft.getInstance().gameRenderer.getDepthFar();
        int nearRadius = shown != null ? shown.radius : radius;
        float fadeEnd = nearRadius * CloudField.CELL_XZ - 24.0F;
        float fadeStart = fadeEnd * 0.7F;
        float split = depthFar * 0.9F;
        shader.safeGetUniform("Split").set(split, far ? 1.0F : -1.0F, FAR_NEAR, FAR_FAR);
        // 雲の海(SEA_TAG)は StormFade.zw で消える。遠景が続きを描く間は消さない(穴の外の床はシェーダーが捨てる)。
        if (cut) {
            shader.safeGetUniform("StormFade").set(30000.0F, 30100.0F, 30000.0F, 30100.0F);
        } else {
            shader.safeGetUniform("StormFade").set(SEA_FADE_FROM, SEA_FADE_TO, SEA_FADE_FROM, SEA_FADE_TO);
        }
        shader.safeGetUniform("Puff").set(1.0F, 1.0F, 0.0F, 0.0F);
        shader.safeGetUniform("Far").set(farFadeFrom(), farFadeTo(), farShow, cut ? 1.0F : 0.0F);
        if (farShown != null) {
            shader.safeGetUniform("FarHole").set((float) (farShown.holeX - FAR_HOLE - fx), (float) (farShown.holeZ - FAR_HOLE - fz),
                    (float) (farShown.holeX + FAR_HOLE - fx), (float) (farShown.holeZ + FAR_HOLE - fz));
        }
        shader.safeGetUniform("Curve").set(sky.curvature, CURVE_FROM, 0.5F / SkyDev.curveRadius(), SkyDev.horizonHaze);
        for (int i = 0; i < 3; i++) {
            // 雲の中の光(11-16 雷雨の強化): 積乱雲からの落雷の所を光らせる(前は口だけで 0)。
            float[] flash = com.yayuy.overgrownvegetation.client.atmosphere.StormStrikes.cloudFlash(i, camX, camY, camZ);
            shader.safeGetUniform("Flash" + i).set(flash[0], flash[1], flash[2], flash[3]);
        }
        // 雲の中(改善 ②、{@link CloudInterior}): 雲の箱も同じ霧に沈め、内側の面は描かない。
        float inside = CloudInterior.amount(partialTick);
        if (inside > 0.001F) {
            float[] ic = CloudInterior.fogColor(level, partialTick);
            shader.safeGetUniform("InsideFog").set(CloudInterior.FOG, inside, 0.0F, 0.0F);
            shader.safeGetUniform("InsideColor").set(ic[0], ic[1], ic[2], 1.0F);
        } else {
            shader.safeGetUniform("InsideFog").set(1000.0F, 0.0F, 0.0F, 0.0F);
        }
        Vec3 cloud = cloudColor(level, partialTick, level.getCloudColor(partialTick));
        Vec3 skyColor = level.getSkyColor(new Vec3(camX, camY, camZ), partialTick);
        Vec3 sunDir = SunLight.sunDirection(level, partialTick);
        boolean moon = sunDir.y < 0.0;
        Vec3 light = moon ? sunDir.scale(-1.0) : sunDir;
        float strength = SunLight.lightStrength(level, light, partialTick);
        float warm = moon ? 0.0F : 1.0F - SunLight.smooth(Mth.clamp((float) sunDir.y / 0.35F, 0.0F, 1.0F));
        float[] moonColor = new float[3];
        if (moon) {
            SunLight.airColor(level, light, partialTick, moonColor);
        }
        float sr = moon ? moonColor[0] : 1.0F;
        float sg = moon ? moonColor[1] : Mth.lerp(warm, 0.97F, 0.62F);
        float sb = moon ? moonColor[2] : Mth.lerp(warm, 0.9F, 0.35F);
        shader.safeGetUniform("CloudColor").set((float) cloud.x, (float) cloud.y, (float) cloud.z, 1.0F);
        shader.safeGetUniform("SunDir").set((float) light.x, (float) light.y, (float) light.z, strength);
        shader.safeGetUniform("SunColor").set(sr, sg, sb, 1.0F);
        shader.safeGetUniform("SkyColor").set((float) skyColor.x, (float) skyColor.y, (float) skyColor.z, 1.0F);
        shader.safeGetUniform("Fade").set(fadeStart, fadeEnd, (float) sunDir.x, (float) sunDir.z);
        float[] sunrise = level.effects().getSunriseColor(level.getTimeOfDay(partialTick), partialTick);
        if (sunrise != null) {
            shader.safeGetUniform("Glow").set(sunrise[0], sunrise[1], sunrise[2], sunrise[3]);
        } else {
            shader.safeGetUniform("Glow").set(0.0F, 0.0F, 0.0F, 0.0F);
        }
        shader.safeGetUniform("World").set((float) camY, coverage, sky.floorFlatness, 0.0F);
        // 雨の前触れ(11-09 段階 2a-6 ユーザー決定「前触れの間だけ雲の底を少し下げて暗くする」): 底を {@link #PRE_RAIN_DROP} 下げ、下面を暗く。
        float preRain = com.yayuy.overgrownvegetation.sky.CloudWeather.preRain(level, partialTick);
        shader.safeGetUniform("PreRain").set(preRain * PRE_RAIN_DROP, preRain, 0.0F, 0.0F);
        // 時間帯の色(11-08、design の値。仮決め): 昼 → 夕方(日が低いほど)→ 夜。
        float duskK = moon ? 0.0F : 1.0F - SunLight.smooth(Mth.clamp((float) sunDir.y / 0.35F, 0.0F, 1.0F));
        float nightK = SunLight.night(level, partialTick);
        float[] lit = tint(sky.litDay, sky.litDusk, sky.litNight, duskK, nightK);
        float[] shadeT = tint(sky.shadeDay, sky.shadeDusk, sky.shadeNight, duskK, nightK);
        shader.safeGetUniform("LitTint").set(lit[0], lit[1], lit[2], 1.0F);
        shader.safeGetUniform("ShadeTint").set(shadeT[0], shadeT[1], shadeT[2], 1.0F);
        // 地平の霧の色(改善 ③④): 遠くの雲・曇りの天井・夜の遠い雲の底をここへ溶かす。
        float[] horizon = RenderSystem.getShaderFogColor();
        shader.safeGetUniform("HorizonColor").set(horizon[0], horizon[1], horizon[2], 1.0F);
        // 森の霞(11-13 見え方の点検 A): 霞で満ちた空の中では雲も霞に溶かす。
        com.yayuy.overgrownvegetation.client.atmosphere.CloudHaze.set(shader);
        float[] clear = clearCloudColor(level, partialTick);
        shader.safeGetUniform("Rough").set(clear[0], clear[1], clear[2], roughness(level, partialTick));
        // 風の筋の向き = ふだんの向きの風(固定)。今の風の向きにすると、場の座標の原点のまわりで模様ごと回って速く流れて見えた(10-18)。
        double wl = Math.sqrt(CloudField.WIND_X * CloudField.WIND_X + CloudField.WIND_Z * CloudField.WIND_Z);
        shader.safeGetUniform("Field").set((float) (fx - Math.floor(fx / FIELD_PERIOD) * FIELD_PERIOD),
                (float) (fz - Math.floor(fz / FIELD_PERIOD) * FIELD_PERIOD), (float) (CloudField.WIND_X / wl), (float) (CloudField.WIND_Z / wl));
        // w = 秒(11-18: 嵐の巣の壁の濃淡の揺れ・雨の帯の動き。4096 秒で一回り)。
        shader.safeGetUniform("Look2").set(sky.windStreaks, sky.blueHaze, sky.texture, (float) ((level.getGameTime() + (double) partialTick) / 20.0 % 4096.0));
        // 積乱雲が雲に落とす影(11-09 段階 2a、{@link CloudSelfShadow})。
        if (far) {
            CloudSelfShadow.update(level, partialTick, fx, fz, camY, stormsHere, stormKey(stormsHere, fx, fz));
        }
        CloudSelfShadow.Map shadowMap = CloudSelfShadow.current();
        net.minecraft.client.renderer.texture.DynamicTexture shadowTexture = CloudSelfShadow.texture();
        if (shadowMap != null && shadowTexture != null) {
            // 場の座標の原点を世界へ戻し(風で流れた分を足す)、カメラからの位置に。
            float ox = (float) (shadowMap.originX + CloudField.offsetX((double) gameTime + partialTick) - camX);
            float oz = (float) (shadowMap.originZ + CloudField.offsetZ((double) gameTime + partialTick) - camZ);
            shader.safeGetUniform("ShadowA").set(ox, oz, (float) (CloudSelfShadow.SIZE * CloudSelfShadow.TEXEL), CloudSelfShadow.setting());
            shader.safeGetUniform("ShadowB").set((float) shadowMap.kx, (float) shadowMap.kz, (float) CloudSelfShadow.REF, (float) CloudSelfShadow.RANGE);
            RenderSystem.setShaderTexture(0, shadowTexture.getId());
        } else {
            shader.safeGetUniform("ShadowA").set(0.0F, 0.0F, 1.0F, 0.0F);
        }

        RenderTarget cloudsTarget = far ? null : Minecraft.getInstance().levelRenderer.getCloudsTarget();
        if (cloudsTarget != null) {
            cloudsTarget.bindWrite(false);
        }
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        // 1 回目: 奥行きだけ(透けない所だけ)。2 回目: 色(奥行きを書かずに重ねる。輪郭のやわらかさ)。バニラの雲と同じ。
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        shader.safeGetUniform("Look").set(sky.celShading, sky.softEdges, 1.0F, 1.0F);
        drawMeshes(shader, modelView, projection, nearOk, farOk, prevOk, prevFarOk, fx, fz, dy);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        shader.safeGetUniform("Look").set(sky.celShading, sky.softEdges, 1.0F, 0.0F);
        drawMeshes(shader, modelView, projection, nearOk, farOk, prevOk, prevFarOk, fx, fz, dy);
        // 巻雲(11-09 段階 2a): 雲を塗った後に、同じ奥行きの幅で、奥行きを書かずに重ねる(手前の積乱雲には隠れ、上から見ると積乱雲の手前に見える)。
        Cirrus.draw(level, partialTick, modelView, projection, camX, camY, camZ, far, split);
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        if (far) {
            // 地形はこの後にいつもの奥行きで描くので、雲だけの奥行きを消す(空は奥行きを書かないので消しても困らない)。
            RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        }
        if (cloudsTarget != null) {
            Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        }
    }

    /** 昼・夕方・夜の色を混ぜる。 */
    private static float[] tint(float[] day, float[] dusk, float[] night, float duskK, float nightK) {
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            out[i] = Mth.lerp(nightK, Mth.lerp(duskK, day[i], dusk[i]), night[i]);
        }
        return out;
    }

    private static void drawMeshes(ShaderInstance shader, Matrix4f modelView, Matrix4f projection, boolean nearOk, boolean farOk,
                                   boolean prevOk, boolean prevFarOk, double fx, double fz, double dy) {
        float k = swapProgress(swapStart);
        float kFar = swapProgress(farSwapStart);
        if (nearOk) {
            drawOne(shader, modelView, projection, vbo, shown, fx, fz, dy, k, prevVbo != null ? 1.0F : 0.0F);
        }
        if (prevOk) {
            drawOne(shader, modelView, projection, prevVbo, prevShown, fx, fz, dy, k, -1.0F);
        }
        if (farOk) {
            drawOne(shader, modelView, projection, farVbo, farShown, fx, fz, dy, kFar, prevFarVbo != null ? 1.0F : 0.0F);
        }
        if (prevFarOk) {
            drawOne(shader, modelView, projection, prevFarVbo, prevFarShown, fx, fz, dy, kFar, -1.0F);
        }
        shader.safeGetUniform("Dissolve").set(0.0F, 0.0F, 0.0F, 0.0F);
        VertexBuffer.unbind();
    }

    /** 1 つの形を描く。mode 1 = 差し替えの新しい形・−1 = 古い形・0 = 差し替えていない(Dissolve の説明は voxel_clouds.fsh)。 */
    private static void drawOne(ShaderInstance shader, Matrix4f modelView, Matrix4f projection, VertexBuffer buffer, Window w,
                                double fx, double fz, double dy, float progress, float mode) {
        shader.safeGetUniform("Offset").set((float) (w.originX * (double) CloudField.CELL_XZ - fx), (float) dy,
                (float) (w.originZ * (double) CloudField.CELL_XZ - fz));
        shader.safeGetUniform("Dissolve").set(progress, mode, 0.0F, 0.0F);
        buffer.bind();
        buffer.drawWithShader(modelView, projection, shader);
    }

    /** 差し替えの進み(0〜1)。時刻が戻ったら(ワールドの入り直し)終わったことにする。 */
    private static float swapProgress(double start) {
        double t = (nowTicks - start) / SWAP_TICKS;
        return t < 0.0 ? 1.0F : (float) Math.min(1.0, t);
    }

    /** 古い近くの窓を手放す(VBO は次の差し替えに使い回す)。 */
    private static void endSwap() {
        if (prevVbo != null) {
            if (spareVbo == null) {
                spareVbo = prevVbo;
            } else {
                prevVbo.close();
            }
        }
        prevVbo = null;
        prevShown = null;
    }

    private static void endFarSwap() {
        if (prevFarVbo != null) {
            if (spareFarVbo == null) {
                spareFarVbo = prevFarVbo;
            } else {
                prevFarVbo.close();
            }
        }
        prevFarVbo = null;
        prevFarShown = null;
    }

    /** 新しい窓が今の窓と重なるか(重ならない = 瞬間移動・入り直し。そのときは溶け合わせずに差し替える)。 */
    private static boolean overlaps(Window a, Window b) {
        int reach = Math.max(a.radius, 1);
        return Math.abs(a.centerX - b.centerX) < reach && Math.abs(a.centerZ - b.centerZ) < reach;
    }

    // ---------------------------------------------------------------- 遠景

    /** 高さで遠景を使うかを決め(行き来しないよう 395〜410 で切り替える)、見え方を 1 秒かけて寄せる。下りて消えきったら遠景を手放す。 */
    private static void updateFar(double now, double camY) {
        if (DesignData.sky().farClouds <= 0.0F) {
            farWanted = false;
        } else if (camY >= FAR_ON_Y || SkyDev.farFromGround(1.0F)) {
            farWanted = true;
        } else if (camY < FAR_OFF_Y) {
            farWanted = false; // 地上で曇りになったときもここで消える(SkyDev.farFromGround)
        }
        double dt = Mth.clamp(now - farAlphaTime, 0.0, 5.0);
        farAlphaTime = now;
        boolean ready = farWanted && farShown != null && shown != null && shown.farId == farShown.id;
        float step = (float) dt * 0.05F;
        farAlpha = ready ? Math.min(1.0F, farAlpha + step) : Math.max(0.0F, farAlpha - step);
        if (!farWanted && farAlpha <= 0.0F && farShown != null && pendingPair == null) {
            farShown = null;
        }
        // 外の輪(行き来しないよう 850〜950 で切り替える。低の段では使わない)。
        // 11-08 改善 ⑭: 外の輪は高の段だけ(曇りの日に 19 万面・GPU +2.8 ms だった。前は中でも作った)。
        if ((camY >= FAR3_ON_Y || SkyDev.farFromGround(2.0F)) && tier() == 0) {
            outerWanted = true;
        } else if ((camY < FAR3_OFF_Y && !SkyDev.farFromGround(2.0F)) || tier() > 0) {
            outerWanted = false;
        }
        float reachTo = farShown != null && farShown.outer ? FAR3_RANGE - 400.0F : FAR_FADE_TO;
        float move = (float) dt * 200.0F;
        farReach = farReach < reachTo ? Math.min(reachTo, farReach + move) : Math.max(reachTo, farReach - move);
    }

    static float farFadeFrom() {
        return farReach - (FAR_FADE_TO - FAR_FADE_FROM);
    }

    static float farFadeTo() {
        return farReach;
    }

    /** 遠景を作り直すか(無い・穴の真ん中から離れた・雲の量が変わった・段が変わった)。 */
    private static boolean needFar(ClientLevel level, float partialTick, long gameTime, double fx, double fz, float coverage, int style) {
        if (farShown == null || farShown.outer != outerWanted || farShown.coarse != (tier() >= 1) || farShown.style != style || farShown.seed != CloudField.seed()
                || !farShown.shape.equals(com.yayuy.overgrownvegetation.sky.CloudShape.current())) {
            return true;
        }
        if (Math.abs(fx - farShown.holeX) > FAR_MOVE || Math.abs(fz - farShown.holeZ) > FAR_MOVE || Math.abs(coverage - farShown.coverage) > 0.02F) {
            return true;
        }
        // 遠景の積乱雲の育ち(2 秒に 1 回だけ数え、作り直しは 1 分に 1 回まで)。
        if (gameTime - lastFarCheck >= 40L) {
            lastFarCheck = gameTime;
            int range = farShown.outer ? FAR3_RANGE : FAR_RANGE;
            List<CloudField.Storm> farStorms = storms(level, partialTick, coverage, farShown.holeX - range - 400.0,
                    farShown.holeZ - range - 400.0, farShown.holeX + range + 400.0, farShown.holeZ + range + 400.0);
            farStormKeyNow = farStormKey(farStorms, farShown.holeX, farShown.holeZ, farShown.outer);
        }
        return farStormKeyNow != farShown.stormKey && gameTime - lastFarBuildTick >= FAR_REBUILD_TICKS;
    }

    private static void uploadFar(Window w) {
        endFarSwap();
        if (farShown != null && farShown.quads > 0 && farVbo != null && farShown.outer == w.outer
                && Math.abs(farShown.holeX - w.holeX) < FAR_RANGE && Math.abs(farShown.holeZ - w.holeZ) < FAR_RANGE) {
            prevFarVbo = farVbo;
            prevFarShown = farShown;
            farVbo = spareFarVbo;
            spareFarVbo = null;
            farSwapStart = nowTicks;
        }
        try {
            if (w.mesh != null) {
                if (farVbo == null) {
                    farVbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
                }
                farVbo.bind();
                farVbo.upload(w.mesh);
                VertexBuffer.unbind();
                w.mesh = null;
            }
        } finally {
            if (w.bytes != null) {
                w.bytes.close();
                w.bytes = null;
            }
        }
        farShown = w;
    }

    /**
     * 遠景を作る(別スレッド)。雲の海: 穴(FAR_HOLE)〜FAR_MID を 48 ブロック、その外 FAR_RANGE までを 96 ブロックの柱で(中・低の段は倍)。
     * 遠くほど切れ目と谷を閉じる(FAR_CLOSE_FROM〜TO)。outer(高い所)なら外の輪 FAR3_RANGE までを FAR_CELL_3 の柱で足す。
     */
    private static Window buildFar(int holeX, int holeZ, float coverage, int style, long id, boolean outer, boolean coarse,
                                   List<CloudField.Storm> all, int stormKey) {
        long started = System.nanoTime();
        long cpu0 = CPU.getCurrentThreadCpuTime();
        // 内の 2 段の雲の海は FAR_RANGE までに届く雲だけで引く(外の輪の雲まで回すと、柱ごとの裾野の計算が重い)。
        List<CloudField.Storm> storms = new java.util.ArrayList<>();
        List<CloudField.Storm> mine = new java.util.ArrayList<>();
        List<CloudField.Storm> outerStorms = new java.util.ArrayList<>();
        for (CloudField.Storm s : all) {
            double d = Math.max(Math.abs(s.x() - holeX), Math.abs(s.z() - holeZ));
            if (d < FAR_RANGE + s.reach() + 400.0) {
                storms.add(s);
            }
            if (farMeshed(holeX, holeZ, s, outer)) {
                mine.add(s);
                if (d + s.reach() > FAR_RANGE) {
                    outerStorms.add(s);
                }
            }
        }
        Window w = new Window();
        w.id = id;
        w.stormKey = stormKey;
        w.outer = outer;
        w.coarse = coarse;
        w.style = style;
        int cell1 = coarse ? FAR_CELL_1 * 2 : FAR_CELL_1;
        int cell2 = coarse ? FAR_CELL_2 * 2 : FAR_CELL_2;
        w.holeX = holeX;
        w.holeZ = holeZ;
        w.centerX = holeX / CloudField.CELL_XZ;
        w.centerZ = holeZ / CloudField.CELL_XZ;
        w.originX = (holeX - FAR_RANGE) / CloudField.CELL_XZ;
        w.originZ = (holeZ - FAR_RANGE) / CloudField.CELL_XZ;
        w.coverage = coverage;
        float threshold = CloudField.threshold(coverage);
        ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 21);
        BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        int quads = 0;
        quads += farGrid(b, storms, mine, threshold, coverage, style, w, Math.floorDiv(holeX - FAR_MID, cell1), Math.floorDiv(holeZ - FAR_MID, cell1), 2 * FAR_MID / cell1,
                cell1, Math.floorDiv(holeX - FAR_HOLE, cell1), Math.floorDiv(holeZ - FAR_HOLE, cell1), 2 * FAR_HOLE / cell1);
        quads += farGrid(b, storms, mine, threshold, coverage, style, w, Math.floorDiv(holeX - FAR_RANGE, cell2), Math.floorDiv(holeZ - FAR_RANGE, cell2), 2 * FAR_RANGE / cell2,
                cell2, Math.floorDiv(holeX - FAR_MID, cell2), Math.floorDiv(holeZ - FAR_MID, cell2), 2 * FAR_MID / cell2);
        if (outer) {
            quads += farGrid(b, outerStorms, mine, threshold, coverage, style, w, Math.floorDiv(holeX - FAR3_RANGE, FAR_CELL_3), Math.floorDiv(holeZ - FAR3_RANGE, FAR_CELL_3),
                    2 * FAR3_RANGE / FAR_CELL_3, FAR_CELL_3, Math.floorDiv(holeX - FAR_RANGE, FAR_CELL_3), Math.floorDiv(holeZ - FAR_RANGE, FAR_CELL_3),
                    2 * FAR_RANGE / FAR_CELL_3);
        }
        int floorQuads = quads;
        quads += farStormTiles(b, mine, w);
        w.storms = mine.size();
        if (DEV_LOG) {
            OvergrownVegetation.LOGGER.info("VoxelClouds 遠景を作った 面 {}(床 {}) 積乱雲 {} 外の輪 {} {} ms(CPU {} ms)", quads, floorQuads, mine.size(), outer,
                    (System.nanoTime() - started) / 1000000L, (CPU.getCurrentThreadCpuTime() - cpu0) / 1000000L);
        }
        w.quads = quads;
        w.mesh = quads > 0 ? b.build() : null;
        if (w.mesh == null) {
            w.quads = 0;
            bytes.close();
        } else {
            w.bytes = bytes;
        }
        return w;
    }

    /** 遠景の雲の海の格子 1 段(遠いほど切れ目と谷を閉じる)。 */
    private static int farGrid(BufferBuilder b, List<CloudField.Storm> storms, List<CloudField.Storm> meshStorms, float threshold, float coverage, int style, Window w,
                               int gx0, int gz0, int n, int size, int skipX, int skipZ, int skipN) {
        int[] bottom = new int[n * n];
        int[] top = new int[n * n];
        int per = size / CloudField.CELL_XZ;
        boolean floor = style >= CloudField.STYLE_VALLEY;
        java.util.Arrays.fill(bottom, 1);
        // GRID_TILE 升の四角ごとに、効く積乱雲だけで柱を引く(11-09 軽量化。遠景は 200 を超える雲を柱ごとに回していた)。
        for (int tj = 0; tj < n; tj += GRID_TILE) {
            for (int ti = 0; ti < n; ti += GRID_TILE) {
                double tx0 = (gx0 + ti) * (double) size;
                double tz0 = (gz0 + tj) * (double) size;
                List<CloudField.Storm> here = storms.isEmpty() ? storms : CloudField.near(storms, tx0, tz0, tx0 + GRID_TILE * size, tz0 + GRID_TILE * size);
                farTile(here, threshold, coverage, style, w, gx0, gz0, n, size, skipX, skipZ, skipN, per, floor, bottom, top, ti, tj);
            }
        }
        float offX = gx0 * (float) size - w.originX * (float) CloudField.CELL_XZ;
        float offZ = gz0 * (float) size - w.originZ * (float) CloudField.CELL_XZ;
        // 11-18 試し: 地上からも遠景を出すとき(SkyDev.farLow)は下面も作る(無いと、下から見て横の面だけが細い線に見えた)。
        SkyDev.heap(bottom, top, n, size, gx0, gz0, coverage);
        // 積乱雲に食い込んだ柱は遠景の積乱雲の印で(この窓が形にする積乱雲だけ。{@link #stormColumnTags})。
        float[] tags = stormColumnTags(bottom, top, n, gx0, gz0, size, meshStorms, FAR_FLOOR_STORM_TAG, FAR_FLOOR_NEST_TAG, FAR_FLOOR_STORM_TAG);
        return layer(b, bottom, top, n, n, offX, offZ, FAR_SEA_TAG, size, SkyDev.farLow > 0.0F, tags);
    }

    /** 遠景の格子の四角 1 つ(柱 ti..ti+GRID_TILE, tj..tj+GRID_TILE)。 */
    private static void farTile(List<CloudField.Storm> storms, float threshold, float coverage, int style, Window w, int gx0, int gz0, int n, int size,
                                int skipX, int skipZ, int skipN, int per, boolean floor, int[] bottom, int[] top, int ti, int tj) {
        for (int j = tj; j < Math.min(n, tj + GRID_TILE); j++) {
            for (int i = ti; i < Math.min(n, ti + GRID_TILE); i++) {
                int k = j * n + i;
                bottom[k] = 1;
                top[k] = 0;
                int gx = gx0 + i;
                int gz = gz0 + j;
                if (gx >= skipX && gx < skipX + skipN && gz >= skipZ && gz < skipZ + skipN) {
                    continue;
                }
                double px = (gx + 0.5) * size;
                double pz = (gz + 0.5) * size;
                double d = Math.hypot(px - w.holeX, pz - w.holeZ);
                float close = SunLight.smooth((float) Mth.clamp((d - FAR_CLOSE_FROM) / (FAR_CLOSE_TO - FAR_CLOSE_FROM), 0.0, 1.0));
                // 晴れの日の小さい積雲は遠景では作らない(粗い柱では点々の箱になる)。
                CloudField.Column c = CloudField.column(gx * per + per / 2, gz * per + per / 2, threshold, Mth.lerp(close, coverage, 1.0F), storms, style, false);
                // 積乱雲の台の下は空ける(台は積乱雲の形が受け持つ)。
                if (!storms.isEmpty() && CloudField.baseCovers(storms, px, pz)) {
                    continue;
                }
                if (c.present()) {
                    // 外の輪(192)は 32 に丸める(段が減り横の面が減る。11-08 改善 ⑭)。
                    int q = size >= FAR_CELL_3 ? 32 : (size >= FAR_CELL_2 ? 16 : 8);
                    bottom[k] = c.bottom() - CloudField.LOW_BASE;
                    top[k] = Math.max(bottom[k] + q, Math.round((c.top() - CloudField.LOW_BASE) / (float) q) * q);
                    SkyDev.scatter(bottom, top, k, px, pz, coverage);
                } else if (floor && close > 0.5F) {
                    bottom[k] = 0;
                    top[k] = 2 * CloudField.CELL_Y;
                }
            }
        }
    }

    // ---------------------------------------------------------------- 近くの窓

    private static void upload(Window w) {
        // 今の形が見えていれば、古い形として残して溶け合わせる(11-12)。
        endSwap();
        if (shown != null && shown.quads > 0 && vbo != null && overlaps(shown, w)) {
            prevVbo = vbo;
            prevShown = shown;
            vbo = spareVbo;
            spareVbo = null;
            swapStart = nowTicks;
        }
        try {
            if (w.mesh != null) {
                if (vbo == null) {
                    vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
                }
                vbo.bind();
                vbo.upload(w.mesh);
                VertexBuffer.unbind();
                w.mesh = null;
            }
        } finally {
            if (w.bytes != null) {
                w.bytes.close();
                w.bytes = null;
            }
        }
        shown = w;
    }

    private static void discardPending() {
        farShown = null;
        farAlpha = 0.0F;
        if (pendingPair != null) {
            CompletableFuture<Window[]> p = pendingPair;
            pendingPair = null;
            p.thenAccept(pair -> {
                for (Window w : pair) {
                    close(w);
                }
            });
        }
        if (pending != null) {
            CompletableFuture<Window> p = pending;
            pending = null;
            p.thenAccept(VoxelClouds::close);
        }
    }

    private static void close(Window w) {
        if (w.mesh != null) {
            w.mesh.close();
        }
        if (w.bytes != null) {
            w.bytes.close();
        }
    }

    /** 場から柱を引いて、外から見える面の形を作る(別スレッド)。 */
    private static Window build(int centerX, int centerZ, int radius, float coverage, int style, long farId,
                                List<CloudField.Storm> floorStorms, List<CloudField.Storm> meshStorms, int stormKey, int voxel) {
        Window w = new Window();
        long started = System.nanoTime();
        long cpu0 = CPU.getCurrentThreadCpuTime();
        w.stormKey = stormKey;
        w.storms = meshStorms.size();
        int size = radius * 2 + 1;
        w.centerX = centerX;
        w.centerZ = centerZ;
        w.radius = radius;
        w.originX = centerX - radius;
        w.originZ = centerZ - radius;
        w.coverage = coverage;
        w.style = style;
        w.farId = farId;
        float threshold = CloudField.threshold(coverage);
        ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 20);
        BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        int quads;
        if (style >= CloudField.STYLE_VALLEY) {
            // 雲の海の地形(谷の並び方。既定): 窓の真ん中のまわり FLOOR_RANGE を FLOOR_CELL の柱でひと続きに(元の版の seaMesh/floorGrid と同じ)。
            // 8 ブロックの窓(640)だけにすると、地上からは雲の海の端が輪に見え、雲の上からは遠景の穴(1344)が空いた(11-08 の撮影)。
            double cx0 = centerX * (double) CloudField.CELL_XZ;
            double cz0 = centerZ * (double) CloudField.CELL_XZ;
            int g0x = Mth.floor((cx0 - FLOOR_RANGE) / FLOOR_CELL);
            int g0z = Mth.floor((cz0 - FLOOR_RANGE) / FLOOR_CELL);
            int n = Mth.ceil(2.0 * FLOOR_RANGE / FLOOR_CELL) + 1;
            quads = floorGrid(b, floorStorms, threshold, coverage, style, w, g0x, g0z, n, FLOOR_CELL);
        } else {
            int[] bottom = new int[size * size];
            int[] top = new int[size * size];
            for (int j = 0; j < size; j++) {
                for (int i = 0; i < size; i++) {
                    int k = j * size + i;
                    // 群れ・壁と橋の並び方: 巨大な雲のまわりは窓の中だけ雲海が厚くなる(前の版の窓の外までの粗い雲海は戻していない)。積乱雲の台の下は空ける。
                    CloudField.Column c = CloudField.column(w.originX + i, w.originZ + j, threshold, coverage, floorStorms, style);
                    if (c.present() && !floorStorms.isEmpty()
                            && CloudField.baseCovers(floorStorms, (w.originX + i + 0.5) * CloudField.CELL_XZ, (w.originZ + j + 0.5) * CloudField.CELL_XZ)) {
                        // 11-12: 空けずに薄い床だけ残す(谷の並び方の floorGrid と同じ理由)。
                        c = new CloudField.Column(CloudField.LOW_BASE, CloudField.LOW_BASE + CloudField.CELL_Y, 0.0F);
                    }
                    bottom[k] = c.present() ? c.bottom() - CloudField.LOW_BASE : 1;
                    top[k] = c.present() ? c.top() - CloudField.LOW_BASE : 0;
                }
            }
            quads = layer(b, bottom, top, size, size, 0.0F, 0.0F, LOW_TAG, CloudField.CELL_XZ, true,
                    stormColumnTags(bottom, top, size, w.originX, w.originZ, CloudField.CELL_XZ, floorStorms, FLOOR_STORM_TAG, FLOOR_NEST_TAG, FLOOR_GIANT_TAG));
        }
        int floorQuads = quads;
        quads += stormTiles(b, meshStorms, w, voxel);
        w.quads = quads;
        if (DEV_LOG) {
            OvergrownVegetation.LOGGER.info("VoxelClouds 近くを作った 面 {}(床 {}) 積乱雲 {} 箱 {} {} ms(CPU {} ms)", quads, floorQuads, meshStorms.size(), voxel,
                    (System.nanoTime() - started) / 1000000L, (CPU.getCurrentThreadCpuTime() - cpu0) / 1000000L);
        }
        w.mesh = quads > 0 ? b.build() : null;
        if (w.mesh == null) {
            w.quads = 0;
            bytes.close();
        } else {
            w.bytes = bytes;
        }
        return w;
    }

    /** 雲の海の地形の柱の大きさ(ブロック。箱 3 つ分)と、形にする範囲(窓の真ん中から。遠景の穴 FAR_HOLE + 動きを覆う)。元の版の値。 */
    static final int FLOOR_CELL = CloudSky.FLOOR_CELL;
    static final double FLOOR_RANGE = 1800.0;
    /** 頂点の色の a: 雲の海(シェーダーは StormFade.zw で消す)。 */
    private static final float SEA_TAG = 0.65F;
    /** 雲の海の消え始めと消え終わり(水平距離)。元の版の値。 */
    static final float SEA_FADE_FROM = 1450.0F;
    static final float SEA_FADE_TO = 1780.0F;

    /** 柱を引くときに積乱雲を絞る四角の大きさ(柱の数。{@link CloudField#near})。 */
    static final int GRID_TILE = 8;

    /**
     * 雲の海の地形の柱の格子 1 つ(柱の大きさ size、柱の番号 gx0..gx0+n)。{@link CloudSky#columnRendered} と同じ答え: 積乱雲の台の下は空け、
     * 足元の坂(厚さの上限より高い柱)は 8 ブロックの柱で描く(11-09 段階 2a-2: 24 ブロックの柱では山の裾が階段に見えた)。
     * 坂の細かい柱は {@link #GRID_TILE} 升ごとの小さな格子で作る(格子の境の横の面は、隣の柱の中に隠れる)。
     * 柱は GRID_TILE 升の四角ごとに効く積乱雲だけで引く(11-09 軽量化。答えは同じ)。
     */
    private static int floorGrid(BufferBuilder b, List<CloudField.Storm> storms, float threshold, float coverage, int style, Window w,
                                 int gx0, int gz0, int n, int size) {
        int[] bottom = new int[n * n];
        int[] top = new int[n * n];
        boolean[] fine = new boolean[n * n];
        boolean anyFine = false;
        int per = size / CloudField.CELL_XZ;
        int tiles = (n + GRID_TILE - 1) / GRID_TILE;
        List<CloudField.Storm>[] tileStorms = new List[tiles * tiles];
        for (int tj = 0; tj < tiles; tj++) {
            for (int ti = 0; ti < tiles; ti++) {
                double x0 = (gx0 + ti * GRID_TILE) * (double) size;
                double z0 = (gz0 + tj * GRID_TILE) * (double) size;
                List<CloudField.Storm> here = storms.isEmpty() ? storms : CloudField.near(storms, x0, z0, x0 + GRID_TILE * size, z0 + GRID_TILE * size);
                tileStorms[tj * tiles + ti] = here;
                for (int j = tj * GRID_TILE; j < Math.min(n, (tj + 1) * GRID_TILE); j++) {
                    for (int i = ti * GRID_TILE; i < Math.min(n, (ti + 1) * GRID_TILE); i++) {
                        int k = j * n + i;
                        int gx = gx0 + i;
                        int gz = gz0 + j;
                        bottom[k] = 1;
                        top[k] = 0;
                        if (!here.isEmpty() && CloudField.baseCovers(here, (gx + 0.5) * size, (gz + 0.5) * size)) {
                            // 積乱雲の台の下は薄い床だけ残す(11-12、ユーザー報告「積乱雲の下だけ雲の底が白い」): 前は柱ごと空けていたが、
                            // 柱(24 ブロック)と積乱雲の箱(16 ブロック)の升がずれ、埋まらない細い隙間から日の当たる床の内側の壁が下から見えた。
                            bottom[k] = 0;
                            top[k] = CloudField.CELL_Y;
                            continue;
                        }
                        CloudField.Column c = CloudField.column(gx * per + per / 2, gz * per + per / 2, threshold, coverage, here, style);
                        if (style >= CloudField.STYLE_VALLEY && CloudSky.footColumn(c)) {
                            fine[k] = true;
                            anyFine = true;
                            continue;
                        }
                        if (c.present()) {
                            bottom[k] = c.bottom() - CloudField.LOW_BASE;
                            top[k] = c.top() - CloudField.LOW_BASE;
                            SkyDev.scatter(bottom, top, k, (gx + 0.5) * size, (gz + 0.5) * size, coverage);
                        }
                    }
                }
            }
        }
        float offX = gx0 * (float) size - w.originX * (float) CloudField.CELL_XZ;
        float offZ = gz0 * (float) size - w.originZ * (float) CloudField.CELL_XZ;
        SkyDev.heap(bottom, top, n, size, gx0, gz0, coverage);
        // 積乱雲に食い込んだ柱は積乱雲の印で({@link #stormColumnTags}。遠景の窓の積乱雲に食い込んだ所も。穴の外はシェーダーが床と同じく捨てる)。
        int quads = layer(b, bottom, top, n, n, offX, offZ, SEA_TAG, size, true,
                stormColumnTags(bottom, top, n, gx0, gz0, size, storms, FLOOR_STORM_TAG, FLOOR_NEST_TAG, FLOOR_GIANT_TAG));
        if (!anyFine) {
            return quads;
        }
        // 坂の細かい柱(四角ごと)。
        int fn = GRID_TILE * per;
        int[] fb = new int[fn * fn];
        int[] ft = new int[fn * fn];
        for (int tj = 0; tj < tiles; tj++) {
            for (int ti = 0; ti < tiles; ti++) {
                boolean has = false;
                for (int j = tj * GRID_TILE; j < Math.min(n, (tj + 1) * GRID_TILE) && !has; j++) {
                    for (int i = ti * GRID_TILE; i < Math.min(n, (ti + 1) * GRID_TILE); i++) {
                        if (fine[j * n + i]) {
                            has = true;
                            break;
                        }
                    }
                }
                if (!has) {
                    continue;
                }
                List<CloudField.Storm> here = tileStorms[tj * tiles + ti];
                int cx0 = (gx0 + ti * GRID_TILE) * per;
                int cz0 = (gz0 + tj * GRID_TILE) * per;
                for (int fj = 0; fj < fn; fj++) {
                    for (int fi = 0; fi < fn; fi++) {
                        int k = fj * fn + fi;
                        fb[k] = 1;
                        ft[k] = 0;
                        int ci = ti * GRID_TILE + fi / per;
                        int cj = tj * GRID_TILE + fj / per;
                        if (ci >= n || cj >= n || !fine[cj * n + ci]) {
                            continue;
                        }
                        CloudField.Column c = CloudField.column(cx0 + fi, cz0 + fj, threshold, coverage, here, style);
                        if (c.present()) {
                            fb[k] = c.bottom() - CloudField.LOW_BASE;
                            ft[k] = c.top() - CloudField.LOW_BASE;
                        }
                    }
                }
                float fox = cx0 * (float) CloudField.CELL_XZ - w.originX * (float) CloudField.CELL_XZ;
                float foz = cz0 * (float) CloudField.CELL_XZ - w.originZ * (float) CloudField.CELL_XZ;
                quads += layer(b, fb, ft, fn, fn, fox, foz, SEA_TAG, CloudField.CELL_XZ, true,
                        stormColumnTags(fb, ft, fn, cx0, cz0, CloudField.CELL_XZ, storms, FLOOR_STORM_TAG, FLOOR_NEST_TAG, FLOOR_GIANT_TAG));
            }
        }
        return quads;
    }

    /** 開発用の要約(テストクライアントの {@code clouds status})。 */
    public static String describe() {
        return String.format("雲 有効 %s 量 %.2f 並び %d 面 %d 遠景 %d(見え %.2f・外の輪 %s) 段 %d 速さ %.3f %.3f 種 %x 雲の中 %.2f 積乱雲 %d(遠景 %d)", isActive(),
                lastLevel == null ? -1.0F : coverage(lastLevel, 0.0F), CloudSky.style(), shown == null ? -1 : shown.quads,
                farShown == null ? -1 : farShown.quads, farAlpha, outerWanted, tier(), CloudDriftClient.velX(), CloudDriftClient.velZ(), CloudField.seed(), CloudInterior.amount(1.0F), stormCount(), farShown == null ? -1 : farShown.storms);
    }

    // ---------------------------------------------------------------- 積乱雲の形(11-09 段階 2a。parked の版のまま。箱の大きさだけ段ごと)

    /**
     * 積乱雲を場の座標の TILE 四方のタイルごとに形にする(10-17 深夜。それまでは範囲の重なる雲をまとめていたが、雲の海の地形では山がつながって
     * 1 つの大きなまとまりになり、遠くの雲の箱を大きくできなかった)。タイルの中の箱だけを詰め、タイルの外の隣は全部の雲で確かめる
     * (タイルの境に内側の壁を作らない)。窓の真ん中からタイルの近い縁まで FAR_LOD より遠いタイルは箱を倍の大きさに。
     */
    private static int stormTiles(BufferBuilder b, java.util.List<CloudField.Storm> storms, Window w, int voxel) {
        if (storms.isEmpty()) {
            return 0;
        }
        java.util.List<java.util.List<CloudField.Puff>> puffsOf = new java.util.ArrayList<>();
        double minX = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        double[][] box = new double[storms.size()][];
        for (int i = 0; i < storms.size(); i++) {
            java.util.List<CloudField.Puff> pp = CloudField.puffs(storms.get(i), peakLevel());
            puffsOf.add(pp);
            double bx0 = Double.MAX_VALUE;
            double bz0 = Double.MAX_VALUE;
            double bx1 = -Double.MAX_VALUE;
            double bz1 = -Double.MAX_VALUE;
            double top = CloudField.LOW_BASE;
            for (CloudField.Puff p : pp) {
                double r = p.r() * 1.2;
                bx0 = Math.min(bx0, p.x() - r);
                bx1 = Math.max(bx1, p.x() + r);
                bz0 = Math.min(bz0, p.z() - r);
                bz1 = Math.max(bz1, p.z() + r);
                top = Math.max(top, p.y() + p.r() * p.squash() * 1.2);
            }
            box[i] = new double[]{bx0, bz0, bx1, bz1, top};
            minX = Math.min(minX, bx0);
            minZ = Math.min(minZ, bz0);
            maxX = Math.max(maxX, bx1);
            maxZ = Math.max(maxZ, bz1);
        }
        double cx = w.centerX * (double) CloudField.CELL_XZ;
        double cz = w.centerZ * (double) CloudField.CELL_XZ;
        int quads = 0;
        for (int tz = Mth.floor(minZ / TILE); tz <= Mth.floor(maxZ / TILE); tz++) {
            for (int tx = Mth.floor(minX / TILE); tx <= Mth.floor(maxX / TILE); tx++) {
                double tx0 = tx * (double) TILE;
                double tz0 = tz * (double) TILE;
                java.util.List<CloudField.Puff> here = new java.util.ArrayList<>();
                boolean giant = false;
                boolean nest = false;
                double top = CloudField.LOW_BASE;
                for (int i = 0; i < storms.size(); i++) {
                    double[] bb = box[i];
                    if (bb[2] < tx0 || bb[0] > tx0 + TILE || bb[3] < tz0 || bb[1] > tz0 + TILE) {
                        continue;
                    }
                    here.addAll(puffsOf.get(i));
                    giant |= storms.get(i).giant();
                    nest |= CloudField.nestStorm(storms.get(i));
                    top = Math.max(top, bb[4]);
                }
                if (here.isEmpty()) {
                    continue;
                }
                double ddx = Math.max(0.0, Math.max(tx0 - cx, cx - (tx0 + TILE)));
                double ddz = Math.max(0.0, Math.max(tz0 - cz, cz - (tz0 + TILE)));
                // 遠くのタイルを粗くするのはやめた(粗さの違うタイルの境で面が食い違い、すき間と段の線が出た)。
                // 嵐の巣(11-18): 壁の塔も巨大な雲と同じ粗さに(目の中で 14 本の塔が細かい箱になり、51 FPS まで落ちた)。
                int v = giant || nest ? Math.max(GIANT_VOXEL, voxel) : voxel;
                quads += tileMesh(b, here, tx * TILE / v, tz * TILE / v, TILE / v, (int) Math.ceil((top - CloudField.LOW_BASE) / v) + 1, v,
                        nest ? NEST_TAG : (giant ? GIANT_TAG : STORM_TAG), w);
            }
        }
        return quads;
    }

    /**
     * タイル 1 枚: 箱 (x0..x0+n, 0..ny, z0..z0+n)(箱の単位)を雲の塊で詰め、外から見える面を足す。塊ごとに、その楕円体の範囲の箱だけを確かめる。
     * 陰は箱ごとに CPU で焼く(色の g = 明るさ): 高い所ほど明るく、真上に雲がかぶさっている所(こぶの下・張り出しの下)ほど暗い。
     */
    private static int tileMesh(BufferBuilder b, java.util.List<CloudField.Puff> puffs, int x0, int z0, int n, int ny, int v, float tag, Window w) {
        int nx = n;
        int nz = n;
        java.util.BitSet solid = new java.util.BitSet(nx * ny * nz);
        java.util.List<CloudField.Puff> one = new java.util.ArrayList<>(1);
        one.add(null);
        for (CloudField.Puff p : puffs) {
            one.set(0, p);
            double r = p.r() * 1.2;
            double ry = p.r() * p.squash() * 1.2;
            int ix0 = Math.max(0, Mth.floor((p.x() - r) / v) - x0);
            int ix1 = Math.min(nx - 1, Mth.floor((p.x() + r) / v) - x0);
            int iz0 = Math.max(0, Mth.floor((p.z() - r) / v) - z0);
            int iz1 = Math.min(nz - 1, Mth.floor((p.z() + r) / v) - z0);
            int iy0 = Math.max(0, Mth.floor((Math.max(p.y() - ry, p.floor()) - CloudField.LOW_BASE) / v));
            int iy1 = Math.min(ny - 1, Mth.floor((p.y() + ry - CloudField.LOW_BASE) / v));
            if (ix0 > ix1 || iz0 > iz1 || iy0 > iy1) {
                continue;
            }
            // 11-09 段階 2a-2 軽量化: 塊の芯(正規化した距離² 0.81 未満 = CloudField.inside が雑音なしで中と答える所)はそのまま詰め、
            // 縁の殻(0.81〜1.21)だけ inside で確かめる。外(1.21 以上)は確かめない。答えは前と同じ。
            double pr = p.r();
            double pry = p.r() * p.squash();
            for (int iy = iy0; iy <= iy1; iy++) {
                double y = CloudField.LOW_BASE + (iy + 0.5) * v;
                if (y < p.floor()) {
                    continue;
                }
                double dy = (y - p.y()) / pry;
                for (int iz = iz0; iz <= iz1; iz++) {
                    double z = (z0 + iz + 0.5) * v;
                    double dz = (z - p.z()) / pr;
                    double rest = dy * dy + dz * dz;
                    if (rest >= 1.21) {
                        continue;
                    }
                    for (int ix = ix0; ix <= ix1; ix++) {
                        int idx = (iy * nz + iz) * nx + ix;
                        if (solid.get(idx)) {
                            continue;
                        }
                        double x = (x0 + ix + 0.5) * v;
                        double dx = (x - p.x()) / pr;
                        double d2 = rest + dx * dx;
                        if (d2 < 0.81) {
                            solid.set(idx);
                        } else if (d2 < 1.21 && CloudField.inside(one, x, y, z)) {
                            solid.set(idx);
                        }
                    }
                }
            }
        }
        float height = ny * (float) v;
        int quads = 0;
        Occ occ = new Occ(solid, puffs, nx, ny, nz, x0, z0, v);
        for (int iy = 0; iy < ny; iy++) {
            for (int iz = 0; iz < nz; iz++) {
                for (int ix = 0; ix < nx; ix++) {
                    if (!solid.get((iy * nz + iz) * nx + ix)) {
                        continue;
                    }
                    boolean down = filledT(solid, puffs, nx, ny, nz, x0, z0, v, ix, iy - 1, iz);
                    boolean up = filledT(solid, puffs, nx, ny, nz, x0, z0, v, ix, iy + 1, iz);
                    boolean north = filledT(solid, puffs, nx, ny, nz, x0, z0, v, ix, iy, iz - 1);
                    boolean south = filledT(solid, puffs, nx, ny, nz, x0, z0, v, ix, iy, iz + 1);
                    boolean west = filledT(solid, puffs, nx, ny, nz, x0, z0, v, ix - 1, iy, iz);
                    boolean east = filledT(solid, puffs, nx, ny, nz, x0, z0, v, ix + 1, iy, iz);
                    if (down && up && north && south && west && east) {
                        continue;
                    }
                    // 真上にかぶさる雲の割合(5 × 5 × 3 の広めの範囲でならす。タイルの外は数えない)。
                    int over = 0;
                    for (int dy = 1; dy <= 3; dy++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            for (int dx = -2; dx <= 2; dx++) {
                                if (filled(solid, nx, ny, nz, ix + dx, iy + dy, iz + dz)) {
                                    over++;
                                }
                            }
                        }
                    }
                    float hf = Mth.clamp((iy + 0.5F) * v / Math.max(400.0F, height), 0.0F, 1.0F);
                    // 10-17 夜更け(ユーザー「積乱雲が黒すぎて周りの雲から浮く」): 下でも 0.5、かぶさりの暗さも 0.3 までに(雲の海と同じくらいの明るさ)。
                    float light = Mth.clamp((0.5F + 0.5F * (float) Math.pow(hf, 0.6)) * (1.0F - 0.3F * over / 75.0F), 0.0F, 1.0F);
                    // 角はどれも箱の番号から同じ式で出す(隣の面と少しでもずれると、平らな所にすき間の線が格子に見えた。10-17 ユーザー指摘「謎のグリッド」)。
                    float px0 = edge(x0 + ix, v, w.originX);
                    float pz0 = edge(z0 + iz, v, w.originZ);
                    float py0 = iy * (float) v;
                    float px1 = edge(x0 + ix + 1, v, w.originX);
                    float py1 = (iy + 1) * (float) v;
                    float pz1 = edge(z0 + iz + 1, v, w.originZ);
                    if (!down) {
                        // 一番下の段の底は少し上げる(11-12): 台の下に残した薄い床の底(高さ 0)と同じ面に重なってちらつかないように。
                        // 床のある所は床の底が、無い所はこの底が見える。雨の前触れでも一緒に下がる高さ(voxel_clouds.vsh)。
                        float pyD = iy == 0 ? STORM_BASE_LIFT : py0;
                        quad(b, 0, 0.2F, tag, occ.edges(ix, iy, iz, 0, ix, iy, iz, ix + 1, iy, iz, ix + 1, iy, iz + 1, ix, iy, iz + 1),
                                px0, pyD, pz0, light, px1, pyD, pz0, light, px1, pyD, pz1, light, px0, pyD, pz1, light);
                        quads++;
                    }
                    if (!up) {
                        quad(b, 1, 0.2F, tag, occ.edges(ix, iy, iz, 1, ix, iy + 1, iz + 1, ix + 1, iy + 1, iz + 1, ix + 1, iy + 1, iz, ix, iy + 1, iz),
                                px0, py1, pz1, light, px1, py1, pz1, light, px1, py1, pz0, light, px0, py1, pz0, light);
                        quads++;
                    }
                    if (!north) {
                        quad(b, 2, 0.2F, tag, occ.edges(ix, iy, iz, 2, ix + 1, iy, iz, ix, iy, iz, ix, iy + 1, iz, ix + 1, iy + 1, iz),
                                px1, py0, pz0, light, px0, py0, pz0, light, px0, py1, pz0, light, px1, py1, pz0, light);
                        quads++;
                    }
                    if (!south) {
                        quad(b, 3, 0.2F, tag, occ.edges(ix, iy, iz, 3, ix, iy, iz + 1, ix + 1, iy, iz + 1, ix + 1, iy + 1, iz + 1, ix, iy + 1, iz + 1),
                                px0, py0, pz1, light, px1, py0, pz1, light, px1, py1, pz1, light, px0, py1, pz1, light);
                        quads++;
                    }
                    if (!west) {
                        quad(b, 4, 0.2F, tag, occ.edges(ix, iy, iz, 4, ix, iy, iz, ix, iy, iz + 1, ix, iy + 1, iz + 1, ix, iy + 1, iz),
                                px0, py0, pz0, light, px0, py0, pz1, light, px0, py1, pz1, light, px0, py1, pz0, light);
                        quads++;
                    }
                    if (!east) {
                        quad(b, 5, 0.2F, tag, occ.edges(ix, iy, iz, 5, ix + 1, iy, iz + 1, ix + 1, iy, iz, ix + 1, iy + 1, iz, ix + 1, iy + 1, iz + 1),
                                px1, py0, pz1, light, px1, py0, pz0, light, px1, py1, pz0, light, px1, py1, pz1, light);
                        quads++;
                    }
                }
            }
        }
        return quads;
    }

    /** 箱が詰まっているか(タイルの中はビット、横の外は塊で確かめる。上下の外は空)。 */
    private static boolean filledT(java.util.BitSet solid, java.util.List<CloudField.Puff> puffs, int nx, int ny, int nz, int x0, int z0, int v,
                                   int ix, int iy, int iz) {
        if (iy < 0 || iy >= ny) {
            return false;
        }
        if (ix >= 0 && iz >= 0 && ix < nx && iz < nz) {
            return solid.get((iy * nz + iz) * nx + ix);
        }
        return CloudField.inside(puffs, (x0 + ix + 0.5) * v, CloudField.LOW_BASE + (iy + 0.5) * v, (z0 + iz + 0.5) * v);
    }

    /** 遠景の積乱雲(stormTiles と同じタイル分け。箱の大きさはタイルの近い縁と穴の真ん中の距離で 32・64・128)。 */
    private static int farStormTiles(BufferBuilder b, java.util.List<CloudField.Storm> storms, Window w) {
        if (storms.isEmpty()) {
            return 0;
        }
        java.util.List<java.util.List<CloudField.Puff>> puffsOf = new java.util.ArrayList<>();
        double minX = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        double[][] box = new double[storms.size()][];
        for (int i = 0; i < storms.size(); i++) {
            java.util.List<CloudField.Puff> pp = CloudField.puffs(storms.get(i), Math.max(peakLevel(), 1)); // 遠景は高の段でも峰を半分(11-09 段階 2a-5 ユーザー決定)
            puffsOf.add(pp);
            double bx0 = Double.MAX_VALUE;
            double bz0 = Double.MAX_VALUE;
            double bx1 = -Double.MAX_VALUE;
            double bz1 = -Double.MAX_VALUE;
            double top = CloudField.LOW_BASE;
            for (CloudField.Puff p : pp) {
                double r = p.r() * 1.2;
                bx0 = Math.min(bx0, p.x() - r);
                bx1 = Math.max(bx1, p.x() + r);
                bz0 = Math.min(bz0, p.z() - r);
                bz1 = Math.max(bz1, p.z() + r);
                top = Math.max(top, p.y() + p.r() * p.squash() * 1.2);
            }
            box[i] = new double[]{bx0, bz0, bx1, bz1, top};
            minX = Math.min(minX, bx0);
            minZ = Math.min(minZ, bz0);
            maxX = Math.max(maxX, bx1);
            maxZ = Math.max(maxZ, bz1);
        }
        int quads = 0;
        for (int tz = Mth.floor(minZ / TILE); tz <= Mth.floor(maxZ / TILE); tz++) {
            for (int tx = Mth.floor(minX / TILE); tx <= Mth.floor(maxX / TILE); tx++) {
                double tx0 = tx * (double) TILE;
                double tz0 = tz * (double) TILE;
                java.util.List<CloudField.Puff> here = new java.util.ArrayList<>();
                boolean giant = false;
                boolean nest = false;
                double top = CloudField.LOW_BASE;
                for (int i = 0; i < storms.size(); i++) {
                    double[] bb = box[i];
                    if (bb[2] < tx0 || bb[0] > tx0 + TILE || bb[3] < tz0 || bb[1] > tz0 + TILE) {
                        continue;
                    }
                    here.addAll(puffsOf.get(i));
                    giant |= storms.get(i).giant();
                    nest |= CloudField.nestStorm(storms.get(i));
                    top = Math.max(top, bb[4]);
                }
                if (here.isEmpty()) {
                    continue;
                }
                double ddx = Math.max(0.0, Math.max(tx0 - w.holeX, w.holeX - (tx0 + TILE)));
                double ddz = Math.max(0.0, Math.max(tz0 - w.holeZ, w.holeZ - (tz0 + TILE)));
                double near = Math.hypot(ddx, ddz);
                int v = near < 2200.0 ? 32 : near < 4400.0 ? 64 : near < 8000.0 ? 128 : 256;
                if (giant || nest) {
                    // 巨大な雲(10-18 から胴の半径 1300〜1700)は近くでも 48 より細かくしない(面が多すぎる)。嵐の巣も同じ(11-18)。
                    v = Math.max(v, GIANT_VOXEL);
                }
                quads += tileMesh(b, here, tx * TILE / v, tz * TILE / v, TILE / v, (int) Math.ceil((top - CloudField.LOW_BASE) / v) + 1, v,
                        nest ? FAR_NEST_TAG : FAR_STORM_TAG, w);
            }
        }
        return quads;
    }

    /** 箱の番号 index(大きさ v)の角の、窓の原点からの位置(倍精度で出してから float に。同じ角はいつも同じ値になる)。 */
    private static float edge(int index, int v, int windowOrigin) {
        return (float) ((double) index * v - (double) windowOrigin * CloudField.CELL_XZ);
    }

    private static boolean filled(java.util.BitSet solid, int nx, int ny, int nz, int ix, int iy, int iz) {
        return ix >= 0 && iy >= 0 && iz >= 0 && ix < nx && iy < ny && iz < nz && solid.get((iy * nz + iz) * nx + ix);
    }

    /** 積乱雲のタイルの箱の詰まり(タイルの中はビット、外の 1 箱は塊で確かめて覚える)と、出っ張った辺の印。 */
    private static final class Occ {
        final java.util.BitSet solid;
        final java.util.List<CloudField.Puff> puffs;
        final int nx;
        final int ny;
        final int nz;
        final int x0;
        final int z0;
        final int v;
        /** タイルのまわり 1 箱を含めた詰まりの覚え(0 まだ・1 空・2 詰まり)。 */
        final byte[] memo;

        Occ(java.util.BitSet solid, java.util.List<CloudField.Puff> puffs, int nx, int ny, int nz, int x0, int z0, int v) {
            this.solid = solid;
            this.puffs = puffs;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.x0 = x0;
            this.z0 = z0;
            this.v = v;
            this.memo = new byte[(nx + 2) * ny * (nz + 2)];
        }

        boolean filled(int ix, int iy, int iz) {
            if (iy < 0 || iy >= ny) {
                return false;
            }
            if (ix >= 0 && iz >= 0 && ix < nx && iz < nz) {
                return solid.get((iy * nz + iz) * nx + ix);
            }
            if (ix < -1 || iz < -1 || ix > nx || iz > nz) {
                return false;
            }
            int k = (iy * (nz + 2) + iz + 1) * (nx + 2) + ix + 1;
            if (memo[k] == 0) {
                memo[k] = (byte) (CloudField.inside(puffs, (x0 + ix + 0.5) * v, CloudField.LOW_BASE + (iy + 0.5) * v, (z0 + iz + 0.5) * v) ? 2 : 1);
            }
            return memo[k] == 2;
        }

        /**
         * 箱 (ix, iy, iz) の面 face の 4 つの角(角の番号 a・b・c・d、頂点の順)から、出っ張った辺の印を出す: 辺の真ん中から箱の真ん中への向き(面の向きの成分を除く)の
         * 隣の箱と、その面の外側の斜めの箱がどちらも空いていれば出っ張った辺。
         */
        int edges(int ix, int iy, int iz, int face, int ax, int ay, int az, int bx, int by, int bz, int cx, int cy, int cz, int dx, int dy, int dz) {
            int nx0 = face == 4 ? -1 : face == 5 ? 1 : 0;
            int ny0 = face == 0 ? -1 : face == 1 ? 1 : 0;
            int nz0 = face == 2 ? -1 : face == 3 ? 1 : 0;
            int[] c = {ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz};
            int bits = 0;
            for (int e = 0; e < 4; e++) {
                int p = e * 3;
                int q = ((e + 1) % 4) * 3;
                // 辺の真ん中(2 倍の座標)と箱の真ん中(2 倍)の差の向き。
                int ex = Integer.signum(c[p] + c[q] - (2 * ix + 1));
                int ey = Integer.signum(c[p + 1] + c[q + 1] - (2 * iy + 1));
                int ez = Integer.signum(c[p + 2] + c[q + 2] - (2 * iz + 1));
                if (nx0 != 0) {
                    ex = 0;
                }
                if (ny0 != 0) {
                    ey = 0;
                }
                if (nz0 != 0) {
                    ez = 0;
                }
                if (!filled(ix + ex, iy + ey, iz + ez) && !filled(ix + ex + nx0, iy + ey + ny0, iz + ez + nz0)) {
                    bits |= 1 << e;
                }
            }
            return bits;
        }
    }


    // ---------------------------------------------------------------- 形の補助(元の版のまま)

    /**
     * 1 段の柱(n × m の底・天辺の配列、(offX, offZ) から並ぶ)の外から見える面を足す。横の面は同じ段の隣の柱が覆っていない高さだけ。
     * tag = 頂点の色の a(下の層か積乱雲か)。
     */
    private static int layer(BufferBuilder b, int[] bottom, int[] top, int n, int m, float offX, float offZ, float tag, int s) {
        return layer(b, bottom, top, n, m, offX, offZ, tag, s, true, null);
    }

    /*
     * 積乱雲に食い込んだ床の柱(2026-11-23、ユーザー「雷雲の中に白い雲が混ざってるのは何」→「積乱雲の色に寄せる」):
     * 積乱雲・嵐の巣のまわりは床の柱が盛り上がり、その角が積乱雲の箱の外へ少しはみ出して、暗い雷雲の中に白い欠片として見えた。
     * 柱の天辺のすぐ下が積乱雲の塊の中にある柱は、その積乱雲の印(色の a)で塗る(シェーダーが積乱雲として陰を付ける)。
     * 台の下に残す薄い床(天辺が CELL_Y 以下。11-12)は床のまま。
     */
    /** 柱ごとの印(積乱雲に食い込んだ柱だけ積乱雲の印、ほかは NaN = 床の印)。積乱雲が無ければ null。gx0・gz0 = 柱の番号の原点(場の座標 / size)。 */
    private static float[] stormColumnTags(int[] bottom, int[] top, int n, int gx0, int gz0, int size, List<CloudField.Storm> storms,
                                           float stormTag, float nestTag, float giantTag) {
        if (storms.isEmpty()) {
            return null;
        }
        float[] tags = null;
        for (CloudField.Storm s : storms) {
            List<CloudField.Puff> pp = CloudField.puffs(s, peakLevel());
            if (pp.isEmpty()) {
                continue;
            }
            double bx0 = Double.MAX_VALUE;
            double bz0 = Double.MAX_VALUE;
            double bx1 = -Double.MAX_VALUE;
            double bz1 = -Double.MAX_VALUE;
            for (CloudField.Puff p : pp) {
                double r = p.r() * 1.2;
                bx0 = Math.min(bx0, p.x() - r);
                bx1 = Math.max(bx1, p.x() + r);
                bz0 = Math.min(bz0, p.z() - r);
                bz1 = Math.max(bz1, p.z() + r);
            }
            float t = CloudField.nestStorm(s) ? nestTag : (s.giant() ? giantTag : stormTag);
            int i0 = Math.max(0, Mth.floor(bx0 / size) - gx0);
            int i1 = Math.min(n - 1, Mth.floor(bx1 / size) - gx0);
            int j0 = Math.max(0, Mth.floor(bz0 / size) - gz0);
            int j1 = Math.min(n - 1, Mth.floor(bz1 / size) - gz0);
            for (int j = j0; j <= j1; j++) {
                for (int i = i0; i <= i1; i++) {
                    int k = j * n + i;
                    if (top[k] <= CloudField.CELL_Y || bottom[k] >= top[k] || (tags != null && !Float.isNaN(tags[k]))) {
                        continue;
                    }
                    double x = (gx0 + i + 0.5) * size;
                    double z = (gz0 + j + 0.5) * size;
                    if (CloudField.inside(pp, x, CloudField.LOW_BASE + top[k] - 2.0, z)) {
                        if (tags == null) {
                            tags = new float[n * n];
                            java.util.Arrays.fill(tags, Float.NaN);
                        }
                        tags[k] = t;
                    }
                }
            }
        }
        return tags;
    }

    /**
     * bottoms = false なら下面を作らない(遠景はカメラが雲より上にあるときだけ描くので、雲の海の下面は見えない)。
     * tags = 柱ごとの印({@link #stormColumnTags}。null か NaN の柱は tag)。
     */
    private static int layer(BufferBuilder b, int[] bottom, int[] top, int n, int m, float offX, float offZ, float baseTag, int s, boolean bottoms,
                             float[] tags) {
        int quads = 0;
        float layer = CloudField.LOW_TOP - CloudField.LOW_BASE;
        for (int j = 0; j < m; j++) {
            for (int i = 0; i < n; i++) {
                int k = j * n + i;
                int y0 = bottom[k];
                int y1 = top[k];
                if (y0 >= y1) {
                    continue;
                }
                float tag = tags != null && !Float.isNaN(tags[k]) ? tags[k] : baseTag;
                // 角は番号から同じ式で(隣の柱と少しでもずれると、平らな雲の海にすき間の線が格子に見えた)。
                float x0 = (float) (offX + (double) i * s);
                float x1 = (float) (offX + (double) (i + 1) * s);
                float z0 = (float) (offZ + (double) j * s);
                float z1 = (float) (offZ + (double) (j + 1) * s);
                int open = 0;
                for (int d = 0; d < 4; d++) {
                    int ni = i + (d == 2 ? -1 : d == 3 ? 1 : 0);
                    int nj = j + (d == 0 ? -1 : d == 1 ? 1 : 0);
                    if (ni < 0 || nj < 0 || ni >= n || nj >= m || bottom[nj * n + ni] >= top[nj * n + ni]) {
                        open++;
                    }
                }
                float thin = Math.max(1.0F - Mth.clamp((y1 - y0) / 24.0F, 0.0F, 1.0F), open * 0.15F);
                boolean asStorm = tag != baseTag;
                float h0 = shadeAt(y0, layer, asStorm);
                float h1 = shadeAt(y1, layer, asStorm);
                // 下面(辺: 北・東・南・西)・上面(辺: 南・東・北・西)。出っ張った辺 = 隣の柱がその高さのすぐ上下とも空いている。
                int downBits = (colEmpty2(bottom, top, n, m, i, j - 1, y0 - 1, y0) ? 1 : 0) | (colEmpty2(bottom, top, n, m, i + 1, j, y0 - 1, y0) ? 2 : 0)
                        | (colEmpty2(bottom, top, n, m, i, j + 1, y0 - 1, y0) ? 4 : 0) | (colEmpty2(bottom, top, n, m, i - 1, j, y0 - 1, y0) ? 8 : 0);
                int upBits = (colEmpty2(bottom, top, n, m, i, j + 1, y1 - 1, y1) ? 1 : 0) | (colEmpty2(bottom, top, n, m, i + 1, j, y1 - 1, y1) ? 2 : 0)
                        | (colEmpty2(bottom, top, n, m, i, j - 1, y1 - 1, y1) ? 4 : 0) | (colEmpty2(bottom, top, n, m, i - 1, j, y1 - 1, y1) ? 8 : 0);
                if (bottoms) {
                    quad(b, 0, thin, tag, downBits, x0, y0, z0, h0, x1, y0, z0, h0, x1, y0, z1, h0, x0, y0, z1, h0);
                    quads++;
                }
                quad(b, 1, thin, tag, upBits, x0, y1, z1, h1, x1, y1, z1, h1, x1, y1, z0, h1, x0, y1, z0, h1);
                quads++;
                // 横の面: 隣の柱が覆っていない高さだけ(北 −z・南 +z・西 −x・東 +x)。
                for (int d = 0; d < 4; d++) {
                    int ni = i + (d == 2 ? -1 : d == 3 ? 1 : 0);
                    int nj = j + (d == 0 ? -1 : d == 1 ? 1 : 0);
                    int nb = 1;
                    int nt = 0;
                    if (ni >= 0 && nj >= 0 && ni < n && nj < m) {
                        nb = bottom[nj * n + ni];
                        nt = top[nj * n + ni];
                    }
                    if (nb >= nt) {
                        quads += side(b, d, thin, tag, asStorm, layer, x0, x1, z0, z1, y0, y1, bottom, top, n, m, i, j);
                    } else {
                        if (y0 < nb) {
                            quads += side(b, d, thin, tag, asStorm, layer, x0, x1, z0, z1, y0, Math.min(y1, nb), bottom, top, n, m, i, j);
                        }
                        if (y1 > nt) {
                            quads += side(b, d, thin, tag, asStorm, layer, x0, x1, z0, z1, Math.max(y0, nt), y1, bottom, top, n, m, i, j);
                        }
                    }
                }
            }
        }
        return quads;
    }

    /**
     * 柱の横の面 1 枚(d: 0 北・1 南・2 西・3 東、高さ ya〜yb)。辺の順は 下・(頂点の順で)横・上・横。出っ張った辺: 下は自分の柱と向かいの柱が ya のすぐ下で空、
     * 上は yb のすぐ上で空、横はその向きの隣の柱と斜めの柱が真ん中の高さで空。
     */
    private static int side(BufferBuilder b, int d, float thin, float tag, boolean asStorm, float layer, float x0, float x1, float z0, float z1, int ya, int yb,
                            int[] bottom, int[] top, int n, int m, int i, int j) {
        if (yb <= ya) {
            return 0;
        }
        float ha = shadeAt(ya, layer, asStorm);
        float hb = shadeAt(yb, layer, asStorm);
        int fi = d == 2 ? -1 : d == 3 ? 1 : 0;
        int fj = d == 0 ? -1 : d == 1 ? 1 : 0;
        int mid = (ya + yb) / 2;
        boolean downOpen = !colFilled(bottom, top, n, m, i, j, ya - 1) && !colFilled(bottom, top, n, m, i + fi, j + fj, ya - 1);
        boolean upOpen = !colFilled(bottom, top, n, m, i, j, yb) && !colFilled(bottom, top, n, m, i + fi, j + fj, yb);
        // 頂点の順で 2 番目の辺(b→c)と 4 番目の辺(d→a)の向きの隣。
        int si = d == 0 ? -1 : d == 1 ? 1 : 0;
        int sj = d == 2 ? 1 : d == 3 ? -1 : 0;
        boolean sideB = !colFilled(bottom, top, n, m, i + si, j + sj, mid) && !colFilled(bottom, top, n, m, i + si + fi, j + sj + fj, mid);
        boolean sideD = !colFilled(bottom, top, n, m, i - si, j - sj, mid) && !colFilled(bottom, top, n, m, i - si + fi, j - sj + fj, mid);
        int bits = (downOpen ? 1 : 0) | (sideB ? 2 : 0) | (upOpen ? 4 : 0) | (sideD ? 8 : 0);
        switch (d) {
            case 0 -> quad(b, 2, thin, tag, bits, x1, ya, z0, ha, x0, ya, z0, ha, x0, yb, z0, hb, x1, yb, z0, hb);
            case 1 -> quad(b, 3, thin, tag, bits, x0, ya, z1, ha, x1, ya, z1, ha, x1, yb, z1, hb, x0, yb, z1, hb);
            case 2 -> quad(b, 4, thin, tag, bits, x0, ya, z0, ha, x0, ya, z1, ha, x0, yb, z1, hb, x0, yb, z0, hb);
            default -> quad(b, 5, thin, tag, bits, x1, ya, z1, ha, x1, ya, z0, ha, x1, yb, z0, hb, x1, yb, z1, hb);
        }
        return 1;
    }

    /**
     * 頂点の色の g。床 = 層の中の高さ。積乱雲に食い込んだ柱(11-23)= 積乱雲の箱と同じ焼いた明るさ({@link #tileMesh} の式。真上のかぶさりは
     * 測らず、雲の台の近くのふつうの値 0.85 を掛ける)。床の高さのままだと積乱雲の色の中で明るさが食い違い、細い明るい線に見えた。
     */
    private static float shadeAt(int y, float layer, boolean asStorm) {
        if (!asStorm) {
            return y / layer;
        }
        float hf = Mth.clamp(y / 400.0F, 0.0F, 1.0F);
        return (0.5F + 0.5F * (float) Math.pow(hf, 0.6)) * 0.85F;
    }

    /** 柱 (i, j) が高さ y(その 1 ブロック)を埋めているか(格子の外は空)。 */
    private static boolean colFilled(int[] bottom, int[] top, int n, int m, int i, int j, int y) {
        if (i < 0 || j < 0 || i >= n || j >= m) {
            return false;
        }
        int k = j * n + i;
        return bottom[k] <= y && y < top[k];
    }

    private static boolean colEmpty2(int[] bottom, int[] top, int n, int m, int i, int j, int ya, int yb) {
        return !colFilled(bottom, top, n, m, i, j, ya) && !colFilled(bottom, top, n, m, i, j, yb);
    }

    /**
     * 四角 1 枚。色の r = (面の向き(0 下・1 上・2 北・3 南・4 西・5 東)× 16 + 出っ張った辺の印)/ 255、g = 層の中の高さ(積乱雲は焼いた明るさ)、
     * b = 薄さ、a = 下の層か積乱雲か。UV = 面の中の位置(頂点の順に (0,0)・(1,0)・(1,1)・(0,1))。
     * 出っ張った辺の印(10-17 深夜、輪郭のやわらかさ): 頂点の順の辺 a→b・b→c・c→d・d→a のうち、その向きの隣の箱(と面の外側の斜めの箱)が空いている辺 = 1・2・4・8。
     * シェーダーがその辺の近くを丸く透かして、箱の輪郭をやわらかく見せる。最初は角ごとの開きを頂点に持たせて補間したが、四角を割った三角の向きに
     * 斜めのにじみが出た(面ごとに同じ値と面の中の位置なら、三角の割り方に左右されない)。
     */
    private static void quad(BufferBuilder b, int face, float thin, float tag, int bits,
                             float ax, float ay, float az, float ah, float bx, float by, float bz, float bh,
                             float cx, float cy, float cz, float ch, float dx, float dy, float dz, float dh) {
        // 半目盛り足す(色は float から 8 ビットへ切り捨てで写るので、ちょうどの値だと 1 つ下になり、向きと印が崩れた)。
        float r = (face * 16 + (bits & 15) + 0.5F) / 255.0F;
        b.addVertex(ax, ay, az).setUv(0.0F, 0.0F).setColor(r, ah, thin, tag);
        b.addVertex(bx, by, bz).setUv(1.0F, 0.0F).setColor(r, bh, thin, tag);
        b.addVertex(cx, cy, cz).setUv(1.0F, 1.0F).setColor(r, ch, thin, tag);
        b.addVertex(dx, dy, dz).setUv(0.0F, 1.0F).setColor(r, dh, thin, tag);
    }

}
