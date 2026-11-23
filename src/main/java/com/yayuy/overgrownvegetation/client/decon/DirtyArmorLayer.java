package com.yayuy.overgrownvegetation.client.decon;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.survival.decon.GearDirt;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;

/**
 * 体に着た汚れた防具に、黄緑の胞子の粉を重ねて描く(2026-11-14 サバイバルの背骨 段 3b、決定 33)。防具の模型より少しだけふくらませた
 * 同じ形の模型に、防具の絵の不透明な所だけに点を置いた絵({@link DirtOverlays#armorOverlay})を貼る。
 * バニラの防具の模型を使う防具だけ(GeckoLib で描く防護マスクなどは描かない【仮】)。
 */
public class DirtyArmorLayer<T extends LivingEntity, M extends HumanoidModel<T>> extends RenderLayer<T, M> {

    private final HumanoidArmorModel<T> inner;
    private final HumanoidArmorModel<T> outer;

    public DirtyArmorLayer(RenderLayerParent<T, M> parent, HumanoidArmorModel<T> inner, HumanoidArmorModel<T> outer) {
        super(parent);
        this.inner = inner;
        this.outer = outer;
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, T entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HEAD}) {
            ItemStack stack = entity.getItemBySlot(slot);
            int stage = GearDirt.stage(stack);
            if (stage == 0 || !(stack.getItem() instanceof ArmorItem armor) || armor.getEquipmentSlot() != slot) continue;
            if (stack.getItem() instanceof software.bernie.geckolib.animatable.GeoItem) continue;
            ArmorMaterial material = armor.getMaterial();
            boolean innerModel = slot == EquipmentSlot.LEGS;
            ResourceLocation base = armorTexture(entity, stack, material, innerModel, slot);
            ResourceLocation dust = DirtOverlays.armorOverlay(base, stage);
            if (dust == null) continue;
            HumanoidArmorModel<T> model = innerModel ? inner : outer;
            getParentModel().copyPropertiesTo(model);
            setVisibility(model, slot);
            VertexConsumer vc = buffers.getBuffer(RenderType.armorCutoutNoCull(dust));
            model.renderToBuffer(pose, vc, light, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /**
     * 1.20.1: 防具の 1 枚目の絵(1.21 の {@code material.layers().get(0)} と {@code ClientHooks.getArmorTexture})。
     * Forge 1.20.1 の {@code HumanoidArmorLayer#getArmorResource} と同じ組み立て(型の接尾辞なし)。
     */
    private static ResourceLocation armorTexture(net.minecraft.world.entity.Entity entity, ItemStack stack, ArmorMaterial material, boolean inner,
                                                 EquipmentSlot slot) {
        String texture = material.getName();
        String domain = "minecraft";
        int idx = texture.indexOf(':');
        if (idx != -1) {
            domain = texture.substring(0, idx);
            texture = texture.substring(idx + 1);
        }
        String path = String.format(java.util.Locale.ROOT, "%s:textures/models/armor/%s_layer_%d.png", domain, texture, inner ? 2 : 1);
        return ResourceLocation.parse(net.minecraftforge.client.ForgeHooksClient.getArmorTexture(entity, stack, path, slot, null));
    }

    private static void setVisibility(HumanoidModel<?> model, EquipmentSlot slot) {
        model.setAllVisible(false);
        switch (slot) {
            case HEAD -> {
                model.head.visible = true;
                model.hat.visible = true;
            }
            case CHEST -> {
                model.body.visible = true;
                model.rightArm.visible = true;
                model.leftArm.visible = true;
            }
            case LEGS -> {
                model.body.visible = true;
                model.rightLeg.visible = true;
                model.leftLeg.visible = true;
            }
            case FEET -> {
                model.rightLeg.visible = true;
                model.leftLeg.visible = true;
            }
            default -> {
            }
        }
    }
}
