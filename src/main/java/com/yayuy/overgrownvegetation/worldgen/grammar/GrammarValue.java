package com.yayuy.overgrownvegetation.worldgen.grammar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 数値の解決(リテラル / "$参照" / 制限付き式 / 分布ノード)と blockstate 正規化。
 * ブラウザ側 {@code src/expr.js} と一致。SPEC §3・§4.3。Minecraft 非依存。
 */
final class GrammarValue {

    private GrammarValue() {
    }

    // ---- 数値解決 ----

    static double resolveNumber(JsonElement value, Map<String, Double> scope, GrammarPrng rng) {
        if (value == null) {
            throw new IllegalArgumentException("数値がありません");
        }
        if (value.isJsonPrimitive()) {
            if (value.getAsJsonPrimitive().isNumber()) {
                return value.getAsDouble();
            }
            if (value.getAsJsonPrimitive().isString()) {
                String s = value.getAsString().trim();
                if (isBareRef(s)) {
                    String nm = s.substring(1);
                    Double v = scope.get(nm);
                    if (v == null) {
                        throw new IllegalArgumentException("$" + nm + " が未定義");
                    }
                    return v;
                }
                return evalExpr(s, scope);
            }
        }
        if (value.isJsonObject() && value.getAsJsonObject().has("d")) {
            return sampleDist(value.getAsJsonObject(), scope, rng);
        }
        throw new IllegalArgumentException("数値として解釈できません: " + value);
    }

    private static boolean isBareRef(String s) {
        if (s.length() < 2 || s.charAt(0) != '$') {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = c == '_' || Character.isLetterOrDigit(c) && c < 128;
            if (i == 1 && Character.isDigit(c)) {
                return false;
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static double sampleDist(JsonObject node, Map<String, Double> scope, GrammarPrng rng) {
        String d = node.get("d").getAsString();
        switch (d) {
            case "uniformInt": {
                long lo = Math.round(resolveNumber(node.get("min"), scope, rng));
                long hi = Math.round(resolveNumber(node.get("max"), scope, rng));
                if (hi < lo) {
                    long t = lo;
                    lo = hi;
                    hi = t;
                }
                return lo + rng.nextIntBounded((int) (hi - lo + 1));
            }
            case "uniformFloat": {
                double a = resolveNumber(node.get("min"), scope, rng);
                double b = resolveNumber(node.get("max"), scope, rng);
                return a + rng.nextUnit() * (b - a);
            }
            case "gaussian": {
                double mean = resolveNumber(node.get("mean"), scope, rng);
                double sd = resolveNumber(node.get("stddev"), scope, rng);
                double u1 = rng.nextUnit();
                double u2 = rng.nextUnit();
                double g = Math.sqrt(-2 * StrictMath.log(Math.max(u1, 1e-300)))
                        * StrictMath.cos(2 * Math.PI * u2);
                double v = mean + sd * g;
                if (node.has("clamp")) {
                    JsonArray c = node.getAsJsonArray("clamp");
                    v = Math.min(Math.max(v, c.get(0).getAsDouble()), c.get(1).getAsDouble());
                }
                return v;
            }
            case "choice": {
                JsonArray opts = node.getAsJsonArray("options");
                double total = 0;
                for (JsonElement o : opts) {
                    JsonObject oo = o.getAsJsonObject();
                    total += oo.has("weight") ? oo.get("weight").getAsDouble() : 1;
                }
                double r = rng.nextUnit() * total;
                for (JsonElement o : opts) {
                    JsonObject oo = o.getAsJsonObject();
                    r -= oo.has("weight") ? oo.get("weight").getAsDouble() : 1;
                    if (r < 0) {
                        return resolveNumber(oo.get("value"), scope, rng);
                    }
                }
                JsonObject last = opts.get(opts.size() - 1).getAsJsonObject();
                return resolveNumber(last.get("value"), scope, rng);
            }
            default:
                throw new IllegalArgumentException("未知の分布 d=" + d);
        }
    }

    // ---- 制限付き式(再帰下降) ----

    private static final Map<String, Object> EXPR_CACHE = new java.util.HashMap<>();

    static double evalExpr(String src, Map<String, Double> scope) {
        Node ast;
        synchronized (EXPR_CACHE) {
            ast = (Node) EXPR_CACHE.get(src);
            if (ast == null) {
                ast = new Parser(src).parse();
                EXPR_CACHE.put(src, ast);
            }
        }
        return ast.eval(scope);
    }

    private interface Node {
        double eval(Map<String, Double> scope);
    }

    private static final class Parser {
        private final List<String> toks = new ArrayList<>();
        private int pos;
        private final String src;

        Parser(String src) {
            this.src = src;
            tokenize(src);
        }

        private void tokenize(String s) {
            int i = 0;
            int n = s.length();
            while (i < n) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                if (c == '$' || c == '_' || isAsciiLetter(c)) {
                    int j = i + 1;
                    while (j < n && (s.charAt(j) == '_' || isAsciiLetter(s.charAt(j)) || Character.isDigit(s.charAt(j)))) {
                        j++;
                    }
                    toks.add(s.substring(i, j));
                    i = j;
                    continue;
                }
                if (Character.isDigit(c)) {
                    int j = i + 1;
                    while (j < n && Character.isDigit(s.charAt(j))) {
                        j++;
                    }
                    if (j < n && s.charAt(j) == '.') {
                        j++;
                        while (j < n && Character.isDigit(s.charAt(j))) {
                            j++;
                        }
                    }
                    toks.add(s.substring(i, j));
                    i = j;
                    continue;
                }
                if ("()+-*/,".indexOf(c) >= 0) {
                    toks.add(String.valueOf(c));
                    i++;
                    continue;
                }
                throw new IllegalArgumentException("式を解釈できません: " + src);
            }
        }

        private static boolean isAsciiLetter(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
        }

        private String peek() {
            return pos < toks.size() ? toks.get(pos) : null;
        }

        private String next() {
            return toks.get(pos++);
        }

        private void expect(String t) {
            if (!t.equals(peek())) {
                throw new IllegalArgumentException("式: '" + t + "' が必要です (" + src + ")");
            }
            pos++;
        }

        Node parse() {
            Node ast = parseAdd();
            if (pos != toks.size()) {
                throw new IllegalArgumentException("式: 余分なトークン in " + src);
            }
            return ast;
        }

        private Node parseAdd() {
            Node left = parseMul();
            while ("+".equals(peek()) || "-".equals(peek())) {
                String op = next();
                Node right = parseMul();
                left = bin(op, left, right);
            }
            return left;
        }

        private Node parseMul() {
            Node left = parsePrimary();
            while ("*".equals(peek()) || "/".equals(peek())) {
                String op = next();
                Node right = parsePrimary();
                left = bin(op, left, right);
            }
            return left;
        }

        private Node parsePrimary() {
            String t = peek();
            if (t == null) {
                throw new IllegalArgumentException("式: 予期しない終端 in " + src);
            }
            if (t.equals("(")) {
                next();
                Node e = parseAdd();
                expect(")");
                return e;
            }
            if (t.equals("-")) {
                next();
                Node v = parsePrimary();
                return scope -> -v.eval(scope);
            }
            if (t.charAt(0) == '$') {
                next();
                String name = t.substring(1);
                return scope -> {
                    Double d = scope.get(name);
                    if (d == null) {
                        throw new IllegalArgumentException("$" + name + " が未定義");
                    }
                    return d;
                };
            }
            if (Character.isDigit(t.charAt(0))) {
                next();
                double val = Double.parseDouble(t);
                return scope -> val;
            }
            if (isAsciiLetter(t.charAt(0)) || t.charAt(0) == '_') {
                next();
                if (!"(".equals(peek())) {
                    throw new IllegalArgumentException("式: 未知の識別子 " + t);
                }
                next();
                List<Node> args = new ArrayList<>();
                if (!")".equals(peek())) {
                    args.add(parseAdd());
                    while (",".equals(peek())) {
                        next();
                        args.add(parseAdd());
                    }
                }
                expect(")");
                return func(t, args);
            }
            throw new IllegalArgumentException("式: 予期しないトークン " + t + " in " + src);
        }

        private static Node bin(String op, Node a, Node b) {
            switch (op) {
                case "+":
                    return scope -> a.eval(scope) + b.eval(scope);
                case "-":
                    return scope -> a.eval(scope) - b.eval(scope);
                case "*":
                    return scope -> a.eval(scope) * b.eval(scope);
                case "/":
                    return scope -> a.eval(scope) / b.eval(scope);
                default:
                    throw new IllegalArgumentException("式: 不正な演算子 " + op);
            }
        }

        private Node func(String name, List<Node> args) {
            switch (name) {
                case "min":
                    return scope -> Math.min(args.get(0).eval(scope), args.get(1).eval(scope));
                case "max":
                    return scope -> Math.max(args.get(0).eval(scope), args.get(1).eval(scope));
                case "floor":
                    return scope -> Math.floor(args.get(0).eval(scope));
                case "ceil":
                    return scope -> Math.ceil(args.get(0).eval(scope));
                case "round":
                    return scope -> (double) Math.round(args.get(0).eval(scope));
                case "abs":
                    return scope -> Math.abs(args.get(0).eval(scope));
                case "sqrt":
                    return scope -> Math.sqrt(args.get(0).eval(scope));
                case "clamp":
                    return scope -> {
                        double x = args.get(0).eval(scope);
                        double lo = args.get(1).eval(scope);
                        double hi = args.get(2).eval(scope);
                        return Math.min(Math.max(x, lo), hi);
                    };
                default:
                    throw new IllegalArgumentException("式: 未知の関数 " + name);
            }
        }
    }

    // ---- blockstate ----

    static String pickBlockState(JsonElement entry, GrammarPrng rng) {
        if (entry.isJsonArray()) {
            JsonArray arr = entry.getAsJsonArray();
            double total = 0;
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                total += o.has("weight") ? o.get("weight").getAsDouble() : 1;
            }
            double r = rng.nextUnit() * total;
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                r -= o.has("weight") ? o.get("weight").getAsDouble() : 1;
                if (r < 0) {
                    return normalizeState(o);
                }
            }
            return normalizeState(arr.get(arr.size() - 1).getAsJsonObject());
        }
        return normalizeState(entry.getAsJsonObject());
    }

    static String normalizeState(JsonObject obj) {
        String name = obj.get("block").getAsString();
        if (name.indexOf(':') < 0) {
            name = "minecraft:" + name;
        }
        if (!obj.has("properties")) {
            return name;
        }
        JsonObject props = obj.getAsJsonObject("properties");
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : props.entrySet()) {
            sorted.put(e.getKey(), e.getValue().getAsString());
        }
        if (sorted.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }
}
