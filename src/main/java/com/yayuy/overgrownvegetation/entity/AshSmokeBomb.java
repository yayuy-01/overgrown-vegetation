package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;

/**
 * 灰煙玉(2026-10-16、⑪)。焼けた荒地の灰の煙で匂いを消し、周りの敵がしばらく見失う。
 * 仮決め: 当たった所から半径 {@link #RADIUS} の Mob は狙いを外し、{@link #CONFUSE_TICKS}(10 秒)の間だれも狙えない / 煙は 10 秒残る(見た目だけ)/
 * 灰 3 + 糸で 2 つ。見失わせ方は {@code Mob#setTarget} の手前で止める 1 か所({@link LivingChangeTargetEvent})。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public class AshSmokeBomb extends ThrowableItemProjectile {

    public static final double RADIUS = 5.0D;
    public static final int CONFUSE_TICKS = 200;
    private static final String KEY = "OvSmokeUntil";

    public AshSmokeBomb(EntityType<? extends AshSmokeBomb> type, Level level) {
        super(type, level);
    }

    public AshSmokeBomb(Level level, LivingEntity thrower) {
        super(ModEntities.ASH_SMOKE_BOMB.get(), thrower, level);
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.ASH_SMOKE_BOMB.get();
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (level() instanceof ServerLevel server) {
            burst(server, result.getLocation().x, result.getLocation().y, result.getLocation().z);
            discard();
        }
    }

    /** 煙を広げ、周りの Mob を見失わせる。見失わせた数を返す。 */
    public static int burst(ServerLevel level, double x, double y, double z) {
        AreaEffectCloud cloud = new AreaEffectCloud(level, x, y, z);
        cloud.setRadius(3.5F);
        cloud.setDuration(CONFUSE_TICKS);
        cloud.setRadiusPerTick(0.0F);
        cloud.setParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE);
        level.addFreshEntity(cloud);
        level.sendParticles(ParticleTypes.ASH, x, y + 0.5D, z, 60, 2.0D, 1.0D, 2.0D, 0.02D);
        level.playSound(null, x, y, z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 1.0F, 0.7F);
        long until = level.getGameTime() + CONFUSE_TICKS;
        int n = 0;
        AABB box = new AABB(x, y, z, x, y, z).inflate(RADIUS);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box)) {
            mob.setTarget(null);
            // 狙いの Goal は狙いが空でも覚えている相手を戻す(TargetGoal#canContinueToUse)ので、走っている狙いの Goal ごと止める
            mob.targetSelector.getAvailableGoals().forEach(goal -> {
                if (goal.isRunning()) {
                    goal.stop();
                }
            });
            mob.getNavigation().stop();
            mob.getPersistentData().putLong(KEY, until);
            n++;
        }
        // 「香ばしいにおい」(光る実を食べると付く、植物側の Mob に見つかりやすくなる)も煙で消える
        for (net.minecraft.world.entity.player.Player player : level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class, box)) {
            player.removeEffect(com.yayuy.overgrownvegetation.registry.ModMobEffects.SAVORY_SCENT);
        }
        return n;
    }

    /** 煙で見失っている間か。 */
    public static boolean confused(LivingEntity mob) {
        return mob.level().getGameTime() < mob.getPersistentData().getLong(KEY);
    }

    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() != null && confused(event.getEntity())) {
            event.setCanceled(true);
        }
    }
}
