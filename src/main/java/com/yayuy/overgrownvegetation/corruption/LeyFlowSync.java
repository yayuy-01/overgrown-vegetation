package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.LeyFlowPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * チャンクを見始めたプレイヤーへ、そのチャンクの<b>地脈の通り道</b>を送る(2026-10-16、{@link LeyFlowPayload})。
 * クライアントは過繁茂の森で時々、この線に沿って光の帯と粒を流す(見た目だけ。ブロックは置かない)。
 *
 * <p>線の通らないチャンクは {@link LeyVeinField#hasVeinInChunk}(81 点)で先に落とし、何も送らない。通るチャンクだけ 256 マスを見る。
 * 地上の次元(空のある次元)だけ。バイオームは見ない(過繁茂かどうかはクライアントが描くときに見る)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class LeyFlowSync {

    @SubscribeEvent
    public static void onChunkWatch(ChunkWatchEvent.Sent event) {
        ServerLevel level = event.getLevel();
        if (!level.dimensionType().hasSkyLight() || level.dimensionType().hasCeiling()) {
            return;
        }
        ChunkPos pos = event.getPos();
        long seed = level.getSeed();
        if (!LeyVeinField.hasVeinInChunk(seed, pos.x, pos.z)) {
            return;
        }
        double[] flow = new double[4];
        long[] buffer = new long[256];
        int count = 0;
        double[] targets = targetsNear(level, pos.getMiddleBlockX(), pos.getMiddleBlockZ());
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int bx = pos.getMinBlockX() + x;
                int bz = pos.getMinBlockZ() + z;
                if (LeyVeinField.flowAt(seed, bx, bz, flow)) {
                    // 光の流れをたどる(2026-10-16 ユーザー指定 ⑦): 近くの行き先(ハイヴノード・巨大樹)へ向かう側へ向きをそろえる。
                    // 線の上で行き先にいちばん近い所へ両側から流れ込む(器官は 10-23 に削除)。
                    if (towardFlip(targets, bx + 0.5, bz + 0.5, flow[1], flow[2])) {
                        flow[1] = -flow[1];
                        flow[2] = -flow[2];
                        flow[3] = -flow[3];
                    }
                    buffer[count++] = LeyFlowPayload.pack(x, z, flow[0], flow[1], flow[2], flow[3]);
                }
            }
        }
        if (count == 0) {
            return;
        }
        ServerPlayer player = event.getPlayer();
        PacketDistributor.sendToPlayer(player, new LeyFlowPayload(pos, java.util.Arrays.copyOf(buffer, count)));
        if (DEV_LOG) {
            sent += count;
            if (++sentChunks % 20 == 0) {
                OvergrownVegetation.LOGGER.info("LeyFlowSync 送ったチャンク {}・マス {}", sentChunks, sent);
            }
        }
    }

    /** 光の流れの行き先を探す距離(チャンクの真ん中から水平に、ブロック)。仮決め。 */
    static final double TARGET_RANGE = 256.0D;

    /** チャンクの真ん中 (cx, cz) から {@link #TARGET_RANGE} 以内の行き先の水平の位置 {x0, z0, x1, z1, …}(ハイヴノード・巨大樹の中心)。 */
    static double[] targetsNear(ServerLevel level, int cx, int cz) {
        it.unimi.dsi.fastutil.doubles.DoubleArrayList out = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
        double r2 = TARGET_RANGE * TARGET_RANGE;
        com.yayuy.overgrownvegetation.phase.PhaseData phase = com.yayuy.overgrownvegetation.phase.PhaseData.get(level);
        for (long p : phase.hiveNodeSnapshot()) {
            add(out, net.minecraft.core.BlockPos.getX(p) + 0.5, net.minecraft.core.BlockPos.getZ(p) + 0.5, cx, cz, r2);
        }
        for (long p : phase.giantTreeOriginSnapshot()) {
            add(out, (int) (p >> 32) + 0.5, (int) p + 0.5, cx, cz, r2);
        }
        return out.toDoubleArray();
    }

    private static void add(it.unimi.dsi.fastutil.doubles.DoubleArrayList out, double x, double z, int cx, int cz, double r2) {
        if ((x - cx) * (x - cx) + (z - cz) * (z - cz) <= r2) {
            out.add(x);
            out.add(z);
        }
    }

    /** (x, z) で線の向き (dirX, dirZ) を、いちばん近い行き先の方へ向けるには裏返すか。行き先が無ければ裏返さない(前の向きのまま)。 */
    public static boolean towardFlip(double[] targets, double x, double z, double dirX, double dirZ) {
        double best = Double.MAX_VALUE;
        double tx = 0.0D;
        double tz = 0.0D;
        for (int i = 0; i + 1 < targets.length; i += 2) {
            double d = (targets[i] - x) * (targets[i] - x) + (targets[i + 1] - z) * (targets[i + 1] - z);
            if (d < best) {
                best = d;
                tx = targets[i];
                tz = targets[i + 1];
            }
        }
        if (best == Double.MAX_VALUE) {
            return false;
        }
        return dirX * (tx - x) + dirZ * (tz - z) < 0.0D;
    }

    /** Claude のテストクライアント(-Dov.devbridge=true)だけ、送った数をログへ出す。 */
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");
    private static int sentChunks;
    private static int sent;

    private LeyFlowSync() {
    }
}
