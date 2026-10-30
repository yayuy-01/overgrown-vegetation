package com.yayuy.overgrownvegetation.skyboss;

/**
 * 空の巨大生物(G10・アオミノウミウシ)の数値。<b>ユーザー指定はコメントに「指定」と書いたものだけ</b>で、ほかはすべて仮決め。
 * 行動の決めごとは Vault の {@code Overgrown/空の巨大生物の行動パターン.md}、モデルとアニメは {@code tools/g5-models/mobs/SkyGlaucus*.java}。
 */
public final class SkyBossTuning {

    // ------------------------------------------------------------------ 体
    /**
     * 描画の倍率(モデルの 1 単位 = 3 ブロック。全長 約 2100 ブロック)。
     * 指定(10-18)「現在の 3 倍の大きさ」(前は倍率 16 = 全長 約 690)。モデルの道具の {@code m.renderScale} は 16 のまま(形の確認用)。
     */
    public static final float MODEL_SCALE = 48.0F;
    /** モデルの 1 単位が何ブロックか。 */
    public static final double BLOCKS_PER_UNIT = MODEL_SCALE / 16.0;
    /** 頭の通った道を覚えておく長さ(ブロック、空気の座標)。体の全長 + 余裕。 */
    public static final double TRAIL_KEEP = 2300.0;

    // ------------------------------------------------------------------ 飛び方
    /**
     * 空気に対する速さ(ブロック / tick)。地面に対する速さ = これ + 世界の風(秒速 0.3〜1.9)。
     * 指定(10-18 午前)「移動速度も今よりも速くて良い」→ 秒速 6、指定(10-18 午後)「移動速度をもっと速く」→ 秒速 12(値は仮。前の 2 倍)。
     */
    public static final double AIRSPEED = 0.6;
    /** 操作(曲がり方・上り下り)を決め直す間隔(tick)。この間は同じ操作で進むので、サーバーとクライアントが同じ道を引ける。 */
    public static final int SEGMENT_TICKS = 160;

    // ------------------------------------------------------------------ 旋回({@link SkyBossBrain})
    /**
     * 1 回の旋回の半径の幅(ブロック)。半径を決めてから曲がる速さ = 速さ ÷ 半径 を出す(速さを変えても体のしなりが変わらない)。
     * 1000 = 体の長さの半分ほど(体が大きく弧を描く)。大きく向きを変えるときほど大きい半径を選ぶ。
     */
    public static final double TURN_RADIUS_MIN = 1000.0;
    public static final double TURN_RADIUS_MAX = 2400.0;
    /** この角度(度)以上向きを変える旋回で、向きの差の分の傾きがいっぱいになる。 */
    public static final float TURN_FULL = 120.0F;
    /** 半径の分の傾きの重み: いちばん小さい半径で {@link #MAX_BANK} のこれだけ、いちばん大きい半径で 1 倍。 */
    public static final float BANK_RADIUS_MIN_WEIGHT = 0.6F;
    /** 旋回の後にまっすぐ飛ぶ区間の数(幅)。 */
    public static final int STRAIGHT_SEGS_MIN = 2;
    public static final int STRAIGHT_SEGS_MAX = 7;
    /** 風下の向きからの振れの上限(度)。旋回はこの中で、風下をはさんで左右へ振れる(風上へは回らない)。 */
    public static final float MEANDER = 80.0F;
    /** 機首の上げ下げの上限(度)と、その変わる速さの上限(度 / tick。上下の曲がりは扇の重なりを崩しやすいので小さく)。 */
    public static final float MAX_PITCH = 6.0F;
    public static final float MAX_PITCH_RATE = 0.008F;
    /**
     * ふだん飛ぶ高さ(体の軸の y)と、そのゆるい上下の幅。腹が上なので背の避雷針(軸から約 375 下)が雲の床(y 300〜540 ほど)より上に来る高さ。
     * 腹の触手は軸から約 390 上へなびく。
     */
    public static final double CRUISE_Y = 950.0;
    public static final double CRUISE_SWING = 60.0;

    // ------------------------------------------------------------------ 見せ方(クライアント)
    /**
     * 曲がるときに体を傾ける角度の上限(度)。指定(10-18)「最大 70 度くらい傾けてもよい」。
     * 指定(10-18 午後)「旋回半径が大きいかつ、向きを変える先が向きを変える前の向いていた方向との差が大きいほど傾く」→ 1 回の旋回の傾き =
     * これ × 向きの差の分(sin で {@link #TURN_FULL} に向けて 1 へ)× 半径の分({@link #BANK_RADIUS_MIN_WEIGHT}〜1)。式と重みは仮。
     */
    public static final float MAX_BANK = 70.0F;
    /** 傾きが目標へ追いつく速さ(tick。2 段のなめらかな追い方の 1 段ぶん。立ち上がり 約 12 秒)。 */
    public static final float BANK_EASE_TICKS = 70.0F;
    /** 傾きがこれ(度)より深くなると旋回のアニメ(外側の扇が強く漕ぐ)、{@link #TURN_ANIM_BANK_OFF} より浅くなると渡りの漕ぎへ戻る。 */
    public static final float TURN_ANIM_BANK = 20.0F;
    public static final float TURN_ANIM_BANK_OFF = 15.0F;

    // ------------------------------------------------------------------ 体力と戦い(10-18 夕方から。{@link SkyBossCombat})
    /** 体力の上限。仮(ティタニカ 18000 より多め。地上の対空兵器・戦闘機ができたら合わせる)。 */
    public static final float MAX_HEALTH = 24000.0F;
    /** 指定(10-18)「苔に当たったら減らして通す」→ 苔(体・頭・扇・腕・触手)に当たったダメージの倍率。値は仮。 */
    public static final float MOSS_DAMAGE = 0.3F;
    /** 弱点(避雷針・蕾)に当たったダメージの倍率(「弱点はふつう以上に通る」の仮の値)。 */
    public static final float WEAK_DAMAGE = 1.5F;
    /** 戦いが続いているとみなす時間(tick): 最後に傷を受けた・体のこれだけ近く({@link #COMBAT_NEAR})を飛ばれてから。仮。 */
    public static final int COMBAT_TIMEOUT = 1200;
    public static final double COMBAT_NEAR = 200.0;
    /** 戦っていない昼・晴れのときに戻る体力(1 分に上限のこれだけ)。指定は「日の光でゆっくり戻る」、量は仮。 */
    public static final float REGEN_PER_MINUTE = 0.02F;
    /** ボスバーを見せる距離(体のいちばん近い所から、ブロック)。指定(10-18)「見える距離なら常に」、距離は仮。 */
    public static final double BOSS_BAR_RANGE = 3000.0;
    /** 指定(10-18)「体にぶつかるとダメージを受けて弾かれる」→ ダメージ(仮)・弾かれる速さ(ブロック / tick、仮)・続けて受けない間(tick)。 */
    public static final float CONTACT_DAMAGE = 6.0F;
    public static final double CONTACT_PUSH = 1.2;
    public static final int CONTACT_COOLDOWN = 20;
    /** 渡りの漕ぎの 1 回り(tick): 3 回漕いで(48.6 秒)、残りは滑空。 */
    public static final int STROKE_CYCLE_TICKS = 1800;

    // ------------------------------------------------------------------ 弱点を壊す(10-18 夜から。{@link SkyBossParts})
    /*
     * 指定(10-17・10-18 夜): 避雷針・蕾は壊せる。避雷針は爆発し、その反動で避雷針の付いた胴の真ん中が押し込まれる。壊した避雷針・蕾は時間で生え直す。
     * 数値はすべて仮。
     */
    /** 避雷針の体力(当たったダメージに {@link #WEAK_DAMAGE} を掛けた分が減る。強い弓の矢 約 25 本)。仮。 */
    public static final float ROD_HEALTH = 600.0F;
    /** 蕾 1 つの体力(強い弓の矢 約 6 本)。仮。 */
    public static final float BUD_HEALTH = 120.0F;
    /** 戦っていない間がこれだけ続いたら、壊れた避雷針が生え直し始める(tick)。仮。 */
    public static final int ROD_REGROW_DELAY = 2400;
    /** 戦っていない間がこれだけ続いたら、壊れた蕾が生え直し始める(tick。蕾ごとに {@link #BUD_REGROW_STAGGER} までずらす)。仮。 */
    public static final int BUD_REGROW_DELAY = 1200;
    public static final int BUD_REGROW_STAGGER = 600;
    /** 蕾が 0 から元の大きさへ育つ時間(tick)。仮。 */
    public static final int BUD_REGROW_TICKS = 200;
    /** モデルの道具のアニメの時間の倍率({@code SkyGlaucusAnims.SLOW})。アニメの秒 = 元の秒 × これ。 */
    public static final double ANIM_SLOW = 1.8;
    /** 避雷針の層のアニメ(rod_short・rod_regrow)へ移る補間(tick)。rod_short の最初の形は放電中の開いた鞘なので、0 だと一瞬で開いて見える。 */
    public static final int ROD_TRANSITION = 6;
    /** 体の層・腹の触手の層の切り替わりの補間(tick。10-19 の技から。GeckoLib のコントローラと {@link SkyBossActClock} で同じ値)。仮。 */
    public static final int BODY_TRANSITION = 20;
    public static final int TENT_TRANSITION = 12;
    /** rod_regrow の長さ(tick。元 20 秒 × {@link #ANIM_SLOW})。GameTest がアニメの長さと照らし合わせる。 */
    public static final int ROD_REGROW_TICKS = 720;
    /** rod_short の中で避雷針が爆発する時刻(元の秒。{@code SkyGlaucusAttacks.BOOM})。 */
    public static final double ROD_BOOM = 0.1;

    // ------------------------------------------------------------------ 雷雲に寄る・背を上にする(10-19 から。{@link SkyBossBrain}・{@link SkyBossFlight})
    /*
     * 指定(10-17): 進路は風任せ + 近くの雷雲に寄る / 雷雲の中だけ背を上にして雷を食べる / 雷雲の中では扇を小刻みに左右交互に打って留まる /
     * 遠くの雷雲に気づくと頭をそちらへ向け、少し遅れて体が向きを変える / 雷雲を見つけると漕ぐのが速くなり、雷が避雷針に落ちるたびに身震いする /
     * 腹 ⇄ 背を転がるとき口の触手は重力で垂れる。
     * 指定(10-19): 気づく距離 5 km / 留まるのは数分たつか雲が崩れるまで / 雷を避雷針に落として身震いさせる(今回入れる)。
     */
    /** 指定(10-19)「5 km」: 巨大な積乱雲に気づく距離(空気の座標で、頭から雲の真ん中まで)。 */
    public static final double STORM_DETECT = 5000.0;
    /** 寄る雲の育ちの下限(育ちきる前・崩れかけの雲には寄らない)。仮。 */
    public static final float STORM_MIN_MATURITY = 0.5F;
    /** 雲が見つかってから向きを変え始めるまで(区間。その間は頭だけ先に向ける)。仮。 */
    public static final int LOOK_LEAD_SEGS = 1;
    /** 頭を先に向けるいちばん大きな角度(度)と、追う速さ(tick、2 段のなめらかな追い方の 1 段)。仮。 */
    public static final float LOOK_MAX = 25.0F;
    public static final float LOOK_EASE_TICKS = 60.0F;
    /** 雷雲へ向かうときの速さ(空気に対して。決定「漕ぐのが速くなる」= ふだんの 1.5 倍)。仮。 */
    public static final double EAGER_SPEED = 0.9;
    /** 雷雲へ寄るときのいちばんきつい曲がりの半径(ブロック。渡りの旋回の下限より小さい)。仮。 */
    public static final double SEEK_RADIUS_MIN = 700.0;
    /** 速さが目標へ近づく加速(ブロック / tick²。0 から 0.6 まで 20 秒)。仮。 */
    public static final double SPEED_EASE = 0.0015;
    /** 雷雲の中で留まる速さ(空気に対して。0 = 雲と一緒に風に流れるだけ)。 */
    public static final double HOVER_SPEED = 0.0;
    /** 指定(10-19)「数分たつか雲が崩れたら出ていく」: 留まる長さ(tick、4 分。仮)。崩れ始めたら(育ちが前の区間より下がったら)それより早く出る。 */
    public static final int FEED_TICKS = 4800;
    /** 雷雲を出てから次の雷雲に寄るまでの間(tick、5 分)と、同じ雲には戻らない(覚えておく雲の数)。仮。 */
    public static final int FEED_COOLDOWN = 6000;
    public static final int FED_MEMORY = 8;
    /** 腹 ⇄ 背を転がる長さ(頭で、tick)と、尾が頭より遅れる長さ(体じゅうを頭から尾へ伝わる)。仮。 */
    public static final int ROLL_TICKS = 400;
    public static final int ROLL_LAG_TICKS = 120;
    /** 雷が避雷針に落ちる間(tick。この間でばらつく。身震い 1 回は 約 6.5 秒)。仮。 */
    public static final int STRIKE_INTERVAL_MIN = 200;
    public static final int STRIKE_INTERVAL_MAX = 400;
    /** 雷の始まり(避雷針の先から上へ、ブロック)。仮。 */
    public static final double STRIKE_HEIGHT_MIN = 220.0;
    public static final double STRIKE_HEIGHT_MAX = 340.0;
    /** 雷が落ちたときの体の震え(長さ tick・いちばん大きな角度 度)。仮。 */
    public static final int SHUDDER_TICKS = 50;
    public static final float SHUDDER_DEG = 0.8F;
    /** 転がりの真ん中で口の触手が体に遅れて垂れる角度(度)。仮。 */
    public static final float ORAL_SWING_DEG = 45.0F;

    // ------------------------------------------------------------------ 背の垂れ草(10-19 ユーザー「垂らしましょう」。数値は仮)
    /**
     * 垂れ草の板が体の外向きの面(輪の楕円の近似)からいちばん倒れる角度(度)。外皮は輪の頂点を結んだ八角形で楕円の内側にあるので、
     * 楕円の接する面より少し越えても埋まらない(輪の半幅 25・高さ 15 の胴で 95° まで外皮の外。10-19 の計算)。
     */
    public static final double HANG_MAX_DEG = 92.0;
    /** 真下が体の中を向くとき、外(体の脇)へ倒れる方を選ぶ余裕(度)。背骨の近くの板が左右へばたつかないように。 */
    public static final double HANG_OUTBOARD_DEG = 25.0;
    /** 重力の向きへ追いつく速さ: 振り子の周期(秒)と減衰の割合(1 で行き過ぎない)。 */
    public static final double HANG_PERIOD_S = 3.0;
    public static final double HANG_DAMPING = 0.8;
    /** 蝶番を外皮のこれだけ外へ(モデルの単位。寝た板が外皮とちらつかないように)。 */
    public static final double HANG_LIFT = 0.3;

    // ------------------------------------------------------------------ 放電(大技 2。10-19 ユーザー「放電頼めますか」)
    // 指定(10-17): 雷雲で避雷針に雷を受けて溜め、雷雲を出ても持ち歩き、好きなときに背を上にして 30 秒・半径 200 のプラズマボールのような雷。
    // 放電中は蕾が閉じ、電紋と避雷針が激しく光る。避雷針を壊すとショート。10-19 指定: ショートは体が沈んで一定時間技を出さないだけ
    /** 放電が続く長さ(tick)。指定 30 秒。 */
    public static final int DISCHARGE_TICKS = 600;
    /**
     * 雷が届く範囲(体の表面から、ブロック)。指定 10-19「半径 2000」(前の指定は 10-17 の「半径 200」。そのときは全長 600〜1000 だったので、
     * 体の真ん中からでなく表面から測る = 仮の読み方。ふだんの高さ y 950 から地上まで届く)。
     */
    public static final double DISCHARGE_RADIUS = 2000.0;
    /** 溜まりきるまでに避雷針へ落ちる雷の数。仮(雷は 10〜20 秒おきなので 約 2 分)。 */
    public static final int CHARGE_STRIKES = 8;
    /** 放電を始める体力の段階(行動パターンの段階の表「段階 2 から、近くに雷雲があれば大技 2」)。 */
    public static final int DISCHARGE_STAGE = 2;
    /** 背が上のまま(雷雲の中で)始めるときの予告(tick。電紋が強く光り、避雷針が鞘を開く)。腹が上からは転がる間(26 秒)が予告。仮。 */
    public static final int DISCHARGE_TELEGRAPH = 100;
    /** 放電を終えてから次に始めるまで(tick)。仮。溜め直しが要るので、ふつうはこれより長く空く。 */
    public static final int DISCHARGE_COOLDOWN = 1200;
    /** 渡りの途中で放電する間の速さ(空気に対して、ブロック / tick)。扇を広げてひきつるので漕がない。仮。 */
    public static final float DISCHARGE_SPEED = 0.3F;
    /** ショートの後に技を出さない間(tick)。指定「一定時間」→ 1 分は仮。 */
    public static final int STUN_TICKS = 1200;
    /** ショートで止まった放電の後、腹を上へ戻り始めるまで(tick。体が沈んで跳ね返るのを待つ)。仮。 */
    public static final int SHORT_ROLL_DELAY = 160;
    /** 範囲の中の相手へ雷が飛ぶ間(tick。体のすぐそば 〜 範囲の端)。仮。 */
    public static final int ARC_INTERVAL_NEAR = 10;
    public static final int ARC_INTERVAL_FAR = 40;
    /** 雷 1 本のダメージ(バニラの雷のダメージの種類。防具で減る)。仮。 */
    public static final float ARC_DAMAGE = 6.0F;
    /** 避雷針の層のアニメの長さ(秒。道具の rod_charge 3.0・rod_discharge 1.2 × 時間の倍率)。 */
    public static final double ROD_CHARGE_SECONDS = 3.0 * ANIM_SLOW;
    public static final double ROD_DISCHARGE_SECONDS = 1.2 * ANIM_SLOW;

    // ------------------------------------------------------------------ 技(10-19 ユーザー「一旦全ての攻撃を実装」。動きは 10-17 の決定、数値はすべて仮)
    /** 技で狙う相手の距離(体の表面から)。サバイバル・アドベンチャーのプレイヤーだけ(クリエイティブ・観戦は狙わない)。 */
    public static final double ATTACK_RANGE = 700.0;
    /** 戦いに入ってから最初の技まで(tick。いきなり撃たない)。 */
    public static final int FIRST_ATTACK_DELAY = 100;
    /** 大きな技(下降気流・雲を吐く・種の爆弾・ビーム)どうしの間(tick)。 */
    public static final int BIG_MOVE_GAP = 400;

    // 対空砲火の蕾(段階 1 から。決定「開く 1 秒前に芯が光る → 花弁が反り返って開く → 撃つたびに芯が脈打つ → 撃ち終わると閉じる」)
    /**
     * 閉じてから次の一斉射まで(tick)と、1 回の一斉射で撃つ蕾の数(段階 1・2 = 腹の側の蕾から・段階 3 = 全部の蕾から)。
     * 10-19 夜ユーザー「弾幕、弾速、ともに足りません。がっつり player を殺しに行く感じで、玉の発射速度・クールダウンの短縮を」→ 間 80 → 20・蕾 6 → 8(段階 3 は 12 → 20)。
     */
    public static final int AA_INTERVAL = 20;
    public static final int AA_BUDS = 8;
    public static final int AA_BUDS_STAGE3 = 20;
    /** 芯が光ってから開き始めるまで(tick。指定「開く 1 秒前に芯が光る」)と、撃つ間(tick。この間に蕾ごと 2 発)。 */
    public static final int AA_GLOW_TICKS = 20;
    /** 蕾が開く・閉じるアニメの長さ(tick。道具の buds_some_open 1.6 秒・buds_close 1.6 秒 × 時間の倍率)。 */
    public static final int BUDS_OPEN_TICKS = 137;
    public static final int BUDS_CLOSE_TICKS = 112;
    /**
     * 蕾ごとの開き始めのずれの幅(元の秒。道具の SkyGlaucusAttacks.OPEN_SPREAD と同じ値)。10-19 夜ユーザー「つぼみが同時に開いて撃ち始めるのではなく、各々がバラバラなタイミングで」
     * → 開くアニメ(1.6 → 3.8 秒 × 時間の倍率)の中で蕾ごとに 約 4 秒ずらし、その蕾が開ききってから撃ち始める({@link SkyBossAttacks#budOpenTicks})。
     */
    public static final double BUD_OPEN_SPREAD = 2.2;
    public static final int AA_FIRE_TICKS = 160;
    /** 1 つの蕾が撃つ間(tick。± 3 のばらつき)。10-19 夜: 撃つ間 96 tick に 2 発 → 160 tick に 16 発。 */
    public static final int AA_SHOT_GAP = 10;
    /** 撃ち終わりにまだ相手が技の距離の中にいたら、閉じずに撃ち続ける回数(1 回 = {@link #AA_FIRE_TICKS})。 */
    public static final int AA_EXTEND_MAX = 3;
    /** 胞子の塊: 速さ(ブロック / tick)・寿命(tick。切れたら空中で炸裂)・直撃と炸裂のダメージ・炸裂の半径。 */
    public static final double AA_SPEED = 5.0;
    public static final int AA_LIFE = 420;
    /** 対空砲火で狙う相手の距離(体の表面から)。10-19 夜ユーザー「対空砲火を始める範囲をボスから 2000 ブロック以内(放電と同じ範囲)」。ほかの技は {@link #ATTACK_RANGE}。 */
    public static final double AA_RANGE = DISCHARGE_RADIUS;
    public static final float AA_DAMAGE = 5.0F;
    public static final double AA_BLAST = 5.0;
    /**
     * ばらけ方(10-19 夜ユーザー「WW2 の戦艦による分厚い対空砲火。player をしっかり狙った弾が 6 割、player の周囲にばらける弾が 3 割、player から離れた場所に飛ぶ弾が 1 割」):
     * しっかり狙う割合・まわりにばらける割合(残りは離れた所へ)と、まわりの弾が狙う所の相手からの距離・離れた所の弾の距離(ブロック。仮)。
     */
    public static final double AA_AIMED_SHARE = 0.6;
    public static final double AA_SCATTER_SHARE = 0.3;
    public static final double AA_SCATTER_MIN = 10.0;
    public static final double AA_SCATTER_MAX = 60.0;
    public static final double AA_WILD_MIN = 120.0;
    public static final double AA_WILD_MAX = 400.0;
    /**
     * 時限信管(10-19 夜ユーザー「ボスから見て player のいる距離から誤差 ±100 ブロック分の範囲で爆発する」): 撃った所から弾が着くときの相手の位置までの距離 ± これ
     * (しっかり狙った弾は 0〜+これ。手前では炸裂させない。仮決め)。
     */
    public static final double AA_FUSE_SPREAD = 100.0;
    /**
     * 先回りに使う相手の速さ(10-19 夜): 位置の差を毎 tick この割合でならす(およそ 7 tick の平均。仮)。1 tick にこれ(ブロック)より動いたらテレポートとして 0 から。
     */
    public static final double TRACK_SMOOTH = 0.15;
    public static final double TRACK_JUMP = 10.0;
    /** 狙いのばらつき(ラジアン。しっかり狙う弾だけ)。 */
    public static final double AA_SPREAD = 0.006;

    // 樹液の弾(段階 1 から。決定「腺が膨らんで脈打ち、撃ち出す」)
    public static final int SAP_INTERVAL = 300;
    public static final double SAP_SPEED = 2.0;
    public static final int SAP_LIFE = 260;
    public static final float SAP_DAMAGE = 4.0F;
    public static final double SAP_BLAST = 3.0;
    /** 樹液がかかると遅くなる(鈍化 II、tick)。 */
    public static final int SAP_SLOW_TICKS = 120;
    /** 撃つのは sap_shot の何 tick 目か(縮んで撃つ所。道具の 2.5 秒の 1.75 秒目 × 時間の倍率 + 補間)。 */
    public static final int SAP_RELEASE = 64;
    /** sap_shot の長さ(tick。道具の 2.5 秒 × 時間の倍率)。 */
    public static final int SAP_ANIM_TICKS = 90;

    // 羽ばたきの下降気流(段階 1 から。決定「全部の扇をゆっくり大きく振り上げ → 一斉に一瞬で打ち下ろす。扇の先から雲の尾が下へ噴き出す」)
    public static final int DOWNDRAFT_COOLDOWN = 900;
    /** 打ち下ろす瞬間(tick。補間の後の、道具の 7 秒の 3.55〜3.9 秒 × 時間の倍率)。 */
    public static final int DOWNDRAFT_STRIKE_FROM = 128;
    public static final int DOWNDRAFT_STRIKE_TO = 141;
    /** 押し下げる範囲: 体の軸から横へ・下へ(ブロック)。 */
    public static final double DOWNDRAFT_REACH = 650.0;
    public static final double DOWNDRAFT_DEPTH = 700.0;
    /** 押し下げる速さ(ブロック / tick を足す)とダメージ。 */
    public static final double DOWNDRAFT_PUSH = 2.2;
    public static final float DOWNDRAFT_DAMAGE = 4.0F;

    // 雲を吐いて身を隠す(段階 1 から。決定「頭を下げて顎を開き、雲を吐き出す。その雲の中へ体をひねって潜り込む」)
    public static final int SPIT_COOLDOWN = 2400;
    /** 雲を吐く瞬間(雲を吐き始めてから。体の補間 1 秒 + 顎が開ききる道具の 1.2 秒目 × 時間の倍率)。 */
    public static final int SPIT_RELEASE = 63;
    /** 吐く雲の半径・高さ・長さ(tick)。雲は雲の作業の口(雰囲気づくり)。 */
    public static final double SPIT_RADIUS = 1000.0;
    public static final double SPIT_HEIGHT = 420.0;
    public static final int SPIT_LIFE = 1600;
    /** 弾を見せる距離(撃った・終わった所から)。 */
    public static final double SHOT_SEND_RANGE = 3000.0;

    // 触手で打つ・絡め取る(段階 2 から。決定「上へなびく触手が鞭のようにしなり、近くを飛ぶ相手を打つ・絡め取る」)
    /** 触手の技を出す段階・間(tick)・触手(の当たりの形)からこれより近い相手がいるとき。 */
    public static final int TENT_STAGE = 2;
    public static final int TENT_COOLDOWN = 260;
    public static final double TENT_TRIGGER = 90.0;
    /**
     * 打つ: 当たる間(触手の技を始めてから)・届く余り(触手の太さの外へ)・ダメージ・はね飛ばす速さ。当たる間は GameTest で測った触手の先の速さから
     * (10-19: 54〜72 tick に 1 tick 20〜30 ブロック、いちばん速いのは 62 tick。触手の長さは 約 370 ブロック)。
     */
    public static final int LASH_FROM = 48;
    public static final int LASH_TO = 80;
    public static final double LASH_MARGIN = 5.0;
    public static final float LASH_DAMAGE = 10.0F;
    public static final double LASH_KNOCK = 2.4;
    /**
     * 絡め取る: 捕まえる間(巻き始め。測った先の速さは 72〜84 tick に 1 tick 13 ブロック)・放す tick(ほどく速さの山 150〜168 tick の頭)・捕まえる余り・
     * 締めるダメージと間(tick)・放すときに投げる速さ。
     */
    public static final int GRAB_FROM = 44;
    public static final int GRAB_TO = 90;
    public static final int GRAB_RELEASE = 150;
    public static final double GRAB_MARGIN = 14.0;
    public static final float GRAB_DAMAGE = 3.0F;
    public static final int GRAB_DAMAGE_EVERY = 20;
    public static final double GRAB_FLING = 2.0;

    // 種の爆弾の打ち上げ(段階 2 から。決定「腹の口が開き、打ち上げるたびに体が反動で震える(高度は変わらない)」。ユーザー指定 10-19「地上で爆発して汚染」)
    public static final int SEED_STAGE = 2;
    public static final int SEED_INTERVAL = 500;
    /** 地上の相手: 体のいちばん下よりこれだけ下にいて、体の軸から横へこれより近い。 */
    public static final double SEED_BELOW = 40.0;
    public static final double SEED_REACH = 450.0;
    /** 打ち上げる tick(seed_launch の押し出し。補間 4 + 道具の 3 秒の 0.8 秒目 × 時間の倍率)と、1 回に打ち上げる数・狙いのばらつき(ブロック)。 */
    public static final int SEED_RELEASE = 30;
    public static final int SEED_COUNT = 3;
    public static final double SEED_SCATTER = 14.0;
    /** 打ち上げる速さ(腹の外向き、ブロック / tick)と、寿命(tick。y 950 から地上まで落ちきる長さ)。 */
    public static final double SEED_UP = 1.4;
    public static final int SEED_LIFE = 900;
    /** 地面での爆発: 半径・ダメージ・汚染する円の半径とその縁のゆらぎ。 */
    public static final double SEED_BLAST = 6.0;
    public static final float SEED_DAMAGE = 9.0F;
    public static final double SEED_CORRUPT_RADIUS = 6.0;
    public static final double SEED_CORRUPT_NOISE = 2.0;
    /** 反動の震え(tick・度)。 */
    public static final int SEED_SHAKE_TICKS = 30;
    public static final float SEED_SHAKE_DEG = 0.35F;

    // 大技 1: 転がりながらビーム(段階 2 から。決定「光を溜めきると、蕾が頭から尾へ波のように順に開き、体の長さの軸で転がりながら全部の蕾からビームを出し続ける」)
    public static final int BEAM_STAGE = 2;
    /**
     * 出す所(10-19 夜ユーザー「巨大生物の中心付近の横にいる時に行うように。頭付近で観察していた際に何度か発動されましたが、全く脅威になりませんでした」):
     * 相手を体の軸へ落とした所が、腕の付いた所の真ん中({@link SkyBossBody#ARM_CENTER}、頭の先から 約 580)の前後これだけの中で、軸からこれより近いとき。仮。
     * 前後 300 = 腕の付いた所(頭の先から 282〜882。頭と首は 0〜282)。最初の 400 では頭のすぐ後ろ(182)から入り、頭の横で出た(テストクライアント)。
     */
    public static final double BEAM_ZONE_HALF = 300.0;
    public static final double BEAM_ZONE_DIST = 900.0;
    /** 日の光が溜まりきるまで(秒。晴れの昼。決定「晴れ = 日の光をよく吸うので大技 1 が早く強い / 夜 = 光が溜まりにくい」)と、天気・夜の倍率・段階 3 の倍率。 */
    public static final double BEAM_LIGHT_CLEAR_SECONDS = 45.0;
    public static final double BEAM_LIGHT_RAIN = 0.4;
    public static final double BEAM_LIGHT_THUNDER = 0.15;
    public static final double BEAM_LIGHT_NIGHT = 0.2;
    public static final double BEAM_LIGHT_STAGE3 = 1.5;
    /** 頭から尾へ蕾が開く長さ(tick。道具の buds_wave_open 4.6 秒 × 時間の倍率)。 */
    public static final int BEAM_WAVE_TICKS = 166;
    /** 転がり始めるまで(芯が光ってから)・転がる長さ(tick)・転がる回数(段階 2・段階 3。決定「段階 3 は転がりが速い」)。 */
    public static final int BEAM_ROLL_DELAY = 60;
    public static final int BEAM_ROLL_TICKS = 480;
    public static final int BEAM_TURNS = 2;
    public static final int BEAM_TURNS_STAGE3 = 3;
    /** 転がりが一番速くなる所(転がりの割合。案「ゆっくり始まってだんだん速く」→ ここまで速くなり、後は止まる)。 */
    public static final double BEAM_PEAK = 0.8;
    /** 転がりが頭から尾へ伝わる遅れ(tick。腹 ⇄ 背の ROLL_LAG_TICKS より短い: 速く回るので、長いと体がねじれすぎる)。 */
    public static final int BEAM_ROLL_LAG = 30;
    /** ビームの長さ(地上まで届く)・当たりの太さ(半径)・ダメージ(晴れの昼はその倍率)・屋根で防げる距離(相手から蕾へ、ブロック)。 */
    public static final double BEAM_LENGTH = 1600.0;
    public static final double BEAM_WIDTH = 4.0;
    public static final float BEAM_DAMAGE = 6.0F;
    public static final float BEAM_CLEAR_MULT = 1.5F;
    public static final double BEAM_COVER = 48.0;
    /** ビームの揺れ(10-19 ユーザー「上下左右に少しだけ動かしながら」): 外向きからの最大の傾き(度)と、主な波の長さ(tick)。仮。 */
    public static final double BEAM_SWAY_DEG = 7.0;
    public static final double BEAM_SWAY_TICKS = 70.0;

    // 生き物らしさ: 一日のリズム・ときどきの身震い(10-19 夕方。決定は行動パターン「生き物らしさ」、数値はすべて仮)
    /** 眠る時刻(dayTime、0〜24000。夕暮れの後 〜 夜明けの前)と、晴れた朝の日光浴の終わり(朝 9 時)。 */
    public static final int SLEEP_FROM = 13000;
    public static final int SLEEP_TO = 23000;
    public static final int BASK_TO = 3000;
    /** 眠っている間の速さ(ふだんの速さに掛ける)。 */
    public static final double SLEEP_SPEED = 0.6;
    /** 日光浴: 腹を日の昇る方(東)へ傾ける最大の角度(度)と、傾け始め・戻すのにかける tick。 */
    public static final double BASK_TILT_DEG = 25.0;
    public static final int BASK_EASE = 200;
    /** ときどきの身震いの間(tick。この間で乱数)と、体の震えの大きさ・長さ。 */
    public static final int CASUAL_SHIVER_MIN = 3600;
    public static final int CASUAL_SHIVER_MAX = 7200;
    public static final float CASUAL_SHAKE_DEG = 0.6F;
    public static final int CASUAL_SHAKE_TICKS = 60;

    // 雲海に潜る・飛び出す(10-19 夕方。決定は行動パターン「潜伏と飛び出し(雲海)」、数値はすべて仮)
    /** 体の厚み(ブロック。体がまるごと雲の海に入るか。いちばん太い輪切りの縦の半径 40 × 2)と、雲の海の天辺・底からの余り。 */
    public static final double BODY_THICKNESS = 80.0;
    public static final double SEA_MARGIN = 10.0;
    /** 潜ろうとする間(tick)・その時に潜る割合・前方のこれだけ先にも深い雲の海があるとき(ブロック)。 */
    public static final int SEA_TRY_EVERY = 2400;
    public static final double SEA_DIVE_CHANCE = 0.35;
    public static final double SEA_AHEAD = 800.0;
    /** 下りる・上がる機首の角度の上限(度)と、角度を変える速さの上限(度 / tick)。着いたとみなす高さの差(ブロック)。 */
    public static final float SEA_DIVE_PITCH = 14.0F;
    public static final float SEA_RISE_PITCH = 12.0F;
    public static final float SEA_PITCH_RATE = 0.12F;
    public static final double SEA_ARRIVE = 12.0;
    /** 隠れている長さ(tick)・隠れ終えたら飛び出す割合(残りは上がる)・上がり終えてから次に潜ろうとするまで(tick)。 */
    public static final int SEA_HIDE_MIN = 3600;
    public static final int SEA_HIDE_MAX = 7200;
    public static final double SEA_BREACH_CHANCE = 0.6;
    public static final int SEA_COOLDOWN = 12000;
    /** 奇襲: 頭から横へこれより近く、雲の海の天辺からこれより低い所をプレイヤーが飛んでいたら飛び出す。 */
    public static final double SEA_AMBUSH_RANGE = 700.0;
    public static final double SEA_AMBUSH_HEIGHT = 500.0;
    /** 飛び出しの予告(雲面の盛り上がり・低い唸り)から飛び出すまで(tick)と、盛り上がりを出す頭の前の距離・半径。 */
    public static final int BREACH_TELEGRAPH = 60;
    public static final double BREACH_BULGE_AHEAD = 150.0;
    public static final float BREACH_BULGE_RADIUS = 220.0F;
    /**
     * 飛び出しの頭の道(tick。飛び出しを始めてから): 上りの機首の角度が 0 → 最大になる間・保つ終わり・真ん中(てっぺん。ここをはさんで下りは同じ形)と、
     * 最大の機首の角度(度)・速さの倍率。扇・体のアニメ(補間 40 + 32.4 秒)の中に収める。頭が雲海へ戻って穴を開ける tick。
     */
    public static final double BREACH_UP_FROM = 0.0;
    public static final double BREACH_UP_TO = 70.0;
    public static final double BREACH_HOLD_TO = 150.0;
    public static final double BREACH_HALF = 330.0;
    public static final double BREACH_PITCH = 50.0;
    public static final double BREACH_SPEED = 3.0;
    public static final int BREACH_REENTER = 537;

    // 段階が変わるとき(決定「体じゅうを震わせて垂れ草と胞子を振り落とし、開く蕾が増える」)
    public static final int STAGE_SHAKE_TICKS = 120;
    public static final float STAGE_SHAKE_DEG = 2.2F;

    // ------------------------------------------------------------------ 確かめ用のコマンド(/ov sky)
    /** summon: プレイヤーの風上のこれだけ先に頭を置く(風に流されてプレイヤーの上を通る)。 */
    public static final double SUMMON_UPWIND = 1500.0;
    /** tp: 体の真ん中から横へこれだけ離れた所へ(全身が見える距離)。 */
    public static final double TP_SIDE = 1200.0;

    private SkyBossTuning() {
    }
}
