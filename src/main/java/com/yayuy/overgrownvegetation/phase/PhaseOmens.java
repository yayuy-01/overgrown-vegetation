package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.sky.CloudWeather;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.List;

/**
 * フェーズが上がったときの、フェーズごとの印のうちサーバーの仕組みが要るもの(2026-11-16 enemy-spawns)。正本は Vault「フェーズの仕組み(ポイント・日数)」の
 * 「フェーズが上がったときの現象」と「フェーズが上がるときの合図(実装・11-16)」。共通の合図と F6 は {@link PhaseCues}(シェーダーのセッション)。
 * <ul>
 *   <li><b>F2 汚染域で一斉に芽吹く</b>: 上がった時に、プレイヤーのまわり({@link #SPROUT_RANGE_CHUNKS} チャンク)の読み込まれた過繁茂のチャンクを並べ、
 *   1 tick に {@link #SPROUT_CHUNKS_PER_TICK} チャンクずつ、{@link #SPROUT_COLUMNS} 列の地面で下草の発芽({@link CorruptionSpreader#growSurfacePlants})を 1 回まとめて走らせる。
 *   胞子の粉が少し舞う(仮にバニラの SPORE_BLOSSOM_AIR)。</li>
 *   <li><b>F4 上がった直後に、短く弱い胞子の嵐が 1 回だけ</b>: 雷雨(= 胞子の嵐)を {@link CloudWeather#forceStorm} で 1 回差し込む。
 *   少し後に始まり、雨 {@link #STORM_RAIN_TICKS}・そのうち雷 {@link #STORM_THUNDER_TICKS}、雷の間の風は嵐の {@link #STORM_SCALE} 倍(弱い)。</li>
 * </ul>
 * 上がったときだけ(下げた・同じでは起きない)。一度に何段か上がったとき(コマンド)は、通ったフェーズの印を出す。数はどれも【仮】。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PhaseOmens {

    public static final int SPROUT_RANGE_CHUNKS = 8;
    public static final int SPROUT_CHUNKS_PER_TICK = 2;
    public static final int SPROUT_COLUMNS = 24;
    public static final double SPROUT_CHANCE = 0.6D;
    /** F4 の嵐: 上がってから始まるまで・雨の長さ・雷の始まり(雨の始まりから)・雷の長さ(tick)・風の倍率。 */
    public static final int STORM_DELAY_TICKS = 200, STORM_RAIN_TICKS = 3600, STORM_THUNDER_FROM = 600, STORM_THUNDER_TICKS = 2400;
    public static final double STORM_SCALE = 0.5D;

    private static final LongArrayFIFOQueue SPROUT_QUEUE = new LongArrayFIFOQueue();

    /** テスト用: 芽吹きの真ん中を決め打ちにする(GameTest にはプレイヤーがいない)。null で戻す。 */
    @Nullable
    private static List<BlockPos> centersOverride;

    /** 確かめ用: 最後の芽吹きで並べたチャンクの数と、これまでに芽吹かせたチャンクの数。 */
    public static int lastSproutQueued;
    public static long sproutedChunks;

    private PhaseOmens() {
    }

    @SubscribeEvent
    public static void onPhaseChanged(PhaseEvents.PhaseChanged event) {
        if (!event.advanced()) return;
        ServerLevel level = event.overworld();
        if (crossed(event.oldPhase(), event.newPhase(), 2)) queueSprouts(level);
        if (crossed(event.oldPhase(), event.newPhase(), 4)) forceStorm(level);
    }

    /** old から new へ上がるときに、フェーズ k を通ったか(k に入った)。 */
    public static boolean crossed(int oldPhase, int newPhase, int k) {
        return oldPhase < k && newPhase >= k;
    }

    // ---------------------------------------------------------------- F2 芽吹き

    /** プレイヤーのまわりの読み込まれた過繁茂のチャンクを並べる。並べた数を返す。 */
    public static int queueSprouts(ServerLevel level) {
        PhaseData data = PhaseData.get(level);
        LongOpenHashSet seen = new LongOpenHashSet();
        List<BlockPos> centers = centersOverride;
        if (centers == null) {
            centers = new java.util.ArrayList<>();
            for (ServerPlayer p : level.players()) centers.add(p.blockPosition());
        }
        int r = SPROUT_RANGE_CHUNKS;
        int n = 0;
        for (BlockPos c : centers) {
            int pcx = c.getX() >> 4, pcz = c.getZ() >> 4;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    long key = ChunkPos.asLong(pcx + dx, pcz + dz);
                    if (!seen.add(key) || !data.isOvergrownKey(key)) continue;
                    if (!level.hasChunk(pcx + dx, pcz + dz)) continue;
                    SPROUT_QUEUE.enqueue(key);
                    n++;
                }
            }
        }
        lastSproutQueued = n;
        return n;
    }

    public static int queuedSprouts() {
        return SPROUT_QUEUE.size();
    }

    public static void setCentersForTests(@Nullable List<BlockPos> centers) {
        centersOverride = centers;
    }

    public static void clearForTests() {
        SPROUT_QUEUE.clear();
    }

    /** 止めたら芽吹きの待ち行列を空にする(次のワールドの同じチャンク番号を芽吹かせない。11-18 見直し ⑤7)。 */
    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        SPROUT_QUEUE.clear();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || SPROUT_QUEUE.isEmpty()) return;
        step(level, SPROUT_CHUNKS_PER_TICK);
    }

    /** 並べたチャンクを n 個芽吹かせる(GameTest からも呼ぶ)。芽吹かせた数を返す。 */
    public static int step(ServerLevel level, int n) {
        RandomSource random = level.getRandom();
        int done = 0;
        int tries = SPROUT_QUEUE.size(); // 積み直した分を同じ回に繰り返さない
        while (done < n && tries-- > 0 && !SPROUT_QUEUE.isEmpty()) {
            long key = SPROUT_QUEUE.dequeueLong();
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            // 読み込み・生成の途中(自分と隣)なら後ろへ積み直す。読み込まれていなければ捨てる(11-18 見直し ③4。hasChunk は生成中でも true で、
            // 芽吹かせると主スレッドが生成を待っていた)
            var edge = com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.edge(level, new BlockPos((cx << 4) + 8, level.getMinBuildHeight(), (cz << 4) + 8), 9);
            if (edge == com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.Edge.WAIT) {
                SPROUT_QUEUE.enqueue(key);
                continue;
            }
            if (edge != com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.Edge.READY) continue;
            sproutChunk(level, cx, cz, random);
            done++;
            sproutedChunks++;
        }
        return done;
    }

    private static void sproutChunk(ServerLevel level, int cx, int cz, RandomSource random) {
        for (int i = 0; i < SPROUT_COLUMNS; i++) {
            int x = (cx << 4) + random.nextInt(16), z = (cz << 4) + random.nextInt(16);
            BlockPos feet = TreeCanopyGroundFinder.findSpawnSurface(level, x, z);
            if (feet == null) continue;
            BlockPos host = feet.below();
            BlockState hostState = level.getBlockState(host);
            CorruptionSpreader.growSurfacePlants(level, host, hostState, SPROUT_CHANCE);
            if (random.nextInt(3) == 0) {
                // 芽吹きの粉【仮】: 見た目はシェーダーのセッションが詰めるときにここを差し替える(候補 ModParticles の SPORE_FLUFF・SPORE_BURST_CLOUD。11-16)
                level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, feet.getX() + 0.5D, feet.getY() + 0.3D, feet.getZ() + 0.5D, 3, 0.4D, 0.3D, 0.4D, 0.0D);
            }
        }
    }

    // ---------------------------------------------------------------- F4 弱い嵐

    /** 短く弱い胞子の嵐を 1 回差し込む。天気の予定が止まっている時(雲の雨の合わせが切・doWeatherCycle が切)は、バニラの天気で降らせる。 */
    public static void forceStorm(ServerLevel level) {
        long from = level.getDayTime() + STORM_DELAY_TICKS;
        CloudWeather.forceStorm(from, STORM_RAIN_TICKS, STORM_THUNDER_FROM, STORM_THUNDER_FROM + STORM_THUNDER_TICKS, STORM_SCALE);
        if (!CloudWeather.serverActive()) {
            // 予定で天気を決めていない: バニラの天気をそのまま(遅れなし・弱めの風は雷の間だけ CloudWeather が書けないので掛からない)
            level.setWeatherParameters(0, STORM_RAIN_TICKS, true, true);
        }
    }
}
