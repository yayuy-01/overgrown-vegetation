package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.entity.g1.G1StrikeGoal;
import com.yayuy.overgrownvegetation.entity.g1.G1TargetGoal;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * エンダーマンの 1 段目の (c-2)「根が地面から突き上がる」(2026-11-13、能力の第 4 回。仕様 Vault「G1 エンダーマン(1 段目)」3 節【決定 11-09・数は仮】)。
 * 根の左腕を頭より高く振り上げ(前触れ {@value #WINDUP} tick)、地面に叩きつける。周り(半径 {@value #RADIUS})の<b>汚染した地面</b>から根が突き上がって刺し、
 * 少し浮かせる。<b>汚染していない地面からは出ない</b>(汚染していない地面へ下がればよけられる)【決定 11-09】。
 *
 * <ul>
 *   <li>使う条件: 見えている標的が半径 {@value #RADIUS} 以内にいて、その足元が汚染した地面(当たらない所では使わない【仮】)・根の技の間が明けている。
 *   休み {@value #COOLDOWN} tick。</li>
 *   <li>段: slam_windup {@value #WINDUP} → slam 3(1 tick 目に叩く)→ slam_recover 14。向きは追わない(周り全部に効く)。</li>
 *   <li>前触れの {@value #SHAKE_TICK} tick 目に、周りの汚染した地面が細かく揺れて粒が噴く(<b>どこから出るかが見える</b>)。根が出るのはその時に決めたマス。</li>
 *   <li>当たるのは、根が出るマスの上 {@value #REACH_UP} ブロックまでにいる相手(跳べば避けられる): {@value #DAMAGE} ダメージ(盾で防げる)+ 当てたら暴露量 +300(共通)+
 *   上へ {@value #LIFT}。根の見た目は短命の実体 {@link G1RootSpike}({@value #MAX_SPIKES} 本まで)。</li>
 * </ul>
 */
public class G1RootSlamGoal extends G1TechniqueGoal {

    public static final double RADIUS = 4.0D;
    public static final int WINDUP = 14;
    static final int SHAKE_TICK = 8;
    public static final float DAMAGE = 6.0F;
    public static final double LIFT = 0.4D;
    static final double REACH_UP = 1.0D;
    public static final int COOLDOWN = 240;
    static final int MAX_SPIKES = 14;

    /** 根が出るマス(汚染した地面。前触れで決める)。 */
    private final List<BlockPos> cells = new ArrayList<>();
    private int hits;

    public G1RootSlamGoal(DevelopedParasitized mob) {
        super(mob, new G1Move[]{G1Move.SLAM_WINDUP, G1Move.SLAM, G1Move.SLAM_RECOVER}, new int[]{WINDUP, 3, 14});
    }

    @Override
    protected boolean ready() {
        if (!mob.rootSkillReady() || target == null || !target.isAlive() || !mob.canAttack(target)) return false;
        if (mob.distanceTo(target) > RADIUS || !mob.getSensing().hasLineOfSight(target)) return false;
        return mob.level() instanceof ServerLevel level && groundUnder(level, target) != null;
    }

    @Override
    protected int aimUntil() {
        return 0; // 周り全部に効くので、向きは追わない
    }

    @Override
    protected int cooldown() {
        return COOLDOWN;
    }

    @Override
    public void start() {
        super.start();
        cells.clear();
        hits = 0;
    }

    @Override
    public void stop() {
        super.stop();
        mob.lockRootSkills(G1GrabGoal.ROOT_LOCKOUT);
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (stageIndex == 0 && tickInStage == SHAKE_TICK) {
            // 周りの汚染した地面が細かく揺れて粒が噴く(どこから出るかが見える)
            collectCells(level);
            for (BlockPos p : cells) {
                BlockState s = level.getBlockState(p);
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, s), p.getX() + 0.5D, p.getY() + 1.05D, p.getZ() + 0.5D,
                        3, 0.25D, 0.02D, 0.25D, 0.02D);
            }
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ROOTED_DIRT_BREAK, SoundSource.HOSTILE, 0.8F, 0.5F);
        } else if (stageIndex == 1 && tickInStage == 1) {
            slam(level);
        }
    }

    private void slam(ServerLevel level) {
        if (cells.isEmpty()) collectCells(level);
        // 叩きつけ: 腕の先の地面
        Vec3 face = Vec3.directionFromRotation(0.0F, lockedYaw);
        level.playSound(null, mob.getX() + face.x, mob.getY(), mob.getZ() + face.z, SoundEvents.MACE_SMASH_GROUND, SoundSource.HOSTILE, 0.8F, 0.6F);
        // 当たる相手
        List<BlockPos> spikes = new ArrayList<>();
        AABB box = mob.getBoundingBox().inflate(RADIUS + 1.0D, 3.0D, RADIUS + 1.0D);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (!G1TargetGoal.isPrey(mob, e) || horizontal(e) > RADIUS) continue;
            BlockPos ground = groundUnder(level, e);
            if (ground == null || !cells.contains(ground)) continue;
            spikes.add(ground);
            if (e.hurt(mob.damageSources().mobAttack(mob), DAMAGE)) {
                hits++;
                SporeExposureTracker.addDose(e, G1StrikeGoal.MOB_DOSE);
                Vec3 v = e.getDeltaMovement();
                e.setDeltaMovement(v.x, LIFT, v.z);
                e.hurtMarked = true;
            }
        }
        // 根の見た目: 当たったマス + 残りの汚染したマスから何本か
        List<BlockPos> rest = new ArrayList<>(cells);
        rest.removeAll(spikes);
        Collections.shuffle(rest, new java.util.Random(mob.getRandom().nextLong()));
        for (BlockPos p : rest) {
            if (spikes.size() >= MAX_SPIKES) break;
            spikes.add(p);
        }
        for (BlockPos p : spikes) {
            G1RootSpike.spawn(level, p);
        }
        if (!spikes.isEmpty()) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ROOTS_BREAK, SoundSource.HOSTILE, 1.0F, 0.6F);
        }
        mob.countSlam(spikes.size(), hits);
    }

    private double horizontal(LivingEntity e) {
        double dx = e.getX() - mob.getX(), dz = e.getZ() - mob.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 半径の中の「汚染した地面で、上が空いているマス」を集める(列ごとに、体の高さの少し上から下へ見て最初の地面)。 */
    private void collectCells(ServerLevel level) {
        cells.clear();
        int r = Mth.ceil(RADIUS);
        BlockPos feet = mob.blockPosition();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > RADIUS * RADIUS) continue;
                for (int dy = 1; dy >= -3; dy--) {
                    p.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (!level.isLoaded(p)) break;
                    BlockState s = level.getBlockState(p);
                    if (s.getCollisionShape(level, p).isEmpty()) continue;
                    // 最初の地面
                    if (OvergrownLayerData.isCorruptedCell(level, p, s) && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) {
                        cells.add(p.immutable());
                    }
                    break;
                }
            }
        }
    }

    /** {@code e} の足元の地面が汚染していて、足がその上 {@value #REACH_UP} ブロックまでにあれば、その地面のマス。 */
    @Nullable
    public static BlockPos groundUnder(ServerLevel level, LivingEntity e) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int x = Mth.floor(e.getX()), z = Mth.floor(e.getZ());
        for (int y = Mth.floor(e.getY() + 0.01D); y >= Mth.floor(e.getY() - REACH_UP - 0.01D); y--) {
            p.set(x, y, z);
            BlockState s = level.getBlockState(p);
            if (s.getCollisionShape(level, p).isEmpty()) continue;
            double top = y + 1.0D;
            if (e.getY() - top > REACH_UP) return null;
            return OvergrownLayerData.isCorruptedCell(level, p, s) ? p.immutable() : null;
        }
        return null;
    }

    /** 最後の叩きつけで当たった数(GameTest・確かめ用)。 */
    public int hitsLast() {
        return hits;
    }
}
