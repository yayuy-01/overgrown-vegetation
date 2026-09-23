package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.OvergrowthVariant;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
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
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

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
 */
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
     */
    public static void maybeConvert(ServerLevel level, ChunkPos chunkPos) {
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
                // cursor は必ずこのチャンクの中なので、Level 経由(毎回チャンク検索)ではなくチャンクから直接読む。
                if (chunk.getBlockState(cursor).is(ModTags.CORRUPTED)) {
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
        if (terrain == Terrain.OCEAN) {
            return switch (stage) {
                case OVERGROWTH -> ModBiomes.OVERGROWTH_OCEAN;
                case OVERGROWN -> ModBiomes.OVERGROWN_OCEAN;
                case CLIMAX -> ModBiomes.ENGULFED_OCEAN;
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
     * {@link com.yayuy.overgrownvegetation.spore.SporeBiomeSweep} からプレイヤー周辺のチャンクに対して
     * 定期的に呼ばれる。
     */
    public static void maybeConvertToOvergrowth(ServerLevel level, ChunkPos chunkPos) {
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
        if (SporeConcentration.percentAt(level, chunkPos) < 100.0D - 1.0e-6D) {
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
     * 指定チャンクのバイオームを「極相」段階({@code engulfed} 系)へ書き換える。
     * 昇格の可否は判定しない —— <b>呼ぶ側({@code ClimaxTicker})が台帳で判定済み</b>のものだけを渡すこと。
     *
     * <p><b>読み込まれているチャンクにだけ呼ぶこと。</b> {@link ServerLevel#getBiome} は
     * 読み込まれていなければチャンクを読み込んでしまうので、遠くのチャンクにこれを呼ぶと
     * 「プレイヤーが居なくても進むが、世界は触らない」という極相化の前提が崩れる
     * (読み込んでいないチャンクは台帳にだけ印を付け、次に読み込まれたときにここへ来る)。
     *
     * <p>既に極相なら何もしない。{@link OvergrownVegetationConfig.Common#convertToOvergrownBiome}
     * 設定には従わない({@link #forceOvergrown} と同じ扱い)。
     */
    public static void forceClimax(ServerLevel level, ChunkPos chunkPos) {
        BlockPos samplePos = chunkPos.getMiddleBlockPosition(level.getMinBuildHeight());
        Holder<Biome> currentBiome = level.getBiome(samplePos);
        if (ModBiomes.isClimax(currentBiome)) {
            return;
        }
        ResourceKey<Biome> target =
                targetKey(level, chunkPos, classifyTerrain(currentBiome), OvergrowthVariant.Stage.CLIMAX);
        flowConvert(level, chunkPos, target, null);
        recordOvergrownChunk(level, chunkPos, target);
        // 地脈を敷く。場は座標だけの決定的な関数なので、チャンクごとに敷いても境目で繋がる。
        LeyVeinPlacer.place(level, chunkPos);
    }

    /**
     * {@link #forceOvergrown} をまとめて行う版。多数のチャンクを一度に過繁茂化するとき
     * (地割れ演出など)、クライアントへの再送を 1 回のパケットにまとめて負荷を下げる。
     * batch 内のチャンク同士の縫い目は波打たせない(= 塊の外周だけがなだらかになる)。
     */
    public static void forceOvergrownBatch(ServerLevel level, Iterable<ChunkPos> chunkPositions) {
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
            PhaseData.get(level).markOvergrownChunks(level, convertedChunks);
        }
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
     */
    private static void recordOvergrownChunk(ServerLevel level, ChunkPos chunkPos, ResourceKey<Biome> targetKey) {
        if (ModBiomes.isClimaxKey(targetKey)) {
            // 極相は「過繁茂以上」なので台帳には残すが、面積とポイントは過繁茂になったときに数え済み。
            PhaseData.get(level).markClimaxChunk(level, chunkPos);
        } else if (ModBiomes.isFullyOvergrownKey(targetKey)) {
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
            if (!level.hasChunk(ncx, ncz)) {
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

    private static void finishResend(ServerLevel level, List<ChunkAccess> changed) {
        if (changed.isEmpty()) {
            return;
        }
        level.getChunkSource().chunkMap.resendBiomesForChunks(changed);
        for (ChunkAccess chunk : changed) {
            // バイオームが変わったので、この周辺チャンクの胞子濃度キャッシュを捨てる。
            SporeConcentration.invalidateAround(level, chunk.getPos());
        }
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
                neighbourKeys[d] = level.hasChunk(ncx, ncz)
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

    /** 繁茂系バイオームの「汚染度」。極相 = 3 / 過繁茂 = 2 / 繁茂 = 1 / それ以外 = 0。 */
    private static int rankOf(@Nullable ResourceKey<Biome> key) {
        if (key == null) {
            return 0;
        }
        // isFullyOvergrownKey は極相にも true を返すので、極相を先に見ること。
        if (ModBiomes.isClimaxKey(key)) {
            return 3;
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
