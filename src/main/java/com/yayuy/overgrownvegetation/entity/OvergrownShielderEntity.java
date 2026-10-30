package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import com.yayuy.overgrownvegetation.swarm.SwarmMember;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmAttacker;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmEngageGoal;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmFollowGoal;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmGoals;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmLeadGoal;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmSpreadGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 「過繁茂したスカトゥリウム」— 苔と甲殻に覆われ、背中に胞子ポッドを並べた大型の寄生生物。
 * <b>グループ4（拡大種）のタンク役</b>。高い体力と防御で前線を張る。
 *
 * <h2>2026-09-15: グループ3 → グループ4</h2>
 * ユーザー指定で<b>捕食の要素を落とし、拡大種へ移した</b>。無くなったのは「グループ2 を狩る捕食者」
 * としての側面だけ（{@code PredatorMob} 実装・捕食数のボーナス・{@code predation.json} の項目）で、
 * <b>ステータスと既存の行動はそのまま</b>。代わりに拡大種の役割が 3 つ増えた:
 * <ul>
 *     <li><b>群れ</b>（v2、2026-09-25。{@code swarm/}）—— 前衛(FRONT)。アルボラがいなければ先導役にもなる。
 *     標的は群れの司令塔が迫撃砲の射程({@code shielderMortarMaxRange})で渡し、突進・迫撃砲・追跡は自分の Goal で行う
 *     (相手を囲む輪には入らない。攻城の塊の輪には入る)。</li>
 *     <li><b>迫撃砲</b>（{@link ShielderMortarGoal}）—— 背中のポッドから胞子爆弾を撃ち上げる。</li>
 *     <li><b>座って汚染</b>（{@link ShielderSporeGoal}）—— 襲う相手が居なければその場に座り、
 *     周囲を汚染ブロックへ変えていく。</li>
 * </ul>
 * <b>モデルとテクスチャも同時に差し替えた</b>（背中のシダ → 胞子ポッド）。骨格は
 * 脚・背骨・尾がそのままだが、<b>アニメーションは 8 種すべて作り直した</b>（下の「アニメーション名」を参照）。
 *
 * <h2>ダメージ耐性：遠距離攻撃だけを弾く</h2>
 * <b>矢・三叉槍・火の玉などの投射物と、ウォーデンの音波爆発を無効化する</b>
 * （ユーザー要望 2026-09-19）。弾いたときは<b>盾で防いだときと同じ音</b>
 * （{@code item.shield.block}）が鳴る。
 *
 * <p>それ以外は<b>すべて通る</b> —— 近接攻撃はもちろん、爆発・炎・落下・毒・魔術・
 * サボテンも効く。2026-09-19 より前は逆に「近接攻撃以外は一切効かない」という作りで、
 * 溶岩に落ちても平気という極端な耐性になっていた。
 * （{@code /kill} と奈落は従来どおり {@link DamageTypeTags#BYPASSES_INVULNERABILITY} 経由で通る。）
 *
 * <h2>引き離された相手への 2 つの手：突進と迫撃砲</h2>
 * 対象に一定以上引き離されたとき、<b>突進か迫撃砲のどちらかを交互に出す</b>
 * （ユーザー要望 2026-09-15。札は {@link #shouldMortarNext()}）。
 * <b>見通しが無いときは必ず迫撃砲</b>になる —— 突進は壁を越えられないが、弾は山なりに越えていく。
 * <ul>
 *     <li><b>突進</b>（{@link ShielderChargeGoal}）: 約1.5秒「溜め」（{@code charge_windup}）→
 *     その瞬間の対象方向へロックして2〜3秒間突進（{@code charge}、毎tick 3ダメージ）→
 *     2ブロック以上の段差／壁にぶつかると約3秒、盾で10回防がれると約1.8秒スタン（{@code stunned}）。</li>
 *     <li><b>迫撃砲</b>（{@link ShielderMortarGoal}）: 構えてから、ヴェロフィルと同じ胞子爆弾
 *     （{@link VelophyllBombEntity}）を背中のポッドから山なりに数発。</li>
 * </ul>
 *
 * <h2>胞子感染</h2>
 * {@link ParasitizedMob} を実装しているので、「過繁茂した(MOB名)」と同様に
 * 攻撃した相手へ「胞子感染」を付与/進行させる（{@link com.yayuy.overgrownvegetation.effect.ParasitizedAttackInfectionHandler}、
 * 近接攻撃・突進の接触ダメージどちらでも）。同時にスカトゥリウム自身は「胞子感染」にかからない
 * （自分の汚染地形の上で感染死しないため。{@link com.yayuy.overgrownvegetation.effect.SporeInfectionEffects} が
 * {@link ParasitizedMob} 全種に共通で保証している）。
 *
 * <h2>索敵</h2>
 * 「過繁茂した(MOB名)」全種と共通の {@link com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal}
 * を索敵に使う({@link #registerGoals}）— 種族を問わず全方位に敵対する範囲が Mob の種類によって
 * 食い違わないようにするため。接近・攻撃だけは専用の {@link ShielderChargeGoal} /
 * {@link ShielderPursueGoal} が担当する。
 *
 * <h2>アニメーション名（GeckoLib、差し替え用）</h2>
 * {@code idle} / {@code walk} / {@code run} / {@code charge_windup} / {@code charge} /
 * {@code stunned} / {@code mortar} / {@code sit}。
 *
 * <p><b>アニメーションは生成物</b>（2026-09-15 に全面的に作り直した）。手で書き換えず、
 * {@code tools/anim-preview/gen_shielder_anim.pl} の数値を変えて作り直すこと。生成器には
 * <b>kansetu1 / kansetu2 / kansetu3 には触らない</b>という大原則が入っている ——
 * この 3 つは脚の親なので、回すと体ごと傾いて足が地面から浮くか沈む
 * （ユーザー指摘「体全体を動かして斜めにしたりしないように」への対処）。
 * 体の動きは脚・頭・尾・ポッド・{@code kansetu4}（尾の付け根。脚に影響しない）だけで表す。
 * 唯一の例外は {@code sit} の {@code kansetu1} の Y 平行移動で、脚を畳んだぶんだけ体を下げて
 * 足を地面に残すためのもの。全 8 種の足元最下点は {@code tools/anim-preview} で
 * 0.00 〜 -0.05 ブロックに収まっていることを確認済み。
 *
 * <p>この8つの名前さえ維持すれば、
 * {@code assets/overgrown_vegetation/animations/overgrown_shielder.animation.json} と
 * {@code geo/overgrown_shielder.geo.json} と {@code textures/entity/overgrown_shielder.png} を
 * 上書きするだけで見た目を差し替えられる（Java 側の変更不要）。
 */
public class OvergrownShielderEntity extends Monster
        implements GeoEntity, ParasitizedMob, PredatorMob, SwarmAttacker {

    /** 通常（idle / walk を {@code MobAnimation.isWalking(state)} で切替）。 */
    public static final int STATE_NONE = 0;
    /** 突進の溜め。 */
    public static final int STATE_WINDUP = 1;
    /** 突進中。 */
    public static final int STATE_CHARGE = 2;
    /** 突進失敗によるスタン。 */
    public static final int STATE_STUN = 3;
    /** 背中のポッドから胞子爆弾を撃ち上げている（{@link ShielderMortarGoal}）。 */
    public static final int STATE_MORTAR = 4;
    /** その場に座って周囲を汚染している（{@link ShielderSporeGoal}）。 */
    public static final int STATE_SIT = 5;

    /**
     * 動作を切り替えるときの補間（tick）。GeckoLib はこの間に今の姿勢から新しいアニメの 0 秒目の姿勢へ寄せ、
     * <b>それが終わってからアニメを 0 秒目から流す</b>（4.7.6 の {@code AnimationController#process}）。
     * だから画面の上では、どのアニメも状態を切り替えた tick より 3 tick 遅れて始まる。
     * アニメの時刻に合わせる tick（迫撃砲の 1 発目）は「これ + アニメの秒 × 20」で数える。
     */
    static final int TRANSITION_TICKS = 3;

    private static final EntityDataAccessor<Integer> DATA_ACTION_STATE =
            SynchedEntityData.defineId(OvergrownShielderEntity.class, EntityDataSerializers.INT);

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN_ANIM = RawAnimation.begin().thenLoop("run");
    private static final RawAnimation WINDUP_ANIM = RawAnimation.begin().thenLoop("charge_windup");
    private static final RawAnimation CHARGE_ANIM = RawAnimation.begin().thenLoop("charge");
    private static final RawAnimation STUN_ANIM = RawAnimation.begin().thenLoop("stunned");
    private static final RawAnimation MORTAR_ANIM = RawAnimation.begin().thenLoop("mortar");
    private static final RawAnimation SIT_ANIM = RawAnimation.begin().thenLoop("sit");

    /**
     * 移動速度が素の何倍を超えたら {@code run} に切り替えるか。
     *
     * <p>これを超えるのは<b>群れの後続が追いつくとき</b>（{@code swarm.json} の {@code catchUpSpeed}、仮決め ×1.3）だけ ——
     * 追跡そのものは常に等速（{@link ShielderPursueGoal}。2026-09-15 に走行を削除）。
     * 旧い群れの走り({@code invaderPackDashSpeed} 1.45)は 2026-09-25 に消えた。
     */
    private static final double RUN_SPEED_RATIO = 1.25D;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /** 突進が盾で防がれた累計回数。スタンから復帰するたび 0 に戻す（{@link ShielderChargeGoal} が管理）。 */
    private int shieldBlockedCount;

    /** 次の遠距離攻撃を迫撃砲にするか（突進と交互に出す）。 */
    private boolean mortarNext;
    /** 迫撃砲を次に撃てるようになる {@code tickCount}。 */
    private int mortarReadyTick;

    /** 斧で殴られたときの被ダメージ倍率。 */
    private static final float AXE_DAMAGE_MULTIPLIER = 1.2F;

    /**
     * 捕食したグループ1〜3 の累計({@link PredatorMob})。1 体ごとに最大体力と攻撃力が
     * {@code predation.json} の {@code preyKillBonusPerKill} ずつ複利で上がる。
     * <b>この成長ができるのはアルボラとスカトゥリウムだけ</b>(ユーザー指定 2026-09-15) ——
     * ほかのグループ4 は獲物を追い込む側に回る(群れの司令塔の獲物探し / {@link SporophyllAura})。
     */
    private int preyKillCount;

    @Override
    public int getPreyKillCount() {
        return this.preyKillCount;
    }

    @Override
    public void setPreyKillCount(int count) {
        this.preyKillCount = count;
    }

    public OvergrownShielderEntity(EntityType<? extends OvergrownShielderEntity> type, Level level) {
        super(type, level);
        // 永続化はしない。過繁茂バイオームの生態系スポーン（OvergrownBiomeSpawner）で湧いた個体は
        // プレイヤーが離れればバニラのルールで消え、「プレイヤーの周りだけ密度が保たれる」ようにする。
        // レイドで湧いた個体だけは RaidWaveSpawner が明示的に setPersistenceRequired() する。
        // 経路探索で水に入らない（＝自分から入水しようとしない。岸で止まる）。
        // ここで WATER_BORDER を 0 にしても、2026-09-19 からは
        // LandMobWaterAvoidance がレベル参加時に landMobWaterBorderMalus（既定 16）へ
        // 掛け直す ——「可能なかぎり水を避ける」をグループ1〜10 共通の性質にしたため
        // （ユーザー要望）。ここに残してあるのは、その一括設定を切ったときの素の値。
        this.setPathfindingMalus(PathType.WATER, -1.0F);
        this.setPathfindingMalus(PathType.WATER_BORDER, 0.0F);
        this.getNavigation().setCanFloat(false);
        // 葉は当たり判定が 1 ブロックまるごとある。malus 0(= 通れる扱い)にすると葉の中へ突っ込む
        // 経路が引かれ、実際には入れずに詰まる。バニラ既定どおり通らせない(2026-09-14)。
        this.setPathfindingMalus(PathType.LEAVES, -1.0F);
    }

    /**
     * 幅 1 ブロック以上の Mob ではバニラが省略してしまう「段差の上に体が入るか」の確認を、
     * 自前の {@link OvergrownNodeEvaluator} で必ず行うようにする(登れない段差に挑み続けない)。
     * 中身は {@link InvaderNavigation#createPlain}(2026-09-25: 群れの区間をブロックの変化で引き直すときに、
     * 群れの体の包絡を掛け直せるよう名前つきのクラスへ移した。探索そのものは元の無名クラスと同じ)。
     */
    @Override
    protected PathNavigation createNavigation(Level level) {
        return InvaderNavigation.createPlain(this, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), "overgrown_shielder");
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION_STATE, STATE_NONE);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // 機関砲台が射程(40)に入っていれば、まずそれを迫撃砲で潰す(2026-09-21)。
        // 砲台の射程は 32 なので、撃たれない位置から撃ち込める。同じ優先度の先頭に置いて先に見させる。
        this.goalSelector.addGoal(1, new ShielderTurretMortarGoal(this));
        // 引き離された相手への手は 2 つ。どちらを出すかは shouldMortarNext() の札で交互に決まる。
        this.goalSelector.addGoal(1, new ShielderMortarGoal(this));
        this.goalSelector.addGoal(1, new ShielderChargeGoal(this));
        this.goalSelector.addGoal(2, new ShielderPursueGoal(this));
        // 襲う相手が居ないときは、その場に座って周囲を汚染する（グループ4 共通の役割）。群れの一員では動かない
        // (群れごと止まって汚す SwarmSpreadGoal が同じ座り込みを回す)。
        this.goalSelector.addGoal(3, new ShielderSporeGoal(this));
        // 群れ(v2、2026-09-25)。優先度の並びは AbstractOvergrownInvaderEntity#registerGoals と同じ
        // (0 水・1〜2 種族・3 囲む/汚す・4 先導/後続・7 散歩)。囲むのは攻城の塊の輪だけ(相手は突進・迫撃砲・追跡で)。
        this.goalSelector.addGoal(3, new SwarmEngageGoal(this));
        this.goalSelector.addGoal(3, new SwarmSpreadGoal(this, () -> SporeSpreadSession.sitAction(this)));
        this.goalSelector.addGoal(4, new SwarmLeadGoal(this));
        this.goalSelector.addGoal(4, new SwarmFollowGoal(this));
        // 水を避けて歩く（LandMobWaterAvoidance と対。素の RandomStrollGoal は
        // 水の上の点も行き先に選ぶので、経路が引けずに無駄足になる）。群れの一員はうろつかない(ユーザー指定 2026-09-25)。
        this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.9D) {
            @Override
            public boolean canUse() {
                return !SwarmMember.isInSwarm(OvergrownShielderEntity.this) && super.canUse();
            }
        });
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 10.0F));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new OvergrownHurtByTargetGoal(this));
        // 索敵ロジックそのものは他の「過繁茂した(MOB名)」全種と共通の ParasitizedTargetGoal に一本化
        // (プレイヤーだけ／バニラの敵対 Mob だけに絞っていた旧実装だと、牛・羊・豚・ニワトリ・村人など
        // 本来は他の過繁茂した Mob も襲う相手を素通りしてしまい、Mob の種類によって「敵対する範囲」が
        // 食い違っていた)。除外対象・視線ロストの猶予・茂み(FoliageConcealment)での隠密といった
        // 共通の索敵条件は ParasitizedTargetGoal 側にまとまっている。攻撃・接近は引き続き専用の
        // ShielderChargeGoal / ShielderPursueGoal が担当するので、ここでは索敵 Goal だけを追加する。
        // グループ1〜3 を獲物として狩る(2026-09-15)。狩ったぶんだけ体力と攻撃力が上がる。
        this.targetSelector.addGoal(2, new OvergrownLandTargetGoal(this, Predation::isInvaderPrey));
        ParasitizedTargetGoal.installTargeting(this, 3);
    }

    // --- 迫撃砲と突進の切り替え ---

    /**
     * 次の遠距離攻撃を迫撃砲にするか。{@link ShielderChargeGoal} / {@link ShielderMortarGoal} が見る。
     * いまの攻撃目標がグループ1〜3(獲物)なら札に関わらず false —— 獲物には迫撃砲を撃たない
     * (ユーザー要望 2026-09-24)ので、札が迫撃砲の番でも突進が譲らないようにする。札そのものは変えない。
     */
    public boolean shouldMortarNext() {
        return this.mortarNext && !ShielderMortarGoal.isSparedFromMortar(this.getTarget());
    }

    public void setMortarNext(boolean mortar) {
        this.mortarNext = mortar;
    }

    /** 迫撃砲のクールタイムが明けているか。 */
    public boolean canMortarNow() {
        return this.tickCount >= this.mortarReadyTick;
    }

    /** 斉射を撃ち切った。次に撃てるのは {@code shielderMortarCooldownTicks} 後。 */
    public void startMortarCooldown() {
        this.mortarReadyTick = this.tickCount
                + OvergrownVegetationConfig.COMMON.shielderMortarCooldownTicks.get();
    }

    // --- 群れ（v2、2026-09-25） ---

    /**
     * 攻撃アニメの代わり(群れ v2、{@link SwarmAttacker})。スカトゥリウムは近接の攻撃アニメを持たない
     * (突進・迫撃砲・スタン・座り込みだけ)ので、攻城で塊・壁を叩いたときも見た目は変えない。
     * 仮決め 2026-09-25 —— 専用のアニメを作るかはユーザーに確認すること。
     */
    @Override
    public void playAttackAnimation() {
    }

    /**
     * 座って汚染を広げるときの水平半径（ブロック）。{@link ShielderSporeGoal} が使う。
     * 体が大きいぶんアルボラ（既定 4）より広い。
     */
    public int sporeCorruptionRadius() {
        return OvergrownVegetationConfig.COMMON.shielderCorruptionRadius.get();
    }

    /**
     * 「32 ブロックより遠くで 30 秒何も起きなければ抽選で消す」バニラの規則を止める
     * （{@link InvaderDespawn}）。座って汚染を広げるのも、群れで歩くのも、プレイヤーが近くに居ない時間が要る。
     * 群れの一員はバニラには消させない(消えるときは群れごと。ユーザー指定 2026-09-25)。
     */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        if (SwarmMember.hasSwarmAttachment(this)) {
            return false;
        }
        return InvaderDespawn.removeWhenFarAway(this, distanceToClosestPlayer);
    }

    /** 群れの一員・イベントの個体はポータルを使わない(AbstractOvergrownInvaderEntity と同じ理由)。 */
    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        if (SwarmGoals.blocksPortal(this)) {
            return false;
        }
        return super.canUsePortal(allowPassengers);
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        // 群れの一員の自己修復(所属があるのに名簿に居なければ入り直す。中で 20 tick に 1 回へ間引いている)。
        SwarmMember.tick(this);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide() || !this.isAlive()) {
            return;
        }
        // ターゲットを追っている最中に段差でつかえたら小さくジャンプして乗り越える
        // （ブロックは壊さない）。経路が見つからず止まってしまう問題の緩和。
        if (this.getActionState() == STATE_NONE
                && this.getTarget() != null
                && this.horizontalCollision
                && this.onGround()
                && this.getDeltaMovement().horizontalDistanceSqr() < 0.0025D) {
            this.jumpFromGround();
        }
    }

    public int getActionState() {
        return this.entityData.get(DATA_ACTION_STATE);
    }

    public void setActionState(int state) {
        this.entityData.set(DATA_ACTION_STATE, state);
    }

    public boolean isBusy() {
        return getActionState() != STATE_NONE;
    }

    int getShieldBlockedCount() {
        return shieldBlockedCount;
    }

    void addShieldBlockedCount() {
        shieldBlockedCount++;
    }

    void resetShieldBlockedCount() {
        shieldBlockedCount = 0;
    }

    // --- ダメージ耐性：遠距離攻撃だけを弾く（ユーザー要望 2026-09-19） ---

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return noteHurt(super.hurt(source, amount), source);
        }
        if (isRangedAttack(source)) {
            playShieldBlockSound();
            return false;
        }
        // 斧による攻撃は 1.2 倍通る（甲殻を割るイメージ）。
        if (source.getDirectEntity() instanceof LivingEntity attacker
                && attacker.getMainHandItem().getItem() instanceof AxeItem) {
            amount *= AXE_DAMAGE_MULTIPLIER;
        }
        return noteHurt(super.hurt(source, amount), source);
    }

    /**
     * 実際に効いたら群れの司令塔へ伝える(群れ v2、2026-09-25。{@link SwarmManager#onMemberHurt}: 役割の距離 = 迫撃砲の射程に
     * いればこの一員だけ反撃、群れの標的を替えるかは優先順で)。弾いた遠距離攻撃は効いていないので伝えない。
     */
    private boolean noteHurt(boolean hurt, DamageSource source) {
        if (hurt && !this.level().isClientSide()) {
            SwarmManager.onMemberHurt(this, source);
        }
        return hurt;
    }

    /**
     * <b>弾く攻撃か</b>（ユーザー指定 2026-09-19「遠距離攻撃だけを無効化する」）。
     *
     * <ul>
     *     <li>{@link DamageTypeTags#IS_PROJECTILE} —— 矢・三叉槍・雪玉・火の玉・
     *     この MOD の砲弾や胞子爆弾など、<b>飛んでくるもの</b>すべて。</li>
     *     <li>{@link DamageTypes#SONIC_BOOM} —— ウォーデンの音波爆発。投射物ではないが
     *     遠くから届く攻撃なので同じ扱いにする。</li>
     * </ul>
     *
     * <p><b>爆発は入れていない。</b>手前で起爆するクリーパーや TNT は「遠距離攻撃」ではなく、
     * 盾で弾けるものでもない。以前は無効化していたが、それだと火にも溶岩にも落下にも
     * 傷つかない置物になってしまう。
     */
    private static boolean isRangedAttack(DamageSource source) {
        return source.is(DamageTypeTags.IS_PROJECTILE) || source.is(DamageTypes.SONIC_BOOM);
    }

    /**
     * 弾いたときの音。<b>プレイヤーが盾で防いだときとまったく同じ</b>
     * （{@code item.shield.block}、音程のばらつきも同じ式）。ユーザー指定 2026-09-19。
     *
     * <p>第 1 引数に {@code null} を渡すと<b>全員に聞こえる</b>（特定のプレイヤーを除外しない）。
     */
    private void playShieldBlockSound() {
        if (this.level().isClientSide()) {
            return;
        }
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.SHIELD_BLOCK, SoundSource.HOSTILE,
                1.0F, 0.8F + this.level().getRandom().nextFloat() * 0.4F);
    }

    // --- 移動・当たり判定まわり ---

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(Entity entity) {
        // 大型なので他 Mob には押されない
    }

    /**
     * 同じ群れの一員とは押し合わない(群れ v2、2026-09-25)。スカトゥリウム自身は押されない({@link #isPushable})が、
     * ぶつかった相手のほうは弾かれる —— 群れの後続がスカトゥリウムに当たるたびに足跡から弾き出されないように。
     */
    @Override
    public void push(Entity entity) {
        if (SwarmGoals.sameSwarm(this, entity)) {
            return;
        }
        super.push(entity);
    }

    /** 群れの後続は押し合いの走査を 2 tick に 1 回にする(AbstractOvergrownInvaderEntity と同じ。重なりの被ダメージの判定もここ)。 */
    @Override
    protected void pushEntities() {
        if (!this.level().isClientSide() && Math.floorMod(this.tickCount + this.getId(), 2) != 0
                && SwarmMember.isFollower(this)) {
            return;
        }
        super.pushEntities();
    }

    /** スタン中・溜め中は自力移動不可（押し出しや {@code travel} の水平慣性を止める）。 */
    @Override
    public void travel(net.minecraft.world.phys.Vec3 travelVector) {
        int state = getActionState();
        if (state == STATE_WINDUP || state == STATE_STUN || state == STATE_MORTAR || state == STATE_SIT) {
            super.travel(net.minecraft.world.phys.Vec3.ZERO);
        } else {
            super.travel(travelVector);
        }
    }

    @Override
    public int getMaxHeadXRot() {
        return 20;
    }

    // --- 見た目（GeckoLib） ---

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "action", TRANSITION_TICKS, state -> {
            switch (getActionState()) {
                case STATE_SIT:
                    state.getController().setAnimationSpeed(1.0D);
                    return state.setAndContinue(SIT_ANIM);
                case STATE_MORTAR:
                    state.getController().setAnimationSpeed(1.0D);
                    return state.setAndContinue(MORTAR_ANIM);
                case STATE_STUN:
                    state.getController().setAnimationSpeed(1.0D);
                    return state.setAndContinue(STUN_ANIM);
                case STATE_CHARGE:
                    state.getController().setAnimationSpeed(1.0D);
                    return state.setAndContinue(CHARGE_ANIM);
                case STATE_WINDUP:
                    state.getController().setAnimationSpeed(1.0D);
                    return state.setAndContinue(WINDUP_ANIM);
                default:
                    if (MobAnimation.isWalking(state)) {
                        // 速く歩くとき(群れの後続が追いつくとき)には専用の run がある。
                        // 以前は walk を最大 1.8 倍速で流していたが、歩幅も脚の上げ方も
                        // 歩きのままなので「早送り」にしか見えなかった（2026-09-15 に作り直し）。
                        // 速さは基礎移動速度の何倍かで見る（種の足の速さに合わせるため）。
                        double runFrom = this.getAttributeValue(Attributes.MOVEMENT_SPEED) * RUN_SPEED_RATIO;
                        state.getController().setAnimationSpeed(1.0D);
                        return state.setAndContinue(
                                MobAnimation.isFasterThan(state, runFrom) ? RUN_ANIM : WALK_ANIM);
                    }
                    state.getController().setAnimationSpeed(1.0D);
                    return state.setAndContinue(IDLE_ANIM);
            }
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    // --- サウンド ---

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.MOSS_STEP;
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
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.MOSS_STEP, 0.5F, 0.7F);
    }
    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("PreyKills", this.preyKillCount);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        // 旧ワールドの後始末(2026-09-24)。アルボラの巣の衛兵(NestHome 付き)は永続にしていたので、
        // 巣を消したあとも二度と消えない。永続を戻すだけで、NestHome は次の保存で消える。
        if (tag.contains("NestHome")) {
            com.yayuy.overgrownvegetation.raid.EventMobs.restorePersistence(this, false);
        }
        // 旧い群れ(2026-09-25 まで)の NBT の PackId を群れ ID として引き継ぐ(attachment は先に読まれている)。
        // PackId・PackComplete のキーは次の保存で消える。
        if (tag.hasUUID("PackId")) {
            SwarmManager.adoptLegacyPack(this, tag.getUUID("PackId"));
        }
        this.preyKillCount = tag.getInt("PreyKills");
        PredatorMob.applyPreyKillBonus(this, this.preyKillCount);
    }

}
