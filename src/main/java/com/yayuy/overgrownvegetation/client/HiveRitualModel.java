package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.HiveRitualEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 儀式の蔦({@link HiveRitualEntity})のモデル定義。
 * <ul>
 *     <li>{@code geo/hive_ritual_vines.geo.json} — Blockbench の儀式モデル(PAL テンプレ + 蔦・花・胞子の塊・芽)から
 *     プレイヤーの骨のキューブだけ外したもの。空の骨(body/torso/head/camera/腕/脚)は残っていて、胸の塊と芽の親、
 *     カメラの位置取りに使う。</li>
 *     <li>{@code animations/hive_ritual_vines.animation.json} — 儀式アニメ(37 秒)。</li>
 *     <li>{@code textures/entity/hive_ritual_vines.png} — <b>仮</b>(過繁茂したリアナの複製)。蔦の UV は 16×16 前提。</li>
 * </ul>
 * 3 つとも {@code tools/ritual-assets/run.ps1} が Blockbench の書き出しから作り直す。
 */
public class HiveRitualModel extends GeoModel<HiveRitualEntity> {

    @Override
    public ResourceLocation getModelResource(HiveRitualEntity animatable) {
        return OvergrownVegetation.id("geo/hive_ritual_vines.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(HiveRitualEntity animatable) {
        return OvergrownVegetation.id("textures/entity/hive_ritual_vines.png");
    }

    @Override
    public ResourceLocation getAnimationResource(HiveRitualEntity animatable) {
        return OvergrownVegetation.id("animations/hive_ritual_vines.animation.json");
    }
}
