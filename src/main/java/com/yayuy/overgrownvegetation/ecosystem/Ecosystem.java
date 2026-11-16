package com.yayuy.overgrownvegetation.ecosystem;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * 生態系の台帳の口(2026-11-16)。
 * <ul>
 *   <li>G2・G3 が倒されたら、その升の頭数を 1 減らす({@link EcoLedger#kill})。どこで倒しても多様性が下がる(10-14 ユーザー)。</li>
 *   <li>数えるのはプレイヤー(本人・飛び道具・TNT など持ち主がプレイヤーの間接攻撃)と、{@code countedDamageTypes} の防衛設備だけ。
 *   飼い犬・ゴーレム・G3 の捕食・落下では減らない(10-14 ユーザー指定を保つ)。オーバーワールドだけ。</li>
 *   <li>G2・G3 かは、タグ {@code group_2}・{@code group_3} か、仮の種の印 {@link #STUB_TAG_G2}・{@link #STUB_TAG_G3}
 *   (G2・G3 の種がそろうまでの口。全体管理 11-16 決定 D)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class Ecosystem {

    public static final String STUB_TAG_G2 = "ov_eco_g2";
    public static final String STUB_TAG_G3 = "ov_eco_g3";

    private Ecosystem() {
    }

    /** G2・G3 のどちらか(2 か 3)。どちらでもなければ 0。 */
    public static int residentGroup(Entity e) {
        MobGroup g = MobGroup.of(e);
        if (g == MobGroup.GROUP_2) return 2;
        if (g == MobGroup.GROUP_3) return 3;
        if (e.getTags().contains(STUB_TAG_G2)) return 2;
        if (e.getTags().contains(STUB_TAG_G3)) return 3;
        return 0;
    }

    /** 数える殺し手か(プレイヤーか、数える防衛設備のダメージ)。 */
    public static boolean counted(DamageSource source) {
        Entity killer = source.getEntity();
        if (killer instanceof Player p) return !p.isSpectator();
        if (killer != null) return false; // 飼い犬・ゴーレム・Mob の捕食
        for (String id : DesignData.ecosystem().countedDamageTypes) {
            if (source.typeHolder().unwrapKey().map(k -> k.location().toString().equals(id)).orElse(false)) return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!(e.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!DesignData.ecosystem().enabled) return;
        int g = residentGroup(e);
        if (g == 0 || !counted(event.getSource())) return;
        EcoLedger.get(level).kill(g, e.getBlockX() >> 4, e.getBlockZ() >> 4, EcoLedger.now(level));
    }

    // ---------------------------------------------------------------- 外から読む口

    /** 今の多様性(森の健康、0〜1)。オーバーワールドの台帳。切ってあれば 1。 */
    public static double health(ServerLevel level) {
        if (!DesignData.ecosystem().enabled) return 1.0D;
        return EcoLedger.get(level).health(EcoLedger.now(level));
    }

    /** 森の予算の貯まる速さの倍率(決定 C)。健康 100% で 1(今の釣り合いのまま)。 */
    public static double incomeFactor(ServerLevel level) {
        if (!DesignData.ecosystem().enabled) return 1.0D;
        return EcoLedger.incomeFactor(health(level), DesignData.ecosystem().incomeFloor);
    }
}
