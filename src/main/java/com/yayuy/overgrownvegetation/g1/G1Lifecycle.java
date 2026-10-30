package com.yayuy.overgrownvegetation.g1;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corpse.RisingMob;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.G1Table;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedFlyer;
import com.yayuy.overgrownvegetation.phase.CorruptionCounter;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.EnumSet;

/**
 * G1 の一生(G1 の作り直し 第 1 段、2026-10-22。仕様の正本は Vault「G1 媒介種(寄生された動物)」の追記の【決定】【案】)。
 *
 * <pre>
 * 胞子感染 → 死体(10 分、胞子を出す)→ 起き上がる → 段階 0(周りの Mob とプレイヤーを襲う)
 *   → 1 日(g1.json life.branchAfterDays)で司令塔({@link ForestDirector})に分岐を聞く
 *      ├ 根付き: その場で動かなくなり、汚染シダと同じ {@link CorruptionSpreader} で周りを汚染 → 終わったら「根付いた寄生体」のブロック
 *      ├ 苗床: 湿った暗い場所まで歩き → 「苗床」のブロック(時間ごとに萌芽体を 1 体生む)
 *      └ 進化: 1 段目まで 2 日 → 2 段目までさらに 5 日({@link G1Evolutions}。今は 4 種だけ)
 * </pre>
 *
 * 対象は G1 のタグ(group_1)にいる寄生体のうち、オーバーワールドで地上を歩くもの(ガスト・ブレイズは除く。仮決め:
 * 胞子の場はオーバーワールドだけなので、ネザー・エンドの G1 は段階 0 のまま)。時計は読み込まれている間だけ進み、個体に保存する。
 * 一生が始まった個体は遠くへ離れても消えない(1 日待つため。仮決め)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class G1Lifecycle {

    /** 判定の間隔(tick)。時計はこの分ずつ進む。 */
    public static final int STEP = 20;

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof ParasitizedMob) || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (!(mob.level() instanceof ServerLevel level) || !isSubject(level, mob)) {
            return;
        }
        G1Life life = mob.getData(ModAttachments.G1_LIFE);
        if (!life.applied) {
            life.applied = true;
            mob.setPersistenceRequired();
            if (life.route == G1Life.Route.NONE && G1Evolutions.stageOf(mob.getType()) == 1) {
                life.route = G1Life.Route.EVOLVE; // 1 段目(卵・コマンドで出したものも)は進化の途中
            }
            applyRoute(mob, life);
        }
        if (life.route == G1Life.Route.ROOTED) {
            tickRooted(level, mob, life); // 汚染は毎 tick 見る(汚染シダと同じ間隔の位相)
        }
        if (Math.floorMod(mob.tickCount + mob.getId(), STEP) != 0) {
            return;
        }
        step(level, mob, life, STEP);
    }

    /** この Mob に一生があるか。 */
    public static boolean isSubject(ServerLevel level, Mob mob) {
        return level.dimension() == Level.OVERWORLD
                && mob.isAlive()
                && mob instanceof ParasitizedMob
                && !(mob instanceof AbstractParasitizedFlyer)
                && mob.getType().is(ModTags.GROUP_1)
                && !(mob instanceof RisingMob rising && rising.isRising());
    }

    /** 時計を {@code ticks} 進めて、道ごとの処理をする(テストとコマンドからも呼ぶ)。 */
    public static void step(ServerLevel level, Mob mob, G1Life life, long ticks) {
        G1Table g = DesignData.g1();
        switch (life.route) {
            case NONE -> {
                life.age += ticks;
                if (life.age >= (long) (g.branchAfterDays * 24000.0D)) {
                    decide(level, mob, life);
                }
            }
            case ROOTED -> {
                if (CorruptionSwitch.isOff()) {
                    return; // /ov corruption false: 汚染していないのにブロックになってしまわないよう、時計も止める
                }
                life.routeAge += ticks;
                if (life.routeAge >= g.rootedMaxTicks || (life.spreader != null && life.spreader.isFinished())) {
                    finishRooted(level, mob);
                }
            }
            case NURSERY -> {
                life.routeAge += ticks;
                tickNursery(level, mob, life, g);
            }
            case EVOLVE -> {
                life.routeAge += ticks;
                int stage = G1Evolutions.stageOf(mob.getType());
                double days = stage == 1 ? g.evolve2Days : g.evolve1Days;
                if (life.routeAge >= (long) (days * 24000.0D)) {
                    evolve(level, mob, life);
                }
            }
        }
    }

    /** 司令塔に分岐を聞いて道を決める。 */
    public static ForestDirector.Decision decide(ServerLevel level, Mob mob, G1Life life) {
        ForestDirector.Decision d = ForestDirector.get(level.getServer()).decide(level, mob);
        life.route = d.route();
        life.routeAge = 0;
        life.nurseryTarget = d.nurseryTarget();
        life.note = d.summary();
        applyRoute(mob, life);
        return d;
    }

    /** 道を強いる(コマンド・テスト用)。苗床の行き先が無ければ探し、無ければ根付き。 */
    public static void force(ServerLevel level, Mob mob, G1Life life, G1Life.Route route) {
        life.route = route;
        life.routeAge = 0;
        if (route == G1Life.Route.NURSERY && life.nurseryTarget == null) {
            life.nurseryTarget = MoistDarkPlaces.find(level, mob.blockPosition(), DesignData.g1().nurserySearchRadius, DesignData.g1(), mob.getRandom());
            if (life.nurseryTarget == null) life.nurseryTarget = mob.blockPosition();
        }
        if (route == G1Life.Route.EVOLVE && G1Evolutions.next(mob.getType()) == null) {
            life.route = G1Life.Route.ROOTED;
        }
        life.note = "強いた: " + ForestDirector.routeName(life.route);
        applyRoute(mob, life);
    }

    // ---------------------------------------------------------------- 道ごとの Goal

    /** 道に合わせて Goal を付ける(Goal は保存されないので、読み込みのたびに付け直す)。 */
    static void applyRoute(Mob mob, G1Life life) {
        switch (life.route) {
            case ROOTED -> {
                mob.goalSelector.removeAllGoals(goal -> true);
                mob.targetSelector.removeAllGoals(goal -> true);
                mob.goalSelector.addGoal(0, new HoldStillGoal(mob));
                mob.setTarget(null);
                mob.getNavigation().stop();
            }
            case NURSERY -> {
                mob.targetSelector.removeAllGoals(goal -> true);
                mob.setTarget(null);
                mob.goalSelector.addGoal(0, new WalkToNurseryGoal(mob, life));
            }
            default -> {
            }
        }
    }

    /** 根付いた G1: その場から動かない(MOVE・LOOK・JUMP を握り続ける)。 */
    static final class HoldStillGoal extends Goal {
        private final Mob mob;

        HoldStillGoal(Mob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            return true;
        }

        @Override
        public void tick() {
            mob.getNavigation().stop();
        }
    }

    /** 苗床へ歩く G1: 行き先へ歩き続ける(経路は Mob のナビ。詰まったら時間切れでその場に苗床)。 */
    static final class WalkToNurseryGoal extends Goal {
        private final Mob mob;
        private final G1Life life;
        private int repath;

        WalkToNurseryGoal(Mob mob, G1Life life) {
            this.mob = mob;
            this.life = life;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return life.route == G1Life.Route.NURSERY && life.nurseryTarget != null;
        }

        @Override
        public void tick() {
            if (--repath <= 0) {
                repath = 40;
                BlockPos t = life.nurseryTarget;
                mob.getNavigation().moveTo(t.getX() + 0.5D, t.getY(), t.getZ() + 0.5D, 1.0D);
            }
        }
    }

    // ---------------------------------------------------------------- 根付き

    private static void tickRooted(ServerLevel level, Mob mob, G1Life life) {
        if (CorruptionSwitch.isOff()) {
            return; // /ov corruption false: 汚染も待つ
        }
        CorruptionSpreader spreader = life.spreader();
        if (spreader.isFinished()) {
            return;
        }
        BlockPos pos = mob.blockPosition();
        spreader.seedGround(level, pos);
        int interval = OvergrownVegetationConfig.COMMON.tickInterval.get();
        int phase = Math.floorMod(mob.getUUID().hashCode(), interval);
        if ((mob.tickCount + phase) % interval == 0) {
            int before = spreader.getCorruptedCount();
            int radius = DesignData.g1().rootedRadius;
            if (radius > 0) {
                spreader.tick(level, pos, radius, 1);
            } else {
                spreader.tick(level, pos); // 汚染シダと同じ半径
            }
            CorruptionCounter.record(level, spreader.getCorruptedCount() - before);
        }
    }

    /** 根付き終わり: その場に「根付いた寄生体」を置いて消える。 */
    public static void finishRooted(ServerLevel level, Mob mob) {
        placeAndVanish(level, mob, ModBlocks.ROOTED_PARASITE.get().defaultBlockState(), mob.blockPosition());
    }

    // ---------------------------------------------------------------- 苗床

    private static void tickNursery(ServerLevel level, Mob mob, G1Life life, G1Table g) {
        BlockPos target = life.nurseryTarget;
        boolean arrived = target != null && mob.blockPosition().distSqr(target) <= 2.5D;
        if (!arrived && life.routeAge < g.nurseryWalkTimeoutTicks) {
            return;
        }
        if (CorruptionSwitch.isOff()) {
            return; // 苗床になるのも待つ
        }
        BlockPos at = arrived ? target : mob.blockPosition();
        placeAndVanish(level, mob, ModBlocks.PARASITE_NURSERY.get().defaultBlockState(), at);
    }

    /** {@code state} を {@code at}(置けなければ足元)に置き、胞子をふわっと弾けさせて Mob を消す。 */
    private static void placeAndVanish(ServerLevel level, Mob mob, BlockState state, BlockPos at) {
        BlockPos place = canPlace(level, at, state) ? at : canPlace(level, mob.blockPosition(), state) ? mob.blockPosition() : null;
        if (place != null) {
            level.setBlock(place, state, 3);
        }
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, mob.getX(), mob.getY() + 0.8D, mob.getZ(), 40, 0.6D, 0.8D, 0.6D, 0.02D);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.MOSS_PLACE, mob.getSoundSource(), 1.0F, 0.7F);
        mob.discard();
    }

    private static boolean canPlace(ServerLevel level, BlockPos pos, BlockState state) {
        return level.getBlockState(pos).canBeReplaced() && state.canSurvive(level, pos);
    }

    // ---------------------------------------------------------------- 進化

    /** 次の段の種へ入れ替える(体力の割合・向き・名前は引き継ぐ。一生は進化の途中、今の段の時計は 0 から)。 */
    public static Mob evolve(ServerLevel level, Mob mob, G1Life life) {
        EntityType<? extends Mob> next = G1Evolutions.next(mob.getType());
        if (next == null) {
            life.route = G1Life.Route.ROOTED;
            applyRoute(mob, life);
            return mob;
        }
        float ratio = mob.getHealth() / Math.max(1.0F, mob.getMaxHealth());
        Mob evolved = mob.convertTo(next, true);
        if (evolved == null) {
            return mob;
        }
        evolved.setHealth(Math.max(1.0F, evolved.getMaxHealth() * ratio));
        evolved.yBodyRot = mob.yBodyRot;
        evolved.yHeadRot = mob.yHeadRot;
        evolved.setPersistenceRequired();
        G1Life n = evolved.getData(ModAttachments.G1_LIFE);
        n.route = G1Life.Route.EVOLVE;
        n.routeAge = 0;
        n.age = life.age;
        n.note = life.note;
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, evolved.getX(), evolved.getY() + 1.0D, evolved.getZ(), 60, 0.8D, 1.0D, 0.8D, 0.02D);
        level.playSound(null, evolved.getX(), evolved.getY(), evolved.getZ(), SoundEvents.MOSS_BREAK, evolved.getSoundSource(), 1.2F, 0.6F);
        return evolved;
    }

    // ---------------------------------------------------------------- 倒された G1 を司令塔が覚える

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity().level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (event.getEntity().getType().is(ModTags.GROUP_1) && event.getEntity() instanceof ParasitizedMob) {
            ForestDirector.get(level.getServer()).recordKill(level, new ChunkPos(event.getEntity().blockPosition()));
        }
    }

    private G1Lifecycle() {
    }
}
