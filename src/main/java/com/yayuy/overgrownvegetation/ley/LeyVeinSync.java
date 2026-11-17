package com.yayuy.overgrownvegetation.ley;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.LeyLines;
import com.yayuy.overgrownvegetation.network.LeyVeinMapPayload;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 岩肌の地脈の筋のための升を送る(2026-11-14 シェーダーの仕上げ F。{@link LeyVeinMapPayload})。
 * プレイヤーのまわり {@value #SIZE}×{@value #SIZE} 升(1 升 4 ブロック = 192 ブロック四方)の、地脈の線までの距離と汚染地域かどうか。
 * 1 秒ごとに見て、升の真ん中から {@value #RESEND} ブロックより離れた・次元が変わった・中身(バイオーム)が変わったときだけ送る。
 * 地脈の線はオーバーワールドだけ(ほかの次元へは空の升を送って消す)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID)
public final class LeyVeinSync {

    static final int SIZE = 48;
    static final int RESEND = 32;
    private static final int INTERVAL = 20;

    /** プレイヤーごとの前に送った升(左上・次元・中身の指紋)。 */
    private static final Map<UUID, long[]> LAST = new HashMap<>();

    private LeyVeinSync() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % INTERVAL != 0) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            send(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST.remove(event.getEntity().getUUID());
    }

    private static void send(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        long dim = level.dimension().location().hashCode();
        long[] last = LAST.get(player.getUUID());
        if (level.dimension() != Level.OVERWORLD) {
            if (last == null || last[2] != dim) {
                LAST.put(player.getUUID(), new long[]{0, 0, dim, 0});
                PacketDistributor.sendToPlayer(player, new LeyVeinMapPayload(0, 0, 0, new byte[0], new byte[0]));
            }
            return;
        }
        int cell = LeyVeinMapPayload.CELL;
        int half = SIZE * cell / 2;
        int minX;
        int minZ;
        if (last != null && last[2] == dim && Math.abs(player.getBlockX() - (last[0] + half)) <= RESEND
                && Math.abs(player.getBlockZ() - (last[1] + half)) <= RESEND) {
            // 升の位置はそのまま(中身 = バイオームが変わったかだけ見る)。
            minX = (int) last[0];
            minZ = (int) last[1];
        } else {
            minX = Math.floorDiv(player.getBlockX() - half, cell) * cell;
            minZ = Math.floorDiv(player.getBlockZ() - half, cell) * cell;
        }
        long seed = level.getSeed();
        byte[] distance = new byte[SIZE * SIZE];
        byte[] polluted = new byte[SIZE * SIZE];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int y = player.getBlockY();
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                int x = minX + i * cell + cell / 2;
                int z = minZ + j * cell + cell / 2;
                double d = LeyLines.distanceToLine(seed, x, z);
                distance[j * SIZE + i] = (byte) Math.min(255, (int) Math.round(d * 4.0));
                // 11-18 軽量化 ④: 読み込まれていない所のバイオームは読まない(着いた直後、生成中のチャンクを待って 1.6 秒止まっていた)。
                // そこはクライアントにも描かれていない。読み込まれると中身が変わるので次の 1 秒で送り直す
                polluted[j * SIZE + i] = com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.ready(level, x >> 4, z >> 4) && ModBiomes.isOvergrownLike(level.getBiome(pos.set(x, y, z))) ? (byte) 0xFF : 0;
            }
        }
        long[] sign = {minX, minZ, dim, 31L * Arrays.hashCode(distance) + Arrays.hashCode(polluted)};
        if (Arrays.equals(sign, last)) {
            return;
        }
        LAST.put(player.getUUID(), sign);
        PacketDistributor.sendToPlayer(player, new LeyVeinMapPayload(minX, minZ, SIZE, distance, polluted));
    }
}
