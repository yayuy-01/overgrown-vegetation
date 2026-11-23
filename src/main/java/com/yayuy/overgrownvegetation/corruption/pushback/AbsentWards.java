package com.yayuy.overgrownvegetation.corruption.pushback;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.survival.vent.AirFilters;
import com.yayuy.overgrownvegetation.survival.vent.VentBlockEntity;
import com.yayuy.overgrownvegetation.survival.vent.VentStock;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>留守の間も守る杭・換気機の覚え</b>(11-23 夕方 ユーザー決定。Vault「サバイバルの背骨(籠城と遠征)」の末尾)。次元ごとの保存データ。
 *
 * <ul>
 *   <li>置かれている杭・換気機は 1 台ずつ覚える(場所・FE・いつの FE か・最後に動いていた tick・使う FE/t・受け取っていた FE/t・換気機はフィルター)。
 *   読み込まれて動いている間は機械が {@code MIRROR_TICKS} ごとに(フィルターは変わるたびに)書き写す。</li>
 *   <li><b>留守</b> = チャンクが外れた(onChunkUnloaded)か、機械が {@link #AWAY_AFTER_TICKS} tick 動いていない(読み込まれていても回らないチャンク・
 *   サーバーを開き直した直後)。留守の機械は、この覚えで範囲を守る(杭は {@link RingWards}、換気機は {@link VentGuard} と胞子の場)。</li>
 *   <li>FE は経った時間で減らす: 残り = 覚えた FE − 使う FE/t × 経った tick。ただし「足りていた」機械(網・隣に発電機があり、最後の窓でためた FE が減らなかった。{@link AwayMeter})は減らない。
 *   0 になったらそこで守りが切れる。換気機のフィルターは留守の間も胞子の場の計算で詰まり({@link #applyAway})、予備を順に使う。</li>
 *   <li><b>精算</b>(機械が読み込まれて最初に回ったとき {@link #settle}): FE は「機械の値」と「覚えから出した今の値」の小さい方にし、
 *   換気機のフィルターは留守の間に変わっていれば({@code revision})覚えの物で置き換える。精算の後はすぐ書き写すので、
 *   読み込み直し・保存して開き直しで二重に減らない(引き算でなく、今の値に揃える)。</li>
 *   <li>壊した(別のブロックになった)ら忘れる({@link #forget}。{@code setRemoved} はチャンクが外れたときも呼ばれるので使わない)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class AbsentWards extends SavedData {

    public static final String ID = "overgrown_vegetation_absent_wards";
    /** これだけ動いていない機械は留守とみなす(tick)。 */
    public static final int AWAY_AFTER_TICKS = 40;
    /** 読み込まれている機械が覚えへ書き写す間(tick)。 */
    public static final int MIRROR_TICKS = 20;

    /** 1 台の覚え。 */
    public static final class Machine {
        public final long pos;
        public final boolean vent;
        /** {@link #asOf} の時点の FE。 */
        public int energy;
        public long asOf;
        /** 最後に機械が動いて書き写した tick。 */
        public long lastSeen;
        /** 使う FE/t(杭: 立っているだけの分 + 止めた分の平均。換気機: 動いている間の分)。 */
        public double drainPerTick;
        /** 受け取っていた FE/t(最後の窓)。 */
        public double inflowPerTick;
        /** 網・隣に発電機(汲み上げ器など)があったか。無ければ受け取りを数えない(柱の電池だけなどは留守の間に入り続けない)。 */
        public boolean producerLinked;
        /** 足りていたか(発電機があり、最後の窓でためた FE が減らなかった。{@link AwayMeter#supplied})。足りていた機械は留守の間も FE が減らない。 */
        public boolean supplied;
        /** 換気機のフィルター(杭は null)。 */
        @Nullable
        public VentStock stock;
        /** 留守の間に覚えのフィルターが変わった回数(精算で機械と比べる)。 */
        public int revision;
        /** フィルターの詰まりの端数(換気機)。 */
        public double loadCarry;
        /** 留守か(保存しない。読み込んだ直後は留守)。 */
        public boolean away = true;

        Machine(long pos, boolean vent) {
            this.pos = pos;
            this.vent = vent;
            if (vent) stock = new VentStock();
        }

        public BlockPos blockPos() {
            return BlockPos.of(pos);
        }

        /** 留守の間に減る FE/t(換気機はフィルターが使えないと回らないので 0)。 */
        public double netDrain() {
            if (supplied) return 0.0D;
            return vent && (stock == null || !stock.usable()) ? 0.0D : drainPerTick;
        }

        /** 今の FE(覚えから出した値)。 */
        public int energyAt(long now) {
            double left = energy - netDrain() * Math.max(0L, now - asOf);
            return (int) Math.max(0.0D, Math.floor(left + 1.0E-9D));
        }

        public boolean isAway(long now) {
            return away || now - lastSeen > AWAY_AFTER_TICKS;
        }

        /** 留守の間も守っているか(杭: FE が残る。換気機: 詰まっていないフィルターがあり、1 tick 分の FE が残る)。 */
        public boolean guarding(long now) {
            if (!vent) return supplied || energyAt(now) > 0;
            return stock != null && stock.usable() && (supplied || energyAt(now) >= Math.max(1, VentBlockEntity.feNeeded()));
        }

        /** FE を今に揃える(使う量が変わる前に)。 */
        void advance(long now) {
            energy = energyAt(now);
            asOf = now;
        }

        /** FE が切れる tick(切れないなら Long.MAX_VALUE)。 */
        public long expiry() {
            double d = netDrain();
            if (d <= 0.0D) return Long.MAX_VALUE;
            return asOf + (long) Math.ceil(energy / d);
        }
    }

    final Long2ObjectOpenHashMap<Machine> stakes = new Long2ObjectOpenHashMap<>();
    final Long2ObjectOpenHashMap<Machine> vents = new Long2ObjectOpenHashMap<>();
    /** 中身が変わった回数(保存しない。RingWards の作り直しの合図)。 */
    int version;

    public static final SavedData.Factory<AbsentWards> FACTORY = new SavedData.Factory<>(AbsentWards::new, AbsentWards::load, null);
    private static final Map<ResourceKey<Level>, AbsentWards> CACHE = new HashMap<>();

    public static AbsentWards get(ServerLevel level) {
        AbsentWards w = CACHE.get(level.dimension());
        if (w == null) {
            w = level.getDataStorage().computeIfAbsent(FACTORY, ID);
            CACHE.put(level.dimension(), w);
        }
        return w;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CACHE.clear();
    }

    // ---------------------------------------------------------------- 機械から

    /** 覚え(無ければ作る。機械が読み込まれて最初に回ったとき)。 */
    public static Machine touch(ServerLevel level, BlockPos pos, boolean vent) {
        AbsentWards w = get(level);
        Long2ObjectOpenHashMap<Machine> map = vent ? w.vents : w.stakes;
        Machine m = map.get(pos.asLong());
        if (m == null) {
            m = new Machine(pos.asLong(), vent);
            long now = level.getGameTime();
            m.asOf = now;
            m.lastSeen = now;
            m.away = false;
            map.put(pos.asLong(), m);
            w.version++;
            w.setDirty();
        }
        return m;
    }

    @Nullable
    public static Machine find(ServerLevel level, BlockPos pos, boolean vent) {
        AbsentWards w = get(level);
        return (vent ? w.vents : w.stakes).get(pos.asLong());
    }

    /** 機械が今の状態を書き写した(読み込まれて動いている)。 */
    public static void mirrored(ServerLevel level, Machine m) {
        AbsentWards w = get(level);
        long now = level.getGameTime();
        boolean wasAway = m.isAway(now);
        m.asOf = now;
        m.lastSeen = now;
        m.away = false;
        if (wasAway) w.version++;
        w.setDirty();
    }

    /** チャンクが外れた: この時から留守。 */
    public static void markAway(ServerLevel level, BlockPos pos, boolean vent) {
        Machine m = find(level, pos, vent);
        if (m == null || m.away) return;
        m.away = true;
        AbsentWards w = get(level);
        w.version++;
        w.setDirty();
    }

    /** 壊した(別のブロックになった)。 */
    public static void forget(ServerLevel level, BlockPos pos, boolean vent) {
        AbsentWards w = get(level);
        if ((vent ? w.vents : w.stakes).remove(pos.asLong()) != null) {
            w.version++;
            w.setDirty();
        }
    }

    // ---------------------------------------------------------------- 問い(留守の機械)

    /** 留守で、まだ守っている杭。 */
    public static List<BlockPos> guardingStakes(ServerLevel level) {
        AbsentWards w = get(level);
        if (w.stakes.isEmpty()) return List.of();
        long now = level.getGameTime();
        List<BlockPos> out = new ArrayList<>();
        for (Machine m : w.stakes.values()) if (m.isAway(now) && m.guarding(now) && !w.gone(level, m)) out.add(m.blockPos());
        w.dropGone();
        return out;
    }

    /** 留守で、まだ守っている換気機。 */
    public static List<Machine> guardingVents(ServerLevel level) {
        AbsentWards w = get(level);
        if (w.vents.isEmpty()) return List.of();
        long now = level.getGameTime();
        List<Machine> out = new ArrayList<>();
        for (Machine m : w.vents.values()) if (m.isAway(now) && m.guarding(now) && !w.gone(level, m)) out.add(m);
        w.dropGone();
        return out;
    }

    private final it.unimi.dsi.fastutil.longs.LongArrayList goneStakes = new it.unimi.dsi.fastutil.longs.LongArrayList();
    private final it.unimi.dsi.fastutil.longs.LongArrayList goneVents = new it.unimi.dsi.fastutil.longs.LongArrayList();

    /** 覚えの場所が読み込まれているのに機械が無い(ほかの手段で消えた・テスト場の片付け)。見つけたら後で忘れる。 */
    private boolean gone(ServerLevel level, Machine m) {
        BlockPos p = m.blockPos();
        if (level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null) return false;
        var be = level.getBlockEntity(p);
        boolean ok = m.vent ? be instanceof VentBlockEntity : be instanceof LeyStakeBlockEntity;
        if (!ok) (m.vent ? goneVents : goneStakes).add(m.pos);
        return !ok;
    }

    private void dropGone() {
        if (goneStakes.isEmpty() && goneVents.isEmpty()) return;
        for (long p : goneStakes) stakes.remove(p);
        for (long p : goneVents) vents.remove(p);
        goneStakes.clear();
        goneVents.clear();
        version++;
        setDirty();
    }

    /** 留守の杭・換気機の中で、次に FE が切れる tick(RingWards の作り直しの合図)。 */
    static long nextStakeExpiry(ServerLevel level) {
        AbsentWards w = get(level);
        long now = level.getGameTime(), best = Long.MAX_VALUE;
        for (Machine m : w.stakes.values()) if (m.isAway(now) && m.guarding(now)) best = Math.min(best, m.expiry());
        return best;
    }

    static int version(ServerLevel level) {
        return get(level).version;
    }

    /** 留守の杭が止めた分を払う(RingWards から。効きの設定で割るのは機械と同じ)。 */
    static void payAway(ServerLevel level, BlockPos stake, int fe) {
        Machine m = find(level, stake, false);
        if (m == null || fe <= 0) return;
        long now = level.getGameTime();
        m.advance(now);
        m.energy = Math.max(0, m.energy - (int) Math.ceil(fe / com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.pushbackEfficiency()));
        AbsentWards w = get(level);
        w.version++;
        w.setDirty();
    }

    /** 留守の換気機が場から抜いた量を覚えのフィルターへ({@link com.yayuy.overgrownvegetation.survival.vent.Ventilators#apply} から)。 */
    public static void applyAway(ServerLevel level, BlockPos pos, double units, int steps, SporeFieldTable table) {
        Machine m = find(level, pos, true);
        if (m == null || m.stock == null || steps <= 0) return;
        long now = level.getGameTime();
        m.advance(now); // 詰まって止まる前の FE を今まで減らす
        boolean changed = false;
        if (!m.stock.filter.isEmpty() && units > 0.0D) {
            m.loadCarry += units * VentBlockEntity.loadPerUnit(table);
            int whole = (int) m.loadCarry;
            m.loadCarry -= whole;
            AirFilters.absorb(m.stock.filter, whole);
            changed = true;
        }
        if (m.stock.rotate()) changed = true;
        if (changed) {
            m.revision++;
            AbsentWards w = get(level);
            w.version++;
            w.setDirty();
        }
    }

    /** 留守の換気機がこの計算で抜ける量(場の単位。差してあるフィルターの残りで切る)。詰まっていれば先に予備へ替える。 */
    public static double awayBudget(ServerLevel level, Machine m, int steps, SporeFieldTable table) {
        if (m.stock == null) return 0.0D;
        if (m.stock.rotate()) {
            m.revision++;
            get(level).setDirty();
        }
        if (m.stock.filter.isEmpty()) return 0.0D;
        double perUnit = VentBlockEntity.loadPerUnit(table);
        double byFilter = perUnit <= 0.0D ? Double.POSITIVE_INFINITY : AirFilters.remaining(m.stock.filter) / perUnit;
        return Math.min(table.vent().capPerUpdate() * steps, byFilter);
    }

    // ---------------------------------------------------------------- 片付け

    /** 覚えの場所が読み込まれているのに機械が無い(ほかの手段で消えた)物を忘れる(10 秒に 1 回)。 */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % 200L != 17L) return;
        AbsentWards w = CACHE.get(level.dimension());
        if (w == null || (w.stakes.isEmpty() && w.vents.isEmpty())) return;
        long now = level.getGameTime();
        sweep(level, w, w.stakes, now, false);
        sweep(level, w, w.vents, now, true);
    }

    private static void sweep(ServerLevel level, AbsentWards w, Long2ObjectOpenHashMap<Machine> map, long now, boolean vent) {
        var it = map.values().iterator();
        while (it.hasNext()) {
            Machine m = it.next();
            if (!m.isAway(now)) continue;
            BlockPos p = m.blockPos();
            if (level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null) continue;
            var be = level.getBlockEntity(p);
            boolean ok = vent ? be instanceof VentBlockEntity : be instanceof LeyStakeBlockEntity;
            if (!ok) {
                it.remove();
                w.version++;
                w.setDirty();
            }
        }
    }

    // ---------------------------------------------------------------- 保存

    static AbsentWards load(CompoundTag tag, HolderLookup.Provider registries) {
        AbsentWards w = new AbsentWards();
        ListTag list = tag.getList("machines", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            Machine m = new Machine(t.getLong("pos"), t.getBoolean("vent"));
            m.energy = t.getInt("energy");
            m.asOf = t.getLong("asOf");
            m.lastSeen = t.getLong("lastSeen");
            m.drainPerTick = t.getDouble("drain");
            m.inflowPerTick = t.getDouble("inflow");
            m.producerLinked = t.getBoolean("linked");
            m.supplied = t.getBoolean("supplied");
            m.revision = t.getInt("revision");
            m.loadCarry = t.getDouble("loadCarry");
            if (m.vent && m.stock != null && t.contains("stock")) m.stock.load(t.getCompound("stock"), registries);
            (m.vent ? w.vents : w.stakes).put(m.pos, m);
        }
        return w;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Long2ObjectOpenHashMap<Machine> map : List.of(stakes, vents)) {
            for (Machine m : map.values()) {
                CompoundTag t = new CompoundTag();
                t.putLong("pos", m.pos);
                t.putBoolean("vent", m.vent);
                t.putInt("energy", m.energy);
                t.putLong("asOf", m.asOf);
                t.putLong("lastSeen", m.lastSeen);
                t.putDouble("drain", m.drainPerTick);
                t.putDouble("inflow", m.inflowPerTick);
                t.putBoolean("linked", m.producerLinked);
                t.putBoolean("supplied", m.supplied);
                t.putInt("revision", m.revision);
                t.putDouble("loadCarry", m.loadCarry);
                if (m.stock != null) {
                    CompoundTag s = new CompoundTag();
                    m.stock.save(s, registries);
                    t.put("stock", s);
                }
                list.add(t);
            }
        }
        tag.put("machines", list);
        return tag;
    }

    // ---------------------------------------------------------------- 表示・テスト

    /** 表示用: 覚えている台数・留守の台数・留守で守っている台数。 */
    public static int[] counts(ServerLevel level, boolean vent) {
        AbsentWards w = get(level);
        long now = level.getGameTime();
        int all = 0, away = 0, guarding = 0;
        for (Machine m : (vent ? w.vents : w.stakes).values()) {
            all++;
            if (m.isAway(now)) {
                away++;
                if (m.guarding(now)) guarding++;
            }
        }
        return new int[]{all, away, guarding};
    }

    /** 表示用: いちばん近い留守の機械(無ければ null)。 */
    @Nullable
    public static Machine nearestAway(ServerLevel level, BlockPos from, boolean vent) {
        AbsentWards w = get(level);
        long now = level.getGameTime();
        Machine best = null;
        double bd = Double.MAX_VALUE;
        for (Machine m : (vent ? w.vents : w.stakes).values()) {
            if (!m.isAway(now)) continue;
            double d = m.blockPos().distSqr(from);
            if (d < bd) {
                bd = d;
                best = m;
            }
        }
        return best;
    }

    /** {@code /ov perf pushback}・{@code /ov perf vent} の 1 行(開発用なので日本語だけ)。 */
    public static String describe(ServerLevel level, BlockPos from, boolean vent) {
        int[] c = counts(level, vent);
        String what = vent ? "換気機" : "杭";
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT, "留守の%s: 覚え %d・留守 %d(守っている %d)", what, c[0], c[1], c[2]));
        Machine m = nearestAway(level, from, vent);
        if (m != null) {
            long now = level.getGameTime();
            long exp = m.expiry();
            String left = !m.guarding(now) ? "切れている" : exp == Long.MAX_VALUE ? "切れない(発電機から足りている)"
                    : String.format(java.util.Locale.ROOT, "あと %.1f 分", (exp - now) / 1200.0D);
            sb.append(String.format(java.util.Locale.ROOT, "・いちばん近い留守 %d,%d,%d: %d FE・使う %.2f FE/t・受け取る %.2f FE/t%s・%s",
                    BlockPos.getX(m.pos), BlockPos.getY(m.pos), BlockPos.getZ(m.pos), m.energyAt(now), m.drainPerTick, m.inflowPerTick,
                    m.supplied ? "(発電機から足りている・減らない)" : m.producerLinked ? "(発電機あり・足りない)" : "(発電機なし)", left));
            if (m.stock != null) {
                sb.append(String.format(java.util.Locale.ROOT, "・フィルター %s・予備 %d・詰まった物 %d",
                        m.stock.filter.isEmpty() ? "-" : AirFilters.percentLeft(m.stock.filter) + "%", m.stock.spareFilled(), m.stock.cloggedFilled()));
            }
        }
        return sb.toString();
    }

    /** テスト用: 機械の無い場所に留守の覚えを置く(読み込まれていない遠くの場所で)。 */
    public static Machine putAwayForTests(ServerLevel level, BlockPos pos, boolean vent, int energy, double drainPerTick) {
        AbsentWards w = get(level);
        Machine m = new Machine(pos.asLong(), vent);
        long now = level.getGameTime();
        m.energy = energy;
        m.asOf = now;
        m.lastSeen = now;
        m.drainPerTick = drainPerTick;
        m.away = true;
        (vent ? w.vents : w.stakes).put(m.pos, m);
        w.version++;
        w.setDirty();
        return m;
    }

    /** テスト用: 留守の覚えの時刻を {@code ticks} だけ昔へずらす(その分の時間が経った扱い)。 */
    public static void ageForTests(ServerLevel level, Machine m, long ticks) {
        m.asOf -= ticks;
        m.lastSeen -= ticks;
        m.away = true;
        get(level).version++;
    }

    /** テスト用: 保存して読み直した覚えに入れ替える。 */
    public static void reloadForTests(ServerLevel level) {
        AbsentWards w = get(level);
        CompoundTag tag = w.save(new CompoundTag(), level.registryAccess());
        AbsentWards fresh = load(tag, level.registryAccess());
        w.stakes.clear();
        w.vents.clear();
        w.stakes.putAll(fresh.stakes);
        w.vents.putAll(fresh.vents);
        w.version++;
    }

    /** テスト用: その場所の覚えを消す。 */
    public static void clearForTests(ServerLevel level, BlockPos pos) {
        forget(level, pos, false);
        forget(level, pos, true);
    }

    static double designWindowTicks() {
        return Math.max(20, DesignData.pushBack().absent.supplyWindowSeconds * 20);
    }
}
