package com.yayuy.overgrownvegetation.difficulty;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * ワールドの難易度「敵の強さ」(2026-09-25、ユーザー指定: 攻撃力を倍率で。初期値 1.0・上限 256)を、敵が生き物に与える
 * ダメージへ掛ける。
 *
 * <p><b>敵</b> = グループ 1〜10 の Mob({@link MobGroup#of} が付く種。寄生された家畜も入る)と攻城戦ボスの攻撃
 * ({@link ModTags#SIEGE_BOSS_ATTACK}。ダメージ源にエンティティが付いていないのでタグで見分ける)。矢・胞子爆弾・爆発のような
 * 間接攻撃も {@code getEntity()} が撃った本人を返すので入る。寄生植物装備を着たプレイヤーは {@link MobGroup#of} がグループ 6 を
 * 返すが、{@link Mob} ではないので入らない。
 *
 * <p>受け手は選ばない(プレイヤー・村人・ゴーレム・ペットすべて)。TD で壁やターゲットブロックを削る量は生き物へのダメージではないので、
 * {@link WorldDifficultyData#scaleEnemyStrike} が別に掛ける。
 *
 * <p>優先度は HIGH: 樹液結晶の塗布({@code OvergrowthWardDamage})などの軽減より先に「攻撃力」を上げておく
 * (掛け算どうしなので順番で結果は変わらないが、「1 ダメージぶんは通す」のような下限の処理があっても素直に効くように)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class EnemyStrengthDamage {

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level) || !isEnemyAttack(event.getSource())) {
            return;
        }
        double multiplier = WorldDifficultyData.enemyStrength(level.getServer());
        if (multiplier == 1.0D) {
            return;
        }
        event.setAmount((float) (event.getAmount() * multiplier));
    }

    /** この MOD の敵の攻撃か。 */
    public static boolean isEnemyAttack(DamageSource source) {
        if (source.is(ModTags.SIEGE_BOSS_ATTACK)) {
            return true;
        }
        return source.getEntity() instanceof Mob attacker && MobGroup.of(attacker) != null;
    }

    private EnemyStrengthDamage() {
    }
}
