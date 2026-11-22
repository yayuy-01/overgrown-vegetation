package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.living.LivingDamageEvent;

import java.util.List;

/**
 * <b>導晶の防具</b>(兜・胸当て・脚当て・靴。グレード 1。2026-11-21 夜ユーザー決定)。強さは鉄とダイヤの間で、
 * 地脈の力がある 1 部位ごとに受ける傷を {@code reductionPerPiece} 減らす(4 部位で力がある間はダイヤを少し超える)。
 * 傷を受けるたびに、力がある部位が 1 回分ずつ使う。切れたらふつうの防具。
 *
 * <p>兜は防護マスクと一体にしない(マスクは付け物「口覆いの布」になる)。付け物の枠 3 つは付け物の仕組みの側が足す。
 * 防具を素通りする傷(飢え・奈落・胞子など {@code bypasses_armor})は減らさず、力も使わない【仮】。
 * 数は {@code gear_tiers.json} の {@code armor}【仮】。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID)
public final class ConduitArmor {

    private ConduitArmor() {
    }

    static GearTierTable.Armor armor() {
        return DesignData.gearTiers().armor;
    }

    static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    public static final class Piece extends ArmorItem implements LeyCharge.Chargeable {
        public Piece(Holder<ArmorMaterial> material, Type type, Properties p) {
            super(material, type, p);
        }

        @Override
        public int leyCapacity() {
            return armor().capacity;
        }

        @Override
        public int leyCost() {
            return armor().fePerHit;
        }

        @Override
        public String leyHintKey() {
            return "item.overgrown_vegetation.ley_charge.hint_armor";
        }

        @Override
        public boolean overrideOtherStackedOnMe(ItemStack stack, ItemStack other, Slot slot, ClickAction action, Player player, SlotAccess access) {
            return LeyCharge.onStackedOn(stack, other, action, player) || super.overrideOtherStackedOnMe(stack, other, slot, action, player, access);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            LeyCharge.tooltip(stack, tooltip);
        }
    }

    /** 着ている導晶の防具のうち、力がある部位の数。 */
    public static int chargedPieces(LivingEntity entity) {
        int n = 0;
        for (EquipmentSlot slot : SLOTS) {
            ItemStack s = entity.getItemBySlot(slot);
            if (s.getItem() instanceof Piece && LeyCharge.charged(s)) n++;
        }
        return n;
    }

    /** 傷 {@code damage} を、力がある部位の分だけ減らした値。{@code spend} なら部位ごとに 1 回分の力を使う。 */
    public static float reduce(LivingEntity entity, DamageSource source, float damage, boolean spend) {
        if (damage <= 0.0F || source.is(DamageTypeTags.BYPASSES_ARMOR)) return damage;
        Player player = entity instanceof Player p ? p : null;
        int n = 0;
        for (EquipmentSlot slot : SLOTS) {
            ItemStack s = entity.getItemBySlot(slot);
            if (!(s.getItem() instanceof Piece) || !LeyCharge.charged(s)) continue;
            if (spend) LeyCharge.spend(s, player);
            n++;
        }
        if (n == 0) return damage;
        return damage * (float) Math.max(0.0D, 1.0D - n * armor().reductionPerPiece);
    }

    /** 防具・強度・エンチャントで減った後の傷にかける(無敵の間に弾かれた傷では呼ばれない)。 */
    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Pre event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) return;
        float before = event.getNewDamage();
        float after = reduce(entity, event.getSource(), before, true);
        if (after != before) event.setNewDamage(after);
    }
}
