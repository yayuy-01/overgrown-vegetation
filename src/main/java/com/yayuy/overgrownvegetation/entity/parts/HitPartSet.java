package com.yayuy.overgrownvegetation.entity.parts;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.entity.PartEntity;

/**
 * 本体 1 体ぶんの叩ける部位の並び(11-08、森の中を動く型 ② の段 1)。種によらない部品。
 *
 * <p>使い方(エンダードラゴンと同じ。<b>部位の id は本体の id の続き番号</b>でないと、クライアントの部位と食い違う):
 * <pre>{@code
 * // コンストラクタ(super の後)
 * this.hitParts = new HitPartSet(this, names, widths, heights);
 * this.setId(ENTITY_COUNTER.getAndAdd(this.hitParts.count() + 1) + 1);
 * // setId の上書き
 * super.setId(id); if (this.hitParts != null) this.hitParts.assignIds(id + 1);
 * // isMultipartEntity() → true、getParts() → hitParts.parts()
 * // 毎 tick(サーバーとクライアントの両方。クライアントは照準の当たりに使う): hitParts.place(this, yRot, centers)
 * }</pre>
 *
 * <p>置き場は種の側が決める(ファルキムネスはアニメから作った軌道表 {@code FalcimnesPartTrack})。
 * ここは<b>体格 1 のブロックで書いた部位の中心</b>(前・左・上)に本体の体格(SCALE 属性)を掛け、向きで回して置くだけ。
 */
public final class HitPartSet {

    private final String[] names;
    private final float[] widths, heights;
    private final HitPart[] parts;
    private float placedScale = Float.NaN;
    private boolean placedOnce;

    public HitPartSet(Mob owner, String[] names, float[] widths, float[] heights) {
        this.names = names;
        this.widths = widths;
        this.heights = heights;
        this.parts = new HitPart[names.length];
        for (int i = 0; i < names.length; i++) {
            this.parts[i] = new HitPart(owner, names[i], widths[i], heights[i]);
        }
    }

    public int count() {
        return this.parts.length;
    }

    public PartEntity<?>[] parts() {
        return this.parts;
    }

    public HitPart part(int i) {
        return this.parts[i];
    }

    /** 部位が叩かれたときに本体へ知らせる口を差す(11-14。本体の hurt の前に呼ばれる)。 */
    public void onHurt(java.util.function.BiConsumer<HitPart, net.minecraft.world.damagesource.DamageSource> hook) {
        for (HitPart p : this.parts) {
            p.onHurt = hook;
        }
    }

    /** 名前で引く(無ければ -1)。 */
    public int indexOf(String name) {
        for (int i = 0; i < this.names.length; i++) {
            if (this.names[i].equals(name)) return i;
        }
        return -1;
    }

    /** 部位の id を {@code firstId} からの続き番号に(本体の {@code setId} から)。 */
    public void assignIds(int firstId) {
        for (int i = 0; i < this.parts.length; i++) {
            this.parts[i].setId(firstId + i);
        }
    }

    /**
     * 部位を置く。前の位置は補間用に残す(F3 + B の箱の描画)。
     *
     * @param yawDeg  体の向き(度。本体の yRot)
     * @param centers 部位ごとの中心 [前, 左, 上](体格 1 のブロック、足元から)。長さは部位の数 × 3
     */
    public void place(Mob owner, float yawDeg, double[] centers) {
        float scale = owner.getScale();
        if (scale != this.placedScale) {
            this.placedScale = scale;
            for (int i = 0; i < this.parts.length; i++) {
                this.parts[i].resize(this.widths[i] * scale, this.heights[i] * scale);
            }
        }
        float yaw = yawDeg * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw);
        double lx = Mth.cos(yaw), lz = Mth.sin(yaw);
        for (int i = 0; i < this.parts.length; i++) {
            HitPart p = this.parts[i];
            double fwd = centers[i * 3] * scale;
            double left = centers[i * 3 + 1] * scale;
            double up = (centers[i * 3 + 2] - this.heights[i] * 0.5D) * scale;
            double x = owner.getX() + fx * fwd + lx * left;
            double y = owner.getY() + up;
            double z = owner.getZ() + fz * fwd + lz * left;
            double ox = this.placedOnce ? p.getX() : x, oy = this.placedOnce ? p.getY() : y, oz = this.placedOnce ? p.getZ() : z;
            p.setPos(x, y, z);
            p.xo = ox;
            p.yo = oy;
            p.zo = oz;
            p.xOld = ox;
            p.yOld = oy;
            p.zOld = oz;
        }
        this.placedOnce = true;
    }
}
