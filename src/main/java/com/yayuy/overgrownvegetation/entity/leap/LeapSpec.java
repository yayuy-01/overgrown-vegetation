package com.yayuy.overgrownvegetation.entity.leap;

/**
 * 「跳ぶ型」の種ごとの値(2026-11-14、サルティムネスで作った。トリツキの跳び移り・アルボラ・ヴェナムネスの段差越えにも渡せるよう種に依らない形)。
 *
 * @param minDist      跳ぶ水平距離の下限(ブロック)
 * @param maxDist      跳ぶ水平距離の上限(ブロック)
 * @param maxUp        上へ跳び上がれる高さ(ブロック)
 * @param maxDown      下へ跳び下りてよい高さ(ブロック。自分の跳びでは落下ダメージを受けない)
 * @param minLift      弧の頂点を、出る所・着く所の高いほうからこれだけは上げる(ブロック)
 * @param maxLift      同じく上げてよい上限(ブロック)。弧はこの範囲で低いものから試し、通り道が空いた最初のものを使う
 * @param bodyFront    体の芯の中心から鼻先まで(ブロック、体格 1)。当たりは芯だけでも、跳ぶ・着く所の点検は体の長さで見る
 * @param bodyBack     体の芯の中心から腹の先まで(ブロック、体格 1)
 * @param bodyHalfWidth 体の長さの点検に使う箱の半幅(ブロック、体格 1。芯より細くてよい)
 * @param gravity      弧の縦の加速度(ブロック/tick²)。宙にいる時間 = これと弧の高さで決まる(バニラの生き物は 0.08)
 * @param crouchTicks  身を沈めてから蹴るまで(tick。向きが揃ってから数える)
 * @param landTicks    着地の後、次の動きへ移るまで(tick)
 */
public record LeapSpec(double minDist, double maxDist, double maxUp, double maxDown, double minLift, double maxLift,
                       double bodyFront, double bodyBack, double bodyHalfWidth, double gravity, int crouchTicks, int landTicks) {
}
