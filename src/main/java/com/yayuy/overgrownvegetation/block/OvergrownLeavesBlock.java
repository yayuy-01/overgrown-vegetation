package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 汚染された葉({@code overgrown_leaves} / {@code overgrown_swamp_leaves})。中身は {@link InertBlock} と同じで、葉どうしの間の面を省けるようにしただけ(2026-10-24、候補 F5)。
 *
 * <p>葉は {@code noOcclusion} なので、バニラはファンシーでも軽量でも隣り合う葉の間の面を全部描く(葉の層の塗りつぶしの主因。DEV_NOTES「葉の層の塗りつぶしの削減(2026-10-24)」)。
 * {@link #faceCull}: 0 = バニラと同じ(全部描く)・1 = 隣が同じ葉なら面を描かない・2 = 隣の葉のさらに奥も同じ葉のときだけ描かない(表の 1 枚下までは残す)。
 * 既定は 2(表から 2 枚は残すので外からの見た目はほぼ同じ。葉の中に目があると空の穴が開く)。
 * 判定は {@code Block.shouldRenderFace} が呼ぶ {@link #skipRendering}・{@link #hidesNeighborFace}(Sodium も同じものを呼ぶ)。切り替えたら区画を組み直す。
 */
public class OvergrownLeavesBlock extends InertBlock {

    public static final MapCodec<OvergrownLeavesBlock> CODEC = simpleCodec(OvergrownLeavesBlock::new);

    /** 0 = 全部描く・1 = 葉どうしの面を省く・2 = 奥にもう 1 枚葉があるときだけ省く(既定、10-24 ユーザー決定: 描画設定によらず常にオン)。開発用 {@code leafcull}。 */
    public static volatile int faceCull = 2;

    public OvergrownLeavesBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends OvergrownLeavesBlock> codec() {
        return CODEC;
    }

    /** 軽量グラフィックでは葉が固い層なので、NeoForge の既定では隣に面を隠させない。描画設定によらず省くため常に許す。 */
    @Override
    public boolean supportsExternalFaceHiding(BlockState state) {
        return true;
    }

    @Override
    protected boolean skipRendering(BlockState state, BlockState adjacent, Direction direction) {
        return faceCull == 1 && adjacent.getBlock() == this || super.skipRendering(state, adjacent, direction);
    }

    /**
     * 隣のブロック(= この葉。{@code pos})が、{@code neighborState} の {@code dir} の向きの面を隠すか。{@code dir} はこの葉から隣を見た向き。
     * 隣も同じ葉で、この葉のさらに奥({@code pos} から {@code dir} の逆へ 1 つ)も同じ葉なら隠す。
     */
    @Override
    public boolean hidesNeighborFace(BlockGetter level, BlockPos pos, BlockState state, BlockState neighborState, Direction dir) {
        return faceCull == 2 && neighborState.getBlock() == this && level.getBlockState(pos.relative(dir.getOpposite())).getBlock() == this;
    }
}
