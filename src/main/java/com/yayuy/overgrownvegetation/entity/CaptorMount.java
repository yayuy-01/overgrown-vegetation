package com.yayuy.overgrownvegetation.entity;

import net.minecraft.world.entity.Entity;

/**
 * 相手を「乗せる」作りで捕まえる Mob の目印(2026-11-16、全体管理経由のユーザー決定)。クリペイムネスの抱え込み・サルティムネスの押さえ込みが使う。
 * 捕まった相手は自分から降りられないので、乗っている間はプレイヤーの画面から乗り物の体力の欄と「左 Shift で降りる」の案内を隠す
 * ({@code client/CaptorMountHud})。乗せるのは捕まえたときだけ、という Mob にだけ付ける。
 *
 * <p><b>技の状態は保存しないが、乗客は保存される</b>(2026-11-16 コードの見直し ①1)。チャンクが外れて読み込み直すと、技は「何もしていない」のに
 * 獲物を乗せたまま戻り、獲物の Mob は自分では降りず、次の捕まえも「もう乗っている」で失敗し続けた。種は毎 tick(サーバー、AI の後)に
 * {@link #releaseStray} を呼び、捕まえている段でないのに乗っているものを放す(ファルキムネスの掴みも同じ形)。</p>
 */
public interface CaptorMount {

    /**
     * 捕まえている段({@code holding})でないのに乗客がいれば {@code release} で放す。放したら true。
     * 捕まえる tick は「乗せる → 段を進める」を同じ Goal の tick の中で済ませるので、AI の後に呼べば取り違えない。
     */
    static boolean releaseStray(Entity captor, boolean holding, Runnable release) {
        if (holding || !captor.isVehicle()) return false;
        release.run();
        return true;
    }
}
