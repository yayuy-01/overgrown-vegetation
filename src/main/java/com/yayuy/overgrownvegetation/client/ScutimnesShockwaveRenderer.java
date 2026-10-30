package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.entity.ScutimnesShockwave;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Quaternionf;

import java.util.List;

/**
 * スクティムネスの衝撃波({@link ScutimnesShockwave})の描画 —— <b>地表のブロックが波打って持ち上がる</b>(2026-09-23)。
 *
 * <p>ユーザー指定「ウォーデンのビームではなく、L_Ender's Cataclysm のボスのようにブロックが少し持ち上がり、自身を起点に波打っていく」。
 * 扇の中の列ごとに、地表のブロックの<b>写し</b>を持ち上げて描く。本物のブロックはその場に残る(地形は変えない)ので、
 * 写しが本物の上へ盛り上がって見える。列と地表はエンティティが 1 回だけ探して覚えている({@link ScutimnesShockwave#columns})。
 *
 * <h2>動き(仮)</h2>
 * <ul>
 *     <li>波が届いた tick から {@link ScutimnesShockwave#RISE_TICKS} tick の山。高さは {@code sin(π·p^}{@link #CURVE_SKEW}{@code )}
 *     —— 素の sin だと 4 tick かけてじわりと上がるので、2.7 tick で頂点へ跳ね上げ、残りでゆっくり沈める(叩かれて跳ねる感じ)。</li>
 *     <li>上がる間は<b>波の進む向き(外向き)へ上面を傾け</b>、沈む間は内へ戻す(波の面の傾きと同じ向き)。最大 {@link #TILT_MAX_DEG}°。</li>
 *     <li>列ごとに横をわずかに縮める({@link ScutimnesShockwave.Column#inset})。隣の列の写しと側面が同じ平面に重なってちらつくのを防ぐ。</li>
 * </ul>
 *
 * <h2>光と色</h2>
 * <p>光は<b>列の真上のマス</b>の明るさを 1 つだけ使う。本物のブロックの隣(地面の中)の光を拾うと、持ち上がって見えた側面が真っ黒になる。
 * 面ごとの陰影は、エンティティ用の描画(法線で陰影が付く)に任せる —— 影の無いのっぺりした塊にしない。
 * 草・葉の色はその場のバイオームの色({@code BlockColors} にレベルと位置を渡す)。模様の向き(草の上面の回転など)は
 * 本物と同じ乱数の種({@code BlockState#getSeed})で選ぶので、写しと本物で模様がずれない。
 */
public class ScutimnesShockwaveRenderer extends EntityRenderer<ScutimnesShockwave> {

    /** 持ち上げの立ち上がりの鋭さ。頂点は {@code 0.5^(1/0.65)} ≈ 0.34(8 tick のうち 2.7 tick 目)。 */
    private static final double CURVE_SKEW = 0.65D;
    /** 傾きの最大(度)。 */
    private static final float TILT_MAX_DEG = 20.0F;
    private static final float TILT_MAX = TILT_MAX_DEG * Mth.DEG_TO_RAD;
    /** これより低い持ち上げは描かない(本物のブロックと面が重なってちらつく)。 */
    private static final float MIN_VISIBLE_LIFT = 0.02F;
    private static final Direction[] SIDES = Direction.values();

    private final BlockRenderDispatcher blocks;
    private final BlockColors colors;
    private final RandomSource random = RandomSource.create();

    public ScutimnesShockwaveRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.blocks = context.getBlockRenderDispatcher();
        this.colors = Minecraft.getInstance().getBlockColors();
        this.shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(ScutimnesShockwave entity) {
        return InventoryMenu.BLOCK_ATLAS;
    }

    @Override
    public void render(ScutimnesShockwave wave, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight) {
        Level level = wave.level();
        double age = wave.tickCount + partialTick;
        // poseStack の原点はエンティティの(補間した)位置。列は世界の座標なので、その差だけ動かす。
        double ex = Mth.lerp(partialTick, wave.xo, wave.getX());
        double ey = Mth.lerp(partialTick, wave.yo, wave.getY());
        double ez = Mth.lerp(partialTick, wave.zo, wave.getZ());
        for (ScutimnesShockwave.Column c : wave.columns()) {
            double p = (age - c.arriveTick()) / ScutimnesShockwave.RISE_TICKS;
            if (p <= 0.0D || p >= 1.0D) {
                continue;
            }
            float s = (float) Math.pow(p, CURVE_SKEW);
            float h = c.lift() * Mth.sin(Mth.PI * s);
            if (h < MIN_VISIBLE_LIFT) {
                continue;
            }
            BlockState state = c.state();
            BlockPos pos = c.pos();
            // 探したあとで掘られた・置き換わった列は描かない(消えたブロックの幽霊を出さない)。
            if (state.getRenderShape() != RenderShape.MODEL || level.getBlockState(pos) != state) {
                continue;
            }
            float tilt = TILT_MAX * Mth.sin(Mth.TWO_PI * s) * Math.min(1.0F, c.lift());

            poseStack.pushPose();
            poseStack.translate(pos.getX() - ex + 0.5D, pos.getY() - ey + h + 0.5D, pos.getZ() - ez + 0.5D);
            if (Math.abs(tilt) > 1.0E-3F) {
                // 軸 (outZ, 0, -outX) まわりの正の回転で、上面 (0,1,0) が外向き (outX, 0, outZ) へ倒れる。
                poseStack.mulPose(new Quaternionf().rotationAxis(tilt, c.outZ(), 0.0F, -c.outX()));
            }
            poseStack.scale(c.inset(), 1.0F, c.inset());
            poseStack.translate(-0.5D, -0.5D, -0.5D);
            drawBlock(level, state, pos, poseStack.last(), buffers, LevelRenderer.getLightColor(level, pos.above()));
            poseStack.popPose();
        }
    }

    /**
     * ブロックのモデルをそのまま描く。{@code BlockRenderDispatcher#renderSingleBlock} とほぼ同じだが、
     * 色をその場のバイオームで、模様を本物と同じ乱数の種で選ぶ(あちらは色が既定値、種が 42 固定)。
     */
    private void drawBlock(Level level, BlockState state, BlockPos pos, PoseStack.Pose pose,
                           MultiBufferSource buffers, int light) {
        BakedModel model = this.blocks.getBlockModel(state);
        long seed = state.getSeed(pos);
        this.random.setSeed(seed);
        for (RenderType type : model.getRenderTypes(state, this.random, ModelData.EMPTY)) {
            VertexConsumer consumer = buffers.getBuffer(RenderTypeHelper.getEntityRenderType(type, false));
            for (Direction side : SIDES) {
                this.random.setSeed(seed);
                emit(level, state, pos, pose, consumer, model.getQuads(state, side, this.random, ModelData.EMPTY, type), light);
            }
            this.random.setSeed(seed);
            emit(level, state, pos, pose, consumer, model.getQuads(state, null, this.random, ModelData.EMPTY, type), light);
        }
    }

    private void emit(Level level, BlockState state, BlockPos pos, PoseStack.Pose pose, VertexConsumer consumer,
                      List<BakedQuad> quads, int light) {
        for (BakedQuad quad : quads) {
            float r = 1.0F;
            float g = 1.0F;
            float b = 1.0F;
            if (quad.isTinted()) {
                int color = this.colors.getColor(state, level, pos, quad.getTintIndex());
                r = (color >> 16 & 0xFF) / 255.0F;
                g = (color >> 8 & 0xFF) / 255.0F;
                b = (color & 0xFF) / 255.0F;
            }
            consumer.putBulkData(pose, quad, r, g, b, 1.0F, light, OverlayTexture.NO_OVERLAY);
        }
    }
}
