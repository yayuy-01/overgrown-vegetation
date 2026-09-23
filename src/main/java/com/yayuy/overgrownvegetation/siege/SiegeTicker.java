package com.yayuy.overgrownvegetation.siege;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * オーバーワールドの tick から {@link SiegeData#tick(ServerLevel)} を、全ディメンションの tick から
 * {@link ShellTracker#tick(ServerLevel)}(砲台の砲弾)を毎 tick 呼び出す。
 *
 * <p>{@link com.yayuy.overgrownvegetation.raid.RaidTicker} と違って間引いていないが、
 * 中身はボスバーの更新と数十バイトの定期再送だけなので実質ゼロコスト
 * (ボスの「移動」は {@link SiegeBossMotion} の式で決まるため、毎 tick 計算するものが無い)。
 * 進行中でなければ即座に return する。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SiegeTicker {

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        // 砲台の砲弾はどのディメンションでも飛ぶ(ボスへの命中判定はオーバーワールドだけ)。
        ShellTracker.get(serverLevel).tick(serverLevel);
        if (serverLevel.dimension() != Level.OVERWORLD) {
            return;
        }
        SiegeData.get(serverLevel).tick(serverLevel);
    }

    private SiegeTicker() {
    }
}
