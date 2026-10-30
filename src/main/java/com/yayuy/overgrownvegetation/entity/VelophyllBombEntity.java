package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.effect.ParasitizedCreeperDetonation;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;


/**
 * ヴェロフィル({@link VelophyllEntity})が落とす<b>胞子爆弾</b>。
 *
 * <p>落ち方と「ブロックを一切壊さない」方針は攻城戦ボスの {@link SiegeBombEntity} と同じだが、
 * 効き方が 3 点だけ違う(ユーザー指定):
 * <ul>
 *     <li><b>爆風が広い</b> — {@code velophyllBombBlastRadius}(既定 6)。</li>
 *     <li><b>汚染は狭い</b> — {@code velophyllBombCorruptionRadius}(既定 2)の、輪郭が波打った円。</li>
 *     <li><b>ダメージが距離で減衰しない</b> — 爆風は範囲内なら一律
 *         {@code velophyllBombBlastDamage}(既定 12)、爆弾が当たった 1 体だけ
 *         {@code velophyllBombDirectDamage}(既定 45)。</li>
 * </ul>
 *
 * <h2>なぜ {@code Level#explode} を使わないのか</h2>
 * バニラの爆発はダメージが「威力 × 距離の減衰 × 遮蔽」で決まるので、「爆風は一律 12・直撃は 45」を
 * 素直に表現できない。ここでは演出(粒子・音)とノックバックを自前で出し、ダメージも自分で配る。
 *
 * <h2>壊すのは葉だけ</h2>
 * 地形は壊さない —— ただし<b>葉({@link ModTags#BOMB_SHATTERS})だけは例外</b>で、
 * <b>落下中は撃ち抜いて</b>({@link #shatterFoliage})、<b>爆発時は
 * {@code velophyllBombLeafClearRadius} の範囲を薙ぎ払う</b>。
 * これが無いと森では全弾が天蓋の上で爆ぜ、木の下の相手には永久に一発も届かない
 * (ユーザー報告 2026-09-14)。ドロップは出さない(このMODの汚染まわりと同じ方針)。
 *
 * <p>グループ1〜10 の Mob は巻き込まない({@link com.yayuy.overgrownvegetation.phase.MobGroupFriendlyFire}
 * と同じ「汚染側は同士討ちしない」方針)。
 */
public class VelophyllBombEntity extends Entity {

    /** 何にも当たらず落ち続けた場合に自然消滅するまでの tick。 */
    private static final int MAX_LIFETIME_TICKS = 400;
    /** 1 tick あたりの落下加速度。照準({@link VelophyllBombGoal})が落下時間の計算に使う。 */
    public static final double GRAVITY = 0.05D;
    /** 1 tick あたりの空気抵抗。 */
    public static final double DRAG = 0.995D;
    /** 生き物への当たり判定を膨らませる量(ブロック)。 */
    private static final double HIT_INFLATE = 0.35D;
    /** 「何に当たったか」を調べるとき、爆弾の当たり判定の箱を膨らませる量(ブロック)。 */
    private static final double SHATTER_PROBE = 0.2D;
    /** 葉を撃ち抜くとき、通り道として開ける穴の半径(ブロック)。 */
    private static final double SHATTER_HOLE_RADIUS = 1.4D;
    /**
     * 爆発時の葉の薙ぎ払い({@link #clearCanopyAround})1 回で出す、葉の破壊演出
     * (levelEvent 2001 = 破壊パーティクル 64 粒 + 破壊音)の上限。消す葉がこれより多ければ等間隔で間引く。
     * 仮決め(2026-09-24): 天蓋の下で炸裂すると 1 発で数百回の破壊演出が一度に届き、重かったため。
     */
    private static final int BOMB_LEAF_EFFECT_MAX = 20;

    /**
     * 爆弾の大きさ(1.0 = ヴェロフィルが落とす通常の爆弾)。威力・各種半径・見た目がまとめてこの倍率になる。
     *
     * <p>1.0 未満になるのは、機関砲台({@code block/GunTurretBlockEntity})が<b>増援を運ぶ胞子弾</b>を
     * 撃ち落としたときに散る小さな爆弾({@link com.yayuy.overgrownvegetation.siege.ShellTracker#intercept})と、
     * 撃ち落とされたペディキュラが散らす爆弾({@link PediculaEntity}、2026-09-23)。
     * 描画にも使うので同期する。
     */
    private static final EntityDataAccessor<Float> DATA_SIZE =
            SynchedEntityData.defineId(VelophyllBombEntity.class, EntityDataSerializers.FLOAT);

    /**
     * 着弾点に汚染シダを 1 株湧かせるか(2026-09-23)。
     *
     * <p>true になるのは、撃ち落とされたペディキュラ({@link PediculaEntity})が散らす小さな爆弾だけ
     * (ユーザー指定:「周囲に小さな胞子爆弾を 4 つ散らばらせ、着弾地点に汚染シダを湧かせる」)。
     * <b>地面に着いて炸裂したときだけ</b>湧く —— 空中で撃ち抜かれた爆弾は、真下の地面が
     * {@value #FERN_GROUND_REACH} ブロックより遠いので湧かない(機関砲台で迎撃すれば防げる)。
     * 描画には関係しないので同期はしない。
     */
    private boolean plantsFern;

    /** 着弾点から何ブロック下まで、汚染シダを植える地面を探すか。 */
    private static final int FERN_GROUND_REACH = 3;

    /**
     * 過繫茂したスカトゥリウムの<b>迫撃砲の弾</b>か(2026-09-24)。機関砲台を止める確率がこれで変わる
     * ({@code defense.json} の {@code gunTurret.mortarDisableChance} = 既定 0.15、それ以外の爆弾は
     * {@code bombDisableChance} = 既定 1.0)。ユーザー指定「迫撃砲が当たって 35% で無効化」→ 2026-09-25 に 15%。
     *
     * <p>落とした本人({@link #owner})で見分けないのは、本人への参照がセーブを挟むと切れるから。
     * 飛んでいる途中でワールドを閉じても迫撃砲の弾のままでいるよう、印として持って保存する。
     */
    private boolean mortarShell;

    /**
     * 撃った種の爆弾の数値の id({@code mobs.json} のその種の {@code bomb})。{@code null} = ヴェロフィルの爆弾の数値 × 大きさ(今までどおり)。
     *
     * <p>ネザーの媒介種(寄生されたガスト・ブレイズ、2026-10-09)が撃つ爆弾だけが持つ。ユーザー指定「火球の代わりに胞子爆弾と同じものを飛ばす。
     * ダメージはもともとの攻撃と同じくらい、燃える代わりに毒が乗る」→ 威力・半径はその種の表の値そのまま(大きさを掛けない。
     * 大きさは見た目だけ)、毒を乗せる、死亡メッセージは「撃った」。撃った本人は G1 なので、獲物(G1〜3)の扱いもしない。
     * 本人の参照はセーブを挟むと切れるので、印として保存する。
     */
    @Nullable
    private String statsId;

    /** 描画時の回転に使う、生成時に決まる乱数の位相。 */
    private float spinSeed;

    /** 落とした本人。巻き込み判定から外すためだけに持つ(消えていても爆発はする)。 */
    @Nullable
    private LivingEntity owner;

    /**
     * 既に爆発したか。{@link #discard()} は爆発処理を抜けた後にしか効かないので、
     * {@code isRemoved()} では処理中の再入を防げない。
     */
    private boolean exploded;

    public VelophyllBombEntity(EntityType<? extends VelophyllBombEntity> type, Level level) {
        super(type, level);
        this.blocksBuilding = false;
    }

    public VelophyllBombEntity(Level level, Vec3 pos, Vec3 velocity, @Nullable LivingEntity owner) {
        this(level, pos, velocity, owner, 1.0F);
    }

    /** @param size 大きさの倍率(1.0 = 通常。威力・半径・見た目がまとめてこの倍率になる) */
    public VelophyllBombEntity(Level level, Vec3 pos, Vec3 velocity, @Nullable LivingEntity owner, float size) {
        this(ModEntities.VELOPHYLL_BOMB.get(), level);
        this.setPos(pos.x, pos.y, pos.z);
        this.setDeltaMovement(velocity);
        this.owner = owner;
        this.spinSeed = level.random.nextFloat() * 360.0F;
        this.entityData.set(DATA_SIZE, Math.max(0.05F, size));
    }

    /** 描画用: 生成時に決まった回転の位相。 */
    public float spinSeed() {
        return this.spinSeed;
    }

    /** 大きさの倍率(1.0 = 通常)。威力・各種半径・描画の大きさがこの倍率になる。 */
    public float size() {
        return this.entityData.get(DATA_SIZE);
    }

    /** 着弾点に汚染シダを湧かせる爆弾にする({@link #plantsFern})。 */
    public VelophyllBombEntity plantingFern() {
        this.plantsFern = true;
        return this;
    }

    /** 着弾点に汚染シダを湧かせる爆弾か。 */
    public boolean plantsFern() {
        return this.plantsFern;
    }

    /** 撃った種の爆弾の数値で効く爆弾にする({@link #statsId})。 */
    public VelophyllBombEntity withStats(String id) {
        this.statsId = id;
        return this;
    }

    /** 撃った種の爆弾の数値の id。ヴェロフィルの爆弾の数値 × 大きさで効くなら {@code null}。 */
    @Nullable
    public String statsId() {
        return this.statsId;
    }

    /** この爆弾の数値(撃った種の表、無ければヴェロフィルの表)。{@code /ov design reload} ですぐ変わるよう、毎回読む。 */
    private com.yayuy.overgrownvegetation.data.MobStat.Bomb cfg() {
        return com.yayuy.overgrownvegetation.data.MobStats.bomb(this.statsId != null ? this.statsId : "velophyll");
    }

    /** 威力・半径に掛ける倍率。ヴェロフィルの表なら大きさ、撃った種の表なら 1(表の値そのまま)。 */
    private float statScale() {
        return this.statsId != null ? 1.0F : size();
    }

    /** スカトゥリウムの迫撃砲の弾にする({@link #mortarShell})。 */
    public VelophyllBombEntity asMortarShell() {
        this.mortarShell = true;
        return this;
    }

    /** スカトゥリウムの迫撃砲の弾か。 */
    public boolean isMortarShell() {
        return this.mortarShell;
    }

    /**
     * 近くで炸裂したとき、機関砲台が止まる確率(0〜1)。迫撃砲の弾は {@code gunTurret.mortarDisableChance}、
     * それ以外は {@code bombDisableChance}。{@code /ov design reload} ですぐ変わるよう、毎回読む。
     */
    public double turretDisableChance() {
        com.yayuy.overgrownvegetation.data.DefenseTable defense = com.yayuy.overgrownvegetation.data.DesignData.defense();
        return this.mortarShell ? defense.turretMortarDisableChance() : defense.turretBombDisableChance();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_SIZE, 1.0F);
    }

    @Override
    public void tick() {
        super.tick();

        Vec3 velocity = this.getDeltaMovement().add(0.0D, -GRAVITY, 0.0D).scale(DRAG);
        this.setDeltaMovement(velocity);
        this.move(MoverType.SELF, velocity);

        if (this.level().isClientSide()) {
            // 航跡は短命版(地面に落ちた粒が 10 秒近く残らないように。2026-09-25、全体監査 #479)。
            this.level().addParticle(ModParticles.OVERGROWN_SPORE_SHORT.get(),
                    this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ(),
                    0.0D, 0.0D, 0.0D);
            return;
        }

        if (this.tickCount > MAX_LIFETIME_TICKS || this.getY() < this.level().getMinBuildHeight() - 8) {
            this.discard();
            return;
        }

        if (this.onGround() || this.verticalCollision || this.horizontalCollision) {
            // 葉に当たっただけなら、砕いて落下を続ける(森で天蓋に防がれ続けないように)。
            if (shatterFoliage((ServerLevel) this.level())) {
                return;
            }
            detonate((ServerLevel) this.level(), null);
            return;
        }
        LivingEntity struck = firstStruck();
        if (struck != null) {
            detonate((ServerLevel) this.level(), struck);
        }
    }

    /**
     * 行く手を塞いでいるのが<b>葉だけ</b>なら砕いて通す(ユーザー要望 2026-09-14:
     * 「木の葉に爆弾が当たった時、木の葉が消えるように」)。
     *
     * <p>これが無いと、森ではすべての爆弾が天蓋の上で爆ぜてしまい、木の下にいる相手には
     * <b>永久に一発も届かない</b>。砕けるのは {@link ModTags#BOMB_SHATTERS} のブロックだけで、
     * 幹や建物に当たれば今までどおりその場で爆発する。
     *
     * @return 砕いて落下を続ける場合 {@code true}。硬いものに当たっていれば {@code false}(= 爆発)。
     */
    private boolean shatterFoliage(ServerLevel level) {
        AABB box = this.getBoundingBox().inflate(SHATTER_PROBE);
        boolean foliage = false;
        for (BlockPos pos : BlockPos.betweenClosed(
                BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (state.is(ModTags.BOMB_SHATTERS)) {
                foliage = true;
            } else if (!state.getCollisionShape(level, pos).isEmpty()) {
                // 葉以外の硬いものが混ざっている。撃ち抜かずにここで爆発する。
                return false;
            }
        }
        if (!foliage) {
            return false;
        }
        // 通り道が塞がったままだと次の tick も同じ所で止まるので、少しだけ広めに開ける。
        // 触れた葉 1 つずつではなく<b>自分の周りを 1 回</b>で済ませる(そのほうが遥かに安い)。
        clearFoliageAround(level, this.blockPosition(), SHATTER_HOLE_RADIUS);
        return true;
    }

    /** {@code center} の周り半径 {@code radius} の葉を、ドロップ無しで壊す。 */
    private static void clearFoliageAround(ServerLevel level, BlockPos center, double radius) {
        int r = Mth.ceil(radius);
        double radiusSq = radius * radius;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusSq) {
                        continue;
                    }
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!level.isLoaded(pos) || !level.getBlockState(pos).is(ModTags.BOMB_SHATTERS)) {
                        continue;
                    }
                    // false = ドロップ無し(このMODの汚染まわりと同じ方針)。破壊の演出は出る。
                    level.destroyBlock(pos, false);
                }
            }
        }
    }

    /**
     * 爆発時の葉の薙ぎ払い。{@link #clearFoliageAround} と同じ葉を同じ順に、同じやり方で消すが、
     * 破壊の演出(levelEvent 2001)だけは最大 {@value #BOMB_LEAF_EFFECT_MAX} 枚ぶん、消した順に等間隔で出す
     * (2026-09-24。半径 5 の球で天蓋の葉を数百枚消すと、1 枚ごとの演出が一度に数百回届いて重かった)。
     *
     * <p>1 枚ずつの処理は {@code Level#destroyBlock(pos, false)} の中身をそのまま写したもの(演出だけを後回しにした):
     * 空気なら何もしない → 火でなければ演出 → (ドロップ無しなので dropResources は呼ばない) →
     * 液体の元ブロックへ置き換え(フラグ 3・再帰 512)→ 置き換えられたら {@code BLOCK_DESTROY} の GameEvent
     * (起こした Entity は無し)。消えるブロック・更新・振動は destroyBlock のときと変わらない。
     * 間引きに乱数は使わない。
     */
    private static void clearCanopyAround(ServerLevel level, BlockPos center, double radius) {
        int r = Mth.ceil(radius);
        // 2026-09-24 軽量化(監査 1846): 葉がありえない場所(開けた拠点など)では球の走査ごと省く。
        // 候補が 1 つも無ければ下のループは何もしないので、消える葉・更新・演出は同じ。
        if (noShatterableAround(level, center, r)) {
            return;
        }
        double radiusSq = radius * radius;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // 演出を出すはずだった葉の位置と状態 id(後でまとめて間引いて出す)。
        LongArrayList effectPos = new LongArrayList();
        IntArrayList effectStateId = new IntArrayList();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusSq) {
                        continue;
                    }
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (!state.is(ModTags.BOMB_SHATTERS)) {
                        continue;
                    }
                    // ここから Level#destroyBlock(pos, false) と同じ(演出だけ effectPos へ回す)。
                    if (state.isAir()) {
                        continue;
                    }
                    FluidState fluid = level.getFluidState(pos);
                    if (!(state.getBlock() instanceof BaseFireBlock)) {
                        effectPos.add(pos.asLong());
                        effectStateId.add(Block.getId(state));
                    }
                    if (level.setBlock(pos, fluid.createLegacyBlock(), Block.UPDATE_ALL, 512)) {
                        level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of((Entity) null, state));
                    }
                }
            }
        }
        // 消した順に等間隔で最大 BOMB_LEAF_EFFECT_MAX 枚(それ以下なら全部)。
        int candidates = effectPos.size();
        int effects = Math.min(candidates, BOMB_LEAF_EFFECT_MAX);
        for (int k = 0; k < effects; k++) {
            int index = (int) ((long) k * candidates / effects);
            level.levelEvent(2001, BlockPos.of(effectPos.getLong(index)), effectStateId.getInt(index));
        }
    }

    /** パレットの状態が爆弾で消える葉か(マスごとの判定と同じタグ)。 */
    private static final Predicate<BlockState> IS_SHATTERABLE = state -> state.is(ModTags.BOMB_SHATTERS);

    /**
     * {@code center} から各軸 {@code r} マスの直方体にかかるチャンクセクションの<b>どれにも</b>
     * {@link ModTags#BOMB_SHATTERS} の葉がありえないか。true なら {@link #clearCanopyAround} の走査は何も消さない
     * (2026-09-24 軽量化、監査 1846。{@code corruption/CorruptionProximity} と同じ手法)。
     *
     * <p>パレット({@link LevelChunkSection#maybeHas})はそのセクションに置かれうる状態の上位集合
     * (使われなくなった状態が残ることはあっても、置かれている状態が漏れることはない)なので、載っていなければ本当に無い。
     * 次のときは確かめずに false を返し、いつもの走査に任せる:
     * <ul>
     *     <li>チャンクがまだ読み込まれていない —— ここでは読み込まない({@code getChunkNow})。</li>
     *     <li>デバッグワールド —— ブロックがセクションではなく座標から決まる。</li>
     *     <li>空気そのものがタグに入っている —— 空気だけのセクションは中身を見ずに空気を返すので、パレットが答えにならない。</li>
     * </ul>
     */
    private static boolean noShatterableAround(ServerLevel level, BlockPos center, int r) {
        if (level.isDebug()
                || Blocks.AIR.defaultBlockState().is(ModTags.BOMB_SHATTERS)
                || Blocks.VOID_AIR.defaultBlockState().is(ModTags.BOMB_SHATTERS)) {
            return false;
        }
        // 高さ範囲の外のマスは走査でも isLoaded が false で飛ばされるので、範囲内のセクションだけ見ればよい。
        int minSectionY = Math.max(level.getMinSection(), (center.getY() - r) >> 4);
        int maxSectionY = Math.min(level.getMaxSection() - 1, (center.getY() + r) >> 4);
        if (minSectionY > maxSectionY) {
            return true;
        }
        for (int chunkX = (center.getX() - r) >> 4; chunkX <= (center.getX() + r) >> 4; chunkX++) {
            for (int chunkZ = (center.getZ() - r) >> 4; chunkZ <= (center.getZ() + r) >> 4; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    return false;
                }
                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
                    // 空気だけのセクションは読むと AIR(上で葉でないと確かめた)なのでパレットは見ない。
                    if (!section.hasOnlyAir() && section.maybeHas(IS_SHATTERABLE)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** 爆弾の当たり判定に触れている、巻き込んでよい生き物のうち最初の 1 体。 */
    @Nullable
    private LivingEntity firstStruck() {
        AABB box = this.getBoundingBox().inflate(HIT_INFLATE);
        for (LivingEntity entity : this.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (canHarm(entity)) {
                return entity;
            }
        }
        return null;
    }

    /**
     * ダメージを与えてよい相手か。
     * <ul>
     *     <li>プレイヤー(クリエイティブ・観戦を除く)</li>
     *     <li>グループ1〜10 に属していない生き物({@link Predation#isInvaderTarget} と同じ線引き)</li>
     *     <li><b>グループ1〜3</b> —— グループ4 の<b>獲物</b>({@link #isPrey})。2026-09-17 に追加。</li>
     * </ul>
     * 落とした本人と、ほかのグループ4・5〜7 は除く。
     *
     * <h2>獲物を通すようにした経緯(ユーザー報告 2026-09-17)</h2>
     * 「同士討ちを防ぐ仕様で、爆弾がグループ2〜3 にダメージを与えられていなかった」。
     * グループ4 がグループ1〜3 を狩る関係は 2026-09-15 に入れてあり、
     * {@link com.yayuy.overgrownvegetation.phase.MobGroupFriendlyFire} にも例外が通っている ——
     * <b>止めていたのはここ</b>だった。この足切りは「グループに属していれば味方」という
     * 捕食より前の線引きのままで、爆風も直撃も獲物へ一切入らなかった。
     */
    private boolean canHarm(LivingEntity entity) {
        if (entity == this.owner || !entity.isAlive()) {
            return false;
        }
        if (entity instanceof net.minecraft.world.entity.player.Player player) {
            return !player.isCreative() && !player.isSpectator();
        }
        // 獲物の判定を先に通す —— グループ1 は ParasitizedMob なので、下の除外に先に当たってしまう。
        if (isPrey(entity)) {
            return true;
        }
        return !(entity instanceof ParasitizedMob)
                && !(entity instanceof BlightFernEntity)
                && MobGroup.of(entity) == null; // Entity 版: 寄生植物装備のプレイヤーは味方
    }

    /**
     * 落とした本人(グループ4)の<b>獲物</b>か。
     *
     * <p>本人が分からないとき(リログで参照が切れたとき)も獲物として扱う ——
     * <b>この爆弾を落とすのはグループ4 しかいない</b>(ヴェロフィルの爆撃と
     * スカトゥリウムの迫撃砲)ので、襲う側を確かめる意味がない。
     */
    private boolean isPrey(LivingEntity entity) {
        if (this.statsId != null) {
            // 撃ったのは G1(ネザーの媒介種)。G1 には獲物が無い(G4 の獲物の線引きを当てない。本人の参照が切れた後も同じ)。
            return false;
        }
        return this.owner != null
                ? Predation.isInvaderPrey(this.owner, entity)
                : Predation.isInvaderPreyGroup(entity);
    }

    /**
     * @param direct 爆弾が直接ぶつかった相手({@code null} なら地形に当たった)。
     *               この 1 体だけが {@code velophyllBombDirectDamage} を追加で受ける。
     */
    private void detonate(ServerLevel level, @Nullable LivingEntity direct) {
        if (this.exploded) {
            return;
        }
        this.exploded = true;
        Vec3 center = this.position();

        applyBlast(level, center, direct);
        // 忌避灯(2026-09-19): 近くで炸裂したら灯りを胞子と蔦で覆って止める。
        com.yayuy.overgrownvegetation.defense.RepellentLamps.coverNear(level, center,
                com.yayuy.overgrownvegetation.data.DesignData.defense().lampCoverRadius() * size());
        // 機関砲台(2026-09-21): 同じく胞子で覆って止める。スカトゥリウムの迫撃砲はこれが狙い。
        // 止まるかどうかは砲台ごとに確率で振る(迫撃砲は 15%、2026-09-25)。
        com.yayuy.overgrownvegetation.defense.GunTurrets.corruptNear(level, center,
                com.yayuy.overgrownvegetation.data.DesignData.defense().turretCorruptRadius() * size(),
                turretDisableChance(), level.random);
        // 爆風で天蓋に穴を開ける。次に落ちてくる爆弾がそこを通れるようになる。
        double leafRadius = cfg().leafClearRadius() * statScale();
        if (leafRadius > 0.0D) {
            // 数百枚になりうるので、破壊の演出だけ間引く版を使う(消える葉は同じ)。
            clearCanopyAround(level, this.blockPosition(), leafRadius);
        }
        corrupt(level, center);
        if (this.plantsFern) {
            plantFern(level, center);
        }
        playEffects(level, center);
        this.discard();
    }

    /**
     * 着弾点の地面に汚染シダを 1 株({@link #plantsFern})。
     *
     * <p>地面は<b>高さマップではなく真下へ数えて</b>探す。高さマップは屋根・張り出し・木の幹の上を
     * 返すので、軒下や洞窟の入口に落ちた爆弾が屋根の上に植えてしまう。
     * 水の底に落ちれば {@link BlightFernPlacement} が汚染水草に差し替える。
     * 除去装置の圏内で湧かないのも {@link BlightFernPlacement#spawn} 側の判定。
     */
    private static void plantFern(ServerLevel level, Vec3 center) {
        BlockPos spot = groundSpot(level, BlockPos.containing(center));
        if (spot != null) {
            BlightFernPlacement.spawn(level, spot, net.minecraft.world.entity.MobSpawnType.MOB_SUMMONED);
        }
    }

    /**
     * {@code from} から {@value #FERN_GROUND_REACH} ブロック下までで、<b>足場の上の空いたマス</b>
     * (植物の本体が入るマス)。見つからなければ {@code null} —— 空中で炸裂した。
     */
    @Nullable
    private static BlockPos groundSpot(ServerLevel level, BlockPos from) {
        BlockPos.MutableBlockPos cursor = from.mutable();
        for (int depth = 0; depth <= FERN_GROUND_REACH; depth++) {
            cursor.set(from.getX(), from.getY() - depth, from.getZ());
            if (!level.isLoaded(cursor) || level.getBlockState(cursor).blocksMotion()) {
                continue;
            }
            if (level.getBlockState(cursor.below()).blocksMotion()) {
                return cursor.immutable();
            }
        }
        return null;
    }

    /** 爆風のダメージ・ノックバック・胞子感染。距離では減衰しない。 */
    private void applyBlast(ServerLevel level, Vec3 center, @Nullable LivingEntity direct) {
        // 爆弾の数値は mobs.json の velophyll.bomb(mod マネージャーの Mob ステータス)。撃った種の爆弾はその種の bomb({@link #statsId})。
        com.yayuy.overgrownvegetation.data.MobStat.Bomb cfg = cfg();
        // 小さい爆弾(機関砲台が撃ち落とした胞子弾の破片)は、威力も半径もそのぶん小さい。
        float size = statScale();
        double radius = cfg.blastRadius() * size;
        float blast = (float) cfg.blastDamage() * size;
        float directBonus = (float) cfg.directDamage() * size;
        double knockback = cfg.knockback() * size;
        double infectRadius = cfg.infectRadius() * size;
        DamageSource source = this.statsId != null
                ? ModDamageTypes.sporeBombShot(level, this, this.owner)
                : ModDamageTypes.velophyllBomb(level, this, this.owner);

        if (direct != null && directBonus > 0.0F) {
            direct.hurt(source, directBonus);
            poison(direct, cfg);
        }
        if (radius <= 0.0D) {
            return;
        }
        double radiusSq = radius * radius;
        double infectSq = infectRadius * infectRadius;
        AABB box = new AABB(center.x - radius, center.y - radius, center.z - radius,
                center.x + radius, center.y + radius, center.z + radius);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
            double distSq = entity.distanceToSqr(center);
            if (distSq > radiusSq) {
                continue;
            }
            if (infectRadius > 0.0D && distSq <= infectSq
                    && AbstractOvergrownInvaderEntity.isSporeVictim(this.owner, entity)) {
                SporeInfectionEffects.plantAttack(entity, 0);
            }
            if (!canHarm(entity)) {
                continue;
            }
            // 直撃した相手は上で 45 を受けているので、爆風ぶんは重ねない。
            if (entity != direct && blast > 0.0F) {
                entity.hurt(source, blast);
                poison(entity, cfg);
            }
            if (knockback > 0.0D) {
                Vec3 push = entity.position().subtract(center);
                double length = push.length();
                if (length > 1.0E-4D) {
                    // 縁でも真上でもなく、中心から外向きに押す。近いほど強い。
                    double strength = knockback * (1.0D - length / radius);
                    entity.push(push.x / length * strength,
                            push.y / length * strength + strength * 0.3D,
                            push.z / length * strength);
                }
            }
        }
    }

    /**
     * 毒を乗せる(撃った種の表の {@code poisonSeconds} が 0 より大きいときだけ。ネザーの媒介種の爆弾、2026-10-09 ユーザー指定
     * 「ガストやブレイズの攻撃で体が燃えた代わりに、寄生されたガストやブレイズの攻撃には毒が乗る」)。
     */
    private void poison(LivingEntity entity, com.yayuy.overgrownvegetation.data.MobStat.Bomb cfg) {
        int ticks = (int) Math.round(cfg.poisonSeconds() * 20.0D);
        if (ticks > 0 && entity.isAlive()) {
            entity.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.POISON, ticks, cfg.poisonAmplifier()), this.owner);
        }
    }

    /** 着弾点の地表を円形に汚染する。輪郭は波打たせる(半径は爆風よりずっと狭い)。 */
    private void corrupt(ServerLevel level, Vec3 center) {
        com.yayuy.overgrownvegetation.data.MobStat.Bomb cfg = cfg();
        double radius = cfg.corruptionRadius() * statScale();
        if (radius <= 0.0D
                || SporeDeviceData.inhibits(level, this.blockPosition(), SporeDeviceData.Inhibit.CORRUPTION)) {
            return;
        }
        ParasitizedCreeperDetonation.corruptSurfaceDisc(level, center, radius,
                cfg.corruptionNoise());
    }

    private void playEffects(ServerLevel level, Vec3 center) {
        double radius = cfg().blastRadius() * statScale();
        // 撃った種の小さい爆弾(ガスト・ブレイズ)は大きな爆発の煙を出さない(爆風の広さに合わせる)。
        level.sendParticles(this.statsId != null ? ParticleTypes.EXPLOSION : ParticleTypes.EXPLOSION_EMITTER,
                center.x, center.y, center.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        // 爆風の広さが目で分かるように、胞子を半径いっぱいへ撒く。粒は短命版(2026-09-25、全体監査 #991)。
        level.sendParticles(ModParticles.OVERGROWN_SPORE_SHORT.get(), center.x, center.y + 0.5D, center.z,
                80, radius * 0.4D, radius * 0.25D, radius * 0.4D, 0.05D);
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, center.x, center.y + 0.8D, center.z,
                30, radius * 0.4D, radius * 0.3D, radius * 0.4D, 0.0D);
        level.playSound(null, center.x, center.y, center.z,
                SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 3.0F, 0.8F);
        level.playSound(null, center.x, center.y, center.z,
                SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.HOSTILE, 1.4F, 0.7F);
    }

    /**
     * <b>爆弾は他の爆発では一切ダメージを受けない。</b>
     * 同時に落ちている爆弾どうしが誘爆し合って {@link StackOverflowError} になるのを根元で断つ
     * ({@link SiegeBombEntity#ignoreExplosion} と同じ理由)。
     */
    @Override
    public boolean ignoreExplosion(net.minecraft.world.level.Explosion explosion) {
        return true;
    }

    /** 落ちてくる爆弾は矢などで撃ち落とせる。 */
    @Override
    public boolean isPickable() {
        return !this.isRemoved();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level() instanceof ServerLevel serverLevel && !this.isRemoved() && !this.exploded) {
            detonate(serverLevel, null);
            return true;
        }
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.spinSeed = tag.getFloat("SpinSeed");
        // 古いセーブには Size が無い(= 通常の大きさ)。
        this.entityData.set(DATA_SIZE, tag.contains("Size") ? Math.max(0.05F, tag.getFloat("Size")) : 1.0F);
        this.plantsFern = tag.getBoolean("PlantsFern");
        this.mortarShell = tag.getBoolean("Mortar");
        this.statsId = tag.contains("StatsId") ? tag.getString("StatsId") : null;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("SpinSeed", this.spinSeed);
        tag.putFloat("Size", size());
        if (this.plantsFern) {
            tag.putBoolean("PlantsFern", true);
        }
        if (this.mortarShell) {
            tag.putBoolean("Mortar", true);
        }
        if (this.statsId != null) {
            tag.putString("StatsId", this.statsId);
        }
    }

    /** 上空から落ちてくるので、遠くからでも見えてほしい。 */
    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 192.0D * 192.0D;
    }

    @Override
    public boolean isPushable() {
        return false;
    }
}
