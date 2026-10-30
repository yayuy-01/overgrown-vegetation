package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * 「胞子濃度」— チャンクごとに決まる 0〜100(%)の値。過繁茂バイオーム
 * ({@link ModBiomes#OVERGROWN})にどれだけ近い/囲まれているかを表す。
 *
 * <h2>計算式</h2>
 * <ol>
 *     <li>そのチャンク自身が過繁茂バイオームなら、割合を一切計算せず必ず <b>100%</b>。</li>
 *     <li>そうでなければ以下の合計(上限 100%):
 *     <ul>
 *         <li><b>割合成分</b> — 半径 {@code sporeConcentrationRadiusChunks}(既定 3)チャンクの正方領域
 *         (未ロードのチャンクは分母・分子どちらからも除外)のうち過繁茂バイオームが占める割合 {@code r}。
 *         {@code r} が {@code sporeConcentrationFullRatio}(既定 0.8 = 8 割)に達した時点で 100% とし、
 *         それ以上は上がらない({@code min(1, r / fullRatio) * 100})。</li>
 *         <li><b>距離成分</b> — 最寄りの過繁茂バイオームチャンクとの「間に挟まるチャンク数」で決まる
 *         (チェビシェフ距離 - 1。斜め隣接も密接扱い)。割合に関係なく必ず加算される。
 *         密接(0)= {@code sporeConcentrationBiomeAdjacentPercent}(既定 50%)、
 *         1 チャンク離れ = {@code sporeConcentrationBiomeGap1Percent}(既定 25%)、
 *         2 チャンク離れ = {@code sporeConcentrationBiomeGap2Percent}(既定 5%)、それ以上 = 0。
 *         判定は割合成分と同じ半径 {@code sporeConcentrationRadiusChunks}(既定 3)の範囲内で行う。</li>
 *         <li><b>汚染ブロック成分</b> — {@code sporeConcentrationPerCorruptedBlockPercent} 参照。</li>
 *     </ul>
 *     </li>
 * </ol>
 *
 * <h2>負荷対策</h2>
 * <p>{@link com.yayuy.overgrownvegetation.effect.SporeInfectionTracker} から高頻度で呼ばれるため:
 * <ul>
 *     <li>結果は次元ごと・チャンクごとに {@code sporeConcentrationCacheTtlTicks}(既定 200)tick キャッシュ。
 *     バイオームが書き換わったとき({@link com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter})は
 *     {@link #invalidateAround} で周辺のキャッシュを捨てる。</li>
 *     <li>汚染ブロック成分の 256 列走査は {@code sporeConcentrationCorruptedScanStride}(既定 2)間隔で
 *     間引きサンプリングし、結果を全体へ外挿する。さらに「もう 100% に届くと確定した」時点で打ち切る。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SporeConcentration {

    /**
     * 計算結果の内訳。{@code percent} が最終的な胞子濃度(0〜100)。
     *
     * @param biomeGap          最寄りの過繁茂バイオームチャンクとの「間に挟まるチャンク数」。
     *                          0 = 密接(隣接/斜め隣接)、1 = 1 チャンク離れ、2 = 2 チャンク離れ、
     *                          -1 = 走査範囲内に過繁茂バイオームなし。
     * @param distanceComponent {@code biomeGap} から決まる加算値(%)。
     * @param corruptedColumns  汚染ブロックだった列数の推定値(間引きサンプリングからの外挿。0〜256)。
     * @param bonusComponent    感染加算成分(%)。プレイヤーがそのチャンクで胞子感染にかかるたびに積まれる
     *                          (2026-10-23 に無くした。いつも 0)。
     * @param deviceComponent   稼働中の胞子装置による変化量 %(除去装置は負、拡散装置は正。{@link SporeDeviceData#deltaAt})。
     *                          過繫茂バイオームのチャンク(必ず 100%)には効かない(常に 0)。
     * @param percentBeforeDevices 装置の影響を除いた胞子濃度(0〜100)。除去装置の「胞子」産出量の基準。
     * @param drifterComponent     上空のペディキュラ({@link PediculaCover})が<b>いる間だけ</b>押し上げている分(%)。
     *                             装置の前の値({@code percentBeforeDevices})をこの分だけ上げてある。0 = いない。
     */
    public record Result(double percent, boolean selfOvergrown, int biomeGap, double distanceComponent,
                         double ratioComponent, double corruptedComponent, int corruptedColumns,
                         double bonusComponent, double deviceComponent, double percentBeforeDevices,
                         double drifterComponent) {

        /** 装置の影響を {@code delta} だけ加えた結果(最終値は 0〜100 に丸める)。 */
        /** 寄生植物装備のプレイヤーぶん {@code delta} % を最終値に足した結果(0〜100 に丸める)。 */
        Result withHostBonus(double delta) {
            // 中身は上限なし(2026-10-23 胞子の場 第 2 段 D。表示する側が 0〜100 に丸める)
            double clamped = Math.max(0.0D, percent + delta);
            return new Result(clamped, selfOvergrown, biomeGap, distanceComponent, ratioComponent,
                    corruptedComponent, corruptedColumns, bonusComponent, deviceComponent, percentBeforeDevices,
                    drifterComponent);
        }

        Result withDevices(double delta) {
            if (delta == 0.0D) {
                return this;
            }
            double clamped = Math.max(0.0D, percentBeforeDevices + delta);
            return new Result(clamped, selfOvergrown, biomeGap, distanceComponent, ratioComponent,
                    corruptedComponent, corruptedColumns, bonusComponent, clamped - percentBeforeDevices,
                    percentBeforeDevices, drifterComponent);
        }

        /**
         * 上空のペディキュラが<b>いる間</b>の結果(2026-09-23)。装置の前の値を {@code cover}(既定 100)まで
         * 引き上げ、そこから装置の変化量 {@code delta} を足し直す —— 胞子除去装置は<b>いつもどおり効く</b>
         * (中心なら下げきり、縁では下げきれない)。呼ぶ側で {@code cover} が今の値より大きいことを確かめてある。
         */
        Result withDrifter(double cover, double delta) {
            double before = cover;
            double clamped = Math.max(0.0D, before + delta);
            return new Result(clamped, selfOvergrown, biomeGap, distanceComponent, ratioComponent,
                    corruptedComponent, corruptedColumns, bonusComponent, clamped - before,
                    before, before - percentBeforeDevices);
        }

    }

    private record Cached(Result result, long computedGameTime) {
    }

    private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Cached>> CACHE = new HashMap<>();
    /** 1 次元あたりのキャッシュ上限。超えたら丸ごと捨てる(TTL 併用なので単純で十分)。 */
    private static final int MAX_CACHE_PER_DIM = 20_000;
    /** 1 列あたり、一番上のブロックから下方向へ何ブロックまで潜って地面を探すか(木の幹の下など)。 */
    private static final int GROUND_SCAN_DEPTH = 32;

    /**
     * ワールドを閉じたらキャッシュを空にする(キーはディメンションだけなので、次に同じワールドや時刻の近い
     * 別のワールドを開くと、TTL の間は前のワールドの計算結果が返る。起動し直したときと同じ状態に戻す)。
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CACHE.clear();
        // 濃度の計算に使う残り 2 つの static キャッシュも同じ理由で捨てる(2026-09-24、監査 2088)。
        // 稼働中の胞子装置の位置(TTL 40 tick)は次元だけがキーなので(器官の下限は 10-23 に器官ごと削除)、
        // 時刻の近い別のワールドを開くと TTL の間は前のワールドの値を返しうる。
        SporeDeviceData.invalidateInhibitionAll();
    }

    /**
     * {@code chunk} の胞子濃度の内訳を返す(キャッシュ付き)。上空のペディキュラが<b>いる間だけ</b>の
     * 100% も含む(計測機が見るのはこれ)。
     */
    public static Result at(ServerLevel level, ChunkPos chunk) {
        return at(level, chunk.toLong(), chunk, true);
    }

    /**
     * {@link #at(ServerLevel, ChunkPos)} の本体。{@code chunk} はキャッシュミス時にだけ必要なので、
     * ブロック座標から呼ぶ側({@link #percentAt(ServerLevel, BlockPos)})は {@code null} を渡して
     * ヒット時の {@link ChunkPos} 生成を省く。
     *
     * @param drifters 上空のペディキュラがいる間の 100% を含めるか({@link #lastingPercentAt} だけが false)
     */
    private static Result at(ServerLevel level, long key, @Nullable ChunkPos chunk, boolean drifters) {
        Result base = atCached(level, key, chunk);
        if (drifters) {
            // 立ち去ればすぐ元に戻るよう、キャッシュの外で足す(2026-09-23)。
            base = withDrifters(level, key, base);
        }
        return base; // 寄生植物装備の上乗せ(PlantArmor)は 2026-10-30 に parked/ へ
    }

    private static Result atCached(ServerLevel level, long key, @Nullable ChunkPos chunk) {
        long cfgTtl = OvergrownVegetationConfig.COMMON.sporeConcentrationCacheTtlTicks.get();
        // 場は 5 秒ごとに変わる
        final long ttl = useField(level) ? Math.min(cfgTtl, com.yayuy.overgrownvegetation.data.DesignData.sporeField().updateTicks()) : cfgTtl;
        Long2ObjectOpenHashMap<Cached> perDim = CACHE.computeIfAbsent(level.dimension(), k -> new Long2ObjectOpenHashMap<>());
        long now = level.getGameTime();

        Cached cached = perDim.get(key);
        if (cached != null) {
            long age = now - cached.computedGameTime();
            if (age >= 0 && age < ttl) {
                return cached.result();
            }
        }

        Result result = compute(level, chunk != null ? chunk : new ChunkPos(key));
        if (!level.hasChunk(ChunkPos.getX(key), ChunkPos.getZ(key))) {
            // 2026-09-24(監査 2099): 自分のチャンクが読み込まれていないときは汚染列を数えずに出した値なので、
            // 覚えない(読み込まれたあとの呼び出しで数え直す)。本番の呼び出し元は読み込み済みの位置しか渡さない。
            return result;
        }
        if (perDim.size() >= MAX_CACHE_PER_DIM) {
            // 2026-09-24 軽量化(監査 2077): 期限切れのエントリは参照されない限り残るので、上限に達したら
            // まず期限切れだけを捨てる(次に引かれたとき計算し直すのは前と同じ)。丸ごと捨てると、いま
            // 使われているチャンクまで一斉にキャッシュミスになる。それでも上限なら従来どおり丸ごと捨てる。
            perDim.values().removeIf(c -> {
                long age = now - c.computedGameTime();
                return age < 0 || age >= ttl;
            });
            if (perDim.size() >= MAX_CACHE_PER_DIM) {
                perDim.clear();
            }
        }
        perDim.put(key, new Cached(result, now));
        return result;
    }

    /** {@code pos} を含むチャンクの胞子濃度(0〜100)。 */
    public static double percentAt(ServerLevel level, BlockPos pos) {
        if (useField(level)) {
            return fieldPercentAt(level, pos);
        }
        // 全 Mob から 20 tick ごとに呼ばれる。キャッシュヒット時は ChunkPos を作らない。
        return withCarrier(level, ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), true);
    }

    /** {@code chunk} の胞子濃度(0〜100)。 */
    public static double percentAt(ServerLevel level, ChunkPos chunk) {
        return withCarrier(level, chunk.toLong(), true);
    }

    /**
     * <b>繁茂バイオーム化</b>({@code OvergrownBiomeConverter#maybeConvertToOvergrowth})が見る胞子濃度。
     * {@link #percentAt} から、上空のペディキュラが<b>いる間だけ</b>の 100% を除いたもの。
     *
     * <p>ユーザー指定(2026-09-23)「いる時だけ 100% にしてほしい」。いる間の 100% で繁茂バイオーム化が走ると、
     * 立ち去ったあとに<b>通り道が点々と繁茂バイオームのまま残る</b>(見回りは 1 チャンクあたり数分に 1 回なので、
     * 通り過ぎる十数秒の間に当たる確率は 1 割弱 —— 当たったチャンクは戻らない)。
     *
     * <p><b>汚染シダの自然発生はこちらを使わない</b>(ユーザー指定 2026-09-23「シダの自然発生は残してほしい」)。
     * 真下にときどきシダが湧くのは、胞子の塊が通った跡として残してよい、という判断。
     * 生き物の感染・装備の汚染・除去装置の稼働・計測機の表示も、いる間は 100% のまま。
     */
    public static double lastingPercentAt(ServerLevel level, ChunkPos chunk) {
        return withCarrier(level, chunk.toLong(), false);
    }

    /**
     * チャンクの胞子濃度に、浸食度を持ち出しているプレイヤーぶんの上乗せを足す(2026-09-22)。
     *
     * <p>上乗せを<b>キャッシュの外で</b>足しているのは、濃度がチャンクごとにキャッシュされているから ——
     * 中へ混ぜると、プレイヤーが立ち去ったあとも上乗せが残る。
     */
    private static double withCarrier(ServerLevel level, long chunkKey, boolean drifters) {
        double base = at(level, chunkKey, null, drifters).percent();
        return base; // 浸食度の持ち出し(ErosionCarrier)は 2026-10-30 に parked/ へ
    }

    /**
     * 上空のペディキュラ({@link PediculaCover})が<b>いま</b>いれば、装置の前の値をその間の濃度(既定 100)まで
     * 引き上げる。過繫茂バイオームのチャンクは元から 100% なので触らない。
     *
     * <p>装置の変化量はキャッシュ済みの結果からは戻せない(0〜100 に丸めた後の差しか残っていない)ので、
     * <b>いるときだけ</b>計算し直す。ペディキュラは数が少ないので、この分岐に入ること自体がまれ。
     */
    private static Result withDrifters(ServerLevel level, long key, Result base) {
        double cover = 0.0D; // ペディキュラ(PediculaCover)は 2026-10-30 に parked/ へ
        if (cover <= base.percentBeforeDevices() || base.selfOvergrown()) {
            return base;
        }
        ChunkPos chunk = new ChunkPos(key);
        double delta = SporeDeviceData.deltaAt(level, chunk);
        return base.withDrifter(cover, delta);
    }

    /**
     * バイオームが書き換わったチャンクの周囲(半径 + 1 チャンク。距離成分・割合成分の両方に効く範囲)の
     * キャッシュを捨てる。{@link com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter} から呼ばれる。
     */
    public static void invalidateAround(ServerLevel level, ChunkPos chunk) {
        invalidateAround(level, chunk, OvergrownVegetationConfig.COMMON.sporeConcentrationRadiusChunks.get() + 1);
    }

    /**
     * {@code chunk} の周囲 {@code r} チャンク(チェビシェフ)のキャッシュを捨てる。胞子装置の設置/撤去/稼働切替は
     * 装置の効果半径({@link com.yayuy.overgrownvegetation.data.EffectTable.DeviceSpec#reachChunks})で呼ぶ。
     */
    public static void invalidateAround(ServerLevel level, ChunkPos chunk, int r) {
        Long2ObjectOpenHashMap<Cached> perDim = CACHE.get(level.dimension());
        if (perDim == null || perDim.isEmpty()) {
            return;
        }
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                perDim.remove(ChunkPos.asLong(chunk.x + dx, chunk.z + dz));
            }
        }
    }

    /** 1 チャンクぶんのキャッシュだけ捨てる(バイオームの書き換え・装置から)。 */
    public static void invalidate(ServerLevel level, ChunkPos chunk) {
        Long2ObjectOpenHashMap<Cached> perDim = CACHE.get(level.dimension());
        if (perDim != null) {
            perDim.remove(chunk.toLong());
        }
    }

    /**
     * 装置の影響を除いた基礎値を求め、稼働中の胞子装置({@link SporeDeviceData})の変化量を最後に足す。
     * 過繫茂バイオームのチャンク(必ず 100%)には装置は一切効かない — 装置で変わるのは過繫茂バイオーム以外のすべて
     * (ユーザー仕様 2026-09-10)。
     */
    private static Result compute(ServerLevel level, ChunkPos chunk) {
        if (useField(level)) {
            // 胞子の場を読む(2026-10-23 第 2 段 D)。除去装置・拡散装置は場そのものに効いている(抜く・出す)ので足さない。
            // スポロフィルの傘だけは今までどおり足す
            return fieldBase(level, chunk).withDevices(0.0D);
        }
        Result base = computeBase(level, chunk);
        if (base.selfOvergrown()) {
            return base;
        }
        // 胞子装置(除去/拡散)と、生きているスポロフィルの傘は同じ扱いで足し合わせる。
        return base.withDevices(SporeDeviceData.deltaAt(level, chunk));
    }

    // ---------------------------------------------------------------- 胞子の場を読む(2026-10-23 第 2 段 D)

    /**
     * 胞子の場が有効なら場を読む。2026-10-23 F からネザー(ネザーの場)とエンド(場は無く、塗られたチャンクを過繁茂の下支えで)も。
     */
    public static boolean useField(ServerLevel level) {
        if (!com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.enabled()) return false;
        return level.dimension() == Level.OVERWORLD || level.dimension() == Level.END
                || (level.dimension() == Level.NETHER && com.yayuy.overgrownvegetation.spore.field.NetherSporeFieldServer.running());
    }

    /**
     * チャンクの濃度 = 場の浮遊 + 林床を「過繁茂のチャンクの晴れた午後 = 100%」で割った値(上限なし)。
     * 下支え: 汚染されたチャンクは段階ごとの下限より下がらない(発生源の放出が下限。除去装置もここまで)。
     * 写しが無い所(プレイヤーから遠い)は台帳の段階の下限だけ。
     */
    private static Result fieldBase(ServerLevel level, ChunkPos chunk) {
        com.yayuy.overgrownvegetation.data.SporeFieldTable t = com.yayuy.overgrownvegetation.data.DesignData.sporeField();
        com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.Cell cell =
                com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.cell(level, chunk.x, chunk.z);
        int stage;
        double raw;
        if (level.dimension() == Level.END) {
            // エンドは場に乗せない: 過繫茂したエンドへ塗られたチャンクを「発芽済み・過繁茂」として下支えだけ(2026-10-23 F)
            stage = com.yayuy.overgrownvegetation.corruption.PortalCorruptionData.get(level.getServer()).isEndIslandDone(chunk.toLong()) ? 2 : 0;
            raw = 0.0D;
        } else if (level.dimension() == Level.NETHER && cell == null) {
            stage = com.yayuy.overgrownvegetation.corruption.NetherLedgerData.get(level).isOvergrown(chunk.toLong()) ? 2 : 0;
            raw = 0.0D;
        } else if (cell != null) {
            stage = cell.stage();
            raw = cell.conc() / t.fullAt() * 100.0D;
            // 台帳の段階も見る(2026-10-24 ユーザーの指摘: 発芽したばかりのチャンクは台帳・バイオームではもう過繁茂なのに、
            // 場では発芽から 2 日まで「繁茂」で下支えが 10% のまま、浮遊 8〜12% と読めていた)
            if (level.dimension() == Level.OVERWORLD) {
                com.yayuy.overgrownvegetation.phase.PhaseData phase = com.yayuy.overgrownvegetation.phase.PhaseData.get(level);
                stage = Math.max(stage, phase.isClimaxChunk(chunk) ? 3 : phase.isOvergrownChunk(chunk) ? 2 : 0);
            }
        } else {
            com.yayuy.overgrownvegetation.phase.PhaseData phase = com.yayuy.overgrownvegetation.phase.PhaseData.get(level);
            stage = phase.isClimaxChunk(chunk) ? 3 : phase.isOvergrownChunk(chunk) ? 2 : 0;
            raw = 0.0D;
        }
        double percent = Math.max(raw, t.floorFor(stage));
        return new Result(percent, stage >= 2, -1, 0.0D, 0.0D, 0.0D, 0, 0.0D, 0.0D, percent, 0.0D);
    }

    /**
     * 地点の濃度: まわり 4 つのチャンクの中心の値をなめらかに混ぜ(チャンクの境で段差を作らない)、地点の補正を掛ける。
     */
    private static double fieldPercentAt(ServerLevel level, BlockPos pos) {
        double fx = pos.getX() / 16.0D - 0.5D, fz = pos.getZ() / 16.0D - 0.5D;
        int x0 = Mth.floor(fx), z0 = Mth.floor(fz);
        double tx = fx - x0, tz = fz - z0;
        double v00 = withCarrier(level, ChunkPos.asLong(x0, z0), true);
        double v10 = withCarrier(level, ChunkPos.asLong(x0 + 1, z0), true);
        double v01 = withCarrier(level, ChunkPos.asLong(x0, z0 + 1), true);
        double v11 = withCarrier(level, ChunkPos.asLong(x0 + 1, z0 + 1), true);
        double v = (v00 * (1 - tx) + v10 * tx) * (1 - tz) + (v01 * (1 - tx) + v11 * tx) * tz;
        return (v + riftFloorPercent(level, pos)) * pointFactor(level, pos);
    }

    /**
     * 地割れの周りの下支え(%、2026-10-24 追記 19【決定】): 噴出の後も地割れの中心から rift.floorRadiusChunks の内側は
     * 濃度の読みに rift.floorPercent を足す。半径の半分までは満額、そこから縁へなだらかに 0。地割れが複数ならいちばん近いもの。
     * オーバーワールドだけ。
     */
    public static double riftFloorPercent(ServerLevel level, BlockPos pos) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return 0.0D;
        long[] rifts = com.yayuy.overgrownvegetation.quake.EarthquakeData.riftCenters();
        if (rifts.length == 0) return 0.0D;
        com.yayuy.overgrownvegetation.data.SporeFieldTable t = com.yayuy.overgrownvegetation.data.DesignData.sporeField();
        double r = t.riftFloorRadius() * 16.0D;
        if (r <= 0.0D || t.riftFloorPercent() <= 0.0D) return 0.0D;
        double best = 0.0D;
        for (long c : rifts) {
            double dx = pos.getX() - BlockPos.getX(c), dz = pos.getZ() - BlockPos.getZ(c);
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d >= r) continue;
            double f = d <= r * 0.5D ? 1.0D : 1.0D - Mth.smoothstep((d - r * 0.5D) / (r * 0.5D));
            best = Math.max(best, f);
        }
        return best * t.riftFloorPercent();
    }

    /**
     * 地点の補正(粗くてよい。【決定】閉じた部屋は厳密でなくてよい・屋根付きの廊下も閉じ扱いで可):
     * 梢より上は薄い / 空が少し見える地下(洞窟の口・窪地)は濃い / 空の光 0 で地面から浅い所は屋根の下(周り 4 方が塞がれば閉じた部屋)/
     * 深い洞窟 / 雨の最中 / 風上のすぐ隣が塞がっていれば壁の陰。
     */
    static double pointFactor(ServerLevel level, BlockPos pos) {
        com.yayuy.overgrownvegetation.data.SporeFieldTable t = com.yayuy.overgrownvegetation.data.DesignData.sporeField();
        if (level.dimension() == Level.NETHER) {
            // ネザー: 天井の岩盤の下はどこも「地表」扱い(高さ地図は天井を指すので使わない)。雨も風も無い。閉じた部屋だけ薄い(2026-10-23 F)
            return enclosed(level, pos.above()) && level.getBlockState(pos.above(2)).isCollisionShapeFullBlock(level, pos.above(2)) ? t.roomFactor() : 1.0D;
        }
        int x = pos.getX(), z = pos.getZ(), y = pos.getY();
        double f = 1.0D;
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y > top + 2) {
            f *= Math.exp(-(y - top - 2) / t.aboveCanopyFalloff());
        } else if (y < ground - 1) {
            int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos.above());
            int depth = ground - y;
            if (sky == 0) {
                f *= depth > 12 ? t.deepCaveFactor() : enclosed(level, pos.above()) ? t.roomFactor() : t.roofFactor();
            } else if (sky < 15) {
                f *= t.hollowFactor();
            }
        }
        if (level.isRainingAt(pos.above(2)) || (level.isRaining() && y >= ground - 1 && level.canSeeSky(pos.above()))) {
            f *= t.rainFactor();
        }
        com.yayuy.overgrownvegetation.spore.field.PrevailingWind.Sample w = level.dimension() == Level.OVERWORLD
                ? com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.windNow(level) : null;
        if (w != null && w.strength > 0.3D) {
            BlockPos up = pos.offset(-(int) Math.round(w.dirX), 1, -(int) Math.round(w.dirZ));
            if (level.getBlockState(up).isCollisionShapeFullBlock(level, up)) {
                f *= t.upwindShadeFactor();
            }
        }
        return f;
    }

    /** 頭の高さで東西南北それぞれ 6 ブロック以内に塞ぐブロックがあるか。 */
    private static boolean enclosed(ServerLevel level, BlockPos head) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            boolean hit = false;
            for (int i = 1; i <= 6 && !hit; i++) {
                m.set(head.getX() + d.getStepX() * i, head.getY(), head.getZ() + d.getStepZ() * i);
                hit = level.getBlockState(m).isCollisionShapeFullBlock(level, m);
            }
            if (!hit) return false;
        }
        return true;
    }

    private static Result computeBase(ServerLevel level, ChunkPos chunk) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;

        if (overgrownState(level, chunk.x, chunk.z) == 1) {
            return new Result(100.0, true, 0, 0.0, 0.0, 0.0, 256, 0.0, 0.0, 100.0, 0.0);
        }
        // 繁茂バイオーム(overgrown_vegetation:overgrowth 系)は「胞子濃度 100%」に達した時点で
        // 割り当てられるが、その後もチャンク自身の胞子濃度は下の通常計算で毎回求め直す
        // (固定 100% にはしない)。発生源の過繁茂バイオームが遠ざかる・汚染ブロックが減るなどで
        // 実際の濃度が下がれば、繁茂バイオームのチャンクでも胞子濃度は下がる。
        // なお overgrownState は過繁茂バイオームしか 1 を返さないので、繁茂バイオームは
        // 近隣チャンクの胞子発生源には元々数えられていない(繁茂 → 繁茂 の際限ない伝播は起きない)。

        int radius = cfg.sporeConcentrationRadiusChunks.get();
        int overgrown = 0;
        int total = 0;
        int minChebyshev = Integer.MAX_VALUE;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int state = overgrownState(level, chunk.x + dx, chunk.z + dz, probe);
                if (state < 0) {
                    // 未ロード。分母にも入れない(「見えている範囲での割合」)。
                    continue;
                }
                total++;
                if (state == 1) {
                    overgrown++;
                    int cheby = Math.max(Math.abs(dx), Math.abs(dz));
                    if (cheby > 0 && cheby < minChebyshev) {
                        minChebyshev = cheby;
                    }
                }
            }
        }

        double ratioComponent = 0.0;
        if (total > 0) {
            double ratio = (double) overgrown / total;
            double fullRatio = cfg.sporeConcentrationFullRatio.get();
            double scaled = fullRatio <= 0.0 ? 1.0 : ratio / fullRatio;
            ratioComponent = Math.min(1.0, scaled) * 100.0;
        }

        // 最寄りの過繁茂バイオームチャンクとの距離(間に挟まるチャンク数)で決まる加算成分。
        int biomeGap = minChebyshev == Integer.MAX_VALUE ? -1 : minChebyshev - 1;
        double distanceComponent = switch (biomeGap) {
            case 0 -> cfg.sporeConcentrationBiomeAdjacentPercent.get();
            case 1 -> cfg.sporeConcentrationBiomeGap1Percent.get();
            case 2 -> cfg.sporeConcentrationBiomeGap2Percent.get();
            default -> 0.0;
        };

        // プレイヤーの感染で積まれたチャンク固有の加算(SporeChunkBonus)。
        double bonusComponent = 0.0D; // SporeChunkBonus は無くした(2026-10-23 第 2 段 G)

        double base = distanceComponent + ratioComponent + bonusComponent;
        double perCol = cfg.sporeConcentrationPerCorruptedBlockPercent.get();

        int corruptedColumns = 0;
        if (perCol > 0.0 && base < 100.0) {
            int stride = Mth.clamp(cfg.sporeConcentrationCorruptedScanStride.get(), 1, 8);
            // これだけの列が汚染なら base と合わせて 100% に届く、という打ち切り閾値。
            int maxUsefulColumns = (int) Math.ceil((100.0 - base) / perCol);
            corruptedColumns = countCorruptedSurface(level, chunk, stride, maxUsefulColumns);
        }
        double corruptedComponent = Math.min(Math.max(0.0, 100.0 - base), corruptedColumns * perCol);

        double percent = Math.min(100.0, base + corruptedComponent);
        return new Result(percent, false, biomeGap, distanceComponent, ratioComponent, corruptedComponent,
                corruptedColumns, bonusComponent, 0.0, percent, 0.0);
    }

    /**
     * チャンクの列のうち「地表(またはその上に生えた植物)が汚染ブロック」な列の数(0〜256 に外挿)。
     *
     * <p>{@code stride} 間隔のグリッド上の列だけを実際に調べ、その割合を 256 列全体へ外挿する。
     * {@code maxUsefulColumns} 相当に達した時点で打ち切る(それ以上調べても最終結果は変わらない)。
     *
     * <p>各列は一番上の非空気ブロック({@link Heightmap.Types#WORLD_SURFACE})から下方向へ走査し、
     * 汚染ブロックが見つかればカウント。汚染されていない葉・原木・蔦・下草・水などは貫通して地面を
     * 探し続け、汚染されていないバニラの地面に突き当たったらノーカウント。呼び出し元でチャンクが
     * ロード済みなのは確認済み。
     */
    private static int countCorruptedSurface(ServerLevel level, ChunkPos chunkPos, int stride, int maxUsefulColumns) {
        // 2026-09-24(監査 2099): チケットの無いチャンクをここで同期ロード(未生成なら生成)しない。
        // 本番の呼び出し元はどれもロード済みの位置(Mob・プレイヤーの居場所、hasChunk を確かめたチャンク)
        // なので結果は変わらない。hasChunk はチケットだけを見るので、読み込み途中のチャンクは従来どおり
        // getChunk で待って数える(getChunkNow にすると、その間だけ 0 列になって値が変わる)。
        if (!level.hasChunk(chunkPos.x, chunkPos.z)) {
            return 0;
        }
        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        int minY = level.getMinBuildHeight();

        int perAxis = (16 + stride - 1) / stride;
        double scale = 256.0 / (perAxis * perAxis);

        int sampled = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x += stride) {
            for (int z = 0; z < 16; z += stride) {
                if (columnGroundIsCorrupted(level, chunk, chunkPos.getMinBlockX() + x, chunkPos.getMinBlockZ() + z,
                        x, z, minY, cursor)) {
                    sampled++;
                    if ((int) Math.round(sampled * scale) >= maxUsefulColumns) {
                        return maxUsefulColumns;
                    }
                }
            }
        }
        return Math.min(256, (int) Math.round(sampled * scale));
    }

    /** 1 列の地面(または上に生えた植物)が汚染ブロックか。 */
    private static boolean columnGroundIsCorrupted(ServerLevel level, LevelChunk chunk, int wx, int wz,
                                                  int localX, int localZ, int minY, BlockPos.MutableBlockPos cursor) {
        // チャンクの getHeight はもういちばん上のブロックの高さ(-1 済み)。前はさらに -1 していて、いちばん上の 1 段(汚染された草など)を
        // 見ずに下から探していた(2026-10-23 直した。今これを使うのは胞子の場を使わない読み = ネザー・エンドだけ)
        int startY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
        for (int dy = 0; dy < GROUND_SCAN_DEPTH && startY - dy > minY; dy++) {
            cursor.set(wx, startY - dy, wz);
            // 列は必ずこのチャンクの中・高さの範囲内なので、Level を経由せずチャンクから直接読む
            // (Level#getBlockState は高さの確認とチャンクの引き直しをするだけで、同じ値を返す。2026-09-24、監査 2077)。
            BlockState state = chunk.getBlockState(cursor);
            if (state.is(ModTags.CORRUPTED)) {
                return true;
            }
            if (state.isAir() || isTreePart(state) || !state.blocksMotion()) {
                // 汚染されていない葉・原木・蔦・下草・水などは貫通して地面を探し続ける。
                continue;
            }
            // 汚染されていない地面(バニラの土・石など)に突き当たった。
            return false;
        }
        return false;
    }

    /** 原木・葉(バニラ / この MOD の巨大樹系を含む)。地面探索の際に貫通する対象。 */
    private static boolean isTreePart(BlockState state) {
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
            return true;
        }
        Block block = state.getBlock();
        return block == ModBlocks.OVERGROWN_LOG.get()
                || block == ModBlocks.GIANT_OVERGROWN_LOG.get()
                || block == ModBlocks.GIANT_OVERGROWN_CAMBIUM.get()
                || block == ModBlocks.OVERGROWN_LEAVES.get();
    }

    private static int overgrownState(ServerLevel level, int chunkX, int chunkZ) {
        return overgrownState(level, chunkX, chunkZ, new BlockPos.MutableBlockPos());
    }

    /** {@code 1}=過繁茂バイオーム(陸 or 海)、{@code 0}=それ以外、{@code -1}=チャンク未ロード(判定不能)。 */
    private static int overgrownState(ServerLevel level, int chunkX, int chunkZ, BlockPos.MutableBlockPos scratch) {
        if (!level.hasChunk(chunkX, chunkZ)) {
            return -1;
        }
        scratch.set((chunkX << 4) + 8, level.getSeaLevel(), (chunkZ << 4) + 8);
        return ModBiomes.isFullyOvergrown(level.getBiome(scratch)) ? 1 : 0;
    }

    private SporeConcentration() {
    }
}
