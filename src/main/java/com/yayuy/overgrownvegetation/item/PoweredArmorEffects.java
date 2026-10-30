package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.GearTable;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/**
 * パワードアーマーのスキルツリーの<b>防御</b>と<b>強靭</b>(順 1 の着手順 6、2026-10-03)。
 *
 * <p>どちらもバニラの防御点・強靭の属性ではなく、<b>防具の計算の後に被害そのものを減らす</b>
 * (ユーザー指定「A」—— 強化クロロフィライト合金で属性も軽減 80% も上限に届いていて、属性を積んでも差が出ないため)。
 * {@link LivingDamageEvent.Pre} は防具・エンチャント・吸収の後なので、ここで掛ければ「防具の計算の後」になる。
 *
 * <ul>
 *   <li><b>防御</b>: 着ている全部位の段を足して {@code poweredArmor.defensePerLevel} を掛けた割合を減らす(4 部位 × 3 段で 24%)</li>
 *   <li><b>強靭</b>: 防具を貫通する攻撃({@link ModTags#ARMOR_PIERCING})だけ、段の合計 × {@code piercingPerLevel} を減らす(最大 60%)</li>
 * </ul>
 * 2 つは掛け算で重なる。過繁茂の Mob からの 10%/部位({@code OvergrowthWardDamage}、防具の計算の前)とも掛け算。
 *
 * <p><b>カートリッジ</b>(着手順 8)もここで掛ける: 各部位に付いたカートリッジの因子のうち、この攻撃に効く種類
 * ({@link CartridgeFactor#tag()})の数を 4 部位ぶん足し、{@code poweredArmor.factorPerItem} を掛けた割合を減らす。
 * {@code /kill} や奈落({@code #minecraft:bypasses_invulnerability})には効かない。どちらも 90% で止める(無敵にしない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PoweredArmorEffects {

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final double CAP = 0.9D;

    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Pre event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) {
            return;
        }
        float factor = damageFactor(victim, event.getSource());
        if (factor < 1.0F) {
            event.setNewDamage(event.getNewDamage() * factor);
        }
    }

    /** この攻撃に掛ける倍率(1 = 減らさない)。GameTest からも呼ぶ。 */
    public static float damageFactor(LivingEntity victim, DamageSource source) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return 1.0F;
        }
        int defense = 0;
        int toughness = 0;
        int factors = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = victim.getItemBySlot(slot);
            if (stack.getItem() instanceof PoweredArmorItem) {
                PoweredArmorTree tree = PoweredArmorItem.tree(stack);
                defense += tree.defense();
                toughness += tree.toughness();
                factors += matchingFactors(PoweredArmorItem.cartridge(stack), source);
            }
        }
        if (defense == 0 && toughness == 0 && factors == 0) {
            return 1.0F;
        }
        GearTable.PoweredArmor cfg = DesignData.gear().poweredArmor();
        double factor = 1.0D - Math.min(CAP, defense * cfg.defensePerLevel());
        if (toughness > 0 && source.is(ModTags.ARMOR_PIERCING)) {
            factor *= 1.0D - Math.min(CAP, toughness * cfg.piercingPerLevel());
        }
        if (factors > 0) {
            // カートリッジ(着手順 8): 4 部位の、この攻撃に効く因子の数を足して掛ける(部位ごと最大 20%、合計 80%。ユーザー指定)
            factor *= 1.0D - Math.min(CAP, factors * cfg.factorPerItem());
        }
        return (float) factor;
    }

    /** カートリッジの因子のうち、この攻撃に効くものの数(1 つの攻撃が 2 つの種類に当たれば両方を数える)。 */
    private static int matchingFactors(CartridgeContents contents, DamageSource source) {
        if (contents == null || contents.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (CartridgeFactor f : CartridgeFactor.values()) {
            int c = contents.count(f);
            if (c > 0 && f.matches(source)) {
                n += c;
            }
        }
        return n;
    }

    private PoweredArmorEffects() {
    }
}
