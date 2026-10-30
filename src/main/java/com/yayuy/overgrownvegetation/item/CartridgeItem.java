package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.GearTable;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 「カートリッジ」(順 1 の着手順 8、2026-10-03)— 因子を入れてパワードアーマーに差す容器。
 *
 * <ul>
 *   <li>因子は作成テーブルの装着タブで入れる。<b>入れた因子は取り出せない</b>(ユーザー指定)</li>
 *   <li>10 個まで({@code poweredArmor.cartridgeCapacity})。1 個 = 2% なので 1 つで最大 20%</li>
 *   <li><b>4 部位それぞれに 1 つ</b>付けられ、効き目は足し算(合計 80% まで。ユーザー指定)。付け外しは装着タブで自由</li>
 * </ul>
 * 中身が 1 個以上あると絵が「充填済み」に変わる(アイテムモデルの {@code overgrown_vegetation:filled})。
 * 重ならない(中身ごとに 1 つずつ)。
 */
public class CartridgeItem extends Item {

    public CartridgeItem(Properties properties) {
        super(properties);
    }

    public static CartridgeContents contents(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.CARTRIDGE_FACTORS.get(), CartridgeContents.EMPTY);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        appendContents(contents(stack), tooltip);
        super.appendHoverText(stack, context, tooltip, flag);
    }

    /** 中身の行(「因子 6 / 10」と、種類ごとの「近接攻撃による攻撃を 8% 軽減」)。パワードアーマーのツールチップも使う。 */
    public static void appendContents(CartridgeContents contents, List<Component> tooltip) {
        GearTable.PoweredArmor cfg = DesignData.gear().poweredArmor();
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.cartridge.total", contents.total(),
                cfg.cartridgeCapacity()).withStyle(ChatFormatting.GRAY));
        for (CartridgeFactor f : CartridgeFactor.values()) {
            int n = contents.count(f);
            if (n > 0) {
                tooltip.add(Component.translatable("tooltip.overgrown_vegetation.cartridge.line",
                        Component.translatable(f.targetKey()), PoweredArmorItem.percent(n * cfg.factorPerItem()))
                        .withStyle(ChatFormatting.GREEN));
            }
        }
    }
}
