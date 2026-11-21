package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EconomyTable;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.phase.MobClassTable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>森の維持費</b>(2026-11-21 ユーザー決定。正本は Vault「前線と部隊(地図の上の森の軍勢)(11-21)」の 9)。
 * 植物側の 1 体は毎秒 値段 × {@code economy.upkeepPerSecond} を払う(森 = 過繁茂の外は × {@code upkeepOutsideFactor})。
 * 1 体ずつ毎 tick 数えず、<b>値段の合計(森の中・外)</b>を持ち、{@link #SAMPLE_TICKS} ごとに数え直す。
 *
 * <p>数える元は {@link Feed}。今は「読み込まれた植物側の Mob」と「仮想の群れの頭数」。部隊の台帳({@code front/})ができたら、
 * そちらの Feed を足して読み込まれていない部隊も数える(読み込まれた実物は二重に数えない)。
 *
 * <p>司令塔は維持費の合計が稼ぎの {@code upkeepIncomeShare}(8 割)を超えないように出す({@link ForestDirector#canField})。
 * 数え直しの間に出した分は {@link #noteFielded} ですぐ足す(同じ 5 秒の間に何十体も出して 8 割を越えないように)。
 */
public final class ForestUpkeep {

    /** 数え直す間(tick)。 */
    public static final int SAMPLE_TICKS = 100;

    /** 値段を足す先。 */
    public interface Sink {
        void add(double price, boolean inForest);
    }

    /** 維持費を払う物を数える元(部隊の台帳など)。 */
    @FunctionalInterface
    public interface Feed {
        void sample(ServerLevel level, Sink sink);
    }

    private static final List<Feed> FEEDS = new ArrayList<>();

    static {
        FEEDS.add(ForestUpkeep::sampleLoaded);
        FEEDS.add(ForestUpkeep::sampleVirtualHordes);
    }

    /** 数える元を足す(部隊の台帳から)。 */
    public static synchronized void addFeed(Feed feed) {
        if (!FEEDS.contains(feed)) FEEDS.add(feed);
    }

    /** 森の中・外の値段の合計と体数(最後に数えた値 + その後に出した分)。 */
    private static double priceIn, priceOut;
    private static int heads;
    private static long sampledAt = Long.MIN_VALUE;
    /** テスト用: 毎秒の維持費を決め打ちにする(null で戻す)。 */
    @Nullable
    private static Double overridePerSecond;

    private ForestUpkeep() {
    }

    public static void setForTests(@Nullable Double perSecond) {
        overridePerSecond = perSecond;
    }

    /** テスト用: 数え直しを止め、値段の合計をこの値に置く(null で戻す)。{@link #noteFielded} は今までどおり足す。 */
    public static void setSampleForTests(@Nullable Double in, @Nullable Double out) {
        frozen = in != null;
        priceIn = in != null ? in : 0.0D;
        priceOut = out != null ? out : 0.0D;
        heads = 0;
        if (!frozen) invalidate();
    }

    private static boolean frozen;

    /** 次に聞かれたとき必ず数え直す(テスト・台帳の大きな変化)。 */
    public static void invalidate() {
        sampledAt = Long.MIN_VALUE;
    }

    /** 毎秒の維持費(古ければ数え直す)。 */
    public static double perSecond(ServerLevel level) {
        Double o = overridePerSecond;
        if (o != null && !frozen) return o; // 数え直しを止めたテストは、その値段の合計で答える
        refresh(level);
        return ForestEconomy.table().upkeepPerSecond(priceIn, priceOut);
    }

    /** 1 日の維持費(毎秒 × 1200)。 */
    public static double perDay(ServerLevel level) {
        return perSecond(level) * EconomyTable.SECONDS_PER_DAY;
    }

    public static int heads(ServerLevel level) {
        refresh(level);
        return heads;
    }

    public static double priceInForest(ServerLevel level) {
        refresh(level);
        return priceIn;
    }

    public static double priceOutside(ServerLevel level) {
        refresh(level);
        return priceOut;
    }

    /** 1 体の毎秒の維持費(純粋な計算に近い口)。 */
    public static double unitPerSecond(double price, boolean inForest) {
        return ForestEconomy.table().upkeepPerSecond(inForest ? price : 0.0D, inForest ? 0.0D : price);
    }

    /** 今出した 1 体を、次の数え直しまで足しておく。 */
    public static void noteFielded(double price, boolean inForest) {
        if (price <= 0.0D) return;
        if (inForest) priceIn += price;
        else priceOut += price;
        heads++;
    }

    private static void refresh(ServerLevel level) {
        if (frozen) return;
        ServerLevel ow = level.getServer() != null ? level.getServer().overworld() : level;
        long now = ow.getGameTime();
        if (sampledAt != Long.MIN_VALUE && now - sampledAt < SAMPLE_TICKS && now >= sampledAt) return;
        sampledAt = now;
        double[] acc = new double[2];
        int[] n = new int[1];
        Sink sink = (price, inForest) -> {
            if (price <= 0.0D) return;
            acc[inForest ? 0 : 1] += price;
            n[0]++;
        };
        List<Feed> feeds;
        synchronized (ForestUpkeep.class) {
            feeds = new ArrayList<>(FEEDS);
        }
        for (Feed f : feeds) f.sample(ow, sink);
        priceIn = acc[0];
        priceOut = acc[1];
        heads = n[0];
    }

    // ---------------------------------------------------------------- 値段

    /** 森の軍勢(維持費を払う)か。系統が環境生物の種と、表に無い種は払わない(環境生物は生態系の台帳の住人)。 */
    public static boolean army(EntityType<?> type) {
        MobClassTable.Entry e = DesignData.mobClass().get(type);
        return e != null && e.lineage() != MobClassTable.Lineage.CRITTER;
    }

    /**
     * 維持費の元になる 1 体の値段(定価。苗床の倍率込み)。G1 の段階 0 は生産の値段(段階 0 の感染 Mob = 100)、ほかは危険度と型の値段。
     * 軍勢でない種は 0。
     */
    public static double unitPrice(EntityType<?> type, boolean stageZeroG1) {
        if (stageZeroG1) return DesignData.director().largeRaid.costStageZero * ForestEconomy.priceFactor();
        if (!army(type)) return 0.0D;
        return ForestDirector.spawnCost(type);
    }

    public static double unitPrice(Entity e) {
        boolean stageZero = e instanceof AbstractParasitizedMonster && !(e instanceof com.yayuy.overgrownvegetation.entity.DevelopedParasitized);
        return unitPrice(e.getType(), stageZero);
    }

    // ---------------------------------------------------------------- 数える元(今ある物)

    /** 読み込まれた植物側の Mob(生きているもの)。 */
    static void sampleLoaded(ServerLevel level, Sink sink) {
        if (level.dimension() != Level.OVERWORLD) return;
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof Mob m) || !m.isAlive()) continue;
            double p = unitPrice(m);
            if (p <= 0.0D) continue;
            sink.add(p, ForestEconomy.inForest(level, m.getX(), m.getZ()));
        }
    }

    /** 仮想の群れの頭数(本物になった分は読み込まれた Mob として数える)。 */
    static void sampleVirtualHordes(ServerLevel level, Sink sink) {
        if (level.dimension() != Level.OVERWORLD || level.getServer() == null) return;
        for (com.yayuy.overgrownvegetation.horde.VirtualHordes.Swarm s : com.yayuy.overgrownvegetation.horde.VirtualHordes.get(level.getServer()).swarms()) {
            boolean in = ForestEconomy.inForest(level, s.x(), s.z());
            for (var c : s.counts().entrySet()) {
                EntityType<?> type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(c.getKey());
                double p = unitPrice(type, false);
                if (p > 0.0D && c.getValue() > 0) sink.add(p * c.getValue(), in);
            }
        }
    }

    public static String describe(ServerLevel level) {
        double ps = perSecond(level);
        return String.format(Locale.ROOT, "維持費: %d 体(値段の合計 森の中 %.0f・外 %.0f)→ 毎秒 %.2f(1 日 %.0f)", heads(level), priceIn, priceOut, ps,
                ps * EconomyTable.SECONDS_PER_DAY);
    }
}
