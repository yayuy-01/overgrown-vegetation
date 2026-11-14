package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.MobInfectionSpec;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Mob の胞子感染と飛び火・動物が汚染から逃げる(2026-11-14 ユーザー決定。正本は Vault「フェーズの仕組み(ポイント・日数)」の F1、
 * 数値は {@code spore_field.json} の {@code mobInfection} = {@link MobInfectionSpec})。
 *
 * <h2>流れ</h2>
 * <ol>
 *     <li>感染の始まり: 暴露量を測る Mob({@link SporeExposureTracker#isMeasuredMob} = 植物側以外の全部)が、Mob の濃度
 *     ({@link SporeExposureTracker#mobPercent}。屋根の決まり込み)が exposure.minPercent 以上の所に startSeconds 続けていると感染。
 *     出たら数え直す【仮】。防護マスクが効いている間は数えない【仮】。数えている間は判定を countStepTicks ごとに細かくする。</li>
 *     <li>感染した後: 暴露量は減らず、growPerSecond ずつ上がる(濃い所ではその分も足す)。1500 で 20 ダメージ・最大体力 −20 の決まりは
 *     そのまま({@link SporeExposureTracker})なので、死因は {@code spore_infection} のまま —— G1 になれる種は今ある鎖({@code corpse/G1Infection})で
 *     死体 → G1、なれない種は萌芽体。治らない【仮】。</li>
 *     <li>飛び火: 感染した Mob が死んだら(死因を問わない【仮】)、そのチャンクの場へ降り積もり jumpDeposit と浮遊 jumpEmit を足す
 *     (発芽のしきい値を超えるので、湿った土地ならそのチャンクがやがて発芽して汚染が始まる)。同じチャンクは jumpCooldownTicks に 1 回。</li>
 *     <li>逃げる: 動物({@link #isFleer})は濃度が逃げ始めの値以上の所で、濃い方から離れる向きへ逃げる({@link SporeFleeGoal})。
 *     感染した後はずっと逃げ、汚染の無い所では最後の向きのまま —— 結果として汚染を外へ運ぶ。</li>
 * </ol>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class MobInfection {

    private MobInfection() {
    }

    // ---------------------------------------------------------------- 状態

    public static boolean isInfected(LivingEntity entity) {
        return entity.hasData(ModAttachments.SPORE_EXPOSURE) && entity.getData(ModAttachments.SPORE_EXPOSURE).infected;
    }

    /** 感染前に浴び続けている途中か(判定を細かくする)。 */
    public static boolean isCounting(LivingEntity entity) {
        if (!entity.hasData(ModAttachments.SPORE_EXPOSURE)) return false;
        SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
        return !ex.infected && ex.exposedTicks > 0;
    }

    /** この判定の間隔(tick)。数えている途中の Mob は countStepTicks、それ以外は {@code base}。 */
    public static int intervalFor(LivingEntity entity, int base) {
        MobInfectionSpec s = DesignData.sporeField().mobInfection();
        return s.enabled() && isCounting(entity) ? Math.min(base, s.countStepTicks()) : base;
    }

    /** 感染させる(コマンド・テスト・G1 の口からも)。測る Mob だけ。 */
    public static void infect(LivingEntity entity, long now) {
        if (!SporeExposureTracker.isMeasuredMob(entity) || !entity.isAlive()) return;
        SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
        if (ex.infected) return;
        ex.infected = true;
        ex.infectedGameTime = now;
        ex.exposedTicks = 0;
        infectedCount++;
    }

    // ---------------------------------------------------------------- 1 回の判定(SporeExposureTracker から)

    /**
     * 感染前の Mob の 1 回の判定ぶん: {@code effectivePercent}(マスクで防いだら 0)が境目以上なら浴びた tick を足し、startSeconds に届いたら感染。
     * 境目より下なら数え直す。数え始めの 1 回は countStepTicks ぶんだけ数える(前の判定から今までずっといたとは限らないので)。
     */
    public static void countExposure(LivingEntity entity, double effectivePercent, int interval, long now) {
        MobInfectionSpec s = DesignData.sporeField().mobInfection();
        if (!s.enabled()) return;
        SporeFieldTable t = DesignData.sporeField();
        if (effectivePercent >= t.exposureMinPercent() && effectivePercent > 0.0D) {
            SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
            ex.exposedTicks += ex.exposedTicks == 0 ? Math.min(interval, s.countStepTicks()) : interval;
            if (ex.exposedTicks >= Math.round(s.startSeconds() * 20.0D)) infect(entity, now);
        } else if (entity.hasData(ModAttachments.SPORE_EXPOSURE)) {
            entity.getData(ModAttachments.SPORE_EXPOSURE).exposedTicks = 0;
        }
    }

    /** 感染した Mob の体の中の胞子が育つ: 暴露量を growPerSecond × 秒 だけ上げる(感染の量で頭打ち)。 */
    public static void grow(LivingEntity entity, int interval) {
        MobInfectionSpec s = DesignData.sporeField().mobInfection();
        if (!s.enabled()) return;
        SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
        ex.dose = Math.min(DesignData.sporeField().infectDose(), ex.dose + s.growPerSecond() * interval / 20.0D);
    }

    /**
     * 判定の後: 逃げる向きを決め直す・感染した Mob から粒を出す。{@code rawPercent} は屋根の決まりを掛ける前の濃度
     * (屋根の下でも匂いで逃げる【仮】)。
     */
    public static void afterCheck(ServerLevel level, LivingEntity entity, double rawPercent, long now) {
        MobInfectionSpec s = DesignData.sporeField().mobInfection();
        if (!s.enabled()) return;
        boolean infected = isInfected(entity);
        if (s.fleeEnabled() && isFleer(entity)) {
            double start = s.fleeStartPercent() > 0.0D ? s.fleeStartPercent() : DesignData.sporeField().exposureMinPercent();
            if (infected || rawPercent >= start) {
                SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
                if (rawPercent >= start) ex.fleeUntil = now + s.fleeLingerTicks();
                updateFleeDirection(level, entity, ex);
            }
        }
        if (infected && s.particleCount() > 0) {
            level.sendParticles(ModParticles.SPORE_JET.get(), entity.getX(), entity.getEyeY() - 0.1D, entity.getZ(),
                    s.particleCount(), entity.getBbWidth() * 0.3D, 0.1D, entity.getBbWidth() * 0.3D, 0.02D);
        }
    }

    /** 逃げる側か: 動物・水の動物・村人と行商人【仮】(敵・ゴーレムは逃げない)。座っている飼い慣らした動物・つながれた Mob は逃げない。 */
    public static boolean isFleer(LivingEntity entity) {
        if (!(entity instanceof PathfinderMob mob)) return false;
        if (!(mob instanceof Animal || mob instanceof WaterAnimal || mob instanceof AbstractVillager)) return false;
        if (mob instanceof TamableAnimal tame && tame.isOrderedToSit()) return false;
        return !mob.isLeashed();
    }

    /** 逃げる途中か(感染していれば常に、そうでなければ濃い所を出てから少しの間)。 */
    public static boolean shouldFlee(LivingEntity entity, long now) {
        if (!entity.hasData(ModAttachments.SPORE_EXPOSURE)) return false;
        SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
        if (ex.fleeX == 0.0F && ex.fleeZ == 0.0F) return false;
        return ex.infected || now < ex.fleeUntil;
    }

    /**
     * 逃げる向き = まわり 8 チャンクの濃度(チャンク単位・キャッシュ付き。部屋の走査をしない)の重心から離れる向き。
     * 差が無ければ前の向きのまま。向きがまだ無ければ乱数で決める。
     */
    static void updateFleeDirection(ServerLevel level, LivingEntity entity, SporeExposure ex) {
        double x = entity.getX(), z = entity.getZ();
        int cx = chunkOf(x), cz = chunkOf(z);
        double dx = 0.0D, dz = 0.0D;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (ox == 0 && oz == 0) continue;
                double v = SporeConcentration.percentAt(level, new ChunkPos(cx + ox, cz + oz));
                if (v <= 0.0D) continue;
                double vx = x - ((cx + ox) * 16 + 8), vz = z - ((cz + oz) * 16 + 8);
                double len = Math.sqrt(vx * vx + vz * vz);
                if (len < 1.0E-3D) continue;
                dx += v * vx / len;
                dz += v * vz / len;
            }
        }
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 1.0E-3D) {
            ex.fleeX = (float) (dx / len);
            ex.fleeZ = (float) (dz / len);
        } else if (ex.fleeX == 0.0F && ex.fleeZ == 0.0F) {
            double a = entity.getRandom().nextDouble() * Math.PI * 2.0D;
            ex.fleeX = (float) Math.cos(a);
            ex.fleeZ = (float) Math.sin(a);
        }
    }

    private static int chunkOf(double blockCoord) {
        return net.minecraft.util.Mth.floor(blockCoord) >> 4;
    }

    // ---------------------------------------------------------------- 逃げる Goal を足す

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof PathfinderMob mob)) return;
        if (!(mob instanceof Animal || mob instanceof WaterAnimal || mob instanceof AbstractVillager)) return;
        if (!SporeExposureTracker.isMeasuredMob(mob)) return;
        if (mob.goalSelector.getAvailableGoals().stream().anyMatch(g -> g.getGoal() instanceof SporeFleeGoal)) return; // 区画の読み直し
        mob.goalSelector.addGoal(1, new SporeFleeGoal(mob));
    }

    // ---------------------------------------------------------------- 飛び火

    /** 次元ごとの「最後に飛び火したゲーム時刻」(チャンク → tick)。保存しない。 */
    private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> LAST_JUMP = new HashMap<>();
    /** 数え(/ov perf・テスト)。 */
    public static int infectedCount, jumpCount;
    /** 最後の飛び火の場所(テスト用)。 */
    public static volatile BlockPos lastJumpForTests;

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level) || !isInfected(entity)) return;
        jumpAt(level, entity.blockPosition());
    }

    /** 飛び火を起こす(同じチャンクの間隔の中なら何もしない)。起こしたら true。 */
    public static boolean jumpAt(ServerLevel level, BlockPos pos) {
        MobInfectionSpec s = DesignData.sporeField().mobInfection();
        if (!s.enabled()) return false;
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        long now = level.getGameTime();
        Long2LongOpenHashMap map = LAST_JUMP.computeIfAbsent(level.dimension(), k -> {
            Long2LongOpenHashMap m = new Long2LongOpenHashMap();
            m.defaultReturnValue(Long.MIN_VALUE);
            return m;
        });
        long last = map.get(key);
        if (last != Long.MIN_VALUE && now - last >= 0 && now - last < s.jumpCooldownTicks()) return false;
        if (map.size() > 4096) map.values().removeIf(v -> now - v >= s.jumpCooldownTicks() || now < v);
        map.put(key, now);
        SporeFieldServer.depositPoint(level, pos, s.jumpDeposit());
        SporeFieldServer.emitPoint(level, pos, s.jumpEmit());
        level.sendParticles(ModParticles.SPORE_BURST_CLOUD.get(), pos.getX() + 0.5D, pos.getY() + 0.6D, pos.getZ() + 0.5D,
                4, 0.4D, 0.2D, 0.4D, 0.0D);
        jumpCount++;
        lastJumpForTests = pos.immutable();
        return true;
    }

    /** テスト用: 飛び火の間隔の記録を消す。 */
    public static void clearJumpCooldownsForTests() {
        LAST_JUMP.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LAST_JUMP.clear();
        infectedCount = 0;
        jumpCount = 0;
        lastJumpForTests = null;
    }
}
