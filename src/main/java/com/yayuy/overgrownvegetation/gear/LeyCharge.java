package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.ley.LeyBatteryItem;
import com.yayuy.overgrownvegetation.ley.LeyText;
import com.yayuy.overgrownvegetation.registry.ModLey;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * <b>地脈の力をためる道具</b>(2026-11-21 グレード 1 の導晶の道具。ユーザー決定「地脈の力をためると強くなる(案 b)」)。
 * FE は電池と同じ部品 {@link ModLey#ENERGY} に持ち、口も電池と同じ NeoForge のアイテムの FE の口({@link ModGear} で登録)。
 *
 * <ul>
 *   <li>充電: 汲み上げ器・鉄塔の<b>電池の口に差す</b>(電池と同じ手つき。口は受け取るだけで、網へは出さない)/
 *   持ち物の画面で<b>満ちた電池をつかんで道具の上で右クリック</b>(電池から {@code pourPerClick} まで移る)。</li>
 *   <li>使う: 力が {@code fePerBlock} 以上ある間は掘る速さが {@code chargedSpeedMultiplier} 倍。1 ブロックごとに {@code fePerBlock} 減る。</li>
 *   <li>切れたらふつうの道具(10-03 の方針: 装備が止まって詰む形にしない)。</li>
 * </ul>
 * 値はすべて {@code gear_tiers.json} の {@code conduit}【仮】。
 */
public final class LeyCharge {

    /** 地脈の力をためる物の印(電池の口に差せる)。 */
    public interface Chargeable {
    }

    private LeyCharge() {
    }

    private static GearTierTable.Conduit c() {
        return DesignData.gearTiers().conduit;
    }

    public static boolean has(ItemStack stack) {
        return stack.getItem() instanceof Chargeable;
    }

    public static int capacity() {
        return Math.max(0, c().capacity);
    }

    public static int maxReceive() {
        return Math.max(1, c().maxReceive);
    }

    public static int stored(ItemStack stack) {
        return Mth.clamp(stack.getOrDefault(ModLey.ENERGY.get(), 0), 0, capacity());
    }

    public static void set(ItemStack stack, int fe) {
        stack.set(ModLey.ENERGY.get(), Mth.clamp(fe, 0, capacity()));
    }

    /** 速くなる分の力が残っているか。 */
    public static boolean charged(ItemStack stack) {
        return has(stack) && stored(stack) >= Math.max(1, c().fePerBlock);
    }

    /** 掘る速さ {@code base} を、力が残っていれば上げる。道具が効かないブロック(速さ 1 以下)は変えない。 */
    public static float boost(ItemStack stack, float base) {
        return base > 1.0F && charged(stack) ? (float) (base * c().chargedSpeedMultiplier) : base;
    }

    /** 1 ブロック掘ったときに払う(サーバーだけ。創造モードは払わない)。払ったら true。 */
    public static boolean spendForBlock(ItemStack stack, Player player) {
        if (!charged(stack)) return false;
        if (player != null && player.getAbilities().instabuild) return true;
        set(stack, stored(stack) - Math.max(1, c().fePerBlock));
        return true;
    }

    /** 電池 {@code battery} から {@code target} へ移す。移した FE を返す。 */
    public static int pour(ItemStack target, ItemStack battery) {
        if (!has(target) || !battery.is(ModLey.BATTERY.get())) return 0;
        int room = capacity() - stored(target);
        int move = Math.min(Math.min(room, LeyBatteryItem.stored(battery)), Math.max(0, c().pourPerClick));
        if (move <= 0) return 0;
        set(target, stored(target) + move);
        battery.set(ModLey.ENERGY.get(), LeyBatteryItem.stored(battery) - move);
        return move;
    }

    /** 持ち物の画面で電池を道具の上で右クリックしたとき({@code overrideOtherStackedOnMe} から)。電池なら true(取り替えを止める)。 */
    public static boolean onStackedOn(ItemStack target, ItemStack other, ClickAction action, Player player) {
        if (action != ClickAction.SECONDARY || !other.is(ModLey.BATTERY.get())) return false;
        int moved = pour(target, other);
        if (moved > 0) player.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.8F, 1.4F);
        return true;
    }

    public static void tooltip(ItemStack stack, List<Component> tooltip) {
        tooltip.add(Component.translatable("item.overgrown_vegetation.ley_charge.charge", LeyText.fe(stored(stack)), LeyText.fe(capacity()))
                .withStyle(charged(stack) ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("item.overgrown_vegetation.ley_charge.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
