package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import org.jetbrains.annotations.Nullable;

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
    /** 這う辺で「足場があるか」を見るときの座標(climbScratch を指したまま足場を見たいときに使う)。 */
    private final BlockPos.MutableBlockPos crawlFloorScratch = new BlockPos.MutableBlockPos();

    /** 縦の辺の重み。探索 1 回につき {@link #prepare} で 1 回だけ読む(2026-09-24 軽量化、報告 277。前はノードごとに config を読んでいた)。 */
    private float climbMalus;
    /**
     * 体の当たり判定の文脈。{@code noCollision} の中({@code BlockCollisions})がその都度作るものと同じ中身を、
     * 探索 1 回につき 1 回だけ作る(探索は同じ tick の中で終わるので、Mob の高さ・持ち物は変わらない)。
     */
    @Nullable
    private CollisionContext climbContext;
    /**
     * いまの探索に掛かっている群れの体の包絡(群れ v2、2026-09-25。{@link InvaderNavigation#withRequest})。
     * 無ければ自分の体で「そのマスに入るか」を見る。
     */
    @Nullable
    private InvaderNavigation.Envelope climbEnvelope;

    @Override
    public void prepare(PathNavigationRegion level, Mob mob) {
        super.prepare(level, mob);
        InvaderNavigation.Envelope envelope = this.requestEnvelope();
        // 群れの区間では、包絡の「登れるか」を config の重みより先に見る。群れに登れない体(スポロフィルなど)が
        // 1 体でもいれば包絡は登らない —— 先導役だけ崖を登っても後ろが付いて来られない(レビュー指摘)。
        this.climbMalus = envelope != null && !envelope.canClimb()
                ? -1.0F
                : OvergrownVegetationConfig.COMMON.arboraClimbPathMalus.get().floatValue();
        this.climbEnvelope = envelope;
        this.climbContext = CollisionContext.of(mob);
    }

    @Override
    public void done() {
        this.climbContext = null;
        this.climbEnvelope = null;
        super.done();
    }

    @Override
    public int getNeighbors(Node[] outputArray, Node node) {
        int count = super.getNeighbors(outputArray, node);
        float malus = this.climbMalus;
        if (malus < 0.0F) {
            // 負の重みは「登らない」の意味にしてある。
            return count;
        }
        for (int dy : VERTICAL) {
            count = addClimbNode(outputArray, count, node, dy, malus);
        }
        InvaderNavigation.Envelope envelope = this.climbEnvelope;
        if (envelope != null && envelope.crawl()) {
            count = addCrawlNodes(outputArray, count, node, malus);
        }
        return count;
    }

    // --- 壁を横へ・天井を這う・角を回る(登る群れの攻城の区間だけ。2026-09-25) ---

    /**
     * 登る群れ({@code Swarm#climber})の攻城の区間(包絡の {@link InvaderNavigation.Envelope#crawl})だけに足す辺
     * (ユーザー指定 2026-09-25「アルボラは壁や天井を通ってターゲットブロックへ行くルートも」)。縦の辺だけでは、
     * <ul>
     *     <li>壁の上端の外へ張り出した庇(ねずみ返し)を回れない(庇の下で上が塞がる)。</li>
     *     <li>壁を登りきったあと、向こう側の壁を<b>這って降りる</b>道が無い(バニラの歩く辺は 3 段より深い段差へ降りない)ので、
     *     囲った拠点の壁の上で区間が行き止まりになる。</li>
     *     <li>壁を横へ這って窓へ回る・天井を這って床の罠の上を越える道が無い。</li>
     * </ul>
     * 足すのは、隣の 4 方向ごとに次の 4 つ(どれも行き先に体がまるごと入るときだけ、重みは縦の辺と同じ):
     * <ol>
     *     <li><b>縁を乗り越える</b> … 張り付いているノード(足場なし)の隣が塞がっていて、その 1 つ上に立てるなら、そこへ
     *     (壁を登りきって上の面へ出る)。バニラの歩く辺は「足場の高さ」の差で段を見るので、足場の無いノードからは 1 段でも
     *     上がれない(縦の辺だけの経路は壁の上端で途切れ、そこから先は体の張り付きが勝手に越えていた)。</li>
     *     <li><b>同じ面を這う</b> … 張り付いているノードから、隣の張り付けるマス(足場なし)へ。壁の横・天井の裏。</li>
     *     <li><b>天井の縁を上へ巻く</b> … 天井(庇)の裏に張り付いているノードから、隣のマスを通ってその 1 つ上(= いま掴まっている
     *     天井のブロックの横の面)へ(庇の裏から庇の外の面へ)。回り込む面は掴まっている天井そのものなので、隣の別の壁へ跳び移る辺には
     *     ならない(はじめは「1 つ上が何かに面していれば」で足していて、テスト場の脇の壁から庇へ宙を跳ぶ経路が出た)。</li>
     *     <li><b>足場の縁を下へ巻く</b> … 立っているノードの隣に足場が無いとき、その 1 つ下(= いま立っている塊の横の面)へ
     *     (壁の上の縁から、向こう側の面へ降りる)。1 つ下に足場があれば足さない(ふつうの 1 段下り。バニラの歩く辺が持っている)。</li>
     * </ol>
     * 評価器が見るのはノードの 1 列だけなので、包絡が 1 マスに収まるときにだけ群れの側が crawl を立てる
     * ({@code SwarmController#envelope})。後続は足跡の CLIMB の点で張り付く({@code SwarmPaths#kindOf} が、足場の無いノードを
     * CLIMB と書く)。行き先の危なさ(溶岩・水など)は {@link #addCrawlNode} が見る。
     */
    private int addCrawlNodes(Node[] outputArray, int count, Node node, float malus) {
        CollisionGetter level = this.currentContext.level();
        BlockPos.MutableBlockPos at = this.climbScratch;
        BlockPos.MutableBlockPos floorAt = this.crawlFloorScratch;
        boolean standing = hasFloor(level, floorAt, node.x, node.y, node.z);
        boolean ceiling = false;
        if (!standing) {
            at.set(node.x, node.y, node.z);
            ceiling = SurfaceCrawl.isAnchor(level, at, Direction.UP);
        }
        for (Direction direction : HORIZONTAL) {
            if (count >= outputArray.length) {
                return count;
            }
            int x = node.x + direction.getStepX();
            int z = node.z + direction.getStepZ();
            if (standing) {
                // 立っているノードから足せるのは「足場の縁を下へ巻く」だけ。隣に足場がある・1 つ下に足場がある(ふつうの 1 段下り)なら
                // 何も足さない。足場は 1 ブロック読むだけなので先に見る(レビュー指摘 2026-09-25: はじめは 4 方向とも体の当たり判定と
                // 張り付く面を先に見ていた。平地のノードでは全部むだ)。
                if (hasFloor(level, floorAt, x, node.y, z) || hasFloor(level, floorAt, x, node.y - 1, z)) {
                    continue;
                }
                at.set(x, node.y, z);
                if (!crawlFits(level, at)) {
                    continue;
                }
                // 立っている塊(壁の上)の横の面へ。張り付く面は、いま立っている塊そのもの。
                at.set(x, node.y - 1, z);
                if (crawlFits(level, at)) {
                    count = addCrawlNode(outputArray, count, x, node.y - 1, z, malus, true);
                }
                continue;
            }
            at.set(x, node.y, z);
            if (!crawlFits(level, at)) {
                // 横のマスに体が入らない = 張り付いている面そのもの。その上に立てるなら乗り越える。
                if (hasFloor(level, floorAt, x, node.y + 1, z)) {
                    at.set(x, node.y + 1, z);
                    if (crawlFits(level, at)) {
                        count = addCrawlNode(outputArray, count, x, node.y + 1, z, malus, false);
                    }
                }
                continue;
            }
            if (!hasFloor(level, floorAt, x, node.y, z) && SurfaceCrawl.hasAnyAnchor(level, at)) {
                count = addCrawlNode(outputArray, count, x, node.y, z, malus, true);
            }
            if (ceiling && count < outputArray.length) {
                // 天井(庇)の縁を上へ巻く: 天井の裏から、その天井のブロックの横の面へ。回り込む先の張り付く面は、
                // いま掴まっている天井のブロックそのもの(隣の別の壁へ跳び移る辺にしない)。
                at.set(x, node.y + 1, z);
                if (crawlFits(level, at)) {
                    count = addCrawlNode(outputArray, count, x, node.y + 1, z, malus, true);
                }
            }
        }
        return count;
    }

    /**
     * 這う辺を 1 本足す(閉じたノードには足さない)。行き先の危なさは歩く辺と同じ物差し(その Mob の重み)で見て、重みが負(溶岩など)なら
     * 足さない。{@code face}(足場の無い面の上のマス)は水の中へも足さない(水の中では張り付けない)。面の重みは縦の辺の重みと行き先の
     * 重みの大きい方、「歩ける」扱いにするのは縦の辺と同じ理由。面でないマス(縁を乗り越えた先 = 壁の上に立つマス)は、バニラの歩く辺が
     * 付けるのと同じ種類・重みにする(ノードの重みは入ってくる辺すべてに掛かるので、縦の辺の重みを載せると壁の上を歩くほかの経路まで
     * 高くつく)。レビュー指摘 2026-09-25: はじめは行き先を見ずに「歩ける」+ 縦の辺の重みにしていたので、溶岩の堀を這って渡る経路が
     * 出た・水の点が CLIMB と書かれた・壁の上や段差のノードがほかの経路でも高くついた。
     */
    private int addCrawlNode(Node[] outputArray, int count, int x, int y, int z, float malus, boolean face) {
        Node known = this.nodes.get(Node.createHash(x, y, z));
        if (known != null && known.closed) {
            return count;
        }
        PathType type = this.getCachedPathType(x, y, z);
        float typeMalus = this.mob.getPathfindingMalus(type);
        if (typeMalus < 0.0F || (face && type == PathType.WATER)) {
            return count;
        }
        Node crawl = this.getNode(x, y, z);
        if (crawl.closed) {
            return count;
        }
        if (face) {
            crawl.type = PathType.WALKABLE;
            crawl.costMalus = Math.max(crawl.costMalus, Math.max(malus, typeMalus));
        } else {
            crawl.type = type;
            crawl.costMalus = Math.max(crawl.costMalus, typeMalus);
        }
        outputArray[count++] = crawl;
        return count;
    }

    /** そのマスに体がまるごと入るか(丸ごと固いブロックなら箱の走査まで行かない。{@link #addClimbNode} と同じ先読み)。 */
    private boolean crawlFits(CollisionGetter level, BlockPos cell) {
        if (this.climbContext != null
                && level.getBlockState(cell).getCollisionShape(level, cell, this.climbContext) == Shapes.block()) {
            return false;
        }
        return canOccupyHere(level, cell);
    }

    /**
     * {@code (x, y, z)} の真下が足場になる(上に立てる)か。{@code scratch} を使い回す(呼んだあと中身は変わる)。
     * 真下のマスが空いていても、その下のフェンス・塀の当たり判定が食い込んでいれば足場に数える({@link SurfaceCrawl#raisedFromBelow}。
     * 体は 0.5 低いところに立つが、経路の点としてはこのマスで表す)。数えないと、塀の上端の縁を乗り越える辺が出ず、
     * 登る群れが塀・胸壁の手前で止まった(2026-10-02)。
     */
    private static boolean hasFloor(CollisionGetter level, BlockPos.MutableBlockPos scratch, int x, int y, int z) {
        scratch.set(x, y - 1, z);
        return level.getBlockState(scratch).blocksMotion() || SurfaceCrawl.raisedFromBelow(level, scratch);
    }

    /** 東西南北(ノードごとに呼ぶので配列を複製する {@code Plane.HORIZONTAL} の反復を避ける)。 */
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    /**
     * {@code node} の {@code dy} 先へ「登る/降りる」辺を足す。
     * 体が入らない、あるいは張り付ける面が無いマスへは足さない。
     */
    private int addClimbNode(Node[] outputArray, int count, Node node, int dy, float malus) {
        if (count >= outputArray.length) {
            return count;
        }
        // 行き先がもう閉じたノードなら、下の判定がどう出ても辺は足さない(下の climb.closed の枝)。
        // 判定はどれもスナップショットを読むだけなので、先に見て省いても結果は同じ
        // (2026-09-24 軽量化、報告 277。壁を登る列では真下のマスがたいてい閉じている)。
        // getNode は無ければ作ってしまうので、表を覗くだけにする。
        Node known = this.nodes.get(Node.createHash(node.x, node.y + dy, node.z));
        if (known != null && known.closed) {
            return count;
        }
        this.climbScratch.set(node.x, node.y + dy, node.z);
        CollisionGetter level = this.currentContext.level();
        // 安い判定(周りの 5 マスを見るだけ)を先に。開けた地形では真上のマスはたいてい宙なので、
        // 体まるごとの当たり判定まで行かずに済む。どちらも同じスナップショットを読むだけで、
        // 辺を足すのは両方とも通ったときだけなので、順番を入れ替えても結果は変わらない。
        if (!SurfaceCrawl.hasAnyAnchor(level, this.climbScratch)) {
            // 掴まるものが無い = 宙。ここへは登れない。
            return count;
        }
        // 行き先のマスそのものが丸ごと固いブロックなら、体の箱(マスの中心を含む)は必ずそれにぶつかる ——
        // canOccupy の noCollision は BlockCollisions で箱の周りを 1 マスずつ読むが、その中で同じマスを
        // 同じ文脈で読んで Shapes.block() と比べ、当たれば false にする。それを先に 1 マスだけ見る
        // (2026-09-24 軽量化、報告 277。平地を歩くノードの真下 = 床がこれで、ノードごとに箱の走査が走っていた)。
        if (this.climbContext != null
                && level.getBlockState(this.climbScratch)
                        .getCollisionShape(level, this.climbScratch, this.climbContext) == Shapes.block()) {
            return count;
        }
        if (!canOccupyHere(level, this.climbScratch)) {
            return count;
        }
        // 行き先の危なさは歩く辺と同じ物差し(その Mob の重み)で見る。重みが負(溶岩・粉雪など)なら登らない・降りない
        // (レビュー指摘 2026-09-25: はじめは行き先を見ずに「歩ける」にしていたので、壁の下の溶岩の堀へ這い降りる経路が出た)。
        PathType type = this.getCachedPathType(node.x, node.y + dy, node.z);
        float typeMalus = this.mob.getPathfindingMalus(type);
        if (typeMalus < 0.0F) {
            return count;
        }
        Node climb = this.getNode(this.climbScratch.getX(), this.climbScratch.getY(), this.climbScratch.getZ());
        if (climb.closed) {
            return count;
        }
        // 「歩ける」扱いにしておくと、PathFinder 側の足切り(malus < 0 なら捨てる)を素通りできる。水の中の点は水のまま
        // (群れの足跡で SWIM と書かれる。CLIMB と書くと後続が水の中で張り付こうとする)。重みは縦の辺と行き先の大きい方。
        climb.type = type == PathType.WATER ? PathType.WATER : PathType.WALKABLE;
        climb.costMalus = Math.max(climb.costMalus, Math.max(malus, typeMalus));
        outputArray[count++] = climb;
        return count;
    }

    /**
     * そのマスに体がまるごと入るか。包絡が無ければ実際に這うときと同じ {@link SurfaceCrawl#canOccupy}。
     * 包絡があれば、包絡の幅・高さの箱を<b>通り道の中心</b>(マス + floor(幅 + 1) / 2)に置いて見る
     * ({@link OvergrownNodeEvaluator#prepare} と同じ中心。群れ v2、2026-09-25)。
     */
    private boolean canOccupyHere(CollisionGetter level, BlockPos cell) {
        InvaderNavigation.Envelope envelope = this.climbEnvelope;
        if (envelope == null) {
            return SurfaceCrawl.canOccupy(this.mob, level, cell);
        }
        if (cell.getY() < this.mob.level().getMinBuildHeight() || cell.getY() >= this.mob.level().getMaxBuildHeight()) {
            return false;
        }
        double centerX = cell.getX() + envelope.corridorOffset();
        double centerZ = cell.getZ() + envelope.corridorOffset();
        double half = envelope.width() / 2.0D;
        AABB box = new AABB(centerX - half, cell.getY(), centerZ - half,
                centerX + half, cell.getY() + envelope.height(), centerZ + half).deflate(0.02D);
        return level.noCollision(this.mob, box);
    }
}
