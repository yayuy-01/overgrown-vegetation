package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.SporeExposurePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import org.joml.Matrix4f;

/**
 * 胞子の暴露量の前触れ = 画面の端の緑のにじみ(2026-11-04 感染システムの第 2 段。ユーザー決定「咳き込みは入れず画面のにじみだけ」)。
 *
 * <ul>
 *     <li>サーバーから {@link SporeExposurePayload}(暴露量の割合と、出始め・濃くなりきる割合)を受ける。</li>
 *     <li>仮決め: 暴露量が半分(750 %秒)を超えたら薄く、8 割(1200)で濃くなりきる。間はなめらかに。効果やダメージは無く、知らせるだけ。</li>
 *     <li>仮決め: 「にじみ」はぼかしのシェーダーを使わず、画面の 4 辺から内へ消えていく薄い緑の帯で描く。辺に沿って厚みと濃さを
 *     ゆっくり揺らし、染みが広がったように見せる。表示の強さは受けた値へ 1 秒ほどかけて寄せる。</li>
 *     <li>GUI の層(カメラの重ね絵 {@code CAMERA_OVERLAYS} の上 = カボチャの顔や凍えの霜と同じ並び)。雰囲気の描画・シェーダーには触らない。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SporeExposureClient {

    /** 帯の色(薄い黄緑、胞子の色)。 */
    private static final float R = 0.56F, G = 0.74F, B = 0.34F;
    /** 濃くなりきったときの外縁の不透明度。 */
    private static final float MAX_ALPHA = 0.62F;
    /** 出始めの強さ(0〜1)。半分を超えた瞬間でも見えるように。 */
    private static final float FAINT_START = 0.3F;
    /** 辺 1 本を何区画に割って揺らすか。 */
    private static final int SEGMENTS = 16;

    private static float fraction;
    private static float faint = 0.5F;
    private static float strong = 0.8F;
    private static float shown;
    private static long lastNanos;

    public static void accept(SporeExposurePayload payload) {
        fraction = payload.fraction();
        faint = payload.faint();
        strong = Math.max(payload.faint() + 1.0E-3F, payload.strong());
    }

    /** 目標の強さ(0〜1)。出始めの割合以下は 0。 */
    public static float targetIntensity(float fraction, float faint, float strong) {
        if (fraction <= faint) return 0.0F;
        float t = Mth.clamp((fraction - faint) / (strong - faint), 0.0F, 1.0F);
        return FAINT_START + (1.0F - FAINT_START) * t;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        fraction = 0.0F;
        shown = 0.0F;
    }

    private static void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0.0F : Math.min(0.25F, (now - lastNanos) / 1.0E9F);
        lastNanos = now;
        float target = mc.player == null || mc.player.isSpectator() ? 0.0F : targetIntensity(fraction, faint, strong);
        shown += (target - shown) * Math.min(1.0F, dt * 1.5F);
        if (shown < 0.004F) return;

        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        float time = (mc.level != null ? mc.level.getGameTime() + partialTick : 0.0F) / 20.0F;
        float alpha = MAX_ALPHA * shown;
        float depth = Math.min(w, h) * (0.10F + 0.16F * shown);

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f m = graphics.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        // 2 重に描く: 外の濃い薄帯 + 内へ長く消える帯(端ほど急に濃く、内へなだらかに)
        edges(b, m, w, h, depth * 0.45F, alpha * 0.55F, time, 0.0F);
        edges(b, m, w, h, depth, alpha * 0.6F, time * 0.7F, 3.1F);
        BufferBuilder.RenderedBuffer mesh = b.endOrDiscardIfEmpty();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    /** 4 辺の帯。外の縁が {@code alpha}、内へ 0 に消える。厚みと濃さは辺に沿ってゆっくり揺れる。 */
    private static void edges(BufferBuilder b, Matrix4f m, int w, int h, float depth, float alpha, float time, float seed) {
        for (int side = 0; side < 4; side++) {
            boolean horizontal = side < 2;
            float len = horizontal ? w : h;
            for (int i = 0; i < SEGMENTS; i++) {
                float u0 = len * i / SEGMENTS, u1 = len * (i + 1) / SEGMENTS;
                float d0 = depth * wobble(side, i, time, seed), d1 = depth * wobble(side, i + 1, time, seed);
                float a0 = alpha * (0.75F + 0.25F * wobble(side, i, time * 1.3F, seed + 7.0F));
                float a1 = alpha * (0.75F + 0.25F * wobble(side, i + 1, time * 1.3F, seed + 7.0F));
                // 角は隣の辺と重なって少し濃くなる(にじみが角に溜まったように見える)
                switch (side) {
                    case 0 -> quad(b, m, u0, 0, u1, 0, u1, d1, u0, d0, a0, a1);           // 上
                    case 1 -> quad(b, m, u1, h, u0, h, u0, h - d0, u1, h - d1, a1, a0);   // 下
                    case 2 -> quad(b, m, 0, u1, 0, u0, d0, u0, d1, u1, a1, a0);           // 左
                    default -> quad(b, m, w, u0, w, u1, w - d1, u1, w - d0, u0, a0, a1);  // 右
                }
            }
        }
    }

    /** 外の 2 点(濃さ aOut0・aOut1)と内の 2 点(透明)の四角。頂点は外 0 → 外 1 → 内 1 → 内 0 の順。 */
    private static void quad(BufferBuilder b, Matrix4f m, float ox0, float oy0, float ox1, float oy1,
                             float ix1, float iy1, float ix0, float iy0, float aOut0, float aOut1) {
        b.vertex(m, ox0, oy0, 0.0F).color(R, G, B, aOut0).endVertex();
        b.vertex(m, ix0, iy0, 0.0F).color(R, G, B, 0.0F).endVertex();
        b.vertex(m, ix1, iy1, 0.0F).color(R, G, B, 0.0F).endVertex();
        b.vertex(m, ox1, oy1, 0.0F).color(R, G, B, aOut1).endVertex();
    }

    /** 0.55〜1.0 のゆっくりした揺れ(区画の番号と時刻から。乱数を使わないので毎フレーム同じ形が続く)。 */
    private static float wobble(int side, int i, float time, float seed) {
        float x = i * 0.9F + side * 5.3F + seed;
        float v = Mth.sin(x + time * 0.35F) * 0.5F + Mth.sin(x * 2.3F - time * 0.22F + 1.7F) * 0.3F
                + Mth.sin(x * 0.37F + time * 0.11F) * 0.2F;
        return 0.775F + 0.225F * v;
    }

    /** GUI の層の登録(MOD バス)。 */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Layers {
        // 1.20.1: 受け手のメソッドの名前はクラスごとに変える(Forge の EventBus は「クラスの単純名_メソッド名_イベントの単純名」で包みを作って使い回すので、同じパッケージの同じ名前の入れ子のクラスがぶつかり、前の受け手が 2 回呼ばれる)
        @SubscribeEvent
        public static void onSporeExposureClientLayers(RegisterGuiOverlaysEvent event) {
            // 1.20.1: 1.21 の CAMERA_OVERLAYS(ビネット・望遠鏡・かぼちゃ・凍え・ポータル)の後 = PORTAL の後
            event.registerAbove(VanillaGuiOverlay.PORTAL.id(), "spore_exposure_haze",
                    SporeExposureClient::render);
        }

        private Layers() {
        }
    }

    private SporeExposureClient() {
    }
}
