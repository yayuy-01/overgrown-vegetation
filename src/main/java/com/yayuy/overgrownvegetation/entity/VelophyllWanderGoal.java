package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * ヴェロフィルの<b>ふだんの飛び回り</b>。地表から {@code velophyllCruiseAltitude}(既定 28)
 * ブロックの高さを保って流す。
 *
 * <p>行き先は<b>必ず遠くに置く</b>(最短 40 ブロック)。{@link VelophyllFlightMoveControl} は
 * 1 tick に {@code velophyllTurnRate} 度しか曲がれないので、近い点を次々に置くと
 * 「曲がりきる前に着いてしまう → また曲がる」でふらついて見える。遠くに置いて、
 * ゆっくり向きを合わせながら長い直線を飛ばせるのが飛行機らしい見え方になる。
 *
 * <p>行き先はいまの進行方向から<b>大きく外れすぎない角度</b>で選ぶ(真後ろを選ぶと
 * 大回りの U ターンに丸ごと費やしてしまう)。
 *
 * <h2>群れの上空から離れない(2026-09-15)</h2>
 * 地上にグループ4 の仲間がいるときは、行き先を<b>その重心から {@code velophyllPackLeash} の内側</b>に
 * 限る(ユーザー要望「グループ4 の mob 同士で集団行動」)。飛行中は経路探索を使わないので
 * {@link InvaderPackGoal} は使えず、代わりに<b>行き先の選び方</b>で同じことをしている。
 *
 * <p>候補が全部外れたときは、重心の周りを回る点をじかに置く —— これが無いと、群れから遠ざかる
 * 向きで飛んでいる間は候補が 1 つも通らず、いつまでも戻れない。結果として
 * <b>群れの上空を大きく旋回し続ける</b>見え方になる。
 */
public class VelophyllWanderGoal extends Goal {

    /** 行き先を選ぶ水平距離(ブロック)。近い点は置かない。 */
    private static final double MIN_RANGE = 40.0D;
    private static final double MAX_RANGE = 90.0D;
    /** いまの進行方向から左右どこまで曲げた先を選んでよいか(度)。 */
    private static final float MAX_HEADING_CHANGE = 100.0F;
    /** 行き先を選び直すまで粘る上限(tick)。 */
    private static final int MAX_TICKS = 600;
    /** 行き先に着いたとみなす水平距離(ブロック)。 */
    private static final double ARRIVE_DISTANCE = 12.0D;
    /** 回り込む点を置く半径(群れの綱の長さに対する割合)。 */
    private static final double ORBIT_FRACTION = 0.6D;
    /** 旋回の中心を先遣隊より前へずらす倍率(陣形の前衛ぶん)。 */
    private static final double FORWARD_SCOUT = 3.0D;
    /** 回り込む点を左右へ振る幅(度)。0 だと毎回同じ真裏になって往復運動に見える。 */
    private static final double ORBIT_JITTER = 50.0D;

    private final VelophyllEntity mob;
    private Vec3 destination;
    private int ticks;

    public VelophyllWanderGoal(VelophyllEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.mob.isGrounded() || this.mob.hasAttackTarget()) {
            return false;
        }
        // 飛び立つモーションの間は始めない(終わったら本体が滑空へ戻す)。地上の胞子撒きが終わった
        // 同じ tick にここが始まると、start() の滑空で飛び立ちを上書きして、飛び立つモーションが一度も見えなかった。
        if (this.mob.getActionState() == VelophyllEntity.STATE_TAKEOFF) {
            return false;
        }
        this.destination = pickDestination();
        return this.destination != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.destination != null
                && !this.mob.isGrounded()
                && !this.mob.hasAttackTarget()
                && this.ticks < MAX_TICKS
                && horizontalDistanceTo(this.destination) > ARRIVE_DISTANCE;
    }

    @Override
    public void start() {
        this.ticks = 0;
        this.mob.setActionState(VelophyllEntity.STATE_GLIDE);
        this.mob.setMoveTargetPoint(this.destination);
    }

    @Override
    public void stop() {
        this.destination = null;
    }

    @Override
    public void tick() {
        this.ticks++;
    }

    private double horizontalDistanceTo(Vec3 point) {
        return Math.hypot(this.mob.getX() - point.x, this.mob.getZ() - point.z);
    }

    /**
     * 巡航高度にある、遠くて・進行方向から外れすぎず・<b>群れの上空から離れすぎない</b>点。
     * 1 つも見つからなければ群れの重心を回る点、群れが居なければ {@code null}。
     */
    @Nullable
    private Vec3 pickDestination() {
        double leash = this.mob.packCohesionDistance();
        // 0 は「綱なし」。群れを見ずに今までどおり流す。
        Vec3 anchor = leash > 0.0D ? packAnchor() : null;
        // yRot は「+Z が 0 度」。進行方向のコンパス角へ直してから左右へ振る。
        float heading = this.mob.getYRot() + 90.0F;
        for (int attempt = 0; attempt < 8; attempt++) {
            float offset = (this.mob.getRandom().nextFloat() * 2.0F - 1.0F) * MAX_HEADING_CHANGE;
            double angle = (heading + offset) * Mth.DEG_TO_RAD;
            double distance = Mth.lerp(this.mob.getRandom().nextDouble(), MIN_RANGE, MAX_RANGE);
            double x = this.mob.getX() + Math.cos(angle) * distance;
            double z = this.mob.getZ() + Math.sin(angle) * distance;
            // 群れがいるなら、そこから離れすぎる点は選ばない。
            if (anchor != null && Math.hypot(x - anchor.x, z - anchor.z) > leash) {
                continue;
            }
            Vec3 destination = atCruiseAltitude(x, z);
            if (destination != null) {
                return destination;
            }
        }
        // 群れから離れる向きに飛んでいると候補が 1 つも通らない。重心の周りを回る点をじかに置く。
        return anchor != null ? orbitAround(anchor, leash) : null;
    }

    /**
     * 飛び回る中心にする点。
     *
     * <ol>
     *     <li><b>編成({@link InvaderPackComposition})に足りない種が居る</b>なら、その<b>いちばん近い個体</b>
     *     ——「まだ群れが揃っていないので、揃っていない側の上空へ移る」。これが無いと、
     *     1 体だけ湧いたヴェロフィルはいつまでも自分の空域を流していて群れに加わらない。</li>
     *     <li>揃っていれば<b>地上の仲間の重心</b>。自分は飛んでいるので重心には数えない
     *     ({@link VelophyllEntity#countsInPackCentroid()})。</li>
     *     <li>どちらも居なければ {@code null} で、今までどおり好きに流す。</li>
     * </ol>
     */
    @Nullable
    private Vec3 packAnchor() {
        List<Mob> members = InvaderPack.packMatesNear(this.mob, InvaderPack.scanRange());
        Map<EntityType<?>, Integer> missing = InvaderPackComposition.shortfall(this.mob, members);
        if (!missing.isEmpty()) {
            Mob recruit = InvaderPack.nearestRecruit(this.mob, missing.keySet(), InvaderPack.recruitRange());
            if (recruit != null) {
                return recruit.position();
            }
        }
        // 編成が揃っているなら、群れの先頭を歩く先遣隊(アルボラ)の上空に付く
        // (ユーザー要望 2026-09-15「アルボラを先遣隊とし、その近くへ」)。
        Mob point = InvaderPack.nearestPointUnit(this.mob, members);
        if (point != null) {
            // 陣形では前衛なので、旋回の中心を<b>先遣隊の少し前</b>へずらして群れの先を飛ぶ。
            float yaw = point.yBodyRot * Mth.DEG_TO_RAD;
            double ahead = OvergrownVegetationConfig.COMMON.invaderPackFormationFront.get()
                    * FORWARD_SCOUT;
            return point.position().add(-Mth.sin(yaw) * ahead, 0.0D, Mth.cos(yaw) * ahead);
        }
        return InvaderPack.centroid(this.mob, members);
    }

    /**
     * 重心を回り込む点。置くのは<b>重心を挟んで自分と反対側</b>(左右に {@value #ORBIT_JITTER} 度まで振る)、
     * 半径は綱の {@value #ORBIT_FRACTION} 倍。
     *
     * <p>反対側に置くのは<b>近すぎる行き先を作らない</b>ため —— 手前に置くと、重心の近くを飛んでいる
     * ときに {@value #ARRIVE_DISTANCE} ブロック以内の点になり、置いた瞬間に「着いた」ことになって
     * 選び直しを繰り返す。反対側なら必ず半径ぶんは離れる。おかげで
     * <b>行って戻る大きな 8 の字</b>を描くようになる。
     */
    @Nullable
    private Vec3 orbitAround(Vec3 anchor, double leash) {
        double dx = this.mob.getX() - anchor.x;
        double dz = this.mob.getZ() - anchor.z;
        // 重心にぴったり重なっているときは向きが出ないので、そのときだけ適当な方向へ。
        double base = (dx * dx + dz * dz < 1.0E-4D)
                ? this.mob.getRandom().nextDouble() * Math.PI * 2.0D
                : Math.atan2(dz, dx) + Math.PI;
        double jitter = (this.mob.getRandom().nextDouble() * 2.0D - 1.0D) * ORBIT_JITTER * Mth.DEG_TO_RAD;
        double angle = base + jitter;
        double radius = leash * ORBIT_FRACTION;
        return atCruiseAltitude(anchor.x + Math.cos(angle) * radius,
                anchor.z + Math.sin(angle) * radius);
    }

    /**
     * その水平位置の<b>巡航高度</b>。高さの基準は<b>葉を含む</b> MOTION_BLOCKING —
     * 地面基準(NO_LEAVES)にすると、巨大樹や森の上では巡航高度が枝の中に入ってしまう
     * (2026-09-14「木につっかえる」)。少しばらけさせるのは、目標より上から入ると
     * 降りながら進む区間(= 滑空の姿勢)ができるため。
     */
    @Nullable
    private Vec3 atCruiseAltitude(double x, double z) {
        int cruise = OvergrownVegetationConfig.COMMON.velophyllCruiseAltitude.get();
        int canopy = this.mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING,
                Mth.floor(x), Mth.floor(z));
        double y = canopy + cruise + this.mob.getRandom().nextInt(11) - 5;
        return y >= this.mob.level().getMaxBuildHeight() - 2 ? null : new Vec3(x, y, z);
    }
}
