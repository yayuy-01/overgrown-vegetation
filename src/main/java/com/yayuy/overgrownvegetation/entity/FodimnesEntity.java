package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 「フォディムネス」— グループ5(適応種)の地中の種。砂虫型のワーム(頭 + 節 16 + 尾、節の間隔 0.80 ブロック)。
 * この実体は<b>頭</b>。節と尾は {@link FodimnesPart}(PartEntity)。モデルの手引きは {@code tools/g5-models/out/fodimnes/NOTES.md}。
 *
 * <h2>ユーザー指定(2026-10-01、動作技術の質問の答えとして。SPEC の原文)</h2>
 * <ul>
 *   <li>体: 16 節、節の間隔 0.80 ブロック({@link #SPACING})、尾に向かって細くなる({@link #TAPER})。動く場所: 地中。</li>
 *   <li>節の並べ方: 頭の通った道をたどる(パス追従)。頭の位置と回転を 1 tick ごとにリングバッファへ積み、各節は履歴を
 *       間隔 × 番号ぶんさかのぼった点。スプラインでつなぎ弧長で等間隔({@link FodimnesBody})。</li>
 *   <li>うねり: なし(道をなぞるだけ。横の波は足さない)。</li>
 *   <li>ひねり: 頭の roll を履歴に積み、位置と同じ遅れで節ごとに読む。旋回の速さに比例して内側へ傾く(バンク、{@link #updateBank})。</li>
 *   <li>地中への出入り: 節は頭の開けた穴をそのまま通る。節が地面を出入りするたびに土のパーティクル。地面の下の節は描かない
 *       ({@link #updateClientVisuals})。</li>
 *   <li>本体 1 体 + 節の当たり判定は PartEntity。同期するのは頭だけで、節の位置はサーバーとクライアントで同じ式。
 *       節の向きは方向ベクトルで持ち、描くときはクォータニオンで前の向きからの最小回転(真上でもねじれない、{@link FodimnesFrames})。
 *       角度の補間は差を {@code Mth.wrapDegrees} で ±180° に直す。</li>
 *   <li>ブロック破壊は無し({@code noPhysics} ですり抜ける。窒息しない)。すべての節に攻撃が当たり、体力は共通({@link FodimnesPart#hurt})。</li>
 * </ul>
 *
 * <h2>能力(採用案)と流れ(仮決め。10-01 夜・10-02 のユーザー指定で動き方を広げた)</h2>
 * 採掘の音({@code BlockEvent.BreakEvent} のプレイヤー)を聞く距離 = 追跡の距離の属性(mobs.json の followRange 24)。
 * 洞窟の中(空が見えない所)に居るプレイヤーと、過繁茂側以外の Mob にも、同じ距離(スニークのプレイヤーは 1/3)で気付く(10-02。プレイヤーが先)。
 * <pre>
 * ROAM(常に地中をうろつく。洞窟があれば床の下を進みながら BREACH = 床から飛び出して放物線のアーチを描き、
 *      反対側の床へ潜る、を続けて繰り返す。ときどき地中だけの寄り道)。REST は湧いた・読み込んだ直後の 1 tick だけ。
 *   → 音・洞窟のプレイヤーか Mob → HUNT。<b>攻めの間も止まらない</b>(2026-10-09 ユーザー指定)。相手の足元の上が開けた洞窟・地表
 *      (空き 3 マス以上)なら、相手の床の下を回り、回った先からアーチの始まりへ寄せながら予兆(床にひび・かちかち、約 1.2 秒)を見せて、
 *      着いたらそのまま床から跳びかかり、相手の上半身を通るアーチで噛む(STRIKE。1.5〜2.5 秒おきに繰り返す。合間は相手の下を回る)。
 *      狭い所(高さ 2 の坑道 = ブランチマイニング)では: 真横の壁(無ければ床)の奥の助走の点へ → BURST(壁を破る瞬間に面へ着く速さで寄せる。
 *      壁にひび・かちかち)→ SNAP(壁を破ってそのまま相手の胴へ突っ込み、噛む)→ CROSS(通路を横切って反対側の壁か床へ潜り直す)
 *      → 3 秒は相手の下を回り、まだ居れば繰り返す。
 * 8 秒(音も気付きも無い)で見失う → ROAM。10-09 まであった LURK(壁の中で口の先を出して待つ)・RETRACT(口を引っ込める)は外した。
 * 空気の中に出てしまった(卵で出した・潜り損ねた)ときは DIVE(下の地面へ潜る)→ 体(節・尾)が地面から出ていれば SETTLE
 * (頭の開けた穴から体を全部引き込むまで、斜め下 → 水平に地中を進む)→ ROAM。
 * 地上への遠出({@link Outing}。段階とは別に持つ): 2〜4 分おき(最初は 30〜90 秒後)、うろつく間に地表の Mob(空が見え、足元に床。
 * 水平 32・上 64 ブロックまで)を感じ取ると ASCEND(獲物の足元の床の下へ斜めに上る。洞窟は突っ切る)→ 気付いたら HUNT(空の下でも跳びかかる)、
 * 地表の段階では地表の下を進みながら地表でも跳ね、次の地表の Mob を探す → 獲物が居ないまま 10 秒か、始めて 60 秒で DESCEND
 * (始めた高さへ斜め下に戻る)→ いつものうろつき。地表のプレイヤーには目では気付かない(音・殴られたときだけ。仮決め)。
 * 地上に棲む個体({@link Habitat#SURFACE}。2026-10-09 のユーザー指定「地下限定ではなく地上でも湧くように」で、地表で湧いた・地表で卵から出した個体):
 * ずっと遠出の地表の段階と同じ動き(地表のすぐ下を進み、地表の Mob に気付き、地表で跳ねる)で、遠出も戻りもしない。
 * </pre>
 * ROAM・BREACH は 2026-10-01 夜のユーザー指定(「地中を常に潜り続けるのではなく、ちょくちょく洞窟内に飛び出してアーチを描くようにジャンプ」。
 * 最初の注文書の「動く場所: 地中」を広げた)、10-02 にユーザー指定で「常に動き続ける・動いている間の 8 割くらいは洞窟の表面を跳ねる・
 * ブランチマイニングの音か洞窟に来たプレイヤーで襲いに行く」。うろつきの跳ねには当たり(ダメージ)は無い。跳びかかりの形は仮決め。
 * SETTLE は 2026-10-01 の runClient(ユーザー「地面に潜れてない」)で足した: 前は頭が岩に入った所で眠り、体の 13 ブロックが地表に残っていた。
 * 遠出は 10-02 のユーザー指定「地下だけにいるのではなく、たまに地上に出て mob を殺しに行く」(形と数値は仮決め)。
 * 地中では地中だけを動く(行く手が空気なら、左右上下にずらした向きで岩の続く方を選ぶ)。空中に出るのはアーチ・攻撃の飛び出し・潜り直しと、
 * 遠出で上る途中に洞窟を突っ切るとき。
 * 攻めは場所で決める 2 通り(壁からの噛みつき・跳びかかりの噛みつき。どちらも攻撃力 1 回分)。G5 の {@code SpecialPacing} は使わない。
 * 標的は採掘の音と、洞窟の中のプレイヤー・Mob(遠出の間と、地上に棲む個体はいつも地表の Mob も)への気付き(殴られたら殴った相手に気付く)。
 * 見て狙う {@code ParasitizedTargetGoal} は入れない。
 */
public class FodimnesEntity extends Monster implements GeoEntity, ParasitizedMob, OvergrownSpawnCheck, IEntityWithComplexSpawn,
        EggPreviewPose {

    // ------------------------------------------------------------------ 体(ユーザー指定と、モデルとの約束)

    /** 胴の節の数(ユーザー指定)。 */
    public static final int SEGMENTS = 16;
    /** 部位の数 = 節 16 + 尾。 */
    public static final int PARTS = SEGMENTS + 1;
    /** 部位の中心どうしの間隔(ブロック、ユーザー指定 0.80)。頭 ↔ 節 1・節 ↔ 節・節 16 ↔ 尾がどれも同じ。体格(SCALE)を掛けて使う。 */
    public static final double SPACING = 0.8D;
    /**
     * 先細りの倍率(節 1〜16。太さだけ、長さ = 間隔は変えない)。{@code tools/g5-models/mobs/Fodimnes.TAPER} と同じ考え方
     * (初めはゆっくり、後ろ 4 節で速く細る鞭の形)を 16 節に引き伸ばしたもの。<b>道具の TAPER と同じ値にしておくこと</b>。
     */
    public static final float[] TAPER = {1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F,
            0.98F, 0.96F, 0.94F, 0.92F, 0.90F, 0.855F, 0.81F, 0.765F, 0.72F};
    /** 尾の倍率 = 最後の節と同じ。 */
    public static final float TAIL_SCALE = 0.72F;
    /** 断面の中心(部位の骨の軸)の高さ(ブロック)= モデルの R 4.3 / 16。道に積むのはこの点。 */
    public static final double AXIS_Y = 4.3D / 16.0D;
    /** 口の胴の軸の高さ(ブロック)= 5.6 / 16。 */
    public static final double MOUTH_Y = 5.6D / 16.0D;
    /** 胴の筒の半径(ブロック)= 4.2 / 16。尾の 1 段目 = 3.6 / 16。 */
    public static final double SEG_RADIUS = 4.2D / 16.0D;
    public static final double TAIL_RADIUS = 3.6D / 16.0D;
    /** 部位の箱を筒より少しだけ太らせる量(ブロック)。 */
    static final double BOX_PAD = 0.03D;
    /** 静止の唇のいちばん前(壁の面の基準)が頭の中心からどれだけ前か(ブロック)= 10.11 / 16(NOTES の「壁の面」)。 */
    public static final double WALL_FACE_FWD = 10.11D / 16.0D;
    /** 閉じた唇の先(描くときの見え隠れの判定に使う点)= 10.5 / 16。 */
    static final double LIP_TIP_FWD = 10.5D / 16.0D;
    /** snap の当たりの瞬間の噛む点: 頭の中心の 0.89 前・地面から 0.42 上(NOTES)。軸からは 0.42 − AXIS_Y 上。 */
    public static final double BITE_FWD = 0.89D;
    public static final double BITE_UP = 0.42D - AXIS_Y;
    /** 蠕動の位相を節ごとにずらす量(tick、NOTES の「位相の約束」)。 */
    public static final int CRAWL_PHASE_TICKS = 5;

    // ------------------------------------------------------------------ 動作(同期してアニメを選ぶ)

    public static final int ACT_NONE = 0;
    /** lurk・retract は 2026-10-09 から使っていない(壁の中で待つ・口を引っ込める段階を外した。止まらないため)。アニメと番号は残す。 */
    public static final int ACT_LURK = 1;
    public static final int ACT_BURST = 2;
    public static final int ACT_SNAP = 3;
    public static final int ACT_RETRACT = 4;

    /** アニメを切り替えるときの補間(tick)。snap だけは burst の終わりの姿勢から始まるので 0。 */
    public static final int TRANSITION_TICKS = 4;
    /** burst(1.0 秒、hold)を流す長さ。 */
    public static final int BURST_TICKS = TRANSITION_TICKS + 20;
    /** burst の 0.19 秒・0.34 秒のかちかち(NOTES)。 */
    static final int BURST_CLICK_1 = TRANSITION_TICKS + 4;
    static final int BURST_CLICK_2 = TRANSITION_TICKS + 7;
    /** burst の 0.45 秒 = 壁を破る瞬間。ここから 0.6 秒までに部位を 0.25 ブロック以上前へ出す(NOTES)。 */
    public static final int BURST_BREAK_TICK = TRANSITION_TICKS + 9;
    static final int BURST_PUSH_END = TRANSITION_TICKS + 12;
    /** burst の 0.45〜0.6 秒に前へ出す量(ブロック/tick)。3 tick で 0.3。 */
    static final double BURST_PUSH = 0.1D;
    /** snap の当たり(0.57 秒、補間 0)。 */
    public static final int SNAP_HIT_TICK = 12;
    static final int SNAP_TICKS = 20;

    // ------------------------------------------------------------------ 仮決めの数値

    /** 採掘の音が途絶えてから見失うまで(8 秒)。 */
    public static final int LOSE_TICKS = 160;
    /**
     * 攻めの間も止まらない(2026-10-09 ユーザー指定「攻撃の際にも止まらず、常に動き続ける」): 飛び出す所へ寄せる・壁を破りに行く・噛みに行くときの
     * いちばん遅い速さ(狩りの速さ {@link #HUNT_SPEED} への割合。仮決め)。予兆(床・壁のひびとかちかち)を見せる間を取るために遅くしても、ここより遅くしない。
     */
    static final double APPROACH_MIN_FACTOR = 0.5D;
    /** 飛び出してよい距離(壁の面から獲物の胴まで、ブロック)。 */
    public static final double STRIKE_RANGE = 3.5D;
    /** 潜り直してから次に飛び出せるまで(3 秒)。 */
    static final int ATTACK_COOLDOWN = 60;
    /** 噛む点から獲物の箱までの届く距離(ブロック)。 */
    static final double BITE_REACH = 0.7D;
    /** 速さ(移動の速さの属性に掛ける倍率。属性 0.35 で 地中 0.21・飛び出し 0.455・潜り直し 0.35 ブロック/tick)。 */
    static final double HUNT_SPEED = 0.6D;
    static final double LUNGE_SPEED = 1.3D;
    static final double CROSS_SPEED = 1.0D;
    static final double DIVE_SPEED = 0.6D;
    /** 旋回の上限(度/tick)。 */
    static final float TURN_HUNT = 10.0F;
    static final float TURN_LURK = 20.0F;
    static final float TURN_SNAP = 20.0F;
    static final float TURN_CROSS = 15.0F;
    /** バンク: 旋回の速さ(度/tick)× この倍率 = 目標の傾き(度)、上限、1 tick に寄せる割合。 */
    static final float BANK_GAIN = 2.5F;
    static final float BANK_MAX = 35.0F;
    static final float BANK_SMOOTH = 0.25F;
    /** 頭の向きを水平のとき背を上へ戻す割合(1 tick)。 */
    static final float HEAD_RELAX = 0.15F;
    /** 地中を進む間の掘る音の間隔(tick)。狩りの間 7・うろつきの間 20(10-02、仮決め)。 */
    static final int DIG_SOUND_INTERVAL = 7;
    static final int DIG_SOUND_ROAM_INTERVAL = 20;
    /** 湧くとき: 頭の後ろ(体の向き)に最低これだけ岩が続くこと(ブロック)。 */
    static final int SPAWN_MIN_ROCK = 3;
    static final int SPAWN_SCAN_ROCK = 6;
    /** SETTLE: 始めた所からこれだけ(× 体格)深くなるまで斜め下(45°)へ、そこからは水平に進む(ブロック)。 */
    static final double SETTLE_DEPTH = 3.0D;
    /** SETTLE の速さ(移動の速さの属性への倍率。地中を進む HUNT と同じ)。 */
    static final double SETTLE_SPEED = 0.6D;
    /** SETTLE をこれだけ続けても体が出ていたら諦めてうろつきへ(tick)。体 13.6 ブロック × 体格を 0.21 × 体格/tick で引き込むのは約 65 tick。 */
    static final int SETTLE_MAX_TICKS = 300;
    /** 諦めた後、次に体を引き込もうとするまで(tick。岩の薄い所でずっと這い回らないように)。 */
    static final int SETTLE_RETRY_TICKS = 600;
    /** スポーンエッグの絵の姿勢: 向き(PREVIEW_YAW を上書き)・頭を上げる角度と、描く節の数(頭 + 前の 2 節。体の全部は 1 マスに収まらない)。 */
    static final float EGG_YAW = -30.0F;
    static final float EGG_PITCH = -20.0F;
    public static final int EGG_VISIBLE_PARTS = 2;
    // ---- うろつき(ROAM)と洞窟へのアーチ跳び(BREACH)。2026-10-01 夜のユーザー指定「地中を常に潜り続けるのではなく、ちょくちょく洞窟内に
    //      飛び出してアーチを描くようにジャンプする」、10-02 のユーザー指定「常に動き続ける。動いている間の 8 割くらいは洞窟の表面を動きながら
    //      アーチを描くように飛び出す。近くでブランチマイニングの音が聞こえたら・洞窟内にプレイヤーが来たら襲いに行く」。数値はすべて仮決め
    /** うろつく速さ(移動の速さの属性への倍率。0.35 × 0.45 × 体格 2 = 0.315 ブロック/tick)。うろつきは終わらない(跳ぶ・襲う・寄り道を挟んで続く)。 */
    static final double ROAM_SPEED = 0.45D;
    /** うろつく向き: 選ぶとき、水平 8 方向でこの距離(× 体格)までに空気(洞窟)がある向きのうち近いものへ。無ければ乱数。 */
    static final double ROAM_SEEK_RANGE = 12.0D;
    /** うろつく高さの幅(× 体格): 始めた高さからこれ以上離れたら戻す。 */
    static final double ROAM_DEPTH_BAND = 4.0D;
    /** アーチを描かずにこれだけうろついたら、向きを選び直す(tick。洞窟の方へ向け直す)。 */
    static final int ROAM_REPICK_TICKS = 160;
    /** アーチの後、地中だけを進む寄り道に入る割合と長さ(tick)。洞窟の表面を跳ね続ける時間を全体の 8 割ほどにする(ユーザー指定の目安)。 */
    static final float ROAM_EXCURSION_CHANCE = 0.15F;
    static final int ROAM_EXCURSION_MIN = 60;
    static final int ROAM_EXCURSION_MAX = 140;
    /** うろつく間、洞窟へ飛び出せるかを見る間隔(tick)。 */
    static final int BREACH_SCAN_INTERVAL = 10;
    /** 跳んだ後、次に跳べるまで(tick、この間の乱数)。10-02 に 15〜35 秒 → 0.5〜1.5 秒(続けて跳ねる)。 */
    static final int BREACH_COOLDOWN_MIN = 10;
    static final int BREACH_COOLDOWN_MAX = 30;
    /** アーチの始まりへ寄せられなかったとき、次に探すまで(tick)。 */
    static final int BREACH_ABORT_COOLDOWN = 60;
    /** アーチの床の上の水平の長さ(× 体格。長い方から試す)。体格 2 で 13・11・9・7 ブロック。 */
    static final double[] BREACH_SPANS = {6.5D, 5.5D, 4.5D, 3.5D};
    /** アーチの高さ = 長さ × この割合(飛び出す角度 約 57°)。天井が低ければ 3/4・1/2 に下げる。下限は × 体格。 */
    static final double BREACH_HEIGHT_RATIO = 0.38D;
    static final double BREACH_HEIGHT_MIN = 1.0D;
    /** アーチの両端を床からこれだけ深くまで伸ばす(× 体格)。ここまでは地中で、頭が隠れたまま向きを合わせる。 */
    static final double BREACH_DEPTH = 1.6D;
    /**
     * 飛び出す床を探す: 頭からの前の距離(× 体格)・向き(まっすぐ → 斜め → 横 → 後ろ。洞窟の端で折り返して跳ね続ける)・
     * 頭の高さから上下の幅(× 体格)。上を先に探す。
     */
    static final double[] BREACH_LOOK_AHEAD = {1.5D, 3.0D, 4.5D};
    static final float[] BREACH_LOOK_YAW = {0.0F, 35.0F, -35.0F, 70.0F, -70.0F, 110.0F, -110.0F, 180.0F};
    static final double BREACH_FLOOR_ABOVE = 6.0D;
    static final double BREACH_FLOOR_BELOW = 3.0D;
    /** 着く床を探す: 飛び出す床の高さの上下この範囲(ブロック)。 */
    static final int BREACH_LANDING_RISE = 3;
    /** アーチを水平に進む速さ(移動の速さの属性への倍率。0.35 × 0.8 × 体格 2 = 0.56 ブロック/tick。飛び出しは縦も足して約 1.1)。 */
    static final double BREACH_SPEED = 0.8D;
    /** アーチの始まり(床の下)へ地中を寄せる時間の上限(tick)。 */
    static final int BREACH_APPROACH_MAX_TICKS = 120;
    /** 洞窟の天井: アーチの頂点の上、このマス数までに岩があること(空の見える所・梢の下へは出ない)。 */
    static final int BREACH_CEILING_SCAN = 48;
    // ---- 洞窟の中の相手への跳びかかり(STRIKE。BREACH の一種で、アーチが相手の上半身を通る)。10-02 の仮決め
    /** 洞窟の中のプレイヤー・Mob に気付く間隔(tick)と、スニークしている相手に気付く距離の割合(聞こえる距離 × これ)。 */
    static final int NOTICE_INTERVAL = 10;
    static final double SNEAK_NOTICE_FACTOR = 1.0D / 3.0D;
    /** 跳びかかりのアーチの長さ(× 体格。相手の前後に半分ずつ)・試す向き(こちらから相手へ → 斜め → 横 → 反対)。 */
    static final double[] STRIKE_SPANS = {3.0D, 4.0D, 5.0D};
    static final float[] STRIKE_YAW = {0.0F, 45.0F, -45.0F, 90.0F, -90.0F, 180.0F};
    /** 跳びかかりのアーチの高さの下限(× 体格。高さ = 相手の上半身(高さの 3/4)− 両端の床)。 */
    static final double STRIKE_HEIGHT_MIN = 0.5D;
    /** 跳びかかれる場所: 相手の足元の床の上に、このマス数の空きが要る(高さ 2 の坑道では跳ばずに壁から待ち伏せる)。 */
    static final int STRIKE_MIN_HEADROOM = 3;
    /** 跳びかかりの溜め(tick、床の下で唇を開く burst の長さ。床にひび・かちかちの予兆)。 */
    static final int STRIKE_TELL_TICKS = BURST_TICKS;
    /** 跳びかかった後、次に跳びかかれるまで(tick、この間の乱数)。その間は相手の下を回る。 */
    static final int STRIKE_COOLDOWN_MIN = 30;
    static final int STRIKE_COOLDOWN_MAX = 50;
    /** 跳びかかりを計画し直す間隔(tick)・寄せている間に相手がこれだけ動いたら計画し直す(ブロック)。 */
    static final int STRIKE_PLAN_INTERVAL = 10;
    static final double STRIKE_REPLAN_DIST = 2.0D;
    /** 跳びかかりの合間、相手の床の下を回る半径(× 体格)。 */
    static final double STALK_RADIUS = 2.5D;
    // ---- 地上への遠出(OUTING)。2026-10-02 のユーザー指定「地下だけにいるのではなく、たまに地上に出て mob を殺しに行く」。数値はすべて仮決め
    /** 遠出の間隔(tick、この間の乱数): 湧いて・読み込まれてから最初の遠出まで 30〜90 秒、遠出の後 2〜4 分。獲物が居なければ 5 秒おきに探し直す。 */
    static final int OUTING_FIRST_MIN = 600;
    static final int OUTING_FIRST_MAX = 1800;
    static final int OUTING_COOLDOWN_MIN = 2400;
    static final int OUTING_COOLDOWN_MAX = 4800;
    static final int OUTING_RETRY_TICKS = 100;
    /** 遠出の獲物を探す範囲: 水平に 聞こえる距離 × この倍率(followRange 24 で 32 ブロック)、頭より上 {@link #OUTING_MAX_RISE}・下 {@link #OUTING_MAX_DROP} ブロック。 */
    static final double OUTING_RANGE_FACTOR = 4.0D / 3.0D;
    static final double OUTING_MAX_RISE = 64.0D;
    static final double OUTING_MAX_DROP = 4.0D;
    /** 上る(ASCEND)時間の上限(tick)。届かなければ諦めて戻る。上る角度の上限(水平 1 に対する縦の割合。1.7 ≒ 60°)。 */
    static final int ASCEND_MAX_TICKS = 600;
    static final double ASCEND_SLOPE = 1.7D;
    /** 地表(SURFACE)で獲物が居ないまま(狩っていない)これだけ経つか、遠出を始めてからこれだけ経ったら戻る(tick)。 */
    static final int SURFACE_IDLE_TICKS = 200;
    static final int OUTING_MAX_TICKS = 1200;
    /** 地表の下を進む高さ: 頭の上の床から {@link #BREACH_DEPTH} × 体格 下 ± この幅(× 体格)。床を探すのは頭の上この数のマスまで。 */
    static final double SURFACE_DEPTH_BAND = 1.0D;
    static final int SURFACE_FLOOR_SCAN = 16;
    /** 地表で高さ・向き(獲物の方)を合わせ直す間隔(tick)。 */
    static final int SURFACE_REFRESH_INTERVAL = 20;
    /** 戻る(DESCEND)時間の上限(tick)。始めた高さ + 1 ブロックまで下りたら終わり。 */
    static final int DESCEND_MAX_TICKS = 400;
    /**
     * 地表を探す高さ(頭の上のマス数。2026-10-09、仮決め): 頭の上の床のうち、岩の天井が無いもの(洞窟・坑道の床は飛ばす)を地表とする
     * ({@link #surfaceFloorAbove})。地表の下を進む高さの基準と、棲み場所の決め方({@link #decideHabitat})に使う。
     */
    public static final int SURFACE_SEARCH_MAX = 48;

    private static final EntityDataAccessor<Integer> DATA_ACTION =
            SynchedEntityData.defineId(FodimnesEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ACTION_TIME =
            SynchedEntityData.defineId(FodimnesEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SEQ =
            SynchedEntityData.defineId(FodimnesEntity.class, EntityDataSerializers.INT);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation CRAWL = RawAnimation.begin().thenLoop("crawl");
    private static final RawAnimation LURK = RawAnimation.begin().thenLoop("lurk");
    private static final RawAnimation BURST = RawAnimation.begin().thenPlayAndHold("burst");
    private static final RawAnimation SNAP = RawAnimation.begin().thenPlayAndHold("snap");
    private static final RawAnimation RETRACT = RawAnimation.begin().thenPlayAndHold("retract");

    /**
     * サーバーの考えの段階(同期しない)。10-09 に壁の中で待つ LURK・口を引っ込める RETRACT を外した(止まらないため)。
     * REST は湧いた・読み込んだ直後の 1 tick だけ(テストの {@link #holdStillForTests} だけが待たせる)。
     */
    public enum Mode { REST, HUNT, BURST, SNAP, CROSS, DIVE, SETTLE, ROAM, BREACH }

    /**
     * 地上への遠出の段階(段階 {@link Mode} と別に持つ。遠出の間も狩り・跳ねは今までの段階で動く)。
     * NONE = 遠出していない、ASCEND = 獲物の足元の床の下へ上っている、SURFACE = 地表の下を進みながら地表の獲物を狩る・地表で跳ねる、
     * DESCEND = 遠出を始めた高さへ戻っている。
     */
    public enum Outing { NONE, ASCEND, SURFACE, DESCEND }

    /**
     * 棲み場所(2026-10-09 のユーザー指定「地下限定ではなく地上でも湧くように」で足した。決め方は仮決め)。最初にうろつき始めるときに決めて保存する
     * ({@link #decideHabitat}): 頭の上の最初の床に岩の天井が無ければ(地表)SURFACE、あれば(洞窟の床)CAVE。
     * SURFACE の個体は遠出の地表の段階と同じ動き(地表のすぐ下を進み、地表の Mob に気付き、地表で跳ねる)をずっと続け、戻らない。
     * CAVE は今までどおり(洞窟をうろつき、たまに地上へ遠出する)。
     */
    public enum Habitat { UNKNOWN, CAVE, SURFACE }

    /**
     * 洞窟へ飛び出すアーチ(放物線): 床の飛び出す点 a から潜る点 b への直線に、上へ {@code 4h·t(1−t)} を足した曲線。
     * t は水平に一定の速さで進む(縦は本物の放物線 = 跳ねる)。t0 &lt; 0・t1 &gt; 1 は床の下(地中で向きを合わせる所と、潜った先)。
     */
    record BreachPlan(Vec3 a, Vec3 b, double h, double t0, double t1) {
    }

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private final FodimnesPart[] parts = new FodimnesPart[PARTS];
    private final FodimnesBody body = new FodimnesBody();

    // ---- 体の解(サーバー・クライアント共通)
    private final double[] partPos = new double[3 * PARTS];
    private final double[] partFwd = new double[3 * PARTS];
    private final float[] partRoll = new float[PARTS];
    private float bankRoll;
    private boolean bodySolved;
    private double solvedX = Double.NaN;
    private double solvedY;
    private double solvedZ;
    private float solvedRoll;

    // ---- サーバーの考え
    private Mode mode = Mode.REST;
    private int modeTicks;
    /** 頭の向き(単位ベクトル)。yRot・xRot はここから出す。 */
    private Vec3 heading;
    private Vec3 moveVel = Vec3.ZERO;
    private int noiseTimer;
    @Nullable
    private Vec3 noisePos;
    @Nullable
    private LivingEntity lastKnownTarget;
    private int attackCooldown;
    private int planTimer;
    @Nullable
    private Vec3 plannedFor;
    /** 待ち伏せの計画: 頭の軸の点・壁の面の中心・面の外向き・壁のブロック・回り込みの点。 */
    @Nullable
    private Vec3 lurkAxis;
    @Nullable
    private Vec3 lurkFace;
    @Nullable
    private Vec3 lurkNormal;
    @Nullable
    private BlockPos lurkCell;
    @Nullable
    private Vec3 stagingAxis;
    @Nullable
    private Vec3 diveTarget;
    @Nullable
    private Vec3 detourDir;
    private int detourTicks;
    private int digSoundTimer;
    /** SETTLE で斜め下へ潜るのをやめる高さ(軸の点の y)。 */
    private double settleFloorY;
    /** SETTLE を諦めた後の待ち(tick)。 */
    private int settleCooldown;
    /** REST からうろつき始めるまでの残り(tick)。本番は 0(止まらない。10-09)、{@link #holdStillForTests} だけが延ばす。 */
    private int restTimer;
    /** 棲み場所({@link Habitat}。最初にうろつき始めるときに決めて保存する)。 */
    private Habitat habitat = Habitat.UNKNOWN;
    /** うろつき: 向き(水平の単位ベクトル)・アーチを描かずにうろついた tick・始めた高さ(軸の点の y)・地中だけを進む寄り道の残り(tick)。 */
    @Nullable
    private Vec3 roamDir;
    private int roamTicks;
    private double roamBaseY;
    private int excursionTicks;
    /** テスト用: 寄り道に入らない(跳ね続ける回数を数えるテスト)。 */
    private boolean noExcursionForTests;
    /** テスト用: うろつきの跳ねを組まない(7 マスのテスト場では、壁の外の低い地面を踏み切りに選んで壁の下をくぐって外へ跳ぶことがある)。 */
    private boolean noRoamBreachForTests;
    /**
     * Mob に気付くか(本番は常に true)。テストでは false にしておく —— テスト場は 24〜48 ブロックおきに並んで同時に走るので、
     * 隣のテスト場の屋根の下の Mob(天井のある洞窟のハスク・ほかのセッションの兵士)に気付いて、頼んでいない標的を追い始める。
     */
    private boolean noticeMobsForTests = true;
    /** 次に洞窟へ飛び出せるまで(tick)。 */
    private int breachCooldown;
    /**
     * 跳びかかり(相手の上半身を通るアーチ): 相手・計画したときの相手の位置・予兆を見せた tick 数(−1 = 跳びかかりでない。寄せながら数える)・
     * アーチに入ってからの tick・1 tick に進む媒介変数(飛び出してから snap の当たりの tick で t = 0.5 に着く)・噛んだか(坑道の snap でも使う)・
     * 次まで・計画し直すまで・ここでは跳べない(狭い = 壁を破って噛む)・相手の下を回る角度・最後の跳びかかりで予兆を見せた tick 数(テスト用)。
     */
    @Nullable
    private LivingEntity strikeTarget;
    @Nullable
    private Vec3 strikeAim;
    private int strikeTell = -1;
    private int lastStrikeTellTicks = -1;
    private int strikeArcTick;
    private double strikeDt;
    private boolean strikeBitten;
    private int strikeCooldown;
    private int strikePlanTimer;
    private boolean strikeBlocked;
    private double stalkAngle;
    /** 洞窟へのアーチ: 計画・今の媒介変数・アーチに入ったか(false = 始まりへ地中を寄せている)・頭が空中か。 */
    @Nullable
    private BreachPlan breach;
    private double breachT;
    private boolean breachArc;
    private boolean breachInAir;
    /**
     * 地上への遠出: 段階・その段階の tick・始めてからの tick・地表で狩っていない tick・次の遠出まで(tick)・始めた高さ(軸の点の y。戻る先)・
     * 狙う獲物(上り始めたときの相手。近づくまでは気付く距離の外)・上る先(獲物の足元の床の {@link #BREACH_DEPTH} × 体格 下)。
     */
    private Outing outing = Outing.NONE;
    private int outingTicks;
    private int outingAge;
    private int outingIdle;
    private int outingCooldown;
    private double homeY = Double.NaN;
    @Nullable
    private LivingEntity outingQuarry;
    @Nullable
    private Vec3 ascendGoal;
    /** 地表で獲物が居ないまま戻るまで(tick。本番は {@link #SURFACE_IDLE_TICKS}。テストは 7 マスの場で長くうろつかせないよう短くする)。 */
    private int surfaceIdleLimit = SURFACE_IDLE_TICKS;
    /** 多重ヒットの間引き: 同じ tick の同じ DamageSource は大きい方の 1 回分だけ。 */
    @Nullable
    private DamageSource lastHurtSource;
    private int lastHurtSourceTick = -1;
    private float lastHurtSourceAmount;

    // ---- クライアントの見た目(0 = 頭、1..17 = 部位)
    private final double[] axisPrev = new double[3 * (PARTS + 1)];
    private final double[] axisNow = new double[3 * (PARTS + 1)];
    private final Quaternionf[] framePrev = new Quaternionf[PARTS + 1];
    private final Quaternionf[] frameNow = new Quaternionf[PARTS + 1];
    private final Quaternionf headFrame = new Quaternionf();
    private final Vector3f headDirPrev = new Vector3f();
    private final Quaternionf[] rawFrames = new Quaternionf[PARTS];
    private final Vector3f[] dirTmp = new Vector3f[PARTS];
    private final boolean[] buried = new boolean[PARTS + 1];
    private final boolean[] inside = new boolean[PARTS + 1];
    private final BlockState[] insideState = new BlockState[PARTS + 1];
    private boolean visualsReady;
    @Nullable
    private AABB cullBox;
    private int clientSeq;
    /** スポーンエッグの絵の描画専用の個体(世界に居ない・tick しない)。世界のブロック・明るさを見ない。 */
    private boolean eggPreview;
    /** スポーンエッグの絵: 描く部分(頭 + 前の節)の真ん中を枠の中心へ寄せる平行移動(ブロック)。 */
    private Vec3 eggShift = Vec3.ZERO;

    public FodimnesEntity(EntityType<? extends FodimnesEntity> type, Level level) {
        super(type, level);
        // 部位は本体のすぐ後に作る(id が本体の次から連番になる。クライアントは setId で同じ番号を振る)
        for (int i = 0; i < PARTS; i++) {
            float s = partTaper(i + 1);
            double r = (i < SEGMENTS ? SEG_RADIUS : TAIL_RADIUS) * s + BOX_PAD;
            this.parts[i] = new FodimnesPart(this, i + 1, (float) (2.0D * r), (float) (2.0D * r));
        }
        // 部位の id を本体の続きの番号で取っておく(エンダードラゴンと同じ。MC-158205 の直し)。
        // 取らないと、別のスレッドが同時に実体を作ったときに部位の番号が飛び、クライアントの setId(本体 + 1…)とずれて部位が殴れなくなる
        this.setId(ENTITY_COUNTER.getAndAdd(PARTS + 1) + 1);
        for (int i = 0; i <= PARTS; i++) {
            this.framePrev[i] = new Quaternionf();
            this.frameNow[i] = new Quaternionf();
        }
        for (int i = 0; i < PARTS; i++) {
            this.rawFrames[i] = new Quaternionf();
            this.dirTmp[i] = new Vector3f();
        }
        this.xpReward = MobStats.xpReward("fodimnes", 40);
        this.outingCooldown = randomBetween(OUTING_FIRST_MIN, OUTING_FIRST_MAX);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.moveControl = new MoveControl(this) {
            @Override
            public void tick() {
            }
        };
        this.lookControl = new LookControl(this) {
            @Override
            public void tick() {
            }
        };
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), "fodimnes");
    }

    /** 経路探索はしない(地中を自分で進む)。差し込まれる Goal が経路を頼んでも作らない。 */
    @Override
    protected PathNavigation createNavigation(Level level) {
        return new GroundPathNavigation(this, level) {
            @Override
            protected boolean canUpdatePath() {
                return false;
            }
        };
    }

    /** 体の向きは頭の向き(サーバーが決めて同期する)。バニラの体の回し方を止める。 */
    @Override
    protected BodyRotationControl createBodyControl() {
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                Mob mob = FodimnesEntity.this;
                mob.yBodyRot = mob.getYRot();
                mob.yHeadRot = mob.getYRot();
            }
        };
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION, ACT_NONE);
        builder.define(DATA_ACTION_TIME, 0);
        builder.define(DATA_SEQ, 0);
    }

    // ================================================================== 部位

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
        for (int i = 0; i < this.parts.length; i++) {
            this.parts[i].setId(id + i + 1);
        }
    }

    /** 部位(1 = 節 1 … 17 = 尾)。 */
    public FodimnesPart part(int index) {
        return this.parts[index - 1];
    }

    /** 部位の太さの倍率(0 = 頭は 1)。 */
    public static float partTaper(int index) {
        if (index <= 0) {
            return 1.0F;
        }
        return index <= SEGMENTS ? TAPER[index - 1] : TAIL_SCALE;
    }

    /** 部位の中心(軸の点)。0 = 頭の軸の点、1..17 = 部位。サーバーでもクライアントでも最後に解いた値。 */
    public Vec3 partCenter(int index) {
        if (index == 0) {
            return axisPoint();
        }
        int i = index - 1;
        return new Vec3(this.partPos[3 * i], this.partPos[3 * i + 1], this.partPos[3 * i + 2]);
    }

    /** 部位の前の向き(頭の方を向いた単位ベクトル)。 */
    public Vec3 partForward(int index) {
        if (index == 0) {
            return this.heading != null ? this.heading : this.getLookAngle();
        }
        int i = index - 1;
        return new Vec3(this.partFwd[3 * i], this.partFwd[3 * i + 1], this.partFwd[3 * i + 2]);
    }

    public float partRoll(int index) {
        return index == 0 ? this.bankRoll : this.partRoll[index - 1];
    }

    /** 頭の軸の点(足元 + 断面の中心の高さ)。 */
    public Vec3 axisPoint() {
        return new Vec3(this.getX(), this.getY() + AXIS_Y * this.getScale(), this.getZ());
    }

    public FodimnesBody body() {
        return this.body;
    }

    // ================================================================== 毎 tick

    @Override
    public void tick() {
        super.tick();
        updateBody(false);
    }

    /**
     * 世界に入った瞬間に部位を置く(最初の tick を待たない)。点検 2026-10-01: 部位は作られた時点で世界の原点 (0, 0, 0) にあり、
     * 最初の tick までそこに当たり判定が残っていた(NeoForge の部位の一覧は箱で引くので、原点のそばの爆発・矢が遠くの個体に当たる)。
     * クライアントでも、tick より先に描かれる 1 フレームで節が原点へ飛んで描かれていた。
     */
    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        updateBody(true);
    }

    /**
     * 体を解いて部位を置く(サーバー・クライアント共通の式)。頭が動いていなければ解き直さない。
     * GameTest からも呼ぶ({@code force})。
     */
    public void updateBody(boolean force) {
        double s = this.getScale();
        this.body.setUnit(s);
        Vec3 axis = axisPoint();
        Vec3 look = headLook();
        updateBank();
        boolean moved = force || Double.isNaN(this.solvedX)
                || Math.abs(axis.x - this.solvedX) > 1.0E-7D || Math.abs(axis.y - this.solvedY) > 1.0E-7D
                || Math.abs(axis.z - this.solvedZ) > 1.0E-7D || Math.abs(this.bankRoll - this.solvedRoll) > 1.0E-4F;
        if (this.body.size() == 0 || this.body.distanceFromNewest(axis.x, axis.y, axis.z) > FodimnesBody.RESET_JUMP * s) {
            // 出たばかり・読み込みで道が無い・テレポート: 頭の後ろへまっすぐ置く
            this.body.seedStraight(axis.x, axis.y, axis.z, -look.x, -look.y, -look.z, SPACING * s * (PARTS + 2), this.bankRoll);
            moved = true;
        }
        if (moved) {
            popAhead(axis, look);
            this.body.offer(axis.x, axis.y, axis.z, this.bankRoll);
            this.body.solve(axis.x, axis.y, axis.z, this.bankRoll, -look.x, -look.y, -look.z,
                    SPACING * s, PARTS, this.partPos, this.partFwd, this.partRoll);
            this.solvedX = axis.x;
            this.solvedY = axis.y;
            this.solvedZ = axis.z;
            this.solvedRoll = this.bankRoll;
            this.bodySolved = true;
        }
        for (int i = 0; i < PARTS; i++) {
            FodimnesPart part = this.parts[i];
            part.tickCount = this.tickCount;
            float taper = partTaper(i + 1);
            double r = ((i < SEGMENTS ? SEG_RADIUS : TAIL_RADIUS) * taper + BOX_PAD) * s;
            double half = SPACING * s * 0.5D;
            double fx = this.partFwd[3 * i];
            double fy = this.partFwd[3 * i + 1];
            double fz = this.partFwd[3 * i + 2];
            // 向きに合わせた軸平行の箱: 長さ 0.8 の筒(半径 r)を覆う
            double ex = Math.abs(fx) * half + r * Math.sqrt(Math.max(0.0D, 1.0D - fx * fx));
            double ey = Math.abs(fy) * half + r * Math.sqrt(Math.max(0.0D, 1.0D - fy * fy));
            double ez = Math.abs(fz) * half + r * Math.sqrt(Math.max(0.0D, 1.0D - fz * fz));
            part.place(this.partPos[3 * i], this.partPos[3 * i + 1], this.partPos[3 * i + 2], ex, ey, ez);
        }
        if (this.level().isClientSide()) {
            updateClientVisuals();
        }
    }

    /**
     * 頭が道を<b>後ろへ</b>戻ったとき(retract)、頭より前に残った道の点を捨てる(サーバーもクライアントも同じ規則。
     * 捨てないと道が頭の前で折り返し、首のあたりの節が折り返しの上に乗る)。
     */
    private void popAhead(Vec3 axis, Vec3 look) {
        double s = this.getScale();
        Vec3 prev = new Vec3(this.xo, this.yo + AXIS_Y * s, this.zo);
        Vec3 step = axis.subtract(prev);
        if (step.dot(look) >= -1.0E-4D) {
            return;
        }
        int guard = 0;
        while (this.body.size() > 1 && guard++ < FodimnesBody.CAPACITY) {
            double dx = this.body.x(0) - axis.x;
            double dy = this.body.y(0) - axis.y;
            double dz = this.body.z(0) - axis.z;
            if (dx * look.x + dy * look.y + dz * look.z <= 0.02D) {
                break;
            }
            this.body.popNewest();
        }
    }

    /** 頭の前の向き(yRot・xRot から。サーバーでもクライアントでも同じ)。 */
    private Vec3 headLook() {
        return Vec3.directionFromRotation(this.getXRot(), this.getYRot());
    }

    /**
     * ユーザー指定「旋回の速さに比例して内側へ傾く(バンク)」。yaw の変化(差は {@code Mth.wrapDegrees})× 倍率を目標に、少しずつ寄せる。
     * 真上・真下を向いているとき(yaw に意味が無い)は水平の割合 cos(pitch) で弱める。サーバーとクライアントで同じ式(見た目だけに使う)。
     */
    private void updateBank() {
        if (this.isDeadOrDying()) {
            return;
        }
        float dYaw = Mth.wrapDegrees(this.getYRot() - this.yRotO);
        float horizontal = Mth.cos(this.getXRot() * Mth.DEG_TO_RAD);
        float target = Mth.clamp(BANK_GAIN * dYaw * horizontal, -BANK_MAX, BANK_MAX);
        this.bankRoll += (target - this.bankRoll) * BANK_SMOOTH;
        if (Math.abs(this.bankRoll) < 1.0E-3F) {
            this.bankRoll = 0.0F;
        }
    }

    // ================================================================== クライアントの見た目

    /**
     * クライアント: 部位の向き(回転最小化フレーム + roll)・補間用の前の値・地面の下の部位・出入りのパーティクル・カリングの箱。
     */
    private void updateClientVisuals() {
        double s = this.getScale();
        // 前の値を控える
        System.arraycopy(this.axisNow, 0, this.axisPrev, 0, this.axisNow.length);
        for (int i = 0; i <= PARTS; i++) {
            this.framePrev[i].set(this.frameNow[i]);
        }
        Vec3 look = headLook();
        Vector3f dir = new Vector3f((float) look.x, (float) look.y, (float) look.z);
        if (!this.visualsReady) {
            FodimnesFrames.upright(this.getYRot(), this.getXRot(), this.headFrame);
        } else {
            FodimnesFrames.advanceHead(this.headFrame, this.headDirPrev, dir, this.getYRot(), this.getXRot(), HEAD_RELAX);
        }
        this.headDirPrev.set(dir);
        for (int i = 0; i < PARTS; i++) {
            this.dirTmp[i].set((float) this.partFwd[3 * i], (float) this.partFwd[3 * i + 1], (float) this.partFwd[3 * i + 2]);
        }
        FodimnesFrames.propagate(this.headFrame, dir, this.dirTmp, this.rawFrames);
        Vec3 axis = axisPoint();
        this.axisNow[0] = axis.x;
        this.axisNow[1] = axis.y;
        this.axisNow[2] = axis.z;
        FodimnesFrames.applyRoll(this.frameNow[0].set(this.headFrame), this.bankRoll);
        for (int i = 0; i < PARTS; i++) {
            this.axisNow[3 * (i + 1)] = this.partPos[3 * i];
            this.axisNow[3 * (i + 1) + 1] = this.partPos[3 * i + 1];
            this.axisNow[3 * (i + 1) + 2] = this.partPos[3 * i + 2];
            FodimnesFrames.applyRoll(this.frameNow[i + 1].set(this.rawFrames[i]), this.partRoll[i]);
        }
        if (!this.visualsReady) {
            System.arraycopy(this.axisNow, 0, this.axisPrev, 0, this.axisNow.length);
            for (int i = 0; i <= PARTS; i++) {
                this.framePrev[i].set(this.frameNow[i]);
            }
        }
        if (this.eggPreview) {
            // スポーンエッグの絵: 描画専用の個体は世界の原点に置かれているので、ブロックで見え隠れを決めない(パーティクルも出さない)。
            // 頭と前の EGG_VISIBLE_PARTS 節だけ描く
            for (int i = 0; i <= PARTS; i++) {
                this.buried[i] = i > EGG_VISIBLE_PARTS;
            }
            this.visualsReady = true;
            return;
        }

        // 地面の下の部位(描かない)と、出入りの瞬間のパーティクル
        Level level = this.level();
        double minX = this.getBoundingBox().minX;
        double minY = this.getBoundingBox().minY;
        double minZ = this.getBoundingBox().minZ;
        double maxX = this.getBoundingBox().maxX;
        double maxY = this.getBoundingBox().maxY;
        double maxZ = this.getBoundingBox().maxZ;
        for (int i = 0; i <= PARTS; i++) {
            double cx = this.axisNow[3 * i];
            double cy = this.axisNow[3 * i + 1];
            double cz = this.axisNow[3 * i + 2];
            Vec3 f = i == 0 ? look : new Vec3(this.partFwd[3 * (i - 1)], this.partFwd[3 * (i - 1) + 1], this.partFwd[3 * (i - 1) + 2]);
            double front = (i == 0 ? LIP_TIP_FWD : SPACING * 0.5D) * s;
            double back = SPACING * 0.5D * s;
            BlockPos cell = BlockPos.containing(cx, cy, cz);
            BlockState st = stateIfLoaded(level, cell);
            boolean in = st != null && isEarthState(level, cell, st);
            this.buried[i] = in
                    && isEarth(level, BlockPos.containing(cx + f.x * front, cy + f.y * front, cz + f.z * front))
                    && isEarth(level, BlockPos.containing(cx - f.x * back, cy - f.y * back, cz - f.z * back));
            if (this.visualsReady && in != this.inside[i]) {
                BlockState solid = in ? st : this.insideState[i];
                if (solid != null && !solid.isAir()) {
                    burstParticles(solid, cx, cy, cz, i == 0 ? 10 : 6);
                    if (i == 0 || i == PARTS) {
                        level.playLocalSound(cx, cy, cz, solid.getSoundType().getBreakSound(), SoundSource.HOSTILE,
                                0.5F, 0.7F + this.random.nextFloat() * 0.2F, false);
                    }
                }
            }
            this.inside[i] = in;
            this.insideState[i] = in ? st : this.insideState[i];
            double r = 0.6D * s;
            minX = Math.min(minX, cx - r);
            minY = Math.min(minY, cy - r);
            minZ = Math.min(minZ, cz - r);
            maxX = Math.max(maxX, cx + r);
            maxY = Math.max(maxY, cy + r);
            maxZ = Math.max(maxZ, cz + r);
        }
        this.cullBox = new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(1.0D * s);
        this.visualsReady = true;

        // 倒れるとき: 見えている節からも煙(頭の煙はバニラ)
        if (this.deathTime == 19) {
            for (int i = 1; i <= PARTS; i++) {
                if (!this.buried[i]) {
                    level.addParticle(ParticleTypes.POOF, this.axisNow[3 * i], this.axisNow[3 * i + 1], this.axisNow[3 * i + 2],
                            this.random.nextGaussian() * 0.02D, 0.05D, this.random.nextGaussian() * 0.02D);
                }
            }
        }
    }

    private void burstParticles(BlockState state, double x, double y, double z, int count) {
        BlockParticleOption opt = new BlockParticleOption(ParticleTypes.BLOCK, state);
        for (int k = 0; k < count; k++) {
            this.level().addParticle(opt, x + (this.random.nextDouble() - 0.5D) * 0.5D, y + (this.random.nextDouble() - 0.5D) * 0.5D,
                    z + (this.random.nextDouble() - 0.5D) * 0.5D, (this.random.nextDouble() - 0.5D) * 0.2D, this.random.nextDouble() * 0.15D,
                    (this.random.nextDouble() - 0.5D) * 0.2D);
        }
    }

    /** クライアント: 部位 i(0 = 頭)が地面の下に埋まっているか(描かない)。 */
    public boolean isPartBuried(int index) {
        return this.visualsReady && this.buried[index];
    }

    /** クライアント: 描くための向き・位置を一度でも作ったか(作る前に描くと、節が世界の原点に描かれる)。 */
    public boolean isVisualReady() {
        return this.visualsReady;
    }

    /**
     * スポーンエッグの絵({@code SpawnEggEntityItemRenderer})の描画専用の個体に姿勢を付ける(作った直後に 1 回)。
     * この個体は世界に入らず tick もしないので、何もしないと {@link #isVisualReady} が立たず何も描かれなかった(2026-10-01 の runClient)。
     * 頭を少し上へ向けて口を見せ、体は後ろ下へまっすぐ。描くのは頭と前の {@link #EGG_VISIBLE_PARTS} 節で、その真ん中を枠の中心へ寄せる。
     */
    @Override
    public void prepareEggPreview() {
        this.eggPreview = true;
        this.setYRot(EGG_YAW);
        this.yRotO = EGG_YAW;
        this.setXRot(EGG_PITCH);
        this.xRotO = EGG_PITCH;
        this.yBodyRot = EGG_YAW;
        this.yBodyRotO = EGG_YAW;
        this.yHeadRot = EGG_YAW;
        this.yHeadRotO = EGG_YAW;
        this.visualsReady = false;
        this.body.clear();
        this.solvedX = Double.NaN;
        updateBody(true);
        // 描く部分の前の端(閉じた唇の先)と後ろの端(最後に描く節の後ろの端)の真ん中を、枠の中心(当たり判定の高さの半分)へ寄せる
        double s = this.getScale();
        Vec3 front = axisPoint().add(headLook().scale(LIP_TIP_FWD * s));
        Vec3 back = partCenter(EGG_VISIBLE_PARTS).subtract(partForward(EGG_VISIBLE_PARTS).scale(SPACING * 0.5D * s));
        Vec3 mid = front.add(back).scale(0.5D);
        double centerY = this.getType().getDimensions().height() * s * 0.5D;
        this.eggShift = new Vec3(this.getX() - mid.x, this.getY() + centerY - mid.y, this.getZ() - mid.z);
    }

    /** スポーンエッグの絵の描画専用の個体か({@link #prepareEggPreview})。 */
    public boolean isEggPreview() {
        return this.eggPreview;
    }

    /** スポーンエッグの絵: 描く部分の真ん中を枠の中心へ寄せる平行移動(ブロック、足元から)。 */
    public Vec3 eggShift() {
        return this.eggShift;
    }

    /**
     * クライアント: 頭の明るさを測るマス = 閉じた唇の先の少し前。待ち伏せ(lurk)・飛び出し(burst)の間は頭の中心が岩の中にあり、
     * バニラの測り方(実体の目の高さのマス)だと岩の中の明るさ 0 で、壁から出した唇が真っ黒に描かれる(点検 2026-10-01)。
     */
    public BlockPos mouthLightProbe() {
        Vec3 look = headLook();
        double d = (LIP_TIP_FWD + 0.2D) * this.getScale();
        Vec3 a = axisPoint();
        return BlockPos.containing(a.x + look.x * d, a.y + look.y * d, a.z + look.z * d);
    }

    /** クライアント: 描くときの部位の軸の点(補間込み)。 */
    public void renderAxis(int index, float partialTick, Vector3d out) {
        int k = 3 * index;
        out.set(Mth.lerp(partialTick, this.axisPrev[k], this.axisNow[k]),
                Mth.lerp(partialTick, this.axisPrev[k + 1], this.axisNow[k + 1]),
                Mth.lerp(partialTick, this.axisPrev[k + 2], this.axisNow[k + 2]));
    }

    /** クライアント: 描くときの部位の向き(モデル → ワールド、roll 込み、前の tick との slerp)。 */
    public Quaternionf renderFrame(int index, float partialTick, Quaternionf out) {
        return this.framePrev[index].slerp(this.frameNow[index], partialTick, out);
    }

    /** クライアント: この tick に頭が動いたか(crawl / idle を選ぶ。速度は同期されないので位置の差で見る)。 */
    public boolean isCrawlingClient() {
        return this.distanceToSqr(this.xo, this.yo, this.zo) > 0.02D * 0.02D;
    }

    /** クライアント: 節 index を flare(膨らんで硬くなる)にするか。頭が飛び出している間の前の 4 節・殴られた直後の全部。 */
    public boolean isFlaring(int index) {
        int act = getAction();
        if ((act == ACT_BURST || act == ACT_SNAP) && index <= 4) {
            return true;
        }
        return this.hurtTime > 0;
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        return this.cullBox != null ? this.cullBox : super.getBoundingBoxForCulling().inflate(SPACING * PARTS);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 96.0D * 96.0D;
    }

    // ================================================================== サーバーの考え

    @Override
    public void aiStep() {
        if (!this.level().isClientSide() && this.isAlive() && !this.isNoAi()) {
            serverBrain();
        } else if (!this.level().isClientSide()) {
            this.moveVel = Vec3.ZERO;
        }
        super.aiStep();
        if (!this.level().isClientSide()) {
            applyHeading();
        }
    }

    /** 動き: 自分で決めた速さでそのまま動く(地形をすり抜ける)。{@code isControlledByLocalInstance} の囲みは必須(クライアントは補間で動く)。 */
    @Override
    public void travel(Vec3 travelVector) {
        if (this.isControlledByLocalInstance()) {
            this.move(MoverType.SELF, this.moveVel);
            this.setDeltaMovement(this.moveVel);
        }
    }

    private void serverBrain() {
        tickNoise();
        tickActionEnd();
        if (this.attackCooldown > 0) {
            this.attackCooldown--;
        }
        if (this.settleCooldown > 0) {
            this.settleCooldown--;
        }
        if (this.breachCooldown > 0) {
            this.breachCooldown--;
        }
        if (this.strikeCooldown > 0) {
            this.strikeCooldown--;
        }
        if (this.outing == Outing.NONE) {
            if (this.outingCooldown > 0) {
                this.outingCooldown--;
            }
        } else {
            this.outingTicks++;
            this.outingAge++;
            this.outingIdle = this.outing == Outing.SURFACE && !isHunting() ? this.outingIdle + 1 : 0;
        }
        if (this.heading == null) {
            this.heading = headLook();
        }
        if (this.tickCount % NOTICE_INTERVAL == 0) {
            noticeCreatures();
        }
        this.modeTicks++;
        this.moveVel = Vec3.ZERO;
        LivingEntity prey = validPrey();
        Mode before = this.mode;
        tickMode(prey);
        if (this.mode != before && this.moveVel.lengthSqr() < 1.0E-6D) {
            // 段階が替わった tick も新しい段階で動く(替わるだけで動かない tick を作らない。2026-10-09 ユーザー指定「常に動き続ける」)
            tickMode(prey);
        }
        tickDigSound();
    }

    private void tickMode(@Nullable LivingEntity prey) {
        switch (this.mode) {
            case REST -> {
                // 湧いた・読み込んだ直後の 1 tick だけ(テストの holdStillForTests は待たせる)。体が地面から出ていても、動き出せば頭の道をなぞって穴へ入る
                if (!isHeadInEarth()) {
                    enterDive();
                } else if (prey != null || hasFreshNoise()) {
                    enterHunt();
                } else if (--this.restTimer <= 0) {
                    enterRoam(null);
                    tickRoam(null);
                }
            }
            case SETTLE -> tickSettle(prey);
            case ROAM -> tickRoam(prey);
            case BREACH -> tickBreach(prey);
            case DIVE -> tickDive(prey);
            case HUNT -> tickHunt(prey);
            case BURST -> tickBurst(prey);
            case SNAP -> tickSnap(prey);
            case CROSS -> tickCross(prey);
        }
    }

    private void setMode(Mode m) {
        this.mode = m;
        this.modeTicks = 0;
    }

    /** min〜max(両端を含む)の乱数。 */
    private int randomBetween(int min, int max) {
        return min + this.random.nextInt(max - min + 1);
    }

    public Mode getMode() {
        return this.mode;
    }

    /** いま獲物を追っているか(聞いて向かっている・相手の下を回る・攻撃中)。 */
    public boolean isHunting() {
        return this.mode == Mode.HUNT || this.mode == Mode.BURST || this.mode == Mode.SNAP
                || this.mode == Mode.CROSS || (this.mode == Mode.BREACH && this.strikeTarget != null);
    }

    // ---------------------------------------------------------------- 音

    /**
     * 採掘の音を聞いた(サーバー)。聞こえる距離(追跡の距離の属性)より遠ければ何もしない。
     *
     * @param where  音のした点
     * @param source 音を立てた相手(獲物にする)。null なら点だけ覚える
     */
    public void hearNoise(Vec3 where, @Nullable LivingEntity source) {
        if (!this.isAlive()) {
            return;
        }
        double range = hearingRange();
        if (axisPoint().distanceToSqr(where) > range * range) {
            return;
        }
        this.noiseTimer = LOSE_TICKS;
        this.noisePos = where;
        if (source != null && source != this) {
            this.setTarget(source);
            this.lastKnownTarget = source;
        }
    }

    public double hearingRange() {
        return this.getAttributeValue(Attributes.FOLLOW_RANGE);
    }

    /**
     * 相手に気付く({@link #NOTICE_INTERVAL} ごと。10-02 のユーザー指定「洞窟内に player が来たら襲いに行く」「player 以外の mob も攻撃する」
     * 「たまに地上に出て mob を殺しに行く」)。プレイヤーは {@link #canNoticeInCave} を満たすいちばん近いプレイヤー(今の標的がプレイヤーでなければ
     * 替える = 対人の G5)。地表のプレイヤーには遠出の間も目では気付かない(採掘の音・殴られたときだけ。仮決め)。
     * Mob は今の標的がまだ気付ける所に居ればそのまま、居なければいちばん近い気付ける Mob({@link #canNoticeMob}: 洞窟の中の Mob、
     * 遠出の間は地表の Mob も)。居続ける間は見失わない。
     */
    private void noticeCreatures() {
        if (!(this.level() instanceof ServerLevel level) || !this.isAlive()) {
            return;
        }
        Vec3 me = axisPoint();
        Player bestPlayer = null;
        double bestPlayerD = Double.MAX_VALUE;
        for (Player p : level.players()) {
            if (canNoticeInCave(p)) {
                double d = p.distanceToSqr(me);
                if (d < bestPlayerD) {
                    bestPlayer = p;
                    bestPlayerD = d;
                }
            }
        }
        if (bestPlayer != null) {
            notice(bestPlayer, true);
            return;
        }
        if (!this.noticeMobsForTests) {
            return;
        }
        LivingEntity current = validPrey();
        if (current != null && !(current instanceof Player) && canNoticeMob(current)) {
            // 今の標的がまだ気付ける所に居る: 近くへほかの Mob が来ても替えずに追い続ける(前は近い方にしか気付き直さず、8 秒で見失っていた)
            notice(current, false);
            return;
        }
        LivingEntity bestMob = null;
        double bestMobD = Double.MAX_VALUE;
        AABB box = new AABB(me, me).inflate(hearingRange());
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> !(e instanceof Player) && e != this)) {
            if (canNoticeMob(e)) {
                double d = e.distanceToSqr(me);
                if (d < bestMobD) {
                    bestMob = e;
                    bestMobD = d;
                }
            }
        }
        if (bestMob != null) {
            notice(bestMob, false);
        }
    }

    /**
     * 洞窟の中に居て気付ける相手か: 気付ける種類({@link #isNoticeableKind})で、聞こえる距離の中(スニークしているプレイヤーはその 1/3)、
     * 目の位置から空が見えない(空の光が 15 でない)。
     */
    public boolean canNoticeInCave(LivingEntity e) {
        if (!isNoticeableKind(e) || !withinNoticeRange(e)) {
            return false;
        }
        return !this.level().canSeeSky(BlockPos.containing(e.getEyePosition()));
    }

    /**
     * 地表の Mob に気付けるか(遠出の間だけ使う。プレイヤーは除く): 気付ける種類で、聞こえる距離の中、目の位置から空が見え、
     * 足元 2 マス以内に床がある({@link #standsOnFloor}。跳びかかる床が下に無い相手 = 飛んでいるオウム・ハチ・ファントムなどは追わない)。
     */
    public boolean canNoticeOnSurface(LivingEntity e) {
        if (e instanceof Player || !isNoticeableKind(e) || !withinNoticeRange(e)) {
            return false;
        }
        return this.level().canSeeSky(BlockPos.containing(e.getEyePosition())) && standsOnFloor(e);
    }

    /** 気付ける Mob か: 洞窟の中の Mob、遠出(上っている・地表に居る)の間は地表の Mob も。 */
    private boolean canNoticeMob(LivingEntity e) {
        return canNoticeInCave(e) || (isSurfaceNoticing() && canNoticeOnSurface(e));
    }

    /** 地表の Mob に気付く間か: 地上に棲む個体はいつも(10-09)、洞窟の個体は遠出で上っている・地表に居る間(始めてから {@link #OUTING_MAX_TICKS} まで)。 */
    private boolean isSurfaceNoticing() {
        return this.habitat == Habitat.SURFACE
                || ((this.outing == Outing.ASCEND || this.outing == Outing.SURFACE) && this.outingAge <= OUTING_MAX_TICKS);
    }

    /**
     * 気付ける種類か: 過繁茂側でない相手({@link Predation#isInvaderTarget}。プレイヤーはクリエイティブ・観戦者・寄生植物装備の宿主を除く。
     * Mob はグループ 1〜10・汚染シダ・レイドボスを除く = ほかの G5 と同じ範囲)。Mob のうち防具立て・コウモリ(空を飛び回り、跳びかかる床が下に無い)・
     * 水の中の相手(魚・イカ。アーチは液体を通らない)は除く(仮決め)。
     */
    private boolean isNoticeableKind(LivingEntity e) {
        if (!e.isAlive() || e == this || e.level() != this.level() || !Predation.isInvaderTarget(this, e)) {
            return false;
        }
        return e instanceof Player
                || !(e instanceof ArmorStand || e.getType().getCategory() == MobCategory.AMBIENT || e.isInWater());
    }

    /** 聞こえる距離の中か(スニークしているプレイヤーはその 1/3)。 */
    private boolean withinNoticeRange(LivingEntity e) {
        double range = hearingRange() * (e instanceof Player p && p.isShiftKeyDown() ? SNEAK_NOTICE_FACTOR : 1.0D);
        return e.distanceToSqr(axisPoint()) <= range * range;
    }

    /** 足元 2 マス以内に床(岩の上の空き)があるか(跳びかかりの計画 {@link #planStrike} と同じ探し方)。 */
    private boolean standsOnFloor(LivingEntity e) {
        return !Double.isNaN(findFloor(this.level(), e.getX(), e.getZ(), e.getY(), 2.0D, 0.0D));
    }

    /** 気付いた({@link #notice(LivingEntity, boolean)} の、今の標的を替えない版。殴られた相手など)。 */
    private void notice(LivingEntity who) {
        notice(who, false);
    }

    /**
     * 居ることに気付いた(洞窟に来た・殴られた)。採掘の音と違って距離は見ない。今の標的がまだ有効でほかの相手なら替えない
     * (override のときは、今の標的がプレイヤーでなければ替える)。標的は音と同じく {@link #LOSE_TICKS} 気付かなければ見失う。
     */
    private void notice(LivingEntity who, boolean override) {
        if (!this.isAlive() || who == this) {
            return;
        }
        LivingEntity current = validPrey();
        if (current != null && current != who && !(override && !(current instanceof Player))) {
            return;
        }
        this.setTarget(who);
        this.lastKnownTarget = who;
        this.noiseTimer = LOSE_TICKS;
        this.noisePos = who.position();
    }

    public int noiseTimer() {
        return this.noiseTimer;
    }

    /** まだ見失っていない音があるか(音を立てた相手が分からなくても、音のした点へは向かう)。 */
    private boolean hasFreshNoise() {
        return this.noiseTimer > 0 && this.noisePos != null;
    }

    private void tickNoise() {
        LivingEntity t = this.getTarget();
        if (t != null && t != this.lastKnownTarget) {
            // ほかの仕組み(殴り返し・群れなど)が付けた標的は、その時点で「聞いた」とみなす
            this.lastKnownTarget = t;
            this.noiseTimer = LOSE_TICKS;
            this.noisePos = t.position();
        }
        if (this.noiseTimer > 0) {
            this.noiseTimer--;
            if (this.noiseTimer == 0) {
                // 8 秒途絶えた: 見失う
                this.setTarget(null);
                this.lastKnownTarget = null;
                this.noisePos = null;
            }
        }
    }

    @Nullable
    private LivingEntity validPrey() {
        LivingEntity t = this.getTarget();
        if (t == null || this.noiseTimer <= 0 || !t.isAlive() || t.level() != this.level()
                || (t instanceof Player p && (p.isCreative() || p.isSpectator()))
                || t.distanceToSqr(this) > Mth.square(hearingRange() * 1.5D)) {
            return null;
        }
        return t;
    }

    // ---------------------------------------------------------------- 動作の同期

    public int getAction() {
        return this.entityData.get(DATA_ACTION);
    }

    /** 今の動作を始めてからの tick(同期したゲーム時刻から)。 */
    public float actionAge(float partialTick) {
        return (float) (this.level().getGameTime() - this.entityData.get(DATA_ACTION_TIME)) + partialTick;
    }

    private void setAction(int action) {
        this.entityData.set(DATA_ACTION, action);
        this.entityData.set(DATA_ACTION_TIME, (int) this.level().getGameTime());
        this.entityData.set(DATA_SEQ, this.entityData.get(DATA_SEQ) + 1);
    }

    /** 終わりのある動作(snap・retract)を時間で切る。 */
    private void tickActionEnd() {
        int act = getAction();
        float age = actionAge(0.0F);
        if (act == ACT_SNAP && age >= SNAP_TICKS) {
            setAction(ACT_NONE);
        }
    }

    // ---------------------------------------------------------------- 段階ごと

    private void enterHunt() {
        setMode(Mode.HUNT);
        this.planTimer = 0;
        this.plannedFor = null;
        this.strikePlanTimer = 0;
        this.strikeBlocked = false;
        clearPlan();
        if (this.outing == Outing.ASCEND) {
            // 上る途中で獲物に気付いた(地表の Mob・洞窟の相手・音): ここからは地表の段階(狩り終えたら地表の下を進んで次を探す)
            enterSurfacePhase();
        }
    }

    private void clearPlan() {
        this.lurkAxis = null;
        this.lurkFace = null;
        this.lurkNormal = null;
        this.lurkCell = null;
        this.stagingAxis = null;
    }

    /**
     * HUNT: 相手の居る場所で攻め方を決める(10-02、仮決め)。相手の足元の上が開けた洞窟・地表(空き {@link #STRIKE_MIN_HEADROOM} マス以上)なら
     * 床の下から跳びかかる(STRIKE = 相手の上半身を通るアーチで噛む)。狭い所(高さ 2 の坑道 = ブランチマイニング)では、
     * 真横の壁(無ければ床)の奥から助走して壁を破り、相手を噛んで向かいの壁へ抜ける。決め手は場所の形だけ(掘っていたかどうかは見ない)。
     * <b>どの間も止まらない</b>(2026-10-09 ユーザー指定「攻撃の際にも止まらず、常に動き続ける」): 次に攻められるまでは相手の下を回り、
     * 予兆(ひび・かちかち)は寄せながら見せる。前は跳びかかりの前に床の下で 1.2 秒溜め、坑道では壁の中で口の先を出して 1.5 秒以上待っていた。
     */
    private void tickHunt(@Nullable LivingEntity prey) {
        double s = this.getScale();
        if (prey == null) {
            if (hasFreshNoise()) {
                // 音を立てた相手が分からない(居なくなった・音だけ): 音のした所の下を回る
                circle(this.noisePos.x, this.noisePos.z, this.noisePos.y - BREACH_DEPTH * s);
                return;
            }
            resumeRoam();
            return;
        }
        // 頭が空気の中(潜り直しの途中)では計画しない(寄せ始めてすぐやめ、毎 tick 計画し直すことになる)
        if (this.strikeCooldown <= 0 && isHeadInEarth() && --this.strikePlanTimer <= 0) {
            this.strikePlanTimer = STRIKE_PLAN_INTERVAL;
            BreachPlan plan = planStrike(prey, strikeLaunchGuess(prey));
            this.strikeBlocked = plan == null;
            if (plan != null) {
                enterBreach(plan, prey);
                tickBreach(prey);
                return;
            }
        }
        if (!this.strikeBlocked || this.attackCooldown > 0) {
            // 開けた所の相手(次に跳びかかれるまで)・坑道の相手(向かいへ抜けた後の 3 秒): 相手の床の下を回る
            stalk(prey);
            return;
        }
        if (--this.planTimer <= 0 || this.plannedFor == null || this.plannedFor.distanceToSqr(prey.position()) > 4.0D) {
            planLurk(prey);
            this.planTimer = 20;
        }
        if (this.lurkAxis == null) {
            // 飛び出せる壁・床が無い: 相手の下を回る
            stalk(prey);
            return;
        }
        if (this.stagingAxis != null) {
            // 壁の奥の助走の点へ
            if (steer(this.stagingAxis, moveSpeed(HUNT_SPEED), TURN_HUNT, true) < 0.5D * s) {
                this.stagingAxis = null;
            }
            return;
        }
        if (preyInFrontOfWall(prey)) {
            // 助走の線に乗った: 壁を破りに行く(burst)
            setMode(Mode.BURST);
            setAction(ACT_BURST);
            tickBurst(prey);
            return;
        }
        // 相手が面の前に居ない: 次の tick に計画し直す(その間も相手の下を回る)
        this.planTimer = 0;
        stalk(prey);
    }

    /** 坑道の攻め: 相手(の胴)が壁の面の前(外向きに 0.2 より先)の、飛び出してよい距離 {@link #STRIKE_RANGE} の中に居るか。 */
    private boolean preyInFrontOfWall(LivingEntity prey) {
        if (this.lurkFace == null || this.lurkNormal == null) {
            return false;
        }
        Vec3 c = preyCenter(prey);
        return c.distanceTo(this.lurkFace) <= STRIKE_RANGE && c.subtract(this.lurkFace).dot(this.lurkNormal) > 0.2D;
    }

    /**
     * BURST(坑道の攻め): 助走の線から面の手前の点(lurkAxis)へ、burst の壁を破る瞬間({@link #BURST_BREAK_TICK})に着く速さで寄せる
     * (止まらない。狩りの速さの {@link #APPROACH_MIN_FACTOR}〜1 倍)。寄せる間に壁のひびとかちかち(予兆)。着いたら壁を破って SNAP。
     * 唇を開く burst のアニメは頭が壁の中なので見えない(前は面の手前で止まって流していた)。
     */
    private void tickBurst(@Nullable LivingEntity prey) {
        int t = this.modeTicks;
        if (this.lurkAxis == null || this.lurkFace == null || this.lurkNormal == null || t > BURST_TICKS + 40) {
            // 計画が消えた・寄せられない(岩盤など)
            setAction(ACT_NONE);
            clearPlan();
            resumeHunt(prey);
            return;
        }
        Level level = this.level();
        BlockState wall = this.lurkCell != null ? level.getBlockState(this.lurkCell) : null;
        Vec3 face = this.lurkFace;
        if (t == 0 && wall != null) {
            // 予兆: 壁にひびのパーティクルと掘る音
            wallParticles(wall, face, 8, 0.05D);
            level.playSound(null, face.x, face.y, face.z, wall.getSoundType().getHitSound(), SoundSource.HOSTILE, 1.2F, 0.5F);
        }
        if (t == BURST_CLICK_1 || t == BURST_CLICK_2) {
            level.playSound(null, face.x, face.y, face.z, SoundEvents.EVOKER_FANGS_ATTACK, SoundSource.HOSTILE, 0.35F, 1.7F);
            if (wall != null) {
                wallParticles(wall, face, 4, 0.03D);
            }
        }
        double hunt = moveSpeed(HUNT_SPEED);
        Vec3 toAxis = this.lurkAxis.subtract(axisPoint());
        double d = toAxis.length();
        double v = Mth.clamp(d / Math.max(1, BURST_BREAK_TICK - t), hunt * APPROACH_MIN_FACTOR, hunt);
        // 着いた(か、すぐ横・後ろを行き過ぎた: 回って戻らずに破る)
        if (d <= v || (d < this.getScale() && toAxis.dot(this.heading) < 0.0D)) {
            // 壁を破る瞬間: 破片と大きな音(ブロックは壊さない)→ そのまま相手へ突っ込む
            if (wall != null) {
                wallParticles(wall, face, 30, 0.15D);
                level.playSound(null, face.x, face.y, face.z, wall.getSoundType().getBreakSound(), SoundSource.HOSTILE, 2.0F, 0.6F);
            }
            level.playSound(null, face.x, face.y, face.z, SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 0.5F, 1.8F);
            setMode(Mode.SNAP);
            setAction(ACT_SNAP);
            this.strikeBitten = false;
            tickSnap(prey);
            return;
        }
        steer(this.lurkAxis, v, TURN_LURK, true);
    }

    /**
     * SNAP: 相手の胴へ突っ込んで噛む。噛む点が snap の当たりの tick({@link #SNAP_HIT_TICK})に胴へ着く速さで、向きは胴を追う
     * (止まらない。狩りの速さの {@link #APPROACH_MIN_FACTOR} 倍より遅くしない。噛んだら向きを変えずに突き抜ける)。
     * 当たりの tick の前後 3 tick に届けば噛み、過ぎたら向かいの壁・床へ抜ける(CROSS)。
     */
    private void tickSnap(@Nullable LivingEntity prey) {
        int t = this.modeTicks;
        double v = moveSpeed(HUNT_SPEED) * APPROACH_MIN_FACTOR;
        if (prey != null && !this.strikeBitten) {
            Vec3 to = preyCenter(prey).subtract(axisPoint());
            double len = to.length();
            if (len > 1.0E-4D) {
                turnHeading(to.scale(1.0D / len), TURN_SNAP);
                double want = len - BITE_FWD * this.getScale();
                v = Mth.clamp(want / Math.max(1, SNAP_HIT_TICK - t), v, moveSpeed(LUNGE_SPEED));
            }
        }
        this.moveVel = this.heading.scale(v);
        if (!this.strikeBitten && prey != null && Math.abs(t - SNAP_HIT_TICK) <= 3 && inBiteReach(prey)) {
            bite(prey);
        }
        if (t >= SNAP_HIT_TICK + 3 || (this.strikeBitten && t >= SNAP_HIT_TICK)) {
            if (!this.strikeBitten) {
                // 空振り: 口を閉じる音だけ
                Vec3 b = bitePoint();
                this.level().playSound(null, b.x, b.y, b.z, SoundEvents.EVOKER_FANGS_ATTACK, SoundSource.HOSTILE, 1.3F, 0.7F);
            }
            enterCross();
        }
    }

    /** 噛める所に相手が居るか: 頭の当たり判定(届くぶん広げる)か、噛む点の届く距離({@link #BITE_REACH} × 体格)。 */
    private boolean inBiteReach(LivingEntity prey) {
        if (!prey.isAlive() || prey.level() != this.level()) {
            return false;
        }
        double reach = BITE_REACH * this.getScale();
        return this.getBoundingBox().inflate(reach * 0.5D).intersects(prey.getBoundingBox())
                || prey.getBoundingBox().distanceToSqr(bitePoint()) <= reach * reach;
    }

    /** 噛む(攻撃力の 1 回分)。跳びかかり・坑道の攻めで 1 回だけ。 */
    private void bite(LivingEntity prey) {
        this.strikeBitten = true;
        Vec3 b = bitePoint();
        this.level().playSound(null, b.x, b.y, b.z, SoundEvents.EVOKER_FANGS_ATTACK, SoundSource.HOSTILE, 1.3F, 0.7F);
        this.doHurtTarget(prey);
    }

    /** 攻めをやめた後: 相手か音が残っていれば HUNT(相手の下を回る)、無ければうろつき。 */
    private void resumeHunt(@Nullable LivingEntity prey) {
        if (prey != null || hasFreshNoise()) {
            enterHunt();
        } else {
            resumeRoam();
        }
    }

    /** snap の当たりの瞬間の噛む点(頭の中心の 0.89 前・軸の 0.15 上。上は頭の背の向き)。 */
    public Vec3 bitePoint() {
        double s = this.getScale();
        Vec3 f = this.heading != null ? this.heading : headLook();
        Vec3 up = uprightUp(f);
        return axisPoint().add(f.scale(BITE_FWD * s)).add(up.scale(BITE_UP * s));
    }

    private void enterCross() {
        setMode(Mode.CROSS);
        this.diveTarget = pickDiveTarget();
    }

    private void tickCross(@Nullable LivingEntity prey) {
        if (this.diveTarget == null) {
            this.diveTarget = pickDiveTarget();
        }
        double d = steer(this.diveTarget, moveSpeed(CROSS_SPEED), TURN_CROSS, false);
        boolean inRock = this.modeTicks > 6 && isHeadInEarth() && isEarthAt(axisPoint().add(this.heading));
        if (inRock || d < 0.3D || this.modeTicks > 100) {
            this.attackCooldown = ATTACK_COOLDOWN;
            this.diveTarget = null;
            clearPlan();
            if (prey != null) {
                enterHunt();
            } else {
                resumeRoam();
            }
        }
    }

    private void enterDive() {
        setMode(Mode.DIVE);
        this.diveTarget = pickGroundTarget();
    }

    private void tickDive(@Nullable LivingEntity prey) {
        if (this.diveTarget == null) {
            this.diveTarget = pickGroundTarget();
        }
        double d = steer(this.diveTarget, moveSpeed(DIVE_SPEED), TURN_HUNT, false);
        boolean inRock = this.modeTicks > 4 && isHeadInEarth() && isEarthAt(axisPoint().add(this.heading));
        if (inRock || d < 0.3D || this.modeTicks > 200) {
            this.diveTarget = null;
            if (prey != null) {
                enterHunt();
            } else {
                afterDive();
            }
        }
    }

    /** 獲物が居なくなった・跳び終えた: うろつきへ戻る(常に動き続ける。10-02)。頭が空気の中なら潜り直す(DIVE)。 */
    private void resumeRoam() {
        if (!isHeadInEarth()) {
            setMode(Mode.DIVE);
        } else {
            enterRoam(null);
        }
    }

    /**
     * 空気の中から地面へ潜り終えた(卵・/summon・潜り損ね): 体(節・尾)が地面から出ていれば、先に穴から全部引き込む(SETTLE)、
     * 入っていればうろつきへ。
     */
    private void afterDive() {
        if (this.settleCooldown <= 0 && !isBodyUnderground()) {
            enterSettle();
        } else {
            enterRoam(null);
        }
    }

    private void enterSettle() {
        setMode(Mode.SETTLE);
        this.settleFloorY = axisPoint().y - SETTLE_DEPTH * this.getScale();
    }

    /**
     * SETTLE: 頭の開けた穴から体を全部引き込む(ユーザー指定「全部の節が頭の開けた穴を通る」を、眠る前に最後まで)。
     * 今の向き(水平の成分)へ、始めた所から {@link #SETTLE_DEPTH} × 体格の深さまでは斜め下 45°、そこからは水平に地中だけを進む。
     * 体が全部地面の下になったらうろつきへ(10-02 まで眠っていた)。{@link #SETTLE_MAX_TICKS} で諦め、{@link #SETTLE_RETRY_TICKS} は引き込み直さない。
     */
    private void tickSettle(@Nullable LivingEntity prey) {
        if (prey != null || hasFreshNoise()) {
            enterHunt();
            return;
        }
        if (!isHeadInEarth()) {
            // 洞窟へ抜けた: 下の地面へ潜り直す(体はその後でまた引き込む)
            enterDive();
            return;
        }
        if (isBodyUnderground()) {
            enterRoam(null);
            return;
        }
        if (this.modeTicks > SETTLE_MAX_TICKS) {
            this.settleCooldown = SETTLE_RETRY_TICKS;
            enterRoam(null);
            return;
        }
        Vec3 axis = axisPoint();
        Vec3 flat = Vec3.directionFromRotation(0.0F, this.getYRot());
        Vec3 want = axis.y > this.settleFloorY ? flat.add(0.0D, -1.0D, 0.0D).normalize() : flat;
        steer(axis.add(want.scale(3.0D * this.getScale())), moveSpeed(SETTLE_SPEED), TURN_HUNT, true);
        if (this.heading.dot(want) < 0.0D) {
            // 行きたい向きと 90° 以上違ううちは、その場で向きだけ変える。自然に湧いた個体は頭が真上(床のすぐ下)を向いていて、
            // steer の「曲がりながら進む」では向きが返る前に床から頭が出てしまう(体は穴の中に残り、DIVE で潜り直すことになる)
            this.moveVel = Vec3.ZERO;
        }
    }

    /**
     * 体(節・尾。頭は除く)が全部地面の下か。描かない部位と同じ決まり(中心と前後の端の 3 点が全部岩の中)で、サーバーでも見られる。
     * 読み込まれていないマスは「岩」とみなす(端のチャンクで這い回らないように)。頭を除くのは、待ち伏せから引っ込んだ口の先が
     * 壁の面に掛かっていても眠れるように(頭の見え隠れは頭が自分で潜って決める)。
     */
    public boolean isBodyUnderground() {
        if (!this.bodySolved) {
            return true;
        }
        Level level = this.level();
        double half = SPACING * 0.5D * this.getScale();
        for (int i = 0; i < PARTS; i++) {
            double cx = this.partPos[3 * i];
            double cy = this.partPos[3 * i + 1];
            double cz = this.partPos[3 * i + 2];
            double fx = this.partFwd[3 * i] * half;
            double fy = this.partFwd[3 * i + 1] * half;
            double fz = this.partFwd[3 * i + 2] * half;
            if (!isEarthOrUnloaded(level, BlockPos.containing(cx, cy, cz))
                    || !isEarthOrUnloaded(level, BlockPos.containing(cx + fx, cy + fy, cz + fz))
                    || !isEarthOrUnloaded(level, BlockPos.containing(cx - fx, cy - fy, cz - fz))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isEarthOrUnloaded(Level level, BlockPos pos) {
        BlockState st = stateIfLoaded(level, pos);
        return st == null || isEarthState(level, pos, st);
    }

    // ---------------------------------------------------------------- うろつき・洞窟へのアーチ跳び(2026-10-01 夜のユーザー指定)

    /** うろつき始める。dir が null なら近くの空気(洞窟)の向き、無ければ乱数の向き。棲み場所がまだなら、ここで決める。 */
    private void enterRoam(@Nullable Vec3 dir) {
        if (this.habitat == Habitat.UNKNOWN) {
            decideHabitat();
        }
        setMode(Mode.ROAM);
        this.roamTicks = 0;
        this.excursionTicks = 0;
        this.roamBaseY = axisPoint().y;
        this.roamDir = dir != null ? flatten(dir) : pickRoamDir();
    }

    /**
     * うろつく向き: 水平 8 方向(始まりの角度は乱数)に {@link #ROAM_SEEK_RANGE} × 体格まで、アーチの床を探すのと同じ高さの幅
     * (頭から上 {@link #BREACH_FLOOR_ABOVE}・下 {@link #BREACH_FLOOR_BELOW} × 体格、3 マスおき)の空気を探し、いちばん近い向き
     * (洞窟の方へ行かないと、岩の中をうろつくだけで飛び出す所が見つからない。跳んで潜った後は床より 体格 × 5 ほど深くに居る)。無ければ乱数の向き。
     */
    private Vec3 pickRoamDir() {
        Level level = this.level();
        Vec3 axis = axisPoint();
        double s = this.getScale();
        int range = Mth.ceil(ROAM_SEEK_RANGE * s);
        List<Integer> heights = new ArrayList<>();
        heights.add(0);
        for (int dy = 3; dy <= BREACH_FLOOR_ABOVE * s; dy += 3) {
            heights.add(dy);
        }
        for (int dy = -3; dy >= -BREACH_FLOOR_BELOW * s; dy -= 3) {
            heights.add(dy);
        }
        float start = this.random.nextFloat() * 45.0F;
        Vec3 best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int k = 0; k < 8; k++) {
            Vec3 dir = Vec3.directionFromRotation(0.0F, start + k * 45.0F);
            search:
            for (int d = 2; d <= range && d < bestDist; d++) {
                for (int dy : heights) {
                    BlockState st = stateIfLoaded(level, BlockPos.containing(axis.x + dir.x * d, axis.y + dy, axis.z + dir.z * d));
                    if (st != null && st.isAir()) {
                        best = dir;
                        bestDist = d;
                        break search;
                    }
                }
            }
        }
        return best != null ? best : Vec3.directionFromRotation(0.0F, this.random.nextFloat() * 360.0F);
    }

    private static Vec3 flatten(Vec3 v) {
        Vec3 f = new Vec3(v.x, 0.0D, v.z);
        double l = f.length();
        return l < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : f.scale(1.0D / l);
    }

    /**
     * ROAM: 地中をうろつき続ける(終わらない。10-02 ユーザー「常に動き続ける」)。洞窟の方へ向かい(高さは始めた所 ± {@link #ROAM_DEPTH_BAND} × 体格)、
     * {@link #BREACH_SCAN_INTERVAL} ごとに洞窟へ飛び出せるか見て、飛び出せればアーチ(BREACH)へ —— 跳んだ先からも続けて跳ぶので、
     * 洞窟があれば床の下を進みながら跳ね続ける。アーチの後ときどき({@link #ROAM_EXCURSION_CHANCE})地中だけを進む寄り道を挟む。
     * アーチを描かずに {@link #ROAM_REPICK_TICKS} うろついたら、洞窟の方へ向きを選び直す。音を聞く・洞窟のプレイヤーに気付けば HUNT。
     * 次の遠出までの待ちが明けていれば地表の獲物を探し、居れば遠出(上る → 地表 → 戻る。{@link #tickAscend}・{@link #tickDescend})。
     * 地表の段階では、高さは頭の上の地表の {@link #BREACH_DEPTH} × 体格 下、向きは獲物の方、アーチは空の下でも跳ぶ(地表で跳ねる)。
     * 地上に棲む個体({@link Habitat#SURFACE}、10-09)はずっと地表の段階と同じ動きで、遠出はしない(戻る先が無い)。
     */
    private void tickRoam(@Nullable LivingEntity prey) {
        if (prey != null || hasFreshNoise()) {
            enterHunt();
            return;
        }
        if (this.outing == Outing.ASCEND) {
            tickAscend();
            return;
        }
        if (!isHeadInEarth()) {
            enterDive();
            return;
        }
        if (this.outing == Outing.DESCEND) {
            tickDescend();
            return;
        }
        // 地表の下を進む: 遠出の地表の段階と、地上に棲む個体(ずっと。戻らない)
        boolean surface = this.outing == Outing.SURFACE || this.habitat == Habitat.SURFACE;
        if (surface) {
            if (this.outing == Outing.SURFACE && (this.outingIdle > this.surfaceIdleLimit || this.outingAge > OUTING_MAX_TICKS)) {
                startDescend();
                tickDescend();
                return;
            }
            if (this.roamDir == null || this.tickCount % SURFACE_REFRESH_INTERVAL == 0) {
                refreshSurfaceRoam();
            }
        } else {
            if (this.outingCooldown <= 0 && this.noticeMobsForTests) {
                LivingEntity quarry = findSurfaceQuarry();
                if (quarry != null) {
                    startOuting(quarry);
                    tickAscend();
                    return;
                }
                this.outingCooldown = OUTING_RETRY_TICKS;
            }
            if (this.roamDir == null || ++this.roamTicks % ROAM_REPICK_TICKS == 0) {
                this.roamDir = pickRoamDir();
                this.roamBaseY = axisPoint().y;
            }
        }
        if (this.excursionTicks > 0) {
            this.excursionTicks--;
        } else if (this.breachCooldown <= 0 && this.modeTicks % BREACH_SCAN_INTERVAL == 1 && !this.noRoamBreachForTests) {
            BreachPlan plan = planBreach(axisPoint(), this.roamDir, surface);
            if (plan != null) {
                enterBreach(plan, null);
                tickBreach(null);
                return;
            }
        }
        double s = this.getScale();
        Vec3 axis = axisPoint();
        double band = (surface ? SURFACE_DEPTH_BAND : ROAM_DEPTH_BAND) * s;
        double vy = axis.y > this.roamBaseY + band ? -0.4D : axis.y < this.roamBaseY - band ? 0.4D : 0.0D;
        Vec3 want = this.roamDir.add(0.0D, vy, 0.0D).normalize();
        steer(axis.add(want.scale(3.0D * s)), moveSpeed(ROAM_SPEED), TURN_HUNT, true);
    }

    // ---------------------------------------------------------------- 地上への遠出(2026-10-02 のユーザー指定)

    /**
     * 遠出の獲物を探す: 地表(目の位置から空が見える)に居て足元に床がある、気付ける種類の Mob(プレイヤーは除く)のうち、
     * 水平に {@link #outingRange} の中・頭より上 {@link #OUTING_MAX_RISE}・下 {@link #OUTING_MAX_DROP} ブロックの中でいちばん近いもの。
     * 地中から地表の足音を感じ取る扱い(気付く距離より広く、深くても届く)。
     */
    @Nullable
    private LivingEntity findSurfaceQuarry() {
        if (!(this.level() instanceof ServerLevel level) || !this.noticeMobsForTests) {
            return null;
        }
        Vec3 me = axisPoint();
        double r = outingRange();
        AABB box = new AABB(me.x - r, me.y - OUTING_MAX_DROP, me.z - r, me.x + r, me.y + OUTING_MAX_RISE, me.z + r);
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> !(e instanceof Player) && e != this)) {
            if (isSurfaceQuarry(e)) {
                double d = e.distanceToSqr(me);
                if (d < bestD) {
                    best = e;
                    bestD = d;
                }
            }
        }
        return best;
    }

    /** 遠出の獲物になれるか({@link #findSurfaceQuarry} の範囲・地表・足元に床)。上っている間、狙った獲物を追い続けるかもこれで見る。 */
    private boolean isSurfaceQuarry(LivingEntity e) {
        if (e instanceof Player || !isNoticeableKind(e)) {
            return false;
        }
        Vec3 me = axisPoint();
        double r = outingRange();
        double dx = e.getX() - me.x;
        double dz = e.getZ() - me.z;
        double rise = e.getY() - me.y;
        if (dx * dx + dz * dz > r * r || rise > OUTING_MAX_RISE || rise < -OUTING_MAX_DROP) {
            return false;
        }
        return this.level().canSeeSky(BlockPos.containing(e.getEyePosition())) && standsOnFloor(e);
    }

    /** 遠出の獲物を探す水平の範囲(聞こえる距離 × {@link #OUTING_RANGE_FACTOR})。 */
    private double outingRange() {
        return hearingRange() * OUTING_RANGE_FACTOR;
    }

    /** 遠出を始める(獲物の足元の床の下へ上る)。戻る先は今の高さ。 */
    private void startOuting(LivingEntity quarry) {
        this.outing = Outing.ASCEND;
        this.outingTicks = 0;
        this.outingAge = 0;
        this.outingIdle = 0;
        this.homeY = axisPoint().y;
        this.outingQuarry = quarry;
        this.ascendGoal = null;
    }

    /** 地表の段階へ(上る途中で獲物に気付いた・上り切った)。 */
    private void enterSurfacePhase() {
        this.outing = Outing.SURFACE;
        this.outingTicks = 0;
        this.outingIdle = 0;
        this.ascendGoal = null;
    }

    /** 戻り始める(地表で獲物が居ないまま時間が経った・遠出の時間切れ・上り切れない・獲物が居なくなった)。 */
    private void startDescend() {
        this.outing = Outing.DESCEND;
        this.outingTicks = 0;
        this.outingQuarry = null;
        this.ascendGoal = null;
    }

    /** 遠出を終える: 次の遠出まで {@link #OUTING_COOLDOWN_MIN}〜{@link #OUTING_COOLDOWN_MAX} 待ち、ここからうろつき直す(高さの基準もここ)。 */
    private void finishOuting() {
        this.outing = Outing.NONE;
        this.outingTicks = 0;
        this.outingAge = 0;
        this.outingIdle = 0;
        this.homeY = Double.NaN;
        this.outingQuarry = null;
        this.ascendGoal = null;
        this.outingCooldown = randomBetween(OUTING_COOLDOWN_MIN, OUTING_COOLDOWN_MAX);
        enterRoam(null);
    }

    /**
     * ASCEND: 獲物の足元の床の {@link #BREACH_DEPTH} × 体格 下へ上る(水平に寄りながら、急でも {@link #ASCEND_SLOPE} の傾きまで)。
     * 途中の洞窟は空気の中もそのまま突っ切る(地中の回り道は水平に進むときの作りで、縦に上ると洞窟の天井の下で迷う)。
     * 空の見える空気の手前だけは回り道する(崖・谷の外へ飛び出さない)。獲物が居なくなったら探し直し、居なければ戻る。
     * 獲物(地表の Mob・洞窟の相手・音)に気付けば HUNT へ移って地表の段階になる({@link #enterHunt})。上る先に着いても地表の段階へ。
     */
    private void tickAscend() {
        if (this.outingTicks > ASCEND_MAX_TICKS) {
            startDescend();
            return;
        }
        LivingEntity quarry = this.outingQuarry;
        if (quarry == null || !isSurfaceQuarry(quarry)) {
            quarry = findSurfaceQuarry();
            this.outingQuarry = quarry;
            this.ascendGoal = null;
            if (quarry == null) {
                startDescend();
                return;
            }
        }
        double s = this.getScale();
        if (this.ascendGoal == null || this.tickCount % SURFACE_REFRESH_INTERVAL == 0) {
            double floor = findFloor(this.level(), quarry.getX(), quarry.getZ(), quarry.getY(), 2.0D, 0.0D);
            if (!Double.isNaN(floor)) {
                this.ascendGoal = new Vec3(quarry.getX(), floor - BREACH_DEPTH * s, quarry.getZ());
            } else if (this.ascendGoal == null) {
                this.ascendGoal = new Vec3(quarry.getX(), quarry.getY() - BREACH_DEPTH * s, quarry.getZ());
            }
        }
        Vec3 axis = axisPoint();
        Vec3 to = this.ascendGoal.subtract(axis);
        if (to.length() < 1.5D * s) {
            enterSurfacePhase();
            enterRoam(to);
            return;
        }
        double horiz = Math.sqrt(to.x * to.x + to.z * to.z);
        Vec3 flat = horiz > 1.0E-3D ? new Vec3(to.x / horiz, 0.0D, to.z / horiz) : flatten(this.heading);
        double vy = Mth.clamp(to.y / Math.max(horiz, 0.5D), -ASCEND_SLOPE, ASCEND_SLOPE);
        Vec3 want = flat.add(0.0D, vy, 0.0D).normalize();
        BlockPos ahead = BlockPos.containing(axis.add(want.scale(1.5D * s)));
        boolean openSky = !isEarth(this.level(), ahead) && this.level().canSeeSky(ahead);
        steer(axis.add(want.scale(3.0D * s)), moveSpeed(HUNT_SPEED), TURN_HUNT, openSky);
    }

    /**
     * DESCEND: 遠出を始めた高さ({@link #homeY})へ、今の向きのまま斜め下 45° に地中を戻る。始めた高さ + 1 ブロックまで下りるか
     * {@link #DESCEND_MAX_TICKS} で終わり、そこからうろつき直す(洞窟があればまた跳ねる)。途中で音・洞窟の相手に気付けば HUNT(狩り終えたら戻りの続き)。
     */
    private void tickDescend() {
        Vec3 axis = axisPoint();
        if (Double.isNaN(this.homeY) || axis.y <= this.homeY + 1.0D || this.outingTicks > DESCEND_MAX_TICKS) {
            finishOuting();
            return;
        }
        double s = this.getScale();
        Vec3 flat = this.roamDir != null ? this.roamDir : flatten(this.heading);
        Vec3 want = flat.add(0.0D, -1.0D, 0.0D).normalize();
        steer(axis.add(want.scale(3.0D * s)), moveSpeed(ROAM_SPEED), TURN_HUNT, true);
    }

    /**
     * 地表の段階のうろつきの高さと向き({@link #SURFACE_REFRESH_INTERVAL} ごと): 高さの基準は頭の上の地表({@link #surfaceFloorAbove}。
     * 10-09 から洞窟・坑道の床は飛ばす)の {@link #BREACH_DEPTH} × 体格 下(丘・谷で地表の高さが変わってもついていく)。
     * 向きは獲物の方(居なくなったら探し直す。居なければ今の向きのまま)。
     */
    private void refreshSurfaceRoam() {
        Vec3 axis = axisPoint();
        double floor = surfaceFloorAbove(this.level(), axis, SURFACE_SEARCH_MAX);
        if (!Double.isNaN(floor)) {
            this.roamBaseY = floor - BREACH_DEPTH * this.getScale();
        }
        LivingEntity quarry = this.outingQuarry;
        if (quarry == null || !isSurfaceQuarry(quarry)) {
            quarry = this.tickCount % (2 * SURFACE_REFRESH_INTERVAL) == 0 ? findSurfaceQuarry() : null;
            this.outingQuarry = quarry;
        }
        if (quarry != null) {
            this.roamDir = flatten(quarry.position().subtract(axis));
        } else if (this.roamDir == null) {
            this.roamDir = flatten(this.heading);
        }
    }

    /**
     * from の真上の最初の床の高さ(岩の上の空きの下面 = 頭の上の地表・洞窟の床)。上へ max マスまでに無ければ NaN
     * (液体・読み込まれていない所に当たっても NaN)。木の幹・葉(過繁茂の木を含む。{@link TreeCanopyGroundFinder#isTreePart})に当たったら、
     * その下が岩ならそこを床とみなす(幹の根元 = 地面。幹を岩と数えて梢の上を地表と取り違えない)。
     */
    public static double floorAbove(Level level, Vec3 from, int max) {
        int x = Mth.floor(from.x);
        int z = Mth.floor(from.z);
        int y0 = Mth.floor(from.y);
        for (int y = y0 + 1; y <= y0 + max; y++) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState st = stateIfLoaded(level, p);
            if (st == null || !st.getFluidState().isEmpty()) {
                return Double.NaN;
            }
            if (TreeCanopyGroundFinder.isTreePart(st)) {
                return isEarth(level, p.below()) ? y : Double.NaN;
            }
            if (!isEarthState(level, p, st)) {
                return isFloorCell(level, x, y, z) ? y : Double.NaN;
            }
        }
        return Double.NaN;
    }

    /**
     * from の上の地表の高さ(上へ max マスまで。2026-10-09): 頭の上の床のうち、上に岩の天井が無いもの({@link #isOpenAbove})。
     * 洞窟・坑道の床は飛ばしてさらに上を探す(地上に棲む個体が坑道の音を追って潜った後、坑道の床を地表と取り違えて居着かないように)。
     * 液体・読み込まれていない所に当たったら・見つからなければ NaN。木の幹・葉に当たったら {@link #floorAbove} と同じく幹の根元。
     */
    public static double surfaceFloorAbove(Level level, Vec3 from, int max) {
        int x = Mth.floor(from.x);
        int z = Mth.floor(from.z);
        int y0 = Mth.floor(from.y);
        for (int y = y0 + 1; y <= y0 + max; y++) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState st = stateIfLoaded(level, p);
            if (st == null || !st.getFluidState().isEmpty()) {
                return Double.NaN;
            }
            if (TreeCanopyGroundFinder.isTreePart(st)) {
                return isEarth(level, p.below()) ? y : Double.NaN;
            }
            if (!isEarthState(level, p, st) && isFloorCell(level, x, y, z) && isOpenAbove(level, x, y, z)) {
                return y;
            }
        }
        return Double.NaN;
    }

    /**
     * (x, z) の柱で、床の上のマス y から上 {@link #BREACH_CEILING_SCAN} マスまでに岩の天井が無いか(地表か)。木の幹・葉は天井と数えない
     * (梢の下も地表)。読み込まれていない所に当たったら天井ありと見る。
     */
    static boolean isOpenAbove(Level level, int x, int y, int z) {
        for (int k = 1; k <= BREACH_CEILING_SCAN; k++) {
            BlockPos p = new BlockPos(x, y + k, z);
            if (p.getY() >= level.getMaxBuildHeight()) {
                return true;
            }
            BlockState st = stateIfLoaded(level, p);
            if (st == null) {
                return false;
            }
            if (!TreeCanopyGroundFinder.isTreePart(st) && isEarthState(level, p, st)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 棲み場所を決める(最初にうろつき始めるとき。2026-10-09、決め方は仮決め): 頭の上の最初の床(上 {@link #SURFACE_FLOOR_SCAN} マスまで)が
     * 地表({@link #isOpenAbove})なら SURFACE(地上で湧いた・地表で卵から出した)、洞窟の床か、床が見つからない(深い地中)なら CAVE。
     */
    private void decideHabitat() {
        Vec3 axis = axisPoint();
        double floor = floorAbove(this.level(), axis, SURFACE_FLOOR_SCAN);
        boolean surface = !Double.isNaN(floor) && isOpenAbove(this.level(), Mth.floor(axis.x), (int) floor, Mth.floor(axis.z));
        this.habitat = surface ? Habitat.SURFACE : Habitat.CAVE;
    }

    /**
     * 洞窟へのアーチを探す(無ければ null)。頭の前(dir と左右 35°、{@link #BREACH_LOOK_AHEAD} × 体格)の柱で飛び出す床(岩の上の空き)を
     * 頭の高さから上 → 下の順に探し、そこから同じ向きへ {@link #BREACH_SPANS} × 体格 先の床(高さ ± {@link #BREACH_LANDING_RISE})へ。
     * 高さは長さ × {@link #BREACH_HEIGHT_RATIO}(天井が低ければ下げる)。通れるかは {@link #checkArc}。
     *
     * @param allowSky 空の下でも跳ぶ(地上への遠出の地表の段階)。false なら洞窟の中だけ(頂点の上に岩の天井)
     */
    @Nullable
    BreachPlan planBreach(Vec3 axis, Vec3 dir, boolean allowSky) {
        Level level = this.level();
        double s = this.getScale();
        Vec3 flat = flatten(dir);
        for (double ahead : BREACH_LOOK_AHEAD) {
            for (float yaw : BREACH_LOOK_YAW) {
                Vec3 d = flat.yRot(yaw * Mth.DEG_TO_RAD);
                double ax = axis.x + d.x * ahead * s;
                double az = axis.z + d.z * ahead * s;
                double floorA = findFloor(level, ax, az, axis.y, BREACH_FLOOR_BELOW * s, BREACH_FLOOR_ABOVE * s);
                if (Double.isNaN(floorA)) {
                    continue;
                }
                Vec3 a = new Vec3(ax, floorA, az);
                for (double span : BREACH_SPANS) {
                    double bx = ax + d.x * span * s;
                    double bz = az + d.z * span * s;
                    double floorB = findFloor(level, bx, bz, floorA, BREACH_LANDING_RISE, BREACH_LANDING_RISE);
                    if (Double.isNaN(floorB)) {
                        continue;
                    }
                    Vec3 b = new Vec3(bx, floorB, bz);
                    double hPref = BREACH_HEIGHT_RATIO * span * s;
                    for (double k : new double[]{1.0D, 0.75D, 0.5D}) {
                        double h = hPref * k;
                        if (h < BREACH_HEIGHT_MIN * s) {
                            break;
                        }
                        BreachPlan plan = checkArc(level, a, b, h, BREACH_DEPTH * s, SEG_RADIUS * s, !allowSky);
                        if (plan != null) {
                            return plan;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** (x, z) の柱で、yStart のマスから上へ above、次に下へ below の範囲の「床」の高さ(床の岩の上面)。無ければ NaN。 */
    private static double findFloor(Level level, double x, double z, double yStart, double below, double above) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        int y0 = Mth.floor(yStart);
        int top = Mth.floor(yStart + above);
        for (int y = y0; y <= top; y++) {
            if (isFloorCell(level, bx, y, bz)) {
                return y;
            }
        }
        int bottom = Mth.floor(yStart - below);
        for (int y = y0 - 1; y >= bottom; y--) {
            if (isFloorCell(level, bx, y, bz)) {
                return y;
            }
        }
        return Double.NaN;
    }

    /** 床の上のマス: 岩でなく、液体も無く、壊せる(バリア・岩盤でない)空きで、その下が岩。 */
    private static boolean isFloorCell(Level level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState st = stateIfLoaded(level, pos);
        if (st == null || !st.getFluidState().isEmpty() || isEarthState(level, pos, st) || st.getDestroySpeed(level, pos) < 0.0F) {
            return false;
        }
        return isEarth(level, pos.below());
    }

    /**
     * アーチ(a → b、高さ h)が通れるか。通れれば両端を床の下 depth まで伸ばした計画、だめなら null。
     * 曲線の上を 0.5 ブロックおきに見る: 床の下(t &lt; 0・t &gt; 1)は全部岩、床の上の真ん中(t 0.12〜0.88)は空き(筒の太さぶん上も)、
     * どこにも液体・入れないマス(岩盤・バリア・読み込まれていない)が無いこと。needCeiling なら頂点が洞窟の中
     * (空が見えず、上 {@link #BREACH_CEILING_SCAN} マスまでに岩)。空の下で跳ぶのは、空の見える相手への跳びかかりと地上への遠出の地表の段階だけ。
     * 動くときは曲線の上を直接進む(steer の入れないマスの見張りを通らない)ので、ここで全部見ておく。
     */
    @Nullable
    static BreachPlan checkArc(Level level, Vec3 a, Vec3 b, double h, double depth, double radius, boolean needCeiling) {
        double dy = b.y - a.y;
        double q = dy + 4.0D * h;
        double t0 = (q - Math.sqrt(q * q + 16.0D * h * depth)) / (8.0D * h);
        double t1 = (q + Math.sqrt((dy - 4.0D * h) * (dy - 4.0D * h) + 16.0D * h * depth)) / (8.0D * h);
        double t = t0;
        while (true) {
            Vec3 p = arcPoint(a, b, h, t);
            BlockPos cell = BlockPos.containing(p);
            BlockState st = stateIfLoaded(level, cell);
            if (st == null || blocksWorm(level, cell) || !st.getFluidState().isEmpty()) {
                return null;
            }
            boolean earth = isEarthState(level, cell, st);
            if (t < 0.0D || t > 1.0D) {
                if (!earth) {
                    return null;
                }
            } else if (t > 0.12D && t < 0.88D) {
                BlockPos up = BlockPos.containing(p.x, p.y + radius + 0.2D, p.z);
                BlockState us = stateIfLoaded(level, up);
                if (earth || us == null || blocksWorm(level, up) || !us.getFluidState().isEmpty() || isEarthState(level, up, us)) {
                    return null;
                }
            }
            if (t >= t1) {
                break;
            }
            t = Math.min(t1, t + 0.5D / Math.max(0.5D, arcTangent(a, b, h, t).length()));
        }
        if (needCeiling) {
            BlockPos apex = BlockPos.containing(arcPoint(a, b, h, 0.5D));
            if (level.canSeeSky(apex) || !hasCeiling(level, apex)) {
                return null;
            }
        }
        return new BreachPlan(a, b, h, t0, t1);
    }

    /** from の上 {@link #BREACH_CEILING_SCAN} マスまでに岩があるか(洞窟の天井。空の下・梢の下には無い)。 */
    private static boolean hasCeiling(Level level, BlockPos from) {
        for (int k = 1; k <= BREACH_CEILING_SCAN; k++) {
            BlockPos p = from.above(k);
            if (p.getY() >= level.getMaxBuildHeight()) {
                return false;
            }
            if (isEarth(level, p)) {
                return true;
            }
        }
        return false;
    }

    /** アーチの点(軸の点)。 */
    static Vec3 arcPoint(Vec3 a, Vec3 b, double h, double t) {
        return new Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t + 4.0D * h * t * (1.0D - t), a.z + (b.z - a.z) * t);
    }

    /** アーチの接線(t で微分。正規化していない)。 */
    static Vec3 arcTangent(Vec3 a, Vec3 b, double h, double t) {
        return new Vec3(b.x - a.x, (b.y - a.y) + 4.0D * h * (1.0D - 2.0D * t), b.z - a.z);
    }

    /**
     * アーチへ。target があれば跳びかかり(寄せながら予兆を見せ、相手の上半身を通るアーチで噛む。予兆はここから始まる)、
     * 無ければうろつきの跳ね。
     */
    private void enterBreach(BreachPlan plan, @Nullable LivingEntity target) {
        setMode(Mode.BREACH);
        this.breach = plan;
        this.breachT = plan.t0();
        this.breachArc = false;
        this.breachInAir = false;
        this.strikeTarget = target;
        this.strikeAim = target != null ? target.position() : null;
        this.strikeTell = target != null ? 0 : -1;
        this.strikeArcTick = 0;
        this.strikeBitten = false;
        if (target != null) {
            setAction(ACT_BURST);
        }
    }

    private void clearBreach() {
        if (this.strikeTarget != null && this.strikeTell >= 0 && !this.breachArc) {
            // 溜めの途中でやめた: 開いた唇を戻す
            setAction(ACT_NONE);
        }
        this.breach = null;
        this.breachArc = false;
        this.breachInAir = false;
        this.strikeTarget = null;
        this.strikeAim = null;
        this.strikeTell = -1;
    }

    /**
     * BREACH: 床の下のアーチの始まり P(t0) へ地中を寄せ(頭は隠れているので向きが急に変わっても見えない)、着いたらアーチの上を進む
     * (向きは曲線の接線。体は頭の道をなぞるので、体全体がアーチを描いて床の穴から穴へ流れる)。床を破って飛び出す瞬間・潜る瞬間に破片と音。
     * うろつきの跳ねは水平に一定の速さ。跳びかかりは<b>止まらずに寄せながら</b>予兆を見せ(飛び出す床にひびとかちかち、唇を開く burst は床の下。
     * 予兆が {@link #STRIKE_TELL_TICKS} ほど続くよう寄せる速さを落とすが、狩りの速さの {@link #APPROACH_MIN_FACTOR} 倍より遅くしない。
     * 2026-10-09 ユーザー指定「攻撃の際にも止まらず」。前は始まりに着いてから 1.2 秒止まって溜めた)、着いたらそのまま snap と同時に飛び出して、
     * snap の当たりの tick にアーチの真ん中(相手の上半身)を通る速さで進み、噛む。
     * P(t1)(潜った先の地中)まで来たら、跳ねはうろつきへ(同じ向きで次を探す)、跳びかかりは HUNT へ(相手の下を回って次を待つ)。
     */
    private void tickBreach(@Nullable LivingEntity prey) {
        BreachPlan plan = this.breach;
        if (plan == null) {
            resumeRoam();
            return;
        }
        boolean strike = this.strikeTarget != null;
        if (!this.breachArc) {
            double speed = moveSpeed(HUNT_SPEED);
            Vec3 start = arcPoint(plan.a(), plan.b(), plan.h(), plan.t0());
            if (strike) {
                if (prey != this.strikeTarget) {
                    abortBreach(prey);
                    return;
                }
                if (this.strikeAim != null && this.modeTicks % STRIKE_PLAN_INTERVAL == 0
                        && Math.hypot(prey.getX() - this.strikeAim.x, prey.getZ() - this.strikeAim.z) > STRIKE_REPLAN_DIST) {
                    // 寄せている間に相手が動いた: 計画し直す
                    abortBreach(prey);
                    return;
                }
                strikeTellEffects(plan);
                double d0 = axisPoint().distanceTo(start);
                speed = Mth.clamp(d0 / Math.max(1, STRIKE_TELL_TICKS - this.strikeTell), speed * APPROACH_MIN_FACTOR, speed);
            } else if (prey != null || hasFreshNoise()) {
                clearBreach();
                enterHunt();
                return;
            }
            double d = steer(start, speed, TURN_HUNT, true);
            // 着いた(か、すぐ横・後ろを行き過ぎた: 回って戻らずにそのまま飛び出す。地中なので向きが急に変わっても見えない)
            boolean passed = d < 1.5D * this.getScale() && start.subtract(axisPoint()).dot(this.heading) < 0.0D;
            if (d < 0.5D * this.getScale() || passed) {
                if (strike) {
                    launchStrike(plan);
                } else {
                    this.breachArc = true;
                }
            } else if (this.modeTicks > BREACH_APPROACH_MAX_TICKS || !isHeadInEarth()) {
                // 寄せられない(岩が途切れている・遠回り)
                abortBreach(prey);
            }
            return;
        }
        double nt;
        if (strike) {
            nt = Math.min(this.breachT + this.strikeDt, plan.t1());
        } else {
            double span = Math.hypot(plan.b().x - plan.a().x, plan.b().z - plan.a().z);
            nt = Math.min(this.breachT + moveSpeed(BREACH_SPEED) / Math.max(0.5D, span), plan.t1());
        }
        Vec3 next = arcPoint(plan.a(), plan.b(), plan.h(), nt);
        this.moveVel = next.subtract(axisPoint());
        this.heading = arcTangent(plan.a(), plan.b(), plan.h(), nt).normalize();
        this.breachT = nt;
        boolean inEarth = isEarthAt(next);
        if (!inEarth && !this.breachInAir) {
            this.breachInAir = true;
            breachEffects(plan.a(), strike);
        } else if (inEarth && this.breachInAir && nt > 0.5D) {
            this.breachInAir = false;
            breachEffects(plan.b(), false);
        }
        if (strike) {
            this.strikeArcTick++;
            if (!this.strikeBitten && Math.abs(this.strikeArcTick - SNAP_HIT_TICK) <= 3) {
                trySwoopBite();
            }
        }
        if (nt >= plan.t1()) {
            finishBreach(plan, prey, strike);
        }
    }

    /**
     * 跳びかかりの予兆(寄せている間、毎 tick): 飛び出す床にひびの破片と叩く音、かちかち(坑道の burst と同じ時機)。唇を開く burst は床の下。
     * {@link #strikeTell} は予兆を見せた tick 数(寄せる速さの目安にも使う)。
     */
    private void strikeTellEffects(BreachPlan plan) {
        int t = this.strikeTell++;
        Level level = this.level();
        Vec3 a = plan.a();
        BlockState floor = stateIfLoaded(level, BlockPos.containing(a.x, a.y - 0.5D, a.z));
        if (floor != null && !floor.isAir()) {
            if (t % 3 == 0) {
                wallParticles(floor, a, 5, 0.05D);
            }
            if (t == 0) {
                level.playSound(null, a.x, a.y, a.z, floor.getSoundType().getHitSound(), SoundSource.HOSTILE, 1.2F, 0.5F);
            }
        }
        if (t == BURST_CLICK_1 || t == BURST_CLICK_2) {
            level.playSound(null, a.x, a.y, a.z, SoundEvents.EVOKER_FANGS_ATTACK, SoundSource.HOSTILE, 0.35F, 1.7F);
        }
    }

    /**
     * 跳びかかりのアーチに入る(床の下の始まりに着いた。止まらずにそのまま): snap を始め、snap の当たりの tick = アーチの真ん中で
     * 相手の上半身を通るように、1 tick に進む媒介変数を決める。
     */
    private void launchStrike(BreachPlan plan) {
        this.breachArc = true;
        this.strikeArcTick = 0;
        this.strikeDt = (0.5D - plan.t0()) / SNAP_HIT_TICK;
        this.lastStrikeTellTicks = this.strikeTell;
        setAction(ACT_SNAP);
    }

    /** 跳びかかりの噛みつき(snap の当たりの前後 3 tick): 届く所に相手が居れば攻撃力の 1 回分({@link #inBiteReach})。 */
    private void trySwoopBite() {
        LivingEntity t = this.strikeTarget;
        if (t != null && inBiteReach(t)) {
            bite(t);
        }
    }

    /**
     * アーチの終わり(潜った先の地中)。跳びかかりなら次の跳びかかりまで待って HUNT へ(相手の下を回る)。跳ねなら同じ向きでうろつきへ戻り、
     * 次の跳ねを探す(ときどき {@link #ROAM_EXCURSION_CHANCE} で、乱数の向きへ地中だけを進む寄り道)。
     */
    private void finishBreach(BreachPlan plan, @Nullable LivingEntity prey, boolean strike) {
        clearBreach();
        if (strike) {
            this.strikeCooldown = STRIKE_COOLDOWN_MIN + this.random.nextInt(STRIKE_COOLDOWN_MAX - STRIKE_COOLDOWN_MIN + 1);
        } else {
            this.breachCooldown = BREACH_COOLDOWN_MIN + this.random.nextInt(BREACH_COOLDOWN_MAX - BREACH_COOLDOWN_MIN + 1);
        }
        if (prey != null || hasFreshNoise()) {
            enterHunt();
            return;
        }
        enterRoam(plan.b().subtract(plan.a()));
        if (!strike && !this.noExcursionForTests && this.random.nextFloat() < ROAM_EXCURSION_CHANCE) {
            this.excursionTicks = ROAM_EXCURSION_MIN + this.random.nextInt(ROAM_EXCURSION_MAX - ROAM_EXCURSION_MIN + 1);
            this.roamDir = Vec3.directionFromRotation(0.0F, this.random.nextFloat() * 360.0F);
        }
    }

    /** アーチをやめる(始まりへ寄せられない・跳びかかりの相手が動いた・替わった)。跳ねは少し待ってから次を探す。 */
    private void abortBreach(@Nullable LivingEntity prey) {
        boolean strike = this.strikeTarget != null;
        clearBreach();
        if (!strike) {
            this.breachCooldown = BREACH_ABORT_COOLDOWN;
        }
        if (prey != null || hasFreshNoise()) {
            enterHunt();
        } else {
            resumeRoam();
        }
    }

    /**
     * 跳びかかりのアーチを探す(無ければ null = ここでは跳べない)。相手の足元の床の上が {@link #STRIKE_MIN_HEADROOM} マス以上空いていること
     * (高さ 2 の坑道では跳ばない = 壁から待ち伏せる)。向きはこちらから相手へ(だめなら斜め・横・反対)、長さ {@link #STRIKE_SPANS} × 体格 を
     * 相手の前後に半分ずつ。高さ = 相手の上半身(足元 + 高さの 3/4)− 両端の床の平均 → アーチの真ん中(t = 0.5)が相手の上半身を通る。
     * 通れるか(液体・岩の柱)は {@link #checkArc}。空の見える相手(地表。地上への遠出・地表の採掘の音・殴られた相手)には空の下でも跳ぶ
     * (10-02 の遠出の追加から。前は洞窟の中だけで、地表の相手には床の下から真上へ飛び出していた)。
     *
     * @param from 「こちら」の点(向きの基準。{@link #strikeLaunchGuess})
     */
    @Nullable
    BreachPlan planStrike(LivingEntity prey, Vec3 from) {
        Level level = this.level();
        double s = this.getScale();
        double floorT = findFloor(level, prey.getX(), prey.getZ(), prey.getY(), 2.0D, 0.0D);
        if (Double.isNaN(floorT) || !hasHeadroom(level, prey.getX(), floorT, prey.getZ(), STRIKE_MIN_HEADROOM)) {
            return null;
        }
        boolean needCeiling = !level.canSeeSky(BlockPos.containing(prey.getEyePosition()));
        double hitY = prey.getY() + prey.getBbHeight() * 0.75D;
        Vec3 toward = flatten(prey.position().subtract(from));
        for (float yaw : STRIKE_YAW) {
            Vec3 d = toward.yRot(yaw * Mth.DEG_TO_RAD);
            for (double span : STRIKE_SPANS) {
                double half = span * s * 0.5D;
                double ax = prey.getX() - d.x * half;
                double az = prey.getZ() - d.z * half;
                double bx = prey.getX() + d.x * half;
                double bz = prey.getZ() + d.z * half;
                double fa = findFloor(level, ax, az, floorT, 2.0D, 2.0D);
                double fb = findFloor(level, bx, bz, floorT, 2.0D, 2.0D);
                if (Double.isNaN(fa) || Double.isNaN(fb)) {
                    continue;
                }
                double h = hitY - (fa + fb) * 0.5D;
                if (h < STRIKE_HEIGHT_MIN * s) {
                    continue;
                }
                BreachPlan plan = checkArc(level, new Vec3(ax, fa, az), new Vec3(bx, fb, bz), h, BREACH_DEPTH * s, SEG_RADIUS * s,
                        needCeiling);
                if (plan != null) {
                    return plan;
                }
            }
        }
        return null;
    }

    /** (x, z) の柱で、床の上 floorY から cells マスが空き(岩でも液体でも入れないマスでもない)か。 */
    private static boolean hasHeadroom(Level level, double x, double floorY, double z, int cells) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        int y0 = Mth.floor(floorY);
        for (int k = 0; k < cells; k++) {
            BlockPos p = new BlockPos(bx, y0 + k, bz);
            BlockState st = stateIfLoaded(level, p);
            if (st == null || isEarthState(level, p, st) || !st.getFluidState().isEmpty() || st.getDestroySpeed(level, p) < 0.0F) {
                return false;
            }
        }
        return true;
    }

    /** 攻めの合間: 相手の床の下(床から {@link #BREACH_DEPTH} × 体格 下)を半径 {@link #STALK_RADIUS} × 体格 で回る(止まらない)。 */
    private void stalk(LivingEntity prey) {
        double floor = findFloor(this.level(), prey.getX(), prey.getZ(), prey.getY(), 2.0D, 0.0D);
        double y = (Double.isNaN(floor) ? prey.getY() : floor) - BREACH_DEPTH * this.getScale();
        circle(prey.getX(), prey.getZ(), y);
    }

    /** (cx, cz) のまわりを高さ y で半径 {@link #STALK_RADIUS} × 体格 で回る(相手の下・音のした所の下。止まらない)。 */
    private void circle(double cx, double cz, double y) {
        double r = STALK_RADIUS * this.getScale();
        this.stalkAngle += moveSpeed(HUNT_SPEED) / r;
        Vec3 point = new Vec3(cx + Math.cos(this.stalkAngle) * r, y, cz + Math.sin(this.stalkAngle) * r);
        steer(point, moveSpeed(HUNT_SPEED), TURN_HUNT, true);
    }

    /**
     * 跳びかかりを組む「こちら」の点(10-09): 相手の下を回っている(回る半径の 1.5 倍の内)なら、回り続けて {@link #STRIKE_TELL_TICKS} 後に着く
     * 円の上の点 —— アーチの始まりがおよそそこに来るので、寄せる間(約 1.2 秒)に予兆を見せてから止まらずに飛び出せる
     * (今の位置から組むと始まりがすぐ横に来て、予兆なしに飛び出すか、止まって溜めることになる)。遠ければ今の位置。
     */
    private Vec3 strikeLaunchGuess(LivingEntity prey) {
        Vec3 axis = axisPoint();
        double r = STALK_RADIUS * this.getScale();
        double dx = axis.x - prey.getX();
        double dz = axis.z - prey.getZ();
        if (dx * dx + dz * dz > 2.25D * r * r) {
            return axis;
        }
        double ahead = this.stalkAngle + moveSpeed(HUNT_SPEED) * STRIKE_TELL_TICKS / r;
        return new Vec3(prey.getX() + Math.cos(ahead) * r, axis.y, prey.getZ() + Math.sin(ahead) * r);
    }

    /** 床を破って飛び出す・潜る瞬間: 床のブロックの破片と割れる音(跳びかかりで飛び出すときは吠える)。ブロックは壊さない。 */
    private void breachEffects(Vec3 at, boolean roar) {
        Level level = this.level();
        BlockState floor = stateIfLoaded(level, BlockPos.containing(at.x, at.y - 0.5D, at.z));
        if (floor != null && !floor.isAir()) {
            wallParticles(floor, at, 30, 0.25D);
            level.playSound(null, at.x, at.y, at.z, floor.getSoundType().getBreakSound(), SoundSource.HOSTILE, 2.0F, 0.6F);
        }
        if (roar) {
            level.playSound(null, at.x, at.y, at.z, SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 0.8F, 0.6F);
        }
    }

    /** 今、洞窟へのアーチの上を進んでいるか(空中・地中を問わず。テスト・デバッグ用)。 */
    public boolean isBreachingArc() {
        return this.mode == Mode.BREACH && this.breachArc;
    }

    // ---------------------------------------------------------------- 計画

    /**
     * 獲物の真横の壁を探す(計画し直すときだけ。20 tick に 1 回まで)。胸の高さ → 足の高さ、4 方向(自分のいる側から)、
     * 獲物の列から 1〜3 マス先の最初の岩で、その奥にも岩があるもの。見つからなければ床(足の下 2 マスが岩)。
     */
    private void planLurk(LivingEntity prey) {
        this.plannedFor = prey.position();
        clearPlan();
        Level level = this.level();
        BlockPos feet = prey.blockPosition();
        Vec3 me = axisPoint();
        List<Direction> dirs = new ArrayList<>(Direction.Plane.HORIZONTAL.stream().toList());
        Vec3 toMe = me.subtract(prey.position());
        // 自分のいる側を向いた面から(内積の大きい順)
        dirs.sort((a, b) -> Double.compare(
                b.getStepX() * toMe.x + b.getStepZ() * toMe.z, a.getStepX() * toMe.x + a.getStepZ() * toMe.z));
        BlockPos best = null;
        Direction bestDir = null;
        double bestScore = Double.MAX_VALUE;
        for (int h = 1; h >= 0; h--) {
            BlockPos row = feet.above(h);
            for (int rank = 0; rank < dirs.size(); rank++) {
                Direction d = dirs.get(rank);
                for (int k = 1; k <= 3; k++) {
                    BlockPos c = row.relative(d, k);
                    BlockState st = stateIfLoaded(level, c);
                    if (st == null) {
                        break;
                    }
                    if (!isEarthState(level, c, st)) {
                        if (!st.getCollisionShape(level, c).isEmpty()) {
                            break;   // ガラス・柵など: 飛び出せない
                        }
                        continue;
                    }
                    if (!isEarth(level, c.relative(d))) {
                        break;       // 壁が薄い(頭と首が入らない)
                    }
                    double score = k * 2.0D + (1 - h) + rank * 0.6D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = c;
                        bestDir = d;
                    }
                    break;
                }
            }
        }
        Vec3 normal;
        Vec3 face;
        BlockPos cell;
        if (best != null) {
            cell = best;
            Vec3 into = new Vec3(bestDir.getStepX(), 0.0D, bestDir.getStepZ());
            normal = into.scale(-1.0D);
            Vec3 centre = Vec3.atCenterOf(cell);
            face = centre.subtract(into.scale(0.5D));
            // 口の胴の軸をマスの真ん中の高さに(頭の軸は口の軸より 0.08 低い)
            face = new Vec3(face.x, cell.getY() + 0.5D - (MOUTH_Y - AXIS_Y) * this.getScale(), face.z);
        } else {
            BlockPos below = feet.below();
            if (!isEarth(level, below) || !isEarth(level, below.below())) {
                return;
            }
            cell = below;
            normal = new Vec3(0.0D, 1.0D, 0.0D);
            face = new Vec3(below.getX() + 0.5D, below.getY() + 1.0D, below.getZ() + 0.5D);
        }
        double s = this.getScale();
        Vec3 axis = face.subtract(normal.scale(WALL_FACE_FWD * s));
        this.lurkCell = cell;
        this.lurkNormal = normal;
        this.lurkFace = face;
        this.lurkAxis = axis;
        // 回り込み: 頭が壁の奥側(面の後ろ 1 ブロック以上)にいて、横のずれが奥行きより小さければ直接。そうでなければ奥の点を経由
        if (needsStaging(me, face, normal, axis, s)) {
            Vec3 staging = axis.subtract(normal.scale(2.5D * s));
            if (!isEarthAt(staging)) {
                staging = axis.subtract(normal.scale(1.5D * s));
            }
            this.stagingAxis = isEarthAt(staging) ? staging : null;
        }
    }

    /**
     * 待ち伏せの点(lurkAxis)へ、壁の奥の点を回ってから入るか(静的。GameTest から直接呼ぶ)。
     * 頭が壁の面の後ろ 1 ブロック以上にいて、軸の線からの横のずれが奥行きより小さければ直接入る。
     * <b>もう壁の中の、待ち伏せの軸の線の上にいる(最後の寄せの途中)なら回らない</b>(点検 2026-10-01: 計画は 20 tick ごとに立て直すので、
     * 最後の寄せ = 面の後ろ 1 ブロックより手前で立て直しが来ると「奥行き不足」で回り込みが付き直し、2.5 奥の点まで引き返して
     * U ターン(1 tick 10°)を繰り返していた)。
     *
     * @param me     頭の軸の点
     * @param face   壁の面の中心
     * @param normal 面の外向き(獲物の方)
     * @param axis   待ち伏せの点(頭の軸の点)
     */
    public static boolean needsStaging(Vec3 me, Vec3 face, Vec3 normal, Vec3 axis, double scale) {
        double behind = face.subtract(me).dot(normal);
        Vec3 toAxis = axis.subtract(me);
        double lateral = toAxis.subtract(normal.scale(toAxis.dot(normal))).length();
        if (behind > 0.2D * scale && lateral < 0.35D * scale) {
            return false;
        }
        return behind < 1.0D || lateral > behind;
    }

    /** 噛んだ後の潜り直し先: 飛び出した向き(水平)の先の最初の岩の 1.5 奥、無ければ 2 ブロック先の床の 1.5 下。 */
    private Vec3 pickDiveTarget() {
        Vec3 axis = axisPoint();
        Vec3 h = new Vec3(this.heading.x, 0.0D, this.heading.z);
        if (h.lengthSqr() < 0.09D) {
            h = this.heading;
        }
        h = h.normalize();
        for (int k = 1; k <= 12; k++) {
            Vec3 p = axis.add(h.scale(k * 0.5D));
            if (isEarthAt(p)) {
                Vec3 deeper = p.add(h.scale(1.5D));
                return isEarthAt(deeper) ? deeper : p.add(h.scale(0.6D));
            }
        }
        Vec3 base = axis.add(h.scale(2.0D));
        for (int dy = 1; dy <= 8; dy++) {
            Vec3 p = base.add(0.0D, -dy, 0.0D);
            if (isEarthAt(p)) {
                return p.add(0.0D, -1.5D, 0.0D);
            }
        }
        return axis.add(h.scale(3.0D)).add(0.0D, -3.0D, 0.0D);
    }

    /** 空気の中に出たときの潜り先: 真下の地面の 1.5 下、無ければ横 4 方向の最初の岩の奥。 */
    private Vec3 pickGroundTarget() {
        Vec3 axis = axisPoint();
        for (int dy = 1; dy <= 12; dy++) {
            Vec3 p = axis.add(0.0D, -dy, 0.0D);
            if (isEarthAt(p)) {
                return p.add(0.0D, -1.5D, 0.0D);
            }
            if (blocksWorm(this.level(), BlockPos.containing(p))) {
                break;
            }
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            Vec3 step = new Vec3(d.getStepX(), 0.0D, d.getStepZ());
            for (int k = 1; k <= 6; k++) {
                Vec3 p = axis.add(step.scale(k));
                if (isEarthAt(p)) {
                    return p.add(step.scale(1.5D));
                }
            }
        }
        return axis.add(0.0D, -4.0D, 0.0D);
    }

    // ---------------------------------------------------------------- 舵取り

    /**
     * 頭を target(軸の点)へ向けて進める。向きは 1 tick に maxTurn 度まで、進むのは頭の向き(曲がりながら進む = 体が弧を描く)。
     *
     * @param underground 地中だけを進む(行く手が岩でなければ、ずらした向きで岩の続く方を選ぶ)
     * @return target までの距離
     */
    private double steer(Vec3 target, double speed, float maxTurn, boolean underground) {
        Vec3 axis = axisPoint();
        Vec3 to = target.subtract(axis);
        double dist = to.length();
        if (dist < 1.0E-4D) {
            this.moveVel = Vec3.ZERO;
            return dist;
        }
        Vec3 want = to.scale(1.0D / dist);
        if (underground && dist > 1.2D) {
            want = undergroundDir(axis, want);
        }
        turnHeading(want, maxTurn);
        double align = this.heading.dot(want);
        double v;
        if (dist > 1.0D) {
            v = speed * Math.max(0.35D, align);
        } else {
            // 近くでも速さの 0.35 倍より遅くしない(行き過ぎたら回って戻る。2026-10-09 ユーザー指定「常に動き続ける」。
            // 前は点の真上で 0 まで落とし、点が横・後ろだと向きを変え終わるまで止まっていた)
            v = Math.max(Math.min(speed, dist) * Math.max(0.0D, align), speed * 0.35D);
        }
        Vec3 vel = this.heading.scale(v);
        // 岩盤・バリア・読み込まれていない所へは入らない。動いた先のマスと少し先のマスの両方を見る(少し先だけだと、角をかすめて
        // 動いた先が入れないマスになり、抜けられなくなった。2026-10-02 の GameTest でテストの構造ブロックに頭が入った)。
        // 今居るマスは見ない(入ってしまっていても外へは出られる)
        BlockPos here = BlockPos.containing(axis);
        BlockPos nextCell = BlockPos.containing(axis.add(vel));
        BlockPos aheadCell = BlockPos.containing(axis.add(vel).add(this.heading.scale(0.35D)));
        if ((!nextCell.equals(here) && blocksWorm(this.level(), nextCell)) || (!aheadCell.equals(here) && blocksWorm(this.level(), aheadCell))) {
            vel = Vec3.ZERO;
        }
        this.moveVel = vel;
        return dist;
    }

    /** 地中の向き選び: 1 ブロック先が岩ならそのまま。違えば左右 30/60/90 度・上下 35/70 度にずらした向きを順に見る。 */
    private Vec3 undergroundDir(Vec3 axis, Vec3 want) {
        if (this.detourTicks > 0 && this.detourDir != null) {
            this.detourTicks--;
            if (isEarthAt(axis.add(this.detourDir))) {
                return this.detourDir;
            }
        }
        if (isEarthAt(axis.add(want))) {
            this.detourDir = null;
            return want;
        }
        float[] yaws = {30.0F, -30.0F, 60.0F, -60.0F, 90.0F, -90.0F};
        for (float y : yaws) {
            Vec3 c = want.yRot(y * Mth.DEG_TO_RAD);
            if (isEarthAt(axis.add(c))) {
                return detour(c);
            }
        }
        Vec3 side = new Vec3(-want.z, 0.0D, want.x);
        if (side.lengthSqr() < 1.0E-6D) {
            side = new Vec3(1.0D, 0.0D, 0.0D);
        }
        side = side.normalize();
        for (float p : new float[]{35.0F, -35.0F, 70.0F, -70.0F}) {
            Vec3 c = rotateAround(want, side, p * Mth.DEG_TO_RAD);
            if (isEarthAt(axis.add(c))) {
                return detour(c);
            }
        }
        Vec3 down = new Vec3(0.0D, -1.0D, 0.0D);
        if (isEarthAt(axis.add(down))) {
            return detour(down);
        }
        return want;
    }

    private Vec3 detour(Vec3 d) {
        this.detourDir = d;
        this.detourTicks = 6;
        return d;
    }

    /** 頭の向きを want へ、1 tick に maxDeg 度まで回す(ベクトルの球面の補間)。 */
    private void turnHeading(Vec3 want, float maxDeg) {
        if (this.heading == null) {
            this.heading = headLook();
        }
        Vec3 w = want.normalize();
        double dot = Mth.clamp(this.heading.dot(w), -1.0D, 1.0D);
        double angle = Math.acos(dot);
        double max = maxDeg * Mth.DEG_TO_RAD;
        if (angle <= max) {
            this.heading = w;
            return;
        }
        Vec3 axis = this.heading.cross(w);
        if (axis.lengthSqr() < 1.0E-8D) {
            // 真逆: 横(水平)の軸で回す
            axis = new Vec3(-this.heading.z, 0.0D, this.heading.x);
            if (axis.lengthSqr() < 1.0E-8D) {
                axis = new Vec3(1.0D, 0.0D, 0.0D);
            }
        }
        this.heading = rotateAround(this.heading, axis.normalize(), max).normalize();
    }

    /** v を単位ベクトル k のまわりに a ラジアン回す(Rodrigues)。 */
    static Vec3 rotateAround(Vec3 v, Vec3 k, double a) {
        double c = Math.cos(a);
        double s = Math.sin(a);
        return v.scale(c).add(k.cross(v).scale(s)).add(k.scale(k.dot(v) * (1.0D - c)));
    }

    /** 頭の向き → yRot・xRot(真上・真下を向いているときは yaw を変えない)。 */
    private void applyHeading() {
        if (this.heading == null) {
            return;
        }
        Vec3 h = this.heading;
        double horiz = Math.sqrt(h.x * h.x + h.z * h.z);
        float yaw = this.getYRot();
        if (horiz > 1.0E-3D) {
            yaw = (float) (Mth.atan2(-h.x, h.z) * Mth.RAD_TO_DEG);
        }
        float pitch = (float) (-Mth.atan2(h.y, horiz) * Mth.RAD_TO_DEG);
        this.setYRot(yaw);
        this.setXRot(pitch);
        this.yBodyRot = yaw;
        this.yHeadRot = yaw;
    }

    /** 向き f の「背」(f に垂直で、なるべく上)。 */
    static Vec3 uprightUp(Vec3 f) {
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = f.cross(up);
        if (right.lengthSqr() < 1.0E-6D) {
            return new Vec3(0.0D, 0.0D, 1.0D);
        }
        return right.normalize().cross(f).normalize();
    }

    private double moveSpeed(double factor) {
        return this.getAttributeValue(Attributes.MOVEMENT_SPEED) * factor * this.getScale();
    }

    private static Vec3 preyCenter(LivingEntity prey) {
        return prey.position().add(0.0D, prey.getBbHeight() * 0.55D, 0.0D);
    }

    private void wallParticles(BlockState state, Vec3 at, int count, double speed) {
        if (this.level() instanceof ServerLevel sl && !state.isAir()) {
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), at.x, at.y, at.z, count, 0.25D, 0.25D, 0.25D, speed);
        }
    }

    /**
     * 地中を進む間の掘る音(頭のいるブロックの叩く音を低く)。近づいてくるのが聞こえる = 対処の手がかり。
     * うろつき・跳ねの間は {@link #DIG_SOUND_ROAM_INTERVAL} おき(10-02 から常に動くので、狩りの間と同じ間隔だと叩く音が途切れない)。
     */
    private void tickDigSound() {
        if (this.moveVel.lengthSqr() < 0.05D * 0.05D) {
            return;
        }
        if (--this.digSoundTimer > 0) {
            return;
        }
        this.digSoundTimer = isHunting() ? DIG_SOUND_INTERVAL : DIG_SOUND_ROAM_INTERVAL;
        BlockPos cell = BlockPos.containing(axisPoint());
        BlockState st = stateIfLoaded(this.level(), cell);
        if (st != null && isEarthState(this.level(), cell, st)) {
            this.level().playSound(null, cell, st.getSoundType().getHitSound(), SoundSource.HOSTILE, 0.5F, 0.55F);
        }
    }

    // ================================================================== 地面の見方

    /** 読み込み済みのチャンクのブロック(読み込み・生成の途中なら null。{@code getBlockState} でメインスレッドを待たせない)。 */
    @Nullable
    public static BlockState stateIfLoaded(Level level, BlockPos pos) {
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
            return null;
        }
        ChunkAccess chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    /** 「岩」= 体が潜っていられるマス: 中身の詰まった不透明なブロック(液体・ブロックエンティティ・壊せないものは除く)。 */
    public static boolean isEarthState(BlockGetter level, BlockPos pos, BlockState st) {
        return !st.isAir() && st.getFluidState().isEmpty() && !st.hasBlockEntity()
                && st.getDestroySpeed(level, pos) >= 0.0F
                && st.isSolidRender(level, pos) && st.isCollisionShapeFullBlock(level, pos);
    }

    public static boolean isEarth(Level level, BlockPos pos) {
        BlockState st = stateIfLoaded(level, pos);
        return st != null && isEarthState(level, pos, st);
    }

    /** 入らないマス: 岩盤・バリアのような壊せないもの、世界の上下の外、読み込まれていない所。 */
    public static boolean blocksWorm(Level level, BlockPos pos) {
        if (pos.getY() <= level.getMinBuildHeight()) {
            return true;
        }
        BlockState st = stateIfLoaded(level, pos);
        return st == null || st.getDestroySpeed(level, pos) < 0.0F;
    }

    private boolean isEarthAt(Vec3 p) {
        return isEarth(this.level(), BlockPos.containing(p));
    }

    public boolean isHeadInEarth() {
        return isEarthAt(axisPoint());
    }

    // ================================================================== 湧き

    /**
     * 湧かせ役の最後の関門(SPEC「近くの固い岩の中へ潜ってから世界に足す(壁の中で眠って聞いている)」)。
     * 渡された足元(床の上に立つ位置)の 1〜2 マス下に地面があれば、その 1 マス下のマスに頭を入れ、体の向き(下・東西南北)は
     * 頭の後ろに岩が {@value #SPAWN_MIN_ROCK} マス以上続く向きのうちいちばん長いもの(下を優先)。頭は体と逆を向く。
     * 見つからなければ湧かない。SCALE はまだ当たり判定に掛かっていないので、マス単位で見る(頭の太さ 0.65 < 1 マス)。
     */
    @Override
    public boolean acceptOvergrownSpawn(ServerLevel level) {
        BlockPos feet = BlockPos.containing(this.getX(), this.getY(), this.getZ());
        BlockPos ground = null;
        for (int dy = 1; dy <= 2; dy++) {
            if (isEarth(level, feet.below(dy))) {
                ground = feet.below(dy);
                break;
            }
        }
        if (ground == null) {
            return false;
        }
        BlockPos headCell = ground.below();
        if (!isEarth(level, headCell)) {
            return false;
        }
        Direction bestDir = null;
        int bestRun = SPAWN_MIN_ROCK - 1;
        List<Direction> order = new ArrayList<>();
        order.add(Direction.DOWN);
        List<Direction> horiz = new ArrayList<>(Direction.Plane.HORIZONTAL.stream().toList());
        java.util.Collections.shuffle(horiz, new java.util.Random(this.random.nextLong()));
        order.addAll(horiz);
        for (Direction d : order) {
            int run = 0;
            for (int k = 1; k <= SPAWN_SCAN_ROCK; k++) {
                if (!isEarth(level, headCell.relative(d, k))) {
                    break;
                }
                run++;
            }
            if (run > bestRun) {
                bestRun = run;
                bestDir = d;
            }
        }
        if (bestDir == null) {
            return false;
        }
        // 頭は体と逆を向く(体が下なら頭は真上)
        Direction face = bestDir.getOpposite();
        float yaw;
        float pitch;
        if (face == Direction.UP) {
            yaw = this.random.nextFloat() * 360.0F - 180.0F;
            pitch = -90.0F;
        } else {
            yaw = face.toYRot();
            pitch = 0.0F;
        }
        double s = this.getScale();
        this.moveTo(headCell.getX() + 0.5D, headCell.getY() + 0.5D - AXIS_Y * s, headCell.getZ() + 0.5D, yaw, pitch);
        this.yBodyRot = yaw;
        this.yHeadRot = yaw;
        this.heading = null;
        this.body.clear();
        this.solvedX = Double.NaN;
        return true;
    }

    // ================================================================== 被弾

    /**
     * 多重ヒットの間引き(爆発のように 1 回の攻撃が頭と複数の部位に当たる): 同じ tick の同じ DamageSource は、
     * 大きい方の 1 回分だけ効かせる(バニラの無敵時間と同じ考え。{@code BYPASSES_COOLDOWN} の攻撃も同じように間引く)。
     * 窒息(IN_WALL)は受けない(地形をすり抜ける)。殴られたら殴った相手を「聞いた」とみなす。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypes.IN_WALL)) {
            return false;
        }
        if (source == this.lastHurtSource && this.tickCount == this.lastHurtSourceTick) {
            if (amount <= this.lastHurtSourceAmount) {
                return false;
            }
            float before = this.lastHurtSourceAmount;
            this.lastHurtSourceAmount = amount;
            if (source.is(DamageTypeTags.BYPASSES_COOLDOWN)) {
                return super.hurt(source, amount - before);
            }
            if (this.invulnerableTime > 10) {
                // バニラは無敵時間の中の大きい一撃を「lastHurt を超えた分だけ」通すつもりだが、NeoForge 21.1.72 の actuallyHurt は
                // 渡された差分を使わず DamageContainer の全額を引く(差分が効かず 3 → 5 で 8 減った。2026-10-01 の GameTest)。
                // 差分を自分で渡す: 比べる lastHurt を 0 にして無敵時間の枝を通し(当たりの演出・無敵時間は付け直さない)、後で大きい方へ戻す。
                float last = this.lastHurt;
                if (amount <= last) {
                    return false;
                }
                this.lastHurt = 0.0F;
                boolean hurt = super.hurt(source, amount - last);
                this.lastHurt = hurt ? amount : last;
                return hurt;
            }
            return super.hurt(source, amount);
        }
        this.lastHurtSource = source;
        this.lastHurtSourceTick = this.tickCount;
        this.lastHurtSourceAmount = amount;
        boolean hurt = super.hurt(source, amount);
        if (hurt && !this.level().isClientSide() && source.getEntity() instanceof LivingEntity attacker && attacker != this
                && !(attacker instanceof Player p && (p.isCreative() || p.isSpectator()))) {
            // 殴った相手に気付く(採掘の音ではなく、居ることに気付いた扱い。距離は見ない)
            notice(attacker);
        }
        return hurt;
    }

    /** 部位(節・尾)への攻撃。体力は共通(ユーザー指定)。 */
    boolean hurtFromPart(FodimnesPart part, DamageSource source, float amount) {
        return this.hurt(source, amount);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void pushEntities() {
    }

    @Override
    public void knockback(double strength, double x, double z) {
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

    /**
     * 地中を進む途中で帯水層・水の溜まった洞窟を抜けても溺れない(仮決め。地形をすり抜ける種なので息の仕組みは持たせない)。
     * {@code canBreatheUnderwater} は NeoForge で final なので、こちらを上書きする。
     */
    @Override
    public boolean canDrownInFluidType(net.neoforged.neoforge.fluids.FluidType type) {
        return false;
    }

    // ================================================================== 音(仮: バニラの流用)

    @Nullable
    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SILVERFISH_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SILVERFISH_DEATH;
    }

    @Override
    public float getVoicePitch() {
        return 0.45F + (this.random.nextFloat() - 0.5F) * 0.1F;
    }

    @Override
    protected float getSoundVolume() {
        return 1.4F;
    }

    // ================================================================== 保存・同期

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        Vec3 a = axisPoint();
        this.body.setUnit(this.getScale());
        this.body.save(tag, "FodimnesBody", a.x, a.y, a.z);
        tag.putFloat("FodimnesRoll", this.bankRoll);
        tag.putInt("FodimnesNoise", this.noiseTimer);
        tag.putInt("FodimnesCooldown", this.attackCooldown);
        // 地上への遠出: 次までの待ちと、遠出の途中なら戻る先の高さ(読み込んだら戻りから続ける。地表に置き去りにしない)
        tag.putInt("FodimnesOutingCooldown", this.outingCooldown);
        if (this.outing != Outing.NONE && !Double.isNaN(this.homeY)) {
            tag.putDouble("FodimnesHomeY", this.homeY);
        }
        // 棲み場所(10-09)。決まる前(湧いた直後)は書かない = 読み込み後、最初にうろつくときに決める
        if (this.habitat != Habitat.UNKNOWN) {
            tag.putString("FodimnesHabitat", this.habitat.name());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.setNoGravity(true);
        this.noPhysics = true;
        this.bankRoll = tag.getFloat("FodimnesRoll");
        Vec3 a = axisPoint();
        this.body.load(tag, "FodimnesBody", a.x, a.y, a.z);
        this.solvedX = Double.NaN;
        // 攻撃の途中の保存は、読み込み後は眠りから始める(獲物の参照は保存されない)。音の残り時間は持ち越す
        this.noiseTimer = tag.getInt("FodimnesNoise");
        this.attackCooldown = tag.getInt("FodimnesCooldown");
        if (tag.contains("FodimnesOutingCooldown")) {
            this.outingCooldown = tag.getInt("FodimnesOutingCooldown");
        }
        this.outingQuarry = null;
        this.ascendGoal = null;
        this.outingTicks = 0;
        this.outingAge = 0;
        this.outingIdle = 0;
        if (tag.contains("FodimnesHomeY")) {
            this.homeY = tag.getDouble("FodimnesHomeY");
            this.outing = Outing.DESCEND;
        } else {
            this.homeY = Double.NaN;
            this.outing = Outing.NONE;
        }
        this.habitat = Habitat.UNKNOWN;
        if (tag.contains("FodimnesHabitat")) {
            try {
                this.habitat = Habitat.valueOf(tag.getString("FodimnesHabitat"));
            } catch (IllegalArgumentException ignored) {
                // 知らない値: 最初にうろつくときに決め直す
            }
        }
        this.mode = Mode.REST;
        this.modeTicks = 0;
        this.restTimer = 0;
        this.roamDir = null;
        clearBreach();
        this.heading = null;
        clearPlan();
        if (!this.level().isClientSide()) {
            this.entityData.set(DATA_ACTION, ACT_NONE);
        }
        // 読み込んだ道から部位をすぐ置く。チャンクから読み込まれた個体は onAddedToLevel を通らず、
        // 端のチャンク(tick しない)に居る間は部位の当たり判定が世界の原点に残り続けていた(点検 2026-10-01)
        updateBody(true);
    }

    /** 出現のパケットに道を載せる(クライアントがサーバーと同じ体の形から始める)。 */
    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buf) {
        Vec3 a = axisPoint();
        buf.writeFloat(this.bankRoll);
        this.body.setUnit(this.getScale());
        this.body.write(buf, a.x, a.y, a.z);
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buf) {
        this.bankRoll = buf.readFloat();
        Vec3 a = axisPoint();
        this.body.read(buf, a.x, a.y, a.z);
        this.solvedX = Double.NaN;
        this.visualsReady = false;
        // 受け取った道ですぐ解き直す(最初の tick より先に描かれるフレームも、サーバーと同じ体の形で描く)
        updateBody(true);
    }

    // ================================================================== アニメ

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // crawl・idle は絶対の時刻に位相を合わせる(頭は節 1 と同じ時刻)。lurk・burst・snap・retract は 0 秒目から
        controllers.add(new FodimnesPhaseController<>(this, "main", TRANSITION_TICKS, this::headState, 0.0D, Set.of("crawl", "idle")));
    }

    private PlayState headState(AnimationState<FodimnesEntity> state) {
        AnimationController<FodimnesEntity> c = state.getController();
        int act = getAction();
        // snap は burst の終わりの姿勢から始まる(NOTES)ので補間 0
        c.transitionLength(act == ACT_SNAP ? 0 : TRANSITION_TICKS);
        int seq = this.entityData.get(DATA_SEQ);
        if (seq != this.clientSeq) {
            this.clientSeq = seq;
            if (act != ACT_NONE) {
                c.forceAnimationReset();
            }
        }
        if (this.isDeadOrDying()) {
            return state.setAndContinue(IDLE);
        }
        return switch (act) {
            case ACT_LURK -> state.setAndContinue(LURK);
            case ACT_BURST -> state.setAndContinue(BURST);
            case ACT_SNAP -> state.setAndContinue(SNAP);
            case ACT_RETRACT -> state.setAndContinue(RETRACT);
            default -> state.setAndContinue(isCrawlingClient() ? CRAWL : IDLE);
        };
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    // ================================================================== テスト用

    /** テスト用: 音を聞かせる(本番は {@link Events#onBlockBreak})。 */
    public void hearForTests(Vec3 where, @Nullable LivingEntity source) {
        hearNoise(where, source);
    }

    /** テスト用: 一息ついたまま動かさない(うろつき・体を引き込む SETTLE を ticks のあいだ始めない。体がテスト場の外へはみ出すテスト用)。 */
    public void holdStillForTests(int ticks) {
        this.settleCooldown = ticks;
        this.restTimer = Math.max(this.restTimer, ticks);
    }

    /** テスト用: 棲み場所を決める(テスト場は天井がバリアで岩が無く、放っておくと地上に棲む個体と決まる)。 */
    public void setHabitatForTests(Habitat h) {
        this.habitat = h;
    }

    public Habitat getHabitat() {
        return this.habitat;
    }

    /** テスト用: 棲み場所の決め方({@link #decideHabitat})を今の位置でやり直して返す。 */
    public Habitat decideHabitatForTests() {
        decideHabitat();
        return this.habitat;
    }

    /** テスト用: 最後の跳びかかりで、飛び出す前に予兆を見せた tick 数(まだ跳んでいなければ −1)。 */
    public int lastStrikeTellTicksForTests() {
        return this.lastStrikeTellTicks;
    }

    /** テスト用: 今すぐ dir の向きへうろつき始める(次の tick に洞窟へ飛び出せるかを見る)。寄り道には入らない(跳ねる回数を数えるため)。 */
    public void startRoamForTests(Vec3 dir) {
        this.breachCooldown = 0;
        this.noExcursionForTests = true;
        enterRoam(dir);
    }

    /** テスト用: who に気付いたことにする(洞窟に来た相手と同じ扱い。採掘の音ではない)。 */
    public void noticeForTests(LivingEntity who) {
        notice(who);
    }

    /** テスト用: Mob に自分で気付くか・地上への遠出を自分で始めるか(テストの足場は既定で false。隣のテスト場の Mob を拾わないように)。 */
    public void setNoticeMobsForTests(boolean notice) {
        this.noticeMobsForTests = notice;
    }

    /** テスト用: 今の位置から who へ跳びかかるアーチを組めるか。 */
    public boolean canStrikeForTests(LivingEntity who) {
        return planStrike(who, axisPoint()) != null;
    }

    /** テスト用: 今の位置から dir の向きに洞窟へのアーチを組めるか(うろつきの跳ね。空の下では跳ばない)。 */
    public boolean canBreachForTests(Vec3 dir) {
        return planBreach(axisPoint(), dir, false) != null;
    }

    /** テスト用: 地上への遠出の地表の段階の跳ね(空の下でも跳ぶ)を、今の位置から dir の向きに組めるか。 */
    public boolean canSurfaceBreachForTests(Vec3 dir) {
        return planBreach(axisPoint(), dir, true) != null;
    }

    /** テスト用: 今すぐ quarry へ向けて地上への遠出を始める(待ちと獲物探しを飛ばす)。 */
    public void startOutingForTests(LivingEntity quarry) {
        enterRoam(null);
        startOuting(quarry);
    }

    /** 地上への遠出の段階(テスト・デバッグ用)。 */
    public Outing getOuting() {
        return this.outing;
    }

    /** テスト用: 地表で獲物が居ないまま戻るまでの tick を短くする(7 マスのテスト場で 10 秒うろつくと、壁の下をくぐって外へ出ることがある)。 */
    public void setSurfaceIdleTicksForTests(int ticks) {
        this.surfaceIdleLimit = ticks;
    }

    /** テスト用: うろつきの跳ね(地表の段階の跳ねも)を組まない。 */
    public void setNoRoamBreachForTests(boolean off) {
        this.noRoamBreachForTests = off;
    }

    /** テスト用: e に気付けるか(Mob の決まり。洞窟の中、遠出の間は地表も)。 */
    public boolean canNoticeMobForTests(LivingEntity e) {
        return canNoticeMob(e);
    }

    /** テスト用: 遠出の数え(段階・その段階の tick・始めてからの tick・地表で狩っていない tick・音の残り・標的)。失敗の説明に添える。 */
    public String outingDebugForTests() {
        LivingEntity t = this.getTarget();
        return this.outing + " 段階 " + this.outingTicks + "・始めて " + this.outingAge + "・狩らず " + this.outingIdle
                + "・音 " + this.noiseTimer + "・標的 " + (t == null ? "なし" : t.getType().toShortString() + (t.isAlive() ? "" : "(居ない)"));
    }

    /** テスト用: 今の頭の向きのまま、道を捨てて後ろへまっすぐ置き直す。 */
    public void resetBodyStraight() {
        this.body.clear();
        this.solvedX = Double.NaN;
        this.heading = null;
        updateBody(true);
    }

    // ================================================================== 採掘の音

    /** ブロックを壊す音を近くのフォディムネスに聞かせる(サーバー)。クリエイティブ・スペクテイターは音を立てない扱い。 */
    public static void onBlockBroken(ServerLevel level, BlockPos pos, Player player) {
        if (player.isCreative() || player.isSpectator()) {
            return;
        }
        Vec3 where = Vec3.atCenterOf(pos);
        // 聞こえる距離は個体の属性(既定 24)。探す箱は 32 まで(属性をそれ以上にしても 32 で頭打ち)
        AABB box = new AABB(pos).inflate(32.0D);
        for (FodimnesEntity worm : level.getEntitiesOfClass(FodimnesEntity.class, box)) {
            worm.hearNoise(where, player);
        }
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
    public static final class Events {
        @SubscribeEvent
        public static void onBlockBreak(BlockEvent.BreakEvent event) {
            if (event.getLevel() instanceof ServerLevel level && event.getPlayer() != null) {
                onBlockBroken(level, event.getPos(), event.getPlayer());
            }
        }

        private Events() {
        }
    }

    /** 部位の当たり判定の箱の一覧(テスト・デバッグ用)。 */
    public List<AABB> partBoxes() {
        List<AABB> out = new ArrayList<>();
        for (FodimnesPart p : this.parts) {
            out.add(p.getBoundingBox());
        }
        return out;
    }

    /** 最後に解いた部位の中心(テスト用、3 × 17)。 */
    public double[] solvedPartPositions() {
        return this.partPos.clone();
    }
}
