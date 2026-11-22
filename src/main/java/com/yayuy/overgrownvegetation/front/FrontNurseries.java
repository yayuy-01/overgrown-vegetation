package com.yayuy.overgrownvegetation.front;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.FrontTable;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.ForestEconomy;
import com.yayuy.overgrownvegetation.director.ForestUpkeep;
import com.yayuy.overgrownvegetation.director.G1Fate;
import com.yayuy.overgrownvegetation.horde.UnitPurpose;
import com.yayuy.overgrownvegetation.horde.VirtualHordes;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.yayuy.overgrownvegetation.port.saved.OvSavedData;

/**
 * <b>広げる部隊の現地の苗床</b>(2026-11-21 夜 ユーザー決定。依頼書 front-nursery-brief.md・正本は Vault「前線と部隊」の末尾)。
 * <ul>
 *   <li>広げる部隊が升に着いたら、部隊の 1 体が苗床になる(値段は苗床化 = 生産と同じ。1 升に 1 つまで)。</li>
 *   <li>苗床は nurseryIntervalSeconds(20 秒。ユーザー決定)ごとに、その升にいる広げる部隊へ 1 体補充する(部隊の上限まで)。
 *       萌芽体(段階 0)は定価 × nurseryPriceFactor(1/8。ユーザー決定)、G2 以降は定価(前線の作り方と同じフェーズの割合で選ぶ)。8 割の関所・予算の 2 割は残す。</li>
 *   <li>苗床も維持費を払う(値段は苗床の値段 = 段階 0)。</li>
 *   <li>台帳の上では升の印。読み込まれたら実物の苗床(G1 の苗床の姿。11-21 夜 ユーザー決定で本採用)を置き、プレイヤーが壊せる。壊されたらその升の補充は止まる。</li>
 *   <li>升が過繁茂になったら萌芽体は生まなくなり、前線の「出す所」(作る途中の部隊が生まれる所)として残る。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class FrontNurseries extends OvSavedData {

    public static final String ID = OvergrownVegetation.MODID + "_front_nurseries";
    public static final OvSavedData.Factory<FrontNurseries> FACTORY = new OvSavedData.Factory<>(FrontNurseries::new, (tag, registries) -> load(tag), null);
    /** 実物の苗床の印(エンティティの保存データ。升のキー)。 */
    public static final String KEY = "ov_front_nursery";

    /** 苗床 1 つ。 */
    public static final class Nursery {
        final long cell;
        int front;
        long lastSpawn;
        boolean overgrown;
        @Nullable
        UUID real;

        Nursery(long cell, int front, long now) {
            this.cell = cell;
            this.front = front;
            this.lastSpawn = now;
        }

        public long cell() {
            return cell;
        }

        public int front() {
            return front;
        }

        public boolean overgrown() {
            return overgrown;
        }

        public int cellX() {
            return ChunkPos.getX(cell);
        }

        public int cellZ() {
            return ChunkPos.getZ(cell);
        }

        public double x() {
            return (cellX() * 4 + 2) * 16.0D + 8.0D;
        }

        public double z() {
            return (cellZ() * 4 + 2) * 16.0D + 8.0D;
        }
    }

    private final List<Nursery> nurseries = new ArrayList<>();
    public static long made, refilled, refilledPlant, destroyed;
    public static double refillPaid;

    public static FrontNurseries get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.load(), FACTORY.constructor(), ID);
    }

    static {
        // 苗床も維持費を払う(値段は苗床の値段 = 段階 0。過繁茂になった升は森の中)
        ForestUpkeep.addFeed((level, sink) -> {
            if (level.getServer() == null) return;
            double price = DesignData.director().largeRaid.costStageZero * ForestEconomy.priceFactor();
            for (Nursery n : get(level.getServer()).nurseries) sink.add(price, n.overgrown);
        });
    }

    public List<Nursery> nurseries() {
        return nurseries;
    }

    @Nullable
    public Nursery at(long cell) {
        for (Nursery n : nurseries) if (n.cell == cell) return n;
        return null;
    }

    /** 前線 f の過繁茂になった苗床(出す所)。 */
    public List<Nursery> sitesOf(int front) {
        List<Nursery> out = new ArrayList<>();
        for (Nursery n : nurseries) if (n.front == front && n.overgrown) out.add(n);
        return out;
    }

    /**
     * 着いた広げる部隊 s が升 (cellX, cellZ) に苗床を作る(無ければ)。部隊の段階 0 を 1 体使い、苗床化の値段(生産と同じ)を払う。作れたら true。
     */
    public boolean tryMake(ServerLevel level, VirtualHordes.Swarm s, int cellX, int cellZ) {
        long cell = ChunkPos.asLong(cellX, cellZ);
        if (at(cell) != null || s.size() < 2) return false;
        ResourceLocation g1 = null;
        for (var e : s.counts().entrySet()) {
            if (FrontDirector.isG1(BuiltInRegistries.ENTITY_TYPE.get(e.getKey())) && e.getValue() > 0) {
                g1 = e.getKey();
                break;
            }
        }
        if (g1 == null) return false;
        double price = DesignData.director().largeRaid.costStageZero * ForestEconomy.priceFactor();
        // 苗床になる 1 体は部隊から抜ける(その分の維持費は苗床の維持費に置き換わる)。苗床化の値段は 8 割の関所を通す
        if (!ForestDirector.get(level).tryPayUnit(level, price, 0.0D, false)) return false;
        s.takeHeadsOf(g1, 1);
        Nursery n = new Nursery(cell, s.front(), level.getGameTime());
        nurseries.add(n);
        made++;
        setDirty();
        VirtualHordes.get(level.getServer()).touch();
        return true;
    }

    /** 1 回まわす(テストからも。FrontDirector から 1 秒ごと)。 */
    public void tick(ServerLevel level, VirtualHordes hordes) {
        FrontTable t = DesignData.front();
        long now = level.getGameTime();
        long interval = (long) (t.nurseryIntervalSeconds * 20.0D);
        PhaseData pd = PhaseData.get(level);
        for (Nursery n : new ArrayList<>(nurseries)) {
            if (!n.overgrown && FrontDirector.cellForest(pd, n.cellX(), n.cellZ())) {
                n.overgrown = true; // 森になった: 萌芽体は生まず、前線の出す所として残る
                setDirty();
            }
            ensureReal(level, n);
            if (n.overgrown || now - n.lastSpawn < interval) continue;
            n.lastSpawn = now;
            refill(level, hordes, n);
            setDirty();
        }
    }

    /** その升にいる広げる部隊へ 1 体補充する。 */
    void refill(ServerLevel level, VirtualHordes hordes, Nursery n) {
        FrontTable t = DesignData.front();
        int max = t.unitMax(DirectorGates.phase(level));
        VirtualHordes.Swarm into = null;
        for (VirtualHordes.Swarm s : hordes.swarms()) {
            if (s.purpose() != UnitPurpose.EXPAND || !s.arrived() || s.size() >= max) continue;
            if (FrontDirector.cellKey(s.tx(), s.tz()) != (((long) n.cellX() << 32) | (n.cellZ() & 0xFFFFFFFFL))) continue;
            into = s;
            break;
        }
        if (into == null) return;
        // 樹域の頭数が目標に届いていたら補充しない(11-22 ユーザー決定)
        int front = into.front() >= 0 ? into.front() : n.front;
        if (FrontDirector.atTarget(front)) {
            FrontDirector.nurseryAtTargetSkips++;
            return;
        }
        // 11-22 ユーザー決定: 全体の頭数が目標の合計の unitGlobalCapShare 倍を超えている間は補充しない
        if (FrontDirector.overGlobalCap(Fronts.get(level.getServer()))) {
            FrontDirector.nurseryGlobalCapSkips++;
            return;
        }
        EntityType<?> type = FrontDirector.pickType(level, level.getRandom(), DirectorGates.phase(level));
        if (type == null) return;
        boolean g1 = FrontDirector.isG1(type);
        double list = ForestUpkeep.unitPrice(type, g1);
        double pay = g1 ? list * t.nurseryPriceFactor : list;
        ForestDirector dir = ForestDirector.get(level);
        if (dir.available(level) - pay < FrontDirector.reserve(level)) return;
        if (!dir.tryPayUnit(level, pay, list, false)) return;
        into.addHeads(BuiltInRegistries.ENTITY_TYPE.getKey(type), 1, pay);
        FrontDirector.addHead(front);
        refilled++;
        if (!g1) refilledPlant++;
        refillPaid += pay;
        hordes.touch();
    }

    /** 読み込まれていれば実物の苗床を置く(G1 の苗床の姿。11-21 夜 ユーザー決定で本採用。無ければ置かない)。 */
    void ensureReal(ServerLevel level, Nursery n) {
        BlockPos center = BlockPos.containing(n.x(), 64.0D, n.z());
        if (level.getChunkSource().getChunkNow(center.getX() >> 4, center.getZ() >> 4) == null || !level.isPositionEntityTicking(center)) return;
        if (n.real != null && level.getEntity(n.real) != null) return;
        if (n.real != null) return; // 置いた実物がこの辺りに居ない(読み込みの外へ出た・別の所で保存された): 置き直さない(倒されたら印ごと消える)
        EntityType<?> type = G1Fate.nurseryTypeOf(G1Species.ZOMBIE);
        if (type == null) {
            for (G1Species g : G1Species.values()) {
                type = G1Fate.nurseryTypeOf(g);
                if (type != null) break;
            }
        }
        if (type == null) return;
        BlockPos feet = com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.findSpawnSurface(level, center.getX(), center.getZ());
        if (feet == null) return;
        Mob m = com.yayuy.overgrownvegetation.spawn.EnemySpawns.create(level, type, feet, MobSpawnType.EVENT, level.getRandom());
        if (m == null) return;
        m.getPersistentData().putLong(KEY, n.cell);
        m.setPersistenceRequired();
        if (level.addFreshEntity(m)) {
            n.real = m.getUUID();
            setDirty();
        }
    }

    /** 実物の苗床が倒された: その升の苗床は止まる(印ごと消す)。 */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!(e.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!e.getPersistentData().contains(KEY)) return;
        FrontNurseries data = get(level.getServer());
        Nursery n = data.at(e.getPersistentData().getLong(KEY));
        if (n == null) return;
        data.nurseries.remove(n);
        destroyed++;
        data.setDirty();
        ForestUpkeep.invalidate();
    }

    public static String describe(ServerLevel level) {
        FrontNurseries d = get(level.getServer());
        int og = 0;
        for (Nursery n : d.nurseries) if (n.overgrown) og++;
        return String.format(Locale.ROOT, "現地の苗床: %d(過繁茂になって出す所 %d)・これまで 作った %d・補充 %d 体(G2 以降 %d・払った %.0f)・壊された %d",
                d.nurseries.size(), og, made, refilled, refilledPlant, refillPaid, destroyed);
    }

    /** テスト用: 苗床の「前に生んだ時刻」を ticks だけ昔にする。 */
    public void ageForTests(long ticks) {
        for (Nursery n : nurseries) n.lastSpawn -= ticks;
    }

    public void clearForTests() {
        nurseries.clear();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Nursery n : nurseries) {
            CompoundTag c = new CompoundTag();
            c.putLong("Cell", n.cell);
            c.putInt("Front", n.front);
            c.putLong("Last", n.lastSpawn);
            c.putBoolean("Og", n.overgrown);
            if (n.real != null) c.putUUID("Real", n.real);
            list.add(c);
        }
        tag.put("Nurseries", list);
        return tag;
    }

    public static FrontNurseries load(CompoundTag tag) {
        FrontNurseries d = new FrontNurseries();
        for (Tag t : tag.getList("Nurseries", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            Nursery n = new Nursery(c.getLong("Cell"), c.getInt("Front"), c.getLong("Last"));
            n.overgrown = c.getBoolean("Og");
            if (c.hasUUID("Real")) n.real = c.getUUID("Real");
            d.nurseries.add(n);
        }
        return d;
    }
}
