package com.yayuy.overgrownvegetation.front;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.FrontTable;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.LargeRaids;
import com.yayuy.overgrownvegetation.director.RaidScales;
import com.yayuy.overgrownvegetation.horde.UnitPurpose;
import com.yayuy.overgrownvegetation.horde.VirtualHordes;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * <b>襲撃 = 前線から襲う部隊を出す</b>(2026-11-21 前線と部隊 区切り 3。正本は Vault「前線と部隊(地図の上の森の軍勢)(11-21)」の 8)。
 * 送り込み・貯めた襲撃・大きな襲撃を 1 本にした。いつ・どの規模かは今までどおり {@code BaseRaids.dispatch} と {@code RaidScales}(見送りと貯め)が決め、
 * <b>出し方だけ</b>をここで置き換える(湧かせない。前線の部隊から出す)。
 * <ul>
 *   <li>小(送り込み・最初の襲撃): いちばん近い前線から、出せる頭数 × raidShareSmall をまっすぐ拠点へ。</li>
 *   <li>中(貯めた襲撃): いちばん近い前線から × raidShareMedium を、拠点の近く(raidGatherBlocks・前線の側)の集まる所へ。全部着くか
 *       raidGatherMaxDays たったら一緒に拠点へ。集まっている所は、プレイヤーが近づけば実物になる(偵察で見つけて先に叩ける)。</li>
 *   <li>大: raidRangeBlocks の中の前線全部から × raidShareLarge を集まる所へ(前触れの 2 日の間に歩く)。本番が始まったら拠点へ。</li>
 *   <li>出せる頭数 = 守りの下限を超えた守りの部隊 + 広げる部隊。撃退された回数 × repelGrowth だけ大きくする(repelMaxFactor まで)。</li>
 *   <li>撃退: 出した頭数の raidLossRepelShare 以上が倒されたら、拠点の撃退の回数を 1 足す。</li>
 *   <li>拠点に着いた部隊は、読み込まれるまで拠点の前で待つ(戦いは読み込まれてから)。島の拠点で水に阻まれたら、流れ木もどきで運ぶ。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class FrontRaids extends SavedData {

    public static final String ID = OvergrownVegetation.MODID + "_front_raids";
    public static final SavedData.Factory<FrontRaids> FACTORY = new SavedData.Factory<>(FrontRaids::new, (tag, registries) -> load(tag), null);

    /** 1 回の襲撃。 */
    public static final class Raid {
        final long id;
        final int baseId;
        final RaidScales.RaidScale scale;
        final double gatherX, gatherZ;
        final double startDay;
        boolean gathering;
        double advanceDay = Double.NaN;
        int sent, dead;

        Raid(long id, int baseId, RaidScales.RaidScale scale, double gatherX, double gatherZ, double startDay) {
            this.id = id;
            this.baseId = baseId;
            this.scale = scale;
            this.gatherX = gatherX;
            this.gatherZ = gatherZ;
            this.startDay = startDay;
        }

        public UUID group() {
            return new UUID(0x52414944L, id); // "RAID"
        }

        public int sent() {
            return sent;
        }

        public int dead() {
            return dead;
        }

        public boolean gathering() {
            return gathering;
        }

        public RaidScales.RaidScale scale() {
            return scale;
        }

        /** テスト用: 倒された数を足す。 */
        public void addDeadForTests(int n) {
            dead += n;
        }
    }

    private final List<Raid> raids = new ArrayList<>();
    private long nextId = 1L;
    public static long raidsSent, headsSent, repels, ferried;

    public static FrontRaids get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public List<Raid> raids() {
        return raids;
    }

    /** 前線から出す作りが働いているか(GameTest のサーバーでは、テストが前線を動かすときだけ)。 */
    public static boolean active(ServerLevel level) {
        if (!FrontDirector.enabled() || !DesignData.front().raidsFromFronts) return false;
        return FrontDirector.tickInGameTests || !(level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer);
    }

    /** 撃退の回数での大きさの倍率。 */
    public static double repelFactor(BaseMemory.Base b) {
        FrontTable t = DesignData.front();
        return Math.min(t.repelMaxFactor, 1.0D + t.repelGrowth * b.repelled());
    }

    /**
     * 拠点 b へ襲う部隊を出す(RaidScales と BaseRaids の口から)。出した頭数を返す(0 なら出せる部隊が無い)。
     */
    public static int send(ServerLevel level, BaseMemory.Base b, double day, RaidScales.RaidScale scale) {
        FrontTable t = DesignData.front();
        Fronts fronts = Fronts.get(level.getServer());
        VirtualHordes hordes = VirtualHordes.get(level.getServer());
        if (fronts.fronts().isEmpty()) return 0;
        Fronts.Front nearest = fronts.nearest(b.centerX(), b.centerZ(), -1);
        if (nearest == null) return 0;
        List<Fronts.Front> from = new ArrayList<>();
        if (scale == RaidScales.RaidScale.LARGE) {
            for (Fronts.Front f : fronts.fronts()) if (FrontDirector.near(f.x, f.z, b.centerX(), b.centerZ(), t.raidRangeBlocks)) from.add(f);
            if (from.isEmpty()) from.add(nearest);
        } else {
            from.add(nearest);
        }
        double share = switch (scale) {
            case SMALL -> t.raidShareSmall;
            case MEDIUM -> t.raidShareMedium;
            case LARGE -> t.raidShareLarge;
        };
        double factor = repelFactor(b);
        // 集まる所: 拠点から前線の側へ raidGatherBlocks(小はまっすぐ拠点へ)
        double dx = nearest.x - b.centerX(), dz = nearest.z - b.centerZ(), d = Math.max(1.0E-3D, Math.sqrt(dx * dx + dz * dz));
        boolean gather = scale != RaidScales.RaidScale.SMALL;
        double gx = gather ? b.centerX() + dx / d * t.raidGatherBlocks : b.centerX();
        double gz = gather ? b.centerZ() + dz / d * t.raidGatherBlocks : b.centerZ();
        FrontRaids data = get(level.getServer());
        Raid raid = new Raid(data.nextId++, b.id(), scale, gx, gz, day);
        raid.gathering = gather;
        Long2IntOpenHashMap real = FrontDirector.realByUnit(level);
        long now = level.getGameTime();
        int max = t.unitMax(com.yayuy.overgrownvegetation.director.DirectorGates.phase(level));
        for (Fronts.Front f : from) {
            int raidable = raidable(hordes, f, real);
            int want = Math.min(raidable, (int) Math.ceil(raidable * share * factor));
            if (want <= 0) continue;
            int got = pull(level, hordes, f, real, want, max, raid, gx, gz, now);
            raid.sent += got;
        }
        if (raid.sent <= 0) return 0;
        data.raids.add(raid);
        data.setDirty();
        raidsSent++;
        headsSent += raid.sent;
        ForestDirector.get(level).note(String.format(Locale.ROOT, "前線から襲う部隊を出した(拠点 #%d・%s・%d 体・前線 %d・撃退 %d 回 → ×%.2f)",
                b.id(), scale.name(), raid.sent, from.size(), b.repelled(), factor));
        return raid.sent;
    }

    /** 前線 f から出せる頭数(守りの下限を超えた守り + 広げる。仮想の頭数だけ)。 */
    static int raidable(VirtualHordes hordes, Fronts.Front f, Long2IntOpenHashMap real) {
        FrontTable t = DesignData.front();
        int total = 0, defend = 0, defendVirtual = 0, expand = 0;
        for (VirtualHordes.Swarm s : hordes.swarms()) {
            if (s.front() != f.id) continue;
            int n = s.size() + real.get(s.id());
            total += n;
            if (s.purpose() == UnitPurpose.DEFEND) {
                defend += n;
                defendVirtual += s.size();
            } else if (s.purpose() == UnitPurpose.EXPAND) {
                expand += s.size();
            }
        }
        int floor = (int) Math.ceil(t.defendShareQuiet * total);
        return Math.max(0, Math.min(defendVirtual, defend - floor)) + expand;
    }

    /** 前線 f の部隊から want 体を抜き、襲う部隊(上限 max ずつ)にして (gx, gz) へ向かわせる。抜いた数を返す。 */
    static int pull(ServerLevel level, VirtualHordes hordes, Fronts.Front f, Long2IntOpenHashMap real, int want, int max, Raid raid, double gx, double gz, long now) {
        FrontTable t = DesignData.front();
        int total = 0, defend = 0;
        List<VirtualHordes.Swarm> src = new ArrayList<>();
        for (VirtualHordes.Swarm s : hordes.swarms()) {
            if (s.front() != f.id) continue;
            int n = s.size() + real.get(s.id());
            total += n;
            if (s.purpose() == UnitPurpose.DEFEND) defend += n;
            if ((s.purpose() == UnitPurpose.DEFEND || s.purpose() == UnitPurpose.EXPAND) && s.size() > 0) src.add(s);
        }
        int defendFloor = (int) Math.ceil(t.defendShareQuiet * total);
        // 広げる部隊から先に、次に守り(下限まで)。拠点に近い部隊から
        src.sort(Comparator.<VirtualHordes.Swarm>comparingInt(s -> s.purpose() == UnitPurpose.EXPAND ? 0 : 1)
                .thenComparingDouble(s -> (s.x() - gx) * (s.x() - gx) + (s.z() - gz) * (s.z() - gz)));
        int got = 0;
        VirtualHordes.Swarm into = null;
        for (VirtualHordes.Swarm s : src) {
            if (got >= want) break;
            int can = s.size();
            if (s.purpose() == UnitPurpose.DEFEND) can = Math.min(can, defend - defendFloor);
            int take = Math.min(can, want - got);
            while (take > 0) {
                if (into == null || into.size() >= max) {
                    into = hordes.newUnit(s.x(), s.y(), s.z(), f.id, UnitPurpose.RAID, now);
                    into.setHunt(raid.group());
                    into.setBase(raid.baseId);
                    into.assign(UnitPurpose.RAID, gx, gz, now);
                }
                int k = Math.min(take, max - into.size());
                double paidBefore = s.paid();
                var heads = s.takeHeads(k);
                int n = heads.values().stream().mapToInt(Integer::intValue).sum();
                if (n <= 0) break;
                for (var e : heads.entrySet()) into.addHeads(e.getKey(), e.getValue(), 0.0D);
                into.paidAdd(paidBefore - s.paid());
                if (s.purpose() == UnitPurpose.DEFEND) defend -= n;
                take -= n;
                got += n;
            }
        }
        hordes.touch();
        return got;
    }

    // ---------------------------------------------------------------- まわす

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 40 != 21) return;
        ServerLevel level = server.overworld();
        if (!active(level)) return;
        tick(level);
    }

    /** 1 回まわす(テストからも)。 */
    public static void tick(ServerLevel level) {
        FrontRaids data = get(level.getServer());
        if (data.raids.isEmpty()) return;
        VirtualHordes hordes = VirtualHordes.get(level.getServer());
        BaseMemory memory = BaseMemory.get(level.getServer());
        double day = BaseMemory.today(level.getServer());
        Long2IntOpenHashMap real = FrontDirector.realByUnit(level);
        FrontTable t = DesignData.front();
        for (Raid r : new ArrayList<>(data.raids)) {
            List<VirtualHordes.Swarm> units = unitsOf(hordes, r);
            int alive = 0;
            for (VirtualHordes.Swarm s : units) alive += s.size() + real.get(s.id());
            BaseMemory.Base b = memory.byId(r.baseId);
            if (alive <= 0 || b == null) {
                finish(level, data, memory, r, b, units, day, alive <= 0 ? "全部倒された" : "拠点を忘れた");
                continue;
            }
            if (r.gathering) {
                boolean allThere = units.stream().allMatch(VirtualHordes.Swarm::arrived);
                boolean largeWaits = r.scale == RaidScales.RaidScale.LARGE && ForestDirector.get(level).mood() == ForestDirector.Mood.LARGE_OMEN;
                boolean timeUp = day - r.startDay >= t.raidGatherMaxDays;
                if (!largeWaits && (allThere || timeUp)) advance(level, hordes, r, b, units, day);
            } else if (!Double.isNaN(r.advanceDay) && day - r.advanceDay > RaidScales.maxDays(r.scale, 1.0D)) {
                finish(level, data, memory, r, b, units, day, "時間切れ");
                continue;
            }
            ferryIfBlocked(level, hordes, r, b, units);
        }
    }

    static List<VirtualHordes.Swarm> unitsOf(VirtualHordes hordes, Raid r) {
        UUID g = r.group();
        List<VirtualHordes.Swarm> out = new ArrayList<>();
        for (VirtualHordes.Swarm s : hordes.swarms()) if (s.purpose() == UnitPurpose.RAID && g.equals(s.hunt())) out.add(s);
        return out;
    }

    /** 集まる所から一緒に拠点へ。 */
    static void advance(ServerLevel level, VirtualHordes hordes, Raid r, BaseMemory.Base b, List<VirtualHordes.Swarm> units, double day) {
        long now = level.getGameTime();
        r.gathering = false;
        r.advanceDay = day;
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (VirtualHordes.Swarm s : units) {
            s.assign(UnitPurpose.RAID, b.centerX(), b.centerZ(), now);
            ids.add(s.id());
        }
        for (Entity e : level.getAllEntities()) {
            if (e instanceof Mob m && m.isAlive() && m.getPersistentData().contains(VirtualHordes.UNIT_KEY)
                    && ids.contains(m.getPersistentData().getLong(VirtualHordes.UNIT_KEY))) VirtualHordes.marchTo(m, b.id(), b.centerX(), b.centerZ());
        }
        hordes.touch();
        get(level.getServer()).setDirty();
    }

    /** 終わる: 撃退なら拠点に覚えさせ、残った部隊は前線の守りへ戻す。大きな襲撃なら森の気分も終える。 */
    static void finish(ServerLevel level, FrontRaids data, BaseMemory memory, Raid r, @Nullable BaseMemory.Base b, List<VirtualHordes.Swarm> units,
                       double day, String why) {
        FrontTable t = DesignData.front();
        boolean repelled = r.sent > 0 && r.dead >= r.sent * t.raidLossRepelShare;
        if (repelled && b != null) {
            memory.addRepelled(b);
            repels++;
        }
        Fronts fronts = Fronts.get(level.getServer());
        for (VirtualHordes.Swarm s : units) {
            Fronts.Front f = fronts.byId(s.front());
            double[] post = f != null ? FrontDirector.post(level, f, level.getRandom()) : new double[]{s.x(), s.z()};
            s.setHunt(null);
            s.assign(UnitPurpose.DEFEND, post[0], post[1], level.getGameTime());
        }
        if (r.scale == RaidScales.RaidScale.LARGE && b != null && ForestDirector.get(level).largeRaidTarget() == b.id()
                && ForestDirector.get(level).mood() == ForestDirector.Mood.LARGE) {
            LargeRaids.end(level, day, "前線の部隊の襲撃が終わった(" + why + ")");
        }
        data.raids.remove(r);
        data.setDirty();
        VirtualHordes.get(level.getServer()).touch();
        ForestDirector.get(level).note(String.format(Locale.ROOT, "前線の襲撃が終わった(%s・出した %d・倒された %d%s)", why, r.sent, r.dead, repelled ? "・撃退" : ""));
    }

    /** 部隊の id → {前に見た x, z, 止まっていた回数}(保存しない)。 */
    private static final Map<Long, double[]> STALL = new HashMap<>();
    /** この回数続けて(2 秒ごと)ほとんど進まず、着いてもいなければ「阻まれた」とみなす【仮】。 */
    public static final int STALL_CHECKS = 5;

    /** 拠点へ向かっているのに進めていないか(水に阻まれて回り道も尽きた・水の上で行ったり来たり)。 */
    public static boolean stalled(VirtualHordes.Swarm s) {
        if (s.arrived()) {
            STALL.remove(s.id());
            return false;
        }
        double[] p = STALL.get(s.id());
        if (p == null || Math.abs(p[0] - s.x()) + Math.abs(p[1] - s.z()) > 2.0D) {
            STALL.put(s.id(), new double[]{s.x(), s.z(), 0});
            return false;
        }
        p[2]++;
        return p[2] >= STALL_CHECKS;
    }

    /** 島の拠点: 水に阻まれて止まった部隊を、拠点が読み込まれていれば流れ木もどきで運ぶ。 */
    static void ferryIfBlocked(ServerLevel level, VirtualHordes hordes, Raid r, BaseMemory.Base b, List<VirtualHordes.Swarm> units) {
        if (r.gathering) return;
        for (VirtualHordes.Swarm s : units) {
            if (s.size() <= 0 || !(s.blocked() || stalled(s))) continue;
            if (!level.isPositionEntityTicking(BlockPos.containing(b.centerX(), 64.0D, b.centerZ()))) continue;
            int n = com.yayuy.overgrownvegetation.drift.DriftRaids.ferry(level, b.centerX(), b.centerZ(), level.getRandom(), s.size(), 0.0D, (feet, random) -> {
                Mob m = hordes.materializeOne(level, s, feet, random);
                if (m != null && !level.addFreshEntity(m)) return null;
                return m;
            }, m -> VirtualHordes.marchTo(m, b.id(), b.centerX(), b.centerZ()));
            ferried += n;
        }
    }

    /** 襲う部隊の実物が倒された: その襲撃の倒された数に足す。 */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!(e.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!e.getPersistentData().contains(VirtualHordes.UNIT_KEY)) return;
        if (!UnitPurpose.RAID.name().equals(e.getPersistentData().getString("ov_unit_purpose"))) return;
        VirtualHordes.Swarm s = VirtualHordes.get(level.getServer()).byId(e.getPersistentData().getLong(VirtualHordes.UNIT_KEY));
        if (s == null || s.hunt() == null) return;
        for (Raid r : get(level.getServer()).raids) {
            if (r.group().equals(s.hunt())) {
                r.dead++;
                get(level.getServer()).setDirty();
                return;
            }
        }
    }

    /** 今の襲撃の 1 行(確かめ用)。 */
    public static String describe(ServerLevel level) {
        FrontRaids data = get(level.getServer());
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "前線の襲撃: %s・今 %d 回・これまで 出した %d 回 %d 体・撃退 %d・流れ木もどきで運んだ %d",
                active(level) ? "入" : "切", data.raids.size(), raidsSent, headsSent, repels, ferried));
        for (Raid r : data.raids) {
            sb.append(String.format(Locale.ROOT, " / #%d 拠点 #%d %s %s 出した %d 倒された %d", r.id, r.baseId, r.scale.name(), r.gathering ? "集まり中" : "攻め中", r.sent, r.dead));
        }
        return sb.toString();
    }

    /** テスト用: 全部忘れる。 */
    public void clearForTests() {
        raids.clear();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Raid r : raids) {
            CompoundTag c = new CompoundTag();
            c.putLong("Id", r.id);
            c.putInt("Base", r.baseId);
            c.putString("Scale", r.scale.name());
            c.putDouble("GX", r.gatherX);
            c.putDouble("GZ", r.gatherZ);
            c.putDouble("Start", r.startDay);
            c.putBoolean("Gather", r.gathering);
            c.putDouble("Advance", r.advanceDay);
            c.putInt("Sent", r.sent);
            c.putInt("Dead", r.dead);
            list.add(c);
        }
        tag.put("Raids", list);
        tag.putLong("NextId", nextId);
        return tag;
    }

    public static FrontRaids load(CompoundTag tag) {
        FrontRaids d = new FrontRaids();
        d.nextId = Math.max(1L, tag.getLong("NextId"));
        for (Tag t : tag.getList("Raids", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            RaidScales.RaidScale scale;
            try {
                scale = RaidScales.RaidScale.valueOf(c.getString("Scale"));
            } catch (IllegalArgumentException ex) {
                scale = RaidScales.RaidScale.SMALL;
            }
            Raid r = new Raid(c.getLong("Id"), c.getInt("Base"), scale, c.getDouble("GX"), c.getDouble("GZ"), c.getDouble("Start"));
            r.gathering = c.getBoolean("Gather");
            r.advanceDay = c.getDouble("Advance");
            r.sent = c.getInt("Sent");
            r.dead = c.getInt("Dead");
            d.raids.add(r);
            d.nextId = Math.max(d.nextId, r.id + 1);
        }
        return d;
    }
}
