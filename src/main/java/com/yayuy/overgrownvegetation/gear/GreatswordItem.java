package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Comparator;
import java.util.List;

/**
 * <b>両手剣</b>(段 2 の近接。2026-11-17 決定 18)。大きく薙ぐ: 振りがたまった一撃で、狙った相手のほかに前の半円({@code sweepRange}・{@code sweepMinDot})の
 * 数体({@code sweepMaxTargets})を攻撃の {@code sweepDamageRatio} 倍で斬る。振りは遅い。バニラの剣の薙ぎ払い(狙った相手のそば 1 ブロック)は使わない。
 *
 * <p><b>両手持ち</b>: 利き手に持っている間、もう片方の手の物は使えない(右クリック・ブロックや Mob に使う・使い始め)。前の版の
 * {@code TwoHandedRules}(10-10 ユーザー指定「左手のアイテムを使えないようにする」、10-30 に退避)を読んで同じ決まりで書き直した。
 * 持っているだけで効く物(不死のトーテムなど)は止めない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public class GreatswordItem extends SwordItem {

    public GreatswordItem(Tier tier, Properties properties) {
        super(tier, properties);
    }

    @Override
    public boolean canPerformAction(ItemStack stack, ItemAbility ability) {
        return ability != ItemAbilities.SWORD_SWEEP && super.canPerformAction(stack, ability);
    }

    // ---- 薙ぎ ----

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        Player player = event.getEntity();
        if (!(player.level() instanceof ServerLevel) || !(player.getMainHandItem().getItem() instanceof GreatswordItem)) return;
        if (player.getAttackStrengthScale(0.5F) < DesignData.gearTiers().greatsword.sweepMinCharge) return;
        sweep(player, event.getTarget());
    }

    /** 前の半円の相手を斬る(GameTest からも)。斬った数を返す({@code target} は数えない)。 */
    public static int sweep(Player player, Entity target) {
        GearTierTable.Greatsword g = DesignData.gearTiers().greatsword;
        if (g.sweepMaxTargets <= 0 || g.sweepRange <= 0.0D) return 0;
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        flat = flat.lengthSqr() < 1.0E-6D ? Vec3.directionFromRotation(0.0F, player.getYRot()) : flat.normalize();
        final Vec3 forward = flat;
        Vec3 eye = player.position();
        double r = g.sweepRange;
        List<LivingEntity> victims = player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(r, 1.0D, r), e -> {
            if (e == player || e == target || !e.isAlive() || e.isSpectator() || e instanceof ArmorStand) return false;
            if (e instanceof Player || player.isAlliedTo(e)) return false;
            if (e instanceof OwnableEntity own && player.getUUID().equals(own.getOwnerUUID())) return false;
            Vec3 to = e.position().subtract(eye);
            Vec3 toFlat = new Vec3(to.x, 0.0D, to.z);
            if (toFlat.lengthSqr() > r * r) return false;
            return toFlat.lengthSqr() < 1.0E-6D || toFlat.normalize().dot(forward) >= g.sweepMinDot;
        });
        victims.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player)));
        float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) * g.sweepDamageRatio);
        int n = 0;
        for (LivingEntity e : victims) {
            if (n >= g.sweepMaxTargets) break;
            if (e.hurt(player.damageSources().playerAttack(player), damage)) {
                e.knockback(0.5D, -forward.x, -forward.z);
                n++;
            }
        }
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, player.getSoundSource(), 1.0F, 0.8F);
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.SWEEP_ATTACK,
                    player.getX() + forward.x * 1.5D, player.getY(0.5D), player.getZ() + forward.z * 1.5D, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        return n;
    }

    // ---- 両手持ち ----

    /** 利き手に両手剣があり、もう片方の手に何か持っているか(= もう片方を止める)。 */
    public static boolean blocksOffhand(Player player) {
        return player.getMainHandItem().getItem() instanceof GreatswordItem && !player.getOffhandItem().isEmpty();
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getHand() == InteractionHand.OFF_HAND && blocksOffhand(event.getEntity())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            tell(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() == InteractionHand.OFF_HAND && blocksOffhand(event.getEntity())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            tell(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() == InteractionHand.OFF_HAND && blocksOffhand(event.getEntity())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getHand() == InteractionHand.OFF_HAND && blocksOffhand(event.getEntity())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onUseStart(LivingEntityUseItemEvent.Start event) {
        if (event.getEntity() instanceof Player player && event.getItem() == player.getOffhandItem() && blocksOffhand(player)) {
            event.setCanceled(true);
        }
    }

    /** 使っている途中で両手剣に持ち替えたら止める(盾を構えたままなど)。 */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.OFF_HAND && player.getMainHandItem().getItem() instanceof GreatswordItem) {
            player.stopUsingItem();
        }
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        if (event.getItemStack().getItem() instanceof GreatswordItem && !event.getToolTip().isEmpty()) {
            event.getToolTip().add(1, Component.translatable("item.overgrown_vegetation.two_handed.tooltip").withStyle(ChatFormatting.GRAY));
        }
    }

    private static void tell(Player player) {
        if (player instanceof ServerPlayer server) {
            server.displayClientMessage(Component.translatable("message.overgrown_vegetation.two_handed"), true);
        }
    }
}
