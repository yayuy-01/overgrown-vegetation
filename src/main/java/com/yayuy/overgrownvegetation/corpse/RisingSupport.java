package com.yayuy.overgrownvegetation.corpse;

import com.yayuy.overgrownvegetation.entity.MobAnimation;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Mob;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * G1 の感染体が死体から起き上がる仕組みの共通部品(2026-10-09)。各クラスは同期データ RISING(残り tick)を持ち、
 * {@link RisingMob} を実装する(バニラの別々のクラスを継いでいるので、データの定義だけは各クラスに置く)。
 */
public final class RisingSupport {

    private RisingSupport() {
    }

    /** 死体から起き上がるアニメ(生成器 {@code tools/g5-models/mobs/G1.java} の rise)。最後の姿勢で止め、次のアニメへは補間で移る。 */
    public static final RawAnimation RISE_ANIM = RawAnimation.begin().thenPlayAndHold("rise");

    /**
     * 起き上がりの正確な残り(サーバーだけ。Mob → 残り)。同期データ RISING は起き上がりの間は始めの値のまま、終わったら 0 にする
     * —— クライアントは「起き上がり中か」しか見ないので見た目は同じで、前のように残りを毎 tick 送らない(2026-10-10、0.1.9 の前の軽量化)。
     * 弱参照なので、消えた Mob の分は残らない。
     */
    private static final Map<Mob, int[]> LEFT = new WeakHashMap<>();

    /** 起き上がりの残りを 1 tick 減らす(サーバーだけ)。終わった tick に RISING を 0 にして送る。 */
    public static void tick(Mob mob, EntityDataAccessor<Integer> rising) {
        if (mob.level().isClientSide()) {
            return;
        }
        int synced = mob.getEntityData().get(rising);
        if (synced <= 0) {
            LEFT.remove(mob);
            return;
        }
        int[] left = LEFT.computeIfAbsent(mob, m -> new int[]{synced});
        if (--left[0] <= 0) {
            LEFT.remove(mob);
            mob.getEntityData().set(rising, 0);
        }
    }

    /** 起き上がりの正確な残り(セーブ用。数え始める前なら RISING の値)。 */
    public static int remaining(Mob mob, EntityDataAccessor<Integer> rising) {
        int[] left = LEFT.get(mob);
        return left != null ? left[0] : mob.getEntityData().get(rising);
    }

    /**
     * 待機・歩きのコントローラ(名前 "move")に起き上がりを足したもの。起き上がりの間は切り替えの補間 0 で rise
     * (補間があると死体の姿勢から始まらず、立ち姿へ一瞬戻って見える)。終わったら補間 5 で待機・歩きへ。
     */
    public static <T extends Mob & GeoAnimatable & RisingMob> AnimationController<T> moveController(T mob, RawAnimation idle, RawAnimation walk) {
        return new AnimationController<>(mob, "move", 5, state -> {
            if (mob.isRising()) {
                state.getController().transitionLength(0);
                return state.setAndContinue(RISE_ANIM);
            }
            state.getController().transitionLength(5);
            return state.setAndContinue(MobAnimation.isWalking(state) ? walk : idle);
        });
    }
}
