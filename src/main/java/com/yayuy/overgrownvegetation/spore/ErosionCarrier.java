package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.ClimaxTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 浸食度を<b>極相の外へ持ち出している</b>あいだの副作用(2026-09-22、{@code climax.json} の
 * {@code erosion.carrier})。
 *
 * <ul>
 *     <li><b>過繁茂バイオームの中</b>: そのプレイヤーの周りで Mob が湧きやすくなる
 *     ({@code overgrownSpawnMultiplier} 倍)。逃げ帰る途中がいちばん危ない。</li>
 *     <li><b>それ以外の場所</b>: 立っているチャンクの胞子濃度が {@code sporeConcentrationBonus} %
 *     上がる。<b>拠点に座っているだけで拠点が汚れる</b>。</li>
 *     <li>浸食度が {@code thresholdPercent}(既定 25)まで下がると、どちらも消える。</li>
 * </ul>
 *
 * <p>狙いは「帰ってきてすぐ家に入る」を悪手にすること —— 25% まで外で落としてから帰る、
 * という判断が生まれる。胞子除去装置のある拠点なら押し返せるので、装置が
 * 「自分の汚れを落とす場所」としても働く。
 *
 * <p>極相の中では何も起きない(そこは元から濃度 100% の世界で、上乗せに意味が無い)。
 *
 * <h2>持ち主のチャンクの台帳</h2>
 * <p>胞子濃度はチャンクごとにキャッシュされているので、そこへ混ぜると
 * <b>プレイヤーが立ち去っても上乗せが残る</b>。上乗せはキャッシュの<b>外側</b>で足すことにして、
 * 「いま持ち主が立っているチャンク」の一覧をここで持つ。一覧は毎 {@value #REBUILD_INTERVAL} tick
 * 作り直す —— 差分で更新すると、死亡・次元移動・切断で取り残しが出る。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ErosionCarrier {

    /** 一覧を作り直す間隔(tick)。歩いて 1 チャンク進むより短ければ十分。 */
    private static final int REBUILD_INTERVAL = 20;

    /** 次元 → 「持ち主が立っているチャンク」。空のときは {@link #CHUNKS} 自体が空になる。 */
    private static final Map<ResourceKey<Level>, LongOpenHashSet> CHUNKS = new HashMap<>();

    /** そのプレイヤーが副作用を出す状態か(浸食度がしきい値より上)。 */
    public static boolean isCarrier(ServerPlayer player) {
        ClimaxTable table = DesignData.climax();
        return Erosion.percent(player) > table.carrierThresholdPercent();
    }

    /**
     * 過繁茂バイオームでの湧きやすさの倍率。持ち出していなければ 1.0。
     * {@code OvergrownBiomeSpawner} のプレイヤーごとの抽選に掛ける。
     */
    public static double spawnMultiplier(ServerPlayer player) {
        if (!isCarrier(player)) {
            return 1.0D;
        }
        var biome = player.level().getBiome(player.blockPosition());
        if (!ModBiomes.isFullyOvergrown(biome) || ModBiomes.isClimax(biome)) {
            return 1.0D; // 過繁茂の中だけ。極相は対象外。
        }
        return DesignData.climax().carrierOvergrownSpawnMultiplier();
    }

    /**
     * そのチャンクの胞子濃度への上乗せ(%)。持ち主が立っていなければ 0。
     * {@link SporeConcentration#percentAt} が<b>キャッシュの外で</b>足す。
     */
    public static double concentrationBonusAt(ServerLevel level, long chunkKey) {
        if (CHUNKS.isEmpty()) {
            return 0.0D;
        }
        LongOpenHashSet set = CHUNKS.get(level.dimension());
        if (set == null || !set.contains(chunkKey)) {
            return 0.0D;
        }
        return DesignData.climax().carrierSporeConcentrationBonus();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % REBUILD_INTERVAL != 0) {
            return;
        }
        rebuild(event);
    }

    private static void rebuild(ServerTickEvent.Post event) {
        CHUNKS.clear();
        double bonus = DesignData.climax().carrierSporeConcentrationBonus();
        if (bonus <= 0.0D) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            LongOpenHashSet set = null;
            for (ServerPlayer player : level.players()) {
                if (player.isSpectator() || !isCarrier(player)) {
                    continue;
                }
                var biome = level.getBiome(player.blockPosition());
                // 過繁茂と極相の中では上乗せしない(過繁茂は湧きの方で効く)。
                if (ModBiomes.isFullyOvergrown(biome)) {
                    continue;
                }
                if (set == null) {
                    set = new LongOpenHashSet();
                    CHUNKS.put(level.dimension(), set);
                }
                set.add(ChunkPos.asLong(player.getBlockX() >> 4, player.getBlockZ() >> 4));
            }
        }
    }

    private ErosionCarrier() {
    }
}
