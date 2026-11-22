package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * <b>導晶の武器</b>(グレード 1。2026-11-21 夜ユーザー決定「武器 = 導晶の剣 + 導晶機関で弦を巻く弩」)。
 * 地脈の力の効き目は道具と同じ形({@link LeyCharge}): 力がある間だけ強くなり、1 回ごとに減り、切れたらふつうの武器。
 * 数は {@code gear_tiers.json} の {@code sword}・{@code crossbow}【仮】。
 */
public final class ConduitWeapons {

    private ConduitWeapons() {
    }

    static GearTierTable.Sword sword() {
        return DesignData.gearTiers().sword;
    }

    static GearTierTable.Crossbow crossbow() {
        return DesignData.gearTiers().crossbow;
    }

    /**
     * 剣の攻撃と振り(力の有無で 2 通り)。素手の 1 と振り 4 はプレイヤーの基礎の値なので引く。
     * 1.20.1: 1.21 の ItemAttributeModifiers の代わりに利き手の Multimap。修飾子の UUID はバニラの手持ちの攻撃の物
     * (説明の数にプレイヤーの基礎が足される。UUID は Item の protected なので {@link Sword} の中で作る)。
     */
    public static Multimap<Attribute, AttributeModifier> swordAttributes(boolean charged) {
        return Sword.attributes(charged);
    }

    /**
     * 弩の装填の時間(tick)。導晶の弩に 1 回撃つ分の力があれば {@code chargedLoadMultiplier} 倍。
     * バニラの {@code CrossbowItem.getChargeDuration} は static なので、mixin({@code CrossbowItemConduitMixin})からここを呼ぶ
     * (装填の判定・音・手の構え・絵の引き具合が同じ値を見る)。
     */
    public static int chargeDuration(ItemStack stack, int vanilla) {
        if (!(stack.getItem() instanceof Crossbow) || !LeyCharge.charged(stack)) return vanilla;
        return Math.max(1, (int) Math.round(vanilla * crossbow().chargedLoadMultiplier));
    }

    /** 導晶の剣(鉄の剣と同じ攻撃 6・振り 1.6。力がある間は +2)。 */
    public static final class Sword extends SwordItem implements LeyCharge.Chargeable {
        public Sword(Tier tier, Properties p) {
            // 1.20.1: 剣の構築子は攻撃・振りを取る。力の無い時の値を渡す(Mob の武器選びの getDamage が見る。効く値は下の getAttributeModifiers)
            super(tier, (int) Math.round(sword().damage - 1.0D - tier.getAttackDamageBonus()), (float) (sword().attackSpeed - 4.0D), p);
        }

        static Multimap<Attribute, AttributeModifier> attributes(boolean charged) {
            GearTierTable.Sword s = sword();
            double damage = s.damage + (charged ? s.chargedBonus : 0.0F) - 1.0D;
            return ImmutableMultimap.<Attribute, AttributeModifier>builder()
                    .put(Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_ATTACK_DAMAGE_UUID, "Weapon modifier", damage,
                            AttributeModifier.Operation.ADDITION))
                    .put(Attributes.ATTACK_SPEED, new AttributeModifier(BASE_ATTACK_SPEED_UUID, "Weapon modifier", s.attackSpeed - 4.0D,
                            AttributeModifier.Operation.ADDITION))
                    .build();
        }

        @Override
        public int leyCapacity() {
            return sword().capacity;
        }

        @Override
        public int leyCost() {
            return sword().fePerHit;
        }

        @Override
        public String leyHintKey() {
            return "item.overgrown_vegetation.ley_charge.hint_sword";
        }

        /** 部品の攻撃の値を持たせず、ここで力の有無を見て返す(説明の攻撃の数も力に合わせて変わる)。 */
        @Override
        public Multimap<Attribute, AttributeModifier> getAttributeModifiers(EquipmentSlot slot, ItemStack stack) {
            return slot == EquipmentSlot.MAINHAND ? swordAttributes(LeyCharge.charged(stack)) : ImmutableMultimap.of();
        }

        @Override
        public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
            if (!attacker.level().isClientSide) LeyCharge.spend(stack, attacker instanceof Player p ? p : null);
            return super.hurtEnemy(stack, target, attacker);
        }

        @Override
        public boolean overrideOtherStackedOnMe(ItemStack stack, ItemStack other, Slot slot, ClickAction action, Player player, SlotAccess access) {
            return LeyCharge.onStackedOn(stack, other, action, player) || super.overrideOtherStackedOnMe(stack, other, slot, action, player, access);
        }

        @Override
        public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
            return slotChanged || !LeyCharge.onlyChargeChanged(oldStack, newStack);
        }

        @Override
        public void appendHoverText(ItemStack stack, @Nullable net.minecraft.world.level.Level context, List<Component> tooltip, TooltipFlag flag) {
            LeyCharge.tooltip(stack, tooltip);
        }
    }

    /** 導晶の弩(バニラの弩が元。導晶機関で弦を巻くので、力がある間は装填が速い)。 */
    public static final class Crossbow extends CrossbowItem implements LeyCharge.Chargeable {
        public Crossbow(Properties p) {
            super(p);
        }

        @Override
        public int leyCapacity() {
            return crossbow().capacity;
        }

        @Override
        public int leyCost() {
            return crossbow().fePerShot;
        }

        @Override
        public String leyHintKey() {
            return "item.overgrown_vegetation.ley_charge.hint_crossbow";
        }

        /**
         * 撃ったら 1 回分の力を使う(込めた矢が出たときだけ。サーバーだけ)。
         * 1.20.1: performShooting は static で上書きできないので、プレイヤーが撃つ use の後で見る(Mob が撃つ時は力を使わない)。
         */
        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack weapon = player.getItemInHand(hand);
            boolean loaded = isCharged(weapon);
            InteractionResultHolder<ItemStack> r = super.use(level, player, hand);
            if (!level.isClientSide && loaded && !isCharged(weapon)) {
                LeyCharge.spend(weapon, player);
            }
            return r;
        }

        @Override
        public boolean isValidRepairItem(ItemStack stack, ItemStack repair) {
            return repair.is(ModGear.CONDUIT_PLATE.get()) || super.isValidRepairItem(stack, repair);
        }

        @Override
        public boolean overrideOtherStackedOnMe(ItemStack stack, ItemStack other, Slot slot, ClickAction action, Player player, SlotAccess access) {
            return LeyCharge.onStackedOn(stack, other, action, player) || super.overrideOtherStackedOnMe(stack, other, slot, action, player, access);
        }

        @Override
        public void appendHoverText(ItemStack stack, @Nullable net.minecraft.world.level.Level context, List<Component> tooltip, TooltipFlag flag) {
            super.appendHoverText(stack, context, tooltip, flag);
            LeyCharge.tooltip(stack, tooltip);
        }
    }
}
