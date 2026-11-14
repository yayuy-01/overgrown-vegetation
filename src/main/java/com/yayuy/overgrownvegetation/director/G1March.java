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

    public boolean active() {
        return active;
    }

    public int baseId() {
        return baseId;
    }

    public Vec3 target(double y) {
        return new Vec3(baseX, y, baseZ);
    }

    public void begin(BaseMemory.Base base) {
        beginTo(base.centerX(), base.centerZ(), base.id());
    }

    /** 拠点以外の行き先へ(第 3 段の「危ない所」など。{@code id} は拠点の番号の代わりの印。危ない所は負の数)。 */
    public void beginTo(double x, double z, int id) {
        active = true;
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
    }

    /** 1 tick(Mob の tick から。歩いていない間も数える = 何かに引っかかっていても時間切れで終わる)。着いた・時間切れなら終わる。 */
    public void step(Vec3 pos) {
        if (!active) return;
        BaseMemoryTable t = DesignData.director().base;
        ticks++;
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
    }
}
