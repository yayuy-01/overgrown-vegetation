package com.yayuy.overgrownvegetation.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * バニラの GUI と同じ見た目の部品(2026-10-14、研究台・パワードアーマー作成テーブルを仮でバニラの窓に戻したときに作った)。
 *
 * <p>窓・スロット・タブ・矢印は {@code textures/gui/container/crafting_table.png} を 1 ピクセルずつ測って塗る(どの大きさでも描ける)。
 * ボタンはバニラのスプライト {@code widget/button*}(9 分割で伸びる)をそのまま使う。
 * 世界観に合わせた絵に作り直すときは、ここを差し替えるか使うのをやめる。
 */
final class VanillaGui {

    static final ResourceLocation BUTTON = ResourceLocation.withDefaultNamespace("widget/button");
    static final ResourceLocation BUTTON_HIGHLIGHTED = ResourceLocation.withDefaultNamespace("widget/button_highlighted");
    static final ResourceLocation BUTTON_DISABLED = ResourceLocation.withDefaultNamespace("widget/button_disabled");
    static final ResourceLocation FRAME_OBTAINED = ResourceLocation.withDefaultNamespace("advancements/task_frame_obtained");
    static final ResourceLocation FRAME_UNOBTAINED = ResourceLocation.withDefaultNamespace("advancements/task_frame_unobtained");
    /** 進捗画面の「冒険」タブと同じ石の背景(16×16 を敷き詰める)。 */
    static final ResourceLocation STONE_BG = ResourceLocation.withDefaultNamespace("textures/gui/advancements/backgrounds/stone.png");

    /** 窓の上の文字(バニラのタイトル・「インベントリ」と同じ色)。 */
    static final int TEXT = 0xFF404040;
    static final int TEXT_DIM = 0xFF707070;
    /** 窓の灰色の上で読める緑と赤。 */
    static final int GOOD = 0xFF207A20;
    static final int BAD = 0xFFA02020;
    /** ボタンの文字(バニラの Button と同じ)。 */
    static final int BUTTON_TEXT = 0xFFFFFFFF;
    static final int BUTTON_TEXT_OFF = 0xFFA0A0A0;

    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int FILL = 0xFFC6C6C6;
    private static final int SHADE = 0xFF555555;
    private static final int SLOT_DARK = 0xFF373737;
    private static final int SLOT_FILL = 0xFF8B8B8B;
    private static final int TAB_OFF_FILL = 0xFFA6A6A6;
    private static final int TAB_OFF_LIGHT = 0xFFD8D8D8;

    /** 作業台の矢印(→)の大きさ。 */
    static final int ARROW_W = 22;
    static final int ARROW_H = 15;

    private VanillaGui() {
    }

    /** 窓(角の丸い灰色の板)。作業台の背景の縁と同じ並び。 */
    static void panel(GuiGraphics g, int x, int y, int w, int h) {
        int r = x + w;
        int b = y + h;
        g.fill(x + 3, y + 3, r - 3, b - 3, FILL);
        g.fill(x + 2, y + 1, r - 3, y + 3, WHITE);
        g.fill(x + 1, y + 2, x + 3, b - 3, WHITE);
        g.fill(x + 3, y + 3, x + 4, y + 4, WHITE);
        g.fill(r - 3, y + 2, r - 2, y + 3, FILL);
        g.fill(x + 2, b - 3, x + 3, b - 2, FILL);
        g.fill(r - 3, y + 3, r - 1, b - 2, SHADE);
        g.fill(x + 3, b - 3, r - 1, b - 2, SHADE);
        g.fill(x + 3, b - 2, r - 2, b - 1, SHADE);
        g.fill(r - 4, b - 4, r - 3, b - 3, SHADE);
        // 縁の黒
        g.fill(x + 2, y, r - 3, y + 1, BLACK);
        g.fill(x + 1, y + 1, x + 2, y + 2, BLACK);
        g.fill(r - 3, y + 1, r - 2, y + 2, BLACK);
        g.fill(r - 2, y + 2, r - 1, y + 3, BLACK);
        g.fill(x, y + 2, x + 1, b - 3, BLACK);
        g.fill(r - 1, y + 3, r, b - 2, BLACK);
        g.fill(x + 1, b - 3, x + 2, b - 2, BLACK);
        g.fill(x + 2, b - 2, x + 3, b - 1, BLACK);
        g.fill(r - 2, b - 2, r - 1, b - 1, BLACK);
        g.fill(x + 3, b - 1, r - 2, b, BLACK);
    }

    /**
     * へこんだ枠(スロットと同じ: 左上が暗く、右下が白い)。{@code x}/{@code y} は枠の左上。
     * 18×18 ならふつうのスロット(アイテムは +1)、26×26 なら作業台の成果の枠(アイテムは +5)。
     */
    static void slot(GuiGraphics g, int x, int y, int w, int h) {
        int r = x + w;
        int b = y + h;
        g.fill(x, y, r, b, SLOT_FILL);
        g.fill(x, y, r - 1, y + 1, SLOT_DARK);
        g.fill(x, y, x + 1, b - 1, SLOT_DARK);
        g.fill(x + 1, b - 1, r, b, WHITE);
        g.fill(r - 1, y + 1, r, b, WHITE);
    }

    /** ふつうのスロットの枠(アイテムの左上 {@code itemX}/{@code itemY} の 1 外側)。 */
    static void slotAround(GuiGraphics g, int itemX, int itemY) {
        slot(g, itemX - 1, itemY - 1, 18, 18);
    }

    /** バニラのボタン。押せないときは灰色、マウスが乗ると白い縁。 */
    static void button(GuiGraphics g, int x, int y, int w, int h, boolean enabled, boolean hovered) {
        g.blitSprite(!enabled ? BUTTON_DISABLED : hovered ? BUTTON_HIGHLIGHTED : BUTTON, x, y, w, h);
    }

    /** ボタンと真ん中の文字(入りきらなければ「…」で切る)。 */
    static void button(GuiGraphics g, Font font, Component text, int x, int y, int w, int h, boolean enabled, boolean hovered) {
        button(g, x, y, w, h, enabled, hovered);
        String s = trim(font, text.getString(), w - 6);
        g.drawString(font, s, x + (w - font.width(s)) / 2, y + (h - 8) / 2 + 1,
                enabled ? BUTTON_TEXT : BUTTON_TEXT_OFF, true);
    }

    /**
     * 窓の上に出たタブ(クリエイティブのタブを文字で)。{@code windowY} は窓の上端。選んだタブは窓とつながり、
     * 選んでいないタブは少し暗く窓の後ろに隠れる(選んでいないタブは窓より先に描くこと)。
     */
    static void tab(GuiGraphics g, Font font, Component label, int x, int windowY, int w, int h, boolean selected) {
        int y = windowY - h + (selected ? 0 : 2);
        int r = x + w;
        int fill = selected ? FILL : TAB_OFF_FILL;
        int light = selected ? WHITE : TAB_OFF_LIGHT;
        int bottom = selected ? windowY + 3 : windowY;
        g.fill(x + 1, y + 1, r - 1, bottom, fill);
        g.fill(x + 2, y + 1, r - 2, y + 3, light);
        g.fill(x + 1, y + 2, x + 3, bottom, light);
        g.fill(r - 3, y + 3, r - 1, bottom, SHADE);
        g.fill(x + 2, y, r - 2, y + 1, BLACK);
        g.fill(x + 1, y + 1, x + 2, y + 2, BLACK);
        g.fill(r - 2, y + 1, r - 1, y + 2, BLACK);
        g.fill(x, y + 2, x + 1, bottom, BLACK);
        g.fill(r - 1, y + 2, r, bottom, BLACK);
        if (selected) {
            // 窓の上の縁を消してつなげる(左右の縁は窓の白・影へ続ける)
            g.fill(x + 3, windowY, r - 3, windowY + 3, FILL);
        }
        String s = trim(font, label.getString(), w - 8);
        g.drawString(font, s, x + (w - font.width(s)) / 2, y + (h - 8) / 2 + (selected ? 0 : -1), TEXT, false);
    }

    /** 作業台の「→」(バニラの作業台の背景の矢印と同じ形・色。切り出すと周りの灰色まで付くので塗る)。 */
    static void arrow(GuiGraphics g, int x, int y) {
        g.fill(x, y + 6, x + 14, y + 9, SLOT_FILL);
        for (int i = 0; i < ARROW_H; i++) {
            int w = 8 - Math.abs(i - 7);
            g.fill(x + 14, y + i, x + 14 + w, y + i + 1, SLOT_FILL);
        }
    }

    /** 石の背景を {@code x0..x1, y0..y1} に敷き詰める(はみ出しは呼ぶ側の scissor で切る)。{@code ox}/{@code oy} は模様のずらし。 */
    static void stone(GuiGraphics g, int x0, int y0, int x1, int y1, int ox, int oy) {
        int sx = x0 - Math.floorMod(ox, 16);
        int sy = y0 - Math.floorMod(oy, 16);
        for (int ty = sy; ty < y1; ty += 16) {
            for (int tx = sx; tx < x1; tx += 16) {
                g.blit(STONE_BG, tx, ty, 0.0F, 0.0F, 16, 16, 16, 16);
            }
        }
    }

    static String trim(Font font, String s, int maxWidth) {
        if (font.width(s) <= maxWidth) {
            return s;
        }
        return font.plainSubstrByWidth(s, Math.max(0, maxWidth - font.width("…"))) + "…";
    }
}
