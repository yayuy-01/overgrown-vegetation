package com.yayuy.overgrownvegetation.ally;

import com.yayuy.overgrownvegetation.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 兵士の見通しと弾の線(2026-10-01)。バニラの {@code Level#clip}(当たり判定で止まる)とほぼ同じだが、
 * 金網・鉄格子・板ガラスの仲間({@link IronBarsBlock})・木の柵・フェンスゲート・葉は<b>見通せて弾も抜ける</b> ——
 * 基地は外周が金網なので、そうしないと中の兵士が外の敵を見つけられず撃てない(見張り台の窓の鉄格子も同じ)。
 */
public final class SoldierSight {

    /** 見通せて弾が抜けるブロックか。 */
    public static boolean seeThrough(BlockState state) {
        return state.getBlock() instanceof IronBarsBlock || state.getBlock() instanceof FenceBlock
                || state.getBlock() instanceof FenceGateBlock || state.is(BlockTags.LEAVES)
                || state.is(ModTags.BOMB_SHATTERS); // 薄い天蓋(バニラの葉 + この MOD の汚染された葉。汚染された葉はバニラの葉のタグに入っていない)
    }

    /** {@code from} から {@code to} への線を、{@link #seeThrough} のブロックを飛ばしてブロックの当たり判定に当てる(水は無視)。 */
    public static BlockHitResult clip(Level level, Vec3 from, Vec3 to, Entity entity) {
        ClipContext ctx = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity);
        return BlockGetter.traverseBlocks(from, to, ctx, (c, pos) -> {
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || seeThrough(state)) {
                return null;
            }
            VoxelShape shape = c.getBlockShape(state, level, pos);
            return level.clipWithInteractionOverride(c.getFrom(), c.getTo(), pos, shape, state);
        }, c -> {
            Vec3 d = c.getFrom().subtract(c.getTo());
            return BlockHitResult.miss(c.getTo(), Direction.getNearest(d.x, d.y, d.z), BlockPos.containing(c.getTo()));
        });
    }

    private SoldierSight() {
    }
}
