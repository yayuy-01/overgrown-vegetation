package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 洞窟の<b>胞子の溜まり</b>(2026-11-03 地下の植生 2・U6。下絵 sheet3 の 7)。サーバーが窪みの床に置いた見えない印 {@code cave_spore_pool} を
 * カメラのまわりで探し、その窪みの床(印と同じ高さで、下が丈夫な空きのマス。印から {@link #REACH} 以内)の上に、床から 1〜2 ブロックの
 * 濃い緑の霧の層を重ねた円盤で描き、胞子の粒をゆっくり上下させる。上の層は {@link #PULSE_SECONDS} 秒の周期で黄緑へかすかに明滅する。
 * 光のブロックは置かない(描画だけ・湧きにも明るさにも効かない)。色は下絵の 4 色、数はすべて仮決め。
 */
final class SporePools {

    /** 印を探す半径(ブロック)と、探し直す間隔(tick)。 */
    private static final int SCAN_RADIUS = 32;
    private static final int SCAN_EVERY = 40;
    /** 窪みの床をたどる半径(サーバーの {@code CaveFlora.POOL_REACH} と同じ)と 1 つの溜まりのマスの上限。 */
    private static final int REACH = 6;
    private static final int MAX_CELLS = 120;
    /** これより遠い溜まりは描かない。 */
    private static final double DRAW_DISTANCE = 40.0;
    private static final float PULSE_SECONDS = 8.0F;
    /** 色(下絵): 外側 #1c2405・基本 #2a3608・濃い所 #3a4a0c・光る頂 #4f6312。 */
    private static final float[] OUTER = rgb(0x1c2405);
    private static final float[] BASE = rgb(0x2a3608);
    private static final float[] DENSE = rgb(0x3a4a0c);
    private static final float[] GLOW = rgb(0x4f6312);
    /** 粒の色(光る頂を少し明るく)。 */
    private static final float[] MOTE = rgb(0x6f8a1a);

    private record Pool(BlockPos marker, List<BlockPos> cells) {
    }

    private static final List<Pool> POOLS = new ArrayList<>();

    static void clear() {
        POOLS.clear();
    }

    static String describe() {
        return "胞子の溜まり " + POOLS.size();
    }

    static void tick(ClientLevel level, LocalPlayer player, long ticks) {
        if (ticks % SCAN_EVERY != 0) {
            return;
        }
        POOLS.clear();
        for (BlockPos marker : scan(level, player.blockPosition())) {
            POOLS.add(new Pool(marker, floorCells(level, marker)));
        }
    }

    /** カメラのまわりの区画から印を集める(パレットに印が無い区画は中を読まない)。 */
    private static List<BlockPos> scan(ClientLevel level, BlockPos center) {
        List<BlockPos> out = new ArrayList<>();
        int minSx = SectionPos.blockToSectionCoord(center.getX() - SCAN_RADIUS);
        int maxSx = SectionPos.blockToSectionCoord(center.getX() + SCAN_RADIUS);
        int minSz = SectionPos.blockToSectionCoord(center.getZ() - SCAN_RADIUS);
        int maxSz = SectionPos.blockToSectionCoord(center.getZ() + SCAN_RADIUS);
        int minSy = Math.max(level.getMinSection(), SectionPos.blockToSectionCoord(center.getY() - SCAN_RADIUS / 2));
        int maxSy = Math.min(level.getMaxSection() - 1, SectionPos.blockToSectionCoord(center.getY() + SCAN_RADIUS / 2));
        for (int sx = minSx; sx <= maxSx; sx++) {
            for (int sz = minSz; sz <= maxSz; sz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int sy = minSy; sy <= maxSy; sy++) {
                    LevelChunkSection section = sections[level.getSectionIndexFromSectionY(sy)];
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(SporePools::isMarker)) {
                        continue;
                    }
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (isMarker(section.getBlockState(x, y, z))) {
                                    out.add(new BlockPos(SectionPos.sectionToBlockCoord(sx) + x, SectionPos.sectionToBlockCoord(sy) + y,
                                            SectionPos.sectionToBlockCoord(sz) + z));
                                }
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static boolean isMarker(BlockState s) {
        return s.is(ModBlocks.CAVE_SPORE_POOL.get());
    }

    /** 印から同じ高さの床を 4 方向へ塗る(空き・水なし・下が丈夫。印から水平 {@link #REACH} 以内)。 */
    private static List<BlockPos> floorCells(ClientLevel level, BlockPos marker) {
        List<BlockPos> cells = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(marker);
        seen.add(marker);
        while (!queue.isEmpty() && cells.size() < MAX_CELLS) {
            BlockPos p = queue.poll();
            cells.add(p);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(d);
                if (Math.abs(n.getX() - marker.getX()) > REACH || Math.abs(n.getZ() - marker.getZ()) > REACH || !seen.add(n)) {
                    continue;
                }
                BlockState s = level.getBlockState(n);
                BlockPos below = n.below();
                if ((s.isAir() || !s.blocksMotion()) && level.getFluidState(n).isEmpty()
                        && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                    queue.add(n);
                }
            }
        }
        return cells;
    }

    /** {@link LifeSprites} の普通の重ね(足し算でない)の間に描く。 */
    static void render(ClientLevel level, float partialTick, Vector3f left, Vector3f up, double camX, double camY, double camZ) {
        if (POOLS.isEmpty()) {
            return;
        }
        float t = (level.getGameTime() + partialTick) / 20.0F;
        for (Pool pool : POOLS) {
            BlockPos m = pool.marker;
            double dx = m.getX() + 0.5 - camX;
            double dz = m.getZ() + 0.5 - camZ;
            if (dx * dx + dz * dz > DRAW_DISTANCE * DRAW_DISTANCE || Math.abs(m.getY() - camY) > DRAW_DISTANCE) {
                continue;
            }
            float seed = (m.getX() * 31 + m.getZ() * 17) * 0.13F;
            float pulse = 0.5F + 0.5F * Mth.sin(t * Mth.TWO_PI / PULSE_SECONDS + seed);
            double floor = m.getY();
            for (BlockPos c : pool.cells) {
                double cx = c.getX() + 0.5;
                double cz = c.getZ() + 0.5;
                float edge = (float) Math.min(1.0, Math.sqrt(sq(c.getX() - m.getX()) + sq(c.getZ() - m.getZ())) / (REACH + 0.5));
                float cellSeed = (c.getX() * 7 + c.getZ() * 13) * 0.37F;
                // 床すれすれの濃い層 → 基本の層 → 上の縁(波打ち、明滅)。縁ほど薄い。
                LifeSprites.disc(cx, floor + 0.12, cz, 0.95F,
                        DENSE[0], DENSE[1], DENSE[2], 0.42F * (1.0F - edge * 0.5F));
                LifeSprites.disc(cx, floor + 0.65, cz, 1.0F,
                        BASE[0], BASE[1], BASE[2], 0.28F * (1.0F - edge * 0.6F));
                double top = floor + 1.2 + 0.25 * Math.sin(t * 0.7 + cellSeed);
                float glow = 0.35F * pulse;
                LifeSprites.disc(cx, top, cz, 1.05F,
                        Mth.lerp(glow, OUTER[0], GLOW[0]), Mth.lerp(glow, OUTER[1], GLOW[1]), Mth.lerp(glow, OUTER[2], GLOW[2]),
                        0.16F * (1.0F - edge * 0.7F));
                // 胞子の粒: マスに 1 つ、ゆっくり上下
                double my = floor + 0.3 + 1.2 * (0.5 + 0.5 * Math.sin(t * 0.25 + cellSeed * 3.0));
                double mx = cx + 0.3 * Math.sin(t * 0.13 + cellSeed);
                double mz = cz + 0.3 * Math.cos(t * 0.11 + cellSeed * 2.0);
                LifeSprites.blob(mx, my, mz, 0.05F, 1.0F, left, up, MOTE[0], MOTE[1], MOTE[2], 0.35F + 0.25F * pulse);
            }
        }
    }

    private static double sq(int v) {
        return (double) v * v;
    }

    private static float[] rgb(int c) {
        return new float[]{(c >> 16 & 255) / 255.0F, (c >> 8 & 255) / 255.0F, (c & 255) / 255.0F};
    }

    private SporePools() {
    }
}
