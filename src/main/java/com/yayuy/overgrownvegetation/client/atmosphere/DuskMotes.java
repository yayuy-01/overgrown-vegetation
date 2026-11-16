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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * <b>夕方の光の粒</b>(合間の小物、案 D1「光る羽虫 / 浮かぶ光の粒を夕方だけ」。2026-10-31。参考画像 2 の浮かぶ光)。
 *
 * <p>夕方(日没の前後)と夜の始めだけ、林床の上 {@code dusk.heightMin}〜{@code heightMax} ブロックに、ゆっくり明滅しながら漂う小さな光の粒を少し出す。
 * 作りは環境の胞子({@link AmbientSpores})と同じ GPU の束(粒の枠を固定数並べ、位置は頂点シェーダー {@code shaders/core/dusk_motes.vsh} が
 * 時刻と枠の番号から決める。CPU は毎 tick 升を少し調べるだけ)。揺られ方は {@code ov_drift.glsl} を弱めて使い、自分でもゆっくりさまよう。
 * 加算の光で描く(<b>粒だけが光る。葉は光らせない</b>)。高さの霧とバニラの霧では色を寄せず薄れる。
 *
 * <ul>
 *     <li>いつ: {@code fromHour} 時から出始め {@code fullHour} 時に揃い、{@code fadeHour} 時から {@code toHour} 時までに消える(既定 17・18・20・21 時)。
 *     雨の間は出さない。</li>
 *     <li>どこに: 森の中({@link ForestPresence#openForest})の、8 ブロックの升ごとに過繁茂のバイオームの所。林床の高さは升の真ん中の
 *     梢から下へたどった、葉・幹でない動きを止めるブロックの上({@link #floorAt})。</li>
 *     <li>色: 青緑寄りの黄緑(植物のエネルギーは青緑・人類の魔法は琥珀、の決まりで琥珀にしない)。粒ごとに 2 色を混ぜる。</li>
 *     <li>数: 品質の段で {@code dusk.counts}(60 / 35 / 15、仮決め)。</li>
 * </ul>
 * 数値は {@code atmosphere.json} の節 {@code dusk}(すべて仮決め)。シェーダーが読めなければ描かない。
 */
final class DuskMotes {

    private static final int MAX_SLOTS = 512;
    private static final int N = WindHistory.N;

    private static VertexBuffer vbo;
    private static int builtSlots;
    private static int floorTexture = -1;
    private static FloatBuffer floorUpload;
    private static final float[] TEXELS = new float[N * N * 2];
    private static int scanAt;
    private static boolean anyOvergrown;
    private static float shown;
    private static ShaderInstance locShader;
    private static int locWind = -1;
    private static int locFloor = -1;

    private DuskMotes() {
    }

    static void clear() {
        java.util.Arrays.fill(TEXELS, 0.0F);
        anyOvergrown = false;
        scanAt = 0;
        shown = 0.0F;
    }

    /** 開発用のログ: 出方・カメラの升の林床の高さ。 */
    static String describe(double x, double z) {
        int idx = Math.floorMod(Math.floorDiv(Mth.floor(z), WindHistory.CELL), N) * N + Math.floorMod(Math.floorDiv(Mth.floor(x), WindHistory.CELL), N);
        return String.format("夕方の粒 %.2f 林床 %.0f 過繁茂 %s", shown, TEXELS[idx * 2 + 1], anyOvergrown);
    }

    /** 今の出方(0〜1。時刻・雨・森の度合い)。 */
    static float amount() {
        return shown;
    }

    /** 時刻(0〜24 時。dayTime 0 = 6 時)での出方 0〜1。 */
    static float timeWeight(long dayTime, AtmosphereTable.DuskParams p) {
        float hour = (float) (Math.floorMod(dayTime, 24000L) / 1000.0 + 6.0) % 24.0F;
        if (hour < 6.0F && p.toHour() > 24.0F) {
            hour += 24.0F;
        }
        float in = SunLight.smooth(Mth.clamp((hour - p.fromHour()) / (p.fullHour() - p.fromHour()), 0.0F, 1.0F));
        float out = 1.0F - SunLight.smooth(Mth.clamp((hour - p.fadeHour()) / (p.toHour() - p.fadeHour()), 0.0F, 1.0F));
        return in * out;
    }

    /** 毎 tick({@link Atmosphere} から)。8 ブロックの升を 16 升ずつ調べる(256 升を 16 tick で一回り)。 */
    static void tick(ClientLevel level, LocalPlayer player) {
        if (!VisualQuality.motes()) {
            clear();
            return;
        }
        AtmosphereTable.DuskParams p = DesignData.atmosphere().dusk();
        // G5 光る胞子の夜(11-08): その夜は一晩中 duskBoost の割合で出す。
        float time = Math.max(timeWeight(level.getDayTime(), p), GlowNight.amount() * DesignData.atmosphere().glowNight().duskBoost());
        float target = time * (1.0F - level.getRainLevel(1.0F)) * ForestPresence.openOvergrown();
        shown += (target - shown) * 0.02F;
        if (shown <= 0.005F || ModShaders.duskMotes() == null) {
            return;
        }
        int c0x = Math.floorDiv(Mth.floor(player.getX()), WindHistory.CELL) - N / 2;
        int c0z = Math.floorDiv(Mth.floor(player.getZ()), WindHistory.CELL) - N / 2;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int n = 0; n < N; n++) {
            int cell = scanAt;
            scanAt = (scanAt + 1) % (N * N);
            int wx = c0x + cell % N;
            int wz = c0z + cell / N;
            int idx = Math.floorMod(wz, N) * N + Math.floorMod(wx, N);
            int cx = wx * WindHistory.CELL + WindHistory.CELL / 2;
            int cz = wz * WindHistory.CELL + WindHistory.CELL / 2;
            pos.set(cx, Mth.floor(player.getY()), cz);
            boolean overgrown = level.hasChunkAt(pos) && ModBiomes.isOvergrownLike(level.getBiome(pos));
            TEXELS[idx * 2] = overgrown ? 1.0F : 0.0F;
            TEXELS[idx * 2 + 1] = overgrown ? floorAt(level, cx, cz, pos) : 0.0F;
        }
        boolean any = false;
        for (int k = 0; k < N * N; k++) {
            any |= TEXELS[k * 2] > 0.0F;
        }
        anyOvergrown = any;
    }

    /**
     * 林床の高さ(いちばん上の、葉でない動きを止めるブロックのすぐ上)。クライアントのチャンクは葉を除いた高さ地図を持たない(サーバーからは
     * WORLD_SURFACE と MOTION_BLOCKING しか届かない)ので、梢から下へたどる。64 段で見つからなければ梢の高さ。
     */
    private static float floorAt(ClientLevel level, int x, int z, BlockPos.MutableBlockPos pos) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        for (int y = top - 1; y > top - 64 && y > level.getMinBuildHeight(); y--) {
            net.minecraft.world.level.block.state.BlockState s = level.getBlockState(pos.set(x, y, z));
            if (s.blocksMotion() && !s.is(net.minecraft.tags.BlockTags.LEAVES) && !s.is(com.yayuy.overgrownvegetation.ModTags.OVERGROWN_LEAVES)
                    && !s.is(net.minecraft.tags.BlockTags.LOGS)) {
                return y + 1;
            }
        }
        return top;
    }

    private static int count() {
        int[] counts = DesignData.atmosphere().dusk().counts();
        return Math.min(MAX_SLOTS, counts[Math.min(2, VisualQuality.atmosphereTier())]);
    }

    /** AFTER_PARTICLES({@link Atmosphere} から。加算の光の描き先を替えた中で)。 */
    static void render(RenderLevelStageEvent event) {
        if (!VisualQuality.motes()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ShaderInstance shader = ModShaders.duskMotes();
        int slots = count();
        int visible = Math.round(slots * shown * (1.0F - ForestPresence.underground()));
        if (shader == null || !anyOvergrown || visible <= 0 || WindHistory.texture() < 0) {
            return;
        }
        AtmosphereTable.DuskParams p = DesignData.atmosphere().dusk();
        ensureGl(slots);
        float partialTick = mc.isPaused() ? 0.0F : event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        Vector3f left = event.getCamera().getLeftVector();
        Vector3f up = event.getCamera().getUpVector();
        // 繰り返しの箱の幅は 2048(位置を折り返す幅)を割り切る 2 の冪に丸める(環境の胞子と同じ)。
        float half = Math.max(8.0F, Math.min(32.0F, Mth.smallestEncompassingPowerOfTwo(Math.round(p.boxHalf() * 2.0F)) / 2.0F));
        double wrap = WindHistory.WRAP;
        shader.safeGetUniform("CamMod").set((float) Math.floorMod((long) Math.floor(cam.x), (long) wrap) + (float) (cam.x - Math.floor(cam.x)),
                (float) cam.y,
                (float) Math.floorMod((long) Math.floor(cam.z), (long) wrap) + (float) (cam.z - Math.floor(cam.z)), 0.0F);
        double windRow = (WindHistory.ticks() - 1 + partialTick) % WindHistory.HISTORY;
        shader.safeGetUniform("Clock").set((float) Atmosphere.time(partialTick), (float) windRow, (float) visible, p.size());
        shader.safeGetUniform("Box").set(half, p.heightMin(), p.heightMax(), half - 1.0F);
        float[] w = p.warmColor();
        float[] c = p.coolColor();
        shader.safeGetUniform("Look").set(w[0], w[1], w[2], p.brightness());
        shader.safeGetUniform("Look2").set(c[0], c[1], c[2], p.blinkSeconds() * 20.0F);
        shader.safeGetUniform("CamLeft").set(left.x(), left.y(), left.z(), 0.0F);
        shader.safeGetUniform("CamUp").set(up.x(), up.y(), up.z(), 0.0F);
        AmbientSpores.setDrift(shader, DesignData.atmosphere().spores());

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        MistFog.setParticleUniforms(shader, cam);
        vbo.bind();
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, event.getModelViewMatrix(), event.getProjectionMatrix(), mc.getWindow());
        shader.apply();
        MistFog.bindParticleMap(shader);
        if (locShader != shader) {
            locShader = shader;
            locWind = GL20.glGetUniformLocation(shader.getId(), "WindHist");
            locFloor = GL20.glGetUniformLocation(shader.getId(), "Floor");
        }
        GL20.glUniform1i(locWind, 7);
        GL20.glUniform1i(locFloor, 6);
        uploadFloor();
        int previous = GlStateManager._getActiveTexture();
        RenderSystem.activeTexture(GL13.GL_TEXTURE7);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, WindHistory.texture());
        RenderSystem.activeTexture(GL13.GL_TEXTURE6);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, floorTexture);
        RenderSystem.activeTexture(previous);

        vbo.draw();

        shader.clear();
        MistFog.unbindParticleMap();
        RenderSystem.activeTexture(GL13.GL_TEXTURE7);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        RenderSystem.activeTexture(GL13.GL_TEXTURE6);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        RenderSystem.activeTexture(previous);
        VertexBuffer.unbind();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    private static void uploadFloor() {
        floorUpload.clear();
        floorUpload.put(TEXELS).flip();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, floorTexture);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, N, N, GL30.GL_RG, GL11.GL_FLOAT, floorUpload);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private static void ensureGl(int slots) {
        if (vbo != null && builtSlots == slots) {
            return;
        }
        if (floorTexture < 0) {
            floorUpload = MemoryUtil.memAllocFloat(N * N * 2);
            floorTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, floorTexture);
            // 升の間はなめらかに(林床の高さが升の境で段にならないように)。
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG32F, N, N, 0, GL30.GL_RG, GL11.GL_FLOAT, (FloatBuffer) null);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
        if (vbo != null) {
            vbo.close();
        }
        builtSlots = slots;
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        for (int slot = 0; slot < slots; slot++) {
            for (int corner = 0; corner < 4; corner++) {
                builder.addVertex(slot, corner, 0.0F);
            }
        }
        MeshData mesh = builder.buildOrThrow();
        vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vbo.bind();
        vbo.upload(mesh);
        VertexBuffer.unbind();
    }
}
