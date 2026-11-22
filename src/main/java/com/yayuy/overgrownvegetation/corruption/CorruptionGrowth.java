package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import com.yayuy.overgrownvegetation.port.registries.DeferredBlock;

import java.util.List;

/**
 * ブロックが汚染された際、露出面に生やす装飾植物(シダ・苔・蔦)をランダムに選ぶ。
 *
 * <p>呼び出し側({@link CorruptionSpreader})は面の向きだけを気にすればよく、
 * 実際にどの見た目のバリエーションが生えるかはここで抽選する。
 * 候補には重み(weight)を付けられ、数値が大きいほど選ばれやすくなる(既定は 1)。
 */
public final class CorruptionGrowth {

    private record Candidate(DeferredBlock<Block> block, int weight) {
        Candidate(DeferredBlock<Block> block) {
            this(block, 1);
        }
    }

    private static final List<Candidate> TOP = List.of(
            new Candidate(ModBlocks.OVERGROWN_SPORE_BLOOM),
            new Candidate(ModBlocks.OVERGROWN_ZENMAI),
            // 巻きシダは立体モデル(2026-09-11 からチャンクメッシュへ焼き込む静的モデル)。
            // 目立たせつつも支配的にならない程度の重みに抑えている
            // (以前は 4。spore_bloom/zenmai は軽いクロスモデル)。
            new Candidate(ModBlocks.OVERGROWN_CURLED_FERN, 2));

    private static final List<Candidate> HANGING = List.of(
            new Candidate(ModBlocks.OVERGROWN_WEEPING_TENDRIL),
            new Candidate(ModBlocks.OVERGROWN_DRIP_MOSS),
            new Candidate(ModBlocks.OVERGROWN_SPORE_TENDRIL));

    private static final List<Candidate> TALL = List.of(
            new Candidate(ModBlocks.OVERGROWN_TALL_FERN),
            new Candidate(ModBlocks.OVERGROWN_SPORE_REED));

    /** 上面(露出面が UP)に生やす下草。 */
    public static BlockState randomTop(RandomSource random) {
        return pick(TOP, random).defaultBlockState();
    }

    /** 下面(露出面が DOWN)に生やす、垂れ下がる植物。 */
    public static BlockState randomHanging(RandomSource random) {
        return pick(HANGING, random).defaultBlockState();
    }

    /** 上面に生える、高さ2ブロックの植物(下半分の状態)。 */
    public static BlockState randomTall(RandomSource random) {
        return pick(TALL, random).defaultBlockState();
    }

    /** 重み付きで候補を 1 つ選ぶ。 */
    private static Block pick(List<Candidate> candidates, RandomSource random) {
        int totalWeight = 0;
        for (Candidate candidate : candidates) {
            totalWeight += candidate.weight();
        }

        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (Candidate candidate : candidates) {
            cumulative += candidate.weight();
            if (roll < cumulative) {
                return candidate.block().get();
            }
        }

        // 到達しないはずだが、念のため最後の候補を返す。
        return candidates.get(candidates.size() - 1).block().get();
    }

    private CorruptionGrowth() {
    }
}
