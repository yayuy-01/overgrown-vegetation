package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.item.PlantArmor;
import com.yayuy.overgrownvegetation.item.PlantArmorItem;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.renderer.GeoArmorRenderer;

/**
 * 「寄生植物装備」を、着用者の上に GeckoLib 立体モデルで描画する。
 * 生成は {@link PlantArmorItem#createGeoRenderer} からクライアント限定で行われる。
 *
 * <p>描く直前に ItemStack の段階を {@link PlantArmorModel} へ渡す。段階 2 以降はユーザーのモデルが頭・脚にもキューブを持つ(段階 3 は「体や頭のほとんどが植物に覆われる」)ので、
 * 胸の装備 1 つなのに頭と脚のボーンも一緒に描く({@link #applyBoneVisibilityBySlot})。
 */
public class PlantArmorRenderer extends GeoArmorRenderer<PlantArmorItem> {

    public PlantArmorRenderer() {
        super(new PlantArmorModel());
    }

    private PlantArmorModel model() {
        return (PlantArmorModel) getGeoModel();
    }

    @Override
    public void prepForRender(Entity entity, ItemStack stack, EquipmentSlot slot, HumanoidModel<?> baseModel,
                              MultiBufferSource bufferSource, float partialTick, float limbSwing, float limbSwingAmount,
                              float netHeadYaw, float headPitch) {
        super.prepForRender(entity, stack, slot, baseModel, bufferSource, partialTick, limbSwing, limbSwingAmount, netHeadYaw, headPitch);
        model().setStage(PlantArmor.stageOf(stack).number);
    }

    @Override
    protected void applyBoneVisibilityBySlot(EquipmentSlot currentSlot) {
        super.applyBoneVisibilityBySlot(currentSlot);
        if (currentSlot == EquipmentSlot.CHEST && model().stage() >= 2) {
            // バニラは胸の描画では頭・脚を隠す(baseModel の visible が false)ので、ここは無条件に出す。
            setBoneVisible(this.head, true);
            setBoneVisible(this.rightLeg, true);
            setBoneVisible(this.leftLeg, true);
        }
    }
}
