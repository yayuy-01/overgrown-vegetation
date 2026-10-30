package com.yayuy.overgrownvegetation.swarm;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.SpawnFit;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.raid.EventMobs;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 群れを<b>まとめて湧かせる</b>(群れ v2、2026-09-25)。生態系スポーン・防衛・小規模レイド・レイド・攻城戦の増援・TD・テスト・計測が使う。
 *
 * <p>肝は順番(レビュー指摘): 群れの所属(attachment)とイベントの印({@link EventMobs})を <b>addFreshEntity の前に</b>書く。
 * 群れの登録({@link SwarmManager#onJoin})と TD の頭数の数えは {@code EntityJoinLevelEvent} で動き、その Join は
 * addFreshEntity の<b>中</b>で飛ぶ —— 今までの湧かせ方(足してから印を付ける)を真似ると、登録のときに所属も印も無い。
 *
 * <p>置き場所は今までの湧きと同じ: 中心のまわり(頭数に応じて ±3〜)で、木の天蓋・幹の下の本物の地面
 * ({@link TreeCanopyGroundFinder})、足元と頭が乾いていて、体がまるごと入る({@link SpawnFit})ところ。
 * 読み込み済みのチャンクだけを見る。バイオームは見ない(呼ぶ側が決める)。
 * 番号は湧いた順に 0..n-1、peak は湧いた頭数。
 */
public final class SwarmSpawner {

    /** 1 体あたり置き場所を探す回数。 */
    private static final int TRIES_PER_MEMBER = 8;

    /**
     * 湧かせた結果。
     *
     * @param id      群れの ID(1 体も湧かなければ null)
     * @param members 湧いた個体(湧いた順)
     */
    public record Result(@Nullable UUID id, List<Mob> members) {
        public boolean spawned() {
            return this.id != null && !this.members.isEmpty();
        }

        /** 湧いた群れ(登録済み)。1 体も湧かなければ null。 */
        @Nullable
        public Swarm swarm(ServerLevel level) {
            return this.id != null ? SwarmManager.get(level, this.id) : null;
        }
    }

    /** {@link #spawn(ServerLevel, BlockPos, List, SwarmOrigin, String, LivingEntity, Consumer)} の、足す前の処理が無い版。 */
    public static Result spawn(ServerLevel level, BlockPos center, List<? extends EntityType<?>> types, SwarmOrigin origin,
                               String eventId, @Nullable LivingEntity initialTarget) {
        return spawn(level, center, types, origin, eventId, initialTarget, null);
    }

    /**
     * {@code types} を 1 つの群れとして {@code center} のまわりに湧かせる。
     *
     * <ul>
     *     <li>群れの種類は最初の種で決まる(ヴェロフィルなら空の群れ)。違う種類の種・群れに入らない種は湧かせない。</li>
     *     <li>頭数は {@code maxSize} で切る。</li>
     *     <li>足す前に: 所属 → {@code beforeAdd}(呼ぶ側の印など)→ 小規模レイドなら {@code hunt_target} = eventId →
     *     出どころの {@link EventMobs} の印(eventId が空なら付けない)。</li>
     *     <li>湧いたあと、{@code initialTarget} があれば群れの標的に指定する({@link #applyInitialTarget}: 出どころにより
     *     狙い {@link Swarm#commandTarget} か、ふつうの標的 {@link Swarm#offerTarget})。</li>
     * </ul>
     *
     * @param eventId   イベントを見分ける値(無ければ "")。小規模レイドは狙うプレイヤーの UUID
     * @param beforeAdd 所属を書いたあと・addFreshEntity の前に呼ぶ(TD の印など)。null 可
     */
    public static Result spawn(ServerLevel level, BlockPos center, List<? extends EntityType<?>> types, SwarmOrigin origin,
                               String eventId, @Nullable LivingEntity initialTarget, @Nullable Consumer<Mob> beforeAdd) {
        String event = eventId != null ? eventId : "";
        List<EntityType<?>> planned = plan(types, EntityType::getKey, t -> t);
        if (planned.isEmpty()) {
            return new Result(null, List.of());
        }
        UUID id = UUID.randomUUID();
        RandomSource random = level.random;
        int spread = 3 + planned.size() / 4;
        List<Mob> spawned = new ArrayList<>(planned.size());
        for (EntityType<?> type : planned) {
            Mob mob = spawnOne(level, type, center, spread, random, id, spawned.size(), planned.size(), origin, event, beforeAdd);
            if (mob != null) {
                spawned.add(mob);
            }
        }
        return finish(level, id, spawned, planned.size(), initialTarget);
    }

    /**
     * 湧かせる位置の決まった 1 体(足元の座標と向き)。{@link #spawnPlaced} へ渡す。
     *
     * @param yaw 向き(度)
     */
    public record Placement(EntityType<?> type, double x, double y, double z, float yaw) {
    }

    /** 置き場所が埋まっていたら上へずらして探す最大の高さ(ブロック)。仮決め 2026-09-25。 */
    private static final int PLACED_MAX_RISE = 12;
    /** 上へずらす刻み(ブロック)。 */
    private static final int PLACED_RISE_STEP = 2;

    /**
     * {@code placements} を 1 つの群れとして、<b>決まった位置</b>に湧かせる(地面を探さない)。TD の空の群れを、遠景の身代わりの絵と
     * 同じ隊列・高さ・向きで出すため(2026-09-25 のユーザー報告「ヴェロフィルが mob になった瞬間、絵は空中なのに地面に湧く」)。
     *
     * <ul>
     *     <li>群れの種類・頭数の上限・足す前の順番(所属 → {@code beforeAdd} → 印 → addFreshEntity)は {@link #spawn} と同じ。</li>
     *     <li>体がまるごと入らなければ({@link SpawnFit#fitsAt}){@value #PLACED_RISE_STEP} ブロックずつ上へ、{@value #PLACED_MAX_RISE}
     *     ブロックまで探す(大きな木の梢など)。それでも入らない・チャンクが読み込まれていない一員は湧かせない。</li>
     * </ul>
     */
    public static Result spawnPlaced(ServerLevel level, List<Placement> placements, SwarmOrigin origin, String eventId,
                                     @Nullable Consumer<Mob> beforeAdd) {
        String event = eventId != null ? eventId : "";
        List<Placement> planned = plan(placements, p -> EntityType.getKey(p.type()), Placement::type);
        if (planned.isEmpty()) {
            return new Result(null, List.of());
        }
        UUID id = UUID.randomUUID();
        List<Mob> spawned = new ArrayList<>(planned.size());
        for (Placement p : planned) {
            Mob mob = spawnPlacedOne(level, p, id, spawned.size(), planned.size(), origin, event, beforeAdd);
            if (mob != null) {
                spawned.add(mob);
            }
        }
        return finish(level, id, spawned, planned.size(), null);
    }

    /**
     * 湧かせる予定を絞る: 群れに入らない種・最初の種と種類(地上 / 空)が違う種を外し、頭数を {@code maxSize} で切る。
     * 群れの種類は最初の種で決まる。空なら何も湧かせない。
     */
    private static <T> List<T> plan(List<? extends T> items, java.util.function.Function<T, Object> name,
                                    java.util.function.Function<T, EntityType<?>> typeOf) {
        List<T> planned = new ArrayList<>();
        Swarm.Kind kind = null;
        for (T item : items) {
            SwarmRole role = SwarmRole.of(typeOf.apply(item));
            if (role == null) {
                OvergrownVegetation.LOGGER.warn("群れに入らない種は群れとして湧かせません: {}", name.apply(item));
                continue;
            }
            Swarm.Kind k = role == SwarmRole.AIR ? Swarm.Kind.AIR : Swarm.Kind.GROUND;
            if (kind == null) {
                kind = k;
            } else if (k != kind) {
                OvergrownVegetation.LOGGER.warn("地上と空の種は同じ群れにしません(空の群れは別に湧かせる): {}", name.apply(item));
                continue;
            }
            planned.add(item);
        }
        if (kind == null) {
            return planned;
        }
        int max = SwarmManager.table().maxSize(kind);
        return planned.size() > max ? new ArrayList<>(planned.subList(0, max)) : planned;
    }

    /** 湧いたあと: peak を実際の頭数に合わせ、標的を渡す。1 体も湧かなければ空の結果。 */
    private static Result finish(ServerLevel level, UUID id, List<Mob> spawned, int planned, @Nullable LivingEntity initialTarget) {
        if (spawned.isEmpty()) {
            return new Result(null, List.of());
        }
        Swarm swarm = SwarmManager.get(level, id);
        if (swarm != null) {
            // 予定より少なく湧いたら peak を実際の頭数に合わせる(残党の判定が予定の頭数で動かないように)。
            swarm.peak = spawned.size();
            if (spawned.size() < planned) {
                for (Mob mob : spawned) {
                    mob.getExistingData(ModAttachments.SWARM)
                            .ifPresent(m -> mob.setData(ModAttachments.SWARM, m.withPeak(spawned.size())));
                }
            }
            swarm.peakWritten = spawned.size();
            if (initialTarget != null && initialTarget.isAlive()) {
                applyInitialTarget(swarm, initialTarget);
            }
        }
        return new Result(id, List.copyOf(spawned));
    }

    /**
     * 湧かせた側が指定した標的を群れへ渡す。出どころで扱いを分ける({@link SwarmOrigin#huntsInitialTarget}):
     * 防衛・小規模レイド・召喚・TD・テスト・計測は狙い({@link Swarm#commandTarget}、プレイヤーなら距離の上限なし)、
     * レイドのウェーブ・攻城戦の増援はふつうの標的({@link Swarm#offerTarget}、追う距離はふつうのプレイヤーと同じ。
     * 仮決め 2026-09-25、レビュー指摘)。{@code raid/EventSwarms} の置き場所を決めた湧かせ方も同じ口を通す。
     */
    public static void applyInitialTarget(Swarm swarm, LivingEntity target) {
        if (swarm.origin().huntsInitialTarget()) {
            swarm.commandTarget(target);
        } else {
            swarm.offerTarget(target);
        }
    }

    /**
     * フェーズ {@code phase} の編成({@code swarm.json} の templates)を振って湧かせる。
     *
     * @param mainCount 主力(先頭の行)の頭数の希望。null なら振る
     */
    public static Result spawnTemplate(ServerLevel level, BlockPos center, int phase, Swarm.Kind kind, SwarmOrigin origin,
                                       String eventId, @Nullable LivingEntity initialTarget, @Nullable Integer mainCount,
                                       @Nullable Consumer<Mob> beforeAdd) {
        SwarmTable table = SwarmManager.table();
        SwarmTable.Template template = table.template(phase, kind);
        if (template == null) {
            return new Result(null, List.of());
        }
        List<EntityType<?>> types = template.roll(level.random, mainCount, table.maxSize(kind));
        return spawn(level, center, types, origin, eventId, initialTarget, beforeAdd);
    }

    @Nullable
    private static Mob spawnOne(ServerLevel level, EntityType<?> type, BlockPos center, int spread, RandomSource random,
                                UUID id, int slot, int planned, SwarmOrigin origin, String eventId,
                                @Nullable Consumer<Mob> beforeAdd) {
        for (int attempt = 0; attempt < TRIES_PER_MEMBER; attempt++) {
            int x = center.getX() + (attempt == 0 && slot == 0 ? 0 : random.nextInt(spread * 2 + 1) - spread);
            int z = center.getZ() + (attempt == 0 && slot == 0 ? 0 : random.nextInt(spread * 2 + 1) - spread);
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
                continue;
            }
            BlockPos pos = TreeCanopyGroundFinder.findSpawnSurface(level, x, z);
            if (pos == null || Math.abs(pos.getY() - center.getY()) > MAX_DY) {
                continue;
            }
            if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.above()).isEmpty()) {
                continue;
            }
            if (!SpawnFit.fits(level, type, pos)) {
                continue;
            }
            Entity entity = type.create(level, null, pos, origin.spawnType(), false, false);
            if (!(entity instanceof Mob mob)) {
                if (entity != null) {
                    entity.discard();
                }
                return null;
            }
            mob.setYRot(random.nextFloat() * 360.0F);
            return addMember(level, mob, id, slot, planned, origin, eventId, beforeAdd);
        }
        return null;
    }

    /** {@link #spawnPlaced} の 1 体: 決まった位置(埋まっていれば少し上)に、決まった向きで。 */
    @Nullable
    private static Mob spawnPlacedOne(ServerLevel level, Placement p, UUID id, int slot, int planned, SwarmOrigin origin,
                                      String eventId, @Nullable Consumer<Mob> beforeAdd) {
        if (level.getChunkSource().getChunkNow(net.minecraft.util.Mth.floor(p.x()) >> 4,
                net.minecraft.util.Mth.floor(p.z()) >> 4) == null) {
            return null;
        }
        double y = p.y();
        boolean fits = false;
        for (int rise = 0; rise <= PLACED_MAX_RISE && y + rise < level.getMaxBuildHeight(); rise += PLACED_RISE_STEP) {
            if (SpawnFit.fitsAt(level, p.type(), p.x(), y + rise, p.z())) {
                y += rise;
                fits = true;
                break;
            }
        }
        if (!fits) {
            return null;
        }
        Entity entity = p.type().create(level, null, BlockPos.containing(p.x(), y, p.z()), origin.spawnType(), false, false);
        if (!(entity instanceof Mob mob)) {
            if (entity != null) {
                entity.discard();
            }
            return null;
        }
        mob.moveTo(p.x(), y, p.z(), p.yaw(), 0.0F);
        mob.setYHeadRot(p.yaw());
        mob.setYBodyRot(p.yaw());
        return addMember(level, mob, id, slot, planned, origin, eventId, beforeAdd);
    }

    /**
     * 所属を書いて世界へ足す(クラスの説明の順番: 所属 → {@code beforeAdd} → 小規模レイドなら {@code hunt_target} → 出どころの印
     * → addFreshEntity)。足せなければ null。
     */
    @Nullable
    private static Mob addMember(ServerLevel level, Mob mob, UUID id, int slot, int planned, SwarmOrigin origin, String eventId,
                                 @Nullable Consumer<Mob> beforeAdd) {
        mob.setData(ModAttachments.SWARM, new SwarmMembership(id, slot, origin, eventId, planned));
        if (beforeAdd != null) {
            beforeAdd.accept(mob);
        }
        if (origin == SwarmOrigin.SMALL_RAID && !eventId.isEmpty()) {
            // EventMobs は小規模レイドの印が生きているかを hunt_target で見るので、印より先に書く。
            mob.setData(ModAttachments.HUNT_TARGET, eventId);
        }
        EventMobs.Kind eventKind = origin.eventKind();
        if (eventKind != null && !eventId.isEmpty()) {
            EventMobs.tag(mob, eventKind, eventId);
        }
        return level.addFreshEntity(mob) ? mob : null;
    }

    /**
     * <b>群れに入らない</b>個体(TD の護衛 —— G5〜G9。2026-09-25)を {@code center} のまわりに 1 体湧かせる。置き場所の探し方は
     * 群れの一員と同じ({@link #spawnOne}: 天蓋の下の本物の地面・乾いている・体がまるごと入る・高さの差 {@value #MAX_DY} まで)。
     * 出どころのイベントの印は addFreshEntity の<b>前</b>に書く(クラスの説明の順番)。
     *
     * @return 湧いた個体。置けなければ null
     */
    @Nullable
    public static Mob spawnLoose(ServerLevel level, EntityType<?> type, BlockPos center, SwarmOrigin origin, String eventId) {
        String event = eventId != null ? eventId : "";
        RandomSource random = level.random;
        int spread = 3;
        for (int attempt = 0; attempt < TRIES_PER_MEMBER; attempt++) {
            int x = center.getX() + random.nextInt(spread * 2 + 1) - spread;
            int z = center.getZ() + random.nextInt(spread * 2 + 1) - spread;
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
                continue;
            }
            BlockPos pos = TreeCanopyGroundFinder.findSpawnSurface(level, x, z);
            if (pos == null || Math.abs(pos.getY() - center.getY()) > MAX_DY) {
                continue;
            }
            if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.above()).isEmpty()) {
                continue;
            }
            if (!SpawnFit.fits(level, type, pos)) {
                continue;
            }
            Entity entity = type.create(level, null, pos, origin.spawnType(), false, false);
            if (!(entity instanceof Mob mob)) {
                if (entity != null) {
                    entity.discard();
                }
                return null;
            }
            mob.setYRot(random.nextFloat() * 360.0F);
            EventMobs.Kind eventKind = origin.eventKind();
            if (eventKind != null && !event.isEmpty()) {
                EventMobs.tag(mob, eventKind, event);
            }
            return level.addFreshEntity(mob) ? mob : null;
        }
        return null;
    }

    /** 置き場所の高さが中心からこれ以上違えば使わない(崖の上下・木の上に散らない)。仮決め 2026-09-25。 */
    private static final int MAX_DY = 8;

    private SwarmSpawner() {
    }
}
