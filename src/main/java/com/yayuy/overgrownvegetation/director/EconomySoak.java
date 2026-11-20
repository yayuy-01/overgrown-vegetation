package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.phase.PhaseData;
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
    private static final long[] repelled = new long[4]; // 送り込み・貯めた・大きな・植物側の行進
    private static long tickSum, tickMax, tickCount, tickStart;
    private static Path dir;

    // ---------------------------------------------------------------- 口

    /** 払われた(ForestDirector#tryPay が払えたとき)。呼び元を 1 つ数える。 */
    static void spent(double amount) {
        if (!on || amount <= 0.0D) return;
        String who = StackWalker.getInstance().walk(s -> s
                .filter(f -> !f.getClassName().endsWith(".ForestDirector") && !f.getClassName().endsWith(".EconomySoak"))
                .findFirst().map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName()).orElse("?"));
        synchronized (SPENT) {
            SPENT.merge(who, amount, Double::sum);
        }
    }

    /** 送り込み 1 回(BaseRaids から)。 */
    static void wave(ServerLevel level, BaseRaids.Result r, boolean hoard) {
        if (!on || dir == null) return;
        String line = String.format(Locale.ROOT, "%.3f,%d,%s,%s,%d,%d,%d,%d,%d,%d,%.1f%n", PhaseData.clockNow(level) / 24000.0D,
                PhaseData.get(level).getPhase(), r.first() ? "first" : hoard ? "hoard" : "wave", r.first() ? "y" : "n",
                r.total(), r.stageZero(), r.developed() + r.evolved(), r.g4(), r.g5plus(), r.spawnedZero(), ForestDirector.get(level).available(level));
        append("raids.csv", "day,phase,kind,first,total,stage0,developed,g4,g5plus,spawned0,budgetAfter\n", line);
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
                dayIndex, repelled[0], repelled[1], repelled[2], repelled[3]);
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
        tickSum = tickMax = tickCount = 0L;
    }

    @SubscribeEvent
    public static void onTickPre(ServerTickEvent.Pre event) {
        if (on) tickStart = System.nanoTime();
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
        incForest += e.chunkSqrtPerDay * Math.sqrt(s.chunks()) * k;
        incLey += (e.leyPerDay * s.leyBig() + e.leySmallPerDay * s.leySmall()) * k;
        incTree += e.treePerDay * s.giantTrees() * k;
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

    /** 拠点へ向かう個体を、見つけたらプレイヤーの攻撃で倒す(撃退した扱い)。 */
    private static void repel(ServerLevel level) {
        ServerPlayer p = level.players().isEmpty() ? null : level.players().get(0);
        if (p == null) return;
        AABB box = p.getBoundingBox().inflate(256.0D, 128.0D, 256.0D);
        for (Mob m : level.getEntitiesOfClass(Mob.class, box, Entity::isAlive)) {
            int kind = -1;
            if (m.getTags().contains(LargeRaids.ATTACKER_TAG)) kind = 2;
            else if (m.getTags().contains(HoardRaids.TAG)) kind = 1;
            else if (m instanceof AbstractParasitizedMonster a && a.march() != null && a.march().active()) kind = 0;
            else if (com.yayuy.overgrownvegetation.spawn.PlantMarch.active(m)) kind = 3;
            if (kind < 0) continue;
            m.hurt(level.damageSources().playerAttack(p), Float.MAX_VALUE);
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
        String line = String.format(Locale.ROOT, "%d,%d,%s,%d,%d,%d,%d,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%d,%d,%d,%d,%.2f,%.1f,%d,\"%s\"%n",
                day, pd.getPhase(), pd.getStage().key(), pd.getPoints(), s.chunks(), s.leyBig() + s.leySmall(), s.giantTrees(),
                pd.hiveNodeSnapshot().length,
                incForest, incLey, incTree, ForestEconomy.gainedInfect - base0Infect, ForestEconomy.refundedDeath - base0Death,
                ForestEconomy.refundedDespawn - base0Despawn, spentTotal, dir0.available(level), ForestDirector.cap(level),
                repelled[0], repelled[1], repelled[2], repelled[3], avgMs, tickMax / 1.0E6D,
                countEntities(level), spent);
        append("economy-days.csv",
                "day,phase,stage,points,overgrownChunks,leyOrgans,giantTrees,hiveNodes,incForest,incLey,incTree,incInfect,refundDeath,refundDespawn,"
                        + "spentTotal,budget,cap,repelWave,repelHoard,repelLarge,repelPlantMarch,tickAvgMs,tickMaxMs,entities,spentByCaller\n", line);
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
