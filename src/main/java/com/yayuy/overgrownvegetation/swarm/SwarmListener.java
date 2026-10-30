package com.yayuy.overgrownvegetation.swarm;

import net.minecraft.world.entity.Mob;

/**
 * 群れを<b>組み直したとき</b>に、保存していない状態(TD の攻城・標的の塊など)を戻すための口(群れ v2、2026-09-25)。
 * {@link SwarmManager#addListener} で 1 回だけ登録する(同じものを 2 度登録しても 1 回と数える)。
 *
 * <p>群れは実行時だけのデータなので、チャンクが読み込み直されたり、サーバーを再起動したりすると、
 * 読み込まれた個体の所属から新しく組み直される。モードや標的の塊は保存していないので、
 * 組み直した群れはただの暇な群れとして動き出す(レビュー指摘「TD の群れが読み込み直すと外へ歩いていく」)。
 * そこでこの口を通して、イベントの側が一員の印(EventMobs の TD の印など)を見て状態を戻す。
 *
 * <p>どれもサーバースレッドから呼ぶ。中で群れの一員を増やしたり減らしたり(discard・join・leave)しないこと。
 */
public interface SwarmListener {

    /**
     * 実行時の群れができて、<b>最初に考える</b>とき(読み込まれている一員を見直し、先導役を選んだ直後、モードを決める前)。
     * この時点で読み込まれている一員は {@link Swarm#members()}。あとから読み込まれた一員は {@link #onMemberRegistered} で届く。
     */
    default void onSwarmRebuilt(Swarm swarm) {
    }

    /**
     * 一員が群れに登録された(読み込み・湧いた・{@link SwarmManager#join}・自己修復)。
     * 組み直した群れにあとから読み込まれた一員の印を見るのに使う。
     */
    default void onMemberRegistered(Swarm swarm, Mob member) {
    }

    /** 群れが空になって消えた(次の tick の終わり)。 */
    default void onSwarmRemoved(Swarm swarm) {
    }
}
