package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
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
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ParasitizedAttackInfectionHandler {

    private static final List<TagKey<EntityType<?>>> GROUPS = List.of(ModTags.GROUP_1, ModTags.GROUP_2, ModTags.GROUP_3,
            ModTags.GROUP_4, ModTags.GROUP_5, ModTags.GROUP_6, ModTags.GROUP_7, ModTags.GROUP_8, ModTags.GROUP_9, ModTags.GROUP_10);

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof Player victim) || !(victim.level() instanceof ServerLevel) || !victim.isAlive()) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof LivingEntity attacker) || attacker == victim) {
            return;
        }
        if (isOvergrownMob(attacker)) {
            SporeInfectionEffects.plantAttack(victim);
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
