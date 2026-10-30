package com.yayuy.overgrownvegetation.td;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.mixin.MobPersistenceAccessor;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 侵攻の間(予告を含む)、ターゲットのまわりから <b>G4 以降でない敵対 Mob</b> を消し、新しく湧かせない(2026-09-25、ユーザー指定
 * 「TD 開始時には G4 以降の mob 以外の敵対 mob を消滅させて、TD 期間中は新規にスポーンしないように(敵対 mob のみなので牛や豚などは
 * 残る)」)。
 *
 * <h2>範囲と期間</h2>
 * <b>イベントのディメンション全体</b>(td.json の {@code hostileClearRadius} が 0 = 既定。ユーザー指定 2026-09-25「できうる限り処理を
 * 軽くするためにワールド全体にする」。はじめは仮決めのターゲットから 256)。正の数ならターゲットから水平にその距離の中、負の数なら
 * 消さない・止めない。予告が出た瞬間に今いる分を消し({@link #clear})、イベントが終わる(勝ち・負け・中止・取りやめ)まで止める。
 * 拠点を離れて侵攻が一時停止している間(最長 3 日で取りやめ)も、ディメンションのどこにも出ない。ネザー・エンドは触らない
 * (仮決め 2026-09-25: 1 人で遊ぶときは誰もいないディメンションは読み込まれず軽さは変わらない。エンドシティのシュルカーのような
 * 二度と湧かない Mob まで消える)。
 *
 * <p>ディメンション全体のときは、バニラの自然湧きの敵対 Mob の回(と、ファントム・パトロール・村の襲撃)を丸ごと止める
 * ({@link #syncMonsterSpawning}。難易度ピースフルと同じ口 {@code ServerChunkCache#setSpawnSettings})。敵対 Mob がいないと
 * 湧きの上限に届かず、バニラは毎 tick すべてのチャンクで湧かせる場所を探し続ける —— 湧かせないなら探させない方が軽い。
 *
 * <h2>敵対 Mob とは({@link #isClearableType})</h2>
 * <ul>
 *     <li><b>グループのある種はグループで決める</b>: G1(媒介種。寄生された家畜も何でも襲う)・G2(被食種。ユーザー指定 2026-09-25
 *     「追加で G2 も消す」)・G3(捕食種。縄張りに入ったプレイヤーを襲う)は消す。<b>G4 以降(G4〜G10)は残す</b>。</li>
 *     <li><b>グループの無いこの MOD の種は残す</b>(巨大樹の巣の節・蔦などの仕掛け。湧きの区分が MONSTER なので、はじめは消していた ——
 *     巣の節を消すと台帳の強制感染の区域だけが残り、その木の儀式が二度とできなくなる。2026-09-25 レビュー指摘)。</li>
 *     <li>それ以外(バニラ・ほかの MOD): {@link Enemy} を持つか、湧きの区分が {@link MobCategory#MONSTER}(スライム・ファントム・
 *     ガスト・シュルカーも)。ボス({@code c:bosses} —— ウィザー・エンダードラゴン)は残す。</li>
 * </ul>
 * 個体では次を残す({@link #isClearable}。どれも仮決め 2026-09-25):
 * <ul>
 *     <li>名札を付けた Mob、ボート・トロッコに乗っている Mob(村人の治療・Mob の運搬)。</li>
 *     <li><b>プレイヤーが置いた Mob</b>(スポーンエッグ・コマンド・ディスペンサー・繁殖。{@link #EXEMPT_TAG})。</li>
 *     <li><b>ワールドの生成で置かれた、二度と湧かない Mob</b>(エルダーガーディアン・森の洋館の邪悪な村人・イグルーの村人ゾンビ・
 *     ピグリンブルートなど。{@link #isWorldPlaced})と、<b>治療中の村人ゾンビ</b>。</li>
 *     <li><b>バニラのレイドの襲撃者</b>(レイドに入っている {@link Raider})。レイドは襲撃者を自分の一覧へ入れてから足すので、足すのを
 *     取り消すと居ない襲撃者が一覧に残ってレイドが 40 分止まる。湧かせるなら、始まりに消すのも揃えてやめる。</li>
 * </ul>
 *
 * <h2>止め方</h2>
 * <ul>
 *     <li>自然湧き・スポナー: {@link MobSpawnEvent.SpawnPlacementCheck} を FAIL(エンティティを作る前に落とす。軽い)。</li>
 *     <li>それ以外の新しい湧き(パトロール・援軍・村の襲撃・変換の出口・寄生の孵化・この MOD の湧かせ方): {@link EntityJoinLevelEvent} を
 *     取り消す。取り消す前に乗り物・乗り手を切り離す(乗り物だけ世界に残って、居ない乗り手を抱えたまま保存されないように)。</li>
 *     <li>バニラの変換(村人 → ウィッチ・ブタ → ゾンビピグリン など)は {@link LivingConversionEvent.Pre} で取り消す(変換の出口を
 *     Join で消すと、元の Mob だけが消えてしまうため)。</li>
 *     <li>範囲の中で読み込まれたチャンクの敵対 Mob と、拾った持ち物を持って入って来た敵対 Mob(ネザーポータルを抜けてきたゾンビなど)は、
 *     次の tick に消す(Join の中では持ち物を落とせない。取り消すと持ち物ごと消える)。</li>
 *     <li>{@code /summon} で置いた Mob は NBT 付きでも止めない。NBT 付きは finalizeSpawn を通らず印が付かないので、世界へ足す直前に
 *     {@code SummonCommandTdExemptMixin} が {@link #markCommandPlaced} で印を付ける(レビュー指摘 2026-09-25、2026-09-27 に直した。
 *     それまでは範囲の中で取り消されて「UUID が重複」と出ていた)。</li>
 * </ul>
 * 消すときは、拾った持ち物(ドロップ率 100% の装備)とエンダーマンの抱えたブロックを落としてから消す(プレイヤーの持ち物を失わせない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class TdHostiles {

    /**
     * プレイヤー・テストが置いた Mob の印(スポーンエッグ・コマンド・ディスペンサー・バケツ・繁殖の {@link FinalizeSpawnEvent} で付ける)。
     * 付いている Mob は消さない・止めない。<b>外さない</b>(はじめは Join で外していたので、侵攻中に置いた Mob がチャンクの読み込み直しで
     * 消え、侵攻の前に置いた Mob は始まりに消えた。2026-09-25 レビュー指摘)。
     */
    public static final String EXEMPT_TAG = "overgrown_vegetation.td_spawn_exempt";

    /**
     * 止めない湧き方: プレイヤーが自分で置いたもの。はじめはイベントの湧き(EVENT)も通していた(バニラのレイドのため)が、それでは
     * 村の襲撃のゾンビ・この MOD の小規模レイドも範囲に入った。レイドの襲撃者は {@link #isClearable} で残す(レビュー指摘 2026-09-25)。
     */
    private static final Set<MobSpawnType> PLAYER_SPAWNS = Set.of(
            MobSpawnType.SPAWN_EGG, MobSpawnType.COMMAND, MobSpawnType.DISPENSER, MobSpawnType.BUCKET, MobSpawnType.BREEDING);

    /** 読み込まれたチャンクから来た・持ち物を持って入って来た、次の tick に消す Mob(ディメンションごと)。 */
    private static final Map<ResourceKey<Level>, List<Mob>> PENDING = new HashMap<>();

    /** バニラの自然湧きの敵対 Mob の回を止めているディメンション({@link #syncMonsterSpawning})。 */
    private static final Set<ResourceKey<Level>> MONSTER_SPAWNING_OFF = new HashSet<>();

    /** 止める湧きの回を掛け直す間隔(tick)。難易度を変えるとバニラが戻すので、そのぶんを直す。 */
    private static final int SPAWN_SETTINGS_INTERVAL_TICKS = 20;

    /** 範囲(ターゲット・半径の 2 乗。ディメンション全体なら無限大)。 */
    private record Zone(BlockPos target, double radiusSq) {
        boolean contains(double x, double z) {
            double dx = x - (this.target.getX() + 0.5D);
            double dz = z - (this.target.getZ() + 0.5D);
            return dx * dx + dz * dz <= this.radiusSq;
        }

        boolean wholeDimension() {
            return this.radiusSq == Double.POSITIVE_INFINITY;
        }
    }

    // ------------------------------------------------------------------ 判定

    /**
     * {@code level} の範囲(進行中のイベントがこのディメンションで、範囲が止まっていない)。無ければ null。毎回台帳を引く(台帳の
     * 引き当ては表を 1 回引くだけで軽い。はじめは同じ tick の答えを控えていたが、同じ tick の中でイベントが終わる・設計値が変わると
     * 古い範囲で止め続けた)。
     */
    @Nullable
    private static Zone zone(ServerLevel level) {
        TdData.Event event = TdData.get(level.getServer()).live();
        if (event == null || !event.dimension().equals(level.dimension())) {
            return null;
        }
        return zoneOf(event);
    }

    /** そのイベントの範囲(td.json の {@code hostileClearRadius}: 0 = ディメンション全体、正 = 半径、負 = 止めない → null)。 */
    @Nullable
    private static Zone zoneOf(TdData.Event event) {
        double radius = DesignData.td().hostileClearRadius();
        if (radius < 0.0D) {
            return null;
        }
        return new Zone(event.target(), radius > 0.0D ? radius * radius : Double.POSITIVE_INFINITY);
    }

    /** {@code (x, z)} が、このディメンションで進行中の侵攻の範囲の中か。 */
    public static boolean inZone(ServerLevel level, double x, double z) {
        Zone zone = zone(level);
        return zone != null && zone.contains(x, z);
    }

    /**
     * 侵攻の間、{@code pos} のあたりに {@code type} を湧かせないか。この MOD の湧かせ方(生態系の補充・外のはぐれ)が、作ってから Join で
     * 取り消されるのを待たずに候補から外すための口(作るぶんが丸ごと無駄になるので。取り消しは今までどおり最後の砦として残る)。
     */
    public static boolean blocksSpawn(ServerLevel level, EntityType<?> type, BlockPos pos) {
        Zone zone = zone(level);
        return zone != null && isClearableType(type) && zone.contains(pos.getX() + 0.5D, pos.getZ() + 0.5D);
    }

    /** その種が「消す・止める」敵対 Mob か(クラスの説明。G4 以降・グループの無いこの MOD の種・ボスは false)。 */
    public static boolean isClearableType(EntityType<?> type) {
        Boolean fixed = fixedVerdict(type);
        return fixed != null ? fixed : type.getCategory() == MobCategory.MONSTER;
    }

    /**
     * 種だけで決まる答え。ボスは残す。グループのある種は G1〜G3 を消し、G4〜G10 を残す。グループの無いこの MOD の種(巨大樹の巣の
     * 節・蔦などの仕掛け)は残す。どれでもなければ null(バニラ・ほかの MOD の種。敵対かどうかで決める)。
     */
    @Nullable
    private static Boolean fixedVerdict(EntityType<?> type) {
        if (type.is(Tags.EntityTypes.BOSSES)) {
            return Boolean.FALSE;
        }
        MobGroup group = MobGroup.of(type);
        if (group != null) {
            return group.number() <= 3;
        }
        if (OvergrownVegetation.MODID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace())) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** その個体を消す・止めるか(種の判定 + {@link Enemy} + 個体の例外。クラスの説明)。 */
    public static boolean isClearable(Mob mob) {
        EntityType<?> type = mob.getType();
        Boolean fixed = fixedVerdict(type);
        if (fixed != null) {
            if (!fixed) {
                return false;
            }
        } else if (!(type.getCategory() == MobCategory.MONSTER || mob instanceof Enemy)
                || isWorldPlaced(mob) || isBeingCured(mob)
                || (mob instanceof Raider raider && raider.getCurrentRaid() != null)) {
            return false;
        }
        if (mob.hasCustomName() || mob.getTags().contains(EXEMPT_TAG)) {
            return false;
        }
        Entity vehicle = mob.getVehicle();
        return vehicle == null || vehicle instanceof Mob; // ボート・トロッコに乗っている(運んでいる)Mob は残す
    }

    /**
     * ワールドの生成で置かれた、二度と湧かない Mob か(エルダーガーディアン・森の洋館の邪悪な村人・イグルーの村人ゾンビ・ピグリンブルート
     * など。バニラはこれらに永続の印を付けて置く)。永続の印は持ち物を拾っても付くので、拾った持ち物があれば違う(= 消す)。
     */
    private static boolean isWorldPlaced(Mob mob) {
        return mob.isPersistenceRequired() && !hasPickedUpItems(mob);
    }

    /** 拾った持ち物(落ちる確率が 1 を超える = 必ず落ちる装備)を持っているか。 */
    private static boolean hasPickedUpItems(Mob mob) {
        MobPersistenceAccessor access = (MobPersistenceAccessor) mob;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!mob.getItemBySlot(slot).isEmpty() && access.overgrown_vegetation$getEquipmentDropChance(slot) > 1.0F) {
                return true;
            }
        }
        return false;
    }

    /** 治療中の村人ゾンビ(プレイヤーが弱体化させて金のリンゴを与えた。消すと村人が一人いなくなる)。 */
    private static boolean isBeingCured(Mob mob) {
        return mob instanceof ZombieVillager villager && villager.isConverting();
    }

    // ------------------------------------------------------------------ 始まりに消す

    /**
     * 侵攻の範囲の中で今読み込まれている敵対 Mob を消す({@code TdTicker} が予告の始まりと、コマンドで始めたときに呼ぶ)。
     *
     * @return 消した数
     */
    static int clear(ServerLevel level, TdData.Event event) {
        Zone zone = zoneOf(event);
        if (zone == null || !event.dimension().equals(level.dimension())) {
            return 0;
        }
        BlockPos t = event.target();
        List<Mob> found = new ArrayList<>();
        if (zone.wholeDimension()) {
            // ディメンション全体: 読み込まれているエンティティを 1 周する(箱で引くと、箱の幅ぶんの区画の列を 1 本ずつ探して遅い)。
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Mob mob && mob.isAlive() && isClearable(mob)) {
                    found.add(mob);
                }
            }
        } else {
            double radius = Math.sqrt(zone.radiusSq());
            AABB box = new AABB(t.getX() - radius, level.getMinBuildHeight(), t.getZ() - radius,
                    t.getX() + 1 + radius, level.getMaxBuildHeight(), t.getZ() + 1 + radius);
            level.getEntities(EntityTypeTest.forClass(Mob.class), box,
                    mob -> mob.isAlive() && zone.contains(mob.getX(), mob.getZ()) && isClearable(mob), found);
        }
        for (Mob mob : found) {
            remove(level, mob);
        }
        if (!found.isEmpty()) {
            OvergrownVegetation.LOGGER.info("TD: 侵攻の範囲の敵対 Mob {} 体を消した(ターゲット {}、{})", found.size(), t,
                    zone.wholeDimension() ? "ディメンション全体" : "半径 " + (int) Math.sqrt(zone.radiusSq()));
        }
        syncMonsterSpawning(level, zone);
        return found.size();
    }

    /**
     * バニラの自然湧きの敵対 Mob の回を、範囲がディメンション全体のあいだ止め、そうでなくなったら戻す(クラスの説明)。止めるのは難易度
     * ピースフルと同じ口 {@code ServerChunkCache#setSpawnSettings}(ファントム・パトロール・村の襲撃もこの旗を見る)。戻すのはバニラが
     * 世界を開いたとき・難易度を変えたときに入れる値(サーバーの {@code isSpawningMonsters} / {@code isSpawningAnimals})。
     * 難易度を変えるとバニラが旗を戻すので、止めている間は {@value #SPAWN_SETTINGS_INTERVAL_TICKS} tick ごとに掛け直す
     * (戻っている間の湧きは {@link #onSpawnPlacementCheck} が落とす)。旗は保存されないので、読み込み直しても掛け直すだけ。
     */
    private static void syncMonsterSpawning(ServerLevel level, @Nullable Zone zone) {
        MinecraftServer server = level.getServer();
        if (zone != null && zone.wholeDimension()) {
            level.getChunkSource().setSpawnSettings(false, server.isSpawningAnimals());
            MONSTER_SPAWNING_OFF.add(level.dimension());
        } else if (MONSTER_SPAWNING_OFF.remove(level.dimension())) {
            level.getChunkSource().setSpawnSettings(server.isSpawningMonsters(), server.isSpawningAnimals());
        }
    }

    /** 拾った持ち物(エンダーマンなら抱えたブロックも)を落とし、煙を出して消す。 */
    private static void remove(ServerLevel level, Mob mob) {
        mob.dropPreservedEquipment();
        if (mob instanceof EnderMan enderman && enderman.getCarriedBlock() != null) {
            enderman.spawnAtLocation(enderman.getCarriedBlock().getBlock());
            enderman.setCarriedBlock(null);
        }
        level.sendParticles(ParticleTypes.POOF, mob.getX(), mob.getY() + mob.getBbHeight() * 0.5D, mob.getZ(),
                6, mob.getBbWidth() * 0.5D, mob.getBbHeight() * 0.4D, mob.getBbWidth() * 0.5D, 0.02D);
        mob.discard();
    }

    // ------------------------------------------------------------------ 新しい湧きを止める

    /** 自然湧き・スポナー: エンティティを作る前に落とす(侵攻が無ければ台帳を 1 回引くだけで帰る)。 */
    @SubscribeEvent
    public static void onSpawnPlacementCheck(MobSpawnEvent.SpawnPlacementCheck event) {
        if (!(event.getLevel().getLevel() instanceof ServerLevel level)) {
            return;
        }
        Zone zone = zone(level);
        if (zone == null || !isClearableType(event.getEntityType())) {
            return;
        }
        BlockPos pos = event.getPos();
        if (zone.contains(pos.getX() + 0.5D, pos.getZ() + 0.5D)) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    /** プレイヤーが置いた Mob(スポーンエッグ・コマンド・ディスペンサー・バケツ・繁殖)に印を付ける(消さない・止めない)。 */
    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (PLAYER_SPAWNS.contains(event.getSpawnType())) {
            event.getEntity().addTag(EXEMPT_TAG);
        }
    }

    /**
     * コマンドで置いた Mob(乗り手ごと)に印を付ける。NBT 付きの {@code /summon} は finalizeSpawn を通らず {@link #onFinalizeSpawn} が
     * 印を付けないので、{@code SummonCommandTdExemptMixin} が世界へ足す直前にここを呼ぶ(NBT 無しは両方で付くが、同じ印なので害は無い)。
     */
    public static void markCommandPlaced(Entity entity) {
        entity.getSelfAndPassengers().forEach(e -> {
            if (e instanceof Mob) {
                e.addTag(EXEMPT_TAG);
            }
        });
    }

    /** バニラの変換の出口が敵対 Mob なら、範囲の中では変換させない(元の Mob のまま)。 */
    @SubscribeEvent
    public static void onConversion(LivingConversionEvent.Pre event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        Zone zone = zone(level);
        if (zone != null && isClearableType(event.getOutcome())
                && zone.contains(event.getEntity().getX(), event.getEntity().getZ())) {
            event.setCanceled(true);
        }
    }

    /**
     * 新しい湧き: 範囲の中の敵対 Mob は取り消す(個体の例外はクラスの説明)。チャンクと一緒に読み込まれた・拾った持ち物を持って入って来た
     * 敵対 Mob は次の tick に消す(持ち物を落とすため)。{@code TdTicker#onJoin}(LOW)・群れの登録(LOWEST)より先に動く。
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (!inZone(level, mob.getX(), mob.getZ()) || !isClearable(mob)) {
            return;
        }
        if (event.loadedFromDisk() || hasPickedUpItems(mob)) {
            PENDING.computeIfAbsent(level.dimension(), k -> new ArrayList<>()).add(mob);
            return;
        }
        // 乗り物・乗り手を切り離してから取り消す。乗り物が先に世界へ入っている(スケルトンホースの罠・ニワトリに乗った子ゾンビ)と、
        // 居ない乗り手を抱えたまま保存され、読み込み直しで乗り手が戻ってくる。切り離した乗り手は、このあと 1 体ずつこの判定を通る。
        mob.stopRiding();
        mob.ejectPassengers();
        event.setCanceled(true);
    }

    /**
     * 読み込まれたチャンクから来た・持ち物を持って入って来た敵対 Mob を消す(まだ範囲の中で、侵攻が続いていれば)。
     * {@value #SPAWN_SETTINGS_INTERVAL_TICKS} tick ごとに、バニラの敵対 Mob の湧きの回を止める・戻す({@link #syncMonsterSpawning})。
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (level.getGameTime() % SPAWN_SETTINGS_INTERVAL_TICKS == 0L) {
            syncMonsterSpawning(level, zone(level));
        }
        List<Mob> pending = PENDING.remove(level.dimension());
        if (pending == null) {
            return;
        }
        for (Mob mob : pending) {
            if (mob.isAlive() && !mob.isRemoved() && mob.level() == level && inZone(level, mob.getX(), mob.getZ())
                    && isClearable(mob)) {
                remove(level, mob);
            }
        }
    }

    /** テスト用({@link TdTestAccess#monsterSpawningOff})。 */
    static boolean monsterSpawningOffForTests(ServerLevel level, boolean sync) {
        if (sync) {
            syncMonsterSpawning(level, zone(level));
        }
        return MONSTER_SPAWNING_OFF.contains(level.dimension());
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            PENDING.remove(level.dimension());
            MONSTER_SPAWNING_OFF.remove(level.dimension());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
        MONSTER_SPAWNING_OFF.clear(); // 旗は保存されない(次に開いたとき、バニラが戻した旗に侵攻が続いていれば掛け直す)
    }

    private TdHostiles() {
    }
}
