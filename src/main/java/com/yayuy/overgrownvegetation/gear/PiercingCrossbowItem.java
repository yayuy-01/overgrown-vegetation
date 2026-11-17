package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * <b>貫きのクロスボウ</b>(段 2 の遠距離。2026-11-17 決定 18)。太い矢が一列に並んだ敵を貫く({@code pierce})・ダメージが重い({@code damageMultiplier})。
 * 装填は遅い({@code chargeTicks}。バニラ 25)。速装填のエンチャントは同じ割合で縮む。
 *
 * <p>バニラの装填の長さは静的な {@code getChargeDuration} で決め打ちなので、使い終わり({@link #releaseUsing})を自前の長さで書き直した。
 * 貫きは矢の保存データ({@code PierceLevel})を書き換えて付ける(矢の setPierceLevel は private。Mixin を増やさない)。
 */
public class PiercingCrossbowItem extends CrossbowItem {

    public PiercingCrossbowItem(Properties properties) {
        super(properties);
    }

    /** 装填にかかる tick(速装填のエンチャントを同じ割合で掛ける)。クライアントの引きの絵もこれを読む。 */
    public static int chargeTicks(ItemStack stack, LivingEntity shooter) {
        int base = Math.max(1, DesignData.gearTiers().crossbow.chargeTicks);
        int vanilla = CrossbowItem.getChargeDuration(stack, shooter); // 速装填込みのバニラの長さ(素は 25)
        return Math.max(1, Math.round(base * vanilla / 25.0F));
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return chargeTicks(stack, entity) + 3;
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity shooter, int timeLeft) {
        int held = getUseDuration(stack, shooter) - timeLeft;
        if (held >= chargeTicks(stack, shooter) && !isCharged(stack)) {
            List<ItemStack> list = draw(stack, shooter.getProjectile(stack), shooter);
            if (!list.isEmpty()) {
                stack.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(list));
                level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(), SoundEvents.CROSSBOW_LOADING_END.value(),
                        shooter.getSoundSource(), 1.0F, 1.0F / (level.getRandom().nextFloat() * 0.5F + 1.0F) + 0.2F);
            }
        }
    }

    @Override
    protected Projectile createProjectile(Level level, LivingEntity shooter, ItemStack weapon, ItemStack ammo, boolean isCrit) {
        Projectile p = super.createProjectile(level, shooter, weapon, ammo, isCrit);
        if (p instanceof AbstractArrow arrow) {
            heavyBolt(arrow);
        }
        return p;
    }

    /** 太い矢にする: ダメージを重く・貫く数を足す(貫通のエンチャントと足し合わせ、127 で頭打ち)。GameTest からも。 */
    public static void heavyBolt(AbstractArrow arrow) {
        GearTierTable.Crossbow c = DesignData.gearTiers().crossbow;
        arrow.setBaseDamage(arrow.getBaseDamage() * c.damageMultiplier);
        int pierce = Math.min(127, arrow.getPierceLevel() + Math.max(0, c.pierce));
        if (pierce != arrow.getPierceLevel()) {
            CompoundTag tag = new CompoundTag();
            arrow.addAdditionalSaveData(tag);
            tag.putByte("PierceLevel", (byte) pierce);
            arrow.readAdditionalSaveData(tag);
        }
    }
}
