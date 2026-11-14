package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * 監視塔フィロセンスに見られているときの画面の縁(2026-11-14。Vault「見られているときの画面の縁の効果」)。
 *
 * <p><b>今は仕組みだけ・仮の見た目</b>: サーバーが送る強さ({@code PhyllosensGazePayload}。視線が通った瞬間 0 → 印の 2 秒で 1 → 見られている間 1)
 * を受け、縁全体に均一な暗い縁(縁から内へ薄くなる黒い帯)を出す。方角は出さない(ユーザー決定)。F1 では消す【仮】。
 * 送られてこなくなって {@link #HOLD_TICKS} たったら、{@link #FADE_PER_TICK} ずつ引く【仮】。
 *
 * <p><b>見た目の差し替え(Fable の作業)</b>: {@link #render} の中身だけを替えればよい(強さは {@link #strength(float)})。
 * 決まっている見た目: 暗い縁がゆっくり脈打つ + 濃いときだけ縁に目の印がうっすら浮かぶ(F5 の合図と同じ絵・ユーザーの絵待ち)。
 * 暴露量のもや({@code SporeExposureClient} の緑がかったにじみ)と見分けられること。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class PhyllosensGazeEdge {

    /** 縁の帯の数・縁から内への深さ（画面の短い辺に対する割合）【仮】。 */
    static final int BANDS = 14;
    static final float EDGE_DEPTH = 0.22F;
    /** 強さ 1 のときの暗さ(0〜1)【仮】。 */
    static final float MAX_DARK = 0.85F;
    /** 送られてこなくなってから引き始めるまで・1 tick に引く量【仮】(1 → 0 を 1 秒)。 */
    static final int HOLD_TICKS = 8;
    static final float FADE_PER_TICK = 0.05F, RISE_PER_TICK = 0.25F;

    private static float target, value, value0;
    private static int sinceMessage = 1000;

    private PhyllosensGazeEdge() {
    }

    /** サーバーから(メインスレッドで)。 */
    public static void receive(float strength) {
        // いくつかの塔に見られているときは強い方(同じ tick の中の知らせの大きい方)
        target = sinceMessage <= 0 ? Math.max(target, strength) : strength;
        sinceMessage = 0;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        value0 = value;
        if (++sinceMessage > HOLD_TICKS) target = 0.0F;
        float t = Mth.clamp(target, 0.0F, 1.0F);
        value += Mth.clamp(t - value, -FADE_PER_TICK, RISE_PER_TICK);
        if (Minecraft.getInstance().player == null) {
            value = value0 = target = 0.0F;
        }
    }

    /** 今の強さ(0〜1、描画の補間)。 */
    public static float strength(float partialTick) {
        return Mth.lerp(partialTick, value0, value);
    }

    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        float s = strength(delta.getGameTimeDeltaPartialTick(false));
        if (s <= 0.01F) return;
        // 仮の見た目: 縁から内へ BANDS 本の黒い帯（外ほど濃い）。四辺とも同じ（方角は出さない）
        int w = g.guiWidth(), h = g.guiHeight();
        int depth = Math.max(8, (int) (Math.min(w, h) * EDGE_DEPTH));
        for (int i = 0; i < BANDS; i++) {
            float f = 1.0F - i / (float) BANDS;
            int alpha = (int) (255 * s * MAX_DARK * f * f);
            if (alpha <= 0) continue;
            int c = alpha << 24;
            int o = depth * i / BANDS, o2 = depth * (i + 1) / BANDS;
            g.fill(o, o, w - o, o2, c);
            g.fill(o, h - o2, w - o, h - o, c);
            g.fill(o, o2, o2, h - o2, c);
            g.fill(w - o2, o2, w - o, h - o2, c);
        }
    }

    /** GUI の層の登録(MOD バス)。 */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Layers {
        @SubscribeEvent
        public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, OvergrownVegetation.id("phyllosens_gaze_edge"), PhyllosensGazeEdge::render);
        }

        private Layers() {
        }
    }
}
