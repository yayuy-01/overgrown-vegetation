package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.corruption.CorruptionDropSuppressor;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 汚染された砂・砂岩。
 *
 * <p>「広がる密林」というコンセプト上、砂地のままでは印象にそぐわないため、
 * 置かれてからしばらく経つと自動的に {@link ModBlocks#OVERGROWN_DIRT}(または条件を満たせば
 * 直接 {@link ModBlocks#OVERGROWN_GRASS_BLOCK})へ変わる。
 * ランダムティックではなく {@link Level#scheduleTick} による確定タイマーなので、
 * 置かれてからちょうど {@link #DECAY_DELAY_TICKS} tick 後に必ず変化する
 * (およそ 20 秒。{@code randomTickSpeed} の影響も受けない)。
 *
 * <p>このタイマーはチャンクの保存データ(スケジュール済みティック)に含まれるため、
 * ワールドを閉じて再度開いても続きから数え直される。
 *
 * <p>{@link ModBlocks#OVERGROWN_GRASS_BLOCK} はバニラの草ブロックと同じく、
 * 既に存在する草ブロックが隣接する {@link ModBlocks#OVERGROWN_DIRT} へランダムティックで
 * 伝染していく仕組みしか持たない。砂漠のように元々草ブロックが一切存在しないバイオームでは
 * 伝染元が無いため、砂が土に腐化するだけでは永遠に草化しない。
 * そのため、頭上が十分明るく水没していない(=バニラの草ブロックが生存できる条件と同じ)場合は
 * 腐化先を土ではなく直接草ブロックにし、そこを新たな伝染元として周囲へ広がっていけるようにする。
 */
public class OvergrownSandBlock extends Block {

    public static final MapCodec<OvergrownSandBlock> CODEC = simpleCodec(OvergrownSandBlock::new);

    /** およそ 20 秒(20 tick/秒 × 20)。 */
    private static final int DECAY_DELAY_TICKS = 400;

    public OvergrownSandBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide() && !oldState.is(state.getBlock())) {
            // 汚染で新しくこのブロックになった瞬間だけ予約する
            // (同じブロックのまま別プロパティに変わっただけの更新では再予約しない)
            level.scheduleTick(pos, this, DECAY_DELAY_TICKS);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!level.getBlockState(pos).is(this)) {
            return;
        }
        Block decayTarget = canBecomeGrass(level, pos)
                ? ModBlocks.OVERGROWN_GRASS_BLOCK.get()
                : ModBlocks.OVERGROWN_DIRT.get();
        // 上に取り残された植物がこの更新で壊れても、アイテムは落とさない(CorruptionDropSuppressor)
        CorruptionDropSuppressor.run(() -> level.setBlockAndUpdate(pos, decayTarget.defaultBlockState()));
    }

    /**
     * 直接草ブロックへ腐化してよいか。{@link com.yayuy.overgrownvegetation.block.OvergrownGrassBlock}
     * の伝染条件(頭上の明るさ・水没していないか)と揃えてある。
     */
    private static boolean canBecomeGrass(ServerLevel level, BlockPos pos) {
        BlockPos abovePos = pos.above();
        BlockState aboveState = level.getBlockState(abovePos);
        if (aboveState.getFluidState().getAmount() >= 8) {
            return false;
        }
        return level.getMaxLocalRawBrightness(abovePos) >= 4;
    }
}
