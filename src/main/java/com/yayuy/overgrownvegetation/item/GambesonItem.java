package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 「布鎧(ギャンベゾン)」(2026-10-15、サバイバル用アイテム ㉟。汚染の前から作れる)。羊毛を革で挟んで縫い重ねた厚い布の鎧。
 * 追加防具・体の枠({@link ExtraArmor.Part#BODY})に付けるので、ふつうの胸当ての下に重ねて着られる。
 * <ul>
 *     <li>防御 2(ユーザー指定)。</li>
 *     <li>着ている間は、過繁茂した Mob の攻撃で胞子にかかる確率が 30% → 15%(ユーザー指定。{@code gambesonAttackInfectChance})。</li>
 *     <li>ふつうの防具と同じく、防具の効く一撃ですり減る(耐久 160。仮決め。{@link ExtraArmorEvents#onDamaged})。</li>
 * </ul>
 */
public class GambesonItem extends Item implements ExtraArmorItem {

    /** 耐久(仮決め。革の胸当て 80 と鉄の 240 の間)。 */
    public static final int DURABILITY = 160;
    /** 防御力(ユーザー指定)。 */
    public static final int DEFENSE = 2;

    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/models/armor/gambeson.png");

    public GambesonItem(Properties properties) {
        super(properties);
    }

    /** 体の追加防具の枠に着ているか。 */
    public static boolean isWorn(Player player) {
        return ExtraArmor.isWearing(player, ExtraArmor.Part.BODY, ModItems.GAMBESON.get());
    }

    @Override
    public ExtraArmor.Part extraArmorPart() {
        return ExtraArmor.Part.BODY;
    }

    @Override
    public int extraArmorDefense() {
        return DEFENSE;
    }

    @Override
    public ResourceLocation wornTexture() {
        return TEXTURE;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.overgrown_vegetation.extra_armor.body").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.overgrown_vegetation.extra_armor.defense", DEFENSE).withStyle(ChatFormatting.BLUE));
    }
}
