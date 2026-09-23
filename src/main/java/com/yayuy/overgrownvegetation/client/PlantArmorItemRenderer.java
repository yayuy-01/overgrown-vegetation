package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.item.PlantArmor;
import com.yayuy.overgrownvegetation.item.PlantArmorItem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

/**
 * 「寄生植物装備」のアイテムアイコンを、平らなスプライトではなく
 * 着用時と同じ GeckoLib 立体モデルで描画する(ユーザー要望 2026-09-16)。
 *
 * <p>部位の出し分け・枠への収め方・共有モデルの後始末は {@link GeoArmorIconRenderer} 側。
 * 段階は描く直前に {@link PlantArmorModel#setStage} へ渡す。段階 2 以降のアイコンは頭と脚も一緒に出す(ユーザーのモデルが頭・脚にもキューブを持つ)。
 */
public class PlantArmorItemRenderer extends GeoArmorIconRenderer<PlantArmorItem> {

    public PlantArmorItemRenderer() {
        super(new PlantArmorModel());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext transformType, PoseStack poseStack,
                             MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        ((PlantArmorModel) getGeoModel()).setStage(PlantArmor.stageOf(stack).number);
        super.renderByItem(stack, transformType, poseStack, bufferSource, packedLight, packedOverlay);
    }

    @Override
    protected Set<String> shownBones(ArmorItem.Type type, ItemStack stack) {
        if (PlantArmor.stageOf(stack).number >= 2) {
            return Set.of("armorHead", "armorBody", "armorRightArm", "armorLeftArm", "armorRightLeg", "armorLeftLeg");
        }
        return super.shownBones(type, stack);
    }
}
