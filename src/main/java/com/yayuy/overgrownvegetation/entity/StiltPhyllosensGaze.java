package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.entity.stilt.StiltLegIK;
import com.yayuy.overgrownvegetation.entity.stilt.StiltNeck;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * フィロセンスの首と頭(2026-11-14、段 2)。行動({@code PhyllosensBrain})が「頭を置きたい点・見たい点・胴を前へ倒す角・芯を光らせるか」を決め、
 * ここがサーバーで少しずつそこへ寄せて({@link #HEAD_SPEED} ほか)クライアントへ送る。首の形はどちらの側も {@link StiltNeck} で同じに解く。
 *
 * <p>頭を置きたい点が無い間は、首はキーのアニメのまま(効き {@code weight} を 0 へ下ろす)。
 */
public final class PhyllosensGaze {

    /** 頭・見る点を寄せる速さ(ブロック/tick)・胴を倒す速さ(度/tick)・効きの上げ下げ(/tick)【仮】。 */
    static final double HEAD_SPEED = 0.25D, LOOK_SPEED = 0.6D, LEAN_SPEED = 1.5D;
    static final float WEIGHT_SPEED = 0.12F;

    public final StiltNeck.Shape shape = StiltNeck.PHYLLOSENS;

    /** 確かめ用(テストクライアントの stiltlook): 行動が首を触らない。 */
    public boolean devHold;

    // ---- 行動が決める(サーバー)
    @Nullable
    private Vec3 wantHead, wantLook;
    private double wantLean;
    private boolean wantGlow;

    // ---- 今の値(サーバー: 寄せた値 / クライアント: 受けた値)。位置はエンティティからの差
    private Vec3 head = Vec3.ZERO, look = Vec3.ZERO;
    private double lean;
    private float weight;
    private boolean glow;
    private boolean init;

    // ---- クライアントの補間用(前の tick)
    private Vec3 head0 = Vec3.ZERO, look0 = Vec3.ZERO;
    private double lean0;
    private float weight0;

    /** 頭を {@code headAt} へ置き、{@code lookAt} を見る(ワールド)。null で首をアニメへ返す。 */
    public void aim(@Nullable Vec3 headAt, @Nullable Vec3 lookAt) {
        this.wantHead = headAt;
        this.wantLook = lookAt;
    }

    public void lean(double deg) {
        this.wantLean = deg;
    }

    public void glow(boolean on) {
        this.wantGlow = on;
    }

    public boolean aiming() {
        return this.wantHead != null;
    }

    /** サーバーの tick: 寄せる。戻り値 = 送る値が変わったか。 */
    boolean serverTick(PhyllosensEntity e) {
        Vec3 pos = e.position();
        float yaw = e.getYRot();
        Vec3 rest = restHead(pos, yaw).subtract(pos);
        if (!this.init) {
            this.init = true;
            this.head = rest;
            this.look = rest.add(0, 2, 0);
        }
        Vec3 th = this.wantHead == null ? rest : this.wantHead.subtract(pos);
        Vec3 tl = this.wantLook == null ? th.add(0, 2, 0) : this.wantLook.subtract(pos);
        this.head = approach(this.head, th, HEAD_SPEED);
        this.look = approach(this.look, tl, LOOK_SPEED);
        double tLean = this.wantHead == null ? 0.0D : this.wantLean;
        this.lean += Mth.clamp(tLean - this.lean, -LEAN_SPEED, LEAN_SPEED);
        this.weight = Mth.clamp(this.weight + (this.wantHead == null ? -WEIGHT_SPEED : WEIGHT_SPEED), 0.0F, 1.0F);
        this.glow = this.wantGlow;
        return true;
    }

    private static Vec3 approach(Vec3 from, Vec3 to, double max) {
        Vec3 d = to.subtract(from);
        double l = d.length();
        return l <= max ? to : from.add(d.scale(max / l));
    }

    /** 静止の首のときの頭の pivot(ワールド)。 */
    public Vec3 restHead(Vec3 pos, float yaw) {
        return StiltNeck.toWorld(this.shape, new double[]{0, 207, -1}, pos, yaw, 0.0D);
    }

    // ------------------------------------------------------------------ 送る / 受ける(PhyllosensEntity の同期データ)

    Vector3f headData() {
        return new Vector3f((float) this.head.x, (float) this.head.y, (float) this.head.z);
    }

    Vector3f lookData() {
        return new Vector3f((float) this.look.x, (float) this.look.y, (float) this.look.z);
    }

    /** 倒す角(度 × 10)・効き(× 100)・光る を 1 つの int に。 */
    int packed() {
        int l = Mth.clamp((int) Math.round(this.lean * 10.0D), -900, 900) + 1000;
        int w = Mth.clamp(Math.round(this.weight * 100.0F), 0, 100);
        return l | (w << 12) | (this.glow ? 1 << 20 : 0);
    }

    /** クライアント: 毎 tick、受けた値を今の値にする(前の値を補間用に残す)。 */
    void clientTick(Vector3f h, Vector3f l, int packed) {
        this.head0 = this.head;
        this.look0 = this.look;
        this.lean0 = this.lean;
        this.weight0 = this.weight;
        this.head = new Vec3(h.x(), h.y(), h.z());
        this.look = new Vec3(l.x(), l.y(), l.z());
        this.lean = ((packed & 0xFFF) - 1000) / 10.0D;
        this.weight = ((packed >> 12) & 0xFF) / 100.0F;
        this.glow = (packed & (1 << 20)) != 0;
        if (!this.init) {
            this.init = true;
            this.head0 = this.head;
            this.look0 = this.look;
            this.lean0 = this.lean;
            this.weight0 = this.weight;
        }
    }

    // ------------------------------------------------------------------ 読む

    public float weight(float pt) {
        return Mth.lerp(pt, this.weight0, this.weight);
    }

    public double lean(float pt) {
        return Mth.lerp(pt, this.lean0, this.lean);
    }

    public boolean glowing() {
        return this.glow;
    }

    /** 首の形(補間。pos・yaw は描画の補間した値)。 */
    public StiltNeck.Pose pose(Vec3 pos, float yaw, float pt) {
        Vec3 h = this.head0.lerp(this.head, pt).add(pos), l = this.look0.lerp(this.look, pt).add(pos);
        double ln = lean(pt);
        return StiltNeck.solve(this.shape, StiltNeck.toBody(this.shape, h, pos, yaw, ln), StiltNeck.toBody(this.shape, l, pos, yaw, ln));
    }

    /** サーバー: 今の首の形。 */
    public StiltNeck.Pose pose(PhyllosensEntity e) {
        return pose(e.position(), e.getYRot(), 1.0F);
    }

    /**
     * サーバー: 頭の pivot(ワールド)。効きが 0 の間(首はアニメ)は静止の首の頭の位置で近似する。
     */
    public Vec3 headWorld(PhyllosensEntity e) {
        if (this.weight <= 0.0F) return restHead(e.position(), e.getYRot());
        StiltNeck.Pose p = pose(e);
        return StiltNeck.toWorld(this.shape, p.head(), e.position(), e.getYRot(), this.lean);
    }

    /** サーバー: 目(花の芯)の位置(ワールド)。頭の pivot から花の見る軸へ 7 単位。 */
    public Vec3 eyeWorld(PhyllosensEntity e) {
        if (this.weight <= 0.0F) {
            return restHead(e.position(), e.getYRot()).add(0, 0.4D, 0);
        }
        StiltNeck.Pose p = pose(e);
        double[] q4 = StiltLegIK.matmul(StiltLegIK.matmul(p.q(), p.q()), StiltLegIK.matmul(p.q(), p.q()));
        double[] axis = StiltLegIK.mul(StiltLegIK.matmul(q4, p.headRot()), this.shape.viewAxis());
        double[] eye = StiltLegIK.add(p.head(), new double[]{axis[0] * 7, axis[1] * 7, axis[2] * 7});
        return StiltNeck.toWorld(this.shape, eye, e.position(), e.getYRot(), this.lean);
    }
}
