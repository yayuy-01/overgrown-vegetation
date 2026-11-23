package com.yayuy.overgrownvegetation.front;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.FrontTable;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.ForestUpkeep;
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
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * <b>根付きの前哨</b>(2026-11-22 ユーザー決定「根付きは動けない前哨」)。根付いた個体は部隊から外し、その場に残る前哨として記録する
 * (置き直し・駆けつけ・合流にはついていかない)。
 * <ul>
 *   <li>広げる部隊の前哨: 巨大樹から outpostMinTreeBlocks【仮】より遠い升に着いた広げる部隊は、行進の間に取り込んだ生き物(感染で段階 0 になった物)が
 *   outpostAfterInfected【仮】たまったら、升 1 つに outpostMaxPerCell【仮】まで根付きに変える。現地の苗床が萌芽体を生むまでの守りと周りの汚染の受け持ち。</li>
 *   <li>守りの根付き: ふだんは根付かない。樹域が攻められた(attacked)ときだけ、持ち場に着いた守りの段階 0 を rootedPerUnit ずつ・部隊の rootedMaxShare まで(【仮】)。</li>
 *   <li>読み込まれていない所は記録だけ。読み込まれたら実物を立てる(前線のフィロセンスと同じ)。実物が倒されたら記録ごと消える。</li>
 *   <li>森に飲み込まれたら枯れる: まわり outpostWitherDepthBlocks【仮】に森でないチャンクが無くなったら、根付きの値段の outpostRefundShare【仮】を森の稼ぎへ返して消える。守りの根付きは飲み込まれても枯れず、樹域が outpostDefendWitherDays【仮】攻められなかったら枯れて返す(11-22 見直し)。</li>
 *   <li>値段は根付きの姿の値段 − 段階 0(今までの根付きと同じ。取り置きを残して払う)。重砲兵(根付きの第二進化体)はまだ入れない。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class FrontOutposts extends SavedData {

    public static final String ID = OvergrownVegetation.MODID + "_front_outposts";
    public static final SavedData.Factory<FrontOutposts> FACTORY = new SavedData.Factory<>(FrontOutposts::new, (tag, registries) -> load(tag), null);
    /** 実物の印(エンティティの保存データ。台帳の番号)。 */
    public static final String KEY = "ov_front_outpost";

    public enum Kind {EXPAND, DEFEND}

    /** 根付いた 1 体の記録。 */
    public static final class Post {
        final int id;
        final int x, z;
        final int front;
        final ResourceLocation type;
        final double price;
        final Kind kind;
        final long unit;
        final long cell;
        final long at;
        UUID real;
        /** 読み込まれているのに実物が見つからなかった秒(保存しない)。 */
        int miss;

        Post(int id, int x, int z, int front, ResourceLocation type, double price, Kind kind, long unit, long cell, long at) {
            this.id = id;
            this.x = x;
            this.z = z;
            this.front = front;
            this.type = type;
            this.price = price;
            this.kind = kind;
            this.unit = unit;
            this.cell = cell;
            this.at = at;
        }

        public int x() {
            return x;
        }

        public int z() {
            return z;
        }

        public Kind kind() {
            return kind;
        }

        public ResourceLocation type() {
            return type;
        }

        @Nullable
        public UUID real() {
            return real;
        }
    }

    private final List<Post> posts = new ArrayList<>();
    private int nextId = 1;
    /** 広げる部隊の id → 行進の間に取り込んだ(まだ根付きにしていない)生き物の数。起動し直すと消える。 */
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap INFECTED = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    public static long rootedExpand, rootedDefend, rootedMoved, withered, witheredDefend, killed, realLost;
    public static double paid, refunded;
    /** 実物が見つからないまま待つ秒(1 秒ごとに数える)。 */
    static final int REAL_MISS_SECONDS = 10;

    static {
        // 11-22 見直し・ユーザー決定: 前哨は実物が立っていなくても、いつも維持費に数える(実物は ForestUpkeep.sampleLoaded で数えない)
        ForestUpkeep.addFeed((level, sink) -> {
            if (level.getServer() == null || level.dimension() != Level.OVERWORLD) return;
            for (Post p : get(level.getServer()).posts) sink.add(p.price, com.yayuy.overgrownvegetation.director.ForestEconomy.inForest(level, p.x, p.z));
        });
    }

    public static FrontOutposts get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public List<Post> posts() {
        return posts;
    }

    /** 広げる部隊が生き物を取り込んだ(FrontDirector.addInfected から)。 */
    public static void noteInfected(long unit, int n) {
        if (n > 0) INFECTED.addTo(unit, n);
    }

    /** 11-22 見直し: サーバーが止まったら消す。 */
    static void clearStatics() {
        INFECTED.clear();
    }

    public static int infectedOf(long unit) {
        return INFECTED.get(unit);
    }

    private Post add(int x, int z, int front, ResourceLocation type, double price, Kind kind, long unit, long cell, long now) {
        Post p = new Post(nextId++, x, z, front, type, price, kind, unit, cell, now);
        posts.add(p);
        setDirty();
        return p;
    }

    private int countAtCell(long cell) {
        int n = 0;
        for (Post p : posts) if (p.kind == Kind.EXPAND && p.cell == cell) n++;
        return n;
    }

    private int countFrom(long unit) {
        int n = 0;
        for (Post p : posts) if (p.kind == Kind.DEFEND && p.unit == unit) n++;
        return n;
    }

    @Nullable
    static EntityType<?> rootedOf(ResourceLocation id) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        for (G1Species g : G1Species.values()) {
            if (g.entityType() == type) return com.yayuy.overgrownvegetation.director.G1Fate.rootedTypeOf(g);
        }
        return null;
    }

    /** 根付きの姿 1 体の値段(取り置きを残して払う)。払えなければ -1。 */
    private static double payRooting(ServerLevel level, EntityType<?> rType) {
        double stageZero = DesignData.director().largeRaid.costStageZero * com.yayuy.overgrownvegetation.director.ForestEconomy.priceFactor();
        double cost = Math.max(0.0D, ForestUpkeep.unitPrice(rType, false) - stageZero);
        ForestDirector dir = ForestDirector.get(level);
        if (dir.available(level) - cost < FrontDirector.reserve(level)) return -1.0D;
        if (!dir.tryPayUnit(level, cost, cost, true)) return -1.0D;
        paid += cost;
        return cost;
    }

    /**
     * 広げる部隊の前哨(FrontDirector.expand から、着いた升で): 巨大樹から outpostMinTreeBlocks より遠い升で、行進の間に取り込んだ生き物が
     * outpostAfterInfected たまっていれば、升 1 つに outpostMaxPerCell まで根付きに変える。根付きにした数を返す。
     */
    public int tryOutpost(ServerLevel level, VirtualHordes.Swarm s, @Nullable Fronts.Front f, int cellX, int cellZ) {
        FrontTable t = DesignData.front();
        if (f == null || t.outpostMaxPerCell <= 0 || !DirectorGates.open(level, DirectorGates.Gate.ROOTED)) return 0;
        double cx = (cellX * 4 + 2) * 16.0D + 8.0D, cz = (cellZ * 4 + 2) * 16.0D + 8.0D;
        double dx = cx - f.x, dz = cz - f.z;
        if (dx * dx + dz * dz < t.outpostMinTreeBlocks * t.outpostMinTreeBlocks) return 0;
        int have = INFECTED.get(s.id());
        if (have < t.outpostAfterInfected) return 0;
        long cell = ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
        int room = Math.min(have, t.outpostMaxPerCell - countAtCell(cell));
        int done = 0;
        RandomSource random = level.getRandom();
        long now = level.getGameTime();
        for (var e : new ArrayList<>(s.counts().entrySet())) {
            if (done >= room) break;
            EntityType<?> rType = rootedOf(e.getKey());
            if (rType == null) continue;
            while (done < room && s.counts().getOrDefault(e.getKey(), 0) > 0) {
                double cost = payRooting(level, rType);
                if (cost < 0.0D) {
                    room = done; // 11-22 見直し: 払えなければ止める(return せず、根付かせた分を INFECTED と touch に反映する)
                    break;
                }
                s.takeHeadsOf(e.getKey(), 1);
                int x = Mth.floor(cx) + random.nextInt(25) - 12, z = Mth.floor(cz) + random.nextInt(25) - 12;
                add(x, z, s.front(), BuiltInRegistries.ENTITY_TYPE.getKey(rType), ForestUpkeep.unitPrice(rType, false), Kind.EXPAND, s.id(), cell, now);
                done++;
                rootedExpand++;
            }
        }
        if (done > 0) {
            INFECTED.put(s.id(), have - done);
            VirtualHordes.get(level.getServer()).touch();
        }
        return done;
    }

    /**
     * 守りの根付き(FrontDirector.assign から。攻められたときだけ): 持ち場に着いた守りの段階 0 を、1 回に rootedPerUnit まで・部隊の rootedMaxShare まで、
     * 部隊の居る所に前哨として残す。
     */
    public int rootDefenders(ServerLevel level, VirtualHordes hordes, Fronts.Front f) {
        FrontTable t = DesignData.front();
        if (t.rootedPerUnit <= 0 || !DirectorGates.open(level, DirectorGates.Gate.ROOTED)) return 0;
        RandomSource random = level.getRandom();
        long now = level.getGameTime();
        int total = 0;
        boolean broke = false;
        for (VirtualHordes.Swarm s : hordes.ofFront(f.id)) {
            if (broke) break;
            if (s.front() != f.id || s.purpose() != UnitPurpose.DEFEND || !s.arrived() || s.size() <= 0) continue;
            if (FrontDirector.reinforcing(s.id())) continue; // 11-22 見直し: 駆けつけた予備は元の樹域の部隊。駆けつけた先の縁で根付かせない
            if (FrontDirector.atFormingSpot(level, f, s.x(), s.z())) continue; // 作る途中の部隊(前線の真ん中)は根付かせない
            int from = countFrom(s.id());
            int max = (int) Math.floor(t.rootedMaxShare * (s.size() + from));
            int done = 0;
            for (var e : new ArrayList<>(s.counts().entrySet())) {
                if (broke || done >= t.rootedPerUnit || from + done >= max) break;
                EntityType<?> rType = rootedOf(e.getKey());
                if (rType == null) continue;
                while (done < t.rootedPerUnit && from + done < max && s.counts().getOrDefault(e.getKey(), 0) > 0) {
                    double cost = payRooting(level, rType);
                    if (cost < 0.0D) {
                        broke = true; // 11-22 見直し: 払えなければ止める(return せず、touch する)
                        break;
                    }
                    s.takeHeadsOf(e.getKey(), 1);
                    int x = Mth.floor(s.x()) + random.nextInt(13) - 6, z = Mth.floor(s.z()) + random.nextInt(13) - 6;
                    add(x, z, f.id, BuiltInRegistries.ENTITY_TYPE.getKey(rType), ForestUpkeep.unitPrice(rType, false), Kind.DEFEND, s.id(), Long.MIN_VALUE, now);
                    done++;
                    rootedDefend++;
                }
            }
            total += done;
        }
        if (total > 0) hordes.touch();
        return total;
    }

    /** 前の決まりで部隊の中に根付いた頭数(11-21〜22 の保存)を、部隊の居る所の前哨へ移す(払いは無し)。 */
    public void takeRootedFromUnits(ServerLevel level, VirtualHordes hordes) {
        long now = level.getGameTime();
        boolean any = false;
        for (VirtualHordes.Swarm s : hordes.swarms()) {
            if (!s.unit() || s.size() <= 0) continue;
            for (var e : new ArrayList<>(s.counts().entrySet())) {
                if (!FrontDirector.isRootedForm(e.getKey())) continue;
                double price = ForestUpkeep.unitPrice(BuiltInRegistries.ENTITY_TYPE.get(e.getKey()), false);
                int n = s.takeHeadsOf(e.getKey(), e.getValue());
                for (int i = 0; i < n; i++) {
                    add(Mth.floor(s.x()) + (i % 5) * 2 - 4, Mth.floor(s.z()) + (i / 5 % 5) * 2 - 4, s.front(), e.getKey(), price, Kind.DEFEND, s.id(), Long.MIN_VALUE, now);
                    rootedMoved++;
                }
                any |= n > 0;
            }
        }
        if (any) hordes.touch();
    }

    /** 1 秒ごと(FrontDirector.run): 読み込まれた所に実物を立てる。 */
    public void tick(ServerLevel level) {
        for (Post p : posts) ensureReal(level, p);
    }

    /** 読み込まれていれば実物を立てる(倒されたら記録ごと消える)。 */
    void ensureReal(ServerLevel level, Post p) {
        if (level.getChunkSource().getChunkNow(p.x >> 4, p.z >> 4) == null || !level.isPositionEntityTicking(new BlockPos(p.x, 64, p.z))) return;
        if (p.real != null) {
            // 11-22 見直し: 読み込まれているのに実物が REAL_MISS_SECONDS 続けて見つからない(倒されずに消えた)なら、立て直す
            if (level.getEntity(p.real) != null) {
                p.miss = 0;
                return;
            }
            if (++p.miss < REAL_MISS_SECONDS) return;
            p.miss = 0;
            p.real = null;
            realLost++;
            setDirty();
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(p.type);
        BlockPos feet = com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.findSpawnSurface(level, p.x, p.z);
        if (feet == null) return;
        Mob m = com.yayuy.overgrownvegetation.spawn.EnemySpawns.create(level, type, feet, MobSpawnType.EVENT, level.getRandom());
        if (m == null) return;
        m.getPersistentData().putInt(KEY, p.id);
        m.setPersistenceRequired();
        if (level.addFreshEntity(m)) {
            p.real = m.getUUID();
            setDirty();
        }
    }

    /**
     * 森に飲み込まれた前哨を枯らす(樹域を数え直すたび): まわり outpostWitherDepthBlocks に森でないチャンクが無ければ、
     * 値段の outpostRefundShare を森の稼ぎへ返して消える。返した額の合計を返す。
     */
    public double witherSwallowed(ServerLevel level) {
        FrontTable t = DesignData.front();
        PhaseData pd = PhaseData.get(level);
        int r = Math.max(1, (int) Math.ceil(t.outpostWitherDepthBlocks / 16.0D));
        double back = 0.0D;
        int gone = 0;
        long now = level.getGameTime();
        long wait = (long) (t.outpostDefendWitherDays * 24000.0D);
        for (java.util.Iterator<Post> it = posts.iterator(); it.hasNext(); ) {
            Post p = it.next();
            if (p.kind == Kind.DEFEND) {
                // 11-22 見直し・ユーザー決定: 守りの根付きは飲み込まれても枯れない。樹域が outpostDefendWitherDays 攻められなかったら枯れる
                long last = Math.max(p.at, FrontDirector.lastAttacked(p.front));
                if (now - last < wait) continue;
                witheredDefend++;
            } else if (!swallowed(pd, p.x >> 4, p.z >> 4, r)) continue;
            double v = p.price * t.outpostRefundShare;
            if (v > 0.0D) ForestDirector.get(level).refund(level, v);
            back += v;
            if (p.real != null) {
                Entity e = level.getEntity(p.real);
                if (e != null) e.discard();
            }
            it.remove();
            withered++;
            gone++;
        }
        refunded += back;
        if (gone > 0) setDirty();
        return back;
    }

    /** まわり r チャンク(円)が全部森か。 */
    static boolean swallowed(PhaseData pd, int cx, int cz, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                if (!pd.isOvergrownKey(ChunkPos.asLong(cx + dx, cz + dz))) return false;
            }
        }
        return true;
    }

    @Nullable
    private Post byId(int id) {
        for (Post p : posts) if (p.id == id) return p;
        return null;
    }

    /** 実物が倒された: 記録ごと消す。 */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!(e.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!e.getPersistentData().contains(KEY)) return;
        FrontOutposts d = get(level.getServer());
        int id = e.getPersistentData().getInt(KEY);
        if (d.posts.removeIf(p -> p.id == id)) {
            killed++;
            d.setDirty();
        }
    }

    /** 枯れて記録の無くなった前哨の実物(読み込まれていない間に枯れた)が読み込まれたら消す。 */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || !event.loadedFromDisk()) return;
        Entity e = event.getEntity();
        if (!e.getPersistentData().contains(KEY)) return;
        Post p = get(level.getServer()).byId(e.getPersistentData().getInt(KEY));
        // 11-22 見直し: 立て直した後に古い実物が読み込まれたら消す(記録の実物と違う)
        if (p == null || (p.real != null && !p.real.equals(e.getUUID()))) event.setCanceled(true);
    }

    /** 計測の CSV 用: 前哨の数・広げる部隊の前哨・守りの根付き・実物・根付いた数(広げる・守り・前の部隊から移した)・枯れた・倒された・返した額(累計)。 */
    public static String stats(ServerLevel level) {
        FrontOutposts d = get(level.getServer());
        int ex = 0, df = 0, real = 0;
        for (Post p : d.posts) {
            if (p.kind == Kind.EXPAND) ex++;
            else df++;
            if (p.real != null) real++;
        }
        return d.posts.size() + "," + ex + "," + df + "," + real + "," + rootedExpand + "," + rootedDefend + "," + rootedMoved + "," + withered + "," + killed
                + "," + String.format(Locale.ROOT, "%.1f", refunded);
    }

    public static String describe(ServerLevel level) {
        FrontOutposts d = get(level.getServer());
        return String.format(Locale.ROOT, "根付きの前哨: %d(実物 %s)・根付いた 広げる %d・守り %d・前の部隊から %d・枯れた %d(返した %.0f)・倒された %d",
                d.posts.size(), d.posts.stream().filter(p -> p.real != null).count(), rootedExpand, rootedDefend, rootedMoved, withered, refunded, killed);
    }

    public void clearForTests() {
        posts.clear();
        INFECTED.clear();
        setDirty();
    }

    public Post addForTests(int x, int z, int front, ResourceLocation type, double price) {
        return add(x, z, front, type, price, Kind.DEFEND, -1L, Long.MIN_VALUE, 0L);
    }

    public Post addForTests(int x, int z, int front, ResourceLocation type, double price, Kind kind, long at) {
        return add(x, z, front, type, price, kind, -1L, Long.MIN_VALUE, at);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Post p : posts) {
            CompoundTag c = new CompoundTag();
            c.putInt("Id", p.id);
            c.putInt("X", p.x);
            c.putInt("Z", p.z);
            c.putInt("Front", p.front);
            c.putString("Type", p.type.toString());
            c.putDouble("Price", p.price);
            c.putString("Kind", p.kind.name());
            c.putLong("Unit", p.unit);
            c.putLong("Cell", p.cell);
            c.putLong("At", p.at);
            if (p.real != null) c.putUUID("Real", p.real);
            list.add(c);
        }
        tag.put("Posts", list);
        tag.putInt("Next", nextId);
        return tag;
    }

    public static FrontOutposts load(CompoundTag tag) {
        FrontOutposts d = new FrontOutposts();
        for (Tag t : tag.getList("Posts", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            ResourceLocation type = ResourceLocation.tryParse(c.getString("Type"));
            if (type == null) continue;
            Kind kind;
            try {
                kind = Kind.valueOf(c.getString("Kind"));
            } catch (IllegalArgumentException e) {
                kind = Kind.DEFEND;
            }
            Post p = new Post(c.getInt("Id"), c.getInt("X"), c.getInt("Z"), c.getInt("Front"), type, c.getDouble("Price"), kind, c.getLong("Unit"), c.getLong("Cell"), c.getLong("At"));
            if (c.hasUUID("Real")) p.real = c.getUUID("Real");
            d.posts.add(p);
        }
        d.nextId = Math.max(1, tag.getInt("Next"));
        return d;
    }
}
