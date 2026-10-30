package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.sky.CloudField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.concurrent.CompletableFuture;

/**
 * ボクセル調の雲(2026-10-17。ideas.voxel_clouds の段取り (1)。ユーザー「次はボクセルの雲(流れる雲の影の土台)を進めてください」)。
 * バニラの雲の代わりに、{@link CloudField} の下の層(y 300〜350)を 8 × 4 × 8 ブロックの箱の柱で描く。
 *
 * <ul>
 *   <li>カメラのまわり {@link #RADIUS_CELLS} 升(640 ブロック)の柱を別スレッドで場から引き、外から見える面だけを形にする(STATIC の VBO)。
 *       形は場の座標で持ち、風で流れた分とカメラの位置は描くときの平行移動(uniform)で足す。作り直すのはカメラ(場の上での位置)が
 *       {@link #REBUILD_CELLS} 升動いたときと、量が変わったときだけ。</li>
 *   <li>頂点には太陽と関係ない値だけを焼く(色の r = 面の向き、g = 層の中の高さ、b = 薄さ)。光はシェーダー({@code voxel_clouds})が
 *       今の太陽(夜は月)の向きと、バニラの雲の色({@code getCloudColor}: 夜は暗く、雨は灰色)で決める: 下面は灰色、低い所ほど暗く、
 *       日の当たる面は日の色(朝夕は橙)、太陽の方を向くと薄い縁ほど光る(銀の縁)。朝焼け・夕焼けの間は空の地平の色(バニラの
 *       {@code getSunriseColor})を太陽の側の雲に映す(下面ほど強く)。遠いほど空の色へかすみ、縁の手前で消える。</li>
 *   <li>バニラと同じ 2 回描き(奥行きだけ → 色)で、一番手前の面だけが空と混ざる(並べ替えが要らない)。</li>
 *   <li>地形の霧は掛けない(森の霧は 96 ブロックで終わるので雲が全部消える)。遠くの消え方は自前。</li>
 * </ul>
 * 積乱雲(2026-10-17、段取り (2)): 塔は下の層の柱の代わりに({@link CloudField#column})、金床は 2 段目の柱として同じ形にする(高さが重ならないので
 * 内側の面は出ない)。育つと作り直す(塔の天辺・金床の広がりが 1 段変わるたびに。3 秒より詰めない)。雷の光 {@link Thunderstorms} を 3 つまで
 * uniform で受け、近くの雲の面を内側から照らす。
 * 遠くの雲(奥の面の 0.9 倍より遠い所)は空と一緒に地形より先に、雲だけの奥行きの幅で描くので、600 まで伸びる塔も地平近くの雲も切れない
 * ({@link #onRenderLevel})。
 * 描く範囲は描画距離で決まる奥の面より手前まで(描画距離が短いと雲の届く範囲も狭い)。バニラの雲の設定が「オフ」だと呼ばれない。
 * 洞窟の中では空と一緒に消える({@code LevelRendererCaveSkyMixin})。数値はすべて仮決め。
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = net.neoforged.fml.common.EventBusSubscriber.Bus.GAME,
        value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class VoxelClouds {
    /** カメラのまわりの柱の数(片側、升)。8 ブロック × 80 = 640 ブロック。 */
    static final int RADIUS_CELLS = 80;
    static final int SIZE = RADIUS_CELLS * 2 + 1;
    /** カメラ(場の上)がこれだけ升を動いたら作り直す。 */
    static final int REBUILD_CELLS = 6;
    /** 量がこれだけ変わったら作り直す。 */
    static final float REBUILD_COVERAGE = 0.01F;

    private static final class Window {
        int originX;
        int originZ;
        int centerX;
        int centerZ;
        float coverage;
        int quads;
        int stormKey;
        ByteBufferBuilder bytes;
        MeshData mesh;
        /** 遠景の窓: 番号と穴の真ん中(場の座標、ブロック)。近くの窓: 作ったときの遠景の番号(0 = 遠景なし)。 */
        long id;
        int holeX;
        int holeZ;
        long farId;
        /** 遠景の窓: 外の輪(FAR3_RANGE まで)を作ったか。 */
        boolean outer;
        /** 遠景の窓: 柱を倍の大きさで作ったか(品質の段階の「雲の遠景」、2026-10-23)。 */
        boolean coarse;
    }

    private static ClientLevel lastLevel;
    private static Window shown;
    private static CompletableFuture<Window> pending;
    private static VertexBuffer vbo;

    /*
     * 遠景(2026-10-18、ユーザー選択「A+E と D」の A。ユーザー「高度 400 を超えて雲より上に入ったとき、読み込み範囲外の雲も見えるように」):
     * 雲の上(高さ FAR_ON_Y より上)で、近くの窓の外に 7680 ブロックまでの雲の海と積乱雲を、遠いほど大きな箱で描く(別の VBO・同じシェーダー)。
     * 遠景には真ん中に四角い穴(FAR_HOLE)があり、その中は近くの窓が受け持つ: 近くの雲の床は穴の外をシェーダーで捨て、積乱雲は真ん中が穴の中なら近く、
     * 外なら遠景が形にする(どちらか片方だけ)。持ち分が食い違わないよう、遠景を作り直すときは近くの窓も同じ遠景で一緒に作り、2 つ同時に差し替える。
     * 穴の幅は、遠景を作り直すまでの動き(FAR_MOVE)と近くの窓のずれを足しても、近くの雲の床(FLOOR_RANGE)の中に収まるように決めた。数値は仮決め。
     */
    static final double FAR_ON_Y = 410.0;
    static final double FAR_OFF_Y = 395.0;
    /** 穴の半分の幅・粗さの境・遠景の外の端(ブロック。どれも 96 の倍数。真ん中は FAR_SNAP の倍数に寄せる)。 */
    static final int FAR_HOLE = 1344;
    static final int FAR_MID = 3840;
    static final int FAR_RANGE = 7680;
    static final int FAR_SNAP = 192;
    /** 雲の海の柱の大きさ(穴〜FAR_MID・その外)。 */
    static final int FAR_CELL_1 = 48;
    static final int FAR_CELL_2 = 96;
    /**
     * 外の輪(2026-10-18 夜、空の世界の順番 6「もっと遠くまで見える」。ユーザー 10-18「ほかの MOD で空を飛ぶ人も多いので、できるだけ遠くまで見えるように」):
     * 雲の海の地平線(丸み CURVE_* で決まる)は y 950 あたりから FAR_RANGE より遠くなる(y 1500 で 10 km・y 3300 で 16 km・y 4000 で 18 km)ので、
     * FAR3_ON_Y より上では FAR3_RANGE まで FAR_CELL_3 の柱の雲の海を足す。積乱雲は巨大な雲と天辺 FAR3_STORM_TOP 以上の塔だけ(入道雲は無し)を 256 ブロックの箱で。
     * それまでは 7.7 km で円盤のように終わり、その先の地平線まで模様の無い帯(地平線の書き割り)と空が見えた(y 3300 の白い帯)。数値は仮決め。
     */
    static final int FAR3_RANGE = 18432;
    static final int FAR_CELL_3 = 192;
    static final double FAR3_ON_Y = 950.0;
    static final double FAR3_OFF_Y = 850.0;
    static final int FAR3_STORM_TOP = 1100;
    /** カメラが穴の真ん中からこれだけ(どちらかの向きに)離れたら作り直す。 */
    static final double FAR_MOVE = 240.0;
    static final int FAR_REBUILD_TICKS = 1200;
    /** 遠景の消え始めと消え終わり(カメラからの水平距離)。 */
    static final float FAR_FADE_FROM = 6400.0F;
    static final float FAR_FADE_TO = 7600.0F;
    /** 遠景の切れ目と谷を閉じ始める・閉じ終わる距離(穴の真ん中から。地面は描画距離の外には無いので、遠くの切れ目の下は空の色になる)。 */
    static final double FAR_CLOSE_FROM = 3000.0;
    static final double FAR_CLOSE_TO = 6000.0;
    private static final float FAR_SEA_TAG = 0.3F;
    private static final float FAR_STORM_TAG = 0.15F;
    /** 丸み(E): この距離より遠い雲を (d − 始まり)² / (2 R) 下げる。 */
    static final float CURVE_FROM = 1200.0F;
    static final float CURVE_RADIUS = 40000.0F;

    private static Window farShown;
    private static VertexBuffer farVbo;
    private static CompletableFuture<Window[]> pendingPair;
    private static boolean farWanted;
    private static float farAlpha;
    /** 外の輪を使う高さか(FAR3_ON_Y / FAR3_OFF_Y)と、遠景の消える距離(外の輪が届くと外へ広げる)。 */
    private static boolean outerWanted;
    private static float farReach = FAR_FADE_TO;
    private static double farAlphaTime;
    private static long farIdSeq;
    private static long lastFarBuildTick;
    private static long lastFarCheck;
    private static int farStormKeyNow;
    private static boolean broken;
    private static long lastLog;
    private static long lastBuildTick;
    /** 作り直しを積乱雲の育ちで詰めすぎない間(tick)。 */
    static final int STORM_REBUILD_TICKS = 200;

    /**
     * ボクセルの雲を描いているか(設定・シェーダー・バニラの雲の設定)。雲の影 {@link CloudShadows} もこれに従う。
     * Distant Horizons があるときはバニラの雲の設定を見ない(DH が自分の雲のために勝手に切るので。2026-10-20、{@link DistantHorizonsCompat})。
     */
    static boolean active() {
        // シェーダーパック使用中はパックの雲を優先し、この MOD の雲と雲の由来の演出(影・光の筋・雲の穴・雲を引く・積乱雲の雷など、この値を見るもの)を止める(10-23)
        return !broken && OvergrownVegetationConfig.COMMON.voxelClouds.get() && ModShaders.voxelClouds != null && !ShaderPacks.cloudsOff()
                && (DistantHorizonsCompat.LOADED || Minecraft.getInstance().options.getCloudsType() != net.minecraft.client.CloudStatus.OFF);
    }

    /**
     * 風の筋の模様をシェーダーへ渡すときの場の座標の折り返し(ブロック。float の細かさを保つため。折り返す所で筋の模様が一度だけずれる。
     * 場は風で 1 時間に 3000 ブロックほどしか動かないので、ふつうは見えない)。
     */
    static final double FIELD_PERIOD = 32768.0;

    /**
     * 荒天の度合い(0〜1。本当の雨は 6 割・雷雨で 1 × 設定 {@code cloudRoughWeather})。雲の上では雨を見せない({@code LevelRainAboveCloudsMixin})が、
     * 雲の色は本当の天気で決める。雲の中の霧の色({@link CloudInterior})も読む。
     */
    static float roughness(ClientLevel level, float partialTick) {
        float rain = com.yayuy.overgrownvegetation.sky.CloudSky.rawRain(level, partialTick);
        float thunder = com.yayuy.overgrownvegetation.sky.CloudSky.rawThunder(level, partialTick);
        return Math.max(rain * 0.6F, thunder) * OvergrownVegetationConfig.COMMON.cloudRoughWeather.get().floatValue();
    }

    /** 天気を入れない時刻だけの雲の色(バニラの {@code ClientLevel#getCloudColor} から雨・雷を除いた式)。 */
    static float[] clearCloudColor(ClientLevel level, float partialTick) {
        float f = Mth.clamp(Mth.cos(level.getTimeOfDay(partialTick) * Mth.TWO_PI) * 2.0F + 0.5F, 0.0F, 1.0F);
        return new float[]{f * 0.9F + 0.1F, f * 0.9F + 0.1F, f * 0.85F + 0.15F};
    }

    /** 今の雲の量({@link com.yayuy.overgrownvegetation.sky.CloudSky}。積乱雲を探すコマンドと同じ式)。 */
    static float coverage(ClientLevel level, float partialTick) {
        return com.yayuy.overgrownvegetation.sky.CloudSky.coverage(level, partialTick);
    }

    /** 場の座標の範囲にかかる積乱雲(雷の光 {@link Thunderstorms} も同じものを引く)。 */
    static java.util.List<CloudField.Storm> storms(ClientLevel level, float partialTick, float coverage,
                                                    double minX, double minZ, double maxX, double maxZ) {
        // 同じフレーム(同じ時刻・同じ partialTick)に同じ範囲で何度も引かれる(雲の影・雲の 2 回の描き分け)ので、その間だけ使い回す
        // (2026-10-19 軽量化。描画のスレッドで積乱雲の升の雑音を毎フレーム 3 回引いていた)。答えは同じ(時刻・天気・範囲だけの関数)。
        if (!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            return com.yayuy.overgrownvegetation.sky.CloudSky.storms(level, partialTick, coverage, minX, minZ, maxX, maxZ);
        }
        StormsKey key = new StormsKey(level, level.getGameTime(), level.getDayTime(), partialTick, coverage, minX, minZ, maxX, maxZ,
                level.getRainLevel(partialTick), level.getThunderLevel(partialTick));
        if (STORMS_MEMO.isEmpty() || !STORMS_MEMO.keySet().iterator().next().sameFrame(key)) {
            STORMS_MEMO.clear();
        }
        java.util.List<CloudField.Storm> memo = STORMS_MEMO.get(key);
        if (memo == null) {
            memo = com.yayuy.overgrownvegetation.sky.CloudSky.storms(level, partialTick, coverage, minX, minZ, maxX, maxZ);
            if (STORMS_MEMO.size() < 8) {
                STORMS_MEMO.put(key, memo);
            }
        }
        return memo;
    }

    private record StormsKey(ClientLevel level, long gameTime, long dayTime, float partialTick, float coverage, double minX, double minZ,
                             double maxX, double maxZ, float rain, float thunder) {
        boolean sameFrame(StormsKey o) {
            return level == o.level && gameTime == o.gameTime && dayTime == o.dayTime && partialTick == o.partialTick;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof StormsKey k && sameFrame(k) && coverage == k.coverage && minX == k.minX && minZ == k.minZ
                    && maxX == k.maxX && maxZ == k.maxZ && rain == k.rain && thunder == k.thunder;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(gameTime, partialTick, coverage, minX, minZ, maxX, maxZ);
        }
    }

    /** {@link #storms} の同じフレームの使い回し(描画のスレッドだけが触る)。 */
    private static final java.util.Map<StormsKey, java.util.List<CloudField.Storm>> STORMS_MEMO = new java.util.LinkedHashMap<>();

    /** 積乱雲の形が変わったかを見る値(塔の天辺と金床の広がりを段で)。 */
    private static int stormKey(java.util.List<CloudField.Storm> storms, double lodX, double lodZ) {
        int key = storms.size();
        for (CloudField.Storm s : storms) {
            key = key * 31 + Math.round(s.maturity() * 32.0F);
            key = key * 31 + (int) (Math.hypot(s.x() - lodX, s.z() - lodZ) / 300.0);
            key = key * 31 + (int) (s.id() ^ (s.id() >>> 32));
        }
        return key;
    }

    /**
     * 雲を描く({@link OverworldCloudEffects#renderClouds} から)。false ならバニラの雲を描かせる(設定でオフ・シェーダーが無い・例外)。
     */
    static boolean render(ClientLevel level, float partialTick, Matrix4f modelView, Matrix4f projection, double camX, double camY, double camZ) {
        if (broken || !OvergrownVegetationConfig.COMMON.voxelClouds.get() || ModShaders.voxelClouds == null || ShaderPacks.cloudsOff()) {
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
     * 描いたら奥行きを消す。遠くの雲はどの地形よりも奥にあるので、地形がそのまま上に描かれて正しく隠れる)。近くの雲は {@link #render} で
     * いつもの奥行きのまま地形の後に描く。最初は遠くの頂点をカメラの方へ縮めて奥の面の手前に収めたが、奥行きの細かさが足りず、遠くの雲がちらついた。
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || !active() || CaveMist.hideSky()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        // 雲の海の切れ目の下の地面(10-18): 空の書き割り(SkyDome は先に描く)の後・遠くの雲の前に、背景として(例外は向こうで止める)。
        FarGround.draw(level, event.getPartialTick().getGameTimeDeltaPartialTick(false), event.getModelViewMatrix(), event.getProjectionMatrix(),
                event.getCamera());
        skyFrame++;
        deferred = false;
        Vec3 cam = event.getCamera().getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        if (deferFarPass()) {
            // 地形の後(AFTER_CUTOUT_BLOCKS)に、空の画素だけへ描く({@link #onAfterCutout})。行列とカメラはここの値を使う。
            deferred = true;
            deferredModelView.set(event.getModelViewMatrix());
            deferredProjection.set(event.getProjectionMatrix());
            deferredCam = cam;
            deferredPartialTick = partialTick;
            return;
        }
        try {
            draw(level, partialTick, event.getModelViewMatrix(), event.getProjectionMatrix(), cam.x, cam.y, cam.z, true);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("ボクセルの雲(遠く)で例外が出たので、このセッションではバニラの雲に戻す", e);
        }
    }

    // ---------- 遠くの雲を地形の後に描く(2026-10-24、半透明の重なりの削減) ----------
    // 空と一緒に地形より先に描くと、林冠に隠れる画素まで重い雲のシェーダーが全部走る(森の中で 4K の GPU 0.6〜0.8 ms、見えているのは画面の 7%)。
    // 地形(不透明・cutout)を描いた後に、主画面の色の絵 + 自分の奥行きの絵の描き先へ描く。自分の奥行きは「主画面の奥行きが空(1.0)なら 1、地形なら 0」で
    // 始める(cloud_sky_mask)ので、地形の画素は奥行きの試しで色を計算する前に落ち、空の画素では雲だけの奥行きの幅で今まで通り前後が決まる。
    // 主画面の奥行きは触らないので、地形が雲より手前に出るのも、雲の後の奥行きを消す手間も今まで通り(結果の絵は同じ)。
    // 森の霧の中(forestFactor > 0)だけ。空の開けた所では隠れる画素が少なく、型を作る 1 回の全画面の分だけ損になる。

    private static long skyFrame;
    private static long cutoutFrame = -1L;
    private static boolean deferred;
    private static final Matrix4f deferredModelView = new Matrix4f();
    private static final Matrix4f deferredProjection = new Matrix4f();
    private static Vec3 deferredCam = Vec3.ZERO;
    private static float deferredPartialTick;
    /** 雲の後に奥行きを消すか(後から描く型では自分の奥行きの絵なので消さない)。 */
    private static boolean maskedPass;
    private static boolean maskBroken;
    private static int maskFbo;
    private static int maskDepth;
    private static int maskWidth;
    private static int maskHeight;
    private static VertexBuffer maskQuad;

    /** 開発用: 前の描き方(空と一緒)に固定する(devbridge の farclouds old|new。同じ起動で前後を比べる)。 */
    private static boolean devOldFarPass;

    public static void devOldFarPass(boolean old) {
        devOldFarPass = old;
    }

    private static boolean deferFarPass() {
        return !devOldFarPass && !maskBroken && ModShaders.cloudSkyMask != null && !ShaderPacks.active()
                // 前のフレームで AFTER_CUTOUT_BLOCKS が来ている(Sodium などで来ないなら前の描き方)
                && cutoutFrame == skyFrame - 1L
                && CorruptionAtmosphere.forestFactor() > 0.0F;
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void onAfterCutout(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            return;
        }
        cutoutFrame = skyFrame;
        if (!deferred) {
            return;
        }
        deferred = false;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || broken) {
            return;
        }
        try {
            drawMasked(level);
        } catch (RuntimeException e) {
            maskBroken = true;
            OvergrownVegetation.LOGGER.error("遠くの雲を地形の後に描けなかったので、空と一緒に描く(前の描き方)に戻す", e);
        }
    }

    private static void drawMasked(ClientLevel level) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int w = main.width;
        int h = main.height;
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int drawTarget = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readTarget = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            if (maskFbo == 0) {
                maskFbo = GL30.glGenFramebuffers();
            }
            if (maskDepth == 0 || maskWidth != w || maskHeight != h) {
                if (maskDepth == 0) {
                    maskDepth = GlStateManager._genTexture();
                }
                RenderSystem.bindTexture(maskDepth);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F, w, h, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.FloatBuffer) null);
                maskWidth = w;
                maskHeight = h;
            }
            if (maskQuad == null) {
                BufferBuilder quad = RenderSystem.renderThreadTesselator().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
                quad.addVertex(-1.0F, -1.0F, 0.0F);
                quad.addVertex(1.0F, -1.0F, 0.0F);
                quad.addVertex(1.0F, 1.0F, 0.0F);
                quad.addVertex(-1.0F, 1.0F, 0.0F);
                maskQuad = new VertexBuffer(VertexBuffer.Usage.STATIC);
                maskQuad.bind();
                maskQuad.upload(quad.buildOrThrow());
                VertexBuffer.unbind();
            }
            // 色は主画面の絵(描画解像度の縮小で中身が替わるので毎回付け直す)、奥行きは自分の絵。
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, maskFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, main.getColorTextureId(), 0);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, maskDepth, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                maskBroken = true;
                OvergrownVegetation.LOGGER.warn("遠くの雲の描き先が作れなかったので、空と一緒に描く(前の描き方)に戻す");
                return;  // このフレームは遠くの雲を描かない(地形の後に前の描き方をすると地形の奥行きを消してしまう)
            }
            GlStateManager._viewport(0, 0, w, h);
            // 型: 主画面の奥行きが空なら 1、地形なら 0。
            ShaderInstance mask = ModShaders.cloudSkyMask;
            mask.setSampler("MainDepth", main.getDepthTextureId());
            RenderSystem.colorMask(false, false, false, false);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.depthFunc(GL11.GL_ALWAYS);
            RenderSystem.disableBlend();
            RenderSystem.disableCull();
            maskQuad.bind();
            maskQuad.drawWithShader(new Matrix4f(), new Matrix4f(), mask);
            VertexBuffer.unbind();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.enableCull();
            maskedPass = true;
            try {
                draw(level, deferredPartialTick, deferredModelView, deferredProjection, deferredCam.x, deferredCam.y, deferredCam.z, true);
            } catch (RuntimeException e) {
                broken = true;
                OvergrownVegetation.LOGGER.error("ボクセルの雲(遠く)で例外が出たので、このセッションではバニラの雲に戻す", e);
            } finally {
                maskedPass = false;
            }
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawTarget);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readTarget);
            GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
        }
    }

    /** 遠くの雲を描くときの雲だけの奥行きの幅(ブロック。10-18 に遠景のため 6000 → 16000、同じ日の夜に外の輪の角(26 km)まで入るよう 28000)。 */
    static final float FAR_NEAR = 64.0F;
    static final float FAR_FAR = 28000.0F;

    private static void draw(ClientLevel level, float partialTick, Matrix4f modelView, Matrix4f projection, double camX, double camY, double camZ,
                             boolean far) {
        if (level != lastLevel) {
            lastLevel = level;
            shown = null;
            discardPending();
        }
        long gameTime = level.getGameTime();
        float coverage = coverage(level, partialTick);
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
        // 巨大な雲は STORM_RANGE まで、ふつうの雲は STORM_NEAR まで。
        java.util.List<CloudField.Storm> all = storms(level, partialTick, coverage, fx - STORM_RANGE, fz - STORM_RANGE, fx + STORM_RANGE, fz + STORM_RANGE);
        java.util.List<CloudField.Storm> storms = new java.util.ArrayList<>();
        for (CloudField.Storm s : all) {
            if (s.giant() || Math.hypot(s.x() - fx, s.z() - fz) < STORM_NEAR + s.reach()) {
                storms.add(s);
            }
        }
        // 遠景(雲の上だけ)。遠景が見えている間は、近くの窓の積乱雲は穴の中のものだけ、雲の床の裾野は近くの全部の積乱雲から。
        if (far) {
            updateFar(gameTime + partialTick, camY);
        }
        Window farNow = farShown != null && (farWanted || farAlpha > 0.0F) ? farShown : null;
        java.util.List<CloudField.Storm> meshStorms = farNow == null ? storms : inHole(all, farNow);
        java.util.List<CloudField.Storm> floorStorms = farNow == null ? storms : all;
        int key = stormKey(meshStorms, fx, fz);
        long farId = farNow == null ? 0L : farNow.id;
        boolean moved = shown == null || Math.abs(cx - shown.centerX) > REBUILD_CELLS || Math.abs(cz - shown.centerZ) > REBUILD_CELLS
                || Math.abs(coverage - shown.coverage) > REBUILD_COVERAGE || shown.farId != farId;
        boolean grown = shown != null && key != shown.stormKey && gameTime - lastBuildTick >= STORM_REBUILD_TICKS;
        boolean busy = pending != null || pendingPair != null;
        if (!busy && far && farWanted && needFar(level, partialTick, gameTime, fx, fz, coverage)) {
            // 遠景と近くの窓を同じ持ち分で一緒に作り、届いたら 2 つ同時に差し替える。
            final float c = coverage;
            final int hx = Math.round((float) (fx / FAR_SNAP)) * FAR_SNAP;
            final int hz = Math.round((float) (fz / FAR_SNAP)) * FAR_SNAP;
            // 高い所では外の輪まで(FAR3_RANGE)。
            final boolean outer = outerWanted;
            final int range = outer ? FAR3_RANGE : FAR_RANGE;
            final java.util.List<CloudField.Storm> farStorms = storms(level, partialTick, coverage, hx - range - 400.0, hz - range - 400.0,
                    hx + range + 400.0, hz + range + 400.0);
            final long id = ++farIdSeq;
            final int farKey = farStormKey(farStorms, hx, hz, outer);
            lastBuildTick = gameTime;
            lastFarBuildTick = gameTime;
            pendingPair = CompletableFuture.supplyAsync(() -> {
                Window fw = buildFar(hx, hz, c, farStorms, farKey, id, outer);
                java.util.List<CloudField.Storm> mine = inHole(all, fw);
                Window nw = build(cx, cz, c, all, mine, stormKey(mine, fx, fz), id);
                return new Window[]{fw, nw};
            }, CloudThread.EXECUTOR);
        } else if (!busy && (moved || grown)) {
            final float c = coverage;
            lastBuildTick = gameTime;
            pending = CompletableFuture.supplyAsync(() -> build(cx, cz, c, floorStorms, meshStorms, key, farId), CloudThread.EXECUTOR);
        }
        if (DEV_LOG && far && gameTime - lastLog >= 100) {
            lastLog = gameTime;
            CloudField.Storm nearest = null;
            CloudField.Storm nearestTower = null;
            for (CloudField.Storm s : storms) {
                if (s.congestus()) {
                    if (nearestTower == null || Math.hypot(s.x() - fx, s.z() - fz) < Math.hypot(nearestTower.x() - fx, nearestTower.z() - fz)) {
                        nearestTower = s;
                    }
                } else if (nearest == null || Math.hypot(s.x() - fx, s.z() - fz) < Math.hypot(nearest.x() - fx, nearest.z() - fz)) {
                    nearest = s;
                }
            }
            // 開発用: 乳房雲・尾流雲を見に行く所(育った金床の真ん中と底の高さ)。
            CloudField.Anvil anvilAt = null;
            CloudField.Storm anvilOf = null;
            for (CloudField.Storm s : all) {
                CloudField.Anvil an = CloudField.anvil(s);
                if (an != null && an.growth() > 0.5F && (anvilAt == null || Math.hypot(an.x() - fx, an.z() - fz) < Math.hypot(anvilAt.x() - fx, anvilAt.z() - fz))) {
                    anvilAt = an;
                    anvilOf = s;
                }
            }
            if (anvilAt != null) {
                OvergrownVegetation.LOGGER.info("VoxelClouds 近い金床 世界 {} {} 底 {} 半径 {} 乳房雲 {} 巨大 {} 塔 {} {}", Math.round(anvilAt.x() + camX - fx),
                        Math.round(anvilAt.z() + camZ - fz), Math.round(anvilAt.floor()), Math.round(anvilAt.radius()), CloudField.hasMammatus(anvilOf), anvilOf.giant(),
                        Math.round(anvilOf.x() + camX - fx), Math.round(anvilOf.z() + camZ - fz));
            }
            if (nearestTower != null) {
                OvergrownVegetation.LOGGER.info("VoxelClouds 近い入道雲 世界 {} {} 太さ {} 天辺 {} 育ち {}", Math.round(nearestTower.x() + camX - fx),
                        Math.round(nearestTower.z() + camZ - fz), Math.round(nearestTower.bodyRadius()), nearestTower.topY(), String.format("%.2f", nearestTower.maturity()));
            }
            // 開発用: 撮る位置を探す(雲の中に入らず、見る向きが開けている所)。
            if (nearest != null) {
                double[] side = clearView(storms, nearest, 700.0, 1300.0, 600.0);
                if (side != null) {
                    OvergrownVegetation.LOGGER.info("VoxelClouds 横から見る所 世界 {} 600 {} 向き {}", Math.round(side[0] + camX - fx),
                            Math.round(side[1] + camZ - fz), Math.round(side[2]));
                }
                double[] high = corridor(storms, fx, fz, 650.0);
                if (high != null) {
                    OvergrownVegetation.LOGGER.info("VoxelClouds 高い所 世界 {} 650 {} 向き {}", Math.round(high[0] + camX - fx), Math.round(high[1] + camZ - fz), Math.round(high[2]));
                }
                double[] lane = corridor(storms, fx, fz, 500.0);
                if (lane != null) {
                    OvergrownVegetation.LOGGER.info("VoxelClouds 通路 世界 {} 500 {} 向き {} 長さ {}", Math.round(lane[0] + camX - fx),
                            Math.round(lane[1] + camZ - fz), Math.round(lane[2]), Math.round(lane[3]));
                }
            }
            outer:
            for (int ring = 0; ring < 12; ring++) {
                for (int k = 0; k < 16; k++) {
                    double a = k * Math.PI / 8.0 + ring * 0.37;
                    double px = fx + Math.cos(a) * ring * 60.0;
                    double pz = fz + Math.sin(a) * ring * 60.0;
                    boolean free = true;
                    for (CloudField.Storm s : storms) {
                        if (Math.abs(px - s.x()) > s.reach() + 80.0 || Math.abs(pz - s.z()) > s.reach() + 80.0) {
                            continue;
                        }
                        java.util.List<CloudField.Puff> pp = CloudField.puffs(s);
                        for (int d = 0; d < 5 && free; d++) {
                            double ox = d == 1 ? 60 : d == 2 ? -60 : 0;
                            double oz = d == 3 ? 60 : d == 4 ? -60 : 0;
                            if (CloudField.inside(pp, px + ox, 500.0, pz + oz)) {
                                free = false;
                            }
                        }
                        if (!free) {
                            break;
                        }
                    }
                    if (free) {
                        OvergrownVegetation.LOGGER.info("VoxelClouds 空いている所 世界 {} 500 {}", Math.round(px + camX - fx), Math.round(pz + camZ - fz));
                        break outer;
                    }
                }
            }
            if (nearest != null) {
                OvergrownVegetation.LOGGER.info("VoxelClouds 近い積乱雲 世界 {} {} 太さ {} 天辺 {} 育ち {} 巨大 {} 金床 {}", Math.round(nearest.x() + camX - fx),
                        Math.round(nearest.z() + camZ - fz), Math.round(nearest.bodyRadius()), nearest.topY(), String.format("%.2f", nearest.maturity()),
                        nearest.giant(), nearest.anvil());
            }
            OvergrownVegetation.LOGGER.info("VoxelClouds 量 {} 日差し {} 光線 {} 積乱雲 {} 雷 {} 面 {} 窓 {},{}", String.format("%.2f", coverage), String.format("%.2f", CloudShadows.sunVisibility()), CrepuscularRays.count(), storms.size(), Thunderstorms.count(),
                    shown == null ? -1 : shown.quads, shown == null ? 0 : shown.centerX, shown == null ? 0 : shown.centerZ);
        }
        boolean nearOk = shown != null && shown.quads > 0 && vbo != null;
        float farShow = farSetting() * farAlpha;
        boolean farOk = farShown != null && farShown.quads > 0 && farVbo != null && farShow > 0.002F;
        // 近くの雲の床を穴の外で捨てる(遠景が受け持つ)のは、近くの窓がその遠景の持ち分で作られている間だけ。
        boolean cut = farOk && shown != null && shown.farId == farShown.id;
        if (!nearOk && !farOk) {
            // 下の層・積乱雲が無くても巻雲は描く(10-18)。
            Cirrus.draw(level, partialTick, modelView, projection, camX, camY, camZ, far, Minecraft.getInstance().gameRenderer.getDepthFar() * 0.9F);
            Virga.draw(level, partialTick, modelView, projection, camX, camY, camZ, far, Minecraft.getInstance().gameRenderer.getDepthFar() * 0.9F);
            CloudWake.draw(level, partialTick, modelView, projection, camX, camY, camZ, far, Minecraft.getInstance().gameRenderer.getDepthFar() * 0.9F);
            return;
        }

        ShaderInstance shader = ModShaders.voxelClouds;
        double dy = CloudField.LOW_BASE - camY;
        float depthFar = Minecraft.getInstance().gameRenderer.getDepthFar();
        float fadeEnd = RADIUS_CELLS * CloudField.CELL_XZ - 24.0F;
        float fadeStart = fadeEnd * 0.7F;
        // 近い・遠いの境(どの地形よりも遠い所)。遠い方は雲だけの奥行きの幅で描く。
        float split = depthFar * 0.9F;
        shader.safeGetUniform("Split").set(split, far ? 1.0F : -1.0F, FAR_NEAR, FAR_FAR);
        boolean floorShown = OvergrownVegetationConfig.COMMON.cumulonimbusStyle.get() >= CloudField.STYLE_VALLEY;
        if (cut) {
            // 遠景が続きを描くので、近くの雲は消さない(穴の外の床は捨てる)。
            stormFadeNow = new float[]{30000.0F, 30100.0F, 30000.0F, 30100.0F};
        } else {
            stormFadeNow = new float[]{STORM_FADE_FROM, STORM_FADE_TO, floorShown ? STORM_FADE_FROM : GIANT_FADE_FROM, floorShown ? STORM_FADE_TO : GIANT_FADE_TO};
        }
        shader.safeGetUniform("StormFade").set(stormFadeNow[0], stormFadeNow[1], stormFadeNow[2], stormFadeNow[3]);
        shader.safeGetUniform("Puff").set(1.0F, 1.0F, 0.0F, 0.0F);
        shader.safeGetUniform("Far").set(farFadeFrom(), farFadeTo(), farShow, cut ? 1.0F : 0.0F);
        if (farShown != null) {
            shader.safeGetUniform("FarHole").set((float) (farShown.holeX - FAR_HOLE - fx), (float) (farShown.holeZ - FAR_HOLE - fz),
                    (float) (farShown.holeX + FAR_HOLE - fx), (float) (farShown.holeZ + FAR_HOLE - fz));
        }
        shader.safeGetUniform("Curve").set(OvergrownVegetationConfig.COMMON.cloudCurvature.get().floatValue(), CURVE_FROM, 0.5F / CURVE_RADIUS, 0.0F);
        float[][] flashes = Thunderstorms.flashUniforms(camX, camY, camZ);
        for (int i = 0; i < flashes.length; i++) {
            shader.safeGetUniform("Flash" + i).set(flashes[i][0], flashes[i][1], flashes[i][2], flashes[i][3]);
        }
        // 雲の中(CloudInterior): 雲の箱も地形と同じ霧に沈め、内側の面は描かない。霧の向こうの積乱雲は灰色の影で少し見せる(巨大な影)。
        float inside = CloudInterior.amount(partialTick) * CloudInterior.setting();
        if (inside > 0.001F) {
            float[] ic = CloudInterior.fogColor(level, partialTick, new Vec3(camX, camY, camZ));
            shader.safeGetUniform("InsideFog").set(CloudInterior.fogFar(Minecraft.getInstance().gameRenderer.getRenderDistance(), partialTick), inside,
                    CloudInterior.SILHOUETTE * CloudInterior.setting() * (1.0F - CloudInterior.storm(partialTick) / Math.max(CloudInterior.amount(partialTick), 1.0E-4F)), 0.0F);
            shader.safeGetUniform("InsideColor").set(ic[0], ic[1], ic[2], 1.0F);
        } else {
            shader.safeGetUniform("InsideFog").set(1000.0F, 0.0F, 0.0F, 0.0F);
        }
        Vec3 cloud = SkyLight.cloudColor(level, partialTick, level.getCloudColor(partialTick));
        Vec3 sky = level.getSkyColor(new Vec3(camX, camY, camZ), partialTick);
        Vec3 sunDir = CanopyLight.sunDirection(level, partialTick);
        boolean moon = sunDir.y < 0.0;
        Vec3 light = moon ? sunDir.scale(-1.0) : sunDir;
        float strength = CanopyLight.lightStrength(level, light, partialTick);
        float warm = moon ? 0.0F : 1.0F - CanopyLight.smooth(Mth.clamp((float) sunDir.y / 0.35F, 0.0F, 1.0F));
        float sr = moon ? CanopyLight.MOON_R : Mth.lerp(warm, 1.0F, 1.0F);
        float sg = moon ? CanopyLight.MOON_G : Mth.lerp(warm, 0.97F, 0.62F);
        float sb = moon ? CanopyLight.MOON_B : Mth.lerp(warm, 0.9F, 0.35F);

        shader.safeGetUniform("CloudColor").set((float) cloud.x, (float) cloud.y, (float) cloud.z, 1.0F);
        shader.safeGetUniform("SunDir").set((float) light.x, (float) light.y, (float) light.z, strength);
        shader.safeGetUniform("SunColor").set(sr, sg, sb, 1.0F);
        shader.safeGetUniform("SkyColor").set((float) sky.x, (float) sky.y, (float) sky.z, 1.0F);
        shader.safeGetUniform("Fade").set(fadeStart, fadeEnd, (float) sunDir.x, (float) sunDir.z);
        // 朝焼け・夕焼け: 空の地平に出るバニラの色を、太陽の側の雲(とくに下から照らされる下面)に映す。
        float[] sunrise = level.effects().getSunriseColor(level.getTimeOfDay(partialTick), partialTick);
        if (sunrise != null) {
            shader.safeGetUniform("Glow").set(sunrise[0], sunrise[1], sunrise[2], sunrise[3]);
        } else {
            shader.safeGetUniform("Glow").set(0.0F, 0.0F, 0.0F, 0.0F);
        }
        // 見た目(10-17 深夜、ユーザーが選んだもの): セル画の陰影・輪郭のやわらかさ・夕焼けで天辺だけ赤く(Look)、雲が雲に落とす影(ShadowA/B)。
        float cel = OvergrownVegetationConfig.COMMON.cloudCelShading.get().floatValue();
        float softEdges = OvergrownVegetationConfig.COMMON.cloudSoftEdges.get().floatValue();
        shader.safeGetUniform("World").set((float) camY, 0.0F, 0.0F, 0.0F);
        // 参考画像に寄せた 3 つ(10-18 ユーザー依頼): 荒天の青灰と切れ目の水色(Rough)・雲の海の風の筋(Field)・遠くの雲の青い霞(Look2)。
        float[] clear = clearCloudColor(level, partialTick);
        shader.safeGetUniform("Rough").set(clear[0], clear[1], clear[2], roughness(level, partialTick));
        // 風の筋の向き = ふだんの向きの風(固定)。今の風の向きにすると、場の座標の原点のまわりで模様ごと回って速く流れて見えた(巻雲と同じ。10-18)。
        double nowWindX = CloudField.WIND_X;
        double nowWindZ = CloudField.WIND_Z;
        double wl = Math.sqrt(nowWindX * nowWindX + nowWindZ * nowWindZ);
        shader.safeGetUniform("Field").set((float) (fx - Math.floor(fx / FIELD_PERIOD) * FIELD_PERIOD),
                (float) (fz - Math.floor(fz / FIELD_PERIOD) * FIELD_PERIOD), (float) (nowWindX / wl), (float) (nowWindZ / wl));
        // 雲の穴(10-18): 近い順に 8 つまで。
        CloudHoles.uniforms(shader, level, partialTick, camX, camY, camZ);
        shader.safeGetUniform("Look2").set(OvergrownVegetationConfig.COMMON.cloudWindStreaks.get().floatValue(),
                OvergrownVegetationConfig.COMMON.cloudBlueHaze.get().floatValue(), OvergrownVegetationConfig.COMMON.cloudTexture.get().floatValue(), 0.0F);
        if (far) {
            CloudSelfShadow.update(level, partialTick, fx, fz, camY, storms, key);
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
        // 1 回目: 奥行きだけ。2 回目: 色(一番手前の面だけが通る)。バニラの雲と同じ。
        // 1 回目は透けない所だけ奥行きを書く(輪郭をやわらかく透かした所は書かない)。2 回目は奥行きを書かずに色を重ねる
        // (透かした縁の向こうの雲も描かれ、縁は空・奥の雲と混ざる。10-17 深夜、輪郭のやわらかさ)。
        // 近くの窓と遠景はどちらの回も続けて描く(奥行きの回で両方の一番手前が決まってから色を塗る)。
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        shader.safeGetUniform("Look").set(cel, softEdges, 1.0F, 1.0F);
        drawMeshes(shader, modelView, projection, nearOk, farOk, fx, fz, dy);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        shader.safeGetUniform("Look").set(cel, softEdges, 1.0F, 0.0F);
        drawMeshes(shader, modelView, projection, nearOk, farOk, fx, fz, dy);
        // 巻雲(10-18): 雲を塗った後に、同じ奥行きの幅で、奥行きを書かずに重ねる(手前の積乱雲には隠れ、上から見ると積乱雲の手前に見える)。
        CloudHoles.drawWalls(level, partialTick, modelView, projection, camX, camY, camZ, far, split);
        Cirrus.draw(level, partialTick, modelView, projection, camX, camY, camZ, far, split);
        // 尾流雲(10-18): 巻雲と同じく、雲を塗った後に奥行きを書かずに重ねる。
        Virga.draw(level, partialTick, modelView, projection, camX, camY, camZ, far, split);
        // 雲を引く(10-19): 雲から出たものが後ろに残す雲のかけら。
        CloudWake.draw(level, partialTick, modelView, projection, camX, camY, camZ, far, split);
        CloudHoles.drawRings(level, partialTick, modelView, projection, camX, camY, camZ, far, split);
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        if (far && !maskedPass) {
            // 地形はこの後にいつもの奥行きで描くので、雲だけの奥行きを消す(空は奥行きを書かないので消しても困らない)。
            RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        }
        if (cloudsTarget != null) {
            Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        }
    }

    /** 近くの窓と遠景の VBO を描く(窓ごとに原点が違うので Offset を替える)。 */
    private static void drawMeshes(ShaderInstance shader, Matrix4f modelView, Matrix4f projection, boolean nearOk, boolean farOk,
                                   double fx, double fz, double dy) {
        if (nearOk) {
            shader.safeGetUniform("Offset").set((float) (shown.originX * (double) CloudField.CELL_XZ - fx), (float) dy,
                    (float) (shown.originZ * (double) CloudField.CELL_XZ - fz));
            vbo.bind();
            vbo.drawWithShader(modelView, projection, shader);
        }
        if (farOk) {
            shader.safeGetUniform("Offset").set((float) (farShown.originX * (double) CloudField.CELL_XZ - fx), (float) dy,
                    (float) (farShown.originZ * (double) CloudField.CELL_XZ - fz));
            farVbo.bind();
            farVbo.drawWithShader(modelView, projection, shader);
        }
        // 吐いた雲(10-19、空の巨大生物が隠れる雲)。それぞれ自分の VBO で、膨らみ・薄れを uniform で渡す。
        CloudPuffs.draw(shader, modelView, projection, fx, fz, dy, stormFadeNow);
        VertexBuffer.unbind();
    }

    /** 今の積乱雲の消える距離(StormFade。吐いた雲を描く間だけ遠くまで出して、戻す)。 */
    private static float[] stormFadeNow = {VoxelClouds.STORM_FADE_FROM, VoxelClouds.STORM_FADE_TO, VoxelClouds.GIANT_FADE_FROM, VoxelClouds.GIANT_FADE_TO};

    /** 吐いた雲 1 つの形(別スレッドで作る。頂点は窓の原点 (originX, originZ)(升)からの位置、高さは雲の底 LOW_BASE から)。 */
    record PuffMesh(MeshData mesh, ByteBufferBuilder bytes, int quads) {
    }

    /** 吐いた雲の塊を箱 v で詰めて、外から見える面を作る({@link CloudPuffs})。x0, z0 = 箱の番号、n = 横の箱の数、ny = 縦の箱の数(LOW_BASE から)。 */
    static PuffMesh buildPuffMesh(java.util.List<CloudField.Puff> puffs, int originX, int originZ, int x0, int z0, int n, int ny, int v) {
        Window w = new Window();
        w.originX = originX;
        w.originZ = originZ;
        ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 18);
        BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        int quads = tileMesh(b, puffs, x0, z0, n, ny, v, STORM_TAG, w);
        MeshData mesh = quads > 0 ? b.build() : null;
        if (mesh == null) {
            bytes.close();
            return null;
        }
        return new PuffMesh(mesh, bytes, quads);
    }

    static float farSetting() {
        return OvergrownVegetationConfig.COMMON.farClouds.get().floatValue();
    }

    /** 遠景がどれだけ見えているか(0〜1。雲の上で遠景が届いてから 1 秒で 1、下りると 1 秒で 0)。 */
    static float farAlpha() {
        return farAlpha;
    }

    /** 高さで遠景を使うかを決め(行き来しないよう 395〜410 で切り替える)、見え方を 1 秒かけて寄せる。下りて消えきったら遠景を手放す。 */
    private static void updateFar(double now, double camY) {
        if (farSetting() <= 0.0F) {
            farWanted = false;
        } else if (camY >= FAR_ON_Y) {
            farWanted = true;
        } else if (camY < FAR_OFF_Y) {
            farWanted = false;
        }
        double dt = Mth.clamp(now - farAlphaTime, 0.0, 5.0);
        farAlphaTime = now;
        boolean ready = farWanted && farShown != null && shown != null && shown.farId == farShown.id;
        float step = (float) dt * 0.05F;
        farAlpha = ready ? Math.min(1.0F, farAlpha + step) : Math.max(0.0F, farAlpha - step);
        if (!farWanted && farAlpha <= 0.0F && farShown != null && pendingPair == null) {
            farShown = null;
        }
        // 外の輪(行き来しないよう 850〜950 で切り替える)。届いたら消える距離を 1 秒に 4000 ブロックずつ外へ広げる(外の雲がぱっと出ない)。
        if (camY >= FAR3_ON_Y) {
            outerWanted = true;
        } else if (camY < FAR3_OFF_Y) {
            outerWanted = false;
        }
        float reachTo = farShown != null && farShown.outer ? FAR3_RANGE - 400.0F : FAR_FADE_TO;
        float move = (float) dt * 200.0F;
        farReach = farReach < reachTo ? Math.min(reachTo, farReach + move) : Math.max(reachTo, farReach - move);
    }

    /** 遠景の消え始め・消え終わりの距離(外の輪があるときは外の輪の端へ広がる)。 */
    static float farFadeFrom() {
        return farReach - (FAR_FADE_TO - FAR_FADE_FROM);
    }

    static float farFadeTo() {
        return farReach;
    }

    /** 遠景が形にする積乱雲か(穴の外・届く範囲。外の輪では巨大な雲と天辺 FAR3_STORM_TOP 以上の塔だけ、入道雲は無し)。 */
    private static boolean farMeshed(int holeX, int holeZ, CloudField.Storm s, boolean outer) {
        if (holeOwns(holeX, holeZ, s)) {
            return false;
        }
        double d = Math.max(Math.abs(s.x() - holeX), Math.abs(s.z() - holeZ));
        if (d < FAR_RANGE + s.reach()) {
            return true;
        }
        return outer && d < FAR3_RANGE + s.reach() && !s.congestus() && (s.giant() || s.top() >= FAR3_STORM_TOP);
    }

    /** 遠景を作り直すか(無い・穴の真ん中から離れた・雲の量が変わった・積乱雲の育ちが変わった)。 */
    private static boolean needFar(ClientLevel level, float partialTick, long gameTime, double fx, double fz, float coverage) {
        if (farShown == null || farShown.outer != outerWanted || farShown.coarse != VisualQuality.coarseFarClouds()) {
            return true;
        }
        if (Math.abs(fx - farShown.holeX) > FAR_MOVE || Math.abs(fz - farShown.holeZ) > FAR_MOVE || Math.abs(coverage - farShown.coverage) > 0.02F) {
            return true;
        }
        if (gameTime - lastFarCheck >= 40L) {
            lastFarCheck = gameTime;
            int range = farShown.outer ? FAR3_RANGE : FAR_RANGE;
            java.util.List<CloudField.Storm> farStorms = storms(level, partialTick, coverage, farShown.holeX - range - 400.0,
                    farShown.holeZ - range - 400.0, farShown.holeX + range + 400.0, farShown.holeZ + range + 400.0);
            farStormKeyNow = farStormKey(farStorms, farShown.holeX, farShown.holeZ, farShown.outer);
        }
        return farStormKeyNow != farShown.stormKey && gameTime - lastFarBuildTick >= FAR_REBUILD_TICKS;
    }

    /** 真ん中が穴の中の積乱雲(近くの窓の持ち分)。 */
    private static java.util.List<CloudField.Storm> inHole(java.util.List<CloudField.Storm> storms, Window far) {
        java.util.List<CloudField.Storm> list = new java.util.ArrayList<>();
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

    /** 遠景の積乱雲の形が変わったかを見る値(穴の外の雲の育ちを 8 段で)。 */
    private static int farStormKey(java.util.List<CloudField.Storm> storms, int holeX, int holeZ, boolean outer) {
        int key = 17;
        for (CloudField.Storm s : storms) {
            if (!farMeshed(holeX, holeZ, s, outer)) {
                continue;
            }
            key = key * 31 + Math.round(s.maturity() * 8.0F);
            key = key * 31 + (int) (s.id() ^ (s.id() >>> 32));
        }
        return key;
    }

    private static void uploadFar(Window w) {
        try {
            if (w.mesh != null) {
                if (farVbo == null) {
                    farVbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
                }
                long t0 = System.nanoTime();
                int bytes = w.mesh.vertexBuffer().remaining();
                farVbo.bind();
                farVbo.upload(w.mesh);
                logUpload("farVbo", bytes, System.nanoTime() - t0);
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
     * 遠景を作る(別スレッド)。雲の海: 穴(FAR_HOLE)〜FAR_MID を 48 ブロック、その外 FAR_RANGE までを 96 ブロックの柱で(粗さの境は柱の境に揃う)。
     * 遠くほど切れ目と谷を閉じる(FAR_CLOSE_FROM〜TO)。積乱雲: 真ん中が穴の外の雲を、タイルの近さで 32・64・128 ブロックの箱に(最初の 24・48・96 は面が 18 万枚で重かった)。
     * outer(高い所)なら外の輪 FAR3_RANGE までを FAR_CELL_3 の柱と 256 ブロックの箱で足す({@link #farMeshed})。
     */
    private static Window buildFar(int holeX, int holeZ, float coverage, java.util.List<CloudField.Storm> all, int stormKey, long id, boolean outer) {
        long started = System.nanoTime();
        // 内の 2 段の雲の海は FAR_RANGE までに届く雲だけで引く(外の輪の雲まで回すと、柱ごとの裾野の計算が重い)。
        java.util.List<CloudField.Storm> storms = new java.util.ArrayList<>();
        for (CloudField.Storm s : all) {
            if (Math.max(Math.abs(s.x() - holeX), Math.abs(s.z() - holeZ)) < FAR_RANGE + s.reach() + 400.0) {
                storms.add(s);
            }
        }
        Window w = new Window();
        w.id = id;
        w.outer = outer;
        w.coarse = VisualQuality.coarseFarClouds();
        // 粗い遠景は内の 2 段の柱を倍に(96・192。穴・粗さの境・外の端は 192 で割り切れ、真ん中は FAR_SNAP = 192 に寄るので柱の境が揃う)。
        // 外の輪(y 950 より上だけ)は 192 のまま —— 384 にすると真ん中の寄せ(192)と柱の境がずれ、内の輪との間に 192 ブロックのすき間ができる。
        int cell1 = w.coarse ? FAR_CELL_1 * 2 : FAR_CELL_1;
        int cell2 = w.coarse ? FAR_CELL_2 * 2 : FAR_CELL_2;
        w.holeX = holeX;
        w.holeZ = holeZ;
        w.centerX = holeX / CloudField.CELL_XZ;
        w.centerZ = holeZ / CloudField.CELL_XZ;
        w.originX = (holeX - FAR_RANGE) / CloudField.CELL_XZ;
        w.originZ = (holeZ - FAR_RANGE) / CloudField.CELL_XZ;
        w.coverage = coverage;
        w.stormKey = stormKey;
        float threshold = CloudField.threshold(coverage);
        int style = com.yayuy.overgrownvegetation.sky.CloudSky.style();
        ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 21);
        BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        int quads = 0;
        int n1 = 2 * FAR_MID / cell1;
        quads += farGrid(b, storms, threshold, coverage, style, w, Math.floorDiv(holeX - FAR_MID, cell1), Math.floorDiv(holeZ - FAR_MID, cell1), n1,
                cell1, Math.floorDiv(holeX - FAR_HOLE, cell1), Math.floorDiv(holeZ - FAR_HOLE, cell1), 2 * FAR_HOLE / cell1);
        int n2 = 2 * FAR_RANGE / cell2;
        quads += farGrid(b, storms, threshold, coverage, style, w, Math.floorDiv(holeX - FAR_RANGE, cell2), Math.floorDiv(holeZ - FAR_RANGE, cell2), n2,
                cell2, Math.floorDiv(holeX - FAR_MID, cell2), Math.floorDiv(holeZ - FAR_MID, cell2), 2 * FAR_MID / cell2);
        java.util.List<CloudField.Storm> mine = new java.util.ArrayList<>();
        for (CloudField.Storm s : all) {
            if (farMeshed(holeX, holeZ, s, outer)) {
                mine.add(s);
            }
        }
        if (outer) {
            // 外の輪(FAR_RANGE〜FAR3_RANGE)の雲の海: 192 ブロックの柱。裾野と底の穴は、外の輪で形にする積乱雲だけで引く。
            java.util.List<CloudField.Storm> outerStorms = new java.util.ArrayList<>();
            for (CloudField.Storm s : mine) {
                if (Math.max(Math.abs(s.x() - holeX), Math.abs(s.z() - holeZ)) + s.reach() > FAR_RANGE) {
                    outerStorms.add(s);
                }
            }
            int n3 = 2 * FAR3_RANGE / FAR_CELL_3;
            quads += farGrid(b, outerStorms, threshold, coverage, style, w, Math.floorDiv(holeX - FAR3_RANGE, FAR_CELL_3), Math.floorDiv(holeZ - FAR3_RANGE, FAR_CELL_3),
                    n3, FAR_CELL_3, Math.floorDiv(holeX - FAR_RANGE, FAR_CELL_3), Math.floorDiv(holeZ - FAR_RANGE, FAR_CELL_3), 2 * FAR_RANGE / FAR_CELL_3);
        }
        int floorQuads = quads;
        quads += farStormTiles(b, mine, w);
        w.quads = quads;
        if (DEV_LOG) {
            OvergrownVegetation.LOGGER.info("VoxelClouds 遠景 {} 面 {}(床 {}) 積乱雲 {} 外の輪 {} 穴 {},{} {} ms", id, quads, floorQuads, mine.size(), outer, holeX, holeZ,
                    (System.nanoTime() - started) / 1000000L);
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

    /** 遠景の雲の海の格子 1 段(floorGrid と同じ並べ方。遠いほど切れ目と谷を閉じる)。 */
    private static int farGrid(BufferBuilder b, java.util.List<CloudField.Storm> storms, float threshold, float coverage, int style, Window w,
                               int gx0, int gz0, int n, int size, int skipX, int skipZ, int skipN) {
        int[] bottom = new int[n * n];
        int[] top = new int[n * n];
        int per = size / CloudField.CELL_XZ;
        boolean floor = style >= CloudField.STYLE_VALLEY;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
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
                // 霞んだ地面(FarGround)を描くときは閉じない: 切れ目の下に地面が見え、晴れた地域が遠くで雲の海に見えてしまうのを避ける(10-18)。
                float close = CanopyLight.smooth((float) Mth.clamp((d - FAR_CLOSE_FROM) / (FAR_CLOSE_TO - FAR_CLOSE_FROM), 0.0, 1.0))
                        * (1.0F - Mth.clamp(FarGround.setting(), 0.0F, 1.0F));
                // 晴れの日の小さい積雲は遠景では作らない(粗い柱では点々の箱になる)。
                CloudField.Column c = CloudField.column(gx * per + per / 2, gz * per + per / 2, threshold, Mth.lerp(close, coverage, 1.0F), storms, style, false);
                if (CloudField.baseCovers(storms, px, pz)) {
                    continue;
                }
                if (c.present()) {
                    // 高さを 8・16 ブロックに丸める(なだらかな所の段が減り、横の面が減る)。
                    int q = size >= FAR_CELL_2 ? 16 : 8;
                    bottom[k] = c.bottom() - CloudField.LOW_BASE;
                    top[k] = Math.max(bottom[k] + q, Math.round((c.top() - CloudField.LOW_BASE) / (float) q) * q);
                } else if (floor && close > 0.5F) {
                    // 遠くの谷は薄い床でふさぐ(谷の底の下は空の色で、線に見える)。
                    bottom[k] = 0;
                    top[k] = 2 * CloudField.CELL_Y;
                }
            }
        }
        float offX = gx0 * (float) size - w.originX * (float) CloudField.CELL_XZ;
        float offZ = gz0 * (float) size - w.originZ * (float) CloudField.CELL_XZ;
        return layer(b, bottom, top, n, n, offX, offZ, FAR_SEA_TAG, size, false);
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
            java.util.List<CloudField.Puff> pp = CloudField.puffs(storms.get(i));
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
                double top = CloudField.LOW_BASE;
                for (int i = 0; i < storms.size(); i++) {
                    double[] bb = box[i];
                    if (bb[2] < tx0 || bb[0] > tx0 + TILE || bb[3] < tz0 || bb[1] > tz0 + TILE) {
                        continue;
                    }
                    here.addAll(puffsOf.get(i));
                    giant |= storms.get(i).giant();
                    top = Math.max(top, bb[4]);
                }
                if (here.isEmpty()) {
                    continue;
                }
                double ddx = Math.max(0.0, Math.max(tx0 - w.holeX, w.holeX - (tx0 + TILE)));
                double ddz = Math.max(0.0, Math.max(tz0 - w.holeZ, w.holeZ - (tz0 + TILE)));
                double near = Math.hypot(ddx, ddz);
                int v = near < 2200.0 ? 32 : near < 4400.0 ? 64 : near < 8000.0 ? 128 : 256;
                if (giant) {
                    // 巨大な雲(10-18 から胴の半径 1300〜1700)は近くでも 48 より細かくしない(面が多すぎる)。
                    v = Math.max(v, GIANT_VOXEL);
                }
                quads += tileMesh(b, here, tx * TILE / v, tz * TILE / v, TILE / v, (int) Math.ceil((top - CloudField.LOW_BASE) / v) + 1, v,
                        FAR_STORM_TAG, w);
            }
        }
        return quads;
    }

    private static void upload(Window w) {
        try {
            if (w.mesh != null) {
                if (vbo == null) {
                    vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
                }
                long t0 = System.nanoTime();
                int bytes = w.mesh.vertexBuffer().remaining();
                vbo.bind();
                vbo.upload(w.mesh);
                logUpload("vbo", bytes, System.nanoTime() - t0);
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

    /** テストクライアント(-Dov.devbridge=true)だけ、2 ms を超えた雲の送り込みを大きさと一緒にログへ出す(2026-10-20、飛行中の引っかかりの調べ)。 */
    private static void logUpload(String which, int bytes, long nanos) {
        if (DEV_UPLOAD_LOG && nanos > 2_000_000L) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("[clouds] {} の送り込み {} ms・{} KB", which, String.format("%.2f", nanos / 1.0E6), bytes / 1024);
        }
    }

    private static final boolean DEV_UPLOAD_LOG = Boolean.getBoolean("ov.devbridge");

    private static void discardPending() {
        farShown = null;
        farAlpha = 0.0F;
        if (pendingPair != null) {
            CompletableFuture<Window[]> p = pendingPair;
            pendingPair = null;
            p.thenAccept(pair -> {
                for (Window w : pair) {
                    if (w.mesh != null) {
                        w.mesh.close();
                    }
                    if (w.bytes != null) {
                        w.bytes.close();
                    }
                }
            });
        }
        if (pending != null) {
            CompletableFuture<Window> p = pending;
            pending = null;
            p.thenAccept(w -> {
                if (w.mesh != null) {
                    w.mesh.close();
                }
                if (w.bytes != null) {
                    w.bytes.close();
                }
            });
        }
    }

    /** カメラのまわりの積乱雲を探す範囲(場の座標、ブロック)。巨大な雲はここまで、ふつうの雲は {@link #STORM_NEAR} まで形にする。 */
    static final double STORM_RANGE = 2600.0;
    static final double STORM_NEAR = 1800.0;
    /** 積乱雲の消え始めと消え終わり(水平距離、ブロック。ふつう・巨大)。 */
    static final float STORM_FADE_FROM = 1450.0F;
    static final float STORM_FADE_TO = 1780.0F;
    static final float GIANT_FADE_FROM = 3400.0F;
    static final float GIANT_FADE_TO = 4000.0F;
    /** 積乱雲の箱の大きさ(ブロック。ふつう・巨大な雲を含むまとまり)。仮決め。 */
    static final int STORM_VOXEL = 16;
    static final int GIANT_VOXEL = 48;
    /** 頂点の色の a: 下の層 / 巨大な積乱雲 / 積乱雲(シェーダーが消え方と陰の付け方を分ける)。 */
    private static final float LOW_TAG = 0.5F;
    private static final float GIANT_TAG = 0.8F;
    /** 巨大な積乱雲のまわりの雲海(下の層の陰の付け方で、消え方は巨大な雲と同じ)。 */
    private static final float SEA_TAG = 0.65F;
    /** 雲海の柱の大きさ(下の層の箱 3 つ分 = 24 ブロック。雲海は窓の外まで広いので粗く)。 */
    static final int SEA_CELLS = 3;
    private static final float STORM_TAG = 1.0F;

    /**
     * 場から柱を引いて、外から見える面の形を作る(別スレッド)。下の層は窓の中だけ(積乱雲の底の所は空ける。巨大な雲のまわりは雲海)、
     * 積乱雲は塊の集まりを 3 次元の箱に詰めて、外から見える面だけ(範囲の重なる雲はひとまとめ)。
     */
    private static Window build(int centerX, int centerZ, float coverage, java.util.List<CloudField.Storm> storms,
                                java.util.List<CloudField.Storm> meshStorms, int stormKey, long farId) {
        Window w = new Window();
        w.centerX = centerX;
        w.centerZ = centerZ;
        w.originX = centerX - RADIUS_CELLS;
        w.originZ = centerZ - RADIUS_CELLS;
        w.coverage = coverage;
        w.stormKey = stormKey;
        w.farId = farId;
        float threshold = CloudField.threshold(coverage);
        int style = com.yayuy.overgrownvegetation.sky.CloudSky.style();
        int cell = CloudField.CELL_XZ;
        int[] bottom = new int[SIZE * SIZE];
        int[] top = new int[SIZE * SIZE];
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                int k = j * SIZE + i;
                double fx = (w.originX + i + 0.5) * cell;
                double fz = (w.originZ + j + 0.5) * cell;
                CloudField.Column c = CloudField.column(w.originX + i, w.originZ + j, threshold, coverage, storms, style);
                if (c.present() && (seaOwned(storms, w.originX + i, w.originZ + j) || !storms.isEmpty() && CloudField.baseCovers(storms, fx, fz))) {
                    c = null;
                }
                bottom[k] = c == null || !c.present() ? 1 : c.bottom() - CloudField.LOW_BASE;
                top[k] = c == null || !c.present() ? 0 : c.top() - CloudField.LOW_BASE;
            }
        }
        ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 20);
        BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        int quads = layer(b, bottom, top, SIZE, SIZE, 0.0F, 0.0F, LOW_TAG, CloudField.CELL_XZ);
        quads += seaMesh(b, storms, threshold, coverage, w);
        quads += stormTiles(b, meshStorms, w);
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

    /** 積乱雲を形にするタイルの大きさ(ブロック。箱 12・24 の倍数)。 */
    static final int TILE = 768;

    /**
     * 積乱雲を場の座標の TILE 四方のタイルごとに形にする(10-17 深夜。それまでは範囲の重なる雲をまとめていたが、雲の海の地形では山がつながって
     * 1 つの大きなまとまりになり、遠くの雲の箱を大きくできなかった)。タイルの中の箱だけを詰め、タイルの外の隣は全部の雲で確かめる
     * (タイルの境に内側の壁を作らない)。窓の真ん中からタイルの近い縁まで FAR_LOD より遠いタイルは箱を倍の大きさに。
     */
    private static int stormTiles(BufferBuilder b, java.util.List<CloudField.Storm> storms, Window w) {
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
            java.util.List<CloudField.Puff> pp = CloudField.puffs(storms.get(i));
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
                double top = CloudField.LOW_BASE;
                for (int i = 0; i < storms.size(); i++) {
                    double[] bb = box[i];
                    if (bb[2] < tx0 || bb[0] > tx0 + TILE || bb[3] < tz0 || bb[1] > tz0 + TILE) {
                        continue;
                    }
                    here.addAll(puffsOf.get(i));
                    giant |= storms.get(i).giant();
                    top = Math.max(top, bb[4]);
                }
                if (here.isEmpty()) {
                    continue;
                }
                double ddx = Math.max(0.0, Math.max(tx0 - cx, cx - (tx0 + TILE)));
                double ddz = Math.max(0.0, Math.max(tz0 - cz, cz - (tz0 + TILE)));
                // 遠くのタイルを粗くするのはやめた(粗さの違うタイルの境で面が食い違い、すき間と段の線が出た)。
                int v = giant ? GIANT_VOXEL : STORM_VOXEL;
                quads += tileMesh(b, here, tx * TILE / v, tz * TILE / v, TILE / v, (int) Math.ceil((top - CloudField.LOW_BASE) / v) + 1, v,
                        giant ? GIANT_TAG : STORM_TAG, w);
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
            for (int iy = iy0; iy <= iy1; iy++) {
                double y = CloudField.LOW_BASE + (iy + 0.5) * v;
                for (int iz = iz0; iz <= iz1; iz++) {
                    double z = (z0 + iz + 0.5) * v;
                    for (int ix = ix0; ix <= ix1; ix++) {
                        int idx = (iy * nz + iz) * nx + ix;
                        if (solid.get(idx)) {
                            continue;
                        }
                        if (CloudField.inside(one, (x0 + ix + 0.5) * v, y, z)) {
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
                        quad(b, 0, 0.2F, tag, occ.edges(ix, iy, iz, 0, ix, iy, iz, ix + 1, iy, iz, ix + 1, iy, iz + 1, ix, iy, iz + 1),
                                px0, py0, pz0, light, px1, py0, pz0, light, px1, py0, pz1, light, px0, py0, pz1, light);
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

    /** これより遠いタイル(窓の真ん中からタイルの近い縁まで)は箱を倍の大きさにする。 */
    static final double FAR_LOD = 700.0;

    /**
     * 雲海の柱の受け持ち: 下の層の箱(場の座標の升 cx, cz)が、雲海の粗い柱(SEA_CELLS 升四方)の受け持ちか。粗い柱の真ん中で雲海の濃さを見る
     * (細かい柱と粗い柱が同じ所を二重に描かないよう、粗い升の単位で決める)。
     */
    private static boolean seaOwned(java.util.List<CloudField.Storm> storms, int cx, int cz) {
        if (floorStyle()) {
            return true;
        }
        int gx = Math.floorDiv(cx, SEA_CELLS);
        int gz = Math.floorDiv(cz, SEA_CELLS);
        double size = SEA_CELLS * (double) CloudField.CELL_XZ;
        return CloudField.sea(storms, (gx + 0.5) * size, (gz + 0.5) * size) >= 0.02F;
    }

    /**
     * 巨大な積乱雲のまわりの雲海を、窓の外まで粗い柱(24 ブロック)で形にする(窓の中も雲海は粗い柱が受け持つ。最初は窓の中だけ細かく作り、
     * 外から見ると雲海が窓の縁で輪になって切れていた)。消え方は巨大な雲と同じ。
     */
    private static int seaMesh(BufferBuilder b, java.util.List<CloudField.Storm> storms, float threshold, float coverage, Window w) {
        int quads = 0;
        int size = SEA_CELLS * CloudField.CELL_XZ;
        if (floorStyle()) {
            // 雲の海の地形(谷): 窓の真ん中のまわり FLOOR_RANGE を 24 ブロックの柱でひと続きに。
            // 最初は近くを 16・遠くを 32 の柱にしていたが、粗さの違う所の境で面が食い違い、平らな雲の海に四角い線が出た(10-17 ユーザー指摘「謎のグリッド」)。
            double cx0 = w.centerX * (double) CloudField.CELL_XZ;
            double cz0 = w.centerZ * (double) CloudField.CELL_XZ;
            int g0x = Mth.floor((cx0 - FLOOR_RANGE) / FLOOR_CELL);
            int g0z = Mth.floor((cz0 - FLOOR_RANGE) / FLOOR_CELL);
            int n = Mth.ceil(2.0 * FLOOR_RANGE / FLOOR_CELL) + 1;
            quads += floorGrid(b, storms, threshold, coverage, w, g0x, g0z, n, FLOOR_CELL, -1, -1, 0);
            return quads;
        }
        for (CloudField.Storm g : storms) {
            if (!g.giant()) {
                continue;
            }
            int gx0 = Mth.floor((g.x() - CloudField.SEA_RADIUS) / size);
            int gz0 = Mth.floor((g.z() - CloudField.SEA_RADIUS) / size);
            int n = Mth.ceil(2.0 * CloudField.SEA_RADIUS / size) + 1;
            int[] bottom = new int[n * n];
            int[] top = new int[n * n];
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    int k = j * n + i;
                    bottom[k] = 1;
                    top[k] = 0;
                    int gx = gx0 + i;
                    int gz = gz0 + j;
                    double cx = (gx + 0.5) * size;
                    double cz = (gz + 0.5) * size;
                    if (CloudField.sea(storms, cx, cz) < 0.02F || nearerGiant(storms, g, cx, cz)) {
                        continue;
                    }
                    CloudField.Column c = CloudField.column(gx * SEA_CELLS + 1, gz * SEA_CELLS + 1, threshold, coverage, storms,
                            com.yayuy.overgrownvegetation.sky.CloudSky.style());
                    if (c.present() && !CloudField.baseCovers(storms, cx, cz)) {
                        bottom[k] = c.bottom() - CloudField.LOW_BASE;
                        top[k] = c.top() - CloudField.LOW_BASE;
                    }
                }
            }
            float offX = gx0 * (float) size - w.originX * (float) CloudField.CELL_XZ;
            float offZ = gz0 * (float) size - w.originZ * (float) CloudField.CELL_XZ;
            quads += layer(b, bottom, top, n, n, offX, offZ, SEA_TAG, size);
        }
        return quads;
    }

    /**
     * 雲の海の地形の柱の格子 1 つ(柱の大きさ size、柱の番号 gx0..gx0+n)。skip* の四角(同じ size の番号)の中は作らない(内側の細かい格子が受け持つ)。
     */
    private static int floorGrid(BufferBuilder b, java.util.List<CloudField.Storm> storms, float threshold, float coverage, Window w,
                                 int gx0, int gz0, int n, int size, int skipX, int skipZ, int skipN) {
        int[] bottom = new int[n * n];
        int[] top = new int[n * n];
        int per = size / CloudField.CELL_XZ;
        int style = com.yayuy.overgrownvegetation.sky.CloudSky.style();
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int k = j * n + i;
                bottom[k] = 1;
                top[k] = 0;
                int gx = gx0 + i;
                int gz = gz0 + j;
                if (skipN > 0 && gx >= skipX && gx < skipX + skipN && gz >= skipZ && gz < skipZ + skipN) {
                    continue;
                }
                CloudField.Column c = CloudField.column(gx * per + per / 2, gz * per + per / 2, threshold, coverage, storms, style);
                if (c.present() && !CloudField.baseCovers(storms, (gx + 0.5) * size, (gz + 0.5) * size)) {
                    bottom[k] = c.bottom() - CloudField.LOW_BASE;
                    top[k] = c.top() - CloudField.LOW_BASE;
                }
            }
        }
        float offX = gx0 * (float) size - w.originX * (float) CloudField.CELL_XZ;
        float offZ = gz0 * (float) size - w.originZ * (float) CloudField.CELL_XZ;
        return layer(b, bottom, top, n, n, offX, offZ, SEA_TAG, size);
    }

    /** 雲の床のある並び方(谷・壁と橋)か(10-18 から近くに積乱雲が無くても床を出す)。 */
    private static boolean floorStyle() {
        return com.yayuy.overgrownvegetation.sky.CloudSky.style() >= CloudField.STYLE_VALLEY;
    }

    /** 雲の海の地形の柱の大きさ(ブロック。下の層の箱 3 つ分)。 */
    static final int FLOOR_CELL = 24;

    /** 雲の床を形にする範囲(窓の真ん中から、ブロック)。 */
    static final double FLOOR_RANGE = 1800.0;

    /** ほかの巨大な雲の方が近い所(2 つの雲海が重なる所は近い方だけが受け持つ)。 */
    private static boolean nearerGiant(java.util.List<CloudField.Storm> storms, CloudField.Storm g, double x, double z) {
        double d = Math.hypot(x - g.x(), z - g.z());
        for (CloudField.Storm o : storms) {
            if (o != g && o.giant() && Math.hypot(x - o.x(), z - o.z()) < d) {
                return true;
            }
        }
        return false;
    }

    /** 開発用: その点がどれかの積乱雲の中か。 */
    private static boolean inAny(java.util.List<CloudField.Storm> storms, double x, double y, double z) {
        if (floorStyle() && y < CloudField.LOW_BASE + CloudField.landscape(x, z, storms, 1.0F) + 20.0) {
            return true;
        }
        for (CloudField.Storm s : storms) {
            if (Math.abs(x - s.x()) > s.reach() + 40.0 || Math.abs(z - s.z()) > s.reach() + 40.0) {
                continue;
            }
            if (CloudField.inside(CloudField.puffs(s), x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /** 開発用: a から b までの線が雲を通らないか(target の雲は最後の 3 割で入ってよい)。 */
    private static boolean clearLine(java.util.List<CloudField.Storm> storms, double ax, double ay, double az, double bx, double by, double bz, double until) {
        for (int i = 0; i <= 40; i++) {
            double t = i / 40.0 * until;
            if (inAny(storms, Mth.lerp(t, ax, bx), Mth.lerp(t, ay, by), Mth.lerp(t, az, bz))) {
                return false;
            }
        }
        return true;
    }

    /** 開発用: target を横から見る位置(距離 near〜far、高さ y。カメラから雲の手前 3 割まで何も無い)と向き(MC の yaw)。 */
    private static double[] clearView(java.util.List<CloudField.Storm> storms, CloudField.Storm target, double near, double far, double y) {
        double ty = (CloudField.LOW_BASE + target.topY()) * 0.5;
        for (double d = near; d <= far; d += 150.0) {
            for (int k = 0; k < 16; k++) {
                double a = k * Math.PI / 8.0;
                double px = target.x() + Math.cos(a) * d;
                double pz = target.z() + Math.sin(a) * d;
                if (clearLine(storms, px, y, pz, target.x(), ty, target.z(), 0.7)) {
                    double yaw = Math.toDegrees(Math.atan2(-(target.x() - px), target.z() - pz));
                    return new double[]{px, pz, yaw};
                }
            }
        }
        return null;
    }

    /** 開発用: (fx, fz) のまわりで高さ y の、いちばん長く開けた向きの通路(位置・向き・長さ)。 */
    private static double[] corridor(java.util.List<CloudField.Storm> storms, double fx, double fz, double y) {
        double[] best = null;
        for (int ring = 0; ring < 6; ring++) {
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4.0 + ring;
                double px = fx + Math.cos(a) * ring * 80.0;
                double pz = fz + Math.sin(a) * ring * 80.0;
                if (inAny(storms, px, y, pz)) {
                    continue;
                }
                for (int dk = 0; dk < 16; dk++) {
                    double da = dk * Math.PI / 8.0;
                    double len = 0.0;
                    while (len < 900.0 && !inAny(storms, px + Math.cos(da) * (len + 40.0), y, pz + Math.sin(da) * (len + 40.0))) {
                        len += 40.0;
                    }
                    // 長すぎる(雲の無い方を向いた)より、300〜700 で雲に当たる通路がよい。
                    double score = len >= 900.0 ? 200.0 : len;
                    if (best == null || score > best[4]) {
                        best = new double[]{px, pz, Math.toDegrees(Math.atan2(-Math.cos(da), Math.sin(da))), len, score};
                    }
                }
            }
        }
        return best;
    }

    /** 箱の番号 index(大きさ v)の角の、窓の原点からの位置(倍精度で出してから float に。同じ角はいつも同じ値になる)。 */
    private static float edge(int index, int v, int windowOrigin) {
        return (float) ((double) index * v - (double) windowOrigin * CloudField.CELL_XZ);
    }

    private static boolean filled(java.util.BitSet solid, int nx, int ny, int nz, int ix, int iy, int iz) {
        return ix >= 0 && iy >= 0 && iz >= 0 && ix < nx && iy < ny && iz < nz && solid.get((iy * nz + iz) * nx + ix);
    }

    /**
     * 1 段の柱(n × m の底・天辺の配列、(offX, offZ) から並ぶ)の外から見える面を足す。横の面は同じ段の隣の柱が覆っていない高さだけ。
     * tag = 頂点の色の a(下の層か積乱雲か)。
     */
    private static int layer(BufferBuilder b, int[] bottom, int[] top, int n, int m, float offX, float offZ, float tag, int s) {
        return layer(b, bottom, top, n, m, offX, offZ, tag, s, true);
    }

    /** bottoms = false なら下面を作らない(遠景はカメラが雲より上にあるときだけ描くので、雲の海の下面は見えない)。 */
    private static int layer(BufferBuilder b, int[] bottom, int[] top, int n, int m, float offX, float offZ, float tag, int s, boolean bottoms) {
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
                float h0 = y0 / layer;
                float h1 = y1 / layer;
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
                        quads += side(b, d, thin, tag, layer, x0, x1, z0, z1, y0, y1, bottom, top, n, m, i, j);
                    } else {
                        if (y0 < nb) {
                            quads += side(b, d, thin, tag, layer, x0, x1, z0, z1, y0, Math.min(y1, nb), bottom, top, n, m, i, j);
                        }
                        if (y1 > nt) {
                            quads += side(b, d, thin, tag, layer, x0, x1, z0, z1, Math.max(y0, nt), y1, bottom, top, n, m, i, j);
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
    private static int side(BufferBuilder b, int d, float thin, float tag, float layer, float x0, float x1, float z0, float z1, int ya, int yb,
                            int[] bottom, int[] top, int n, int m, int i, int j) {
        if (yb <= ya) {
            return 0;
        }
        float ha = ya / layer;
        float hb = yb / layer;
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

    /** 積乱雲のタイルの箱の詰まり(タイルの外の横の隣は塊で確かめて覚える。上下の外は空)と、角の開き。 */
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

    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    private VoxelClouds() {
    }
}
