package com.yayuy.overgrownvegetation.worldgen.grammar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ブロック集積(セル → 最終 blockstate)と形状スタンプ。ブラウザ側 {@code src/grid.js} と一致。
 * SPEC §7・§9。Minecraft 非依存(state は正規化文字列のまま持つ)。
 */
final class GrammarGrid {

    static final class Cell {
        final int x;
        final int y;
        final int z;
        String state;
        boolean prot;

        Cell(int x, int y, int z, String state, boolean prot) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.state = state;
            this.prot = prot;
        }
    }

    final Map<Long, Cell> cells = new HashMap<>();
    private final int limit;
    boolean truncated;

    /** 直近の stamp/scatter が触れたセル(decay の match 省略時の対象)。 */
    java.util.Set<Long> recording;

    GrammarGrid(int limit) {
        this.limit = limit > 0 ? limit : 200000;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + (1 << 22)) << 44)
                | ((long) (y + (1 << 21)) << 22)
                | (long) (z + (1 << 21));
    }

    /** overwrite: "always" | "if_air" | "never" | "keep_protected"(既定) */
    void set(double dx, double dy, double dz, String state, boolean prot, String overwrite) {
        int x = (int) Math.floor(dx);
        int y = (int) Math.floor(dy);
        int z = (int) Math.floor(dz);
        long k = key(x, y, z);
        // src/engine.js は grid.set をラップして「呼ばれたら必ず」recording に足す(設置可否に関わらず)。
        if (recording != null) {
            recording.add(k);
        }
        Cell cur = cells.get(k);
        if (cur != null) {
            String ow = overwrite != null ? overwrite : "keep_protected";
            if (ow.equals("if_air") || ow.equals("never")) {
                return;
            }
            if (ow.equals("keep_protected") && cur.prot) {
                return;
            }
            cur.state = state;
            if (prot) {
                cur.prot = true;
            }
            return;
        }
        if (cells.size() >= limit) {
            truncated = true;
            return;
        }
        cells.put(k, new Cell(x, y, z, state, prot));
    }

    void remove(int x, int y, int z) {
        cells.remove(key(x, y, z));
    }

    boolean has(int x, int y, int z) {
        return cells.containsKey(key(x, y, z));
    }

    Cell get(int x, int y, int z) {
        return cells.get(key(x, y, z));
    }

    int size() {
        return cells.size();
    }

    /** SPEC §9: x昇順 → y昇順 → z昇順。 */
    List<Cell> toSortedList() {
        List<Cell> arr = new ArrayList<>(cells.values());
        arr.sort((a, b) -> {
            if (a.x != b.x) {
                return Integer.compare(a.x, b.x);
            }
            if (a.y != b.y) {
                return Integer.compare(a.y, b.y);
            }
            return Integer.compare(a.z, b.z);
        });
        return arr;
    }

    int[] bounds() {
        if (cells.isEmpty()) {
            return new int[] {0, 0, 0, 0, 0, 0};
        }
        int mnx = Integer.MAX_VALUE;
        int mny = Integer.MAX_VALUE;
        int mnz = Integer.MAX_VALUE;
        int mxx = Integer.MIN_VALUE;
        int mxy = Integer.MIN_VALUE;
        int mxz = Integer.MIN_VALUE;
        for (Cell c : cells.values()) {
            mnx = Math.min(mnx, c.x);
            mny = Math.min(mny, c.y);
            mnz = Math.min(mnz, c.z);
            mxx = Math.max(mxx, c.x);
            mxy = Math.max(mxy, c.y);
            mxz = Math.max(mxz, c.z);
        }
        return new int[] {mnx, mny, mnz, mxx, mxy, mxz};
    }

    // ---- 形状(src/grid.js の SG.shapes と一致) ----

    static final class Brush {
        GrammarGrid grid;
        double cx;
        double cy;
        double cz;
        String state;
        boolean prot;
        String overwrite;
        GrammarPrng rng;
    }

    static void sphere(Brush o, double radius) {
        int r = Math.max(0, (int) Math.round(radius));
        int cx = (int) Math.floor(o.cx);
        int cy = (int) Math.floor(o.cy);
        int cz = (int) Math.floor(o.cz);
        if (r == 0) {
            o.grid.set(cx, cy, cz, o.state, o.prot, o.overwrite);
            return;
        }
        double edge = (r + 0.35) * (r + 0.35);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz <= edge) {
                        o.grid.set(cx + dx, cy + dy, cz + dz, o.state, o.prot, o.overwrite);
                    }
                }
            }
        }
    }

    static void blob(Brush o, double rhIn, double rvIn, double jitter) {
        int h = Math.max(1, (int) Math.round(rhIn));
        int v = Math.max(1, (int) Math.round(rvIn));
        int cx = (int) Math.floor(o.cx);
        int cy = (int) Math.floor(o.cy);
        int cz = (int) Math.floor(o.cz);
        double hSq = (h + 0.5) * (h + 0.5);
        double vSq = (v + 0.5) * (v + 0.5);
        for (int dx = -h; dx <= h; dx++) {
            for (int dy = -v; dy <= v; dy++) {
                for (int dz = -h; dz <= h; dz++) {
                    double e = (dx * dx + dz * dz) / hSq + (dy * (double) dy) / vSq;
                    if (e > 1) {
                        continue;
                    }
                    if (e > 0.55 && jitter > 0) {
                        if (o.rng.nextUnit() < jitter) {
                            continue;
                        }
                    }
                    o.grid.set(cx + dx, cy + dy, cz + dz, o.state, o.prot, o.overwrite);
                }
            }
        }
    }

    static void disc(Brush o, double radius, String axis) {
        int r = Math.max(0, (int) Math.round(radius));
        int cx = (int) Math.floor(o.cx);
        int cy = (int) Math.floor(o.cy);
        int cz = (int) Math.floor(o.cz);
        double edge = (r + 0.5) * (r + 0.5);
        for (int a = -r; a <= r; a++) {
            for (int b = -r; b <= r; b++) {
                if (a * a + b * b > edge) {
                    continue;
                }
                if ("x".equals(axis)) {
                    o.grid.set(cx, cy + a, cz + b, o.state, o.prot, o.overwrite);
                } else if ("z".equals(axis)) {
                    o.grid.set(cx + a, cy + b, cz, o.state, o.prot, o.overwrite);
                } else {
                    o.grid.set(cx + a, cy, cz + b, o.state, o.prot, o.overwrite);
                }
            }
        }
    }

    static void column(Brush o, double radius, double length, String axis) {
        int len = Math.max(1, (int) Math.round(length));
        for (int i = 0; i < len; i++) {
            Brush b = new Brush();
            b.grid = o.grid;
            b.state = o.state;
            b.prot = o.prot;
            b.overwrite = o.overwrite;
            b.rng = o.rng;
            b.cx = o.cx;
            b.cy = o.cy;
            b.cz = o.cz;
            if ("x".equals(axis)) {
                b.cx = o.cx + i;
            } else if ("z".equals(axis)) {
                b.cz = o.cz + i;
            } else {
                b.cy = o.cy + i;
            }
            disc(b, radius, axis != null ? axis : "y");
        }
    }

    static void box(Brush o, double[] size, boolean hollow, int wall) {
        // src/grid.js は size をそのままループ上限に使う(丸めない)。整数前提。
        int sx = (int) size[0];
        int sy = (int) size[1];
        int sz = (int) size[2];
        int cx = (int) Math.floor(o.cx);
        int cy = (int) Math.floor(o.cy);
        int cz = (int) Math.floor(o.cz);
        int x0 = cx - (int) Math.floor((sx - 1) / 2.0);
        int y0 = cy;
        int z0 = cz - (int) Math.floor((sz - 1) / 2.0);
        int w = wall > 0 ? wall : 1;
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    if (hollow) {
                        boolean onShell = x < w || x >= sx - w || y < w || y >= sy - w || z < w || z >= sz - w;
                        if (!onShell) {
                            continue;
                        }
                    }
                    o.grid.set(x0 + x, y0 + y, z0 + z, o.state, o.prot, o.overwrite);
                }
            }
        }
    }

    static void mound(Brush o, double radius) {
        int r = Math.max(0, (int) Math.round(radius));
        int cx = (int) Math.floor(o.cx);
        int gy = (int) Math.floor(o.cy);
        int cz = (int) Math.floor(o.cz);
        if (r == 0) {
            o.grid.set(cx, gy, cz, o.state, o.prot, o.overwrite);
            o.grid.set(cx, gy - 1, cz, o.state, o.prot, o.overwrite);
            return;
        }
        double edge = (r + 0.5) * (r + 0.5);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double hs = dx * dx + dz * dz;
                if (hs > edge) {
                    continue;
                }
                int top = (int) Math.round(Math.sqrt(Math.max(0, edge - hs)));
                for (int dy = -1; dy <= top; dy++) {
                    o.grid.set(cx + dx, gy + dy, cz + dz, o.state, o.prot, o.overwrite);
                }
            }
        }
    }
}
