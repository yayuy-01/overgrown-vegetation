package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.adaptation.Adaptation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.legs.FootPlanter;
import com.yayuy.overgrownvegetation.entity.parts.HitPartSet;
import com.yayuy.overgrownvegetation.entity.action.ActionDef;
import com.yayuy.overgrownvegetation.entity.action.ActionPhase;
import com.yayuy.overgrownvegetation.entity.action.ActionPicker;
import com.yayuy.overgrownvegetation.entity.action.ActionTiming;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import com.yayuy.overgrownvegetation.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
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
import java.util.UUID;

/**
 * 「ファルキムネス」(Falcimnes)— <b>グループ5(適応種)</b>の陸の純粋な攻撃役(2026-09-23)。
 * カマキリの姿をした爬虫類。前脚の鎌({@code wepon} / {@code wepon2})で斬る。
 *
 * <p>適応種なので、倒された武器種への耐性が種ごとに付いていく({@link Adaptation}、{@code group_5.json} に所属)。
 *
 * <h2>行動(ユーザー指定)</h2>
 * <ul>
 *     <li><b>横薙ぎ</b>(基本攻撃): 鎌を横に振る。<b>鎌の先が正面中央に来た瞬間</b>({@link #ATTACK_HIT_TICK})に、
 *     前方の扇の中の相手全員へ攻撃力ぶん。左右の鎌を交互に使う。</li>
 *     <li><b>連撃</b>: 鎌を振りかざして溜め(ユーザー指定 0.8 秒 → 前準備のため 1.2 秒)、何度も振る。振りの間隔は {@link #FLURRY_INTERVALS} の順に
 *     <b>だんだん速くなる</b>。振っている間も遅い足で相手へ寄っていく。</li>
 *     <li><b>掴み</b>: 鎌を大きく上に振りかざし、前方へ大きく突進。当たった相手を掴み上げて噛みつき
 *     ({@link #BITE_DAMAGE}、防具貫通)、最後に<b>防具に関係なく即死</b>させる。掴まれている間にこちらへ
 *     {@link #BREAK_FREE_DAMAGE} 以上のダメージを入れれば離す。</li>
 *     <li><b>回避</b>: この種の適応値が {@link #DODGE_RESISTANCE} 以上の攻撃を、大きく後ろへ跳んでかわす
 *     (クールダウン 30 秒)。</li>
 *     <li><b>威嚇</b>: プレイヤーと対峙したとき、まれに立ち上がって咆哮する。</li>
 * </ul>
 *
 * <h2>大技は通常攻撃の合間に</h2>
 * <p>ユーザー指定(2026-09-23)「最初から特殊攻撃を使わず、通常攻撃を使いながら、時々特殊攻撃を織り交ぜる」。
 * 通常攻撃 = 横薙ぎ、特殊攻撃 = 連撃・掴み。出し方は {@link SpecialPacing#standard()}(数値は仮: 交戦から 6 秒かつ横薙ぎ 2 回までは出さない、
 * 大技の後は横薙ぎ 2 回を挟む、揃ったら横薙ぎの場面ごとに 35% で連撃)。掴みは横薙ぎの届かない間合いの技なので、
 * {@link SpecialPacing#ready()} の間だけ {@link #GRAB_CHANCE_PER_TICK} で狙う。威嚇(演出)と回避(適応への反応)は数えない。
 * 数えるのは {@link #startAction}(GameTest の {@link #forceAction} も数える)。間合いの外から撃ち続ける相手には横薙ぎを出せないので、
 * 横薙ぎの間合いの外で追い続けた {@link #OUT_OF_REACH_AS_NORMAL_TICKS} tick も横薙ぎ 1 回として数える(仮)。
 *
 * <h2>時刻はアニメーションと対</h2>
 * <p>アニメーションは {@code tools/falcimnes-anim/FalcAnim.java} の生成物で、鎌の先の到達点を座標で解いてある。
 * ここの {@code *_TICKS} / {@code *_HIT_TICK} と生成器の時刻は<b>必ず揃える</b>(片方だけ変えると
 * 鎌が前に出ていないのに当たる)。tick は {@link #TRANSITION_TICKS}(動作の切り替えの補間)+ アニメの秒 × 20。
 *
 * <h2>掴んだ獲物は「乗せる」</h2>
 * <p>獲物はこの Mob に<b>乗客として乗せて</b>顎の前へ置く({@link #getPassengerAttachmentPoint})。
 * プレイヤーの位置はクライアントが決めるので、毎 tick テレポートさせるとガタつく —— 乗り物なら
 * バニラの同期がそのまま使える。自分から降りられず別の乗り物にも乗れないよう {@link Events#onMount} で止め
 * (ログアウトでは先に離す —— {@link Events#onLogout})、
 * 乗ったまま本体を殴れるよう {@link #canRiderInteract} を開けてある。
 *
 * <h2>回る速さに上限がある</h2>
 * <p>ユーザー指定(2026-09-24)「スクティムネス同様、その場で回る速さに制限を。速さは毎秒 140 度」。作りはスクティムネスと同じ:
 * 向き({@code yRot} = 体 = 頭)は<b>この個体が持つ {@link #heading} だけが正</b>で、毎 tick の終わり({@link #tickTurn})に
 * 「行きたい向き」へ最大 {@link #MAX_TURN_PER_TICK} 度だけ回してから書き戻す。移動・視線・動作は {@link #wantFacing} で希望を出すだけ
 * (バニラの {@code MoveControl}・{@code Mob#lookAt}・{@code BodyRotationControl}・よその {@code setYRot} の直書きは上書きされる)。
 * 進むのは前だけ(速さ × max(0, cos(向きのずれ))、ずれが {@link #TANK_MAX_OFF} 度を超えたらその場で回る)—— 脚のアニメが前への歩きと
 * その場の旋回しか持たないので、横・斜めへ滑らせない。回避も向きを変えずに跳ぶ(相手の居る側から後ろ・左・右を選ぶ)。
 *
 * <h2>アニメは 2 つの層</h2>
 * <p>コントローラ {@code "legs"} が脚・胴・尾({@code legs_*})を、{@code "main"} が首・鎌・翅を流す(2026-09-24 に作り直し。
 * ユーザー指定「スクティムネスと同じように、生物的におかしくない可動、説得力のある動きに」)。脚の層は体の実際の動き(前へ進んだ量・
 * 回った角度)で歩き・旋回・待機を選ぶので、横薙ぎの踏み込み・連撃の寄り・向きの追いかけの最中でも足が地面を滑らない。
 * 回避・掴みの突進・死は、両方の層がその動作の専用のアニメを同時に流す。
 * 3 つ目のコントローラ {@code "voice"} は口パク(下顎だけ、2026-09-24)。待機の唸り・痛みの声を鳴らした間だけ首の層の下顎を上書きする
 * ({@link #voiceState})。どのアニメも口を動かし、下顎は左右とも斜め下へ開く(ユーザー指定 2026-09-24)。
 *
 * <h2>待機の仕草</h2>
 * <p>標的がいなくて立ち止まっているとき、まれに見回し・あくび・翅の身震い・鎌の手入れをする({@link #ACT_IDLE}、{@link #tickIdleFidget})。
 * 標的が現れたら・殴られたらすぐやめ、鎌の手入れで鎌を持ち上げている間なら {@link #ACT_GROOM_END} で下ろしてから戻る。
 */
public class FalcimnesEntity extends Monster implements GeoEntity, ParasitizedMob {

    // ------------------------------------------------------------------ 動作

    public static final int ACT_NONE = 0;
    public static final int ACT_ATTACK_R = 1;
    public static final int ACT_ATTACK_L = 2;
    public static final int ACT_FLURRY_WINDUP = 3;
    public static final int ACT_FLURRY_R = 4;
    public static final int ACT_FLURRY_L = 5;
    public static final int ACT_GRAB_WINDUP = 6;
    public static final int ACT_GRAB_DASH = 7;
    public static final int ACT_GRAB_HOLD = 8;
    public static final int ACT_DODGE = 9;
    public static final int ACT_THREAT = 10;
    /** 空振り・振りほどかれた直後の隙。アニメは grab_end(挟む構えから下ろす)。 */
    public static final int ACT_RECOVER = 11;
    /**
     * 連撃のあと、構えた鎌を下ろす間(アニメ flurry_end、0.6 秒)。構えから待機へ補間(4 tick)で直に戻すと
     * 鎌が地面と首を通り抜けるので、下ろす動きをアニメにしてある。
     */
    public static final int ACT_FLURRY_END = 12;
    /** 威嚇の途中で殴られたときに、振りかざした鎌を下ろす間(アニメ ikaku_end、0.6 秒)。待機へ直に補間すると鎌が地面を通る。 */
    public static final int ACT_THREAT_END = 13;
    /**
     * 左 / 右へ跳ぶ回避(2026-09-24)。回る速さに上限を付けたので、回避は向きを変えずに跳ぶ: 相手が正面 ±{@link #DODGE_BACK_ARC} 度なら
     * 後ろ({@link #ACT_DODGE})、右に居れば左へ、左に居れば右へ。以前は相手へ一瞬で向き直ってから後ろへ跳んでいた。
     */
    public static final int ACT_DODGE_L = 14;
    public static final int ACT_DODGE_R = 15;
    /**
     * 待機の仕草(2026-09-24、ユーザー要望「待機モーション」)。標的がいなくて立ち止まっているとき、まれにその場で流す。
     * 種類は {@link #DATA_IDLE_KIND}({@link #IDLE_LOOK_R} など)。標的が現れたら・殴られたらすぐやめる。
     */
    public static final int ACT_IDLE = 16;
    /** 鎌の手入れの途中でやめるとき、持ち上げた鎌を下ろす間(アニメ groom_end_*、0.4 秒)。待機へ直に補間すると刃が前脚を通る。 */
    public static final int ACT_GROOM_END = 17;
    /** 飛ぶ斬撃(通常技・遠、11-05)。右鎌 / 左鎌。アニメ wave_r / wave_l。 */
    public static final int ACT_WAVE_R = 18;
    public static final int ACT_WAVE_L = 19;
    /** 地走りの光の帯(特殊技、11-05)。刺す(rift_windup)→ 刺したまま待つ(rift_hold、ループ)→ 抜く(rift_recover)。 */
    public static final int ACT_RIFT_WINDUP = 20;
    public static final int ACT_RIFT_HOLD = 21;
    public static final int ACT_RIFT_RECOVER = 22;
    /** 掴みの止め(11-05、即死をやめて特大ダメージ)。アニメ grab_finish。 */
    public static final int ACT_GRAB_FINISH = 23;
    // --- 段 2(11-07 夜): 連撃の締めと小さな通常技(アニメ tools/falcimnes-anim/FalcMoves) ---
    /** 連撃の締め: 両鎌の X 字の交差斬り(ユーザー決定)。続けて {@link #ACT_FLURRY_END}。アニメ flurry_x。 */
    public static final int ACT_FLURRY_X = 24;
    /** 切り上げ(右・左)。アニメ kiriage_r / kiriage_l。 */
    public static final int ACT_KIRIAGE_R = 25;
    public static final int ACT_KIRIAGE_L = 26;
    /** 突き掻き(両鎌)。アニメ tsukikaki。 */
    public static final int ACT_TSUKIKAKI = 27;
    /** 交差斬り(両鎌を左右から内へ。連撃の締めの X 字とは別の技)。アニメ kosa。 */
    public static final int ACT_KOSA = 28;
    /** 踏み込み斬り(右・左)。アニメ fumikomi_r / fumikomi_l。 */
    public static final int ACT_FUMIKOMI_R = 29;
    public static final int ACT_FUMIKOMI_L = 30;
    /** 小突き(右・左)。アニメ kozuki_r / kozuki_l。 */
    public static final int ACT_KOZUKI_R = 31;
    public static final int ACT_KOZUKI_L = 32;

    /** 設計データ({@code actions.json})の種の名前。 */
    static final String ACTIONS_ID = "falcimnes";

    /** 技の時刻の頭の補間(= 下の {@link #TRANSITION_TICKS}。前方参照を避けるため先に置く)。 */
    static final int LEAD = 4;

    // --- 技の時刻(前触れ → 実行 → 隙。アニメ tools/falcimnes-anim と対。tick は TRANSITION_TICKS + アニメの秒 × 20) ---
    /** 横薙ぎ: 前触れ 0.7(ユーザー指定)/ 実行 0.2(0.8 秒で当たる)/ 隙 0.7 秒。アニメ attack_r / attack_l 1.6 秒。 */
    static final ActionTiming SWEEP = ActionTiming.ofSeconds(LEAD, 0.7, 0.2, 0.7);
    /** 飛ぶ斬撃: 前触れ 1.0 / 実行 0.3(1.1 秒で放つ)/ 隙 0.7 秒。アニメ wave_r / wave_l 2.0 秒。 */
    static final ActionTiming WAVE = ActionTiming.ofSeconds(LEAD, 1.0, 0.3, 0.7);
    /** 飛ぶ斬撃を放つ tick(アニメの 1.1 秒)。 */
    static final int WAVE_RELEASE_TICK = LEAD + 22;
    /** これより後は向きを変えない(アニメの 0.85 秒のポーズブレイクから。放つ向きが決まる —— 見てから横へ出る猶予)。 */
    static final int WAVE_TRACK_UNTIL = LEAD + 17;
    /** 地走り: 刺す 0.8 秒(前触れ)。刺さった瞬間(この tick)に光の筋を走らせ、刺したまま待つ。 */
    static final int RIFT_STAB_TICK = LEAD + 16;
    /** 地走り: これより後は向きを変えない(叩きつけ始め、アニメの 0.6 秒)。 */
    static final int RIFT_TRACK_UNTIL = LEAD + 12;
    /** 地走り: 抜く(隙 1.0 秒 = 20 tick。補間 0 でつなぐ)。 */
    static final int RIFT_RECOVER_TICKS = 20;
    /** 鎌を刺した所(体の中心から前へ、ブロック、体格 1.0)。生成器の刺した鎌先(z −49.7 px)。 */
    static final double RIFT_STAB_FORWARD = 49.7D / 16.0D;
    /** 掴みの止め: 1.2 秒(アニメ grab_finish)、0.5 秒で噛む。 */
    static final int FINISH_TICKS = LEAD + 24;
    static final int FINISH_BITE_TICK = LEAD + 10;
    /**
     * 動作を切り替えるときの補間(tick)。GeckoLib はこの間に今の姿勢から新しいアニメの 0 秒目の姿勢へ寄せ、
     * <b>それが終わってからアニメを 0 秒目から流す</b>(4.7.6 の {@code AnimationController#process})。
     * だから画面の上では、どのアニメもサーバーの動作開始より 4 tick 遅れて始まる。アニメの時刻に合わせる
     * tick(当たる瞬間・咆哮・噛みつき・動作の長さ)は、すべて「これ + アニメの秒 × 20」で数える。
     * 連撃の 1 振りだけは補間 0(1 振りが最短 3 tick なので、補間を挟むと振りが消える)。
     * 2026-09-23 のレビューで発覚: それまでは横薙ぎが「振りかぶって震えている最中」(画面の 0.50 秒)に当たっていた。
     */
    static final int TRANSITION_TICKS = LEAD;

    // --- 待機の仕草(アニメは tools/falcimnes-anim が作る。長さ・時刻は FalcAnim の idleLook などと揃える) ---
    /** 見回し(右 = 右鎌の側から / 左)・あくび・翅の身震い・鎌の手入れ(右鎌 / 左鎌)。 */
    public static final int IDLE_LOOK_R = 0, IDLE_LOOK_L = 1, IDLE_GAPE = 2, IDLE_SHAKE = 3, IDLE_GROOM_R = 4, IDLE_GROOM_L = 5;
    /** 仕草の長さ(tick、補間 + アニメの秒 × 20。idle_look 2.6 秒・idle_gape 2.4 秒・idle_shake 1.8 秒・idle_groom 3.4 秒)。 */
    static final int[] IDLE_TICKS = {TRANSITION_TICKS + 52, TRANSITION_TICKS + 52, TRANSITION_TICKS + 48, TRANSITION_TICKS + 36,
            TRANSITION_TICKS + 68, TRANSITION_TICKS + 68};
    /**
     * 鎌の手入れで鎌を持ち上げている間(アニメの tick、中継に着く 0.25 秒 〜 中継へ戻る 3.1 秒)。この間にやめるときは
     * {@link #ACT_GROOM_END} で下ろし、回避は {@code dodge*_hi}。休めと中継の間なら待機・次の動作へ直に移ってよい(検算済み)。
     */
    static final int GROOM_RAISED_FROM = 5, GROOM_RAISED_UNTIL = 62;
    /** 鎌の手入れの中断(アニメ groom_end 0.4 秒)と、そのうち鎌がまだ中継より上にある間(アニメの tick、0.2 秒)。 */
    static final int GROOM_END_TICKS = TRANSITION_TICKS + 8, GROOM_END_RAISED = 4;
    /** 仕草を出すまでに立ち止まっている tick と、その後 1 tick ごとに出す確率の分母(平均 5 + 8 秒に 1 度。仮決め 2026-09-24)。 */
    static final int IDLE_MIN_STILL = 100, IDLE_CHANCE = 160;
    /**
     * クライアントが待機の仕草を見始めたのが、サーバーが始めてからこれより後(tick)なら、仕草は流さずに待機を見せる(見た目だけの仕草なので省いてよい)。
     * GeckoLib は描画されたときしかアニメを進めないので、画面の外・近づいて見え始めたときに始まった仕草は 0 秒目から遅れて流れる。
     * 鎌の手入れが遅れて流れると、サーバーが終えたとき(やめたとき)クライアントはまだ鎌を口元に上げていて、休めの鎌へ直に戻す補間で刃が前脚を通る。
     * 遅れがこの量までなら、どの時刻の組み合わせも当たらないことを検算した(tools/falcimnes-anim の FalcCheck の LAG)。
     */
    static final int FIDGET_LATE_TICKS = 3;
    /**
     * 掴みの終わり(ACT_RECOVER)の最初の間(補間 4 + アニメの 0.25 秒)は回避しない(仮、2026-09-24)。突き出した鎌を引き戻している最中から
     * 振りかざしの回避へ補間すると、鎌が下顎を貫いて地面の下へ潜る(検算の割り込みの掃き出しで出た)。突進・掴みの直後の体勢の崩れ。
     */
    static final int RECOVER_NO_DODGE = TRANSITION_TICKS + 5;

    // --- 横薙ぎ(11-05 作り直し: アニメ attack_r / attack_l は 1.6 秒 = 前触れ 0.7・実行 0.2・隙 0.7、0.80 秒で鎌の先が正面中央) ---
    static final int ATTACK_TICKS = SWEEP.total();
    static final int ATTACK_HIT_TICK = TRANSITION_TICKS + 16;
    /** これより後は向きを変えない(当たる直前 0.15 秒は振り切るだけ。横へ逃げれば外れる)。 */
    static final int ATTACK_TRACK_UNTIL = ATTACK_HIT_TICK - 3;
    /** 横薙ぎが届く前方の距離(ブロック、体の中心から、体格 1.0 のとき)。鎌の先は正面で約 4.5 ブロック先。 */
    static final double SWEEP_REACH = 4.7D;
    /** 判定だけに足すゆとり(ブロック)。鎌の先が相手の体に届いて見えるのに外れる、を無くす。 */
    static final double SWEEP_MARGIN = 0.6D;
    /** 横薙ぎの左右の幅(片側、ブロック、体格 1.0 のとき)。 */
    static final double SWEEP_HALF_WIDTH = 2.4D;
    /**
     * 振りかぶりながら詰める間合い(届く距離に対する割合)。
     * 空振りの原因(ユーザー指摘「標的より手前で振って外れる」)は、届くぎりぎりで振り始めていたこと ——
     * 相手が少し下がるだけで刃が手前を通る。振りかぶりの間にここまで踏み込み、懐で当てる。
     */
    static final double ATTACK_IDEAL_RATIO = 0.6D;
    /** 振りかぶりながら踏み込む速さ(ブロック/tick、体格 1.0 のとき)。 */
    static final double ATTACK_STEP_SPEED = 0.16D;

    // --- 連撃 ---
    // 連撃は横薙ぎを振り始める場面ならどこでも代わりに出せる(距離の条件は横薙ぎと同じ)。2026-09-24 に「4.0 × 体格 以内」の条件を外した ——
    // 横薙ぎを振り始める距離((4.7 × 体格 + 相手の幅の半分)の 85%、体格 1.8 のプレイヤー相手で 7.45)より 0.25 だけ内側だったので、
    // 歩いて寄ると必ずその 0.25 の外で横薙ぎを振り始め、置き換えの 35% を一度も引かなかった(下がって避ける相手には連撃が出ない)。
    // 連撃は振りながら 0.55 倍の足で寄るので、横薙ぎの間合いの端で始めても届く。
    /** 溜め 1.2 秒(ユーザー指定は 0.8 秒 → 「対応できる秒数を」で 2026-09-23 に延長)。 */
    static final int FLURRY_WINDUP_TICKS = TRANSITION_TICKS + 24;
    /**
     * 1 振りごとの間隔(tick)。だんだん速くなる。段 2(11-07 夜)で最短を 3 → 5 に【仮】: 1 振りのアニメ(0.5 秒)を間隔に合わせて速めて流すので、
     * 3 tick では 3.3 倍になり斜めの振りが跳ねて見える。14 振りのあと {@link #ACT_FLURRY_X}(X 字の締め)。
     */
    static final int[] FLURRY_INTERVALS = {12, 11, 10, 9, 8, 8, 7, 7, 6, 6, 6, 5, 5, 5};
    /** 連撃の締め(X 字): 前触れ 0.3(両鎌を開いて伸び上がる)/ 実行 0.25(0.45 秒で当たる)/ 隙 0.25 秒【仮】。アニメ flurry_x 0.8 秒。 */
    static final ActionTiming FLURRY_X = ActionTiming.ofSeconds(LEAD, 0.3, 0.25, 0.25);
    static final int FLURRY_X_HIT = LEAD + 9;

    // --- 小さな通常技(段 2、11-07 夜。時刻はアニメ FalcMoves と対、数値は全部【仮】。ダメージ・間合い・点は actions.json) ---
    /** 切り上げ: 前触れ 1.15(鎌を前脚の外へ出して斜め下後ろへ引き、0.95〜1.15 秒はその形で震える)/ 実行 0.3(1.37 秒で当たる)/ 隙 0.75。アニメ 2.2 秒(11-08 に構えを直して 0.3 秒延ばした)。 */
    static final ActionTiming KIRIAGE = ActionTiming.ofSeconds(LEAD, 1.15, 0.3, 0.75);
    static final int KIRIAGE_HIT = LEAD + 27;
    /** 突き掻き: 前触れ 0.9(0.65〜0.9 秒は胸の前に畳んでぴたりと止まる)/ 実行 0.3(1.05 秒で当たる)/ 隙 0.75。アニメ 1.95 秒(11-08 に構えを直して 0.1 秒延ばした)。 */
    static final ActionTiming TSUKIKAKI = ActionTiming.ofSeconds(LEAD, 0.9, 0.3, 0.75);
    static final int TSUKIKAKI_HIT = LEAD + 21;
    /** 交差斬り: 前触れ 1.0 / 実行 0.3(1.12 秒で当たる)/ 隙 0.9。アニメ 2.2 秒。 */
    static final ActionTiming KOSA = ActionTiming.ofSeconds(LEAD, 1.0, 0.3, 0.9);
    static final int KOSA_HIT = LEAD + 22;
    /** 踏み込み斬り: 前触れ 1.0(0.2〜0.8 秒に横へ踏み出す)/ 実行 0.3(1.1 秒で当たる)/ 隙 0.9。アニメ 2.2 秒。 */
    static final ActionTiming FUMIKOMI = ActionTiming.ofSeconds(LEAD, 1.0, 0.3, 0.9);
    static final int FUMIKOMI_HIT = LEAD + 22;
    static final int FUMIKOMI_STEP_FROM = LEAD + 4, FUMIKOMI_STEP_UNTIL = LEAD + 16;
    /** 小突き: 前触れ 0.3 / 実行 0.2(0.38 秒で当たる)/ 隙 0.45。アニメ 0.95 秒。 */
    static final ActionTiming KOZUKI = ActionTiming.ofSeconds(LEAD, 0.3, 0.2, 0.45);
    static final int KOZUKI_HIT = LEAD + 8;
    /** 小さな技の届く距離・横幅(横薙ぎの何倍か。体格 1.0 の値に bodyScale を掛ける)【仮】。 */
    static final double KIRIAGE_REACH = 1.0D, KIRIAGE_WIDTH = 0.8D, TSUKI_REACH = 1.05D, TSUKI_WIDTH = 0.6D,
            KOSA_REACH = 0.75D, KOSA_WIDTH = 1.4D, FUMI_REACH = 1.0D, FUMI_WIDTH = 1.0D, KOZUKI_REACH = 0.95D, KOZUKI_WIDTH = 0.5D;
    static final double FLURRY_REACH = 4.4D;
    /** 連撃のあと鎌を下ろす間(11-05: アニメ flurry_end 0.8 秒 = 隙 16 tick。1 振りのダメージ倍率・寄る足は actions.json)。 */
    static final int FLURRY_END_TICKS = TRANSITION_TICKS + 16;

    // --- 掴み ---
    /**
     * 突進までの前準備 2.5 秒(ユーザー指定 2026-09-23「スプリントの前に威嚇してため時間を」)。
     * 0.6 秒で立ち上がって {@link #GRAB_ROAR_TICK} に咆哮 → 1.6 秒から鎌を高く振りかざす → 2.5 秒で突進。
     * 咆哮が聞こえたら掴みが来る、という合図になる(単独の威嚇とは長さと最後の構えが違う)。
     */
    static final int GRAB_WINDUP_TICKS = TRANSITION_TICKS + 50;
    static final int GRAB_ROAR_TICK = TRANSITION_TICKS + 12;
    /** これより後は向きを変えない(突進の向きが決まる。横へ逃げる猶予)。 */
    static final int GRAB_TRACK_UNTIL = GRAB_WINDUP_TICKS - 10;
    static final int GRAB_DASH_MAX_TICKS = 22;
    // 突進の速さ・掴みに行く距離・クールダウン・噛みつき・止め・振りほどきは actions.json の falcimnes/grab(11-05。通常技になった。
    // 間隔は 20 → 30 秒、止めは即死でなく 30 ダメージで防具が効く)。
    /**
     * 横薙ぎの間合いの外で、見えている標的を追い続けたこれだけの tick を「横薙ぎを出そうとした 1 回」として大技の勘定に数える
     * (2026-09-24、仮。ユーザー未確認)。数えないと、間合いの外から弓・銃で撃ち続ける相手(歩き 0.22・走り 0.28 ブロック/tick で、
     * この種の足 約 0.16 より速い)には横薙ぎが一度も出せず、{@link SpecialPacing#ready()} が永遠に揃わない —— 掴みも出ないまま
     * 撃たれ続ける。特殊攻撃の出し方を入れる前は、掴みの間合いの相手へ交戦 5 秒目から毎 tick 1/20 で掴みに行っていた。
     * 5 秒ごとなので、引き撃ちの相手への最初の掴みは早くても暖機明けから約 10 秒後。
     */
    static final int OUT_OF_REACH_AS_NORMAL_TICKS = 100;
    /** 噛む時刻(掴んでからの tick)。アニメ grab_hold は 1 秒ループで 0.5 秒に噛む。 */
    static final int BITE_FIRST = TRANSITION_TICKS + 10;
    static final int BITE_INTERVAL = 20;
    /** 掴みの終わり(獲物を放した・殺した・振りほどかれた・外した)に鎌を下ろす間(アニメ grab_end 0.9 秒)。これより短い隙は無い。 */
    static final int GRAB_END_TICKS = TRANSITION_TICKS + 18;
    /** 掴んだ獲物の中心(体の中心から前へ・上へ、ブロック)。生成器の {@code PREY}(0, 30, −50)px と同じ点。 */
    static final double PREY_FORWARD = 50.0D / 16.0D;
    static final double PREY_UP = 30.0D / 16.0D;

    // --- 回避 ---
    /** この適応値以上の攻撃をかわす。 */
    static final double DODGE_RESISTANCE = 0.15D;
    static final int DODGE_COOLDOWN = 600;
    static final int DODGE_TICKS = TRANSITION_TICKS + 12;
    /** 相手がこの角度(正面から、度)の内なら後ろへ跳ぶ。外なら相手と逆の横へ(仮)。 */
    static final float DODGE_BACK_ARC = 60.0F;
    /** 跳ぶ速さ(横・上、ブロック/tick)。着地はアニメ(legs_dodge*)の 0.37 秒 = 補間の 4 tick の後の 7.4 tick 目ごろ。 */
    static final double DODGE_SPEED = 0.85D;
    static final double DODGE_LIFT = 0.42D;

    // --- 倒れる(アニメ death / legs_death は 1.5 秒) ---
    /** 消えるまで(補間 4 tick + 倒れるアニメ 1.5 秒)。バニラの 20 tick だと倒れきる前に消える。 */
    static final int DEATH_TICKS = TRANSITION_TICKS + 30;

    // ------------------------------------------------------------------ 旋回

    /** ユーザー指定(2026-09-24)「その場で回る速さは毎秒 140 度」= 1 tick 7 度。 */
    public static final float MAX_TURN_PER_TICK = 7.0F;
    /**
     * 角速度を「行きたい速さ」へ 1 tick に寄せる割合(スクティムネス・機関砲台と同じ作り)。仮: 0.3(スクティムネスは 0.2)。
     * 軽い捕食者なので回り始め・止まりが早い。
     */
    static final float TURN_SMOOTHING = 0.3F;
    /** 向きのずれがこれ(度)を超えたら進まずにその場で回る(スクティムネスと同じ、仮)。 */
    static final float TANK_MAX_OFF = 70.0F;
    /** 止まっていて 1 tick にこれ(度)以上回っていれば旋回の足踏み(legs_turn 系)を流す。 */
    static final float TURN_ANIM_MIN = 0.35F;
    /** 横薙ぎ・連撃を振り始めてよい、標的の向きのずれ(度)。振りかぶりの間(15 tick)にも最大 105 度回れる。 */
    static final float ATTACK_FACING = 60.0F;
    /** 掴み・威嚇を始めてよい向きのずれ(度)。咆哮は相手へ顔を向けてから。 */
    static final float SPECIAL_FACING = 45.0F;

    /** 向きの希望の強さ。強いものが勝つ(同じ強さなら後から出したもの)。 */
    static final int TURN_LOOK = 0;
    static final int TURN_HUNT = 1;
    static final int TURN_MOVE = 2;
    static final int TURN_ACTION = 3;

    // --- 脚の層のループの速さ(tools/falcimnes-anim の検算の最後の表「再生速度 1.0 の地上速度・旋回」と合わせる) ---
    /** legs_walk を速さ 1.0 で流したときの地上速度(px/tick、体格 1.0)。ブロックへは ÷ 16 × 体格。 */
    static final double WALK_GROUND_SPEED_PX = 0.9677D;
    /** legs_dash(掴みの突進のギャロップ)の地上速度(px/tick、体格 1.0)。 */
    static final double DASH_GROUND_SPEED_PX = 7.2115D;
    /** legs_turn を速さ 1.0 で流したときの角速度(度/tick)。= {@link #MAX_TURN_PER_TICK}。 */
    static final double TURN_ANIM_DEG_PER_TICK = 7.0D;
    /** 旋回で足先が運ばれる半径(px、体格 1.0、原点から)。足の置き場(前 (±22, −11)・後ろ (±20, 12))の原点からの距離の平均。 */
    static final double TURN_FOOT_RADIUS_PX = 24.0D;
    /** これ(ブロック/tick)以上前へ進んだ tick は歩き。 */
    static final double WALK_MIN_SPEED = 0.01D;
    /**
     * ループを流す速さの幅。上は横薙ぎの踏み込み(体格 1.8 で 0.29 ブロック/tick = 歩きの 2.6 倍)まで足が付いていくように 3.0。
     * 下はほぼ止まっているだけ(位相を積み上げるコントローラなので、遅く流しても巻き戻らない)。
     */
    static final double LOOP_SPEED_MIN = 0.05D;
    static final double LOOP_SPEED_MAX = 3.0D;

    // --- 威嚇 ---
    static final int THREAT_TICKS = TRANSITION_TICKS + 60;
    /** 咆哮する時刻(アニメ ikaku の頂点 0.9 秒)。 */
    static final int THREAT_ROAR_TICK = TRANSITION_TICKS + 18;
    /** 威嚇を中断されたとき、鎌を下ろす間(アニメ ikaku_end 0.6 秒)。 */
    static final int THREAT_END_TICKS = TRANSITION_TICKS + 12;
    /** 新しいプレイヤーを狙ったときに威嚇する確率。 */
    static final float THREAT_CHANCE_ON_ENGAGE = 0.3F;
    static final int THREAT_COOLDOWN = 1800;
    /** 威嚇する間合い(体格 1.0 のとき。×getScale())。近すぎると顔が相手に埋まる(体格 1.8 で頭は中心の 6.4 ブロック前)。 */
    static final double THREAT_MIN_RANGE = 4.5D;
    static final double THREAT_MAX_RANGE = 12.0D;

    private static final EntityDataAccessor<Integer> DATA_ACTION =
            SynchedEntityData.defineId(FalcimnesEntity.class, EntityDataSerializers.INT);
    /** 動作を始めるたびに 1 増える。同じアニメを頭から流し直す合図。 */
    private static final EntityDataAccessor<Integer> DATA_SEQ =
            SynchedEntityData.defineId(FalcimnesEntity.class, EntityDataSerializers.INT);
    /** 連撃の今の 1 振りの長さ(tick)。クライアントはこれでアニメの速さを決める。 */
    private static final EntityDataAccessor<Integer> DATA_SLASH_TICKS =
            SynchedEntityData.defineId(FalcimnesEntity.class, EntityDataSerializers.INT);

    /**
     * 直前の tick に回った角度(度、+ が右回り = yaw が増える向き、0.05 刻み)。旋回の足踏みを流すか・どの速さで流すかに使う
     * (クライアントの yRot は同期の補間なので、回った量をそのまま取ると止まったり動いたりに見える。スクティムネスと同じ)。
     */
    private static final EntityDataAccessor<Float> DATA_TURN =
            SynchedEntityData.defineId(FalcimnesEntity.class, EntityDataSerializers.FLOAT);
    /** 回避を始めたとき鎌を振りかざしていたか({@link #armsRaised})。首の層の回避のアニメを {@code dodge*_hi} にする。 */
    private static final EntityDataAccessor<Boolean> DATA_DODGE_HI =
            SynchedEntityData.defineId(FalcimnesEntity.class, EntityDataSerializers.BOOLEAN);
    /** 待機の仕草の種類({@link #ACT_IDLE} / {@link #ACT_GROOM_END} の間だけ意味がある)。 */
    private static final EntityDataAccessor<Integer> DATA_IDLE_KIND =
            SynchedEntityData.defineId(FalcimnesEntity.class, EntityDataSerializers.INT);
    /** 動作を始めたときのワールドの時刻(下位 32 ビット)。クライアントが待機の仕草を見始めたのが遅れたかを測る({@link #FIDGET_LATE_TICKS})。 */
    private static final EntityDataAccessor<Integer> DATA_ACTION_TIME =
            SynchedEntityData.defineId(FalcimnesEntity.class, EntityDataSerializers.INT);
    /** 森の中を動く型 ② の段 2(11-08): 体の曲げ。首 4 つ・腹 4 つの骨の向き(体に対する度、1 バイトずつ)。{@link FalcimnesBend}。 */
    private static final EntityDataAccessor<Long> DATA_BEND =
            SynchedEntityData.defineId(FalcimnesEntity.class, EntityDataSerializers.LONG);
    /**
     * 11-08 夜: 胴の地形の傾き(前後・左右、0.25 度刻み 1 バイトずつ)と高さ(実体の y からの差、1/256 ブロック、2 バイト)。
     * サーバーも描画と同じ足の置き方({@link FootPlanter})を 1 つ動かして求め、叩ける部位をこれで傾ける(坂で頭が下がっても部位が付いていく)。
     */
    /** 段 3(11-08 夜): 狭い所の構えの効き(1 バイト)と首を下げる構えの効き(1 バイト)。 */
    private static final EntityDataAccessor<Integer> DATA_SHAPE =
            SynchedEntityData.defineId(FalcimnesEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_TERRAIN =
            SynchedEntityData.defineId(FalcimnesEntity.class, EntityDataSerializers.INT);
    /** 個体の性格(段 3、{@link FalcimnesPersonality})。慎重さ・攻撃性・回り込み(0〜1)。構えの見た目にも使うのでクライアントへ送る。 */
    private static final EntityDataAccessor<Float> DATA_CAUTION =
            SynchedEntityData.defineId(FalcimnesEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_AGGRESSION =
            SynchedEntityData.defineId(FalcimnesEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_FLANK =
            SynchedEntityData.defineId(FalcimnesEntity.class, EntityDataSerializers.FLOAT);

    // --- 脚・胴・尾の層(コントローラ "legs") ---
    private static final RawAnimation LEGS_IDLE = RawAnimation.begin().thenLoop("legs_idle");
    private static final RawAnimation LEGS_WALK = RawAnimation.begin().thenLoop("legs_walk");
    /** その場の旋回(右回り = yaw が増える向き)。 */
    private static final RawAnimation LEGS_TURN = RawAnimation.begin().thenLoop("legs_turn");
    private static final RawAnimation LEGS_TURN_L = RawAnimation.begin().thenLoop("legs_turn_l");
    /** 掴みの突進(ギャロップ)。 */
    private static final RawAnimation LEGS_DASH = RawAnimation.begin().thenLoop("legs_dash");
    private static final RawAnimation LEGS_DODGE = RawAnimation.begin().thenPlayAndHold("legs_dodge");
    private static final RawAnimation LEGS_DODGE_L = RawAnimation.begin().thenPlayAndHold("legs_dodge_l");
    private static final RawAnimation LEGS_DODGE_R = RawAnimation.begin().thenPlayAndHold("legs_dodge_r");
    private static final RawAnimation LEGS_DEATH = RawAnimation.begin().thenPlayAndHold("legs_death");

    // --- 首・鎌・翅の層(コントローラ "main") ---
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    /** その場で回るとき、回る向きへ首と頭が先に向く(右回り / 左回り)。 */
    private static final RawAnimation TURN = RawAnimation.begin().thenLoop("turn");
    private static final RawAnimation TURN_L = RawAnimation.begin().thenLoop("turn_l");
    private static final RawAnimation DODGE_L = RawAnimation.begin().thenPlayAndHold("dodge_l");
    private static final RawAnimation DODGE_R = RawAnimation.begin().thenPlayAndHold("dodge_r");
    /** 鎌を振りかざしていたときの回避(0 秒目の鎌が前の低い構え)。 */
    private static final RawAnimation DODGE_HI = RawAnimation.begin().thenPlayAndHold("dodge_hi");
    private static final RawAnimation DODGE_L_HI = RawAnimation.begin().thenPlayAndHold("dodge_l_hi");
    private static final RawAnimation DODGE_R_HI = RawAnimation.begin().thenPlayAndHold("dodge_r_hi");
    private static final RawAnimation DEATH = RawAnimation.begin().thenPlayAndHold("death");
    private static final RawAnimation ATTACK_R = RawAnimation.begin().thenPlay("attack_r");
    private static final RawAnimation ATTACK_L = RawAnimation.begin().thenPlay("attack_l");
    private static final RawAnimation FLURRY_WINDUP = RawAnimation.begin().thenPlayAndHold("flurry_windup");
    private static final RawAnimation FLURRY_R = RawAnimation.begin().thenPlayAndHold("flurry_r");
    private static final RawAnimation FLURRY_L = RawAnimation.begin().thenPlayAndHold("flurry_l");
    private static final RawAnimation GRAB_WINDUP = RawAnimation.begin().thenPlayAndHold("grab_windup");
    private static final RawAnimation GRAB_DASH = RawAnimation.begin().thenLoop("grab_dash");
    private static final RawAnimation GRAB_HOLD = RawAnimation.begin().thenLoop("grab_hold");
    private static final RawAnimation FLURRY_END = RawAnimation.begin().thenPlayAndHold("flurry_end");
    private static final RawAnimation GRAB_END = RawAnimation.begin().thenPlayAndHold("grab_end");
    private static final RawAnimation THREAT_END = RawAnimation.begin().thenPlayAndHold("ikaku_end");
    private static final RawAnimation DODGE = RawAnimation.begin().thenPlayAndHold("dodge");
    private static final RawAnimation THREAT = RawAnimation.begin().thenPlay("ikaku");
    private static final RawAnimation WAVE_R_ANIM = RawAnimation.begin().thenPlayAndHold("wave_r");
    private static final RawAnimation WAVE_L_ANIM = RawAnimation.begin().thenPlayAndHold("wave_l");
    private static final RawAnimation RIFT_WINDUP_ANIM = RawAnimation.begin().thenPlayAndHold("rift_windup");
    // 段 2(11-07 夜)
    private static final RawAnimation FLURRY_X_ANIM = RawAnimation.begin().thenPlayAndHold("flurry_x");
    private static final RawAnimation KIRIAGE_R_ANIM = RawAnimation.begin().thenPlayAndHold("kiriage_r");
    private static final RawAnimation KIRIAGE_L_ANIM = RawAnimation.begin().thenPlayAndHold("kiriage_l");
    private static final RawAnimation TSUKIKAKI_ANIM = RawAnimation.begin().thenPlayAndHold("tsukikaki");
    private static final RawAnimation KOSA_ANIM = RawAnimation.begin().thenPlayAndHold("kosa");
    private static final RawAnimation FUMIKOMI_R_ANIM = RawAnimation.begin().thenPlayAndHold("fumikomi_r");
    private static final RawAnimation FUMIKOMI_L_ANIM = RawAnimation.begin().thenPlayAndHold("fumikomi_l");
    private static final RawAnimation KOZUKI_R_ANIM = RawAnimation.begin().thenPlayAndHold("kozuki_r");
    private static final RawAnimation KOZUKI_L_ANIM = RawAnimation.begin().thenPlayAndHold("kozuki_l");
    private static final RawAnimation RIFT_HOLD_ANIM = RawAnimation.begin().thenLoop("rift_hold");
    private static final RawAnimation RIFT_RECOVER_ANIM = RawAnimation.begin().thenPlayAndHold("rift_recover");
    private static final RawAnimation GRAB_FINISH_ANIM = RawAnimation.begin().thenPlayAndHold("grab_finish");
    /** 待機の仕草({@link #IDLE_LOOK_R} などの順)。 */
    private static final RawAnimation[] IDLE_ANIMS = {
            RawAnimation.begin().thenPlayAndHold("idle_look_r"), RawAnimation.begin().thenPlayAndHold("idle_look_l"),
            RawAnimation.begin().thenPlayAndHold("idle_gape"), RawAnimation.begin().thenPlayAndHold("idle_shake"),
            RawAnimation.begin().thenPlayAndHold("idle_groom_r"), RawAnimation.begin().thenPlayAndHold("idle_groom_l")};
    private static final RawAnimation GROOM_END_R = RawAnimation.begin().thenPlayAndHold("groom_end_r");
    private static final RawAnimation GROOM_END_L = RawAnimation.begin().thenPlayAndHold("groom_end_l");

    // --- 口パクの層(コントローラ "voice"。下顎だけ。鳴き声を鳴らしている間だけ首の層の下顎を上書きする) ---
    private static final RawAnimation VOICE_GROWL = RawAnimation.begin().thenPlayAndHold("voice_growl");
    private static final RawAnimation VOICE_HURT = RawAnimation.begin().thenPlayAndHold("voice_hurt");
    /** 待機の唸り(サーバーが鳴らした瞬間をクライアントへ知らせるエンティティイベント)。 */
    private static final byte EVENT_GROWL = 101;
    /** 口パクの長さ(tick、補間 2 + アニメの秒 × 20。voice_growl 2.4 秒・voice_hurt 0.7 秒)。 */
    private static final int VOICE_TRANSITION = 2, VOICE_GROWL_TICKS = VOICE_TRANSITION + 48, VOICE_HURT_TICKS = VOICE_TRANSITION + 14;
    /** 口パクを始める知らせから、コントローラが初めて見るまでに許す遅れ(tick)。これより遅れたら流さない。 */
    private static final int VOICE_LATE_TICKS = 3;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    // --- サーバー側の状態(保存しない。読み込み直したら何もしていない状態から) ---
    private int actionTicks;
    /** 通常技の選び方とクールダウン(層 2 の共通部品、11-05)。保存しない。 */
    private final ActionPicker picker = new ActionPicker();
    /** 地走りの光の帯: 刺したまま待つ長さ(tick、光の筋が帯を立てて消えるまで)。 */
    private int riftHoldTicks;
    /** 掴んでからの噛みつきの回数。 */
    private int bites;
    private int dodgeCooldown;
    private int threatCooldown;
    private int recoverTicks;
    private boolean nextSlashLeft;
    private int flurryIndex;
    private Vec3 dashDir = Vec3.ZERO;
    private float holdDamage;
    /** 自分で獲物を離すときだけ立てる({@link Events#onMount} が降車を止めないように)。 */
    private boolean releasing;
    @Nullable
    private UUID lastEngaged;
    /** 大技(連撃・掴み)を通常攻撃(横薙ぎ)の合間に出すための勘定。保存しない。 */
    private final SpecialPacing pacing = DesignData.actions().newPacing(ACTIONS_ID);
    /** 横薙ぎの間合いの外で標的を追い続けている tick({@link #OUT_OF_REACH_AS_NORMAL_TICKS} ごとに通常攻撃 1 回ぶんとして数える)。 */
    private int outOfReachTicks;

    // --- 段 3(11-08): 個体の性格と連続技。性格は保存する(OvPersonality)、ほかは保存しない ---
    /** 性格を決めたか(保存から読んだ・setPersonality)。決めていなければ最初の tick に U 字の分布で振る。 */
    private boolean personalitySet;
    /** 連続技の何段目か(0 = 1 段目・普通の技、1 = 2 段目。2 段目からは続けない —— 最大 2 段は決定)。 */
    private int comboStep;
    /** 小さな技の当たりの結果({@link FalcimnesPersonality#RESULT_HIT} など)。2 段目を選ぶ鍵。 */
    private int comboResult;
    /** GameTest 用: 連続技に続ける確率を上書きする(負なら設計データと性格から)。 */
    private double comboChanceOverride = -1.0D;
    /** 慎重な個体: 隙が無いまま待った tick。性格で決まる長さで待ちきれず攻めに行く。 */
    private int patience;
    /** 慎重な個体: 攻めに行く残りの tick(隙を見つけた・待ちきれなかった)。 */
    private int commitTicks;
    /** 相手が 1 tick 前に盾を構えていたか(盾を下ろした隙)。 */
    private boolean targetWasBlocking;
    /** この tick に後ずさりする速さの割合(HuntGoal が毎 tick 立て、移動の操縦が使って消す)。 */
    private float backpedal;

    // --- 向き(サーバーが正。クライアントは同期された yRot に体を揃えるだけ) ---
    /** いまの向き(度)。NaN なら次の tick に yRot から取り直す(湧いた直後・テレポートの後)。 */
    private float heading = Float.NaN;
    /** いまの角速度(度/tick)。 */
    private float turnSpeed;
    private float wantedYaw;
    private int wantedPriority = -1;

    /** 待機の仕草を出すまでの、立ち止まって落ち着いている tick(サーバー)。 */
    private int stillTicks;
    /** 待機の唸りの口パクが終わる tick(サーバー)。それまでは待機の仕草を出さない(仕草が始まると口パクが切れ、唸り声だけが残る)。 */
    private int growlQuietUntil;

    /** クライアント: 最後に見た {@link #DATA_SEQ}(コントローラごと)。 */
    private int clientSeq = -1;
    private int clientLegsSeq = -1;

    // --- 地形に合わせた足の置き方(2026-11-07、クライアントの描画だけ。FootPlanter・client/FalcimnesLegRig)---
    /**
     * 脚の基準の置き場(ブロック、体格 1。左前・左後・右前・右後)。生成器の待機の足の置き場(FalcBody.NEUTRAL_F = (22, −11)・
     * NEUTRAL_H = (20, 12) px、焼き込む前)× 焼き込み 1.8 ÷ 16。手付けの待機と同じ所に置くので、平らな地面では手付けとほぼ同じ形。
     */
    public static final double[] LEG_HOME_LEFT = {22 * 1.8D / 16, 20 * 1.8D / 16, -22 * 1.8D / 16, -20 * 1.8D / 16};
    public static final double[] LEG_HOME_FWD = {11 * 1.8D / 16, -12 * 1.8D / 16, 11 * 1.8D / 16, -12 * 1.8D / 16};
    /** 対角の組み: 左前 + 右後 = 0、左後 + 右前 = 1。 */
    private static final int[] LEG_GROUP = {0, 1, 1, 0};
    /** 確かめ用の名前(keyed / feet)を効かせるか。Claude のテストクライアント(-Dov.devbridge=true)だけ。 */
    private static final boolean DEV_HOOKS = Boolean.getBoolean("ov.devbridge");
    @Nullable
    private FootPlanter footPlanter;
    /** 段 3: 性格の構えの鎌・首の先の効き(0〜1、技の間は 0 へ)と、前のフレームの時刻(クライアントの描画だけが使う)。 */
    public float postureFade = 1.0F, postureClock;
    /** 描画の IK の前のフレームの解(解き継ぐ)。 */
    private final double[][] legSolution = new double[4][];
    private int legSolvedTick = -100, legsRenderedTick = -100;
    /** クライアント: いま手続きの足を使っているか(使っている間、脚の層は待機を流す。胴・尾の揺れだけ借りる)。 */
    private boolean clientProceduralLegs;
    /** クライアント: いま流している口パク(null なら無し)・終わる tick・流し直しの合図と、コントローラが最後に見た合図。 */
    @Nullable
    private RawAnimation clientVoice;
    private int clientVoiceEnd;
    private int clientVoiceSeq;
    private int clientVoiceSeen;
    private int clientVoiceStart;
    /** クライアント: 今の待機の仕草(と、それに続く手入れの中断)を流しているか。遅れて見え始めた仕草は流さない({@link #FIDGET_LATE_TICKS})。 */
    private boolean clientFidgetShown;

    public FalcimnesEntity(EntityType<? extends FalcimnesEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("falcimnes", 40);
        this.moveControl = new TankMoveControl(this);
        this.lookControl = new TankLookControl(this);
        this.setPathfindingMalus(PathType.WATER, -1.0F);
        this.setPathfindingMalus(PathType.LEAVES, -1.0F);
        this.getNavigation().setCanFloat(false);
        // 森の中を動く型 ② の段 1(11-08): 叩ける部位。id は本体の続き番号にする(エンダードラゴンと同じ。クライアントの部位と揃える)
        FalcimnesPartTrack track = FalcimnesPartTrack.get();
        this.hitParts = new HitPartSet(this, track.names, track.widths, track.heights);
        this.partNow = new double[track.count() * 3];
        this.partFrom = new double[track.count() * 3];
        this.partTarget = new double[track.count() * 3];
        this.partBent = new double[track.count() * 3];
        this.partPosed = new double[track.count() * 3];
        this.setId(ENTITY_COUNTER.getAndAdd(this.hitParts.count() + 1) + 1);
    }

    // ------------------------------------------------------------------ 細い芯と叩ける部位(11-08、森の中を動く型 ② の段 1)

    /*
     * 叩ける部位(9 個: 頭・首 2・鎌の腕〜前腕 / 刃 × 左右・腹 2。11-08 夜にユーザー「細かすぎる、もう少し大雑把に」で 17 個からまとめた)。置き場はアニメから作った軌道表
     * ({@link FalcimnesPartTrack}、生成器 tools/falcimnes-anim/PartTrack.java)を、同期された動作・動作の始まった時刻・連撃の速さ・
     * 仕草の種類から、描画の mainState と同じアニメ・同じ時刻で引く(サーバーとクライアントで同じ置き場になる)。
     * 地形とぶつかる本体の箱は胴の芯(幅 1.3 × 高さ 2.1【仮】)だけで、そこから前後に長く伸びた首・頭・鎌・腹をこれで殴れるようにする。
     * 大きさ・骨の上の点は全部【仮】(Claude)。首はユーザーの決定(頭・鎌・腹)に無いが、頭と胴の間の長い首を殴れないと
     * 「見えている所を殴れる」にならないので足した。脚は殴れない(細く、芯の左右 2.6 ブロックまで広がる)。
     * 付いていかないもの: 性格の構え(数 px)・脚の手続きの胴の高さと坂の傾き(クライアントの描画だけが知っている)・歩きの揺れ(待機の形で代える)。
     */
    /**
     * 目の高さ(体格 1 のブロック)。芯を低くしても、視線・見回し・飛び道具の狙いは前と同じ頭の高さから
     * (前は箱の高さ 3.06 の 0.85 倍 = 2.6。頭は待機で 2.3〜3.0)。窒息は芯の中で見る({@link #isInWall})。
     */
    public static final float EYE_HEIGHT = 2.6F;

    /** 叩ける部位(サーバーとクライアントの両方。クライアントは照準の当たりに使う)。 */
    private final HitPartSet hitParts;
    /** 部位の中心(体格 1 のブロック、[前, 左, 上] × 部位)。今・動作が替わった時の・表の値。 */
    private final double[] partNow, partFrom, partTarget, partBent, partPosed;
    /** 段 2: 体の曲げ(首は先の経路に、腹は通った跡に沿う)。 */
    private final FalcimnesBend bend = new FalcimnesBend();

    public FalcimnesBend bend() {
        return this.bend;
    }

    // --- 坂・段での胴の傾き(11-08 夜、ユーザー「坂で下がる頭の部位も付いていかせる」) ---

    /** サーバーの足の置き方(描画と同じ部品。叩ける部位の傾きを求めるためだけに使う)。 */
    @Nullable
    private FootPlanter serverPlanter;
    /** 計測用(サーバー)。 */
    public static volatile long terrainNanos, terrainCalls;

    /**
     * サーバー: 描画と同じ足の置き方を 1 つ動かし、胴の傾き・高さを送る。手付けの脚になる間(回避・突進・死・水の中・乗り物)は
     * 描画と同じく効きを 0 へ下ろす。描画は 40 ブロックより遠いと手付けの脚(傾かない)になるが、サーバーは見ている人を知らないので常に傾ける。
     */
    /** 足の置き方を計算する、プレイヤーからの距離(ブロック)【仮 48 = 矢が届くくらい】。11-08 夜、ユーザー「近くにプレイヤーがいる個体だけ計算する」。 */
    static final double TERRAIN_RANGE = 48.0D;
    /** 送る傾きが目標へ寄る割合(1 tick あたり)【仮】。範囲に出入りしたとき部位が跳ねないように。 */
    static final double TERRAIN_EASE = 0.2D;
    /** 計測用: 範囲を見ずに常に計算する(GameTest で直す前と比べる)。 */
    public static boolean terrainAlways;
    /** GameTest 用: この個体だけ範囲を見ずに計算する(テストのサーバーにはプレイヤーがいない)。 */
    public boolean terrainForced;
    /** 送っている傾き {前後 度, 左右 度, 高さ}(なめらかに寄せる)。 */
    private final double[] terrainOut = new double[3];
    private int terrainSteady;

    // --- 段 3(11-08 夜): 狭い所で前を起こして細くなる構え・低い葉の下で首を下げる構え ---

    /**
     * 狭い所の構えに入る隙間の幅(左右の壁の間、ブロック、体格 1)・抜ける幅【仮】。幹と幹の間 2 マスの真ん中で 2.0。
     * 片側だけに幹があるときは、反対側が {@link #GAP_LOOK} まで開いているので入らない。
     */
    static final double SQUEEZE_IN_WIDTH = 2.5D, SQUEEZE_OUT_WIDTH = 3.5D;
    /** 入ってから抜けるまでの最低の tick・抜けてから入り直すまでの最低の tick【仮】(ぱたぱた切り替わらないように)。 */
    static final int SQUEEZE_MIN_HOLD = 30, SQUEEZE_MIN_GAP = 20;
    /** 左右の壁を探す距離(ブロック、体格 1)【仮】。 */
    static final double GAP_LOOK = 3.0D;
    /** 狭い所の構えに入っているか・今の状態に入ってからの tick(サーバー)。 */
    private boolean squeezeOn;
    private int squeezeHold = SQUEEZE_MIN_GAP;

    /**
     * 芯の今の位置・1.5・3 ブロック前で、体の左右それぞれ一番近い壁(足元から 1 段上に当たりのあるブロック。バリアを除く)までの距離を測り、
     * 左 + 右の一番狭い所を返す(壁が無ければ {@link #GAP_LOOK} とする)。
     */
    private double narrowestGap(double fx, double fz, double s) {
        // 左 = (cos yaw, sin yaw)。前 (−sin, cos) から: cos = fz、sin = −fx
        double lx = fz;
        double lz = -fx;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        double y = this.getY() + 1.0D * s + 0.5D;
        double best = Double.MAX_VALUE;
        for (double ahead : new double[]{0.0D, 1.5D, 3.0D}) {
            double cx = this.getX() + fx * ahead * s, cz = this.getZ() + fz * ahead * s;
            double w = 0.0D;
            for (int side = -1; side <= 1; side += 2) {
                double hit = GAP_LOOK * s;
                for (double dd = 0.5D; dd <= GAP_LOOK + 1e-6; dd += 0.5D) {
                    p.set(cx + lx * side * dd * s, y, cz + lz * side * dd * s);
                    BlockState st = this.level().getBlockState(p);
                    if (!st.isAir() && !st.is(net.minecraft.world.level.block.Blocks.BARRIER) && !st.getCollisionShape(this.level(), p).isEmpty()) {
                        hit = dd * s;
                        break;
                    }
                }
                w += hit;
            }
            best = Math.min(best, w);
        }
        return best;
    }
    /** 構えの効きが変わる速さ(1 tick あたり)【仮】。0.6 秒・0.5 秒で全部。 */
    static final float SQUEEZE_RATE = 0.08F, DUCK_RATE = 0.1F;
    /** 狭い所の構えの脚: 横の幅の倍率と歩幅の倍率(効き 1 のとき)【仮】。 */
    static final double SQUEEZE_LATERAL = 0.6D, SQUEEZE_STRIDE = 0.65D;

    /** 構えの効き(両側。サーバーが求めて送り、クライアントは受けた値。前の tick の値は描画の補間用)。 */
    private float squeezeNow, squeezePrev, duckNow, duckPrev;
    /** サーバー: 送る前の効き(なめらかに寄せる)。 */
    private float squeezeServer, duckServer;

    public float squeeze(float pt) {
        return Mth.lerp(pt, this.squeezePrev, this.squeezeNow);
    }

    public float duck(float pt) {
        return Mth.lerp(pt, this.duckPrev, this.duckNow);
    }

    /** 足の置き方の歩幅の倍率(狭い所の構えで小股)。 */
    double squeezeStride() {
        return Mth.lerp(this.squeezeNow, 1.0D, SQUEEZE_STRIDE);
    }

    /** 足の置き方の横の幅の倍率(狭い所の構えで脚を寄せる)。 */
    double squeezeLateral() {
        return Mth.lerp(this.squeezeNow, 1.0D, SQUEEZE_LATERAL);
    }

    /**
     * サーバー: 森の地図で芯の前後(今の位置・1.5・3 ブロック前)の一番近い幹までの距離を見て狭い所の構えを、頭の上(前 3.5〜5.5 の足元から
     * 2 段上)に当たりのあるブロック(葉・枝・幹)があれば首を下げる構えを、なめらかに寄せて送る。技の間・死んだ後は 0 へ戻す。
     * クライアント: 受けた値を進める。
     */
    private void tickShape() {
        this.squeezePrev = this.squeezeNow;
        this.duckPrev = this.duckNow;
        if (!(this.level() instanceof ServerLevel server)) {
            int v = this.entityData.get(DATA_SHAPE);
            this.squeezeNow = (v & 0xFF) / 255.0F;
            this.duckNow = ((v >> 8) & 0xFF) / 255.0F;
            return;
        }
        float qT = 0.0F, dT = 0.0F;
        // 近くにプレイヤーがいる個体だけ(見えない所では構えを変えない。重さのため。足の置き方と同じ範囲)
        if (getAction() == ACT_NONE && !this.isDeadOrDying() && !this.isPassenger() && (this.terrainForced || playerWithin(TERRAIN_RANGE))) {
            double s = this.getScale();
            float yaw = this.getYRot() * Mth.DEG_TO_RAD;
            double fx = -Mth.sin(yaw), fz = Mth.cos(yaw);
            // 11-08 夜の直し: 「一番近い幹までの距離」だけで決めると、片側に幹が 1 本あるだけで構えに入り、すぐ戻るのを繰り返した
            // (ユーザー「ブロックがあるだけで鎌を後ろに回したり戻したりしすぎ」)。左右の両側を測り、隙間の幅で決める。入る・抜けるに差と保つ時間を持たせる
            double width = narrowestGap(fx, fz, s);
            if (this.squeezeOn) {
                this.squeezeHold++;
                if (width >= SQUEEZE_OUT_WIDTH * s && this.squeezeHold >= SQUEEZE_MIN_HOLD) {
                    this.squeezeOn = false;
                    this.squeezeHold = 0;
                }
            } else {
                this.squeezeHold++;
                if (width <= SQUEEZE_IN_WIDTH * s && this.squeezeHold >= SQUEEZE_MIN_GAP) {
                    this.squeezeOn = true;
                    this.squeezeHold = 0;
                }
            }
            qT = this.squeezeOn ? 1.0F : 0.0F;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            // 狭い所の構えで頭が上がっている間は、もう 1 段上(足元から 3 段上)も見る
            int rows = this.squeezeServer > 0.3F ? 2 : 1;
            look:
            for (double ahead : new double[]{3.5D, 4.5D, 5.5D}) {
                for (int r = 0; r < rows; r++) {
                    p.set(this.getX() + fx * ahead * s, this.getY() + (2.0D + r) * s + 0.5D, this.getZ() + fz * ahead * s);
                    BlockState st = this.level().getBlockState(p);
                    // 見えない壁(バリア)は頭が当たって見えるものではないので数えない(GameTest のテスト場の壁も)
                    if (!st.isAir() && !st.is(net.minecraft.world.level.block.Blocks.BARRIER) && !st.getCollisionShape(this.level(), p).isEmpty()) {
                        dT = 1.0F;
                        break look;
                    }
                }
            }
        }
        this.squeezeServer += Mth.clamp(qT - this.squeezeServer, -SQUEEZE_RATE, SQUEEZE_RATE);
        this.duckServer += Mth.clamp(dT - this.duckServer, -DUCK_RATE, DUCK_RATE);
        int packed = Math.round(this.squeezeServer * 255.0F) | (Math.round(this.duckServer * 255.0F) << 8);
        this.entityData.set(DATA_SHAPE, packed);
        this.squeezeNow = (packed & 0xFF) / 255.0F;   // サーバーも送った値(丸めた値)を使う(部位が両側で同じになる)
        this.duckNow = ((packed >> 8) & 0xFF) / 255.0F;
    }

    /** GameTest 用: 構えの効きを直に置く(サーバー)。 */
    public void forceShapeForTest(float q, float d) {
        this.squeezeServer = q;
        this.duckServer = d;
    }

    /** うろつきを始める、プレイヤーからの距離(ブロック)【仮】。遠くの個体は動かさない(重さのため。バニラの「動きの無い時間」の代わり)。 */
    static final double STROLL_RANGE = 64.0D;
    /** うろつきの間隔(平均の tick)と行き先の範囲(横・縦、ブロック)【仮】。 */
    static final int STROLL_INTERVAL = 80, STROLL_XZ = 12, STROLL_Y = 7;

    /**
     * 標的のいない間、森の中を歩き回る(11-08 夜、ユーザー「待機の癖も直す」)。
     *
     * <p>前はバニラの {@code WaterAvoidingRandomStrollGoal} だった。バニラのうろつきは「動きの無い時間」({@code noActionTime})が 100 を超えると
     * 始まらず、その時間を 0 に戻すのは 32 ブロック以内にいる<b>サバイバルの</b>プレイヤーだけ(クリエイティブ・観戦は数えない)、
     * しかも昼のモンスターはこの時間が 1 tick に 2 ずつ増える。だからクリエイティブで見ていると、標的のいない個体は一度もうろつかず、
     * 待機の仕草だけを繰り返して止まったままに見えた。ここでは動きの無い時間を見ず、代わりに {@link #STROLL_RANGE} 以内に
     * だれか(クリエイティブ・観戦も含む)いるときだけ始める。行き先はバニラと同じく陸の上の乱数の位置(水を避ける)。
     */
    static final class ForestStrollGoal extends net.minecraft.world.entity.ai.goal.RandomStrollGoal {
        private final FalcimnesEntity falc;

        ForestStrollGoal(FalcimnesEntity mob) {
            super(mob, 0.8D, STROLL_INTERVAL, false);
            this.falc = mob;
        }

        @Override
        public boolean canUse() {
            // 確かめ用(テストクライアントだけ): 名前が nostroll で始まる個体はうろつかない(navto の経路を上書きしないように)
            if (DEV_HOOKS && this.falc.hasCustomName() && this.falc.getCustomName().getString().startsWith("nostroll")) return false;
            return !this.falc.isActing() && this.falc.playerWithin(STROLL_RANGE) && super.canUse();
        }

        @Nullable
        @Override
        protected Vec3 getPosition() {
            if (this.mob.isInWaterOrBubble()) {
                Vec3 p = net.minecraft.world.entity.ai.util.LandRandomPos.getPos(this.mob, STROLL_XZ, STROLL_Y);
                return p == null ? super.getPosition() : p;
            }
            return net.minecraft.world.entity.ai.util.LandRandomPos.getPos(this.mob, STROLL_XZ, STROLL_Y);
        }
    }

    /** この個体から {@code range} ブロック以内にプレイヤー(観戦も含む)がいるか。 */
    boolean playerWithin(double range) {
        double r2 = range * range;
        for (Player p : this.level().players()) {
            if (p.distanceToSqr(this) <= r2) return true;
        }
        return false;
    }

    private void tickTerrain() {
        if (this.level().isClientSide()) return;
        long t0 = System.nanoTime();
        double s = this.getScale();
        double pitch = 0.0D, roll = 0.0D, lift = 0.0D;
        if (terrainAlways || this.terrainForced || playerWithin(TERRAIN_RANGE)) {
            if (this.serverPlanter == null) {
                // 範囲に入った(または湧いた): 新しく作る。最初の tick は今の地面へそのまま置き、効きは 0 から上がる(+ 送る値もなめらかに寄せる)
                this.serverPlanter = new FootPlanter(LEG_HOME_LEFT, LEG_HOME_FWD, LEG_GROUP);
            }
            int act = getAction();
            boolean keyed = this.isDeadOrDying() || isDodge(act) || act == ACT_GRAB_DASH || this.isPassenger() || this.isInWater();
            double stride = FalcimnesPersonality.stride(caution(), aggression());
            this.serverPlanter.stepAt = 0.45D * stride * squeezeStride();
            this.serverPlanter.lead = 6.0D * stride * squeezeStride();
            this.serverPlanter.lateral = squeezeLateral();
            this.serverPlanter.tick(this, !keyed, s);
            float w = this.serverPlanter.weight(1.0F);
            lift = Mth.clamp(this.serverPlanter.bodyWorldY(1.0F) - this.getY(), -2.0D * s, 1.2D * s) * w;
            pitch = Math.toDegrees(this.serverPlanter.bodyPitch(1.0F)) * w;
            roll = Math.toDegrees(this.serverPlanter.bodyRoll(1.0F)) * w;
        } else {
            // 範囲の外: 計算しない(足の置き方を捨てる)。送る傾きは平らへなめらかに戻す
            this.serverPlanter = null;
        }
        // 範囲に出入りした前後 20 tick だけなめらかに寄せる。続けて計算している間は寄せない(描画の足の置き方と遅れがずれないように)
        this.terrainSteady = this.serverPlanter != null ? this.terrainSteady + 1 : 0;
        double ease = this.terrainSteady > 20 ? 1.0D : TERRAIN_EASE;
        this.terrainOut[0] += (pitch - this.terrainOut[0]) * ease;
        this.terrainOut[1] += (roll - this.terrainOut[1]) * ease;
        this.terrainOut[2] += (lift - this.terrainOut[2]) * ease;
        pitch = this.terrainOut[0];
        roll = this.terrainOut[1];
        lift = this.terrainOut[2];
        int p = Mth.clamp((int) Math.round(pitch * 4.0D), -127, 127) & 0xFF;
        int r = Mth.clamp((int) Math.round(roll * 4.0D), -127, 127) & 0xFF;
        int l = Mth.clamp((int) Math.round(lift * 256.0D), -32767, 32767) & 0xFFFF;
        this.entityData.set(DATA_TERRAIN, p | (r << 8) | (l << 16));
        terrainNanos += System.nanoTime() - t0;
        terrainCalls++;
    }

    /** 送った胴の傾き {前後(度、+ で前が上がる), 左右(度、+ で左が上がる), 高さ(ブロック)}。両側で同じ値。 */
    public float[] terrainTilt() {
        int v = this.entityData.get(DATA_TERRAIN);
        return new float[]{(byte) (v & 0xFF) / 4.0F, (byte) ((v >> 8) & 0xFF) / 4.0F, (short) ((v >> 16) & 0xFFFF) / 256.0F};
    }

    /** 首に付いている部位(首の逆の起こしを受ける)。 */
    private static boolean onNeck(String name) {
        return switch (name) {
            case "head", "neck_a", "neck_b", "arm_l", "arm_r", "blade_l", "blade_r" -> true;
            default -> false;
        };
    }

    /**
     * 部位の中心(体格 1、[前, 左, 上])を胴の傾きで回す。描画({@code client/FalcimnesLegRig})と同じく、首に付いた部位は先に首の付け根
     * (前 1.474・高さ 1.575)まわりで胴の傾きの半分だけ逆へ起こし、次に全部を腰の中心(前 0.056・高さ 1.575)まわりで前後・左右に傾け、高さを足す。
     */
    private void applyTerrain(double[] c, String[] names) {
        float[] t = terrainTilt();
        if (t[0] == 0.0F && t[1] == 0.0F && t[2] == 0.0F) return;
        double s = Math.max(this.getScale(), 0.05F);
        double pitch = Math.toRadians(t[0]), roll = Math.toRadians(t[1]), lift = t[2] / s;
        double neckF = 1.474D, neckU = 1.575D, hubF = 0.056D, hubU = 1.575D;
        double cn = Math.cos(-pitch * 0.5D), sn = Math.sin(-pitch * 0.5D);
        double cp = Math.cos(pitch), sp = Math.sin(pitch), cr = Math.cos(roll), sr = Math.sin(roll);
        for (int i = 0; i < names.length; i++) {
            double f = c[i * 3], l = c[i * 3 + 1], u = c[i * 3 + 2];
            if (onNeck(names[i])) {
                double df = f - neckF, du = u - neckU;
                f = neckF + df * cn - du * sn;
                u = neckU + df * sn + du * cn;
            }
            double df = f - hubF, du = u - hubU;
            f = hubF + df * cp - du * sp;
            u = hubU + df * sp + du * cp;
            du = u - hubU;
            double l2 = l * cr - du * sr;
            u = hubU + l * sr + du * cr;
            c[i * 3] = f;
            c[i * 3 + 1] = l2;
            c[i * 3 + 2] = u + lift;
        }
    }

    /** 体の曲げを 1 tick 進める(サーバーが求めて送り、クライアントは受けた値を進める)。aiStep の移動の後。 */
    private void tickBend() {
        if (this.level().isClientSide()) {
            this.bend.clientTick(this.entityData.get(DATA_BEND));
        } else {
            this.entityData.set(DATA_BEND, this.bend.serverTick(this));
        }
    }
    /** 部位の動作の鍵(動作 × 始まった時刻。替わったら今の置き場から補間し直す)。 */
    private long partKey = Long.MIN_VALUE;
    private int partSinceKey;
    /** 部位に足す性格の構えの効き(両側で同じ式。描画の postureFade は描画の時刻で進むので別に持つ)。 */
    private float partPostureFade;
    private boolean partPlaced;

    @Override
    public void setId(int id) {
        super.setId(id);
        // コンストラクタの setId より前(Entity のコンストラクタの中)には呼ばれないが、念のため
        if (this.hitParts != null) {
            this.hitParts.assignIds(id + 1);
        }
    }

    @Override
    public boolean isMultipartEntity() {
        return true;
    }

    @Override
    public net.neoforged.neoforge.entity.PartEntity<?>[] getParts() {
        return this.hitParts.parts();
    }

    public HitPartSet hitParts() {
        return this.hitParts;
    }

    /** 部位を体の向き・構えに合わせて置き直す(aiStep の移動の後、両側)。 */
    private void placeHitParts() {
        FalcimnesPartTrack track = FalcimnesPartTrack.get();
        int act = this.isDeadOrDying() ? -1 : getAction();
        int start = act == -1 ? 0 : this.entityData.get(DATA_ACTION_TIME);
        long key = ((long) act << 32) ^ (start & 0xffffffffL);
        int elapsed = act == -1 ? this.deathTime : (int) (this.level().getGameTime() - start);
        // 描画の mainState と同じアニメ・補間の長さ・速さ
        String anim;
        int blend = TRANSITION_TICKS;
        double speed = 1.0D;
        switch (act) {
            case -1 -> anim = "death";
            case ACT_ATTACK_R -> anim = "attack_r";
            case ACT_ATTACK_L -> anim = "attack_l";
            case ACT_FLURRY_WINDUP -> anim = "flurry_windup";
            case ACT_FLURRY_R, ACT_FLURRY_L -> {
                anim = act == ACT_FLURRY_R ? "flurry_r" : "flurry_l";
                blend = 0;
                speed = 10.0D / Math.max(1, this.entityData.get(DATA_SLASH_TICKS));
            }
            case ACT_GRAB_WINDUP -> anim = "grab_windup";
            case ACT_GRAB_DASH -> anim = "grab_dash";
            case ACT_GRAB_HOLD -> anim = "grab_hold";
            case ACT_FLURRY_END -> anim = "flurry_end";
            case ACT_RECOVER -> anim = "grab_end";
            case ACT_THREAT_END -> anim = "ikaku_end";
            case ACT_DODGE -> anim = this.entityData.get(DATA_DODGE_HI) ? "dodge_hi" : "dodge";
            case ACT_DODGE_L -> anim = this.entityData.get(DATA_DODGE_HI) ? "dodge_l_hi" : "dodge_l";
            case ACT_DODGE_R -> anim = this.entityData.get(DATA_DODGE_HI) ? "dodge_r_hi" : "dodge_r";
            case ACT_THREAT -> anim = "ikaku";
            case ACT_WAVE_R -> anim = "wave_r";
            case ACT_WAVE_L -> anim = "wave_l";
            case ACT_RIFT_WINDUP -> anim = "rift_windup";
            case ACT_RIFT_HOLD -> { anim = "rift_hold"; blend = 0; }
            case ACT_RIFT_RECOVER -> { anim = "rift_recover"; blend = 0; }
            case ACT_GRAB_FINISH -> anim = "grab_finish";
            case ACT_FLURRY_X -> anim = "flurry_x";
            case ACT_KIRIAGE_R -> anim = "kiriage_r";
            case ACT_KIRIAGE_L -> anim = "kiriage_l";
            case ACT_TSUKIKAKI -> anim = "tsukikaki";
            case ACT_KOSA -> anim = "kosa";
            case ACT_FUMIKOMI_R -> anim = "fumikomi_r";
            case ACT_FUMIKOMI_L -> anim = "fumikomi_l";
            case ACT_KOZUKI_R -> anim = "kozuki_r";
            case ACT_KOZUKI_L -> anim = "kozuki_l";
            case ACT_IDLE -> anim = IDLE_ANIM_NAMES[Math.floorMod(idleKind(), IDLE_ANIM_NAMES.length)];
            case ACT_GROOM_END -> anim = idleKind() == IDLE_GROOM_L ? "groom_end_l" : "groom_end_r";
            default -> {
                // 動作なし(待機・歩き・旋回)。待機のループを世界の時刻で流す(歩きの揺れは小さいので待機で代える)
                anim = "idle";
            }
        }
        if (!track.has(anim)) anim = "idle";
        if (key != this.partKey) {
            this.partKey = key;
            this.partSinceKey = 0;
            System.arraycopy(this.partNow, 0, this.partFrom, 0, this.partNow.length);
        }
        // 補間の重みは「この個体が動作の替わりを見てから」の tick(クライアントは同期が数 tick 遅れて届くので、開始時刻からだと補間が飛ぶ)
        int since = this.partSinceKey++;
        double t = act == ACT_NONE ? this.level().getGameTime() / 20.0D
                : Math.max(0, elapsed - blend) / 20.0D * speed;
        track.sample(anim, t, this.partTarget);
        if (!this.partPlaced) {
            // 初めて置くとき(湧いた・クライアントで見え始めた)は補間せず、今の動作の形から始める
            System.arraycopy(this.partTarget, 0, this.partFrom, 0, this.partTarget.length);
        }
        double w =blend <= 0 ? 1.0D : Mth.clamp((since + 1) / (double) blend, 0.0D, 1.0D);
        for (int i = 0; i < this.partNow.length; i++) {
            this.partNow[i] = Mth.lerp(w, this.partFrom[i], this.partTarget[i]);
        }
        this.partPlaced = true;
        // 11-08 夜: 性格の構え(慎重 = 低く首を垂らす / 荒い = 高く首を起こす)にも付いていく。性格は同期されているので両側で同じ値。
        // 構えの効きは描画(FalcimnesPostureRig)と同じく、動作の無い間 1・技の間 0 へ、1 秒に 8 の速さで寄る(1 tick で 4 割)
        float postureWant = act == ACT_NONE ? 1.0F : 0.0F;
        this.partPostureFade += (postureWant - this.partPostureFade) * 0.4F;
        float[] pw = FalcimnesPersonality.postureWeights(caution(), aggression());
        System.arraycopy(this.partNow, 0, this.partPosed, 0, this.partNow.length);   // 構えは partNow(動作の切り替えの補間の始点)へ混ぜない
        // 段 3: 狭い所の構えの間は性格の構えを薄める(鎌の構えが畳んだ鎌とぶつかる)。描画の FalcimnesShapeRig と同じ
        track.addPosture(this.partPosed, pw[0], pw[1], Math.max(0.0F, (0.5F - caution()) * 2.0F), this.partPostureFade * (1.0F - this.squeezeNow));
        track.addShape(this.partPosed, this.squeezeNow, this.duckNow);
        this.bend.bendParts(this.partPosed, this.partBent, track.names);   // 段 2: 首・腹の曲げに付いていく(両側で同じ値)
        applyTerrain(this.partBent, track.names);   // 11-08 夜: 坂・段での胴の傾きと高さ(サーバーが求めて送った値。両側で同じ)
        this.hitParts.place(this, this.getYRot(), this.partBent);
    }

    /** GameTest の失敗の手がかり。 */
    public String partDebug() {
        return "key " + this.partKey + " since " + this.partSinceKey + " placed " + this.partPlaced + " act " + getAction()
                + " t0 " + this.entityData.get(DATA_ACTION_TIME) + " now " + this.level().getGameTime()
                + " head " + this.partNow[0] + "," + this.partNow[1] + "," + this.partNow[2] + " from " + this.partFrom[0] + " target " + this.partTarget[0];
    }

    /** 待機の仕草のアニメの名前({@link #IDLE_ANIMS} と同じ順)。 */
    private static final String[] IDLE_ANIM_NAMES = {"idle_look_r", "idle_look_l", "idle_gape", "idle_shake", "idle_groom_r", "idle_groom_l"};

    /**
     * 窒息は芯の箱の中で見る。バニラは目の高さで見るが、目({@link #EYE_HEIGHT})は芯の箱の上に出ていて、
     * 芯が通れる隙間でも頭の高さの葉・枝に「埋まった」と見てしまう(頭・首は地形とぶつからない作り)。
     * 見る高さは前の既定の目と同じ「箱の高さの 0.85」。
     */
    @Override
    public boolean isInWall() {
        if (this.noPhysics) {
            return false;
        }
        float w = this.getBbWidth() * 0.8F;
        AABB box = AABB.ofSize(new Vec3(this.getX(), this.getY() + this.getBbHeight() * 0.85D, this.getZ()), w, 1.0E-6D, w);
        return BlockPos.betweenClosedStream(box).anyMatch(p -> {
            BlockState s = this.level().getBlockState(p);
            return !s.isAir() && s.isSuffocating(this.level(), p)
                    && net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(s.getCollisionShape(this.level(), p).move(p.getX(), p.getY(), p.getZ()),
                    net.minecraft.world.phys.shapes.Shapes.create(box), net.minecraft.world.phys.shapes.BooleanOp.AND);
        });
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), "falcimnes");
    }

    /** 幅のある体でも段差の上に体が入るかを確かめる経路探索(他の大型種と同じ)。 */
    @Override
    protected PathNavigation createNavigation(Level level) {
        return new GroundPathNavigation(this, level) {
            @Override
            protected PathFinder createPathFinder(int maxVisitedNodes) {
                OvergrownNodeEvaluator evaluator = new OvergrownNodeEvaluator();
                evaluator.setLongBody(FalcimnesBend.LONG_BODY);   // 段 2: 幹に近い所・狭い所の急な曲がりを高くつける
                this.nodeEvaluator = evaluator;
                this.nodeEvaluator.setCanPassDoors(true);
                return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
            }

            /**
             * 11-09(ユーザー「直す」): 回り道が経路を探す範囲(調べるマスの数・距離)の外にあると、慎重な個体が葉や障害物の手前で止まった。
             * 普段の探索が届かない経路しか返さなかったときだけ、調べる数を {@link #WIDE_NODES} 倍・距離を +{@link #WIDE_EXTRA} に広げて 1 回探し直す
             * (普段の歩きの重さは変わらない)。探し直しには間隔を空ける(届かなければもっと空ける)。広げても届かない(閉じた囲いなど)ときは、
             * 慎重な個体は届かない経路のまま手前まで行って、うろつきに戻る(押し分けるのは荒い個体だけ、の決まりはそのまま)。
             */
            @Override
            protected net.minecraft.world.level.pathfinder.Path createPath(java.util.Set<BlockPos> targets, int regionOffset, boolean offsetUpward,
                                                                            int accuracy, float followRange) {
                net.minecraft.world.level.pathfinder.Path p = super.createPath(targets, regionOffset, offsetUpward, accuracy, followRange);
                FalcimnesEntity f = FalcimnesEntity.this;
                if ((p == null || !p.canReach()) && !targets.isEmpty() && f.level() instanceof net.minecraft.server.level.ServerLevel sl
                        && (f.tickCount >= f.wideRetryTick || f.detourMoved())) {
                    if (detourCoarse) return coarse(sl, p, targets, regionOffset, offsetUpward, accuracy, followRange);
                    long t0 = System.nanoTime();
                    net.minecraft.world.level.pathfinder.Path w;
                    this.setMaxVisitedNodesMultiplier(WIDE_NODES);
                    // 広げた探し直しでは長い体の重み(幹・葉の近さ・曲がり)を外す。重みがあると遠回りの探索で調べるマスが増え、4 倍でも届かなかった
                    // (25 マスの生け垣、11-09)。回り道を見つけるのが目的なので、壁際を通る経路でよい
                    OvergrownNodeEvaluator ev = this.nodeEvaluator instanceof OvergrownNodeEvaluator o ? o : null;
                    OvergrownNodeEvaluator.LongBody keep = ev != null ? ev.longBody() : null;
                    if (ev != null) ev.setLongBody(null);
                    try {
                        w = super.createPath(targets, regionOffset, offsetUpward, accuracy, followRange + WIDE_EXTRA);
                    } finally {
                        this.resetMaxVisitedNodesMultiplier();
                        if (ev != null) ev.setLongBody(keep);
                    }
                    boolean reached = w != null && w.canReach();
                    // 届かなくても、普段の経路より WIDE_CLOSER 以上目標に近い所まで行けるなら採る(回り込んだ先で探し直せば、残りは普段の探索で届く。
                    // 25 マスの生け垣では、広げた経路が端を回って向こう側の目標の 4 ブロック手前まで来ていた、11-09)
                    boolean closer = !reached && w != null && w.getEndNode() != null && w.getTarget() != null
                            && endGap(w) + WIDE_CLOSER <= (p == null || p.getEndNode() == null ? Double.MAX_VALUE : endGap(p));
                    f.wideRetryTick = f.tickCount + (reached || closer ? WIDE_RETRY_TICKS : WIDE_GIVE_UP_TICKS);
                    f.detourFrom = null;
                    long ns = System.nanoTime() - t0;
                    widePathNanos += ns;
                    com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.spend(sl, ns);
                    if (DEV_HOOKS) com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("FALC_WIDE {} µs 普段 {} / 広げた {} ノード {} 届く {}", ns / 1000,
                            p == null ? "null" : p.getNodeCount() + (p.canReach() ? "届く" : "届かない"), w == null ? "null" : "ok", w == null ? -1 : w.getNodeCount() + (w.getEndNode() == null ? "" : " 終わり " + w.getEndNode().asBlockPos() + " 目標 " + w.getTarget()), reached);
                    widePathCalls++;
                    if (reached || closer) {
                        widePathFound++;
                        return w;
                    }
                }
                return p;
            }

            /**
             * 11-09: 広げた探し直し(1 回 11〜13 ms)の代わり。粗い下調べ({@link com.yayuy.overgrownvegetation.entity.forest.CoarseDetour})で
             * 回り道を見つけ、その道の途中(普段の探索で届く距離)の「途中の目標」まで普段の探索でつなぐ。途中の目標に着けば、
             * 呼ぶ側(Goal)が引き直すたびにまた下調べして先へ進む(動いた後は間隔を待たない)。サーバー全体の予算を使い切っていたら、
             * 少し後({@link #DETOUR_WAIT_TICKS})に回す(それまでは普段の届かない経路のまま)。
             */
            net.minecraft.world.level.pathfinder.Path coarse(net.minecraft.server.level.ServerLevel sl, net.minecraft.world.level.pathfinder.Path p,
                                                             java.util.Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy, float followRange) {
                FalcimnesEntity f = FalcimnesEntity.this;
                if (!com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.budgetLeft(sl)) {
                    f.wideRetryTick = f.tickCount + DETOUR_WAIT_TICKS;
                    return p;
                }
                BlockPos goal = null;
                for (BlockPos t : targets) if (goal == null || t.distSqr(f.blockPosition()) < goal.distSqr(f.blockPosition())) goal = t;
                int width = net.minecraft.util.Mth.floor(f.getBbWidth() + 1.0F);
                BlockPos from = f.blockPosition();
                double reach = Math.max(DETOUR_REACH_MIN, Math.min(followRange * DETOUR_REACH, DETOUR_REACH_MAX));
                long call0 = System.nanoTime();
                var plan = com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.plan(sl, from, goal, width, !f.leafPusher, reach);
                widePathCalls++;
                double before = p == null || p.getEndNode() == null ? Double.MAX_VALUE : Math.sqrt(p.getEndNode().asBlockPos().distSqr(goal));
                net.minecraft.world.level.pathfinder.Path q = null;
                if (plan != null && plan.gap() + WIDE_CLOSER <= before) {
                    long t0 = System.nanoTime();
                    // 途中の目標は近く(reach 以内)で道も分かっているので、調べる数と距離を絞る(届かないときに数を使い切るのが重さの山だった、11-09)
                    // 長い体の重みも外す(広げた探し直しと同じ。体が通れる幅は下調べで確かめてある。重みがあると曲がり角の手前で数が尽きた)
                    this.setMaxVisitedNodesMultiplier(Math.max(DETOUR_FINE_NODES, DETOUR_FINE_MIN_NODES / (followRange * 16.0F)));
                    OvergrownNodeEvaluator ev = this.nodeEvaluator instanceof OvergrownNodeEvaluator o ? o : null;
                    OvergrownNodeEvaluator.LongBody keep = ev != null ? ev.longBody() : null;
                    if (ev != null) ev.setLongBody(null);
                    try {
                        q = super.createPath(java.util.Set.of(plan.waypoint()), regionOffset, offsetUpward, accuracy, (float) (reach + 4.0D));
                    } finally {
                        this.resetMaxVisitedNodesMultiplier();
                        if (ev != null) ev.setLongBody(keep);
                    }
                    long ns = System.nanoTime() - t0;
                    widePathNanos += ns;
                    com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.spend(sl, ns);
                }
                com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.noteCall(System.nanoTime() - call0);
                // 途中の目標(か、そのすぐ手前)まで行ければ採る。届かなくても途中の目標に 1 ブロック以上近づくなら採る(引き返して行ったり来たりしない)
                boolean ok = q != null && q.getEndNode() != null && (q.getEndNode().asBlockPos().distSqr(plan.waypoint()) <= DETOUR_NEAR_SQR
                        || Math.sqrt(q.getEndNode().asBlockPos().distSqr(plan.waypoint())) + 1.0D <= Math.sqrt(from.distSqr(plan.waypoint())));
                f.wideRetryTick = f.tickCount + (ok ? WIDE_RETRY_TICKS : WIDE_GIVE_UP_TICKS);
                f.detourFrom = ok ? from : null;
                f.detourTo = ok ? q.getEndNode().asBlockPos() : null;
                if (detourTrace) lastDetour = "位置 " + from + " 目標 " + goal + " 下調べ " + (plan == null ? "なし" : plan.waypoint() + " 残り " + plan.gap()) + " 普段の残り " + before + " 細かい終わり " + (q == null || q.getEndNode() == null ? "-" : q.getEndNode().asBlockPos()) + " 採る " + ok;
                if (DEV_HOOKS) com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("FALC_DETOUR 位置 {} 目標 {} 葉を避ける {} 下調べ {} 途中 {} 残り {} / 普段の残り {} 細かい終わり {} 採る {}", from, goal, !f.leafPusher,
                        plan == null ? "なし" : "あり", plan == null ? "-" : plan.waypoint(), plan == null ? "-" : String.format(java.util.Locale.ROOT, "%.1f", plan.gap()),
                        before == Double.MAX_VALUE ? "-" : String.format(java.util.Locale.ROOT, "%.1f", before), q == null || q.getEndNode() == null ? "-" : q.getEndNode().asBlockPos(), ok);
                if (ok) {
                    widePathFound++;
                    return q;
                }
                return p;
            }
        };
    }

    /** 回り道のやり方: true = 粗い下調べ + 途中の目標(11-09)、false = 前の広げた探し直し(比べる計測のため残す)。 */
    public static volatile boolean detourCoarse = true;
    /** 確かめ用(GameTest): 最後の回り道の中身を文字にして残すか・その文字。 */
    public static volatile boolean detourTrace;
    public static volatile String lastDetour = "";
    /** 予算を使い切っていたときに待つ tick・途中の目標を置く距離(普段の探す距離に掛ける・上限ブロック)・途中の目標に「着ける」とみる距離の 2 乗【仮】。 */
    static final int DETOUR_WAIT_TICKS = 5;
    static final double DETOUR_REACH = 0.3D, DETOUR_REACH_MIN = 4.0D, DETOUR_REACH_MAX = 12.0D, DETOUR_NEAR_SQR = 4.0D;
    /** 途中の目標へ動き出した所(ここから {@link #DETOUR_MOVED_SQR} より離れたら、間隔を待たずに次を下調べしてよい)。 */
    private BlockPos detourFrom;
    static final double DETOUR_MOVED_SQR = 4.0D;
    /** 途中の目標までの細かい探索の、調べる数の倍率(普段に掛ける)と下限(マス)【仮】。 */
    static final float DETOUR_FINE_NODES = 0.35F, DETOUR_FINE_MIN_NODES = 64.0F;

    /** 前の途中の目標への経路の終わり(ここに着いたら、動いた距離によらず次を下調べしてよい)。 */
    private BlockPos detourTo;

    boolean detourMoved() {
        if (this.detourFrom == null) return false;
        BlockPos at = this.blockPosition();
        return at.distSqr(this.detourFrom) > DETOUR_MOVED_SQR || (this.detourTo != null && !this.detourTo.equals(this.detourFrom) && at.distSqr(this.detourTo) <= 3.0D);   // 経路のノードは体の足場の角、体の位置はその +1(バニラの大きい体)
    }

    /** 経路を広げて探し直すときの、調べるマスの数の倍率と、探す距離の足し(ブロック)【仮】。 */
    static final float WIDE_NODES = 2.5F;
    /** 広げた経路が届かなくても、普段の経路より目標にこれだけ(ブロック)近い所で終わるなら採る【仮】。 */
    static final double WIDE_CLOSER = 2.0D;

    /** 経路の終わりのノードから目標までの距離(ブロック)。 */
    static double endGap(net.minecraft.world.level.pathfinder.Path p) {
        return Math.sqrt(p.getEndNode().asBlockPos().distSqr(p.getTarget()));
    }
    static final float WIDE_EXTRA = 16.0F;
    /** 広げて探し直す間隔: 届いたら次まで / 届かなかったら次まで(tick)【仮】。 */
    static final int WIDE_RETRY_TICKS = 60, WIDE_GIVE_UP_TICKS = 200;
    /** 次に広げて探し直してよい tick。 */
    private int wideRetryTick;
    /** 計測用: 広げた探し直しの時間(ns)・回数・届いた回数。 */
    public static volatile long widePathNanos, widePathCalls, widePathFound;

    /**
     * 体は常に {@code yRot} と同じ向き(サーバーでもクライアントでも)。バニラの {@code BodyRotationControl} は
     * 動いている向きへ体を回すので、そのままだと体だけ速く回ってしまう。
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
        builder.define(DATA_SLASH_TICKS, 10);
        builder.define(DATA_TURN, 0.0F);
        builder.define(DATA_DODGE_HI, false);
        builder.define(DATA_IDLE_KIND, IDLE_LOOK_R);
        builder.define(DATA_ACTION_TIME, 0);
        builder.define(DATA_BEND, 0L);
        builder.define(DATA_TERRAIN, 0);
        builder.define(DATA_SHAPE, 0);
        builder.define(DATA_CAUTION, 0.5F);
        builder.define(DATA_AGGRESSION, 0.5F);
        builder.define(DATA_FLANK, 0.5F);
    }

    // ------------------------------------------------------------------ 個体の性格(段 3)

    public float caution() {
        return this.entityData.get(DATA_CAUTION);
    }

    public float aggression() {
        return this.entityData.get(DATA_AGGRESSION);
    }

    public float flank() {
        return this.entityData.get(DATA_FLANK);
    }

    /** 性格を決める(0〜1 に収める)。GameTest は 0.5 / 0.5 / 0.5(段 2 までと同じ振る舞い)を置く。 */
    public void setPersonality(float caution, float aggression, float flank) {
        this.entityData.set(DATA_CAUTION, Mth.clamp(caution, 0.0F, 1.0F));
        this.entityData.set(DATA_AGGRESSION, Mth.clamp(aggression, 0.0F, 1.0F));
        this.entityData.set(DATA_FLANK, Mth.clamp(flank, 0.0F, 1.0F));
        this.personalitySet = true;
        applyPersonalityToPaths();
    }

    // --- 段 3(11-08 夜): 通れない所の振る舞いを性格で変える(決定「慎重な個体は大きく回り込み、荒い個体は下草や葉を押し分けて近道する。ブロックは壊さない」) ---

    /** 荒い個体(開く構えの重み)がこれより大きいと葉を押し分けて通る【仮】。 */
    static final float LEAF_PUSH_MIN = 0.4F;
    /** 葉を押し分けるときの経路の重み(1 マスあたり)【仮】。 */
    static final float LEAF_PUSH_MALUS = 2.0F;
    /** この個体は葉を押し分けて通るか(サーバーの当たりの判定から毎回読む。性格を決めたときに求めておく)。 */
    private boolean leafPusher;

    public boolean pushesThroughLeaves() {
        return this.leafPusher;
    }

    /**
     * 性格から経路の重みを決める(値は全部【仮】)。慎重(畳む重み g)ほど幹から離れたがり・曲がりを嫌い(大きく回り込む)、
     * 荒い(開く重み o)ほど幹の近く・曲がりを気にしない。荒い個体は葉の重みを「通れない」から {@link #LEAF_PUSH_MALUS} にし、
     * 当たりも葉を通り抜ける({@code mixin/LeavesPushThroughMixin})。
     */
    private void applyPersonalityToPaths() {
        float[] w = FalcimnesPersonality.postureWeights(caution(), aggression());
        float g = w[0], o = w[1];
        OvergrownNodeEvaluator.LongBody base = FalcimnesBend.LONG_BODY;
        OvergrownNodeEvaluator.LongBody mine = new OvergrownNodeEvaluator.LongBody(
                base.clearWant() + 1.0F * g - 0.5F * o, base.clearCost() + 1.0F * g - 0.5F * o,
                base.turnCost() + 0.6F * g - 0.3F * o, base.tightClear());
        if (this.getNavigation().getNodeEvaluator() instanceof OvergrownNodeEvaluator ev) {
            ev.setLongBody(mine);
        }
        this.leafPusher = o > LEAF_PUSH_MIN;
        this.setPathfindingMalus(PathType.LEAVES, this.leafPusher ? LEAF_PUSH_MALUS : -1.0F);
    }

    /** 技の重みの性格の掛け数。 */
    double personalityWeight(ActionDef d) {
        return FalcimnesPersonality.weight(d, caution(), aggression(), flank());
    }

    /** 性格の数値({@code actions.json} の擬似の技 {@code personality} の extra)。 */
    static double pv(String key, double fallback) {
        return def(FalcimnesPersonality.ID).get(key, fallback);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new HuntGoal(this));
        this.goalSelector.addGoal(6, new ForestStrollGoal(this));   // 段 3(11-08 夜): バニラのうろつきは創造・観戦の人のそばで始まらなかった
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        // 11-05: 群れ(swarm)と寄生体の索敵(ParasitizedTargetGoal)は戻していないので、バニラの Goal で最小限に。
        // 殴られたら反撃・プレイヤー・過繁茂の側でない生き物を狙う(グループに入っている種は狙わない)。
        this.targetSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(this, LivingEntity.class, 10, true, false,
                e -> !(e instanceof Player) && !(e instanceof ParasitizedMob) && MobGroup.of(e.getType()) == null
                        && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand)));
    }

    // ------------------------------------------------------------------ 状態

    public int getAction() {
        return this.entityData.get(DATA_ACTION);
    }

    public boolean isActing() {
        return getAction() != ACT_NONE;
    }

    public boolean isHoldingPrey() {
        return (getAction() == ACT_GRAB_HOLD || getAction() == ACT_GRAB_FINISH) && !this.releasing;
    }

    /** 大技の出し方の勘定(GameTest 用。書き換えない)。 */
    public SpecialPacing getSpecialPacing() {
        return this.pacing;
    }

    /**
     * 動作を始める。技の勘定もここで数える(11-05): 通常技(横薙ぎ・連撃・掴み・飛ぶ斬撃)は {@link SpecialPacing#onNormalAttack} と
     * 選び方のクールダウン・直前の技({@link ActionPicker#used})、特殊技(地走りの光の帯)は {@link SpecialPacing#onSpecial}。
     * 連撃の 1 振り・突進・掴んだ後・刺したまま待つ などの続きの動作は数えない。
     */
    private void startAction(int action) {
        String id = defIdOf(action);
        if (id != null) {
            ActionDef def = def(id);
            this.picker.used(def);
            if (action == ACT_RIFT_WINDUP) {
                this.pacing.onSpecial();
            } else {
                this.pacing.onNormalAttack();
            }
        }
        this.entityData.set(DATA_ACTION_TIME, (int) this.level().getGameTime());
        this.entityData.set(DATA_ACTION, action);
        this.entityData.set(DATA_SEQ, this.entityData.get(DATA_SEQ) + 1);
        this.actionTicks = 0;
        this.comboResult = FalcimnesPersonality.RESULT_NONE;
    }

    /** 動作の頭の技の名前({@code actions.json} の鍵)。続きの動作なら null。 */
    @Nullable
    static String defIdOf(int action) {
        return switch (action) {
            case ACT_ATTACK_R, ACT_ATTACK_L -> "sweep";
            case ACT_FLURRY_WINDUP -> "flurry";
            case ACT_GRAB_WINDUP -> "grab";
            case ACT_WAVE_R, ACT_WAVE_L -> "wave";
            case ACT_RIFT_WINDUP -> "rift";
            case ACT_KIRIAGE_R, ACT_KIRIAGE_L -> "kiriage";
            case ACT_TSUKIKAKI -> "tsukikaki";
            case ACT_KOSA -> "kosa";
            case ACT_FUMIKOMI_R, ACT_FUMIKOMI_L -> "fumikomi";
            case ACT_KOZUKI_R, ACT_KOZUKI_L -> "kozuki";
            default -> null;
        };
    }

    /** 技の調整値(設計データ)。 */
    public static ActionDef def(String id) {
        return DesignData.actions().def(ACTIONS_ID, id);
    }

    /**
     * 鎌の光の筋の明るさ(0〜1、クライアントの描画 {@code FalcimnesVeinLayer} が読む。11-05)。光るのは光の技の 2 つだけ:
     * <ul>
     *     <li>飛ぶ斬撃: 前触れの 0.3 秒から灯り始め(付け根から刃先へ光が満ちていく見え方は絵の筋の形に任せる)、実行で一番明るく、隙の 0.4 秒で消える。</li>
     *     <li>地走り: 刺すまで(0.1〜0.8 秒)に灯り、刺したまま待つ間は脈打ち、抜き始めて 0.4 秒で消える。</li>
     * </ul>
     * 時刻は動作を始めたワールドの時刻({@link #DATA_ACTION_TIME})から数える(クライアントの動作の tick は持たない)。
     */
    public float veinGlow(float partialTick) {
        int act = getAction();
        float t = (int) this.level().getGameTime() - this.entityData.get(DATA_ACTION_TIME) + partialTick;
        switch (act) {
            case ACT_WAVE_R, ACT_WAVE_L -> {
                float on = LEAD + 6.0F;
                if (t < on) return 0.0F;
                if (t < WAVE.executeStart()) return (t - on) / (WAVE.executeStart() - on);
                if (t < WAVE.recoverStart()) return 1.0F;
                return Mth.clamp(1.0F - (t - WAVE.recoverStart()) / 8.0F, 0.0F, 1.0F);
            }
            case ACT_RIFT_WINDUP -> {
                return Mth.clamp((t - LEAD - 2.0F) / (RIFT_STAB_TICK - LEAD - 2.0F), 0.0F, 1.0F);
            }
            case ACT_RIFT_HOLD -> {
                return 0.82F + 0.18F * Mth.sin(t * 0.8F);
            }
            case ACT_RIFT_RECOVER -> {
                return Mth.clamp(1.0F - t / 8.0F, 0.0F, 1.0F);
            }
            default -> {
                return 0.0F;
            }
        }
    }

    /** 今の動作の段(前触れ・実行・隙)。横薙ぎと飛ぶ斬撃だけ(ほかは続きの動作で段を分けている)。GameTest 用。 */
    public ActionPhase phase() {
        return switch (getAction()) {
            case ACT_ATTACK_R, ACT_ATTACK_L -> SWEEP.phaseAt(this.actionTicks);
            case ACT_WAVE_R, ACT_WAVE_L -> WAVE.phaseAt(this.actionTicks);
            case ACT_RIFT_WINDUP -> ActionPhase.WINDUP;
            case ACT_RIFT_HOLD -> ActionPhase.EXECUTE;
            case ACT_RIFT_RECOVER -> ActionPhase.RECOVER;
            case ACT_FLURRY_X -> FLURRY_X.phaseAt(this.actionTicks);
            case ACT_KIRIAGE_R, ACT_KIRIAGE_L -> KIRIAGE.phaseAt(this.actionTicks);
            case ACT_TSUKIKAKI -> TSUKIKAKI.phaseAt(this.actionTicks);
            case ACT_KOSA -> KOSA.phaseAt(this.actionTicks);
            case ACT_FUMIKOMI_R, ACT_FUMIKOMI_L -> FUMIKOMI.phaseAt(this.actionTicks);
            case ACT_KOZUKI_R, ACT_KOZUKI_L -> KOZUKI.phaseAt(this.actionTicks);
            default -> ActionPhase.DONE;
        };
    }

    /** 通常技(横薙ぎ・連撃・掴み・飛ぶ斬撃・段 2 の小さな技 5 つ)の調整値。 */
    java.util.List<ActionDef> normalDefs() {
        return java.util.List.of(def("sweep"), def("flurry"), def("grab"), def("wave"),
                def("kiriage"), def("tsukikaki"), def("kosa"), def("fumikomi"), def("kozuki"));
    }

    /** 選び方(GameTest 用。書き換えない)。 */
    public ActionPicker getPicker() {
        return this.picker;
    }

    private void endAction() {
        this.entityData.set(DATA_ACTION, ACT_NONE);
        this.actionTicks = 0;
        this.comboStep = 0;
    }

    private void recover(int ticks) {
        this.recoverTicks = ticks;
        startAction(ACT_RECOVER);
    }

    // ------------------------------------------------------------------ 毎 tick

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            tickFootPlanter();
            tickUndergrowthBrush();
        }
    }

    /**
     * 足の置き場を 1 tick 進める(クライアント)。近くで描かれていて(描画が {@link #markLegsRendered} で知らせる)、
     * 歩き・旋回・待機・技の最中なら手続き。回避・突進・死・水の中・乗り物の上は手付けのアニメに任せる。
     */
    private void tickFootPlanter() {
        if (this.footPlanter == null) {
            this.footPlanter = new FootPlanter(LEG_HOME_LEFT, LEG_HOME_FWD, LEG_GROUP);
        }
        int act = getAction();
        boolean keyedOnly = this.isDeadOrDying() || isDodge(act) || act == ACT_GRAB_DASH || this.isPassenger() || this.isInWater();
        // 確かめ用(テストクライアントだけ。撮り比べる): 名前が "keyed" の個体は手付けのまま、"feet" の個体は足の置き場に粒を出す
        String devName = DEV_HOOKS && this.hasCustomName() ? this.getCustomName().getString() : "";
        if (devName.equals("keyed")) {
            keyedOnly = true;
        }
        boolean near = this.tickCount - this.legsRenderedTick <= 5;
        boolean on = near && !keyedOnly;
        // 段 3: 歩幅は性格で(慎重 = 小股、荒い = 大股)。踏み替えるずれと先回りの両方に掛ける【仮】
        double stride = FalcimnesPersonality.stride(caution(), aggression());
        this.footPlanter.stepAt = 0.45D * stride * squeezeStride();   // 段 3: 狭い所では小股・脚を寄せる
        this.footPlanter.lead = 6.0D * stride * squeezeStride();
        this.footPlanter.lateral = squeezeLateral();
        this.footPlanter.tick(this, on, this.getScale());
        this.clientProceduralLegs = this.footPlanter.weight(1.0F) > 0.0F && !keyedOnly;
        if (devName.equals("feet") && this.footPlanter.ready()) {
            StringBuilder sb = this.tickCount % 2 == 0 ? new StringBuilder() : null;
            for (int i = 0; i < this.footPlanter.legs(); i++) {
                Vec3 f = this.footPlanter.foot(i, 1.0F);
                this.level().addParticle(net.minecraft.core.particles.ParticleTypes.END_ROD, f.x, f.y + 0.05D, f.z, 0, 0, 0);
                if (sb != null) {
                    sb.append(String.format(java.util.Locale.ROOT, " [%d] %.2f %.2f %.2f", i, f.x, f.y, f.z));
                }
            }
            if (sb != null) {
                com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("FALC_FEET pos {} {} {} yaw {} w {}{}", this.getX(), this.getY(), this.getZ(),
                        this.yBodyRot, this.footPlanter.weight(1.0F), sb);
            }
        }
    }

    /** 下草を押し分けた音を最後に鳴らした tick(クライアント)。 */
    private int brushSoundTick;

    /**
     * 段 3(11-08 夜): 下草・葉を押し分ける見た目(クライアント、近くで描いていて歩いているときだけ)。
     * 足先(足の置き方の置き場)と頭・首・腹(叩ける部位の中心)のマスに、当たりの無い植物(草・シダ・この MOD の下草)か葉があれば、
     * そのブロックの欠片を少し散らし、ときどきそのブロックの音を小さく鳴らす。ブロックは変えない。数・間隔は【仮】。
     */
    private void tickUndergrowthBrush() {
        if (this.footPlanter == null || !this.footPlanter.ready() || this.tickCount % 2 != 0) return;
        if (clientForwardSpeed() < 0.03D && Math.abs(getTurnRate()) < 2.0F) return;
        Level level = this.level();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        boolean any = false;
        BlockState heard = null;
        int n = this.footPlanter.legs() + this.hitParts.count();
        for (int i = 0; i < n; i++) {
            Vec3 at = i < this.footPlanter.legs() ? this.footPlanter.foot(i, 1.0F).add(0.0D, 0.3D, 0.0D)
                    : this.hitParts.part(i - this.footPlanter.legs()).getBoundingBox().getCenter();
            p.set(at.x, at.y, at.z);
            BlockState st = level.getBlockState(p);
            // 見えないブロック(光のブロックなど)は数えない(その欠片 = 電球の絵の白い粒が出ていた、11-08 夜)
            boolean plant = !st.isAir() && st.getRenderShape() != net.minecraft.world.level.block.RenderShape.INVISIBLE
                    && st.getFluidState().isEmpty() && st.canBeReplaced() && st.getCollisionShape(level, p).isEmpty();
            boolean leaf = st.is(net.minecraft.tags.BlockTags.LEAVES) || st.getBlock() instanceof com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock;
            if (!plant && !leaf) continue;
            if (this.random.nextInt(leaf ? 2 : 3) != 0) continue;
            any = true;
            heard = st;
            for (int k = 0; k < (leaf ? 3 : 1); k++) {
                level.addParticle(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, st),
                        at.x + (this.random.nextDouble() - 0.5D) * 0.6D, at.y + this.random.nextDouble() * 0.4D, at.z + (this.random.nextDouble() - 0.5D) * 0.6D,
                        0.0D, 0.05D, 0.0D);
            }
        }
        if (any && this.tickCount - this.brushSoundTick > 12) {
            this.brushSoundTick = this.tickCount;
            var sound = heard.getSoundType();
            level.playLocalSound(this.getX(), this.getY(), this.getZ(), sound.getHitSound(), net.minecraft.sounds.SoundSource.HOSTILE,
                    sound.getVolume() * 0.35F, sound.getPitch() * 0.8F, false);
        }
    }

    @Nullable
    public FootPlanter footPlanter() {
        return this.footPlanter;
    }

    public double[][] legSolution() {
        return this.legSolution;
    }

    /** 前のフレームの解が新しい(2 tick 以内)か。古ければ手付けの構えから解き直す。 */
    public boolean legSolutionFresh() {
        return this.tickCount - this.legSolvedTick <= 2;
    }

    public void markLegSolution() {
        this.legSolvedTick = this.tickCount;
    }

    public void markLegsRendered() {
        this.legsRenderedTick = this.tickCount;
    }

    /**
     * 動作は {@code aiStep} で進める({@code customServerAiStep} ではなく)。{@code NoAI} の個体でも動作だけは
     * 進むので、GameTest が AI を止めたまま {@link #forceAction} / {@link #grab} で動作を確かめられる。
     */
    @Override
    public void aiStep() {
        if (!this.level().isClientSide() && !Float.isNaN(this.heading)) {
            // この tick の間(Goal・移動・当たりの判定)に使う向きは、旋回の上限を通ったものだけ。
            // tick の外でよそが書いた向き(setYRot の直書き)はここで戻す(置き直しの moveTo は heading を捨てるので残る)。
            applyFacing();
        }
        super.aiStep();
        tickShape();
        tickBend();
        tickTerrain();
        placeHitParts();
        if (this.level().isClientSide() || !this.isAlive()) {
            return;
        }
        if (!this.personalitySet) {
            // 段 3: 湧いた個体(finalizeSpawn を通らない湧き方も)は最初の tick に性格を振る。つまみごとに U 字の分布。
            setPersonality(FalcimnesPersonality.roll(this.random), FalcimnesPersonality.roll(this.random), FalcimnesPersonality.roll(this.random));
        }
        this.picker.tick();
        if (this.dodgeCooldown > 0) this.dodgeCooldown--;
        if (this.threatCooldown > 0) this.threatCooldown--;
        // 交戦の勘定(標的が替わってもやり直さない。10 秒見失ったらやり直す —— SpecialPacing の javadoc)。AI の後
        // (標的を選ぶ Goal と HuntGoal は super.aiStep の中)に数えるので、HuntGoal が見ているのは 1 tick 前の勘定
        // (暖機と見失いの 10 秒が 1 tick ずれるだけ。交戦を始めた tick はまだ交戦していない扱いなので、大技が早まる側にはずれない)。
        this.pacing.tick(this.getTarget());
        // 動作は保存しないが、乗客(掴んだ獲物)は保存される。読み込み直すと掴んでいない状態で獲物を抱えたままになるので放す
        // (獲物の Mob は自分では降りない)。噛みつきの最中以外に乗っているものは全部これ。
        if (getAction() != ACT_GRAB_HOLD && getAction() != ACT_GRAB_FINISH && this.isVehicle()) {
            this.ejectPassengers();
        }
        if (this.pendingForce >= 0) {
            int act = this.pendingForce;
            this.pendingForce = -1;
            // 確かめ用: 相手が居なければ近くの相手(24 ブロック以内でいちばん近い敵)を相手にする(NoAI でも技が相手へ向くように)。
            if (this.getTarget() == null || !this.getTarget().isAlive()) {
                LivingEntity near = this.level().getNearestEntity(LivingEntity.class,
                        net.minecraft.world.entity.ai.targeting.TargetingConditions.forNonCombat().range(24.0D).selector(this::isFoeForWave),
                        this, this.getX(), this.getY(), this.getZ(), this.getBoundingBox().inflate(24.0D));
                if (near != null) this.setTarget(near);
            }
            startAction(act);
        }
        if (isActing()) {
            this.actionTicks++;
            tickAction();
        }
        tickIdleFidget();
        // 旋回は最後 —— この tick に出た向きの希望(Goal・移動・動作)を全部見てから回す。
        tickTurn();
    }

    private void tickAction() {
        LivingEntity target = this.getTarget();
        boolean hasTarget = target != null && target.isAlive();
        switch (getAction()) {
            case ACT_ATTACK_R, ACT_ATTACK_L -> {
                holdStill();
                if (this.actionTicks == 1) playSound(ModSounds.FALCIMNES_GROWL.get(), 1.0F, 1.15F);
                if (hasTarget && this.actionTicks <= ATTACK_TRACK_UNTIL) {
                    turnTo(target, 30.0F);
                    stepToward(target, SWEEP_REACH * this.bodyScale() * ATTACK_IDEAL_RATIO, ATTACK_STEP_SPEED);
                }
                if (this.actionTicks == ATTACK_HIT_TICK - 2) playSound(ModSounds.FALCIMNES_SLASH.get(), 1.0F, 1.0F);
                if (this.actionTicks == ATTACK_HIT_TICK) {
                    for (LivingEntity e : inSweep(SWEEP_REACH + SWEEP_MARGIN, SWEEP_HALF_WIDTH)) {
                        this.doHurtTarget(e);
                    }
                }
                if (this.actionTicks >= ATTACK_TICKS) {
                    endAction();
                }
            }
            case ACT_FLURRY_WINDUP -> {
                holdStill();
                if (this.actionTicks == 1) playSound(ModSounds.FALCIMNES_GROWL.get(), 1.4F, 0.85F);
                if (hasTarget) turnTo(target, 20.0F);
                if (this.actionTicks >= FLURRY_WINDUP_TICKS) {
                    this.flurryIndex = 0;
                    startSlash();
                }
            }
            case ACT_FLURRY_R, ACT_FLURRY_L -> {
                int interval = FLURRY_INTERVALS[this.flurryIndex];
                if (hasTarget) {
                    turnTo(target, 25.0F);
                    // 振りながら寄っていく(遅い足で)。近すぎれば止まる。
                    if (this.distanceTo(target) > 3.0F * this.bodyScale()) {
                        if (this.actionTicks == 1 || this.tickCount % 5 == 0) {
                            this.getNavigation().moveTo(target, def("flurry").get("moveSpeed", 0.55D));
                        }
                    } else {
                        this.getNavigation().stop();
                    }
                }
                // 1 振りの真ん中(切り上げ)で当てる。切り捨てだと間隔 3 で刃がまだ頭の上にある(画面の 0.17 秒)。
                if (this.actionTicks == Math.max(1, (interval + 1) / 2)) {
                    playSound(ModSounds.FALCIMNES_SLASH.get(), 0.9F, 1.0F + this.flurryIndex * 0.03F);
                    float dmg = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE) * (float) def("flurry").get("damageFactor", 0.3D);
                    for (LivingEntity e : inSweep(FLURRY_REACH + SWEEP_MARGIN, SWEEP_HALF_WIDTH * 0.8D)) {
                        // 間隔が無敵時間(10 tick)より短くなるので、1 振りごとに無敵時間を切る。
                        e.invulnerableTime = 0;
                        if (e.hurt(this.damageSources().mobAttack(this), dmg)) {
                            e.knockback(0.15D, -forward().x, -forward().z);
                        }
                    }
                }
                if (this.actionTicks >= interval) {
                    this.flurryIndex++;
                    if (this.flurryIndex < FLURRY_INTERVALS.length) {
                        startSlash();
                    } else {
                        this.getNavigation().stop();
                        startAction(ACT_FLURRY_X);   // 段 2: 締めの X 字の交差斬りへ
                    }
                }
            }
            case ACT_FLURRY_X -> {
                holdStill();
                if (hasTarget && this.actionTicks < FLURRY_X.executeStart()) turnTo(target, 20.0F);
                if (this.actionTicks == 1) playSound(ModSounds.FALCIMNES_GROWL.get(), 1.2F, 1.1F);
                if (this.actionTicks == FLURRY_X_HIT - 2) playSound(ModSounds.FALCIMNES_SLASH.get(), 1.2F, 0.8F);
                if (this.actionTicks == FLURRY_X_HIT) {
                    float dmg = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE) * (float) def("flurry").get("finishFactor", 1.0D);
                    for (LivingEntity e : inSweep(FLURRY_REACH + SWEEP_MARGIN, SWEEP_HALF_WIDTH)) {
                        e.invulnerableTime = 0;
                        if (e.hurt(this.damageSources().mobAttack(this), dmg)) {
                            e.knockback(0.6D, -forward().x, -forward().z);
                        }
                    }
                }
                if (this.actionTicks >= FLURRY_X.total()) startAction(ACT_FLURRY_END);
            }
            case ACT_KIRIAGE_R, ACT_KIRIAGE_L, ACT_TSUKIKAKI, ACT_KOSA, ACT_FUMIKOMI_R, ACT_FUMIKOMI_L, ACT_KOZUKI_R, ACT_KOZUKI_L ->
                    tickSmallMove(getAction(), target, hasTarget);
            case ACT_GRAB_WINDUP -> {
                holdStill();
                // 最後の 0.5 秒は向きを固定する —— 突進の向きが見えてから横へ逃げられる。
                if (hasTarget && this.actionTicks <= GRAB_TRACK_UNTIL) turnTo(target, 12.0F);
                if (this.actionTicks == GRAB_ROAR_TICK) {
                    this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                            ModSounds.FALCIMNES_ROAR.get(), SoundSource.HOSTILE, 3.0F, 1.0F);
                }
                if (this.actionTicks >= GRAB_WINDUP_TICKS) {
                    this.dashDir = forward();
                    playSound(ModSounds.FALCIMNES_SCREECH.get(), 1.6F, 1.0F);
                    startAction(ACT_GRAB_DASH);
                }
            }
            case ACT_GRAB_DASH -> tickDash();
            case ACT_GRAB_HOLD -> tickHold();
            case ACT_GRAB_FINISH -> tickFinish();
            case ACT_WAVE_R, ACT_WAVE_L -> {
                holdStill();
                // 前触れの頭で低く唸る(光の筋が灯り始める —— 描画)。放つ瞬間に風切り。向きは 0.85 秒のポーズブレイクで決まる。
                if (this.actionTicks == 1) playSound(ModSounds.FALCIMNES_GROWL.get(), 1.2F, 0.8F);
                if (hasTarget && this.actionTicks <= WAVE_TRACK_UNTIL) turnTo(target, 20.0F);
                if (this.actionTicks == WAVE_RELEASE_TICK) releaseWave(hasTarget ? target : null);
                if (this.actionTicks >= WAVE.total()) endAction();
            }
            case ACT_RIFT_WINDUP -> {
                holdStill();
                if (this.actionTicks == 1) playSound(ModSounds.FALCIMNES_GROWL.get(), 1.5F, 0.7F);
                if (hasTarget && this.actionTicks <= RIFT_TRACK_UNTIL) turnTo(target, 20.0F);
                if (this.actionTicks >= RIFT_STAB_TICK) {
                    stabRift(hasTarget ? target : null);
                    startAction(ACT_RIFT_HOLD);
                }
            }
            case ACT_RIFT_HOLD -> {
                holdStill();
                if (this.actionTicks >= this.riftHoldTicks) startAction(ACT_RIFT_RECOVER);
            }
            case ACT_RIFT_RECOVER -> {
                holdStill();
                if (this.actionTicks >= RIFT_RECOVER_TICKS) endAction();
            }
            case ACT_DODGE, ACT_DODGE_L, ACT_DODGE_R -> {
                if (this.actionTicks >= DODGE_TICKS) endAction();
            }
            case ACT_THREAT -> {
                holdStill();
                if (hasTarget) turnTo(target, 10.0F);
                if (this.actionTicks == THREAT_ROAR_TICK) {
                    this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                            ModSounds.FALCIMNES_ROAR.get(), SoundSource.HOSTILE, 3.0F, 1.0F);
                }
                if (this.actionTicks >= THREAT_TICKS) endAction();
            }
            case ACT_RECOVER -> {
                holdStill();
                if (this.actionTicks >= this.recoverTicks) endAction();
            }
            case ACT_FLURRY_END -> {
                holdStill();
                if (this.actionTicks >= FLURRY_END_TICKS) endAction();
            }
            case ACT_THREAT_END -> {
                holdStill();
                if (this.actionTicks >= THREAT_END_TICKS) endAction();
            }
            case ACT_IDLE -> {
                holdStill();
                if (hasTarget) {
                    interruptIdle();
                } else if (this.actionTicks >= IDLE_TICKS[idleKind()]) {
                    endAction();
                }
            }
            case ACT_GROOM_END -> {
                holdStill();
                if (this.actionTicks >= GROOM_END_TICKS) endAction();
            }
            default -> endAction();
        }
    }

    // ------------------------------------------------------------------ 待機の仕草(2026-09-24)

    /**
     * 標的がいなくて、地面の上で立ち止まっている(歩いていない・回っていない・殴られていない・燃えていない・唸っていない)のが
     * {@link #IDLE_MIN_STILL} tick 続いたら、
     * 1 tick ごとに 1/{@link #IDLE_CHANCE} で待機の仕草を出す(見回し 3 割・あくび 2 割・翅の身震い 2 割・鎌の手入れ 3 割。仮決め)。
     * AI を止めた個体(GameTest)は出さない。
     */
    private void tickIdleFidget() {
        boolean calm = !isActing() && this.getTarget() == null && !this.isNoAi() && this.onGround() && !this.isInWater()
                && this.getNavigation().isDone() && Math.abs(getTurnRate()) < 0.5F && !this.isVehicle() && !this.isPassenger()
                && this.hurtTime == 0 && !this.isOnFire() && this.tickCount >= this.growlQuietUntil;
        if (!calm) {
            this.stillTicks = 0;
            return;
        }
        if (++this.stillTicks >= IDLE_MIN_STILL && this.random.nextInt(IDLE_CHANCE) == 0) {
            int r = this.random.nextInt(10);
            int kind = r < 3 ? (this.random.nextBoolean() ? IDLE_LOOK_R : IDLE_LOOK_L)
                    : r < 5 ? IDLE_GAPE
                    : r < 7 ? IDLE_SHAKE
                    : (this.random.nextBoolean() ? IDLE_GROOM_R : IDLE_GROOM_L);
            startIdle(kind);
        }
    }

    /** 待機の仕草を始める(GameTest からも呼ぶ)。 */
    public void startIdle(int kind) {
        this.stillTicks = 0;
        this.getNavigation().stop();
        this.entityData.set(DATA_IDLE_KIND, kind);
        startAction(ACT_IDLE);
    }

    public int idleKind() {
        return this.entityData.get(DATA_IDLE_KIND);
    }

    static boolean isGroom(int kind) {
        return kind == IDLE_GROOM_R || kind == IDLE_GROOM_L;
    }

    /**
     * 待機の仕草をやめる(標的が現れた・殴られた)。鎌の手入れで鎌を持ち上げているなら {@link #ACT_GROOM_END} で下ろし、
     * ほかはそのまま次の動作へ(首と頭だけの仕草なので、補間で直に移っても当たらない —— 検算済み)。
     */
    private void interruptIdle() {
        int t = this.actionTicks - TRANSITION_TICKS;
        if (isGroom(idleKind()) && t >= GROOM_RAISED_FROM && t < GROOM_RAISED_UNTIL) {
            startAction(ACT_GROOM_END);
        } else {
            endAction();
        }
    }

    /**
     * 小さな通常技(段 2、11-07 夜)を 1 tick 進める。どれも前触れの間は標的へ向き直り(実行の 4 tick 前まで —— 向きが決まってから避けられる)、
     * 当たりの tick に前方の扇で斬る。技ごとの付け足し(数値は actions.json、すべて【仮】):
     * <ul>
     *   <li>切り上げ: 打ち上げ({@code lift})。盾を構えていたら盾を弾く({@code shieldBreak} tick 使えない)。</li>
     *   <li>突き掻き: 1〜2 ブロック引き寄せる({@code pull})。</li>
     *   <li>交差斬り: 幅広いが届く距離は短い(扇の形だけ)。</li>
     *   <li>踏み込み斬り: 前触れの間、鎌の側へ横に踏み出して回り込む({@code sidestep} ブロック/tick)。</li>
     *   <li>小突き: 弱いが速い。</li>
     * </ul>
     */
    private void tickSmallMove(int act, @Nullable LivingEntity target, boolean hasTarget) {
        ActionTiming tm;
        int hit;
        double reach, width;
        String id;
        switch (act) {
            case ACT_KIRIAGE_R, ACT_KIRIAGE_L -> { tm = KIRIAGE; hit = KIRIAGE_HIT; reach = KIRIAGE_REACH; width = KIRIAGE_WIDTH; id = "kiriage"; }
            case ACT_TSUKIKAKI -> { tm = TSUKIKAKI; hit = TSUKIKAKI_HIT; reach = TSUKI_REACH; width = TSUKI_WIDTH; id = "tsukikaki"; }
            case ACT_KOSA -> { tm = KOSA; hit = KOSA_HIT; reach = KOSA_REACH; width = KOSA_WIDTH; id = "kosa"; }
            case ACT_FUMIKOMI_R, ACT_FUMIKOMI_L -> { tm = FUMIKOMI; hit = FUMIKOMI_HIT; reach = FUMI_REACH; width = FUMI_WIDTH; id = "fumikomi"; }
            default -> { tm = KOZUKI; hit = KOZUKI_HIT; reach = KOZUKI_REACH; width = KOZUKI_WIDTH; id = "kozuki"; }
        }
        ActionDef d = def(id);
        boolean fumi = act == ACT_FUMIKOMI_R || act == ACT_FUMIKOMI_L;
        if (!fumi || this.actionTicks < FUMIKOMI_STEP_FROM || this.actionTicks > FUMIKOMI_STEP_UNTIL) {
            holdStill();
        }
        if (hasTarget && this.actionTicks <= tm.executeStart() - 4) turnTo(target, 30.0F);
        if (this.actionTicks == 1 && act != ACT_KOZUKI_R && act != ACT_KOZUKI_L) playSound(ModSounds.FALCIMNES_GROWL.get(), 0.9F, 1.2F);
        if (fumi && this.actionTicks >= FUMIKOMI_STEP_FROM && this.actionTicks <= FUMIKOMI_STEP_UNTIL) {
            // 鎌の側(右の鎌 = 本体の左 = left())へ横に踏み出して回り込む。向きは標的へ向け続ける(上の turnTo)。
            Vec3 side = act == ACT_FUMIKOMI_R ? left() : left().scale(-1.0D);
            double sp = d.get("sidestep", 0.16D);
            Vec3 v = this.getDeltaMovement();
            this.setDeltaMovement(side.x * sp, v.y, side.z * sp);
            this.hasImpulse = true;
        }
        if (this.actionTicks == hit - 2) playSound(ModSounds.FALCIMNES_SLASH.get(), 0.9F, act == ACT_KOZUKI_R || act == ACT_KOZUKI_L ? 1.4F : 1.1F);
        if (this.actionTicks == hit) {
            float dmg = d.damage() > 0 ? (float) d.damage() : (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE);
            // 段 3: 1 段目の結果(どれか 1 体に通れば当たり、通らず盾で受けられたら防がれた、誰もいなければ外れ)
            this.comboResult = FalcimnesPersonality.RESULT_MISSED;
            for (LivingEntity e : inSweep(SWEEP_REACH * reach + SWEEP_MARGIN, SWEEP_HALF_WIDTH * width)) {
                boolean blocking = e instanceof Player p && p.isBlocking();
                if (!e.hurt(this.damageSources().mobAttack(this), dmg)) {
                    if (blocking && this.comboResult != FalcimnesPersonality.RESULT_HIT) this.comboResult = FalcimnesPersonality.RESULT_BLOCKED;
                    // 盾で受けられた(切り上げは盾を弾く)
                    if (blocking && id.equals("kiriage") && e instanceof Player p) {
                        p.getCooldowns().addCooldown(p.getUseItem().getItem(), (int) d.get("shieldBreak", 40.0D));
                        p.stopUsingItem();
                    }
                    continue;
                }
                this.comboResult = FalcimnesPersonality.RESULT_HIT;
                switch (id) {
                    case "kiriage" -> {
                        Vec3 v = e.getDeltaMovement();
                        e.setDeltaMovement(v.x, Math.max(v.y, d.get("lift", 0.5D)), v.z);
                        e.hurtMarked = true;
                    }
                    case "tsukikaki" -> {
                        // 引き寄せる(体の正面の、鎌の届く距離の半分ほどの所へ向けて)
                        Vec3 to = this.position().add(forward().scale(SWEEP_REACH * this.bodyScale() * 0.45D)).subtract(e.position());
                        double len = Math.sqrt(to.x * to.x + to.z * to.z);
                        if (len > 1e-3) {
                            double pull = d.get("pull", 0.6D);
                            e.setDeltaMovement(to.x / len * pull, Math.max(e.getDeltaMovement().y, 0.15D), to.z / len * pull);
                            e.hurtMarked = true;
                        }
                    }
                    default -> e.knockback(0.3D, -forward().x, -forward().z);
                }
            }
        }
        // 段 3: 隙の途中(comboCut)で 2 段目へ。2 段目の前触れが 1 段目の終わり際に見える。
        if (this.actionTicks == comboCutTick(tm, id) && tryCombo(id, target, hasTarget)) return;
        if (this.actionTicks >= tm.total()) endAction();
    }

    /**
     * 連続技で 2 段目へ切り替える tick(隙の頭から、技の comboCut の割合。無ければ personality の comboCut)。技ごとの値は、
     * 2 段目の頭(休めの鎌)へ 4 tick で補間しても鎌が地面・首・脚を通らない一番早い所(FalcCheck の -Dfalc.comboscan で探した)。
     */
    static int comboCutTick(ActionTiming tm, String id) {
        int rec = tm.total() - tm.recoverStart();
        return tm.recoverStart() + (int) Math.round(rec * def(id).get("comboCut", pv("comboCut", 0.35D)));
    }

    /** 小さな技の名前 → 動作(片方の鎌の技は左右を交互に)。小さな技でなければ -1。 */
    int smallMoveAction(String id) {
        return switch (id) {
            case "kiriage" -> flipSide() ? ACT_KIRIAGE_L : ACT_KIRIAGE_R;
            case "tsukikaki" -> ACT_TSUKIKAKI;
            case "kosa" -> ACT_KOSA;
            case "fumikomi" -> flipSide() ? ACT_FUMIKOMI_L : ACT_FUMIKOMI_R;
            case "kozuki" -> flipSide() ? ACT_KOZUKI_L : ACT_KOZUKI_R;
            default -> -1;
        };
    }

    /**
     * 連続技(段 3、決定「小さな技を組み合わせて最大 2 段。2 段目は 1 段目の結果で決める」)。1 段目の技の extra
     * {@code next.<hit|blocked|missed>.<技>} の重み × 性格の掛け数から 2 段目を引く。続けるかは {@code comboChance}(攻撃性で上げ、
     * 慎重さで下げ、1 段目の結果の倍率 comboOnHit・comboOnBlocked・comboOnMissed を掛ける。すべて【仮】)。2 段目は届く間合いの技だけ。同じ技へは続けない(表に無い)。
     */
    private boolean tryCombo(String id, @Nullable LivingEntity target, boolean hasTarget) {
        if (this.comboStep > 0 || !hasTarget || this.comboResult == FalcimnesPersonality.RESULT_NONE) return false;
        double chance = this.comboChanceOverride >= 0.0D ? this.comboChanceOverride
                : pv("comboChance", 0.35D) * FalcimnesPersonality.tilt(pv("comboAggressive", 2.2D), aggression())
                * FalcimnesPersonality.tilt(pv("comboCautious", 0.7D), caution())
                * pv(switch (this.comboResult) {   // 続けるかも 1 段目の結果で(決定)。防がれたら崩しに行き、外れたら引きやすい
                    case FalcimnesPersonality.RESULT_HIT -> "comboOnHit";
                    case FalcimnesPersonality.RESULT_BLOCKED -> "comboOnBlocked";
                    default -> "comboOnMissed";
                }, 1.0D);
        if (this.random.nextDouble() >= Math.min(0.95D, chance)) return false;
        String prefix = "next." + FalcimnesPersonality.resultKey(this.comboResult) + ".";
        double s = bodyScale();
        double d = Math.sqrt(this.distanceToSqr(target.getX(), this.getY(), target.getZ()));
        double reach = SWEEP_REACH * s + target.getBbWidth() * 0.5D;
        if (Math.abs(target.getY() - this.getY()) >= 3.0D * s) return false;
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.List<Double> ws = new java.util.ArrayList<>();
        double sum = 0.0D;
        for (var e : def(id).extra().entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            String next = e.getKey().substring(prefix.length());
            if (next.equals(id) || smallMoveAction(next) < 0) continue;
            ActionDef nd = def(next);
            if (!ActionPicker.inRange(nd, d, this.getScale(), reach)) continue;
            double w = e.getValue() * personalityWeight(nd);
            if (w <= 0.0D) continue;
            ids.add(next);
            ws.add(w);
            sum += w;
        }
        if (ids.isEmpty()) return false;
        double r = this.random.nextDouble() * sum;
        String next = ids.get(ids.size() - 1);
        for (int i = 0; i < ids.size(); i++) {
            r -= ws.get(i);
            if (r < 0.0D) {
                next = ids.get(i);
                break;
            }
        }
        startAction(smallMoveAction(next));
        this.comboStep = 1;
        return true;
    }

    private void startSlash() {
        int interval = FLURRY_INTERVALS[this.flurryIndex];
        this.entityData.set(DATA_SLASH_TICKS, interval);
        startAction(this.flurryIndex % 2 == 0 ? ACT_FLURRY_R : ACT_FLURRY_L);
    }

    private void tickDash() {
        // 向きは突進を始めた向きのまま(dashDir は始めた瞬間の正面)。よその希望で回らないよう、同じ向きを動作の希望として出す。
        wantFacing((float) (Mth.atan2(this.dashDir.z, this.dashDir.x) * Mth.RAD_TO_DEG) - 90.0F, TURN_ACTION);
        Vec3 v = this.getDeltaMovement();
        this.setDeltaMovement(this.dashDir.x * def("grab").get("dashSpeed", 0.95D), v.y, this.dashDir.z * def("grab").get("dashSpeed", 0.95D));
        this.hasImpulse = true;

        LivingEntity caught = findGrabbable();
        if (caught != null) {
            if (!grab(caught)) {
                // 持ち上げられない相手(大きすぎる・ボスなど)は突き飛ばすだけ。
                this.doHurtTarget(caught);
                this.setDeltaMovement(Vec3.ZERO);
                recover(GRAB_END_TICKS);
            }
            return;
        }
        if ((this.horizontalCollision && this.actionTicks > 3) || this.actionTicks >= GRAB_DASH_MAX_TICKS) {
            this.setDeltaMovement(Vec3.ZERO.add(0.0D, this.getDeltaMovement().y, 0.0D));
            // 壁に突っ込んだら長めに固まる。
            recover(this.horizontalCollision ? 40 : GRAB_END_TICKS + 2);
        }
    }

    private void tickHold() {
        holdStill();
        LivingEntity victim = heldVictim();
        if (victim == null) {
            return;
        }
        int t = this.actionTicks;
        ActionDef g = def("grab");
        int bitesWanted = Math.max(0, (int) Math.round(g.get("bites", 3.0D)));
        // 噛みつきを決めた回数だけ(1 秒ごと、ループの 0.5 秒)。その次のループの頭(挟む構え)で止めへ移る —— 止めのアニメの 0 秒目と同じ姿勢。
        if (t >= LEAD + BITE_INTERVAL * bitesWanted) {
            startAction(ACT_GRAB_FINISH);
            return;
        }
        if (t >= BITE_FIRST && (t - BITE_FIRST) % BITE_INTERVAL == 0) {
            playSound(ModSounds.FALCIMNES_BITE.get(), 1.2F, 0.9F + this.random.nextFloat() * 0.2F);
            victim.invulnerableTime = 0;
            victim.hurt(ModDamageTypes.falcimnes((ServerLevel) this.level(), ModDamageTypes.FALCIMNES_BITE, this),
                    (float) g.get("bite", 8.0D));
            this.bites++;
            if (this.level() instanceof ServerLevel server) {
                Vec3 p = victim.position().add(0.0D, victim.getBbHeight() * 0.6D, 0.0D);
                server.sendParticles(ParticleTypes.DAMAGE_INDICATOR, p.x, p.y, p.z, 4, 0.2D, 0.2D, 0.2D, 0.1D);
            }
        }
    }

    /**
     * 掴みの止め(11-05、ユーザー決定「即死をやめて特大ダメージ」)。首を反らせて大きく口を開け、{@link #FINISH_BITE_TICK} に叩きつけるように噛む
     * (ダメージ = {@code actions.json} の grab の damage、仮 30。<b>防具は効く</b>。盾は掴まれていて構えられない)。噛んだまま首を振り、
     * 終わりに放す。生き延びた相手(防具・トーテム・クリエイティブ)はここで離れる。
     */
    private void tickFinish() {
        holdStill();
        LivingEntity victim = heldVictim();
        if (victim == null) {
            return;
        }
        if (this.actionTicks == FINISH_BITE_TICK) {
            playSound(ModSounds.FALCIMNES_BITE.get(), 1.8F, 0.65F);
            victim.invulnerableTime = 0;
            victim.hurt(ModDamageTypes.falcimnes((ServerLevel) this.level(), ModDamageTypes.FALCIMNES_EXECUTION, this),
                    (float) def("grab").damage());
            if (this.level() instanceof ServerLevel server) {
                Vec3 p = victim.position().add(0.0D, victim.getBbHeight() * 0.6D, 0.0D);
                server.sendParticles(ParticleTypes.DAMAGE_INDICATOR, p.x, p.y, p.z, 10, 0.3D, 0.3D, 0.3D, 0.15D);
            }
            return;
        }
        if (this.actionTicks >= FINISH_TICKS) {
            releasePrey(false);
        }
    }

    /**
     * 掴んでいる獲物。殺した・獲物が消えたなら、死体を顎の前に吊ったまま歩かないよう落として鎌を下ろし({@code grab_end})、null。
     */
    @Nullable
    private LivingEntity heldVictim() {
        Entity prey = this.getFirstPassenger();
        if (prey instanceof LivingEntity victim && victim.isAlive()) {
            return victim;
        }
        this.releasing = true;
        this.ejectPassengers();
        this.releasing = false;
        endHold();
        recover(GRAB_END_TICKS);
        return null;
    }

    /**
     * 飛ぶ斬撃を放つ(11-05)。向きは体の正面(0.85 秒のポーズブレイクで決まっている)。高さは相手の胸(体の高さ 1〜3.5 の間に切る)。
     * 出す所は体の前 3.5 ブロック(鎌の先は 7 ブロック前まで届くが、近くの相手の後ろから出ないように)。
     */
    private void releaseWave(@Nullable LivingEntity target) {
        if (!(this.level() instanceof ServerLevel server)) return;
        ActionDef w = def("wave");
        Vec3 f = forward();
        double y = target != null ? target.getY() + target.getBbHeight() * 0.6D : this.getY() + 2.0D;
        y = Mth.clamp(y, this.getY() + 1.0D, this.getY() + 3.5D);
        Vec3 pos = new Vec3(this.getX() + f.x * 3.5D, y, this.getZ() + f.z * 3.5D);
        FalcimnesWaveEntity.shoot(server, this, pos, f, w.get("speed", 1.0D), w.get("range", 16.0D), (float) w.damage(),
                (float) w.get("width", 3.0D), (float) w.get("height", 0.5D));
        server.playSound(null, pos.x, pos.y, pos.z, ModSounds.FALCIMNES_WAVE.get(), SoundSource.HOSTILE, 2.0F, 1.0F);
    }

    /**
     * 鎌を地面に刺して、光の筋を走らせる(11-05)。刺した所は体の前 {@link #RIFT_STAB_FORWARD} × 体格。筋は相手の今の足元へまっすぐ、
     * その {@code overshoot} 先まで(最大 {@code lineMax})。相手が居なければ正面へ最大の長さ。刺したまま待つ長さは帯が消えるまで。
     */
    private void stabRift(@Nullable LivingEntity target) {
        if (!(this.level() instanceof ServerLevel server)) return;
        ActionDef r = def("rift");
        Vec3 f = forward();
        double fwd = RIFT_STAB_FORWARD * this.bodyScale();
        Vec3 start = new Vec3(this.getX() + f.x * fwd, this.getY(), this.getZ() + f.z * fwd);
        double speed = r.get("lineSpeed", 1.2D);
        int delay = (int) Math.round(r.get("bandDelay", 12.0D)), band = (int) Math.round(r.get("bandTicks", 8.0D));
        // 11-05 夜(ユーザー指定): 刺した所から前へ burstUnder 未満の相手(筋が届かない近さ)には、刺した所を中心に円く噴き出す。
        if (target != null && (target.getX() - start.x) * f.x + (target.getZ() - start.z) * f.z < r.get("burstUnder", 2.0D)) {
            double radius = r.get("burstRadius", 4.0D);
            FalcimnesRiftEntity.spawnBurst(server, this, start, this.getYRot(), radius, speed, delay, band,
                    r.get("bandHeight", 3.0D), (float) r.damage(), r.get("lift", 0.6D));
            this.riftHoldTicks = FalcimnesRiftEntity.bandEndTicks(radius, speed, delay, band);
            riftStabEffects(server, start);
            return;
        }
        double lineMax = r.get("lineMax", 18.0D);
        Vec3 dir = f;
        double length = lineMax;
        if (target != null) {
            Vec3 to = new Vec3(target.getX() - start.x, 0.0D, target.getZ() - start.z);
            double dist = to.length();
            if (dist > 1.0D) {
                dir = to.scale(1.0D / dist);
                // 体の正面から 60 度を超えて横へは曲げない(刺した鎌の向きのまま)。
                if (dir.dot(f) < 0.5D) dir = f;
            }
            length = Math.min(lineMax, Math.max(2.0D, dist + r.get("overshoot", 2.0D)));
        }
        float yaw = (float) (Mth.atan2(dir.z, dir.x) * Mth.RAD_TO_DEG) - 90.0F;
        FalcimnesRiftEntity.spawn(server, this, start, yaw, length, r.get("width", 1.5D), speed, delay, band,
                r.get("bandHeight", 3.0D), (float) r.damage(), r.get("lift", 0.6D));
        this.riftHoldTicks = FalcimnesRiftEntity.bandEndTicks(length, speed, delay, band);
        riftStabEffects(server, start);
    }

    /** 刺した瞬間の音と土。 */
    private void riftStabEffects(ServerLevel server, Vec3 start) {
        server.playSound(null, start.x, start.y, start.z, ModSounds.FALCIMNES_RIFT_STAB.get(), SoundSource.HOSTILE, 2.0F, 1.0F);
        server.playSound(null, start.x, start.y, start.z, ModSounds.FALCIMNES_RIFT_RUMBLE.get(), SoundSource.HOSTILE, 2.5F, 1.0F);
        server.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK,
                        this.level().getBlockState(BlockPos.containing(start.x, start.y - 0.5D, start.z))),
                start.x, start.y + 0.1D, start.z, 24, 0.6D, 0.1D, 0.6D, 0.15D);
    }

    /**
     * {@code prey} を掴み上げて噛みつきへ移る。持ち上げられない相手なら false(何もしない)。
     * 突進が当たったときに呼ぶ。GameTest からも直接呼ぶ。
     */
    public boolean grab(LivingEntity prey) {
        if (!canCarry(prey) || !prey.startRiding(this, true)) {
            return false;
        }
        this.holdDamage = 0.0F;
        this.releasing = false;
        this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);
        playSound(ModSounds.FALCIMNES_BITE.get(), 1.2F, 0.8F);
        startAction(ACT_GRAB_HOLD);
        return true;
    }

    /**
     * 確かめ用(11-05): {@code /data merge entity <この個体> {OvForceAction:<動作の番号>}} で次の tick にその動作を始める
     * ({@link #forceAction} と同じ。runClaudeClient で技を 1 つずつ撮るため)。{@code /data get} では今の動作が {@code OvAction} に出る。
     * 動作そのものは保存しない(読み込み直したら何もしていない状態から)ので、普段の保存には影響しない。
     */
    private int pendingForce = -1;

    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("OvAction", getAction());
        if (this.personalitySet) {
            net.minecraft.nbt.CompoundTag p = new net.minecraft.nbt.CompoundTag();
            p.putFloat("caution", caution());
            p.putFloat("aggression", aggression());
            p.putFloat("flank", flank());
            tag.put("OvPersonality", p);
        }
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("OvForceAction")) {
            this.pendingForce = tag.getInt("OvForceAction");
        }
        // 段 3: 個体の性格。確かめ用に /data merge entity <個体> {OvPersonality:{caution:1f,aggression:0f,flank:0.5f}} でも置ける(書いた値だけ変わる)
        if (tag.contains("OvPersonality")) {
            net.minecraft.nbt.CompoundTag p = tag.getCompound("OvPersonality");
            setPersonality(p.contains("caution") ? p.getFloat("caution") : caution(),
                    p.contains("aggression") ? p.getFloat("aggression") : aggression(),
                    p.contains("flank") ? p.getFloat("flank") : flank());
        }
    }

    /** GameTest 用: 連続技に続ける確率(0〜1)。負なら設計データと性格から。 */
    public void setComboChanceForTest(double chance) {
        this.comboChanceOverride = chance;
    }

    /** 連続技の何段目か(GameTest 用)。 */
    public int comboStep() {
        return this.comboStep;
    }

    /** 動作をいますぐ始める(GameTest・デバッグ用。溜めや間合いの条件は見ない)。 */
    public void forceAction(int action) {
        startAction(action);
    }

    /** 獲物を離す。{@code shaken} は振りほどかれたとき(長めの隙)。 */
    private void releasePrey(boolean shaken) {
        Entity prey = this.getFirstPassenger();
        this.releasing = true;
        this.ejectPassengers();
        this.releasing = false;
        if (prey != null) {
            Vec3 f = forward();
            prey.setDeltaMovement(f.x * 0.5D, 0.35D, f.z * 0.5D);
            prey.hurtMarked = true;
        }
        endHold();
        recover(shaken ? 30 : GRAB_END_TICKS);
    }

    private void endHold() {
        this.holdDamage = 0.0F;
        this.bites = 0;
        // 放した直後に横薙ぎを重ねない(前は「攻撃の間 20 tick」)。
        this.picker.setCooldown("sweep", Math.max(this.picker.cooldown("sweep"), 20));
    }

    // ------------------------------------------------------------------ 判定

    /** 前方の扇に入っている、斬ってよい相手。 */
    private List<LivingEntity> inSweep(double reach, double halfWidth) {
        double s = this.bodyScale();
        Vec3 f = forward();
        Vec3 r = new Vec3(-f.z, 0.0D, f.x);
        List<LivingEntity> out = new ArrayList<>();
        AABB box = this.getBoundingBox().inflate(reach * s + 1.0D, 3.0D * s, reach * s + 1.0D);
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, box, this::isFoe)) {
            Vec3 d = e.position().subtract(this.position());
            double fw = d.x * f.x + d.z * f.z;
            double lat = Math.abs(d.x * r.x + d.z * r.z);
            double er = e.getBbWidth() * 0.5D;
            double dy = e.getY() - this.getY();
            if (fw < 0.3D || fw > reach * s + er) continue;
            if (lat > halfWidth * s + er) continue;
            if (dy < -1.5D || dy > 3.0D * s) continue;
            out.add(e);
        }
        return out;
    }

    /** 突進の先(鎌の間)に居る、掴める相手。狙っている相手を優先する。 */
    @Nullable
    private LivingEntity findGrabbable() {
        double s = this.bodyScale();
        Vec3 c = this.position().add(forward().scale(PREY_FORWARD * s));
        AABB zone = new AABB(c.x - 1.3D * s, this.getY(), c.z - 1.3D * s,
                c.x + 1.3D * s, this.getY() + 2.8D * s, c.z + 1.3D * s);
        LivingEntity best = null;
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, zone, this::isFoe)) {
            if (e == this.getTarget()) return e;
            if (best == null || e.distanceToSqr(c) < best.distanceToSqr(c)) best = e;
        }
        return best;
    }

    /** 斬ってよい相手か(自分・乗客・汚染側の仲間・クリエイティブ/観戦者は除く)。 */
    private boolean isFoe(LivingEntity e) {
        if (e == this || !e.isAlive() || e.isPassengerOfSameVehicle(this) || e.getVehicle() == this) return false;
        if (e instanceof Player p && (p.isCreative() || p.isSpectator())) return false;
        if (e == this.getTarget()) return true;
        return MobGroup.of(e) == null;
    }

    /** 飛ぶ斬撃・光の帯が当たってよい相手か(横薙ぎと同じ。11-05)。 */
    public boolean isFoeForWave(LivingEntity e) {
        return isFoe(e);
    }

    /** 持ち上げられる相手か。 */
    private static boolean canCarry(LivingEntity e) {
        // 何かに乗っている相手は掴まない: grab は startRiding を force で呼ぶので、別の個体が掴んでいる獲物を奪うと
        // 両方の乗客の表に残ってしまう(レビューで発覚、2026-09-23)。
        return !e.getType().is(Tags.EntityTypes.BOSSES)
                && e.getBbWidth() <= 2.0F && e.getBbHeight() <= 3.0F
                && !e.isVehicle() && !e.isPassenger();
    }

    /** 体の向いている方向(水平の単位ベクトル)。 */
    private Vec3 forward() {
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
    }

    /** 体の左(ファイル +X)。 */
    private Vec3 left() {
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(Mth.cos(yaw), 0.0D, Mth.sin(yaw));
    }

    private float yawTo(Entity target) {
        return (float) (Mth.atan2(target.getZ() - this.getZ(), target.getX() - this.getX()) * Mth.RAD_TO_DEG) - 90.0F;
    }

    /** 標的の方向の、正面からのずれ(度、+ が右 = yaw が増える側)。 */
    private float offFront(Entity target) {
        return Mth.wrapDegrees(yawTo(target) - this.getYRot());
    }

    /**
     * 動作の最中に標的へ向く。回る速さは旋回の上限({@link #MAX_TURN_PER_TICK})まで —— {@code maxStep} は以前の動作ごとの上限で、
     * どれも上限(7)以上だったので、いまは上限だけで決まる(引数は動作の意図の記録として残す)。
     */
    private void turnTo(Entity target, float maxStep) {
        wantFacing(yawTo(target), TURN_ACTION);
        this.getLookControl().setLookAt(target, 30.0F, 30.0F);
    }

    /**
     * {@code target} まで {@code ideal} ブロックより遠ければ、{@code speed}(体格 1.0 のとき)で踏み込む。
     * 振りかぶりの最中に使う —— 移動入力は止めてあるので速度で直接動かす。<b>進むのは体の正面へだけ</b>(向きのずれの cos 倍、
     * 2026-09-24)。以前は標的へまっすぐ滑らせていて、向きがずれていると体が横へ動いていた(脚は前へ歩くアニメしか持たない)。
     */
    private void stepToward(Entity target, double ideal, double speed) {
        Vec3 d = target.position().subtract(this.position());
        d = new Vec3(d.x, 0.0D, d.z);
        double dist = d.length();
        Vec3 v = this.getDeltaMovement();
        if (dist > ideal && dist > 1.0E-3D) {
            double ahead = Math.max(0.0D, Mth.cos(offFront(target) * Mth.DEG_TO_RAD));
            double step = Math.min(speed * this.bodyScale(), dist - ideal) * ahead;
            Vec3 m = forward().scale(step);
            this.setDeltaMovement(m.x, v.y, m.z);
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
     * 向きを 1 tick 進める(スクティムネス・機関砲台と同じ作り)。角速度を「行きたい速さ」(残り角度を ±{@link #MAX_TURN_PER_TICK} で
     * 切ったもの)へ {@link #TURN_SMOOTHING} ずつ寄せ、残り角度より大きくは進めない。希望が無ければ止まる。回した結果を
     * {@code yRot} = 体 = 頭へ書き戻す —— この tick の間によそが書いた向きはここで消える。
     * <b>動作の最中は、動作自身の希望({@link #TURN_ACTION})でしか回らない</b>(水から上がる Goal などの移動の希望で、突進や噛みつきの
     * 途中に体が回らないように)。
     */
    private void tickTurn() {
        if (Float.isNaN(this.heading)) {
            this.heading = this.getYRot();
        }
        if (this.wantedPriority >= 0 && this.wantedPriority < TURN_ACTION && isActing()) {
            this.wantedPriority = -1;
        }
        float delta = this.wantedPriority >= 0 ? Mth.wrapDegrees(this.wantedYaw - this.heading) : 0.0F;
        float want = Mth.clamp(delta, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
        this.turnSpeed += (want - this.turnSpeed) * TURN_SMOOTHING;
        float step = Math.abs(this.turnSpeed) > Math.abs(delta) ? delta : this.turnSpeed;
        if (Math.abs(this.turnSpeed) < 1.0E-3F && this.wantedPriority < 0) {
            this.turnSpeed = 0.0F;
        }
        this.heading = Mth.wrapDegrees(this.heading + step);
        this.wantedPriority = -1;
        applyFacing();
        this.entityData.set(DATA_TURN, Math.round(step * 20.0F) / 20.0F);
    }

    private void applyFacing() {
        this.setYRot(this.heading);
        this.yBodyRot = this.heading;
        this.yHeadRot = this.heading;
    }

    /** 直前の tick に回った角度(度、+ が右回り)。クライアントでも読める(同期)。 */
    public float getTurnRate() {
        return this.entityData.get(DATA_TURN);
    }

    /** 向きをいますぐ変える(湧かせた直後・GameTest 用)。旋回の上限を通らない。 */
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

    /** バニラの「その場で相手へ向き直る」(1 tick に最大 {@code maxYRotIncrease} 度)も旋回の上限を通す。 */
    @Override
    public void lookAt(Entity entity, float maxYRotIncrease, float maxXRotIncrease) {
        wantFacing(yawTo(entity), TURN_HUNT);
    }

/** その場から動かない(溜め・威嚇・掴み中)。 */
    private void holdStill() {
        this.getNavigation().stop();
        this.setZza(0.0F);
        this.setXxa(0.0F);
    }

    // ------------------------------------------------------------------ 回避と被弾

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!this.level().isClientSide() && tryDodge(source, amount)) {
            return false;
        }
        float before = this.getHealth();
        boolean hurt = super.hurt(source, amount);
        if (hurt && !this.level().isClientSide()) {
            // 殴られたら落ち着くまで待機の仕草を出さない(毒・火・サボテンなどで殴られ続けている間は、仕草が出てすぐ切れるのを繰り返していた)。
            this.stillTicks = 0;
            if (getAction() == ACT_GRAB_HOLD || getAction() == ACT_GRAB_FINISH) {
                this.holdDamage += Math.max(0.0F, before - this.getHealth());
                if (this.holdDamage >= def("grab").get("breakFree", 15.0D) && this.isAlive()) {
                    releasePrey(true);
                }
            } else if (getAction() == ACT_THREAT) {
                startAction(ACT_THREAT_END);
            } else if (getAction() == ACT_IDLE) {
                interruptIdle();
            }
        }
        return hurt;
    }

    /**
     * 適応値が {@link #DODGE_RESISTANCE} 以上の攻撃なら、大きく後ろへ跳んでかわす(当たらなかったことにする)。
     * 掴みの突進中・獲物を掴んでいる間はかわさない(身を投げ出している最中)。
     */
    private boolean tryDodge(DamageSource source, float amount) {
        if (this.dodgeCooldown > 0 || !this.isAlive() || !(this.level() instanceof ServerLevel server)) return false;
        int act = getAction();
        if (act == ACT_GRAB_HOLD || act == ACT_GRAB_FINISH || act == ACT_GRAB_DASH || isDodge(act)) return false;
        // 光の技(飛ぶ斬撃・地走り)は始めたら最後まで(11-05。前触れの間も跳ばない —— 光った技は必ず来る、と読めるように)。
        if (act == ACT_WAVE_R || act == ACT_WAVE_L || act == ACT_RIFT_WINDUP || act == ACT_RIFT_HOLD || act == ACT_RIFT_RECOVER) return false;
        if (committedStrike(act, this.actionTicks)) return false;
        // 段 2(11-07 夜): 連撃の締めと終わり・小さな技は始めたら最後まで(回避しない)【仮】。検算の割り込みの表(FalcCheck#sweeps)もそう見ている。
        if (act == ACT_FLURRY_X || act == ACT_FLURRY_END || isSmallMove(act)) return false;
        if (act == ACT_RECOVER && this.actionTicks < RECOVER_NO_DODGE) return false;
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        if (source.getEntity() == null && source.getDirectEntity() == null) return false;
        // 無敵時間中で、どのみち通らない一撃に回避を使わない。
        if (this.invulnerableTime > 10 && amount <= this.lastHurt) return false;
        if (Adaptation.resistance(server.getServer(), this.getType(), source) < DODGE_RESISTANCE) return false;

        // 向きは変えずに跳ぶ(回る速さの上限)。相手が正面なら後ろへ、右に居れば左へ、左に居れば右へ(背後の相手も横へ)。
        Entity from = source.getEntity() != null ? source.getEntity() : source.getDirectEntity();
        float rel = offFront(from);
        int dodge;
        Vec3 dir;
        if (Math.abs(rel) <= DODGE_BACK_ARC) {
            dodge = ACT_DODGE;
            dir = forward().scale(-1.0D);
        } else if (rel > 0.0F) {
            dodge = ACT_DODGE_L;
            dir = left();
        } else {
            dodge = ACT_DODGE_R;
            dir = left().scale(-1.0D);
        }
        this.entityData.set(DATA_DODGE_HI, armsRaised(act, this.actionTicks, idleKind()));
        this.getNavigation().stop();
        this.setDeltaMovement(dir.x * DODGE_SPEED, DODGE_LIFT, dir.z * DODGE_SPEED);
        this.hasImpulse = true;
        this.hurtMarked = true;
        this.dodgeCooldown = DODGE_COOLDOWN;
        playSound(ModSounds.FALCIMNES_SLASH.get(), 1.0F, 0.6F);
        server.sendParticles(ParticleTypes.POOF, this.getX(), this.getY() + 0.2D, this.getZ(), 12, 0.8D, 0.1D, 0.8D, 0.02D);
        startAction(dodge);
        return true;
    }

    static boolean isDodge(int act) {
        return act == ACT_DODGE || act == ACT_DODGE_L || act == ACT_DODGE_R;
    }

    public boolean isDodging() {
        return isDodge(getAction());
    }
    /** 段 2 の小さな通常技か。 */
    public static boolean isSmallMove(int act) {
        return act >= ACT_KIRIAGE_R && act <= ACT_KOZUKI_L;
    }

    /** 片方の鎌の技の左右を交互に(横薙ぎ・飛ぶ斬撃と同じ合図を使う)。true なら左。 */
    boolean flipSide() {
        boolean l = this.nextSlashLeft;
        this.nextSlashLeft = !l;
        return l;
    }


    /**
     * 振り始めた打ち込み(横薙ぎの振り 〜 下ろし終わり、連撃の 1 振り)の最中か。この間は回避しない(2026-09-24、仮)。
     * 振り抜いた鎌(前下へ伸びきった腕)から回避の構えへは、GeckoLib の 4 tick の補間が地面を 2〜5 通る。打ち込みは止められない、
     * という隙にもなる(横薙ぎの振りかぶりの間・連撃の溜めの間はかわす)。
     */
    static boolean committedStrike(int act, int ticks) {
        // 11-05: 横薙ぎはアニメの 0.6 秒から(前の低いところより上へ振りかぶった後の引き絞り・ポーズブレイク・振り・隙)。
        // 検算の割り込みの掃き出しは 0.2〜0.62 秒を振りかざしの回避で見ている(tools/falcimnes-anim の FalcCheck#sweeps)。
        return ((act == ACT_ATTACK_R || act == ACT_ATTACK_L) && ticks >= TRANSITION_TICKS + 12)
                || act == ACT_FLURRY_R || act == ACT_FLURRY_L;
    }

    /**
     * 鎌を振りかざしているか(回避の首の層のアニメを選ぶ)。0 秒目の鎌の構えを 1 つにすると、振りかざした鎌からの補間と
     * 下ろした鎌からの補間の片方が必ず地面や首を通るので、2 通り用意してある({@code dodge*} / {@code dodge*_hi})。
     * 時刻は各アニメで鎌が前の低いところを通る時刻(tools/falcimnes-anim の {@code raiseFront} / {@code lowerFront})。鎌の手入れと
     * その中断は、鎌が中継({@code PRE})より上にある時刻({@link #GROOM_RAISED_FROM} / {@link #GROOM_END_RAISED})。
     */
    static boolean armsRaised(int act, int ticks, int idleKind) {
        int t = ticks - TRANSITION_TICKS;
        return switch (act) {
            case ACT_IDLE -> isGroom(idleKind) && t >= GROOM_RAISED_FROM && t < GROOM_RAISED_UNTIL;
            case ACT_GROOM_END -> t < GROOM_END_RAISED;
            case ACT_ATTACK_R, ACT_ATTACK_L -> t >= 4;
            case ACT_FLURRY_WINDUP -> t >= 4;
            case ACT_FLURRY_END -> t < 13;
            case ACT_GRAB_WINDUP -> t >= 5;
            case ACT_THREAT -> t >= 7 && t < 56;
            case ACT_THREAT_END -> t < 9;
            case ACT_RECOVER -> t < 15;
            default -> false;
        };
    }

    /** 回避・掴みの前準備などの経過(GameTest 用)。 */
    public int getActionTicks() {
        return this.actionTicks;
    }

    // ------------------------------------------------------------------ 倒れる

    /**
     * 倒れるアニメ(1.5 秒)の間は消さない。バニラの横倒しは描画で切ってある({@code FalcimnesRenderer})。
     * 11-15 ユーザー決定「アニメの後に破裂」: アニメの後に膨らんで破裂し、消すのは {@code corpse/PlantSideBurst}。ここは破裂が外されたときの控え。
     */
    @Override
    protected void tickDeath() {
        this.deathTime++;
        if (this.deathTime >= Math.max(DEATH_TICKS, com.yayuy.overgrownvegetation.spore.PlantSideRules.burstTick(this) + 2) && !this.level().isClientSide() && !this.isRemoved()) {
            this.level().broadcastEntityEvent(this, (byte) 60);
            this.remove(RemovalReason.KILLED);
        }
    }

    @Override
    public void die(DamageSource source) {
        this.releasing = true;
        super.die(source);
        // 倒れる演出(20 tick)の間ぶら下げたままにしない。
        this.ejectPassengers();
    }

    // ------------------------------------------------------------------ 移動・乗客

    @Override
    public void travel(Vec3 input) {
        switch (getAction()) {
            case ACT_NONE, ACT_FLURRY_R, ACT_FLURRY_L -> super.travel(input);
            // 突進・回避は速度だけで動く。それ以外はその場に留まる。
            default -> super.travel(Vec3.ZERO);
        }
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float partialTick) {
        double s = this.bodyScale();
        double up = PREY_UP * s - passenger.getBbHeight() * 0.5D + passenger.getVehicleAttachmentPoint(this).y;
        return new Vec3(0.0D, up, PREY_FORWARD * s).yRot(-this.getYRot() * Mth.DEG_TO_RAD);
    }

    /** 掴まれている獲物に操縦させない(ゾンビを掴むとゾンビがこちらを歩かせてしまう)。 */
    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        return null;
    }

    /** 掴まれたプレイヤーがこちらを殴れるように(乗り物は普通は狙えない)。 */
    @Override
    public boolean canRiderInteract() {
        return true;
    }

    /** 掴まれた獲物は座らせない(ぶら下がっている)。 */
    @Override
    public boolean shouldRiderSit() {
        return false;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return this.getPassengers().isEmpty();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(Entity entity) {
    }

    /**
     * 首と鎌が当たり判定の外へ長く伸びているので、描画の打ち切りは広めに。上にも広げる —— 掴みの前準備で振りかざした鎌は
     * 足元から 8.2 ブロック(体格 1.8)に届き、見上げると本体の箱が画面の外に出て全体が消えていた。
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        // 11-08: 当たりの箱を胴の芯へ細くしたので、箱から広げるのをやめて位置から取る(広さは前の 2.97 × 3.06 の箱から広げたのと同じ)
        double s = this.bodyScale();
        double half = 4.325D * s;
        return new AABB(this.getX() - half, this.getY() - 3.0D * s, this.getZ() - half,
                this.getX() + half, this.getY() + 4.7D * s, this.getZ() + half);
    }

    @Override
    public int getMaxHeadXRot() {
        return 25;
    }

    // ------------------------------------------------------------------ 追跡と行動の選択

    /**
     * 相手が居る間(または何かの動作中)は常に動いている Goal。動作中は何もしない ——
     * 動作は {@link #tickAction} が動かしている。この Goal が MOVE / LOOK を握っているので、
     * 溜めや掴みの最中にぶらつき・見回しの Goal が割り込まない。
     *
     * <p>JUMP は握らない(2026-09-24)。優先度 0 の {@code FloatGoal} が JUMP を取り合うと、水に浸かった瞬間にこの Goal を止め、
     * 水から出るまで再開できない —— その間は追わず・斬らず、空いた MOVE / LOOK でぶらつき・見回しの Goal が動いていた。
     * JUMP を使う Goal はほかに無い(段差の飛び乗りは Goal のフラグと関係ない)ので、浮く Goal と同時に動かす。
     */
    static final class HuntGoal extends net.minecraft.world.entity.ai.goal.Goal {
        private final FalcimnesEntity mob;
        private int repath;
        /** 届かない相手へ、経路の端で止まったまま同じマス同士の探索をやり直さない(2026-09-24 軽量化、報告 354。スクティムネスと同じ)。 */
        private final PathRetryMemo pathMemo = new PathRetryMemo();

        HuntGoal(FalcimnesEntity mob) {
            this.mob = mob;
            this.setFlags(java.util.EnumSet.of(Flag.MOVE, Flag.LOOK));
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
            if (target == null || !target.isAlive()) return;
            mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
            double d = Math.sqrt(mob.distanceToSqr(target.getX(), mob.getY(), target.getZ()));
            boolean sees = mob.getSensing().hasLineOfSight(target);
            // 正面からのずれ。大技・横薙ぎは標的へ向き直ってから(回る速さの上限があるので、背後の相手へいきなり振らない)。
            float off = Math.abs(mob.offFront(target));

            // 威嚇: 新しいプレイヤーと対峙したときにまれに。
            if (target instanceof Player p && !p.getUUID().equals(mob.lastEngaged) && off <= SPECIAL_FACING) {
                mob.lastEngaged = p.getUUID();
                if (mob.threatCooldown <= 0 && sees && d >= THREAT_MIN_RANGE * mob.bodyScale() && d <= THREAT_MAX_RANGE * mob.bodyScale()
                        && mob.random.nextFloat() < THREAT_CHANCE_ON_ENGAGE) {
                    mob.threatCooldown = THREAT_COOLDOWN;
                    mob.startAction(ACT_THREAT);
                    return;
                }
            }
            double s = mob.bodyScale();
            double reach = SWEEP_REACH * s + target.getBbWidth() * 0.5D;
            // 横薙ぎの間合いの外で追い続けた時間を、通常技を出そうとした回数として数える(引き撃ちの相手にも特殊技が出るように。
            // OUT_OF_REACH_AS_NORMAL_TICKS の javadoc)。
            if (sees && d > reach * 0.85D && ++mob.outOfReachTicks >= OUT_OF_REACH_AS_NORMAL_TICKS) {
                mob.outOfReachTicks = 0;
                mob.pacing.onNormalAttack();
            }
            // 11-05: 通常技 4 つを点数で選ぶ(層 2 の ActionPicker。直前の技は点数を下げる)。近接技(横薙ぎ・連撃)は「振り始める距離」
            // = 届く距離の 85% まで(振りかぶりの間に踏み込んで懐で当てる)。掴み・飛ぶ斬撃は間合いに入った 1 tick ごとに小さな確率で候補に入る。
            double dy = Math.abs(target.getY() - mob.getY());
            // 段 3: 慎重な個体は隙を待つ。攻めに行くのは隙を見つけたとき・待ちきれなくなったとき(数値はすべて【仮】)。
            boolean committed = mob.tickPatience(target);
            ActionDef picked = mob.picker.pick(mob.normalDefs(), d, mob.getScale(), reach * 0.85D, a -> (committed || a.get("counter", 0.0D) > 0.0D) && switch (a.id()) {
                case "sweep", "flurry", "kiriage", "tsukikaki", "kosa", "fumikomi", "kozuki" -> off <= ATTACK_FACING && dy < 3.0D * s;
                case "grab" -> sees && off <= SPECIAL_FACING && mob.onGround();
                case "wave" -> sees && off <= SPECIAL_FACING && dy < 4.0D;
                default -> false;
            }, mob::personalityWeight, mob.random);
            if (picked != null) {
                // 特殊技(地走りの光の帯): 通常技を出す場面で、出してよく(SpecialPacing の roll に勝ち)間合い・クールダウンが合えば代わりに。
                ActionDef rift = def("rift");
                if (mob.picker.ready("rift") && ActionPicker.inRange(rift, d, mob.getScale(), 0.0D) && sees && off <= SPECIAL_FACING
                        && mob.onGround() && mob.pacing.roll(mob.random)) {
                    mob.startAction(ACT_RIFT_WINDUP);
                    return;
                }
                mob.outOfReachTicks = 0;
                mob.onAttackStarted();
                switch (picked.id()) {
                    case "sweep" -> {
                        mob.startAction(mob.nextSlashLeft ? ACT_ATTACK_L : ACT_ATTACK_R);
                        mob.nextSlashLeft = !mob.nextSlashLeft;
                    }
                    case "flurry" -> mob.startAction(ACT_FLURRY_WINDUP);
                    // 段 2(11-07 夜)の小さな技。片方の鎌の技は左右を交互に。
                    case "kiriage" -> mob.startAction(mob.flipSide() ? ACT_KIRIAGE_L : ACT_KIRIAGE_R);
                    case "tsukikaki" -> mob.startAction(ACT_TSUKIKAKI);
                    case "kosa" -> mob.startAction(ACT_KOSA);
                    case "fumikomi" -> mob.startAction(mob.flipSide() ? ACT_FUMIKOMI_L : ACT_FUMIKOMI_R);
                    case "kozuki" -> mob.startAction(mob.flipSide() ? ACT_KOZUKI_L : ACT_KOZUKI_R);
                    case "grab" -> mob.startAction(ACT_GRAB_WINDUP);
                    case "wave" -> {
                        mob.startAction(mob.nextSlashLeft ? ACT_WAVE_L : ACT_WAVE_R);
                        mob.nextSlashLeft = !mob.nextSlashLeft;
                    }
                    default -> {
                    }
                }
                return;
            }
            // 追う(間合いの少し手前で止まる)。止まっている間は標的へ向き直る(その場で旋回の足踏み)。
            // 段 3: 止まる距離は慎重さで変わる(待っている慎重な個体は遠く、攻めに行くときと大胆な個体は今まで通り〜少し近く)。
            double stop = reach * ATTACK_IDEAL_RATIO * (committed ? Math.min(1.0D, mob.holdFactor()) : mob.holdFactor());
            double band = 1.0D * s;
            if (!committed && d < stop - band) {
                // 間合いを保つ: 向き合ったまま後ずさる
                mob.getNavigation().stop();
                mob.wantFacing(mob.yawTo(target), TURN_HUNT);
                mob.backpedal = (float) pv("backpedal", 0.5D);
            } else if (d > stop) {
                if (--repath <= 0) {
                    repath = 8;
                    Vec3 flankTo = mob.flankPoint(target, stop);
                    if (flankTo != null) {
                        // 回り込み: 相手の横へずらした点へ
                        mob.getNavigation().moveTo(flankTo.x, flankTo.y, flankTo.z, 1.0D);
                    } else {
                        // moveTo(target, 1.0) と同じ。前回届かなかったのと同じマス同士なら探索を省く(PathRetryMemo)。
                        pathMemo.moveTo(mob, target, 1.0D);
                    }
                }
            } else {
                mob.getNavigation().stop();
                mob.wantFacing(mob.yawTo(target), TURN_HUNT);
            }
        }
    }

    // ------------------------------------------------------------------ 段 3: 性格の振る舞い(数値はすべて【仮】)

    /**
     * 慎重な個体の待ちを 1 tick 進め、攻めに行く(普通の技を選んでよい)かを返す。慎重さ 0.5 以下の個体は常に攻めに行く(段 2 までと同じ)。
     * 慎重な個体は、相手の隙({@link FalcimnesPersonality#opening})を見つけるか、慎重さに比例する長さ({@code patienceTicks} × 慎重さの片側)
     * 待ちきれなくなると {@code commitTicks} の間だけ攻めに行く。技を 1 つ始めたら待ちに戻る({@link #onAttackStarted})。
     */
    boolean tickPatience(LivingEntity target) {
        boolean opening = FalcimnesPersonality.opening(this, target, this.targetWasBlocking);
        this.targetWasBlocking = target instanceof Player p && p.isBlocking();
        double wary = FalcimnesPersonality.over(caution());
        if (wary <= 0.0D) return true;
        if (this.commitTicks > 0) {
            this.commitTicks--;
            return true;
        }
        if (opening || ++this.patience >= (int) Math.round(pv("patienceTicks", 160.0D) * wary)) {
            this.patience = 0;
            this.commitTicks = (int) pv("commitTicks", 60.0D);
            return true;
        }
        return false;
    }

    /** 選んで技を始めた(連続技の 2 段目・特殊技は除く)。慎重な個体は待ちに戻る。 */
    void onAttackStarted() {
        this.commitTicks = 0;
        this.patience = 0;
    }

    /** 止まる距離の倍率: 慎重さ 0.5 で 1、1 で 1 + 2 × keepDistance × 0.5、0 で小さく(下限 0.75)。 */
    double holdFactor() {
        return Math.max(0.75D, 1.0D + 2.0D * pv("keepDistance", 0.8D) * (caution() - 0.5D));
    }

    /**
     * 回り込みの行き先(回り込み 0.5 以下なら null = まっすぐ追う)。相手から自分への向きを、個体ごとに決まった側へ
     * {@code flankAngle} × 回り込みの片側だけ回した、相手から {@code dist} の点。
     */
    @Nullable
    Vec3 flankPoint(LivingEntity target, double dist) {
        double over = FalcimnesPersonality.over(flank());
        if (over <= 0.05D) return null;
        double ang = Math.toRadians(pv("flankAngle", 60.0D) * over) * ((this.getUUID().getLeastSignificantBits() & 1L) == 0L ? 1.0D : -1.0D);
        double dx = this.getX() - target.getX(), dz = this.getZ() - target.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-3) return null;
        dx /= len;
        dz /= len;
        double cos = Math.cos(ang), sin = Math.sin(ang);
        double rx = dx * cos - dz * sin, rz = dx * sin + dz * cos;
        return new Vec3(target.getX() + rx * dist, target.getY(), target.getZ() + rz * dist);
    }

    // ------------------------------------------------------------------ 旋回の上限の付いた操縦(スクティムネスと同じ作り)

    /**
     * 移動: 行き先の向きは {@link #wantFacing} で希望を出すだけ(バニラは 1 tick に 90° まで直に回す)。
     * 進む速さは向きのずれの cos 倍(ずれが {@link #TANK_MAX_OFF} を超えたら 0 = その場で回る)。横歩き(strafe)はしない。
     */
    final class TankMoveControl extends MoveControl {
        TankMoveControl(FalcimnesEntity mob) {
            super(mob);
        }

        @Override
        public void tick() {
            FalcimnesEntity f = FalcimnesEntity.this;
            if (this.operation == Operation.MOVE_TO) {
                this.operation = Operation.WAIT;
                double dx = this.wantedX - f.getX();
                double dz = this.wantedZ - f.getZ();
                double dy = this.wantedY - f.getY();
                if (dx * dx + dy * dy + dz * dz < 2.5000003E-7F) {
                    f.setZza(0.0F);
                    return;
                }
                float want = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
                f.wantFacing(want, TURN_MOVE);
                float off = Math.abs(Mth.wrapDegrees(want - f.getYRot()));
                float speed = (float) (this.speedModifier * f.getAttributeValue(Attributes.MOVEMENT_SPEED));
                f.setSpeed(speed);
                f.setZza(off > TANK_MAX_OFF ? 0.0F : speed * Mth.cos(off * Mth.DEG_TO_RAD));
                f.setXxa(0.0F);
                // 段差の飛び乗り(バニラの MoveControl と同じ条件)。
                BlockPos pos = f.blockPosition();
                BlockState state = f.level().getBlockState(pos);
                // 段 3: この個体の当たりで見る(葉を押し分ける荒い個体は、葉の中で段と見て跳ねていた)
                VoxelShape shape = state.getCollisionShape(f.level(), pos, net.minecraft.world.phys.shapes.CollisionContext.of(f));
                if (dy > (double) f.maxUpStep() && dx * dx + dz * dz < (double) Math.max(1.0F, f.getBbWidth())
                        || !shape.isEmpty() && f.getY() < shape.max(Direction.Axis.Y) + (double) pos.getY()
                        && !state.is(BlockTags.DOORS) && !state.is(BlockTags.FENCES)) {
                    f.getJumpControl().jump();
                    this.operation = Operation.JUMPING;
                }
            } else if (this.operation == Operation.JUMPING) {
                f.setSpeed((float) (this.speedModifier * f.getAttributeValue(Attributes.MOVEMENT_SPEED)));
                if (f.onGround()) {
                    this.operation = Operation.WAIT;
                }
            } else {
                this.operation = Operation.WAIT;
                if (f.backpedal > 0.0F && !f.isActing()) {
                    // 段 3: 慎重な個体が間合いを保つ後ずさり(HuntGoal がこの tick に立てたときだけ)。速さは普段の backpedal 倍【仮】。
                    float speed = (float) f.getAttributeValue(Attributes.MOVEMENT_SPEED);
                    f.setSpeed(speed);
                    f.setZza(-speed * f.backpedal);
                } else {
                    f.setZza(0.0F);
                }
                f.setXxa(0.0F);
            }
            f.backpedal = 0.0F;
        }
    }

    /**
     * 視線: 頭だけ振り向くことは無い(頭の向きは首の層のアニメが持つ)。見たい向きを {@link #TURN_LOOK} の希望として出し、体ごと回る
     * (見回し・プレイヤーを見る Goal で、止まったまま向きを変える —— そのとき旋回の足踏みが流れる)。
     */
    final class TankLookControl extends LookControl {
        TankLookControl(FalcimnesEntity mob) {
            super(mob);
        }

        @Override
        public void tick() {
            FalcimnesEntity f = FalcimnesEntity.this;
            if (this.lookAtCooldown > 0) {
                this.lookAtCooldown--;
                this.getYRotD().ifPresent(yaw -> f.wantFacing(yaw, TURN_LOOK));
                this.getXRotD().ifPresent(pitch -> f.setXRot(this.rotateTowards(f.getXRot(),
                        Mth.clamp(pitch, -f.getMaxHeadXRot(), f.getMaxHeadXRot()), this.xMaxRotAngle)));
            } else {
                f.setXRot(this.rotateTowards(f.getXRot(), 0.0F, 5.0F));
            }
            f.yHeadRot = f.getYRot();
        }
    }

    /** 体・頭は常に {@code yRot} に揃える(サーバーでもクライアントでも。クライアントの yRot は同期の補間)。 */
    static final class TankBodyControl extends BodyRotationControl {
        private final FalcimnesEntity mob;

        TankBodyControl(FalcimnesEntity mob) {
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
     * アニメは 3 つのコントローラ(脚と首の層は {@link PhaseKeepingAnimationController}: 速さを変えても位相が巻き戻らない)。
     * <ul>
     *     <li>{@code "legs"}(脚・胴・尾): 回避・突進・死はその専用、ほかは<b>体の実際の動き</b>で歩き / 旋回の足踏み / 待機を選び、
     *     ループの速さを体の速さに合わせる(前へ進んだ量 ÷ 再生速度 1.0 の地上速度、回った角度 ÷ 7 度)。動作の最中もそのまま —— 横薙ぎの
     *     踏み込み・連撃の寄り・標的への向き直りで、足が地面を滑らない。</li>
     *     <li>{@code "main"}(首・鎌・翅): 動作中は動作のアニメ、何もしていなければ歩き / 旋回(首が回る向きへ先に向く)/ 待機。</li>
     *     <li>{@code "voice"}(下顎だけ、口パク): 待機の唸り・痛みの声を鳴らした間だけ流し、首の層の下顎を上書きする({@link #voiceState})。</li>
     * </ul>
     * 脚と首の 2 つの層は別々のボーンを受け持つ(生成器の書き出しで確かめている)ので、どの組み合わせでも混ざらない。口パクの層だけは首の層の下顎を
     * わざと上書きする(同じ骨は後に登録したコントローラが勝つ)。
     * 選び方の数値はスクティムネスと同じ(クライアントでその tick に体が前へ進んだ量で歩き、止まって回っていれば旋回)。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new PhaseKeepingAnimationController<>(this, "legs", TRANSITION_TICKS, this::legsState));
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", TRANSITION_TICKS, this::mainState));
        // 口パク(下顎だけ)。首の層より後に登録する —— 同じ骨は後のコントローラが勝つので、流している間だけ首の層の下顎を上書きする。
        controllers.add(new AnimationController<>(this, "voice", VOICE_TRANSITION, this::voiceState));
    }

    /**
     * 口パクの層(2026-09-24、ユーザー要望「アニメーションを全体的に口を動かす動きも含める」)。待機の唸り({@link #EVENT_GROWL})と
     * 痛みの声({@link #handleDamageEvent})を鳴らした間だけ、声の大きさに合わせて下顎を動かす。動作の最中は流さない
     * (動作のアニメが口を動かしている。待機の唸りは動作の最中は鳴らさない)。止めると首の層の下顎へ戻る —— 口パクの頭と終わりは
     * 待機の口に近い開きにしてある。
     */
    private PlayState voiceState(AnimationState<FalcimnesEntity> state) {
        if (this.clientVoice == null || this.isDeadOrDying() || this.tickCount >= this.clientVoiceEnd) {
            this.clientVoice = null;
            return PlayState.STOP;
        }
        // 動作の最中は流さない(待機の仕草を殴られて止めるときは、被弾の知らせが動作の同期より 1 tick ほど先に届くので、捨てずに待つ)。
        if (isActing()) return PlayState.STOP;
        if (this.clientVoiceSeen != this.clientVoiceSeq) {
            this.clientVoiceSeen = this.clientVoiceSeq;
            // 画面の外で鳴った声は、見え始めたときにはもう終わりかけている —— 0 秒目から流すと声と口がずれるので捨てる。
            if (this.tickCount - this.clientVoiceStart > VOICE_LATE_TICKS) {
                this.clientVoice = null;
                return PlayState.STOP;
            }
            state.getController().forceAnimationReset();
        }
        return state.setAndContinue(this.clientVoice);
    }

    /** クライアント: 口パクを始める。 */
    private void startVoice(RawAnimation voice, int ticks) {
        this.clientVoice = voice;
        this.clientVoiceStart = this.tickCount;
        this.clientVoiceEnd = this.tickCount + ticks;
        this.clientVoiceSeq++;
    }

    private PlayState legsState(AnimationState<FalcimnesEntity> state) {
        AnimationController<FalcimnesEntity> c = state.getController();
        c.transitionLength(TRANSITION_TICKS);
        c.setAnimationSpeed(1.0D);
        if (this.isDeadOrDying()) {
            return state.setAndContinue(LEGS_DEATH);
        }
        int act = getAction();
        int seq = this.entityData.get(DATA_SEQ);
        if (seq != this.clientLegsSeq) {
            this.clientLegsSeq = seq;
            if (isDodge(act)) {
                c.forceAnimationReset();
            }
        }
        switch (act) {
            case ACT_DODGE: return state.setAndContinue(LEGS_DODGE);
            case ACT_DODGE_L: return state.setAndContinue(LEGS_DODGE_L);
            case ACT_DODGE_R: return state.setAndContinue(LEGS_DODGE_R);
            case ACT_GRAB_DASH:
                // 突進は溜めの最後から一気に 0.95 ブロック/tick。補間を挟むとその 4 tick に足が 4 ブロック近く地面を滑るので、一気に切り替える。
                c.transitionLength(0);
                c.setAnimationSpeed(loopSpeed(clientForwardSpeed(), px(DASH_GROUND_SPEED_PX)));
                return state.setAndContinue(LEGS_DASH);
            default:
                break;
        }
        if (this.clientProceduralLegs) {
            // 脚は FalcimnesLegRig が地形に合わせて曲げる。脚の層は待機(胴の呼吸・尾の揺れ)を流し、脚の形の基準にする。
            return state.setAndContinue(LEGS_IDLE);
        }
        Gait gait = clientGait();
        if (gait.walking()) {
            c.setAnimationSpeed(loopSpeed(gait.ahead(), px(WALK_GROUND_SPEED_PX)));
            return state.setAndContinue(LEGS_WALK);
        }
        if (gait.turning()) {
            c.setAnimationSpeed(loopSpeed(gait.turn(), TURN_ANIM_DEG_PER_TICK));
            return state.setAndContinue(gait.turnLeft() ? LEGS_TURN_L : LEGS_TURN);
        }
        return state.setAndContinue(LEGS_IDLE);
    }

    private PlayState mainState(AnimationState<FalcimnesEntity> state) {
        AnimationController<FalcimnesEntity> c = state.getController();
        c.transitionLength(TRANSITION_TICKS);
        c.setAnimationSpeed(1.0D);
        if (this.isDeadOrDying()) {
            return state.setAndContinue(DEATH);
        }
        int act = getAction();
        int seq = this.entityData.get(DATA_SEQ);
        if (seq != this.clientSeq) {
            this.clientSeq = seq;
            if (act == ACT_IDLE) {
                // 見始めたのが遅れた仕草は流さない(FIDGET_LATE_TICKS)。手入れの中断(ACT_GROOM_END)は、手入れを流していたときだけ流す。
                this.clientFidgetShown = (int) this.level().getGameTime() - this.entityData.get(DATA_ACTION_TIME) <= FIDGET_LATE_TICKS;
            } else if (act != ACT_GROOM_END) {
                this.clientFidgetShown = false;
            }
            if (act != ACT_NONE) {
                c.forceAnimationReset();
            }
        }
        boolean hi = this.entityData.get(DATA_DODGE_HI);
        switch (act) {
            case ACT_ATTACK_R: return state.setAndContinue(ATTACK_R);
            case ACT_ATTACK_L: return state.setAndContinue(ATTACK_L);
            case ACT_FLURRY_WINDUP: return state.setAndContinue(FLURRY_WINDUP);
            case ACT_FLURRY_R:
            case ACT_FLURRY_L:
                // 連撃は 1 振りが最短 3 tick なので、補間を挟むと振りが消える。1 振りのアニメは 0.5 秒(10 tick)、間隔に収まる速さで流す。
                c.transitionLength(0);
                c.setAnimationSpeed(10.0D / Math.max(1, this.entityData.get(DATA_SLASH_TICKS)));
                return state.setAndContinue(act == ACT_FLURRY_R ? FLURRY_R : FLURRY_L);
            case ACT_GRAB_WINDUP: return state.setAndContinue(GRAB_WINDUP);
            case ACT_GRAB_DASH: return state.setAndContinue(GRAB_DASH);
            case ACT_GRAB_HOLD: return state.setAndContinue(GRAB_HOLD);
            case ACT_FLURRY_END: return state.setAndContinue(FLURRY_END);
            case ACT_RECOVER: return state.setAndContinue(GRAB_END);
            case ACT_THREAT_END: return state.setAndContinue(THREAT_END);
            case ACT_DODGE: return state.setAndContinue(hi ? DODGE_HI : DODGE);
            case ACT_DODGE_L: return state.setAndContinue(hi ? DODGE_L_HI : DODGE_L);
            case ACT_DODGE_R: return state.setAndContinue(hi ? DODGE_R_HI : DODGE_R);
            case ACT_THREAT: return state.setAndContinue(THREAT);
            case ACT_WAVE_R: return state.setAndContinue(WAVE_R_ANIM);
            case ACT_WAVE_L: return state.setAndContinue(WAVE_L_ANIM);
            case ACT_RIFT_WINDUP: return state.setAndContinue(RIFT_WINDUP_ANIM);
            case ACT_FLURRY_X: return state.setAndContinue(FLURRY_X_ANIM);
            case ACT_KIRIAGE_R: return state.setAndContinue(KIRIAGE_R_ANIM);
            case ACT_KIRIAGE_L: return state.setAndContinue(KIRIAGE_L_ANIM);
            case ACT_TSUKIKAKI: return state.setAndContinue(TSUKIKAKI_ANIM);
            case ACT_KOSA: return state.setAndContinue(KOSA_ANIM);
            case ACT_FUMIKOMI_R: return state.setAndContinue(FUMIKOMI_R_ANIM);
            case ACT_FUMIKOMI_L: return state.setAndContinue(FUMIKOMI_L_ANIM);
            case ACT_KOZUKI_R: return state.setAndContinue(KOZUKI_R_ANIM);
            case ACT_KOZUKI_L: return state.setAndContinue(KOZUKI_L_ANIM);
            case ACT_RIFT_HOLD:
                // 刺した姿勢のまま補間 0 でつなぐ(生成器が刺す終わり・待つループ・抜く頭を同じ姿勢に揃えている。検算の「つなぎ目」)。
                c.transitionLength(0);
                return state.setAndContinue(RIFT_HOLD_ANIM);
            case ACT_RIFT_RECOVER:
                c.transitionLength(0);
                return state.setAndContinue(RIFT_RECOVER_ANIM);
            case ACT_GRAB_FINISH: return state.setAndContinue(GRAB_FINISH_ANIM);
            case ACT_IDLE:
                return state.setAndContinue(this.clientFidgetShown ? IDLE_ANIMS[Math.floorMod(idleKind(), IDLE_ANIMS.length)] : IDLE);
            case ACT_GROOM_END:
                if (!this.clientFidgetShown) return state.setAndContinue(IDLE);
                return state.setAndContinue(idleKind() == IDLE_GROOM_L ? GROOM_END_L : GROOM_END_R);
            default:
                break;
        }
        Gait gait = clientGait();
        if (gait.walking()) {
            // 脚の層の歩きと同じ速さで流す(同時に切り替わるので位相もそろう)。
            c.setAnimationSpeed(loopSpeed(gait.ahead(), px(WALK_GROUND_SPEED_PX)));
            return state.setAndContinue(WALK);
        }
        if (gait.turning()) {
            return state.setAndContinue(gait.turnLeft() ? TURN_L : TURN);
        }
        return state.setAndContinue(IDLE);
    }

    /**
     * いまの体の動き(クライアント)。
     *
     * @param ahead    この tick に体が前へ進んだ量(ブロック)
     * @param turn     直前の tick に回った角度の大きさ(度。{@link #DATA_TURN})
     * @param turnLeft 左回り(yaw が減る向き)か
     * @param walking  歩きを流すか: 前へ {@link #WALK_MIN_SPEED} 以上進み、かつ回って運ばれる足先より速い
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
     * (後ろ・横へ押されたぶんは歩きに数えない)。
     */
    private double clientForwardSpeed() {
        Vec3 f = forward();
        return Math.max(0.0D, (this.getX() - this.xo) * f.x + (this.getZ() - this.zo) * f.z);
    }

    /** ファイル座標の px(体格 1.0)を、いまの体格のブロックへ。 */
    /**
     * 描画倍率を geo に焼き込んだ倍率(11-05)。この種の距離の定数は「焼き込む前の体格 1.0」で書いてあるので、
     * 体格(SCALE 属性、今は 1)にこれを掛けた {@link #bodyScale()} で使う。アニメの生成器の {@code Rig.BAKE} と同じ値。
     */
    public static final double BAKE = 1.8D;

    /** 焼き込む前の体格(SCALE × {@link #BAKE})。前の {@code getScale()} の代わり。 */
    public double bodyScale() {
        return this.getScale() * BAKE;
    }

    private double px(double px) {
        return px / 16.0D * this.bodyScale();
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
        return ModSounds.FALCIMNES_IDLE.get();
    }

    @Override
    public int getAmbientSoundInterval() {
        return 160;
    }

    /**
     * 待機の唸り。動作の最中は鳴らさない(動作のアニメが口を動かしていて、口パクと合わない)。サーバーで鳴らしたら、クライアントへ
     * {@link #EVENT_GROWL} を送って口パクを流す(唸りはサーバーが鳴らし、クライアントの {@code playAmbientSound} は音を出さない)。
     */
    @Override
    public void playAmbientSound() {
        if (isActing()) return;
        super.playAmbientSound();
        if (!this.level().isClientSide() && !this.isSilent()) {
            this.level().broadcastEntityEvent(this, EVENT_GROWL);
            this.growlQuietUntil = this.tickCount + VOICE_GROWL_TICKS;
        }
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EVENT_GROWL) {
            if (!isActing() && !this.isSilent()) startVoice(VOICE_GROWL, VOICE_GROWL_TICKS);
            return;
        }
        super.handleEntityEvent(id);
    }

    /**
     * 被弾(クライアント)。痛みの声そのものはサーバーの {@code playHurtSound} が鳴らし、ここでは口パクだけ始める。
     * 動作の最中は出さない —— 待機の仕草は例外(サーバーは殴られたら仕草を必ずやめる。やめた知らせは被弾の知らせより少し後に届く)。
     */
    @Override
    public void handleDamageEvent(DamageSource source) {
        super.handleDamageEvent(source);
        if (this.level().isClientSide() && (!isActing() || getAction() == ACT_IDLE) && this.isAlive() && !this.isSilent()) {
            startVoice(VOICE_HURT, VOICE_HURT_TICKS);
        }
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ModSounds.FALCIMNES_HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ModSounds.FALCIMNES_DEATH.get();
    }

    /**
     * 平常時・被弾・死亡の声の高さの個体差。バニラは ±20% ずらすが、合成した低い竜の声が 1.2 倍になると
     * 小さな生き物に聞こえてしまうので ±5% に抑える。
     */
    @Override
    public float getVoicePitch() {
        return 1.0F + (this.random.nextFloat() - this.random.nextFloat()) * 0.05F;
    }

    /** 足音は重い獣のもの(初版は蜘蛛の足音だった。見た目は虫でも種族は竜 —— ユーザー指定)。 */
    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.RAVAGER_STEP, 0.6F, 0.8F);
    }

    // ------------------------------------------------------------------ 掴まれた獲物は降りられない

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
    public static final class Events {
        @SubscribeEvent
        public static void onMount(EntityMountEvent event) {
            // 降車はサーバーでしか起きない(Player#rideTick)。クライアントで止めると、サーバーが離したときに食い違う。
            if (event.getLevel().isClientSide()) {
                return;
            }
            if (event.isDismounting()) {
                if (event.getEntityBeingMounted() instanceof FalcimnesEntity f
                        && f.isAlive() && !f.isRemoved() && f.isHoldingPrey()) {
                    event.setCanceled(true);
                }
                return;
            }
            // 掴まれている獲物が別の乗り物(ボート・馬・ほかの Mob)に乗るのも止める(2026-10-01 点検、バティムネスと同じ穴)。
            // Entity#startRiding は今の乗り物から降りる stopRiding が上で止められても先へ進み、新しい乗り物にも載せてしまう ——
            // 獲物が両方の乗客の表に残り、離しても表から消えない(離す ejectPassengers は獲物の今の乗り物 = 新しい方から降ろすため)。
            // 自分で離す途中(releasing)は isHoldingPrey が false なので止めない。
            if (event.isMounting()
                    && event.getEntityMounting().getVehicle() instanceof FalcimnesEntity f
                    && f != event.getEntityBeingMounted()
                    && f.isAlive() && !f.isRemoved() && f.isHoldingPrey()) {
                event.setCanceled(true);
            }
        }

        /**
         * 掴まれたままプレイヤーがログアウトしたら、先に離す(2026-10-01 点検、バティムネスと同じ穴)。離さないと、バニラは
         * プレイヤーの乗り物ごと(= このファルキムネスごと)プレイヤーのデータに保存して世界から消す({@code PlayerList#remove})。
         * ログインすると隣に戻って次の tick に離す(動作は保存しない)ので、どのみち離すことになる —— それならファルキムネスは世界に残す。
         * {@code PlayerLoggedOutEvent} は {@code PlayerList#remove} の頭(保存より前)で来る。
         */
        @SubscribeEvent
        public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            releaseHeldRider(event.getEntity());
        }

        /** {@code rider} を掴んでいるファルキムネスがいれば、その場(顎の前)で離させる(ログアウト・GameTest 用)。離したら true。 */
        public static boolean releaseHeldRider(Entity rider) {
            if (rider.getVehicle() instanceof FalcimnesEntity f && !f.level().isClientSide() && f.isHoldingPrey()) {
                f.releasePrey(false);
                return true;
            }
            return false;
        }

        private Events() {
        }
    }
}
