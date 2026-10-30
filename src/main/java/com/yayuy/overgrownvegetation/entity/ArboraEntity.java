package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * 「アルボラ」({@code arbora}) — グループ4(拡大種)の最初の 1 種。大量に湧く雑魚枠。
 *
 * <p>カマキリ型。1 対の武器腕を振り上げて叩きつける近接攻撃を持ち、移動速度はプレイヤーの
 * ダッシュジャンプと同じくらい速い(既定 {@code movementSpeed 0.36})。標的を見つけると
 * ちょくちょく飛びついて距離を詰める({@link InvaderLeapGoal})。
 * 体力 22・防御力 5・攻撃力 8。
 *
 * <p>グループ4 共通の動き(過繁茂バイオームの外へ出る・グループ外の生き物を襲う・汚れていない
 * 土地でうずくまって胞子をばらまき<b>その間だけ</b>周囲を汚染する・死亡時に周囲を汚染する)は
 * {@link AbstractOvergrownInvaderEntity} 側にある。
 *
 * <p>アルボラ固有の足まわりは<b>壁のぼり/天井這い</b>({@link #canClimbWalls})と、
 * <b>川や池を跳び越える</b>({@link InvaderWaterLeapGoal})の 2 つ。
 *
 * <h2>アニメーション名(GeckoLib、差し替え用)</h2>
 * {@code idle} / {@code walk} / {@code run} / {@code attack} / {@code leap} / {@code spore}。
 * {@code assets/overgrown_vegetation/animations/arbora.animation.json}。動きの確認は
 * {@code tools/anim-preview}(モーションビューア)で。
 */
public class ArboraEntity extends AbstractOvergrownInvaderEntity implements PredatorMob {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN_ANIM = RawAnimation.begin().thenLoop("run");
    private static final RawAnimation ATTACK_ANIM = RawAnimation.begin().thenLoop("attack");
    private static final RawAnimation LEAP_ANIM = RawAnimation.begin().thenLoop("leap");
    private static final RawAnimation SPORE_ANIM = RawAnimation.begin().thenLoop("spore");

    /**
     * 捕食したグループ1〜3 の累計({@link PredatorMob})。1 体ごとに最大体力と攻撃力が
     * {@code predation.json} の {@code preyKillBonusPerKill} ずつ複利で上がる。
     *
     * <p><b>この成長ができるのはアルボラとスカトゥリウムだけ</b>(ユーザー指定 2026-09-15)。
     * ほかのグループ4 は獲物を追い込む側に回る —— 群れの司令塔が獲物を探して前衛へ渡し
     * (旧 {@code InvaderPreyIntel}。2026-09-25 に群れの標的探しへ統合)、スポロフィルがバフから外してデバフを掛ける
     * ({@link SporophyllAura})。
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

    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("PreyKills", this.preyKillCount);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.preyKillCount = tag.getInt("PreyKills");
        PredatorMob.applyPreyKillBonus(this, this.preyKillCount);
    }

    public ArboraEntity(EntityType<? extends ArboraEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("arbora", 5);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "arbora");
    }

    /**
     * カマキリらしく<b>壁を登り、天井を這う</b>(ユーザー要望 2026-09-14 — ファランギオターブスと同じ動き)。
     * 経路探索も {@link net.minecraft.world.entity.ai.navigation.WallClimberNavigation} になる。
     * 判定と駆動は {@link AbstractOvergrownInvaderEntity} 側、体の傾きは
     * {@code client/ArboraRenderer} 側。
     */
    @Override
    protected boolean canClimbWalls() {
        return true;
    }

    /*
     * 群れの先導役(ユーザー要望 2026-09-15「アルボラを先遣隊に」、2026-09-25「1 匹が先導」)は、群れの司令塔が
     * 前衛の中から<b>アルボラを先に</b>選ぶ(swarm/SwarmController の選び方)。旧い群れの「先遣隊」の印
     * (isPackPointUnit)と、先遣隊どうしの広がり(invaderPackTetherDistance)は 2026-09-25 に消えた。
     */

    /**
     * <b>1 体ずつうずくまって胞子をばらまく動きは取らない</b>(ユーザー指定 2026-09-20)。
     *
     * <p>アルボラの汚染の広げ方は、群れごと止まって汚染する形で受け持つ(2026-09-24、ユーザー指定「群れで止まって汚染」)。
     * 群れの中では {@link com.yayuy.overgrownvegetation.swarm.goal.SwarmSpreadGoal} が、このうずくまりと同じ中身
     * ({@link SporeSpreadSession})で汚す({@link #createSpreadAction} の既定)。群れに居ないアルボラは汚染を広げない。
     */
    @Override
    protected boolean useSporeCrouchGoal() {
        return false;
    }

    @Override
    protected void registerSpeciesGoals() {
        // 川や池は跳び越えて渡る(水を経路に使えないので、そのままだと岸で足踏みしてしまう)。
        // 勢い(横 2.44 / 上 0.91)から逆算して最大 24 ブロック先・5 ブロックの高さまで届く
        // (ユーザー指定 2026-09-14)。着地できる向こう岸を確かめてから跳ぶので、
        // 標的への飛びつき(水へ落ちうる)より先に見る。汀に着く前 —— 水まで 6 マス手前 —— でも
        // 跳ぶので、川へ向かって歩いている途中で足が止まらない(2026-09-17)。
        this.goalSelector.addGoal(2, new InvaderWaterLeapGoal(this, 2.44D, 0.91D, 60));
        // 標的へ 3.5 秒に 1 回まで飛びつく。跳ぶ勢い(横 1.15 / 上 0.55)から届く距離と高さは
        // InvaderLeapGoal 側が逆算するので、飛距離を変えたいときはこの 2 つの数字だけ動かす。
        // 現状およそ 9.7 ブロック先まで・2.0 ブロックの高さまで届く(2026-09-14 に 6.2 → 9.7 へ延長)。
        this.goalSelector.addGoal(2, new InvaderLeapGoal(this, 1.15D, 0.55D, 70));
        // 詰まったとき、回り道より先に<b>坑道を掘る</b>(ユーザー要望 2026-09-17)。
        // 回り道({@link InvaderUnstickGoal})と同じ優先度だが、あちらが
        // prefersDiggingWhenStuck() を見て譲るので競合しない。
        this.goalSelector.addGoal(1, new ArboraTunnelGoal(this));
    }

    /** 詰まったら回り道より先に坑道を掘る({@link ArboraTunnelGoal})。 */
    @Override
    public boolean prefersDiggingWhenStuck() {
        return ArboraTunnelGoal.wantsToDig(this);
    }

    /**
     * 動作を切り替えるときの補間(tick)。GeckoLib はこの間に今の姿勢から新しいアニメの 0 秒目の姿勢へ寄せ、
     * <b>それが終わってからアニメを 0 秒目から流す</b>(4.7.6 の {@code AnimationController#process})。
     * だから画面の上では、どのアニメも状態を切り替えた tick より 4 tick 遅れて始まる。
     * アニメの長さに合わせる tick は「これ + アニメの秒 × 20」で数える。
     */
    static final int TRANSITION_TICKS = 4;

    /**
     * {@code attack} は 0.6 秒 = 12 tick。補間のぶん後ろへずらす。
     * 12 のままだと画面では 0.35 秒(振り下ろしの途中)で待機へ戻り、叩きつけ(0.4 秒)が出なかった(2026-09-23)。
     */
    @Override
    protected int attackAnimationTicks() {
        return TRANSITION_TICKS + 12;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", TRANSITION_TICKS, state -> {
            switch (getActionState()) {
                case STATE_SPORE -> {
                    return state.setAndContinue(SPORE_ANIM);
                }
                case STATE_LEAP -> {
                    return state.setAndContinue(LEAP_ANIM);
                }
                case STATE_ATTACK, STATE_DIG -> {
                    // 掘りは専用のモーションを持たないので attack を流す
                    // (壁を繰り返し叩いている見え方になる)。
                    return state.setAndContinue(ATTACK_ANIM);
                }
                default -> {
                }
            }
            // 壁・天井では水平方向にほとんど動かない(= isMoving が立たない)ので、
            // 脚が止まって見えないよう歩きを流しておく。傾きはレンダラーが付ける。
            if (isClimbing() || isOnCeiling()) {
                return state.setAndContinue(WALK_ANIM);
            }
            if (MobAnimation.isWalking(state)) {
                return state.setAndContinue(
                        MobAnimation.isFasterThan(state, 0.14D) ? RUN_ANIM : WALK_ANIM);
            }
            return state.setAndContinue(IDLE_ANIM);
        }));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.SPIDER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SPIDER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SILVERFISH_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 0.6F;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.SPIDER_STEP, 0.12F, 1.1F);
    }
}
