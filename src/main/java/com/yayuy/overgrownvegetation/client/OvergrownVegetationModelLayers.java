package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.model.geom.ModelLayerLocation;

/**
 * 「過繁茂した(MOB名)」の専用モデル({@code Parasitized*Model})が使う {@link ModelLayerLocation} 一覧。
 * バニラの {@code net.minecraft.client.model.geom.ModelLayers} と同じ役割(レイヤー定義の登録先/
 * {@link net.minecraft.client.renderer.entity.EntityRendererProvider.Context#bakeLayer} で使うキー)。
 * 登録は {@link OvergrownVegetationClient} の {@code registerLayerDefinitions}。
 */
public final class OvergrownVegetationModelLayers {

    /** 浸食度の苔(wide)。素のプレイヤーモデルより膨らませてある。 */
    public static final ModelLayerLocation EROSION_DEFAULT = main("erosion_default");

    /** 浸食度の苔(slim)。 */
    public static final ModelLayerLocation EROSION_SLIM = main("erosion_slim");

    /** 攻城戦ボスが落とす爆弾({@link SiegeBombModel})。 */
    public static final ModelLayerLocation SIEGE_BOMB = main("siege_bomb");

    /** 追加防具の枠ごとの体の模型(2026-10-15、{@link ExtraArmorLayer})。 */
    public static final ModelLayerLocation EXTRA_ARMOR_HEAD = main("extra_armor_head");
    public static final ModelLayerLocation EXTRA_ARMOR_BODY = main("extra_armor_body");
    public static final ModelLayerLocation EXTRA_ARMOR_WAIST = main("extra_armor_waist");
    public static final ModelLayerLocation EXTRA_ARMOR_FEET = main("extra_armor_feet");

    private static ModelLayerLocation main(String name) {
        return new ModelLayerLocation(OvergrownVegetation.id(name), "main");
    }

    private OvergrownVegetationModelLayers() {
    }
}
