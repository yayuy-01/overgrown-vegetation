package com.yayuy.overgrownvegetation.data;

/**
 * F5 の襲撃の設計値({@code director.json} の {@code assault}、2026-11-16)。正本は Vault「F5 の襲撃(最初の襲撃と送り込み)(11-16)」。
 *
 * <p>ユーザーが決めたのは: 最初の襲撃は認識した次の夜に G1 と G4 を中心に約 15 体・前触れなし / 送り込みは 1 回 10 体から最大 40 体・20〜40 分おき /
 * 着いた敵は拠点を汚す(1 体 1 分で空気 +1%・降り積もりは空気の 1/4)/ 壁や扉を壊せるのは木・ガラス・土と扉だけ(タグ {@code raid_breakable})・
 * 壊すのは力のある種だけ / 貯めた襲撃は送り込みを 1〜2 回飛ばしてまとめた 1 回(上限 40・進化体と G5 を 1.5 倍)。<b>ほかの数は【仮】</b>。
 */
public final class AssaultTable {

    public static final AssaultTable DEFAULT = new AssaultTable(null);

    public static final class Json {
        public BaseMemoryTable.WaveJson firstRaid;
        public Integer nightStartTicks;
        public Integer nightEndTicks;
        public Integer maxWave;
        public Double stageZeroCost;
        public Double hoardShareFactor;
        public Double foulRadius;
        public Double foulAirPercentPerMinute;
        public Double foulDepositShare;
        public Integer foulIntervalTicks;
        public Integer breakStuckTicks;
        public Integer breakTicksBase;
        public Integer breakTicksPerHardness;
        public Double breakReach;
        public Integer hoardMaxAlive;
        public Integer hoardMaxTotal;
        public Integer hoardRefillTicks;
        public Double hoardMaxDays;
        public Double firstRetryUnloadedDays;
        public Double firstRetryPoorDays;
        public Integer nightLastTicks;
    }

    /** 最初の襲撃の顔ぶれ(fromDays は使わない)。ユーザー「G1 と G4 を中心に約 15 体」。 */
    public final BaseMemoryTable.Wave firstRaid;
    /** 最初の襲撃が来る時刻の幅(1 日の中の tick。13000 = 夜の始め)。 */
    public final int nightStartTicks;
    public final int nightEndTicks;
    /** 1 回の送り込みの上限の体数(ユーザー 40)。 */
    public final int maxWave;
    /** 送り込みで段階 0 を湧かせる値段(1 体)。 */
    public final double stageZeroCost;
    /** 貯めた襲撃の回の、進化体と G5 の割合の倍率(ユーザー 1.5)。 */
    public final double hoardShareFactor;
    /** 拠点を汚す: 拠点の真ん中からの半径(ブロック)・1 体 1 分で上げる空気(%)・降り積もりの割合・間(tick)。 */
    public final double foulRadius;
    public final double foulAirPercentPerMinute;
    public final double foulDepositShare;
    public final int foulIntervalTicks;
    /** 壊す: 道が詰まったとみなす tick・壊す時間(基本 + 硬さ 1 あたり)・壊せる遠さ(ブロック)。 */
    public final int breakStuckTicks;
    public final int breakTicksBase;
    public final int breakTicksPerHardness;
    public final double breakReach;
    /** 貯めた襲撃(11-16 ユーザー): 同時にいるのは hoardMaxAlive(40)まで、倒された分を継ぎ足してその回の合計は hoardMaxTotal(80)まで。
     *  継ぎ足しの間(tick)と、始めてから終えるまでの長さ(日)は【仮】。 */
    public final int hoardMaxAlive;
    public final int hoardMaxTotal;
    public final int hoardRefillTicks;
    public final double hoardMaxDays;
    /** 最初の襲撃が 0 体だったときの試し直しの間(日。見直し ②1): 拠点のまわりが読み込まれていない・予算が足りない。夜の終わり(tick)を過ぎたら次の夜。【仮】 */
    public final double firstRetryUnloadedDays;
    public final double firstRetryPoorDays;
    public final int nightLastTicks;

    private AssaultTable(Json j) {
        Json s = j != null ? j : new Json();
        BaseMemoryTable.WaveJson f = s.firstRaid;
        this.firstRaid = f == null ? new BaseMemoryTable.Wave(0.0D, 7, 2, 6, 0)
                : new BaseMemoryTable.Wave(0.0D, cnt(f.stageZero, 7), cnt(f.developed, 2), cnt(f.g4, 6), cnt(f.g5plus, 0));
        this.nightStartTicks = clampTick(s.nightStartTicks, 13000);
        this.nightEndTicks = Math.max(this.nightStartTicks, clampTick(s.nightEndTicks, 15000));
        this.maxWave = Math.max(1, Math.min(200, s.maxWave != null ? s.maxWave : 40));
        this.stageZeroCost = nn(s.stageZeroCost, 1.0D);
        this.hoardShareFactor = Math.max(1.0D, nn(s.hoardShareFactor, 1.5D));
        this.foulRadius = Math.max(4.0D, nn(s.foulRadius, 24.0D));
        this.foulAirPercentPerMinute = nn(s.foulAirPercentPerMinute, 1.0D);
        this.foulDepositShare = nn(s.foulDepositShare, 0.25D);
        this.foulIntervalTicks = Math.max(20, s.foulIntervalTicks != null ? s.foulIntervalTicks : 100);
        this.breakStuckTicks = Math.max(10, s.breakStuckTicks != null ? s.breakStuckTicks : 40);
        this.breakTicksBase = Math.max(1, s.breakTicksBase != null ? s.breakTicksBase : 60);
        this.breakTicksPerHardness = Math.max(0, s.breakTicksPerHardness != null ? s.breakTicksPerHardness : 40);
        this.breakReach = Math.max(1.0D, Math.min(4.0D, nn(s.breakReach, 2.0D)));
        this.hoardMaxAlive = Math.max(1, Math.min(200, s.hoardMaxAlive != null ? s.hoardMaxAlive : 40));
        this.hoardMaxTotal = Math.max(this.hoardMaxAlive, Math.min(500, s.hoardMaxTotal != null ? s.hoardMaxTotal : 80));
        this.hoardRefillTicks = Math.max(20, s.hoardRefillTicks != null ? s.hoardRefillTicks : 200);
        this.hoardMaxDays = Math.max(0.05D, nn(s.hoardMaxDays, 1.0D));
        this.firstRetryUnloadedDays = Math.max(0.005D, nn(s.firstRetryUnloadedDays, 0.02D));
        this.firstRetryPoorDays = Math.max(0.005D, nn(s.firstRetryPoorDays, 0.05D));
        this.nightLastTicks = Math.max(this.nightEndTicks, clampTick(s.nightLastTicks, 23000));
    }

    public static AssaultTable fromJson(Json j) {
        return new AssaultTable(j);
    }

    private static int cnt(Integer v, int d) {
        return v != null ? Math.max(0, Math.min(200, v)) : d;
    }

    private static int clampTick(Integer v, int d) {
        return v != null ? Math.max(0, Math.min(23999, v)) : d;
    }

    private static double nn(Double v, double fallback) {
        return v != null && Double.isFinite(v) ? Math.max(0.0D, v) : fallback;
    }
}
