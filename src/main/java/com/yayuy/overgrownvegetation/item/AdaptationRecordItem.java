package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.ModTags;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * G5 の「適応の記録」(順 1 の着手順 11、2026-10-04)。G5 の 7 体がそれぞれ自分の記録を落とす
 * (プレイヤーの武器に適応した敵を倒し、その適応を自分の装備に取り込む —— 仕様書 {@code ORDER1_SPEC.md} の 4 節)。
 * パワードアーマーのスキルツリーの支払いに使う。どの本に使えるかは<b>アイテムのタグ</b>で決まる
 * ({@link ModTags#ADAPTATION_RECORDS_DEFENSE} ほか。ユーザー指定: 防御 ← 盾・喰らい / 強靭 ← 狙い・掴み / 耐久 ← 水・闇・掘削)。
 * 説明もタグから出すので、タグを書き換えれば説明も合う。
 */
public class AdaptationRecordItem extends Item {

    /** 落とす G5 の和名の翻訳キー(entity の名前)。 */
    private final String sourceEntityKey;

    public AdaptationRecordItem(String sourceEntityKey, Properties properties) {
        super(properties);
        this.sourceEntityKey = sourceEntityKey;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.adaptation_record.source",
                Component.translatable(this.sourceEntityKey)).withStyle(ChatFormatting.GRAY));
        String[] branches = {"defense", "toughness", "durability"};
        var tags = List.of(ModTags.ADAPTATION_RECORDS_DEFENSE, ModTags.ADAPTATION_RECORDS_TOUGHNESS,
                ModTags.ADAPTATION_RECORDS_DURABILITY);
        for (int i = 0; i < branches.length; i++) {
            if (stack.is(tags.get(i))) {
                tooltip.add(Component.translatable("tooltip.overgrown_vegetation.adaptation_record.branch",
                        Component.translatable("gui.overgrown_vegetation.powered_armor_table.branch." + i))
                        .withStyle(ChatFormatting.BLUE));
            }
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
