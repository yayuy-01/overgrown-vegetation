package com.yayuy.overgrownvegetation.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * 汚染水草 — 汚染シダ({@link BlightFernEntity})の水中版。
 *
 * <p>「汚染シダとすべて同じ仕様」で、違いは湧いた場所が水中か地上かだけ。よって挙動は
 * {@link BlightFernEntity} をそのまま継承し(侵食・完了時の次世代スポーン・当たり判定・
 * フェーズポイント・各種除外判定など、{@code instanceof BlightFernEntity} で拾われるものは
 * すべてそのまま効く)、ここでは水流で流されないようにするだけ。
 *
 * <p>水中で窒息しないための「息継ぎ不要」は、{@code canBreatheUnderwater()} がバニラで final の
 * ため、エンティティタイプタグ {@code #minecraft:can_breathe_under_water}
 * ({@code data/minecraft/tags/entity_type/can_breathe_under_water.json})で付与している。
 *
 * <p>汚染シダを湧かせる各所は {@link BlightFernPlacement#spawn} を経由し、湧く地点が水中なら
 * このエンティティに、地上なら {@link BlightFernEntity} に振り分けられる。
 *
 * <p>見た目は GeckoLib 経由({@code geo/blight_water_grass.geo.json} ほか)。仮モデルは
 * 大きな水草。差し替えるときはそのリソース3点を上書きするだけでよい。
 */
public class BlightWaterGrassEntity extends BlightFernEntity {

    public BlightWaterGrassEntity(EntityType<? extends BlightWaterGrassEntity> type, Level level) {
        super(type, level);
    }

    /** 水流で押し流されない(湧いた場所に根を張っている)。 */
    @Override
    public boolean isPushedByFluid() {
        return false;
    }
}
