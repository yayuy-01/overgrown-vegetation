package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.AbstractOvergrownAquaticPredatorEntity;
import com.yayuy.overgrownvegetation.entity.Predation;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * <b>汚染側(グループ1〜10)は同士討ちをしない</b> — グループに属する生き物どうしのダメージと、
 * 攻城戦ボスの攻撃がグループ1〜10に当たったぶんを、まとめて<b>無効化</b>する(ユーザー要望 2026-09-13)。
 *
 * <h2>なぜ索敵側だけでは足りないのか</h2>
 * <p>「狙わない」は各 Goal や {@code SiegeData.pickVictim} で既に守っているが、それでは
 * <b>巻き添え</b>が止まらない。ボスのビームは線の近くの生物すべてに入るし、胞子爆弾の爆発は
 * 範囲内の全員に入る。狙っていなくても、味方が焼ける。
 * ここで<b>ダメージそのものを落とす</b>ことで「何があっても同士討ちをしない」を保証する。
 *
 * <h2>捕食は同士討ちではない</h2>
 * <p>グループ3の捕食者がグループ2を狩る({@code predation.json})のも、水生の捕食者が水中の
 * グループ1〜3を襲うのも、<b>意図して作った食物連鎖</b>なので通す。
 * <b>グループ4(拡大種)がグループ1〜3 を狩る</b>のも同じ扱い(2026-09-15 に追加)。ここで止めているのは
 * 「その関係に無い相手を傷つけてしまう」ぶんだけ。
 *
 * <h2>プレイヤーは対象外</h2>
 * <p>プレイヤーはどのグループにも属さないので、掛かるのは汚染側どうしのときだけ。
 * プレイヤーがグループ1〜10を殴るのも、グループ1〜10がプレイヤーを襲うのも、今までどおり。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class MobGroupFriendlyFire {

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide() || MobGroup.of(victim) == null) {
            // 汚染側の生き物でなければ何もしない(プレイヤー・バニラ Mob はここを素通り)。
            return;
        }

        // 攻城戦ボスの攻撃(ビーム・小ビームの乱射・胞子爆弾の爆発)。ダメージ源にエンティティが
        // 付いていないので下の「攻撃者のグループ」判定では捕まらず、タグで見分ける。
        if (event.getSource().is(ModTags.SIEGE_BOSS_ATTACK)) {
            event.setCanceled(true);
            return;
        }

        if (!(event.getSource().getEntity() instanceof LivingEntity attacker) || attacker == victim) {
            return;
        }
        if (MobGroup.of(attacker) == null) {
            return;
        }
        // 「寄生植物装備」を着けたプレイヤー(グループ 6 扱い)がグループ1〜10 を殴るのは通す(ユーザー指定 2026-09-18)。
        // 殴られた側が反撃しないのは PlantArmorEvents#onChangeTarget。
        if (com.yayuy.overgrownvegetation.item.PlantArmor.isHost(attacker)) {
            return;
        }
        if (isIntendedPredation(attacker, victim)) {
            return;
        }
        event.setCanceled(true);
    }

    /** 意図して作ってある食物連鎖(= 同士討ちではない)か。 */
    private static boolean isIntendedPredation(LivingEntity attacker, LivingEntity victim) {
        if (Predation.isPredatorOf(attacker, victim)) {
            return true;
        }
        // グループ4(拡大種)はグループ1〜3 を獲物にする(ユーザー要望 2026-09-15)。
        // グループ3 がグループ2 を狩るのと同じ「意図して作った食物連鎖」なので通す。
        if (Predation.isInvaderPrey(attacker, victim)) {
            return true;
        }
        // 水生の捕食者は「水中・水面に居るグループ1〜3」を襲う(2026-09-08 / 09 の決定)。
        return attacker instanceof AbstractOvergrownAquaticPredatorEntity
                && Predation.isWaterborneTarget(attacker, victim);
    }

    private MobGroupFriendlyFire() {
    }
}
