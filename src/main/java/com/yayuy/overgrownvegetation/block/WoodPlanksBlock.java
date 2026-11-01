package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 木ごとの板材(コルダイテス・封印木・巨大樹の外皮・巨大樹。2026-11-01)。中身はオークの板材の写しで、燃え方もバニラの板材と同じ
 * ({@code FireBlock} の板材の 5 / 20)。{@code ofFullCopy} は燃え方を写さないので、ここで返す。
 */
public class WoodPlanksBlock extends Block {
    /** バニラの板材の燃えやすさ(火が燃え移って消える率)。 */
    private static final int FLAMMABILITY = 20;
    /** バニラの板材の火の広がりやすさ(隣の火が燃え移る率)。 */
    private static final int FIRE_SPREAD_SPEED = 5;

    public WoodPlanksBlock(Properties properties) {
        super(properties);
    }

    @Override
    public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return FLAMMABILITY;
    }

    @Override
    public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return FIRE_SPREAD_SPEED;
    }
}
