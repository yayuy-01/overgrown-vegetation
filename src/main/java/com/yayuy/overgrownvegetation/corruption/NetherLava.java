package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.data.NetherSpreadTable;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * <b>ネザーの溶岩の作り変え</b>(2026-11-11 骨組み)。溶岩は表面が覆われるだけで地中に残る(ユーザー決定)。
 *
 * <ul>
 *     <li><b>段階 2 の殻</b>({@link #crustRing}): 溶岩の海の表面(高さ {@link #surfaceY} で真上が空いている所)を、
 *     岸(溶岩でない横のマス)に接している所から 1 輪ずつ冷えた殻にする。岸から沖へ帯のように進むので、光の計算し直しも帯ごとにまとまる。</li>
 *     <li><b>段階 4 の湖</b>({@link #lakeRing}): 殻の下に溶岩がある列を、岸か湖に接している所から 1 輪ずつ湖へ。
 *     上 2 段を水・その下 1 段を殻({@code crystalChance} で熱の結晶を含む殻)。溶岩が 2 段しかない所は水 1 段 + 殻、1 段の所は殻のまま。
 *     水面は元の溶岩の高さなので岸の形は変わらない。洞窟の下(真上が空いていない)の溶岩は触らない。</li>
 *     <li><b>土手</b>: 水を置く高さで、横のマスが溶岩なら先に冷えた殻にする(バニラでは水が溶岩の源泉に触れると黒曜石・流れに触れると丸石)。
 *     隣の列が後で湖になるときは、その列の殻が水に置き換わる(土手は「殻の下に溶岩がある列」として同じ規則で湖になる)。</li>
 *     <li><b>海の外の溶岩</b>({@link #outsideSection}、段階 2): 海面より上の、空気に触れた溶岩の源泉を殻に、流れている溶岩は上流の源泉を辿って殻に
 *     ({@link LavaSolidifier} の辿り方の殻の版)。マグマブロック(溶岩の岸)も冷えた殻へ。</li>
 * </ul>
 * 置くのは {@link NetherGround#put}(近隣の更新も形の更新も出さない)。
 */
public final class NetherLava {

    private NetherLava() {
    }

    /** 溶岩の海の表面の高さ(ネザーの海面 32 の 1 つ下 = 31)。 */
    public static int surfaceY(Level level) {
        // Level#getSeaLevel は 1.21.1 ではどの次元も 63 を返す。ネザーの海面(32)は地形の生成器が持っている
        if (level instanceof net.minecraft.server.level.ServerLevel server) {
            return server.getChunkSource().getGenerator().getSeaLevel() - 1;
        }
        return level.getSeaLevel() - 1;
    }

    static boolean isLava(BlockState s) {
        return s.getFluidState().is(FluidTags.LAVA);
    }

    static boolean isCrust(BlockState s) {
        return s.is(ModBlocks.COOLED_CRUST.get()) || s.is(ModBlocks.HEAT_CRYSTAL_CRUST.get());
    }

    static boolean isWater(BlockState s) {
        return s.getFluidState().is(FluidTags.WATER);
    }

    /** テスト用: 冷えた殻か熱の結晶を含む殻か。 */
    public static boolean isCrustForTests(BlockState s) {
        return isCrust(s);
    }

    private static BlockState crust() {
        return ModBlocks.COOLED_CRUST.get().defaultBlockState();
    }

    // ------------------------------------------------------------------------------------------
    // 段階 2: 海の表面の殻
    // ------------------------------------------------------------------------------------------

    /** 溶岩の海の表面の 1 マスか(高さ S の溶岩の源泉で、真上が空いている)。 */
    static boolean isSeaSurfaceLava(ServerLevel level, BlockPos.MutableBlockPos p, int x, int s, int z) {
        BlockState here = level.getBlockState(p.set(x, s, z));
        if (!here.is(Blocks.LAVA) || !here.getFluidState().isSource()) return false;
        BlockState up = level.getBlockState(p.set(x, s + 1, z));
        return up.isAir() || NetherSurface.isOpen(up);
    }

    /**
     * 1 輪ぶん、岸に接した海の表面を殻にする。{@code all} なら接していなくても全部(段階 3 に入っても残っていた分の始末)。
     * 置いた数を返す({@code budget} まで)。残りが無ければ -1。
     */
    static int crustRing(ServerLevel level, int[] cols, boolean all, int budget) {
        int s = surfaceY(level);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        LongArrayList matFirst = new LongArrayList();
        LongArrayList ring = new LongArrayList();
        int remaining = 0;
        for (int c = 0; c + 1 < cols.length; c += 2) {
            int x = cols[c], z = cols[c + 1];
            if (!isSeaSurfaceLava(level, p, x, s, z)) continue;
            remaining++;
            boolean shore = all;
            // 溶岩の浮き草の下は、岸に接していなくても先に固まる(2026-11-12 ユーザー決定: 殻は浮き草のある所から)
            if (level.getBlockState(q.set(x, s + 1, z)).is(ModBlocks.LAVA_FERN_MAT.get())) {
                matFirst.add(BlockPos.asLong(x, s, z));
                continue;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (shore) break;
                q.set(x + d.getStepX(), s, z + d.getStepZ());
                if (!level.isLoaded(q)) continue;
                if (!isLava(level.getBlockState(q))) shore = true;
            }
            if (shore) ring.add(BlockPos.asLong(x, s, z));
        }
        matFirst.addAll(ring);
        ring = matFirst;
        if (remaining == 0) return -1;
        int placed = 0;
        for (int i = 0; i < ring.size() && placed < budget; i++) {
            NetherGround.put(level, p.set(ring.getLong(i)), crust());
            placed++;
        }
        return placed;
    }

    // ------------------------------------------------------------------------------------------
    // 段階 4: 湖
    // ------------------------------------------------------------------------------------------

    /**
     * 列 {@code (x, z)} の湖にできる深さ。0 = 湖にならない。1 = 殻だけ(溶岩 1 段)・2 = 水 1 段 + 殻・3 = 水 2 段 + 殻。
     * 表面(S)が溶岩か殻で真上が空いていて、その下に溶岩(か土手の殻)が続く列だけ。
     */
    static int lakeDepth(ServerLevel level, BlockPos.MutableBlockPos p, int x, int s, int z) {
        BlockState top = level.getBlockState(p.set(x, s, z));
        if (!(isLava(top) || isCrust(top))) return 0;
        BlockState up = level.getBlockState(p.set(x, s + 1, z));
        if (!(up.isAir() || NetherSurface.isOpen(up) || NetherTubeTrees.isStem(up))) return 0; // 洞窟の下・岩の下(管の樹は湖に浸かるので通す。2026-11-12)
        // 表面から下へ、溶岩か殻(段階 2 の殻・土手)が何段続くか(3 段まで)。殻は溶岩が冷えた物なので元の溶岩の深さと同じ
        int depth = 1;
        for (int k = 1; k <= 2; k++) {
            BlockState b = level.getBlockState(p.set(x, s - k, z));
            if (isLava(b) || isCrust(b)) {
                depth++;
            } else {
                break;
            }
        }
        return depth;
    }

    /**
     * 1 輪ぶん、岸か湖に接した列を湖にする。{@code all} なら接していなくても全部(段階 5 に入っても残っていた分)。
     * 置き換えた数を返す({@code budget} まで)。湖にできる列が残っていなければ -1。{@code lakeCells} は増えた水面のマス数を足す口(長さ 1)。
     */
    static int lakeRing(ServerLevel level, int[] cols, NetherSpreadTable t, boolean all, int budget, int[] lakeCells) {
        int s = surfaceY(level);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        LongArrayList ring = new LongArrayList();
        int remaining = 0;
        for (int c = 0; c + 1 < cols.length; c += 2) {
            int x = cols[c], z = cols[c + 1];
            int depth = lakeDepth(level, p, x, s, z);
            if (depth < 2) continue; // 1 段の所は殻のまま(段階 2 の殻が残る)
            remaining++;
            boolean shore = all;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (shore) break;
                q.set(x + d.getStepX(), s, z + d.getStepZ());
                if (!level.isLoaded(q)) continue;
                BlockState n = level.getBlockState(q);
                // 岸 = 水、または湖にならない隣(陸・浅い殻)。溶岩のままの隣と、まだ湖になっていない殻の隣は岸ではない
                if (isWater(n) || (!isLava(n) && (!isCrust(n) || lakeDepth(level, new BlockPos.MutableBlockPos(), q.getX(), s, q.getZ()) < 2))) {
                    shore = true;
                }
            }
            if (shore) ring.add(BlockPos.asLong(x, depth, z)); // y の代わりに深さを入れておく
        }
        if (remaining == 0) return -1;
        long seed = level.getSeed();
        int placed = 0;
        for (int i = 0; i < ring.size() && placed < budget; i++) {
            BlockPos col = BlockPos.of(ring.getLong(i));
            int x = col.getX(), z = col.getZ(), depth = col.getY();
            int waterLevels = depth - 1; // 3 → 水 2 段、2 → 水 1 段
            int bottom = s - waterLevels;
            // 1) 底の殻(水より先に置く)
            BlockState floor = NetherGround.hash01(seed, x, bottom, z, 31) < t.crystalChance
                    ? ModBlocks.HEAT_CRYSTAL_CRUST.get().defaultBlockState() : crust();
            NetherGround.put(level, p.set(x, bottom, z), floor);
            placed++;
            // 2) 土手: 水を置く高さで横が溶岩なら殻に
            for (int y = s; y > bottom; y--) {
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    q.set(x + d.getStepX(), y, z + d.getStepZ());
                    if (isLava(level.getBlockState(q))) {
                        NetherGround.put(level, q, crust());
                        placed++;
                    }
                }
            }
            // 3) 水(下から)
            // 管の樹が立っていれば、水の代わりに管を湖の底まで伸ばして浸ける(2026-11-12)
            int sunk = NetherTubeTrees.sinkInto(level, x, z, s, bottom);
            if (sunk > 0) {
                placed += sunk;
            } else {
                for (int y = bottom + 1; y <= s; y++) {
                    NetherGround.put(level, p.set(x, y, z), Fluids.WATER.defaultFluidState().createLegacyBlock());
                    placed++;
                }
            }
            lakeCells[0]++;
        }
        return placed;
    }

    // ------------------------------------------------------------------------------------------
    // 段階 2: 海の外の溶岩とマグマ
    // ------------------------------------------------------------------------------------------

    /**
     * そのセクションの、海面より上の空気に触れた溶岩(源泉は殻・流れは上流の源泉を殻)と、空気に触れたマグマブロック(殻)。
     * 置き換えた数を返す。
     */
    static int outsideSection(ServerLevel level, LevelChunk chunk, int sectionIndex) {
        LevelChunkSection section = chunk.getSection(sectionIndex);
        if (section.hasOnlyAir()) return 0;
        if (!section.maybeHas(st -> st.is(Blocks.LAVA) || st.is(Blocks.MAGMA_BLOCK))) return 0;
        int baseY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        int s = surfaceY(level);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        int changed = 0;
        for (int i = 0; i < 4096; i++) {
            int lx = i & 15, lz = (i >> 4) & 15, ly = i >> 8;
            BlockState st = section.getBlockState(lx, ly, lz);
            boolean lava = st.is(Blocks.LAVA);
            if (!lava && !st.is(Blocks.MAGMA_BLOCK)) continue;
            int x = x0 + lx, y = baseY + ly, z = z0 + lz;
            if (lava && y <= s) continue; // 海(表面は crustRing、地中は触らない)
            boolean exposed = false;
            for (Direction d : Direction.values()) {
                BlockState o = level.getBlockState(n.setWithOffset(at.set(x, y, z), d));
                if (o.isAir() || NetherSurface.isOpen(o)) {
                    exposed = true;
                    break;
                }
            }
            if (!exposed) continue;
            if (!lava) {
                NetherGround.put(level, at.set(x, y, z), crust());
                changed++;
            } else if (st.getFluidState().isSource()) {
                NetherGround.put(level, at.set(x, y, z), crust());
                changed++;
            } else {
                changed += crustSourcesUpstream(level, at.set(x, y, z).immutable(), s);
            }
        }
        return changed;
    }

    /** 流れている溶岩から上流へ辿り、見つけた源泉(海面より上)を殻にする({@link LavaSolidifier} と同じ辿り方)。 */
    static int crustSourcesUpstream(ServerLevel level, BlockPos flowing, int seaSurface) {
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        LongOpenHashSet seen = new LongOpenHashSet();
        queue.enqueue(flowing.asLong());
        seen.add(flowing.asLong());
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        int done = 0;
        while (!queue.isEmpty() && seen.size() <= 256 && done < 16) {
            cur.set(queue.dequeueLong());
            FluidState f = level.getFluidState(cur);
            if (!f.is(FluidTags.LAVA)) continue;
            if (f.isSource()) {
                if (cur.getY() > seaSurface) {
                    NetherGround.put(level, cur, crust());
                    done++;
                }
                continue;
            }
            int amount = f.getAmount();
            for (Direction d : Direction.values()) {
                if (d == Direction.DOWN) continue;
                next.setWithOffset(cur, d);
                long k = next.asLong();
                if (seen.contains(k) || !level.isLoaded(next)) continue;
                FluidState o = level.getFluidState(next);
                if (!o.is(FluidTags.LAVA)) continue;
                if (d == Direction.UP || o.isSource() || o.getAmount() > amount) {
                    seen.add(k);
                    queue.enqueue(k);
                }
            }
        }
        return done;
    }
}
