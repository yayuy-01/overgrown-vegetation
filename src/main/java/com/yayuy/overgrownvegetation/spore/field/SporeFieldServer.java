package com.yayuy.overgrownvegetation.spore.field;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.corruption.LedgerSpread;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.perf.BackgroundTask;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 胞子の場をゲームで回す所(2026-10-23 胞子の場と風 第 2 段 A・C)。仕様の正本は Vault「胞子の場と風による汚染の広がり」。
 *
 * <h2>流れ</h2>
 * <ol>
 *     <li>オーバーワールドの tick で、汚染の暦({@link PhaseData#clock})が場の暦より {@code updateTicks}(5 秒)進んでいたら、
 *     その時点の入力(天気の印・巨大樹・噴出中の地割れ)の写しを作り、{@link BackgroundTask} で裏のスレッドへ</li>
 *     <li>裏のスレッドは場の鍵を持って、溜まった入力(点の放出・台帳に載ったチャンク)を取り込み、遅れた分の更新を
 *     {@code maxStepsPerRun} 回まで進める(睡眠で暦が飛んだ分はここで追いつく)</li>
 *     <li>答え(初めて発芽したチャンク・2 体目の萌芽体)を次の tick の頭にサーバースレッドで当てはめる。
 *     発芽したチャンクは {@link LedgerSpread#acceptFieldSprouts} で台帳(フェーズに数える)と書き換え待ちへ —— 読み込まれたら今までどおり
 *     {@code OvergrownBiomeConverter} の流れで過繁茂になる</li>
 * </ol>
 * 場そのものは裏のスレッドと、鍵を持ったサーバースレッド(保存・/ov の表示)しか触らない。ほかの所からの入力は列 {@link #INPUTS} に積む。
 *
 * <p>止まるとき: {@code /ov corruption false}・フェーズ 0・フェーズの凍結・{@code spore_field.json} の enabled=false。
 * 止まっている間の時間は取り戻さない(台帳の広がりと同じ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SporeFieldServer {

    private static final ReentrantLock LOCK = new ReentrantLock();
    /** ほかの所からの入力(どのスレッドからでも積める。裏のスレッドが計算の頭で取り込む)。 */
    private static final ConcurrentLinkedQueue<Consumer<SporeField>> INPUTS = new ConcurrentLinkedQueue<>();
    private static final BackgroundTask<Result> TASK = new BackgroundTask<>("胞子の場");

    private static SporeField field;
    /** 場がどの暦まで進んだか(サーバースレッドが持つ)。 */
    private static long fieldClock = Long.MIN_VALUE;
    private static MinecraftServer current;
    /** 直近の計算の要約(/ov perf spore)。 */
    private static volatile String lastSummary = "まだ回っていない";
    private static volatile int lastFrontCells, lastFronts;

    /** テスト用: 発芽を台帳へ渡さない(テストの世界を過繁茂にしない)。 */
    private static boolean discardSproutsForTests;

    private SporeFieldServer() {
    }

    // ---------------------------------------------------------------- ほかの所からの入力

    /**
     * 点の放出(汚染の球・G1/G4 の死・寄生クリーパーなど)。量は過繁茂 1 チャンクの 1 日の放出が 1。
     * オーバーワールドとネザー(2026-11-12 ユーザー決定: 同じ生き物がネザーにも来るので、ネザーの場へ回す。量の単位は同じ)。ほかの次元は捨てる。
     */
    public static void emitPoint(ServerLevel level, BlockPos pos, double amount) {
        if (amount <= 0) return;
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        if (level.dimension() == Level.NETHER) {
            NetherSporeFieldServer.emitPoint(cx, cz, amount);
            return;
        }
        if (level.dimension() != Level.OVERWORLD) return;
        INPUTS.add(f -> f.emitPoint(cx, cz, amount));
    }

    /** 点の降り積もり(歩く胞子嚢の破裂など。G1 の作り直し 第 1 段)。量は場の降り積もりの単位。オーバーワールドとネザー(2026-11-12)。 */
    public static void depositPoint(ServerLevel level, BlockPos pos, double amount) {
        if (amount <= 0) return;
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        if (level.dimension() == Level.NETHER) {
            NetherSporeFieldServer.depositPoint(cx, cz, amount);
            return;
        }
        if (level.dimension() != Level.OVERWORLD) return;
        INPUTS.add(f -> f.depositPoint(cx, cz, amount));
    }

    /** 台帳に載った(どの経路でも。{@link PhaseData#markOvergrownChunks} など)→ 場でも汚染済み・放出中に。 */
    public static void onLedgerAdded(long chunkKey, long since) {
        int cx = ChunkPos.getX(chunkKey), cz = ChunkPos.getZ(chunkKey);
        INPUTS.add(f -> {
            if (!f.isCorrupted(cx, cz)) f.setCorrupted(cx, cz, since);
        });
    }

    /** シャベルで粉を払った(SporeSweeping)。そのチャンクの降り積もりを share だけ減らす。 */
    public static void sweep(BlockPos pos, double share) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        INPUTS.add(f -> f.sweep(cx, cz, share));
    }

    /** 読み込まれるまで発芽を待っているチャンク(場の flags の写し。読み込まれたら場へ解くよう頼む)。サーバースレッド。 */
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet WAITING = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    @SubscribeEvent
    public static void onChunkLoadRelease(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        if (WAITING.isEmpty() || !(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        long key = event.getChunk().getPos().toLong();
        if (!WAITING.remove(key)) return;
        int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
        long now = fieldClock;
        INPUTS.add(f -> f.releaseWaiting(cx, cz, now));
    }

    /** テスト用: 待ちの見張りを見る。 */
    public static boolean waitingForTests(long key) {
        return WAITING.contains(key);
    }

    /** 本物の高さを見る列(作り直し側 LevelTerrain と同じ 5 本。葉・木を拾わないよう水の底の高さのいちばん低いもの)。 */
    private static final int[][] HEIGHT_COLUMNS = {{8, 8}, {3, 3}, {12, 3}, {3, 12}, {12, 12}};

    /**
     * チャンクが読み込まれた: 本物の地表の高さを場へ渡す(2026-10-30 谷風・逆転層・山の壁。場の区画を作るときは生成器の式の補間)。
     * 区画が無ければ場は何もしない(区画を作るときは式の値)。チャンクを読み込み直すたびに同じ値が入るだけ。
     */
    @SubscribeEvent
    public static void onChunkLoadHeight(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        if (field == null || !(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!(event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk)) return;
        float y = exactGroundY(chunk);
        int cx = chunk.getPos().x, cz = chunk.getPos().z;
        INPUTS.add(f -> f.setExactHeight(cx, cz, y));
    }

    /** 読み込まれたチャンクの地表の高さ(5 本の列の水の底の高さのいちばん低いもの)。 */
    public static float exactGroundY(net.minecraft.world.level.chunk.ChunkAccess chunk) {
        int y = Integer.MAX_VALUE;
        for (int[] c : HEIGHT_COLUMNS) {
            // 過繁茂の葉・幹は高さ地図に入るので、梢をくぐった本当の地面で(2026-11-04: 林冠が地表 y 186 と記録され、逆転層の判定に響いた)
            int top = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, c[0], c[1]);
            y = Math.min(y, CaveMouths.groundTop(chunk, chunk.getPos().getMinBlockX() + c[0], top, chunk.getPos().getMinBlockZ() + c[1], chunk.getMinBuildHeight()));
        }
        return y;
    }

    /** 洞窟の口の多さを測った({@link CaveMouths}。2026-11-04)。区画が無ければ場は何もしない。 */
    static void onMouthMeasured(int cx, int cz, float mouth) {
        if (field == null) return;
        INPUTS.add(f -> f.setMouth(cx, cz, mouth));
    }

    /**
     * 今日の逆転層の高さ(ブロック。2026-11-04 高所の読み)。場の seed と日から決まる式なので鍵は要らない。場がまだ無ければ +∞(切らない)。
     */
    public static double inversionNow(ServerLevel level) {
        SporeField f = field;
        if (f == null) return Double.POSITIVE_INFINITY;
        return f.inversionY(Math.floorDiv(level.getDayTime(), (long) PrevailingWind.DAY));
    }

    /** 息をする地割れのチャンク(噴出した地割れの中心。2026-10-30)。 */
    private static long[] breathingChunks() {
        long[] rifts = com.yayuy.overgrownvegetation.quake.EarthquakeData.riftCenters();
        long[] out = new long[rifts.length];
        for (int i = 0; i < rifts.length; i++) out[i] = ChunkPos.asLong(BlockPos.getX(rifts[i]) >> 4, BlockPos.getZ(rifts[i]) >> 4);
        return out;
    }

    /**
     * 地割れが噴き出した(2026-10-24 追記 19): 中心から全方位へ広がる圧の前線を出す。濃さは frontFullRadiusChunks で濃度 100%、
     * 距離^frontFalloff に反比例で薄まり、frontMinPercent を割る所で止まる。速さは frontSpeedChunksPerSec(ゲームの 1 秒 = 20 tick)。
     */
    public static void onRiftErupt(int cx, int cz) {
        SporeFieldTable t = DesignData.sporeField();
        double full = t.fullAt();
        double strength = full * Math.pow(t.riftFrontFullRadius(), t.riftFrontFalloff());
        double perDay = t.riftFrontSpeed() * (PrevailingWind.DAY / 20.0D);
        double minAir = full * t.riftFrontMinPercent() / 100.0D;
        SporeField.RiftFront rf = new SporeField.RiftFront(cx, cz, strength, t.riftFrontFalloff(), perDay, minAir);
        rf.maxRadius = t.riftFrontRadius(); // 2026-10-24 ユーザー指定: 半径 12〜16 チャンクで止める
        INPUTS.add(f -> f.addRiftFront(rf));
    }

    /** 開発用(/ov perf spore deposit): まわり 3×3 チャンクの降り積もりを発芽のしきい値の percent % にする。オーバーワールドだけ。 */
    public static void setDepositForCommand(ServerLevel level, int cx, int cz, double percent) {
        if (level.dimension() != Level.OVERWORLD) return;
        double v = DesignData.sporeField().field().germThreshold * percent / 100.0D;
        INPUTS.add(f -> {
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    f.clearDepositAround(cx + dx, cz + dz, 0.0D);
                    f.depositPoint(cx + dx, cz + dz, v);
                }
        });
    }

    /** 根付いた萌芽体が 1 体壊された → その升の萌芽体の数を 1 減らす。 */
    public static void onSproutRemoved(BlockPos pos) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        INPUTS.add(f -> f.removeOneSprout(cx, cz));
    }

    /** 台帳から外れた(焼けた荒地)→ 場でも汚染を取り消す。 */
    public static void onLedgerRemoved(long chunkKey) {
        int cx = ChunkPos.getX(chunkKey), cz = ChunkPos.getZ(chunkKey);
        INPUTS.add(f -> f.unsprout(cx, cz));
    }

    /** 場が回っているか(設計データで有効)。 */
    public static boolean enabled() {
        return DesignData.sporeField().enabled();
    }

    // ---------------------------------------------------------------- 始まりと終わり

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        current = server;
        INPUTS.clear();
        ServerLevel overworld = server.overworld();
        SporeField f = new SporeField(WindWeatherData.windSeed(server), DesignData.sporeField().field());
        f.ground = new BiomeGround(overworld, f.p);
        long[] clock = new long[1];
        int tiles;
        LOCK.lock();
        try {
            tiles = SporeFieldStore.load(server, f, clock);
            // 台帳と照らし合わせる(台帳にあって場に無いチャンクは汚染済みとして入れる。新しいワールドの地割れなど)
            PhaseData phase = PhaseData.get(overworld);
            phase.forEachOvergrownChunk(key -> {
                int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
                if (!f.isCorrupted(cx, cz)) f.setCorrupted(cx, cz, phase.overgrownSinceTick(new ChunkPos(key)));
            });
            field = f;
            fieldClock = clock[0];
            WAITING.clear();
            f.forEachWaiting(WAITING::add);
            com.yayuy.overgrownvegetation.quake.EarthquakeData.get(overworld); // 地割れの中心(下支え)を読み込んでおく
        } finally {
            LOCK.unlock();
        }
        OvergrownVegetation.LOGGER.info("胞子の場: 区画 {} 個・汚染されたチャンク {} を読み込みました", tiles, f.corruptedCount);
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
        current = null;
        READOUT = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        WATCH_EXTRA.clear();
        WAITING.clear();
        com.yayuy.overgrownvegetation.quake.EarthquakeData.clearCache();
        SporeSweeping.clear();
        SporeDustSync.clear();
        INPUTS.clear();
    }

    @SubscribeEvent
    public static void onLevelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD && current != null) {
            save(level.getServer(), false);
        }
    }

    private static void save(MinecraftServer server, boolean wait) {
        SporeFieldStore.Snapshot snap;
        try {
            // 裏で計算中なら終わるのを待つ(閉じるときは必ず、自動保存は 2 秒まで)
            if (wait) LOCK.lock();
            else if (!LOCK.tryLock(2, TimeUnit.SECONDS)) return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (field == null) return;
            snap = SporeFieldStore.snapshot(field, fieldClock);
        } finally {
            LOCK.unlock();
        }
        SporeFieldStore.write(server, snap, wait);
    }

    // ---------------------------------------------------------------- 回す

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || field == null) {
            return;
        }
        // GameTest のサーバーでは自分では回さない(ほかのテストが残した台帳から発芽が広がり、無関係なテスト場を過繁茂にしないため)。
        // 場のテストは tick / catchUpForTests を直に呼ぶ
        if (level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) {
            return;
        }
        tick(level);
    }

    /** 1 tick 分(テストからも呼ぶ)。計算を始めたら true。 */
    public static boolean tick(ServerLevel level) {
        MinecraftServer server = level.getServer();
        SporeFieldTable table = DesignData.sporeField();
        PhaseData phase = PhaseData.get(level);
        long clock = phase.clock(server);
        if (!table.enabled() || CorruptionSwitch.isOff() || phase.getPhase() < 1 || phase.isFrozen()) {
            fieldClock = clock; // 止まっている間の時間は取り戻さない
            return false;
        }
        if (TASK.running()) return false;
        if (fieldClock == Long.MIN_VALUE) {
            fieldClock = clock;
            return false;
        }
        return startRun(level, table, clock) > 0;
    }

    /** 遅れていれば計算を始める。進める更新の回数を返す(0 = 始めない)。 */
    private static int startRun(ServerLevel level, SporeFieldTable table, long clock) {
        int u = table.updateTicks();
        long behind = clock - fieldClock;
        if (behind < u) return 0;
        int steps = (int) Math.min(table.maxStepsPerRun(), behind / u);
        Inputs in = gatherInputs(level, table, clock, steps);
        SporeField f = field;
        TASK.start(level.getServer(), () -> compute(f, in), r -> apply(level, r));
        return steps;
    }

    /**
     * テスト用: 場の暦を {@code behindTicks} 遅らせて 1 回分の計算を回す(フェーズ・凍結の判定を飛ばす。GameTest ではその場で当てはめる)。
     * 進めた更新の回数を返す。
     */
    public static int catchUpForTests(ServerLevel level, long behindTicks) {
        return catchUpForTests(level, behindTicks, false);
    }

    /** このチャンクの発芽だけ台帳へ渡す(読み込まれるまで待つ升のテスト。ほかのテストの升は今までどおり捨てる)。 */
    public static int catchUpForTests(ServerLevel level, long behindTicks, long keepOnlyKey) {
        if (field == null) return 0;
        long clock = PhaseData.get(level).clock(level.getServer());
        fieldClock = clock - behindTicks;
        keepOnlyForTests = keepOnlyKey;
        try {
            return startRun(level, DesignData.sporeField(), clock);
        } finally {
            keepOnlyForTests = null;
        }
    }

    private static Long keepOnlyForTests;

    public static int catchUpForTests(ServerLevel level, long behindTicks, boolean keepSprouts) {
        if (field == null) return 0;
        long clock = PhaseData.get(level).clock(level.getServer());
        fieldClock = clock - behindTicks;
        discardSproutsForTests = !keepSprouts; // GameTest ではその場で当てはめるので、この呼び出しの中だけ
        try {
            return startRun(level, DesignData.sporeField(), clock);
        } finally {
            discardSproutsForTests = false;
        }
    }

    /** サーバースレッドで作る入力の写し。 */
    private record Inputs(long fromClock, int steps, int stepTicks, long dayTimeNow, long clockNow, long seed,
                          PrevailingWind.Weather weather, long lastRainEnd, float rainIntensity, float afterIntensity,
                          long[] canopyChunks, double[] canopyRate, long[] eruptions, long[] breathers,
                          long[] watchTiles, SporeFieldTable table, long[] trees,
                          com.yayuy.overgrownvegetation.survival.vent.Ventilators.Snapshot vents) {
    }

    private static Inputs gatherInputs(ServerLevel level, SporeFieldTable table, long clock, int steps) {
        MinecraftServer server = level.getServer();
        WindWeatherData wx = WindWeatherData.get(server);
        // 巨大樹の梢(中心)= 第二の胞子の震源(2026-10-23 D)。ハイヴノードを倒された木は外す。記録の古い順に maxTrees 本まで
        PhaseData pd = PhaseData.get(level);
        LongArrayList cl = new LongArrayList();
        it.unimi.dsi.fastutil.doubles.DoubleArrayList cr = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
        forEachTreeSource(pd, level.getGameTime(), table, (tr, perDay) -> {
            cl.add(ChunkPos.asLong(((int) (tr >> 32)) >> 4, ((int) tr) >> 4));
            cr.add(perDay);
        });
        long[] canopy = cl.toLongArray();
        double[] canopyRate = cr.toDoubleArray();
        return new Inputs(fieldClock, steps, table.updateTicks(), level.getDayTime(), clock, field.seed,
                wx.weather(), wx.lastRainEnd(),
                // 雨の強さ(11-12 雨の種類。世界で 1 つ・雷の無い雨の値)と、止んだ雨の強さ。
                com.yayuy.overgrownvegetation.sky.RainIntensity.global(level, level.isRaining(), false, 0.0F), wx.lastRainIntensity(),
                canopy, canopyRate, com.yayuy.overgrownvegetation.quake.EarthquakeData.get(level).eruptingChunks(level.getGameTime()),
                breathingChunks(),
                watchTiles(level), table, livingTreeChunks(pd),
                // 換気機(2026-11-14 段 2c): 動いている機械の場所と、この計算で抜ける量(フィルターの残りで切る)
                com.yayuy.overgrownvegetation.survival.vent.Ventilators.snapshot(level, steps, table));
    }

    /** 胞子を出している巨大樹 1 本(根元の x,z を詰めた値と、1 日あたりの放出。朝の上乗せは入らない)。 */
    @FunctionalInterface
    public interface TreeSink {
        void accept(long packedOrigin, double perDay);
    }

    /**
     * 胞子の発生源になっている巨大樹を記録の古い順に {@code maxTrees} 本まで(ハイヴノードを倒された木は外す)、育ち具合の 1 日の放出と一緒に渡す
     * (2026-11-06 に {@link #gatherInputs} から出した。巨大樹の胞子の柱 {@code GiantTreeSync} も同じ規則で量を決める)。
     */
    public static void forEachTreeSource(PhaseData pd, long gameTime, SporeFieldTable table, TreeSink sink) {
        int max = table.giantTreeMaxTrees() > 0 ? table.giantTreeMaxTrees() : Integer.MAX_VALUE;
        int n = 0;
        for (long tr : pd.giantTreeOriginSnapshot()) {
            if (n >= max) break;
            if (pd.isGiantTreeDead(tr)) continue;
            long born = pd.giantTreeBorn(tr);
            double ageDays = born == Long.MIN_VALUE ? Double.MAX_VALUE : (gameTime - born) / 24000.0D;
            sink.accept(tr, table.giantTreePerDay(ageDays));
            n++;
        }
    }

    /** 朝(時刻 0〜3000 = 6〜9 時)の梢の上乗せ(1 日の量を朝の 1/8 日に寄せる)。{@link #compute} と胞子の柱で同じ。 */
    public static double canopyMorningPerDay(SporeFieldTable table, long timeOfDay) {
        return Math.floorMod(timeOfDay, PrevailingWind.DAY) < 3000 ? table.canopyMorningPerDay() * 8 : 0.0D;
    }

    /**
     * 生きている巨大樹の根元のチャンク(本数の上限 maxTrees を掛けない。逆転層より上でも発芽・着地を許す例外に使う。2026-11-04)。
     */
    private static long[] livingTreeChunks(PhaseData pd) {
        LongArrayList out = new LongArrayList();
        for (long tr : pd.giantTreeOriginSnapshot()) {
            if (pd.isGiantTreeDead(tr)) continue;
            out.add(ChunkPos.asLong(((int) (tr >> 32)) >> 4, ((int) tr) >> 4));
        }
        return out.toLongArray();
    }

    /** 計算の答え。 */
    private record Result(long toClock, int steps, long[] sprouts, long nanos,
                          int tiles, long corrupted, int parcels, it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Readout> readout, long[] seconds,
                          long[] caves, long[] ventPos, double[] ventTaken) {
    }

    /** 裏のスレッド(GameTest ではその場)。場の鍵を持って入力を取り込み、更新を進める。 */
    private static Result compute(SporeField f, Inputs in) {
        long t0 = System.nanoTime();
        LongArrayList sprouts = new LongArrayList();
        LongArrayList seconds = new LongArrayList();
        LongArrayList caves = new LongArrayList();
        int frontCells = 0;
        int nVents = in.vents.pos().length;
        double[] ventTaken = new double[nVents];
        double ventCap = in.table.vent().capPerUpdate(), ventRadius = in.table.vent().radiusChunks();
        LOCK.lock();
        try {
            Consumer<SporeField> input;
            while ((input = INPUTS.poll()) != null) input.accept(f);
            f.breathers = in.breathers; // 息をする地割れ(2026-10-30)
            f.treeChunks = in.trees; // 逆転層より上の例外(生きている巨大樹の近く。2026-11-04)
            f.sproutDep.clear(); // 前の答えの発芽の直前の値は、断られた分を戻し終えたので捨てる
            for (long k : f.released) sprouts.add(k); // 読み込まれて待ちが解けた発芽
            f.released.clear();
            SporeFieldTable table = in.table;
            double dt = in.stepTicks / (double) PrevailingWind.DAY;
            PrevailingWind.Sample s = new PrevailingWind.Sample();
            for (int k = 1; k <= in.steps; k++) {
                long t = in.fromClock + (long) k * in.stepTicks;
                long dayT = in.dayTimeNow - (in.clockNow - t);
                boolean raining = in.weather.raining(), thundering = in.weather.thundering();
                // 雨の後の湿りの長さは止んだ雨の強さで(11-12。普通 1 倍・小雨 0.5 倍・大雨 1.5 倍)。
                double afterScale = Math.max(0.5, Math.min(1.5, com.yayuy.overgrownvegetation.sky.RainIntensity.normalized(in.afterIntensity)));
                boolean after = !raining && in.lastRainEnd != PrevailingWind.NONE && dayT - in.lastRainEnd < f.p.afterRainTicks * afterScale;
                PrevailingWind.sample(in.seed, dayT, in.weather, s);
                // ゲームの発生源
                long tod = Math.floorMod(dayT, PrevailingWind.DAY);
                double liftFrac = 1.0D - Math.exp(-table.giantTreeLiftPerDay() * dt);
                for (int i = 0; i < in.canopyChunks.length; i++) {
                    long c = in.canopyChunks[i];
                    int ccx = ChunkPos.getX(c), ccz = ChunkPos.getZ(c);
                    // 一日中(育ち具合で増える)。朝(6〜9 時)はさらに上乗せ(1 日の量を朝の 1/8 日に寄せる)
                    double perDay = in.canopyRate[i] + canopyMorningPerDay(table, tod);
                    f.emitPoint(ccx, ccz, perDay * dt);
                    // 嵐が無くても梢の浮遊を上空の層へ持ち上げる(晴れの日も塊になって飛び地を作る)
                    if (liftFrac > 0) f.liftAt(ccx, ccz, liftFrac);
                }
                // 地割れの噴出(2026-10-24 追記 19): 巨大樹の成木の何倍もの 1 日分を噴出の長さに詰めて放出し、上空の塊へ持ち上げる
                if (in.eruptions.length > 0) {
                    double eruptDays = Math.max(1, com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.quakeEruptDurationTicks.get())
                            / (double) PrevailingWind.DAY;
                    double rate = table.riftTreeMultiple() * table.giantTreePerDay(Double.MAX_VALUE) / eruptDays;
                    double riftLift = 1.0D - Math.exp(-table.riftLiftPerDay() * dt);
                    for (long c : in.eruptions) {
                        f.emitPoint(ChunkPos.getX(c), ChunkPos.getZ(c), rate * dt);
                        if (riftLift > 0) f.liftAt(ChunkPos.getX(c), ChunkPos.getZ(c), riftLift);
                    }
                }
                f.step(new SporeField.Conditions(t, dayT, raining, thundering, after, in.rainIntensity, in.afterIntensity), s);
                frontCells += f.frontCells;
                for (int[] cs : f.newCaveSprouts) caves.add(ChunkPos.asLong(cs[0], cs[1])); // 洞窟の床からの発芽(2026-11-04)
                for (int[] ns : f.newSprouts) {
                    if (ns[3] == 1) sprouts.add(ChunkPos.asLong(ns[0], ns[1]));
                    else seconds.add(ChunkPos.asLong(ns[0], ns[1]));
                }
                // 吸い込み口: 換気機(2026-11-14 段 2c、決定 11)。場そのものから、1 回の更新で上限まで抜く(範囲を 0 にはしない。
                // 流れ込みが少なければ近くはほぼ 0 まで下がり、上限を超える流れ込みは抜き切れない)。降り積もりは捨てない(発芽は止めない)。
                // 前の除去装置・拡散装置は 2026-10-30 に parked/ へ
                for (int i = 0; i < nVents; i++) {
                    double room = Math.min(ventCap, in.vents.budget()[i] - ventTaken[i]);
                    if (room <= 0) continue;
                    long d = in.vents.pos()[i];
                    ventTaken[i] += f.extractAround(BlockPos.getX(d) >> 4, BlockPos.getZ(d) >> 4, ventRadius, room);
                }
            }
            lastFrontCells = frontCells;
            lastFronts = f.fronts.size();
            return new Result(in.fromClock + (long) in.steps * in.stepTicks, in.steps, sprouts.toLongArray(),
                    System.nanoTime() - t0, f.tileCount(), f.corruptedCount, f.parcels.size(), readout(f, in), seconds.toLongArray(),
                    caves.toLongArray(), in.vents.pos(), ventTaken);
        } finally {
            LOCK.unlock();
        }
    }

    /** サーバースレッドで答えを当てはめる。 */
    private static void apply(ServerLevel level, Result r) {
        if (field == null) return;
        fieldClock = Math.max(fieldClock, r.toClock);
        long[] sproutKeys = keepOnlyForTests == null ? r.sprouts
                : java.util.Arrays.stream(r.sprouts).filter(k -> k == keepOnlyForTests).toArray();
        if (sproutKeys.length > 0 && !CorruptionSwitch.isOff() && !discardSproutsForTests) {
            LongArrayList waiting = new LongArrayList();
            long[] rejected = LedgerSpread.acceptFieldSprouts(level, sproutKeys, waiting);
            for (long key : rejected) onLedgerRemoved(key);
            // 読み込まれていないので断られた: 取り消さずに読み込まれるまで待たせる(降り積もりは残す。2026-10-24)
            for (long key : waiting) {
                int wcx = ChunkPos.getX(key), wcz = ChunkPos.getZ(key);
                INPUTS.add(f -> f.waitForLoad(wcx, wcz));
                WAITING.add(key);
            }
        }
        // 2 体目の萌芽体(読み込まれていれば歩く萌芽体を出す。読み込まれていなければ読み込んだときに根付いた姿で)
        if (r.seconds.length > 0 && !CorruptionSwitch.isOff() && !discardSproutsForTests && keepOnlyForTests == null) {
            for (long key : r.seconds) SporeSprouts.onSecondSprout(level, key);
        }
        // 洞窟の床からの発芽(2026-11-04): 読み込まれていれば次の tick から洞窟の床に芽の点を置く(読み込まれていなければ読み込んだときに)
        if (r.caves.length > 0 && !CorruptionSwitch.isOff() && !discardSproutsForTests && keepOnlyForTests == null) {
            for (long key : r.caves) SporeSprouts.onCaveSprout(level, key);
        }
        READOUT = r.readout;
        // 換気機が抜いた量をフィルターへ(2026-11-14 段 2c)
        if (r.ventPos.length > 0) {
            com.yayuy.overgrownvegetation.survival.vent.Ventilators.apply(level, r.ventPos, r.ventTaken, r.steps, DesignData.sporeField());
        }
        SporeSweeping.refresh(level); // 払った升の印を消すか見る
        SporeDustSync.send(level); // 粉の見た目(2026-10-31 に戻した)
        lastSummary = String.format("更新 %d 回 / %.1f ms・区画 %d・汚染されたチャンク %d・発芽 %d・上空の塊 %d",
                r.steps, r.nanos / 1e6, r.tiles, r.corrupted, r.sprouts.length, r.parcels)
                + (lastFronts > 0 || lastFrontCells > 0 ? String.format("・地割れの前線 %d 本(配った升 %d)", lastFronts, lastFrontCells) : "");
    }

    // ---------------------------------------------------------------- 読み(濃度・計測機・粉の見た目)

    /**
     * 区画 1 つの読みの写し(裏のスレッドが計算の終わりに作り、サーバースレッドは鍵なしで読む)。
     * conc = 浮遊 + 林床(濃度のもと)、dep = 降り積もり、stage = 0 汚染なし・1 繁茂(発芽から過繁茂まで)・2 過繁茂、
     * age = 発芽からの日数(汚染なしは -1)、count = 発芽の回数(萌芽体の数)。
     */
    public static final class Readout {
        final float[] conc = new float[SporeField.TN], dep = new float[SporeField.TN], age = new float[SporeField.TN], air = new float[SporeField.TN];
        final byte[] stage = new byte[SporeField.TN], count = new byte[SporeField.TN];
        /** 地下の浮遊と、洞窟の床から発芽した回数(2026-11-04)。 */
        final float[] under = new float[SporeField.TN], underDep = new float[SporeField.TN];
        final byte[] cave = new byte[SporeField.TN];
    }

    /** 1 チャンクの読み(写しに無ければ null)。under = 地下の浮遊、caveCount = 洞窟の床から発芽した回数(2026-11-04)。 */
    public record Cell(double conc, double dep, int stage, double ageDays, int count, double under, int caveCount, double underDep) {
        public Cell(double conc, double dep, int stage, double ageDays, int count) {
            this(conc, dep, stage, ageDays, count, 0.0D, 0, 0.0D);
        }
    }

    private static volatile it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Readout> READOUT = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    /** プレイヤーのまわり ±この数の区画(32 チャンク)を写す。 */
    private static final int WATCH_TILES = 1;

    /**
     * 次元ごとの読み(2026-10-23 F): オーバーワールド = この場、ネザー = ネザーの場、エンドなど = null。
     * ネザーでも動く所はこちらを使う(次元を見ない {@link #cell(int, int)} はオーバーワールドの値を返す)。
     */
    public static Cell cell(ServerLevel level, int cx, int cz) {
        if (level.dimension() == Level.OVERWORLD) return cell(cx, cz);
        if (level.dimension() == Level.NETHER) return NetherSporeFieldServer.cell(cx, cz);
        return null;
    }

    /** 次元ごとの浮遊(2026-10-23 F)。 */
    public static double airAt(ServerLevel level, int cx, int cz) {
        if (level.dimension() == Level.OVERWORLD) return airAt(cx, cz);
        if (level.dimension() == Level.NETHER) return NetherSporeFieldServer.airAt(cx, cz);
        return 0.0D;
    }

    /** 写しのある所の読み。無ければ null(遠い・場がまだ回っていない)。サーバースレッドから。 */
    public static Cell cell(int cx, int cz) {
        Readout r = READOUT.get(SporeField.key(cx >> SporeField.TB, cz >> SporeField.TB));
        if (r == null) return null;
        int i = SporeField.idx(cx, cz);
        return new Cell(r.conc[i], r.dep[i], r.stage[i], r.age[i], r.count[i], r.under[i], r.cave[i], r.underDep[i]);
    }

    private static final PrevailingWind.Sample WIND_NOW = new PrevailingWind.Sample();
    private static long windNowTick = Long.MIN_VALUE;

    /** 今の卓越風(同じ tick の中は覚えておく)。場がまだ無ければ null。サーバースレッドから。 */
    public static PrevailingWind.Sample windNow(ServerLevel level) {
        if (field == null) return null;
        long now = level.getGameTime();
        if (now != windNowTick) {
            PrevailingWind.sample(field.seed, level.getDayTime(), WindWeatherData.get(level.getServer()).weather(), WIND_NOW);
            windNowTick = now;
        }
        return WIND_NOW;
    }

    /** 浮遊だけ(G1 の作り直し 第 1 段: Mob の感染は空の下の浮遊 × 秒)。写しが無ければ NaN。サーバースレッドから。 */
    public static double airAt(int cx, int cz) {
        Readout r = READOUT.get(SporeField.key(cx >> SporeField.TB, cz >> SporeField.TB));
        return r == null ? Double.NaN : r.air[SporeField.idx(cx, cz)];
    }

    private static long[] watchTiles(ServerLevel level) {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet set = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (net.minecraft.server.level.ServerPlayer p : level.players()) {
            int tx = (p.getBlockX() >> 4) >> SporeField.TB, tz = (p.getBlockZ() >> 4) >> SporeField.TB;
            for (int dz = -WATCH_TILES; dz <= WATCH_TILES; dz++)
                for (int dx = -WATCH_TILES; dx <= WATCH_TILES; dx++) set.add(SporeField.key(tx + dx, tz + dz));
        }
        for (long d : WATCH_EXTRA) set.add(d);
        // 動いている換気機のまわり(下支えを下げた所を、場の値で読めるように。2026-11-14 段 2c)
        int vr = (int) Math.ceil(DesignData.sporeField().vent().radiusChunks()) + 1;
        for (long c : com.yayuy.overgrownvegetation.survival.vent.Ventilators.runningChunks(level)) {
            for (int dz = -vr; dz <= vr; dz += vr)
                for (int dx = -vr; dx <= vr; dx += vr)
                    set.add(SporeField.key((ChunkPos.getX(c) + dx) >> SporeField.TB, (ChunkPos.getZ(c) + dz) >> SporeField.TB));
        }
        return set.toLongArray();
    }

    /** テスト・強制読み込みの所など、プレイヤーがいなくても写したい区画。 */
    private static final it.unimi.dsi.fastutil.longs.LongArrayList WATCH_EXTRA = new it.unimi.dsi.fastutil.longs.LongArrayList();

    /** ポータルなど: このチャンクを含む区画をいつも写す(同じ区画は 1 回だけ、2026-10-23 F)。 */
    static void watch(int cx, int cz) {
        long k = SporeField.key(cx >> SporeField.TB, cz >> SporeField.TB);
        if (!WATCH_EXTRA.contains(k)) WATCH_EXTRA.add(k);
    }

    /** ポータル越しの染み出し: その升の浮遊 + 林床から amount を抜く(次の計算の頭で)。 */
    static void extractCell(int cx, int cz, double amount) {
        if (amount > 0) INPUTS.add(f -> f.extractAround(cx, cz, 0.0D, amount));
    }

    /** ポータル越しの染み出し: その升の浮遊へ amount を足す。 */
    static void emitCell(int cx, int cz, double amount) {
        if (amount > 0) INPUTS.add(f -> f.emitPoint(cx, cz, amount));
    }

    /** テスト用: このチャンクを含む区画も写す。 */
    public static void watchForTests(int cx, int cz) {
        WATCH_EXTRA.add(SporeField.key(cx >> SporeField.TB, cz >> SporeField.TB));
    }

    /** 裏のスレッド(鍵を持って): 見たい区画の写しを作る。 */
    private static it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Readout> readout(SporeField f, Inputs in) {
        return readoutOf(f, in.watchTiles, in.fromClock + (long) in.steps * in.stepTicks);
    }

    /** 区画の写しを作る(ネザーの場と共用、2026-10-23 F)。 */
    static it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Readout> readoutOf(SporeField f, long[] watchTiles, long now) {
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Readout> out = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        for (long key : watchTiles) {
            SporeField.Tile t = f.tiles.get(key);
            Readout r = new Readout();
            if (t != null) {
                for (int i = 0; i < SporeField.TN; i++) {
                    r.conc[i] = t.air[i] + t.low[i];
                    r.air[i] = t.air[i];
                    r.dep[i] = t.dep[i];
                    r.under[i] = t.under[i];
                    r.underDep[i] = t.underDep[i];
                    r.cave[i] = t.caveCount[i];
                    r.count[i] = t.count[i];
                    long sp = t.sprout[i];
                    if (sp == SporeField.NOT) {
                        r.age[i] = -1;
                        continue;
                    }
                    double age = (now - sp) / (double) PrevailingWind.DAY;
                    r.age[i] = (float) age;
                    r.stage[i] = (byte) (age < f.p.daysToOvergrown ? 1 : 2);
                }
            } else {
                java.util.Arrays.fill(r.age, -1f);
            }
            out.put(key, r);
        }
        return out;
    }

    // ---------------------------------------------------------------- 見る口(/ov perf spore)

    /** 今いるチャンクの場の値と風(コマンドの表示)。鍵が取れなければ「更新中」。 */
    public static List<String> describe(ServerLevel level, BlockPos pos) {
        List<String> out = new ArrayList<>();
        if (level.dimension() == Level.NETHER) {
            // ネザーの場(2026-10-23 F): 風は無い。写しの値だけ
            int ncx = pos.getX() >> 4, ncz = pos.getZ() >> 4;
            Cell c = NetherSporeFieldServer.cell(ncx, ncz);
            // 2026-11-11: 寒暖差の風。温度(0 冷たい〜1 熱い)を隣と並べる。風は冷たい升から熱い隣へ吹く
            NetherThermal th = NetherSporeFieldServer.CURRENT;
            out.add(th == null ? "ネザーの場(寒暖差の風。まだ温度を計算していない)"
                    : String.format("ネザーの場(寒暖差の風): 温度 ここ %.2f・東 %.2f・西 %.2f・南 %.2f・北 %.2f(風は冷たい方から熱い方へ)・森の段階 %d",
                    th.temperature(ncx, ncz), th.temperature(ncx + 1, ncz), th.temperature(ncx - 1, ncz),
                    th.temperature(ncx, ncz + 1), th.temperature(ncx, ncz - 1), th.stage(ncx, ncz)));
            out.add(c == null ? String.format("チャンク (%d, %d): 写しが無い", ncx, ncz)
                    : String.format("チャンク (%d, %d): 浮遊 + 林床 %.4f・浮遊 %.4f・降り積もり %.4f・段階 %d・萌芽体 %d", ncx, ncz, c.conc(),
                    NetherSporeFieldServer.airAt(ncx, ncz), c.dep(), c.stage(), c.count()));
            out.add("ここの濃度 " + String.format("%.0f%%", com.yayuy.overgrownvegetation.spore.SporeConcentration.percentAt(level, pos)));
            out.add("直近の計算: " + NetherSporeFieldServer.summary());
            return out;
        }
        if (field == null) {
            out.add("胞子の場はまだ動いていない(オーバーワールドで読み込み後に始まる)");
            return out;
        }
        MinecraftServer server = level.getServer();
        WindWeatherData wx = WindWeatherData.get(server);
        PrevailingWind.Sample s = new PrevailingWind.Sample();
        long dayTime = level.getDayTime();
        PrevailingWind.Weather w = wx.weather();
        PrevailingWind.sample(field.seed, dayTime, w, s);
        String kind = s.storm ? "嵐(雷雨)" : s.strongDay ? "風の強い日" : w.raining() ? "雨" : "晴れ・曇り";
        out.add(String.format("風: %s へ %.0f 度・強さ %.2f・%s", compass(s.angleDeg()), s.angleDeg(), s.strength, kind));
        out.add("ここの濃度 " + String.format("%.0f%%", com.yayuy.overgrownvegetation.spore.SporeConcentration.percentAt(level, pos)));
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        try {
            if (!LOCK.tryLock(500, TimeUnit.MILLISECONDS)) {
                out.add("場は更新中(もう一度)");
                return out;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return out;
        }
        try {
            long sp = field.sproutTick(cx, cz);
            out.add(String.format("チャンク (%d, %d): 浮遊 %.4f・林床 %.4f・降り積もり %.4f(発芽のしきい値 %.2f)", cx, cz,
                    field.air(cx, cz), field.low(cx, cz), field.deposited(cx, cz), field.p.germThreshold));
            out.add(String.format("地下: 浮遊 %.4f・降り積もり %.4f・洞窟の口 %.2f・洞窟の発芽 %d 回・今日の逆転層 y %.0f(地表 y %.0f)",
                    field.under(cx, cz), field.underDeposited(cx, cz), field.mouth(cx, cz), field.caveCount(cx, cz),
                    inversionNow(level), field.heightAt(cx, cz)));
            out.add(sp == SporeField.NOT ? "まだ発芽していない"
                    : String.format("発芽済み(萌芽体 %d 体・%.1f 日前)", field.sproutCount(cx, cz),
                    (PhaseData.get(level).clock(server) - sp) / (double) PrevailingWind.DAY));
            out.add(String.format("場の全体: 区画 %d・汚染されたチャンク %d・上空の塊 %d", field.tileCount(), field.corruptedCount, field.parcels.size()));
        } finally {
            LOCK.unlock();
        }
        out.add("直近の計算: " + lastSummary);
        return out;
    }

    private static String compass(double deg) {
        String[] n = {"東", "南東", "南", "南西", "西", "北西", "北", "北東"};
        return n[(int) Math.round(deg / 45.0) & 7];
    }

    // ---------------------------------------------------------------- テスト用

    /** テスト用: 場の値を鍵を持って読む・書く。 */
    public static <T> T withFieldForTests(java.util.function.Function<SporeField, T> fn) {
        LOCK.lock();
        try {
            return field == null ? null : fn.apply(field);
        } finally {
            LOCK.unlock();
        }
    }

    /** テスト用: 場の区画 (tx, tz) を保存の形にする(区画が無ければ null)。 */
    public static net.minecraft.nbt.CompoundTag tileTagForTests(SporeField f, int tx, int tz) {
        SporeField.Tile t = f.tiles.get(SporeField.key(tx, tz));
        return t == null ? null : SporeFieldStore.tileTag(t);
    }

    /** テスト用: 保存の形から区画 (tx, tz) を読み込む(保存係と同じ読み方。高さが無ければ土地から引く)。 */
    public static void loadTileForTests(SporeField f, int tx, int tz, net.minecraft.nbt.CompoundTag tag) {
        f.putLoadedTile(SporeFieldStore.readTile(f, tx, tz, tag));
    }

    public static long fieldClockForTests() {
        return fieldClock;
    }

    // ---------------------------------------------------------------- 土地

    /**
     * 升の土地をバイオームの元(世界生成のノイズ。チャンクを読み込まずに引ける)から決める(仮決め):
     * 海 = 発芽する・いつも湿っている・空気への放出 0.3 倍(水の下の株。深い海は沈むのに時間がかかるので発芽の速さ 0.7 倍。2026-10-24 追記 18、倍率は仮決め) /
     * 川・沼・マングローブ・海辺 = 昼でも湿る / 土地の倍率(湿った・川沿い ×3、乾いた ×0.15、高い山 ×0.08。2026-10-30)は {@link #terrainMul}。
     */
    public static final class BiomeGround implements SporeField.Ground {
        /** 深い海の発芽の速さ(沈むのに時間がかかる)と、海の升の空気への放出(水の下の株)。どちらも仮決め(2026-10-24)。 */
        static final float DEEP_OCEAN_GERM = 0.7f, OCEAN_AIR_EMIT = 0.3f;
        private final BiomeSource source;
        private final Climate.Sampler sampler;
        private final int qy;

        private final net.minecraft.world.level.chunk.ChunkGenerator generator;
        private final net.minecraft.world.level.levelgen.RandomState randomState;
        private final net.minecraft.world.level.LevelHeightAccessor heightAccessor;
        private final int seaLevel;
        private final SporeField.Params p;
        /** 生成器の式で引いた地表の高さ(heightStride おきの格子の点 → y)。場の鍵を持った所からだけ呼ぶ。 */
        private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap gridHeights = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

        BiomeGround(ServerLevel level, SporeField.Params p) {
            this.generator = level.getChunkSource().getGenerator();
            this.source = generator.getBiomeSource();
            this.randomState = level.getChunkSource().randomState();
            this.sampler = randomState.sampler();
            this.heightAccessor = net.minecraft.world.level.LevelHeightAccessor.create(level.getMinBuildHeight(), level.getHeight());
            this.seaLevel = generator.getSeaLevel();
            this.qy = seaLevel >> 2;
            this.p = p;
        }

        /**
         * 地表の高さ(2026-10-30 谷風・逆転層・山の壁)。生成器の式(getFirstOccupiedHeight。1 回 数 ms)を heightStride チャンクおきの格子で引き、
         * 間は双線形に補間する(作り直し側 LevelTerrain / SporeTerrain と同じ。チャンクを読み込まない・生成しない)。
         * 読み込まれたチャンクの本物の高さは、サーバースレッドで {@link SporeField#setExactHeight} へ入れる。
         */
        @Override
        public float height(int cx, int cz) {
            int s = Math.max(1, p.heightStride);
            int x0 = Math.floorDiv(cx, s) * s, z0 = Math.floorDiv(cz, s) * s;
            double fx = (cx - x0) / (double) s, fz = (cz - z0) / (double) s;
            double a = gridHeight(x0, z0), b = gridHeight(x0 + s, z0), c = gridHeight(x0, z0 + s), d = gridHeight(x0 + s, z0 + s);
            return (float) ((a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + d * fx) * fz);
        }

        private int gridHeight(int cx, int cz) {
            long k = ChunkPos.asLong(cx, cz);
            if (gridHeights.containsKey(k)) return gridHeights.get(k);
            int y = generator.getFirstOccupiedHeight((cx << 4) + 8, (cz << 4) + 8,
                    net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, heightAccessor, randomState);
            gridHeights.put(k, y);
            return y;
        }

        /**
         * 土地の倍率(2026-10-30、作り直し側 SporeTerrain#kindOf の幅): 川 → riverMul、海・海面より 2 下 → 1(水)、高さ ≥ mountainY → mountMul、
         * 降水量 ≥ wetDownfall → wetMul、≤ dryDownfall → dryMul、ほか 1。
         */
        @Override
        public float terrainMul(int cx, int cz, float height) {
            Holder<Biome> b = at(cx, cz);
            if (b.is(BiomeTags.IS_RIVER)) return (float) p.riverMul;
            if (b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_DEEP_OCEAN) || height < seaLevel - 2) return 1f;
            if (height >= p.mountainY) return (float) p.mountMul;
            float downfall = b.value().getModifiedClimateSettings().downfall();
            if (downfall >= p.wetDownfall) return (float) p.wetMul;
            if (downfall <= p.dryDownfall) return (float) p.dryMul;
            return 1f;
        }

        private Holder<Biome> at(int cx, int cz) {
            return source.getNoiseBiome((cx << 2) + 2, qy, (cz << 2) + 2, sampler);
        }

        @Override
        public float germFactor(int cx, int cz) {
            return germOf(at(cx, cz));
        }

        /** 2026-10-24 追記 18【決定】胞子は水にも沈んで芽吹く(前は海 = 発芽しない)。 */
        public static float germOf(Holder<Biome> b) {
            return b.is(BiomeTags.IS_DEEP_OCEAN) ? DEEP_OCEAN_GERM : 1f;
        }

        @Override
        public boolean wet(int cx, int cz) {
            return wetOf(at(cx, cz));
        }

        public static boolean wetOf(Holder<Biome> b) {
            return b.is(BiomeTags.IS_RIVER) || b.is(Tags.Biomes.IS_SWAMP) || b.is(BiomeTags.IS_BEACH) || b.is(Biomes.MANGROVE_SWAMP)
                    || b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_DEEP_OCEAN);
        }

        @Override
        public float airEmitFactor(int cx, int cz) {
            return airEmitOf(at(cx, cz));
        }

        public static float airEmitOf(Holder<Biome> b) {
            return b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_DEEP_OCEAN) ? OCEAN_AIR_EMIT : 1f;
        }

        // 林床の広がりやすさの元(spreadFactor)は 1。土地の差は terrainMul が発芽と林床の両方に掛ける(2026-10-30。前はここで
        // 川・湿地・密林 ×2、砂漠・荒野・サバンナ ×0.3、山のタグ ×0.5)
    }
}
