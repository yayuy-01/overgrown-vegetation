package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * <b>群れで過繁茂バイオームの外へ出ていく</b>ときの行き先を決める(ユーザー要望 2026-09-15:
 * 「グループ4が群れを組んだ際、過繁茂したバイオームの外に出ようとする行動を追加してほしい」)。
 *
 * <p>それまでの {@link InvaderAdvanceGoal} は<b>足元から {@code 28} ブロックの範囲</b>で
 * 「繁茂系でない地点」を探していた。外周の近くならこれで外へ出られるが、
 * <b>バイオームの内側に居ると候補が 1 つも見つからず</b>、
 * 「とにかく今の場所から離れる」ランダムな方向へ歩くだけになる。
 * 群れはその場をうろつくばかりで、いつまでも縄張りから出ていかない。
 *
 * <p>ここでやるのは<b>どちらが外かを測る</b>こと。群れの居る場所から
 * {@value #DIRECTIONS} 方向へ {@value #SAMPLE_STEP} ブロック刻みでバイオームを見て、
 * <b>いちばん早く繁茂系が途切れる向き</b>を選び、その境目の
 * {@code invaderPackMarchMargin} ブロック先を行き先にする。
 * あとは {@link InvaderAdvanceGoal} が刻んで歩き、後続は
 * {@link InvaderPack#sharedDestination} でその点を写す —— つまり<b>群れ全員が同じ一点</b>を
 * 目指すので、外へ出る途中で放射状にばらけない。
 *
 * <h2>未ロードのチャンクは読まない</h2>
 * {@code Level#getBiome} は未ロードのチャンクに当たると<b>その場でチャンクを生成・読み込む</b>。
 * 8 方向 × 十数回の走査でそれをやるとサーバーが引っかかるので、
 * {@link Level#hasChunkAt} が false になった時点でその方向の走査を打ち切る。
 * <b>読めなかった先は「判らない」であって「外」ではない</b> —— 未ロードを外として扱うと、
 * プレイヤーの描画距離の縁が常に最短の「外」になり、群れがプレイヤーから逃げる形になってしまう。
 *
 * <h2>どちらも判らないときは、群れごとに決まった一方向へ</h2>
 * 走査できた範囲がすべて繁茂系だった(内陸に居る / 周りが未ロード)場合は、
 * <b>群れの ID から決まる向き</b>へまっすぐ進む。乱数にしないのは、
 * <ul>
 *     <li>群れ全員が<b>同じ答え</b>を出す(誰が先に選んでも同じ方向)、</li>
 *     <li>選び直すたびに<b>同じ方向</b>になる(向きがふらついて元の場所へ戻らない)</li>
 * </ul>
 * ため。進んだ先でチャンクが読めるようになれば、そこで改めて本当の外周が見つかる。
 */
public final class InvaderPackMarch {

    /** 外を探す方向の数(45 度刻み)。 */
    private static final int DIRECTIONS = 8;
    /** 1 方向あたりの走査の刻み幅(ブロック)。バイオームは 4×4 単位なのでこれで十分粗い。 */
    private static final double SAMPLE_STEP = 16.0D;
    /** 向きが測れなかったときに進む距離の下限(ブロック)。 */
    private static final double BLIND_MARCH = 96.0D;

    /**
     * {@code leader} の居る場所から見て<b>繁茂系バイオームの外</b>にあたる一点。
     *
     * <p>戻り値は<b>絶対座標</b>(群れ全員で写し合うため)。Y は {@code leader} の高さのまま ——
     * 経路探索側が地面へ吸着させる。
     */
    public static Vec3 exitTarget(Mob leader) {
        Level level = leader.level();
        Vec3 from = leader.position();
        double range = OvergrownVegetationConfig.COMMON.invaderPackMarchRange.get();
        double margin = OvergrownVegetationConfig.COMMON.invaderPackMarchMargin.get();

        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        if (range > 0.0D) {
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int i = 0; i < DIRECTIONS; i++) {
                double angle = i * (Math.PI * 2.0D / DIRECTIONS);
                double dx = Math.cos(angle);
                double dz = Math.sin(angle);
                for (double d = SAMPLE_STEP; d <= range; d += SAMPLE_STEP) {
                    cursor.set(Mth.floor(from.x + dx * d), Mth.floor(from.y), Mth.floor(from.z + dz * d));
                    if (!level.hasChunkAt(cursor)) {
                        // 判らない。ここから先は読まない(読むとチャンク生成が走る)。
                        break;
                    }
                    if (!ModBiomes.isOvergrownLike(level.getBiome(cursor))) {
                        if (d < bestDistance) {
                            bestDistance = d;
                            best = new Vec3(dx, 0.0D, dz);
                        }
                        break;
                    }
                }
            }
        }

        if (best != null) {
            return from.add(best.scale(bestDistance + margin));
        }
        return from.add(fixedHeading(leader).scale(Math.max(range, BLIND_MARCH) + margin));
    }

    /**
     * 群れごとに決まった向き(水平の単位ベクトル)。
     * 群れに属していない個体は自分の UUID から決めるので、こちらも個体ごとに一定。
     */
    private static Vec3 fixedHeading(Mob leader) {
        UUID id = null;
        if (leader instanceof InvaderPackMember member) {
            id = member.packBond().id();
        }
        if (id == null) {
            id = leader.getUUID();
        }
        double angle = Math.floorMod(id.hashCode(), 360) * Mth.DEG_TO_RAD;
        return new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
    }

    private InvaderPackMarch() {
    }
}
