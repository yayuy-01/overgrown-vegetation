package com.yayuy.overgrownvegetation.base;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DefenseTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>点いている</b>妨害灯({@link JammingLampBlock})の台帳と、灯りが掛ける弱体化(2026-10-01)。
 *
 * <p>妨害灯は忌避灯の上位(ユーザー指定 2026-10-01): 半径の中に<b>グループ6 以下</b>を寄せ付けず、
 * 半径の中の過繁茂の Mob に<b>攻撃力の低下と移動速度の低下</b>を掛ける。忌避灯の台帳
 * ({@link com.yayuy.overgrownvegetation.defense.RepellentLamps})にも同時に載るので、グループ1〜4 の追い出し・
 * TD の群れが灯りを覆いに行く・ヴェロフィルの爆撃は忌避灯と同じに効く。ここはグループ5〜6 の追い出し
 * ({@link JammingAvoidGoal})と弱体化のための台帳。
 *
 * <p>半径は忌避灯と同じ {@code defense.json} の {@code repellentLamp.radius}(TD の群れの灯りの扱いとずれないように、仮決め)。
 * 弱体化はバニラの「弱体化」(攻撃力 −4 × 段階)と「移動速度低下」(−15% × 段階)。段階・持続・間隔は {@code jammingLamp}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class JammingLamps {

    private static final Map<ResourceKey<Level>, Set<BlockPos>> ACTIVE = new ConcurrentHashMap<>();
    /** {@link #ACTIVE} を 32 ブロック四方の区画に分けたもの(忌避灯の台帳と同じ作り)。追い出しの Goal が 2 tick ごとに引く。 */
    private static final Map<ResourceKey<Level>, Map<Long, Set<BlockPos>>> CELLS = new ConcurrentHashMap<>();
    private static final int CELL_SHIFT = 5;

    public static void register(Level level, BlockPos pos) {
        BlockPos key = pos.immutable();
        ACTIVE.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet()).add(key);
        CELLS.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(cellOf(key.getX(), key.getZ()), k -> ConcurrentHashMap.newKeySet())
                .add(key);
    }

    public static void unregister(Level level, BlockPos pos) {
        Set<BlockPos> set = ACTIVE.get(level.dimension());
        if (set != null) {
            set.remove(pos);
        }
        Map<Long, Set<BlockPos>> cells = CELLS.get(level.dimension());
        if (cells != null) {
            cells.computeIfPresent(cellOf(pos.getX(), pos.getZ()), (k, cell) -> {
                cell.remove(pos);
                return cell.isEmpty() ? null : cell;
            });
        }
    }

    /** 台帳に載っているか(GameTest 用)。 */
    public static boolean isRegistered(Level level, BlockPos pos) {
        Set<BlockPos> set = ACTIVE.get(level.dimension());
        return set != null && set.contains(pos);
    }

    private static long cellOf(int blockX, int blockZ) {
        return ChunkPos.asLong(blockX >> CELL_SHIFT, blockZ >> CELL_SHIFT);
    }

    /** {@code from} から {@code range} 以内で最も近い、点いている妨害灯。無ければ null。 */
    @Nullable
    public static BlockPos nearestActive(Level level, Vec3 from, double range) {
        Map<Long, Set<BlockPos>> cells = CELLS.get(level.dimension());
        if (cells == null || cells.isEmpty() || !(range >= 0.0D)) {
            return null;
        }
        int minCx = Mth.floor(from.x - range - 1.0D) >> CELL_SHIFT;
        int maxCx = Mth.floor(from.x + range + 1.0D) >> CELL_SHIFT;
        int minCz = Mth.floor(from.z - range - 1.0D) >> CELL_SHIFT;
        int maxCz = Mth.floor(from.z + range + 1.0D) >> CELL_SHIFT;
        BlockPos best = null;
        double bestSq = range * range;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                Set<BlockPos> cell = cells.get(ChunkPos.asLong(cx, cz));
                if (cell == null) {
                    continue;
                }
                for (BlockPos pos : cell) {
                    double sq = from.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
                    if (sq <= bestSq) {
                        bestSq = sq;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    /**
     * 灯り 1 つぶんの弱体化: 半径の中の過繁茂の Mob(グループ1〜10 のどれか)へ弱体化と移動速度低下を付け直す。
     * 追い出せないグループ7 以上にも効く。付けた数を返す。{@link JammingLampBlockEntity} が間隔ごとに呼ぶ。
     */
    public static int applyDebuffs(ServerLevel level, BlockPos lamp) {
        List<Mob> targets = targets(level, lamp);
        targets.forEach(JammingLamps::debuff);
        return targets.size();
    }

    /** 灯り {@code lamp} の半径の中にいる過繁茂の Mob(グループ1〜10 のどれか)。 */
    public static List<Mob> targets(ServerLevel level, BlockPos lamp) {
        double radius = DesignData.defense().lampRadius();
        Vec3 center = Vec3.atCenterOf(lamp);
        double radiusSq = radius * radius;
        return level.getEntitiesOfClass(Mob.class, new AABB(lamp).inflate(radius),
                mob -> mob.isAlive() && MobGroup.of(mob) != null && mob.distanceToSqr(center) <= radiusSq);
    }

    /** 1 体に弱体化と移動速度低下を付け直す(段階・持続は {@code jammingLamp})。 */
    public static void debuff(Mob mob) {
        DefenseTable defense = DesignData.defense();
        int ticks = defense.jammingEffectTicks();
        mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, defense.jammingWeaknessAmplifier(), true, true));
        mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, defense.jammingSlownessAmplifier(), true, true));
    }

    /**
     * グループ5〜{@code jammingLamp.maxGroup} に追い出しの Goal を足す。1〜4 は忌避灯の Goal
     * ({@code entity/GuidedHuntInjector} が足す)が妨害灯も忌避灯の台帳から見るので要らない。
     * ヴェロフィルは灯りを爆撃する側(グループ4)なので、ここには来ない。
     */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof PathfinderMob mob)) {
            return;
        }
        MobGroup group = MobGroup.of(mob);
        if (group != null && group.number() > 4 && group.number() <= DesignData.defense().jammingMaxGroup()) {
            mob.goalSelector.addGoal(1, new JammingAvoidGoal(mob, 1.1D));
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ACTIVE.clear();
        CELLS.clear();
    }

    private JammingLamps() {
    }
}
