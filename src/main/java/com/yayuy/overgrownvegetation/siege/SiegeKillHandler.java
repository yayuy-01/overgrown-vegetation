package com.yayuy.overgrownvegetation.siege;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.BlightFernPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * 攻城戦ボスの攻撃で死んだ Mob の場所に<b>必ず</b>汚染シダ(水中なら汚染水草)を生やす。
 *
 * <p>ボスに薙ぎ払われた跡がそのまま苗床になる、という筋書き。抽選は無く、死ねば必ず 1 本生える。
 *
 * <h2>「ボスの攻撃」の見分け方</h2>
 * <p>ダメージタイプのタグ {@link ModTags#SIEGE_BOSS_ATTACK}(= {@code siege_beam} + {@code siege_bomb})で判定する。
 * 胞子爆弾の爆発にわざわざ専用のダメージタイプを与えてあるのはこのため — 既定の爆発ダメージのままだと
 * TNT で死んだ場合と区別がつかない。<b>砲台の砲弾({@code siege_shell})は入れていない</b>ので、
 * プレイヤーの誤射で死んだ Mob からは生えない。
 *
 * <h2>効かない場合</h2>
 * <ul>
 *   <li><b>プレイヤー</b>は対象外(死体も苗床も残さない)。</li>
 *   <li>もともと汚染シダ系の Mob は対象外(自分の死に場所に自分が生える意味が無い)。</li>
 *   <li>稼働中の<b>胞子除去装置の範囲内</b>では湧きが阻害される({@link BlightFernPlacement#spawn})。
 *       ここを迂回すると「装置の範囲内は汚染シダが湧かない」という約束が壊れるので、あえて通している。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SiegeKillHandler {

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled()) {
            return;
        }
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player) {
            return;
        }
        if (entity instanceof com.yayuy.overgrownvegetation.entity.BlightFernEntity) {
            return;
        }
        if (!event.getSource().is(ModTags.SIEGE_BOSS_ATTACK)) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos deathPos = entity.blockPosition();
        BlightFernPlacement.spawn(level, deathPos, MobSpawnType.MOB_SUMMONED);
    }

    private SiegeKillHandler() {
    }
}
