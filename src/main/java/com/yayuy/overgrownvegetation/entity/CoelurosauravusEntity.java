package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.lizard.SimpleLizard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * コエルロサウラウス {@code coelurosauravus}(狩る側の環境生物・原生消費種。ペルム紀の滑空する爬虫類。脇腹の骨の翼で木から木へ滑る)。
 * 狩る側の環境生物はその環境に生息しているだけの生き物(胞子を運ばない)。G1〜G8・環境生物なので、ほかのすべての Mob から敵対される(タグ critter_hunter → plant_side)。
 *
 * <h2>段 1(2026-11-16): 歩く・幹を登る・滑空して降りる</h2>
 * 地上の移動は「トカゲ型」({@link SimpleLizard})。そこへ 1 つの輪を足す:
 * うろつく → 近くの幹(丸太が縦に 4〜20 本【仮】)へ寄る → 真上へ登る(体を縦に・climb・脚の IK は切る)→ 葉の下で休む →
 * 幹から跳んで滑空(glide・旋回で体を傾ける・前へ 0.3 / 沈み 0.06 = 5 進んで 1 落ちる【仮】)→ 地面の近くで dive → 着地して歩く。
 * 滑空中に葉・幹・壁へ当たったら止まって落ちる。落下の痛みは受けない。プレイヤーへの反応(滑空中に気付くなど)は行動の段。
 * 自然には湧かない。体格 2.2(11-16 ユーザー決定)。張り付き(cling)は使わない(11-16 全体管理)。
 * <p>
 * 登る形のアニメ(climb)は胴を −90 度に立て、モデルの原点(足の高さ)を中心に上下へ伸びる(体格 1 で尾の先 −15.6 px・頭 +14.5 px)。
 * 描画で尾の先が足の高さに来るまで持ち上げ、幹の側へ寄せる({@code CoelurosauravusRenderer})。登る間の当たりは尾の側に残る(後の段で見直す)。
 */
public class CoelurosauravusEntity extends SimpleLizard {

    public static final String ID = "coelurosauravus";
    /** 描画倍率 1(大きさは体格 = 属性の scale 2.2 で掛ける)。 */
    public static final float RENDER_SCALE = 1.0F;
    static final double PX = RENDER_SCALE / 16.0D;

    /** 足の置き場(左前・右前・左後ろ・右後ろ、体格 1 のブロック)。walk の着いている間の真ん中(FK で測った): 前は左右 ±3.0 px・前 3.25 px、後ろは左右 ±3.3 px・後ろ 2.0 px。 */
    static final double[] LEG_HOME_LEFT = {3.0D * PX, -3.0D * PX, 3.3D * PX, -3.3D * PX};
    static final double[] LEG_HOME_FWD = {3.25D * PX, 3.25D * PX, -2.0D * PX, -2.0D * PX};
    /** 曲げの付け根: 首 neck・頭 head(z −4.8・−6.8。残りは頭の先の目安)、尾 tail1〜tail4(z 3.4・7.4・11.4・15.2)。 */
    static final double[] BEND_FRONT = {4.8D * PX, 6.8D * PX, 8.5D * PX, 10.0D * PX};
    static final double[] BEND_BACK = {3.4D * PX, 7.4D * PX, 11.4D * PX, 15.2D * PX};
    /**
     * walk・run・climb を 1.0 倍で流したときの速さ(ブロック/tick、体格 1)。11-16 にモデル制作が足先の決まった 1 点で測った値
     * (tools/g5-models/out/g2g3/lizard_gait/coelurosauravus_measure.txt): walk 前足 0.043・後ろ足 0.039 → 0.042、run 前足 0.100・後ろ足 0.091 → 0.095
     * (前足が 1 割速いアニメ)、climb は着いている足が 0.4 秒で前 3.3・後ろ 4.0 単位下がる → 0.028(1 巡で体が 0.44 ブロック上がる)。
     */
    static final double WALK_REF = 0.042D, RUN_REF = 0.095D, CLIMB_REF = 0.028D;
    public static final double JUMP_STRENGTH = 0.62D;

    /** 登る形(climb)の広がり(体格 1 のブロック): 原点から下の尾の先・上の頭の先・前へ出る足先。 */
    public static final double CLIMB_TAIL = 15.6D * PX, CLIMB_HEAD = 14.5D * PX, CLIMB_REACH = 3.4D * PX;

    /** 滑空の速さ(ブロック/tick、体格に依らない): 前へ 0.3・沈み 0.06 = 高さの 5 倍進む(11-16 ユーザー【仮】)。 */
    public static final double GLIDE_FWD = 0.3D, GLIDE_SINK = 0.06D;
    /** 地面の近くの dive(降りに入る)の速さ【仮】。 */
    public static final double DIVE_FWD = 0.22D, DIVE_SINK = 0.11D;
    /** 足の下この高さ(ブロック)に地面が来たら dive【仮】。 */
    static final double DIVE_HEIGHT = 3.0D;
    /** 滑空中のゆるい旋回(度/tick)の上限と、振り直すまでの tick【仮】。 */
    static final float GLIDE_DRIFT_MAX = 2.0F;
    static final int DRIFT_HOLD_MIN = 20, DRIFT_HOLD_MAX = 60;
    /** 登る幹: 丸太が縦に 4〜20 本(11-16 ユーザー【仮】)。登れる高さが 2 ブロックに満たない幹は使わない(体が 4 ブロック近くあるので、低い幹は登っても跳べない)【仮】。 */
    public static final int TRUNK_MIN = 4, TRUNK_MAX = 20;
    static final double MIN_CLIMB = 2.0D;
    /** 幹を探す範囲(水平・上下)【仮】。 */
    static final int TRUNK_SEARCH = 10, TRUNK_SEARCH_Y = 3;
    /** 葉の下で休む tick【仮】。 */
    static final int PERCH_MIN = 60, PERCH_MAX = 200;
    /** 跳ぶ向き: 幹から外向き ±50 度【仮】。 */
    static final float LAUNCH_SPREAD = 50.0F;
    /** 降りてから次に幹へ向かうまで(tick)【仮】と、幹を探す見込み(1 tick に 1/これ)【仮】。 */
    static final int CLIMB_COOLDOWN_MIN = 400, CLIMB_COOLDOWN_MAX = 900, CLIMB_CHANCE = 80;
    /** 体を傾ける量: 回る速さ(度/tick)× これ、上限(度)【仮】。 */
    static final float BANK_PER_TURN = 9.0F, BANK_MAX = 30.0F;

    public static final int MODE_GROUND = 0, MODE_CLIMB = 1, MODE_PERCH = 2, MODE_GLIDE = 3, MODE_DIVE = 4, MODE_FALL = 5;
    private static final EntityDataAccessor<Byte> DATA_MODE = SynchedEntityData.defineId(CoelurosauravusEntity.class, EntityDataSerializers.BYTE);

    private static final RawAnimation CLIMB = RawAnimation.begin().thenLoop("climb");
    private static final RawAnimation GLIDE = RawAnimation.begin().thenLoop("glide");
    private static final RawAnimation DIVE = RawAnimation.begin().thenLoop("dive");

    /** 登っている幹(サーバー)。 */
    @Nullable
    private Trunk trunk;
    private int perchTicks, driftHold, climbCooldown = CLIMB_COOLDOWN_MIN;
    private float drift;
    /** 確かめ用: 次の 1 回は見込みのくじを引かずに幹を探す。 */
    private boolean searchNow;
    /** 確かめ用: 登った・休んだ・跳んだ・当たって落ちた・着いた回数(サーバー)。 */
    public int climbs, perches, launches, knocks, landings;
    private float bankNow, bankPrev;

    public CoelurosauravusEntity(EntityType<? extends CoelurosauravusEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 8);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID).add(Attributes.JUMP_STRENGTH, JUMP_STRENGTH);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_MODE, (byte) MODE_GROUND);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(5, new ClimbTrunkGoal(this));   // うろつき(6)より先
    }

    // ------------------------------------------------------------------ 形(地上・登る・休む・滑空・dive・落ちる)

    /** 今の形({@code MODE_*})。クライアントでも同期した値で答える。 */
    public int mode() {
        return this.entityData.get(DATA_MODE);
    }

    private void setMode(int mode) {
        this.entityData.set(DATA_MODE, (byte) mode);
    }

    public boolean climbing() {
        int m = mode();
        return m == MODE_CLIMB || m == MODE_PERCH;
    }

    public boolean airborne() {
        int m = mode();
        return m == MODE_GLIDE || m == MODE_DIVE;
    }

    /** 地上でない間は「技の最中」(うろつき・止まっては走る・脚の IK・曲げを止める。向きはこの形が決める)。 */
    @Override
    public boolean isActing() {
        return mode() != MODE_GROUND;
    }

    @Override
    public boolean rigsOff() {
        return mode() != MODE_GROUND;
    }

    /** 滑空で降りるのが常なので、落下の痛みは受けない(当たって落ちたときも)。 */
    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    /** 登る・休む・滑空・dive の間は速度を毎 tick 決め打ちにする(推力を持たない。重力・空気の抵抗に任せると比が守れない)。 */
    @Override
    public void travel(Vec3 input) {
        int m = mode();
        if (this.level().isClientSide() || m == MODE_GROUND || m == MODE_FALL) {
            super.travel(m == MODE_FALL ? Vec3.ZERO : input);
            return;
        }
        if (this.isInWater() || this.isPassenger()) {
            setMode(MODE_GROUND);
            this.trunk = null;
            super.travel(input);
            return;
        }
        switch (m) {
            case MODE_CLIMB -> tickClimb();
            case MODE_PERCH -> tickPerch();
            default -> tickGlide(m == MODE_DIVE);
        }
    }

    @Override
    protected void tickLizard() {
        int m = mode();
        if (m == MODE_FALL && (this.onGround() || this.isInWater())) {
            setMode(MODE_GROUND);
            this.landings++;
            this.climbCooldown = cooldown();
        }
        if (m == MODE_GROUND && this.climbCooldown > 0) this.climbCooldown--;
        // 確かめ用(テストクライアントだけ): 名前に climbnow を含む個体は待たずに幹へ向かう
        if (DEV_HOOKS && this.hasCustomName() && this.getCustomName().getString().contains("climbnow")) this.climbCooldown = 0;
    }

    private int cooldown() {
        return CLIMB_COOLDOWN_MIN + this.random.nextInt(CLIMB_COOLDOWN_MAX - CLIMB_COOLDOWN_MIN + 1);
    }

    private void tickClimb() {
        Trunk t = this.trunk;
        if (t == null || !t.stillThere(this)) {
            fall();
            return;
        }
        wantFacing(t.yaw, TURN_ACTION);
        double v = CLIMB_REF * Math.max(this.getScale(), 0.05F);
        double dy = Math.min(v, t.topY - this.getY());
        this.setDeltaMovement(0.0D, dy, 0.0D);
        this.move(MoverType.SELF, this.getDeltaMovement());
        this.setPos(t.x, this.getY(), t.z);   // 横へはずれない(真上へだけ)
        this.fallDistance = 0.0F;
        if (this.verticalCollision || this.getY() >= t.topY - 1.0E-3D) {
            this.setDeltaMovement(Vec3.ZERO);
            setMode(MODE_PERCH);
            this.perches++;
            this.perchTicks = PERCH_MIN + this.random.nextInt(PERCH_MAX - PERCH_MIN + 1);
        }
    }

    private void tickPerch() {
        Trunk t = this.trunk;
        if (t == null || !t.stillThere(this)) {
            fall();
            return;
        }
        wantFacing(t.yaw, TURN_ACTION);
        this.setDeltaMovement(Vec3.ZERO);
        this.setPos(t.x, this.getY(), t.z);
        this.fallDistance = 0.0F;
        if (--this.perchTicks <= 0) launch();
    }

    /** 幹から跳ぶ: 外向き ±50 度へ向き直り、立てた体の真ん中の高さから滑空に入る。 */
    private void launch() {
        Trunk t = this.trunk;
        this.trunk = null;
        float yaw = Mth.wrapDegrees(t == null ? this.getYRot() : t.yaw + 180.0F + (this.random.nextFloat() * 2.0F - 1.0F) * LAUNCH_SPREAD);
        double s = Math.max(this.getScale(), 0.05F);
        double rad = yaw * Mth.DEG_TO_RAD;
        Vec3 to = new Vec3(this.getX() - Mth.sin((float) rad) * 0.3D, this.getY() + CLIMB_TAIL * s, this.getZ() + Mth.cos((float) rad) * 0.3D);
        if (this.level().noCollision(this, this.getBoundingBox().move(to.subtract(this.position())))) this.setPos(to);
        startGlide(yaw);
        this.launches++;
    }

    /** 滑空を始める(確かめ用にも開けてある)。 */
    public void startGlide(float yaw) {
        this.getNavigation().stop();
        this.snapFacing(yaw);
        this.driftHold = 0;
        this.fallDistance = 0.0F;
        setMode(MODE_GLIDE);
    }

    private void tickGlide(boolean diving) {
        if (--this.driftHold <= 0) {
            this.driftHold = DRIFT_HOLD_MIN + this.random.nextInt(DRIFT_HOLD_MAX - DRIFT_HOLD_MIN + 1);
            this.drift = (this.random.nextFloat() * 2.0F - 1.0F) * GLIDE_DRIFT_MAX;
        }
        if (diving) this.drift = 0.0F;   // 降りに入ったらまっすぐ
        float yaw = this.getYRot();
        wantFacing(yaw + this.drift, TURN_ACTION);
        double fwd = diving ? DIVE_FWD : GLIDE_FWD, sink = diving ? DIVE_SINK : GLIDE_SINK;
        float rad = yaw * Mth.DEG_TO_RAD;
        Vec3 vel = new Vec3(-Mth.sin(rad) * fwd, -sink, Mth.cos(rad) * fwd);
        this.setDeltaMovement(vel);
        this.move(MoverType.SELF, vel);
        this.fallDistance = 0.0F;
        if (this.onGround()) {
            setMode(MODE_GROUND);
            this.setDeltaMovement(vel.x * 0.5D, 0.0D, vel.z * 0.5D);
            this.landings++;
            this.climbCooldown = cooldown();
        } else if (this.horizontalCollision) {
            fall();
            this.knocks++;
        } else if (!diving && groundWithin(DIVE_HEIGHT)) {
            setMode(MODE_DIVE);
        }
    }

    /** 止まって落ちる(葉・幹・壁へ当たった・登っていた幹が無くなった)。 */
    private void fall() {
        this.trunk = null;
        this.setDeltaMovement(0.0D, Math.min(0.0D, this.getDeltaMovement().y), 0.0D);
        setMode(MODE_FALL);
    }

    /** 足の下 {@code h} ブロック以内に当たりのあるブロックがあるか。 */
    private boolean groundWithin(double h) {
        AABB box = this.getBoundingBox();
        return !this.level().noCollision(this, new AABB(box.minX, box.minY - h, box.minZ, box.maxX, box.minY, box.maxZ));
    }

    // ------------------------------------------------------------------ 幹

    /**
     * 登る幹と、登る位置・向き・休む高さ(サーバー)。
     *
     * @param base 丸太の根元(下が丸太でなく、当たりのあるブロック)
     * @param logs 根元から縦に続く丸太の数
     * @param x    登る間の体の真ん中(幹の面の外)
     * @param yaw  幹へ向く向き
     * @param topY 休む高さ(体の原点。頭が葉に入らない・体が幹から出ない)
     */
    public record Trunk(BlockPos base, int logs, double x, double z, double startY, float yaw, double topY) {
        /** 体の真ん中の高さに丸太がまだあるか(切られた・燃えた)。 */
        boolean stillThere(CoelurosauravusEntity e) {
            double mid = e.getY() + (CLIMB_TAIL + CLIMB_HEAD) * Math.max(e.getScale(), 0.05F) * 0.5D;
            int y = Mth.clamp(Mth.floor(mid), this.base.getY(), this.base.getY() + this.logs - 1);
            return isLog(e.level().getBlockState(new BlockPos(this.base.getX(), y, this.base.getZ())));
        }
    }

    static boolean isLog(net.minecraft.world.level.block.state.BlockState s) {
        return s.is(BlockTags.LOGS);
    }

    /**
     * {@code base} の幹を登る計画。面は 4 つのうち、立つ所の下に地面があり、登る形の体(幅 = 当たりの幅、高さ = 尾の先から頭の先)を
     * 真上へ滑らせて当たらない面。休む高さ = 当たらずに上がれる所と「頭が最後の丸太の 1 つ上まで」の低いほう。2 ブロックに満たなければ null。
     */
    @Nullable
    public Trunk planClimb(BlockPos base) {
        Level level = this.level();
        if (!isLog(level.getBlockState(base)) || isLog(level.getBlockState(base.below()))) return null;
        if (level.getBlockState(base.below()).getCollisionShape(level, base.below()).isEmpty()) return null;
        int logs = 0;
        while (logs <= TRUNK_MAX && isLog(level.getBlockState(base.above(logs)))) logs++;
        if (logs < TRUNK_MIN || logs > TRUNK_MAX) return null;
        double s = Math.max(this.getScale(), 0.05F);
        double hw = this.getBbWidth() * 0.5D, tall = (CLIMB_TAIL + CLIMB_HEAD) * s;
        double y0 = base.getY();
        double cap = y0 + logs + 1.0D - tall;
        List<Direction> faces = new ArrayList<>(Direction.Plane.HORIZONTAL.stream().toList());
        // 体に近い面から(寄る間に選び直しても、反対側の面へ飛ばない)
        faces.sort(java.util.Comparator.comparingDouble(d -> sq(base.getX() + 0.5D + d.getStepX() - this.getX()) + sq(base.getZ() + 0.5D + d.getStepZ() - this.getZ())));
        for (Direction d : faces) {
            double cx = base.getX() + 0.5D + d.getStepX() * (0.5D + hw + 0.02D), cz = base.getZ() + 0.5D + d.getStepZ() * (0.5D + hw + 0.02D);
            AABB stand = new AABB(cx - hw, y0, cz - hw, cx + hw, y0 + this.getBbHeight(), cz + hw);
            if (!level.noCollision(this, stand) || level.noCollision(this, stand.move(0.0D, -0.2D, 0.0D))) continue;   // 立てる・下に地面がある
            if (!level.noCollision(this, new AABB(cx - hw, y0, cz - hw, cx + hw, y0 + tall, cz + hw))) continue;
            double top = y0;
            while (top + 0.25D <= cap && level.noCollision(this, new AABB(cx - hw, top + 0.25D, cz - hw, cx + hw, top + 0.25D + tall, cz + hw))) top += 0.25D;
            if (top - y0 < MIN_CLIMB) continue;
            return new Trunk(base.immutable(), logs, cx, cz, y0, d.getOpposite().toYRot(), top);
        }
        return null;
    }

    /** まわりの幹を探す(近い順に最初に登れたもの)。 */
    @Nullable
    Trunk findTrunk() {
        BlockPos c = this.blockPosition();
        List<BlockPos> bases = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -TRUNK_SEARCH; dx <= TRUNK_SEARCH; dx++) {
            for (int dz = -TRUNK_SEARCH; dz <= TRUNK_SEARCH; dz++) {
                for (int dy = -TRUNK_SEARCH_Y; dy <= TRUNK_SEARCH_Y; dy++) {
                    p.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    if (isLog(this.level().getBlockState(p)) && !isLog(this.level().getBlockState(p.below()))) bases.add(p.immutable());
                }
            }
        }
        bases.sort(java.util.Comparator.comparingDouble(b -> b.distSqr(c)));
        for (int i = 0; i < bases.size() && i < 6; i++) {
            Trunk t = planClimb(bases.get(i));
            if (t != null) return t;
        }
        return null;
    }

    /** 幹を登り始める(立つ所の真上へ寄せ、幹へ向く)。確かめ用にも開けてある。 */
    public void startClimb(Trunk t) {
        this.getNavigation().stop();
        this.trunk = t;
        this.setPos(t.x, Math.max(this.getY(), t.startY), t.z);
        this.setDeltaMovement(Vec3.ZERO);
        this.snapFacing(t.yaw);
        setMode(MODE_CLIMB);
        this.climbs++;
    }

    /** 確かめ用: 幹へ寄る Goal の最後の様子。 */
    String goalNote = "まだ";

    public String debugGoal() {
        return this.goalNote + " 待ち " + this.climbCooldown;
    }

    /** 確かめ用: 次に幹へ向かうまでの待ちを 0 にし、次の 1 回は見込みのくじ(1/40)を引かずに幹を探す(テストが運に左右されない)。 */
    public void readyToClimb() {
        this.climbCooldown = 0;
        this.searchNow = true;
    }

    @Nullable
    public Trunk trunk() {
        return this.trunk;
    }

    /** うろつきの合間に、近くの幹へ寄って登る。着いたら形が「登る」に変わって終わる(その先は体の tick が進める)。 */
    static final class ClimbTrunkGoal extends Goal {
        private final CoelurosauravusEntity mob;
        @Nullable
        private Trunk plan;
        private int ticks;

        /** 立つ所からこの距離(ブロック)以内なら、寄せて登り始める(幅の広い体は経路の点に早めに「着いた」ことになり、最後の 1 ブロックが詰まりにくい)【仮】。 */
        static final double NEAR = 1.5D;

        ClimbTrunkGoal(CoelurosauravusEntity mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (this.mob.mode() != MODE_GROUND || !this.mob.onGround() || this.mob.isInWater() || this.mob.climbCooldown > 0) return false;
            if (!this.mob.searchNow && this.mob.getRandom().nextInt(reducedTickDelay(CLIMB_CHANCE)) != 0) return false;
            this.mob.searchNow = false;
            this.plan = this.mob.findTrunk();
            this.mob.goalNote = this.plan == null ? "幹なし" : "見つけた " + this.plan;
            if (this.plan == null) this.mob.climbCooldown = 100;   // 近くに幹が無い: 少し待って探し直す【仮】
            return this.plan != null;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            return this.plan != null && this.mob.mode() == MODE_GROUND && this.ticks < 300;
        }

        @Override
        public void start() {
            this.ticks = 0;
            this.mob.getNavigation().moveTo(this.plan.x(), this.plan.startY(), this.plan.z(), 1.0D);
        }

        @Override
        public void tick() {
            this.ticks++;
            Trunk t = this.plan;
            if (t == null) return;
            double dx = t.x() - this.mob.getX(), dz = t.z() - this.mob.getZ();
            boolean near = dx * dx + dz * dz < NEAR * NEAR && Math.abs(this.mob.getY() - t.startY()) < 0.6D;
            this.mob.goalNote = String.format(java.util.Locale.ROOT, "寄る tick %d 残り %.2f 高さの差 %.2f 地面 %s 経路 %s", this.ticks, Math.sqrt(dx * dx + dz * dz), this.mob.getY() - t.startY(), this.mob.onGround(), this.mob.getNavigation().isDone() ? "なし" : "あり");
            if (near && this.mob.onGround()) {
                Trunk fresh = this.mob.planClimb(t.base());   // 寄る間に変わっていないか
                if (fresh != null) this.mob.startClimb(fresh);
                else this.mob.goalNote = "着いたが登れない";
                this.plan = null;
                return;
            }
            if (dx * dx + dz * dz < 2.5D * 2.5D) {
                // 幅の広い体は経路の点に早めに「着いた」ことになる(幅の半分 0.77)ので、最後は立つ所へまっすぐ寄る
                this.mob.getNavigation().stop();
                this.mob.getMoveControl().setWantedPosition(t.x(), t.startY(), t.z(), 0.6D);
            } else if (this.mob.getNavigation().isDone() && this.ticks % 20 == 0) {
                this.mob.getNavigation().moveTo(t.x(), t.startY(), t.z(), 1.0D);
            }
        }

        @Override
        public void stop() {
            if (this.mob.mode() == MODE_GROUND && this.mob.climbCooldown <= 0) this.mob.climbCooldown = 200;   // 着けなかった【仮】
            this.plan = null;
        }
    }

    // ------------------------------------------------------------------ 描画

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            this.bankPrev = this.bankNow;
            float want = airborne() ? Mth.clamp(getTurnRate() * BANK_PER_TURN, -BANK_MAX, BANK_MAX) : 0.0F;
            this.bankNow += (want - this.bankNow) * 0.2F;
        }
    }

    /** 描画: 旋回で体を倒す角度(度、+ が右回りの旋回)。 */
    public float bank(float pt) {
        return Mth.lerp(pt, this.bankPrev, this.bankNow);
    }

    @Nullable
    @Override
    protected RawAnimation actAnimation(AnimationController<SimpleLizard> c) {
        double s = Math.max(this.getScale(), 0.05F);
        switch (mode()) {
            case MODE_CLIMB -> {
                c.setAnimationSpeed(Mth.clamp((this.getY() - this.yo) / (CLIMB_REF * s), 0.3D, 3.0D));
                return CLIMB;
            }
            case MODE_PERCH -> {
                c.setAnimationSpeed(0.0D);   // 葉の下で止まる(登る形のまま)
                return CLIMB;
            }
            case MODE_GLIDE, MODE_FALL -> {
                return GLIDE;
            }
            case MODE_DIVE -> {
                return DIVE;
            }
            default -> {
                return null;
            }
        }
    }

    @Override
    protected double[] legHomeLeft() {
        return LEG_HOME_LEFT;
    }

    @Override
    protected double[] legHomeFwd() {
        return LEG_HOME_FWD;
    }

    @Override
    protected double[] bendFront() {
        return BEND_FRONT;
    }

    @Override
    protected double[] bendBack() {
        return BEND_BACK;
    }

    /** 登る形は原点から上へ 4 ブロック近く伸びるので、その間は広く取る。 */
    @Override
    protected double cullingRadius() {
        return (climbing() ? 2.0D : 1.0D) * this.getScale();
    }

    @Override
    protected double walkRef() {
        return WALK_REF;
    }

    @Override
    protected double runRef() {
        return RUN_REF;
    }

    /** 足の流れる距離 0.04〜0.11 ブロック(体格 1。速さ × 1.4)【仮】。walk の前足は体格 1 で ±0.108 ブロック運ぶ。 */
    @Override
    protected double[] stepRange() {
        return new double[]{0.04D, 0.11D, 1.4D};
    }

    @Override
    protected double[] footProbe() {
        return new double[]{0.05D, 0.2D, 0.35D};
    }
}
