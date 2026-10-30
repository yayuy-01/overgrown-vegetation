package com.yayuy.overgrownvegetation.quake;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.BlightFernPlacement;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

/**
 * フェーズ1開始演出の「裂け目」そのものの幾何と、地形への実処理。
 *
 * <p>{@link EarthquakeData.ActiveQuake} 1件から毎 tick 使い捨てで生成される。状態は持たず、
 * 進捗(何番目の「波」まで掘ったか等)は {@link EarthquakeData.ActiveQuake} 側が持つ。
 *
 * <h2>形</h2>
 * <p>震央を中心に、ランダムな水平方向 {@code yaw} を長軸とする「いびつな」楕円の足跡
 * (境界・中心線・深さをすべて {@link #noise1} で歪ませてあるので、きれいな楕円にはならない)。
 * 掘削は「中心から両端へ」長軸方向に {@code formTicks} 個の波に分けて進む。各列は地表の
 * 構造物・木・そして陸地の水(池・泉・水たまり)も含めて上端から、最深部で {@code floorTargetY}
 * (Y 座標指定)まで掘る ── 裂け目の中の陸水は焼失するが、海・川バイオームの水源だけは残して
 * 自然に流れ込ませる(壁状に浮かせないため)。完成時に
 * 床・断面の壁・地表の縁を汚染ブロックへ変え、放射状の「ひび」を多数走らせ、足跡が掛かる
 * チャンクを過繁茂バイオームへ変換する。完成後は最深部から上空へ漏斗状に広がる緑の胞子を
 * 勢いよく噴き上げ続ける。
 */
final class EarthquakeFissure {

    /** 楕円外側の縁(地表の傷跡)を汚染する範囲。 */
    private static final double RIM_E = 1.3D;
    /** 走査する楕円距離の上限(これを超える列は一切触らない)。 */
    private static final double SCAN_E = 1.32D;
    /** 掘削する楕円距離の上限。これより外は掘らない(縁の凸凹は carveFloorY のノイズで付ける)。 */
    private static final double CARVE_E = 1.12D;
    /** 床の下をこの厚みぶん汚染ブロックにする。 */
    private static final int FLOOR_THICKNESS = 3;
    /** 噴出パーティクルの緑(過繁茂バイオームの漂う胞子と同系色、やや明るめ)。 */
    private static final Vector3f SPORE_GREEN = new Vector3f(0.34F, 0.72F, 0.27F);
    private static final double TAU = Math.PI * 2.0D;

    private final ServerLevel level;
    private final double ex;
    private final double ez;
    private final int ey;
    private final double ax;
    private final double az;
    private final double px;
    private final double pz;
    private final double halfLen;
    private final double halfWid;
    private final int length;
    private final int width;
    private final int floorTargetY;
    private final int minY;
    private final long seed;
    private final int formTicks;
    private final int qEruptX;
    private final int qEruptY;
    private final int qEruptZ;
    /** 足跡の列の表({@link EarthquakeData.ActiveQuake#columnIndex})を置いておく先。 */
    private final EarthquakeData.ActiveQuake quake;

    EarthquakeFissure(ServerLevel level, EarthquakeData.ActiveQuake q, int formTicks) {
        this.level = level;
        this.quake = q;
        this.ex = q.ex;
        this.ez = q.ez;
        this.ey = q.ey;
        this.ax = Math.cos(q.yaw);
        this.az = Math.sin(q.yaw);
        this.px = -Math.sin(q.yaw);
        this.pz = Math.cos(q.yaw);
        this.length = q.length;
        this.width = q.width;
        this.halfLen = Math.max(1.0D, q.length / 2.0D);
        this.halfWid = Math.max(1.0D, q.width / 2.0D);
        this.minY = level.getMinBuildHeight();
        this.floorTargetY = Mth.clamp(q.floorY, this.minY + 5, this.ey - 10);
        this.seed = q.seed;
        this.formTicks = Math.max(1, formTicks);
        this.qEruptX = q.eruptX;
        this.qEruptY = q.eruptY;
        this.qEruptZ = q.eruptZ;
    }

    // ---- 掘削(徐々に走る) ------------------------------------------------

    /** 波 {@code w}(0 = 中心, formTicks-1 = 両端)に属する列を掘る。 */
    void carveWave(int w) {
        // 波 w の列だけを forEachColumn の表から引く(掘削中は毎 tick 足跡全体を舐めていたので、ここが重かった。
        // 2026-09-24 に表を 1 回だけ作るようにした)。訪れる列・順番・e の値は全列を回してここで弾いていた頃と同じ。
        forEachColumn(w, (wx, wz, u, v, e) -> {
            if (e > CARVE_E || waveOf(u) != w) {
                return;
            }
            int fy = carveFloorY(u, v, e);
            int top = columnTop(wx, wz);
            // 列ごとに 1 本の連続した縦穴として掘る(途中に固体を残さない = 空中に壁が浮かない)。
            // 壁の凹凸は隣り合う列の carveFloorY が違うことで階段状に付く。
            for (int y = top; y > fy; y--) {
                carveCell(wx, y, wz);
            }
            RandomSource r = level.getRandom();
            if (r.nextInt(4) == 0) {
                level.sendParticles(ParticleTypes.LARGE_SMOKE, wx + 0.5D, ey + 0.6D, wz + 0.5D,
                        1, 0.3D, 0.15D, 0.3D, 0.02D);
            }
            if (r.nextInt(3) == 0) {
                level.sendParticles(new DustParticleOptions(SPORE_GREEN, 1.5F), wx + 0.5D, ey + 0.9D, wz + 0.5D,
                        2, 0.35D, 0.4D, 0.35D, 0.03D);
            }
        });
        // 亀裂が走る地響きは土砂崩れの音(EarthquakeData 側で1回再生)が主。ここでは近くのプレイヤー向けに
        // ときどき岩の砕ける音を薄く重ねるだけにする。
        if (w % 22 == 0) {
            level.playSound(null, BlockPos.containing(ex, ey, ez), SoundEvents.DEEPSLATE_BREAK, SoundSource.AMBIENT,
                    2.0F, 0.30F + level.getRandom().nextFloat() * 0.2F);
        }
    }

    /**
     * 足跡(楕円距離 {@link #SCAN_E} 以内の列 = {@link #forEachColumn} が訪れる全列)が掛かるチャンクのキー
     * (2026-09-25、ユーザー承認「レイドの見直し」、報告 1450)。地割れの掘削中に強制ロードするのはこれだけにする。
     *
     * <p>掘削・予兆の輪郭・床と縁の汚染・水抜きが触る列はすべてこの中にある。隣の列を覗く処理(液体の確認・側壁・
     * 周囲のひび)は足跡の外へ少しはみ出すが、強制ロードのチケットは固定したチャンクの周囲 2 チャンク(32 ブロック以上)も
     * 読み込むので、その中に収まる。完成時の水の落ち着け({@link #settleWater}、楕円距離 1.8 まで)だけは長い裂け目の
     * 先端で数列がさらに外へ出るので、読み込まれていない列を飛ばす(使い捨ての Java で既定の範囲の裂け目を数えた。
     * 足跡は長さ 112・幅 20 で 13〜18 チャンク、248・33 で 41〜51、384・46 で 83〜95。以前は常に 17×17 = 289)。
     *
     * <p>足跡の形は震央の高さ(ey)に依らない({@link #buildColumnIndex} は ex / ez / yaw / 長さ / 幅 / 種と
     * 波の数しか使わない)ので、{@link EarthquakeEvent} は ey を決める前にこれを呼ぶ。そのとき作ったインスタンスでは、
     * 高さに依る値(掘る深さ {@code floorTargetY} など)を使わないこと。作った列の表は
     * {@link EarthquakeData.ActiveQuake#columnIndex} に残り、同じ波の数なら予兆・掘削でそのまま使い回される。
     */
    long[] footprintChunks() {
        LongOpenHashSet chunks = new LongOpenHashSet();
        forEachColumn((wx, wz, u, v, e) -> chunks.add(ChunkPos.asLong(wx >> 4, wz >> 4)));
        return chunks.toLongArray();
    }

    /** 発生前の予兆: 地表にいびつな裂け目の輪郭を薄く描く。 */
    void warnOutline() {
        RandomSource r = level.getRandom();
        forEachColumn((wx, wz, u, v, e) -> {
            if (e < 0.82D || e > 1.12D || r.nextInt(4) != 0) {
                return;
            }
            int sy = columnTop(wx, wz);
            level.sendParticles(ParticleTypes.SMOKE, wx + 0.5D, sy + 1.0D, wz + 0.5D, 1, 0.12D, 0.02D, 0.12D, 0.0D);
            if (r.nextInt(4) == 0) {
                level.sendParticles(ParticleTypes.CRIT, wx + 0.5D, sy + 1.05D, wz + 0.5D, 1, 0.15D, 0.02D, 0.15D, 0.0D);
            }
        });
    }

    // ---- 完成時: 汚染ブロック化 + バイオーム変換 --------------------------

    /**
     * 掘った空洞の床・断面(側壁)・地表の縁を汚染ブロックへ変える。
     * {@link #dryFissure()} の直後に呼ばれるので、水が抜けた壁面もそのまま汚染ブロックで固まる。
     */
    void lineWithCorruption() {
        Long2ObjectOpenHashMap<int[]> columns = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet inside = new LongOpenHashSet();

        forEachColumn((wx, wz, u, v, e) -> {
            if (e > RIM_E) {
                return;
            }
            int fy = carveFloorY(u, v, e);
            columns.put(key(wx, wz), new int[]{wx, wz, fy, (int) Math.round(e * 1000.0D)});
            if (e <= 1.0D) {
                inside.add(key(wx, wz));
            }
        });

        for (int[] col : columns.values()) {
            int wx = col[0];
            int wz = col[1];
            int fy = col[2];
            double e = col[3] / 1000.0D;

            if (e <= 1.0D) {
                // 床とその下
                for (int y = fy; y >= fy - FLOOR_THICKNESS; y--) {
                    corruptCell(wx, y, wz);
                }
                // 断面(側壁): 隣が裂け目の外なら、その露出面を底まで汚染ブロックにする。
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    int nx = wx + dir.getStepX();
                    int nz = wz + dir.getStepZ();
                    if (inside.contains(key(nx, nz))) {
                        continue;
                    }
                    int top = Math.max(ey, columnTop(nx, nz));
                    for (int y = top; y >= fy - 2; y--) {
                        corruptCell(nx, y, nz);
                    }
                }
            } else {
                // 裂け目の縁(地表の傷跡)
                int sy = surfaceTop(wx, wz);
                for (int y = sy; y >= sy - 3; y--) {
                    corruptCell(wx, y, wz);
                }
            }
        }
    }

    /**
     * 裂け目の体積内に残った液体(掘削中に隣から漏れ込んだ陸地の水など)を完成時に一掃し、乾いた空洞にする。
     * <b>海・川バイオームの水は対象外</b> ── そこは意図的に水が流れ込んで満ちるままにする。
     * 陸地側の水はここで消えたきり戻らない。
     */
    void dryFissure() {
        forEachColumn((wx, wz, u, v, e) -> {
            if (e > RIM_E) {
                return;
            }
            int fy = carveFloorY(u, v, e) - 1;
            for (int y = ey + 8; y >= fy; y--) {
                BlockPos p = new BlockPos(wx, y, wz);
                FluidState fs = level.getFluidState(p);
                if (fs.isEmpty()) {
                    continue;
                }
                if (fs.is(FluidTags.WATER) && isOceanOrRiver(p)) {
                    continue;
                }
                BlockState s = level.getBlockState(p);
                if (s.getDestroySpeed(level, p) < 0.0F) {
                    continue;
                }
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
            }
        });
    }

    /**
     * 完成時に、裂け目の内側とすぐ外側の水を全て「フロー更新待ち」にして、支えを失った水源が
     * 壁のように空中へ残らないようにする(掘削中の取りこぼしの後始末)。海・川はこれで裂け目へ
     * 流れ込み(満ちる)、陸地の水も崩れて落ちる。{@link #dryFissure()} の後に呼ぶ。
     */
    void settleWater() {
        int r = Mth.ceil(halfLen * 1.5D + halfWid * 2.7D) + 8;
        int cx = Mth.floor(ex);
        int cz = Mth.floor(ez);
        double roughU = halfLen * 1.6D;
        double roughV = halfWid * 3.4D;
        int top = ey + 12;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int wx = cx - r; wx <= cx + r; wx++) {
            for (int wz = cz - r; wz <= cz + r; wz++) {
                double rx = wx + 0.5D - ex;
                double rz = wz + 0.5D - ez;
                double u = rx * ax + rz * az;
                double v = rx * px + rz * pz;
                if ((u / roughU) * (u / roughU) + (v / roughV) * (v / roughV) > 1.0D) {
                    continue;
                }
                double e = ellipticE(u, v);
                if (e > 1.8D) {
                    continue;
                }
                // 読み込まれていない列は見ない(2026-09-25、報告 1450)。強制ロードを足跡のチャンクだけにしたので、
                // 長い裂け目の先端のすぐ外(楕円距離 1.3〜1.8)の数列が、足跡から 3 チャンク離れて読み込み範囲の外に出ることがある
                // (既定の最大の裂け目で 1 万数千列のうち数列)。掘った列からは遠く、水を流し直す必要が無いので、
                // その場で読み込まずに飛ばす。
                if (level.getChunkSource().getChunkNow(wx >> 4, wz >> 4) == null) {
                    continue;
                }
                // 裂け目の内側は底まで、外側の縁は地表付近だけ見ればよい。
                int lo = e <= 1.0D ? carveFloorY(u, v, e) - 2 : ey - 8;
                for (int y = top; y >= lo; y--) {
                    p.set(wx, y, wz);
                    FluidState fs = level.getFluidState(p);
                    if (!fs.isEmpty()) {
                        level.scheduleTick(p.immutable(), fs.getType(), fs.getType().getTickDelay(level));
                    }
                }
            }
        }
    }

    /**
     * 裂け目の縁から外へ、太さ・長さがバラバラの浅い「ひび」を走らせる(6〜10 本)。
     * 起点は中央だけでなく<b>全長にわたってまばらに</b>取る。
     */
    void spawnPeripheralCracks() {
        RandomSource r = RandomSource.create(seed ^ 0x9E3779B97F4A7C15L);
        int cracks = 6 + r.nextInt(5);
        for (int c = 0; c < cracks; c++) {
            // 起点: 長軸方向にまばらな位置 startU、その位置での縁(短軸方向の境界)。
            double startU = (r.nextDouble() - 0.5D) * length * 0.92D;
            double side = r.nextBoolean() ? 1.0D : -1.0D;
            double lo = 0.0D;
            double hi = halfWid * 4.0D + 4.0D;
            for (int i = 0; i < 22; i++) {
                double mid = (lo + hi) * 0.5D;
                if (ellipticE(startU, side * mid) < 1.0D) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            double u = startU;
            double v = side * lo;
            // 外向き(短軸方向)を主に、長軸方向へも少しふらつかせる。
            double hu = (r.nextDouble() - 0.5D) * 0.9D;
            double hv = side;
            double hn = Math.sqrt(hu * hu + hv * hv);
            hu /= hn;
            hv /= hn;

            int steps = 4 + r.nextInt(15);
            double thickness = 0.4D + r.nextDouble() * 2.4D;
            for (int s = 0; s < steps; s++) {
                // 進みながら向きを揺らし、太さもゆっくり変える
                double wob = (r.nextDouble() - 0.5D) * 1.1D;
                double nu = hu * Math.cos(wob) - hv * Math.sin(wob);
                double nv = hu * Math.sin(wob) + hv * Math.cos(wob);
                hu = nu;
                hv = nv;
                u += hu;
                v += hv;
                thickness = Mth.clamp(thickness + (r.nextDouble() - 0.5D) * 0.8D, 0.4D, 2.6D);

                int rad = (int) Math.round(thickness);
                int d = 1 + r.nextInt(4);
                for (int dx = -rad; dx <= rad; dx++) {
                    for (int dv = -rad; dv <= rad; dv++) {
                        if (dx * dx + dv * dv > rad * rad + 1) {
                            continue;
                        }
                        int wx = Mth.floor(ex + ax * (u + dx) + px * (v + dv) + 0.5D);
                        int wz = Mth.floor(ez + az * (u + dx) + pz * (v + dv) + 0.5D);
                        int g = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz) - 1;
                        for (int y = columnTop(wx, wz); y > g - d; y--) {
                            carveCell(wx, y, wz);
                        }
                        corruptCell(wx, g - d, wz);
                    }
                }
                if (r.nextInt(4) == 0) {
                    int wx = Mth.floor(ex + ax * u + px * v + 0.5D);
                    int wz = Mth.floor(ez + az * u + pz * v + 0.5D);
                    int sy = columnTop(wx, wz);
                    level.sendParticles(ParticleTypes.SMOKE, wx + 0.5D, sy + 0.5D, wz + 0.5D, 2, 0.2D, 0.05D, 0.2D, 0.0D);
                }
            }
        }
    }

    /**
     * 過繁茂バイオームへ変換するチャンクを集めて一括変換する(再送は1パケット):
     * <ul>
     *   <li>裂け目の足跡が掛かるチャンク({@code e <= RIM_E})。</li>
     *   <li>胞子噴出点を中心に {@code quakeSporeZoneChunks} チャンク半径の正方領域
     *   (= 胞子濃度100%の胞子感染ゾーン)。</li>
     * </ul>
     */
    void convertBiome() {
        LongOpenHashSet chunks = new LongOpenHashSet();
        forEachColumn((wx, wz, u, v, e) -> {
            if (e <= RIM_E) {
                chunks.add(ChunkPos.asLong(wx >> 4, wz >> 4));
            }
        });

        int zone = OvergrownVegetationConfig.COMMON.quakeSporeZoneChunks.get();
        if (zone > 0) {
            int cx = (qEruptY != Integer.MIN_VALUE ? qEruptX : Mth.floor(ex)) >> 4;
            int cz = (qEruptY != Integer.MIN_VALUE ? qEruptZ : Mth.floor(ez)) >> 4;
            for (int dx = -zone; dx <= zone; dx++) {
                for (int dz = -zone; dz <= zone; dz++) {
                    chunks.add(ChunkPos.asLong(cx + dx, cz + dz));
                }
            }
        }

        java.util.List<ChunkPos> list = new java.util.ArrayList<>(chunks.size());
        for (long c : chunks) {
            list.add(new ChunkPos(c));
        }
        OvergrownBiomeConverter.forceOvergrownBatch(level, list);
    }

    // ---- 胞子噴出 -------------------------------------------------------

    /**
     * 裂け目中央の底の点(胞子噴出の起点)。{@link #carveFloorY} は世界の状態に依存しない
     * 決定的な計算なので、掘削の途中でも呼べる。
     */
    int[] centerFloorPoint() {
        return new int[]{Mth.floor(ex), carveFloorY(0.0D, 0.0D, 0.0D), Mth.floor(ez)};
    }

    /**
     * 裂け目の中央の底から、専用の緑の胞子({@link com.yayuy.overgrownvegetation.registry.ModParticles#OVERGROWN_SPORE})を
     * 花火のように噴き上げる。噴出点で一気に加速 → 毎 tick の摩擦で徐々に減速 → 地表を越えて上空まで
     * 届き、広範囲に散る(動きの設定は {@code quakeSpore*})。
     */
    void eruptSpores() {
        RandomSource r = level.getRandom();
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double speed = cfg.quakeSporeSpeed.get();
        double coneRad = Math.toRadians(cfg.quakeSporeConeDeg.get());
        double density = cfg.quakeSporeDensity.get();

        // 噴出は必ず「裂け目のいちばん深く掘れた点」から(完成時に記録済み)。
        int wx;
        int wz;
        int fy;
        if (qEruptY != Integer.MIN_VALUE) {
            wx = qEruptX;
            wz = qEruptZ;
            fy = qEruptY;
        } else {
            wx = Mth.floor(ex);
            wz = Mth.floor(ez);
            fy = level.getHeight(Heightmap.Types.MOTION_BLOCKING, wx, wz);
        }
        double bx = wx + 0.5D;
        double by = fy + 1.0D;
        double bz = wz + 0.5D;

        // 連続噴出 + たまに大きなパルス(花火の破裂)。数は quakeSporeDensity 倍。
        boolean burst = r.nextInt(3) == 0;
        int base = Math.max(1, Mth.ceil(22.0D * density));
        int extra = burst ? Math.max(1, Mth.ceil(70.0D * density)) : 0;
        for (int i = 0; i < base + extra; i++) {
            double a = (r.nextDouble() * 2.0D - 1.0D) * coneRad;
            double roll = r.nextDouble() * TAU;
            double power = (i >= base ? 1.0D : 0.82D) * (0.72D + r.nextDouble() * 0.5D);
            double sp = speed * power;
            double horiz = Math.sin(a) * sp;
            farSpore(bx + (r.nextDouble() - 0.5D) * 3.0D, by, bz + (r.nextDouble() - 0.5D) * 3.0D,
                    horiz * Math.cos(roll), Math.cos(a) * sp, horiz * Math.sin(roll));
        }

        // 遠くからのシルエット用: 地表からほぼ真上へ、ゆっくり太く立ちのぼる筋(長持ち)。
        int pillar = Math.max(1, Mth.ceil(6.0D * density));
        for (int p = 0; p < pillar; p++) {
            double a = r.nextDouble() * TAU;
            double rr = r.nextDouble() * 2.5D;
            farSpore(bx + Math.cos(a) * rr, ey + 1.0D, bz + Math.sin(a) * rr,
                    (r.nextDouble() - 0.5D) * 0.3D, 2.2D + r.nextDouble() * 1.8D, (r.nextDouble() - 0.5D) * 0.3D);
        }

        // 上空に漂う本物の胞子(散ったあとゆっくり降ってくる仕上げ)
        double topSpread = 4.0D + width * 0.5D;
        int haze = Math.max(1, Mth.ceil(3.0D * density));
        for (int p = 0; p < haze; p++) {
            double a = r.nextDouble() * TAU;
            double rad = 1.0D + r.nextDouble() * topSpread;
            double hx = bx + Math.cos(a) * rad;
            double hy = ey + 6.0D + r.nextDouble() * 28.0D;
            double hz = bz + Math.sin(a) * rad;
            for (ServerPlayer pl : level.players()) {
                level.sendParticles(pl, ParticleTypes.SPORE_BLOSSOM_AIR, true, hx, hy, hz, 1, 0.5D, 0.4D, 0.5D, 0.0D);
            }
        }

        if (r.nextInt(12) == 0) {
            level.playSound(null, BlockPos.containing(ex, ey, ez), SoundEvents.SCULK_CATALYST_BLOOM,
                    SoundSource.AMBIENT, 2.0F, 0.6F);
        }
    }

    /** 過繁茂の胞子パーティクルを、全プレイヤーへ「遠距離」({@code longDistance} = 512 ブロック)で送る。 */
    private void farSpore(double x, double y, double z, double vx, double vy, double vz) {
        for (ServerPlayer pl : level.players()) {
            level.sendParticles(pl, ModParticles.OVERGROWN_SPORE.get(), true, x, y, z, 0, vx, vy, vz, 1.0D);
        }
    }

    // ---- 噴出中のハザード(確定感染 + 汚染シダ) --------------------------

    /** 噴出エフェクトの中心の水平座標(未記録なら震央)。 */
    private double[] hazardCenter() {
        return qEruptY != Integer.MIN_VALUE
                ? new double[]{qEruptX + 0.5D, qEruptZ + 0.5D}
                : new double[]{ex, ez};
    }

    private int hazardRadius() {
        return OvergrownVegetationConfig.COMMON.quakeEruptHazardRadiusChunks.get() * 16;
    }

    /**
     * 噴出エフェクトの中心の周囲、水平半径 {@code quakeEruptHazardRadiusChunks} チャンク以内の
     * ランダムな地表(水中なら海底)に汚染シダ / 汚染水草を湧かせる。過繁茂ブロックの上や
     * 塞がった場所は避け、範囲内の汚染シダが {@code quakeEruptFernMaxNearby} に達したら打ち止め。
     */
    void spawnRandomFerns() {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        int radius = hazardRadius();
        if (radius <= 0) {
            return;
        }
        double[] c = hazardCenter();
        int baseX = Mth.floor(c[0]);
        int baseZ = Mth.floor(c[1]);

        int maxNearby = cfg.quakeEruptFernMaxNearby.get();
        if (maxNearby > 0) {
            AABB area = new AABB(baseX - radius, level.getMinBuildHeight(), baseZ - radius,
                    baseX + radius, level.getMaxBuildHeight(), baseZ + radius);
            if (level.getEntitiesOfClass(BlightFernEntity.class, area).size() >= maxNearby) {
                return;
            }
        }

        RandomSource r = level.getRandom();
        int attempts = Math.max(1, cfg.quakeEruptFernAttemptsPerInterval.get());
        for (int i = 0; i < attempts; i++) {
            double ang = r.nextDouble() * TAU;
            int dist = r.nextInt(radius + 1);
            int x = baseX + (int) Math.round(Math.cos(ang) * dist);
            int z = baseZ + (int) Math.round(Math.sin(ang) * dist);
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            int oceanFloor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
            BlockPos pos = oceanFloor < surface ? new BlockPos(x, oceanFloor, z) : new BlockPos(x, surface, z);
            if (fernSpotOk(pos)) {
                BlightFernPlacement.spawn(level, pos, MobSpawnType.NATURAL);
            }
        }
    }

    /** {@code pos}(植物本体のマス)に汚染シダ / 汚染水草を置けるか。足場は固体・非汚染、本体+頭上が空き。 */
    private boolean fernSpotOk(BlockPos pos) {
        BlockState ground = level.getBlockState(pos.below());
        if (!ground.blocksMotion() || ground.is(ModTags.CORRUPTED)) {
            return false;
        }
        BlockState at = level.getBlockState(pos);
        BlockState above = level.getBlockState(pos.above());
        if (at.blocksMotion() || above.blocksMotion()) {
            return false;
        }
        // 水中(→汚染水草)は可。溶岩など他の液体は不可。
        return at.getFluidState().isEmpty() || at.getFluidState().is(FluidTags.WATER);
    }

    // ---- 形(いびつな楕円) ------------------------------------------------

    /**
     * 軸座標 {@code (u, v)}(u = 長軸, v = 短軸)での「いびつな楕円距離」。1 以下が内側。
     *
     * <p>中心線のうねり・位置ごとの幅の膨らみ/くびれを {@link #noise1} で歪ませつつ、
     * 幅プロファイルに端へ向かって細くなるテーパー({@code taper})を必ず掛けるので、
     * いびつでも「両端が尖った刃のような形」になる。
     */
    private double ellipticE(double u, double v) {
        double lenN = Mth.clamp(1.0D + 0.15D * noise1(v * 0.16D, 0x5DL), 0.82D, 1.2D);
        double eu = u / (halfLen * lenN);
        double eu2 = eu * eu;
        if (eu2 >= 1.6D) {
            return 2.0D + eu2;
        }
        // 端へ向かうテーパー(中心 1、端 0)。localWid にこれを掛け、さらに eu2+ev2<=1 の楕円式と
        // 合わさることで、実効の半幅は概ね (1 - eu2) に比例する(= 直線的に尖っていく)。
        double taper = Math.pow(Math.max(0.0D, 1.0D - Math.min(1.0D, eu2)), 0.5D);
        double meander = halfWid * 0.5D * noise1(u * 0.08D, 0x71L);
        double vv = v - meander;
        double widN = 0.72D + 0.48D * noise1(u * 0.11D, 0x13L)
                + 0.34D * noise1(vv * 0.27D + u * 0.045D, 0x37L);
        double localWid = Math.max(0.5D, halfWid * taper * Mth.clamp(widN, 0.3D, 1.7D));
        double ev = vv / localWid;
        return eu2 + ev * ev;
    }

    /**
     * 軸座標 {@code (u, v)} / 楕円距離 {@code e} の列の、実際に掘る一番下の Y(絶対座標)。
     * 中心ほど深く {@code floorTargetY}。大小 2 スケールのノイズで床を凸凹させ壁を階段状にし、
     * ノイズの塊に当たった列は途中で止めて「棚(ledge)」を残す(平らな壁面をなくす)。
     */
    private int carveFloorY(double u, double v, double e) {
        double t = Math.pow(Mth.clamp(1.0D - e, 0.0D, 1.0D), 0.5D);
        double base = ey - (ey - floorTargetY) * t;
        double jit = noise1(u * 0.6D, 0x21L) * 2.2D
                + noise1(v * 0.85D, 0x22L) * 2.0D
                + noise1(u * 0.19D + v * 0.13D, 0x2BL) * 7.5D
                + noise1(u * 0.07D - v * 0.05D, 0x2CL) * 5.0D;
        int fy = Mth.clamp((int) Math.round(base + jit), minY + 3, ey - 1);

        // 縁寄り(e が大きい)の列だけ、たまに浅めで止めて壁から張り出す「棚」を残す。
        // 途中に固体を残すわけではない(棚の下は元の地形と繋がったまま)ので空中には浮かない。
        // 中心の深い部分には作らない(凸凹は上のノイズだけで付ける)。
        if (e > 0.5D) {
            double ledge = noise1(u * 0.31D + v * 0.27D, 0x4DL);
            if (ledge > 0.55D) {
                fy = Math.min(ey - 1, fy + (int) ((ledge - 0.55D) * 30.0D));
            }
        }
        return fy;
    }

    private int waveOf(double axial) {
        return Mth.clamp((int) Math.floor(Math.abs(axial) / halfLen * formTicks), 0, formTicks - 1);
    }

    /** フレームレート非依存の滑らかな擬似ノイズ、範囲 ~[-1, 1](3 つの正弦波の重み付き和)。 */
    private double noise1(double x, long salt) {
        long s = seed ^ salt;
        double p1 = (s & 0xFFFFL) * 9.5e-4D;
        double p2 = (s >>> 16 & 0xFFFFL) * 7.1e-4D;
        double p3 = (s >>> 32 & 0xFFFFL) * 5.3e-4D;
        return 0.5D * Math.sin(x + p1)
                + 0.3D * Math.sin(x * 2.37D + p2 * 1.7D + 1.3D)
                + 0.2D * Math.sin(x * 5.11D + p3 * 2.9D + 2.7D);
    }

    // ---- ブロック操作 ------------------------------------------------

    /** その列の一番上のブロックの Y(木・構造物・葉・雪も含む「上端」)。 */
    private int columnTop(int wx, int wz) {
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
    }

    /** 木の天蓋・幹・水を貫いた先の本物の地表ブロックの Y。 */
    private int surfaceTop(int wx, int wz) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz) - 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(wx, y, wz);
        int floor = minY + 1;
        for (int i = 0; i < 80 && p.getY() > floor; i++) {
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && s.getFluidState().isEmpty() && !TreeCanopyGroundFinder.isTreePart(s)) {
                return p.getY();
            }
            p.move(Direction.DOWN);
        }
        return y;
    }

    /**
     * 空洞にするマスを空気にする。木・構造物・葉、そして<b>陸地の水(池・泉・水たまり)や溶岩は
     * まるごと消す</b> ── そこにあった水は焼失し、空洞は乾いたままになる。海・川バイオームの
     * 水源だけは消さず、そのまま裂け目へ流れ込ませる(海底を貫けばそこは水で満ちる)。
     *
     * <p><b>液体に接するマスを掘るときは必ず</b>近傍更新あり({@code 3})+ 6 方向の隣接液体へ
     * フロー更新を掛ける。これをしないと、支えを失った水源が更新されないまま「壁」のように
     * 空中へ残ってしまう(スクショの問題)。更新すれば水は掘った空洞へ流れ落ちる。
     */
    private void carveCell(int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = level.getBlockState(p);
        if (s.isAir()) {
            return;
        }
        if (s.getDestroySpeed(level, p) < 0.0F) {
            return;
        }

        // 海・川バイオームの水源は残す(自然に流し込む)。それ以外の液体・固体は掘る。
        FluidState here = s.getFluidState();
        boolean keepThisCell = !here.isEmpty() && here.is(FluidTags.WATER) && isOceanOrRiver(p);

        // このマス自身 or いずれかの隣が液体かどうか。
        boolean touchesFluid = !here.isEmpty();
        for (Direction d : Direction.values()) {
            if (!level.getFluidState(p.relative(d)).isEmpty()) {
                touchesFluid = true;
                break;
            }
        }

        if (!keepThisCell) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), touchesFluid ? 3 : (2 | 16));
        }
        if (touchesFluid) {
            for (Direction d : Direction.values()) {
                BlockPos np = p.relative(d);
                FluidState nf = level.getFluidState(np);
                if (!nf.isEmpty()) {
                    level.scheduleTick(np, nf.getType(), nf.getType().getTickDelay(level));
                }
            }
        }
    }

    /** その位置が海・川バイオームか(そこの水源は焼失させず自然に流す)。 */
    private boolean isOceanOrRiver(BlockPos p) {
        Holder<Biome> b = level.getBiome(p);
        return b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_DEEP_OCEAN) || b.is(BiomeTags.IS_RIVER);
    }

    private void corruptCell(int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = level.getBlockState(p);
        if (s.isAir() || s.is(ModTags.CORRUPTED)) {
            return;
        }
        if (!CorruptionRegistry.canCorrupt(level, p, s)) {
            return;
        }
        BlockState corrupted = CorruptionRegistry.corrupt(level, p, s, level.getRandom());
        // 竹などはここで茎まるごとドロップ無しで消される(placeCorrupted 内の特別処理)。
        CorruptionRegistry.placeCorrupted(level, p, s, corrupted, 2 | 16);
    }

    private static long key(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) << 32 | ((long) z & 0xFFFFFFFFL);
    }

    /**
     * 裂け目の足跡に掛かる全ての世界列をちょうど 1 回ずつ訪れる。ワールド座標のバウンディング
     * ボックスを走査し、各列を裂け目の軸/垂直座標へ射影して {@link #ellipticE} を求める。
     */
    private void forEachColumn(ColumnConsumer consumer) {
        forEachColumn(-1, consumer);
    }

    /**
     * {@link #forEachColumn(ColumnConsumer)} のうち、波 {@code wave} に属する列だけを訪れる版
     * ({@code wave} が負なら全列)。弾いた列には consumer を呼ばないだけで、残る列の順番と渡す値は全列版と変わらない。
     *
     * <p>2026-09-24 軽量化(報告 1075): 以前は呼ぶたびに外接正方形(最大で約 700×700 列)を回し、足跡の中の
     * 全列で {@link #ellipticE}(正弦を十数回)を計算していた。掘削中は毎 tick、予兆の終盤は 4 tick ごと、
     * 完成の tick には 3 回。足跡の形はこの地割れに固有で世界の状態に依存しないので、{@link #columnIndex()} で
     * 1 回だけ表にして、以降は表を引く。表は同じ式・同じ順番(wx → wz の昇順)で作るので、訪れる列・順番・
     * 渡す u / v / e はどれも毎回計算していた頃と同じ double になる。
     */
    private void forEachColumn(int wave, ColumnConsumer consumer) {
        ColumnIndex idx = columnIndex();
        if (wave < 0) {
            for (int i = 0; i < idx.wx.length; i++) {
                consumer.accept(idx.wx[i], idx.wz[i], idx.u[i], idx.v[i], idx.e[i]);
            }
            return;
        }
        // waveOf は [0, formTicks - 1] に丸めるので、その外の波に属する列は無い(以前も何も訪れなかった)。
        if (wave >= idx.byWave.length) {
            return;
        }
        for (int i : idx.byWave[wave]) {
            consumer.accept(idx.wx[i], idx.wz[i], idx.u[i], idx.v[i], idx.e[i]);
        }
    }

    /** この地割れの列の表。無いか、波の数(設定の quakeFormTicks 由来)が変わっていたら作り直す。 */
    private ColumnIndex columnIndex() {
        ColumnIndex idx = quake.columnIndex;
        if (idx == null || idx.formTicks != formTicks) {
            idx = buildColumnIndex();
            quake.columnIndex = idx;
        }
        return idx;
    }

    /**
     * 足跡に掛かる全ての世界列(楕円距離 {@link #SCAN_E} 以内)を、以前の {@code forEachColumn} と同じ走査・同じ判定で
     * 集めて表にする。ワールド座標のバウンディングボックスを走査し、各列を裂け目の軸/垂直座標へ射影して
     * {@link #ellipticE} を求める。
     */
    private ColumnIndex buildColumnIndex() {
        int r = Mth.ceil(halfLen * 1.5D + halfWid * 2.7D) + 3;
        int cx = Mth.floor(ex);
        int cz = Mth.floor(ez);
        double roughU = halfLen * 1.45D;
        double roughV = halfWid * 2.6D;
        IntArrayList xs = new IntArrayList();
        IntArrayList zs = new IntArrayList();
        DoubleArrayList us = new DoubleArrayList();
        DoubleArrayList vs = new DoubleArrayList();
        DoubleArrayList es = new DoubleArrayList();
        IntArrayList waves = new IntArrayList();
        int[] waveCounts = new int[formTicks];
        for (int wx = cx - r; wx <= cx + r; wx++) {
            for (int wz = cz - r; wz <= cz + r; wz++) {
                double rx = wx + 0.5D - ex;
                double rz = wz + 0.5D - ez;
                double u = rx * ax + rz * az;
                double v = rx * px + rz * pz;
                // 楕円の外側は重いノイズ計算をせず素早く弾く
                if ((u / roughU) * (u / roughU) + (v / roughV) * (v / roughV) > 1.0D) {
                    continue;
                }
                double e = ellipticE(u, v);
                if (e > SCAN_E) {
                    continue;
                }
                int w = waveOf(u);
                xs.add(wx);
                zs.add(wz);
                us.add(u);
                vs.add(v);
                es.add(e);
                waves.add(w);
                waveCounts[w]++;
            }
        }
        int[][] byWave = new int[formTicks][];
        for (int w = 0; w < formTicks; w++) {
            byWave[w] = new int[waveCounts[w]];
        }
        int[] filled = new int[formTicks];
        for (int i = 0; i < waves.size(); i++) {
            int w = waves.getInt(i);
            byWave[w][filled[w]++] = i; // 添字の昇順 = 全列版の訪問順
        }
        return new ColumnIndex(formTicks, xs.toIntArray(), zs.toIntArray(),
                us.toDoubleArray(), vs.toDoubleArray(), es.toDoubleArray(), byWave);
    }

    /**
     * 足跡の列の表(2026-09-24 軽量化 報告 1075)。{@code wx[i] … e[i]} が i 番目の列(全列版の訪問順)、
     * {@code byWave[w]} が波 {@code w} に属する列の添字(昇順)。{@link EarthquakeData.ActiveQuake#columnIndex} に
     * 保存せずに置く。
     */
    static final class ColumnIndex {
        final int formTicks;
        final int[] wx;
        final int[] wz;
        final double[] u;
        final double[] v;
        final double[] e;
        final int[][] byWave;

        ColumnIndex(int formTicks, int[] wx, int[] wz, double[] u, double[] v, double[] e, int[][] byWave) {
            this.formTicks = formTicks;
            this.wx = wx;
            this.wz = wz;
            this.u = u;
            this.v = v;
            this.e = e;
            this.byWave = byWave;
        }
    }

    @FunctionalInterface
    private interface ColumnConsumer {
        void accept(int wx, int wz, double axial, double perp, double ellipticE);
    }
}
