package com.yayuy.overgrownvegetation.corpse;

/**
 * 死体ブロックから起き上がる G1 の感染体(2026-10-09、ユーザー指定「死体ブロックが Mob になるときは死体が起き上がるアニメ」)。
 *
 * <p>起き上がっている間({@link #RISE_TICKS})は動かない(AI を止める)。見た目は GeckoLib のアニメ {@code rise}
 * (生成器 {@code tools/g5-models/mobs/G1.java} が死体の姿勢との差から作る。長さ {@code G1.RISE} = 2.6 秒)。
 * アニメは切り替えの補間 0 で始める(補間があると死体の姿勢から始まらず、立ち姿へ一瞬戻って見える)。
 */
public interface RisingMob {

    /** 起き上がりの長さ(tick)。生成器の {@code G1.RISE}(2.6 秒)と同じ。 */
    int RISE_TICKS = 52; // 2026-11-08: 種ごとの長さは G1Species#riseTicks(村人 3.4 秒・クリーパー 2.85 秒)。これは旧の共通の長さ

    /** 起き上がりを始める(サーバー側で、湧かせた直後に呼ぶ)。 */
    void startRising(int ticks);

    /** 起き上がりの途中か(両側で読める。同期データ)。 */
    boolean isRising();
}
