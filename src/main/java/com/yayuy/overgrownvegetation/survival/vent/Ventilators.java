package com.yayuy.overgrownvegetation.survival.vent;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.data.VentSpec;
import com.yayuy.overgrownvegetation.corruption.pushback.AbsentWards;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 読み込まれている換気機の一覧と、胞子の場とのやりとり(2026-11-14 段 2c。サーバースレッドだけ)。
 *
 * <ul>
 *     <li>{@link #snapshot}: 場の計算を始める前に、動いている機械の場所と「この計算で抜ける量」を写す
 *     (眠りで一度に何十回も進むときも、フィルターの残り以上は抜かない)。</li>
 *     <li>{@link #apply}: 計算が抜いた量を機械へ返す(フィルターが詰まる)。</li>
 *     <li>{@link #covers}: 稼働中の機械の範囲のチャンクか(そこでは汚染の下支えを外し、場の値だけで読む)。</li>
 * </ul>
 * 吸うのはオーバーワールドの場だけ(ネザーの場は別の作り。ネザーでは回るが何も抜かない【仮】)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class Ventilators {

    private static final Map<ResourceKey<Level>, Set<VentBlockEntity>> LOADED = new HashMap<>();

    private Ventilators() {
    }

    static void add(ServerLevel level, VentBlockEntity be) {
        LOADED.computeIfAbsent(level.dimension(), k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(be);
    }

    static void remove(ServerLevel level, VentBlockEntity be) {
        Set<VentBlockEntity> set = LOADED.get(level.dimension());
        if (set != null) set.remove(be);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LOADED.clear();
    }

    /** その次元で動いている機械(壊れた・消えた物は外す)。 */
    public static List<VentBlockEntity> running(ServerLevel level) {
        Set<VentBlockEntity> set = LOADED.get(level.dimension());
        if (set == null || set.isEmpty()) return List.of();
        List<VentBlockEntity> out = new ArrayList<>();
        set.removeIf(be -> be.isRemoved() || be.getLevel() == null);
        for (VentBlockEntity be : set) if (be.running()) out.add(be);
        return out;
    }

    /** 場の計算へ渡す写し: 機械の場所(BlockPos#asLong)と、この計算で抜ける量(場の単位)。 */
    public record Snapshot(long[] pos, double[] budget) {
        public static final Snapshot NONE = new Snapshot(new long[0], new double[0]);
    }

    public static Snapshot snapshot(ServerLevel level, int steps, SporeFieldTable table) {
        if (level.dimension() != Level.OVERWORLD) return Snapshot.NONE;
        List<VentBlockEntity> run = running(level);
        List<AbsentWards.Machine> away = AbsentWards.guardingVents(level); // 留守の機械も場から吸い、覚えのフィルターが詰まる(11-23)
        if (run.isEmpty() && away.isEmpty()) return Snapshot.NONE;
        it.unimi.dsi.fastutil.longs.LongArrayList pos = new it.unimi.dsi.fastutil.longs.LongArrayList();
        it.unimi.dsi.fastutil.doubles.DoubleArrayList budget = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
        for (VentBlockEntity be : run) {
            pos.add(be.getBlockPos().asLong());
            budget.add(be.budgetUnits(steps, table));
        }
        for (AbsentWards.Machine m : away) {
            if (pos.contains(m.pos)) continue;
            pos.add(m.pos);
            budget.add(AbsentWards.awayBudget(level, m, steps, table));
        }
        return new Snapshot(pos.toLongArray(), budget.toDoubleArray());
    }

    /** 計算が抜いた量を機械へ返す。機械が消えていれば捨てる。 */
    public static void apply(ServerLevel level, long[] pos, double[] extracted, int steps, SporeFieldTable table) {
        for (int i = 0; i < pos.length; i++) {
            BlockPos p = BlockPos.of(pos[i]);
            AbsentWards.Machine m = AbsentWards.find(level, p, true);
            if (m != null && m.isAway(level.getGameTime())) {
                AbsentWards.applyAway(level, p, extracted[i], steps, table); // 留守の機械は覚えのフィルターへ(11-23)
            } else if (level.isLoaded(p) && level.getBlockEntity(p) instanceof VentBlockEntity be) {
                be.onExtracted(extracted[i], steps, table);
            }
        }
    }

    /**
     * チャンク (cx, cz) が稼働中の換気機の範囲か。範囲の中では汚染の下支え(繁茂 10%・過繁茂 25%)を外し、場の値だけで読む
     * (2026-11-14 段 2c【仮】。機械が流れ込みに追いついていれば場の値はほぼ 0、嵐で追いつかなければ場の値そのものが高い)。
     */
    public static boolean covers(ServerLevel level, int cx, int cz, VentSpec spec) {
        if (!spec.lowerFloor() || level.dimension() != Level.OVERWORLD) return false;
        Set<VentBlockEntity> set = LOADED.getOrDefault(level.dimension(), Set.of());
        double r2 = spec.radiusChunks() * spec.radiusChunks();
        for (VentBlockEntity be : set) {
            if (be.isRemoved() || !be.running()) continue;
            int dx = (be.getBlockPos().getX() >> 4) - cx, dz = (be.getBlockPos().getZ() >> 4) - cz;
            if (dx * dx + dz * dz <= r2) return true;
        }
        for (AbsentWards.Machine m : AbsentWards.guardingVents(level)) {
            int dx = (BlockPos.getX(m.pos) >> 4) - cx, dz = (BlockPos.getZ(m.pos) >> 4) - cz;
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    /** 動いている機械のいるチャンク(場の写しを作る区画に足す)。 */
    public static long[] runningChunks(ServerLevel level) {
        List<VentBlockEntity> run = running(level);
        List<AbsentWards.Machine> away = AbsentWards.guardingVents(level); // 留守でも守っている機械(11-23)
        long[] out = new long[run.size() + away.size()];
        for (int i = 0; i < run.size(); i++) {
            BlockPos p = run.get(i).getBlockPos();
            out[i] = net.minecraft.world.level.ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4);
        }
        for (int i = 0; i < away.size(); i++) {
            long p = away.get(i).pos;
            out[run.size() + i] = net.minecraft.world.level.ChunkPos.asLong(BlockPos.getX(p) >> 4, BlockPos.getZ(p) >> 4);
        }
        return out;
    }
}
