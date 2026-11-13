package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.BlockAndItemGeoLayer;

import java.util.function.Function;

/**
 * G1 のスケルトンが右手に固められた弓(2026-11-08)。バニラの手持ちアイテムを骨 {@code right_item} に描く(11-05 ユーザー「弓はバニラの手持ちアイテムで」)。
 * 立ち姿の Mob と死体ブロックの両方で使う。
 *
 * <p>骨の約束(Vault {@code モデル制作/G1スケルトン作り直し/r1.md}): 骨を回していないとき、弓の長い向き = 骨の +Y、絵の面 = YZ 面、
 * 握りの中心 = 骨の軸。アイテムの絵(バニラの bow.png は左下 → 右上の対角に弓)を {@code NONE}(変換なし、0〜1 の箱)で描き、
 * 真ん中へ寄せてから Z に 45° 回して対角を縦に、Y に 90° 回して絵の面を YZ 面へ。大きさはバニラの手持ちの 0.9 倍(仕様「大きさはバニラの手持ちのまま」)。
 * 木の腹・弦の向き(±Z)はゲームで確かめた向きに合わせる({@link #FLIP})。
 */
public class G1HeldItemLayer<T extends GeoAnimatable> extends BlockAndItemGeoLayer<T> {

    static final String BONE = "right_item";
    /** 手持ちの大きさ(バニラの弓の third person の 0.9 と同じ)。 */
    static final float SCALE = 0.9F;
    /** 弓の腹と弦を入れ替える(Y に 180°)。ゲームで見て決める【仮】。 */
    static final boolean FLIP = false;

    public G1HeldItemLayer(GeoRenderer<T> renderer, Function<T, ItemStack> stack) {
        super(renderer, (bone, animatable) -> BONE.equals(bone.getName()) ? stack.apply(animatable) : null, (bone, animatable) -> null);
    }

    @Override
    protected void renderStackForBone(PoseStack poseStack, GeoBone bone, ItemStack stack, T animatable, MultiBufferSource bufferSource,
                                      float partialTick, int packedLight, int packedOverlay) {
        poseStack.pushPose();
        poseStack.scale(SCALE, SCALE, SCALE);
        poseStack.mulPose(Axis.YP.rotationDegrees(FLIP ? 270.0F : 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(45.0F));
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.NONE, packedLight, packedOverlay, poseStack,
                bufferSource, Minecraft.getInstance().level, 0);
        poseStack.popPose();
    }
}
