package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 汚染地域の<b>漂う胞子を GPU で動かす</b>(2026-10-23、ユーザー採用「粒を GPU で動かす」、動きの作り方は (a) を選択)。
 *
 * <p>前はバイオームの粒({@link DriftingSporeParticle}、上限 1000 粒)を 1 粒ずつ tick して描いていた。計測(bench_real2・描画距離 16・1080p)では
 * 描画のスレッドの 3.7%(うち明るさを引く {@code getLightColor} が 2%)。ここでは粒の「枠」を固定数並べた頂点の束を 1 回だけ作り、
 * 位置・明るさ・色は頂点シェーダー({@code shaders/core/gpu_spores.vsh})が時刻と枠の番号から計算する。CPU が毎 tick するのは:
 * <ul>
 *   <li><b>風に押された量</b>: カメラのまわり 16 × 16 升(8 ブロック)ごとに、胞子が風({@link SporeDrift#flow})で流される速さを毎 tick 積み、
 *   1024 tick ぶんの履歴を 3D テクスチャに置く。粒は「今の押された量 − 生まれた時の押された量」だけ流れる。生まれた升の風で動くので、
 *   近くの粒は突風で一斉に流れる(突風の通った所から順に)。渦と沈みは式(時刻の sin)で足す。</li>
 *   <li><b>明るさと湧く所</b>: カメラのまわり 16 × 16 × 16 升(4 ブロック)の空の光・ブロックの光・湧きやすさ(漂う胞子のバイオームで、空気のマス)を
 *   小さな 3D テクスチャへ。升は世界座標で折り返して使い回し、カメラが動いて入れ替わった升と、毎 tick {@value #LIGHT_ROUND_ROBIN} 升ずつを読み直す。</li>
 *   <li>光の筋({@link CanopyLight#fillShafts})を uniform に詰める(筋の中で緑のまま明るく光る判定はシェーダーで)。</li>
 * </ul>
 * 粒は 25〜50 秒の周期で生まれ直し、そのたびにカメラを囲む入れ子の箱({@link #MAX_SLOTS})の中の新しい所に現れる。
 * 箱は世界に固定で、カメラが動くと端で折り返す(端の 4 ブロックは薄くして見せない)。
 *
 * <p>Iris のシェーダーパックを使っている間・シェーダーが読めなかったとき・開発用の {@code gpuspores off} の間は描かず、
 * {@link DriftingSporeParticle.Provider} が今までの粒を返す({@link #replacesParticles})。数は品質の段階の「粒の量」と粒子設定に従う。数値は仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class GpuSpores {

    /**
     * 枠の数(粒の量 100% のとき)。バニラの漂う胞子は上限 1000 粒が全部空気の中にいるが、束の枠は地面の中に当たると地形に隠れて見えないので
     * (森の箱のおよそ半分)、倍にしてほぼ同じ数にする。箱は入れ子(±8 に 19%・±16 に 48%・±32 に 33%)で、バニラの 2 つの三角の分布の
     * 「近いほど濃い」に合わせた(最初は ±16 と ±32 の半分ずつで、足元の粒がバニラの 5 分の 1 ほどしかなかった)。
     * 2048 では葉の茂った斜面でバニラの 1.3 分の 1 ほどだったので、ユーザー指定(10-23)で 3072 に上げた。品質の段階では
     * 粒の量(高 100%・中 70%・低 50%)を掛けて 3072・2150・1536 枠。
     */
    private static final int MAX_SLOTS = 3072;
    /** 光の筋の中の光る胞子: 筋 1 本の組の枠の数(CPU 版の上限 420 を 14 本で分けても足りるように)。 */
    private static final int MOTES_PER_GROUP = 64;
    /** 風の升の一辺(ブロック)と、升の数(一辺)。 */
    private static final int WIND_CELL = 8;
    private static final int WIND_N = 16;
    /** 風の履歴の長さ(tick)。粒の寿命の上限(1000)より長く。 */
    private static final int HISTORY = 1024;
    /** 明るさの升の一辺(ブロック)と、升の数(一辺)。 */
    private static final int LIGHT_CELL = 4;
    private static final int LIGHT_N = 16;
    private static final int LIGHT_TEXELS = LIGHT_N * LIGHT_N * LIGHT_N;
    /** 毎 tick 読み直す明るさの升の数(全部で約 3 秒)。128 では JFR で描画のスレッドの 1.1% を光の読み出しが使っていた。 */
    private static final int LIGHT_ROUND_ROBIN = 64;
    /** カメラが動いて入れ替わった升を 1 tick に読む上限(残りは次の tick)。 */
    private static final int LIGHT_FRESH_BUDGET = 768;
    /** 座標を折り返す幅(float の精度のため。風の升・明るさの升・箱の幅の公倍数)。 */
    private static final double WRAP = 2048.0;
    private static final int MAX_SHAFTS = 14;
    private static final ResourceLocation SPRITE = ResourceLocation.withDefaultNamespace("drip_fall");

    /** 開発用(テストクライアントの {@code gpuspores on|off}): false で今までの粒に戻す(撮り比べ・計り比べ)。 */
    public static volatile boolean enabled = true;

    private static ClientLevel level;
    private static long ticks;
    private static boolean broken;
    /** この tick に束で描くか({@link #replacesParticles} が粒ごとに読む)。 */
    private static boolean active;
    /** 湧きやすさが 0 でない明るさの升の数(0 なら描かない)。 */
    private static int densityTexels;

    // 風の履歴(升ごとの押された量)。
    private static final int[] windKeyX = new int[WIND_N * WIND_N];
    private static final int[] windKeyZ = new int[WIND_N * WIND_N];
    private static final double[] accX = new double[WIND_N * WIND_N];
    private static final double[] accZ = new double[WIND_N * WIND_N];
    private static final float[] strength = new float[WIND_N * WIND_N];
    /** テクスチャと同じ中身(押された量を一斉にずらすときに丸ごと上げ直すため)。[時刻][z][x][4]。 */
    private static float[] windMirror;
    private static final double[] FLOW = new double[2];

    // 明るさの升。
    private static final int[] lightKeyX = new int[LIGHT_TEXELS];
    private static final int[] lightKeyY = new int[LIGHT_TEXELS];
    private static final int[] lightKeyZ = new int[LIGHT_TEXELS];
    private static final byte[] lightData = new byte[LIGHT_TEXELS * 4];
    private static int lightCursor;
    private static boolean lightDirty;
    private static final Map<Biome, Boolean> SPORE_BIOMES = new IdentityHashMap<>();

    // GL の物(描画のスレッドだけが触る)。
    private static int windTex = -1;
    private static int lightTex = -1;
    private static VertexBuffer vbo;
    private static ByteBuffer lightUpload;
    private static FloatBuffer rowUpload;
    private static FloatBuffer columnUpload;
    private static FloatBuffer shaftUpload;
    private static final float[] SHAFTS = new float[MAX_SHAFTS * 8];
    private static ShaderInstance locShader;
    private static int locLight = -1;
    private static int locWind = -1;
    private static int locShafts = -1;
    private static int locShaftCount = -1;
    // 光る胞子の束。
    private static VertexBuffer moteVbo;
    private static FloatBuffer moteUpload;
    private static final float[] MOTE_GROUPS = new float[MAX_SHAFTS * 12];
    private static final float[] MOTE_LIGHT = new float[8];
    private static ShaderInstance moteLocShader;
    private static int moteLocWind = -1;
    private static int moteLocGroups = -1;
    private static int moteLocGroupCount = -1;

    /** 漂う胞子の粒({@link DriftingSporeParticle})を作らずに済むか(束が代わりに描いている)。 */
    public static boolean replacesParticles() {
        return active;
    }

    /** 光の筋の中の光る胞子を CPU で持たずに済むか({@link CanopyLight#tickMotes} が読む。束のシェーダーが読めたときだけ)。 */
    static boolean replacesMotes() {
        return active && ModShaders.gpuMotes != null;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel now = mc.level;
        if (now != level) {
            level = now;
            reset();
        }
        active = false;
        if (now == null || mc.player == null || broken || !enabled || ModShaders.gpuSpores == null || ShaderPacks.active()) {
            return;
        }
        if (mc.isPaused()) {
            active = true;
            return;
        }
        try {
            ensureGl();
            Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
            ticks++;
            tickWind(cam);
            tickLight(now, cam);
            active = true;
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("漂う胞子の束の準備で例外が出たので、このセッションではバニラの粒に戻す", e);
        }
    }

    private static void reset() {
        ticks = 0;
        densityTexels = 0;
        lightCursor = 0;
        SPORE_BIOMES.clear();
        java.util.Arrays.fill(windKeyX, Integer.MIN_VALUE);
        java.util.Arrays.fill(windKeyZ, Integer.MIN_VALUE);
        java.util.Arrays.fill(accX, 0.0);
        java.util.Arrays.fill(accZ, 0.0);
        java.util.Arrays.fill(strength, 0.0F);
        java.util.Arrays.fill(lightKeyX, Integer.MIN_VALUE);
        java.util.Arrays.fill(lightData, (byte) 0);
        if (windMirror != null) {
            java.util.Arrays.fill(windMirror, 0.0F);
            uploadWindAll();
        }
        lightDirty = true;
    }

    // ---- 風 ----

    private static void tickWind(Vec3 cam) {
        int c0x = floorDiv(cam.x, WIND_CELL) - WIND_N / 2;
        int c0z = floorDiv(cam.z, WIND_CELL) - WIND_N / 2;
        double time = CanopyWind.time(0.0F);
        int row = (int) Math.floorMod(ticks, (long) HISTORY);
        // 新しく入った升の押された量は、カメラの升の値から始める(値の大きさを揃えるだけ。差しか使わない)。
        int camIndex = index(Math.floorMod(c0x + WIND_N / 2, WIND_N), Math.floorMod(c0z + WIND_N / 2, WIND_N));
        double startX = windKeyX[camIndex] == c0x + WIND_N / 2 && windKeyZ[camIndex] == c0z + WIND_N / 2 ? accX[camIndex] : 0.0;
        double startZ = windKeyX[camIndex] == c0x + WIND_N / 2 && windKeyZ[camIndex] == c0z + WIND_N / 2 ? accZ[camIndex] : 0.0;
        for (int i = 0; i < WIND_N; i++) {
            for (int k = 0; k < WIND_N; k++) {
                int wx = c0x + i;
                int wz = c0z + k;
                int idx = index(Math.floorMod(wx, WIND_N), Math.floorMod(wz, WIND_N));
                if (windKeyX[idx] != wx || windKeyZ[idx] != wz) {
                    // 別の升が入った: 履歴を全部いまの値にする(この升で生まれた粒は、入る前の分は押されていない)。
                    windKeyX[idx] = wx;
                    windKeyZ[idx] = wz;
                    accX[idx] = startX;
                    accZ[idx] = startZ;
                    fillColumn(idx);
                }
                double w = SporeDrift.flow((wx + 0.5) * WIND_CELL, (wz + 0.5) * WIND_CELL, time, FLOW);
                accX[idx] += FLOW[0];
                accZ[idx] += FLOW[1];
                strength[idx] = (float) w;
                int m = (row * WIND_N * WIND_N + idx) * 4;
                windMirror[m] = (float) accX[idx];
                windMirror[m + 1] = (float) accZ[idx];
                windMirror[m + 2] = strength[idx];
            }
        }
        // 押された量が大きくなりすぎたら(float の精度)、全部を同じだけずらす。差は変わらない。
        if (Math.abs(startX) > 1024.0 || Math.abs(startZ) > 1024.0) {
            float ox = (float) (Math.floor(startX / 256.0) * 256.0);
            float oz = (float) (Math.floor(startZ / 256.0) * 256.0);
            for (int i = 0; i < accX.length; i++) {
                accX[i] -= ox;
                accZ[i] -= oz;
            }
            for (int m = 0; m < windMirror.length; m += 4) {
                windMirror[m] -= ox;
                windMirror[m + 1] -= oz;
            }
            uploadWindAll();
            return;
        }
        uploadWindRow(row);
    }

    private static int index(int x, int z) {
        return z * WIND_N + x;
    }

    private static void fillColumn(int idx) {
        float x = (float) accX[idx];
        float z = (float) accZ[idx];
        columnUpload.clear();
        for (int t = 0; t < HISTORY; t++) {
            int m = (t * WIND_N * WIND_N + idx) * 4;
            windMirror[m] = x;
            windMirror[m + 1] = z;
            windMirror[m + 2] = 0.0F;
            windMirror[m + 3] = 0.0F;
            columnUpload.put(x).put(z).put(0.0F).put(0.0F);
        }
        columnUpload.flip();
        bindForUpload(windTex);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, idx % WIND_N, idx / WIND_N, 0, 1, 1, HISTORY, GL11.GL_RGBA, GL11.GL_FLOAT, columnUpload);
        unbindUpload();
    }

    private static void uploadWindRow(int row) {
        rowUpload.clear();
        rowUpload.put(windMirror, row * WIND_N * WIND_N * 4, WIND_N * WIND_N * 4);
        rowUpload.flip();
        bindForUpload(windTex);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, row, WIND_N, WIND_N, 1, GL11.GL_RGBA, GL11.GL_FLOAT, rowUpload);
        unbindUpload();
    }

    private static void uploadWindAll() {
        FloatBuffer all = MemoryUtil.memAllocFloat(windMirror.length);
        try {
            all.put(windMirror).flip();
            bindForUpload(windTex);
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_RGBA32F, WIND_N, WIND_N, HISTORY, 0, GL11.GL_RGBA, GL11.GL_FLOAT, all);
            unbindUpload();
        } finally {
            MemoryUtil.memFree(all);
        }
    }

    // ---- 明るさと湧く所 ----

    private static void tickLight(ClientLevel lvl, Vec3 cam) {
        int c0x = floorDiv(cam.x, LIGHT_CELL) - LIGHT_N / 2;
        int c0y = floorDiv(cam.y, LIGHT_CELL) - LIGHT_N / 2;
        int c0z = floorDiv(cam.z, LIGHT_CELL) - LIGHT_N / 2;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int fresh = 0;
        for (int t = 0; t < LIGHT_TEXELS && fresh < LIGHT_FRESH_BUDGET; t++) {
            int tx = t % LIGHT_N;
            int ty = (t / LIGHT_N) % LIGHT_N;
            int tz = t / (LIGHT_N * LIGHT_N);
            int wx = c0x + Math.floorMod(tx - c0x, LIGHT_N);
            int wy = c0y + Math.floorMod(ty - c0y, LIGHT_N);
            int wz = c0z + Math.floorMod(tz - c0z, LIGHT_N);
            if (lightKeyX[t] != wx || lightKeyY[t] != wy || lightKeyZ[t] != wz) {
                sampleLight(lvl, pos, t, wx, wy, wz);
                fresh++;
            }
        }
        for (int n = 0; n < LIGHT_ROUND_ROBIN; n++) {
            int t = lightCursor;
            lightCursor = (lightCursor + 1) % LIGHT_TEXELS;
            if (lightKeyX[t] != Integer.MIN_VALUE) {
                sampleLight(lvl, pos, t, lightKeyX[t], lightKeyY[t], lightKeyZ[t]);
            }
        }
        if (lightDirty) {
            lightDirty = false;
            int count = 0;
            for (int t = 0; t < LIGHT_TEXELS; t++) {
                if (lightData[t * 4 + 2] != 0) {
                    count++;
                }
            }
            densityTexels = count;
            lightUpload.clear();
            lightUpload.put(lightData).flip();
            bindForUpload(lightTex);
            GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0, LIGHT_N, LIGHT_N, LIGHT_N, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, lightUpload);
            unbindUpload();
        }
    }

    /** 升の真ん中のブロックの光と湧きやすさを読む(真ん中が固いブロックなら光は 2 つ上で読む)。 */
    private static void sampleLight(ClientLevel lvl, BlockPos.MutableBlockPos pos, int t, int wx, int wy, int wz) {
        lightKeyX[t] = wx;
        lightKeyY[t] = wy;
        lightKeyZ[t] = wz;
        int x = wx * LIGHT_CELL + LIGHT_CELL / 2;
        int y = wy * LIGHT_CELL + LIGHT_CELL / 2;
        int z = wz * LIGHT_CELL + LIGHT_CELL / 2;
        int o = t * 4;
        pos.set(x, y, z);
        if (!lvl.hasChunkAt(pos) || lvl.isOutsideBuildHeight(pos)) {
            write(o, 0, 0, 0, 0);
            return;
        }
        // 湧きやすさはバイオームだけで決める。最初は真ん中が固いブロックなら湧かせなかったが、葉の茂った斜面では升の真ん中が葉に当たりやすく、
        // バニラ(葉の間の空気に湧く)の 3 分の 1 ほどしか出なかった(10-23 の撮り比べ)。地面の中の粒は地形に隠れて見えない。
        float density = 1.0F;
        BlockState state = lvl.getBlockState(pos);
        if (state.isCollisionShapeFullBlock(lvl, pos)) {
            // 光は固いブロックの中では 0 なので、2 つ上の空気から読む。
            pos.set(x, y + 2, z);
        }
        int sky = lvl.getBrightness(LightLayer.SKY, pos);
        int block = lvl.getBrightness(LightLayer.BLOCK, pos);
        if (density > 0.0F && !sporeBiome(lvl.getBiome(pos).value())) {
            density = 0.0F;
        }
        write(o, sky * 17, block * 17, Math.round(density * 255.0F), 255);
    }

    private static void write(int o, int r, int g, int b, int a) {
        if (lightData[o] != (byte) r || lightData[o + 1] != (byte) g || lightData[o + 2] != (byte) b || lightData[o + 3] != (byte) a) {
            lightData[o] = (byte) r;
            lightData[o + 1] = (byte) g;
            lightData[o + 2] = (byte) b;
            lightData[o + 3] = (byte) a;
            lightDirty = true;
        }
    }

    /** バイオームの環境の粒が漂う胞子か。 */
    private static boolean sporeBiome(Biome biome) {
        return SPORE_BIOMES.computeIfAbsent(biome, b -> b.getAmbientParticle()
                .map(settings -> settings.getOptions().getType() == ModParticles.DRIFTING_SPORE.get())
                .orElse(false));
    }

    // ---- 描く ----

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !active || vbo == null) {
            return;
        }
        ShaderInstance shader = ModShaders.gpuSpores;
        if (shader == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int count = slotCount(mc);
        try {
            if (count > 0 && densityTexels > 0) {
                render(event, mc, shader, count);
            }
            if (ModShaders.gpuMotes != null) {
                // 光る粒は加算なので、ファビュラスでは粒の層でなく主画面へ(CanopyLight#drawToMainTarget)。ふつうの胞子は粒の層のまま。
                boolean main = CanopyLight.drawToMainTarget();
                try {
                    renderMotes(event, mc, ModShaders.gpuMotes);
                } finally {
                    CanopyLight.restoreTarget(main);
                }
            }
        } catch (RuntimeException e) {
            broken = true;
            active = false;
            OvergrownVegetation.LOGGER.error("漂う胞子の束の描画で例外が出たので、このセッションではバニラの粒に戻す", e);
        }
    }

    /** 描く枠の数: 品質の段階の「粒の量」と粒子設定(減少 = 2/3、最小 = 0。バニラの粒の作り方と同じ割合)。 */
    private static int slotCount(Minecraft mc) {
        ParticleStatus status = mc.options.particles().get();
        if (status == ParticleStatus.MINIMAL) {
            return 0;
        }
        float k = VisualQuality.particles() * (status == ParticleStatus.DECREASED ? 2.0F / 3.0F : 1.0F);
        return Math.min(MAX_SLOTS, Math.round(MAX_SLOTS * k));
    }

    private static void render(RenderLevelStageEvent event, Minecraft mc, ShaderInstance shader, int count) {
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        if (mc.isPaused()) {
            partialTick = 0.0F;
        }
        TextureAtlas atlas = (TextureAtlas) mc.getTextureManager().getTexture(TextureAtlas.LOCATION_PARTICLES);
        TextureAtlasSprite sprite = atlas.getSprite(SPRITE);
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        int shafts = CanopyLight.fillShafts(SHAFTS, cam.x, cam.y, cam.z);

        shader.safeGetUniform("CamMod").set((float) Math.floorMod((long) Math.floor(cam.x), (long) WRAP) + (float) (cam.x - Math.floor(cam.x)),
                (float) cam.y,
                (float) Math.floorMod((long) Math.floor(cam.z), (long) WRAP) + (float) (cam.z - Math.floor(cam.z)), 0.0F);
        double windRow = (ticks - 1 + partialTick) % HISTORY;
        shader.safeGetUniform("Clock").set((float) (ticks + partialTick), (float) windRow, (float) count, ForestPresence.underground());
        shader.safeGetUniform("CamLeft").set(left.x(), left.y(), left.z(), CanopyLight.daylightNow());
        shader.safeGetUniform("CamUp").set(up.x(), up.y(), up.z(), ForestPresence.highSkyFrom());
        shader.safeGetUniform("Sprite").set(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());

        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES);
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        vbo.bind();
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, event.getModelViewMatrix(), event.getProjectionMatrix(), mc.getWindow());
        shader.apply();
        if (locShader != shader) {
            locShader = shader;
            int program = shader.getId();
            locLight = GL20.glGetUniformLocation(program, "LightVol");
            locWind = GL20.glGetUniformLocation(program, "WindHist");
            locShafts = GL20.glGetUniformLocation(program, "Shafts");
            locShaftCount = GL20.glGetUniformLocation(program, "ShaftCount");
        }
        GL20.glUniform1i(locLight, 6);
        GL20.glUniform1i(locWind, 7);
        GL20.glUniform1i(locShaftCount, shafts);
        if (shafts > 0) {
            shaftUpload.clear();
            shaftUpload.put(SHAFTS, 0, shafts * 8).flip();
            GL20.glUniform4fv(locShafts, shaftUpload);
        }
        int previous = GlStateManager._getActiveTexture();
        RenderSystem.activeTexture(GL13.GL_TEXTURE6);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, lightTex);
        RenderSystem.activeTexture(GL13.GL_TEXTURE7);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, windTex);
        RenderSystem.activeTexture(previous);

        vbo.draw();

        shader.clear();
        RenderSystem.activeTexture(GL13.GL_TEXTURE6);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        RenderSystem.activeTexture(GL13.GL_TEXTURE7);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        RenderSystem.activeTexture(previous);
        VertexBuffer.unbind();

        mc.gameRenderer.lightTexture().turnOffLightLayer();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /**
     * 光の筋の中の光る胞子({@code shaders/core/gpu_motes.vsh}。CPU 版は {@link CanopyLight#tickMotes})。筋 1 本に {@value #MOTES_PER_GROUP} 枠の組を持ち、
     * 枠は 90〜200 tick の周期で筋の中に生まれ直し、漂う胞子と同じ風の履歴で流れる。筋の中にいる間だけ光り、光の方を向くほど明るい。
     * 出す枠の割合は CPU の湧き方から({@link CanopyLight#fillMoteGroups})。加算で、奥行きは書かない。
     */
    private static void renderMotes(RenderLevelStageEvent event, Minecraft mc, ShaderInstance shader) {
        ClientLevel lvl = mc.level;
        if (lvl == null || moteVbo == null) {
            return;
        }
        Camera camera = event.getCamera();
        float partialTick = mc.isPaused() ? 0.0F : event.getPartialTick().getGameTimeDeltaPartialTick(false);
        if (!CanopyLight.moteLight(lvl, camera, partialTick, MOTE_LIGHT)) {
            return;
        }
        Vec3 cam = camera.getPosition();
        int groups = CanopyLight.fillMoteGroups(MOTE_GROUPS, cam.x, cam.y, cam.z, MOTES_PER_GROUP, MAX_SHAFTS);
        if (groups == 0) {
            return;
        }
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        shader.safeGetUniform("CamMod").set((float) Math.floorMod((long) Math.floor(cam.x), (long) WRAP) + (float) (cam.x - Math.floor(cam.x)),
                (float) cam.y,
                (float) Math.floorMod((long) Math.floor(cam.z), (long) WRAP) + (float) (cam.z - Math.floor(cam.z)), 0.0F);
        double windRow = (ticks - 1 + partialTick) % HISTORY;
        shader.safeGetUniform("Clock").set((float) (ticks + partialTick), (float) windRow, 0.0F, 0.0F);
        shader.safeGetUniform("CamLeft").set(left.x(), left.y(), left.z(), 0.0F);
        shader.safeGetUniform("CamUp").set(up.x(), up.y(), up.z(), 0.0F);
        shader.safeGetUniform("Sun").set(MOTE_LIGHT[0], MOTE_LIGHT[1], MOTE_LIGHT[2], MOTE_LIGHT[3]);
        shader.safeGetUniform("Glow").set(MOTE_LIGHT[4], MOTE_LIGHT[5], MOTE_LIGHT[6], MOTE_LIGHT[7]);

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA, com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        moteVbo.bind();
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, event.getModelViewMatrix(), event.getProjectionMatrix(), mc.getWindow());
        shader.apply();
        if (moteLocShader != shader) {
            moteLocShader = shader;
            int program = shader.getId();
            moteLocWind = GL20.glGetUniformLocation(program, "WindHist");
            moteLocGroups = GL20.glGetUniformLocation(program, "Groups");
            moteLocGroupCount = GL20.glGetUniformLocation(program, "GroupCount");
        }
        GL20.glUniform1i(moteLocWind, 7);
        GL20.glUniform1i(moteLocGroupCount, groups);
        moteUpload.clear();
        moteUpload.put(MOTE_GROUPS, 0, groups * 12).flip();
        GL20.glUniform4fv(moteLocGroups, moteUpload);
        int previous = GlStateManager._getActiveTexture();
        RenderSystem.activeTexture(GL13.GL_TEXTURE7);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, windTex);
        RenderSystem.activeTexture(previous);

        moteVbo.draw();

        shader.clear();
        RenderSystem.activeTexture(GL13.GL_TEXTURE7);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        RenderSystem.activeTexture(previous);
        VertexBuffer.unbind();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    // ---- GL の準備 ----

    private static void ensureGl() {
        if (vbo != null) {
            return;
        }
        windMirror = new float[WIND_N * WIND_N * HISTORY * 4];
        rowUpload = MemoryUtil.memAllocFloat(WIND_N * WIND_N * 4);
        columnUpload = MemoryUtil.memAllocFloat(HISTORY * 4);
        shaftUpload = MemoryUtil.memAllocFloat(MAX_SHAFTS * 8);
        lightUpload = MemoryUtil.memAlloc(LIGHT_TEXELS * 4);

        windTex = GL11.glGenTextures();
        setupTexture(windTex);
        uploadWindAll();

        lightTex = GL11.glGenTextures();
        setupTexture(lightTex);
        lightUpload.clear();
        lightUpload.put(lightData).flip();
        bindForUpload(lightTex);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, LIGHT_N, LIGHT_N, LIGHT_N, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, lightUpload);
        unbindUpload();

        // 枠ごとに 4 頂点(位置の x = 枠の番号、y = 角の番号)。
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        for (int slot = 0; slot < MAX_SLOTS; slot++) {
            for (int corner = 0; corner < 4; corner++) {
                builder.addVertex(slot, corner, 0.0F);
            }
        }
        MeshData mesh = builder.buildOrThrow();
        vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vbo.bind();
        vbo.upload(mesh);
        VertexBuffer.unbind();

        // 光る胞子: 筋の組 × 枠ごとに 4 頂点(x = 枠の番号、y = 角の番号。組は 番号 / MOTES_PER_GROUP)。
        moteUpload = MemoryUtil.memAllocFloat(MAX_SHAFTS * 12);
        BufferBuilder motes = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        for (int slot = 0; slot < MAX_SHAFTS * MOTES_PER_GROUP; slot++) {
            for (int corner = 0; corner < 4; corner++) {
                motes.addVertex(slot, corner, 0.0F);
            }
        }
        MeshData moteMesh = motes.buildOrThrow();
        moteVbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
        moteVbo.bind();
        moteVbo.upload(moteMesh);
        VertexBuffer.unbind();
    }

    private static void setupTexture(int id) {
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, id);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }

    /** 3D テクスチャを結び、ほかの描画が残した読み込みの設定(行の長さ・飛ばし)を外す。2D の結びには触らない。 */
    private static void bindForUpload(int id) {
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, id);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
    }

    private static void unbindUpload() {
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }

    private static int floorDiv(double v, int cell) {
        return Math.floorDiv((int) Math.floor(v), cell);
    }

    /** 開発用: 今の状態(テストクライアントの {@code gpuspores}). */
    public static String describe() {
        Minecraft mc = Minecraft.getInstance();
        return "GPUSPORES enabled=" + enabled + " active=" + active + " broken=" + broken + " shader=" + (ModShaders.gpuSpores != null)
                + " pack=" + ShaderPacks.active() + " slots=" + slotCount(mc) + " densityTexels=" + densityTexels + "/" + LIGHT_TEXELS
                + " shafts=" + CanopyLight.fillShafts(SHAFTS, 0, 0, 0) + " motes=" + replacesMotes();
    }

    private GpuSpores() {
    }
}
