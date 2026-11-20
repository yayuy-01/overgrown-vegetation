package com.yayuy.overgrownvegetation.drift;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.pushback.RingWards;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.organ.OrganBlocks;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 腫れた島(2026-11-20。Fable の案「島2 腫れた島」 = 模型 {@code sea-look-art/Sea.java} の {@code island(2)} を本物の島へ移した。芯なし)。
 *
 * <p>漂流する擬態 Mob が小さな島に根付くと、その島を {@link #start} で帳面に載せる。初めて島のまわりが読み込まれた時に、
 * 模型の形を島の大きさの倍率で伸び縮みさせた「置くマスの順の列」(計画)を作って保存し、{@link DriftTable.Swell#growDays} 日かけて
 * 先頭から少しずつ置く(20 tick に {@link DriftTable.Swell#blocksPerStep} マスまで・TickBudget の残りで止まる)。
 * 置く順: 島の肌(上の土を肉・菌糸の床へ)→ 水ぶくれの山(下から)→ 枯れ木と根の簾。
 *
 * <ul>
 *   <li>置くのは空気・置き換えてよい植物・葉・自分の肉と水ぶくれの所だけ(水には置かない)。肌だけは上の土・砂・草を置き換える。</li>
 *   <li>押し返しの効く所({@link RingWards#guarded})には置かない。</li>
 *   <li>形の更新を出さない置き方({@code UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE})。根の簾が置く途中で落ちないように。</li>
 *   <li>模型のうち筏の部分(島を囲む筏の裾・岸から届いた筏の橋・裾の浮き嚢)は、筏が取りやめになったので作らない。</li>
 *   <li>枯れ木は汚染された丸太をそのまま使う(模型は暗く塗った丸太。枯れた丸太のブロックは無い)【仮】。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SwollenIslands extends SavedData {

    public static final String ID = "overgrown_vegetation_swollen_islands";

    static final byte SKIN_FLESH = 0, SKIN_HYPHA = 1, FLESH = 2, BLISTER = 3, LOG_Y = 4, LOG_X = 5, LOG_Z = 6, DANG = 7, DANG_TIP = 8;

    /** 模型の水ぶくれの山(中心 x, y, z と半径 x, y, z。y は水面の 1 つ上が 0)。 */
    static final double[][] BLOBS = {{-1, 5, 0, 5.0, 4.6, 4.6}, {4, 7.5, -2, 3.4, 3.4, 3.2}, {-5, 6.5, 3, 3.2, 3.0, 3.2}, {1, 10.5, 1, 3.0, 2.8, 3.0},
            {6, 3, 4, 2.6, 2.4, 2.6}, {-7, 3, -4, 2.8, 2.4, 2.6}, {2, 3, -7, 2.4, 2.2, 2.4}, {-2, 13, 0, 1.9, 1.8, 1.9}};
    /** 模型の枯れ木(x, z, 高さ)。 */
    static final int[][] DEAD = {{-8, 5, 8}, {8, -1, 9}, {3, 8, 7}, {-3, -8, 8}};

    public static final class Island {
        public int cx, cz, wy;
        public double radius;
        public long seed, start;
        /** 計画(まだ作っていなければ null)。 */
        long[] pos;
        byte[] kind;
        public int done;
        /** 確かめ用: 高さの上限を差し替える(負なら設計値)。 */
        public int maxHeight = -1;

        public int planned() {
            return pos == null ? -1 : pos.length;
        }
    }

    final List<Island> islands = new ArrayList<>();

    public static final SavedData.Factory<SwollenIslands> FACTORY = new SavedData.Factory<>(SwollenIslands::new, SwollenIslands::load, null);

    public static SwollenIslands get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** 島を帳面に載せる(同じ島が載っていれば何もしない)。載せた島か、既にあった島を返す。上限を超えれば null。 */
    public static Island start(ServerLevel level, DriftShore.Island island, int wy) {
        SwollenIslands d = get(level);
        for (Island i : d.islands) {
            if (Mth.square(i.cx - island.cx()) + Mth.square(i.cz - island.cz()) <= Mth.square(Math.max(4.0D, i.radius + island.radius()))) return i;
        }
        if (d.islands.size() >= DesignData.drift().swell.maxIslands) return null;
        Island i = new Island();
        i.cx = island.cx();
        i.cz = island.cz();
        i.wy = wy;
        i.radius = island.radius();
        i.seed = level.getRandom().nextLong();
        i.start = level.getGameTime();
        d.islands.add(i);
        d.setDirty();
        return i;
    }

    public static List<Island> islands(ServerLevel level) {
        return get(level).islands;
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % 20 != 7) return;
        if (!DesignData.drift().enabled) return;
        SwollenIslands d = get(level);
        if (d.islands.isEmpty()) return;
        if (!com.yayuy.overgrownvegetation.perf.TickBudget.mayStart(com.yayuy.overgrownvegetation.perf.PerfJob.DRIFT_ISLANDS)) return;
        long t0 = com.yayuy.overgrownvegetation.perf.TickBudget.begin();
        int placed = grow(level, d, false);
        com.yayuy.overgrownvegetation.perf.TickBudget.end(com.yayuy.overgrownvegetation.perf.PerfJob.DRIFT_ISLANDS, t0, placed);
        DriftStats.swell(System.nanoTime() - t0, placed);
    }

    /** 育てる。{@code all} なら時間と 1 回の上限を無視して計画を全部置く(コマンド・確かめ用)。置いたマスの数を返す。 */
    static int grow(ServerLevel level, SwollenIslands d, boolean all) {
        DriftTable.Swell t = DesignData.drift().swell;
        int budget = all ? Integer.MAX_VALUE : Math.max(1, t.blocksPerStep);
        int placed = 0;
        long growTicks = Math.max(1L, (long) (t.growDays * 24000.0D));
        for (Iterator<Island> it = d.islands.iterator(); it.hasNext() && budget > 0; ) {
            Island i = it.next();
            if (i.pos == null) {
                if (!plan(level, i)) continue; // まわりが読み込まれていない
                d.setDirty();
            }
            int want = all ? i.pos.length : (int) Math.min(i.pos.length, (double) i.pos.length * (level.getGameTime() - i.start) / growTicks);
            while (i.done < want && budget > 0) {
                if (!all && placed > 0 && !com.yayuy.overgrownvegetation.perf.TickBudget.timeLeft()) {
                    budget = 0;
                    break;
                }
                BlockPos p = BlockPos.of(i.pos[i.done]);
                if (level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null) break; // 読み込まれたら続き
                if (place(level, p, i.kind[i.done])) {
                    placed++;
                    budget--;
                }
                i.done++;
                d.setDirty();
            }
            if (i.done >= i.pos.length) it.remove(); // 育ち終えた
        }
        return placed;
    }

    /** 確かめ用・コマンド用: 帳面の島を全部、今すぐ育ち切らせる。置いたマスの数を返す。 */
    public static int growAllNow(ServerLevel level) {
        return grow(level, get(level), true);
    }

    // ------------------------------------------------------------ 計画

    /** 確かめ用: 1 つの島だけを今すぐ育ち切らせる(計画を作れなければ -1)。置いたマスの数を返す。 */
    public static int growNow(ServerLevel level, Island i) {
        if (i.pos == null && !plan(level, i)) return -1;
        int placed = 0;
        for (; i.done < i.pos.length; i.done++) {
            if (place(level, BlockPos.of(i.pos[i.done]), i.kind[i.done])) placed++;
        }
        get(level).islands.remove(i);
        get(level).setDirty();
        return placed;
    }

    static boolean plan(ServerLevel level, Island i) {
        DriftTable.Swell t = DesignData.drift().swell;
        double s = Mth.clamp(i.radius / Math.max(0.1D, t.modelRadius), t.minScale, t.maxScale);
        int reach = (int) Math.ceil(16 * s) + 2;
        for (int cx = (i.cx - reach) >> 4; cx <= (i.cx + reach) >> 4; cx++)
            for (int cz = (i.cz - reach) >> 4; cz <= (i.cz + reach) >> 4; cz++)
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
        int maxH = i.maxHeight >= 0 ? i.maxHeight : t.maxHeight;
        int base = i.wy + 1, top = base + maxH;
        Long2ByteLinkedOpenHashMap out = new Long2ByteLinkedOpenHashMap();
        Long2IntOpenHashMap colTop = new Long2IntOpenHashMap(); // 計画の中の列のいちばん上(枯れ木の根元に使う)
        colTop.defaultReturnValue(Integer.MIN_VALUE);
        int sd = (int) (i.seed ^ (i.seed >>> 32));
        // 1. 島の肌: 上の土・砂・草を肉か菌糸の床に(模型: ノイズ 0.38 より上の所だけ)
        int skin = (int) Math.ceil(14 * s);
        for (int dx = -skin; dx <= skin; dx++)
            for (int dz = -skin; dz <= skin; dz++) {
                int x = i.cx + dx, z = i.cz + dz;
                if (DriftShore.column(level, x, z, i.wy) != DriftShore.Col.LAND) continue;
                BlockPos stand = DriftShore.standOn(level, x, z, i.wy, maxH);
                if (stand == null) continue;
                BlockPos g = stand.below();
                if (!skinnable(level.getBlockState(g))) continue;
                if (noise(dx / s, dz / s, 5, sd + 41) <= 0.38D) continue;
                out.put(g.asLong(), noise(dx / s, dz / s, 3, sd + 42) > 0.6D ? SKIN_HYPHA : SKIN_FLESH);
            }
        // 2. 水ぶくれの山: 肉の殻(半径 + 0.6)の中に水ぶくれ(0.6 上へずらす)。下から順に置く
        Map<Long, Byte> blob = new java.util.LinkedHashMap<>();
        for (double[] b : BLOBS) {
            double bx = i.cx + 0.5D + b[0] * s, bz = i.cz + 0.5D + b[2] * s, by = base + b[1] * s;
            double rx = Math.max(1.0D, b[3] * s), ry = Math.max(1.0D, b[4] * s), rz = Math.max(1.0D, b[5] * s);
            ball(blob, bx, by, bz, rx + 0.6D, ry + 0.2D, rz + 0.6D, FLESH, base, top);
            ball(blob, bx, by + 0.6D, bz, rx, ry, rz, BLISTER, base, top);
        }
        List<Map.Entry<Long, Byte>> cells = new ArrayList<>(blob.entrySet());
        cells.sort((a, c) -> Integer.compare(BlockPos.getY(a.getKey()), BlockPos.getY(c.getKey())));
        for (Map.Entry<Long, Byte> e : cells) {
            out.put((long) e.getKey(), (byte) e.getValue());
            long col = BlockPos.asLong(BlockPos.getX(e.getKey()), 0, BlockPos.getZ(e.getKey()));
            colTop.put(col, Math.max(colTop.get(col), BlockPos.getY(e.getKey())));
        }
        // 3. 枯れ木と根の簾
        for (int[] tr : DEAD) {
            // 模型の枯れ木は島の縁に立つ。本物の島では縁の外(水)に出ることがあるので、陸に乗るまで中心へ寄せる
            int x = 0, z = 0;
            boolean onLand = false;
            for (double f = 1.0D; f >= 0.3D && !onLand; f -= 0.1D) {
                x = i.cx + (int) Math.round(tr[0] * s * f);
                z = i.cz + (int) Math.round(tr[1] * s * f);
                onLand = DriftShore.column(level, x, z, i.wy) == DriftShore.Col.LAND;
            }
            if (!onLand) continue;
            BlockPos stand = DriftShore.standOn(level, x, z, i.wy, maxH);
            if (stand == null) continue;
            int y0 = Math.max(stand.getY(), colTop.get(BlockPos.asLong(x, 0, z)) + 1);
            int h = Math.max(4, (int) Math.round(tr[2] * Math.max(0.6D, s)));
            if (y0 + h > top) h = top - y0;
            if (h < 3) continue;
            for (int k = 0; k < h; k++) out.put(BlockPos.asLong(x, y0 + k, z), LOG_Y);
            int ty = y0 + h - 1;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (hs(x + d.getStepX(), z + d.getStepZ(), sd + 50) % 4 == 0) continue;
                int ay = ty - hs(x + d.getStepX(), z + d.getStepZ(), sd + 52) % 3;
                for (int q = 1; q <= 2; q++) {
                    int ax = x + d.getStepX() * q, az = z + d.getStepZ() * q;
                    long ap = BlockPos.asLong(ax, ay, az);
                    if (!out.containsKey(ap)) out.put(ap, d.getAxis() == Direction.Axis.X ? LOG_X : LOG_Z);
                    int n = 2 + hs(ax, az, sd + 51) % 3;
                    for (int k = 1; k <= n; k++) {
                        long dp = BlockPos.asLong(ax, ay - k, az);
                        if (ay - k < base || out.containsKey(dp)) break;
                        out.put(dp, k == n ? DANG_TIP : DANG);
                    }
                }
            }
        }
        i.pos = out.keySet().toLongArray();
        i.kind = new byte[i.pos.length];
        for (int k = 0; k < i.pos.length; k++) i.kind[k] = out.get(i.pos[k]);
        i.done = 0;
        return true;
    }

    private static void ball(Map<Long, Byte> out, double cx, double cy, double cz, double rx, double ry, double rz, byte kind, int minY, int maxY) {
        for (int x = Mth.floor(cx - rx); x <= Mth.floor(cx + rx); x++)
            for (int y = Math.max(minY, Mth.floor(cy - ry)); y <= Math.min(maxY - 1, Mth.floor(cy + ry)); y++)
                for (int z = Mth.floor(cz - rz); z <= Mth.floor(cz + rz); z++) {
                    double d = Mth.square((x + 0.5D - cx) / rx) + Mth.square((y + 0.5D - cy) / ry) + Mth.square((z + 0.5D - cz) / rz);
                    if (d <= 1.0D) out.put(BlockPos.asLong(x, y, z), kind);
                }
    }

    private static boolean skinnable(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(net.minecraft.world.level.block.Blocks.GRAVEL);
    }

    // ------------------------------------------------------------ 置く

    static boolean place(ServerLevel level, BlockPos p, byte kind) {
        if (RingWards.guarded(level, p)) return false;
        BlockState cur = level.getBlockState(p);
        BlockState want = state(kind);
        if (cur.is(want.getBlock())) return false;
        if (kind == SKIN_FLESH || kind == SKIN_HYPHA) {
            if (!skinnable(cur)) return false;
        } else if (!cur.getFluidState().isEmpty() || !(cur.isAir() || cur.canBeReplaced() || cur.is(BlockTags.LEAVES) || ours(cur))) {
            return false;
        }
        level.setBlock(p, want, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        return true;
    }

    private static boolean ours(BlockState s) {
        return s.is(OrganBlocks.ORGAN_FLESH.get()) || s.is(OrganBlocks.CAVE_BLISTER.get()) || s.is(OrganBlocks.ORGAN_HYPHA_FLOOR.get());
    }

    static BlockState state(byte kind) {
        return switch (kind) {
            case SKIN_HYPHA -> OrganBlocks.ORGAN_HYPHA_FLOOR.get().defaultBlockState();
            case SKIN_FLESH, FLESH -> OrganBlocks.ORGAN_FLESH.get().defaultBlockState();
            case BLISTER -> OrganBlocks.CAVE_BLISTER.get().defaultBlockState();
            case LOG_X -> log(Direction.Axis.X);
            case LOG_Z -> log(Direction.Axis.Z);
            case DANG -> ModBlocks.DANGLING_ROOTS_PLANT.get().defaultBlockState();
            case DANG_TIP -> ModBlocks.DANGLING_ROOTS.get().defaultBlockState();
            default -> log(Direction.Axis.Y);
        };
    }

    private static BlockState log(Direction.Axis axis) {
        BlockState s = ModBlocks.OVERGROWN_LOG.get().defaultBlockState();
        return s.hasProperty(RotatedPillarBlock.AXIS) ? s.setValue(RotatedPillarBlock.AXIS, axis) : s;
    }

    // ------------------------------------------------------------ ノイズ(模型 Ob.vn と同じ考え: 格子の値ノイズ)

    static int hs(int x, int z, int seed) {
        int h = x * 374761393 + z * 668265263 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) & 0x7fffffff;
    }

    static double noise(double x, double z, int period, int seed) {
        double fx = x / period, fz = z / period;
        int x0 = Mth.floor(fx), z0 = Mth.floor(fz);
        double tx = fx - x0, tz = fz - z0;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);
        double a = hs(x0, z0, seed) / (double) 0x7fffffff, b = hs(x0 + 1, z0, seed) / (double) 0x7fffffff;
        double c = hs(x0, z0 + 1, seed) / (double) 0x7fffffff, e = hs(x0 + 1, z0 + 1, seed) / (double) 0x7fffffff;
        return (a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + e * tx) * tz;
    }

    // ------------------------------------------------------------ 保存

    static SwollenIslands load(CompoundTag tag, HolderLookup.Provider lookup) {
        SwollenIslands d = new SwollenIslands();
        ListTag list = tag.getList("islands", Tag.TAG_COMPOUND);
        for (int k = 0; k < list.size(); k++) {
            CompoundTag c = list.getCompound(k);
            Island i = new Island();
            i.cx = c.getInt("cx");
            i.cz = c.getInt("cz");
            i.wy = c.getInt("wy");
            i.radius = c.getDouble("r");
            i.seed = c.getLong("seed");
            i.start = c.getLong("start");
            i.done = c.getInt("done");
            if (c.contains("pos")) {
                i.pos = c.getLongArray("pos");
                i.kind = c.getByteArray("kind");
                if (i.kind.length != i.pos.length) {
                    i.pos = null;
                    i.kind = null;
                    i.done = 0;
                }
            }
            d.islands.add(i);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        ListTag list = new ListTag();
        for (Island i : islands) {
            CompoundTag c = new CompoundTag();
            c.putInt("cx", i.cx);
            c.putInt("cz", i.cz);
            c.putInt("wy", i.wy);
            c.putDouble("r", i.radius);
            c.putLong("seed", i.seed);
            c.putLong("start", i.start);
            c.putInt("done", i.done);
            if (i.pos != null) {
                c.putLongArray("pos", i.pos);
                c.putByteArray("kind", i.kind);
            }
            list.add(c);
        }
        tag.put("islands", list);
        return tag;
    }

    /** 確かめ用: 帳面から外す。 */
    public static void forgetForTests(ServerLevel level, Island i) {
        get(level).islands.remove(i);
    }

    static boolean overworld(Level level) {
        return level.dimension() == Level.OVERWORLD;
    }
}
