package com.yayuy.overgrownvegetation.spore.field;

import com.yayuy.overgrownvegetation.network.SporeDustPayload;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 胞子の粉の見た目を送る(2026-10-31 合間の小物 3。元は parked の同名のクラスから浮遊の濃度・巨大樹の梢・地割れの前線を落とした)。
 * 場の 1 回の更新の後({@link SporeSweeping#refresh} の後。印を消した結果を送る)に、プレイヤーのまわり {@value #RADIUS} チャンク
 * (13×13)の降り積もり %({@link SporeSweeping#dustLevel})と払った升の印を、前に送った物と違うときだけ送る。
 * 台帳の過繁茂のチャンク(オーバーワールド)は 0(もう植物の色。降り積もりの値は場に残る)。ブロックには何も書かない。
 */
final class SporeDustSync {

    static final int RADIUS = 6;
    /** プレイヤーごとの前に送った物(左上のチャンク・中身の指紋)。 */
    private static final Map<UUID, long[]> LAST = new HashMap<>();

    private SporeDustSync() {
    }

    static void send(ServerLevel level) {
        boolean overworld = level.dimension() == Level.OVERWORLD;
        int size = RADIUS * 2 + 1;
        for (ServerPlayer p : level.players()) {
            int cx0 = (p.getBlockX() >> 4) - RADIUS;
            int cz0 = (p.getBlockZ() >> 4) - RADIUS;
            byte[] levels = new byte[size * size];
            int[] masks = new int[size * size];
            for (int dz = 0; dz < size; dz++) {
                for (int dx = 0; dx < size; dx++) {
                    int cx = cx0 + dx;
                    int cz = cz0 + dz;
                    int v = SporeSweeping.dustLevel(SporeFieldServer.cell(level, cx, cz));
                    if (v > 0 && overworld && com.yayuy.overgrownvegetation.phase.PhaseData.get(level).isOvergrownChunk(new ChunkPos(cx, cz))) {
                        v = 0;
                    }
                    if (v > 0) {
                        int i = dz * size + dx;
                        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                        levels[i] = (byte) v;
                        masks[i] = chunk != null ? chunk.getData(ModAttachments.SWEPT_DUST) : 0;
                    }
                }
            }
            long[] sign = {cx0, cz0, level.dimension().location().hashCode(), Arrays.hashCode(levels), Arrays.hashCode(masks)};
            if (Arrays.equals(sign, LAST.get(p.getUUID()))) {
                continue;
            }
            LAST.put(p.getUUID(), sign);
            PacketDistributor.sendToPlayer(p, new SporeDustPayload(cx0, cz0, size, levels, masks));
        }
    }

    /** そのプレイヤーの前に送った物を忘れる(ログアウト。同じ専用サーバーへ入り直したとき、場が変わるのを待たずに送り直す。見直し第 2 回 ④2)。 */
    static void forget(UUID player) {
        LAST.remove(player);
    }

    /** ワールドを閉じたら忘れる(次に入ったときに必ず送り直す)。 */
    static void clear() {
        LAST.clear();
    }
}
