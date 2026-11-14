package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.BaseMemoryTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/**
 * 拠点へ攻めて行く使い(2026-11-14、司令塔 第 2 段 区切り 3。D-8・D-12)。段階 0 と 1 段目(進化体)のどちらも持てる
 * (1 段目には {@link G1Fate} が無いので、Mob の側に直に持つ)。歩くのは {@link G1MarchGoal}。
 *
 * <p>認識した拠点の真ん中へ歩き、{@code marchArriveBlocks} まで近づいたら使いは終わり(そこからは、いつもの狙い方でプレイヤー・家畜を襲う)。
 * 途中で獲物を見つけたら、そちらを先に襲う(歩きは止まり、獲物がいなくなったら続きを歩く)。{@code marchTimeoutTicks} 歩いても着かなければ終わり。
 * 昼も夜も向かう(11-14 ユーザー決定 Q8 = 1)。
 */
public final class G1March {

    private boolean active;
    private int baseId;
    private double baseX;
    private double baseZ;
    private int ticks;
    /** 回り込み(第 3 段 区切り 2): 先に向かう弱い方角の点。着いたら真ん中へ。 */
    private boolean hasWay;
    private double wayX;
    private double wayZ;

    public boolean active() {
        return active;
    }

    public int baseId() {
        return baseId;
    }

    public Vec3 target(double y) {
        return hasWay ? new Vec3(wayX, y, wayZ) : new Vec3(baseX, y, baseZ);
    }

    /** 回り込みの途中か。 */
    public boolean flanking() {
        return hasWay;
    }

    /**
     * 拠点へ、守りの薄い方角から回り込んで向かう(11-13 ユーザー「弱い所を突く」)。弱い方角が分かっていて、
     * 回り込む割合(flankShare × 段の強さ。11-14 ユーザー「回り込む割合に段の強さを掛ける」)の確率に当たれば、先に弱い方角の輪の上の点へ。
     */
    public void beginFlank(BaseMemory.Base base, double flankChance, net.minecraft.util.RandomSource random) {
        begin(base);
        Vec3 way = base.weakPoint(DesignData.director().base.flankRadius, 0.0D);
        if (way != null && random.nextDouble() < flankChance) {
            hasWay = true;
            wayX = way.x;
            wayZ = way.z;
        }
    }

    public void begin(BaseMemory.Base base) {
        beginTo(base.centerX(), base.centerZ(), base.id());
    }

    /** 拠点以外の行き先へ(第 3 段の「危ない所」など。{@code id} は拠点の番号の代わりの印。危ない所は負の数)。 */
    public void beginTo(double x, double z, int id) {
        active = true;
        hasWay = false;
        baseId = id;
        baseX = x;
        baseZ = z;
        ticks = 0;
    }

    /** 進化で入れ替わるとき、使いを新しい体へ引き継ぐ。 */
    public void copyFrom(G1March other) {
        active = other.active;
        baseId = other.baseId;
        baseX = other.baseX;
        baseZ = other.baseZ;
        ticks = other.ticks;
        hasWay = other.hasWay;
        wayX = other.wayX;
        wayZ = other.wayZ;
    }

    /** 1 tick(Mob の tick から。歩いていない間も数える = 何かに引っかかっていても時間切れで終わる)。着いた・時間切れなら終わる。 */
    public void step(Vec3 pos) {
        if (!active) return;
        BaseMemoryTable t = DesignData.director().base;
        ticks++;
        if (hasWay) {
            double wx = pos.x - wayX, wz = pos.z - wayZ;
            if (wx * wx + wz * wz <= t.flankArriveBlocks * t.flankArriveBlocks) hasWay = false;
            if (ticks >= t.marchTimeoutTicks) active = false;
            return;
        }
        double dx = pos.x - baseX, dz = pos.z - baseZ;
        if (dx * dx + dz * dz <= t.marchArriveBlocks * t.marchArriveBlocks || ticks >= t.marchTimeoutTicks) active = false;
    }

    public void cancel() {
        active = false;
    }

    public int ticks() {
        return ticks;
    }

    public void setTicksForTests(int t) {
        ticks = t;
    }

    public void save(CompoundTag tag) {
        if (!active) return;
        CompoundTag m = new CompoundTag();
        m.putInt("Base", baseId);
        m.putDouble("X", baseX);
        m.putDouble("Z", baseZ);
        m.putInt("Ticks", ticks);
        if (hasWay) {
            m.putDouble("WX", wayX);
            m.putDouble("WZ", wayZ);
        }
        tag.put("March", m);
    }

    public void load(CompoundTag tag) {
        active = tag.contains("March");
        if (!active) return;
        CompoundTag m = tag.getCompound("March");
        baseId = m.getInt("Base");
        baseX = m.getDouble("X");
        baseZ = m.getDouble("Z");
        ticks = m.getInt("Ticks");
        hasWay = m.contains("WX");
        wayX = m.getDouble("WX");
        wayZ = m.getDouble("WZ");
    }
}
