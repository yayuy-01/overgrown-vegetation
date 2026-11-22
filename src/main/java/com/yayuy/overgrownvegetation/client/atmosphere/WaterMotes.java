package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.client.UnderwaterMoteParticle;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.shader.ModShaders;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/**
 * <b>水中の浮遊物</b>(2026-11-13、水まわり ⑥ の続き。ユーザー「バニラの水中の粒とかも消して、汚染地域の胞子みたいにがっつり改変してもいいかも」
 * → 案に了承「この案で作る」)。理由: バニラの水中の粒は平たい絵の点で、陸の胞子(小さな立方体)と見た目も動きも揃わず「ドットが浮いているだけ」に見えた。
 * 胞子は空から降って水に溜まるので、水の濁りは陸の胞子の続きとして見せる。
 *
 * <p>陸の環境の胞子({@link AmbientSpores})と同じ作り: 粒の枠を固定数並べた頂点の束を 1 回だけ作り、位置は頂点シェーダー
 * ({@code shaders/core/water_motes.vsh})が時刻と枠の番号から決める。CPU は毎 tick ほぼ何もしない。
 * <ul>
 *     <li>形: 一辺 {@link #EDGE} ブロックほどの小さな立方体。カメラを向く 3 面に陰を付け、ゆっくり回る。</li>
 *     <li>置き場: 世界に固定した {@link #HALF} × 2 四方・高さ {@link #HALF_H} × 2 の繰り返しの箱(カメラが動いても粒はその場に残る)。
 *     {@link #BOTTOM_SHARE} の粒は水底から {@link #BOTTOM_BAND} ブロックの中(泥の粒は底に濃い)。</li>
 *     <li>水の中だけ: 水の深さの地図({@link WaterDepth}。列ごとの水面の高さと深さ)を読み、水面より上・水底より下の粒は描かない。</li>
 *     <li>動き: 水の共通の流れ({@link WaterCurrent})に流され、ゆっくり沈み、少しふらつく。</li>
 *     <li>色と数: 目の所の水で決める(汚染の度合い {@link #murk} を時間をかけて寄せる)。汚染の水は胞子の黄緑・泥炭の茶・藻の白をたっぷり、
 *     バニラの水は白い粒が {@link #CLEAR_SHARE} だけ。深いほど暗い。</li>
 *     <li>数: 重さの段(高・中・低)で {@link #COUNTS}。</li>
 * </ul>
 * これが動いている間({@link #active()})は、バニラの水中の粒({@code minecraft:underwater})と {@link UnderwaterMotes} の足し粒を出さない。
 * 潜っている間だけ描く。数値はすべて仮決め。
 */
public final class WaterMotes {

    static final int[] COUNTS = {2400, 1500, 700};
    static final float CLEAR_SHARE = 0.12F;
    /** 汚染の度合い: 過繁茂の水 1、繁茂の水 {@link #MURK_OVERGROWTH}、ほか 0。 */
    static final float MURK_OVERGROWTH = 0.65F;
    static final float EDGE = 0.045F;
    static final float HALF = 16.0F;
    static final float HALF_H = 10.0F;
    static final float BOTTOM_SHARE = 0.4F;
    static final float BOTTOM_BAND = 2.5F;
    /** 沈む速さ(ブロック/tick)。 */
    static final float SINK = 0.0006F;
    /** 色(r, g, b)と割合。汚染の水の粒は泥炭 {@link #PEAT}・藻 {@link #ALGAE}・残りは胞子 {@link #SPORE}。 */
    static final float[] SPORE = {0.56F, 0.69F, 0.25F};
    static final float[] PEAT = {0.42F, 0.32F, 0.20F, 0.3F};
    static final float[] ALGAE = {0.80F, 0.86F, 0.74F, 0.15F};
    static final float[] CLEAR = {0.82F, 0.88F, 0.92F};
    /** 夜の明るさの下限(昼の何倍か)。 */
    static final float NIGHT = 0.25F;

    private static final int MAX_SLOTS = 4096;
    private static VertexBuffer vbo;
    private static int builtSlots;
    private static ShaderInstance locShader;
    private static int locDepth = -1;
    private static float murk;
    private static float murkTarget;
    private static int drawn;

    private WaterMotes() {
    }

    /** バニラの水中の粒を置き換えているか(シェーダーが読め、深さの地図が出来ていて、水中の見た目が有効)。 */
    public static boolean active() {
        return VisualQuality.underwater() && VisualQuality.motes() && ModShaders.waterMotes() != null && WaterDepth.ready();
    }

    static String describe() {
        return String.format("水中の浮遊物 %s 濁り %.2f 描いた %d", active(), murk, drawn);
    }

    static void tick(ClientLevel level) {
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        var biome = level.getBiome(BlockPos.containing(eye));
        murkTarget = biome.is(ModBiomes.OVERGROWN) || biome.is(ModBiomes.OVERGROWN_OCEAN) || biome.is(ModBiomes.OVERGROWN_RIVER) ? 1.0F
                : (UnderwaterMoteParticle.overgrownWater(biome) ? MURK_OVERGROWTH : 0.0F);
        murk += (murkTarget - murk) * 0.02F;
    }

    static void clear() {
        murk = 0.0F;
        drawn = 0;
    }

    private static int count() {
        return Math.min(MAX_SLOTS, COUNTS[Math.min(COUNTS.length - 1, VisualQuality.atmosphereTier())]);
    }

    /** 潜っている間に({@link Atmosphere} の粒の後から)。 */
    static void render(RenderLevelStageEvent event) {
        drawn = 0;
        ShaderInstance shader = ModShaders.waterMotes();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !active()) {
            return;
        }
        int slots = count();
        int shown = Math.round(slots * (CLEAR_SHARE + (1.0F - CLEAR_SHARE) * murk));
        if (shown <= 0) {
            return;
        }
        ensureGl(slots);
        float partialTick = mc.isPaused() ? 0.0F : event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        // 明るさ: 真上の水面のすぐ上の空の光 × 昼の強さ(夜は NIGHT まで)と、目の所のブロックの光の大きい方。
        // 目の所の空の光は水 1 ブロックごとに 1 減り、10 ブロック潜ると粒が黒い点になったので(11-13 の撮影)、水中の散った光として水面で測る。
        // 深さによる暗さはシェーダーが粒ごとに掛ける。
        BlockPos eye = BlockPos.containing(cam);
        BlockPos.MutableBlockPos up = eye.mutable();
        for (int i = 0; i < 32 && level.getFluidState(up).is(net.minecraft.tags.FluidTags.WATER); i++) {
            up.move(0, 1, 0);
        }
        float day = SunLight.daylight(level, SunLight.sunDirection(level, partialTick), partialTick);
        float sky = level.getBrightness(LightLayer.SKY, up) / 15.0F * (NIGHT + (1.0F - NIGHT) * day);
        float block = level.getBrightness(LightLayer.BLOCK, eye) / 15.0F;
        float bright = 0.25F + 0.75F * Math.max(sky, block * 0.8F);

        double wrap = 2048.0;
        shader.safeGetUniform("CamMod").set((float) Math.floorMod((long) Math.floor(cam.x), (long) wrap) + (float) (cam.x - Math.floor(cam.x)),
                (float) cam.y,
                (float) Math.floorMod((long) Math.floor(cam.z), (long) wrap) + (float) (cam.z - Math.floor(cam.z)), 0.0F);
        shader.safeGetUniform("Clock").set((float) Atmosphere.time(partialTick), 0.0F, (float) shown, EDGE);
        shader.safeGetUniform("Box").set(HALF, HALF_H, BOTTOM_SHARE, BOTTOM_BAND);
        shader.safeGetUniform("Flow").set(WaterCurrent.x(), WaterCurrent.z(), SINK, 0.0F);
        shader.safeGetUniform("Look").set(bright, murkShare(slots, shown), 0.0F, 0.0F);
        shader.safeGetUniform("Spore").set(SPORE[0], SPORE[1], SPORE[2], 0.0F);
        shader.safeGetUniform("Peat").set(PEAT[0], PEAT[1], PEAT[2], PEAT[3]);
        shader.safeGetUniform("Algae").set(ALGAE[0], ALGAE[1], ALGAE[2], ALGAE[3]);
        shader.safeGetUniform("Clear").set(CLEAR[0], CLEAR[1], CLEAR[2], 0.0F);
        shader.safeGetUniform("DepthA").set((float) (WaterDepth.originX() - cam.x), (float) (WaterDepth.originZ() - cam.z), (float) WaterDepth.SIZE,
                (float) (WaterDepth.baseY() - cam.y));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        vbo.bind();
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, event.getModelViewMatrix(), event.getProjectionMatrix(), mc.getWindow());
        shader.apply();
        // 深さの地図は json に書かず、ここで場所を引いて 6 番に渡す(陸の胞子と同じやり方)。
        if (locShader != shader) {
            locShader = shader;
            locDepth = GL20.glGetUniformLocation(shader.getId(), "DepthMap");
        }
        GL20.glUniform1i(locDepth, 6);
        int previous = GlStateManager._getActiveTexture();
        RenderSystem.activeTexture(GL13.GL_TEXTURE6);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, WaterDepth.textureId());
        RenderSystem.activeTexture(previous);

        vbo.draw();
        drawn = shown;

        shader.clear();
        RenderSystem.activeTexture(GL13.GL_TEXTURE6);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        RenderSystem.activeTexture(previous);
        VertexBuffer.unbind();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /**
     * 描く粒のうち汚染の色にする割合。数は CLEAR_SHARE + (1 − CLEAR_SHARE) × 濁り なので、増えた分(と元の白い粒の濁り分)が汚染の色:
     * 濁り 0 なら 0(白だけ)、1 なら 1(汚染の色だけ)。
     */
    private static float murkShare(int slots, int shown) {
        return Mth.clamp(1.0F - slots * CLEAR_SHARE * (1.0F - murk) / Math.max(1, shown), 0.0F, 1.0F);
    }

    private static void ensureGl(int slots) {
        if (vbo != null && builtSlots == slots) {
            return;
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
}
