package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.parts.HitPart;
import com.yayuy.overgrownvegetation.entity.parts.HitPartSet;
import com.yayuy.overgrownvegetation.entity.tower.TowerNeck;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 「フィロセンス」(G4・森の目)— <b>監視塔</b>(2026-11-14 に竹馬型から作り直し。仕様の正本は Vault「Mob の能力/フィロセンス」の
 * 「作り直し: 竹馬型をやめて『監視塔』にする」以降)。竹馬の体は {@link StiltPhyllosensEntity}(内部用の id {@code phyllosens_stilt})。
 *
 * <p>蔦で編まれた太い塔(閉じて約 13 ブロック)が森に立ち、<b>動かない・押されない・重力で落ちない</b>。てっぺんがほどけて 10 節・15 ブロックの
 * 首になり、先に花の頭が付く。エンティティの位置は塔の根元の地面(モデルの root)、当たり判定は塔の幹(2.6 × 13)。叩ける部位は頭と板根 4 枚
 * ({@link HitPartSet})。行動は {@link PhyllosensWatch}(閉じた待機 ⇄ 見張り ⇄ 覗き・叩かれる・崩れる)。
 *
 * <h2>体力【仮・Claude 11-14】</h2>
 * 塔の本体 40(幹・頭を叩くとここへ)。板根は 1 枚 {@link #PLATE_HP} を別に持ち(防御 {@link #PLATE_ARMOR})、本体へは入らない。2 枚折れると腰から崩れる。
 * 起き上がるときに編み直して板根も戻る。
 *
 * <h2>古いワールドの竹馬</h2>
 * 11-14 より前のワールドに残った竹馬の個体({@code phyllosens} で保存。胴の位置 = 地面から 2.5〜6.5 の空中)は、この監視塔として読み込まれる。
 * 監視塔の印 {@link #TAG_TOWER} が無い個体は、最初の tick に真下の地面まで下ろす({@link #settle})。
 */
public class PhyllosensEntity extends Monster implements GeoEntity, ParasitizedMob {

    public static final String ID = "phyllosens";
    /** 保存の印(これが無い = 竹馬の時代の個体)。 */
    static final String TAG_TOWER = "OvTower";

    /** 板根 1 枚の体力・防御【仮・Vault の竹馬の脚 16 / 防御 8 を読み替え】。 */
    public static final float PLATE_HP = 16.0F, PLATE_ARMOR = 8.0F;
    /** 板根の無敵時間(tick)。 */
    static final int PLATE_INVUL = 10;
    /** 倒れるアニメ death(2.0 秒)の間は消さない。 */
    static final int DEATH_TICKS = 40;

    /** 部位の並び: 0 = 頭、1〜4 = 板根(geo の stump_f1〜f4)。 */
    static final String[] PART_NAMES = {"head", "root_1", "root_2", "root_3", "root_4"};
    /**
     * 板根の中心(前・左・上、ブロック)。geo の stump_f の軸(根元の輪の上、半径 約 20 単位)から外へ同じ向きに 2 倍(箱は半径 22〜58 単位)。
     * ファイルの空間の +X は Mob の左。
     */
    static final double[][] PLATE_AT = {
            {33.9 / 16.0, 21.2 / 16.0, 0.5},
            {-18.8 / 16.0, 35.3 / 16.0, 0.5},
            {-36.5 / 16.0, -16.3 / 16.0, 0.5},
            {15.0 / 16.0, -37.1 / 16.0, 0.5}};

    private static final EntityDataAccessor<Integer> DATA_STATE = SynchedEntityData.defineId(PhyllosensEntity.class, EntityDataSerializers.INT);
    /** 覗きの首の狙い: 面の中の (前 r, 上 y)(単位)と面の向き(度)。 */
    private static final EntityDataAccessor<Vector3f> DATA_NECK = SynchedEntityData.defineId(PhyllosensEntity.class, EntityDataSerializers.VECTOR3);
    /** 見る点(エンティティからの差、ブロック)。 */
    private static final EntityDataAccessor<Vector3f> DATA_LOOK = SynchedEntityData.defineId(PhyllosensEntity.class, EntityDataSerializers.VECTOR3);
    /** 覗きの種類(2 bit)・覗きの効き(7 bit)・見つめの効き(7 bit)・折れた板根(4 bit)・見つめの向き(度 + 180、9 bit)。 */
    private static final EntityDataAccessor<Integer> DATA_PACK = SynchedEntityData.defineId(PhyllosensEntity.class, EntityDataSerializers.INT);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final HitPartSet hitParts;
    private final PhyllosensWatch watch;
    final float[] plateHp = {PLATE_HP, PLATE_HP, PLATE_HP, PLATE_HP};
    private final int[] plateInvul = new int[4];
    /** 部位の口から本体の hurt へ渡す間だけ持つ(-1 = 本体)。 */
    private int hurtPart = -1;
    private boolean settled;

    // ---- クライアント(描画の補間)
    private float peekW0, peekW1, lockW0, lockW1, lockYaw0, lockYaw1;
    private int lastSerial = -1;
    private PhyllosensWatch.State lastState;
    /** 確かめ用(テストクライアント -Dov.devbridge): アニメが変わったらログへ。 */
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");
    private String lastAnimName = "";

    public PhyllosensEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.xpReward = 12;
        float[] w = {1.5F, 1.4F, 1.4F, 1.4F, 1.4F}, h = {1.3F, 1.0F, 1.0F, 1.0F, 1.0F};
        this.hitParts = new HitPartSet(this, PART_NAMES, w, h);
        this.hitParts.onHurt(this::onPartHurt);
        this.setId(ENTITY_COUNTER.getAndAdd(this.hitParts.count() + 1) + 1);
        this.watch = new PhyllosensWatch(this);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_STATE, 0);
        this.entityData.define(DATA_NECK, new Vector3f());
        this.entityData.define(DATA_LOOK, new Vector3f(0, 21, 4));
        this.entityData.define(DATA_PACK, 180 << 20);
    }

    public PhyllosensWatch watch() {
        return this.watch;
    }

    public HitPartSet hitParts() {
        return this.hitParts;
    }

    // ------------------------------------------------------------------ 部位

    @Override
    public void setId(int id) {
        super.setId(id);
        if (this.hitParts != null) this.hitParts.assignIds(id + 1);
    }

    @Override
    public boolean isMultipartEntity() {
        return true;
    }

    @Override
    public net.minecraftforge.entity.PartEntity<?>[] getParts() {
        return this.hitParts.parts();
    }

    private void onPartHurt(HitPart part, DamageSource src) {
        this.hurtPart = this.hitParts.indexOf(part.name);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        int part = this.hurtPart;
        this.hurtPart = -1;
        if (this.level().isClientSide() || this.isDeadOrDying()) return super.hurt(source, amount);
        if (this.isInvulnerableTo(source)) return false;
        if (part >= 1 && part <= 4) {
            return hurtPlate(part - 1, source, amount);
        }
        boolean hit = super.hurt(source, amount);
        if (hit && part == 0) this.watch.onHeadHit(source);
        return hit;
    }

    /** 板根を叩かれた: 板根の体力だけ減らす(本体へは入れない)。根元から胞子を吹く。 */
    private boolean hurtPlate(int i, DamageSource source, float amount) {
        if (this.plateHp[i] <= 0.0F || this.plateInvul[i] > 0) return false;
        float dmg = CombatRules.getDamageAfterAbsorb(amount, PLATE_ARMOR, 0.0F);
        this.plateHp[i] = Math.max(0.0F, this.plateHp[i] - dmg);
        this.plateInvul[i] = PLATE_INVUL;
        if (this.level() instanceof ServerLevel sl) {
            Vec3 c = platePos(i);
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SPRUCE_WOOD.defaultBlockState()), c.x, c.y, c.z, 10, 0.4, 0.3, 0.4, 0.1);
        }
        this.watch.onPlateHit(i, this.plateHp[i] <= 0.0F, source);
        return true;
    }

    /** 板根 i の真ん中(ワールド)。 */
    public Vec3 platePos(int i) {
        double[] a = PLATE_AT[i];
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), lx = Mth.cos(yaw), lz = Mth.sin(yaw);
        return new Vec3(this.getX() + fx * a[0] + lx * a[1], this.getY() + a[2], this.getZ() + fz * a[0] + lz * a[1]);
    }

    public int brokenPlates() {
        int n = 0;
        for (float h : this.plateHp) if (h <= 0.0F) n++;
        return n;
    }

    int brokenMask() {
        int m = 0;
        for (int i = 0; i < 4; i++) if (this.plateHp[i] <= 0.0F) m |= 1 << i;
        return m;
    }

    /** 起き上がり: 編み直して板根も戻る【仮】。 */
    void restorePlates() {
        for (int i = 0; i < 4; i++) this.plateHp[i] = PLATE_HP;
    }

    private void placeParts() {
        double[] c = new double[PART_NAMES.length * 3];
        Vec3 head = this.watch.headPartCenter();
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        double dx = head.x - this.getX(), dz = head.z - this.getZ();
        c[0] = dx * -Mth.sin(yaw) + dz * Mth.cos(yaw);
        c[1] = dx * Mth.cos(yaw) + dz * Mth.sin(yaw);
        c[2] = head.y - this.getY();
        int broken = this.level().isClientSide() ? (this.entityData.get(DATA_PACK) >> 16) & 0xF : brokenMask();
        for (int i = 0; i < 4; i++) {
            double[] a = PLATE_AT[i];
            boolean off = (broken & (1 << i)) != 0;
            c[(i + 1) * 3] = a[0];
            c[(i + 1) * 3 + 1] = a[1];
            c[(i + 1) * 3 + 2] = off ? -50.0D : a[2];   // 折れた板根は地面の下へ逃がす(叩けない)
        }
        this.hitParts.place(this, this.getYRot(), c);
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        // 動かない: 押し流し・ノックバックで動いた分を消す
        this.setDeltaMovement(Vec3.ZERO);
        super.tick();
        this.yBodyRot = this.getYRot();
        this.yHeadRot = this.getYRot();
        if (this.level().isClientSide()) {
            clientTick();
        } else {
            if (!this.settled) settle();
            for (int i = 0; i < 4; i++) if (this.plateInvul[i] > 0) this.plateInvul[i]--;
            sync();
        }
        placeParts();
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (this.isAlive()) this.watch.tick();
    }

    /** 真下の地面まで下ろす(古い竹馬の個体・空中に置かれた卵)。足場が 24 ブロック以内に無ければそのまま。 */
    void settle() {
        this.settled = true;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int x = Mth.floor(this.getX()), z = Mth.floor(this.getZ()), y0 = Mth.floor(this.getY() + 0.01D);
        for (int y = y0; y > y0 - 24 && y > this.level().getMinBuildHeight(); y--) {
            p.set(x, y - 1, z);
            BlockState s = this.level().getBlockState(p);
            if (!s.getCollisionShape(this.level(), p).isEmpty()) {
                double top = p.getY() + s.getCollisionShape(this.level(), p).max(net.minecraft.core.Direction.Axis.Y);
                if (Math.abs(top - this.getY()) > 1.0E-3D) this.moveTo(this.getX(), top, this.getZ(), this.getYRot(), 0.0F);
                return;
            }
        }
    }

    private void sync() {
        PhyllosensWatch w = this.watch;
        this.entityData.set(DATA_STATE, w.state().ordinal() | ((w.serial() & 0xFFFF) << 8));
        this.entityData.set(DATA_NECK, new Vector3f((float) w.peekR(), (float) w.peekY(), (float) w.peekYaw()));
        Vec3 look = w.lookAt().subtract(this.position());
        this.entityData.set(DATA_LOOK, new Vector3f((float) look.x, (float) look.y, (float) look.z));
        int lockYaw = Mth.clamp((int) Math.round(Mth.wrapDegrees(w.lockYaw())) + 180, 0, 360);
        this.entityData.set(DATA_PACK, (w.peekKind().ordinal() & 3) | (Math.round(w.peekWeight() * 100) << 2)
                | (Math.round(w.lockWeight() * 100) << 9) | (brokenMask() << 16) | (lockYaw << 20));
    }

    private void clientTick() {
        int pk = this.entityData.get(DATA_PACK);
        this.peekW0 = this.peekW1;
        this.lockW0 = this.lockW1;
        this.lockYaw0 = this.lockYaw1;
        this.peekW1 = ((pk >> 2) & 0x7F) / 100.0F;
        this.lockW1 = ((pk >> 9) & 0x7F) / 100.0F;
        this.lockYaw1 = ((pk >> 20) & 0x1FF) - 180.0F;
        if (this.tickCount <= 1) {
            this.peekW0 = this.peekW1;
            this.lockW0 = this.lockW1;
            this.lockYaw0 = this.lockYaw1;
        }
        this.watch.clientTick(state(), this.peekW1, this.lockW1, this.lockYaw1, peekKind(), neckTarget(), this.position().add(lookOffset()));
    }

    // ------------------------------------------------------------------ 描画が読む

    public PhyllosensWatch.State state() {
        int s = this.entityData.get(DATA_STATE) & 0xFF;
        PhyllosensWatch.State[] v = PhyllosensWatch.State.values();
        return v[Mth.clamp(s, 0, v.length - 1)];
    }

    public int stateSerial() {
        return (this.entityData.get(DATA_STATE) >> 8) & 0xFFFF;
    }

    public PhyllosensWatch.PeekKind peekKind() {
        return PhyllosensWatch.PeekKind.values()[Math.min(this.entityData.get(DATA_PACK) & 3, 2)];
    }

    public Vector3f neckTarget() {
        return this.entityData.get(DATA_NECK);
    }

    public Vec3 lookOffset() {
        Vector3f v = this.entityData.get(DATA_LOOK);
        return new Vec3(v.x(), v.y(), v.z());
    }

    public float peekWeight(float pt) {
        return Mth.lerp(pt, this.peekW0, this.peekW1);
    }

    public float lockWeight(float pt) {
        return Mth.lerp(pt, this.lockW0, this.lockW1);
    }

    public float lockYaw(float pt) {
        return Mth.lerp(pt, this.lockYaw0, this.lockYaw1);
    }

    public int clientBrokenMask() {
        return (this.entityData.get(DATA_PACK) >> 16) & 0xF;
    }

    // ------------------------------------------------------------------ 動かない立ち物

    @Override
    public void travel(Vec3 input) {
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void push(Entity entity) {
    }

    @Override
    public void push(double x, double y, double z) {
    }

    @Override
    public void knockback(double strength, double x, double z) {
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
    }

    /** 塔は地面に根付いている(足元の葉・草で窒息しない)。 */
    @Override
    public boolean isInWall() {
        return false;
    }

    /** 立ち続ける(遠くても消えない)。ピースフルではバニラの決まりどおり消える(ほかの植物側と同じ)。 */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    protected BodyRotationControl createBodyControl() {
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                PhyllosensEntity.this.yBodyRot = PhyllosensEntity.this.getYRot();
                PhyllosensEntity.this.yHeadRot = PhyllosensEntity.this.getYRot();
            }
        };
    }

    /** 首と花は根元から 15 ブロック先まで・見張りで 23 ブロックの高さまで届く。 */
    @Override
    public AABB getBoundingBoxForCulling() {
        return this.getBoundingBox().expandTowards(0.0D, 11.0D, 0.0D).inflate(11.0D, 0.0D, 11.0D);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean(TAG_TOWER, true);
        for (int i = 0; i < 4; i++) tag.putFloat("Plate" + i, this.plateHp[i]);
        this.watch.save(tag);   // 崩れ・倒れ・起き上がりの途中(読み込み直しで板根が折れたまま残らないよう。①10)
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        // 竹馬の時代の個体(印なし)は最初の tick に地面へ下ろす。監視塔は置かれた所に根付いているので下ろさない
        this.settled = tag.getBoolean(TAG_TOWER);
        for (int i = 0; i < 4; i++) {
            if (tag.contains("Plate" + i)) this.plateHp[i] = tag.getFloat("Plate" + i);
        }
        this.watch.load(tag);
        this.setNoGravity(true);
    }

    // ------------------------------------------------------------------ 倒れる

    /** 倒れるアニメ(2 秒)の後に膨らんで破裂する(11-15 ユーザー決定。消すのは {@code corpse/PlantSideBurst}、ここは外されたときの控え)。 */
    @Override
    protected void tickDeath() {
        BurstDeath.tick(this, DEATH_TICKS);
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        this.watch.onDeath();
    }

    // ------------------------------------------------------------------ アニメ

    static final String PUFF = "puff";

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", PhyllosensWatch.LEAD, this::mainState));
        // 胞子を吹く(倍率のキーだけの重ねる用)。サーバーから triggerAnim で
        controllers.add(new AnimationController<>(this, PUFF, 0, s -> PlayState.STOP)
                .triggerableAnim("head", RawAnimation.begin().thenPlay("spore_puff_head"))
                .triggerableAnim("base", RawAnimation.begin().thenPlay("spore_puff_base")));
    }

    private PlayState mainState(AnimationState<PhyllosensEntity> state) {
        AnimationController<PhyllosensEntity> c = state.getController();
        PhyllosensWatch.State st = this.isDeadOrDying() ? PhyllosensWatch.State.DEAD : state();
        RawAnimation want = PhyllosensWatch.animation(st, peekKind(), lockWeight(1.0F) > 0.5F);
        int serial = stateSerial();
        if (serial != this.lastSerial) {
            boolean reentry = this.lastSerial >= 0 && st == this.lastState;
            this.lastSerial = serial;
            this.lastState = st;
            // 同じ状態へ入り直した(叩かれて引っ込む が続いた など)ときだけ頭から流し直す。状態が変わっただけ(崩れる → 倒れている、
            // 覗きの前触れ → 覗く)は同じアニメの続きなので流し直さない(流し直すと前触れから再生し直して形が飛ぶ)
            if (reentry && !PhyllosensWatch.keepsLoop(st)) c.forceAnimationReset();
        }
        if (DEV_LOG && c.getCurrentAnimation() != null && !c.getCurrentAnimation().animation().name().equals(this.lastAnimName)) {
            this.lastAnimName = c.getCurrentAnimation().animation().name();
            System.out.println("[phyllosens-anim] t=" + this.tickCount + " " + st + " → " + this.lastAnimName);
        }
        // 繰り返しだけのアニメ(見回し・閉じた待機)へ戻るときは、今そのアニメが流れていれば続ける(位相が飛ばないように)。見つめ(watch_lock)は除く
        if ((want == PhyllosensWatch.A_WATCH || want == PhyllosensWatch.A_CLOSED) && c.getCurrentAnimation() != null
                && PhyllosensWatch.loopName(st, peekKind()).equals(c.getCurrentAnimation().animation().name())) {
            return PlayState.CONTINUE;
        }
        return state.setAndContinue(want);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    /** 首の付け根(ワールド)。 */
    public Vec3 neckBase() {
        return TowerNeck.baseWorld(TowerNeck.PHYLLOSENS, this.position(), this.getYRot());
    }
}
