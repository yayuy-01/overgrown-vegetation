package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.organ.OrganData;
import com.yayuy.overgrownvegetation.organ.OrganKind;
import com.yayuy.overgrownvegetation.organ.OrganPlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>器官の節</b>(2026-09-23、要望)。
 *
 * <p>器官は必ずここから生える。<b>節が器官そのもの</b>であって、
 * 立ち上がっている塔は節が作り出した<b>体</b>にすぎない ——
 * だから世界への効きも、潰したときの手応えも、すべてこのブロックが握っている。
 *
 * <h2>置けば生え、壊せば枯れる</h2>
 * <ul>
 *     <li>{@code onPlace} —— 台帳へ載せ、<b>その場で体を組み上げる</b>
 *     ({@link OrganPlacer#grow})。生成もクリエイティブで手置きしたときも同じ道を通るので、
 *     「生成でだけ形が違う」が起きない。</li>
 *     <li>{@code onRemove} —— 台帳から外す。効果は<b>その瞬間に消え</b>、
 *     体は {@code OrganWither} が上から順に枯らしていく。</li>
 * </ul>
 *
 * <h2>掘って辿り着くもの</h2>
 * <p>節は体の<b>根元の塊に埋まっている</b>({@code SporeGlandTower} の {@code rootHeight})。
 * むき出しだと近寄って殴るだけで終わってしまうので、掘って辿り着くようにしてある ——
 * その途中で表面に点在する腺({@link SporeGlandBlock})に触ることになる。
 *
 * <p>硬さと爆破耐性は高い。器官を潰すのは<b>遠征</b>であってほしいし、
 * TNT を投げ込んで遠くから終わらせられても困る。
 */
public class OrganNodeBlock extends Block {

    public OrganNodeBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (state.is(oldState.getBlock()) || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (OrganData.get(serverLevel).add(pos, OrganKind.SPORE_GLAND, serverLevel.getGameTime())) {
            OrganPlacer.grow(serverLevel, pos, OrganKind.SPORE_GLAND);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            OrganData.get(serverLevel).kill(pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
