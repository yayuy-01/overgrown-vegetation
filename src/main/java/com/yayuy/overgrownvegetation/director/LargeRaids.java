package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.LargeRaidTable;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.phase.PhaseStage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * <b>大きな襲撃</b>(2026-11-14、司令塔 第 4 段)。司令塔の襲撃の大・中・小(小 = 送り込み・中 = 貯めた襲撃・大 = これ)の「大」。
 * 11-14 ユーザー: 「少し貯めた襲撃とは違って周りをうろつく mob すら用意せず、2 日ほど一切の mob 湧きがなくなり、溜めたバイオマスを使用して
 * 一気に mob を沸かせて襲撃する」・「負けや勝ちなどなく、大量の mob に襲われるイベント」・「溜めたバイオマスの分だけ際限なく常に湧かせる」。
 *
 * <ul>
 *   <li><b>起こる</b>: F7 から(DirectorGates.Gate.LARGE_RAID)・今の段が定着か最盛・認識した拠点がある・前の大きな襲撃から {@code cooldownDays}・
 *   ふだんか貯めている最中(貯めた予算はそのまま持ち越す)。1 日に {@code chancePerDay} の割合。</li>
 *   <li><b>前触れ</b>(約 2 日): 植物側の湧きが止まる({@link DirectorGates#plantSpawnsSuppressed}: 苗床の萌芽体とスポナー・送り込み・危ない所へ送る・
 *   貯める。G1 の自然湧き・G4 以降を戻す側もこの口を見る)。偵察は続ける。すでにいる個体はそのまま。予算の上限は {@code capFactor} 倍。</li>
 *   <li><b>本番</b>: 認識した拠点へ、予算が尽きるまで途切れずに攻め手を湧かせ続ける(波で休まない)。{@code spawnIntervalTicks} ごとに
 *   {@code spawnPerStep} 体まで。同時にいる攻め手が {@code maxAlive}(重さのための安全弁)に達している間は待ち、倒された分はすぐ補う。
 *   湧かせる所は森の方角か弱い方角の、プレイヤーから見通せない地面({@link RaidSpawns})。湧いた攻め手は弱い方角から拠点へ回り込む。
 *   勝ち負けは無い。予算が尽きたら終わり(攻め手は残る)。拠点を忘れたら終わり(予算は残る)。</li>
 * </ul>
 * 湧かせる口({@link RaidSpawns})と送る口({@link G1March#beginFlank}・{@link BaseRaids})は、後の超大規模の襲撃からも呼べる。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class LargeRaids {

    /** 攻め手の印(数えるため)。 */
    public static final String ATTACKER_TAG = "ov_large_raid";

    private LargeRaids() {
    }

    /** 起こしてよいか(純粋な計算)。 */
    public static boolean canStart(boolean open, PhaseStage stage, boolean hasRecognizedBase, double day, double cooldownUntil,
                                   ForestDirector.Mood mood) {
        return open && stage != PhaseStage.INTRO && hasRecognizedBase && day >= cooldownUntil
                && (mood == ForestDirector.Mood.NORMAL || mood == ForestDirector.Mood.HOARD);
    }

    /** 見張り(20 秒ごと)から: 起こす・前触れから本番へ。 */
    static void tick(ServerLevel level, double day, int sampleTicks) {
        if (DirectorGates.peaceful(level)) return; // ピースフルの間は始めない・時計を進めない(見直し ②9)
        ForestDirector d = ForestDirector.get(level);
        LargeRaidTable t = DesignData.director().largeRaid;
        RandomSource random = level.getRandom();
        switch (d.mood()) {
            case LARGE_OMEN -> {
                if (day >= d.moodUntil()) begin(level, day);
            }
            case LARGE -> {
                if (target(level) == null) end(level, day, "拠点を忘れた");
            }
            default -> {
                double perSample = t.chancePerDay * sampleTicks / 24000.0D;
                if (canStart(DirectorGates.open(level, DirectorGates.Gate.LARGE_RAID), DirectorGates.stage(level), strongestBase(level) != null,
                        day, d.largeRaidCooldownUntil(), d.mood()) && random.nextDouble() < perSample) {
                    startOmen(level, day, t.omenMinDays + random.nextDouble() * (t.omenMaxDays - t.omenMinDays));
                }
            }
        }
    }

    /** 前触れを始める(貯めている最中なら、貯めた予算を持ち越す)。 */
    public static void startOmen(ServerLevel level, double day, double days) {
        ForestDirector d = ForestDirector.get(level);
        d.setMood(ForestDirector.Mood.LARGE_OMEN, day, day + days);
        d.note(String.format(Locale.ROOT, "大きな襲撃の前触れ(%.2f 日。植物側の湧きが止まる。予算 %.1f / 上限 %.1f)", days, d.available(level), ForestDirector.cap(level)));
    }

    /** 本番を始める。いちばん点の高い認識した拠点を的にする。無ければ、ふだんに戻る(予算は残る)。 */
    public static void begin(ServerLevel level, double day) {
        ForestDirector d = ForestDirector.get(level);
        BaseMemory.Base b = strongestBase(level);
        if (b == null) {
            end(level, day, "認識した拠点が無い");
            return;
        }
        d.setLargeRaidTarget(b.id());
        d.setMood(ForestDirector.Mood.LARGE, day, Double.MAX_VALUE);
        d.note(String.format(Locale.ROOT, "大きな襲撃が始まった(拠点 #%d。予算 %.1f)", b.id(), d.available(level)));
    }

    /** 終わる(ふだんへ。次まで cooldownDays)。 */
    public static void end(ServerLevel level, double day, String why) {
        ForestDirector d = ForestDirector.get(level);
        d.setMood(ForestDirector.Mood.NORMAL, day, day);
        d.setLargeRaidCooldownUntil(day + DesignData.director().largeRaid.cooldownDays);
        d.note("大きな襲撃が終わった(" + why + "。予算 " + String.format(Locale.ROOT, "%.1f", d.available(level)) + ")");
    }

    @Nullable
    static BaseMemory.Base strongestBase(ServerLevel level) {
        BaseMemory.Base best = null;
        for (BaseMemory.Base b : BaseMemory.get(level.getServer()).bases()) {
            if (b.stage() != BaseMemory.Stage.RECOGNIZED) continue;
            if (best == null || b.score() > best.score()) best = b;
        }
        return best;
    }

    /** 本番の的(認識した拠点のまま残っていれば)。 */
    @Nullable
    public static BaseMemory.Base target(ServerLevel level) {
        BaseMemory.Base b = BaseMemory.get(level.getServer()).byId(ForestDirector.get(level).largeRaidTarget());
        return b != null && b.stage() == BaseMemory.Stage.RECOGNIZED ? b : null;
    }

    // ---------------------------------------------------------------- 本番: 途切れずに湧かせる

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (!DesignData.director().enabled) return;
        LargeRaidTable t = DesignData.director().largeRaid;
        if (server.getTickCount() % t.spawnIntervalTicks != 0) return;
        ServerLevel level = server.overworld();
        if (ForestDirector.get(level).mood() != ForestDirector.Mood.LARGE) return;
        if (DirectorGates.peaceful(level)) return; // ピースフルの間は湧かせない・払わない(見直し ②9)
        step(level, level.getRandom());
    }

    /** 設定画面の「ゲーム内容」で変えられる上限の下限と上限(重くなりすぎないように)【仮】。 */
    public static final int MAX_ALIVE_MIN = 20, MAX_ALIVE_MAX = 500;

    /**
     * 同時に出ている攻め手の上限: MOD の設定(COMMON の {@code gameplay.largeRaidMaxAlive})があればそれ(20〜500)、−1 なら設計データ
     * ({@code director.json} の {@code largeRaid.maxAlive})。設定がまだ読めないとき(起動の途中)は設計データ。
     */
    public static int maxAlive() {
        int fallback = DesignData.director().largeRaid.maxAlive;
        try {
            int c = com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.largeRaidMaxAlive.get();
            return c < 0 ? fallback : Math.max(MAX_ALIVE_MIN, Math.min(MAX_ALIVE_MAX, c));
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    /** 攻め手の数(拠点のまわり countRadius の中の、印の付いた生きている個体)。 */
    public static int alive(ServerLevel level, BaseMemory.Base b) {
        double r = DesignData.director().largeRaid.countRadius;
        // G4・G5 の攻め手も数える(11-15 enemy-spawns。印は同じ)
        return level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                new AABB(b.centerX() - r, level.getMinBuildHeight(), b.centerZ() - r, b.centerX() + r, level.getMaxBuildHeight(), b.centerZ() + r),
                m -> m.isAlive() && m.getTags().contains(ATTACKER_TAG)).size();
    }

    /**
     * 1 回ぶん湧かせる。予算が段階 0 の値段にも足りなければ終わる。同時の上限に当たっていれば、今回は湧かせない(倒された分は次の回にすぐ補う)。
     *
     * @return 湧かせた数
     */
    /** 確かめ用: 本番で湧かせる回を呼んだ数。 */
    public static long steps;

    public static int step(ServerLevel level, RandomSource random) {
        steps++;
        ForestDirector d = ForestDirector.get(level);
        LargeRaidTable t = DesignData.director().largeRaid;
        double day = BaseMemory.today(level.getServer());
        BaseMemory.Base b = target(level);
        if (b == null) {
            end(level, day, "拠点を忘れた");
            return 0;
        }
        if (d.available(level) + 1.0E-9D < t.costStageZero) {
            end(level, day, "予算を使い切った");
            return 0;
        }
        int room = maxAlive() - alive(level, b);
        int n = 0;
        double devPrice = ForestDirector.stagePrice(DesignData.director().costEvolve, DirectorGates.strength(level, DirectorGates.Gate.EVOLVE));
        double flank = DesignData.director().base.flankShare * DirectorGates.strength(level, DirectorGates.Gate.LARGE_RAID);
        for (int i = 0; i < Math.min(room, t.spawnPerStep); i++) {
            double roll = random.nextDouble();
            // 顔ぶれ(11-14 夕): G4〜G6 が plantShare。開いている G4・G5 の地上の種を、G1 と同じ湧かせ方(見通せない地面)で湧かせ、
            // 印を付けて拠点へ歩かせる(11-15 enemy-spawns)。出せる種が無い・置けない間は段階 0 を湧かせる
            if (roll < t.plantShare) {
                net.minecraft.world.entity.EntityType<?> type = com.yayuy.overgrownvegetation.spawn.DirectorHooks.pickPlant(level, random, 0);
                if (type != null) {
                    double plantPrice = ForestDirector.stagePrice(ForestDirector.spawnCost(type),
                            DirectorGates.strength(level, com.yayuy.overgrownvegetation.spawn.DirectorHooks.gateOf(com.yayuy.overgrownvegetation.spawn.DirectorHooks.groupOf(type))));
                    BlockPos pat = d.available(level) + 1.0E-9D >= plantPrice ? spot(level, b, random) : null;
                    net.minecraft.world.entity.Mob pm = pat == null ? null : RaidSpawns.spawnPlantAttacker(level, pat, type, random);
                    if (pm != null) {
                        d.tryPay(level, plantPrice);
                        pm.addTag(ATTACKER_TAG);
                        com.yayuy.overgrownvegetation.spawn.PlantMarch.begin(pm, b);
                        n++;
                        continue;
                    }
                }
            }
            boolean developed = roll >= t.plantShare && roll < t.plantShare + t.developedShare && d.available(level) + 1.0E-9D >= devPrice;
            double price = developed ? devPrice : t.costStageZero;
            if (d.available(level) + 1.0E-9D < price) break;
            BlockPos at = spot(level, b, random);
            if (at == null) break; // 見通せない地面が見つからない回は休む(次の回にまた探す)
            AbstractParasitizedMonster m = RaidSpawns.spawnAttacker(level, at, developed, random);
            if (m == null) continue;
            d.tryPay(level, price);
            m.addTag(ATTACKER_TAG);
            m.march().beginFlank(b, flank, random);
            n++;
        }
        return n;
    }

    /** テスト用: 湧かせる所を決め打ちにする(null で戻す。テスト場の外は読み込まれていないため)。 */
    @Nullable
    private static java.util.function.Supplier<BlockPos> spotOverride;

    public static void setSpotForTests(@Nullable java.util.function.Supplier<BlockPos> p) {
        spotOverride = p;
    }

    /** 湧かせる所: 森の方角か、拠点の弱い方角(半々【仮】)。 */
    @Nullable
    private static BlockPos spot(ServerLevel level, BaseMemory.Base b, RandomSource random) {
        if (spotOverride != null) return spotOverride.get();
        LargeRaidTable t = DesignData.director().largeRaid;
        Vec3 center = new Vec3(b.centerX(), 64.0D, b.centerZ());
        double angle;
        Vec3 away = ForestEdge.of(level).away(center);
        if (b.weakSector() >= 0 && (away == null || random.nextBoolean())) {
            angle = BaseDefense.angleOf(b.weakSector(), DesignData.director().base.weakSectors);
        } else if (away != null) {
            angle = Math.atan2(-away.z, -away.x);
        } else {
            angle = random.nextDouble() * Math.PI * 2.0D;
        }
        return RaidSpawns.findHiddenSpot(level, b.centerX(), b.centerZ(), angle, t.spawnSpreadDeg, t.spawnMinBlocks, t.spawnMaxBlocks,
                t.playerMinBlocks, t.spawnTries, random);
    }
}
