package com.yayuy.overgrownvegetation.spawn;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.EnumSet;

/**
 * G4・G5 を拠点へ歩かせる使い(2026-11-15、司令塔の {@code BaseRaids.registerMarcher} の中身)。G1 の {@code G1March} と同じ形の、もっと簡単なもの:
 * 拠点の真ん中へ歩き、{@code marchArriveBlocks} まで来たら終わり(そこからはその種のいつもの狙い方)。{@code marchTimeoutTicks} 歩いても着かなければ終わり。
 * 途中で相手がいれば、そちらが先(相手がいる間は歩かない)。
 *
 * <p>13 種のクラスには手を入れない: 入るときに外から Goal を 1 つ足す({@link #onJoin})。歩かせるのは表の「司令塔の口に入れる地上の種」だけ
 * (空の種は種ごとの移動の作りとぶつかるので、まだ入れない。11-15 全体管理)。行き先は個体の保存データに持つ(保存から戻っても続く)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PlantMarch {

    private static final String KEY = "ov_plant_march";

    private PlantMarch() {
    }

    /** 拠点へ歩かせ始める。歩かせられない種なら false。 */
    public static boolean begin(Mob mob, BaseMemory.Base base) {
        return begin(mob, base.id(), base.centerX(), base.centerZ());
    }

    /** 行き先を直に渡して歩かせ始める(確かめの命令 {@code /ov perf spawns march} も使う)。 */
    public static boolean begin(Mob mob, int baseId, double x, double z) {
        if (!(mob instanceof PathfinderMob) || !DirectorHooks.marchable(mob.getType())) return false;
        CompoundTag t = new CompoundTag();
        t.putInt("Base", baseId);
        t.putDouble("X", x);
        t.putDouble("Z", z);
        t.putLong("Since", mob.level().getGameTime());
        mob.getPersistentData().put(KEY, t);
        EnemySpawns.release(mob); // 縁の上限・片付けから外す
        return true;
    }

    public static boolean active(Mob mob) {
        return mob.getPersistentData().contains(KEY);
    }

    public static int baseId(Mob mob) {
        return active(mob) ? mob.getPersistentData().getCompound(KEY).getInt("Base") : -1;
    }

    /** 拠点への行き先(歩いていなければ null)。 */
    @javax.annotation.Nullable
    public static net.minecraft.world.phys.Vec3 target(Mob mob) {
        if (!active(mob)) return null;
        CompoundTag t = mob.getPersistentData().getCompound(KEY);
        return new net.minecraft.world.phys.Vec3(t.getDouble("X"), mob.getY(), t.getDouble("Z"));
    }

    public static void end(Mob mob) {
        mob.getPersistentData().remove(KEY);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof PathfinderMob mob)) return;
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl && mob.getPersistentData().hasUUID(com.yayuy.overgrownvegetation.entity.crawler.CrawlerPack.EXTERNAL_KEY)) {
            com.yayuy.overgrownvegetation.entity.crawler.CrawlerPack.rejoin(sl, mob); // 外から作った群れ(11-17)を保存から組み直す
        }
        if (DirectorHooks.packFollower(mob.getType())) mob.goalSelector.addGoal(0, new com.yayuy.overgrownvegetation.entity.crawler.CrawlerPack.ExternalFollowGoal(mob)); // 群れの後続として先導役に付いていく(11-17)。11-18 A: 動いている群れでは種の狩り・行進より先(相手が遠い間は先導役の経路に乗る)
        if (DirectorHooks.breaker(mob.getType())) mob.goalSelector.addGoal(0, new BreachGoal(mob)); // 壁や扉を壊す(11-16。力のある種だけ)
        if (!DirectorHooks.marchable(mob.getType())) return;
        mob.goalSelector.addGoal(2, new MarchGoal(mob));
    }

    /** 拠点へ歩く Goal。一度に遠くへ道を引くと探しきれないので、24 ブロック先の点を順に目指す。 */
    static final class MarchGoal extends Goal {
        private final PathfinderMob mob;
        private int repath;

        MarchGoal(PathfinderMob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return active(mob) && mob.getTarget() == null;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void start() {
            repath = 0;
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
        }

        @Override
        public void tick() {
            CompoundTag t = mob.getPersistentData().getCompound(KEY);
            double tx = t.getDouble("X"), tz = t.getDouble("Z");
            double dx = tx - mob.getX(), dz = tz - mob.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            var base = DesignData.director().base;
            // 壊す種は拠点の真ん中(2 ブロック)まで来る = 壁に突き当たって壊す(11-16。閉じた部屋の中の人は見えないので、狙う相手が無くても壁まで来させる)
            double arrive = DirectorHooks.breaker(mob.getType()) ? 2.0D : base.marchArriveBlocks;
            if (dist <= arrive || mob.level().getGameTime() - t.getLong("Since") > base.marchTimeoutTicks) {
                end(mob);
                mob.getNavigation().stop();
                return;
            }
            if (--repath > 0 && !mob.getNavigation().isDone()) return;
            repath = 20;
            double step = Math.min(dist, 24.0D);
            int wx = net.minecraft.util.Mth.floor(mob.getX() + dx / dist * step), wz = net.minecraft.util.Mth.floor(mob.getZ() + dz / dist * step);
            int wy = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);
            mob.getNavigation().moveTo(wx + 0.5D, wy, wz + 0.5D, 1.0D);
        }
    }
}
