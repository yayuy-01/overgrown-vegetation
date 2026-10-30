package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * 「ヴォラムネス」— グループ5(適応種)の地中の種(2026-10-01 実装)。鰓曳動物とヤツメウナギを合わせた姿で、地上に出ると高さ 8.7 ブロック。
 * 遠目には地面から生えた巨大な蕾の茎、口は歯の花(NOTES)。
 *
 * <h2>ユーザーが決めたこと</h2>
 * <ul>
 *     <li>採用の案(2026-09-24〜26): <b>地中を泳ぐ大型の蠕虫。走る・跳ぶ振動で位置を探り、真下から丸呑みにする。
 *     呑まれると内部で継続ダメージ、内側から一定量殴ると吐き出す。スニークで歩けば気付かれない</b>。</li>
 *     <li>モデル・テクスチャ・アニメは低キューブ版(2026-09-30、ユーザーが見本の絵を見て「よし」)。描画倍率 2(= mobs.json の scale)。</li>
 *     <li>G5 は G4 より湧きにくく数が出ない(レア枠・1 体ずつ)。適応(武器への耐性)は group_5 タグで自動。</li>
 * </ul>
 * それ以外(流れ・数値・距離・時間・音)は<b>すべて仮決め</b>。下書き {@code tools/g5-impl/notes/voramnes.md}。
 *
 * <h2>流れ(状態 = 行動番号 {@code ACT_*}。アニメ 20 本と 1 対 1)</h2>
 * <pre>
 *   地中 stalk ──(振動)──▶ 振動の点へ泳ぐ ──(着いて、上に獲物)──▶ ambush(花が地表に開く = 予兆)
 *        ▲                                                   │ 開き切って獲物が花の上 → erupt / 居なければ lurk で待つ
 *        │ submerge                                          ▼
 *   地上 idle ◀── spit ◀──(中から殴られた量が一定)── digest ◀── erupt(花を閉じて突き上げ、呑む)
 *        │ 近くの相手                                      ▲
 *        └──▶ open ──▶ gape ──▶ swallow(正面から呑む)────┘
 * </pre>
 * 地中の間は {@code noPhysics}(ブロックをすり抜ける。<b>壊さない</b>・窒息しない)。移動は地表の高さをたどって
 * {@code setPos} で進める({@link VoramnesTerrain} の「潜れる地面」の上だけ。経路探索は使わない —— 実効幅 2.5 の歩きの経路探索は失敗するため)。
 * 地上に出ている間は根元から動かない(向きだけ変える)。
 *
 * <h2>呑んだ獲物</h2>
 * ファルキムネスの掴みと同じく<b>乗客として乗せる</b>({@code startRiding(this, true)})。位置は胴の中ほど({@code trunk4_skin}、
 * {@link #getPassengerAttachmentPoint})。自分から降りられない({@link Events#onMount})、中から殴れる({@link #canRiderInteract})。
 * 呑まれた相手は描かない({@code client/VoramnesClientEvents}: 周りからは胴の膨らみ = digest のアニメで分かる)。
 * 呑まれたプレイヤーの画面は、暗い肉の色の濃い霧で覆い、アクションバーに「中から殴れ」と出す(何が起きたか分かるように)。
 *
 * <h2>特殊攻撃</h2>
 * 攻撃は「呑む」の 1 種類だけ(真下から = ambush / 正面から = open)で、どちらも予兆(花が開く 1.35 秒・口を開く 1.4 秒)を見せてから当たる。
 * G5 の「特殊攻撃は交戦直後に出さない」({@link SpecialPacing})は<b>使っていない</b>(アクムネスと同じ判断。通常攻撃にあたるものが無く、
 * 呑むのがこの種の攻撃そのもののため。ユーザー確認待ち)。
 *
 * <h2>当たり判定</h2>
 * 状態で大きさを変える({@link #getDefaultDimensions}): 地上に出ている間は登録の大きさ(実効 2.5 × 8.5)、
 * 地中では蕾の先(実効 1.0 × 0.9)、地面の花の間は花の真ん中(実効 2.5 × 1.0)。登録の大きさはいちばん大きい地上の姿のまま
 * (SpawnFitGameTests#spawnBoxCoversGrownBody)。頭が前へ覆いかぶさるアニメがあるので、頭に部位 {@link VoramnesHeadPart} を 1 つ足した。
 */
public class VoramnesEntity extends Monster implements GeoEntity, ParasitizedMob, OvergrownSpawnCheck {

    // ------------------------------------------------------------------ 行動(= アニメ)

    public static final int ACT_STALK = 0;
    public static final int ACT_EMERGE = 1;
    public static final int ACT_IDLE = 2;
    public static final int ACT_SUBMERGE = 3;
    public static final int ACT_OPEN = 4;
    public static final int ACT_GAPE = 5;
    public static final int ACT_SWALLOW = 6;
    public static final int ACT_DIGEST = 7;
    public static final int ACT_DIGEST_KICK = 8;
    public static final int ACT_SPIT = 9;
    public static final int ACT_AMBUSH = 10;
    public static final int ACT_LURK = 11;
    public static final int ACT_LURK_FLINCH = 12;
    public static final int ACT_ERUPT = 13;
    public static final int ACT_FLINCH = 14;
    public static final int ACT_DEATH = 15;
    public static final int ACT_DEATH_OPEN = 16;
    public static final int ACT_DEATH_DIGEST = 17;
    public static final int ACT_DEATH_BURIED = 18;
    public static final int ACT_DEATH_STALK = 19;

    /** 行動番号 → アニメの名前(NOTES の 20 本)。 */
    private static final String[] ANIM_NAMES = {
            "stalk", "emerge", "idle", "submerge", "open", "gape", "swallow", "digest", "digest_kick", "spit",
            "ambush", "lurk", "lurk_flinch", "erupt", "flinch", "death", "death_open", "death_digest", "death_buried", "death_stalk"
    };

    /** 行動を切り替えるときの補間(tick)。GeckoLib は補間の後で 0 秒目から流す(メモリ geckolib-transition-delays-animation)。 */
    public static final int TRANSITION_TICKS = 4;
    /** ループの途中から短い動作へ入るときの補間(被弾・吐く・閉じる)。 */
    static final int SHORT_TRANSITION = 3;
    /** 同期の遅れ・補間の端数ぶんの余り(hold のアニメは最後の姿勢のまま待てる)。 */
    static final int MARGIN = 1;

    // アニメの長さ(tick。NOTES の秒 × 20)と合図の時刻
    static final int EMERGE_LEN = 32;
    /** emerge の 0.3 秒で地表を割る(土の粒子)。 */
    static final int EMERGE_BREAK = 6;
    static final int SUBMERGE_LEN = 28;
    static final int OPEN_LEN = 22;
    static final int SWALLOW_LEN = 26;
    /** swallow の 0.3 秒(歯板 0.27 秒・上と横の花弁 0.35 秒で閉じる)で呑む。 */
    static final int SWALLOW_CATCH = 6;
    static final int KICK_LEN = 10;
    static final int SPIT_LEN = 26;
    /** spit の 0.6 秒(0.55〜0.6 秒で口が開き切る)で吐き出す。 */
    static final int SPIT_EJECT = 12;
    static final int AMBUSH_LEN = 18;
    /** ambush の 0.55 秒で花が開き切る。 */
    static final int AMBUSH_OPEN = 11;
    static final int ERUPT_LEN = 20;
    /** erupt の 0.23 秒(歯板 0.17 秒・外輪 0.23 秒で閉じる)で呑む。 */
    static final int ERUPT_CATCH = 5;
    static final int FLINCH_LEN = 10;
    /** 口が開いている区間(秒。NOTES のアニメの時刻): open は溜め(蕾を締めて反る)の後から、swallow は花弁が閉じるまで、spit は吐いた口が閉じるまで。 */
    static final float OPEN_MOUTH_SEC = 0.35F;
    static final float SWALLOW_CLOSE_SEC = 0.35F;
    static final float SPIT_CLOSE_SEC = 1.0F;
    /**
     * 出入りの途中で「地上に立っている」と見なして flinch で受ける区間(秒、仮)。flinch の 0 秒目 = 静止の姿勢なので、
     * それに近い間だけ(emerge は 1.6 秒で静止に着く → 1.2 秒から、submerge は 0 秒目が静止 → 0.3 秒まで)。NOTES の表の「emerge submerge の地上の間」。
     */
    static final float EMERGE_STAND_SEC = 1.2F;
    static final float SUBMERGE_STAND_SEC = 0.3F;
    /** 倒れるアニメ(1.6 秒)+ 補間 + 余韻。ほかの G5 と同じ作り(tickDeath で待ってから消す)。 */
    static final int DEATH_TICKS = TRANSITION_TICKS + 32 + 6;

    // ------------------------------------------------------------------ 形(ブロック。NOTES の値 = 描画倍率 2 のとき。今の体格 / 2 を掛けて使う)

    /** NOTES の数値の描画倍率。 */
    static final float NOTES_SCALE = 2.0F;
    /** 地中の間の当たり判定(素の値。体格 2 で 1.0 × 0.9 = 蕾の先)。 */
    static final float BURIED_W = 0.5F;
    static final float BURIED_H = 0.45F;
    /** 地面の花の間の当たり判定の高さ(素の値。幅は登録の幅のまま)。 */
    static final float FLOWER_H = 0.5F;
    /** 花の上で呑まれる範囲(真ん中からの半径・高さ)。花の差し渡しは約 6。 */
    static final double CATCH_R = 2.2D;
    static final double CATCH_TOP = 2.5D;
    /**
     * 正面の呑み込み: 口が届く点(前 4.4、地上 1.9〜2.3)のまわり。届く範囲は体の向きに沿った長方形
     * (前 4.4 ± 1.8・横 ± 1.8・高さ −0.5〜3.5。相手の幅の半分だけ縁を広げる。{@link #mouthReach})。
     * 開いた口で呑みに行く範囲も同じ形(点検係 2026-10-01: 呑みに行く範囲が前 1.5 からだったので、前 1.5〜2.3 の相手へは必ず空振りしていた。
     * 当たりの箱も向きによらない形にした —— 軸に沿った箱だと斜め 45° のときだけ届く距離が 1.4 倍に伸びていた)。
     */
    static final double SWALLOW_FWD = 4.4D;
    static final double SWALLOW_R = 1.8D;
    static final double SWALLOW_TOP = 3.5D;
    /** 口を開き始める距離。 */
    static final double OPEN_R = 6.0D;
    /**
     * 口の届く範囲より内側(足元)の相手を見続けたら、潜って真下から呑みに行くまでの tick(仮、点検係 2026-10-01)。
     * 口は前 2.6 からしか届かないので、根元に張り付いた相手を正面からは呑めない(何度でも空振りする)。
     */
    static final int CLOSE_DIVE_TICKS = 10;
    /** 地上に出ている間に相手を見つける距離(水平)・見失う距離・上下。 */
    static final double FRONT_NOTICE_R = 9.0D;
    static final double FRONT_LOSE_R = 13.0D;
    static final double FRONT_DY = 6.0D;
    /** 頭(目)の高さ(静止の姿勢の口の中心)。 */
    static final double HEAD_UP = 7.0D;
    /**
     * 見通しを測る高さ(仮)。目は頭(7 ブロック)にあるが、そこから測ると葉の天蓋・低い天井の中に目が入って何も見えなくなるので、
     * 胴の中ほどから測る(壁越しに気付かない、が守れればよい)。
     */
    static final double SIGHT_UP = 3.0D;
    /** 呑んだ獲物の真ん中の高さ(胴の塊 trunk4_skin のあたり)。 */
    static final double PREY_UP = 3.3D;
    /** spit の吐き出しの口の中心(前・上)。 */
    static final double SPIT_FWD = 4.3D;
    static final double SPIT_UP = 3.2D;
    /** 呑める相手の大きさの上限(幅・高さ。仮)。これより大きい相手(アイアンゴーレムなど)は噛むだけ。 */
    static final double MAX_PREY_W = 1.6D;
    static final double MAX_PREY_H = 2.6D;
    /** 地上に出る・花を開く前に要る上の空き(仮)。 */
    static final double HEADROOM = 4.0D;

    // ------------------------------------------------------------------ 動き・時間(仮)

    /** 振動の点へ泳ぐ速さ = 移動速度 × これ(0.3 → 0.18 ブロック/tick = 秒速 3.6。走るプレイヤーは 5.6)。 */
    static final double HUNT_FACTOR = 0.6D;
    /** ぶらつく速さ = 移動速度 × これ。 */
    static final double WANDER_FACTOR = 0.25D;
    /** 着いたと見なす水平の距離。 */
    static final double ARRIVE = 0.6D;
    /** 振動を覚えている時間(8 秒)。着いた点でもこの間は聞き耳を立てて待つ。 */
    static final int NOISE_MEMORY = 160;
    /** 進めなくなってから諦めるまで。 */
    static final int STUCK_GIVE_UP = 20;
    /** ぶらつきの行き先を選ぶ確率(1/N per tick)と距離。 */
    static final int WANDER_CHANCE = 100;
    static final int WANDER_RANGE = 10;
    /** 聞こえない間に、ときどき地上へ顔を出して辺りを見る(1/N per tick)。 */
    static final int PERISCOPE_CHANCE = 1500;
    /** 地上で相手が居ないまま待つ時間(これを過ぎたら潜る)。 */
    static final int IDLE_LINGER = 80;
    /** 口を開いたまま待つ上限(過ぎたら呑みに行く = 空振りしてもよい)。 */
    static final int GAPE_MAX = 30;
    /** 地面の花のまま待つ上限(過ぎたら閉じて突き上がる)。 */
    static final int LURK_MAX = 60;
    /** 正面の呑み込みを空振りした後・吐いた後の冷却。 */
    static final int MISS_COOLDOWN = 40;
    static final int SPIT_COOLDOWN = 60;
    /** 吐き出した相手をすぐ呑み直さない時間。 */
    static final int SPAT_IMMUNE = 100;
    /** 旋回の上限(度/tick)。地上では根元から回るので遅く、地中では速い。 */
    static final float TURN_SURFACED = 4.0F;
    static final float TURN_BURIED = 12.0F;
    /** 口を開くのは相手がこの角度(度)より正面に入ってから。 */
    static final float OPEN_ARC = 30.0F;

    // ------------------------------------------------------------------ 呑む・消化(仮)

    /** 胃の中の継続ダメージの間隔と量(攻撃力 × これ。12 → 3.6、防具貫通)。 */
    static final int DIGEST_INTERVAL = 20;
    static final float DIGEST_FACTOR = 0.3F;
    /** 呑んだ瞬間・花の上に居て呑めなかった相手への噛みつき(攻撃力 × これ。防具は効く)。 */
    static final float BITE_FACTOR = 0.5F;
    /** 中から殴られてこれだけ(最大体力の割合)体力が減ったら吐き出す。外からの被弾は半分だけ数える。 */
    static final float SPIT_FRACTION = 0.08F;
    static final float OUTSIDE_FACTOR = 0.5F;
    /** 呑み続ける上限(トーテム・再生で死なない相手)。過ぎたら吐き出す。 */
    static final int DIGEST_MAX = 400;
    /** 中で死んだ相手を降ろすまで(Mob は倒れる 20 tick の後に消える。プレイヤーはリスポーンを待たない)。 */
    static final int DEAD_PREY_WAIT = 40;
    /** 呑んだプレイヤーへ出す合図(アクションバー)の間隔。乗った直後のバニラの「スニークで降りる」を上書きする。 */
    static final int SWALLOWED_MESSAGE_INTERVAL = 60;
    public static final String SWALLOWED_MESSAGE = "message." + OvergrownVegetation.MODID + ".voramnes_swallowed";

    // ------------------------------------------------------------------ 同期

    private static final EntityDataAccessor<Integer> DATA_ACTION =
            SynchedEntityData.defineId(VoramnesEntity.class, EntityDataSerializers.INT);
    /** 同じ行動の出し直しをクライアントへ知らせる番号。 */
    private static final EntityDataAccessor<Integer> DATA_SEQ =
            SynchedEntityData.defineId(VoramnesEntity.class, EntityDataSerializers.INT);
    /** 行動を始めたゲーム時刻(頭の部位の位置をクライアントでも同じ式で出す)。 */
    private static final EntityDataAccessor<Integer> DATA_ACTION_TIME =
            SynchedEntityData.defineId(VoramnesEntity.class, EntityDataSerializers.INT);
    /** その行動へ入るときの補間(tick)。当たりの tick = 補間 + アニメの秒 × 20 をサーバーとクライアントで揃える。 */
    private static final EntityDataAccessor<Integer> DATA_TRANSITION =
            SynchedEntityData.defineId(VoramnesEntity.class, EntityDataSerializers.INT);

    private static final RawAnimation[] ANIMS = new RawAnimation[ANIM_NAMES.length];

    static {
        for (int i = 0; i < ANIM_NAMES.length; i++) {
            ANIMS[i] = isLoop(i) ? RawAnimation.begin().thenLoop(ANIM_NAMES[i]) : RawAnimation.begin().thenPlayAndHold(ANIM_NAMES[i]);
        }
    }

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private final VoramnesHeadPart head;
    private final PartEntity<?>[] parts;

    /** サーバー: 今の行動の経過 tick。 */
    private int actionTicks;
    /** サーバー: 最後に聞いた振動の点と、覚えている残り tick。 */
    @Nullable
    private Vec3 noisePos;
    private int noiseTicks;
    private int stuckTicks;
    @Nullable
    private Vec3 wanderPos;
    /** サーバー: 地上で相手の居ない tick と、潜るまでの待ち。 */
    private int idleTicks;
    private int idleLinger = IDLE_LINGER;
    private int frontCooldown;
    @Nullable
    private LivingEntity frontTarget;
    private int frontScan;
    /** サーバー: 地上で、口の届かない足元の相手を見続けている tick({@link #CLOSE_DIVE_TICKS} で潜る)。 */
    private int closeTicks;
    /** サーバー: 呑んでからの tick・中から殴られた量・中で死んでからの tick。 */
    private int digestTicks;
    private float spitMeter;
    private int deadPreyTicks;
    /** 呑んだプレイヤーへ合図を出すまでの tick。 */
    private int messageDelay;
    /** 自分で獲物を降ろすときだけ立てる({@link Events#onMount} が止めないように)。 */
    private boolean releasing;
    /** 吐き出しの最中(降りる位置を口にする)。 */
    private boolean spitting;
    /** 吐いた相手(しばらく呑み直さない)と、次の tick に掛ける吐き出しの勢い(降車の直後は位置の同期で速度が消えるため 1 tick 待つ)。 */
    @Nullable
    private UUID spatUuid;
    private int spatUntil;
    @Nullable
    private Entity pendingSpit;
    private Vec3 pendingSpitVelocity = Vec3.ZERO;
    /**
     * Mob の振動を聞くか(本番は常に true。10-09)。テストでは false にしておく —— テスト場は並んで同時に走るので、隣のテスト場の Mob の足音・
     * 着地(跳んだ着地は 24 ブロック届く)を聞いて、頼んでいない相手を追い始める。
     */
    private boolean hearMobsForTests = true;
    /** サーバー: 向けたい向き(NaN なら今のまま)。 */
    private float wantedYaw = Float.NaN;
    /** 同じ tick に同じダメージ源が本体と頭の両方に当たったら 1 回にまとめる。 */
    @Nullable
    private DamageSource lastHitSource;
    private long lastHitTime = Long.MIN_VALUE;

    /** クライアント: 見えている行動の番号(出し直しでアニメを最初から流す)。 */
    private int clientSeq;

    public VoramnesEntity(EntityType<? extends VoramnesEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("voramnes", 60);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.lookControl = new FixedLookControl(this);
        this.head = new VoramnesHeadPart(this);
        this.parts = new PartEntity<?>[]{this.head};
        // エンダードラゴンと同じく、部位の id を本体の続き番号に予約する(MC-158205。予約しないと次に作られた別のエンティティと重なる)
        this.setId(ENTITY_COUNTER.getAndAdd(this.parts.length + 1) + 1);
        this.refreshDimensions();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), "voramnes");
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION, ACT_STALK);
        builder.define(DATA_SEQ, 0);
        builder.define(DATA_ACTION_TIME, 0);
        builder.define(DATA_TRANSITION, 0);
    }

    /**
     * 標的は「殴ってきた相手」だけを Goal で選ぶ。G5 共通の {@code ParasitizedTargetGoal}(視線を無視して範囲内の相手を全部狙う)は
     * <b>付けない</b> —— 付けるとスニークで歩く相手にも気付いてしまう(ユーザー指定に反する)。気付くのは振動({@link VoramnesSenses})・
     * 殴られたとき・地上に出ている間に目で見たとき({@link #frontTarget()})だけ。行動は全部 {@link #aiStep} で進める(NoAI の GameTest でも進む)。
     */
    @Override
    protected void registerGoals() {
        this.targetSelector.addGoal(1, new OvergrownHurtByTargetGoal(this));
    }

    @Override
    protected BodyRotationControl createBodyControl() {
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                Mob mob = VoramnesEntity.this;
                mob.yBodyRot = mob.getYRot();
                mob.yHeadRot = mob.getYRot();
            }
        };
    }

    // ------------------------------------------------------------------ 部位

    @Override
    public boolean isMultipartEntity() {
        return true;
    }

    @Override
    public PartEntity<?>[] getParts() {
        return this.parts;
    }

    @Override
    public void setId(int id) {
        super.setId(id);
        if (this.parts == null) {
            return; // 親のコンストラクタの途中(部位はまだ無い)
        }
        for (int i = 0; i < this.parts.length; i++) {
            this.parts[i].setId(id + i + 1);
        }
    }

    /** 頭の部位の置き場所。サーバー・クライアントの両方で、同期した行動と時刻から同じ式で出す。 */
    private void positionHead() {
        float s = this.getScale();
        this.head.setScale(s);
        double[] o = headOffset(getAction(), animSeconds(this.level().getGameTime()));
        double k = s / NOTES_SCALE;
        Vec3 f = forward();
        double cx = this.getX() + f.x * o[0] * k;
        double cy = this.getY() + o[1] * k;
        double cz = this.getZ() + f.z * o[0] * k;
        this.head.xo = this.head.getX();
        this.head.yo = this.head.getY();
        this.head.zo = this.head.getZ();
        this.head.xOld = this.head.getX();
        this.head.yOld = this.head.getY();
        this.head.zOld = this.head.getZ();
        // setPos は x・z が真ん中、y が足元
        this.head.setPos(cx, cy - this.head.getBbHeight() * 0.5D, cz);
    }

    /**
     * 頭(口の中心)の位置(前・上、ブロック、描画倍率 2 のとき)。NOTES の「口の中心」の表と各アニメの合図の時刻から、
     * 行動ごとに数点を直線でつないだ<b>おおよその</b>軌跡(アニメの揺れは追わない)。GameTest からも呼ぶ。
     */
    public static double[] headOffset(int action, float t) {
        return switch (action) {
            // 地中・地面の花の間は、蕾・花そのものが本体の当たり判定なので、頭の部位は地面の下へしまう
            // (殴る向きからはブロックが先に当たるので狙えない。爆発が両方に当たっても本体が 1 回にまとめる)
            case ACT_STALK, ACT_DEATH_STALK, ACT_AMBUSH, ACT_LURK, ACT_LURK_FLINCH, ACT_DEATH_BURIED -> new double[]{0.0D, BURIED_HEAD_UP};
            case ACT_EMERGE -> path(t, HEAD_EMERGE);
            case ACT_SUBMERGE -> path(t, HEAD_SUBMERGE);
            case ACT_OPEN -> path(t, HEAD_OPEN);
            case ACT_GAPE -> new double[]{3.4D, 4.1D};
            case ACT_SWALLOW -> path(t, HEAD_SWALLOW);
            case ACT_SPIT -> path(t, HEAD_SPIT);
            case ACT_ERUPT -> path(t, HEAD_ERUPT);
            case ACT_DEATH, ACT_DEATH_DIGEST -> path(t, HEAD_DEATH);
            case ACT_DEATH_OPEN -> path(t, HEAD_DEATH_OPEN);
            default -> new double[]{0.8D, HEAD_UP}; // idle・flinch・digest・digest_kick
        };
    }

    /** 地中・地面の花の間の頭の部位の高さ(地面の下)。 */
    static final double BURIED_HEAD_UP = -1.2D;

    // 頭の軌跡のキー: {秒, 前, 上} の並び(NOTES の口の中心の表と合図の時刻から。おおよそ)
    private static final double[] HEAD_EMERGE = {0.0D, 0.0D, BURIED_HEAD_UP, 0.3D, 0.0D, 1.0D, 1.2D, 0.6D, 7.4D, 1.6D, 0.8D, HEAD_UP};
    private static final double[] HEAD_SUBMERGE = {0.0D, 0.8D, HEAD_UP, 0.3D, 0.4D, 7.8D, 1.4D, 0.0D, BURIED_HEAD_UP};
    private static final double[] HEAD_OPEN = {0.0D, 0.8D, HEAD_UP, 0.35D, 0.2D, 7.6D, 1.1D, 3.4D, 4.1D};
    private static final double[] HEAD_SWALLOW = {0.0D, 3.4D, 4.1D, 0.12D, 4.4D, 2.3D, 0.3D, 4.4D, 1.9D, 0.45D, 4.2D, 2.1D,
            0.95D, 0.8D, HEAD_UP};
    private static final double[] HEAD_SPIT = {0.15D, 0.8D, HEAD_UP, 0.45D, 0.2D, 7.3D, 0.6D, SPIT_FWD, SPIT_UP,
            0.93D, 4.2D, 3.5D, 1.3D, 0.8D, HEAD_UP};
    private static final double[] HEAD_ERUPT = {0.0D, 0.0D, BURIED_HEAD_UP, 0.23D, 0.0D, 1.5D, 0.42D, 0.3D, 8.0D, 1.0D, 0.8D, HEAD_UP};
    private static final double[] HEAD_DEATH = {0.0D, 0.8D, HEAD_UP, 0.5D, 2.5D, 6.0D, 1.1D, 5.5D, 1.3D};
    private static final double[] HEAD_DEATH_OPEN = {0.0D, 3.4D, 4.1D, 0.35D, 2.0D, 6.0D, 1.1D, 5.5D, 1.3D};

    /** {@code {秒, 前, 上}} の並びを直線でつなぐ。範囲の外は端の値。 */
    private static double[] path(float t, double[] keys) {
        int n = keys.length / 3;
        if (t <= keys[0]) {
            return new double[]{keys[1], keys[2]};
        }
        for (int i = 1; i < n; i++) {
            double t1 = keys[i * 3];
            if (t <= t1) {
                double ta = keys[(i - 1) * 3];
                double u = (t - ta) / (t1 - ta);
                double fa = keys[(i - 1) * 3 + 1];
                double ua = keys[(i - 1) * 3 + 2];
                return new double[]{fa + (keys[i * 3 + 1] - fa) * u, ua + (keys[i * 3 + 2] - ua) * u};
            }
        }
        return new double[]{keys[(n - 1) * 3 + 1], keys[(n - 1) * 3 + 2]};
    }

    /** 頭の部位への被弾(体力は本体と共通)。 */
    boolean hurtFromPart(DamageSource source, float amount) {
        return this.hurt(source, amount);
    }

    // ------------------------------------------------------------------ 状態

    /** 体が今どこにあるか(当たり判定・影・描き方を決める)。 */
    public enum Posture {
        /** 地中(蕾の先だけ出ている)。 */
        BURIED,
        /** 地面に花を開いている。 */
        FLOWER,
        /** 地上に出ている(出入りの途中を含む)。 */
        SURFACED
    }

    public static Posture postureOf(int action) {
        return switch (action) {
            case ACT_STALK, ACT_DEATH_STALK -> Posture.BURIED;
            case ACT_AMBUSH, ACT_LURK, ACT_LURK_FLINCH, ACT_DEATH_BURIED -> Posture.FLOWER;
            default -> Posture.SURFACED;
        };
    }

    public Posture posture() {
        return postureOf(getAction());
    }

    public static boolean isLoop(int action) {
        return action == ACT_STALK || action == ACT_IDLE || action == ACT_GAPE || action == ACT_DIGEST || action == ACT_LURK;
    }

    /** 行動番号 → アニメの名前(GameTest から呼ぶ)。 */
    public static String animationName(int action) {
        return ANIM_NAMES[action];
    }

    public static int actionCount() {
        return ANIM_NAMES.length;
    }

    /**
     * 地上に出ている間は地面の下の胴({@code tail1})を隠す(NOTES の「実装の注意」。洞窟の天井・崖・水辺で見えてしまうため)。
     * 出入り・地面の花・地中の間は描く。
     */
    public static boolean hidesTail(int action) {
        return switch (action) {
            case ACT_IDLE, ACT_OPEN, ACT_GAPE, ACT_SWALLOW, ACT_DIGEST, ACT_DIGEST_KICK, ACT_SPIT, ACT_FLINCH,
                 ACT_DEATH, ACT_DEATH_OPEN, ACT_DEATH_DIGEST -> true;
            default -> false;
        };
    }

    /**
     * 地中・地面の花の間(stalk・ambush・lurk・lurk_flinch・death_stalk・death_buried)は、地面の下に埋まった胴の箱
     * ({@code trunk1_skin}〜{@code neck2_skin})と {@code tail1} を隠す(点検係 2026-10-01)。この間 body は −54〜−76 単位にあり、
     * 胴は地表から 7〜11 ブロック下まで垂れているので、描くと真下の洞窟から見上げたときに天井から胴がぶら下がって見えてしまう(未 runClient)。
     * 地表より上に出るのは頭(襟)と花弁・歯板だけ(胴の箱の上端は地表から最大 0.06 ブロック上 = death_buried の最後、襟の中)。
     */
    public static boolean hidesBuriedTrunk(int action) {
        return postureOf(action) != Posture.SURFACED;
    }

    /** 獲物を中に抱えていてよい行動(呑んだ直後の swallow・erupt の残り、消化、吐くまで)。 */
    static boolean holdsPrey(int action) {
        return action == ACT_SWALLOW || action == ACT_ERUPT || action == ACT_DIGEST || action == ACT_DIGEST_KICK
                || action == ACT_SPIT;
    }

    static boolean isDeath(int action) {
        return action >= ACT_DEATH;
    }

    /**
     * 倒れ方を今の行動(とそのアニメの秒)で選ぶ(NOTES の「状態ごとに被弾・死のアニメを分けてある」表。0 秒目がその状態の姿勢なので、
     * 補間 4 tick で飛ばずにつながる)。表に無い出入り・erupt は、その時刻の姿勢に近い方へ。
     */
    public static int deathActionFor(int action, float t) {
        return switch (action) {
            case ACT_STALK, ACT_DEATH_STALK -> ACT_DEATH_STALK;
            case ACT_EMERGE -> t < 0.5F ? ACT_DEATH_STALK : ACT_DEATH;
            case ACT_SUBMERGE -> t > 0.9F ? ACT_DEATH_STALK : ACT_DEATH;
            case ACT_AMBUSH -> t < 0.2F ? ACT_DEATH_STALK : ACT_DEATH_BURIED;
            case ACT_LURK, ACT_LURK_FLINCH, ACT_DEATH_BURIED -> ACT_DEATH_BURIED;
            case ACT_ERUPT -> t < 0.3F ? ACT_DEATH_BURIED : ACT_DEATH_DIGEST;
            case ACT_OPEN -> t < 0.35F ? ACT_DEATH : ACT_DEATH_OPEN; // 溜め(蕾を締めて反る)の間は、開いた花の death_open より静止に近い
            case ACT_GAPE, ACT_DEATH_OPEN -> ACT_DEATH_OPEN;
            case ACT_SWALLOW -> t < 0.8F ? ACT_DEATH_OPEN : ACT_DEATH_DIGEST; // 0.76 秒で下の花弁も閉じる → 呑んだ姿に近い
            case ACT_SPIT -> t < 0.4F ? ACT_DEATH_DIGEST : (t < 1.0F ? ACT_DEATH_OPEN : ACT_DEATH);
            case ACT_DIGEST, ACT_DIGEST_KICK, ACT_DEATH_DIGEST -> ACT_DEATH_DIGEST;
            default -> ACT_DEATH; // idle・flinch
        };
    }

    /**
     * 外からの被弾で流すアニメ(NOTES の「状態ごとに被弾・死のアニメを分けてある」表)。流さないなら −1。
     * 地上に立っている(idle、emerge の終わり際・submerge の出だし)= flinch、地面の花(lurk、開き切った後の ambush)= lurk_flinch、
     * 呑んでいる(digest・digest_kick)= digest_kick(吐き出すかどうかは {@link #hurt} が中から殴られた量で決める)。
     * 口を開いている(open・gape・swallow・spit)・地中を進む・突き上げ(erupt)・出入りの途中は止めない。
     */
    public static int hurtReactionFor(int action, float t) {
        return switch (action) {
            case ACT_IDLE -> ACT_FLINCH;
            case ACT_EMERGE -> t >= EMERGE_STAND_SEC ? ACT_FLINCH : -1;
            case ACT_SUBMERGE -> t <= SUBMERGE_STAND_SEC ? ACT_FLINCH : -1;
            case ACT_LURK -> ACT_LURK_FLINCH;
            case ACT_AMBUSH -> t >= AMBUSH_OPEN / 20.0F ? ACT_LURK_FLINCH : -1; // lurk_flinch の 0 秒目 = ambush の最後(開き切った花)
            case ACT_DIGEST, ACT_DIGEST_KICK -> ACT_DIGEST_KICK;
            default -> -1;
        };
    }

    /**
     * 傷を受ける姿か(2026-10-09 ユーザー指定「ダメージを受けるのは飲み込んで体を出している時と口を開けた時のみ」)。
     * 口を開けている = open の溜めの後・gape・swallow の花弁が閉じるまで・spit の口が閉じるまで({@link #OPEN_MOUTH_SEC} など)。
     * 呑んで体を出している = digest・digest_kick と、獲物を中に抱えた地上の姿(swallow・erupt・spit の残り)。
     * それ以外(地中・地面の花・地上で口を閉じて待つ・出入り)は受けない({@link #isInvulnerableTo})。
     * そのため {@link #hurtReactionFor} の flinch・lurk_flinch は被弾では流れなくなった(digest_kick は流れる)。
     *
     * @param t       今の行動のアニメの秒
     * @param holding 獲物を中に抱えているか
     */
    public static boolean exposedAction(int action, float t, boolean holding) {
        return switch (action) {
            case ACT_OPEN -> t >= OPEN_MOUTH_SEC;
            case ACT_GAPE, ACT_DIGEST, ACT_DIGEST_KICK -> true;
            case ACT_SWALLOW -> t < SWALLOW_CLOSE_SEC || holding;
            case ACT_SPIT -> t < SPIT_CLOSE_SEC || holding;
            default -> holding && postureOf(action) == Posture.SURFACED;
        };
    }

    /** いま傷を受ける姿か({@link #exposedAction})。 */
    public boolean isExposed() {
        return exposedAction(getAction(), animSeconds(), isHoldingPrey());
    }

    /**
     * 地面に潜伏しているか(地中を進む・地面に花を開いて待つ。2026-10-09 ユーザー指定「地面に潜伏している間は mob から見つからないように」)。
     * この間は Mob から見つからない({@link #canBeSeenAsEnemy}・{@link Events#onChangeTarget})。地上に出ている間(出入りの途中を含む)は見つかる。
     */
    public boolean isHiddenInGround() {
        return posture() != Posture.SURFACED;
    }

    public int getAction() {
        return this.entityData.get(DATA_ACTION);
    }

    public int getActionTicks() {
        return this.actionTicks;
    }

    /** 今の行動のアニメの秒(補間を除く)。 */
    private float animSeconds(long gameTime) {
        long start = this.entityData.get(DATA_ACTION_TIME) + this.entityData.get(DATA_TRANSITION);
        return Math.max(0.0F, (gameTime - start) / 20.0F);
    }

    private float animSeconds() {
        return Math.max(0.0F, (this.actionTicks - this.entityData.get(DATA_TRANSITION)) / 20.0F);
    }

    private void startAction(int action, int transition) {
        this.entityData.set(DATA_TRANSITION, transition);
        this.entityData.set(DATA_ACTION_TIME, (int) this.level().getGameTime());
        this.entityData.set(DATA_SEQ, this.entityData.get(DATA_SEQ) + 1);
        this.entityData.set(DATA_ACTION, action); // 最後に: 大きさの取り直し(onSyncedDataUpdated)が新しい行動を見るように
        this.actionTicks = 0;
        this.closeTicks = 0;
    }

    /** テスト・デバッグ用: 行動をいますぐ始める(補間なし)。 */
    public void forceAction(int action) {
        startAction(action, 0);
    }

    /** テスト用: Mob の振動を聞くか({@link #hearMobsForTests})。 */
    public void setHearMobsForTests(boolean hear) {
        this.hearMobsForTests = hear;
    }

    /** 行動が変わったら当たり判定の大きさを取り直す(サーバーでもクライアントでも呼ばれる)。 */
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_ACTION.equals(key)) {
            this.refreshDimensions();
        }
    }

    /** 当たり判定の大きさを体の場所で変える(登録の大きさ = 地上の姿がいちばん大きい)。体格(SCALE)は呼び出し元が掛ける。 */
    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        EntityDimensions full = super.getDefaultDimensions(pose);
        if (this.entityData == null) {
            return full;
        }
        return switch (posture()) {
            case BURIED -> EntityDimensions.scalable(BURIED_W, BURIED_H);
            case FLOWER -> EntityDimensions.scalable(full.width(), FLOWER_H);
            case SURFACED -> full;
        };
    }

    /** 体格 / NOTES の描画倍率(形の数値に掛ける)。 */
    private double k() {
        return this.getScale() / NOTES_SCALE;
    }

    public boolean isHunting() {
        return this.noiseTicks > 0 && this.noisePos != null;
    }

    @Nullable
    public Vec3 getNoisePos() {
        return this.noiseTicks > 0 ? this.noisePos : null;
    }

    /** 獲物を中に抱えているか({@link Events#onMount} が降車を止める)。 */
    public boolean isHoldingPrey() {
        return this.isAlive() && !this.isRemoved() && !this.releasing && this.isVehicle();
    }

    /** 呑まれた相手を描かないか(クライアント)。乗っているもの全部。 */
    public boolean hidesPassengers() {
        return true;
    }

    // ------------------------------------------------------------------ 聞く

    /**
     * 振動を受ける({@link VoramnesSenses} が配る。GameTest からも直接呼ぶ)。聞こえたら true。
     * 距離の決まりは {@link VoramnesSenses#noiseRange}。NoAI の個体は聞かない。
     */
    public boolean onVibration(Vec3 pos, Entity cause, boolean landing, @Nullable BlockState surface) {
        if (this.level().isClientSide() || !this.isAlive() || this.isNoAi()) {
            return false;
        }
        double range = VoramnesSenses.noiseRange(cause, landing, surface);
        if (range <= 0.0D) {
            return false;
        }
        double dx = pos.x - this.getX();
        double dz = pos.z - this.getZ();
        if (dx * dx + dz * dz > range * range || Math.abs(pos.y - this.getY()) > VoramnesSenses.MAX_DY) {
            return false;
        }
        LivingEntity maker = VoramnesSenses.noiseMaker(cause);
        if (maker != null && (!isFoe(maker) || (!this.hearMobsForTests && !(maker instanceof Player)))) {
            return false;
        }
        LivingEntity current = this.getTarget();
        if (maker != null && current != null && current != maker && current.isAlive() && this.noiseTicks > 0
                && !(maker instanceof Player && !(current instanceof Player))) {
            // 今の標的の音がまだ新しい: ほかの Mob の足音では替えない(群れに振り回されない。プレイヤーの振動は Mob の標的より先。10-09)
            return false;
        }
        hearVibration(pos, maker);
        return true;
    }

    /** 振動の点を覚えて泳ぎ出す。出どころが世界に居る相手なら標的にもする(地上に出たときに向く相手)。 */
    public void hearVibration(Vec3 pos, @Nullable LivingEntity source) {
        rememberNoise(pos);
        if (source != null && source != this.getTarget() && source.isAlive() && source.level() == this.level()
                && source.isAddedToLevel()) {
            super.setTarget(source);
        }
    }

    private void rememberNoise(Vec3 pos) {
        if (this.noisePos == null || this.noisePos.distanceToSqr(pos) > 1.0D) {
            this.stuckTicks = 0;
        }
        this.noisePos = pos;
        this.noiseTicks = NOISE_MEMORY;
    }

    private void forgetNoise() {
        this.noisePos = null;
        this.noiseTicks = 0;
        this.stuckTicks = 0;
    }

    /** よそ(殴られた・防衛の兵として呼ばれた)から標的が決まったら、その相手の居る場所を「聞いた」ことにする。 */
    @Override
    public void setTarget(@Nullable LivingEntity target) {
        LivingEntity before = this.getTarget();
        super.setTarget(target);
        LivingEntity now = this.getTarget();
        if (!this.level().isClientSide() && now != null && now != before) {
            rememberNoise(now.position());
        }
    }

    // ------------------------------------------------------------------ 毎 tick

    @Override
    public void tick() {
        this.noPhysics = true;
        super.tick();
        positionHead();
    }

    /** 行動は {@code aiStep} で進める(NoAI の個体でも進むので、GameTest が {@link #forceAction} / {@link #forceSwallow} で確かめられる)。 */
    @Override
    public void aiStep() {
        this.noPhysics = true;
        super.aiStep();
        this.setDeltaMovement(Vec3.ZERO);
        if (this.level().isClientSide() || !this.isAlive()) {
            return;
        }
        if (this.frontCooldown > 0) {
            this.frontCooldown--;
        }
        if (this.noiseTicks > 0 && --this.noiseTicks == 0) {
            forgetNoise();
        }
        applyPendingSpit();
        if (this.messageDelay > 0 && --this.messageDelay == 0 && this.getFirstPassenger() instanceof Player player) {
            player.displayClientMessage(Component.translatable(SWALLOWED_MESSAGE), true);
        }
        if (this.tickCount % 20 == 0) {
            dropStaleTarget();
        }
        detachStrayPassengers();
        // 抱えていてはいけない行動なのに誰かが乗っている(読み込み直し・/ride など)→ 降ろす
        if (this.isVehicle() && !holdsPrey(getAction())) {
            releasePrey(false);
        }
        this.actionTicks++;
        tickAction();
        tickTurn();
    }

    /**
     * 別の世界へ行ってしまった獲物を、位置を動かさずに切り離す(点検係 2026-10-01)。呑まれたプレイヤーをコマンドで別の次元へ送ると、
     * {@code ServerPlayer#teleportTo} の降車は {@link Events#onMount} に止められたまま次元を越え、こちらの乗客の表に残る
     * (新しい世界ではプレイヤーが tick されず、こちらの {@code positionRider} が古い世界の座標へ置き続け、消化のダメージも届く)。
     * 降りる位置({@link #getDismountLocationForPassenger})はこちらの世界の座標なので使わない({@code removeVehicle} だけ)。
     */
    private void detachStrayPassengers() {
        for (Entity rider : List.copyOf(this.getPassengers())) {
            if (rider.level() != this.level()) {
                this.releasing = true;
                rider.removeVehicle();
                this.releasing = false;
            }
        }
    }

    private void tickAction() {
        int act = getAction();
        int t = this.actionTicks;
        int tr = this.entityData.get(DATA_TRANSITION);
        switch (act) {
            case ACT_STALK -> tickStalk();
            case ACT_EMERGE -> {
                if (t == tr + EMERGE_BREAK) {
                    burstDirt(40, 1.2D);
                    playWorldSound(SoundEvents.GRAVEL_BREAK, 1.6F, 0.5F);
                    playWorldSound(SoundEvents.ROOTED_DIRT_BREAK, 1.6F, 0.6F);
                }
                if (t >= tr + EMERGE_LEN + MARGIN) {
                    this.idleTicks = 0;
                    startAction(ACT_IDLE, 0);
                }
            }
            case ACT_IDLE -> tickIdle();
            case ACT_SUBMERGE -> {
                if (t == tr + SUBMERGE_LEN - 6) {
                    burstDirt(20, 0.8D);
                }
                if (t >= tr + SUBMERGE_LEN + MARGIN) {
                    this.wanderPos = null;
                    startAction(ACT_STALK, 0);
                }
            }
            case ACT_OPEN -> {
                faceFront();
                if (t >= tr + OPEN_LEN + MARGIN) {
                    startAction(ACT_GAPE, 0);
                }
            }
            case ACT_GAPE -> {
                LivingEntity target = faceFront();
                if ((target != null && inLungeZone(target)) || t >= GAPE_MAX || (target == null && t >= 10)) {
                    startAction(ACT_SWALLOW, SHORT_TRANSITION);
                }
            }
            case ACT_SWALLOW -> {
                if (t == tr + SWALLOW_CATCH) {
                    catchFront();
                }
                if (t >= tr + SWALLOW_LEN + MARGIN) {
                    if (this.isVehicle()) {
                        startDigest();
                    } else {
                        this.frontCooldown = MISS_COOLDOWN;
                        this.idleTicks = 0;
                        startAction(ACT_IDLE, TRANSITION_TICKS);
                    }
                }
            }
            case ACT_DIGEST -> tickDigest();
            case ACT_DIGEST_KICK -> {
                tickDigest();
                if (getAction() == ACT_DIGEST_KICK && t >= tr + KICK_LEN + MARGIN) {
                    startAction(ACT_DIGEST, 0); // digest を 0 秒目から(digest_kick の最後 = digest の 0 秒目)
                }
            }
            case ACT_SPIT -> {
                if (t == tr + SPIT_EJECT - 2) {
                    playWorldSound(SoundEvents.LLAMA_SPIT, 2.0F, 0.45F);
                }
                if (t == tr + SPIT_EJECT) {
                    releasePrey(true);
                }
                if (t >= tr + SPIT_LEN + MARGIN) {
                    this.frontCooldown = SPIT_COOLDOWN;
                    this.idleTicks = 0;
                    this.idleLinger = IDLE_LINGER;
                    startAction(ACT_IDLE, 0);
                }
            }
            case ACT_AMBUSH -> {
                if (t == tr + AMBUSH_OPEN) {
                    burstDirt(30, 1.8D);
                }
                if (t >= tr + AMBUSH_LEN + MARGIN) {
                    startAction(foeInCatchZone() != null ? ACT_ERUPT : ACT_LURK, 0);
                }
            }
            case ACT_LURK -> {
                if (((t & 1) == 0 && foeInCatchZone() != null) || t >= LURK_MAX) {
                    startAction(ACT_ERUPT, SHORT_TRANSITION);
                }
            }
            case ACT_LURK_FLINCH -> {
                if (t >= tr + FLINCH_LEN + MARGIN) {
                    startAction(ACT_LURK, 0);
                }
            }
            case ACT_ERUPT -> {
                if (t == tr + ERUPT_CATCH) {
                    catchFromBelow();
                }
                if (t >= tr + ERUPT_LEN + MARGIN) {
                    if (this.isVehicle()) {
                        startDigest();
                    } else {
                        this.idleTicks = 0;
                        this.idleLinger = IDLE_LINGER;
                        startAction(ACT_IDLE, TRANSITION_TICKS);
                    }
                }
            }
            case ACT_FLINCH -> {
                if (t >= tr + FLINCH_LEN + MARGIN) {
                    startAction(ACT_IDLE, 0);
                }
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ 地中

    /**
     * 地中: 振動の点へ泳ぐ → 着いたら上に獲物が居るか見て ambush、居なければ覚えている間は聞き耳を立てて待つ。
     * 聞こえない間はゆっくりぶらつき、ときどき地上へ顔を出す。NoAI の個体は動かない。
     */
    private void tickStalk() {
        if (this.isNoAi()) {
            return;
        }
        if (this.tickCount % 20 == 0 && !VoramnesTerrain.isValidSurface(this.level(), this.blockPosition())) {
            // 足元の地面が無くなった(掘られた・水が来た・卵で妙な所に出た)→ 近くの地表へ
            Integer y = VoramnesTerrain.surfaceAt(this.level(), this.getBlockX(), this.getBlockZ(), this.getBlockY(), 2, 12);
            if (y != null) {
                this.setPos(this.getX(), y, this.getZ());
            } else if (!isHunting() && this.wanderPos == null) {
                // この列に地表が無い(蕾の上に水・溶岩を流された)→ 隣の乾いた列へ泳いで出る(点検係 2026-10-01。
                // 以前はその場に留まり、溶岩なら焼け死ぬまで動かなかった。追っている間は振動の点へ進むのでそのまま)
                this.wanderPos = nearbyDryColumn();
            }
        }
        if (isHunting()) {
            Vec3 goal = this.noisePos;
            double d = horizontalDistance(goal);
            if (d > ARRIVE) {
                if (stalkStep(goal, huntSpeed())) {
                    this.stuckTicks = 0;
                } else if (++this.stuckTicks > STUCK_GIVE_UP) {
                    // 真下へ行けない(床がハーフブロック・水の上・崖の上)→ 近ければ地上へ出て正面から、遠ければ諦める
                    if (d <= OPEN_R * k() && canRise()) {
                        startAction(ACT_EMERGE, TRANSITION_TICKS);
                    }
                    forgetNoise();
                }
            } else if ((this.tickCount & 3) == 0 && canRise() && foeInCatchZone() != null) {
                playWorldSound(SoundEvents.ROOTED_DIRT_BREAK, 1.4F, 0.6F);
                startAction(ACT_AMBUSH, TRANSITION_TICKS);
            }
            return;
        }
        if (this.wanderPos != null) {
            if (horizontalDistance(this.wanderPos) <= ARRIVE || !stalkStep(this.wanderPos, wanderSpeed())) {
                this.wanderPos = null;
            }
        } else if (this.random.nextInt(WANDER_CHANCE) == 0) {
            double x = this.getX() + (this.random.nextDouble() * 2.0D - 1.0D) * WANDER_RANGE;
            double z = this.getZ() + (this.random.nextDouble() * 2.0D - 1.0D) * WANDER_RANGE;
            if (VoramnesTerrain.surfaceAt(this.level(), Mth.floor(x), Mth.floor(z), this.getBlockY(), 3, 4) != null) {
                this.wanderPos = new Vec3(x, this.getY(), z);
            }
        }
        if (this.random.nextInt(PERISCOPE_CHANCE) == 0 && canRise()) {
            this.idleLinger = IDLE_LINGER + 20 + this.random.nextInt(60);
            startAction(ACT_EMERGE, TRANSITION_TICKS);
        }
    }

    /** 隣(1〜2 列先の 8 方向)の、地表のある列の真ん中。無ければ null。 */
    @Nullable
    private Vec3 nearbyDryColumn() {
        int bx = this.getBlockX();
        int bz = this.getBlockZ();
        for (int r = 1; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx += r) {
                for (int dz = -r; dz <= r; dz += r) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    Integer y = VoramnesTerrain.surfaceAt(this.level(), bx + dx, bz + dz, this.getBlockY(), 3, 4);
                    if (y != null) {
                        return new Vec3(bx + dx + 0.5D, y, bz + dz + 0.5D);
                    }
                }
            }
        }
        return null;
    }

    private double huntSpeed() {
        return this.getAttributeValue(Attributes.MOVEMENT_SPEED) * HUNT_FACTOR;
    }

    private double wanderSpeed() {
        return this.getAttributeValue(Attributes.MOVEMENT_SPEED) * WANDER_FACTOR;
    }

    private double horizontalDistance(Vec3 p) {
        return Math.sqrt(Mth.square(p.x - this.getX()) + Mth.square(p.z - this.getZ()));
    }

    /**
     * 地中を {@code goal} へ 1 歩進む。進む先の列の地表の高さへ上下する(上へ 4・下へ 6 まで)。
     * 真っすぐ進めなければ x だけ・z だけを試す。どれも駄目なら false(ブロックは壊さない)。
     */
    private boolean stalkStep(Vec3 goal, double speed) {
        double dx = goal.x - this.getX();
        double dz = goal.z - this.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1.0E-4D) {
            return true;
        }
        double step = Math.min(speed, d);
        double nx = this.getX() + dx / d * step;
        double nz = this.getZ() + dz / d * step;
        Integer y = columnSurface(nx, nz);
        if (y == null) {
            y = columnSurface(nx, this.getZ());
            if (y != null) {
                nz = this.getZ();
            } else {
                y = columnSurface(this.getX(), nz);
                if (y == null) {
                    return false;
                }
                nx = this.getX();
            }
        }
        this.setPos(nx, y, nz);
        this.wantedYaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        if (this.tickCount % 3 == 0) {
            burstDirt(2, 0.25D);
        }
        if (this.tickCount % 10 == 0) {
            playWorldSound(SoundEvents.GRAVEL_STEP, 0.5F, 0.55F + this.random.nextFloat() * 0.1F);
        }
        return true;
    }

    /** 進む先の地表の高さ(同じ列なら今の高さのまま。地表の判定は列が変わるときだけ)。 */
    @Nullable
    private Integer columnSurface(double x, double z) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        if (bx == this.getBlockX() && bz == this.getBlockZ()) {
            return this.getBlockY();
        }
        return VoramnesTerrain.surfaceAt(this.level(), bx, bz, this.getBlockY(), 4, 6);
    }

    /** 地上に出る・花を開く空きがあるか。 */
    private boolean canRise() {
        return VoramnesTerrain.hasHeadroom(this.level(), this.blockPosition(), Mth.ceil(HEADROOM * k()));
    }

    // ------------------------------------------------------------------ 地上

    /**
     * 地上: 近くの相手へ向いて口を開く。口の届かない足元に張り付いた相手には、潜って真下から呑みに行く(点検係 2026-10-01、仮)。
     * 相手が居なければしばらく待って潜る。遠くの振動が聞こえたら潜って追う。
     */
    private void tickIdle() {
        if (this.isNoAi()) {
            return;
        }
        this.idleTicks++;
        LivingEntity target = frontTarget();
        if (target != null) {
            this.idleTicks = 0;
            faceTowards(target);
            double d = horizontalDistance(target.position());
            if (d < mouthNear(target.getBbWidth() * 0.5D, k())) {
                // 根元の相手(口は前 2.6 からしか届かない)→ その点を「聞いた」ことにして潜る。地中から真下へ回って花を開く
                if (++this.closeTicks >= CLOSE_DIVE_TICKS) {
                    this.closeTicks = 0;
                    rememberNoise(target.position());
                    startAction(ACT_SUBMERGE, TRANSITION_TICKS);
                }
                return;
            }
            this.closeTicks = 0;
            if (this.frontCooldown <= 0 && d <= OPEN_R * k() && Math.abs(yawOff(target)) <= OPEN_ARC) {
                playWorldSound(SoundEvents.RAVAGER_ROAR, 1.2F, 0.75F);
                startAction(ACT_OPEN, TRANSITION_TICKS);
            }
            return;
        }
        this.closeTicks = 0;
        if (isHunting() && horizontalDistance(this.noisePos) > OPEN_R * k()) {
            startAction(ACT_SUBMERGE, TRANSITION_TICKS);
            return;
        }
        if (this.idleTicks > this.idleLinger) {
            startAction(ACT_SUBMERGE, TRANSITION_TICKS);
        }
    }

    /** 口を開いている間: 相手へ向き続ける(gape のアニメは上半身が獲物を追う)。今の相手を返す。 */
    @Nullable
    private LivingEntity faceFront() {
        LivingEntity target = frontTarget();
        if (target != null) {
            faceTowards(target);
        }
        return target;
    }

    /** 地上に出ている間に向く相手: 今の標的が見えていればそれ、居なければ近くの見える相手(5 tick ごとに探す)。 */
    @Nullable
    private LivingEntity frontTarget() {
        if (this.frontTarget != null && (!isFoe(this.frontTarget)
                || horizontalDistance(this.frontTarget.position()) > FRONT_LOSE_R * k())) {
            this.frontTarget = null;
        }
        if (--this.frontScan > 0) {
            return this.frontTarget;
        }
        this.frontScan = 5;
        LivingEntity target = this.getTarget();
        LivingEntity best = null;
        if (target != null && isFoe(target) && withinFront(target) && seesFromHead(target)) {
            best = target;
        } else {
            double r = FRONT_NOTICE_R * k();
            AABB box = new AABB(this.getX() - r, this.getY() - FRONT_DY * k(), this.getZ() - r,
                    this.getX() + r, this.getY() + FRONT_DY * k() + HEAD_UP * k(), this.getZ() + r);
            double bestD = r * r;
            for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, box, this::isFoe)) {
                double d = Mth.square(e.getX() - this.getX()) + Mth.square(e.getZ() - this.getZ());
                if (d <= bestD) {
                    best = e;
                    bestD = d;
                }
            }
            if (best != null && !seesFromHead(best)) {
                best = null;
            }
        }
        this.frontTarget = best;
        return best;
    }

    private boolean withinFront(LivingEntity e) {
        return horizontalDistance(e.position()) <= FRONT_NOTICE_R * k() && Math.abs(e.getY() - this.getY()) <= FRONT_DY * k();
    }

    /** 胴の中ほど({@link #SIGHT_UP})から相手の目まで見通せるか。 */
    private boolean seesFromHead(LivingEntity e) {
        Vec3 eye = this.position().add(0.0D, SIGHT_UP * k(), 0.0D);
        return this.level().clip(new ClipContext(eye, e.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this))
                .getType() == HitResult.Type.MISS;
    }

    /** 開いた口で呑みに行ける範囲に入ったか(= swallow の当たりで口が届く範囲。{@link #inMouthReach})。 */
    private boolean inLungeZone(LivingEntity e) {
        return inMouthReach(e);
    }

    /** swallow の当たりで口が届くか: 体の向きに沿った長方形({@link #mouthReach})と高さ(足元 −0.5〜{@link #SWALLOW_TOP})。 */
    private boolean inMouthReach(LivingEntity e) {
        Vec3 f = forward();
        double dx = e.getX() - this.getX();
        double dz = e.getZ() - this.getZ();
        double fw = dx * f.x + dz * f.z;
        double side = dx * -f.z + dz * f.x;
        double k = k();
        double dy = e.getY() - this.getY();
        return mouthReach(fw, side, e.getBbWidth() * 0.5D, k) && dy + e.getBbHeight() > -0.5D && dy < SWALLOW_TOP * k;
    }

    /**
     * 口が届く水平の範囲(GameTest から呼ぶ)。体の向きに沿って前 {@code forward}・横 {@code side}(ブロック)に真ん中がある、
     * 幅の半分 {@code halfWidth} の相手に届くか。{@code k} = 体格 / 描画倍率 2。前 (4.4 ± 1.8)·k・横 ± 1.8·k を、相手の幅の半分だけ広げる。
     */
    public static boolean mouthReach(double forward, double side, double halfWidth, double k) {
        return forward >= mouthNear(halfWidth, k) && forward <= (SWALLOW_FWD + SWALLOW_R) * k + halfWidth
                && Math.abs(side) <= SWALLOW_R * k + halfWidth;
    }

    /** 口が届くいちばん手前(前の距離)。これより根元に居る相手は正面からは呑めない(潜って真下から: {@link #tickIdle})。 */
    public static double mouthNear(double halfWidth, double k) {
        return (SWALLOW_FWD - SWALLOW_R) * k - halfWidth;
    }

    // ------------------------------------------------------------------ 呑む

    /** 花の上(真ん中から {@link #CATCH_R})に居る相手。今の標的を優先し、次に近い順。居なければ null。 */
    @Nullable
    LivingEntity foeInCatchZone() {
        double r = CATCH_R * k();
        AABB box = new AABB(this.getX() - r, this.getY() - 0.5D, this.getZ() - r,
                this.getX() + r, this.getY() + CATCH_TOP * k(), this.getZ() + r);
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, box, this::isFoe)) {
            double d = Mth.square(e.getX() - this.getX()) + Mth.square(e.getZ() - this.getZ());
            double reach = r + e.getBbWidth() * 0.5D;
            if (d > reach * reach) {
                continue;
            }
            if (e == this.getTarget()) {
                return e;
            }
            if (d < bestD) {
                best = e;
                bestD = d;
            }
        }
        return best;
    }

    /** erupt の当たり: 花の上の相手を 1 体呑み、呑めなかった相手は噛んで弾き飛ばす。 */
    private void catchFromBelow() {
        playWorldSound(SoundEvents.EVOKER_FANGS_ATTACK, 1.6F, 0.55F);
        burstDirt(40, 1.5D);
        LivingEntity prey = foeInCatchZone();
        if (prey != null && swallow(prey)) {
            bite(prey, false);
        }
        double r = CATCH_R * k();
        AABB box = new AABB(this.getX() - r, this.getY() - 0.5D, this.getZ() - r,
                this.getX() + r, this.getY() + CATCH_TOP * k(), this.getZ() + r);
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, box, this::isFoe)) {
            bite(e, true);
        }
    }

    /** swallow の当たり: 口の届く範囲({@link #inMouthReach})の相手を 1 体呑む(呑めない大きさなら噛む)。 */
    private void catchFront() {
        playWorldSound(SoundEvents.EVOKER_FANGS_ATTACK, 1.6F, 0.7F);
        double k = k();
        Vec3 c = this.position().add(forward().scale(SWALLOW_FWD * k));
        double r = (SWALLOW_R * Math.sqrt(2.0D)) * k + 1.0D; // 向きに沿った長方形を包む箱で拾い、長方形で絞る
        AABB zone = new AABB(c.x - r, this.getY() - 3.0D, c.z - r, c.x + r, this.getY() + SWALLOW_TOP * k, c.z + r);
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, zone, cand -> isFoe(cand) && inMouthReach(cand))) {
            if (e == this.getTarget()) {
                best = e;
                break;
            }
            double d = e.distanceToSqr(c);
            if (d < bestD) {
                best = e;
                bestD = d;
            }
        }
        if (best == null) {
            return;
        }
        if (swallow(best)) {
            bite(best, false);
            playWorldSound(SoundEvents.GENERIC_EAT, 1.5F, 0.5F);
        } else {
            bite(best, true);
        }
    }

    /** 噛む(攻撃力 × {@link #BITE_FACTOR}、防具は効く)。{@code shove} なら外へ弾き飛ばす。 */
    private void bite(LivingEntity e, boolean shove) {
        float dmg = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE) * BITE_FACTOR;
        e.hurt(this.damageSources().mobAttack(this), dmg);
        if (shove && !e.isPassenger()) {
            Vec3 away = new Vec3(e.getX() - this.getX(), 0.0D, e.getZ() - this.getZ());
            away = away.lengthSqr() < 1.0E-4D ? forward() : away.normalize();
            e.push(away.x * 0.8D, 0.45D, away.z * 0.8D);
            e.hurtMarked = true;
        }
    }

    /** 呑める相手か(ボス・大きすぎる相手・何かを乗せている相手は呑まない。ファルキムネスの canCarry に倣う)。 */
    public boolean canSwallow(LivingEntity e) {
        double k = k();
        return !e.getType().is(Tags.EntityTypes.BOSSES)
                && e.getBbWidth() <= MAX_PREY_W * k && e.getBbHeight() <= MAX_PREY_H * k
                && !e.isVehicle();
    }

    /**
     * {@code prey} を呑む(乗客として胴の中へ)。呑めなければ false。何かに乗っている相手は先に降ろす
     * (ほかの個体が抱えている獲物は降ろせないので奪わない —— startRiding の force は降車が止められても乗せてしまい、両方の乗客の表に残る)。
     */
    boolean swallow(LivingEntity prey) {
        if (this.isVehicle() || !canSwallow(prey)) {
            return false;
        }
        if (prey.isPassenger()) {
            prey.stopRiding();
            if (prey.isPassenger()) {
                return false;
            }
        }
        if (!prey.startRiding(this, true)) {
            return false;
        }
        this.releasing = false;
        this.spitMeter = 0.0F;
        this.digestTicks = 0;
        this.deadPreyTicks = 0;
        // 呑まれた Mob は外の相手への狙いを捨てる(胴の中から弓・魔法で撃たせない。新しく狙うのは Events#onChangeTarget が止める)
        if (prey instanceof Mob mob && mob.getTarget() != null && mob.getTarget() != this) {
            mob.setTarget(null);
        }
        // 合図は次の tick に(乗せた tick の終わりに乗客の同期が飛び、バニラの「スニークで降りる」の表示が先の合図を上書きするため)
        this.messageDelay = 2;
        return true;
    }

    /** テスト用: {@code prey} をいますぐ呑んで消化を始める(噛みつき・予兆なし)。 */
    public boolean forceSwallow(LivingEntity prey) {
        if (!swallow(prey)) {
            return false;
        }
        startDigest();
        return true;
    }

    /** 消化を始める。呑み込みの途中で中から十分殴られていたら、そのまま吐き出す(spit の 0 秒目 = digest の 0 秒目)。 */
    private void startDigest() {
        this.digestTicks = 0;
        this.deadPreyTicks = 0;
        startAction(this.spitMeter >= spitThreshold() ? ACT_SPIT : ACT_DIGEST, 0);
    }

    /**
     * 消化: {@link #DIGEST_INTERVAL} ごとに防具を貫く継続ダメージ(voramnes_digest)。相手が中で死んだら降ろして地上で待つ。
     * クリエイティブ・観戦者になった・長く生き延びた相手は吐き出す。
     */
    private void tickDigest() {
        Entity passenger = this.getFirstPassenger();
        if (!(passenger instanceof LivingEntity prey) || prey.isRemoved()) {
            releasePrey(false);
            this.idleTicks = 0;
            this.idleLinger = IDLE_LINGER / 2;
            startAction(ACT_IDLE, TRANSITION_TICKS);
            return;
        }
        if (!prey.isAlive()) {
            // 中で死んだ: 倒れる姿を見せないよう、消える(Mob は 20 tick)まで抱えたまま。プレイヤーはリスポーンを待たない
            if (++this.deadPreyTicks >= DEAD_PREY_WAIT) {
                releasePrey(false);
                this.idleTicks = 0;
                this.idleLinger = IDLE_LINGER / 2;
                startAction(ACT_IDLE, TRANSITION_TICKS);
            }
            return;
        }
        if (prey instanceof Player player && (player.isCreative() || player.isSpectator())) {
            startAction(ACT_SPIT, SHORT_TRANSITION);
            return;
        }
        this.digestTicks++;
        if (prey instanceof Player player && this.digestTicks % SWALLOWED_MESSAGE_INTERVAL == 0) {
            player.displayClientMessage(Component.translatable(SWALLOWED_MESSAGE), true);
        }
        if (this.digestTicks % DIGEST_INTERVAL == 0 && this.level() instanceof ServerLevel server) {
            prey.invulnerableTime = 0;
            prey.hurt(ModDamageTypes.voramnesDigest(server, this), digestDamage());
            playWorldSound(SoundEvents.SLIME_SQUISH, 1.2F, 0.45F + this.random.nextFloat() * 0.1F);
        }
        if (this.digestTicks >= DIGEST_MAX && prey.isAlive()) {
            startAction(ACT_SPIT, SHORT_TRANSITION);
        }
    }

    /** 胃の中の 1 回ぶんのダメージ。 */
    public float digestDamage() {
        return (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE) * DIGEST_FACTOR;
    }

    /** 吐き出すまでに中から減らす体力。 */
    public float spitThreshold() {
        return this.getMaxHealth() * SPIT_FRACTION;
    }

    /**
     * 獲物を降ろす。{@code spit} なら口から前へ吐き出す({@link #getDismountLocationForPassenger} が口の点を返し、
     * 勢いは次の tick に掛ける)。プレイヤーの位置の同期はバニラ(ServerEntity が降りたプレイヤーを降車位置へ teleport する)。
     */
    private void releasePrey(boolean spit) {
        Entity prey = this.getFirstPassenger();
        this.releasing = true;
        this.spitting = spit;
        this.ejectPassengers();
        this.spitting = false;
        this.releasing = false;
        if (prey != null) {
            this.spatUuid = prey.getUUID();
            this.spatUntil = this.tickCount + SPAT_IMMUNE;
            if (spit) {
                Vec3 f = forward();
                this.pendingSpit = prey;
                this.pendingSpitVelocity = new Vec3(f.x * 0.45D, 0.2D, f.z * 0.45D);
            }
        }
        this.spitMeter = 0.0F;
        this.digestTicks = 0;
        this.deadPreyTicks = 0;
    }

    private void applyPendingSpit() {
        Entity prey = this.pendingSpit;
        if (prey == null) {
            return;
        }
        this.pendingSpit = null;
        if (prey.isAlive() && !prey.isPassenger()) {
            prey.setDeltaMovement(this.pendingSpitVelocity);
            prey.hurtMarked = true;
        }
    }

    /** 降りる位置: 吐き出しなら口の中心(入らなければ根元)、それ以外(死んだ・読み込み直し)は根元の地面。 */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        if (this.spitting) {
            double k = k();
            Vec3 mouth = this.position().add(forward().scale(SPIT_FWD * k))
                    .add(0.0D, SPIT_UP * k - passenger.getBbHeight() * 0.5D, 0.0D);
            if (fits(passenger, mouth)) {
                return mouth;
            }
        }
        Vec3 base = this.position();
        if (fits(passenger, base)) {
            return base;
        }
        double ring = this.getBbWidth() * 0.5D + passenger.getBbWidth() * 0.5D + 0.3D;
        for (int i = 0; i < 8; i++) {
            float yaw = this.getYRot() + i * 45.0F;
            Vec3 p = base.add(-Mth.sin(yaw * Mth.DEG_TO_RAD) * ring, 0.0D, Mth.cos(yaw * Mth.DEG_TO_RAD) * ring);
            if (fits(passenger, p)) {
                return p;
            }
        }
        return base;
    }

    private boolean fits(LivingEntity passenger, Vec3 feet) {
        return this.level().noCollision(passenger, passenger.getDimensions(Pose.STANDING).makeBoundingBox(feet));
    }

    /** 呑んだ獲物は胴の中ほど(胴の塊 trunk4_skin のあたり)に置く。 */
    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float partialTick) {
        double up = PREY_UP * k() - passenger.getBbHeight() * 0.5D + passenger.getVehicleAttachmentPoint(this).y;
        return new Vec3(0.0D, up, 0.0D);
    }

    /** 呑まれた獲物に操縦させない。 */
    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        return null;
    }

    /** 呑まれたプレイヤーが中から殴れるように。 */
    @Override
    public boolean canRiderInteract() {
        return true;
    }

    @Override
    public boolean shouldRiderSit() {
        return false;
    }

    /** 自分から乗ることはできない(呑むときは force で乗せる。読み込み直し・同期も force)。 */
    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return false;
    }

    /** 獲物を抱えている間は消えない(遠くで消えると獲物ごと居なくなる)。 */
    @Override
    public boolean requiresCustomPersistence() {
        return super.requiresCustomPersistence() || this.isVehicle();
    }

    /**
     * 敵か(自分・呑まれている相手(自分の中も、ほかの個体の中も)・汚染側の仲間・クリエイティブ/観戦者・吐いたばかりの相手・防具立ては除く)。
     * ほかの個体の胃の中の相手を除くのは点検係 2026-10-01(以前は、地上の個体が隣の個体の胴の中の獲物へ口を開き、呑めずに噛みえた)。
     */
    boolean isFoe(LivingEntity e) {
        if (e == this || !e.isAlive() || e.level() != this.level() || e.getVehicle() instanceof VoramnesEntity || !e.attackable()) {
            return false;
        }
        if (e instanceof Player p && (p.isCreative() || p.isSpectator())) {
            return false;
        }
        if (this.spatUuid != null && this.tickCount < this.spatUntil && e.getUUID().equals(this.spatUuid)) {
            return false;
        }
        if (e == this.getTarget()) {
            return true;
        }
        return MobGroup.of(e) == null;
    }

    private void dropStaleTarget() {
        LivingEntity t = this.getTarget();
        if (t == null) {
            return;
        }
        boolean gone = !t.isAlive() || t.level() != this.level()
                || (t instanceof Player p && (p.isCreative() || p.isSpectator()))
                || (!isHunting() && this.distanceToSqr(t) > Mth.square(this.getAttributeValue(Attributes.FOLLOW_RANGE)));
        if (gone) {
            this.setTarget(null);
        }
    }

    // ------------------------------------------------------------------ 向き

    private void faceTowards(Entity e) {
        double dx = e.getX() - this.getX();
        double dz = e.getZ() - this.getZ();
        if (dx * dx + dz * dz > 1.0E-4D) {
            this.wantedYaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        }
    }

    /** 相手が正面から何度ずれているか(度、±180)。 */
    private float yawOff(Entity e) {
        float want = (float) (Mth.atan2(e.getZ() - this.getZ(), e.getX() - this.getX()) * Mth.RAD_TO_DEG) - 90.0F;
        return Mth.wrapDegrees(want - this.getYRot());
    }

    private void tickTurn() {
        if (!Float.isNaN(this.wantedYaw)) {
            float max = posture() == Posture.BURIED ? TURN_BURIED : TURN_SURFACED;
            this.setYRot(Mth.approachDegrees(this.getYRot(), this.wantedYaw, max));
            this.wantedYaw = Float.NaN;
        }
        this.yBodyRot = this.getYRot();
        this.yHeadRot = this.getYRot();
    }

    /** 体の向いている方向(水平の単位ベクトル)。 */
    private Vec3 forward() {
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
    }

    // ------------------------------------------------------------------ 被弾・倒れる

    /**
     * 傷を受けるのは {@link #isExposed 口を開けた時と、呑んで体を出している時}だけ(2026-10-09 ユーザー指定)。それ以外は何も通さない
     * (クリエイティブのプレイヤーの攻撃も。仮決め)。{@code /kill}・奈落({@code BYPASSES_INVULNERABILITY})だけは通す。
     * 殴ってきた相手の居場所は、受けなくても「聞く」({@link #hurt})。
     */
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        if (!source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) && !isExposed()) {
            return true;
        }
        return super.isInvulnerableTo(source);
    }

    /**
     * 潜伏している間は Mob から見つからない({@link #isHiddenInGround})。バニラの標的選び({@code TargetingConditions} の {@code canAttack})は
     * これを見るので、ゾンビ・アイアンゴーレム・同盟軍の兵士などは新しく狙わず、狙っていた相手も潜った時点で見失う({@code TargetGoal#canContinueToUse})。
     * {@code setTarget} を直接呼ぶ処理は {@link Events#onChangeTarget} が止める。機関砲台も撃たない({@code GunTurretTargeting#isEnemy}、仮決め)。
     */
    @Override
    public boolean canBeSeenAsEnemy() {
        return !isHiddenInGround() && super.canBeSeenAsEnemy();
    }

    /**
     * 被弾: 壁の中のダメージ(地中をすり抜けるので)は捨てる。同じ tick の同じダメージ源は 1 回だけ(本体と頭の部位の両方に当たる爆発など)。
     * 殴ってきた相手の居場所は「聞いた」ことにする。状態ごとの反応は NOTES の表のとおり({@link #hurtReactionFor}: 地上 = flinch、
     * 呑んでいる = digest_kick、地面の花 = lurk_flinch、口を開いている・地中を進む = 反応なし)。呑んでいる間は中から殴られた量(外からは半分)で吐き出す。
     * 2026-10-09 から傷を受けるのは {@link #isExposed} の間だけ({@link #isInvulnerableTo})なので、flinch・lurk_flinch は被弾では流れない。
     * 受けない姿でも、殴ってきた相手の居場所は聞く(下の hearVibration は super.hurt の前)。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypes.IN_WALL)) {
            return false;
        }
        if (this.level().isClientSide()) {
            return super.hurt(source, amount);
        }
        long now = this.level().getGameTime();
        if (now == this.lastHitTime && source == this.lastHitSource) {
            return false;
        }
        this.lastHitTime = now;
        this.lastHitSource = source;
        Entity passenger = this.getFirstPassenger();
        Entity attacker = source.getEntity();
        boolean fromInside = attacker != null && attacker == passenger;
        // 殴ってきた敵の居場所を「聞いた」ことにする(クリエイティブ・汚染側の仲間の流れ弾では動かない。点検係 2026-10-01)
        if (attacker instanceof LivingEntity living && !fromInside && this.isAlive() && isFoe(living)) {
            hearVibration(living.position(), living);
        }
        float before = this.getHealth();
        boolean hurt = super.hurt(source, amount);
        if (!hurt || !this.isAlive()) {
            return hurt;
        }
        float lost = Math.max(0.0F, before - this.getHealth());
        int act = getAction();
        int reaction = hurtReactionFor(act, animSeconds());
        // 獲物を抱えている間は、呑み込みの途中(swallow・erupt の残り)でも中から殴られた量を数える(点検係 2026-10-01。
        // 以前は digest に入るまでの約 1 秒の被弾を捨てていた)。足りていれば digest に入らず吐き出す({@link #startDigest})
        if (this.isVehicle() && holdsPrey(act) && act != ACT_SPIT) {
            this.spitMeter += fromInside ? lost : lost * OUTSIDE_FACTOR;
        }
        if (act == ACT_DIGEST || act == ACT_DIGEST_KICK) {
            startAction(this.spitMeter >= spitThreshold() ? ACT_SPIT : ACT_DIGEST_KICK, SHORT_TRANSITION);
        } else if (reaction >= 0) {
            if (act == ACT_EMERGE) {
                this.idleTicks = 0; // 出てくる途中で殴られた: 出切ったのと同じく、地上で待つ時間を数え直す
            }
            startAction(reaction, SHORT_TRANSITION);
        }
        return hurt;
    }

    @Override
    public void die(DamageSource source) {
        if (!this.level().isClientSide()) {
            startAction(deathActionFor(getAction(), animSeconds()), TRANSITION_TICKS);
        }
        this.releasing = true;
        super.die(source);
        if (!this.level().isClientSide()) {
            releasePrey(false);
        }
        this.releasing = false;
    }

    /** 倒れるアニメの間は消さない。バニラの横倒しは描画で切ってある(VoramnesRenderer)。 */
    @Override
    protected void tickDeath() {
        this.deathTime++;
        if (this.deathTime >= DEATH_TICKS && !this.level().isClientSide() && !this.isRemoved()) {
            this.level().broadcastEntityEvent(this, (byte) 60);
            this.remove(RemovalReason.KILLED);
        }
    }

    // ------------------------------------------------------------------ 物理(地中をすり抜ける)

    /** 速度では動かない(地中は {@link #stalkStep} の {@code setPos}、地上では根元から動かない)。 */
    @Override
    public void travel(Vec3 travelVector) {
        this.calculateEntityAnimation(false);
    }

    @Override
    public void knockback(double strength, double x, double z) {
    }

    /**
     * 経路は引かない。行動は全部 {@link #aiStep} が {@code setPos} で動かすが、グループの Mob には全種まとめて
     * {@code GuidedHuntGoal}(フィロセンスの誘導)・{@code SmallRaidHuntGoal}(小規模レイド)などの歩く Goal が差し込まれる。
     * 経路が引けると {@code MoveControl} が体の向きを 1 tick 90° まで勝手に回し、{@link #tickTurn} の向きとけんかするので、
     * ここで最初から引けないようにしておく(幅 2.5 の地上の体では、どのみち経路探索は失敗する)。
     */
    @Override
    protected PathNavigation createNavigation(Level level) {
        return new GroundPathNavigation(this, level) {
            @Override
            protected boolean canUpdatePath() {
                return false;
            }
        };
    }

    /** ピストンに押されない(地中の体は地面ごと動かされるものではない。押されると潜れない所へずれる)。 */
    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void pushEntities() {
    }

    @Override
    protected void doPush(Entity entity) {
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    /** 息は切れない(地中に棲む。蕾の先に水を掛けられても溺れない)。 */
    @Override
    protected int decreaseAirSupply(int air) {
        return air;
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
    }

    @Override
    public boolean onClimbable() {
        return false;
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    /** 自分の「足音」は出さない(ほかのヴォラムネス・スカルクセンサーに拾われない)。 */
    @Override
    protected MovementEmission getMovementEmission() {
        return MovementEmission.NONE;
    }

    // ------------------------------------------------------------------ 湧き

    /**
     * 湧きの最後の関門(足場係の口)。湧かせ役が置いた地表のすぐ近く(上下 1)に、下へ潜れる地面が {@link VoramnesTerrain#SOLID_DEPTH}
     * マス続き・液体の無い地表があればそこへ置き直し、地中(stalk)のまま世界に入る(= 湧いてすぐ潜っている)。無ければ湧かない。
     */
    @Override
    public boolean acceptOvergrownSpawn(ServerLevel level) {
        Integer y = VoramnesTerrain.surfaceAt(level, this.getBlockX(), this.getBlockZ(), this.getBlockY(), 1, 1);
        if (y == null) {
            return false;
        }
        this.moveTo(this.getX(), y, this.getZ(), this.getYRot(), 0.0F);
        if (getAction() != ACT_STALK) {
            startAction(ACT_STALK, 0);
        }
        return true;
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (!this.level().isClientSide()) {
            VoramnesSenses.register(this);
        }
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        if (!this.level().isClientSide()) {
            VoramnesSenses.unregister(this); // 耳の表はサーバーのスレッドだけが触る(一人用ではクライアントの個体も同じ JVM に居る)
        }
    }

    // ------------------------------------------------------------------ 保存

    /**
     * 保存するのは体の場所(地中・地上・呑んでいる)と消化の勘定だけ。読み込み直すと、地中なら stalk、地上なら idle、呑んでいれば digest から
     * (アニメの途中からは始めない)。呑んでいた獲物(Mob)は乗客として一緒に保存・復元される(バニラ)。プレイヤーは抜けるときに
     * この個体ごと自分のデータへ持って行き、戻ると乗ったまま復元される(バニラの RootVehicle)。獲物が戻らなかった digest は次の tick に idle へ。
     */
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        int act = getAction();
        String place = holdsPrey(act) && this.isVehicle() ? "digesting"
                : (postureOf(act) == Posture.SURFACED && !isDeath(act) ? "surfaced" : "buried");
        tag.putString("VoramnesPlace", place);
        tag.putFloat("VoramnesSpitMeter", this.spitMeter);
        tag.putInt("VoramnesDigestTicks", this.digestTicks);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.spitMeter = tag.getFloat("VoramnesSpitMeter");
        this.digestTicks = tag.getInt("VoramnesDigestTicks");
        switch (tag.getString("VoramnesPlace")) {
            // 吐き出しの途中(もう十分殴られていた)で保存されたなら、吐き出しからやり直す(体力・属性は super が先に読んでいる)
            case "digesting" -> startAction(this.spitMeter >= spitThreshold() ? ACT_SPIT : ACT_DIGEST, 0);
            case "surfaced" -> startAction(ACT_IDLE, 0);
            default -> startAction(ACT_STALK, 0);
        }
    }

    // ------------------------------------------------------------------ 音・粒子(仮: バニラの音。専用の音は未作成)

    /** 地表の土を跳ね上げる(足元のブロックの粒子)。 */
    private void burstDirt(int count, double spread) {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        BlockState ground = this.level().getBlockState(this.blockPosition().below());
        if (ground.isAir()) {
            ground = Blocks.DIRT.defaultBlockState();
        }
        server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), this.getX(), this.getY() + 0.1D, this.getZ(),
                count, spread * k(), 0.1D, spread * k(), 0.15D);
    }

    private void playWorldSound(SoundEvent sound, float volume, float pitch) {
        this.level().playSound(null, this.getX(), this.getY() + 1.0D, this.getZ(), sound, SoundSource.HOSTILE, volume, pitch);
    }

    /** 地上に出ている間だけ唸る(地中では黙っている)。 */
    @Nullable
    @Override
    protected SoundEvent getAmbientSound() {
        return posture() == Posture.SURFACED ? SoundEvents.RAVAGER_AMBIENT : null;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.RAVAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.RAVAGER_DEATH;
    }

    @Override
    public float getVoicePitch() {
        return 0.6F + (this.random.nextFloat() - 0.5F) * 0.1F;
    }

    @Override
    protected float getSoundVolume() {
        return 1.5F;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
    }

    // ------------------------------------------------------------------ 描画まわり

    /**
     * 描画の打ち切り: 地上に出た姿の柱から水平に 8・上に 1.5 広げる(NOTES の表。真上の花を見上げたとき・覆いかぶさる頭を真下から見たとき・
     * 倒れた頭の側から見たときに消えないように)。地中・花の間も同じ箱にする(出入りのアニメが柱の高さまで伸びるため)。
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        float s = this.getScale();
        EntityDimensions full = this.getType().getDimensions();
        double half = full.width() * s * 0.5D + 8.0D;
        return new AABB(this.getX() - half, this.getY() - 1.0D, this.getZ() - half,
                this.getX() + half, this.getY() + full.height() * s + 1.5D, this.getZ() + half);
    }

    // ------------------------------------------------------------------ アニメ

    /**
     * 層は 1 つ({@code main})。脚が無く体ごと 1 本のアニメで動くので、2 層に分ける理由が無い。
     * 行動番号・出し直しの番号・補間の長さはサーバーが同期する(補間の長さを行動ごとに変えるので、当たりの tick = 補間 + 秒 × 20 が両側で揃う)。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", TRANSITION_TICKS, this::mainState));
    }

    private PlayState mainState(AnimationState<VoramnesEntity> state) {
        AnimationController<VoramnesEntity> c = state.getController();
        c.transitionLength(this.entityData.get(DATA_TRANSITION));
        int seq = this.entityData.get(DATA_SEQ);
        if (seq != this.clientSeq) {
            this.clientSeq = seq;
            c.forceAnimationReset();
        }
        int act = getAction();
        if (act < 0 || act >= ANIMS.length) {
            act = ACT_STALK;
        }
        return state.setAndContinue(ANIMS[act]);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    /** 頭だけ回すバニラの見回しを止める(向きは {@link #tickTurn} だけが決める)。 */
    static final class FixedLookControl extends LookControl {
        FixedLookControl(Mob mob) {
            super(mob);
        }

        @Override
        public void tick() {
        }
    }

    // ------------------------------------------------------------------ 呑まれた獲物は降りられない・中で窒息しない

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
    public static final class Events {
        /** 呑まれている間は自分から降りられない(降車はサーバーでしか起きない。クライアントで止めると食い違う)。死んだ獲物は降ろす。 */
        @SubscribeEvent
        public static void onMount(EntityMountEvent event) {
            if (event.isDismounting() && !event.getLevel().isClientSide()
                    && event.getEntityBeingMounted() instanceof VoramnesEntity worm && worm.isHoldingPrey()
                    && event.getEntityMounting().isAlive()) {
                event.setCanceled(true);
            }
        }

        /**
         * 呑まれている獲物は壁の中・押し合いで傷つかない(胴の中は地上 3.3 ブロックの高さなので、低い天井・葉の下では頭がブロックに入る。
         * ファルキムネスの「天井が低いと獲物が窒息する」の対策)。
         */
        @SubscribeEvent
        public static void onIncomingDamage(LivingIncomingDamageEvent event) {
            if (event.getEntity().getVehicle() instanceof VoramnesEntity
                    && (event.getSource().is(DamageTypes.IN_WALL) || event.getSource().is(DamageTypes.CRAMMING))) {
                event.setCanceled(true);
            }
        }

        /**
         * 呑まれている Mob は外の相手を新しく狙えない(描かれない胴の中から、スケルトンが矢を・エヴォーカーが牙を出すのを防ぐ)。
         * 呑んだヴォラムネス本体を狙うのは通す(中から殴り返す = 吐き出させる手段は Mob にも残す)。
         * 地面に潜伏しているヴォラムネスは誰も新しく狙えない(2026-10-09 ユーザー指定。{@link VoramnesEntity#canBeSeenAsEnemy} を通らずに
         * {@code setTarget} を呼ぶ処理の分)。
         */
        @SubscribeEvent
        public static void onChangeTarget(LivingChangeTargetEvent event) {
            LivingEntity target = event.getNewAboutToBeSetTarget();
            if (target == null || event.getEntity().level().isClientSide()) {
                return;
            }
            if (target instanceof VoramnesEntity hidden && hidden.isHiddenInGround()) {
                event.setCanceled(true);
                return;
            }
            if (event.getEntity().getVehicle() instanceof VoramnesEntity worm && target != worm) {
                event.setCanceled(true);
            }
        }

        private Events() {
        }
    }
}
