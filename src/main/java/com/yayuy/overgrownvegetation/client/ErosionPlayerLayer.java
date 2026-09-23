package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.ClimaxTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;

/**
 * 浸食度のプレイヤーの見た目(2026-09-22)。2 つのパスを重ねる。
 *
 * <ol>
 *     <li><b>色かぶり</b> —— プレイヤー自身のスキンをもう一度、{@code climax.json} の
 *     {@code render.tintColor}(既定 {@code #1D2714})で着色して半透明に重ねる。
 *     テクスチャがスキンそのものなので<b>乗算</b>になり、肌の模様は 1 ピクセルも消えずに全体が緑へ寄る。
 *     濃さは 浸食度% × {@code render.tintMaxAlpha}(既定 0.25)。
 *     <b>100% でも元のスキンを塗りつぶさない</b>のが狙いなので、ここを上げすぎないこと。</li>
 *     <li><b>苔</b> —— 専用テクスチャ(3 段階 × slim/wide)を、<b>少し膨らませたモデル</b>で重ねる。</li>
 * </ol>
 *
 * <h2>なぜモデルを膨らませるのか</h2>
 * <p>苔のテクスチャは<b>基本層の UV</b>にだけ描いてある。素のプレイヤーモデルでそのまま描くと、
 * 帽子や上着(外層、+0.25 で膨らんでいる)を持つスキンでは<b>苔が服の内側に隠れて見えない</b>。
 * 外層より大きい {@value #INFLATION} で膨らませたモデルを別に用意して、必ず外側に出す。
 *
 * <h2>薄くしない</h2>
 * <p>苔は<b>半透明にしない</b>(ユーザー指定)。薄く透けると「生えている」ではなく「汚れている」に見える。
 * 段階の切り替わりもぼかさず、過繫茂したチェスト/ドアのオーバーレイと同じくパッと変わる。
 * 濃さで進行を見せるのは<b>色かぶりの方</b>の役目。
 */
public class ErosionPlayerLayer
        extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    /** 苔のモデルの膨らませ量。バニラの外層(+0.25)より大きくないと服の内側に入る。 */
    public static final float INFLATION = 0.32F;

    private static final ResourceLocation[] WIDE = textures("default");
    private static final ResourceLocation[] SLIM = textures("slim");

    private final PlayerModel<AbstractClientPlayer> mossModel;
    private final boolean slim;

    public ErosionPlayerLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent,
                                     EntityModelSet models, boolean slim) {
        super(parent);
        this.slim = slim;
        this.mossModel = new PlayerModel<>(models.bakeLayer(
                slim ? OvergrownVegetationModelLayers.EROSION_SLIM
                        : OvergrownVegetationModelLayers.EROSION_DEFAULT), slim);
    }

    private static ResourceLocation[] textures(String kind) {
        ResourceLocation[] out = new ResourceLocation[3];
        for (int i = 0; i < out.length; i++) {
            out[i] = OvergrownVegetation.id("textures/entity/erosion/" + kind + "_" + (i + 1) + ".png");
        }
        return out;
    }

    /**
     * 浸食度の効き具合(0〜1)に対応する苔テクスチャ。既定 3 段なら
     * 0〜33% が段階1、33〜66% が段階2、66〜100% が段階3。
     * 一人称の腕({@code ErosionFirstPerson})とここで同じものを使う。
     */
    public static ResourceLocation mossTexture(boolean slim, float scale) {
        ResourceLocation[] stages = slim ? SLIM : WIDE;
        return stages[Mth.clamp((int) (scale * stages.length), 0, stages.length - 1)];
    }

    /**
     * 色かぶりに使う ARGB。{@code render.tintColor} に 浸食度% ぶんのアルファを載せたもの。
     * スキンのテクスチャに<b>掛ける</b>ので、肌の模様は消えず全体が緑へ寄るだけになる。
     */
    public static int tintColor(ClimaxTable table, float scale) {
        float alpha = scale * (float) table.tintMaxAlpha();
        int rgb = 0xFF000000 | table.tintColor();
        return FastColor.ARGB32.color(
                Mth.clamp((int) (alpha * 255.0F), 0, 255),
                FastColor.ARGB32.red(rgb), FastColor.ARGB32.green(rgb), FastColor.ARGB32.blue(rgb));
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                       AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTick,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        float percent = ClientErosion.percentOf(player);
        if (percent <= 0.0F || player.isInvisible()) {
            return;
        }
        ClimaxTable table = DesignData.climax();
        float scale = (float) table.scale(percent);
        if (scale <= 0.0F) {
            return;
        }

        renderTint(poseStack, bufferSource, packedLight, player, table, scale);
        renderMoss(poseStack, bufferSource, packedLight, player, limbSwing, limbSwingAmount, ageInTicks,
                netHeadYaw, headPitch, scale);
    }

    /** スキンをもう一度、暗い緑で着色して薄く重ねる。 */
    private void renderTint(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                            AbstractClientPlayer player, ClimaxTable table, float scale) {
        int color = tintColor(table, scale);
        if (FastColor.ARGB32.alpha(color) <= 2) {
            return;
        }
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.entityTranslucent(player.getSkin().texture()));
        this.getParentModel().renderToBuffer(poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY, color);
    }

    /**
     * 苔を描く。<b>半透明にしない</b> —— 苔は「生えている」ものなので、薄く透けると
     * 汚れのように見えてしまう(ユーザー指定 2026-09-22)。段階が変わると絵もパッと変わるが、
     * 過繫茂したチェスト/ドアのオーバーレイ({@code overgrowth_overlay_1〜3})と同じ扱いで揃えてある。
     *
     * <p>そのため描画も {@code entityCutoutNoCull}(アルファが 0 か 1 か)を使う。
     * {@code entityTranslucent} だと頂点アルファがそのまま効いてしまう。
     */
    private void renderMoss(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                            AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float ageInTicks,
                            float netHeadYaw, float headPitch, float scale) {
        this.getParentModel().copyPropertiesTo(this.mossModel);
        this.mossModel.prepareMobModel(player, limbSwing, limbSwingAmount, 0.0F);
        this.mossModel.setupAnim(player, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

        VertexConsumer buffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(mossTexture(this.slim, scale)));
        this.mossModel.renderToBuffer(poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
    }
}
