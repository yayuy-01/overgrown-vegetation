package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * <b>胞子を焼く筒</b>(段 3 の遠距離・押し返す道具を兼ねる。2026-11-18 決定 16・19)。押している間、{@code pulseTicks} ごとに
 * 前の扇形(半角 {@code halfAngle}・{@code range} ブロック)を焼く:
 * <ul>
 *   <li>扇の中の相手(撃つ本人・仲間・プレイヤーを除く)は燃え、{@code damage} を受ける。</li>
 *   <li>扇の中の汚染の下草・蔦・胞子嚢(山刀で刈れる物 {@code machete_clearable})を焼き消す(落とし物なし)。火のブロックは置かない。</li>
 * </ul>
 * 1 回 {@code cost} FE。結晶が切れたら止まる。
 */
public class SporeBurnerItem extends CrystalToolItem {

    public SporeBurnerItem(Properties properties) {
        super(properties);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (LeyCrystal.stored(stack) < DesignData.gearTiers().sporeBurner.cost && !player.getAbilities().instabuild) {
            player.playSound(SoundEvents.DISPENSER_FAIL, 0.6F, 1.6F);
            return InteractionResultHolder.fail(stack);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void onUseTick(Level level, LivingEntity user, ItemStack stack, int remaining) {
        GearTierTable.SporeBurner b = DesignData.gearTiers().sporeBurner;
        if (!(level instanceof ServerLevel server) || !(user instanceof Player player)) return;
        if ((getUseDuration(stack, user) - remaining) % Math.max(1, b.pulseTicks) != 0) return;
        if (!LeyCrystal.spend(player, stack, b.cost)) {
            player.stopUsingItem();
            return;
        }
        pulse(server, player);
        if (player.tickCount % 20 == 0) {
            stack.hurtAndBreak(1, player, player.getUsedItemHand() == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
        }
    }

    /** 扇の向き({@code look} は水平・上下とも見る向き)の中にあるか。 */
    static boolean inCone(Vec3 eye, Vec3 look, Vec3 p, double range, double cosHalf) {
        Vec3 to = p.subtract(eye);
        double len = to.length();
        if (len > range || len < 1.0E-4D) return len < 1.0E-4D;
        return to.scale(1.0D / len).dot(look) >= cosHalf;
    }

    /** 1 回焼く(GameTest からも)。焼いた植物の数と燃やした相手の数を返す。 */
    public static int[] pulse(ServerLevel level, Player player) {
        GearTierTable.SporeBurner b = DesignData.gearTiers().sporeBurner;
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double cosHalf = Math.cos(Math.toRadians(b.halfAngle));
        int mobs = 0;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(b.range),
                e -> e != player && e.isAlive() && !(e instanceof Player) && !player.isAlliedTo(e))) {
            if (!inCone(eye, look, e.getBoundingBox().getCenter(), b.range + e.getBbWidth() * 0.5D, cosHalf)) continue;
            e.igniteForSeconds(b.igniteSeconds);
            if (e.hurt(level.damageSources().indirectMagic(player, player), (float) b.damage)) mobs++;
        }
        // 扇の中の植物: 見る向きに沿って 1 ブロックおきに進み、その距離の扇の幅の箱を見る(1 回およそ 2000 マス)
        Set<BlockPos> burned = new HashSet<>();
        for (double d = 1.0D; d <= b.range; d += 1.0D) {
            double r = Math.tan(Math.toRadians(b.halfAngle)) * d;
            Vec3 c = eye.add(look.scale(d));
            int ri = (int) Math.ceil(r);
            BlockPos center = BlockPos.containing(c);
            for (BlockPos p : BlockPos.betweenClosed(center.offset(-ri, -ri, -ri), center.offset(ri, ri, ri))) {
                if (burned.contains(p) || !level.getBlockState(p).is(MacheteItem.CLEARABLE)) continue;
                if (!inCone(eye, look, Vec3.atCenterOf(p), b.range, cosHalf) || !level.mayInteract(player, p)) continue;
                BlockPos fixed = p.immutable();
                level.setBlock(fixed, Blocks.AIR.defaultBlockState(), 3);
                burned.add(fixed);
            }
        }
        for (int i = 0; i < 6; i++) {
            Vec3 v = look.add((level.random.nextDouble() - 0.5D) * 0.5D, (level.random.nextDouble() - 0.5D) * 0.3D, (level.random.nextDouble() - 0.5D) * 0.5D)
                    .normalize().scale(0.4D);
            Vec3 p = eye.add(look.scale(0.8D)).add(0, -0.2D, 0);
            level.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 0, v.x, v.y, v.z, 1.0D);
        }
        for (BlockPos p : burned) {
            level.sendParticles(ParticleTypes.SMOKE, p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D, 2, 0.2D, 0.2D, 0.2D, 0.01D);
        }
        if (player.tickCount % 8 == 0) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.35F, 0.7F);
        }
        return new int[]{burned.size(), mobs};
    }
}
