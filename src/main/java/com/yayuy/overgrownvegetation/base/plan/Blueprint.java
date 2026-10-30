package com.yayuy.overgrownvegetation.base.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 建物 1 棟の設計図(2026-10-01)。幅 {@code w}(x)× 高さ {@code h}(y)× 奥行 {@code d}(z)の箱に、ブロック状態の文字列を並べたもの。
 *
 * <p>向きの約束: <b>正面(入口)は +z(南)</b>。y = 0 が床(地面の高さ = 建物の土台の高さに置く)。
 * マス目の値は {@link #KEEP}(触らない = 地形のまま)・{@link States#AIR}(空ける)・ブロック。
 * 回した設計図は {@link #rotated} で作る(寸法の w と d が入れ替わり、状態の向きも回る)。
 */
public final class Blueprint {

    /** 触らないマス(地形・ほかの建物をそのままにする)。 */
    public static final String KEEP = "";

    public final int w;
    public final int h;
    public final int d;
    private final List<String> palette;
    private final short[] cells;

    Blueprint(int w, int h, int d, List<String> palette, short[] cells) {
        this.w = w;
        this.h = h;
        this.d = d;
        this.palette = palette;
        this.cells = cells;
    }

    /** そのマスの状態。箱の外・触らないマスは {@link #KEEP}。 */
    public String get(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= d) {
            return KEEP;
        }
        return palette.get(cells[index(x, y, z)]);
    }

    private int index(int x, int y, int z) {
        return (y * d + z) * w + x;
    }

    /** 使っている状態の一覧(0 番は {@link #KEEP})。 */
    public List<String> palette() {
        return palette;
    }

    /** 時計回りに {@code turns} 回 1/4 回転させた設計図。 */
    public Blueprint rotated(int turns) {
        int t = Math.floorMod(turns, 4);
        if (t == 0) {
            return this;
        }
        int nw = (t & 1) == 1 ? d : w;
        int nd = (t & 1) == 1 ? w : d;
        List<String> np = new ArrayList<>(palette.size());
        for (String s : palette) {
            np.add(s.equals(KEEP) ? KEEP : States.rotate(s, t));
        }
        short[] nc = new short[cells.length];
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    int rx = x;
                    int rz = z;
                    int cw = w;
                    int cd = d;
                    for (int i = 0; i < t; i++) {
                        // 時計回り 1 回: (x, z) → (d-1-z, x)、寸法は (w, d) → (d, w)
                        int tx = cd - 1 - rz;
                        rz = rx;
                        rx = tx;
                        int tmp = cw;
                        cw = cd;
                        cd = tmp;
                    }
                    nc[(y * nd + rz) * nw + rx] = cells[index(x, y, z)];
                }
            }
        }
        return new Blueprint(nw, h, nd, np, nc);
    }

    /** 組み立て。マス目の既定は {@link #KEEP}。 */
    public static final class Builder {
        public final int w;
        public final int h;
        public final int d;
        private final List<String> palette = new ArrayList<>();
        private final Map<String, Short> index = new HashMap<>();
        private final short[] cells;

        public Builder(int w, int h, int d) {
            this.w = w;
            this.h = h;
            this.d = d;
            this.cells = new short[w * h * d];
            idx(KEEP);
        }

        private short idx(String state) {
            Short i = index.get(state);
            if (i == null) {
                i = (short) palette.size();
                palette.add(state);
                index.put(state, i);
            }
            return i;
        }

        public boolean inside(int x, int y, int z) {
            return x >= 0 && y >= 0 && z >= 0 && x < w && y < h && z < d;
        }

        public Builder set(int x, int y, int z, String state) {
            if (inside(x, y, z)) {
                cells[(y * d + z) * w + x] = idx(state);
            }
            return this;
        }

        public String get(int x, int y, int z) {
            return inside(x, y, z) ? palette.get(cells[(y * d + z) * w + x]) : KEEP;
        }

        /** 直方体を埋める(端を含む。逆順でもよい)。 */
        public Builder fill(int x0, int y0, int z0, int x1, int y1, int z1, String state) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
                        set(x, y, z, state);
                    }
                }
            }
            return this;
        }

        /** 直方体の側面だけ(床と天井は塗らない)。 */
        public Builder ring(int x0, int y0, int z0, int x1, int y1, int z1, String state) {
            int ax = Math.min(x0, x1);
            int bx = Math.max(x0, x1);
            int az = Math.min(z0, z1);
            int bz = Math.max(z0, z1);
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int x = ax; x <= bx; x++) {
                    set(x, y, az, state);
                    set(x, y, bz, state);
                }
                for (int z = az; z <= bz; z++) {
                    set(ax, y, z, state);
                    set(bx, y, z, state);
                }
            }
            return this;
        }

        /** 触らないマスだけ埋める(既に何か置いたマスは残す)。 */
        public Builder fillKeep(int x0, int y0, int z0, int x1, int y1, int z1, String state) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
                        if (inside(x, y, z) && get(x, y, z).equals(KEEP)) {
                            set(x, y, z, state);
                        }
                    }
                }
            }
            return this;
        }

        public Blueprint build() {
            return new Blueprint(w, h, d, List.copyOf(palette), cells.clone());
        }
    }
}
