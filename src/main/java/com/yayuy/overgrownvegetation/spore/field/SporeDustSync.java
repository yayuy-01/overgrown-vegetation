package com.yayuy.overgrownvegetation.spore.field;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.network.SporeDustPayload;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 胞子の粉の見た目を送る(2026-10-23 胞子の場と風 第 2 段 E の (1)。【決定】粉は地面の見た目に重ねるだけ・屋根にも積もる)。
 * 場の 1 回の更新の後に、プレイヤーのまわり 13×13 チャンクの降り積もりを 8 段(発芽のしきい値で 7)にして、前と変わっていれば送る。
 * 汚染済みのチャンクは 0(もう植物の色)。ブロックには何も書かない。
 *
 * <p>シャベルで払った升の印({@link ModAttachments#SWEPT_DUST}、{@link SporeSweeping})も一緒に送る。降り積もりが払う直前の値まで
 * 戻った・汚染された・粉が無くなったチャンクの印は、送る前に {@link SporeSweeping#refresh} で消す(また粉が出る)。
 */
final class SporeDustSync {

    static final int RADIUS = 6;

    private SporeDustSync() {
    }

    /**
     * 粉の濃さ = 胞子計の「降り積もり %」(発芽のしきい値 = 100、0〜100 で頭打ち。2026-10-24: 前は 0〜7 の段で、しきい値の 4% で
     * もう 1 段目が付き、胞子計 1% の所でも縁の隣のチャンクから濃く混ざって見えた)。汚染済み・写しの無いチャンクは 0。
     */
    static int dustLevel(SporeFieldServer.Cell c) {
        if (c == null || c.stage() > 0) return 0;
        double threshold = DesignData.sporeField().field().germThreshold;
        return (int) Math.max(0, Math.min(100, Math.round(c.dep() / threshold * 100.0D)));
    }

    /**
     * 送る(その次元の場を 1 回更新した後)。オーバーワールドとネザーの場から呼ぶ。粉(降り積もり)もどちらでも。
     * 2026-10-23 G から、浮遊の濃度・プレイヤーの所の濃度・近くの巨大樹の梢も一緒に送る(緑の霧とプルームの描画)。
     */
    static void send(ServerLevel level) {
        boolean overworld = level.dimension() == net.minecraft.world.level.Level.OVERWORLD;
        SporeSweeping.refresh(level);
        int size = RADIUS * 2 + 1;
        double fullAt = DesignData.sporeField().fullAt();
        for (ServerPlayer p : level.players()) {
            int cx0 = (p.getBlockX() >> 4) - RADIUS, cz0 = (p.getBlockZ() >> 4) - RADIUS;
            byte[] levels = new byte[size * size];
            int[] masks = new int[size * size];
            byte[] air = new byte[size * size];
            for (int dz = 0; dz < size; dz++)
                for (int dx = 0; dx < size; dx++) {
                    int cx = cx0 + dx, cz = cz0 + dz;
                    int i = dz * size + dx;
                    double a = SporeFieldServer.airAt(level, cx, cz) / fullAt * 100.0D;
                    air[i] = (byte) Math.min(255, Math.max(0, (int) Math.round(a / 2.0D)));
                    SporeFieldServer.Cell c = SporeFieldServer.cell(level, cx, cz); // ネザーでも粉を見せる(2026-10-23 相談セッションの答え)
                    int v = dustLevel(c);
                    // 汚染済み(台帳の過繁茂)のチャンクには粉を見せない(降り積もりの値は残す。2026-10-24)
                    if (v > 0 && overworld && com.yayuy.overgrownvegetation.phase.PhaseData.get(level).isOvergrownChunk(new net.minecraft.world.level.ChunkPos(cx, cz))) v = 0;
                    if (v > 0) {
                        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                        levels[i] = (byte) v;
                        masks[i] = chunk != null ? chunk.getData(ModAttachments.SWEPT_DUST) : 0;
                    }
                }
            double here = com.yayuy.overgrownvegetation.spore.SporeConcentration.percentAt(level, net.minecraft.core.BlockPos.containing(p.getEyePosition()));
            int hereTenths = (int) Math.min(100000, Math.round(here * 10.0D));
            int[] trees = overworld ? trees(level, p) : new int[0];
            PacketDistributor.sendToPlayer(p, new SporeDustPayload(cx0, cz0, size, levels, masks, air, hereTenths, trees,
                    overworld ? SporeFieldServer.frontsForClient() : new int[0]));
        }
    }

    /** 巨大樹の梢を探す距離(ブロック)。 */
    private static final int TREE_RANGE = 320;

    /** プレイヤーの近くの、ハイヴノードを倒されていない巨大樹の梢 {x, z, (梢の高さ << 8) | 育ち具合}。梢のチャンクが読み込まれていなければ出さない。 */
    private static int[] trees(ServerLevel level, ServerPlayer p) {
        com.yayuy.overgrownvegetation.phase.PhaseData pd = com.yayuy.overgrownvegetation.phase.PhaseData.get(level);
        com.yayuy.overgrownvegetation.data.SporeFieldTable t = DesignData.sporeField();
        long now = level.getGameTime();
        it.unimi.dsi.fastutil.ints.IntArrayList out = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (long tr : pd.giantTreeOriginSnapshot()) {
            if (pd.isGiantTreeDead(tr)) continue;
            int x = (int) (tr >> 32), z = (int) tr;
            double dx = x - p.getX(), dz = z - p.getZ();
            if (dx * dx + dz * dz > TREE_RANGE * TREE_RANGE) continue;
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
            int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
            long born = pd.giantTreeBorn(tr);
            double age = born == Long.MIN_VALUE ? Double.MAX_VALUE : (now - born) / 24000.0D;
            double rate = t.giantTreePerDay(age) / Math.max(1.0D, t.giantTreePerDay(Double.MAX_VALUE));
            out.add(x);
            out.add(z);
            out.add((Math.max(0, top) << 8) | (int) Math.round(Math.min(1.0D, rate) * 255));
            if (out.size() >= 3 * 16) break;
        }
        return out.toIntArray();
    }

    static void clear() {
        SporeSweeping.clear();
    }
}
