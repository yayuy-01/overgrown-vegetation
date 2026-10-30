package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.swarm.SwarmPaths;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * グループ4(拡大種)の<b>経路探索</b>。バニラの経路探索に 4 つ手を入れてある
 * (ユーザー報告 2026-09-17:「壁につっかえって動けなくなる個体があまりにも多い」
 * 「目標にしている敵への道を探す行動があまり賢くない」)。
 *
 * <h2>1. クモ由来の「経路を無視した直進」を止める(壁に刺さる最大の原因)</h2>
 * 壁を登る種({@link ArboraEntity})はクモと同じ {@link WallClimberNavigation} を使っていた。
 * これには<b>隠れた直進機能</b>がある:
 * <pre>{@code
 * public Path createPath(BlockPos pos, int accuracy) {
 *     this.pathToPosition = pos;          // 経路が引けなくても記録される
 *     return super.createPath(pos, accuracy);
 * }
 * public void tick() {
 *     if (!this.isDone()) { super.tick(); }
 *     else if (this.pathToPosition != null) {
 *         this.mob.getMoveControl().setWantedPosition(...);   // 経路を無視して直進
 *     }
 * }
 * }</pre>
 * クモは登れるので成立する設計だが、この MOD では次の 3 つが重なって<b>詰みになる</b>。
 * <ol>
 *     <li>{@code PathNavigation#stop()} は {@code path} しか消さない ——
 *     {@code pathToPosition} は残るので、直進は<b>その地点に近づくまで永久に続く</b>。</li>
 *     <li>{@link InvaderPackGoal} は 30 tick ごと、{@link InvaderAdvanceGoal} は 10 tick ごとに
 *     {@code createPath} を呼ぶ。失敗するたびに直進先が更新される。</li>
 *     <li>直進中は {@code isDone()} が <b>true</b>。
 *     {@link AbstractOvergrownInvaderEntity} の詰まり検知は標的を持たない個体について
 *     「経路が無い = 歩いていない」とみなして見逃すので、<b>永久に壁を押し続ける</b>。
 *     しかも壁登りの判定({@code detectWallClimb})も「標的が無く経路も無い」個体は弾くので、
 *     登って抜けることもできない。</li>
 * </ol>
 * ここでは {@link Climber#tick} で直進を握り潰し、{@link Climber#moveTo(Entity, double)} が
 * <b>経路を引けなかったことを正直に false で返す</b>ようにしてある。
 * 壁の上の相手へ体を押し当てて登るきっかけは、
 * {@link AbstractOvergrownInvaderEntity} 側が<b>時間制限つきで</b>作り直している。
 *
 * <h2>2. 経路探索の射程を索敵距離から切り離す</h2>
 * バニラは探索範囲も打ち切り距離も {@code FOLLOW_RANGE} で決める
 * ({@code PathNavigation#createPath} → {@code PathFinder#findPath} の {@code maxRange})。
 * アルボラは 32 なので、<b>32 ブロック歩いて届かない回り道は最初から探さない</b>。
 * 索敵距離を伸ばすと敵対範囲まで広がってしまうので、ここでは
 * <b>経路探索にだけ</b> {@code invaderPathRange}(既定 48)を使う。
 *
 * <h2>3. 探索の予算を増やす</h2>
 * 展開してよいノード数は {@code FOLLOW_RANGE × 16} = 512。幅 2.0 のスポロフィルは 3×3 の枡目を占めるので
 * 通れる道が減り、洞窟や谷では 512 で足りない。{@code invaderPathBudgetMultiplier}(既定 3)倍する。
 *
 * <h2>4. 「どこへも行かない経路」を捨てる</h2>
 * バニラの {@code PathFinder} は目的地へ届かないとき<b>いちばん近いノードまでの部分経路</b>を返す
 * ({@code reachesTarget = false})。呼び出し側はこれを成功と区別しないので、
 * 壁の手前まで歩いて止まり、また同じ経路を引き直す —— これが「道を探すのが賢くない」の正体。
 * ここでは<b>ほとんど進まない部分経路を {@code null} に落とし</b>、
 * 落とした回数を {@link AbstractOvergrownInvaderEntity#notePathFailure} に伝える。
 * 一定回数続けば「詰まった」印が立ち、{@link InvaderUnstickGoal} が回り道を探す。
 *
 * <h2>5. 捨てた探索を同じ条件で繰り返さない(2026-09-24 軽量化)</h2>
 * 4 で捨てた探索と、自分の座標(ブロックの中の位置まで)・目標のブロックも探索の形も同じまま
 * {@link PathRetryMemo#PATH_RETRY_TICKS} tick 以内に引き直されたら、探索を省いて同じ失敗を返す({@link FailedSearch})。
 * 予算 3 倍の探索が、届かない相手へ近接の引き直しのたびに丸ごと走っていた。
 *
 * <h2>6. 探索 1 回ぶんの上書き(群れ v2、2026-09-25)</h2>
 * 群れの先導役が引く「区間」は、群れのいちばん大きい体(<b>包絡</b>、{@link Envelope})が通れる道でなければ
 * 後ろが付いて来られない。また狩り・攻城では、川を泳いで渡る区間も引きたい。どちらも
 * {@link #withRequest} で<b>その 1 回の探索にだけ</b>掛け、終わったら必ず外す。評価器や Mob に置きっぱなしにすると、
 * 近接の引き直し・立て直しの回り道・坑道の開通確認まで太い体・泳ぐ前提になる(レビュー指摘)。
 * 上書きつきの探索は {@link FailedSearch} の鍵にも入るので、包絡で届かなかった区間が、同じ場所からの
 * 普段の探索を「同じ失敗」として省かせることもない。
 * その経路を歩いている間にナビがブロックの変化で引き直すときも、同じ上書きで、群れの札を取ってから引き直す
 * ({@link RequestMemo}。バニラの引き直しは覚えている行き先へ自分の体で引くので、区間が黙って細い体の経路に替わっていた)。
 */
public final class InvaderNavigation {

    /**
     * 部分経路が「進んだ」と認めるのに要る距離(ブロック)。
     * これ未満しか動けない経路は無かったことにする。
     */
    private static final double MIN_PROGRESS = 3.0D;

    /**
     * 群れの<b>体の包絡</b>(群れ v2、2026-09-25)。先導役の区間の探索でだけ使う。
     *
     * @param width    包絡の幅(ブロック、実寸。前衛・支援役のいちばん太い体)
     * @param height   包絡の高さ(ブロック、実寸。{@code swarm.json} の上限で頭打ち)
     * @param canClimb 壁を登る辺を使ってよいか(群れの地上の全員が登れて、しかも狩りのとき・登る群れの攻城のときだけ)
     * @param crawl    壁を横へ這う・天井を這う・角を回る辺も使ってよいか({@link ClimbNodeEvaluator}。登る群れ
     *                 ({@code Swarm#climber})の攻城のときだけ、2026-09-25。{@code canClimb} も立っているときだけ効く)
     */
    public record Envelope(float width, float height, boolean canClimb, boolean crawl) {

        /** 壁・天井を這わない包絡。 */
        public Envelope(float width, float height, boolean canClimb) {
            this(width, height, canClimb, false);
        }

        /** バニラが占有を調べるマス数(幅)。{@code NodeEvaluator#prepare} と同じ {@code floor(幅 + 1)}。 */
        public int blockWidth() {
            return Mth.floor(this.width + 1.0F);
        }

        /**
         * ノードから<b>通り道の中心</b>までのずれ(ブロック)。{@code Path#getEntityPosAtNode} がこの幅の体を立たせる位置と同じ
         * ({@code (int)(幅 + 1) × 0.5})。群れの足跡({@code swarm/Trail})はこの位置で記録する。
         */
        public double corridorOffset() {
            return blockWidth() * 0.5D;
        }
    }

    /**
     * {@code search}(中で {@code mob.getNavigation().createPath(...)} を 1 回呼ぶ)を、<b>この 1 回だけ</b>
     * 包絡と「水を渡る」の上書きつきで走らせる(群れ v2、2026-09-25)。
     *
     * <ul>
     *     <li>{@code envelope} … 評価器({@link OvergrownNodeEvaluator} とその子)が {@code prepare} で読み、
     *     占有マス数・上りの判定・壁登りの判定を包絡の大きさで行う。評価器がこの MOD のものでなければ無視する。</li>
     *     <li>{@code waterCrossing} … 探索のあいだだけ Mob の {@code WATER} / {@code WATER_BORDER} の重みを 0 にし、
     *     終わったら元の値へ戻す({@link AbstractOvergrownInvaderEntity#setWaterCrossing} を 1 回ぶんにした形)。
     *     重みはバニラの評価器が Mob から直接読む(静的な {@code getPathTypeStatic} も)ので、評価器の側では掛け替えられない。</li>
     * </ul>
     * 上書きつきの探索は、届かなかった回数を本体の詰まり検知({@link AbstractOvergrownInvaderEntity#notePathFailure})へ
     * <b>数えない</b> —— 群れの区間が通れないのは「群れが通れない」のであって、その個体が詰まったのではない。
     * 失敗の扱い(待ってから引き直す・行き先を変える)は群れの側({@code swarm/SwarmPaths})が決める。
     * ほとんど進まない部分経路を捨てる({@link #judge})のは今までどおり。
     *
     * <p>サーバースレッドから、経路探索が同期で終わる前提で呼ぶ(バニラと同じ)。上書きは finally で必ず外す。
     *
     * <p>引けた経路はナビ({@link RequestMemo} を持つもの)が覚え、その経路を {@code moveTo} で歩いている間に
     * ブロックの変化で引き直すときは、<b>同じ上書きで</b>、群れの札を取ってから引き直す({@link RequestMemo}、2026-09-25 レビュー指摘)。
     */
    @Nullable
    public static Path withRequest(Mob mob, @Nullable Envelope envelope, boolean waterCrossing, Supplier<Path> search) {
        Path path = runWithOverride(mob, envelope, waterCrossing, search);
        if (path != null && mob.getNavigation() instanceof RequestAware aware) {
            aware.requestMemo().produced(path, envelope, waterCrossing);
        }
        return path;
    }

    /**
     * よそで引いて包絡で確かめ済みの経路(群れの区間の引き継ぎの写し)を、{@link #withRequest} で引いた経路と同じように
     * ナビに覚えさせる。直後の {@code moveTo(path)} で効く(ブロックの変化で引き直すときも同じ上書きになる)。
     */
    public static void adoptRequest(Mob mob, Path path, @Nullable Envelope envelope, boolean waterCrossing) {
        if (mob.getNavigation() instanceof RequestAware aware) {
            aware.requestMemo().produced(path, envelope, waterCrossing);
        }
    }

    /**
     * ナビがいま歩いている {@code current} が、{@code root}({@link #withRequest} / {@link #adoptRequest} の経路を
     * {@code moveTo} で歩き始めたときにナビが持った経路)を、ブロックの変化で<b>同じ上書きのまま</b>引き直した続きか。
     * 群れの足跡が、引き直した経路を区間の続きとして引き継いでよいかの判定({@code swarm/SwarmPaths#recordTrail})。
     */
    public static boolean continuesRequest(Mob mob, @Nullable Path current, @Nullable Path root) {
        return mob.getNavigation() instanceof RequestAware aware && aware.requestMemo().continues(current, root);
    }

    /**
     * いま経路探索ができる体勢か。地面の上・液体の中・乗り物の上(バニラの {@code GroundPathNavigation#canUpdatePath} と同じ)で、
     * しかも壁や天井に張り付いていない。これ以外のときバニラの {@code createPath} は探さずに null を返すので、
     * 群れの側はそれを「引けなかった」と数えないよう、先にこれで確かめる(2026-09-25 レビュー指摘)。
     * 張り付いている間は探せても、面の制御が {@code travel} を止めているので歩けない。
     */
    public static boolean canSearchNow(Mob mob) {
        if (mob instanceof AbstractOvergrownInvaderEntity invader && invader.isClinging()) {
            return false;
        }
        if (mob.getNavigation() instanceof GroundPathNavigation) {
            return mob.onGround() || mob.isInLiquid() || mob.isPassenger();
        }
        return true;
    }

    /** 上書き(包絡・水を渡る)を 1 回ぶん掛けて {@code search} を走らせる。ナビに覚えさせるかは呼び出し側。 */
    @Nullable
    private static Path runWithOverride(Mob mob, @Nullable Envelope envelope, boolean waterCrossing, Supplier<Path> search) {
        PathNavigation navigation = mob.getNavigation();
        OvergrownNodeEvaluator evaluator = navigation.getNodeEvaluator() instanceof OvergrownNodeEvaluator own ? own : null;
        float water = 0.0F;
        float waterBorder = 0.0F;
        if (waterCrossing) {
            water = mob.getPathfindingMalus(PathType.WATER);
            waterBorder = mob.getPathfindingMalus(PathType.WATER_BORDER);
            mob.setPathfindingMalus(PathType.WATER, 0.0F);
            mob.setPathfindingMalus(PathType.WATER_BORDER, 0.0F);
        }
        if (evaluator != null) {
            evaluator.beginRequest(envelope, waterCrossing);
        }
        try {
            return search.get();
        } finally {
            if (evaluator != null) {
                evaluator.endRequest();
            }
            if (waterCrossing) {
                mob.setPathfindingMalus(PathType.WATER, water);
                mob.setPathfindingMalus(PathType.WATER_BORDER, waterBorder);
            }
        }
    }

    /** {@code climber} が true なら壁を登れる種(クモと同じ経路探索)。 */
    public static PathNavigation create(AbstractOvergrownInvaderEntity mob, Level level, boolean climber) {
        return climber ? new Climber(mob, level) : new Walker(mob, level);
    }

    /** 経路探索にだけ使う射程。索敵距離({@code FOLLOW_RANGE})より短くはならない。 */
    static float pathRange(Mob mob) {
        double follow = mob.getAttributeValue(Attributes.FOLLOW_RANGE);
        return (float) Math.max(follow, OvergrownVegetationConfig.COMMON.invaderPathRange.get());
    }

    /** 展開してよいノード数の倍率。 */
    static float budget() {
        return OvergrownVegetationConfig.COMMON.invaderPathBudgetMultiplier.get().floatValue();
    }

    /**
     * 自分で地形を変えた(掘った)ときに呼ぶ。届かなかった探索の控え({@link FailedSearch})を捨てる
     * (2026-09-24 軽量化、報告 109)。控えは「地形が変わっていない」前提なので、壁を抜いた直後の探索まで省かないように。
     */
    static void forgetFailedSearch(PathNavigation navigation) {
        if (navigation instanceof Climber climber) {
            climber.failed.forget();
        } else if (navigation instanceof Walker walker) {
            walker.failed.forget();
        }
    }

    /**
     * 引けた経路を採点する。使えないものは {@code null} にして、
     * その回数を本体へ伝える({@link AbstractOvergrownInvaderEntity#notePathFailure})。
     */
    @Nullable
    static Path judge(AbstractOvergrownInvaderEntity mob, @Nullable Path path) {
        return judge(mob, path, true);
    }

    /**
     * @param count 届いた・届かなかったを本体の詰まり検知へ数えるか。{@link #withRequest} の上書きつきの探索(群れの区間・
     *              群れの立て直し)は数えない —— 失敗の扱いは群れの側が決める(2026-09-25)。
     */
    @Nullable
    static Path judge(AbstractOvergrownInvaderEntity mob, @Nullable Path path, boolean count) {
        if (path == null || path.getNodeCount() == 0) {
            if (count) {
                mob.notePathFailure();
            }
            return null;
        }
        if (path.canReach()) {
            if (count) {
                mob.notePathSuccess();
            }
            return path;
        }
        // 届かない経路。少しでも前へ進めるなら使う(近づいた先で改めて探し直せる)。
        Vec3 end = path.getEntityPosAtNode(mob, path.getNodeCount() - 1);
        if (mob.position().closerThan(end, MIN_PROGRESS)) {
            if (count) {
                mob.notePathFailure();
            }
            return null;
        }
        return path;
    }

    /** {@link #searchJudged} の中身(射程を受け取って親の探索を 1 回呼ぶ)。 */
    @FunctionalInterface
    private interface RangedSearch {
        @Nullable
        Path run(float range);
    }

    /**
     * 控え({@link FailedSearch})と採点({@link #judge})つきの探索 1 回。{@link Climber} と {@link Walker} で共通。
     *
     * <p>{@link #withRequest} の上書きがあれば、その中身(包絡・水を渡るか)も控えの鍵に入れ、届いた・届かなかったを
     * 本体の詰まり検知へ数えない(2026-09-25、群れ v2)。
     */
    @Nullable
    private static Path searchJudged(PathNavigation nav, AbstractOvergrownInvaderEntity invader, FailedSearch failed,
                                     boolean canUpdatePath, Set<BlockPos> targets, int regionOffset, boolean offsetUpward,
                                     int accuracy, RangedSearch search) {
        float budget = budget();
        float range = pathRange(invader);
        OvergrownNodeEvaluator evaluator = nav.getNodeEvaluator() instanceof OvergrownNodeEvaluator own ? own : null;
        boolean count = evaluator == null || !evaluator.hasRequest();
        Envelope envelope = evaluator != null ? evaluator.requestEnvelope() : null;
        boolean water = evaluator != null && evaluator.requestWaterCrossing();
        if (failed.begin(nav, invader, canUpdatePath, targets, regionOffset, offsetUpward, accuracy, range, budget,
                envelope, water)) {
            // 前回と同じ探索が judge で捨てられたばかり。探し直しても同じなので、失敗だけ同じように数える。
            if (count) {
                invader.notePathFailure();
            }
            return null;
        }
        nav.setMaxVisitedNodesMultiplier(budget);
        Path path = judge(invader, search.run(range), count);
        failed.end(path);
        return path;
    }

    /** 上書きつきの経路を覚えるナビ({@link Climber} / {@link Walker} / {@link Plain})。 */
    interface RequestAware {
        RequestMemo requestMemo();
    }

    /**
     * 上書きつきの探索({@link #withRequest})で引いた経路を、ナビが歩いている間だけ覚える(群れ v2、2026-09-25 レビュー指摘)。
     *
     * <p>バニラのナビは、経路の近くのブロックの当たり判定が変わると({@code ServerLevel#sendBlockUpdated} →
     * {@code shouldRecomputePath})、または遅らせた引き直しの時刻が来ると、{@code recomputePath} で
     * <b>覚えている行き先へ自分の体で</b>引き直す(区間の探索でもナビの行き先は書き換わる)。群れの区間がこれで引き直されると、
     * 包絡も「水を渡る」も外れた経路に黙って置き換わり、札の入れ物も通らず、足跡が通り道の中心で記録できなくなる
     * (プレイヤーの建築・扉・坑道・TD の壁壊しで起きる)。だから上書きつきの経路を歩いている間は:
     * <ul>
     *     <li>引き直しにも同じ上書きを掛ける。</li>
     *     <li>引き直す前に群れの札を取る({@code swarm/SwarmPaths#acquireRecompute})。取れない・宙にいるときは
     *     1 tick 遅らせてやり直す(遅らせる印はバニラの {@code hasDelayedRecomputation})。</li>
     *     <li>引き直した経路を元の経路の「続き」として覚え({@link #continues})、群れの足跡が区間として引き継ぐ。</li>
     * </ul>
     * サーバースレッドだけが触る。ナビ 1 つに 1 つ。
     */
    static final class RequestMemo {
        /** {@link #withRequest} / {@link #adoptRequest} が返した・渡した経路(まだ歩いていない)と、その上書き。 */
        @Nullable
        private Path produced;
        @Nullable
        private Envelope producedEnvelope;
        private boolean producedWater;
        /** 上書きつきで引いた経路として、ナビがいま歩いている経路(引き直せば新しい経路に替わる)。 */
        @Nullable
        private Path walking;
        /** {@link #walking} の元(moveTo で歩き始めたときにナビが持った経路)。引き直しても替わらない。 */
        @Nullable
        private Path root;
        @Nullable
        private Envelope envelope;
        private boolean water;

        void produced(Path path, @Nullable Envelope envelope, boolean water) {
            this.produced = path;
            this.producedEnvelope = envelope;
            this.producedWater = water;
        }

        /**
         * {@code moveTo(Path)} のあと。渡した経路が上書きつきで引いたものなら、ナビがいま持つ経路を覚える
         * (並びが同じならバニラは古い経路の物をそのまま使うので、渡した物ではなくナビの物)。それ以外の経路なら忘れる。
         */
        void applied(@Nullable Path argument, @Nullable Path current) {
            if (argument != null && argument == this.produced && current != null) {
                this.walking = current;
                this.root = current;
                this.envelope = this.producedEnvelope;
                this.water = this.producedWater;
            } else {
                forgetWalking();
            }
            this.produced = null;
            this.producedEnvelope = null;
            this.producedWater = false;
        }

        /** ナビがいま歩いている経路が、上書きつきで引いたもの(またはその引き直し)か。 */
        boolean covers(@Nullable Path current) {
            return current != null && current == this.walking;
        }

        boolean continues(@Nullable Path current, @Nullable Path root) {
            return current != null && current == this.walking && root != null && root == this.root;
        }

        /**
         * 同じ上書きで引き直す({@code vanilla} = バニラの {@code recomputePath}。探索はナビの {@code createPath} を通る)。
         * 新しい経路が引ければ続きとして覚え、引けなければ忘れる。
         */
        void recompute(Mob mob, Runnable vanilla, Supplier<Path> current) {
            Path before = current.get();
            runWithOverride(mob, this.envelope, this.water, () -> {
                vanilla.run();
                return current.get();
            });
            Path after = current.get();
            if (after == null) {
                forgetWalking();
            } else if (after != before) {
                this.walking = after;
            }
        }

        private void forgetWalking() {
            this.walking = null;
            this.root = null;
            this.envelope = null;
            this.water = false;
        }
    }

    /**
     * {@code recomputePath} の前半(3 つのナビで共通)。上書きつきの経路を歩いていて、しかも今すぐ引き直す番なら、
     * 引き直してよいか(宙にいない・札が取れた)を返す。
     *
     * @param due          バニラの {@code recomputePath} が今すぐ探索する番か(前の引き直しから 20 tick 超え、行き先あり)
     * @param canUpdatePath ナビの {@code canUpdatePath()}(protected なので呼び出し側が渡す)
     * @return true なら引き直してよい。false なら 1 tick 遅らせる(呼び出し側が {@code hasDelayedRecomputation} を立てる)
     */
    private static boolean mayRecomputeRequest(Mob mob, boolean due, boolean canUpdatePath) {
        if (!due) {
            // バニラは遅らせる印を立てるだけで探さない。札は要らない。
            return true;
        }
        return canUpdatePath && SwarmPaths.acquireRecompute(mob);
    }

    /** 壁を登る種(アルボラ)。 */
    private static final class Climber extends WallClimberNavigation implements RequestAware {

        private final AbstractOvergrownInvaderEntity invader;
        /** 届かなかった探索の控え(2026-09-24 軽量化、報告 109)。 */
        private final FailedSearch failed = new FailedSearch();
        /** 上書きつきで引いた経路の控え(2026-09-25、群れ v2)。 */
        private final RequestMemo memo = new RequestMemo();

        Climber(AbstractOvergrownInvaderEntity mob, Level level) {
            super(mob, level);
            this.invader = mob;
        }

        @Override
        public RequestMemo requestMemo() {
            return this.memo;
        }

        /**
         * <b>壁を登る辺を持った</b>経路探索({@link ClimbNodeEvaluator}、2026-09-20)。
         * これで A* が「壁を登って越える」経路を自分で見つけるようになり、
         * 崖の下で階段状の場所を探して遠回りしなくなる(ユーザー要望)。
         */
        @Override
        protected PathFinder createPathFinder(int maxVisitedNodes) {
            this.nodeEvaluator = new ClimbNodeEvaluator();
            this.nodeEvaluator.setCanPassDoors(true);
            return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
        }

        @Override
        protected Path createPath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy) {
            return searchJudged(this, this.invader, this.failed, this.canUpdatePath(), targets, regionOffset, offsetUpward,
                    accuracy, range -> super.createPath(targets, regionOffset, offsetUpward, accuracy, range));
        }

        /**
         * 経路の近くのブロックが変わった({@code ServerLevel#sendBlockUpdated})。控えは地形が同じ前提なので捨てる。
         * 群れの区間・立て直し(上書きつきの経路)を歩いていれば、同じ上書きで・札を取ってから引き直す({@link RequestMemo})。
         */
        @Override
        public void recomputePath() {
            this.failed.forget();
            if (this.memo.covers(this.path)) {
                boolean due = this.level.getGameTime() - this.timeLastRecompute > 20L && this.getTargetPos() != null;
                if (!mayRecomputeRequest(this.mob, due, this.canUpdatePath())) {
                    this.hasDelayedRecomputation = true;
                } else if (due) {
                    this.memo.recompute(this.mob, super::recomputePath, this::getPath);
                } else {
                    super.recomputePath();
                }
                return;
            }
            super.recomputePath();
        }

        /** 控えを通らない探索(judge も通らない)。ナビの状態が変わるので控えを捨てる。 */
        @Override
        public Path createPath(BlockPos pos, int regionOffset, int accuracy) {
            this.failed.forget();
            return super.createPath(pos, regionOffset, accuracy);
        }

        /** 経路が引けなければ<b>false</b>。バニラは「直進するから true」と嘘をつく。 */
        @Override
        public boolean moveTo(Entity entity, double speed) {
            Path path = this.createPath(entity, 1);
            return path != null && this.moveTo(path, speed);
        }

        /**
         * 経路を歩き始める。控え({@link FailedSearch})は「探索してからナビが経路を歩いていない」前提
         * (歩くと詰まりの数えが動く)なので捨てる(2026-09-24 軽量化、報告 109 のレビュー指摘)。
         */
        @Override
        public boolean moveTo(@Nullable Path path, double speed) {
            if (path != null) {
                this.failed.forget();
            }
            boolean moved = super.moveTo(path, speed);
            this.memo.applied(path, this.path);
            return moved;
        }

        /** 経路が無いときの直進(クモの機能)をしない。 */
        @Override
        public void tick() {
            if (this.isDone()) {
                return;
            }
            super.tick();
        }
    }

    /** 壁を登らない種(フィロセンス・スポロフィル)。 */
    private static final class Walker extends GroundPathNavigation implements RequestAware {

        private final AbstractOvergrownInvaderEntity invader;
        /** 届かなかった探索の控え(2026-09-24 軽量化、報告 109)。 */
        private final FailedSearch failed = new FailedSearch();
        /** 上書きつきで引いた経路の控え(2026-09-25、群れ v2)。 */
        private final RequestMemo memo = new RequestMemo();

        Walker(AbstractOvergrownInvaderEntity mob, Level level) {
            super(mob, level);
            this.invader = mob;
        }

        @Override
        public RequestMemo requestMemo() {
            return this.memo;
        }

        @Override
        protected PathFinder createPathFinder(int maxVisitedNodes) {
            this.nodeEvaluator = new OvergrownNodeEvaluator();
            this.nodeEvaluator.setCanPassDoors(true);
            return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
        }

        @Override
        protected Path createPath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy) {
            return searchJudged(this, this.invader, this.failed, this.canUpdatePath(), targets, regionOffset, offsetUpward,
                    accuracy, range -> super.createPath(targets, regionOffset, offsetUpward, accuracy, range));
        }

        /**
         * 経路の近くのブロックが変わった({@code ServerLevel#sendBlockUpdated})。控えは地形が同じ前提なので捨てる。
         * 群れの区間・立て直し(上書きつきの経路)を歩いていれば、同じ上書きで・札を取ってから引き直す({@link RequestMemo})。
         */
        @Override
        public void recomputePath() {
            this.failed.forget();
            if (this.memo.covers(this.path)) {
                boolean due = this.level.getGameTime() - this.timeLastRecompute > 20L && this.getTargetPos() != null;
                if (!mayRecomputeRequest(this.mob, due, this.canUpdatePath())) {
                    this.hasDelayedRecomputation = true;
                } else if (due) {
                    this.memo.recompute(this.mob, super::recomputePath, this::getPath);
                } else {
                    super.recomputePath();
                }
                return;
            }
            super.recomputePath();
        }

        /** 控えを通らない探索(judge も通らない)。ナビの状態が変わるので控えを捨てる。 */
        @Override
        public Path createPath(BlockPos pos, int regionOffset, int accuracy) {
            this.failed.forget();
            return super.createPath(pos, regionOffset, accuracy);
        }

        /**
         * 経路を歩き始める。控え({@link FailedSearch})は「探索してからナビが経路を歩いていない」前提
         * (歩くと詰まりの数えが動く)なので捨てる(2026-09-24 軽量化、報告 109 のレビュー指摘)。
         */
        @Override
        public boolean moveTo(@Nullable Path path, double speed) {
            if (path != null) {
                this.failed.forget();
            }
            boolean moved = super.moveTo(path, speed);
            this.memo.applied(path, this.path);
            return moved;
        }
    }

    /**
     * スカトゥリウムの経路探索(バニラの {@link GroundPathNavigation} + {@link OvergrownNodeEvaluator})。幅 1 ブロック以上の体で
     * バニラが省く「段差の上に体が入るか」を必ず見る(2026-09-14。元は {@code OvergrownShielderEntity} の中の無名クラス)。
     * 射程・予算・控え・採点はバニラのまま。群れの区間の引き直しに上書きを掛け直すため({@link RequestMemo})、
     * 名前つきのクラスにした(2026-09-25、群れ v2 のレビュー指摘)。
     */
    public static PathNavigation createPlain(Mob mob, Level level) {
        return new Plain(mob, level);
    }

    private static final class Plain extends GroundPathNavigation implements RequestAware {

        private final RequestMemo memo = new RequestMemo();

        Plain(Mob mob, Level level) {
            super(mob, level);
        }

        @Override
        public RequestMemo requestMemo() {
            return this.memo;
        }

        @Override
        protected PathFinder createPathFinder(int maxVisitedNodes) {
            this.nodeEvaluator = new OvergrownNodeEvaluator();
            this.nodeEvaluator.setCanPassDoors(true);
            return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
        }

        @Override
        public void recomputePath() {
            if (this.memo.covers(this.path)) {
                boolean due = this.level.getGameTime() - this.timeLastRecompute > 20L && this.getTargetPos() != null;
                if (!mayRecomputeRequest(this.mob, due, this.canUpdatePath())) {
                    this.hasDelayedRecomputation = true;
                } else if (due) {
                    this.memo.recompute(this.mob, super::recomputePath, this::getPath);
                } else {
                    super.recomputePath();
                }
                return;
            }
            super.recomputePath();
        }

        @Override
        public boolean moveTo(@Nullable Path path, double speed) {
            boolean moved = super.moveTo(path, speed);
            this.memo.applied(path, this.path);
            return moved;
        }
    }

    /**
     * <b>judge で捨てた探索を、同じ条件のまま繰り返さない</b>ための控え(2026-09-24 軽量化、監査報告 109/310)。
     * {@link PathRetryMemo} と同じ考え方を、ナビの中に入れて<b>全部の Goal に</b>効かせる
     * (近接はバニラの {@code MeleeAttackGoal} で、4〜25 tick ごとに引き直す。予算 ×{@code invaderPathBudgetMultiplier} の探索が
     * 届かないまま同じ場所から繰り返されていた)。
     *
     * <p>探索を省くのは次が<b>全部</b>揃ったときだけ。省いた呼び出しは、同じ探索をしたときと同じく
     * {@code notePathFailure} を 1 回数えて {@code null} を返す(ナビには触れない)。
     * <ul>
     *   <li>前回<b>実際に探索した</b>結果が judge で捨てられた(経路なし・ほとんど進まない部分経路)。</li>
     *   <li>その探索のときもいまも、ナビが経路を持っていないか歩き終えている —— バニラが必ず探索する形。
     *   しかもその間に新しい経路を受け取っていない({@code moveTo(Path)}・{@code recomputePath}・judge を通らない探索で捨てる)。
     *   だから詰まりと時間切れの数え({@code isStuck} など)も、探索したときのまま動いていない。</li>
     *   <li>自分の<b>座標と体の箱がぴったり同じ</b>で、接地・水中の別と落ちてよい高さ({@code Mob#getMaxFallDistance})も同じ。</li>
     *   <li>目標のブロック(1 つだけのとき)・探索の形(余白・上へずらすか・精度)・射程・予算が同じ。</li>
     *   <li>その探索から {@link PathRetryMemo#PATH_RETRY_TICKS} tick 経っていない。</li>
     * </ul>
     * 自分の位置を<b>ブロックではなく座標そのもの</b>で比べるのは、探索も judge もブロックの中の位置まで読むから
     * (2026-09-24 レビュー指摘。はじめはブロックで比べていた)。judge の「{@link #MIN_PROGRESS} 以上進むか」は連続座標、
     * バニラの {@code WalkNodeEvaluator#getStart} は接地・水中・{@code floor(y + 0.5)}・体の箱の四隅、
     * 柵や扉の脇の {@code canReachWithoutCollision} は自分の座標からの線分、当たり判定の文脈({@code CollisionContext.of})は足の高さを読む。
     * 群れの中で押されてブロックの中を少し動いただけで、部分経路の終わりが 3 ブロックの内と外を行き来しうる。
     * 落ちてよい高さは標的の有無と体力で変わるので、その場で殴られただけでも探し直す。
     * 座標ごと同じなら探索の入力は地形のほかは全部同じなので、結果が元と変わるのは地形だけが変わったときで、それも
     * {@link PathRetryMemo#PATH_RETRY_TICKS} tick 以内に探し直す。届かない相手の前で立ち止まっている個体(速度が 0 に落ちて座標が動かない)には
     * そのまま効く。探索しない呼び出し(宙に浮いている・歩いている経路の使い回しなど)を挟んだら、控えは捨てる。
     * 水を渡るかの重みを変えたとき({@link AbstractOvergrownInvaderEntity#setWaterCrossing})も捨てる。
     * {@link #withRequest} の上書き(群れの体の包絡・その 1 回だけ水を渡る)は鍵に入れる(2026-09-25、群れ v2) ——
     * 包絡で届かなかった区間のあとに、同じ場所から自分の体で引く近接の探索まで省かないように(レビュー指摘)。
     */
    private static final class FailedSearch {

        /** 控えがあるか(届かなかった最後の探索)。 */
        private boolean held;
        /** {@link #begin} が「必ず探索する形で、ナビが経路を歩いていない」と判断した呼び出しか。{@link #end} で控えを残すかを決める。 */
        private boolean fresh;
        /** 探索したときの自分の座標と体の箱。どちらも作り直されるだけで書き換わらない値なので、そのまま持って equals で比べる。 */
        @Nullable
        private Vec3 pos;
        @Nullable
        private AABB box;
        private boolean onGround;
        private boolean inWater;
        private int maxFall;
        private long to;
        private long searchedAt;
        private int regionOffset;
        private boolean offsetUpward;
        private int accuracy;
        private float range;
        private float budget;
        /** 探索に掛かっていた上書き({@link #withRequest})。包絡は値の record なので equals で比べる。 */
        @Nullable
        private Envelope envelope;
        private boolean water;

        /**
         * 探索の前に呼ぶ。true なら前回の届かなかった探索と同じなので、探索を省いてよい。
         *
         * @param canUpdatePath ナビの {@code canUpdatePath()}(protected なので呼び出し側が渡す)
         * @param envelope      この探索に掛かっている群れの体の包絡(無ければ null)
         * @param water         この探索だけ水を渡るか
         */
        boolean begin(PathNavigation nav, Mob mob, boolean canUpdatePath, Set<BlockPos> targets,
                      int regionOffset, boolean offsetUpward, int accuracy, float range, float budget,
                      @Nullable Envelope envelope, boolean water) {
            // バニラの PathNavigation#createPath が早期に返す条件(目標なし・奈落・宙・歩いている経路の使い回し)に
            // 1 つも当たらない = 必ず探索する呼び出しで、しかもナビが経路を持っていないか歩き終えているときだけを控えの対象にする
            // (経路を歩き終えていれば使い回しの枝には入らないので、必ず探索する)。歩いている途中の経路があると、探索は
            // (届かなくても)その経路の時間切れの数えをやり直し、その後も歩くあいだ詰まりの数えが動くので、省くと差が出る。
            Path current = nav.getPath();
            this.fresh = targets.size() == 1
                    && canUpdatePath
                    && mob.getY() >= (double) mob.level().getMinBuildHeight()
                    && (current == null || current.isDone());
            if (!this.fresh) {
                this.held = false;
                return false;
            }
            long now = mob.level().getGameTime();
            long toKey = targets.iterator().next().asLong();
            Vec3 pos = mob.position();
            AABB box = mob.getBoundingBox();
            boolean onGround = mob.onGround();
            boolean inWater = mob.isInWater();
            int maxFall = mob.getMaxFallDistance();
            // 安い比較から。座標と箱は最後に値で比べる(止まっている個体は同じ物がそのまま返ってくることが多い)。
            if (this.held
                    && now - this.searchedAt < PathRetryMemo.PATH_RETRY_TICKS
                    && toKey == this.to
                    && regionOffset == this.regionOffset
                    && offsetUpward == this.offsetUpward
                    && accuracy == this.accuracy
                    && range == this.range
                    && budget == this.budget
                    && water == this.water
                    && Objects.equals(envelope, this.envelope)
                    && onGround == this.onGround
                    && inWater == this.inWater
                    && maxFall == this.maxFall
                    && pos.equals(this.pos)
                    && box.equals(this.box)) {
                return true;
            }
            // 探索する。鍵をいまの値にしておき、end で結果を見て控えを残すか決める。
            this.held = false;
            this.pos = pos;
            this.box = box;
            this.onGround = onGround;
            this.inWater = inWater;
            this.maxFall = maxFall;
            this.to = toKey;
            this.searchedAt = now;
            this.regionOffset = regionOffset;
            this.offsetUpward = offsetUpward;
            this.accuracy = accuracy;
            this.range = range;
            this.budget = budget;
            this.envelope = envelope;
            this.water = water;
            return false;
        }

        /** 探索のあとに呼ぶ。judge で捨てられた(null)ときだけ控えを残す。 */
        void end(@Nullable Path judged) {
            this.held = this.fresh && judged == null;
        }

        void forget() {
            this.held = false;
        }
    }

    private InvaderNavigation() {
    }
}
