package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * 「エストック」(2026-10-10。武器の 2 本目 = ユーザー指定。素材はツヴァイヘンダーと同じ 3 本 = Claude 仮決め)。鎧の隙間を突く刺突剣。
 *
 * <p><b>防具を {@value #ARMOR_PIERCE} だけ無視する</b>(近接で殴ったとき、防具が減らす分をその割合だけ減らす)・<b>薙ぎ払いが無い</b>(突くだけ)・
 * <b>リーチが {@value #REACH_BONUS} マス長い</b>。片手で持つ(両手持ちのタグには入れない。前の案の「片手 + 盾」の組)。ここまで全部 Claude 仮決め。
 * 走りながらの踏み込み突き(前の案)は特別な動きなので作っていない(10-10 ユーザー「特別な動きは今はまだ用意しない」)。
 * 攻撃力・攻撃速度・耐久は gear.json の {@code perTool.estoc}(無ければ {@code GearTable} の種別の既定)。
 *
 * <p>剣のまま({@link SwordItem})なのは、剣のエンチャント(タグ {@code #minecraft:swords}。範囲ダメージ増加も付けられるが、薙ぎ払わないので効かない)・
 * 蜘蛛の巣・植物越しの攻撃・汚染の蔦の扱いをそのまま受けるため。
 */
public class EstocItem extends SwordItem {

    /** 手に持っている間に足すリーチ(素手・剣は 3)。 */
    public static final double REACH_BONUS = 0.5D;
    /** 近接で殴ったとき、相手の防具が減らす分のうち無視する割合。 */
    public static final float ARMOR_PIERCE = 0.5F;

    public static final ResourceLocation REACH_ID = OvergrownVegetation.id("estoc_reach");

    public EstocItem(Tier tier, Properties properties) {
        super(tier, properties);
    }

    /** 剣と同じ攻撃力・攻撃速度に、リーチを足したもの。 */
    public static ItemAttributeModifiers createAttributes(Tier tier, float attackDamage, float attackSpeed) {
        return SwordItem.createAttributes(tier, attackDamage, attackSpeed)
                .withModifierAdded(Attributes.ENTITY_INTERACTION_RANGE,
                        new AttributeModifier(REACH_ID, REACH_BONUS, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
    }

    /** 突くだけ: 剣のできること(蜘蛛の巣など)から薙ぎ払いだけ外す。 */
    @Override
    public boolean canPerformAction(ItemStack stack, ItemAbility itemAbility) {
        return itemAbility != ItemAbilities.SWORD_SWEEP && super.canPerformAction(stack, itemAbility);
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
    public static final class Events {

        /** 利き手のエストックで直接殴った(飛び道具でない)ときだけ、防具の分を減らす。 */
        @SubscribeEvent
        public static void onIncomingDamage(LivingIncomingDamageEvent event) {
            DamageSource source = event.getSource();
            if ((source.is(DamageTypes.PLAYER_ATTACK) || source.is(DamageTypes.MOB_ATTACK))
                    && source.getEntity() instanceof LivingEntity attacker && source.getDirectEntity() == attacker
                    && attacker.getMainHandItem().getItem() instanceof EstocItem) {
                event.getContainer().addModifier(DamageContainer.Reduction.ARMOR, (container, reduction) -> reduction * (1.0F - ARMOR_PIERCE));
            }
        }

        private Events() {
        }
    }
}
