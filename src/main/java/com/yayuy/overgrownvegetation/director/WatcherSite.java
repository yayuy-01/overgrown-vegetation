package com.yayuy.overgrownvegetation.director;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * 司令塔が植えた監視塔 1 本の記録(2026-11-14)。拠点の記憶({@link BaseMemory.Base#watchers})に持つ。
 * 立っている間だけ持ち、刈られたら外す(刈られた本数は拠点の側に数える)。
 */
public final class WatcherSite {

    private final BlockPos pos;
    private final double plantedDay;
    private boolean grown;

    WatcherSite(BlockPos pos, double plantedDay) {
        this.pos = pos.immutable();
        this.plantedDay = plantedDay;
    }

    public BlockPos pos() {
        return pos;
    }

    public double plantedDay() {
        return plantedDay;
    }

    /** 育ち切ったか(監視塔の側が {@link Watchers#onGrown} で知らせる)。 */
    public boolean grown() {
        return grown;
    }

    void setGrown() {
        grown = true;
    }

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putLong("Pos", pos.asLong());
        t.putDouble("At", plantedDay);
        t.putBoolean("Grown", grown);
        return t;
    }

    static WatcherSite load(CompoundTag t) {
        WatcherSite w = new WatcherSite(BlockPos.of(t.getLong("Pos")), t.getDouble("At"));
        w.grown = t.getBoolean("Grown");
        return w;
    }
}
