package com.yayuy.overgrownvegetation.data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * G1(寄生された動物)の作り直し 第 1 段の設計値({@code g1.json}、2026-10-22)。
 *
 * <p>仕様の正本は Vault「G1 媒介種(寄生された動物)」の「作り直し(2026-10-22 の相談)」と「森の知性(司令塔)」。
 * ユーザー指定は 感染の目安「空の下で浮遊 50% に 30 秒相当」・屋根の下では積まない(繁茂・過繁茂のバイオームでは積む)だけ。
 * 時計(死体 10 分・判断まで 1 日・1 段目 2 日・2 段目 +5 日)は Claude の案をユーザーが了承した仮の値、
 * 司令塔の重み・苗床・能力の数値はすべて仮決め。バイオマスの値段は 0(ユーザーが後で決める。枠だけ)。
 */
public final class G1Table {

    public static final G1Table DEFAULT = new G1Table(null);

    /** Gson の受け皿。null は既定値。 */
    public static final class Json {
        public Infection infection;
        public Corpse corpse;
        public Life life;
        public Rooted rooted;
        public Nursery nursery;
        public Director director;
        public Abilities abilities;
    }

    public static final class Infection {
        /** 空の下にいる間だけ積む(屋根 = 葉以外の動きを止めるブロック)。 */
        public Boolean underSkyOnly;
        /** 目安: 浮遊 percent% に seconds 秒 = percent × seconds(%秒)で感染。 */
        public Double percent;
        public Double seconds;
        /** 浮遊の 100%(場の生の値)。0 なら spore_field.json の reading.fullAt。 */
        public Double airFullAt;
    }

    public static final class Corpse {
        /** 死体が胞子を出して起き上がるまで(tick)。 */
        public Integer emitTicks;
        /** 放出を積む間隔(tick)。量は spore_field.json の sources.corpsePerDay。 */
        public Integer emitEveryTicks;
    }

    public static final class Life {
        public Double branchAfterDays;
        public Double evolve1Days;
        public Double evolve2Days;
    }

    public static final class Rooted {
        /** 根付いて汚染する半径。0 = 汚染シダと同じ(config の corruptionRadius)。 */
        public Integer radius;
        /** これだけ経っても汚染し終えなければブロックになる(tick)。 */
        public Integer maxTicks;
    }

    public static final class Nursery {
        public Integer searchRadius;
        public Integer walkTimeoutTicks;
        public Double minMoisture;
        public Integer maxSkyLight;
        public Integer intervalTicks;
        public Integer maxAlive;
        public Integer aliveRadius;
        public Integer maxTotal;
        /** G4 以上を生む確率と種(枠だけ。既定 0 と空)。 */
        public Double g4Chance;
        public List<String> g4Types;
    }

    public static final class Director {
        public Double jitter;
        public Double rootedBase, rootedThinDeposit, rootedFrontNear, rootedFewPeople;
        public Double nurseryBase, nurseryFarFront, nurseryFewPeople;
        public Double evolveBase, evolvePlayerNear, evolveKills;
        public Double depositRef;
        public Integer frontNearChunks, frontFarChunks, frontSearchChunks;
        public Integer playerNearBlocks, fewPeopleBlocks;
        public Double killsRef, killsHalfLifeDays;
        /** バイオマスの値段(ユーザーが後で決める。今は 0 = 払わない)。点 = 効き ÷ (1 + 値段)。 */
        public Double costRooted, costNursery, costEvolve1, costEvolve2;
        /** 元の種の性向(id → [根付き, 苗床, 進化] に足す点)。 */
        public Map<String, double[]> speciesBias;
    }

    public static final class Abilities {
        public Double vineArmRange, vineArmPull;
        public Integer vineArmCooldownTicks;
        public Double sporesacCowPerDay;
        public Integer podCreeperFuseTicks;
        public Double podCreeperBurst;
        public Double executionerWhipRange, executionerWhipPull, executionerThornDamage;
        public Integer executionerWhipCooldownTicks, executionerThornCooldownTicks;
        public Double sporangiumChargeSpeed, sporangiumBurstAir, sporangiumBurstDeposit, sporangiumBurstBelowHealth;
        public Integer sporangiumChargeCooldownTicks, sporangiumBurstFerns;
        public Integer burstPodSearchRadius, burstPodFerns;
        public Double burstPodBurst;
    }

    // ---------------------------------------------------------------- 値

    public final boolean underSkyOnly;
    public final double infectPercent, infectSeconds, airFullAt;
    public final int corpseEmitTicks, corpseEmitEveryTicks;
    public final double branchAfterDays, evolve1Days, evolve2Days;
    public final int rootedRadius, rootedMaxTicks;
    public final int nurserySearchRadius, nurseryWalkTimeoutTicks, nurseryMaxSkyLight, nurseryIntervalTicks,
            nurseryMaxAlive, nurseryAliveRadius, nurseryMaxTotal;
    public final double nurseryMinMoisture, nurseryG4Chance;
    public final List<String> nurseryG4Types;
    public final double jitter, rootedBase, rootedThinDeposit, rootedFrontNear, rootedFewPeople,
            nurseryBase, nurseryFarFront, nurseryFewPeople, evolveBase, evolvePlayerNear, evolveKills,
            depositRef, killsRef, killsHalfLifeDays, costRooted, costNursery, costEvolve1, costEvolve2;
    public final int frontNearChunks, frontFarChunks, frontSearchChunks, playerNearBlocks, fewPeopleBlocks;
    private final Map<String, double[]> speciesBias;
    public final double vineArmRange, vineArmPull, sporesacCowPerDay, podCreeperBurst,
            executionerWhipRange, executionerWhipPull, executionerThornDamage,
            sporangiumChargeSpeed, sporangiumBurstAir, sporangiumBurstDeposit, sporangiumBurstBelowHealth, burstPodBurst;
    public final int vineArmCooldownTicks, podCreeperFuseTicks, executionerWhipCooldownTicks, executionerThornCooldownTicks,
            sporangiumChargeCooldownTicks, sporangiumBurstFerns, burstPodSearchRadius, burstPodFerns;

    private G1Table(Json j) {
        Json src = j != null ? j : new Json();
        Infection in = src.infection != null ? src.infection : new Infection();
        this.underSkyOnly = in.underSkyOnly == null || in.underSkyOnly;
        this.infectPercent = d(in.percent, 50.0D, 0.1D, 10000.0D);
        this.infectSeconds = d(in.seconds, 30.0D, 0.05D, 36000.0D);
        this.airFullAt = d(in.airFullAt, 0.0D, 0.0D, 1.0E6D);

        Corpse co = src.corpse != null ? src.corpse : new Corpse();
        this.corpseEmitTicks = i(co.emitTicks, 6000, 0, 24000 * 30);
        this.corpseEmitEveryTicks = i(co.emitEveryTicks, 100, 1, 24000);

        Life li = src.life != null ? src.life : new Life();
        this.branchAfterDays = d(li.branchAfterDays, 1.0D, 0.0D, 1000.0D);
        this.evolve1Days = d(li.evolve1Days, 2.0D, 0.0D, 1000.0D);
        this.evolve2Days = d(li.evolve2Days, 5.0D, 0.0D, 1000.0D);

        Rooted ro = src.rooted != null ? src.rooted : new Rooted();
        this.rootedRadius = i(ro.radius, 0, 0, 64);
        this.rootedMaxTicks = i(ro.maxTicks, 24000, 20, 24000 * 30);

        Nursery nu = src.nursery != null ? src.nursery : new Nursery();
        this.nurserySearchRadius = i(nu.searchRadius, 16, 2, 48);
        this.nurseryWalkTimeoutTicks = i(nu.walkTimeoutTicks, 2400, 20, 24000);
        this.nurseryMinMoisture = d(nu.minMoisture, 0.8D, 0.0D, 100.0D);
        this.nurseryMaxSkyLight = i(nu.maxSkyLight, 7, 0, 15);
        this.nurseryIntervalTicks = i(nu.intervalTicks, 6000, 20, 24000 * 30);
        this.nurseryMaxAlive = i(nu.maxAlive, 3, 0, 64);
        this.nurseryAliveRadius = i(nu.aliveRadius, 16, 1, 64);
        this.nurseryMaxTotal = i(nu.maxTotal, 8, 0, 1000);
        this.nurseryG4Chance = d(nu.g4Chance, 0.0D, 0.0D, 1.0D);
        this.nurseryG4Types = nu.g4Types != null ? List.copyOf(nu.g4Types) : List.of();

        Director di = src.director != null ? src.director : new Director();
        this.jitter = d(di.jitter, 0.2D, 0.0D, 1.0D);
        this.rootedBase = d(di.rootedBase, 0.3D, -10, 10);
        this.rootedThinDeposit = d(di.rootedThinDeposit, 0.6D, -10, 10);
        this.rootedFrontNear = d(di.rootedFrontNear, 0.4D, -10, 10);
        this.rootedFewPeople = d(di.rootedFewPeople, 0.3D, -10, 10);
        this.nurseryBase = d(di.nurseryBase, 0.3D, -10, 10);
        this.nurseryFarFront = d(di.nurseryFarFront, 0.6D, -10, 10);
        this.nurseryFewPeople = d(di.nurseryFewPeople, 0.3D, -10, 10);
        this.evolveBase = d(di.evolveBase, 0.2D, -10, 10);
        this.evolvePlayerNear = d(di.evolvePlayerNear, 0.6D, -10, 10);
        this.evolveKills = d(di.evolveKills, 0.6D, -10, 10);
        this.depositRef = d(di.depositRef, 0.1D, 1.0E-6D, 1000.0D);
        this.frontNearChunks = i(di.frontNearChunks, 8, 0, 256);
        this.frontFarChunks = i(di.frontFarChunks, 24, 1, 256);
        this.frontSearchChunks = i(di.frontSearchChunks, 32, 1, 64);
        this.playerNearBlocks = i(di.playerNearBlocks, 64, 1, 512);
        this.fewPeopleBlocks = i(di.fewPeopleBlocks, 48, 1, 512);
        this.killsRef = d(di.killsRef, 5.0D, 0.1D, 1000.0D);
        this.killsHalfLifeDays = d(di.killsHalfLifeDays, 3.0D, 0.01D, 1000.0D);
        this.costRooted = d(di.costRooted, 0.0D, 0.0D, 1.0E9D);
        this.costNursery = d(di.costNursery, 0.0D, 0.0D, 1.0E9D);
        this.costEvolve1 = d(di.costEvolve1, 0.0D, 0.0D, 1.0E9D);
        this.costEvolve2 = d(di.costEvolve2, 0.0D, 0.0D, 1.0E9D);
        Map<String, double[]> bias = new HashMap<>(defaultBias());
        if (di.speciesBias != null) {
            for (Map.Entry<String, double[]> e : di.speciesBias.entrySet()) {
                if (e.getValue() != null && e.getValue().length == 3) bias.put(e.getKey(), e.getValue().clone());
            }
        }
        this.speciesBias = Map.copyOf(bias);

        Abilities ab = src.abilities != null ? src.abilities : new Abilities();
        this.vineArmRange = d(ab.vineArmRange, 4.0D, 1.0D, 16.0D);
        this.vineArmPull = d(ab.vineArmPull, 0.9D, 0.0D, 4.0D);
        this.vineArmCooldownTicks = i(ab.vineArmCooldownTicks, 80, 1, 2400);
        this.sporesacCowPerDay = d(ab.sporesacCowPerDay, 0.5D, 0.0D, 1000.0D);
        this.podCreeperFuseTicks = i(ab.podCreeperFuseTicks, 45, 1, 200);
        this.podCreeperBurst = d(ab.podCreeperBurst, 0.5D, 0.0D, 1000.0D);
        this.executionerWhipRange = d(ab.executionerWhipRange, 7.0D, 1.0D, 24.0D);
        this.executionerWhipPull = d(ab.executionerWhipPull, 1.1D, 0.0D, 4.0D);
        this.executionerWhipCooldownTicks = i(ab.executionerWhipCooldownTicks, 60, 1, 2400);
        this.executionerThornDamage = d(ab.executionerThornDamage, 6.0D, 0.0D, 100.0D);
        this.executionerThornCooldownTicks = i(ab.executionerThornCooldownTicks, 70, 1, 2400);
        this.sporangiumChargeSpeed = d(ab.sporangiumChargeSpeed, 1.8D, 0.1D, 5.0D);
        this.sporangiumChargeCooldownTicks = i(ab.sporangiumChargeCooldownTicks, 120, 1, 2400);
        this.sporangiumBurstAir = d(ab.sporangiumBurstAir, 1.0D, 0.0D, 1000.0D);
        this.sporangiumBurstDeposit = d(ab.sporangiumBurstDeposit, 0.1D, 0.0D, 100.0D);
        this.sporangiumBurstBelowHealth = d(ab.sporangiumBurstBelowHealth, 0.25D, 0.0D, 1.0D);
        this.sporangiumBurstFerns = i(ab.sporangiumBurstFerns, 3, 0, 16);
        this.burstPodSearchRadius = i(ab.burstPodSearchRadius, 24, 1, 64);
        this.burstPodBurst = d(ab.burstPodBurst, 0.8D, 0.0D, 1000.0D);
        this.burstPodFerns = i(ab.burstPodFerns, 2, 0, 16);
    }

    public static G1Table fromJson(Json j) {
        return j == null ? DEFAULT : new G1Table(j);
    }

    /** 元の種の性向 [根付き, 苗床, 進化](id は名前空間なし)。無ければ 0。 */
    public double[] biasFor(String path) {
        double[] b = speciesBias.get(path);
        return b != null ? b : new double[3];
    }

    /** ノートの【案】: ゾンビ・村人・スケルトン・クリーパー = 進化寄り、牛・豚・羊 = 苗床寄り、小型 = 根付き寄り(点は仮決め)。 */
    private static Map<String, double[]> defaultBias() {
        Map<String, double[]> m = new HashMap<>();
        for (String id : List.of("parasitized_zombie", "parasitized_villager", "parasitized_skeleton", "parasitized_creeper")) {
            m.put(id, new double[]{0, 0, 0.5});
        }
        for (String id : List.of("parasitized_cow", "parasitized_pig", "parasitized_sheep", "parasitized_hoglin")) {
            m.put(id, new double[]{0, 0.5, 0});
        }
        for (String id : List.of("parasitized_chicken", "parasitized_baby_zombie", "parasitized_spider")) {
            m.put(id, new double[]{0.5, 0, 0});
        }
        return m;
    }

    private static double d(Double v, double def, double min, double max) {
        return v == null || v.isNaN() ? def : Math.max(min, Math.min(max, v));
    }

    private static int i(Integer v, int def, int min, int max) {
        return v == null ? def : Math.max(min, Math.min(max, v));
    }
}
