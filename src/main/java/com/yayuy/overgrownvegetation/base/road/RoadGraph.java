package com.yayuy.overgrownvegetation.base.road;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterable;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.List;

/**
 * 道のマスをたどって、基地どうしの<b>直接のつながり</b>を求める(順 2 の着手順 7、2026-10-08)。ワールドを読まない計算なので別スレッドで回せる。
 *
 * <p><b>ユーザー指定(10-08)</b>: 数えるのは直接つながった基地だけ = ほかの基地を通らずに道で行ける基地。だから、たどっている途中で
 * ほかの基地の口に着いたら、その基地を数えて、そのマスから先へは進まない(A—B—C なら A から見えるのは B だけ)。
 *
 * <p>Claude 仮決め: マスは 3 次元(プレイヤーが橋で道をまたいでも混ざらない)。隣は斜めを含む 8 方向で、高さの差 1 まで
 * (ハーフ・階段の坂が続いているとみなす)。基地の口は高さを持たない列で、口とその周り 1 マスにかかったマスがその基地に届いたマス。
 */
public final class RoadGraph {

    /** 基地の口: 基地の番号と、口の列({@link ChunkPos#asLong} と同じ詰め方の x, z)。 */
    public record Port(int siteId, long[] columns) {
    }

    /**
     * 基地の番号 → 直接つながった基地の番号。
     *
     * @param cells 道のマス({@link BlockPos#asLong})。数えないマス(覆われた・切れたチャンクにある)は先に除いておく
     * @param ports 基地の口(陥落した基地も入れる —— 数えはしないが、その先へは通さない)
     */
    public static Int2ObjectMap<IntSet> neighbors(LongIterable cells, List<Port> ports) {
        Long2ObjectOpenHashMap<IntArrayList> columns = new Long2ObjectOpenHashMap<>();
        for (LongIterator it = cells.iterator(); it.hasNext(); ) {
            long p = it.nextLong();
            columns.computeIfAbsent(ChunkPos.asLong(BlockPos.getX(p), BlockPos.getZ(p)), k -> new IntArrayList(1)).add(BlockPos.getY(p));
        }
        // 列 → その列にかかる基地(口と、その周り 1 マス)
        Long2IntOpenHashMap owner = new Long2IntOpenHashMap();
        owner.defaultReturnValue(-1);
        for (Port port : ports) {
            for (long c : port.columns()) {
                int x = ChunkPos.getX(c);
                int z = ChunkPos.getZ(c);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        owner.putIfAbsent(ChunkPos.asLong(x + dx, z + dz), port.siteId());
                    }
                }
            }
        }
        Int2ObjectMap<IntSet> out = new Int2ObjectOpenHashMap<>();
        for (Port port : ports) {
            IntSet found = new IntOpenHashSet();
            LongOpenHashSet seen = new LongOpenHashSet();
            LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
            for (long c : port.columns()) {
                int x = ChunkPos.getX(c);
                int z = ChunkPos.getZ(c);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        IntArrayList ys = columns.get(ChunkPos.asLong(x + dx, z + dz));
                        if (ys == null) {
                            continue;
                        }
                        for (int i = 0; i < ys.size(); i++) {
                            long p = BlockPos.asLong(x + dx, ys.getInt(i), z + dz);
                            if (seen.add(p)) {
                                queue.enqueue(p);
                            }
                        }
                    }
                }
            }
            while (!queue.isEmpty()) {
                long p = queue.dequeueLong();
                int x = BlockPos.getX(p);
                int y = BlockPos.getY(p);
                int z = BlockPos.getZ(p);
                int at = owner.get(ChunkPos.asLong(x, z));
                if (at != -1 && at != port.siteId()) {
                    found.add(at); // ほかの基地の口に着いた。数えて、ここから先へは進まない
                    continue;
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        IntArrayList ys = columns.get(ChunkPos.asLong(x + dx, z + dz));
                        if (ys == null) {
                            continue;
                        }
                        for (int i = 0; i < ys.size(); i++) {
                            int y2 = ys.getInt(i);
                            if (Math.abs(y2 - y) > 1 || (dx == 0 && dz == 0 && y2 == y)) {
                                continue;
                            }
                            long q = BlockPos.asLong(x + dx, y2, z + dz);
                            if (seen.add(q)) {
                                queue.enqueue(q);
                            }
                        }
                    }
                }
            }
            out.put(port.siteId(), found);
        }
        return out;
    }

    private RoadGraph() {
    }
}
