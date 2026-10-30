package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.ChatFormatting;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * この MOD のアイテムの説明文(2026-10-15 ユーザー指定「ツールチップの説明文も変更できるように」)。
 *
 * <p>言語ファイルに <b>{@code item.overgrown_vegetation.<id>.tooltip}</b>(ブロックなら {@code block.…})が
 * 書いてあれば、持ち物の説明にその 1 行を灰色で足す。<b>鍵があるかどうかだけで決まる</b>ので、
 * 説明を足したいアイテムは mod マネージャーのアイテムタブで文を入れるだけでよく、Java は触らなくてよい。
 *
 * <p>改行したいときは言語ファイルの値に {@code \n} を入れる(行ごとに分けて出す)。
 *
 * <p>自前で説明を出しているアイテム({@code SporeInfectionSyringeItem} は注入する濃度を引数で埋め、
 * {@code TwoHandedRules} は両手持ちの注意を出す)とは鍵が別なので二重にはならない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ItemDescriptionTooltip {

    private ItemDescriptionTooltip() {
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!OvergrownVegetation.MODID.equals(id.getNamespace())) {
            return;
        }
        // 持ち物の名前の鍵(item. か block.)に .tooltip を足したものを探す。
        String key = stack.getItem().getDescriptionId() + ".tooltip";
        if (!I18n.exists(key)) {
            return;
        }
        for (String line : I18n.get(key).split("\n")) {
            event.getToolTip().add(Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
    }
}
