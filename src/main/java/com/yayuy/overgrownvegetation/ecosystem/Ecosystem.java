package com.yayuy.overgrownvegetation.ecosystem;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EcosystemTable;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * <b>生態系の台帳(兵站)</b>(2026-09-24、ユーザー指定。2026-10-14 にユーザー指定で作り直し)。
 * グループ2・3 に「植物の経済」という役目を持たせる仕組み。
 *
 * <pre>
 * 毎秒 (1 + 過繁茂チャンク 100 ごとに +1) × 多様性 ──▶ バイオマス(植物の蓄え)──▶ 防衛の兵(グループ4〜6)/ TD イベント
 * 多様性(ワールドで 1 つ)── 時間で戻る(5 日で 0→100%)
 * プレイヤー・防衛設備がどこでグループ2・3 を倒しても ──▶ 多様性が下がる ──▶ ① グループ2・3 が湧きにくくなる
 *                                                                     ├▶ ② しきい値を下回るとグループ4〜6 が防衛に湧く
 *                                                                     └▶ ③ バイオマスの稼ぎが減る
 * </pre>
 *
 * <h2>2026-10-14 の作り直し(ユーザー指定)</h2>
 * <ul>
 *     <li>多様性は区域(8×8 チャンク)ごとではなく<b>ワールド全体で 1 つ</b>。</li>
 *     <li>バイオマスは<b>毎秒 1、過繁茂のチャンク 100 ごとに +1</b>(前は区域ごとに 1 分 10 × 豊かさ × 繁茂率)。
 *     多様性を掛けるのは「G2・3 を倒すとバイオマスの獲得量が減る」の指定から。</li>
 * </ul>
 *
 * <p><b>オーバーワールドだけ</b>。他の次元でグループ2・3 を倒しても数えない。数える過繁茂のチャンクは
 * {@link PhaseData} の台帳({@link PhaseData#overgrownLedgerSize}。キーに次元が無く、ネザーの分も混ざる既存の性質)。
 *
 * <h2>数えるのは誰が倒したときか(ユーザー指定)</h2>
 * <p><b>プレイヤー</b>({@code DamageSource#getEntity()} がプレイヤー = 矢や TNT のような間接攻撃も含む)と、
 * {@code countedDamageTypes} に書いた<b>防衛設備</b>(既定は機関砲台・有刺鉄線)だけ。
 * 飼い慣らした狼・ゴーレム・グループ3 の捕食・落下などでは減らない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class Ecosystem {

    /** バイオマスの収入を 1 回に何秒ぶんまで足すか(異常に長い間隔で一気に貯まらないように)。 */
    private static final double MAX_INCOME_SECONDS = 3600.0D;

    // --- 区域(防衛の兵を出す場所の目安だけ) ---

    /** {@code pos} を含む区域のキー。 */
    public static long regionKeyAt(BlockPos pos) {
        return EcosystemMath.regionKey(pos.getX() >> 4, pos.getZ() >> 4, DesignData.ecosystem().regionChunks());
    }

    /** 区域 {@code regionKey} の中で、過繁茂以上として台帳に載っているチャンクの数。 */
    public static int coveredChunks(PhaseData data, long regionKey) {
        int size = DesignData.ecosystem().regionChunks();
        int x0 = ChunkPos.getX(regionKey) * size;
        int z0 = ChunkPos.getZ(regionKey) * size;
        int count = 0;
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                if (data.isOvergrownChunk(new ChunkPos(x0 + dx, z0 + dz))) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 区域ごとの過繁茂のチャンク数(台帳の写しから。{@code /ov perf} の表示用)。 */
    public static Long2IntOpenHashMap coveredRegions(it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap ledger, int size) {
        Long2IntOpenHashMap covered = new Long2IntOpenHashMap();
        it.unimi.dsi.fastutil.longs.LongIterator keys = ledger.keySet().iterator();
        while (keys.hasNext()) {
            long chunk = keys.nextLong();
            covered.addTo(EcosystemMath.regionKey(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), size), 1);
        }
        return covered;
    }

    // --- 多様性 ---

    /** ワールドの今の多様性(0〜1)。 */
    public static double health(ServerLevel level) {
        return EcosystemData.get(level.getServer()).health(level.getServer().overworld().getGameTime(),
                DesignData.ecosystem().recoveryPerTick());
    }

    /** ワールドの今の防衛の段階(0 = なし)。 */
    public static int tier(ServerLevel level) {
        EcosystemTable table = DesignData.ecosystem();
        return EcosystemData.get(level.getServer()).tier(level.getServer().overworld().getGameTime(),
                table.recoveryPerTick(), table.thresholds(), table.hysteresis());
    }

    /** 台帳がある次元か(オーバーワールドだけ)。 */
    public static boolean isLedgerLevel(ServerLevel level) {
        return level.dimension() == Level.OVERWORLD;
    }

    /** グループ2・3(生態系を作る側)か。 */
    public static boolean isEcosystemMember(EntityType<?> type) {
        MobGroup group = MobGroup.of(type);
        return group == MobGroup.GROUP_2 || group == MobGroup.GROUP_3;
    }

    /** この一撃で倒されたら多様性を減らすか(プレイヤーか防衛設備)。 */
    public static boolean counts(DamageSource source, EcosystemTable table) {
        if (source.getEntity() instanceof Player) {
            return true;
        }
        for (ResourceKey<DamageType> key : table.countedDamageTypes()) {
            if (source.is(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code victim} が {@code source} で倒された。数える死ならワールドの多様性を減らし、減らした量を返す(数えなければ 0)。
     */
    public static double recordKill(ServerLevel level, LivingEntity victim, DamageSource source) {
        if (!isLedgerLevel(level) || !isEcosystemMember(victim.getType())) {
            return 0.0D;
        }
        EcosystemTable table = DesignData.ecosystem();
        if (!counts(source, table)) {
            return 0.0D;
        }
        double loss = table.healthLoss(victim.getType(), MobGroup.of(victim.getType()));
        if (loss <= 0.0D) {
            return 0.0D;
        }
        EcosystemData.get(level.getServer()).damage(level.getGameTime(), loss, table.recoveryPerTick());
        return loss;
    }

    /** 最後に聞く —— トーテムなどで死亡が取り消されたら数えない。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (event.isCanceled() || !(victim.level() instanceof ServerLevel level)) {
            return;
        }
        recordKill(level, victim, event.getSource());
    }

    // --- 湧き ---

    /**
     * 生態系スポーンの目標個体数を、ワールドの多様性で減らす({@code OvergrownBiomeSpawner} から)。
     * グループ2・3 以外と、オーバーワールド以外はそのまま返す。
     */
    public static int scaleSpawnTarget(ServerLevel level, BlockPos pos, EntityType<?> type, int target,
                                       RandomSource random) {
        if (!isLedgerLevel(level) || !isEcosystemMember(type)) {
            return target;
        }
        return EcosystemMath.scaledTarget(target, health(level), random.nextDouble());
    }

    // --- バイオマス ---

    /** 今の収入(1 秒あたり)。 */
    public static double incomePerSecond(ServerLevel overworld) {
        EcosystemTable table = DesignData.ecosystem();
        return EcosystemMath.incomePerSecond(table.biomassPerSecond(), table.biomassPerStep(),
                table.biomassChunksPerStep(), PhaseData.get(overworld).overgrownLedgerSize(), health(overworld));
    }

    /** 収入を足す。{@code incomeIntervalTicks}(既定 20 = 1 秒)ごと、フェーズ1 以降だけ。 */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !isLedgerLevel(level)) {
            return;
        }
        EcosystemTable table = DesignData.ecosystem();
        long now = level.getGameTime();
        if (now % table.incomeIntervalTicks() != 0L) {
            return;
        }
        EcosystemData data = EcosystemData.get(level.getServer());
        long last = data.lastIncomeTick();
        data.setLastIncomeTick(now);
        if (PhaseData.get(level).getPhase() < 1 || last < 0L || now <= last) {
            // フェーズ0 の間は貯めない(フェーズポイントと同じ)。時刻だけ進めておくので、
            // フェーズ1 になった瞬間にさかのぼって貯まることもない。
            return;
        }
        double seconds = Math.min(MAX_INCOME_SECONDS, (now - last) / 20.0D);
        double perSecond = incomePerSecond(level);
        data.setLastIncomePerSecond(perSecond);
        // 貯蓄上限(2026-10-23 追記 14): 基本 + 生きている巨大樹 1 本ごと。上限を超えて貯めない(上限より多く持っていれば減らしはしない)
        PhaseData pd = PhaseData.get(level);
        double cap = table.biomassCap(pd.livingGiantTreeCount());
        data.setLastBiomassCap(cap);
        double room = cap - data.biomass();
        if (room > 0.0D) data.addBiomass(Math.min(room, perSecond * seconds));
    }

    private Ecosystem() {
    }
}
