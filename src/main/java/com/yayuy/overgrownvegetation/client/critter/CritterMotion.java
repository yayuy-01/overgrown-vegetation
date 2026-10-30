package com.yayuy.overgrownvegetation.client.critter;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * G2・G3 の全種が乗る「動きの層」(2026-10-22、ユーザーと決めた直しの表の「全種共通」)。
 * キーのアニメの<b>上に</b>手続きの動きを足す: 注視(頭が相手を向く)・坂で前後に傾く・曲がると内へ傾く・
 * 尾と体節が通ってきた道をなぞる・触角が遅れて揺れる・多脚の歩きの波({@link LegSet})・待機の小さな揺れ。
 *
 * <h2>呼ぶ場所</h2>
 * <p>{@code GeoModel#setCustomAnimations}(キーの補間の後)から {@link #apply} を呼ぶ。足し算なので、キーで
 * 動かしている骨にもそのまま重なる。
 *
 * <h2>骨に書いた印を消す</h2>
 * <p>GeckoLib は「この tick にキーで動かなかった骨」を前の値から静止へ戻すが、その判定は骨の「変わった」印で行う。
 * ここで角度を書くと印が立ったまま次のフレームへ残り、戻す処理が飛ばされて足した角度が毎フレーム積み上がる。
 * だから書き終えた骨は {@link GeoBone#resetStateChanges()} で印を下ろす(GeckoLib も tick の終わりに全骨で同じことをしている)。
 */
public final class CritterMotion {

    private static final float RAD = Mth.DEG_TO_RAD;
    private static final float[] M = new float[9];

    private CritterMotion() {
    }

    public static <T extends LivingEntity & GeoAnimatable> void apply(GeoModel<T> model, T e, AnimationState<T> state, CritterRig rig) {
        apply(model, e, state, rig, true);
    }

    /**
     * @param grounded 地面を歩いているか。false(木・壁を登る、天井にぶら下がる、滑空する)のときは地面に沿わせる計算と
     *                 Java の脚を止める(その姿勢はキーのアニメが受け持つ)
     */
    public static <T extends LivingEntity & GeoAnimatable> void apply(GeoModel<T> model, T e, AnimationState<T> state, CritterRig rig,
                                                                      boolean grounded) {
        float pt = state.getPartialTick();
        CritterTrail trail = CritterTrail.of(e);
        float scale = Math.max(e.getScale(), 0.1F);
        float now = trail.distNow(pt);
        float time = e.tickCount + pt;

        // ---- 注視: 頭と体の向きの差・頭の上下を、首と頭で分け合う
        if (rig.lookBones.length > 0) {
            float headYaw = Mth.rotLerp(pt, e.yHeadRotO, e.yHeadRot);
            float bodyYaw = Mth.rotLerp(pt, e.yBodyRotO, e.yBodyRot);
            float yaw = Mth.clamp(Mth.wrapDegrees(headYaw - bodyYaw), -rig.lookMaxYaw, rig.lookMaxYaw);
            float pitch = Mth.clamp(Mth.lerp(pt, e.xRotO, e.getXRot()), -rig.lookMaxPitch, rig.lookMaxPitch);
            for (int i = 0; i < rig.lookBones.length; i++) {
                GeoBone b = bone(model, rig.lookBones[i]);
                if (b != null) {
                    // 右を見る(yaw +)= 前が右へ = 中の Y − / 下を見る(pitch +)= 前が下がる = 中の X −
                    BoneMath.add(b, -pitch * rig.lookShare[i] * RAD, -yaw * rig.lookShare[i] * RAD, 0.0F);
                    done(b);
                }
            }
        }

        // ---- 地を歩く種: 体と鎖の真下の地面を測る(段差・坂で地面に沿う。TerrainProbe)
        float[] th = null;
        if (rig.terrain && grounded && rig.pitchBone != null) {
            float[] pz = probeZ(model, rig);
            if (pz != null) {
                TerrainProbe.update(e, trail, pz, scale);
                th = new float[pz.length];
                float y = (float) Mth.lerp(pt, e.yo, e.getY());
                for (int i = 0; i < pz.length; i++) {
                    th[i] = Mth.clamp(Mth.lerp(pt, trail.hPrev[i], trail.hCur[i]) - y, -TerrainProbe.DOWN, TerrainProbe.UP);
                }
            }
        }

        // ---- 体の前後の傾き: 地を歩く種は真下の地面(無ければ道の坂)、飛ぶ・泳ぐ種は上下の速さ
        float bodyPitch = 0.0F;
        if (th != null) {
            float len = rig.slopeLen * scale;
            bodyPitch = Mth.clamp((float) Math.toDegrees(Math.atan2(th[0] - th[1], len)) * rig.slopeScale, -rig.maxSlope, rig.maxSlope);
            // 体の付け根を、その真下の地面の高さへ持ち上げる・下ろす(位置の単位はモデルの 1/16 ブロック。描画で体格が掛かる)
            GeoBone b = bone(model, rig.pitchBone);
            if (b != null && th[0] != 0.0F) {
                b.setPosY(b.getPosY() + th[0] * 16.0F / scale);
                done(b);
            }
        } else if (rig.climbPitch != 0.0F) {
            bodyPitch = Mth.clamp(Mth.lerp(pt, trail.vyO, trail.vy) * rig.climbPitch, -rig.maxClimb, rig.maxClimb);
        } else if (rig.pitchBone != null) {
            float len = rig.slopeLen * scale;
            float dy = trail.yBack(now, 0.0F) - trail.yBack(now, len);
            bodyPitch = Mth.clamp((float) Math.toDegrees(Math.atan2(dy, len)) * rig.slopeScale, -rig.maxSlope, rig.maxSlope);
        }
        if (rig.pitchBone != null && bodyPitch != 0.0F) {
            GeoBone b = bone(model, rig.pitchBone);
            if (b != null) {
                BoneMath.add(b, bodyPitch * RAD, 0.0F, 0.0F);
                done(b);
            }
        }

        // ---- 曲がると内へ傾く(右へ曲がる = 右が下がる = 左が上がる = Z −)
        if (rig.rollBone != null) {
            float rate = Mth.lerp(pt, trail.yawRateO, trail.yawRate);
            float roll = Mth.clamp(-rate * rig.rollPerRate, -rig.maxRoll, rig.maxRoll);
            GeoBone b = bone(model, rig.rollBone);
            if (b != null && roll != 0.0F) {
                BoneMath.add(b, 0.0F, 0.0F, roll * RAD);
                done(b);
            }
        }

        // ---- 尾・体節: 自分のいる距離だけ後ろの道の向きを向く
        if (!rig.chains.isEmpty()) {
            float bodyYaw = Mth.rotLerp(pt, e.yBodyRotO, e.yBodyRot);
            for (CritterRig.Chain c : rig.chains) {
                applyChain(model, c, trail, now, scale, bodyYaw, bodyPitch, rig.originZ, th, chainBase(rig, c), rig.probeZ);
            }
        }

        // ---- 触角・ひげ: 遅れて揺れるばね + 小さな探り
        if (rig.antennae.length > 0) {
            float sy = Mth.lerp(pt, trail.springYawO, trail.springYaw);
            float sp = Mth.lerp(pt, trail.springPitchO, trail.springPitch);
            for (int i = 0; i < rig.antennae.length; i++) {
                GeoBone b = bone(model, rig.antennae[i]);
                if (b == null) {
                    continue;
                }
                float side = (i % 2 == 0) ? 1.0F : -1.0F;
                float probe = Mth.sin(time * 0.11F + i * 1.7F) * 4.0F + Mth.sin(time * 0.29F + i) * 2.0F;
                BoneMath.add(b, (sp + probe * 0.5F) * rig.antennaScale * RAD, (sy + probe * side) * rig.antennaScale * RAD, 0.0F);
                done(b);
            }
        }

        // ---- 多脚の歩き
        if (rig.legs != null && grounded) {
            applyLegs(model, rig.legs, state.getLimbSwingAmount(), now, scale);
        }

        // ---- 待機の揺れ(歩くと弱まる)
        if (!rig.sways.isEmpty()) {
            float calm = 1.0F - Mth.clamp(state.getLimbSwingAmount() * 6.0F, 0.0F, 0.7F);
            for (CritterRig.Sway s : rig.sways) {
                GeoBone b = bone(model, s.bone());
                if (b == null) {
                    continue;
                }
                float v = Mth.sin((time / 20.0F / s.period() + s.phase()) * Mth.TWO_PI) * s.amp() * calm * RAD;
                BoneMath.add(b, s.axis() == 0 ? v : 0.0F, s.axis() == 1 ? v : 0.0F, s.axis() == 2 ? v : 0.0F);
                done(b);
            }
        }
    }

    /** 水平の速さ(ブロック/tick、ならした値)。種ごとの手続き(メガネウラの脚のたたみなど)に使う。 */
    public static float speed(LivingEntity e, float partialTick) {
        CritterTrail t = CritterTrail.of(e);
        return Mth.lerp(partialTick, t.speedO, t.speed);
    }

    /** terrain の測る点: [0] 体の付け根、[1] そこから slopeLen 後ろ、続けて鎖ごとに各骨の付け根と最後の骨の先。 */
    private static float[] probeZ(GeoModel<?> model, CritterRig rig) {
        if (rig.probeZ != null) {
            return rig.probeZ;
        }
        GeoBone body = bone(model, rig.pitchBone);
        if (body == null) {
            return null;
        }
        java.util.List<Float> z = new java.util.ArrayList<>();
        z.add(body.getPivotZ());
        z.add(body.getPivotZ() + rig.slopeLen * 16.0F);
        for (CritterRig.Chain c : rig.chains) {
            float last = 0.0F;
            final float lastLen = 4.0F; // 最後の骨の先 = 0.25 ブロック後ろ(applyChain の最後の節の長さと同じ)
            for (String name : c.bones()) {
                GeoBone b = bone(model, name);
                if (b == null) {
                    return null;
                }
                z.add(b.getPivotZ());
                last = b.getPivotZ();
            }
            z.add(last + lastLen);
        }
        float[] out = new float[Math.min(z.size(), 32)];
        for (int i = 0; i < out.length; i++) {
            out[i] = z.get(i);
        }
        rig.probeZ = out;
        return out;
    }

    /** 鎖 c の最初の骨が probeZ の何番目か。 */
    private static int chainBase(CritterRig rig, CritterRig.Chain c) {
        int base = 2;
        for (CritterRig.Chain o : rig.chains) {
            if (o == c) {
                return base;
            }
            base += o.bones().length + 1;
        }
        return base;
    }

    private static <T extends GeoAnimatable> void applyChain(GeoModel<T> model, CritterRig.Chain c, CritterTrail trail,
                                                             float now, float scale, float bodyYaw, float bodyPitch, float refZ,
                                                             float[] th, int base, float[] probe) {
        GeoBone first = bone(model, c.bones()[0]);
        if (first == null) {
            return;
        }
        float prevYaw = bodyYaw;
        float prevPitch = bodyPitch;
        // 地を歩く種: いまの節の付け根の実際の高さ(地面の高さで測る)。最初は体の付け根の地面から、体の傾きのぶん下げる
        float yJoint = th != null ? th[0] - (probe[base] - probe[0]) / 16.0F * scale * Mth.sin(bodyPitch * RAD) : 0.0F;
        float cumYaw = 0.0F;
        float cumPitch = 0.0F;
        // 平たい鎖(同じ親の兄弟)は位置も動かす: 1 つ前の骨の付け根からの差を、そこまでの角度で回して置き直す
        float px = first.getPivotX();
        float py = first.getPivotY();
        float pz = first.getPivotZ();
        float qx = px;
        float qy = py;
        float qz = pz;
        int n = c.bones().length;
        for (int k = 0; k < n; k++) {
            GeoBone b = bone(model, c.bones()[k]);
            if (b == null) {
                return;
            }
            float back = Math.max(0.0F, (b.getPivotZ() - refZ) / 16.0F * scale);
            GeoBone next = k + 1 < n ? bone(model, c.bones()[k + 1]) : null;
            float seg = next != null ? Math.max((next.getPivotZ() - b.getPivotZ()) / 16.0F * scale, 0.05F) : 0.25F * scale;
            float yaw = trail.yawBack(now, back);
            // 上下: 地を歩く種は節の両端の真下の地面の差、ほかは通ってきた道の高さの差
            float pitch;
            if (th != null) {
                // 前の節が置いた高さから、この節の後ろの端が自分の真下の地面へ届く角度(1 節で曲がれるのは ±60 度まで。
                // 届かなければ次の節が続きを曲がる = 段差では数節かけて垂れ下がる・持ち上がる)
                float len = Math.max((probe[base + k + 1] - probe[base + k]) / 16.0F * scale, 0.02F);
                float sin = Mth.clamp((yJoint - th[base + k + 1]) / len, -0.866F, 0.866F);
                pitch = (float) Math.toDegrees(Math.asin(sin));
                yJoint -= len * sin;
            } else {
                float dy = trail.yBack(now, back) - trail.yBack(now, back + seg);
                pitch = (float) Math.toDegrees(Math.atan2(dy, seg));
            }
            float relYaw = Mth.clamp(Mth.wrapDegrees(yaw - prevYaw), -35.0F, 35.0F) * c.yawGain();
            float relPitch = Mth.clamp(pitch - prevPitch, th != null ? -90.0F : -30.0F, th != null ? 90.0F : 30.0F) * (th != null ? 1.0F : c.pitchGain());
            prevYaw = yaw;
            prevPitch = pitch;
            // 後ろの節が古い(左寄りの)向き = 前が左へ = 中の Y +。坂を上る節 = 前が上がる = 中の X +
            float ry = -relYaw * RAD;
            float rx = relPitch * RAD;
            if (!c.flat()) {
                BoneMath.add(b, rx, ry, 0.0F);
                done(b);
                continue;
            }
            if (k > 0) {
                // 1 つ前の骨(付け根 q)から見たこの骨の付け根を、そこまでの角度で回す
                float ox = b.getPivotX() - px;
                float oy = b.getPivotY() - py;
                float oz = b.getPivotZ() - pz;
                BoneMath.euler(cumPitch, cumYaw, 0.0F, M);
                float nx = qx + M[0] * ox + M[1] * oy + M[2] * oz;
                float ny = qy + M[3] * ox + M[4] * oy + M[5] * oz;
                float nz = qz + M[6] * ox + M[7] * oy + M[8] * oz;
                px = b.getPivotX();
                py = b.getPivotY();
                pz = b.getPivotZ();
                qx = nx;
                qy = ny;
                qz = nz;
                // 位置の X は描画で反転される(中の付け根の x は json の −x)
                b.setPosX(b.getPosX() - (nx - px));
                b.setPosY(b.getPosY() + (ny - py));
                b.setPosZ(b.getPosZ() + (nz - pz));
            }
            cumYaw += ry;
            cumPitch += rx;
            BoneMath.add(b, cumPitch, cumYaw, 0.0F);
            done(b);
        }
    }

    private static <T extends GeoAnimatable> void applyLegs(GeoModel<T> model, LegSet set, float swing, float now, float scale) {
        set.solve(name -> bone(model, name));
        float w = Mth.clamp((swing - 0.01F) / 0.08F, 0.0F, 1.0F);
        if (w <= 0.0F) {
            return;
        }
        float cyc = now / (set.stride * scale);
        float amp = set.swingAmp() * w;
        float lift = set.lift * RAD * w;
        for (LegSet.Leg leg : set.legs) {
            if (!leg.ok) {
                continue;
            }
            GeoBone hip = bone(model, leg.bone);
            if (hip == null) {
                continue;
            }
            float u = cyc + leg.phase;
            u -= Mth.floor(u);
            float fore;
            float up;
            if (u < set.duty) {
                float t = u / set.duty;
                fore = -Mth.cos(t * Mth.PI);
                up = Mth.sin(t * Mth.PI);
            } else {
                float t = (u - set.duty) / (1.0F - set.duty);
                fore = 1.0F - 2.0F * t;
                up = 0.0F;
            }
            BoneMath.preRotate(hip, 0.0F, 1.0F, 0.0F, fore * amp * leg.forwardSign);
            if (up > 0.0F) {
                BoneMath.preRotate(hip, -leg.dz, 0.0F, leg.dx, up * lift);
                for (int i = 0; i < leg.knees.length; i++) {
                    float[] ax = leg.kneeAxes[i];
                    GeoBone k = ax == null ? null : bone(model, leg.knees[i]);
                    if (k != null) {
                        BoneMath.preRotate(k, ax[0], ax[1], ax[2], up * lift * leg.bends[i]);
                        done(k);
                    }
                }
            }
            done(hip);
        }
    }

    private static GeoBone bone(GeoModel<?> model, String name) {
        return model.getAnimationProcessor().getBone(name);
    }

    private static void done(GeoBone b) {
        b.resetStateChanges();
    }
}
