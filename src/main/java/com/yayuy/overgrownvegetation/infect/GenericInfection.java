package com.yayuy.overgrownvegetation.infect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.GenericInfectionSpec;
import com.yayuy.overgrownvegetation.network.GenericInfectionPayload;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 汎用の感染体(2026-11-15 ユーザー決定。専用の G1 が無い生き物が、生きたまま植物側に変わる)の土台: 変わった印・保存・クライアントへの同期。
 * 正本は Vault「G1 媒介種(寄生された動物)」の「汎用の感染体」節、数は {@code spore_field.json} の {@code genericInfection}。
 *
 * <h2>区切り 1(この版)</h2>
 * <ul>
 *     <li>印 {@link GenericInfected}(乱数の種・変わり始めた時刻・変わる動きの長さ・ボスか)を保存し、追跡しているクライアントへ送る
 *     ({@link GenericInfectionPayload} → {@code client.infect.GenericInfectionClient})。追跡を始めたクライアント(後から近づいた・入り直した)にも送る。
 *     消えたときはクライアントの側が自分で消す({@code InfectClientEvents#onLeave})ので、送るのは戻したときだけ。</li>
 *     <li>変わった Mob は植物側({@link SporeExposureTracker#isPlantSide} が真)= 暴露量を測らない・G1 の技が当たらない。</li>
 *     <li>確かめ用の命令 {@code /ov perf infection transform|revert|list}({@code spore/MobInfectionCommand})。</li>
 * </ul>
 * まだ無い物(区切り 2 から): 20 ダメージで死ぬ所で変わる・変わる間の止まり・狙う相手の差し替え・汎用の攻撃・ボスの体力・声・呼び寄せ・死んだとき。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class GenericInfection {

    private GenericInfection() {
    }

    public static GenericInfectionSpec spec() {
        return DesignData.sporeField().genericInfection();
    }

    /** 変わったか(変わっている途中も含む)。 */
    public static boolean isTurned(Entity entity) {
        return entity.hasData(ModAttachments.GENERIC_INFECTED) && entity.getData(ModAttachments.GENERIC_INFECTED).turned;
    }

    /** 変わる動きの途中か。 */
    public static boolean isTransforming(Entity entity) {
        return entity.hasData(ModAttachments.GENERIC_INFECTED)
                && entity.getData(ModAttachments.GENERIC_INFECTED).transforming(entity.level().getGameTime());
    }

    /**
     * 汎用の感染体になれる Mob か: 生きている Mob(プレイヤー以外)・まだ植物側でない・専用の G1 が無い
     * ({@link G1Species#ofVictim} が無く、表の種の子どもでもない = 子どもは今までどおり萌芽体【仮】)・{@code excludedEntities} に無い。
     */
    public static boolean canTurn(LivingEntity entity) {
        GenericInfectionSpec s = spec();
        if (!s.enabled() || !(entity instanceof Mob) || entity instanceof Player || !entity.isAlive()) return false;
        if (SporeExposureTracker.isPlantSide(entity)) return false;
        if (G1Species.ofVictim(entity) != null || G1Species.isChildOfListedSpecies(entity)) return false;
        return !s.excludedEntities().contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
    }

    /** ボスとして扱う種か(タグ {@code c:bosses} か、{@code bossEntities})【仮】。 */
    public static boolean isBossType(LivingEntity entity) {
        return entity.getType().is(Tags.EntityTypes.BOSSES)
                || spec().bossEntities().contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
    }

    /**
     * 変える。{@code instant} なら変わる動きを飛ばす(確かめ用)。なれない Mob・もう変わった Mob には何もしない。
     *
     * @return 変えたか
     */
    public static boolean turn(LivingEntity entity, boolean instant) {
        if (!canTurn(entity)) return false;
        GenericInfected g = entity.getData(ModAttachments.GENERIC_INFECTED);
        g.turned = true;
        g.seed = entity.getRandom().nextLong();
        g.startTick = entity.level().getGameTime();
        g.transformTicks = instant ? 0 : spec().transformTicks();
        g.boss = isBossType(entity);
        turnedCount++;
        sync(entity);
        return true;
    }

    /** 戻す(確かめ用)。変わっていなければ何もしない。 */
    public static boolean revert(LivingEntity entity) {
        if (!isTurned(entity)) return false;
        entity.getData(ModAttachments.GENERIC_INFECTED).clear();
        sync(entity);
        return true;
    }

    /** 印の今の様子を、その Mob を追跡しているプレイヤー全員へ送る。 */
    public static void sync(Entity entity) {
        if (entity.level().isClientSide()) return;
        PacketDistributor.sendToPlayersTrackingEntity(entity, payloadOf(entity));
    }

    /** 送る中身(変わっていなければ「消す」)。 */
    public static GenericInfectionPayload payloadOf(Entity entity) {
        if (!isTurned(entity)) return GenericInfectionPayload.removed(entity.getId());
        GenericInfected g = entity.getData(ModAttachments.GENERIC_INFECTED);
        return new GenericInfectionPayload(entity.getId(), true, g.seed, g.startTick, g.transformTicks, g.boss);
    }

    /** 追跡を始めた(Mob を出すパケットの後に来る。{@code ServerEntity#addPairing})→ 変わった Mob ならそのプレイヤーへ送る。 */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        Entity target = event.getTarget();
        if (event.getEntity() instanceof ServerPlayer sp && isTurned(target)) {
            PacketDistributor.sendToPlayer(sp, payloadOf(target));
        }
    }

    /** 数え(/ov perf infection)。起動してから変えた回数。 */
    public static int turnedCount;
}
