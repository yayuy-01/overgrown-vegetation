package com.yayuy.overgrownvegetation.ecosystem.life;

import com.yayuy.overgrownvegetation.block.FallenLogBlock;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * G2・G3 の行動の段の Goal(2026-11-18)。優先は 守る 1 → 逃げる 2 → 洞へ帰る 3 → 狩る・あさる 4 → 休む 5(土台のうろつきは 6)。
 * 数はどれも【仮】({@link EcoBehavior})。満腹なら狩らない・あさらない(10-22 の決定)。
 */
public final class EcoGoals {

    public static final int DEFEND = 1, FLEE = 2, DEN = 3, HUNT = 4, FORAGE = 4, REST = 5;

    private EcoGoals() {
    }

    /** 土台の registerGoals から呼ぶ。 */
    public static void register(PathfinderMob mob, net.minecraft.world.entity.ai.goal.GoalSelector goals) {
        EcoLife life = (EcoLife) mob;
        goals.addGoal(0, new ExitDenGoal(mob, life));
        goals.addGoal(DEFEND, new DefendGoal(mob, life));
        goals.addGoal(FLEE, new FleeGoal(mob, life));
        goals.addGoal(DEN, new DenGoal(mob, life));
        goals.addGoal(HUNT, new HuntGoal(mob, life));
        goals.addGoal(FORAGE, new ForageGoal(mob, life));
        goals.addGoal(REST, new RestGoal(mob, life));
        if ("stenodictya_nymph".equals(EcoLife.id(mob))) goals.addGoal(DEN, new PupateGoal(mob, life));
    }

    static long now(Level level) {
        return level.getGameTime();
    }

    /** 起きている時間か。確かめ用: 名前に ecoawake を含む個体はいつも起きている・ecosleep はいつも眠い。 */
    static boolean awake(PathfinderMob mob, EcoBehavior b) {
        if (com.yayuy.overgrownvegetation.entity.DevNames.has(mob, "ecoawake")) return true;
        if (com.yayuy.overgrownvegetation.entity.DevNames.has(mob, "ecosleep")) return false;
        return b.awake(mob.level());
    }

    /** 確かめ用の名前(DevNames): noeco は行動の Goal を全部止める。notarget(移動の段の GameTest の名前)も止める。 */
    static boolean off(PathfinderMob mob) {
        return com.yayuy.overgrownvegetation.entity.DevNames.has(mob, "noeco") || com.yayuy.overgrownvegetation.entity.DevNames.has(mob, "notarget");
    }

    // ================================================================ 逃げる

    /** 何から逃げるか: 叩いた相手・サバイバルのプレイヤー(しゃがむと半分の距離)・植物側でない生き物・自分を獲物にする G3。 */
    @Nullable
    static LivingEntity threat(PathfinderMob mob, EcoBehavior b) {
        double r = b.fleeRange;
        LivingEntity hurtBy = mob.getLastHurtByMob();
        if (hurtBy != null && hurtBy.isAlive() && mob.tickCount - mob.getLastHurtByMobTimestamp() < 60) return hurtBy;
        if (r <= 0.0D) return null;
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Player p : mob.level().players()) {
            if (p.isCreative() || p.isSpectator() || !p.isAlive()) continue;
            double rr = p.isCrouching() ? r * 0.5D : r;
            double d = p.distanceToSqr(mob);
            if (d < rr * rr && d < bestD) {
                best = p;
                bestD = d;
            }
        }
        if (best != null) return best;
        AABB box = mob.getBoundingBox().inflate(r, 3.0D, r);
        for (LivingEntity e : mob.level().getEntitiesOfClass(LivingEntity.class, box, e -> e != mob && e.isAlive() && !(e instanceof Player))) {
            boolean danger;
            if (e instanceof EcoLife && e instanceof PathfinderMob) {
                danger = EcoLife.behavior(e).isPrey(mob.getType());   // 自分を食べる G3
            } else {
                danger = !PlantSideRules.isPlantSide(e);
            }
            if (!danger) continue;
            double d = e.distanceToSqr(mob);
            if (d < bestD) {
                best = e;
                bestD = d;
            }
        }
        return best;
    }

    public static final class FleeGoal extends Goal {
        final PathfinderMob mob;
        final EcoLife life;
        @Nullable
        LivingEntity from;
        int startle, time;

        FleeGoal(PathfinderMob mob, EcoLife life) {
            this.mob = mob;
            this.life = life;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (off(this.mob) || this.life.ecoBusy() || !every(this.mob, 5)) return false;
            this.from = threat(this.mob, EcoLife.behavior(this.mob));
            return this.from != null;
        }

        @Override
        public boolean canContinueToUse() {
            return this.from != null && this.from.isAlive() && this.time > 0 && !this.life.ecoBusy();
        }

        @Override
        public void start() {
            EcoBehavior b = EcoLife.behavior(this.mob);
            this.time = 100;
            this.mob.getNavigation().stop();
            boolean hasClip = EcoLife.clip(this.mob, EcoAct.STARTLE) != null;
            this.startle = hasClip ? b.startleTicks : 0;
            if (this.startle > 0) this.life.ecoSetAct(EcoAct.STARTLE);
            else run();
        }

        /** 逃げ込む洞(住む洞が 12 ブロック以内で入れるとき)。 */
        @Nullable
        BlockPos denTarget;

        void run() {
            this.life.ecoSetAct(EcoAct.NONE);
            BlockPos home = this.life.ecoMind().home;
            if (home != null && this.mob.level() instanceof net.minecraft.server.level.ServerLevel sl && denUsable(sl, this.mob, home)) {
                Vec3 m = EcoDenBlock.mouth(home, sl.getBlockState(home));
                if (m.distanceToSqr(this.mob.position()) < 12.0D * 12.0D && this.mob.getNavigation().moveTo(m.x, m.y, m.z, 1.0D)) {
                    this.denTarget = home;
                    return;
                }
            }
            this.denTarget = null;
            Vec3 away = DefaultRandomPos.getPosAway(this.mob, 10, 4, this.from.position());
            if (away != null) this.mob.getNavigation().moveTo(away.x, away.y, away.z, 1.0D);
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            this.time--;
            if (this.startle > 0) {
                this.mob.getLookControl().setLookAt(this.from);
                if (--this.startle == 0) run();
                return;
            }
            if (this.denTarget != null) {
                Vec3 m = EcoDenBlock.mouth(this.denTarget, this.mob.level().getBlockState(this.denTarget));
                if (horiz(this.mob.position(), m) < 1.1D * 1.1D) {
                    if (EcoDens.enter(this.mob, this.denTarget)) return;   // 逃げ込んだ(消えた)
                    this.life.ecoMind().home = null;
                    this.denTarget = null;
                    run();
                    return;
                }
            }
            if (this.mob.getNavigation().isDone()) {
                double r = Math.max(EcoLife.behavior(this.mob).fleeRange, 4.0D) * 1.6D;
                if (this.from.distanceToSqr(this.mob) > r * r) this.time = 0;
                else run();
            }
        }

        @Override
        public void stop() {
            this.from = null;
            this.life.ecoSetAct(EcoAct.NONE);
        }
    }

    // ================================================================ 狩る

    public static final class HuntGoal extends Goal {
        final PathfinderMob mob;
        final EcoLife life;
        @Nullable
        LivingEntity prey;
        int time, cooldown, eating;

        HuntGoal(PathfinderMob mob, EcoLife life) {
            this.mob = mob;
            this.life = life;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (off(this.mob) || this.life.ecoBusy() || !every(this.mob, 20)) return false;
            EcoBehavior b = EcoLife.behavior(this.mob);
            if (b.prey.isEmpty() || !awake(this.mob, b)) return false;
            if (fullness(this.mob, this.life, b) >= b.huntBelow) return false;
            double r = b.huntRange;
            LivingEntity best = null;
            double bestD = Double.MAX_VALUE;
            for (LivingEntity e : this.mob.level().getEntitiesOfClass(LivingEntity.class, this.mob.getBoundingBox().inflate(r, 4.0D, r),
                    e -> e != this.mob && e.isAlive() && b.isPrey(e.getType()))) {
                double d = e.distanceToSqr(this.mob);
                if (d < bestD && d <= r * r) {
                    best = e;
                    bestD = d;
                }
            }
            this.prey = best;
            return best != null;
        }

        @Override
        public boolean canContinueToUse() {
            if (this.eating > 0) return true;
            return this.prey != null && this.prey.isAlive() && this.time > 0 && !this.life.ecoBusy()
                    && this.prey.distanceToSqr(this.mob) < sq(EcoLife.behavior(this.mob).huntRange * 2.0D);
        }

        @Override
        public void start() {
            this.time = 400;
            this.cooldown = 0;
            this.eating = 0;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            EcoBehavior b = EcoLife.behavior(this.mob);
            if (this.eating > 0) {
                this.mob.getNavigation().stop();
                if (--this.eating == 0) {
                    this.life.ecoMind().feed(now(this.mob.level()), b, this.mob.getRandom(), 1.0D);
                    this.life.ecoSetAct(EcoAct.NONE);
                }
                return;
            }
            this.time--;
            LivingEntity p = this.prey;
            this.mob.getLookControl().setLookAt(p, 30.0F, 30.0F);
            double reach = this.mob.getBbWidth() * 0.5D + p.getBbWidth() * 0.5D + 0.8D;
            if (this.mob.distanceToSqr(p) <= reach * reach) {
                this.mob.getNavigation().stop();
                if (this.cooldown-- <= 0) {
                    this.cooldown = 20;
                    this.mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    this.mob.doHurtTarget(p);
                    if (!p.isAlive() || p.isDeadOrDying()) {
                        this.eating = b.eatTicks * 2;          // 倒した獲物を食べる
                        this.life.ecoSetAct(EcoAct.EAT);
                    }
                }
            } else if ((this.time & 7) == 0 || this.mob.getNavigation().isDone()) {
                this.mob.getNavigation().moveTo(p, 1.0D);
            }
        }

        @Override
        public void stop() {
            this.prey = null;
            this.eating = 0;
            this.life.ecoSetAct(EcoAct.NONE);
            this.mob.getNavigation().stop();
        }
    }

    // ================================================================ あさる(見た目だけの虫・葉)

    public static final class ForageGoal extends Goal {
        final PathfinderMob mob;
        final EcoLife life;
        @Nullable
        Vec3 spot;
        int time, eating;

        ForageGoal(PathfinderMob mob, EcoLife life) {
            this.mob = mob;
            this.life = life;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (off(this.mob) || this.life.ecoBusy() || !every(this.mob, 40)) return false;
            EcoBehavior b = EcoLife.behavior(this.mob);
            if (b.forage == EcoBehavior.Forage.NONE || !awake(this.mob, b)) return false;
            if (fullness(this.mob, this.life, b) >= b.forageBelow) return false;
            this.spot = findSpot(this.mob, b.forage);
            return this.spot != null;
        }

        @Override
        public boolean canContinueToUse() {
            return (this.eating > 0 || this.time > 0) && !this.life.ecoBusy();
        }

        @Override
        public void start() {
            this.time = 300;
            this.eating = 0;
            this.mob.getNavigation().moveTo(this.spot.x, this.spot.y, this.spot.z, 0.8D);
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            if (this.eating > 0) {
                this.mob.getNavigation().stop();
                if (--this.eating == 0) {
                    EcoBehavior b = EcoLife.behavior(this.mob);
                    this.life.ecoMind().feed(now(this.mob.level()), b, this.mob.getRandom(), b.forageAmount);
                    this.life.ecoSetAct(EcoAct.NONE);
                    this.time = 0;
                }
                return;
            }
            this.time--;
            double d = this.mob.position().distanceToSqr(this.spot);
            if (d < 1.5D * 1.5D || this.mob.getNavigation().isDone() && d < 3.0D * 3.0D) {
                this.mob.getNavigation().stop();
                this.eating = EcoLife.behavior(this.mob).eatTicks;
                this.life.ecoSetAct(EcoAct.EAT);
            } else if (this.mob.getNavigation().isDone()) {
                this.time = 0;   // 行けなかった
            }
        }

        @Override
        public void stop() {
            this.spot = null;
            this.eating = 0;
            this.life.ecoSetAct(EcoAct.NONE);
        }
    }

    /** あさる所を探す(まわり 10 ブロックを 16 回まで当てずっぽうに見る)。立つ所(足元の高さ)を返す。 */
    @Nullable
    static Vec3 findSpot(PathfinderMob mob, EcoBehavior.Forage kind) {
        Level level = mob.level();
        if (kind == EcoBehavior.Forage.GROUND) {
            return LandRandomPos.getPos(mob, 6, 2);
        }
        BlockPos base = mob.blockPosition();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 16; i++) {
            int dx = mob.getRandom().nextInt(21) - 10, dz = mob.getRandom().nextInt(21) - 10;
            for (int dy = 3; dy >= -3; dy--) {
                p.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                BlockState at = level.getBlockState(p);
                if (!at.getCollisionShape(level, p).isEmpty()) continue;
                BlockState below = level.getBlockState(p.below());
                switch (kind) {
                    case LOG -> {
                        if (below.is(ModBlocks.FALLEN_LOG.get()) && !below.getValue(FallenLogBlock.ROOT)) return Vec3.atBottomCenterOf(p);
                    }
                    case LEAF -> {
                        if (below.is(BlockTags.LEAVES)) return Vec3.atBottomCenterOf(p);
                    }
                    case TRUNK -> {
                        if (below.isFaceSturdy(level, p.below(), Direction.UP)) {
                            for (Direction d : Direction.Plane.HORIZONTAL) {
                                if (level.getBlockState(p.relative(d)).is(BlockTags.LOGS)) return Vec3.atBottomCenterOf(p);
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        return null;
    }

    // ================================================================ 休む

    public static final class RestGoal extends Goal {
        final PathfinderMob mob;
        final EcoLife life;
        int time;
        boolean sleeping;

        RestGoal(PathfinderMob mob, EcoLife life) {
            this.mob = mob;
            this.life = life;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (off(this.mob) || this.life.ecoBusy() || !every(this.mob, 40)) return false;
            EcoBehavior b = EcoLife.behavior(this.mob);
            if (EcoLife.clip(this.mob, EcoAct.REST) == null) return false;
            if (!b.rests) return false;
            this.sleeping = !awake(this.mob, b);
            if (this.sleeping) return true;
            // 起きている間も、満腹なら時々ひと休み【仮】
            return fullness(this.mob, this.life, b) > 0.85D && this.mob.getRandom().nextInt(6) == 0;
        }

        @Override
        public boolean canContinueToUse() {
            if (this.life.ecoBusy()) return false;
            if (this.sleeping) return !awake(this.mob, EcoLife.behavior(this.mob));
            return this.time > 0;
        }

        @Override
        public void start() {
            this.time = EcoLife.behavior(this.mob).restTicks;
            this.mob.getNavigation().stop();
            this.life.ecoSetAct(EcoAct.REST);
        }

        @Override
        public void tick() {
            this.time--;
            this.mob.getNavigation().stop();
        }

        @Override
        public void stop() {
            this.life.ecoSetAct(EcoAct.NONE);
        }
    }

    static double sq(double v) {
        return v * v;
    }

    /**
     * おおよそ n tick に 1 回真。Goal の canUse は偶数の tick にしか呼ばれない(Mob#serverAiStep)ので、tick の番号をそのまま割ると
     * id が奇数の個体で一度も真にならない。2 で割ってから数える。
     */
    /** 満腹度。確かめ用: 名前に ecohungry を含む個体はいつも空腹(写真・テストクライアント用)。 */
    static double fullness(PathfinderMob mob, EcoLife life, EcoBehavior b) {
        if (com.yayuy.overgrownvegetation.entity.DevNames.has(mob, "ecohungry")) return 0.0D;
        return life.ecoMind().fullness(now(mob.level()), b, mob.getRandom());
    }

    static boolean every(PathfinderMob mob, int n) {
        return ((mob.tickCount >> 1) + mob.getId()) % Math.max(1, n / 2) == 0;
    }

    static double horiz(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return dx * dx + dz * dz + Math.min(sq(a.y - b.y), 4.0D);
    }

    /** その洞に入れるか(洞のブロックで、空か同じ種で、空きがある)。 */
    static boolean denUsable(net.minecraft.server.level.ServerLevel level, PathfinderMob mob, BlockPos pos) {
        if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof EcoDenBlockEntity den) || den.full()) return false;
        String s = den.species();
        return s == null || s.equals(EcoLife.id(mob));
    }

    /** 洞に住む種か(EcoDens の表)。 */
    static boolean denDweller(PathfinderMob mob) {
        String id = EcoLife.id(mob);
        for (EcoDens.Den d : EcoDens.DEN_SPECIES) if (d.id().equals(id)) return true;
        return false;
    }

    /** 洞の出入りのアニメの間、向き {@code dir} へその種の速さ × 体格だけ進める(当たりは無視。アニメの足が滑らない速さ)。 */
    static void stepInto(PathfinderMob mob, Direction dir) {
        double[] g = EcoLife.DEN_GAIT.get(EcoLife.id(mob));
        if (g == null) return;
        double v = g[1] * mob.getScale();
        mob.setPos(mob.getX() + dir.getStepX() * v, mob.getY(), mob.getZ() + dir.getStepZ() * v);
        mob.setDeltaMovement(Vec3.ZERO);
    }

    /** 洞を出た直後の距離(ブロック): 這い出るアニメの間に進む分だけ、入口より奥から始める。 */
    public static double exitLength(PathfinderMob mob) {
        double[] g = EcoLife.DEN_GAIT.get(EcoLife.id(mob));
        return g == null || EcoLife.clip(mob, EcoAct.EXIT_DEN) == null ? 0.0D : g[0] * g[1] * mob.getScale();
    }

    /** 洞から這い出る(exit_den を流しながら、入口の外へ進む)。 */
    public static final class ExitDenGoal extends Goal {
        final PathfinderMob mob;
        final EcoLife life;

        ExitDenGoal(PathfinderMob mob, EcoLife life) {
            this.mob = mob;
            this.life = life;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            return this.life.ecoMind().exiting > 0;
        }

        @Override
        public boolean canContinueToUse() {
            return this.life.ecoMind().exiting > 0;
        }

        @Override
        public void start() {
            this.mob.getNavigation().stop();
            this.mob.noPhysics = true;
            this.mob.setNoGravity(true);   // 当たりを無視する間は落ちないように
            this.life.ecoSetAct(EcoAct.EXIT_DEN);
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            EcoMind mind = this.life.ecoMind();
            this.mob.setYRot(mind.exitDir.toYRot());
            this.mob.setYBodyRot(this.mob.getYRot());
            this.mob.setYHeadRot(this.mob.getYRot());
            stepInto(this.mob, mind.exitDir);
            mind.exiting--;
        }

        @Override
        public void stop() {
            this.mob.noPhysics = false;
            this.mob.setNoGravity(false);
            this.life.ecoMind().exiting = 0;
            this.life.ecoSetAct(EcoAct.NONE);
        }
    }

    // ================================================================ 洞へ帰る

    /** 眠い時間になったら住む洞(無ければ 16 ブロック以内の入れる洞)へ帰って入る。入ると Mob は消え、洞の中に保存される。 */
    public static final class DenGoal extends Goal {
        final PathfinderMob mob;
        final EcoLife life;
        @Nullable
        BlockPos den;
        int time, entering;

        DenGoal(PathfinderMob mob, EcoLife life) {
            this.mob = mob;
            this.life = life;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (off(this.mob) || this.life.ecoBusy() || !every(this.mob, 20)) return false;
            if (!(this.mob.level() instanceof net.minecraft.server.level.ServerLevel sl) || !denDweller(this.mob)) return false;
            if (awake(this.mob, EcoLife.behavior(this.mob)) || com.yayuy.overgrownvegetation.entity.DevNames.has(this.mob, "noden")) return false;
            BlockPos home = this.life.ecoMind().home;
            if (home == null || !denUsable(sl, this.mob, home)) {
                home = EcoDens.findDen(sl, this.mob, 16);
                this.life.ecoMind().home = home;
            }
            this.den = home;
            return home != null;
        }

        @Override
        public boolean canContinueToUse() {
            return this.den != null && this.time > 0 && !this.life.ecoBusy();
        }

        @Override
        public void start() {
            this.time = 400;
            this.entering = -1;
            Vec3 m = EcoDenBlock.mouth(this.den, this.mob.level().getBlockState(this.den));
            this.mob.getNavigation().moveTo(m.x, m.y, m.z, 0.9D);
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            this.time--;
            BlockState st = this.mob.level().getBlockState(this.den);
            if (!st.is(EcoBlocks.DEN.get())) {
                this.life.ecoMind().home = null;
                this.den = null;
                return;
            }
            if (this.entering >= 0) {
                this.mob.getNavigation().stop();
                this.mob.setYRot(st.getValue(EcoDenBlock.FACING).getOpposite().toYRot());
                this.mob.setYBodyRot(this.mob.getYRot());
                stepInto(this.mob, st.getValue(EcoDenBlock.FACING).getOpposite());
                if (this.entering-- == 0 && !EcoDens.enter(this.mob, this.den)) {
                    this.life.ecoMind().home = null;
                    this.den = null;
                }
                return;
            }
            Vec3 m = EcoDenBlock.mouth(this.den, st);
            if (horiz(this.mob.position(), m) < 1.1D * 1.1D) {
                double[] g = EcoLife.DEN_GAIT.get(EcoLife.id(this.mob));
                this.entering = g != null && EcoLife.clip(this.mob, EcoAct.ENTER_DEN) != null ? (int) g[0] : 0;
                if (this.entering > 0) {
                    this.life.ecoSetAct(EcoAct.ENTER_DEN);
                    this.mob.moveTo(m.x, this.mob.getY(), m.z, st.getValue(EcoDenBlock.FACING).getOpposite().toYRot(), 0.0F);
                    this.mob.noPhysics = true;
                    this.mob.setNoGravity(true);   // 当たりを無視する間は落ちないように
                }
            } else if (horiz(this.mob.position(), m) < 3.0D * 3.0D) {
                // 入口のそば: 経路は「近くまで来た」で終わるので、最後は向きと速さを毎 tick 直に渡す(MoveControl の目標は 1 tick しか効かない)
                this.mob.getNavigation().stop();
                this.mob.getMoveControl().setWantedPosition(m.x, m.y, m.z, 0.9D);
            } else if (this.mob.getNavigation().isDone()) {
                if (!this.mob.getNavigation().moveTo(m.x, m.y, m.z, 0.9D)) this.time = 0;
            }
        }

        @Override
        public void stop() {
            this.mob.noPhysics = false;
            this.mob.setNoGravity(false);
            this.life.ecoSetAct(EcoAct.NONE);
            if (this.time <= 0 && this.den != null) this.life.ecoMind().home = null;   // 行けなかった洞は忘れる
            this.den = null;
        }
    }

    // ================================================================ 繭を作る(幼虫 → 成虫)

    /** 幼虫が育ち切るまで(2 日)【仮】。 */
    public static final long GROW_TICKS = 48000L;

    /** 育ち切った・満腹の幼虫は、近くの葉の下面に梢の繭を作って入る(11-18「羽化は繭を通す」)。 */
    public static final class PupateGoal extends Goal {
        final PathfinderMob mob;
        final EcoLife life;
        @Nullable
        BlockPos at;
        int time;

        PupateGoal(PathfinderMob mob, EcoLife life) {
            this.mob = mob;
            this.life = life;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (off(this.mob) || this.life.ecoBusy() || !every(this.mob, 100)) return false;
            long now = now(this.mob.level());
            EcoMind mind = this.life.ecoMind();
            if (mind.born == Long.MIN_VALUE) mind.born = now;
            if (now - mind.born < GROW_TICKS) return false;
            if (mind.fullness(now, EcoLife.behavior(this.mob), this.mob.getRandom()) < 0.5D) return false;
            this.at = cocoonSpot(this.mob);
            return this.at != null;
        }

        @Override
        public boolean canContinueToUse() {
            return this.at != null && this.time > 0;
        }

        @Override
        public void start() {
            this.mob.getNavigation().stop();
            boolean clip = EcoLife.clip(this.mob, EcoAct.COCOON) != null;
            this.time = clip ? 36 : 1;
            if (clip) {
                // アニメ担当 11-18: 葉の下面 − 0.32 の升の真ん中へ移してから流す(上下の反転は body 骨。体は回さない)
                BlockPos p = this.at;
                this.mob.moveTo(p.getX() + 0.5D, p.getY() + 1.0D - 0.32D, p.getZ() + 0.5D, this.mob.getYRot(), 0.0F);
                this.mob.setNoGravity(true);
                this.mob.noPhysics = true;
                this.mob.setDeltaMovement(Vec3.ZERO);
                this.life.ecoSetAct(EcoAct.COCOON);
            }
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            if (--this.time > 0) return;
            BlockPos p = this.at;
            this.at = null;
            if (!this.mob.level().getBlockState(p).isAir() || !this.mob.level().getBlockState(p.above()).is(BlockTags.LEAVES)) return;
            this.mob.level().setBlock(p, EcoBlocks.COCOON.get().defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
            this.mob.discard();
        }

        @Override
        public void stop() {
            this.life.ecoSetAct(EcoAct.NONE);
            this.mob.setNoGravity(false);
            this.mob.noPhysics = false;
        }
    }

    /** 繭を下げる所: まわり(横 4・上下 2)の葉のうち、すぐ下とその下が空気の所(葉の下面)。近い順。 */
    @Nullable
    public static BlockPos cocoonSpot(PathfinderMob mob) {
        Level level = mob.level();
        BlockPos base = mob.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -3; dy <= 1; dy++) {
                    p.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    if (!level.getBlockState(p).is(BlockTags.LEAVES)) continue;
                    BlockPos under = p.below();
                    if (!level.getBlockState(under).isAir() || !level.getBlockState(under.below()).isAir()) continue;
                    double d = under.distSqr(base);
                    if (d < bestD) {
                        bestD = d;
                        best = under.immutable();
                    }
                }
            }
        }
        return best;
    }

    // ================================================================ 守る(サソリ・ファランギオタルブス)

    /**
     * 洞を壊した人(EcoDenBlock が標的にする)と、住む洞の {@code nestRange} 以内に来たサバイバルのプレイヤーを襲う(11-16・11-18 ユーザー)。
     * 洞から 16 ブロック(洞が無ければ自分のいた所から)離れるか、400 tick で諦める。
     */
    public static final class DefendGoal extends Goal {
        final PathfinderMob mob;
        final EcoLife life;
        int time, cooldown;
        @Nullable
        Vec3 anchor;

        DefendGoal(PathfinderMob mob, EcoLife life) {
            this.mob = mob;
            this.life = life;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        static boolean fair(@Nullable LivingEntity e) {
            return e instanceof Player p && p.isAlive() && !p.isCreative() && !p.isSpectator();
        }

        @Override
        public boolean canUse() {
            if (off(this.mob) || this.life.ecoBusy()) return false;
            EcoBehavior b = EcoLife.behavior(this.mob);
            if (!b.defends) return false;
            if (fair(this.mob.getTarget())) return true;
            BlockPos home = this.life.ecoMind().home;
            if (b.nestRange <= 0.0D || home == null || !every(this.mob, 10)) return false;
            Player p = this.mob.level().getNearestPlayer(home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D, b.nestRange, false);
            if (!fair(p)) return false;
            this.mob.setTarget(p);
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            LivingEntity t = this.mob.getTarget();
            return fair(t) && this.time > 0 && this.anchor != null && t.position().distanceToSqr(this.anchor) < 16.0D * 16.0D;
        }

        @Override
        public void start() {
            this.time = 400;
            this.cooldown = 0;
            BlockPos home = this.life.ecoMind().home;
            this.anchor = home != null ? Vec3.atCenterOf(home) : this.mob.position();
            this.life.ecoSetAct(EcoAct.NONE);
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            this.time--;
            LivingEntity t = this.mob.getTarget();
            if (t == null) return;
            this.mob.getLookControl().setLookAt(t, 30.0F, 30.0F);
            double reach = this.mob.getBbWidth() * 0.5D + t.getBbWidth() * 0.5D + 0.9D;
            if (this.mob.distanceToSqr(t) <= reach * reach) {
                this.mob.getNavigation().stop();
                if (this.cooldown-- <= 0) {
                    this.cooldown = 20;
                    this.mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    this.mob.doHurtTarget(t);
                }
            } else if ((this.time & 7) == 0 || this.mob.getNavigation().isDone()) {
                this.mob.getNavigation().moveTo(t, 1.0D);
            }
        }

        @Override
        public void stop() {
            this.mob.setTarget(null);
            this.anchor = null;
        }
    }
}
