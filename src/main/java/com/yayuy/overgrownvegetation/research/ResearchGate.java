package com.yayuy.overgrownvegetation.research;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.difficulty.ExperimentalFeatures;
import com.yayuy.overgrownvegetation.registry.ModResearch;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.level.BlockEvent;

import java.util.Set;

/**
 * 実験的要素の「研究」(2026-11-23 夜 ユーザー決定「研究で解除する系は最初から作れるようにする」)。オフのワールドでは:
 * <ul>
 *     <li>研究の道具(研究台・拡大鏡・地脈の共鳴器)は作れない({@link Research#recipeLocked})・置けない({@link #onPlace})。</li>
 *     <li>研究で開く作り方は最初から全部作れる({@link Research#recipeLocked})。</li>
 *     <li>置いてある研究台は開かず一言出す({@code ResearchBenchBlock})。進めていた研究は止まるだけで、オンに戻すと続きから。</li>
 * </ul>
 * 物は消さない(持っている道具・置いてある台・開いた節はそのまま)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class ResearchGate {

    /** 研究の道具の作り方(オフで閉じる)。 */
    public static final Set<ResourceLocation> GEAR_RECIPES = Set.of(
            OvergrownVegetation.id("research_bench"),
            OvergrownVegetation.id("research_lens"),
            OvergrownVegetation.id("ley_resonator"));

    private ResearchGate() {
    }

    public static boolean on(MinecraftServer server) {
        return ExperimentalFeatures.enabled(server, ExperimentalFeatures.Feature.RESEARCH);
    }

    /** サーバーならワールドの値、クライアントならサーバーから届いた値。 */
    public static boolean on(Level level) {
        return ExperimentalFeatures.enabled(level, ExperimentalFeatures.Feature.RESEARCH);
    }

    /** 研究の道具のブロックか。 */
    public static boolean isGear(BlockState state) {
        return state.is(ModResearch.BENCH.get()) || state.is(ModResearch.LENS.get()) || state.is(ModResearch.RESONATOR.get());
    }

    /** オフのワールドでは研究の道具を置けない(手に戻る)。 */
    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        LevelAccessor la = event.getLevel();
        if (la.isClientSide() || !(la instanceof Level level) || !isGear(event.getPlacedBlock()) || on(level)) return;
        event.setCanceled(true);
        if (event.getEntity() instanceof Player player) {
            player.displayClientMessage(Component.translatable("research.overgrown_vegetation.experimental_off"), true);
            // 手の物はサーバーで戻るが、クライアントは減ったと思っているので送り直す
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) sp.server.tell(new net.minecraft.server.TickTask(sp.server.getTickCount(), () -> sp.inventoryMenu.sendAllDataToRemote())); // 戻した後に
        }
    }
}
