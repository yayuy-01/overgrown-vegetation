package com.yayuy.overgrownvegetation.client.attach;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.gear.attach.ArmorAttachmentItem;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoArmorRenderer;
import software.bernie.geckolib.renderer.GeoRenderer;

/**
 * 付け物 1 種を、着た体の上に GeckoLib の立体モデルで描く(2026-11-21 夜。モデル・絵は仮)。
 *
 * <p>ファイルは付け物ごと・部位ごとに分けてある(Blockbench で作り直した物に差し替えやすいように):
 * <ul>
 *   <li>形: {@code geo/armor_attachment/<id>_<部位>.geo.json}(無ければ {@code <id>.geo.json})。骨はバニラの防具の geo と同じ名前
 *   ({@code armorHead}・{@code armorBody}・{@code armorRightArm}…・{@code armorRightBoot}…)。</li>
 *   <li>絵: {@code textures/armor_attachment/<id>.png}。</li>
 * </ul>
 * 部位ごとのファイルに描きたい物だけを入れるので、骨はぜんぶ見せる(バニラの防具は部位で骨を隠すが、ベルトは脚の防具なのに胴の骨に付く)。
 */
public class AttachmentGeoRenderer extends GeoArmorRenderer<ArmorAttachmentItem> {

    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/armor/protective_mask.animation.json");

    public AttachmentGeoRenderer() {
        super(new Model());
    }

    /** {@code item} を {@code slot} に着たときの形のファイル(無ければ null。描かない)。 */
    @Nullable
    public static ResourceLocation modelFor(ArmorAttachmentItem item, EquipmentSlot slot) {
        String id = BuiltInRegistries.ITEM.getKey(item).getPath();
        ResourceLocation perPart = OvergrownVegetation.id("geo/armor_attachment/" + id + "_" + part(slot) + ".geo.json");
        if (GeckoLibCache.getBakedModels().containsKey(perPart)) return perPart;
        ResourceLocation shared = OvergrownVegetation.id("geo/armor_attachment/" + id + ".geo.json");
        return GeckoLibCache.getBakedModels().containsKey(shared) ? shared : null;
    }

    private static String part(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "head";
            case CHEST -> "chest";
            case LEGS -> "legs";
            default -> "feet";
        };
    }

    @Override
    protected void applyBoneVisibilityBySlot(EquipmentSlot currentSlot) {
        HumanoidModel<?> m = (HumanoidModel<?>) this;
        // 部位ごとに見せる骨(armor_attachments.json の bones。書いていなければ全部)
        com.yayuy.overgrownvegetation.gear.attach.AttachTable.Entry e =
                com.yayuy.overgrownvegetation.gear.attach.ArmorAttachments.entry(getCurrentStack());
        java.util.List<String> only = e == null ? null : e.bones.get(part(currentSlot));
        java.util.function.Predicate<String> show = name -> only == null || only.isEmpty() || only.contains(name);
        setBoneVisible(this.head, m.head.visible && show.test("armorHead"));
        setBoneVisible(this.body, m.body.visible && show.test("armorBody"));
        setBoneVisible(this.rightArm, m.rightArm.visible && show.test("armorRightArm"));
        setBoneVisible(this.leftArm, m.leftArm.visible && show.test("armorLeftArm"));
        setBoneVisible(this.rightLeg, m.rightLeg.visible && show.test("armorRightLeg"));
        setBoneVisible(this.leftLeg, m.leftLeg.visible && show.test("armorLeftLeg"));
        setBoneVisible(this.rightBoot, m.rightLeg.visible && show.test("armorRightBoot"));
        setBoneVisible(this.leftBoot, m.leftLeg.visible && show.test("armorLeftBoot"));
    }

    /** 同じ防具に付いている同じ付け物の数(2 枚目・3 枚目の骨を出すため。{@link AttachmentLayer} が描く前に入れる)。 */
    private int count = 1;

    public void setCount(int count) {
        this.count = count;
    }

    @Override
    public void preRender(com.mojang.blaze3d.vertex.PoseStack poseStack, ArmorAttachmentItem animatable, software.bernie.geckolib.cache.object.BakedGeoModel model,
            @Nullable net.minecraft.client.renderer.MultiBufferSource bufferSource, @Nullable com.mojang.blaze3d.vertex.VertexConsumer buffer, boolean isReRender,
            float partialTick, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
        for (software.bernie.geckolib.cache.object.GeoBone b : model.topLevelBones()) hideByCount(b);
    }

    /** 名前が count2・count3 で始まる骨は、同じ付け物がその枚数以上付いたときだけ見せる(子の骨ごと)。 */
    private void hideByCount(software.bernie.geckolib.cache.object.GeoBone bone) {
        String n = bone.getName();
        if (n.startsWith("count2") || n.startsWith("count3")) {
            // 骨の隠れは描くたびに戻らない(親の骨を見せても子の hidden は残る)ので、毎回はっきり決める
            boolean hide = n.startsWith("count2") ? count < 2 : count < 3;
            bone.setHidden(hide);
            if (hide) return;
        }
        for (software.bernie.geckolib.cache.object.GeoBone c : bone.getChildBones()) hideByCount(c);
    }

    private static final class Model extends GeoModel<ArmorAttachmentItem> {
        @Override
        public ResourceLocation getModelResource(ArmorAttachmentItem animatable, @Nullable GeoRenderer<ArmorAttachmentItem> renderer) {
            if (renderer instanceof GeoArmorRenderer<ArmorAttachmentItem> r && r.getCurrentSlot() != null) {
                ResourceLocation loc = modelFor(animatable, r.getCurrentSlot());
                if (loc != null) return loc;
            }
            return getModelResource(animatable);
        }

        @Override
        public ResourceLocation getModelResource(ArmorAttachmentItem animatable) {
            return OvergrownVegetation.id("geo/armor_attachment/" + BuiltInRegistries.ITEM.getKey(animatable).getPath() + ".geo.json");
        }

        @Override
        public ResourceLocation getTextureResource(ArmorAttachmentItem animatable) {
            return OvergrownVegetation.id("textures/armor_attachment/" + BuiltInRegistries.ITEM.getKey(animatable).getPath() + ".png");
        }

        @Override
        public ResourceLocation getAnimationResource(ArmorAttachmentItem animatable) {
            return ANIMATION;
        }
    }
}
