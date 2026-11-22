package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>原木から化けた過繁茂しているブロック</b>(2026-10-05 夜、2026-10-30 に covered/ から移した。ユーザー報告「木が汚染されると過繁茂しているブロックになって、
 * 倒木や木を消すときに空中に残ってしまう」)。
 *
 * <p>見た目・硬さ・落とす物・名前は過繁茂しているブロック({@code overgrown_block})と同じ。違うのは
 * {@code #minecraft:logs} に入っていること —— 湿地・密林の「元の木を残さない」({@code corruption/SwampTreeFall})は
 * 原木の繋がりを辿って木 1 本を集めるので、幹の途中が普通の過繁茂しているブロックだとそこで繋がりが切れ、その上の幹・葉と
 * そのブロック自身が宙に残っていた。原木として辿られるので木と一緒に片付く。自然の葉もこれを原木と数える(葉が枯れ始めない)。
 * 基地の工兵も原木と同じく払う。
 *
 * <p>汚染が原木を「過繁茂しているブロック」にするときは必ずこれにする({@code CorruptionRegistry})。アイテムは無い
 * (掘っても、中ボタンで選んでも過繁茂しているブロック)。汚染済み({@code #corrupted})。
 */
public class OvergrownTrunkBlock extends Block {

    public OvergrownTrunkBlock(Properties properties) {
        super(properties);
    }

    @Override
    public ItemStack getCloneItemStack(net.minecraft.world.level.BlockGetter level, BlockPos pos, BlockState state) {
        return new ItemStack(ModBlocks.OVERGROWN_BLOCK.get());
    }
}
