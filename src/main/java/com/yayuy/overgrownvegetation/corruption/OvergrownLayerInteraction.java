package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * <b>覆われたマスを「ただのブロック」にする</b>(2026-09-22、要望)。
 *
 * <p>植物に覆われたブロックは<b>置き換えていない</b>(元のブロックのまま)ので、放っておくと
 * <b>金床や砥石の UI が普通に開いてしまう</b>。ここで右クリックを受け付けなくする。
 *
 * <p>止めるのは<b>ブロックへの操作だけ</b>({@code setUseBlock(DENY)}) ——
 * 手に持っているアイテムの使用は通すので、覆われたブロックに向かって別のブロックを置くことはできる。
 * 壊すのも通常どおり(左クリックには触れない)。
 *
 * <h2>止まらないもの</h2>
 * <p><b>レッドストーンで動く分は止まらない</b> —— 覆われたドアがレバーで開き、音符ブロックが鳴る。
 * ブロックそのものを差し替えていないので、そこまでは止められない。
 * <b>自分で動くもの</b>({@link net.minecraft.world.level.block.entity.BlockEntity} を持つもの ——
 * 他 MOD の機械・かまどなど)は差し替えて完全に止めている({@code block/OvergrownHuskBlock})。
 *
 * <p>この割り切りは<b>意図的</b>。中身を持たないブロックまで差し替えると
 * BlockEntity が数千個に膨れるので、記録 8 バイト + 右クリック抑止で済ませている
 * ({@link OvergrownLayerData})。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OvergrownLayerInteraction {

    private OvergrownLayerInteraction() {
    }

    /**
     * 覆われたブロックは右クリックに応えない。
     *
     * <p>優先度を上げてあるのは、ほかの MOD が先に「使った」ことにしてしまう前に止めるため。
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (OvergrownLayerData.isCovered(level, event.getPos())) {
            event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
        }
    }

    /** チャンクを見始めたプレイヤーへ、そのチャンクの記録をまるごと送る。 */
    @SubscribeEvent
    public static void onChunkWatch(ChunkWatchEvent.Sent event) {
        ServerPlayer player = event.getPlayer();
        LevelChunk chunk = event.getChunk();
        OvergrownLayerData.sendAll(player, chunk);
    }

    /** この tick で新しく覆われたぶんを、まとめて送る。 */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            OvergrownLayerData.flush(level);
        }
    }
}
