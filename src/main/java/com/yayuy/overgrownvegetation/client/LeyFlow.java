package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.network.LeyFlowPayload;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * <b>地脈の流れ</b>(2026-10-16。極相で地脈になる線に沿って、時々(数分に一度)青緑の光の帯が<b>線の上の宙を漂い</b>、
 * 光の粒が線に沿って流れて十数秒で消える。ブロックは置かず描画だけ)。storyline の段階の案「過繁茂 = 時々見える地脈の流れ」。
 * 最初はユーザー選択「地面から透ける光の帯」で地面に貼った板にしたが、シダの森では下草に隠れて見えなかったので、
 * 同じ日にユーザー指定で「地面が光るのではなく、帯のようなものが地脈のできる場所の上を漂う」へ替えた。
 *
 * <ul>
 *     <li>線のマスはサーバーが送る({@link LeyFlowPayload}。チャンクを見始めたとき。線の場はシードで決まり、シードはクライアントに無いため)。
 *     マスごとに強さ(線の中心で 1、中心から 2.5 ブロックで 0)・線の向き・線に沿った座標を持つ。</li>
 *     <li>流れは 2〜4 分おき(ワールドに入って 30〜90 秒後に最初)。14 秒: 3 秒かけて地面から浮かび上がり、8 秒漂い、3 秒かけて昇りながら消える。仮決め。</li>
 *     <li>帯: 線の中心から 1 ブロック以内のマスが、それぞれ線に沿った短い光の板(前後 {@link #SEG_HALF} ブロック、中ほどが明るく両端と上下の縁が 0)を
 *     描く(加算)。板の置き場所は<b>周りの線のマスの重心</b>(強さの 2 乗で重み付け)= 線の中心なので、斜めの線でもマス目の階段にならない。
 *     隣の板と重なって 1 本のなめらかな帯になる(重みは中心からの距離で 1→0。重なりの合計はどの向きの線でもほぼ同じ明るさ)。
 *     板の向き(線の向き)も周りのマスの並びから求める(サーバーの向きは歪めた座標の上のもので、地面の上の線とずれる。{@link #ensureShape})。
 *     板の幅の向きは「線の向き × 目への向き」で、どこから見ても帯の面が見える(線の真上からは平ら、横からは立った帯)。
 *     線をまっすぐ見通すときだけ縦に寄せる(外積が 0 に近いとちらつくため)。</li>
 *     <li>高さ: 周りの線のマスの地面の平均 + {@link #HOVER} と、下草・茂みのてっぺんの平均 + {@link #HOVER_PLANTS} の高い方
 *     (開けた地面や高さ 1 の下草なら地面から 1.9、高さ 2 のシダの中なら 2.9。最初の 1.9 だけでは、森の高さ 2〜3 の茂みに埋もれた)。
 *     上下・左右にゆったり波打ち({@link #BOB}・{@link #SWAY})、幅が細くなったり太くなったりして(ねじれて見える)、
 *     波は線に沿って毎秒 2 ブロックほどで流れていく。明るい所(脈)は線に沿って毎秒 {@link #PULSE_SPEED} ブロックで流れる
 *     ({@link #PULSE_PERIOD} ブロックおき)。</li>
 *     <li>粒: 流れている間、帯の高さに湧いて線の向きへ毎秒 2〜4 ブロックで流れ、少し昇って 2〜4 秒で消える。</li>
 *     <li>過繁茂の地域(極相を含む)だけ。霧の奥(霧の終わりの手前から)と目の前 1〜4 ブロックは薄くする(歩いて帯に入ったとき画面が光で埋まらないように)。
 *     色は Vault デザイン方針の「地脈・結晶の青緑」(人類の魔法の琥珀と見分ける)。</li>
 * </ul>
 * 設定 {@code leyFlowEnabled}。数値はすべて仮決め。テストクライアントの {@code wind leyflow}(すぐ流す)・{@code wind gotoley}(近くの線へ行く)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class LeyFlow {

    /** 帯を描く、目からの水平の距離(ブロック)。 */
    private static final double RANGE = 40.0;
    /** 脈の流れる速さ(ブロック/秒)と間隔(ブロック)。 */
    private static final double PULSE_SPEED = 5.0;
    private static final double PULSE_PERIOD = 14.0;
    /** 1 回の流れの長さ(tick): 浮かぶ・漂う・消える。 */
    private static final int FADE_IN = 60;
    private static final int HOLD = 160;
    private static final int FADE_OUT = 60;
    /** 粒の上限。 */
    private static final int MAX_MOTES = 160;
    /** 青緑(地脈・結晶の色)。 */
    private static final float R = 0.35F;
    private static final float G = 1.0F;
    private static final float B = 0.82F;

    /** 帯を描くマスの強さの下限(中心から 1 ブロック = 0.6)。重みは 0.6 で 0、中心で 1。 */
    private static final float CORE_STRENGTH = 0.6F;
    /** 重心と地面の平均を取る半径(ブロック)。帯の幅(5)の半分より少し広く。 */
    private static final int SHAPE_RADIUS = 3;
    /** 帯の中心の高さ(ブロック): 地面の平均から {@link #HOVER}、下草・茂みのてっぺんの平均から {@link #HOVER_PLANTS} の、高い方。 */
    private static final double HOVER = 1.9;
    private static final double HOVER_PLANTS = 0.9;
    /** 1 マスの板の前後の長さ(ブロック。隣の板と重なる)と、帯の幅の半分(ブロック)。 */
    private static final double SEG_HALF = 1.0;
    private static final double HALF_WIDTH = 0.75;
    /** 波打ち: 上下・左右の振れ幅(ブロック)。線に沿った波数(1/ブロック)と速さ(1/秒)。速さ ÷ 波数 = 波の流れる速さ(ブロック/秒)。 */
    private static final double BOB = 0.28;
    private static final double BOB_K = 0.26;
    private static final double BOB_W = 0.55;
    private static final double SWAY = 0.35;
    private static final double SWAY_K = 0.13;
    private static final double SWAY_W = 0.3;
    /** ねじれ(幅の太さの波)。 */
    private static final double TWIST_K = 0.19;
    private static final double TWIST_W = 0.45;
    /** 帯の芯の濃さ(加算)。帯の上下の縁へ 0 になる。仮決め。 */
    private static final float BAND_ALPHA = 0.45F;
    /** 帯の断面: 中心からの位置(幅の半分を 1)と、その位置の濃さ。 */
    private static final float[] ROW_V = {-1.0F, -0.4F, 0.0F, 0.4F, 1.0F};
    private static final float[] ROW_A = {0.0F, 0.55F, 1.0F, 0.55F, 0.0F};
    private static final float[] PX = new float[15];
    private static final float[] PY = new float[15];
    private static final float[] PZ = new float[15];
    private static final float[] PA = new float[15];
    /** {@link #ensureShape} の周りのマスの写し(半径 3 の円の中は 29 マス)。 */
    private static final double[] NX = new double[49];
    private static final double[] NZ = new double[49];
    private static final double[] NA = new double[49];
    private static final double[] NW = new double[49];
    private static final double[] NH = new double[49];

    /** 線の上の 1 マス。 */
    private static final class Cell {
        final int x;
        final int z;
        final float strength;
        final float dirX;
        final float dirZ;
        final float along;
        /** 地面の上面の高さ(NaN = まだ調べていない、−∞ = 地面が無い)と、過繁茂の地域か。 */
        float ground = Float.NaN;
        boolean overgrown;
        /** 地面を調べたときの目の高さ(離れたら調べ直す。巨大樹の枝の上で調べた高さを、下りてからも使わないように)。 */
        int groundRef;
        /**
         * 帯の置き場所(周りの線のマスの重心)とそこの線に沿った座標・地面の上の線の向き・沿った座標の 1 ブロックあたりの増え方・
         * 帯の中心の高さ(周りの地面・下草から。下の {@link #slope} と組)。{@link #shapeGen} が今の {@link #generation} と違えば作り直す({@link #ensureShape})。
         */
        double centerX;
        double centerZ;
        double centerAlong;
        float tanX;
        float tanZ;
        float alongRate;
        float base;
        /** 帯の高さの、線の向きへ 1 ブロック進むごとの増え方(坂に沿わせる)。 */
        float slope;
        /** 下草・茂みのてっぺん(地面のすぐ上から空気まで。草が無ければ地面と同じ)。 */
        float plantTop;
        int shapeGen = -1;
        int shapeRef;

        Cell(int x, int z, float strength, float dirX, float dirZ, float along) {
            this.x = x;
            this.z = z;
            this.strength = strength;
            this.dirX = dirX;
            this.dirZ = dirZ;
            this.along = along;
        }
    }

    private static final class Mote {
        double x;
        double y;
        double z;
        final double vx;
        final double vz;
        final int life;
        int age;
        final float size;

        Mote(double x, double y, double z, double vx, double vz, int life, float size) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vz = vz;
            this.life = life;
            this.size = size;
        }
    }

    private static final Long2ObjectOpenHashMap<Cell[]> CHUNKS = new Long2ObjectOpenHashMap<>();
    /** 同じマスを位置で引く表(重心と地面の平均で周りのマスを見るため)。キーは {@code BlockPos.asLong(x, 0, z)}。 */
    private static final Long2ObjectOpenHashMap<Cell> CELLS = new Long2ObjectOpenHashMap<>();
    private static final List<Mote> MOTES = new ArrayList<>();
    private static final RandomSource RANDOM = RandomSource.create();
    private static ClientLevel flowLevel;
    /** 線のマスを受け取るたびに増える(後から届いた隣のチャンクで、縁のマスの重心を作り直すため)。 */
    private static int generation;
    /** 次の流れまでの tick と、今の流れの経過(−1 = 流れていない)。 */
    private static int nextFlowIn = 600 + RANDOM.nextInt(1200);
    private static int flowAge = -1;
    private static long ticks;
    private static boolean broken;

    /** サーバーから線のマスを受け取る(チャンクを見始めたとき)。 */
    public static void accept(LeyFlowPayload payload) {
        // ワールドが替わったかはここでも見る。ワールドに入るとき、近くのチャンクの線は最初の tick より先に届くので、
        // tick の側だけで見ると届いたばかりの線を消してしまう(2026-10-16 にテストクライアントで、入った所の線だけ描かれなかった)。
        resetIfLevelChanged(Minecraft.getInstance().level);
        ChunkPos chunk = payload.chunk();
        long[] packed = payload.cells();
        Cell[] cells = new Cell[packed.length];
        for (int i = 0; i < packed.length; i++) {
            long p = packed[i];
            int index = (int) (p & 0xFF);
            float strength = ((p >>> 8) & 0xFF) / 255.0F;
            double angle = ((p >>> 16) & 0xFFFF) / 65535.0 * Math.PI * 2.0;
            float along = Float.intBitsToFloat((int) (p >>> 32));
            cells[i] = new Cell(chunk.getMinBlockX() + (index & 15), chunk.getMinBlockZ() + (index >> 4), strength,
                    (float) Math.cos(angle), (float) Math.sin(angle), along);
        }
        Cell[] old = CHUNKS.put(chunk.toLong(), cells);
        if (old != null) {
            for (Cell c : old) {
                CELLS.remove(BlockPos.asLong(c.x, 0, c.z));
            }
        }
        for (Cell c : cells) {
            CELLS.put(BlockPos.asLong(c.x, 0, c.z), c);
        }
        generation++;
    }

    private static void resetIfLevelChanged(ClientLevel level) {
        if (level != flowLevel) {
            CHUNKS.clear();
            CELLS.clear();
            MOTES.clear();
            flowLevel = level;
            flowAge = -1;
            nextFlowIn = 600 + RANDOM.nextInt(1200);
            generation++;
        }
    }

    /** 開発用(テストクライアントの {@code wind leyflow}): すぐに流す。 */
    public static void startNow() {
        flowAge = 0;
    }

    /** 開発用: 受け取っている線のマスのうち、(x, z) にいちばん近いもの {x, z}。無ければ null。 */
    /**
     * 地脈の線に沿った座標(ブロック。光の流れの向きへ増える。サーバーが行き先 = ハイヴノード・巨大樹・器官の方へ向きをそろえて送る)。
     * そのマスの線を受け取っていなければ NaN。極相の密林の「網の目」({@link LeyNightGlow})が、地脈のブロックを流れる光に使う。
     */
    public static float alongAt(int x, int z) {
        Cell c = CELLS.get(BlockPos.asLong(x, 0, z));
        return c == null ? Float.NaN : c.along;
    }

    public static int[] nearestCell(double x, double z) {
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (Cell[] cells : CHUNKS.values()) {
            for (Cell c : cells) {
                double d = (c.x - x) * (c.x - x) + (c.z - z) * (c.z - z);
                if (c.strength > 0.8F && d < bestD) {
                    bestD = d;
                    best = new int[]{c.x, c.z};
                }
            }
        }
        return best;
    }

    /** 開発用: プレイヤーの周り 3 チャンクで、線のマスを受け取っているチャンクの一覧。 */
    public static String describeChunks(int pcx, int pcz) {
        StringBuilder sb = new StringBuilder("受け取り済み:");
        for (int dz = -3; dz <= 3; dz++) {
            for (int dx = -3; dx <= 3; dx++) {
                Cell[] cells = CHUNKS.get(ChunkPos.asLong(pcx + dx, pcz + dz));
                if (cells != null) {
                    sb.append(" (").append(pcx + dx).append(",").append(pcz + dz).append(")=").append(cells.length);
                }
            }
        }
        return sb.toString();
    }

    /** 今の流れの出入り(0〜1)。 */
    private static float envelope(float partialTick) {
        if (flowAge < 0) {
            return 0.0F;
        }
        float t = flowAge + partialTick;
        float in = CanopyLight.smooth(Mth.clamp(t / FADE_IN, 0.0F, 1.0F));
        float out = 1.0F - CanopyLight.smooth(Mth.clamp((t - FADE_IN - HOLD) / FADE_OUT, 0.0F, 1.0F));
        return in * out;
    }

    /** 帯の高さの出入り: 浮かぶ間は 0.7 ブロック下から上がってきて、消える間は 0.5 ブロック昇る。 */
    private static float lift(float partialTick) {
        if (flowAge < 0) {
            return 0.0F;
        }
        float t = flowAge + partialTick;
        float in = CanopyLight.smooth(Mth.clamp(t / FADE_IN, 0.0F, 1.0F));
        float out = CanopyLight.smooth(Mth.clamp((t - FADE_IN - HOLD) / FADE_OUT, 0.0F, 1.0F));
        return -0.7F * (1.0F - in) + 0.5F * out;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        resetIfLevelChanged(level);
        if (level == null || player == null || mc.isPaused()) {
            return;
        }
        ticks++;
        // 遠くなったチャンクの線を捨てる(見え始めたら送り直される)。
        if (ticks % 100 == 0) {
            int pcx = player.chunkPosition().x;
            int pcz = player.chunkPosition().z;
            for (Iterator<Long2ObjectMap.Entry<Cell[]>> it = CHUNKS.long2ObjectEntrySet().iterator(); it.hasNext(); ) {
                Long2ObjectMap.Entry<Cell[]> e = it.next();
                if (Math.abs(ChunkPos.getX(e.getLongKey()) - pcx) > 16 || Math.abs(ChunkPos.getZ(e.getLongKey()) - pcz) > 16) {
                    for (Cell c : e.getValue()) {
                        CELLS.remove(BlockPos.asLong(c.x, 0, c.z));
                    }
                    it.remove();
                }
            }
        }
        if (!OvergrownVegetationConfig.COMMON.leyFlowEnabled.get()) {
            flowAge = -1;
            MOTES.clear();
            return;
        }
        if (flowAge >= 0) {
            if (++flowAge > FADE_IN + HOLD + FADE_OUT) {
                flowAge = -1;
            }
        } else if (--nextFlowIn <= 0) {
            flowAge = 0;
            nextFlowIn = 2400 + RANDOM.nextInt(2400);
        }
        tickMotes(player);
        if (DEV_LOG && ticks % 100 == 0) {
            OvergrownVegetation.LOGGER.info("LeyFlow チャンク {}・マス {}・範囲 {}・地面あり {}(高さ {})・描いた板 {}・流れ {}・粒 {}", CHUNKS.size(), CELLS.size(),
                    lastInRange, lastGrounded, lastGroundY, lastDrawn, flowAge, MOTES.size());
        }
    }

    /** Claude のテストクライアント(-Dov.devbridge=true)だけ、5 秒ごとに数をログへ出す。 */
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");
    private static int lastDrawn;
    private static int lastInRange;
    private static int lastGrounded;
    private static float lastGroundY;

    private static void tickMotes(LocalPlayer player) {
        for (Iterator<Mote> it = MOTES.iterator(); it.hasNext(); ) {
            Mote m = it.next();
            if (++m.age >= m.life) {
                it.remove();
                continue;
            }
            m.x += m.vx;
            m.z += m.vz;
            m.y += 0.005;
        }
        float e = envelope(0.0F);
        if (e <= 0.05F || CHUNKS.isEmpty()) {
            return;
        }
        // 流れている間、近くの線の中ほどのマスの帯の高さに粒を湧かせる(1 tick に数個)。帯の形をまだ作っていないマス(描く範囲の外)は飛ばす。
        float lift = lift(0.0F);
        for (int tries = 0; tries < 6 && MOTES.size() < MAX_MOTES; tries++) {
            Cell c = randomNearCell(player);
            if (c == null || c.strength < CORE_STRENGTH || c.shapeGen < 0 || !c.overgrown || Float.isNaN(c.ground) || Float.isInfinite(c.ground)) {
                continue;
            }
            if (RANDOM.nextFloat() > e * c.strength * 0.6F) {
                continue;
            }
            double speed = (2.0 + RANDOM.nextDouble() * 2.0) / 20.0;
            double along = (RANDOM.nextDouble() - 0.5) * 1.5;
            double side = (RANDOM.nextDouble() - 0.5) * 0.6;
            MOTES.add(new Mote(c.centerX + c.tanX * along - c.tanZ * side,
                    c.base + lift + (RANDOM.nextDouble() - 0.5) * 0.7,
                    c.centerZ + c.tanZ * along + c.tanX * side,
                    c.tanX * speed, c.tanZ * speed, 40 + RANDOM.nextInt(40), 0.04F + RANDOM.nextFloat() * 0.05F));
        }
    }

    private static Cell randomNearCell(LocalPlayer player) {
        int pcx = player.chunkPosition().x + RANDOM.nextInt(5) - 2;
        int pcz = player.chunkPosition().z + RANDOM.nextInt(5) - 2;
        Cell[] cells = CHUNKS.get(ChunkPos.asLong(pcx, pcz));
        if (cells == null || cells.length == 0) {
            return null;
        }
        return cells[RANDOM.nextInt(cells.length)];
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || broken) {
            return;
        }
        if (flowAge < 0 && MOTES.isEmpty()) {
            return;
        }
        boolean main = CanopyLight.drawToMainTarget();  // ファビュラスでは加算の光を粒の層でなく主画面へ(CanopyLight#drawToMainTarget)
        try {
            render(event);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("地脈の流れの描画で例外が出たので、このセッションでは描かない", e);
        } finally {
            CanopyLight.restoreTarget(main);
        }
    }

    /**
     * マスの地面と下草のてっぺんを(まだなら・目の高さが離れたら)調べる。地面は上から下へ、空気・草・葉・<b>木の幹</b>を通り抜けた
     * 最初の固いブロックの上面(幹を通り抜けないと、木の立つマスで幹のてっぺんを地面と取り違え、帯がそこへ跳ね上がる)。水なら無し。
     * 下草のてっぺんは、地面のすぐ上から空気までの草・葉(茂み)の上端(3 ブロックまで。幹・石のような固いブロックで止める)。
     */
    private static void ensureGround(ClientLevel level, Cell cell, int refY) {
        if (!Float.isNaN(cell.ground) && Math.abs(cell.groundRef - refY) <= 16) {
            return;
        }
        cell.groundRef = refY;
        cell.ground = Float.NEGATIVE_INFINITY;
        cell.overgrown = false;
        int top = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, cell.x, cell.z) - 1, refY + 40);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(cell.x, top, cell.z);
        for (int y = top; y > top - 64; y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) {
                return; // 水面・水の中には浮かべない
            }
            if (state.isAir() || !state.canOcclude() || TreeCanopyGroundFinder.isTreePart(state)) {
                continue;
            }
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                continue;
            }
            cell.ground = y + (float) shape.max(Direction.Axis.Y);
            break;
        }
        if (Float.isInfinite(cell.ground)) {
            return;
        }
        int above = Mth.floor(cell.ground - 0.01F) + 1;
        int plantTop = above;
        for (int y = above; y < above + 3; y++) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.canOcclude() || !state.getFluidState().isEmpty()) {
                break;
            }
            plantTop = y + 1;
        }
        cell.plantTop = Math.max(cell.ground, plantTop);
        cell.overgrown = ModBiomes.isOvergrownLike(level.getBiome(BlockPos.containing(cell.x, cell.ground, cell.z)));
    }

    /**
     * 帯の置き場所・向き・高さの元を作る。半径 {@link #SHAPE_RADIUS} の線のマス(サーバーの向きがほぼ同じもの。網目の継ぎ目で別の線を混ぜない)を、
     * 強さの 2 乗で重み付けして:
     * <ul>
     *     <li>重心 = 置き場所。強さは線の中心からの距離で左右対称に下がるので、重心は線の中心に乗る。</li>
     *     <li>並びのいちばん長い向き(重み付きの散らばりの主軸)= 線の向き。<b>サーバーの向きは使わない</b>: あれは歪めた座標の上の向きで、
     *     地面の上の線とは何十度もずれる所がある(2026-10-16、帯の板が線に斜めに並んで点線になった)。符号だけサーバーの向きに合わせる
     *     (流れの向きを保つ)。帯は幅 5 なので、半径 3 の中の並びは横 ±0.8・縦 ±1.7 ほどの細長さになり、主軸ははっきり決まる。</li>
     *     <li>線に沿った座標の 1 ブロックあたりの増え方(重み付きの最小二乗。波打ちの位相を隣の板とそろえるため)。</li>
     *     <li>帯の高さ: マスごとの浮かせる高さに平面を当てはめた、重心での高さと線の向きの傾き。</li>
     * </ul>
     */
    private static void ensureShape(ClientLevel level, Cell cell, int refY) {
        if (cell.shapeGen == generation && Math.abs(cell.shapeRef - refY) <= 16) {
            return;
        }
        int count = 0;
        double sw = 0.0;
        double sx = 0.0;
        double sz = 0.0;
        double sa = 0.0;
        double hw = 0.0;
        double hx = 0.0;
        double hz = 0.0;
        double hy = 0.0;
        for (int dz = -SHAPE_RADIUS; dz <= SHAPE_RADIUS; dz++) {
            for (int dx = -SHAPE_RADIUS; dx <= SHAPE_RADIUS; dx++) {
                if (dx * dx + dz * dz > SHAPE_RADIUS * SHAPE_RADIUS) {
                    continue;
                }
                Cell n = CELLS.get(BlockPos.asLong(cell.x + dx, 0, cell.z + dz));
                if (n == null || n.dirX * cell.dirX + n.dirZ * cell.dirZ < 0.7F) {
                    continue;
                }
                double w = n.strength * n.strength;
                NX[count] = n.x + 0.5;
                NZ[count] = n.z + 0.5;
                NA[count] = n.along;
                NW[count] = w;
                count++;
                sw += w;
                sx += w * (n.x + 0.5);
                sz += w * (n.z + 0.5);
                sa += w * n.along;
                ensureGround(level, n, refY);
                if (Float.isInfinite(n.ground)) {
                    NH[count - 1] = Double.NaN;
                } else {
                    double h = Math.max(n.ground + HOVER, n.plantTop + HOVER_PLANTS);
                    NH[count - 1] = h;
                    hw += w;
                    hx += w * (n.x + 0.5);
                    hz += w * (n.z + 0.5);
                    hy += w * h;
                }
            }
        }
        cell.centerX = sw > 0.0 ? sx / sw : cell.x + 0.5;
        cell.centerZ = sw > 0.0 ? sz / sw : cell.z + 0.5;
        cell.centerAlong = sw > 0.0 ? sa / sw : cell.along;
        // 重み付きの散らばり(共分散)と、線に沿った座標との共分散。
        double cxx = 0.0;
        double cxz = 0.0;
        double czz = 0.0;
        double cxa = 0.0;
        double cza = 0.0;
        for (int i = 0; i < count; i++) {
            double ox = NX[i] - cell.centerX;
            double oz = NZ[i] - cell.centerZ;
            double oa = NA[i] - cell.centerAlong;
            cxx += NW[i] * ox * ox;
            cxz += NW[i] * ox * oz;
            czz += NW[i] * oz * oz;
            cxa += NW[i] * ox * oa;
            cza += NW[i] * oz * oa;
        }
        double tx = cell.dirX;
        double tz = cell.dirZ;
        if (count >= 4) {
            double angle = 0.5 * Math.atan2(2.0 * cxz, cxx - czz);
            tx = Math.cos(angle);
            tz = Math.sin(angle);
            if (tx * cell.dirX + tz * cell.dirZ < 0.0) {
                tx = -tx;
                tz = -tz;
            }
        }
        cell.tanX = (float) tx;
        cell.tanZ = (float) tz;
        // 沿った座標の傾き g を [cxx cxz; cxz czz] g = [cxa; cza] で解き、線の向きの成分(1 ブロック進むと何増えるか)を取る。
        double det = cxx * czz - cxz * cxz;
        double rate = 1.0;
        if (count >= 4 && Math.abs(det) > 1.0e-6) {
            double gx = (czz * cxa - cxz * cza) / det;
            double gz = (cxx * cza - cxz * cxa) / det;
            rate = Mth.clamp(gx * tx + gz * tz, 0.2, 3.0);
        }
        cell.alongRate = (float) rate;
        // 帯の高さ: マスごとの「地面 + HOVER と下草のてっぺん + HOVER_PLANTS の高い方」に平面を当てはめ、重心での高さと線の向きの傾きを取る。
        // 板を水平にして高さだけ合わせると、坂では隣の板と段々にずれて途切れた短い板の列に見えた(2026-10-16、森の丘の線)。
        cell.base = cell.ground + (float) HOVER;
        cell.slope = 0.0F;
        if (hw > 0.0) {
            double mx = hx / hw;
            double mz = hz / hw;
            double my = hy / hw;
            double qxx = 0.0;
            double qxz = 0.0;
            double qzz = 0.0;
            double qxy = 0.0;
            double qzy = 0.0;
            int valid = 0;
            for (int i = 0; i < count; i++) {
                if (Double.isNaN(NH[i])) {
                    continue;
                }
                valid++;
                double ox = NX[i] - mx;
                double oz = NZ[i] - mz;
                double oy = NH[i] - my;
                qxx += NW[i] * ox * ox;
                qxz += NW[i] * ox * oz;
                qzz += NW[i] * oz * oz;
                qxy += NW[i] * ox * oy;
                qzy += NW[i] * oz * oy;
            }
            double qdet = qxx * qzz - qxz * qxz;
            double gx = 0.0;
            double gz = 0.0;
            if (valid >= 4 && Math.abs(qdet) > 1.0e-6) {
                gx = (qzz * qxy - qxz * qzy) / qdet;
                gz = (qxx * qzy - qxz * qxy) / qdet;
            }
            cell.base = (float) (my + gx * (cell.centerX - mx) + gz * (cell.centerZ - mz));
            cell.slope = (float) Mth.clamp(gx * tx + gz * tz, -1.5, 1.5);
        }
        cell.shapeGen = generation;
        cell.shapeRef = refY;
    }

    private static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || level != flowLevel) {
            return;
        }
        Camera camera = event.getCamera();
        if (camera.getFluidInCamera() != FogType.NONE) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float e = envelope(partialTick);
        Vec3 eye = camera.getPosition();
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        MultiBufferSource.BufferSource buffers = CanopyLight.immediateBuffers();  // Iris のまとめ描きを避ける(CanopyLight#immediateBuffers、2026-10-22)
        VertexConsumer c = buffers.getBuffer(CanopyLight.renderType());
        if (e > 0.003F) {
            double seconds = (ticks + partialTick) / 20.0;
            float lift = lift(partialTick);
            int refY = Mth.floor(eye.y);
            lastInRange = 0;
            lastGrounded = 0;
            lastDrawn = 0;
            int ecx = Mth.floor(eye.x) >> 4;
            int ecz = Mth.floor(eye.z) >> 4;
            int chunkRange = (int) Math.ceil(RANGE / 16.0) + 1;
            for (int dz = -chunkRange; dz <= chunkRange; dz++) {
                for (int dx = -chunkRange; dx <= chunkRange; dx++) {
                    Cell[] cells = CHUNKS.get(ChunkPos.asLong(ecx + dx, ecz + dz));
                    if (cells == null) {
                        continue;
                    }
                    for (Cell cell : cells) {
                        if (cell.strength < CORE_STRENGTH) {
                            continue;
                        }
                        double hx = cell.x + 0.5 - eye.x;
                        double hz = cell.z + 0.5 - eye.z;
                        double dist = Math.sqrt(hx * hx + hz * hz);
                        if (dist > RANGE) {
                            continue;
                        }
                        lastInRange++;
                        ensureGround(level, cell, refY);
                        if (Float.isInfinite(cell.ground)) {
                            continue;
                        }
                        lastGrounded++;
                        lastGroundY = cell.ground;
                        if (!cell.overgrown) {
                            continue;
                        }
                        ensureShape(level, cell, refY);
                        double phase = (cell.centerAlong - seconds * PULSE_SPEED) / PULSE_PERIOD * Math.PI * 2.0;
                        double pulse = Math.pow(0.5 + 0.5 * Math.cos(phase), 2.0);
                        float weight = (cell.strength - CORE_STRENGTH) / (1.0F - CORE_STRENGTH);
                        float rangeFade = 1.0F - CanopyLight.smooth(Mth.clamp((float) ((dist - RANGE * 0.7) / (RANGE * 0.3)), 0.0F, 1.0F));
                        float fogFade = 1.0F - CanopyLight.smooth(Mth.clamp((float) ((dist - fogStart) / Math.max(1.0F, fogEnd - fogStart)), 0.0F, 1.0F));
                        float alpha = (float) (BAND_ALPHA * weight * (0.3 + 0.7 * pulse)) * e * rangeFade * fogFade;
                        if (alpha <= 0.002F) {
                            continue;
                        }
                        if (segment(c, cell, eye, seconds, lift, alpha)) {
                            lastDrawn++;
                        }
                    }
                }
            }
        }
        // 線に沿って流れる粒。
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        for (Mote m : MOTES) {
            float life = Math.min(1.0F, m.age / 10.0F) * Math.min(1.0F, (m.life - m.age) / 15.0F);
            float glow = 0.8F * life * Math.max(e, 0.3F);
            double px = Mth.lerp(partialTick, m.x - m.vx, m.x) - eye.x;
            double py = m.y - eye.y;
            double pz = Mth.lerp(partialTick, m.z - m.vz, m.z) - eye.z;
            if (glow <= 0.01F || px * px + py * py + pz * pz > RANGE * RANGE) {
                continue;
            }
            mote(c, (float) px, (float) py, (float) pz, m.size, left, up, glow);
        }
        buffers.endBatch(CanopyLight.renderType());
    }

    /**
     * 1 マスの帯の板: 重心から線の向きへ前後 {@link #SEG_HALF}(両端の濃さ 0)、幅は {@link #ROW_V} の 5 段(上下の縁が 0)。
     * 波打ち・ねじれは線に沿った座標と時刻だけで決めるので、重なる隣の板と形がそろう。目の前 1〜4 ブロックは薄くする。描いたら true。
     */
    private static boolean segment(VertexConsumer c, Cell cell, Vec3 eye, double seconds, float lift, float alpha) {
        double sideX = -cell.tanZ;
        double sideZ = cell.tanX;
        double midY = cell.base + lift;
        // 幅の向き = 線の向き × 目への向き(どこから見ても面が見える)。線を見通す向きでは外積が小さくなるので、縦へ寄せる。
        double vx = eye.x - cell.centerX;
        double vy = eye.y - midY;
        double vz = eye.z - cell.centerZ;
        double vLen = Math.sqrt(vx * vx + vy * vy + vz * vz);
        float near = CanopyLight.smooth(Mth.clamp((float) ((vLen - 1.0) / 3.0), 0.0F, 1.0F));
        alpha *= near;
        if (alpha <= 0.002F) {
            return false;
        }
        vx /= vLen;
        vy /= vLen;
        vz /= vLen;
        double nx = -cell.tanZ * vy;
        double ny = cell.tanZ * vx - cell.tanX * vz;
        double nz = cell.tanX * vy;
        if (ny < 0.0) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        double nLen = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nLen < 0.3) {
            ny += (0.3 - nLen) / 0.3;
        }
        nLen = Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= nLen;
        ny /= nLen;
        nz /= nLen;
        // 3 列(後ろの端・中ほど・前の端)× 5 段の頂点(描画のスレッドだけで使う使い回しの配列)。
        float[] px = PX;
        float[] py = PY;
        float[] pz = PZ;
        float[] pa = PA;
        for (int col = 0; col < 3; col++) {
            double u = (col - 1) * SEG_HALF;
            double along = cell.centerAlong + u * cell.alongRate;
            double sway = SWAY * Math.sin(along * SWAY_K - seconds * SWAY_W + 1.3);
            double bob = BOB * Math.sin(along * BOB_K - seconds * BOB_W);
            double half = HALF_WIDTH * (0.75 + 0.25 * Math.cos(along * TWIST_K - seconds * TWIST_W + 0.5));
            double cx = cell.centerX + cell.tanX * u + sideX * sway;
            double cy = midY + cell.slope * u + bob;
            double cz = cell.centerZ + cell.tanZ * u + sideZ * sway;
            for (int row = 0; row < 5; row++) {
                int i = col * 5 + row;
                double v = ROW_V[row] * half;
                px[i] = (float) (cx + nx * v - eye.x);
                py[i] = (float) (cy + ny * v - eye.y);
                pz[i] = (float) (cz + nz * v - eye.z);
                pa[i] = col == 1 ? alpha * ROW_A[row] : 0.0F;
            }
        }
        for (int col = 0; col < 2; col++) {
            for (int row = 0; row < 4; row++) {
                int a = col * 5 + row;
                int b = (col + 1) * 5 + row;
                vertex(c, px, py, pz, pa, a);
                vertex(c, px, py, pz, pa, b);
                vertex(c, px, py, pz, pa, b + 1);
                vertex(c, px, py, pz, pa, a + 1);
            }
        }
        return true;
    }

    private static void vertex(VertexConsumer c, float[] px, float[] py, float[] pz, float[] pa, int i) {
        c.addVertex(px[i], py[i], pz[i]).setColor(R, G, B, pa[i]);
    }

    /** 光の粒 1 つ(カメラを向くぼやけた丸。中心が明るく縁が 0 の 8 枚の三角)。 */
    private static void mote(VertexConsumer c, float x, float y, float z, float size, Vector3f left, Vector3f up, float alpha) {
        for (int i = 0; i < 8; i++) {
            float a0 = i * Mth.TWO_PI / 8.0F;
            float a1 = (i + 1) * Mth.TWO_PI / 8.0F;
            float px = (left.x() * Mth.cos(a0) + up.x() * Mth.sin(a0)) * size;
            float py = (left.y() * Mth.cos(a0) + up.y() * Mth.sin(a0)) * size;
            float pz = (left.z() * Mth.cos(a0) + up.z() * Mth.sin(a0)) * size;
            float qx = (left.x() * Mth.cos(a1) + up.x() * Mth.sin(a1)) * size;
            float qy = (left.y() * Mth.cos(a1) + up.y() * Mth.sin(a1)) * size;
            float qz = (left.z() * Mth.cos(a1) + up.z() * Mth.sin(a1)) * size;
            c.addVertex(x, y, z).setColor(R, G, B, alpha);
            c.addVertex(x + px, y + py, z + pz).setColor(R, G, B, 0.0F);
            c.addVertex(x + qx, y + qy, z + qz).setColor(R, G, B, 0.0F);
            c.addVertex(x, y, z).setColor(R, G, B, alpha);
        }
    }

    private LeyFlow() {
    }
}
