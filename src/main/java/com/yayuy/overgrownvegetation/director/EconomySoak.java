package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EconomyTable;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>計測用</b>: F7 までの通しの放置で、森の稼ぎと襲撃の規模を測る(2026-11-20 全体管理の依頼 economy-f7-soak-brief.md)。
 * {@code /ov perf g1 director soak start|stop|status} で動かす。動いている間だけ:
 * <ul>
 *   <li>森の予算から払われるたびに、呼び元(クラス.メソッド)ごとに足す({@link #spent}。{@link ForestDirector#tryPay} から)。</li>
 *   <li>20 tick ごとに、稼ぎの内訳(森の √・井戸・巨大樹)を時間ぶん積む。感染の稼ぎ・森の中の戻り・消えた戻りは {@link ForestEconomy} の数の差。</li>
 *   <li>襲撃は常にすべて撃退した扱い: 拠点へ向かう個体(G1 の行進・植物側の行進・貯めた襲撃・大きな襲撃の印)を、見つけたらプレイヤーの攻撃で倒す
 *   (森の外なら払った分は戻らない・撃破の点は入る)。倒した数を種類ごとに数える。</li>
 *   <li>プレイヤーは倒れない・腹が減らない(計測の間だけ)。</li>
 *   <li>ゲーム内の 1 日(フェーズの暦)ごとに、ゲームフォルダの {@code soak/economy-days.csv} へ 1 行。送り込みは {@code soak/raids.csv} へ 1 回 1 行。</li>
 * </ul>
 * 遊びには使わない(既定は止まっている)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class EconomySoak {

    private EconomySoak() {
    }

    public static volatile boolean on;
    private static long dayIndex = -1L;
    private static final Map<String, Double> SPENT = new TreeMap<>();
    private static double incForest, incLey, incTree;
    private static double base0Infect, base0Death, base0Despawn;
    private static final long[] repelled = new long[6]; // 送り込み・貯めた・大きな・植物側の行進・前線の襲う部隊・前線の狩る部隊(11-21)
    private static long tickSum, tickMax, tickCount, tickStart;
    private static Path dir;
    // 2 回目(11-21): 湧かせる所で弾いた数(理由ごと)・広げた段・流れ木もどき・襲撃の流れ(見送り・小・中・大)を日ごとに
    private static final long[] base0Miss = new long[3];
    private static final long[] base0Widen = new long[4];
    private static int base0Ferried;
    private static final long[] decisions = new long[5]; // 見送り・小・中・大・導入で小
    private static double maxSavedDays;
    private static long largeBegun, largeOmen;
    /** G1Fate から払う道(ForestDirector#visit が払う前に置く。払ったら消す)。 */
    static volatile String payRoute;

    // ---------------------------------------------------------------- 口

    /** 払われた(ForestDirector#tryPay が払えたとき)。呼び元を 1 つ数える。 */
    static void spent(double amount) {
        if (!on || amount <= 0.0D) return;
        String who = StackWalker.getInstance().walk(s -> s
                .filter(f -> !f.getClassName().endsWith(".ForestDirector") && !f.getClassName().endsWith(".EconomySoak"))
                .findFirst().map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName()).orElse("?"));
        String route = payRoute;
        if (route != null && who.startsWith("G1Fate.")) who = who + ":" + route; // 「G1 の道ほか」の中身(進化・根付き・苗床)
        synchronized (SPENT) {
            SPENT.merge(who, amount, Double::sum);
        }
    }

    /** 11-22 樹域から出した襲撃 1 回・継ぎ足し 1 回(FrontRaids から)。どの樹域から何体出したか(from = "#樹域の id=体数 …")・上限。 */
    public static void frontRaid(ServerLevel level, long raidId, String kind, int baseId, String pick, String from, int sent, int cap,
                                 int srcHeads, int srcTarget, int srcRaidable) {
        if (!on || dir == null) return;
        // 11-23 D: 出した樹域の 頭数・目標・出せる頭数の合計(抜く前)。from の各樹域にも (h/t/r) を付けた
        append("front-raids.csv", "day,phase,raid,kind,base,pick,sent,cap,srcHeads,srcTarget,srcRaidable,from\n",
                String.format(Locale.ROOT, "%.3f,%d,%d,%s,%d,%s,%d,%d,%d,%d,%d,%s%n",
                PhaseData.clockNow(level) / 24000.0D, PhaseData.get(level).getPhase(), raidId, kind, baseId, pick, sent,
                cap >= Integer.MAX_VALUE / 4 ? -1 : cap, srcHeads, srcTarget, srcRaidable, from));
    }

    /** 送り込み 1 回(BaseRaids から)。 */
    static void wave(ServerLevel level, BaseRaids.Result r, boolean hoard, long[] missBefore, long[] widenBefore, int ferriedBefore) {
        if (!on || dir == null) return;
        long[] m = RaidSpawns.lastMisses, w = RaidSpawns.lastWidened;
        String line = String.format(Locale.ROOT, "%.3f,%d,%s,%s,%d,%d,%d,%d,%d,%d,%.1f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n", PhaseData.clockNow(level) / 24000.0D,
                PhaseData.get(level).getPhase(), r.first() ? "first" : hoard ? "hoard" : "wave", r.first() ? "y" : "n",
                r.total(), r.stageZero(), r.developed() + r.evolved(), r.g2(), r.g3plus(), r.spawnedZero(), ForestDirector.get(level).available(level),
                r.wantStageZero(), r.wantDeveloped(), r.wantG2(), r.wantG3plus(),
                m[0] - missBefore[0], m[1] - missBefore[1], m[2] - missBefore[2],
                w[0] - widenBefore[0], w[1] - widenBefore[1], w[2] - widenBefore[2], w[3] - widenBefore[3],
                com.yayuy.overgrownvegetation.drift.DriftRaids.ferried - ferriedBefore);
        append("raids.csv", "day,phase,kind,first,total,stage0,developed,g2,g3plus,spawned0,budgetAfter,"
                + "wantStage0,wantDeveloped,wantG2,wantG3plus,missUnloaded,missGround,missSeen,foundSpread,found90,found360,foundNone,ferries\n", line);
    }

    /** 襲撃の機会ごとの決め(RaidScales#skipOrStart から)。 */
    static void decision(boolean skip, RaidScales.RaidScale scale, boolean introSmall, double savedDays) {
        if (!on) return;
        if (skip) decisions[0]++;
        else if (introSmall) decisions[4]++;
        else decisions[1 + scale.ordinal()]++;
        maxSavedDays = Math.max(maxSavedDays, savedDays);
    }

    /** 大きな襲撃: 前触れに入った(omen = true)・本番が始まった。 */
    static void large(boolean omen) {
        if (!on) return;
        if (omen) largeOmen++;
        else largeBegun++;
    }

    // ---------------------------------------------------------------- 動かす

    public static String start(MinecraftServer server) {
        dir = server.getServerDirectory().resolve("soak");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return "フォルダを作れない: " + e.getMessage();
        }
        on = true;
        dayIndex = -1L;
        resetDay();
        return "計測を始めた: " + dir.toAbsolutePath();
    }

    public static String stop() {
        on = false;
        return "計測を止めた";
    }

    public static String status(ServerLevel level) {
        return String.format(Locale.ROOT, "計測 %s・%d 日目・撃退 送り込み %d / 貯めた %d / 大きな %d / 植物の行進 %d", on ? "中" : "止まっている",
                dayIndex, repelled[0], repelled[1], repelled[2], repelled[3]) + ringWater(level);
    }

    /**
     * 拠点ごとの、流れ木もどきの道が島とみなすかの輪(大きな襲撃の湧かせる距離の真ん中・24 点)の水の割合(11-21: ferry が 0 体だったのが仕様どおりかの確かめ)。
     * 島とみなすのは 読めた点が 1/3 以上で 水が {@link com.yayuy.overgrownvegetation.drift.DriftRaids#WATER_SHARE} 以上。
     */
    private static String ringWater(ServerLevel level) {
        var t = DesignData.director().largeRaid;
        double r = (t.spawnMinBlocks + t.spawnMaxBlocks) * 0.5D;
        StringBuilder sb = new StringBuilder();
        for (BaseMemory.Base b : BaseMemory.get(level.getServer()).bases()) {
            int water = 0, known = 0;
            for (int i = 0; i < 24; i++) {
                double a = 2.0D * Math.PI * i / 24;
                var c = com.yayuy.overgrownvegetation.drift.DriftShore.column(level, net.minecraft.util.Mth.floor(b.centerX() + Math.cos(a) * r),
                        net.minecraft.util.Mth.floor(b.centerZ() + Math.sin(a) * r), level.getSeaLevel() - 1);
                if (c == com.yayuy.overgrownvegetation.drift.DriftShore.Col.UNKNOWN) continue;
                known++;
                if (c == com.yayuy.overgrownvegetation.drift.DriftShore.Col.WATER) water++;
            }
            sb.append(String.format(Locale.ROOT, "・拠点 #%d (%.0f, %.0f) の輪(半径 %.0f)の水 %d / 読めた %d → %s", b.id(), b.centerX(), b.centerZ(), r, water, known,
                    com.yayuy.overgrownvegetation.drift.DriftRaids.acrossWater(level, b.centerX(), b.centerZ(),
                            new int[]{(int) t.spawnMinBlocks, (int) t.spawnMaxBlocks, level.getSeaLevel() - 1, DesignData.drift().drift.minDepth}) ? "島" : "島ではない"));
        }
        return sb.toString();
    }

    private static void resetDay() {
        synchronized (SPENT) {
            SPENT.clear();
        }
        incForest = incLey = incTree = 0.0D;
        base0Infect = ForestEconomy.gainedInfect;
        base0Death = ForestEconomy.refundedDeath;
        base0Despawn = ForestEconomy.refundedDespawn;
        java.util.Arrays.fill(repelled, 0L);
        System.arraycopy(RaidSpawns.lastMisses, 0, base0Miss, 0, 3);
        System.arraycopy(RaidSpawns.lastWidened, 0, base0Widen, 0, 4);
        base0Ferried = com.yayuy.overgrownvegetation.drift.DriftRaids.ferried;
        java.util.Arrays.fill(decisions, 0L);
        maxSavedDays = 0.0D;
        largeBegun = largeOmen = 0L;
        tickSum = tickMax = tickCount = 0L;
        prevOverflow = StorageOverflow.total;
        prevOverflowSpore = StorageOverflow.sporeTotal;
    }

    /** 11-22 tick の最大の訳を見る: 1 tick が SPIKE_MS を超えたら、見張りのスレッドがサーバーのスレッドの今の積み重ねを soak/spikes.txt に書く(計測の間だけ)。 */
    private static final long SPIKE_MS = 500L;
    private static volatile Thread serverThread;
    private static volatile long spikeTickStart, spikeDumpedFor = -1L;
    private static Thread watchdog;

    @SubscribeEvent
    public static void onTickPre(ServerTickEvent.Pre event) {
        if (!on) return;
        tickStart = System.nanoTime();
        spikeTickStart = tickStart;
        serverThread = Thread.currentThread();
        startWatchdog();
    }

    private static synchronized void startWatchdog() {
        if (watchdog != null && watchdog.isAlive()) return;
        watchdog = new Thread(() -> {
            while (on) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    return;
                }
                long st = spikeTickStart;
                Thread t = serverThread;
                long ms = (System.nanoTime() - st) / 1_000_000L;
                if (t == null || st == spikeDumpedFor || ms < SPIKE_MS) continue;
                spikeDumpedFor = st;
                StringBuilder sb = new StringBuilder("--- tick ").append(ms).append(" ms で見た積み重ね(").append(java.time.LocalTime.now()).append(")\n");
                StackTraceElement[] es = t.getStackTrace();
                for (int i = 0; i < Math.min(40, es.length); i++) sb.append("  ").append(es[i]).append('\n');
                append("spikes.txt", "", sb.toString());
            }
        }, "ov-soak-spike-watch");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    @SubscribeEvent
    public static void onTickPost(ServerTickEvent.Post event) {
        if (!on) return;
        long dt = System.nanoTime() - tickStart;
        tickSum += dt;
        tickMax = Math.max(tickMax, dt);
        tickCount++;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        if (server.getTickCount() % 20 != 0) return;
        // 稼ぎの内訳を 20 tick ぶん積む
        ForestEconomy.Sources s = ForestEconomy.sources(level);
        var e = ForestEconomy.table();
        double k = 20.0D / 24000.0D;
        incForest += s.forestPerDay() * k; // 11-21: 前線ごとの森の稼ぎの合計
        incLey += s.leyPerDay() * k; // 井戸の上乗せ(自分の前線の稼ぎ × 割合)
        incTree += s.treePerDay() * k;
        // プレイヤーは倒れない・腹が減らない
        for (ServerPlayer p : level.players()) {
            p.setInvulnerable(true);
            p.getFoodData().setFoodLevel(20);
            p.setHealth(p.getMaxHealth());
        }
        repel(level);
        long day = PhaseData.clockNow(level) / 24000L;
        if (dayIndex < 0L) dayIndex = day;
        if (day != dayIndex) {
            writeDay(level, dayIndex);
            dayIndex = day;
            resetDay();
        }
    }

    /** 追い払いで倒している間だけ真(KillPoints が撃破の点を入れない)。 */
    public static boolean repelling;

    /** 拠点へ向かう個体を、見つけたらプレイヤーの攻撃で倒す(撃退した扱い)。 */
    private static void repel(ServerLevel level) {
        ServerPlayer p = level.players().isEmpty() ? null : level.players().get(0);
        if (p == null) return;
        AABB box = p.getBoundingBox().inflate(256.0D, 128.0D, 256.0D);
        for (Mob m : level.getEntitiesOfClass(Mob.class, box, Entity::isAlive)) {
            int kind = -1;
            String purpose = m.getPersistentData().getString("ov_unit_purpose");
            if ("RAID".equals(purpose)) kind = 4;
            else if ("HUNT".equals(purpose)) kind = 5;
            else if (m.getPersistentData().contains(com.yayuy.overgrownvegetation.horde.VirtualHordes.UNIT_KEY)) kind = -1; // 前線の住人(守る・広げる・建てる)は撃退の数に入れない
            else if (m.getTags().contains(LargeRaids.ATTACKER_TAG)) kind = 2;
            else if (m.getTags().contains(HoardRaids.TAG)) kind = 1;
            else if (m instanceof AbstractParasitizedMonster a && a.march() != null && a.march().active()) kind = 0;
            else if (com.yayuy.overgrownvegetation.spawn.PlantMarch.active(m)) kind = 3;
            if (kind < 0) continue;
            // 11-22: 計測の追い払いは撃破の点に入れない(1 万体の大襲撃を倒すと閾値の何倍もの点が入り、フェーズの日を測れない)。倒したのはプレイヤーのまま(偵察・森の覚えのため)
            repelling = true;
            try {
                m.hurt(level.damageSources().playerAttack(p), Float.MAX_VALUE);
            } finally {
                repelling = false;
            }
            if (!m.isAlive()) repelled[kind]++;
        }
    }

    private static void writeDay(ServerLevel level, long day) {
        PhaseData pd = PhaseData.get(level);
        ForestEconomy.Sources s = ForestEconomy.sources(level);
        ForestDirector dir0 = ForestDirector.get(level);
        StringBuilder spent = new StringBuilder();
        double spentTotal = 0.0D;
        synchronized (SPENT) {
            for (Map.Entry<String, Double> en : SPENT.entrySet()) {
                if (spent.length() > 0) spent.append(' ');
                spent.append(en.getKey()).append('=').append(String.format(Locale.ROOT, "%.1f", en.getValue()));
                spentTotal += en.getValue();
            }
        }
        double avgMs = tickCount == 0 ? 0.0D : tickSum / 1.0E6D / tickCount;
        // 器官の数(種類ごと: 育ち切った+育っている。大小は合わせる)と亀裂の数(場所だけ決めた / 開いた / 枯れた)。どれも台帳・データの上の数
        StringBuilder organs = new StringBuilder();
        for (com.yayuy.overgrownvegetation.organ.OrganKind k : com.yayuy.overgrownvegetation.organ.OrganKind.values()) {
            int alive = 0, growing = 0;
            for (com.yayuy.overgrownvegetation.organ.ForestOrgans.Organ o : com.yayuy.overgrownvegetation.organ.ForestOrgans.get(level).organs()) {
                if (o.kind != k) continue;
                if (o.active()) alive++;
                else if (o.state == com.yayuy.overgrownvegetation.organ.ForestOrgans.State.GROWING) growing++;
            }
            organs.append(k.id).append('=').append(alive).append('+').append(growing).append(' ');
        }
        int[] fis = new int[3];
        for (var fz : com.yayuy.overgrownvegetation.corruption.fissure.FissureData.get(level).fissures()) fis[fz.state.ordinal()]++;
        // 11-21 前線と部隊: 前線の数・部隊の頭数(目的ごと)・維持費・作った数・襲撃・巨大樹
        int[] heads = new int[com.yayuy.overgrownvegetation.horde.UnitPurpose.values().length];
        int units = 0;
        for (var sw : com.yayuy.overgrownvegetation.horde.VirtualHordes.get(level.getServer()).swarms()) {
            if (!sw.unit()) continue;
            units++;
            heads[sw.purpose().ordinal()] += sw.size();
        }
        String front = String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%d,%d,%.1f,%d,%d,%d,%d,%d,%d,%d,%d",
                com.yayuy.overgrownvegetation.front.Fronts.get(level.getServer()).fronts().size(), units,
                heads[com.yayuy.overgrownvegetation.horde.UnitPurpose.DEFEND.ordinal()], heads[com.yayuy.overgrownvegetation.horde.UnitPurpose.EXPAND.ordinal()],
                heads[com.yayuy.overgrownvegetation.horde.UnitPurpose.HUNT.ordinal()], heads[com.yayuy.overgrownvegetation.horde.UnitPurpose.RAID.ordinal()],
                heads[com.yayuy.overgrownvegetation.horde.UnitPurpose.BUILD.ordinal()], ForestUpkeep.perDay(level), ForestUpkeep.heads(level),
                com.yayuy.overgrownvegetation.front.FrontDirector.produced, com.yayuy.overgrownvegetation.front.FrontRaids.raidsSent,
                com.yayuy.overgrownvegetation.front.FrontRaids.headsSent, com.yayuy.overgrownvegetation.front.FrontRaids.repels,
                com.yayuy.overgrownvegetation.front.FrontTrees.placed, repelled[4], repelled[5]);
        String line = String.format(Locale.ROOT, "%d,%d,%s,%d,%d,%d,%d,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%d,%d,%d,%d,%.2f,%.1f,%d,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%d,%d,%d,%s,\"%s\"%n",
                day, pd.getPhase(), pd.getStage().key(), pd.getPoints(), s.chunks(), s.leyBig() + s.leySmall(), s.giantTrees(),
                pd.hiveNodeSnapshot().length,
                incForest, incLey, incTree, ForestEconomy.gainedInfect - base0Infect, ForestEconomy.refundedDeath - base0Death,
                ForestEconomy.refundedDespawn - base0Despawn, spentTotal, dir0.available(level), ForestDirector.cap(level),
                repelled[0], repelled[1], repelled[2], repelled[3], avgMs, tickMax / 1.0E6D,
                countEntities(level), organs.toString().trim(), fis[0], fis[1], fis[2], 
                RaidSpawns.lastMisses[0] - base0Miss[0], RaidSpawns.lastMisses[1] - base0Miss[1], RaidSpawns.lastMisses[2] - base0Miss[2],
                RaidSpawns.lastWidened[0] - base0Widen[0], RaidSpawns.lastWidened[1] - base0Widen[1], RaidSpawns.lastWidened[2] - base0Widen[2],
                RaidSpawns.lastWidened[3] - base0Widen[3], com.yayuy.overgrownvegetation.drift.DriftRaids.ferried - base0Ferried,
                decisions[0], decisions[1], decisions[2], decisions[3], decisions[4], maxSavedDays, largeOmen, largeBegun, LargeRaids.scaledTotal,
                front + "," + expandStats() + "," + String.format(Locale.ROOT, "%.1f", com.yayuy.overgrownvegetation.front.FrontDirector.reserve(level)) + "," + com.yayuy.overgrownvegetation.front.FrontDirector.targetStats(com.yayuy.overgrownvegetation.front.Fronts.get(level.getServer())) + "," + storageStats(level) + "," + treeStats(level) + "," + organLimits(level) + "," + unitSpread(level) + "," + baseStages(level),
                spent);
        append("economy-days.csv",
                "day,phase,stage,points,overgrownChunks,leyOrgans,giantTrees,hiveNodes,incForest,incLey,incTree,incInfect,refundDeath,refundDespawn,"
                        + "spentTotal,budget,cap,repelWave,repelHoard,repelLarge,repelPlantMarch,tickAvgMs,tickMaxMs,entities,organs(alive+growing),fissurePending,fissureOpen,fissureDead,"
                        + "missUnloaded,missGround,missSeen,foundSpread,found90,found360,foundNone,ferries,"
                        + "skip,small,medium,large,introSmall,maxSavedDays,largeOmen,largeBegun,largeScaledTotal,"
                        + "fronts,units,headsDefend,headsExpand,headsHunt,headsRaid,headsBuild,upkeepPerDay,upkeepHeads,produced,frontRaids,frontRaidHeads,"
                        + "frontRepels,treesPlaced,repelFrontRaid,repelHunt,expandSends,expandDistAvg,expandDistMax,expandArrivals,expandArriveDaysAvg,expandArriveDaysMax,reserve,targetHeads,frontsAtTarget,atTargetSkips,nurseryAtTargetSkips,capBase,capHive,capSac,sacs,sacsSmall,overflow,overflowSpore,overflowRecent,treeManagedShare,edgeToTreeMedian,edgeToTreeP90,treeSitesPending,edgeSeenShare,frontWatchers,frontWatchersGrown,frontWatchersReal,frontWatchersKilled,organLimit,organLimitSmall,organsLiving,organsLivingSmall,defendEdgeShare,defendEdgeShareEdgeFronts,defendInEdgeFronts,overTargetHeads,overTargetFronts,overBefore,overShrunkBefore,rebalancedHeads,rebalancedUnits,postEdge,postTree,reposted,reinforcedHeads,reinforcedUnits,reinforceReturned,reinforceCalls,reinforceNoEdge,reinforceNoInner,reinforceNoRoom,raidLossesSkipped,outposts,outpostsExpand,outpostsDefend,outpostsReal,rootedExpand,rootedDefend,rootedMoved,outpostsWithered,outpostsKilled,outpostRefunded,expandInfected,absorbed,headsPerTarget,globalCapSkips,nurseryGlobalCapSkips,routedInfected,bases,spentByCaller\n", line);
    }

    /** 広げる部隊を出した数・距離(ブロック)の平均と最大・着いた数・着くまでの日数の平均と最大(累計。11-21 夜)。 */
    private static String expandStats() {
        long n = com.yayuy.overgrownvegetation.front.FrontDirector.expandSends, a = com.yayuy.overgrownvegetation.front.FrontDirector.expandArrivals;
        return String.format(Locale.ROOT, "%d,%.0f,%.0f,%d,%.2f,%.2f", n,
                n > 0 ? com.yayuy.overgrownvegetation.front.FrontDirector.expandDistSum / n : 0.0D, com.yayuy.overgrownvegetation.front.FrontDirector.expandDistMax,
                a, a > 0 ? com.yayuy.overgrownvegetation.front.FrontDirector.expandArriveDaysSum / a : 0.0D, com.yayuy.overgrownvegetation.front.FrontDirector.expandArriveDaysMax);
    }

    /** 拠点ごとの「段/送った回数/次の送り込みの日」(11-21 前線の襲撃が出ない訳を見る)。 */
    /** 11-22 貯蓄の上限の内訳(基礎値・ハイヴノード分・蓄えの嚢分)・嚢の数・この日あふれた量・この日胞子に回した量(胞子の場の単位)。 */
    private static double prevOverflow, prevOverflowSpore;

    private static String storageStats(ServerLevel level) {
        var t = DesignData.director();
        double hives = com.yayuy.overgrownvegetation.front.FrontTrees.livingTreeCount(level); // 11-22: 生きている巨大樹 = ハイヴノード
        double sacs = com.yayuy.overgrownvegetation.organ.OrganWorld.sacHiveEquivalent(level);
        var organs = com.yayuy.overgrownvegetation.organ.ForestOrgans.get(level);
        int sacBig = organs.livingCount(com.yayuy.overgrownvegetation.organ.OrganKind.SAC, false), sacSmall = organs.livingCount(com.yayuy.overgrownvegetation.organ.OrganKind.SAC, true);
        double over = StorageOverflow.total - prevOverflow, spore = StorageOverflow.sporeTotal - prevOverflowSpore;
        prevOverflow = StorageOverflow.total;
        prevOverflowSpore = StorageOverflow.sporeTotal;
        return String.format(Locale.ROOT, "%.0f,%.0f,%.0f,%d,%d,%.1f,%.1f,%.1f", t.capBase, t.capPerHiveNode * hives, t.capPerHiveNode * sacs, sacBig, sacSmall,
                over, spore, StorageOverflow.recent());
    }

    /**
     * 11-22 全体管理２の頼み(前線に巨大樹が追い付いているか): 過繁茂のうち生きている巨大樹の管理の中(treeManageBlocks)の割合・
     * 森の縁の升(4×4 チャンク。真ん中のチャンクが過繁茂でなく、上下左右の升のどれかの真ん中が過繁茂)の真ん中から最寄りの生きている巨大樹までの距離の中央値と 9 割の点(ブロック)・
     * 巨大樹の予定地のうち FUNDING の数。巨大樹が無ければ距離は空。決め方は試算(director-sim-report の 3 回目)とそろえた。生きている巨大樹 = FrontTrees.managed と同じ(置く待ちも含む)。
     */
    private static String treeStats(ServerLevel level) {
        PhaseData pd = PhaseData.get(level);
        it.unimi.dsi.fastutil.longs.LongArrayList trees = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (long k : pd.giantTreeOriginSnapshot()) if (!pd.isGiantTreeDead(k)) trees.add(k);
        for (long k : com.yayuy.overgrownvegetation.front.FrontTrees.pendingTreeKeys(level)) if (!trees.contains(k)) trees.add(k);
        int n = trees.size();
        double[] tx = new double[n], tz = new double[n];
        for (int i = 0; i < n; i++) {
            tx[i] = (int) (trees.getLong(i) >> 32) + 0.5D;
            tz[i] = (int) trees.getLong(i) + 0.5D;
        }
        double r2 = DesignData.front().treeManageBlocks * DesignData.front().treeManageBlocks;
        long[] total = {0L}, managed = {0L};
        // 縁の升(試算 director-sim-report の 3 回目と同じ決め方): 4×4 チャンクの升で、真ん中のチャンク(cx×4+2, cz×4+2)が過繁茂でなく、
        // 上下左右の升のどれかの真ん中が過繁茂(FrontDirector.cellForest・nextToForest と同じ形。生き物・水は見ない)
        it.unimi.dsi.fastutil.longs.LongOpenHashSet edgeCells = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        // 管理の中の判定は、巨大樹を管理の広さの升に分けて近くの升だけ見る(25 日目で過繁茂 100 万・巨大樹 600 本でも 1 日 1 回で軽く)
        double bucket = Math.max(16.0D, DesignData.front().treeManageBlocks);
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.ints.IntArrayList> grid = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        for (int i = 0; i < n; i++) grid.computeIfAbsent(ChunkPos.asLong((int) Math.floor(tx[i] / bucket), (int) Math.floor(tz[i] / bucket)), q -> new it.unimi.dsi.fastutil.ints.IntArrayList()).add(i);
        pd.forEachOvergrownChunk(k -> {
            total[0]++;
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            double x = cx * 16.0D + 8.0D, z = cz * 16.0D + 8.0D;
            int bx = (int) Math.floor(x / bucket), bz = (int) Math.floor(z / bucket);
            boolean in = false;
            for (int ox = -1; ox <= 1 && !in; ox++) {
                for (int oz = -1; oz <= 1 && !in; oz++) {
                    var list = grid.get(ChunkPos.asLong(bx + ox, bz + oz));
                    if (list == null) continue;
                    for (int q = 0; q < list.size(); q++) {
                        int i = list.getInt(q);
                        double dx = tx[i] - x, dz = tz[i] - z;
                        if (dx * dx + dz * dz <= r2) {
                            in = true;
                            break;
                        }
                    }
                }
            }
            if (in) managed[0]++;
            if (Math.floorMod(cx, 4) != 2 || Math.floorMod(cz, 4) != 2) return; // 升の真ん中のチャンクだけ
            int gx = Math.floorDiv(cx, 4), gz = Math.floorDiv(cz, 4);
            for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = gx + o[0], nz = gz + o[1];
                if (!pd.isOvergrownKey(ChunkPos.asLong(nx * 4 + 2, nz * 4 + 2))) edgeCells.add(ChunkPos.asLong(nx, nz));
            }
        });
        // 11-22 縁の升のうち森の視界(ForestVision。森の縁 64・偵察・見張り・前線のフィロセンス・地脈)の中にある割合(多ければ 5000 升まで間引く)
        int seenStride = Math.max(1, edgeCells.size() / 5000), seenAll = 0, seenIn = 0, idx = 0;
        for (long c : edgeCells) {
            if (idx++ % seenStride != 0) continue;
            seenAll++;
            if (ForestVision.sees(level, new net.minecraft.core.BlockPos(ChunkPos.getX(c) * 64 + 32, 64, ChunkPos.getZ(c) * 64 + 32))) seenIn++;
        }
        String seenShare = seenAll > 0 ? String.format(Locale.ROOT, "%.3f", (double) seenIn / seenAll) : "";
        String median = "", p90 = ""; // 巨大樹が無ければ空(試算と同じ)
        if (n > 0 && !edgeCells.isEmpty()) {
            double[] a = new double[edgeCells.size()];
            int j = 0;
            for (long c : edgeCells) {
                double x = ChunkPos.getX(c) * 64.0D + 32.0D, z = ChunkPos.getZ(c) * 64.0D + 32.0D; // 升の真ん中から巨大樹の根元まで
                double best = Double.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    double dx = tx[i] - x, dz = tz[i] - z, d = dx * dx + dz * dz;
                    if (d < best) best = d;
                }
                a[j++] = Math.sqrt(best);
            }
            java.util.Arrays.sort(a);
            median = String.valueOf(Math.round(a[(int) Math.floor(a.length * 0.5D)]));
            p90 = String.valueOf(Math.round(a[Math.min(a.length - 1, (int) Math.floor(a.length * 0.9D))]));
        }
        int funding = 0;
        for (var s : com.yayuy.overgrownvegetation.front.FrontTrees.get(level.getServer()).sites()) if (s.state() == com.yayuy.overgrownvegetation.front.FrontTrees.State.FUNDING) funding++;
        return String.format(Locale.ROOT, "%.3f,%s,%s,%d,%s,%s", total[0] > 0 ? (double) managed[0] / total[0] : 0.0D, median, p90, funding, seenShare,
                com.yayuy.overgrownvegetation.front.FrontWatchers.stats(level));
    }

    /** 11-22 器官の数の上限(フェーズの数 + 生きている巨大樹 × 大 1・小 2 × 器官の数の倍率)と、今生きている数(乗っ取りの器官も含む)。 */
    /** 11-22 守りの持ち場(縁の割合)・目標を超えた頭数・移した頭数・感染と取り込みで増えた頭数(累計)。 */
    private static String unitSpread(ServerLevel level) {
        return com.yayuy.overgrownvegetation.front.FrontDirector.defendEdgeStats(com.yayuy.overgrownvegetation.horde.VirtualHordes.get(level.getServer())) + "," + com.yayuy.overgrownvegetation.front.FrontDirector.overTargetStats(com.yayuy.overgrownvegetation.front.Fronts.get(level.getServer()))
                + "," + com.yayuy.overgrownvegetation.front.FrontDirector.overBefore + "," + com.yayuy.overgrownvegetation.front.FrontDirector.overShrunkBefore
                + "," + com.yayuy.overgrownvegetation.front.FrontDirector.rebalancedHeads + "," + com.yayuy.overgrownvegetation.front.FrontDirector.rebalancedUnits
                + "," + com.yayuy.overgrownvegetation.front.FrontDirector.postEdge + "," + com.yayuy.overgrownvegetation.front.FrontDirector.postTree + "," + com.yayuy.overgrownvegetation.front.FrontDirector.reposted + "," + com.yayuy.overgrownvegetation.front.FrontDirector.reinforcedHeads + "," + com.yayuy.overgrownvegetation.front.FrontDirector.reinforcedUnits + "," + com.yayuy.overgrownvegetation.front.FrontDirector.reinforceReturned + "," + com.yayuy.overgrownvegetation.front.FrontDirector.reinforceCalls + "," + com.yayuy.overgrownvegetation.front.FrontDirector.reinforceNoEdge + "," + com.yayuy.overgrownvegetation.front.FrontDirector.reinforceNoInner + "," + com.yayuy.overgrownvegetation.front.FrontDirector.reinforceNoRoom + "," + com.yayuy.overgrownvegetation.front.FrontDirector.raidLossesSkipped + "," + com.yayuy.overgrownvegetation.front.FrontOutposts.stats(level)
                + "," + com.yayuy.overgrownvegetation.front.FrontDirector.expandInfected + "," + com.yayuy.overgrownvegetation.front.FrontDirector.absorbed
                + "," + String.format(Locale.ROOT, "%.3f", com.yayuy.overgrownvegetation.front.FrontDirector.globalRatio(com.yayuy.overgrownvegetation.front.Fronts.get(level.getServer())))
                + "," + com.yayuy.overgrownvegetation.front.FrontDirector.globalCapSkips + "," + com.yayuy.overgrownvegetation.front.FrontDirector.nurseryGlobalCapSkips + "," + com.yayuy.overgrownvegetation.front.FrontDirector.routedInfected;
    }

    private static String organLimits(ServerLevel level) {
        var organs = com.yayuy.overgrownvegetation.organ.ForestOrgans.get(level);
        return com.yayuy.overgrownvegetation.organ.OrganPlanner.limit(level, false) + "," + com.yayuy.overgrownvegetation.organ.OrganPlanner.limit(level, true) + ","
                + organs.livingCount(false) + "," + organs.livingCount(true);
    }

    private static String baseStages(ServerLevel level) {
        StringBuilder sb = new StringBuilder();
        for (BaseMemory.Base b : BaseMemory.get(level.getServer()).bases()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(b.stage().name(), 0, 3).append('/').append(b.wavesSent()).append('/').append(String.format(Locale.ROOT, "%.2f", b.nextWaveDay()));
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static int countEntities(ServerLevel level) {
        int n = 0;
        for (Entity ignored : level.getAllEntities()) n++;
        return n;
    }

    private static void append(String file, String header, String line) {
        if (dir == null) return;
        Path f = dir.resolve(file);
        try {
            if (!Files.exists(f)) Files.writeString(f, header, StandardCharsets.UTF_8);
            Files.writeString(f, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.warn("計測の書き出しに失敗: {}", e.toString());
        }
    }
}
