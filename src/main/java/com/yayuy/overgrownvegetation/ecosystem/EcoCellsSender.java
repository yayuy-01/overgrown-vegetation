package com.yayuy.overgrownvegetation.ecosystem;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.network.EcoCellsPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.player.PlayerEvent;
import com.yayuy.overgrownvegetation.port.event.tick.ServerTickEvent;
import com.yayuy.overgrownvegetation.port.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * まわりの升の環境生物の賑わいをプレイヤーへ送る(2026-11-17。{@link EcoCellsPayload})。
 * 見た目だけの小さな生き物の数を、その升の頭数に合わせるため(狩りすぎた升は虫も減る。ユーザー 11-17)。台帳は読むだけ。
 * <ul>
 *   <li>{@link #CHECK_EVERY} tick ごとに調べ、中心の升か値(1/255 刻み)が変わったプレイヤーにだけ送る。</li>
 *   <li>入ったとき・ディメンションを移ったときはすぐ送る。オーバーワールドの外・台帳が切ってあるときは値なし(クライアントは満員と読む)。</li>
 * </ul>
 * 数はどれも【仮】。半径は small-critters(見た目)と決める。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class EcoCellsSender {

    /** 調べる間(tick)。【仮】 */
    public static final int CHECK_EVERY = 40;
    /** 送る範囲(升)。中心から半径 2 = 5×5 升(4×4 チャンクなら 320 ブロック四方)。【仮】 */
    public static final int RADIUS = 2;

    /** 最後に送った中身(プレイヤーごと。サーバーのスレッドだけで触る)。 */
    private static final Map<UUID, EcoCellsPayload> LAST = new HashMap<>();

    private EcoCellsSender() {
    }

    /** 中心の升 (centerX, centerZ) のまわりの値を作る(純粋。GameTest からも)。 */
    public static EcoCellsPayload build(EcoLedger ledger, int centerX, int centerZ, int radius, long now) {
        EcoTable t = DesignData.ecosystem();
        int side = 2 * radius + 1;
        byte[] prey = new byte[side * side], hunter = new byte[side * side];
        int i = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int chunkX = (centerX + dx) << t.cellShift, chunkZ = (centerZ + dz) << t.cellShift;
                prey[i] = EcoCellsPayload.pack(ledger.heads(Ecosystem.PREY, chunkX, chunkZ, now) / t.fullPrey);
                hunter[i] = EcoCellsPayload.pack(ledger.heads(Ecosystem.HUNTER, chunkX, chunkZ, now) / t.fullHunter);
                i++;
            }
        }
        return new EcoCellsPayload(centerX, centerZ, (byte) t.cellShift, (byte) radius, prey, hunter);
    }

    /** そのプレイヤーに送る中身。 */
    public static EcoCellsPayload compute(ServerPlayer player) {
        if (player.level().dimension() != Level.OVERWORLD || !DesignData.ecosystem().enabled) return EcoCellsPayload.NONE;
        ServerLevel level = (ServerLevel) player.level();
        int shift = DesignData.ecosystem().cellShift;
        int cx = (player.getBlockX() >> 4) >> shift, cz = (player.getBlockZ() >> 4) >> shift;
        return build(EcoLedger.get(level), cx, cz, RADIUS, EcoLedger.now(level));
    }

    /** 変わっていれば送る。force なら必ず送る。送ったら true。 */
    static boolean update(ServerPlayer player, boolean force) {
        EcoCellsPayload p = compute(player);
        EcoCellsPayload prev = LAST.get(player.getUUID());
        if (!force && p.sameAs(prev)) return false;
        LAST.put(player.getUUID(), p);
        PacketDistributor.sendToPlayer(player, p);
        return true;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % CHECK_EVERY != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) update(player, false);
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) update(sp, true);
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) update(sp, true);
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST.remove(event.getEntity().getUUID());
    }
}
