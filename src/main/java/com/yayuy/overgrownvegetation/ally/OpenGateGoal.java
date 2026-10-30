package com.yayuy.overgrownvegetation.ally;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

/**
 * 工兵が経路の上の柵の門を開けて通り、通り過ぎたら閉める(順 2 の着手順 4 = 整備、2026-10-04。バニラの扉の開け閉めと同じ作り)。
 * 経路に門を入れるのは {@link EngineerNavigation}。
 */
public class OpenGateGoal extends Goal {

    private final Mob mob;
    private BlockPos gate = BlockPos.ZERO;
    private float dirX;
    private float dirZ;
    private boolean passed;
    private int forget;

    public OpenGateGoal(Mob mob) {
        this.mob = mob;
    }

    private static boolean closedGate(Level level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.getBlock() instanceof FenceGateBlock && !s.getValue(FenceGateBlock.OPEN);
    }

    @Override
    public boolean canUse() {
        if (!mob.horizontalCollision || !(mob.getNavigation() instanceof GroundPathNavigation nav)) {
            return false;
        }
        Path path = nav.getPath();
        if (path == null || path.isDone()) {
            return false;
        }
        for (int i = 0; i < Math.min(path.getNextNodeIndex() + 2, path.getNodeCount()); i++) {
            Node node = path.getNode(i);
            BlockPos p = new BlockPos(node.x, node.y, node.z);
            if (mob.distanceToSqr(p.getX() + 0.5, mob.getY(), p.getZ() + 0.5) <= 2.25 && closedGate(mob.level(), p)) {
                gate = p;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return forget > 0 && !passed;
    }

    @Override
    public void start() {
        passed = false;
        forget = 20;
        dirX = (float) (gate.getX() + 0.5 - mob.getX());
        dirZ = (float) (gate.getZ() + 0.5 - mob.getZ());
        setOpen(true);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        forget--;
        float fx = (float) (gate.getX() + 0.5 - mob.getX());
        float fz = (float) (gate.getZ() + 0.5 - mob.getZ());
        if (dirX * fx + dirZ * fz < 0.0F) {
            passed = true;
        }
    }

    @Override
    public void stop() {
        setOpen(false); // 開けたままにしない(正門が開いていると壁の中が避難場所でなくなる。バニラの扉の閉め方と同じ)
    }

    private void setOpen(boolean open) {
        Level level = mob.level();
        BlockState s = level.getBlockState(gate);
        if (!(s.getBlock() instanceof FenceGateBlock) || s.getValue(FenceGateBlock.OPEN) == open) {
            return;
        }
        level.setBlock(gate, s.setValue(FenceGateBlock.OPEN, open), Block.UPDATE_ALL);
        level.playSound(null, gate, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 1.0F,
                level.getRandom().nextFloat() * 0.1F + 0.9F);
    }
}
