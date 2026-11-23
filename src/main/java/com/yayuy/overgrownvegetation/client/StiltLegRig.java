package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.stilt.StiltLegIK;
import com.yayuy.overgrownvegetation.entity.stilt.StiltWalker;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 竹馬型の脚の描画(2026-11-10。種によらない)。サーバーが決めた足の置き場({@link StiltWalker.Client})へ、脚 4 本を
 * {@link StiltLegIK} で伸ばす。キーのアニメ({@code walk} など)が脚の骨に入れた値は<b>上書き</b>する(脚の持ち上げ方は手続きの弧)。
 *
 * <p>骨はモデルごとに使い回し(個体ごとではない)。書いた骨は {@link GeoBone#resetStateChanges()} で印を下ろす
 * (下ろさないと、キーの無い骨で書いた値が次のフレームに残る。記憶 geckolib-custom-anim-and-easing-traps)。
 */
public final class StiltLegRig {

    private StiltLegRig() {
    }

    /**
     * 脚を解く。{@code legs[i]} = {付け根, 腿, 脛, 足先} の骨の名前。{@code tips[i]} = 足先の骨の静止の先(中の座標、足先の pivot からのずれ)。
     * {@code rootShiftY} = root の骨を下げる量(モデル単位。モデルの原点 = 足元を、エンティティの位置 = 胴の箱の底へ合わせる)。
     */
    public static void apply(GeoModel<?> model, Mob e, StiltWalker.Client feet, String[][] legs, double[][] tips,
                             float rootShiftY, float partialTick, float weight) {
        GeoBone root = (software.bernie.geckolib.cache.object.GeoBone) model.getAnimationProcessor().getBone("root");
        if (root == null) return;
        root.setPosY(root.getPosY() - rootShiftY);
        root.resetStateChanges();
        if (feet == null || !feet.ready() || weight <= 0.0F) return;

        // root の行列(中の座標)。付け根(coxa)は root の子で回さない
        double[] mRoot = boneMatrix(root);
        double[] inv = invertRigid(mRoot);
        double ex = Mth.lerp(partialTick, e.xo, e.getX()), ey = Mth.lerp(partialTick, e.yo, e.getY()), ez = Mth.lerp(partialTick, e.zo, e.getZ());
        float yaw = Mth.rotLerp(partialTick, e.yBodyRotO, e.yBodyRot) * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), lx = Mth.cos(yaw), lz = Mth.sin(yaw);
        double[] down = mulDir(inv, 0.0D, -1.0D, 0.0D);
        for (int i = 0; i < legs.length; i++) {
            GeoBone coxa = (software.bernie.geckolib.cache.object.GeoBone) model.getAnimationProcessor().getBone(legs[i][0]);
            GeoBone femur = (software.bernie.geckolib.cache.object.GeoBone) model.getAnimationProcessor().getBone(legs[i][1]);
            GeoBone tibia = (software.bernie.geckolib.cache.object.GeoBone) model.getAnimationProcessor().getBone(legs[i][2]);
            GeoBone tarsus = (software.bernie.geckolib.cache.object.GeoBone) model.getAnimationProcessor().getBone(legs[i][3]);
            if (coxa == null || femur == null || tibia == null || tarsus == null) continue;
            Vec3 f = feet.foot(i, e.tickCount, partialTick);
            if (f == null) continue;
            double dx = f.x - ex, dy = f.y - ey, dz = f.z - ez;
            double left = dx * lx + dz * lz, fwd = dx * fx + dz * fz;
            double[] target = apply(inv, -left * 16.0D, dy * 16.0D, -fwd * 16.0D);
            double[] pF = {femur.getPivotX(), femur.getPivotY(), femur.getPivotZ()};
            double[] pT = {tibia.getPivotX(), tibia.getPivotY(), tibia.getPivotZ()};
            double[] pS = {tarsus.getPivotX(), tarsus.getPivotY(), tarsus.getPivotZ()};
            double[] tip = StiltLegIK.add(pS, tips[i]);
            double[] a = StiltLegIK.solve(pF, pT, pS, tip, target, down);
            coxa.setRotX(0.0F);
            coxa.setRotY(0.0F);
            coxa.setRotZ(0.0F);
            set(femur, a[0], a[1], a[2], weight);
            set(tibia, a[3], a[4], a[5], weight);
            set(tarsus, a[6], a[7], a[8], weight);
            coxa.resetStateChanges();
        }
    }

    private static void set(GeoBone b, double x, double y, double z, float w) {
        b.setRotX(Mth.lerp(w, b.getRotX(), (float) x));
        b.setRotY(Mth.lerp(w, b.getRotY(), (float) y));
        b.setRotZ(Mth.lerp(w, b.getRotZ(), (float) z));
        b.resetStateChanges();
    }

    // ------------------------------------------------------------------ 行列(3×4、行優先)

    /** 骨の行列: 位置のずらし(translate(−posX, posY, posZ))· T(pivot)·Rz·Ry·Rx·T(−pivot)。 */
    static double[] boneMatrix(GeoBone b) {
        double[] r = StiltLegIK.matrix(b.getRotX(), b.getRotY(), b.getRotZ());
        double px = b.getPivotX(), py = b.getPivotY(), pz = b.getPivotZ();
        return new double[]{
                r[0], r[1], r[2], px - (r[0] * px + r[1] * py + r[2] * pz) - b.getPosX(),
                r[3], r[4], r[5], py - (r[3] * px + r[4] * py + r[5] * pz) + b.getPosY(),
                r[6], r[7], r[8], pz - (r[6] * px + r[7] * py + r[8] * pz) + b.getPosZ()};
    }

    static double[] apply(double[] m, double x, double y, double z) {
        return new double[]{
                m[0] * x + m[1] * y + m[2] * z + m[3],
                m[4] * x + m[5] * y + m[6] * z + m[7],
                m[8] * x + m[9] * y + m[10] * z + m[11]};
    }

    static double[] mulDir(double[] m, double x, double y, double z) {
        return new double[]{m[0] * x + m[1] * y + m[2] * z, m[4] * x + m[5] * y + m[6] * z, m[8] * x + m[9] * y + m[10] * z};
    }

    static double[] invertRigid(double[] m) {
        double[] o = {m[0], m[4], m[8], 0, m[1], m[5], m[9], 0, m[2], m[6], m[10], 0};
        o[3] = -(o[0] * m[3] + o[1] * m[7] + o[2] * m[11]);
        o[7] = -(o[4] * m[3] + o[5] * m[7] + o[6] * m[11]);
        o[11] = -(o[8] * m[3] + o[9] * m[7] + o[10] * m[11]);
        return o;
    }
}
