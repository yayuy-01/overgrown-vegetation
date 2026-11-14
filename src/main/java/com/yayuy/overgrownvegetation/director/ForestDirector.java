package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.DirectorTable;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>森の知性(司令塔)</b> —— 世界に 1 つの「世界の意思」(2026-10-22 ユーザー決定。Mob ではなくワールドの保存データ)。
 * 0.2.0 の第 1 段(2026-11-13)。仕様の正本は Vault「森の知性(司令塔)」の「【決定】0.2.0 の司令塔(11-13)」。
 *
 * <ul>
 *   <li><b>森の予算</b>({@link ForestBudget}): 過繁茂のチャンク(台帳 {@link PhaseData#overgrownLedgerSize})が多いほど速く貯まり、
 *   ハイヴノード({@link PhaseData#hiveNodeSnapshot})の数で上限が上がる。毎 tick は数えず、聞かれたときに前回からの時間ぶんを足す。</li>
 *   <li><b>判断</b>({@link #visit}): 段階 0 の個体が自分の「見に来る時刻」({@link G1Fate})になったら呼ぶ。そのときの状況で
 *   進化 / 何もしない を点で決め、進化なら予算を払う(根付き・苗床は区切り 2 以降でここへ足す)。経路探索はしない・サーバースレッドで軽く。</li>
 * </ul>
 * 数はどれも {@link DirectorTable}({@code director.json})の【仮】。拠点の記憶・学ぶ・貯めて使う・弱い所を突くは第 2・3 段。
 */
public final class ForestDirector extends SavedData implements ForestBudget {

    private static final String ID = "overgrown_vegetation_forest_director";

    public static final SavedData.Factory<ForestDirector> FACTORY =
            new SavedData.Factory<>(ForestDirector::new, (tag, registries) -> load(tag), null);

    /** 司令塔が個体に決める道。保存は名前で。 */
    public enum Route {
        NONE("まだ何もしない"), EVOLVE("進化"), ROOTED("根付き"), NURSERY("苗床");

        public final String label;

        Route(String label) {
            this.label = label;
        }

        public static Route byName(String name) {
            for (Route r : values()) {
                if (r.name().equals(name)) return r;
            }
            return NONE;
        }
    }

    /** 判断の入力(テストは作って {@link #score} に渡す)。 */
    public record Inputs(boolean canEvolve, boolean canRoot, boolean canNursery, double budget, double playerDistance, double ageDays,
                         double forestDistance) {
        /** 苗床を選べない個体(区切り 5 までのテストの形)。 */
        public Inputs(boolean canEvolve, boolean canRoot, double budget, double playerDistance, double ageDays) {
            this(canEvolve, canRoot, false, budget, playerDistance, ageDays, 0.0D);
        }

        /** 森の中(縁からの距離 0)にいる個体。 */
        public Inputs(boolean canEvolve, boolean canRoot, boolean canNursery, double budget, double playerDistance, double ageDays) {
            this(canEvolve, canRoot, canNursery, budget, playerDistance, ageDays, 0.0D);
        }
    }

    /** 判断の結果と点(選べない道は負の無限大)。 */
    public record Decision(Route route, double nothing, double evolve, double rooted, double nursery, String summary) {
    }

    /** その道になるときに払う値段。 */
    public static double cost(DirectorTable t, Route route) {
        return switch (route) {
            case EVOLVE -> t.costEvolve;
            case ROOTED -> t.costRooted;
            case NURSERY -> t.costNursery;
            case NONE -> 0.0D;
        };
    }

    private double budget;
    /** 予算を最後に数えたゲーム時刻(−1 = まだ)。 */
    private long budgetAt = -1L;
    /** これまでの判断の数({@link Route} の順)。 */
    private final long[] decided = new long[Route.values().length];
    /** 直近の判断(保存しない。{@code /ov perf g1 director} で見る)。 */
    private final ArrayDeque<String> recent = new ArrayDeque<>();

    private ForestDirector() {
    }

    public static ForestDirector get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public static ForestDirector get(ServerLevel level) {
        return get(level.getServer());
    }

    // ---------------------------------------------------------------- 予算

    /** 1 日に貯まる量(今の過繁茂のチャンク数から)。 */
    public static double incomePerDay(ServerLevel level) {
        return DesignData.director().incomePerDay(PhaseData.get(level).overgrownLedgerSize());
    }

    /** 貯蓄の上限(今のハイヴノードの数から)。 */
    public static double cap(ServerLevel level) {
        return DesignData.director().cap(PhaseData.get(level).hiveNodeSnapshot().length);
    }

    /**
     * 前回から {@code ticks} たった後の予算(純粋な計算)。上限を超えていた分は切る(ハイヴノードが倒されて上限が下がったとき)。
     */
    public static double accrued(double budget, long ticks, double incomePerDay, double cap) {
        double grown = budget + incomePerDay * Math.max(0L, ticks) / 24000.0D;
        return Math.max(0.0D, Math.min(cap, grown));
    }

    private void accrue(ServerLevel level) {
        long now = level.getServer().overworld().getGameTime();
        if (budgetAt < 0L) {
            budgetAt = now;
            setDirty();
            return;
        }
        if (now == budgetAt) return;
        if (DesignData.director().enabled) {
            budget = accrued(budget, now - budgetAt, incomePerDay(level), cap(level));
        }
        budgetAt = now;
        setDirty();
    }

    @Override
    public double available(ServerLevel level) {
        accrue(level);
        return budget;
    }

    @Override
    public boolean tryPay(ServerLevel level, double cost) {
        accrue(level);
        if (cost > budget + 1.0E-9D) return false;
        budget = Math.max(0.0D, budget - cost);
        setDirty();
        return true;
    }

    @Override
    public void refund(ServerLevel level, double amount) {
        accrue(level);
        budget = Math.min(Math.max(budget, cap(level)), budget + Math.max(0.0D, amount));
        setDirty();
    }

    /** 予算をこの値にする(確かめ用のコマンドとテスト。上限は見ない —— 次に数えるとき上限へ切られる)。 */
    public void setBudget(ServerLevel level, double value) {
        accrue(level);
        budget = Math.max(0.0D, value);
        setDirty();
    }

    // ---------------------------------------------------------------- 判断

    /**
     * 点を付けて道を選ぶ(純粋な計算。乱数は揺らぎだけ)。
     * 何もしない = {@code nothingBase}。進化 = 進化体のある種で予算が足りるときだけ、
     * {@code evolveBase} + 近くのプレイヤー(0〜1)× {@code evolvePlayerNear} + 起き上がってからの日数(0〜1)× {@code evolveAge}。
     */
    public static Decision score(DirectorTable t, Inputs in, RandomSource random) {
        double nothing = jitter(t.nothingBase, t, random);
        double evolve = Double.NEGATIVE_INFINITY;
        if (in.canEvolve() && in.budget() + 1.0E-9D >= t.costEvolve) {
            double near = Math.max(0.0D, 1.0D - in.playerDistance() / t.playerNearBlocks);
            double age = Math.min(1.0D, Math.max(0.0D, in.ageDays()) / t.ageRefDays);
            evolve = jitter(t.evolveBase + t.evolvePlayerNear * near + t.evolveAge * age, t, random);
        }
        // 根付き(区切り 2): 根付きの姿のある種で予算が足りるときだけ。rootedBase + 起き上がってからの日数 × rootedAge。
        // 「拠点と森の間に置く」(D-16)は拠点の記憶(第 2 段)が入ってから点に足す
        double rooted = Double.NEGATIVE_INFINITY;
        if (in.canRoot() && in.budget() + 1.0E-9D >= t.costRooted) {
            double age = Math.min(1.0D, Math.max(0.0D, in.ageDays()) / t.ageRefDays);
            rooted = jitter(t.rootedBase + t.rootedAge * age, t, random);
        }
        Route route = Route.NONE;
        double best = nothing;
        if (evolve > best) {
            route = Route.EVOLVE;
            best = evolve;
        }
        if (rooted > best) {
            route = Route.ROOTED;
            best = rooted;
        }
        // 苗床(区切り 6): 苗床の姿のある種で予算が足りるときだけ。nurseryBase + 起き上がってからの日数 × nurseryAge。
        // 湿った暗がりは要らない(D-17)。森から離れてからなる(歩くのは根付きと同じ)
        double nursery = Double.NEGATIVE_INFINITY;
        if (in.canNursery() && in.budget() + 1.0E-9D >= t.costNursery) {
            double age = Math.min(1.0D, Math.max(0.0D, in.ageDays()) / t.ageRefDays);
            // 11-14 ユーザー決定(R6-3)「苗床は森から離れれば離れるほど選ばれやすく」: 見に来たときの、その個体と森の縁との距離で点を上げる
            // (nurseryFarBlocks で頭打ち。森が見当たらない = 十分に遠い)。式と数は【仮】
            double far = Math.min(1.0D, Math.max(0.0D, in.forestDistance()) / t.nurseryFarBlocks);
            nursery = jitter(t.nurseryBase + t.nurseryAge * age + t.nurseryFar * far, t, random);
        }
        if (nursery > best) {
            route = Route.NURSERY;
        }
        String summary = String.format(Locale.ROOT, "%s(何もしない %.2f / 進化 %s / 根付き %s / 苗床 %s。予算 %.1f・人 %s・起きてから %.1f 日・森の縁から %s)",
                route.label, nothing, fmt(evolve), fmt(rooted), fmt(nursery),
                in.budget(), in.playerDistance() == Double.MAX_VALUE ? "なし" : String.format(Locale.ROOT, "%.0f", in.playerDistance()),
                in.ageDays(), Double.isInfinite(in.forestDistance()) ? "遠い" : String.format(Locale.ROOT, "%.0f", in.forestDistance()));
        return new Decision(route, nothing, evolve, rooted, nursery, summary);
    }

    private static String fmt(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%.2f", v) : "-";
    }

    private static double jitter(double v, DirectorTable t, RandomSource random) {
        return v * (1.0D + t.jitter * (random.nextDouble() * 2.0D - 1.0D));
    }

    /**
     * 段階 0 の 1 体を見に来た。道を決め、進化なら予算を払う(前兆の間に倒されても戻さない = 森の損。【仮】)。
     *
     * @param bornAt この個体が起き上がった(世界に出た)ゲーム時刻
     */
    public Decision visit(ServerLevel level, AbstractParasitizedMonster mob, long bornAt) {
        DirectorTable t = DesignData.director();
        Player nearest = level.getNearestPlayer(mob.getX(), mob.getY(), mob.getZ(), -1.0D,
                p -> !p.isSpectator() && !((Player) p).isCreative());
        double playerDist = nearest != null ? nearest.distanceTo(mob) : Double.MAX_VALUE;
        double ageDays = Math.max(0L, level.getGameTime() - bornAt) / 24000.0D;
        G1Fate fate = mob.fate();
        Inputs in = new Inputs(G1Fate.developedOf(mob) != null, fate != null && fate.rootedForm() != null,
                fate != null && fate.nurseryForm() != null,
                available(level), playerDist, ageDays,
                fate != null ? fate.forest(level).distance(mob.position()) : 0.0D);
        Decision d = score(t, in, mob.getRandom());
        if (d.route() != Route.NONE && !tryPay(level, cost(t, d.route()))) {
            d = new Decision(Route.NONE, d.nothing(), d.evolve(), d.rooted(), d.nursery(), d.summary());
        }
        record(d.route(), mob.species().key() + " → " + d.summary());
        return d;
    }

    // ---------------------------------------------------------------- 苗床のスポナーの口(区切り 6。D-9・D-14)
    /**
     * 苗床から出せる Mob(G4〜G8)。<b>今は空</b>: G4 以降は退避中なので、戻した種を {@link #registerNurserySpawn} で足す(D-14「仕組みだけ先に入れ、
     * G4〜G6 が戻ったらつなぐ」)。空の間は、苗床は何も孵さず、予算も払わない。
     */
    private static final List<java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>>> NURSERY_SPAWNS =
            new ArrayList<>();

    public static void registerNurserySpawn(java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>> type) {
        NURSERY_SPAWNS.add(type);
    }

    public static List<java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>>> nurserySpawns() {
        return NURSERY_SPAWNS;
    }

    /**
     * 苗床が「孵してよいか」を聞く口: 出せる Mob があり、予算が {@code costSpawn} に足りれば、払って 1 種を返す(どれを出すかは今は乱数。
     * 拠点の記憶・森の性格が入ったら、ここで選ぶ)。出せない・足りないなら null(何も払わない)。
     */
    @Nullable
    public net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob> buySpawn(
            ServerLevel level, RandomSource random,
            List<java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>>> pool) {
        if (pool.isEmpty() || !DesignData.director().enabled) return null;
        if (!tryPay(level, DesignData.director().costSpawn)) return null;
        spawnsBought++;
        setDirty();
        return pool.get(random.nextInt(pool.size())).get();
    }

    /** これまでに苗床へ払って孵した数。 */
    private long spawnsBought;

    public long spawnsBought() {
        return spawnsBought;
    }

    /** 判断を数えて、直近の一覧へ足す(司令塔を通さずに道を決めたときも呼ぶ)。 */
    public void record(Route route, String line) {
        decided[route.ordinal()]++;
        recent.addFirst(line);
        while (recent.size() > 8) recent.removeLast();
        setDirty();
    }

    // ---------------------------------------------------------------- 見る口

    public List<String> describe(ServerLevel level) {
        PhaseData phase = PhaseData.get(level);
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "森の予算 %.1f / 上限 %.1f(ハイヴノード %d)・1 日に +%.1f(過繁茂のチャンク %d)",
                available(level), cap(level), phase.hiveNodeSnapshot().length, incomePerDay(level), phase.overgrownLedgerSize()));
        DirectorTable t = DesignData.director();
        out.add(String.format(Locale.ROOT, "値段: 進化 %.0f・根付き %.0f・苗床 %.0f / これまでの判断: 進化 %d・根付き %d・苗床 %d・何もしない %d",
                t.costEvolve, t.costRooted, t.costNursery, decided[Route.EVOLVE.ordinal()], decided[Route.ROOTED.ordinal()],
                decided[Route.NURSERY.ordinal()], decided[Route.NONE.ordinal()]));
        out.addAll(recent);
        return out;
    }

    public long decidedCount(Route route) {
        return decided[route.ordinal()];
    }

    // ---------------------------------------------------------------- 保存

    private static ForestDirector load(CompoundTag tag) {
        ForestDirector d = new ForestDirector();
        d.budget = Math.max(0.0D, tag.getDouble("Budget"));
        d.budgetAt = tag.contains("BudgetAt") ? tag.getLong("BudgetAt") : -1L;
        d.spawnsBought = tag.getLong("SpawnsBought");
        long[] dec = tag.getLongArray("Decided");
        System.arraycopy(dec, 0, d.decided, 0, Math.min(dec.length, d.decided.length));
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putDouble("Budget", budget);
        tag.putLong("BudgetAt", budgetAt);
        tag.putLongArray("Decided", decided);
        tag.putLong("SpawnsBought", spawnsBought);
        return tag;
    }
}
