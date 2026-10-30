package com.yayuy.overgrownvegetation.client.horde;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.CorruptionAtmosphere;
import com.yayuy.overgrownvegetation.network.HordeSightPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * いまの<b>見える距離</b>をサーバーへ知らせる(2026-09-26。{@link HordeSightPayload})。サーバーは TD の仮想の群れをこの外で
 * 生ませるので、遠景の身代わりの絵が霧の中から歩いて出てくる(ユーザー指定「mob が絵として湧く場所をもっと遠くから。外周の霧の
 * 中から出てくる感じに」)。
 *
 * <p>見える距離 = 描画距離(サーバーの描画距離で切られた実際の値)と、地形の霧の終わり(過繁茂の霧 96・極相 48。境目では寄せている
 * 途中の値)の近い方。水・溶岩の中の霧は数えない(出ればすぐ見えるので)。{@value #CHECK_TICKS} tick ごとに見て、
 * {@value #MIN_CHANGE} ブロック以上変わったときだけ送る(入った直後は必ず送る)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class HordeSight {

    /** 見る間隔(tick)。 */
    private static final int CHECK_TICKS = 10;
    /** これ以上変わったら送る(ブロック)。 */
    private static final int MIN_CHANGE = 4;

    /** 最後に送った値(-1 = まだ送っていない)。 */
    private static int lastSent = -1;
    private static int ticks;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            lastSent = -1;
            return;
        }
        if (++ticks < CHECK_TICKS) {
            return;
        }
        ticks = 0;
        int sight = sight(minecraft.options.getEffectiveRenderDistance(), CorruptionAtmosphere.terrainFogFar());
        if (lastSent < 0 || Math.abs(sight - lastSent) >= MIN_CHANGE) {
            PacketDistributor.sendToServer(new HordeSightPayload(sight));
            lastSent = sight;
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        lastSent = -1;
    }

    /** 描画距離(チャンク)と地形の霧の終わり(ブロック。負 = 上書きしていない)から、見える距離(ブロック)。 */
    static int sight(int renderChunks, float terrainFogFar) {
        double render = renderChunks * 16.0D;
        return (int) Math.ceil(terrainFogFar > 0.0F ? Math.min(render, terrainFogFar) : render);
    }

    private HordeSight() {
    }
}
