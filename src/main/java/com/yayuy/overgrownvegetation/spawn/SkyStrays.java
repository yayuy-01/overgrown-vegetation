package com.yayuy.overgrownvegetation.spawn;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import com.yayuy.overgrownvegetation.port.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 印の無い空の種の片付け(2026-11-17 深夜 ユーザー決定 (a)「遠くで片付ける」)。
 * 空の種({@link SkyFlyer} のうち spawns.json の表で habitats に sky を持つ種)は自分では消えない作り(縁・地下の湧きの片付けは 11-23 片付け E で消えた)。
 * F7 の前線の写しで、印の無いヴェロフィル・セミナリアが 27 体ずつたまった回があった(出どころ不明)。
 * <ul>
 *   <li>{@link #INTERVAL} tick おきに、オーバーワールドの印の無い空の種のうち、どのプレイヤー(観戦を除く)からも {@code despawnBlocks} より
 *   遠いものを消す。名札・紐・乗り物の個体は残す。</li>
 *   <li>出どころ探し: 印の無い空の種が保存からでなく新しく世界に入ったら、数を数え、開発用のクライアント({@code -Dov.devbridge})では
 *   呼び出し元をログに出す(30 秒に 1 回まで)。スポーンエッグ・命令で出したものも数える。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class SkyStrays {

    private SkyStrays() {
    }

    /** 見る間(tick)【仮】。 */
    static final int INTERVAL = 200;
    private static final boolean DEV = Boolean.getBoolean("ov.devbridge");

    /** 確かめ用: 片付けた数・印の無いまま新しく入った数。 */
    public static long cleared, untaggedFresh;
    private static long lastTraceAt = Long.MIN_VALUE;

    /** 表で空に住む種(表が替わったら作り直す)。 */
    private static volatile SpawnTable typesFrom;
    private static volatile Set<EntityType<?>> skyTypes = Set.of();

    static Set<EntityType<?>> skyTypes() {
        SpawnTable t = DesignData.spawns();
        if (typesFrom != t) {
            Set<EntityType<?>> s = new HashSet<>();
            for (SpawnTable.Species sp : t.species) {
                if (sp.habitats().contains(SpawnTable.Habitat.SKY) && sp.type() != null) s.add(sp.type());
            }
            skyTypes = Set.copyOf(s);
            typesFrom = t;
        }
        return skyTypes;
    }

    /** 片付けの対象か(空の種・印なし・名札や紐なし)。 */
    static boolean stray(Entity e) {
        return e instanceof SkyFlyer m && skyTypes().contains(m.getType())
                && !m.hasCustomName() && !m.isLeashed() && !m.isPassenger() && !m.isVehicle();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (level.getGameTime() % INTERVAL != 0) return;
        if (level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) return;
        sweep(level);
    }

    /** 遠い印なしの空の種を消す。消した数を返す。 */
    public static int sweep(ServerLevel level) {
        double far = DesignData.spawns().despawnBlocks;
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer p : level.players()) if (!p.isSpectator()) players.add(p);
        if (players.isEmpty()) return 0; // 誰もいないときは片付けない(前は全部「遠い」になり、印の無い空の種を全部消していた。11-18 見直し ⑦4)
        List<Mob> gone = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (!stray(e)) continue;
            boolean near = false;
            for (ServerPlayer p : players) {
                if (p.distanceToSqr(e) < far * far) {
                    near = true;
                    break;
                }
            }
            if (!near) gone.add((Mob) e);
        }
        for (Mob m : gone) m.discard();
        cleared += gone.size();
        return gone.size();
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.loadedFromDisk() || !stray(event.getEntity())) return;
        untaggedFresh++;
        if (!DEV) return;
        long now = event.getLevel().getGameTime();
        if (now - lastTraceAt < 600) return;
        lastTraceAt = now;
        OvergrownVegetation.LOGGER.info("SKY_STRAY 印の無い空の種が新しく入った: " + event.getEntity().getType() + " at " + event.getEntity().blockPosition(),
                new Throwable("出どころ"));
    }
}
