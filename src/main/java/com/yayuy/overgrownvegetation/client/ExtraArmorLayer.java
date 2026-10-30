package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.item.ExtraArmor;
import com.yayuy.overgrownvegetation.item.ExtraArmorItem;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 追加防具({@link ExtraArmor})を、ふつうの防具と同じようにプレイヤーの体へ描く層(2026-10-15 ユーザー指定)。
 *
 * <p>絵は品ごとの {@link ExtraArmorItem#wornTexture}(バニラの防具の層と同じ 64×32 の並び)。枠ごとに少しずつ膨らませた体の模型を使い、
 * バニラが使う膨らみ(スキンの上着 0.25・帽子とズボン 0.5・兜と胸当て 1.0)とぶつからないようにしてある
 * (頭 0.75 / 体 0.65 / 腰 0.375 / 足 0.75。仮決め)。兜・胸当てを着けると、その下の追加防具は隠れる(ふつうの防具と同じ重なり方)。
 * 角灯のように防具の層の絵では描けない形の品({@link ExtraArmorItem#renderWornItem})は、品の模型を枠の場所に下げて描く。
 * 描くのはプレイヤーだけ。1 人称の腕には描かない。他のプレイヤーの分は {@code network/ExtraArmorPayload} で届く。
 */
public class ExtraArmorLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    private final HumanoidModel<AbstractClientPlayer>[] models;

    @SuppressWarnings("unchecked")
    public ExtraArmorLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent, EntityModelSet modelSet) {
        super(parent);
        this.models = new HumanoidModel[ExtraArmor.SIZE];
        for (ExtraArmor.Part part : ExtraArmor.Part.values()) {
            this.models[part.ordinal()] = new HumanoidModel<>(modelSet.bakeLayer(OvergrownVegetationModelLayers.extraArmor(part)));
        }
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, AbstractClientPlayer player,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (player.isInvisible()) {
            return;
        }
        ExtraArmor armor = ExtraArmor.of(player);
        for (ExtraArmor.Part part : ExtraArmor.Part.values()) {
            ItemStack stack = armor.get(part.ordinal());
            if (!(stack.getItem() instanceof ExtraArmorItem item) || item.extraArmorPart() != part) {
                continue;
            }
            if (item.renderWornItem()) {
                renderHangingItem(poseStack, buffer, stack, part);
                continue;
            }
            ResourceLocation texture = item.wornTexture();
            if (texture == null) {
                continue;
            }
            HumanoidModel<AbstractClientPlayer> model = models[part.ordinal()];
            getParentModel().copyPropertiesTo(model);
            showParts(model, part);
            VertexConsumer consumer = buffer.getBuffer(RenderType.armorCutoutNoCull(texture));
            model.renderToBuffer(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
        }
    }

    /**
     * 角灯のような「品の形そのもの」を、枠の場所に下げて描く(今は腰の右の横だけ)。置いたときの吊り下げの模型を小さくして、
     * 鎖の付け根が腰の高さに来るように吊る。光る品なので明るさは最大で描く。
     */
    private void renderHangingItem(PoseStack poseStack, MultiBufferSource buffer, ItemStack stack, ExtraArmor.Part part) {
        if (part != ExtraArmor.Part.WAIST || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)) {
            return;
        }
        net.minecraft.world.level.block.state.BlockState state = blockItem.getBlock().defaultBlockState();
        if (state.hasProperty(net.minecraft.world.level.block.LanternBlock.HANGING)) {
            state = state.setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true);
        }
        poseStack.pushPose();
        getParentModel().body.translateAndRotate(poseStack);
        // 体の模型の中(y が下向き)で、腰の右の横へ。
        poseStack.translate(-0.36D, 0.62D, 0.0D);
        poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(180.0F));
        float scale = 0.42F;
        poseStack.scale(scale, scale, scale);
        poseStack.translate(-0.5D, -1.0D, -0.5D);
        net.minecraft.client.Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, poseStack, buffer,
                net.minecraft.client.renderer.LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }

    /** バニラの防具と同じ部位の分け方(頭 = 頭と帽子 / 体 = 胴と腕 / 腰 = 胴と脚 / 足 = 脚)。 */
    private static void showParts(HumanoidModel<?> model, ExtraArmor.Part part) {
        model.setAllVisible(false);
        switch (part) {
            case HEAD -> {
                model.head.visible = true;
                model.hat.visible = true;
            }
            case BODY -> {
                model.body.visible = true;
                model.rightArm.visible = true;
                model.leftArm.visible = true;
            }
            case WAIST -> {
                model.body.visible = true;
                model.rightLeg.visible = true;
                model.leftLeg.visible = true;
            }
            case FEET -> {
                model.rightLeg.visible = true;
                model.leftLeg.visible = true;
            }
        }
    }
}
