package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.entity.ScutimnesEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「スクティムネス」の描画。
 *
 * <h2>モデルを +Z へ 14px ずらす</h2>
 * <p>エンティティの原点(当たり判定の中心・旋回の中心)はモデルのファイル座標 (0, 0, −14) —— 8 本の脚の足先の真ん中あたり。
 * ファイル座標の原点 (0, 0, 0) は後ろ脚よりさらに後ろ(尾の付け根)にあり、そのままだと体が当たり判定の前へはみ出し、
 * その場で回ると尾の付け根を中心に頭と盾が大きく振り回されて見える。GeckoLib の変換は {@code poseStack.scale(体格)} →
 * {@code applyRotations} の Y 回転(180° − 体の向き)→ モデル(ファイルの x を反転して焼いたもの)の順なので、
 * 回転の<b>後</b>にモデルの +Z(= 後ろ)へ 14/16 ずらせば、ファイルの (0, 0, −14) が原点に来る(体格は先に掛かっているので
 * 14px × 体格 になる)。向きは JOML で数値を確かめた(向き 0 / 90 / −37 度で、ファイルの (0, 0, −14) が原点、
 * 頭は前へ 6px × 体格、ファイル +X の盾は体の左)。
 *
 * <p>影は原点(= 脚の真ん中)に落ちるので、ずらした後の体にそのまま合う。{@code GeoEntityRenderer} は SCALE で影を広げないので、
 * 脚の幅に合わせて手で決めてある(体格 1.6 のとき 1.5。体格 2.88 にしたので 1.8 倍して 2.7、脚の幅は約 4.8 ブロック)。
 */
public class ScutimnesRenderer extends GeoEntityRenderer<ScutimnesEntity> {

    /** モデルを後ろ(モデルの +Z)へずらす量(ブロック、体格 1.0)。遠景の身代わりの絵も同じだけずらす(client/horde/HordeSprites)。 */
    public static final float MODEL_SHIFT = (float) (-ScutimnesEntity.ORIGIN_Z_PX / 16.0D);

    public ScutimnesRenderer(EntityRendererProvider.Context context) {
        super(context, new ScutimnesModel());
        this.shadowRadius = 2.7F;
    }

    @Override
    protected void applyRotations(ScutimnesEntity animatable, PoseStack poseStack, float ageInTicks, float rotationYaw,
                                  float partialTick, float nativeScale) {
        super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick, nativeScale);
        poseStack.translate(0.0F, 0.0F, MODEL_SHIFT);
    }

    /**
     * バニラの「倒れると横へ 90° 転がる」を切る。倒れる動きはアニメ {@code death}(脚が折れて体が沈む)が持っている ——
     * 両方掛かると 7 ブロックの体が崩れながら横倒しになって地面に埋まる。
     */
    @Override
    protected float getDeathMaxRotation(ScutimnesEntity animatable) {
        return 0.0F;
    }
}
