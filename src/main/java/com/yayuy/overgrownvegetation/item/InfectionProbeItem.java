package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.infect.InfectionProbe;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 感染の試し棒(2026-11-19。確かめ用 —— 中身は {@link InfectionProbe})。殴ると相手を感染の死の道筋で即死させ、結果をチャットに出す。
 * スニーク + 右クリックは {@link InfectionProbe#onEntityInteract}。絵はバニラの棒を流用し、光らせて見分ける。
 */
public class InfectionProbeItem extends Item {

    public InfectionProbeItem(Properties properties) {
        super(properties);
    }

    /** 殴った: 普通の攻撃は止め(true)、サーバーで感染の死を起こす。 */
    @Override
    public boolean onLeftClickEntity(ItemStack stack, Player player, Entity entity) {
        if (player.level() instanceof ServerLevel level) {
            if (!InfectionProbe.mayUse(player)) {
                player.sendSystemMessage(Component.translatable("item.overgrown_vegetation.infection_probe.no_permission").withStyle(ChatFormatting.RED));
                return true;
            }
            InfectionProbe.Outcome o = InfectionProbe.strike(level, entity);
            player.sendSystemMessage(InfectionProbe.message(entity, o, false));
        }
        return true;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.overgrown_vegetation.infection_probe.tooltip.hit").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.overgrown_vegetation.infection_probe.tooltip.sneak").withStyle(ChatFormatting.GRAY));
    }
}
