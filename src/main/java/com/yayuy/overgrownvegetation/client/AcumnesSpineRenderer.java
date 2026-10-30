package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.entity.AcumnesSpine;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * アクムネスの棘(弾)の描画。モデルの先は −Z を向いているので、<b>速度の向きへ −Z を回す</b>
 * (バニラの矢のように yRot / xRot の符号の約束に頼らない)。大きさは本体と同じ体格 1.8 倍(針先の棘がそのまま飛んで見える)。
 */
public class AcumnesSpineRenderer extends GeoEntityRenderer<AcumnesSpine> {

    private static final Vector3f FORWARD = new Vector3f(0.0F, 0.0F, -1.0F);

    public AcumnesSpineRenderer(EntityRendererProvider.Context context) {
        super(context, new AcumnesSpineModel());
        this.withScale(1.8F);
        this.shadowRadius = 0.0F;
    }

    @Override
    protected void applyRotations(AcumnesSpine animatable, PoseStack poseStack, float ageInTicks, float rotationYaw,
                                  float partialTick, float nativeScale) {
        Vec3 v = animatable.getDeltaMovement();
        if (v.lengthSqr() < 1.0E-6D) {
            return;
        }
        Vector3f to = new Vector3f((float) v.x, (float) v.y, (float) v.z).normalize();
        poseStack.mulPose(new Quaternionf().rotationTo(FORWARD, to));
    }
}
