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
import com.yayuy.overgrownvegetation.port.component.OvItemData;

/**
 * <b>地脈の力をためる道具・武器・防具</b>(2026-11-21 グレード 1 の導晶。ユーザー決定「地脈の力をためると強くなる(案 b)」)。
 * FE は電池と同じ部品 {@link ModLey#ENERGY} に持ち、口も電池と同じ NeoForge のアイテムの FE の口({@link ModGear} で登録)。
 *
 * <ul>
 *   <li>充電: 汲み上げ器・鉄塔の<b>電池の口に差す</b>(電池と同じ手つき。口は受け取るだけで、網へは出さない)/
 *   持ち物の画面で<b>満ちた電池をつかんで上で右クリック</b>(電池から {@code pourPerClick} まで移る)。</li>
 *   <li>使う: 1 回分({@link Chargeable#leyCost})以上ある間だけ強くなり、1 回ごとに減る。
 *   道具 = 掘る速さ・剣 = 攻撃・弩 = 装填の速さ・防具 = 受ける傷。</li>
 *   <li>切れたらふつうの道具・武器・防具(10-03 の方針: 装備が止まって詰む形にしない)。</li>
 * </ul>
 * 値はすべて {@code gear_tiers.json} の {@code conduit}・{@code sword}・{@code crossbow}・{@code armor}【仮】。
 */
public final class LeyCharge {

    /**
     * 地脈の力をためる物の印(電池の口に差せる)。ためられる量と 1 回に使う量は物ごと(道具 = 1 ブロック・剣 = 1 回当てる・
     * 弩 = 1 回撃つ・防具 = 1 回傷を受ける)。既定は道具の値。
     */
    public interface Chargeable {
        default int leyCapacity() {
            return DesignData.gearTiers().conduit.capacity;
        }

        /** 1 回に使う FE(これより少なければ力が無い扱い)。 */
        default int leyCost() {
            return DesignData.gearTiers().conduit.fePerBlock;
        }

        /** 説明の 2 行目(何が強くなるか・充電の仕方)。 */
        default String leyHintKey() {
            return "item.overgrown_vegetation.ley_charge.hint";
        }
    }

    private LeyCharge() {
    }

    private static GearTierTable.Conduit c() {
        return DesignData.gearTiers().conduit;
    }

    public static boolean has(ItemStack stack) {
        return stack.getItem() instanceof Chargeable;
    }

    /** 道具のためられる量(道具 4 本の口の登録と既定)。 */
    public static int capacity() {
        return Math.max(0, c().capacity);
    }

    /** その物のためられる量(ためない物は 0)。 */
    public static int capacity(ItemStack stack) {
        return stack.getItem() instanceof Chargeable ch ? Math.max(0, ch.leyCapacity()) : 0;
    }

    /** その物が 1 回に使う量。 */
    public static int cost(ItemStack stack) {
        return stack.getItem() instanceof Chargeable ch ? Math.max(1, ch.leyCost()) : Integer.MAX_VALUE;
    }

    public static int maxReceive() {
        return Math.max(1, c().maxReceive);
    }

    public static int stored(ItemStack stack) {
        return Mth.clamp(OvItemData.getOrDefault(stack, ModLey.ENERGY.get(), 0), 0, capacity(stack));
    }

    public static void set(ItemStack stack, int fe) {
        OvItemData.set(stack, ModLey.ENERGY.get(), Mth.clamp(fe, 0, capacity(stack)));
    }

    /** 満たした物(創造モードのタブ・試す用)。 */
    public static ItemStack full(ItemStack stack) {
        set(stack, capacity(stack));
        return stack;
    }

    /** 1 回分の力が残っているか(残っている間だけ強くなる)。 */
    public static boolean charged(ItemStack stack) {
        return has(stack) && stored(stack) >= cost(stack);
    }

    /** 掘る速さ {@code base} を、力が残っていれば上げる。道具が効かないブロック(速さ 1 以下)は変えない。 */
    public static float boost(ItemStack stack, float base) {
        return base > 1.0F && charged(stack) ? (float) (base * c().chargedSpeedMultiplier) : base;
    }

    /** 1 ブロック掘ったときに払う(サーバーだけ。創造モードは払わない)。払ったら true。 */
    public static boolean spendForBlock(ItemStack stack, Player player) {
        return spend(stack, player);
    }

    /** 1 回分({@link #cost})を払う(サーバーだけ。創造モードは払わない)。力が足りなければ払わず false。 */
    public static boolean spend(ItemStack stack, Player player) {
        if (!charged(stack)) return false;
        if (player != null && player.getAbilities().instabuild) return true;
        // 導晶装飾の布(防具の付け物)を着けていれば減りが少ない(2026-11-21 夜 armor-attachments【仮 1 割】)
        set(stack, stored(stack) - com.yayuy.overgrownvegetation.gear.attach.ArmorAttachments.leyCost(player, cost(stack)));
        return true;
    }

    /** 電池 {@code battery} から {@code target} へ移す。移した FE を返す。 */
    public static int pour(ItemStack target, ItemStack battery) {
        if (!has(target) || !battery.is(ModLey.BATTERY.get())) return 0;
        int room = capacity(target) - stored(target);
        int move = Math.min(Math.min(room, LeyBatteryItem.stored(battery)), Math.max(0, c().pourPerClick));
        if (move <= 0) return 0;
        set(target, stored(target) + move);
        OvItemData.set(battery, ModLey.ENERGY.get(), LeyBatteryItem.stored(battery) - move);
        return move;
    }

    /** 持ち物の画面で電池を上で右クリックしたとき({@code overrideOtherStackedOnMe} から)。電池なら true(取り替えを止める)。 */
    public static boolean onStackedOn(ItemStack target, ItemStack other, ClickAction action, Player player) {
        if (action != ClickAction.SECONDARY || !other.is(ModLey.BATTERY.get())) return false;
        int moved = pour(target, other);
        if (moved > 0) player.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.8F, 1.4F);
        return true;
    }

    /** 力の量だけが変わったときは、手に持った物を持ち替えの動きにしない(当てる・掘るたびに手が下がらないように)。 */
    public static boolean onlyChargeChanged(ItemStack oldStack, ItemStack newStack) {
        if (!oldStack.is(newStack.getItem())) return false;
        ItemStack a = oldStack.copy();
        ItemStack b = newStack.copy();
        OvItemData.remove(a, ModLey.ENERGY.get());
        OvItemData.remove(b, ModLey.ENERGY.get());
        a.setDamageValue(0);
        b.setDamageValue(0);
        return ItemStack.isSameItemSameComponents(a, b);
    }

    public static void tooltip(ItemStack stack, List<Component> tooltip) {
        tooltip.add(Component.translatable("item.overgrown_vegetation.ley_charge.charge", LeyText.fe(stored(stack)), LeyText.fe(capacity(stack)))
                .withStyle(charged(stack) ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable(stack.getItem() instanceof Chargeable ch ? ch.leyHintKey() : "item.overgrown_vegetation.ley_charge.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
