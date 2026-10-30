package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderBuffersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

import java.util.List;

/**
 * 種族専用の GeckoLib モデルを持つ汚染死体({@link com.yayuy.overgrownvegetation.block.GeoCorruptedCorpseBlock}:
 * 羊・豚・クリーパー)の描画。
 *
 * <p>この BER は
 * チャンクメッシュにまとめて焼き込まれず 1 個体につき毎フレーム 1 回ずつ個別に描画される。
 * 描画距離を {@value #VIEW_DISTANCE} ブロックへ縮めて、遠くの死体は描画しない
 * (= 近づかないと描画されない)。
 *
 * <p>{@link com.yayuy.overgrownvegetation.registry.ModBlockEntities#CORRUPTED_CORPSE} は
 * 牛・ゾンビ・クモの死体(通常のブロックモデル描画)とも共用しているため、専用 geo モデルを
 * 持たないブロックでは何も描画しない({@link CorruptedCorpseModel#hasGeoModel})。
 */
public class CorruptedCorpseRenderer extends GeoBlockRenderer<CorruptedCorpseBlockEntity> {

    /** この BER が描画される最大距離(ブロック)。バニラ既定の 64 より大幅に短い。 */
    private static final int VIEW_DISTANCE = 24;

    /**
     * 専用の溜め場所を持たせる死体の描画の種類(絵ごとに 1 つ)。登録({@link FixedBuffers})と描き出し
     * ({@link AfterBlockEntities})は必ずこの並びを使う。並びは死体の絵の一覧({@link CorruptedCorpseModel#textures})から作る。
     */
    static final List<RenderType> BATCHED_TYPES = CorruptedCorpseModel.textures().stream()
            .map(CorruptedCorpseRenderer::renderTypeFor)
            .distinct()
            .toList();

    public CorruptedCorpseRenderer() {
        super(new CorruptedCorpseModel());
    }

    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }

    /**
     * 死体の描画の種類(2026-10-10、0.1.9 の前の軽量化)。GeckoLib の既定と同じ {@code entityCutoutNoCull} の、
     * 輪郭線(発光の縁取り)の印だけを外した版。
     *
     * <p>既定のままだと、同じ絵で描く感染体(通常の個体)と同じ種類になる。その種類に溜め場所を持たせると生きている Mob まで
     * 溜め場所に入り、Mob を描く段階から後ろへずれる(手前の半透明の Mob に隠れうる)ので、死体だけの種類に分けた。
     * 輪郭線の印を見るのは発光するエンティティの描画({@code OutlineBufferSource})だけで、ブロックエンティティの描画は
     * そこを通らないので、絵は 1 画素も変わらない(シェーダー・切り抜き・壊すときのひび割れも既定と同じ)。
     */
    static RenderType renderTypeFor(ResourceLocation texture) {
        return RenderType.entityCutoutNoCull(texture, false);
    }

    @Override
    public RenderType getRenderType(CorruptedCorpseBlockEntity animatable, ResourceLocation texture,
                                    @javax.annotation.Nullable MultiBufferSource bufferSource, float partialTick) {
        return renderTypeFor(texture);
    }

    /**
     * 死体の絵ごとに<b>専用の溜め場所</b>を持たせる(MOD バス・クライアント、2026-10-10)。
     *
     * <p>溜め場所の無い描画の種類は、別の種類が来るたびにそこまでを描き出す。死体は 24 ブロック以内のものを毎フレーム
     * 1 体ずつ描くので、種の違う死体が並んでいると 1 体ごとに描き出し(そのたびにシェーダーの準備)が起きていた。
     * 溜め場所があれば、見えている死体を全部積んでから絵ごとに 1 回だけ描く({@link AfterBlockEntities})。
     *
     * <p>見た目が変わらない理由: 切り抜き・不透明の種類なので、死体どうし・他の不透明なものとの前後は描く順番に関係なく
     * 深度で決まる。描き出すのはブロックエンティティの直後(水・色ガラスなど半透明の地形より前)で、今までと同じ層。
     */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class FixedBuffers {

        @SubscribeEvent
        public static void onRegisterRenderBuffers(RegisterRenderBuffersEvent event) {
            for (RenderType type : BATCHED_TYPES) {
                event.registerRenderBuffer(type);
            }
        }

        private FixedBuffers() {
        }
    }

    /**
     * {@link FixedBuffers} に積んだ死体を、ブロックエンティティを描き終えた段階で描く(ゲームバス・クライアント)。
     * これをしないとレベル描画の最後の {@code endBatch()} まで持ち越され、半透明の地形より後に描かれて
     * 水やガラス越しの死体が消える。
     */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
    public static final class AfterBlockEntities {

        @SubscribeEvent
        public static void onRenderLevelStage(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
                return;
            }
            MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
            for (RenderType type : BATCHED_TYPES) {
                bufferSource.endBatch(type);
            }
        }

        private AfterBlockEntities() {
        }
    }

    @Override
    public void render(CorruptedCorpseBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (!CorruptedCorpseModel.hasGeoModel(blockEntity.getBlockState().getBlock())) {
            // 専用モデルの無い死体(牛・ゾンビ・クモ)は通常のブロックモデルで描画済み。
            return;
        }
        if (blockEntity.isRemoved()) {
            // 孵化で消えたのに、描画の区画が作り直されるまで残っている(起き上がる Mob と二重に見える)。
            return;
        }
        if (blockEntity.hasExactOffset() && blockEntity.getLevel() != null) {
            // 崩れ落ちから置かれた死体: 描き始めたことを知らせる(崩れ落ちた Mob を消して入れ替える。2026-10-09)
            net.minecraft.core.BlockPos p = blockEntity.getBlockPos();
            com.yayuy.overgrownvegetation.client.corpse.ClientCorpseCollapse.corpseDrawn(
                    p.getX() + 0.5 + blockEntity.offsetX(), p.getY() + blockEntity.offsetY(), p.getZ() + 0.5 + blockEntity.offsetZ(),
                    blockEntity.getLevel().getGameTime());
        }
        super.render(blockEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
    }

    /** 崩れ落ちに対応した死体の姿勢の値(無ければ null = 今までどおり北向き・ブロックの真ん中)。 */
    @javax.annotation.Nullable
    private static com.yayuy.overgrownvegetation.client.corpse.CorpsePoses.Pose poseOf(CorruptedCorpseBlockEntity be) {
        String species = com.yayuy.overgrownvegetation.corpse.CorpseSpecies.speciesOf(be.getBlockState().getBlock());
        return species == null ? null : com.yayuy.overgrownvegetation.client.corpse.CorpsePoses.get(species);
    }

    /**
     * 死体の向きと位置(2026-10-09): 死んだ Mob の位置(ブロックの底の真ん中からのずれ)へ動かし、その体の向きで描く
     * (GeckoLib の Mob の描画と同じ {@code YP(180 − yaw)})。崩れ落ちの終わり・起き上がりの始まりとぴったり重なる。
     * 手で置いた死体はずれが無いので、死体の外接箱の真ん中をブロックの真ん中へ寄せる。
     * この時点の PoseStack は描画の拡大(scaleModelForRender)の内側なので、ずらす量は拡大で割る。
     */
    @Override
    protected void rotateBlock(net.minecraft.core.Direction facing, PoseStack poseStack) {
        CorruptedCorpseBlockEntity be = this.animatable;
        var pose = be == null ? null : poseOf(be);
        if (pose == null) {
            super.rotateBlock(facing, poseStack);
            return;
        }
        float s = pose.renderScale();
        float yaw = be.yaw();
        org.joml.Quaternionf q = com.mojang.math.Axis.YP.rotationDegrees(180.0F - yaw);
        if (be.hasExactOffset()) {
            poseStack.translate(be.offsetX() / s, be.offsetY() / s, be.offsetZ() / s);
        } else {
            // geo の x は描画で反転する(GeckoLib)。真ん中 (−cx, 0, cz)/16 を向きで回してから引く
            org.joml.Vector3f c = new org.joml.Vector3f(-pose.center()[0] / 16.0F, 0.0F, pose.center()[1] / 16.0F);
            q.transform(c);
            poseStack.translate(-c.x, 0.0F, -c.z);
        }
        poseStack.mulPose(q);
    }

    /** 死体の大きさ = バニラのレンダラーの拡大(村人 0.9375)。崩れ落ちたバニラの Mob と同じ大きさ。 */
    @Override
    public void scaleModelForRender(float widthScale, float heightScale, PoseStack poseStack, CorruptedCorpseBlockEntity animatable,
                                    software.bernie.geckolib.cache.object.BakedGeoModel model, boolean isReRender, float partialTick,
                                    int packedLight, int packedOverlay) {
        var pose = poseOf(animatable);
        float s = pose == null ? 1.0F : pose.renderScale();
        super.scaleModelForRender(widthScale * s, heightScale * s, poseStack, animatable, model, isReRender, partialTick, packedLight, packedOverlay);
    }

    /** 死体は 2〜3 ブロックの長さで、死んだ位置の前へ伸びる。1 マスの枠のままだと、ブロックが画面から外れた途端に消える。 */
    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox(CorruptedCorpseBlockEntity blockEntity) {
        return new net.minecraft.world.phys.AABB(blockEntity.getBlockPos()).inflate(3.0);
    }
}
