package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.spore.MobInfection;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

import java.util.List;

/**
 * 過繁茂した Mob(グループ 1〜10 のタグ)に攻撃されたプレイヤーの胞子感染(10-14 ユーザー指定:
 * 未感染は 30% でかかり、感染中は 5% で進みが 10 秒進む。{@link SporeInfectionEffects#plantAttack})。
 *
 * <p>2026-11-04 に parked から戻して<b>口だけ</b>にした: 今のコードで攻撃する過繁茂の Mob はいない(汚染シダは攻撃しない。
 * 寄生 Mob・G1・寄生植物装備は退避中)。Mob を戻したら、グループのタグに入っていればそのまま効く。Mob への感染の道は消した
 * (Mob は暴露量で 20 ダメージ、11-04)。
 *
 * <p>2026-11-15(ユーザー決定): プレイヤー以外の Mob も、G1・G4〜G10・汎用の感染体({@link PlantSideRules#isInfector})に傷を付けられたら
 * 必ず {@link MobInfection#infect} で感染する(暴露量は減らず上がり続け、約 2 分で死ぬ)。ボス・ゴーレム・ほかの MOD の Mob も除かない
 * (ただし {@code spore_exposure_immune} のタグと植物側は感染の仕組みそのものが測らない)。G1 の 1 撃ごとの +300 の暴露は、これに置き換えた。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ParasitizedAttackInfectionHandler {

    private static final List<TagKey<EntityType<?>>> GROUPS = List.of(ModTags.GROUP_1, ModTags.GROUP_2, ModTags.GROUP_3,
            ModTags.GROUP_4, ModTags.GROUP_5, ModTags.GROUP_6, ModTags.GROUP_7, ModTags.GROUP_8, ModTags.GROUP_9, ModTags.GROUP_10);

    /**
     * 攻撃の元は {@code getSource().getEntity()}(飛び道具なら撃った Mob、爆発なら爆ぜた Mob)で見る。直に当たった物(矢・火の玉)は見ない。
     * 傷が付いた(防具・吸収を引いたあとのダメージが 0 より大きい)ときだけ。盾で防いだ撃ちはこの知らせが来ない。
     */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level) || !victim.isAlive()) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof LivingEntity attacker) || attacker == victim) {
            return;
        }
        if (victim instanceof Player player) {
            // プレイヤーは今のまま(10-14: 未感染は 30%・感染中は 5%)。汎用の感染体もここに入れる(11-15)
            if (isOvergrownMob(attacker) || PlantSideRules.isTransformed(attacker)) {
                SporeInfectionEffects.plantAttack(player);
            }
            return;
        }
        // プレイヤー以外の Mob: G1・G4〜G10・汎用の感染体に傷を付けられたら必ず感染(11-15 ユーザー決定。除く相手は無い。G2・G3 は感染させない)
        if (event.getNewDamage() > 0.0F && PlantSideRules.isInfector(attacker)) {
            MobInfection.infect(victim, level.getGameTime());
        }
    }

    /** 過繁茂した Mob(グループ 1〜10 のどれかのタグ)か。 */
    public static boolean isOvergrownMob(LivingEntity e) {
        for (TagKey<EntityType<?>> g : GROUPS) {
            if (e.getType().is(g)) return true;
        }
        return false;
    }

    private ParasitizedAttackInfectionHandler() {
    }
}
