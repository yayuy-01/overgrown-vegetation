package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.corruption.CoveredLight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.extensions.IBlockExtension;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 汚染が座標の記録で覆った光源を暗くする(2026-10-05、{@link CoveredLight})。
 *
 * <p>NeoForge の明かりの計算は位置付きの {@code getLightEmission(state, level, pos)}({@link IBlockExtension} の既定の
 * 実装)を呼ぶので、それを {@link Block} で上書きする。バニラのブロックはこれを上書きしていないので全部に効く
 * (自分で上書きしている他 MOD のブロックには効かない)。光らないブロックはすぐ返すので、ふだんの重さは変わらない。
 */
@Mixin(Block.class)
public abstract class BlockCoveredLightMixin implements IBlockExtension {

    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        int base = state.getLightEmission();
        return base <= 0 ? base : CoveredLight.emission(state, level, pos, base);
    }
}
