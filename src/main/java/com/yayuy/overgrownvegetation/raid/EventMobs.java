package com.yayuy.overgrownvegetation.raid;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity;
import com.yayuy.overgrownvegetation.mixin.EntityPersistentDataAccessor;
import com.yayuy.overgrownvegetation.mixin.MobPersistenceAccessor;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.siege.SiegeData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * イベントで湧いた Mob の<b>出どころの印</b>と、イベントが終わったあとの<b>永続の後始末</b>
 * (2026-09-24、ユーザー承認「イベント mob の後始末」)。
 *
 * <p>レイドのウェーブ・攻城戦の反撃の増援・小規模レイドの一団は、
 * 湧いた瞬間に {@code setPersistenceRequired} で永続にしていたが、<b>終わっても外していなかった</b>。
 * 永続の個体はバニラの「遠すぎる」でも消えないので、レイドや攻城戦のたびに溜まり続け、
 * チャンクごと保存されていた(長く遊んだワールドで {@code /kill @e} が 783 体を消した件)。
 *
 * <h2>印</h2>
 * 永続データ({@code getPersistentData()})の {@link #KEY} に 4 つを書く(新しい attachment は作らない):
 * <ul>
 *     <li>{@code kind} … どのイベントか({@link Kind})</li>
 *     <li>{@code id} … そのイベントを見分ける値(ビーコンの UUID / 攻城戦の開始時刻 / 狙うプレイヤーの UUID)</li>
 *     <li>{@code wasPersistent} … 印を付ける前から永続だったか(戻すときはこの値へ)</li>
 *     <li>{@code since} … 印を付けたゲーム内時刻(2026-09-25)。小規模レイドの狙いの期限({@link SmallRaidHunt#expired})</li>
 * </ul>
 *
 * <h2>後始末</h2>
 * <ul>
 *     <li><b>終わった瞬間</b>: 読み込まれている印付きの個体の永続を元へ戻し、印を消す({@link #releaseOrigin})。
 *     ビーコンが消えた・レイドの台帳から外れた({@link RaidData})/ 攻城戦が止まった({@link SiegeData#stop})/
 *     小規模レイドの狙いが外れた({@link SmallRaidHunt#giveUp})とき。</li>
 *     <li><b>読み込まれていなかった個体</b>: チャンクごと読み込まれた瞬間({@link #onJoin})に、
 *     出どころがもう終わっていれば同じように戻す。</li>
 * </ul>
 * 戻したあとは<b>その種の普段の規則</b>で消える(グループ4 なら {@link com.yayuy.overgrownvegetation.entity.InvaderDespawn})。
 *
 * <p>印を持つのは永続の個体だけ(戻すときに印も消す)なので、探すときは永続の Mob だけを見る。
 * 永続データは {@code getPersistentData()} で読むと空の入れ物が作られて以後ずっと保存されるので、
 * 探すときは {@link EntityPersistentDataAccessor} で<b>作らずに</b>覗く(汚染シダなど、印の無い永続の個体に何も残さない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class EventMobs {

    /** 永続データのキー。 */
    private static final String KEY = "overgrown_vegetation_event_origin";
    private static final String KIND = "kind";
    private static final String ID = "id";
    private static final String WAS_PERSISTENT = "wasPersistent";
    /** 印を付けたゲーム内時刻(2026-09-25)。小規模レイドの狙いの期限({@link SmallRaidHunt#expired})に使う。 */
    private static final String SINCE = "since";

    /** どのイベントで湧いたか。 */
    public enum Kind {
        /** レイドビーコンのウェーブ。id はビーコンの UUID。 */
        NEXUS("nexus"),
        /** 攻城戦の反撃の増援。id は攻城戦の開始時刻({@link SiegeData#startGameTime})。 */
        SIEGE("siege"),
        /** 小規模レイドの一団。id は狙うプレイヤーの UUID({@link ModAttachments#HUNT_TARGET} と同じ値)。 */
        SMALL_RAID("small_raid"),
        /**
         * TD イベント(森の侵攻、2026-09-25)の攻め手。id はイベントの UUID({@code td/TdData})。
         * 生きている攻め手の数はこの印で数える({@code td/TdTicker}。群れの所属は組み直すたびに変わりうるので使わない)。
         */
        TD("td");

        private final String key;

        Kind(String key) {
            this.key = key;
        }
    }

    /**
     * 湧かせた(送り出した)個体に印を付けて永続にする。{@code setPersistenceRequired()} の代わりに呼ぶ。
     * 出どころがもう終わっていれば(攻城戦が止まったあとに着弾した反撃など)、その場で戻す。
     */
    public static void tag(Mob mob, Kind kind, String id) {
        CompoundTag data = mob.getPersistentData();
        // 2 度目の印でも「イベントの前に永続だったか」は最初の値を持ち越す。
        boolean wasPersistent = data.contains(KEY, Tag.TAG_COMPOUND)
                ? data.getCompound(KEY).getBoolean(WAS_PERSISTENT)
                : mob.isPersistenceRequired();
        CompoundTag origin = new CompoundTag();
        origin.putString(KIND, kind.key);
        origin.putString(ID, id);
        origin.putBoolean(WAS_PERSISTENT, wasPersistent);
        origin.putLong(SINCE, mob.level().getGameTime());
        data.put(KEY, origin);
        mob.setPersistenceRequired();
        if (mob.level() instanceof ServerLevel level && !isActive(level, mob, kind, id)) {
            release(mob);
        }
    }

    /** {@code level} に読み込まれている、出どころが {@code kind}/{@code id} の生きた個体の数。 */
    public static int count(ServerLevel level, Kind kind, String id) {
        int n = 0;
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Mob mob && mob.isAlive() && matches(mob, kind, id)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 出どころが {@code kind}/{@code id} の、読み込まれている個体を全部戻す(全ディメンション)。
     * {@code id} が null なら、その種類のイベントのものを id を問わず全部。
     */
    public static void releaseOrigin(MinecraftServer server, Kind kind, @Nullable String id) {
        for (ServerLevel level : server.getAllLevels()) {
            List<Mob> hits = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Mob mob && matches(mob, kind, id)) {
                    hits.add(mob);
                }
            }
            for (Mob mob : hits) {
                release(mob);
            }
        }
    }

    /**
     * その個体が出どころ {@code kind}/{@code id} の印を持っているか({@code id} が null なら id を問わない)。
     * 印は永続の個体にしか付かないので、永続でなければ見ずに false(永続データの入れ物も作らない)。
     * TD の攻め手の数え({@code td/TdTicker})と、組み直した群れの攻城の戻し(2026-09-25)に使う。
     */
    public static boolean has(Mob mob, Kind kind, @Nullable String id) {
        return matches(mob, kind, id);
    }

    /** 小規模レイドの狙いが外れた({@link SmallRaidHunt#giveUp})。その個体だけ戻す。 */
    public static void onHuntCleared(Mob mob) {
        if (matches(mob, Kind.SMALL_RAID, null)) {
            release(mob);
        }
    }

    /**
     * {@code kind} の印を付けたゲーム内時刻(2026-09-25)。印が無い・別のイベントの印・時刻の無い古い印なら -1。
     * 永続かどうかは問わない(印さえ残っていれば読む)。
     */
    public static long taggedAt(Mob mob, Kind kind) {
        CompoundTag origin = originOf(mob);
        if (origin == null || !kind.key.equals(origin.getString(KIND)) || !origin.contains(SINCE, Tag.TAG_LONG)) {
            return -1L;
        }
        return origin.getLong(SINCE);
    }

    /**
     * 永続を {@code persistent} に戻す。
     * <b>名札を付けられた個体は永続のまま</b> —— 名札は永続を立てるだけなので、外すと名前ごと消えてしまう。
     */
    public static void restorePersistence(Mob mob, boolean persistent) {
        ((MobPersistenceAccessor) mob).overgrown_vegetation$setPersistenceRequired(persistent || mob.hasCustomName());
    }

    /** チャンクごと読み込まれた印付きの個体は、出どころがもう終わっていれば戻す。 */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.loadedFromDisk() || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof Mob mob) || !candidate(mob)) {
            return;
        }
        CompoundTag origin = originOf(mob);
        if (origin == null) {
            return;
        }
        Kind kind = kindOf(origin.getString(KIND));
        String id = origin.getString(ID);
        if (kind == Kind.SMALL_RAID && !origin.contains(SINCE, Tag.TAG_LONG)) {
            // 2026-09-25 より前に付けた印には時刻が無い。狙いの期限は、読み込まれたこの時点から数える。
            origin.putLong(SINCE, level.getGameTime());
        }
        if (kind == null || !isActive(level, mob, kind, id)) {
            if (kind == Kind.SMALL_RAID && id.equals(mob.getExistingData(ModAttachments.HUNT_TARGET).orElse(""))) {
                // 狙いは残っているのに期限切れ(未ロードの間に逃げ切られた一団。報告 1978)。狙いごと捨てる(永続も外れる)。
                SmallRaidHunt.giveUp(mob);
            } else {
                release(mob);
            }
        }
    }

    // --- 内部 ---

    /** 印を消して永続を元へ戻す。 */
    private static void release(Mob mob) {
        CompoundTag data = ((EntityPersistentDataAccessor) mob).overgrown_vegetation$getPersistentDataOrNull();
        if (data == null || !data.contains(KEY, Tag.TAG_COMPOUND)) {
            return;
        }
        boolean wasPersistent = data.getCompound(KEY).getBoolean(WAS_PERSISTENT);
        data.remove(KEY);
        restorePersistence(mob, wasPersistent);
    }

    /** そのイベントがまだ続いているか。 */
    private static boolean isActive(ServerLevel level, Mob mob, Kind kind, String id) {
        return switch (kind) {
            case NEXUS -> nexusActive(level, id);
            case SIEGE -> {
                SiegeData siege = SiegeData.get(level);
                yield siege.isActive() && Long.toString(siege.startGameTime()).equals(id);
            }
            // 狙いが残っていて、期限({@link SmallRaidHunt#MAX_HUNT_TICKS}、2026-09-25)が切れていない間だけ続く。
            case SMALL_RAID -> !id.isEmpty()
                    && id.equals(mob.getExistingData(ModAttachments.HUNT_TARGET).orElse(""))
                    && !SmallRaidHunt.expired(mob, level.getGameTime());
            // 予告中・侵攻中の TD イベントの id と同じ間だけ続く(終わった・中止した侵攻の攻め手は読み込まれた瞬間に戻す)。
            case TD -> com.yayuy.overgrownvegetation.td.TdEvents.isLiveEvent(level, id);
        };
    }

    /**
     * ビーコンのレイドが続いているか。台帳({@link RaidData})に載っていれば続いている。
     * 台帳に載らないビーコン(コマンドで直に出したもの)も、生きて読み込まれていれば続いているとみなす。
     */
    private static boolean nexusActive(ServerLevel level, String id) {
        UUID nexusId;
        try {
            nexusId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (RaidData.ActiveRaid raid : RaidData.get(level).getActiveRaids()) {
            if (raid.nexusId().equals(nexusId)) {
                return true;
            }
        }
        return level.getEntity(nexusId) instanceof OvergrownNexusEntity nexus && nexus.isAlive();
    }

    private static boolean matches(Mob mob, Kind kind, @Nullable String id) {
        if (!candidate(mob)) {
            return false;
        }
        CompoundTag origin = originOf(mob);
        return origin != null && kind.key.equals(origin.getString(KIND))
                && (id == null || id.equals(origin.getString(ID)));
    }

    /** 印を持ちうる個体か。印付きの個体は必ず永続なので、永続でなければ見るまでもない。 */
    private static boolean candidate(Mob mob) {
        return mob.isPersistenceRequired();
    }

    /** 印。無ければ null(永続データが無い個体に空の入れ物を作らない)。 */
    @Nullable
    private static CompoundTag originOf(Mob mob) {
        CompoundTag data = ((EntityPersistentDataAccessor) mob).overgrown_vegetation$getPersistentDataOrNull();
        return data != null && data.contains(KEY, Tag.TAG_COMPOUND) ? data.getCompound(KEY) : null;
    }

    @Nullable
    private static Kind kindOf(String key) {
        for (Kind kind : Kind.values()) {
            if (kind.key.equals(key)) {
                return kind;
            }
        }
        return null;
    }

    private EventMobs() {
    }
}
