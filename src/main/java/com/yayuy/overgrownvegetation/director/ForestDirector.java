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
import com.yayuy.overgrownvegetation.port.saved.OvSavedData;

/**
 * <b>森の知性(司令塔)</b> —— 世界に 1 つの「世界の意思」(2026-10-22 ユーザー決定。Mob ではなくワールドの保存データ)。
 * 0.2.0 の第 1 段(2026-11-13)。仕様の正本は Vault「森の知性(司令塔)」の「【決定】0.2.0 の司令塔(11-13)」。
 *
 * <ul>
 *   <li><b>森の予算</b>({@link ForestBudget}): 過繁茂のチャンク(台帳 {@link PhaseData#overgrownLedgerSize})が多いほど速く貯まり、
 *   ハイヴノード({@link PhaseData#hiveNodeSnapshot})の数で上限が上がる。毎 tick は数えず、聞かれたときに前回からの時間ぶんを足す。</li>
 *   <li>2026-11-23 片付け E: 段階 0 の個体を 1 体ずつ見に来て道(進化・根付き・苗床)を選ぶ判断(visit・score)は消した。
 *   11-21 から前線と部隊(front/)が受け持つ。</li>
 * </ul>
 * 数はどれも {@link DirectorTable}({@code director.json})の【仮】。拠点の記憶・学ぶ・貯めて使う・弱い所を突くは第 2・3 段。
 */
public final class ForestDirector extends OvSavedData implements ForestBudget {

    private static final String ID = "overgrown_vegetation_forest_director";

    public static final OvSavedData.Factory<ForestDirector> FACTORY =
            new OvSavedData.Factory<>(ForestDirector::new, (tag, registries) -> load(tag), null);

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

    /** 段の強さで割った値段(強さ 0.1 未満は 0.1 とみなす)。 */
    public static double stagePrice(double cost, double strength) {
        return cost / Math.max(0.1D, Math.min(1.0D, strength));
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
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.load(), FACTORY.constructor(), ID);
    }

    public static ForestDirector get(ServerLevel level) {
        return get(level.getServer());
    }

    // ---------------------------------------------------------------- 予算

    /**
     * 1 日に貯まる量 = フェーズの基本 × 面積の割合(11-14 ユーザー決定の収支。数は【仮】)。フェーズは司令塔が見るフェーズ(差し替えを含む。
     * GameTest のサーバーでは全部開いている = いちばん上の基本)。
     */
    public static double incomePerDay(ServerLevel level) {
        if (!DesignData.director().enabled) return 0.0D;
        // 11-20 夜 ユーザー決定「フェーズの基本の物差しをやめる」: 稼ぎ = √(過繁茂のチャンク数) × 0.8 + 井戸 + 巨大樹(1 つあたり何ぼ)。ForestEconomy
        return ForestEconomy.incomePerDay(level);
    }

    /** そのフェーズの incomeBase(11-20 夜から稼ぎには使わない。古い保存・表示のため残す)。 */
    public static double incomeBase(ServerLevel level) {
        double[] b = DesignData.director().incomeBase;
        return b[Math.max(0, Math.min(b.length - 1, DirectorGates.phase(level)))];
    }

    /**
     * 面積の割合(今の面積 ÷ そのフェーズのふつうの広さ)を読む 1 か所の口。物差しはフェーズのポイントと同じもので、
     * 「古い仕組みの片付けと汚染の速さの計測」のセッションが phases.json の {@code normalArea} に作った(11-14)。
     * 今の面積 = 過繁茂の台帳のチャンク数。ふつうの広さが無いフェーズ(F0・GameTest の全部開き)は 1.0。幅 0.3〜1.5 に収めるのは DirectorTable#income。
     */
    public static double areaRatio(ServerLevel level) {
        int normal = DesignData.phases().normalArea(DirectorGates.phase(level));
        if (normal <= 0) return 1.0D;
        return PhaseData.get(level).overgrownLedgerSize() / (double) normal;
    }

    /** 貯蓄の上限 = 基礎値 + (生きている巨大樹 + 蓄えの嚢の換算) × capPerHiveNode(11-22)。貯める・大きな襲撃の間は倍にする。 */
    private static long capNodesAt = Long.MIN_VALUE;
    private static int capNodesLevel;
    private static double capNodes;

    public static double cap(ServerLevel level) {
        // 11-22 ユーザー決定: 上限 = 基礎値 + ハイヴノード × a + 蓄えの嚢(ハイヴノード sacHiveNodes 個分。11-17)× a。1 日の稼ぎには比例しない
        // ハイヴノードは「生きている巨大樹の数」で数える(仕様の「巨大樹 1 本 = ハイヴノード 1 つ」。記録されたノードは読み込まれた所でしか増えないため。11-22 全体管理了承)
        // 11-22 見直し: 巨大樹と嚢の数え(上限は 1 tick に何百回も聞かれる)は同じ tick の中は覚えておく(GameTest のサーバーでは毎回数える)
        long now = level.getGameTime();
        boolean test = level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer;
        if (test || now != capNodesAt || capNodesLevel != System.identityHashCode(level)) {
            capNodes = com.yayuy.overgrownvegetation.front.FrontTrees.livingTreeCount(level) + com.yayuy.overgrownvegetation.organ.OrganWorld.sacHiveEquivalent(level);
            capNodesAt = now;
            capNodesLevel = System.identityHashCode(level);
        }
        double base = DesignData.director().cap(capNodes);
        // MOD の設定「森の予算の上限」(11-19。難易度の段で変わる)。収入の倍率は accrue で掛けるので、ここは上限の倍率だけ
        base *= com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.forestCap(level.getServer());
        // 貯める間は上限を上げる(第 3 段「貯めて一気に使う」)
        Mood m = get(level).mood;
        if (m == Mood.HOARD) return base * DesignData.director().hoard.capFactor;
        // 大きな襲撃の前触れ・本番の間も上限を上げる(11-14 ユーザー T7: 4 倍【仮】)
        // 11-19 襲撃の規模の「大」の静かな前触れ・本番では上げない(静かな 2 日で森の予算を膨らませない。全体管理・ユーザー「増やさず減らさない」)
        if ((m == Mood.LARGE_OMEN || m == Mood.LARGE) && !LargeRaids.scaled(get(level))) return base * DesignData.director().largeRaid.capFactor;
        return base;
    }

    // ---------------------------------------------------------------- 気分(第 3 段 区切り 3。② 貯めた襲撃。③ 大きな襲撃も同じ形)

    /**
     * 森の気分。ふだん / 貯める(予算を使わず、G1 は遠巻きにうろつく。終わったら一気に使う)。
     * 11-14 ユーザー: 襲撃は ① 送り込み ② 貯めた襲撃 ③ 大きな襲撃(いちばん強い。前触れの約 2 日は植物側の湧きが止まる)。
     */
    public enum Mood {
        NORMAL("ふだん"), HOARD("貯める"), LARGE_OMEN("大きな襲撃の前触れ"), LARGE("大きな襲撃");

        public final String label;

        Mood(String label) {
            this.label = label;
        }

        static Mood byName(String n) {
            for (Mood m : values()) {
                if (m.name().equals(n)) return m;
            }
            return NORMAL;
        }
    }

    private Mood mood = Mood.NORMAL;
    private double moodSince;
    private double moodUntil;
    /** この日までは次に貯め始めない。 */
    private double hoardCooldownUntil;
    /** 貯めているか(G1 の Goal が毎 tick 見る。見張りが付け直す。保存しない)。 */
    private static volatile boolean hoarding;

    public Mood mood() {
        return mood;
    }

    public double moodSince() {
        return moodSince;
    }

    public double moodUntil() {
        return moodUntil;
    }

    public double hoardCooldownUntil() {
        return hoardCooldownUntil;
    }

    /** 貯めている最中か(サーバーの G1 の Goal から)。 */
    public static boolean hoarding() {
        return hoarding;
    }

    void setMood(Mood m, double since, double until) {
        // 見直し ②6: 貯める・大きな襲撃の上がった上限から ふだん へ戻るとき、貯めた分を切り捨てない(次に数えるとき carry へ移す)
        if (m == Mood.NORMAL && mood != Mood.NORMAL) carryPending = true;
        mood = m;
        moodSince = since;
        moodUntil = until;
        hoarding = m == Mood.HOARD;
        setDirty();
    }

    void setHoardCooldownUntil(double day) {
        hoardCooldownUntil = day;
        setDirty();
    }

    // ---- 大きな襲撃(第 4 段)
    /** 本番の的(拠点の番号)と、次に起こしてよい日。 */
    private int largeRaidTarget = -1;
    private double largeRaidCooldownUntil;
    /** 11-19 襲撃の規模の「大」で始めた本番なら、始めた日(前の起こし方の本番なら NaN)。 */
    private double largeRaidScaledSince = Double.NaN;

    public double largeRaidScaledSince() {
        return largeRaidScaledSince;
    }

    void setLargeRaidScaledSince(double day) {
        largeRaidScaledSince = day;
        setDirty();
    }

    public int largeRaidTarget() {
        return largeRaidTarget;
    }

    public double largeRaidCooldownUntil() {
        return largeRaidCooldownUntil;
    }

    void setLargeRaidTarget(int id) {
        largeRaidTarget = id;
        setDirty();
    }

    void setLargeRaidCooldownUntil(double day) {
        largeRaidCooldownUntil = day;
        setDirty();
    }

    /** 見張りから: 保存から読んだ気分を、G1 が見る印へ写す。 */
    void syncFlag() {
        hoarding = mood == Mood.HOARD;
    }

    /**
     * 前回から {@code ticks} たった後の予算(純粋な計算)。上限を超えていた分は切る(ハイヴノードが倒されて上限が下がったとき)。
     */
    public static double accrued(double budget, long ticks, double incomePerDay, double cap) {
        double grown = budget + incomePerDay * Math.max(0L, ticks) / 24000.0D;
        return Math.max(0.0D, Math.min(cap, grown));
    }

    /**
     * 上限を超えて残している貯め(見直し ②6、11-16): 貯めた襲撃・大きな襲撃の上がった上限で貯めた分は、ふだんに戻っても切り捨てず、
     * 使うか、1 日に上限の {@link #CARRY_DECAY_PER_DAY} ずつ減るまで残す【仮】。その間は収入で増えない(上限を超えているので)。
     * ハイヴノードが倒されて上限が下がったときは、今までどおりすぐ切る(carry にしない)。
     */
    private double carry;
    private boolean carryPending;
    public static final double CARRY_DECAY_PER_DAY = 0.25D;

    public double carry() {
        return carry;
    }

    private void accrue(ServerLevel level) {
        // 11-16 ユーザー決定: 時計は 1 つ(フェーズの暦 = ゲームの時間 + 寝て飛ばした分)。寝て飛ばした時間も森は稼ぐ
        long now = com.yayuy.overgrownvegetation.phase.PhaseData.get(level.getServer().overworld()).clock(level.getServer());
        if (budgetAt < 0L) {
            budgetAt = now;
            setDirty();
            return;
        }
        if (now == budgetAt) return;
        if (DesignData.director().enabled) {
            double cap = cap(level);
            if (carryPending) {
                carry = Math.max(0.0D, budget - cap);
                carryPending = false;
            }
            double days = Math.max(0L, now - budgetAt) / 24000.0D;
            carry = Math.max(0.0D, Math.min(carry, budget - cap) - cap * CARRY_DECAY_PER_DAY * days); // 使った分・減った分だけ小さくなる
            // 生態系の台帳(11-16 全体管理 決定 C): 森の健康は貯まる速さにだけ掛ける(上限には掛けない。健康 100% で倍率 1 = 今までどおり)
            double factor = com.yayuy.overgrownvegetation.ecosystem.Ecosystem.incomeFactor(level)
                    * com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.forestIncome(level.getServer()); // MOD の設定「森の予算の生産量」(11-19)
            // 11-21 維持費: 稼ぎ − 植物側の Mob の維持費(毎秒)を積む。維持費は稼ぎの倍率(森の健康・MOD の設定)には掛けない
            double net = incomePerDay(level) * factor - ForestUpkeep.perDay(level);
            double limit = cap + carry;
            double grown = budget + net * days;
            // 11-22 ユーザー決定: 上限からあふれた分(この間に稼いで入りきらなかった分)は捨てずに森全体の胞子へ
            double over = Math.max(0.0D, grown - Math.max(limit, budget));
            if (over > 0.0D) StorageOverflow.add(over);
            StorageOverflow.decay(days);
            budget = accrued(budget, now - budgetAt, net, limit);
        }
        budgetAt = now;
        setDirty();
    }

    // ---------------------------------------------------------------- 維持費の関所(11-21)

    /** 毎秒の稼ぎ(森の健康・MOD の設定の倍率込み)。 */
    public static double incomePerSecond(ServerLevel level) {
        if (!DesignData.director().enabled) return 0.0D;
        // 同じ tick の中は覚えておく(関所は 1 体出すたびに聞く。稼ぎの元は器官の一覧を数えるので、部隊を作る回に何百回も数え直さない)
        long now = level.getGameTime();
        Double o = ForestEconomy.incomeOverride();
        if (now == incomeAt && java.util.Objects.equals(o, incomeOverrideAt)) return incomeCached;
        incomeCached = incomePerDay(level) * com.yayuy.overgrownvegetation.ecosystem.Ecosystem.incomeFactor(level)
                * com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.forestIncome(level.getServer())
                / com.yayuy.overgrownvegetation.data.EconomyTable.SECONDS_PER_DAY;
        incomeAt = now;
        incomeOverrideAt = o;
        return incomeCached;
    }

    private static long incomeAt = Long.MIN_VALUE;
    private static double incomeCached;
    @Nullable
    private static Double incomeOverrideAt;

    /** 維持費の上限(毎秒) = 稼ぎ × upkeepIncomeShare(8 割)。 */
    public static double upkeepLimitPerSecond(ServerLevel level) {
        return incomePerSecond(level) * ForestEconomy.table().upkeepIncomeShare;
    }

    /**
     * 定価 {@code listPrice} の 1 体をもう 1 体出しても、維持費の合計が稼ぎの 8 割を超えないか(11-21 ユーザー「ふつうに出しているだけで
     * 足りなくなって衰えることは無い」)。Mob を作る道はどれもここを通す。司令塔が止まっているときは通す。
     */
    public static boolean canField(ServerLevel level, double listPrice, boolean inForest) {
        if (!DesignData.director().enabled || listPrice <= 0.0D) return true;
        return ForestUpkeep.perSecond(level) + ForestUpkeep.unitPerSecond(listPrice, inForest) <= upkeepLimitPerSecond(level) + 1.0E-9D;
    }

    /** 8 割の関所を通り、{@code pay} を払えたら true(出した 1 体を維持費に足す)。 */
    public boolean tryPayUnit(ServerLevel level, double pay, double listPrice, boolean inForest) {
        if (!canField(level, listPrice, inForest)) {
            fieldRefused++;
            return false;
        }
        if (!tryPay(level, pay)) return false;
        ForestUpkeep.noteFielded(listPrice, inForest);
        return true;
    }

    /** 8 割の関所で断った数(保存しない)。 */
    public static long fieldRefused;

    // ---- 11-19 襲撃の規模: 貯めの財布の口(RaidScales)。襲撃で湧かせる間だけ開く(サーバーの主スレッドだけ)。
    // 開いている間は、使える量 = (予算 + その拠点の財布) ÷ 値段の倍率、払うときは 値段 × 倍率 を 財布 → 予算 の順に払う。
    @Nullable
    private static PurseScope purse;

    /** 財布の口。閉じると前の口に戻る(大きな襲撃の中で仮想の群れが払う・継ぎ足しが送り込みを呼ぶ、の入れ子のため)。 */
    public static final class PurseScope implements AutoCloseable {
        final BaseMemory memory;
        final BaseMemory.Base base;
        final double costFactor;
        @Nullable
        private final PurseScope prev;

        private PurseScope(BaseMemory memory, BaseMemory.Base base, double costFactor, @Nullable PurseScope prev) {
            this.memory = memory;
            this.base = base;
            this.costFactor = Math.max(0.01D, costFactor);
            this.prev = prev;
        }

        @Override
        public void close() {
            purse = prev;
        }
    }

    /** 拠点 b の財布を開く(try-with-resources で閉じる)。 */
    public static PurseScope openPurse(BaseMemory memory, BaseMemory.Base b, double costFactor) {
        purse = new PurseScope(memory, b, costFactor, purse);
        return purse;
    }

    @Override
    public double available(ServerLevel level) {
        accrue(level);
        PurseScope p = purse;
        if (p != null) return (budget + p.base.purse()) / p.costFactor;
        return budget;
    }

    @Override
    public boolean tryPay(ServerLevel level, double cost) {
        accrue(level);
        PurseScope p = purse;
        if (p != null) {
            double c = cost * p.costFactor;
            if (c > budget + p.base.purse() + 1.0E-9D) return false;
            double rest = c - p.memory.spendPurse(p.base, c);
            budget = Math.max(0.0D, budget - rest);
            setDirty();
            EconomySoak.spent(c); // 計測の間だけ(呼び元ごとに数える)
            return true;
        }
        if (cost > budget + 1.0E-9D) return false;
        budget = Math.max(0.0D, budget - cost);
        setDirty();
        EconomySoak.spent(cost); // 計測の間だけ
        return true;
    }

    @Override
    public void refund(ServerLevel level, double amount) {
        accrue(level);
        // 11-22 見直し: 上限を超えた分は捨てずに、あふれ(胞子)へ回す(accrue と同じ扱い)
        double c = cap(level), before = budget, add = Math.max(0.0D, amount);
        budget = Math.min(Math.max(budget, c), budget + add);
        double over = before + add - budget;
        if (over > 1.0E-9D) StorageOverflow.add(over);
        setDirty();
    }

    /** 予算をこの値にする(確かめ用のコマンドとテスト。上限は見ない —— 次に数えるとき上限へ切られる)。 */
    public void setBudget(ServerLevel level, double value) {
        accrue(level);
        budget = Math.max(0.0D, value);
        setDirty();
    }

    // ---------------------------------------------------------------- 拠点

    /** いちばん近い認識した拠点(真ん中まで evolveBaseBlocks 以内)。無ければ null。 */
    @Nullable
    public static BaseMemory.Base nearestRecognized(ServerLevel level, double x, double z) {
        double within = DesignData.director().base.evolveBaseBlocks;
        BaseMemory.Base best = null;
        double bestD = within * within;
        for (BaseMemory.Base b : BaseMemory.get(level.getServer()).bases()) {
            if (b.stage() != BaseMemory.Stage.RECOGNIZED) continue;
            double dx = b.centerX() - x, dz = b.centerZ() - z;
            double d2 = dx * dx + dz * dz;
            if (d2 <= bestD) {
                bestD = d2;
                best = b;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- 苗床のスポナーの口(区切り 6。D-9・D-14)
    /**
     * 苗床から出せる Mob(G2〜G6)。<b>今は空</b>: G2 以降は退避中なので、戻した種を {@link #registerNurserySpawn} で足す(D-14「仕組みだけ先に入れ、
     * G2〜G4 が戻ったらつなぐ」)。空の間は、苗床は何も孵さず、予算も払わない。
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
    /**
     * 場所つきの口(第 2 段 区切り 4): 認識した拠点があるのに、この苗床がどの拠点のそば(evolveBaseBlocks)にも無ければ、
     * {@code farNurserySkip} の割合で断る(予算を拠点のそばの苗床に回す。拠点のそばから孵った Mob は拠点へ向かう = {@link BaseRaids#onNurseryHatched})。
     */
    @Nullable
    public net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob> buySpawn(
            ServerLevel level, RandomSource random,
            List<java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>>> pool,
            net.minecraft.world.phys.Vec3 at) {
        if (pool.isEmpty()) return null;
        if (skipFarNursery(level, at, random)) return null;
        return buySpawn(level, random, pool);
    }

    /** Mob から苗床になった物の口(11-20): そこから孵す Mob の値段に {@code localFactor}(economy.nurseryLocalFactor)を掛ける。 */
    @Nullable
    public net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob> buySpawn(
            ServerLevel level, RandomSource random,
            List<java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>>> pool,
            net.minecraft.world.phys.Vec3 at, double localFactor) {
        if (pool.isEmpty()) return null;
        if (skipFarNursery(level, at, random)) return null;
        return buySpawnPriced(level, random, pool, localFactor);
    }

    /** 直前の buySpawn で払った額(孵した Mob に書く。ForestEconomy の markPaid)。 */
    private double lastBoughtPrice;

    public double lastBoughtPrice() {
        return lastBoughtPrice;
    }

    /** 拠点から遠い苗床を断るか。 */
    public static boolean skipFarNursery(ServerLevel level, net.minecraft.world.phys.Vec3 at, RandomSource random) {
        boolean anyRecognized = false;
        for (BaseMemory.Base b : BaseMemory.get(level.getServer()).bases()) {
            if (b.stage() == BaseMemory.Stage.RECOGNIZED) anyRecognized = true;
        }
        if (!anyRecognized || nearestRecognized(level, at.x, at.z) != null) return false;
        return random.nextDouble() < DesignData.director().base.farNurserySkip;
    }

    @Nullable
    public net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob> buySpawn(
            ServerLevel level, RandomSource random,
            List<java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>>> pool) {
        return buySpawnPriced(level, random, pool, 1.0D);
    }

    @Nullable
    private net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob> buySpawnPriced(
            ServerLevel level, RandomSource random,
            List<java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>>> pool, double localFactor) {
        lastBoughtPrice = 0.0D;
        if (pool.isEmpty() || !DesignData.director().enabled) return null;
        // 苗床のスポナーは F4 から。大きな襲撃の前触れの間は止める(DirectorGates)
        if (!DirectorGates.open(level, DirectorGates.Gate.SPAWNER) || DirectorGates.plantSpawnsSuppressed(level) || hoarding()) return null; // ピースフルも(②9) // 貯めている間も孵さない
        // 小さな段: F4 の中は、導入でまれに・定着で普通に孵す(聞かれた回を強さの確率で通す)【仮】
        double spawnStrength = DirectorGates.strength(level, DirectorGates.Gate.SPAWNER);
        if (!DirectorGates.roll(spawnStrength, random)) return null;
        // 孵す Mob を先に選び、そのグループで値段を引く(11-14 ユーザー: G2 12・G3 25・G4 40【仮】)。値段も強さで割る(道と同じ)
        // 選ぶのは、そのグループの送り込みのゲートが開いている種だけ(G3 は F5・G4 以降は F6。G2 と G1 などは苗床のスポナーのゲートのまま。11-15 enemy-spawns)
        List<java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>>> openPool = new ArrayList<>();
        // 11-18 夜: 出るフェーズは種ごと(groupGateOpen)・値段は危険度と型(spawnCost)。払える種の中から選ぶ
        // (安い種は数で・高い種は予算が貯まったときに。払えない種を引いて何も孵さない、を無くす)
        double avail = available(level);
        for (var s : pool) {
            net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob> t = s.get();
            if (groupGateOpen(level, t) && stagePrice(spawnCost(t) * localFactor, spawnStrength) <= avail + 1.0E-9D) openPool.add(s);
        }
        if (openPool.isEmpty()) return null;
        net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob> type = openPool.get(random.nextInt(openPool.size())).get();
        double bought = stagePrice(spawnCost(type) * localFactor, spawnStrength);
        if (!tryPayUnit(level, bought, spawnCost(type) * localFactor, true)) return null; // 11-21 維持費の 8 割の関所(苗床は森の中)
        lastBoughtPrice = bought;
        spawnsBought++;
        setDirty();
        return type;
    }

    /**
     * 苗床が孵してよいグループか(11-15 enemy-spawns): G3 は送り込みの G3 のゲート(F5)・G4 以降は混ざった群れのゲート(F6)が開いてから。
     * G2 とグループの無い種は、苗床のスポナーのゲート(buySpawn の頭)のまま。
     * <p>11-14 夕の「G2 以降の自然の湧きも予算を払う口」(buyNaturalSpawn)は 11-15 に消した: 自然の湧きは縁の湧き({@code spawn/EnemySpawns})で、
     * 数はフェーズの段だけで決め、予算は払わない(11-15 ユーザー)。
     */
    static boolean groupGateOpen(ServerLevel level, net.minecraft.world.entity.EntityType<?> type) {
        // 11-18 夜 ユーザー決定: 出るフェーズは種ごと(mob_class.json の phase。表に無い種は前のグループのゲート)
        return DirectorGates.speciesOpen(level, type);
    }

    /**
     * 森がその Mob を湧かせる値段(段で割る前)。11-18 夜 ユーザー決定「危険度と型を値段の目安に」: mob_class.json の
     * base × step^(危険度 − 1) × 型の倍率【仮】。表に無い・危険度 0 の種は前の決まり(グループで。G3・G4 のほかは G2 の値段)。
     */
    public static double spawnCost(net.minecraft.world.entity.EntityType<?> type) {
        com.yayuy.overgrownvegetation.phase.MobGroup g = com.yayuy.overgrownvegetation.phase.MobGroup.of(type);
        // 11-20: 苗床の群体はワールド全体の Mob の値段を軽くする(ForestEconomy#priceFactor)
        return DesignData.mobClass().price(type, DesignData.director().spawnCost(g == null ? 2 : g.number())) * ForestEconomy.priceFactor();
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

    /** 判断の数には入れずに、直近の一覧へだけ足す(偵察・送り込みの記録。第 2 段)。 */
    public void note(String line) {
        recent.addFirst(line);
        while (recent.size() > 8) recent.removeLast();
    }

    // ---------------------------------------------------------------- 見る口

    public List<String> describe(ServerLevel level) {
        PhaseData phase = PhaseData.get(level);
        List<String> out = new ArrayList<>();
        out.add(DirectorGates.describe(level));
        out.add(String.format(Locale.ROOT, "森の予算 %.1f / 上限 %.1f(ハイヴノード %d)・1 日に +%.1f(過繁茂のチャンク %d)× 森の健康の倍率 %.2f",
                available(level), cap(level), phase.hiveNodeSnapshot().length, incomePerDay(level), phase.overgrownLedgerSize(),
                com.yayuy.overgrownvegetation.ecosystem.Ecosystem.incomeFactor(level)));
        out.add(ForestEconomy.describe(level));
        DirectorTable t = DesignData.director();
        out.add(String.format(Locale.ROOT, "進化の値段 %.0f / これまでに進化させた %d", t.costEvolve, decided[Route.EVOLVE.ordinal()]));
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
        d.mood = Mood.byName(tag.getString("Mood"));
        d.moodSince = tag.getDouble("MoodSince");
        d.moodUntil = tag.getDouble("MoodUntil");
        d.hoardCooldownUntil = tag.getDouble("HoardCooldown");
        d.largeRaidTarget = tag.contains("LargeTarget") ? tag.getInt("LargeTarget") : -1;
        d.largeRaidCooldownUntil = tag.getDouble("LargeCooldown");
        d.largeRaidScaledSince = tag.contains("LargeScaled") ? tag.getDouble("LargeScaled") : Double.NaN;
        d.carry = Math.max(0.0D, tag.getDouble("Carry"));
        d.carryPending = tag.getBoolean("CarryPending");
        // 11-21 物差しを 100 倍に: 前の単位で保存された予算・貯めを 1 回だけ 100 倍する
        if (tag.getInt("Scale") < 100) {
            d.budget *= 100.0D;
            d.carry *= 100.0D;
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putDouble("Budget", budget);
        tag.putLong("BudgetAt", budgetAt);
        tag.putLongArray("Decided", decided);
        tag.putLong("SpawnsBought", spawnsBought);
        tag.putString("Mood", mood.name());
        tag.putDouble("MoodSince", moodSince);
        tag.putDouble("MoodUntil", moodUntil);
        tag.putDouble("HoardCooldown", hoardCooldownUntil);
        tag.putInt("LargeTarget", largeRaidTarget);
        tag.putDouble("LargeCooldown", largeRaidCooldownUntil);
        if (!Double.isNaN(largeRaidScaledSince)) tag.putDouble("LargeScaled", largeRaidScaledSince);
        tag.putDouble("Carry", carry);
        tag.putBoolean("CarryPending", carryPending);
        tag.putInt("Scale", 100);
        return tag;
    }
}
