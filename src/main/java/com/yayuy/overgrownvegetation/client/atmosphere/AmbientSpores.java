package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.shader.ModShaders;
import com.yayuy.overgrownvegetation.data.AtmosphereTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * 過繁茂のバイオームに漂う<b>環境の胞子</b>(一覧 28。2026-10-30 夜 第 2 段「動き」。元は parked の {@code DriftingSporeParticle}・{@code SporeDrift}、
 * 2026-10-15 夜 ユーザー「環境パーティクルの胞子の動きが、差し込む光に照らされた胞子の動きと違いすぎる。光の胞子に合わせて」「突風が吹いた際は
 * 一斉に動くように」、10-17「浮く立方体はいい感じ」)。
 *
 * <h2>GPU で動かす(10-30 夜 決定)</h2>
 * 光る胞子({@link ShaftMotes})と同じ作り: 粒の枠を固定数並べた頂点の束を 1 回だけ作り、位置は頂点シェーダー
 * ({@code shaders/core/ambient_spores.vsh})が時刻と枠の番号から決める。動きは光る胞子と<b>同じ式</b>(風に押された量の履歴 {@link WindHistory}・
 * 同じ渦・前線が過ぎたあとの余韻)なので、突風の前線が来ると周りの胞子と光る胞子が一斉に同じ向きへ速く流れ、過ぎるとゆっくり落ち着く。
 * CPU の粒(バニラの ParticleEngine、1 粒ずつ tick で風を引く。組の上限 1000)より軽く、CPU は毎 tick 何もしない。バイオーム json の
 * {@code particle} は使わない(CPU の粒を併存させない)。
 *
 * <ul>
 *     <li>形: 一辺 {@code spores.size}(0.045)ブロックの<b>小さな立方体</b>。カメラを向く 3 面だけ描き、面ごとにブロックと同じ陰を付ける。
 *     少しずつ回る。</li>
 *     <li>置き場: 世界に固定した {@code boxHalf × 2} 四方・高さ {@code boxHeight} の繰り返しの箱のうちカメラにいちばん近い写し
 *     (カメラが動いても粒はその場に留まる)。縁で薄れる。</li>
 *     <li>どこに出るか: 8 ブロックの升ごとに過繁茂のバイオームか(1 秒おきに 16 升ずつ調べ、16 × 16 升の小さなテクスチャで渡す)。
 *     地下({@link ForestPresence#underground})では {@code undergroundKeep}(25%)まで減らす(光の届かない粒を薄く)。</li>
 *     <li>明るさ: 目の所の空の光 × 昼の強さ(夜は {@code nightBrightness})とブロックの光の大きい方。</li>
 *     <li>数: 重さの段(高・中・低)で {@code spores.counts}(1500 / 900 / 400、10-30 夜 仮決め)。</li>
 * </ul>
 * 色・大きさ・明るさは仮決め。シェーダーが読めなければ描かない。
 */
final class AmbientSpores {

    /** 頂点の束の枠の数(counts の上限)。 */
    private static final int MAX_SLOTS = 4096;
    private static final int N = WindHistory.N;
    /** 胞子の色(parked の環境の胞子 = バニラの spore_blossom_air と同じ緑)。 */
    private static final float R = 0.32F;
    private static final float G = 0.5F;
    private static final float B = 0.22F;

    private static VertexBuffer vbo;
    private static int densityTexture = -1;
    private static FloatBuffer densityUpload;
    /** 升ごとの過繁茂のバイオームか(0 / 1)と、梢の高さ(その升と周りの列の一番上のブロックのすぐ上、ワールドの y)。 */
    private static final float[] DENSITY = new float[N * N];
    private static final int[] TOPS = new int[N * N];
    private static final int[] CELL_X = new int[N * N];
    private static final int[] CELL_Z = new int[N * N];
    private static final float[] TEXELS = new float[N * N * 2];
    /** 頂点の束に作ってある枠の数(重さの段が替わったら作り直す)。 */
    private static int builtSlots;
    private static int scanAt;
    private static boolean anyOvergrown;
    private static ShaderInstance locShader;
    private static int locWind = -1;
    private static int locDensity = -1;
    private static int locSunMap = -1;

    /**
     * 梢より上の開けた空の下での出し方(10-30 夜 提案 1。開発用に {@code wind spores <番号>} で切り替えて撮り比べる)。
     * 0 = 梢の高さで箱を切る(升ごと)、1 = 森の度合い({@link ForestPresence#forestFactor})で全体を薄く、2 = 両方、3 = どちらもしない。
     */
    static int ceilingMode = 0;
    /** 開発用: 日向で明るくする割合をこのセッションだけ替える(負なら設計値)。 */
    static float litOverride = -1.0F;

    /** 描く物があるか。 */
    static boolean visible() {
        return anyOvergrown && ModShaders.ambientSpores() != null && count() > 0;
    }

    private static int count() {
        int[] counts = DesignData.atmosphere().spores().counts();
        return Math.min(MAX_SLOTS, counts[Math.min(counts.length - 1, VisualQuality.atmosphereTier())]);
    }

    static void clear() {
        java.util.Arrays.fill(DENSITY, 0.0F);
        java.util.Arrays.fill(CELL_X, Integer.MIN_VALUE);
        anyOvergrown = false;
        scanAt = 0;
    }

    /**
     * 毎 tick({@link Atmosphere} から)。8 ブロックの升を 16 升ずつ調べる(256 升を 16 tick で一回り)。梢の高さは升の真ん中と
     * ±3 ブロックの 4 か所の一番上(葉を含む)の高い方。
     */
    static void tick(ClientLevel level, LocalPlayer player) {
        if (ModShaders.ambientSpores() == null) {
            return;
        }
        int c0x = Math.floorDiv(Mth.floor(player.getX()), WindHistory.CELL) - N / 2;
        int c0z = Math.floorDiv(Mth.floor(player.getZ()), WindHistory.CELL) - N / 2;
        int y = Mth.floor(player.getY());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int n = 0; n < N; n++) {
            int cell = scanAt;
            scanAt = (scanAt + 1) % (N * N);
            int wx = c0x + cell % N;
            int wz = c0z + cell / N;
            int idx = Math.floorMod(wz, N) * N + Math.floorMod(wx, N);
            int cx = wx * WindHistory.CELL + WindHistory.CELL / 2;
            int cz = wz * WindHistory.CELL + WindHistory.CELL / 2;
            pos.set(cx, y, cz);
            boolean loaded = level.hasChunkAt(pos);
            boolean overgrown = loaded && ModBiomes.isOvergrownLike(level.getBiome(pos));
            DENSITY[idx] = overgrown ? 1.0F : 0.0F;
            CELL_X[idx] = wx;
            CELL_Z[idx] = wz;
            int top = Integer.MIN_VALUE;
            if (loaded) {
                top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz);
                top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx + 3, cz + 3));
                top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx - 3, cz + 3));
                top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx + 3, cz - 3));
                top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx - 3, cz - 3));
            }
            TOPS[idx] = top;
        }
        boolean any = false;
        for (int k = 0; k < N * N; k++) {
            any |= DENSITY[k] > 0.0F;
        }
        anyOvergrown = any;
    }

    /**
     * 升の濃さと、箱を切る高さ: 周り 3 × 3 升の梢の一番高い所(林冠の隙間で切れないように)と、その升の一番上 + 3(空き地では
     * 地面の近くだけ薄く残す)の高い方。まだ調べていない升は切らない。
     */
    private static void fillTexels() {
        for (int idx = 0; idx < N * N; idx++) {
            int wx = CELL_X[idx];
            int wz = CELL_Z[idx];
            float ceiling = 1.0E6F;
            if (wx != Integer.MIN_VALUE && TOPS[idx] != Integer.MIN_VALUE) {
                int best = TOPS[idx] + 3;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int j = Math.floorMod(wz + dz, N) * N + Math.floorMod(wx + dx, N);
                        if (CELL_X[j] == wx + dx && CELL_Z[j] == wz + dz && TOPS[j] != Integer.MIN_VALUE) {
                            best = Math.max(best, TOPS[j]);
                        }
                    }
                }
                ceiling = best;
            }
            TEXELS[idx * 2] = DENSITY[idx];
            TEXELS[idx * 2 + 1] = ceiling;
        }
    }

    static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        ShaderInstance shader = ModShaders.ambientSpores();
        if (level == null || shader == null || !visible() || WindHistory.texture() < 0) {
            return;
        }
        AtmosphereTable.SporesParams p = DesignData.atmosphere().spores();
        float under = ForestPresence.underground();
        float keep = 1.0F - under * (1.0F - p.undergroundKeep());
        if (ceilingMode == 1 || ceilingMode == 2) {
            keep *= ForestPresence.forestFactor();
        }
        int shown = Math.round(count() * keep);
        if (shown <= 0) {
            return;
        }
        ensureGl(count());
        float partialTick = mc.isPaused() ? 0.0F : event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        // 明るさ: 目の所の空の光 × 昼の強さ(夜は nightBrightness まで)と、ブロックの光の大きい方。
        BlockPos eye = BlockPos.containing(cam);
        float day = SunLight.daylight(level, SunLight.sunDirection(level, partialTick), partialTick);
        float sky = level.getBrightness(LightLayer.SKY, eye) / 15.0F * (p.nightBrightness() + (1.0F - p.nightBrightness()) * day);
        float block = level.getBrightness(LightLayer.BLOCK, eye) / 15.0F;
        float bright = 0.25F + 0.75F * Math.max(sky, block * 0.8F);

        // 繰り返しの箱の幅は 2048(位置を折り返す幅)を割り切る 2 の冪に丸める(折り返しの所で粒が飛ばないように)。
        float half = Math.max(8.0F, Math.min(32.0F, Mth.smallestEncompassingPowerOfTwo(Math.round(p.boxHalf() * 2.0F)) / 2.0F));
        double wrap = WindHistory.WRAP;
        shader.safeGetUniform("CamMod").set((float) Math.floorMod((long) Math.floor(cam.x), (long) wrap) + (float) (cam.x - Math.floor(cam.x)),
                (float) cam.y,
                (float) Math.floorMod((long) Math.floor(cam.z), (long) wrap) + (float) (cam.z - Math.floor(cam.z)), 0.0F);
        double windRow = (WindHistory.ticks() - 1 + partialTick) % WindHistory.HISTORY;
        shader.safeGetUniform("Clock").set((float) Atmosphere.time(partialTick), (float) windRow, (float) shown, p.size());
        shader.safeGetUniform("Box").set(half, p.boxHeight(), p.boxHeight() * 0.2F, half - 1.0F);
        shader.safeGetUniform("Look").set(R, G, B, bright);
        setDrift(shader, p);
        // 梢の高さで箱を切るか(提案 1)と、光の筋・木漏れ日の日向で少し明るく(提案 3。林冠の日向の地図 {@link CanopySunMap} を読む)。
        boolean sunMap = CanopySunMap.sporeTexture >= 0;
        shader.safeGetUniform("Sky").set(ceilingMode == 0 || ceilingMode == 2 ? 1.0F : 0.0F, litOverride >= 0.0F ? litOverride : p.litBoost(), sunMap ? 1.0F : 0.0F, 0.0F);
        float[] sa = CanopySunMap.SPORE_SUN_A;
        float[] ma = CanopySunMap.SPORE_MAP_A;
        shader.safeGetUniform("SunA").set(sa[0], sa[1], sa[2], sunMap ? sa[3] : 0.0F);
        shader.safeGetUniform("MapA").set(ma[0], ma[1], ma[2], ma[3]);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        // 霧(第 3 段 {@link MistFog}): 地形と同じ高さの霧とバニラの霧で霞ませる。
        MistFog.setParticleUniforms(shader, event.getCamera().getPosition());
        vbo.bind();
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, event.getModelViewMatrix(), event.getProjectionMatrix(), mc.getWindow());
        shader.apply();
        MistFog.bindParticleMap(shader);
        // 自前の sampler3D と升の濃さは json に書かず、ここで場所を引いて渡す(光る胞子と同じ 7 番に風の履歴、6 番に升の濃さ)。
        if (locShader != shader) {
            locShader = shader;
            locWind = GL20.glGetUniformLocation(shader.getId(), "WindHist");
            locDensity = GL20.glGetUniformLocation(shader.getId(), "Density");
            locSunMap = GL20.glGetUniformLocation(shader.getId(), "SunMap");
        }
        GL20.glUniform1i(locWind, 7);
        GL20.glUniform1i(locDensity, 6);
        GL20.glUniform1i(locSunMap, 5);
        uploadDensity();
        int previous = GlStateManager._getActiveTexture();
        RenderSystem.activeTexture(GL13.GL_TEXTURE7);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, WindHistory.texture());
        RenderSystem.activeTexture(GL13.GL_TEXTURE6);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, densityTexture);
        RenderSystem.activeTexture(GL13.GL_TEXTURE5);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sunMap ? CanopySunMap.sporeTexture : 0);
        RenderSystem.activeTexture(previous);

        vbo.draw();

        shader.clear();
        MistFog.unbindParticleMap();
        RenderSystem.activeTexture(GL13.GL_TEXTURE7);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        RenderSystem.activeTexture(GL13.GL_TEXTURE6);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        RenderSystem.activeTexture(GL13.GL_TEXTURE5);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        RenderSystem.activeTexture(previous);
        VertexBuffer.unbind();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** 粒の揺られ方(左右のふらつき・転がり、{@code shaders/include/ov_drift.glsl})の数値を渡す。光る胞子({@link ShaftMotes})も使う。 */
    static void setDrift(ShaderInstance shader, AtmosphereTable.SporesParams p) {
        shader.safeGetUniform("Drift").set(0.0F, 0.0F, p.wobbleCalm(), p.wobbleGust());
        shader.safeGetUniform("Drift2").set(0.0F, 0.0F, p.spinCalm(), p.spinPerBlock());
    }

    private static void uploadDensity() {
        fillTexels();
        densityUpload.clear();
        densityUpload.put(TEXELS).flip();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, densityTexture);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, N, N, GL30.GL_RG, GL11.GL_FLOAT, densityUpload);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private static void ensureGl(int slots) {
        if (vbo != null && builtSlots == slots) {
            return;
        }
        if (densityTexture < 0) {
            createDensityTexture();
        }
        if (vbo != null) {
            vbo.close();
        }
        builtSlots = slots;
        // 枠ごとに 3 面 × 4 頂点(位置の x = 枠の番号、y = 面 × 4 + 角の番号)。
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        for (int slot = 0; slot < slots; slot++) {
            for (int code = 0; code < 12; code++) {
                builder.addVertex(slot, code, 0.0F);
            }
        }
        MeshData mesh = builder.buildOrThrow();
        vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vbo.bind();
        vbo.upload(mesh);
        VertexBuffer.unbind();
    }

    private static void createDensityTexture() {
        densityUpload = MemoryUtil.memAllocFloat(N * N * 2);
        densityTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, densityTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG32F, N, N, 0, GL30.GL_RG, GL11.GL_FLOAT, (FloatBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private AmbientSpores() {
    }
}
