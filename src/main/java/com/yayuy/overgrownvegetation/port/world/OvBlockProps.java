package com.yayuy.overgrownvegetation.port.world;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * 【1.20.1 だけの物・互換の層】1.20.5 の {@code BlockBehaviour.Properties.ofFullCopy(block)} の代わり。
 * 1.20.1 の {@code Properties.copy} は 1.20.5 の {@code ofLegacyCopy} と同じで、次の 6 つを写さない:
 * 跳ぶ強さ・レッドストーンを通すか・Mob が湧けるか・後処理・窒息するか・視界をふさぐか。
 * ここでは {@code copy} に、その 6 つを<b>元のブロックの既定の状態に聞く</b>形で足す(1.20.1 では元の Properties の中身を外から読めないため)。
 *
 * <p>違い(1.20.1 で動きが変わりうる所): 元のブロックの判定が状態や形で変わる物は、元の既定の状態で判定する
 * (例: 既定の判定「当たり判定が丸ごとの箱か」は、こちらのブロックの形ではなく元のブロックの既定の形で見る)。
 * 葉・ガラス・ソウルサンドなど、いつも同じ答えの物は同じになる。{@code drops}(落とす物の表)は写さない(1.20.5 でも元が未設定なら同じ)。
 *
 * <p>本流の {@code Properties.ofFullCopy(x)} は port-rewrite.pl が {@code OvBlockProps.ofFullCopy(x)} に書き換える。
 */
public final class OvBlockProps {

    private OvBlockProps() {
    }

    public static BlockBehaviour.Properties ofFullCopy(BlockBehaviour source) {
        BlockBehaviour.Properties p = BlockBehaviour.Properties.copy(source);
        if (!(source instanceof Block block)) {
            return p;
        }
        p.jumpFactor(block.getJumpFactor());
        p.isRedstoneConductor((state, level, pos) -> block.defaultBlockState().isRedstoneConductor(level, pos));
        p.isValidSpawn((state, level, pos, type) -> block.defaultBlockState().isValidSpawn(level, pos, type));
        p.hasPostProcess((state, level, pos) -> block.defaultBlockState().hasPostProcess(level, pos));
        p.isSuffocating((state, level, pos) -> block.defaultBlockState().isSuffocating(level, pos));
        p.isViewBlocking((state, level, pos) -> block.defaultBlockState().isViewBlocking(level, pos));
        return p;
    }

    /** 1.20.5 の {@code ofLegacyCopy}(= 1.20.1 の {@code copy})。 */
    public static BlockBehaviour.Properties ofLegacyCopy(BlockBehaviour source) {
        return BlockBehaviour.Properties.copy(source);
    }
}
