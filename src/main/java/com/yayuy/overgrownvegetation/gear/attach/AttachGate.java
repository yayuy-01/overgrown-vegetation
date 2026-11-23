package com.yayuy.overgrownvegetation.gear.attach;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.difficulty.ExperimentalFeatures;
import com.yayuy.overgrownvegetation.registry.ModArmorAttach;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * 実験的要素の「防具の付け物」(2026-11-23 夜 ユーザー決定「防具に付ける装飾(口元を覆う布以外)」)。オフのワールドでは:
 * <ul>
 *     <li>付け物の台と、口覆いの布以外の付け物は作れない({@code difficulty.ExperimentalRecipes})。台は置けない({@link #onPlace})。</li>
 *     <li>口覆いの布は作業台で兜と合わせて付ける({@link MouthClothRecipe}。オン・オフに関わらず)。</li>
 * </ul>
 * 物は消さない(持っている付け物・付いている付け物・置いてある台・ベルトの中身はそのまま)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class AttachGate {

    private AttachGate() {
    }

    /** サーバーならワールドの値、クライアントならサーバーから届いた値。 */
    public static boolean on(Level level) {
        return ExperimentalFeatures.enabled(level, ExperimentalFeatures.Feature.ARMOR_ATTACHMENTS);
    }

    /** オフのワールドでは付け物の台を置けない(手に戻る)。 */
    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        LevelAccessor la = event.getLevel();
        if (la.isClientSide() || !(la instanceof Level level) || !event.getPlacedBlock().is(ModArmorAttach.BENCH.get()) || on(level)) return;
        event.setCanceled(true);
        if (event.getEntity() instanceof Player player) {
            player.displayClientMessage(Component.translatable("gear.overgrown_vegetation.attachments_off"), true);
            // 手の物はサーバーで戻るが、クライアントは減ったと思っているので送り直す
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) sp.server.tell(new net.minecraft.server.TickTask(sp.server.getTickCount(), () -> sp.inventoryMenu.sendAllDataToRemote())); // 戻した後に
        }
    }
}
