package com.yayuy.overgrownvegetation.client.critter;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 1 体ごとの「通ってきた道」と動きのなまり(2026-10-22、G2・G3 の動きの層)。描画のスレッドだけで触る。
 *
 * <p>体の向き・高さを、歩いた距離をキーにして輪の配列へ残す。尾・体節は「自分のいる距離だけ後ろの道の向き」を向けば
 * 進路をなぞって曲がる(ムカデの体節も、トカゲの尾も同じ仕組み)。止まって向きだけ変えたときは、古い向きを少しずつ
 * 今の向きへ寄せて、体がまっすぐに戻る。
 *
 * <p>更新は描画のときに、エンティティの tick が進んだぶんだけ行う(tick のイベントを使わない。見えていない個体は計算しない)。
 * 1 秒以上描かれなかった個体は道を捨てて今の姿勢から始め直す。
 */
final class CritterTrail {

    /** 残す点の数。点は最低 {@link #SPACING} ブロックおきなので、6 ブロックぶん残る(アースロプレウラの最大の体 5 ブロックより長い)。 */
    private static final int N = 100;
    private static final float SPACING = 0.06F;

    private static final Map<LivingEntity, CritterTrail> TRAILS = new WeakHashMap<>();

    static CritterTrail of(LivingEntity e) {
        CritterTrail t = TRAILS.get(e);
        if (t == null) {
            t = new CritterTrail();
            TRAILS.put(e, t);
        }
        t.update(e);
        return t;
    }

    private final float[] dist = new float[N];
    private final float[] yaw = new float[N];
    private final float[] ys = new float[N];
    private int head = -1;
    private int count;
    private int lastTick = Integer.MIN_VALUE;
    private double lastX;
    private double lastY;
    private double lastZ;
    private float lastYaw;
    /** 歩いた距離の合計(ブロック)。tick の値と 1 つ前の値(描画で補間する)。 */
    float total;
    float totalO;
    /** 向きの変わる速さ(度/tick、+ = 右へ曲がる)をならした値。 */
    float yawRate;
    float yawRateO;
    /** 上下の速さ(ブロック/tick)をならした値。 */
    float vy;
    float vyO;
    /** 水平の速さ(ブロック/tick)をならした値。 */
    float speed;
    float speedO;
    /** 触角・ひげのばね(左右と上下)。 */
    float springYaw;
    float springYawO;
    private float springYawV;
    float springPitch;
    float springPitchO;
    private float springPitchV;
    /** 地面の高さ(TerrainProbe)。点ごとに 1 つ前の tick と今の値。 */
    final float[] hPrev = new float[32];
    final float[] hCur = new float[32];
    int probeTick = Integer.MIN_VALUE;
    int probeN = -1;

    private void update(LivingEntity e) {
        int tick = e.tickCount;
        if (tick == lastTick) {
            return;
        }
        if (head < 0 || tick < lastTick || tick - lastTick > 20) {
            reset(e);
            lastTick = tick;
            return;
        }
        int steps = Math.min(tick - lastTick, 5);
        lastTick = tick;
        for (int i = 0; i < steps; i++) {
            step(e);
        }
    }

    private void reset(LivingEntity e) {
        head = 0;
        count = 1;
        total = totalO = 0.0F;
        dist[0] = 0.0F;
        yaw[0] = e.yBodyRot;
        ys[0] = (float) e.getY();
        lastX = e.getX();
        lastY = e.getY();
        lastZ = e.getZ();
        lastYaw = e.yBodyRot;
        yawRate = yawRateO = vy = vyO = speed = speedO = 0.0F;
        springYaw = springYawO = springYawV = springPitch = springPitchO = springPitchV = 0.0F;
    }

    private void step(LivingEntity e) {
        double dx = e.getX() - lastX;
        double dz = e.getZ() - lastZ;
        float moved = (float) Math.sqrt(dx * dx + dz * dz);
        if (moved > 2.0F) {
            moved = 0.0F; // テレポート
        }
        float dyaw = Mth.wrapDegrees(e.yBodyRot - lastYaw);
        float dy = (float) (e.getY() - lastY);
        lastX = e.getX();
        lastY = e.getY();
        lastZ = e.getZ();
        lastYaw = e.yBodyRot;

        totalO = total;
        total += moved;
        yawRateO = yawRate;
        yawRate += (dyaw - yawRate) * 0.3F;
        vyO = vy;
        vy += (Mth.clamp(dy, -1.0F, 1.0F) - vy) * 0.3F;
        speedO = speed;
        speed += (moved - speed) * 0.3F;

        // 触角: 曲がると外へ遅れ、動き出す・上り下りで上下に揺れる(減衰するばね)
        springYawO = springYaw;
        springYawV = (springYawV + (-yawRate * 0.06F - springYaw) * 0.25F) * 0.72F;
        springYaw += springYawV;
        springPitchO = springPitch;
        springPitchV = (springPitchV + (vy * 2.5F + speed * 1.2F - springPitch) * 0.25F) * 0.72F;
        springPitch += springPitchV;

        // 道の点: 前の点から SPACING 以上進んだら新しい点、そうでなければ先頭の点を今の姿勢で書き直す
        float cur = e.yBodyRot;
        float y = (float) e.getY();
        if (total - dist[head] >= SPACING) {
            head = (head + 1) % N;
            count = Math.min(count + 1, N);
        }
        dist[head] = total;
        yaw[head] = cur;
        ys[head] = y;

        // 止まって向きを変えたら体はまっすぐへ戻る(動いているときも少しだけ寄せて、曲がりっぱなしにしない)
        float relax = moved < 0.01F ? 0.08F : 0.015F;
        for (int k = 1, i = head; k < count; k++) {
            i = (i - 1 + N) % N;
            yaw[i] += Mth.wrapDegrees(cur - yaw[i]) * relax;
        }
    }

    /** 今(描画の時点)の歩いた距離。 */
    float distNow(float partial) {
        return Mth.lerp(partial, totalO, total);
    }

    /** 今から back ブロック後ろの道の向き(度)。道が足りなければいちばん古い点。 */
    float yawBack(float now, float back) {
        return sample(now - back, yaw, true);
    }

    /** 今から back ブロック後ろの道の高さ。 */
    float yBack(float now, float back) {
        return sample(now - back, ys, false);
    }

    private float sample(float target, float[] v, boolean angle) {
        int i = head;
        int newer = -1;
        for (int k = 0; k < count; k++) {
            if (dist[i] <= target) {
                if (newer < 0) {
                    return v[i];
                }
                float span = dist[newer] - dist[i];
                float f = span <= 1.0E-4F ? 0.0F : (target - dist[i]) / span;
                return angle ? v[i] + Mth.wrapDegrees(v[newer] - v[i]) * f : Mth.lerp(f, v[i], v[newer]);
            }
            newer = i;
            i = (i - 1 + N) % N;
        }
        return v[newer < 0 ? head : newer];
    }
}
