package com.yayuy.overgrownvegetation.ecosystem;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * <b>生態系の台帳</b>(2026-09-24、2026-10-14 に作り直し)。ワールドに 1 つ(オーバーワールドに保存)。
 *
 * <p>持っているのは 2 つだけ。
 * <ul>
 *     <li><b>多様性</b>(豊かさ)—— <b>ワールド全体で 1 つ</b>の 0〜1 の値(10-14 ユーザー指定。前は 8×8 チャンクの区域ごと)。
 *     どこでグループ2・3 を倒しても同じ値が減る。</li>
 *     <li><b>バイオマス</b> —— 植物側の蓄え(世界で 1 つの値)。防衛の兵を出すたび・TD イベントで減る。</li>
 * </ul>
 *
 * <h2>戻り方は「読むときに計算する」</h2>
 * <p>「最後に書いた多様性」と「その時刻」だけを持ち、今の値は読むたびに経過時間から出す
 * ({@link EcosystemMath#recovered})。時刻はオーバーワールドのゲーム内時刻({@code getGameTime}、寝ても飛ばない方)。
 *
 * <p>前の区域ごとの記録(セーブの {@code regions})は読まずに捨てる(多様性は 100% から始まる)。
 */
public final class EcosystemData extends SavedData {

    private static final String ID = "overgrown_vegetation_ecosystem";

    public static final SavedData.Factory<EcosystemData> FACTORY =
            new SavedData.Factory<>(EcosystemData::new, (tag, registries) -> load(tag), null);

    /** {@link #since} の時点の多様性(0〜1)。 */
    private double health = 1.0D;
    /** {@link #health} を書いたゲーム内時刻。 */
    private long since;
    /** 最後に判定した防衛の段階(戻り方に余裕を持たせるために覚えておく)。 */
    private int tier;
    private double biomass;
    /** 最後にバイオマスの収入を足したゲーム内時刻。-1 = まだ一度も足していない。 */
    private long lastIncomeTick = -1L;
    /** 直近の収入(1 秒あたり)。表示用で、保存しない。 */
    private double lastIncomePerSecond;

    private EcosystemData() {
    }

    public static EcosystemData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static EcosystemData load(CompoundTag tag) {
        EcosystemData data = new EcosystemData();
        data.biomass = tag.getDouble("biomass");
        data.lastIncomeTick = tag.contains("lastIncomeTick") ? tag.getLong("lastIncomeTick") : -1L;
        if (tag.contains("health")) {
            data.health = Math.max(0.0D, Math.min(1.0D, tag.getDouble("health")));
            data.since = tag.getLong("since");
            data.tier = tag.getInt("tier");
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putDouble("biomass", biomass);
        tag.putLong("lastIncomeTick", lastIncomeTick);
        tag.putDouble("health", health);
        tag.putLong("since", since);
        tag.putInt("tier", tier);
        return tag;
    }

    // --- 多様性 ---

    /** 今の多様性(0〜1)。 */
    public double health(long now, double perTick) {
        return EcosystemMath.recovered(health, now - since, perTick);
    }

    /** 多様性を {@code amount} 減らす。減らしたあとの値を返す。 */
    public double damage(long now, double amount, double perTick) {
        double next = Math.max(0.0D, health(now, perTick) - Math.max(0.0D, amount));
        health = next;
        since = now;
        setDirty();
        return next;
    }

    /** 多様性を {@code value}(0〜1)にする(コマンド・GameTest の後片付け用)。 */
    public void setHealth(long now, double value) {
        health = Math.max(0.0D, Math.min(1.0D, value));
        since = now;
        setDirty();
    }

    /** 防衛の段階(0 = なし)。判定した段階を覚えておく(戻り方の余裕のため)。 */
    public int tier(long now, double perTick, double[] thresholds, double hysteresis) {
        int next = EcosystemMath.tierFor(health(now, perTick), tier, thresholds, hysteresis);
        if (next != tier) {
            tier = next;
            setDirty();
        }
        return next;
    }

    // --- バイオマス ---

    public double biomass() {
        return biomass;
    }

    /** 直近の収入のときの貯蓄上限(表示用。保存しない。収入がまだ無ければ NaN)。 */
    private double lastBiomassCap = Double.NaN;

    public double lastBiomassCap() {
        return lastBiomassCap;
    }

    void setLastBiomassCap(double cap) {
        lastBiomassCap = cap;
    }

    public void setBiomass(double value) {
        biomass = Math.max(0.0D, value);
        setDirty();
    }

    public void addBiomass(double amount) {
        if (amount > 0.0D) {
            biomass += amount;
            setDirty();
        }
    }

    /** {@code cost} を払えたら払って {@code true}。足りなければ何もせず {@code false}。 */
    public boolean spend(double cost) {
        if (cost < 0.0D || biomass < cost) {
            return false;
        }
        biomass -= cost;
        setDirty();
        return true;
    }

    long lastIncomeTick() {
        return lastIncomeTick;
    }

    void setLastIncomeTick(long tick) {
        if (lastIncomeTick != tick) {
            lastIncomeTick = tick;
            setDirty();
        }
    }

    /** 直近に足した収入(1 秒あたり)。サーバーを起動してから最初の収入までは 0。 */
    public double lastIncomePerSecond() {
        return lastIncomePerSecond;
    }

    void setLastIncomePerSecond(double perSecond) {
        lastIncomePerSecond = perSecond;
    }
}
