package com.yayuy.overgrownvegetation.raid;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import com.yayuy.overgrownvegetation.defense.SporeAlarms;
import com.yayuy.overgrownvegetation.entity.SpawnFit;
import com.yayuy.overgrownvegetation.phase.OutsideInvaderSpawner;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 小規模レイドの発生(2026-09-19)。狙うプレイヤーを 1 人選び、その遠くの地表に一団を湧かせて
 * {@link SmallRaidHuntGoal} の狙いを付ける。警報器があれば方角と距離つきで警告が出る
 * ({@link SporeAlarms#warn})。警報器が無ければ<b>何も知らせない</b>(気づいたら囲まれている)。
 */
public final class SmallRaidController {

    /** 地点を探す回数(未ロード・水面・過繫茂バイオームの中などで空振りするため)。 */
    private static final int SITE_ATTEMPTS = 24;
    /** 1 体ごとの配置を探す回数。 */
    private static final int PLACE_ATTEMPTS = 8;
    /** 一団が散らばる半径(ブロック)。 */
    private static final int CLUSTER_RADIUS = 6;

    /**
     * 一団を 1 つ出す。プレイヤーが居ない・適地が無いなら false。
     */
    public static boolean tryStart(ServerLevel level, int phase) {
        ServerPlayer target = pickTarget(level);
        if (target == null) {
            return false;
        }
        return start(level, target, phase);
    }

    /** 指定のプレイヤーを狙う一団を出す(コマンド用にも公開)。 */
    public static boolean start(ServerLevel level, ServerPlayer target, int phase) {
        PhaseTable phases = DesignData.phases();
        int minDistance = phases.smallRaidMinDistance(phase);
        int maxDistance = phases.smallRaidMaxDistance(phase);
        RandomSource random = level.random;

        BlockPos site = findSite(level, target, minDistance, maxDistance, random);
        if (site == null) {
            return false;
        }

        List<Mob> spawned = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : phases.smallRaidComposition(phase).entrySet()) {
            EntityType<?> type = OutsideInvaderSpawner.resolve(entry.getKey());
            if (type == null || !MobStats.naturalSpawn(type)) {
                continue;
            }
            int count = entry.getValue() != null ? entry.getValue() : 0;
            for (int i = 0; i < count; i++) {
                Mob mob = spawnOne(level, type, site, random);
                if (mob != null) {
                    spawned.add(mob);
                }
            }
        }
        if (spawned.isEmpty()) {
            return false;
        }
        for (Mob mob : spawned) {
            mob.setPersistenceRequired();
            mob.setData(ModAttachments.HUNT_TARGET, target.getUUID().toString());
        }
        // 近くに巣があれば住人も送り出す(ユーザー指定 2026-09-20)。
        // 巣から出るぶんは NestExitGoal が道しるべを辿って坑口まで歩かせる。
        int reinforced = com.yayuy.overgrownvegetation.nest.NestReinforcements.dispatch(level, site, target);
        SporeAlarms.warn(level, Component.translatable("message.overgrown_vegetation.small_raid.started",
                spawned.size() + reinforced), Vec3.atCenterOf(site));
        return true;
    }

    /** 狙うプレイヤー(サバイバル/アドベンチャーのみ)をランダムに 1 人。 */
    @Nullable
    private static ServerPlayer pickTarget(ServerLevel level) {
        List<ServerPlayer> candidates = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && !player.isCreative() && player.isAlive()) {
                candidates.add(player);
            }
        }
        return candidates.isEmpty() ? null : candidates.get(level.random.nextInt(candidates.size()));
    }

    /**
     * プレイヤーから {@code min}〜{@code max} ブロックの輪の中で、ロード済みかつ水面でない地表を探す。
     * 過繫茂バイオームの中でも構わない(そこからやって来る方が自然)。
     */
    @Nullable
    private static BlockPos findSite(ServerLevel level, ServerPlayer player, int min, int max, RandomSource random) {
        for (int i = 0; i < SITE_ATTEMPTS; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            int distance = min + random.nextInt(max - min + 1);
            int x = player.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = player.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            if (!level.hasChunkAt(new BlockPos(x, level.getSeaLevel(), z))) {
                continue;
            }
            BlockPos pos = TreeCanopyGroundFinder.findSpawnSurface(level, x, z);
            if (pos == null) {
                continue;
            }
            if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.above()).isEmpty()) {
                continue;
            }
            return pos;
        }
        return null;
    }

    @Nullable
    private static Mob spawnOne(ServerLevel level, EntityType<?> type, BlockPos site, RandomSource random) {
        for (int i = 0; i < PLACE_ATTEMPTS; i++) {
            int x = site.getX() + random.nextInt(CLUSTER_RADIUS * 2 + 1) - CLUSTER_RADIUS;
            int z = site.getZ() + random.nextInt(CLUSTER_RADIUS * 2 + 1) - CLUSTER_RADIUS;
            if (!level.hasChunkAt(new BlockPos(x, site.getY(), z))) {
                continue;
            }
            BlockPos pos = TreeCanopyGroundFinder.findSpawnSurface(level, x, z);
            if (pos == null || !level.getFluidState(pos).isEmpty()) {
                continue;
            }
            if (!SpawnFit.fits(level, type, pos)) {
                continue;
            }
            Entity entity = type.create(level, null, pos, MobSpawnType.EVENT, false, false);
            if (!(entity instanceof Mob mob)) {
                return null;
            }
            level.addFreshEntity(mob);
            return mob;
        }
        return null;
    }

    private SmallRaidController() {
    }
}
