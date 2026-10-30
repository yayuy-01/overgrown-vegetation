package com.yayuy.overgrownvegetation.td;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * TD イベント(森の侵攻)の<b>外から呼ぶ口</b>(2026-09-25)。ターゲットブロック({@link TargetBlock} /
 * {@link TargetBlockEntity})・攻城({@code TdSiege})・生態系防衛・{@code EventMobs} が使う。中身は {@link TdTicker} と {@link TdData}。
 *
 * <p>「進行中」= 予告中(WARNING)か侵攻中(ACTIVE)。予告中はまだ攻め手がいないので、削り・勝ち負けは侵攻中だけ起きる。
 * どれもサーバースレッドから呼ぶこと。
 */
public final class TdEvents {

    /**
     * ターゲットの耐久が 0 になった({@link TargetBlockEntity#damage} から、ブロックを消す<b>前</b>に)。
     *
     * <p>侵攻中・そのイベントのターゲットなら<b>負け</b>: 状態を先に終わりにしてからブロックを消し
     * (消えたときの {@link TargetBlock#onRemove} は {@link #isDefeating} で負けの処理と分かる)、拠点のまわりに汚染を噴き出させ、
     * 残りの予算と仮想の群れを世界のバイオマスへ返す。増援の一時停止({@link #isPaused})中でも負けにする —— 攻め手が拠点で
     * 動いている限り攻城は効く({@link #isSiegeLive})。
     * そうでない(予告中・別のブロック・コマンドで直接削った)なら負けにせず、耐久を満タンへ戻す
     * (耐久 0 のまま残ると、以後どの攻撃も「もう壊れている」で弾かれる)。
     */
    public static void onTargetDestroyed(ServerLevel level, BlockPos pos) {
        TdData data = TdData.get(level.getServer());
        TdData.Event event = data.active();
        if (event == null || !event.dimension().equals(level.dimension()) || !event.target().equals(pos)) {
            TargetBlockEntity target = TdTargets.get(level, pos);
            if (target != null) {
                target.resetHp();
            }
            OvergrownVegetation.LOGGER.info("TD: 侵攻中でないターゲット {} の耐久が 0 になったので満タンへ戻した", pos);
            return;
        }
        TdTicker.defeat(level, data, event);
    }

    /**
     * ターゲットブロックが耐久 0 の負け以外で無くなった({@link TargetBlock#onRemove}: 爆発・クリエイティブ・{@code /setblock} など)。
     * <ul>
     *     <li><b>侵攻中</b>のターゲットなら<b>負け</b>(汚染が噴き出す)。仮決め 2026-09-25(レビュー指摘): はじめは「撤去 = 中止・払い戻し」
     *     だったので、耐久が 10% の知らせを見てから掘れば、ユーザー指定(2026-09-24)の「ターゲットが壊れたら負け・拠点の周りに汚染」を
     *     一度も受けずに済んだ。いまは進行中のターゲットをプレイヤーが掘れない({@link TargetBlock} の {@code getDestroyProgress})ので、
     *     ここへ来るのは爆発・クリエイティブ・コマンドだけで、それも「拠点が落ちた」と数える。</li>
     *     <li><b>予告中</b>のターゲットなら取りやめ(まだ何も切り出していない)。</li>
     * </ul>
     * 中止して払い戻すのは {@code /ov td stop} とディメンションが無いときだけ。
     */
    public static void onTargetRemoved(ServerLevel level, BlockPos pos) {
        TdData data = TdData.get(level.getServer());
        TdData.Event event = data.live();
        if (event == null || !event.dimension().equals(level.dimension()) || !event.target().equals(pos)) {
            return;
        }
        if (event.state() == TdData.State.ACTIVE) {
            OvergrownVegetation.LOGGER.info("TD: 侵攻中のターゲット {} が耐久 0 以外で無くなったので負けにする", pos);
            TdTicker.defeat(level, data, event);
        } else {
            TdTicker.cancel(level.getServer(), data, event, TdData.Outcome.CANCELLED, level.getGameTime());
        }
    }

    /** 負けの処理でそのターゲットを消している最中か({@link TargetBlock#onRemove} が「撤去」と取り違えないように)。 */
    public static boolean isDefeating(ServerLevel level, BlockPos pos) {
        return TdData.get(level.getServer()).isDefeating(level.dimension(), pos);
    }

    /**
     * そのワールドで森の<b>増援</b>を止めているか。進行中で、ターゲットから {@code pauseWithoutPlayersRadius} に誰もいない間は true
     * (新しい仮想の群れ・行進・実体化・残党の片付け・勝ちの判定を止める)。このワールドに進行中のイベントが無いときも true。
     *
     * <p><b>攻城(塊と壁の削り・負け)はこれを見ない</b>({@link #isSiegeLive})。仮決め 2026-09-25(レビュー指摘): はじめは削りも止めていたが、
     * 攻め手のいる拠点は 128 ブロックより遠く(シミュレーション距離の四角の角まで)でも動き続けるので、耐久が減ったら 130〜190 ブロック
     * 離れて削りだけを止め、自動の砲台に攻め手を片付けさせれば、侵攻に負けようがなかった。
     */
    public static boolean isPaused(ServerLevel level) {
        TdData.Event event = TdData.get(level.getServer()).live();
        if (event == null || !event.dimension().equals(level.dimension())) {
            return true;
        }
        return event.paused();
    }

    /**
     * 攻城が効くか: {@code target} が、このワールドの<b>侵攻中</b>(ACTIVE。予告中は攻め手がいない)のイベントのターゲット。
     * 増援の一時停止({@link #isPaused})は見ない —— 攻め手が拠点で動いている(= 群れの Goal が塊を叩きに来る)なら削れる。
     * {@code TdSiege} の壁選び・叩く・見張りと、負けの判定が使う。
     */
    public static boolean isSiegeLive(ServerLevel level, @Nullable BlockPos target) {
        if (target == null) {
            return false;
        }
        TdData.Event event = TdData.get(level.getServer()).active();
        return event != null && event.dimension().equals(level.dimension()) && event.target().equals(target);
    }

    /** {@code pos} が、このワールドの進行中(予告中・侵攻中)のイベントのターゲットか。 */
    public static boolean isActiveTarget(ServerLevel level, BlockPos pos) {
        TdData.Event event = TdData.get(level.getServer()).live();
        return event != null && event.dimension().equals(level.dimension()) && event.target().equals(pos);
    }

    /** このワールドの進行中のイベントのターゲット。無い・別のワールドなら null。 */
    @Nullable
    public static BlockPos activeTarget(ServerLevel level) {
        TdData.Event event = TdData.get(level.getServer()).live();
        return event != null && event.dimension().equals(level.dimension()) ? event.target() : null;
    }

    /** 進行中のイベントの id(攻め手の印の値)。ワールドを問わない。無ければ null。 */
    @Nullable
    public static String activeEventId(ServerLevel level) {
        TdData.Event event = TdData.get(level.getServer()).live();
        return event != null ? event.eventId() : null;
    }

    /** {@code id} が進行中のイベントか({@code EventMobs} の TD の印が生きているか)。 */
    public static boolean isLiveEvent(ServerLevel level, String id) {
        TdData.Event event = TdData.get(level.getServer()).live();
        return event != null && event.eventId().equals(id);
    }

    /**
     * そのプレイヤーの位置のまわりで生態系防衛の兵を出さないか。進行中のイベントのターゲットから
     * {@code defenseSuppressRadius}(仮決め 256)以内なら true(TD 設計 v2: 侵攻中に防衛の兵が拠点へ重ならないように)。
     */
    public static boolean suppressesEcosystemDefense(ServerLevel level, BlockPos pos) {
        BlockPos target = activeTarget(level);
        if (target == null) {
            return false;
        }
        double r = com.yayuy.overgrownvegetation.data.DesignData.td().defenseSuppressRadius();
        return target.distSqr(pos) <= r * r;
    }

    private TdEvents() {
    }
}
