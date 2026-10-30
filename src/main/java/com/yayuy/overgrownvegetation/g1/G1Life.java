package com.yayuy.overgrownvegetation.g1;

import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * G1 の 1 体の一生(G1 の作り直し 第 1 段、2026-10-22)。データの添付({@code ModAttachments.G1_LIFE})で個体に保存する
 * (【案】「進化の時計は個体に保存」)。
 *
 * <ul>
 *     <li>{@link #age} … 段階 0 で起き上がってからの tick(読み込まれている間だけ進む)。{@code g1.json life.branchAfterDays} で司令塔に分岐を聞く</li>
 *     <li>{@link #route} … 司令塔が決めた道。決まってからの tick は {@link #routeAge}(進化なら今の段に入ってからの tick)</li>
 *     <li>根付きの汚染は {@link #spreader}(汚染シダ・死体と同じ {@link CorruptionSpreader})、苗床の行き先は {@link #nurseryTarget}</li>
 * </ul>
 */
public final class G1Life implements INBTSerializable<CompoundTag> {

    public enum Route {
        /** まだ決まっていない(段階 0)。 */
        NONE,
        /** その場に根付いて汚染し、ブロックになる。 */
        ROOTED,
        /** 湿った暗い場所へ歩き、苗床のブロックになる。 */
        NURSERY,
        /** 進化する(1 段目 → 2 段目)。 */
        EVOLVE
    }

    public long age;
    public Route route = Route.NONE;
    public long routeAge;
    public CorruptionSpreader spreader;
    public BlockPos nurseryTarget;
    /** 司令塔の判断の要約(/ov perf g1 で見る)。 */
    public String note = "";
    /** 読み込み後に道ごとの Goal を付け直したか(保存しない)。 */
    public transient boolean applied;

    public CorruptionSpreader spreader() {
        if (spreader == null) {
            spreader = new CorruptionSpreader();
        }
        return spreader;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Age", age);
        tag.putString("Route", route.name());
        tag.putLong("RouteAge", routeAge);
        tag.putString("Note", note);
        if (nurseryTarget != null) {
            tag.putLong("NurseryTarget", nurseryTarget.asLong());
        }
        if (spreader != null) {
            CompoundTag s = new CompoundTag();
            spreader.save(s);
            tag.put("Spreader", s);
        }
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        age = tag.getLong("Age");
        try {
            route = Route.valueOf(tag.getString("Route"));
        } catch (IllegalArgumentException e) {
            route = Route.NONE;
        }
        routeAge = tag.getLong("RouteAge");
        note = tag.getString("Note");
        nurseryTarget = tag.contains("NurseryTarget") ? BlockPos.of(tag.getLong("NurseryTarget")) : null;
        if (tag.contains("Spreader")) {
            spreader = new CorruptionSpreader();
            spreader.load(tag.getCompound("Spreader"));
        }
        applied = false;
    }
}
