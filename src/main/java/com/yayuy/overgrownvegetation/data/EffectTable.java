package com.yayuy.overgrownvegetation.data;

/**
 * {@code effects.json} — 「胞子感染」デバフの設計値(2026-09-10 の刷新版)。
 *
 * <h2>構成</h2>
 * <ul>
 *     <li>{@code exposure}: 胞子濃度による感染判定(プレイヤーの低濃度帯ロール / 高濃度帯の滞在時間、
 *     Mob の滞在時間)。{@code SporeInfectionTracker} が使う。</li>
 *     <li>{@code level1} / {@code level2} / {@code level3}: 各段階(amplifier 0 / 1 / 2)の症状と持続時間、
 *     その段階へ進む条件(攻撃での悪化確率・過繫茂バイオーム滞在時間・巨大樹の核の半径)。</li>
 *     <li>{@code sporeDevices}: 胞子除去装置 / 胞子拡散装置({@code SporeDeviceBlock})の設計値。</li>
 *     <li>{@code gearContamination}: 「装備の胞子汚染」({@code spore.GearContamination})— 進む速さ・段ごとの
 *     性能低下率・除染機の速さ。</li>
 *     <li>{@code waterRot}: 「水ぐされ」({@code effect.WaterRotTracker})— 発症までの浸水時間・
 *     上がってからの持続・毎秒のダメージ。</li>
 * </ul>
 *
 * <p>防御力/移動速度の低下は段階ごとに<b>別値</b>で持つ(バニラの「amplifier+1 倍」ではない —
 * {@code SporeInfectionMobEffect} が NeoForge の level curve 付き属性修飾で段階ごとの値を返す)。
 *
 * <p>胞子感染3(amplifier 2)は満了するとプレイヤーが死亡する({@code killPlayerOnExpiry})。
 */
public final class EffectTable {

    public static final class Json {
        public SporeInfection sporeInfection;
        public SporeDevices sporeDevices;
        public GearContamination gearContamination;
        /** スポロフィルが味方に配る「スポロフィルの胞子」(バフ)。 */
        public SporophyllSpores sporophyllSpores;
        /** スポロフィルが敵に付ける「纏わりついた胞子」(デバフ)。 */
        public ClingingSpores clingingSpores;
        /** フィロセンスに見つかっている「Philosense is watching you」(デバフ)。 */
        public SimpleEffect phyllosensWatch;
        /** フィロセンスが味方に配る「フィロセンスの誘導」(バフ)。 */
        public SimpleEffect phyllosensGuidance;
        /** 水に浸かりすぎたグループ1〜10 の Mob が起こす「水ぐされ」(デバフ)。 */
        public WaterRot waterRot;
        /** 「熟した垂れ草の光る果実」を食べると付く 2 つの効果(バフ + デバフ)。 */
        public GlowFruit glowFruit;
        /** 「未発達な種子」を食べると付く 3 つの効果(再生・耐性 + 香ばしいにおい)。 */
        public UndevelopedSeed undevelopedSeed;
        /** 「大きな未発達な果実」を食べると付く効果一式(強力なバフ + 胞子感染の進行 + 遅れてくる空腹)。 */
        public LargeUndevelopedFruit largeUndevelopedFruit;
    }

    /** 持続だけを持つ効果。 */
    public static final class SimpleEffect {
        /** 効果の持続(tick)。範囲内に居るあいだは配り直されるので、実際はこれより長く続く。 */
        public Integer durationTicks;
    }

    /** 「スポロフィルの胞子」(バフ)。 */
    public static final class SporophyllSpores {
        public Integer durationTicks;
        /** 効果の段階(0 = I)。 */
        public Integer amplifier;
        /** 攻撃時に相手の防御力を無視する割合(0〜1)。 */
        public Double armorPiercing;
        /** 攻撃時に相手の防具の耐久を削る量 = 与ダメージ × これ。 */
        public Double armorDurabilityFactor;
    }

    /** 「纏わりついた胞子」(デバフ)。 */
    public static final class ClingingSpores {
        public Integer durationTicks;
        /** 効果の段階(0 = I)。移動速度低下・攻撃力低下の強さもこれで決まる。 */
        public Integer amplifier;
        /** グループ4 の Mob からの被検知距離が伸びる量(ブロック)。 */
        public Double detectionBonus;
        /** true なら胞子感染の進行を早める。 */
        public Boolean escalatesInfection;
    }

    /**
     * 「熟した垂れ草の光る果実」({@code overgrown_glowberry})を食べたときに付く 2 つの効果。
     * {@code effect/FruitEffects} が読む。
     */
    public static final class GlowFruit {
        /** 「栄養満点!」(バフ)の持続 tick。付いているあいだ毎 tick 食料ゲージが 1 回復する。 */
        public Integer wellFedTicks;
        /** 「香ばしいにおい」(デバフ)の持続 tick。 */
        public Integer savoryScentTicks;
        /** 「香ばしいにおい」が付いているあいだ、過繫茂 Mob からの被検知距離が伸びる量(ブロック)。 */
        public Double detectionBonus;
    }

    /**
     * 「未発達な種子」({@code undeveloped_seed})を食べたときに付く効果。
     * {@code effect/FruitEffects} が読む。再生と耐性はバニラの効果をそのまま使う。
     */
    public static final class UndevelopedSeed {
        /** バニラ「再生」の持続 tick(40 = 2 秒)。0 で付けない。 */
        public Integer regenerationTicks;
        /** バニラ「再生」の段階(0 = 再生 I)。 */
        public Integer regenerationAmplifier;
        /** バニラ「耐性」の持続 tick(40 = 2 秒)。0 で付けない。 */
        public Integer resistanceTicks;
        /** バニラ「耐性」の段階(0 = 耐性 I)。 */
        public Integer resistanceAmplifier;
        /** 「香ばしいにおい」(デバフ)の持続 tick(100 = 5 秒)。0 で付けない。 */
        public Integer savoryScentTicks;
    }

    /**
     * 「大きな未発達な果実」({@code large_undeveloped_fruit})を食べたときに付く効果一式。
     * {@code effect/FruitEffects} が読む。
     *
     * <p>強力なバフ(再生III・耐性II・栄養満点)と引き換えに、<b>胞子感染が一段進み</b>
     * ({@code escalateInfection})、{@code hungerDelayTicks} 経ってから激しい空腹が来る。
     */
    public static final class LargeUndevelopedFruit {
        /** バニラ「再生」の持続 tick(600 = 30 秒)。0 で付けない。 */
        public Integer regenerationTicks;
        /** バニラ「再生」の段階(2 = 再生 III)。 */
        public Integer regenerationAmplifier;
        /** バニラ「耐性」の持続 tick(600 = 30 秒)。0 で付けない。 */
        public Integer resistanceTicks;
        /** バニラ「耐性」の段階(1 = 耐性 II)。 */
        public Integer resistanceAmplifier;
        /** 「栄養満点！」(バフ)の持続 tick(3600 = 3 分)。0 で付けない。 */
        public Integer wellFedTicks;
        /** 「香ばしいにおい」(デバフ)の持続 tick(6000 = 5 分)。0 で付けない。 */
        public Integer savoryScentTicks;
        /**
         * true なら食べるたびに胞子感染が一段進む(未感染 → 胞子感染1、1 → 2、2 → 3)。
         * <b>すでに胞子感染3 を持っている場合は、切れるのを待たずにその場で「満了時の結末」</b>
         * ({@code level3.killPlayerOnExpiry})<b>が起きる</b>。
         */
        public Boolean escalateInfection;
        /** 食べてから空腹が来るまでの tick(7200 = 6 分)。 */
        public Integer hungerDelayTicks;
        /** 遅れて来るバニラ「空腹」の持続 tick(3600 = 3 分)。0 で付けない。 */
        public Integer hungerTicks;
        /** 遅れて来るバニラ「空腹」の段階(2 = 空腹 III)。 */
        public Integer hungerAmplifier;
    }

    /** 「水ぐされ」(デバフ)。{@code effect/WaterRotTracker} と {@code effect/WaterRotEffect} が読む。 */
    public static final class WaterRot {
        /** false にすると新規の発症を止める(すでに付いているぶんは自然満了)。 */
        public Boolean enabled;
        /** 水に<b>連続で</b>これだけ浸かると発症する tick 数(600 = 30 秒)。水から出ると 0 に戻る。 */
        public Integer soakTicks;
        /**
         * 効果の持続 tick(600 = 30 秒)。水に浸かっているあいだは判定のたびに張り直されるので、
         * 実際には「上がってからの残り時間」として働く。
         */
        public Integer lingerTicks;
        /** 1 回のダメージ量。 */
        public Double damagePerHit;
        /** ダメージの間隔 tick(20 = 毎秒 1 回)。 */
        public Integer damageIntervalTicks;
        /** 水に浸かっているかの判定間隔 tick。短いほど 30 秒の刻みが細かくなるが負荷も増える。 */
        public Integer checkIntervalTicks;
    }

    /** 「水ぐされ」の確定値。 */
    public record WaterRotSpec(boolean enabled, int soakTicks, int lingerTicks, double damagePerHit,
                               int damageIntervalTicks, int checkIntervalTicks) {
    }

    /** 胞子除去装置 / 胞子拡散装置({@code block/SporeDeviceBlock})の設計値。 */
    public static final class SporeDevices {
        public Device purifier;
        public Device diffuser;
    }

    public static final class Device {
        /** 装置のあるチャンクへの胞子濃度の変化量 %(除去装置は −、拡散装置は +。ここには正の値を書く)。 */
        public Double centerPercent;
        /** 半径の末端での変化量 %。中心から末端まで距離(ブロック、水平ユークリッド)に比例して直線的に減る。 */
        public Double edgePercent;
        /**
         * 効果半径(ブロック、水平の円形)。既定 48(= 従来の 3 チャンク相当)。
         * 胞子濃度(チャンク単位)は「装置のあるチャンクは必ず centerPercent、他は装置からチャンク中心までの距離」で、
         * ブロック汚染・汚染シダ湧きの阻害は判定地点そのものまでの距離で求める。
         */
        public Integer radiusBlocks;
        /** 旧キー(チャンク単位)。{@code radiusBlocks} が無いときだけ ×16 して読み替える。 */
        public Integer radiusChunks;
        /** レッドストーンダスト 1 個で稼働する tick 数。 */
        public Integer ticksPerRedstone;
        /** 除去装置: この tick 数稼働するごとに「胞子」を産出する。 */
        public Integer sporeIntervalTicks;
        /** 除去装置: 周囲のもともとの胞子濃度が 100% のときの 1 サイクルあたりの「胞子」産出数(濃度に比例、端数は確率)。 */
        public Double sporesPerCycleAtFull;
        /** 拡散装置: レッドストーンダスト 1 個と一緒に消費する「胞子」の個数。 */
        public Integer sporesPerRedstone;
        /**
         * 除去装置: 稼働中、効果範囲内のブロック汚染(汚染シダ・死体・過繫茂バイオームのにじみ/深部汚染・
         * 建築浸食などすべての経路)を阻害するか。阻害率はそのチャンクへの変化量 %(centerPercent〜edgePercent)
         * と同じで、装置のチャンク(100%)では完全に止まり、末端(10%)では 10% の確率で汚染が不発になる。
         */
        public Boolean inhibitCorruption;
        /** 除去装置: 稼働中、効果範囲内での汚染シダ(汚染水草含む)のスポーンを阻害するか(阻害率は同上)。 */
        public Boolean inhibitFernSpawn;
        /**
         * 除去装置: 稼働中、効果範囲内のチャンクが過繫茂/繫茂バイオームへ書き換わるのを止めるか。
         * こちらは確率ではなく、範囲内(変化量が 0 より大きいチャンク)なら必ず止める。
         */
        public Boolean inhibitBiomeConversion;
        /**
         * <b>フィルターが要るか</b>(2026-09-21 のユーザー指定「浄化器のフィルターを消耗品に」)。
         * true の装置は胞子フィルターが挿さっていないと動かない。除去装置だけ true。
         */
        public Boolean requiresFilter;
        /**
         * 産出サイクル 1 回でフィルターが減る量(胞子の産出量に関わらず掛かる基本の目詰まり)。
         * きれいな空気の中でも少しずつ減るので、置きっぱなしでも交換は要る。
         */
        public Integer filterWearPerCycle;
        /**
         * <b>胞子 1 個あたり</b>の追加の減り。濃いところほど早く目詰まりする ——
         * これが「汚れた空気ほどフィルターが保たない」という手応えになる。
         */
        public Integer filterWearPerSpore;
        /**
         * 除染装置が 1 サイクルでフィルターの耐久をいくつ戻すか
         * (ユーザー指定 2026-09-21「使い切ったフィルターは除染装置で再使用できるように」)。
         * サイクルの長さは装備の除染と同じ {@code gearContamination.decontaminationTicksPerPercent}。
         * 既定 2 なら耐久 256 を洗い切るのに 128 サイクル = およそ 4 分半。
         */
        public Integer filterRepairPerCycle;
    }

    /** 1 装置ぶんの確定値。 */
    public record DeviceSpec(double centerPercent, double edgePercent, int radiusBlocks, int ticksPerRedstone,
                             int sporeIntervalTicks, double sporesPerCycleAtFull, int sporesPerRedstone,
                             boolean inhibitCorruption, boolean inhibitFernSpawn, boolean inhibitBiomeConversion,
                             boolean requiresFilter, int filterWearPerCycle, int filterWearPerSpore,
                             int filterRepairPerCycle) {

        /** 装置から {@code distanceBlocks}(水平ユークリッド)離れた地点への変化量 %(絶対値)。半径外は 0。 */
        public double percentAt(double distanceBlocks) {
            if (distanceBlocks > radiusBlocks) {
                return 0.0D;
            }
            if (radiusBlocks <= 0) {
                return centerPercent;
            }
            return centerPercent - (centerPercent - edgePercent) * (distanceBlocks / radiusBlocks);
        }

        /** 効果が届きうる最大のチャンク距離(チェビシェフ)。キャッシュ破棄の範囲などに使う。 */
        public int reachChunks() {
            return Math.max(1, (radiusBlocks + 15) / 16 + 1);
        }
    }

    /** 「装備の胞子汚染」({@link com.yayuy.overgrownvegetation.spore.GearContamination})の設計値。 */
    public static final class GearContamination {
        /** 機能そのものの ON/OFF。 */
        public Boolean enabled;
        /** この胞子濃度 % 未満のところでは汚染が進まない(既定 15)。 */
        public Double minConcentrationPercent;
        /** 胞子濃度がちょうど {@code minConcentrationPercent} のとき、汚染 0% → 100% にかかる tick 数(既定 72000 = 1 時間)。 */
        public Integer ticksToFullAtMin;
        /** 胞子濃度 100% のとき、汚染 0% → 100% にかかる tick 数(既定 12000 = 10 分)。 */
        public Integer ticksToFullAtMax;
        /** 判定の間隔 tick(既定 100 = 5 秒。1 回の判定で間隔ぶんまとめて進める)。 */
        public Integer checkIntervalTicks;
        /** true なら持ち物すべて、false なら手に持っている物と身に着けている防具だけが汚染される。 */
        public Boolean wholeInventory;
        /** 汚染度(%)→ 性能低下率(0〜1)の段。昇順でなくてよい(満たす中で一番大きい低下率を採る)。 */
        public java.util.List<Step> steps;
        /** 除染機({@link com.yayuy.overgrownvegetation.block.DecontaminatorBlock})が汚染を 1% 減らすのにかかる tick 数(既定 40 = 2 秒)。 */
        public Integer decontaminationTicksPerPercent;
        /** 樹液結晶で処理した装備の、汚染の進みに掛ける倍率(既定 0.1 = 10 分の 1 の速さ)。 */
        public Double sapCrystalRateFactor;
    }

    /** {@link GearContamination#steps} の 1 段。 */
    public static final class Step {
        /** この汚染度(%)以上のとき、 */
        public Double atLeastPercent;
        /** 性能がこの割合(0〜1)だけ下がる。 */
        public Double performanceReduction;
    }

    /** {@link Step} の確定値。 */
    public record StepSpec(double atLeastPercent, double performanceReduction) {
    }

    /** 「装備の胞子汚染」の確定値。 */
    public record GearContaminationSpec(boolean enabled, double minConcentrationPercent, int ticksToFullAtMin,
                                        int ticksToFullAtMax, int checkIntervalTicks, boolean wholeInventory,
                                        java.util.List<StepSpec> steps, int decontaminationTicksPerPercent,
                                        double sapCrystalRateFactor) {

        /**
         * 胞子濃度 {@code concentration}(%)の場所で 1 tick に進む汚染度(%)。
         * 下限濃度で {@code ticksToFullAtMin}、100% で {@code ticksToFullAtMax} かけて 100% に届くよう、
         * 「満了までの tick 数」を濃度で直線補間する。下限未満は 0(進まない)。
         */
        public double gainPerTick(double concentration) {
            if (!enabled || concentration < minConcentrationPercent) {
                return 0.0D;
            }
            double span = 100.0D - minConcentrationPercent;
            double t = span <= 0.0D ? 1.0D : Math.max(0.0D, Math.min(1.0D, (concentration - minConcentrationPercent) / span));
            double ticks = ticksToFullAtMin + (ticksToFullAtMax - ticksToFullAtMin) * t;
            return ticks <= 0.0D ? 100.0D : 100.0D / ticks;
        }

        /** 汚染度 {@code percent}(%)のときの性能低下率(0〜1)。 */
        public double reductionAt(double percent) {
            double best = 0.0D;
            for (StepSpec s : steps) {
                if (percent >= s.atLeastPercent() && s.performanceReduction() > best) {
                    best = s.performanceReduction();
                }
            }
            return Math.max(0.0D, Math.min(1.0D, best));
        }
    }

    public static final class SporeInfection {
        public Exposure exposure;
        public Level level1;
        public Level level2;
        public Level level3;
    }

    /** 胞子濃度による感染判定。 */
    public static final class Exposure {
        /** プレイヤー: 低濃度帯の下限 %(この値未満は感染しない・滞在カウントもリセット)。 */
        public Double playerLowMinPercent;
        /** プレイヤー: 低濃度帯では、この tick 数滞在するごとに「胞子濃度 % と同じ確率」で胞子感染1 をロールする。 */
        public Integer playerLowRollIntervalTicks;
        /** プレイヤー: 高濃度帯の下限 %(この値以上は滞在時間で確定感染)。 */
        public Double playerHighMinPercent;
        /** プレイヤー: 高濃度帯にこの tick 数連続滞在で胞子感染1 が確定付与される。 */
        public Integer playerHighDwellTicks;
        /** Mob: この % 以上のチャンクで滞在カウントが進む。 */
        public Double mobMinPercent;
        /** Mob: この tick 数連続滞在で胞子感染1 が付与される。 */
        public Integer mobDwellTicks;
    }

    /** 各段階の症状と、その段階へ進む条件。 */
    public static final class Level {
        public Integer playerDurationTicks;
        public Integer mobDurationTicks;
        /** 防御力低下(0.0〜1.0、ARMOR に対する ADD_MULTIPLIED_TOTAL)。 */
        public Double armorReduction;
        /** 移動速度低下(0.0〜1.0)。 */
        public Double moveReduction;
        /** プレイヤーの毎 tick の満腹度消耗(バニラの空腹 I = 0.005)。 */
        public Double hungerExhaustionPerTick;
        /** プレイヤーがこの段階に感染した瞬間、いるチャンクの胞子濃度へ加算する %。 */
        public Double chunkSporeBonusPercent;
        /** 与ダメージ減少率(攻撃力低下)。0 で無効。 */
        public Double attackDamageReduction;
        /** この段階の対象を、過繫茂した Mob / 捕食者が FOLLOW_RANGE よりどれだけ広く検知できるか(ブロック)。 */
        public Double detectionRangeBonus;
        /**
         * プレイヤーが一つ下の段階を持った状態でグループ1〜10 の Mob に攻撃されたとき、この段階へ悪化する確率。
         * level1 では未使用。
         */
        public Double upgradeChanceOnAttack;
        /**
         * プレイヤーが過繫茂バイオームにこの tick 数連続滞在するとこの段階へ進む。
         * level2 は「感染状態を問わず滞在時間」、level3 は「胞子感染2 を持った状態での滞在時間」。0 で無効。
         */
        public Integer overgrownBiomeDwellTicks;
        /**
         * 満了時にこの段階を途切れさせず再付与する条件。level1: 胞子濃度がこの % 以上のチャンクにいる。
         * level2/level3: 過繫茂バイオームにいる(この値は未使用)。
         */
        public Double continuePercent;
        /** level3 のみ: 胞子感染2 のプレイヤーが巨大樹の核からこの距離(ブロック)以内に入ると胞子感染3。0 で無効。 */
        public Double giantTreeCoreRadius;
        /** 感染した瞬間にバニラの「空腹」効果も併せて付与する tick 数(0 で付与しない)。level3 の「胞子感染2 と同じ空腹が 10 分間」用。 */
        public Integer vanillaHungerDurationTicks;
        /** 上記バニラ「空腹」の amplifier(0 = 空腹 I、1 = 空腹 II = 0.01/tick)。 */
        public Integer vanillaHungerAmplifier;
        /** この段階のプレイヤーは茂み({@code #overgrown_vegetation:concealing})の中にいても見つかる。 */
        public Boolean revealInFoliage;
        /** この段階が自然満了したプレイヤーは死亡し、死亡地点に汚染シダが出現する。 */
        public Boolean killPlayerOnExpiry;
    }

    /** 1 段階ぶんの確定値。 */
    public record LevelSpec(int playerDurationTicks, int mobDurationTicks, double armorReduction, double moveReduction,
                            double hungerExhaustionPerTick, double chunkSporeBonusPercent, double attackDamageReduction,
                            double detectionRangeBonus, double upgradeChanceOnAttack, int overgrownBiomeDwellTicks,
                            double continuePercent, double giantTreeCoreRadius, int vanillaHungerDurationTicks,
                            int vanillaHungerAmplifier, boolean revealInFoliage, boolean killPlayerOnExpiry) {
    }

    /** 段階数(amplifier 0〜2)。 */
    public static final int MAX_LEVEL_INDEX = 2;

    private final double playerLowMinPercent;
    private final int playerLowRollIntervalTicks;
    private final double playerHighMinPercent;
    private final int playerHighDwellTicks;
    private final double mobMinPercent;
    private final int mobDwellTicks;
    private final LevelSpec[] levels = new LevelSpec[MAX_LEVEL_INDEX + 1];
    private final double maxDetectionRangeBonus;
    private final DeviceSpec purifier;
    private final GearContaminationSpec gearContamination;
    private final DeviceSpec diffuser;

    // --- 追加効果(スポロフィル / フィロセンス)。null 安全に読むためのアクセサ。 ---
    private final SporophyllSpores sporophyllSpores;
    private final ClingingSpores clingingSpores;
    private final SimpleEffect phyllosensWatch;
    private final SimpleEffect phyllosensGuidance;
    private final WaterRotSpec waterRot;
    private final GlowFruit glowFruit;
    private final UndevelopedSeed undevelopedSeed;
    private final LargeUndevelopedFruit largeFruit;

    /** 「スポロフィルの胞子」(バフ)。 */
    public int sporophyllBuffTicks() { return orElse(sporophyllSpores.durationTicks, 1200); }
    public int sporophyllBuffAmplifier() { return orElse(sporophyllSpores.amplifier, 1); }
    public double sporophyllArmorPiercing() { return orElse(sporophyllSpores.armorPiercing, 0.5D); }
    public double sporophyllArmorDurabilityFactor() { return orElse(sporophyllSpores.armorDurabilityFactor, 1.0D); }

    /** 「纏わりついた胞子」(デバフ)。 */
    public int clingingDebuffTicks() { return orElse(clingingSpores.durationTicks, 600); }
    public int clingingDebuffAmplifier() { return orElse(clingingSpores.amplifier, 0); }
    public double clingingDetectionBonus() { return orElse(clingingSpores.detectionBonus, 24.0D); }
    public boolean clingingEscalatesInfection() {
        return clingingSpores.escalatesInfection == null || clingingSpores.escalatesInfection;
    }

    /** フィロセンスの視線(デバフ)/ 誘導(バフ)の持続。 */
    public int phyllosensWatchTicks() { return orElse(phyllosensWatch.durationTicks, 1200); }
    public int phyllosensGuidanceTicks() { return orElse(phyllosensGuidance.durationTicks, 1200); }

    /** 「水ぐされ」(デバフ)。 */
    public WaterRotSpec waterRot() { return waterRot; }

    /** 「熟した垂れ草の光る果実」を食べたときの 2 つの効果。 */
    public int glowFruitWellFedTicks() { return orElse(glowFruit.wellFedTicks, 600); }
    public int glowFruitSavoryScentTicks() { return orElse(glowFruit.savoryScentTicks, 1200); }
    public double glowFruitDetectionBonus() { return orElse(glowFruit.detectionBonus, 15.0D); }

    /** 「未発達な種子」を食べたときの効果。 */
    public int seedRegenerationTicks() { return orElse(undevelopedSeed.regenerationTicks, 40); }
    public int seedRegenerationAmplifier() { return orElse(undevelopedSeed.regenerationAmplifier, 0); }
    public int seedResistanceTicks() { return orElse(undevelopedSeed.resistanceTicks, 40); }
    public int seedResistanceAmplifier() { return orElse(undevelopedSeed.resistanceAmplifier, 0); }
    public int seedSavoryScentTicks() { return orElse(undevelopedSeed.savoryScentTicks, 100); }

    /** 「大きな未発達な果実」を食べたときの効果一式。 */
    public int largeFruitRegenerationTicks() { return orElse(largeFruit.regenerationTicks, 600); }
    public int largeFruitRegenerationAmplifier() { return orElse(largeFruit.regenerationAmplifier, 2); }
    public int largeFruitResistanceTicks() { return orElse(largeFruit.resistanceTicks, 600); }
    public int largeFruitResistanceAmplifier() { return orElse(largeFruit.resistanceAmplifier, 1); }
    public int largeFruitWellFedTicks() { return orElse(largeFruit.wellFedTicks, 3600); }
    public int largeFruitSavoryScentTicks() { return orElse(largeFruit.savoryScentTicks, 6000); }
    public boolean largeFruitEscalatesInfection() {
        return largeFruit.escalateInfection == null || largeFruit.escalateInfection;
    }
    public int largeFruitHungerDelayTicks() { return orElse(largeFruit.hungerDelayTicks, 7200); }
    public int largeFruitHungerTicks() { return orElse(largeFruit.hungerTicks, 3600); }
    public int largeFruitHungerAmplifier() { return orElse(largeFruit.hungerAmplifier, 2); }

    private EffectTable(SporeInfection s, SporeDevices dv, GearContamination gc,
                        SporophyllSpores sporophyllSpores, ClingingSpores clingingSpores,
                        SimpleEffect phyllosensWatch, SimpleEffect phyllosensGuidance,
                        WaterRot waterRot, GlowFruit glowFruit, UndevelopedSeed undevelopedSeed,
                        LargeUndevelopedFruit largeFruit) {
        SporeInfection d = DEFAULT_SPORE;
        SporeDevices dd = DEFAULT_DEVICES;
        this.purifier = mergeDevice(dv != null ? dv.purifier : null, dd.purifier);
        this.diffuser = mergeDevice(dv != null ? dv.diffuser : null, dd.diffuser);
        this.gearContamination = mergeGear(gc, DEFAULT_GEAR);
        this.sporophyllSpores = sporophyllSpores != null ? sporophyllSpores : new SporophyllSpores();
        this.clingingSpores = clingingSpores != null ? clingingSpores : new ClingingSpores();
        this.phyllosensWatch = phyllosensWatch != null ? phyllosensWatch : new SimpleEffect();
        this.phyllosensGuidance = phyllosensGuidance != null ? phyllosensGuidance : new SimpleEffect();
        this.waterRot = mergeWaterRot(waterRot);
        this.glowFruit = glowFruit != null ? glowFruit : new GlowFruit();
        this.undevelopedSeed = undevelopedSeed != null ? undevelopedSeed : new UndevelopedSeed();
        this.largeFruit = largeFruit != null ? largeFruit : new LargeUndevelopedFruit();
        Exposure e = s.exposure != null ? s.exposure : new Exposure();
        this.playerLowMinPercent = orElse(e.playerLowMinPercent, d.exposure.playerLowMinPercent);
        this.playerLowRollIntervalTicks = orElse(e.playerLowRollIntervalTicks, d.exposure.playerLowRollIntervalTicks);
        this.playerHighMinPercent = orElse(e.playerHighMinPercent, d.exposure.playerHighMinPercent);
        this.playerHighDwellTicks = orElse(e.playerHighDwellTicks, d.exposure.playerHighDwellTicks);
        this.mobMinPercent = orElse(e.mobMinPercent, d.exposure.mobMinPercent);
        this.mobDwellTicks = orElse(e.mobDwellTicks, d.exposure.mobDwellTicks);
        this.levels[0] = merge(s.level1, d.level1);
        this.levels[1] = merge(s.level2, d.level2);
        this.levels[2] = merge(s.level3, d.level3);
        double maxBonus = 0.0D;
        for (LevelSpec spec : levels) {
            maxBonus = Math.max(maxBonus, spec.detectionRangeBonus());
        }
        this.maxDetectionRangeBonus = maxBonus;
    }

    private static LevelSpec merge(Level l, Level d) {
        Level v = l != null ? l : new Level();
        return new LevelSpec(
                orElse(v.playerDurationTicks, d.playerDurationTicks),
                orElse(v.mobDurationTicks, d.mobDurationTicks),
                orElse(v.armorReduction, d.armorReduction),
                orElse(v.moveReduction, d.moveReduction),
                orElse(v.hungerExhaustionPerTick, d.hungerExhaustionPerTick),
                orElse(v.chunkSporeBonusPercent, d.chunkSporeBonusPercent),
                orElse(v.attackDamageReduction, d.attackDamageReduction),
                orElse(v.detectionRangeBonus, d.detectionRangeBonus),
                orElse(v.upgradeChanceOnAttack, d.upgradeChanceOnAttack),
                orElse(v.overgrownBiomeDwellTicks, d.overgrownBiomeDwellTicks),
                orElse(v.continuePercent, d.continuePercent),
                orElse(v.giantTreeCoreRadius, d.giantTreeCoreRadius),
                orElse(v.vanillaHungerDurationTicks, d.vanillaHungerDurationTicks),
                orElse(v.vanillaHungerAmplifier, d.vanillaHungerAmplifier),
                orElse(v.revealInFoliage, d.revealInFoliage),
                orElse(v.killPlayerOnExpiry, d.killPlayerOnExpiry));
    }

    /** 「水ぐされ」の既定値。json に節そのものが無くても動くよう、ここだけで面倒を見る。 */
    private static WaterRotSpec mergeWaterRot(WaterRot v) {
        WaterRot w = v != null ? v : new WaterRot();
        return new WaterRotSpec(
                orElse(w.enabled, true),
                orElse(w.soakTicks, 600),            // 水に 30 秒
                orElse(w.lingerTicks, 600),          // 上がってから 30 秒
                orElse(w.damagePerHit, 1.0D),
                orElse(w.damageIntervalTicks, 20),   // 毎秒 1 ダメージ
                orElse(w.checkIntervalTicks, 10));
    }

    private static GearContaminationSpec mergeGear(GearContamination l, GearContamination d) {
        GearContamination v = l != null ? l : new GearContamination();
        java.util.List<Step> src = v.steps != null && !v.steps.isEmpty() ? v.steps : d.steps;
        java.util.List<StepSpec> steps = new java.util.ArrayList<>(src.size());
        for (Step s : src) {
            if (s == null || s.atLeastPercent == null || s.performanceReduction == null) {
                continue;
            }
            steps.add(new StepSpec(s.atLeastPercent, s.performanceReduction));
        }
        return new GearContaminationSpec(
                orElse(v.enabled, d.enabled),
                orElse(v.minConcentrationPercent, d.minConcentrationPercent),
                orElse(v.ticksToFullAtMin, d.ticksToFullAtMin),
                orElse(v.ticksToFullAtMax, d.ticksToFullAtMax),
                orElse(v.checkIntervalTicks, d.checkIntervalTicks),
                orElse(v.wholeInventory, d.wholeInventory),
                java.util.List.copyOf(steps),
                orElse(v.decontaminationTicksPerPercent, d.decontaminationTicksPerPercent),
                orElse(v.sapCrystalRateFactor, orElse(d.sapCrystalRateFactor, 0.1D)));
    }

    private static DeviceSpec mergeDevice(Device l, Device d) {
        Device v = l != null ? l : new Device();
        return new DeviceSpec(
                orElse(v.centerPercent, d.centerPercent),
                orElse(v.edgePercent, d.edgePercent),
                v.radiusBlocks != null ? v.radiusBlocks
                        : v.radiusChunks != null ? v.radiusChunks * 16   // 旧キー(チャンク)からの読み替え
                        : d.radiusBlocks,
                orElse(v.ticksPerRedstone, d.ticksPerRedstone),
                orElse(v.sporeIntervalTicks, d.sporeIntervalTicks),
                orElse(v.sporesPerCycleAtFull, d.sporesPerCycleAtFull),
                orElse(v.sporesPerRedstone, d.sporesPerRedstone),
                orElse(v.inhibitCorruption, d.inhibitCorruption),
                orElse(v.inhibitFernSpawn, d.inhibitFernSpawn),
                orElse(v.inhibitBiomeConversion, d.inhibitBiomeConversion),
                orElse(v.requiresFilter, d.requiresFilter),
                orElse(v.filterWearPerCycle, d.filterWearPerCycle),
                orElse(v.filterWearPerSpore, d.filterWearPerSpore),
                orElse(v.filterRepairPerCycle, d.filterRepairPerCycle));
    }

    static EffectTable fromJson(Json j) {
        SporeInfection s = j != null && j.sporeInfection != null ? j.sporeInfection : new SporeInfection();
        SporeDevices dv = j != null ? j.sporeDevices : null;
        return new EffectTable(s, dv, j != null ? j.gearContamination : null,
                j != null ? j.sporophyllSpores : null, j != null ? j.clingingSpores : null,
                j != null ? j.phyllosensWatch : null, j != null ? j.phyllosensGuidance : null,
                j != null ? j.waterRot : null, j != null ? j.glowFruit : null,
                j != null ? j.undevelopedSeed : null,
                j != null ? j.largeUndevelopedFruit : null);
    }

    /** 「装備の胞子汚染」。 */
    public GearContaminationSpec gearContamination() {
        return gearContamination;
    }

    // --- devices ---

    /** 胞子除去装置。 */
    public DeviceSpec purifier() {
        return purifier;
    }

    /** 胞子拡散装置。 */
    public DeviceSpec diffuser() {
        return diffuser;
    }

    // --- exposure ---

    public double playerLowMinPercent() {
        return playerLowMinPercent;
    }

    public int playerLowRollIntervalTicks() {
        return playerLowRollIntervalTicks;
    }

    public double playerHighMinPercent() {
        return playerHighMinPercent;
    }

    public int playerHighDwellTicks() {
        return playerHighDwellTicks;
    }

    public double mobMinPercent() {
        return mobMinPercent;
    }

    public int mobDwellTicks() {
        return mobDwellTicks;
    }

    // --- levels ---

    /** amplifier(0〜2。範囲外は端に丸める)に対応する段階の値。 */
    public LevelSpec level(int amplifier) {
        int i = Math.max(0, Math.min(MAX_LEVEL_INDEX, amplifier));
        return levels[i];
    }

    /** 全段階の中で最大の被検知距離ボーナス(索敵 AABB を広めに取るため)。 */
    public double maxDetectionRangeBonus() {
        return maxDetectionRangeBonus;
    }

    private static int orElse(Integer v, int def) {
        return v != null ? v : def;
    }

    private static double orElse(Double v, double def) {
        return v != null ? v : def;
    }

    private static boolean orElse(Boolean v, boolean def) {
        return v != null ? v : def;
    }

    // --- 既定値 ---

    private static final SporeInfection DEFAULT_SPORE = buildDefault();
    private static final SporeDevices DEFAULT_DEVICES = buildDefaultDevices();
    private static final GearContamination DEFAULT_GEAR = buildDefaultGear();
    public static final EffectTable DEFAULT = new EffectTable(DEFAULT_SPORE, DEFAULT_DEVICES, DEFAULT_GEAR,
            null, null, null, null, null, null, null, null);

    private static GearContamination buildDefaultGear() {
        GearContamination g = new GearContamination();
        g.enabled = true;
        g.minConcentrationPercent = 15.0D;      // 胞子濃度 15% 未満では汚染されない
        g.ticksToFullAtMin = 72000;             // 濃度 15% で 1 時間かけて 100%
        g.ticksToFullAtMax = 12000;             // 濃度 100% なら 10 分で 100%
        g.checkIntervalTicks = 100;
        g.wholeInventory = true;
        g.decontaminationTicksPerPercent = 40;  // 除染機: 2 秒で 1%
        g.steps = java.util.List.of(
                step(25.0D, 0.05D),
                step(50.0D, 0.25D),
                step(75.0D, 0.45D),
                step(100.0D, 0.65D));
        return g;
    }

    private static Step step(double atLeastPercent, double performanceReduction) {
        Step s = new Step();
        s.atLeastPercent = atLeastPercent;
        s.performanceReduction = performanceReduction;
        return s;
    }

    private static SporeDevices buildDefaultDevices() {
        SporeDevices dv = new SporeDevices();
        // 除去装置: 自チャンク −100%、半径 48 ブロック(水平の円形)で末端 −10%(16 ブロック → −70、32 → −40、48 → −10)。
        Device p = new Device();
        p.centerPercent = 100.0D;
        p.edgePercent = 10.0D;
        p.radiusBlocks = 48;
        p.ticksPerRedstone = 6000;        // レッドストーンダスト 1 個 = 5 分
        p.sporeIntervalTicks = 600;       // 30 秒ごとに「胞子」を産出
        p.sporesPerCycleAtFull = 4.0D;    // もともとの濃度 100% で 4 個/サイクル(濃度に比例)
        p.sporesPerRedstone = 0;
        p.inhibitCorruption = true;       // 範囲内のブロック汚染を阻害(装置のチャンクは完全停止)
        p.inhibitFernSpawn = true;        // 範囲内の汚染シダの湧きを阻害
        p.inhibitBiomeConversion = true;  // 範囲内の過繫茂/繫茂バイオーム化を止める
        p.requiresFilter = true;          // フィルターが無いと動かない(2026-09-21)
        p.filterWearPerCycle = 1;
        p.filterWearPerSpore = 1;
        p.filterRepairPerCycle = 2;       // 耐久 256 を 128 サイクル(約 4 分半)で洗い上げる
        dv.purifier = p;
        // 拡散装置: その真逆(+100% → +10%)。レッドストーンダスト 1 個 + 胞子 1 個で 5 分。
        Device f = new Device();
        f.centerPercent = 100.0D;
        f.edgePercent = 10.0D;
        f.radiusBlocks = 48;
        f.ticksPerRedstone = 6000;
        f.sporeIntervalTicks = 0;
        f.sporesPerCycleAtFull = 0.0D;
        f.sporesPerRedstone = 1;
        f.inhibitCorruption = false;      // 拡散装置は何も阻害しない
        f.inhibitFernSpawn = false;
        f.inhibitBiomeConversion = false;
        f.requiresFilter = false;         // 拡散装置にフィルターは要らない
        f.filterWearPerCycle = 0;
        f.filterWearPerSpore = 0;
        f.filterRepairPerCycle = 0;
        dv.diffuser = f;
        return dv;
    }

    private static SporeInfection buildDefault() {
        SporeInfection s = new SporeInfection();
        s.exposure = new Exposure();
        s.exposure.playerLowMinPercent = 1.0D;
        s.exposure.playerLowRollIntervalTicks = 600;   // 30 秒ごとに濃度 % の確率
        s.exposure.playerHighMinPercent = 51.0D;
        s.exposure.playerHighDwellTicks = 400;         // 20 秒滞在で確定
        s.exposure.mobMinPercent = 50.0D;
        s.exposure.mobDwellTicks = 600;                // 30 秒滞在

        Level l1 = new Level();
        l1.playerDurationTicks = 1200;                 // 1 分
        l1.mobDurationTicks = 1200;                    // 1 分後に死亡
        l1.armorReduction = 0.20D;
        l1.moveReduction = 0.0D;
        l1.hungerExhaustionPerTick = 0.005D;
        l1.chunkSporeBonusPercent = 1.0D;
        l1.attackDamageReduction = 0.0D;
        l1.detectionRangeBonus = 0.0D;
        l1.upgradeChanceOnAttack = 0.0D;
        l1.overgrownBiomeDwellTicks = 0;
        l1.continuePercent = 51.0D;
        l1.giantTreeCoreRadius = 0.0D;
        l1.vanillaHungerDurationTicks = 0;
        l1.vanillaHungerAmplifier = 0;
        l1.revealInFoliage = false;
        l1.killPlayerOnExpiry = false;
        s.level1 = l1;

        Level l2 = new Level();
        l2.playerDurationTicks = 6000;                 // 5 分
        l2.mobDurationTicks = 1200;
        l2.armorReduction = 0.40D;
        l2.moveReduction = 0.10D;
        l2.hungerExhaustionPerTick = 0.010D;
        l2.chunkSporeBonusPercent = 5.0D;
        l2.attackDamageReduction = 0.0D;
        l2.detectionRangeBonus = 20.0D;
        l2.upgradeChanceOnAttack = 0.05D;
        l2.overgrownBiomeDwellTicks = 6000;            // 過繫茂バイオームに 5 分
        l2.continuePercent = 0.0D;
        l2.giantTreeCoreRadius = 0.0D;
        l2.vanillaHungerDurationTicks = 0;
        l2.vanillaHungerAmplifier = 0;
        l2.revealInFoliage = false;
        l2.killPlayerOnExpiry = false;
        s.level2 = l2;

        // 胞子感染3: 5 分。防御力/移動速度低下は胞子感染2 より強く、空腹は「胞子感染2 と同じ」をバニラの
        // 空腹 II(0.01/tick = 胞子感染2 の hungerExhaustionPerTick と同値)として 10 分間別枠で付与する。
        // 茂みの中にいても見つかり、自然満了で死亡 → 汚染シダ。
        Level l3 = new Level();
        l3.playerDurationTicks = 6000;
        l3.mobDurationTicks = 1200;
        l3.armorReduction = 0.60D;
        l3.moveReduction = 0.20D;
        l3.hungerExhaustionPerTick = 0.0D;
        l3.chunkSporeBonusPercent = 0.0D;
        l3.attackDamageReduction = 0.0D;
        l3.detectionRangeBonus = 30.0D;
        l3.upgradeChanceOnAttack = 0.05D;
        l3.overgrownBiomeDwellTicks = 12000;           // 胞子感染2 を持った状態で過繫茂バイオームに 10 分
        l3.continuePercent = 0.0D;
        l3.giantTreeCoreRadius = 60.0D;
        l3.vanillaHungerDurationTicks = 12000;
        l3.vanillaHungerAmplifier = 1;
        l3.revealInFoliage = true;
        l3.killPlayerOnExpiry = true;
        s.level3 = l3;
        return s;
    }
}
