package com.yayuy.overgrownvegetation.ley;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModLey;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;
import com.yayuy.overgrownvegetation.port.component.OvItemData;

/**
 * <b>地脈の電池</b>(2026-11-13 サバイバルの背骨 段 2b、決定 8「電池にためて電池で動かす」)。FE をためて持ち運ぶ。
 * 中身はデータコンポーネント {@link ModLey#ENERGY}、口は NeoForge のアイテムの FE の口({@code ComponentEnergyStorage}、{@link ModLey} で登録)
 * なので、ほかの MOD の充電台・機械の電池の枠でも使える。
 *
 * <p>充電: 汲み上げ器に差す(汲み上げ器の中が半分を越えた余りで充電)/ 柱に掛ける(網の余りで充電し、足りない時は網へ出す)。
 * 使う: 柱に掛けて網へ出す / 2c の換気の機械に差す(機械の側で {@code Capabilities.EnergyStorage.ITEM} を読む)。
 * 見た目【仮】: 真鍮の筒に革の帯。後でユーザーが描き直す。容量は {@code spore_field.json} の {@code ley.batteryCapacity}【仮】。
 */
public class LeyBatteryItem extends Item {

    public LeyBatteryItem(Properties properties) {
        super(properties);
    }

    public static int capacity() {
        return DesignData.sporeField().ley().batteryCapacity();
    }

    public static int maxTransfer() {
        return DesignData.sporeField().ley().batteryMaxTransfer();
    }

    public static int stored(ItemStack stack) {
        return Math.max(0, OvItemData.getOrDefault(stack, ModLey.ENERGY.get(), 0));
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round(13.0F * Mth.clamp(stored(stack) / (float) capacity(), 0.0F, 1.0F));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0xACE300; // 地脈の黄緑(地脈の根と同じ)
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.overgrown_vegetation.ley_battery.charge",
                LeyText.fe(stored(stack)), LeyText.fe(capacity())).withStyle(ChatFormatting.GRAY));
    }
}
