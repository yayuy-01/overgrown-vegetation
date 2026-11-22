package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * ランダムティックを受けない、ただのブロック(2026-09-24 軽量化、報告 2022)。
 *
 * <p>汚染された葉({@code overgrown_leaves} / {@code overgrown_swamp_leaves})と汚染されたレッドストーン鉱石は、
 * 見た目と音をバニラの葉・レッドストーン鉱石から {@code Properties.ofFullCopy} で借りている。
 * そのとき「ランダムティックを受ける」印まで一緒に写ってくる(バニラの葉は落葉、レッドストーン鉱石は消灯に使う)。
 * ところがこちらは素の {@link Block} なので {@code randomTick} は何もしない —— それなのに印だけ付いていると、
 * 巨大樹の樹冠のように葉しか無いセクションまで毎 tick ランダムティックの抽選対象になる。
 *
 * <p>ここで印を外す({@link #isRandomlyTicking} を常に false)。{@code randomTick} はもともと空で、
 * ランダムティックに渡される {@code level.random} も引かないので、このブロックに起きることは何も変わらない。
 * (葉だけのセクションが抽選の対象から外れるので、バニラがランダムティックの位置を選ぶ内部の数列の進み方は変わる。
 * どのブロックが選ばれるかの確率は同じ —— ブロックを 1 つ置いたり壊したりしたときと同じ種類の変化。)
 * 同じやり方は {@link UndevelopedBushBlock#isRandomlyTicking} にもある。
 *
 * <p>壊したときの経験値は {@link #InertBlock(BlockBehaviour.Properties, IntProvider)} で持たせる(汚染されたレッドストーン鉱石はバニラと同じ 1〜5。
 * 2026-10-10 ユーザー指定「覆われた鉱石から原石が出るように」でバニラの鉱石とそろえた)。
 */
public class InertBlock extends Block {


    private final IntProvider xpRange;

    public InertBlock(BlockBehaviour.Properties properties) {
        this(properties, ConstantInt.ZERO);
    }

    public InertBlock(BlockBehaviour.Properties properties, IntProvider xpRange) {
        super(properties);
        this.xpRange = xpRange;
    }

    @Override
    public boolean isRandomlyTicking(BlockState state) {
        return false;
    }

    @Override
    public int getExpDrop(BlockState state, net.minecraft.world.level.LevelReader level, net.minecraft.util.RandomSource random, BlockPos pos,
            int fortuneLevel, int silkTouchLevel) {
        // 1.20.1: シルクタッチの 0 は自分で(1.21 はエンチャントの効果が 0 にする)
        return silkTouchLevel == 0 ? this.xpRange.sample(random) : 0;
    }
}
