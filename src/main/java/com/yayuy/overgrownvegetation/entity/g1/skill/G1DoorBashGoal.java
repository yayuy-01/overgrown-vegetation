package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * ゾンビの 1 段目の (b-1)「戸口を破る」(2026-11-12。仕様 Vault「G1 ゾンビ(1 段目)」3 節【決定 11-09 ユーザー「扉を叩き割る」・数は【仮】】)。
 * 標的が扉の向こうにいると、木の扉・木のトラップドア・柵の扉を根腕で叩き続けて壊す(難易度に関係なく)。
 *
 * <ul>
 *   <li>見つけ方: 経路の次の 2 マスに閉じた木の扉(バニラの {@code DoorInteractGoal} と同じ。ゾンビの navigation は扉を通る経路を引く)、
 *   または標的までの視線が {@value #REACH} ブロック以内の閉じた木の扉・トラップドア・柵の扉で遮られている
 *   (トラップドア・柵の扉は経路に出ないので、視線で拾う)。</li>
 *   <li>1 回 = door_bash_windup 20 + door_bash 20 tick(根腕を振り上げて叩く)。叩くたびに低い音・ひび({@code destroyBlockProgress})・
 *   扉の隙間から根の粒。{@value #HITS} 回で壊れる(8 秒)。</li>
 *   <li>壊したら: 扉は落とさず消え、戸口の周り(半径 {@value #CORRUPT_RADIUS})を汚染する(屋根の下へ胞子が入る口になる)。</li>
 *   <li>鉄の扉は壊せない(対処)。叩いている間に倒せば止まる。</li>
 * </ul>
 * 「扉の跡に根を残す」は、戸口に固いブロックを置くと入口を塞ぎ直すので、床と枠の汚染だけにした【仮・問い】。数値はすべて【仮】(このクラスの定数)。
 */
public class G1DoorBashGoal extends Goal {

    static final int WINDUP = 20;
    static final int BASH = 20;
    /** 叩いた瞬間(door_bash の何 tick 目)。【仮】 */
    static final int HIT_AT = 3;
    static final int HITS = 4;
    static final double REACH = 2.2D;
    static final int CORRUPT_RADIUS = 1;

    private final DevelopedParasitized mob;
    @Nullable
    private BlockPos door;
    private int t;
    private int hits;

    public G1DoorBashGoal(DevelopedParasitized mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** 叩いて壊せる戸口か(閉じた木の扉・木のトラップドア・柵の扉)。 */
    public static boolean bashable(BlockState s) {
        boolean kind = s.is(BlockTags.WOODEN_DOORS) || s.is(BlockTags.WOODEN_TRAPDOORS) || s.is(BlockTags.FENCE_GATES);
        return kind && s.hasProperty(BlockStateProperties.OPEN) && !s.getValue(BlockStateProperties.OPEN);
    }

    @Override
    public boolean canUse() {
        if (mob.action() != AbstractParasitizedMonster.ACTION_NONE || mob.isStunned()) return false;
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return false;
        door = findDoor(target);
        return door != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (door == null) return false; // 壊した
        if (t > 0 && t < WINDUP + BASH) return true; // 振り出した 1 回は最後まで
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive() && bashable(mob.level().getBlockState(door))
                && mob.distanceToSqr(Vec3.atCenterOf(door)) <= (REACH + 1.0D) * (REACH + 1.0D);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        t = 0;
        hits = 0;
        mob.getNavigation().stop();
    }

    @Override
    public void stop() {
        if (door != null) {
            mob.level().destroyBlockProgress(mob.getId(), door, -1);
        }
        door = null;
        t = 0;
        mob.setAction(AbstractParasitizedMonster.ACTION_NONE);
        mob.markTechniqueUsed();
    }

    @Override
    public void tick() {
        if (door == null) return;
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(Vec3.atCenterOf(door));
        if (t >= WINDUP + BASH) {
            t = 0; // 次の 1 回へ
        }
        t++;
        if (t == 1) {
            mob.setAction(G1Move.DOOR_BASH_WINDUP.id());
        } else if (t == WINDUP + 1) {
            mob.setAction(G1Move.DOOR_BASH.id());
        } else if (t == WINDUP + HIT_AT) {
            hit();
        }
    }

    private void hit() {
        if (!(mob.level() instanceof ServerLevel level) || door == null) return;
        BlockState s = level.getBlockState(door);
        if (!bashable(s)) return;
        hits++;
        level.levelEvent(1019, door, 0); // ゾンビが木の扉を叩く音
        level.destroyBlockProgress(mob.getId(), door, Math.min(9, hits * 10 / HITS - 1));
        Vec3 c = Vec3.atCenterOf(door);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, s), c.x, c.y, c.z, 12, 0.3D, 0.4D, 0.3D, 0.05D);
        // 扉の隙間から根が這い込む
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ROOTED_DIRT.defaultBlockState()), c.x, door.getY() + 0.1D, c.z,
                6 + hits * 3, 0.45D, 0.05D, 0.45D, 0.0D);
        if (hits >= HITS) {
            breakDoor(level, s);
        }
    }

    private void breakDoor(ServerLevel level, BlockState s) {
        BlockPos at = door;
        level.destroyBlockProgress(mob.getId(), at, -1);
        level.removeBlock(at, false);
        level.levelEvent(1021, at, 0); // ゾンビが木の扉を壊す音
        level.levelEvent(2001, at, Block.getId(s));
        if (!CorruptionSwitch.isOff() && !SporeDeviceData.inhibits(level, at, SporeDeviceData.Inhibit.CORRUPTION)) {
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-CORRUPT_RADIUS, -1, -CORRUPT_RADIUS), at.offset(CORRUPT_RADIUS, 2, CORRUPT_RADIUS))) {
                if (!level.getBlockState(p).isAir()) {
                    NoisyCorruptionBurst.single(level, p.immutable());
                }
            }
        }
        door = null;
    }

    /** 標的との間の、叩いて壊せる戸口(扉は下の半分)。無ければ null。 */
    @Nullable
    BlockPos findDoor(LivingEntity target) {
        // 1. 経路の次の 2 マスの扉(バニラの DoorInteractGoal と同じ見方)
        PathNavigation nav = mob.getNavigation();
        Path path = nav.getPath();
        if (path != null && !path.isDone()) {
            for (int i = 0; i < Math.min(path.getNextNodeIndex() + 2, path.getNodeCount()); i++) {
                Node node = path.getNode(i);
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos p = new BlockPos(node.x, node.y + dy, node.z);
                    if (mob.distanceToSqr(p.getX() + 0.5D, mob.getY(), p.getZ() + 0.5D) <= 2.25D && bashable(mob.level().getBlockState(p))) {
                        return lower(p);
                    }
                }
            }
        }
        // 2. 標的までの視線を遮る戸口(足元の高さと目の高さ)
        for (double h : new double[]{0.5D, mob.getEyeHeight()}) {
            Vec3 from = mob.position().add(0.0D, h, 0.0D);
            Vec3 to = target.position().add(0.0D, Math.min(h, target.getBbHeight() * 0.9D), 0.0D);
            BlockHitResult hit = mob.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceTo(from) <= REACH
                    && bashable(mob.level().getBlockState(hit.getBlockPos()))) {
                return lower(hit.getBlockPos());
            }
        }
        return null;
    }

    private BlockPos lower(BlockPos p) {
        BlockState s = mob.level().getBlockState(p);
        if (s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            return p.below();
        }
        return p;
    }
}
