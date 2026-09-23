package com.yayuy.overgrownvegetation.phase;

/**
 * フェーズ4以降に自然湧きしうる「進化した感染 Mob」であることを示すマーカー。
 *
 * <p>現時点では見た目・AI ともにバニラ Mob をそのまま流用した仮実装で、寄生に関する本格的な
 * システム(寄生の進行・治療・能力など)は後日実装される。このインターフェースは
 * 「感染/寄生された Mob かどうか」を種族に関わらず一括で判定したい箇所のために存在する
 * (フェーズポイントの判定は 2026-09-13 の再編でグループタグ({@link MobGroup})ベースの
 * {@link MobGroupPoints} に移ったので、ここでは使われていない)。
 */
public interface ParasitizedMob {
}
