package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

/**
 * レベルに参加する全ての「バニラの敵対 Mob」({@link Enemy} 実装、ゾンビ・スケルトン・クリーパー・
 * クモ・エンダーマン等 — {@link ParasitizedMob} 自身を除く)に、近くの「過繁茂した(MOB名)」
 * ({@link ParasitizedMob})も攻撃対象にする {@link NearestAttackableTargetGoal} を仕込む。
 *
 * <p>これにより「過繁茂した(MOB名)」は{@link ParasitizedTargetGoal}で種族を問わず一方的に
 * 攻撃するだけでなく、逆に他の敵対 Mob 側からも狙われるようになり、双方向の敵対関係になる。
 * 既存のプレイヤー狙いの索敵 Goal(優先度1〜3が一般的)はそのまま残し、これと同じ優先度2に
 * 追加で積む方式 — バニラ自身も同じ優先度に複数のターゲット Goal を積むことがある
 * (例: バニラの {@code Zombie} は優先度3に村人用とアイアンゴーレム用を両方持つ)ので、
 * 同じ優先度に複数の {@link NearestAttackableTargetGoal} が共存すること自体は問題ない。
 *
 * <p>{@link ParasitizedMob} 自身(寄生されたゾンビ等)はここでは対象外 — 既に
 * {@link ParasitizedTargetGoal} が種族を問わず全方位を攻撃対象にしており、二重に追加する必要が無い。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ParasitizedHostilityInjector {

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof Mob mob && mob instanceof Enemy && !(mob instanceof ParasitizedMob)) {
            mob.targetSelector.addGoal(2, new ParasitizedOnlyTargetGoal(mob));
        }
    }

    /**
     * {@link ParasitizedMob} だけを狙う {@link NearestAttackableTargetGoal}(2026-09-24、軽量化)。
     *
     * <p>中身は {@code new NearestAttackableTargetGoal<>(mob, LivingEntity.class, 10, true, false,
     * candidate -> candidate instanceof ParasitizedMob)} そのままで、違うのは候補の集め方だけ。
     * バニラは索敵箱の生き物を全部集めてから 1 体ずつ絞るが、過繁茂したバイオームでは箱の中が
     * 汚染シダや過繁茂 Mob だらけで、ほとんどが捨てられる。ここでは集める段階
     * ({@link #PARASITIZED_ONLY})で {@link ParasitizedMob} 以外を読み飛ばす。
     * 並び順・{@code targetConditions}(視線も含む)・乱数の引き方は同じなので、選ばれる相手は変わらない。
     */
    private static final class ParasitizedOnlyTargetGoal extends NearestAttackableTargetGoal<LivingEntity> {

        ParasitizedOnlyTargetGoal(Mob mob) {
            super(mob, LivingEntity.class, 10, true, false, candidate -> candidate instanceof ParasitizedMob);
        }

        @Override
        protected void findTarget() {
            List<LivingEntity> found = this.mob.level().getEntities(PARASITIZED_ONLY,
                    this.getTargetSearchArea(this.getFollowDistance()), candidate -> true);
            this.target = nearestPassing(found, this.mob.getX(), this.mob.getEyeY(), this.mob.getZ());
        }

        /**
         * {@code level.getNearestEntity(found, targetConditions, mob, x, y, z)} と同じ相手を返す
         * (2026-09-24 軽量化、報告 664・1560 行)。
         *
         * <p>あちらは候補の<b>全員</b>に {@code targetConditions}(視線のレイキャスト込み。mustSee=true)を
         * かけてから、通った中で一番近いもの(同じ距離なら一覧の先の方)を選ぶ。ここでは候補を近い順に
         * 安定に並べ(同じ距離なら一覧の先の方が前)、前から 1 体ずつ試して<b>最初に通った相手で打ち切る</b>。
         * それより前の候補は全部落ちているので、選び方は同じ —— 選ばれる相手は変わらない。
         * レイキャストは、近い順に最初に通る 1 体までしか引かない。判定そのもの・乱数の引き方は変えていない。
         *
         * <p>距離が数でない候補がいると並べ方が {@code <} の比較と食い違うので、そのときはバニラにそのまま任せる。
         */
        @Nullable
        private LivingEntity nearestPassing(List<LivingEntity> found, double x, double y, double z) {
            int n = found.size();
            if (n <= 1) {
                return this.mob.level().getNearestEntity(found, this.targetConditions, this.mob, x, y, z);
            }
            double[] distSq = new double[n];
            for (int i = 0; i < n; i++) {
                double d = found.get(i).distanceToSqr(x, y, z);
                if (Double.isNaN(d)) {
                    return this.mob.level().getNearestEntity(found, this.targetConditions, this.mob, x, y, z);
                }
                distSq[i] = d;
            }
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            // オブジェクト配列の Arrays.sort は安定(同じ距離なら元の並び = 一覧の先の方が前に残る)。
            Arrays.sort(order, (a, b) -> Double.compare(distSq[a], distSq[b]));
            for (Integer index : order) {
                LivingEntity candidate = found.get(index);
                if (this.targetConditions.test(this.mob, candidate)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    /**
     * 各区画の「生き物({@link LivingEntity})」の一覧を、{@link ParasitizedMob} だけ通して読む。
     * 一覧を {@link LivingEntity} のものにしておくのは、バニラの {@code getEntitiesOfClass} と
     * 同じ一覧を同じ順に読むため。
     */
    private static final EntityTypeTest<Entity, LivingEntity> PARASITIZED_ONLY = new EntityTypeTest<>() {
        @Nullable
        @Override
        public LivingEntity tryCast(Entity entity) {
            return entity instanceof LivingEntity living && entity instanceof ParasitizedMob ? living : null;
        }

        @Override
        public Class<? extends Entity> getBaseClass() {
            return LivingEntity.class;
        }
    };

    private ParasitizedHostilityInjector() {
    }
}
