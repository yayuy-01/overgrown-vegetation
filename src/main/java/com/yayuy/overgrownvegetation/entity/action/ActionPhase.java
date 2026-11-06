package com.yayuy.overgrownvegetation.entity.action;

/**
 * 1 つの技の段(2026-11-05、層 2 の共通部品)。教科書 15 章の区切り: <b>前触れ</b>(構えに入る + 合図。判定はまだ無い)→
 * <b>実行</b>(判定が出ている)→ <b>隙</b>(終わりの構え + 戻り。プレイヤーの反撃の番)。
 */
public enum ActionPhase {
    /** 前触れ。避ける・盾を構える猶予。 */
    WINDUP,
    /** 実行。当たりが出ている間。 */
    EXECUTE,
    /** 隙。動けない間(反撃の番)。 */
    RECOVER,
    /** 終わった。 */
    DONE
}
