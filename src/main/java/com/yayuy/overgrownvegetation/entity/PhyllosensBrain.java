package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.effect.PhyllosensWatchEffect;
import com.yayuy.overgrownvegetation.entity.action.ActionTiming;
import com.yayuy.overgrownvegetation.entity.stilt.StiltNeck;
import com.yayuy.overgrownvegetation.entity.stilt.StiltParams;
import com.yayuy.overgrownvegetation.entity.stilt.StiltPlanner;
import com.yayuy.overgrownvegetation.entity.stilt.StiltSight;
import com.yayuy.overgrownvegetation.entity.stilt.StiltWalker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * フィロセンスの「見る」行動(2026-11-14、竹馬型 段 2「首の動き」)。サーバーだけ。仕様の正本は Vault「Mob の能力/フィロセンス」の
 * 「覗き込み」と段 2 の節。
 *
 * <ul>
 *   <li><b>歩く</b>: かがんだ・頭の上が塞がっているときは、首を前・下へ逃がす(葉に埋めない)。</li>
 *   <li><b>枯れ木に化ける</b>: 止まって {@link StiltParams#disguiseAfter} tick で、足を寄せて踏み直し、{@code idle_disguise} の形に。</li>
 *   <li><b>見張り</b>: 化けて {@link StiltParams#watchEvery} tick、近く({@link StiltParams#sightRange})にサバイバルの人がいれば、
 *       まわりの開けた所へ歩いて {@code watch_open} → {@code watch_sweep}。見えたら {@code watch_lock} で首を向け、見え続けたら印。</li>
 *   <li><b>森で覗く</b>: {@link StiltParams#noticeRange} 以内の人に気づくと止まり、首の届く範囲から相手が見える頭の置き場を探して首を曲げる
 *       (幹を回り込む)。見え続けて {@link StiltParams#markDelay} で印。その前に幹の陰へ隠れ直されると、探し直し → あきらめる。</li>
 *   <li><b>開口を覗く</b>: 最後に見た相手が屋根の下へ隠れたら、その近くの開口(入口・窓・穴)を探し、外に立って胴を下ろし、前へ倒し、
 *       つぼみのまま頭を開口へ寄せて中を見る。見えたら印。</li>
 *   <li><b>頭を叩かれる</b>: 覗いている間に頭を叩かれると引っ込め、{@link StiltParams#headHitCooldown} の間覗かない。</li>
 * </ul>
 * 覗きは前触れ(首が出る・胴が下りる)→ 実行(見る・印)→ 隙(引っ込める)の 3 段({@link ActionTiming})。
 */
public final class PhyllosensBrain {

    public enum Mode { WALK, DISGUISE, WATCH_GO, WATCH, PEEK, PEER_GO, PEER, RETRACT }

    /** 覗きの 3 段: 前触れ 30・実行(上限)・隙 30 tick【仮】。実行の長さは見張り・覗きの長さで決める。 */
    static final ActionTiming PEEK_TIMING = new ActionTiming(0, 30, 400, 30);

    private final PhyllosensEntity mob;
    private final StiltParams p;
    private final StiltWalker w;
    private final PhyllosensGaze g;

    private Mode mode = Mode.WALK;
    private int modeT, idle, lost, tries;
    private boolean locked, visible;
    @Nullable
    private Player target;
    @Nullable
    private Vec3 lastSeen, peekHead, openCell, openDir, standAt;
    private long lastSeenAt = -100000L, seenSince = -1L, coolUntil, hitUntil, nextWatch;
    private int seen;
    private boolean openSolid;
    /** 確かめ用: 最後に分かれ道で決めたこと(GameTest の失敗の説明)。 */
    public String note = "-";
    private int whyIn, whyNeck, whySee;
    @Nullable
    private OpeningSearch search;

    /** 計測用: 見張り・覗きの考えにかかった時間(ns)と回数。 */
    public static volatile long nanos, calls;

    PhyllosensBrain(PhyllosensEntity mob, StiltWalker walker) {
        this.mob = mob;
        this.w = walker;
        this.p = walker.p;
        this.g = mob.gaze();
    }

    // ------------------------------------------------------------------ 読み口(GameTest・描画)

    public Mode mode() {
        return this.mode;
    }

    public boolean locked() {
        return this.locked;
    }

    /** 今、相手が見えているか(5 tick ごとに測る)。 */
    public boolean seesTarget() {
        return this.visible;
    }

    /** 見え始めた時刻(見えていなければ −1)。 */
    public long seenSince() {
        return this.seenSince;
    }

    @Nullable
    public Vec3 lastSeen() {
        return this.lastSeen;
    }

    @Nullable
    public Vec3 openingCell() {
        return this.openCell;
    }

    /** 行動を持っている(うろつきを止める)か。 */
    public boolean busy() {
        return this.mode != Mode.WALK && this.mode != Mode.DISGUISE;
    }

    // ------------------------------------------------------------------ tick

    void tick() {
        if (!(this.mob.level() instanceof ServerLevel level) || this.g.devHold) return;
        long t0 = System.nanoTime();
        long now = level.getGameTime();
        this.modeT++;
        if ((now + this.mob.getId()) % 5L == 0L) scan(level);
        switch (this.mode) {
            case WALK, DISGUISE -> calm(level, now);
            case WATCH_GO -> {
                if (!this.w.moving() || this.modeT > 400) enter(Mode.WATCH);
            }
            case WATCH -> watch(level, now);
            case PEEK -> peek(level, now);
            case PEER_GO -> peerGo(level, now);
            case PEER -> peer(level, now);
            case RETRACT -> {
                if (this.modeT > PEEK_TIMING.recover()) enter(Mode.WALK);
            }
        }
        nanos += System.nanoTime() - t0;
        calls++;
    }

    private void enter(Mode m) {
        this.mode = m;
        this.modeT = 0;
        this.tries = 0;
        this.lost = 0;
        this.seen = 0;
        this.seenSince = -1L;
        this.locked = false;
        switch (m) {
            case WALK -> {
                this.w.stanceScale = 1.0D;
                this.w.heightOverride = Double.NaN;
                this.w.faceYaw = Double.NaN;
                this.g.aim(null, null);
                this.g.lean(0.0D);
                this.g.glow(false);
            }
            case DISGUISE -> {
                this.w.stanceScale = this.p.disguiseStance;
                this.g.aim(null, null);
                this.g.glow(false);
                this.nextWatch = this.mob.level().getGameTime() + this.p.watchEvery;
            }
            case WATCH_GO, PEER_GO -> {
                this.w.stanceScale = 1.0D;
                this.g.aim(null, null);
                this.g.glow(false);
            }
            case WATCH -> {
                this.w.stanceScale = 1.0D;
                this.g.glow(true);
            }
            case PEEK -> {
                this.w.stop();
                this.peekHead = null;
            }
            case PEER -> this.w.stanceScale = this.p.peerStance;
            case RETRACT -> {
                this.g.aim(null, null);
                this.g.lean(0.0D);
                this.g.glow(false);
                this.w.heightOverride = Double.NaN;
                this.w.faceYaw = Double.NaN;
                this.w.stanceScale = 1.0D;
            }
        }
    }

    /** 叩ける頭が叩かれた(本体の hurt の前)。覗いている間なら引っ込めて、しばらく覗かない。 */
    void onHeadHit() {
        if (this.mode == Mode.PEEK || this.mode == Mode.PEER || this.mode == Mode.PEER_GO) {
            this.hitUntil = this.mob.level().getGameTime() + this.p.headHitCooldown;
            this.coolUntil = Math.max(this.coolUntil, this.hitUntil);
            enter(Mode.RETRACT);
        }
    }

    // ------------------------------------------------------------------ 相手

    /** 相手になれるプレイヤー(サバイバル・冒険。創造・観戦は見ない)。GameTest の偽プレイヤーも拾えるよう箱で探す。 */
    static boolean watchable(Player pl) {
        return pl.isAlive() && !pl.isCreative() && !pl.isSpectator();
    }

    private void scan(ServerLevel level) {
        double r = this.p.sightRange;
        List<Player> list = this.forcedTarget != null ? List.of(this.forcedTarget)
                : level.getEntitiesOfClass(Player.class, this.mob.getBoundingBox().inflate(r), PhyllosensBrain::watchable);
        Player best = null;
        double bd = r * r;
        for (Player pl : list) {
            double d = pl.distanceToSqr(this.mob);
            if (d <= bd) {
                bd = d;
                best = pl;
            }
        }
        this.target = best;
        boolean was = this.visible;
        this.visible = best != null && StiltSight.canSee(level, this.g.eyeWorld(this.mob), best.getEyePosition());
        long now = level.getGameTime();
        if (this.visible) {
            if (!was || this.seenSince < 0L) this.seenSince = now;   // 状態が変わって見え始めが消えても、見えている間に数え直す
            this.lastSeen = best.position();
            this.lastSeenAt = now;
        } else {
            this.seenSince = -1L;
        }
    }

    /** 見えている相手に印を付ける(見え続けて markDelay 経ってから。付いた後も見えている間は付け直す)。 */
    private void markIfSeen(long now) {
        if (this.visible && this.target != null && this.seenSince >= 0L && now - this.seenSince >= this.p.markDelay) {
            PhyllosensWatchEffect.mark(this.target);
        }
    }

    private boolean coverOver(ServerLevel level, Vec3 at) {
        return !skyOpen(level, BlockPos.containing(at.x, at.y + 1.6D, at.z));
    }

    /**
     * 真上に屋根が無いか(葉を除いた高さ地図)。空の光({@code canSeeSky})は組んだばかりの屋根にまだ追いついていないことがあり、
     * 家の中を「空が見える」と見た(11-14 GameTest)。葉の下は屋根でない(森の覗きで扱う)。
     */
    static boolean skyOpen(ServerLevel level, BlockPos p) {
        return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()) <= p.getY();
    }

    // ------------------------------------------------------------------ 歩く・化ける

    private void calm(ServerLevel level, long now) {
        if (this.w.moving()) {
            this.idle = 0;
            if (this.mode == Mode.DISGUISE) enter(Mode.WALK);
            duck(level);
        } else if (++this.idle >= this.p.disguiseAfter && this.mode == Mode.WALK) {
            enter(Mode.DISGUISE);
        } else if (this.mode == Mode.WALK) {
            duck(level);
        }
        Player t = this.target;
        if (t == null || now < this.coolUntil) {
            if (this.mode == Mode.DISGUISE && now >= this.nextWatch && t != null) startWatch(level);
            return;
        }
        double d = t.distanceTo(this.mob);
        if (d <= this.p.noticeRange && !coverOver(level, t.position())) {
            enter(Mode.PEEK);
        } else if (d <= this.p.peerRange && now - this.lastSeenAt <= 200L && !this.visible && coverOver(level, t.position())) {
            startPeer(level);
        } else if (this.mode == Mode.DISGUISE && now >= this.nextWatch) {
            startWatch(level);
        }
    }

    /** 歩いている間、かがんでいる・頭の上が塞がっているなら首を前・下へ(葉に埋めない)。 */
    private void duck(ServerLevel level) {
        if ((this.modeT & 7) != 0) return;
        Vec3 pos = this.mob.position();
        float yaw = this.mob.getYRot();
        boolean blocked = false;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        Vec3 head = this.g.restHead(pos, yaw);
        for (int dy = 0; dy <= 2 && !blocked; dy++) {
            q.set(Mth.floor(head.x), Mth.floor(head.y) + dy, Mth.floor(head.z));
            blocked = StiltSight.blocks(level, q) || !level.getBlockState(q).getCollisionShape(level, q).isEmpty();
        }
        if (!blocked) {
            this.g.aim(null, null);
            return;
        }
        Vec3 base = StiltNeck.baseWorld(this.g.shape, pos, yaw, 0.0D);
        Vec3 fwd = Vec3.directionFromRotation(0.0F, yaw);
        Vec3 h = base.add(fwd.scale(3.2D)).add(0, -0.4D, 0);
        this.g.aim(h, h.add(fwd.scale(3.0D)).add(0, -0.5D, 0));
    }

    // ------------------------------------------------------------------ 見張り

    private void startWatch(ServerLevel level) {
        this.nextWatch = level.getGameTime() + this.p.watchEvery;
        StiltPlanner.Node best = null;
        double bestScore = -1.0D;
        int r = this.p.watchRadius;
        for (int k = 0; k < 10; k++) {
            int x = Mth.floor(this.mob.getX() + 0.5D) + this.mob.getRandom().nextInt(r * 2 + 1) - r;
            int z = Mth.floor(this.mob.getZ() + 0.5D) + this.mob.getRandom().nextInt(r * 2 + 1) - r;
            StiltPlanner.Node n = StiltPlanner.evaluate(level, this.p, x, z, this.mob.getY() - 5.0D);
            if (n == null) continue;
            // 林冠の穴・開けた所: 足場が多く、頭の高さの上に空がある
            boolean sky = skyOpen(level, BlockPos.containing(x, n.b() + 6.0D, z));
            double score = n.open() + (sky ? 1.0D : 0.0D);
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        if (best == null) {
            enter(Mode.WATCH);
            return;
        }
        this.w.goTo(best.cx(), best.b(), best.cz(), 1.0D);
        enter(Mode.WATCH_GO);
    }

    private void watch(ServerLevel level, long now) {
        Player t = this.target;
        if (this.visible && t != null) {
            this.locked = true;
            this.lost = 0;
            Vec3 base = StiltNeck.baseWorld(this.g.shape, this.mob.position(), this.mob.getYRot(), 0.0D);
            Vec3 eye = t.getEyePosition();
            Vec3 head = base.add(eye.subtract(base).normalize().scale(StiltNeck.reach(this.g.shape) * 0.85D));
            this.g.aim(head, eye);
            markIfSeen(now);
        } else if (this.locked) {
            if (++this.lost > 40) {
                this.locked = false;
                this.g.aim(null, null);
                // 屋根の下へ隠れた: 開口を覗きに行く
                if (t != null && this.lastSeen != null && coverOver(level, this.lastSeen) && now >= this.coolUntil
                        && this.lastSeen.distanceTo(this.mob.position()) <= this.p.peerRange) {
                    startPeer(level);
                    return;
                }
            }
        }
        if (this.modeT > this.p.watchFor && !this.locked) enter(Mode.DISGUISE);
    }

    // ------------------------------------------------------------------ 森で覗く

    private void peek(ServerLevel level, long now) {
        Player t = this.target;
        if (t == null || t.distanceTo(this.mob) > this.p.noticeRange * 1.5D || this.modeT > PEEK_TIMING.total()) {
            this.coolUntil = now + this.p.peekCooldown;
            enter(Mode.RETRACT);
            return;
        }
        if (this.peekHead == null || (!this.visible && this.modeT % 20 == 0 && headArrived())) {
            if (this.peekHead != null && ++this.tries > this.p.peekRetries) {
                // 隠れ直された: あきらめる
                this.coolUntil = now + this.p.peekCooldown;
                enter(Mode.RETRACT);
                return;
            }
            this.peekHead = findPeekHead(level, t.getEyePosition());
            this.note = "覗く頭 " + (this.peekHead == null ? "なし" : String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", this.peekHead.x, this.peekHead.y, this.peekHead.z)) + " 回 " + this.tries + " 中" + this.whyIn + " 首" + this.whyNeck + " 見え" + this.whySee + " 目 " + String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", t.getEyePosition().x, t.getEyePosition().y, t.getEyePosition().z) + " 付け根 " + String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", StiltNeck.baseWorld(this.g.shape, this.mob.position(), this.mob.getYRot(), 0.0D).x, StiltNeck.baseWorld(this.g.shape, this.mob.position(), this.mob.getYRot(), 0.0D).y, StiltNeck.baseWorld(this.g.shape, this.mob.position(), this.mob.getYRot(), 0.0D).z);
            if (this.peekHead == null) {
                this.tries++;
                if (this.tries > this.p.peekRetries) {
                    this.coolUntil = now + this.p.peekCooldown;
                    enter(Mode.RETRACT);
                }
                return;
            }
        }
        this.g.aim(this.peekHead, t.getEyePosition());
        this.g.glow(headArrived());
        // 覗く間は胴を回さない(回すと選んだ頭の点がずれる。首は 150° まで曲がる)
        markIfSeen(now);
    }

    private boolean headArrived() {
        return this.peekHead == null || this.g.headWorld(this.mob).distanceToSqr(this.peekHead) < 0.6D * 0.6D;
    }

    private void faceTowards(Vec3 at) {
        this.w.faceYaw = Mth.atan2(-(at.x - this.mob.getX()), at.z - this.mob.getZ()) * Mth.RAD_TO_DEG;
    }

    /**
     * 頭の pivot h から相手の目が「しっかり」見えるか: 花の芯(頭から目の向きへ 0.44)と、そこから左右へ 0.2 ずらした 3 本の線が全部通る。
     * 幹の角すれすれの線を選ぶと、頭が少しずれただけで見えなくなった(11-14 GameTest)。
     */
    private static boolean seesWell(ServerLevel level, Vec3 h, Vec3 eye) {
        Vec3 d = eye.subtract(h).normalize();
        Vec3 e = h.add(d.scale(0.44D));
        Vec3 side = new Vec3(-d.z, 0, d.x);
        if (side.lengthSqr() < 1.0E-6D) side = new Vec3(1, 0, 0);
        side = side.normalize().scale(0.2D);
        return StiltSight.canSee(level, e, eye) && StiltSight.canSee(level, e.add(side), eye) && StiltSight.canSee(level, e.subtract(side), eye);
    }

    /** 首の届く範囲で、相手の目が見えて、首がそこまで通る頭の置き場(相手の向きに一番近いもの)。 */
    @Nullable
    private Vec3 findPeekHead(ServerLevel level, Vec3 eye) {
        this.whyIn = this.whyNeck = this.whySee = 0;
        Vec3 pos = this.mob.position();
        Vec3 base = StiltNeck.baseWorld(this.g.shape, pos, this.mob.getYRot(), 0.0D);
        Vec3 to = eye.subtract(base);
        float yaw0 = (float) (Mth.atan2(-to.x, to.z) * Mth.RAD_TO_DEG);
        double reach = StiltNeck.reach(this.g.shape);
        Vec3 best = null;
        double bestScore = Double.MAX_VALUE;
        int n = 0;
        for (double r : new double[]{reach * 0.95D, reach * 0.7D}) {
            for (int yi = 0; yi < 11; yi++) {
                float dyaw = (yi % 2 == 0 ? 1 : -1) * ((yi + 1) / 2) * 20.0F;
                for (float pitch : new float[]{0.0F, 20.0F, 40.0F, -20.0F}) {
                    if (n++ >= this.p.peekSamples) break;
                    Vec3 want = base.add(Vec3.directionFromRotation(pitch, yaw0 + dyaw).scale(r));
                    // 首の弧で本当に頭が来る所で確かめる(狙った点から 0.4 ずれて、すれすれの線が通らなかった、11-14)
                    StiltNeck.Pose pz = StiltNeck.solve(this.g.shape, StiltNeck.toBody(this.g.shape, want, pos, this.mob.getYRot(), 0.0D),
                            StiltNeck.toBody(this.g.shape, eye, pos, this.mob.getYRot(), 0.0D));
                    Vec3 h = StiltNeck.toWorld(this.g.shape, pz.head(), pos, this.mob.getYRot(), 0.0D);
                    BlockPos hp = BlockPos.containing(h);
                    if (StiltSight.blocks(level, hp)) {
                        this.whyIn++;
                        continue;
                    }
                    if (!StiltSight.canSee(level, base, h)) {   // 首が幹・壁を通らない
                        this.whyNeck++;
                        continue;
                    }
                    if (!seesWell(level, h, eye)) {
                        this.whySee++;
                        continue;
                    }
                    double score = Math.abs(dyaw) + Math.abs(pitch) * 0.5D + (reach - r) * 4.0D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = h;
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ 開口を覗く

    private void startPeer(ServerLevel level) {
        if (this.lastSeen == null) return;
        this.note = "覗きに行く";
        this.search = new OpeningSearch(BlockPos.containing(this.lastSeen), this.p.openingRadius);
        this.openCell = null;
        enter(Mode.PEER_GO);
    }

    private void peerGo(ServerLevel level, long now) {
        if (this.modeT > 600) {
            this.coolUntil = now + this.p.peekCooldown;
            enter(Mode.WALK);
            return;
        }
        if (this.openCell == null) {
            if (this.search == null || this.lastSeen == null) {
                enter(Mode.WALK);
                return;
            }
            Player t = this.target;
            Vec3 eye = t != null && t.distanceToSqr(this.lastSeen) < 9.0D ? t.getEyePosition() : this.lastSeen.add(0, 1.6D, 0);
            OpeningSearch.Result res = this.search.step(level, eye, this.mob.position(), this.p.openingCellsPerTick);
            if (res == null) return;   // まだ探している(次の tick に続き)
            this.search = null;
            this.note = res.cell() == null ? "開口なし" : "開口 " + res.cell().toShortString();
            if (res.cell() == null || !chooseStand(level, res)) {
                if (res.cell() != null) this.note += " 立ち位置なし";
                this.coolUntil = now + this.p.peekCooldown;
                enter(Mode.WALK);
                return;
            }
            this.w.goTo(this.standAt.x, this.standAt.y, this.standAt.z, 1.0D);
            this.note += " 立つ " + BlockPos.containing(this.standAt).toShortString();
            return;
        }
        if (!this.w.moving()) {
            if (this.mob.position().distanceToSqr(this.standAt.x, this.mob.getY(), this.standAt.z) < 2.0D * 2.0D) {
                enter(Mode.PEER);
            } else {
                this.note += " 着かない(" + String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(this.mob.position().distanceToSqr(this.standAt.x, this.mob.getY(), this.standAt.z))) + ")";
                this.coolUntil = now + this.p.peekCooldown;
                enter(Mode.WALK);
            }
        }
    }

    /** 開口の外 standOff に、胴を下ろせる立ち位置を探す(横へ 1 ずつ・前後 0.5 ずつずらして)。 */
    private boolean chooseStand(ServerLevel level, OpeningSearch.Result res) {
        Vec3 c = Vec3.atCenterOf(res.cell());
        Vec3 d = new Vec3(res.dir().getStepX(), 0, res.dir().getStepZ());
        Vec3 side = new Vec3(-d.z, 0, d.x);
        for (double off : new double[]{this.p.peerStandOff, this.p.peerStandOff + 0.5D, this.p.peerStandOff - 0.5D}) {
            for (int s : new int[]{0, 1, -1}) {
                Vec3 at = c.add(d.scale(off)).add(side.scale(s));
                int x = Mth.floor(at.x + 0.5D), z = Mth.floor(at.z + 0.5D);
                if (StiltPlanner.canCrouch(level, this.p, x, z, c.y - 1.0D, this.p.peerHeight)) {
                    StiltPlanner.Node n = StiltPlanner.evaluate(level, this.p, x, z, c.y - 1.0D);
                    if (n == null) continue;
                    this.openCell = c;
                    this.openSolid = !level.getBlockState(res.cell()).isAir();   // ガラス・柵の窓: 頭は外に止める
                    this.openDir = d;
                    this.standAt = new Vec3(x, n.b(), z);
                    return true;
                }
            }
        }
        return false;
    }

    private void peer(ServerLevel level, long now) {
        Player t = this.target;
        if (this.openCell == null || this.modeT > this.p.peerFor) {
            this.coolUntil = now + this.p.peekCooldown;
            enter(Mode.RETRACT);
            return;
        }
        // 前触れ: 開口へ向き、脚を広げて胴を下ろし、前へ倒す。頭は胴が下りてから
        this.w.faceYaw = Mth.atan2(this.openDir.x, -this.openDir.z) * Mth.RAD_TO_DEG;
        this.w.heightOverride = this.p.peerHeight;
        this.g.lean(this.p.peerLean);
        Vec3 look = t != null && this.visible ? t.getEyePosition() : (this.lastSeen != null ? this.lastSeen.add(0, 1.0D, 0) : this.openCell.subtract(this.openDir));
        Vec3 head = this.openCell.add(this.openDir.scale(this.openSolid ? 1.05D : 0.45D));
        if (this.modeT >= PEEK_TIMING.windup()) {
            this.g.aim(head, look);
            this.peekHead = head;
            this.g.glow(headArrived());
        }
        markIfSeen(now);
    }

    // ------------------------------------------------------------------ 開口の探し

    /**
     * 相手のまわり(半径 R の立方体)で、頭を寄せられる開口を探す。1 tick に {@code perTick} マスずつ続きから(段 1 の経路と同じく予算を守る)。
     * 開口 = 視線を通すマス(空気・ガラス・柵・鉄格子)で、横のどれかの向きに外へ 2 マス空いていて(2 マス先は空が見える)、そこから相手の目が見える。
     * 屋根の下のマス(空が見えない = 入口・窓の中)を先に選ぶ。一番近いもの。
     */
    static final class OpeningSearch {
        record Result(@Nullable BlockPos cell, net.minecraft.core.Direction dir) {
        }

        private final BlockPos center;
        private final int r;
        private int index;
        @Nullable
        private BlockPos best;
        private net.minecraft.core.Direction bestDir = net.minecraft.core.Direction.NORTH;
        private double bestScore = Double.MAX_VALUE;
        /** 計測用。 */
        static volatile long cells;

        OpeningSearch(BlockPos center, int r) {
            this.center = center;
            this.r = r;
        }

        @Nullable
        Result step(ServerLevel level, Vec3 eye, Vec3 from, int perTick) {
            int n = 2 * this.r + 1, total = n * n * n;
            BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos(), q = new BlockPos.MutableBlockPos();
            int end = Math.min(total, this.index + perTick);
            for (; this.index < end; this.index++) {
                int i = this.index;
                int dx = i % n - this.r, dy = (i / n) % n - this.r, dz = i / (n * n) - this.r;
                c.set(this.center.getX() + dx, this.center.getY() + dy, this.center.getZ() + dz);
                cells++;
                if (StiltSight.blocks(level, c)) continue;
                boolean covered = !skyOpen(level, c);
                if (!covered && !StiltSight.seeThrough(level.getBlockState(c))) continue;   // 外の空気のマスは開口でない
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                    q.set(c.getX() + d.getStepX(), c.getY(), c.getZ() + d.getStepZ());
                    if (StiltSight.blocks(level, q) || !level.getBlockState(q).getCollisionShape(level, q).isEmpty()) continue;
                    q.set(c.getX() + d.getStepX() * 2, c.getY(), c.getZ() + d.getStepZ() * 2);
                    if (StiltSight.blocks(level, q) || !skyOpen(level, q)) continue;
                    Vec3 cc = Vec3.atCenterOf(c);
                    double score = cc.distanceTo(from) + Math.abs(dy) * 0.5D;
                    if (score >= this.bestScore) continue;
                    if (!StiltSight.canSee(level, cc.add(d.getStepX() * 0.45D, 0, d.getStepZ() * 0.45D), eye)) continue;
                    this.bestScore = score;
                    this.best = c.immutable();
                    this.bestDir = d;
                }
            }
            if (this.index < total) return null;
            return new Result(this.best, this.bestDir);
        }
    }

    /** 試験用: 相手(GameTest の偽プレイヤーは世界の一覧に入らないので直に渡す)。 */
    @Nullable
    public Player forcedTarget;

    /** 試験用: すぐ見張りに入る(その場で)。 */
    public void forceWatch() {
        enter(Mode.WATCH);
    }

    /** 試験用: 相手を最後に見た所と時刻を入れる(屋根の下へ隠れた相手を覗きに行く場面)。 */
    public void noteSeen(Vec3 at, long now) {
        this.lastSeen = at;
        this.lastSeenAt = now;
    }

    /** 試験用: 見え始めの記録を消す。 */
    void resetSeen() {
        this.seenSince = -1L;
    }

    /** 叩ける頭の中心(ワールド)。 */
    Vec3 headPartCenter() {
        return this.g.eyeWorld(this.mob);
    }

    static AABB around(Vec3 c, double r) {
        return new AABB(c, c).inflate(r);
    }
}
