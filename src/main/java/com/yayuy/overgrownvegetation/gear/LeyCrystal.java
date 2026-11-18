package com.yayuy.overgrownvegetation.gear;

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
 * <b>地脈の結晶</b>(段 3 の武器・装甲の胴。2026-11-18 決定 19「能力の力 = 結晶にためた地脈の力」)。FE は電池と同じ部品 {@link ModLey#ENERGY} に持つ。
 * <ul>
 *   <li>満たし方: 持ち物の画面で、拠点で満たした<b>地脈の電池をつかんで装備の上で右クリック</b>(マスクのフィルターの入れ替えと同じ手つき)。
 *   電池から {@code pourPerClick} まで移る。FE の口も付ける({@code ModGear} の能力登録)ので、後で充電台も作れる。</li>
 *   <li>使うと減る。足りなければ特別な攻撃・能力が止まるだけで、攻撃力・防御は残る(10-03 の方針: 装備が止まって詰む形にしない)。</li>
 * </ul>
 */
public final class LeyCrystal {

    /** 結晶を持つ物。 */
    public interface Powered {
        int crystalCapacity();
    }

    /** 黄緑(地脈の光)。 */
    public static final int BAR_COLOR = 0xB4F05A;

    private LeyCrystal() {
    }

    public static boolean has(ItemStack stack) {
        return stack.getItem() instanceof Powered;
    }

    public static int capacity(ItemStack stack) {
        return stack.getItem() instanceof Powered p ? Math.max(0, p.crystalCapacity()) : 0;
    }

    public static int stored(ItemStack stack) {
        return Mth.clamp(stack.getOrDefault(ModLey.ENERGY.get(), 0), 0, capacity(stack));
    }

    public static void set(ItemStack stack, int fe) {
        stack.set(ModLey.ENERGY.get(), Mth.clamp(fe, 0, capacity(stack)));
    }

    /** {@code fe} 払えれば払って true。足りなければ何も減らさず false(創造モードのプレイヤーは払わずに true は呼び手で)。 */
    public static boolean spend(ItemStack stack, int fe) {
        if (fe <= 0) return true;
        int s = stored(stack);
        if (s < fe) return false;
        set(stack, s - fe);
        return true;
    }

    /** プレイヤーが使うとき: 創造モードなら払わずに通す。 */
    public static boolean spend(Player player, ItemStack stack, int fe) {
        return (player != null && player.getAbilities().instabuild) || spend(stack, fe);
    }

    /** 電池 {@code battery} から {@code target} へ移す。移した FE を返す。 */
    public static int pour(ItemStack target, ItemStack battery) {
        if (!has(target) || !battery.is(ModLey.BATTERY.get())) return 0;
        int room = capacity(target) - stored(target);
        int move = Math.min(Math.min(room, LeyBatteryItem.stored(battery)), DataHolder.pourPerClick());
        if (move <= 0) return 0;
        set(target, stored(target) + move);
        battery.set(ModLey.ENERGY.get(), LeyBatteryItem.stored(battery) - move);
        return move;
    }

    /** 持ち物の画面で電池を装備の上で右クリックしたとき({@code overrideOtherStackedOnMe} から)。 */
    public static boolean onStackedOn(ItemStack target, ItemStack other, ClickAction action, Player player) {
        if (action != ClickAction.SECONDARY || !other.is(ModLey.BATTERY.get())) return false;
        int moved = pour(target, other);
        if (moved > 0) player.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.8F, 1.4F);
        return true;
    }

    public static boolean barVisible(ItemStack stack) {
        return has(stack);
    }

    public static int barWidth(ItemStack stack) {
        int c = capacity(stack);
        return c <= 0 ? 0 : Math.round(13.0F * stored(stack) / (float) c);
    }

    public static void tooltip(ItemStack stack, List<Component> tooltip) {
        tooltip.add(Component.translatable("item.overgrown_vegetation.ley_crystal.charge", LeyText.fe(stored(stack)), LeyText.fe(capacity(stack)))
                .withStyle(stored(stack) > 0 ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("item.overgrown_vegetation.ley_crystal.hint").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** 設計値を読む口(クラスの読み込み順に依らない)。 */
    private static final class DataHolder {
        static int pourPerClick() {
            return com.yayuy.overgrownvegetation.data.DesignData.gearTiers().crystal.pourPerClick;
        }
    }
}
