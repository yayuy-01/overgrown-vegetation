package com.yayuy.overgrownvegetation.client.survival;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.SurvivalHudPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * HUD の 2 本のゲージ(2026-11-13 サバイバルの背骨 段 1)。値はサーバーから {@link SurvivalHudPayload} で届く。
 *
 * <h2>ユーザー指定</h2>
 * <ul>
 *     <li><b>暴露量のバー</b>: 経験値バーの上。経験値バーとハート・食料の行の間に入れ、ハート・食料・防具・泡を上へずらす。
 *     感染中は暴露量ではなく「次の段階までの進み」と「今の段階」を見せる(色を変える)。</li>
 *     <li><b>疲労</b>: 食料の行の上に、アイコン 10 個の列(水中の泡はその上へずれる)。</li>
 * </ul>
 *
 * <h2>仮決め</h2>
 * <ul>
 *     <li>バーは経験値バーの 1 画素上に高さ 5。経験値のレベルの数字(バーの真ん中に重なって描かれる)とぶつけないよう、
 *     真ん中 20 画素をあけて左右 81 画素ずつに分ける(左はハートの列、右は食料の列の真下にそろう)。値は左の端から右の端へ通して満ちる。</li>
 *     <li>感染中は段階の色(感染1 の橙 → 感染10 の暗い赤)で塗り、今の段階の数字をバーの左に出す。</li>
 *     <li>疲労の列は食料と同じく右から満ちる。効き目が出始める割合を越えたら赤みを帯びる。</li>
 *     <li>どちらも F1(GUI を隠す)・創造・観戦では描かず、行もずらさない(自前の層はバニラの判定の外なので自分で見る)。</li>
 *     <li>絵は仮({@code textures/gui/sprites/hud/}。後でユーザーが描き直す)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SurvivalHud {

    private static final ResourceLocation BAR_BG = OvergrownVegetation.id("hud/exposure_bar_background");
    private static final ResourceLocation BAR_EXPOSURE = OvergrownVegetation.id("hud/exposure_bar_progress");
    private static final ResourceLocation BAR_INFECTION = OvergrownVegetation.id("hud/infection_bar_progress");
    private static final ResourceLocation FATIGUE_EMPTY = OvergrownVegetation.id("hud/fatigue_empty");
    private static final ResourceLocation FATIGUE_HALF = OvergrownVegetation.id("hud/fatigue_half");
    private static final ResourceLocation FATIGUE_FULL = OvergrownVegetation.id("hud/fatigue_full");

    /** バーの半分の幅・高さと、バーがずらす行の高さ(バー 5 + すき間 1)。 */
    public static final int HALF_WIDTH = 81, BAR_HEIGHT = 5, BAR_ROW = 6;
    /** 真ん中のすき間(経験値のレベルの数字の場所)。 */
    public static final int GAP = 20;

    private static float exposure;
    private static int stage;
    private static float stageProgress;
    private static float fatigue;
    private static float effectsFrom = 0.5F;
    private static boolean received;
    /** 表示の値(受けた値へなめらかに寄せる。段階が変わったら飛ぶ)。 */
    private static float shownBar;
    private static int shownStage = -1;
    private static long lastNanos;

    private SurvivalHud() {
    }

    public static void accept(SurvivalHudPayload p) {
        exposure = p.exposure();
        stage = p.stage();
        stageProgress = p.stageProgress();
        fatigue = p.fatigue();
        effectsFrom = p.effectsFrom();
        received = true;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        exposure = 0.0F;
        stage = 0;
        stageProgress = 0.0F;
        fatigue = 0.0F;
        received = false;
        shownBar = 0.0F;
        shownStage = -1;
    }

    /** 描くか: F1 で隠していない・ダメージを受けるモード(サバイバル・アドベンチャー)・プレイヤーがいる。 */
    private static boolean visible(Minecraft mc) {
        return !mc.options.hideGui && mc.player != null && mc.gameMode != null && mc.gameMode.canHurtPlayer();
    }

    /** バーの段階の色(感染1 の橙 → 感染10 の暗い赤)。ARGB の RGB 部分を 0〜1 で。 */
    public static float[] stageColor(int stage) {
        float t = Mth.clamp((stage - 1) / 9.0F, 0.0F, 1.0F);
        return new float[]{Mth.lerp(t, 1.0F, 0.62F), Mth.lerp(t, 0.62F, 0.12F), Mth.lerp(t, 0.22F, 0.10F)};
    }

    /** 塗る画素の数(左右あわせて 0〜162)。 */
    public static int fillPixels(float fraction) {
        return Mth.clamp(Math.round(fraction * HALF_WIDTH * 2), 0, HALF_WIDTH * 2);
    }

    private static void renderBar(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!visible(mc)) return;
        Gui gui = mc.gui;
        int w = g.guiWidth(), h = g.guiHeight();
        int y = h - 29 - BAR_ROW;
        int left = w / 2 - 91, right = w / 2 + GAP / 2;

        float target = stage > 0 ? stageProgress : exposure;
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 1.0F : Math.min(1.0F, (now - lastNanos) / 1.0E9F);
        lastNanos = now;
        if (shownStage != stage) {
            shownStage = stage;
            shownBar = target;
        } else {
            shownBar += (target - shownBar) * Math.min(1.0F, dt * 4.0F);
        }

        RenderSystem.enableBlend();
        g.blitSprite(BAR_BG, left, y, HALF_WIDTH, BAR_HEIGHT);
        g.blitSprite(BAR_BG, right, y, HALF_WIDTH, BAR_HEIGHT);
        int fill = received ? fillPixels(shownBar) : 0;
        ResourceLocation sprite = stage > 0 ? BAR_INFECTION : BAR_EXPOSURE;
        if (stage > 0) {
            float[] c = stageColor(stage);
            g.setColor(c[0], c[1], c[2], 1.0F);
        }
        int l = Math.min(fill, HALF_WIDTH), r = Math.max(0, fill - HALF_WIDTH);
        if (l > 0) g.blitSprite(sprite, HALF_WIDTH, BAR_HEIGHT, 0, 0, left, y, l, BAR_HEIGHT);
        if (r > 0) g.blitSprite(sprite, HALF_WIDTH, BAR_HEIGHT, 0, 0, right, y, r, BAR_HEIGHT);
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();

        if (stage > 0) {
            Font font = mc.font;
            String s = Integer.toString(stage);
            int tx = left - 3 - font.width(s), ty = y - 1;
            float[] c = stageColor(stage);
            int color = 0xFF000000 | ((int) (c[0] * 255) << 16) | ((int) (c[1] * 255) << 8) | (int) (c[2] * 255);
            g.drawString(font, s, tx + 1, ty, 0xFF000000, false);
            g.drawString(font, s, tx - 1, ty, 0xFF000000, false);
            g.drawString(font, s, tx, ty + 1, 0xFF000000, false);
            g.drawString(font, s, tx, ty - 1, 0xFF000000, false);
            g.drawString(font, s, tx, ty, color, false);
        }
        gui.leftHeight += BAR_ROW;
        gui.rightHeight += BAR_ROW;
    }

    private static void renderFatigue(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!visible(mc)) return;
        Gui gui = mc.gui;
        int w = g.guiWidth(), h = g.guiHeight();
        int y = h - gui.rightHeight;
        int xRight = w / 2 + 91;
        int units = received ? Mth.clamp(Math.round(fatigue * 20.0F), 0, 20) : 0;
        boolean heavy = fatigue > effectsFrom;
        RenderSystem.enableBlend();
        for (int i = 0; i < 10; i++) {
            int x = xRight - i * 8 - 9;
            g.blitSprite(FATIGUE_EMPTY, x, y, 9, 9);
            if (heavy) g.setColor(1.0F, 0.72F, 0.72F, 1.0F);
            if (i * 2 + 1 < units) {
                g.blitSprite(FATIGUE_FULL, x, y, 9, 9);
            } else if (i * 2 + 1 == units) {
                g.blitSprite(FATIGUE_HALF, x, y, 9, 9);
            }
            if (heavy) g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        RenderSystem.disableBlend();
        gui.rightHeight += 10;
    }

    /** GUI の層の登録(MOD バス)。 */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Layers {
        @SubscribeEvent
        public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.EXPERIENCE_BAR, OvergrownVegetation.id("spore_exposure_bar"), SurvivalHud::renderBar);
            event.registerAbove(VanillaGuiLayers.FOOD_LEVEL, OvergrownVegetation.id("fatigue"), SurvivalHud::renderFatigue);
        }

        private Layers() {
        }
    }
}
