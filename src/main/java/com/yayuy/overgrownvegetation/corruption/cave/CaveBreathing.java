package com.yayuy.overgrownvegetation.corruption.cave;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.fissure.FissureData;
import com.yayuy.overgrownvegetation.corruption.pushback.RingWards;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.spore.field.CaveBreathField;
import com.yayuy.overgrownvegetation.spore.field.CaveMouths;
import com.yayuy.overgrownvegetation.spore.field.MyceliumSeep;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;

/**
 * 洞窟が胞子を吸い込む・吐き出す(2026-11-20 ユーザー「胞子が吸い込むとか吐き出す仕組みを今作っちゃおう」)。設定: 洞窟は卓越風に合わせて息をする
 * (11-07 採用の「洞窟の呼吸」)。風が吹き込む口は地上の胞子を吸い込み、吹き出す口は中の空気を吐く。地下は日の光でなく地脈で育つ。
 *
 * <ul>
 *     <li><b>口</b>: 読み込まれたチャンクを 1 回ずつ測り、洞窟の口(屋根の下の空気から横へたどって空の下へ出る所 = 横穴、無ければ地面より 4 段以上深い縦穴)と
 *     外向きを覚える(保存)。亀裂の前線があふれた口(入口 B)も口に数える。</li>
 *     <li><b>吸う・吐く</b>: 見た目の CaveBreath と同じ決まり。卓越風と口の外向きの内積が −{@code flowThreshold} より小さい(吹き込む)なら吸う、
 *     +{@code flowThreshold} より大きい(吹き出す)なら吐く、間は凪。縦穴は夜に吸い、昼に吐く。</li>
 *     <li><b>吸う</b>: 胞子の場の、地上から地下の層への染み込みを口のチャンクで {@code inhaleGain} × 吹き込みの度合い × 風の強さ 倍にする
 *     (吐く口・凪の口は 0 = 吸わない)。吸った地下の浮遊は、隣の洞窟のチャンク(亀裂の見立てで洞窟・口と分かった所)へ {@code inwardShare} ずつ渡す。
 *     地下の降り積もりがしきい値を越えると洞窟の床から芽吹く(今の胞子の場の仕組み)。地脈の線に近いほどしきい値が低い。</li>
 *     <li><b>吐く</b>: 中の汚染の度合い(亀裂の台帳の追いつき・菌糸の染み下り・地下の浮遊)に応じて、口のチャンクを胞子の場の発生源にする。
 *     入口 B の口は {@code outletBoost} 倍。風に乗って風下へ広がる。</li>
 *     <li>押し返す道具の効く口は吸わない・吐かない。読み込まれていない口も時刻どおりに働く(口の記録と胞子の場は読み込みに関係しない)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class CaveBreathing {
    private CaveBreathing() {
    }

    /** 測る列(保存しない)。 */
    private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();
    /** 直近の計算(調べ用)。 */
    static volatile String last = "";

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        if (!DesignData.cave().breath.enabled) return;
        long k = chunk.getPos().toLong();
        if (!Data.get(level).measured.contains(k)) QUEUE.add(k);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        CaveTable.Breath t = DesignData.cave().breath;
        if (!t.enabled) return;
        if (!TickBudget.mayStart(PerfJob.CAVE_DECOR)) return;
        long start = TickBudget.begin();
        int done = 0;
        try {
            Data data = Data.get(level);
            for (int i = 0; i < Math.max(1, t.measuresPerTick) && !QUEUE.isEmpty(); i++) {
                long k = QUEUE.removeFirstLong();
                if (data.measured.contains(k)) continue;
                LevelChunk c = level.getChunkSource().getChunkNow(ChunkPos.getX(k), ChunkPos.getZ(k));
                if (c == null || !CaveDecor.ready(level, ChunkPos.getX(k), ChunkPos.getZ(k))) continue;
                measure(level, data, c);
                done++;
            }
            if (level.getGameTime() % Math.max(1, t.intervalTicks) == 0) done += breathe(level, data, t);
        } finally {
            TickBudget.end(PerfJob.CAVE_DECOR, start, done);
        }
    }

    // ---------------------------------------------------------------- 決まり(見た目の CaveBreath と同じ)

    /**
     * 口の出入り: 吹き込む(吸う)なら正、吹き出す(吐く)なら負、凪は 0。度合いは 0〜1。{@code yaw} は口の外向き(ラジアン。NaN = 縦穴)。
     * 縦穴は昼に吐き(−0.5)、夜に吸う(+0.5)(見た目: 昼に霞が昇り、夜に粒が下りる)。
     */
    public static double flow(double yaw, double windX, double windZ, double threshold, boolean day) {
        if (Double.isNaN(yaw)) return day ? -0.5D : 0.5D;
        double dot = windX * Math.cos(yaw) + windZ * Math.sin(yaw); // 正 = 風が口から外へ(吹き出す)
        double a = Math.abs(dot);
        if (a < threshold) return 0.0D;
        double k = (a - threshold) / Math.max(0.05D, 1.0D - threshold);
        return dot > 0 ? -k : k;
    }

    /** 染み込みの倍率(吸う口は 1 より大きく、吐く・凪の口は 0)。 */
    public static float soakFactor(double flow, boolean shaft, double windStrength, CaveTable.Breath t) {
        if (flow <= 0.0D) return 0.0f;
        double gain = shaft ? t.shaftInhaleGain : t.inhaleGain * Math.min(1.5D, Math.max(0.0D, windStrength));
        return (float) Math.max(0.0D, gain * flow * (shaft ? 2.0D : 1.0D));
    }

    /** 吐く量(1 回の計算ぶん)。中の汚染の度合い {@code dirty}(0〜1。入口 B は outletBoost 倍)。 */
    public static double exhaleAmount(double flow, double dirty, boolean outlet, double windStrength, CaveTable.Breath t, double fullAt) {
        if (flow >= 0.0D || dirty <= 0.0D) return 0.0D;
        double k = -flow * dirty * (outlet ? t.outletBoost : 1.0D) * Math.max(0.2D, windStrength);
        return fullAt * t.exhalePerDay * k * (Math.max(1, t.intervalTicks) / (double) PrevailingWind.DAY);
    }

    // ---------------------------------------------------------------- 1 回の計算

    static int breathe(ServerLevel level, Data data, CaveTable.Breath t) {
        PrevailingWind.Sample wind = SporeFieldServer.windNow(level);
        double wx = wind == null ? 0.0D : wind.dirX, wz = wind == null ? 0.0D : wind.dirZ, ws = wind == null ? 0.0D : wind.ground();
        boolean day = level.isDay();
        FissureData fd = FissureData.get(level);
        // 入口 B の口(亀裂があふれた口)
        Long2LongOpenHashMap outlets = new Long2LongOpenHashMap();
        Long2FloatOpenHashMap outletYaw = new Long2FloatOpenHashMap();
        for (FissureData.Fissure f : fd.fissures()) {
            if (f.state == FissureData.State.OPEN && f.outletMouth != null && f.reachedMouth()) {
                long k = ChunkPos.asLong(f.outletMouth.getX() >> 4, f.outletMouth.getZ() >> 4);
                outlets.put(k, f.outletMouth.asLong());
                outletYaw.put(k, (float) f.outletYaw);
            }
        }
        LongOpenHashSet keys = new LongOpenHashSet(data.mouths.keySet());
        keys.addAll(outlets.keySet());
        Long2FloatOpenHashMap soak = new Long2FloatOpenHashMap();
        double fullAt = CaveBreathField.fullAt();
        int inhale = 0, exhale = 0, n = 0;
        for (long k : keys) {
            boolean outlet = outlets.containsKey(k);
            BlockPos mouth = BlockPos.of(outlet ? outlets.get(k) : data.mouths.get(k));
            double yaw = outlet ? outletYaw.get(k) : data.yaw.get(k);
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            if (level.getChunkSource().getChunkNow(cx, cz) != null && RingWards.guarded(level, mouth)) { // 押し返す道具の効く口(読み込まれていれば見る)
                soak.put(k, 0.0f);
                continue;
            }
            double fl = flow(yaw, wx, wz, t.flowThreshold, day);
            boolean shaft = Double.isNaN(yaw);
            float s = soakFactor(fl, shaft, ws, t);
            soak.put(k, s);
            n++;
            if (s > 0.0f) {
                inhale++;
                // 吸った分を奥の洞窟へ(隣の、洞窟・口と分かったチャンク)
                int count = 0;
                long[] nb = {ChunkPos.asLong(cx + 1, cz), ChunkPos.asLong(cx - 1, cz), ChunkPos.asLong(cx, cz + 1), ChunkPos.asLong(cx, cz - 1)};
                for (long q : nb) if (cave(fd, data, q)) count++;
                if (count > 0) for (long q : nb) if (cave(fd, data, q)) CaveBreathField.spreadUnder(cx, cz, ChunkPos.getX(q), ChunkPos.getZ(q), t.inwardShare / count);
            } else if (fl < 0.0D) {
                double amount = exhaleAmount(fl, dirtiness(level, fd, k, fullAt), outlet, ws, t, fullAt);
                if (amount > 0.0D) {
                    CaveBreathField.exhale(cx, cz, amount);
                    exhale++;
                }
            }
        }
        CaveBreathField.publish(soak, level.getSeed(), t.leyNearBlocks, t.leyGermMax);
        last = String.format(java.util.Locale.ROOT, "口 %d(入口 B %d)・吸う %d・吐く %d・風 (%.2f, %.2f) 強さ %.2f・%s", n, outlets.size(), inhale, exhale, wx, wz, ws,
                day ? "昼" : "夜");
        return Math.max(1, n);
    }

    /** 洞窟か口と分かったチャンク。 */
    static boolean cave(FissureData fd, Data data, long k) {
        byte kind = fd.known(k);
        return kind == FissureData.K_CAVE || kind == FissureData.K_MOUTH || data.mouths.containsKey(k);
    }

    /** 中の汚染の度合い 0〜1(亀裂の台帳の追いつき・菌糸の染み下り・地下の浮遊の濃さの大きい方)。 */
    static double dirtiness(ServerLevel level, FissureData fd, long k, double fullAt) {
        double d = 0.0D;
        FissureData.Cell c = fd.ledger().get(k);
        if (c != null) d = Math.max(d, c.applied / 256.0D);
        LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(k), ChunkPos.getZ(k));
        if (chunk != null) d = Math.max(d, MyceliumSeep.progressPpm(chunk) / (double) MyceliumSeep.SCALE);
        SporeFieldServer.Cell sc = SporeFieldServer.cell(ChunkPos.getX(k), ChunkPos.getZ(k));
        if (sc != null && fullAt > 0) d = Math.max(d, Math.min(1.0D, sc.under() / fullAt));
        return Math.min(1.0D, d);
    }

    // ---------------------------------------------------------------- 口を測る

    /** チャンクの洞窟の口と外向きを測って覚える(無ければ「測った」だけ)。 */
    static void measure(ServerLevel level, Data data, LevelChunk chunk) {
        long k = chunk.getPos().toLong();
        data.measured.add(k);
        data.setDirty();
        double[] found = findMouth(level, chunk);
        if (found == null) return;
        data.mouths.put(k, BlockPos.asLong((int) found[0], (int) found[1], (int) found[2]));
        data.yaw.put(k, (float) found[3]);
    }

    private static final int[][] DIRS8 = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    /**
     * 口を探す: 屋根の下の空気(地面から 0〜10 段下)から 8 方向へ 7 マスまで空気をたどり、空の下(高さ地図より上)に出たら横穴(外向き = その向き)。
     * 内側へ 3 マス以上空気が続く所だけ(口の脇の壁ぎわを除く。見た目の CaveBreath と同じ考え)。無ければ、まわりの地面より 4 段以上深い縦穴(外向き NaN)。
     * 見つからなければ null。返す値 = {x, y, z, 外向き}。
     */
    @Nullable
    static double[] findMouth(ServerLevel level, LevelChunk chunk) {
        int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        double[] shaft = null;
        for (int lx = 1; lx < 16; lx += 2) {
            for (int lz = 1; lz < 16; lz += 2) {
                int x = bx + lx, z = bz + lz;
                int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz);
                int ground = CaveMouths.groundTop(chunk, x, top, z, level.getMinBuildHeight());
                for (int y = ground; y >= ground - 10; y--) {
                    if (!air(level, m.set(x, y, z)) || open(level, x, y, z)) continue; // 屋根の下の空気
                    for (int d = 0; d < 8; d++) {
                        int[] dv = DIRS8[d];
                        boolean out = false;
                        for (int s = 1; s <= 7; s++) {
                            int px = x + dv[0] * s, pz = z + dv[1] * s;
                            if (!air(level, m.set(px, y, pz))) break;
                            if (open(level, px, y, pz)) {
                                out = true;
                                break;
                            }
                        }
                        if (!out) continue;
                        int depth = 0;
                        for (int s = 1; s <= 3; s++) if (air(level, m.set(x - dv[0] * s, y, z - dv[1] * s))) depth++;
                        if (depth < 3) continue;
                        return new double[]{x, y, z, Math.atan2(dv[1], dv[0])};
                    }
                }
                if (shaft == null && top < ground - 4 && air(level, m.set(x, top + 1, z))) shaft = new double[]{x, ground, z, Double.NaN};
            }
        }
        return shaft;
    }

    private static boolean air(ServerLevel level, BlockPos p) {
        LevelChunk c = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
        if (c == null) return false;
        BlockState s = c.getBlockState(p);
        return s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty();
    }

    /** 空の下か(高さ地図より上)。 */
    private static boolean open(ServerLevel level, int x, int y, int z) {
        LevelChunk c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        return c != null && y > c.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
    }

    // ---------------------------------------------------------------- 記録

    public static final class Data extends SavedData {
        static final String ID = "overgrown_vegetation_cave_breath";
        static final SavedData.Factory<Data> FACTORY = new SavedData.Factory<>(Data::new, (tag, reg) -> load(tag), null);
        /** 測ったチャンク。 */
        final LongOpenHashSet measured = new LongOpenHashSet();
        /** 口のあるチャンク → 口のマス・外向き(NaN = 縦穴)。 */
        final Long2LongOpenHashMap mouths = new Long2LongOpenHashMap();
        final Long2FloatOpenHashMap yaw = new Long2FloatOpenHashMap();

        public static Data get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(FACTORY, ID);
        }

        public int mouthCount() {
            return mouths.size();
        }

        /** テスト・コマンド用: 口を直に足す・消す。 */
        public void putMouth(long chunk, BlockPos mouth, double yawRad) {
            measured.add(chunk);
            mouths.put(chunk, mouth.asLong());
            yaw.put(chunk, (float) yawRad);
            setDirty();
        }

        public void removeMouth(long chunk) {
            mouths.remove(chunk);
            yaw.remove(chunk);
            setDirty();
        }

        static Data load(CompoundTag tag) {
            Data d = new Data();
            for (long k : tag.getLongArray("Measured")) d.measured.add(k);
            long[] mk = tag.getLongArray("MouthKeys"), mp = tag.getLongArray("MouthPos");
            int[] yb = tag.getIntArray("MouthYaw");
            for (int i = 0; i < Math.min(mk.length, Math.min(mp.length, yb.length)); i++) {
                d.mouths.put(mk[i], mp[i]);
                d.yaw.put(mk[i], Float.intBitsToFloat(yb[i]));
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putLongArray("Measured", measured.toLongArray());
            long[] mk = mouths.keySet().toLongArray();
            long[] mp = new long[mk.length];
            int[] yb = new int[mk.length];
            for (int i = 0; i < mk.length; i++) {
                mp[i] = mouths.get(mk[i]);
                yb[i] = Float.floatToIntBits(yaw.get(mk[i]));
            }
            tag.putLongArray("MouthKeys", mk);
            tag.putLongArray("MouthPos", mp);
            tag.putIntArray("MouthYaw", yb);
            return tag;
        }
    }
}
