package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.NetherOvergrowthVariant;
import com.yayuy.overgrownvegetation.registry.OvergrowthVariant;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 汚染がチャンクの地表を一定割合以上覆ったら、そのチャンクのバイオームを
 * {@link ModBiomes#OVERGROWN} に書き換える。
 *
 * <h2>仕組み</h2>
 * <p>バニラにはロード済みチャンクのバイオームを後から書き換えて全クライアントへ再送する
 * 仕組みが {@code /fillbiome} コマンド用に既に用意されている
 * ({@link net.minecraft.server.commands.FillBiomeCommand} 参照)。ここではそれと全く同じ経路
 * ({@link ChunkAccess#fillBiomesFromNoise} で書き換えてから
 * {@link net.minecraft.server.level.ChunkMap#resendBiomesForChunks} で再送する)を使う。
 *
 * <h2>縁のなだらか化 (2026-09-09)</h2>
 * <p>バイオームは 4 ブロック四方(quart)単位で保存されているので、チャンクを丸ごと単一バイオームに
 * 塗ると境界が必ずチャンクの辺(16 の倍数)に沿った直線・直角の階段状になる。これを避けるため、
 * {@link #flowConvert} はチャンク内を quart 単位で塗り分け、<b>隣接する別バイオームとの縫い目</b>だけを
 * {@link #seamNoise01} のノイズで最大 {@code overgrownBiomeEdgeNoise} ブロック後退させて波打たせる。
 * <ul>
 *     <li>相手が繁茂系でない(海など、これ以上変換されない)縁 → こちらが後退し、後退した quart は
 *     現状のバイオームを透過させる(= 相手のバイオームが覗く)。緑と青の海岸線が波打つ。</li>
 *     <li>相手も繁茂系で別キー(過繁茂 ↔ 繁茂 など) → 汚染度(rank)の高い方をまっすぐ、低い方だけを
 *     後退させ、後退した quart には相手(高汚染側)のバイオームを描き込む。縫い目は 1 本の曲線になる。</li>
 *     <li>相手が同一キー → 縫い目は不可視なので何もしない(まっすぐ塗る)。</li>
 * </ul>
 * 縫い目を挟む 2 チャンクは同じノイズ場・同じ「どちらが後退するか」判定を使うので、隙間も重なりも
 * できない。片方が変換されるたびに直交隣接チャンクの縁を描き直す({@link #flowConvertInto})ので、
 * フロンティアが進むと波打つ縫い目もそれに追随して外側へ移り、取り残しの島はできない。
 * {@code overgrownBiomeEdgeSmoothing} を false にすると従来通りチャンク単位の一括塗りに戻る。
 *
 * <p>{@link ModBiomes#OVERGROWN} 自体はどの multi-noise 設定(バイオーム選択)からも参照して
 * いないので、ワールド生成中に自然に選ばれることは無い。ここで明示的に書き込んだチャンクにしか
 * 現れない、書き換え専用のバイオーム。
 *
 * <p>{@link #maybeConvertToOvergrowth} は、地表の汚染割合が閾値に届く前でも胞子濃度が 100% に
 * 達したチャンクを「繁茂バイオーム」({@link ModBiomes#OVERGROWTH})へ書き換える。繁茂バイオームは
 * その後、実際の汚染が進んで {@link #maybeConvert} の閾値に達した時点で {@link ModBiomes#OVERGROWN}
 * へ昇格する({@link #maybeConvert} の早期リターンは過繁茂のみを弾くので、繁茂チャンクは走査対象に残る)。
 *
 * <p>クライアントへの再送は tick の終わりにまとめて送る({@link #onServerTickEnd}、2026-09-24)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OvergrownBiomeConverter {

    /** 判定に使うチャンクの列数(16x16 固定)。 */
    private static final int COLUMNS = 16 * 16;

    /**
     * 陸のチャンクで、未汚染の水底を分母から除いたあとに残る列がこれ未満なら「ほぼ湖」なので、
     * 水底も含めた通常の割合判定(= 変換しない)に戻す。
     */
    private static final int MIN_LAND_COLUMNS = 48;

    /** 縁のにじみを描き直す方向(直交 4 方向)。 */
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    /**
     * 指定チャンクの地表(列ごとの最上部ブロック、{@link Heightmap.Types#WORLD_SURFACE})を調べ、
     * 汚染ブロックの割合が設定の閾値以上ならバイオームを書き換える。
     *
     * <p>既に {@link ModBiomes#OVERGROWN} なチャンクは 256 列を走査しない。ただし縁のなだらか化が
     * 有効なときは、隣接チャンクの進行に合わせて縁のにじみだけ描き直す({@link #flowConvert} は
     * 変化が無ければ何もしないので空振りは安い)。
     *
     * <p>256列の走査自体も、結果が既に確定した時点で打ち切る(2026-08-24 軽量化、判定結果は
     * 従来と完全に同一)。{@code required} は「割合が閾値以上」を満たすために必要な最小の
     * 汚染列数——列ごとに数えながら (1) 既に {@code required} 列に達していれば残りを見るまでもなく
     * 「変換する」と確定、(2) 逆に残り全列が仮にすべて汚染済みだったとしても {@code required} に
     * 届かないなら「変換しない」と確定、のどちらかが成立した時点でその場で打ち切る。
     * 陸のチャンクの (2) は、水を除いた割合の判定も「残り全列が汚染済み」で届かないときに限る(2026-09-24)。
     */
    public static void maybeConvert(ServerLevel level, ChunkPos chunkPos) {
        if (CorruptionSwitch.isOff()) {
            return; // /ov corruption false(2026-10-10 ユーザー指定): バイオームも書き換えない
        }
        if (NetherLedger.active(level)) {
            return; // 2026-11-11 ネザーのバイオームは根の台帳の段階だけが決める(setNetherStageBiome)
        }
        if (!OvergrownVegetationConfig.COMMON.convertToOvergrownBiome.get()) {
            return;
        }

        BlockPos samplePos = chunkPos.getMiddleBlockPosition(level.getMinBuildHeight());
        Holder<Biome> currentBiome = level.getBiome(samplePos);
        // ここは過繁茂(陸・海・川)のみを弾く(isOvergrownLike ではない)。繁茂バイオームのチャンクは、
        // 汚染が進んで閾値に達したら過繁茂へ昇格させたいので、走査対象に残す必要がある。
        if (ModBiomes.isFullyOvergrown(currentBiome)) {
            // 既に過繁茂。汚染は増える一方なので閾値の再判定は不要。縁のにじみだけ、
            // 隣接チャンクが追いついた分に合わせて描き直す。
            if (edgeSmoothingEnabled()) {
                ResourceKey<Biome> key = currentBiome.unwrapKey().orElse(null);
                if (key != null) {
                    flowConvert(level, chunkPos, key, null);
                }
            }
            return;
        }

        if (SporeDeviceData.inhibits(level, chunkPos, SporeDeviceData.Inhibit.BIOME_CONVERSION)) {
            // 稼働中の胞子除去装置の効果範囲内のチャンクは、地表がどれだけ汚染されてもバイオームは書き換えない。
            return;
        }

        if (NetherSurface.applies(level)) {
            // 天井のある次元(ネザー)は高さマップが天井の岩盤を返すので、列の床を全部見る(2026-10-02)。
            if (netherThresholdReached(level, chunkPos)) {
                convertTo(level, level.getChunk(chunkPos.x, chunkPos.z),
                        targetKey(level, chunkPos, Terrain.LAND, OvergrowthVariant.Stage.OVERGROWN));
            }
            return;
        }

        Terrain terrain = classifyTerrain(currentBiome);
        // 列の「流体を除いた最上部」を見る。海・川は水底、陸も湿地の池・棚田の水底(泥)が汚染として数えられるよう
        // OCEAN_FLOOR に統一(2026-09-10。以前は陸だけ WORLD_SURFACE で、池が広がるほど閾値に届かなくなっていた)。
        Heightmap.Types heightmap = Heightmap.Types.OCEAN_FLOOR;

        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        double threshold = OvergrownVegetationConfig.COMMON.overgrownBiomeThreshold.get();
        // 「割合 >= threshold」を満たすのに必要な最小の汚染列数。列は整数個しか無いので、
        // 元の判定(corrupted / COLUMNS >= threshold)と完全に同値になるよう切り上げる。
        int required = (int) Math.ceil(threshold * COLUMNS);

        int corrupted = 0;
        int processed = 0;
        int counted = 0;
        boolean thresholdReached = false;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        scan:
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                processed++;
                int surfaceY = chunk.getHeight(heightmap, x, z); // ChunkAccess#getHeight は既に「最上部ブロックの Y」(LevelReader 版と違い -1 済み)
                cursor.set(chunkPos.getMinBlockX() + x, surfaceY, chunkPos.getMinBlockZ() + z);
                // 氷は OCEAN_FLOOR に入るので、凍った湖・川は氷が「汚染されていない地面」に数えられて閾値に届かなかった。
                // 氷と水をくぐって本当の底を見る(2026-10-16)。陸ならその上が水なので湖と同じく分母から外れる。
                if (MeltedIce.isIce(chunk.getBlockState(cursor))) {
                    int minY = chunk.getMinBuildHeight();
                    BlockState below;
                    do {
                        surfaceY--;
                        cursor.setY(surfaceY);
                        below = chunk.getBlockState(cursor);
                        // 水と、水の中の動きを止めない物(海草・昆布)はくぐる。OCEAN_FLOOR と同じ決まり。
                    } while (surfaceY > minY && (MeltedIce.isIce(below)
                            || below.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && !below.blocksMotion()));
                }
                // cursor は必ずこのチャンクの中なので、Level 経由(毎回チャンク検索)ではなくチャンクから直接読む。
                if (OvergrownLayerData.isCorruptedCell(chunk, cursor, chunk.getBlockState(cursor))) { // 記録で覆ったマスも(2026-10-30 夜)
                    corrupted++;
                    counted++;
                } else if (!terrain.isWatery()) {
                    // 陸のチャンクでは、未汚染の水底(湖・池・川の底)は分母に入れない。汚染は陸から広がり、
                    // 水底は繁茂系バイオームになってから浸食されるので、水の多いチャンクが永遠に閾値に届かない
                    // (2026-09-10 の報告)のを防ぐ。海・川バイオームのチャンクは従来どおり水底の汚染割合で判定。
                    cursor.setY(surfaceY + 1);
                    if (!chunk.getBlockState(cursor).getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                        counted++;
                    }
                } else {
                    counted++;
                }
                if (corrupted >= required) {
                    // 既に閾値到達が確定した。残りの列を見ても結論は変わらないので打ち切る。
                    thresholdReached = true;
                    break scan;
                }
                if (terrain.isWatery() && corrupted + (COLUMNS - processed) < required) {
                    // 残り全列が仮にすべて汚染済みだったとしても閾値に届かないことが確定した。
                    break scan;
                }
                if (!terrain.isWatery() && threshold <= 1.0D && corrupted + (COLUMNS - processed) < required) {
                    // 陸: 残り全列が汚染済み(= 分母も最大)の場合が下の「水を除いた割合」判定の最良ケース。
                    // 閾値 <= 1 なら ceil(threshold * n) は n が 1 増えても高々 1 しか増えないので、
                    // 未汚染列や水の列が混ざってもこれより有利にはならない。それでも届かなければ変換しないと確定
                    // (2026-09-24 軽量化、判定結果は従来と完全に同一)。
                    int bestCounted = counted + (COLUMNS - processed);
                    if (bestCounted < MIN_LAND_COLUMNS
                            || corrupted + (COLUMNS - processed) < (int) Math.ceil(threshold * bestCounted)) {
                        return;
                    }
                }
            }
        }
        if (!thresholdReached && !terrain.isWatery() && counted >= MIN_LAND_COLUMNS) {
            // 水を除いた列のうち汚染列の割合で判定(水面下の未汚染列を除外した分母)。
            thresholdReached = corrupted >= (int) Math.ceil(threshold * counted);
        }

        if (thresholdReached) {
            convertTo(level, chunk, targetKey(level, chunkPos, terrain, OvergrowthVariant.Stage.OVERGROWN));
        }

    }
    /** ネザーのチャンクの閾値判定を、同じチャンクで何度も繰り返さない間隔(仮決め)。 */
    private static final long NETHER_RESCAN_TICKS = 100L;
    /** ネザーのチャンク → 最後に閾値を判定したゲーム時刻。 */
    private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap NETHER_LAST_SCAN =
            new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    /**
     * ネザー版の閾値判定(2026-10-02)。列ごとに床({@link NetherSurface#floors})を全部見て、<b>どれか 1 段の床が汚染されている列</b>を
     * 汚染列として数える。床の無い列(溶岩の海の上・空洞)は分母に入れない。床のある列が {@link #MIN_LAND_COLUMNS} 未満なら変換しない。
     * 割合の閾値はオーバーワールドと同じ {@code overgrownBiomeThreshold}。
     *
     * <p>汚染の 1 マスごとに呼ばれうるが、列の床探しは 1 チャンクで数千マス読むので、同じチャンクは
     * {@link #NETHER_RESCAN_TICKS} のあいだ判定し直さない。
     */
    private static boolean netherThresholdReached(ServerLevel level, ChunkPos chunkPos) {
        long now = level.getGameTime();
        long key = chunkPos.toLong();
        if (now - NETHER_LAST_SCAN.getOrDefault(key, Long.MIN_VALUE / 2) < NETHER_RESCAN_TICKS) {
            return false;
        }
        if (NETHER_LAST_SCAN.size() > 4096) {
            NETHER_LAST_SCAN.clear();
        }
        NETHER_LAST_SCAN.put(key, now);

        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        double threshold = OvergrownVegetationConfig.COMMON.overgrownBiomeThreshold.get();
        int[] floors = new int[NetherSurface.MAX_FLOORS];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int corrupted = 0;
        int counted = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int wx = chunkPos.getMinBlockX() + x;
                int wz = chunkPos.getMinBlockZ() + z;
                int n = NetherSurface.floors(level, chunk, wx, wz, floors);
                if (n == 0) {
                    continue;
                }
                counted++;
                for (int i = 0; i < n; i++) {
                    if (OvergrownLayerData.isCorruptedCell(chunk, cursor.set(wx, floors[i], wz), chunk.getBlockState(cursor))) { // 記録で覆ったマスも
                        corrupted++;
                        break;
                    }
                }
            }
        }
        return counted >= MIN_LAND_COLUMNS && corrupted >= (int) Math.ceil(threshold * counted);
    }

    /** チャンクの地形分類。繁茂系バイオームの「陸 / 海 / 川」の 3 系統に対応する。 */
    private enum Terrain {
        LAND,
        OCEAN,
        RIVER;

        /** 水中(海・川)か。地表走査で水底({@link Heightmap.Types#OCEAN_FLOOR})を見るべきかどうか。 */
        boolean isWatery() {
            return this != LAND;
        }
    }

    /**
     * このチャンクを書き換える先のバイオームキー。海は環境を問わず統一
     * ({@link ModBiomes#OVERGROWN_OCEAN} / {@link ModBiomes#OVERGROWTH_OCEAN})、陸・川は
     * 地域マップ({@link OvergrowthRegionMap})が決める環境({@link OvergrowthVariant})の
     * 過繁茂 / 繁茂キー。地域マップはシードと座標だけの決定的関数なので、繁茂 → 過繁茂の昇格や
     * 縁の描き直しでも同じ環境が選ばれる。
     */
    private static ResourceKey<Biome> targetKey(ServerLevel level, ChunkPos chunkPos, Terrain terrain, OvergrowthVariant.Stage stage) {
        if (level.dimension() == Level.END) {
            // エンドは段階も環境も無く 1 種だけ(2026-10-02)。繁茂・極相を頼まれても同じキーを返す
            // (無いキーを引くと getHolderOrThrow で落ちる。胞子の見回りはエンドのチャンクにも来る)。
            return ModBiomes.OVERGROWN_END;
        }
        if (level.dimension() == Level.NETHER) {
            // ネザーは元のバイオームの種類で決める(地域マップの湿地・密林は使わない。2026-10-02)。
            // 書き換えた後は全高が同じキーになるので、どの高さで見ても同じ種類が選ばれる。
            Holder<Biome> source = level.getBiome(chunkPos.getMiddleBlockPosition(NetherSurface.BIOME_SAMPLE_Y));
            return NetherOvergrowthVariant.of(source).key(stage);
        }
        if (terrain == Terrain.OCEAN) {
            return switch (stage) {
                case OVERGROWTH -> ModBiomes.OVERGROWTH_OCEAN;
                case OVERGROWN -> ModBiomes.OVERGROWN_OCEAN;
            };
        }
        OvergrowthVariant variant = OvergrowthRegionMap.variantAt(level, chunkPos);
        return variant.key(terrain == Terrain.RIVER, stage);
    }

    /**
     * このチャンクを「陸 / 海 / 川」どの系統の繁茂系バイオームへ書き換えるべきか。
     * 既に繁茂系バイオームなら、その系統を維持する(段階だけ上げる)。
     * まだ繁茂系でなければ、元のバイオームタグ(川 → 海 → 陸 の順で判定)から決める。
     */
    private static Terrain classifyTerrain(Holder<Biome> currentBiome) {
        if (ModBiomes.isOvergrownRiver(currentBiome)) {
            return Terrain.RIVER;
        }
        if (ModBiomes.isOvergrownOcean(currentBiome)) {
            return Terrain.OCEAN;
        }
        if (ModBiomes.isOvergrownLike(currentBiome)) {
            return Terrain.LAND; // 既に陸側の繁茂系。段階だけ上げる。
        }
        if (currentBiome.is(BiomeTags.IS_RIVER)) {
            return Terrain.RIVER;
        }
        if (currentBiome.is(BiomeTags.IS_OCEAN) || currentBiome.is(BiomeTags.IS_DEEP_OCEAN)) {
            return Terrain.OCEAN;
        }
        return Terrain.LAND;
    }

    /**
     * 胞子濃度が 100% に達したチャンクのバイオームを「繁茂バイオーム」({@link ModBiomes#OVERGROWTH})へ
     * 書き換える。地表の汚染割合はまだ問わない(それが閾値に達したら {@link #maybeConvert} が
     * {@link ModBiomes#OVERGROWN} へ昇格させる)。
     *
     * <p>既に繁茂系バイオーム({@link ModBiomes#isOvergrownLike})なら何もしない。
     * {@link com.yayuy.overgrownvegetation.spore.DepositBiomeSweep} からプレイヤー周辺のチャンクに対して
     * 定期的に呼ばれる。
     */
    public static void maybeConvertToOvergrowth(ServerLevel level, ChunkPos chunkPos) {
        if (CorruptionSwitch.isOff()) {
            return; // /ov corruption false(2026-10-10 ユーザー指定): バイオームも書き換えない
        }
        if (NetherLedger.active(level)) {
            return; // 2026-11-11 ネザーのバイオームは根の台帳の段階だけが決める
        }
        if (!OvergrownVegetationConfig.COMMON.convertToOvergrownBiome.get()
                || !OvergrownVegetationConfig.COMMON.convertToOvergrowthBiome.get()) {
            return;
        }

        BlockPos samplePos = chunkPos.getMiddleBlockPosition(level.getMinBuildHeight());
        Holder<Biome> currentBiome = level.getBiome(samplePos);
        if (ModBiomes.isOvergrownLike(currentBiome)) {
            return;
        }
        if (SporeDeviceData.inhibits(level, chunkPos, SporeDeviceData.Inhibit.BIOME_CONVERSION)) {
            // 稼働中の胞子除去装置の効果範囲内は繫茂バイオームにもしない。
            return;
        }
        if (SporeConcentration.useField(level)) {
            // 胞子の場(2026-10-23 第 2 段、相談セッションの答え 2): 読み込まれたチャンクで、降り積もりが発芽のしきい値の半分以上・
            // または発芽して 2 日未満(繁茂の段階)なら繁茂の札。過繁茂は台帳で
            com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.Cell cell =
                    com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.cell(level, chunkPos.x, chunkPos.z);
            double half = com.yayuy.overgrownvegetation.data.DesignData.sporeField().field().germThreshold * 0.5D;
            if (cell == null || !(cell.dep() >= half || cell.stage() == 1)) {
                return;
            }
        } else {
            // 場が止まっている(spore_field.json の enabled=false・場の無い次元)間は繁茂にしない(2026-10-30)
            return;
        }

        ResourceKey<Biome> target = targetKey(level, chunkPos, classifyTerrain(currentBiome), OvergrowthVariant.Stage.OVERGROWTH);
        convertTo(level, level.getChunk(chunkPos.x, chunkPos.z), target);
    }

    /**
     * 指定チャンクのバイオームを、地表の汚染割合や胞子濃度に関係なく「過繁茂」段階
     * ({@link ModBiomes#OVERGROWN} / {@code _OCEAN} / {@code _RIVER})へ強制的に書き換える。
     * フェーズ1開始の地割れ演出({@link com.yayuy.overgrownvegetation.quake.EarthquakeFissure})が、
     * 裂け目が跨ぐチャンクを即座に過繁茂化するために使う。既に過繁茂なら何もしない。
     * {@link OvergrownVegetationConfig.Common#convertToOvergrownBiome} 設定には従わない(スクリプト的なイベントのため)。
     */
    public static void forceOvergrown(ServerLevel level, ChunkPos chunkPos) {
        if (CorruptionSwitch.isOff()) {
            return; // /ov corruption false(2026-10-10 ユーザー指定): バイオームも書き換えない
        }
        BlockPos samplePos = chunkPos.getMiddleBlockPosition(level.getMinBuildHeight());
        Holder<Biome> currentBiome = level.getBiome(samplePos);
        if (ModBiomes.isFullyOvergrown(currentBiome)) {
            return;
        }
        ResourceKey<Biome> target = targetKey(level, chunkPos, classifyTerrain(currentBiome), OvergrowthVariant.Stage.OVERGROWN);
        flowConvert(level, chunkPos, target, null);
        recordOvergrownChunk(level, chunkPos, target);
    }

    /**
     * ネザーの根の台帳の段階でバイオームを書き換える(2026-11-11 骨組み。段階 1〜2 = 繁茂・3〜5 = 過繁茂)。
     * 数えるのは台帳({@link NetherLedger})なので、ここでは面積・ポイントに数えない。同じキーなら何もしない。
     */
    public static void setNetherStageBiome(ServerLevel level, ChunkPos chunkPos, boolean overgrown) {
        if (CorruptionSwitch.isOff()) {
            return;
        }
        ResourceKey<Biome> target = targetKey(level, chunkPos, Terrain.LAND,
                overgrown ? OvergrowthVariant.Stage.OVERGROWN : OvergrowthVariant.Stage.OVERGROWTH);
        Holder<Biome> current = level.getBiome(chunkPos.getMiddleBlockPosition(NetherSurface.BIOME_SAMPLE_Y));
        if (current.is(target)) {
            return;
        }
        flowConvert(level, chunkPos, target, null);
    }

    /**
     * {@link #forceOvergrown} をまとめて行う版。多数のチャンクを一度に過繁茂化するとき
     * (地割れ演出など)、クライアントへの再送を 1 回のパケットにまとめて負荷を下げる。
     * batch 内のチャンク同士の縫い目は波打たせない(= 塊の外周だけがなだらかになる)。
     */
    public static int forceOvergrownBatch(ServerLevel level, Iterable<ChunkPos> chunkPositions) {
        if (CorruptionSwitch.isOff()) {
            return 0; // /ov corruption false(2026-10-10 ユーザー指定): バイオームも書き換えない
        }
        LongSet batch = new LongOpenHashSet();
        List<ChunkPos> order = new ArrayList<>();
        for (ChunkPos chunkPos : chunkPositions) {
            if (batch.add(chunkPos.toLong())) {
                order.add(chunkPos);
            }
        }

        List<ChunkAccess> changed = new ArrayList<>();
        LongSet changedSet = new LongOpenHashSet();
        List<ChunkPos> convertedChunks = new ArrayList<>();
        for (ChunkPos chunkPos : order) {
            Holder<Biome> currentBiome = level.getBiome(chunkPos.getMiddleBlockPosition(level.getMinBuildHeight()));
            if (ModBiomes.isFullyOvergrown(currentBiome)) {
                continue;
            }
            flowConvertInto(level, chunkPos, targetKey(level, chunkPos, classifyTerrain(currentBiome), OvergrowthVariant.Stage.OVERGROWN),
                    batch, changed, changedSet);
            convertedChunks.add(chunkPos);
        }
        finishResend(level, changed);
        if (!convertedChunks.isEmpty()) {
            // 過繁茂化したチャンク数をまとめて 1 回で報告する(フェーズ進行の判定も 1 回で済む)。
            if (level.dimension() == Level.OVERWORLD) {
                PhaseData.get(level).markOvergrownChunks(level, convertedChunks);
            } else {
                // 台帳のキーは次元を持たないので、ほかの次元のチャンクは台帳へ書かない(recordOvergrownChunk 参照)。
                NetherLedger.record(level, convertedChunks);
            }
        }
        // 既に過繁茂だったチャンクは上の早期 continue で飛ばしているので、渡した数とは一致しない。
        // 呼び出し側が「渡した数」を成果として出すと、1 マスも変わっていないのに成功したように見える。
        return convertedChunks.size();
    }

    /**
     * チャンク(全高)のバイオームを {@code targetKey} へ書き換え、全クライアントへ再送する。
     * 縁のなだらか化が有効なら quart 単位で塗り分け、直交隣接チャンクの縁も描き直す。
     */
    private static void convertTo(ServerLevel level, ChunkAccess chunk, ResourceKey<Biome> targetKey) {
        flowConvert(level, chunk.getPos(), targetKey, null);
        recordOvergrownChunk(level, chunk.getPos(), targetKey);
    }

    /**
     * 過繁茂段階({@link ModBiomes#isFullyOvergrownKey})への書き換えを 1 チャンクぶん
     * フェーズ進行へ報告する({@link PhaseData#addOvergrownChunks} — フェーズ2以降の昇格条件と
     * {@code points.perOvergrownChunk} のポイント)。繁茂段階への書き換えは数えない。
     *
     * <p>呼び出し元はどれも「まだ過繁茂でないチャンク」だけを書き換える
     * ({@link #maybeConvert} / {@link #forceOvergrown} / {@link #forceOvergrownBatch} の早期リターン)ので、
     * 同じチャンクが二重に数えられることはない。厳密には、縁のなだらか化の振幅
     * ({@code overgrownBiomeEdgeNoise})を 8 より大きくするとチャンク中央の quart まで後退しうるため、
     * 「中央を見て過繁茂かどうか判定する」早期リターンをすり抜けて同じチャンクを数え直す可能性が残る。
     * 進行の目安として許容する(既定値 8 では起こらない)。
     *
     * <p><b>オーバーワールド以外の次元は台帳へ書かない</b>(2026-10-02)。台帳のキーはチャンクの座標だけなので、
     * ネザーの過繁茂チャンクを書くと同じ座標のオーバーワールドのチャンクとして数えられてしまう。面積とポイントは
     * 次元をまたいで共通(フェーズはワールド単位)なので、{@link PhaseData#addOvergrownChunks} で今までどおり数える。
     */
    private static void recordOvergrownChunk(ServerLevel level, ChunkPos chunkPos, ResourceKey<Biome> targetKey) {
        if (level.dimension() != Level.OVERWORLD) {
            if (ModBiomes.isFullyOvergrownKey(targetKey)) {
                NetherLedger.record(level, java.util.List.of(chunkPos));
            }
            return;
        }
        if (ModBiomes.isFullyOvergrownKey(targetKey)) {
            PhaseData.get(level).markOvergrownChunk(level, chunkPos);
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // 縁のなだらか化つきバイオーム書き換え
    // ------------------------------------------------------------------------------------------------------

    /** {@code overgrownBiomeEdgeSmoothing} が true かつノイズ振幅が正か。 */
    private static boolean edgeSmoothingEnabled() {
        return OvergrownVegetationConfig.COMMON.overgrownBiomeEdgeSmoothing.get()
                && OvergrownVegetationConfig.COMMON.overgrownBiomeEdgeNoise.get() > 1.0e-4D;
    }

    /** {@link #flowConvertInto} を実行し、変化したチャンクをまとめて再送する。 */
    private static void flowConvert(ServerLevel level, ChunkPos cp, ResourceKey<Biome> targetKey, @Nullable LongSet batch) {
        List<ChunkAccess> changed = new ArrayList<>();
        LongSet changedSet = new LongOpenHashSet();
        flowConvertInto(level, cp, targetKey, batch, changed, changedSet);
        finishResend(level, changed);
    }

    /**
     * チャンク {@code cp} を {@code targetKey} へ書き換え、変化があれば直交隣接の繁茂系チャンクの
     * 縁も描き直す。再送はしない(呼び出し側が {@code changed} をまとめて送る)。
     *
     * @param batch      これから同じ変換対象になるチャンク集合(縫い目を波打たせない)。無ければ null。
     * @param changed    このパスで書き換わったチャンクの蓄積先。
     * @param changedSet {@code changed} の重複排除用(packed chunk long)。
     */
    private static void flowConvertInto(ServerLevel level, ChunkPos cp, ResourceKey<Biome> targetKey,
                                        @Nullable LongSet batch, List<ChunkAccess> changed, LongSet changedSet) {
        if (!repaintChunk(level, cp, targetKey, batch)) {
            return; // 変化なし → 縫い目状態も変わらないので隣接の描き直しも不要
        }
        addChanged(level, cp, changed, changedSet);

        for (Direction dir : HORIZONTAL) {
            int ncx = cp.x + dir.getStepX();
            int ncz = cp.z + dir.getStepZ();
            if (batch != null && batch.contains(ChunkPos.asLong(ncx, ncz))) {
                continue; // その隣接チャンク自身が batch ループで処理される
            }
            if (level.getChunkSource().getChunkNow(ncx, ncz) == null) {
                // 読み込まれていない・読み込みの途中(2026-10-06: hasChunk はチケットしか見ないので、途中のチャンクを
                // 描き直そうとして getChunk でサーバースレッドが読み込みを待っていた)。
                continue;
            }
            ResourceKey<Biome> nKey = biomeKeyAt(level, (ncx << 4) + 8, (ncz << 4) + 8);
            if (nKey == null || !ModBiomes.isOvergrownKey(nKey)) {
                continue; // 繁茂系でない隣接は縫い目を描かない(こちらが後退して透過するだけ)
            }
            // 隣接の描き直しでは batch を渡さない(cp は既に確定済みなので普通に見える)。
            if (repaintChunk(level, new ChunkPos(ncx, ncz), nKey, null)) {
                addChanged(level, new ChunkPos(ncx, ncz), changed, changedSet);
            }
        }
    }

    private static void addChanged(ServerLevel level, ChunkPos cp, List<ChunkAccess> changed, LongSet changedSet) {
        if (changedSet.add(cp.toLong())) {
            changed.add(level.getChunk(cp.x, cp.z));
        }
    }

    /**
     * クライアントへのバイオームの再送を tick の終わりにまとめるための保留分(2026-09-24 軽量化、報告 1494)。
     * 次元ごとの書き換わったチャンク({@link ChunkPos#toLong})。サーバースレッドだけが触り、サーバー停止で捨てる。
     *
     * <p>以前は書き換えのたびに(隣の描き直しを含めて数チャンクずつ)その場で再送していたので、
     * 同じ tick に何度も書き換えると同じチャンクを何通にも分けて送り、クライアントはパケットのたびに
     * 周りのチャンクを描き直していた。サーバー側のバイオームと胞子濃度のキャッシュ破棄は今までどおりその場で行い、
     * クライアントへの反映だけが同じ tick の終わりまで遅れる。
     */
    private static final Map<ResourceKey<Level>, LongLinkedOpenHashSet> PENDING_RESEND = new HashMap<>();

    /**
     * チャンクのバイオームを世界生成のときのものへ戻す(押し返し 2026-11-18。台帳から外したとき)。生成器のバイオームの元に
     * 同じ座標を聞けば決まる(記録は要らない)。クライアントへは tick の終わりにまとめて送る。
     */
    public static void restoreOriginal(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
        chunk.fillBiomesFromNoise(level.getChunkSource().getGenerator().getBiomeSource(),
                level.getChunkSource().randomState().sampler());
        chunk.setUnsaved(true);
        finishResend(level, List.<ChunkAccess>of(chunk));
    }

    /** チャンク全体を 1 つのバイオームで塗る(縁のなだらか化なし。焼けた荒地 2026-11-18)。 */
    public static void paintWhole(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk, ResourceKey<Biome> key) {
        Holder<Biome> holder = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME).getHolderOrThrow(key);
        chunk.fillBiomesFromNoise((x, y, z, sampler) -> holder, level.getChunkSource().randomState().sampler());
        chunk.setUnsaved(true);
        finishResend(level, List.<ChunkAccess>of(chunk));
    }

    private static void finishResend(ServerLevel level, List<ChunkAccess> changed) {
        if (changed.isEmpty()) {
            return;
        }
        LongLinkedOpenHashSet pending = PENDING_RESEND.computeIfAbsent(level.dimension(),
                key -> new LongLinkedOpenHashSet());
        for (ChunkAccess chunk : changed) {
            pending.add(chunk.getPos().toLong());
        }
    }

    /**
     * この tick に書き換わったチャンクのバイオームを、次元ごとに 1 回の {@code resendBiomesForChunks} でまとめて送る
     * (プレイヤーごとに 1 通。{@link #PENDING_RESEND})。全次元の tick が終わってから送るので、
     * どの次元のどの処理(コマンドを含む)で書き換えても取りこぼさない。読み込まれなくなったチャンクは送らない
     * (見ているプレイヤーはもういないか、読み込み直しで新しいバイオームごと受け取る)。
     */
    @SubscribeEvent
    public static void onServerTickEnd(ServerTickEvent.Post event) {
        if (PENDING_RESEND.isEmpty()) {
            return;
        }
        for (Map.Entry<ResourceKey<Level>, LongLinkedOpenHashSet> entry : PENDING_RESEND.entrySet()) {
            ServerLevel level = event.getServer().getLevel(entry.getKey());
            if (level == null) {
                continue;
            }
            List<ChunkAccess> chunks = new ArrayList<>(entry.getValue().size());
            LongIterator it = entry.getValue().iterator();
            while (it.hasNext()) {
                long key = it.nextLong();
                LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
                if (chunk != null) {
                    chunks.add(chunk);
                }
            }
            if (!chunks.isEmpty()) {
                level.getChunkSource().chunkMap.resendBiomesForChunks(chunks);
            }
        }
        PENDING_RESEND.clear();
    }

    /** ワールドを閉じたら送りそびれた再送を捨てる(次に開いた別のワールドの同じ座標へ送らないため)。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING_RESEND.clear();
        NETHER_LAST_SCAN.clear();
    }

    /**
     * チャンク {@code cp} の quart 割り当てを計算し、現状と違えば {@link ChunkAccess#fillBiomesFromNoise}
     * で書き換える。
     *
     * @return 実際に書き換えたら true。
     */
    private static boolean repaintChunk(ServerLevel level, ChunkPos cp, ResourceKey<Biome> targetKey, @Nullable LongSet batch) {
        LevelChunk chunk = level.getChunk(cp.x, cp.z);
        Registry<Biome> reg = level.registryAccess().registryOrThrow(Registries.BIOME);
        Holder<Biome> targetHolder = reg.getHolderOrThrow(targetKey);

        boolean smooth = edgeSmoothingEnabled();
        double amp = smooth ? OvergrownVegetationConfig.COMMON.overgrownBiomeEdgeNoise.get() : 0.0D;
        long seed = level.getSeed();

        int baseQX = QuartPos.fromBlock(cp.getMinBlockX());
        int baseQZ = QuartPos.fromBlock(cp.getMinBlockZ());
        int surfQuartY = QuartPos.fromBlock(level.getSeaLevel());

        // 4 方向の隣接チャンクのバイオームキー(縫い目判定用。方向ごとに 1 度だけ引く)。
        // null = ロード外(→ 繁茂系でない扱い)。batchNeighbour[] は「これから同じ変換対象」フラグ。
        ResourceKey<Biome>[] neighbourKeys = smooth ? newKeyArray() : null;
        boolean[] batchNeighbour = smooth ? new boolean[HORIZONTAL.length] : null;
        if (smooth) {
            for (int d = 0; d < HORIZONTAL.length; d++) {
                Direction dir = HORIZONTAL[d];
                int ncx = cp.x + dir.getStepX();
                int ncz = cp.z + dir.getStepZ();
                batchNeighbour[d] = batch != null && batch.contains(ChunkPos.asLong(ncx, ncz));
                neighbourKeys[d] = level.getChunkSource().getChunkNow(ncx, ncz) != null
                        ? biomeKeyAt(level, (ncx << 4) + 8, (ncz << 4) + 8)
                        : null;
            }
        }

        // intended[lx][lz]: この quart 列に塗る Holder。null = 現状維持(y ごとに透過)。
        Holder<Biome>[][] intended = newHolderGrid();
        boolean changed = false;
        for (int lx = 0; lx < 4; lx++) {
            for (int lz = 0; lz < 4; lz++) {
                Holder<Biome> want = smooth
                        ? decideQuart(reg, cp, targetKey, targetHolder, neighbourKeys, batchNeighbour, lx, lz, amp, seed)
                        : targetHolder;
                intended[lx][lz] = want;

                Holder<Biome> current = chunk.getNoiseBiome(baseQX + lx, surfQuartY, baseQZ + lz);
                Holder<Biome> effective = (want == null) ? current : want;
                if (!sameBiome(current, effective)) {
                    changed = true;
                }
            }
        }
        if (!changed) {
            return false;
        }

        Holder<Biome>[][] grid = intended;
        chunk.fillBiomesFromNoise((qx, qy, qz, sampler) -> {
            int lx = qx - baseQX;
            int lz = qz - baseQZ;
            if (lx < 0 || lx > 3 || lz < 0 || lz > 3) {
                return chunk.getNoiseBiome(qx, qy, qz);
            }
            Holder<Biome> want = grid[lx][lz];
            return want != null ? want : chunk.getNoiseBiome(qx, qy, qz);
        }, level.getChunkSource().randomState().sampler());
        chunk.setUnsaved(true);
        return true;
    }

    /**
     * quart 列 {@code (lx,lz)} に塗るバイオームを決める。
     *
     * <p>4 方向の隣接チャンクとの縫い目を順に見て、この quart がその縫い目の「波打つ境界」より
     * 外側(隣の側)に来ているかを判定する。外側に来ていれば:
     * <ul>
     *     <li>相手が繁茂系でない → {@code null}(現状維持=相手を透過)。</li>
     *     <li>相手も繁茂系で別キーかつ「後退するのはこちら」なら → 相手(汚染度の高い方優先)の Holder。</li>
     * </ul>
     * どの縫い目でも後退しなければ {@code targetHolder}。
     */
    @Nullable
    private static Holder<Biome> decideQuart(Registry<Biome> reg, ChunkPos cp,
                                             ResourceKey<Biome> targetKey, Holder<Biome> targetHolder,
                                             ResourceKey<Biome>[] neighbourKeys, boolean[] batchNeighbour,
                                             int lx, int lz, double amp, long seed) {
        if (lx >= 1 && lx <= 2 && lz >= 1 && lz <= 2) {
            // 中央 2x2 の quart は必ず塗る。チャンク中央のサンプル点(昇格判定・隣接チャンクからの参照)が
            // 縫い目の後退で元のバイオームのまま取り残されると、周囲が「まだ変換されていない」と見なし続けて
            // 縁の後退が固定化するため(2026-09-11)。波打ちは外周 1 列の quart だけで表現する。
            return targetHolder;
        }
        int wx = cp.getMinBlockX() + lx * 4 + 2;
        int wz = cp.getMinBlockZ() + lz * 4 + 2;
        long selfLong = cp.toLong();
        int myRank = rankOf(targetKey);

        Holder<Biome> yieldOvergrown = null;
        int yieldRank = Integer.MIN_VALUE;
        boolean yieldForeign = false;

        for (int d = 0; d < HORIZONTAL.length; d++) {
            if (batchNeighbour[d]) {
                continue; // これから同じ変換対象 → 縫い目なし
            }
            Direction dir = HORIZONTAL[d];
            int ncx = cp.x + dir.getStepX();
            int ncz = cp.z + dir.getStepZ();
            long nl = ChunkPos.asLong(ncx, ncz);

            int inset;      // この quart 中心から、その方向のチャンク境界までの距離(2/6/10/14)
            long seamConst; // 境界プレーンの固定座標(ワールド)
            long seamVary;  // 境界に沿った座標(この quart 中心)
            int axis;       // 0 = 東西(x 固定)/ 1 = 南北(z 固定)
            switch (dir) {
                case EAST -> {
                    inset = (cp.getMaxBlockX() + 1) - wx;
                    seamConst = cp.getMaxBlockX() + 1L;
                    seamVary = wz;
                    axis = 0;
                }
                case WEST -> {
                    inset = wx - cp.getMinBlockX();
                    seamConst = cp.getMinBlockX();
                    seamVary = wz;
                    axis = 0;
                }
                case SOUTH -> {
                    inset = (cp.getMaxBlockZ() + 1) - wz;
                    seamConst = cp.getMaxBlockZ() + 1L;
                    seamVary = wx;
                    axis = 1;
                }
                default -> { // NORTH
                    inset = wz - cp.getMinBlockZ();
                    seamConst = cp.getMinBlockZ();
                    seamVary = wx;
                    axis = 1;
                }
            }
            if (inset > amp) {
                continue; // 縫い目から十分内側 → この方向では後退しえない
            }

            ResourceKey<Biome> nKey = neighbourKeys[d];
            if (nKey == targetKey) {
                continue; // 同一バイオーム → 縫い目は不可視、まっすぐ塗る
            }

            double boundaryInset = amp * seamNoise01(seamConst, seamVary, axis, seed);
            if (inset >= boundaryInset) {
                continue; // 波打つ境界より内側 → 我々の側
            }

            // この quart は縫い目の外側 → 後退する。
            boolean nOvergrown = nKey != null && ModBiomes.isOvergrownKey(nKey);
            if (!nOvergrown) {
                yieldForeign = true; // 海など。透過して相手を覗かせる
                continue;
            }
            int nRank = rankOf(nKey);
            // 縫い目を挟む 2 チャンクで「後退するのは片方だけ」になるよう、
            // 汚染度(rank)の低い方 → 同率ならチャンク long の小さい方、を後退側にする。
            boolean iRetreat = (myRank != nRank) ? myRank < nRank : (selfLong < nl);
            if (!iRetreat) {
                continue; // こちらはまっすぐ塗る側。相手が後退して埋めてくる。
            }
            if (nRank > yieldRank) {
                yieldRank = nRank;
                yieldOvergrown = reg.getHolderOrThrow(nKey);
            }
        }

        if (yieldOvergrown != null) {
            return yieldOvergrown; // 相手(高汚染側)のバイオームを描き込む
        }
        if (yieldForeign) {
            return null; // 現状維持=透過
        }
        return targetHolder;
    }

    /** 繁茂系バイオームの「汚染度」。過繁茂 = 2 / 繁茂 = 1 / それ以外 = 0。 */
    private static int rankOf(@Nullable ResourceKey<Biome> key) {
        if (key == null) {
            return 0;
        }
        if (ModBiomes.isFullyOvergrownKey(key)) {
            return 2;
        }
        if (ModBiomes.isOvergrownKey(key)) {
            return 1;
        }
        return 0;
    }

    @Nullable
    private static ResourceKey<Biome> biomeKeyAt(ServerLevel level, int wx, int wz) {
        return level.getBiome(new BlockPos(wx, level.getSeaLevel(), wz)).unwrapKey().orElse(null);
    }

    private static boolean sameBiome(Holder<Biome> a, Holder<Biome> b) {
        if (a == b) {
            return true;
        }
        ResourceKey<Biome> ak = a.unwrapKey().orElse(null);
        return ak != null && b.is(ak);
    }

    @SuppressWarnings("unchecked")
    private static Holder<Biome>[][] newHolderGrid() {
        return (Holder<Biome>[][]) new Holder<?>[4][4];
    }

    @SuppressWarnings("unchecked")
    private static ResourceKey<Biome>[] newKeyArray() {
        return (ResourceKey<Biome>[]) new ResourceKey<?>[HORIZONTAL.length];
    }

    /**
     * 縫い目に沿ってなだらかに波打つ「境界の後退量(0〜1)」を返す。<br>
     * 縫い目を挟む 2 チャンクが必ず同じ値を得るよう、引数は必ず
     * (境界の固定座標, 境界に沿った座標, 軸) の順で渡すこと。
     *
     * <p>周期は約 65 / 30 / 13 ブロックの正弦波の重ね合わせ。ワールドシードで位相をずらすので
     * ワールドごとに模様が変わる。
     */
    private static double seamNoise01(long constCoord, long varyCoord, int axis, long seed) {
        double phase = (seed & 0xFFFFL) * 0.000191D + axis * 3.17D;
        double c = constCoord * 0.00047D + ((seed >>> 16) & 0xFFFFL) * 0.00011D;
        double p = varyCoord;
        double main = Math.sin(p * 0.0973D + c + phase);
        double mid = 0.45D * Math.sin(p * 0.211D - c * 1.7D + phase * 2.0D);
        double fine = 0.18D * Math.sin(p * 0.487D + c * 0.6D + phase * 0.5D);
        double v = (main + mid + fine) / 1.63D; // おおむね [-1, 1]
        v = Math.max(-1.0D, Math.min(1.0D, v));
        return 0.5D + 0.5D * v; // [0, 1]
    }

    private OvergrownBiomeConverter() {
    }
}
