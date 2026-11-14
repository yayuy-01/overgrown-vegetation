package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.effect.PhyllosensWatchEffect;
import com.yayuy.overgrownvegetation.entity.action.ActionTiming;
import com.yayuy.overgrownvegetation.entity.forest.CoarseDetour;
import com.yayuy.overgrownvegetation.entity.stilt.OpeningSearch;
import com.yayuy.overgrownvegetation.entity.stilt.StiltSight;
import com.yayuy.overgrownvegetation.entity.tower.TowerNeck;
import com.yayuy.overgrownvegetation.registry.ModPhyllosens;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import software.bernie.geckolib.animation.RawAnimation;

import java.util.List;
import java.util.Locale;

/**
 * 監視塔フィロセンスの行動(2026-11-14)。状態: 閉じた待機 ⇄ 見張り ⇄ 覗き(木々の間・上から・横から)、叩かれる(引っ込む・起きる・胞子)、
 * 崩れる(板根 2 枚)→ スタン → 起き上がる、死ぬ。アニメは Vault「監視塔のアニメの正式な一覧」のつながりどおり(前触れ → 実行 → 隙)。
 *
 * <h2>首</h2>
 * 覗く間は首を「見たい点」へ手続きで向ける({@link TowerNeck}。アニメ {@code peek_*} の形を見本にして解く)。効き {@link #peekWeight} は前触れの間に
 * 0 → 1、隙の間に 1 → 0(アニメの前触れ・隙の動きとまぜる)。見張りで相手を見つめる間は首の 3〜8 節目をねじって鉤と花を相手へ向ける
 * (アニメの見回しと同じ動かし方。根元は回さない)。サーバーはキーのアニメを知らないので、頭の位置はアニメの数値表の姿勢で近似する。
 *
 * <h2>見える</h2>
 * 視線の決まりは竹馬型と同じ {@link StiltSight}(ガラス・柵・鉄格子は通す。固いブロックと葉は遮る)。見え続けて {@link #MARK_DELAY} で
 * 視線の印({@link PhyllosensWatchEffect})。相手はサバイバル・冒険のプレイヤーだけ。
 */
public final class PhyllosensWatch {

    public enum State { CLOSED, OPENING, WATCH, CLOSING, PEEK_IN, PEEK, PEEK_OUT, RECOIL, WAKE, COLLAPSE, STUN, RISE, DEAD }

    public enum PeekKind { GAP, TOP, SIDE }

    // ------------------------------------------------------------------ 数値【仮・Claude 11-14。竹馬型の値を監視塔に読み替え】

    /** アニメの切り替えの補間(tick)。サーバーの段の長さにも足す(記憶 geckolib-transition-delays-animation)。 */
    public static final int LEAD = 4;
    /** 見る距離・森で気づく距離(ブロック)。竹馬型の仮のまま。 */
    public static final double SIGHT_RANGE = 128.0D, NOTICE_RANGE = 32.0D;
    /** 見え始めてから印まで(tick)。Vault の決定「見え始めて 2 秒で視線の印」。 */
    public static final int MARK_DELAY = 40;
    /** 閉じてから次に見張るまで・見張りの長さ・近くに人がいると閉じてから開くまで(tick)。 */
    public static final int WATCH_EVERY = 400, WATCH_FOR = 600, CLOSED_NOTICE = 100;
    /** 覗きの長さの上限・覗いた後の休み・叩かれたら覗かない間・見えないまま待つ回数(20 tick ごと)。 */
    public static final int PEEK_FOR = 300, PEEK_COOLDOWN = 200, HIT_COOLDOWN = 200, PEEK_RETRIES = 3;
    /** 屋根の下へ隠れた相手を覚えている間(tick)。 */
    public static final int SEEN_MEMORY = 600;
    /** 屋内を覗く: 開口は首の付け根の真下から横へこの距離まで(Vault の決定「根元から約 8 ブロック」)。開口を探す半径・1 tick のマス数。 */
    public static final double PEEK_REACH = 8.5D;
    public static final int OPENING_RADIUS = 6, OPENING_CELLS = 700;
    /** 1 回に考えを進める時間の下限(ns。共通の 2 ms/tick の予算の残りがこれより少なければ次の tick)。 */
    public static final long SLICE_NANOS = 500_000L;
    /** 胞子を吹く間隔(tick)。 */
    public static final int PUFF_COOLDOWN = 30;
    /** 倒れている間(tick)。Vault「約 6 秒」= collapse_stun 3 秒 × 2。 */
    public static final int STUN_TICKS = 120;
    /** 印の相手を司令塔へ知らせ直す間隔(tick。Watchers.markSeen は半径 64 を探すので毎回は呼ばない)。 */
    public static final int CALL_REFRESH = 200;
    /** 見張りに入ってから・最後に見えてから、覗きを考え始めるまで（tick）。 */
    public static final int LOOK_SETTLE = 30;

    /** 胞子の煙【仮。G1 チビゾンビの袋の煙と同じ量】: 半径・漂う長さ・1 秒あたりの暴露量(%秒)。 */
    public static final float PUFF_HEAD_RADIUS = 2.5F, PUFF_BASE_RADIUS = 3.0F;
    public static final int PUFF_TICKS = 60;
    public static final double PUFF_DOSE = 100.0D;

    // ---- 段の長さ(tick)。アニメの秒 × 20 + 補間
    static final ActionTiming OPEN = ActionTiming.ofSeconds(LEAD, 1.8, 0, 0);
    static final ActionTiming CLOSE = ActionTiming.ofSeconds(LEAD, 0, 0, 1.6);
    static final ActionTiming[] PEEK_T = {
            ActionTiming.ofSeconds(LEAD, 1.2, PEEK_FOR / 20.0, 1.2),
            ActionTiming.ofSeconds(LEAD, 1.4, PEEK_FOR / 20.0, 1.4),
            ActionTiming.ofSeconds(LEAD, 1.6, PEEK_FOR / 20.0, 1.6)};
    static final ActionTiming RECOIL_T = ActionTiming.ofSeconds(LEAD, 0, 0.5, 1.2);
    static final ActionTiming WAKE_T = ActionTiming.ofSeconds(LEAD, 0, 1.1, 0);
    static final ActionTiming COLLAPSE_T = ActionTiming.ofSeconds(LEAD, 0, 1.3, 0);
    static final ActionTiming RISE_T = ActionTiming.ofSeconds(LEAD, 0, 0, 1.6);
    /** 吹く粒の時刻(アニメの一覧): 頭 0.14 秒・根元 0.18〜0.26 秒。 */
    static final int PUFF_HEAD_AT = 3, PUFF_BASE_AT = 4;

    // ---- アニメの姿勢の頭(萼の中心、Mob の枠: 左・上・前 ブロック。anim_numbers_r2.txt)と花の向き
    static final double[] CLOSED_HEAD = {7 / 16.0, 0.9, 4.6}, CLOSED_LOOK = {0, -0.05, 1};
    static final double[] RECOIL_HEAD = {0, 15.5, 5.1}, RECOIL_LOOK = {0, 1, 0.3};
    static final double[] DOWNED_HEAD = {29 / 16.0, 0.8, 8.0}, DOWNED_LOOK = {0, 0, 1};
    static final TowerNeck.Pose WATCH_POSE = TowerNeck.of(TowerNeck.PHYLLOSENS, TowerNeck.WATCH, 0.0D);

    private final PhyllosensEntity mob;
    private final TowerNeck.Shape sh = TowerNeck.PHYLLOSENS;

    private State state = State.CLOSED;
    private int stateT, serial, lost, tries;
    private boolean locked, visible;
    @Nullable
    private Player target;
    @Nullable
    private Vec3 lastSeen;
    private long lastSeenAt = -100000L, seenSince = -1L, coolUntil, hitUntil, nextWatch, puffReady, lastCall = -100000L;
    private int pendingHeadPuff = -1, pendingBasePuff = -1;
    @Nullable
    private Vec3 basePuffAt;
    @Nullable
    private Vec3 headPuffAt;

    // ---- 首(サーバーが決めて送る)
    private PeekKind kind = PeekKind.GAP;
    private double peekR, peekY, peekYaw;
    @Nullable
    private TowerNeck.Pose peekPose;
    @Nullable
    private Vec3 peekHead;
    private float peekW, lockW;
    private double lockYaw;
    private Vec3 look = Vec3.ZERO, headEst, lookDirEst = new Vec3(0, 0, 1);

    // ---- 探し(予算の中で続きから)
    @Nullable
    private OpeningSearch search;
    @Nullable
    private GapSearch gap;
    /** 確かめ用: 最後に分かれ道で決めたこと(GameTest の失敗の説明)。 */
    public String note = "-";
    private String lastWhy = "";

    /** 試験用: 相手(GameTest の偽プレイヤーは世界の一覧に入らないので直に渡す)。 */
    @Nullable
    public Player forcedTarget;

    // ---- 計測(1 tick の全個体の合計)
    private static long statTick = Long.MIN_VALUE, statCur;
    public static volatile long statWorst, statSum, statTicks, statCalls;

    // ---- クライアント
    @Nullable
    private TowerNeck.Pose clientPose;
    private PeekKind clientKind = PeekKind.GAP;
    private float clientR = Float.NaN, clientY = Float.NaN;

    PhyllosensWatch(PhyllosensEntity mob) {
        this.mob = mob;
    }

    // ------------------------------------------------------------------ 読み口

    public State state() {
        return this.state;
    }

    public int serial() {
        return this.serial;
    }

    public int stateTicks() {
        return this.stateT;
    }

    public boolean locked() {
        return this.locked;
    }

    public boolean seesTarget() {
        return this.visible;
    }

    public PeekKind peekKind() {
        return this.kind;
    }

    public double peekR() {
        return this.peekR;
    }

    public double peekY() {
        return this.peekY;
    }

    public double peekYaw() {
        return this.peekYaw;
    }

    public float peekWeight() {
        return this.peekW;
    }

    public float lockWeight() {
        return this.lockW;
    }

    public double lockYaw() {
        return this.lockYaw;
    }

    public Vec3 lookAt() {
        return this.look.lengthSqr() == 0.0D ? this.mob.position().add(0, 21, 0) : this.look;
    }

    @Nullable
    public Vec3 peekHeadPoint() {
        return this.peekHead;
    }

    // ------------------------------------------------------------------ tick

    void tick() {
        if (!(this.mob.level() instanceof ServerLevel level)) return;
        long t0 = System.nanoTime();
        long now = level.getGameTime();
        this.stateT++;
        if (this.headEst == null) this.headEst = tableHead(State.CLOSED);
        boolean eyesOpen = switch (this.state) {
            case WATCH, PEEK_IN, PEEK, PEEK_OUT -> true;
            default -> false;
        };
        if ((now + this.mob.getId()) % 5L == 0L) scan(level, eyesOpen);
        puffs(level);
        switch (this.state) {
            case CLOSED -> closed(now);
            case OPENING -> {
                if (this.stateT >= OPEN.total()) enter(State.WATCH);
            }
            case WATCH -> watch(level, now);
            case CLOSING -> {
                if (this.stateT >= CLOSE.total()) enter(State.CLOSED);
            }
            case PEEK_IN -> {
                ActionTiming t = PEEK_T[this.kind.ordinal()];
                this.peekW = smooth((this.stateT - t.lead()) / (float) t.windup());
                aimLook();
                if (this.stateT >= t.executeStart()) enter(State.PEEK);
            }
            case PEEK -> peek(level, now);
            case PEEK_OUT -> {
                ActionTiming t = PEEK_T[this.kind.ordinal()];
                this.peekW = 1.0F - smooth(this.stateT / (float) t.recover());
                if (this.stateT >= t.recover() + t.lead()) enter(State.WATCH);
            }
            case RECOIL -> {
                this.peekW = Math.max(0.0F, this.peekW - 0.17F);
                if (this.stateT >= RECOIL_T.total()) enter(State.WATCH);
            }
            case WAKE -> {
                if (this.stateT >= WAKE_T.total()) enter(State.WATCH);
            }
            case COLLAPSE -> {
                if (this.stateT >= COLLAPSE_T.total()) enter(State.STUN);
            }
            case STUN -> {
                if (this.stateT >= STUN_TICKS) enter(State.RISE);
            }
            case RISE -> {
                if (this.stateT >= RISE_T.total()) {
                    this.mob.restorePlates();
                    enter(State.CLOSED);
                }
            }
            case DEAD -> {
            }
        }
        // 見つめの効き(見張りで相手を見ている間)と向き
        float lw = this.state == State.WATCH && this.locked ? 1.0F : 0.0F;
        this.lockW += Mth.clamp(lw - this.lockW, -0.15F, 0.12F);
        if (this.state != State.WATCH) this.lockW = Math.max(0.0F, this.lockW - 0.1F);
        if (this.state != State.PEEK_IN && this.state != State.PEEK && this.state != State.PEEK_OUT && this.state != State.RECOIL) {
            this.peekW = Math.max(0.0F, this.peekW - 0.15F);
        }
        updateHead();
        record(now, System.nanoTime() - t0);
    }

    private static float smooth(float x) {
        float t = Mth.clamp(x, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    private static void record(long gameTime, long ns) {
        if (gameTime != statTick) {
            if (statTick != Long.MIN_VALUE) {
                statWorst = Math.max(statWorst, statCur);
                statSum += statCur;
                statTicks++;
            }
            statTick = gameTime;
            statCur = 0L;
        }
        statCur += ns;
        statCalls++;
    }

    public static void resetStats() {
        statWorst = statSum = statTicks = statCalls = 0L;
        statTick = Long.MIN_VALUE;
        statCur = 0L;
    }

    void enter(State s) {
        this.state = s;
        this.stateT = 0;
        this.serial++;
        this.lost = 0;
        this.tries = 0;
        switch (s) {
            case CLOSED -> {
                this.locked = false;
                this.nextWatch = this.mob.level().getGameTime() + WATCH_EVERY;
                this.search = null;
                this.gap = null;
            }
            case OPENING, WAKE -> this.seenSince = -1L;
            case WATCH -> {
                this.locked = false;
                this.seenSince = -1L;
            }
            case CLOSING, COLLAPSE -> {
                this.locked = false;
                this.search = null;
                this.gap = null;
            }
            case PEEK_IN -> this.seenSince = -1L;
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ 相手

    /** 相手になれるプレイヤー(サバイバル・冒険。創造・観戦は見ない)。 */
    static boolean watchable(Player pl) {
        return pl.isAlive() && !pl.isCreative() && !pl.isSpectator();
    }

    private void scan(ServerLevel level, boolean eyesOpen) {
        double r = SIGHT_RANGE;
        List<Player> list = this.forcedTarget != null ? List.of(this.forcedTarget)
                : level.getEntitiesOfClass(Player.class, this.mob.getBoundingBox().inflate(r, r, r), PhyllosensWatch::watchable);
        Player best = null;
        double bd = r * r;
        for (Player pl : list) {
            if (!watchable(pl)) continue;
            double d = pl.distanceToSqr(this.mob);
            if (d <= bd) {
                bd = d;
                best = pl;
            }
        }
        this.target = best;
        boolean was = this.visible;
        this.visible = eyesOpen && best != null && StiltSight.canSee(level, eye(), best.getEyePosition());
        long now = level.getGameTime();
        if (this.visible) {
            if (!was || this.seenSince < 0L) this.seenSince = now;
            this.lastSeen = best.position();
            this.lastSeenAt = now;
            // 画面の縁: 視線が通った瞬間 0 → 印の 2 秒で 1 → 見られている間 1(見られている本人だけへ)
            if (best instanceof net.minecraft.server.level.ServerPlayer sp) {
                float s = Mth.clamp((now - this.seenSince) / (float) MARK_DELAY, 0.0F, 1.0F);
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, new com.yayuy.overgrownvegetation.network.PhyllosensGazePayload(Math.max(0.02F, s)));
            }
        } else {
            this.seenSince = -1L;
        }
    }

    /** 見えている相手に印を付ける(見え続けて MARK_DELAY から。付いた後も見えている間は付け直す)。 */
    private void markIfSeen(long now) {
        Player t = this.target;
        if (!this.visible || t == null || this.seenSince < 0L || now - this.seenSince < MARK_DELAY) return;
        boolean had = t.hasEffect(ModPhyllosens.PHYLLOSENS_WATCH);
        PhyllosensWatchEffect.mark(t);
        if (!had || now - this.lastCall >= CALL_REFRESH) {
            this.lastCall = now;
            PhyllosensCall.seen(this.mob, t);
        }
    }

    /** 相手の頭の上に屋根があるか(葉を除く)。 */
    private static boolean coverOver(ServerLevel level, Vec3 at) {
        return !OpeningSearch.skyOpen(level, BlockPos.containing(at.x, at.y + 1.6D, at.z));
    }

    // ------------------------------------------------------------------ 閉じた待機

    private void closed(long now) {
        if (this.devHold) return;
        Player t = this.target;
        if (t == null) return;
        double d = t.distanceTo(this.mob);
        if (now >= this.nextWatch || (d <= NOTICE_RANGE && this.stateT >= CLOSED_NOTICE)) enter(State.OPENING);
    }

    // ------------------------------------------------------------------ 見張り

    private void watch(ServerLevel level, long now) {
        if (this.devHold) {
            if (this.lockW > 0.0F || this.locked) this.locked = this.devLock;
            return;
        }
        Player t = this.target;
        if (this.visible && t != null) {
            this.locked = true;
            this.lost = 0;
            this.search = null;
            this.gap = null;
            this.look = t.getEyePosition();
            double[] f = TowerNeck.toFile(t.getEyePosition(), this.mob.position(), this.mob.getYRot());
            double want = TowerNeck.fileToPlane(this.sh, f)[2];
            this.lockYaw += Mth.clamp(Mth.wrapDegrees(want - this.lockYaw), -6.0D, 6.0D);
            markIfSeen(now);
        } else if (this.locked && ++this.lost > 40) {
            this.locked = false;
        }
        if (!this.locked) this.lockYaw += Mth.clamp(-this.lockYaw, -3.0D, 3.0D);
        // 見えないが近くにいる: 首の届く所から覗く
        // 見張りに入ってすぐ（花がまだ上がりきらない・見えるかをまだ測っていない）と、さっきまで見えていた間は覗かない
        if (!this.visible && t != null && now >= this.coolUntil && now >= this.hitUntil && this.stateT >= LOOK_SETTLE && now - this.lastSeenAt >= LOOK_SETTLE) {
            if (this.search != null || this.gap != null) {
                continueSearch(level, t);
                return;
            }
            boolean covered = coverOver(level, t.position());
            double flat = Math.sqrt(sq(t.getX() - this.mob.getX()) + sq(t.getZ() - this.mob.getZ()));
            if (covered && this.lastSeen != null && now - this.lastSeenAt <= SEEN_MEMORY && flat <= PEEK_REACH + OPENING_RADIUS) {
                startOpeningSearch();
                return;
            }
            if (!covered && t.distanceTo(this.mob) <= NOTICE_RANGE) {
                this.gap = new GapSearch(t.getEyePosition());
                this.note = "木々の間を探す";
                return;
            }
        }
        if (this.stateT > WATCH_FOR && !this.locked && this.search == null && this.gap == null) enter(State.CLOSING);
    }

    private static double sq(double x) {
        return x * x;
    }

    private void startOpeningSearch() {
        Vec3 base = this.mob.position();
        int ground = Mth.floor(base.y);
        this.search = new OpeningSearch(BlockPos.containing(this.lastSeen), OPENING_RADIUS, c -> {
            double dx = c.getX() + 0.5D - base.x, dz = c.getZ() + 0.5D - base.z;
            return dx * dx + dz * dz <= PEEK_REACH * PEEK_REACH && c.getY() >= ground - 1 && c.getY() <= ground + 9;
        });
        this.note = "開口を探す";
    }

    private void continueSearch(ServerLevel level, Player t) {
        if (CoarseDetour.remaining(level) < SLICE_NANOS) return;   // 共通の予算(2 ms/tick)が足りなければ次の tick
        long s0 = System.nanoTime();
        try {
            if (this.search != null) {
                Vec3 eye = this.lastSeen != null && t.distanceToSqr(this.lastSeen) < 9.0D ? t.getEyePosition() : (this.lastSeen != null ? this.lastSeen.add(0, 1.6D, 0) : t.getEyePosition());
                OpeningSearch.Result res = this.search.step(level, eye, this.mob.position(), OPENING_CELLS);
                if (res == null) return;
                this.search = null;
                if (res.cell() == null) {
                    this.note = "開口なし";
                    this.coolUntil = level.getGameTime() + PEEK_COOLDOWN;
                    return;
                }
                if (!openingHead(level, res, eye)) {
                    this.note = "開口 " + res.cell().toShortString() + " に頭が届かない" + this.lastWhy;
                    this.coolUntil = level.getGameTime() + PEEK_COOLDOWN;
                }
                return;
            }
            if (this.gap != null) {
                Vec3 h = this.gap.step(level, s0);
                if (h == null && !this.gap.done()) return;
                GapSearch g = this.gap;
                this.gap = null;
                if (h == null) {
                    this.note = "木々の間に覗ける所が無い(" + g.tried + ")";
                    this.coolUntil = level.getGameTime() + PEEK_COOLDOWN;
                    return;
                }
                startPeek(g.kind, g.pose, h, g.eye);
            }
        } finally {
            CoarseDetour.spend(level, System.nanoTime() - s0);
        }
    }

    // ------------------------------------------------------------------ 覗きの頭の置き場

    /** 首が通れないマス(葉は通る: 林冠を押し分けて下りる。固いブロックは通らない)。 */
    static boolean neckBlocks(Level level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.isAir() || s.is(BlockTags.LEAVES) || s.getBlock() instanceof com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock) return false;
        return !s.getCollisionShape(level, p).isEmpty();
    }

    /** 首の節が全部通れるか(付け根から頭まで、節ごとの線)。 */
    private boolean neckClear(Level level, TowerNeck.Pose p) {
        Vec3 pos = this.mob.position();
        float yaw = this.mob.getYRot();
        Vec3 prev = TowerNeck.toWorld(TowerNeck.jointFile(this.sh, p, 1), pos, yaw);
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int k = 2; k <= this.sh.segments(); k++) {
            Vec3 j = TowerNeck.toWorld(TowerNeck.jointFile(this.sh, p, k), pos, yaw);
            // 節の線を 0.5 ブロックおきに
            double len = j.distanceTo(prev);
            int n = Math.max(1, (int) Math.ceil(len / 0.5D));
            for (int i = 1; i <= n; i++) {
                Vec3 a = prev.lerp(j, i / (double) n);
                if (neckBlocks(level, q.set(Mth.floor(a.x), Mth.floor(a.y), Mth.floor(a.z)))) return false;
            }
            if (j.y < pos.y + 0.2D) return false;   // 地面に刺さる
            prev = j;
        }
        return true;
    }

    /** 塔の幹(根元 半径 2.15 → 頂 0.9 ブロック)に首が刺さっていないか(付け根に近い 3 節は塔の上に出るので見ない)。 */
    private boolean clearOfTower(TowerNeck.Pose p) {
        for (int k = 3; k <= this.sh.segments(); k++) {
            double[] j = TowerNeck.jointFile(this.sh, p, k);
            double h = j[1] / 16.0D, flat = Math.sqrt(j[0] * j[0] + j[2] * j[2]) / 16.0D;
            if (h > 12.6D) continue;
            double rad = Mth.lerp(Mth.clamp(h / 12.6D, 0.0D, 1.0D), 2.15D, 0.9D) + 0.25D;
            if (flat < rad) return false;
        }
        return true;
    }

    /** 頭の軸 h(ワールド)へ首を解く。届かない・刺さるなら null。 */
    @Nullable
    TowerNeck.Pose solveTo(Vec3 h, PeekKind k) {
        double[] f = TowerNeck.toFile(h, this.mob.position(), this.mob.getYRot());
        double[] pl = TowerNeck.fileToPlane(this.sh, f);
        double r = pl[0], y = pl[1], yaw = pl[2];
        TowerNeck.Pose p = TowerNeck.solve(this.sh, seed(k), r, y, yaw);
        if (p.miss() > 8.0D) return null;   // 0.5 ブロックより外れる(届かない)
        return p;
    }

    static double[] seed(PeekKind k) {
        return switch (k) {
            case GAP -> TowerNeck.PEEK_GAP;
            case TOP -> TowerNeck.PEEK_TOP;
            case SIDE -> TowerNeck.PEEK_SIDE;
        };
    }

    /** 頭の軸 h から相手の目が「しっかり」見えるか(花の芯から 3 本。竹馬型と同じ)。 */
    private static boolean seesWell(Level level, Vec3 h, Vec3 eye) {
        Vec3 d = eye.subtract(h).normalize();
        Vec3 e = h.add(d.scale(0.44D));
        Vec3 side = new Vec3(-d.z, 0, d.x);
        if (side.lengthSqr() < 1.0E-6D) side = new Vec3(1, 0, 0);
        side = side.normalize().scale(0.2D);
        return StiltSight.canSee(level, e, eye) && StiltSight.canSee(level, e.add(side), eye) && StiltSight.canSee(level, e.subtract(side), eye);
    }

    /** 首の付け根の真下からの横の距離(ブロック)。 */
    private double flatFromAxis(Vec3 p) {
        return Math.sqrt(sq(p.x - this.mob.getX()) + sq(p.z - this.mob.getZ()));
    }

    /** 開口の外に頭を置く: 外への距離を試し、首が届き・通り・開口ごしに見える一番外。 */
    private boolean openingHead(ServerLevel level, OpeningSearch.Result res, Vec3 eye) {
        Vec3 c = Vec3.atCenterOf(res.cell());
        Vec3 d = new Vec3(res.dir().getStepX(), 0, res.dir().getStepZ());
        boolean solid = !level.getBlockState(res.cell()).isAir();   // ガラス・柵の窓: 頭は外に止める
        double[] outs = solid ? new double[]{2.8D, 2.5D, 2.2D, 1.9D, 1.6D} : new double[]{2.2D, 1.9D, 1.6D, 1.3D, 1.0D};
        StringBuilder why = new StringBuilder();
        for (double out : outs) {
            Vec3 h = c.add(d.scale(out));
            PeekKind k = flatFromAxis(h) <= 4.5D ? PeekKind.TOP : PeekKind.SIDE;
            TowerNeck.Pose p = solveTo(h, k);
            if (p == null) {
                why.append(" 届かず");
                continue;
            }
            Vec3 got = headOf(p);
            if (StiltSight.blocks(level, BlockPos.containing(got))) {
                why.append(" 頭が埋まる");
                continue;
            }
            if (!neckClear(level, p)) {
                why.append(" 首が通らない");
                continue;
            }
            if (!clearOfTower(p)) {
                why.append(" 塔に刺さる");
                continue;
            }
            if (!StiltSight.canSee(level, got.add(eye.subtract(got).normalize().scale(0.44D)), eye)) {
                why.append(" 見えない");
                continue;
            }
            this.note = String.format(Locale.ROOT, "開口 %s を%s覗く(外へ %.1f)", res.cell().toShortString(), k == PeekKind.TOP ? "上から" : "横から", out);
            startPeek(k, p, got, eye);
            return true;
        }
        this.lastWhy = why.toString();
        return false;
    }

    /** 解いた首の頭の軸(ワールド)。首の付け根の x のずれ(0)も入れる。 */
    Vec3 headOf(TowerNeck.Pose p) {
        return TowerNeck.headWorld(this.sh, p, this.mob.position(), this.mob.getYRot());
    }

    /** 木々の間から覗く頭の置き場を、予算の中で続きから試す。 */
    final class GapSearch {
        final Vec3 eye;
        int index, tried;
        PeekKind kind = PeekKind.GAP;
        TowerNeck.Pose pose;
        static final double[] DIST = {6.0D, 8.0D, 4.0D, 10.0D};
        static final double[] HEIGHT = {9.0D, 6.0D, 11.0D, 4.0D, 2.5D};
        static final double[] TURN = {0.0D, 20.0D, -20.0D, 40.0D, -40.0D};

        GapSearch(Vec3 eye) {
            this.eye = eye;
        }

        boolean done() {
            return this.index >= DIST.length * HEIGHT.length * TURN.length;
        }

        @Nullable
        Vec3 step(ServerLevel level, long s0) {
            Vec3 pos = PhyllosensWatch.this.mob.position();
            double bear = Math.atan2(this.eye.z - pos.z, this.eye.x - pos.x);
            while (!done()) {
                if (System.nanoTime() - s0 > SLICE_NANOS) return null;
                int i = this.index++;
                double dist = DIST[i % DIST.length], hgt = HEIGHT[(i / DIST.length) % HEIGHT.length];
                double turn = Math.toRadians(TURN[i / (DIST.length * HEIGHT.length)]);
                double flatToEye = Math.sqrt(sq(this.eye.x - pos.x) + sq(this.eye.z - pos.z));
                if (dist > flatToEye - 1.0D) continue;   // 相手より向こうへは出さない
                Vec3 h = new Vec3(pos.x + Math.cos(bear + turn) * dist, pos.y + hgt, pos.z + Math.sin(bear + turn) * dist);
                this.tried++;
                PeekKind k = hgt >= 6.0D ? PeekKind.GAP : (dist <= 4.5D ? PeekKind.TOP : PeekKind.SIDE);
                if (StiltSight.blocks(level, BlockPos.containing(h))) continue;
                TowerNeck.Pose p = solveTo(h, k);
                if (p == null) continue;
                Vec3 got = headOf(p);
                if (StiltSight.blocks(level, BlockPos.containing(got)) || !clearOfTower(p) || !neckClear(level, p)) continue;
                if (!seesWell(level, got, this.eye)) continue;
                this.kind = k;
                this.pose = p;
                PhyllosensWatch.this.note = String.format(Locale.ROOT, "木々の間から覗く(%s・横 %.0f 高さ %.0f 回し %.0f)", k, dist, hgt, Math.toDegrees(turn));
                return got;
            }
            return null;
        }
    }

    private void startPeek(PeekKind k, TowerNeck.Pose p, Vec3 head, Vec3 eye) {
        this.kind = k;
        this.peekPose = p;
        this.peekR = p.tipR();
        this.peekY = p.tipY();
        this.peekYaw = p.yaw();
        this.peekHead = head;
        this.look = eye;
        this.search = null;
        this.gap = null;
        enter(State.PEEK_IN);
    }

    // ------------------------------------------------------------------ 覗く

    private void aimLook() {
        Player t = this.target;
        if (t != null && (this.visible || this.peekW < 0.9F) && t.distanceTo(this.mob) < NOTICE_RANGE * 1.5D) this.look = t.getEyePosition();
    }

    private void peek(ServerLevel level, long now) {
        if (this.devHold) {
            this.peekW = 1.0F;
            return;
        }
        ActionTiming tm = PEEK_T[this.kind.ordinal()];
        this.peekW = 1.0F;
        Player t = this.target;
        aimLook();
        if (t == null || t.distanceTo(this.mob) > NOTICE_RANGE * 1.5D || this.stateT >= tm.execute()) {
            leavePeek(now);
            return;
        }
        if (this.visible) {
            this.tries = 0;
            markIfSeen(now);
        } else if (this.stateT % 20 == 0 && ++this.tries > PEEK_RETRIES) {
            this.note = "隠れ直された";
            leavePeek(now);
        }
    }

    private void leavePeek(long now) {
        this.coolUntil = now + PEEK_COOLDOWN;
        enter(State.PEEK_OUT);
    }

    // ------------------------------------------------------------------ 叩かれる

    /** 頭を叩かれた(本体の体力が減った後)。 */
    void onHeadHit(DamageSource src) {
        long now = this.mob.level().getGameTime();
        switch (this.state) {
            case PEEK_IN, PEEK -> {
                // 覗き中: 胞子を吹いて引っ込む(hit_recoil + spore_puff_head を重ねる)。しばらく覗かない
                this.hitUntil = now + HIT_COOLDOWN;
                this.coolUntil = Math.max(this.coolUntil, this.hitUntil);
                puffHead(now, true);
                enter(State.RECOIL);
            }
            case CLOSED -> {
                // 閉じている間(頭は根元の地面): 胞子を吹いて起き、見張る(hit_wake。吹く動きはアニメに入っている)
                this.pendingHeadPuff = LEAD + PUFF_HEAD_AT;
                this.headPuffAt = this.headEst.add(this.lookDirEst.scale(0.6D));
                this.puffReady = now + PUFF_COOLDOWN;
                enter(State.WAKE);
            }
            default -> puffHead(now, false);
        }
    }

    /** 板根を叩かれた(折れたら broke)。根元から胞子を吹く。2 枚折れたら腰から崩れる。 */
    void onPlateHit(int i, boolean broke, DamageSource src) {
        long now = this.mob.level().getGameTime();
        if (now >= this.puffReady && this.state != State.DEAD) {
            this.puffReady = now + PUFF_COOLDOWN;
            this.mob.triggerAnim(PhyllosensEntity.PUFF, "base");
            this.pendingBasePuff = PUFF_BASE_AT;
            Vec3 p = this.mob.platePos(i);
            Vec3 c = this.mob.position();
            Vec3 d = new Vec3(p.x - c.x, 0, p.z - c.z).normalize();
            this.basePuffAt = c.add(d.scale(1.8D)).add(0, 0.1D, 0);
        }
        if (broke && this.mob.brokenPlates() >= 2) {
            switch (this.state) {
                case COLLAPSE, STUN, RISE, DEAD -> {
                }
                default -> {
                    this.note = "腰から崩れる";
                    enter(State.COLLAPSE);
                }
            }
        }
    }

    private void puffHead(long now, boolean force) {
        if (!force && now < this.puffReady) return;
        this.puffReady = now + PUFF_COOLDOWN;
        this.headPuffAt = this.headEst.add(this.lookDirEst.scale(0.6D));
        this.mob.triggerAnim(PhyllosensEntity.PUFF, "head");
        this.pendingHeadPuff = PUFF_HEAD_AT;
    }

    /** 吹く粒の時刻が来たら煙を出す(暴露量 + 視界の霞み。ダメージ無し・防護マスクで防げる)。 */
    private void puffs(ServerLevel level) {
        if (this.pendingHeadPuff >= 0 && this.pendingHeadPuff-- == 0) {
            // 叩かれた時の頭の所から（引っ込み始めた頭の今の所ではない）。煙の円柱の底は頭の下へ半径ぶん、地面より下へは出さない
            Vec3 h = this.headPuffAt != null ? this.headPuffAt : this.headEst.add(this.lookDirEst.scale(0.6D));
            Vec3 at = new Vec3(h.x, Math.max(this.mob.getY(), h.y - PUFF_HEAD_RADIUS), h.z);
            this.headPuffAt = null;
            PhyllosensSpores.puff(level, this.mob, at, PUFF_HEAD_RADIUS);
        }
        if (this.pendingBasePuff >= 0 && this.pendingBasePuff-- == 0 && this.basePuffAt != null) {
            PhyllosensSpores.puff(level, this.mob, this.basePuffAt, PUFF_BASE_RADIUS);
        }
    }

    void onDeath() {
        enter(State.DEAD);
        if (this.mob.level() instanceof ServerLevel level) PhyllosensCall.cut(this.mob, level);
    }

    // ------------------------------------------------------------------ 頭の位置(サーバーの近似)

    private Vec3 frame(double[] lup) {
        return TowerNeck.toWorld(new double[]{lup[0] * 16.0D, lup[1] * 16.0D, -lup[2] * 16.0D}, this.mob.position(), this.mob.getYRot());
    }

    private Vec3 frameDir(double[] lup) {
        return frame(lup).subtract(this.mob.position()).normalize();
    }

    private Vec3 tableHead(State s) {
        return switch (s) {
            case CLOSED, CLOSING, RISE, WAKE -> frame(CLOSED_HEAD);
            case RECOIL -> frame(RECOIL_HEAD);
            case COLLAPSE, STUN, DEAD -> frame(DOWNED_HEAD);
            default -> headOf(WATCH_POSE.withYaw(this.lockYaw * this.lockW));
        };
    }

    private Vec3 tableLook(State s) {
        return switch (s) {
            case CLOSED, CLOSING, RISE -> frameDir(CLOSED_LOOK);
            case RECOIL -> frameDir(RECOIL_LOOK);
            case COLLAPSE, STUN, DEAD -> frameDir(DOWNED_LOOK);
            default -> {
                Vec3 h = headOf(WATCH_POSE.withYaw(this.lockYaw * this.lockW));
                Vec3 fwd = Vec3.directionFromRotation(20.0F, this.mob.getYRot() + (float) (this.lockYaw * this.lockW));
                yield this.lockW > 0.0F && this.look.lengthSqr() > 0.0D ? this.look.subtract(h).normalize() : fwd;
            }
        };
    }

    /** 状態の頭の位置へ寄せる(アニメの動きに近い速さで)。覗きは解いた首の頭とまぜる。 */
    private void updateHead() {
        // 叩かれて起きる(hit_wake): 前半は地面の頭、後半は見張りの頭
        State s = this.state == State.WAKE && this.stateT > WAKE_T.total() / 2 ? State.WATCH
                : this.state == State.OPENING && this.stateT > OPEN.total() / 2 ? State.WATCH
                : this.state == State.CLOSING && this.stateT < CLOSE.total() / 2 ? State.WATCH : this.state;
        Vec3 want = tableHead(s);
        Vec3 dir = tableLook(s);
        if (this.peekPose != null && this.peekW > 0.0F) {
            want = want.lerp(this.peekHead, this.peekW);
            Vec3 ld = this.look.subtract(this.peekHead);
            if (ld.lengthSqr() > 1.0E-6D) dir = dir.lerp(ld.normalize(), this.peekW).normalize();
        }
        Vec3 d = want.subtract(this.headEst);
        double l = d.length(), max = this.peekW > 0.0F && this.peekW < 1.0F ? 1.5D : 1.0D;
        this.headEst = l <= max ? want : this.headEst.add(d.scale(max / l));
        this.lookDirEst = dir;
    }

    /** 目(花の芯)の位置(ワールド)。 */
    public Vec3 eye() {
        Vec3 h = this.headEst != null ? this.headEst : tableHead(this.state);
        return h.add(this.lookDirEst.scale(0.44D));
    }

    /** 叩ける頭の中心(ワールド)。閉じた・倒れたつぼみは地面に寝ているので、萼から花の向きへ少し出す。 */
    Vec3 headPartCenter() {
        if (this.mob.level().isClientSide()) return clientHeadCenter();
        Vec3 h = this.headEst != null ? this.headEst : tableHead(this.state);
        return h.add(this.lookDirEst.scale(0.6D));
    }

    // ------------------------------------------------------------------ 描画のアニメ

    static final RawAnimation A_CLOSED = RawAnimation.begin().thenLoop("idle_closed");
    static final RawAnimation A_OPEN = RawAnimation.begin().thenPlay("watch_windup").thenLoop("watch");
    static final RawAnimation A_WATCH = RawAnimation.begin().thenLoop("watch");
    static final RawAnimation A_LOCK = RawAnimation.begin().thenPlayAndHold("watch_lock");
    static final RawAnimation A_CLOSE = RawAnimation.begin().thenPlay("watch_recover").thenLoop("idle_closed");
    static final RawAnimation[] A_PEEK = new RawAnimation[3], A_PEEK_OUT = new RawAnimation[3];
    static final RawAnimation A_RECOIL = RawAnimation.begin().thenPlay("hit_recoil").thenPlay("hit_recoil_recover").thenLoop("watch");
    static final RawAnimation A_WAKE = RawAnimation.begin().thenPlay("hit_wake").thenLoop("watch");
    static final RawAnimation A_COLLAPSE = RawAnimation.begin().thenPlay("collapse").thenLoop("collapse_stun");
    static final RawAnimation A_RISE = RawAnimation.begin().thenPlay("collapse_recover").thenLoop("idle_closed");
    static final RawAnimation A_DEATH = RawAnimation.begin().thenPlayAndHold("death");

    static {
        String[] n = {"peek_gap", "peek_top", "peek_side"};
        for (int i = 0; i < 3; i++) {
            A_PEEK[i] = RawAnimation.begin().thenPlay(n[i] + "_windup").thenLoop(n[i]);
            A_PEEK_OUT[i] = RawAnimation.begin().thenPlay(n[i] + "_recover").thenLoop("watch");
        }
    }

    public static RawAnimation animation(State s, PeekKind k, boolean locked) {
        return switch (s) {
            case CLOSED -> A_CLOSED;
            case OPENING -> A_OPEN;
            case WATCH -> locked ? A_LOCK : A_WATCH;
            case CLOSING -> A_CLOSE;
            case PEEK_IN, PEEK -> A_PEEK[k.ordinal()];
            case PEEK_OUT -> A_PEEK_OUT[k.ordinal()];
            case RECOIL -> A_RECOIL;
            case WAKE -> A_WAKE;
            case COLLAPSE, STUN -> A_COLLAPSE;
            case RISE -> A_RISE;
            case DEAD -> A_DEATH;
        };
    }

    /** 繰り返しだけのアニメの状態(今そのアニメが流れていれば、入り直しても続ける)。 */
    public static boolean keepsLoop(State s) {
        return s == State.WATCH || s == State.CLOSED;
    }

    public static String loopName(State s, PeekKind k) {
        return s == State.CLOSED ? "idle_closed" : "watch";
    }

    // ------------------------------------------------------------------ クライアント

    void clientTick(State s, float peekW, float lockW, float lockYaw, PeekKind k, Vector3f neck, Vec3 lookWorld) {
        this.state = s;
        this.peekW = peekW;
        this.lockW = lockW;
        this.lockYaw = lockYaw;
        this.look = lookWorld;
        if (peekW > 0.0F && (k != this.clientKind || Math.abs(neck.x() - this.clientR) > 0.01F || Math.abs(neck.y() - this.clientY) > 0.01F
                || this.clientPose == null)) {
            this.clientKind = k;
            this.clientR = neck.x();
            this.clientY = neck.y();
            this.clientPose = TowerNeck.solve(this.sh, seed(k), neck.x(), neck.y(), neck.z());
        } else if (this.clientPose != null && Math.abs(this.clientPose.yaw() - neck.z()) > 1.0E-3D) {
            this.clientPose = this.clientPose.withYaw(neck.z());
        }
    }

    /** 描画: 覗きの首(効きが 0 なら null)。 */
    @Nullable
    public TowerNeck.Pose clientPose() {
        return this.clientPose;
    }

    private Vec3 clientHeadCenter() {
        if (this.clientPose != null && this.peekW > 0.5F) {
            Vec3 h = headOf(this.clientPose);
            return h.add(this.look.subtract(h).normalize().scale(0.6D));
        }
        Vec3 h = tableHead(this.state);
        return h.add(tableLook(this.state).scale(0.6D));
    }

    // ------------------------------------------------------------------ 試験用

    /** 確かめ用(テストクライアントの towerdev): 行動が状態を変えない(時間で進む段だけ進む)。 */
    public boolean devHold, devLock;

    /** 確かめ用: 状態へ入る。 */
    public void devState(State s) {
        enter(s);
    }

    /** 確かめ用: 見張りで lookAt を見つめる。 */
    public void devLockAt(Vec3 at) {
        this.devHold = true;
        this.devLock = true;
        this.locked = true;
        this.look = at;
        double[] f = TowerNeck.toFile(at, this.mob.position(), this.mob.getYRot());
        this.lockYaw = TowerNeck.fileToPlane(this.sh, f)[2];
        if (this.state != State.WATCH) enter(State.WATCH);
        this.locked = true;
    }

    /** 確かめ用: 頭の軸を head(ワールド)へ置いて look を見る覗き(届かなければ false)。 */
    public boolean devPeek(PeekKind k, Vec3 head, Vec3 look) {
        TowerNeck.Pose p = solveTo(head, k);
        if (p == null) return false;
        this.devHold = true;
        startPeek(k, p, headOf(p), look);
        return true;
    }

    /** 試験用: すぐ見張りに入る。 */
    public void forceWatch() {
        enter(State.WATCH);
    }

    /** 試験用: 相手を最後に見た所と時刻を入れる(屋根の下へ隠れた相手を覗く場面)。 */
    public void noteSeen(Vec3 at, long now) {
        this.lastSeen = at;
        this.lastSeenAt = now;
    }

    /** 試験用: 板根 i を折る(i の体力を 0 に)。 */
    public void breakPlateForTests(int i) {
        this.mob.plateHp[i] = 0.0F;
        onPlateHit(i, true, this.mob.damageSources().generic());
    }
}
