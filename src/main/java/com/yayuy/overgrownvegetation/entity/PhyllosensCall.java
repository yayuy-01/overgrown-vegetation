package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.director.PlantSideCall;
import com.yayuy.overgrownvegetation.director.Watchers;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.EnumSet;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 監視塔フィロセンスと司令塔・味方のつなぎ(2026-11-14)。司令塔の側の口は {@link Watchers}・{@link PlantSideCall}(G1 の作成のセッションが 11-14 に用意)。
 *
 * <ul>
 *   <li>{@link #seen}: 視線の印を付けた →「この相手をここで見た」を {@link Watchers#markSeen} へ渡す。そばの覚えた拠点の認識の確定と、
 *   半径 64【仮・director.json の watcher.callRadius】の植物側の Mob の呼び寄せ({@link PlantSideCall#towards})は司令塔の側で起きる。
 *   G1 は司令塔が歩かせる。G4 以降はここで登録する口 {@link #call} が受ける。</li>
 *   <li>{@link #cut}: 倒された → {@link Watchers#onCut}(司令塔が植えた塔なら、認識が遅れる・1 段戻る)。</li>
 * </ul>
 * <b>植える口({@code Watchers.registerPlanter})は登録しない</b>: 登録すると司令塔が植え始め(予算を払う)、認識の確定に監視塔の印が要るようになる。
 * 植えるのは司令塔の持ち場で、育つ途中の姿もまだ無い(このセッションの範囲外)。
 *
 * <h2>G4・G5 の向かわせ方【仮・Claude 11-14】</h2>
 * 今ゲームにいる G4・G5 は アルボラ(G4)・ファルキムネス・ヴェナムネス(G5)。
 * <ul>
 *   <li>アルボラ・ファルキムネス: 標的があれば距離を問わず追う作り(各種の HuntGoal)なので、標的の無い個体に印の相手を標的として渡す。</li>
 *   <li>それ以外(ヴェナムネスはまだ攻撃が無い段 1): 入ってきたときに {@link GuideGoal} を差し、呼ばれている間 印の相手の居場所へ歩かせる
 *   ({@link #ENGAGE} まで寄ったら標的として渡して止まる)。</li>
 * </ul>
 * 退避中の {@code GuidedHuntGoal}・{@code PhyllosensGuidanceEffect}({@code overgrown/parked/})は、退避中の影響の表・config・群れの部品に
 * 絡むので戻していない(合流してから向かう作りは、群れ v2 の司令役に任せる方向)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID)
public final class PhyllosensCall {

    private PhyllosensCall() {
    }

    static final TagKey<EntityType<?>> G4 = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "group_4"));
    static final TagKey<EntityType<?>> G5 = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "group_5"));
    /** ここまで寄ったら標的として渡す(ブロック)【仮】。 */
    static final double ENGAGE = 12.0D;

    /** 呼ばれている個体 → (相手, いつまで)。保存しない(呼びかけは 1 分)。 */
    private static final Map<Mob, Object[]> GUIDED = new WeakHashMap<>();

    /** MOD の構築子から({@code ModPhyllosens.init})。 */
    public static void register() {
        PlantSideCall.registerListener(PhyllosensCall::call);
    }

    static void seen(PhyllosensEntity tower, LivingEntity target) {
        if (tower.level() instanceof ServerLevel level) Watchers.markSeen(level, tower.blockPosition(), target);
    }

    static void cut(PhyllosensEntity tower, ServerLevel level) {
        Watchers.onCut(level, tower.blockPosition());
    }

    static boolean calledKind(Mob m) {
        return m.getType().is(G4) || m.getType().is(G5);
    }

    /** {@link PlantSideCall.Listener}: 半径の中の G4・G5 を相手へ向かわせる。 */
    static int call(ServerLevel level, Vec3 at, LivingEntity target, double radius, int ticks) {
        int n = 0;
        long until = level.getGameTime() + ticks;
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(at, at).inflate(radius, 64.0D, radius), PhyllosensCall::calledKind)) {
            if (!m.isAlive() || m.distanceToSqr(at) > radius * radius || m instanceof PhyllosensEntity) continue;
            LivingEntity cur = m.getTarget();
            if (cur != null && cur.isAlive()) continue;   // 戦っている最中は取り上げない
            if (m instanceof ArboraEntity || m instanceof FalcimnesEntity) {
                m.setTarget(target);
            } else {
                GUIDED.put(m, new Object[]{target, until});
            }
            n++;
        }
        return n;
    }

    /** 試験用: 呼ばれているか。 */
    public static boolean guided(Mob m) {
        return GUIDED.containsKey(m);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getEntity() instanceof PathfinderMob m && calledKind(m) && !(m instanceof ArboraEntity) && !(m instanceof FalcimnesEntity)
                && !(m instanceof PhyllosensEntity)) {
            m.goalSelector.addGoal(3, new GuideGoal(m));
        }
    }

    /** 呼ばれている間、印の相手の居場所へ歩く(標的の無い間だけ)。 */
    static final class GuideGoal extends Goal {
        private final PathfinderMob mob;
        private int repath;

        GuideGoal(PathfinderMob mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        private LivingEntity quarry() {
            Object[] g = GUIDED.get(this.mob);
            if (g == null) return null;
            LivingEntity t = (LivingEntity) g[0];
            if (!t.isAlive() || this.mob.level().getGameTime() > (long) g[1] || (t instanceof Player p && (p.isCreative() || p.isSpectator()))) {
                GUIDED.remove(this.mob);
                return null;
            }
            return t;
        }

        @Override
        public boolean canUse() {
            LivingEntity cur = this.mob.getTarget();
            if (cur != null && cur.isAlive()) return false;
            LivingEntity t = quarry();
            return t != null && this.mob.distanceToSqr(t) > 4.0D * 4.0D;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void start() {
            this.repath = 0;
        }

        @Override
        public void stop() {
            this.mob.getNavigation().stop();
        }

        @Override
        public void tick() {
            LivingEntity t = quarry();
            if (t == null) return;
            if (this.mob.distanceToSqr(t) <= ENGAGE * ENGAGE) {
                this.mob.setTarget(t);
                GUIDED.remove(this.mob);
                return;
            }
            if (--this.repath > 0) return;
            this.repath = 20;
            Vec3 to = t.position();
            Vec3 d = to.subtract(this.mob.position());
            if (d.length() > 24.0D) to = this.mob.position().add(d.normalize().scale(24.0D));   // 遠いときはその方向の 24 先
            this.mob.getNavigation().moveTo(to.x, to.y, to.z, 1.0D);
        }
    }
}
