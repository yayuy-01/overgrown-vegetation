package com.yayuy.overgrownvegetation.entity;

import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.raid.RaidAreaCorruption;
import com.yayuy.overgrownvegetation.raid.RaidCorruptionNode;
import com.yayuy.overgrownvegetation.raid.RaidData;
import com.yayuy.overgrownvegetation.raid.RaidRootGrowth;
import com.yayuy.overgrownvegetation.raid.RaidWaveSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * レイドイベントの中核「レイドビーコン」(登録 id は歴史的経緯で {@code overgrown_nexus} のまま。
 * 表示名は 2026-09-17 に「過繫茂の苗床」から改名)。<b>本体は一切回転しない</b>高体力のボス
 * (手作りモデル {@code overgrown_nexus.geo.json} — 中央の1本の巨大な蔦 {@code vine} が
 * 常にうねりながら立ち上がり、先端に胞子の花。根元近くから3本の低く這う蔦
 * {@code vine2}・{@code vine3}・{@code vine4} が生えている)。
 *
 * <p>ボスバーを表示し、倒されるとレイドを終了する({@link RaidData} と連携)。
 *
 * <h2>汚染の広げ方は2段構え</h2>
 * <ol>
 *     <li><b>根の伸長</b>({@link RaidRootGrowth})—— ビーコンから水平距離
 *     {@code raidRootMaxDistance}(既定100)まで根を這わせ、沿道に汚染の起点
 *     ({@link RaidCorruptionNode})を落としていく。根は限界に近づくほど細くなる。</li>
 *     <li><b>面汚染</b>({@link RaidAreaCorruption})—— 全ての根が限界距離で止まったら
 *     (残りが全部、読み込まれていないチャンクの手前で待っているときも。{@link RaidRootGrowth#isFinished()})
 *     そちらへ切り替え、ビーコンを中心とした
 *     半径 {@code raidAreaCorruptionRadius} の球を、近い順に(地表も地下も)塗りつぶす。
 *     所要時間は {@code raidAreaCorruptionSweepMinutes}(既定20分)で、
 *     <b>遠い層ほど同時に処理する量が増える</b>。</li>
 * </ol>
 * 敵対 Mob の増援({@link RaidWaveSpawner})はどちらの段階でも並行して続く(ただし近くに狙えるプレイヤーが
 * 居る間だけ。居ない間はウェーブの時計が止まる。2026-09-25)。
 *
 * <p>このビーコン自身は<b>グループ4(拡大種)</b>に属する({@code group_4} タグ)。
 * 入れていなかった頃は、グループ4 の索敵が「どのグループにも属さない = 野生の Mob」として
 * レイドの中核を敵と見なして殴りに来ていた。
 *
 * <h2>攻撃行動は持たない(2026-09-06〜)</h2>
 * <p>苗床自身に自衛能力は持たせず、自衛は専用の別 Mob に任せる方針になったため、
 * {@code registerGoals()} は薙ぎ払い({@link NexusSweepGoal}）・衝撃波({@link NexusSonicBoomGoal}）・
 * 胞子ばら撒き({@link NexusSporeBurstGoal}）のいずれも登録しない。各 goal クラス自体、
 * 対応する同期データ(状態・進捗)、{@code OvergrownNexusModel} 側の対応するアニメーション
 * (溜め/振り/膨張などの見た目)は削除せず残してある(何も goal が状態を変えないため、
 * 常に IDLE のまま何も起きない)。vine4 を対象の方へ向ける仕組み({@code houkou} ボーンの
 * FK 計算・総当たり探索、{@code raid-system.md} に詳細な経緯を記録)は、方向が安定するまで
 * 何度も原因究明が必要だった箇所 — 同種の「向いて攻撃する触手」を新しい Mob で作る場合は
 * そちらのメモを先に参照すること。
 *
 * <p>本体は回転させない設計のため、常時のうねり(idle sway)は対象ボーン(蔦)自体を
 * {@link com.yayuy.overgrownvegetation.client.OvergrownNexusModel#setCustomAnimations} で
 * 直接曲げる。同期するのは各系統の状態(int)と進捗(float 0〜1)だけで、
 * 実際の曲げ方はモデル側が受け持つ。
 */
public class OvergrownNexusEntity extends PathfinderMob implements GeoEntity {

    /** vine4(衝撃波)の状態。 */
    public static final int BOOM_IDLE = 0;
    public static final int BOOM_CHARGE = 1;
    public static final int BOOM_RECOIL = 2;
    /** 反動の後、狙い(ヨー・ピッチ)をなめらかにレストポーズへ戻している最中。 */
    public static final int BOOM_RETURN = 3;

    /** vine2・vine3(薙ぎ払い)の状態。 */
    public static final int SWEEP_IDLE = 0;
    public static final int SWEEP_WINDUP = 1;
    public static final int SWEEP_SWING = 2;
    /** 振り抜いた後、なめらかにレストポーズへ戻している最中。 */
    public static final int SWEEP_RETURN = 3;

    /** vine(胞子ばら撒き、housinou の膨張/収縮)の状態。 */
    public static final int SPORE_IDLE = 0;
    public static final int SPORE_INFLATE = 1;
    public static final int SPORE_DEFLATE = 2;

    /**
     * {@code houkou} ボーンの位置は、{@code vine4} ボーン自体の回転(=狙い、
     * {@link #getBoomAimYaw()}/{@link #getBoomAimPitch()})によって刻々と変わるため、
     * 「レストポーズを一度だけ計算した固定値」では、体が対象へ曲がってもマズル(発射位置)だけが
     * レストポーズの位置(見た目と無関係などこか)に取り残されてしまっていた
     * (「敵と真逆の位置から発射される」不具合の正体)。そのため、houkou のごく手前
     * (vine4 の回転を適用する<b>直前</b>)までのチェーンだけを事前に計算して定数化し
     * ({@link #HOUKOU_PRE_VINE4}）、vine4 自身の回転は実行時に(現在の狙いを反映した値で)
     * 毎回計算し直す({@link #computeHoukouOffset}）。
     *
     * <p>{@code houkou}→{@code vine-20}→{@code vine-19}→{@code vine-18}→{@code vine-17}→
     * {@code vine-16} を静的 pivot まわりに静的 rotation で(葉→根の順に)適用して求めた、
     * モデル単位(16単位=1ブロック)での中間点。geo.json のこの部分は苗床が生きている間
     * 変化しないので定数として扱ってよい。
     *
     * <h2>Y軸だけでは狙えない(重要)</h2>
     * <p>{@code vine4} 自身のレストポーズは X・Y・Z 全軸にまたがる複合回転
     * ({@link #VINE4_REST_ROT_X_DEG} 等)で据え付けられているため、Y 軸(ヨー)だけを
     * 「目標との差分だけ足す」やり方では、実際に houkou が向く方角は非線形かつ非常に狭い
     * 範囲(検証時で見た目 40° 程度)しか動かせず、その範囲を超える差分を入れるとかえって
     * 目標から離れていく(酷いと反対側を向く)ことが FK 計算による検証で判明した
     * ({@code tools} 配下で臨時に書いた FkCalc2〜5.java 参照、raid-system.md にも記録)。
     * Y と X(ピッチ)を<b>組み合わせて</b>総当たりすると実際にはほぼ全方位をカバーできることも
     * 同じ検証で確認済みなので、{@link #solveBoomAim} は単純な引き算ではなく
     * 「(ヨー, ピッチ)の格子を総当たりして、実際に一番近づく組を選ぶ」方式にしてある。
     */
    private static final Vec3 HOUKOU_PRE_VINE4 = new Vec3(25.93269D, 72.06853D, -7.70136D);
    /** {@code vine4} 自身の pivot(モデル単位)。 */
    private static final Vec3 VINE4_PIVOT = new Vec3(4.0D, -1.0D, -8.0D);
    /** {@code vine4} 自身のレストポーズでの回転(度、geo.json 準拠)。 */
    private static final double VINE4_REST_ROT_X_DEG = 135.68718D;
    private static final double VINE4_REST_ROT_Y_DEG = -72.39303D;
    private static final double VINE4_REST_ROT_Z_DEG = -134.31282D;

    /**
     * {@code hana} ボーン(vine 本体の頂上、つぼみ/花)の、{@link #computeHoukouOffset} と同じ
     * GeckoLib 実際の変換(X軸回転は符号反転、Z はそのまま — 詳細は {@link #computeHoukouOffset}
     * のコメント参照)で葉→根の順に静的 pivot まわりの静的 rotation を適用し、16単位=1ブロックで
     * 割って求めたレストポーズでの足元からのオフセット。{@code hana} は回転しないので、こちらは
     * houkou と違い常に一定の固定値でよい。ほぼ真上(x≈0)。
     *
     * <p>以前はここも X 軸の符号を反転せずに計算しており、Z 成分の符号が逆
     * (-0.5064 ではなく実際は +0.4064)になっていた—houkou の符号バグを見つけた際に
     * 合わせて計算し直した値。
     */
    private static final Vec3 HANA_LOCAL_OFFSET = new Vec3(0.0D, 6.4982D, 0.4064D);

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");

    private static final EntityDataAccessor<Integer> DATA_BOOM_STATE =
            SynchedEntityData.defineId(OvergrownNexusEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_BOOM_PROGRESS =
            SynchedEntityData.defineId(OvergrownNexusEntity.class, EntityDataSerializers.FLOAT);
    /**
     * {@code vine4}(houkou)を対象へ向けるのに必要な追加ヨー角・ピッチ角(度、{@link #solveBoomAim}
     * が総当たりで探した組)。{@link NexusSonicBoomGoal} が溜め中、対象の位置から毎 tick 計算して
     * 同期する。モデル側({@code OvergrownNexusModel})はこれを常時うねりの角度上限を無視した
     * 絶対値として {@code vine4} ボーンへそのまま適用する — 「体ごと曲げてプレイヤーに向ける」
     * 動作は小さな常時うねりとは別枠。
     */
    private static final EntityDataAccessor<Float> DATA_BOOM_AIM_YAW =
            SynchedEntityData.defineId(OvergrownNexusEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_BOOM_AIM_PITCH =
            SynchedEntityData.defineId(OvergrownNexusEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_SWEEP_STATE =
            SynchedEntityData.defineId(OvergrownNexusEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_SWEEP_PROGRESS =
            SynchedEntityData.defineId(OvergrownNexusEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_SPORE_STATE =
            SynchedEntityData.defineId(OvergrownNexusEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_SPORE_PROGRESS =
            SynchedEntityData.defineId(OvergrownNexusEntity.class, EntityDataSerializers.FLOAT);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    private final ServerBossEvent bossEvent = new ServerBossEvent(
            Component.translatable("event.overgrown_vegetation.raid.boss"),
            BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);

    /** レイドビーコンから時間経過で伸びていく根。{@code raidRootMaxDistance} まで伸びると止まる。 */
    private final RaidRootGrowth roots = new RaidRootGrowth();

    /**
     * 根が伸びきったあとに引き継ぐ「面汚染」。ビーコンを中心とした球の中を、近い順に
     * {@code raidAreaCorruptionSweepMinutes} かけて塗りつぶしていく(遠い層ほど速い)。
     */
    private final RaidAreaCorruption areaCorruption = new RaidAreaCorruption();

    /** 根に沿って落とされた汚染の起点。round-robin で少しずつ tick する。 */
    private final List<RaidCorruptionNode> corruptionNodes = new ArrayList<>();

    /** {@link #corruptionNodes} を tick する際の巡回カーソル。 */
    private int nodeCursor;

    /** このレイドが始まったゲーム内時刻(tick)。 */
    private long raidStartGameTime;

    /**
     * ウェーブの時計(tick)。近くに狙えるプレイヤーが居る間だけ 1 tick ずつ進み、{@code raidWaveIntervalTicks} に
     * 達したらウェーブを出して 0 に戻る(2026-09-25、ユーザー承認「レイドの見直し」、報告 410)。
     * 以前はゲーム内時刻(最後のウェーブの時刻)で数えていたので、誰も居ない強制ロードの中でも 1 分ごとに
     * ウェーブが湧き続けていた。居なかった間のぶんは取り戻さない(戻ってきたら、離れたときの続きから数える)。
     */
    private int waveClock;

    /**
     * 直近の見回り({@link #WAVE_PRESENCE_CHECK_TICKS} ごと)で、ウェーブの時計を進めてよいプレイヤーが近くに居たか
     * ({@link RaidWaveSpawner#hasWavePlayerNearby})。保存しない —— 読み込み直後は「居ない」扱いで、最長 1 秒で見直す。
     */
    private boolean wavePlayerNearby;

    /** {@link #wavePlayerNearby} を見直す間隔(tick)。1 秒ごとで足りる(ウェーブの間隔は既定 1 分)。 */
    private static final int WAVE_PRESENCE_CHECK_TICKS = 20;

    public OvergrownNexusEntity(EntityType<? extends OvergrownNexusEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.xpReward = MobStats.xpReward("overgrown_nexus", 0);
        this.bossEvent.setVisible(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        // scale はモデル実寸(全高9ブロック超)をさらに 3 倍にする値。当たり判定にも素直に効く
        // ({@link #getDefaultDimensions} の相殺トリックは廃止済み)。mobs.json の "scale" で編集可。
        return MobStats.apply(Mob.createMobAttributes(), "overgrown_nexus");
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_BOOM_STATE, BOOM_IDLE);
        builder.define(DATA_BOOM_PROGRESS, 0.0F);
        builder.define(DATA_BOOM_AIM_YAW, 0.0F);
        builder.define(DATA_BOOM_AIM_PITCH, 0.0F);
        builder.define(DATA_SWEEP_STATE, SWEEP_IDLE);
        builder.define(DATA_SWEEP_PROGRESS, 0.0F);
        builder.define(DATA_SPORE_STATE, SPORE_IDLE);
        builder.define(DATA_SPORE_PROGRESS, 0.0F);
    }

    @Override
    protected void registerGoals() {
        // 苗床自身に自衛能力は持たせない方針になったため、薙ぎ払い・衝撃波・胞子ばら撒きの
        // 3行動(NexusSweepGoal/NexusSonicBoomGoal/NexusSporeBurstGoal)は登録しない
        // (自衛は別の専用 Mob に任せる)。各 goal クラス自体・対応する
        // DATA_BOOM_*/DATA_SWEEP_*/DATA_SPORE_* の同期フィールドと
        // OvergrownNexusModel#applyBoom/applySweep/applySpore は削除せず残してある —
        // 常時ゆらぐ idle sway(applyChainSway)は登録される goal が無くても引き続き動く
        // (setCustomAnimations から毎フレーム直接呼ばれているため)。
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 実際の動き(常時のうねり・薙ぎ払い・衝撃波の溜め/反動)はすべて
        // OvergrownNexusModel#setCustomAnimations がボーンへ直接書き込む。
        // ここでは GeckoLib が要求する最低限のコントローラだけ用意する。
        controllers.add(new AnimationController<>(this, "idle", 0, state -> state.setAndContinue(IDLE_ANIM)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    public long getRaidStartGameTime() {
        return raidStartGameTime;
    }

    public void setRaidStartGameTime(long gameTime) {
        this.raidStartGameTime = gameTime;
    }

    // --- vine4: 衝撃波の状態 ---

    public int getBoomState() {
        return this.entityData.get(DATA_BOOM_STATE);
    }

    public void setBoomState(int state) {
        this.entityData.set(DATA_BOOM_STATE, state);
    }

    public boolean isBoomBusy() {
        return getBoomState() != BOOM_IDLE;
    }

    public float getBoomProgress() {
        return this.entityData.get(DATA_BOOM_PROGRESS);
    }

    public void setBoomProgress(float progress) {
        this.entityData.set(DATA_BOOM_PROGRESS, progress);
    }

    /**
     * {@code houkou} ボーン(vine4 の先端)の現在のワールド座標。溜め中・反動中は現在の狙い
     * ({@link #getBoomAimYaw()}/{@link #getBoomAimPitch()})を {@code vine4} の回転へ反映した
     * 位置になる — 以前はレストポーズを一度だけ計算した固定値だったため、体が対象へ曲がっても
     * マズル(発射位置)だけがレストポーズの位置(見た目と無関係などこか)に取り残され、
     * 「敵と真逆の位置から発射される」不具合の原因になっていた。
     */
    public Vec3 houkouWorldPosition() {
        double aimYawDeg = isBoomBusy() ? getBoomAimYaw() : 0.0D;
        double aimPitchDeg = isBoomBusy() ? getBoomAimPitch() : 0.0D;
        Vec3 rotated = computeHoukouOffset(aimYawDeg, aimPitchDeg).yRot((float) Math.toRadians(-this.getYRot()));
        return this.position().add(rotated);
    }

    /** {@code hana} ボーン(vine 本体の頂上、つぼみ/花)のおおよそのワールド座標。 */
    public Vec3 hanaWorldPosition() {
        Vec3 rotated = HANA_LOCAL_OFFSET.yRot((float) Math.toRadians(-this.getYRot()));
        return this.position().add(rotated);
    }

    /**
     * {@code vine4} に足す(ヨーへの追加角, ピッチへの追加角)を総当たりで探し、houkou の
     * <b>水平ベアリング(ヨー)</b>が実際に {@code targetWorldPos} の方向を向くようにする
     * ({@link NexusSonicBoomGoal} が溜め中に毎 tick 呼ぶ)。
     *
     * <p>当初は houkou の3次元方向(ヨー+ピッチ)をまとめて対象方向のベクトルに一致させようと
     * したが、実際に全方位について検証すると誤差が最大60°を超える組み合わせが多数あり、
     * 全く実用にならなかった(vine4 のレストポーズが複雑な3軸複合回転のため、3次元方向を
     * 総当たりで一致させようとすると評価関数がひどく多峰的になり、格子探索が正しい解に
     * 収束しない)。一方、<b>水平ベアリングだけ</b>を一致させる(ピッチは「ヨーを合わせるための
     * 自由なつまみ」として好きに動かしてよいことにする)と、全方位について1度未満の誤差で
     * 収束することを確認済み({@code tools} 配下の FkCalc5/8.java 参照、raid-system.md にも記録)。
     * 見た目上も「houkou が対象の方角を向いているかどうか」はほぼ水平ベアリングだけで判断される
     * ため、ピッチの一致を諦めても実用上の見た目には問題ない。
     */
    public float[] solveBoomAim(Vec3 targetWorldPos) {
        Vec3 toTarget = targetWorldPos.subtract(this.position());
        if (toTarget.x * toTarget.x + toTarget.z * toTarget.z < 1.0E-6D) {
            return new float[] {0.0F, 0.0F};
        }
        // ワールド方向 → entity 自身の yRot を打ち消した「モデルのローカル向き」に変換
        // (houkouWorldPosition が使っているのと逆向きの回転)、その水平ベアリングを目標にする。
        Vec3 targetDirLocal = toTarget.normalize().yRot((float) Math.toRadians(this.getYRot()));
        double targetYawDeg = Math.toDegrees(Math.atan2(-targetDirLocal.x, targetDirLocal.z));

        double bestYaw = 0.0D;
        double bestPitch = 0.0D;
        double bestErr = Double.MAX_VALUE;
        for (double dy = -180.0D; dy <= 180.0D; dy += 6.0D) {
            for (double dx = -90.0D; dx <= 90.0D; dx += 6.0D) {
                double err = yawError(targetYawDeg, dy, dx);
                if (err < bestErr) {
                    bestErr = err;
                    bestYaw = dy;
                    bestPitch = dx;
                }
            }
        }
        double coarseYaw = bestYaw;
        double coarsePitch = bestPitch;
        for (double dy = coarseYaw - 6.0D; dy <= coarseYaw + 6.0D; dy += 1.0D) {
            for (double dx = coarsePitch - 6.0D; dx <= coarsePitch + 6.0D; dx += 1.0D) {
                double err = yawError(targetYawDeg, dy, dx);
                if (err < bestErr) {
                    bestErr = err;
                    bestYaw = dy;
                    bestPitch = dx;
                }
            }
        }
        return new float[] {(float) bestYaw, (float) bestPitch};
    }

    /** houkou の水平ベアリングと {@code targetYawDeg} との差(絶対値、度)。 */
    private static double yawError(double targetYawDeg, double extraYawDeg, double extraPitchDeg) {
        Vec3 offset = computeHoukouOffset(extraYawDeg, extraPitchDeg);
        double yaw = Math.toDegrees(Math.atan2(-offset.x, offset.z));
        return Math.abs(Mth.wrapDegrees(yaw - targetYawDeg));
    }

    /**
     * {@code vine4} の Y 軸回転に {@code extraYawDeg}、X 軸回転に {@code extraPitchDeg} を足した
     * 状態での houkou の、苗床の足元からのオフセット(ブロック、entity 自身の yRot 適用前=
     * モデルのローカル向き)。{@link #HOUKOU_PRE_VINE4}(vine4 の回転を適用する直前までの
     * 固定チェーン)へ、vine4 自身の pivot まわりの回転を適用する。
     *
     * <h2>この計算式は「手で書き写した式」ではなく GeckoLib が実際に使うクラスをそのまま呼ぶ(重要)</h2>
     * <p>以前は符号・回転順序を手で調べて({@code BakedModelFactory}/{@code RenderUtil} を読んで
     * X・Y反転+X→Y→Z順と判明させて)自前の三角関数で再現していたが、それでもなお実機で
     * 「90度ずれる」といった食い違いが再発した — 手書きの再現には常に写し間違いのリスクが
     * 残るため、根本的な対策として {@code RenderUtil#rotateMatrixAroundBone} が実際に使っている
     * {@link com.mojang.math.Axis}({@code Axis.ZP/YP/XP.rotation(...)})と {@link Quaternionf}
     * をここでも直接使い、"PoseStack へ Z→Y→X の順で mulPose する" のと全く同じ合成
     * ({@code q.mul(...)} は mulPose と同じ「今の回転に右から掛ける」意味を持つ)を行う。
     * こうすることで、符号・順序の解釈違いが原理的に入り込む余地がなくなる。
     */
    private static Vec3 computeHoukouOffset(double extraYawDeg, double extraPitchDeg) {
        float rotZ = (float) Math.toRadians(VINE4_REST_ROT_Z_DEG);
        float rotY = (float) Math.toRadians(-VINE4_REST_ROT_Y_DEG + extraYawDeg);
        float rotX = (float) Math.toRadians(-VINE4_REST_ROT_X_DEG + extraPitchDeg);

        Quaternionf q = new Quaternionf();
        q.mul(Axis.ZP.rotation(rotZ));
        q.mul(Axis.YP.rotation(rotY));
        q.mul(Axis.XP.rotation(rotX));

        Vector3f v = new Vector3f(
                (float) (HOUKOU_PRE_VINE4.x - VINE4_PIVOT.x),
                (float) (HOUKOU_PRE_VINE4.y - VINE4_PIVOT.y),
                (float) (HOUKOU_PRE_VINE4.z - VINE4_PIVOT.z));
        q.transform(v);

        return new Vec3((v.x() + VINE4_PIVOT.x) / 16.0D, (v.y() + VINE4_PIVOT.y) / 16.0D,
                (v.z() + VINE4_PIVOT.z) / 16.0D);
    }

    // --- vine4: houkou を対象へ向ける追加ヨー角・ピッチ角 ---

    public float getBoomAimYaw() {
        return this.entityData.get(DATA_BOOM_AIM_YAW);
    }

    public void setBoomAimYaw(float yawDeg) {
        this.entityData.set(DATA_BOOM_AIM_YAW, yawDeg);
    }

    public float getBoomAimPitch() {
        return this.entityData.get(DATA_BOOM_AIM_PITCH);
    }

    public void setBoomAimPitch(float pitchDeg) {
        this.entityData.set(DATA_BOOM_AIM_PITCH, pitchDeg);
    }

    // --- vine2・vine3: 薙ぎ払いの状態 ---

    public int getSweepState() {
        return this.entityData.get(DATA_SWEEP_STATE);
    }

    public void setSweepState(int state) {
        this.entityData.set(DATA_SWEEP_STATE, state);
    }

    public boolean isSweepBusy() {
        return getSweepState() != SWEEP_IDLE;
    }

    public float getSweepProgress() {
        return this.entityData.get(DATA_SWEEP_PROGRESS);
    }

    public void setSweepProgress(float progress) {
        this.entityData.set(DATA_SWEEP_PROGRESS, progress);
    }

    // --- vine: 胞子ばら撒き(housinou の膨張/収縮)の状態 ---

    public int getSporeState() {
        return this.entityData.get(DATA_SPORE_STATE);
    }

    public void setSporeState(int state) {
        this.entityData.set(DATA_SPORE_STATE, state);
    }

    public boolean isSporeBusy() {
        return getSporeState() != SPORE_IDLE;
    }

    public float getSporeProgress() {
        return this.entityData.get(DATA_SPORE_PROGRESS);
    }

    public void setSporeProgress(float progress) {
        this.entityData.set(DATA_SPORE_PROGRESS, progress);
    }

    /**
     * この苗床が「敵」とみなす対象かどうか(薙ぎ払い・衝撃波・胞子ばら撒き、3系統の攻撃すべてが
     * ターゲット選定にこれを使う、単一の判定基準)。プレイヤー(観戦・クリエイティブ除く)、または
     * グループ1〜10いずれにも属さない Mob(まだ寄生されていない野生の Mob)が対象 — 過繁茂した
     * (MOB名)・汚染シダ・苗床自身は味方として除外する。
     */
    public boolean isHostileTarget(LivingEntity e) {
        if (e == this || !e.isAlive()) {
            return false;
        }
        if (e instanceof Player player) {
            return !player.isSpectator() && !player.isCreative()
                    && !com.yayuy.overgrownvegetation.item.PlantArmor.isHost(player);
        }
        if (e instanceof BlightFernEntity || e instanceof OvergrownNexusEntity) {
            return false;
        }
        return MobGroup.of(e) == null;
    }

    /**
     * 当たり判定を、見た目の全高(9ブロック超)よりだいぶ小さい「幹」相当の大きさにする
     * ({@link BlightFernEntity#getDefaultDimensions} と同じ考え方)。この基準サイズには
     * バニラが {@code Attributes.SCALE} を自動で掛けるため、以前はここで {@code 1/scale} を
     * 掛けて打ち消し「見た目だけ拡大・当たり判定は常に固定」にしていたが、ユーザーが
     * 「苗床のサイズを3倍に」と要望したため相殺トリックを廃止 — {@code SCALE} を上げれば
     * 見た目・当たり判定ともに素直に大きくなる。
     */
    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        return EntityDimensions.scalable(3.0F, 6.5F);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            spawnAmbientParticles();
            return;
        }
        if (this.level() instanceof ServerLevel serverLevel && !this.isDeadOrDying()) {
            serverTick(serverLevel);
        }
    }

    /**
     * 足場の上で止まっている間は、動きの計算(当たり判定。体が大きく、約 9×19.5×9 ブロックの箱でブロックと Mob を調べる)を
     * {@value #SETTLED_TRAVEL_INTERVAL} tick に 1 回にする(2026-10-10、ユーザー了承。0.1.9 の前の軽量化 —— ユーザーの記録で苗床の時間の 7 割がここだった)。
     * 足元を掘られて落ち始めるのが最大 1 秒遅れる。押された・水や溶岩の中・宙に浮いている間は毎 tick 計算する。
     */
    private static final int SETTLED_TRAVEL_INTERVAL = 20;

    @Override
    public void travel(Vec3 travelVector) {
        if (!this.level().isClientSide() && this.onGround()
                && this.getDeltaMovement().horizontalDistanceSqr() < 1.0E-7D
                && !this.isInWater() && !this.isInLava()
                && (this.tickCount + this.getId()) % SETTLED_TRAVEL_INTERVAL != 0) {
            return;
        }
        super.travel(travelVector);
    }

    /** サーバー側の毎 tick 処理。 */
    private void serverTick(ServerLevel level) {
        updateBossBar(level);
        growRoots(level);
        tickCorruptionNodes(level);
        // 根が限界距離まで伸びきったら(残りが全部、読み込まれていないチャンクの手前で待っているときも)、
        // 以降は地表と地下の汚染に集中する(ビーコンを中心とした球を、近い順に塗りつぶしていく)。
        if (roots.isFinished()) {
            areaCorruption.tick(level, this.blockPosition());
        }
        maybeSpawnWave(level);
    }

    private void maybeSpawnWave(ServerLevel level) {
        int interval = OvergrownVegetationConfig.COMMON.raidWaveIntervalTicks.get();
        if (interval <= 0) {
            return;
        }
        // 近くに狙えるプレイヤーが居るかは 1 秒に 1 回だけ見る(苗床ごとに位相をずらす)。
        if (Math.floorMod(this.tickCount + this.getId(), WAVE_PRESENCE_CHECK_TICKS) == 0) {
            this.wavePlayerNearby = RaidWaveSpawner.hasWavePlayerNearby(level, this.position());
        }
        if (!this.wavePlayerNearby) {
            return; // 誰も居ない間は時計ごと止める(2026-09-25、報告 410)
        }
        // 出現直後はウェーブなし(時計は 0 から)。最初のウェーブは、プレイヤーが近くに居た時間が interval に達したとき。
        if (++this.waveClock >= interval) {
            this.waveClock = 0;
            RaidWaveSpawner.tryWave(level, this);
        }
    }

    private void updateBossBar(ServerLevel level) {
        this.bossEvent.setProgress(this.getMaxHealth() > 0.0F ? this.getHealth() / this.getMaxHealth() : 0.0F);
        int radius = OvergrownVegetationConfig.COMMON.raidBossBarRadius.get();
        double radiusSq = (double) radius * radius;
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && player.distanceToSqr(this) <= radiusSq) {
                this.bossEvent.addPlayer(player);
            } else {
                this.bossEvent.removePlayer(player);
            }
        }
    }

    private void growRoots(ServerLevel level) {
        if (com.yayuy.overgrownvegetation.corruption.CorruptionSwitch.isOff()) {
            return; // /ov corruption false(2026-10-10 ユーザー指定): 苗床の根も伸ばさない(ウェーブは止めない)
        }
        if (roots.isFullyGrown()) {
            return; // 全ての先端が raidRootMaxDistance まで到達済み。以降は面汚染に任せる。
        }
        // isFinished(面汚染へ切り替え済み)でも、読み込まれていないチャンクの手前で待っている先端が残っていれば
        // 呼び続ける —— プレイヤーが近くに来てその先が読み込まれたら、止まったところから伸び直す(2026-09-25)。
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        int interval = Math.max(1, cfg.raidRootGrowthIntervalTicks.get());
        if (this.tickCount % interval != 0) {
            return;
        }
        List<BlockPos> newNodes = roots.grow(level, this.blockPosition(), this.getRandom());
        int cap = cfg.raidMaxActiveCorruptionNodes.get();
        for (BlockPos pos : newNodes) {
            corruptionNodes.add(new RaidCorruptionNode(pos));
        }
        // 上限を超えたら、完了済みの古い起点から捨てる(汚染したブロックはそのまま残る)。
        while (corruptionNodes.size() > cap) {
            int removeIndex = -1;
            for (int i = 0; i < corruptionNodes.size(); i++) {
                if (corruptionNodes.get(i).isFinished()) {
                    removeIndex = i;
                    break;
                }
            }
            if (removeIndex < 0) {
                break;
            }
            corruptionNodes.remove(removeIndex);
            if (nodeCursor > removeIndex) {
                nodeCursor--;
            }
        }
    }

    private void tickCorruptionNodes(ServerLevel level) {
        if (corruptionNodes.isEmpty()) {
            return;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        int interval = Math.max(1, cfg.raidCorruptionTickInterval.get());
        if (this.tickCount % interval != 0) {
            return;
        }
        int perCycle = Math.min(cfg.raidCorruptionNodesPerCycle.get(), corruptionNodes.size());
        for (int i = 0; i < perCycle; i++) {
            if (nodeCursor >= corruptionNodes.size()) {
                nodeCursor = 0;
            }
            corruptionNodes.get(nodeCursor).tick(level);
            nodeCursor++;
        }
    }

    private void spawnAmbientParticles() {
        if (this.random.nextInt(4) != 0) {
            return;
        }
        double x = this.getX() + (this.random.nextDouble() - 0.5D) * 3.0D;
        double y = this.getY() + this.random.nextDouble() * 7.0D;
        double z = this.getZ() + (this.random.nextDouble() - 0.5D) * 3.0D;
        this.level().addParticle(ParticleTypes.SPORE_BLOSSOM_AIR, x, y, z, 0.0D, -0.01D, 0.0D);
    }

    @Override
    public void die(DamageSource source) {
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.EXPLOSION,
                    this.getX(), this.getY() + 2.0D, this.getZ(), 20, 2.0D, 3.0D, 2.0D, 0.0D);
            serverLevel.playSound(null, this.blockPosition(), SoundEvents.WITHER_DEATH, this.getSoundSource(), 1.0F, 0.7F);
        }
        super.die(source);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (this.level() instanceof ServerLevel serverLevel) {
            this.bossEvent.removeAllPlayers();
            if (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED) {
                RaidData.get(serverLevel).onNexusRemoved(serverLevel, this.getUUID(),
                        reason == RemovalReason.KILLED);
            }
        }
        super.remove(reason);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(net.minecraft.world.entity.Entity entity) {
        // 押し合わない
    }

    @Override
    protected void pushEntities() {
        // 周囲のエンティティを押しのけない
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.MOSS_HIT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.MOSS_BREAK;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putLong("RaidStartGameTime", raidStartGameTime);
        tag.putInt("WaveClock", waveClock);
        tag.putInt("NodeCursor", nodeCursor);
        tag.put("Roots", roots.save());
        tag.put("AreaCorruption", areaCorruption.save());
        ListTag nodeList = new ListTag();
        for (RaidCorruptionNode node : corruptionNodes) {
            nodeList.add(node.save());
        }
        tag.put("CorruptionNodes", nodeList);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        raidStartGameTime = tag.getLong("RaidStartGameTime");
        if (tag.contains("WaveClock")) {
            waveClock = tag.getInt("WaveClock");
        } else if (tag.contains("LastWaveGameTime") && tag.getLong("LastWaveGameTime") >= 0L) {
            // 2026-09-25 より前のセーブ(最後のウェーブのゲーム内時刻)。経過を時計へ引き継ぐ(1 間隔を超えるぶんは切り捨て)。
            long elapsed = this.level().getGameTime() - tag.getLong("LastWaveGameTime");
            int interval = Math.max(0, OvergrownVegetationConfig.COMMON.raidWaveIntervalTicks.get());
            waveClock = (int) Mth.clamp(elapsed, 0L, (long) interval);
        } else {
            waveClock = 0;
        }
        nodeCursor = tag.getInt("NodeCursor");
        if (tag.contains("Roots")) {
            roots.load(tag.getCompound("Roots"));
        }
        if (tag.contains("AreaCorruption")) {
            areaCorruption.load(tag.getCompound("AreaCorruption"));
        }
        corruptionNodes.clear();
        ListTag nodeList = tag.getList("CorruptionNodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < nodeList.size(); i++) {
            corruptionNodes.add(RaidCorruptionNode.load(nodeList.getCompound(i)));
        }
    }
}
