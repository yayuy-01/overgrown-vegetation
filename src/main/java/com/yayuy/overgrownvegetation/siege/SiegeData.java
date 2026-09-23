package com.yayuy.overgrownvegetation.siege;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.BeamShieldBlockEntity;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.DorgaArchelausTitanicaEntity;
import com.yayuy.overgrownvegetation.entity.SiegeBombEntity;
import com.yayuy.overgrownvegetation.network.SiegeBarragePayload;
import com.yayuy.overgrownvegetation.network.SiegeBossCryPayload;
import com.yayuy.overgrownvegetation.network.SiegeBossStatePayload;
import com.yayuy.overgrownvegetation.network.SiegeBossStatePayload.BeamState;
import com.yayuy.overgrownvegetation.network.SiegeBossStatePayload.TentacleVolley;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.raid.RaidChunkLoader;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 超大型レイドボス「ドルガ=アーケロウス・ティタニカ」(攻城戦)の進行状況(ワールド単位)を保持する
 * {@link SavedData}。{@link com.yayuy.overgrownvegetation.raid.RaidData} と同じくオーバーワールドの
 * {@code DimensionDataStorage} に紐付く。
 *
 * <h2>ボスは「エンティティではない」</h2>
 * <p>このボスはプレイヤーからはるか遠く(既定 1025 ブロック)を飛ぶ。その距離では
 * <ul>
 *   <li>チャンクがティックされないのでエンティティは動かない(強制ロードは現実的でない — 周回する
 *       ボスに追従して数千チャンクを次々ロードすることになる)、</li>
 *   <li>仮に動かしても {@code ChunkMap} のトラッキング距離(サーバーの view-distance が上限)を
 *       超えるためクライアントへ一切送られない</li>
 * </ul>
 * ので、ボスの実体はこの SavedData が持つ軌道パラメータと体力だけにしてある。位置は
 * {@link SiegeBossMotion} の式からサーバー・クライアント双方が同じように計算する。
 * プレイヤーが近づいたときだけ {@link DorgaArchelausTitanicaEntity} を湧かせて実体に切り替える。
 *
 * <h2>攻撃</h2>
 * <ul>
 *   <li><b>花のビーム(遠距離砲撃)</b> — {@code siegeBeamMinRange} より遠くに居るプレイヤーの足元へ、
 *       一定間隔で巨大なビームを撃ち込む。迫撃砲のように「溜め(予告)→照射→復帰」の段階を踏み、
 *       クライアントは段階と着弾点を受け取って予告と光線を描く。首はその方向へ向く。</li>
 *   <li><b>触手ビーム(近距離)</b> — 実エンティティが居る(= プレイヤーが近い)あいだ、腹側の触手から
 *       近くのプレイヤーへ細いビームを撃つ。</li>
 *   <li><b>爆撃(近距離)</b> — 触手の先端から爆弾を落とす({@link SiegeBombEntity}、地形破壊は既定ゼロ)。</li>
 * </ul>
 * ボスバーは距離制限がないパケットなので、ディメンション内の全プレイヤーにそのまま出せる。
 */
public final class SiegeData extends SavedData {

    private static final String ID = "overgrown_vegetation_siege";

    public static final SavedData.Factory<SiegeData> FACTORY =
            new SavedData.Factory<>(SiegeData::new, (tag, registries) -> load(tag), null);

    /** ビームの段階。 */
    public static final int BEAM_IDLE = 0;
    public static final int BEAM_CHARGE = 1;
    public static final int BEAM_FIRE = 2;
    public static final int BEAM_RECOVER = 3;

    /**
     * 実エンティティを消すのは {@code siegeCloseRange × この倍率} まで離れてから(ヒステリシス)。
     * 境界ちょうどで湧く/消えるを繰り返さないようにするため。
     */
    private static final double ENTITY_RELEASE_FACTOR = 1.35D;

    /** 遠距離ビームの標的探しを何 tick おきに行うか。 */
    private static final int TARGET_SEARCH_INTERVAL = 20;

    /**
     * 砲撃への反撃({@link #onShelled})で「撃ってきた者」を探す距離(ブロック)。
     * 砲弾が出てきた場所 = 砲台の砲口なので、そこに乗っている射手は数ブロック以内に居る。
     * 少し広めに取ってあるのは、発射直後に砲台から降りて動いたぶんを拾うため。
     */
    private static final double GUNNER_SEARCH_RANGE = 32.0D;

    /** 実エンティティのために胴の中心の周りを強制ロードする半径(チャンク)。1 = 3×3。 */
    private static final int ANCHOR_RADIUS_CHUNKS = 1;

    /**
     * 体軸のうち当たり判定が存在する範囲(モデル単位、中心からの片側)。
     * 頭(花)が −216 あたり、尾の先が +216 あたり({@link TitanicaKinematics#BODY_PARTS})。
     */
    private static final double BODY_HALF_UNITS = 216.0D;

    /** 花のビームの毎 tick ダメージで生物を探す範囲: 光線の終端(着弾点)側からこの長さぶん。 */
    private static final double BEAM_DAMAGE_SPAN = 320.0D;

    /**
     * 乱射の小ビームで生物を探す範囲: 線の<b>終端(= 地面に当たった所)</b>からこの長さぶん。
     * 1 発ずつが 3 tick に 1 回飛ぶので、触手の先端(上空数百ブロック)までまるごと検索を掛けるのは
     * 無駄が大きい。地表から 256 ブロックぶん見ておけば、地上に居る者も飛んでいる者も入る。
     */
    private static final double BEAM_DAMAGE_REACH = 64.0D;

    /**
     * 近距離のビーム(触手ビーム・小ビームの乱射)で、狙点から<b>手前</b>へ探す長さ。
     * 相手が居るのは狙点のまわりだけなので、ここを広げても当たる相手は増えないのに
     * エンティティ検索が触るセクションだけが増える。近距離の乱射は 3 tick に 1 発
     * = 毎秒 7 回近く走るので、ここの広さがそのまま負荷になる(2026-09-13 に 320 → 96 へ)。
     */
    private static final double NEAR_BEAM_DAMAGE_SPAN = 96.0D;

    /** 花のビームの溜め中、追従した狙点をクライアントへ送り直す間隔(tick)。 */
    private static final int BEAM_TRACK_SYNC_INTERVAL = 5;

    /**
     * 地形で切るために {@link #beamEnd} が {@code level.clip} を掛ける長さ(狙点から先、ブロック)。
     * ここを超えて伸ばす必要は無い —— 狙点の周りは地面が近く、そこで当たらなければ空中なので、
     * それ以上探しても<b>未ロードのチャンクを同期ロードさせるだけ</b>になる。
     */
    private static final double TERRAIN_CLIP_RANGE = 96.0D;

    // --- 進行状態 ---
    private boolean active;
    private double centerX;
    private double centerY;
    private double centerZ;
    private double radius;
    private double altitude;
    private double speed;
    private long startGameTime;
    private float health;
    private float maxHealth;
    /** 近距離用の実エンティティ({@link DorgaArchelausTitanicaEntity})。存在しないときは null。 */
    @Nullable
    private UUID entityId;
    /** 直近で爆弾を落としたゲーム内時刻(tick)。 */
    private long lastBombGameTime = Long.MIN_VALUE;
    /**
     * 実エンティティのために強制ロードしているチャンク({@link com.yayuy.overgrownvegetation.raid.RaidChunkLoader}
     * が新たに固定した分だけ)。ボスは全長 700 ブロック超なので、プレイヤーが頭やヒレの下に居ても
     * 胴の中心(= エンティティの位置)のチャンクは遠くて読み込まれていない。エンティティはその
     * チャンクが無いと存在できないため、近距離モードのあいだは中心の周り 3×3 を固定しておく。
     * 中心が別のチャンクへ移ったら固定し直す。再起動をまたいでも解除できるよう永続化する。
     */
    private long[] anchorChunks = new long[0];
    /** {@link #anchorChunks} を固定したときの中心チャンク({@code ChunkPos.toLong})。 */
    private long anchorChunkPos = Long.MIN_VALUE;

    // --- 花のビーム(遠距離砲撃) ---
    private int beamPhase = BEAM_IDLE;
    private long beamPhaseStart;
    private double beamTargetX;
    private double beamTargetY;
    private double beamTargetZ;
    private long lastBeamEndGameTime = Long.MIN_VALUE;
    /**
     * 溜めのあいだ<b>追いかけている</b>プレイヤー。撃ち始めた瞬間に {@code null} にして狙点を固定する
     * (ユーザー指示 2026-09-13: 「待機中は追従、撃っている最中は固定」)。
     * 相手が落ちた・死んだ・観戦になったときもここで手放し、そのときの場所へ撃ち込む。
     */
    @Nullable
    private UUID beamTargetPlayer;
    /**
     * 狙点をわざとずらす量({@code siebeBeamScatter} の円のどこか)。追従してもこのずれは変わらないので、
     * 「狙いが甘い」という性格が追いかけているあいだも保たれる。
     */
    private double beamScatterX;
    private double beamScatterZ;

    // --- 首 ---
    /**
     * 首の現在の向き(胴基準のヨー/ピッチ差分・度)。毎 tick 目標角へなめらかに寄る
     * ({@link #tickNeck})。ビーム中は標的へ速く、終わったら正面へ<b>ゆっくり</b>戻る。
     */
    private float neckYaw;
    private float neckPitch;

    // --- 触手ビーム(近距離) ---
    private int tentaclePhase = BEAM_IDLE;
    private long tentaclePhaseStart;
    /**
     * 1 斉射ぶんの「どの触手から撃つか」。長さは {@code siegeTentacleBeamCount}(既定 8)で、
     * <b>同時に</b>撃つ({@link #pickVictims} が選んだ<b>別々の相手</b>へ 1 本ずつ)。
     *
     * <p>保存はしない — 溜め 10 tick + 照射 12 tick の一瞬の状態で、
     * ワールドを読み直したときは撃っていない状態から始めればよい。
     */
    private int[] tentacleIndices = new int[0];
    /**
     * {@link #tentacleIndices} と対の<b>終点</b>(x, y, z を 3 つずつ並べた配列)。
     * 撃つ直前に {@link #beamEnd} で伸ばし切った点に差し替わり、クライアントはこれを線の先として描く。
     */
    private double[] tentacleTargets = new double[0];
    /**
     * {@link #tentacleIndices} と対の<b>狙点</b>(相手の目の位置)。当たり判定を探す窓の中心に使う
     * ({@link #damageWindow})。終点は地形しだいで狙点の 512 ブロック先まで伸びうるので、
     * こちらを別に持っておかないと窓が相手から外れてしまう。同期はしない(サーバーだけで使う)。
     */
    private double[] tentacleAims = new double[0];
    /** 斉射のうち、もう撃った本数({@link #tentacleIndices} の先頭から順に撃つ)。 */
    private int tentacleFired;
    private long lastTentacleEndGameTime = Long.MIN_VALUE;

    // --- 撃破後の落下 ---
    /**
     * 体力が尽きて<b>落ちている最中</b>か。{@link SiegeDeathFall} が位置と姿勢を決め、
     * 着地して少し置いてから {@link #stop} で攻城戦そのものが終わる。
     * このあいだ攻撃は一切しないし、これ以上ダメージも受けない。
     */
    private boolean dying;
    /** 撃破された瞬間の経過 tick({@link #elapsed})。落下の式の基準。 */
    private double deathAt;
    /** 落ちて着地する Y。撃破した瞬間にサーバーが 1 度だけ求め、クライアントへも送る。 */
    private double deathGroundY;
    /** 着地の演出(爆発と地響き)をもう出したか。 */
    private boolean deathImpacted;

    // --- 胞子爆弾の曲射 ---
    /**
     * 次に胞子爆弾を斉射する予定のゲーム内時刻(tick)。撃つたびに
     * {@link #randomDelay 設定値の 0.55〜1.45 倍}を足し直すので、間隔は規則的にならない
     * (メトロノームのように正確に降ってくると、間で動けばいいだけになってしまう)。
     */
    private long nextMortarGameTime = Long.MIN_VALUE;

    // --- 砲撃への反撃 ---
    /**
     * 反撃の斉射のうち、まだ撃っていない回数。砲弾が命中した時点で
     * {@code siegeRetaliationShots} が入り({@link #onShelled})、1 斉射ごとに減る。
     * 0 のあいだは反撃していない。
     */
    private int retaliationShotsLeft;
    /** 反撃の次の 1 斉射を撃つゲーム内時刻(tick)。 */
    private long nextRetaliationGameTime = Long.MIN_VALUE;
    /**
     * 斉射を撃ち終えたあと、次の反撃を受け付けられるようになるゲーム内時刻(tick)。
     * これが無いと砲弾 1 発ごとに反撃が走り、増援が無限に湧く。
     */
    private long retaliationReadyGameTime = Long.MIN_VALUE;
    /** 反撃の狙点 = 撃ってきた砲弾が出てきた場所(= 砲台)。3 回の斉射は同じ狙点へ撃つ。 */
    private double retaliationAimX;
    private double retaliationAimY;
    private double retaliationAimZ;

    // --- 触手の小ビームの乱射 ---
    /** 乱射中かどうか。 */
    private boolean barrageActive;
    /** 乱射を始めた / 直近の 1 発を撃ったゲーム内時刻(tick)と、次に始める予定の時刻。 */
    private long barrageStart;
    private long lastBarrageShotGameTime = Long.MIN_VALUE;
    private long nextBarrageGameTime = Long.MIN_VALUE;
    /**
     * 乱射の狙点。<b>始めた時点のプレイヤーの位置で固定する</b> —
     * 「プレイヤーがいると思われる方角」を薙いでいるのであって、追尾しているのではない。
     */
    private double barrageTargetX;
    private double barrageTargetY;
    private double barrageTargetZ;

    /** 直近で悲鳴を上げたゲーム内時刻(tick)。 */
    private long lastCryGameTime = Long.MIN_VALUE;
    /**
     * フェーズ昇格による攻城戦を既に始めた最大フェーズ(2026-09-17)。現在フェーズがこれを上回り、かつ
     * そのフェーズの {@code siegeEnabled} が true なら 1 回だけ攻城戦を始める
     * ({@link #onPhaseAdvanced} / {@link #tickPhaseTrigger})。{@link com.yayuy.overgrownvegetation.raid.RaidData}
     * の {@code lastRaidPhase} と同じ考え方。
     */
    private int lastSiegePhase;
    /**
     * 次にフェーズ昇格で攻城戦を始めるときの中心座標のヒント(保存しない)。{@code /siege} は
     * 打った本人の位置を包囲されたい場所として渡す。null ならオンラインのプレイヤーから選ぶ。
     */
    @Nullable
    private transient BlockPos nextCenterHint;

    /**
     * ボスバー。{@link SavedData} には保存しない(サーバー起動ごとに作り直す)。
     * 距離ではなくディメンションで絞る — 1000 ブロック先のボスでもバーは見せたいため。
     */
    private final ServerBossEvent bossEvent = new ServerBossEvent(
            Component.translatable("event.overgrown_vegetation.siege.boss"),
            BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);

    private SiegeData() {
        this.bossEvent.setVisible(false);
    }

    public static SiegeData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static SiegeData load(CompoundTag tag) {
        SiegeData data = new SiegeData();
        data.active = tag.getBoolean("active");
        data.lastSiegePhase = tag.getInt("lastSiegePhase");
        data.centerX = tag.getDouble("centerX");
        data.centerY = tag.getDouble("centerY");
        data.centerZ = tag.getDouble("centerZ");
        data.radius = tag.getDouble("radius");
        data.altitude = tag.getDouble("altitude");
        data.speed = tag.getDouble("speed");
        data.startGameTime = tag.getLong("startGameTime");
        data.health = tag.getFloat("health");
        data.maxHealth = tag.getFloat("maxHealth");
        data.entityId = tag.hasUUID("entityId") ? tag.getUUID("entityId") : null;
        data.lastBombGameTime = tag.contains("lastBombGameTime") ? tag.getLong("lastBombGameTime") : Long.MIN_VALUE;
        data.anchorChunks = tag.contains("anchorChunks") ? tag.getLongArray("anchorChunks") : new long[0];
        data.anchorChunkPos = tag.contains("anchorChunkPos") ? tag.getLong("anchorChunkPos") : Long.MIN_VALUE;

        data.beamPhase = tag.getInt("beamPhase");
        data.beamPhaseStart = tag.getLong("beamPhaseStart");
        data.neckYaw = tag.getFloat("neckYaw");
        data.neckPitch = tag.getFloat("neckPitch");
        data.beamTargetX = tag.getDouble("beamTargetX");
        data.beamTargetY = tag.getDouble("beamTargetY");
        data.beamTargetZ = tag.getDouble("beamTargetZ");
        data.beamTargetPlayer = tag.hasUUID("beamTargetPlayer") ? tag.getUUID("beamTargetPlayer") : null;
        data.beamScatterX = tag.getDouble("beamScatterX");
        data.beamScatterZ = tag.getDouble("beamScatterZ");
        data.lastBeamEndGameTime = tag.contains("lastBeamEndGameTime") ? tag.getLong("lastBeamEndGameTime") : Long.MIN_VALUE;

        // 触手ビームは一瞬の状態なので保存しない(読み直したら撃っていない状態から)。
        data.lastTentacleEndGameTime = tag.contains("lastTentacleEndGameTime") ? tag.getLong("lastTentacleEndGameTime") : Long.MIN_VALUE;

        data.dying = tag.getBoolean("dying");
        data.deathAt = tag.getDouble("deathAt");
        data.deathGroundY = tag.getDouble("deathGroundY");
        data.deathImpacted = tag.getBoolean("deathImpacted");

        data.nextMortarGameTime = tag.contains("nextMortarGameTime") ? tag.getLong("nextMortarGameTime") : Long.MIN_VALUE;

        data.retaliationShotsLeft = tag.getInt("retaliationShotsLeft");
        data.nextRetaliationGameTime = tag.contains("nextRetaliationGameTime")
                ? tag.getLong("nextRetaliationGameTime") : Long.MIN_VALUE;
        data.retaliationReadyGameTime = tag.contains("retaliationReadyGameTime")
                ? tag.getLong("retaliationReadyGameTime") : Long.MIN_VALUE;
        data.retaliationAimX = tag.getDouble("retaliationAimX");
        data.retaliationAimY = tag.getDouble("retaliationAimY");
        data.retaliationAimZ = tag.getDouble("retaliationAimZ");

        data.barrageActive = tag.getBoolean("barrageActive");
        data.barrageStart = tag.getLong("barrageStart");
        data.nextBarrageGameTime = tag.contains("nextBarrageGameTime") ? tag.getLong("nextBarrageGameTime") : Long.MIN_VALUE;
        data.barrageTargetX = tag.getDouble("barrageTargetX");
        data.barrageTargetY = tag.getDouble("barrageTargetY");
        data.barrageTargetZ = tag.getDouble("barrageTargetZ");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("active", active);
        tag.putInt("lastSiegePhase", lastSiegePhase);
        tag.putDouble("centerX", centerX);
        tag.putDouble("centerY", centerY);
        tag.putDouble("centerZ", centerZ);
        tag.putDouble("radius", radius);
        tag.putDouble("altitude", altitude);
        tag.putDouble("speed", speed);
        tag.putLong("startGameTime", startGameTime);
        tag.putFloat("health", health);
        tag.putFloat("maxHealth", maxHealth);
        if (entityId != null) {
            tag.putUUID("entityId", entityId);
        }
        tag.putLong("lastBombGameTime", lastBombGameTime);
        tag.putLongArray("anchorChunks", anchorChunks);
        tag.putLong("anchorChunkPos", anchorChunkPos);

        tag.putInt("beamPhase", beamPhase);
        tag.putLong("beamPhaseStart", beamPhaseStart);
        tag.putFloat("neckYaw", neckYaw);
        tag.putFloat("neckPitch", neckPitch);
        tag.putDouble("beamTargetX", beamTargetX);
        tag.putDouble("beamTargetY", beamTargetY);
        tag.putDouble("beamTargetZ", beamTargetZ);
        if (beamTargetPlayer != null) {
            tag.putUUID("beamTargetPlayer", beamTargetPlayer);
        }
        tag.putDouble("beamScatterX", beamScatterX);
        tag.putDouble("beamScatterZ", beamScatterZ);
        tag.putLong("lastBeamEndGameTime", lastBeamEndGameTime);

        tag.putLong("lastTentacleEndGameTime", lastTentacleEndGameTime);

        tag.putBoolean("dying", dying);
        tag.putDouble("deathAt", deathAt);
        tag.putDouble("deathGroundY", deathGroundY);
        tag.putBoolean("deathImpacted", deathImpacted);

        tag.putLong("nextMortarGameTime", nextMortarGameTime);

        tag.putInt("retaliationShotsLeft", retaliationShotsLeft);
        tag.putLong("nextRetaliationGameTime", nextRetaliationGameTime);
        tag.putLong("retaliationReadyGameTime", retaliationReadyGameTime);
        tag.putDouble("retaliationAimX", retaliationAimX);
        tag.putDouble("retaliationAimY", retaliationAimY);
        tag.putDouble("retaliationAimZ", retaliationAimZ);

        tag.putBoolean("barrageActive", barrageActive);
        tag.putLong("barrageStart", barrageStart);
        tag.putLong("nextBarrageGameTime", nextBarrageGameTime);
        tag.putDouble("barrageTargetX", barrageTargetX);
        tag.putDouble("barrageTargetY", barrageTargetY);
        tag.putDouble("barrageTargetZ", barrageTargetZ);
        return tag;
    }

    // ------------------------------------------------------------------ 参照

    public boolean isActive() {
        return active;
    }

    public SiegeBossMotion motion() {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        // 寄ってくる速さと下限は config から毎回読む(開始時に焼き込まないので、途中で変えても効く)。
        // config は「何周ぶんで寄り切るか」で持っていて、式が使う 1 tick あたりの縮み幅はここで逆算する。
        double minRadius = cfg.siegeMinRadius.get();
        double approach = SiegeBossMotion.approachFor(radius, minRadius, speed, cfg.siegeApproachLaps.get());
        return new SiegeBossMotion(centerX, centerY, centerZ, radius, altitude, speed, approach, minRadius);
    }

    /** 開始からの経過 tick。 */
    public long elapsed(ServerLevel level) {
        return Math.max(0L, level.getServer().overworld().getGameTime() - startGameTime);
    }

    /** 現在のボスの位置(胴の中心線上の点)。 */
    public Vec3 position(ServerLevel level) {
        return positionAt(elapsed(level));
    }

    /**
     * 開始から {@code t} tick 後のボスの位置。<b>周回の式と落下の式のどちらを使うかはここで決まる</b> —
     * 位置を欲しがる側({@code tick}・実エンティティ・当たり判定の箱・悲鳴)は全部ここを通すこと。
     * クライアント側の {@code ClientSiegeBoss.position} がまったく同じ分岐をしている。
     */
    public Vec3 positionAt(double t) {
        SiegeBossMotion motion = motion();
        return dying ? SiegeDeathFall.positionAt(motion, deathAt, deathGroundY, t) : motion.positionAt(t);
    }

    /** 開始から {@code t} tick 後の胴のヨー(落下中は撃たれた瞬間のまま)。 */
    public float yawAt(double t) {
        SiegeBossMotion motion = motion();
        return dying ? SiegeDeathFall.yawAt(motion, deathAt) : motion.yawAt(t);
    }

    /** 開始から {@code t} tick 後の胴のピッチ(落下中は頭から突っ込み、着地したら水平へ戻る)。 */
    public float pitchAt(double t) {
        SiegeBossMotion motion = motion();
        return dying ? SiegeDeathFall.pitchAt(motion, deathAt, deathGroundY, t) : motion.pitchAt(t);
    }

    /** 体力が尽きて落下中か。 */
    public boolean isDying() {
        return dying;
    }

    public float getHealth() {
        return health;
    }

    public float getMaxHealth() {
        return maxHealth;
    }

    public int beamPhase() {
        return beamPhase;
    }

    public Vec3 beamTarget() {
        return new Vec3(beamTargetX, beamTargetY, beamTargetZ);
    }

    public int tentaclePhase() {
        return tentaclePhase;
    }

    // ------------------------------------------------------------------ 開始・終了・体力

    /**
     * 攻城戦を開始する。{@code center} が円の中心 = 「ボス戦が始まったときにプレイヤーがいた位置」で、
     * 以後この中心は動かない(プレイヤーを追いかけない)。
     *
     * @return 開始できたら true(既に進行中なら false)
     */
    /**
     * フェーズが上がった瞬間に {@code PhaseData#advanceToPhase} から呼ぶ(2026-09-17)。
     * そのフェーズの {@code siegeEnabled}({@code phases.json}、既定はフェーズ7以降)が true なら、
     * <b>その場で</b>攻城戦を 1 回始める。
     *
     * <p>ここで始められなかった場合(プレイヤーが居ない等)は {@code lastSiegePhase} を進めないので、
     * {@link #tickPhaseTrigger} が 5 秒ごとに再挑戦する(＝「フェーズ7開始で必ずボス戦」を、
     * 誰かがログインするまで待ってでも守る)。{@code /ov phase set 7} のような
     * 手動変更でも、同じ再挑戦で始まる。
     */
    public static void onPhaseAdvanced(ServerLevel overworld, int newPhase) {
        get(overworld).tryStartForPhase(overworld, newPhase, false);
    }

    /**
     * {@code /siege}(フェーズ7突入の仮トリガー)から呼ぶ、その場で始める版。
     * 「このフェーズでは既に 1 回始めた」という記録({@code lastSiegePhase})を<b>無視する</b>ので、
     * ボスを倒したあと同じフェーズでもう一度試せる(テスト用の入口なので回数を絞らない)。
     *
     * @return 攻城戦を始められたら true
     */
    public static boolean startForPhase(ServerLevel overworld, int phase, BlockPos center) {
        SiegeData data = get(overworld);
        data.setNextCenterHint(center);
        return data.tryStartForPhase(overworld, phase, true);
    }

    /**
     * {@code /siege} が「包囲される場所」として打った本人の位置を渡すためのヒント。
     * 次にフェーズ昇格で攻城戦を始めるときに 1 回だけ使う(保存しない)。
     */
    public void setNextCenterHint(@Nullable BlockPos center) {
        this.nextCenterHint = center;
    }

    /**
     * 現在フェーズが攻城戦の対象({@code siegeEnabled})で、まだそのフェーズで始めていなければ始める。
     *
     * @return 攻城戦を始めた(またはこのフェーズぶんの処理が済んだ)なら true
     */
    private boolean tryStartForPhase(ServerLevel overworld, int phase, boolean force) {
        if (!OvergrownVegetationConfig.COMMON.siegeEnabled.get()) {
            // toml で攻城戦そのものを切ってある。始めても tick が即座に止めるので、始めない。
            return false;
        }
        if ((!force && phase <= lastSiegePhase) || !DesignData.phases().siegeEnabled(phase)) {
            return false;
        }
        if (active) {
            // 既に攻城戦が進行中。二重には始めず、このフェーズぶんは済んだものとして記録する。
            lastSiegePhase = phase;
            setDirty();
            return true;
        }

        BlockPos center = nextCenterHint;
        if (center == null) {
            // 包囲する相手が要る。オンラインのプレイヤーから 1 人選ぶ(居なければ次の判定へ持ち越し)。
            java.util.List<ServerPlayer> players = overworld.players();
            if (players.isEmpty()) {
                return false;
            }
            center = players.get(overworld.random.nextInt(players.size())).blockPosition();
        }
        if (!start(overworld, center)) {
            return false;
        }
        nextCenterHint = null;
        lastSiegePhase = phase;
        setDirty();
        return true;
    }

    /**
     * {@link #tick} から 5 秒ごとに呼ぶ、フェーズ昇格による攻城戦の再挑戦
     * ({@link #onPhaseAdvanced} がプレイヤー不在などで始められなかったぶん)。
     */
    private void tickPhaseTrigger(ServerLevel overworld) {
        if (overworld.getGameTime() % 100L != 0L) {
            return;
        }
        int phase = com.yayuy.overgrownvegetation.phase.PhaseData.get(overworld).getPhase();
        if (phase > lastSiegePhase) {
            tryStartForPhase(overworld, phase, false);
        }
    }

    public boolean start(ServerLevel level, BlockPos center) {
        if (active) {
            return false;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        this.active = true;
        this.centerX = center.getX() + 0.5D;
        this.centerY = center.getY();
        this.centerZ = center.getZ() + 0.5D;
        this.radius = cfg.siegeRadius.get();
        this.altitude = cfg.siegeAltitude.get();
        this.speed = cfg.siegeSpeed.get();
        this.startGameTime = level.getServer().overworld().getGameTime();
        this.maxHealth = cfg.siegeHealth.get().floatValue();
        this.health = this.maxHealth;
        this.entityId = null;
        this.lastBombGameTime = Long.MIN_VALUE;
        this.beamPhase = BEAM_IDLE;
        this.lastBeamEndGameTime = this.startGameTime;   // 開始直後に撃たず、まず 1 間隔ぶん待つ
        this.tentaclePhase = BEAM_IDLE;
        this.lastTentacleEndGameTime = Long.MIN_VALUE;
        // 曲射・乱射も開始直後には来ない(Long.MIN_VALUE にしておくと、次の tick で待ち時間が引かれる)。
        this.nextMortarGameTime = Long.MIN_VALUE;
        // 反撃は前の攻城戦の続きを持ち越さない(撃たれていないのに撃ち返す状態から始まらないように)。
        this.retaliationShotsLeft = 0;
        this.nextRetaliationGameTime = Long.MIN_VALUE;
        this.retaliationReadyGameTime = Long.MIN_VALUE;
        this.barrageActive = false;
        this.nextBarrageGameTime = Long.MIN_VALUE;
        this.lastCryGameTime = Long.MIN_VALUE;
        setDirty();

        this.bossEvent.setVisible(true);
        this.bossEvent.setProgress(1.0F);
        level.getServer().getPlayerList().broadcastSystemMessage(
                Component.translatable("message.overgrown_vegetation.siege.started"), false);
        sync(level);
        OvergrownVegetation.LOGGER.info("攻城戦を開始しました(中心 {}, 半径 {}, 高度 {}, 速さ {} blk/tick)。",
                center, radius, altitude, speed);
        return true;
    }

    /** 攻城戦を終了する。 */
    public void stop(ServerLevel level, boolean killed) {
        if (!active) {
            return;
        }
        if (killed && !dying) {
            // 断末魔。まだ active なうちに(位置の式が使えるうちに)鳴らす。
            // 落下の演出を経てここへ来た場合は beginDeath でもう鳴らしてあるので二重に鳴らさない。
            cry(level, maxHealth, true);
        }
        this.active = false;
        this.dying = false;
        this.deathImpacted = false;
        DorgaArchelausTitanicaEntity entity = currentEntity(level);
        if (entity != null) {
            entity.discard();
        }
        this.entityId = null;
        releaseAnchor(level);
        this.beamPhase = BEAM_IDLE;
        this.tentaclePhase = BEAM_IDLE;
        this.barrageActive = false;
        this.retaliationShotsLeft = 0;
        setDirty();
        this.bossEvent.removeAllPlayers();
        this.bossEvent.setVisible(false);
        if (killed) {
            level.getServer().getPlayerList().broadcastSystemMessage(
                    Component.translatable("message.overgrown_vegetation.siege.ended"), false);
        }
        sync(level);
    }

    /**
     * ボスに {@code amount} のダメージを与える。近距離用の実エンティティが居るときはそちらへ転送し、
     * 居ないときは体力を直接削る。どちらの経路でも体力はこの {@link SiegeData} に集約される。
     */
    public void hurt(ServerLevel level, float amount) {
        if (!active || dying || amount <= 0.0F) {
            return;
        }
        DorgaArchelausTitanicaEntity entity = currentEntity(level);
        if (entity != null) {
            entity.hurt(level.damageSources().generic(), amount);
            return;
        }
        this.health = Math.max(0.0F, this.health - amount);
        setDirty();
        if (this.health <= 0.0F) {
            beginDeath(level);  // すぐには終わらせず、落ちていく演出に入る。
        } else {
            cry(level, amount, false);
        }
    }

    /**
     * ボスに {@code source} のダメージを与える(砲弾など)。{@link #hurt(ServerLevel, float)} と違い、
     * 実エンティティが居ないときも {@link DorgaArchelausTitanicaEntity#hurt} と同じ規則で
     * 防御のしきい値({@code siegeArmor})を差し引く — 防具貫通でないダメージは、しきい値を超えた分だけ通る。
     * 実エンティティが居るときはそちらへ転送し、エンティティ側が同じ規則を適用する。
     */
    public void hurt(ServerLevel level, float amount, DamageSource source) {
        if (!active || dying || amount <= 0.0F) {
            return;
        }
        DorgaArchelausTitanicaEntity entity = currentEntity(level);
        if (entity != null) {
            entity.hurt(source, amount);
            return;
        }
        if (!source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_ARMOR)
                && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            amount -= OvergrownVegetationConfig.COMMON.siegeArmor.get().floatValue();
            if (amount <= 0.0F) {
                return;
            }
        }
        this.health = Math.max(0.0F, this.health - amount);
        setDirty();
        if (this.health <= 0.0F) {
            beginDeath(level);  // すぐには終わらせず、落ちていく演出に入る。
        } else {
            cry(level, amount, false);
        }
    }

    /**
     * 現在の首の向き(胴基準のヨー/ピッチ差分・度)。ビーム中は標的へ向き、終わったら正面へ戻るが、
     * <b>どちらもなめらかに追従する</b>({@link #tickNeck})。戻りは {@code siegeNeckReturnSpeed} で
     * 意図的に遅くしてあるので、撃ち終わった首がゆっくり正面へ帰っていく。
     *
     * <p>クライアント({@code ClientSiegeBoss})も同じ式・同じ既定値で追従させ、値そのものも
     * {@code SiegeBossStatePayload} で送って大きくずれたときだけ合わせる。ここで返す値は
     * 当たり判定パーツ({@link #partBoxes})とビームの発射点に使うので、見た目と当たりが一致する。
     */
    public float[] neckOffsets(ServerLevel level, Vec3 position, float yaw, float pitch) {
        return new float[]{neckYaw, neckPitch};
    }

    public float neckYaw() {
        return neckYaw;
    }

    public float neckPitch() {
        return neckPitch;
    }

    /** 首を目標角へ 1 tick ぶん寄せる。ビーム中は速く、戻りはゆっくり。 */
    private void tickNeck(Vec3 position, float yaw, float pitch) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        float[] desired = beamPhase == BEAM_IDLE
                ? new float[]{0.0F, 0.0F}
                : TitanicaKinematics.neckOffsets(position, yaw, pitch, cfg.siegeScale.get(), beamTarget());
        float rate = (float) (beamPhase == BEAM_IDLE
                ? cfg.siegeNeckReturnSpeed.get().doubleValue()
                : cfg.siegeNeckFollowSpeed.get().doubleValue());
        neckYaw += (desired[0] - neckYaw) * rate;
        neckPitch += (desired[1] - neckPitch) * rate;
        if (Math.abs(neckYaw) < 0.01F) {
            neckYaw = 0.0F;
        }
        if (Math.abs(neckPitch) < 0.01F) {
            neckPitch = 0.0F;
        }
    }

    /**
     * 当たり判定パーツ 28 個の箱(ワールド座標)。実エンティティが居なくても、軌道の式と
     * {@link TitanicaKinematics#partCenters} から同じ箱を再現する — 砲弾({@link ShellTracker})の
     * 命中判定用。攻城戦が進行中でなければ null。
     */
    @Nullable
    public List<AABB> partBoxes(ServerLevel level) {
        if (!active) {
            return null;
        }
        SiegeBossMotion motion = motion();
        double t = elapsed(level);
        Vec3 center = motion.positionAt(t);
        float yaw = motion.yawAt(t);
        float pitch = motion.pitchAt(t);
        double scale = OvergrownVegetationConfig.COMMON.siegeScale.get();
        float[] neck = neckOffsets(level, center, yaw, pitch);
        Vec3[] centers = TitanicaKinematics.partCenters(center, yaw, pitch, scale, neck[0], neck[1]);
        double[] sizes = TitanicaKinematics.partSizes(scale);
        List<AABB> boxes = new ArrayList<>(centers.length);
        for (int i = 0; i < centers.length; i++) {
            boxes.add(AABB.ofSize(centers[i], sizes[i], sizes[i], sizes[i]));
        }
        return boxes;
    }

    /**
     * {@link DorgaArchelausTitanicaEntity#hurt} から、エンティティ側で減った体力を書き戻すために呼ばれる。
     * ここからエンティティへ再転送すると無限再帰になるので、体力を代入するだけにしてある。
     */
    public void setHealthFromEntity(ServerLevel level, float value) {
        if (!active || dying) {
            return;
        }
        if (value <= 0.0F) {
            beginDeath(level);
            return;
        }
        float lost = this.health - Math.max(0.0F, value);
        this.health = Math.max(0.0F, value);
        if (lost > 0.0F) {
            // 実エンティティ経由の被弾(近距離)。死んだときの断末魔は die() → stop() 側で鳴る。
            cry(level, lost, false);
        }
        setDirty();
        sync(level);
    }

    // ------------------------------------------------------------------ tick

    /**
     * {@link SiegeTicker} から毎 tick 呼ばれる。ボス自身の「移動」は式で決まるのでここでは何もせず、
     * 実エンティティの出し入れ・攻撃の段階進行・ボスバー・クライアントへの定期再送だけを行う。
     */
    public void tick(ServerLevel level) {
        // フェーズ昇格による開始待ち(プレイヤー不在で始められなかったぶん)の再挑戦。
        // 進行中でなくても見る必要があるので、active の早期リターンより先に置く。
        if (OvergrownVegetationConfig.COMMON.siegeEnabled.get()) {
            tickPhaseTrigger(level);
        }
        if (!active) {
            return;
        }
        if (!OvergrownVegetationConfig.COMMON.siegeEnabled.get()) {
            stop(level, false);
            return;
        }

        bossEvent.setProgress(maxHealth > 0.0F ? Mth.clamp(health / maxHealth, 0.0F, 1.0F) : 0.0F);
        List<ServerPlayer> players = level.players();
        for (ServerPlayer player : players) {
            if (!bossEvent.getPlayers().contains(player)) {
                bossEvent.addPlayer(player);
            }
        }
        for (ServerPlayer player : List.copyOf(bossEvent.getPlayers())) {
            if (!players.contains(player)) {
                bossEvent.removePlayer(player);
            }
        }

        double t = elapsed(level);
        Vec3 position = positionAt(t);
        float yaw = yawAt(t);

        if (dying) {
            tickDeath(level, position, t);
            return;
        }

        tickNeck(position, yaw, pitchAt(t));
        updateBossEntity(level, position);
        tickBombing(level, position, yaw);
        tickFlowerBeam(level, position);
        tickTentacleBeam(level, position, yaw);
        // 砲撃への反撃。撃たれたことへの返しなので、ビームの最中でも近距離でも撃つ。
        tickRetaliation(level, position, yaw);
        // 頭のビームを撃っていないあいだの 2 つの行動。どちらも beamPhase を見て自分で引っ込む。
        tickMortar(level, position, yaw);
        tickBarrage(level, position, yaw);

        int interval = Math.max(1, OvergrownVegetationConfig.COMMON.siegeSyncIntervalTicks.get());
        if (level.getGameTime() % interval == 0L) {
            sync(level);
        }
    }

    // ------------------------------------------------------------------ 撃破と落下

    /**
     * 体力が尽きた。<b>その場で終わらせず、地面へ落ちていく演出に入る</b>(ユーザー要望 2026-09-13)。
     *
     * <p>落下は {@link SiegeDeathFall} の<b>式</b>で、位置の同期パケットは要らない —— 追加で送るのは
     * 「いつ死んだか」と「着地する Y」の 2 つだけ。攻城戦そのものが終わる(ボスバーが消え、
     * 終了メッセージが出る)のは、着地して {@code LINGER_TICKS} 置いたあと。
     */
    public void beginDeath(ServerLevel level) {
        if (!active || dying) {
            return;
        }
        dying = true;
        deathImpacted = false;
        deathAt = elapsed(level);
        health = 0.0F;
        beamPhase = BEAM_IDLE;
        tentaclePhase = BEAM_IDLE;
        barrageActive = false;
        deathGroundY = groundYAt(level, motion().positionAt(deathAt));
        setDirty();
        // 断末魔はここで 1 度だけ(stop 側では鳴らさない)。落ちているあいだ尾を引く。
        cry(level, maxHealth, true);
        sync(level);
        OvergrownVegetation.LOGGER.debug("攻城戦ボスが撃破されました。落下を開始します(着地 Y = {})。", deathGroundY);
    }

    /** 落下中の毎 tick。攻撃も首の追従もしない — 実エンティティを位置に追従させ、着地を見るだけ。 */
    private void tickDeath(ServerLevel level, Vec3 position, double t) {
        updateBossEntity(level, position);
        if (!deathImpacted && SiegeDeathFall.landed(motion(), deathAt, deathGroundY, t)) {
            deathImpacted = true;
            setDirty();
            impact(level, position);
        }
        if (SiegeDeathFall.finished(motion(), deathAt, deathGroundY, t)) {
            stop(level, true);
            return;
        }
        int interval = Math.max(1, OvergrownVegetationConfig.COMMON.siegeSyncIntervalTicks.get());
        if (level.getGameTime() % interval == 0L) {
            sync(level);
        }
    }

    /** 着地の瞬間の演出。チャンクが読み込まれていないところでは何も出せないので、そのときは黙って諦める。 */
    private void impact(ServerLevel level, Vec3 at) {
        if (!level.hasChunkAt(BlockPos.containing(at))) {
            return;
        }
        double scale = OvergrownVegetationConfig.COMMON.siegeScale.get();
        double spread = TitanicaKinematics.MODEL_LENGTH_UNITS / 16.0D * scale * 0.25D;
        for (int i = 0; i < 24; i++) {
            double dx = (level.random.nextDouble() * 2.0D - 1.0D) * spread;
            double dz = (level.random.nextDouble() * 2.0D - 1.0D) * spread;
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x + dx, at.y, at.z + dz, 1,
                    0.0D, 0.0D, 0.0D, 0.0D);
        }
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 16.0F, 0.4F);
    }

    /** 落ちた先の地面の高さ。未ロードなら海面で代用する(そこは誰も見ていない)。 */
    private static double groundYAt(ServerLevel level, Vec3 at) {
        BlockPos pos = BlockPos.containing(at.x, at.y, at.z);
        if (!level.hasChunkAt(pos)) {
            return level.getSeaLevel();
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
    }

    // ------------------------------------------------------------------ 実エンティティ

    /**
     * 近距離用の実エンティティの出し入れ。
     *
     * <p>プレイヤーが {@code siegeCloseRange} まで近づき、かつボスの居る場所がロード済みなら
     * {@link DorgaArchelausTitanicaEntity} を湧かせる。離れたら(ヒステリシスぶんの余裕を見て)discard する。
     * エンティティは遠距離時とまったく同じ {@link SiegeBossMotion} の式で動くので、
     * 湧いた瞬間・消えた瞬間のどちらでも位置は完全に一致し、見た目は途切れない。
     */
    private void updateBossEntity(ServerLevel level, Vec3 position) {
        double closeRange = OvergrownVegetationConfig.COMMON.siegeCloseRange.get();
        Player nearest = level.getNearestPlayer(position.x, position.y, position.z, -1.0D, false);
        // 「近い」は<b>胴の中心まで</b>ではなく<b>体のいちばん近い所まで</b>で測る。
        // ボスは全長 615 ブロックあるので、中心で測ると尾の先や頭の真下に立っていても
        // 300 ブロック以上「遠い」ことになり、当たり判定パーツが湧かなかった。
        double distance = nearest == null ? Double.MAX_VALUE : distanceToBody(level, position, nearest.position());

        DorgaArchelausTitanicaEntity entity = currentEntity(level);
        if (entity == null) {
            if (entityId != null && distance > closeRange * ENTITY_RELEASE_FACTOR) {
                // チャンクが未ロードで見つからないまま遠ざかった。参照だけ捨てる
                // (エンティティ自身は次に自分の tick が回ったとき、攻城戦が終わっていれば自ら discard する)。
                entityId = null;
                setDirty();
            }
            boolean wanted = distance <= closeRange;
            updateAnchor(level, position, wanted);
            if (wanted && level.hasChunkAt(BlockPos.containing(position))) {
                spawnBossEntity(level, position);
            }
            return;
        }
        if (distance > closeRange * ENTITY_RELEASE_FACTOR) {
            entity.discard();
            entityId = null;
            setDirty();
            updateAnchor(level, position, false);
            return;
        }
        updateAnchor(level, position, true);
    }

    /**
     * {@code from} から<b>ボスの体</b>(体軸の線分)までの距離。頭の先から尾の先までを 1 本の線分として扱う。
     *
     * <p>首は曲がるので頭は軸から最大 150 ブロックほど外れうるが、ここは
     * 「当たり判定パーツを用意すべきか」の大づかいな判定なので軸で足りる。
     */
    private double distanceToBody(ServerLevel level, Vec3 center, Vec3 from) {
        SiegeBossMotion motion = motion();
        double t = elapsed(level);
        double half = BODY_HALF_UNITS / 16.0D * OvergrownVegetationConfig.COMMON.siegeScale.get();
        Vec3 forward = TitanicaKinematics.direction(yawAt(t), pitchAt(t));
        return TitanicaKinematics.distanceToSegment(from,
                center.add(forward.scale(half)), center.add(forward.scale(-half)));
    }

    /**
     * 実エンティティが必要なあいだ、胴の中心(= エンティティの位置)の周り 3×3 チャンクを強制ロードしておく。
     * 中心が別のチャンクへ移ったら固定し直す。{@link #anchorChunks} の javadoc に理由がある。
     */
    private void updateAnchor(ServerLevel level, Vec3 position, boolean wanted) {
        if (!wanted) {
            releaseAnchor(level);
            return;
        }
        BlockPos center = BlockPos.containing(position);
        long chunk = ChunkPos.asLong(center.getX() >> 4, center.getZ() >> 4);
        if (anchorChunks.length > 0 && anchorChunkPos == chunk) {
            return;
        }
        releaseAnchor(level);
        anchorChunks = RaidChunkLoader.force(level, center, ANCHOR_RADIUS_CHUNKS);
        anchorChunkPos = chunk;
        setDirty();
    }

    private void releaseAnchor(ServerLevel level) {
        if (anchorChunks.length == 0 && anchorChunkPos == Long.MIN_VALUE) {
            return;
        }
        RaidChunkLoader.release(level, anchorChunks);
        anchorChunks = new long[0];
        anchorChunkPos = Long.MIN_VALUE;
        setDirty();
    }

    private void spawnBossEntity(ServerLevel level, Vec3 position) {
        DorgaArchelausTitanicaEntity entity = ModEntities.DORGA_ARCHELAUS_TITANICA.get().create(level);
        if (entity == null) {
            return;
        }
        SiegeBossMotion motion = motion();
        double t = elapsed(level);
        entity.moveTo(position.x, position.y - entity.getBbHeight() * 0.5D, position.z,
                motion.yawAt(t), motion.pitchAt(t));
        entity.syncFrom(this);
        if (!level.addFreshEntity(entity)) {
            return;
        }
        entityId = entity.getUUID();
        setDirty();
        OvergrownVegetation.LOGGER.debug("攻城戦ボスの実エンティティを生成しました({})。", position);
    }

    @Nullable
    private DorgaArchelausTitanicaEntity currentEntity(ServerLevel level) {
        if (entityId == null) {
            return null;
        }
        return level.getEntity(entityId) instanceof DorgaArchelausTitanicaEntity entity && entity.isAlive()
                ? entity : null;
    }

    // ------------------------------------------------------------------ 爆撃

    /**
     * 爆撃。{@code siegeBombIntervalTicks} ごとに、触手の先端から {@code siegeBombCount} 発を落とす。
     * 爆弾は落下しながら爆ぜるが、地形破壊は既定でゼロ({@link SiegeBombEntity} 参照)。
     *
     * <p>ボスが未ロードチャンクに居るときは<b>湧かせない</b> — その場所ではチャンクがティックされず、
     * 爆弾が落ちも爆ぜもしないうえ、誰も見ていないため。結果として「プレイヤーが近くに居るときだけ
     * 爆撃が起きる」ことになる。
     */
    private void tickBombing(ServerLevel level, Vec3 position, float yaw) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        int interval = cfg.siegeBombIntervalTicks.get();
        if (interval <= 0) {
            return;
        }
        long now = level.getGameTime();
        if (lastBombGameTime != Long.MIN_VALUE && now - lastBombGameTime < interval) {
            return;
        }
        lastBombGameTime = now;
        setDirty();

        if (!level.hasChunkAt(BlockPos.containing(position))) {
            return;
        }

        double scale = cfg.siegeScale.get();
        int count = cfg.siegeBombCount.get();
        double drop = cfg.siegeBombDropOffset.get();
        Vec3 velocity = motion().headingAt(elapsed(level)).scale(speed).add(0.0D, -0.2D, 0.0D);
        int tentacles = TitanicaKinematics.tentacleCount();
        int first = level.random.nextInt(tentacles);
        for (int i = 0; i < count; i++) {
            int index = (first + i * 5) % tentacles;
            Vec3 spawn = TitanicaKinematics.tentacleTip(position, yaw, scale, index).add(0.0D, -drop, 0.0D);
            level.addFreshEntity(new SiegeBombEntity(level, spawn, velocity));
        }
    }

    // ------------------------------------------------------------------ 花のビーム(遠距離砲撃)

    /**
     * 頭の先の花から放つ巨大なビーム。かなり離れた({@code siegeBeamMinRange} 以上)プレイヤーだけを狙う
     * 迫撃砲で、溜め(予告)→照射→復帰の段階を踏む。段階が変わるたびに {@link #sync} して、
     * クライアントが予告・光線・首の向きを描けるようにする。
     */
    private void tickFlowerBeam(ServerLevel level, Vec3 position) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        long now = level.getGameTime();
        int interval = cfg.siegeBeamIntervalTicks.get();
        switch (beamPhase) {
            case BEAM_IDLE -> {
                if (interval <= 0 || now % TARGET_SEARCH_INTERVAL != 0L) {
                    return;
                }
                if (lastBeamEndGameTime != Long.MIN_VALUE && now - lastBeamEndGameTime < interval) {
                    return;
                }
                // 近距離モード(実エンティティが居る = 誰かが siegeCloseRange 以内)のあいだは撃たない。
                // 近くのプレイヤーは触手ビーム・爆撃の担当で、花のビームは遠くの拠点への砲撃。
                if (entityId != null || currentEntity(level) != null) {
                    return;
                }
                ServerPlayer target = pickFarTarget(level, position, cfg.siegeBeamMinRange.get());
                if (target == null) {
                    return;
                }
                double scatter = cfg.siegeBeamScatter.get();
                double angle = level.random.nextDouble() * Math.PI * 2.0D;
                double dist = Math.sqrt(level.random.nextDouble()) * scatter;
                beamScatterX = Math.cos(angle) * dist;
                beamScatterZ = Math.sin(angle) * dist;
                // 溜めのあいだは追いかける。誰を追うかを覚えておく。
                beamTargetPlayer = target.getUUID();
                beginFlowerBeam(level, target.position().add(beamScatterX, 0.0D, beamScatterZ));
                target.displayClientMessage(Component.translatable("message.overgrown_vegetation.siege.beam_locked"), true);
            }
            case BEAM_CHARGE -> {
                // 溜めのあいだは標的に<b>追従する</b>(ユーザー指示 2026-09-13)。
                trackBeamTarget(level, now);
                if (now - beamPhaseStart >= cfg.siegeBeamChargeTicks.get()) {
                    beamPhase = BEAM_FIRE;
                    beamPhaseStart = now;
                    // 撃ち始めたら<b>その場に固定</b>。ここから先は走って避けられる。
                    beamTargetPlayer = null;
                    setDirty();
                    applyFlowerBeamImpact(level, beamTarget());
                    sync(level);
                }
            }
            case BEAM_FIRE -> {
                applyFlowerBeamDamage(level, beamTarget());
                if (now - beamPhaseStart >= cfg.siegeBeamFireTicks.get()) {
                    beamPhase = BEAM_RECOVER;
                    beamPhaseStart = now;
                    setDirty();
                    sync(level);
                }
            }
            case BEAM_RECOVER -> {
                if (now - beamPhaseStart >= cfg.siegeBeamRecoverTicks.get()) {
                    beamPhase = BEAM_IDLE;
                    lastBeamEndGameTime = now;
                    setDirty();
                    sync(level);
                }
            }
            default -> beamPhase = BEAM_IDLE;
        }
    }

    /**
     * 花から {@code distance} ブロック離れた地点での花のビームの半径。花では {@code siegeBeamStartRadius}、
     * {@code siegeBeamGrowDistance} 離れると {@code siegeBeamRadius} になり、その先も同じ割合で太くなり続ける
     * (上限なし)。描画({@code SiegeBeamRenderer})と着弾のダメージ半径の両方がこれを使う。
     */
    public static float flowerBeamRadiusAt(double distance) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double grow = Math.max(1.0D, cfg.siegeBeamGrowDistance.get());
        return (float) (cfg.siegeBeamStartRadius.get() + cfg.siegeBeamRadius.get() * Math.max(0.0D, distance) / grow);
    }

    /**
     * 溜めのあいだ、狙点を標的の現在地へ追従させる(ユーザー指示 2026-09-13)。
     *
     * <p>撃ち始めた瞬間に {@link #beamTargetPlayer} を手放すので、<b>追うのは溜めのあいだだけ</b> ——
     * 照射が始まったら狙点は固定で、そこから走れば避けられる、という関係は今までどおり。
     * ずらし量({@link #beamScatterX}/{@link #beamScatterZ})は据え置きなので「狙いが甘い」性格も保たれる。
     *
     * <p>同期は {@value #BEAM_TRACK_SYNC_INTERVAL} tick おき。首の追従はサーバーもクライアントも
     * 同じ式でなめらかに寄せている({@code siegeNeckFollowSpeed})ので、この粗さでも段付きには見えない。
     */
    private void trackBeamTarget(ServerLevel level, long now) {
        if (beamTargetPlayer == null) {
            return;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(beamTargetPlayer);
        if (player == null || !player.isAlive() || player.isSpectator()) {
            // 居なくなった。最後に見た場所へそのまま撃ち込む。
            beamTargetPlayer = null;
            setDirty();
            return;
        }
        Vec3 aim = player.position().add(beamScatterX, 0.0D, beamScatterZ);
        if (aim.distanceToSqr(beamTarget()) < 1.0E-4D) {
            return;
        }
        beamTargetX = aim.x;
        beamTargetY = aim.y;
        beamTargetZ = aim.z;
        setDirty();
        if (now % BEAM_TRACK_SYNC_INTERVAL == 0L) {
            sync(level);
        }
    }

    /** {@code minRange} より遠くに居るプレイヤーからランダムに 1 人選ぶ。居なければ null。 */
    @Nullable
    private ServerPlayer pickFarTarget(ServerLevel level, Vec3 position, double minRange) {
        List<ServerPlayer> candidates = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            if (player.position().distanceTo(position) >= minRange) {
                candidates.add(player);
            }
        }
        return candidates.isEmpty() ? null : candidates.get(level.random.nextInt(candidates.size()));
    }

    private void beginFlowerBeam(ServerLevel level, Vec3 aim) {
        beamPhase = BEAM_CHARGE;
        beamPhaseStart = level.getGameTime();
        beamTargetX = aim.x;
        beamTargetY = aim.y;
        beamTargetZ = aim.z;
        setDirty();
        sync(level);
    }

    /** コマンド用: 射程の条件を無視して、指定地点へ花のビームを撃ち始める(狙点は固定。追従しない)。 */
    public boolean forceFlowerBeam(ServerLevel level, Vec3 aim) {
        if (!active || beamPhase != BEAM_IDLE) {
            return false;
        }
        // 前のビームの標的が残っていると、指定した地点ではなくその人を追ってしまう。
        beamTargetPlayer = null;
        beginFlowerBeam(level, aim);
        return true;
    }

    /** 現在の首の向きで求めた、花(ビームの発射点)のワールド座標。 */
    private Vec3 currentFlowerPosition(ServerLevel level, Vec3 target) {
        SiegeBossMotion motion = motion();
        double t = elapsed(level);
        Vec3 position = motion.positionAt(t);
        float yaw = motion.yawAt(t);
        float pitch = motion.pitchAt(t);
        double scale = OvergrownVegetationConfig.COMMON.siegeScale.get();
        float[] neck = TitanicaKinematics.neckOffsets(position, yaw, pitch, scale, target);
        return TitanicaKinematics.flowerPosition(position, yaw, pitch, scale, neck[0], neck[1]);
    }

    /**
     * 花のビームの着弾の瞬間の処理: 演出・ノックバック用の爆発({@link Level.ExplosionInteraction#NONE} =
     * ブロックは壊さない)と、{@code siegeBeamCraterRadius}(既定 0 = 無し)を上げたときだけ爆弾と同じ
     * 「柔らかいブロックだけを削る」限定的なクレーター。ダメージは {@link #applyFlowerBeamDamage} が毎 tick 入れる。
     */
    private void applyFlowerBeamImpact(ServerLevel level, Vec3 target) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        level.explode(null, null, null, target.x, target.y, target.z,
                cfg.siegeBeamPower.get().floatValue(), false, Level.ExplosionInteraction.NONE);
        int crater = cfg.siegeBeamCraterRadius.get();
        if (crater > 0) {
            SiegeBombEntity.carveMinimalCrater(level, BlockPos.containing(target), crater,
                    cfg.siegeBombCraterMaxResistance.get().floatValue());
        }
    }

    /**
     * 照射中、毎 tick: 光線(花から着弾点へ向かう円錐、{@link #flowerBeamRadiusAt})の中に居る生物すべてに
     * {@code siegeBeamDamage} を入れる。防具は貫通しない(2026-09-13 にやめた)が、バニラの無敵時間は無視する
     * (でないと 10 tick に 1 回、超過分しか入らない)。着弾点より少し先まで貫通させる。
     * 検索範囲は光線の終端側 {@value #BEAM_DAMAGE_SPAN} ブロックぶんだけ(それより上は誰も居ない)。
     */
    private void applyFlowerBeamDamage(ServerLevel level, Vec3 target) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        float damage = cfg.siegeBeamDamage.get().floatValue();
        if (damage <= 0.0F) {
            return;
        }
        Vec3 flower = currentFlowerPosition(level, target);
        Vec3 axis = target.subtract(flower);
        double length = axis.length();
        if (length < 1.0E-3D) {
            return;
        }
        Vec3 dir = axis.scale(1.0D / length);
        // 狙った地点で止まらず、その先 siegeBeamOvershoot まで突き進む(描画と同じ)。
        double overshoot = cfg.siegeBeamOvershoot.get();
        Vec3 end = target.add(dir.scale(overshoot));
        Vec3 spanStart = flower.add(dir.scale(Math.max(0.0D, length - BEAM_DAMAGE_SPAN)));
        double endRadius = flowerBeamRadiusAt(length + overshoot);
        AABB box = new AABB(spanStart, end).inflate(endRadius);
        DamageSource source = ModDamageTypes.siegeBeam(level);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box, SiegeData::canBeHitByBoss)) {
            Vec3 p = entity.getBoundingBox().getCenter().subtract(flower);
            double along = p.dot(dir);
            if (along < 0.0D || along > length + overshoot) {
                continue;
            }
            double offAxis = p.subtract(dir.scale(along)).length();
            if (offAxis > flowerBeamRadiusAt(along) + entity.getBbWidth() * 0.5D) {
                continue;
            }
            // 展開した遮蔽盾の裏に居るなら、この生物にはビームが届かない。
            if (BeamShieldBlockEntity.shielded(level, flower, entity.getEyePosition())) {
                continue;
            }
            entity.invulnerableTime = 0;
            entity.hurt(source, damage);
        }
    }

    // ------------------------------------------------------------------ 触手ビーム(近距離)

    /**
     * 腹側の触手から近くのプレイヤーへ撃つ細いビーム。実エンティティが居る(= プレイヤーが
     * {@code siegeCloseRange} 以内に居る)あいだだけ動く。
     */
    private void tickTentacleBeam(ServerLevel level, Vec3 position, float yaw) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        long now = level.getGameTime();
        DorgaArchelausTitanicaEntity entity = currentEntity(level);
        if (entity == null) {
            if (tentaclePhase != BEAM_IDLE) {
                tentaclePhase = BEAM_IDLE;
                setDirty();
                sync(level);
            }
            return;
        }
        switch (tentaclePhase) {
            case BEAM_IDLE -> {
                int interval = cfg.siegeTentacleBeamIntervalTicks.get();
                if (interval <= 0) {
                    return;
                }
                if (lastTentacleEndGameTime != Long.MIN_VALUE && now - lastTentacleEndGameTime < interval) {
                    return;
                }
                double reach = cfg.siegeCloseRange.get() * ENTITY_RELEASE_FACTOR;
                // 1 本ではなく、別々の触手から同時に siegeTentacleBeamCount 本(既定 8)。
                // 狙うのはプレイヤーとは限らない(近距離の攻撃は下に居る Mob も等しく薙ぐ)。
                List<LivingEntity> victims = pickVictims(level, position, reach, cfg.siegeTentacleBeamCount.get());
                if (victims.isEmpty()) {
                    return;
                }
                List<Vec3> aims = new ArrayList<>(victims.size());
                for (LivingEntity victim : victims) {
                    aims.add(victim.getEyePosition());
                }
                beginTentacleVolley(level, aims);
            }
            case BEAM_CHARGE -> {
                if (now - tentaclePhaseStart >= cfg.siegeTentacleBeamChargeTicks.get()) {
                    tentaclePhase = BEAM_FIRE;
                    tentaclePhaseStart = now;
                    tentacleFired = 0;
                    setDirty();
                    // 終点は<b>ここで全部まとめて</b>求めてしまう。1 本ずつ撃つのは damage と描画の
                    // タイミングだけで、終点まで撃つたびに送り直すと 1 斉射で 8 回同期することになる。
                    // ボスは 0.08 ブロック/tick しか動かないので、24 tick のあいだのずれは 2 ブロック未満。
                    double overshoot = cfg.siegeTentacleBeamOvershoot.get();
                    for (int i = 0; i < tentacleIndices.length; i++) {
                        Vec3 tip = TitanicaKinematics.tentacleTip(position, yaw, cfg.siegeScale.get(), tentacleIndices[i]);
                        setTentacleTarget(i, beamEnd(level, tip, tentacleAim(i), overshoot));
                    }
                    sync(level);
                }
            }
            case BEAM_FIRE -> {
                // <b>1 本ずつ</b>走らせる(小ビームの乱射と同じ見せ方。ユーザー指示 2026-09-13)。
                int shotInterval = Math.max(1, cfg.siegeTentacleBeamShotIntervalTicks.get());
                long age = now - tentaclePhaseStart;
                while (tentacleFired < tentacleIndices.length && age >= (long) tentacleFired * shotInterval) {
                    Vec3 tip = TitanicaKinematics.tentacleTip(position, yaw, cfg.siegeScale.get(),
                            tentacleIndices[tentacleFired]);
                    applyTentacleBeam(level, tip, tentacleTarget(tentacleFired), tentacleAim(tentacleFired));
                    tentacleFired++;
                    setDirty();
                }
                // 最後の 1 本が消えるまで FIRE のまま(クライアントはこの段階のあいだ描いている)。
                long last = (long) Math.max(0, tentacleIndices.length - 1) * shotInterval;
                if (tentacleFired >= tentacleIndices.length
                        && age >= last + cfg.siegeTentacleBeamFireTicks.get()) {
                    tentaclePhase = BEAM_IDLE;
                    lastTentacleEndGameTime = now;
                    setDirty();
                    sync(level);
                }
            }
            default -> tentaclePhase = BEAM_IDLE;
        }
    }

    /**
     * 触手ビームの斉射を始める。{@code aims} 1 つにつき 1 本、<b>別々の触手</b>から撃つ
     * (触手は 18 本あるので、8 本ぶん選んでも重ならない)。
     */
    private void beginTentacleVolley(ServerLevel level, List<Vec3> aims) {
        int count = Math.min(aims.size(), TitanicaKinematics.tentacleCount());
        // 触手の番号を重複なく選ぶ(Fisher–Yates を count 本ぶんだけ回す)。
        int total = TitanicaKinematics.tentacleCount();
        int[] pool = new int[total];
        for (int i = 0; i < total; i++) {
            pool[i] = i;
        }
        for (int i = 0; i < count; i++) {
            int j = i + level.random.nextInt(total - i);
            int swap = pool[i];
            pool[i] = pool[j];
            pool[j] = swap;
        }

        tentacleIndices = new int[count];
        tentacleTargets = new double[count * 3];
        tentacleAims = new double[count * 3];
        tentacleFired = 0;
        for (int i = 0; i < count; i++) {
            tentacleIndices[i] = pool[i];
            Vec3 aim = aims.get(i);
            // 終点は撃つ直前に差し替わる。それまでは狙点をそのまま入れておく。
            setTentacleTarget(i, aim);
            tentacleAims[i * 3] = aim.x;
            tentacleAims[i * 3 + 1] = aim.y;
            tentacleAims[i * 3 + 2] = aim.z;
        }
        tentaclePhase = BEAM_CHARGE;
        tentaclePhaseStart = level.getGameTime();
        setDirty();
        sync(level);
    }

    /** 斉射の {@code i} 本目の狙点。範囲外は原点(斉射が空のときの保険)。 */
    private Vec3 tentacleTarget(int i) {
        int at = i * 3;
        return at + 2 < tentacleTargets.length
                ? new Vec3(tentacleTargets[at], tentacleTargets[at + 1], tentacleTargets[at + 2])
                : Vec3.ZERO;
    }

    /** 斉射の {@code i} 本目の狙点(相手の目の位置)。当たり判定の窓の中心。 */
    private Vec3 tentacleAim(int i) {
        int at = i * 3;
        return at + 2 < tentacleAims.length
                ? new Vec3(tentacleAims[at], tentacleAims[at + 1], tentacleAims[at + 2])
                : tentacleTarget(i);
    }

    private void setTentacleTarget(int i, Vec3 aim) {
        int at = i * 3;
        if (at + 2 < tentacleTargets.length) {
            tentacleTargets[at] = aim.x;
            tentacleTargets[at + 1] = aim.y;
            tentacleTargets[at + 2] = aim.z;
        }
    }

    /** クライアントへ送る斉射の中身。 */
    private TentacleVolley tentacleVolley() {
        int count = tentacleIndices.length;
        Vec3[] targets = new Vec3[count];
        for (int i = 0; i < count; i++) {
            targets[i] = tentacleTarget(i);
        }
        return new TentacleVolley(tentacleIndices.clone(), targets);
    }

    /** コマンド用: 実エンティティが居れば、指定地点へ触手ビームの斉射を撃ち始める。 */
    public boolean forceTentacleBeam(ServerLevel level, Vec3 aim) {
        if (!active || tentaclePhase != BEAM_IDLE || currentEntity(level) == null) {
            return false;
        }
        int count = OvergrownVegetationConfig.COMMON.siegeTentacleBeamCount.get();
        List<Vec3> aims = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            aims.add(aim);
        }
        beginTentacleVolley(level, aims);
        return true;
    }

    /**
     * 触手ビームの判定: 触手の先端から終点({@link #beamEnd} で伸ばし切った先)までの線分の近くに
     * 居る生物すべてへダメージ。<b>標的の居た場所で止まらない</b>ので、後ろに居た者も巻き込む。
     *
     * <p>防具は貫通しない(2026-09-13 に貫通をやめた)。バニラの無敵時間だけは無視する。
     */
    private void applyTentacleBeam(ServerLevel level, Vec3 from, Vec3 to, Vec3 aim) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double radius = cfg.siegeTentacleBeamRadius.get();
        float damage = cfg.siegeTentacleBeamDamage.get().floatValue();
        if (damage <= 0.0F || to.subtract(from).lengthSqr() < 1.0E-6D) {
            return;
        }
        DamageSource source = ModDamageTypes.siegeBeam(level);
        AABB box = damageWindow(from, to, aim, radius);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box, SiegeData::canBeHitByBoss)) {
            double d = TitanicaKinematics.distanceToSegment(entity.getBoundingBox().getCenter(), from, to);
            if (d <= radius + entity.getBbWidth() * 0.5D) {
                entity.invulnerableTime = 0;
                entity.hurt(source, damage);
            }
        }
    }

    /**
     * ビーム 1 本の当たり判定を探す箱。<b>狙点のまわりの窓</b>にする —— 手前
     * {@value #NEAR_BEAM_DAMAGE_SPAN} ブロックから、狙点の先 {@value #BEAM_DAMAGE_REACH} ブロックまで
     * (手前側は触手の先端より先へは行かないように丸める)。
     *
     * <p>線分そのもの(触手の先端 〜 伸ばし切った終点)を丸ごと囲むと、上空数百ブロックから
     * 地中 512 ブロックまでの巨大な箱になる。乱射は 3 tick に 1 発飛ぶので、そこでエンティティ検索を
     * 掛けるのは現実的でない。相手が居るのは狙点のまわりだけなので、そこだけ見れば同じ結果になる。
     */
    private static AABB damageWindow(Vec3 from, Vec3 to, Vec3 aim, double radius) {
        Vec3 axis = to.subtract(from);
        if (axis.lengthSqr() < 1.0E-6D) {
            return new AABB(aim, aim).inflate(radius + 1.0D);
        }
        Vec3 dir = axis.normalize();
        double back = Math.min(NEAR_BEAM_DAMAGE_SPAN, aim.distanceTo(from));
        return new AABB(aim.subtract(dir.scale(back)), aim.add(dir.scale(BEAM_DAMAGE_REACH)))
                .inflate(radius + 1.0D);
    }

    /**
     * ボスの攻撃が当たってよい相手か。<b>グループ1〜10(汚染側)は撃たない</b> —
     * ボス自身もグループ7なので、この 1 行で自傷も同士討ちも止まる。
     *
     * <p>狙点の選び方({@link #pickVictim})でも汚染側を外しているが、ビームは「線の近くの生物すべて」に
     * 入るので、狙っていなくても巻き添えで焼けてしまう。当たり判定の側でも外すのが要る。
     * 取りこぼしても {@link com.yayuy.overgrownvegetation.phase.MobGroupFriendlyFire} が最後に落とす。
     */
    private static boolean canBeHitByBoss(LivingEntity entity) {
        return !(entity instanceof DorgaArchelausTitanicaEntity) && MobGroup.of(entity) == null;
    }

    /**
     * ビームの終点 — 狙点で止めずに {@code overshoot} ブロック先まで伸ばし、途中で地形にぶつかったら
     * そこで止める(頭の花のビームと同じ「突き抜ける」挙動)。
     *
     * <p>地形で切っているのは見た目のためだけではない: 切らないと線分が 512 ブロック地中へ潜り、
     * 当たり判定を探す AABB がそのぶん無駄に膨らむ。地面で止めれば探す範囲は空〜地表で収まる。
     */
    private static Vec3 beamEnd(ServerLevel level, Vec3 from, Vec3 aim, double overshoot) {
        Vec3 axis = aim.subtract(from);
        if (axis.lengthSqr() < 1.0E-6D) {
            return aim;
        }
        Vec3 dir = axis.normalize();
        Vec3 far = aim.add(dir.scale(overshoot));
        // 地形で切るのは<b>狙点から先のわずかな区間だけ</b>(2026-09-13 に絞った)。
        // 触手の先端は 1000 ブロック先の<b>未ロードチャンク</b>に在ることがあり、そこから clip すると
        // Level.getBlockState が通り道のチャンクを片っ端から同期ロード(未生成なら生成)してしまう。
        // 乱射は 1 回で 40 発撃つので、これはサーバーが目に見えて止まるほど重い
        // —— 遠くで戦っているときに乱射が来なくなっていた原因。
        if (!level.hasChunkAt(BlockPos.containing(aim))) {
            return far;
        }
        Vec3 clipTo = aim.add(dir.scale(Math.min(overshoot, TERRAIN_CLIP_RANGE)));
        HitResult hit = level.clip(new ClipContext(aim, clipTo, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS ? far : hit.getLocation();
    }

    // ------------------------------------------------------------------ 胞子爆弾の曲射

    /** 頭の花のビームの最中(溜め・照射・復帰のいずれか)か。この 2 つの行動はそのあいだ休む。 */
    private boolean headBeamBusy() {
        return beamPhase != BEAM_IDLE;
    }

    /**
     * <b>近距離モード</b> — ボスの下や近くにプレイヤーが居て、実エンティティが湧いている状態
     * ({@code siegeCloseRange} 512 以内)。攻撃の組み立てがここで切り替わる:
     * <ul>
     *   <li>頭の花のビームは撃たない(元からの仕様。あれは遠くの拠点への砲撃)。</li>
     *   <li><b>胞子爆弾の曲射もしない</b> — 真下に居る相手へ 14 秒かけて山なりに撃つのは不自然だし、
     *       近距離では触手ビームと乱射が担当する。</li>
     *   <li><b>乱射の間隔が {@code siegeBarrageCloseIntervalTicks}(既定 8 秒)まで詰まる</b>。</li>
     * </ul>
     * 判定は花のビームと同じく「id が残っているか、実体が見つかるか」の OR ——
     * チャンクが未ロードで実体を引けないだけ、という状態も近距離として扱う。
     */
    private boolean closeRange(ServerLevel level) {
        return entityId != null || currentEntity(level) != null;
    }

    /** 設定の間隔を 0.55〜1.45 倍に散らした待ち時間(tick)。次の斉射・乱射までの間隔を規則的にしないため。 */
    private static long randomDelay(ServerLevel level, int interval) {
        return Math.max(1L, (long) (interval * (0.55D + level.random.nextDouble() * 0.90D)));
    }

    /**
     * <b>胞子爆弾の曲射</b>。頭のビームを撃っていないあいだ、{@code siegeMortarIntervalTicks} ごとに
     * 触手の先から {@code siegeMortarCount} 発を、プレイヤーの<b>いる辺り</b>へ山なりに投げ上げる。
     *
     * <h2>弾道</h2>
     * <p>爆弾は {@link ShellTracker} に乗せる — つまり砲台の砲弾とまったく同じ「発射位置・初速・発射時刻」
     * だけのデータで、エンティティではない。おかげで 1000 ブロック先の未ロードチャンクから撃っても
     * 飛んでいけるし、弾道はプレイヤー側からもそのまま見える。
     *
     * <p>初速は「解を探す」のではなく<b>飛翔時間から逆算</b>する: 水平距離を {@code siegeMortarSpeed} で
     * 割った時間 T で着くように水平成分を決め、鉛直成分は {@code vy = dy/T + g·T/2}。こうすると射程外が
     * 存在せず(どんなに遠くても必ず届く)、山なりの度合いは {@code siegeMortarGravity} だけで決まる
     * (弾道の高さ = g·T²/8。既定では 1000 ブロック先へ撃つと 300 ブロックほど上を通る)。
     *
     * <h2>当たらなさ</h2>
     * <p>狙点をプレイヤー中心の半径 {@code siegeMortarScatter} の円のどこかへずらし、さらに初速へ
     * {@code siegeMortarJitter} の誤差を乗せる。後者は飛翔時間ぶん増幅されるので、遠いほど散る。
     * 直撃はまれで、ふつうは「周りにばらばら落ちてくる」。
     */
    private void tickMortar(ServerLevel level, Vec3 position, float yaw) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        int interval = cfg.siegeMortarIntervalTicks.get();
        if (interval <= 0 || headBeamBusy() || level.getGameTime() % TARGET_SEARCH_INTERVAL != 0L) {
            return;
        }
        if (closeRange(level)) {
            // 近くにプレイヤーが居るあいだは曲射しない(触手ビームと乱射の担当)。
            return;
        }
        long now = level.getGameTime();
        if (nextMortarGameTime == Long.MIN_VALUE) {
            nextMortarGameTime = now + randomDelay(level, interval);
            setDirty();
            return;
        }
        if (now < nextMortarGameTime) {
            return;
        }
        ServerPlayer target = pickNearTarget(level, position, cfg.siegeMortarRange.get());
        if (target == null) {
            return;
        }
        nextMortarGameTime = now + randomDelay(level, interval);
        setDirty();
        mortarVolley(level, position, yaw, target.position(),
                cfg.siegeMortarCount.get(), cfg.siegeMortarScatter.get(), 0);
    }

    // ------------------------------------------------------------------ 砲撃への反撃

    /**
     * <b>砲台の砲弾が命中した</b>({@link ShellTracker} から)。ユーザー要望 2026-09-17:
     * 撃たれたら胞子爆弾の<b>斉射</b>({@code siegeRetaliationBombs} 発 = 普段の曲射と同じ 3 発)を
     * {@code siegeRetaliationShots} 回<b>連続</b>で撃ち返し、<b>その弾だけ</b>が着弾地点に
     * {@code siegeRetaliationSpawnCount} 体のグループ4 を置いていく({@link SporeBombReinforcements})。
     * 既定では 3 発 × 3 斉射 = 9 発、増援は合わせて 27 体。
     *
     * <p>狙うのは<b>撃ってきた者の居る場所そのものではない</b>(ユーザー指定 2026-09-17)。
     * {@code from}(= 砲弾が出てきた場所 = 砲台)のそばに居るプレイヤーを基点に、
     * <b>半径 {@code siegeRetaliationScatter}(既定 45)の円のどこか</b>へ 1 発ずつ落ちる
     * ({@link #mortarVolley} が 1 発ごとに引き直す)。直撃で潰すのではなく、
     * 砲台の周り一帯に増援をばら撒くための撃ち方。そばにプレイヤーが居なければ砲台そのものを基点にする
     * (無人の砲台をレッドストーンで撃たせている場合)。
     *
     * <p>基点は<b>当たった時点で固定</b>する。弾は着くまでに十数秒かかるので追尾はしないし、
     * 斉射 3 回の途中で基点を引き直すと弾が散らばる方向まで変わってしまう。
     *
     * <p>撃ち返しは {@code siegeRetaliationCooldownTicks} に 1 回まで。これが無いと砲弾 1 発ごとに
     * 斉射 3 回が走って、増援が湧き続ける(砲台は数十秒でリロードできるし、複数人が撃てる)。
     * 3 回を撃ち終わる前に当たった砲弾も同じ理由で無視する —— 狙点を上書きすると、
     * 残りの斉射が途中で別の方向へ飛んでしまう。
     */
    public void onShelled(ServerLevel level, Vec3 from) {
        if (!active || dying) {
            return;
        }
        int shots = OvergrownVegetationConfig.COMMON.siegeRetaliationShots.get();
        if (shots <= 0 || retaliationShotsLeft > 0) {
            return;
        }
        long now = level.getGameTime();
        if (retaliationReadyGameTime != Long.MIN_VALUE && now < retaliationReadyGameTime) {
            return;
        }
        retaliationShotsLeft = shots;
        nextRetaliationGameTime = now;   // この tick のうちに 1 斉射目(撃たれた直後に返す)
        // 基点は砲台のそばに居るプレイヤー。居なければ砲台そのもの(無人の砲台を撃たせている場合)。
        ServerPlayer gunner = pickNearTarget(level, from, GUNNER_SEARCH_RANGE);
        Vec3 anchor = gunner != null ? gunner.position() : from;
        retaliationAimX = anchor.x;
        retaliationAimY = anchor.y;
        retaliationAimZ = anchor.z;
        setDirty();
    }

    /** 反撃を 1 斉射ずつ進める。撃つ弾は普段の曲射と同じで、増援を連れてくる点だけが違う。 */
    private void tickRetaliation(ServerLevel level, Vec3 position, float yaw) {
        if (retaliationShotsLeft <= 0) {
            return;
        }
        long now = level.getGameTime();
        if (now < nextRetaliationGameTime) {
            return;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        mortarVolley(level, position, yaw,
                new Vec3(retaliationAimX, retaliationAimY, retaliationAimZ),
                cfg.siegeRetaliationBombs.get(), cfg.siegeRetaliationScatter.get(),
                cfg.siegeRetaliationSpawnCount.get());
        if (--retaliationShotsLeft <= 0) {
            retaliationReadyGameTime = now + cfg.siegeRetaliationCooldownTicks.get();
        } else {
            nextRetaliationGameTime = now + cfg.siegeRetaliationGapTicks.get();
        }
        setDirty();
    }

    /** コマンド用: 間隔や頭のビームの条件を無視して、いますぐ 1 斉射する。 */
    public boolean forceMortar(ServerLevel level, Vec3 aim) {
        if (!active) {
            return false;
        }
        SiegeBossMotion motion = motion();
        double t = elapsed(level);
        nextMortarGameTime = level.getGameTime()
                + randomDelay(level, OvergrownVegetationConfig.COMMON.siegeMortarIntervalTicks.get());
        setDirty();
        mortarVolley(level, motion.positionAt(t), motion.yawAt(t), aim,
                OvergrownVegetationConfig.COMMON.siegeMortarCount.get(),
                OvergrownVegetationConfig.COMMON.siegeMortarScatter.get(), 0);
        return true;
    }

    /**
     * 1 斉射ぶんの胞子爆弾を撃つ。狙点は {@code aim} のまわりへ散らす。
     *
     * @param count         撃つ発数(通常の曲射なら {@code siegeMortarCount})
     * @param scatter       狙点を散らす円の半径(ブロック)。通常の曲射は {@code siegeMortarScatter}、
     *                      砲撃への反撃は {@code siegeRetaliationScatter} と別の値を使う。
     * @param reinforcements 1 発の着弾地点に湧かせるグループ4 の数。0 = 湧かせない。
     *                       正の数を渡すのは砲撃への反撃({@link #tickRetaliation})だけ。
     */
    private void mortarVolley(ServerLevel level, Vec3 position, float yaw, Vec3 aim,
                              int count, double scatter, int reinforcements) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        ShellTracker tracker = ShellTracker.get(level);
        double scale = cfg.siegeScale.get();
        double speed = Math.max(0.5D, cfg.siegeMortarSpeed.get());
        double gravity = cfg.siegeMortarGravity.get();
        double jitter = cfg.siegeMortarJitter.get();
        int minFlight = cfg.siegeMortarMinFlightTicks.get();
        float direct = cfg.siegeMortarDirectDamage.get().floatValue();
        int tentacles = TitanicaKinematics.tentacleCount();
        int first = level.random.nextInt(tentacles);

        for (int i = 0; i < count; i++) {
            Vec3 origin = TitanicaKinematics.tentacleTip(position, yaw, scale, (first + i * 5) % tentacles);
            // 狙点: 目標を中心にした円のどこか(sqrt を掛けると円内が一様になる)。
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double radius = Math.sqrt(level.random.nextDouble()) * scatter;
            Vec3 spot = aim.add(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius);

            double dx = spot.x - origin.x;
            double dz = spot.z - origin.z;
            double flight = Math.max(minFlight, Math.sqrt(dx * dx + dz * dz) / speed);
            Vec3 velocity = new Vec3(dx / flight,
                    (spot.y - origin.y) / flight + 0.5D * gravity * flight,
                    dz / flight)
                    .add((level.random.nextDouble() - 0.5D) * jitter,
                            (level.random.nextDouble() - 0.5D) * jitter,
                            (level.random.nextDouble() - 0.5D) * jitter);
            tracker.fireSpore(level, origin, velocity, gravity, direct, reinforcements);
        }
    }

    // ------------------------------------------------------------------ 触手の小ビームの乱射

    /**
     * <b>触手の小ビームの乱射</b>。頭のビームを撃っていないあいだ、{@code siegeBarrageIntervalTicks} ごとに
     * {@code siegeBarrageDurationTicks} のあいだ、腹側の触手から細いビームを撃ち散らす。
     *
     * <p>狙点は<b>始めた時点のプレイヤーの位置で固定</b>し、1 発ごとにそこから半径
     * {@code siegeBarrageScatter} の球のどこかへ向けて撃つ。追尾しないので、走って離れれば当たらなくなる —
     * 「プレイヤーがいると思われる方角を薙いでいる」という見た目そのもの。
     */
    private void tickBarrage(ServerLevel level, Vec3 position, float yaw) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        long now = level.getGameTime();
        if (!barrageActive) {
            // 近距離モードでは間隔が詰まる(既定 20 秒 → 8 秒)。
            boolean close = closeRange(level);
            int interval = close ? cfg.siegeBarrageCloseIntervalTicks.get() : cfg.siegeBarrageIntervalTicks.get();
            if (interval <= 0 || headBeamBusy() || now % TARGET_SEARCH_INTERVAL != 0L) {
                return;
            }
            if (nextBarrageGameTime == Long.MIN_VALUE) {
                nextBarrageGameTime = now + barrageDelay(level, interval, close);
                setDirty();
                return;
            }
            if (now < nextBarrageGameTime) {
                return;
            }
            // 乱射の狙点もプレイヤーとは限らない(下に居る Mob の辺りへ撃ち散らすこともある)。
            LivingEntity target = pickVictim(level, position, cfg.siegeBarrageRange.get());
            if (target == null) {
                return;
            }
            beginBarrage(level, target.getEyePosition());
            return;
        }
        // 撃っている最中に頭のビームが始まったら、そちらを優先して止める。
        if (headBeamBusy() || now - barrageStart >= cfg.siegeBarrageDurationTicks.get()) {
            boolean close = closeRange(level);
            barrageActive = false;
            nextBarrageGameTime = now + barrageDelay(level,
                    close ? cfg.siegeBarrageCloseIntervalTicks.get() : cfg.siegeBarrageIntervalTicks.get(), close);
            setDirty();
            return;
        }
        int shotInterval = Math.max(1, cfg.siegeBarrageShotIntervalTicks.get());
        if (lastBarrageShotGameTime != Long.MIN_VALUE && now - lastBarrageShotGameTime < shotInterval) {
            return;
        }
        lastBarrageShotGameTime = now;
        // 1 回の発射で siegeBarrageShotsPerStep 本(既定 1)。密度を上げたいときはここを増やす。
        int perStep = Math.max(1, cfg.siegeBarrageShotsPerStep.get());
        for (int i = 0; i < perStep; i++) {
            fireBarrageShot(level, position, yaw);
        }
    }

    /**
     * 次の乱射までの待ち時間。遠距離では {@link #randomDelay} で散らすが、
     * <b>近距離では設定した秒数ちょうど</b>にする — 「近くに居るあいだは 8 秒ごと」という
     * 決め打ちの圧を掛けたいので、ここで散らすと意味が薄れる。
     */
    private static long barrageDelay(ServerLevel level, int interval, boolean close) {
        return close ? Math.max(1L, interval) : randomDelay(level, interval);
    }

    private void beginBarrage(ServerLevel level, Vec3 aim) {
        barrageActive = true;
        barrageStart = level.getGameTime();
        lastBarrageShotGameTime = Long.MIN_VALUE;
        barrageTargetX = aim.x;
        barrageTargetY = aim.y;
        barrageTargetZ = aim.z;
        setDirty();
    }

    /** コマンド用: 指定地点の方角へ乱射を始める。 */
    public boolean forceBarrage(ServerLevel level, Vec3 aim) {
        if (!active || barrageActive) {
            return false;
        }
        beginBarrage(level, aim);
        return true;
    }

    /** 乱射の 1 発。触手 1 本を選び、狙点を大きくずらした先へ撃つ。 */
    private void fireBarrageShot(ServerLevel level, Vec3 position, float yaw) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double scatter = cfg.siegeBarrageScatter.get();
        int index = level.random.nextInt(TitanicaKinematics.tentacleCount());
        Vec3 from = TitanicaKinematics.tentacleTip(position, yaw, cfg.siegeScale.get(), index);
        // 狙点を中心にした球のどこか(上下は半分 — 空へ撃ち上げてばかりでは見栄えがしない)。
        Vec3 aim = new Vec3(barrageTargetX, barrageTargetY, barrageTargetZ).add(
                (level.random.nextDouble() * 2.0D - 1.0D) * scatter,
                (level.random.nextDouble() * 2.0D - 1.0D) * scatter * 0.5D,
                (level.random.nextDouble() * 2.0D - 1.0D) * scatter);
        Vec3 segment = aim.subtract(from);
        if (segment.lengthSqr() < 1.0E-6D) {
            return;
        }
        // 狙った辺りで止まらず、地形に当たるまで撃ち抜く。
        Vec3 to = beamEnd(level, from, aim, cfg.siegeBarrageOvershoot.get());
        applyBarrageBeam(level, from, to, aim);
        PacketDistributor.sendToPlayersInDimension(level.getServer().overworld(),
                new SiegeBarragePayload(from, to, level.getGameTime()));
    }

    /**
     * 乱射の 1 発の判定: 線分の近くに居る生物へ {@code siegeBarrageDamage}。
     * 花のビームと同じく、展開した遮蔽盾の裏に居れば当たらない。
     */
    private void applyBarrageBeam(ServerLevel level, Vec3 from, Vec3 to, Vec3 aim) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        float damage = cfg.siegeBarrageDamage.get().floatValue();
        if (damage <= 0.0F) {
            return;
        }
        double radius = cfg.siegeBarrageRadius.get();
        // 触手の先は遠いので、生物を探す範囲は狙点のまわりの窓だけに絞る。
        AABB box = damageWindow(from, to, aim, radius);
        DamageSource source = ModDamageTypes.siegeBeam(level);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box, SiegeData::canBeHitByBoss)) {
            Vec3 center = entity.getBoundingBox().getCenter();
            if (TitanicaKinematics.distanceToSegment(center, from, to) > radius + entity.getBbWidth() * 0.5D) {
                continue;
            }
            if (BeamShieldBlockEntity.shielded(level, from, entity.getEyePosition())) {
                continue;
            }
            entity.invulnerableTime = 0;
            entity.hurt(source, damage);
        }
    }

    /** {@code maxRange} より近くに居るプレイヤーからランダムに 1 人選ぶ。居なければ null。 */
    @Nullable
    private ServerPlayer pickNearTarget(ServerLevel level, Vec3 position, double maxRange) {
        List<ServerPlayer> candidates = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            if (player.position().distanceTo(position) <= maxRange) {
                candidates.add(player);
            }
        }
        return candidates.isEmpty() ? null : candidates.get(level.random.nextInt(candidates.size()));
    }

    /**
     * <b>小ビームの乱射</b>の標的。<b>プレイヤーとは限らない</b> —
     * {@code siegeMobTargetChance} の確率で、プレイヤーの周り {@code siegeTargetMobRadius} に居る
     * 生物のほうを狙う(ボスから見れば下で動いている物はどれも同じ、という扱い)。
     *
     * <p>探すのを「プレイヤーの周り」に限っているのは、<b>チャンクが読み込まれているのがそこだけ</b>だから。
     * ボスの真下でもプレイヤーが居なければ生物は存在しないので、1000 ブロック四方を舐める意味が無い。
     *
     * @return 標的。範囲内に誰も居なければ null
     */
    @Nullable
    private LivingEntity pickVictim(ServerLevel level, Vec3 position, double maxRange) {
        ServerPlayer player = pickNearTarget(level, position, maxRange);
        if (player == null) {
            return null;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double mobChance = cfg.siegeMobTargetChance.get();
        int radius = cfg.siegeTargetMobRadius.get();
        if (radius <= 0 || mobChance <= 0.0D || level.random.nextDouble() >= mobChance) {
            return player;
        }
        List<LivingEntity> mobs = level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(radius),
                e -> e.isAlive() && !(e instanceof Player) && !(e instanceof DorgaArchelausTitanicaEntity)
                        // グループ1〜10 は汚染側の生き物なので撃たない(ボス自身もグループ7)。
                        && MobGroup.of(e) == null);
        return mobs.isEmpty() ? player : mobs.get(level.random.nextInt(mobs.size()));
    }

    /**
     * 触手の精密ビームの斉射用に、<b>できるだけ別々の相手</b>を {@code count} 体選ぶ。
     * 候補はプレイヤーと、そのまわり {@code siegeTargetMobRadius} に居る汚染側でない生き物
     * ({@link #canBeHitByBoss})を全部まとめて混ぜたもの —— <b>プレイヤーも Mob も等しく狙う</b>。
     *
     * <p class="warn"><b>近距離の 2 つ(精密ビーム・小ビームの乱射)だけが Mob も狙う</b>
     * (ユーザー指示 2026-09-13)。遠距離用の頭の花のビームと胞子爆弾の曲射は<b>プレイヤーだけ</b>を
     * 狙う({@link #pickFarTarget} / {@link #pickNearTarget})。拠点を焼きに来る砲撃と、
     * 真下で暴れている物を片端から薙ぐ近距離の攻撃、という住み分け。
     *
     * <p>候補が {@code count} に足りないときは<b>同じ相手を繰り返す</b> —— 本数は減らさない。
     * 「8 本の触手が一斉に走る」という絵を優先していて、相手が 1 人しか居ないなら 8 本ともそこへ集まる。
     */
    private List<LivingEntity> pickVictims(ServerLevel level, Vec3 position, double maxRange, int count) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        int radius = cfg.siegeTargetMobRadius.get();
        List<LivingEntity> candidates = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            if (player.position().distanceTo(position) > maxRange) {
                continue;
            }
            candidates.add(player);
            if (radius <= 0) {
                continue;
            }
            // 探索を「プレイヤーの周り」に限っているのは、チャンクが読み込まれているのがそこだけだから。
            for (LivingEntity mob : level.getEntitiesOfClass(LivingEntity.class,
                    player.getBoundingBox().inflate(radius),
                    e -> e.isAlive() && !(e instanceof Player) && canBeHitByBoss(e))) {
                if (!candidates.contains(mob)) {
                    candidates.add(mob);
                }
            }
        }
        if (candidates.isEmpty() || count <= 0) {
            return List.of();
        }
        // 並び順が固定だと毎回同じ相手から狙われるので混ぜる。
        for (int i = candidates.size() - 1; i > 0; i--) {
            int j = level.random.nextInt(i + 1);
            LivingEntity swap = candidates.get(i);
            candidates.set(i, candidates.get(j));
            candidates.set(j, swap);
        }
        List<LivingEntity> chosen = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            chosen.add(candidates.get(i % candidates.size()));
        }
        return chosen;
    }

    // ------------------------------------------------------------------ 悲鳴

    /**
     * ボスの悲鳴。ディメンション内の全員へ合図を送り、実際の再生はクライアントに任せる
     * ({@link com.yayuy.overgrownvegetation.client.ClientSiegeCry} — ボスは未ロードチャンクに居るので
     * サーバー側の {@code playSound} では誰にも届かない)。
     * 連射で鳴りっぱなしにならないよう {@code siegeCryCooldownTicks} の間隔を空ける(断末魔は例外)。
     */
    private void cry(ServerLevel level, float amount, boolean death) {
        long now = level.getGameTime();
        if (!death) {
            int cooldown = OvergrownVegetationConfig.COMMON.siegeCryCooldownTicks.get();
            if (lastCryGameTime != Long.MIN_VALUE && now - lastCryGameTime < cooldown) {
                return;
            }
        }
        lastCryGameTime = now;
        // 悲鳴は頭(花)のあたりから。首の向きも込みの実際の位置を使う。
        double t = elapsed(level);
        Vec3 center = positionAt(t);
        Vec3 head = TitanicaKinematics.flowerPosition(center, yawAt(t), pitchAt(t),
                OvergrownVegetationConfig.COMMON.siegeScale.get(), neckYaw, neckPitch);
        // 最大体力の 2 割を一撃で持っていかれたら「severity 1」。
        float severity = maxHealth > 0.0F ? Mth.clamp(amount / (maxHealth * 0.2F), 0.0F, 1.0F) : 0.5F;
        PacketDistributor.sendToPlayersInDimension(level.getServer().overworld(),
                new SiegeBossCryPayload(head, death, severity));
    }

    // ------------------------------------------------------------------ 同期

    /**
     * 現在の軌道パラメータ・経過 tick・体力・ビームの段階をディメンション内の全プレイヤーへ送る。
     * 1 パケットに状態を全部載せてあるので、取りこぼしても次の再送で必ず追従でき、
     * 途中ログインしたプレイヤーも 1 秒以内に合流できる({@code EarthquakeShakePayload} と同じ考え方)。
     */
    public void sync(ServerLevel level) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        long now = level.getGameTime();
        SiegeBossStatePayload payload = active
                ? new SiegeBossStatePayload(true, centerX, centerY, centerZ,
                        (float) radius, (float) altitude, (float) speed,
                        (int) Math.min(Integer.MAX_VALUE, elapsed(level)),
                        cfg.siegeScale.get().floatValue(),
                        health, maxHealth, neckYaw, neckPitch,
                        (float) SiegeBossMotion.approachFor(radius, cfg.siegeMinRadius.get(), speed,
                                cfg.siegeApproachLaps.get()),
                        cfg.siegeMinRadius.get().floatValue(),
                        new BeamState(beamPhase, (int) Math.max(0L, now - beamPhaseStart),
                                cfg.siegeBeamChargeTicks.get(), cfg.siegeBeamFireTicks.get(), cfg.siegeBeamRecoverTicks.get(),
                                0, beamTarget()),
                        new BeamState(tentaclePhase, (int) Math.max(0L, now - tentaclePhaseStart),
                                cfg.siegeTentacleBeamChargeTicks.get(), cfg.siegeTentacleBeamFireTicks.get(), 1,
                                tentacleIndices.length > 0 ? tentacleIndices[0] : 0, tentacleTarget(0)),
                        tentacleVolley(),
                        dying ? (float) deathAt : -1.0F, (float) deathGroundY)
                : SiegeBossStatePayload.inactive();
        PacketDistributor.sendToPlayersInDimension(level.getServer().overworld(), payload);
    }
}
