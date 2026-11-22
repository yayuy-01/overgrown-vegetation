package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.player.BonemealEvent;

/**
 * 「過繁茂した〇〇」系の汚染フルブロックに骨粉を使ったとき、バニラの草ブロックと同じように
 * 周囲へ装飾植物を生やす({@link CorruptedBonemeal})。
 *
 * <p>汚染ブロックはバニラの {@code BonemealableBlock} を実装していないので、
 * ここでイベントを乗っ取り、{@link BonemealEvent#setSuccessful(boolean)} で骨粉が
 * 使われた扱いにしたうえで骨粉を 1 つ消費する(バニラの草ブロックへの骨粉と同じ挙動)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class CorruptedBonemealHandler {

    @SubscribeEvent
    public static void onBonemeal(BonemealEvent event) {
        if (event.isCanceled()) {
            return;
        }

        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = event.getState();
        if (!state.is(ModTags.CORRUPTED) || !state.isCollisionShapeFullBlock(level, pos)) {
            return;
        }

        if (level instanceof ServerLevel serverLevel) {
            CorruptedBonemeal.apply(serverLevel, pos);
            // バニラの草ブロックへの骨粉と同じく、成否にかかわらず 1 つ消費する
            event.getStack().shrink(1);
        }
        // client/server 両方で「骨粉が使われた」扱いにし、腕振り等の見た目を合わせる
        event.setSuccessful(true);
    }

    private CorruptedBonemealHandler() {
    }
}
