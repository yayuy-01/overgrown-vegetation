package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import com.yayuy.overgrownvegetation.swarm.SwarmOrigin;
import com.yayuy.overgrownvegetation.swarm.SwarmSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

import java.util.ArrayList;
import java.util.List;

/**
 * 「スポロフィル」({@code sporophyll}) — グループ4(拡大種)の<b>支援役</b>。
 *
 * <p>頭のキノコの傘から胞子を撒き続け、<b>半径 {@code sporophyllAuraRange}(既定 64。2026-09-26 に 32 から倍に)を
 * 丸ごと「汚染側に都合のいい場所」に変える</b>。中身はすべて {@link SporophyllAura}:
 * <ul>
 *     <li>味方(グループ4 以上)に 再生 I・攻撃力上昇 II + 独自バフ「スポロフィルの胞子」
 *     (2026-09-26 に耐性をやめ、再生を II → I。撒いている本人には掛からない)</li>
 *     <li>敵に 独自デバフ「纏わりついた胞子」(移動速度低下・攻撃力低下を含む)+ 5 秒ごとに装備の胞子汚染 +1%</li>
 *     <li>足元の胞子濃度を大きく引き上げる</li>
 *     <li>その範囲を<b>過繫茂バイオームと同じ扱い</b>にする(胞子感染が 1 → 2 → 3 と上がっていく)</li>
 * </ul>
 * <b>自分では攻撃しない。</b> 戦うのは、この傘の下で強化された仲間のほう。
 * <b>標的も掴まない</b>({@link #useTargetGoals()})—— 掴んでも何もできず、相手を抱えたまま動けなくなる。
 * 群れの中でも司令塔は支援役に標的を渡さない(ユーザー指定 2026-09-25「スポロフィルは標的を持たない」)。
 *
 * <h2>群れの中での立ち位置(v2、2026-09-25)</h2>
 * 群れでは支援役(SUPPORT)として前衛の<b>後ろの列</b>を歩く(ユーザー指定「スポロフィル・フィロセンスを後方に」。
 * 並びは群れの司令塔が役割で決める)。フィロセンスより前なので、オーラの半径(既定 64)に前衛が収まる。
 * 群れが止まって汚すときは自分の場所に立っているだけ(傘の胞子がいつも汚している)。
 * 旧い群れの「重心へ寄る倍率」は 2026-09-25 に消えた。
 *
 * <h2>体はフィロセンスの流用</h2>
 * ボーン構成は {@link PhyllosensEntity} と完全に一致する(花 {@code flower}+{@code bone10〜13} が
 * 傘 {@code kasa} に変わっただけ)。そのため <b>{@code idle} / {@code walk} / {@code run} は
 * {@code phyllosens.animation.json} をそのまま写してある</b> —— 歩き方・走り方を直すときは
 * <b>両方そろえて直すこと</b>。
 *
 * <h2>傘から落ちる胞子</h2>
 * <b>アニメーションではなく粒子</b>。{@link #spawnCapSpores} が行動状態に関係なく毎 tick 出しているので、
 * どのアニメーションでも必ず降っている(ユーザー要望「すべてのアニメーションに付けてほしい」)。
 */
public class SporophyllEntity extends AbstractOvergrownInvaderEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN_ANIM = RawAnimation.begin().thenLoop("run");

    /**
     * 傘の高さ(素のモデルでの足元からのブロック数)。<b>描画が始まるまでの保険にしか使わない</b> ——
     * 普段は {@link #capPosition}(実際に焼かれた骨の位置)を使う。
     *
     * <p>ピボットの生の値(3.43)ではないのは、{@code bone5} と {@code head} に 45 度ずつの
     * 基準回転が入っていて、傘が<b>実際にはもっと上・もっと手前</b>に来るため。
     */
    private static final double CAP_HEIGHT = 4.85D;
    /** 傘の前方向への出っ張り(素のモデルでのブロック数)。上の注記と同じ理由で生のピボットとは違う。 */
    private static final double CAP_FORWARD = 2.73D;
    /** 傘の半径(素のモデルでのブロック数)。粒子を散らす広さ。 */
    private static final double CAP_RADIUS = 1.6D;
    /** 1 tick に傘から出す粒の数。 */
    private static final int CAP_SPORES_PER_TICK = 2;

    /**
     * 汚染の中心を張り直すまでの待ち(tick)。汚し尽くした / 胞子除去装置に阻まれたあと、
     * すぐ張り直すと<b>毎 tick 候補一覧を作り直す</b>ことになるので間を置く。
     */
    private static final int CORRUPTION_RETRY_TICKS = 60;
    /** 中心からこれだけ歩いたら張り直す(半径に対する割合)。 */
    private static final double CORRUPTION_REANCHOR_FRACTION = 0.5D;
    /** 呼び出したアルボラを置く水平範囲(ブロック)。 */
    private static final int SUMMON_SPREAD = 4;
    /** 湧かせすぎ防止で周囲のグループ所属 Mob を数える半径(ブロック)。 */
    private static final double SUMMON_NEARBY_RADIUS = 16.0D;

    /** 前回 {@link SporophyllAura} へ登録したチャンク。またいだら濃度キャッシュを捨てる。 */
    private long lastChunk = Long.MIN_VALUE;
    /** 装備の胞子汚染を次に進める {@code tickCount}。 */
    private int nextGearTick;

    /** いま広げている汚染。{@link #tickCorruption} が張り替える。 */
    @Nullable
    private InvaderSporeField corruptionField;
    /** {@link #corruptionField} の中心。ここから離れたら張り直す。 */
    @Nullable
    private BlockPos corruptionAnchor;
    /** 汚染を張り直してよくなる {@code tickCount}。 */
    private int corruptionRetryTick;

    /** 前回アルボラを呼んでから受けたダメージの合計。{@code sporophyllSummonDamage} ごとに呼ぶ。 */
    private float damageSinceSummon;

    /**
     * 傘が実際にあるワールド座標。<b>クライアント専用</b>で、
     * {@code client/SporophyllRenderer} が描画のたびに焼かれた骨の行列から読んで入れる。
     *
     * <p>骨の鎖に基準回転(45 度 × 2)が入っているうえアニメーションでも首が動くので、
     * ピボットからの計算では合わない(2026-09-14 のユーザー報告「パーティクルの位置がずれている」)。
     * 描画がまだ来ていない間だけ {@link #CAP_HEIGHT} / {@link #CAP_FORWARD} の概算を使う。
     */
    @Nullable
    private Vec3 capPosition;

    public SporophyllEntity(EntityType<? extends SporophyllEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("sporophyll", 14);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "sporophyll");
    }

    /**
     * うずくまって汚染を広げる動き({@link InvaderSporeCrouchGoal})は使わない。
     * <b>傘の胞子がその役をずっと果たしている</b>し、専用の {@code spore} アニメーションも無い。
     */
    @Override
    protected boolean useSporeCrouchGoal() {
        return false;
    }

    /** 自分では殴らない(支援役)。専用の {@code attack} アニメーションも無い。 */
    @Override
    protected boolean useMeleeAttackGoal() {
        return false;
    }

    /**
     * <b>標的を掴まない</b>。殴る手も撃つ手も持たないので、掴んでも何も起きないどころか、
     * <b>相手を抱えたままその場をうろつくだけ</b>になる(2026-09-15、旧い群れの実装で判明)。
     * 反撃も同じ理由で持たせない —— 殴られたら群れの仲間が代わりに反応する(群れ v2 では司令塔が、殴った相手へ群れの標的を
     * 替えるかを決める。スポロフィル自身には渡さない)。
     */
    @Override
    public boolean useTargetGoals() {
        return false;
    }

    /** 群れが止まって全員で汚すとき、スポロフィルは自分の場所に立っているだけ(傘の胞子がいつも汚している)。 */
    @Override
    protected com.yayuy.overgrownvegetation.swarm.goal.SwarmSpreadGoal.Action createSpreadAction() {
        return null;
    }

    /**
     * 常時ばらまく汚染の半径({@code sporophyllCorruptionRadius})。
     * ほかのグループ4 と違い「うずくまっている間だけ」ではなく<b>ずっと</b>撒き続けるので、
     * 1 回あたりの範囲はアルボラ(既定 4)とフィロセンス(既定 10)の中間に置いてある。
     */
    @Override
    public int sporeCorruptionRadius() {
        return OvergrownVegetationConfig.COMMON.sporophyllCorruptionRadius.get();
    }

    // --- 胞子を撒く ---

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = this.blockPosition();
        SporophyllAura.mark(level, pos);
        tickCorruption(level, pos);

        // チャンクをまたいだら、前のチャンクの位置登録を捨てて濃度キャッシュを無効化する
        // (濃度は 200 tick キャッシュされるので、そうしないと効果が置き去りになる)。
        long chunk = ChunkPos.asLong(pos);
        if (chunk != this.lastChunk) {
            int r = boostInvalidateRadius();
            if (this.lastChunk != Long.MIN_VALUE) {
                SporeConcentration.invalidateAround(level, new ChunkPos(this.lastChunk), r);
            }
            SporeConcentration.invalidateAround(level, new ChunkPos(chunk), r);
            this.lastChunk = chunk;
        }

        int interval = Math.max(1, OvergrownVegetationConfig.COMMON.sporophyllAuraIntervalTicks.get());
        // 2026-09-24 軽量化(監査 940): 配る tick を個体ごとにずらす。tickCount は保存されないので、
        // 同じ tick に湧いた・読み込まれた個体がそろって同じ tick に傘の半径を走査していた。
        // ずれるのは配る tick だけ(間隔は同じ、初回が最大 interval - 1 tick 前後する)。
        if (Math.floorMod(this.tickCount + this.getId(), interval) != 0) {
            return;
        }
        boolean gearTick = this.tickCount >= this.nextGearTick;
        if (gearTick) {
            this.nextGearTick = this.tickCount
                    + OvergrownVegetationConfig.COMMON.sporophyllGearContaminationIntervalTicks.get();
        }
        SporophyllAura.apply(level, this, gearTick);
    }

    /**
     * チャンクをまたいだときに濃度キャッシュを捨てる半径(チャンク)。
     *
     * <p>2026-09-24 軽量化(監査 1813): 以前は濃度の計算半径 + 1(既定 4 = 周り 81 チャンク × 2)を捨てていたが、
     * 傘の上乗せ({@link SporophyllAura#concentrationBoostAt})が効くのは「チャンクの中心が足跡から
     * {@code sporophyllAuraRange} 未満」のチャンクだけで、足跡のチャンクから見て
     * {@code (range + 7.5) / 16} チャンク未満(既定 64 なら 4 チャンク先)まで。
     * 1 つ余裕を持たせた {@code ceil(range / 16) + 1} にし、以前の半径は超えない
     * (既定 64 では 5 → 以前の半径 4 で頭打ち。4 チャンク先まで届くので足りている。
     * 範囲を 72 より広げると、外周のチャンクは下の寿命まで古い濃度のままになる)。
     * 上乗せの届かない外側のキャッシュは、ふだんどおり寿命(既定 200 tick)で計算し直される。
     */
    private static int boostInvalidateRadius() {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        int concentrationRadius = cfg.sporeConcentrationRadiusChunks.get() + 1;
        int boostReach = Mth.ceil(cfg.sporophyllAuraRange.get() / 16.0D) + 1;
        return Math.min(concentrationRadius, boostReach);
    }

    /**
     * 立ち止まっていようが歩いていようが、<b>足元の周りを汚染し続ける</b>
     * (ユーザー要望 2026-09-15「常に周囲を汚染していく感じに」)。
     *
     * <p>ほかのグループ4 が「うずくまっている間だけ」汚すのに対し、この Mob には
     * うずくまり({@link InvaderSporeCrouchGoal})が無い —— 傘の胞子がその役をずっと果たしている、
     * という位置づけなので<b>常時</b>が筋になる。汚し方そのものは共通の {@link InvaderSporeField}
     * (近い順に 1 マスずつ・胞子除去装置の中では止まる)。
     *
     * <p>違うのは<b>中心を張り替える</b>ところ。{@link InvaderSporeField} は中心を固定した
     * 使い切りの仕掛けなので、歩く Mob に付いて回らせるには
     * <ul>
     *     <li>半径の {@value #CORRUPTION_REANCHOR_FRACTION} だけ歩いたら、または</li>
     *     <li>届く範囲を汚し尽くしたら({@code isFinished})</li>
     * </ul>
     * いまの足元を中心に作り直す。作り直しは候補一覧を並べ直す処理なので、
     * 尽きたあとは {@value #CORRUPTION_RETRY_TICKS} tick 待ってからにする ——
     * これが無いと、汚し終わった場所や胞子除去装置の中で<b>毎 tick 作り直す</b>ことになる。
     */
    private void tickCorruption(ServerLevel level, BlockPos here) {
        int radius = sporeCorruptionRadius();
        if (radius <= 0) {
            return;
        }
        double reanchor = Math.max(2.0D, radius * CORRUPTION_REANCHOR_FRACTION);
        boolean moved = this.corruptionAnchor == null
                || this.corruptionAnchor.distSqr(here) > reanchor * reanchor;
        boolean exhausted = this.corruptionField == null || this.corruptionField.isFinished();
        if (moved || (exhausted && this.tickCount >= this.corruptionRetryTick)) {
            this.corruptionAnchor = here;
            this.corruptionField = new InvaderSporeField(here, radius,
                    OvergrownVegetationConfig.COMMON.sporophyllCorruptionIntervalTicks.get(),
                    this.random);
            this.corruptionRetryTick = this.tickCount + CORRUPTION_RETRY_TICKS;
        }
        if (this.corruptionField != null) {
            this.corruptionField.spread(level, here);
        }
    }

    // --- ダメージを受けるとアルボラを呼ぶ ---

    /**
     * <b>{@code sporophyllSummonDamage} ダメージ受けるごとに、アルボラを
     * {@code sporophyllSummonCount} 体呼ぶ</b>(ユーザー要望 2026-09-15)。
     *
     * <p>数えるのは<b>実際に減った体力</b>(防具・吸収などを通したあとの値)であって、
     * 攻撃側の生ダメージではない。自分の傘のバフは自分には掛からない({@link SporophyllAura#apply} が本人を飛ばす)。
     * 2026-09-26 に傘のバフから耐性が外れたので、近くの別のスポロフィルから耐性をもらうことも無くなった。
     *
     * <p>しきい値を 2 回ぶん超える大きな一撃なら、その場で 2 回ぶん呼ぶ(端数は繰り越す)。
     * 倒される一撃でも呼ぶ —— 散り際に仲間を残していく形になる。
     *
     * <p>呼んだアルボラは<b>群れに入る</b>(群れ v2、2026-09-25): 自分の群れに空きがあればそこへ、無ければ新しい群れで。
     * {@link #summonGroup} を参照。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        float before = this.getHealth();
        boolean hurt = super.hurt(source, amount);
        if (hurt && this.level() instanceof ServerLevel level) {
            float taken = before - this.getHealth();
            if (taken > 0.0F) {
                this.damageSinceSummon += taken;
                trySummonGuards(level, source);
            }
        }
        return hurt;
    }

    private void trySummonGuards(ServerLevel level, DamageSource source) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double threshold = cfg.sporophyllSummonDamage.get();
        int count = cfg.sporophyllSummonCount.get();
        if (threshold <= 0.0D || count <= 0) {
            this.damageSinceSummon = 0.0F;
            return;
        }
        while (this.damageSinceSummon >= threshold) {
            this.damageSinceSummon -= (float) threshold;
            if (nearbyCrowded(level, cfg.sporophyllSummonNearbyCap.get())) {
                continue;
            }
            int placed = summonGroup(level, count, source);
            if (placed > 0) {
                level.sendParticles(ModParticles.FINE_SPORE.get(),
                        this.getX(), this.getY(this.getBbHeight() * 0.7D), this.getZ(),
                        40, 1.2D, 0.6D, 1.2D, 0.02D);
                level.playSound(null, this.blockPosition(), SoundEvents.SCULK_CATALYST_BLOOM,
                        this.getSoundSource(), 1.2F, 0.7F);
            }
        }
    }

    /**
     * 周囲 {@value #SUMMON_NEARBY_RADIUS} ブロックに、グループに属する Mob が既に {@code cap} 体以上いるか。
     * 湧かせすぎの歯止め({@code sporophyllSummonNearbyCap} を 0 にすると無制限)。
     */
    private boolean nearbyCrowded(ServerLevel level, int cap) {
        if (cap <= 0) {
            return false;
        }
        int existing = 0;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                this.getBoundingBox().inflate(SUMMON_NEARBY_RADIUS))) {
            if (MobGroup.of(entity.getType()) != null && ++existing >= cap) {
                return true;
            }
        }
        return false;
    }

    /**
     * アルボラを {@code count} 体呼んで群れに入れる(群れ v2、2026-09-25。swarm-api の「Sporophyll summons」)。
     * 「呼ばれた以上は、呼んだ相手を襲っているものへ加勢させる」(ユーザー要望 2026-09-15)は群れの規則で守る:
     * <ul>
     *     <li>自分が地上の群れの一員で、その群れに {@code count} 体の空きがある({@link SwarmManager#hasRoom}: 上限・組み直した直後・
     *     読み込み外れた一員が戻っていない、を見る)なら、足元の周り ±{@value #SUMMON_SPREAD} に置いて同じ群れへ入れる
     *     ({@link SwarmManager#join})。殴った相手へは、殴られた一員と同じ規則で反撃させる({@link SwarmManager#onMemberHurt} に
     *     同じダメージの出どころを渡す: 役割の距離にいれば 100 tick その一員が反撃、群れの標的を替えるかは優先順で)。</li>
     *     <li>そうでなければ(群れに居ない・満員・組み直した直後)、呼んだ数で<b>新しい群れ</b>(出どころ SUMMON)を作り、
     *     殴った相手を群れの標的に指定する({@link SwarmSpawner#spawn} → {@code Swarm#commandTarget}。プレイヤーなら
     *     小規模レイドの狙いと同じ扱いで、見失い・死亡・観戦・クリエイティブで諦める)。</li>
     * </ul>
     *
     * @return 実際に置けた数
     */
    private int summonGroup(ServerLevel level, int count, DamageSource source) {
        LivingEntity threat = this.getLastHurtByMob();
        boolean validThreat = threat != null && threat.isAlive() && Predation.isInvaderTarget(this, threat);
        Swarm swarm = SwarmManager.get(this);
        if (swarm != null && swarm.kind() == Swarm.Kind.GROUND && SwarmManager.hasRoom(swarm, count)) {
            int placed = 0;
            for (int i = 0; i < count; i++) {
                ArboraEntity arbora = summonArbora(level);
                if (arbora == null) {
                    continue;
                }
                placed++;
                if (SwarmManager.join(arbora, swarm) == null) {
                    // 入れなかった(同じ tick にほかの一員が入って満員になった)。以前と同じく個体として加勢させる。
                    if (validThreat && Predation.isInvaderTarget(arbora, threat)) {
                        arbora.setTarget(threat);
                    }
                } else if (validThreat && source.getEntity() == threat) {
                    SwarmManager.onMemberHurt(arbora, source);
                }
            }
            return placed;
        }
        List<EntityType<?>> types = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            types.add(ModEntities.ARBORA.get());
        }
        SwarmSpawner.Result result = SwarmSpawner.spawn(level, this.blockPosition(), types, SwarmOrigin.SUMMON, "",
                validThreat ? threat : null);
        return result.members().size();
    }

    /** アルボラを 1 体、足元の周りの地表へ置く(ワールドへ足すところまで)。置ける場所が無ければ null。 */
    @Nullable
    private ArboraEntity summonArbora(ServerLevel level) {
        BlockPos at = this.blockPosition();
        int x = at.getX() + this.random.nextInt(SUMMON_SPREAD * 2 + 1) - SUMMON_SPREAD;
        int z = at.getZ() + this.random.nextInt(SUMMON_SPREAD * 2 + 1) - SUMMON_SPREAD;
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // 崖の上や真下の洞窟へ落とさない。
        if (Math.abs(surface - at.getY()) > 4) {
            return null;
        }
        BlockPos spot = new BlockPos(x, surface, z);
        // 体がまるごと入る場所にだけ湧かせる(地面や壁へ埋まらないように)。
        if (!SpawnFit.fits(level, ModEntities.ARBORA.get(), spot)) {
            return null;
        }
        ArboraEntity arbora = ModEntities.ARBORA.get()
                .create(level, null, spot, MobSpawnType.MOB_SUMMONED, false, false);
        if (arbora == null || !level.addFreshEntity(arbora)) {
            return null;
        }
        return arbora;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            spawnCapSpores();
        }
    }

    /**
     * 傘の縁から胞子がこぼれ落ちる。粒はヴェロフィルの航跡と同じ
     * {@link ModParticles#FINE_SPORE 細かいほう}で、初速をほぼ与えないので<b>ゆっくり落ちる</b>
     * (ユーザー要望)。クライアント側だけなので通信は発生しない。
     */
    private void spawnCapSpores() {
        if (this.isInvisible()) {
            return;
        }
        double scale = this.getScale();
        Vec3 cap = this.capPosition;
        double centerX;
        double centerZ;
        double y;
        if (cap != null) {
            centerX = cap.x;
            centerZ = cap.z;
            y = cap.y;
        } else {
            // 描画がまだ一度も来ていない間の概算。Minecraft の yaw は 0 で +Z 向きなので
            // 前方向は (-sin, 0, cos)。
            float yaw = this.yBodyRot * Mth.DEG_TO_RAD;
            centerX = this.getX() - Mth.sin(yaw) * CAP_FORWARD * scale;
            centerZ = this.getZ() + Mth.cos(yaw) * CAP_FORWARD * scale;
            y = this.getY() + CAP_HEIGHT * scale;
        }
        double radius = CAP_RADIUS * scale;

        for (int i = 0; i < CAP_SPORES_PER_TICK; i++) {
            // 傘は円盤なので、縁寄りを濃くするために平方根で散らす。
            double angle = this.random.nextDouble() * Math.PI * 2.0D;
            double r = Math.sqrt(this.random.nextDouble()) * radius;
            double px = centerX + Math.cos(angle) * r;
            double py = y - this.random.nextDouble() * 0.3D;
            double pz = centerZ + Math.sin(angle) * r;
            // 遠い粒と、細かい胞子が溜まりすぎているときは間引く(2026-09-25、全体監査 #468)。
            if (!com.yayuy.overgrownvegetation.client.ParticleThrottle.allowFineSpore(px, py, pz, this.random)) {
                continue;
            }
            this.level().addParticle(ModParticles.FINE_SPORE.get(), px, py, pz, 0.0D, -0.01D, 0.0D);
        }
    }

    /** {@code client/SporophyllRenderer} が描画のたびに、焼かれた傘の骨の位置を入れてくる。 */
    public void setCapPosition(Vec3 pos) {
        this.capPosition = pos;
    }

    @Override
    public void remove(RemovalReason reason) {
        if (this.level() instanceof ServerLevel level) {
            SporophyllAura.forget(level, this.blockPosition());
        }
        super.remove(reason);
    }

    // --- アニメーション ---

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 4, state -> {
            // 専用の attack / leap / spore アニメーションは持たない(体がフィロセンスの流用で、
            // 用意してあるのが idle / walk / run の 3 つだけ)。移動の速さだけで選ぶ。
            if (MobAnimation.isWalking(state)) {
                return state.setAndContinue(
                        MobAnimation.isFasterThan(state, 0.12D) ? RUN_ANIM : WALK_ANIM);
            }
            return state.setAndContinue(IDLE_ANIM);
        }));
    }

    // --- 音 ---

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.SCULK_CATALYST_BLOOM;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SCULK_BLOCK_BREAK;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SCULK_CATALYST_BREAK;
    }

    @Override
    protected float getSoundVolume() {
        return 0.7F;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.MOSS_STEP, 0.2F, 0.8F);
    }
}
