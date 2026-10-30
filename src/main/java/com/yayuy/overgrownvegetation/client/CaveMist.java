package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * 地下の霧(2026-10-16 ユーザー「4 の案(地下の霧と深さの色)のような、環境を美しく見せる感じが良い」。案は storyline の
 * ideas.atmosphere「密林の地下と光の加減をもっと神秘的・不安にする案」の (4))。
 *
 * <p>汚染地域の地下の<b>大きな洞窟</b>で、床の低い所に膝ほどの高さの薄い霧が溜まる。朝霧の雲の板({@link MorningMistView})と同じ作りで、
 * カメラのまわり 128 ブロック四方の「カメラのいる洞窟の床の高さ」の地図({@link CaveMistMap}。2 ブロックで 1 マス)から、窓の床の真ん中の高さ +
 * 設定 {@code caveMistHeight}(既定 {@link CaveMistMap#DEPTH})を霧のてっぺんにし、そこから床までに {@link #SHEETS} 枚のまだらな板を重ねる。まわりより低いくぼみ・穴は
 * 霧で満ちて濃く、岩棚・盛り上がりの上には出ない(最初は朝霧と同じ「床の低い方から 3 割」にしたが、穴のまわりのてっぺんが穴の底まで下がり、
 * 穴の底に薄く張るだけになった)。床の探し方・どこに立っても同じ形になる理由は {@link CaveMistMap}。
 * <ul>
 *     <li><b>大きな洞窟だけ</b>: まわり 18 ブロック四方のうち床のある所が 35% に満たないマス(細い坑道)には出さない。
 *     壁の近く・地図の縁は 2〜3 マスかけて薄くする(板が壁に刺さる線を見せない)。</li>
 *     <li><b>照らされた霧</b>: 床の 1 段上のブロックの光(光る植物・地脈・松明)と空の光(天井の穴・洞窟の口)を板の頂点に覚え、
 *     そこだけ霧を明るく・少し濃く描く。光が変わっても(松明を置いた)次に地図を作り直すまで({@link #REBUILD_TICKS} tick ごと)は前のまま。</li>
 *     <li><b>暗がり</b>: 光の届かない所の霧は、深さで色の変わるかすかな光({@link CaveLight#depthWeights}: 浅い = 緑・中 = 青緑・
 *     深い = 出自の「発光する岩」の淡い水色)。霧の中の胞子がかすかに光っている、という見た目。</li>
 *     <li><b>霧の中は霞で</b>(2026-10-16 ユーザー指摘「下に降りると霧の断面図が見えてしまう」): 板は霧の中・すぐ上からだと真横に見え、
 *     切り口がガラスの帯のように見えた。板は、板のその点よりカメラが {@link #SHEETS_FROM_ABOVE}〜{@link #SHEETS_FULL_ABOVE} ブロック以上上にいる所だけ描き(点ごと)、
 *     霧の中の見え方は地形と水のシェーダーの霞(ov_cave_mist.glsl。朝霧の霞と同じ作りで、視線が層の中を通った長さだけ霧の色へ寄せる)が受け持つ。
 *     霞の色も床の 1 段上の光で明るくなる。地形だけに効くので、霧の中の Mob・粒は霞まない。</li>
 *     <li><b>霧の滝</b>(10-16 ユーザー提案): 崖・深い穴のふちから、霧が筋になって流れ落ち、途中で消える({@link #buildFalls})。崖の下の霧は
 *     上の段とつながらない({@link CaveMistMap} の霧のてっぺんは同じ段の床だけで決める)。</li>
 *     <li><b>作り直し</b>: 地図を作り直したら、前の板から新しい板へ {@link #CROSSFADE_TICKS} tick かけて入れ替える(一気に切り替えると、
 *     光の変わった所・地図の縁が跳ねて見える)。</li>
 * </ul>
 * 出すのはカメラが岩の天井の下(頭上の世界の一番上までに岩がある。{@link CaveLight#hasRockRoof})にいて、
 * 汚染地域の中にいるときだけ。板だけで、地形の霞(朝霧の ov_mist)は使わない。見た目だけ(明るさ・湧きは変えない)。
 * 数値・色は全部 Claude の仮決め。設定 {@code caveMist}・{@code caveMistDensity}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CaveMist {

    static final int SIZE = CaveMistMap.SIZE;
    static final int TEXEL = CaveMistMap.TEXEL;
    /** 板の枚数・1 枚の濃さ(くぼみの底。平らな床はその {@link #FLAT_WEIGHT} 倍)・カメラの近くで消す距離。真横から見る板はシェーダーで消す。 */
    static final int SHEETS = 4;
    static final float SHEET_ALPHA = 0.17F;
    /** 品質の段階で霧の板を減らしたときの枚数(2026-10-23)。描くときの濃さに SHEETS / FEW_SHEETS を掛けて全体の濃さを保つ。 */
    static final int FEW_SHEETS = 2;
    static final float NEAR_FROM = 2.0F;
    static final float NEAR_TO = 6.0F;
    /** 平らな床の霧の濃さ(くぼみの底との比)と、くぼみの底で濃さが 1 になる、平らな床より深い分(ブロック)。 */
    static final float FLAT_WEIGHT = 0.6F;
    static final float POOL_EXTRA = 2.5F;
    /** 板(霧の上の面だけの雲の天井)の上端のてっぺんからの深さと厚さ(ブロック)。 */
    static final float DECK_TOP = 0.1F;
    static final float DECK_THICK = 0.5F;
    /** 板を消し始める隣のマスとのてっぺんの差と、消しきるまでの幅(ブロック。1 マス = 2 ブロック)。 */
    static final float STEEP_FROM = 0.5F;
    static final float STEEP_RANGE = 0.7F;
    /** 霧の滝: 立てる段差(ブロック)・霧のてっぺんから消えるまでの長さ(ブロック)・1 枚の濃さ・カメラの近くで消す距離。仮決め。 */
    static final float DROP_MIN = 2.5F;
    static final float FALL_LENGTH = 4.0F;
    /** 霧の滝の上端の、ふち(上の床)からの高さ(ブロック)。 */
    static final float FALL_LIP = 0.35F;
    static final float FALL_ALPHA = 0.3F;
    static final float FALL_NEAR_FROM = 1.0F;
    static final float FALL_NEAR_TO = 3.0F;
    /** 地図を作り直す: カメラが中心から横にこれだけずれたら・この tick ごと。高さは基準の帯を 2 ブロック越えたら。 */
    static final double RECENTER = 10.0;
    static final double RECENTER_Y_MARGIN = 2.0;
    static final long REBUILD_TICKS = 100L;
    /** 前の板から新しい板へ入れ替える時間(tick)。 */
    static final int CROSSFADE_TICKS = 30;
    /** 1 tick に地図作りへ使ってよい時間(ナノ秒)。 */
    static final long BUDGET_NANOS = 1_000_000L;
    /**
     * 板をカメラがずっと上から見るときの濃さの倍率(板のその点より {@link #SHEETS_FULL_ABOVE} 上から、さらに 6 ブロック上で全部。シェーダーで点ごと)。
     * 岩棚の上から見下ろすと板を急な角度で見るので薄く透けてしまう(朝霧と同じく物理では出ない見え方)。
     */
    static final float ABOVE_BOOST = 2.5F;
    /** 暗がりの霧の色(深さの帯ごと)と、その明るさ。 */
    static final float[] SHALLOW_MIST = {0.30F, 0.50F, 0.30F};
    static final float[] MID_MIST = {0.26F, 0.58F, 0.58F};
    static final float[] DEEP_MIST = {0.40F, 0.62F, 0.66F};
    static final float AMBIENT = 0.5F;
    /** ブロックの光・空の光(昼・夜)で照らされた霧に足す色。 */
    static final float[] GLOW = {0.80F, 0.85F, 0.78F};
    static final float[] SKY_DAY = {0.95F, 0.93F, 0.85F};
    static final float[] SKY_NIGHT = {0.10F, 0.13F, 0.20F};
    /**
     * 霧の中の見え方(霞、地形のシェーダー ov_cave_mist.glsl): 1 ブロックあたりの濃さ(立った目から 12 ブロック先の床で約 3 割・30 ブロックで約 6 割)・
     * 層のてっぺんのやわらかい厚さ(ブロック)・霧より上にいるときの倍率(板が足されるので板より小さく)。
     */
    static final float HAZE_DENSITY = 0.05F;
    static final float HAZE_SOFT = 1.2F;
    static final float HAZE_ABOVE_BOOST = 1.5F;
    /**
     * 板を描くのは、板のその点よりカメラがこの高さ以上上にいる所だけ(この高さで 0、{@link #SHEETS_FULL_ABOVE} で全部。シェーダーで点ごとに決める)。
     * 霧の中・すぐ上では板を真横から見ることになり、板の切り口がガラスの帯のように見えた(10-16 ユーザー指摘「下に降りると霧の断面図が見えてしまう」)。中は霞で見せる。
     * 最初はカメラの足元の霧の高さだけで全部の板を出し入れしたので、足元の霧に近づくと遠くの崖を流れ落ちる霧までパッと消えた(10-16 ユーザー指摘)。
     */
    static final float SHEETS_FROM_ABOVE = 1.0F;
    static final float SHEETS_FULL_ABOVE = 2.5F;

    @Nullable
    private static CaveMistMap building;
    @Nullable
    private static CaveMistMap shown;
    @Nullable
    private static LevelReader reader;
    private static ClientLevel mapLevel;
    private static long ticks;
    private static long shownAt;
    private static boolean roofed;
    private static boolean broken;
    /** 霧の出る度合い(0〜1、なめらかに寄せる)。 */
    private static float fade;
    /** カメラの高さ − カメラの所の霧のてっぺん(なめらかに寄せる。分からなければ 0)。 */
    private static float cameraAbove;
    /** 新しい板の割合(0〜1)。1 未満の間は前の板も描く。 */
    private static float blend = 1.0F;

    /** カメラの所の空の光(毎 tick)と、霧の色(深さの帯の暗がりの色、毎 tick)。 */
    private static int cameraSky = 15;
    private static final float[] FOG_RGB = new float[3];
    /** 地下の霧の中でバニラの霧の色(と背景の色)を霧の色へ寄せる割合。仮決め。 */
    static final float CAVE_FOG_BLEND = 0.9F;

    /** 地下の霧が出ている(背景の奈落の板を描かない。{@code LevelRendererCaveSkyMixin})。 */
    public static boolean hideVoid() {
        return fade > 0.05F;
    }

    /** 地下の霧の中で、空の光がまったく届かない閉じた洞窟にいる(空も描かない)。 */
    public static boolean hideSky() {
        return fade > 0.5F && cameraSky == 0;
    }

    /** バニラの霧の色を {@link #fogColor} へ寄せる割合({@link CorruptionAtmosphere} が読む)。 */
    public static float fogColorBlend() {
        return fade * CAVE_FOG_BLEND;
    }

    public static float[] fogColor() {
        return FOG_RGB;
    }

    private static boolean enabled() {
        return OvergrownVegetationConfig.COMMON.caveMist.get() && !SodiumTerrain.unsupported() && !broken;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != mapLevel) {
            building = null;
            shown = null;
            mapLevel = level;
            reader = level == null ? null : new LevelReader(level);
            fade = 0.0F;
        }
        if (level == null || mc.player == null || mc.isPaused()) {
            return;
        }
        ticks++;
        blend = Math.min(1.0F, blend + 1.0F / CROSSFADE_TICKS);
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        cameraSky = level.getBrightness(LightLayer.SKY, BlockPos.containing(eye));
        float[] wt = new float[3];
        CaveLight.depthWeights(eye.y, wt);
        for (int c = 0; c < 3; c++) {
            FOG_RGB[c] = AMBIENT * (wt[0] * SHALLOW_MIST[c] + wt[1] * MID_MIST[c] + wt[2] * DEEP_MIST[c]);
        }
        if (ticks % 10 == 0) {
            roofed = CaveLight.hasRockRoof(level, eye);
        }
        float target = enabled() && roofed ? CorruptionAtmosphere.forestFactor() : 0.0F;
        fade = target > fade ? Math.min(target, fade + 0.03F) : Math.max(target, fade - 0.03F);
        if (target <= 0.0F) {
            building = null;
            if (fade <= 0.0F) {
                shown = null;
            }
            return;
        }
        if (building == null) {
            boolean far = shown == null || Math.abs(eye.x - (shown.originX + SIZE * TEXEL / 2.0)) > RECENTER
                    || Math.abs(eye.z - (shown.originZ + SIZE * TEXEL / 2.0)) > RECENTER
                    || Math.abs(eye.y - (shown.baseY + CaveMistMap.BASE_STEP / 2.0)) > CaveMistMap.BASE_STEP / 2.0 + RECENTER_Y_MARGIN;
            if (far || ticks - shownAt >= REBUILD_TICKS) {
                building = new CaveMistMap(CaveMistMap.originFor(eye.x), CaveMistMap.originFor(eye.z), CaveMistMap.baseFor(eye.y),
                        OvergrownVegetationConfig.COMMON.caveMistHeight.get());
            }
        }
        if (building != null && reader != null && building.step(reader, BUDGET_NANOS, eye.x, eye.y, eye.z)) {
            shown = building;
            building = null;
            shownAt = ticks;
        }
        updateCameraAbove(eye);
        if (DEV_LOG && ticks % 100 == 0 && shown != null) {
            CaveMistMap m = shown;
            int floors = 0;
            int tops = 0;
            for (int k = 0; k < SIZE * SIZE; k++) {
                floors += Float.isNaN(m.floor[k]) ? 0 : 1;
                tops += Float.isNaN(m.top[k]) ? 0 : 1;
            }
            OvergrownVegetation.LOGGER.info("CaveMist 出る度合い={} 床 {}/{} 霧のマス {} 板の四角 {} 滝の四角 {} カメラ y={} てっぺんの上 {}", String.format("%.2f", fade),
                    floors, SIZE * SIZE, tops, quads, fallQuads, String.format("%.1f", eye.y), String.format("%.1f", cameraAbove));
        }
    }

    /** Claude のテストクライアント(-Dov.devbridge=true)だけ、霧の状態を 5 秒ごとにログへ出す。 */
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    /** カメラのいるマス(無ければまわり 2 マスで最も近い霧のマス)のてっぺんからのカメラの高さへ寄せる。 */
    private static void updateCameraAbove(Vec3 eye) {
        CaveMistMap m = shown;
        float target = 0.0F;
        if (m != null) {
            int ci = Math.floorDiv(Mth.floor(eye.x) - m.originX, TEXEL);
            int cj = Math.floorDiv(Mth.floor(eye.z) - m.originZ, TEXEL);
            float best = Float.NaN;
            int bestD = Integer.MAX_VALUE;
            for (int dj = -2; dj <= 2; dj++) {
                for (int di = -2; di <= 2; di++) {
                    int i = ci + di;
                    int j = cj + dj;
                    int d = di * di + dj * dj;
                    if (i >= 0 && j >= 0 && i < SIZE && j < SIZE && d < bestD && !Float.isNaN(m.top[j * SIZE + i])) {
                        best = m.top[j * SIZE + i];
                        bestD = d;
                    }
                }
            }
            if (!Float.isNaN(best)) {
                target = (float) eye.y - best;
            }
        }
        cameraAbove += (target - cameraAbove) * 0.1F;
    }

    /** {@link CaveMistMap} がブロックを読む口(クライアントのワールド)。 */
    private static final class LevelReader implements CaveMistMap.World {
        private final ClientLevel level;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        LevelReader(ClientLevel level) {
            this.level = level;
        }

        @Override
        public int kind(int x, int y, int z) {
            BlockState state = level.getBlockState(pos.set(x, y, z));
            if (!state.getFluidState().isEmpty()) {
                return FLUID;
            }
            if (state.isAir() || !state.blocksMotion()) {
                return OPEN;
            }
            return TreeCanopyGroundFinder.isTreePart(state) ? TREE : SOLID;
        }

        @Override
        public boolean loaded(int x, int z) {
            return level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null;
        }

        @Override
        public int blockLight(int x, int y, int z) {
            return level.getBrightness(LightLayer.BLOCK, pos.set(x, y, z));
        }

        @Override
        public int skyLight(int x, int y, int z) {
            return level.getBrightness(LightLayer.SKY, pos.set(x, y, z));
        }

        @Override
        public int surface(int x, int z) {
            return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        }

        @Override
        public int minY() {
            return level.getMinBuildHeight();
        }
    }

    // ------------------------------------------------------------------ 描く

    @Nullable
    private static VertexBuffer vbo;
    @Nullable
    private static CaveMistMap vboMap;
    /** 板を減らして作ったか(品質の段階の「霧の板」、2026-10-23)。 */
    private static boolean vboFew;
    private static int quads;
    /** 入れ替え中の前の板。 */
    @Nullable
    private static VertexBuffer oldVbo;
    @Nullable
    private static CaveMistMap oldMap;
    private static int oldQuads;

    // ------------------------------------------------------------------ 霞(霧の中の見え方)

    @Nullable
    private static DynamicTexture hazeTexture;
    @Nullable
    private static CaveMistMap hazeMap;
    /** 霞の地図の高さの基準(地図の基準の高さからこれだけ下。てっぺんを 1/4 ブロック刻みで 64 ブロックぶん持つ)。 */
    static final int HAZE_BASE_BELOW = 30;

    /**
     * 空を描いた後・地形を描く前に、霞の地図と濃さ・色を地形と水のシェーダー(ov_cave_mist.glsl)へ渡す。視線が霧の層の中を通った長さだけ
     * 霧の色へ寄せるので、板と違って中に入っても切り口が見えない(10-16 ユーザー指摘「下に降りると霧の断面図が見えてしまう」)。
     */
    @SubscribeEvent
    public static void onRenderSky(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || broken || SodiumTerrain.unsupported()) {
            return;
        }
        ShaderInstance[] shaders = {GameRenderer.getRendertypeSolidShader(), GameRenderer.getRendertypeCutoutShader(),
                GameRenderer.getRendertypeCutoutMippedShader(), GameRenderer.getRendertypeTranslucentShader()};
        try {
            CaveMistMap m = shown;
            ClientLevel level = Minecraft.getInstance().level;
            float density = m == null || level == null ? 0.0F
                    : (float) (HAZE_DENSITY * fade * OvergrownVegetationConfig.COMMON.caveMistDensity.get());
            if (density <= 0.0F || event.getCamera().getFluidInCamera() != FogType.NONE) {
                hazeOff(shaders);
                return;
            }
            if (hazeTexture == null) {
                hazeTexture = new DynamicTexture(SIZE, SIZE, false);
                hazeTexture.setFilter(true, false);
            }
            if (hazeMap != m) {
                if (!writeHaze(m, hazeTexture)) {
                    hazeOff(shaders);
                    return;
                }
                hazeMap = m;
            }
            float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            Vec3 cam = event.getCamera().getPosition();
            float[] wt = new float[3];
            CaveLight.depthWeights(cam.y, wt);
            float day = Mth.clamp((level.getSkyDarken(partialTick) - 0.2F) / 0.8F, 0.0F, 1.0F);
            float seconds = (float) (((level.getGameTime() % 200000L) + partialTick) / 20.0);
            float r = AMBIENT * (wt[0] * SHALLOW_MIST[0] + wt[1] * MID_MIST[0] + wt[2] * DEEP_MIST[0]);
            float g = AMBIENT * (wt[0] * SHALLOW_MIST[1] + wt[1] * MID_MIST[1] + wt[2] * DEEP_MIST[1]);
            float b = AMBIENT * (wt[0] * SHALLOW_MIST[2] + wt[1] * MID_MIST[2] + wt[2] * DEEP_MIST[2]);
            for (ShaderInstance shader : shaders) {
                if (shader == null) {
                    continue;
                }
                shader.setSampler("OvCaveMistMap", hazeTexture.getId());
                // 画素の真ん中が列の真ん中(原点 + 2i + 1.5)に来るよう半ブロックずらす。
                shader.safeGetUniform("OvCaveA").set((float) (m.originX + 0.5 - cam.x), (float) (m.originZ + 0.5 - cam.z), (float) TEXEL, (float) SIZE);
                shader.safeGetUniform("OvCaveB").set(density, HAZE_ABOVE_BOOST, (float) (m.baseY - HAZE_BASE_BELOW - cam.y), seconds);
                shader.safeGetUniform("OvCaveC").set(r, g, b, HAZE_SOFT);
                shader.safeGetUniform("OvCaveD").set(GLOW[0], GLOW[1], GLOW[2], (float) wrap64(cam.x / 7.0));
                shader.safeGetUniform("OvCaveE").set(Mth.lerp(day, SKY_NIGHT[0], SKY_DAY[0]), Mth.lerp(day, SKY_NIGHT[1], SKY_DAY[1]),
                        Mth.lerp(day, SKY_NIGHT[2], SKY_DAY[2]), (float) wrap64(cam.z / 7.0));
            }
        } catch (RuntimeException e) {
            broken = true;
            hazeOff(shaders);
            OvergrownVegetation.LOGGER.error("地下の霧の霞で例外が出たので、このセッションでは地下の霧を描かない", e);
        }
    }

    private static void hazeOff(ShaderInstance[] shaders) {
        for (ShaderInstance shader : shaders) {
            if (shader != null) {
                shader.safeGetUniform("OvCaveB").set(0.0F, 1.0F, 0.0F, 0.0F);
            }
        }
    }

    /**
     * 霞の地図を書く: r = てっぺん(基準 − {@link #HAZE_BASE_BELOW} から 1/4 ブロック刻み)、g = 重み({@link #weights})、b・a = 床の 1 段上の
     * ブロックの光・空の光。てっぺんの無いマスはまわりの平均で埋める(線形の補間で崩れないように。重み 0 なので見えない)。霧のマスが 1 つも無ければ false。
     */
    private static boolean writeHaze(CaveMistMap m, DynamicTexture texture) {
        NativeImage image = texture.getPixels();
        if (image == null) {
            return false;
        }
        int count = SIZE * SIZE;
        double sum = 0.0;
        int n = 0;
        for (int k = 0; k < count; k++) {
            if (!Float.isNaN(m.top[k])) {
                sum += m.top[k];
                n++;
            }
        }
        if (n == 0) {
            return false;
        }
        float mean = (float) (sum / n);
        float[] w = weights(m);
        int base = m.baseY - HAZE_BASE_BELOW;
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                int k = j * SIZE + i;
                float top = m.top[k];
                if (Float.isNaN(top)) {
                    float s = 0.0F;
                    int c = 0;
                    for (int dj = -2; dj <= 2; dj++) {
                        for (int di = -2; di <= 2; di++) {
                            int ii = i + di;
                            int jj = j + dj;
                            if (ii >= 0 && jj >= 0 && ii < SIZE && jj < SIZE && !Float.isNaN(m.top[jj * SIZE + ii])) {
                                s += m.top[jj * SIZE + ii];
                                c++;
                            }
                        }
                    }
                    top = c > 0 ? s / c : mean;
                }
                int r = Mth.clamp(Math.round((top - base) * 4.0F), 0, 255);
                int g = Mth.clamp(Math.round(w[k] * 255.0F), 0, 255);
                int b = Mth.clamp(m.blockLight[k] * 17, 0, 255);
                int a = Mth.clamp(m.skyLight[k] * 17, 0, 255);
                image.setPixelRGBA(i, j, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        texture.upload();
        texture.setFilter(true, false);
        return true;
    }

    // ------------------------------------------------------------------ 板(霧より上から見たとき)

    /** 粒・水を描いた後に、霧の板を描く(地形の奥行きで隠れ、奥行きは書かない)。 */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || broken) {
            return;
        }
        ShaderInstance shader = ModShaders.caveMist;
        CaveMistMap m = shown;
        ClientLevel level = Minecraft.getInstance().level;
        if (shader == null || m == null || level == null || fade <= 0.0F) {
            return;
        }
        try {
            if (event.getCamera().getFluidInCamera() != FogType.NONE) {
                return;
            }
            if (vboMap != m || vboFew != VisualQuality.fewMistSheets()) {
                // 前の板を入れ替え用に残し、新しい板を作る(前に板が無ければすぐ全部出す)。
                VertexBuffer spare = oldVbo;
                oldVbo = vbo;
                oldMap = vboMap;
                oldQuads = quads;
                vbo = spare;
                buildVbo(m);
                VertexBuffer spareFall = oldFallVbo;
                oldFallVbo = fallVbo;
                oldFallQuads = fallQuads;
                fallVbo = spareFall;
                buildFalls(m);
                blend = (oldQuads > 0 || oldFallQuads > 0) && oldMap != null ? 0.0F : 1.0F;
            }
            float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            Vec3 cam = event.getCamera().getPosition();
            float[] wt = new float[3];
            CaveLight.depthWeights(cam.y, wt);
            float day = Mth.clamp((level.getSkyDarken(partialTick) - 0.2F) / 0.8F, 0.0F, 1.0F);
            // 板を出すのは、板のその点よりカメラが {@link #SHEETS_FROM_ABOVE}〜{@link #SHEETS_FULL_ABOVE} ブロック以上上にいる所だけ(シェーダーで点ごとに決める。
            // 前はカメラの足元の霧の高さだけで全部の板を出し入れしていたので、足元の霧に近づくと遠くの崖を流れ落ちる霧までパッと消えた)。
            float alpha = (float) (SHEET_ALPHA * fade * OvergrownVegetationConfig.COMMON.caveMistDensity.get())
                    * (vboFew ? (float) SHEETS / FEW_SHEETS : 1.0F);
            float seconds = (float) (((level.getGameTime() % 200000L) + partialTick) / 20.0);
            shader.safeGetUniform("MistGlow").set(GLOW[0], GLOW[1], GLOW[2], ABOVE_BOOST - 1.0F);
            shader.safeGetUniform("MistSky").set(Mth.lerp(day, SKY_NIGHT[0], SKY_DAY[0]), Mth.lerp(day, SKY_NIGHT[1], SKY_DAY[1]),
                    Mth.lerp(day, SKY_NIGHT[2], SKY_DAY[2]), 0.0F);
            shader.safeGetUniform("MistNoise").set((float) wrap64(cam.x / 7.0), (float) wrap64(cam.z / 7.0), seconds, 0.0F);
            shader.safeGetUniform("MistFade").set(NEAR_FROM, NEAR_TO, SHEETS_FROM_ABOVE, SHEETS_FULL_ABOVE);
            float r = AMBIENT * (wt[0] * SHALLOW_MIST[0] + wt[1] * MID_MIST[0] + wt[2] * DEEP_MIST[0]);
            float g = AMBIENT * (wt[0] * SHALLOW_MIST[1] + wt[1] * MID_MIST[1] + wt[2] * DEEP_MIST[1]);
            float b = AMBIENT * (wt[0] * SHALLOW_MIST[2] + wt[1] * MID_MIST[2] + wt[2] * DEEP_MIST[2]);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            if (blend < 1.0F && oldVbo != null && oldMap != null && oldQuads > 0) {
                draw(shader, oldVbo, oldMap, cam, r, g, b, alpha * (1.0F - blend), event.getModelViewMatrix(), event.getProjectionMatrix());
            }
            if (vbo != null && quads > 0) {
                draw(shader, vbo, m, cam, r, g, b, alpha * blend, event.getModelViewMatrix(), event.getProjectionMatrix());
            }
            ShaderInstance fallShader = ModShaders.caveMistFall;
            if (fallShader != null) {
                float fallAlpha = (float) (FALL_ALPHA * fade * OvergrownVegetationConfig.COMMON.caveMistDensity.get());
                fallShader.safeGetUniform("MistGlow").set(GLOW[0], GLOW[1], GLOW[2], 0.0F);
                fallShader.safeGetUniform("MistSky").set(Mth.lerp(day, SKY_NIGHT[0], SKY_DAY[0]), Mth.lerp(day, SKY_NIGHT[1], SKY_DAY[1]),
                        Mth.lerp(day, SKY_NIGHT[2], SKY_DAY[2]), 0.0F);
                fallShader.safeGetUniform("FallNoise").set((float) wrap(cam.x + cam.z, 64.0 * 1.3), (float) wrap(cam.y, 64.0 * 2.5), seconds, 0.0F);
                fallShader.safeGetUniform("FallFade").set(FALL_NEAR_FROM, FALL_NEAR_TO, 0.0F, 0.0F);
                if (blend < 1.0F && oldFallVbo != null && oldMap != null && oldFallQuads > 0) {
                    draw(fallShader, oldFallVbo, oldMap, cam, r, g, b, fallAlpha * (1.0F - blend), event.getModelViewMatrix(), event.getProjectionMatrix());
                }
                if (fallVbo != null && fallQuads > 0) {
                    draw(fallShader, fallVbo, m, cam, r, g, b, fallAlpha * blend, event.getModelViewMatrix(), event.getProjectionMatrix());
                }
            }
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("地下の霧で例外が出たので、このセッションでは地下の霧を描かない", e);
        }
    }

    private static void draw(ShaderInstance shader, VertexBuffer buffer, CaveMistMap m, Vec3 cam, float r, float g, float b, float alpha,
                             Matrix4f modelView, Matrix4f projection) {
        if (alpha <= 0.002F) {
            return;
        }
        shader.safeGetUniform("Origin").set((float) (m.originX - cam.x), (float) (m.baseY - cam.y), (float) (m.originZ - cam.z));
        shader.safeGetUniform("MistColor").set(r, g, b, Math.min(1.0F, alpha));
        buffer.bind();
        buffer.drawWithShader(modelView, projection, shader);
        VertexBuffer.unbind();
    }

    /**
     * マスごとの霧の重み(床の上の霧の厚さ × くぼみほど濃く × 壁・地図の縁から離れているか)。霞の濃さに使い、板はこれにさらに
     * 「床から離れているか」「てっぺんがなだらかか」を掛ける。地図ごとに 1 回だけ計算する。
     */
    private static float[] weights(CaveMistMap m) {
        if (weightsMap == m && weights != null) {
            return weights;
        }
        int count = SIZE * SIZE;
        float[] open = new float[count];
        for (int k = 0; k < count; k++) {
            float floor = m.floor[k];
            float top = m.top[k];
            if (Float.isNaN(floor) || Float.isNaN(top)) {
                continue;
            }
            int i = k % SIZE;
            int j = k / SIZE;
            float edge = Mth.clamp(Math.min(Math.min(i, j), Math.min(SIZE - 1 - i, SIZE - 1 - j)) / 3.0F, 0.0F, 1.0F);
            float thick = top - floor;
            float pool = FLAT_WEIGHT + (1.0F - FLAT_WEIGHT) * Mth.clamp((thick - (float) m.depth) / POOL_EXTRA, 0.0F, 1.0F);
            open[k] = Mth.clamp((thick - 0.15F) / 0.5F, 0.0F, 1.0F) * pool * edge;
        }
        // 壁(床の無いマス)の近くを 2 マスほどかけて薄くする: 3×3 の平均より濃くしない。
        float[] soft = new float[count];
        for (int k = 0; k < count; k++) {
            if (open[k] <= 0.0F) {
                continue;
            }
            int i = k % SIZE;
            int j = k / SIZE;
            float sum = 0.0F;
            for (int dj = -1; dj <= 1; dj++) {
                for (int di = -1; di <= 1; di++) {
                    int ii = i + di;
                    int jj = j + dj;
                    if (ii >= 0 && jj >= 0 && ii < SIZE && jj < SIZE) {
                        sum += open[jj * SIZE + ii];
                    }
                }
            }
            soft[k] = Math.min(open[k], sum / 9.0F);
        }
        weightsMap = m;
        weights = soft;
        return soft;
    }

    @Nullable
    private static CaveMistMap weightsMap;
    @Nullable
    private static float[] weights;

    /**
     * てっぺんのなだらかさ(0〜1)。隣のマスとのてっぺんの差が {@link #STEEP_FROM} ブロックを超えると下がり、{@link #STEEP_FROM} + {@link #STEEP_RANGE} で 0。
     * 崖・柱のまわり・穴の縁のように霧の高さが急に変わる所では板が垂れ下がってぎざぎざの線・へこみに見えた(10-16 ユーザー指摘)ので、そこは板を消して霞に任せる。
     * 3×3 の一番小さい値にして、急な所の 1 マス手前から消す。
     */
    static float[] gentleness(CaveMistMap m) {
        int count = SIZE * SIZE;
        float[] raw = new float[count];
        for (int k = 0; k < count; k++) {
            float top = m.top[k];
            if (Float.isNaN(top)) {
                continue;
            }
            int i = k % SIZE;
            int j = k / SIZE;
            float most = 0.0F;
            for (int d = 0; d < 4; d++) {
                int ii = i + (d == 0 ? 1 : (d == 1 ? -1 : 0));
                int jj = j + (d == 2 ? 1 : (d == 3 ? -1 : 0));
                if (ii >= 0 && jj >= 0 && ii < SIZE && jj < SIZE && !Float.isNaN(m.top[jj * SIZE + ii])) {
                    most = Math.max(most, Math.abs(top - m.top[jj * SIZE + ii]));
                }
            }
            raw[k] = Mth.clamp(1.0F - (most - STEEP_FROM) / STEEP_RANGE, 0.0F, 1.0F);
        }
        float[] out = new float[count];
        for (int k = 0; k < count; k++) {
            int i = k % SIZE;
            int j = k / SIZE;
            float least = raw[k];
            for (int dj = -1; dj <= 1; dj++) {
                for (int di = -1; di <= 1; di++) {
                    int ii = i + di;
                    int jj = j + dj;
                    if (ii >= 0 && jj >= 0 && ii < SIZE && jj < SIZE && !Float.isNaN(m.top[jj * SIZE + ii])) {
                        least = Math.min(least, raw[jj * SIZE + ii]);
                    }
                }
            }
            out[k] = least;
        }
        return out;
    }

    /**
     * 地図から板の頂点を作る(座標は地図の原点・基準の高さからの相対)。頂点の色の r = 板の深さ(0 = てっぺん)、
     * g・b = 床の 1 段上のブロックの光・空の光(0〜1)、a = 重み。
     * 板は霧の上の面(てっぺんから {@link #DECK_TOP}〜{@link #DECK_TOP} + {@link #DECK_THICK} 下)だけに重ねる雲の天井で、床・くぼみの形はなぞらない
     * (前は床までの厚さに板を広げていたので、穴の上で板が沈んでへこみ、柱の根元で板が登った。深さは霞が見せる)。
     * 板がまわり 3×3 の床の一番高い所({@link CaveMistMap#floorHi})に近い所・てっぺんが急な所({@link #gentleness})は消す。
     */
    private static void buildVbo(CaveMistMap m) {
        vboMap = m;
        vboFew = VisualQuality.fewMistSheets();
        int sheets = vboFew ? FEW_SHEETS : SHEETS;
        quads = 0;
        int count = SIZE * SIZE;
        float[] soft = weights(m);
        float[] gentle = gentleness(m);
        ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 20);
        try {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            float[] y = new float[count];
            float[] w = new float[count];
            for (int s = 0; s < sheets; s++) {
                float f = (s + 0.5F) / sheets;
                for (int k = 0; k < count; k++) {
                    float top = m.top[k];
                    float floor = m.floorHi[k];
                    if (Float.isNaN(top)) {
                        y[k] = Float.NaN;
                        w[k] = 0.0F;
                        continue;
                    }
                    float sheet = top - DECK_TOP - f * DECK_THICK;
                    y[k] = sheet - m.baseY;
                    float clear = Float.isNaN(floor) ? 0.0F : Mth.clamp((sheet - floor - 0.2F) / 0.4F, 0.0F, 1.0F);
                    w[k] = soft[k] * clear * gentle[k];
                }
                for (int j = 0; j < SIZE - 1; j++) {
                    for (int i = 0; i < SIZE - 1; i++) {
                        int a = j * SIZE + i;
                        int b = a + 1;
                        int c = a + SIZE + 1;
                        int d = a + SIZE;
                        if (Float.isNaN(y[a]) || Float.isNaN(y[b]) || Float.isNaN(y[c]) || Float.isNaN(y[d])) {
                            continue;
                        }
                        if (w[a] <= 0.003F && w[b] <= 0.003F && w[c] <= 0.003F && w[d] <= 0.003F) {
                            continue;
                        }
                        vertex(builder, m, i, j, a, y[a], f, w[a]);
                        vertex(builder, m, i + 1, j, b, y[b], f, w[b]);
                        vertex(builder, m, i + 1, j + 1, c, y[c], f, w[c]);
                        vertex(builder, m, i, j + 1, d, y[d], f, w[d]);
                        quads++;
                    }
                }
            }
            if (quads == 0) {
                return;
            }
            MeshData data = builder.buildOrThrow();
            if (vbo == null) {
                vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
            }
            vbo.bind();
            vbo.upload(data);
            VertexBuffer.unbind();
        } finally {
            bytes.close();
        }
    }

    // ------------------------------------------------------------------ 霧の滝(崖・穴のふちから流れ落ちる霧)

    @Nullable
    private static VertexBuffer fallVbo;
    private static int fallQuads;
    @Nullable
    private static VertexBuffer oldFallVbo;
    private static int oldFallQuads;

    /**
     * 霧の滝の頂点を作る(2026-10-16 ユーザー提案「崖のようになっている場所は下まで霧を続かせず、途中で消えるようにすれば、作り直しても崖付近には
     * 下に流れる霧のように見える霧ができる」・前の指摘「小さい穴に霧が流れるように落ちるのではなく、へこむ」)。
     * 霧のあるマス A の隣のマス B の床が {@link #DROP_MIN} ブロック以上低いとき、A と B の境(B の列の手前)に縦の幕を 2 枚立て、ふち(A の床)の
     * {@link #FALL_LIP} 上から {@link #FALL_LENGTH} 下(B の床まで届くならそこ)まで、筋が流れ落ちながら薄くなって消えるように描く。形は A・B の床と A の霧だけで決まるので、
     * 地図を作り直しても同じ所に同じ滝ができる。幕は真ん中で 2 つに分け、向きに並ぶ隣の幕とは端の高さを平均してつなぎ、隣に幕が無い端は 0 にして消す。
     * 頂点の色の r = 落ちた割合(0 = 上、1 = 消える所)、g・b = A の床の 1 段上の光、a = 重み。
     */
    private static void buildFalls(CaveMistMap m) {
        fallQuads = 0;
        int count = SIZE * SIZE;
        float[] soft = weights(m);
        boolean[][] has = new boolean[4][count];
        float[][] top = new float[4][count];
        float[][] bottom = new float[4][count];
        int found = 0;
        for (int k = 0; k < count; k++) {
            float fa = m.floor[k];
            float ta = m.top[k];
            if (Float.isNaN(fa) || Float.isNaN(ta) || soft[k] < 0.05F || ta < fa + 0.3F) {
                continue;
            }
            int i = k % SIZE;
            int j = k / SIZE;
            for (int d = 0; d < 4; d++) {
                int ni = i + DIR_I[d];
                int nj = j + DIR_J[d];
                if (ni < 0 || nj < 0 || ni >= SIZE || nj >= SIZE) {
                    continue;
                }
                float fb = m.floor[nj * SIZE + ni];
                if (Float.isNaN(fb) || fa - fb < DROP_MIN) {
                    continue;
                }
                // 上端はふち(A の床)のすぐ上から。霧のてっぺんから始めると、ふちより上の部分が床に立った光る板に見えた(10-16 テストクライアント)。
                float yTop = fa + FALL_LIP;
                float yBottom = Math.max(fb + 0.2F, fa - FALL_LENGTH);
                if (yTop - yBottom < 0.8F) {
                    continue;
                }
                has[d][k] = true;
                top[d][k] = yTop;
                bottom[d][k] = yBottom;
                found++;
            }
        }
        if (found == 0) {
            return;
        }
        ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 18);
        try {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (int d = 0; d < 4; d++) {
                boolean alongZ = d < 2;
                for (int k = 0; k < count; k++) {
                    if (!has[d][k]) {
                        continue;
                    }
                    int i = k % SIZE;
                    int j = k / SIZE;
                    // 幕の向きに並ぶ前後のマス(x 向きの幕は z に、z 向きの幕は x に並ぶ)。
                    int prev = alongZ ? (j > 0 ? k - SIZE : -1) : (i > 0 ? k - 1 : -1);
                    int next = alongZ ? (j < SIZE - 1 ? k + SIZE : -1) : (i < SIZE - 1 ? k + 1 : -1);
                    boolean p = prev >= 0 && has[d][prev];
                    boolean n = next >= 0 && has[d][next];
                    float t0 = p ? (top[d][k] + top[d][prev]) * 0.5F : top[d][k];
                    float b0 = p ? (bottom[d][k] + bottom[d][prev]) * 0.5F : bottom[d][k];
                    float t1 = n ? (top[d][k] + top[d][next]) * 0.5F : top[d][k];
                    float b1 = n ? (bottom[d][k] + bottom[d][next]) * 0.5F : bottom[d][k];
                    // 隣に幕が無い端は 0 にして消す。幕は真ん中で 2 つに分け、真ん中はこのマスの濃さ(1 マスだけの幕も左右の端が硬くならない)。
                    float w0 = p ? (soft[k] + soft[prev]) * 0.5F : 0.0F;
                    float w1 = n ? (soft[k] + soft[next]) * 0.5F : 0.0F;
                    float wm = soft[k];
                    float tm = top[d][k];
                    float bm = bottom[d][k];
                    float bl = m.blockLight[k] / 15.0F;
                    float sl = m.skyLight[k] / 15.0F;
                    // A の列(ブロック)の地図の原点からの位置。B の列は 2 ブロック先。
                    float ax = m.columnX(k) - m.originX;
                    float az = m.columnZ(k) - m.originZ;
                    for (int layer = 0; layer < 2; layer++) {
                        float inset = 0.05F + 0.4F * layer;
                        float lw = layer == 0 ? 1.0F : 0.7F;
                        float x0;
                        float z0;
                        float x1;
                        float z1;
                        if (alongZ) {
                            float x = d == 0 ? ax + 2.0F + inset : ax - 2.0F + 1.0F - inset;
                            x0 = x;
                            x1 = x;
                            z0 = az - 0.5F;
                            z1 = az + 1.5F;
                        } else {
                            float z = d == 2 ? az + 2.0F + inset : az - 2.0F + 1.0F - inset;
                            z0 = z;
                            z1 = z;
                            x0 = ax - 0.5F;
                            x1 = ax + 1.5F;
                        }
                        float xm = (x0 + x1) * 0.5F;
                        float zm = (z0 + z1) * 0.5F;
                        builder.addVertex(x0, t0 - m.baseY, z0).setColor(0.0F, bl, sl, w0 * lw);
                        builder.addVertex(xm, tm - m.baseY, zm).setColor(0.0F, bl, sl, wm * lw);
                        builder.addVertex(xm, bm - m.baseY, zm).setColor(1.0F, bl, sl, wm * lw);
                        builder.addVertex(x0, b0 - m.baseY, z0).setColor(1.0F, bl, sl, w0 * lw);
                        builder.addVertex(xm, tm - m.baseY, zm).setColor(0.0F, bl, sl, wm * lw);
                        builder.addVertex(x1, t1 - m.baseY, z1).setColor(0.0F, bl, sl, w1 * lw);
                        builder.addVertex(x1, b1 - m.baseY, z1).setColor(1.0F, bl, sl, w1 * lw);
                        builder.addVertex(xm, bm - m.baseY, zm).setColor(1.0F, bl, sl, wm * lw);
                        fallQuads += 2;
                    }
                }
            }
            MeshData data = builder.buildOrThrow();
            if (fallVbo == null) {
                fallVbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
            }
            fallVbo.bind();
            fallVbo.upload(data);
            VertexBuffer.unbind();
        } finally {
            bytes.close();
        }
    }

    /** 隣のマスの向き: 0 = +x、1 = −x、2 = +z、3 = −z。 */
    private static final int[] DIR_I = {1, -1, 0, 0};
    private static final int[] DIR_J = {0, 0, 1, -1};

    private static void vertex(BufferBuilder builder, CaveMistMap m, int i, int j, int k, float y, float sheet, float weight) {
        builder.addVertex(i * TEXEL + TEXEL / 2 + 0.5F, y, j * TEXEL + TEXEL / 2 + 0.5F)
                .setColor(sheet, m.blockLight[k] / 15.0F, m.skyLight[k] / 15.0F, weight);
    }

    private static double wrap(double value, double period) {
        return value - Math.floor(value / period) * period;
    }

    private static double wrap64(double value) {
        return value - Math.floor(value / 64.0) * 64.0;
    }

    private CaveMist() {
    }
}
