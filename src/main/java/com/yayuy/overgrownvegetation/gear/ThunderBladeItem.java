package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>雷を纏う刃</b>(段 3 の近接。2026-11-18 決定 19)。振りがたまった一撃で、電撃が狙った相手から近くの敵へ {@code jumps} 回まで飛び移る
 * ({@code jumpRange} 以内のいちばん近い敵。ダメージは {@code firstDamage} から {@code falloff} 倍ずつ)。1 回 {@code cost} FE。
 * 飛び移る先は敵(敵対 Mob・植物側)だけ。プレイヤー・飼っている動物・防具立ては飛ばさない。結晶が足りなければ普通の剣。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public class ThunderBladeItem extends CrystalSwordItem {

    private static final DustParticleOptions SPARK = new DustParticleOptions(new Vector3f(0.75F, 1.0F, 0.4F), 0.8F);

    public ThunderBladeItem(Tier tier, Properties properties) {
        super(tier, properties);
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        Player player = event.getEntity();
        ItemStack stack = player.getMainHandItem();
        if (!(player.level() instanceof ServerLevel) || !(stack.getItem() instanceof ThunderBladeItem)) return;
        GearTierTable.ThunderBlade t = DesignData.gearTiers().thunderBlade;
        if (player.getAttackStrengthScale(0.5F) < t.minCharge) return;
        Entity target = event.getTarget();
        if (target instanceof PartEntity<?> part) target = part.getParent();
        if (!(target instanceof LivingEntity first) || !LeyCrystal.spend(player, stack, t.cost)) return;
        chain(player, first);
    }

    /** {@code first}(狙った相手)から電撃を飛び移らせる(GameTest からも)。当てた数を返す。 */
    public static int chain(Player player, LivingEntity first) {
        GearTierTable.ThunderBlade t = DesignData.gearTiers().thunderBlade;
        ServerLevel level = (ServerLevel) player.level();
        Set<Entity> hit = new HashSet<>();
        hit.add(first);
        LivingEntity from = first;
        double damage = t.firstDamage;
        int n = 0;
        for (int i = 0; i < t.jumps; i++) {
            final LivingEntity origin = from;
            List<LivingEntity> near = level.getEntitiesOfClass(LivingEntity.class, origin.getBoundingBox().inflate(t.jumpRange),
                    e -> !hit.contains(e) && isFoe(player, e) && e.distanceToSqr(origin) <= t.jumpRange * t.jumpRange);
            if (near.isEmpty()) break;
            near.sort((a, b) -> Double.compare(a.distanceToSqr(origin), b.distanceToSqr(origin)));
            LivingEntity next = near.get(0);
            arc(level, origin.position().add(0, origin.getBbHeight() * 0.6D, 0), next.position().add(0, next.getBbHeight() * 0.6D, 0));
            if (next.hurt(level.damageSources().indirectMagic(player, player), (float) damage)) n++;
            hit.add(next);
            from = next;
            damage *= t.falloff;
        }
        level.playSound(null, first.getX(), first.getY(), first.getZ(), SoundEvents.TRIDENT_THUNDER.value(), player.getSoundSource(), 0.4F, 1.8F);
        return n;
    }

    static boolean isFoe(Player player, LivingEntity e) {
        if (e == player || !e.isAlive() || e.isSpectator() || e instanceof Player || e instanceof ArmorStand) return false;
        if (player.isAlliedTo(e)) return false;
        if (e instanceof OwnableEntity own && own.getOwnerUUID() != null) return false;
        return e instanceof Enemy || PlantSideRules.isPlantSide(e);
    }

    private static void arc(ServerLevel level, Vec3 a, Vec3 b) {
        int steps = Math.max(3, (int) (a.distanceTo(b) * 3));
        for (int i = 0; i <= steps; i++) {
            Vec3 p = a.lerp(b, i / (double) steps);
            level.sendParticles(SPARK, p.x, p.y, p.z, 1, 0.05D, 0.05D, 0.05D, 0.0D);
        }
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, b.x, b.y, b.z, 8, 0.2D, 0.2D, 0.2D, 0.1D);
    }
}
