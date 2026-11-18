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
 *   <li>扇の中の汚染の下草・蔦・胞子嚢(山刀で刈れる物 {@code machete_clearable})と汚染の葉を焼き消す(落とし物なし)。</li>
 *   <li>扇の当たった表の汚染の土は燻る灰ブロック、覆いの記録ははがれる(10-08〜10 の火の決まりを 11-18 夜に引き継いだ。{@code corruption/pushback})。</li>
 *   <li>焼いたマスに {@code fireChance} でふつうの火を付ける(10-09 朝「火を広範囲につける道具」)。</li>
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

    /** 焼くと燻る灰ブロックになる汚染の地面(10-08 夜のユーザー指定: 過繁茂のブロック・草・土。泥・砂利は今は無い)。 */
    public static boolean scorches(net.minecraft.world.level.block.state.BlockState s) {
        return s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_GRASS_BLOCK.get())
                || s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_DIRT.get())
                || s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_BLOCK.get());
    }

    /** どれかの面が空気か植物(炎が届く)に面しているか。 */
    private static boolean exposed(ServerLevel level, BlockPos p) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            net.minecraft.world.level.block.state.BlockState n = level.getBlockState(p.relative(d));
            if (n.isAir() || !n.blocksMotion()) return true;
        }
        return false;
    }

    /** 1 回焼く(GameTest からも)。焼いた植物・地面・覆いの数と燃やした相手の数を返す。 */
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
                if (burned.contains(p)) continue;
                net.minecraft.world.level.block.state.BlockState s = level.getBlockState(p);
                boolean plant = s.is(MacheteItem.CLEARABLE) || s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_LEAVES.get());
                boolean ground = !plant && scorches(s);
                boolean covered = !plant && !ground && !s.isAir()
                        && com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCovered(level, p);
                if (!plant && !ground && !covered) continue;
                if (!inCone(eye, look, Vec3.atCenterOf(p), b.range, cosHalf) || !level.mayInteract(player, p)) continue;
                BlockPos fixed = p.immutable();
                if (!plant && !exposed(level, fixed)) continue; // 地面と覆いは、炎の当たる表のマスだけ(地中まで焼かない)
                if (plant) {
                    level.setBlock(fixed, Blocks.AIR.defaultBlockState(), 3);
                } else if (ground) {
                    // 汚染の土 → 燻る灰ブロック(10-08〜09 のユーザー指定を 11-18 夜に筒へ引き継いだ)
                    level.setBlock(fixed, com.yayuy.overgrownvegetation.corruption.pushback.SmolderingAshBlock.from(s), 3);
                } else {
                    com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.uncover(level, fixed); // 覆いが焼け落ちて元のブロック
                }
                com.yayuy.overgrownvegetation.corruption.pushback.LedgerShrink.touch(level, fixed,
                        com.yayuy.overgrownvegetation.corruption.pushback.LedgerShrink.Cause.FIRE);
                burned.add(fixed);
                // ふつうの火を付ける(10-09 朝の決まり「ただ火を広範囲につける道具」を 11-18 夜に筒へ。Q2-2 了承)。焼けた地面の上か、焼け落ちた植物のマスに
                BlockPos firePos = plant ? fixed : fixed.above();
                if (level.random.nextFloat() < b.fireChance && level.getBlockState(firePos).isAir()
                        && net.minecraft.world.level.block.BaseFireBlock.canBePlacedAt(level, firePos, net.minecraft.core.Direction.UP)) {
                    level.setBlock(firePos, net.minecraft.world.level.block.BaseFireBlock.getState(level, firePos), 11);
                }
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
