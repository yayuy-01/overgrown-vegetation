package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ParasitizedCreeper;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * アイアンゴーレム・スノーゴーレムに「近くの過繫茂 Mob({@link MobGroup} のどれかに属する Mob)を
 * 自発的に襲う」ターゲット選択 Goal を仕込む。
 *
 * <p>バニラのゴーレムが自発的に狙うのは {@link Enemy} を実装した Mob だけ
 * (アイアンゴーレムは {@code NearestAttackableTargetGoal<>(this, Mob.class, 5, false, false,
 * e -> e instanceof Enemy && !(e instanceof Creeper))}、スノーゴーレムは {@code Enemy} 全部)。
 * ところがこの MOD の過繫茂 Mob はほとんどが {@link Enemy} を実装していない —
 * 「過繫茂した牛/羊/豚」はバニラの {@code Cow}/{@code Sheep}/{@code Pig} 派生、アルボラや
 * ヴェロフィル等の独自 Mob も {@code PathfinderMob} 派生なので、ゴーレムの索敵にまったく
 * 引っかからなかった(村に湧いた過繫茂 Mob を殴られるまで放置する、という症状)。
 * {@link Enemy} 実装済みの数種(バニラの {@code Monster} 派生 = 過繫茂したゾンビ/クモ・
 * 過繫茂の盾持ち・ファランギオターブス)だけがたまたま襲われていた。
 *
 * <p>そこで「{@link Enemy} かどうか」ではなく「{@link MobGroup} に属しているか」で狙う
 * Goal を追加する — 判定基準をエンティティタイプタグ
 * ({@code #overgrown_vegetation:group_1}〜{@code group_7})に寄せることで、Mob を増やしても
 * このクラスを触らずに済み、データパックからも差し替えられる(ユーザー選択: グループ所属の
 * 過繫茂 Mob は無害な小動物(グループ2)も含めて全部襲う)。
 *
 * <p>バニラの {@link Creeper} 除外はあえて引き継がない — {@link ParasitizedCreeper} の自爆は
 * {@link ParasitizedCreeperDetonation} が地形破壊なしの胞子爆発に差し替えており、
 * ゴーレムが近寄って自爆されても村が吹き飛ぶことはないため(むしろ「ゴーレムに寄って自爆する」
 * のがユーザー要望)。
 *
 * <p>優先度と {@code mustSee}/{@code mustReach}/{@code randomInterval} は、それぞれのゴーレムが
 * バニラで {@link Enemy} 用に使っている値をそのまま踏襲している(アイアンゴーレムは壁越しでも
 * 気付く {@code mustSee=false}、スノーゴーレムは見えている相手だけ)。既存の Goal は残したまま
 * 同じ優先度に積み増す方式 — {@link ParasitizedHostilityInjector} と同じ考え方。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class GolemHostilityInjector {

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof IronGolem golem) {
            golem.targetSelector.addGoal(3, overgrownTargetGoal(golem, 5, false));
        } else if (event.getEntity() instanceof SnowGolem golem) {
            golem.targetSelector.addGoal(1, overgrownTargetGoal(golem, 10, true));
        }
    }

    private static NearestAttackableTargetGoal<Mob> overgrownTargetGoal(Mob golem, int randomInterval, boolean mustSee) {
        return new NearestAttackableTargetGoal<>(golem, Mob.class, randomInterval, mustSee, false,
                candidate -> MobGroup.of(candidate) != null);
    }

    private GolemHostilityInjector() {
    }
}
