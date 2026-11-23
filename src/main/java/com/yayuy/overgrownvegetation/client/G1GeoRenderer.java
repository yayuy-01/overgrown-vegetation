package com.yayuy.overgrownvegetation.client;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.LivingEntity;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.renderer.GeoEntityRenderer;


/**
 * G1 の感染体のレンダラー(2026-10-09 の作り直し、2026-11-08 に段階 0 の組み込みで光る層を足した)。大きさはバニラのレンダラーと同じ
 * (村人 0.9375 など)にして、崩れ落ち → 死体 → 起き上がりで大きさが跳ねないようにする。
 * 胞子の袋の中に透ける胞子だけぼんやり光る(11-04 決定。絵 {@code <id>_glowmask.png}、{@link G1Glow})。
 */
public class G1GeoRenderer<T extends LivingEntity & GeoEntity> extends GeoEntityRenderer<T> {

    public G1GeoRenderer(EntityRendererProvider.Context context, String id, float scale, float shadow) {
        this(context, new G1GeoModel<>(id, false), scale, shadow);
    }

    public G1GeoRenderer(EntityRendererProvider.Context context, G1GeoModel<T> model, float scale, float shadow) {
        super(context, model);
        this.shadowRadius = shadow;
        if (scale != 1.0F) {
            withScale(scale);
        }
        addRenderLayer(new G1Glow<>(this, e -> false));
    }

    /** 1 段目は死んでも横に倒れない(death_burst で震えて袋が膨らみ、その場で破裂する。11-11 ユーザー)。段階 0 はバニラのまま倒れる。 */
    @Override
    protected float getDeathMaxRotation(T animatable) {
        // 根付きも倒れない(death_wither でその場に枯れて崩れる。11-13 ユーザー)
        return animatable instanceof com.yayuy.overgrownvegetation.entity.DevelopedParasitized
                || animatable instanceof com.yayuy.overgrownvegetation.entity.RootedParasitized
                || animatable instanceof com.yayuy.overgrownvegetation.entity.RootGuardian
                || animatable instanceof com.yayuy.overgrownvegetation.entity.NurseryParasitized ? 0.0F : super.getDeathMaxRotation(animatable);
    }

    /** 羊の 1 段目の綿毛の段(0〜3)で立って見える房の数(骨 boll_1〜boll_5)。【仮】(2026-11-13、能力の第 2 回) */
    private static final int[] BOLLS_BY_FLUFF = {0, 2, 4, 5};

    /**
     * 羊の 1 段目: 綿毛を放つ・刈られると房が減る(草を食べると戻る)。減った分の房の骨(番号の大きい方から)を描かない。
     * 骨の値は書き換えない(描くのを飛ばすだけ。骨に値を足すと GeckoLib の「変わった」印で積み上がる罠があるので)。光る層の描き直しもここを通る。
     */
    @Override
    public void renderRecursively(com.mojang.blaze3d.vertex.PoseStack poseStack, T animatable, software.bernie.geckolib.cache.object.GeoBone bone,
                                  net.minecraft.client.renderer.RenderType renderType, net.minecraft.client.renderer.MultiBufferSource bufferSource,
                                  com.mojang.blaze3d.vertex.VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight,
                                  int packedOverlay, float red, float green, float blue, float alpha) {
        if (animatable instanceof com.yayuy.overgrownvegetation.entity.DevelopedParasitized d
                && d.dev() == com.yayuy.overgrownvegetation.entity.g1.G1Developed.SHEEP) {
            String name = bone.getName();
            if (name.length() == 6 && name.startsWith("boll_")) {
                int index = name.charAt(5) - '0';
                int fluff = Math.max(0, Math.min(BOLLS_BY_FLUFF.length - 1, d.fluff()));
                if (index > BOLLS_BY_FLUFF[fluff]) return;
            }
        }
        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }

    /** 1 段目の死に際は赤い被弾色を出さない(破裂のアニメを見せる。G1DeathBurst の javadoc)。 */
    @Override
    public int getPackedOverlay(T animatable, float u, float partialTick) {
        // 根付きの「枯れて崩れる」も赤くしない(2.2 秒のあいだ全身が赤いままになる。11-13)
        if ((animatable instanceof com.yayuy.overgrownvegetation.entity.DevelopedParasitized
                || animatable instanceof com.yayuy.overgrownvegetation.entity.RootedParasitized
                || animatable instanceof com.yayuy.overgrownvegetation.entity.RootGuardian
                || animatable instanceof com.yayuy.overgrownvegetation.entity.NurseryParasitized) && animatable.deathTime > 0) {
            return net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;
        }
        return super.getPackedOverlay(animatable, u, partialTick);
    }

}
