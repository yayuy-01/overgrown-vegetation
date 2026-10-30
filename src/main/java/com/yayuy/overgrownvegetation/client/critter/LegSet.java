package com.yayuy.overgrownvegetation.client.critter;

import net.minecraft.util.Mth;
import software.bernie.geckolib.cache.object.GeoBone;

import java.util.ArrayList;
import java.util.List;

/**
 * Java で動かす多脚の歩き(2026-10-22、G2・G3。アースロプレウラ・スペラエオプレウラ・リトプレウラ・ファランギオターブス)。
 *
 * <p>足の位相は<b>歩いた距離</b>から決めるので、速さが変わっても足が滑らない(キーの歩きは 1 つの速さでしか合わない)。
 * 1 周 = {@link #stride} ブロック(体格 1)進む。1 周のうち {@link #duty} の間は足を上げて前へ運び、残りは地面に着いたまま
 * 体の進みと同じ速さで後ろへ送る。振れの角度は歩幅と脚の長さから決める(足が地面で滑らない角度)。
 *
 * <p>回し方は「親の座標で回す」({@link BoneMath#preRotate})。脚の付け根の骨に、縦の軸まわりの前後の振りと、
 * 脚の向きに直交する水平の軸まわりの持ち上げを掛ける。関節の骨があれば、持ち上げている間だけ曲げる。
 * 脚の向きは骨の静止の回転と、関節の骨の軸の並びから最初に 1 回だけ求める。
 */
public final class LegSet {

    final float stride;
    final float duty;
    final float lift;
    final float reach;
    final List<Leg> legs = new ArrayList<>();
    private GeoBone solvedFor;

    /**
     * @param stride 1 周で進む距離(ブロック、体格 1)
     * @param duty   1 周のうち足を上げている割合(0.3〜0.5)
     * @param lift   足を上げる角度(度)
     * @param reach  付け根から足先までの水平の長さ(モデルの単位 1/16 ブロック)。振れの角度を決める
     */
    public LegSet(float stride, float duty, float lift, float reach) {
        this.stride = stride;
        this.duty = duty;
        this.lift = lift;
        this.reach = reach;
    }

    /**
     * 脚を 1 本足す。phase は周期の割合(0〜1)。kneeAndBend は関節の骨の名前と曲げの係数(1 = lift と同じ角度、− で逆へ)。
     */
    public LegSet leg(String bone, float phase, Object... kneeAndBend) {
        int n = kneeAndBend.length / 2;
        String[] knees = new String[n];
        float[] bends = new float[n];
        for (int i = 0; i < n; i++) {
            knees[i] = (String) kneeAndBend[i * 2];
            bends[i] = ((Number) kneeAndBend[i * 2 + 1]).floatValue();
        }
        legs.add(new Leg(bone, phase, knees, bends));
        return this;
    }

    /** 振れの角度(ラジアン)。足が着いている間((1 − duty) 周)に体は (1 − duty) × 歩幅 進むので、足先は同じだけ後ろへ動けばよい。 */
    float swingAmp() {
        float r = Math.max(reach / 16.0F, 0.05F);
        return Math.min((1.0F - duty) * stride / (2.0F * r), 0.9F);
    }

    /** 骨の向きを求める(骨が作り直されたときだけ)。 */
    void solve(java.util.function.Function<String, GeoBone> bones) {
        // 骨はリソースの読み直しで作り直されるので、最初の脚の骨が変わったら解き直す
        GeoBone key = legs.isEmpty() ? null : bones.apply(legs.get(0).bone);
        if (key == solvedFor) {
            return;
        }
        solvedFor = key;
        for (Leg leg : legs) {
            leg.solve(bones);
        }
    }

    static final class Leg {
        final String bone;
        final float phase;
        final String[] knees;
        final float[] bends;
        /** 付け根の骨の親の座標での、脚の水平の向き(長さ 1)と、前へ振るときの回す向き(±1)。 */
        float dx;
        float dz;
        float forwardSign;
        /** 関節ごとの曲げの軸(その骨の親の座標)。 */
        float[][] kneeAxes;
        boolean ok;

        Leg(String bone, float phase, String[] knees, float[] bends) {
            this.bone = bone;
            this.phase = phase;
            this.knees = knees;
            this.bends = bends;
        }

        void solve(java.util.function.Function<String, GeoBone> bones) {
            GeoBone hip = bones.apply(bone);
            ok = hip != null;
            if (!ok) {
                return;
            }
            // 脚の向き(静止の回転を掛ける前): 関節があれば付け根 → いちばん先の関節、無ければ付け根の x の向き
            float vx;
            float vy;
            float vz;
            GeoBone tip = knees.length > 0 ? bones.apply(knees[knees.length - 1]) : null;
            if (tip != null) {
                vx = tip.getPivotX() - hip.getPivotX();
                vy = tip.getPivotY() - hip.getPivotY();
                vz = tip.getPivotZ() - hip.getPivotZ();
            } else {
                vx = Math.signum(hip.getPivotX());
                vy = 0.0F;
                vz = 0.0F;
            }
            float[] m = new float[9];
            BoneMath.euler(hip.getInitialSnapshot().getRotX(), hip.getInitialSnapshot().getRotY(), hip.getInitialSnapshot().getRotZ(), m);
            float rx = m[0] * vx + m[1] * vy + m[2] * vz;
            float rz = m[6] * vx + m[7] * vy + m[8] * vz;
            float h = Mth.sqrt(rx * rx + rz * rz);
            if (h < 1.0E-4F) {
                rx = Math.signum(hip.getPivotX());
                rz = 0.0F;
                h = Math.max(Math.abs(rx), 1.0E-4F);
            }
            dx = rx / h;
            dz = rz / h;
            // 縦の軸まわりに + へ回すと、足先は (up × dir) = (dz, 0, −dx) の向きへ動く。前は −Z なので
            // (up × dir)·(0, 0, −1) = dx > 0 なら + で前へ
            forwardSign = dx > 0 ? 1.0F : -1.0F;

            kneeAxes = new float[knees.length][];
            for (int i = 0; i < knees.length; i++) {
                GeoBone k = bones.apply(knees[i]);
                GeoBone next = i + 1 < knees.length ? bones.apply(knees[i + 1]) : null;
                GeoBone prev = i > 0 ? bones.apply(knees[i - 1]) : hip;
                float ex;
                float ez;
                if (k == null) {
                    continue;
                }
                if (next != null) {
                    ex = next.getPivotX() - k.getPivotX();
                    ez = next.getPivotZ() - k.getPivotZ();
                } else {
                    ex = k.getPivotX() - prev.getPivotX();
                    ez = k.getPivotZ() - prev.getPivotZ();
                }
                float eh = Mth.sqrt(ex * ex + ez * ez);
                if (eh < 1.0E-4F) {
                    continue;
                }
                // 軸 = dir × up = (−dz, 0, dx)(関節の親の座標 = 脚の静止の回転の前の座標)。+ で先が持ち上がる
                kneeAxes[i] = new float[]{-ez / eh, 0.0F, ex / eh};
            }
        }
    }
}
