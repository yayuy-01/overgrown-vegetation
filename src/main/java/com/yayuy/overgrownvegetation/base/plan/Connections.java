package com.yayuy.overgrownvegetation.base.plan;

import java.util.Map;

/**
 * 塀・金網(鉄格子・板ガラス)・木の柵のつながりを、隣のマスから決め直す(2026-10-01)。
 *
 * <p>ゲームは {@code setBlock} で置いたブロックのつながりを自分では直さない(バニラの構造物も
 * {@code Block.updateFromNeighbourShapes} で後から直している)。ここで<b>設計図と配置計画だけから</b>決めておけば、
 * 世界生成でチャンクごとに置いても境目でずれず、プレビューも同じ形になる。規則はバニラのおおまかな写し:
 * <ul>
 *     <li>金網の仲間: 金網の仲間・塀・固い立方体へつながる。</li>
 *     <li>塀: 塀・金網の仲間・固い立方体・向きの合うフェンスゲートへつながる。上に固いブロックがあれば tall、無ければ low。
 *     まっすぐ 2 方向だけなら柱(up)を立てない(上に塀の柱・灯りがあれば立てる)。</li>
 *     <li>木の柵: 木の柵・固い立方体・向きの合うフェンスゲートへつながる。</li>
 * </ul>
 */
public final class Connections {

    /** 周りのマスを読む口。分からないマス(地形など)は {@link Blueprint#KEEP} を返す(つながらない扱い)。 */
    public interface Reader {
        String get(int x, int y, int z);
    }

    /** つながりを持つブロックか。 */
    public static boolean connects(String state) {
        return Shapes.isBars(state) || Shapes.isWall(state) || Shapes.isFence(state);
    }

    /** {@code state} のつながりを、(x, y, z) の周りから決め直した状態。つながりを持たないブロックはそのまま。 */
    public static String resolve(String state, Reader r, int x, int y, int z) {
        if (Shapes.isBars(state)) {
            Map<String, String> p = States.props(state);
            for (Facing f : Facing.values()) {
                String n = r.get(x + f.dx, y, z + f.dz);
                boolean attach = Shapes.isBars(n) || Shapes.isWall(n) || Shapes.isFullCube(n);
                p.put(f.serialized(), Boolean.toString(attach));
            }
            p.putIfAbsent("waterlogged", "false");
            return States.build(States.id(state), p);
        }
        if (Shapes.isWall(state)) {
            Map<String, String> p = States.props(state);
            String above = r.get(x, y + 1, z);
            boolean coveredAbove = Shapes.isFullCube(above);
            boolean[] side = new boolean[4];
            for (Facing f : Facing.values()) {
                String n = r.get(x + f.dx, y, z + f.dz);
                boolean attach = Shapes.isWall(n) || Shapes.isBars(n) || Shapes.isFullCube(n) || gateAccepts(n, f);
                side[f.ordinal()] = attach;
                p.put(f.serialized(), attach ? (coveredAbove ? "tall" : "low") : "none");
            }
            boolean straight = (side[0] && side[2] && !side[1] && !side[3]) || (side[1] && side[3] && !side[0] && !side[2]);
            // 有刺鉄線: 金網の支柱(塀を積んだ柱)の天辺に載る
            boolean postAbove = (Shapes.isWall(above) && "true".equals(States.prop(above, "up")))
                    || States.id(above).contains("lantern") || States.id(above).contains("torch")
                    || States.id(above).contains("floodlight") || States.id(above).contains("_lamp")
                    || States.id(above).endsWith(":barbed_wire");
            p.put("up", Boolean.toString(!straight || postAbove));
            p.putIfAbsent("waterlogged", "false");
            return States.build(States.id(state), p);
        }
        if (Shapes.isFence(state)) {
            Map<String, String> p = States.props(state);
            for (Facing f : Facing.values()) {
                String n = r.get(x + f.dx, y, z + f.dz);
                boolean attach = Shapes.isFence(n) || Shapes.isFullCube(n) || gateAccepts(n, f);
                p.put(f.serialized(), Boolean.toString(attach));
            }
            p.putIfAbsent("waterlogged", "false");
            return States.build(States.id(state), p);
        }
        return state;
    }

    /** {@code dir} の向きにある隣がフェンスゲートで、こちらへ横腹(門柱)を向けているか。 */
    private static boolean gateAccepts(String neighbour, Facing dir) {
        if (!Shapes.isFenceGate(neighbour)) {
            return false;
        }
        Facing gate = Facing.byName(States.prop(neighbour, "facing"));
        if (gate == null) {
            return false;
        }
        // ゲートの向き(開く向き)と直角の方向に門柱がある
        return (gate.dx == 0) != (dir.dx == 0);
    }

    /** 設計図の中のつながりをすべて決め直す(箱の外はつながらない扱い)。 */
    public static void resolveAll(Blueprint.Builder b) {
        for (int y = 0; y < b.h; y++) {
            for (int z = 0; z < b.d; z++) {
                for (int x = 0; x < b.w; x++) {
                    String s = b.get(x, y, z);
                    if (connects(s)) {
                        b.set(x, y, z, resolve(s, b::get, x, y, z));
                    }
                }
            }
        }
    }

    private Connections() {
    }
}
