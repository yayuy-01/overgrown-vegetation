package com.yayuy.overgrownvegetation.menu;

/**
 * 研究台 UI の<b>唯一のレイアウト定義</b>。
 *
 * <h2>2026-10-14: 仮でバニラの窓に戻した</h2>
 * ユーザー指定「研究台の UI をバニラのような形式に。全画面ではなく作業台などと同じように window のような感じに。
 * 世界観に合わせて作り直すので、仮でバニラのデザインに戻したい」。それまでの全画面版(640×360 の 1 枚絵
 * {@code textures/gui/research_ui.png} を画面いっぱいに引き伸ばす)のコードは {@code tools/ui-backup-2026-10-14/} に置いてある。
 *
 * <p>窓は<b>タブ 2 つ</b>(研究 = ツリーと詳しい表示 / 作業 = 5×5 拡大作業台・解析・インベントリ)。
 * タブの帯を含めて {@link #CANVAS_W}×{@link #CANVAS_H} = 320×240 に収める —— バニラの GUI の大きさの自動はどの画面でも
 * 320×240 以上を残すので、はみ出さない(それより狭いときだけ画面側が縮める)。
 *
 * <h2>座標</h2>
 * ここの座標はすべてキャンバス(タブの帯の左上が 0,0)。窓はその {@link #WIN_Y} 下から。
 * {@link net.minecraft.world.inventory.Slot#x}/{@code y} は 1.21.1 で final なのでメニュー生成時に確定させる。
 * {@link ResearchTableMenu} はここの定数をそのまま使い、画面側は {@code leftPos}/{@code topPos} を 0 にして
 * キャンバス座標 = スロット座標にする。
 */
public final class ResearchUi {

    // ===================== キャンバス =====================

    public static final int CANVAS_W = 320;
    public static final int CANVAS_H = 240;
    /** タブの帯の高さ。窓はこの下から。 */
    public static final int TAB_H = 18;
    public static final int WIN_X = 0;
    public static final int WIN_Y = TAB_H;
    public static final int WIN_W = CANVAS_W;
    public static final int WIN_H = CANVAS_H - TAB_H;                     // 222

    // ===================== タブ =====================

    public static final int TAB_COUNT = 2;
    public static final int TAB_TREE = 0;
    public static final int TAB_WORK = 1;
    public static final int TAB_X = 4;
    public static final int TAB_W = 64;
    public static final int TAB_STEP = 66;

    // ===================== 見出し(両方のタブ)=====================

    public static final int TITLE_X = 8;
    public static final int TITLE_Y = WIN_Y + 6;
    /** 研究ポイントの文字の右端。 */
    public static final int POINTS_RIGHT = WIN_X + WIN_W - 8;

    // ===================== 研究のタブ: Tier の切り替え =====================

    public static final int RAIL_TIERS = 6;
    public static final int RAIL_X = 8;
    public static final int RAIL_Y = WIN_Y + 17;
    public static final int RAIL_W = 49;
    public static final int RAIL_H = 16;
    public static final int RAIL_STEP = 51;

    public static int railX(int tierIndex0) {
        return RAIL_X + tierIndex0 * RAIL_STEP;
    }

    // ===================== 研究のタブ: ツリー(進捗画面のような石の面)=====================

    /** へこんだ枠の外側。 */
    public static final int TREE_FRAME_X = 7;
    public static final int TREE_FRAME_Y = WIN_Y + 37;
    public static final int TREE_FRAME_W = 200;
    public static final int TREE_FRAME_H = 178;
    /** 描ける内側(枠の 1 内側)。 */
    public static final int TREE_X0 = TREE_FRAME_X + 1;
    public static final int TREE_Y0 = TREE_FRAME_Y + 1;
    public static final int TREE_X1 = TREE_FRAME_X + TREE_FRAME_W - 1;
    public static final int TREE_Y1 = TREE_FRAME_Y + TREE_FRAME_H - 1;

    /** ノードの枠(進捗の枠 26×26)。ノードは名前を出さず、名前はツールチップと右の詳しい表示に出す。 */
    public static final int NODE = 26;
    /** research.json の格子 1 つぶん(ズーム 1 のとき)。 */
    public static final int CELL_X = 36;
    public static final int CELL_Y = 32;

    // ===================== 研究のタブ: 右の詳しい表示 =====================

    public static final int PANEL_X = 212;
    public static final int PANEL_Y = TREE_FRAME_Y;
    public static final int PANEL_W = 101;

    public static final int PANEL_ICON_X = PANEL_X;
    public static final int PANEL_ICON_Y = PANEL_Y + 1;
    public static final int PANEL_TITLE_X = PANEL_X + 20;
    public static final int PANEL_TITLE_Y = PANEL_Y + 1;
    public static final int PANEL_TITLE_W = PANEL_W - 20;

    public static final int PANEL_TEXT_X = PANEL_X;
    public static final int PANEL_TEXT_W = PANEL_W;
    public static final int PANEL_DESC_Y = PANEL_Y + 25;
    /** 前提・開放レシピなどの一覧の開始 y。 */
    public static final int PANEL_LIST_Y = PANEL_Y + 74;
    /** 必要な研究ポイントの行。 */
    public static final int PANEL_COST_Y = PANEL_Y + 148;

    public static final int PANEL_BTN_X = PANEL_X;
    public static final int PANEL_BTN_Y = PANEL_Y + 158;
    public static final int PANEL_BTN_W = PANEL_W;
    public static final int PANEL_BTN_H = 20;

    // ===================== 作業のタブ(スロット群)=====================

    public static final int SLOT = 18;
    public static final int GRID = 5;
    /** 解析枠の最大数(研究で増やせる上限)。 */
    public static final int ANALYZE_MAX = 5;

    /** 5×5 拡大作業台 → 矢印 → 成果。座標はアイテムの左上(枠はその 1 外側)。 */
    public static final int GRID_X = 35;
    public static final int GRID_Y = WIN_Y + 30;
    public static final int ARROW_X = GRID_X + GRID * SLOT + 4;           // 129
    public static final int ARROW_Y = GRID_Y + 2 * SLOT + 1;
    /** 成果は作業台と同じ 26×26 の枠(アイテムは枠の +5)。 */
    public static final int RESULT_X = ARROW_X + 22 + 9;                  // 160
    public static final int RESULT_Y = GRID_Y + 2 * SLOT;

    /** 解析: 枠は横 1 列、その下にボタン。 */
    public static final int ANALYZE_X = 198;
    public static final int ANALYZE_Y = GRID_Y;
    public static final int ANALYZE_BTN_X = ANALYZE_X - 1;
    public static final int ANALYZE_BTN_Y = ANALYZE_Y + SLOT + 4;
    public static final int ANALYZE_BTN_W = ANALYZE_MAX * SLOT;
    public static final int ANALYZE_BTN_H = 20;

    /** プレイヤーのインベントリ(窓の横の真ん中)。 */
    public static final int INV_X = (CANVAS_W - 9 * SLOT) / 2 + 1;        // 80
    public static final int INV_Y = WIN_Y + WIN_H - 82;
    public static final int HOTBAR_Y = INV_Y + 3 * SLOT + 4;

    /** ラベルは矩形の 11px 上に置く。 */
    public static int labelY(int y) {
        return y - 11;
    }

    private ResearchUi() {
    }
}
