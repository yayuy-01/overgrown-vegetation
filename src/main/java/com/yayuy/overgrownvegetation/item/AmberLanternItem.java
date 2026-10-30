package com.yayuy.overgrownvegetation.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * 「琥珀の角灯」(2026-10-15、サバイバル用アイテム ㊳。汚染の前から作れる)。真鍮の枠に媒介結晶を入れた、火を使わない琥珀色の灯り。
 * <ul>
 *     <li>腰の追加防具の枠({@link ExtraArmor.Part#WAIST})に下げると、持ち主の周りが明るさ 12 で照らされる「動く光」
 *     ({@code client/light/DynamicLights}。ユーザー指定)。燃料は要らない(ユーザー指定)。体の腰の横に角灯の模型が下がって見える。</li>
 *     <li>置けばバニラのランタンと同じく、床に立てても天井から吊っても使える明るさ 15 の灯り(ユーザー指定)。</li>
 * </ul>
 */
public class AmberLanternItem extends BlockItem implements ExtraArmorItem {

    /** 腰に下げたときの動く光の明るさ(ユーザー指定)。 */
    public static final int WORN_LIGHT = 12;

    public AmberLanternItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public ExtraArmor.Part extraArmorPart() {
        return ExtraArmor.Part.WAIST;
    }

    @Override
    public ResourceLocation wornTexture() {
        return null;
    }

    @Override
    public int wornLightLevel() {
        return WORN_LIGHT;
    }

    @Override
    public boolean renderWornItem() {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.overgrown_vegetation.extra_armor.waist").withStyle(ChatFormatting.GRAY));
    }
}
