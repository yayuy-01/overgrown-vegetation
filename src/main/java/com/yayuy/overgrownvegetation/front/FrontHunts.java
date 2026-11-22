package com.yayuy.overgrownvegetation.front;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.FrontTable;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestVision;
import com.yayuy.overgrownvegetation.horde.UnitPurpose;
import com.yayuy.overgrownvegetation.horde.VirtualHordes;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>狩り</b>(2026-11-21 前線と部隊 区切り 3。正本は Vault「前線と部隊(地図の上の森の軍勢)(11-21)」の 7)。
 * <ul>
 *   <li>森に見られた({@link ForestVision#sees}: 偵察・森の縁・監視塔・地脈)プレイヤーへ、近くの部隊から抜き出して狩る部隊を作り、向かわせる。合図は無し(ユーザー)。</li>
 *   <li>守りが下限(静かな前線の守りの割合)を割る前線の守りからは抜かない。広げる部隊からは抜ける。数はフェーズの上限まで。</li>
 *   <li>見えている間は的の今の所へ、見失ったら最後に見た所へ行き、そこで huntSearchSeconds 探して、見つからなければ前線の守りへ戻る。</li>
 *   <li>同じプレイヤーには、狩りが終わってから huntCooldownSeconds あける。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class FrontHunts {

    /** プレイヤーごとの狩りの覚え(保存しない: 再起動すると狩る部隊は「見失った」扱いで戻る)。 */
    static final class Hunt {
        double lastX, lastZ;
        long lastSeen = Long.MIN_VALUE;
        long cooldownUntil = Long.MIN_VALUE;
        /** 着いてから探し始めた時刻。 */
        long searchSince = Long.MIN_VALUE;
    }

    private static final Map<UUID, Hunt> HUNTS = new HashMap<>();
    /** テスト用: 見られているかの決め打ち(null で本物の {@link ForestVision#sees})。 */
    @Nullable
    public static java.util.function.Predicate<ServerPlayer> seenForTests;
    public static long started, pulled, ended;

    private FrontHunts() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 40 != 13) return;
        if (!FrontDirector.tickInGameTests && server instanceof net.minecraft.gametest.framework.GameTestServer) return;
        if (!FrontDirector.enabled()) return;
        tick(server.overworld(), server.overworld().players());
    }

    @SubscribeEvent
    public static void onStopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        HUNTS.clear();
    }

    public static boolean seen(ServerLevel level, ServerPlayer p) {
        if (seenForTests != null) return seenForTests.test(p);
        // 11-22 ユーザー決定 B 案: 森の中で行動して気づかれたら見つかる
        if (ForestAwareness.noticed(level, p)) return true;
        ForestVision.Why why = ForestVision.why(level, p.blockPosition());
        // awareInsideNeedsNotice: 管理された森の中(過繁茂のチャンクの上)は森の縁の視界では見つからない(気づきだけ)。偵察・監視塔・地脈はそのまま
        if (why == ForestVision.Why.FOREST && DesignData.front().awareInsideNeedsNotice
                && com.yayuy.overgrownvegetation.phase.PhaseData.get(level).isOvergrownKey(net.minecraft.world.level.ChunkPos.asLong(p.getBlockX() >> 4, p.getBlockZ() >> 4))) {
            return false;
        }
        return why != ForestVision.Why.NONE;
    }

    /** 1 回まわす(テストからも。players は狩りの的になる人)。 */
    public static void tick(ServerLevel level, List<ServerPlayer> players) {
        VirtualHordes hordes = VirtualHordes.get(level.getServer());
        long now = level.getGameTime();
        FrontTable t = DesignData.front();
        for (ServerPlayer p : players) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
            Hunt h = HUNTS.computeIfAbsent(p.getUUID(), k -> new Hunt());
            boolean seen = seen(level, p);
            if (seen) {
                h.lastX = p.getX();
                h.lastZ = p.getZ();
                h.lastSeen = now;
                h.searchSince = Long.MIN_VALUE;
            }
            List<VirtualHordes.Swarm> mine = huntersOf(hordes, p.getUUID());
            if (mine.isEmpty()) {
                if (seen && now >= h.cooldownUntil) start(level, hordes, p, h);
                continue;
            }
            // 狩っている: 見えていれば今の所へ、見失っていれば最後に見た所へ
            boolean lost = now - h.lastSeen > (long) (t.huntLostSeconds * 20.0D);
            boolean arrived = mine.stream().allMatch(VirtualHordes.Swarm::arrived);
            if (lost && arrived) {
                if (h.searchSince == Long.MIN_VALUE) h.searchSince = now;
                if (now - h.searchSince > (long) (t.huntSearchSeconds * 20.0D)) {
                    end(level, hordes, mine);
                    h.cooldownUntil = now + (long) (t.huntCooldownSeconds * 20.0D);
                    continue;
                }
            }
            for (VirtualHordes.Swarm s : mine) {
                if (Math.abs(s.tx() - h.lastX) > 2.0D || Math.abs(s.tz() - h.lastZ) > 2.0D) s.assign(UnitPurpose.HUNT, h.lastX, h.lastZ, s.purposeSince());
            }
            retargetReal(level, mine, h.lastX, h.lastZ);
            hordes.touch();
        }
        // 的がいなくなった(抜けた・死んだ)狩りは戻す
        for (VirtualHordes.Swarm s : new ArrayList<>(hordes.swarms())) {
            if (s.purpose() != UnitPurpose.HUNT) continue;
            UUID u = s.hunt();
            boolean present = false;
            for (ServerPlayer p : players) if (p.getUUID().equals(u) && p.isAlive() && !p.isSpectator() && !p.isCreative()) present = true;
            if (!present) end(level, hordes, List.of(s));
        }
    }

    static List<VirtualHordes.Swarm> huntersOf(VirtualHordes hordes, UUID u) {
        List<VirtualHordes.Swarm> out = new ArrayList<>();
        for (VirtualHordes.Swarm s : hordes.swarms()) if (s.purpose() == UnitPurpose.HUNT && u.equals(s.hunt())) out.add(s);
        return out;
    }

    /** 近くの部隊から抜き出して狩る部隊を作る。抜けた頭数を返す。 */
    static int start(ServerLevel level, VirtualHordes hordes, ServerPlayer p, Hunt h) {
        FrontTable t = DesignData.front();
        int want = t.huntMax(DirectorGates.phase(level));
        if (want <= 0) return 0;
        Fronts fronts = Fronts.get(level.getServer());
        Long2IntOpenHashMap real = FrontDirector.realByUnit(level);
        // 前線ごとの守りの頭数と下限
        Map<Integer, int[]> defend = new HashMap<>(); // front → {守り, 合計}
        Map<Integer, Double> value = new HashMap<>(); // front → 払った額の合計(攻められたかの判定)
        for (VirtualHordes.Swarm s : hordes.swarms()) {
            if (!s.unit()) continue;
            int[] a = defend.computeIfAbsent(s.front(), k -> new int[2]);
            value.merge(s.front(), s.paid(), Double::sum);
            int n = s.size() + real.get(s.id());
            a[1] += n;
            if (s.purpose() == UnitPurpose.DEFEND) a[0] += n;
        }
        double range = t.huntRangeBlocks;
        List<VirtualHordes.Swarm> cands = new ArrayList<>();
        for (VirtualHordes.Swarm s : hordes.swarms()) {
            if (!s.unit() || s.size() <= 0) continue;
            if (s.purpose() != UnitPurpose.DEFEND && s.purpose() != UnitPurpose.EXPAND) continue;
            if (!FrontDirector.near(s, p.getX(), p.getZ(), range)) continue;
            cands.add(s);
        }
        cands.sort(Comparator.comparingDouble(s -> (s.x() - p.getX()) * (s.x() - p.getX()) + (s.z() - p.getZ()) * (s.z() - p.getZ())));
        int got = 0;
        long now = level.getGameTime();
        for (VirtualHordes.Swarm s : cands) {
            if (got >= want) break;
            int can = s.size();
            if (s.purpose() == UnitPurpose.DEFEND) {
                int[] a = defend.get(s.front());
                Fronts.Front f = fronts.byId(s.front());
                double floor = (f != null && FrontDirector.attacked(f, value.getOrDefault(s.front(), 0.0D)) ? t.defendShareAttacked : t.defendShareQuiet) * a[1];
                can = Math.min(can, (int) Math.floor(a[0] - floor));
                if (can <= 0) continue;
            }
            int take = Math.min(can, want - got);
            double paidBefore = s.paid();
            var heads = s.takeHeads(take);
            int n = heads.values().stream().mapToInt(Integer::intValue).sum();
            if (n <= 0) continue;
            VirtualHordes.Swarm u = hordes.newUnit(s.x(), s.y(), s.z(), s.front(), UnitPurpose.HUNT, now);
            for (var e : heads.entrySet()) u.addHeads(e.getKey(), e.getValue(), 0.0D);
            u.paidAdd(paidBefore - s.paid());
            u.setHunt(p.getUUID());
            u.assign(UnitPurpose.HUNT, h.lastX, h.lastZ, now);
            if (s.purpose() == UnitPurpose.DEFEND) defend.get(s.front())[0] -= n;
            got += n;
        }
        if (got > 0) {
            started++;
            pulled += got;
            hordes.touch();
        }
        return got;
    }

    /** 狩りを終える: 前線の守りへ戻す(実物はそのまま。遠くなれば部隊へ戻る)。 */
    static void end(ServerLevel level, VirtualHordes hordes, List<VirtualHordes.Swarm> units) {
        Fronts fronts = Fronts.get(level.getServer());
        for (VirtualHordes.Swarm s : units) {
            Fronts.Front f = fronts.byId(s.front());
            double[] post = f != null ? FrontDirector.post(level, f, level.getRandom()) : new double[]{s.x(), s.z()};
            s.setHunt(null);
            s.assign(UnitPurpose.DEFEND, post[0], post[1], level.getGameTime());
            ended++;
        }
        // 実物の目的も守りに(行進をやめ、プレイヤーから遠ければ部隊へ戻る)
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (VirtualHordes.Swarm s : units) ids.add(s.id());
        for (Entity e : level.getAllEntities()) {
            if (e instanceof Mob m && m.isAlive() && ids.contains(m.getPersistentData().getLong(VirtualHordes.UNIT_KEY))
                    && m.getPersistentData().contains(VirtualHordes.UNIT_KEY)) {
                m.getPersistentData().putString("ov_unit_purpose", UnitPurpose.DEFEND.name());
            }
        }
        hordes.touch();
    }

    /** 狩る部隊の実物を的へ行進させ直す。 */
    static void retargetReal(ServerLevel level, List<VirtualHordes.Swarm> units, double x, double z) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (VirtualHordes.Swarm s : units) ids.add(s.id());
        for (Entity e : level.getAllEntities()) {
            if (e instanceof Mob m && m.isAlive() && m.getPersistentData().contains(VirtualHordes.UNIT_KEY)
                    && ids.contains(m.getPersistentData().getLong(VirtualHordes.UNIT_KEY)) && m.getTarget() == null) {
                VirtualHordes.marchTo(m, -1, x, z);
            }
        }
    }

    /** テスト用: その人の「最後に見た時刻」と「探し始めた時刻」を ticks だけ昔にする。 */
    public static void ageForTests(UUID u, long ticks) {
        Hunt h = HUNTS.get(u);
        if (h == null) return;
        if (h.lastSeen != Long.MIN_VALUE) h.lastSeen -= ticks;
        if (h.searchSince != Long.MIN_VALUE) h.searchSince -= ticks;
    }

    /** テスト用: 覚えを消す。 */
    public static void clearForTests() {
        HUNTS.clear();
    }

    /** テスト用: 同じプレイヤーにあける時刻(無ければ Long.MIN_VALUE)。 */
    public static long cooldownUntil(UUID u) {
        Hunt h = HUNTS.get(u);
        return h == null ? Long.MIN_VALUE : h.cooldownUntil;
    }
}
