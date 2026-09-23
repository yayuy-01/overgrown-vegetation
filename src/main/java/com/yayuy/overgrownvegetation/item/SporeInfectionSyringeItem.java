package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * クリエイティブ専用のデバッグアイテム「胞子感染注射器 I/II/III」。右クリックで<b>自分自身</b>に
 * 指定段階(amplifier {@link #amplifier})の「胞子感染」を<b>強制的に</b>付与する —
 * 「低いグレードはかからない」ルールと防護マスクを無視して、今の段階を取り除いてから付け直す
 * ({@link SporeInfectionEffects#forceSet})。チャンクへの胞子濃度加算・胞子感染3 のバニラ空腹など、
 * 通常経路と同じ付随処理も走るので、効果の動作確認にそのまま使える。
 *
 * <p>クリエイティブモード以外では何もしない(アクションバーにその旨を表示)。レシピは無い。
 */
public class SporeInfectionSyringeItem extends Item {

    private final int amplifier;

    public SporeInfectionSyringeItem(int amplifier, Properties properties) {
        super(properties);
        this.amplifier = amplifier;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel) {
            if (!player.getAbilities().instabuild) {
                player.displayClientMessage(
                        Component.translatable("item.overgrown_vegetation.spore_infection_syringe.creative_only")
                                .withStyle(ChatFormatting.RED), true);
                return InteractionResultHolder.fail(stack);
            }
            boolean applied = SporeInfectionEffects.forceSet(player, amplifier);
            player.displayClientMessage(
                    Component.translatable(applied
                                    ? "item.overgrown_vegetation.spore_infection_syringe.applied"
                                    : "item.overgrown_vegetation.spore_infection_syringe.failed",
                            amplifier + 1).withStyle(applied ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.RED), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.overgrown_vegetation.spore_infection_syringe.tooltip", amplifier + 1)
                .withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
