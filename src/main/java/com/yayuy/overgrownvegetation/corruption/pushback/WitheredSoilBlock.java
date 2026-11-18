package com.yayuy.overgrownvegetation.corruption.pushback;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModPushBack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * <b>枯れた土</b>(押し返し 2026-11-18 夜)。枯らし薬を浴びた汚染の地面。{@code spray.reviveDays}【仮 1 日】たつと見直す:
 * そのチャンクがまだ台帳に載っていれば(胞子が溜まっている)元の汚染の地面へ戻り、外れていれば(割合が 3 割を切った・発芽前)ふつうの土・草に戻る。
 * 外れたチャンクも、胞子が溜まり直して発芽すればまた森になる(ユーザー「胞子が溜まって 1 日ほどで発芽すれば、すぐ戻る」)。
 */
public class WitheredSoilBlock extends Block {

    public static final MapCodec<WitheredSoilBlock> CODEC = simpleCodec(WitheredSoilBlock::new);
    /** 元の汚染の地面: 0 = 過繁茂の草・1 = 過繁茂の土・2 = 過繁茂のブロック。 */
    public static final IntegerProperty ORIGIN = IntegerProperty.create("origin", 0, 2);

    public WitheredSoilBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ORIGIN, 0));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ORIGIN);
    }

    /** 枯らし薬で枯れる汚染の地面か。 */
    public static boolean withers(BlockState s) {
        return s.is(ModBlocks.OVERGROWN_GRASS_BLOCK.get()) || s.is(ModBlocks.OVERGROWN_DIRT.get()) || s.is(ModBlocks.OVERGROWN_BLOCK.get());
    }

    public static BlockState from(BlockState s) {
        int o = s.is(ModBlocks.OVERGROWN_GRASS_BLOCK.get()) ? 0 : s.is(ModBlocks.OVERGROWN_DIRT.get()) ? 1 : 2;
        return ModPushBack.WITHERED_SOIL.get().defaultBlockState().setValue(ORIGIN, o);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        if (!old.is(this) && level instanceof ServerLevel) {
            long ticks = Math.max(1L, (long) (com.yayuy.overgrownvegetation.data.DesignData.pushBack().spray.reviveDays * 24000L));
            level.scheduleTick(pos, this, (int) Math.min(Integer.MAX_VALUE, ticks));
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        level.setBlock(pos, reviveTo(level, pos, state), Block.UPDATE_ALL);
    }

    /** 見直したときに何へ戻るか(GameTest からも)。 */
    public static BlockState reviveTo(ServerLevel level, BlockPos pos, BlockState state) {
        int o = state.getValue(ORIGIN);
        boolean forest = level.dimension() == Level.OVERWORLD
                ? PhaseData.get(level).isOvergrownKey(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4))
                : com.yayuy.overgrownvegetation.registry.ModBiomes.isOvergrownLike(level.getBiome(pos));
        if (forest) {
            return (o == 0 ? ModBlocks.OVERGROWN_GRASS_BLOCK : o == 1 ? ModBlocks.OVERGROWN_DIRT : ModBlocks.OVERGROWN_BLOCK).get().defaultBlockState();
        }
        boolean covered = !level.getBlockState(pos.above()).isAir() && level.getBlockState(pos.above()).blocksMotion();
        return o == 0 && !covered ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState();
    }
}
