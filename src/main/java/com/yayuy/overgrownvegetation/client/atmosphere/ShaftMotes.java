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
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;

/**
 * <b>光の筋の中で光る胞子</b>(一覧 27。2026-10-15「胞子が光に当たって光る」、10-23 GPU で動かす、10-30 作り直し。元は parked の {@code client/GpuSpores} の
 * 光る胞子の半分。漂う環境の胞子(一覧 28)の半分は第 2 段で)。
 *
 * <p>粒の「枠」を固定数並べた頂点の束({@link #MAX_GROUPS} 本 × {@link #PER_GROUP} 枠)を 1 回だけ作り、位置・明るさは頂点シェーダー
 * ({@code shaders/core/gpu_motes.vsh})が時刻と枠の番号から計算する。CPU が毎フレーム渡すのは筋ごとの組(上端・下端・幅・出方・湧く範囲・出す枠の割合)と
 * 光の向きと色だけ。枠は 90〜200 tick の周期で筋の中に生まれ直し、風に押された量の履歴({@link WindHistory})で流れ、渦で漂う。
 * 筋の中にいる間だけ光り、光の方を向くほど明るい({@link SunLight#phase})。夜は月光の筋の中で青白く弱く。加算で、奥行きは書かない。
 * 数は {@code maxMotes} × 重さの段の粒の量。シェーダーが読めなければ描かない。
 */
final class ShaftMotes {

    private static final int MAX_GROUPS = 14;
    private static final int PER_GROUP = 64;
    /** 光る胞子が湧く、筋の下端からの長さ(ブロック。巨大樹の傘から落ちる長い筋の上の方は霧の奥なので湧かせない)。 */
    private static final double MOTE_SPAN = 30.0;

    private static VertexBuffer vbo;
    private static FloatBuffer groupUpload;
    private static final float[] GROUPS = new float[MAX_GROUPS * 12];
    private static final float[] RGB = new float[3];
    private static ShaderInstance locShader;
    private static int locWind = -1;
    private static int locGroups = -1;
    private static int locGroupCount = -1;

    /**
     * 毎 tick({@link Atmosphere} から)。風の履歴は筋や環境の胞子が無い所(森の外)でも進める —— 止めると、森へ戻ったとき
     * 粒が「生まれた時の押された量」として古い行を読み、位置が一度だけ飛んだ(10-30 夜 提案 5)。
     */
    static void tick(Vec3 cam) {
        if (ModShaders.gpuMotes() != null || ModShaders.ambientSpores() != null) {
            WindHistory.tick(cam, Atmosphere.time(0.0F));
        }
    }

    static void render(RenderLevelStageEvent event) {
        if (!VisualQuality.lightShafts()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        ShaderInstance shader = ModShaders.gpuMotes();
        if (level == null || shader == null || LightShafts.count() + CaveShafts.count() == 0 || WindHistory.texture() < 0) {
            return;
        }
        // 11-07: 洞窟の天井の穴の筋(CaveShafts)の中にも漂わせる(森の外でも)
        float intensity = DesignData.atmosphere().intensity() * Math.max(ForestPresence.openOvergrown(), CaveShafts.moteIntensity());
        if (intensity <= 0.0F) {
            return;
        }
        ensureGl();
        Camera camera = event.getCamera();
        float partialTick = mc.isPaused() ? 0.0F : event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = camera.getPosition();
        int groups = fillGroups(cam);
        if (groups == 0) {
            return;
        }
        Vec3 light = SunLight.lightDirection(level, partialTick);
        SunLight.airColor(level, light, partialTick, RGB);
        var t = DesignData.atmosphere();
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        double wrap = WindHistory.WRAP;
        shader.safeGetUniform("CamMod").set((float) Math.floorMod((long) Math.floor(cam.x), (long) wrap) + (float) (cam.x - Math.floor(cam.x)),
                (float) cam.y,
                (float) Math.floorMod((long) Math.floor(cam.z), (long) wrap) + (float) (cam.z - Math.floor(cam.z)), 0.0F);
        double windRow = (WindHistory.ticks() - 1 + partialTick) % WindHistory.HISTORY;
        shader.safeGetUniform("Clock").set((float) Atmosphere.time(partialTick), (float) windRow, 0.0F, 0.0F);
        shader.safeGetUniform("CamLeft").set(left.x(), left.y(), left.z(), 0.0F);
        shader.safeGetUniform("CamUp").set(up.x(), up.y(), up.z(), 0.0F);
        shader.safeGetUniform("Sun").set((float) light.x, (float) light.y, (float) light.z, SunLight.lightStrength(level, light, partialTick));
        // 光る粒は筋より少し緑と青を足して、淡い金の中で胞子の色に見せる。
        // 11-07: 過繁茂の外の洞窟の筋の粒は胞子でなく埃の色(caveShafts.dustColor)へ寄せる
        float dust = CaveShafts.count() > 0 ? CaveLights.openShare() : 0.0F;
        float[] dc = DesignData.atmosphere().caveShafts().dustColor();
        shader.safeGetUniform("Glow").set(Mth.lerp(dust, RGB[0], RGB[0] * dc[0]), Mth.lerp(dust, Math.min(1.0F, RGB[1] + 0.05F), RGB[1] * dc[1]),
                Mth.lerp(dust, RGB[2] + 0.1F, RGB[2] * dc[2]), intensity);
        shader.safeGetUniform("Phase").set(t.phaseMin(), t.phaseMax() - t.phaseMin(), t.phasePower(), 0.0F);
        AmbientSpores.setDrift(shader, t.spores());

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        // 霧(第 3 段 {@link MistFog}): 地形と同じ高さの霧とバニラの霧で霞ませる。
        MistFog.setParticleUniforms(shader, event.getCamera().getPosition());
        vbo.bind();
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, event.getModelViewMatrix(), event.getProjectionMatrix(), mc.getWindow());
        shader.apply();
        MistFog.bindParticleMap(shader);
        // 自前の sampler3D と配列の uniform は json に書かず、ここで場所を引いて渡す(apply は json の samplers を並び順の unit に結ぶ)。
        if (locShader != shader) {
            locShader = shader;
            int program = shader.getId();
            locWind = GL20.glGetUniformLocation(program, "WindHist");
            locGroups = GL20.glGetUniformLocation(program, "Groups");
            locGroupCount = GL20.glGetUniformLocation(program, "GroupCount");
        }
        GL20.glUniform1i(locWind, 7);
        GL20.glUniform1i(locGroupCount, groups);
        groupUpload.clear();
        groupUpload.put(GROUPS, 0, groups * 12).flip();
        GL20.glUniform4fv(locGroups, groupUpload);
        int previous = GlStateManager._getActiveTexture();
        RenderSystem.activeTexture(GL13.GL_TEXTURE7);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, WindHistory.texture());
        RenderSystem.activeTexture(previous);

        vbo.draw();

        shader.clear();
        MistFog.unbindParticleMap();
        RenderSystem.activeTexture(GL13.GL_TEXTURE7);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        RenderSystem.activeTexture(previous);
        VertexBuffer.unbind();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
    /** 林冠の筋と洞窟の天井の穴の筋(11-07)。 */
    private static List<LightShafts.Shaft> allShafts() {
        List<LightShafts.Shaft> all = new java.util.ArrayList<>(LightShafts.shafts());
        all.addAll(CaveShafts.shafts());
        return all;
    }


    /**
     * 筋ごとの組に 12 個ずつ(上端 − カメラ、湧く幅の半分、下端 − カメラ、出ている度合い、湧き始める位置 tMin、出す枠の割合、光る判定の幅の倍率、薄い筋なら 1)。
     * 組の番号は筋が消えるまで変えない(変えると粒が別の筋へ飛ぶ)。出す枠の割合は筋の長さ・幅に比例する「生きている数」÷ 組の枠の数で、全体を
     * {@code maxMotes} × 粒の量で抑える。戻り値は使っている組の番号の最大 + 1。
     */
    private static int fillGroups(Vec3 cam) {
        Arrays.fill(GROUPS, 0.0F);
        boolean[] used = new boolean[MAX_GROUPS];
        for (LightShafts.Shaft s : allShafts()) {
            if (s.moteGroup >= 0 && s.moteGroup < MAX_GROUPS) {
                used[s.moteGroup] = true;
            }
        }
        float ws = LightShafts.widthScale(LightShafts.lastSoftness());
        float total = 0.0F;
        int top = 0;
        float[] want = new float[MAX_GROUPS];
        for (LightShafts.Shaft s : allShafts()) {
            if (s.moteGroup < 0) {
                for (int g = 0; g < MAX_GROUPS; g++) {
                    if (!used[g]) {
                        used[g] = true;
                        s.moteGroup = g;
                        break;
                    }
                }
                if (s.moteGroup < 0) {
                    continue;
                }
            }
            int g = s.moteGroup;
            Vec3 bottom = s.bottom == null ? s.top : s.bottom;
            double length = s.top.distanceTo(bottom);
            float presence = s.presence();
            float rate = presence * (float) (Math.min(length, MOTE_SPAN) * 0.03) * (0.6F + s.width) * (s.thin ? 0.6F : 1.0F)
                    * VisualQuality.particles() * AirHaze.haze();
            want[g] = Math.min(PER_GROUP, rate * 145.0F);
            total += want[g];
            int i = g * 12;
            GROUPS[i] = (float) (s.top.x - cam.x);
            GROUPS[i + 1] = (float) (s.top.y - cam.y);
            GROUPS[i + 2] = (float) (s.top.z - cam.z);
            GROUPS[i + 3] = s.width * ws * 0.9F;
            GROUPS[i + 4] = (float) (bottom.x - cam.x);
            GROUPS[i + 5] = (float) (bottom.y - cam.y);
            GROUPS[i + 6] = (float) (bottom.z - cam.z);
            GROUPS[i + 7] = presence;
            GROUPS[i + 8] = (float) Math.max(0.15, 1.0 - MOTE_SPAN / Math.max(length, 1.0));
            GROUPS[i + 10] = s.width * ws;
            GROUPS[i + 11] = s.thin ? 1.0F : 0.0F;
            top = Math.max(top, g + 1);
        }
        float cap = VisualQuality.particleCap(DesignData.atmosphere().maxMotes());
        float k = total > cap ? cap / total : 1.0F;
        for (int g = 0; g < top; g++) {
            GROUPS[g * 12 + 9] = want[g] * k / PER_GROUP;
        }
        return top;
    }

    private static void ensureGl() {
        if (vbo != null) {
            return;
        }
        groupUpload = MemoryUtil.memAllocFloat(MAX_GROUPS * 12);
        // 枠ごとに 4 頂点(位置の x = 枠の番号、y = 角の番号。組は 番号 / PER_GROUP)。
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        for (int slot = 0; slot < MAX_GROUPS * PER_GROUP; slot++) {
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

    private ShaftMotes() {
    }
}
