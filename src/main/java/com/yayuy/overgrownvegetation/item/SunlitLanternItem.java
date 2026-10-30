package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 陽溜まりのランタン(2026-10-16、①)。チラコイト(吸われすぎた光の結晶)に光をためて放つ。Mob は吸いきれないので寄りつかない。
 * <ul>
 *     <li>ユーザー指定: 腰に下げる / 燃料はグロウベリー / G1 と小さな敵が寄りつかない(半径 5 ほど)/ 灯している間だけ燃料が減る。</li>
 *     <li>仮決め: グロウベリー 1 個で 2 分、16 個まで / 極相では光を吸われて 2 倍で減る / 明るさ 15 の動く光 /
 *     手に持って右クリック、または腰に下げたままキー(既定 N)で灯す・消す / 持ち物の画面でグロウベリーを持ってランタンをクリックすると燃料を足す。</li>
 * </ul>
 * 寄りつかなくする仕組みは忌避灯と同じ({@code entity/RepellentAvoidGoal}、{@code defense/SunlitLanterns})。
 */
public class SunlitLanternItem extends Item implements ExtraArmorItem {

    public static final int FUEL_PER_BERRY = 2400;
    public static final int MAX_BERRIES = 16;
    public static final int MAX_FUEL = FUEL_PER_BERRY * MAX_BERRIES;
    public static final int LIGHT = 15;

    public SunlitLanternItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    public static int fuel(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.LANTERN_FUEL.get(), 0);
    }

    public static boolean lit(ItemStack stack) {
        return Boolean.TRUE.equals(stack.get(ModDataComponents.LANTERN_LIT.get()));
    }

    /** 灯っていて燃料がある。 */
    public static boolean burning(ItemStack stack) {
        return lit(stack) && fuel(stack) > 0;
    }

    public static void setFuel(ItemStack stack, int fuel) {
        stack.set(ModDataComponents.LANTERN_FUEL.get(), Math.max(0, Math.min(MAX_FUEL, fuel)));
    }

    public static void setLit(ItemStack stack, boolean lit) {
        stack.set(ModDataComponents.LANTERN_LIT.get(), lit);
    }

    /** 灯す・消す。状態が変わったら true。 */
    public static boolean toggle(ItemStack stack, Player player) {
        if (lit(stack)) {
            setLit(stack, false);
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.sunlit_lantern.off").withStyle(ChatFormatting.GRAY), true);
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.CANDLE_EXTINGUISH, SoundSource.PLAYERS, 0.8F, 1.0F);
            return true;
        }
        if (fuel(stack) <= 0) {
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.sunlit_lantern.no_fuel").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        setLit(stack, true);
        player.displayClientMessage(Component.translatable("message.overgrown_vegetation.sunlit_lantern.on").withStyle(ChatFormatting.GOLD), true);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, 1.4F);
        return true;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide()) {
            toggle(stack, player);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /** 持ち物の画面で、グロウベリーを持ってランタンをクリックすると燃料を足す(入るだけ)。 */
    @Override
    public boolean overrideOtherStackedOnMe(ItemStack stack, ItemStack other, Slot slot, ClickAction action, Player player, SlotAccess access) {
        if (action != ClickAction.PRIMARY || !other.is(Items.GLOW_BERRIES) || !slot.allowModification(player)) {
            return false;
        }
        int room = (MAX_FUEL - fuel(stack)) / FUEL_PER_BERRY;
        int n = Math.min(room, other.getCount());
        if (n > 0) {
            setFuel(stack, fuel(stack) + n * FUEL_PER_BERRY);
            other.shrink(n);
            player.playSound(SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, 0.8F, 1.2F);
        }
        return true;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round(13.0F * fuel(stack) / MAX_FUEL);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0xE8C25A;
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
    public int wornLightLevel(ItemStack stack) {
        return burning(stack) ? LIGHT : 0;
    }

    @Override
    public boolean renderWornItem() {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.overgrown_vegetation.extra_armor.waist").withStyle(ChatFormatting.GRAY));
        int seconds = fuel(stack) / 20;
        tooltip.add(Component.translatable(lit(stack) ? "tooltip.overgrown_vegetation.sunlit_lantern.lit" : "tooltip.overgrown_vegetation.sunlit_lantern.unlit",
                seconds / 60, String.format("%02d", seconds % 60)).withStyle(lit(stack) ? ChatFormatting.GOLD : ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.sunlit_lantern.hint",
                Component.keybind("key.overgrown_vegetation.sunlit_lantern")).withStyle(ChatFormatting.DARK_GRAY));
    }
}
