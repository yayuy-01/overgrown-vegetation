package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.SeminariaEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「セミナリア」のモデル。正本は Blockbench の {@code デスクトップ\blockbench\mobs\seminaria.bbmodel}(11-15 に練習モデル フリマキ r7
 * {@code tools/g5-models/out/unadopted/practice/prac2_g4_drifter_r7} を写した)。体の傾き・機首の上下は描画({@link SeminariaRenderer})で全体を回す。
 *
 * <p>風に揉まれる層(2026-11-15 段 2。Vault「セミナリア」6.5b、正本はモデル制作の {@code stage2_anim/scripts/gust.js}): アニメの後に、
 * 翅のひねり・体と頭の横揺れ・腹と冠毛の遅れた振れを骨の回転へ足す。強さは {@link SeminariaEntity#gust}(飛ぶ間だけ)。
 * json のアニメにしないのは、同じ骨を 2 つのコントローラが動かすと後が勝って羽ばたきが消えるため。
 */
public class SeminariaModel extends GeoModel<SeminariaEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/seminaria.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/seminaria.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/seminaria.animation.json");

    /** 層の骨と軸(0 = x, 1 = y, 2 = z)。gust.js の表の写し(順も同じ)。 */
    private static final String[] BONES = {
            "wingf_l", "wingf_l", "wingf_r", "wingf_r", "wingb_l", "wingb_l", "wingb_r", "wingb_r",
            "thorax", "head", "ab1", "ab2", "ab3", "pappus", "pappus",
            "pb0", "pb1", "pb2", "pb3", "pb4", "pb5",
            "tipf_l", "tipf_r", "tipb_l", "tipb_r"};
    private static final int[] AXES = {0, 1, 0, 1, 0, 1, 0, 1, 2, 2, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 2};
    /** 行ごとの波 [振幅(度、強さ 1 のとき), 周期(tick), 位相(ラジアン)] …。 */
    private static final double[][] WAVES = {
            {14, 23, 0.0, 6, 7.3, 1.1}, {5, 31, 0.4},
            {14, 27, 2.0, 6, 8.9, 0.3}, {5, 37, 1.7},
            {18, 19, 0.9, 7, 6.1, 2.6}, {6, 29, 2.2},
            {18, 21, 3.1, 7, 7.9, 1.4}, {6, 33, 0.8},
            {5, 41, 0.0, 2, 13, 1.0},
            {-3, 41, 0.6},
            {4, 43, 0.5}, {6, 43, -0.4}, {9, 43, -1.3, 3, 17, 0.2},
            {8, 43, -2.2}, {5, 26, 0.7},
            {6, 11, 0.0}, {6, 13, 1.3}, {6, 9.5, 2.9}, {6, 12, 4.1}, {6, 10.5, 5.2}, {6, 14, 0.6},
            // 翅の先のばたつき(2026-11-16。Vault 8.9【仮・モデル制作の案】)
            {7, 5.3, 0.5, 3, 17, 2.0}, {7, 6.1, 2.4, 3, 19, 0.9}, {9, 4.7, 1.6, 4, 23, 3.0}, {9, 5.9, 0.2, 4, 21, 1.3}};

    @Override
    public ResourceLocation getModelResource(SeminariaEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(SeminariaEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(SeminariaEntity animatable) {
        return ANIMATION;
    }

    @Override
    public void setCustomAnimations(SeminariaEntity e, long instanceId, AnimationState<SeminariaEntity> animationState) {
        super.setCustomAnimations(e, instanceId, animationState);
        float pt = animationState.getPartialTick();
        float s = e.gust(pt);
        if (s <= 0.001F) return;
        double tick = e.tickCount + pt;
        double ph = e.gustPhase();
        for (int i = 0; i < BONES.length; i++) {
            GeoBone b = getAnimationProcessor().getBone(BONES[i]);
            if (b == null) continue;
            double[] w = WAVES[i];
            double deg = 0.0D;
            for (int k = 0; k < w.length; k += 3) {
                deg += w[k] * Math.sin(2.0D * Math.PI * tick / w[k + 1] + w[k + 2] + ph);
            }
            float r = (float) Math.toRadians(s * deg);
            // json の度 → GeckoLib の骨: x・y は向きが逆(GeckoLib が読み込むときに裏返す)
            switch (AXES[i]) {
                case 0 -> b.setRotX(b.getRotX() - r);
                case 1 -> b.setRotY(b.getRotY() - r);
                default -> b.setRotZ(b.getRotZ() + r);
            }
            b.resetStateChanges();
        }
    }
}
