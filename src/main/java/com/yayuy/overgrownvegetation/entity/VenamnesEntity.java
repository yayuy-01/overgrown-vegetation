package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.action.ActionDef;
import com.yayuy.overgrownvegetation.entity.action.ActionPicker;
import com.yayuy.overgrownvegetation.entity.action.ActionTiming;
import com.yayuy.overgrownvegetation.entity.forest.ForestMap;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import com.yayuy.overgrownvegetation.entity.legs.FootPlanter;
import com.yayuy.overgrownvegetation.entity.runner.GaitSway;
import com.yayuy.overgrownvegetation.entity.runner.LongBodyRunner;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * ヴェナムネス {@code venamnes}(G5・攻撃特化。11-14 に本名。仮名オイカケ、仮の id {@code g5_pursuer})。G5 の原型: 逃げる人を速く走って追い詰め、口で噛む。
 * ファルキムネスより脆く、長く追う「足の速さ」の Mob(名簿「Mob の名簿と能力の刷新(11-04)」の G5 の表・注文書 g5_pursuer)。
 *
 * <h2>段 1(2026-11-14): 歩くところまで。段 1b(同日): 歩きの見直しと走りのアニメ。技の段 A〜C(11-15): 追う・噛みつき・飛びかかり・足噛み・狩りの疾走・跳び上がり・泳ぎ・性格</h2>
 * 移動は「② 大型の地上を走る型」({@link LongBodyRunner}。ファルキムネスの作りの写し)を使い回す。攻撃・能力はまだ無い(次の段で相談)。
 * 技は {@link #tickAction}(前触れ → 実行 → 隙)、追い方と技の選び方は {@link VenamnesHuntGoal}、仕様と仮の値は Vault「Mob の能力/ヴェナムネス」7〜9 節。自然には湧かない(スポーンエッグ・コマンドだけ)。
 *
 * <h3>ヴェナムネスらしさ = 速さ【全部仮・Claude 11-14】</h3>
 * <ul>
 *   <li><b>開けた所で速い</b>: 森の地図({@link ForestMap})で体の前 0・4 ブロックの幹までの距離を 5 tick ごとに見て、開けているほど移動の速さに
 *       最大 {@link #SPRINT_OPEN} 倍を掛ける(回っている間は弱める)。うろつき(経路の速さ倍率 1 未満)では掛けない。</li>
 *   <li><b>森の中でも足が止まりにくい</b>: 回る速さ {@link #TURN_PER_TICK} 度/tick(ファルキムネス 7)、その場で回りだす向きのずれ
 *       {@link #MOVE_OFF} 度(同 70)、向きがずれていても前へ {@link #TURN_ADVANCE} は進む(同 0)。経路の長い体の重みは軽め({@link #LONG_BODY})。</li>
 * </ul>
 *
 * <h3>脚と歩き方(11-14 段 1b で見直し)</h3>
 * 二足(太もも → すね → ふ節 → 足)。近くで描かれていて遅い間({@link #IK_MAX_SPEED} 未満)は足の置き場({@link FootPlanter})+ IK:
 * 大股でゆっくり運び、左右を半周期ずらす({@link #tunePlanter})。腰の上下とひねり・胸と首と尾の遅れは、実際の踏み替えから
 * {@link GaitSway} が出す(アニメは上半身の構え stalk だけ)。それより速い間は手付けの走り run(宙に浮く間がある)を実際の速さに合わせて流す。
 * 遠くの個体(足の置き場が効かない)がゆっくり歩く間は手付けの walk。walk・run・stalk は {@code tools/venamnes-anim} が同じ式で焼く
 * (正本は Blockbench の venamnes.bbmodel)。
 */
public class VenamnesEntity extends LongBodyRunner implements GeoEntity, ParasitizedMob {

    public static final String ID = "venamnes";

    /** 描画倍率(geo に焼き込まず描画で掛ける。テクスチャは 1 単位 2.5 テクセルなので、画素はブロックの 1 画素に揃う)。 */
    public static final float RENDER_SCALE = 2.5F;
    /** モデルの 1 単位(px)をブロックへ。 */
    static final double PX = RENDER_SCALE / 16.0D;

    // --- 速さ【仮】 ---
    /** 開けた所で移動の速さに掛ける倍率の上限【仮】。 */
    public static final double SPRINT_OPEN = 1.16D;   // 11-15 ユーザー決定(案 1): 1.45 → 約 1.16。地面の速さは倍率の 2 乗で効く(バニラの Mob は setSpeed が前への入力にも同じ値を入れる)ので、急ぎの素の 0.194 × 1.16² ≒ 0.26(プレイヤーの全力疾走 0.28 より少し遅い)。1.45 では 0.408 で狩りの疾走 0.35 より速かった
    /** 幹までの距離がこれ以下なら倍率 1、{@link #OPEN_CLEAR} 以上で上限(ブロック)【仮】。森の地図の上限は 6。 */
    static final double FOREST_CLEAR = 2.0D, OPEN_CLEAR = 5.0D;
    /** 倍率が目標へ寄る速さ(1 tick あたり)【仮】。 */
    static final double SPRINT_RATE = 0.04D;
    /** 回っている間の倍率の弱め: 1 tick にこの角度回っていれば倍率 1 へ戻す(度)【仮】。 */
    static final double SPRINT_TURN_DROP = 8.0D;
    /** 回る速さ(度/tick)・その場で回りだすずれ(度)・ずれていても進む割合【仮】。 */
    static final float TURN_PER_TICK = 10.0F, MOVE_OFF = 85.0F, TURN_ADVANCE = 0.35F;
    /** うろつきの速さ(移動の速さに掛ける)【仮】。 */
    static final double STROLL_SPEED = 0.72D;   // 11-14 段 1b: 0.55 → 0.72(地面の速さ 0.059 → 約 0.10 ブロック/tick。0.059 は体長 8 ブロックには這うように遅く、大股にできなかった)
    /** 経路の長い体の重み【仮】: ファルキムネス (2.0, 1.0, 0.6, 1.5) より軽く、曲がりを嫌わない。 */
    public static final OvergrownNodeEvaluator.LongBody LONG_BODY = new OvergrownNodeEvaluator.LongBody(1.8F, 0.8F, 0.35F, 1.3F);

    // --- 体の作り(モデル prac2_g5_pursuer_r9 の骨の位置 × 描画倍率) ---
    /** 足の置き場: 左右 ±4.5 px、前 0.87 px(足の裏の中心)。 */
    static final double[] LEG_HOME_LEFT = {4.5D * PX, -4.5D * PX};
    static final double[] LEG_HOME_FWD = {0.87D * PX, 0.87D * PX};
    /** 0 = 左脚、1 = 右脚(交互に踏み替える)。 */
    static final int[] LEG_GROUP = {0, 1};
    /** 曲げの付け根: 首 neck0・neck1・neck2・頭 head(z −10・−13.5・−17・−20)、尾 tail0〜tail3(z 4・9・14・18.5)。 */
    static final double[] BEND_FRONT = {10.0D * PX, 13.5D * PX, 17.0D * PX, 20.0D * PX};
    static final double[] BEND_BACK = {4.0D * PX, 9.0D * PX, 14.0D * PX, 18.5D * PX};

    // --- 脚の手続きと手付けの切り替え・アニメの速さ ---
    /** これより速い(ブロック/tick)と手付けの歩き(走り)にする / これより遅くなったら足の置き場へ戻す【仮】。 */
    public static final double IK_MAX_SPEED = 0.235D, IK_BACK_SPEED = 0.205D;   // 11-14 段 1b: 0.20・0.16 から上げた(急ぎの素の速さ 0.194 が境のすぐ下で、森の中で切り替えが続くため)
    /**
     * walk・run を速さ 1.0 で流したときの地上速度(ブロック/tick)。道具 {@code tools/venamnes-anim} が、踏む足が着いている間に後ろへ送る速さを
     * この値に合わせて焼く(walk は 28 tick で 1 周・歩幅 2.8 ブロック、run は 16 tick で 1 周・歩幅 4.96 ブロック)。【11-14 段 1b】
     */
    public static final double WALK_REF = 0.10D, RUN_REF = 0.31D;
    static final double LOOP_SPEED_MIN = 0.3D, LOOP_SPEED_MAX = 2.5D;
    /** 走りを流す速さの範囲(境 0.205 で約 0.66 倍、最高 0.39 で約 1.26 倍)。 */
    static final double RUN_SPEED_MIN = 0.55D, RUN_SPEED_MAX = 1.5D;
    /** これ(ブロック/tick)以上前へ進んだ tick は歩き。 */
    static final double WALK_MIN_SPEED = 0.01D;
    /** その場で回っていても歩きを流す回る速さ(度/tick)。旋回の足踏みのアニメはまだ無い。 */
    static final float TURN_ANIM_MIN = 1.5F;

    // ------------------------------------------------------------------ 技(段 A 11-15)
    // 時刻はアニメ(Fable のセッションの段 2「技のアニメ」の一覧)と対。前触れの頭に切り替えの補間 LEAD を足す(GeckoLib は補間の後に
    // アニメを 0 秒目から流す)。段と段の継ぎ目は同じ姿なので補間 0 でつなぐ(ずれない)。

    /** 動作(同期する)。段ごとに 1 つ。 */
    public static final int ACT_NONE = 0, ACT_BITE_WINDUP = 1, ACT_BITE = 2, ACT_BITE_RECOVER = 3,
            ACT_POUNCE_WINDUP = 4, ACT_POUNCE = 5, ACT_POUNCE_RECOVER = 6,
            // 段 B(11-15): 狩りの疾走。叫び → 疾走(走りながら追う。脚・曲げは止めない = isActing ではない)→ 最後の飛びかかり → 着地 → 息切れ
            ACT_HUNT_WINDUP = 7, ACT_HUNT_SPRINT = 8, ACT_HUNT_POUNCE = 9, ACT_HUNT_LAND = 10, ACT_HUNT_RECOVER = 11,
            // 段 C(11-15): 段差を跳び上がる(経路の次の点が 2 段以上上のとき)
            ACT_LEAP_WINDUP = 12, ACT_LEAP = 13, ACT_LEAP_RECOVER = 14;

    // --- 段 C: どこでも逃げ切れない(決定「段差も水も越えてくる」)【仮・Claude 11-15】 ---
    /** 跳び上がれる高さ(ブロック)。経路を引く間だけ「上れる段」をこの高さにする(ほかの Mob の経路は変えない)。 */
    public static final float LEAP_HEIGHT = 3.0F;
    /** 跳び上がる: 経路の次の点がこれ以上高く(段)、横にこれ以内(ブロック)なら跳ぶ。 */
    static final int LEAP_MIN_RISE = 2;
    static final double LEAP_REACH = 3.0D;
    /** 長さ: leap_windup 0.40 秒(+ 補間 4。LEAD はこの下で決めるので数で書く)・leap(足が 0.10 秒で離れる。宙は最後の姿のまま)・leap_recover 0.50 秒。 */
    public static final int LEAP_WINDUP_T = 4 + 8, LEAP_LIFTOFF = 2, LEAP_RECOVER_T = 10, LEAP_MAX_AIR = 40;
    /** 跳ぶ頂点の余裕(着く段の上へ、ブロック)・宙で崖の縁へ寄せ続ける横の速さの下限(ブロック/tick)。 */
    static final double LEAP_CLEAR = 0.5D, LEAP_AIR_PUSH = 0.18D;
    /** 泳ぐ速さ(ブロック/tick、水の中で前へ進む間)・経路の水の重み(1 マス)。 */
    public static final double SWIM_SPEED = 0.15D;
    static final float WATER_MALUS = 4.0F;

    /** 動作に入るときの補間(tick)。 */
    static final int LEAD = 4;

    // --- 段 B: 狩りの疾走(時刻は技のアニメの一覧と対)【仮・Claude 11-15。速さはユーザー決定】 ---
    /** 叫び: 補間 + hunt_windup 1.40 秒(立ち上がって叫び、低く伸びる構えで止まる)。 */
    public static final int HUNT_WINDUP_T = LEAD + 28;
    /** 息切れ: hunt_recover 3.00 秒。 */
    public static final int HUNT_RECOVER_T = 60;
    /** 疾走の長さの既定(tick。json の sprintTicks)。 */
    static final int HUNT_SPRINT_T = 80;
    /** 疾走の移動の倍率: 地面の速さは倍率の 2 乗で効くので、急ぎの素の 0.194 → 0.35 は √(0.35/0.194) ≒ 1.34(11-15 ユーザー決定の 0.35)。開けた所の倍率は掛けない。 */
    public static final double HUNT_SPEED = 1.34D;
    /** sprint を速さ 1.0 で流したときの地上速度(ブロック/tick。アニメの一覧: 1 歩 4.9 ブロック・14 tick で 1 巡)。 */
    public static final double SPRINT_REF = 0.35D;
    /** 疾走の最後の飛びかかりを始める距離(体の真ん中から。頭から約 2〜6)・正面のずれ(度)。 */
    static final double HUNT_POUNCE_MIN = 5.0D, HUNT_POUNCE_MAX = 9.3D;
    static final float HUNT_POUNCE_CONE = 20.0F;
    /** 叫びの光: 口 0.40〜1.05 秒・腱 0.40 秒から(アニメの頭から。補間 4 を足す)。息切れの頭で腱は 10 tick かけて消える。 */
    static final float HUNT_MOUTH_ON = LEAD + 8.0F, HUNT_MOUTH_OFF = LEAD + 21.0F, HUNT_TENDON_ON = LEAD + 8.0F, HUNT_TENDON_FADE = 10.0F;

    // --- 段 B: すれ違いざまの足噛み(run に重ねる 2 つ目の層。技の最中ではない = 走りを止めない)【仮・Claude 11-15】 ---
    /** 足噛みの段(同期する)。0 = なし、1〜3 = 左(前触れ・実行・隙)、4〜6 = 右。左右は自分から見た相手の側。 */
    public static final int NIP_NONE = 0, NIP_L_WINDUP = 1, NIP_L = 2, NIP_L_RECOVER = 3, NIP_R_WINDUP = 4, NIP_R = 5, NIP_R_RECOVER = 6;
    /** 長さ: nip_*_windup 0.30 秒・nip_* 0.20 秒・nip_*_recover 0.35 秒。補間なし(頭と終わりは run の首の構え)。 */
    public static final int NIP_WINDUP_T = 6, NIP_EXEC_T = 4, NIP_RECOVER_T = 7;
    /** 当たり: nip の 0.08〜0.15 秒 = 実行の 2〜3 tick 目。 */
    static final int NIP_HIT_FROM = 2, NIP_HIT_TO = 3;
    /** 噛める所(体の真ん中から見た相手の位置、ブロック): 前へ {@link #NIP_FWD_MIN}〜{@link #NIP_FWD_MAX}・横へ {@link #NIP_SIDE_MIN}〜{@link #NIP_SIDE_MAX}(首を横へ曲げて届く所)。 */
    static final double NIP_FWD_MIN = 0.5D, NIP_FWD_MAX = 4.5D, NIP_SIDE_MIN = 0.4D, NIP_SIDE_MAX = 3.0D;
    /** 相手がこちらを向いている(向きのずれがこれ以内、度)と行き過ぎて当たらない(決定「向き合うと行き過ぎる」)。 */
    static final float NIP_FACING = 70.0F;
    /** 足噛みを始める速さ(ブロック/tick。走っている間だけ。歩き stalk の首の構えには合わない)。 */
    static final double NIP_MIN_SPEED = VenamnesEntity.IK_MAX_SPEED;   // 11-15: 0.2 → 走りへ切り替わる速さ 0.235(それより遅いと歩き stalk の首の構えで、足噛みのアニメが合わない)
    /** 噛みつき: bite_windup 0.50 秒・bite 0.35 秒・bite_recover 0.80 秒。 */
    public static final ActionTiming BITE_T = ActionTiming.ofSeconds(LEAD, 0.50D, 0.35D, 0.80D);
    /** 噛みつきの当たり: bite の 0.10〜0.17 秒(口が閉じる瞬間)= 実行の 2〜3 tick 目。 */
    static final int BITE_HIT_FROM = 2, BITE_HIT_TO = 3;
    /** 飛びかかり: pounce_windup 0.80 秒(+ 補間)、pounce は足が 0.10 秒で離れ(2 tick 目に跳ぶ)0.40 秒で宙の姿のまま止まる、pounce_recover 1.00 秒。 */
    public static final int POUNCE_WINDUP_T = LEAD + 16, POUNCE_LIFTOFF = 2, POUNCE_RECOVER_T = 20;
    /** 飛びかかりの当たり: pounce の 0.26 秒(口が開く)〜着地、と pounce_recover の 0〜0.10 秒(着地で口が閉じる)。 */
    static final int POUNCE_HIT_FROM = 5, LAND_HIT_TO = 2;
    /** 宙にいてよい最長(tick。これを過ぎたら着地したことにする)。 */
    static final int POUNCE_MAX_AIR = 40;

    // --- 間合い・向き【仮・Claude 11-15】(ブロック、体格 1) ---
    /** 頭(顎)が体の真ん中から前へ出ている距離(モデルの頭の付け根 z −20 px × 2.5 ÷ 16 = 3.1 + 顎)。 */
    static final double HEAD_REACH = 3.3D;
    /** 噛みつきを始める距離(体の真ん中から相手の真ん中、相手の半幅を足す)・当たる距離(真ん中から相手の箱まで)・正面からのずれ(度)。 */
    static final double BITE_START = 3.9D, BITE_REACH = 4.6D;
    static final float BITE_START_CONE = 25.0F, BITE_HIT_CONE = 40.0F;
    /** 噛みつき・飛びかかりを始める高さの差(ブロック)。 */
    static final double BITE_DY = 2.5D, POUNCE_DY = 1.5D;
    /** 飛びかかりを始める正面からのずれ(度)。 */
    static final float POUNCE_START_CONE = 15.0F;
    /** 前触れの間に標的へ向き直る速さ(度/tick)。飛びかかりは跳ぶ {@link #POUNCE_LOCK} tick 前から向きを固める(横へ出ればかわせる)。 */
    static final float BITE_TRACK_TURN = 6.0F, POUNCE_TRACK_TURN = 4.0F;
    static final int BITE_LOCK = 3, POUNCE_LOCK = 6;
    /** 飛びかかりの跳ぶ距離の下限・上限(ブロック。頭が相手に届く所へ着く = 相手までの距離 − {@link #HEAD_REACH})。 */
    static final double POUNCE_MIN_LEAP = 1.5D, POUNCE_MAX_LEAP = 9.0D;
    /** 宙で口が当たる広さ(相手の箱をこれだけ膨らませて頭の点が入るか、ブロック)。 */
    static final double POUNCE_BITE_RADIUS = 0.9D;

    private static final EntityDataAccessor<Integer> DATA_ACTION = SynchedEntityData.defineId(VenamnesEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SEQ = SynchedEntityData.defineId(VenamnesEntity.class, EntityDataSerializers.INT);

    private static final RawAnimation BITE_WINDUP_ANIM = RawAnimation.begin().thenPlayAndHold("bite_windup");
    private static final RawAnimation BITE_ANIM = RawAnimation.begin().thenPlayAndHold("bite");
    private static final RawAnimation BITE_RECOVER_ANIM = RawAnimation.begin().thenPlayAndHold("bite_recover");
    private static final RawAnimation POUNCE_WINDUP_ANIM = RawAnimation.begin().thenPlayAndHold("pounce_windup");
    private static final RawAnimation POUNCE_ANIM = RawAnimation.begin().thenPlayAndHold("pounce");
    private static final RawAnimation POUNCE_RECOVER_ANIM = RawAnimation.begin().thenPlayAndHold("pounce_recover");
    private static final RawAnimation HUNT_WINDUP_ANIM = RawAnimation.begin().thenPlayAndHold("hunt_windup");
    private static final RawAnimation SPRINT_ANIM = RawAnimation.begin().thenLoop("sprint");
    private static final RawAnimation HUNT_RECOVER_ANIM = RawAnimation.begin().thenPlayAndHold("hunt_recover");
    private static final RawAnimation LEAP_WINDUP_ANIM = RawAnimation.begin().thenPlayAndHold("leap_windup");
    private static final RawAnimation LEAP_ANIM = RawAnimation.begin().thenPlayAndHold("leap");
    private static final RawAnimation LEAP_RECOVER_ANIM = RawAnimation.begin().thenPlayAndHold("leap_recover");
    private static final RawAnimation SWIM_ANIM = RawAnimation.begin().thenLoop("swim");
    private static final RawAnimation PROWL_ANIM = RawAnimation.begin().thenLoop("prowl");
    private static final RawAnimation PROWL_FREEZE_ANIM = RawAnimation.begin().thenLoop("prowl_freeze");
    private static final RawAnimation[] NIP_ANIMS = {null,
            RawAnimation.begin().thenPlayAndHold("nip_l_windup"), RawAnimation.begin().thenPlayAndHold("nip_l"), RawAnimation.begin().thenPlayAndHold("nip_l_recover"),
            RawAnimation.begin().thenPlayAndHold("nip_r_windup"), RawAnimation.begin().thenPlayAndHold("nip_r"), RawAnimation.begin().thenPlayAndHold("nip_r_recover")};
    private static final EntityDataAccessor<Integer> DATA_NIP = SynchedEntityData.defineId(VenamnesEntity.class, EntityDataSerializers.INT);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run");
    /** 足の置き場で歩く間の上半身(脚と腰の上下・首と尾の付け根は手続きが足す)。 */
    private static final RawAnimation STALK = RawAnimation.begin().thenLoop("stalk");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /** 開けた所の倍率(サーバー)。 */
    private double sprint = 1.0D;
    /** 倍率の目標(森の地図を聞いた tick に決め、間の tick は使い回す)。 */
    private double sprintGoal = 1.0D;
    /** 森の地図を聞く間隔(tick)【仮】。 */
    static final int SPRINT_LOOK_EVERY = 5;
    /** クライアント: 手付けの走りにしているか(速さのヒステリシス)。 */
    private boolean keyedRun;
    /** クライアント: 足の運びから出す胴・首・尾の動き。 */
    private final GaitSway sway = new GaitSway();
    /** クライアント: 泳ぐ間に描く体を水面へ下ろす割合(0〜1、前の tick の値も)。体そのものは下げない(岸へ上がれなくなるので)。 */
    private float swimSink, swimSinkO;
    /** 計測用(サーバー): 速さの倍率を求めた時間。 */
    public static volatile long sprintNanos, sprintCalls;

    // --- 技の状態(サーバー。保存しない: 読み込み直したら何もしていない状態から) ---
    private final ActionPicker picker = new ActionPicker();
    /** 今の段に入ってからの tick(段を変えると 0、次の tick から 1)。 */
    private int actionTicks;
    @Nullable
    private LivingEntity actionTarget;
    /** この技で一度当てたか(1 回の技で 1 回まで)。 */
    private boolean actionHit;
    /** 飛びかかり: 跳んだ距離(ブロック)・地面を離れたか。 */
    private double leapDistance;
    private boolean leftGround;
    /** 追う Goal が経路を持たずに標的へ真っすぐ舵を取っているか。 */
    private boolean steering;
    /** 確かめ用: {@code /data merge entity <個体> {OvForceAction:<番号>}} で次の tick にその技を始める(-1 = なし)。 */
    private int pendingForce = -1;
    /** 足噛み(サーバー): 今の段に入ってからの tick・この足噛みで当てたか・相手。 */
    private int nipTicks;
    private boolean nipHit;
    @Nullable
    private LivingEntity nipTarget;
    /** 足噛みの結果(GameTest 用): 0 = まだ、1 = 当てた、2 = 外した(向き合われた・届かない)。 */
    private int nipResult;

    // --- クライアント: 動作の見え方 ---
    private int clientAct = ACT_NONE, clientSeq = -1, clientActStart, animSeq = -1;

    public VenamnesEntity(EntityType<? extends VenamnesEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 30);
        // 段 C(11-15): 水も越えてくる。経路は水を通ってよい(重み WATER_MALUS)・水面に浮く(土台は水を通らない作り。この種だけ変える)
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER, WATER_MALUS);
        this.getNavigation().setCanFloat(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION, ACT_NONE);
        builder.define(DATA_SEQ, 0);
        builder.define(DATA_NIP, NIP_NONE);
        builder.define(DATA_CAUTION, 0.5F);
        builder.define(DATA_PROWL, PROWL_NONE);
    }

    /** うろつく・見回すに、追う(技を選ぶ)Goal と標的の Goal を足す(標的はファルキムネスと同じ: 殴られたら反撃・プレイヤー・グループの外の生き物)。 */
    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(1, new VenamnesHuntGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new SeekGoal<>(this, Player.class, e -> true));
        this.targetSelector.addGoal(3, new SeekGoal<>(this, LivingEntity.class,
                e -> !(e instanceof Player) && !(e instanceof ParasitizedMob) && MobGroup.of(e.getType()) == null && !(e instanceof ArmorStand)));
    }

    /**
     * 自分で標的を探す Goal(バニラの最寄りの標的。プレイヤーはサバイバル・アドベンチャーだけ = バニラの決まり)。
     * 確かめ用: 名前が {@code nostroll} で始まる個体は自分では探さない(GameTest の隣のテスト場の Mob を拾わない。標的はテストが置く)。
     */
    static final class SeekGoal<T extends LivingEntity> extends NearestAttackableTargetGoal<T> {
        SeekGoal(VenamnesEntity mob, Class<T> type, java.util.function.Predicate<LivingEntity> filter) {
            super(mob, type, 10, true, false, filter);
        }

        @Override
        public boolean canUse() {
            if (this.mob.hasCustomName() && this.mob.getCustomName().getString().startsWith("nostroll")) return false;
            return super.canUse();
        }
    }

    // ------------------------------------------------------------------ 型の値

    @Override
    protected double[] legHomeLeft() {
        return LEG_HOME_LEFT;
    }

    @Override
    protected double[] legHomeFwd() {
        return LEG_HOME_FWD;
    }

    @Override
    protected int[] legGroups() {
        return LEG_GROUP;
    }

    @Override
    protected double[] bendFront() {
        return BEND_FRONT;
    }

    @Override
    protected double[] bendBack() {
        return BEND_BACK;
    }

    @Override
    protected OvergrownNodeEvaluator.LongBody longBodyWeights() {
        return LONG_BODY;
    }

    @Override
    protected float maxMoveOff() {
        return MOVE_OFF;
    }

    @Override
    protected float minTurnAdvance() {
        return TURN_ADVANCE;
    }

    @Override
    protected double strollSpeed() {
        return STROLL_SPEED;
    }

    @Override
    protected double cullingRadius() {
        return 4.5D * this.getScale();
    }

    /** 移動の速さに掛ける倍率: 狩りの疾走の間は {@link #HUNT_SPEED}(開けた所の倍率は掛けない。11-15 ユーザー決定)、ほかは開けた所の倍率。 */
    @Override
    protected double gaitBoost() {
        return isHuntSprinting() ? def("hunt").get("speedMult", HUNT_SPEED) : this.sprint;
    }

    public double sprintFactor() {
        return this.sprint;
    }

    // ------------------------------------------------------------------ 速さ

    /** 開けた所の倍率を目標へ寄せる(サーバー、移動の後)。 */
    @Override
    protected void tickRunner() {
        if (!this.personalitySet) setCaution(FalcimnesPersonality.roll(this.random));   // 湧いた個体ごとに 1 回(U 字の分布)
        tickAction();
        tickTerrain();
        long t0 = System.nanoTime();
        double target = 1.0D;
        boolean hurrying = currentSpeedModifier() >= 1.0D && this.onGround();
        if (hurrying && this.tickCount % SPRINT_LOOK_EVERY != 0) {
            target = this.sprintGoal;   // 森の地図を聞くのは 5 tick に 1 回(裏のスレッドを混ませない)
        } else if (hurrying && this.level() instanceof ServerLevel sl) {
            ForestMap map = ForestMap.of(sl);
            float yaw = this.getYRot() * Mth.DEG_TO_RAD;
            double fx = -Mth.sin(yaw), fz = Mth.cos(yaw);
            int y = this.getBlockY();
            double clear = Double.MAX_VALUE;
            boolean known = false;
            for (double ahead = 0.0D; ahead <= 4.0D; ahead += 4.0D) {
                float c = map.clearance(Mth.floor(this.getX() + fx * ahead), y, Mth.floor(this.getZ() + fz * ahead));
                if (Float.isNaN(c)) continue;
                known = true;
                clear = Math.min(clear, c);
            }
            if (known) {
                target = sprintTarget(clear, getTurnRate());
            } else {
                target = this.sprint;   // 地図がまだ無い: 今のまま
            }
        }
        this.sprintGoal = target;
        this.sprint += Mth.clamp(target - this.sprint, -SPRINT_RATE * 2.0D, SPRINT_RATE);   // 落とすのは速く
        sprintNanos += System.nanoTime() - t0;
        sprintCalls++;
        if (DEV_HOOKS && this.hasCustomName()) devHooks(this.getCustomName().getString());
    }

    /**
     * 確かめ用(テストクライアントだけ): 名前が {@code nostroll_run} で始まる個体は、経路が尽きるたびに 16〜28 ブロック先の陸へ
     * 速さ 1.0 で走る(10 体の重さを測る・走る姿を撮る)。名前に {@code trace} を含む個体は 10 tick ごとに速さと倍率を書く。
     */
    private void devHooks(String name) {
        if (name.startsWith("nostroll_run") && this.getNavigation().isDone()) {
            net.minecraft.world.phys.Vec3 p = net.minecraft.world.entity.ai.util.LandRandomPos.getPos(this, 28, 6);
            if (p != null && p.distanceToSqr(this.position()) > 16.0D * 16.0D) this.getNavigation().moveTo(p.x, p.y, p.z, 1.0D);
        }
        if (name.contains("perflog") && this.level().getGameTime() % 200L == 0L) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "VEN_CHASE 引き直し %d 回 次へ回した %d 回 計 %.1f ms 1 回の最悪 %.2f ms / 回り道の細かい探索 計 %.1f ms・下調べの 1 tick の最悪 %.2f ms",
                    com.yayuy.overgrownvegetation.entity.runner.ChasePathBudget.calls,
                    com.yayuy.overgrownvegetation.entity.runner.ChasePathBudget.deferred, com.yayuy.overgrownvegetation.entity.runner.ChasePathBudget.nanos / 1e6,
                    com.yayuy.overgrownvegetation.entity.runner.ChasePathBudget.worst / 1e6,
                    com.yayuy.overgrownvegetation.entity.runner.LongBodyNavigation.detourNanos / 1e6,
                    com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.maxTickNanos / 1e6));
            com.yayuy.overgrownvegetation.entity.runner.ChasePathBudget.resetStats();
            com.yayuy.overgrownvegetation.entity.runner.LongBodyNavigation.detourNanos = 0L;
            com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.maxTickNanos = 0L;
        }
        if (name.contains("trace") && this.tickCount % 10 == 0) {
            double v = Math.sqrt(sq(this.getX() - this.xo) + sq(this.getZ() - this.zo));
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "VEN_GAIT %s pos %.1f %.1f %.1f 速さ %.3f 倍率 %.2f 目標 %.2f 急ぎ %.2f 舵 %s 回る %.1f 経路 %s", name, this.getX(), this.getY(), this.getZ(), v, this.sprint, this.sprintGoal, currentSpeedModifier(), this.steering,
                    getTurnRate(), this.getNavigation().isDone() ? "なし" : "あり"));
        }
    }

    /**
     * 開けた所の倍率の目標: 体の前の幹までの一番近い距離 {@code clear}(ブロック)が {@link #FOREST_CLEAR} 以下で 1、{@link #OPEN_CLEAR} 以上で
     * {@link #SPRINT_OPEN}(間はなめらかに)。1 tick に {@link #SPRINT_TURN_DROP} 度回っていれば 1。
     */
    public static double sprintTarget(double clear, float turnRate) {
        double open = Mth.clamp((clear - FOREST_CLEAR) / (OPEN_CLEAR - FOREST_CLEAR), 0.0D, 1.0D);
        open = open * open * (3.0D - 2.0D * open);
        double turning = Mth.clamp(1.0D - Math.abs(turnRate) / SPRINT_TURN_DROP, 0.0D, 1.0D);
        return 1.0D + (SPRINT_OPEN - 1.0D) * open * turning;
    }

    // ------------------------------------------------------------------ 技(段 A 11-15)

    public int getAction() {
        return this.entityData.get(DATA_ACTION);
    }

    /**
     * 技の最中か(同期した動作から。クライアントでも読める = 脚の手続き・体の曲げを止める)。狩りの疾走の走っている間は技の最中に
     * 数えない(走りながら曲がるので体の曲げは効かせる。脚は速さで手付けの走りになる。動かすのは追う Goal)。
     */
    @Override
    public boolean isActing() {
        int a = getAction();
        return a != ACT_NONE && a != ACT_HUNT_SPRINT;
    }

    /** 狩りの疾走で走っている間か。 */
    public boolean isHuntSprinting() {
        return getAction() == ACT_HUNT_SPRINT;
    }

    public int getNip() {
        return this.entityData.get(DATA_NIP);
    }

    /** 足噛みの結果(GameTest 用): 0 = まだ、1 = 当てた、2 = 外した。 */
    public int nipResult() {
        return this.nipResult;
    }

    /** 技の調整値(設計データ {@code actions.json} の {@code venamnes})。 */
    public static ActionDef def(String id) {
        return DesignData.actions().def(ID, id);
    }

    /** 選んで出す通常技(段 A: 噛みつき・飛びかかり)。 */
    static List<ActionDef> normalDefs() {
        return List.of(def("bite"), def("pounce"), def("nip"), def("hunt"));
    }

    ActionPicker picker() {
        return this.picker;
    }

    /** 前触れの間は標的へ少しずつ向き直る(遅く)、ほかの技の間は回らない(希望が出ないので止まる)。 */
    @Override
    protected float maxTurnPerTick() {
        return switch (getAction()) {
            case ACT_BITE_WINDUP, ACT_HUNT_WINDUP -> BITE_TRACK_TURN;
            case ACT_POUNCE_WINDUP -> POUNCE_TRACK_TURN;
            default -> TURN_PER_TICK;
        };
    }

    /** まっすぐの舵の間も「急いでいる」(開けた所の倍率を掛ける。経路が無いので土台の読み方では 0 になる)。 */
    @Override
    protected double currentSpeedModifier() {
        return this.steering ? this.steerSpeed : super.currentSpeedModifier();
    }

    /** まっすぐの舵の速さ倍率(つけ回しは遅い)。 */
    private double steerSpeed = 1.0D;

    void setSteerSpeed(double s) {
        this.steerSpeed = s;
    }

    void setSteering(boolean on) {
        this.steering = on;
    }

    boolean isSteering() {
        return this.steering;
    }

    /** 確かめ用: 直す前の追い方(舵・予算・探す数の絞りなし)で追うか。 */
    /** 確かめ用: 名前に trace を含む個体(開発用のログ)。 */
    boolean devTrace() {
        return DEV_HOOKS && this.hasCustomName() && this.getCustomName().getString().contains("trace");
    }

    boolean devPlainChase() {
        return this.hasCustomName() && this.getCustomName().getString().contains("plainchase");
    }

    /** 標的への向き(度)。 */
    float yawTo(Entity target) {
        return (float) (Mth.atan2(target.getZ() - this.getZ(), target.getX() - this.getX()) * Mth.RAD_TO_DEG) - 90.0F;
    }

    /** 標的が正面からどれだけずれているか(度、−180〜180)。 */
    float offFront(Entity target) {
        return Mth.wrapDegrees(yawTo(target) - this.getYRot());
    }

    /** 選んだ技を始める(追う Goal から)。 */
    void startSkill(ActionDef d, LivingEntity target) {
        this.picker.used(d);
        this.actionTarget = target;
        this.actionHit = false;
        if (d.id().equals("nip")) {
            startNip(target);
            return;
        }
        setAction(switch (d.id()) {
            case "pounce" -> ACT_POUNCE_WINDUP;
            case "hunt" -> ACT_HUNT_WINDUP;
            default -> ACT_BITE_WINDUP;
        });
    }

    // ------------------------------------------------------------------ 性格(段 C 11-15)
    // 決定(11-14 ユーザー): 慎重な個体は 16〜24 ブロック離れてつけ回し、振り向くと止まり、背を向けて逃げた瞬間に狩りの疾走。
    // 大胆な個体は見つけたら真っすぐ来る。ファルキムネスと同じ仕組み = 連続したつまみ(慎重さ 0〜1)を U 字の分布で振る。
    // 慎重さ 0.5 以下は大胆(段 A・B の振る舞いのまま)。数値は actions.json の擬似の技 personality【仮】。

    private static final EntityDataAccessor<Float> DATA_CAUTION = SynchedEntityData.defineId(VenamnesEntity.class, EntityDataSerializers.FLOAT);
    /** つけ回しの見え方(同期する): 0 = ふつう、1 = つけ回し(低い構えで歩く)、2 = 振り向かれて固まる。 */
    private static final EntityDataAccessor<Integer> DATA_PROWL = SynchedEntityData.defineId(VenamnesEntity.class, EntityDataSerializers.INT);
    public static final int PROWL_NONE = 0, PROWL_STALK = 1, PROWL_FREEZE = 2;
    private boolean personalitySet;

    public float caution() {
        return this.entityData.get(DATA_CAUTION);
    }

    /** 性格を決める(0〜1。GameTest は 0 = 大胆・1 = 慎重を置く)。 */
    public void setCaution(float caution) {
        this.entityData.set(DATA_CAUTION, Mth.clamp(caution, 0.0F, 1.0F));
        this.personalitySet = true;
    }

    /** 慎重さの片側(0.5 以下は 0 = 大胆、1 で 1)。 */
    public double wary() {
        return FalcimnesPersonality.over(caution());
    }

    public int prowlMode() {
        return this.entityData.get(DATA_PROWL);
    }

    void setProwlMode(int m) {
        if (this.entityData.get(DATA_PROWL) != m) this.entityData.set(DATA_PROWL, m);
    }

    /** 性格の数値({@code actions.json} の擬似の技 {@code personality} の extra)。 */
    static double pv(String key, double fallback) {
        return def("personality").get(key, fallback);
    }

    // ------------------------------------------------------------------ 段差と水(段 C 11-15)

    /** 経路を引いている最中か(この間だけ「上れる段」を {@link #LEAP_HEIGHT} にする)。 */
    private boolean pathing;

    /**
     * 上れる段: 経路を引く間だけ {@link #LEAP_HEIGHT}(バニラの経路は {@code maxUpStep} で「何段上の隣へ行けるか」を決める。
     * 跳べば届く段を経路に入れる)。動く間はふつうの値(体が一気に 3 段持ち上がらないように)。
     */
    @Override
    public float maxUpStep() {
        return this.pathing ? Math.max(LEAP_HEIGHT, super.maxUpStep()) : super.maxUpStep();
    }

    /** 経路: 土台と同じ(長い体の重み + 回り道)に、引く間だけ跳べる段を上れることにする。この種だけ。 */
    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
        return new com.yayuy.overgrownvegetation.entity.runner.LongBodyNavigation(this, level, LONG_BODY, () -> true) {
            @Override
            protected net.minecraft.world.level.pathfinder.Path createPath(java.util.Set<net.minecraft.core.BlockPos> targets, int regionOffset,
                                                                           boolean offsetUpward, int accuracy, float followRange) {
                VenamnesEntity v = VenamnesEntity.this;
                v.pathing = !(v.hasCustomName() && v.getCustomName().getString().contains("noleap"));   // 確かめ用: 名前 noleap は跳び上がりを経路に入れない(重さの比べ)
                try {
                    return super.createPath(targets, regionOffset, offsetUpward, accuracy, followRange);
                } finally {
                    v.pathing = false;
                }
            }
        };
    }

    /** 跳び上がりの行き先(経路の次の点の足元)。 */
    @Nullable
    private Vec3 leapTo;
    /** 狩りの疾走の途中で跳んだか(着地の後は疾走へ戻らず息切れへ。段差で疾走が途切れる = 相手の逃げる機会)。 */
    private boolean leapFromSprint;

    /**
     * 段差と水(サーバー、移動の後): 経路の次の点が {@link #LEAP_MIN_RISE} 段以上高く近ければ跳び上がりを始める / 水の中で前へ進む間は
     * {@link #SWIM_SPEED} で泳ぐ(バニラの水の中は 0.02 ずつしか押さず、長い体がほとんど進まない)。
     */
    private void tickTerrain() {
        if (isActing() || !this.isAlive()) return;
        if (this.isInWater()) {
            if (currentSpeedModifier() > 0.0D || this.steering) {
                float yaw = this.getYRot() * Mth.DEG_TO_RAD;
                Vec3 v = this.getDeltaMovement();
                double s = SWIM_SPEED * this.getScale();
                this.setDeltaMovement(-Mth.sin(yaw) * s, v.y, Mth.cos(yaw) * s);
            }
            return;
        }
        if (!this.onGround()) return;
        net.minecraft.world.level.pathfinder.Path p = this.getNavigation().getPath();
        if (p == null || p.isDone()) return;
        net.minecraft.world.level.pathfinder.Node n = p.getNextNode();
        int rise = n.y - this.getBlockY();
        if (rise < LEAP_MIN_RISE || rise > Mth.ceil(LEAP_HEIGHT)) return;
        // 体が立つ所(大きい体の経路の点は足場の北西の角で、体は点 + floor(幅 + 1) / 2 に立つ。Path#getEntityPosAtNode)
        Vec3 at = p.getNextEntityPos(this);
        double dx = at.x - this.getX(), dz = at.z - this.getZ();
        if (dx * dx + dz * dz > LEAP_REACH * LEAP_REACH * this.getScale() * this.getScale()) return;
        this.leapTo = new Vec3(at.x, n.y, at.z);
        this.leapFromSprint = isHuntSprinting();
        this.leftGround = false;
        setAction(ACT_LEAP_WINDUP);
    }

    /** 跳び上がり: 頂点が着く段の {@link #LEAP_CLEAR} 上になる上向きの勢いを {@link LeapArc} で探し、横は宙の長さで割る。 */
    private void launchUp() {
        Vec3 to = this.leapTo != null ? this.leapTo : this.position().add(0, LEAP_HEIGHT, 0);
        double rise = to.y - this.getY() + LEAP_CLEAR;
        double lift = 0.42D;
        while (lift < 1.2D && LeapArc.reach(0.0D, lift, 60)[1] < rise) lift += 0.02D;
        double dx = to.x - this.getX(), dz = to.z - this.getZ(), dist = Math.sqrt(dx * dx + dz * dz);
        double h = 0.05D;
        while (h < 1.0D && !LeapArc.canReach(h, lift, 60, dist, to.y - this.getY())) h += 0.02D;
        double ux = dist > 1.0E-3D ? dx / dist : 0.0D, uz = dist > 1.0E-3D ? dz / dist : 0.0D;
        this.setDeltaMovement(ux * h, lift, uz * h);
        this.setOnGround(false);
        this.hasImpulse = true;
    }

    /** 宙で、まだ着く段より低い間は崖の縁へ寄せ続ける(壁に当たると横の勢いが消えるので)。 */
    private void leapAirPush() {
        if (this.leapTo == null || this.onGround()) return;
        double dx = this.leapTo.x - this.getX(), dz = this.leapTo.z - this.getZ(), dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.3D) return;
        Vec3 v = this.getDeltaMovement();
        double hv = Math.sqrt(v.x * v.x + v.z * v.z);
        if (hv >= LEAP_AIR_PUSH) return;
        this.setDeltaMovement(dx / dist * LEAP_AIR_PUSH, v.y, dz / dist * LEAP_AIR_PUSH);
    }

    // ------------------------------------------------------------------ 足噛み(段 B 11-15)

    /** 相手が自分の左にいるか(体の向きに対して)。左 = 向いている向きを右手に 90 度回した逆(Minecraft の向きで (cos, sin))。 */
    boolean onLeft(Entity t) {
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        return (t.getX() - this.getX()) * Mth.cos(yaw) + (t.getZ() - this.getZ()) * Mth.sin(yaw) > 0.0D;
    }

    /** 相手の位置を体の向きで分けた {前, 横(左が +)}(ブロック)。 */
    double[] relative(Entity t) {
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        double dx = t.getX() - this.getX(), dz = t.getZ() - this.getZ();
        return new double[]{dx * -Mth.sin(yaw) + dz * Mth.cos(yaw), dx * Mth.cos(yaw) + dz * Mth.sin(yaw)};
    }

    /** 足噛みの届く所(首を横へ曲げて届く): 前へ 0.5〜4.5・横へ 0.4〜3.0・高さの差 2 以内。 */
    boolean inNipZone(LivingEntity t) {
        double s = this.getScale();
        double[] r = relative(t);
        double side = Math.abs(r[1]);
        return r[0] >= NIP_FWD_MIN * s && r[0] <= NIP_FWD_MAX * s && side >= NIP_SIDE_MIN * s && side <= NIP_SIDE_MAX * s
                && Math.abs(t.getY() - this.getY()) < 2.0D;
    }

    /** 相手がこちらを向いているか(相手の向きと「相手から自分への向き」のずれが {@link #NIP_FACING} 度以内)。 */
    static boolean facesMe(LivingEntity t, Entity me) {
        float toMe = (float) (Mth.atan2(me.getZ() - t.getZ(), me.getX() - t.getX()) * Mth.RAD_TO_DEG) - 90.0F;
        return Math.abs(Mth.wrapDegrees(toMe - t.getYHeadRot())) <= NIP_FACING;
    }

    /** 足噛みを始める(相手のいる側の首を振る)。走りは止めない。 */
    void startNip(LivingEntity target) {
        this.nipTarget = target;
        this.nipHit = false;
        this.nipResult = 0;
        setNip(onLeft(target) ? NIP_L_WINDUP : NIP_R_WINDUP);
    }

    /** 足噛みをいますぐ始める(GameTest・確かめ用)。左右は今の標的の側。 */
    public void forceNip() {
        LivingEntity t = this.getTarget();
        if (t == null) {   // 確かめ用: 標的が無ければ、近く(6 ブロック)のいちばん近い生き物
            t = this.level().getNearestEntity(LivingEntity.class, net.minecraft.world.entity.ai.targeting.TargetingConditions.forNonCombat().range(6.0D),
                    this, this.getX(), this.getY(), this.getZ(), this.getBoundingBox().inflate(6.0D));
        }
        this.picker.used(def("nip"));
        if (t != null) startNip(t);
    }

    /** 確かめ用: {@code /data merge entity <個体> {OvForceNip:1b}} で次の tick に足噛み。 */
    private boolean pendingNip;

    private void setNip(int n) {
        this.entityData.set(DATA_NIP, n);
        this.nipTicks = 0;
    }

    /** 足噛みを 1 tick 進める。実行の 2〜3 tick 目に、相手が届く所にいて・こちらを向いていなければ噛んで足を鈍らせる。 */
    private void tickNip() {
        int n = getNip();
        if (n == NIP_NONE) return;
        if (isActing()) {   // ほかの技が始まった(足噛みは捨てる)
            setNip(NIP_NONE);
            return;
        }
        this.nipTicks++;
        LivingEntity t = this.nipTarget != null && this.nipTarget.isAlive() && this.nipTarget.level() == this.level() ? this.nipTarget : null;
        int base = n >= NIP_R_WINDUP ? NIP_R_WINDUP : NIP_L_WINDUP, step = n - base;
        if (step == 0) {
            if (this.nipTicks >= NIP_WINDUP_T) setNip(base + 1);
        } else if (step == 1) {
            if (!this.nipHit && t != null && this.nipTicks >= NIP_HIT_FROM && this.nipTicks <= NIP_HIT_TO) {
                if (inNipZone(t) && !facesMe(t, this)) {
                    this.nipHit = true;
                    this.nipResult = 1;
                    ActionDef d = def("nip");
                    float dmg = (float) d.damage();
                    if (t.hurt(this.damageSources().mobAttack(this), dmg > 0.0F ? dmg : 4.0F)) this.setLastHurtMob(t);
                    t.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN,
                            (int) d.get("slowTicks", 60.0D), (int) d.get("slowAmp", 1.0D)), this);
                }
            }
            if (this.nipTicks >= NIP_EXEC_T) {
                if (!this.nipHit) this.nipResult = 2;
                if (DEV_HOOKS && this.hasCustomName() && this.getCustomName().getString().contains("trace")) {
                    com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("VEN_NIP 結果 {} 側 {}", this.nipResult == 1 ? "当てた" : "外した", base == NIP_L_WINDUP ? "左" : "右");
                }
                setNip(base + 2);
            }
        } else if (this.nipTicks >= NIP_RECOVER_T) {
            setNip(NIP_NONE);
        }
    }

    /** 技をいますぐ始める(GameTest・確かめ用。間合い・クールダウンは見ない)。標的は今の標的。 */
    public void forceAction(int act) {
        this.actionTarget = this.getTarget();
        this.actionHit = false;
        switch (act) {
            case ACT_BITE_WINDUP -> this.picker.used(def("bite"));
            case ACT_POUNCE_WINDUP -> this.picker.used(def("pounce"));
            case ACT_HUNT_WINDUP -> this.picker.used(def("hunt"));
            default -> {
            }
        }
        setAction(act);
    }

    private void setAction(int act) {
        this.entityData.set(DATA_ACTION, act);
        this.entityData.set(DATA_SEQ, this.entityData.get(DATA_SEQ) + 1);
        this.actionTicks = 0;
        if (act != ACT_NONE) {
            this.entityData.set(DATA_PROWL, PROWL_NONE);
            this.getNavigation().stop();
            this.steering = false;
        }
    }

    /** 今の段に入ってからの tick(GameTest 用)。 */
    public int actionTicks() {
        return this.actionTicks;
    }

    /**
     * 技を 1 tick 進める(サーバー、移動の後・旋回の前)。前触れ → 実行 → 隙の段を時刻どおりに切り替え、当たりの時刻に当てる。
     * 前触れの間だけ標的へ向き直る(固める tick より前まで)。実行と隙の間は動かない・回らない(反撃の番)。
     */
    private void tickAction() {
        this.picker.tick();
        if (this.pendingForce >= 0) {
            int f = this.pendingForce;
            this.pendingForce = -1;
            forceAction(f);
        }
        if (this.pendingNip) {
            this.pendingNip = false;
            forceNip();
        }
        tickNip();
        int act = getAction();
        if (act == ACT_NONE) return;
        if (!this.isAlive()) {
            setAction(ACT_NONE);
            return;
        }
        this.actionTicks++;
        LivingEntity t = this.actionTarget != null && this.actionTarget.isAlive() && this.actionTarget.level() == this.level() ? this.actionTarget : null;
        int k = this.actionTicks;
        switch (act) {
            case ACT_BITE_WINDUP -> {
                if (t != null && k <= BITE_T.executeStart() - BITE_LOCK) wantFacing(yawTo(t), TURN_ACTION);
                if (k >= BITE_T.executeStart()) setAction(ACT_BITE);
            }
            case ACT_BITE -> {
                if (!this.actionHit && t != null && k >= BITE_HIT_FROM && k <= BITE_HIT_TO && biteReaches(t)) {
                    this.actionHit = true;
                    this.doHurtTarget(t);
                }
                if (k >= BITE_T.execute()) setAction(ACT_BITE_RECOVER);
            }
            case ACT_BITE_RECOVER -> {
                if (k >= BITE_T.recover()) setAction(ACT_NONE);
            }
            case ACT_POUNCE_WINDUP -> {
                if (t != null && k <= POUNCE_WINDUP_T - POUNCE_LOCK) wantFacing(yawTo(t), TURN_ACTION);
                if (k >= POUNCE_WINDUP_T) {
                    // 跳ぶ距離は、固めた向きの先で頭が今の相手の位置へ届く所(相手が横へ出ればそのまま外れる)
                    double d = t != null ? Math.sqrt(this.distanceToSqr(t.getX(), this.getY(), t.getZ())) : POUNCE_MIN_LEAP + HEAD_REACH;
                    this.leapDistance = Mth.clamp(d - HEAD_REACH * this.getScale(), POUNCE_MIN_LEAP, POUNCE_MAX_LEAP);
                    this.leftGround = false;
                    setAction(ACT_POUNCE);
                }
            }
            case ACT_POUNCE -> {
                if (k == POUNCE_LIFTOFF) launch(this.leapDistance, def("pounce").get("lift", 0.42D));
                if (!this.onGround()) this.leftGround = true;
                if (!this.actionHit && t != null && k >= POUNCE_HIT_FROM && headTouches(t)) hitPounce(t);
                boolean landed = this.leftGround && this.onGround() && k > POUNCE_LIFTOFF + 1;
                if (landed || k > POUNCE_LIFTOFF + POUNCE_MAX_AIR) setAction(ACT_POUNCE_RECOVER);
            }
            case ACT_POUNCE_RECOVER -> {
                if (!this.actionHit && t != null && k <= LAND_HIT_TO && headTouches(t)) hitPounce(t);
                if (k >= POUNCE_RECOVER_T) setAction(ACT_NONE);
            }
            // --- 狩りの疾走(段 B) ---
            case ACT_HUNT_WINDUP -> {
                // 叫ぶ間は動かない(標的へ向き直るだけ。立ち上がって叫ぶ前触れ = 背を向けて逃げる猶予)
                if (t != null) wantFacing(yawTo(t), TURN_ACTION);
                if (k >= HUNT_WINDUP_T) setAction(ACT_HUNT_SPRINT);
            }
            case ACT_HUNT_SPRINT -> {
                // 走らせるのは追う Goal(huntSprintTick)。ここは長さと、最後の飛びかかりへの切り替えだけ
                int max = (int) def("hunt").get("sprintTicks", HUNT_SPRINT_T);
                if (t == null || k >= max) {
                    setAction(ACT_HUNT_RECOVER);   // 見失った・時間切れ: 飛びかからずに息切れへ
                } else {
                    double d = Math.sqrt(this.distanceToSqr(t.getX(), this.getY(), t.getZ()));
                    double s = this.getScale();
                    if (this.onGround() && d <= HUNT_POUNCE_MAX * s && Math.abs(offFront(t)) <= HUNT_POUNCE_CONE
                            && Math.abs(t.getY() - this.getY()) < POUNCE_DY) {
                        this.leapDistance = Mth.clamp(d - HEAD_REACH * s, POUNCE_MIN_LEAP, POUNCE_MAX_LEAP);
                        this.leftGround = false;
                        this.actionHit = false;
                        setAction(ACT_HUNT_POUNCE);
                    }
                }
            }
            case ACT_HUNT_POUNCE -> {
                if (k == POUNCE_LIFTOFF) launch(this.leapDistance, def("hunt").get("lift", 0.42D));
                if (!this.onGround()) this.leftGround = true;
                if (!this.actionHit && t != null && k >= POUNCE_HIT_FROM && headTouches(t)) hitHunt(t);
                boolean landed = this.leftGround && this.onGround() && k > POUNCE_LIFTOFF + 1;
                if (landed || k > POUNCE_LIFTOFF + POUNCE_MAX_AIR) setAction(ACT_HUNT_LAND);
            }
            case ACT_HUNT_LAND -> {
                if (!this.actionHit && t != null && k <= LAND_HIT_TO && headTouches(t)) hitHunt(t);
                if (k >= POUNCE_RECOVER_T) setAction(ACT_HUNT_RECOVER);
            }
            case ACT_HUNT_RECOVER -> {
                if (k >= HUNT_RECOVER_T) setAction(ACT_NONE);
            }
            // --- 段差を跳び上がる(段 C) ---
            case ACT_LEAP_WINDUP -> {
                if (this.leapTo != null) wantFacing((float) (Mth.atan2(this.leapTo.z - this.getZ(), this.leapTo.x - this.getX()) * Mth.RAD_TO_DEG) - 90.0F, TURN_ACTION);
                if (k >= LEAP_WINDUP_T) setAction(ACT_LEAP);
            }
            case ACT_LEAP -> {
                if (k == LEAP_LIFTOFF) launchUp();
                if (!this.onGround()) this.leftGround = true;
                if (k > LEAP_LIFTOFF) leapAirPush();
                boolean landed = this.leftGround && this.onGround() && k > LEAP_LIFTOFF + 1;
                if (landed || k > LEAP_LIFTOFF + LEAP_MAX_AIR) setAction(ACT_LEAP_RECOVER);
            }
            case ACT_LEAP_RECOVER -> {
                if (k >= LEAP_RECOVER_T) {
                    this.leapTo = null;
                    setAction(this.leapFromSprint ? ACT_HUNT_RECOVER : ACT_NONE);
                    this.leapFromSprint = false;
                }
            }
            default -> setAction(ACT_NONE);
        }
    }

    /** 噛みつきが届くか: 体の真ん中から相手の箱の一番近い所まで {@link #BITE_REACH} 以内・正面から {@link #BITE_HIT_CONE} 度以内・高さの差 {@link #BITE_DY} 以内。 */
    boolean biteReaches(LivingEntity t) {
        double d = Math.sqrt(this.distanceToSqr(t.getX(), this.getY(), t.getZ())) - t.getBbWidth() * 0.5D;
        return d <= BITE_REACH * this.getScale() && Math.abs(offFront(t)) <= BITE_HIT_CONE && Math.abs(t.getY() - this.getY()) < BITE_DY;
    }

    /** 宙・着地の口: 頭の点(体の前 {@link #HEAD_REACH}、高さは目の高さ)が相手の箱を {@link #POUNCE_BITE_RADIUS} 膨らませた中に入るか。 */
    boolean headTouches(LivingEntity t) {
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        double r = HEAD_REACH * this.getScale();
        Vec3 head = new Vec3(this.getX() - Mth.sin(yaw) * r, this.getY() + this.getEyeHeight(), this.getZ() + Mth.cos(yaw) * r);
        return t.getBoundingBox().inflate(POUNCE_BITE_RADIUS * this.getScale()).contains(head);
    }

    private void hitHunt(LivingEntity t) {
        hitWith(t, def("hunt").damage());
    }

    private void hitPounce(LivingEntity t) {
        hitWith(t, def("pounce").damage());
    }

    private void hitWith(LivingEntity t, double damage) {
        this.actionHit = true;
        float dmg = (float) damage;
        if (dmg <= 0.0F) dmg = (float) this.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        if (t.hurt(this.damageSources().mobAttack(this), dmg)) {
            this.setLastHurtMob(t);
        }
    }

    /**
     * 向いている先へ {@code distance} ブロック跳ぶ。上向きの勢い {@code lift} を決めると、宙にいる長さは横の勢いに依らないので、
     * 横の勢いは「横 1 で跳んだときの距離」({@link LeapArc}、バニラの宙の物理)で割って決まる。踏み切りの tick は地面の摩擦
     * (0.6 × 0.91)が掛からないよう、地面を離れたことにする。
     */
    private void launch(double distance, double lift) {
        double unit = LeapArc.reach(1.0D, lift, 80)[0];
        double h = unit > 1.0E-3D ? distance / unit : 0.0D;
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        this.setDeltaMovement(-Mth.sin(yaw) * h, lift, Mth.cos(yaw) * h);
        this.setOnGround(false);
        this.hasImpulse = true;
    }

    /** 確かめ用: 跳ぶ距離(GameTest 用)。 */
    public double leapDistance() {
        return this.leapDistance;
    }

    // --- 光(クライアント。発光マスク 2 枚の明るさ 0〜1。時刻は技のアニメの一覧と対)【仮の消え方・点き方の長さ】 ---
    /** 点くまで・消えるまでの長さ(tick)。 */
    static final float GLOW_IN = 3.0F, GLOW_OUT = 2.0F;
    /** 口が消える bite の時刻(0.17 秒)・腱が消える pounce の時刻(0.15 秒、足が離れる)。 */
    static final float MOUTH_OFF_AT = 3.4F, TENDON_OFF_AT = 3.0F;

    /** クライアント: 今の段に入ってからの tick(補間込み)。 */
    private float clientAge(float pt) {
        return this.tickCount - this.clientActStart + pt;
    }

    /** 口の光: 噛みつきの前触れ(0 秒から)〜 bite の 0.17 秒。 */
    public float mouthGlow(float pt) {
        float a = clientAge(pt);
        return switch (this.clientAct) {
            case ACT_BITE_WINDUP -> Mth.clamp(a / GLOW_IN, 0.0F, 1.0F);
            case ACT_BITE -> Mth.clamp(1.0F - (a - MOUTH_OFF_AT) / GLOW_OUT, 0.0F, 1.0F);
            // 狩りの疾走の叫び: hunt_windup の 0.40〜1.05 秒
            case ACT_HUNT_WINDUP -> Math.min(Mth.clamp((a - HUNT_MOUTH_ON) / GLOW_IN, 0.0F, 1.0F),
                    Mth.clamp(1.0F - (a - HUNT_MOUTH_OFF) / GLOW_OUT, 0.0F, 1.0F));
            default -> 0.0F;
        };
    }

    /** 腱の光: 飛びかかりの前触れ(0 秒から)〜 pounce の 0.15 秒(足が離れる)。 */
    public float tendonGlow(float pt) {
        float a = clientAge(pt);
        return switch (this.clientAct) {
            case ACT_POUNCE_WINDUP -> Mth.clamp(a / GLOW_IN, 0.0F, 1.0F);
            case ACT_POUNCE -> Mth.clamp(1.0F - (a - TENDON_OFF_AT) / GLOW_OUT, 0.0F, 1.0F);
            // 狩りの疾走: 叫びの 0.40 秒から点き、疾走・最後の飛びかかり・着地の間ずっと。息切れの頭から 10 tick で消える
            case ACT_HUNT_WINDUP -> Mth.clamp((a - HUNT_TENDON_ON) / GLOW_IN, 0.0F, 1.0F);
            case ACT_HUNT_SPRINT, ACT_HUNT_POUNCE, ACT_HUNT_LAND -> 1.0F;
            case ACT_HUNT_RECOVER -> Mth.clamp(1.0F - a / HUNT_TENDON_FADE, 0.0F, 1.0F);
            default -> 0.0F;
        };
    }

    // --- 保存(技の状態は保存しない。確かめ用の口だけ) ---

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("OvAction", getAction());
        if (this.personalitySet) {
            CompoundTag p = new CompoundTag();
            p.putFloat("caution", caution());
            tag.put("OvPersonality", p);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("OvForceAction")) this.pendingForce = tag.getInt("OvForceAction");
        if (tag.contains("OvForceNip")) this.pendingNip = true;
        // 段 C: 性格。確かめ用に /data merge entity <個体> {OvPersonality:{caution:1f}} でも置ける
        if (tag.contains("OvPersonality")) setCaution(tag.getCompound("OvPersonality").getFloat("caution"));
    }

    // ------------------------------------------------------------------ 脚

    // --- 足の運び(足の置き場で歩く間)【仮・Claude 11-14 段 1b】 ---
    /**
     * 着いている足が体の下を後ろへ流れる距離(ブロック。= 踏み替えを始めるずれ): 止まりかけは {@link #STEP_MIN}、速さ(ブロック/tick)に
     * {@link #STEP_PER} を掛けて足し、{@link #STEP_MAX}(腰を上げた脚が届く前後の幅)まで。速さ 0.10 で 1.7 → 1 歩 2.8 ブロック・28 tick で左右 1 巡。
     * 段 1 は 0.35 + 速さ × 2 で、足は体の前の 0.5 ブロックの中だけを 15 tick で 1 巡していた(ユーザー「足の運びが小さい・遅い」)。
     */
    static final double STEP_MIN = 0.45D, STEP_PER = 12.5D, STEP_MAX = 2.0D;
    /** 踏み替えの長さ(tick)= 着いている時間 × {@link #SWING_SHARE}、{@link #SWING_MIN}〜{@link #SWING_MAX}。段 1 は 4〜7(速い小股)。 */
    static final double SWING_SHARE = 0.65D;
    static final int SWING_MIN = 6, SWING_MAX = 11;
    /** 足の運びの真ん中を、基準の置き場からこれだけ後ろへ(ブロック)。腰の関節は置き場の 0.37 後ろ = 足は腰の少し前に着き、腰の後ろで離れる。 */
    static final double STEP_CENTER_BACK = 0.3D;
    /** 先回りの上限(tick)と、回っている間の上限。 */
    static final double LEAD_MAX = 8.0D, LEAD_TURNING = 3.0D;
    /** 腰の関節が体の中心より後ろにある量(モデルの px)。 */
    static final double HIP_BACK_PX = 1.5D;

    @Override
    protected void configurePlanter(FootPlanter fp) {
        fp.lift = 0.55D;       // 宙の足を上げる高さ(ブロック)【仮】。段 1 は 0.45
        fp.probeUp = 1.3D;
        fp.probeDown = 2.2D;   // 腰の高さ 1.6 + 少し
    }

    /** 速いほど大股に。ゆっくりの間は長く宙を運び、左右を半周期ずらす(片足が宙の間はもう片足が支える)。 */
    @Override
    protected void tunePlanter(FootPlanter fp) {
        double v = Math.sqrt(sq(this.getX() - this.xo) + sq(this.getZ() - this.zo)) / Math.max(this.getScale(), 0.05F);
        boolean turning = Math.abs(getTurnRate()) > 3.0F;
        double step = Mth.clamp(STEP_MIN + v * STEP_PER, STEP_MIN, STEP_MAX);
        double stance = v > 0.01D ? step / v : 40.0D;   // 足が着いている tick
        int swing = Mth.clamp((int) Math.round(stance * SWING_SHARE), SWING_MIN, turning ? SWING_MIN : SWING_MAX);
        fp.stepAt = step;
        fp.swingTicks = swing;
        fp.restGap = turning || v < 0.02D ? 0 : Mth.clamp((int) Math.round((stance - swing) * 0.5D), 0, 4);
        fp.lead = Mth.clamp((step * 0.5D - STEP_CENTER_BACK) / Math.max(v, 0.01D), 0.0D, turning ? LEAD_TURNING : LEAD_MAX);
    }

    /** 宙の足: 離れてすぐはつま先を垂らし、着く前に少し上げる(ラジアン、+ でつま先が上がる)【仮】。 */
    @Override
    public double footPitch(int leg, float pt) {
        FootPlanter fp = footPlanter();
        if (fp == null || !fp.swinging(leg)) return 0.0D;
        double s = Mth.clamp(fp.swingProgress(leg) - (1.0D - pt) / Math.max(fp.swingTicks, 1), 0.0D, 1.0D);   // 描く足先は 1 tick 前からの補間
        double drop = Math.sin(Math.PI * Mth.clamp(s / 0.6D, 0.0D, 1.0D));
        double up = Math.sin(Math.PI * Mth.clamp((s - 0.6D) / 0.4D, 0.0D, 1.0D));
        return -0.55D * drop + 0.22D * up;
    }

    /** クライアント: 足の置き場の後で、足の運びから胴・首・尾の動きを 1 tick 進める。 */
    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide()) return;
        this.swimSinkO = this.swimSink;
        this.swimSink = Mth.approach(this.swimSink, this.isInWater() && !this.isActing() ? 1.0F : 0.0F, 0.2F);
        int act = getAction(), seq = this.entityData.get(DATA_SEQ);
        if (act != this.clientAct || seq != this.clientSeq) {
            this.clientAct = act;
            this.clientSeq = seq;
            this.clientActStart = this.tickCount;
        }
        FootPlanter fp = footPlanter();
        if (fp == null || !fp.ready()) return;
        float yaw = this.yBodyRot * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), s = Math.max(this.getScale(), 0.05F);
        for (int i = 0; i < 2; i++) {
            net.minecraft.world.phys.Vec3 f = fp.foot(i, 1.0F);
            this.swayFwd[i] = ((f.x - this.getX()) * fx + (f.z - this.getZ()) * fz) / s + HIP_BACK_PX * PX;
            this.swayPlanted[i] = !fp.swinging(i) && !fp.hanging(i);
            this.swaySwing[i] = fp.swingProgress(i);
        }
        boolean walking = proceduralLegs() && clientForwardSpeed() / s > 0.02D;
        this.sway.tick(this.swayFwd, this.swayPlanted, this.swaySwing, SWAY_SIDE, walking);
        if (DEV_HOOKS && this.hasCustomName() && this.getCustomName().getString().contains("gait")) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "VEN_SWAY t %d 腰 %+.3f ひねり %+.1f 傾き %+.1f 胸 %+.1f 首 %+.1f 走り %s", this.tickCount, this.sway.get(GaitSway.LIFT, 1.0F),
                    this.sway.get(GaitSway.YAW, 1.0F), this.sway.get(GaitSway.ROLL, 1.0F), this.sway.get(GaitSway.FRONT_PITCH, 1.0F),
                    this.sway.get(GaitSway.NECK_PITCH, 1.0F), this.keyedRun));
        }
    }

    private final double[] swayFwd = new double[2];
    private final boolean[] swayPlanted = new boolean[2];
    private final float[] swaySwing = new float[2];
    /** 脚の左右(+1 = 左)。{@link #LEG_HOME_LEFT} の並び。 */
    private static final int[] SWAY_SIDE = {1, -1};

    /** 描画: 足の運びから出した胴・首・尾の動き。 */
    /** 描画: 泳ぐ間に体を下ろす割合(0〜1)。 */
    public float swimSink(float pt) {
        return Mth.lerp(pt, this.swimSinkO, this.swimSink);
    }

    /** 泳ぐ間に描く体を下ろす量(ブロック)【仮】: 浮いている体は足が水面の 0.4 下で、腰は足の 1.6 上 → 腰を水面へ。 */
    public static final double SWIM_SINK = 1.2D;

    public GaitSway sway() {
        return this.sway;
    }

    private static double sq(double v) {
        return v * v;
    }

    @Override
    protected boolean legsFree() {
        if (!super.legsFree()) return false;
        if (!this.level().isClientSide()) return true;   // サーバー: 体の曲げは疾走の間も効かせる
        if (isHuntSprinting()) return false;   // 疾走の間は手付けの sprint(走り出しの遅い間も足の置き場へ戻さない)
        // 速く走る間は手付けの歩き(足の置き場は二足の宙に浮く走りを持たない)。ヒステリシスで切り替える
        double v = Math.sqrt(sq(this.getX() - this.xo) + sq(this.getZ() - this.zo));
        if (this.keyedRun) {
            if (v < IK_BACK_SPEED) this.keyedRun = false;
        } else if (v > IK_MAX_SPEED) {
            this.keyedRun = true;
        }
        return !this.keyedRun;
    }

    // ------------------------------------------------------------------ アニメ

    /**
     * アニメは 1 つのコントローラ(脚・首・尾が同じアニメに入っている)。前へ進んでいれば walk を実際の速さに合わせて流し
     * (位相を積み上げるコントローラなので速さを変えても巻き戻らない)、止まっていれば idle。脚の IK が効いている間も walk を流す
     * (首・尾の揺れはそのまま、脚は IK が休めの形を基準に上書きする)。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", 4, this::mainState));
        // 段 B: 足噛みの層(首・頭・顎だけ)。後に登録した方が同じ骨を上書きするので、走りの上に重なる。流していない間は STOP(骨に触らない)。
        // 頭と終わりは run の首の構えなので補間 0(記憶 geckolib-two-layer-controllers の口パクの層と同じ作り)
        controllers.add(new AnimationController<>(this, "nip", 0, this::nipState));
    }

    private PlayState nipState(AnimationState<VenamnesEntity> state) {
        int n = getNip();
        if (n == NIP_NONE || this.isDeadOrDying() || n >= NIP_ANIMS.length) return PlayState.STOP;
        state.getController().transitionLength(0);
        return state.setAndContinue(NIP_ANIMS[n]);
    }

    private PlayState mainState(AnimationState<VenamnesEntity> state) {
        AnimationController<VenamnesEntity> c = state.getController();
        c.setAnimationSpeed(1.0D);
        c.transitionLength(LEAD);
        int act = getAction();
        if (act != ACT_NONE && !this.isDeadOrDying()) {
            // 技: 段ごとに 1 本。前触れへは補間 LEAD で入り、段と段の継ぎ目は同じ姿なので補間 0(サーバーの時刻とずれない)
            int seq = this.entityData.get(DATA_SEQ);
            if (seq != this.animSeq) {
                this.animSeq = seq;
                c.forceAnimationReset();
            }
            // 補間: 技に入る段は LEAD、継ぎ目が同じ姿の段は 0。疾走 → 最後の飛びかかりは走りの位相がまちまちなので 2、
            // 息切れは頭が疾走の構え(着地の後は静止の姿)なので LEAD(アニメの一覧の注意)
            c.transitionLength(switch (act) {
                case ACT_BITE_WINDUP, ACT_POUNCE_WINDUP, ACT_HUNT_WINDUP, ACT_HUNT_RECOVER, ACT_LEAP_WINDUP -> LEAD;
                case ACT_HUNT_POUNCE -> 2;
                default -> 0;
            });
            if (act == ACT_HUNT_SPRINT) {
                // 疾走: 踏む足が滑らないよう実際の速さに合わせて流す(基準 0.35)
                c.setAnimationSpeed(Mth.clamp(clientForwardSpeed() / (SPRINT_REF * this.getScale()), RUN_SPEED_MIN, RUN_SPEED_MAX));
            }
            RawAnimation a = switch (act) {
                case ACT_BITE_WINDUP -> BITE_WINDUP_ANIM;
                case ACT_BITE -> BITE_ANIM;
                case ACT_BITE_RECOVER -> BITE_RECOVER_ANIM;
                case ACT_POUNCE_WINDUP -> POUNCE_WINDUP_ANIM;
                case ACT_POUNCE, ACT_HUNT_POUNCE -> POUNCE_ANIM;
                case ACT_POUNCE_RECOVER, ACT_HUNT_LAND -> POUNCE_RECOVER_ANIM;
                case ACT_HUNT_WINDUP -> HUNT_WINDUP_ANIM;
                case ACT_HUNT_SPRINT -> SPRINT_ANIM;
                case ACT_HUNT_RECOVER -> HUNT_RECOVER_ANIM;
                case ACT_LEAP_WINDUP -> LEAP_WINDUP_ANIM;
                case ACT_LEAP -> LEAP_ANIM;
                case ACT_LEAP_RECOVER -> LEAP_RECOVER_ANIM;
                default -> IDLE;
            };
            return state.setAndContinue(a);
        }
        if (this.isInWater() && !this.isDeadOrDying()) {
            // 泳ぐ(段 C): 脚は畳んだまま(土台が水の中は足の置き場を止める)。水面に浮かせるのは浮く Goal
            return state.setAndContinue(SWIM_ANIM);
        }
        double ahead = clientForwardSpeed();
        boolean turning = Math.abs(getTurnRate()) >= TURN_ANIM_MIN;
        if (!this.isDeadOrDying() && (ahead >= WALK_MIN_SPEED || turning)) {
            if (this.keyedRun) {
                // 走り: 脚の切り替え(legsFree)と同じ旗で選ぶ。踏む足が滑らないよう、実際の速さに合わせて流す
                c.setAnimationSpeed(Mth.clamp(ahead / (RUN_REF * this.getScale()), RUN_SPEED_MIN, RUN_SPEED_MAX));
                return state.setAndContinue(RUN);
            }
            if (proceduralLegs()) {
                // 足の置き場で歩く間: 上半身だけの構え。脚・腰の上下・首と尾の付け根は実際の踏み替えから手続きで足す
                return state.setAndContinue(prowlMode() == PROWL_NONE ? STALK : PROWL_ANIM);   // 段 C: つけ回しは低い構え prowl
            }
            // 遠く(足の置き場が効いていない)でゆっくり歩く間: 手付けの歩き
            double v = Math.max(ahead, turning ? WALK_REF * 0.5D : 0.0D);
            c.setAnimationSpeed(Mth.clamp(v / (WALK_REF * this.getScale()), LOOP_SPEED_MIN, LOOP_SPEED_MAX));
            return state.setAndContinue(WALK);
        }
        return state.setAndContinue(prowlMode() == PROWL_FREEZE ? PROWL_FREEZE_ANIM : IDLE);   // 段 C: 振り向かれて固まる
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
