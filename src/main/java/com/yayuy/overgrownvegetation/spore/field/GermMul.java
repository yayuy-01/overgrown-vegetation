package com.yayuy.overgrownvegetation.spore.field;

import it.unimi.dsi.fastutil.longs.Long2FloatMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;

/**
 * 地図の上の生態系(2026-11-21 {@code ecosystem.map})から胞子の場への口: 升(2^shift チャンク四方)ごとの<b>発芽のしきい値の倍率</b>(1 未満 = 芽吹きやすい)。
 * 升の生き物の感染の割合が上がるほど小さくなる。場の計算は裏のスレッドなので、作ったら書き換えない写しとして {@link SporeFieldServer#INPUTS} で渡す。
 * 載っていない升は 1(今までどおり)。区画({@link SporeField#TB})ごとに「載っている升があるか」も持ち、区画の中を見る前に飛ばせるようにする。
 */
public final class GermMul {

    private final Long2FloatOpenHashMap byCell;
    private final LongOpenHashSet tiles = new LongOpenHashSet();
    private final int shift;

    /** {@code cells}: 升のキー(ChunkPos.asLong(cx >> shift, cz >> shift))→ 倍率(0〜1)。写して持つ。 */
    public GermMul(Long2FloatOpenHashMap cells, int shift) {
        this.shift = Math.max(0, Math.min(SporeField.TB, shift));
        this.byCell = new Long2FloatOpenHashMap(cells.size());
        this.byCell.defaultReturnValue(1f);
        for (Long2FloatMap.Entry e : cells.long2FloatEntrySet()) {
            float v = e.getFloatValue();
            if (!(v < 1f)) continue;
            long k = e.getLongKey();
            byCell.put(k, Math.max(0.01f, v));
            int cx = ChunkPos.getX(k) << this.shift, cz = ChunkPos.getZ(k) << this.shift;
            tiles.add(ChunkPos.asLong(cx >> SporeField.TB, cz >> SporeField.TB));
        }
    }

    public boolean isEmpty() {
        return byCell.isEmpty();
    }

    public int size() {
        return byCell.size();
    }

    /** その区画(区画の座標)に倍率の付いた升があるか。 */
    boolean hasTile(int tx, int tz) {
        return tiles.contains(ChunkPos.asLong(tx, tz));
    }

    /** チャンク(cx, cz)のしきい値の倍率。 */
    public float at(int cx, int cz) {
        return byCell.get(ChunkPos.asLong(cx >> shift, cz >> shift));
    }

    /** 場へ渡す(次の更新の頭で当てはまる)。null で外す。 */
    public static void publish(GermMul m) {
        SporeFieldServer.INPUTS.add(f -> f.germMul = m);
    }
}
