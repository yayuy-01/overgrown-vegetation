package com.yayuy.overgrownvegetation.swarm;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * G4 の<b>群れ v2</b> の名簿と司令塔の拍(2026-09-25)。ワールドごとに「群れ ID → {@link Swarm}」を持つ。
 *
 * <h2>ユーザー指定(2026-09-25)</h2>
 * <ul>
 *     <li>1 群れに司令塔 1 つ(エンティティではなくサーバー側のデータ)。1 匹が先導し、残りは付いていく。
 *     後続は自分で標的を探さず、経路も引かず、うろつかず、群れの走査もしない。</li>
 *     <li>編成は湧いた時点で揃った混成(足りない種を迎えに行く動きは無い)。</li>
 *     <li>消えるときは群れごと(全員がプレイヤーから遠いとき)。</li>
 *     <li>暇なときは定期的に止まって全員で汚染を広げ、また進む。</li>
 *     <li>ヴェロフィルは空の群れを別に作る。ペディキュラは群れに入らない。</li>
 * </ul>
 * 旧い分散型の群れ({@code InvaderPack*} / {@code PackTracker})の置き換え。旧い仕組みの地図は開発ノートの「G4 の群れ(v2)」。
 *
 * <h2>名簿の作り方(実行時だけ。保存するのは一員の所属 {@link SwarmMembership})</h2>
 * <ul>
 *     <li><b>登録</b>: {@link EntityJoinLevelEvent} を<b>いちばん最後</b>(LOWEST。取り消された Join は届かない)に受ける。
 *     すでにワールドにいる個体を入れるときは {@link #join}(防衛の追加・召喚・残党の合流)。湧かせる側は所属を
 *     <b>addFreshEntity の前に</b>書く({@link SwarmSpawner}。Join は addFreshEntity の中で飛ぶ)。</li>
 *     <li><b>自己修復</b>: 一員は自分の tick から {@link #memberTick} を呼ぶ(20 tick ごと、ID で位相をずらす)。
 *     Join と Leave は対ではない(チャンクの読み込みの途中で Join だけ飛ぶ・Join 無しで追跡が始まる)ので、名簿に居なければ入り直す。</li>
 *     <li><b>Leave</b>: 群れの一覧から外して印を付けるだけ。<b>群れを名簿から消さない・選び直さない・経路を写さない</b>
 *     (discard は Leave を同期で投げるので、ここで群れを消すと司令塔の回している一覧が壊れる。レビュー指摘の CME)。
 *     空になった群れは tick の最後にまとめて消す。</li>
 * </ul>
 *
 * <h2>司令塔の拍</h2>
 * {@link LevelTickEvent.Post} で群れの写しを回し、各群れは {@code thinkIntervalTicks}(10)tick に 1 回、
 * {@code floorMod(ID のハッシュ, 間隔)} の位相で考える({@link SwarmController})。
 *
 * <h2>後始末</h2>
 * ワールドを閉じたとき・サーバーが止まったときに全部捨てる(忌避灯などの台帳と同じ。別のワールドへ古い Mob の参照を持ち越さない)。
 * 触るのはサーバースレッドだけ(クライアントの Mob を渡されたら何もしない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SwarmManager {

    /** 自己修復の間隔(tick)。 */
    private static final int SELF_HEAL_INTERVAL = 20;

    /** ワールド(ディメンション)ごとの名簿。{@link LevelSwarms#level} が今のワールドと違えば古い(捨てる)。 */
    private static final Map<ResourceKey<Level>, LevelSwarms> LEVELS = new ConcurrentHashMap<>();
    private static final List<SwarmListener> LISTENERS = new CopyOnWriteArrayList<>();

    /** 1 つのワールドの名簿と札の入れ物。 */
    private static final class LevelSwarms {
        final ServerLevel level;
        final LinkedHashMap<UUID, Swarm> swarms = new LinkedHashMap<>();
        final SwarmPaths.TokenBucket bucket = new SwarmPaths.TokenBucket();

        LevelSwarms(ServerLevel level) {
            this.level = level;
        }
    }

    @Nullable
    private static LevelSwarms existing(ServerLevel level) {
        LevelSwarms swarms = LEVELS.get(level.dimension());
        return swarms != null && swarms.level == level ? swarms : null;
    }

    private static LevelSwarms forLevel(ServerLevel level) {
        return LEVELS.compute(level.dimension(), (key, current) ->
                current != null && current.level == level ? current : new LevelSwarms(level));
    }

    static SwarmPaths.TokenBucket bucket(ServerLevel level) {
        return forLevel(level).bucket;
    }

    static SwarmTable table() {
        return DesignData.swarm();
    }

    // --- 読む ---

    /**
     * その個体の群れ。所属(attachment)があり、群れが名簿にあり、その群れの一覧に入っているときだけ。
     * クライアントの個体・群れに居ない個体は null。<b>Goal は毎 tick これで引き直す</b>(持ち続けない)。
     */
    @Nullable
    public static Swarm get(Mob mob) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return null;
        }
        SwarmMembership membership = mob.getExistingData(ModAttachments.SWARM).orElse(null);
        if (membership == null || membership.isNone()) {
            return null;
        }
        LevelSwarms swarms = existing(level);
        if (swarms == null) {
            return null;
        }
        Swarm swarm = swarms.swarms.get(membership.id());
        return swarm != null && swarm.contains(mob) ? swarm : null;
    }

    /** ID で群れを引く。無ければ null。 */
    @Nullable
    public static Swarm get(ServerLevel level, UUID id) {
        LevelSwarms swarms = existing(level);
        return swarms != null ? swarms.swarms.get(id) : null;
    }

    /** そのワールドの群れの写し。 */
    public static List<Swarm> swarms(ServerLevel level) {
        LevelSwarms swarms = existing(level);
        return swarms != null ? List.copyOf(swarms.swarms.values()) : List.of();
    }

    /** そのワールドに読み込まれている群れの数({@code phases.json} の {@code maxPacks} = 「読み込まれている群れの上限」)。 */
    public static int count(ServerLevel level) {
        LevelSwarms swarms = existing(level);
        if (swarms == null) {
            return 0;
        }
        int n = 0;
        for (Swarm swarm : swarms.swarms.values()) {
            if (!swarm.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** 種類ごとの群れの数。 */
    public static int count(ServerLevel level, Swarm.Kind kind) {
        LevelSwarms swarms = existing(level);
        if (swarms == null) {
            return 0;
        }
        int n = 0;
        for (Swarm swarm : swarms.swarms.values()) {
            if (!swarm.isEmpty() && swarm.kind() == kind) {
                n++;
            }
        }
        return n;
    }

    /**
     * このフェーズであと何群れ湧かせてよいか({@code phases.json} の {@code maxPacks}。0 なら無制限 = {@link Integer#MAX_VALUE})。
     * 旧 {@code PackTracker#budget} の置き換え。
     */
    public static int budget(ServerLevel level, int phase) {
        int max = DesignData.phases().maxPacks(phase);
        return max > 0 ? Math.max(0, max - count(level)) : Integer.MAX_VALUE;
    }

    /**
     * {@code incoming} 体を入れる空きがあるか。上限({@code maxSize})に加えて、組み直した直後({@code settleWindowTicks})と、
     * 読み込み外れたまま戻っていない一員がいる群れ(最後に外れてから {@code settleWindowTicks} まで。{@link Swarm#membersMissing})は
     * 「空きなし」とみなす(残りがまだ読み込まれていないだけかもしれない。レビュー指摘)。
     * 呼んだ側はこれを見てから {@link #join} する(join 自体は上限しか見ない)。
     */
    public static boolean hasRoom(Swarm swarm, int incoming) {
        SwarmTable table = table();
        if (swarm.size() + incoming > table.maxSize(swarm.kind())) {
            return false;
        }
        long now = swarm.level().getGameTime();
        return now - swarm.createdTick >= table.settleWindowTicks() && !swarm.membersMissing(now);
    }

    // --- 登録 ---

    /** 読み込み・湧いた個体の登録。取り消された Join は届かない(LOWEST・receiveCanceled なし)。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (!mob.hasData(ModAttachments.SWARM)) {
            return;
        }
        register(level, mob);
    }

    /**
     * 所属(attachment)を読んで群れへ入れる。群れが無ければ作る。
     * 種類の違う群れ・上限に達した群れには入れず、所属を外す(ふつうの個体に戻す。古いワールドの大きすぎた群れの余り)。
     */
    @Nullable
    static Swarm register(ServerLevel level, Mob mob) {
        SwarmMembership membership = mob.getExistingData(ModAttachments.SWARM).orElse(null);
        if (membership == null || membership.isNone()) {
            return null;
        }
        SwarmRole role = SwarmRole.of(mob);
        if (role == null) {
            mob.removeData(ModAttachments.SWARM);
            return null;
        }
        Swarm.Kind kind = role == SwarmRole.AIR ? Swarm.Kind.AIR : Swarm.Kind.GROUND;
        LevelSwarms swarms = forLevel(level);
        Swarm swarm = swarms.swarms.get(membership.id());
        if (swarm != null && swarm.contains(mob)) {
            return swarm;
        }
        if (swarm != null && swarm.kind() != kind) {
            OvergrownVegetation.LOGGER.debug("群れ {} は {} なので {} を入れない", swarm.shortId(), swarm.kind(), mob);
            mob.removeData(ModAttachments.SWARM);
            return null;
        }
        SwarmTable table = table();
        if (swarm != null && swarm.size() >= table.maxSize(kind)) {
            OvergrownVegetation.LOGGER.debug("群れ {} は上限なので {} を群れから外す", swarm.shortId(), mob);
            mob.removeData(ModAttachments.SWARM);
            return null;
        }
        // 別の群れの一覧に残っていれば外す(所属を書き換えて移った個体)。
        detachRuntime(level, mob, membership.id());
        long now = level.getGameTime();
        if (swarm == null) {
            swarm = new Swarm(membership.id(), level, kind, membership.origin(), membership.eventId(), membership.climber(),
                    now);
            swarms.swarms.put(membership.id(), swarm);
        }
        int slot = membership.slot();
        if (slot < 0) {
            slot = nextSlot(swarm);
            mob.setData(ModAttachments.SWARM, membership.withSlot(slot));
        }
        Swarm.Member member = new Swarm.Member(mob, role, slot);
        member.active = mob.isAlive() && level.isPositionEntityTicking(mob.blockPosition());
        swarm.members.put(mob, member);
        swarm.noteArrival(mob.getUUID());
        swarm.dirty = true;
        swarm.peak = Math.max(swarm.peak, Math.max(membership.peak(), swarm.members.size()));
        SwarmTargeting.onRegistered(swarm, mob);
        for (SwarmListener listener : LISTENERS) {
            listener.onMemberRegistered(swarm, mob);
        }
        return swarm;
    }

    /** 新しい番号 = 読み込まれている一員の番号の最大 + 1。 */
    private static int nextSlot(Swarm swarm) {
        int max = -1;
        for (Swarm.Member m : swarm.members.values()) {
            max = Math.max(max, m.slot);
        }
        return max + 1;
    }

    /** {@code keep} 以外の群れの一覧からその個体を外す(実行時だけ。所属は触らない)。 */
    private static void detachRuntime(ServerLevel level, Mob mob, @Nullable UUID keep) {
        LevelSwarms swarms = existing(level);
        if (swarms == null) {
            return;
        }
        for (Swarm swarm : swarms.swarms.values()) {
            if (swarm.id().equals(keep)) {
                continue;
            }
            if (swarm.members.remove(mob) != null) {
                swarm.dirty = true;
                if (swarm.vanguard == mob) {
                    swarm.vanguard = null;
                }
                // 元の群れの先導役だった(残党の合流など)。移った先では後続なので、歩く速さの縛りを外す。
                SwarmController.removePace(mob);
            }
        }
    }

    /**
     * すでにワールドにいる個体を群れ {@code id} へ入れる(所属を書き、その場で登録する)。
     * 防衛の追加・スポロフィルの召喚・残党の合流で使う。群れが無ければ作る(その個体が最初の一員)。
     *
     * @param slot 番号。負なら空いている番号(読み込まれている一員の最大 + 1)
     * @return 入った群れ。種類の違い・上限で入れなければ null(所属は元のまま)
     */
    @Nullable
    public static Swarm join(Mob mob, UUID id, int slot, SwarmOrigin origin, String eventId) {
        if (!(mob.level() instanceof ServerLevel level) || mob.isRemoved()) {
            return null;
        }
        SwarmRole role = SwarmRole.of(mob);
        if (role == null) {
            return null;
        }
        Swarm.Kind kind = role == SwarmRole.AIR ? Swarm.Kind.AIR : Swarm.Kind.GROUND;
        Swarm target = get(level, id);
        if (target != null) {
            if (target.contains(mob)) {
                return target;
            }
            if (target.kind() != kind || target.size() >= table().maxSize(kind)) {
                return null;
            }
        }
        SwarmMembership previous = mob.getExistingData(ModAttachments.SWARM).orElse(null);
        int assigned = slot >= 0 ? slot : (target != null ? nextSlot(target) : 0);
        int peak = target != null ? Math.max(target.peak, target.size() + 1) : 1;
        // 登る群れへ加わるなら登る印も写す(読み込み直しでこの個体が最初に登録されても、群れが登る群れのままになるように)。
        boolean climber = target != null && target.climber();
        mob.setData(ModAttachments.SWARM, new SwarmMembership(id, assigned, origin, eventId, peak, climber));
        Swarm joined = register(level, mob);
        if (joined == null && previous != null && !previous.isNone()) {
            // 入れなかった。元の所属へ戻す。
            mob.setData(ModAttachments.SWARM, previous);
            register(level, mob);
        }
        return joined;
    }

    /** 群れ {@code swarm} へ入れる(出どころ・イベントはその群れのもの)。 */
    @Nullable
    public static Swarm join(Mob mob, Swarm swarm) {
        return join(mob, swarm.id(), -1, swarm.origin(), swarm.eventId());
    }

    /**
     * 群れを抜けてふつうの個体に戻る(所属を消す)。付いて来られなかった自然の群れの一員など。
     * 群れの標的を渡されていた場合も標的はそのまま(個体の AI が引き継ぐ)。消え方は普段の個体の規則になる。
     */
    public static void leave(Mob mob) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        detachRuntime(level, mob, null);
        mob.removeData(ModAttachments.SWARM);
        SwarmController.removePace(mob);
    }

    /**
     * 一員の tick から毎 tick 呼ぶ(中で 20 tick に 1 回、ID で位相をずらして自己修復する)。
     * 群れの attachment を持っていない個体では何もしない。
     */
    public static void memberTick(Mob mob) {
        if (mob.level().isClientSide() || Math.floorMod(mob.tickCount + mob.getId(), SELF_HEAL_INTERVAL) != 0) {
            return;
        }
        selfHeal(mob);
    }

    /** 所属があるのに名簿に居なければ入り直す({@link #memberTick} から。すぐに確かめたいときは直接呼んでよい)。 */
    public static void selfHeal(Mob mob) {
        if (!(mob.level() instanceof ServerLevel level) || mob.isRemoved()) {
            return;
        }
        SwarmMembership membership = mob.getExistingData(ModAttachments.SWARM).orElse(null);
        if (membership == null || membership.isNone()) {
            return;
        }
        Swarm swarm = get(level, membership.id());
        if (swarm != null && swarm.contains(mob)) {
            return;
        }
        register(level, mob);
    }

    /**
     * 古いワールドの群れ(NBT の {@code PackId})を引き継ぐ。読み込み({@code readAdditionalSaveData})から呼ぶ。
     * 群れの attachment をまだ持っていない個体だけ、群れ ID = PackId・出どころ NATURAL・番号は登録のときに振る・
     * peak は不明(登録のたびの頭数で埋まる)で所属を書く。上限を超えた余りは登録のときにふつうの個体に戻る。
     * 旧い群れはヴェロフィルも同じ ID だったので、ヴェロフィルは PackId から決まる別の ID の空の群れにする。
     */
    public static void adoptLegacyPack(Mob mob, @Nullable UUID packId) {
        if (packId == null || mob.hasData(ModAttachments.SWARM)) {
            return;
        }
        SwarmRole role = SwarmRole.of(mob);
        if (role == null) {
            return;
        }
        UUID id = role == SwarmRole.AIR
                ? UUID.nameUUIDFromBytes(("overgrown_vegetation:air_swarm:" + packId).getBytes(StandardCharsets.UTF_8))
                : packId;
        mob.setData(ModAttachments.SWARM, new SwarmMembership(id, -1, SwarmOrigin.NATURAL, "", 0));
    }

    /**
     * 一員が離れた(死んだ・消された・チャンクごと読み込み外れた・別のディメンションへ)。一覧から外して印を付けるだけ。
     * ここでは群れを名簿から消さない・選び直さない・経路を写さない(CME)。
     */
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        SwarmMembership membership = mob.getExistingData(ModAttachments.SWARM).orElse(null);
        if (membership == null || membership.isNone()) {
            return;
        }
        LevelSwarms swarms = existing(level);
        Swarm swarm = swarms != null ? swarms.swarms.get(membership.id()) : null;
        if (swarm == null || swarm.members.remove(mob) == null) {
            return;
        }
        swarm.dirty = true;
        Entity.RemovalReason reason = mob.getRemovalReason();
        if (reason == null || reason == Entity.RemovalReason.UNLOADED_TO_CHUNK) {
            // 理由 null = チャンクが見えない範囲へ落ちた(シミュレーション距離の縁)。戻れば登録で外れる。
            swarm.noteDeparture(mob.getUUID(), level.getGameTime());
        }
        if (swarm.vanguard == mob) {
            // 選び直しは次に考えるとき(区間の写しもそこで)。
            swarm.vanguard = null;
        }
        SwarmController.removePace(mob);
    }

    /**
     * 一員が殴られた。一員の {@code hurt} から(実際にダメージを受けたときに)呼ぶ。
     * 殴った相手が役割の距離にいればその一員だけ反撃し、群れの標的は「無い」か「相手がプレイヤーで、今の標的が反撃より
     * 下の優先(ふつうのプレイヤー・獲物・グループ外)」のときだけ替える({@link SwarmTargeting#onMemberHurt})。
     */
    public static void onMemberHurt(Mob mob, DamageSource source) {
        Swarm swarm = get(mob);
        if (swarm != null) {
            SwarmTargeting.onMemberHurt(swarm, mob, source);
        }
    }

    // --- 拍 ---

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        LevelSwarms swarms = existing(level);
        if (swarms == null || swarms.swarms.isEmpty()) {
            return;
        }
        // 時間が止まっている(/tick freeze)間は考えない(ゲーム時刻が進まず、同じ群れが毎 tick 考えてしまう)。
        if (!level.tickRateManager().runsNormally()) {
            return;
        }
        long now = level.getGameTime();
        int interval = table().thinkIntervalTicks();
        long phaseNow = Math.floorMod(now, (long) interval);
        for (Swarm swarm : List.copyOf(swarms.swarms.values())) {
            if (swarm.isEmpty() || Math.floorMod(swarm.id().hashCode(), interval) != phaseNow) {
                continue;
            }
            SwarmController.think(swarm, now);
        }
        // Leave の処理では群れを消さない。空になった群れはここでまとめて消す。
        Iterator<Swarm> it = swarms.swarms.values().iterator();
        while (it.hasNext()) {
            Swarm swarm = it.next();
            if (swarm.isEmpty()) {
                it.remove();
                for (SwarmListener listener : LISTENERS) {
                    listener.onSwarmRemoved(swarm);
                }
            }
        }
    }

    // --- 後始末 ---

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LEVELS.computeIfPresent(level.dimension(), (key, current) -> current.level == level ? null : current);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LEVELS.clear();
        SwarmPaths.resetCounters();
    }

    // --- 組み直しの口 ---

    /** 組み直しの口を登録する(同じものは 1 回だけ)。 */
    public static void addListener(SwarmListener listener) {
        if (!LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(SwarmListener listener) {
        LISTENERS.remove(listener);
    }

    static void fireRebuilt(Swarm swarm) {
        for (SwarmListener listener : LISTENERS) {
            listener.onSwarmRebuilt(swarm);
        }
    }

    private SwarmManager() {
    }
}
