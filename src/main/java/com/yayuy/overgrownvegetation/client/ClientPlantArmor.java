package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.item.PlantArmor;
import com.yayuy.overgrownvegetation.network.PlantArmorGrowthPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 「寄生植物装備」の成長の進みをクライアントで持ち、HUD に出す(ユーザー要望 2026-09-18: 成長までの時間をしっかり見たい)。
 *
 * <p>値はサーバーから毎秒届く {@link PlantArmorGrowthPayload}。届いてからの経過 tick を足して表示するので、
 * 秒表示が 1 秒ごとに滑らかに進む。着けていないとき(負の値)は何も出さない。
 *
 * <p>HUD は画面左上に 2 行: 「寄生植物装備 第 N 段階」と、次の段階までの残り時間 + 進み具合のバー。
 * 最終段階では装着した時間の合計を出す。F3 のデバッグ画面と GUI 非表示(F1)のときは出さない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class ClientPlantArmor {

    private static int ticks = -1;
    private static int stage = 1;
    private static long receivedAt = 0L;

    private static final int BAR_WIDTH = 96;
    private static final int BAR_HEIGHT = 5;
    private static final int COLOR_TITLE = 0xFFB0E100;
    private static final int COLOR_TEXT = 0xFFE8FF93;
    private static final int COLOR_BAR_BACK = 0xC0202A16;
    private static final int COLOR_BAR_FILL = 0xFF9ECA05;
    private static final int COLOR_BAR_EDGE = 0xFF334214;

    private ClientPlantArmor() {
    }

    public static void accept(PlantArmorGrowthPayload payload) {
        ticks = payload.ticks();
        stage = payload.stage();
        Minecraft mc = Minecraft.getInstance();
        receivedAt = mc.level != null ? mc.level.getGameTime() : 0L;
    }

    /** いまの装着時間(tick)。届いていない・着けていないときは負の値。 */
    public static int ticks() {
        if (ticks < 0) {
            return -1;
        }
        Minecraft mc = Minecraft.getInstance();
        long now = mc.level != null ? mc.level.getGameTime() : receivedAt;
        // 次のパケット(1 秒)までの間は手元で進める。取りこぼしても 3 秒以上は先へ進めない。
        return ticks + (int) Math.max(0L, Math.min(60L, now - receivedAt));
    }

    /** 次の段階に上がる装着時間(tick)。最終段階なら負の値。 */
    public static long nextStageTicks(PlantArmor.Stage stage) {
        var cfg = OvergrownVegetationConfig.COMMON;
        return switch (stage) {
            case ONE -> cfg.plantArmorStage2Minutes.get() * 1200L;
            case TWO -> cfg.plantArmorStage3Minutes.get() * 1200L;
            default -> -1L;
        };
    }

    /** いまの段階の始まりの装着時間(tick)。進み具合のバーの左端。 */
    public static long stageStartTicks(PlantArmor.Stage stage) {
        var cfg = OvergrownVegetationConfig.COMMON;
        return switch (stage) {
            case ONE -> 0L;
            case TWO -> cfg.plantArmorStage2Minutes.get() * 1200L;
            default -> cfg.plantArmorStage3Minutes.get() * 1200L;
        };
    }

    /** tick を「h:mm:ss」か「mm:ss」にする。 */
    public static String formatTicks(long ticks) {
        long seconds = Math.max(0L, ticks) / 20L;
        long h = seconds / 3600L, m = (seconds % 3600L) / 60L, s = seconds % 60L;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }

    /** 残り時間の文字列(最終段階なら空)。ツールチップと HUD で共用。 */
    public static String remainingText(PlantArmor.Stage stage) {
        long next = nextStageTicks(stage);
        int now = ticks();
        if (next < 0 || now < 0) {
            return "";
        }
        return formatTicks(next - now);
    }

    /** いまの段階の中での進み具合 0〜1(最終段階は 1)。 */
    public static float progress(PlantArmor.Stage stage) {
        long next = nextStageTicks(stage);
        int now = ticks();
        if (next < 0) {
            return 1.0F;
        }
        long start = stageStartTicks(stage);
        if (now < 0 || next <= start) {
            return 0.0F;
        }
        return (float) Math.max(0.0, Math.min(1.0, (now - start) / (double) (next - start)));
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen() || !PlantArmor.isHost(mc.player)) {
            return;
        }
        if (ticks() < 0) {
            return; // まだサーバーから届いていない
        }
        PlantArmor.Stage current = PlantArmor.stageOf(PlantArmor.worn(mc.player));
        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int x = 6, y = 6;
        g.drawString(font, Component.translatable("hud.overgrown_vegetation.plant_armor.title", current.number), x, y, COLOR_TITLE, true);
        y += font.lineHeight + 2;
        if (current == PlantArmor.Stage.THREE) {
            g.drawString(font, Component.translatable("hud.overgrown_vegetation.plant_armor.final", formatTicks(ticks())), x, y, COLOR_TEXT, true);
            return;
        }
        g.drawString(font, Component.translatable("hud.overgrown_vegetation.plant_armor.next", remainingText(current)), x, y, COLOR_TEXT, true);
        y += font.lineHeight + 2;
        int fill = Math.round(progress(current) * (BAR_WIDTH - 2));
        g.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, COLOR_BAR_EDGE);
        g.fill(x + 1, y + 1, x + BAR_WIDTH - 1, y + BAR_HEIGHT - 1, COLOR_BAR_BACK);
        if (fill > 0) {
            g.fill(x + 1, y + 1, x + 1 + fill, y + BAR_HEIGHT - 1, COLOR_BAR_FILL);
        }
        String pct = Math.round(progress(current) * 100.0F) + "%";
        g.drawString(font, pct, x + BAR_WIDTH + 4, y - 1, COLOR_TEXT, true);
    }
}
