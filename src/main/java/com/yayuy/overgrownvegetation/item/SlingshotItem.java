package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.entity.SlingshotShot;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;

/**
 * パチンコ(2026-10-16、⑯。ユーザー指定「弓のように引いて放つ」)。弾は小石・炸裂弾(⑰)・粘り弾。弓より弱く、飛ぶ距離も短い(仮決め: 初速は弓の 2/3)。
 */
public class SlingshotItem extends ProjectileWeaponItem {

    public static final Predicate<ItemStack> AMMO = s -> s.is(ModItems.PEBBLE.get()) || s.is(ModItems.BURST_SHOT.get())
            || s.is(ModItems.STICKY_SHOT.get());

    public SlingshotItem(Properties properties) {
        super(properties);
    }

    @Override
    public Predicate<ItemStack> getAllSupportedProjectiles() {
        return AMMO;
    }

    @Override
    public int getDefaultProjectileRange() {
        return 10;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.getProjectile(stack).isEmpty() && !player.hasInfiniteMaterials()) {
            return InteractionResultHolder.fail(stack);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (!(entity instanceof Player player)) {
            return;
        }
        ItemStack ammo = player.getProjectile(stack);
        if (ammo.isEmpty()) {
            return;
        }
        float power = BowItem.getPowerForTime(getUseDuration(stack, entity) - timeLeft);
        if (power < 0.1F) {
            return;
        }
        List<ItemStack> shots = draw(stack, ammo, player);
        if (level instanceof ServerLevel server && !shots.isEmpty()) {
            shoot(server, player, player.getUsedItemHand(), stack, shots, power * 2.0F, 1.5F, power == 1.0F, null);
        }
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.CROSSBOW_SHOOT, SoundSource.PLAYERS, 0.6F,
                1.4F + level.random.nextFloat() * 0.3F);
        player.awardStat(Stats.ITEM_USED.get(this));
    }

    @Override
    protected Projectile createProjectile(Level level, LivingEntity shooter, ItemStack weapon, ItemStack ammo, boolean crit) {
        SlingshotShot shot = new SlingshotShot(level, shooter);
        shot.setItem(ammo.copyWithCount(1));
        return shot;
    }

    @Override
    protected void shootProjectile(LivingEntity shooter, Projectile projectile, int index, float velocity, float inaccuracy, float angle,
                                   @Nullable LivingEntity target) {
        if (projectile instanceof SlingshotShot shot) {
            shot.setPower(velocity / 2.0F);
        }
        projectile.shootFromRotation(shooter, shooter.getXRot(), shooter.getYRot() + angle, 0.0F, velocity, inaccuracy);
    }
}
