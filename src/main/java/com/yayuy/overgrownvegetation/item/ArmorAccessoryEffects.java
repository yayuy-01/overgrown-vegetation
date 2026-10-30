package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ItemAttributeModifierEvent;

/**
 * 装飾品・拡張パーツのうち<b>属性で効くもの</b>(俊足・緩衝・採掘・踏ん張り)を防具の属性に足す(順 1 の着手順 9)。
 *
 * <p>{@link ItemAttributeModifierEvent} はスタックの属性を問い合わせるたびに(装備の付け替えの判定・説明の表示)
 * 両側で呼ばれる。ここで足すと、防具の説明の「〜に装備したとき」に行が出て、着たときにだけ効く。
 * スタックの {@code ATTRIBUTE_MODIFIERS} コンポーネントに書かないのは、書くと防具自身の防御点などの修飾を置き換えてしまうため。
 *
 * <p>数値は {@code gear.json} の {@code accessories}。{@code /ov design reload} で変えたときは、着ている防具は着直すと新しい値になる
 * (着ている間は付けたときの修飾が残る)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ArmorAccessoryEffects {

    @SubscribeEvent
    public static void onItemAttributes(ItemAttributeModifierEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof ArmorItem armor)) {
            return;
        }
        add(event, armor, ArmorAccessory.attached(stack, ArmorAccessory.Kind.ACCESSORY));
        add(event, armor, ArmorAccessory.attached(stack, ArmorAccessory.Kind.EXTENSION));
    }

    private static void add(ItemAttributeModifierEvent event, ArmorItem armor, ArmorAccessory accessory) {
        // 部位の違うものは効かせない(付けるときに弾いているので、来るのはコマンドで載せたときくらい)
        if (accessory == null || accessory.type() != armor.getType()) {
            return;
        }
        Holder<Attribute> attribute = accessory.attribute();
        AttributeModifier modifier = accessory.modifier();
        if (attribute != null && modifier != null) {
            event.addModifier(attribute, modifier, EquipmentSlotGroup.bySlot(armor.getEquipmentSlot()));
        }
    }

    private ArmorAccessoryEffects() {
    }
}
