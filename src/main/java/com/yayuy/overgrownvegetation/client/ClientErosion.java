package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.ErosionPayload;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * クライアント側の浸食度の控え(2026-09-22)。{@link ErosionPayload} で届いた値を
 * エンティティ id ごとに持っておき、描画(スキンの色かぶり・苔のオーバーレイ)と HUD が読む。
 *
 * <p>サーバーは値が動いたときにしか送らないので、ここが唯一の参照先になる。
 * 届いていないプレイヤーは 0 として扱う(まだ追跡していない = 描く必要が無い)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class ClientErosion {

    private static final Int2FloatOpenHashMap BY_ENTITY = new Int2FloatOpenHashMap();

    static {
        BY_ENTITY.defaultReturnValue(0.0F);
    }

    /** 届いた値を控える。0 になったら覚えておく必要が無いので捨てる。 */
    public static void accept(ErosionPayload payload) {
        if (payload.percent() <= 0.0F) {
            BY_ENTITY.remove(payload.entityId());
        } else {
            BY_ENTITY.put(payload.entityId(), payload.percent());
        }
    }

    /** そのプレイヤーの浸食度(0〜100)。届いていなければ 0。 */
    public static float percentOf(Player player) {
        return BY_ENTITY.get(player.getId());
    }

    /** 浸食度の効き具合(0〜1)。描画の濃さはこれを掛けて出す。 */
    public static float scaleOf(Player player) {
        return Math.min(1.0F, Math.max(0.0F, percentOf(player) / 100.0F));
    }

    /**
     * ワールドを抜けたら捨てる。エンティティ id は次のワールドで別人に振り直されるので、
     * 残したままだと<b>無関係のプレイヤーが苔むして見える</b>。
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        BY_ENTITY.clear();
    }

    private ClientErosion() {
    }
}
