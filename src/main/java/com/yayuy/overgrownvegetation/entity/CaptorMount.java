package com.yayuy.overgrownvegetation.entity;

/**
 * 相手を「乗せる」作りで捕まえる Mob の目印(2026-11-16、全体管理経由のユーザー決定)。クリペイムネスの抱え込み・サルティムネスの押さえ込みが使う。
 * 捕まった相手は自分から降りられないので、乗っている間はプレイヤーの画面から乗り物の体力の欄と「左 Shift で降りる」の案内を隠す
 * ({@code client/CaptorMountHud})。乗せるのは捕まえたときだけ、という Mob にだけ付ける。
 */
public interface CaptorMount {
}
