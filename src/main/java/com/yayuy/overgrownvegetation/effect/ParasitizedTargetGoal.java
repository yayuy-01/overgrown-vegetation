package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity;
import com.yayuy.overgrownvegetation.entity.Predation;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.BreedGoal;
import net.minecraft.world.entity.ai.goal.FollowParentGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.phys.AABB;

/**
 * 「過繁茂した(MOB名)」({@link ParasitizedMob})を、種族を問わず(プレイヤーだけでなく他の Mob にも)
 * 攻撃を仕掛ける敵対 Mob にする targetSelector 用 Goal。{@link #install} で
 * {@link MeleeAttackGoal} とセットで各 {@code Parasitized*} のコンストラクタに直接追加する
 * (バニラの {@code registerGoals()} を上書きする代わりに、コンストラクタで積み増す方式)。
 *
 * <p>索敵・追跡は障害物(壁・葉・蔓など)を一切無視する — {@code mustSee=false} かつ
 * {@link TargetingConditions#ignoreLineOfSight()} により、視線(LOS)が通っていない相手も新規に
 * 発見でき、追跡中に一時的に見えなくなっても(距離が {@link #getFollowDistance()} 以内である限り)
 * 見失わない(ユーザー要望)。標的を手放す条件は、死亡・{@link Mob#canAttack}
 * (無敵判定など)・チーム・追跡範囲外に出た場合、
 * {@link FoliageConcealment#isConcealed 茂みに隠れている相手}、そして
 * {@link #isUnreachable たどり着ける経路が無い状態が一定時間続いた場合}(いずれも
 * {@link #canContinueToUse} 参照) — 視線を無視するようにした副作用で「見えてはいるが壁の
 * 向こう」のような到達不能な相手を選びやすくなったため、壁際にいつまでも張り付かず
 * 別の標的に切り替えられるようにしている。
 *
 * <p>対象からは自分自身・他の {@link ParasitizedMob}(同士討ちさせない)・{@link BlightFernEntity}
 * (侵食の起点となる無害な植物 Mob)・{@link OvergrownNexusEntity}(レイドボス本体。味方であり、
 * 過繁茂した Mob に攻撃されると本来無関係なダメージで足止めされてしまう)・
 * {@link FoliageConcealment#isConcealed 茂みに隠れている相手}を除外する
 * ({@link OvergrownVegetationConfig.Common#bushConcealment} で無効化可能。追跡中の相手が
 * 途中で茂みに逃げ込んだ場合も {@link OvergrownVegetationConfig.Common#bushConcealmentGraceTicks}
 * の猶予(既定20tick=1秒)の後に見失う — {@link #canContinueToUse} 参照)。
 *
 * <p>さらに、これらの Mob は全て陸生なので{@link Predation#isLandTarget 水中・水面にいる相手は狙わない}
 * (自分も水に入っている場合を除く。追跡中の相手が水に入った場合はその時点で見失う —
 * ユーザー要望 2026-09-11)。また
 * {@link OvergrownVegetationConfig.Common#prioritizeUninfectedCreepers} が有効なら、新規索敵時に
 * 「まだ『胞子感染』にかかっていないクリーパー」を距離に関係なく最優先で狙う({@link #findTarget})。
 * そのクリーパーが感染すると優先度は元に戻り、通常どおり「最も近い相手」の一体として扱われる。既に「胞子感染2」
 * ({@link ModMobEffects#SPORE_INFECTION} amplifier &gt;= 1)にかかっている対象は、通常の
 * 索敵範囲({@code FOLLOW_RANGE})より {@code effects.json} の {@code sporeInfection.level2.detectionRangeBonus}
 * だけ広い範囲からでも検知できる(「検知範囲の増加」のデバフ効果)。
 *
 * <p>自前の {@code owner} フィールドは持たない — 親クラス {@code TargetGoal} が既に持っている
 * {@code protected final Mob mob} をそのまま使う。理由: {@link #getFollowDistance()} は
 * {@code NearestAttackableTargetGoal} 自身のコンストラクタから(このクラスのコンストラクタ本体が
 * 走る**前に**)仮想呼び出しされるため、独自フィールドを使うとその時点でまだ未代入(null)で
 * {@code NullPointerException} になる。継承済みの {@code mob} は {@code TargetGoal} のコンストラクタで
 * 真っ先に代入されるので、このタイミングでも安全に参照できる。
 */
public final class ParasitizedTargetGoal extends NearestAttackableTargetGoal<LivingEntity> {

    private final double baseRange;
    private final double bonusRange;

    /**
     * 現在の標的が茂みに隠れ続けている連続tick数。{@link #canContinueToUse} が
     * {@link OvergrownVegetationConfig.Common#bushConcealmentGraceTicks} との比較に使う
     * (隠れていない tick が1回でもあれば0にリセットされる、追い直すたびに {@link #start} でも
     * リセットされる)。
     */
    private int concealedTicks;

    /**
     * {@link #install(PathfinderMob)} が {@link MeleeAttackGoal} を積む既定の優先度。
     * 同じ優先度の Goal を割り込めない点については {@link #install(PathfinderMob, int)} 参照。
     */
    private static final int DEFAULT_MELEE_PRIORITY = 2;

    /**
     * 経路探索(重い処理)を実際に実行する間隔。{@link #isUnreachable} はこの間隔でしか
     * {@code createPath} を呼ばず、それ以外の tick は前回の結果を使い回す
     * (バニラの {@link MeleeAttackGoal} 自身の経路再計算とほぼ同じ頻度)。
     */
    private static final int PATH_CHECK_INTERVAL = 20;

    /** 次に {@link #isUnreachable} が実際に経路探索を行うまでの残りtick。 */
    private int pathCheckCooldown;
    /** 経路が見つからない状態が続いている合計tick数。{@link #isUnreachable} 参照。 */
    private int unreachableTicks;

    private ParasitizedTargetGoal(Mob mob) {
        // mustSee=false: 障害物越しでも新規に標的を発見・追跡できるようにする(ユーザー要望)。
        // 標的を見失う条件は茂みでの隠密({@link #canContinueToUse})など独自ロジックのみに委ねる。
        super(mob, LivingEntity.class, 10, false, false, null);
        this.baseRange = mob.getAttributeValue(Attributes.FOLLOW_RANGE);
        // 索敵箱は全段階中の最大ボーナスで広めに取り、対象ごとの実際の許容距離は isValidTarget が段階別に決める。
        this.bonusRange = SporeInfectionDetection.maxBonus();
        // コンストラクタ内で this を安全にキャプチャできるよう、targetConditions はここで組み直す
        // (親クラスのコンストラクタ時点ではまだ baseRange/bonusRange が未初期化のため)。
        // ignoreLineOfSight(): 新規ターゲット探索(findTarget → targetConditions.test)も
        // 視線(LOS)を要求しないようにする(mustSee=false と対になる設定。TargetingConditions は
        // 既定で視線判定込みのため、明示的に外さないと壁越しの相手を新規発見できない)。
        this.targetConditions = TargetingConditions.forCombat()
                .range(baseRange + bonusRange)
                .ignoreLineOfSight()
                .selector(this::isValidTarget);
    }

    /**
     * 追い直すたびに茂み隠密({@link #concealedTicks}）・到達不能判定({@link #unreachableTicks}・
     * {@link #pathCheckCooldown}）の連続カウントをリセットする。
     */
    @Override
    public void start() {
        this.concealedTicks = 0;
        this.unreachableTicks = 0;
        this.pathCheckCooldown = 0;
        super.start();
    }

    /**
     * {@code mob} に近接攻撃 Goal と、この索敵 Goal をセットで追加する。
     *
     * <p>{@link MeleeAttackGoal} の速度倍率は種族の {@code MOVEMENT_SPEED} 基礎値から逆算し、
     * 実際に追跡する速度が {@link OvergrownVegetationConfig.Common#parasitizedChaseSpeed}
     * (既定 0.3 ≒ バニラのゾンビの追跡速度 0.23 より明確に速い経験的な値)を<b>下回らない</b>
     * ようにしている({@code Mob#setSpeed} は {@code 倍率 × MOVEMENT_SPEED} で計算されるため)。
     *
     * <p><b>下回らない、であって一致させるのではない</b>(2026-09-21 に修正)。以前は倍率を
     * {@code targetSpeed / baseMovementSpeed} そのままにしていたので、基礎速度が 0.3 を超える種は
     * <b>追跡中だけ 0.3 に落とされて</b>いた —— {@code mobs.json} で足を速くしても戦闘中は効かない。
     *
     * <p>注意: 基礎移動速度はこの Goal を積む時点(=コンストラクタ)の値で読む。後から
     * {@code SPEED_MODIFIER_BABY} 等で基礎速度が変わる Mob は追跡速度が想定とズレる
     * ({@code ParasitizedZombie} は子ゾンビ化を禁止してこれを回避している)。
     *
     * <p>{@link MeleeAttackGoal} の第3引数({@code followingTargetEvenIfNotSeen}）は {@code true}
     * にしている。{@code false} だとバニラ実装上、視線(LOS)が通っていない間は経路の再計算を
     * 一切行わなくなり、それまでの経路を使い切った時点で {@code canContinueToUse()} が
     * {@code false} になって Goal 自体が停止する — {@link ParasitizedTargetGoal} 側は障害物を
     * 無視して標的を保持し続けるのに、こちらが {@code false} のままだと肝心の追跡(経路の再計算)が
     * 途中で止まってしまう(見失っている間も実際に攻撃(近接ヒット判定)するには依然として視線が
     * 必要なので、壁越しに殴れるようにはならない)。
     */
    public static void install(PathfinderMob mob) {
        install(mob, DEFAULT_MELEE_PRIORITY);
    }

    /**
     * {@link #install(PathfinderMob)} と同じだが、{@link MeleeAttackGoal} を積む優先度を指定する。
     *
     * <p>既定の {@link #DEFAULT_MELEE_PRIORITY}(=2)だと、同じ優先度 2 に自分より先に積まれている
     * Goal を<b>一生割り込めない</b>点に注意 — {@code GoalSelector} は「優先度の数字が厳密に小さい
     * Goal だけが、実行中の Goal から制御フラグ({@code MOVE} など)を奪える」という規則なので、
     * 同じ優先度では先に走り出した方が勝ち続ける。{@link MeleeAttackGoal} は標的が生きている限り
     * {@code canContinueToUse()} が {@code true} を返し続けるため、こうなると相手の Goal は
     * 二度と動けない。
     *
     * <p>実際にこれで壊れていたのが「過繫茂したクリーパー」
     * ({@link com.yayuy.overgrownvegetation.entity.ParasitizedCreeper}) —
     * バニラのクリーパーは導火線 Goal({@code SwellGoal}、{@code MOVE} フラグ)を優先度 2 に、
     * 近接攻撃を優先度 4 に積むことで「遠いうちは殴りに行き、3 ブロックまで近付いたら
     * 膨らみ始める」を成立させている。ここに追加の {@link MeleeAttackGoal} を優先度 2 で積むと、
     * 標的を見つけた時点で(まだ遠いので {@code SwellGoal} は {@code canUse()} が {@code false})
     * 近接攻撃が先に {@code MOVE} を握り、その後いくら近付いても {@code SwellGoal} が
     * 割り込めず、<b>永久に自爆しなくなる</b>。クリーパーだけはバニラと同じ優先度 4 で積む。
     */
    public static void install(PathfinderMob mob, int meleePriority) {
        double targetSpeed = OvergrownVegetationConfig.COMMON.parasitizedChaseSpeed.get();
        double baseMovementSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        // <b>自分の足より遅くはしない</b>(2026-09-21)。逆算はあくまで「遅い種を引き上げる」ための
        // もので、{@code parasitizedChaseSpeed} より速い種まで<b>そこまで落として</b>しまうと、
        // {@code mobs.json} で足を速くしても追跡中だけ効かない —— 実際、寄生されたクモ(0.35)が
        // 追跡中だけ 0.3 に落ちていた。
        double meleeSpeedModifier = baseMovementSpeed > 0.0D
                ? Math.max(1.0D, targetSpeed / baseMovementSpeed) : 1.0D;

        mob.goalSelector.addGoal(meleePriority, new MeleeAttackGoal(mob, meleeSpeedModifier, true));
        installTargeting(mob, 1);
    }

    /**
     * {@link MeleeAttackGoal} は追加せず、この索敵 Goal だけを {@code priority} で targetSelector に
     * 仕込む。{@link com.yayuy.overgrownvegetation.entity.OvergrownShielderEntity} のように、
     * 攻撃・追跡は専用 Goal(突進など)に任せたいが「過繁茂した Mob は種族を問わず全方位に敵対する」
     * という索敵ロジック自体は他の「過繁茂した(MOB名)」と共通にしたい Mob 向け。「過繁茂した Mob」
     * 全種に共通の索敵条件(除外対象・茂みでの隠密・視線ロストの猶予など、このクラスの javadoc 参照)
     * を、Mob の種類によらず単一の実装に一本化するための入口。
     */
    public static void installTargeting(Mob mob, int priority) {
        mob.targetSelector.addGoal(priority, new ParasitizedTargetGoal(mob));
    }

    /**
     * バニラの動物 Mob(牛・羊・豚・ニワトリ)が {@code registerGoals()} で登録する、平和的な習性の
     * Goal を丸ごと取り除く — 殴られて逃げる({@link PanicGoal}）、繁殖する({@link BreedGoal}）、
     * 餌でおびき寄せられる({@link TemptGoal}）、親についていく({@link FollowParentGoal}）。
     * 「ゾンビなどと同じ完全な敵対 Mob」というユーザー要望により、動物ベースの4種
     * (牛・羊・豚・ニワトリ)のコンストラクタから {@link #install} とセットで呼ぶ。
     * ゾンビ/スケルトン/クリーパー/クモはバニラ側にこれらの Goal が元々無いので呼ぶ必要は無い。
     *
     * <p>{@code registerGoals()} 自体はバニラの各動物クラスが {@code super(type, level)} の中で
     * 既に実行し終えているので、後からここで該当する Goal だけを狙い撃ちして消す方式を取っている。
     */
    public static void stripPassiveAnimalGoals(Mob mob) {
        mob.goalSelector.removeAllGoals(goal ->
                goal instanceof PanicGoal
                        || goal instanceof BreedGoal
                        || goal instanceof TemptGoal
                        || goal instanceof FollowParentGoal);
    }

    /**
     * バニラのゾンビ/スケルトン/クリーパー/クモが {@code registerGoals()} で最初から持っている
     * 「プレイヤー(や村人・アイアンゴーレム)だけを狙う」ターゲット選択 Goal
     * ({@link HurtByTargetGoal}・{@link NearestAttackableTargetGoal} とそのサブクラス。クモの
     * 内部クラス {@code SpiderTargetGoal} も {@link NearestAttackableTargetGoal} を継承しているため
     * まとめて除去される)と、対応する近接攻撃 Goal({@link MeleeAttackGoal} とそのサブクラス、
     * 例: {@code ZombieAttackGoal}・{@code SpiderAttackGoal})を丸ごと取り除く。
     *
     * <p>{@link #install} が追加する {@link ParasitizedTargetGoal}(全種族対象)と専用の
     * {@link MeleeAttackGoal} だけが索敵・攻撃を担うようにするための処理 —
     * バニラ側の Goal が同じ優先度で残っていると({@code HurtByTargetGoal} は
     * {@link #install} の {@link ParasitizedTargetGoal} と同じ優先度1)、たまたま先に評価された方が
     * ターゲットを奪い合い、狙う相手がプレイヤーだけに戻ってしまう瞬間が起こり得るため、
     * 「過繁茂した(MOB名)」が種族を問わず確実に敵対する({@link ParasitizedTargetGoal} 参照)ことを
     * 保証するには不要な方を完全に除去しておくのが安全。ゾンビ/スケルトン/クリーパー/クモの
     * コンストラクタから {@link #install} とセットで(その前に)呼ぶ。動物ベースの4種
     * (牛・羊・豚・ニワトリ)は元々こういう Goal を持たないので呼ぶ必要は無い
     * ({@link #stripPassiveAnimalGoals} が担当する平和的習性のほうを参照)。
     */
    public static void stripVanillaHostileGoals(Mob mob) {
        mob.targetSelector.removeAllGoals(goal ->
                goal instanceof HurtByTargetGoal || goal instanceof NearestAttackableTargetGoal);
        mob.goalSelector.removeAllGoals(goal -> goal instanceof MeleeAttackGoal);
    }

    /**
     * 索敵(新規ターゲット探索)の際は常に「拡張後」の範囲でエンティティを収集する
     * (対象ごとの正確な距離判定は {@link #isValidTarget} が {@link #targetConditions} 経由で行うので、
     * ここでは取りこぼしが無いよう広めに箱を取るだけでよい)。
     */
    @Override
    protected AABB getTargetSearchArea(double targetDistance) {
        return super.getTargetSearchArea(baseRange + bonusRange);
    }

    /**
     * 新規ターゲット探索。設定
     * {@link OvergrownVegetationConfig.Common#prioritizeUninfectedCreepers} が有効なら、
     * まず索敵範囲内の「まだ『胞子感染』にかかっていないクリーパー」だけを対象に最も近い個体を探し、
     * 見つかればそれを距離に関係なく最優先ターゲットにする。該当個体がいなければ(=範囲内のクリーパーが
     * 全て感染済み、あるいはそもそもいない)、バニラどおり {@code super.findTarget()} で
     * 「最も近い有効ターゲット」にフォールバックする。
     *
     * <p>{@code findTarget()} は {@code canUse()} からのみ呼ばれ、{@code canUse()} は
     * その Goal が非アクティブ(=今ターゲットを持っていない)ときにしか評価されないため、
     * 「追跡中の相手を未感染クリーパー出現で横取りしない」という挙動が自然に満たされる。
     */
    @Override
    protected void findTarget() {
        if (OvergrownVegetationConfig.COMMON.prioritizeUninfectedCreepers.get()) {
            LivingEntity creeper = mob.level().getNearestEntity(
                    mob.level().getEntitiesOfClass(
                            Creeper.class,
                            getTargetSearchArea(getFollowDistance()),
                            candidate -> !isSporeInfected(candidate)),
                    targetConditions, mob, mob.getX(), mob.getEyeY(), mob.getZ());
            if (creeper != null) {
                this.target = creeper;
                return;
            }
        }
        super.findTarget();
    }

    private static boolean isSporeInfected(LivingEntity entity) {
        return entity.getEffect(ModMobEffects.SPORE_INFECTION) != null;
    }

    /**
     * 追跡継続の判定({@link net.minecraft.world.entity.ai.goal.target.TargetGoal#canContinueToUse}）は
     * 今まさに追っている相手の感染状態を見て範囲を決める — 「胞子感染2」が切れたら次の tick から
     * 通常の索敵範囲に戻り、既にその範囲外にいれば見失う(=デバフの「10秒間だけ」の効果と自然に一致する)。
     *
     * <p>{@code mob}(継承フィールド)は {@link #ParasitizedTargetGoal(Mob)} 本体が走る前の
     * 親コンストラクタからも一度呼ばれるが、その時点でも {@code mob} 自体は既に非 null
     * (上記クラス javadoc 参照)なので安全。{@code baseRange}/{@code bonusRange} はその1回だけ
     * 一時的に 0 のまま読まれるが、結果はこの直後に {@link #targetConditions} ごと上書きされるため
     * 実害は無い。
     */
    @Override
    protected double getFollowDistance() {
        LivingEntity currentTarget = mob.getTarget();
        if (currentTarget != null) {
            return baseRange + SporeInfectionDetection.bonusFor(mob, currentTarget);
        }
        return baseRange;
    }

    private boolean isValidTarget(LivingEntity candidate) {
        if (candidate == mob || !candidate.isAlive()) {
            return false;
        }
        if (candidate instanceof ParasitizedMob || candidate instanceof BlightFernEntity
                || candidate instanceof OvergrownNexusEntity) {
            return false;
        }
        if (FoliageConcealment.isConcealed(candidate)) {
            // 茂みの中にいる相手は新規ターゲットとして選ばない(クラス javadoc 参照)。
            return false;
        }
        if (!Predation.isLandTarget(mob, candidate)) {
            // 水中・水面にいる相手は狙わない(自分が水に落ちている間も同じ。Predation#isLandTarget 参照)。
            return false;
        }

        double allowedRange = baseRange + SporeInfectionDetection.bonusFor(mob, candidate);
        return mob.distanceToSqr(candidate) <= allowedRange * allowedRange;
    }

    /**
     * 追跡中の相手が茂み({@link FoliageConcealment})に逃げ込んだ場合、
     * {@link OvergrownVegetationConfig.Common#bushConcealmentGraceTicks}(既定20tick=1秒)だけ
     * 連続で隠れ続けたら見失う(ユーザー要望: 入った瞬間ではなく猶予を置いて見失う)。
     * 猶予の間に一度でも茂みから出れば {@link #concealedTicks} は0に戻り、通常の追跡に戻る。
     *
     * <p>また、{@link #isUnreachable} が「たどり着ける経路が無い」と判定した状態が
     * {@link OvergrownVegetationConfig.Common#unreachableTargetGraceTicks} を超えて続いた場合も
     * 見失う(ユーザー要望: 壁の向こうなど到達不能な相手にいつまでも張り付かず、別の標的に
     * 切り替えられるようにする)。{@code mustSee=false} かつ視線を無視した索敵にしたことで、
     * 以前より「見えてはいるが経路的には完全に隔てられている」相手を選びやすくなった副作用への対処。
     *
     * <p>それ以外は {@link net.minecraft.world.entity.ai.goal.target.TargetGoal#canContinueToUse}
     * (死亡・{@code canAttack}・チーム・距離)にそのまま委ねる — {@code mustSee=false} なので
     * 視線(LOS)そのものによる見失いは発生しない。
     */
    @Override
    public boolean canContinueToUse() {
        LivingEntity currentTarget = mob.getTarget();
        if (currentTarget == null) {
            return super.canContinueToUse();
        }
        if (FoliageConcealment.isConcealed(currentTarget)) {
            concealedTicks++;
            if (concealedTicks > OvergrownVegetationConfig.COMMON.bushConcealmentGraceTicks.get()) {
                return false;
            }
        } else {
            concealedTicks = 0;
        }
        if (!Predation.isLandTarget(mob, currentTarget)) {
            // 追跡中の相手が水に入ったらその時点で見失う
            // ({@link Predation#isLandTarget}。水生の捕食者が陸に逃げた相手を追わないのと対)。
            return false;
        }
        if (isUnreachable(currentTarget)) {
            return false;
        }
        return super.canContinueToUse();
    }

    /**
     * {@code target} へ実際にたどり着ける経路が無い状態が
     * {@link OvergrownVegetationConfig.Common#unreachableTargetGraceTicks} を超えて続いているか。
     *
     * <p>既に近接間合いに入っている場合は経路が引けなくても問題ない(足元の1マス先を無駄に
     * 探索させない)ので、まずそちらを判定する。経路探索({@code createPath})自体は重い処理なので
     * 毎tickは行わず {@link #PATH_CHECK_INTERVAL} tickに1回だけ実行し、それ以外の tick は前回の
     * 判定結果をそのまま延長する。経路が見つかった/間合いに入った時点で {@link #unreachableTicks}
     * は即座に0に戻るので、密集した植生越しなど一時的に経路が引けないだけの相手を誤って
     * 見失うことはほぼ無い({@link com.yayuy.overgrownvegetation.entity.ShielderPursueGoal} の
     * ビーライン移動で経路上の障害を抜けられれば、次回の判定までに解消する)。
     */
    private boolean isUnreachable(LivingEntity target) {
        if (mob.isWithinMeleeAttackRange(target)) {
            unreachableTicks = 0;
            pathCheckCooldown = 0;
            return false;
        }
        if (pathCheckCooldown > 0) {
            pathCheckCooldown--;
        } else {
            pathCheckCooldown = PATH_CHECK_INTERVAL;
            if (mob.getNavigation().createPath(target, 0) != null) {
                unreachableTicks = 0;
            } else {
                unreachableTicks += PATH_CHECK_INTERVAL;
            }
        }
        return unreachableTicks > OvergrownVegetationConfig.COMMON.unreachableTargetGraceTicks.get();
    }
}
