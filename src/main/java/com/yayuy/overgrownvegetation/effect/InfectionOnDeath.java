package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.difficulty.DifficultySetting;
import com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.yayuy.overgrownvegetation.port.attachment.OvAttach;

/**
 * 死んでも感染が残る(MOD の設定 {@link DifficultySetting#KEEP_INFECTION}。2026-11-19 第 3 便のユーザー決定。既定オフ、Nightmare でオン)。
 *
 * <p>今は死ぬと必ず治る(感染の効果は新しい体へ写らず、段階の進み {@code SPORE_STAGE_TICKS} も copyOnDeath しない)。
 * オンなら、死んだときの段階と進みを覚え、復活したときに効果と添付を戻す【仮: 残すのはこの 2 つだけ】。
 * 暴露量(SPORE_EXPOSURE)と疲労は 0 から(既存の決定のまま、ここでは触らない)。
 *
 * <p>覚えるのは死んだとき(トーテムで助かったときは LivingDeathEvent が来ない)。死んだ画面のまま抜けてサーバーが落ちたときのために、
 * 体の写し(Clone)の時点で元の体にまだ効果が残っていればそれも拾う。戻すのは復活の後(体が世界に入ってから。効果の表示を送るため)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class InfectionOnDeath {

    /** 死んだときの段階(1〜)と段階の進み。 */
    public record Kept(int stage, int progress) {
    }

    private static final Map<UUID, Kept> PENDING = new ConcurrentHashMap<>();

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Player player) || player.level().isClientSide()) return;
        MinecraftServer server = player.getServer();
        if (server == null || !WorldDifficultyData.on(server, DifficultySetting.KEEP_INFECTION)) return;
        Kept kept = capture(player);
        if (kept != null) PENDING.put(player.getUUID(), kept);
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath() || event.getEntity().level().isClientSide()) return;
        Player original = event.getOriginal();
        if (PENDING.containsKey(original.getUUID())) return;
        MinecraftServer server = original.getServer();
        if (server == null || !WorldDifficultyData.on(server, DifficultySetting.KEEP_INFECTION)) return;
        Kept kept = capture(original);
        if (kept != null) PENDING.put(original.getUUID(), kept);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered() || event.getEntity().level().isClientSide()) return;
        Kept kept = PENDING.remove(event.getEntity().getUUID());
        if (kept != null) restore(event.getEntity(), kept);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }

    /** 今の感染(段階と進み)。未感染なら null。 */
    @Nullable
    public static Kept capture(Player player) {
        int stage = SporeInfectionEffects.stageOf(player);
        return stage <= 0 ? null : new Kept(stage, SporeInfectionEffects.progressOf(player));
    }

    /** 覚えた感染を体に戻す(効果・防御と最大体力の修飾・段階の進み)。 */
    public static void restore(Player player, Kept kept) {
        SporeInfectionEffects.setPlayerStage(player, kept.stage());
        OvAttach.setData(player, ModAttachments.SPORE_STAGE_TICKS, Math.max(0, kept.progress()));
    }

    private InfectionOnDeath() {
    }
}
