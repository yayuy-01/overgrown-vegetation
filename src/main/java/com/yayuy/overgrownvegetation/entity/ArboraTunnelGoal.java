package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst;
import com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter;
import com.yayuy.overgrownvegetation.phase.CorruptionCounter;
import com.yayuy.overgrownvegetation.raid.EventMobs;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import com.yayuy.overgrownvegetation.td.TdEvents;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * アルボラが<b>標的への坑道を掘る</b>(ユーザー要望 2026-09-17:
 * 「敵との間に障害があった際、ゆっくりと穴を掘っていく。蟻が仲間のために穴を掘る感じで、
 * 自分が通るためだけでなく仲間も通すために小さい洞窟を掘りながら突き進む」)。
 *
 * <h2>いつ掘るか —— 登れるなら掘らない</h2>
 * この Goal が動くのは<b>「詰まった」印が立ったとき</b>({@link AbstractOvergrownInvaderEntity#isStuck()})。
 * そこへ至るまでに本体は
 * <ol>
 *     <li>経路を引こうとして失敗し、</li>
 *     <li>{@code NO_APPROACH_LIMIT}(3 秒)のあいだ相手へまっすぐ体を押し当て、</li>
 *     <li>壁にぶつかれば {@code detectWallClimb} が成立して<b>登り</b>を試す</li>
 * </ol>
 * を済ませている。つまり<b>登って届く相手には、掘る前に登っている</b>。
 *
 * <p>それでも足りないので、<b>相手が自分より {@code arboraDigMaxTargetRise} 以上高いところに居る
 * あいだは掘らない</b> —— 壁の上の相手はブロックを壊さずに登って襲う(ユーザー指定)。
 * ただし<b>一度詰まりを繰り返した相手</b>({@link AbstractOvergrownInvaderEntity#recentStuckCount()})
 * には上へも掘る。岩に囲まれた高い空洞にいる相手へ、登るだけでは永久に届かないため。
 *
 * <h2>掘り方</h2>
 * <b>2 マス幅 × 2 マス高</b>で掘る。1 マス幅だと自分しか通れない ——
 * 仲間(スカトゥリウム 2.5 幅は無理だが、アルボラ・フィロセンス・スポロフィル)が続けるように
 * 広げてある。1 マスあたり {@code arboraDigTicksPerBlock}(既定 25 = 1.25 秒)かけるので、
 * 1 歩ぶん(4 マス)進むのに 5 秒。「ゆっくり」掘る。
 * <b>TD の侵攻中は 3 マス幅 × 3 マス高</b>(上りは 4。ユーザー指定 2026-09-25「G5 などの大型 mob も通れるように大きな穴を」)。
 *
 * <p><b>真下・真上の相手には螺旋階段を掘る</b>(ユーザー指定 2026-09-17)。直下掘りは
 * 見た目が単調なうえ、掘った穴に落ちて溶岩や空洞へ落ちる。半径
 * {@code arboraDigSpiralRadius}(既定 2)の輪を 45 度ずつ回りながら 1 マスずつ下る/上るので、
 * 1 周 8 段で 8 ブロック進む。斜めに 1 マスずつずれるだけなので歩いて降りられる。
 *
 * <h2>掘った穴は必ず汚染される</h2>
 * 1 マス抜くたびに、そのまわり {@code arboraDigCorruptRadius}(既定 2)ブロックを
 * {@link NoisyCorruptionBurst#single} で汚染する(ユーザー指定)。
 * 坑道は<b>掘り進むそばから過繁茂した岩肌になる</b> —— 蟻の巣というより菌糸の通り道になる。
 * 稼働中の胞子除去装置の圏内では汚染できないので、そこだけは素の穴が残る。
 *
 * <h2>掘らないもの</h2>
 * <ul>
 *     <li><b>壊せないブロック</b>(岩盤・バリア) —— そこで坑道を諦める。</li>
 *     <li><b>水・溶岩に面したマス</b> —— 抜いた瞬間に坑道が水没する/溶岩が流れ込む。</li>
 *     <li><b>ブロックエンティティ</b>(チェスト・かまど等) —— プレイヤーの持ち物を壊さない。</li>
 * </ul>
 * どれかに当たったら掘るのをやめ、その相手を {@code arboraDigGiveUpTicks} のあいだ諦める
 * (諦めないと、同じ岩盤を延々と叩き続ける)。
 *
 * <p>ドロップは出さない。坑道 1 本で丸石が数百個出ても邪魔なだけなので、
 * 胞子爆弾と同じく<b>壊すだけ</b>にしてある。
 */
public class ArboraTunnelGoal extends Goal {

    /** 螺旋の 1 段あたりの角度(度)。45 度 = 1 周 8 段。 */
    private static final double SPIRAL_STEP_DEGREES = 45.0D;
    /** 経路が通ったかを確かめ直す間隔(tick)。掘るたびに確かめるほどではない。 */
    private static final int REPATH_INTERVAL = 40;
    /** 掘っている向きへ体を押す速さ。 */
    private static final double CRAWL_SPEED = 0.6D;
    /** 掘った先のマスに「着いた」とみなす距離(ブロック)。 */
    private static final double ARRIVE_DISTANCE = 1.1D;
    /** 1 歩ぶん歩くのに掛けてよい時間(tick)。着けなくても次の 1 歩へ進む。 */
    private static final int STEP_TIMEOUT = 60;

    private final ArboraEntity mob;

    /** いま抜こうとしているマスの並び(手前から)。空になったら 1 歩進んで作り直す。 */
    private final List<BlockPos> plan = new ArrayList<>();
    /**
     * いま掘っている 1 歩。<b>憶えておくのが肝</b> —— 螺旋は呼ぶたびに角度が進むので、
     * 毎 tick 計算し直すと坑道が前へ走ってしまう。
     */
    private Step step;
    /** いまの 1 歩に費やした tick(歩き着けないまま固まらないための保険)。 */
    private int stepTicks;
    /** いまのマスに費やした tick。 */
    private int digTicks;
    /** これまでに抜いたマスの数。{@code arboraDigMaxBlocks} で打ち切る。 */
    private int dugBlocks;
    /** 経路を確かめ直すまでの残り tick。 */
    private int repathTimer;
    /** 螺旋を掘っているときの回転角(度)。 */
    private double spiralAngle;
    /** 螺旋の中心(水平)。掘り始めに決める。 */
    private BlockPos spiralCentre;
    /** 掘るのをやめる理由が出たら true。 */
    private boolean abandoned;
    /** 掘り始めた高さ。ここから {@code arboraDigMaxDescent} より下へは掘らない。 */
    private int startY;

    public ArboraTunnelGoal(ArboraEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    /**
     * いま掘りたい状況か。{@link InvaderUnstickGoal} もこれを見て、
     * 掘れるときは<b>回り道を譲る</b>({@link AbstractOvergrownInvaderEntity#prefersDiggingWhenStuck()})。
     */
    static boolean wantsToDig(ArboraEntity mob) {
        if (!OvergrownVegetationConfig.COMMON.arboraDigEnabled.get()) {
            return false;
        }
        if (!(mob.level() instanceof ServerLevel)) {
            return false;
        }
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        // 群れの後続は掘らない(群れ v2、2026-09-25)。後続は先導役の足跡をなぞるだけで、塞がれたら立て直しの経路を 1 本引く。
        // 掘るのは先導役と群れに居ない個体だけ(攻城の壁は TD が群れ単位で壊す)。
        if (com.yayuy.overgrownvegetation.swarm.SwarmMember.isFollower(mob)) {
            return false;
        }
        if (mob.isInWater() || mob.isClimbing() || mob.isOnCeiling()) {
            return false;
        }
        double range = OvergrownVegetationConfig.COMMON.arboraDigRange.get();
        if (mob.distanceToSqr(target) > range * range) {
            return false;
        }
        // 自分よりずっと深く潜っている相手へは掘らない(ユーザー報告 2026-09-20:
        // 「地下の敵を標的にしてずっと地面を掘り続ける」)。索敵側でも同じ足切りを
        // しているが、反撃(OvergrownHurtByTargetGoal)はそこを通らないのでここでも見る。
        if (!Predation.isWithinChaseDepth(mob, target)) {
            return false;
        }
        // 相手が上にいるなら、まずは登って届かせる(ユーザー指定)。
        // 何度も詰まっている相手にだけ、上へも掘る。
        double rise = target.getY() - mob.getY();
        return rise < OvergrownVegetationConfig.COMMON.arboraDigMaxTargetRise.get()
                || mob.recentStuckCount() >= 1;
    }

    @Override
    public boolean canUse() {
        return this.mob.isStuck() && wantsToDig(this.mob);
    }

    @Override
    public boolean canContinueToUse() {
        return !this.abandoned
                && this.dugBlocks < OvergrownVegetationConfig.COMMON.arboraDigMaxBlocks.get()
                && !tooDeep()
                && wantsToDig(this.mob);
    }

    /**
     * 掘り始めた高さから {@code arboraDigMaxDescent} より下まで潜ったか。
     *
     * <p><b>最後の歯止め</b>(2026-09-20)。索敵と {@link #wantsToDig} の両方で
     * 「深く潜っている相手は狙わない/掘らない」を見ているが、掘っている最中に相手が
     * 更に下へ降りていくと、1 歩ずつは条件を満たしたまま<b>いくらでも潜れてしまう</b>。
     * 掘り始めからの落差で切っておけば、どう転んでも青天井にはならない。
     */
    private boolean tooDeep() {
        int limit = OvergrownVegetationConfig.COMMON.arboraDigMaxDescent.get();
        return limit > 0 && this.startY - this.mob.getBlockY() > limit;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.mob.clearStuck();
        // 掘っているあいだに「辿り着けない相手」と判断されて標的を捨てられては困る。
        this.mob.clearStuckMemory();
        this.mob.getNavigation().stop();
        this.plan.clear();
        this.step = null;
        this.stepTicks = 0;
        this.digTicks = 0;
        this.dugBlocks = 0;
        this.repathTimer = REPATH_INTERVAL;
        this.spiralCentre = null;
        this.abandoned = false;
        this.startY = this.mob.getBlockY();
        this.mob.setActionState(AbstractOvergrownInvaderEntity.STATE_DIG);
    }

    @Override
    public void stop() {
        this.plan.clear();
        this.step = null;
        this.spiralCentre = null;
        if (this.mob.getActionState() == AbstractOvergrownInvaderEntity.STATE_DIG) {
            this.mob.setActionState(AbstractOvergrownInvaderEntity.STATE_IDLE);
        }
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target == null || !(this.mob.level() instanceof ServerLevel level)) {
            return;
        }
        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        // 掘っているあいだも「詰まった」と数えられないよう、状態を保ち続ける。
        this.mob.setActionState(AbstractOvergrownInvaderEntity.STATE_DIG);

        if (--this.repathTimer <= 0) {
            this.repathTimer = REPATH_INTERVAL;
            if (pathOpened(target)) {
                // 掘り抜けた(あるいは相手が出てきた)。あとは普通に歩いて詰める。
                this.abandoned = true;
                return;
            }
        }

        if (this.step == null) {
            planNextStep(level, target);
        }

        if (!this.plan.isEmpty()) {
            digOne(level, target);
            return;
        }

        // 抜き終わった。そのマスへ体を進める。着いたら次の 1 歩を組む。
        Vec3 goal = Vec3.atBottomCenterOf(this.step.standing());
        this.mob.getMoveControl().setWantedPosition(goal.x, goal.y, goal.z, CRAWL_SPEED);
        if (this.mob.position().closerThan(goal, ARRIVE_DISTANCE)
                || ++this.stepTicks > STEP_TIMEOUT) {
            this.step = null;
            this.stepTicks = 0;
        }
    }

    /** 手前の 1 マスを掘る。 */
    private void digOne(ServerLevel level, LivingEntity target) {
        BlockPos next = this.plan.get(0);
        if (isAlreadyOpen(level, next)) {
            this.plan.remove(0);
            this.digTicks = 0;
            return;
        }
        if (!canDig(level, next)) {
            abandon(target);
            return;
        }
        if (++this.digTicks < OvergrownVegetationConfig.COMMON.arboraDigTicksPerBlock.get()) {
            return;
        }
        this.digTicks = 0;
        breakAndCorrupt(level, next);
        // 自分で壁を抜いた。ナビの「届かなかった探索の控え」は地形が同じ前提なので捨てる
        // (2026-09-24 軽量化、報告 109。掘った直後の到達確認・近接の経路探索を省かないように)。
        InvaderNavigation.forgetFailedSearch(this.mob.getNavigation());
        this.plan.remove(0);
        this.dugBlocks++;
    }

    /** いま標的まで<b>届く</b>経路が引けるか。引けたら掘るのをやめてよい。 */
    private boolean pathOpened(LivingEntity target) {
        Path path = this.mob.getNavigation().createPath(target, 0);
        return path != null && path.canReach();
    }

    /** 1 歩ぶんの行き先と、そのために抜くマス。 */
    private record Step(BlockPos standing, List<BlockPos> clear) {
    }

    /** 次の 1 歩を決めて、抜くマスを並べる。 */
    private void planNextStep(ServerLevel level, LivingEntity target) {
        this.step = stepTo(target);
        this.stepTicks = 0;
        this.digTicks = 0;
        this.plan.clear();
        for (BlockPos pos : this.step.clear()) {
            if (!isAlreadyOpen(level, pos)) {
                this.plan.add(pos);
            }
        }
    }

    /**
     * 次の 1 歩。<b>真下・真上の相手には螺旋</b>、それ以外は標的の方角へまっすぐ。
     *
     * <p>掘る断面は <b>2 マス幅 × 2 マス高</b>(上りだけ 3 マス高 —— 段を上がるのに要る)。TD の侵攻中は 3 × 3(上りは 4)。
     * 幅を 2 にしてあるのは<b>仲間を通すため</b>(ユーザー指定)。
     */
    private Step stepTo(LivingEntity target) {
        BlockPos from = this.mob.blockPosition();
        double dx = target.getX() - this.mob.getX();
        double dz = target.getZ() - this.mob.getZ();
        double dy = target.getY() - this.mob.getY();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (horizontal < cfg.arboraDigSpiralThreshold.get() && Math.abs(dy) > 3.0D) {
            return spiralStep(from, dy > 0.0D ? 1 : -1);
        }
        this.spiralCentre = null;

        Direction forward = Direction.getNearest(dx, 0.0D, dz);
        int rise = dy > 2.0D ? 1 : (dy < -2.0D ? -1 : 0);
        BlockPos ahead = from.relative(forward);
        BlockPos standing = ahead.above(rise);
        Direction side = forward.getClockWise();
        // TD の侵攻中は 3 マス幅 × 3 マス高(上りは 4)。ユーザー指定 2026-09-25「自身だけが通れる穴を掘るのではなく、G5 などの大型 mob も
        // 通れるように大きな穴を」(スクティムネスが通れる 3 × 3。TD の群れの穴 td/TdSiege.SWARM_HOLE_CELLS と同じ)。
        boolean wide = siegeTunnel();

        List<BlockPos> clear = new ArrayList<>(wide ? 12 : 6);
        // 上るときは足元のマスも抜かないと段に乗れないので 3 マス高。
        int top = (rise > 0 ? 2 : 1) + (wide ? 1 : 0);
        for (int h = rise < 0 ? -1 : 0; h <= top; h++) {
            clear.add(ahead.above(h));
            clear.add(ahead.relative(side).above(h));
            if (wide) {
                clear.add(ahead.relative(side.getOpposite()).above(h));
            }
        }
        return new Step(standing, clear);
    }

    /** TD の侵攻の攻め手として掘っているか(侵攻中のイベントの印を持つ)。 */
    private boolean siegeTunnel() {
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return false;
        }
        String eventId = TdEvents.activeEventId(level);
        return eventId != null
                && TdEvents.isSiegeLive(level, TdEvents.activeTarget(level))
                && EventMobs.has(this.mob, EventMobs.Kind.TD, eventId);
    }

    /**
     * 螺旋階段を 1 段(ユーザー指定 2026-09-17「真下に目標がいたりするときは直下掘りせずに
     * らせん階段を掘る」)。
     *
     * <p>中心は掘り始めの足元。半径 {@code arboraDigSpiralRadius} の輪の上を
     * {@value #SPIRAL_STEP_DEGREES} 度ずつ回りながら、1 段につき 1 マス上下する。
     * 半径 2・45 度なら隣の段とは斜めに 1 マスずれるだけなので、そのまま歩いて降りられる。
     */
    private Step spiralStep(BlockPos from, int rise) {
        if (this.spiralCentre == null) {
            this.spiralCentre = from;
            // いまの向きから始めると、掘り始めに体をひねらずに済む。
            this.spiralAngle = this.mob.getYRot() + 90.0F;
        }
        this.spiralAngle += SPIRAL_STEP_DEGREES * rise;
        int radius = OvergrownVegetationConfig.COMMON.arboraDigSpiralRadius.get();
        double radians = Math.toRadians(this.spiralAngle);
        int x = this.spiralCentre.getX() + (int) Math.round(Math.cos(radians) * radius);
        int z = this.spiralCentre.getZ() + (int) Math.round(Math.sin(radians) * radius);
        BlockPos standing = new BlockPos(x, from.getY() + rise, z);

        List<BlockPos> clear = new ArrayList<>(4);
        // 足元と頭の 2 マス。上るときは踏み台のぶん 1 マス余計に抜く。
        int top = rise > 0 ? 2 : 1;
        for (int h = 0; h <= top; h++) {
            clear.add(standing.above(h));
        }
        // 中心側にもう 1 列 —— 螺旋の内壁を削って通り道を広げる(仲間が続けるように)。
        clear.add(inward(standing).above(0));
        clear.add(inward(standing).above(1));
        return new Step(standing, clear);
    }

    /** 螺旋の中心へ 1 マス寄った位置。 */
    private BlockPos inward(BlockPos pos) {
        int dx = Integer.signum(this.spiralCentre.getX() - pos.getX());
        int dz = Integer.signum(this.spiralCentre.getZ() - pos.getZ());
        return pos.offset(dx, 0, dz);
    }

    /** もう通れるマスか(空気・草など)。 */
    private boolean isAlreadyOpen(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced();
    }

    /**
     * 抜いてよいマスか。駄目なものが 1 つでもあれば坑道は諦める。
     * 張り付いたまま箱の壁を掘る({@link AbstractOvergrownInvaderEntity} の張り付きの掘り、2026-10-02)も同じ決まりを使う。
     */
    static boolean canDig(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getDestroySpeed(level, pos) < 0.0F) {
            // 岩盤・バリア。
            return false;
        }
        if (level.getBlockEntity(pos) != null) {
            // チェスト・かまどなど。プレイヤーの持ち物は壊さない。
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        // 水・溶岩に面したマスは抜かない —— 抜いた瞬間に坑道が水没する。
        for (Direction direction : Direction.values()) {
            if (!level.getFluidState(pos.relative(direction)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 1 マス抜いて、そのまわりを汚染する。ドロップは出さない。張り付きの掘りも使う。 */
    static void breakAndCorrupt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        level.playSound(null, pos, state.getSoundType().getBreakSound(), SoundSource.HOSTILE, 0.8F, 0.9F);
        level.levelEvent(2001, pos, net.minecraft.world.level.block.Block.getId(state));
        level.destroyBlock(pos, false);
        corruptAround(level, pos);
    }

    /**
     * 抜いたマスのまわりを汚染する(ユーザー指定:「掘った穴は必ず周囲のブロックが汚染される」)。
     * 稼働中の胞子除去装置の圏内では汚染できないので、そこだけ素の穴が残る。
     */
    private static void corruptAround(ServerLevel level, BlockPos pos) {
        int radius = OvergrownVegetationConfig.COMMON.arboraDigCorruptRadius.get();
        if (radius <= 0 || SporeDeviceData.inhibits(level, pos, SporeDeviceData.Inhibit.CORRUPTION)) {
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // 汚染の記録とバイオーム変換の打診は、マスごとではなく最後にまとめて行う(2026-09-24 軽量化、報告 739)。
        // 以前は球の 33 マスそれぞれが成功のたびに同じ 1〜2 チャンクへ打診していた。NoisyCorruptionBurst#sphere と同じ形。
        LongOpenHashSet touchedChunks = new LongOpenHashSet();
        int corrupted = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radius * radius) {
                        continue;
                    }
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (NoisyCorruptionBurst.single(level, cursor.immutable(), touchedChunks)) {
                        corrupted++;
                    }
                }
            }
        }
        CorruptionCounter.record(level, corrupted);
        for (long packedChunk : touchedChunks) {
            OvergrownBiomeConverter.maybeConvert(level, new ChunkPos(packedChunk));
        }
    }

    /** 掘れないものに当たった。その相手はしばらく諦める(同じ岩盤を叩き続けないため)。 */
    private void abandon(LivingEntity target) {
        this.abandoned = true;
        this.mob.giveUpOnTarget(target,
                OvergrownVegetationConfig.COMMON.arboraDigGiveUpTicks.get());
    }
}
