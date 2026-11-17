package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.ClientEarthquake;
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
 *   <li>{@link TdHordePayload} — TD の行進中の群れ(遠景の身代わり、S2C、帳簿のたび)</li>
 *   <li>{@link ShellFiredPayload} / {@link ShellImpactPayload} — 砲台の砲弾の発射・着弾(S2C)</li>
 *   <li>{@link SiegeBarragePayload} — 触手の小ビームの乱射 1 発(S2C)</li>
 *   <li>{@link SiegeBossCryPayload} — ボスの悲鳴(S2C)</li>
 *   <li>{@link ResearchSyncPayload} — 研究の進捗 + ツリー定義(S2C)</li>
 *   <li>{@link PlantArmorGrowthPayload} — 寄生植物装備の装着時間(成長の進み、S2C)</li>
 *   <li>{@link MobVariantPayload} — 亜種の種別(S2C、追跡開始時に 1 回)</li>
 *   <li>{@link ErosionPayload} — 浸食度の % (S2C、整数 % が変わったとき・追跡開始時)</li>
 *   <li>{@link ExtraArmorPayload} — 追加防具の 4 枠の中身(S2C、枠が変わったとき・追跡開始時。見た目用)</li>
 *   <li>{@link OvergrownLayerPayload} — 植物に覆われたマスの記録 (S2C、チャンクを見始めたとき・覆われたとき)</li>
 *   <li>{@link SporeAirPayload} — プレイヤーの所の胞子濃度(S2C、5 秒ごと。密林の雰囲気の光の筋の濃さ)</li>
 *   <li>{@link SporeAirMapPayload} — まわり ±24 チャンクの浮遊の濃度(S2C、5 秒ごとに変わったとき。胞子の空気の霞、2026-11-06)</li>
 *   <li>{@link SporeDustPayload} — 胞子の粉の降り積もり %と払った升の印(S2C、場の更新で変わったとき)</li>
 *   <li>{@link SporeExposurePayload} — 自分の胞子の暴露量(S2C、1/32 刻みが変わったとき。前触れの画面のにじみ、2026-11-04)</li>
 *   <li>{@link ResearchUnlockPayload} — 研究ノードの開放要求(C2S)</li>
 *   <li>{@link PoweredArmorCraftPayload} — パワードアーマー作成テーブルの「作成」(C2S、レシピ id)</li>
 *   <li>{@link CannonControlPayload} — 砲台に乗っている間の発射・装薬(C2S)</li>
 *   <li>{@link HordeSightPayload} — 見える距離(描画距離と霧の終わりの近い方。TD の群れを霧の外で生ませる、C2S、変わったとき)</li>
 *   <li>{@link WorldDifficultyRequestPayload} / {@link WorldDifficultyApplyPayload} — ポーズ画面の難易度の画面を開く・完了(C2S)、
 *   {@link WorldDifficultySyncPayload} — その返事(S2C)</li>
 *   <li>{@link ExperimentalFeaturesPayload} — 実験的要素のオン・オフ(S2C、ログインと切り替えのとき)</li>
 *   <li>{@link OpenGuidebookPayload} — ガイドブックを開く(S2C、本を右クリックしたとき。Mob の章の中身)</li>
 *   <li>{@link LeyFlowPayload} — 地脈の通り道(S2C、線の通るチャンクを見始めたとき。過繁茂の森で時々光が流れる)</li>
 *   <li>{@link SkyBossStatePayload} — 空の巨大生物の動き(S2C、区間が変わるとき・入ってきたとき・雷が避雷針へ落ちるとき。オーバーワールドの全員へ)</li>
 *   <li>{@link SkyBossPartsPayload} — 空の巨大生物の弱点(避雷針・蕾)の壊れ具合(S2C、壊れた・生え直したとき・入ってきたとき)</li>
 *   <li>{@link SkyBossArcPayload} — 空の巨大生物の放電の雷 1 本(S2C、範囲の中の相手に当たったとき。近くのプレイヤーへ)</li>
 *   <li>{@link SkyBossShotPayload} — 空の巨大生物の技の弾(S2C、撃った・終わったとき。近くのプレイヤーへ)</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModNetwork {

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        // 版は 2026-10-10 に 2 へ(苔の記録の通信に「消す」を足した。違う版の mod どうしは接続の時点ではじかれる)。
        // 2026-10-15 に 3 へ(ガイドブックを開く通信を足した)。2026-10-16 に 4 へ(地脈の通り道を足した)。2026-10-17 に 5 へ(積乱雲の中を走る雷を足した)。
        // 2026-10-18 に 6 へ(空の巨大生物の動きを足した)。2026-10-18 夜に 7 へ(空の巨大生物の弱点の壊れ具合を足した)。
        // 2026-10-19 に 8 へ(空の巨大生物の動きに、転がり・速さの目標・雷が落ちる時刻などの追加の値を足した)。
        // 2026-10-19 に 9 へ(空の巨大生物の放電の雷を足した)。2026-10-19 に 10 へ(吐いた雲を足した)。2026-10-19 に 11 へ(空の巨大生物の技の弾を足した)。
        // 2026-10-19 に 12 へ(雲の丸い穴を足し、吐いた雲に膨らむ長さ・消える長さを足した)。
        // 2026-10-23 に 13 へ(卓越風の入力を足した。胞子の場と風 第 2 段)。
        // 2026-10-23 に 14 へ(胞子の粉の見た目を足した)。
        // 2026-10-23 に 15 へ(胞子の粉にシャベルで払った升の印を足した)。
        // 2026-10-23 に 16 へ(胞子の粉に浮遊の濃度・プレイヤーの所の濃度・巨大樹の梢を足した。緑の霧とプルーム)。
        // 2026-10-30 に 19 へ(胞子の粉の見た目と卓越風の入力の通信を画面の演出と一緒に parked/ へ退避)。
        // 2026-10-30 夜に 20 へ(プレイヤーの所の胞子濃度を足した。密林の雰囲気の光の筋の濃さ)。
        // 2026-10-30 夜に 21 へ(胞子濃度の通信に卓越風の向きと強さを相乗りさせた。密林の雰囲気 第 2 段の風)。
        // 2026-10-31 に 22 へ(胞子の粉の見た目の通信を戻した。合間の小物 3)。
        // 2026-11-01 に 23 へ(這い上がる苔の記録 CoverCreepPayload を足した)。
        // 2026-11-04 に 24 へ(自分の胞子の暴露量 SporeExposurePayload を足した。前触れの画面のにじみ)。
        // 2026-11-08 に 25 へ(雲を流した距離 CloudDriftPayload を足した。雲の段階 1「風を 1 つに」)。
        // 2026-11-08 に 26 へ(雰囲気の出来事の確かめ AtmosphereFxPayload を足した。G4 胞子の風・G5 光る胞子の夜の /ov fx)。
        // 2026-11-09 に 27 へ(雲を流した距離に積乱雲の形の設計値を足した。雲の段階 2a)。
        // 2026-11-09 に 28 へ(雲を流した距離に天気の設計値と、雨の予定で天気を決めている間かを足した。雲の段階 2a-5)。
        // 2026-11-10 に 29 へ(ネザーの植物が適応したか HeatForestClockPayload を足した。熱水の森の覆いの絵を 10 分の前後で分ける)。
        // 2026-11-11 U13 で HeatForestClockPayload は parked へ退避(版は 29 のまま)。
        // 2026-11-16 に 30 へ(今の天気 WeatherPayload・汚染の森への落雷 ForestLightningPayload を足した。weather-system)。
        // 2026-11-16 に 31 へ(積乱雲から地面への落雷 CloudStrikePayload を足した。weather-system)。
        // 2026-11-16 に 32 へ(今のワールドイベント WorldEventPayload を足した。world-events)。
        // 2026-11-17 に 33 へ(まわりの升の G2・G3 の賑わい EcoCellsPayload を足した。見た目だけの小さな生き物)。
        PayloadRegistrar registrar = event.registrar("33");
        registrar.playToClient(
                EcoCellsPayload.TYPE,
                EcoCellsPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.atmosphere.EcoCellsClient.accept(payload)));
        registrar.playToClient(
                WorldEventPayload.TYPE,
                WorldEventPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.worldevent.WorldEventClient.accept(payload)));
        registrar.playToClient(
                CloudStrikePayload.TYPE,
                CloudStrikePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.atmosphere.WeatherClient.acceptStrike(payload)));
        registrar.playToClient(
                WeatherPayload.TYPE,
                WeatherPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.atmosphere.WeatherClient.accept(payload)));
        registrar.playToClient(
                ForestLightningPayload.TYPE,
                ForestLightningPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.atmosphere.WeatherClient.acceptLightning(payload)));
        registrar.playToClient(
                AtmosphereFxPayload.TYPE,
                AtmosphereFxPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.atmosphere.AtmosphereFx.accept(payload)));
        registrar.playToClient(
                CloudDriftPayload.TYPE,
                CloudDriftPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.sky.CloudDriftClient.accept(payload)));

        registrar.playToClient(
                EarthquakeShakePayload.TYPE,
                EarthquakeShakePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientEarthquake.onPayload(payload)));

        registrar.playToClient(
                OvergrownLayerPayload.TYPE,
                OvergrownLayerPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.ClientOvergrownLayer.accept(payload)));

        registrar.playToClient(
                CoverCreepPayload.TYPE,
                CoverCreepPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.ClientOvergrownLayer.acceptCreep(payload)));

        registrar.playToClient(
                SporeAirPayload.TYPE,
                SporeAirPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.atmosphere.PrevailingWindClient.accept(payload)));

        registrar.playToClient(
                SporeAirMapPayload.TYPE,
                SporeAirMapPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.atmosphere.SporeAir.accept(payload)));

        registrar.playToClient(
                LeyVeinMapPayload.TYPE,
                LeyVeinMapPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.atmosphere.LeyVeins.accept(payload)));

        registrar.playToClient(
                SporeDustPayload.TYPE,
                SporeDustPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.ClientSporeDust.accept(payload)));

        registrar.playToClient(
                SporeExposurePayload.TYPE,
                SporeExposurePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.SporeExposureClient.accept(payload)));

        // まわりの巨大樹(2026-11-06 胞子の柱・夜の地脈の光。S2C、5 秒ごとに変わったとき)
        registrar.playToClient(
                GiantTreesPayload.TYPE,
                GiantTreesPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.atmosphere.GiantTrees.accept(payload)));

    }

    private ModNetwork() {
    }
}
