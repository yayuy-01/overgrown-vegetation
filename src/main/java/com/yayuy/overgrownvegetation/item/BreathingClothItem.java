package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.spore.SporeExposure;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 「濾し布の呼吸覆い」(2026-10-15、サバイバル用アイテム ㉞。汚染の前から作れる)。布を口と鼻に巻き、木炭を挟んで胞子をこす手作りの覆い。
 * 追加防具・頭の枠({@link ExtraArmor.Part#HEAD})に付ける。
 * <ul>
 *     <li>胞子濃度でかかるまでの 30 秒が 60 秒になる({@code breathingClothDwellFactor}、ユーザー指定)。かかってからの進行には効かない。</li>
 *     <li>濃い胞子(かかる濃度 50% 以上)の中にいる間だけ耐久が減り、尽きたら作り直す(ユーザー指定)。5 秒に 1・耐久 240 で 20 分(仮決め)。</li>
 *     <li>防護マスク(バニラの頭の枠)をかぶっている間はすり減らない(ユーザー指定)。</li>
 *     <li>防御力は無し(ユーザー指定)。防護マスク(バニラの頭の枠。かぶっている間はかからない)の手前の品。</li>
 * </ul>
 */
public class BreathingClothItem extends Item implements ExtraArmorItem {

    /** 耐久(5 秒に 1 減って 20 分。仮決め)。 */
    public static final int DURABILITY = 240;

    private static final net.minecraft.resources.ResourceLocation TEXTURE =
            com.yayuy.overgrownvegetation.OvergrownVegetation.id("textures/models/armor/breathing_cloth.png");

    public BreathingClothItem(Properties properties) {
        super(properties);
    }

    @Override
    public ExtraArmor.Part extraArmorPart() {
        return ExtraArmor.Part.HEAD;
    }

    @Override
    public net.minecraft.resources.ResourceLocation wornTexture() {
        return TEXTURE;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, java.util.List<net.minecraft.network.chat.Component> tooltip,
                                net.minecraft.world.item.TooltipFlag flag) {
        tooltip.add(net.minecraft.network.chat.Component.translatable("item.overgrown_vegetation.extra_armor.head")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    /** かかるまでの時間に掛ける倍率(付けていなければ 1)。 */
    public static double dwellFactor(Player player) {
        return isWorn(player) ? DesignData.effects().playerInfection().breathingClothDwellFactor() : 1.0D;
    }

    /** 追加防具・頭の枠に付けているか。 */
    public static boolean isWorn(Player player) {
        return ExtraArmor.isWearing(player, ExtraArmor.Part.HEAD, ModItems.BREATHING_CLOTH.get());
    }

    /**
     * 濃い胞子の中にいた {@code interval} tick ぶん、耐久を減らす({@code effect/SporeInfectionTracker} から)。
     * 尽きたら壊れる音を鳴らして枠を空にする。
     */
    public static void wear(ServerLevel level, Player player, SporeExposure exposure, int interval) {
        if (!isWorn(player) || player.getAbilities().instabuild || ProtectiveMaskItem.isWearing(player)) {
            // 防護マスクをかぶっている間はもうかからないので、呼吸覆いは使われない(すり減らない。10-15 ユーザー指定)。
            return;
        }
        int every = DesignData.effects().playerInfection().breathingClothWearIntervalTicks();
        exposure.clothWearTicks += interval;
        while (exposure.clothWearTicks >= every) {
            exposure.clothWearTicks -= every;
            ItemStack stack = ExtraArmor.worn(player, ExtraArmor.Part.HEAD);
            stack.hurtAndBreak(1, level, player, item -> level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.WOOL_BREAK, SoundSource.PLAYERS, 1.0F, 0.8F));
            if (stack.isEmpty()) {
                ExtraArmor.of(player).set(ExtraArmor.Part.HEAD.ordinal(), ItemStack.EMPTY);
                ExtraArmor.broadcast(player);
                exposure.clothWearTicks = 0;
                return;
            }
        }
    }
}
