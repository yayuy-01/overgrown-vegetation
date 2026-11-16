package com.yayuy.overgrownvegetation.client.worldevent;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.atmosphere.CloudHaze;
import com.yayuy.overgrownvegetation.client.atmosphere.DenseFog;
import com.yayuy.overgrownvegetation.client.atmosphere.ForestPresence;
import com.yayuy.overgrownvegetation.client.atmosphere.GiantTrees;
import com.yayuy.overgrownvegetation.client.atmosphere.SporeAir;
import com.yayuy.overgrownvegetation.client.atmosphere.SunLight;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.client.sky.CloudInterior;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.WorldEventLooks;
import com.yayuy.overgrownvegetation.worldevent.WorldEventKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>ワールドイベントの空の見た目</b>(2026-11-16。ユーザー決定の中身は Vault「ワールドイベント 7 つの中身の案」の【決定】、作り方と絵は
 * 「ワールドイベントの見た目(実装の記録)」)。どのイベントがどのくらい進んでいるかは {@link WorldEventClient} から読む。効き目は無い(見た目だけ)。
 *
 * <h2>胞子のオーロラ</h2>
 * 理由(設定): 高空の胞子が主星の風に当たって光る。汚染の森の上ほど明るい(森が胞子を空へ送っている匂わせ)。
 * 空を描き終えた所(星・月の後)で、雲より先に、画面いっぱいの四角を足し算で重ねる({@code shaders/core/ov_aurora})。雲は後から描かれるので幕を隠す。
 * 遠くの雲もこの段({@code AFTER_SKY})で描かれるので、受け手の順を {@link EventPriority#HIGHEST} にして先に描く。
 * <ul>
 *     <li>出方: 始まりから {@code riseTicks} で出て、終わりの {@code fallTicks} で消える(短いイベントでは長さの半分ずつに縮める)。</li>
 *     <li>薄くする物: 昼(夜の度合いを掛ける)・雨・濃霧で満ちた空・雲の中・森の霞の中({@code hazeDim})。地下・水の中・シェーダーパック使用中は描かない。</li>
 *     <li>森の上の明るさ: クライアントが知っている森の場所(胞子の空気の地図 {@link SporeAir} と巨大樹の一覧 {@link GiantTrees})から、
 *     明るい所を 4 つまで選んでシェーダーへ渡す(2 秒おきに選び直す)。</li>
 * </ul>
 * 値は {@code sky.json} の節 {@code worldEvents.aurora}(すべて仮決め)。確かめは {@code /ov event start aurora <進み>}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class WorldEventSky {

    private WorldEventSky() {
    }

    /** 模様の座標を float で渡すための折り返し(ここを越えるとき模様が一度だけずれる)。 */
    private static final double PERIOD = 65536.0;
    /** 時刻の折り返し(tick。実時間 1 日)。 */
    private static final long TIME_WRAP = 1_728_000L;

    /** Claude のテストクライアント(-Dov.devbridge=true)だけ、5 秒ごとに状態をログへ出す。 */
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");
    private static int devLogged = -1;
    private static boolean broken;
    private static float lastAurora;
    private static int ticks;
    /** 森の明るい所(世界の x, z, 重さ)。4 つまで。 */
    private static final float[][] SPOTS = new float[4][3];
    private static int spotCount;

    /** 出て消える包み(0〜1): 始まりから rise で出て、終わりの fall で消える。短いときは半分ずつに縮める。 */
    public static float envelope(float elapsed, float length, float rise, float fall) {
        if (length <= 0.0F || elapsed <= 0.0F || elapsed >= length) {
            return 0.0F;
        }
        float r = Math.max(1.0F, Math.min(rise, length * 0.5F));
        float f = Math.max(1.0F, Math.min(fall, length * 0.5F));
        return SunLight.smooth(Mth.clamp(elapsed / r, 0.0F, 1.0F)) * SunLight.smooth(Mth.clamp((length - elapsed) / f, 0.0F, 1.0F));
    }

    /** オーロラの出ている度合い(0〜1。時刻・天気で薄める前)。フェーズ 3 の合図の短いオーロラも、サーバーが同じ種を短い長さで起こすのでここを通る。 */
    static float auroraEvent(float partialTick) {
        WorldEventLooks.Aurora a = DesignData.sky().events.aurora;
        float e = 0.0F;
        if (WorldEventClient.is(WorldEventKind.AURORA)) {
            e = envelope(WorldEventClient.elapsedTicks(partialTick), WorldEventClient.lengthTicks(), a.riseTicks, a.fallTicks);
        }
        return e;
    }

    /** いま空に出ているオーロラの強さ(0〜1。昇る胞子 {@link SporeRise} もこれで出し入れする)。 */
    public static float auroraShown() {
        return lastAurora;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) {
            return;
        }
        ticks++;
        if (DEV_LOG && ticks % 100 == 50 && WorldEventClient.kind() != null) {
            OvergrownVegetation.LOGGER.info("{} / {} / {} / {} / {} / {} / {}", describe(), SporeRise.describe(), WorldEventCues.describe(), MeteorShower.describe(),
                    Eclipse.describe(), LeyTide.describe(), HorizonShadow.describe() + " / " + GlowingTide.describe());
        }
        if (ticks % 40 == 1 && auroraEvent(0.0F) > 0.0F) {
            pickSpots(mc.gameRenderer.getMainCamera().getPosition());
        }
    }

    /**
     * 森の明るい所を選ぶ: カメラのまわり ±512 を 64 おきに胞子の空気の度合いで見て、巨大樹も足し、重い順に 4 つ(選んだ所の近くは次に選ばない)。
     */
    private static void pickSpots(Vec3 cam) {
        List<float[]> cands = new ArrayList<>();
        for (int j = -8; j <= 8; j++) {
            for (int i = -8; i <= 8; i++) {
                double x = cam.x + i * 64.0;
                double z = cam.z + j * 64.0;
                float w = SporeAir.levelAt(x, z);
                if (w > 0.05F) {
                    cands.add(new float[]{(float) x, (float) z, Math.min(1.0F, w * 1.5F)});
                }
            }
        }
        for (GiantTrees.Info t : GiantTrees.all()) {
            double dx = t.axisX() - cam.x;
            double dz = t.axisZ() - cam.z;
            if (dx * dx + dz * dz < 6000.0 * 6000.0) {
                cands.add(new float[]{(float) t.axisX(), (float) t.axisZ(), 1.0F});
            }
        }
        float radius = DesignData.sky().events.aurora.forestRadius * 0.6F;
        spotCount = 0;
        while (spotCount < 4 && !cands.isEmpty()) {
            float[] best = cands.get(0);
            for (float[] c : cands) {
                if (c[2] > best[2]) {
                    best = c;
                }
            }
            SPOTS[spotCount][0] = best[0];
            SPOTS[spotCount][1] = best[1];
            SPOTS[spotCount][2] = best[2];
            spotCount++;
            final float bx = best[0], bz = best[1];
            cands.removeIf(c -> (c[0] - bx) * (c[0] - bx) + (c[1] - bz) * (c[1] - bz) < radius * radius);
        }
    }

    /** 空を描き終えた所。遠くの雲({@code VoxelClouds} も AFTER_SKY)より先に描く。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        lastAurora = 0.0F;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (broken || level == null || ShaderPacks.active() || event.getCamera().getFluidInCamera() != FogType.NONE
                || !level.dimensionType().hasSkyLight() || level.effects().skyType() != DimensionSpecialEffects.SkyType.NORMAL) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        try {
            drawAurora(event, level, partial);
            drawHalo(event, level, partial);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("ワールドイベントの空の見た目で例外が出たので、このセッションでは描かない", e);
        }
    }

    private static void drawAurora(RenderLevelStageEvent event, ClientLevel level, float partial) {
        WorldEventLooks.Aurora a = DesignData.sky().events.aurora;
        ShaderInstance shader = WorldEventShaders.aurora();
        float amount = auroraEvent(partial) * a.strength;
        if (shader == null || amount <= 0.001F) {
            return;
        }
        if (DEV_LOG && ticks % 100 == 0 && ticks != devLogged) {
            devLogged = ticks;
            OvergrownVegetation.LOGGER.info("WorldEventSky オーロラ 出方 {} 夜 {} 雨 {} 濃霧 {} 雲の中 {} 地下 {} 森の霞 {}", amount, SunLight.night(level, partial),
                    level.getRainLevel(partial), DenseFog.skyFillPublic(), CloudInterior.amount(partial), ForestPresence.underground(), CloudHaze.wall());
        }
        // 空の見え方で薄める: 昼・雨・濃霧で満ちた空・雲の中・地下・森の霞の中。
        amount *= SunLight.night(level, partial) * (1.0F - level.getRainLevel(partial)) * (1.0F - DenseFog.skyFillPublic())
                * (1.0F - Mth.clamp(CloudInterior.amount(partial) * 2.0F, 0.0F, 1.0F)) * (1.0F - ForestPresence.underground())
                * (1.0F - Mth.clamp(a.hazeDim, 0.0F, 1.0F) * CloudHaze.wall());
        if (amount <= 0.002F) {
            return;
        }
        lastAurora = Math.min(1.0F, amount);
        Vec3 cam = event.getCamera().getPosition();
        double time = ((level.getGameTime() % TIME_WRAP) + partial) / 20.0;
        float bottom = Math.max(200.0F, a.bottom - (float) cam.y);
        float top = Math.max(bottom + 200.0F, a.top - (float) cam.y);
        double drift = time * a.drift;
        shader.safeGetUniform("InvViewProj").set(new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix()).invert());
        shader.safeGetUniform("Aurora").set(amount, (float) time, a.shimmer, Math.max(200.0F, a.scale));
        shader.safeGetUniform("Layer").set(bottom, top, a.base, a.forestBoost);
        shader.safeGetUniform("Field").set((float) (cam.x - Math.floor(cam.x / PERIOD) * PERIOD), (float) (cam.z - Math.floor(cam.z / PERIOD) * PERIOD),
                (float) (drift * 0.8), (float) (drift * 0.6));
        shader.safeGetUniform("ColorLow").set(rgb(a.colorLow, 0), rgb(a.colorLow, 1), rgb(a.colorLow, 2), 1.0F);
        shader.safeGetUniform("ColorHigh").set(rgb(a.colorHigh, 0), rgb(a.colorHigh, 1), rgb(a.colorHigh, 2), 1.0F);
        for (int i = 0; i < 4; i++) {
            if (i < spotCount) {
                shader.safeGetUniform("Spot" + i).set((float) (SPOTS[i][0] - cam.x), (float) (SPOTS[i][1] - cam.z), Math.max(50.0F, a.forestRadius), SPOTS[i][2]);
            } else {
                shader.safeGetUniform("Spot" + i).set(0.0F, 0.0F, 1.0F, 0.0F);
            }
        }
        fullscreenAdd(shader);
    }

    /**
     * <b>日暈</b>(理由(設定): 空高くの胞子の結晶で光が曲がる。植物と関係ない、ただの不思議な空)。太陽から {@code radiusDeg} の所に光の輪、
     * 太陽と同じ高さの左右に幻日、太陽のまわりに乳白色の薄い膜({@code shaders/core/ov_halo})。太陽が低い(地平線に近い)ほど弱く、
     * 雨・濃霧・雲の中・地下・森の霞の中では薄くなる。値は {@code worldEvents.halo}(仮決め)。確かめは {@code /ov event start halo <進み>}。
     */
    private static void drawHalo(RenderLevelStageEvent event, ClientLevel level, float partial) {
        lastHalo = 0.0F;
        if (!WorldEventClient.is(WorldEventKind.HALO)) {
            return;
        }
        WorldEventLooks.Halo h = DesignData.sky().events.halo;
        ShaderInstance shader = WorldEventShaders.halo();
        Vec3 sun = SunLight.sunDirection(level, partial);
        float amount = envelope(WorldEventClient.elapsedTicks(partial), WorldEventClient.lengthTicks(), h.riseTicks, h.fallTicks) * h.strength
                * SunLight.smooth(Mth.clamp(((float) sun.y - 0.05F) / 0.25F, 0.0F, 1.0F))
                * (1.0F - level.getRainLevel(partial)) * (1.0F - DenseFog.skyFillPublic())
                * (1.0F - Mth.clamp(CloudInterior.amount(partial) * 2.0F, 0.0F, 1.0F)) * (1.0F - ForestPresence.underground())
                * (1.0F - 0.6F * CloudHaze.wall());
        if (shader == null || amount <= 0.002F) {
            return;
        }
        lastHalo = Math.min(1.0F, amount);
        shader.safeGetUniform("InvViewProj").set(new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix()).invert());
        shader.safeGetUniform("Halo").set(amount, (float) Math.toRadians(Mth.clamp(h.radiusDeg, 5.0F, 60.0F)),
                (float) Math.toRadians(Mth.clamp(h.widthDeg, 0.2F, 10.0F)), h.veil);
        shader.safeGetUniform("SunDir").set((float) sun.x, (float) sun.y, (float) sun.z, h.dogs);
        shader.safeGetUniform("Tint").set(1.0F, 0.98F, 0.94F, Mth.clamp(h.rainbow, 0.0F, 1.0F));
        fullscreenAdd(shader);
    }

    private static float lastHalo;

    /**
     * 空の段(AFTER_SKY)で向きだけの形を描く前に: 描画の行列をカメラの回転だけにする(この段ではまだ入っていない。月・流れ星・柱・影が使う)。
     * 描き終えたら {@link #popSkyView}。
     */
    static void pushSkyView(RenderLevelStageEvent event) {
        org.joml.Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        stack.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
    }

    static void popSkyView() {
        RenderSystem.getModelViewStack().popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    static float rgb(float[] c, int i) {
        return c != null && c.length > i ? Mth.clamp(c[i], 0.0F, 4.0F) : 1.0F;
    }

    /** 画面いっぱいの四角を足し算で 1 枚(奥行きは読まない・書かない)。 */
    static void fullscreenAdd(ShaderInstance shader) {
        ShaderInstance before = RenderSystem.getShader();
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.addVertex(-1.0F, -1.0F, 0.0F);
        b.addVertex(1.0F, -1.0F, 0.0F);
        b.addVertex(1.0F, 1.0F, 0.0F);
        b.addVertex(-1.0F, 1.0F, 0.0F);
        BufferUploader.drawWithShader(b.buildOrThrow());
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        if (before != null) {
            RenderSystem.setShader(() -> before);
        }
    }

    /** 確かめ用の 1 行。 */
    public static String describe() {
        WorldEventKind k = WorldEventClient.kind();
        return String.format("ワールドイベントの空: 種 %s 進み %.2f・オーロラ %.2f(森の明るい所 %d)・日暈 %.2f%s", k == null ? "なし" : k.id,
                WorldEventClient.progress(0.0F), lastAurora, spotCount, lastHalo, broken ? "・例外で止めた" : "");
    }
}
