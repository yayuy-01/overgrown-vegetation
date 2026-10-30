package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.item.PoweredArmorItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「パワードアーマー」のモデル定義(順 1 の着手順 6)。4 部位で同じ geo・テクスチャを共有し、部位ごとの出し分けは
 * GeckoLib の {@code GeoArmorRenderer} がボーン名({@code armorHead} ほか)で行う。
 *
 * <p><b>モデルとテクスチャはユーザーが作る</b>(ユーザー指定 2026-10-03)。2026-10-05 にユーザーの Blockbench のモデルへ差し替えた
 * (色は tools/powered-suit/paint.sh、geo と png は tools/powered-suit/export.sh で .bbmodel から書き出し)。
 * 差し替えは下の 2 ファイルを上書きするだけ。ボーン名を GeckoLib の防具の型({@code armorHead} / {@code armorBody} /
 * {@code armorRightArm} / {@code armorLeftArm} / {@code armorRightLeg} / {@code armorLeftLeg} /
 * {@code armorRightBoot} / {@code armorLeftBoot})から変えるときだけ、ここかレンダラーも直す。
 *
 * <p>アニメーションはしないが、{@code GeoModel} が参照先を要求するので空の animation.json を置いてある
 * ({@code animations/} には {@code .animation.json} 以外を置かない —— 1 つでも置くと MOD 全体のアニメが死ぬ)。
 */
public class PoweredArmorModel extends GeoModel<PoweredArmorItem> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/armor/powered_armor.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/armor/powered_armor.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/armor/powered_armor.animation.json");

    @Override
    public ResourceLocation getModelResource(PoweredArmorItem animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(PoweredArmorItem animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(PoweredArmorItem animatable) {
        return ANIMATION;
    }
}
