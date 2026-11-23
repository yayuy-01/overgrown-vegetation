package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.NurseryParasitized;
import com.yayuy.overgrownvegetation.entity.RootGuardian;
import com.yayuy.overgrownvegetation.entity.RootedParasitized;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

/**
 * 司令塔の「学ぶ」の記録(2026-11-14、第 3 段 区切り 1)。プレイヤー(とその飛び道具・飼い犬など持ち主のいる生き物)に倒された G1 を
 * {@link KillMemory} へ。フェーズは見ない(F1 から裏で)。拠点の守りの見積もり({@link BaseDefense})が読む。
 * <p>2026-11-23 片付け E: 危ない所へ進化体を送る作り(dispatch・send)は消した(11-21 から前線の狩りが受け持つ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class DangerWatch {

    private DangerWatch() {
    }

    /** 数える G1 か(段階 0・1 段目・根付き・苗床・根の守り手)。 */
    public static boolean isG1(Entity e) {
        return e instanceof AbstractParasitizedMonster || e instanceof RootedParasitized || e instanceof NurseryParasitized
                || e instanceof RootGuardian;
    }

    /** プレイヤーが倒したことになるか(プレイヤー本人・その飛び道具・持ち主がプレイヤーの生き物)。 */
    public static boolean byPlayer(@javax.annotation.Nullable Entity killer) {
        if (killer instanceof Player p) return !p.isSpectator();
        return killer instanceof OwnableEntity own && own.getOwner() instanceof Player;
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!(e.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || !isG1(e)) return;
        if (!DesignData.director().enabled || !byPlayer(event.getSource().getEntity())) return;
        if (!ForestVision.sees(level, e.blockPosition())) return; // 11-20 戦場の霧: 視界の外で倒された Mob は森は知らない
        KillMemory.get(level.getServer()).record(ChunkPos.asLong(e.getBlockX() >> 4, e.getBlockZ() >> 4), BaseMemory.today(level.getServer()));
    }
}
