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
 *   <li>環境生物が倒されたら、その升の頭数を 1 減らす({@link EcoLedger#kill})。どこで倒しても多様性が下がる(10-14 ユーザー)。</li>
 *   <li>数えるのはプレイヤー(本人・飛び道具・TNT など持ち主がプレイヤーの間接攻撃)と、{@code countedDamageTypes} の防衛設備だけ。
 *   飼い犬・ゴーレム・狩る側の環境生物の捕食・落下では減らない(10-14 ユーザー指定を保つ)。オーバーワールドだけ。</li>
 *   <li>環境生物かは、タグ {@code critter_prey}・{@code critter_hunter} か、仮の種の印 {@link #STUB_TAG_PREY}・{@link #STUB_TAG_HUNTER}
 *   (環境生物の種がそろうまでの口。全体管理 11-16 決定 D)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class Ecosystem {

    public static final String STUB_TAG_PREY = "ov_eco_prey";
    public static final String STUB_TAG_HUNTER = "ov_eco_hunter";

    /**
     * 環境生物の 2 つの側(2026-11-21 に旧 G2・G3 の番号から付け替え)。台帳・洞・仮の種の配列の添え字にも使う。
     * {@link #PREY} = 獲物の側(タグ critter_prey。生息しているだけの虫・カタツムリなど)、
     * {@link #HUNTER} = 狩る側(タグ critter_hunter。虫を狩る爬虫類・サソリなど)。
     */
    public static final int NONE = -1, PREY = 0, HUNTER = 1;

    private Ecosystem() {
    }

    /** 環境生物のどちらの側か({@link #PREY} か {@link #HUNTER})。どちらでもなければ {@link #NONE}。 */
    public static int critterKind(Entity e) {
        MobGroup g = MobGroup.of(e);
        if (g == MobGroup.CRITTER_PREY) return PREY;
        if (g == MobGroup.CRITTER_HUNTER) return HUNTER;
        if (e.getTags().contains(STUB_TAG_PREY)) return PREY;
        if (e.getTags().contains(STUB_TAG_HUNTER)) return HUNTER;
        return NONE;
    }

    /** 数える殺し手か(プレイヤーか、数える防衛設備のダメージ)。 */
    public static boolean counted(DamageSource source) {
        Entity killer = source.getEntity();
        if (killer instanceof Player p) return !p.isSpectator();
        // 防衛設備のダメージは、弾や設備の Entity が付いていても数える(殺し手の判定より先に見る)
        String type = source.typeHolder().unwrapKey().map(k -> k.location().toString()).orElse("");
        return DesignData.ecosystem().countedDamageTypes.contains(type); // 飼い犬・ゴーレム・Mob の捕食・落下は数えない
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!(e.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!DesignData.ecosystem().enabled) return;
        int g = critterKind(e);
        if (g == NONE || !counted(event.getSource())) return;
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
