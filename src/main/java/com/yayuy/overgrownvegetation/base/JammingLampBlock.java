package com.yayuy.overgrownvegetation.base;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.block.RepellentLampBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 妨害灯(2026-10-01)。忌避灯の上位: 点いているあいだ半径の中にグループ6 以下が入ってこず、
 * 半径の中の過繁茂の Mob は攻撃力と移動速度が落ちる(ユーザー指定)。同盟軍の道の支点にもなる(道の台帳は未実装)。
 *
 * <p>{@link RepellentLampBlock} の子なので、忌避灯を見ている処理(TD の群れが覆いに行く・壊さずに覆う、
 * ヴェロフィルの爆撃で蔦に覆われる、右クリックで剥がす)はそのまま効く。覆う/剥がすときは
 * 忌避灯の台帳と {@link JammingLamps} の両方を出し入れする。
 */
public class JammingLampBlock extends RepellentLampBlock {

    public static final MapCodec<JammingLampBlock> CODEC = simpleCodec(JammingLampBlock::new);

    public JammingLampBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(LIT, true)); // 基地の灯りは電池なしで点いたまま(2026-10-24、忌避灯の電池化)
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new JammingLampBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null
                : createTickerHelper(type, BaseBlocks.JAMMING_LAMP_ENTITY.get(), JammingLampBlockEntity::serverTick);
    }

    @Override
    public void setCovered(Level level, BlockPos pos, BlockState state, boolean covered) {
        boolean was = state.getValue(COVERED);
        super.setCovered(level, pos, state, covered);
        if (was == covered) {
            return;
        }
        if (covered) {
            JammingLamps.unregister(level, pos);
        } else {
            JammingLamps.register(level, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            JammingLamps.unregister(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
