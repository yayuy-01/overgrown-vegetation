package com.yayuy.overgrownvegetation.front;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.FrontTable;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.ForestUpkeep;
import com.yayuy.overgrownvegetation.horde.VirtualHordes;
import com.yayuy.overgrownvegetation.registry.ModPhyllosens;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * <b>前線のフィロセンス</b>(2026-11-22 ユーザー決定「巨大樹の管理できていない前線は、フィロセンスとかをちょっとずつ置いて視界を確保する」。
 * 答え: 広げる部隊が植える・巨大樹の管理に入ってもそのまま残る)。
 * <ul>
 *   <li>広げる部隊が升に着いたら、となりの まだ森でない升(縁の外側。樹域の真ん中からいちばん遠いもの)の真ん中が巨大樹の管理の外で、frontWatcherSpacingBlocks(160【仮】)の中に前線のフィロセンスが無ければ 1 体植える。
 *   値段はフィロセンス 1 体の値段(予算から)【仮】。監視塔の門(DirectorGates WATCHER)が開いてから。</li>
 *   <li>育ちは拠点の見張りと同じ(director.json の watcher.growDays)。育ち切ったら、まわり frontWatcherVisionBlocks(96【仮】。拠点の見張りは 48)が森の視界({@code ForestVision})に入る。</li>
 *   <li>拠点の記憶の見張り(WatcherSite)とは別の、森全体の台帳。巨大樹の管理の中に入っても残す。実物が倒されたら記録ごと消え、視界も消える。</li>
 *   <li>読み込まれていない所では記録だけ。育ち切って、その場所が読み込まれたら実物(フィロセンス)を立てる(現地の苗床と同じ)。
 *   ※ 拠点の見張りは、今は実物を植える口(Watchers.registerPlanter)が登録されておらず、記録も実物も出ない。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class FrontWatchers extends SavedData {

    public static final String ID = OvergrownVegetation.MODID + "_front_watchers";
    public static final SavedData.Factory<FrontWatchers> FACTORY = new SavedData.Factory<>(FrontWatchers::new, (tag, registries) -> load(tag), null);
    /** 実物の印(エンティティの保存データ。台帳の番号)。 */
    public static final String KEY = "ov_front_watcher";

    /** 前線のフィロセンス 1 体の記録。 */
    public static final class Site {
        final int id;
        final int x, z;
        final int front;
        final long plantedAt;
        boolean grown;
        UUID real;

        Site(int id, int x, int z, int front, long plantedAt) {
            this.id = id;
            this.x = x;
            this.z = z;
            this.front = front;
            this.plantedAt = plantedAt;
        }

        public int x() {
            return x;
        }

        public int z() {
            return z;
        }

        public boolean grown() {
            return grown;
        }
    }

    private final List<Site> sites = new ArrayList<>();
    private int nextId = 1;
    public static long planted, killed;
    public static double paid;

    public static FrontWatchers get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public List<Site> sites() {
        return sites;
    }

    /** フィロセンス 1 体の値段(Mob の値段。無ければ監視塔の値段)。 */
    public static double price() {
        double p = ForestUpkeep.unitPrice(ModPhyllosens.PHYLLOSENS.get(), false);
        return p > 0.0D ? p : DesignData.director().watcher.cost;
    }

    /**
     * 着いた広げる部隊 s が升 (cellX, cellZ) の真ん中にフィロセンスを植える(管理の外・間隔の中に無い・門が開いている・予算がある)。植えたら true。
     */
    public boolean tryPlant(ServerLevel level, VirtualHordes.Swarm s, int cellX, int cellZ) {
        FrontTable t = DesignData.front();
        if (!t.frontWatchersEnabled || !DirectorGates.open(level, DirectorGates.Gate.WATCHER) || ForestDirector.hoarding()) return false;
        // 11-22 ユーザー「植える所をその先に」: 着いた升ではなく、となりの まだ森でない升(縁の外側)のうち、樹域の真ん中から いちばん遠い升の真ん中
        com.yayuy.overgrownvegetation.phase.PhaseData pd = com.yayuy.overgrownvegetation.phase.PhaseData.get(level);
        Fronts.Front f = Fronts.get(level.getServer()).byId(s.front());
        double fx = f != null ? f.x : (cellX * 4 + 2) * 16.0D, fz = f != null ? f.z : (cellZ * 4 + 2) * 16.0D;
        int bestX = Integer.MIN_VALUE, bestZ = 0;
        double bestD = -1.0D;
        for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int nx = cellX + o[0], nz = cellZ + o[1];
            if (FrontDirector.cellForest(pd, nx, nz)) continue;
            double px = (nx * 4 + 2) * 16 + 8, pz = (nz * 4 + 2) * 16 + 8, d = (px - fx) * (px - fx) + (pz - fz) * (pz - fz);
            if (d > bestD) {
                bestD = d;
                bestX = nx;
                bestZ = nz;
            }
        }
        if (bestX == Integer.MIN_VALUE) return false; // まわりが全部森: 縁ではない
        int x = (bestX * 4 + 2) * 16 + 8, z = (bestZ * 4 + 2) * 16 + 8;
        if (FrontTrees.managed(level, x + 0.5D, z + 0.5D) || near(x, z, t.frontWatcherSpacingBlocks)) return false;
        double price = price();
        ForestDirector dir = ForestDirector.get(level);
        if (dir.available(level) + 1.0E-9D < price || !dir.tryPay(level, price)) return false;
        sites.add(new Site(nextId++, x, z, s.front(), level.getGameTime()));
        planted++;
        paid += price;
        setDirty();
        return true;
    }

    /** (x, z) から r 以内に前線のフィロセンス(育ち途中も)があるか。 */
    boolean near(double x, double z, double r) {
        double r2 = r * r;
        for (Site w : sites) {
            double dx = w.x - x, dz = w.z - z;
            if (dx * dx + dz * dz < r2) return true;
        }
        return false;
    }

    /** 育ち切ったものを GRID ブロックの升に分けた索引(森の視界はよく聞かれ、試算では 30 日で 2000 体を超えるため)。 */
    private static final int GRID = 128;
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<List<Site>> grownIndex = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private boolean indexDirty = true;

    private void reindex() {
        grownIndex.clear();
        for (Site w : sites) {
            if (!w.grown) continue;
            grownIndex.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(Math.floorDiv(w.x, GRID), Math.floorDiv(w.z, GRID)), k -> new ArrayList<>()).add(w);
        }
        indexDirty = false;
    }

    @Override
    public void setDirty() {
        super.setDirty();
        indexDirty = true;
    }

    /** 育ち切った前線のフィロセンスの周り r 以内か(森の視界)。 */
    public static boolean nearGrown(ServerLevel level, BlockPos pos, double r) {
        if (level.getServer() == null) return false;
        FrontWatchers d = get(level.getServer());
        if (d.sites.isEmpty()) return false;
        if (d.indexDirty) d.reindex();
        double r2 = r * r;
        int reach = (int) Math.ceil(r / GRID);
        int gx = Math.floorDiv(pos.getX(), GRID), gz = Math.floorDiv(pos.getZ(), GRID);
        for (int ox = -reach; ox <= reach; ox++) {
            for (int oz = -reach; oz <= reach; oz++) {
                List<Site> list = d.grownIndex.get(net.minecraft.world.level.ChunkPos.asLong(gx + ox, gz + oz));
                if (list == null) continue;
                for (Site w : list) {
                    double dx = w.x - pos.getX(), dz = w.z - pos.getZ();
                    if (dx * dx + dz * dz <= r2) return true;
                }
            }
        }
        return false;
    }

    /** 1 回まわす(FrontDirector から 1 秒ごと): 育ち・実物。 */
    public void tick(ServerLevel level) {
        long grow = (long) (DesignData.director().watcher.growDays * 24000.0D);
        long now = level.getGameTime();
        for (Site w : sites) {
            if (!w.grown && now - w.plantedAt >= grow) {
                w.grown = true;
                setDirty();
            }
            if (w.grown) ensureReal(level, w);
        }
    }

    /** 育ち切って読み込まれていれば実物を立てる(置いた実物がこの辺りに居なければ置き直さない。倒されたら記録ごと消える)。 */
    void ensureReal(ServerLevel level, Site w) {
        if (w.real != null) return;
        BlockPos center = new BlockPos(w.x, 64, w.z);
        if (level.getChunkSource().getChunkNow(w.x >> 4, w.z >> 4) == null || !level.isPositionEntityTicking(center)) return;
        BlockPos feet = com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.findSpawnSurface(level, w.x, w.z);
        if (feet == null) return;
        Mob m = com.yayuy.overgrownvegetation.spawn.EnemySpawns.create(level, ModPhyllosens.PHYLLOSENS.get(), feet, MobSpawnType.EVENT, level.getRandom());
        if (m == null) return;
        m.getPersistentData().putInt(KEY, w.id);
        m.setPersistenceRequired();
        if (level.addFreshEntity(m)) {
            w.real = m.getUUID();
            setDirty();
        }
    }

    /** 実物が倒された: 記録ごと消す(視界も消える)。 */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!(e.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!e.getPersistentData().contains(KEY)) return;
        FrontWatchers d = get(level.getServer());
        int id = e.getPersistentData().getInt(KEY);
        if (d.sites.removeIf(w -> w.id == id)) {
            killed++;
            d.setDirty();
        }
    }

    /** 計測の CSV 用: 数・育ち切った数・実物の数・倒された数(累計)。 */
    public static String stats(ServerLevel level) {
        FrontWatchers d = get(level.getServer());
        int grown = 0, real = 0;
        for (Site w : d.sites) {
            if (w.grown) grown++;
            if (w.real != null) real++;
        }
        return d.sites.size() + "," + grown + "," + real + "," + killed;
    }

    public static String describe(ServerLevel level) {
        FrontWatchers d = get(level.getServer());
        int grown = 0;
        for (Site w : d.sites) if (w.grown) grown++;
        return String.format(Locale.ROOT, "前線のフィロセンス: %d(育ち切った %d)・これまで 植えた %d(払った %.0f)・倒された %d", d.sites.size(), grown, planted, paid, killed);
    }

    /** テスト用: 植えた時刻を ticks だけ昔にする。 */
    public void ageForTests(long ticks) {
        List<Site> aged = new ArrayList<>();
        for (Site w : sites) {
            Site a = new Site(w.id, w.x, w.z, w.front, w.plantedAt - ticks);
            a.grown = w.grown;
            a.real = w.real;
            aged.add(a);
        }
        sites.clear();
        sites.addAll(aged);
        indexDirty = true;
    }

    public void clearForTests() {
        sites.clear();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Site w : sites) {
            CompoundTag c = new CompoundTag();
            c.putInt("Id", w.id);
            c.putInt("X", w.x);
            c.putInt("Z", w.z);
            c.putInt("Front", w.front);
            c.putLong("At", w.plantedAt);
            c.putBoolean("Grown", w.grown);
            if (w.real != null) c.putUUID("Real", w.real);
            list.add(c);
        }
        tag.put("Sites", list);
        tag.putInt("Next", nextId);
        return tag;
    }

    public static FrontWatchers load(CompoundTag tag) {
        FrontWatchers d = new FrontWatchers();
        for (Tag t : tag.getList("Sites", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            Site w = new Site(c.getInt("Id"), c.getInt("X"), c.getInt("Z"), c.getInt("Front"), c.getLong("At"));
            w.grown = c.getBoolean("Grown");
            if (c.hasUUID("Real")) w.real = c.getUUID("Real");
            d.sites.add(w);
        }
        d.nextId = Math.max(1, tag.getInt("Next"));
        return d;
    }
}
