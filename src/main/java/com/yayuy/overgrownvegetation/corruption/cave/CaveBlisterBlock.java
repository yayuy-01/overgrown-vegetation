package com.yayuy.overgrownvegetation.corruption.cave;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.corruption.cliff.CliffSacFog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 洞窟の水ぶくれ(2026-11-20 洞窟の作り 嚢2 大きな水ぶくれ)。器官の嚢を明るくした絵で、ぼんやり光る(光 4【仮】。今の洞窟の嚢 cave_spore_sac に合わせる)。
 * 芯なし(ユーザーの芯の決まり)。壊すと崖の嚢と同じ胞子の霧({@link CliffSacFog})が出る。
 * 塊は数十個の嚢なので、崖の嚢のような連鎖はしない: 1 つ割ると霧 1 回。まわり 3 マスにもう霧が出ていれば足さない(霧を重ねない)。
 */
public class CaveBlisterBlock extends Block {

    public static final MapCodec<CaveBlisterBlock> CODEC = simpleCodec(CaveBlisterBlock::new);

    public CaveBlisterBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel server) burst(server, pos);
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void wasExploded(Level level, BlockPos pos, Explosion explosion) {
        if (level instanceof ServerLevel server) burst(server, pos);
        super.wasExploded(level, pos, explosion);
    }

    /** 最近の破裂(位置 → 霧が消える時刻)。霧を重ねないため。保存しない。 */
    private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap RECENT = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    /** 破裂(霧 1 回。まわり 3 マスにまだ霧が出ていれば足さない)。 */
    public static void burst(ServerLevel level, BlockPos pos) {
        long now = level.getGameTime();
        RECENT.long2LongEntrySet().removeIf(e -> e.getLongValue() <= now);
        for (long k : RECENT.keySet()) if (BlockPos.of(k).distSqr(pos) <= 9.0D) return;
        CliffSacFog.start(level, pos);
        RECENT.put(pos.asLong(), now + Math.max(1, com.yayuy.overgrownvegetation.data.DesignData.cliff().burst.durationTicks));
    }
}
