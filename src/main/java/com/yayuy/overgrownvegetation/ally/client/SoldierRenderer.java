package com.yayuy.overgrownvegetation.ally.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ally.SoldierEntity;
import com.yayuy.overgrownvegetation.ally.SoldierKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderBuffersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 兵士の描画(2026-10-01)。プレイヤーの体型(太い腕のスティーブ型)に兵種ごとの 64 × 64 のスキン
 * ({@code textures/entity/soldier/<兵種>.png}、{@code tools/soldier-skins} が描く)。手の HK416 は GeckoLib の
 * アイテムの描画がそのまま出る。標的を構えている間は両手で銃を前へ(クロスボウを構える姿勢)。
 */
public class SoldierRenderer extends HumanoidMobRenderer<SoldierEntity, PlayerModel<SoldierEntity>> {

    private static final Map<SoldierKind, ResourceLocation> TEXTURES = new EnumMap<>(SoldierKind.class);

    /**
     * 専用の溜め場所を持たせる描画の種類(兵種ごとの肌 + 手の HK416)。登録({@link #registerBuffers})と
     * 描き出し({@link AfterEntities})は必ずこの並びを使う(2026-10-10、片方だけ直して食い違わないように 1 つにした)。
     */
    private static final List<RenderType> BATCHED_TYPES = new ArrayList<>();

    static {
        for (SoldierKind k : SoldierKind.values()) {
            TEXTURES.put(k, ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "textures/entity/soldier/" + k.id + ".png"));
        }
        for (ResourceLocation skin : TEXTURES.values()) {
            BATCHED_TYPES.add(RenderType.entityTranslucent(skin));
        }
        BATCHED_TYPES.add(RenderType.entityCutoutNoCull(Hk416Renderer.TEXTURE));
    }

    public SoldierRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
    }

    /**
     * 兵士の肌(兵種ごと)と手の HK416 に、専用の描画の溜め場所を持たせる(2026-10-07、FPS の調査)。
     *
     * <p>溜め場所の無い描画の種類は、別の種類が来るたびにそこまでを描き出す。兵士は 1 体ごとに「肌 → GeckoLib の銃」と
     * 種類が替わるので、1 体 2 回ずつ描き出し(そのたびにシェーダーの準備)が起きていた —— 基地の 46 体で 1 コマ約 3.5 ms。
     * 溜め場所があれば全員ぶんを 1 回で描く。見た目は同じ(肌は半透明の種類なので、まとめた中で奥から順に並べ直される)。
     * 描き出すのは {@link AfterEntities}(2026-10-10 に足した)。
     */
    static void registerBuffers(RegisterRenderBuffersEvent event) {
        for (RenderType type : BATCHED_TYPES) {
            event.registerRenderBuffer(type);
        }
    }

    /**
     * {@link #registerBuffers} の溜め場所を、Mob を描き終えた段階で描き出す(ゲームバス・クライアント、2026-10-10)。
     *
     * <p>溜め場所を持たせた種類は、誰かが描き出さないとレベル描画の最後の {@code endBatch()} まで残る。描画設定が
     * 「速い」「綺麗」のときはそれが半透明の地形の後なので、水・色ガラス・氷の向こうの兵士と銃が消えていた
     * (10-07 に溜め場所を足したときの取りこぼし)。Mob を描き終えた所(バニラも Mob 用の種類をここで描き出す)で描けば、
     * 溜め場所を足す前と同じく半透明の地形より前に描かれる。ブロックエンティティを描き終えた所でももう一度描き出す
     * —— 他 MOD の飾り台などが銃を描いた場合のため(ふだんは空なので何もしない)。
     */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
    public static final class AfterEntities {

        @SubscribeEvent
        public static void onRenderLevelStage(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES
                    && event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
                return;
            }
            MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
            for (RenderType type : BATCHED_TYPES) {
                bufferSource.endBatch(type);
            }
        }

        private AfterEntities() {
        }
    }

    @Override
    public ResourceLocation getTextureLocation(SoldierEntity entity) {
        return TEXTURES.get(entity.kind());
    }

    @Override
    public void render(SoldierEntity entity, float yaw, float partialTicks, PoseStack pose, MultiBufferSource buffers, int light) {
        PlayerModel<SoldierEntity> model = getModel();
        boolean aiming = entity.isAiming() && !entity.getMainHandItem().isEmpty();
        model.rightArmPose = aiming ? HumanoidModel.ArmPose.CROSSBOW_HOLD
                : entity.getMainHandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        model.leftArmPose = HumanoidModel.ArmPose.EMPTY;
        super.render(entity, yaw, partialTicks, pose, buffers, light);
    }
}
