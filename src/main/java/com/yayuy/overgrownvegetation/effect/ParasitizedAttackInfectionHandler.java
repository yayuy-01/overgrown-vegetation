package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.spore.MobInfection;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.living.LivingDamageEvent;


/**
 * 植物側の攻撃で感染する入口(攻撃の元が {@link PlantSideRules#isInfector} = G1・G2〜G8・汎用の感染体で、傷が 0 より大きいとき)。
 * <ul>
 *   <li>プレイヤー: 未感染は 30% でかかる(10-14 ユーザー指定。{@link SporeInfectionEffects#plantAttack})+ 傷の暴露(11-15。{@link #addWoundDose})。
 *   感染中は傷の暴露が段の進みへ積まれる(11-15「混ぜた形」。前の「感染中は 5% で 10 秒進む」は外した)。</li>
 * </ul>
 *
 * <p>2026-11-15(ユーザー決定): プレイヤー以外の Mob も、G1・G2〜G8・汎用の感染体({@link PlantSideRules#isInfector})に傷を付けられたら
 * 必ず {@link MobInfection#infect} で感染する(暴露量は減らず上がり続け、約 2 分で死ぬ)。ボス・ゴーレム・ほかの MOD の Mob も除かない
 * (ただし {@code spore_exposure_immune} のタグと植物側は感染の仕組みそのものが測らない)。G1 の 1 撃ごとの +300 の暴露は、これに置き換えた。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class ParasitizedAttackInfectionHandler {

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
        handle(level, victim, attacker, event.getNewDamage());
    }

    /** テスト用: 傷 {@code newDamage} を付けられたことにして、入口の中身を通す。 */
    public static void onLivingDamageForTests(LivingEntity victim, LivingEntity attacker, float newDamage) {
        if (victim.level() instanceof ServerLevel level) handle(level, victim, attacker, newDamage);
    }

    private static void handle(ServerLevel level, LivingEntity victim, LivingEntity attacker, float newDamage) {
        if (victim instanceof Player player) {
            // 植物側(G1・G2〜G8・汎用の感染体)に傷を付けられたときだけ(2026-11-16 コードの見直し ③4: 前は環境生物も入り、傷 0 でも抽選していた)
            if (newDamage > 0.0F && PlantSideRules.isInfector(attacker)) {
                SporeInfectionEffects.plantAttack(player); // 未感染は 30%(10-14)
                addWoundDose(player, newDamage); // 傷の暴露(11-15 ユーザー決定。マスクでは防げない)
            }
            return;
        }
        // プレイヤー以外の Mob: G1・G2〜G8・汎用の感染体に傷を付けられたら必ず感染(11-15 ユーザー決定。除く相手は無い。環境生物は感染させない)
        // + 1 撃ごとにダメージに比例した暴露量の上乗せ(11-15 ユーザー決定。約 2 分より早く死ぬ)
        if (newDamage > 0.0F && PlantSideRules.isInfector(attacker)) {
            MobInfection.infect(victim, level.getGameTime());
            addWoundDose(victim, newDamage);
        }
    }

    /**
     * 傷の暴露: {@code damage} × 1 ダメージあたりの量(spore_field.json の mobInfection.woundDosePlayerPerDamage・woundDoseMobPerDamage)を
     * 暴露量に足す(感染の量で頭打ち。満ちたら次の暴露量の判定で、プレイヤーは感染 1・Mob は 20 ダメージ)。防護マスクは見ない(傷口から入る)。
     * 感染中のプレイヤーは段の進みへ足す(11-15「混ぜた形」)。創造・観戦のプレイヤー・測らない Mob には足さない。
     */
    public static void addWoundDose(LivingEntity victim, float damage) {
        com.yayuy.overgrownvegetation.data.MobInfectionSpec s = com.yayuy.overgrownvegetation.data.DesignData.sporeField().mobInfection();
        double amount = (victim instanceof Player ? s.woundDosePlayerPerDamage() : s.woundDoseMobPerDamage()) * damage;
        // 傷口から入る = マスクでは防げない(2026-11-16 ③6 で共通の口 SporeExposureTracker.addExposure へ)
        com.yayuy.overgrownvegetation.spore.SporeExposureTracker.addExposure(victim, amount, false);
    }

    private ParasitizedAttackInfectionHandler() {
    }
}
