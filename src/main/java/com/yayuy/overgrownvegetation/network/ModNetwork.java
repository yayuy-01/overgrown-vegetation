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
        PayloadRegistrar registrar = event.registrar("18");
        // 胞子の粉の見た目(場の更新ごと、変わっていれば。2026-10-23)。
        registrar.playToClient(
                SporeDustPayload.TYPE,
                SporeDustPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> com.yayuy.overgrownvegetation.client.ClientSporeDust.accept(payload)));
        // 卓越風の入力(入ったとき・雨と雷が切り替わったとき。2026-10-23)。
        registrar.playToClient(
                PrevailingWindPayload.TYPE,
                PrevailingWindPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> com.yayuy.overgrownvegetation.client.ClientPrevailingWind.accept(payload)));
        // 雲の丸い穴(空の巨大生物が雲海へ潜った所。オーバーワールドの全員へ。2026-10-19)。
        registrar.playToClient(
                CloudHolePayload.TYPE,
                CloudHolePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> com.yayuy.overgrownvegetation.client.CloudHoles.onHole(payload)));
        // 空の巨大生物の技の弾(撃った・終わった。クライアントは同じ式で飛ばして描く。2026-10-19)。
        registrar.playToClient(
                SkyBossShotPayload.TYPE,
                SkyBossShotPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> com.yayuy.overgrownvegetation.client.skyboss.ClientSkyBoss.onShot(payload)));
        // 吐いた雲(空の巨大生物が雲を吐いて隠れる。オーバーワールドの全員へ。2026-10-19)。
        registrar.playToClient(
                CloudPuffPayload.TYPE,
                CloudPuffPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> com.yayuy.overgrownvegetation.client.CloudPuffs.onPayload(payload)));
        // 空の巨大生物の放電の雷 1 本(サーバーが当たりを決め、クライアントは描くだけ。2026-10-19)。
        registrar.playToClient(
                SkyBossArcPayload.TYPE,
                SkyBossArcPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> com.yayuy.overgrownvegetation.client.skyboss.ClientSkyBoss.onArc(payload)));
        // 空の巨大生物の弱点の壊れ具合(2026-10-18 夜)。
        registrar.playToClient(
                SkyBossPartsPayload.TYPE,
                SkyBossPartsPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> com.yayuy.overgrownvegetation.client.skyboss.ClientSkyBoss.onParts(payload)));
        // 空の巨大生物の動き(距離を問わずオーバーワールドの全員へ。クライアントが同じ式で道を引く。2026-10-18)。
        registrar.playToClient(
                SkyBossStatePayload.TYPE,
                SkyBossStatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> com.yayuy.overgrownvegetation.client.skyboss.ClientSkyBoss.onPayload(payload)));
        // 積乱雲の中を走る雷(触れたら即死。サーバーが決めて当たりも調べ、クライアントは描くだけ。2026-10-17)。
        registrar.playToClient(
                CloudBoltPayload.TYPE,
                CloudBoltPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> com.yayuy.overgrownvegetation.client.Thunderstorms.onBolt(payload)));
        registrar.playToClient(
                EarthquakeShakePayload.TYPE,
                EarthquakeShakePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientEarthquake.onPayload(payload)));

        registrar.playToClient(
                SiegeBossStatePayload.TYPE,
                SiegeBossStatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientSiegeBoss.onPayload(payload)));

        // TD の行進中の群れ(遠景の身代わり、2026-09-25)。
        registrar.playToClient(
                TdHordePayload.TYPE,
                TdHordePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.horde.ClientHorde.onPayload(payload)));

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

        // 胞子感染で死んだ Mob が死体の姿勢へ崩れ落ち始めた(2026-10-09)。
        registrar.playToClient(
                CorpseCollapsePayload.TYPE,
                CorpseCollapsePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.corpse.ClientCorpseCollapse.onPayload(payload)));

        // 追加防具の 4 枠の中身(見た目を周りへ配る。2026-10-15)。
        registrar.playToClient(
                ExtraArmorPayload.TYPE,
                ExtraArmorPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.ClientExtraArmor.accept(payload)));

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

        // TD の群れを霧の外で生ませるための、見える距離(2026-09-26)。
        registrar.playToServer(
                HordeSightPayload.TYPE,
                HordeSightPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer sp) {
                        com.yayuy.overgrownvegetation.td.TdSight.report(sp, payload.blocks());
                    }
                }));

        // 背負った帆布の背嚢を開くキー(2026-10-16)。
        registrar.playToServer(
                BackpackOpenPayload.TYPE,
                BackpackOpenPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer sp) {
                        net.minecraft.world.item.ItemStack worn = com.yayuy.overgrownvegetation.item.ExtraArmor.worn(sp,
                                com.yayuy.overgrownvegetation.item.ExtraArmor.Part.BODY);
                        if (worn.getItem() instanceof com.yayuy.overgrownvegetation.item.CanvasBackpackItem) {
                            com.yayuy.overgrownvegetation.item.CanvasBackpackItem.open(sp, worn,
                                    com.yayuy.overgrownvegetation.menu.BackpackMenu.WORN);
                        }
                    }
                }));

        // 紐に吊るした物を素手で外す(何も無い所への右クリック。2026-10-22)。
        registrar.playToServer(
                RopeTakePayload.TYPE,
                RopeTakePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer sp) {
                        com.yayuy.overgrownvegetation.defense.ClapperInteractions.takeLookedAt(sp);
                    }
                }));

        // 腰に下げた陽溜まりのランタンを灯す・消すキー(2026-10-16)。
        registrar.playToServer(
                SunlitLanternTogglePayload.TYPE,
                SunlitLanternTogglePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer sp) {
                        com.yayuy.overgrownvegetation.defense.SunlitLanterns.toggleWorn(sp);
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

        // パワードアーマー作成テーブルの「作成」(順 1 の着手順 5、2026-10-03)。
        registrar.playToServer(
                PoweredArmorCraftPayload.TYPE,
                PoweredArmorCraftPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer sp) {
                        com.yayuy.overgrownvegetation.crafting.PoweredArmorTableCrafting.craft(sp, payload.recipeId());
                    }
                }));

        // ワールドごとの難易度(ポーズ画面の葉っぱのボタン、2026-09-25)。
        registrar.playToServer(
                WorldDifficultyRequestPayload.TYPE,
                WorldDifficultyRequestPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer sp) {
                        com.yayuy.overgrownvegetation.difficulty.WorldDifficultySync.sendTo(sp);
                    }
                }));

        registrar.playToServer(
                WorldDifficultyApplyPayload.TYPE,
                WorldDifficultyApplyPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer sp) {
                        com.yayuy.overgrownvegetation.difficulty.WorldDifficultySync.apply(sp, payload);
                    }
                }));

        registrar.playToClient(
                WorldDifficultySyncPayload.TYPE,
                WorldDifficultySyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.difficulty.ClientWorldDifficulty.accept(payload)));

        // 実験的要素のオン・オフ(2026-10-09。ログインと切り替えのとき)。
        registrar.playToClient(
                ExperimentalFeaturesPayload.TYPE,
                ExperimentalFeaturesPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.difficulty.ClientExperimentalFeatures.accept(payload)));

        // ガイドブックを開く(2026-10-15。本を右クリックしたとき)。
        registrar.playToClient(
                OpenGuidebookPayload.TYPE,
                OpenGuidebookPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.guide.ClientGuidebook.open(payload)));

        // 地脈の通り道(2026-10-16。チャンクを見始めたとき。過繁茂の森で時々光が流れる)。
        registrar.playToClient(
                LeyFlowPayload.TYPE,
                LeyFlowPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.LeyFlow.accept(payload)));
    }

    private ModNetwork() {
    }
}
