package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 両手持ちの武器(2026-10-10、ユーザー指定「左手のアイテムを使えないようにする」。タグ {@link ModTags#TWO_HANDED}、今はツヴァイヘンダー 3 本と布のグライダー(2026-10-22))。
 *
 * <p>両手持ちの武器を<b>利き手</b>に持っている間、もう片方の手(オフハンド)の物は使えない: 右クリックで使う(盾を構える・食べる・投げる・弓など)、
 * ブロックに使う(置く・火打石など)、Mob に使う(餌・リードなど)を全部止める。使っている途中で持ち替えたら、そこで止める(盾を構えたまま持ち替えた場合など)。
 * 止めたらアクションバーで知らせる。持っているだけで効く物(不死のトーテムなど)・オフハンドに両手持ちの武器を持ったときは止めない(Claude 仮決め)。
 *
 * <p>クライアントとサーバーの両方で同じ判定をする(どちらも利き手の物を知っている)ので、盾の構えなどが一瞬だけ見えることはない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class TwoHandedRules {

    /** 利き手に両手持ちの武器があり、オフハンドに何か持っているか(= オフハンドを止める)。 */
    public static boolean blocksOffhand(Player player) {
        return player.getMainHandItem().is(ModTags.TWO_HANDED) && !player.getOffhandItem().isEmpty();
    }

    /** オフハンドの物を使っている途中なら止める(毎 tick。GameTest からも呼ぶ)。 */
    public static void enforce(Player player) {
        if (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.OFF_HAND && player.getMainHandItem().is(ModTags.TWO_HANDED)) {
            player.stopUsingItem();
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getHand() == InteractionHand.OFF_HAND && blocksOffhand(event.getEntity())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            tell(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() == InteractionHand.OFF_HAND && blocksOffhand(event.getEntity())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            tell(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() == InteractionHand.OFF_HAND && blocksOffhand(event.getEntity())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getHand() == InteractionHand.OFF_HAND && blocksOffhand(event.getEntity())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    /** 右クリック以外の道から使い始めるとき(念のため)。使い始めの時点では使う手がまだ記録されていないので、物が同じかで見る。 */
    @SubscribeEvent
    public static void onUseStart(LivingEntityUseItemEvent.Start event) {
        if (event.getEntity() instanceof Player player && event.getItem() == player.getOffhandItem() && blocksOffhand(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        enforce(event.getEntity());
    }

    /** ツールチップに「両手持ち」の 1 行(名前のすぐ下)。 */
    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        if (event.getItemStack().is(ModTags.TWO_HANDED) && !event.getToolTip().isEmpty()) {
            event.getToolTip().add(1, Component.translatable("item.overgrown_vegetation.two_handed.tooltip").withStyle(ChatFormatting.GRAY));
        }
    }

    private static void tell(Player player) {
        if (player instanceof ServerPlayer server) {
            server.displayClientMessage(Component.translatable("message.overgrown_vegetation.two_handed"), true);
        }
    }

    private TwoHandedRules() {
    }
}
