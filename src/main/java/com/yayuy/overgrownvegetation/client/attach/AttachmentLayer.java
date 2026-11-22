package com.yayuy.overgrownvegetation.client.attach;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.gear.attach.ArmorAttachmentItem;
import com.yayuy.overgrownvegetation.gear.attach.ArmorAttachments;
import com.yayuy.overgrownvegetation.registry.ModArmorAttach;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.yayuy.overgrownvegetation.port.component.ItemContainerContents;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import com.yayuy.overgrownvegetation.port.component.OvItemData;

/**
 * 着ている防具に付いた付け物を体の上に描く層(2026-11-21 夜)。プレイヤー(太い腕・細い腕)と防具立てに足す({@link AttachClient})。
 * 同じ防具に同じ物が 2 枚以上付いていても 1 回だけ描く(形が同じなので)。
 */
public class AttachmentLayer<T extends LivingEntity, M extends HumanoidModel<T>> extends RenderLayer<T, M> {

    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final Map<Item, AttachmentGeoRenderer> RENDERERS = new HashMap<>();

    public AttachmentLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void copyPose(HumanoidModel from, HumanoidModel to) {
        from.copyPropertiesTo(to);
    }


    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity, float limbSwing, float limbSwingAmount,
            float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        for (EquipmentSlot slot : SLOTS) {
            ItemStack armor = entity.getItemBySlot(slot);
            ItemContainerContents c = armor.isEmpty() ? null : OvItemData.get(armor, ModArmorAttach.ATTACHMENTS.get());
            if (c == null) continue;
            Set<Item> drawn = new HashSet<>(4);
            for (ItemStack s : c.nonEmptyItems()) {
                if (!(s.getItem() instanceof ArmorAttachmentItem item) || !drawn.add(item)) continue;
                if (AttachmentGeoRenderer.modelFor(item, slot) == null) continue;
                AttachmentGeoRenderer r = RENDERERS.computeIfAbsent(item, k -> new AttachmentGeoRenderer());
                r.setCount(ArmorAttachments.count(armor, item));
                r.prepForRender(entity, s, slot, getParentModel(), buffer, partialTick, limbSwing, limbSwingAmount, netHeadYaw, headPitch);
                copyPose(getParentModel(), r);
                r.renderToBuffer(poseStack, null, packedLight, OverlayTexture.NO_OVERLAY, -1);
            }
        }
    }
}
