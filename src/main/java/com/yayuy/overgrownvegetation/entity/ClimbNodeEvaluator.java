package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * <b>壁登り・天井這いを経路探索に組み込む</b>(ユーザー要望 2026-09-20:
 * 「しっかり壁を登ったり天井を走ることができるという前提でルートを組むようにしてほしい」)。
 *
 * <h2>それまで何が起きていたか</h2>
 * アルボラは壁を登れるのに、<b>経路探索はそれを知らなかった</b>。
 * {@code WallClimberNavigation} は名前のわりに経路の作り方には一切手を入れておらず
 * (やっているのは「経路が引けないときに直進する」だけ。それも詰まりの原因なので
 * {@link InvaderNavigation} で潰した)、A* が歩く道しか数えないのは素の Mob と同じ。
 *
 * <p>結果、崖の下に立った個体は<b>目の前の壁を登れば 3 秒で済むのに、
 * 階段状になっている場所を延々と探して遠回りする</b>(ユーザー提供の画像がまさにこれ)。
 *
 * <h2>やること —— 縦の辺を足す</h2>
 * バニラの {@code WalkNodeEvaluator} が作る「歩く辺」に加えて、
 * <b>張り付ける面が隣にあるマスの真上・真下</b>への辺を足す。
 * これだけで A* は「壁を 5 マス登って、上の地面を歩く」という経路を<b>自分で見つける</b>。
 *
 * <p>張り付ける面の判定は実際に這うときと同じ {@link SurfaceCrawl#hasAnyAnchor} ——
 * <b>経路が通ると言った場所は実際に這える</b>ことが保証される。
 * 壁から離れた空中には辺が伸びないので、宙を歩く経路にはならない。
 *
 * <h2>登りは歩きより高くつく</h2>
 * 縦の辺には {@code arboraClimbPathMalus}(既定 2.0)の重みを乗せてある。
 * A* は距離 + 重みで選ぶので、<b>平地を回り込めばすぐ着く場合は今までどおり歩く</b>。
 * 壁を登るのは、そのほうが本当に近いときだけ。重みを 0 にすると何でも登るようになり、
 * 大きくすると崖を避けるようになる。
 */
public class ClimbNodeEvaluator extends OvergrownNodeEvaluator {

    /** 縦の辺を足す先(上と下)。 */
    private static final int[] VERTICAL = {1, -1};

    private final BlockPos.MutableBlockPos climbScratch = new BlockPos.MutableBlockPos();

    @Override
    public int getNeighbors(Node[] outputArray, Node node) {
        int count = super.getNeighbors(outputArray, node);
        float malus = OvergrownVegetationConfig.COMMON.arboraClimbPathMalus.get().floatValue();
        if (malus < 0.0F) {
            // 負の重みは「登らない」の意味にしてある。
            return count;
        }
        for (int dy : VERTICAL) {
            count = addClimbNode(outputArray, count, node, dy, malus);
        }
        return count;
    }

    /**
     * {@code node} の {@code dy} 先へ「登る/降りる」辺を足す。
     * 体が入らない、あるいは張り付ける面が無いマスへは足さない。
     */
    private int addClimbNode(Node[] outputArray, int count, Node node, int dy, float malus) {
        if (count >= outputArray.length) {
            return count;
        }
        this.climbScratch.set(node.x, node.y + dy, node.z);
        CollisionGetter level = this.currentContext.level();
        if (!SurfaceCrawl.canOccupy(this.mob, level, this.climbScratch)) {
            return count;
        }
        if (!SurfaceCrawl.hasAnyAnchor(level, this.climbScratch)) {
            // 掴まるものが無い = 宙。ここへは登れない。
            return count;
        }
        Node climb = this.getNode(this.climbScratch.getX(), this.climbScratch.getY(), this.climbScratch.getZ());
        if (climb.closed) {
            return count;
        }
        // 「歩ける」扱いにしておくと、PathFinder 側の足切り(malus < 0 なら捨てる)を素通りできる。
        climb.type = PathType.WALKABLE;
        climb.costMalus = Math.max(climb.costMalus, malus);
        outputArray[count++] = climb;
        return count;
    }
}
