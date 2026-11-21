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
        setBoneVisible(this.head, m.head.visible);
        setBoneVisible(this.body, m.body.visible);
        setBoneVisible(this.rightArm, m.rightArm.visible);
        setBoneVisible(this.leftArm, m.leftArm.visible);
        setBoneVisible(this.rightLeg, m.rightLeg.visible);
        setBoneVisible(this.leftLeg, m.leftLeg.visible);
        setBoneVisible(this.rightBoot, m.rightLeg.visible);
        setBoneVisible(this.leftBoot, m.leftLeg.visible);
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
