package com.yayuy.overgrownvegetation.entity.leap;

import com.yayuy.overgrownvegetation.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 待ち伏せの種の「伏せる場所」選び(2026-11-14、サルティムネスの段 1。注文書: 汚染地の道・柵の外の藪に地べたへ伏せ、人の通り道の横で待つ)。
 * まわりの点をいくつか試し、点数の一番高い所と、そこで顔を向ける向き(開けた通り道の方)を返す。全部【仮・Claude 11-14】。
 *
 * <h2>点数</h2>
 * <ul>
 *   <li><b>通り道に面する</b>(重み {@link #W_LANE}): 鼻先の先 1〜7 ブロックが歩いて通れる(芯が入り、下に地面がある)割合。8 方向で一番開けた向きに顔を向ける。
 *       その先が道のブロック(土の道・砂利など {@code road_blocks})なら足す({@link #W_ROAD})。</li>
 *   <li><b>体が隠れる</b>({@link #W_COVER}): 体のまわり(脇と後ろ)の足元・その上に下草・茂み・葉・幹がある割合。</li>
 *   <li><b>地面の色が合う</b>({@link #W_GROUND}): 足元が汚染のブロック・土・草・苔。</li>
 *   <li><b>暗い</b>({@link #W_DARK}): 明るさ(空と灯りの大きいほう)が低いほど。</li>
 *   <li>体の長さぶんが入らない所・歩けない所は選ばない。今いる所からの遠さは少しだけ引く({@link #W_FAR})。</li>
 * </ul>
 */
public final class AmbushSpots {

    private AmbushSpots() {
    }

    /** 試す点の数・探す半径(ブロック)・今いる所から最低これだけ離れた所【仮】。 */
    public static final int TRIES = 24;
    public static final double RADIUS = 16.0D, MIN_MOVE = 4.0D;
    static final double W_LANE = 3.0D, W_ROAD = 1.0D, W_COVER = 2.0D, W_GROUND = 1.0D, W_DARK = 1.0D, W_FAR = 0.03D;
    /** 通り道とみなす開け具合の下限(これ未満の場所には伏せない)。 */
    static final double MIN_LANE = 0.5D;

    /** 選んだ場所: 足元と顔を向ける向き(度)と点数。 */
    public record Spot(Vec3 feet, float yaw, double score) {
    }

    @Nullable
    public static Spot find(Mob mob, LeapSpec spec) {
        return findSome(mob, spec, TRIES, null);
    }

    /**
     * {@code tries} 点だけ試し、{@code best} と比べて良い方を返す(2026-11-16 見直し 第 3 便: 24 点 = 千回を超える当たりの点検を 1 tick にまとめず、
     * 呼ぶ側が数 tick に分ける)。
     */
    @Nullable
    public static Spot findSome(Mob mob, LeapSpec spec, int tries, @Nullable Spot best) {
        RandomSource rnd = mob.getRandom();
        for (int i = 0; i < tries; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2.0D;
            double r = MIN_MOVE + rnd.nextDouble() * (RADIUS - MIN_MOVE);
            double x = Mth.floor(mob.getX() + Math.cos(ang) * r) + 0.5D;
            double z = Mth.floor(mob.getZ() + Math.sin(ang) * r) + 0.5D;
            Spot s = score(mob, spec, x, z);
            if (s != null && (best == null || s.score() > best.score())) best = s;
        }
        return best;
    }

    /** 列 (x, z) に伏せたときの点数(伏せられなければ null)。 */
    @Nullable
    public static Spot score(Mob mob, LeapSpec spec, double x, double z) {
        double y = LeapPlanner.standY(mob, x, z, mob.getY() + 6.0D, mob.getY() - 6.0D, mob.getY());
        if (Double.isNaN(y)) return null;
        Vec3 feet = new Vec3(x, y, z);
        if (!LeapPlanner.walkable(mob, feet)) return null;
        Level level = mob.level();
        double sc = mob.getScale();
        double front = spec.bodyFront() * sc;
        double bestLane = -1.0D, bestRoad = 0.0D;
        float bestYaw = 0.0F;
        for (int d = 0; d < 8; d++) {
            float yaw = d * 45.0F;
            if (!LeapPlanner.bodyFits(mob, spec, feet, yaw)) continue;
            float rr = yaw * Mth.DEG_TO_RAD;
            double fx = -Mth.sin(rr), fz = Mth.cos(rr);
            int open = 0, road = 0;
            for (int k = 1; k <= 7; k++) {
                double px = x + fx * (front + k), pz = z + fz * (front + k);
                if (LeapPlanner.noBlockCollision(level, mob, LeapPlanner.coreBox(mob, px, y, pz)) && LeapPlanner.groundBelow(mob, px, y, pz, 1.5D)) {
                    open++;
                    if (level.getBlockState(BlockPos.containing(px, y - 0.5D, pz)).is(ModTags.ROAD_BLOCKS)) road++;
                }
            }
            double lane = open / 7.0D;
            if (lane > bestLane) {
                bestLane = lane;
                bestRoad = road / 7.0D;
                bestYaw = yaw;
            }
        }
        if (bestLane < MIN_LANE) return null;
        // 体のまわり(脇と後ろ)の隠れ
        float rr = bestYaw * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(rr), fz = Mth.cos(rr);
        double lx = fz, lz = -fx;   // 体の左
        double side = spec.bodyHalfWidth() * sc + 1.5D;
        int cover = 0, n = 0;
        for (double along = -spec.bodyBack() * sc; along <= front; along += 2.0D) {
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                n++;
                if (concealing(level, BlockPos.containing(x + fx * along + lx * side * sgn, y + 0.5D, z + fz * along + lz * side * sgn))) cover++;
            }
        }
        n++;
        if (concealing(level, BlockPos.containing(x - fx * (spec.bodyBack() * sc + 1.5D), y + 0.5D, z - fz * (spec.bodyBack() * sc + 1.5D)))) cover++;
        BlockState ground = level.getBlockState(BlockPos.containing(x, y - 0.5D, z));
        double groundFit = ground.is(ModTags.CORRUPTED) || ground.is(BlockTags.DIRT) ? 1.0D : 0.0D;
        BlockPos at = BlockPos.containing(x, y + 0.5D, z);
        double dark = 1.0D - level.getMaxLocalRawBrightness(at) / 15.0D;
        double far = Math.sqrt(mob.distanceToSqr(feet));
        double score = W_LANE * bestLane + W_ROAD * bestRoad + W_COVER * cover / (double) n + W_GROUND * groundFit + W_DARK * dark - W_FAR * far;
        return new Spot(feet, bestYaw, score);
    }

    /** 体を隠すもの(下草・茂み・葉・幹・汚染の植物)が、このマスかその上にあるか。 */
    static boolean concealing(Level level, BlockPos pos) {
        for (int dy = 0; dy <= 1; dy++) {
            BlockState s = level.getBlockState(pos.above(dy));
            if (s.isAir()) continue;
            if (s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(ModTags.CONCEALING) || s.is(ModTags.CORRUPTED) && s.canBeReplaced()
                    || s.canBeReplaced() && s.getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
