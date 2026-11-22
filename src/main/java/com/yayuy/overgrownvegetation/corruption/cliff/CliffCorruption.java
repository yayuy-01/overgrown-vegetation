package com.yayuy.overgrownvegetation.corruption.cliff;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.util.LoadedChunks;
import com.yayuy.overgrownvegetation.util.TickPhases;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.event.tick.LevelTickEvent;
import com.yayuy.overgrownvegetation.port.saved.OvSavedData;

/**
 * 崖・急斜面の汚染の巡回(2026-11-20)。汚染の段が変わったときの呼び口は無いので、プレイヤーのまわりのチャンクを少しずつ見て、
 * 段が上がっていたら 1 回だけ {@link CliffShapes#decorateChunk} を呼ぶ(済みの印は {@link Data} に保存)。
 * <ul>
 *   <li>縄(と細い根・縁の房)= {@code sweep.ropeStage} 以上(既定 1 = 繁茂)。嚢の房 = {@code sweep.sacStage} 以上(既定 2 = 過繁茂)【全体管理の仮の案】。</li>
 *   <li>段はチャンクの真ん中の地面のバイオームで見る(繁茂 / 過繁茂のバイオームはチャンク単位で塗られる)。</li>
 *   <li>オーバーワールドだけ(ネザーは熱の森で別の作り)。汚染の全停止({@link CorruptionSwitch})では止まる。</li>
 *   <li>まわり 1 チャンクが読み込まれていないチャンクは後回し(縄は隣のチャンクへはみ出す)。</li>
 *   <li>1 tick の予算 {@link PerfJob#CLIFF_CORRUPTION}(重い tick は見送る)。</li>
 * </ul>
 * 一度生やしたチャンクは、その段ではもう見ない(押し返して取った根は生え直さない【仮】)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class CliffCorruption {
    private CliffCorruption() {
    }

    /** 巡回の位相(TickPhases のほかの周期処理と重ならない値を選んだ。仮)。 */
    static final int PHASE = 23;
    static final byte ROPES = 1, SACS = 2;

    /** 直近の 1 チャンクの結果(調べのコマンド用)。 */
    static volatile CliffShapes.Result last;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        CliffTable t = DesignData.cliff();
        if (!t.enabled || CorruptionSwitch.isOff()) return;
        CliffTable.Sweep sw = t.sweep;
        if (!TickPhases.isDue(level.getGameTime(), PHASE, Math.max(1, sw.intervalTicks))) return;
        if (level.players().isEmpty()) return;
        if (!TickBudget.mayStart(PerfJob.CLIFF_CORRUPTION)) return;
        long start = TickBudget.begin();
        int done = 0;
        try {
            done = sweep(level, t, level.getRandom());
        } finally {
            TickBudget.end(PerfJob.CLIFF_CORRUPTION, start, done);
        }
    }

    /** 1 回の巡回。生やしたチャンクの数を返す。 */
    static int sweep(ServerLevel level, CliffTable t, RandomSource random) {
        CliffTable.Sweep sw = t.sweep;
        Data data = Data.get(level);
        int processed = 0, tries = Math.max(1, sw.chunksPerPass) * 6;
        int r = Math.max(0, sw.radiusChunks);
        for (int i = 0; i < tries && processed < sw.chunksPerPass; i++) {
            ServerPlayer p = level.players().get(random.nextInt(level.players().size()));
            if (p.isSpectator()) continue;
            ChunkPos pc = p.chunkPosition();
            ChunkPos c = new ChunkPos(pc.x + random.nextInt(2 * r + 1) - r, pc.z + random.nextInt(2 * r + 1) - r);
            if (process(level, data, c, t) != null) processed++;
        }
        return processed;
    }

    /** まわり 1 チャンク(3 × 3)のどこかに縄を生やしたか(経路の延長 {@link RootPath#climbAllowance} の近道)。 */
    public static boolean ropesNear(ServerLevel level, BlockPos pos) {
        Data d = Data.get(level);
        if (d.done.isEmpty()) return false;
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if ((d.done.get(ChunkPos.asLong(cx + dx, cz + dz)) & ROPES) != 0) return true;
        }
        return false;
    }

    /** チャンクの汚染の段(0 = なし・1 = 繁茂・2 = 過繁茂)。チャンクの真ん中の地面のバイオームで見る。 */
    static int stage(ServerLevel level, ChunkPos c) {
        int x = c.getMiddleBlockX(), z = c.getMiddleBlockZ();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        Holder<Biome> b = level.getBiome(new BlockPos(x, y, z));
        if (ModBiomes.isFullyOvergrown(b)) return 2;
        if (ModBiomes.isOvergrownLike(b)) return 1;
        return 0;
    }

    /** 1 チャンクを見て、要れば生やす。生やしたら結果、何もしなければ null。 */
    static CliffShapes.Result process(ServerLevel level, Data data, ChunkPos c, CliffTable t) {
        if (level.getChunkSource().getChunkNow(c.x, c.z) == null) return null;
        int st = stage(level, c);
        if (st == 0) return null;
        byte want = 0;
        if (st >= t.sweep.ropeStage) want |= ROPES;
        if (st >= t.sweep.sacStage) want |= SACS;
        byte have = data.get(c);
        byte todo = (byte) (want & ~have);
        if (todo == 0) return null;
        if (!LoadedChunks.neighbourhoodLoaded(level, c.x, c.z)) return null;
        CliffShapes.Result res = CliffShapes.decorateChunk(level, c, (todo & ROPES) != 0, (todo & SACS) != 0);
        data.mark(c, (byte) (have | todo));
        last = res;
        return res;
    }

    /** 生やした段の印(チャンク → ビット: 1 = 縄・2 = 嚢)。オーバーワールドに保存。 */
    public static final class Data extends OvSavedData {
        static final String ID = "overgrown_vegetation_cliff";
        static final OvSavedData.Factory<Data> FACTORY = new OvSavedData.Factory<>(Data::new, (tag, reg) -> load(tag), null);

        final Long2ByteOpenHashMap done = new Long2ByteOpenHashMap();

        public static Data get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(FACTORY.load(), FACTORY.constructor(), ID);
        }

        byte get(ChunkPos c) {
            return done.get(c.toLong());
        }

        void mark(ChunkPos c, byte bits) {
            done.put(c.toLong(), bits);
            setDirty();
        }

        /** コマンド・テスト用: 印を消す。 */
        public void clear(ChunkPos c) {
            if (done.remove(c.toLong()) != 0) setDirty();
        }

        static Data load(CompoundTag tag) {
            Data d = new Data();
            long[] keys = tag.getLongArray("chunks");
            byte[] bits = tag.getByteArray("bits");
            for (int i = 0; i < Math.min(keys.length, bits.length); i++) d.done.put(keys[i], bits[i]);
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            long[] keys = new long[done.size()];
            byte[] bits = new byte[done.size()];
            int i = 0;
            for (Long2ByteMap.Entry e : done.long2ByteEntrySet()) {
                keys[i] = e.getLongKey();
                bits[i++] = e.getByteValue();
            }
            tag.putLongArray("chunks", keys);
            tag.putByteArray("bits", bits);
            return tag;
        }
    }
}
