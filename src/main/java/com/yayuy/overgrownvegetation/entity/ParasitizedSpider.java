package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 「寄生されたクモ」— フェーズ4以降の進化型感染 Mob。バニラのクモが元々持つ「プレイヤーだけを狙う」
 * 索敵/近接攻撃 Goal は {@link ParasitizedTargetGoal#stripVanillaHostileGoals} で取り除き、代わりに
 * {@link ParasitizedTargetGoal} により他の Mob やプレイヤーを種族を問わず見つけ次第攻撃する
 * (攻撃されると「胞子感染2」に感染する)。
 *
 * <p>見た目は GeckoLib 経由の専用モデル(Blockbench 製、寄生植物が生えたクモ。{@link ParasitizedCreeper} と
 * 同じ構成)に差し替えている。モデル/アニメーション/テクスチャの実体は
 * {@code assets/overgrown_vegetation/geo/overgrown_spider.geo.json}・
 * {@code animations/overgrown_spider.animation.json}・
 * {@code textures/entity/overgrown_spider.png}(モデル・テクスチャはユーザー提供の本物。
 * アニメーションはバニラのクモに準じた idle/walk を Claude が作成、頭付近から生えたシダ
 * ({@code fern1}〜{@code fern3})には待機中・歩行中とも風で揺れる緩やかなスウェイを付けている)。
 * 差し替え時はこのクラスの変更は不要(アニメーション名を変える場合は下の
 * {@code IDLE_ANIM}/{@code WALK_ANIM} だけ合わせる)。
 */
public class ParasitizedSpider extends Spider implements ParasitizedMob, GeoEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public ParasitizedSpider(EntityType<? extends ParasitizedSpider> type, Level level) {
        super(type, level);
        ParasitizedTargetGoal.stripVanillaHostileGoals(this);
        ParasitizedTargetGoal.install(this);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "move", 5,
                state -> state.setAndContinue(MobAnimation.isWalking(state) ? WALK_ANIM : IDLE_ANIM)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
