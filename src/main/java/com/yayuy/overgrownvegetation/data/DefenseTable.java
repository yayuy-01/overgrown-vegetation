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
 *     <li>{@code sporeAlarm}: 胞子警報器。半径 {@code radius} の中のプレイヤーへ、フェーズの移り変わり・
 *     レイド・小規模レイドの警告をチャットに流す。次のフェーズまでの達成率が {@code phaseWarnFraction}
 *     に達したときにも「移行が近い」と警告する。</li>
 *     <li>{@code gunTurret}: 機関砲台(2026-09-21)。半径 {@code range} かつ仰俯角
 *     {@code minElevation}〜{@code maxElevation} に入った敵を、毎分 {@code roundsPerMinute} 発・
 *     1 発 {@code damage} で撃つ({@code block/GunTurretBlockEntity})。銃口が目標まで
 *     {@code aimTolerance} 度に寄ったら発砲し、目標は {@code retargetIntervalTicks} ごとに選び直す。
 *     増援を運ぶ胞子弾を撃ち落とすと、大きさ {@code clusterBombletSize} の小さな胞子爆弾が
 *     {@code clusterBomblets} 個ばらまかれる。</li>
 * </ul>
 * {@code /ov design reload} で即反映。
 */
public final class DefenseTable {

    public static final class Json {
        public RepellentLamp repellentLamp;
        public BarbedWire barbedWire;
        public SporeAlarm sporeAlarm;
        public GunTurret gunTurret;
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
    private final double turretShielderDetectRange;
    private final double turretModelScale;
    private final double turretTracerSpeed;

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
        this.alarmRadius = alarm.radius != null ? Math.max(1.0D, alarm.radius) : 96.0D;
        this.alarmPhaseWarnFraction = alarm.phaseWarnFraction != null
                ? Math.max(0.0D, Math.min(1.0D, alarm.phaseWarnFraction)) : 0.9D;
        this.turretDamage = turret.damage != null ? Math.max(0.0D, turret.damage) : 15.0D;
        this.turretRoundsPerMinute = turret.roundsPerMinute != null
                ? Math.max(1.0D, turret.roundsPerMinute) : 240.0D;
        this.turretRange = turret.range != null ? Math.max(1.0D, turret.range) : 32.0D;
        this.turretVelophyllRange = turret.velophyllRange != null
                ? Math.max(1.0D, turret.velophyllRange) : 64.0D;
        this.turretMinElevation = turret.minElevation != null
                ? Math.max(-90.0D, Math.min(90.0D, turret.minElevation)) : -28.0D;
        this.turretMaxElevation = turret.maxElevation != null
                ? Math.max(-90.0D, Math.min(90.0D, turret.maxElevation)) : 85.0D;
        this.turretTraverseSpeed = turret.traverseSpeed != null ? Math.max(0.01D, turret.traverseSpeed) : 0.75D;
        this.turretElevationSpeed = turret.elevationSpeed != null ? Math.max(0.01D, turret.elevationSpeed) : 2.0D;
        this.turretAimTolerance = turret.aimTolerance != null ? Math.max(0.1D, turret.aimTolerance) : 3.0D;
        this.turretRetargetIntervalTicks = turret.retargetIntervalTicks != null
                ? Math.max(1, turret.retargetIntervalTicks) : 10;
        this.turretClusterBomblets = turret.clusterBomblets != null ? Math.max(0, turret.clusterBomblets) : 3;
        this.turretClusterBombletSize = turret.clusterBombletSize != null
                ? Math.max(0.05D, Math.min(1.0D, turret.clusterBombletSize)) : 0.45D;
        this.turretCorruptRadius = turret.corruptRadius != null ? Math.max(0.0D, turret.corruptRadius) : 4.0D;
        this.turretShielderDetectRange = turret.shielderDetectRange != null
                ? Math.max(0.0D, turret.shielderDetectRange) : 40.0D;
        this.turretModelScale = turret.modelScale != null
                ? Math.max(0.1D, Math.min(4.0D, turret.modelScale)) : 1.5D;
        this.turretTracerSpeed = turret.tracerSpeed != null ? Math.max(0.5D, turret.tracerSpeed) : 3.0D;
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

    /** 機関砲台: 旋回速度(度/tick)。既定 0.75 = 秒間 15 度(真後ろを向くのに 12 秒)。 */
    public double turretTraverseSpeed() {
        return turretTraverseSpeed;
    }

    /** 機関砲台: 俯仰速度(度/tick)。既定 2.0 = 秒間 40 度(台座の旋回より速い — 上を狙い直すのに使うため)。 */
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
     * 機関砲台: 過繫茂したスカトゥリウムが<b>迫撃砲で潰しに来る</b>距離(既定 40 = 迫撃砲の最大射程)。
     * 砲台の射程(32)より長いので、砲台に撃たれない位置から撃ち込める。
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

    static DefenseTable fromJson(Json j) {
        return new DefenseTable(j);
    }

    public static final DefenseTable DEFAULT = new DefenseTable(null);
}
