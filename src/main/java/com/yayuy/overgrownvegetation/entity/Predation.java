package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * グループ3(原生消費種)の捕食者 → グループ2(原生生産種)の被食者の対応表。
 *
 * <p>グループ2の Mob は他の Mob を攻撃しないが、対応するグループ3の Mob からは一方的に狩られる。
 * グループ3の Mob は対応するグループ2の Mob を1体倒すごとに攻撃力・体力が5%ずつ上昇する
 * ({@link PredatorMob})。
 *
 * <ul>
 *     <li>過繁茂したスカトゥリウム / ファランギオターブス → プロトファスマ・アースロプレウラ</li>
 *     <li>メガネウラ → ステノデクティア</li>
 * </ul>
 *
 * <p>対応表は {@code config/overgrown_vegetation/mobs.json} の各 Mob の {@code preys}
 * (「襲いに行く相手」)が唯一の真実(2026-09-21 に {@code predation.json} から移動)。
 * 既定値は {@link com.yayuy.overgrownvegetation.data.MobStats#DEFAULTS}。
 * {@code /ov design reload} で即反映される。
 */
public final class Predation {

    /** {@code predator} 種が {@code prey} 種を捕食対象にしているか。 */
    public static boolean isPrey(EntityType<?> predator, EntityType<?> prey) {
        return com.yayuy.overgrownvegetation.data.MobStats.preysOn(predator, prey);
    }

    /** {@code predator} が {@code prey} を捕食する関係か({@link LivingEntity} 版)。 */
    public static boolean isPredatorOf(LivingEntity predator, LivingEntity prey) {
        return isPrey(predator.getType(), prey.getType());
    }

    /**
     * {@code candidate} が {@code self}(グループ3の捕食者)にとっての「侵入者」か —
     * 縄張りに入ってきた同盟軍の兵士(タグ {@code overgrown_vegetation:allies}、2026-10-01)、
     * またはバニラの敵対 Mob({@link Enemy})。
     *
     * <p>捕食者は捕食対象({@link #isPredatorOf})に加えてこれらにも先制攻撃する
     * (ユーザー決定 2026-09-08: 「捕食者は侵入者とも戦う」)。被食者(グループ2)や
     * 他の過繁茂 Mob・苗床・汚染シダは対象外。
     *
     * <h2>プレイヤーは侵入者にしない(ユーザー指定 2026-10-03)</h2>
     * <p>グループ2・3 は<b>環境生物</b> —— 不思議な生態をしているだけで、プレイヤーを襲わない
     * (ユーザー指定 2026-09-21「最近の感染系 MOD は player を苦しめることしか考えていない気がする」)。
     * <b>殴られたら反撃する</b>のは別口で、優先度 1 の {@link OvergrownHurtByTargetGoal} が受け持つ
     * (ユーザー指定 2026-10-03「反撃はする」)。
     *
     * <p><b>兵士とバニラの敵対 Mob は侵入者のまま</b>(ユーザー指定 2026-10-03)。
     * この種族は「縄張りに入ったものを襲う」生き物のままで、プレイヤーだけが例外になる。
     *
     * <p>寄生植物装備のプレイヤーを除く分岐はここから消えた —— プレイヤーを一律で外すので要らない。
     * 装備による「過繁茂側(グループ 6)扱い」は {@link #isInvaderTarget} と
     * {@code MobGroup#of(Entity)} に残っている(そちらはプレイヤーを狙う側なので今も要る)。
     */
    public static boolean isIntruder(LivingEntity self, LivingEntity candidate) {
        if (candidate == self || !candidate.isAlive()) {
            return false;
        }
        if (candidate instanceof ParasitizedMob
                || candidate instanceof BlightFernEntity
                || candidate instanceof OvergrownNexusEntity) {
            return false;
        }
        if (candidate instanceof Player) {
            return false;
        }
        if (candidate.getType().is(com.yayuy.overgrownvegetation.ally.AllyEntities.ALLIES)) {
            return true;
        }
        return candidate instanceof Enemy;
    }

    /**
     * {@code candidate} が {@code self}(グループ4・拡大種)の襲撃対象か —
     * <b>プレイヤー、および「グループ1〜10のどれにも属していない」生き物すべて</b>
     * (ユーザー要望 2026-09-13:「積極的に過繁茂したバイオームの外に出て、プレイヤーや
     * グループ1〜10に属していない mob を襲う」)。
     *
     * <p>{@link #isIntruder} との違いは対象の広さ。捕食者(グループ3)が襲うのは
     * 「プレイヤー + バニラの敵対 Mob」だけだが、拡大種は牛・村人・ニワトリのような
     * 無害な相手も同じように襲う。汚染側({@link MobGroup} に属する Mob・
     * {@link ParasitizedMob} の実装・汚染シダ・苗床)だけが対象外。
     */
    public static boolean isInvaderTarget(LivingEntity self, LivingEntity candidate) {
        if (candidate == self || !candidate.isAlive()) {
            return false;
        }
        if (candidate instanceof Player player) {
            // 「寄生植物装備」を着けたプレイヤーは過繫茂側(グループ 6)なので狙わない。
            return !player.isCreative() && !player.isSpectator()
                    && !com.yayuy.overgrownvegetation.item.PlantArmor.isHost(player);
        }
        if (candidate instanceof ParasitizedMob
                || candidate instanceof BlightFernEntity
                || candidate instanceof OvergrownNexusEntity) {
            return false;
        }
        // グループ1〜10 は味方。それ以外は全部襲う。
        return MobGroup.of(candidate.getType()) == null;
    }

    /**
     * {@code candidate} が {@code self}(グループ4・拡大種)の<b>獲物</b>か
     * (ユーザー要望 2026-09-15:「グループ3 がグループ2 に行うような捕食行動を、
     * グループ4 がグループ1〜3 に対して取る」)。
     *
     * <p>グループ3 の捕食({@code predation.json} の対応表)と違い、<b>表を持たない</b> ——
     * グループ4 にとってはグループ1〜3 が丸ごと獲物。汚染側の中でも
     * 「先に生まれた側を拡大種が食って成り上がる」という関係なので、種の組み合わせを
     * 細かく決める意味が薄い。
     *
     * <p>ステータスが上がるのは<b>アルボラとスカトゥリウムだけ</b>
     * ({@link PredatorMob} を実装しているのがこの 2 種)。ほかのグループ4 も獲物を狙うが、
     * それは<b>この 2 種のための地ならし</b>(群れの司令塔の獲物探し —— 旧 {@code InvaderPreyIntel} —— / {@link SporophyllAura})。
     *
     * <p>グループ4 同士・グループ5〜7・プレイヤー・グループ外は対象外
     * (プレイヤーとグループ外は {@link #isInvaderTarget} のほうで拾う)。
     */
    public static boolean isInvaderPrey(LivingEntity self, LivingEntity candidate) {
        if (candidate == self || !candidate.isAlive()) {
            return false;
        }
        if (MobGroup.of(self.getType()) != MobGroup.GROUP_4) {
            return false;
        }
        return isInvaderPreyGroup(candidate);
    }

    /**
     * {@code candidate} が<b>グループ4 の獲物にあたるグループ</b>(1〜3)に属しているか。
     * 襲う側を問わない版の {@link #isInvaderPrey}。
     *
     * <p>要るのは<b>襲った本人が手元に無い</b>ところ —— 胞子爆弾
     * ({@link VelophyllBombEntity})はリログを挟むと落とした本人への参照が切れるが、
     * <b>あれを落とすのはグループ4 しかいない</b>ので、獲物かどうかは相手側だけで決まる。
     */
    public static boolean isInvaderPreyGroup(LivingEntity candidate) {
        MobGroup group = MobGroup.of(candidate.getType());
        return group == MobGroup.GROUP_1 || group == MobGroup.GROUP_2 || group == MobGroup.GROUP_3;
    }

    /**
     * 陸生の過繁茂 Mob が {@code candidate} を索敵対象にしてよいか —
     * <b>水中・水面にいる相手は狙わない</b>(ユーザー要望 2026-09-11)。
     *
     * <p>水生の捕食者が陸上の相手を無視する({@link #isWaterborneTarget})のとちょうど対になる規則で、
     * 「陸の Mob が魚・イカ・水生の過繁茂 Mob(や泳いで逃げたプレイヤー)を延々と追って溺れる」のを防ぐ。
     * 水中・水面かの判定は {@link AquaticOvergrownMob#inWaterOrAtSurface} と共通。
     *
     * <p><b>自分自身が水に入っている</b>間も同じく狙わない —— 狙えば水から上がらなくなり、
     * 水ぐされ({@link com.yayuy.overgrownvegetation.effect.WaterRotTracker})で溶けてしまうため
     * (ユーザー要望 2026-09-17:「アルボラやスカトゥリウムなど遠距離攻撃手段のない Mob が
     * 水中の Mob を狙わないようにしてほしい」)。落ちた個体はまず岸へ上がる
     * ({@link EscapeWaterGoal})。
     *
     * <p>例外は<b>水に入らずに攻撃できる種</b>({@link ModTags#RANGED_ATTACKER} —— フィロセンスの
     * ビーム、ヴェロフィルの爆弾)だけ。こちらは浅瀬に立ったまま隣の相手を撃てるので、
     * 自分が水に浸かっている間は従来どおり狙える。スカトゥリウムの迫撃砲はこの例外に<b>含めない</b>
     * (理由は {@link ModTags#RANGED_ATTACKER} の説明)。
     *
     * <p>この規則が掛かるのは索敵({@link OvergrownLandTargetGoal} /
     * {@link com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal})だけで、反撃
     * ({@link OvergrownHurtByTargetGoal})は対象外 — 水中から攻撃してきた相手には従来どおり反撃する。
     */
    /**
     * <b>自分よりずっと深く潜っている相手は狙わない</b>(ユーザー報告 2026-09-20:
     * 「アルボラが地下の敵を標的にしてしまい、ずっと地面を掘り続けてしまう」)。
     *
     * <p>見るのは<b>お互いの「地表からの深さ」の差</b>。地表は葉を無視した高さ
     * ({@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES})なので、森の木の下は地表扱いになる。
     *
     * <pre>
     *   相手の深さ - 自分の深さ &gt; landMobMaxTargetDepth  →  狙わない
     * </pre>
     *
     * <p><b>絶対の高さではなく「深さの差」で見るのが肝。</b>
     * <ul>
     *     <li>崖の上に立っていて相手が崖下の<b>地表</b>にいる場合 —— 相手の深さは 0 なので狙う。
     *     高さの差だけで切ると、歩いて降りられる相手を見逃してしまう。</li>
     *     <li>自分が<b>坑道の中</b>(深さ 20)にいて相手も同じ坑道にいる場合 —— 差が小さいので狙う。
     *     絶対の深さで切ると、地下で鉢合わせした相手とも戦えなくなる。</li>
     *     <li>自分が地表(深さ 0)で相手が洞窟(深さ 20)—— 差が 20 なので狙わない。これが直したかった形。</li>
     * </ul>
     *
     * <p><b>反撃には掛からない</b>({@link OvergrownHurtByTargetGoal} は別経路)。
     * 洞窟から撃ってきた相手には今までどおり反撃する ——
     * そのあと辿り着けなければ、詰まり検知が相手を諦める。
     */
    public static boolean isWithinChaseDepth(LivingEntity self, LivingEntity candidate) {
        double limit = OvergrownVegetationConfig.COMMON.landMobMaxTargetDepth.get();
        if (limit <= 0.0D) {
            return true;
        }
        return depthBelowSurface(candidate) - depthBelowSurface(self) <= limit;
    }

    /** その位置の地表(葉を無視した高さ)から何ブロック下にいるか。地表より上なら 0 以下。 */
    private static double depthBelowSurface(LivingEntity entity) {
        int surface = entity.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(entity.getX()), Mth.floor(entity.getZ()));
        return surface - entity.getY();
    }

    public static boolean isLandTarget(LivingEntity self, LivingEntity candidate) {
        if (!AquaticOvergrownMob.inWaterOrAtSurface(candidate)) {
            return true;
        }
        return self.getType().is(ModTags.RANGED_ATTACKER) && AquaticOvergrownMob.inWaterOrAtSurface(self);
    }

    /**
     * 水生の捕食者({@link AbstractOvergrownAquaticPredatorEntity})が襲う「水中・水面の相手」か。
     *
     * <p>ユーザー要望 2026-09-08 / 09: 「<b>水中・水面にいる</b>グループ1・2・3の Mob(および水中の
     * 侵入者)は襲う。海岸・川辺の陸上にいる相手は襲わない」。捕食対象({@link #isPredatorOf})は
     * 水陸問わず襲うのでここには含めない(別 Goal)。
     *
     * <ul>
     *     <li>{@link AquaticOvergrownMob#inWaterOrAtSurface} が true でなければ対象外(陸上は無視)。</li>
     *     <li>同種は襲わない(共食い防止)。</li>
     *     <li>グループ1・2・3({@link MobGroup})に属する Mob(水生の {@link RhizodusEntity}/
     *     {@link XenacanthusEntity} や、水に入ってきた陸生の過繁茂 Mob 含む)なら対象。
     *     グループ4以降(汚染シダ等)・苗床は対象外。</li>
     *     <li>いずれのグループにも属さない相手はバニラの侵入者判定({@link #isIntruder})に従う
     *     (水中のプレイヤー・敵対 Mob)。</li>
     * </ul>
     */
    public static boolean isWaterborneTarget(LivingEntity self, LivingEntity candidate) {
        if (candidate == self || !candidate.isAlive() || candidate.getType() == self.getType()) {
            return false;
        }
        if (!AquaticOvergrownMob.inWaterOrAtSurface(candidate)) {
            return false;
        }
        MobGroup group = MobGroup.of(candidate.getType());
        if (group == MobGroup.GROUP_1 || group == MobGroup.GROUP_2 || group == MobGroup.GROUP_3) {
            return true;
        }
        return isIntruder(self, candidate);
    }

    private Predation() {
    }
}
