package com.yayuy.overgrownvegetation.entity.cling;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 張り付いている体の<b>見た目の向き</b>(クライアント、2026-11-05)。体の上向き = 張り付いている面の向き(天井では逆さま、
 * 11-04 ユーザー決定)、前 = 面の上で進む向き。角で面が替わったら <b>3〜4 tick かけてなめらかに</b>回す(毎 tick 残りの 45% ずつ寄せる。
 * 4 tick で 9 割)。当たり判定は回さない(マイクラの箱はいつも軸に沿う)—— ここは描画だけ。
 *
 * <p>床の上で上向きが落ち着いている間は {@link #upright} が真で、描画はバニラの体の向き({@code yBodyRot})のままにする。
 */
public final class ClingPose {

    /** 1 tick に残りのどれだけ寄せるか。0.45 → 3 tick で 83%・4 tick で 91%。 */
    private static final float RATE = 0.45F;

    private final Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F);
    private final Vector3f prevUp = new Vector3f(0.0F, 1.0F, 0.0F);
    private final Vector3f fwd = new Vector3f(0.0F, 0.0F, 1.0F);
    private final Vector3f prevFwd = new Vector3f(0.0F, 0.0F, 1.0F);
    private boolean floorish = true;
    private boolean prevFloorish = true;

    /**
     * クライアントの毎 tick。
     *
     * @param anchor     同期された面({@link SurfaceNode#anchor}・{@link SurfaceNode#FOLIAGE}・{@link SurfaceNode#NONE})
     * @param forward    同期された前の向き(壁・天井のとき使う)
     * @param bodyYawDeg 体の向き(床・宙・葉の中のとき使う)
     */
    public void tick(int anchor, Vec3 forward, float bodyYawDeg) {
        prevUp.set(up);
        prevFwd.set(fwd);
        prevFloorish = floorish;
        Direction face = anchor < 6 ? Direction.from3DDataValue(anchor) : null;
        boolean onWall = face != null && face != Direction.DOWN;
        floorish = !onWall;
        Vector3f tUp = onWall
                ? new Vector3f(-face.getStepX(), -face.getStepY(), -face.getStepZ())
                : new Vector3f(0.0F, 1.0F, 0.0F);
        float yaw = bodyYawDeg * Mth.DEG_TO_RAD;
        Vector3f yawFwd = new Vector3f(-Mth.sin(yaw), 0.0F, Mth.cos(yaw));
        Vector3f tFwd = onWall ? new Vector3f((float) forward.x, (float) forward.y, (float) forward.z) : yawFwd;
        // 真逆へは回せないので、前の向きへ少し寄せた所を経由する(天井から落ちたときなど)。
        if (up.dot(tUp) < -0.5F) {
            tUp.add(new Vector3f(fwd).mul(0.6F)).normalize();
        }
        lerpTo(up, tUp, RATE);
        boolean settled = !onWall && up.y > 0.9995F;
        if (settled) {
            up.set(0.0F, 1.0F, 0.0F);
            fwd.set(yawFwd);
        } else {
            lerpTo(fwd, tFwd, RATE);
        }
        orthonormalize(fwd, up, yawFwd);
    }

    /** 床に立って上向きが落ち着いている(描画はバニラの向きのまま)。 */
    public boolean upright(float partialTick) {
        return floorish && prevFloorish && up.y > 0.9995F && prevUp.y > 0.9995F;
    }

    /**
     * 体の回転(モデルの +Y → 上向き、モデルの −Z → 前)。GeckoLib の体はバニラと同じく −Z を前に作ってある
     * ({@code GeoEntityRenderer#applyRotations} の {@code YP(180 − yaw)} と同じ向きになる)。
     */
    public Quaternionf rotation(float partialTick, Quaternionf out) {
        Vector3f u = new Vector3f(prevUp).lerp(up, partialTick);
        if (u.lengthSquared() < 1.0E-6F) {
            u.set(up);
        }
        u.normalize();
        Vector3f f = new Vector3f(prevFwd).lerp(fwd, partialTick);
        orthonormalize(f, u, new Vector3f(fwd));
        Vector3f r = new Vector3f(f).cross(u); // f × n(床で前 +Z なら −X。バニラの 180° 回しと同じ)
        Matrix3f m = new Matrix3f(r, u, new Vector3f(f).negate());
        return out.setFromNormalized(m);
    }

    private static void lerpTo(Vector3f v, Vector3f target, float t) {
        v.lerp(target, t);
        if (v.lengthSquared() < 1.0E-6F) {
            v.set(target);
        }
        v.normalize();
    }

    /** {@code f} を {@code n} に直交する単位ベクトルへ。潰れたら {@code fallback} から作る。 */
    private static void orthonormalize(Vector3f f, Vector3f n, Vector3f fallback) {
        f.sub(new Vector3f(n).mul(f.dot(n)));
        if (f.lengthSquared() < 1.0E-5F) {
            f.set(fallback).sub(new Vector3f(n).mul(fallback.dot(n)));
            if (f.lengthSquared() < 1.0E-5F) {
                f.set(Math.abs(n.y) < 0.9F ? new Vector3f(0.0F, 1.0F, 0.0F) : new Vector3f(0.0F, 0.0F, 1.0F));
                f.sub(new Vector3f(n).mul(f.dot(n)));
            }
        }
        f.normalize();
    }
}
