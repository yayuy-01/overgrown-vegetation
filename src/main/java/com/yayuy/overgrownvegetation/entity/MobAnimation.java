package com.yayuy.overgrownvegetation.entity;

import software.bernie.geckolib.animation.AnimationState;

/**
 * GeckoLib のアニメーション切り替えで使う「いま歩いているか / 走っているか」の判定。
 *
 * <h2>{@code AnimationState#isMoving()} を使ってはいけない(バグ修正 2026-09-16)</h2>
 * <p>症状: <b>歩いている途中でアニメーションが止まり、体が固定されたまま水平に滑っていく</b>。
 * しばらくすると戻り、また止まる。
 *
 * <p>原因は GeckoLib 側の判定式にある。{@code GeoEntityRenderer#actuallyRender} は
 * <pre>{@code
 * Vec3 velocity   = animatable.getDeltaMovement();
 * float avgVelocity = (|velocity.x| + |velocity.z|) / 2;
 * isMoving = avgVelocity >= getMotionAnimThreshold(animatable)   // 既定 0.015
 *            && limbSwingAmount != 0;
 * }</pre>
 * と書いていて、<b>{@code getDeltaMovement()} を見ている</b>。ところがこれは
 * <b>クライアント側では当てにならない</b> ——
 * <ul>
 *     <li>Mob の速度がクライアントへ送られるのは<b>ノックバックを受けたときだけ</b>
 *     ({@code ServerEntity} が {@code hurtMarked} のときしか
 *     {@code ClientboundSetEntityMotionPacket} を投げない)。ふだんの歩行では一度も飛ばない。</li>
 *     <li>クライアントでも {@code LivingEntity#aiStep} → {@code travel} は毎 tick 走るが、
 *     AI の入力({@code xxa}/{@code zza})はサーバーにしか無いので推進力はゼロ。
 *     残っている速度に摩擦(地上で約 0.91)が掛かり続けるだけなので、
 *     <b>1〜2 秒で 0.015 を下回る</b>。</li>
 *     <li>見た目が動いているのは速度ではなく<b>サーバー座標への補間</b>
 *     ({@code lerpPositionAndRotationStep})のぶん。</li>
 * </ul>
 * つまり「実際には歩いているのに {@code isMoving()} が false」になり、待機アニメーションへ
 * 切り替わったまま補間で滑っていく。押し合い({@code pushEntities})や段差の昇り降りで
 * 速度が跳ねた tick だけ true に戻るので、<b>直ったり止まったりを繰り返す</b>。
 *
 * <h2>代わりに見るもの</h2>
 * <p>{@code limbSwingAmount}({@link AnimationState#getLimbSwingAmount()})。これは
 * {@code LivingEntity#calculateEntityAnimation} が<b>実際の座標の変化量</b>
 * ({@code getX() - xo})から毎 tick 更新するので、補間で動いているクライアント側でも正しい
 * —— バニラのモデルが手足を振るのに使っているのもこの値。
 *
 * <p>値は「1 tick あたりの移動距離 × 4」が上限 1 で頭打ちになったものへ収束する。
 * 目安: 歩き(0.1/tick)で約 0.4、走り(0.2/tick)で約 0.8。
 */
public final class MobAnimation {

    /**
     * これ以上なら「歩いている」。{@code GeoEntityRenderer} が使っていたのと同じ 0.015。
     * 止まると 1 tick ごとに 0.6 倍で落ちるので、立ち止まってから約 6 tick で待機へ戻る。
     */
    private static final float WALKING = 0.015F;

    /** {@code limbSwingAmount} は「1 tick あたりの移動距離 × {@value}」(上限 1)に収束する。 */
    private static final float SWING_PER_BLOCK = 4.0F;

    /** 歩行/遊泳アニメーションを流すべきか。 */
    public static boolean isWalking(AnimationState<?> state) {
        return state.getLimbSwingAmount() >= WALKING;
    }

    /**
     * 1 tick あたり {@code blocksPerTick} ブロックより速く動いているか
     * (歩き / 走りの切り替えに使う。{@link #isWalking} が true のときだけ意味がある)。
     *
     * <p>速さの物差しをブロック/tick のままにしてあるのは、呼ぶ側の閾値
     * (種ごとに 0.10〜0.16)を書き換えずに済ませるため。ここで
     * {@code limbSwingAmount} の尺度({@value #SWING_PER_BLOCK} 倍・上限 1)へ直す。
     *
     * <p>以前は呼ぶ側が {@code getDeltaMovement().horizontalDistance()} を直接見ていたが、
     * それはクライアントでは<b>ほぼ永久に 0</b>(クラス javadoc 参照)なので、
     * 走りのアニメーションはそもそも一度も流れていなかった。
     */
    public static boolean isFasterThan(AnimationState<?> state, double blocksPerTick) {
        float threshold = (float) Math.min(blocksPerTick * SWING_PER_BLOCK, 1.0D);
        return state.getLimbSwingAmount() >= threshold;
    }

    /**
     * 歩きの再生速度 = 実際に 1 tick に進んだ水平の量 ÷ (アニメが足を滑らせない速さ × 体格)(2026-10-10、獲物の環境生物のモデル作り直しで足した)。
     * 体格(SCALE)が大きいほど歩幅も大きいので体格を掛ける。{@code limbSwingAmount} は 0.25 ブロック/tick で頭打ちなので位置の差で見る。
     * 速さを毎フレーム変えるので、コントローラは {@link PhaseKeepingAnimationController}(位相が巻き戻らない)と組にする。
     * 下限は 0.3(初めは 0.5。体格 2 倍にしたイアンタサウルスの散歩が 0.42 前後になり、0.5 で止めると足が 2 割前へ滑ったため。2026-10-10)。
     *
     * @param refPerScale 体格 1 でアニメの足が滑らない速さ(ブロック/tick)
     */
    public static double walkSpeedFor(net.minecraft.world.entity.LivingEntity entity, double refPerScale) {
        double dx = entity.getX() - entity.xo;
        double dz = entity.getZ() - entity.zo;
        double moved = Math.sqrt(dx * dx + dz * dz);
        return net.minecraft.util.Mth.clamp(moved / (refPerScale * entity.getScale()), 0.3D, 2.5D);
    }

    private MobAnimation() {
    }
}
