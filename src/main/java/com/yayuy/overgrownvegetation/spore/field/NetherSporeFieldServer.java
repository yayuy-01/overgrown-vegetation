package com.yayuy.overgrownvegetation.spore.field;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.corruption.NetherLedger;
import com.yayuy.overgrownvegetation.corruption.NetherLedgerData;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.perf.BackgroundTask;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * <b>ネザーの胞子の場</b>(2026-10-23 胞子の場と風 F。追記 15【決定】)。オーバーワールドと同じ {@link SporeField} を、
 * <b>風も雨も嵐も無い場</b>として回す: 卓越風 0(運ぶのは拡散と林床の層だけ)・上空の層と飛び地なし。
 * 発生源は過繁茂のチャンク(段階別の放出は同じ)と、ポータル越しの染み出し({@link PortalSeepage})。
 * 土地のむら({@link NetherGround}): 溶岩の多いチャンクは乾きで遅く、玄武岩デルタ・真紅の森・歪んだ森は湿りで速い。
 *
 * <p>発芽したチャンクはネザーの記録({@link NetherLedgerData})へ入れ、読み込まれたら今までどおりバイオームを書き換えて
 * 床をまだらに汚染する({@link NetherLedger#acceptFieldSprouts})。前の「台帳の縁へ確率で印を付ける広がり」はやめた。
 * 保存は {@code data/overgrown_vegetation_spore_field_nether/}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class NetherSporeFieldServer {

    private static final long SEED_SALT = 0x4E45544845524649L;

    private static final ReentrantLock LOCK = new ReentrantLock();
    static final ConcurrentLinkedQueue<Consumer<SporeField>> INPUTS = new ConcurrentLinkedQueue<>();
    private static final BackgroundTask<Result> TASK = new BackgroundTask<>("胞子の場(ネザー)");
    private static SporeField field;
    private static long fieldClock = Long.MIN_VALUE;
    private static volatile Long2ObjectOpenHashMap<SporeFieldServer.Readout> READOUT = new Long2ObjectOpenHashMap<>();
    /** プレイヤーがいなくても写したい区画(ポータル・テスト)。 */
    private static final LongOpenHashSet WATCH_EXTRA = new LongOpenHashSet();
    private static volatile String lastSummary = "まだ回っていない";
    private static boolean discardSproutsForTests;

    private NetherSporeFieldServer() {
    }

    public static boolean running() {
        return field != null;
    }

    // ---------------------------------------------------------------- 入力

    public static void emitPoint(int cx, int cz, double amount) {
        if (amount > 0) INPUTS.add(f -> f.emitPoint(cx, cz, amount));
    }

    /** 写しを作る区画に足す(ポータルのチャンク)。 */
    static void watch(int cx, int cz) {
        WATCH_EXTRA.add(SporeField.key(cx >> SporeField.TB, cz >> SporeField.TB));
    }

    public static void watchForTests(int cx, int cz) {
        watch(cx, cz);
    }

    // ---------------------------------------------------------------- 始まり・終わり・保存

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ServerLevel nether = server.getLevel(Level.NETHER);
        INPUTS.clear();
        WATCH_EXTRA.clear();
        if (nether == null) return;
        SporeField f = new SporeField(WindWeatherData.windSeed(server) ^ SEED_SALT, DesignData.sporeField().field());
        f.ground = new NetherGround(nether);
        long[] clock = new long[1];
        int tiles;
        LOCK.lock();
        try {
            tiles = SporeFieldStore.load(server, f, clock, SporeFieldStore.NETHER_DIR);
            // 前の台帳(2026-10-23 より前のワールド)の過繁茂チャンクは、育ちきった汚染済みとして入れる
            long since = PhaseData.get(server.overworld()).clock(server) - (long) (f.p.daysToOvergrown * 2 * PrevailingWind.DAY);
            NetherLedgerData ledger = NetherLedgerData.get(nether);
            ledger.overgrownKeys().forEach((long key) -> {
                int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
                if (!f.isCorrupted(cx, cz)) f.setCorrupted(cx, cz, since);
            });
            field = f;
            fieldClock = clock[0];
        } finally {
            LOCK.unlock();
        }
        OvergrownVegetation.LOGGER.info("胞子の場(ネザー): 区画 {} 個・汚染されたチャンク {} を読み込みました", tiles, f.corruptedCount);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        save(event.getServer(), true);
        LOCK.lock();
        try {
            field = null;
        } finally {
            LOCK.unlock();
        }
        fieldClock = Long.MIN_VALUE;
        lastSummary = "まだ回っていない"; // 次に開くワールドへ前のワールドの要約を見せない
        READOUT = new Long2ObjectOpenHashMap<>();
        WATCH_EXTRA.clear();
        INPUTS.clear();
        LAVA.clear();
    }

    @SubscribeEvent
    public static void onLevelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.NETHER && field != null) {
            save(level.getServer(), false);
        }
    }

    private static void save(MinecraftServer server, boolean wait) {
        SporeFieldStore.Snapshot snap;
        try {
            if (wait) LOCK.lock();
            else if (!LOCK.tryLock(2, TimeUnit.SECONDS)) return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (field == null) return;
            snap = SporeFieldStore.snapshot(field, fieldClock, SporeFieldStore.NETHER_DIR);
        } finally {
            LOCK.unlock();
        }
        SporeFieldStore.write(server, snap, wait);
    }

    // ---------------------------------------------------------------- 回す

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.NETHER || field == null) return;
        if (level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) return; // テストは catchUpForTests で
        tick(level);
    }

    public static boolean tick(ServerLevel nether) {
        MinecraftServer server = nether.getServer();
        SporeFieldTable table = DesignData.sporeField();
        PhaseData phase = PhaseData.get(server.overworld());
        long clock = phase.clock(server);
        if (!table.enabled() || CorruptionSwitch.isOff() || phase.getPhase() < 1 || phase.isFrozen()) {
            fieldClock = clock;
            return false;
        }
        if (TASK.running()) return false;
        if (fieldClock == Long.MIN_VALUE) {
            fieldClock = clock;
            return false;
        }
        return startRun(nether, table, clock) > 0;
    }

    /** テスト用: 場の暦を {@code behindTicks} 遅らせて 1 回分回す(その場で当てはめる)。 */
    public static int catchUpForTests(ServerLevel nether, long behindTicks, boolean keepSprouts) {
        if (field == null) return 0;
        long clock = PhaseData.get(nether.getServer().overworld()).clock(nether.getServer());
        fieldClock = clock - behindTicks;
        discardSproutsForTests = !keepSprouts;
        try {
            return startRun(nether, DesignData.sporeField(), clock);
        } finally {
            discardSproutsForTests = false;
        }
    }

    private record Inputs(long fromClock, int steps, int stepTicks, long dayTimeNow, long clockNow, long[] watchTiles,
                          SporeFieldTable table) {
    }

    private record Result(long toClock, int steps, long[] sprouts, long[] seconds, long nanos, int tiles,
                          long corrupted, Long2ObjectOpenHashMap<SporeFieldServer.Readout> readout) {
    }

    private static int startRun(ServerLevel nether, SporeFieldTable table, long clock) {
        int u = table.updateTicks();
        long behind = clock - fieldClock;
        if (behind < u) return 0;
        int steps = (int) Math.min(table.maxStepsPerRun(), behind / u);
        // 昼夜はオーバーワールドの時計で回す(2026-10-23 相談セッションの答え: ネザーだけ常に速い・遅いにならないように)
        Inputs in = new Inputs(fieldClock, steps, u, nether.getServer().overworld().getDayTime(), clock,
                watchTiles(nether), table);
        SporeField f = field;
        TASK.start(nether.getServer(), () -> compute(f, in), r -> apply(nether, r));
        return steps;
    }

    private static Result compute(SporeField f, Inputs in) {
        long t0 = System.nanoTime();
        LongArrayList sprouts = new LongArrayList();
        LongArrayList seconds = new LongArrayList();
        LOCK.lock();
        try {
            Consumer<SporeField> input;
            while ((input = INPUTS.poll()) != null) input.accept(f);
            PrevailingWind.Sample calm = new PrevailingWind.Sample(); // 風 0・嵐なし
            for (int k = 1; k <= in.steps; k++) {
                long t = in.fromClock + (long) k * in.stepTicks;
                long dayT = in.dayTimeNow - (in.clockNow - t);
                f.step(new SporeField.Conditions(t, dayT, false, false, false), calm);
                for (int[] ns : f.newSprouts) {
                    if (ns[3] == 1) sprouts.add(ChunkPos.asLong(ns[0], ns[1]));
                    else seconds.add(ChunkPos.asLong(ns[0], ns[1]));
                }
            }
            long now = in.fromClock + (long) in.steps * in.stepTicks;
            return new Result(now, in.steps, sprouts.toLongArray(), seconds.toLongArray(), System.nanoTime() - t0, f.tileCount(),
                    f.corruptedCount, SporeFieldServer.readoutOf(f, in.watchTiles, now));
        } finally {
            LOCK.unlock();
        }
    }

    private static void apply(ServerLevel nether, Result r) {
        if (field == null) return;
        fieldClock = Math.max(fieldClock, r.toClock);
        if (r.sprouts.length > 0 && !CorruptionSwitch.isOff() && !discardSproutsForTests) {
            NetherLedger.acceptFieldSprouts(nether, r.sprouts);
        }
        // 2 体目の萌芽体(オーバーワールドと同じ)
        if (r.seconds.length > 0 && !CorruptionSwitch.isOff() && !discardSproutsForTests) {
            for (long key : r.seconds) SporeSprouts.onSecondSprout(nether, key);
        }
        READOUT = r.readout;
        lastSummary = String.format("更新 %d 回 / %.1f ms・区画 %d・汚染されたチャンク %d・発芽 %d",
                r.steps, r.nanos / 1e6, r.tiles, r.corrupted, r.sprouts.length);
        PortalSeepage.afterUpdate(nether.getServer());
        SporeDustSync.send(nether); // 粉・浮遊・プレイヤーの所の濃度(粉の見た目・緑の霧・プルーム)
    }

    private static long[] watchTiles(ServerLevel nether) {
        LongOpenHashSet set = new LongOpenHashSet();
        for (net.minecraft.server.level.ServerPlayer p : nether.players()) {
            int tx = (p.getBlockX() >> 4) >> SporeField.TB, tz = (p.getBlockZ() >> 4) >> SporeField.TB;
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) set.add(SporeField.key(tx + dx, tz + dz));
        }
        set.addAll(WATCH_EXTRA);
        return set.toLongArray();
    }

    // ---------------------------------------------------------------- 読み

    /** 写しのある所の読み。無ければ null。 */
    public static SporeFieldServer.Cell cell(int cx, int cz) {
        SporeFieldServer.Readout r = READOUT.get(SporeField.key(cx >> SporeField.TB, cz >> SporeField.TB));
        if (r == null) return null;
        int i = SporeField.idx(cx, cz);
        return new SporeFieldServer.Cell(r.conc[i], r.dep[i], r.stage[i], r.age[i], r.count[i]);
    }

    public static double airAt(int cx, int cz) {
        SporeFieldServer.Readout r = READOUT.get(SporeField.key(cx >> SporeField.TB, cz >> SporeField.TB));
        return r == null ? 0.0D : r.air[SporeField.idx(cx, cz)];
    }

    public static String summary() {
        return lastSummary;
    }

    /** テスト用: 場の値を鍵を持って読む・書く。 */
    public static <T> T withFieldForTests(java.util.function.Function<SporeField, T> fn) {
        LOCK.lock();
        try {
            return field == null ? null : fn.apply(field);
        } finally {
            LOCK.unlock();
        }
    }

    // ---------------------------------------------------------------- 土地

    /** 読み込んだチャンクの溶岩の多さ(列の割合 × 100、byte)。裏のスレッドからも読む。 */
    private static final ConcurrentHashMap<Long, Byte> LAVA = new ConcurrentHashMap<>();
    /** これ以上の割合の列が溶岩の面なら「溶岩の近く」= 乾き(仮決め)。 */
    private static final int LAVA_DRY_PERCENT = 25;

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.NETHER
                || !(event.getChunk() instanceof LevelChunk chunk)) return;
        long key = chunk.getPos().toLong();
        if (LAVA.containsKey(key)) return;
        LAVA.put(key, (byte) lavaPercent(level, chunk));
    }

    /** 16 列を見て、上から最初の空気でないマスが溶岩の列の割合(%)。 */
    static int lavaPercent(ServerLevel level, LevelChunk chunk) {
        int top = com.yayuy.overgrownvegetation.corruption.NetherSurface.topY(level);
        int bottom = com.yayuy.overgrownvegetation.corruption.NetherSurface.bottomY(level);
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        int lava = 0;
        for (int i = 0; i < 16; i++) {
            int x = chunk.getPos().getMinBlockX() + (i & 3) * 4 + 2, z = chunk.getPos().getMinBlockZ() + (i >> 2) * 4 + 2;
            boolean sawAir = false;
            for (int y = top; y >= bottom; y--) {
                var st = chunk.getBlockState(c.set(x, y, z));
                if (st.isAir()) {
                    sawAir = true;
                    continue;
                }
                if (sawAir) {
                    if (st.getFluidState().is(Fluids.LAVA) || st.is(Blocks.MAGMA_BLOCK)) lava++;
                    // 洞窟の天井と床を区別しない: 空気の下の最初の面だけ見る
                    break;
                }
            }
        }
        return lava * 100 / 16;
    }

    /** ネザーの土地(2026-10-23 F の仮決め): 溶岩の多いチャンク × 0.3、玄武岩デルタ × 2、真紅の森・歪んだ森 × 1.5、ほか 1。 */
    static final class NetherGround implements SporeField.Ground {
        private final BiomeSource source;
        private final Climate.Sampler sampler;

        NetherGround(ServerLevel nether) {
            this.source = nether.getChunkSource().getGenerator().getBiomeSource();
            this.sampler = nether.getChunkSource().randomState().sampler();
        }

        private Holder<Biome> at(int cx, int cz) {
            return source.getNoiseBiome((cx << 2) + 2, com.yayuy.overgrownvegetation.corruption.NetherSurface.BIOME_SAMPLE_Y >> 2,
                    (cz << 2) + 2, sampler);
        }

        @Override
        public float germFactor(int cx, int cz) {
            return 1f;
        }

        @Override
        public boolean wet(int cx, int cz) {
            Holder<Biome> b = at(cx, cz);
            return b.is(Biomes.CRIMSON_FOREST) || b.is(Biomes.WARPED_FOREST) || b.is(Biomes.BASALT_DELTAS);
        }

        @Override
        public float spreadFactor(int cx, int cz) {
            Byte lava = LAVA.get(ChunkPos.asLong(cx, cz));
            if (lava != null && lava >= LAVA_DRY_PERCENT) return 0.3f;
            Holder<Biome> b = at(cx, cz);
            if (b.is(Biomes.BASALT_DELTAS)) return 2f;
            if (b.is(Biomes.CRIMSON_FOREST) || b.is(Biomes.WARPED_FOREST)) return 1.5f;
            return 1f;
        }
    }
}
