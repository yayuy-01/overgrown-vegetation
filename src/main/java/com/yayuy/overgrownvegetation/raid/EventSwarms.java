package com.yayuy.overgrownvegetation.raid;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import com.yayuy.overgrownvegetation.swarm.SwarmMembership;
import com.yayuy.overgrownvegetation.swarm.SwarmOrigin;
import com.yayuy.overgrownvegetation.swarm.SwarmRole;
import com.yayuy.overgrownvegetation.swarm.SwarmSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * イベント(小規模レイド・レイドのウェーブ・攻城戦の増援・生態系防衛)が G4 を<b>群れとして</b>湧かせるときの共通の道具
 * (群れ v2、2026-09-25)。
 *
 * <ul>
 *     <li>{@link #split}: 湧かせる種を「地上の群れ」「空の群れ(ヴェロフィル)」「群れに入らない種」に分ける
 *     (群れに入らない種 —— ペディキュラ・G1〜3 など —— は今までどおり 1 体ずつの個体で湧かせる)。</li>
 *     <li>{@link #batches}: 上限({@code swarm.json} の {@code maxSize}、地上 12 / 空 6)を超える頭数を、
 *     種が偏らないように複数の群れへ分ける(頭数は減らさない)。</li>
 *     <li>{@link #spawnPlaced}: 置き場所を呼ぶ側が決めた個体を群れとして湧かせる(攻城戦の増援は「着弾した高さの床」に置くので、
 *     {@link SwarmSpawner} の置き方 —— 地表 —— が使えない)。</li>
 *     <li>{@link #spawnJoining}: 既にいる群れに加わる個体を湧かせる(生態系防衛の 2 体目以降)。</li>
 * </ul>
 *
 * <p>どれも {@link SwarmSpawner} と同じ<b>順番</b>を守る: 群れの所属(attachment)とイベントの印({@link EventMobs})を
 * {@code addFreshEntity} の<b>前</b>に書く(群れの登録は {@code EntityJoinLevelEvent} で動き、その Join は
 * addFreshEntity の中で飛ぶ。レビュー指摘)。
 */
public final class EventSwarms {

    /**
     * 湧かせる種の分け方。
     *
     * @param ground      地上の群れに入る種(アルボラ・スカトゥリウム・スポロフィル・フィロセンス)
     * @param air         空の群れに入る種(ヴェロフィル)
     * @param individuals 群れに入らない種(1 体ずつの個体で湧かせる)
     */
    public record Split(List<EntityType<?>> ground, List<EntityType<?>> air, List<EntityType<?>> individuals) {
    }

    /** 呼ぶ側が決めた置き場所。 */
    public record Placement(EntityType<?> type, BlockPos pos) {
    }

    /** {@code types} を地上・空・群れに入らない種に分ける(並びは保つ)。 */
    public static Split split(List<? extends EntityType<?>> types) {
        List<EntityType<?>> ground = new ArrayList<>();
        List<EntityType<?>> air = new ArrayList<>();
        List<EntityType<?>> individuals = new ArrayList<>();
        for (EntityType<?> type : types) {
            SwarmRole role = SwarmRole.of(type);
            if (role == null) {
                individuals.add(type);
            } else if (role == SwarmRole.AIR) {
                air.add(type);
            } else {
                ground.add(type);
            }
        }
        return new Split(ground, air, individuals);
    }

    /**
     * 同じ種類({@code kind})の種の並びを、群れの上限({@code maxSize})以下の塊に分ける。
     * 塊の数は最小(頭数 ÷ 上限の切り上げ)で、役割の順(前衛 → 支援 → 遠距離)に並べてから 1 体ずつ順に配るので、
     * どの塊にも前衛が入り、支援・遠距離が 1 つの塊に固まらない。上限以下ならそのまま 1 塊。
     */
    public static List<List<EntityType<?>>> batches(List<EntityType<?>> types, Swarm.Kind kind) {
        List<List<EntityType<?>>> out = new ArrayList<>();
        if (types.isEmpty()) {
            return out;
        }
        int max = Math.max(1, DesignData.swarm().maxSize(kind));
        if (types.size() <= max) {
            out.add(new ArrayList<>(types));
            return out;
        }
        List<EntityType<?>> sorted = new ArrayList<>(types);
        sorted.sort(Comparator.comparingInt(EventSwarms::roleOrder));
        int count = (sorted.size() + max - 1) / max;
        for (int i = 0; i < count; i++) {
            out.add(new ArrayList<>());
        }
        for (int i = 0; i < sorted.size(); i++) {
            out.get(i % count).add(sorted.get(i));
        }
        return out;
    }

    private static int roleOrder(EntityType<?> type) {
        SwarmRole role = SwarmRole.of(type);
        return role != null ? role.order : Integer.MAX_VALUE;
    }

    /**
     * 呼ぶ側が置き場所まで決めた個体を湧かせる。群れに入る種は種類(地上 / 空)ごとに、上限以下の群れにまとめる
     * (1 回の呼び出しで地上の群れ・空の群れがそれぞれ 1 つ以上できうる)。群れに入らない種は印だけ付けた個体で湧かせ、
     * {@code target} がいれば今までどおり {@code setTarget} する。群れには {@code target} を群れの標的として指定する
     * ({@link SwarmSpawner#applyInitialTarget}: 攻城戦の増援・レイドのウェーブはふつうの標的、ほかは狙い)。
     *
     * <p>置き場所の確かめ(地面・水・体が入るか)は呼ぶ側の仕事(ここでは確かめない)。
     *
     * @param eventId イベントを見分ける値(攻城戦の開始時刻など)。無ければ ""
     * @return 実際に湧いた頭数
     */
    public static int spawnPlaced(ServerLevel level, List<Placement> placements, SwarmOrigin origin, String eventId,
                                  @Nullable LivingEntity target) {
        String event = eventId != null ? eventId : "";
        List<Placement> ground = new ArrayList<>();
        List<Placement> air = new ArrayList<>();
        int spawned = 0;
        for (Placement p : placements) {
            SwarmRole role = SwarmRole.of(p.type());
            if (role == null) {
                Mob mob = create(level, p.type(), p.pos(), origin);
                if (mob == null) {
                    continue;
                }
                markEvent(mob, origin, event);
                if (level.addFreshEntity(mob)) {
                    spawned++;
                    if (target != null && target.isAlive()) {
                        mob.setTarget(target);
                    }
                }
            } else if (role == SwarmRole.AIR) {
                air.add(p);
            } else {
                ground.add(p);
            }
        }
        spawned += spawnPlacedKind(level, ground, Swarm.Kind.GROUND, origin, event, target);
        spawned += spawnPlacedKind(level, air, Swarm.Kind.AIR, origin, event, target);
        return spawned;
    }

    private static int spawnPlacedKind(ServerLevel level, List<Placement> placements, Swarm.Kind kind, SwarmOrigin origin,
                                       String eventId, @Nullable LivingEntity target) {
        if (placements.isEmpty()) {
            return 0;
        }
        int max = Math.max(1, DesignData.swarm().maxSize(kind));
        int spawned = 0;
        for (int from = 0; from < placements.size(); from += max) {
            List<Placement> chunk = placements.subList(from, Math.min(placements.size(), from + max));
            UUID id = UUID.randomUUID();
            int n = chunk.size();
            int slot = 0;
            for (Placement p : chunk) {
                Mob mob = create(level, p.type(), p.pos(), origin);
                if (mob == null) {
                    continue;
                }
                // 番号は湧いた順(0..)、peak は予定の頭数(置き場所は確かめ済みなので、ほぼそのまま湧く)。
                mob.setData(ModAttachments.SWARM, new SwarmMembership(id, slot, origin, eventId, n));
                markEvent(mob, origin, eventId);
                if (level.addFreshEntity(mob)) {
                    slot++;
                    spawned++;
                }
            }
            Swarm swarm = slot > 0 ? SwarmManager.get(level, id) : null;
            if (swarm != null && target != null && target.isAlive()) {
                // 攻城戦の増援(SIEGE)はふつうの標的、それ以外は狙い(SwarmSpawner#applyInitialTarget。仮決め 2026-09-25)。
                SwarmSpawner.applyInitialTarget(swarm, target);
            }
        }
        return spawned;
    }

    /**
     * 既にいる群れ {@code swarm} に加わる個体を {@code pos} に湧かせる(所属を addFreshEntity の前に書くので、
     * 世界に入った瞬間に群れの一員になる。番号は群れの空き番号、出どころ・イベントは群れのもの)。
     * 群れの空き({@link SwarmManager#hasRoom})は呼ぶ側が先に確かめる。上限で入れなかった個体は、登録のときに
     * 所属が外れてふつうの個体になる({@link SwarmManager#get} が null)。
     *
     * @param beforeAdd 所属を書いたあと・addFreshEntity の前に呼ぶ(防衛の印など)。null 可
     * @return 湧いた個体。湧かなければ null
     */
    @Nullable
    public static Mob spawnJoining(ServerLevel level, EntityType<?> type, BlockPos pos, Swarm swarm,
                                   @Nullable Consumer<Mob> beforeAdd) {
        Mob mob = create(level, type, pos, swarm.origin());
        if (mob == null) {
            return null;
        }
        mob.setData(ModAttachments.SWARM,
                new SwarmMembership(swarm.id(), -1, swarm.origin(), swarm.eventId(), swarm.peak(), swarm.climber()));
        if (beforeAdd != null) {
            beforeAdd.accept(mob);
        }
        markEvent(mob, swarm.origin(), swarm.eventId());
        return level.addFreshEntity(mob) ? mob : null;
    }

    /** 作るだけ(世界にはまだ入れない)。Mob でなければ捨てて null。 */
    @Nullable
    private static Mob create(ServerLevel level, EntityType<?> type, BlockPos pos, SwarmOrigin origin) {
        Entity entity = type.create(level, null, pos, origin.spawnType(), false, false);
        if (entity instanceof Mob mob) {
            mob.setYRot(level.random.nextFloat() * 360.0F);
            return mob;
        }
        if (entity != null) {
            entity.discard();
        }
        return null;
    }

    /** 出どころのイベントの印(小規模レイドは狙いを先に書く —— 印が生きているかを狙いで見るため)。 */
    private static void markEvent(Mob mob, SwarmOrigin origin, String eventId) {
        if (eventId.isEmpty()) {
            return;
        }
        if (origin == SwarmOrigin.SMALL_RAID) {
            mob.setData(ModAttachments.HUNT_TARGET, eventId);
        }
        EventMobs.Kind kind = origin.eventKind();
        if (kind != null) {
            EventMobs.tag(mob, kind, eventId);
        }
    }

    private EventSwarms() {
    }
}
