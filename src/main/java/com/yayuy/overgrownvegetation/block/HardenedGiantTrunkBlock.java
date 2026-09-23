package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 「硬化した巨大樹の幹」。巨大樹の幹の真下に埋まっている球状の空洞
 * ({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature#buildHiveChamber 巣の広間})の
 * 殻を作るブロック。将来ここに湧く「hive node」の部屋を、外から掘り崩されないようにするためのもの。
 *
 * <h2>ネザライト以上の斧でしか壊せない</h2>
 * <p>採掘できる道具は 2 つのタグで決まる。
 * <ul>
 *   <li>{@code #minecraft:mineable/axe} —— 斧だけが「正しい道具」。</li>
 *   <li>{@code #minecraft:needs_diamond_tool} + {@code #minecraft:incorrect_for_diamond_tool} ——
 *       木・石・金・鉄・ダイヤの階層すべてで「正しく掘れないブロック」になり、
 *       {@code incorrect_for_netherite_tool} にだけ入らないのでネザライト階層だけが残る。</li>
 * </ul>
 *
 * <p>バニラの {@code requiresCorrectToolForDrops()} は「間違った道具ではドロップしない」だけで、
 * 時間をかければ誰でも壊せてしまう。ここでは {@link #getDestroyProgress} を上書きして、
 * 正しい道具(= ネザライト以上の斧)以外では採掘の進捗を常に 0 にし、<b>文字どおり壊せない</b>
 * ようにしている(クリエイティブの即時破壊は進捗を見ないのでこれまでどおり壊せる)。
 * 爆発耐性も高くしてあるので、クリーパーや TNT で殻に穴を開けることもできない。
 */
public class HardenedGiantTrunkBlock extends Block {

    public HardenedGiantTrunkBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (!player.getMainHandItem().isCorrectToolForDrops(state)) {
            return 0.0F;
        }
        return super.getDestroyProgress(state, player, level, pos);
    }
}
