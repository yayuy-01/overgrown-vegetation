package com.yayuy.overgrownvegetation.entity.stilt;

/**
 * 竹馬型(移動の型 ③、2026-11-10。1 体目はフィロセンス)の種ごとの数値。全部【仮】(Claude 11-10)。ユーザーが決めた値は【決定】と書く。
 *
 * <p>高さの言い方: <b>胴の高さ</b> = 胴の中心(モデルの root の pivot)が足元の地面からどれだけ上か。エンティティの位置は胴の箱の底
 * (= 胴の中心 − {@link #bodyHalf})。
 *
 * <p>脚の置き場の基準 {@code homeLeft / homeFwd} はブロック。left = 胴の左へ、fwd = 前へ。並びは 左前・右前・左後・右後
 * (モデルの骨 {@code leg_fl / leg_fr / leg_bl / leg_br})。
 */
public final class StiltParams {

    /** 胴の箱の半分の高さ(胴の当たり判定 1.5 × 1.5【仮・依頼 11-10】)。 */
    public double bodyHalf = 0.75D;
    /** 胴の高さの帯: 一番低く(かがむ)・一番高く(脚を伸ばしきる手前)・ふつう。【仮】決定 11-10「約 2.5〜6.5、ふつうは高い側」。 */
    public double minHeight = 2.5D, maxHeight = 6.5D, preferHeight = 5.5D;
    /** これより低い胴は「かがんでいる」: 膝が胴より高く外へ張り出すので、横に幹・壁があるとかがめない【仮】。 */
    public double crouchBelow = 4.5D;
    /** 上り下りできる段差(ブロック)【決定 11-10 = 5】。 */
    public int maxStep = 5;
    /** 足を置ける水の深さ(ブロック)【決定 11-10 = 5、仮】。 */
    public int maxWaterDepth = 5;

    /** 付け根(股関節)の位置: 胴の中心から left / fwd / 上下(ブロック)。r2 のモデルの femur の pivot(±8, 98, ∓7)/16 から。 */
    public double[] hipLeft = {0.5D, -0.5D, 0.5D, -0.5D}, hipFwd = {0.44D, 0.44D, -0.44D, -0.44D};
    public double hipUp = -0.375D;
    /**
     * 付け根から足首(足先の骨の付け根)まで届く距離(ブロック)。腿 + 脛 = 前 5.89・後 5.92 から少し引いた【仮】。
     * 足先の骨は真下へ向けるので、足首 = 置き場 + {@link #ankleUp} 上。
     */
    public double legReach = 5.8D, ankleUp = 0.5D;
    /** 足先が付け根より上へ来ない(畳める限り)。付け根からの高さの差の下限(ブロック)【仮】。 */
    public double minDrop = 0.9D;
    /** 脚の置き場の基準(ブロック)。r2 の静止の足先 (±19, 0, −30 / +26)/16 を少し外へ開いた【仮】。 */
    public double[] homeLeft = {1.35D, -1.35D, 1.35D, -1.35D}, homeFwd = {1.9D, 1.9D, -1.7D, -1.7D};
    /** 踏み替える順(1 本ずつ。左後 → 左前 → 右後 → 右前、四つ足の常歩と同じ)。 */
    public int[] order = {2, 0, 3, 1};

    /** 速さ(移動速度の属性の値)。森 0.12・開けた所 0.28【仮・依頼 11-10】。実際の速さ(ブロック/tick)はバニラの地上の Mob と同じ換算 2.16 × s²。 */
    public double speedForest = 0.12D, speedOpen = 0.28D;
    /** 「開けた所」と見なす幹までの距離(ブロック、ForestMap)。0 = 幹のそば = 森の速さ【仮】。 */
    public double openClearance = 4.0D;
    /** 踏み替えの長さ(tick): 一番短く・一番長く【仮】。 */
    public int swingMin = 6, swingMax = 14;
    /** 足を上げる高さ(ブロック)【仮】。 */
    public double swingLift = 0.9D;
    /** 置き場からこれだけずれたら踏み替える(ブロック)【仮】。 */
    public double stepAt = 0.55D;
    /** 体の動きを先回りする tick(足を置き場の少し前へ着く)【仮】。 */
    public double lead = 22.0D;
    /** 胴の上下の速さの上限(ブロック/tick)と回る速さの上限(度/tick)【仮】。 */
    public double maxVy = 0.08D, maxTurn = 4.0D;
    /** 足が下りた所の人・Mob を押しのける速さ(ダメージ無し)【決定 11-10「押しのけるだけ」、値は仮】。 */
    public double footPush = 0.45D;

    /**
     * 届かなかった目標を覚えておく tick【仮 100 = 5 秒】。その間は探し直さず一番近づける所で待つ(同じ目標・近くから来た個体も使い回す)。
     * 過ぎたら探し直す = 目の前で道が開いたときに気づくまでの長さ(依頼 11-11「数秒」)。ファルキムネスの「失敗したら 10 秒空ける」と同じ考え。
     */
    public int unreachableTicks = 100;
    /** 届かないまま探し直す回数の上限(近づけなくなったら目標を手放す)【仮】。 */
    public int unreachableTries = 6;
    /** 経路の探索に使う時間: サーバー全体の予算 2 ms/tick の残りから、歩き・足のために残す分と、これより少なければその tick は探さない分(ns)【仮】。 */
    public long planReserveNanos = 400_000L, planMinSliceNanos = 250_000L;

    // ------------------------------------------------------------------ 段 2(11-14): 首の動き・見張り・覗き。全部【仮・Claude】

    /** 止まってから枯れ木に化けるまで(tick)・化けたときの足の置き場の広さ(倍)・覗くときの広さ。 */
    public int disguiseAfter = 60;
    public double disguiseStance = 0.55D, peerStance = 1.25D;
    /** 相手を見る距離(仕様 9 節「視線 128」)・森で覗きに気づく距離・覗きに行く(開口を探す)距離(ブロック)。 */
    public double sightRange = 128.0D, noticeRange = 32.0D, peerRange = 40.0D;
    /** 見え続けて印が付くまで(tick。この間に隠れ直せば付かない)。 */
    public int markDelay = 40;
    /** 化けてから見張りに出るまで(tick)・見張りの長さ・見張りの場所を探す半径(ブロック)。 */
    public int watchEvery = 400, watchFor = 600, watchRadius = 16;
    /** 森で覗く: 頭の置き場を探す点の数・やり直す回数・あきらめた後の待ち(tick)。 */
    public int peekSamples = 96, peekRetries = 3, peekCooldown = 200;
    /** 開口を覗く: 相手のまわりを探す半径(ブロック)・1 tick に調べるマスの数・立ち位置の開口からの距離・胴の中心の高さ・前へ倒す角(度)・覗く長さ(tick)。 */
    public int openingRadius = 6, openingCellsPerTick = 700;
    public double peerStandOff = 3.0D, peerHeight = 2.5D, peerLean = 25.0D;
    public int peerFor = 300;
    /** 頭を叩かれたら覗くのをやめる長さ(tick)【決定 11-10「しばらく」、値は仮 10 秒】。 */
    public int headHitCooldown = 200;
    /** 叩ける頭の大きさ(ブロック、つぼみ)。 */
    public float headSize = 1.2F;

    public static StiltParams phyllosens() {
        return new StiltParams();
    }

    /** 移動速度の属性の値 → ブロック/tick(バニラの地上の Mob の測り 11-05: 0.23 → 0.112・0.28 → 0.169)。 */
    public static double blocksPerTick(double attribute) {
        return 2.16D * attribute * attribute;
    }
}
