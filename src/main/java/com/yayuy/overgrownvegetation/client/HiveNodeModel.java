package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.HiveNodeEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「ハイヴノード」のモデル定義。実体は GeckoLib が読み込む3つのリソースファイル。
 *
 * <ul>
 *     <li>モデル形状: {@code assets/overgrown_vegetation/geo/hive_node.geo.json}</li>
 *     <li>アニメーション: {@code assets/overgrown_vegetation/animations/hive_node.animation.json}</li>
 *     <li>テクスチャ: {@code assets/overgrown_vegetation/textures/entity/hive_node.png}</li>
 * </ul>
 *
 * <p>2026-09-18 からモデルはユーザー製の 512×512 テクスチャ版(ボーン {@code hivenode} / {@code ami})。
 * テクスチャは <b>8 コマの mcmeta アニメーション</b>(縦に 8 枚並べた 512×4096、{@code frametime} 25 =
 * 1.25 秒、{@code interpolate} 有り)で、10 秒かけて {@code hivenode.png} → {@code hivenode-2.png} → 元へ
 * じんわり移り変わる。GeckoLib は mcmeta アニメを {@code AnimatableTexture} で自前再生する。
 * 元の 2 枚からコマを作り直すツールは {@code tools/hive-texture/}。
 *
 * <p>発光マスク({@code _glowmask})は使わない(網の穴が黒くなる。{@link HiveNodeRenderer} 参照)。
 * ノード自身が小部屋に明るさ 15 の光源ブロックを置くので、2 枚目の明るい点はそのまま明るく見える。
 *
 * <p>geo はユーザーの {@code hivenode.geo.json} の y を 16 px 下げたもの(球の中心を当たり判定の中心 =
 * 足元 + 1.5 ブロックに合わせるため)。
 */
public class HiveNodeModel extends LodGeoModel<HiveNodeEntity> {

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/hive_node.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/hive_node.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/hive_node.animation.json");

    @Override
    public ResourceLocation getModelResource(HiveNodeEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(HiveNodeEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(HiveNodeEntity animatable) {
        return ANIMATION;
    }
}
