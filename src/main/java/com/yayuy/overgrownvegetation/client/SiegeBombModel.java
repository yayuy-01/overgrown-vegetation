package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * 攻城戦ボスが落とす爆弾({@link com.yayuy.overgrownvegetation.entity.SiegeBombEntity})のモデル —
 * <b>胞子の塊</b>。芯の塊に 6 つの瘤と 3 つの小さな粒が寄り集まった、ぼこぼこした球。
 * テクスチャは {@code textures/entity/siege_bomb.png}(32×32、黄緑の胞子嚢に暗緑の斑と孔)。
 */
public final class SiegeBombModel {

    private final ModelPart root;

    public SiegeBombModel(ModelPart root) {
        this.root = root;
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        PartDefinition core = root.addOrReplaceChild("core",
                CubeListBuilder.create().texOffs(0, 0).addBox(-4.0F, -4.0F, -4.0F, 8.0F, 8.0F, 8.0F),
                PartPose.ZERO);

        // 6 つの瘤(各面の外側へ少しはみ出す)。
        float[][] lumps = {
                {4.5F, 0.0F, 0.0F}, {-4.5F, 0.0F, 0.0F}, {0.0F, 4.5F, 0.0F},
                {0.0F, -4.5F, 0.0F}, {0.0F, 0.0F, 4.5F}, {0.0F, 0.0F, -4.5F}
        };
        for (int i = 0; i < lumps.length; i++) {
            core.addOrReplaceChild("lump" + i,
                    CubeListBuilder.create().texOffs(0, 16).addBox(-2.5F, -2.5F, -2.5F, 5.0F, 5.0F, 5.0F),
                    PartPose.offsetAndRotation(lumps[i][0], lumps[i][1], lumps[i][2], 0.3F * i, 0.5F * i, 0.2F * i));
        }
        // 3 つの小さな粒(斜めの隙間を埋める)。
        float[][] grains = {{3.5F, 3.5F, 0.0F}, {-3.5F, 0.0F, 3.5F}, {0.0F, -3.5F, -3.5F}};
        for (int i = 0; i < grains.length; i++) {
            core.addOrReplaceChild("grain" + i,
                    CubeListBuilder.create().texOffs(20, 16).addBox(-1.5F, -1.5F, -1.5F, 3.0F, 3.0F, 3.0F),
                    PartPose.offsetAndRotation(grains[i][0], grains[i][1], grains[i][2], 0.6F, 0.4F * i, 0.8F));
        }

        return LayerDefinition.create(mesh, 32, 32);
    }

    public void render(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay, int color) {
        root.render(poseStack, buffer, packedLight, packedOverlay, color);
    }
}
