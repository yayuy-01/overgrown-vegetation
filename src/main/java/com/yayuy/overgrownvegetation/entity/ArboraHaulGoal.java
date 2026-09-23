package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NestTable;
import com.yayuy.overgrownvegetation.nest.NestData;
import com.yayuy.overgrownvegetation.nest.NestPods;
import com.yayuy.overgrownvegetation.nest.NestRoute;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;

/**
 * アルボラが<b>狩りの戦利品を巣へ運び込む</b>(ユーザー指定 2026-09-20)。
 *
 * <p>群れに入っていてもいなくても、近くに巣があれば拾ったものを持って坑道を辿り、
 * 貯蔵室の貯蔵胞へ入れる。<b>狩った獲物の落し物は {@link com.yayuy.overgrownvegetation.nest.NestHauling}
 * がその場で手に持たせる</b>ので、「1 体狩ったら必ず運ぶ」が成り立つ。
 *
 * <h2>手順</h2>
 * <ol>
 *     <li><b>拾う</b> — 手ぶらのときだけ。{@code haulSearchRadius} 以内の落し物へ歩いて拾う</li>
 *     <li><b>坑口へ</b> — 巣の道しるべの先頭(坑口)まで歩く</li>
 *     <li><b>潜る</b> — 道しるべを 1 点ずつ奥へ辿る({@link NestRoute})</li>
 *     <li><b>入れる</b> — 貯蔵胞まで歩いて中身を移す</li>
 * </ol>
 *
 * <h2>長い距離を一気に指さない</h2>
 * <p>アルボラの経路探索は壁のぼり用で、<b>経路が見つからないとそのまま直進する</b>
 * ([[wallclimber-nav-hidden-beeline]])。目的地が遠いと経路が出ず、坑口の前で立ち止まったまま
 * どこかへ行ってしまう —— 2026-09-20 のユーザー報告がまさにこれだった。
 * ここでは {@link #walkTo} が必ず {@link #LEG} ブロック以内の中継点を指す。
 */
public class ArboraHaulGoal extends Goal {

    private enum Phase { SEEK_ITEM, TO_MOUTH, DESCEND, TO_POD }

    private static final double SPEED = 1.1D;
    /** 1 回に指してよい距離。これより遠い相手へは中継点を置く。 */
    private static final double LEG = 12.0D;
    /** 拾える距離。 */
    private static final double PICKUP = 1.8D;
    /** 貯蔵胞に届く距離。 */
    private static final double DEPOSIT = 2.5D;
    /** 1 回の運搬に掛けてよい上限(tick)。越えたら諦めて落とす。 */
    private static final int PATIENCE = 2400;
    /** 続けて経路が出なかった回数の上限。越えたら諦める。 */
    private static final int FAIL_LIMIT = 60;
    /** 貯蔵室のこの距離まで来たら、実際の貯蔵胞をブロックとして探しに行く。 */
    private static final double POD_SEARCH = 10.0D;

    private final ArboraEntity arbora;
    private Phase phase = Phase.SEEK_ITEM;
    @Nullable
    private ItemEntity item;
    @Nullable
    private NestData.Nest nest;
    /** 目指す貯蔵室(記録の座標)。チャンクが読み込まれていなくても分かる。 */
    @Nullable
    private BlockPos room;
    /** 実際の貯蔵胞。部屋の近くまで来てから探す(ブロックを見るので読み込みが要る)。 */
    @Nullable
    private BlockPos pod;
    private List<BlockPos> route = List.of();
    /** 道しるべの親(坑口へ 1 歩戻る先)。 */
    private int[] parents = new int[0];
    /** 今たどっている道しるべの並びと、そのどこまで来たか。 */
    private it.unimi.dsi.fastutil.ints.IntList steps = it.unimi.dsi.fastutil.ints.IntList.of();
    private int cursor;
    private int waited;
    private int ticks;
    private int fails;
    /**
     * 次に {@link #canUse} を試すまでの待ち(tick)。貯蔵胞探しは部屋ごとに 13×13×13 マスを見るので、
     * 毎 tick 試すと重い。運び終わった / 諦めたあとはしばらく置く。
     */
    private int cooldown;

    public ArboraHaulGoal(ArboraEntity arbora) {
        this.arbora = arbora;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (arbora.getTarget() != null || arbora.nestRole() == NestRole.BUILDER) {
            return false;                              // 戦闘中と建設中は運ばない
        }
        if (!(arbora.level() instanceof ServerLevel level)) {
            return false;
        }
        NestTable t = DesignData.nest();
        nest = NestData.get(level).nearest(arbora.blockPosition());
        if (nest == null || nest.route().isEmpty()) {
            return false;
        }
        if (nest.origin().distSqr(arbora.blockPosition()) > (double) t.haulNestRadius() * t.haulNestRadius()) {
            return false;                              // 巣が遠すぎる
        }
        route = nest.route();
        parents = nest.routeParents();
        if (!arbora.carriedLoot().isEmpty()) {
            return resume(level);                      // 抱えている。運びかけの続きから
        }
        if (arbora.nestRole() == NestRole.RESIDENT && arbora.isInsideOwnNest()) {
            return false;                              // 住人は巣の中で落し物を拾い歩かない
        }
        item = findItem(t);
        if (item == null) {
            return false;
        }
        phase = Phase.SEEK_ITEM;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (arbora.getTarget() != null || ticks > PATIENCE || fails > FAIL_LIMIT) {
            return false;
        }
        return switch (phase) {
            case SEEK_ITEM -> item != null && item.isAlive() && arbora.carriedLoot().isEmpty();
            case TO_MOUTH, DESCEND, TO_POD -> !arbora.carriedLoot().isEmpty();
        };
    }

    @Override
    public void start() {
        ticks = 0;
        fails = 0;
    }

    @Override
    public void stop() {
        arbora.getNavigation().stop();
        item = null;
        cooldown = 40;
        boolean givenUp = ticks > PATIENCE || fails > FAIL_LIMIT;
        if (arbora.carriedLoot().isEmpty()) {
            pod = null;                                // 運び終わった。行き先は忘れる
            room = null;
            steps = it.unimi.dsi.fastutil.ints.IntList.of();
        } else if (givenUp) {
            // 辿り着けなかった。消えてしまわないようにその場へ落とす
            arbora.spawnAtLocation(arbora.carriedLoot());
            arbora.setCarriedLoot(ItemStack.EMPTY);
            pod = null;
            room = null;
            steps = it.unimi.dsi.fastutil.ints.IntList.of();
        }
        // 中断されただけなら pod と index は残す —— 捨てると再開時に段取りをやり直して、
        // 坑口の前で止まったままになる(2026-09-20 のユーザー報告の原因)。
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        ticks++;
        if (!(arbora.level() instanceof ServerLevel level)) {
            return;
        }
        switch (phase) {
            case SEEK_ITEM -> seekItem(level);
            case TO_MOUTH -> toMouth();
            case DESCEND -> descend();
            case TO_POD -> toPod(level);
        }
    }

    // --- 歩き方 ---

    /**
     * {@code goal} へ歩く。遠ければ {@link #LEG} ブロック先の中継点を指す ——
     * 壁のぼり用の経路探索は遠い目的地の経路を出せず、出せないと直進して壁に張り付くため。
     */
    private void walkTo(BlockPos goal) {
        if (!arbora.getNavigation().isDone()) {
            return;                                    // まだ歩いている
        }
        Vec3 here = arbora.position();
        Vec3 to = Vec3.atCenterOf(goal).subtract(here);
        double distance = to.length();
        Vec3 aim = distance <= LEG ? Vec3.atCenterOf(goal) : here.add(to.normalize().scale(LEG));
        if (arbora.getNavigation().moveTo(aim.x, aim.y, aim.z, SPEED)) {
            fails = 0;
            return;
        }
        fails++;
        arbora.getLookControl().setLookAt(goal.getX() + 0.5D, goal.getY() + 1.0D, goal.getZ() + 0.5D);
    }

    // --- 1. 拾う ---

    @Nullable
    private ItemEntity findItem(NestTable t) {
        double r = t.haulSearchRadius();
        AABB box = arbora.getBoundingBox().inflate(r);
        ItemEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (ItemEntity candidate : arbora.level().getEntitiesOfClass(ItemEntity.class, box,
                e -> e.isAlive() && !e.getItem().isEmpty() && !e.hasPickUpDelay())) {
            double sq = arbora.distanceToSqr(candidate);
            if (sq < bestSq) {
                bestSq = sq;
                best = candidate;
            }
        }
        return best;
    }

    private void seekItem(ServerLevel level) {
        if (item == null) {
            return;
        }
        if (arbora.distanceToSqr(item) <= PICKUP * PICKUP) {
            arbora.setCarriedLoot(item.getItem().copy());
            item.discard();
            item = null;
            arbora.playSound(SoundEvents.ITEM_PICKUP, 0.4F, 1.6F);
            if (!resume(level)) {
                arbora.spawnAtLocation(arbora.carriedLoot());
                arbora.setCarriedLoot(ItemStack.EMPTY);
            }
            return;
        }
        walkTo(item.blockPosition());
    }

    /**
     * 運び先の貯蔵室と、今どこから再開するかを決める。貯蔵室が無ければ false。
     *
     * <p>目指すのは<b>貯蔵胞そのものではなく「貯蔵室の記録の座標」</b>。貯蔵胞はブロックなので
     * 見るにはチャンクの読み込みが要る —— 巣から離れた場所で狩ったときは奥の部屋がまだ
     * 読み込まれておらず、<b>行き先が決まらないまま立ち止まる</b>(2026-09-20 のユーザー報告)。
     * 記録の座標なら読み込み無しで分かるので、まずそこへ歩き、着いてから胞を探す。
     *
     * <p>段取りは<b>毎回いまの居場所から決め直す</b> —— 中断のたびに捨てると、
     * 坑口に着いたあと「もう奥まで来た」と誤判定して直に貯蔵胞を指し、経路が出ずに止まる。
     */
    private boolean resume(ServerLevel level) {
        if (nest == null) {
            return false;
        }
        if (room == null) {
            room = nearestStorageRoom();
        }
        if (room == null) {
            return false;                              // 貯蔵室の無い巣。運び先が無い
        }
        route = nest.route();
        parents = nest.routeParents();
        if (room.distSqr(arbora.blockPosition()) <= POD_SEARCH * POD_SEARCH) {
            phase = Phase.TO_POD;                      // もう貯蔵室のあたり
            return true;
        }
        int at = NestRoute.nearestIndex(route, arbora.blockPosition());
        boolean insideTunnels = at >= 0 && route.get(at).distSqr(arbora.blockPosition()) <= 64.0D;
        if (insideTunnels) {
            // 道しるべの木を辿る。貯蔵室の中心も道しるべなので、そこまで一続きで出る。
            steps = NestRoute.between(parents, at, NestRoute.nearestIndex(route, room));
            cursor = 0;
            waited = 0;
            phase = Phase.DESCEND;
        } else {
            phase = Phase.TO_MOUTH;                    // 外に居る。まず坑口へ
        }
        return true;
    }

    /** 記録の中でいちばん近い貯蔵室。無ければ null。 */
    @Nullable
    private BlockPos nearestStorageRoom() {
        if (nest == null) {
            return null;
        }
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (NestData.ChamberRecord chamber : nest.chambers()) {
            if (chamber.type() != com.yayuy.overgrownvegetation.nest.NestChamber.STORAGE) {
                continue;
            }
            double sq = chamber.pos().distSqr(nest.origin());
            if (sq < bestSq) {
                bestSq = sq;
                best = chamber.pos();
            }
        }
        return best;
    }

    // --- 2. 坑口へ ---

    private void toMouth() {
        BlockPos mouth = route.get(0);
        if (mouth.distSqr(arbora.blockPosition()) <= 16.0D) {
            steps = NestRoute.between(parents, 0, NestRoute.nearestIndex(route, room));
            cursor = 0;
            waited = 0;
            phase = Phase.DESCEND;
            arbora.getNavigation().stop();
            return;
        }
        walkTo(mouth);
    }

    // --- 3. 潜る ---

    /**
     * 道しるべの木を 1 点ずつ辿って貯蔵室まで潜る。
     *
     * <p>以前は幹に沿った添字を 1 つずつ増やしていたので、<b>貯蔵室が枝の先にあると辿り着けなかった</b>。
     * いまは木の上の道(坑口側へ登り、目的の枝へ下る)をそのまま歩く(2026-09-20)。
     */
    private void descend() {
        if (room == null || cursor >= steps.size()) {
            phase = Phase.TO_POD;                      // 貯蔵室のあたりまで来た
            arbora.getNavigation().stop();
            return;
        }
        int index = steps.getInt(cursor);
        if (index < 0 || index >= route.size()) {
            phase = Phase.TO_POD;
            return;
        }
        BlockPos way = route.get(index);
        if (NestRoute.reached(arbora, way)) {
            cursor++;
            waited = 0;
            arbora.getNavigation().stop();
            return;
        }
        // 1 点で手間取ったら飛ばす。坑道は繋がっているので次の点へは歩ける ——
        // ここで粘ると壁に張り付いたまま固まる。
        if (++waited > 60) {
            waited = 0;
            cursor++;
            arbora.getNavigation().stop();
            return;
        }
        if (!arbora.getNavigation().isDone()) {
            return;
        }
        if (!arbora.getNavigation().moveTo(way.getX() + 0.5D, way.getY(), way.getZ() + 0.5D, SPEED)) {
            fails++;
        } else {
            fails = 0;
        }
    }

    // --- 4. 入れる ---

    private void toPod(ServerLevel level) {
        if (room == null) {
            return;
        }
        if (pod == null) {
            // 部屋の近くまで来た。ここで初めてブロックを見に行く(この距離なら読み込まれている)
            if (room.distSqr(arbora.blockPosition()) > POD_SEARCH * POD_SEARCH) {
                walkTo(room);
                return;
            }
            pod = NestPods.nearest(level, nest, arbora.blockPosition());
            if (pod == null) {
                fails += 10;                           // 部屋に着いたのに胞が無い。諦めを早める
                walkTo(room);
                return;
            }
        }
        if (pod.distSqr(arbora.blockPosition()) <= DEPOSIT * DEPOSIT) {
            ItemStack rest = NestPods.insertAt(level, pod, arbora.carriedLoot());
            arbora.setCarriedLoot(rest);
            arbora.playSound(SoundEvents.BONE_BLOCK_PLACE, 0.5F, 0.8F);
            // 運び込んだのが目で分かるように(中身は開けないと見えないため)
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                    pod.getX() + 0.5D, pod.getY() + 1.0D, pod.getZ() + 0.5D, 6, 0.3D, 0.3D, 0.3D, 0.0D);
            if (!rest.isEmpty()) {
                BlockPos next = NestPods.nearest(level, nest, arbora.blockPosition());   // 満杯。別の胞へ
                pod = next != null && !next.equals(pod) ? next : null;
                if (pod == null) {
                    arbora.spawnAtLocation(rest);
                    arbora.setCarriedLoot(ItemStack.EMPTY);
                }
            }
            return;
        }
        walkTo(pod);
    }
}
