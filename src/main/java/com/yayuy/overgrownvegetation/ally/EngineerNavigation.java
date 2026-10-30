package com.yayuy.overgrownvegetation.ally;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * 工兵の経路探し(順 2 の着手順 4 = 整備、2026-10-04)。閉じた柵の門を木の扉と同じ「開けて通れる」ものとして経路に入れる
 * (基地の正門は柵の門の列。整備で外周の 5 マス外の有刺鉄線の輪と、壁の外の払う幅の根まで出るため。Claude 仮決め)。
 * 門を開け閉めするのは {@link OpenGateGoal}。ほかの兵士は門を通らない(壁の中に留まる)。
 */
public class EngineerNavigation extends GroundPathNavigation {

    /**
     * 経路を探す範囲(ブロック)。ほかの兵士は追う範囲(48)と同じだが、工兵は基地の端から端・正門の外まで歩くので広く見る
     * (追う範囲 = 撃ちにいく範囲は変えない)。
     */
    static final float PATH_RANGE = 96.0F;

    public EngineerNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new GateNodeEvaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        return new PathFinder(this.nodeEvaluator, Math.max(maxVisitedNodes, (int) (PATH_RANGE * 16)));
    }

    @Override
    protected net.minecraft.world.level.pathfinder.Path createPath(java.util.Set<BlockPos> targets, int regionOffset, boolean offsetUpward,
                                                                   int accuracy) {
        return createPath(targets, regionOffset, offsetUpward, accuracy, Math.max(PATH_RANGE,
                (float) mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE)));
    }

    /**
     * 閉じた柵の門のマスを「閉じた木の扉」にする(扉を開けられる Mob では通れるマスになる)。門の上のマスは今までどおり(柵の上には乗らない)。
     * 地形の種類の写し(レベルで共有)は書き換えず、読んだ後に置き換えるだけ。
     */
    static final class GateNodeEvaluator extends WalkNodeEvaluator {
        @Override
        public PathType getPathType(PathfindingContext context, int x, int y, int z) {
            PathType t = super.getPathType(context, x, y, z);
            if (t == PathType.FENCE) {
                BlockState s = context.level().getBlockState(new BlockPos(x, y, z));
                if (s.getBlock() instanceof FenceGateBlock && !s.getValue(FenceGateBlock.OPEN)) {
                    return PathType.DOOR_WOOD_CLOSED;
                }
            }
            return t;
        }
    }
}
