package com.yayuy.overgrownvegetation.data;

import com.yayuy.overgrownvegetation.spore.field.SporeField;

/**
 * 胞子の場と風の設計値({@code spore_field.json}、2026-10-23 第 2 段)。仕様の正本は Vault「胞子の場と風による汚染の広がり」。
 *
 * <p>{@code field} は {@link SporeField.Params} そのまま(見積もり器 {@code tools/spread-measure/field} の v7 + 軽量化で合わせた値。
 * 速さの目標 = 全方位 4〜6・風下 6〜8・強風 10〜16 チャンク/日はユーザー指定、数値そのものはすべて仮決め)。
 * {@code secondThreshold}(2 体目の発芽のしきい値 0.5)はユーザー指定(10-23 追記 6)、後で詰める。
 * 書いていない項目は {@link SporeField.Params} の既定値。
 *
 * <p>{@code sources} はゲームだけの発生源・吸い込み口(すべて仮決め)。
 */
public final class SporeFieldTable {

    public static final SporeFieldTable DEFAULT = new SporeFieldTable(null);

    /** Gson の受け皿。null は既定値。 */
    public static final class Json {
        public Boolean enabled;
        /** 更新の間隔(tick)。【決定】5 秒 = 100。field.stepTicks もこれに揃える。 */
        public Integer updateTicks;
        /** 1 回の計算で進める更新の上限(睡眠などで暦が飛んだときの追いつき。超えた分は次の回へ)。 */
        public Integer maxStepsPerRun;
        public SporeField.Params field;
        public Sources sources;
        public Reading reading;
        public Exposure exposure;
        public Sprouts sprouts;
        public Alarm alarm;
        public Sweep sweep;
        public GiantTree giantTree;
        public Look look;
        public Rift rift;
    }

    /**
     * 地割れの噴出(2026-10-24 追記 19、数は仮決め)。噴出の間(地割れの eruptDuration、既定 3 分)、地割れのチャンクへ巨大樹の成木の
     * eruptTreeMultiple 倍の 1 日分を噴出の長さに詰めて放出し、liftPerDay の速さで上空の塊(飛び地)にする。噴出の始めに圧の前線を出す:
     * 速さ frontSpeedChunksPerSec で全方位へ広がり、通った升の浮遊へ「frontFullRadiusChunks で濃度 100%」になる量 × (frontFullRadius / 距離)^frontFalloff
     * を 1 回配る。配る量が frontMinPercent を割る所で止まる。噴出の後も地割れの中心から floorRadiusChunks の内側は濃度の読みに
     * floorPercent を足す(縁でなだらかに 0)。
     */
    public static final class Rift {
        public Double eruptTreeMultiple, liftPerDay, frontSpeedChunksPerSec, frontFullRadiusChunks, frontFalloff, frontMinPercent, floorPercent, floorRadiusChunks, frontRadiusChunks;
    }

    /**
     * 緑の霧とプルームの見た目(2026-10-23 G、仮決め。クライアントも読む)。濃度が startPercent を越えると霧が色づき始め、
     * fullPercent で霧の距離が minFar まで縮む(100% でうっすら・200% で白緑・300% 超えで視界数十ブロック)。色は雰囲気強化の LUT と後で揃える。
     * プルームは浮遊が plumeMinPercent を越えた升から、風下へ薄い帯を上空 10〜30 ブロックに浮かべる(不透明度の上限 plumeAlpha)。
     */
    public static final class Look {
        public Double fogStartPercent, fogFullPercent, fogMinFar, fogR, fogG, fogB, plumeMinPercent, plumeAlpha, treePlumeAlpha;
        /** 粉の濃さ(2026-10-24): 胞子計の「降り積もり %」と同じ物差し。これ未満は描かない・100% の不透明度・曲がり具合(1 = 直線、大きいほど薄い所が薄い)・葉の上の倍率。 */
        public Double dustMinPercent, dustMaxAlpha, dustGamma, dustLeafFactor;
        /** 水面の粉の倍率(水面は地面より強く見えるので。2026-10-24 仮 0.7)。 */
        public Double dustWaterFactor;
    }

    /**
     * 巨大樹 = 第二の胞子の震源(2026-10-23 追記 13【決定】、数は仮決め)。梢のチャンクへ一日中放出し(育ちかけ young → 成木 mature を
     * matureDays で直線に)、朝はさらに sources.canopyMorningPerDay を上乗せ。嵐が無くても梢の浮遊を liftPerDay の速さで上空の層へ
     * 持ち上げて塊にする(塊の間隔は field.parcelEveryTicks)。効くのは記録の古い順に maxTrees 本まで(0 = 無制限)。
     * ハイヴノードを倒すと、水平 killRadius ブロック以内でいちばん近い巨大樹の放出が止まる(再生なし)。
     * ハイヴノードの周り infectionRadius ブロックでは胞子感染の段階が上がる速さに infectionSpeedMul を掛ける(5.0 = 5 倍速。読み方を確認中)。
     */
    public static final class GiantTree {
        public Double youngPerDay, maturePerDay, matureDays, liftPerDay, killRadius, infectionRadius, infectionSpeedMul;
        public Integer maxTrees;
    }

    /**
     * シャベルで胞子の粉を払う(2026-10-23 第 2 段の追加。【決定】チャンクを 4×4 の升 16 個に分け、払うと降り積もりをその升の分だけ減らし「胞子」を落とす。
     * 数は仮決め)。濃い升(降り積もりが発芽のしきい値以上)は dropMin〜dropMax 個、薄い升は (降り積もり ÷ しきい値) × thinChance の確率で 1 個。
     */
    public static final class Sweep {
        /** 1 升で減らす割合(1/16 = 0.0625)。 */
        public Double share;
        public Integer dropMin, dropMax;
        public Double thinChance;
    }

    /** 胞子警報器は濃度の「上がり方」で鳴る(区切り 2 の H、仮決め。嵐の前に鳴る)。 */
    public static final class Alarm {
        /** 1 分でこれだけ(% の差)上がったら鳴る。今の濃度がこれ未満なら鳴らない。続けて鳴らさない間(tick)。 */
        public Double risePerMinute, minPercent;
        public Integer cooldownTicks;
    }

    /**
     * ブロック汚染の芽(区切り 2 の E、仮決め)。発芽したチャンクが読み込まれたら、地表と洞窟の床のランダムな点から
     * CorruptionSpreader を半径 radius で走らせる(【決定】1 チャンクの汚染が始まるまで実時間 3〜5 分)。萌芽体(F)は歩いてから根付く。
     */
    public static final class Sprouts {
        public Integer pointsPerChunk;
        public Double caveShare, wetMultiplier;
        public Integer radius, tickInterval, maxActivePoints;
        /** 萌芽体が歩き回る長さ(tick)。湿った所(水辺・雨・夜)では早く根付く。 */
        public Integer wanderTicksMin, wanderTicksMax;
        /** 行き先があるのにこの長さ(tick)位置が変わらなければ、その場で根付く(穴につっかえた萌芽体。2026-10-24 追記 18、仮 200 = 10 秒)。 */
        public Integer stuckRootTicks;
    }

    /**
     * 濃度の読み(区切り 2 の D。すべて仮決め、10-23 相談セッションの答え 5)。100% = 過繁茂のチャンクの晴れた午後のふつうの
     * 浮遊 + 林床({@code fullAt}。見積もり器 LevelCheck で 0.55)。中身は上限なし(嵐で 250% ほど)、表示は 0〜100%。
     */
    public static final class Reading {
        public Double fullAt;
        /** 下支え(発生源の放出が下限): 繁茂・過繁茂・極相のチャンクの濃度の下限(%)。除去装置もここまでしか下げられない。 */
        public Double floorThriving, floorOvergrown, floorClimax;
        /** 地点の補正: 梢(葉を含む一番上)より上はこのブロック数ごとに 1/e に薄く / 洞窟の口・窪地(空が少し見える地下)は濃く /
         *  閉じた部屋(空の光 0・地面から浅い・周り 4 方が塞がる)は薄く / 塞がっていない屋根の下 / 深い洞窟(地下の層の流れ込みは後の段)/
         *  雨の最中 / 風上 1 ブロックが塞がっている(壁の陰)。 */
        public Double aboveCanopyFalloff, hollowFactor, roomFactor, roofFactor, deepCaveFactor, rainFactor, upwindShadeFactor;
    }

    /** 暴露(区切り 2 の D、仮決め。10-23 相談セッションの答え 5・マスクは 10-23 ユーザー指定で 25%)。 */
    public static final class Exposure {
        /** これ未満の濃度(%)は積まない。 */
        public Double minPercent;
        /** 濃い所を出てから暴露が半分に減るまで(秒)。 */
        public Double halfLifeSeconds;
        /** 防護マスクを着けているときの暴露の割合(%)。【決定】10-23 ユーザー: 既定 25、0 = 暴露しない(今までどおりかからない)、100 = マスクが無いのと同じ。 */
        public Double maskPercent;
    }

    /** ゲームの発生源・吸い込み口(1 チャンクの過繁茂の放出 = field.emitOvergrown(1 日あたり)を物差しにした量)。 */
    public static final class Sources {
        /** 除去装置: 抜く範囲(チャンク)、1 回の更新で抜ける量の上限(0 = 無し = 範囲内を抑え切る。10-23 追記 11)、抜いた量 1 あたりの「胞子」アイテム(1 サイクルの上限は無し。取り出し口がいっぱいになると止まる)。 */
        public Double purifierRadiusChunks;
        public Double purifierCapPerUpdate;
        public Double purifierSporesPerUnit;
        /** 拡散装置: 1 日あたりの放出(装置のチャンクの浮遊へ)。 */
        public Double diffuserPerDay;
        /** 巨大樹の梢: 朝(6〜9 時)の 1 日あたりに直した放出(中心のチャンクへ)。 */
        public Double canopyMorningPerDay;
        /** ポータル越しの染み出し: ネザーの場の 1 回の更新で、ポータルのチャンクの浮遊 + 林床の何 % を向こう側へ移すか(2026-10-23 F、仮決め)。 */
        public Double portalSeepPercent;
        /** 点の放出(汚染の球 NoisyCorruptionBurst): 半径 1 ブロックあたり半径の 2 乗に比例する量。 */
        public Double burstPerRadiusSq;
        /** 胞子感染で死んだ Mob の死体: 起き上がるまでの間(g1.json corpse.emitTicks)の 1 日あたりの放出(死体のチャンクへ。G1 の作り直し 第 1 段、仮決め)。 */
        public Double corpsePerDay;
    }

    private final boolean enabled;
    private final int updateTicks;
    private final int maxStepsPerRun;
    private final SporeField.Params field;
    private final double purifierRadiusChunks, purifierCapPerUpdate, purifierSporesPerUnit;
    private final double portalSeepPercent;
    private final double diffuserPerDay, canopyMorningPerDay, burstPerRadiusSq, corpsePerDay;
    private final double fullAt, floorThriving, floorOvergrown, floorClimax;
    private final double aboveCanopyFalloff, hollowFactor, roomFactor, roofFactor, deepCaveFactor, rainFactor, upwindShadeFactor;
    private final double exposureMinPercent, exposureHalfLifeSeconds, maskFactor;
    private final int sproutPoints, sproutRadius, sproutTickInterval, sproutMaxActive, wanderMin, wanderMax, stuckRootTicks;
    private final double sproutCaveShare, sproutWetMultiplier;
    private final double alarmRise, alarmMin;
    private final int alarmCooldown;
    private final double sweepShare, sweepThinChance;
    private final int sweepDropMin, sweepDropMax;
    private final double gtYoung, gtMature, gtMatureDays, gtLift, gtKillRadius, gtInfectionRadius, gtInfectionMul;
    private final int gtMaxTrees;
    private final double fogStart, fogFull, fogMinFar, fogR, fogG, fogB, plumeMin, plumeAlpha, treePlumeAlpha;
    private final double dustMinPercent, dustMaxAlpha, dustGamma, dustLeafFactor, dustWaterFactor;
    private final double riftTreeMultiple, riftLift, riftFrontSpeed, riftFrontFull, riftFrontFalloff, riftFrontMin, riftFloor, riftFloorRadius, riftFrontRadius;

    private SporeFieldTable(Json j) {
        Json src = j != null ? j : new Json();
        this.enabled = src.enabled == null || src.enabled;
        this.updateTicks = src.updateTicks != null ? Math.max(20, Math.min(1200, src.updateTicks)) : 100;
        this.maxStepsPerRun = src.maxStepsPerRun != null ? Math.max(1, Math.min(2400, src.maxStepsPerRun)) : 60;
        SporeField.Params p = src.field != null ? src.field : gameDefaults();
        p.stepTicks = this.updateTicks;
        this.field = p;
        Sources s = src.sources != null ? src.sources : new Sources();
        this.purifierRadiusChunks = orElse(s.purifierRadiusChunks, 3.0D);
        this.purifierCapPerUpdate = orElse(s.purifierCapPerUpdate, 0.0D);
        this.purifierSporesPerUnit = orElse(s.purifierSporesPerUnit, 0.35D);
        this.diffuserPerDay = orElse(s.diffuserPerDay, 20.0D);
        this.canopyMorningPerDay = orElse(s.canopyMorningPerDay, 100.0D);
        this.portalSeepPercent = Math.max(0.0D, Math.min(100.0D, orElse(s.portalSeepPercent, 3.0D)));
        this.burstPerRadiusSq = orElse(s.burstPerRadiusSq, 0.5D);
        this.corpsePerDay = orElse(s.corpsePerDay, 1.0D);
        Reading rd = src.reading != null ? src.reading : new Reading();
        this.fullAt = Math.max(1e-6, orElse(rd.fullAt, 0.55D));
        this.floorThriving = orElse(rd.floorThriving, 10.0D);
        this.floorOvergrown = orElse(rd.floorOvergrown, 25.0D);
        this.floorClimax = orElse(rd.floorClimax, 50.0D);
        this.aboveCanopyFalloff = Math.max(1.0D, orElse(rd.aboveCanopyFalloff, 16.0D));
        this.hollowFactor = orElse(rd.hollowFactor, 1.3D);
        this.roomFactor = orElse(rd.roomFactor, 0.2D);
        this.roofFactor = orElse(rd.roofFactor, 0.6D);
        this.deepCaveFactor = orElse(rd.deepCaveFactor, 0.5D);
        this.rainFactor = orElse(rd.rainFactor, 0.7D);
        this.upwindShadeFactor = orElse(rd.upwindShadeFactor, 0.85D);
        Exposure ex = src.exposure != null ? src.exposure : new Exposure();
        this.exposureMinPercent = orElse(ex.minPercent, 5.0D);
        this.exposureHalfLifeSeconds = Math.max(1.0D, orElse(ex.halfLifeSeconds, 60.0D));
        this.maskFactor = Math.min(100.0D, orElse(ex.maskPercent, 25.0D)) / 100.0D;
        Sprouts sp = src.sprouts != null ? src.sprouts : new Sprouts();
        this.sproutPoints = sp.pointsPerChunk != null ? Math.max(1, Math.min(64, sp.pointsPerChunk)) : 10;
        this.sproutRadius = sp.radius != null ? Math.max(1, Math.min(8, sp.radius)) : 4;
        this.sproutTickInterval = sp.tickInterval != null ? Math.max(1, Math.min(200, sp.tickInterval)) : 10;
        this.sproutMaxActive = sp.maxActivePoints != null ? Math.max(1, sp.maxActivePoints) : 400;
        this.sproutCaveShare = Math.min(1.0D, orElse(sp.caveShare, 0.2D));
        this.sproutWetMultiplier = orElse(sp.wetMultiplier, 1.3D);
        this.wanderMin = sp.wanderTicksMin != null ? Math.max(20, sp.wanderTicksMin) : 600;
        this.wanderMax = sp.wanderTicksMax != null ? Math.max(this.wanderMin, sp.wanderTicksMax) : 2400;
        this.stuckRootTicks = sp.stuckRootTicks != null ? Math.max(20, sp.stuckRootTicks) : 200;
        Alarm al = src.alarm != null ? src.alarm : new Alarm();
        this.alarmRise = orElse(al.risePerMinute, 20.0D);
        this.alarmMin = orElse(al.minPercent, 15.0D);
        this.alarmCooldown = al.cooldownTicks != null ? Math.max(200, al.cooldownTicks) : 3600;
        Sweep sw = src.sweep != null ? src.sweep : new Sweep();
        this.sweepShare = Math.max(0.0D, Math.min(1.0D, orElse(sw.share, 1.0D / 16.0D)));
        this.sweepDropMin = sw.dropMin != null ? Math.max(0, sw.dropMin) : 1;
        this.sweepDropMax = Math.max(this.sweepDropMin, sw.dropMax != null ? sw.dropMax : 2);
        this.sweepThinChance = Math.max(0.0D, orElse(sw.thinChance, 1.0D));
        GiantTree gt = src.giantTree != null ? src.giantTree : new GiantTree();
        this.gtYoung = Math.max(0.0D, orElse(gt.youngPerDay, 100.0D));
        this.gtMature = Math.max(0.0D, orElse(gt.maturePerDay, 400.0D));
        this.gtMatureDays = Math.max(0.0D, orElse(gt.matureDays, 3.0D));
        this.gtLift = Math.max(0.0D, orElse(gt.liftPerDay, 1.0D));
        this.gtKillRadius = Math.max(1.0D, orElse(gt.killRadius, 48.0D));
        this.gtInfectionRadius = Math.max(0.0D, orElse(gt.infectionRadius, 32.0D));
        this.gtInfectionMul = Math.max(0.0D, orElse(gt.infectionSpeedMul, 5.0D));
        this.gtMaxTrees = gt.maxTrees != null ? Math.max(0, gt.maxTrees) : 24;
        Rift rf = src.rift != null ? src.rift : new Rift();
        this.riftTreeMultiple = Math.max(0.0D, orElse(rf.eruptTreeMultiple, 30.0D));
        this.riftLift = Math.max(0.0D, orElse(rf.liftPerDay, 4.0D));
        this.riftFrontSpeed = Math.max(0.01D, orElse(rf.frontSpeedChunksPerSec, 2.0D));
        this.riftFrontFull = Math.max(1.0D, orElse(rf.frontFullRadiusChunks, 20.0D));
        this.riftFrontFalloff = Math.max(0.1D, orElse(rf.frontFalloff, 1.0D));
        this.riftFrontMin = Math.max(0.0D, orElse(rf.frontMinPercent, 5.0D));
        this.riftFrontRadius = Math.max(1.0D, orElse(rf.frontRadiusChunks, 14.0D));
        this.riftFloor = Math.max(0.0D, orElse(rf.floorPercent, 100.0D));
        this.riftFloorRadius = Math.max(0.0D, orElse(rf.floorRadiusChunks, 3.0D));
        Look lk = src.look != null ? src.look : new Look();
        this.fogStart = orElse(lk.fogStartPercent, 80.0D);
        this.fogFull = Math.max(this.fogStart + 1.0D, orElse(lk.fogFullPercent, 300.0D));
        this.fogMinFar = Math.max(4.0D, orElse(lk.fogMinFar, 28.0D));
        this.fogR = orElse(lk.fogR, 0.80D);
        this.fogG = orElse(lk.fogG, 0.88D);
        this.fogB = orElse(lk.fogB, 0.72D);
        this.plumeMin = Math.max(0.0D, orElse(lk.plumeMinPercent, 20.0D));
        this.plumeAlpha = Math.max(0.0D, Math.min(1.0D, orElse(lk.plumeAlpha, 0.30D)));
        this.treePlumeAlpha = Math.max(0.0D, Math.min(1.0D, orElse(lk.treePlumeAlpha, 0.35D)));
        this.dustMinPercent = Math.max(0.0D, orElse(lk.dustMinPercent, 1.0D));
        this.dustMaxAlpha = Math.max(0.0D, Math.min(1.0D, orElse(lk.dustMaxAlpha, 0.42D)));
        this.dustGamma = Math.max(0.1D, Math.min(5.0D, orElse(lk.dustGamma, 1.2D)));
        this.dustLeafFactor = Math.max(0.0D, Math.min(1.0D, orElse(lk.dustLeafFactor, 0.6D)));
        this.dustWaterFactor = Math.max(0.0D, Math.min(1.0D, orElse(lk.dustWaterFactor, 0.4D)));
    }

    /** ゲームの既定: 見積もり器の既定から、極相を日数ではなく台帳の印で決める所だけ変える。 */
    public static SporeField.Params gameDefaults() {
        SporeField.Params p = new SporeField.Params();
        p.daysToClimax = 1.0E9;
        return p;
    }

    private static double orElse(Double v, double def) {
        return v != null && Double.isFinite(v) ? Math.max(0.0D, v) : def;
    }

    public static SporeFieldTable fromJson(Json j) {
        return j == null ? DEFAULT : new SporeFieldTable(j);
    }

    public boolean enabled() {
        return enabled;
    }

    public int updateTicks() {
        return updateTicks;
    }

    public int maxStepsPerRun() {
        return maxStepsPerRun;
    }

    /** 場の数値(呼ぶ側は書き換えない)。 */
    public SporeField.Params field() {
        return field;
    }

    public double purifierRadiusChunks() {
        return purifierRadiusChunks;
    }

    public double purifierCapPerUpdate() {
        return purifierCapPerUpdate;
    }

    public double purifierSporesPerUnit() {
        return purifierSporesPerUnit;
    }

    public double portalSeepPercent() {
        return portalSeepPercent;
    }

    public double diffuserPerDay() {
        return diffuserPerDay;
    }

    public double canopyMorningPerDay() {
        return canopyMorningPerDay;
    }

    public double burstPerRadiusSq() {
        return burstPerRadiusSq;
    }

    public double corpsePerDay() {
        return corpsePerDay;
    }

    public double fullAt() {
        return fullAt;
    }

    /** 段階(1 繁茂・2 過繁茂・3 極相)の下支え(%)。0 は 0。 */
    public double floorFor(int stage) {
        return switch (stage) {
            case 1 -> floorThriving;
            case 2 -> floorOvergrown;
            case 3 -> floorClimax;
            default -> 0.0D;
        };
    }

    public double aboveCanopyFalloff() {
        return aboveCanopyFalloff;
    }

    public double hollowFactor() {
        return hollowFactor;
    }

    public double roomFactor() {
        return roomFactor;
    }

    public double roofFactor() {
        return roofFactor;
    }

    public double deepCaveFactor() {
        return deepCaveFactor;
    }

    public double rainFactor() {
        return rainFactor;
    }

    public double upwindShadeFactor() {
        return upwindShadeFactor;
    }

    public double exposureMinPercent() {
        return exposureMinPercent;
    }

    public double exposureHalfLifeSeconds() {
        return exposureHalfLifeSeconds;
    }

    public double maskFactor() {
        return maskFactor;
    }

    public int sproutPoints() {
        return sproutPoints;
    }

    public int sproutRadius() {
        return sproutRadius;
    }

    public int sproutTickInterval() {
        return sproutTickInterval;
    }

    public int sproutMaxActive() {
        return sproutMaxActive;
    }

    public double sproutCaveShare() {
        return sproutCaveShare;
    }

    public double sproutWetMultiplier() {
        return sproutWetMultiplier;
    }

    public int wanderMin() {
        return wanderMin;
    }

    public int wanderMax() {
        return wanderMax;
    }

    public int stuckRootTicks() {
        return stuckRootTicks;
    }

    public double alarmRise() {
        return alarmRise;
    }

    public double alarmMin() {
        return alarmMin;
    }

    public int alarmCooldown() {
        return alarmCooldown;
    }

    public double sweepShare() {
        return sweepShare;
    }

    public int sweepDropMin() {
        return sweepDropMin;
    }

    public int sweepDropMax() {
        return sweepDropMax;
    }

    public double sweepThinChance() {
        return sweepThinChance;
    }

    /** 巨大樹 1 本の 1 日あたりの放出(生えてからの日数で育ちかけ → 成木)。 */
    public double giantTreePerDay(double ageDays) {
        double t = gtMatureDays <= 0.0D ? 1.0D : Math.min(1.0D, Math.max(0.0D, ageDays / gtMatureDays));
        return gtYoung + (gtMature - gtYoung) * t;
    }

    public double fogStartPercent() { return fogStart; }

    public double fogFullPercent() { return fogFull; }

    public double fogMinFar() { return fogMinFar; }

    public float[] fogColor() { return new float[]{(float) fogR, (float) fogG, (float) fogB}; }

    public double plumeMinPercent() { return plumeMin; }

    public double plumeAlpha() { return plumeAlpha; }

    public double treePlumeAlpha() { return treePlumeAlpha; }
    public double dustMinPercent() { return dustMinPercent; }
    public double dustMaxAlpha() { return dustMaxAlpha; }
    public double dustGamma() { return dustGamma; }
    public double dustLeafFactor() { return dustLeafFactor; }
    public double dustWaterFactor() { return dustWaterFactor; }
    public double riftTreeMultiple() { return riftTreeMultiple; }
    public double riftLiftPerDay() { return riftLift; }
    public double riftFrontSpeed() { return riftFrontSpeed; }
    public double riftFrontFullRadius() { return riftFrontFull; }
    public double riftFrontFalloff() { return riftFrontFalloff; }
    public double riftFrontMinPercent() { return riftFrontMin; }
    public double riftFrontRadius() { return riftFrontRadius; }
    public double riftFloorPercent() { return riftFloor; }
    public double riftFloorRadius() { return riftFloorRadius; }

    public double giantTreeLiftPerDay() {
        return gtLift;
    }

    public int giantTreeMaxTrees() {
        return gtMaxTrees;
    }

    public double giantTreeKillRadius() {
        return gtKillRadius;
    }

    public double hiveNodeInfectionRadius() {
        return gtInfectionRadius;
    }

    /** ハイヴノードの周りで胞子感染の段階が上がる速さの倍率(設計データ giantTree.infectionSpeedMul、既定 5.0)。 */
    public double hiveNodeInfectionSpeedMul() {
        return gtInfectionMul;
    }
}
