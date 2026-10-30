package com.yayuy.overgrownvegetation.quake;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.network.EarthquakeShakePayload;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.registry.ModSounds;
import com.yayuy.overgrownvegetation.raid.RaidChunkLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * フェーズ1開始演出「地割れ・胞子噴出」の進行状況(ワールド単位)を保持する {@link SavedData}。
 * {@link com.yayuy.overgrownvegetation.phase.PhaseData} と同じくオーバーワールドに紐付く。
 *
 * <p>1件の演出のタイムライン(開始 = 震央 Y が確定したゲーム内時刻からの経過 tick。
 * {@code total} = {@code quakeFormTicks} = 土砂崩れ効果音の実尺、既定 312 = 15.6 秒):
 * <ul>
 *   <li>e == 0: 画面が揺れ始めるのと同時に、土砂崩れ(地響き)の効果音が発生地点から1回鳴り出す。</li>
 *   <li>0 〜 preShake: 徐々に画面が揺れ始める(揺れは S2C パケットでクライアントが描画)。
 *   終盤は地表に裂け目の輪郭が薄く浮かぶ。まだ地面は裂けない。</li>
 *   <li>preShake 〜 total: 大きく揺れながら裂け目が中心から両端へ徐々に走る
 *   ({@link EarthquakeFissure#carveWave})。掘る波の数は {@code total - preShake}。</li>
 *   <li>e == total: 効果音の鳴り終わりと同時に裂け目が完成する。空洞に残った陸地の水は焼失し
 *   ({@link EarthquakeFissure#dryFissure})、床・断面の壁・地表の縁が汚染ブロックになる。以降
 *   {@code eruptDuration}(既定3分)、裂け目から上空へ胞子が噴き続ける(噴出自体は完成の
 *   少し前 {@code quakeEruptStartTicks} から)。</li>
 *   <li>total 〜 +fadeOut: 画面の揺れがゆっくり収まる。</li>
 * </ul>
 * 進行は {@link EarthquakeTicker} が毎 tick 呼ぶ。発生は {@link EarthquakeEvent}。
 */
public final class EarthquakeData extends SavedData {

    private static final String ID = "overgrown_vegetation_earthquake";

    public static final SavedData.Factory<EarthquakeData> FACTORY =
            new SavedData.Factory<>(EarthquakeData::new, (tag, registries) -> load(tag), null);

    /**
     * いずれかの地割れが「胞子噴出フェーズ」に入っているか(オーバーワールドの tick で毎回更新)。
     * この間は汚染シダなどの侵食速度が {@code quakeEruptionCorruptionMultiplier} 倍になる
     * ({@link com.yayuy.overgrownvegetation.corruption.CorruptionSpreader} が参照)。
     */
    private static volatile boolean eruptionActive;

    public static boolean isEruptionActive() {
        return eruptionActive;
    }

    /** 進行中の1件の地割れ演出。フィールドは可変(進捗を書き込むため)。 */
    static final class ActiveQuake {
        long id;
        double ex;
        double ez;
        int ey = Integer.MIN_VALUE;
        float yaw;
        int length;
        int width;
        /** 裂け目の最深部が到達する Y 座標。縁へ向かって地表まで浅くなる。 */
        int floorY;
        long seed;
        /** 胞子噴出の起点(裂け目中央の底)。ey が MIN なら未記録。 */
        int eruptX;
        int eruptY = Integer.MIN_VALUE;
        int eruptZ;
        /** 震央 Y が確定したゲーム内時刻。-1 はまだ未確定(次 tick で確定させる)。 */
        long startGameTime = -1L;
        int carvedWaves;
        boolean finalizedFissure;
        boolean announcedBegin;
        /** 胞子噴出フェーズに入ったか(形成の途中で始まる)。 */
        boolean eruptionStarted;
        /** 土砂崩れの音を鳴らしたゲーム内時刻。-1 = まだ鳴らしていない。演出開始(画面の揺れ始め)と同時に1回だけ鳴らす。 */
        long lastFissureSoundTime = -1L;
        long[] forcedChunks = new long[0];
        /**
         * 裂け目の足跡の列の表({@link EarthquakeFissure#forEachColumn} 用、2026-09-24 軽量化 報告 1075)。
         * 形は上のフィールドだけで決まるので、最初に要った tick に 1 回作って使い回す。<b>保存しない</b>
         * (読み込み直したら最初に要ったときに作り直す)。
         */
        EarthquakeFissure.ColumnIndex columnIndex;
        /** 強制ロードしたチャンクの読み込みを待ち始めたゲーム内時刻(-1 = 待っていない)。保存しない。 */
        long forceWaitStart = -1L;
    }

    /**
     * 演出を始める前に、強制ロードした足跡のチャンクが裏で読み込み終わるのを待つ上限(tick)。
     * 過ぎたら残りをその場で読み込んで始める(以前と同じ)。仮決め 2026-09-25(報告 1450。それまでは 2026-09-24 の
     * 「印の反映だけを 20 tick 待つ」で、読み込み途中のチャンクは予兆の輪郭・掘削が読んだときに同期で待っていた)。
     */
    private static final int FORCE_LOAD_WAIT_TICKS = 200;

    /**
     * 裂け目が中心から両端へ走る tick 数(= 掘る波の数)。{@code quakeFormTicks - quakePreShakeTicks}(最低 1)。
     * {@link EarthquakeEvent} が足跡の列の表を先に作るときも同じ値を使う(表は波の数ごとに作るので、揃えれば使い回せる)。
     */
    static int carveSpan() {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        return Math.max(1, cfg.quakeFormTicks.get() - cfg.quakePreShakeTicks.get());
    }

    private final List<ActiveQuake> active = new ArrayList<>();
    /**
     * 噴出した地割れの中心(噴出点 BlockPos.asLong)。噴出の後も周りの濃度を下支えする(2026-10-24 追記 19【決定】)。
     * 写しは濃度の読み(毎 tick 多数)から引くので配列で持つ。
     */
    private final it.unimi.dsi.fastutil.longs.LongArrayList rifts = new it.unimi.dsi.fastutil.longs.LongArrayList();
    private static volatile long[] RIFTS = new long[0];

    /** サーバーを止めたら写しを捨てる(次に開くワールドへ持ち越さない)。 */
    public static void clearCache() {
        RIFTS = new long[0];
    }

    /** 噴出した地割れの中心(BlockPos.asLong)。オーバーワールドの値。読み込む前は空。 */
    public static long[] riftCenters() {
        return RIFTS;
    }

    /** 噴出中の地割れの噴出点のチャンク(場の入力。噴出の長さは quakeEruptDurationTicks)。 */
    public long[] eruptingChunks(long now) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        long eruptStart = cfg.quakePreShakeTicks.get() + Math.min(carveSpan(), cfg.quakeEruptStartTicks.get());
        long eruptEnd = eruptStart + cfg.quakeEruptDurationTicks.get();
        it.unimi.dsi.fastutil.longs.LongArrayList out = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (ActiveQuake q : active) {
            if (!q.eruptionStarted || q.eruptY == Integer.MIN_VALUE) continue;
            long e = now - q.startGameTime;
            if (e < eruptEnd) out.add(ChunkPos.asLong(q.eruptX >> 4, q.eruptZ >> 4));
        }
        return out.toLongArray();
    }

    /** テスト用: 地割れの中心を足す・消す。 */
    public void addRiftForTests(BlockPos center) {
        rifts.add(center.asLong());
        RIFTS = rifts.toLongArray();
    }

    public void removeRiftForTests(BlockPos center) {
        rifts.rem(center.asLong());
        RIFTS = rifts.toLongArray();
    }

    private EarthquakeData() {
    }

    public static EarthquakeData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static EarthquakeData load(CompoundTag tag) {
        EarthquakeData data = new EarthquakeData();
        ListTag list = tag.getList("active", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            ActiveQuake q = new ActiveQuake();
            q.id = e.getLong("id");
            q.ex = e.getDouble("ex");
            q.ez = e.getDouble("ez");
            q.ey = e.contains("ey") ? e.getInt("ey") : Integer.MIN_VALUE;
            q.yaw = e.getFloat("yaw");
            q.length = e.getInt("length");
            q.width = e.getInt("width");
            q.floorY = e.contains("floorY") ? e.getInt("floorY") : -30;
            q.seed = e.getLong("seed");
            if (e.contains("eruptY")) {
                q.eruptX = e.getInt("eruptX");
                q.eruptY = e.getInt("eruptY");
                q.eruptZ = e.getInt("eruptZ");
            }
            q.startGameTime = e.contains("startGameTime") ? e.getLong("startGameTime") : -1L;
            q.carvedWaves = e.getInt("carvedWaves");
            q.finalizedFissure = e.getBoolean("finalizedFissure");
            q.announcedBegin = e.getBoolean("announcedBegin");
            q.eruptionStarted = e.getBoolean("eruptionStarted");
            q.lastFissureSoundTime = e.contains("lastFissureSoundTime")
                    ? e.getLong("lastFissureSoundTime") : -1L;
            q.forcedChunks = e.contains("forcedChunks") ? e.getLongArray("forcedChunks") : new long[0];
            data.active.add(q);
        }
        for (long r : tag.getLongArray("rifts")) data.rifts.add(r);
        RIFTS = data.rifts.toLongArray();
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (ActiveQuake q : active) {
            CompoundTag e = new CompoundTag();
            e.putLong("id", q.id);
            e.putDouble("ex", q.ex);
            e.putDouble("ez", q.ez);
            if (q.ey != Integer.MIN_VALUE) {
                e.putInt("ey", q.ey);
            }
            e.putFloat("yaw", q.yaw);
            e.putInt("length", q.length);
            e.putInt("width", q.width);
            e.putInt("floorY", q.floorY);
            e.putLong("seed", q.seed);
            if (q.eruptY != Integer.MIN_VALUE) {
                e.putInt("eruptX", q.eruptX);
                e.putInt("eruptY", q.eruptY);
                e.putInt("eruptZ", q.eruptZ);
            }
            e.putLong("startGameTime", q.startGameTime);
            e.putInt("carvedWaves", q.carvedWaves);
            e.putBoolean("finalizedFissure", q.finalizedFissure);
            e.putBoolean("announcedBegin", q.announcedBegin);
            e.putBoolean("eruptionStarted", q.eruptionStarted);
            e.putLong("lastFissureSoundTime", q.lastFissureSoundTime);
            e.putLongArray("forcedChunks", q.forcedChunks);
            list.add(e);
        }
        tag.put("active", list);
        tag.putLongArray("rifts", rifts.toLongArray());
        return tag;
    }

    // ---- 発生 ----------------------------------------------------------

    void begin(ActiveQuake q) {
        active.add(q);
        setDirty();
    }

    public boolean hasActive() {
        return !active.isEmpty();
    }

    /** コマンド用: 進行中の全演出を打ち切り、強制ロードを解除する。 */
    public int stopAll(ServerLevel overworld) {
        int n = active.size();
        for (ActiveQuake q : active) {
            RaidChunkLoader.release(overworld, q.forcedChunks);
        }
        active.clear();
        setDirty();
        return n;
    }

    // ---- 進行 ----------------------------------------------------------

    void tick(ServerLevel overworld) {
        if (active.isEmpty()) {
            eruptionActive = false;
            return;
        }
        boolean dirty = false;
        boolean anyErupting = false;
        for (var it = active.iterator(); it.hasNext(); ) {
            ActiveQuake q = it.next();
            if (advance(overworld, q)) {
                it.remove();
                dirty = true;
            } else {
                dirty = true; // 進行中は毎 tick 何かしら書き換わる(carvedWaves 等)ので保存対象にしておく
                if (q.eruptionStarted) {
                    anyErupting = true;
                }
            }
        }
        eruptionActive = anyErupting;
        if (dirty) {
            setDirty();
        }
    }

    /** @return この演出が終わって一覧から外してよいなら true。 */
    private boolean advance(ServerLevel level, ActiveQuake q) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        long now = level.getGameTime();

        // 震央 Y を確定させる(必要ならチャンクを同期ロード)。
        if (q.startGameTime < 0L) {
            // 2026-09-24 軽量化(報告 1064): EarthquakeEvent は強制ロードの印だけを付け、読み込みは待たない。
            // 2026-09-25(報告 1450): 印の反映だけでなく、足跡のチャンクが「読み込み終わる」まで演出を始めない
            // (最長 FORCE_LOAD_WAIT_TICKS)。読み込み途中のチャンクを輪郭・掘削が読むと、その場で読み込みを待つ
            // (= 同期ロード)ため。読み込み終わっていれば、最初に全部を同期で読み込んでいた頃と同じ地形を読む。
            if (!forcedChunksLoaded(level, q)) {
                if (q.forceWaitStart < 0L) {
                    q.forceWaitStart = now;
                }
                if (now - q.forceWaitStart < FORCE_LOAD_WAIT_TICKS) {
                    return false;
                }
                // 保険: 待ちすぎた。以前と同じく、残りをこの場で同期ロードしてから始める。
                for (long key : q.forcedChunks) {
                    if (level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) == null) {
                        level.getChunk(ChunkPos.getX(key), ChunkPos.getZ(key));
                    }
                }
            }
            int bx = Mth.floor(q.ex);
            int bz = Mth.floor(q.ez);
            level.getChunk(bx >> 4, bz >> 4);
            q.ey = resolveSurface(level, bx, bz);
            q.startGameTime = now;
        }
        if (!q.announcedBegin) {
            level.getServer().getPlayerList().broadcastSystemMessage(
                    Component.translatable("message.overgrown_vegetation.quake.begin"), false);
            q.announcedBegin = true;
        }

        int pre = cfg.quakePreShakeTicks.get();
        // quakeFormTicks = 土砂崩れ効果音の実尺(既定 312 = 15.6 秒)。
        // 「画面が揺れ始める = 効果音の再生開始」(e == 0)から「効果音の鳴り終わり = 裂け目の完成」までがこの長さ。
        int total = cfg.quakeFormTicks.get();
        int fade = cfg.quakeFadeOutTicks.get();
        // 予兆(pre)のあと、実際に裂け目が中心から両端へ走る tick 数(= 掘る波の数)。完成は e == total。
        int carveSpan = carveSpan();
        int formEnd = total;
        int shakeEnd = formEnd + fade;
        // 胞子噴出は形成の途中(quakeEruptStartTicks)から始まる。
        long eruptStart = pre + Math.min(carveSpan, cfg.quakeEruptStartTicks.get());
        long eruptEnd = eruptStart + cfg.quakeEruptDurationTicks.get();
        long e = now - q.startGameTime;

        // 画面の揺れパケット(取りこぼしに強いよう定期再送)。エンベロープが total で締まるよう
        // formTicks には carveSpan を渡す(クライアント: preTicks + formTicks = pre + carveSpan = total)。
        if (e <= shakeEnd + 20 && now % 10L == 0L) {
            PacketDistributor.sendToPlayersInDimension(level, new EarthquakeShakePayload(
                    q.ex, q.ey, q.ez, (int) Math.max(0L, e), pre, carveSpan, fade,
                    cfg.quakeShakeIntensity.get().floatValue(), cfg.quakeShakeRadius.get()));
        }

        // 土砂崩れ(地響き)の効果音を「発生地点」から、画面が揺れ始めるのと同時 = 演出開始(e == 0)に
        // ちょうど1回鳴らす。クリップの実尺 15.6 秒 = quakeFormTicks なので、鳴り終わりと裂け目の完成が揃う。
        if (q.lastFissureSoundTime < 0L) {
            level.playSound(null, q.ex, q.ey, q.ez, ModSounds.EARTHQUAKE_FISSURE.get(),
                    SoundSource.HOSTILE, 8.0F, 1.0F);
            q.lastFissureSoundTime = now;
            double nearestSq = Double.MAX_VALUE;
            for (ServerPlayer p : level.players()) {
                nearestSq = Math.min(nearestSq, p.distanceToSqr(q.ex, q.ey, q.ez));
            }
            OvergrownVegetation.LOGGER.info(
                    "地割れ演出: 土砂崩れの音を発生地点 ({}, {}, {}) から再生(画面の揺れ始めと同時)。最寄りプレイヤーまで {} ブロック(減衰 1024)。",
                    Mth.floor(q.ex), q.ey, Mth.floor(q.ez),
                    nearestSq == Double.MAX_VALUE ? "?" : (int) Math.sqrt(nearestSq));
        }

        if (e < pre) {
            if (pre - e <= 60L && now % 4L == 0L) {
                new EarthquakeFissure(level, q, carveSpan).warnOutline();
            }
        } else if (e < formEnd) {
            EarthquakeFissure f = new EarthquakeFissure(level, q, carveSpan);
            int want = (int) (e - pre);
            while (q.carvedWaves <= want && q.carvedWaves < carveSpan) {
                f.carveWave(q.carvedWaves);
                q.carvedWaves++;
            }
        }

        // 胞子噴出フェーズの開始(形成の途中)。噴出点を記録し、告知 + 効果音。
        if (e >= eruptStart && !q.eruptionStarted) {
            int[] p = new EarthquakeFissure(level, q, carveSpan).centerFloorPoint();
            q.eruptX = p[0];
            q.eruptY = p[1];
            q.eruptZ = p[2];
            q.eruptionStarted = true;
            // 胞子の場: 圧の前線を出し、地割れの中心を下支えに記録する(2026-10-24 追記 19)
            rifts.add(BlockPos.asLong(q.eruptX, q.eruptY, q.eruptZ));
            RIFTS = rifts.toLongArray();
            com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.onRiftErupt(q.eruptX >> 4, q.eruptZ >> 4);
            for (ServerPlayer sp : level.players()) {
                level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.WARDEN_SONIC_BOOM,
                        SoundSource.AMBIENT, 0.9F, 0.55F);
            }
            level.getServer().getPlayerList().broadcastSystemMessage(
                    Component.translatable("message.overgrown_vegetation.quake.erupt"), false);
        }

        // 胞子噴出パーティクル + 噴出中のハザード(周囲の Mob を確定感染 + ランダムな汚染シダ)。
        if (q.eruptionStarted && e < eruptEnd) {
            long er = e - eruptStart;
            EarthquakeFissure f = new EarthquakeFissure(level, q, carveSpan);
            if (er % Math.max(1, cfg.quakeEruptIntervalTicks.get()) == 0L) {
                f.eruptSpores();
            }
            if (er % Math.max(1, cfg.quakeEruptFernIntervalTicks.get()) == 0L) {
                f.spawnRandomFerns();
            }
        }

        // 形成完了: 断面の汚染ブロック化 + 周囲のひび。強制ロードを中心付近だけに縮小。
        if (e >= formEnd && !q.finalizedFissure) {
            EarthquakeFissure f = new EarthquakeFissure(level, q, carveSpan);
            while (q.carvedWaves < carveSpan) {
                f.carveWave(q.carvedWaves);
                q.carvedWaves++;
            }
            f.dryFissure();
            f.settleWater();
            f.lineWithCorruption();
            f.spawnPeripheralCracks();
            if (cfg.quakeConvertBiome.get()) {
                f.convertBiome();
            }
            q.finalizedFissure = true;
            q.columnIndex = null; // 足跡の列の表はもう使わない(噴出中は要らない)。
            RaidChunkLoader.release(level, q.forcedChunks);
            // 噴出中に固定するのは、ハザード(感染・汚染シダ)が働く quakeEruptHazardRadiusChunks の正方形だけ
            // (既定 3 = 以前の固定値と同じ 7x7)。0 ならハザードが無いので何も固定しない(粒子は読み込みと無関係)。
            // 2026-09-25(報告 1450): 印だけ付ける。直前まで足跡の固定で読み込まれているので、同期で読む必要は無い。
            int hazardChunks = cfg.quakeEruptHazardRadiusChunks.get();
            q.forcedChunks = hazardChunks > 0
                    ? RaidChunkLoader.forceWithoutLoading(level,
                            new BlockPos(Mth.floor(q.ex), q.ey, Mth.floor(q.ez)), hazardChunks)
                    : new long[0];
        }

        if (q.finalizedFissure && e >= eruptEnd) {
            RaidChunkLoader.release(level, q.forcedChunks);
            return true;
        }
        return false;
    }

    /**
     * この地割れが固定したチャンクがすべて読み込み終わったか({@code getChunkNow} は読み込み途中なら null で、
     * 読み込みを待たない。{@code hasChunk} は読み込み途中でも true なので、ここでは使わない)。
     */
    private static boolean forcedChunksLoaded(ServerLevel level, ActiveQuake q) {
        for (long key : q.forcedChunks) {
            if (level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) == null) {
                return false;
            }
        }
        return true;
    }

    private static int resolveSurface(ServerLevel level, int x, int z) {
        BlockPos g = TreeCanopyGroundFinder.findSpawnSurface(level, x, z);
        if (g != null) {
            return g.getY();
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }
}
