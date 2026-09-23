package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.world.entity.Mob;

/**
 * グループ4(拡大種)の<b>消え方</b>(ユーザー報告 2026-09-15:「少し離れただけでデスポーンする」)。
 *
 * <h2>バニラの規則</h2>
 * {@code Mob#checkDespawn} は 2 つの条件で Mob を消す。どちらも
 * {@code removeWhenFarAway(距離の2乗)} が true を返したときだけ実行される。
 * <ol>
 *     <li><b>遠すぎる</b> — いちばん近いプレイヤーから 128 ブロック超(MONSTER)。<b>即座に消える。</b></li>
 *     <li><b>暇そう</b> — <b>32 ブロック</b>より遠くにいて、{@code noActionTime} が 600(30 秒)を
 *     超えていると、<b>毎 tick 1/800 の確率で消える</b>(平均 40 秒ほどで当たる)。
 *     {@code noActionTime} は毎 tick 増え、<b>32 ブロック以内にプレイヤーが来たときだけ 0 に戻る</b>。</li>
 * </ol>
 *
 * <h2>なぜグループ4 では成り立たないのか</h2>
 * 32 ブロックは「少し離れた」でしかないのに対し、グループ4 は<b>プレイヤーから離れて働く</b>設計:
 * <ul>
 *     <li><b>ヴェロフィル</b>は地表から {@code velophyllCruiseAltitude}(既定 28)＋ばらつき +5 の
 *     高さを飛ぶので、<b>真上を飛んでいるだけで 32 ブロックを超える</b>。つまり見えているのに
 *     30 秒後から抽選が始まり、1 分ほどで消える。</li>
 *     <li><b>フィロセンス</b>は「32 ブロック以内にプレイヤーが 1 分来なかったら索敵」、
 *     <b>ヴェロフィル</b>は「3 分プレイヤーを見つけられなかったら地上へ降りて汚染」——
 *     どちらも<b>その時間を待つ前に消える</b>ので、機能そのものが発動しない。</li>
 *     <li>過繁茂バイオームの外へ歩いて出て、群れを組んで土地を汚す({@link InvaderPack})のも、
 *     プレイヤーが付いて回らないと成立しない。</li>
 * </ul>
 *
 * <h2>やっていること</h2>
 * <b>「暇そう」の抽選だけを止め、「遠すぎる」はそのまま残す。</b>
 * {@code removeWhenFarAway} は上の 2 つから同じ引数で呼ばれるので、
 * <b>{@code invaderDespawnRange} の内側では false・外側では true</b> を返せば、
 * 1 つ目(距離が範囲外のときだけ呼ばれる)は通り、2 つ目(範囲内で呼ばれる)は落ちる。
 *
 * <p>数を抑える歯止めは「遠すぎる」が引き続き担う —— グループ4 は外へ歩いていく種なので、
 * 遅かれ早かれ範囲外に出て消える。
 *
 * <h2>例外: 編成が揃った群れ(2026-09-15)</h2>
 * <b>編成が揃った群れの一員は一切消えない</b>(ユーザー要望)。そこだけは
 * 「遠すぎる」も通さないので、揃った群れは<b>永久に残る</b>。
 * 詳しくは {@link #removeWhenFarAway}。
 */
public final class InvaderDespawn {

    /**
     * {@code Mob#removeWhenFarAway} の中身。
     *
     * <p><b>編成が揃った群れの一員は一切消えない</b>(ユーザー要望 2026-09-15)。
     * アルボラ 8 / スポロフィル 2 / フィロセンス 1 / ヴェロフィル 3 / スカトゥリウム 2 が
     * そろった群れは、ようやく出来上がった「隊」なので、プレイヤーが離れたくらいで
     * 崩してしまうと編成を組み直すところからやり直しになる。
     *
     * <p>判定は毎 tick 呼ばれるので、ここでは<b>印を見るだけ</b>。
     * 実際に数えるのは {@link InvaderPackBonds} が
     * {@code invaderPackBondIntervalTicks} ごとに 1 回だけ行う。
     *
     * <p class="warn">揃った群れは<b>永久に残る</b>。数の歯止めは
     * 「揃うまでは今までどおり消える」ことと、湧きそのものの上限だけになる。
     * 増えすぎるようなら {@code invaderPackCompleteGraceTicks} を 0 にすると
     * この仕組みごと止められる。
     *
     * @param mob                       判定する個体
     * @param distanceSqToNearestPlayer いちばん近いプレイヤーまでの距離の<b>2 乗</b>
     *                                  (バニラの引数名は {@code distanceToClosestPlayer} だが
     *                                  渡ってくるのは {@code distanceToSqr} の値)
     */
    public static boolean removeWhenFarAway(Mob mob, double distanceSqToNearestPlayer) {
        if (mob instanceof InvaderPackMember member && member.packBond().completeAt(mob.tickCount)) {
            return false;
        }
        double range = OvergrownVegetationConfig.COMMON.invaderDespawnRange.get();
        return distanceSqToNearestPlayer > range * range;
    }

    private InvaderDespawn() {
    }
}
