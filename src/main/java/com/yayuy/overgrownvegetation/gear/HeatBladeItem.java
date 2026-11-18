package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * <b>熱で焼き切る刃</b>(段 3 の近接。2026-11-18 決定 19)。
 * <ul>
 *   <li>当たると {@code igniteSeconds} 燃やし、植物側には {@code plantDamageMultiplier} 倍(1 回 {@code hitCost} FE)。</li>
 *   <li>蔦・下草・胞子嚢(山刀で刈れる物 {@code machete_clearable})を速く刈り、刈った所とつながった同じ植物を {@code burnMax} まで焼き消す
 *   (落とし物なし・1 回 {@code burnCost} FE)。火のブロックは置かない。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public class HeatBladeItem extends CrystalSwordItem {

    /** 今の一撃で熱が乗った相手(攻撃の知らせとダメージの知らせの間だけ。サーバーの 1 本の流れ)。 */
    private static Entity heatedTarget;
    private static boolean burning;

    public HeatBladeItem(Tier tier, Properties properties) {
        super(tier, properties);
    }

    @Override
    public float getDestroySpeed(ItemStack stack, BlockState state) {
        return state.is(MacheteItem.CLEARABLE) ? DesignData.gearTiers().heatBlade.clearSpeed : super.getDestroySpeed(stack, state);
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        Player player = event.getEntity();
        ItemStack stack = player.getMainHandItem();
        heatedTarget = null;
        if (!(player.level() instanceof ServerLevel) || !(stack.getItem() instanceof HeatBladeItem)) return;
        GearTierTable.HeatBlade h = DesignData.gearTiers().heatBlade;
        Entity target = event.getTarget();
        if (target instanceof PartEntity<?> part) target = part.getParent();
        if (!(target instanceof LivingEntity living) || !LeyCrystal.spend(player, stack, h.hitCost)) return;
        living.igniteForSeconds(h.igniteSeconds);
        heatedTarget = living;
        ((ServerLevel) player.level()).sendParticles(ParticleTypes.FLAME, living.getX(), living.getY(0.5D), living.getZ(), 10, 0.3D, 0.4D, 0.3D, 0.02D);
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (heatedTarget == null || event.getEntity() != heatedTarget) return;
        heatedTarget = null;
        if (event.getSource().getEntity() instanceof Player && PlantSideRules.isPlantSide(event.getEntity())) {
            event.setAmount((float) (event.getAmount() * DesignData.gearTiers().heatBlade.plantDamageMultiplier));
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (burning || !(event.getPlayer() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) return;
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof HeatBladeItem) || !event.getState().is(MacheteItem.CLEARABLE)) return;
        if (!LeyCrystal.spend(player, stack, DesignData.gearTiers().heatBlade.burnCost)) return;
        burnConnected(level, event.getPos(), player);
    }

    /** {@code origin} とつながった刈れる植物を焼き消す(落とし物なし)。焼いた数を返す({@code origin} 自身は数えない)。GameTest からも。 */
    public static int burnConnected(ServerLevel level, BlockPos origin, Player player) {
        int max = DesignData.gearTiers().heatBlade.burnMax;
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(origin);
        for (Direction d : Direction.values()) queue.add(origin.relative(d));
        int n = 0;
        burning = true;
        try {
            while (!queue.isEmpty() && n < max) {
                BlockPos p = queue.poll();
                if (!seen.add(p) || !level.isLoaded(p) || p.distManhattan(origin) > 8) continue;
                if (!level.getBlockState(p).is(MacheteItem.CLEARABLE)) continue;
                if (player != null && !level.mayInteract(player, p)) continue;
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                level.sendParticles(ParticleTypes.FLAME, p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D, 3, 0.2D, 0.2D, 0.2D, 0.01D);
                level.sendParticles(ParticleTypes.SMOKE, p.getX() + 0.5D, p.getY() + 0.6D, p.getZ() + 0.5D, 2, 0.2D, 0.2D, 0.2D, 0.01D);
                n++;
                for (Direction d : Direction.values()) queue.add(p.relative(d));
            }
        } finally {
            burning = false;
        }
        if (n > 0) level.playSound(null, origin, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.5F, 1.4F);
        return n;
    }
}
