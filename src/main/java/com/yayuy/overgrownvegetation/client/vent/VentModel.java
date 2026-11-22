package com.yayuy.overgrownvegetation.client.vent;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.survival.vent.VentBlock;
import com.yayuy.overgrownvegetation.survival.vent.VentBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.model.GeoModel;

/**
 * 地脈の換気機の模型(2026-11-22)。置いた機械とアイテムで同じ geo・絵・アニメを使う。正本は Blockbench の
 * {@code デスクトップ\blockbench\zyokyosouti_kai_B_yayuy.bbmodel}(書き出して名前を ley_ventilator にそろえた)。
 *
 * <p>絵は .bbmodel の 2 枚(11-22 ユーザー「FE が通ってるときに格子が光るように」): 止まっている間は格子が暗い {@code ley_ventilator.png}、
 * 動いている間({@link VentBlock#RUNNING})は格子が青い {@code ley_ventilator_running.png}。青い格子は
 * {@code ley_ventilator_running_glowmask.png} で暗い所でも光る({@link VentRenderer})。アイテムは止まった絵。
 */
public final class VentModel<T extends GeoAnimatable> extends GeoModel<T> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/ley_ventilator.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/ley_ventilator.png");
    private static final ResourceLocation TEXTURE_RUNNING = OvergrownVegetation.id("textures/entity/ley_ventilator_running.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/ley_ventilator.animation.json");

    /** 動いている(格子が光る)か。 */
    static boolean running(Object animatable) {
        return animatable instanceof VentBlockEntity be && be.getBlockState().hasProperty(VentBlock.RUNNING)
                && be.getBlockState().getValue(VentBlock.RUNNING);
    }

    @Override
    public ResourceLocation getModelResource(T animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(T animatable) {
        return running(animatable) ? TEXTURE_RUNNING : TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(T animatable) {
        return ANIMATION;
    }
}
