package com.yayuy.overgrownvegetation.port.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.grower.AbstractMegaTreeGrower;
import net.minecraft.world.level.block.grower.AbstractTreeGrower;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【1.20.1 だけの物・互換の層】バニラ 1.20.5 の {@code net.minecraft.world.level.block.grower.TreeGrower}(名前・大木・普通の木・花のそばの木)。
 * 1.20.1 の苗木は {@link AbstractTreeGrower} を受けるので、それを継ぐ。大木があるときは 2×2 の苗木で大木を試し、無ければ普通の木
 * (1.20.5 と同じ)。{@link #CODEC} は名前で引く(作った物だけ)。
 */
public class TreeGrower extends AbstractTreeGrower {

    private static final Map<String, TreeGrower> BY_NAME = new ConcurrentHashMap<>();
    public static final Codec<TreeGrower> CODEC = Codec.STRING.comapFlatMap(
            name -> Optional.ofNullable(BY_NAME.get(name)).map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "Unknown tree grower " + name)),
            g -> g.name);

    private final String name;
    private final Optional<ResourceKey<ConfiguredFeature<?, ?>>> megaTree;
    private final Optional<ResourceKey<ConfiguredFeature<?, ?>>> tree;
    private final Optional<ResourceKey<ConfiguredFeature<?, ?>>> flowers;
    private final AbstractMegaTreeGrower mega;

    public TreeGrower(String name, Optional<ResourceKey<ConfiguredFeature<?, ?>>> megaTree,
                      Optional<ResourceKey<ConfiguredFeature<?, ?>>> tree, Optional<ResourceKey<ConfiguredFeature<?, ?>>> flowers) {
        this.name = name;
        this.megaTree = megaTree;
        this.tree = tree;
        this.flowers = flowers;
        TreeGrower self = this;
        this.mega = new AbstractMegaTreeGrower() {
            @Override
            protected @Nullable ResourceKey<ConfiguredFeature<?, ?>> getConfiguredMegaFeature(RandomSource random) {
                return self.megaTree.orElse(null);
            }

            @Override
            protected @Nullable ResourceKey<ConfiguredFeature<?, ?>> getConfiguredFeature(RandomSource random, boolean hasFlowers) {
                return self.getConfiguredFeature(random, hasFlowers);
            }
        };
        BY_NAME.put(name, this);
    }

    public String name() {
        return name;
    }

    @Override
    protected @Nullable ResourceKey<ConfiguredFeature<?, ?>> getConfiguredFeature(RandomSource random, boolean hasFlowers) {
        if (hasFlowers && flowers.isPresent()) {
            return flowers.get();
        }
        return tree.orElse(null);
    }

    @Override
    public boolean growTree(ServerLevel level, ChunkGenerator generator, BlockPos pos, BlockState state, RandomSource random) {
        if (megaTree.isPresent()) {
            for (int i = 0; i >= -1; --i) {
                for (int j = 0; j >= -1; --j) {
                    if (AbstractMegaTreeGrower.isTwoByTwoSapling(state, level, pos, i, j)) {
                        return mega.placeMega(level, generator, pos, state, random, i, j);
                    }
                }
            }
        }
        return super.growTree(level, generator, pos, state, random);
    }
}
