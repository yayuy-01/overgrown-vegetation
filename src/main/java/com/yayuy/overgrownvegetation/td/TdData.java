package com.yayuy.overgrownvegetation.td;

import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * TD イベント(森の侵攻)の<b>台帳</b>(2026-09-25)。ワールドに 1 つ(オーバーワールドに保存、id {@code overgrown_vegetation_td})。
 *
 * <p>持っているのは「最後に終わった時刻と結果」と、<b>進行中(予告中・侵攻中)のイベント 1 つ</b>だけ
 * (同時に 1 つ。仮決め 2026-09-25)。イベントには次を保存する:
 * <ul>
 *     <li>id(UUID。攻め手の {@code EventMobs} の印・群れの eventId と同じ値)、ディメンション、ターゲットの座標</li>
 *     <li>状態(予告 WARNING / 侵攻 ACTIVE / 終わった ENDED)とその時刻、一時停止が始まった時刻</li>
 *     <li>予算(まだ仮想の群れにもなっていないバイオマス、double)と、開始時の予算(ボスバーの分母)</li>
 *     <li><b>生きている攻め手の数</b>とその値段の合計(バイオマス)(保存する —— 読み込まれていない攻め手も数に入れるため。
 *     増えるのは実体化したとき、減るのは倒された・消されたときだけ。チャンクごと読み込み外れたときは減らさない)、倒された数</li>
 *     <li>仮想の群れ(データだけで行進している群れ)の一覧: 位置・種ごとの頭数・速さ・空かどうか</li>
 *     <li>始めの一斉投入を済ませたか({@code opened}。2026-09-25)</li>
 *     <li>攻めてくる向き({@code direction}。全方位か 8 方位。2026-09-26)</li>
 * </ul>
 * 払い戻せるか({@code refundable})も持つ: 世界のバイオマスを切り出して始めたときだけ、中止・負けで残りを世界へ返す
 * ({@code /ov td start <予算>} で決めた予算は世界から取っていないので返さない)。
 *
 * <p>ボスバーと「いま負けの処理でブロックを消している」印は保存しない(サーバーごとに作り直す。{@code SiegeData} と同じ)。
 */
public final class TdData extends SavedData {

    private static final String ID = "overgrown_vegetation_td";

    public static final SavedData.Factory<TdData> FACTORY =
            new SavedData.Factory<>(TdData::new, (tag, registries) -> load(tag), null);

    /** イベントの状態。 */
    public enum State {
        /** 予告中。ターゲットの近くにプレイヤーが来るまで侵攻へ進まない。 */
        WARNING("warning"),
        /** 侵攻中。 */
        ACTIVE("active"),
        /** 終わった(結果は {@link #lastOutcome})。 */
        ENDED("ended");

        private final String key;

        State(String key) {
            this.key = key;
        }

        static State byKey(String key) {
            for (State s : values()) {
                if (s.key.equals(key)) {
                    return s;
                }
            }
            return ENDED;
        }
    }

    /** 終わり方。 */
    public enum Outcome {
        NONE("none"),
        /** 予算が尽き、仮想の群れも攻め手もいなくなった。 */
        VICTORY("victory"),
        /** ターゲットが壊された(侵攻中に耐久 0 以外で無くなったときも)。 */
        DEFEAT("defeat"),
        /** コマンドで止めた・ディメンションが無い・予告中にターゲットが無くなった。 */
        CANCELLED("cancelled"),
        /** 誰もいないまま止まり続けた。 */
        ABANDONED("abandoned");

        private final String key;

        Outcome(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        static Outcome byKey(String key) {
            for (Outcome o : values()) {
                if (o.key.equals(key)) {
                    return o;
                }
            }
            return NONE;
        }
    }

    /** データだけで行進している群れ 1 つ。 */
    public static final class VirtualSwarm {
        final UUID id;
        final boolean air;
        double x;
        double y;
        double z;
        /** 行進の速さ(ブロック / tick)。 */
        double speed;
        /** 種(エンティティの id 文字列)ごとの頭数。並びは編成の行の順(先頭が主力)。 */
        final Object2IntLinkedOpenHashMap<String> counts = new Object2IntLinkedOpenHashMap<>();
        /** 実体化の条件を満たしたのに 1 体も置けなかった回数(保存しない。1 体でも置けた群れは実体になって消える)。 */
        int failures;
        /**
         * 置けなかった群れが、ターゲットからの距離を変えずに輪に沿って<b>歩いて</b>向かう角度(ターゲットから見た {@code atan2(dz, dx)}。
         * NaN = 横へ歩いていない)。保存しない(読み直したら、着いたものとしてその場でやり直す)。{@code TdTicker#sidestep}。
         */
        double sidestepAngle = Double.NaN;
        /** 横へ歩く向き(+1 = 角度が増える向き、−1 = 減る向き、0 = まだ決めていない)。2 回目からは同じ向きへ探し続ける。保存しない。 */
        int sidestepSign;
        /**
         * 壁・天井を登る群れ(swarm.json の templates.climb)か。実体化のとき所属の climber を立てる({@code Swarm#climber}。
         * 2026-09-25 のユーザー指定「壁を壊して入るグループと、壁を登るグループ」)。
         */
        boolean climber;

        VirtualSwarm(UUID id, boolean air, double x, double y, double z, double speed) {
            this.id = id;
            this.air = air;
            this.x = x;
            this.y = y;
            this.z = z;
            this.speed = speed;
        }

        public int size() {
            int n = 0;
            for (Object2IntMap.Entry<String> e : counts.object2IntEntrySet()) {
                n += Math.max(0, e.getIntValue());
            }
            return n;
        }

        public boolean air() {
            return air;
        }

        public boolean climber() {
            return climber;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id);
            t.putBoolean("air", air);
            if (climber) {
                t.putBoolean("climber", true);
            }
            t.putDouble("x", x);
            t.putDouble("y", y);
            t.putDouble("z", z);
            t.putDouble("speed", speed);
            ListTag list = new ListTag();
            for (Object2IntMap.Entry<String> e : counts.object2IntEntrySet()) {
                CompoundTag c = new CompoundTag();
                c.putString("type", e.getKey());
                c.putInt("n", e.getIntValue());
                list.add(c);
            }
            t.put("counts", list);
            return t;
        }

        static VirtualSwarm load(CompoundTag t) {
            VirtualSwarm v = new VirtualSwarm(t.hasUUID("id") ? t.getUUID("id") : UUID.randomUUID(), t.getBoolean("air"),
                    t.getDouble("x"), t.getDouble("y"), t.getDouble("z"), t.getDouble("speed"));
            v.climber = t.getBoolean("climber");
            ListTag list = t.getList("counts", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag c = list.getCompound(i);
                int n = c.getInt("n");
                if (n > 0) {
                    v.counts.addTo(c.getString("type"), n);
                }
            }
            return v;
        }
    }

    /** 進行中(または最後に終わった)イベント。 */
    public static final class Event {
        final UUID id;
        final ResourceKey<Level> dimension;
        final BlockPos target;
        State state;
        long stateSince;
        /** 一時停止が始まった時刻。止まっていなければ -1。 */
        long pausedSince = -1L;
        /** まだ仮想の群れになっていない予算(バイオマス)。 */
        double budget;
        /** 開始時の予算(バイオマス。ボスバーの分母)。 */
        double initialTotal;
        /** 生きている攻め手(実体化した数 − 倒された・消された数)。読み込まれていない個体も数に入る。 */
        int alive;
        /**
         * 生きている攻め手の値段の合計(種ごとの {@code tdCost}。バイオマス)。{@link #alive} と同じときに増減する
         * (ボスバーの「残り N バイオマス」のうち、もう出てきて戦っている分。2026-09-26)。
         */
        double aliveCost;
        int killed;
        /** 世界のバイオマスを切り出して始めたか(中止・負けのときに返すか)。 */
        boolean refundable;
        /** 次に仮想の群れを作ってよい時刻。 */
        long nextSwarmTick;
        final List<VirtualSwarm> virtual = new ArrayList<>();
        /**
         * 予算も仮想の群れも尽きたあと、生きている数が読み込まれている攻め手より多くなった(差が読み込まれていない所にいる)時刻
         * (保存しない。-1 = 差が無い)。{@code TdTicker#stragglers} がこの時刻から数えて差を数から外す。
         */
        long noLoadedSince = -1L;
        /**
         * 侵攻が起きた(予告が出た)ときのフェーズ。攻め手の顔ぶれはこのフェーズで湧く種だけ(ユーザー指定 2026-09-25「TD が起きた
         * フェーズで湧く mob のみ」)。-1 = 不明(それより前に保存されたイベント。今のフェーズを使う)。
         */
        int phase = -1;
        /**
         * 侵攻の始めの一斉投入(上限いっぱいの群れを実体化の輪に出す。{@code TdTicker#opening})を済ませたか。侵攻に入るときに false。
         * 保存する(読み直した侵攻で二度出さない)。この項目の無い古いイベントは済んだものとする。
         */
        boolean opened = true;
        /** この tick まで、帳簿を待たずに毎 tick 実体化する(一斉投入の直後)。保存しない。 */
        long burstUntil = Long.MIN_VALUE;
        /**
         * 攻めてくる向き(2026-09-26 のユーザー指定「全周囲 4 割・特定の方向 6 割、過繁茂したバイオームがある方向からになりやすく」)。
         * 自動の侵攻は予告のときに振り、侵攻もその向きで来る(見張り台が予告で知らせるため。2026-10-08 順 2 の着手順 8)。
         * 予告を飛ばすコマンドは侵攻に入るときに決める({@code TdTicker#rollDirection}、コマンドなら指定の向き)。保存する。
         * この項目の無い古い侵攻は全方位。
         */
        TdDirection direction = TdDirection.AROUND;
        /**
         * {@link #direction} をもう決めたか(予告のときに振った・侵攻に入った)。偽なら侵攻に入るときに振る。保存する
         * (予告の途中で読み直しても、知らせた向きのまま来る)。この項目の無い古いイベントは偽。
         */
        boolean directionChosen;

        Event(UUID id, ResourceKey<Level> dimension, BlockPos target, State state, long now) {
            this.id = id;
            this.dimension = dimension;
            this.target = target.immutable();
            this.state = state;
            this.stateSince = now;
        }

        public UUID id() {
            return id;
        }

        /** 攻め手の印・群れの eventId に使う文字列。 */
        public String eventId() {
            return id.toString();
        }

        public ResourceKey<Level> dimension() {
            return dimension;
        }

        public BlockPos target() {
            return target;
        }

        public State state() {
            return state;
        }

        public long stateSince() {
            return stateSince;
        }

        public boolean paused() {
            return pausedSince >= 0L;
        }

        public long pausedSince() {
            return pausedSince;
        }

        public double budget() {
            return budget;
        }

        public double initialTotal() {
            return initialTotal;
        }

        public int alive() {
            return alive;
        }

        /** 生きている攻め手の値段の合計(バイオマス)。 */
        public double aliveCost() {
            return alive > 0 ? Math.max(0.0D, aliveCost) : 0.0D;
        }

        public int killed() {
            return killed;
        }

        public boolean refundable() {
            return refundable;
        }

        /** 攻めてくる向き(全方位か 8 方位のどれか)。 */
        public TdDirection direction() {
            return direction;
        }

        /** 向きをもう決めたか(予告のときに振った・侵攻に入った)。 */
        public boolean directionChosen() {
            return directionChosen;
        }

        public List<VirtualSwarm> virtualSwarms() {
            return List.copyOf(virtual);
        }

        /** 仮想の群れの頭数の合計。 */
        public int virtualSize() {
            int n = 0;
            for (VirtualSwarm v : virtual) {
                n += v.size();
            }
            return n;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id);
            t.putString("dimension", dimension.location().toString());
            t.putLong("target", target.asLong());
            t.putString("state", state.key);
            t.putLong("stateSince", stateSince);
            t.putLong("pausedSince", pausedSince);
            t.putDouble("budget", budget);
            t.putDouble("initialTotal", initialTotal);
            t.putInt("alive", alive);
            t.putDouble("aliveCost", aliveCost);
            t.putInt("killed", killed);
            t.putBoolean("refundable", refundable);
            t.putLong("nextSwarmTick", nextSwarmTick);
            t.putInt("phase", phase);
            t.putBoolean("opened", opened);
            t.putString("direction", direction.key());
            t.putBoolean("directionChosen", directionChosen);
            ListTag list = new ListTag();
            for (VirtualSwarm v : virtual) {
                list.add(v.save());
            }
            t.put("virtual", list);
            return t;
        }

        @Nullable
        static Event load(CompoundTag t) {
            ResourceLocation dim = ResourceLocation.tryParse(t.getString("dimension"));
            if (!t.hasUUID("id") || dim == null) {
                return null;
            }
            Event e = new Event(t.getUUID("id"), ResourceKey.create(Registries.DIMENSION, dim),
                    BlockPos.of(t.getLong("target")), State.byKey(t.getString("state")), t.getLong("stateSince"));
            e.pausedSince = t.contains("pausedSince") ? t.getLong("pausedSince") : -1L;
            e.budget = t.getDouble("budget");
            e.initialTotal = t.getDouble("initialTotal");
            e.alive = t.getInt("alive");
            // この項目の無い古いイベントは 1 体 1 と見る(値段が種ごとになる前と同じ。表示にしか使わない)。
            e.aliveCost = t.contains("aliveCost") ? t.getDouble("aliveCost") : e.alive;
            e.killed = t.getInt("killed");
            e.refundable = t.getBoolean("refundable");
            e.nextSwarmTick = t.getLong("nextSwarmTick");
            e.phase = t.contains("phase") ? t.getInt("phase") : -1;
            e.opened = !t.contains("opened") || t.getBoolean("opened");
            e.direction = TdDirection.byKey(t.getString("direction"));
            e.directionChosen = t.getBoolean("directionChosen");
            ListTag list = t.getList("virtual", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                VirtualSwarm v = VirtualSwarm.load(list.getCompound(i));
                if (v.size() > 0) {
                    e.virtual.add(v);
                }
            }
            return e;
        }
    }

    /** 最後に終わった時刻。まだ一度も終わっていなければ {@link Long#MIN_VALUE}。 */
    private long lastEventEndTick = Long.MIN_VALUE;
    private Outcome lastOutcome = Outcome.NONE;
    @Nullable
    private Event event;

    // --- 保存しない ---

    /** ボスバー「森の侵攻 — 残り N バイオマス」。サーバーごとに作り直す({@code SiegeData} と同じ)。 */
    private final ServerBossEvent bossEvent = new ServerBossEvent(
            Component.translatable("event.overgrown_vegetation.td.boss", 0),
            BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.NOTCHED_10);
    /** ボスバーに今出している N(名前を送り直すのは変わったときだけ)。 */
    private long shownRemaining = -1L;
    /** 負けの処理でターゲットを消している最中の座標({@link TdEvents#isDefeating})。 */
    @Nullable
    private BlockPos defeatingPos;
    @Nullable
    private ResourceKey<Level> defeatingDimension;

    private TdData() {
        this.bossEvent.setVisible(false);
    }

    public static TdData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static TdData load(CompoundTag tag) {
        TdData data = new TdData();
        data.lastEventEndTick = tag.contains("lastEventEndTick") ? tag.getLong("lastEventEndTick") : Long.MIN_VALUE;
        data.lastOutcome = Outcome.byKey(tag.getString("lastOutcome"));
        if (tag.contains("event", Tag.TAG_COMPOUND)) {
            data.event = Event.load(tag.getCompound("event"));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (lastEventEndTick != Long.MIN_VALUE) {
            tag.putLong("lastEventEndTick", lastEventEndTick);
        }
        tag.putString("lastOutcome", lastOutcome.key);
        if (event != null) {
            tag.put("event", event.save());
        }
        return tag;
    }

    // --- 読む ---

    /** 予告中・侵攻中のイベント。無ければ(終わっていれば)null。 */
    @Nullable
    public Event live() {
        return event != null && event.state != State.ENDED ? event : null;
    }

    /** 侵攻中(ACTIVE)のイベント。無ければ null。 */
    @Nullable
    public Event active() {
        return event != null && event.state == State.ACTIVE ? event : null;
    }

    /** 最後のイベント(終わったものも含む。表示用)。 */
    @Nullable
    public Event last() {
        return event;
    }

    public long lastEventEndTick() {
        return lastEventEndTick;
    }

    public Outcome lastOutcome() {
        return lastOutcome;
    }

    /** 前のイベントが終わってから {@code cooldownTicks} 経ったか。 */
    public boolean cooldownOver(long now, int cooldownTicks) {
        return lastEventEndTick == Long.MIN_VALUE || now - lastEventEndTick >= cooldownTicks;
    }

    // --- 書く(td パッケージの中だけ) ---

    /**
     * ピースフルの間、次の侵攻までの待ちを止める({@code difficulty/Peaceful}、2026-09-26)。毎 tick 呼ばれ、待っている最中なら
     * 前の終わりの時刻を 1 tick 後ろへずらす。まだ侵攻が無い・待ち終わっているなら触らない。
     */
    void holdCooldown(long now, int cooldownTicks) {
        if (lastEventEndTick != Long.MIN_VALUE && now - lastEventEndTick < cooldownTicks) {
            lastEventEndTick++;
            setDirty();
        }
    }

    Event begin(UUID id, ResourceKey<Level> dimension, BlockPos target, State state, long now) {
        this.event = new Event(id, dimension, target, state, now);
        this.shownRemaining = -1L;
        setDirty();
        return this.event;
    }

    void end(Event e, Outcome outcome, long now) {
        e.state = State.ENDED;
        e.stateSince = now;
        e.pausedSince = -1L;
        e.virtual.clear();
        this.lastEventEndTick = now;
        this.lastOutcome = outcome;
        hideBossBar();
        setDirty();
    }

    ServerBossEvent bossEvent() {
        return bossEvent;
    }

    long shownRemaining() {
        return shownRemaining;
    }

    void setShownRemaining(long n) {
        this.shownRemaining = n;
    }

    void hideBossBar() {
        bossEvent.removeAllPlayers();
        bossEvent.setVisible(false);
        shownRemaining = -1L;
    }

    void setDefeating(@Nullable ResourceKey<Level> dimension, @Nullable BlockPos pos) {
        this.defeatingDimension = dimension;
        this.defeatingPos = pos != null ? pos.immutable() : null;
    }

    boolean isDefeating(ResourceKey<Level> dimension, BlockPos pos) {
        return defeatingPos != null && defeatingPos.equals(pos) && dimension.equals(defeatingDimension);
    }

    /** 表示用の状態の名前。 */
    static String describeState(@Nullable Event e) {
        return e == null ? "none" : e.state.key.toLowerCase(Locale.ROOT);
    }
}
