package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * <b>地脈の杖</b>(段 3 の遠距離。2026-11-18 決定 19)。右クリックで黄緑の光の弾({@link LeyBoltEntity})を撃つ。1 発 {@code cost} FE・
 * {@code cooldownTicks} おき。結晶が足りなければ撃てない。
 */
public class LeyStaffItem extends CrystalToolItem {

    public LeyStaffItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        GearTierTable.LeyStaff s = DesignData.gearTiers().leyStaff;
        if (LeyCrystal.stored(stack) < s.cost && !player.getAbilities().instabuild) {
            player.playSound(SoundEvents.DISPENSER_FAIL, 0.6F, 1.6F);
            return InteractionResultHolder.fail(stack);
        }
        if (!level.isClientSide) {
            fire(level, player, stack);
            stack.hurtAndBreak(1, player, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
        }
        player.getCooldowns().addCooldown(this, s.cooldownTicks);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** 1 発撃つ(GameTest からも)。撃てたら弾を返す。 */
    public static LeyBoltEntity fire(Level level, Player player, ItemStack stack) {
        GearTierTable.LeyStaff s = DesignData.gearTiers().leyStaff;
        if (!LeyCrystal.spend(player, stack, s.cost)) return null;
        LeyBoltEntity bolt = new LeyBoltEntity(level, player);
        bolt.setPos(player.getX(), player.getEyeY() - 0.1D, player.getZ());
        bolt.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, s.speed, 0.0F);
        level.addFreshEntity(bolt);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.0F, 1.6F);
        return bolt;
    }
}
