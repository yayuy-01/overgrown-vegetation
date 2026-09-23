package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.BlightFernPlacement;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * グループ 1 の Mob({@code #overgrown_vegetation:group_1}、寄生された動物)は、繁茂系バイオーム
 * (過繁茂 / 繁茂、密林 / 湿地 / 菌湿地帯、海・川を含む {@link ModBiomes#isOvergrownLike})の中で
 * 合計 {@code group1OvergrownDeathTicks}(既定 3 分)を過ごすとその場で死に、死んだ場所に汚染シダが湧く。
 *
 * <p>滞在時間はエンティティの永続データ({@code getPersistentData()})に積むので、ワールドを閉じても
 * 持ち越される。既定では外に出ても減らない(一度入った時間はそのまま残る)。
 * {@code group1OvergrownDeathDecay} を true にすると、外にいる間は同じ速さで減っていく(連続滞在の判定に近づく)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class Group1OvergrownExposure {

    /** 永続データのキー(繁茂系バイオーム内の累計 tick)。 */
    private static final String KEY = "overgrown_vegetation_overgrown_ticks";
    /** 判定間隔(tick)。毎 tick 走らせる必要はない。 */
    private static final int INTERVAL = 20;

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity entity) || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (Math.floorMod(entity.tickCount + entity.getId(), INTERVAL) != 0) {
            return;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.group1OvergrownDeath.get() || !entity.isAlive() || !entity.getType().is(ModTags.GROUP_1)) {
            return;
        }

        boolean inside = ModBiomes.isOvergrownLike(level.getBiome(entity.blockPosition()));
        CompoundTag data = entity.getPersistentData();
        int ticks = data.getInt(KEY);
        if (inside) {
            ticks += INTERVAL;
        } else if (cfg.group1OvergrownDeathDecay.get()) {
            ticks = Math.max(0, ticks - INTERVAL);
        } else {
            return; // 外にいる間は何も変わらない。
        }
        data.putInt(KEY, ticks);

        if (!inside || ticks < cfg.group1OvergrownDeathTicks.get()) {
            return;
        }

        // 限界。その場で死に、死骸から汚染シダが芽吹く。
        BlockPos pos = entity.blockPosition();
        entity.kill();
        if (entity.isDeadOrDying() || !entity.isAlive()) {
            BlightFernPlacement.spawn(level, pos, MobSpawnType.MOB_SUMMONED);
        }
    }

    private Group1OvergrownExposure() {
    }
}
