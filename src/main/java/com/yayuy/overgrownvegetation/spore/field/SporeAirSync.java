package com.yayuy.overgrownvegetation.spore.field;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.network.SporeAirMapPayload;
import com.yayuy.overgrownvegetation.network.SporeAirPayload;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤーの所の胞子濃度を 5 秒ごと(場の更新と同じ間隔)に送る(2026-10-30、密林の雰囲気の提案 2。{@link SporeAirPayload})。
 * 値は目の高さの {@link SporeConcentration#percentAt}(場のキャッシュを読むだけ)。場が止まっている次元では 0 を送る。
 *
 * <p>2026-11-06 胞子の空気: 同じ間隔で、まわり ±{@value #MAP_RADIUS} チャンクの<b>浮遊</b>の升({@link SporeAirMapPayload})も、前と変わっていれば送る
 * (風下の平原に流れる霞と、視線の先の濁り。林床は入れない = ユーザー決定)。読むのは場の写し({@link SporeFieldServer#airAt})だけ。
 * オーバーワールドでない・場が止まっている({@code /ov corruption false}・spore_field の enabled=false)ときは全部 0(霞を出さない = ユーザー決定)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID)
public final class SporeAirSync {

    private static final int INTERVAL = 100;
    /** 送る範囲(± チャンク。描画距離 16 を覆う。仮決め)。 */
    static final int MAP_RADIUS = 24;
    /** プレイヤーごとの前に送った升(左上のチャンク・次元・中身の指紋)。 */
    private static final Map<UUID, long[]> LAST = new HashMap<>();

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % INTERVAL != 0) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            float percent = (float) SporeConcentration.percentAt(player.serverLevel(), player.blockPosition().above());
            // 卓越風(第 2 段「動き」の林の風の土台)。オーバーワールドの場があるときだけ、無ければ向きは NaN。
            PrevailingWind.Sample wind = player.serverLevel().dimension() == Level.OVERWORLD ? SporeFieldServer.windNow(player.serverLevel()) : null;
            float angle = wind == null ? Float.NaN : (float) Math.atan2(wind.dirZ, wind.dirX);
            float strength = wind == null ? 0.0F : (float) wind.ground(); // 地上の風(凪の日は落ちる。11-16 weather-system)
            PacketDistributor.sendToPlayer(player, new SporeAirPayload(percent, angle, strength));
            sendMap(player);
        }
    }

    /** 浮遊の升を、前と変わっていれば送る。 */
    private static void sendMap(ServerPlayer player) {
        int size = MAP_RADIUS * 2 + 1;
        int cx0 = (player.getBlockX() >> 4) - MAP_RADIUS;
        int cz0 = (player.getBlockZ() >> 4) - MAP_RADIUS;
        byte[] levels = new byte[size * size];
        boolean running = player.serverLevel().dimension() == Level.OVERWORLD && SporeFieldServer.enabled() && !CorruptionSwitch.isOff();
        if (running) {
            double full = DesignData.sporeField().fullAt();
            for (int dz = 0; dz < size; dz++) {
                for (int dx = 0; dx < size; dx++) {
                    double air = SporeFieldServer.airAt(cx0 + dx, cz0 + dz);
                    if (air > 0.0) { // NaN(写しが無い)は 0
                        levels[dz * size + dx] = SporeAirMapPayload.encode(air / full * 100.0);
                    }
                }
            }
        }
        long[] sign = {cx0, cz0, player.serverLevel().dimension().location().hashCode(), Arrays.hashCode(levels)};
        if (Arrays.equals(sign, LAST.get(player.getUUID()))) {
            return;
        }
        LAST.put(player.getUUID(), sign);
        PacketDistributor.sendToPlayer(player, new SporeAirMapPayload(cx0, cz0, size, levels));
    }

    /** ワールドを閉じたら忘れる(次に入ったときに必ず送り直す)。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LAST.clear();
    }

    /** 抜けたプレイヤーを忘れる(入り直したら送り直す)。 */
    @SubscribeEvent
    public static void onLoggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        LAST.remove(event.getEntity().getUUID());
    }

    private SporeAirSync() {
    }
}
