package com.yayuy.overgrownvegetation.ecosystem.life;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * 環境生物の 1 体のサーバー側の心(2026-11-18、行動の段)。満腹度は「最後に量った値と時刻」だけを持ち、読むときに減らす(毎 tick 数えない)。
 * 満腹度 0〜1。{@link EcoBehavior#hungerTicks} で 1 → 0 まで減る。保存は Mob の追加データ {@code OvEco}。
 */
public final class EcoMind {

    private double fullness = -1.0D;
    private long at;
    /** 今の動作と、それを終える時刻(ゲーム時刻)。 */
    EcoAct act = EcoAct.NONE;
    long actUntil;
    /** 住んでいる洞(無ければ null)。洞から出た個体に付く。 */
    @javax.annotation.Nullable
    public net.minecraft.core.BlockPos home;
    /** 初めて見た時刻(育ちの物差し。幼虫の羽化)。 */
    public long born = Long.MIN_VALUE;
    /** 洞から這い出ている残り tick と、その向き(サーバー・保存しない)。 */
    public int exiting;
    public net.minecraft.core.Direction exitDir = net.minecraft.core.Direction.NORTH;

    /** 満腹度(0〜1)。初めて読むときは 0.3〜0.8 で始める(湧いた直後に全員が一斉に狩らないように)。 */
    public double fullness(long now, EcoBehavior b, RandomSource random) {
        if (this.fullness < 0.0D) {
            this.fullness = 0.3D + random.nextDouble() * 0.5D;
            this.at = now;
        }
        double spent = Math.max(0L, now - this.at) / (double) Math.max(1L, b.hungerTicks);
        return Mth.clamp(this.fullness - spent, 0.0D, 1.0D);
    }

    /** 食べた: 満腹度に {@code amount} を足す(1 を超えない)。 */
    public void feed(long now, EcoBehavior b, RandomSource random, double amount) {
        double f = fullness(now, b, random);
        this.fullness = Mth.clamp(f + amount, 0.0D, 1.0D);
        this.at = now;
    }

    /** 確かめ用: 満腹度をじかに決める。 */
    public void set(long now, double value) {
        this.fullness = Mth.clamp(value, 0.0D, 1.0D);
        this.at = now;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putDouble("Full", this.fullness);
        t.putLong("At", this.at);
        if (this.home != null) t.putLong("Home", this.home.asLong());
        t.putLong("Born", this.born);
        return t;
    }

    public void load(CompoundTag t) {
        if (t.contains("Full")) this.fullness = t.getDouble("Full");
        this.at = t.getLong("At");
        this.home = t.contains("Home") ? net.minecraft.core.BlockPos.of(t.getLong("Home")) : null;
        this.born = t.contains("Born") ? t.getLong("Born") : Long.MIN_VALUE;
    }
}
