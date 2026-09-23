package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.SporophyllAura;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * <b>スポロフィルの効果を、胞子除去装置の圏内で剥がす</b>(ユーザー要望 2026-09-16:
 * 「スポロフィルのバフが胞子除去装置の範囲内に入ると消失するようにしてほしい」
 * + 続けて「デバフも消えるようにお願いします」)。
 *
 * <p>味方のバフも敵のデバフも同じ扱い —— 装置の圏内は<b>胞子の効果が届かない場所</b>になる。
 *
 * <p>剥がす仕掛けは 2 か所に分かれている。
 * <ul>
 *     <li>{@link SporophyllAura#apply} —— <b>傘の下かつ装置の圏内</b>。
 *     配るその場で剥がすので、装置の中では強化も弱体化も起きない。</li>
 *     <li><b>ここ</b> —— <b>効果を持ったまま傘を離れ、装置に入った</b>個体。
 *     傘の外はスポロフィル本体が見ていないので、こちらが拾わないと
 *     持続(バフ 1 分 / デバフ 30 秒)ぶん効いたままになる。
 *     プレイヤーが傘の下から装置へ逃げ帰る場面がまさにこれ。</li>
 * </ul>
 *
 * <h2>負荷</h2>
 * {@link EntityTickEvent.Post} は<b>世界中の全エンティティ</b>で毎 tick 走るので、
 * 足切りを軽い順に並べてある:
 * <ol>
 *     <li>{@link LivingEntity} か、サーバー側か(参照 2 つ)</li>
 *     <li><b>スポロフィルの効果が付いているか</b>({@link SporophyllAura#hasAuraEffect}) ——
 *     効果一覧の取得 2 回。ほとんどのエンティティはここで抜ける。</li>
 *     <li>{@code sporophyllPurifierCheckIntervalTicks}(既定 20)の間引き。
 *     個体ごとに位相をずらすので、同じ tick に全員が装置を探すことはない。</li>
 *     <li>装置の圏内か({@link SporeDeviceData#insidePurifier}) —— 稼働中一覧は
 *     40 tick キャッシュされていて、そこから外接正方形で絞り込む。</li>
 * </ol>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SporophyllAuraPurge {

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity entity)) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (!SporophyllAura.hasAuraEffect(entity)) {
            return;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.sporophyllPurifierClearsAura.get()) {
            return;
        }
        int interval = Math.max(1, cfg.sporophyllPurifierCheckIntervalTicks.get());
        if (interval > 1 && Math.floorMod(entity.tickCount + entity.getId(), interval) != 0) {
            return;
        }
        if (!SporeDeviceData.insidePurifier(level, entity.blockPosition())) {
            return;
        }
        // どちらが付いているか分からないので両方剥がす(付いていない効果の removeEffect は空振り)。
        SporophyllAura.purgeBuffs(entity);
        SporophyllAura.purgeDebuffs(entity);
    }

    private SporophyllAuraPurge() {
    }
}
