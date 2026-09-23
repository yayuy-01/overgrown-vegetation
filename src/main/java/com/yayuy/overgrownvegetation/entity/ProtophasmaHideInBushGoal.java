package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.util.ChunkCachedReader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * 「プロトファスマ」が、対応するグループ3の捕食者({@link Predation})に追われている際、近くの茂み
 * ({@link ModTags#CONCEALING})に潜り込んでうずくまり、やり過ごそうとするゴール。
 *
 * <p><b>あくまで挙動のみ</b> — {@link com.yayuy.overgrownvegetation.effect.FoliageConcealment} の
 * 茂み隠密は「プレイヤー専用」なので、これで実際に捕食者のターゲットが切れるわけではない。捕食者が
 * 諦めずに茂みへ突っ込んでくれば普通に捕まる。虫が「隠れれば見つからないはず」と信じて縮こまる、
 * という見た目上の行動を足すだけ(ユーザー要望 2026-09-09)。
 *
 * <p>優先度は {@link net.minecraft.world.entity.ai.goal.PanicGoal} と同じ 1 に積む
 * ({@link ProtophasmaEntity#registerSpeciesGoals()})。到達できる茂みが近くにある限り、
 * ただ逃げ回る {@link CritterAvoidPredatorGoal}(優先度 2)より隠れる方を選ぶ。殴られた瞬間
 * ({@link Mob#getLastHurtByMob()})は隠れるのをやめてパニック逃走に譲る。
 */
public class ProtophasmaHideInBushGoal extends Goal {

    private static final double PREDATOR_SEARCH_RADIUS = 12.0D;
    private static final double PREDATOR_LOSE_RADIUS = 20.0D;
    private static final int BUSH_SEARCH_RADIUS_XZ = 9;
    private static final int BUSH_SEARCH_RADIUS_Y = 4;
    private static final float MOVE_SPEED = 1.4F;
    /** これ以上隠れ続けても捕食者が去らなければ、観念して逃走に切り替える。 */
    private static final int MAX_HIDE_TICKS = 300;
    private static final int RESCAN_INTERVAL = 20;

    private final ProtophasmaEntity mob;
    private final PathNavigation navigation;
    private LivingEntity predator;
    private BlockPos hidePos;
    private int hideTicks;
    private int nextScanTime;
    private boolean hidden;

    public ProtophasmaHideInBushGoal(ProtophasmaEntity mob) {
        this.mob = mob;
        this.navigation = mob.getNavigation();
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (this.mob.tickCount < this.nextScanTime) {
            return false;
        }
        this.nextScanTime = this.mob.tickCount + RESCAN_INTERVAL;
        if (this.mob.getLastHurtByMob() != null) {
            // 殴られている最中はパニック逃走に任せる。
            return false;
        }
        this.predator = findPredator();
        if (this.predator == null) {
            return false;
        }
        this.hidePos = findBush(this.predator);
        return this.hidePos != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.predator == null || !this.predator.isAlive() || this.hidePos == null) {
            return false;
        }
        if (this.mob.getLastHurtByMob() != null) {
            return false;
        }
        if (this.mob.distanceToSqr(this.predator) > PREDATOR_LOSE_RADIUS * PREDATOR_LOSE_RADIUS) {
            return false;
        }
        if (!this.mob.level().getBlockState(this.hidePos).is(ModTags.CONCEALING)) {
            return false;
        }
        if (this.hidden) {
            return this.hideTicks < MAX_HIDE_TICKS;
        }
        return !this.navigation.isDone() || this.mob.blockPosition().closerToCenterThan(
                Vec3.atCenterOf(this.hidePos), 1.6D);
    }

    @Override
    public void start() {
        this.hidden = false;
        this.hideTicks = 0;
        this.navigation.moveTo(this.hidePos.getX() + 0.5D, this.hidePos.getY(), this.hidePos.getZ() + 0.5D, MOVE_SPEED);
    }

    @Override
    public void stop() {
        this.predator = null;
        this.hidePos = null;
        this.hidden = false;
        this.hideTicks = 0;
        this.navigation.stop();
        this.mob.setActionState(AbstractOvergrownCritterEntity.STATE_IDLE);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (this.hidePos == null) {
            // tick() 内で経路を張り直せず hidePos を捨てた直後、cleanup が走らない奇数tickで
            // もう一度 tick() が呼ばれることがある。canContinueToUse で次の偶数tickに終了する。
            return;
        }
        boolean insideBush = this.mob.level().getBlockState(this.mob.blockPosition()).is(ModTags.CONCEALING);
        boolean atSpot = insideBush || this.mob.blockPosition().equals(this.hidePos)
                || this.mob.blockPosition().closerToCenterThan(Vec3.atCenterOf(this.hidePos), 1.1D);

        if (!this.hidden) {
            if (atSpot) {
                this.hidden = true;
                this.navigation.stop();
                this.mob.setActionState(AbstractOvergrownCritterEntity.STATE_HIDE);
            } else if (this.navigation.isDone()) {
                // 経路が切れた — 茂みまで辿り着けないので張り直す。
                this.navigation.moveTo(this.hidePos.getX() + 0.5D, this.hidePos.getY(),
                        this.hidePos.getZ() + 0.5D, MOVE_SPEED);
                if (this.navigation.isDone()) {
                    this.hidePos = null; // canContinueToUse で終了させる
                }
            }
            return;
        }

        // うずくまってじっとしている。捕食者の方をチラ見しつつ動かない。
        this.navigation.stop();
        Vec3 v = this.mob.getDeltaMovement();
        this.mob.setDeltaMovement(v.x * 0.6D, v.y, v.z * 0.6D);
        if (this.predator != null) {
            this.mob.getLookControl().setLookAt(this.predator.getX(),
                    this.predator.getEyeY(), this.predator.getZ());
        }
        this.hideTicks++;
    }

    private LivingEntity findPredator() {
        List<Mob> predators = this.mob.level().getEntitiesOfClass(Mob.class,
                this.mob.getBoundingBox().inflate(PREDATOR_SEARCH_RADIUS),
                candidate -> candidate.isAlive() && Predation.isPredatorOf(candidate, this.mob));
        // 一番近い捕食者(同距離なら先に見つかった方)。stream().min() と同じ選び方。
        Mob nearest = null;
        double nearestSq = Double.MAX_VALUE;
        for (Mob candidate : predators) {
            double distSq = this.mob.distanceToSqr(candidate);
            if (distSq < nearestSq) {
                nearestSq = distSq;
                nearest = candidate;
            }
        }
        return nearest;
    }

    /**
     * 捕食者から見て自分より遠ざかる(少なくとも近づかない)側にある、到達可能な茂みブロックの中で
     * 自分に一番近いものを探す。
     */
    private BlockPos findBush(LivingEntity threat) {
        BlockPos origin = this.mob.blockPosition();
        double mobToThreatSq = this.mob.distanceToSqr(threat);
        List<BlockPos> candidates = new ArrayList<>();
        // 19×19×9 ≒ 3200 マスの読み。チャンクキャッシュ付きの読み(結果は Level#getBlockState と同じ)。
        ChunkCachedReader reader = new ChunkCachedReader(this.mob.level());
        for (int dx = -BUSH_SEARCH_RADIUS_XZ; dx <= BUSH_SEARCH_RADIUS_XZ; dx++) {
            for (int dz = -BUSH_SEARCH_RADIUS_XZ; dz <= BUSH_SEARCH_RADIUS_XZ; dz++) {
                for (int dy = -BUSH_SEARCH_RADIUS_Y; dy <= BUSH_SEARCH_RADIUS_Y; dy++) {
                    int x = origin.getX() + dx;
                    int y = origin.getY() + dy;
                    int z = origin.getZ() + dz;
                    if (!reader.getBlockState(x, y, z).is(ModTags.CONCEALING)) {
                        continue;
                    }
                    double bushToThreatSq = threat.distanceToSqr(x + 0.5D, y + 0.5D, z + 0.5D);
                    if (bushToThreatSq + 1.0D < mobToThreatSq) {
                        // 捕食者に近づく側の茂みには飛び込まない。
                        continue;
                    }
                    candidates.add(new BlockPos(x, y, z));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
        for (BlockPos candidate : candidates) {
            if (this.navigation.createPath(candidate, 0) != null) {
                return candidate;
            }
        }
        return null;
    }
}
