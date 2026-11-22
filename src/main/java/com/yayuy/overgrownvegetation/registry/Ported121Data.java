package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.gear.ModMobDrops;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.event.furnace.FurnaceFuelBurnTimeEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * 【1.20.1 だけの物】本流では data で決めている物のうち、1.20.1 の data では書けない物を Java で(2026-11-23、D データの報告から F が引き取った)。
 *
 * <ul>
 *   <li>堆肥({@code data_maps/item/compostables.json}): 群体の種 0.65。</li>
 *   <li>燃料({@code data_maps/item/furnace_fuels.json}): 熱の袋 1600・熱の膜 800 tick。</li>
 *   <li>押し返さないダメージ({@code tags/damage_type/no_knockback.json} の 2 つ。1.20.1 のバニラはこのタグを読まない):
 *       胞子の感染・蠢く壁で受けた打撃では押し返されない。</li>
 * </ul>
 * 本流の data の値を変えたら、ここも合わせる(公開前の追い付きで D の報告の「data_maps」を見る)。
 */
public final class Ported121Data {

    private Ported121Data() {
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        private ModBus() {
        }

        @SubscribeEvent
        public static void onCommonSetup(FMLCommonSetupEvent event) {
            event.enqueueWork(() -> ComposterBlock.COMPOSTABLES.put(ModMobDrops.COLONY_SEED.get(), 0.65F));
        }
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
    public static final class GameBus {
        private GameBus() {
        }

        @SubscribeEvent
        public static void onFuel(FurnaceFuelBurnTimeEvent event) {
            Item item = event.getItemStack().getItem();
            if (item == ModMobDrops.HEAT_SAC.get()) {
                event.setBurnTime(1600);
            } else if (item == ModMobDrops.THERMAL_MEMBRANE.get()) {
                event.setBurnTime(800);
            }
        }

        // 1.20.1 の LivingEntity#hurt は押し返し(knockback)の後に lastDamageSource を入れるので、hurt の入口で受けたダメージを控えておく
        private static LivingEntity hurtEntity;
        private static DamageSource hurtSource;

        @SubscribeEvent
        public static void onAttack(LivingAttackEvent event) {
            hurtEntity = event.getEntity();
            hurtSource = event.getSource();
        }

        @SubscribeEvent
        public static void onKnockBack(LivingKnockBackEvent event) {
            if (event.getEntity() != hurtEntity || hurtSource == null) {
                return;
            }
            if (hurtSource.is(ModDamageTypes.SPORE_INFECTION) || hurtSource.is(ModCreepingWall.CREEPING_WALL)) {
                event.setCanceled(true);
            }
        }
    }
}
