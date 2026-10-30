package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.spore.MorningMist;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import javax.annotation.Nullable;

/**
 * 朝霧の見た目(2026-10-16 ユーザー指定「山の上から森を見たとき、森が霧がかって見えるように。下に行くと霧があるのか
 * 分からないほど薄い」)。霧がどこにどれだけあるかは {@link MorningMist}(サーバーと同じ式)。
 *
 * <ul>
 *     <li><b>雲の板</b>(2026-10-16 ユーザー指定「谷やくぼみに溜まる霧は薄い雲のようにくぼみに溜まる」「汚染されていない所から汚染地域を眺めたとき、
 *     霧が下の方に溜まっている様子が見える」): 層のてっぺんから厚さの 9 割まで {@link #SHEETS} 枚の水平の板を重ね、まだらな雲の模様(ゆっくり流れる)で薄く描く
 *     (自分のシェーダー morning_mist_cloud)。板は地面・梢より下では消し(当たる所は 3 ブロックかけて薄く)、カメラの近く・真横から見る所も消す
 *     (平らな板に見えないように)。カメラが霧の中にいる間は薄くする(中は霞とバニラの霧で見せる)。</li>
 *     <li><b>霞</b>: カメラのまわり 512 ブロック四方の「霧の層の底の高さ」の地図(8 ブロックで 1 画素)を作り、地形と水の
 *     シェーダー(ov_mist.glsl)が、視線が層の中を通った長さだけ霧の色へ寄せる。</li>
 *     <li><b>上から見ると濃い</b>: 本物は谷を何 km も見渡すので上から白く見えるが、Minecraft の距離では層の中を通る長さが
 *     上からでも中からでもあまり変わらない。そこで、カメラが層のてっぺんより上にいるほど濃く見せる
 *     ({@link #ABOVE_RAMP} ブロック上で {@link #ABOVE_BOOST} 倍)。下りて層に入ると自然に薄くなる。</li>
 *     <li><b>中にいるとき</b>: 近く(20 ブロックほど)はほとんど澄んだまま、遠く(設定 {@code morningMistFogDistance}、既定 64)
 *     から先はバニラの霧で隠す。地形だけでなく Mob も隠れる(霧に紛れる Mob)—— {@link CorruptionAtmosphere} が読む。</li>
 * </ul>
 *
 * <p>濃さ・色・距離は全部 Claude の仮決め。Sodium・Iris では地形のシェーダーを使わないので霞は出ない(中にいるときの
 * バニラの霧は出る)。霧の向こうの巨大樹のシルエット({@link GiantTreeSilhouettes})は自分のシェーダーなので霞まない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class MorningMistView {

    /** 地図の大きさ(画素)と 1 画素の幅(ブロック)。 */
    static final int SIZE = 64;
    static final int TEXEL = 8;
    /** 濃い朝の層の中での、1 ブロックあたりの霞の濃さ(設定 1 のとき)。20 ブロック先で約 9%。 */
    static final double DENSITY = 0.0045;
    /** 層の上にいるカメラが見る濃さの倍率と、その倍率に届く高さ(ブロック)。 */
    static final double ABOVE_BOOST = 8.0;
    static final double ABOVE_RAMP = 24.0;
    /** 中にいるときのバニラの霧の始まり(終わりの何倍)。 */
    static final float MIST_NEAR_FRACTION = 0.3F;
    /** 地図を作り直す: カメラが中心からこれだけ離れたら・この tick ごと。 */
    static final double RECENTER = 48.0;
    static final long REBUILD_TICKS = 100L;
    /** 1 tick に地図作りへ使ってよい時間(ナノ秒)。初めは地面を調べるので重い。 */
    static final long BUDGET_NANOS = 1_500_000L;
    /** 雲の板の枚数・1 枚の濃さ(濃い朝)・カメラの近くで消す距離(ブロック)・霧の中にいるときの濃さの倍率。仮決め。 */
    static final int SHEETS = 4;
    /**
     * 濃さを決めたときの枚数(朝霧 5・林冠の雲海 3)。板を減らしたら 1 枚を濃くして、重ねた全体の濃さをこの枚数のときに合わせる
     * (2026-10-24、半透明の重なりの削減で「多い」を 5+3 → 4+2 枚に。{@link #SHEET_ALPHA} はこの枚数での 1 枚の濃さ)。
     */
    static final int BASE_SHEETS = 5;
    static final int BASE_SEA_SHEETS = 3;
    static final float SHEET_ALPHA = 0.2F;
    static final float CLOUD_NEAR_FROM = 12.0F;
    static final float CLOUD_NEAR_TO = 32.0F;
    static final float CLOUD_INSIDE = 0.35F;

    private static final class Map {
        final int originX;
        final int originZ;
        final double[] low = new double[SIZE * SIZE];
        /** 表面の高い方の高さ(林冠の雲海の床の高さに使う)。 */
        final double[] high = new double[SIZE * SIZE];
        final double[] prox = new double[SIZE * SIZE];
        final boolean[] valid = new boolean[SIZE * SIZE];
        /** 画素の真ん中の列の表面の高さ(梢・地面・水面。読み込まれていなければ NaN)。雲の板を地面の下で消すのに使う。 */
        final float[] surface = new float[SIZE * SIZE];
        int next;
        int[] pixels;
        double base;

        Map(int originX, int originZ) {
            this.originX = originX;
            this.originZ = originZ;
        }
    }

    @Nullable
    private static Map building;
    @Nullable
    private static Map shown;
    private static boolean uploadPending;
    @Nullable
    private static DynamicTexture texture;
    private static ClientLevel mapLevel;
    private static long ticks;
    private static long shownAt;
    private static boolean nearOvergrown;
    private static boolean broken;

    /** 今の濃さ(時刻・朝の当たり・雨)と層の厚さ。 */
    private static double strength;
    private static double depth;
    /** カメラが層の中にいる度合い(0〜1、なめらかに寄せた値)と、層のてっぺんからの高さ(ブロック、負 = 中)。 */
    private static float cameraFog;
    private static double cameraAbove = Double.POSITIVE_INFINITY;
    /** 林冠の雲海のてっぺんからのカメラの高さ(ブロック)。 */
    private static double cameraAboveSea = Double.POSITIVE_INFINITY;
    /** カメラのいる所の霧の中の深さ(0〜1、濃さを掛けない)。空気中の胞子の量に使う。 */
    private static double cameraInside;
    /** 霧の出ている森の近くにいる度合い(濃さ × カメラの所の森の近さ、なめらかに寄せた値)。 */
    private static float nearMist;

    private static final float[] FOG_RGB = new float[3];

    static boolean active() {
        return !SodiumTerrain.unsupported() && !broken && enabled();
    }

    private static boolean enabled() {
        return OvergrownVegetationConfig.COMMON.morningMist.get();
    }

    /**
     * <b>林冠の雲海</b>を見下ろしている度合い(0〜1。2026-10-17、10-16 にユーザーが選んだ「密林をさらに美しく見せる案」の (8)「朝、巨大樹の上から見下ろすと
     * 林冠に霧の海」)。林冠の雲海のてっぺん({@link #seaTop})より {@value #SEA_FROM}〜{@value #SEA_TO} ブロック上で 0 → 1、× 今の朝霧の濃さ(× 2、1 まで)。
     * {@link CorruptionAtmosphere} はこの間だけ森の霧を晴らし(霧の海の上の空気は澄んでいる)、雲の板は濃く白くなる。昼・霧の無い朝は 0(森の霧は今まで通り)。
     */
    public static float cloudSeaView() {
        if (!active() || !Double.isFinite(cameraAboveSea) || strength <= 0.0) {
            return 0.0F;
        }
        double t = Mth.clamp((cameraAboveSea - SEA_FROM) / (SEA_TO - SEA_FROM), 0.0, 1.0);
        return (float) (t * t * (3.0 - 2.0 * t) * Math.min(1.0, strength * 2.0));
    }

    /** 雲海に見える高さ(層のてっぺんからのブロック)と、そのときの雲の板の濃さの倍率。仮決め。 */
    static final double SEA_FROM = 8.0;
    static final double SEA_TO = 40.0;
    /** 林冠の雲海: 表面の高い方の高さ({@link MorningMist#HIGH_PERCENTILE})のこれだけ上がてっぺん、厚さ、板の枚数、1 枚の濃さ。仮決め。 */
    static final double SEA_ABOVE = 1.5;
    static final double SEA_DEPTH = 4.0;
    static final int SEA_SHEETS = 2;
    /** 品質の段階で霧の板を減らしたときの枚数(朝霧・林冠の雲海。2026-10-23)。 */
    static final int FEW_SHEETS = 3;
    static final int FEW_SEA_SHEETS = 2;
    static final float SEA_SHEET_ALPHA_SCALE = 1.8F;
    /** 雲海を張る所: 列の表面(梢)が雲海のてっぺんからこのブロック数以内。仮決め。 */
    static final double SEA_SUPPORT = 30.0;

    /** 林冠の雲海のてっぺん: 表面の高い方 + {@link #SEA_ABOVE}(朝霧の層のてっぺんより下にはしない)。 */
    static double seaTop(double low, double high) {
        return Math.max(low + depth, high + SEA_ABOVE);
    }
    static final float SEA_SHEET_BOOST = 2.6F;

    /** カメラが霧の中にいる度合い(0〜1)。{@link CorruptionAtmosphere} がバニラの霧を近づけるのに使う。 */
    public static float cameraFog() {
        return cameraFog;
    }

    /**
     * バニラの霧の色を朝霧の色へ寄せる割合。中にいるときは {@link #MIST_FOG_COLOR_INSIDE}、霧の出ている森の近くで上にいるときも
     * {@link #MIST_FOG_COLOR_NEAR} まで(遠くの森が朝霧の色に霞む)。仮決め。
     */
    public static float fogColorBlend() {
        return Math.max(cameraFog * MIST_FOG_COLOR_INSIDE, nearMist * MIST_FOG_COLOR_NEAR);
    }

    static final float MIST_FOG_COLOR_INSIDE = 0.6F;
    static final float MIST_FOG_COLOR_NEAR = 0.35F;

    /** 中にいるときに霧へ寄せる色(毎フレーム {@link #onRenderLevel} で決める)。 */
    public static float[] fogColor() {
        return FOG_RGB;
    }

    /** 空気中の胞子の量(見た目の倍率。{@link MorningMist#airSpores})。光の筋・光る胞子が読む。 */
    public static float airSpores(ClientLevel level, float partialTick) {
        return (float) MorningMist.airSpores(level.getDayTime(), level.getRainLevel(partialTick), cameraInside * strength);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != mapLevel) {
            building = null;
            shown = null;
            mapLevel = level;
        }
        if (level == null || mc.player == null || mc.isPaused()) {
            return;
        }
        ticks++;
        strength = enabled() ? MorningMist.strength(level) : 0.0;
        if (strength <= 0.0) {
            building = null;
            shown = null;
            cameraInside = 0.0;
            cameraAbove = Double.POSITIVE_INFINITY;
            cameraAboveSea = Double.POSITIVE_INFINITY;
            nearMist = Math.max(0.0F, nearMist - 0.05F);
            cameraFog = Math.max(0.0F, cameraFog - 0.05F);
            return;
        }
        depth = MorningMist.depth(level.getDayTime());
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        // 汚染地域が近くにあるか(1 秒に 1 回、足元と周り 80・160・240 ブロックの 24 か所)。無ければ作らない。
        // 汚染されていない山などから森を眺めても霧が見えるよう、地図の届く所(±256)まで見る(10-16 ユーザー指定)。
        if (ticks % 20 == 1 || shown == null && building == null) {
            nearOvergrown = false;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int i = 0; i < 25 && !nearOvergrown; i++) {
                double angle = i * Math.PI / 4.0 + (i >= 16 ? Math.PI / 8.0 : 0.0);
                double r = i == 24 ? 0.0 : (i < 8 ? 80.0 : (i < 16 ? 160.0 : 240.0));
                pos.set(eye.x + Math.cos(angle) * r, eye.y, eye.z + Math.sin(angle) * r);
                if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null) {
                    nearOvergrown = ModBiomes.isOvergrownLike(level.getBiome(pos));
                }
            }
        }
        if (!nearOvergrown) {
            building = null;
            shown = null;
            cameraInside = 0.0;
            nearMist = Math.max(0.0F, nearMist - 0.05F);
            cameraFog = Math.max(0.0F, cameraFog - 0.05F);
            return;
        }
        updateCamera(level, eye);
        if (building == null) {
            boolean far = shown == null || Math.abs(eye.x - (shown.originX + SIZE * TEXEL / 2.0)) > RECENTER
                    || Math.abs(eye.z - (shown.originZ + SIZE * TEXEL / 2.0)) > RECENTER;
            if (far || ticks - shownAt >= REBUILD_TICKS) {
                int ox = Math.floorDiv(Mth.floor(eye.x), MorningMist.GRID) * MorningMist.GRID - SIZE * TEXEL / 2;
                int oz = Math.floorDiv(Mth.floor(eye.z), MorningMist.GRID) * MorningMist.GRID - SIZE * TEXEL / 2;
                building = new Map(ox, oz);
            }
        }
        if (building != null) {
            build(level, building);
        }
        if (DEV_LOG && ticks % 100 == 0 && shown != null) {
            Map m = shown;
            int valid = 0;
            double lowMin = Double.POSITIVE_INFINITY;
            double lowMax = Double.NEGATIVE_INFINITY;
            double prox = 0.0;
            for (int k = 0; k < SIZE * SIZE; k++) {
                if (m.valid[k]) {
                    valid++;
                    lowMin = Math.min(lowMin, m.low[k]);
                    lowMax = Math.max(lowMax, m.low[k]);
                    prox += m.prox[k];
                }
            }
            OvergrownVegetation.LOGGER.info("MorningMist 濃さ={} 厚さ={} 画素 {}/{} 底 {}〜{} 近さの平均={} カメラ: 層の上 {} 霧の中={} 雲の板 {} 面・描いた {} 回",
                    String.format("%.2f", strength), String.format("%.1f", depth), valid, SIZE * SIZE,
                    String.format("%.1f", lowMin), String.format("%.1f", lowMax), String.format("%.2f", valid == 0 ? 0.0 : prox / valid),
                    String.format("%.1f", cameraAbove), String.format("%.2f", cameraFog), cloudQuads, cloudDraws);
            cloudDraws = 0;
        }
    }

    /** Claude のテストクライアント(-Dov.devbridge=true)だけ、霧の状態を 5 秒ごとにログへ出す。 */
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    private static void updateCamera(ClientLevel level, Vec3 eye) {
        double[] out = new double[3];
        double inside = 0.0;
        double above = Double.POSITIVE_INFINITY;
        double aboveSea = Double.POSITIVE_INFINITY;
        BlockPos at = BlockPos.containing(eye);
        if (MorningMist.sample(level, eye.x, eye.z, out)) {
            above = eye.y - (out[0] + depth);
            aboveSea = eye.y - seaTop(out[0], out[2]);
            // 洞窟・建物の中は霧の外。
            if (level.getBrightness(LightLayer.SKY, at) >= 6) {
                inside = MorningMist.insideFactor(above) * out[1];
            }
        }
        cameraInside = inside;
        cameraAbove = above;
        cameraAboveSea = aboveSea;
        float near = (float) (strength * (Double.isFinite(above) ? out[1] : 0.0));
        nearMist = near > nearMist ? Math.min(near, nearMist + 0.02F) : Math.max(near, nearMist - 0.02F);
        float target = (float) (inside * strength);
        cameraFog = target > cameraFog ? Math.min(target, cameraFog + 0.02F) : Math.max(target, cameraFog - 0.02F);
    }

    private static void build(ClientLevel level, Map m) {
        long start = System.nanoTime();
        double[] out = new double[3];
        while (m.next < SIZE * SIZE) {
            int index = m.next++;
            int i = index % SIZE;
            int j = index / SIZE;
            double x = m.originX + i * TEXEL + TEXEL / 2.0;
            double z = m.originZ + j * TEXEL + TEXEL / 2.0;
            if (MorningMist.sample(level, x, z, out)) {
                m.low[index] = out[0];
                m.high[index] = out[2];
                m.prox[index] = out[1];
                m.valid[index] = true;
            }
            int sx = Mth.floor(x);
            int sz = Mth.floor(z);
            m.surface[index] = level.getChunkSource().getChunkNow(sx >> 4, sz >> 4) == null ? Float.NaN
                    : level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, sx, sz);
            if (System.nanoTime() - start > BUDGET_NANOS) {
                return;
            }
        }
        // 底の高さは地図の一番低い所から半ブロック刻み(128 ブロックの幅まで)。分からない画素は平均で埋める(線形の補間で崩れないように)。
        double base = Double.POSITIVE_INFINITY;
        double sum = 0.0;
        int count = 0;
        for (int k = 0; k < SIZE * SIZE; k++) {
            if (m.valid[k]) {
                base = Math.min(base, m.low[k]);
                sum += m.low[k];
                count++;
            }
        }
        if (count == 0) {
            shown = null;
            building = null;
            shownAt = ticks;
            return;
        }
        double mean = sum / count;
        m.base = base;
        m.pixels = new int[SIZE * SIZE];
        for (int k = 0; k < SIZE * SIZE; k++) {
            double low = m.valid[k] ? m.low[k] : mean;
            int r = Mth.clamp((int) Math.round((low - base) * 2.0), 0, 255);
            int b = m.valid[k] ? Mth.clamp((int) Math.round(m.prox[k] * 255.0), 0, 255) : 0;
            int a = m.valid[k] ? 255 : 0;
            m.pixels[k] = (a << 24) | (b << 16) | r;
        }
        shown = m;
        building = null;
        shownAt = ticks;
        uploadPending = true;
    }

    /** 空を描いた後・地形を描く前に、霧の地図と濃さ・色を地形と水のシェーダーへ渡す。 */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || broken) {
            return;
        }
        ShaderInstance[] shaders = {GameRenderer.getRendertypeSolidShader(), GameRenderer.getRendertypeCutoutShader(),
                GameRenderer.getRendertypeCutoutMippedShader(), GameRenderer.getRendertypeTranslucentShader()};
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            Map m = shown;
            float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            float density = 0.0F;
            Vec3 sun = null;
            if (m != null && level != null && strength > 0.0) {
                density = (float) (DENSITY * strength * OvergrownVegetationConfig.COMMON.morningMistDensity.get());
                sun = CanopyLight.sunDirection(level, partialTick);
            }
            if (level != null) {
                // 中にいるときにバニラの霧を寄せる色(地図が無くても決めておく)。
                if (sun == null) {
                    sun = CanopyLight.sunDirection(level, partialTick);
                }
                mistColor(level, sun, partialTick, FOG_RGB);
            }
            // Sodium・Iris では地形のシェーダーを使わないので霞は渡さない。色だけは上で決める(雲の板とバニラの霧が読む。
            // 2026-10-20 まではここより前で抜けていて、色が黒のまま = 雲の板が黒い膜になり、霧の近くでバニラの霧が黒へ寄っていた)。
            if (SodiumTerrain.unsupported()) {
                return;
            }
            if (density <= 0.0F || m == null) {
                for (ShaderInstance shader : shaders) {
                    if (shader != null) {
                        shader.safeGetUniform("OvMistB").set(0.0F, 1.0F, 0.0F, 0.0F);
                    }
                }
                return;
            }
            if (texture == null) {
                texture = new DynamicTexture(SIZE, SIZE, false);
                texture.setFilter(true, false);
            }
            if (uploadPending) {
                NativeImage image = texture.getPixels();
                if (image != null) {
                    for (int j = 0; j < SIZE; j++) {
                        for (int i = 0; i < SIZE; i++) {
                            image.setPixelRGBA(i, j, m.pixels[j * SIZE + i]);
                        }
                    }
                    texture.upload();
                    texture.setFilter(true, false);
                }
                uploadPending = false;
            }
            Vec3 cam = event.getCamera().getPosition();
            double above = cameraAbove;
            float boost = 1.0F;
            if (Double.isFinite(above)) {
                double t = Mth.clamp(above / ABOVE_RAMP, 0.0, 1.0);
                boost = (float) (1.0 + (ABOVE_BOOST - 1.0) * t * t * (3.0 - 2.0 * t));
            }
            float seconds = (float) (((level.getGameTime() % 200000L) + partialTick) / 20.0);
            float warm = (float) (0.8 * Mth.clamp(1.0 - sun.y / 0.45, 0.0, 1.0) * Mth.clamp((sun.y + 0.12) / 0.2, 0.0, 1.0));
            float noiseX = (float) wrap64(cam.x / 24.0);
            float noiseZ = (float) wrap64(cam.z / 24.0);
            for (ShaderInstance shader : shaders) {
                if (shader == null) {
                    continue;
                }
                shader.setSampler("OvMistMap", texture.getId());
                shader.safeGetUniform("OvMistA").set((float) (m.originX - cam.x), (float) (m.originZ - cam.z), (float) TEXEL,
                        (float) SIZE);
                shader.safeGetUniform("OvMistB").set(density, boost, (float) (m.base - cam.y), seconds);
                shader.safeGetUniform("OvMistC").set(FOG_RGB[0], FOG_RGB[1], FOG_RGB[2], (float) depth);
                shader.safeGetUniform("OvMistD").set((float) sun.x, (float) sun.y, (float) sun.z, warm);
                shader.safeGetUniform("OvMistE").set(noiseX, noiseZ, 0.0F, 0.0F);
            }
        } catch (RuntimeException e) {
            broken = true;
            for (ShaderInstance shader : shaders) {
                if (shader != null) {
                    shader.safeGetUniform("OvMistB").set(0.0F, 1.0F, 0.0F, 0.0F);
                }
            }
            OvergrownVegetation.LOGGER.error("朝霧の地図で例外が出たので、このセッションでは霞を出さない", e);
        }
    }

    // ------------------------------------------------------------------ 雲の板

    @Nullable
    private static com.mojang.blaze3d.vertex.VertexBuffer cloudVbo;
    /** 雲の板を作った元の地図と、そのときの層の厚さ(厚さが変わったら作り直す)。 */
    @Nullable
    private static Map cloudMap;
    private static double cloudDepth;
    /** 板を減らして作ったか(品質の段階の「霧の板」、2026-10-23。変わったら作り直す)。 */
    private static boolean cloudFew;
    private static int cloudQuads;
    /** 雲の板を描いた回数(テストクライアントのログ用。ログを出すたびに 0 に戻す)。 */
    private static int cloudDraws;

    /** 粒・水を描いた後に、霧の雲の板を描く(地形の奥行きで隠れ、奥行きは書かない)。 */
    @SubscribeEvent
    public static void onRenderClouds(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || broken) {
            return;
        }
        ShaderInstance shader = ModShaders.morningMistCloud;
        Map m = shown;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (shader == null || m == null || level == null || strength <= 0.0 || m.pixels == null) {
            return;
        }
        try {
            if (event.getCamera().getFluidInCamera() != net.minecraft.world.level.material.FogType.NONE) {
                return;
            }
            if (cloudMap != m || Math.abs(cloudDepth - depth) > 0.25 || cloudFew != VisualQuality.fewMistSheets() || cloudSheets != manySheets() * 16 + manySeaSheets()) {
                buildClouds(m);
            }
            if (cloudVbo == null || cloudQuads == 0) {
                return;
            }
            // 雑音の表は uniform を決める前に(焼くときに Origin・MistFade を使う)。
            boolean table = hashTable(shader);
            float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            Vec3 cam = event.getCamera().getPosition();
            Vec3 sun = CanopyLight.sunDirection(level, partialTick);
            float warm = (float) (0.8 * Mth.clamp(1.0 - sun.y / 0.45, 0.0, 1.0) * Mth.clamp((sun.y + 0.12) / 0.2, 0.0, 1.0));
            float alpha = (float) (SHEET_ALPHA * strength * OvergrownVegetationConfig.COMMON.morningMistDensity.get())
                    * Mth.lerp(cameraFog, 1.0F, CLOUD_INSIDE)
                    * Mth.lerp(cloudSeaView(), 1.0F, SEA_SHEET_BOOST)
                    * mistSheetScale();
            float seconds = (float) (((level.getGameTime() % 200000L) + partialTick) / 20.0);
            shader.safeGetUniform("Origin").set((float) (m.originX - cam.x), (float) (m.base - cam.y), (float) (m.originZ - cam.z));
            shader.safeGetUniform("MistColor").set(FOG_RGB[0], FOG_RGB[1], FOG_RGB[2], Math.min(1.0F, alpha));
            shader.safeGetUniform("MistSun").set((float) sun.x, (float) sun.y, (float) sun.z, warm);
            shader.safeGetUniform("MistNoise").set((float) wrap64(cam.x / 40.0), (float) wrap64(cam.z / 40.0), seconds, table ? 1.0F : 0.0F);
            shader.safeGetUniform("MistFade").set(CLOUD_NEAR_FROM, CLOUD_NEAR_TO, seaSheetScale() / mistSheetScale(), 0.0F);
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            com.mojang.blaze3d.systems.RenderSystem.disableCull();
            cloudVbo.bind();
            shader.setDefaultUniforms(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, event.getModelViewMatrix(), event.getProjectionMatrix(), mc.getWindow());
            shader.apply();
            int previous = com.mojang.blaze3d.platform.GlStateManager._getActiveTexture();
            if (table) {
                org.lwjgl.opengl.GL20.glUniform1i(hashLocation, HASH_UNIT);
                com.mojang.blaze3d.systems.RenderSystem.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + HASH_UNIT);
                org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, hashTexture);
                com.mojang.blaze3d.systems.RenderSystem.activeTexture(previous);
            }
            cloudVbo.draw();
            shader.clear();
            if (table) {
                com.mojang.blaze3d.systems.RenderSystem.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + HASH_UNIT);
                org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0);
                com.mojang.blaze3d.systems.RenderSystem.activeTexture(previous);
            }
            com.mojang.blaze3d.vertex.VertexBuffer.unbind();
            cloudDraws++;
            com.mojang.blaze3d.systems.RenderSystem.enableCull();
            com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("朝霧の雲の板で例外が出たので、このセッションでは霧を描かない", e);
        }
    }

    /** 開発用: 「多い」のときの枚数を差し替える(0 = {@link #SHEETS}・{@link #SEA_SHEETS})。前の枚数と同じ時刻で撮り比べるため(devbridge の mistsheets)。 */
    private static int devSheets;
    private static int devSeaSheets;
    /** 今の板を作ったときの枚数(朝霧 × 16 + 雲海)。 */
    private static int cloudSheets;

    public static void devSetSheets(int sheets, int seaSheets) {
        devSheets = Math.max(0, sheets);
        devSeaSheets = Math.max(0, seaSheets);
    }

    private static int manySheets() {
        return devSheets > 0 ? devSheets : SHEETS;
    }

    private static int manySeaSheets() {
        return devSeaSheets > 0 ? devSeaSheets : SEA_SHEETS;
    }

    /** 朝霧の板 1 枚の濃さの倍率(今の枚数で、{@link #BASE_SHEETS} 枚のときの全体の濃さに合わせる)。 */
    private static float mistSheetScale() {
        return (float) BASE_SHEETS / (cloudFew ? FEW_SHEETS : manySheets());
    }

    /** 林冠の雲海の板 1 枚の濃さの倍率({@link #BASE_SEA_SHEETS} 枚のときに合わせる)。シェーダーへは朝霧の倍率との比で渡す(頂点の色の g = 雲海の板)。 */
    private static float seaSheetScale() {
        return (float) BASE_SEA_SHEETS / (cloudFew ? FEW_SEA_SHEETS : manySeaSheets());
    }

    /** 雑音の格子の乱数の表を結ぶテクスチャの番号(json の samplers は並び順の番号に結ぶので、自分で結ぶ。GpuSpores と同じ 6)。 */
    private static final int HASH_UNIT = 6;
    private static final int HASH_SIZE = 64;
    private static int hashTexture;
    private static int hashLocation = -1;
    /** 表を焼いたシェーダー(資源の読み直しで替わったら焼き直す)。焼けなかったら null のまま {@link #hashFailed}。 */
    private static ShaderInstance hashShader;
    private static ShaderInstance hashFailed;

    /**
     * 雲の板の雑音の格子の乱数(sin のハッシュ)を 64×64 の表に焼き、読めるなら true(2026-10-24、半透明の重なりの削減)。
     * 板 1 枚の 1 画素で sin を 12 回計算していたのを表の読み出しにする。<b>同じシェーダーの焼く型(MistFade.w = 1)で GPU に計算させる</b>ので、
     * 表の値は今までの計算と同じ(CPU の Math.sin で作ると GPU の sin と値が違い、まだらの模様が変わる)。
     * 焼けなかった・中身がおかしいときは false を返し、シェーダーは前の sin の計算のまま描く。
     */
    private static boolean hashTable(ShaderInstance shader) {
        if (shader == hashShader) {
            return true;
        }
        if (shader == hashFailed) {
            return false;
        }
        hashShader = null;
        hashFailed = shader;
        int[] viewport = new int[4];
        org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_VIEWPORT, viewport);
        // 描き先は主画面とは限らない(ファビュラスの粒の層・描画解像度の縮小)ので、今のものへ戻す。
        int drawTarget = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readTarget = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
        int fbo = 0;
        try {
            if (hashTexture == 0) {
                hashTexture = com.mojang.blaze3d.platform.GlStateManager._genTexture();
                com.mojang.blaze3d.systems.RenderSystem.bindTexture(hashTexture);
                org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_NEAREST);
                org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_NEAREST);
                org.lwjgl.opengl.GL11.glTexImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL30.GL_R32F, HASH_SIZE, HASH_SIZE, 0,
                        org.lwjgl.opengl.GL11.GL_RED, org.lwjgl.opengl.GL11.GL_FLOAT, (java.nio.FloatBuffer) null);
            }
            fbo = org.lwjgl.opengl.GL30.glGenFramebuffers();
            // 板 1 枚(画面いっぱいの四角)を焼く型で描く。行列は単位行列・原点 0 なので Position がそのまま画面の座標。
            com.mojang.blaze3d.vertex.BufferBuilder quad = com.mojang.blaze3d.systems.RenderSystem.renderThreadTesselator().begin(
                    com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
            quad.addVertex(-1.0F, -1.0F, 0.0F).setColor(0.0F, 0.0F, 0.0F, 1.0F);
            quad.addVertex(1.0F, -1.0F, 0.0F).setColor(0.0F, 0.0F, 0.0F, 1.0F);
            quad.addVertex(1.0F, 1.0F, 0.0F).setColor(0.0F, 0.0F, 0.0F, 1.0F);
            quad.addVertex(-1.0F, 1.0F, 0.0F).setColor(0.0F, 0.0F, 0.0F, 1.0F);
            com.mojang.blaze3d.vertex.MeshData mesh = quad.buildOrThrow();
            com.mojang.blaze3d.vertex.VertexBuffer buffer = new com.mojang.blaze3d.vertex.VertexBuffer(com.mojang.blaze3d.vertex.VertexBuffer.Usage.STATIC);
            try {
                buffer.bind();
                buffer.upload(mesh);
                shader.safeGetUniform("Origin").set(0.0F, 0.0F, 0.0F);
                shader.safeGetUniform("MistFade").set(0.0F, 1.0F, 0.0F, 1.0F);
                com.mojang.blaze3d.systems.RenderSystem.disableBlend();
                com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
                com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
                com.mojang.blaze3d.systems.RenderSystem.disableCull();
                shader.setDefaultUniforms(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, new org.joml.Matrix4f(), new org.joml.Matrix4f(),
                        Minecraft.getInstance().getWindow());
                shader.apply();
                // apply の後に描き先を自分の表へ(Iris の止めを外す型は apply の中で描き先を替える)。
                com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, fbo);
                org.lwjgl.opengl.GL30.glFramebufferTexture2D(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0,
                        org.lwjgl.opengl.GL11.GL_TEXTURE_2D, hashTexture, 0);
                boolean complete = org.lwjgl.opengl.GL30.glCheckFramebufferStatus(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER) == org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
                if (complete) {
                    com.mojang.blaze3d.platform.GlStateManager._viewport(0, 0, HASH_SIZE, HASH_SIZE);
                    buffer.draw();
                }
                shader.clear();
                if (!complete) {
                    return false;
                }
            } finally {
                buffer.close();
                com.mojang.blaze3d.vertex.VertexBuffer.unbind();
            }
            // 中身を確かめる(0〜1 で、全部が同じ値でない)。焼く型を知らない古いシェーダー(資源パックの差し替えなど)なら板の色になる。
            java.nio.FloatBuffer check = org.lwjgl.BufferUtils.createFloatBuffer(HASH_SIZE * HASH_SIZE);
            com.mojang.blaze3d.systems.RenderSystem.bindTexture(hashTexture);
            org.lwjgl.opengl.GL11.glGetTexImage(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_RED, org.lwjgl.opengl.GL11.GL_FLOAT, check);
            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            for (int i = 0; i < HASH_SIZE * HASH_SIZE; i++) {
                float v = check.get(i);
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            if (!(min >= 0.0F && max < 1.0F && max - min > 0.5F)) {
                OvergrownVegetation.LOGGER.info("MorningMistView: 雑音の表の中身が違う({}〜{})ので sin の計算で描く", min, max);
                return false;
            }
            hashLocation = org.lwjgl.opengl.GL20.glGetUniformLocation(shader.getId(), "MistHash");
            if (hashLocation < 0) {
                return false;
            }
            hashShader = shader;
            hashFailed = null;
            return true;
        } catch (RuntimeException e) {
            OvergrownVegetation.LOGGER.warn("MorningMistView: 雑音の表を焼けなかったので sin の計算で描く", e);
            return false;
        } finally {
            if (fbo != 0) {
                org.lwjgl.opengl.GL30.glDeleteFramebuffers(fbo);
            }
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, drawTarget);
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, readTarget);
            com.mojang.blaze3d.platform.GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
            com.mojang.blaze3d.systems.RenderSystem.disableCull();
            shader.safeGetUniform("MistFade").set(CLOUD_NEAR_FROM, CLOUD_NEAR_TO, 0.0F, 0.0F);
        }
    }

    /**
     * 地図から雲の板の頂点を作る({@link #SHEETS} 枚。座標は地図の原点・底の高さからの相対)。頂点の色の r = 板の深さ(0 = てっぺん)、
     * a = 重み(汚染地域の近さ × 地図の縁 × 地面・梢より上か)。重みが全部 0 の四角は作らない。
     */
    private static void buildClouds(Map m) {
        cloudMap = m;
        cloudDepth = depth;
        cloudFew = VisualQuality.fewMistSheets();
        cloudSheets = manySheets() * 16 + manySeaSheets();
        // 板を減らすときは 1 枚を濃くして、重ねた全体の濃さをほぼ保つ(描くときの濃さに枚数の比を掛ける。頂点の重みは 1 で頭打ちなので、そちらには掛けない。
        // 朝霧と雲海で比が違うので、雲海の板は頂点の色の g で見分けてシェーダーで掛け直す。{@link #mistSheetScale}・{@link #seaSheetScale})。
        int sheets = cloudFew ? FEW_SHEETS : manySheets();
        int seaSheets = cloudFew ? FEW_SEA_SHEETS : manySeaSheets();
        com.mojang.blaze3d.vertex.ByteBufferBuilder bytes = new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 20);
        try {
            com.mojang.blaze3d.vertex.BufferBuilder builder = new com.mojang.blaze3d.vertex.BufferBuilder(bytes,
                    com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
            float[] y = new float[SIZE * SIZE];
            float[] w = new float[SIZE * SIZE];
            float[] known = knownFade(m);
            int quads = 0;
            // 朝霧の層の板 SHEETS 枚 + 林冠の雲海の板 SEA_SHEETS 枚(梢の高い方の少し上に張る雲の床。高い梢だけが突き出る。2026-10-17)。
            for (int s = 0; s < sheets + seaSheets; s++) {
                boolean sea = s >= sheets;
                float f = sea ? (s - sheets + 0.5F) / seaSheets : (s + 0.5F) / sheets;
                for (int k = 0; k < SIZE * SIZE; k++) {
                    if (!m.valid[k] || sea && Double.isNaN(m.high[k])) {
                        w[k] = 0.0F;
                        continue;
                    }
                    double top = sea ? seaTop(m.low[k], m.high[k]) : m.low[k] + depth;
                    double sheet = sea ? top - 0.3 - f * SEA_DEPTH : top - 0.3 - f * depth * 0.9;
                    y[k] = (float) (sheet - m.base);
                    float clear = Float.isNaN(m.surface[k]) ? 0.0F : (float) Mth.clamp((sheet - m.surface[k] + 0.5) / 3.0, 0.0, 1.0);
                    int i = k % SIZE;
                    int j = k / SIZE;
                    float edge = Mth.clamp(Math.min(Math.min(i, j), Math.min(SIZE - 1 - i, SIZE - 1 - j)) / 3.0F, 0.0F, 1.0F);
                    // 雲海は梢が下で支えている所だけ(画素の真ん中の列の表面が雲海のてっぺんから SEA_SUPPORT 以内)。森の外の平地の上に浮かない。
                    float support = !sea ? 1.0F : Float.isNaN(m.surface[k]) ? 0.0F
                            : (float) Mth.clamp((m.surface[k] - (top - SEA_SUPPORT)) / 6.0, 0.0, 1.0);
                    w[k] = (float) m.prox[k] * clear * edge * support * known[k] * (sea ? SEA_SHEET_ALPHA_SCALE : 1.0F);
                }
                for (int j = 0; j < SIZE - 1; j++) {
                    for (int i = 0; i < SIZE - 1; i++) {
                        int a = j * SIZE + i;
                        int b = a + 1;
                        int c = a + SIZE + 1;
                        int d = a + SIZE;
                        if (w[a] <= 0.003F && w[b] <= 0.003F && w[c] <= 0.003F && w[d] <= 0.003F) {
                            continue;
                        }
                        vertex(builder, i, j, y[a], f, sea, Math.min(1.0F, w[a]));
                        vertex(builder, i + 1, j, y[b], f, sea, Math.min(1.0F, w[b]));
                        vertex(builder, i + 1, j + 1, y[c], f, sea, Math.min(1.0F, w[c]));
                        vertex(builder, i, j + 1, y[d], f, sea, Math.min(1.0F, w[d]));
                        quads++;
                    }
                }
            }
            cloudQuads = quads;
            if (quads == 0) {
                return;
            }
            com.mojang.blaze3d.vertex.MeshData data = builder.buildOrThrow();
            if (cloudVbo == null) {
                cloudVbo = new com.mojang.blaze3d.vertex.VertexBuffer(com.mojang.blaze3d.vertex.VertexBuffer.Usage.STATIC);
            }
            cloudVbo.bind();
            cloudVbo.upload(data);
            com.mojang.blaze3d.vertex.VertexBuffer.unbind();
        } finally {
            bytes.close();
        }
    }

    /**
     * 分からない画素(読み込まれていないチャンク)までの近さで板を薄くする係数(3 画素 = 24 ブロック手前から 0 へ)。2026-10-17: 雲海を見下ろすときに森の霧を晴らすと、
     * 読み込まれた範囲の縁で板がまっすぐ切れているのが見えた(前は森の霧に隠れていた)。
     */
    private static float[] knownFade(Map m) {
        float[] f = new float[SIZE * SIZE];
        for (int k = 0; k < SIZE * SIZE; k++) {
            int i = k % SIZE;
            int j = k / SIZE;
            int nearest = 4;
            for (int dj = -3; dj <= 3 && nearest > 0; dj++) {
                for (int di = -3; di <= 3; di++) {
                    int ii = i + di;
                    int jj = j + dj;
                    if (ii < 0 || jj < 0 || ii >= SIZE || jj >= SIZE || !m.valid[jj * SIZE + ii]) {
                        nearest = Math.min(nearest, Math.max(Math.abs(di), Math.abs(dj)));
                    }
                }
            }
            f[k] = Mth.clamp(nearest / 4.0F, 0.0F, 1.0F);
        }
        return f;
    }

    private static void vertex(com.mojang.blaze3d.vertex.BufferBuilder builder, int i, int j, float y, float sheet, boolean sea, float weight) {
        builder.addVertex(i * TEXEL + TEXEL / 2.0F, y, j * TEXEL + TEXEL / 2.0F).setColor(sheet, sea ? 1.0F : 0.0F, 0.0F, weight);
    }

    /**
     * 霧の色: 夜明け前は暗い青灰、日が昇ると明るい白(森の青緑を少し)。太陽の方の暖かい色はシェーダーで足す。
     * 明るさは太陽の高さで寄せる(日の出の少し前から明るくなる)。仮決め。
     */
    private static void mistColor(ClientLevel level, Vec3 sun, float partialTick, float[] out) {
        double light = Mth.clamp((sun.y + 0.15) / 0.45, 0.0, 1.0);
        light = light * light * (3.0 - 2.0 * light);
        light *= 1.0 - 0.6 * level.getRainLevel(partialTick);
        out[0] = (float) Mth.lerp(light, 0.10, 0.78);
        out[1] = (float) Mth.lerp(light, 0.13, 0.84);
        out[2] = (float) Mth.lerp(light, 0.16, 0.85);
    }

    private static double wrap64(double value) {
        return value - Math.floor(value / 64.0) * 64.0;
    }

    private MorningMistView() {
    }
}
