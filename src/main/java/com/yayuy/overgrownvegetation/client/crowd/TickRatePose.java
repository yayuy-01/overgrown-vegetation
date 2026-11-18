package com.yayuy.overgrownvegetation.client.crowd;

import com.yayuy.overgrownvegetation.client.LodGeoModel;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.constant.dataticket.DataTicket;

import java.util.Collection;

/**
 * アニメを 1 tick に 1 回だけ計算し、フレームの間は骨の値(回転・位置・大きさ)を直線で補間する(2026-11-18 大群の軽量化 C の案 2。
 * 11-18 ユーザー了承「撮って見せて」)。
 *
 * <p>GeckoLib はふだん、フレームごと・1 体ごとにキーフレームを引き直す(グレガリア 1 体 約 20 µs)。ここでは個体ごとに
 * 「tick k の姿勢」と「tick k+1 の姿勢」を覚え、フレームでは部分 tick で混ぜるだけにする。計算は tick に 1 回(+ 混ぜる手間)なので、
 * 60 FPS ならアニメの重さが約 1/3、120 FPS なら約 1/6。20 FPS を切るとほとんど効かない(毎フレーム新しい tick になる)。</p>
 *
 * <p>見た目の違い: キーの間の曲線(catmullrom など)が 1/20 秒ごとの直線つなぎになる。アニメの切り替え(歩き → 走り)は最大 1 tick 早く始まる。
 * GeckoLib には決まった時刻(tick k, k+1)だけを順に見せるので、補間・切り替えの状態は崩れない。</p>
 *
 * <p>姿勢は GeckoLib の個体ごとのデータ({@link AnimatableManager#setData})に置く(個体が消えれば一緒に消える)。</p>
 */
public final class TickRatePose {

    /** 確かめ用の切り替え(devbridge の {@code crowd tickrate off|on})。 */
    public static volatile boolean enabled = true;

    private static final DataTicket<Cache> TICKET = new DataTicket<>("overgrown_vegetation_tick_pose", Cache.class);
    private static final int PER_BONE = 9;

    private TickRatePose() {
    }

    /**
     * この個体に使うか。カメラから AI の段階化の nearBlocks(perf.json【仮】24)より近い個体は、今まで通りフレームごとに計算する
     * (11-18 の撮り比べ: 走り(1 巡 5 tick)は tick ごとの直線つなぎだと脚の弧がずれて、近くでは見分けがつく)。
     */
    public static boolean applies(Entity animatable) {
        if (!enabled) return false;
        double near = com.yayuy.overgrownvegetation.data.DesignData.perf().nearBlocks;
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceToSqr(animatable.position()) > near * near;
    }

    public static <T extends Entity & GeoAnimatable> void handle(LodGeoModel<T> model, T animatable, long instanceId,
                                                                 AnimationState<T> state, float partialTick) {
        Collection<GeoBone> bones = model.getAnimationProcessor().getRegisteredBones();
        if (bones.isEmpty() || Minecraft.getInstance().isPaused()) {
            model.handleAnimationsNow(animatable, instanceId, state, partialTick);
            return;
        }
        AnimatableManager<T> manager = animatable.getAnimatableInstanceCache().getManagerForId(instanceId);
        Double tickData = state.getData(DataTickets.TICK);
        double tick = tickData != null ? tickData : animatable.tickCount;
        long k = (long) Math.floor(tick);
        float f = (float) (tick - k) + partialTick;
        if (f >= 1.0F) {
            k += (long) Math.floor(f);
            f -= (float) Math.floor(f);
        }

        Cache c = manager.getData(TICKET);
        int size = bones.size() * PER_BONE;
        if (c == null || c.prev.length != size) {
            c = new Cache(size);
            manager.setData(TICKET, c);
        }
        if (c.nextTick == k) {
            float[] t = c.prev;
            c.prev = c.next;
            c.next = t;
            evaluate(model, animatable, instanceId, state, k + 1, bones, c.next);
            c.nextTick = k + 1;
        } else if (c.nextTick != k + 1) {
            // 初めて・しばらく描かれなかった・フレームが tick を飛ばした: 計算は 1 回だけにして、この tick の間は次の姿勢のまま
            // (2 回計算すると 20 FPS を切る場面で前より重くなる)
            evaluate(model, animatable, instanceId, state, k + 1, bones, c.next);
            System.arraycopy(c.next, 0, c.prev, 0, c.next.length);
            c.nextTick = k + 1;
        }
        apply(bones, c.prev, c.next, f);
        if (tickData != null) state.setData(DataTickets.TICK, tickData);
    }

    /** GeckoLib に tick {@code t} ちょうどの姿勢を計算させ、骨の値を {@code out} へ写す。 */
    private static <T extends Entity & GeoAnimatable> void evaluate(LodGeoModel<T> model, T animatable, long instanceId, AnimationState<T> state,
                                                                    long t, Collection<GeoBone> bones, float[] out) {
        state.setData(DataTickets.TICK, (double) t);
        model.handleAnimationsNow(animatable, instanceId, state, 0.0F);
        int i = 0;
        for (GeoBone b : bones) {
            out[i] = b.getRotX();
            out[i + 1] = b.getRotY();
            out[i + 2] = b.getRotZ();
            out[i + 3] = b.getPosX();
            out[i + 4] = b.getPosY();
            out[i + 5] = b.getPosZ();
            out[i + 6] = b.getScaleX();
            out[i + 7] = b.getScaleY();
            out[i + 8] = b.getScaleZ();
            i += PER_BONE;
        }
    }

    private static void apply(Collection<GeoBone> bones, float[] a, float[] b, float f) {
        int i = 0;
        for (GeoBone bone : bones) {
            bone.updateRotation(a[i] + (b[i] - a[i]) * f, a[i + 1] + (b[i + 1] - a[i + 1]) * f, a[i + 2] + (b[i + 2] - a[i + 2]) * f);
            bone.updatePosition(a[i + 3] + (b[i + 3] - a[i + 3]) * f, a[i + 4] + (b[i + 4] - a[i + 4]) * f, a[i + 5] + (b[i + 5] - a[i + 5]) * f);
            bone.updateScale(a[i + 6] + (b[i + 6] - a[i + 6]) * f, a[i + 7] + (b[i + 7] - a[i + 7]) * f, a[i + 8] + (b[i + 8] - a[i + 8]) * f);
            i += PER_BONE;
        }
    }

    /** 1 体ぶんの 2 つの姿勢(骨 × 9 の値)と、next が何 tick の姿勢か。 */
    static final class Cache {
        float[] prev;
        float[] next;
        long nextTick = Long.MIN_VALUE;

        Cache(int size) {
            this.prev = new float[size];
            this.next = new float[size];
        }
    }
}
