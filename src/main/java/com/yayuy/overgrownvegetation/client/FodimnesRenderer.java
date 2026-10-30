package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.entity.FodimnesEntity;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「フォディムネス」の描画: 頭({@code fodimnes_head}、この描き手)+ 節 16({@code fodimnes_segment})+ 尾({@code fodimnes_tail})。
 * 光は無い(発光マスクを重ねない)。描画倍率 1。
 *
 * <h2>ユーザー指定</h2>
 * <ul>
 *   <li>節の向きは方向ベクトルで持ち、描くときは<b>クォータニオンで前の向きからの最小回転</b>(真上を向いてもねじれない)+ ひねり(roll)。
 *       向きは本体がクライアントの tick で作る({@code FodimnesEntity#renderFrame}、{@code FodimnesFrames})。フレームの間は前の tick と slerp。</li>
 *   <li><b>地面の下にある節は描かない(頭・尾も)</b>: 部位の中心と前後の端の 3 点が全部岩の中なら描かない
 *       (中心だけで決めると、壁の穴から半分出ている節が消えて穴の所で体が途切れる)。頭が埋まっているときは影も出さない。</li>
 * </ul>
 *
 * <h2>部位の置き方(NOTES の「部位の約束」)</h2>
 * 部位の軸の点へ平行移動 → 向きを掛ける → 体格を掛ける → 先細りは<b>太さだけ</b>、軸のまわりに({@code scale(s, s, 1)}。長さ = 間隔 0.8 は変えない)→
 * 軸の高さ(4.3/16)ぶん下げて部位の原点(地面の中心)へ。モデルの首・節の筒の 1°/0.5° の傾きはそのまま(ちらつき止め)。
 * 節の蠕動の位相は部位ごとのアニメの持ち主({@code FodimnesPart})が番号 × 5 tick ずらす。
 */
public class FodimnesRenderer extends GeoEntityRenderer<FodimnesEntity> {

    private final FodimnesPartRenderer segmentRenderer = new FodimnesPartRenderer(new FodimnesPartModel("fodimnes_segment"));
    private final FodimnesPartRenderer tailRenderer = new FodimnesPartRenderer(new FodimnesPartModel("fodimnes_tail"));
    private final Vector3d partAxis = new Vector3d();
    private final Quaternionf partFrame = new Quaternionf();
    private final Quaternionf headFrame = new Quaternionf();

    public FodimnesRenderer(EntityRendererProvider.Context context) {
        super(context, new FodimnesModel());
        this.shadowRadius = 0.35F;
    }

    @Override
    public void render(FodimnesEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource,
                       int packedLight) {
        if (!entity.isVisualReady()) {
            // 部位の位置・向きをまだ一度も作っていない(世界に入った直後の 1 フレーム。普通は onAddedToLevel で作ってある)
            return;
        }
        if (entity.isEggPreview()) {
            // スポーンエッグの絵(FodimnesEntity#prepareEggPreview): 頭と前の数節を、その真ん中が枠の中心に来るようにずらして描く
            Vec3 shift = entity.eggShift();
            poseStack.pushPose();
            poseStack.translate(shift.x, shift.y, shift.z);
            super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
            renderBody(entity, partialTick, poseStack, bufferSource, packedLight);
            poseStack.popPose();
            return;
        }
        boolean headVisible = !entity.isPartBuried(0);
        // 影は描いた後にバニラが shadowRadius を読んで描く。頭が岩の中なら影も出さない
        this.shadowRadius = headVisible ? 0.35F : 0.0F;
        if (headVisible) {
            super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        }
        if (!entity.isInvisible()) {
            renderBody(entity, partialTick, poseStack, bufferSource, packedLight);
        }
    }

    /** 頭の向き: 軸(断面の中心)のまわりに、本体が作った向き(回転最小化 + バンク)を掛ける。バニラの yaw の回し方・死の横転はしない。 */
    @Override
    protected void applyRotations(FodimnesEntity animatable, PoseStack poseStack, float ageInTicks, float rotationYaw, float partialTick,
                                  float nativeScale) {
        float axis = (float) FodimnesEntity.AXIS_Y;
        poseStack.translate(0.0F, axis, 0.0F);
        poseStack.mulPose(animatable.renderFrame(0, partialTick, this.headFrame));
        poseStack.translate(0.0F, -axis, 0.0F);
    }

    private void renderBody(FodimnesEntity entity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        // PoseStack の原点 = 描く頭の位置(バニラが xOld → x を補間した所)
        double ox = Mth.lerp(partialTick, entity.xOld, entity.getX());
        double oy = Mth.lerp(partialTick, entity.yOld, entity.getY());
        double oz = Mth.lerp(partialTick, entity.zOld, entity.getZ());
        float scale = entity.getScale();
        float axis = (float) FodimnesEntity.AXIS_Y;
        for (int i = 1; i <= FodimnesEntity.PARTS; i++) {
            if (entity.isPartBuried(i)) {
                continue;
            }
            entity.renderAxis(i, partialTick, this.partAxis);
            entity.renderFrame(i, partialTick, this.partFrame);
            float taper = FodimnesEntity.partTaper(i);
            poseStack.pushPose();
            poseStack.translate(this.partAxis.x - ox, this.partAxis.y - oy, this.partAxis.z - oz);
            poseStack.mulPose(this.partFrame);
            poseStack.scale(scale, scale, scale);
            // 先細りは軸(断面の中心)のまわりに掛ける: 軸が道の上に乗ったまま細る(当たり判定の箱も軸が中心)。
            // 見本の組み上げは「腹を地面に着けたまま」原点のまわりに縮めているが、道は 3 次元(真上・真下へも潜る)なので、
            // 原点まわりだと細い節ほど部位の「下」へ最大 0.075 ブロックずれ、縦に潜るときに道から横へ外れて見える
            poseStack.scale(taper, taper, 1.0F);
            poseStack.translate(0.0F, -axis, 0.0F);
            int light = partLight(entity, i, packedLight);
            FodimnesPartRenderer r = i <= FodimnesEntity.SEGMENTS ? this.segmentRenderer : this.tailRenderer;
            r.renderPart(poseStack, entity.part(i), bufferSource, light, partialTick);
            poseStack.popPose();
        }
    }

    /**
     * 部位の明るさ: 部位の中心のマスの明るさ。中心が岩の中(穴から半分出ている節。岩の中の明るさは 0 で真っ黒になる)なら、
     * 前後の端のうち岩でないマスの明るい方(2026-10-01 の点検で、頭の明るさから変えた —— 頭は 13 ブロック先の別の明るさの所に居ることがある)。
     * どちらも岩なら(描かない部位)頭の明るさ。
     */
    private int partLight(FodimnesEntity entity, int index, int headLight) {
        if (entity.isEggPreview()) {
            // スポーンエッグの絵: 描画専用の個体は世界の原点に居るので、世界の明るさではなくアイテムの明るさ
            return headLight;
        }
        Level level = entity.level();
        BlockPos p = BlockPos.containing(this.partAxis.x, this.partAxis.y, this.partAxis.z);
        if (!FodimnesEntity.isEarth(level, p)) {
            return LevelRenderer.getLightColor(level, p);
        }
        Vec3 f = entity.partForward(index);
        double half = FodimnesEntity.SPACING * 0.5D * entity.getScale();
        int block = -1;
        int sky = -1;
        for (int k = -1; k <= 1; k += 2) {
            BlockPos e = BlockPos.containing(this.partAxis.x + f.x * half * k, this.partAxis.y + f.y * half * k, this.partAxis.z + f.z * half * k);
            if (!FodimnesEntity.isEarth(level, e)) {
                int light = LevelRenderer.getLightColor(level, e);
                block = Math.max(block, LightTexture.block(light));
                sky = Math.max(sky, LightTexture.sky(light));
            }
        }
        return block < 0 ? headLight : LightTexture.pack(block, sky);
    }

    /** 頭の明るさ(ブロックの光): 目の高さのマスと唇の先の少し前のマスの明るい方({@link FodimnesEntity#mouthLightProbe})。 */
    @Override
    protected int getBlockLightLevel(FodimnesEntity entity, BlockPos pos) {
        int own = super.getBlockLightLevel(entity, pos);
        return Math.max(own, entity.level().getBrightness(LightLayer.BLOCK, entity.mouthLightProbe()));
    }

    /** 頭の明るさ(空の光)。ブロックの光と同じく唇の先の少し前も見る。 */
    @Override
    protected int getSkyLightLevel(FodimnesEntity entity, BlockPos pos) {
        int own = super.getSkyLightLevel(entity, pos);
        return Math.max(own, entity.level().getBrightness(LightLayer.SKY, entity.mouthLightProbe()));
    }

    /** 倒れ方: 横転はしない(死のアニメは無い。赤くなって煙になる。体はその場で止まる)。 */
    @Override
    protected float getDeathMaxRotation(FodimnesEntity animatable) {
        return 0.0F;
    }
}
