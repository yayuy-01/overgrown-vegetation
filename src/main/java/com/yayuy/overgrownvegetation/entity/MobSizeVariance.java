package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStat;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * この MOD の全 Mob に「湧いた個体ごとの体格の個体差」を与える。
 *
 * <p>実効 {@code SCALE} = {@code mobs.json の scale}（固定の体格倍率、既定 1.0）×
 * ランダム({@code scaleMin}〜{@code scaleMax}、既定はどちらも 1.0 = 個体差なし)。
 *
 * <ul>
 *     <li>初めてレベルに参加した個体には倍率を抽選し、確定した実効 SCALE を
 *     {@link ModAttachments#SIZE_VARIANCE}(serialize あり)へ記録する。</li>
 *     <li>ワールド再読み込みなどで再参加した個体は記録済みの値をそのまま適用するので、
 *     体格が抽選し直されない。</li>
 * </ul>
 *
 * <p>{@code scaleMin}/{@code scaleMax} は {@code config/overgrown_vegetation/mobs.json} で
 * 種族ごとに設定でき、mod マネージャーからも編集できる。既定ではファランギオターブスのみ
 * 0.6〜1.0、他は 1.0〜1.0。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class MobSizeVariance {

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        ResourceLocation id = EntityType.getKey(mob.getType());
        if (!id.getNamespace().equals(OvergrownVegetation.MODID)) {
            return;
        }
        AttributeInstance scaleAttr = mob.getAttribute(Attributes.SCALE);
        if (scaleAttr == null) {
            return;
        }

        float stored = mob.getData(ModAttachments.SIZE_VARIANCE);
        float effectiveScale;
        if (stored > 0.0F) {
            effectiveScale = stored;
        } else {
            MobStat stat = DesignData.mobs().forId(id.getPath());
            double lo = stat.scaleMin();
            double hi = stat.scaleMax();
            double variance = lo + (hi > lo ? mob.getRandom().nextDouble() * (hi - lo) : 0.0D);
            effectiveScale = (float) (supplierBaseScale(mob.getType()) * variance);
            mob.setData(ModAttachments.SIZE_VARIANCE, effectiveScale);
        }

        if (Math.abs(scaleAttr.getBaseValue() - effectiveScale) > 1.0e-4) {
            scaleAttr.setBaseValue(effectiveScale);
            mob.refreshDimensions();
        }
    }

    /** 登録済みの属性サプライヤから、この種族の SCALE 基礎値(個体差を掛ける前の値)を得る。 */
    private static double supplierBaseScale(EntityType<?> type) {
        @SuppressWarnings("unchecked")
        EntityType<? extends LivingEntity> living = (EntityType<? extends LivingEntity>) type;
        AttributeSupplier supplier = DefaultAttributes.getSupplier(living);
        return supplier.hasAttribute(Attributes.SCALE) ? supplier.getBaseValue(Attributes.SCALE) : 1.0D;
    }

    private MobSizeVariance() {
    }
}
