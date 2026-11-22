package com.yayuy.overgrownvegetation.ecosystem.map;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.LedgerSpread;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.difficulty.DifficultySetting;
import com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData;
import com.yayuy.overgrownvegetation.ecosystem.EcoTable;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spore.field.GermMul;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.server.ServerStoppedEvent;
import com.yayuy.overgrownvegetation.port.event.tick.LevelTickEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <b>地図の上の生態系で汚染が広がる</b>(2026-11-21 ユーザー決定。正本は Vault「前線と部隊(地図の上の森の軍勢)(11-21)」の「地図の上の生態系で広がる」)。
 *
 * <ul>
 *   <li>升(生態系の台帳と同じ 4×4 チャンク)ごとに、バニラの動物と村人の頭数を持つ({@link MapLedger}・満員は {@link MapCapacity})。</li>
 *   <li>広げる部隊が升に居ると感染が進む({@link #infect})。感染 1 回ごとに森の稼ぎ(司令塔の {@code EconomyTable#infectGain}。足すのは呼ぶ側)と、
 *   G1 として部隊に加わる数(種類ごと)を返す。</li>
 *   <li>感染の割合が上がるほど、その升の胞子が芽吹きやすい(発芽のしきい値 × (1 − (1 − germMinMul) × 割合)。{@link GermMul} で胞子の場へ)。</li>
 *   <li><b>健康な生き物がいなくなったら</b>(全部感染した)その升の 16 チャンクを過繁茂の印にする({@link LedgerSpread#acceptFieldSprouts}
 *   = 胞子の発芽と同じ道。読み込まれたら地形が変わる)。断られたチャンク(換気機・焼けた荒地など)は時間をおいて試し直す。</li>
 *   <li>生き物の少ない升(満員 &lt; barrenBelow。砂漠・海・高山など)はこの道を使わない(胞子だけで広がる)。</li>
 *   <li>実物との照らし合わせは {@link MapSync}。</li>
 * </ul>
 * どれもサーバースレッド・オーバーワールドだけ。前線と部隊(director・horde)はここの口だけを呼ぶ。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class MapEcology {

    private MapEcology() {
    }

    // ---------------------------------------------------------------- 口(前線と部隊へ)

    /** 感染を進めた結果。byType は感染した種類ごとの数(並びは決まる)。becameOvergrown = この呼び出しで過繁茂の印を付けた。 */
    public record InfectResult(int infected, double income, Map<EntityType<?>, Integer> byType, boolean becameOvergrown) {
        public static final InfectResult NONE = new InfectResult(0, 0.0D, Map.of(), false);

        /** そのうちの村人の数。 */
        public int villagers() {
            return byType.getOrDefault(EntityType.VILLAGER, 0);
        }
    }

    /** 升の様子。ratio = 感染 ÷ 満員。barren = この道を使わない升。overgrown = 過繁茂の印を付けた(か、胞子で 16 チャンクとも過繁茂)。 */
    public record CellView(int full, int animals, int villagers, double healthy, double infected, double ratio, boolean barren, boolean overgrown, float germMul) {
        /** 前線と部隊の呼び方: 頭数(健康な数)・生き物の少ない升。 */
        public int heads() {
            return (int) Math.floor(healthy + 1.0E-9D);
        }

        public boolean sparse() {
            return barren;
        }
    }

    /** 升が過繁茂になった知らせ。 */
    @FunctionalInterface
    public interface OvergrownListener {
        void onCellOvergrown(ServerLevel level, long cellKey);
    }

    private static final List<OvergrownListener> LISTENERS = new CopyOnWriteArrayList<>();

    public static void addOvergrownListener(OvergrownListener l) {
        LISTENERS.add(l);
    }

    public static void removeOvergrownListener(OvergrownListener l) {
        LISTENERS.remove(l);
    }

    /** チャンクの升のキー。 */
    public static long cellOf(int chunkX, int chunkZ) {
        int s = shift();
        return ChunkPos.asLong(chunkX >> s, chunkZ >> s);
    }

    /** 升の真ん中(高さ 64)。 */
    public static BlockPos cellCenter(long cellKey) {
        int s = shift(), half = 8 << s;
        return new BlockPos((ChunkPos.getX(cellKey) << (s + 4)) + half, 64, (ChunkPos.getZ(cellKey) << (s + 4)) + half);
    }

    /**
     * 広げる部隊({@code unitSize} 体)が升に {@code ticks} だけ居た: 感染を進める。端数は升に持ち越すので、毎 tick でもまとめてでも呼べる。
     * 切ってある(設計値・MOD の設定「動物や村人の感染」・ピースフル)・生き物の少ない升・健康な生き物がいない升では何もしない。
     */
    public static InfectResult infect(ServerLevel level, long cellKey, int unitSize, long ticks) {
        level = level.getServer().overworld();
        if (unitSize <= 0 || ticks <= 0 || !active(level)) return InfectResult.NONE;
        MapTable t = table();
        double amount = unitSize * (ticks / 20.0D) * t.infectPerMobPerSecond;
        return addInfected(level, cellKey, amount, false);
    }

    /** {@link #infect(ServerLevel, long, int, long)} の升の座標・秒の版(前線と部隊の呼び方)。村人の数は {@link InfectResult#villagers()}。 */
    public static InfectResult infect(ServerLevel level, int cellX, int cellZ, int attackers, double seconds) {
        return infect(level, ChunkPos.asLong(cellX, cellZ), attackers, Math.round(seconds * 20.0D));
    }

    /** {@link #view(ServerLevel, long)} の升の座標の版。満員の計算は升ごとに 1 回だけ(写しを持つ)。 */
    public static CellView view(ServerLevel level, int cellX, int cellZ) {
        return view(level, ChunkPos.asLong(cellX, cellZ));
    }

    /** 升が過繁茂になった知らせ(升のキー = ChunkPos.asLong(cellX, cellZ))。 */
    public static void onOvergrown(java.util.function.LongConsumer listener) {
        addOvergrownListener((lv, k) -> listener.accept(k));
    }

    /** 升の頭数と感染の割合。 */
    public static CellView view(ServerLevel level, long cellKey) {
        level = level.getServer().overworld();
        MapTable t = table();
        MapLedger ledger = MapLedger.get(level.getServer());
        MapLedger.Cell c = ledger.get(cellKey);
        long now = now(level);
        if (c == null) {
            MapCapacity.Info info = info(level, cellKey);
            boolean barren = info.full() < t.barrenBelow;
            return new CellView(info.full(), info.animals(), info.villagers(), info.full(), 0.0D, 0.0D, barren, allChunksOvergrown(level, cellKey), 1f);
        }
        boolean barren = c.full() < t.barrenBelow;
        double r = MapLedger.ratio(c);
        return new CellView(c.full(), c.animals, c.villagers, MapLedger.healthy(c, now, recoveryTicks(t)), c.infected, r, barren,
                c.state != MapLedger.NONE || allChunksOvergrown(level, cellKey), barren ? 1f : germMul(r, t));
    }

    // ---------------------------------------------------------------- 中身

    static MapTable table() {
        return DesignData.ecosystem().map;
    }

    static int shift() {
        return DesignData.ecosystem().cellShift;
    }

    static long now(ServerLevel level) {
        return PhaseData.clockNow(level);
    }

    static double recoveryTicks(MapTable t) {
        return t.lackRecoveryDays * 24000.0D;
    }

    /** 発芽のしきい値の倍率。 */
    public static float germMul(double ratio, MapTable t) {
        return (float) (1.0D - (1.0D - t.germMinMul) * Math.max(0.0D, Math.min(1.0D, ratio)));
    }

    /** 動いているか(設計値・MOD の設定・ピースフル)。 */
    public static boolean active(ServerLevel level) {
        EcoTable e = DesignData.ecosystem();
        if (!e.enabled || !e.map.enabled || level.getDifficulty() == Difficulty.PEACEFUL) return false;
        return WorldDifficultyData.on(level.getServer(), DifficultySetting.MOB_INFECTION);
    }

    /** 満員の写し(升のキー → 中身。読んだ升だけ。保存しない)。 */
    private static final Long2ObjectOpenHashMap<MapCapacity.Info> INFO = new Long2ObjectOpenHashMap<>();

    static MapCapacity.Info info(ServerLevel level, long cellKey) {
        MapCapacity.Info i = INFO.get(cellKey);
        if (i == null) {
            if (INFO.size() > 200_000) INFO.clear();
            i = MapCapacity.compute(level, cellKey, shift(), table());
            INFO.put(cellKey, i);
        }
        return i;
    }

    /** 升の中身(満員は台帳にあればそちら、種類の混ぜ方はバイオームから)。 */
    static MapCapacity.Mix mix(ServerLevel level, long cellKey) {
        return info(level, cellKey).mix();
    }

    /**
     * 感染を {@code amount} 足す(地図の上の部隊・実物の感染の共通の道)。{@code real} = 実物がもう感染した(照らし合わせ済みの数も足す)。
     */
    static InfectResult addInfected(ServerLevel level, long cellKey, double amount, boolean real) {
        MapTable t = table();
        MapLedger ledger = MapLedger.get(level.getServer());
        MapLedger.Cell c = ledger.get(cellKey);
        MapCapacity.Info info = null;
        if (c == null) {
            info = info(level, cellKey);
            if (info.full() < t.barrenBelow) return InfectResult.NONE;
        } else if (c.full() < t.barrenBelow) {
            return InfectResult.NONE;
        }
        long now = now(level);
        double rt = recoveryTicks(t);
        if (c == null) c = ledger.getOrCreate(cellKey, info);
        double healthy = MapLedger.healthy(c, now, rt);
        if (healthy <= 0.0D) {
            checkOvergrown(level, cellKey, c, now);
            return InfectResult.NONE;
        }
        double add = Math.min(healthy, amount);
        double before = c.infected;
        c.infected = before + add;
        // 端数の誤差で 0.9999… に留まらないよう、残りがほんの少しなら食べきる
        if (MapLedger.healthy(c, now, rt) < 1.0E-6D) c.infected = c.full() - MapLedger.lackNow(c, now, rt);
        int whole = (int) Math.floor(c.infected + 1.0E-9D) - (int) Math.floor(before + 1.0E-9D);
        if (real) c.synced += whole;
        ledger.markDirty();
        dirtyGerm = true;
        Map<EntityType<?>, Integer> byType = Map.of();
        double income = 0.0D;
        if (whole > 0) {
            byType = split(level, cellKey, c, (int) Math.floor(before + 1.0E-9D), whole);
            if (!real) income = income(byType);
            if (real) ledger.infectedReal += whole;
            else ledger.infectedMap += whole;
        }
        boolean became = checkOvergrown(level, cellKey, c, now);
        return new InfectResult(whole, income, byType, became);
    }

    /** n 番目から count 頭の種類(升と番号の乱数で決まる。村人は村人の割合で)。 */
    static Map<EntityType<?>, Integer> split(ServerLevel level, long cellKey, MapLedger.Cell c, int from, int count) {
        MapCapacity.Mix mix = mix(level, cellKey);
        Map<EntityType<?>, Integer> out = new LinkedHashMap<>();
        double vShare = c.full() <= 0 ? 0.0D : c.villagers / (double) c.full();
        for (int k = 0; k < count; k++) {
            long h = mixHash(cellKey * 31L + from + k);
            double u = (h >>> 11) * 0x1.0p-53;
            EntityType<?> type;
            if (u < vShare) {
                type = EntityType.VILLAGER;
            } else {
                double u2 = (mixHash(h) >>> 11) * 0x1.0p-53;
                type = mix.types[mix.pick(u2)];
            }
            out.merge(type, 1, Integer::sum);
        }
        return Collections.unmodifiableMap(out);
    }

    /** 森の稼ぎ(1 頭ごとに司令塔の infectGain(元の最大体力))。 */
    static double income(Map<EntityType<?>, Integer> byType) {
        double sum = 0.0D;
        for (Map.Entry<EntityType<?>, Integer> e : byType.entrySet()) {
            sum += com.yayuy.overgrownvegetation.director.ForestEconomy.table().infectGain(MapCapacity.maxHealth(e.getKey())) * e.getValue();
        }
        return sum;
    }

    private static long mixHash(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /** 升の 16 チャンクがもう全部過繁茂か(胞子で先に覆われた)。 */
    static boolean allChunksOvergrown(ServerLevel level, long cellKey) {
        PhaseData phase = PhaseData.get(level);
        int s = shift(), n = 1 << s, bx = ChunkPos.getX(cellKey) << s, bz = ChunkPos.getZ(cellKey) << s;
        for (int dx = 0; dx < n; dx++)
            for (int dz = 0; dz < n; dz++)
                if (!phase.isOvergrownKey(ChunkPos.asLong(bx + dx, bz + dz))) return false;
        return true;
    }

    /** 試し直しを待つ升 → 次に試す時刻(実行時だけ。再起動したら台帳の PARTIAL から作り直す)。 */
    private static final Long2LongOpenHashMap RETRY = new Long2LongOpenHashMap();
    private static boolean retryLoaded;

    /**
     * 健康な生き物がいなくて感染が 1 頭以上なら、升の 16 チャンクを過繁茂の印にする。初めて 1 チャンクでも受け取られたら知らせる。
     *
     * @return この呼び出しで初めて過繁茂になった
     */
    static boolean checkOvergrown(ServerLevel level, long cellKey, MapLedger.Cell c, long now) {
        if (c.state == MapLedger.DONE || c.infected < 1.0D || c.full() < table().barrenBelow) return false;
        if (MapLedger.healthy(c, now, recoveryTicks(table())) >= 0.5D) return false;
        return mark(level, cellKey, c);
    }

    private static boolean mark(ServerLevel level, long cellKey, MapLedger.Cell c) {
        PhaseData phase = PhaseData.get(level);
        int s = shift(), n = 1 << s, bx = ChunkPos.getX(cellKey) << s, bz = ChunkPos.getZ(cellKey) << s;
        it.unimi.dsi.fastutil.longs.LongArrayList keys = new it.unimi.dsi.fastutil.longs.LongArrayList(n * n);
        for (int dx = 0; dx < n; dx++)
            for (int dz = 0; dz < n; dz++) {
                long k = ChunkPos.asLong(bx + dx, bz + dz);
                if (!phase.isOvergrownKey(k)) keys.add(k);
            }
        long[] rejected = keys.isEmpty() ? new long[0] : LedgerSpread.acceptFieldSprouts(level, keys.toLongArray());
        boolean accepted = keys.size() > rejected.length || keys.isEmpty();
        boolean first = c.state == MapLedger.NONE && accepted;
        MapLedger ledger = MapLedger.get(level.getServer());
        if (rejected.length == 0) {
            c.state = MapLedger.DONE;
            RETRY.remove(cellKey);
        } else {
            if (accepted) c.state = MapLedger.PARTIAL;
            RETRY.put(cellKey, level.getGameTime() + table().retryOvergrownTicks);
        }
        ledger.markDirty();
        dirtyGerm = true;
        if (first) {
            ledger.overgrownCells++;
            for (OvergrownListener l : LISTENERS) {
                try {
                    l.onCellOvergrown(level, cellKey);
                } catch (RuntimeException e) {
                    OvergrownVegetation.LOGGER.error("地図の上の生態系: 過繁茂の知らせで例外", e);
                }
            }
        }
        return first;
    }

    // ---------------------------------------------------------------- 毎 tick(胞子の場へ渡す・試し直す)

    private static boolean dirtyGerm = true;
    private static GermMul published;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        MapTable t = table();
        long gt = level.getGameTime();
        if (gt % t.publishIntervalTicks == 0) {
            if (!DesignData.ecosystem().enabled || !t.enabled) {
                if (published != null) {
                    published = null;
                    GermMul.publish(null);
                }
            } else if (dirtyGerm) {
                publishGerm(level);
            }
        }
        if (gt % 200 == 7) retry(level, gt);
        MapSync.tick(level, t);
    }

    /** 感染の割合の倍率を作って胞子の場へ渡す(変わったときだけ)。 */
    public static GermMul publishGerm(ServerLevel level) {
        dirtyGerm = false;
        MapTable t = table();
        MapLedger ledger = MapLedger.get(level.getServer());
        Long2FloatOpenHashMap m = new Long2FloatOpenHashMap();
        long now = now(level);
        double rt = recoveryTicks(t);
        java.util.List<Long> idle = new java.util.ArrayList<>();
        for (Long2ObjectMap.Entry<MapLedger.Cell> e : ledger.entries()) {
            MapLedger.Cell c = e.getValue();
            if (c.full() < t.barrenBelow || c.state == MapLedger.DONE) continue;
            if (c.infected > 0.0D) m.put(e.getLongKey(), germMul(MapLedger.ratio(c), t));
            else if (c.state == MapLedger.NONE && c.synced == 0 && MapLedger.lackNow(c, now, rt) <= 0.0D) idle.add(e.getLongKey());
        }
        for (long k : idle) {
            MapLedger.Cell c = ledger.get(k);
            if (c != null) ledger.dropIfIdle(k, c, now, rt);
        }
        GermMul g = new GermMul(m, shift());
        published = g;
        GermMul.publish(g);
        return g;
    }

    private static void retry(ServerLevel level, long gt) {
        MapLedger ledger = MapLedger.get(level.getServer());
        if (!retryLoaded) {
            retryLoaded = true;
            for (Long2ObjectMap.Entry<MapLedger.Cell> e : ledger.entries()) {
                MapLedger.Cell c = e.getValue();
                // 印を断られたまま止まった升(PARTIAL)と、全部感染したのに印が付いていない升(NONE)を試し直す
                if (c.state == MapLedger.PARTIAL || (c.state == MapLedger.NONE && c.infected >= 1.0D)) RETRY.put(e.getLongKey(), gt);
            }
        }
        if (RETRY.isEmpty()) return;
        long now = now(level);
        long[] due = RETRY.long2LongEntrySet().stream().filter(e -> e.getLongValue() <= gt).mapToLong(Long2LongOpenHashMap.Entry::getLongKey).limit(64).toArray();
        for (long k : due) {
            RETRY.remove(k);
            MapLedger.Cell c = ledger.get(k);
            if (c == null || c.state == MapLedger.DONE) continue;
            if (c.state == MapLedger.PARTIAL) mark(level, k, c);
            else checkOvergrown(level, k, c, now);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        INFO.clear();
        RETRY.clear();
        retryLoaded = false;
        dirtyGerm = true;
        published = null;
        MapSync.clear();
    }

    /** 確かめ用: 実行時の写しを捨てる(満員の写し・試し直し)。 */
    public static void clearCachesForTests() {
        INFO.clear();
        RETRY.clear();
        dirtyGerm = true;
    }

    /** 渡した倍率の数(表示用)。 */
    public static int publishedCells() {
        GermMul g = published;
        return g == null ? 0 : g.size();
    }
}
