package com.yayuy.overgrownvegetation.menu;

/**
 * 研究台 UI の<b>唯一のレイアウト定義</b>。
 *
 * <h2>考え方: 固定サイズの「デザインキャンバス」</h2>
 * UI は {@link #CANVAS_W}×{@link #CANVAS_H} の固定キャンバスとして設計する。ここに書かれた座標は
 * すべてこのキャンバス上のピクセル。画面サイズへの追従は {@code ResearchTableScreen} が
 * <b>一様スケール 1 つ</b>(縦横同率・中央寄せ・余りは余白)で行うので縦横比は崩れない。
 * つまり <b>UI 全体を 1 枚の絵として描ける</b>。
 *
 * <h2>テクスチャは 1 枚(アトラス)</h2>
 * {@code textures/gui/research_ui.png}({@link #TEX_W}×{@link #TEX_H})だけを使う。
 * 上部 {@link #TEX_W}×{@code CANVAS_H*TEX_SCALE} が UI 背景そのもの(キャンバスの
 * {@link #TEX_SCALE} 倍解像度)で、その下にノード枠などの繰り返しスプライトを並べてある。
 * 位置は {@link Sprite} にまとめてあるので、絵を描き替えたらここを直すだけでよい。
 *
 * <p>下絵(ガイド画像)は {@code tools/research-ui} の生成器がこのクラスの定数から直接作る。
 * レイアウトを変えたら生成し直せば、絵と座標がずれない。
 *
 * <h2>スロット座標</h2>
 * {@link net.minecraft.world.inventory.Slot#x}/{@code y} は 1.21.1 で final なのでメニュー生成時に
 * 確定させる必要がある。{@link ResearchTableMenu} はここの定数をそのまま使う
 * (画面側は {@code leftPos}/{@code topPos} を 0 にして、キャンバス座標 = スロット座標にする)。
 */
public final class ResearchUi {

    // ===================== キャンバス =====================

    /** デザインキャンバスの幅(px)。16:9。 */
    public static final int CANVAS_W = 640;
    /** デザインキャンバスの高さ(px)。 */
    public static final int CANVAS_H = 360;

    // ===================== アトラス(1 枚絵)=====================

    /** アトラスはキャンバスの何倍解像度か。上げれば絵は精細になる(座標は変えなくてよい)。 */
    public static final int TEX_SCALE = 2;
    public static final int TEX_W = CANVAS_W * TEX_SCALE;                 // 1280
    /** UI 背景(720)+ スプライト帯(240)。 */
    public static final int TEX_H = CANVAS_H * TEX_SCALE + 240;           // 960

    /** アトラス内の矩形(テクスチャピクセル)。 */
    public record Sprite(int u, int v, int tw, int th) {
        /** キャンバス換算の幅。 */
        public int w() {
            return tw / TEX_SCALE;
        }

        /** キャンバス換算の高さ。 */
        public int h() {
            return th / TEX_SCALE;
        }
    }

    /** UI 背景。キャンバス全面にそのまま貼る。 */
    public static final Sprite BG = new Sprite(0, 0, TEX_W, CANVAS_H * TEX_SCALE);

    private static final int STRIP_V = CANVAS_H * TEX_SCALE;              // 720

    // --- ノード枠(状態ごと。可変サイズなので伸縮して描く)---
    private static final int NODE_TW = 252;   // = キャンバス 126
    private static final int NODE_TH = 112;   // = キャンバス 56
    public static final Sprite NODE_LOCKED = new Sprite(0, STRIP_V, NODE_TW, NODE_TH);
    public static final Sprite NODE_AVAILABLE = new Sprite(252, STRIP_V, NODE_TW, NODE_TH);
    public static final Sprite NODE_UNLOCKED = new Sprite(504, STRIP_V, NODE_TW, NODE_TH);
    public static final Sprite NODE_PENDING = new Sprite(756, STRIP_V, NODE_TW, NODE_TH);
    public static final Sprite NODE_EXCLUDED = new Sprite(1008, STRIP_V, NODE_TW, NODE_TH);

    private static final int STRIP2_V = STRIP_V + NODE_TH;                // 832

    /** ツリー背景のタイル(キャンバス 64×64 で敷き詰める)。 */
    public static final Sprite TREE_TILE = new Sprite(0, STRIP2_V, 128, 128);
    /** スロットの窪み(キャンバス 18×18)。背景に描き込むなら透明にしておけばよい。 */
    public static final Sprite SLOT_BG = new Sprite(128, STRIP2_V, 36, 36);
    /** 作業台の「→」(キャンバス 22×15)。 */
    public static final Sprite ARROW_RIGHT = new Sprite(164, STRIP2_V, 44, 30);
    /** 解析の「↓」(キャンバス 15×22)。 */
    public static final Sprite ARROW_DOWN = new Sprite(208, STRIP2_V, 30, 44);

    // --- ボタン(キャンバス 100×18)---
    public static final Sprite BTN = new Sprite(240, STRIP2_V, 200, 36);
    public static final Sprite BTN_HOVER = new Sprite(240, STRIP2_V + 36, 200, 36);
    public static final Sprite BTN_DISABLED = new Sprite(240, STRIP2_V + 72, 200, 36);

    // --- Tier タブ(キャンバス 92×26)---
    public static final Sprite TAB = new Sprite(440, STRIP2_V, 184, 52);
    public static final Sprite TAB_ACTIVE = new Sprite(440, STRIP2_V + 52, 184, 52);

    /** 閉じるボタン(キャンバス 20×20)。 */
    public static final Sprite CLOSE = new Sprite(624, STRIP2_V, 40, 40);
    public static final Sprite CLOSE_HOVER = new Sprite(664, STRIP2_V, 40, 40);

    // ===================== ヘッダー =====================

    public static final int TITLE_X = 20;
    public static final int TITLE_Y = 14;
    /** 見出しの英字サブタイトル。 */
    public static final int SUBTITLE_Y = 26;

    public static final int POINTS_ICON_X = 452;
    public static final int POINTS_ICON_Y = 14;
    public static final int POINTS_TEXT_X = 474;
    public static final int POINTS_TEXT_Y = 18;

    public static final int CLOSE_X = 600;
    public static final int CLOSE_Y = 12;
    public static final int CLOSE_W = 20;
    public static final int CLOSE_H = 20;

    // ===================== Tier タブ列 =====================

    public static final int RAIL_TIERS = 6;
    public static final int RAIL_X = 18;
    public static final int RAIL_Y = 54;
    public static final int RAIL_W = 92;
    public static final int RAIL_H = 26;
    public static final int RAIL_STEP = 30;

    public static int railY(int tierIndex0) {
        return RAIL_Y + tierIndex0 * RAIL_STEP;
    }

    // ===================== 研究ツリー表示域 =====================

    public static final int TREE_X0 = 120;
    public static final int TREE_Y0 = 52;
    public static final int TREE_X1 = 500;
    public static final int TREE_Y1 = 238;

    // ===================== 右の詳細パネル =====================

    public static final int PANEL_X = 510;
    public static final int PANEL_Y = 48;
    public static final int PANEL_W = 116;
    public static final int PANEL_H = 192;

    public static final int PANEL_ICON_X = PANEL_X + 8;                   // 518
    public static final int PANEL_ICON_Y = PANEL_Y + 8;                   // 56
    public static final int PANEL_TITLE_X = PANEL_X + 30;                 // 540
    public static final int PANEL_TITLE_Y = PANEL_Y + 8;                  // 56
    public static final int PANEL_TIER_Y = PANEL_Y + 19;                  // 67

    public static final int PANEL_TEXT_X = PANEL_X + 8;                   // 518
    public static final int PANEL_TEXT_W = PANEL_W - 16;                  // 100
    public static final int PANEL_DESC_Y = PANEL_Y + 36;                  // 84
    /** 前提・開放レシピなどの一覧の開始 y。 */
    public static final int PANEL_LIST_Y = PANEL_Y + 76;                  // 124
    /** 必要な研究ポイントの行。 */
    public static final int PANEL_COST_Y = PANEL_Y + 142;                 // 190

    public static final int PANEL_BTN_X = PANEL_X + 8;                    // 518
    public static final int PANEL_BTN_Y = PANEL_Y + 164;                  // 212
    public static final int PANEL_BTN_W = 100;
    public static final int PANEL_BTN_H = 18;

    // ===================== 下部バー(スロット群)=====================

    public static final int SLOT = 18;
    public static final int GRID = 5;
    /** 解析枠の最大数(研究で増やせる上限)。 */
    public static final int ANALYZE_MAX = 5;

    public static final int BAR_X = 95;
    public static final int BAR_Y = 246;
    public static final int BAR_W = 450;
    public static final int BAR_H = 108;

    // 5×5 拡大作業台 → 矢印 → 成果
    public static final int GRID_X = BAR_X + 12;                          // 107
    public static final int GRID_Y = BAR_Y + 12;                          // 258
    public static final int ARROW_X = GRID_X + GRID * SLOT + 6;           // 203
    public static final int RESULT_X = ARROW_X + 26;                      // 229
    public static final int RESULT_Y = GRID_Y + 2 * SLOT;                 // 294

    // プレイヤーインベントリ
    public static final int INV_X = RESULT_X + SLOT + 20;                 // 267
    public static final int INV_Y = BAR_Y + 20;                           // 266
    public static final int HOTBAR_Y = INV_Y + 3 * SLOT + 6;              // 326

    // 解析エリア(インベントリの右。枠は横 1 列に並べ、その下に ↓ とボタン)
    public static final int ANALYZE_X = INV_X + 9 * SLOT + 20;            // 449
    public static final int ANALYZE_Y = BAR_Y + 16;                       // 262
    public static final int ANALYZE_BTN_W = 54;
    public static final int ANALYZE_BTN_H = 18;

    /** 解析枠の行の中央 x(↓ とボタンをここに合わせる)。 */
    public static int analyzeCenterX(int analyzeCount) {
        return ANALYZE_X + Math.max(1, analyzeCount) * SLOT / 2;
    }

    public static int analyzeArrowY(int analyzeCount) {
        return ANALYZE_Y + SLOT + 3;
    }

    public static int analyzeButtonY(int analyzeCount) {
        return analyzeArrowY(analyzeCount) + 15;
    }

    /** ラベルはどれも矩形の 11px 上に置く。 */
    public static int labelY(int y) {
        return y - 11;
    }

    private ResearchUi() {
    }
}
