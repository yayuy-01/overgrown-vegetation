package com.yayuy.overgrownvegetation.entity.g1;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * 動けない G1(根付き・苗床)が、育った木の幹や汚染ブロックに埋まったら、その上へ出す(2026-11-14 ユーザー決定 R6-7
 * 「木の幹に埋まったらその上に出るようにできる?」)。
 *
 * <ul>
 *   <li><b>埋まった</b> = 足元から {@link #FOOT} ブロックの高さまでの体が、ブロックの当たりに重なっている(頭の上の葉に触れているだけなら埋まっていない)。</li>
 *   <li><b>出す先</b> = 真上の、いちばん近い「足元から {@link #CLEAR} ブロックの高さまでが空いている」所。そこへ持ち上げた後は、ふつうに落ちるので、
 *   幹・枝・葉の上面に載る(宙には浮かない)。当たり判定も見た目も体ごと動く。背の高い柱の上の方が葉に重なるのは許す【仮】。</li>
 *   <li><b>上限</b> = 真上 {@link #MAX_LIFT} ブロックまで【仮】。それより上まで埋まっている(巨大樹の幹の中など)ときは、動かさずその場に残す
 *   (埋まりのダメージは受けないので枯れない)。横へはずらさない(根付いた場所から動かない、の決まりを保つ)。</li>
 * </ul>
 * 見るのは {@link #INTERVAL} tick に 1 回(サーバー)。
 */
public final class G1Unbury {

    /** 埋まったかを見る高さ・出す先に要る空きの高さ(ブロック。体がこれより低ければ体の高さ)・真上へ出す上限・見る間隔。【仮】 */
    public static final double FOOT = 0.5D;
    public static final double CLEAR = 1.5D;
    public static final int MAX_LIFT = 12;
    public static final int INTERVAL = 40;

    private G1Unbury() {
    }

    /** 足元が埋まっていれば、真上のいちばん近い空きへ持ち上げる。持ち上げたら true。 */
    public static boolean liftIfBuried(Mob mob) {
        Level level = mob.level();
        if (level.isClientSide() || !mob.isAlive()) return false;
        AABB box = mob.getBoundingBox();
        AABB foot = new AABB(box.minX, box.minY + 0.01D, box.minZ, box.maxX, box.minY + Math.min(FOOT, box.getYsize()), box.maxZ).deflate(0.05D, 0.0D, 0.05D);
        if (level.noCollision(mob, foot)) return false;
        double clear = Math.min(CLEAR, box.getYsize());
        // 足元のブロックの上面から順に、1 ブロックずつ上を試す
        double baseY = Math.floor(box.minY);
        for (int up = 1; up <= MAX_LIFT; up++) {
            double y = baseY + up;
            AABB space = new AABB(box.minX, y + 0.01D, box.minZ, box.maxX, y + clear, box.maxZ).deflate(0.05D, 0.0D, 0.05D);
            if (!level.noCollision(mob, space)) continue;
            mob.setPos(mob.getX(), y, mob.getZ());
            mob.setDeltaMovement(0.0D, 0.0D, 0.0D);
            mob.fallDistance = 0.0F;
            return true;
        }
        return false;
    }
}
