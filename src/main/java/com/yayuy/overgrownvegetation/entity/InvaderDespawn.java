package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.swarm.SwarmMember;
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
 *     <li>過繁茂バイオームの外へ群れで歩いて出て土地を汚す({@link com.yayuy.overgrownvegetation.swarm.SwarmManager})のも、
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
 * <h2>群れの一員は群れごと消える(群れ v2、ユーザー指定 2026-09-25)</h2>
 * 群れの一員はここでは<b>一切消さない</b>(false)。消えるのは群れごと —— 群れの司令塔が、読み込まれている全員が
 * いちばん近いプレイヤーから {@code invaderDespawnRange} より遠いときに、永続でない一員をまとめて消す
 * ({@code swarm/SwarmController} の消え方)。1 体ずつバニラに消させると、群れの端の 1 体だけが抜けて隊列が崩れる。
 * 2026-09-15 の「編成が揃った群れは消えない」はユーザーが取り下げた(揃った群れが永久に残って溜まっていた)。
 *
 * <p>群れに<b>居ない</b>個体(スポーンエッグ・{@code /summon}・外湧き・群れから外れた個体・ペディキュラ)は、
 * 上の「遠すぎるときだけ消える」のまま。
 */
public final class InvaderDespawn {

    /**
     * {@code Mob#removeWhenFarAway} の中身。
     *
     * <ul>
     *     <li>群れの所属(attachment {@code swarm})を持っている → false。名簿で引けるかは問わない
     *     ({@link SwarmMember#hasSwarmAttachment}。読み込み直後で名簿にまだ居ない一員を、群れの判断の前にバニラが消さないように)。
     *     消すのは群れの司令塔だけ(全員が遠いとき、永続でない一員を)。</li>
     *     <li>それ以外(群れに居ない個体) → {@code invaderDespawnRange} の外側でだけ true(「暇そう」の抽選は止める)。</li>
     * </ul>
     *
     * @param mob                       判定する個体
     * @param distanceSqToNearestPlayer いちばん近いプレイヤーまでの距離の<b>2 乗</b>
     *                                  (バニラの引数名は {@code distanceToClosestPlayer} だが
     *                                  渡ってくるのは {@code distanceToSqr} の値)
     */
    public static boolean removeWhenFarAway(Mob mob, double distanceSqToNearestPlayer) {
        if (SwarmMember.hasSwarmAttachment(mob)) {
            return false;
        }
        double range = OvergrownVegetationConfig.COMMON.invaderDespawnRange.get();
        return distanceSqToNearestPlayer > range * range;
    }

    private InvaderDespawn() {
    }
}
