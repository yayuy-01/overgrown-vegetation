package com.yayuy.overgrownvegetation.perf;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.crawler.CrawlerPack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.EnumSet;

/**
 * <b>追跡の寄り</b>(2026-11-18 大群の軽量化 A。ユーザー「遠くから追いかけるときだけ地図を使う形で行こうか」)。
 *
 * <p>標的のプレイヤーから {@code flowField.chaseNearBlocks}(20)より遠い間だけ、そのプレイヤーへの流れの地図({@link FlowFields#requestFor}。
 * 1 人につき 1 枚)を下って寄る。近づいたら止まり、種ごとの狩り(回り込み・待ち伏せ・跳びかかりなど)に任せる = 戦い方の個性は残す。
 * 地図が無い・地図の外・詰まった間は動かない(種ごとの狩りが今までどおり経路を引く)。地図が使えなかったら 40 tick は試さない。
 *
 * <p>種のクラスには手を入れず、入るときに外から足す(優先度 0: 種の狩りの Goal(1〜3)より先に動きを取る。取っている間は種の狩りの
 * 「始められるか」も呼ばれないので、その経路探索も走らない)。使う種は {@code flowField.chaseSpecies}(まず 2 種で試す)。
 * 群れの後続(先導役でない)は群れの付いていき方に任せる。toml {@code flowFieldEnabled} が切なら何もしない。
 */
public final class FlowChaseGoal extends Goal {

    /** 確かめ用: 寄りを始めた回数・地図が使えず退いた回数。 */
    public static long starts, gaveUp;

    private final PathfinderMob mob;
    private final FlowFields.Steer steer = new FlowFields.Steer();
    private long blockedUntil;
    private int timer;

    public FlowChaseGoal(PathfinderMob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    private double speed() {
        return mob instanceof com.yayuy.overgrownvegetation.entity.crawler.SmallCrawler sc ? sc.rushSpeed() : 1.0D;
    }

    /** 遠くの標的のプレイヤー(なければ null)。 */
    private Player farTarget() {
        LivingEntity t = mob.getTarget();
        if (!(t instanceof Player p) || !p.isAlive() || p.isSpectator()) return null;
        double near = DesignData.perf().flowChaseNearBlocks;
        return mob.distanceToSqr(p) > near * near ? p : null;
    }

    @Override
    public boolean canUse() {
        if (!FlowFields.enabled() || mob.level().getGameTime() < blockedUntil) return false;
        Player p = farTarget();
        if (p == null) return false;
        CrawlerPack pack = CrawlerPack.externalOf(mob);
        if (pack != null && pack.size() > 0 && pack.leader() != mob) return false; // 群れの後続は群れに付いていく
        if (!steer.steerTo(mob, p, speed())) {
            blockedUntil = mob.level().getGameTime() + 40L;
            return false;
        }
        starts++;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return FlowFields.enabled() && farTarget() != null && steer.active();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        timer = 0;
    }

    @Override
    public void stop() {
        steer.reset();
    }

    @Override
    public void tick() {
        if (++timer % 4 != 0) {
            steer.reapply(mob); // 舵は渡された tick しか歩かせない
            return;
        }
        Player p = farTarget();
        if (p == null || !steer.steerTo(mob, p, speed())) {
            blockedUntil = mob.level().getGameTime() + 40L;
            gaveUp++;
            steer.pause();
        }
    }

    /** 入るときに、使う種へ足す。 */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
    public static final class Attach {
        private Attach() {
        }

        @SubscribeEvent
        public static void onJoin(EntityJoinLevelEvent event) {
            if (event.getLevel().isClientSide() || !(event.getEntity() instanceof PathfinderMob mob)) return;
            if (!DesignData.perf().flowChaseSpecies.contains(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()))) return;
            mob.goalSelector.addGoal(0, new FlowChaseGoal(mob)); // ヴェナムネスの狩りは 1。同じ優先度だと取れないので 0(20 ブロックより近い・地図が使えない間は譲る)
        }
    }
}
