package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.ClientEarthquake;
import com.yayuy.overgrownvegetation.client.ClientResearch;
import com.yayuy.overgrownvegetation.client.ClientShells;
import com.yayuy.overgrownvegetation.client.ClientSiegeBarrage;
import com.yayuy.overgrownvegetation.client.ClientSiegeBoss;
import com.yayuy.overgrownvegetation.client.ClientSiegeCry;
import com.yayuy.overgrownvegetation.entity.SiegeCannonSeatEntity;
import com.yayuy.overgrownvegetation.research.ResearchLogic;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * このMODの独自ネットワークパケットの登録。
 * <ul>
 *   <li>{@link EarthquakeShakePayload} — 地割れの画面揺れ(S2C)</li>
 *   <li>{@link SiegeBossStatePayload} — 超大型レイドボス(攻城戦)の軌道と体力(S2C)</li>
 *   <li>{@link ShellFiredPayload} / {@link ShellImpactPayload} — 砲台の砲弾の発射・着弾(S2C)</li>
 *   <li>{@link SiegeBarragePayload} — 触手の小ビームの乱射 1 発(S2C)</li>
 *   <li>{@link SiegeBossCryPayload} — ボスの悲鳴(S2C)</li>
 *   <li>{@link ResearchSyncPayload} — 研究の進捗 + ツリー定義(S2C)</li>
 *   <li>{@link PlantArmorGrowthPayload} — 寄生植物装備の装着時間(成長の進み、S2C)</li>
 *   <li>{@link MobVariantPayload} — 亜種の種別(S2C、追跡開始時に 1 回)</li>
 *   <li>{@link ErosionPayload} — 浸食度の % (S2C、整数 % が変わったとき・追跡開始時)</li>
 *   <li>{@link OvergrownLayerPayload} — 植物に覆われたマスの記録 (S2C、チャンクを見始めたとき・覆われたとき)</li>
 *   <li>{@link ResearchUnlockPayload} — 研究ノードの開放要求(C2S)</li>
 *   <li>{@link CannonControlPayload} — 砲台に乗っている間の発射・装薬(C2S)</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModNetwork {

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(
                EarthquakeShakePayload.TYPE,
                EarthquakeShakePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientEarthquake.onPayload(payload)));

        registrar.playToClient(
                SiegeBossStatePayload.TYPE,
                SiegeBossStatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientSiegeBoss.onPayload(payload)));

        registrar.playToClient(
                ShellFiredPayload.TYPE,
                ShellFiredPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientShells.onFired(payload)));

        registrar.playToClient(
                ShellImpactPayload.TYPE,
                ShellImpactPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientShells.onImpact(payload)));

        registrar.playToClient(
                SiegeBarragePayload.TYPE,
                SiegeBarragePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientSiegeBarrage.onShot(payload)));

        registrar.playToClient(
                SiegeBossCryPayload.TYPE,
                SiegeBossCryPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientSiegeCry.onPayload(payload)));

        registrar.playToClient(
                PlantArmorGrowthPayload.TYPE,
                PlantArmorGrowthPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> com.yayuy.overgrownvegetation.client.ClientPlantArmor.accept(payload)));

        registrar.playToClient(
                ResearchSyncPayload.TYPE,
                ResearchSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientResearch.accept(payload)));

        registrar.playToClient(
                MobVariantPayload.TYPE,
                MobVariantPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> com.yayuy.overgrownvegetation.client.MobVariantTextures.accept(payload)));

        registrar.playToClient(
                ErosionPayload.TYPE,
                ErosionPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.ClientErosion.accept(payload)));

        registrar.playToClient(
                OvergrownLayerPayload.TYPE,
                OvergrownLayerPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.ClientOvergrownLayer.accept(payload)));

        registrar.playToServer(
                CannonControlPayload.TYPE,
                CannonControlPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer sp) {
                        SiegeCannonSeatEntity.control(sp, payload.action(), payload.value());
                    }
                }));

        registrar.playToServer(
                ResearchUnlockPayload.TYPE,
                ResearchUnlockPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer sp) {
                        ResearchLogic.tryUnlock(sp, payload.nodeId());
                    }
                }));
    }

    private ModNetwork() {
    }
}
