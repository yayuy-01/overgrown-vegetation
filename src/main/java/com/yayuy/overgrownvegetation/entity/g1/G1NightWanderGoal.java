package com.yayuy.overgrownvegetation.entity.g1;

import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;

/**
 * 夜に外へ出る(11-05 ユーザー「入れる」。共通の仕様書の能力の表「昼はその場をうろつき、夜は明かり・生き物の多い方へ歩く」)。
 * 森の外へ胞子を運ぶ役。標的がいる間は動かない(攻撃が先)。
 *
 * <p>行き先の選び方【仮】: 半径 {@value #RADIUS}(仕様の 48【仮】)の中で
 * <ul>
 *   <li>生き物(G1〜G10・防具立て以外)を最大 {@value #MAX_CREATURES} 体見て、それぞれの周り 8 ブロックの数が多い所</li>
 *   <li>地表の {@value #LIGHT_SAMPLES} か所のブロックの明るさ(松明・ランタンなど)</li>
 * </ul>
 * を点数にして、いちばん高い所へ歩く(点 = 周りの生き物の数 + 明るさ ÷ 3)。どちらも見つからなければ動かない(うろつきに任せる)。
 * 探すのは {@value #SEARCH_INTERVAL} tick に 1 回(重さを抑える)。着いたら(4 ブロック以内)か {@value #MAX_WALK_TICKS} tick で終わる。
 */
public class G1NightWanderGoal extends Goal {

    static final int RADIUS = 48;
    static final int MAX_CREATURES = 24;
    static final int LIGHT_SAMPLES = 12;
    static final int SEARCH_INTERVAL = 200;
    static final int MAX_WALK_TICKS = 600;

    private final AbstractParasitizedMonster mob;
    private int cooldown;
    @Nullable
    private BlockPos goal;
    private int walked;

    public G1NightWanderGoal(AbstractParasitizedMonster mob) {
        this.mob = mob;
        this.cooldown = mob.getRandom().nextInt(SEARCH_INTERVAL);
        setFlags(EnumSet.of(Flag.MOVE));
    }

    /** 夜か(日の明るさが落ちている。雷雨の昼も入る —— バニラのゾンビが燃えない暗さ)。 */
    static boolean isNight(AbstractParasitizedMonster mob) {
        return !mob.level().isDay() || mob.level().isThundering();
    }

    @Override
    public boolean canUse() {
        if (mob.getTarget() != null || !isNight(mob)) {
            return false;
        }
        if (--cooldown > 0) {
            return false;
        }
        cooldown = SEARCH_INTERVAL;
        goal = pick();
        return goal != null;
    }

    @Override
    public boolean canContinueToUse() {
        return goal != null && mob.getTarget() == null && walked < MAX_WALK_TICKS && !mob.getNavigation().isDone()
                && mob.blockPosition().distSqr(goal) > 16.0D;
    }

    @Override
    public void start() {
        walked = 0;
        mob.getNavigation().moveTo(goal.getX() + 0.5D, goal.getY(), goal.getZ() + 0.5D, 1.0D);
    }

    @Override
    public void tick() {
        walked++;
    }

    @Override
    public void stop() {
        goal = null;
        mob.getNavigation().stop();
    }

    /** 行き先(無ければ null)。 */
    @Nullable
    BlockPos pick() {
        double best = 0.0D;
        BlockPos at = null;
        AABB box = mob.getBoundingBox().inflate(RADIUS, 16.0D, RADIUS);
        List<LivingEntity> found = mob.level().getEntitiesOfClass(LivingEntity.class, box, e -> G1TargetGoal.isPrey(mob, e));
        int n = Math.min(found.size(), MAX_CREATURES);
        for (int i = 0; i < n; i++) {
            LivingEntity e = found.get(i);
            int around = 0;
            for (int j = 0; j < found.size(); j++) {
                if (found.get(j).distanceToSqr(e) < 64.0D) {
                    around++;
                }
            }
            if (around > best) {
                best = around;
                at = e.blockPosition();
            }
        }
        for (int i = 0; i < LIGHT_SAMPLES; i++) {
            int x = mob.getBlockX() + mob.getRandom().nextInt(RADIUS * 2 + 1) - RADIUS;
            int z = mob.getBlockZ() + mob.getRandom().nextInt(RADIUS * 2 + 1) - RADIUS;
            if (!mob.level().hasChunkAt(new BlockPos(x, mob.getBlockY(), z))) {
                continue;
            }
            int y = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            double score = mob.level().getBrightness(LightLayer.BLOCK, p) / 3.0D;
            if (score > best) {
                best = score;
                at = p;
            }
        }
        return at;
    }
}
