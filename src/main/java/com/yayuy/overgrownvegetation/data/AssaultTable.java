package com.yayuy.overgrownvegetation.data;

/**
 * F5 の襲撃の設計値({@code director.json} の {@code assault}、2026-11-16)。正本は Vault「F5 の襲撃(最初の襲撃と送り込み)(11-16)」。
 *
 * <p>ユーザーが決めたのは: 最初の襲撃は認識した次の夜に G1 と G2 を中心に約 15 体・前触れなし / 送り込みは 1 回 10 体から最大 40 体・20〜40 分おき /
 * 着いた敵は拠点を汚す(1 体 1 分で空気 +1%・降り積もりは空気の 1/4)/ 壁や扉を壊せるのは木・ガラス・土と扉だけ(タグ {@code raid_breakable})・
 * 壊すのは力のある種だけ / 貯めた襲撃は送り込みを 1〜2 回飛ばしてまとめた 1 回(上限 40・進化体と G3 を 1.5 倍)。<b>ほかの数は【仮】</b>。
 */
public final class AssaultTable {

    public static final AssaultTable DEFAULT = new AssaultTable(null);

    public static final class Json {
        public BaseMemoryTable.WaveJson firstRaid;
        public Integer nightStartTicks;
        public Integer nightEndTicks;
        public Double foulRadius;
        public Double foulAirPercentPerMinute;
        public Double foulDepositShare;
        public Integer foulIntervalTicks;
        public Integer breakStuckTicks;
        public Integer breakTicksBase;
        public Integer breakTicksPerHardness;
        public Double breakReach;
        public Integer hoardMaxAlive;
        public Integer hoardRefillTicks;
        public Double firstRetryPoorDays;
        public Integer nightLastTicks;
    }

    /** 最初の襲撃の顔ぶれ(fromDays は使わない)。ユーザー「G1 と G2 を中心に約 15 体」。 */
    public final BaseMemoryTable.Wave firstRaid;
    /** 最初の襲撃が来る時刻の幅(1 日の中の tick。13000 = 夜の始め)。 */
    public final int nightStartTicks;
    public final int nightEndTicks;
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
    /** 貯めた襲撃(11-16 ユーザー): 同時にいるのは hoardMaxAlive(40)まで・継ぎ足しの間(tick)【仮】。前線の中の襲撃が使う(11-23 片付け E: 合計 hoardMaxTotal・長さ hoardMaxDays は消した)。 */
    public final int hoardMaxAlive;
    public final int hoardRefillTicks;
    /** 最初の襲撃で前線から出せる部隊が無かったときの試し直しの間(日。見直し ②1)。夜の終わり(tick)を過ぎたら次の夜。【仮】 */
    public final double firstRetryPoorDays;
    public final int nightLastTicks;

    private AssaultTable(Json j) {
        Json s = j != null ? j : new Json();
        BaseMemoryTable.WaveJson f = s.firstRaid;
        this.firstRaid = f == null ? new BaseMemoryTable.Wave(0.0D, 7, 2, 6, 0)
                : new BaseMemoryTable.Wave(0.0D, cnt(f.stageZero, 7), cnt(f.developed, 2), cnt(f.g2, 6), cnt(f.g3plus, 0));
        this.nightStartTicks = clampTick(s.nightStartTicks, 13000);
        this.nightEndTicks = Math.max(this.nightStartTicks, clampTick(s.nightEndTicks, 15000));
        this.foulRadius = Math.max(4.0D, nn(s.foulRadius, 24.0D));
        this.foulAirPercentPerMinute = nn(s.foulAirPercentPerMinute, 1.0D);
        this.foulDepositShare = nn(s.foulDepositShare, 0.25D);
        this.foulIntervalTicks = Math.max(20, s.foulIntervalTicks != null ? s.foulIntervalTicks : 100);
        this.breakStuckTicks = Math.max(10, s.breakStuckTicks != null ? s.breakStuckTicks : 40);
        this.breakTicksBase = Math.max(1, s.breakTicksBase != null ? s.breakTicksBase : 60);
        this.breakTicksPerHardness = Math.max(0, s.breakTicksPerHardness != null ? s.breakTicksPerHardness : 40);
        this.breakReach = Math.max(1.0D, Math.min(4.0D, nn(s.breakReach, 2.0D)));
        this.hoardMaxAlive = Math.max(1, Math.min(200, s.hoardMaxAlive != null ? s.hoardMaxAlive : 40));
        this.hoardRefillTicks = Math.max(20, s.hoardRefillTicks != null ? s.hoardRefillTicks : 200);
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
