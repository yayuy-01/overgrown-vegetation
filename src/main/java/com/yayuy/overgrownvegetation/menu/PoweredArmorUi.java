package com.yayuy.overgrownvegetation.menu;

/**
 * パワードアーマー作成テーブルの UI の<b>唯一のレイアウト定義</b>(順 1 の着手順 5、2026-10-03)。
 *
 * <h2>2026-10-14: 仮でバニラの窓に戻した</h2>
 * ユーザー指定「パワードアーマー制作台の UI をバニラのような形式に。全画面ではなく作業台などと同じように window のような感じに。
 * 世界観に合わせて作り直すので、仮でバニラのデザインに戻したい」。それまでの全画面版(640×360 の背景
 * {@code textures/gui/powered_armor_ui.png} を画面いっぱいに引き伸ばし、枠・ボタンをドット調で塗る)のコードは
 * {@code tools/ui-backup-2026-10-14/} に置いてある。
 *
 * <p>研究台({@link ResearchUi})と同じく、窓の上にタブの帯を出し、タブの帯を含めて {@link #CANVAS_W}×{@link #CANVAS_H} = 320×240 に収める
 * (バニラの GUI の大きさの自動はどの画面でも 320×240 以上を残す)。座標はすべてキャンバス(タブの帯の左上が 0,0)。
 * 窓の中の作業面は x 7〜313・y {@code WIN_Y}+7〜{@code WIN_Y}+126、その下はどのタブでもプレイヤーのインベントリ。
 */
public final class PoweredArmorUi {

    // ===================== キャンバス =====================

    public static final int CANVAS_W = 320;
    public static final int CANVAS_H = 240;
    /** タブの帯の高さ。窓はこの下から。 */
    public static final int TAB_STRIP_H = 18;
    public static final int WIN_X = 0;
    public static final int WIN_Y = TAB_STRIP_H;
    public static final int WIN_W = CANVAS_W;
    public static final int WIN_H = CANVAS_H - TAB_STRIP_H;               // 222

    // ===================== タブ =====================

    public static final int TAB_COUNT = 4;
    public static final int TAB_X = 4;
    public static final int TAB_W = 70;
    public static final int TAB_STEP = 72;

    // ===================== プレイヤーのインベントリ(全タブ共通)=====================

    public static final int SLOT = 18;
    /** スロット(16×16 のアイテム)の左上。枠はその 1 外側に 18×18。窓の横の真ん中。 */
    public static final int INV_X = (CANVAS_W - 9 * SLOT) / 2 + 1;        // 80
    public static final int INV_Y = WIN_Y + WIN_H - 82;                   // 158
    public static final int HOTBAR_Y = INV_Y + 3 * SLOT + 4;              // 216

    /** ラベルは矩形の 11px 上(研究台と同じ)。 */
    public static int labelY(int y) {
        return y - 11;
    }

    /** 作業面(インベントリの見出しより上)。 */
    public static final int AREA_X0 = 7;
    public static final int AREA_Y0 = WIN_Y + 7;
    public static final int AREA_X1 = 313;
    public static final int AREA_Y1 = WIN_Y + 126;

    /** 枠の下の説明などの行の高さ。 */
    public static final int INFO_LINE = 10;

    // ===================== 作成タブ: 左の一覧 =====================

    public static final int LIST_X = 7;
    public static final int LIST_Y = AREA_Y0;
    public static final int LIST_W = 110;
    public static final int LIST_ROW_H = 20;
    public static final int LIST_STEP = 22;
    /** 一度に見せる行数(超えたらホイールで送る)。 */
    public static final int LIST_ROWS = 4;

    // ===================== 作成タブ: 右の詳しい表示 =====================

    public static final int DETAIL_X = 124;
    /** 結果のアイコン(スロットの枠の中)。 */
    public static final int DETAIL_ICON_Y = AREA_Y0 + 1;
    public static final int DETAIL_NAME_X = DETAIL_X + 22;
    public static final int DETAIL_NAME_Y = AREA_Y0 + 1;

    public static final int MATS_Y = AREA_Y0 + 34;
    public static final int MATS_STEP = 20;
    /** 素材の行の最大数(超えた分は描かない。1.0.0 のレシピは 4 種まで)。 */
    public static final int MATS_ROWS = 4;
    /** 素材の名前の左端と、持っている数 / 要る数の右端。 */
    public static final int MATS_NAME_X = DETAIL_X + 22;
    public static final int MATS_COUNT_RIGHT = AREA_X1;

    public static final int BTN_X = 253;
    public static final int BTN_Y = AREA_Y0;
    public static final int BTN_W = 60;
    public static final int BTN_H = 18;

    // ===================== 作業枠(設計図・スキルツリー・装着のタブで共用。着手順 6)=====================

    /** 作業枠の枠(26×26)。アイテムはその中の {@link #WORK_X}/{@link #WORK_Y}。 */
    public static final int WORK_FRAME_X = 7;
    public static final int WORK_FRAME_Y = AREA_Y0 + 10;
    public static final int BIG_FRAME = 26;
    public static final int WORK_X = WORK_FRAME_X + 5;
    public static final int WORK_Y = WORK_FRAME_Y + 5;
    /** 作業枠の下の説明(名前 2 行・段 3 行)。幅は {@link #WORK_INFO_W}。 */
    public static final int WORK_INFO_X = WORK_FRAME_X;
    public static final int WORK_INFO_Y = WORK_FRAME_Y + 30;
    public static final int WORK_INFO_W = 76;
    /** 名前の 1 行目から段の行までの下げ幅。 */
    public static final int INFO_LEVELS_DY = 22;

    // ===================== スキルツリーのタブ(ユーザー指定「P: 1 部位ずつ」)=====================

    public static final int TREE_X = 90;
    public static final int TREE_COL_STEP = 74;
    public static final int NODE_W = 72;
    public static final int NODE_H = 18;
    /** 本の名前の y。 */
    public static final int TREE_HEAD_Y = AREA_Y0;
    public static final int NODE_Y = AREA_Y0 + 11;
    public static final int NODE_STEP = 22;

    /** 選んだ段の支払いの見出しと行。 */
    public static final int COST_LABEL_Y = AREA_Y0 + 79;
    public static final int COST_Y = AREA_Y0 + 89;
    /** 押せない理由の 1 行(支払いの行の下)。 */
    public static final int STATUS_Y = COST_Y + 21;
    /** 研究ポイントの文字の右端の次から素材の枠を並べる。 */
    public static final int COST_ITEMS_X = 158;
    public static final int COST_ITEM_STEP = 46;

    public static final int UPGRADE_BTN_X = 253;
    public static final int UPGRADE_BTN_Y = COST_Y;
    public static final int UPGRADE_BTN_W = 60;
    public static final int UPGRADE_BTN_H = 18;

    // ===================== 設計図のタブ =====================

    /** 作業枠 → 設計図の矢印。 */
    public static final int PRINT_ARROW_X = 94;
    public static final int PRINT_ARROW_Y = WORK_FRAME_Y + 6;
    public static final int PRINT_FRAME_X = 124;
    public static final int PRINT_FRAME_Y = WORK_FRAME_Y;
    public static final int PRINT_X = PRINT_FRAME_X + 5;
    public static final int PRINT_Y = PRINT_FRAME_Y + 5;
    public static final int PRINT_INFO_X = PRINT_FRAME_X;
    public static final int PRINT_INFO_Y = WORK_INFO_Y;

    public static final int COPY_BTN_X = PRINT_FRAME_X;
    public static final int COPY_BTN_Y = AREA_Y0 + 83;
    public static final int COPY_BTN_W = 84;
    public static final int COPY_BTN_H = 18;

    /** 「作り直す」の欄(右)。素材の行と、その下のボタン。 */
    public static final int REBUILD_X = 214;
    public static final int REBUILD_MATS_Y = WORK_FRAME_Y;
    public static final int REBUILD_STEP = 19;
    public static final int REBUILD_ROWS = 4;
    public static final int REBUILD_BTN_X = REBUILD_X;
    public static final int REBUILD_BTN_Y = AREA_Y0 + 88;
    public static final int REBUILD_BTN_W = AREA_X1 - REBUILD_X;
    public static final int REBUILD_BTN_H = 18;

    // ===================== 装着のタブ(カートリッジ。着手順 8)=====================

    /** 左の欄: 付いている効果の見出しと行(行は {@code FIT_EFFECT_LINES} 行まで)。 */
    public static final int FIT_EFFECTS_LABEL_Y = WORK_INFO_Y + 22;
    public static final int FIT_EFFECTS_Y = WORK_INFO_Y + 32;

    /** 真ん中の欄(付ける物): x 86〜216。 */
    public static final int CART_FRAME_X = 86;
    public static final int CART_FRAME_Y = WORK_FRAME_Y;
    public static final int CART_X = CART_FRAME_X + 5;
    public static final int CART_Y = CART_FRAME_Y + 5;
    public static final int MIDDLE_W = 130;
    /** 付ける物の枠の右: 名前と付け先(カートリッジのときは名前と「パワードアーマー用」)。 */
    public static final int CART_TOTAL_X = CART_FRAME_X + 30;
    public static final int ATTACH_NAME_Y = CART_FRAME_Y + 3;
    public static final int ATTACH_PART_Y = CART_FRAME_Y + 14;
    /** 枠の下の説明の 1 行目(カートリッジの因子の合計 / 装飾品・拡張パーツの効き目 / 空のときの案内)。 */
    public static final int ATTACH_DESC_Y = WORK_INFO_Y;
    /** 種類ごとの行(5 行)。 */
    public static final int FACTOR_ROWS_X = CART_FRAME_X;
    public static final int FACTOR_ROWS_Y = ATTACH_DESC_Y + 11;
    public static final int FACTOR_ROW_STEP = 10;
    public static final int FACTOR_COUNT_RIGHT = 190;
    /** 因子の枠(18×18)と「入れる」。 */
    public static final int FACTOR_FRAME_X = CART_FRAME_X;
    public static final int FACTOR_FRAME_Y = AREA_Y1 - 18;
    public static final int FACTOR_X = FACTOR_FRAME_X + 1;
    public static final int FACTOR_Y = FACTOR_FRAME_Y + 1;
    public static final int INSERT_BTN_X = FACTOR_FRAME_X + 22;
    public static final int INSERT_BTN_Y = FACTOR_FRAME_Y;
    public static final int INSERT_BTN_W = 50;
    public static final int INSERT_BTN_H = 18;
    /** 右の欄: 「付ける」と、装備の差し口 3 つ(カートリッジ・装飾品・拡張パーツ)と「外す」(ユーザー指定「b」)。 */
    public static final int FIT_RIGHT_X = 221;
    public static final int ATTACH_BTN_Y = WORK_FRAME_Y;
    public static final int FIT_BTN_W = AREA_X1 - FIT_RIGHT_X;
    public static final int FIT_BTN_H = 18;
    public static final int SOCKET_Y = AREA_Y0 + 42;
    public static final int SOCKET_STEP = 26;
    public static final int SOCKET_W = AREA_X1 - FIT_RIGHT_X;
    public static final int SOCKET_H = 24;
    public static final int SOCKET_BTN_X = FIT_RIGHT_X + 60;
    public static final int SOCKET_BTN_DY = 3;
    public static final int SOCKET_BTN_W = 30;
    public static final int SOCKET_BTN_H = 18;

    private PoweredArmorUi() {
    }
}
