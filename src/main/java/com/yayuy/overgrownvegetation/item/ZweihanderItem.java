package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * 「ツヴァイヘンダー」(2026-10-09。G6 の Mob ができるまで武器を作る、の 1 本目。絵はユーザー、素材はクロロフィライト・
 * クロロフィライト合金・サンライト合金の 3 本 = ユーザー指定)。両手で振る大剣。
 *
 * <p>剣より重く遅い代わりに、<b>リーチが {@value #REACH_BONUS} マス長く</b>、<b>薙ぎ払い(スイープ)が最初から強い</b>
 * (薙ぎ払いの割合 +{@value #SWEEP_RATIO} = 「範囲ダメージ増加 I」と同じ。バニラの薙ぎ払いは 1 しか通らない)。リーチは薙ぎ払いが届く距離も延ばす
 * (バニラの薙ぎ払いは「プレイヤーからリーチ以内」で巻き込む相手を絞る)。この 2 つは Claude 仮決めで、ここの定数。
 * 攻撃力・攻撃速度・耐久は gear.json の {@code perTool.zweihander}(無ければ {@code GearTable} の種別の既定)。
 *
 * <p>剣のまま({@link SwordItem})にしているのは、薙ぎ払い・剣のエンチャント(タグ {@code #minecraft:swords})・蜘蛛の巣・
 * 植物越しの攻撃({@code SwingThroughFoliage})・汚染の蔦の扱いをそのまま受けるため。両手持ち(左手の物を使えない)はタグ {@code #overgrown_vegetation:two_handed} と {@link TwoHandedRules}(10-10 ユーザー指定)。特別な動きは今は作らない(10-10 ユーザー)。
 */
public class ZweihanderItem extends SwordItem {

    /** 手に持っている間に足すリーチ(エンティティに届く距離。素手・剣は 3)。 */
    public static final double REACH_BONUS = 1.0D;
    /** 手に持っている間に足す薙ぎ払いの割合(巻き込んだ相手へ 1 + これ × 攻撃力。上限 1.0 はバニラの属性の上限)。 */
    public static final double SWEEP_RATIO = 0.5D;

    public static final ResourceLocation REACH_ID = OvergrownVegetation.id("zweihander_reach");
    public static final ResourceLocation SWEEP_ID = OvergrownVegetation.id("zweihander_sweep");

    public ZweihanderItem(Tier tier, Properties properties) {
        super(tier, properties);
    }

    /** 剣と同じ攻撃力・攻撃速度(手に持ったときの緑の行)に、リーチと薙ぎ払いを足したもの。 */
    public static ItemAttributeModifiers createAttributes(Tier tier, float attackDamage, float attackSpeed) {
        return SwordItem.createAttributes(tier, attackDamage, attackSpeed)
                .withModifierAdded(Attributes.ENTITY_INTERACTION_RANGE,
                        new AttributeModifier(REACH_ID, REACH_BONUS, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                .withModifierAdded(Attributes.SWEEPING_DAMAGE_RATIO,
                        new AttributeModifier(SWEEP_ID, SWEEP_RATIO, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
    }
}
