package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 「寄生された羊」— フェーズ4以降の進化型感染 Mob。敵対化しており他の Mob や
 * プレイヤーを見つけ次第攻撃する({@link ParasitizedTargetGoal} 参照。攻撃されると「胞子感染2」に感染する)。
 *
 * <p>バニラの {@link Sheep} が持つ平和的な習性(殴られて逃げる/繁殖/餌でおびき寄せられる/親について歩く)は
 * 「ゾンビなどと同じ完全な敵対 Mob」というユーザー要望に反するため全て取り除いている
 * ({@link ParasitizedTargetGoal#stripPassiveAnimalGoals}）。草を食べて毛を再生する
 * {@code eatBlockGoal} は敵対性に直接関わらない資源系の習性なので残してある。
 *
 * <p>見た目は GeckoLib 経由の専用モデル(Blockbench 製、寄生植物が生えた羊。{@link ParasitizedPig} と
 * 同じ構成)に差し替えている。モデル/アニメーション/テクスチャの実体は
 * {@code assets/overgrown_vegetation/geo/overgrown_sheep.geo.json}・
 * {@code animations/overgrown_sheep.animation.json}・
 * {@code textures/entity/overgrown_sheep.png}（モデル・テクスチャはユーザー提供の本物。
 * アニメーションはバニラの羊に準じた idle/walk を Claude が作成、体から生えたシダ
 * ({@code fern1}〜{@code fern3})には待機中に風で揺れる緩やかなスウェイを付けている）。
 * 差し替え時はこのクラスの変更は不要（アニメーション名を変える場合は下の
 * {@code IDLE_ANIM}/{@code WALK_ANIM} だけ合わせる）。
 */
public class ParasitizedSheep extends Sheep implements ParasitizedMob, GeoEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public ParasitizedSheep(EntityType<? extends ParasitizedSheep> type, Level level) {
        super(type, level);
        ParasitizedTargetGoal.stripPassiveAnimalGoals(this);
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
