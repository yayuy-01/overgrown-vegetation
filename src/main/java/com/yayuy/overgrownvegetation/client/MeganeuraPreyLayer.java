package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.MeganeuraEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 仕留めた獲物の Mob モデルを、メガネウラの口元に咥えさせて描く描画レイヤー。
 *
 * <p>何を咥えているかは {@link MeganeuraEntity#getPreyDisplay()}(同期データ)が持っていて、
 * 獲物を仕留めた瞬間から枝の上での咀嚼({@code chew})が終わるまでの間だけ入っている。
 * このレイヤーは表示するかどうかを自分で判断せず、その値の有無にそのまま従う
 * ——つまり枝まで運んでいる最中もずっと咥えたままになる。
 *
 * <p>獲物は 90 度横を向けて描く。メガネウラの顎が向いている側(モデル座標の -Z 方向)に
 * 獲物の<b>横腹</b>が来るので、そこへ少しだけ食い込ませて「脇腹にかじりついている」形にしている。
 *
 * <p>描画用の Mob インスタンスはワールドには参加させず、種類ごとに1体だけ作って使い回す。
 * ティックも進めないので常に静止した既定ポーズで描かれる ——「咥えられている死体」としては
 * そのほうが都合がよい。
 */
public class MeganeuraPreyLayer extends GeoRenderLayer<MeganeuraEntity> {

    /** 口元のボーン(頭)。{@code meganeura.geo.json} のボーン名。 */
    private static final String HEAD_BONE = "bone";
    /**
     * 頭ボーンのローカル座標系(＝モデル座標系)での「口」の位置(Blockbench のピクセル)。
     * 頭のキューブは z -9〜-6・y 3〜5 にあるので、その少し前の顎先あたりを狙っている。
     */
    private static final float MOUTH_X = 0.0F;
    private static final float MOUTH_Y = 3.2F;
    private static final float MOUTH_Z = -9.5F;
    /** 咥えた獲物の見た目の大きさ(ブロック)。獲物の当たり判定の長辺をこの大きさに揃える。 */
    private static final float PREY_SIZE = 0.45F;
    /** 元のサイズより大きくはしない(小さい虫を巨大化させないため)。 */
    private static final float MAX_SCALE = 1.0F;
    /** 獲物の向き。90度横を向かせて横腹を口に差し出す(-90 なら反対側の脇腹)。 */
    private static final float PREY_YAW = 90.0F;
    /** 顎が横腹にどれだけ食い込んで見えるか(ブロック)。 */
    private static final float BITE_DEPTH = 0.12F;

    /** 獲物の描画が入れ子でこのレイヤーを呼び返すのを防ぐ保険。 */
    private static boolean rendering;

    /**
     * 描画用の Mob インスタンス。値が {@code null} のエントリは「作れなかった種類」の記録で、
     * 毎フレーム作り直そうとしないための負のキャッシュを兼ねる。
     */
    private final Map<EntityType<?>, Entity> preyCache = new HashMap<>();
    private Level cachedLevel;

    /**
     * 作ったレイヤー(メガネウラの描画器 1 つにつき 1 つ。資源の読み直しで描画器ごと作り直される)。
     * 切断・次元の移動のときに {@link ForgetLevel} が中身を空にする(2026-10-10)。弱参照なので、
     * 古い描画器のぶんは捨てられればここからも消える。触るのはメインスレッドだけ。
     */
    private static final Set<MeganeuraPreyLayer> INSTANCES = Collections.newSetFromMap(new WeakHashMap<>());

    public MeganeuraPreyLayer(GeoRenderer<MeganeuraEntity> renderer) {
        super(renderer);
        INSTANCES.add(this);
    }

    /** 描画用の獲物を全部手放す(次に咥えたときに、そのときのワールドで作り直す)。メインスレッドから呼ぶこと。 */
    private static void forgetAll() {
        for (MeganeuraPreyLayer layer : INSTANCES) {
            layer.preyCache.clear();
            layer.cachedLevel = null;
        }
    }

    /**
     * 捨てられる ClientLevel を描画用の獲物ごと手放す(ゲームバス・クライアント、2026-10-10)。今までは次に獲物を
     * 描くまで、前のワールドが丸ごと残っていた。統合サーバーのワールドの Unload はサーバーのスレッドから来るので、
     * クライアントのワールドのときだけ空にする。
     */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
    public static final class ForgetLevel {

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            forgetAll();
        }

        @SubscribeEvent
        public static void onLevelUnload(LevelEvent.Unload event) {
            if (event.getLevel().isClientSide()) {
                forgetAll();
            }
        }

        private ForgetLevel() {
        }
    }

    @Override
    public void renderForBone(PoseStack poseStack, MeganeuraEntity animatable, GeoBone bone, RenderType renderType,
                              MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick,
                              int packedLight, int packedOverlay) {
        if (rendering || !HEAD_BONE.equals(bone.getName())) {
            return;
        }
        EntityType<?> preyType = animatable.getPreyDisplay();
        if (preyType == null || preyType == animatable.getType()) {
            return;
        }
        Entity prey = resolvePrey(preyType);
        if (prey == null) {
            return;
        }

        // 位置に依存する描画(水中判定など)がおかしくならないよう、咥えている本体に合わせておく。
        prey.setPos(animatable.getX(), animatable.getY(), animatable.getZ());
        prey.setOldPosAndRot();

        EntityDimensions dimensions = preyType.getDimensions();
        float longest = Math.max(Math.max(dimensions.width(), dimensions.height()), 0.05F);
        float scale = Math.min(MAX_SCALE, PREY_SIZE / longest);
        // 獲物を横向きにすると、口の方(モデル座標の +Z 側)を向くのは獲物の「横腹」になる。
        // その横腹の面が顎の位置に来るまで前へ出し、BITE_DEPTH だけ食い込ませる。
        float flankOffset = Math.max(0.0F, dimensions.width() * 0.5F * scale - BITE_DEPTH);

        poseStack.pushPose();
        // renderForBone の時点で座標系はモデル座標系(1単位＝1ブロック、X だけ左右反転)。
        poseStack.translate(-MOUTH_X / 16.0F, MOUTH_Y / 16.0F, MOUTH_Z / 16.0F - flankOffset);
        poseStack.scale(scale, scale, scale);
        // 90度横に向けて、横腹をメガネウラの口へ差し出す(逆の脇腹を見せたいなら -90 に)。
        poseStack.mulPose(Axis.YP.rotationDegrees(PREY_YAW));
        // Mob は足元が原点なので、体の中心が口の高さに来るように半分だけ下げる。
        poseStack.translate(0.0F, -dimensions.height() * 0.5F, 0.0F);

        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        dispatcher.setRenderShadow(false);
        rendering = true;
        try {
            dispatcher.render(prey, 0.0D, 0.0D, 0.0D, 0.0F, partialTick, poseStack, bufferSource, packedLight);
        } catch (Exception e) {
            // 描画のためだけに作った Mob なので、相手のレンダラーが想定外の状態で落ちても
            // メガネウラごと描画不能にはしない。以後この種類は表示を諦める。
            this.preyCache.put(preyType, null);
        } finally {
            rendering = false;
            dispatcher.setRenderShadow(true);
            poseStack.popPose();
        }
    }

    /**
     * {@code type} の描画用インスタンスを返す(なければ作ってキャッシュする)。
     * 作れない種類やワールド切り替え直後は {@code null}。
     */
    @Nullable
    private Entity resolvePrey(EntityType<?> type) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        if (this.cachedLevel != level) {
            // ワールドが変わったら作り直す(Mob は生成時のワールドを掴んだままなので)。
            this.cachedLevel = level;
            this.preyCache.clear();
        }
        if (this.preyCache.containsKey(type)) {
            return this.preyCache.get(type);
        }

        Entity prey = null;
        try {
            prey = type.create(level);
            if (prey != null) {
                prey.setPos(0.0D, 0.0D, 0.0D);
                prey.setYRot(0.0F);
                prey.setXRot(0.0F);
                prey.setOldPosAndRot();
            }
        } catch (Exception e) {
            // 生成にサーバー側の情報が要るなど、クライアントだけでは作れない種類は表示しない。
            prey = null;
        }
        this.preyCache.put(type, prey);
        return prey;
    }
}
