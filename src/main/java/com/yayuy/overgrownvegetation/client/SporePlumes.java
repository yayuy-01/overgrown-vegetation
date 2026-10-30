package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * 胞子のプルーム(2026-10-23 胞子の場と風 G。追記 15【決定】)。粒は使わない(重い)。
 *
 * <ul>
 *     <li><b>風下へ流れる帯</b>: まわり 13 × 13 チャンクのうち浮遊の濃度が {@code look.plumeMinPercent} を越えた升から、
 *     卓越風の向きへ薄い帯を 2 枚、地面の 12・22 ブロック上に浮かべる。濃い所ほど濃く、遠くからも「あの方向から来る」と分かる。</li>
 *     <li><b>巨大樹の葉から流れ出す胞子</b>: 柱は立てない。梢全体(差し渡し約 60)から風下へ、薄く広い帯が流れ出す
 *     (杉の花粉が風に乗るように)。風の強い日・嵐と朝(6〜9 時)に濃い。葉は光らせない。</li>
 * </ul>
 * 帯は板の縁と先へ向かって透明になり、帯に沿って流れる濃淡が風の速さで動く。Sodium でも出るよう、エンティティの半透明の
 * RenderType(白い絵 × 頂点の色)で描き、霧も効く。描く順番は天気の後(ボクセルの雲と同じ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class SporePlumes {

    private static final ResourceLocation WHITE = ResourceLocation.withDefaultNamespace("textures/misc/white.png");
    /** 帯 1 本の分割(先へ向かう向き)。 */
    private static final int SEGMENTS = 6;
    private static final float[] SHEET_HEIGHTS = {12.0F, 22.0F};
    private static final float CHUNK_WIDTH = 14.0F;
    private static final float TREE_WIDTH = 60.0F;
    private static final float[] TREE_SHEETS = {-10.0F, -3.0F, 4.0F};
    /** 直近のフレームで描いた帯の数(テストクライアントの数え用)。 */
    static int lastRibbons;
    /**
     * 開発用(2026-10-24、DevBridge の {@code plume})。old = 前の描き方(奥行きを書く entityTranslucent + Iris のまとめ描きの器)/
     * 濃さの倍率(光学的な厚さ: 頂点の不透明度 a → 1 − (1 − a)^k。薄い所は k 倍、濃い所は 1 で頭打ち)/ 緑(萌芽体の噴きの胞子の色へ寄せる割合)。
     */
    static boolean devOldType, devOldShape;
    static float devDensity = 1.0F, devGreen;
    private static int devRgb;
    private static RenderType type;

    private SporePlumes() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) return;
        boolean main = CanopyLight.drawToMainTarget();  // 「最高」では天気の層でなく主画面へ(CanopyLight#restoreWeatherTarget)
        try {
            draw(event);
        } finally {
            CanopyLight.restoreWeatherTarget(main);
        }
    }

    private static void draw(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || ClientSporeAir.gridSize() == 0 && ClientSporeAir.trees().length == 0 && ClientSporeAir.fronts().length == 0) return;
        PrevailingWind.Sample wind = level.dimension() == net.minecraft.world.level.Level.OVERWORLD
                ? ClientPrevailingWind.sample(level.getDayTime()) : null;
        // ネザーに風は無い: 帯は流れず、ゆっくり漂うだけ(向きは固定)
        double dirX = wind != null ? wind.dirX : 1.0D, dirZ = wind != null ? wind.dirZ : 0.0D;
        double strength = wind != null ? wind.strength : 0.15D;
        SporeFieldTable t = DesignData.sporeField();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float time = ((level.getGameTime() % 24000L) + partial) / 20.0F;
        float flow = (float) (0.6D + strength * 2.0D); // 濃淡が流れる速さ(ブロック/秒の目安 × 0.1)
        // ClientLevel#getSkyDarken(float) は空の明るさ(真昼 1.0 / 夜 0.2)。前は暗さと取り違えて昼ほど 1/4 に暗かった(2026-10-24)
        float day = Mth.clamp((level.getSkyDarken(partial) - 0.2F) / 0.8F, 0.0F, 1.0F);
        float dark = 0.25F + 0.75F * day;
        float[] fc = t.fogColor();
        float gr = devGreen;
        int r = (int) (Mth.lerp(gr, fc[0], 0.34F) * 255 * dark), g = (int) (Mth.lerp(gr, fc[1], 0.72F) * 255 * dark),
                b = (int) (Mth.lerp(gr, fc[2], 0.27F) * 255 * dark);

        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack(); // カメラの回転は RenderSystem の視点行列に入っている(エンティティの描画と同じ)
        Matrix4f m = pose.last().pose();
        // 奥行きを書かない型を、その場で描き切る器で(2026-10-24)。前の entityTranslucent は奥行きを書き、Iris(パック無しでも)の
        // まとめ描きの器は次のフレームの半透明の地形の直前に描くので、後から描く水・雲・降り積もりの膜が帯の矩形の形に抜けた
        // (DEV_NOTES「胞子の帯越しに半透明が消える件」)
        RenderType rt = devOldType ? RenderType.entityTranslucent(WHITE) : renderType();
        MultiBufferSource.BufferSource buffers = devOldType ? mc.renderBuffers().bufferSource() : CanopyLight.immediateBuffers();
        VertexConsumer vc = buffers.getBuffer(rt);
        devRgb = r << 16 | g << 8 | b;
        int ribbons = 0;

        // 1) 升ごとの帯
        int size = ClientSporeAir.gridSize();
        if (size > 0) {
            double len = 24.0D + 28.0D * Math.min(3.0D, strength);
            // 濃い升から MAX_CHUNK_PLUMES 升まで(地割れの噴出で窓じゅうが濃いと帯が 500 本を越えて重かった。2026-10-24)
            float cut = (float) t.plumeMinPercent();
            int over = 0;
            float[] airs = new float[size * size];
            for (int i = 0; i < airs.length; i++) {
                airs[i] = ClientSporeAir.airPercent(ClientSporeAir.gridMinCx() + i % size, ClientSporeAir.gridMinCz() + i / size);
                if (airs[i] > cut) over++;
            }
            if (over > MAX_CHUNK_PLUMES) {
                float[] sorted = airs.clone();
                java.util.Arrays.sort(sorted);
                cut = Math.max(cut, sorted[sorted.length - MAX_CHUNK_PLUMES]);
            }
            for (int dz = 0; dz < size; dz++)
                for (int dx = 0; dx < size; dx++) {
                    int cx = ClientSporeAir.gridMinCx() + dx, cz = ClientSporeAir.gridMinCz() + dz;
                    float air = airs[dz * size + dx];
                    if (air <= t.plumeMinPercent() || air < cut) continue;
                    float a = (float) (t.plumeAlpha() * Mth.clamp((air - t.plumeMinPercent()) / 200.0D, 0.0D, 1.0D));
                    if (a < 0.01F) continue;
                    double x = (cx << 4) + 8, z = (cz << 4) + 8;
                    if (!level.hasChunk(cx, cz)) continue;
                    int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) x, (int) z);
                    for (float h : SHEET_HEIGHTS) {
                        ribbon(vc, m, cam, x, ground + h, z, dirX, dirZ, len, CHUNK_WIDTH, a, r, g, b, time, flow,
                                (cx * 31 + cz * 17) * 0.37F + h);
                        ribbons++;
                    }
                }
        }

        // 2) 巨大樹の葉から流れ出す胞子(梢全体から風下へ)
        int[] trees = ClientSporeAir.trees();
        if (trees.length >= 3 && wind != null) {
            long tod = Math.floorMod(level.getDayTime(), 24000L);
            float morning = tod < 3000L ? 1.6F : 1.0F;
            float windy = wind.storm ? 1.8F : wind.strongDay ? 1.5F : 1.0F;
            double len = 90.0D + 90.0D * Math.min(3.0D, strength);
            for (int i = 0; i + 2 < trees.length; i += 3) {
                double x = trees[i] + 0.5D, z = trees[i + 1] + 0.5D;
                int top = trees[i + 2] >> 8;
                float grow = (trees[i + 2] & 0xFF) / 255.0F;
                float a = (float) Math.min(0.6D, t.treePlumeAlpha() * (0.4D + 0.6D * grow) * morning * windy);
                for (float h : TREE_SHEETS) {
                    // 梢の真ん中より少し風上から始め、梢をまたいで風下へ流れ出す
                    ribbon(vc, m, cam, x - dirX * 20.0D, top + h, z - dirZ * 20.0D, dirX, dirZ, len, TREE_WIDTH, a, r, g, b,
                            time, flow, trees[i] * 0.013F + h);
                    ribbons++;
                }
            }
        }
        // 3) 地割れの圧の前線: 全方位へ広がる輪の帯(2026-10-24 追記 19)。カメラから見える範囲の弧だけ描く
        int[] fr = ClientSporeAir.fronts();
        if (fr.length >= 5) {
            double since = (level.getGameTime() - ClientSporeAir.frontsTick() + partial) / 20.0D;
            for (int i = 0; i + 4 < fr.length; i += 5) {
                double stop = fr[i + 4];
                double rad = Math.min(stop, fr[i + 2] + fr[i + 3] / 100.0D * since);
                float fade = (float) Mth.clamp(1.0D - rad / Math.max(1.0D, stop), 0.0D, 1.0D);
                float a = (float) Math.min(0.6D, t.plumeAlpha() * 1.6D * (0.25D + 0.75D * fade));
                if (a < 0.01F || rad < 4.0D) continue;
                ribbons += ring(vc, m, cam, level, fr[i], fr[i + 1], rad, a, r, g, b, time);
            }
        }
        buffers.endBatch(rt);
        lastRibbons = ribbons;
    }

    /** 帯の型: エンティティの半透明(白い絵 × 頂点の色・霧も効く)と同じ中身で、奥行きは読むが書かない・遠い順に並べる。 */
    private static RenderType renderType() {
        if (type == null) {
            type = RenderType.create(OvergrownVegetation.MODID + "_spore_plume", com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY,
                    com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, 1 << 16, false, true,
                    RenderType.CompositeState.builder()
                            .setShaderState(net.minecraft.client.renderer.RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                            .setTextureState(new net.minecraft.client.renderer.RenderStateShard.TextureStateShard(WHITE, false, false))
                            .setTransparencyState(net.minecraft.client.renderer.RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setCullState(net.minecraft.client.renderer.RenderStateShard.NO_CULL)
                            .setLightmapState(net.minecraft.client.renderer.RenderStateShard.LIGHTMAP)
                            .setOverlayState(net.minecraft.client.renderer.RenderStateShard.OVERLAY)
                            .setWriteMaskState(net.minecraft.client.renderer.RenderStateShard.COLOR_WRITE)
                            .createCompositeState(false));
        }
        return type;
    }

    /** 頂点の不透明度(0〜255)。開発用の濃さの倍率 {@link #devDensity} を光学的な厚さとして掛ける。 */
    private static int alpha255(float a) {
        a = Mth.clamp(a, 0.0F, 1.0F);
        if (devDensity != 1.0F) a = 1.0F - (float) Math.pow(1.0F - a, devDensity);
        return (int) (a * 255);
    }

    /** DevBridge の {@code plume}: old / new / density &lt;k&gt; / green &lt;0〜1&gt;。 */
    public static String devCommand(String arg) {
        String[] p = arg.trim().split("\\s+");
        switch (p[0]) {
            case "old" -> devOldType = true;
            case "new" -> devOldType = false;
            case "oldshape" -> devOldShape = true;
            case "newshape" -> devOldShape = false;
            case "density" -> devDensity = Float.parseFloat(p[1]);
            case "green" -> devGreen = Float.parseFloat(p[1]);
            default -> { }
        }
        ClientLevel level = Minecraft.getInstance().level;
        PrevailingWind.Sample w = level != null ? ClientPrevailingWind.sample(level.getDayTime()) : null;
        return "PLUME old=" + devOldType + " density=" + devDensity + " green=" + devGreen + " ribbons=" + lastRibbons
                + " trees=" + java.util.Arrays.toString(ClientSporeAir.trees())
                + (w != null ? String.format(" wind=(%.2f, %.2f) strength=%.2f", w.dirX, w.dirZ, w.strength) : "") + String.format(" rgb=%06x", devRgb);
    }

    /** 升ごとの帯を出す升の数の上限(濃い順)。 */
    private static final int MAX_CHUNK_PLUMES = 40;

    /** 輪の帯の幅(ブロック、外へ向かう向き)と、カメラから描く距離。 */
    private static final double RING_WIDTH = 28.0D, RING_VIEW = 320.0D;

    /**
     * 地割れの圧の前線の輪: 中心 (x, z)、半径 rad の円周に沿って、幅 RING_WIDTH の帯を地上 12・22 ブロックに 2 枚。
     * 帯の内縁と外縁は透明。カメラから RING_VIEW の外の弧は描かない(輪が大きくなっても描く量は増えない)。
     */
    private static int ring(VertexConsumer vc, Matrix4f m, Vec3 cam, ClientLevel level, double x, double z, double rad,
                            float alpha, int r, int g, int b, float time) {
        double dist = Math.hypot(cam.x - x, cam.z - z);
        if (Math.abs(dist - rad) > RING_VIEW + RING_WIDTH) return 0;
        int n = Mth.clamp((int) (rad * Math.PI * 2.0D / 8.0D), 24, 1 << 16); // 8 ブロックごと
        double step = Math.PI * 2.0D / n;
        // カメラから RING_VIEW 以内に入る弧の角度の範囲だけ回す(輪が大きくなっても回す数は増えない)
        int k0 = 0, k1 = n;
        if (dist > 1.0D && rad > 1.0D) {
            double cosw = (dist * dist + rad * rad - RING_VIEW * RING_VIEW) / (2.0D * dist * rad);
            if (cosw > -1.0D) {
                double half = Math.acos(Math.min(1.0D, cosw)) + step;
                double camAng = Math.atan2(cam.z - z, cam.x - x);
                k0 = (int) Math.floor((camAng - half) / step);
                k1 = (int) Math.ceil((camAng + half) / step);
            }
        }
        int drawn = 0;
        for (int kk = k0; kk < k1; kk++) {
            double a0 = kk * step, a1 = a0 + step;
            double mx = x + Math.cos(a0 + step * 0.5D) * rad, mz = z + Math.sin(a0 + step * 0.5D) * rad;
            if ((mx - cam.x) * (mx - cam.x) + (mz - cam.z) * (mz - cam.z) > RING_VIEW * RING_VIEW) continue;
            int ground = level.hasChunk(((int) Math.floor(mx)) >> 4, ((int) Math.floor(mz)) >> 4)
                    ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(mx), (int) Math.floor(mz)) : (int) cam.y - 10;
            float wave = 0.7F + 0.3F * Mth.sin((float) (a0 * 7.0D) - time * 1.3F);
            for (float h : new float[]{12.0F, 22.0F}) {
                double y = ground + h - cam.y;
                // 内縁(0)→ 中(a)→ 外縁(0)の 2 列
                double[] rr = {rad - RING_WIDTH * 0.5D, rad, rad + RING_WIDTH * 0.5D};
                float[] ww = {0.0F, 1.0F, 0.0F};
                for (int l = 0; l < 2; l++) {
                    ringVertex(vc, m, x, z, cam, a0, rr[l], y, alpha * wave * ww[l], r, g, b);
                    ringVertex(vc, m, x, z, cam, a1, rr[l], y, alpha * wave * ww[l], r, g, b);
                    ringVertex(vc, m, x, z, cam, a1, rr[l + 1], y, alpha * wave * ww[l + 1], r, g, b);
                    ringVertex(vc, m, x, z, cam, a0, rr[l + 1], y, alpha * wave * ww[l + 1], r, g, b);
                }
            }
            drawn++;
        }
        return drawn > 0 ? 1 : 0;
    }

    private static void ringVertex(VertexConsumer vc, Matrix4f m, double x, double z, Vec3 cam, double ang, double rr, double y,
                                   float alpha, int r, int g, int b) {
        int a = alpha255(alpha);
        vc.addVertex(m, (float) (x + Math.cos(ang) * rr - cam.x), (float) y, (float) (z + Math.sin(ang) * rr - cam.z))
                .setColor(r, g, b, a).setUv(0.5F, 0.5F).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(0.0F, 1.0F, 0.0F);
    }

    /**
     * 1 本の帯: 起点
 (x, y, z) から向き (dx, dz) へ長さ len、幅 width。横は 3 列(縁は透明)、先へ SEGMENTS 分割。
     * 不透明度は起点で 0 から立ち上がって先へ向かって消え、帯に沿って流れる濃淡を掛ける。帯はわずかに下がりながら広がる。
     */
    private static void ribbon(VertexConsumer vc, Matrix4f m, Vec3 cam, double x, double y, double z, double dx, double dz,
                               double len, float width, float alpha, int r, int g, int b, float time, float flow, float phase) {
        double px = -dz, pz = dx; // 横の向き
        float[][] col = new float[SEGMENTS + 1][];
        for (int s = 0; s <= SEGMENTS; s++) {
            float u = s / (float) SEGMENTS;
            // 起点から u = 0.3 で立ち上がり、終わり(u = 1)へなだらかに 0 へ。前の sin(π × min(1, 1.6u)) は u = 0.625 で 0 になり、
            // 帯の後ろ 4 割が描かれていなかった(2026-10-24。開発用 plume oldshape で前の形)
            float along = devOldShape
                    ? (float) Math.sin(Math.PI * Math.min(1.0F, u * 1.6F)) * (1.0F - u * 0.55F)
                    : (float) (Math.sin(Math.PI * 0.5D * Math.min(1.0F, u / 0.3F)) * Math.pow(1.0F - u, 1.2D));
            float wave = 0.75F + 0.25F * Mth.sin(u * 9.0F - time * flow + phase);
            float spread = 1.0F + u * 1.4F;
            double cx = x + dx * len * u, cz = z + dz * len * u, cy = y - u * 3.0D;
            float hw = width * 0.5F * spread;
            col[s] = new float[]{(float) (cx - cam.x), (float) (cy - cam.y), (float) (cz - cam.z), hw, alpha * along * wave};
        }
        for (int s = 0; s < SEGMENTS; s++) {
            float[] p0 = col[s], p1 = col[s + 1];
            // 横 3 列: 外縁(0)→ 中(a)→ 中(a)→ 外縁(0)
            float[] lane = {-1.0F, -0.35F, 0.35F, 1.0F};
            float[] w = {0.0F, 1.0F, 1.0F, 0.0F};
            for (int l = 0; l < 3; l++) {
                quad(vc, m, p0, p1, px, pz, lane[l], lane[l + 1], w[l], w[l + 1], r, g, b);
            }
        }
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float[] p0, float[] p1, double px, double pz,
                             float l0, float l1, float w0, float w1, int r, int g, int b) {
        vertex(vc, m, p0, px, pz, l0, w0, r, g, b);
        vertex(vc, m, p1, px, pz, l0, w0, r, g, b);
        vertex(vc, m, p1, px, pz, l1, w1, r, g, b);
        vertex(vc, m, p0, px, pz, l1, w1, r, g, b);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, float[] p, double px, double pz, float lane, float weight,
                               int r, int g, int b) {
        float off = p[3] * lane;
        int a = alpha255(p[4] * weight);
        vc.addVertex(m, p[0] + (float) (px * off), p[1], p[2] + (float) (pz * off))
                .setColor(r, g, b, a).setUv(0.5F, 0.5F).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(0.0F, 1.0F, 0.0F);
    }
}
