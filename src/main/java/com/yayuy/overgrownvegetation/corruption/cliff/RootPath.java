package com.yayuy.overgrownvegetation.corruption.cliff;

import com.yayuy.overgrownvegetation.organ.OrganBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;

/**
 * 絡む根の縄を、植物側の Mob の経路に入れる(2026-11-20 ユーザー決定「縄を Mob の道として経路に入れる」。クモの壁登りと同じ考え方)。
 * {@code mixin/WalkNodeEvaluatorRootMixin} がバニラの地上の経路探索の隣の節に、次の節を足す:
 * <ul>
 *   <li><b>上る</b>: 体の横に絡む根がある節(= 縄に取り付ける所)から、真上の節(体が通れるなら)。</li>
 *   <li><b>縄に沿って横へ</b>: 宙の節(縄に掴まっている)から、横の節(そこも縄に触れるなら)。縄はゆらいで横へずれるので。</li>
 *   <li><b>縁を越える</b>: 縄に取り付いている節から、横の固いブロックの上(体が通れるなら)。崖の上・岩棚へ上がる。</li>
 * </ul>
 * 節をたどる動きはバニラのまま: 次の節が真上なら MoveControl が跳び、はしご扱い({@link RootClimb})で上る。宙にいる間は
 * {@code PathNavigation#tick} の「真上の節へ進む」の枝が節を進める(バニラがはしごのために持っている枝)。
 * 縄のマスそのもの(根)は通れないので、節は縄の外側・横のすき間になる。頭の上が根でつかえるすき間は「上る」を出さないので、経路が通らない。
 *
 * <p>今は縄(絡む根)だけ。全面に張り付く移動(Vault「全面に張り付く移動」)の作り直しのときは、ここの「取り付ける面」を広げる形でつなぐ。
 */
public final class RootPath {
    private RootPath() {
    }

    /** 1 つの節から足す数の上限(PathFinder の隣の配列は 32。バニラは 8 まで使う)。 */
    static final int MAX_EXTRA = 10;

    /** 足した節の数・取り付ける所に当たった数(調べ・テスト用。数えるだけ)。 */
    public static long addedCount, touchCount; // volatile にしない(経路探索の中で毎回足すので。読むのはテストだけ)

    /**
     * 隣の節を足す。{@code out} の {@code count} 番目から書き、足した数を返す。
     *
     * @param height 体の高さ(マス)
     * @param node   節を作る(NodeEvaluator#getNode)
     */
    public static int addNeighbors(PathfindingContext ctx, Node from, Node[] out, int count, int height, NodeSource node) {
        int added = 0;
        int x = from.x, y = from.y, z = from.z;
        if (!touchesRoot(ctx, x, y, z)) return 0;
        touchCount++;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        boolean airborne = !solid(ctx, p.set(x, y - 1, z));
        // 上る
        boolean upFree = passable(ctx, x, y + 1, z, height, p);
        if (upFree) {
            added += put(out, count + added, node.overgrown$node(x, y + 1, z));
        }
        // 真上へ縄が続くなら、横へは出さない(宙の節ごとに横へ枝分かれすると、探索の上限に先に当たって経路が崖の下で切れた)
        boolean ropeAbove = upFree && touchesRoot(ctx, x, y + 1, z);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (count + added >= out.length - 1 || added >= MAX_EXTRA) break;
            int nx = x + d.getStepX(), nz = z + d.getStepZ();
            if (solid(ctx, p.set(nx, y, nz))) {
                // 縁を越える: 横の固いブロックの上へ(自分の頭の上も空いていること)
                if (passable(ctx, x, y + 1, z, height, p) && passable(ctx, nx, y + 1, nz, height, p)) {
                    added += put(out, count + added, node.overgrown$node(nx, y + 1, nz));
                }
            } else if (airborne && !ropeAbove && passable(ctx, nx, y, nz, height, p) && touchesRoot(ctx, nx, y, nz)) {
                // 縄に沿って横へ
                added += put(out, count + added, node.overgrown$node(nx, y, nz));
            }
        }
        addedCount += added;
        return added;
    }

    /** 上の相手へ引く経路に足す距離(ブロック)【仮】。崖の下まで歩く分 + 縄 20〜30 段ほど。 */
    public static final float CLIMB_ALLOWANCE = 24.0F;

    /**
     * 植物側の Mob が、3 段以上上の行き先へ経路を引くときに足す距離({@code mixin/PathNavigationRootRangeMixin})。それ以外は 0。
     */
    public static float climbAllowance(net.minecraft.world.entity.Mob m, java.util.Set<BlockPos> targets) {
        if (m == null || targets == null) return 0.0F;
        boolean above = false;
        for (BlockPos p : targets) above |= p.getY() - m.getY() >= 3.0D; // 行き先で見る(相手選びがその tick に相手を外していても)
        if (!above) return 0.0F;
        if (!(m.level() instanceof net.minecraft.server.level.ServerLevel sl) || !CliffCorruption.ropesNear(sl, m.blockPosition())) return 0.0F; // 縄を生やした所の近くだけ(ふつうの丘では重さを変えない)
        return com.yayuy.overgrownvegetation.spore.PlantSideRules.isPlantSide(m) ? CLIMB_ALLOWANCE : 0.0F;
    }

    /**
     * 節を作る口(NodeEvaluator#getNode は protected)。mixin {@code WalkNodeEvaluatorRootMixin} が WalkNodeEvaluator に付ける
     * (mixin の中のラムダ・メソッド参照は mixin のクラスを型に持ってしまい、読み込みで落ちるので、口を付ける形にした)。
     */
    public interface NodeSource {
        Node overgrown$node(int x, int y, int z);
    }

    private static int put(Node[] out, int i, Node n) {
        if (n == null || n.closed || i >= out.length) return 0;
        if (n.type == PathType.BLOCKED || n.costMalus < 0.0F) {
            n.type = PathType.OPEN;
            n.costMalus = 0.0F;
        }
        out[i] = n;
        return 1;
    }

    /** 体の横(足元か腰の高さ)に絡む根があるか。 */
    static boolean touchesRoot(PathfindingContext ctx, int x, int y, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 1; dy++) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (ctx.getBlockState(p.set(x + d.getStepX(), y + dy, z + d.getStepZ())).is(OrganBlocks.TANGLED_ROOT.get())) return true;
            }
        }
        return false;
    }

    static boolean solid(PathfindingContext ctx, BlockPos p) {
        return !ctx.getBlockState(p).getCollisionShape(ctx.level(), p).isEmpty();
    }

    /** (x, y, z) から上 height マスが、当たりの無いマスか。 */
    static boolean passable(PathfindingContext ctx, int x, int y, int z, int height, BlockPos.MutableBlockPos p) {
        for (int i = 0; i < Math.max(1, height); i++) {
            if (solid(ctx, p.set(x, y + i, z))) return false;
        }
        return true;
    }
}
