package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.guide.Guidebook;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * ガイドブック(2026-10-15、ユーザー指定)。右クリックで開く。中身(どの Mob に出会ったか・数値・ドロップ)はサーバーが
 * 組み立てて {@link com.yayuy.overgrownvegetation.network.OpenGuidebookPayload} で送り、届いたらクライアントが本を開く。
 */
public class GuidebookItem extends Item {

    public GuidebookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayer(serverPlayer, Guidebook.build(serverPlayer));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
