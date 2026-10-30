package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 「パワードアーマーの設計図」(順 1 の着手順 6、2026-10-03)— 救済措置。
 *
 * <p>作成テーブルの設計図のタブで、パワードアーマー 1 部位のツリーを写す({@link PoweredArmorBlueprint})。
 * 失ったあと、作成の素材 + 追加の素材でツリーを引き継いだ装備を作り直せる。<b>作り直しても設計図は残る</b>
 * (ユーザー指定)。写すのは白紙か、同じ部位の設計図だけ(違う部位を上書きして消さないため)。
 * 白紙は作業台で作る(紙 3 + フロエマイト 1。Claude 仮決め)。
 */
public class PoweredArmorBlueprintItem extends Item {

    public PoweredArmorBlueprintItem(Properties properties) {
        super(properties);
    }

    /** 写してある中身。白紙なら null。 */
    @Nullable
    public static PoweredArmorBlueprint data(ItemStack stack) {
        return stack.get(ModDataComponents.POWERED_ARMOR_BLUEPRINT.get());
    }

    /** 部位の短い名前(作成の一覧と同じ翻訳キー)。 */
    public static Component partName(String part) {
        return Component.translatable("gui.overgrown_vegetation.powered_armor_table.part." + part);
    }

    @Override
    public Component getName(ItemStack stack) {
        PoweredArmorBlueprint bp = data(stack);
        if (bp == null) {
            return Component.translatable(this.getDescriptionId(stack));
        }
        return Component.translatable(this.getDescriptionId(stack) + ".written", partName(bp.part()), bp.mk());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        PoweredArmorBlueprint bp = data(stack);
        if (bp == null) {
            tooltip.add(Component.translatable("tooltip.overgrown_vegetation.powered_armor_blueprint.blank")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.overgrown_vegetation.powered_armor_blueprint.levels",
                    bp.tree().defense(), bp.tree().toughness(), bp.tree().durability()).withStyle(ChatFormatting.GREEN));
            tooltip.add(Component.translatable("tooltip.overgrown_vegetation.powered_armor_blueprint.written")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
