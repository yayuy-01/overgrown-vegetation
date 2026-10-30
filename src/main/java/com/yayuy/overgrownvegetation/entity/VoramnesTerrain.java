package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * ヴォラムネス({@link VoramnesEntity})が「潜って泳げる地面」と「顔を出せる地表」の判定(2026-10-01)。
 * GameTest から直接呼べるよう、実体から切り出した静的な関数だけを置く。
 *
 * <h2>決まり(すべて仮決め)</h2>
 * <ul>
 *     <li><b>潜れる地面</b> = 当たり判定が 1 マスまるごとのブロックで、液体を含まず、木の一部(幹・葉。過繁茂の木も
 *     {@link TreeCanopyGroundFinder#isTreePart})でなく、硬さが {@value #MAX_HARDNESS} 以下(黒曜石・岩盤・強化深層岩は潜れない)。
 *     土・石・砂・深層岩・鉱石・板材も潜れる(置いたブロックかどうかは見分けない)。</li>
 *     <li><b>地表</b> = 潜れる地面の真上の、当たり判定が空で液体の無いマス(草・花・雪 1 枚は通る)。
 *     下に潜れる地面が {@value #SOLID_DEPTH} マス以上続いていること(薄い床・橋の上は地表と見なさない)。
 *     <b>ハーフブロック・カーペットの床の下へは来られない</b>(床が「当たり判定が空」でも「1 マスまるごと」でもないため)。</li>
 *     <li><b>ブロックは一切壊さない</b>(地中は {@code noPhysics} ですり抜けるだけ。SPEC、フォディムネスと揃える)。</li>
 * </ul>
 */
public final class VoramnesTerrain {

    /** 地表の下に続いていなければならない、潜れる地面の厚さ(マス、仮)。 */
    public static final int SOLID_DEPTH = 3;
    /** 潜れる地面の硬さの上限(仮)。石 1.5・深層岩 3・鉄ブロック 5 まで。黒曜石 50・岩盤 −1 は潜れない。 */
    public static final float MAX_HARDNESS = 5.0F;

    private VoramnesTerrain() {
    }

    /** {@code pos} のブロックが潜って泳げる地面か。 */
    public static boolean isGround(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty() || TreeCanopyGroundFinder.isTreePart(state)) {
            return false;
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F || hardness > MAX_HARDNESS) {
            return false;
        }
        return state.isCollisionShapeFullBlock(level, pos);
    }

    /** {@code pos} のマスを体(蕾の先・地上に出た体)が通れるか: 当たり判定が空で、液体が無い。 */
    public static boolean isPassable(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
    }

    /** {@code surface}(足元のマス)が地表として使えるか: そのマスが通れて、下に潜れる地面が {@link #SOLID_DEPTH} マス続く。 */
    public static boolean isValidSurface(BlockGetter level, BlockPos surface) {
        if (!isPassable(level, surface)) {
            return false;
        }
        BlockPos.MutableBlockPos p = surface.mutable();
        for (int i = 1; i <= SOLID_DEPTH; i++) {
            p.setY(surface.getY() - i);
            if (!isGround(level, p)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 列 {@code (x, z)} の地表の高さ(足元のマスの y)。{@code refY} に近い順(0, +1, −1, +2, −2 …)に、上へ {@code up}・下へ {@code down} まで探す。
     * 見つからなければ null(水の底・崖の上下・洞窟の天井の下など)。
     */
    @Nullable
    public static Integer surfaceAt(BlockGetter level, int x, int z, int refY, int up, int down) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, refY, z);
        int reach = Math.max(up, down);
        for (int i = 0; i <= reach; i++) {
            if (i <= up) {
                p.setY(refY + i);
                if (isValidSurface(level, p)) {
                    return refY + i;
                }
            }
            if (i > 0 && i <= down) {
                p.setY(refY - i);
                if (isValidSurface(level, p)) {
                    return refY - i;
                }
            }
        }
        return null;
    }

    /**
     * 地表 {@code surface} の上に {@code need} マスの空きがあるか(地上に出る・花を開く前に見る)。
     * 葉は押しのけて出られる(当たり判定があっても数える)。幹を含め、それ以外の固いものに当たったら足りない。
     */
    public static boolean hasHeadroom(BlockGetter level, BlockPos surface, int need) {
        BlockPos.MutableBlockPos p = surface.mutable();
        for (int i = 0; i < need; i++) {
            p.setY(surface.getY() + i);
            BlockState state = level.getBlockState(p);
            if (!state.getFluidState().isEmpty()) {
                return false;
            }
            if (!state.getCollisionShape(level, p).isEmpty() && !isLeaves(state)) {
                return false;
            }
        }
        return true;
    }

    /** 葉(バニラのタグ + 過繁茂の木の葉。過繁茂の木はバニラのタグを持たない —— メモリ overgrown-trees-lack-vanilla-tags)。幹は含めない。 */
    private static boolean isLeaves(BlockState state) {
        if (state.is(BlockTags.LEAVES)) {
            return true;
        }
        return TreeCanopyGroundFinder.isTreePart(state) && !state.is(BlockTags.LOGS)
                && (state.getBlock() == com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_LEAVES.get()
                || state.getBlock() == com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_SWAMP_LEAVES.get());
    }

    /** 足元のブロックが掘り返した土の塊({@code burrow} の骨)を見せてよい土か(シャベルで掘るもの)。描画用。 */
    public static boolean isSoil(BlockState below) {
        return below.is(BlockTags.MINEABLE_WITH_SHOVEL);
    }
}
