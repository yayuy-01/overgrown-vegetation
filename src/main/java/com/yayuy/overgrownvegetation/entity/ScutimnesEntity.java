package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.adaptation.Adaptation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import com.yayuy.overgrownvegetation.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.DragonFireball;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.entity.projectile.windcharge.AbstractWindCharge;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.Tags;
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
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * 「スクティムネス」(Scutimnes)— <b>グループ5(適応種)</b>の陸の盾役(2026-09-23)。
 * 甲殻の盾を 2 枚、体の前に構えた 8 本脚の生き物。見た目は蟹・蜘蛛に近いが、種族は大型の爬虫類・竜に近い(ユーザー指定)。
 * モデルはユーザー製(体格 2.88 倍 = 初版の 1.6 をさらに 1.8 倍、全長約 13 ブロック。どちらもユーザー指定)。
 *
 * <p>適応種なので、倒された武器種への耐性が種ごとに付いていく({@link Adaptation}、{@code group_5.json} に所属)。
 * 盾の軽減は {@link #hurt} の入口で掛け、適応はその後の {@code LivingDamageEvent.Pre} で別に掛かる(重なってよい)。
 *
 * <h2>行動(太字はユーザー指定)</h2>
 * <ul>
 *     <li><b>盾</b>: ふだんは盾を<b>上げた</b>まま(ユーザー製の仮アニメの姿勢)。標的を見つけたら近づき、<b>距離 10 ほどで盾を下ろして迫る</b>。
 *     <b>盾を上げるのは距離が 15 を超えたとき</b>。<b>標的が遠距離攻撃をしてくるなら距離に関係なく下ろす</b>。
 *     下ろしている間は<b>移動速度 −35%</b>、盾に当たった<b>近接は 50% カット・飛び道具は弾く</b>
 *     (<b>盾に効くのは剣による攻撃・状態異常・炎だけ</b>)。</li>
 *     <li><b>バッシュ</b>(通常攻撃): <b>左右どちらかの盾を前へ勢いよく突き出して殴る。ダメージ 8 と少ないがノックバックが強く、
 *     装備の耐久を他の mob より多く削る</b>。</li>
 *     <li><b>尾の薙ぎ払い</b>(通常攻撃): <b>背後に回られたら尾の先のふくらみを大きく横に振って薙ぎ払う。クールタイム 8 秒</b>。</li>
 *     <li><b>ガード → 叩きつけ</b>(特殊攻撃): <b>最大 5 秒、相手の攻撃を 100% カットして溜め、終わると同時に両盾を体ごと振り上げて
 *     地面に叩きつけ、叩いた点から前方へ扇状の衝撃波</b>({@link ScutimnesShockwave})。<b>波にはカットした分のダメージが乗る。
 *     クールタイム 45 秒。ガード中も動けて、常に標的へ正面を向けようとする</b>。</li>
 *     <li><b>最初から特殊攻撃を使わず、通常攻撃を使いながら時々織り交ぜる</b>({@link SpecialPacing})。</li>
 * </ul>
 *
 * <h2>その場で速く回れない(戦車)</h2>
 * <p>ユーザー指定: 「様々な mob と違い、その場で高速回転できず、機関砲台のようにゆっくりと回転することしかできない。
 * プレイヤーは動きの遅いスクティムネスの背後をとり、ダメージを与えなければいけない」。
 * 向き({@code yRot} = 体 = 頭)は<b>この個体が持つ {@link #heading} だけが正</b>で、毎 tick の終わり({@link #tickTurn})に
 * 「行きたい向き」へ最大 {@link #MAX_TURN_PER_TICK} 度だけ回してから書き戻す。移動・視線・行動の各所は向きを直接書かず
 * {@link #wantFacing} で希望を出すだけ。バニラの {@code MoveControl}(1 tick 90°)・{@code Mob#lookAt}・
 * {@code BodyRotationControl}(移動の向きへ体を回す)・よその {@code setYRot} の直書きは、どれも上書きされる
 * (tick の頭で {@link #heading} へ戻し、tick の終わりに回した結果を書く)。置き直し({@code moveTo}: 湧き・テレポート)だけは向きごと受け入れる。
 * 進むのは前だけ: 移動の速さ × max(0, cos(向きのずれ))、ずれが {@link #TANK_MAX_OFF} 度を超えたらその場で回るだけ。
 *
 * <h2>時刻はアニメーションと対</h2>
 * <p>アニメーションは {@code tools/scutimnes-anim/}(生成器)の生成物。tick は {@link #TRANSITION_TICKS}(動作の切り替えの補間)
 * + アニメの秒 × 20。片方だけ変えると盾が前に出ていないのに当たる(ファルキムネスと同じ決まり。DEV_NOTES
 * 「GeckoLib のアニメとサーバーの tick を合わせる」)。<b>形に関わる定数(バッシュの届く点・叩きつけの点・尾の届く距離・
 * 歩き/旋回のアニメの地上速度)は「形(points.json と合わせる)」の節にまとめてある</b>。
 *
 * <h2>座標の決まり</h2>
 * <p>エンティティの原点はモデルのファイル座標 (0, 0, {@link #ORIGIN_Z_PX}) —— 描画でモデルを +Z へ 14px × 体格ずらす
 * ({@code client/ScutimnesRenderer})。旋回の中心もここ。<b>ファイル +X = この個体の左</b>、ファイル −X = 右
 * (GeckoLib はファイルの x を反転して焼き、yaw 0 で Y 180° 回して描く)。{@code shield}(x&lt;0)= 右の盾、{@code shield2}(x&gt;0)= 左の盾。
 * アニメ名の {@code _l} / {@code _r} もこの左右。
 */
public class ScutimnesEntity extends Monster implements GeoEntity, ParasitizedMob,
        com.yayuy.overgrownvegetation.swarm.goal.SwarmAttacker {

    // ------------------------------------------------------------------ 動作

    public static final int ACT_NONE = 0;
    /** 盾を下ろす(アニメ shield_down 0.6 秒。{@link #SHIELD_SETTLE_TICK} から盾が効く)。 */
    public static final int ACT_SHIELD_DOWN = 1;
    /** 盾を上げる(アニメ shield_up 0.6 秒。始めた瞬間から盾は効かない)。 */
    public static final int ACT_SHIELD_UP = 2;
    /** 左の盾(shield2)で突く。 */
    public static final int ACT_BASH_L = 3;
    /** 右の盾(shield)で突く。 */
    public static final int ACT_BASH_R = 4;
    /** 尾先のふくらみで左(ファイル +X)側を払う(右へ振りかぶって左へ振り抜く)。 */
    public static final int ACT_TAIL_L = 5;
    public static final int ACT_TAIL_R = 6;
    /** ガード姿勢へ移る(アニメ guard_start 0.4 秒)。この間からもう 100% カット。 */
    public static final int ACT_GUARD_START = 7;
    /** ガード姿勢の保持(アニメ guard / guard_walk のループ)。 */
    public static final int ACT_GUARD = 8;
    /** 叩きつけ(アニメ slam 2.0 秒)。盾は頭の上 —— この間は盾の効果なし。 */
    public static final int ACT_SLAM = 9;

    /**
     * 動作を切り替えるときの補間(tick)。GeckoLib はこの間に今の姿勢から新しいアニメの 0 秒目の姿勢へ寄せ、
     * <b>それが終わってからアニメを 0 秒目から流す</b>(4.7.6 の {@code AnimationController#process})。
     * アニメの時刻に合わせる tick(当たる瞬間・鳴らす瞬間・動作の長さ)は、すべて「これ + アニメの秒 × 20」で数える。
     */
    static final int TRANSITION_TICKS = 4;

    // --- 盾の上げ下げ(アニメ shield_down / shield_up は 0.6 秒。shield_down は 0.45 秒で盾が所定の位置に収まる) ---
    static final int SHIELD_DOWN_TICKS = TRANSITION_TICKS + 12;
    /** これ以降は盾が効く(shield_down の 0.45 秒)。 */
    static final int SHIELD_SETTLE_TICK = TRANSITION_TICKS + 9;
    static final int SHIELD_UP_TICKS = TRANSITION_TICKS + 12;
    /**
     * 盾の音({@code SCUTIMNES_SHIELD})の「噛み合う」音が、鳴らしてから来るまで(tick)。音の担当の計測で 0.33 秒。
     * 下ろすときは盾が収まる {@link #SHIELD_SETTLE_TICK}、上げるときは上げきる {@link #SHIELD_UP_TICKS} に噛み合うように、この分だけ早く鳴らす。
     */
    static final int SHIELD_SOUND_LEAD = 7;

    // --- バッシュ(アニメ bash_l / bash_r は 1.1 秒、0.60 秒で突き出しの頂点 = 当たる瞬間) ---
    static final int BASH_TICKS = TRANSITION_TICKS + 22;
    static final int BASH_HIT_TICK = TRANSITION_TICKS + 12;
    /** バッシュの後、次のバッシュまで(tick)。仮。 */
    static final int BASH_COOLDOWN = 20;
    /**
     * 標的がこの角度(正面から、度)の内に居るときだけ振り始める。仮。振っている間は向きを変えない(下の {@link #BASH_START_RATIO})ので、
     * 当たる左右の幅(盾の板の外接箱 + 相手の幅の半分 + {@link #BASH_MARGIN})がこの角度を覆っていること(プレイヤー相手、原点から 2 ブロックで
     * 突く盾の側へ 66°・反対側へ 33° まで当たる)。
     */
    static final float BASH_TRIGGER_ANGLE = 30.0F;
    /**
     * 振り始める間合い(届く距離 {@link #bashReach} に対する割合)。ファルキムネスの空振り対策と同じ考え方
     * (ユーザー報告「標的より手前で振って外れる」)—— 届くぎりぎりでは振り始めない。追うのは {@link #BASH_IDEAL_RATIO} まで。
     *
     * <p>届く距離には判定のゆとり {@link #BASH_MARGIN} まで入れる(2026-09-24、統合で実測の盾に合わせたときに変えた)。
     * 実測の盾の突き出しは原点の 1.82 ブロック前(体格 1.6)で、当たり判定の半幅 1.44 のすぐ外までしか出ない。
     * ゆとり抜きの 85%(1.80)だと、当たり判定に触れている相手(原点から 1.74、斜めを向いていれば 2.46)にも
     * ほとんど振り始めなくなるため。追跡の止まる 0.7 は、盾の前面が相手の手前の面へ 0.2 ほど食い込む所。
     *
     * <p><b>溜めの間は踏み込まず、向きも変えない</b>(2026-09-24、レビューの指摘で変えた)。初版はファルキムネスと同じく溜めの間に
     * 0.7 まで踏み込み(1 tick 0.16 ブロック、下がる相手なら 12 tick で最大 1.9 ブロック)、当たる 4 tick 前まで標的へ向きを変えていたが、
     * アニメ bash_l / bash_r は 0〜0.4 秒に 8 本の足を踏ん張ったまま体を<b>後ろへ</b>ためる作りなので、本体を前へ動かしたり回したりすると
     * 接地した足がそのぶん地面を滑っていた。振り始めの 85% の間合いから、止まっている相手には判定のゆとりの内で必ず当たる
     * (下がる・横へ逃げる相手には外れる。遅い盾役の隙として受け入れる)。
     */
    static final double BASH_START_RATIO = 0.85D;
    static final double BASH_IDEAL_RATIO = 0.7D;
    /** 判定だけに足すゆとり(ブロック)。盾が相手の体に届いて見えるのに外れる、を無くす(ファルキムネスと同じ 0.6)。 */
    static final double BASH_MARGIN = 0.6D;
    /** バッシュが当たる高さの下限(相手の足元 − この個体の足元、ブロック)。上限は突き出した盾の上端 + 1({@link #bashReachesHeight})。 */
    static final double BASH_MIN_DY = -1.5D;
    /**
     * 防具の耐久の追加の削り: 防具 4 部位それぞれに<b>通常ぶん(max(1, ダメージ ÷ 4))のこの倍</b>を足す(合計 3 倍)。仮。
     * 通常ぶんは、バニラと同じく<b>難易度を掛けた後</b>のダメージから数える(プレイヤー。イージーで 5、ハードで 12 → 通常ぶん 1 / 3)。
     * 1.21.1 のバニラでは防具が削れるのはプレイヤーだけ(Mob の防具は通常ぶんが 0)なので、Mob にはこの追加分だけが入る。
     */
    static final int BASH_ARMOR_WEAR_MULTIPLIER = 2;
    /** 盾で防がれたとき、その盾の耐久を追加で削る量。仮。 */
    static final int BASH_SHIELD_WEAR = 15;
    /** 盾で防がれたときの押し戻し(バニラの knockback の強さ)。防いでも重い一撃で押される。仮。 */
    static final double BASH_BLOCKED_KNOCKBACK = 0.6D;

    // --- 尾の薙ぎ払い(アニメ tail_l / tail_r は 1.3 秒、0.70 秒で尾が真後ろを通過 = 当たる瞬間) ---
    static final int TAIL_TICKS = TRANSITION_TICKS + 26;
    static final int TAIL_HIT_TICK = TRANSITION_TICKS + 14;
    /** 風切りの音は鳴らしてから約 0.13 秒で山(音の担当の計測)。振りの中央の 3 tick 前に鳴らす。 */
    static final int TAIL_SOUND_TICK = TAIL_HIT_TICK - 3;
    /**
     * 標的の方向が正面からこの角度(度)以上なら「背後」。仮。ただし振るのは、標的が尾の当たる扇({@link #inTailArc})の中に
     * 居るときだけ(扇の外の標的に振ると、見た目どおり届かずに外れるので)。
     */
    static final float TAIL_TRIGGER_ANGLE = 100.0F;
    /**
     * 尾のダメージ。当てる扇は正面から 90° 以上(= 前後の成分が 0 以下)で、かつアニメの尾先が実際に通る弧
     * ({@link #TAIL_ARC_SWEEP_DEG} / {@link #TAIL_ARC_WINDUP_DEG})の内({@link #inTailArc})。仮。
     */
    static final float TAIL_DAMAGE = 10.0F;
    /** 横へのノックバック(バニラの knockback の強さ)。仮。 */
    static final double TAIL_KNOCKBACK = 1.2D;
    /**
     * 判定に足すゆとり(ブロック)。尾先の弧の最大距離({@link #TAIL_ARC_MAX_PX})+ これ = 体格 1.6 で 6.9 ブロック。
     * 扇の角度にも、相手の幅の半分 + これ のぶんのゆとりを足す({@link #inTailArc})。
     */
    static final double TAIL_MARGIN = 0.6D;
    /** ユーザー指定「クールタイム 8 秒」。 */
    static final int TAIL_COOLDOWN = 160;

    // --- ガード → 叩きつけ ---
    /** ガード姿勢へ移る間(アニメ guard_start 0.4 秒)。 */
    static final int GUARD_START_TICKS = TRANSITION_TICKS + 8;
    /** ユーザー指定「最大 5 秒」。ガードを始めた tick(guard_start の頭)から数える。 */
    static final int GUARD_MAX_TICKS = 100;
    /** 溜められる量の上限(仮。TaCZ の連射で一撃死を作らないため)。衝撃波の {@link ScutimnesShockwave#MAX_STORED} と同じ。 */
    static final float GUARD_STORE_CAP = 60.0F;
    /** ガードで一撃を溜めたときに立てる無敵時間(tick)。バニラの被弾と同じ 20({@link #absorb})。 */
    static final int GUARD_INVULNERABLE_TICKS = 20;
    /** ユーザー指定「クールタイム 45 秒」。叩きつけた tick から数える。 */
    static final int GUARD_COOLDOWN = 900;
    /** 条件が揃っているとき、1 tick あたり 1/これ でガードに入る(平均 1 秒)。仮。 */
    static final int GUARD_CHANCE_PER_TICK = 20;
    /** 「正面から攻めてきている」= 直近この tick の間に正面から被弾した(2 秒)。仮。 */
    static final int GUARD_FRONT_HIT_WINDOW = 40;
    /** ガード中、標的がこれより遠ければゆっくり寄る(波が届くように。起点の約 1.7 先から 6 ブロック)。仮。 */
    static final double GUARD_APPROACH_RANGE = 5.0D;
    /** 叩きつけ(アニメ slam 2.0 秒、1.10 秒で盾が正面の地面を叩く)。 */
    static final int SLAM_TICKS = TRANSITION_TICKS + 40;
    static final int SLAM_HIT_TICK = TRANSITION_TICKS + 22;
    /** slam の 0 秒で咆哮(声の山は鳴らしてから約 0.6 秒 = 振り上げの頂点)。 */
    static final int SLAM_ROAR_TICK = TRANSITION_TICKS;

    /** 倒れる(アニメ death 1.5 秒で崩れ落ちる)。バニラの 20 tick で消すと途中で消えるので延ばす。 */
    static final int DEATH_TICKS = TRANSITION_TICKS + 30;

    // ------------------------------------------------------------------ 形(tools/scutimnes-anim/points.json と合わせる)
    //
    // アニメ担当の計測値(points.json、体格 1.0 のファイル座標 px)から取る値。2026-09-24 に最終のアニメの実測値へ合わせた
    // (それまでは仕様書 §2・§3 の仮置き)。アニメを作り直したら<b>ここだけ</b>合わせる。ブロックへは px ÷ 16 × getScale()({@link #px})。

    /** エンティティの原点(ファイル座標の z、px)。描画はモデルを −これ ÷ 16 × 体格 だけ +Z へずらす。 */
    public static final double ORIGIN_Z_PX = -14.0D;
    /**
     * (a) {@code bash_l} の 0.60 秒の盾(shield2)の前面の中心(ファイル座標 px)。{@code bash_r} は x の符号が逆。
     * points.json の {@code bash.bash_l.plate_front_center}。原点の 18.2px 前 = 体格 1.6 で 1.82 ブロック前
     * (仕様書の「z ≈ -36」は初期姿勢の盾を z -28 と読み違えた値で、実物の盾は構えで z -24.6、突き出して -32.2)。
     */
    static final double[] BASH_POINT = {0.627D, 6.244D, -32.220D};
    /** 突き出した盾の板の外接箱の左右(px、{@code bash_l}。ファイル +X = 左)。当たる範囲の左右。points.json の plate_box_min / max の x。 */
    static final double BASH_PLATE_MIN_X_PX = -1.877D;
    static final double BASH_PLATE_MAX_X_PX = 9.297D;
    /** 当たる範囲の上端(px、足元から)。points.json の plate_box_max の y。 */
    static final double BASH_TOP_PX = 14.015D;
    /**
     * (b) {@code slam} の 1.10 秒に盾が地面に触れる点(ファイル座標 px、左右の平均)。衝撃波の起点。
     * points.json の {@code slam.contact_center}(y は地面の高さを使うので見ない)。原点の 1.42 ブロック前(体格 1.6)。
     */
    static final double[] SLAM_POINT = {0.0D, 0.05D, -28.186D};
    /** (c) 尾の振り全体で尾先が描く弧の、原点からの最大距離(px)。points.json の {@code tail_*.arc_radius_max}(62.995 / 62.996)。 */
    static final double TAIL_ARC_MAX_PX = 63.0D;
    /**
     * (c) 尾先が真後ろから<b>払う側</b>へ振り抜く角度の最大(度)と、<b>振りかぶる側</b>の最大(度)。
     * points.json の {@code tail_l.arc_angle_max_deg}(62.2)/ {@code arc_angle_min_deg}(−42.4)。{@code tail_r} は左右が逆で同じ大きさ。
     */
    static final double TAIL_ARC_SWEEP_DEG = 62.2D;
    static final double TAIL_ARC_WINDUP_DEG = 42.4D;
    /**
     * (e) 再生速度 1.0 のときの地上速度(ブロック/tick、体格 1.6)。points.json の
     * {@code loops.<名前>.ground_speed_blocks_per_tick_at_scale_1_6}。
     */
    static final double WALK_GROUND_SPEED = 0.169D;
    static final double WALK_SHIELD_GROUND_SPEED = 0.082D;
    static final double GUARD_WALK_GROUND_SPEED = 0.077D;
    /** 上の地上速度を測ったときの体格(points.json の {@code _at_scale_1_6})。使う所で {@link #groundSpeed} がいまの体格へ直す。 */
    static final double GROUND_SPEED_MEASURED_SCALE = 1.6D;
    /** (e) 再生速度 1.0 のときの旋回の足踏みの角速度(度/tick)。points.json の {@code loops.turn*.turn_deg_per_tick} の絶対値。 */
    static final double TURN_ANIM_DEG_PER_TICK = 1.5D;
    static final double TURN_SHIELD_ANIM_DEG_PER_TICK = 1.5D;
    static final double GUARD_TURN_ANIM_DEG_PER_TICK = 1.5D;

    // ------------------------------------------------------------------ 旋回

    /**
     * 1 tick に回れる最大(度)。仮: 秒 30°。機関砲台は 0.75(秒 15°)だが、同じにすると背後に張り付かれ続けて
     * 近づかれたら何もできないので倍にした。
     */
    public static final float MAX_TURN_PER_TICK = 1.5F;
    /** 角速度を「行きたい速さ」へ 1 tick に寄せる割合(機関砲台の {@code SLEW_SMOOTHING} と同じ作り)。仮。 */
    static final float TURN_SMOOTHING = 0.2F;
    /** 向きのずれがこれ(度)を超えたら進まずにその場で回る。仮。 */
    static final float TANK_MAX_OFF = 70.0F;
    /** 止まっていて 1 tick にこれ(度)以上回っていれば旋回の足踏み(turn 系)を流す。 */
    static final float TURN_ANIM_MIN = 0.25F;

    /** 向きの希望の強さ。強いものが勝つ(同じ強さなら後から出したもの)。 */
    static final int TURN_LOOK = 0;
    static final int TURN_HUNT = 1;
    static final int TURN_MOVE = 2;
    static final int TURN_ACTION = 3;

    // ------------------------------------------------------------------ 盾

    /** ユーザー指定「距離 10 ほどで盾を下ろす」。体格は掛けない(ブロックそのまま)。 */
    public static final double SHIELD_DOWN_RANGE = 10.0D;
    /** ユーザー指定「盾を上げるのは距離が 15 を超えたとき」。 */
    public static final double SHIELD_UP_RANGE = 15.0D;
    /** 盾に当たる = 攻撃の出どころが正面 ±60° の内(cos 60°)。仮。 */
    static final double SHIELD_ARC_COS = 0.5D;
    /** ユーザー指定「盾に当たった近接は 50% カット」。 */
    static final float SHIELD_MELEE_FACTOR = 0.5F;
    /** 直近これだけの tick の間に標的の飛び道具が当たっていれば「遠距離の標的」(10 秒)。仮。 */
    static final int RANGED_MEMORY_TICKS = 200;
    /**
     * 機関砲台に最後に撃たれてからこれだけの間は「撃たれている」(3 秒。砲台は 5 tick に 1 発なので、撃たれ続けている間は切れない)。
     * 標的が居なければ、その間は砲台へ盾を向ける(ユーザー指定 2026-09-24)。長さは仮。
     */
    static final int TURRET_FIRE_MEMORY_TICKS = 60;
    /**
     * 機関砲台に撃たれながら進むとき、進む向きを砲台からずらしてよい幅(度)。盾(正面 ±60°)の内側に、遅い旋回(秒間 30°)の
     * 遅れの余りを残す。仮決め 2026-09-25({@link #faceTurretFire})。
     */
    static final float BRACE_ARC = 45.0F;
    /** 覚えておく機関砲台の数({@link #bracingTurret})。 */
    static final int TURRET_SHOTS_KEPT = 4;
    /** ユーザー指定「盾を下ろしている間は通常の −35%」。移動速度の属性に掛ける(バニラの Mob は実速度が属性の 2 乗に比例)。 */
    static final double SHIELD_SLOW = -0.35D;
    static final ResourceLocation SHIELD_SLOW_ID = OvergrownVegetation.id("scutimnes_shield_slow");
    /** TaCZ(銃 mod)の名前空間。開発環境には入っていないので名前で見分けるだけ({@code adaptation/WeaponClassifier} と同じ)。 */
    static final String TACZ = "tacz";

    // ------------------------------------------------------------------ 同期

    private static final EntityDataAccessor<Integer> DATA_ACTION =
            SynchedEntityData.defineId(ScutimnesEntity.class, EntityDataSerializers.INT);
    /** 動作を始めるたびに 1 増える。同じアニメを頭から流し直す合図。 */
    private static final EntityDataAccessor<Integer> DATA_SEQ =
            SynchedEntityData.defineId(ScutimnesEntity.class, EntityDataSerializers.INT);
    /** 盾を下ろしているか(下ろしきった後 true、上げ始めた瞬間に false)。待機・歩き・旋回のアニメを選ぶ。 */
    private static final EntityDataAccessor<Boolean> DATA_SHIELD =
            SynchedEntityData.defineId(ScutimnesEntity.class, EntityDataSerializers.BOOLEAN);
    /**
     * 直前の tick に回った角度(度、+ が右回り = yaw が増える向き、0.05 刻み)。旋回の足踏みを流すか・どの速さで流すかに使う。
     * クライアントの {@code yBodyRot} は向きの同期(1 バイト = 1.4° 刻み)の補間なので、ゆっくりした旋回だと止まったり動いたりに見える。
     */
    private static final EntityDataAccessor<Float> DATA_TURN =
            SynchedEntityData.defineId(ScutimnesEntity.class, EntityDataSerializers.FLOAT);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    /** 旋回の足踏み(右回り = {@link #DATA_TURN} &gt; 0 = yaw が増える向き)。 */
    private static final RawAnimation TURN = RawAnimation.begin().thenLoop("turn");
    /** 左回りの足踏み(yaw が減る向き)。生成器が右回りを鏡に写して作る({@code _l} の 3 本とも)。 */
    private static final RawAnimation TURN_L = RawAnimation.begin().thenLoop("turn_l");
    private static final RawAnimation IDLE_SHIELD = RawAnimation.begin().thenLoop("idle_shield");
    private static final RawAnimation WALK_SHIELD = RawAnimation.begin().thenLoop("walk_shield");
    private static final RawAnimation TURN_SHIELD = RawAnimation.begin().thenLoop("turn_shield");
    /** 盾を下ろした左回り。 */
    private static final RawAnimation TURN_SHIELD_L = RawAnimation.begin().thenLoop("turn_shield_l");
    private static final RawAnimation SHIELD_DOWN = RawAnimation.begin().thenPlayAndHold("shield_down");
    private static final RawAnimation SHIELD_UP = RawAnimation.begin().thenPlayAndHold("shield_up");
    private static final RawAnimation BASH_L = RawAnimation.begin().thenPlayAndHold("bash_l");
    private static final RawAnimation BASH_R = RawAnimation.begin().thenPlayAndHold("bash_r");
    private static final RawAnimation TAIL_L = RawAnimation.begin().thenPlayAndHold("tail_l");
    private static final RawAnimation TAIL_R = RawAnimation.begin().thenPlayAndHold("tail_r");
    private static final RawAnimation GUARD_START = RawAnimation.begin().thenPlayAndHold("guard_start");
    private static final RawAnimation GUARD = RawAnimation.begin().thenLoop("guard");
    /** ガード姿勢のまま歩く(移動しているときだけ。その場で向きを変えるときは {@link #GUARD_TURN})。 */
    private static final RawAnimation GUARD_WALK = RawAnimation.begin().thenLoop("guard_walk");
    /**
     * ガード姿勢のままその場で向きを変える足踏み(右回り / 左回り)。仕様書の契約では guard_walk が向き変えも兼ねていたが、
     * 生成器が旋回用を別に作った(足が原点まわりの円周方向へ運ばれる。guard_walk を旋回の速さで流すと足が滑る)。
     */
    private static final RawAnimation GUARD_TURN = RawAnimation.begin().thenLoop("guard_turn");
    private static final RawAnimation GUARD_TURN_L = RawAnimation.begin().thenLoop("guard_turn_l");
    private static final RawAnimation SLAM = RawAnimation.begin().thenPlayAndHold("slam");
    private static final RawAnimation DEATH = RawAnimation.begin().thenPlayAndHold("death");

    /**
     * 歩き・旋回のループを流す速さの幅。上は速すぎて脚が破綻しないように。下はほぼ止まっているだけ
     * (2026-09-24 に 0.4 → 0.05。位相を積み上げる {@link PhaseKeepingAnimationController} にしたので、遅く流しても脚は破綻せず
     * その場で止まるだけ。0.4 のままだと、止まりかけの体より速くループが回って接地した足が後ろへ擦られていた)。
     */
    private static final double LOOP_SPEED_MIN = 0.05D;
    private static final double LOOP_SPEED_MAX = 1.8D;
    /**
     * これ(ブロック/tick)以上前へ進んだ tick は歩き系のループ(クライアント)。体格 1.6 で 1 tick 0.1 px。
     * 止まるときは、体(位置の補間)がこれを下回るまでループを体の速さに合わせて流し、それから待機へ切り替える(止めてから約 9 tick)。
     */
    static final double WALK_MIN_SPEED = 0.01D;
    /**
     * 旋回で足先が運ばれる半径(px、体格 1.0、原点から)。足先の置き場の真ん中あたり(ユーザー製の初期姿勢で 13.5〜15.4)。
     * 前へ進みながら回っているとき、旋回で足先が運ばれる速さ(角速度 × これ)のほうが前へ進む速さより大きければ旋回の足踏みを流す。
     */
    static final double TURN_FOOT_RADIUS_PX = 14.3D;

    private static final EquipmentSlot[] ARMOR_SLOTS =
            {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /** 特殊攻撃(ガード)を通常攻撃(バッシュ・尾)の合間に出すための勘定。保存しない。 */
    private final SpecialPacing pacing = SpecialPacing.standard();

    // --- サーバー側の状態(保存しない。読み込み直したら盾を上げた・何もしていない状態から) ---
    private int actionTicks;
    private int attackCooldown;
    private int tailCooldown;
    private int guardCooldown;
    /** ガードを始めてからの tick(guard_start から数える)。 */
    private int guardTicks;
    /** ガードで溜めた量(0〜{@link #GUARD_STORE_CAP})。 */
    private float guardStored;
    /** 最後の叩きつけで衝撃波に渡した量(GameTest 用)。 */
    private float lastSlamStored = -1.0F;
    private int lastFrontHitTick = -1000;
    /** 最後に当たった機関砲台の弾が出た点(砲耳)と、その tick。砲台はブロックなので標的にできず、ここで覚える。 */
    @Nullable
    private Vec3 turretFireFrom;
    private int turretFireTick = -1000;
    /** 最近撃ってきた機関砲台(古い順、{@value #TURRET_SHOTS_KEPT} 台まで。{@link #bracingTurret})。保存しない。 */
    private final List<TurretShot> turretShots = new ArrayList<>(TURRET_SHOTS_KEPT);
    @Nullable
    private UUID lastRangedAttacker;
    private int lastRangedTick = -1000;
    /** 同じ弾を二度数えない(弾いた弾が次の tick もまだ当たり判定に掛かっていることがある)。 */
    private int lastDeflectedId = -1;
    private int lastDeflectedTick = -1000;
    private int repathTicks;
    /**
     * 届かない標的(水・舟の上・柱の上・谷の向こう)へ、同じマス同士の経路探索を 8 tick ごとにやり直さない(2026-09-24、
     * 3×3×3 の体で 1 回が重い)。追う({@link #combatTick})とガード中に寄る({@link #tickGuard})は同じ呼び出しなので 1 つを共有。
     */
    private final PathRetryMemo pathMemo = new PathRetryMemo();

    // --- 向き(サーバーが正。クライアントは同期された yRot に体を揃えるだけ) ---
    /** いまの向き(度)。NaN なら次の tick に yRot から取り直す(湧いた直後・テレポートの後)。 */
    private float heading = Float.NaN;
    /** いまの角速度(度/tick)。 */
    private float turnSpeed;
    private float wantedYaw;
    private int wantedPriority = -1;

    /** クライアント: 最後に見た {@link #DATA_SEQ}。 */
    private int clientSeq = -1;
    /** クライアント: いまのガードに入る動作の間に、旋回の足踏みへ移ったか(移ったら guard_start へ戻さない)。 */
    private boolean clientGuardStepping;

    public ScutimnesEntity(EntityType<? extends ScutimnesEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("scutimnes", 40);
        this.moveControl = new TankMoveControl(this);
        this.lookControl = new TankLookControl(this);
        this.setPathfindingMalus(PathType.WATER, -1.0F);
        this.setPathfindingMalus(PathType.LEAVES, -1.0F);
        this.getNavigation().setCanFloat(false);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), "scutimnes");
    }

    /** 幅のある体でも段差の上に体が入るかを確かめる経路探索(ファルキムネス・他の大型種と同じ)。 */
    @Override
    protected PathNavigation createNavigation(Level level) {
        return new GroundPathNavigation(this, level) {
            @Override
            protected PathFinder createPathFinder(int maxVisitedNodes) {
                this.nodeEvaluator = new OvergrownNodeEvaluator();
                this.nodeEvaluator.setCanPassDoors(true);
                return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
            }
        };
    }

    /**
     * 体は常に {@code yRot} と同じ向き(サーバーでもクライアントでも)。バニラの {@code BodyRotationControl} は
     * 動いている向きへ体を、止まっていれば頭の向きへ体を回すので、そのままだと体だけ速く回ってしまう。
     * {@code Mob} のコンストラクタの中で呼ばれる —— このクラスのフィールドには触れないこと。
     */
    @Override
    protected BodyRotationControl createBodyControl() {
        return new TankBodyControl(this);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION, ACT_NONE);
        builder.define(DATA_SEQ, 0);
        builder.define(DATA_SHIELD, false);
        builder.define(DATA_TURN, 0.0F);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new HuntGoal(this));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new OvergrownHurtByTargetGoal(this));
        ParasitizedTargetGoal.installTargeting(this, 2);
    }

    // ------------------------------------------------------------------ 状態

    public int getAction() {
        return this.entityData.get(DATA_ACTION);
    }

    public boolean isActing() {
        return getAction() != ACT_NONE;
    }

    /**
     * TD の護衛としてターゲットブロックを叩いたときの見せ方(盾のバッシュを 1 回。ほかの動作中は何もしない。{@code td/TdAssaultGoal}、
     * 2026-09-25)。削りは叩いた瞬間に入る(振りの当たりの時刻は待たない)。
     */
    @Override
    public void playAttackAnimation() {
        if (!isActing()) {
            startBash(this.random.nextBoolean());
        }
    }

    /** 盾を下ろしている(下ろしきった)か。ガード・叩きつけの間も true(終わると下ろした構えへ戻る)。 */
    public boolean isShieldDown() {
        return this.entityData.get(DATA_SHIELD);
    }

    /** ガードで 100% カットしている間か(guard_start の頭から叩きつけまで)。 */
    public boolean isGuarding() {
        int act = getAction();
        return act == ACT_GUARD_START || act == ACT_GUARD;
    }

    /**
     * いま盾が正面を守っているか(近接 50% / 飛び道具を弾く / 爆発 0)。下ろしている途中は {@link #SHIELD_SETTLE_TICK} から、
     * 上げている間と叩きつけの間(盾は頭の上)は効かない。クライアントでは下ろしている途中の tick が分からないので効かない扱い
     * (矢の当たりの見込みにしか使わない。決めるのはサーバー)。
     */
    public boolean isShieldEffective() {
        int act = getAction();
        if (act == ACT_SHIELD_DOWN) {
            return !this.level().isClientSide() && this.actionTicks >= SHIELD_SETTLE_TICK;
        }
        if (act == ACT_SHIELD_UP || act == ACT_SLAM) {
            return false;
        }
        return isShieldDown();
    }

    /** バッシュ・尾・ガードを出せる構えか(盾を下ろしきっていて、何もしていない)。 */
    private boolean shieldSettled() {
        return isShieldDown() && !isActing();
    }

    /** ガードで溜めている量(GameTest 用)。 */
    public float getGuardStored() {
        return this.guardStored;
    }

    /** 最後の叩きつけで衝撃波に渡した量。まだ叩きつけていなければ −1(GameTest 用)。 */
    public float getLastSlamStored() {
        return this.lastSlamStored;
    }

    /** 特殊攻撃の出し方の勘定(GameTest 用。書き換えない)。 */
    public SpecialPacing getSpecialPacing() {
        return this.pacing;
    }

    /** 直前の tick に回った角度(度、+ が右回り)。 */
    public float getTurnRate() {
        return this.entityData.get(DATA_TURN);
    }

    /**
     * 動作を始める。特殊攻撃の出し方の勘定もここで数える —— バッシュ・尾は通常攻撃、ガードに入った瞬間が特殊攻撃
     * (ファルキムネスと同じ。GameTest の {@link #forceAction} も数える)。盾の上げ下げ・ガードの保持・叩きつけは続きの動作なので数えない。
     */
    private void startAction(int action) {
        switch (action) {
            case ACT_BASH_L, ACT_BASH_R, ACT_TAIL_L, ACT_TAIL_R -> this.pacing.onNormalAttack();
            case ACT_GUARD_START -> this.pacing.onSpecial();
            default -> {
            }
        }
        this.entityData.set(DATA_ACTION, action);
        this.entityData.set(DATA_SEQ, this.entityData.get(DATA_SEQ) + 1);
        this.actionTicks = 0;
    }

    private void endAction() {
        this.entityData.set(DATA_ACTION, ACT_NONE);
        this.actionTicks = 0;
    }

    private void setShieldDown(boolean down) {
        this.entityData.set(DATA_SHIELD, down);
        updateShieldSlow();
    }

    /** 盾を下ろしている間だけ移動速度の属性を −35%(ユーザー指定)。ガード中も同じ速さ(仕様)。 */
    private void updateShieldSlow() {
        AttributeInstance speed = this.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        boolean slow = isShieldDown();
        boolean has = speed.hasModifier(SHIELD_SLOW_ID);
        if (slow && !has) {
            speed.addTransientModifier(new AttributeModifier(SHIELD_SLOW_ID, SHIELD_SLOW,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        } else if (!slow && has) {
            speed.removeModifier(SHIELD_SLOW_ID);
        }
    }

    // ------------------------------------------------------------------ 毎 tick

    /**
     * 動作・盾・旋回は {@code aiStep} で進める({@code customServerAiStep} ではなく)。Goal の無い個体
     * (GameTest の {@code spawnWithNoFreeWill})でも進むので、GameTest が {@link #forceAction} / {@link #combatTick} で確かめられる。
     * 旋回({@link #tickTurn})は最後 —— この tick に出た向きの希望(Goal・移動・動作)を全部見てから回す。
     */
    @Override
    public void aiStep() {
        if (!this.level().isClientSide() && !Float.isNaN(this.heading)) {
            // この tick の間(Goal・移動・当たりの判定)に使う向きは、旋回の制限を通ったものだけ。
            // tick の外でよそが書いた向き(setYRot の直書き)はここで戻す(置き直しの moveTo は heading を捨てるので残る)。
            applyFacing();
        }
        super.aiStep();
        if (this.level().isClientSide() || !this.isAlive()) {
            return;
        }
        if (this.attackCooldown > 0) this.attackCooldown--;
        if (this.tailCooldown > 0) this.tailCooldown--;
        if (this.guardCooldown > 0) this.guardCooldown--;
        // 交戦の勘定(標的が替わってもやり直さない。10 秒見失ったらやり直す —— SpecialPacing の javadoc)。
        // ファルキムネスと同じく AI の後に数える(HuntGoal が見るのは 1 tick 前の勘定。暖機と見失いの 10 秒が 1 tick ずれるだけ)。
        this.pacing.tick(this.getTarget());
        if (isActing()) {
            this.actionTicks++;
            tickAction();
        } else {
            tickShield();
            faceTurretFire();
        }
        tickTurn();
        updateShieldSlow();
    }

    private void tickAction() {
        LivingEntity target = this.getTarget();
        boolean hasTarget = target != null && target.isAlive();
        switch (getAction()) {
            case ACT_SHIELD_DOWN -> {
                holdStill();
                if (this.actionTicks == SHIELD_SETTLE_TICK - SHIELD_SOUND_LEAD) {
                    playSound(ModSounds.SCUTIMNES_SHIELD.get(), 1.0F, 1.0F);
                }
                if (this.actionTicks >= SHIELD_DOWN_TICKS) {
                    setShieldDown(true);
                    endAction();
                }
            }
            case ACT_SHIELD_UP -> {
                holdStill();
                if (this.actionTicks == SHIELD_UP_TICKS - SHIELD_SOUND_LEAD) {
                    playSound(ModSounds.SCUTIMNES_SHIELD.get(), 1.0F, 0.92F);
                }
                if (this.actionTicks >= SHIELD_UP_TICKS) {
                    endAction();
                }
            }
            case ACT_BASH_L, ACT_BASH_R -> {
                // 溜めの間は踏み込まず、向きも変えない(足を踏ん張って後ろへためるアニメなので。BASH_START_RATIO の javadoc)。
                holdStill();
                if (this.actionTicks == 1) playSound(ModSounds.SCUTIMNES_GROWL.get(), 1.2F, getVoicePitch());
                if (this.actionTicks == BASH_HIT_TICK) {
                    bashHit(getAction() == ACT_BASH_L);
                }
                if (this.actionTicks >= BASH_TICKS) {
                    this.attackCooldown = BASH_COOLDOWN;
                    endAction();
                }
            }
            case ACT_TAIL_L, ACT_TAIL_R -> {
                // 振っている間は向きを変えない(脚は踏ん張ったまま。回すと足が滑る)。
                holdStill();
                if (this.actionTicks == 1) playSound(ModSounds.SCUTIMNES_GROWL.get(), 1.0F, getVoicePitch() * 1.05F);
                if (this.actionTicks == TAIL_SOUND_TICK) playSound(ModSounds.SCUTIMNES_TAIL.get(), 1.4F, 1.0F);
                if (this.actionTicks == TAIL_HIT_TICK) {
                    tailHit(getAction() == ACT_TAIL_L);
                }
                if (this.actionTicks >= TAIL_TICKS) {
                    endAction();
                }
            }
            case ACT_GUARD_START, ACT_GUARD -> tickGuard(target, hasTarget);
            case ACT_SLAM -> {
                // 振り上げてから叩きつけるまで向きは固定(ガード中に標的へ向け終わっている。回すと後ろ脚が滑る)。
                holdStill();
                if (this.actionTicks == SLAM_ROAR_TICK) {
                    this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                            ModSounds.SCUTIMNES_ROAR.get(), SoundSource.HOSTILE, 3.0F, getVoicePitch());
                }
                if (this.actionTicks == SLAM_HIT_TICK) {
                    slam();
                }
                if (this.actionTicks >= SLAM_TICKS) {
                    endAction();
                }
            }
            default -> endAction();
        }
    }

    /** 動作をいますぐ始める(GameTest・デバッグ用。間合いや盾の条件は見ない)。ガードは溜めを 0 から数え直す。 */
    public void forceAction(int action) {
        if (action == ACT_GUARD_START) {
            startGuard();
        } else if (action == ACT_SHIELD_UP) {
            startShieldUp();
        } else {
            startAction(action);
        }
    }

    /** 盾をいますぐ下ろした(上げた)状態にする。上げ下げの動作は挟まない(GameTest 用)。 */
    public void forceShieldDown(boolean down) {
        setShieldDown(down);
    }

    // ------------------------------------------------------------------ 盾

    /**
     * 盾を下ろすべきか(ユーザー指定のヒステリシス)。標的が居なければ上げる。遠距離の標的なら距離に関係なく下ろす。
     * 距離 {@link #SHIELD_DOWN_RANGE} 以下で下ろし、{@link #SHIELD_UP_RANGE} を超えたら上げ、その間はいまのまま。
     *
     * @param down      いま下ろしているか
     * @param distance  標的までの水平距離(ブロック、原点から)
     */
    public static boolean wantShieldDown(boolean down, boolean hasTarget, double distance, boolean rangedTarget) {
        if (!hasTarget) {
            return false;
        }
        if (rangedTarget || distance <= SHIELD_DOWN_RANGE) {
            return true;
        }
        if (distance > SHIELD_UP_RANGE) {
            return false;
        }
        return down;
    }

    /** 何もしていないとき、標的との距離で盾を上げ下げする。上げ下げの間(0.8 秒)は足を止める。 */
    private void tickShield() {
        LivingEntity target = this.getTarget();
        boolean has = target != null && target.isAlive();
        boolean want = wantShieldDown(isShieldDown(), has, has ? horizontalDistanceTo(target) : 0.0D,
                has && isRangedAttacker(target))
                || (!has && isUnderTurretFire());
        if (want && !isShieldDown()) {
            startAction(ACT_SHIELD_DOWN);
        } else if (!want && isShieldDown()) {
            startShieldUp();
        }
    }

    /** 標的が居ないまま機関砲台に撃たれているか(最後の被弾から {@link #TURRET_FIRE_MEMORY_TICKS} 以内)。 */
    public boolean isUnderTurretFire() {
        return this.turretFireFrom != null && this.tickCount - this.turretFireTick <= TURRET_FIRE_MEMORY_TICKS;
    }

    /**
     * 標的が居ないときに機関砲台に撃たれている間は、砲台へ正面(盾)を向ける(ユーザー指定 2026-09-24)。盾は {@link #tickShield} が
     * 下ろす。砲台はブロックで標的にできないので、弾が出た点(砲耳)へ向く。標的が居るときは標的との戦いを優先する。
     * 何もしていない tick だけ呼ぶ(盾の上げ下げの間は脚を踏み替えているので回さない)。
     * <ul>
     *     <li><b>止まっている</b>(この tick に移動・視線の希望が無い)… 砲台へ正面を向ける(動作の希望 {@link #TURN_ACTION})。</li>
     *     <li><b>進んでいる</b>(TD の護衛がターゲットへ歩いている など)… 止まらずに、進む向きを砲台の ±{@value #BRACE_ARC}° に収める
     *     —— 盾(正面 ±60°)で弾を受けながら前進する(ユーザー指定 2026-09-25「機関砲に攻撃されている間は機関砲台のほうを向き、攻撃を
     *     防ぎながら目標に前進するように」)。戦車は体の向きにしか進めない(横歩き・後ずさりが無い)ので、進む向きが砲台から
     *     {@value #BRACE_ARC} + {@value #TANK_MAX_OFF}° より離れている(目標が砲台の反対側)ときは、盾を向けたままでは進めない ——
     *     進む方を取る(仮決め 2026-09-25)。</li>
     * </ul>
     * 2026-09-25 のユーザー報告「機関砲台の攻撃を防ぐためにその場で止まってしまい動かない」: はじめは撃たれている間ずっと毎 tick
     * 経路を止めていた。砲台は 5 tick に 1 発・撃てている目標を離さないので、撃たれ続けて一歩も進めなかった。
     */
    private void faceTurretFire() {
        LivingEntity target = this.getTarget();
        if ((target != null && target.isAlive()) || !isUnderTurretFire()) {
            return;
        }
        Vec3 turretAt = bracingTurret();
        if (turretAt == null) {
            return;
        }
        float turret = yawTo(turretAt);
        if (this.wantedPriority < 0) {
            wantFacing(turret, TURN_ACTION);
            return;
        }
        float rel = Mth.wrapDegrees(this.wantedYaw - turret);
        if (Math.abs(rel) <= BRACE_ARC || Math.abs(rel) > BRACE_ARC + TANK_MAX_OFF) {
            return; // 進む向きのままで盾が砲台に向いている / 目標が砲台の反対側(進む方を取る)
        }
        wantFacing(turret + Mth.clamp(rel, -BRACE_ARC, BRACE_ARC), TURN_ACTION);
    }

    /** 機関砲台の弾を覚える(同じ砲台 = 砲耳が 1 ブロック以内なら時刻だけ書き換える。{@value #TURRET_SHOTS_KEPT} 台まで)。 */
    private void noteTurretShot(Vec3 origin) {
        this.turretShots.removeIf(s -> s.origin().distanceToSqr(origin) <= 1.0D);
        this.turretShots.add(new TurretShot(origin, this.tickCount));
        while (this.turretShots.size() > TURRET_SHOTS_KEPT) {
            this.turretShots.remove(0);
        }
    }

    /**
     * 盾を向ける砲台: 最近({@link #TURRET_FIRE_MEMORY_TICKS} 以内)撃ってきた砲台のうち、いまの体の向きにいちばん近いもの。
     * 複数の砲台に撃たれても、当たるたびに向きを砲台から砲台へ振られない(遅い旋回が追いつかず、どちらにも盾が向かなくなる)。
     */
    @Nullable
    private Vec3 bracingTurret() {
        Vec3 best = null;
        float bestOff = Float.MAX_VALUE;
        for (TurretShot shot : this.turretShots) {
            if (this.tickCount - shot.tick() > TURRET_FIRE_MEMORY_TICKS) {
                continue;
            }
            float off = Math.abs(Mth.wrapDegrees(yawTo(shot.origin()) - this.getYRot()));
            if (off < bestOff) {
                bestOff = off;
                best = shot.origin();
            }
        }
        return best != null ? best : this.turretFireFrom;
    }

    /** 覚えている機関砲台の弾(砲耳の位置と、最後に当たった tick)。 */
    private record TurretShot(Vec3 origin, int tick) {
    }

    /** 盾を上げ始める。上げ始めた瞬間から盾の効果は無く、足も通常の速さに戻る(動作の間は止まっている)。 */
    private void startShieldUp() {
        setShieldDown(false);
        startAction(ACT_SHIELD_UP);
    }

    /**
     * 標的が遠距離攻撃をしてくるか(仮の定義)。遠距離武器を手に持っている(弓・クロスボウ・トライデント・{@code #c:tools/bow} 等・
     * TaCZ の名前空間のアイテム)/ 遠距離攻撃の Mob({@link RangedAttackMob})/ 直近 10 秒にその相手の飛び道具が当たった。
     */
    public boolean isRangedAttacker(LivingEntity target) {
        if (target instanceof RangedAttackMob) {
            return true;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            if (isRangedWeapon(target.getItemInHand(hand))) {
                return true;
            }
        }
        return target.getUUID().equals(this.lastRangedAttacker)
                && this.tickCount - this.lastRangedTick <= RANGED_MEMORY_TICKS;
    }

    static boolean isRangedWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof ProjectileWeaponItem || stack.getItem() instanceof TridentItem) {
            return true;
        }
        if (stack.is(Tags.Items.TOOLS_BOW) || stack.is(Tags.Items.TOOLS_CROSSBOW) || stack.is(Tags.Items.RANGED_WEAPON_TOOLS)) {
            return true;
        }
        return TACZ.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace());
    }

    // ------------------------------------------------------------------ バッシュ

    /**
     * バッシュが届く距離(原点から前へ、ブロック): 突き出した盾の前面 + 相手の幅の半分 + 判定のゆとり {@link #BASH_MARGIN}
     * (= {@link #bashHit} が当てる前後の範囲の端)。振り始め・踏み込みの間合いはこれに対する割合。
     */
    private double bashReach(LivingEntity target) {
        return px(-(BASH_POINT[2] - ORIGIN_Z_PX)) + target.getBbWidth() * 0.5D + BASH_MARGIN;
    }

    /**
     * バッシュが当たる高さか({@code dy} = 相手の足元 − この個体の足元、ブロック)。下は {@link #BASH_MIN_DY}、上は突き出した盾の上端
     * ({@link #BASH_TOP_PX})+ 1。<b>振り始める条件({@link #combatTick})と当たりの判定({@link #bashHit})の両方でこれを使う</b> ——
     * 初版は振り始めだけ「体格 × 2.5 まで」(体格 1.6 で 4.0)と広く、当たりは 2.4 までだったので、段の上(2.4〜4.0 上)の相手に
     * 空振りを 2.3 秒ごとに繰り返し、その空振りを通常攻撃として数えていた。
     */
    private boolean bashReachesHeight(double dy) {
        return dy >= BASH_MIN_DY && dy <= px(BASH_TOP_PX) + 1.0D;
    }

    private void startBash(boolean left) {
        startAction(left ? ACT_BASH_L : ACT_BASH_R);
    }

    /** 突き出した盾の前(体格込み)に居る相手全員へ。左右の範囲は突き出した盾の板の外接箱(points.json)。 */
    private void bashHit(boolean left) {
        playSound(ModSounds.SCUTIMNES_BASH.get(), 1.5F, 1.0F);
        double front = px(-(BASH_POINT[2] - ORIGIN_Z_PX));
        double side = px((BASH_PLATE_MIN_X_PX + BASH_PLATE_MAX_X_PX) * 0.5D);
        double halfWidth = px((BASH_PLATE_MAX_X_PX - BASH_PLATE_MIN_X_PX) * 0.5D);
        double top = px(BASH_TOP_PX);
        Vec3 f = forward();
        Vec3 l = left();
        double sign = left ? 1.0D : -1.0D;
        AABB box = this.getBoundingBox().inflate(front + 2.0D, top + 1.0D, front + 2.0D);
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, box, this::isFoe)) {
            Vec3 d = e.position().subtract(this.position());
            double fw = d.x * f.x + d.z * f.z;
            double lat = (d.x * l.x + d.z * l.z) * sign;
            double er = e.getBbWidth() * 0.5D;
            if (fw < 0.3D || fw > front + er + BASH_MARGIN) continue;
            if (Math.abs(lat - side) > halfWidth + er + BASH_MARGIN) continue;
            if (!bashReachesHeight(e.getY() - this.getY())) continue;
            bash(e);
        }
    }

    /**
     * 1 体を殴る。ダメージ 8(攻撃力)・前へ強いノックバック(攻撃のノックバック 2.0 → バニラの knockback 1.0)は
     * {@code doHurtTarget} のまま。当たったら防具 4 部位の耐久を追加で削り、盾で防がれたらその盾の耐久を追加で削って押し戻す。
     */
    private void bash(LivingEntity victim) {
        DamageSource source = this.damageSources().mobAttack(this);
        boolean blocked = victim.isBlocking() && victim.isDamageSourceBlocked(source);
        if (this.doHurtTarget(victim)) {
            wearArmor(victim, source);
        }
        if (blocked) {
            wearShield(victim);
            Vec3 f = forward();
            victim.knockback(BASH_BLOCKED_KNOCKBACK, -f.x, -f.z);
            victim.hurtMarked = true;
        }
    }

    /** 防具 4 部位それぞれに、通常ぶんの {@link #BASH_ARMOR_WEAR_MULTIPLIER} 倍を追加で削る。 */
    private void wearArmor(LivingEntity victim, DamageSource source) {
        int extra = bashArmorWear(armorSeenDamage(source, victim, (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE)));
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack armor = victim.getItemBySlot(slot);
            if (armor.getItem() instanceof ArmorItem && armor.canBeHurtBy(source)) {
                armor.hurtAndBreak(extra, victim, slot);
            }
        }
    }

    /**
     * 防具が「受けた」とみなすダメージ(通常ぶんの削りの元)。バニラはプレイヤーの防具を<b>難易度を掛けた後</b>の量で削る
     * ({@code Player#hurt} が {@code DamageType#scaling} で増減してから {@code hurtArmor})ので、プレイヤーには同じ式を掛ける
     * (Mob 攻撃はイージー min(ダメージ ÷ 2 + 1, ダメージ)・ハード × 1.5)。プレイヤー以外は難易度で増減しない。
     * 2026-09-24 まではノーマルの値で固定していたので、合計がイージーで 5 倍・ハードで 2.3 倍になっていた。
     */
    public static float armorSeenDamage(DamageSource source, LivingEntity victim, float damage) {
        if (victim instanceof Player player) {
            return Math.max(0.0F, source.type().scaling().getScalingFunction()
                    .scaleDamage(source, player, damage, player.level().getDifficulty()));
        }
        return damage;
    }

    /**
     * バッシュ 1 回で防具 1 部位に追加で削る量(通常ぶん = バニラの max(1, ダメージ ÷ 4) の {@link #BASH_ARMOR_WEAR_MULTIPLIER} 倍)。
     *
     * @param damage 防具が受けたダメージ({@link #armorSeenDamage}。プレイヤーなら難易度を掛けた後)
     */
    public static int bashArmorWear(float damage) {
        return BASH_ARMOR_WEAR_MULTIPLIER * (int) Math.max(1.0F, damage / 4.0F);
    }

    /** 構えている盾の耐久を追加で削る。壊れたらバニラと同じく手から消して構えを解く({@code Player#hurtCurrentlyUsedShield} と同じ後始末)。 */
    private void wearShield(LivingEntity victim) {
        ItemStack shield = victim.getUseItem();
        if (shield.isEmpty() || !shield.canPerformAction(ItemAbilities.SHIELD_BLOCK)) {
            return;
        }
        InteractionHand hand = victim.getUsedItemHand();
        EquipmentSlot slot = hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
        shield.hurtAndBreak(BASH_SHIELD_WEAR, victim, slot);
        if (shield.isEmpty()) {
            victim.setItemSlot(slot, ItemStack.EMPTY);
            victim.stopUsingItem();
            victim.playSound(SoundEvents.SHIELD_BREAK, 0.8F, 0.8F + this.random.nextFloat() * 0.4F);
        }
    }

    // ------------------------------------------------------------------ 尾

    private void startTail(boolean left) {
        this.tailCooldown = TAIL_COOLDOWN;
        startAction(left ? ACT_TAIL_L : ACT_TAIL_R);
    }

    /** 尾が届く距離(原点から、ブロック。体格 1.6 で約 7)。 */
    private double tailReach() {
        return px(TAIL_ARC_MAX_PX) + TAIL_MARGIN;
    }

    /**
     * 相手が尾の当たる扇の中か(原点から見た水平の向き)。アニメの尾先が実際に通る弧 —— 真後ろから払う側へ
     * {@link #TAIL_ARC_SWEEP_DEG}、振りかぶる側へ {@link #TAIL_ARC_WINDUP_DEG} —— に、相手の幅の半分 + {@link #TAIL_MARGIN}
     * ぶんの角度のゆとりを足したもの。加えて正面から 90° 以上(前後の成分が 0 以下)であること。
     *
     * <p>2026-09-24(統合): 最初は「後ろ半分すべて」に当てていたが、アニメの尾は払う側でも真後ろから約 62° までしか行かない
     * (尾の長さで決まる)ので、真横寄りの相手は尾が届いていないのに飛ばされて見えるはず(points.json からの見込み。
     * runClient では未確認)。実測の弧に合わせた。
     *
     * @param left 左(ファイル +X)へ払う({@code tail_l})か
     */
    private boolean inTailArc(Entity e, boolean left) {
        Vec3 f = forward();
        double dx = e.getX() - this.getX();
        double dz = e.getZ() - this.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dx * f.x + dz * f.z > 0.0D) {
            return false;
        }
        if (dist < 1.0E-3D) {
            return true;
        }
        Vec3 l = left();
        double behind = -(dx * f.x + dz * f.z);
        double toSweep = (dx * l.x + dz * l.z) * (left ? 1.0D : -1.0D);
        double deg = Math.toDegrees(Math.atan2(toSweep, behind));
        double slack = Math.toDegrees(Math.asin(Math.min(1.0D, (e.getBbWidth() * 0.5D + TAIL_MARGIN) / dist)));
        return deg <= TAIL_ARC_SWEEP_DEG + slack && deg >= -(TAIL_ARC_WINDUP_DEG + slack);
    }

    /** 後ろ側の尾の扇({@link #inTailArc}、半径 {@link #tailReach})の中の相手全員へ。振り抜く側へ強く払う。 */
    private void tailHit(boolean left) {
        double reach = tailReach();
        Vec3 f = forward();
        Vec3 sweep = left().scale(left ? 1.0D : -1.0D);
        AABB box = this.getBoundingBox().inflate(reach + 1.0D, 3.0D, reach + 1.0D);
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, box, this::isFoe)) {
            Vec3 d = e.position().subtract(this.position());
            d = new Vec3(d.x, 0.0D, d.z);
            double dist = d.length();
            double dy = e.getY() - this.getY();
            if (dist > reach + e.getBbWidth() * 0.5D) continue;
            if (!inTailArc(e, left)) continue;
            if (dy < -1.5D || dy > 3.0D * this.getScale()) continue;
            if (e.hurt(this.damageSources().mobAttack(this), TAIL_DAMAGE)) {
                this.setLastHurtMob(e);
                Vec3 out = dist > 1.0E-3D ? d.scale(1.0D / dist) : f.scale(-1.0D);
                Vec3 push = sweep.add(out.scale(0.5D)).normalize();
                e.knockback(TAIL_KNOCKBACK, -push.x, -push.z);
                e.hurtMarked = true;
            }
        }
    }

    // ------------------------------------------------------------------ ガードと叩きつけ

    private void startGuard() {
        this.guardTicks = 0;
        this.guardStored = 0.0F;
        this.getNavigation().stop();
        startAction(ACT_GUARD_START);
    }

    /**
     * ガードの間: 標的へ正面を向け続け(旋回の制限はそのまま)、遠ければ盾を下ろした速さで寄る。
     * {@link #GUARD_MAX_TICKS} 経つか、溜めが上限に達したら({@link #absorb})叩きつける。
     */
    private void tickGuard(@Nullable LivingEntity target, boolean hasTarget) {
        this.guardTicks++;
        if (getAction() == ACT_GUARD_START) {
            holdStill();
            if (this.actionTicks == 1) playSound(ModSounds.SCUTIMNES_GUARD.get(), 1.4F, getVoicePitch());
            if (hasTarget) wantFacing(yawTo(target), TURN_ACTION);
            if (this.actionTicks >= GUARD_START_TICKS) startAction(ACT_GUARD);
        } else if (hasTarget) {
            wantFacing(yawTo(target), TURN_ACTION);
            if (horizontalDistanceTo(target) > GUARD_APPROACH_RANGE) {
                if (--this.repathTicks <= 0) {
                    this.repathTicks = 8;
                    this.pathMemo.moveTo(this, target, 1.0D);
                }
            } else {
                this.getNavigation().stop();
            }
        } else {
            holdStill();
        }
        if (this.guardTicks >= GUARD_MAX_TICKS && isGuarding()) {
            startSlam();
        }
    }

    /**
     * ガードで受けた一撃を溜める。上限に達したらその場でガードを終えて叩きつける。
     *
     * <p><b>バニラの無敵時間の決まりをそのまま当てる</b>(2026-09-24、レビューの指摘): {@code LivingEntity#hurt} は、前の一撃から
     * 10 tick の間({@code invulnerableTime} &gt; 10)に来た一撃のうち、前の一撃({@code lastHurt})を超えたぶんしか通さない。
     * 初版はガードの一撃を {@code super.hurt} の手前で止めていたのでこれを通らず、連打・拡散(1 tick に矢 3 本)が全部溜まって、
     * 「カットした分」(ユーザー指定)の 2.5〜3.5 倍の波を返していた。ここでは同じ決まりで溜める量を決め、無敵時間も立てる
     * (体は赤く光らない —— {@code hurtTime} は立てない)。背面・側面の一撃は {@code super.hurt} が同じ無敵時間を見る。
     * TaCZ の弾は当てる直前に無敵時間を 0 にするので、1 発の 2 回の hurt はどちらも溜まる。
     *
     * @param source 受けたダメージ({@code #bypasses_cooldown} を見るだけ)。弾いた弾(ダメージにならなかったもの)は null
     * @return 溜めた量(無敵時間で落ちた一撃は 0)
     */
    private float absorb(@Nullable DamageSource source, float amount) {
        if (amount <= 0.0F) {
            return 0.0F;
        }
        float gain;
        if (this.invulnerableTime > 10 && (source == null || !source.is(DamageTypeTags.BYPASSES_COOLDOWN))) {
            if (amount <= this.lastHurt) {
                return 0.0F;
            }
            gain = amount - this.lastHurt;
            this.lastHurt = amount;
        } else {
            gain = amount;
            this.lastHurt = amount;
            this.invulnerableTime = GUARD_INVULNERABLE_TICKS;
        }
        this.guardStored = Math.min(GUARD_STORE_CAP, this.guardStored + gain);
        if (this.guardStored >= GUARD_STORE_CAP && isGuarding()) {
            startSlam();
        }
        return gain;
    }

    private void startSlam() {
        this.getNavigation().stop();
        startAction(ACT_SLAM);
    }

    /** 盾が地面を叩いた瞬間: 叩いた点から前方へ扇状の衝撃波(溜めた量が乗る)。 */
    private void slam() {
        playSound(ModSounds.SCUTIMNES_SLAM.get(), 2.5F, 1.0F);
        Vec3 f = forward();
        Vec3 l = left();
        double ahead = px(-(SLAM_POINT[2] - ORIGIN_Z_PX));
        double side = px(SLAM_POINT[0]);
        Vec3 origin = this.position().add(f.x * ahead + l.x * side, 0.0D, f.z * ahead + l.z * side);
        this.lastSlamStored = this.guardStored;
        if (this.level() instanceof ServerLevel server) {
            ScutimnesShockwave.spawn(server, this, origin, this.getYRot(), this.guardStored);
        }
        this.guardStored = 0.0F;
        this.guardCooldown = GUARD_COOLDOWN;
    }

    // ------------------------------------------------------------------ 被弾

    /** 盾から見た攻撃の種類。 */
    private enum Hit { MELEE, PROJECTILE, EXPLOSION, OTHER }

    /**
     * 盾に効くのは剣による攻撃・状態異常・炎だけ(ユーザー指定)。ここで分ける(上から順に):
     * <ul>
     *     <li>TaCZ の弾(爆発を除く)→ 正面なら弾く(0)</li>
     *     <li>{@code #bypasses_shield}(落下・窒息・魔法・衝撃波・噛みつきなど、盾で受けるものではない)→ 素通り</li>
     *     <li>爆発 → 正面なら 0</li>
     *     <li>飛び道具(タグ・直接の出どころが弾・この MOD の機関砲台の弾)→ 正面なら弾く(0)</li>
     *     <li>炎・棘の鎧 → 素通り</li>
     *     <li>殴った本人が直接当てたもの(剣・斧・素手・Mob の近接。仮に全部「剣による攻撃」扱い)→ 正面なら 50%</li>
     * </ul>
     *
     * <p><b>TaCZ の弾は {@code #bypasses_shield} より先に見る</b>(2026-09-24、レビューの指摘)。TaCZ は 1 発を 2 回の hurt に分けて当て、
     * 2 回目(防具貫通のぶん = 銃の {@code armor_ignore}、既定の銃で 20〜60%)の種類 {@code tacz:bullet_ignore_armor} を
     * {@code #minecraft:bypasses_armor} に入れている。バニラの {@code #bypasses_shield} は {@code #bypasses_armor} を含むので、
     * 初版の順だとその 2 回目が素通りし、正面の盾でもガードでも防具を無視して満額入っていた(溜めにも入らない)。
     * 飛び道具の判定ごと前へ出すのはだめ —— 負傷のスプラッシュポーションは直接の出どころがポーション(飛び道具)の
     * {@code indirect_magic} なので、盾で止まってしまう。
     */
    private static Hit classify(DamageSource source) {
        Entity direct = source.getDirectEntity();
        Entity attacker = source.getEntity();
        // TaCZ の弾は自前で当たって hurt を直に呼ぶ。銃床で殴ったもの(直接の出どころ = 撃った本人)は近接。
        if (isTacz(source) && !source.is(DamageTypeTags.IS_EXPLOSION) && (direct == null || direct != attacker)) {
            return Hit.PROJECTILE;
        }
        if (source.is(DamageTypeTags.BYPASSES_SHIELD)) {
            return Hit.OTHER;
        }
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            return Hit.EXPLOSION;
        }
        // 機関砲台の弾(2026-09-24): 撃った本人も弾のエンティティも居ない即着弾だが、銃弾なので飛び道具として弾く(仕様の「飛び道具は弾く」)。
        // 正面かどうかは撃った点(ModDamageTypes.AutocannonShot)で見る。
        if (source.is(DamageTypeTags.IS_PROJECTILE) || direct instanceof Projectile || source.is(ModDamageTypes.AUTOCANNON)) {
            return Hit.PROJECTILE;
        }
        if (source.is(DamageTypeTags.IS_FIRE) || source.is(DamageTypes.THORNS)) {
            return Hit.OTHER;
        }
        if (direct != null && direct == attacker) {
            return Hit.MELEE;
        }
        return Hit.OTHER;
    }

    private static boolean isTacz(DamageSource source) {
        Entity direct = source.getDirectEntity();
        if (direct != null && TACZ.equals(BuiltInRegistries.ENTITY_TYPE.getKey(direct.getType()).getNamespace())) {
            return true;
        }
        return source.typeHolder().unwrapKey().map(k -> TACZ.equals(k.location().getNamespace())).orElse(false);
    }

    /**
     * 攻撃の出どころ: 飛び道具なら弾が来た位置(1 tick 前。弾のエンティティが無い機関砲台の弾は撃った点)、近接なら殴った本人、
     * 爆発なら爆心。
     */
    @Nullable
    private static Vec3 hitOrigin(DamageSource source, Hit kind) {
        Entity direct = source.getDirectEntity();
        return switch (kind) {
            case PROJECTILE -> direct != null ? direct.position().subtract(direct.getDeltaMovement())
                    : source instanceof ModDamageTypes.AutocannonShot shot ? shot.origin() : source.getSourcePosition();
            case MELEE -> source.getEntity() != null ? source.getEntity().position() : null;
            case EXPLOSION -> source.getSourcePosition();
            case OTHER -> null;
        };
    }

    /** {@code from} が盾の正面 ±60° の内か(原点から見て、水平)。真上・真下は外。 */
    private boolean inShieldArc(@Nullable Vec3 from) {
        if (from == null) {
            return false;
        }
        double dx = from.x - this.getX();
        double dz = from.z - this.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3D) {
            return false;
        }
        Vec3 f = forward();
        return (dx * f.x + dz * f.z) / len >= SHIELD_ARC_COS;
    }

    /** 汚染側どうしの巻き添え({@code MobGroupFriendlyFire} が落とす)。盾で溜めたり鳴らしたりしない。 */
    private boolean isFriendlyFire(DamageSource source) {
        if (source.is(ModTags.SIEGE_BOSS_ATTACK)) {
            return true;
        }
        return source.getEntity() instanceof LivingEntity a && a != this && MobGroup.of(a.getType()) != null;
    }

    /**
     * 盾はここ(hurt の入口)で掛ける。ガード中は正面からの近接・飛び道具・爆発を 100% カットして「来た量」を溜める
     * (防具より前の値。無敵時間の決まりはバニラと同じ({@link #absorb})。上限 {@link #GUARD_STORE_CAP})。
     * 背面・側面は盾が無いので全部通り、溜まらない。炎・状態異常などは盾と関係なく通る。
     * 適応({@code LivingDamageEvent.Pre})はこの後に別で掛かる。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide() || !this.isAlive() || amount <= 0.0F || isFriendlyFire(source)) {
            return super.hurt(source, amount);
        }
        Hit kind = classify(source);
        if (source instanceof ModDamageTypes.AutocannonShot shot) {
            // 盾に当たったかどうかに関係なく覚える(背中を撃たれているときこそ向き直りたい)。
            this.turretFireFrom = shot.origin();
            this.turretFireTick = this.tickCount;
            noteTurretShot(shot.origin());
        }
        if (kind == Hit.PROJECTILE && source.getEntity() instanceof LivingEntity shooter) {
            noteRangedAttacker(shooter);
        }
        if (kind == Hit.OTHER || !inShieldArc(hitOrigin(source, kind))) {
            return super.hurt(source, amount);
        }
        this.lastFrontHitTick = this.tickCount;
        if (isGuarding()) {
            // 無敵時間で落ちた一撃(連打の 2 発目など)は、バニラと同じく何も起きない(音も反撃の標的選びも無し)。
            if (absorb(source, amount) > 0.0F) {
                blockedFeedback(hitOrigin(source, kind), kind == Hit.MELEE ? 0.9F : 1.0F, 0.8F);
                if (source.getEntity() instanceof LivingEntity attacker) {
                    retaliate(attacker);
                }
            }
            return false;
        }
        if (!isShieldEffective()) {
            return super.hurt(source, amount);
        }
        if (kind == Hit.MELEE) {
            blockedFeedback(hitOrigin(source, kind), 0.7F, 0.75F);
            return super.hurt(source, amount * SHIELD_MELEE_FACTOR);
        }
        // 飛び道具(弾が自前で hurt を呼ぶもの)・爆発: 正面は 0。
        blockedFeedback(hitOrigin(source, kind), 1.0F, 1.0F);
        if (source.getEntity() instanceof LivingEntity attacker) {
            retaliate(attacker);
        }
        return false;
    }

    /**
     * 盾で弾く。弾(バニラの飛び道具)が当たる瞬間に呼ばれる(1.21 の {@code Projectile#hitTargetOrDeflectSelf})。
     * 正面から来た弾は跳ね返し({@link ProjectileDeflection#REVERSE})、ダメージは入らない。ガード中は弾きつつ溜める。
     * クライアントでも呼ばれる(矢の当たりの見込み)ので、音や勘定はサーバーだけで。
     *
     * <p>跳ね返さずに当たらせるもの({@link #passesShield}):
     * <ul>
     *     <li><b>スプラッシュ・残留ポーション</b>(2026-09-24、レビューの指摘)。跳ね返すと割れずに投げた側へ戻り、手前の地面で割れるので
     *     効き目が直撃の 1.0 から 0〜0.4 に落ち、近くで投げた本人に掛かっていた。ユーザー指定「盾に効くのは剣による攻撃・状態異常・炎だけ」
     *     の状態異常を掛ける手段そのものなので、盾の面で割れさせる(ダメージの種類は魔法なので {@link #hurt} で素通り)。</li>
     *     <li><b>ウィンドチャージ</b>(バニラのブリーズと同じ)。撃ってから 5 tick は跳ね返せない作りで、跳ね返しを返しても
     *     反転せず当たりもせずに体をすり抜けていた。当たらせれば盾の面ではじけ、ダメージ 1 は飛び道具として {@link #hurt} が弾く。</li>
     * </ul>
     * 火の付いた矢・火の玉・効果付きの矢・シュルカーの弾・ウィザーの頭蓋骨は飛び道具として跳ね返す(炎や効果も付かない。
     * ユーザー指定の「飛び道具は弾く」と「炎・状態異常は効く」のどちらを優先するかは未確認)。
     */
    @Override
    public ProjectileDeflection deflection(Projectile projectile) {
        if (!this.isAlive() || projectile.getOwner() == this || passesShield(projectile)
                || !(isGuarding() || isShieldEffective())
                || !inShieldArc(projectile.position().subtract(projectile.getDeltaMovement()))) {
            return super.deflection(projectile);
        }
        if (!this.level().isClientSide()) {
            onProjectileBlocked(projectile);
        }
        return ProjectileDeflection.REVERSE;
    }

    private void onProjectileBlocked(Projectile projectile) {
        if (projectile.getId() == this.lastDeflectedId && this.tickCount - this.lastDeflectedTick < 10) {
            return;
        }
        this.lastDeflectedId = projectile.getId();
        this.lastDeflectedTick = this.tickCount;
        Entity owner = projectile.getOwner();
        boolean friendly = owner instanceof LivingEntity o && MobGroup.of(o.getType()) != null;
        if (!friendly) {
            this.lastFrontHitTick = this.tickCount;
            if (owner instanceof LivingEntity shooter) {
                noteRangedAttacker(shooter);
                retaliate(shooter);
            }
            if (isGuarding()) {
                absorb(null, projectileDamage(projectile));
            }
        }
        blockedFeedback(projectile.position(), 1.0F, 1.0F);
    }

    /** 盾で跳ね返さずに当たらせる飛び道具か({@link #deflection} の javadoc)。 */
    static boolean passesShield(Projectile projectile) {
        return projectile instanceof ThrownPotion || projectile instanceof AbstractWindCharge;
    }

    /**
     * 弾いた弾が当たっていたら入ったはずの量(ガードの溜めに足す。ユーザー指定「波にはカットした分のダメージが乗る」)。
     * 各弾の {@code onHitEntity} と同じ式(2026-09-24、レビューの指摘で直した。初版は矢を ceil(速さ × 基本ダメージ) だけで数えて
     * 射撃ダメージ増加・会心が入らず(射撃ダメージ増加 V の引き絞りで実際 14〜21 のところ 6)、火の玉の仲間を一律 5 と数えていた):
     * <ul>
     *     <li>矢: 基本ダメージに撃った弓・クロスボウのエンチャント(射撃ダメージ増加など)を足し、ceil(速さ × それ)。
     *     会心の矢は、バニラの乱数の上乗せ(0〜j/2+1)の期待値を足す。</li>
     *     <li>トライデント: 8 に投げたトライデントのエンチャントを足したもの。</li>
     *     <li>小さな火の玉 5・大きな火の玉 6・ウィザーの頭蓋骨 8(撃った本人が居なければ 5)・シュルカーの弾 4・ラマの唾 1。
     *     それ以外の火の玉の仲間(他の MOD のもの)は仮に 5。</li>
     *     <li>当たってもダメージの無いもの(雪玉・卵・ドラゴンの火の玉)は 0。</li>
     * </ul>
     */
    float projectileDamage(Projectile projectile) {
        Entity owner = projectile.getOwner();
        if (projectile instanceof ThrownTrident trident) {
            float damage = 8.0F;
            if (this.level() instanceof ServerLevel server) {
                DamageSource source = this.damageSources().trident(trident, owner != null ? owner : trident);
                damage = EnchantmentHelper.modifyDamage(server, trident.getWeaponItem(), this, source, damage);
            }
            return damage;
        }
        if (projectile instanceof AbstractArrow arrow) {
            double base = arrow.getBaseDamage();
            if (arrow.getWeaponItem() != null && this.level() instanceof ServerLevel server) {
                DamageSource source = this.damageSources().arrow(arrow, owner != null ? owner : arrow);
                base = EnchantmentHelper.modifyDamage(server, arrow.getWeaponItem(), this, source, (float) base);
            }
            int j = Mth.ceil(Mth.clamp(arrow.getDeltaMovement().length() * base, 0.0D, 2.147483647E9D));
            float damage = j;
            if (arrow.isCritArrow()) {
                damage += (j / 2 + 1) * 0.5F;
            }
            return damage;
        }
        if (projectile instanceof SmallFireball) {
            return 5.0F;
        }
        if (projectile instanceof LargeFireball) {
            return 6.0F;
        }
        if (projectile instanceof WitherSkull) {
            return owner instanceof LivingEntity ? 8.0F : 5.0F;
        }
        if (projectile instanceof ShulkerBullet) {
            return 4.0F;
        }
        if (projectile instanceof LlamaSpit) {
            return 1.0F;
        }
        if (projectile instanceof DragonFireball) {
            return 0.0F;
        }
        if (projectile instanceof AbstractHurtingProjectile) {
            return 5.0F;
        }
        return 0.0F;
    }

    private void noteRangedAttacker(LivingEntity shooter) {
        this.lastRangedAttacker = shooter.getUUID();
        this.lastRangedTick = this.tickCount;
    }

    /** 盾で受けた(ダメージが入らなかった)相手にも反撃する(殴られても hurt が通らないと標的を選ぶ Goal が気づかない)。 */
    private void retaliate(LivingEntity attacker) {
        if (attacker != this && attacker != this.getTarget() && MobGroup.of(attacker.getType()) == null) {
            this.setLastHurtByMob(attacker);
        }
    }

    /**
     * 盾に当たった手応え(音と火花)。音はバニラの「盾で攻撃を防いだ音」(ユーザー指定 2026-09-26。それまでは合成した
     * {@code SCUTIMNES_DEFLECT} で、登録と ogg は戻せるように残してある)。高さの揺らぎはバニラ(0.8〜1.2)より狭い 0.9〜1.1 倍に、
     * 呼ぶ側の {@code pitch}(ガード・近接は 0.75〜0.8 で低く重く)を掛ける。
     */
    private void blockedFeedback(@Nullable Vec3 from, float volume, float pitch) {
        playSound(SoundEvents.SHIELD_BLOCK, volume, pitch * (0.9F + this.random.nextFloat() * 0.2F));
        if (this.level() instanceof ServerLevel server) {
            Vec3 f = forward();
            double ahead = px(-(BASH_POINT[2] - ORIGIN_Z_PX)) * 0.6D;
            Vec3 p = this.position().add(f.x * ahead, px(8.0D), f.z * ahead);
            if (from != null && from.distanceToSqr(this.position()) < 64.0D) {
                p = p.add(from.subtract(p).scale(0.3D));
            }
            server.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 6, 0.2D, 0.2D, 0.2D, 0.2D);
        }
    }

    // ------------------------------------------------------------------ 旋回

    /**
     * この tick に向きたい向き(度、Minecraft の yaw)。強さ {@code priority} の大きいものが勝つ
     * (見回し {@link #TURN_LOOK} &lt; 追跡 {@link #TURN_HUNT} &lt; 移動 {@link #TURN_MOVE} &lt; 動作 {@link #TURN_ACTION})。
     * 実際に回すのは tick の終わりの {@link #tickTurn}。
     */
    void wantFacing(float yaw, int priority) {
        if (priority >= this.wantedPriority) {
            this.wantedYaw = yaw;
            this.wantedPriority = priority;
        }
    }

    /**
     * 向きを 1 tick 進める(機関砲台 {@code GunTurretBlockEntity#advanceAim} と同じ作り)。角速度を「行きたい速さ」
     * (残り角度を ±{@link #MAX_TURN_PER_TICK} で切ったもの)へ {@link #TURN_SMOOTHING} ずつ寄せ、残り角度より大きくは進めない
     * (速度の遅れで行き過ぎると震える)。希望が無ければ止まる。回した結果を {@code yRot} = 体 = 頭へ書き戻す ——
     * この tick の間によそが書いた向きはここで消える。
     */
    private void tickTurn() {
        if (Float.isNaN(this.heading)) {
            this.heading = this.getYRot();
        }
        // 動作の最中(ガードを除く)は、動作が自分で出した希望({@link #TURN_ACTION})でしか回らない(2026-09-24、レビューの指摘)。
        // 盾の上げ下げ・バッシュ・尾・叩きつけのアニメは足を踏ん張ったまま(または踏み替えながら)なので、回ると足が滑る。
        // ふだんは追跡の Goal が MOVE / LOOK を握っていて誰も希望を出さないが、水に落ちて岸へ泳ぐ Goal(EscapeWaterGoal、
        // この MOD の全陸生種に優先度 0 で入る)などが割り込むと、その移動・視線の希望で尾の薙ぎや叩きつけの途中に体が回っていた。
        if (this.wantedPriority >= 0 && this.wantedPriority < TURN_ACTION && locksFacing(getAction())) {
            this.wantedPriority = -1;
        }
        float delta = this.wantedPriority >= 0 ? Mth.wrapDegrees(this.wantedYaw - this.heading) : 0.0F;
        float want = Mth.clamp(delta, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
        this.turnSpeed += (want - this.turnSpeed) * TURN_SMOOTHING;
        float step = Math.abs(this.turnSpeed) > Math.abs(delta) ? delta : this.turnSpeed;
        if (Math.abs(this.turnSpeed) < 1.0E-3F && this.wantedPriority < 0) {
            this.turnSpeed = 0.0F;
        }
        this.heading += step;
        this.wantedPriority = -1;
        applyFacing();
        this.entityData.set(DATA_TURN, Math.round(step * 20.0F) / 20.0F);
    }

    /** この動作の間は、動作自身の希望でしか向きを変えないか(何もしていないとき・ガードに入る間・ガードの保持中は変える)。 */
    static boolean locksFacing(int action) {
        return action != ACT_NONE && action != ACT_GUARD_START && action != ACT_GUARD;
    }

    private void applyFacing() {
        this.setYRot(this.heading);
        this.yBodyRot = this.heading;
        this.yHeadRot = this.heading;
    }

    /** 向きをいますぐ変える(湧かせた直後・GameTest 用)。旋回の制限を通らない。 */
    public void snapFacing(float yaw) {
        this.heading = yaw;
        this.turnSpeed = 0.0F;
        this.setYRot(yaw);
        this.yRotO = yaw;
        this.yBodyRot = yaw;
        this.yBodyRotO = yaw;
        this.yHeadRot = yaw;
        this.yHeadRotO = yaw;
    }

    /** 置き直し(湧き・テレポート・コマンド)は向きもそのまま受け入れる。次の tick に yRot から取り直す。 */
    @Override
    public void moveTo(double x, double y, double z, float yRot, float xRot) {
        super.moveTo(x, y, z, yRot, xRot);
        this.heading = Float.NaN;
        this.turnSpeed = 0.0F;
    }

    /** バニラの「その場で相手へ向き直る」(1 tick に最大 {@code maxYRotIncrease} 度)も旋回の制限を通す。 */
    @Override
    public void lookAt(Entity entity, float maxYRotIncrease, float maxXRotIncrease) {
        wantFacing(yawTo(entity), TURN_HUNT);
    }

    /** 体の向いている方向(水平の単位ベクトル)。 */
    Vec3 forward() {
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
    }

    /** 体の左(ファイル +X)。 */
    Vec3 left() {
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(Mth.cos(yaw), 0.0D, Mth.sin(yaw));
    }

    private float yawTo(Entity target) {
        return yawTo(target.position());
    }

    private float yawTo(Vec3 point) {
        return (float) (Mth.atan2(point.z - this.getZ(), point.x - this.getX()) * Mth.RAD_TO_DEG) - 90.0F;
    }

    /** 標的の方向が正面から何度か(0〜180)。 */
    private float angleFromFront(Entity target) {
        return Math.abs(Mth.wrapDegrees(yawTo(target) - this.getYRot()));
    }

    private double horizontalDistanceTo(Entity target) {
        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** ファイル座標の長さ(px、体格 1.0)をいまの体格のブロックへ。 */
    private double px(double pixels) {
        return pixels / 16.0D * this.getScale();
    }

    /** その場から動かない(溜め・上げ下げ・叩きつけの間)。 */
    private void holdStill() {
        this.getNavigation().stop();
        this.setZza(0.0F);
        this.setXxa(0.0F);
    }

    // ------------------------------------------------------------------ 移動

    @Override
    public void travel(Vec3 input) {
        switch (getAction()) {
            case ACT_NONE, ACT_GUARD -> super.travel(input);
            // 踏み込み・ノックバックは速度だけで動く。それ以外はその場に留まる。
            default -> super.travel(Vec3.ZERO);
        }
    }

    /**
     * 押されない(7 ブロックの体を殴って押し込めない)。自分からは周りを押し出す({@code doPush} はバニラのまま)——
     * ファルキムネスは押し合いを両方切ってあるが、この種は体の中に立たれると「正面の盾・背後の弱点」が成り立たないので
     * 体からは押し出す(仮)。
     */
    @Override
    public boolean isPushable() {
        return false;
    }

    /**
     * 尾(原点の約 6.4 ブロック後ろ、体格 1.6)と、叩きつけで振り上げた盾が当たり判定の外へ大きく出るので、描画の打ち切りは広めに。
     * 横は尾の長さ、上は振り上げ(立ち上がった盾)まで。
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        double s = this.getScale();
        return super.getBoundingBoxForCulling().inflate(3.2D * s + 0.5D, 2.5D * s, 3.2D * s + 0.5D);
    }

    @Override
    public int getMaxHeadXRot() {
        return 25;
    }

    /** 倒れるアニメ(1.5 秒)を最後まで見せてから消える。バニラは 20 tick で消す。 */
    @Override
    protected void tickDeath() {
        this.deathTime++;
        if (this.deathTime >= DEATH_TICKS && !this.level().isClientSide() && !this.isRemoved()) {
            this.level().broadcastEntityEvent(this, (byte) 60);
            this.remove(RemovalReason.KILLED);
        }
    }

    // ------------------------------------------------------------------ 追跡と行動の選択

    /**
     * 標的が居るときに毎 tick 呼ぶ(何かの動作中は呼ばない)。{@link HuntGoal} が呼ぶ。GameTest からも直接呼ぶ。
     * 出す順: 背後なら尾 → ガード(特殊攻撃の条件が揃えば)→ 正面ならバッシュ → 追う。
     */
    public void combatTick(LivingEntity target) {
        if (isActing() || target == null || !target.isAlive()) {
            return;
        }
        double d = horizontalDistanceTo(target);
        float angle = angleFromFront(target);
        double dy = target.getY() - this.getY();
        boolean sameLevel = dy > -1.5D && dy < 2.5D * this.getScale();
        boolean onLeft = target.position().subtract(this.position()).dot(left()) > 0.0D;
        boolean settled = shieldSettled();
        // 尾: 背後に回られたら(クールタイム 8 秒)。尾が実際に届く扇の中に居るときだけ(外れる振りはしない)。
        if (settled && this.tailCooldown <= 0 && angle >= TAIL_TRIGGER_ANGLE && sameLevel
                && d <= tailReach() + target.getBbWidth() * 0.5D && inTailArc(target, onLeft)) {
            startTail(onLeft);
            return;
        }
        // ガード: 交戦から時間が経ち通常攻撃を挟み終えていて、正面から攻めてきている(直近に正面から被弾 / 遠距離の標的)とき、ときどき。
        if (settled && this.guardCooldown <= 0 && this.pacing.ready()
                && (this.tickCount - this.lastFrontHitTick <= GUARD_FRONT_HIT_WINDOW || isRangedAttacker(target))
                && this.random.nextInt(GUARD_CHANCE_PER_TICK) == 0) {
            startGuard();
            return;
        }
        // バッシュ: 正面の相手へ、届くぎりぎりより手前で振り始める(溜めの間は踏み込まない)。高さは当たりの判定と同じ範囲だけ。
        double reach = bashReach(target);
        boolean bashHeight = bashReachesHeight(dy);
        if (settled && this.attackCooldown <= 0 && angle <= BASH_TRIGGER_ANGLE && bashHeight && d <= reach * BASH_START_RATIO) {
            startBash(onLeft);
            return;
        }
        // 追う。向きは標的へ(経路を歩いている間は移動の向きが勝つ)。高さで届かない相手(段・根・柱の上)は、
        // 間合いの内でも立ち止まらずに経路を探し直す(回り込めるなら回り込む。無理なら経路の端で止まる)。
        wantFacing(yawTo(target), TURN_HUNT);
        if (d > reach * BASH_IDEAL_RATIO || !bashHeight) {
            if (--this.repathTicks <= 0) {
                this.repathTicks = 8;
                // 前回届かなかったのと同じマス同士なら探索を省く(経路の端で止まったまま。PathRetryMemo)。
                this.pathMemo.moveTo(this, target, 1.0D);
            }
        } else {
            this.getNavigation().stop();
        }
    }

    /**
     * 相手が居る間(または何かの動作中)は常に動いている Goal。動作中は何もしない —— 動作は {@link #tickAction} が
     * 動かしている。MOVE / LOOK を握っているので、溜めやガードの最中にぶらつき・見回しの Goal が割り込まない。
     *
     * <p>JUMP は握らない(2026-09-24、レビューの指摘。ファルキムネスも同じ)。優先度 0 の {@link FloatGoal} は JUMP だけを使うので、
     * 握っていると水に浸かった瞬間(爆発・水流で押された、足元にバケツの水を撒かれた)にこの Goal が止められ、水から出るまで
     * 再開できなかった —— その間は追わず・殴らず・尾も振らず、空いた MOVE / LOOK でぶらつき・見回しの Goal が体を回していた。
     * JUMP を使う Goal はほかに無い(段差の飛び乗りは移動の制御が直に跳ぶので Goal のフラグと関係ない)。
     * なお岸が近ければ、この MOD の全陸生種に入る岸へ泳ぐ Goal({@link EscapeWaterGoal}、優先度 0・MOVE と LOOK)がこの Goal より先に動く
     * (水ぐされで溶ける前に上がる。この MOD の方針)。そのときの移動の希望で動作の途中に体が回らないよう、{@link #tickTurn} で止めてある。
     */
    static final class HuntGoal extends Goal {
        private final ScutimnesEntity mob;

        HuntGoal(ScutimnesEntity mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = mob.getTarget();
            return mob.isActing() || (t != null && t.isAlive());
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
        }

        @Override
        public void tick() {
            if (mob.isActing()) return;
            LivingEntity target = mob.getTarget();
            if (target != null && target.isAlive()) {
                mob.combatTick(target);
            }
        }
    }

    /** 斬ってよい相手か(自分・乗客・汚染側の仲間・クリエイティブ/観戦者は除く。ファルキムネスと同じ線引き)。 */
    private boolean isFoe(LivingEntity e) {
        if (e == this || !e.isAlive() || e.isPassengerOfSameVehicle(this) || e.getVehicle() == this) return false;
        if (e instanceof Player p && (p.isCreative() || p.isSpectator())) return false;
        if (e == this.getTarget()) return true;
        return MobGroup.of(e) == null;
    }

    // ------------------------------------------------------------------ 旋回の制限の付いた操縦

    /**
     * 移動: 行き先の向きは {@link #wantFacing} で希望を出すだけ(バニラは 1 tick に 90° まで直に回す)。
     * 進む速さは向きのずれの cos 倍(ずれが {@link #TANK_MAX_OFF} を超えたら 0 = その場で回る)。横歩き(strafe)はしない。
     * 前進の入力 zza だけを縮める —— バニラの Mob は加速が「速さ × zza」なので、実速度がずれの cos に比例する。
     */
    final class TankMoveControl extends MoveControl {
        TankMoveControl(ScutimnesEntity mob) {
            super(mob);
        }

        @Override
        public void tick() {
            ScutimnesEntity s = ScutimnesEntity.this;
            if (this.operation == Operation.MOVE_TO) {
                this.operation = Operation.WAIT;
                double dx = this.wantedX - s.getX();
                double dz = this.wantedZ - s.getZ();
                double dy = this.wantedY - s.getY();
                if (dx * dx + dy * dy + dz * dz < 2.5000003E-7F) {
                    s.setZza(0.0F);
                    return;
                }
                float want = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
                s.wantFacing(want, TURN_MOVE);
                float off = Math.abs(Mth.wrapDegrees(want - s.getYRot()));
                float speed = (float) (this.speedModifier * s.getAttributeValue(Attributes.MOVEMENT_SPEED));
                s.setSpeed(speed);
                s.setZza(off > TANK_MAX_OFF ? 0.0F : speed * Mth.cos(off * Mth.DEG_TO_RAD));
                s.setXxa(0.0F);
                // 段差の飛び乗り(バニラの MoveControl と同じ条件)。
                BlockPos pos = s.blockPosition();
                BlockState state = s.level().getBlockState(pos);
                VoxelShape shape = state.getCollisionShape(s.level(), pos);
                if (dy > (double) s.maxUpStep() && dx * dx + dz * dz < (double) Math.max(1.0F, s.getBbWidth())
                        || !shape.isEmpty() && s.getY() < shape.max(Direction.Axis.Y) + (double) pos.getY()
                        && !state.is(BlockTags.DOORS) && !state.is(BlockTags.FENCES)) {
                    s.getJumpControl().jump();
                    this.operation = Operation.JUMPING;
                }
            } else if (this.operation == Operation.JUMPING) {
                s.setSpeed((float) (this.speedModifier * s.getAttributeValue(Attributes.MOVEMENT_SPEED)));
                if (s.onGround()) {
                    this.operation = Operation.WAIT;
                }
            } else {
                // 待機。横歩き(strafe)も戦車には無いのでここへ落とす。
                this.operation = Operation.WAIT;
                s.setZza(0.0F);
                s.setXxa(0.0F);
            }
        }
    }

    /**
     * 視線: 頭だけ振り向くことは無い(頭は体と一体)。見たい向きを {@link #TURN_LOOK} の希望として出し、体ごとゆっくり回る
     * (見回し・プレイヤーを見る Goal で、止まったまま向きを変える —— そのとき旋回の足踏みが流れる)。
     */
    final class TankLookControl extends LookControl {
        TankLookControl(ScutimnesEntity mob) {
            super(mob);
        }

        @Override
        public void tick() {
            ScutimnesEntity s = ScutimnesEntity.this;
            if (this.lookAtCooldown > 0) {
                this.lookAtCooldown--;
                this.getYRotD().ifPresent(yaw -> s.wantFacing(yaw, TURN_LOOK));
                this.getXRotD().ifPresent(pitch -> s.setXRot(this.rotateTowards(s.getXRot(),
                        Mth.clamp(pitch, -s.getMaxHeadXRot(), s.getMaxHeadXRot()), this.xMaxRotAngle)));
            } else {
                s.setXRot(this.rotateTowards(s.getXRot(), 0.0F, 5.0F));
            }
            s.yHeadRot = s.getYRot();
        }
    }

    /** 体・頭は常に {@code yRot} に揃える(サーバーでもクライアントでも。クライアントの yRot は同期の補間)。 */
    static final class TankBodyControl extends BodyRotationControl {
        private final ScutimnesEntity mob;

        TankBodyControl(ScutimnesEntity mob) {
            super(mob);
            this.mob = mob;
        }

        @Override
        public void clientTick() {
            this.mob.yBodyRot = this.mob.getYRot();
            this.mob.yHeadRot = this.mob.getYRot();
        }
    }

    // ------------------------------------------------------------------ 見た目

    /**
     * アニメの選び方(クライアント)。動作中は動作のアニメ。何もしていない・ガードの保持中は、体の動きに合わせて歩き系 /
     * 旋回の足踏み / 待機を選び、ループの速さを体の速さに合わせる(盾の状態で上げた系 / {@code *_shield} / {@code guard*})。
     *
     * <h3>歩き・旋回の速さの合わせ方(2026-09-24 に作り直した。レビューの指摘)</h3>
     * <ul>
     *     <li>コントローラは {@link PhaseKeepingAnimationController}(速さを変えても位相が巻き戻らない)。GeckoLib のままだと、止まるたびに
     *     速さを下げた瞬間に脚が逆に歩き(walk で最大 4.7 tick 戻る)、下限 0.4 に張り付いた後半の約 10 tick は止まりかけの体より速く
     *     ループが回って、接地した足が 0.5 ブロックほど後ろへ擦られていた。</li>
     *     <li>速さは {@code limbSwingAmount}({@link MobAnimation})ではなく、<b>その tick に体が前へ進んだ量</b>({@link #clientForwardSpeed})。
     *     描画の体はフレームごとにこの量を割って動くので、ループをこの速さで流せば接地した足は地面に止まる。{@code limbSwingAmount} は
     *     この量の指数平均で 1〜2 tick 遅れるうえ、被弾のたびに 1.5 へ跳ねる({@code LivingEntity#handleDamageEvent})—— その場で回って
     *     いるときに殴られると、約 0.45 秒 walk が流れて空足を踏んでいた。後ろ・横へ押された(ノックバック)ぶんは歩きに数えない。</li>
     *     <li>前へ進みながら回っているときは、足先を多く運ぶほう(前へ進む速さ / 角速度 × {@link #TURN_FOOT_RADIUS_PX})を流す
     *     (経路の曲がり角でずれが 70° を超えてその場で回り始めると、止まりかけの 0.7 秒ぶん walk が勝って足が真っすぐ後ろへ運ばれていた)。</li>
     * </ul>
     * 止まるときの検算(サーバーの摩擦・位置の補間・1 tick 4 フレームを真似た計算): 全速の walk から止まると、walk が外れるのは約 9 tick 後、
     * その間に接地した足が地面に対して動くのは 0.04 ブロック(以前は位相の戻り最大 4.7 tick と、約 0.56 ブロックの擦れ)。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", TRANSITION_TICKS, state -> {
            AnimationController<ScutimnesEntity> c = state.getController();
            c.transitionLength(TRANSITION_TICKS);
            c.setAnimationSpeed(1.0D);
            if (this.isDeadOrDying()) {
                return state.setAndContinue(DEATH);
            }
            int act = getAction();
            int seq = this.entityData.get(DATA_SEQ);
            if (seq != this.clientSeq) {
                this.clientSeq = seq;
                this.clientGuardStepping = false;
                if (act != ACT_NONE) {
                    c.forceAnimationReset();
                }
            }
            Gait gait = clientGait();
            switch (act) {
                case ACT_SHIELD_DOWN: return state.setAndContinue(SHIELD_DOWN);
                case ACT_SHIELD_UP: return state.setAndContinue(SHIELD_UP);
                case ACT_BASH_L: return state.setAndContinue(BASH_L);
                case ACT_BASH_R: return state.setAndContinue(BASH_R);
                case ACT_TAIL_L: return state.setAndContinue(TAIL_L);
                case ACT_TAIL_R: return state.setAndContinue(TAIL_R);
                case ACT_GUARD_START:
                    // guard_start は脚を踏ん張ったまま構えるアニメ。ガードに入る間もユーザー指定で標的へ向き続けるので、回っていれば
                    // ガード姿勢の旋回の足踏みで構えに入る(回る体の下で足が滑らないように。レビューの指摘)。一度足踏みに移ったら
                    // この動作の間は guard_start へ戻らない(戻ると構えを解いて最初から構え直して見える)。
                    if (gait.turning()) {
                        this.clientGuardStepping = true;
                    }
                    if (!this.clientGuardStepping) {
                        return state.setAndContinue(GUARD_START);
                    }
                    return guardLoop(state, gait);
                case ACT_GUARD: return guardLoop(state, gait);
                case ACT_SLAM: return state.setAndContinue(SLAM);
                default:
                    boolean down = isShieldDown();
                    if (gait.walking()) {
                        c.setAnimationSpeed(loopSpeed(gait.ahead(), groundSpeed(down ? WALK_SHIELD_GROUND_SPEED : WALK_GROUND_SPEED)));
                        return state.setAndContinue(down ? WALK_SHIELD : WALK);
                    }
                    if (gait.turning()) {
                        c.setAnimationSpeed(loopSpeed(gait.turn(), down ? TURN_SHIELD_ANIM_DEG_PER_TICK : TURN_ANIM_DEG_PER_TICK));
                        if (down) {
                            return state.setAndContinue(gait.turnLeft() ? TURN_SHIELD_L : TURN_SHIELD);
                        }
                        return state.setAndContinue(gait.turnLeft() ? TURN_L : TURN);
                    }
                    return state.setAndContinue(down ? IDLE_SHIELD : IDLE);
            }
        }));
    }

    /** ガード姿勢のまま歩く / 向きを変える / 構えているループ。 */
    private PlayState guardLoop(AnimationState<ScutimnesEntity> state, Gait gait) {
        AnimationController<ScutimnesEntity> c = state.getController();
        if (gait.walking()) {
            c.setAnimationSpeed(loopSpeed(gait.ahead(), groundSpeed(GUARD_WALK_GROUND_SPEED)));
            return state.setAndContinue(GUARD_WALK);
        }
        if (gait.turning()) {
            c.setAnimationSpeed(loopSpeed(gait.turn(), GUARD_TURN_ANIM_DEG_PER_TICK));
            return state.setAndContinue(gait.turnLeft() ? GUARD_TURN_L : GUARD_TURN);
        }
        return state.setAndContinue(GUARD);
    }

    /**
     * いまの体の動き(クライアント)。
     *
     * @param ahead    この tick に体が前へ進んだ量(ブロック。{@link #clientForwardSpeed})
     * @param turn     直前の tick に回った角度の大きさ(度。{@link #DATA_TURN})
     * @param turnLeft 左回り(yaw が減る向き)か
     * @param walking  歩き系を流すか: 前へ {@link #WALK_MIN_SPEED} 以上進み、かつ回って運ばれる足先より速い
     * @param turning  旋回の足踏みを流すか: 歩いておらず、{@link #TURN_ANIM_MIN} 以上回っている
     */
    private record Gait(double ahead, float turn, boolean turnLeft, boolean walking, boolean turning) {
    }

    private Gait clientGait() {
        float turnRate = getTurnRate();
        float turn = Math.abs(turnRate);
        double ahead = clientForwardSpeed();
        double turnFoot = Math.toRadians(turn) * px(TURN_FOOT_RADIUS_PX);
        boolean walking = ahead >= WALK_MIN_SPEED && ahead >= turnFoot;
        return new Gait(ahead, turn, turnRate < 0.0F, walking, !walking && turn >= TURN_ANIM_MIN);
    }

    /**
     * この tick に体が前へ進んだ量(ブロック、クライアント)。位置の補間で動いた量 {@code getX() − xo} の、いまの向きへの成分
     * (後ろ・横へ押されたぶんは歩きに数えない)。描画の体はフレームごとにこの量を割って動く。
     */
    private double clientForwardSpeed() {
        Vec3 f = forward();
        return Math.max(0.0D, (this.getX() - this.xo) * f.x + (this.getZ() - this.zo) * f.z);
    }

    /**
     * 再生速度 1.0 のときの地上速度(体格 {@link #GROUND_SPEED_MEASURED_SCALE} で測った値)を、いまの体格に直す。
     * 歩幅はモデルの大きさに比例するので、体格を変えても足が滑らない(2026-09-24 に体格 1.6 → 2.88 にしたときに足した)。
     */
    private double groundSpeed(double atMeasuredScale) {
        return atMeasuredScale / GROUND_SPEED_MEASURED_SCALE * this.getScale();
    }

    /** ループを流す速さ = 実際の速さ ÷ 再生速度 1.0 のときの速さ。 */
    private static double loopSpeed(double actual, double atSpeedOne) {
        return Mth.clamp(actual / atSpeedOne, LOOP_SPEED_MIN, LOOP_SPEED_MAX);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    // ------------------------------------------------------------------ 音

    @Override
    protected SoundEvent getAmbientSound() {
        return ModSounds.SCUTIMNES_IDLE.get();
    }

    @Override
    public int getAmbientSoundInterval() {
        return 160;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ModSounds.SCUTIMNES_HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ModSounds.SCUTIMNES_DEATH.get();
    }

    /** 声の高さの個体差は ±5%(ファルキムネスと同じ。バニラの ±20% だと大きな竜の声が小さな生き物に聞こえる)。 */
    @Override
    public float getVoicePitch() {
        return 1.0F + (this.random.nextFloat() - this.random.nextFloat()) * 0.05F;
    }

    /** 足音は重い獣のもの(ファルキムネスと同じラヴェジャー。8 本脚でも種族は竜)。 */
    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.RAVAGER_STEP, 0.55F, 0.85F);
    }
}
