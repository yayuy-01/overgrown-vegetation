package com.yayuy.overgrownvegetation.spawn;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.crawler.CrawlerPack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 司令塔がたまった Mob を群れにまとめる(2026-11-17 深夜 ユーザー決定。正本は Vault「敵の湧き方」の【決定 11-17 深夜】)。
 * F7 の前線で Mob 207 体・1 tick 40〜50 ms、Mob の動きが約 30%(経路探索が重い)。上限を超えて物量で来るのは許し、そのぶん群れで軽くする。
 * <ul>
 *   <li>{@code merge.intervalTicks} おきに、プレイヤー(観戦を除く)のまわり {@code scanBlocks} の、種の表に役割(role)がある地上の個体を
 *   {@code cellBlocks} の升に分ける。相手のいない・拠点へ歩いていない・群れに入っていない個体が「浮いている」。</li>
 *   <li>升に外の群れ(CrawlerPack)がいくつかあれば、大きい方へ 1 つにまとめる(大きさの上限なし)。升に群れがあれば、浮いている個体をそこへ足す。
 *   群れが無く、浮いている個体が {@code minMobs} 以上なら新しい群れにする(並びは役割の順 = 前衛が先導役)。</li>
 *   <li>それでも浮いている個体は、まわり 8 升の群れのうち、その個体の役割が比 {@code ratio} に足りていない群れへ入れる(足りない役割を優先)。</li>
 *   <li>入れない: 根付き・苗床・根付いた・腰を据えたゲスタトリクス(役割が無い・{@link CrawlerPack#anchored})・霧の中で立ち止まっている G5・名札つき・空の種
 *   (空は飛ぶ型の Flock のまま。上限を外すのは後)。</li>
 * </ul>
 * 後続の動き(経路を探さず持ち場へ寄る)と先導役の交代は {@link CrawlerPack}。流れの場の波は作らない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PackMerger {

    private PackMerger() {
    }

    /** 確かめ用: 作った群れ・足した個体・群れどうしをまとめた回数・かかった時間。 */
    public static long formed, added, merged, runs, nanos;
    /** テストで世界の tick から動かす。 */
    public static boolean tickInGameTests;
    /** テスト用: 升の大きさの上書き(テスト場が升の境をまたがないように。null で戻す)。 */
    public static Double cellOverride;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!tickInGameTests && level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) return;
        SpawnTable.Merge t = DesignData.spawns().merge;
        if (!t.enabled || level.getGameTime() % t.intervalTicks != 0) return;
        Set<Mob> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            run(level, p.getX(), p.getY(), p.getZ(), seen);
        }
    }

    /** 群れに入れてよい種と状態か(相手がいる・歩かされている個体も、入っている群れからは外さない)。 */
    static boolean eligible(Mob m) {
        if (!m.isAlive() || m.hasCustomName() || m.isPassenger() || DirectorHooks.roleOf(m.getType()) == null) return false;
        if (CrawlerPack.anchored(m)) return false;
        return !(m.getTags().contains(FogLurkers.TAG) && !m.getTags().contains(FogLurkers.WOKE_TAG));
    }

    /** 動いている(相手のプレイヤーを見つけた・司令塔が歩かせている)。11-18 ユーザー A: まとめるのは群れが動くときだけ。 */
    static boolean moving(Mob m) {
        return m.getTarget() instanceof net.minecraft.world.entity.player.Player || PlantMarch.active(m);
    }

    /** 浮いている(群れに入っていない・動いている)。 */
    static boolean loose(Mob m) {
        return CrawlerPack.externalOf(m) == null && moving(m);
    }

    /** 1 人ぶん(テスト・命令からも)。seen は同じ回にほかのプレイヤーで見た個体(二度見ない)。 */
    public static void run(ServerLevel level, double x, double y, double z, Set<Mob> seen) {
        long t0 = System.nanoTime();
        SpawnTable.Merge t = DesignData.spawns().merge;
        double cellSize = cellOverride != null ? cellOverride : t.cellBlocks;
        double join = t.joinBlocks, join2 = join * join;
        double r = t.scanBlocks;
        AABB box = new AABB(x - r, y - 32.0D, z - r, x + r, y + 32.0D, z + r);
        Map<Long, List<Mob>> looseByCell = new HashMap<>();
        Map<Long, List<CrawlerPack>> packsByCell = new HashMap<>();
        for (Mob m : level.getEntitiesOfClass(Mob.class, box, PackMerger::eligibleType)) {
            if (!seen.add(m)) continue;
            if (CrawlerPack.anchored(m)) {
                if (CrawlerPack.externalOf(m) != null) CrawlerPack.leaveExternal(level, m);
                continue;
            }
            if (!eligible(m)) continue;
            long cell = cell(m.getX(), m.getZ(), cellSize);
            CrawlerPack p = CrawlerPack.externalOf(m);
            if (p != null) {
                if (p.leader() == m) packsByCell.computeIfAbsent(cell, k -> new ArrayList<>(2)).add(p);
            } else if (loose(m)) {
                looseByCell.computeIfAbsent(cell, k -> new ArrayList<>()).add(m);
            }
        }
        // 同じ升の群れで、先導役どうしが join 以内のものを 1 つにまとめる
        for (List<CrawlerPack> ps : packsByCell.values()) {
            for (int i = 0; i < ps.size(); i++) {
                for (int j = i + 1; j < ps.size(); j++) {
                    CrawlerPack a = ps.get(i), b = ps.get(j);
                    if (a.size() == 0 || b.size() == 0 || a.leader().distanceToSqr(b.leader()) > join2) continue;
                    CrawlerPack big = CrawlerPack.mergeExternal(level, a, b);
                    ps.set(i, big);
                    ps.set(j, big == a ? b : a);
                    merged++;
                }
            }
            ps.removeIf(p -> p.size() == 0);
        }
        // 動いている浮いた個体: まわり 9 升の群れのうち、先導役から join 以内で、その役割がいちばん足りない群れへ
        List<Mob> left = new ArrayList<>();
        for (List<Mob> ms : looseByCell.values()) {
            for (Mob m : ms) {
                int role = DirectorHooks.roleOf(m.getType()).ordinal();
                long c = cell(m.getX(), m.getZ(), cellSize);
                int cx = (int) (c >> 32), cz = (int) c;
                CrawlerPack best = null;
                double bestNeed = Double.NEGATIVE_INFINITY;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        List<CrawlerPack> ps = packsByCell.get(key(cx + dx, cz + dz));
                        if (ps == null) continue;
                        for (CrawlerPack p : ps) {
                            if (p.size() == 0 || p.leader().distanceToSqr(m) > join2) continue;
                            double need = deficit(p, role, t.ratio);
                            if (need > bestNeed) {
                                best = p;
                                bestNeed = need;
                            }
                        }
                    }
                }
                if (best != null) {
                    CrawlerPack.addExternal(level, best, m);
                    added++;
                } else {
                    left.add(m);
                }
            }
        }
        // 残り: 先導役(役割の順)から join 以内に minMobs 以上いれば、新しい群れにする
        left.sort(java.util.Comparator.comparingInt(m -> DirectorHooks.roleOf(m.getType()).ordinal()));
        Set<Mob> taken = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Mob lead : left) {
            if (taken.contains(lead)) continue;
            List<Mob> near = new ArrayList<>();
            near.add(lead);
            for (Mob m : left) {
                if (m != lead && !taken.contains(m) && m.distanceToSqr(lead) <= join2) near.add(m);
            }
            if (near.size() < t.minMobs) continue;
            taken.addAll(near);
            CrawlerPack.formExternal(level, near);
            formed++;
            added += near.size();
        }
        runs++;
        nanos += System.nanoTime() - t0;
    }

    /** 箱の検索の絞り(役割のある種だけ。状態は後で見る)。 */
    private static boolean eligibleType(Mob m) {
        return DirectorHooks.roleOf(m.getType()) != null;
    }

    /** 群れの中でその役割がどれだけ足りないか(比どおりの数 − 今の数。正なら足りない)。 */
    static double deficit(CrawlerPack p, int role, int[] ratio) {
        int sum = 0;
        for (int v : ratio) sum += v;
        if (sum <= 0) return 0.0D;
        int[] c = p.roleCounts();
        int n = 0;
        for (int v : c) n += v;
        return (n + 1) * (double) ratio[role] / sum - c[role];
    }

    static long cell(double x, double z, double size) {
        return key((int) Math.floor(x / size), (int) Math.floor(z / size));
    }

    static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** 確かめ用の 1 行。 */
    public static String describe() {
        return String.format(java.util.Locale.ROOT, "まとめ: 作った群れ %d・足した個体 %d・群れどうし %d・1 回 平均 %.3f ms(%d 回)",
                formed, added, merged, runs == 0 ? 0.0D : nanos / 1e6 / runs, runs);
    }
}
