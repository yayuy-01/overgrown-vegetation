package com.yayuy.overgrownvegetation.base.plan;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ブロック状態の文字列({@code "minecraft:spruce_stairs[facing=north,half=bottom]"})を扱う道具(2026-10-01)。
 *
 * <p>このパッケージ({@code base.plan})は<b>マインクラフトのクラスを一切使わない</b>。基地の設計図と配置計画を
 * ゲーム内の設置とブラウザの 3D プレビュー({@code tools/base-preview})の両方で同じものにするため —— プレビューは
 * このパッケージだけを素の javac で組んで書き出す。ゲーム側は文字列を {@code BlockStateParser} で読む({@code base.build})。
 *
 * <p>回転は真上から見て時計回りの 1/4 回転の回数(x = 東、z = 南)。北 → 東 → 南 → 西。
 */
public final class States {

    public static final String AIR = "minecraft:air";

    /** 状態の文字列を組む: {@code of("minecraft:oak_stairs", "facing", "north", "half", "bottom")}。 */
    public static String of(String id, String... kv) {
        if (kv.length == 0) {
            return id;
        }
        StringBuilder sb = new StringBuilder(id).append('[');
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(kv[i]).append('=').append(kv[i + 1]);
        }
        return sb.append(']').toString();
    }

    /** {@code "ns:id[...]"} の id の部分。 */
    public static String id(String state) {
        int b = state.indexOf('[');
        return b < 0 ? state : state.substring(0, b);
    }

    /** 性質(並びは書いた順)。無ければ空。 */
    public static Map<String, String> props(String state) {
        Map<String, String> out = new LinkedHashMap<>();
        int b = state.indexOf('[');
        if (b < 0) {
            return out;
        }
        String body = state.substring(b + 1, state.length() - 1);
        if (body.isEmpty()) {
            return out;
        }
        for (String pair : body.split(",")) {
            int eq = pair.indexOf('=');
            out.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return out;
    }

    public static String prop(String state, String key) {
        return props(state).get(key);
    }

    public static String build(String id, Map<String, String> props) {
        if (props.isEmpty()) {
            return id;
        }
        StringBuilder sb = new StringBuilder(id).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }

    public static String with(String state, String key, String value) {
        Map<String, String> p = props(state);
        p.put(key, value);
        return build(id(state), p);
    }

    /**
     * 時計回りに {@code turns} 回 1/4 回転させた状態。向き(facing)・軸(axis)・旗の角度(rotation 0〜15)・
     * つながり(north/east/south/west)を回す。階段の shape・扉の hinge・ベッドの part は向きに対する相対なので変えない。
     */
    public static String rotate(String state, int turns) {
        int t = Math.floorMod(turns, 4);
        if (t == 0 || state.indexOf('[') < 0) {
            return state;
        }
        Map<String, String> p = props(state);
        Map<String, String> out = new LinkedHashMap<>(p);
        String facing = p.get("facing");
        if (facing != null) {
            Facing f = Facing.byName(facing);
            if (f != null) {
                out.put("facing", f.rotateCw(t).serialized());
            }
        }
        String axis = p.get("axis");
        if (axis != null && (t & 1) == 1) {
            if (axis.equals("x")) {
                out.put("axis", "z");
            } else if (axis.equals("z")) {
                out.put("axis", "x");
            }
        }
        String rotation = p.get("rotation");
        if (rotation != null) {
            try {
                out.put("rotation", Integer.toString(Math.floorMod(Integer.parseInt(rotation) + 4 * t, 16)));
            } catch (NumberFormatException ignored) {
                // 数でない rotation は回さない
            }
        }
        boolean hasSides = false;
        for (Facing f : Facing.values()) {
            if (p.containsKey(f.serialized())) {
                hasSides = true;
                break;
            }
        }
        if (hasSides) {
            for (Facing f : Facing.values()) {
                out.remove(f.serialized());
            }
            for (Facing f : Facing.values()) {
                String v = p.get(f.serialized());
                if (v != null) {
                    out.put(f.rotateCw(t).serialized(), v);
                }
            }
        }
        return build(id(state), out);
    }

    private States() {
    }
}
