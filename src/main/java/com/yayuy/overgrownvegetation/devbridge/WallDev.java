package com.yayuy.overgrownvegetation.devbridge;

import com.yayuy.overgrownvegetation.client.wall.BoxCreepingWallRenderer;
import com.yayuy.overgrownvegetation.client.wall.CreepingWallRendering;
import com.yayuy.overgrownvegetation.client.wall.TorrentCreepingWallRenderer;

import java.util.function.Consumer;

/**
 * 蠢く壁の見た目の案を切り替える(2026-11-16、見た目の試作の撮り比べ用。配布 jar には入らない)。
 * {@code wallstyle a|b|c|box}。a = 根の濁流・b = 飲まれた者たち・c = 胞子嚢と光・box = 仮の箱(既定)。返事は {@code WALLSTYLE …}。
 */
final class WallDev {

    private static TorrentCreepingWallRenderer torrent;

    private WallDev() {
    }

    /**
     * {@code wallstyle caves [半径]}: 画面の側に読み込まれた地形から、撮る場所の候補を探す。床の升(下が固く、上 2 升が空き、空が見えない)を
     * 4 ブロックおきに調べ、そこから 12 ブロックまで空洞をたどって分ける: 狭い通路(たどれた升が少なく、距離 10 の殻が 2 つに分かれる)・
     * 分岐(殻が 3 つ以上)・広い空洞(升が多い)。返事は {@code WALLCAVE 種類 x y z 升 殻}。
     */
    private static void caves(String[] a, Consumer<String> log) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            log.accept("WALLCAVE ワールドの中で");
            return;
        }
        net.minecraft.client.multiplayer.ClientLevel level = mc.level;
        int radius = a.length > 1 ? Integer.parseInt(a[1]) : 96;
        net.minecraft.core.BlockPos c = mc.player.blockPosition();
        net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
        java.util.List<int[]> found = new java.util.ArrayList<>();
        for (int x = c.getX() - radius; x <= c.getX() + radius; x += 4) {
            for (int z = c.getZ() - radius; z <= c.getZ() + radius; z += 4) {
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) - 10;
                for (int y = Math.max(level.getMinBuildHeight() + 6, -50); y < top; y++) {
                    if (!open(level, m.set(x, y, z)) || !open(level, m.set(x, y + 1, z)) || open(level, m.set(x, y - 1, z))
                            || !level.getFluidState(m.set(x, y - 1, z)).isEmpty()) {
                        continue;
                    }
                    int[] r = probe(level, x, y, z);
                    if (r != null) {
                        found.add(new int[]{r[0], x, y, z, r[1], r[2]});
                    }
                }
            }
        }
        String[] names = {"通路", "分岐", "空洞"};
        for (int kind = 0; kind < 3; kind++) {
            final int k = kind;
            // 通路は升の少ない順(狭い)、分岐は殻の多い順、空洞は升の多い順
            java.util.List<int[]> list = new java.util.ArrayList<>(found.stream().filter(r -> r[0] == k).toList());
            list.sort((p, q) -> k == 0 ? p[4] - q[4] : k == 1 ? q[5] - p[5] : q[4] - p[4]);
            java.util.List<int[]> shown = new java.util.ArrayList<>();
            for (int[] r : list) {
                boolean far = true;
                for (int[] s : shown) {
                    if (Math.abs(s[1] - r[1]) + Math.abs(s[2] - r[2]) + Math.abs(s[3] - r[3]) < 24) {
                        far = false;
                        break;
                    }
                }
                if (far) {
                    shown.add(r);
                    log.accept("WALLCAVE " + names[k] + " " + r[1] + " " + r[2] + " " + r[3] + " 升 " + r[4] + " 殻 " + r[5]);
                    if (shown.size() >= 6) {
                        break;
                    }
                }
            }
        }
        log.accept("WALLCAVE 終わり 候補 " + found.size());
    }

    private static boolean open(net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.core.BlockPos p) {
        net.minecraft.world.level.block.state.BlockState s = level.getBlockState(p);
        return (s.isAir() || !s.isSolidRender(level, p)) && s.getFluidState().isEmpty();
    }

    /** 12 ブロックまでたどる。返すのは {種類 0 通路・1 分岐・2 空洞, 升の数, 距離 10〜11 の殻のかたまりの数}。行き止まり・小部屋は null。 */
    private static int[] probe(net.minecraft.client.multiplayer.ClientLevel level, int x, int y, int z) {
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap dist = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        dist.defaultReturnValue(-1);
        it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue q = new it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue();
        long start = net.minecraft.core.BlockPos.asLong(x, y, z);
        dist.put(start, 0);
        q.enqueue(start);
        net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
        it.unimi.dsi.fastutil.longs.LongOpenHashSet shell = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        while (!q.isEmpty()) {
            long p = q.dequeueLong();
            int d = dist.get(p);
            if (d >= 10) {
                shell.add(p);
            }
            if (d >= 11) {
                continue;
            }
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                m.set(net.minecraft.core.BlockPos.getX(p) + dir.getStepX(), net.minecraft.core.BlockPos.getY(p) + dir.getStepY(),
                        net.minecraft.core.BlockPos.getZ(p) + dir.getStepZ());
                long n = m.asLong();
                if (dist.containsKey(n) || !open(level, m)) {
                    continue;
                }
                if (level.canSeeSky(m)) {
                    return null; // 空の見える所につながる
                }
                dist.put(n, d + 1);
                q.enqueue(n);
            }
            if (dist.size() > 4000) {
                return new int[]{2, dist.size(), 0};
            }
        }
        // 殻のかたまりを数える
        int comps = 0;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (long s : shell) {
            if (!seen.add(s)) {
                continue;
            }
            comps++;
            q.enqueue(s);
            while (!q.isEmpty()) {
                long p = q.dequeueLong();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long n = net.minecraft.core.BlockPos.asLong(net.minecraft.core.BlockPos.getX(p) + dx, net.minecraft.core.BlockPos.getY(p) + dy,
                                    net.minecraft.core.BlockPos.getZ(p) + dz);
                            if (shell.contains(n) && seen.add(n)) {
                                q.enqueue(n);
                            }
                        }
                    }
                }
            }
        }
        if (comps < 2) {
            return null;
        }
        if (dist.size() > 2200) {
            return new int[]{2, dist.size(), comps};
        }
        if (comps >= 3) {
            return new int[]{1, dist.size(), comps};
        }
        return dist.size() < 420 ? new int[]{0, dist.size(), comps} : null;
    }

    static void run(String arg, Consumer<String> log) {
        String a = arg.trim();
        if (a.startsWith("ray")) {
            // wallstyle ray [最大]: 視線を 0.25 ずつたどり、最初に当たる固いブロックと、その手前の空いた升を返す(壁を起こす座標決め)
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return;
            }
            String[] s = a.split("\\s+");
            double max = s.length > 1 ? Double.parseDouble(s[1]) : 48.0D;
            net.minecraft.world.phys.Vec3 eye = mc.player.getEyePosition(), dir = mc.player.getLookAngle();
            net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
            net.minecraft.core.BlockPos last = null;
            for (double t = 0.5D; t <= max; t += 0.25D) {
                m.set(net.minecraft.util.Mth.floor(eye.x + dir.x * t), net.minecraft.util.Mth.floor(eye.y + dir.y * t), net.minecraft.util.Mth.floor(eye.z + dir.z * t));
                if (!open(mc.level, m)) {
                    log.accept("WALLRAY 当たり 距離 " + String.format("%.1f", t) + " " + m.getX() + " " + m.getY() + " " + m.getZ() + " "
                            + mc.level.getBlockState(m).getBlock() + " 手前の空き " + (last == null ? "なし" : last.getX() + " " + last.getY() + " " + last.getZ()));
                    return;
                }
                last = m.immutable();
            }
            log.accept("WALLRAY 当たらない 最後の空き " + (last == null ? "なし" : last.getX() + " " + last.getY() + " " + last.getZ()));
            return;
        }
        if (a.startsWith("caves")) {
            caves(a.split("\\s+"), log);
            return;
        }
        if (a.equals("box")) {
            CreepingWallRendering.renderer = new BoxCreepingWallRenderer();
            log.accept("WALLSTYLE box");
            return;
        }
        TorrentCreepingWallRenderer.Style s = switch (a) {
            case "a" -> TorrentCreepingWallRenderer.STYLE_A;
            case "b" -> TorrentCreepingWallRenderer.STYLE_B;
            case "c" -> TorrentCreepingWallRenderer.STYLE_C;
            default -> null;
        };
        if (s == null) {
            log.accept("WALLSTYLE 使い方: wallstyle a|b|c|box(今 " + (CreepingWallRendering.renderer instanceof TorrentCreepingWallRenderer
                    ? TorrentCreepingWallRenderer.style.name() : "box") + "・直前のフレーム " + String.format("%.3f", TorrentCreepingWallRenderer.lastNanos / 1e6)
                    + " ms・面 " + TorrentCreepingWallRenderer.lastFaces + "(近く " + TorrentCreepingWallRenderer.lastNear + "))");
            return;
        }
        if (torrent == null) {
            torrent = new TorrentCreepingWallRenderer();
        }
        TorrentCreepingWallRenderer.style = s;
        CreepingWallRendering.renderer = torrent;
        log.accept("WALLSTYLE " + s.name());
    }
}
