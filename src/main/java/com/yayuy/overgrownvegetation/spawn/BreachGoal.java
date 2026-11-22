package com.yayuy.overgrownvegetation.spawn;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.AssaultTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * 壁や扉を壊す(2026-11-16 F5 の襲撃。ユーザー決定: 壊せるのは木・ガラス・土と扉だけ = タグ {@code raid_breakable}。石・レンガ・鉄・設備は壊せない。
 * 壊すのは力のある種だけ = {@code spawns.json} の {@code breaker}(アルボラ・ファルキムネス・クリペイムネス・ヴェナムネス)。壊すのに時間がかかり、その間に叩ける)。
 *
 * <p>種のクラスには手を入れず、入るときに外から足す({@link PlantMarch#onJoin})。拠点へ歩いている間か、相手を追っている間に、
 * {@code breakStuckTicks} 進まなければ、進みたい向きの {@code breakReach} ブロック先(足と頭の高さ)にあるタグのブロックを、
 * {@code breakTicksBase + breakTicksPerHardness × 硬さ} tick かけて壊す(その場で止まる。叩かれても続ける)。落とし物は出さない。
 * {@code mobGriefing} が切れていれば壊さない(バニラと同じ)。BlockEntity を持つブロック(設備・入れ物)はタグにあっても壊さない。
 */
public final class BreachGoal extends Goal {

    public static final TagKey<Block> BREAKABLE = TagKey.create(net.minecraft.core.registries.Registries.BLOCK, OvergrownVegetation.id("raid_breakable"));

    /** 確かめ用: これまでに壊した数。 */
    public static long broken;

    private final PathfinderMob mob;
    @Nullable
    private BlockPos target;
    private int progress;
    private int total;
    private double lastX = Double.NaN, lastZ;
    private long lastTime;
    private int still;

    public BreachGoal(PathfinderMob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** そのブロックを襲撃で壊せるか。 */
    public static boolean breakable(BlockState s) {
        return s.is(BREAKABLE) && !s.hasBlockEntity() && s.getBlock().defaultDestroyTime() >= 0.0F;
    }

    /** 壊すのにかかる tick。 */
    public static int breakTicks(BlockState s) {
        AssaultTable a = DesignData.director().assault;
        return a.breakTicksBase + Math.round(a.breakTicksPerHardness * Math.max(0.0F, s.getBlock().defaultDestroyTime()));
    }

    @Override
    public boolean canUse() {
        if (!(mob.level() instanceof ServerLevel level) || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) return false;
        if (!com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.on(level.getServer(), com.yayuy.overgrownvegetation.difficulty.DifficultySetting.RAID_BREACH)) return false; // MOD の設定「襲撃で壁や地形を壊すか」(11-19)
        if (!raiding(level)) { // 見直し(疑い): 拠点のまわりの襲撃の間だけ壊す。ほかの所で追っていて詰まっても、斜面や木は掘らない
            still = 0;
            lastX = Double.NaN;
            return false;
        }
        Vec3 goal = destination();
        if (goal == null) {
            still = 0;
            lastX = Double.NaN;
            return false;
        }
        long now = level.getGameTime();
        if (Double.isNaN(lastX)) {
            lastX = mob.getX();
            lastZ = mob.getZ();
            lastTime = now;
            return false;
        }
        double dx = mob.getX() - lastX, dz = mob.getZ() - lastZ;
        int dt = (int) Math.max(1L, now - lastTime);
        lastX = mob.getX();
        lastZ = mob.getZ();
        lastTime = now;
        if (dx * dx + dz * dz > 0.01D * dt * dt) {
            still = 0;
            return false;
        }
        still += dt;
        if (still < DesignData.director().assault.breakStuckTicks) return false;
        target = find(level, goal);
        return target != null;
    }

    /** 壊してよい時か: 拠点へ歩いている・襲撃の印がある個体で、認識した拠点の真ん中から {@link #BASE_RANGE} の中。 */
    private boolean raiding(ServerLevel level) {
        boolean raider = PlantMarch.active(mob) || mob.getTags().contains(com.yayuy.overgrownvegetation.director.HoardRaids.TAG)
                || mob.getTags().contains(com.yayuy.overgrownvegetation.director.LargeRaids.ATTACKER_TAG);
        if (!raider) return false;
        for (com.yayuy.overgrownvegetation.director.BaseMemory.Base b : com.yayuy.overgrownvegetation.director.BaseMemory.get(level.getServer()).bases()) {
            if (b.stage() != com.yayuy.overgrownvegetation.director.BaseMemory.Stage.RECOGNIZED) continue;
            double dx = b.centerX() - mob.getX(), dz = b.centerZ() - mob.getZ();
            if (dx * dx + dz * dz <= BASE_RANGE * BASE_RANGE) return true;
        }
        return breakAnywhereForTests;
    }

    /** 拠点の真ん中からこの半径の中でだけ壊す(ブロック)【仮】。 */
    public static final double BASE_RANGE = 48.0D;

    /** テスト用: 拠点が無くても壊す(テスト場の壁のテスト)。 */
    public static boolean breakAnywhereForTests;

    /** 進みたい所: 追っている相手(近すぎれば無し = 戦っている)、なければ拠点への行き先。 */
    @Nullable
    private Vec3 destination() {
        LivingEntity t = mob.getTarget();
        if (t != null && t.isAlive()) return mob.distanceToSqr(t) < 9.0D ? null : t.position();
        return PlantMarch.target(mob);
    }

    @Nullable
    private BlockPos find(ServerLevel level, Vec3 goal) {
        double dx = goal.x - mob.getX(), dz = goal.z - mob.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3D) return null;
        dx /= len;
        dz /= len;
        double half = mob.getBbWidth() * 0.5D;
        int heights = Math.max(2, (int) Math.ceil(mob.getBbHeight()));
        double reach = DesignData.director().assault.breakReach;
        for (double s = 0.3D; s <= reach + 1.0E-6D; s += 0.5D) {
            for (int y = 0; y < Math.min(heights, 3); y++) {
                BlockPos p = BlockPos.containing(mob.getX() + dx * (half + s), mob.getY() + y + 0.1D, mob.getZ() + dz * (half + s));
                if (breakable(level.getBlockState(p))) return p;
            }
        }
        return null;
    }

    @Override
    public void start() {
        progress = 0;
        total = target == null ? 1 : breakTicks(mob.level().getBlockState(target));
        mob.getNavigation().stop();
    }

    @Override
    public boolean canContinueToUse() {
        if (target == null || progress >= total) return false;
        if (!breakable(mob.level().getBlockState(target))) return false;
        double reach = DesignData.director().assault.breakReach + mob.getBbWidth() + 1.5D;
        return mob.distanceToSqr(Vec3.atCenterOf(target)) < reach * reach;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (target == null) return;
        mob.getLookControl().setLookAt(Vec3.atCenterOf(target));
        mob.getNavigation().stop();
        progress++;
        if (progress % 20 == 1) mob.level().levelEvent(1019, target, 0); // 木の扉を叩く音(バニラのゾンビと同じ)
        mob.level().destroyBlockProgress(mob.getId(), target, Math.min(9, progress * 10 / Math.max(1, total)));
        if (progress >= total) {
            mob.level().destroyBlock(target, false, mob);
            broken++;
        }
    }

    @Override
    public void stop() {
        if (target != null) mob.level().destroyBlockProgress(mob.getId(), target, -1);
        target = null;
        still = 0;
        lastX = Double.NaN;
    }
}
