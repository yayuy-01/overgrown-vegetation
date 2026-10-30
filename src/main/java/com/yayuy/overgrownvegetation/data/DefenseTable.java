package com.yayuy.overgrownvegetation.data;

/**
 * {@code defense.json} — 序盤の防衛設備の設計値(2026-09-19)。
 * <ul>
 *     <li>{@code repellentLamp}: 忌避灯。半径 {@code radius} の中にグループ1〜4 の Mob が入ってこない
 *     ({@code entity/RepellentAvoidGoal})。ヴェロフィルだけは例外で、{@code velophyllDetectRange} 以内の
 *     灯りを見つけると爆撃しに来る({@code entity/VelophyllLampBombGoal})。爆弾が
 *     {@code coverRadius} 以内で炸裂すると灯りは蔦と胞子に覆われて止まる(右クリックで剥がす)。</li>
 *     <li>{@code barbedWire}: 有刺鉄線。触れた生き物に {@code damage} を {@code damageIntervalTicks} ごとに与え、
 *     移動速度を {@code slowMultiplier} 倍にする。</li>
 *     <li>{@code abatis}: 逆茂木(2026-10-16)。有刺鉄線と同じ作りで弱い。{@code hitsPerStage} 回刺すごとに 1 段傷み、4 段目を刺し切ると壊れる。</li>
 *     <li>{@code sporeAlarm}: 胞子警報器。半径 {@code radius} の中のプレイヤーへ、フェーズの移り変わり・
 *     レイド・小規模レイドの警告をチャットに流す。次のフェーズまでの達成率が {@code phaseWarnFraction}
 *     に達したときにも「移行が近い」と警告する。</li>
 *     <li>{@code gunTurret}: 機関砲台(2026-09-21)。半径 {@code range} かつ仰俯角
 *     {@code minElevation}〜{@code maxElevation} に入った敵を、毎分 {@code roundsPerMinute} 発・
 *     1 発 {@code damage} で撃つ({@code block/GunTurretBlockEntity})。銃口が目標まで
 *     {@code aimTolerance} 度に寄ったら発砲し、目標は {@code retargetIntervalTicks} ごとに選び直す。
 *     増援を運ぶ胞子弾を撃ち落とすと、大きさ {@code clusterBombletSize} の小さな胞子爆弾が
 *     {@code clusterBomblets} 個ばらまかれる。</li>
 *     <li>{@code jammingLamp}: 妨害灯(2026-10-01)。半径は忌避灯と同じ。{@code maxGroup} 以下のグループを追い出し、
 *     半径の中の過繁茂の Mob に弱体化と移動速度低下を付け直す(段階・持続・間隔)。</li>
 *     <li>{@code alliedBase}: 同盟軍の基地の貯蓄と補給値・留守中に減る量・陥落までの日数・周りの汚染の数え方(2026-10-04、{@link AlliedBase})と、
 *     世界生成の基地の種類・レベルの重みと生まない場所(順 2 の着手順 2。間隔と離れは {@code worldgen/structure_set/allied_bases.json})と、
 *     減った守備兵の補充の値段・野戦病院の割引・間隔(順 2 の着手順 3)。</li>
 * </ul>
 * {@code /ov design reload} で即反映。
 */
public final class DefenseTable {

    public static final class Json {
        public RepellentLamp repellentLamp;
        public BarbedWire barbedWire;
        public Abatis abatis;
        public SporeAlarm sporeAlarm;
        public GunTurret gunTurret;
        public JammingLamp jammingLamp;
        public AlliedBase alliedBase;
    }

    /**
     * 同盟軍の基地の貯蓄と補給値(順 2 の着手順 1、2026-10-04、{@code base/build/BaseEconomy})。
     * <b>ユーザー指定(10-01)</b>: 貯蓄の上限は大体 1 万(レベルで変える)・補給値は 1 日 1000〜3000 で時間とともに増え上限なし・
     * 道でつながった基地 1 つで +15%(足し算)、補給基地とのつながりは代わりに +60%・読み込まれていない基地は周りの汚染で 1 日ごとに減り
     * (完全に囲まれていれば 2800〜4000)、減る量が回復を上回る日が 3 日続くと陥落。
     * <b>ユーザー指定(10-04、測りの表を見て)</b>: 周りの汚染は外周から 8 チャンクの輪で、胞子除去装置が守るチャンクを分母から外す・補給値の増え方は 1 日 1% のまま。
     * レベルごとの値・「読み込まれている」の幅・最初の貯蓄は Claude 仮決め。
     */
    public static final class AlliedBase {
        /** レベル 1〜5 の貯蓄の上限。 */
        public int[] stockCap;
        /** レベル 1〜5 の補給値の始まり(1 日に回復する量)。 */
        public int[] supplyStart;
        /** 補給値が 1 日ごとに、始まりの値のこの割合ずつ増える(上限なし)。 */
        public Double supplyGrowthPerDay;
        /** 道でつながった基地 1 つごとの補給値の割り増し(足し算)。 */
        public Double connectionBonus;
        /** 補給基地とのつながり 1 本の割り増し({@code connectionBonus} の代わり)。 */
        public Double supplyBaseBonus;
        /** 周りが全部汚染された留守中の基地が 1 日に減る量の下限・上限(この間の乱数 × 周りの汚染の割合)。 */
        public Integer declineMin;
        public Integer declineMax;
        /** 留守中に「減る量 > 回復量」の日がこれだけ続くと陥落する。 */
        public Integer fallStreakDays;
        /** 周りの汚染を数える形。{@code ring} = 基地の外周からの輪、{@code circle} = 中心からの円。 */
        public String surroundShape;
        /** 輪なら外周からの幅、円なら中心からの半径(チャンク)。 */
        public Integer surroundChunks;
        /** 周りの数え方で、胞子除去装置が台帳から守っているチャンクを分母から外すか。 */
        public Boolean surroundSkipGuarded;
        /** 「読み込まれている」= 同じ次元のプレイヤーが中心から(計画の外接の半分 + これ)ブロック以内にいる。 */
        public Integer presenceMargin;
        /** 新しい基地の最初の貯蓄(上限に対する割合)。 */
        public Double startStockFraction;
        /**
         * 世界生成の基地の種類の重み(種類の id → 重み。順 2 の着手順 2、2026-10-04)。<b>ユーザー指定(10-04)</b>: 研究をまれに
         * (大型防衛・補給・工廠 20 / 野戦病院・観測 15 / 研究 10)。<b>浄化は最初から無い(10-02)</b>ので 0。
         */
        public java.util.Map<String, Integer> worldgenTypeWeights;
        /** 世界生成の基地のレベル 1〜5 の重み(Claude 仮決め: 同じ割合。ユーザー指定は「レベルはランダム」)。 */
        public int[] worldgenLevelWeights;
        /** 世界生成の基地の中心(73 × 73)の起伏の上限(標本の高さの差。Claude 仮決め)。これより荒い所には生まない。 */
        public Integer worldgenCoreMaxRelief;
        /** 村が来うる場所からこのチャンク数以内には世界生成の基地を生まない(基地の書き出しは村の家を避けない。Claude 仮決め)。 */
        public Integer worldgenAvoidVillageChunks;
        /** 同じく森の洋館(Claude 仮決め)。 */
        public Integer worldgenAvoidMansionChunks;
        /**
         * 減った守備兵の補充の値段(兵種の id → 貯蓄。順 2 の着手順 3、2026-10-04)。<b>ユーザー指定(10-04)</b>: 標準 = 歩兵 100・工兵 120・
         * 砲兵 200・エリート 400(比は強さから)。
         */
        public java.util.Map<String, Integer> replenishCosts;
        /** 野戦病院の基地の補充の割引(0.2 = 2 割引)。<b>ユーザー指定(10-01)</b>「野戦病院は補充が少し安い」。割合は Claude 仮決め。 */
        public Double replenishHospitalDiscount;
        /**
         * 補充の間隔(tick。減った兵士を兵舎から 1 体ずつ、この間隔で出す)。<b>ユーザー指定(10-01)</b>「一定時間ごとに減った分だけ・1 体ずつ間隔を空ける」。
         * 600(30 秒)は Claude 仮決め。留守中の補充(ユーザー指定 10-04「戻るまでに済ませておく」)も 1 日にこの間隔で数えた数まで。
         */
        public Integer replenishIntervalTicks;
        /**
         * 倉庫へ買い足す品の値段(貯蓄。順 2 の着手順 4 = 整備、2026-10-04)。{@code rounds} = 機関砲弾 16 発(作業台 1 回分)、{@code redstone} = レッドストーン 1 個、
         * {@code filter} = 新しい胞子フィルター 1 枚、{@code repair} = 補修材 1 個。<b>ユーザー指定(10-02)</b>「荷捌き場の倉庫に貯蓄を使って機関砲弾・補修材・
         * 新しいフィルターなどが補充される」。値段は Claude 仮決め(作り方の材料の重さから)。
         */
        public java.util.Map<String, Integer> upkeepCosts;
        /**
         * 倉庫に置いておく目安(これまで買い足す)。{@code roundsPerTurret} = 機関砲台 1 台あたりの弾、{@code redstonePerPurifier} = 除去装置 1 台あたりのレッドストーン、
         * {@code filtersPerPurifier} = 除去装置 1 台あたりのフィルター(使用済み + 未使用。<b>ユーザー指定(10-02)</b>「除去装置 × 2 の分だけ、それ以上は増えない」)、
         * {@code repairPerLevel} = レベル 1 あたりの補修材。filtersPerPurifier 以外は Claude 仮決め。
         */
        public java.util.Map<String, Integer> upkeepStock;
        /** 壊された設備を置き直す補修材の数({@code turret} = 機関砲台、{@code purifier} = 胞子除去装置。Claude 仮決め)。ほかのブロックは 1 マス 1 個。 */
        public java.util.Map<String, Integer> upkeepDeviceRepair;
        /** 倉庫へ買い足す間隔(tick。プレイヤーがいる間だけ。Claude 仮決め 1200 = 1 分)。 */
        public Integer upkeepRestockIntervalTicks;
        /** 機関砲台の弾がこれ以下になったら、{@link #upkeepTurretFill} 発まで足す(Claude 仮決め 64 / 128。128 は置いたときの量)。 */
        public Integer upkeepTurretRefillBelow;
        public Integer upkeepTurretFill;
        /** 除去装置のレッドストーンがこれ以下になったら、{@link #upkeepFuelFill} 個まで足す(Claude 仮決め 4 / 16。16 は置いたときの量)。 */
        public Integer upkeepFuelRefillBelow;
        public Integer upkeepFuelFill;
        /** フィルターがこの割合すり減ったら替える(0.75 = 4 分の 3。Claude 仮決め —— 止まる前に替える)。 */
        public Double upkeepFilterSwapWear;
        /** 除去装置の取れた胞子がこの数以上たまったら取り出す(64 で止まる。Claude 仮決め 32)。 */
        public Integer upkeepSporesEmptyAt;
        /** 修理と解体の 1 マスにかかる時間(tick。Claude 仮決め 10 = 0.5 秒)。 */
        public Integer upkeepFixTicks;
        /** 工兵が仕事を置いて戦う敵の近さ(マス。Claude 仮決め 8)。 */
        public Integer upkeepEngageRange;
        /**
         * 見張り台の早期警報(順 2 の着手順 8 の最小限、2026-10-08)。TD のターゲットから水平にこの距離(ブロック)の中に陥落していない基地があると、
         * 予告のときにいちばん近い基地の見張り台が群れの向きと規模を知らせる。0 で知らせない。<b>ユーザー指定(10-01)</b>「見張り台が TD の群れを
         * 早めに知らせる」、10-08 に「TD の予告に群れの方角と規模を足すだけ」に絞った。距離は Claude 仮決め 512。
         */
        public Double watchRadius;
        /** 信頼・寄付・取引(順 2 の着手順 5、2026-10-07)。中身は {@link BaseTradeConfig}。 */
        public BaseTradeConfig.Json trade;
        /** 道とつながり(順 2 の着手順 7、2026-10-08)。中身は {@link BaseRoadConfig}。 */
        public BaseRoadConfig.Json road;
    }

    /** 周りの汚染を数える形。 */
    public enum SurroundShape {
        RING, CIRCLE;

        static SurroundShape of(String s) {
            return "circle".equalsIgnoreCase(s) ? CIRCLE : RING;
        }
    }

    /**
     * 妨害灯(2026-10-01、{@code base/JammingLampBlock})。半径は忌避灯と同じ。{@code maxGroup} 以下のグループを追い出し(ユーザー指定 6)、
     * 半径の中の過繁茂の Mob に {@code intervalTicks} ごと、バニラの弱体化(段階 {@code weaknessAmplifier}、0 = I = 攻撃力 −4)と
     * 移動速度低下(段階 {@code slownessAmplifier}、1 = II = −30%)を {@code effectTicks} だけ付け直す(段階・持続・間隔は仮決め)。
     */
    public static final class JammingLamp {
        public Integer maxGroup;
        public Integer weaknessAmplifier;
        public Integer slownessAmplifier;
        public Integer effectTicks;
        public Integer intervalTicks;
    }

    public static final class RepellentLamp {
        public Double radius;
        public Double velophyllDetectRange;
        public Double coverRadius;
    }

    public static final class BarbedWire {
        public Double damage;
        public Integer damageIntervalTicks;
        public Double slowMultiplier;
    }

    public static final class Abatis {
        public Double damage;
        public Integer damageIntervalTicks;
        public Double slowMultiplier;
        public Integer hitsPerStage;
    }

    public static final class SporeAlarm {
        public Double radius;
        public Double phaseWarnFraction;
    }

    public static final class GunTurret {
        public Double damage;
        public Double roundsPerMinute;
        public Double range;
        public Double velophyllRange;
        public Double minElevation;
        public Double maxElevation;
        public Double traverseSpeed;
        public Double elevationSpeed;
        public Double aimTolerance;
        public Integer retargetIntervalTicks;
        public Integer clusterBomblets;
        public Double clusterBombletSize;
        public Double corruptRadius;
        /** スカトゥリウムの迫撃砲の弾が {@code corruptRadius} 以内で炸裂したとき、砲台が止まる確率(0〜1)。 */
        public Double mortarDisableChance;
        /** 迫撃砲以外の胞子爆弾(ヴェロフィルの爆撃など)で止まる確率(0〜1)。 */
        public Double bombDisableChance;
        public Double shielderDetectRange;
        public Double modelScale;
        public Double tracerSpeed;
    }

    private final double lampRadius;
    private final double lampVelophyllDetectRange;
    private final double lampCoverRadius;
    private final double wireDamage;
    private final int wireDamageIntervalTicks;
    private final double wireSlowMultiplier;
    private final double abatisDamage;
    private final int abatisDamageIntervalTicks;
    private final double abatisSlowMultiplier;
    private final int abatisHitsPerStage;
    private final double alarmRadius;
    private final double alarmPhaseWarnFraction;
    private final double turretDamage;
    private final double turretRoundsPerMinute;
    private final double turretRange;
    private final double turretVelophyllRange;
    private final double turretMinElevation;
    private final double turretMaxElevation;
    private final double turretTraverseSpeed;
    private final double turretElevationSpeed;
    private final double turretAimTolerance;
    private final int turretRetargetIntervalTicks;
    private final int turretClusterBomblets;
    private final double turretClusterBombletSize;
    private final double turretCorruptRadius;
    private final double turretMortarDisableChance;
    private final double turretBombDisableChance;
    private final double turretShielderDetectRange;
    private final double turretModelScale;
    private final double turretTracerSpeed;
    private final int jammingMaxGroup;
    private final int jammingWeaknessAmplifier;
    private final int jammingSlownessAmplifier;
    private final int jammingEffectTicks;
    private final int jammingIntervalTicks;
    private final int[] baseStockCap;
    private final int[] baseSupplyStart;
    private final double baseSupplyGrowthPerDay;
    private final double baseConnectionBonus;
    private final double baseSupplyBaseBonus;
    private final int baseDeclineMin;
    private final int baseDeclineMax;
    private final int baseFallStreakDays;
    private final SurroundShape baseSurroundShape;
    private final int baseSurroundChunks;
    private final boolean baseSurroundSkipGuarded;
    private final int basePresenceMargin;
    private final double baseStartStockFraction;
    private final java.util.Map<String, Integer> baseWorldgenTypeWeights;
    private final int[] baseWorldgenLevelWeights;
    private final int baseWorldgenCoreMaxRelief;
    private final int baseWorldgenAvoidVillageChunks;
    private final int baseWorldgenAvoidMansionChunks;
    private final java.util.Map<String, Integer> baseReplenishCosts;
    private final double baseReplenishHospitalDiscount;
    private final int baseReplenishIntervalTicks;
    private final java.util.Map<String, Integer> baseUpkeepCosts;
    private final java.util.Map<String, Integer> baseUpkeepStock;
    private final java.util.Map<String, Integer> baseUpkeepDeviceRepair;
    private final int baseUpkeepRestockIntervalTicks;
    private final int baseUpkeepTurretRefillBelow;
    private final int baseUpkeepTurretFill;
    private final int baseUpkeepFuelRefillBelow;
    private final int baseUpkeepFuelFill;
    private final double baseUpkeepFilterSwapWear;
    private final int baseUpkeepSporesEmptyAt;
    private final int baseUpkeepFixTicks;
    private final int baseUpkeepEngageRange;
    private final double baseWatchRadius;
    private final BaseTradeConfig baseTrade;
    private final BaseRoadConfig baseRoad;

    /** 倉庫の品の値段の既定値(id の順。Claude 仮決め: 弾 16 発 = 鉄 1 + 火薬 1 で 4、レッドストーン 2、フィルター 10、補修材 2)。 */
    private static final String[] UPKEEP_ITEMS = {"rounds", "redstone", "filter", "repair"};
    private static final int[] DEFAULT_UPKEEP_COSTS = {4, 2, 10, 2};
    /** 倉庫に置いておく目安の既定値(フィルターは除去装置 × 2 = ユーザー指定、ほかは Claude 仮決め)。 */
    private static final String[] UPKEEP_STOCK = {"roundsPerTurret", "redstonePerPurifier", "filtersPerPurifier", "repairPerLevel"};
    private static final int[] DEFAULT_UPKEEP_STOCK = {64, 8, 2, 64};
    /** 壊された設備を置き直す補修材の既定値(Claude 仮決め: 機関砲台 32・除去装置 16)。 */
    private static final String[] UPKEEP_DEVICES = {"turret", "purifier"};
    private static final int[] DEFAULT_UPKEEP_DEVICE_REPAIR = {32, 16};

    /** 補充の値段の既定値(兵種の id の順。ユーザー指定 10-04「標準」)。 */
    private static final String[] SOLDIER_KINDS = {"rifleman", "artillery", "engineer", "elite"};
    private static final int[] DEFAULT_REPLENISH_COSTS = {100, 200, 120, 400};

    /** 世界生成の基地の種類の重みの既定値(種類の id の順。ユーザー指定 10-04「研究をまれに」、浄化は 0)。 */
    private static final String[] WORLDGEN_TYPES = {"purification", "defense", "supply", "arsenal", "observation", "hospital", "research"};
    private static final int[] DEFAULT_WORLDGEN_TYPE_WEIGHTS = {0, 20, 20, 20, 15, 15, 10};
    /** 世界生成の基地のレベル 1〜5 の重みの既定値(Claude 仮決め: 同じ割合)。 */
    private static final int[] DEFAULT_WORLDGEN_LEVEL_WEIGHTS = {20, 20, 20, 20, 20};

    /** 基地の貯蓄の上限の既定値(レベル 1〜5、Claude 仮決め。「約 1 万・レベルで変える」)。 */
    private static final int[] DEFAULT_STOCK_CAP = {2000, 4000, 6000, 8000, 10000};
    /** 補給値の始まりの既定値(レベル 1〜5、Claude 仮決め。「1 日 1000〜3000」)。 */
    private static final int[] DEFAULT_SUPPLY_START = {1000, 1500, 2000, 2500, 3000};

    private DefenseTable(Json j) {
        Json src = j != null ? j : new Json();
        RepellentLamp lamp = src.repellentLamp != null ? src.repellentLamp : new RepellentLamp();
        BarbedWire wire = src.barbedWire != null ? src.barbedWire : new BarbedWire();
        SporeAlarm alarm = src.sporeAlarm != null ? src.sporeAlarm : new SporeAlarm();
        GunTurret turret = src.gunTurret != null ? src.gunTurret : new GunTurret();
        this.lampRadius = lamp.radius != null ? Math.max(1.0D, lamp.radius) : 12.0D;
        this.lampVelophyllDetectRange = lamp.velophyllDetectRange != null ? Math.max(0.0D, lamp.velophyllDetectRange) : 64.0D;
        this.lampCoverRadius = lamp.coverRadius != null ? Math.max(0.0D, lamp.coverRadius) : 4.0D;
        this.wireDamage = wire.damage != null ? Math.max(0.0D, wire.damage) : 2.0D;
        this.wireDamageIntervalTicks = wire.damageIntervalTicks != null ? Math.max(1, wire.damageIntervalTicks) : 10;
        this.wireSlowMultiplier = wire.slowMultiplier != null ? Math.max(0.01D, Math.min(1.0D, wire.slowMultiplier)) : 0.3D;
        Abatis abatis = src.abatis != null ? src.abatis : new Abatis();
        this.abatisDamage = abatis.damage != null ? Math.max(0.0D, abatis.damage) : 1.0D;
        this.abatisDamageIntervalTicks = abatis.damageIntervalTicks != null ? Math.max(1, abatis.damageIntervalTicks) : 20;
        this.abatisSlowMultiplier = abatis.slowMultiplier != null ? Math.max(0.01D, Math.min(1.0D, abatis.slowMultiplier)) : 0.6D;
        this.abatisHitsPerStage = abatis.hitsPerStage != null ? Math.max(1, Math.min(16, abatis.hitsPerStage)) : 16; // ブロックの状態 HITS が 0〜15 なので 16 まで
        this.alarmRadius = alarm.radius != null ? Math.max(1.0D, alarm.radius) : 96.0D;
        this.alarmPhaseWarnFraction = alarm.phaseWarnFraction != null
                ? Math.max(0.0D, Math.min(1.0D, alarm.phaseWarnFraction)) : 0.9D;
        this.turretDamage = turret.damage != null ? Math.max(0.0D, turret.damage) : 15.0D;
        this.turretRoundsPerMinute = turret.roundsPerMinute != null
                ? Math.max(1.0D, turret.roundsPerMinute) : 240.0D;
        this.turretRange = turret.range != null ? Math.max(1.0D, turret.range) : 48.0D;
        this.turretVelophyllRange = turret.velophyllRange != null
                ? Math.max(1.0D, turret.velophyllRange) : 64.0D;
        this.turretMinElevation = turret.minElevation != null
                ? Math.max(-90.0D, Math.min(90.0D, turret.minElevation)) : -28.0D;
        this.turretMaxElevation = turret.maxElevation != null
                ? Math.max(-90.0D, Math.min(90.0D, turret.maxElevation)) : 85.0D;
        this.turretTraverseSpeed = turret.traverseSpeed != null ? Math.max(0.01D, turret.traverseSpeed) : 1.125D;
        this.turretElevationSpeed = turret.elevationSpeed != null ? Math.max(0.01D, turret.elevationSpeed) : 4.0D;
        this.turretAimTolerance = turret.aimTolerance != null ? Math.max(0.1D, turret.aimTolerance) : 3.0D;
        this.turretRetargetIntervalTicks = turret.retargetIntervalTicks != null
                ? Math.max(1, turret.retargetIntervalTicks) : 10;
        this.turretClusterBomblets = turret.clusterBomblets != null ? Math.max(0, turret.clusterBomblets) : 3;
        this.turretClusterBombletSize = turret.clusterBombletSize != null
                ? Math.max(0.05D, Math.min(1.0D, turret.clusterBombletSize)) : 0.45D;
        this.turretCorruptRadius = turret.corruptRadius != null ? Math.max(0.0D, turret.corruptRadius) : 4.0D;
        this.turretMortarDisableChance = turret.mortarDisableChance != null
                ? Math.max(0.0D, Math.min(1.0D, turret.mortarDisableChance)) : 0.15D;
        this.turretBombDisableChance = turret.bombDisableChance != null
                ? Math.max(0.0D, Math.min(1.0D, turret.bombDisableChance)) : 1.0D;
        this.turretShielderDetectRange = turret.shielderDetectRange != null
                ? Math.max(0.0D, turret.shielderDetectRange) : 40.0D;
        this.turretModelScale = turret.modelScale != null
                ? Math.max(0.1D, Math.min(4.0D, turret.modelScale)) : 1.5D;
        this.turretTracerSpeed = turret.tracerSpeed != null ? Math.max(0.5D, turret.tracerSpeed) : 3.0D;
        JammingLamp jam = src.jammingLamp != null ? src.jammingLamp : new JammingLamp();
        this.jammingMaxGroup = jam.maxGroup != null ? Math.max(0, Math.min(10, jam.maxGroup)) : 6;
        this.jammingWeaknessAmplifier = jam.weaknessAmplifier != null ? Math.max(0, Math.min(9, jam.weaknessAmplifier)) : 0;
        this.jammingSlownessAmplifier = jam.slownessAmplifier != null ? Math.max(0, Math.min(9, jam.slownessAmplifier)) : 1;
        this.jammingEffectTicks = jam.effectTicks != null ? Math.max(1, jam.effectTicks) : 60;
        this.jammingIntervalTicks = jam.intervalTicks != null ? Math.max(1, jam.intervalTicks) : 20;
        AlliedBase base = src.alliedBase != null ? src.alliedBase : new AlliedBase();
        this.baseStockCap = perLevel(base.stockCap, DEFAULT_STOCK_CAP, 1);
        this.baseSupplyStart = perLevel(base.supplyStart, DEFAULT_SUPPLY_START, 0);
        this.baseSupplyGrowthPerDay = base.supplyGrowthPerDay != null ? Math.max(0.0D, base.supplyGrowthPerDay) : 0.01D;
        this.baseConnectionBonus = base.connectionBonus != null ? Math.max(0.0D, base.connectionBonus) : 0.15D;
        this.baseSupplyBaseBonus = base.supplyBaseBonus != null ? Math.max(0.0D, base.supplyBaseBonus) : 0.60D;
        int declineMin = base.declineMin != null ? Math.max(0, base.declineMin) : 2800;
        this.baseDeclineMin = declineMin;
        this.baseDeclineMax = base.declineMax != null ? Math.max(declineMin, base.declineMax) : Math.max(declineMin, 4000);
        this.baseFallStreakDays = base.fallStreakDays != null ? Math.max(1, base.fallStreakDays) : 3;
        this.baseSurroundShape = base.surroundShape != null ? SurroundShape.of(base.surroundShape) : SurroundShape.RING;
        this.baseSurroundChunks = base.surroundChunks != null ? Math.max(1, Math.min(32, base.surroundChunks)) : 8;
        this.baseSurroundSkipGuarded = base.surroundSkipGuarded != null ? base.surroundSkipGuarded : true;
        this.basePresenceMargin = base.presenceMargin != null ? Math.max(0, base.presenceMargin) : 64;
        this.baseStartStockFraction = base.startStockFraction != null
                ? Math.max(0.0D, Math.min(1.0D, base.startStockFraction)) : 1.0D;
        java.util.Map<String, Integer> weights = new java.util.LinkedHashMap<>();
        for (int i = 0; i < WORLDGEN_TYPES.length; i++) {
            Integer given = base.worldgenTypeWeights != null ? base.worldgenTypeWeights.get(WORLDGEN_TYPES[i]) : null;
            weights.put(WORLDGEN_TYPES[i], given != null ? Math.max(0, given) : DEFAULT_WORLDGEN_TYPE_WEIGHTS[i]);
        }
        this.baseWorldgenTypeWeights = java.util.Collections.unmodifiableMap(weights);
        this.baseWorldgenLevelWeights = perLevel(base.worldgenLevelWeights, DEFAULT_WORLDGEN_LEVEL_WEIGHTS, 0);
        this.baseWorldgenCoreMaxRelief = base.worldgenCoreMaxRelief != null ? Math.max(0, base.worldgenCoreMaxRelief) : 20;
        this.baseWorldgenAvoidVillageChunks = base.worldgenAvoidVillageChunks != null
                ? Math.max(0, Math.min(32, base.worldgenAvoidVillageChunks)) : 12;
        this.baseWorldgenAvoidMansionChunks = base.worldgenAvoidMansionChunks != null
                ? Math.max(0, Math.min(32, base.worldgenAvoidMansionChunks)) : 10;
        java.util.Map<String, Integer> costs = new java.util.LinkedHashMap<>();
        for (int i = 0; i < SOLDIER_KINDS.length; i++) {
            Integer given = base.replenishCosts != null ? base.replenishCosts.get(SOLDIER_KINDS[i]) : null;
            costs.put(SOLDIER_KINDS[i], given != null ? Math.max(0, given) : DEFAULT_REPLENISH_COSTS[i]);
        }
        this.baseReplenishCosts = java.util.Collections.unmodifiableMap(costs);
        this.baseReplenishHospitalDiscount = base.replenishHospitalDiscount != null
                ? Math.max(0.0D, Math.min(1.0D, base.replenishHospitalDiscount)) : 0.2D;
        this.baseReplenishIntervalTicks = base.replenishIntervalTicks != null ? Math.max(20, base.replenishIntervalTicks) : 600;
        this.baseUpkeepCosts = named(base.upkeepCosts, UPKEEP_ITEMS, DEFAULT_UPKEEP_COSTS);
        this.baseUpkeepStock = named(base.upkeepStock, UPKEEP_STOCK, DEFAULT_UPKEEP_STOCK);
        this.baseUpkeepDeviceRepair = named(base.upkeepDeviceRepair, UPKEEP_DEVICES, DEFAULT_UPKEEP_DEVICE_REPAIR);
        this.baseUpkeepRestockIntervalTicks = base.upkeepRestockIntervalTicks != null ? Math.max(20, base.upkeepRestockIntervalTicks) : 1200;
        this.baseUpkeepTurretFill = base.upkeepTurretFill != null ? Math.max(1, Math.min(576, base.upkeepTurretFill)) : 128;
        this.baseUpkeepTurretRefillBelow = base.upkeepTurretRefillBelow != null
                ? Math.max(0, Math.min(baseUpkeepTurretFill - 1, base.upkeepTurretRefillBelow)) : 64;
        this.baseUpkeepFuelFill = base.upkeepFuelFill != null ? Math.max(1, Math.min(64, base.upkeepFuelFill)) : 16;
        this.baseUpkeepFuelRefillBelow = base.upkeepFuelRefillBelow != null
                ? Math.max(0, Math.min(baseUpkeepFuelFill - 1, base.upkeepFuelRefillBelow)) : 4;
        this.baseUpkeepFilterSwapWear = base.upkeepFilterSwapWear != null
                ? Math.max(0.05D, Math.min(1.0D, base.upkeepFilterSwapWear)) : 0.75D;
        this.baseUpkeepSporesEmptyAt = base.upkeepSporesEmptyAt != null ? Math.max(1, Math.min(64, base.upkeepSporesEmptyAt)) : 32;
        this.baseUpkeepFixTicks = base.upkeepFixTicks != null ? Math.max(1, base.upkeepFixTicks) : 10;
        this.baseUpkeepEngageRange = base.upkeepEngageRange != null ? Math.max(0, Math.min(48, base.upkeepEngageRange)) : 8;
        this.baseWatchRadius = base.watchRadius != null ? Math.max(0.0D, Math.min(4096.0D, base.watchRadius)) : 512.0D;
        this.baseTrade =base.trade != null ? BaseTradeConfig.of(base.trade) : BaseTradeConfig.DEFAULT;
        this.baseRoad = base.road != null ? BaseRoadConfig.of(base.road) : BaseRoadConfig.DEFAULT;
    }

    /** 名前付きの値の表(無い名前は既定値、負の値は 0)。 */
    private static java.util.Map<String, Integer> named(java.util.Map<String, Integer> given, String[] names, int[] fallback) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            Integer v = given != null ? given.get(names[i]) : null;
            out.put(names[i], v != null ? Math.max(0, v) : fallback[i]);
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /** レベル 1〜5 の 5 つの値。足りない・無いところは既定値、{@code min} より小さい値は {@code min}。 */
    private static int[] perLevel(int[] given, int[] fallback, int min) {
        int[] out = fallback.clone();
        if (given != null) {
            for (int i = 0; i < Math.min(given.length, out.length); i++) {
                out[i] = Math.max(min, given[i]);
            }
        }
        return out;
    }

    /** レベル {@code level}(1〜5、外は端に寄せる)の基地の貯蓄の上限。 */
    public int baseStockCap(int level) {
        return baseStockCap[Math.max(1, Math.min(baseStockCap.length, level)) - 1];
    }

    /** レベル {@code level} の基地の補給値の始まり(1 日)。 */
    public int baseSupplyStart(int level) {
        return baseSupplyStart[Math.max(1, Math.min(baseSupplyStart.length, level)) - 1];
    }

    /** 補給値が 1 日ごとに始まりの値のこの割合ずつ増える。 */
    public double baseSupplyGrowthPerDay() {
        return baseSupplyGrowthPerDay;
    }

    /** 道でつながった基地 1 つごとの割り増し(足し算、ユーザー指定 +15%)。 */
    public double baseConnectionBonus() {
        return baseConnectionBonus;
    }

    /** 補給基地とのつながり 1 本の割り増し(ユーザー指定 +60%)。 */
    public double baseSupplyBaseBonus() {
        return baseSupplyBaseBonus;
    }

    /** 周りが全部汚染された留守中の基地が 1 日に減る量の下限(ユーザー指定 2800)。 */
    public int baseDeclineMin() {
        return baseDeclineMin;
    }

    /** 同じく上限(ユーザー指定 4000)。 */
    public int baseDeclineMax() {
        return baseDeclineMax;
    }

    /** 「減る量 > 回復量」の日がこれだけ続くと陥落(ユーザー指定 3 日)。 */
    public int baseFallStreakDays() {
        return baseFallStreakDays;
    }

    public SurroundShape baseSurroundShape() {
        return baseSurroundShape;
    }

    /** 輪なら外周からの幅、円なら中心からの半径(チャンク)。 */
    public int baseSurroundChunks() {
        return baseSurroundChunks;
    }

    /** 胞子除去装置が台帳から守っているチャンクを、周りの数え方の分母から外すか。 */
    public boolean baseSurroundSkipGuarded() {
        return baseSurroundSkipGuarded;
    }

    /** 「読み込まれている」の幅(計画の外接の半分に足すブロック数)。 */
    public int basePresenceMargin() {
        return basePresenceMargin;
    }

    /** 新しい基地の最初の貯蓄(上限に対する割合)。 */
    public double baseStartStockFraction() {
        return baseStartStockFraction;
    }

    /** 世界生成の基地の種類 {@code typeId} の重み(知らない id は 0)。 */
    public int baseWorldgenTypeWeight(String typeId) {
        return baseWorldgenTypeWeights.getOrDefault(typeId, 0);
    }

    /** 世界生成の基地のレベル {@code level}(1〜5)の重み。 */
    public int baseWorldgenLevelWeight(int level) {
        return baseWorldgenLevelWeights[Math.max(1, Math.min(baseWorldgenLevelWeights.length, level)) - 1];
    }

    /** 世界生成の基地の中心の起伏の上限。 */
    public int baseWorldgenCoreMaxRelief() {
        return baseWorldgenCoreMaxRelief;
    }

    /** 村が来うる場所からこのチャンク数以内には世界生成の基地を生まない。 */
    public int baseWorldgenAvoidVillageChunks() {
        return baseWorldgenAvoidVillageChunks;
    }

    /** 森の洋館が来うる場所からこのチャンク数以内には世界生成の基地を生まない。 */
    public int baseWorldgenAvoidMansionChunks() {
        return baseWorldgenAvoidMansionChunks;
    }

    /** 兵種 {@code kindId} の守備兵 1 体の補充の値段(貯蓄。知らない id は 0)。 */
    public int baseReplenishCost(String kindId) {
        return baseReplenishCosts.getOrDefault(kindId, 0);
    }

    /** 野戦病院の基地の補充の割引(0〜1)。 */
    public double baseReplenishHospitalDiscount() {
        return baseReplenishHospitalDiscount;
    }

    /** 補充の間隔(tick)。 */
    public int baseReplenishIntervalTicks() {
        return baseReplenishIntervalTicks;
    }

    /** 倉庫の品 {@code item}({@code rounds} = 弾 16 発・{@code redstone}・{@code filter}・{@code repair})の値段(貯蓄。知らない id は 0)。 */
    public int baseUpkeepCost(String item) {
        return baseUpkeepCosts.getOrDefault(item, 0);
    }

    /** 倉庫に置いておく目安 {@code key}({@code roundsPerTurret}・{@code redstonePerPurifier}・{@code filtersPerPurifier}・{@code repairPerLevel})。 */
    public int baseUpkeepStock(String key) {
        return baseUpkeepStock.getOrDefault(key, 0);
    }

    /** 壊された設備 {@code device}({@code turret}・{@code purifier})を置き直す補修材の数。 */
    public int baseUpkeepDeviceRepair(String device) {
        return baseUpkeepDeviceRepair.getOrDefault(device, 1);
    }

    /** 倉庫へ買い足す間隔(tick)。 */
    public int baseUpkeepRestockIntervalTicks() {
        return baseUpkeepRestockIntervalTicks;
    }

    /** 機関砲台の弾がこれ以下なら足す。 */
    public int baseUpkeepTurretRefillBelow() {
        return baseUpkeepTurretRefillBelow;
    }

    /** 機関砲台の弾をここまで足す。 */
    public int baseUpkeepTurretFill() {
        return baseUpkeepTurretFill;
    }

    /** 除去装置のレッドストーンがこれ以下なら足す。 */
    public int baseUpkeepFuelRefillBelow() {
        return baseUpkeepFuelRefillBelow;
    }

    /** 除去装置のレッドストーンをここまで足す。 */
    public int baseUpkeepFuelFill() {
        return baseUpkeepFuelFill;
    }

    /** フィルターを替えるすり減りの割合(0〜1)。 */
    public double baseUpkeepFilterSwapWear() {
        return baseUpkeepFilterSwapWear;
    }

    /** 取れた胞子がこの数以上なら取り出す。 */
    public int baseUpkeepSporesEmptyAt() {
        return baseUpkeepSporesEmptyAt;
    }

    /** 修理と解体の 1 マスの時間(tick)。 */
    public int baseUpkeepFixTicks() {
        return baseUpkeepFixTicks;
    }

    /** 工兵が仕事を置いて戦う敵の近さ(マス)。 */
    public int baseUpkeepEngageRange() {
        return baseUpkeepEngageRange;
    }

    /** TD の予告を見張り台が知らせる、ターゲットから基地の中心までの距離(ブロック、水平。0 = 知らせない。順 2 の着手順 8)。 */
    public double baseWatchRadius() {
        return baseWatchRadius;
    }

    /** 信頼・寄付・取引の数値(順 2 の着手順 5)。 */
    public BaseTradeConfig baseTrade() {
        return baseTrade;
    }

    /** 道とつながりの数値(順 2 の着手順 7)。 */
    public BaseRoadConfig baseRoad() {
        return baseRoad;
    }

    /** 妨害灯が追い出すグループの上限(この番号以下。ユーザー指定 6)。 */
    public int jammingMaxGroup() {
        return jammingMaxGroup;
    }

    public int jammingWeaknessAmplifier() {
        return jammingWeaknessAmplifier;
    }

    public int jammingSlownessAmplifier() {
        return jammingSlownessAmplifier;
    }

    public int jammingEffectTicks() {
        return jammingEffectTicks;
    }

    public int jammingIntervalTicks() {
        return jammingIntervalTicks;
    }

    public double lampRadius() {
        return lampRadius;
    }

    public double lampVelophyllDetectRange() {
        return lampVelophyllDetectRange;
    }

    public double lampCoverRadius() {
        return lampCoverRadius;
    }

    public double wireDamage() {
        return wireDamage;
    }

    public int wireDamageIntervalTicks() {
        return wireDamageIntervalTicks;
    }

    public double wireSlowMultiplier() {
        return wireSlowMultiplier;
    }

    public double abatisDamage() {
        return abatisDamage;
    }

    public int abatisDamageIntervalTicks() {
        return abatisDamageIntervalTicks;
    }

    public double abatisSlowMultiplier() {
        return abatisSlowMultiplier;
    }

    /** 1 段傷むまでに刺す回数(棒 1 本で戻る量でもある)。 */
    public int abatisHitsPerStage() {
        return abatisHitsPerStage;
    }

    public double alarmRadius() {
        return alarmRadius;
    }

    public double alarmPhaseWarnFraction() {
        return alarmPhaseWarnFraction;
    }

    /** 機関砲台: 1 発のダメージ。 */
    public double turretDamage() {
        return turretDamage;
    }

    /** 機関砲台: 発射速度(毎分の発数)。 */
    public double turretRoundsPerMinute() {
        return turretRoundsPerMinute;
    }

    /**
     * 機関砲台: 発射の間隔(tick)。毎分 240 発 = 5 tick に 1 発。
     *
     * <p>撃つ瞬間は {@code (ゲーム内時刻 + 台ごとの位相) % この値 == 0} で決まる ——
     * <b>サーバーとクライアントが同じ式で同じ瞬間を出せる</b>ので、発砲のたびにパケットを
     * 送らなくても発砲炎と反動が揃う({@code block/GunTurretBlockEntity})。
     */
    public int turretFireIntervalTicks() {
        return Math.max(1, (int) Math.round(1200.0D / turretRoundsPerMinute));
    }

    /** 機関砲台: 検知・射程(ブロック)。 */
    public double turretRange() {
        return turretRange;
    }

    /**
     * 機関砲台: <b>ヴェロフィルにだけ</b>使う検知・射程(ブロック、既定 64 = 通常の倍。ユーザー指定 2026-09-21)。
     * 爆弾を抱えて来る相手は、落とされる前に遠くで仕留めたいので特別扱いする。
     * 落ちてきた爆弾そのものや他の敵は {@link #turretRange()} のまま。
     */
    public double turretVelophyllRange() {
        return turretVelophyllRange;
    }

    /** 機関砲台: 俯角(下向きが負、既定 −28 度)。 */
    public double turretMinElevation() {
        return turretMinElevation;
    }

    /** 機関砲台: 仰角(既定 +85 度)。 */
    public double turretMaxElevation() {
        return turretMaxElevation;
    }

    /** 機関砲台: 旋回速度(度/tick)。既定 1.125 = 秒間 22.5 度(真後ろを向くのに 8 秒)。 */
    public double turretTraverseSpeed() {
        return turretTraverseSpeed;
    }

    /** 機関砲台: 俯仰速度(度/tick)。既定 4.0 = 秒間 80 度(台座の旋回より速い — 上を狙い直すのに使うため)。 */
    public double turretElevationSpeed() {
        return turretElevationSpeed;
    }

    /**
     * 機関砲台: 胞子爆弾がこの距離以内で炸裂すると、砲台は胞子に覆われて<b>止まる</b>
     * (既定 4。ユーザー指定 2026-09-21)。右クリックで拭えば動き出す。
     */
    public double turretCorruptRadius() {
        return turretCorruptRadius;
    }

    /**
     * 機関砲台: <b>過繫茂したスカトゥリウムの迫撃砲</b>の弾が {@link #turretCorruptRadius} 以内で炸裂したとき、
     * 砲台が覆われて止まる確率(既定 0.15。ユーザー指定: 2026-09-24「迫撃砲が当たって 35% で無効化」→ 2026-09-25 に 15% へ)。
     * <b>1 発ごと・砲台ごと</b>に振る。斉射は 3 発なので、3 発とも近くに落ちれば斉射 1 回で止まる確率は約 39%。
     */
    public double turretMortarDisableChance() {
        return turretMortarDisableChance;
    }

    /**
     * 機関砲台: 迫撃砲<b>以外</b>の胞子爆弾(ヴェロフィルの爆撃・ペディキュラや胞子弾の破片)で止まる確率
     * (既定 1.0 = 2026-09-21 からの「必ず止まる」のまま。確率にしたのは迫撃砲だけ)。
     */
    public double turretBombDisableChance() {
        return turretBombDisableChance;
    }

    /**
     * 機関砲台: 過繫茂したスカトゥリウムが<b>迫撃砲で潰しに来る</b>距離(既定 40 = 迫撃砲の最大射程)。
     * 砲台の射程が 32 だった間は、砲台に撃たれない位置から撃ち込めた(2026-10-02 に射程を 48 へ伸ばしたので、今は砲台の方が長い)。
     */
    public double turretShielderDetectRange() {
        return turretShielderDetectRange;
    }

    /**
     * 機関砲台: モデルの倍率(既定 1.5 = 台座がおよそ 1.3 ブロック四方、銃身が 2.4 ブロック)。<b>銃口の位置も同じ倍率で動く</b>ので、
     * 見た目と弾の出どころがずれることはない({@code defense/GunTurretGeometry})。
     */
    public double turretModelScale() {
        return turretModelScale;
    }

    /**
     * 機関砲台: 見える曳光弾の速さ(ブロック/tick、既定 3.0)。
     * <b>当たり判定は即着弾</b>なので、これは見た目が目標へ届くまでの速さでしかない。
     */
    public double turretTracerSpeed() {
        return turretTracerSpeed;
    }

    /** 機関砲台: この角度(度)まで銃口が目標に寄っていれば撃つ。 */
    public double turretAimTolerance() {
        return turretAimTolerance;
    }

    /** 機関砲台: 目標を選び直す間隔(tick)。目標を失ったときは間隔を待たずに選び直す。 */
    public int turretRetargetIntervalTicks() {
        return turretRetargetIntervalTicks;
    }

    /** 増援を運ぶ胞子弾を撃ち落としたときに散る、小さな胞子爆弾の数。 */
    public int turretClusterBomblets() {
        return turretClusterBomblets;
    }

    /** その小さな胞子爆弾の大きさ(1.0 = ヴェロフィルの爆弾と同じ。威力も半径もこの倍率)。 */
    public double turretClusterBombletSize() {
        return turretClusterBombletSize;
    }

    public static DefenseTable fromJson(Json j) {
        return new DefenseTable(j);
    }

    public static final DefenseTable DEFAULT = new DefenseTable(null);
}
