package com.yayuy.overgrownvegetation.entity.flyer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 飛ぶ型の群れ(2026-11-15、ヴェロフィル段 1)。サーバーの中だけにあり、保存はしない(群れの番号・並び・家・高さの層を各個体が持ち、
 * 読み込み直したときに同じ番号の個体が集まって組み直す)。判断は 1 tick に 1 回(最初に tick した個体が回す)。
 *
 * <h2>状態</h2>
 * <ul>
 *   <li>{@link State#PATROL}: 先頭が家のまわりを大きく巡回し、後続は V 字({@link VFormation})。</li>
 *   <li>{@link State#ROOST}: 夜・雨。先頭のまわりの林冠のてっぺんに止まり木を割り当て、各々が降りて止まる。</li>
 *   <li>{@link State#WAKE}: 朝・雨上がり。止まっている個体が先頭から順に少しずつずらして飛び立つ。全員が飛んだら巡回へ。</li>
 *   <li>{@link State#FLEE}: 止まり木で驚いた。全員が一斉に飛び立ち、驚かせたものから離れる向きへ隊列で飛んで、まだ夜なら別の木に止まり直す。</li>
 * </ul>
 * 先頭が死ぬと {@link #SCATTER_TICKS} の間、各々の向きがばらつき(乱れ)、並びの 1 番が先頭になって残りが詰める(リストの並びがそのまま並び)。
 */
public final class Flock {

    public enum State { PATROL, ROOST, WAKE, FLEE }

    /** 先頭が落ちたときの乱れの長さ(tick)【仮】。 */
    public static final int SCATTER_TICKS = 30;
    /** 巡回: 家からの半径・行き先の距離・曲げてよい角度・粘る上限【仮】。 */
    public static final double HOME_RADIUS = 64.0D, PATROL_MIN = 40.0D, PATROL_MAX = 80.0D, PATROL_FAN = 50.0D;
    public static final int PATROL_GIVE_UP = 400;
    /** 止まり木を探す半径(見つからなければ広げる)・止まり木の間【仮】。 */
    public static final int ROOST_RADIUS = 24, ROOST_RADIUS_WIDE = 40;
    public static final double PERCH_GAP = 3.0D;
    /** 驚いて逃げる長さ(tick)【仮】。巡航で 60 ブロックほど。 */
    public static final int FLEE_TICKS = 90;
    /** 飛び立ちをずらす幅(tick)【仮】。 */
    public static final int WAKE_SPREAD = 60;

    private static final Map<ServerLevel, Map<UUID, Flock>> BY_LEVEL = new WeakHashMap<>();

    public final UUID id;
    /** 並び順(0 = 先頭)。 */
    final List<SkyFlyer> members = new ArrayList<>();
    /** 並びの鍵(保存された並び。新しく入った個体は最後)。members と同じ並び。 */
    private final List<Integer> keys = new ArrayList<>();
    BlockPos home;
    /** 林冠からの高さの層(ブロック)。群れごとに 1 つ。 */
    double layer;
    State state = State.PATROL;
    int scatter;
    /** 止まっている群れが「気付いた」相手の位置と残り(tick)。身構え({@link SkyFlyer#alertRange})用。 */
    double alertX, alertZ;
    int alertTicks;
    private long lastTick = Long.MIN_VALUE;
    private SkyFlyer lastLeader;
    /** 巡回の行き先。 */
    double patrolX, patrolZ;
    boolean hasPatrol;
    /** 今の巡回の行き先が「家から離れすぎたので戻る」ものか(2026-11-15 セミナリアで足した。風に流される種が戻るときだけ強く羽ばたく)。 */
    boolean returningHome;
    private int patrolTicks;
    /** 逃げる向き・残り。 */
    double fleeHeading;
    int fleeTicks;
    /** 止まり木が見つからなかったときの探し直しまで。 */
    private int roostRetry;
    /** 止まり木のまわりの中心(止まり木が足りない個体はこの上を回る)。 */
    BlockPos roostCenter;

    private Flock(UUID id, BlockPos home, double layer) {
        this.id = id;
        this.home = home;
        this.layer = layer;
    }

    /** 番号の群れ(無ければ作る)。 */
    static Flock obtain(ServerLevel level, UUID id, BlockPos home, double layer) {
        Map<UUID, Flock> map = BY_LEVEL.computeIfAbsent(level, k -> new HashMap<>());
        return map.computeIfAbsent(id, k -> new Flock(k, home, layer));
    }

    /** 番号の群れ(無ければ null)。 */
    static Flock find(ServerLevel level, UUID id) {
        Map<UUID, Flock> map = BY_LEVEL.get(level);
        return map == null ? null : map.get(id);
    }

    public int size() {
        return this.members.size();
    }

    public SkyFlyer leader() {
        return this.members.isEmpty() ? null : this.members.get(0);
    }

    public int slotOf(SkyFlyer f) {
        return this.members.indexOf(f);
    }

    public State state() {
        return this.state;
    }

    public boolean returningHome() {
        return this.returningHome;
    }

    public int scatterTicks() {
        return this.scatter;
    }

    public List<SkyFlyer> membersView() {
        return java.util.Collections.unmodifiableList(this.members);
    }

    /** 入れる(鍵の順に。鍵が負なら最後へ)。 */
    void add(SkyFlyer f, int key) {
        if (this.members.contains(f)) return;
        if (key < 0) key = this.keys.isEmpty() ? 0 : this.keys.get(this.keys.size() - 1) + 1;
        int i = 0;
        while (i < this.keys.size() && this.keys.get(i) <= key) i++;
        this.members.add(i, f);
        this.keys.add(i, key);
    }

    void remove(SkyFlyer f) {
        int i = this.members.indexOf(f);
        if (i >= 0) {
            this.members.remove(i);
            this.keys.remove(i);
        }
    }

    /** 1 tick に 1 回だけ回す。 */
    void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (now == this.lastTick) return;
        this.lastTick = now;
        for (int i = this.members.size() - 1; i >= 0; i--) {
            SkyFlyer m = this.members.get(i);
            if (m.isRemoved() || !m.isAlive() || m.flock != this || m.level() != level) {
                this.members.remove(i);
                this.keys.remove(i);
            }
        }
        if (this.members.isEmpty()) {
            Map<UUID, Flock> map = BY_LEVEL.get(level);
            if (map != null) map.remove(this.id);
            return;
        }
        SkyFlyer leader = this.members.get(0);
        if (this.lastLeader != null && this.lastLeader != leader && this.lastLeader.isDeadOrDying()) {
            this.scatter = SCATTER_TICKS;
            for (SkyFlyer m : this.members) m.onScatter();
            this.hasPatrol = false;
        }
        this.lastLeader = leader;
        if (this.scatter > 0) this.scatter--;
        if (this.alertTicks > 0) this.alertTicks--;

        boolean roost = leader.wantsRoost();
        RandomSource rnd = level.getRandom();
        switch (this.state) {
            case PATROL -> {
                if (anyPerched()) {
                    // 読み込み直した直後(止まったまま保存された): 止まり木はそのまま
                    this.state = roost ? State.ROOST : State.WAKE;
                    if (this.state == State.WAKE) scheduleWake(rnd);
                    this.roostCenter = leader.blockPosition();
                } else if (roost) {
                    if (--this.roostRetry <= 0) pickRoost(level, leader.blockPosition());
                } else {
                    updatePatrol(leader, rnd);
                }
            }
            case FLEE -> {
                if (--this.fleeTicks <= 0) {
                    if (roost) pickRoost(level, leader.blockPosition());
                    if (this.state == State.FLEE) this.state = State.PATROL;
                    this.hasPatrol = false;
                }
            }
            case ROOST -> {
                if (!roost) {
                    this.state = State.WAKE;
                    scheduleWake(rnd);
                }
            }
            case WAKE -> {
                if (roost) {
                    this.state = State.ROOST;   // 起きかけで夜・雨に戻った: 止まり木はそのまま(飛び立った個体は同じ木へ戻る)
                } else if (!anyPerched()) {
                    this.state = State.PATROL;
                    this.hasPatrol = false;
                    for (SkyFlyer m : this.members) m.perch = null;
                }
            }
        }
    }

    private boolean anyPerched() {
        for (SkyFlyer m : this.members) {
            if (m.mode() == SkyFlyer.MODE_PERCHED || m.mode() == SkyFlyer.MODE_LANDING) return true;
        }
        return false;
    }

    /** 先頭から順に、少しずつずらして飛び立たせる。 */
    private void scheduleWake(RandomSource rnd) {
        int t = 0;
        for (SkyFlyer m : this.members) {
            m.takeoffDelay = t;
            t = Math.min(WAKE_SPREAD, t + 4 + rnd.nextInt(10));
        }
    }

    /** 止まり木を探して割り当てる。見つからなければ 100 tick 後に探し直す(その間は巡回のまま)。 */
    void pickRoost(ServerLevel level, BlockPos center) {
        int n = this.members.size();
        SkyFlyer lead = leader();
        double up = lead != null ? lead.perchClearUp() : SkyProbe.CLEAR_UP;   // 種ごとの真上の空き(既定 6)
        List<BlockPos> spots = SkyProbe.findPerches(level, center, ROOST_RADIUS, n, PERCH_GAP, up);
        if (spots.size() < n) {
            List<BlockPos> wide = SkyProbe.findPerches(level, center, ROOST_RADIUS_WIDE, n, PERCH_GAP, up);
            if (wide.size() > spots.size()) spots = wide;
        }
        if (spots.isEmpty()) {
            this.roostRetry = 100;
            return;
        }
        // 近い個体から近い止まり木へ(先頭が一番高い所)
        for (int i = 0; i < n; i++) {
            this.members.get(i).perch = i < spots.size() ? spots.get(i) : null;
        }
        this.roostCenter = spots.get(0);
        this.state = State.ROOST;
    }

    /** 止まり木で驚いた: 全員が一斉に飛び立ち、(fromX, fromZ) から離れる向きへ逃げる。 */
    /** 止まっている群れが (x, z) の相手に気付いた(身構え)。見え続ける間は毎 5 tick 呼ばれて延びる。 */
    void alert(double x, double z) {
        this.alertX = x;
        this.alertZ = z;
        this.alertTicks = 12;
    }

    void flush(ServerLevel level, double fromX, double fromZ) {
        if (this.state != State.ROOST && this.state != State.WAKE) return;
        SkyFlyer leader = leader();
        if (leader == null) return;
        RandomSource rnd = level.getRandom();
        for (SkyFlyer m : this.members) {
            m.perch = null;
            m.takeoffDelay = rnd.nextInt(4);
            m.startTakeoff();
        }
        this.fleeHeading = FlightBody.yawOf(leader.getX() - fromX, leader.getZ() - fromZ);
        this.fleeTicks = FLEE_TICKS;
        this.state = State.FLEE;
        this.hasPatrol = false;
    }

    /**
     * 巡回の行き先を外から決める(2026-11-15 ヴェロフィルの爆撃: 相手の上空を回る)。次に選び直すまで {@link #PATROL_GIVE_UP} tick 粘る。
     * 呼ぶ側が毎回置き直す前提(着いたら土台がまた選ぶ)。
     */
    public void steerPatrol(double x, double z) {
        this.patrolX = x;
        this.patrolZ = z;
        this.hasPatrol = true;
        this.patrolTicks = PATROL_GIVE_UP;
    }

    /** 先頭の巡回の行き先: 今の向きから ±{@link #PATROL_FAN} 度・{@link #PATROL_MIN}〜{@link #PATROL_MAX} 先。家から離れすぎていたら家の方へ。 */
    private void updatePatrol(SkyFlyer leader, RandomSource rnd) {
        double dx = this.patrolX - leader.getX(), dz = this.patrolZ - leader.getZ();
        if (this.hasPatrol && dx * dx + dz * dz > 12.0D * 12.0D && --this.patrolTicks > 0) return;
        double hx = leader.getX() - (this.home.getX() + 0.5D), hz = leader.getZ() - (this.home.getZ() + 0.5D);
        if (hx * hx + hz * hz > HOME_RADIUS * 0.75D * HOME_RADIUS * 0.75D) {
            double a = rnd.nextDouble() * Math.PI * 2.0D, r = rnd.nextDouble() * HOME_RADIUS * 0.4D;
            this.patrolX = this.home.getX() + 0.5D + Math.cos(a) * r;
            this.patrolZ = this.home.getZ() + 0.5D + Math.sin(a) * r;
            this.returningHome = true;
        } else {
            this.returningHome = false;
            double h = Math.toRadians(leader.patrolFanCenter() + (rnd.nextDouble() * 2.0D - 1.0D) * PATROL_FAN);
            double d = Mth.lerp(rnd.nextDouble(), PATROL_MIN, PATROL_MAX);
            double px = leader.getX() - Math.sin(h) * d, pz = leader.getZ() + Math.cos(h) * d;
            // 家の輪の外へ出るなら輪の内へ引き戻す
            double qx = px - (this.home.getX() + 0.5D), qz = pz - (this.home.getZ() + 0.5D);
            double q = Math.sqrt(qx * qx + qz * qz);
            if (q > HOME_RADIUS) {
                px = this.home.getX() + 0.5D + qx / q * HOME_RADIUS;
                pz = this.home.getZ() + 0.5D + qz / q * HOME_RADIUS;
            }
            this.patrolX = px;
            this.patrolZ = pz;
        }
        this.hasPatrol = true;
        this.patrolTicks = PATROL_GIVE_UP;
    }

    /** ワールドを閉じたら群れを忘れる(値が実体を持ち、実体が面を持つので WeakHashMap だけでは面が残る)。 */
    @net.neoforged.fml.common.EventBusSubscriber(modid = com.yayuy.overgrownvegetation.OvergrownVegetation.MODID)
    public static final class Unload {
        private Unload() {
        }

        @net.neoforged.bus.api.SubscribeEvent
        public static void onLevelUnload(net.neoforged.neoforge.event.level.LevelEvent.Unload event) {
            if (event.getLevel() instanceof ServerLevel sl) BY_LEVEL.remove(sl);
        }
    }

    /** 空になった群れを忘れる。 */
    static void forget(ServerLevel level, Flock f) {
        Map<UUID, Flock> map = BY_LEVEL.get(level);
        if (map != null && f.members.isEmpty()) map.remove(f.id);
    }

    /** 計測・テスト用: この面の群れの数。 */
    public static int count(ServerLevel level) {
        Map<UUID, Flock> map = BY_LEVEL.get(level);
        return map == null ? 0 : map.size();
    }
}
