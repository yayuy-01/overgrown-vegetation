package com.yayuy.overgrownvegetation.wall;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 蠢く壁の<b>前触れ</b>(決定 その 1「洞窟の環境音・水滴が急に止む → 奥から何かが擦れる低い地鳴りが近づき、灯りが奥から順に消えていく。逃げ始める数秒がある」)。
 * 壁が動き出す前の {@link WallTuning#omenTicks} の間:
 * <ul>
 *     <li>音が止む: 近くのプレイヤーへ知らせ、画面の側で環境音と水滴の音を消す({@code client/wall/WallQuiet})。</li>
 *     <li>地鳴り: 1 秒ごとに、始まりの方角の少し先で低い音(仮にバニラのウォーデンが地面を掘る音を低くして。本当の音は後で)。だんだん大きく。</li>
 *     <li>灯りが奥から消える: 始まりから洞窟をたどった道のりの近い順に、いちばん近い人までの道のりを前触れの長さで割った速さで消す。</li>
 * </ul>
 */
final class WallOmen {

    /** 道のりを調べる上限(升)。 */
    private static final int SEARCH_CAP = 60_000;

    private final CreepingWall wall;
    private final long begin;
    private final LongArrayList lights = new LongArrayList();
    private final it.unimi.dsi.fastutil.ints.IntArrayList lightDist = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private int nextLight;
    /** いちばん近い人までの道のり(見つからなければ調べた中でいちばん遠い道のり)。 */
    private int playerDist;

    WallOmen(CreepingWall wall, long now) {
        this.wall = wall;
        this.begin = now;
        survey();
    }

    /** 始まりから 6 方向に洞窟をたどり、灯りと人までの道のりを調べる(1 回だけ)。 */
    private void survey() {
        ServerLevel level = wall.level;
        Long2IntOpenHashMap seen = new Long2IntOpenHashMap();
        Long2IntOpenHashMap roofs = new Long2IntOpenHashMap();
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        long o = wall.origin.asLong();
        seen.put(o, 0);
        q.enqueue(o);
        LongArrayList playerCells = new LongArrayList();
        for (ServerPlayer p : level.players()) {
            playerCells.add(p.blockPosition().asLong());
            playerCells.add(p.blockPosition().above().asLong());
        }
        int found = -1, maxD = 0;
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        while (!q.isEmpty() && seen.size() < SEARCH_CAP) {
            long p = q.dequeueLong();
            int d = seen.get(p);
            maxD = Math.max(maxD, d);
            if (found < 0 && playerCells.contains(p)) {
                found = d;
            }
            BlockState s = level.getBlockState(n.set(p));
            if (WallCells.isLight(s)) {
                lights.add(p);
                lightDist.add(d);
            }
            for (Direction dir : Direction.values()) {
                n.set(p).move(dir);
                long np = n.asLong();
                if (seen.containsKey(np) || !CreepingWall.loadedNow(level, n)) {
                    continue;
                }
                WallCells.Kind k = WallCells.classify(level, n, roofs);
                if (k == WallCells.Kind.PASS) {
                    seen.put(np, d + 1);
                    q.enqueue(np);
                }
            }
        }
        playerDist = Math.max(4, found >= 0 ? found : maxD);
    }

    /** 前触れの 1 tick。 */
    void tick(long now) {
        ServerLevel level = wall.level;
        double f = Math.min(1.0D, (now - begin) / (double) Math.max(1, WallTuning.omenTicks));
        // 灯りが奥から順に消える(道のりが f × 人までの道のり 以下のもの)。BFS の順なので道のりは小さい順に並んでいる
        double limit = f * playerDist;
        while (nextLight < lights.size() && lightDist.getInt(nextLight) <= limit) {
            BlockPos p = BlockPos.of(lights.getLong(nextLight++));
            if (!CreepingWall.loadedNow(level, p)) {
                continue;
            }
            BlockState s = level.getBlockState(p);
            if (WallCells.isLight(s)) {
                BlockState out = s.getFluidState().createLegacyBlock();
                com.yayuy.overgrownvegetation.corruption.CorruptionDropSuppressor.run(
                        () -> level.setBlock(p, out, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
            }
        }
        // 地鳴り(1 秒ごと。始まりの方角に 10 ブロック先で鳴らすと、遠くても方角が分かる)
        if ((now - begin) % 20 == 0) {
            float volume = (float) (0.5D + 1.5D * f);
            for (ServerPlayer pl : level.players()) {
                Vec3 at = pl.position();
                Vec3 to = Vec3.atCenterOf(wall.origin).subtract(at);
                if (to.lengthSqr() > 128 * 128) {
                    continue;
                }
                Vec3 src = to.lengthSqr() < 100 ? Vec3.atCenterOf(wall.origin) : at.add(to.normalize().scale(10.0D));
                level.playSound(null, src.x, src.y, src.z, SoundEvents.WARDEN_DIG, SoundSource.HOSTILE, volume, 0.5F);
            }
        }
    }
}
