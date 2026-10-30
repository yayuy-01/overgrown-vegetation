package com.yayuy.overgrownvegetation.defense;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * <b>鳴子</b>(2026-10-15、サバイバル用アイテム ㊵。ユーザー指定)の紐の形の計算。
 *
 * <p>紐は柵・塀・柱の類({@link #POSTS})の 2 本の間に張る 1 本の曲線で、式は蔦({@code CorruptedLianaBlockEntity#curvePoint})と同じ
 * 「直線 + 垂れの放物線」。蔦のコードは汚染の仕組みの一部なので触らず、式だけ写した。
 * 紐には「鳴子の板」を 1 枚ずつ吊れる({@link ClapperRopeBlockEntity})。
 */
public final class ClapperRope {

    /** 紐を結べるブロック(柵・塀・柱の類。ユーザー指定)。 */
    public static final TagKey<Block> POSTS = BlockTags.create(OvergrownVegetation.id("clapper_rope_posts"));

    /** 紐の長さの上限(ブロック。蔦の手で張る上限と同じ。仮決め)。 */
    public static final int MAX_LENGTH = 32;
    /** 紐の長さの下限(ブロック)。 */
    public static final int MIN_LENGTH = 2;
    /** 描画・揺れの計算に使う紐の分割数(点は SEGMENTS + 1 個)。 */
    public static final int SEGMENTS = 24;
    /** 視線や物の当たり判定で「紐に触れた」とみなす太さ(ブロック)。 */
    public static final double TOUCH_RADIUS = 0.35D;
    /** 板が紐から吊られる長さ(紐 → 板の上端。ブロック)。ユーザーの模型 naruko.geo.json の輪の中(高さ 4.45 ドット)に紐を通すので 0.45 ドット。 */
    public static final double BOARD_DROP = 0.45D / 16.0D;
    /** 板の高さ(ユーザーの模型で 4 ドット。ブロック)。 */
    public static final double BOARD_HEIGHT = 4.0D / 16.0D;
    /**
     * 吊るした物(燻す物。2026-10-22、地脈技術の 2 段目)の真ん中が紐から下がる長さ(ブロック)。燻されているかはこの点が煙の中にあるかで見る。
     */
    public static final double HANG_ITEM_DROP = 0.22D;
    /** 煙に当たって燻し上がるまでの tick(ユーザー指定 1 分)。 */
    public static final int SMOKE_TICKS = 1200;
    /** 焚き火の煙の柱の高さ(焚き火の上 1〜この数のマス。仮決め 6。2026-10-22 ユーザー指定で送風機なしで燻せるように)。 */
    public static final int SMOKE_COLUMN = 6;

    /**
     * {@code point} が点いている焚き火(魂の焚き火も)の煙の柱の中か: 真下へ {@link #SMOKE_COLUMN} マスまでに点いた焚き火があり、
     * 間にまるごと詰まったブロック(当たり判定が 1 マスいっぱい)が無い。
     */
    public static boolean inCampfireSmoke(net.minecraft.world.level.BlockGetter level, Vec3 point) {
        BlockPos.MutableBlockPos p = BlockPos.containing(point).mutable();
        for (int i = 1; i <= SMOKE_COLUMN; i++) {
            p.move(net.minecraft.core.Direction.DOWN);
            BlockState s = level.getBlockState(p);
            if (net.minecraft.world.level.block.CampfireBlock.isLitCampfire(s)) {
                return true;
            }
            if (s.isCollisionShapeFullBlock(level, p)) {
                return false;
            }
        }
        return false;
    }

    /**
     * 紐に吊るして燻せる物か = 燻製器のレシピ(smoking)の入力にある物(他 MOD のレシピも効く。ユーザー指定)。
     * レシピはクライアントにも配られているので両サイドで同じ答えになる。
     */
    public static java.util.Optional<net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.SmokingRecipe>> smokingRecipe(
            net.minecraft.world.level.Level level, net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) {
            return java.util.Optional.empty();
        }
        return level.getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.SMOKING,
                new net.minecraft.world.item.crafting.SingleRecipeInput(stack), level);
    }

    /** 柱に結べるか。 */
    public static boolean isPost(BlockState state) {
        return state.is(POSTS);
    }

    /** 柱の上の、紐の結び目の位置(柱の真ん中・上から 3/8)。 */
    public static Vec3 knotPoint(BlockPos post) {
        return new Vec3(post.getX() + 0.5D, post.getY() + 0.625D, post.getZ() + 0.5D);
    }

    /** 張ったときの垂れ(長さに比例。仮決め)。 */
    public static float sagFor(double length) {
        return (float) Math.max(0.15D, Math.min(2.0D, length * 0.07D));
    }

    /** 1 本の紐に吊れる板の数(長さ 1 ブロックに 1 枚ほど。仮決め)。 */
    public static int maxBoards(double length) {
        return Math.max(1, (int) Math.floor(length));
    }

    /** 曲線上の点(t = 0 が始点、1 が終点)。 */
    public static Vec3 curvePoint(Vec3 a, Vec3 b, float sag, double t) {
        double x = a.x + (b.x - a.x) * t;
        double z = a.z + (b.z - a.z) * t;
        double y = a.y + (b.y - a.y) * t - 4.0D * sag * t * (1.0D - t);
        return new Vec3(x, y, z);
    }

    /** 視線(start → end の線分)が紐に触れた位置。触れていなければ null。 */
    public record Hit(double t, double distanceAlongRay) {
    }

    /**
     * 視線の線分 {@code from → to} と、曲線を {@link #SEGMENTS} に分けた折れ線の最短距離が {@link #TOUCH_RADIUS} 以内なら、
     * その位置(曲線の t と、視線の始点からの距離)を返す。
     */
    @Nullable
    public static Hit raycast(Vec3 a, Vec3 b, float sag, Vec3 from, Vec3 to) {
        Hit best = null;
        double bestDist = TOUCH_RADIUS * TOUCH_RADIUS;
        Vec3 prev = a;
        for (int i = 1; i <= SEGMENTS; i++) {
            double t = (double) i / SEGMENTS;
            Vec3 cur = curvePoint(a, b, sag, t);
            double[] st = closestBetweenSegments(from, to, prev, cur);
            Vec3 p = lerp(from, to, st[0]);
            Vec3 q = lerp(prev, cur, st[1]);
            double d2 = p.distanceToSqr(q);
            if (d2 <= bestDist) {
                bestDist = d2;
                best = new Hit((i - 1 + st[1]) / SEGMENTS, from.distanceTo(p));
            }
            prev = cur;
        }
        return best;
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return new Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
    }

    /** 2 つの線分 p1→q1、p2→q2 の最も近い点の割合(s, t)。 */
    static double[] closestBetweenSegments(Vec3 p1, Vec3 q1, Vec3 p2, Vec3 q2) {
        Vec3 d1 = q1.subtract(p1);
        Vec3 d2 = q2.subtract(p2);
        Vec3 r = p1.subtract(p2);
        double a = d1.dot(d1);
        double e = d2.dot(d2);
        double f = d2.dot(r);
        double s;
        double t;
        if (a <= 1.0E-9D && e <= 1.0E-9D) {
            return new double[]{0.0D, 0.0D};
        }
        if (a <= 1.0E-9D) {
            s = 0.0D;
            t = clamp(f / e);
        } else {
            double c = d1.dot(r);
            if (e <= 1.0E-9D) {
                t = 0.0D;
                s = clamp(-c / a);
            } else {
                double b = d1.dot(d2);
                double denom = a * e - b * b;
                s = denom != 0.0D ? clamp((b * f - c * e) / denom) : 0.0D;
                t = (b * s + f) / e;
                if (t < 0.0D) {
                    t = 0.0D;
                    s = clamp(-c / a);
                } else if (t > 1.0D) {
                    t = 1.0D;
                    s = clamp((b - c) / a);
                }
            }
        }
        return new double[]{s, t};
    }

    private static double clamp(double v) {
        return v < 0.0D ? 0.0D : Math.min(1.0D, v);
    }

    private ClapperRope() {
    }
}
