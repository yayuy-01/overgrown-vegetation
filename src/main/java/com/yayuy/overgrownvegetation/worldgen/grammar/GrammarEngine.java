package com.yayuy.overgrownvegetation.worldgen.grammar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * grammar インタプリタ。ブラウザ側 {@code src/engine.js} と<b>1 ブロックまで</b>一致させる。
 * SPEC §6・§8。純粋(grammar, seed, terrain → 結果)で Minecraft 非依存。
 *
 * <p>ゲーム内配置は {@code GrammarStructureFeature} が {@link Result#blocks} を
 * {@code level.setBlock} していくだけ。
 */
public final class GrammarEngine {

    /** {@code place_nbt} が記録する構造 NBT 断片の配置指示。実配置は Feature 側。 */
    public record Placement(int x, int y, int z, String id, int rotation, String mirror) {
    }

    public static final class Result {
        public final List<GrammarGrid.Cell> blocks;
        public final int[] bounds; // minx,miny,minz,maxx,maxy,maxz
        public final int blockCount;
        public final boolean truncated;
        public final Map<String, Double> params;
        public final long draws;
        public final List<String> warnings;
        public final List<Placement> placements;

        Result(List<GrammarGrid.Cell> blocks, int[] bounds, int blockCount, boolean truncated,
               Map<String, Double> params, long draws, List<String> warnings, List<Placement> placements) {
            this.blocks = blocks;
            this.bounds = bounds;
            this.blockCount = blockCount;
            this.truncated = truncated;
            this.params = params;
            this.draws = draws;
            this.warnings = warnings;
            this.placements = placements;
        }

        /** SPEC §9: 各行 {@code "x y z state\n"} 連結の SHA-256 のもとになる正規文字列。 */
        public String canonical() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < blocks.size(); i++) {
                GrammarGrid.Cell c = blocks.get(i);
                if (i > 0) {
                    sb.append('\n');
                }
                sb.append(c.x).append(' ').append(c.y).append(' ').append(c.z).append(' ').append(c.state);
            }
            return sb.toString();
        }
    }

    private final JsonObject grammar;
    private final GrammarPrng rng;
    private final GrammarGrid grid;
    private final GrammarTurtle turtle = new GrammarTurtle();
    private final GrammarTerrain terrain;
    private final JsonObject rules;
    private final JsonObject palette;
    private final int maxDepth;
    private final Map<String, Set<String>> paletteStates = new LinkedHashMap<>();
    private final Map<String, Double> rootScope = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<Placement> placements = new ArrayList<>();
    private final Map<String, List<int[]>> selections = new HashMap<>();
    private Set<Long> lastTouched = new HashSet<>();

    private static final int[][] DISP_DIRS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private GrammarEngine(JsonObject grammar, long seed, GrammarTerrain terrain,
                          Map<String, Double> paramOverrides) {
        if (!"structure_grammar".equals(str(grammar, "format", null))) {
            throw new IllegalArgumentException("format が structure_grammar ではありません");
        }
        if (grammar.get("version") == null || grammar.get("version").getAsInt() != 1) {
            throw new IllegalArgumentException("未知の version(この実装は 1 のみ)");
        }
        this.grammar = grammar;
        this.terrain = terrain != null ? terrain : GrammarTerrain.FLAT;
        this.rules = grammar.has("rules") ? grammar.getAsJsonObject("rules") : new JsonObject();
        this.palette = grammar.has("palette") ? grammar.getAsJsonObject("palette") : new JsonObject();

        JsonObject settings = grammar.has("settings") ? grammar.getAsJsonObject("settings") : new JsonObject();
        this.maxDepth = settings.has("maxDepth") ? settings.get("maxDepth").getAsInt() : 12;
        int maxBlocks = settings.has("maxBlocks") ? settings.get("maxBlocks").getAsInt() : 200000;

        this.rng = new GrammarPrng(seed);
        this.grid = new GrammarGrid(maxBlocks);

        for (Map.Entry<String, JsonElement> e : palette.entrySet()) {
            Set<String> set = new HashSet<>();
            if (e.getValue().isJsonArray()) {
                for (JsonElement o : e.getValue().getAsJsonArray()) {
                    set.add(GrammarValue.normalizeState(o.getAsJsonObject()));
                }
            } else {
                set.add(GrammarValue.normalizeState(e.getValue().getAsJsonObject()));
            }
            paletteStates.put(e.getKey(), set);
        }

        JsonObject params = grammar.has("params") ? grammar.getAsJsonObject("params") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : params.entrySet()) {
            Double ov = paramOverrides == null ? null : paramOverrides.get(e.getKey());
            if (ov != null) {
                rootScope.put(e.getKey(), ov);
            } else {
                rootScope.put(e.getKey(), GrammarValue.resolveNumber(e.getValue(), rootScope, rng));
            }
        }

        // base(手作りの土台)を axiom の前に展開する。 SPEC §1.1
        if (grammar.has("base") && grammar.getAsJsonObject("base").has("layers")) {
            JsonObject base = grammar.getAsJsonObject("base");
            int[] bo = {0, 0, 0};
            if (base.has("origin")) {
                JsonArray o = base.getAsJsonArray("origin");
                bo = new int[] {o.get(0).getAsInt(), o.get(1).getAsInt(), o.get(2).getAsInt()};
            }
            JsonObject bkey = base.has("key") ? base.getAsJsonObject("key") : new JsonObject();
            JsonArray layers = base.getAsJsonArray("layers");
            for (int by = 0; by < layers.size(); by++) {
                JsonArray rows = layers.get(by).getAsJsonArray();
                for (int bz = 0; bz < rows.size(); bz++) {
                    String row = rows.get(bz).getAsString();
                    for (int bx = 0; bx < row.length(); bx++) {
                        char ch = row.charAt(bx);
                        if (ch == '.' || ch == ' ') {
                            continue;
                        }
                        JsonElement pkE = bkey.get(String.valueOf(ch));
                        if (pkE == null) {
                            throw new IllegalArgumentException("base.key に '" + ch + "' がありません");
                        }
                        String pk = pkE.getAsString();
                        grid.set(bo[0] + bx, bo[1] + by, bo[2] + bz, pickState(pk), paletteIsProtected(pk), "always");
                    }
                }
            }
        }
    }

    public static Result run(JsonObject grammar, long seed, GrammarTerrain terrain,
                             Map<String, Double> paramOverrides) {
        GrammarEngine engine = new GrammarEngine(grammar, seed, terrain, paramOverrides);
        engine.exec(arr(grammar, "axiom"), engine.rootScope, 0);
        List<GrammarGrid.Cell> sorted = engine.grid.toSortedList();
        return new Result(sorted, engine.grid.bounds(), engine.grid.size(), engine.grid.truncated,
                engine.rootScope, engine.rng.draws, engine.warnings, engine.placements);
    }

    // ---- helpers ----

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o.has(k) ? o.getAsJsonArray(k) : new JsonArray();
    }

    private double num(JsonElement v, Map<String, Double> scope, double def) {
        if (v == null || v.isJsonNull()) {
            return def;
        }
        return GrammarValue.resolveNumber(v, scope, rng);
    }

    private double num(JsonObject step, String key, Map<String, Double> scope, double def) {
        return num(step.get(key), scope, def);
    }

    private boolean paletteIsProtected(String k) {
        JsonElement e = palette.get(k);
        if (e == null) {
            return false;
        }
        if (e.isJsonArray()) {
            for (JsonElement o : e.getAsJsonArray()) {
                if (o.getAsJsonObject().has("protected") && o.getAsJsonObject().get("protected").getAsBoolean()) {
                    return true;
                }
            }
            return false;
        }
        return e.getAsJsonObject().has("protected") && e.getAsJsonObject().get("protected").getAsBoolean();
    }

    private String pickState(String k) {
        JsonElement e = palette.get(k);
        if (e == null) {
            throw new IllegalArgumentException("パレットキー '" + k + "' が未定義");
        }
        return GrammarValue.pickBlockState(e, rng);
    }

    // ---- 実行 ----

    private void exec(JsonArray steps, Map<String, Double> scope, int depth) {
        for (JsonElement se : steps) {
            if (grid.truncated) {
                return;
            }
            JsonObject step = se.getAsJsonObject();
            String op = step.get("op").getAsString();
            switch (op) {
                case "move":
                    doMove(step, scope);
                    break;
                case "turn":
                    turtle.turn(num(step, "yaw", scope, 0), num(step, "pitch", scope, 0),
                            num(step, "roll", scope, 0));
                    break;
                case "grow_toward":
                    turtle.growToward(targetVec(step.get("target")), num(step, "weight", scope, 0.5));
                    break;
                case "taper":
                    turtle.widthTarget = num(step, "to", scope, 0);
                    break;
                case "set_width": {
                    double w = num(step, "to", scope, 0);
                    turtle.width = w;
                    turtle.widthTarget = w;
                    break;
                }
                case "ground_follow": {
                    double mu = num(step, "maxUp", scope, 1);
                    double md = num(step, "maxDown", scope, 2);
                    int gy = terrain.groundY((int) Math.floor(turtle.pos[0]), (int) Math.floor(turtle.pos[2]));
                    if (gy > turtle.pos[1] + mu) {
                        turtle.pos[1] += mu;
                    } else if (gy < turtle.pos[1] - md) {
                        turtle.pos[1] -= md;
                    } else {
                        turtle.pos[1] = gy;
                    }
                    break;
                }
                case "push":
                    turtle.push();
                    break;
                case "pop":
                    turtle.pop();
                    break;
                case "stamp": {
                    Set<Long> prev = grid.recording;
                    grid.recording = new HashSet<>();
                    stampAt(step, scope);
                    lastTouched = grid.recording;
                    grid.recording = prev;
                    break;
                }
                case "scatter": {
                    Set<Long> prev = grid.recording;
                    grid.recording = new HashSet<>();
                    doScatter(step, scope);
                    lastTouched = grid.recording;
                    grid.recording = prev;
                    break;
                }
                case "decay":
                    doDecay(step, scope);
                    break;
                case "place_nbt":
                    doPlaceNbt(step, scope);
                    break;
                case "select":
                    selections.put(step.get("name").getAsString(),
                            selectionCells(step.has("region") ? step.getAsJsonObject("region") : null));
                    break;
                case "displace":
                    doDisplace(step, scope);
                    break;
                case "call": {
                    int rep = (int) Math.max(0, Math.round(num(step, "repeat", scope, 1)));
                    for (int r = 0; r < rep; r++) {
                        callRule(step.get("rule").getAsString(), step.getAsJsonObject("args"), scope, depth + 1);
                    }
                    break;
                }
                case "branch":
                    doBranch(step, scope, depth);
                    break;
                case "if": {
                    boolean ok = evalCond(step.getAsJsonObject("cond"), scope, depth);
                    exec(arr(step, ok ? "then" : "else"), scope, depth);
                    break;
                }
                case "repeat": {
                    int times = (int) Math.max(0, Math.round(num(step, "times", scope, 1)));
                    for (int t = 0; t < times; t++) {
                        exec(arr(step, "steps"), scope, depth);
                    }
                    break;
                }
                default:
                    throw new IllegalArgumentException("未知の op: " + op);
            }
        }
    }

    private double[] targetVec(JsonElement target) {
        if (target.isJsonArray()) {
            JsonArray a = target.getAsJsonArray();
            return GrammarVec.norm(new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()});
        }
        String s = target.getAsString();
        if ("up".equals(s)) {
            return new double[] {0, 1, 0};
        }
        if ("down".equals(s)) {
            return new double[] {0, -1, 0};
        }
        throw new IllegalArgumentException("grow_toward: 未知の target " + s);
    }

    private void doMove(JsonObject step, Map<String, Double> scope) {
        double dist = num(step, "dist", scope, 1);
        double[] start = new double[] {turtle.pos[0], turtle.pos[1], turtle.pos[2]};
        double[] end = GrammarVec.add(start, GrammarVec.scale(GrammarVec.norm(turtle.heading), dist));
        if (step.has("block")) {
            String state = pickState(step.get("block").getAsString());
            boolean prot = paletteIsProtected(step.get("block").getAsString());
            String overwrite = str(step, "overwrite", null);
            int[][] pts = line3(start, end);
            double w0 = turtle.width;
            double w1 = turtle.widthTarget;
            for (int i = 0; i < pts.length; i++) {
                double t = pts.length <= 1 ? 1 : (double) i / (pts.length - 1);
                double w = w0 + (w1 - w0) * t;
                GrammarGrid.Brush br = new GrammarGrid.Brush();
                br.grid = grid;
                br.cx = pts[i][0];
                br.cy = pts[i][1];
                br.cz = pts[i][2];
                br.state = state;
                br.prot = prot;
                br.overwrite = overwrite;
                br.rng = rng;
                GrammarGrid.sphere(br, w);
            }
        }
        turtle.pos = end;
        turtle.width = turtle.widthTarget;
        turtle.tint = Math.min(1, turtle.tint + dist / 32);
    }

    private void stampAt(JsonObject step, Map<String, Double> scope) {
        String block = step.get("block").getAsString();
        GrammarGrid.Brush o = new GrammarGrid.Brush();
        o.grid = grid;
        o.cx = turtle.pos[0];
        o.cy = turtle.pos[1];
        o.cz = turtle.pos[2];
        o.state = pickState(block);
        o.prot = paletteIsProtected(block);
        o.overwrite = str(step, "overwrite", null);
        o.rng = rng;
        String shape = step.get("shape").getAsString();
        switch (shape) {
            case "sphere":
                GrammarGrid.sphere(o, num(step, "radius", scope, 0));
                break;
            case "blob":
                GrammarGrid.blob(o, num(step, "radiusH", scope, 1), num(step, "radiusV", scope, 1),
                        num(step, "jitter", scope, 0));
                break;
            case "disc":
                GrammarGrid.disc(o, num(step, "radius", scope, 1), str(step, "axis", "y"));
                break;
            case "column":
                GrammarGrid.column(o, num(step, "radius", scope, 0), num(step, "length", scope, 1),
                        str(step, "axis", "y"));
                break;
            case "box": {
                JsonArray sz = step.getAsJsonArray("size");
                GrammarGrid.box(o, new double[] {
                        num(sz.get(0), scope, 0), num(sz.get(1), scope, 0), num(sz.get(2), scope, 0)
                }, step.has("hollow") && step.get("hollow").getAsBoolean(),
                        step.has("wall") ? step.get("wall").getAsInt() : 0);
                break;
            }
            case "mound":
                GrammarGrid.mound(o, num(step, "radius", scope, 1));
                break;
            default:
                throw new IllegalArgumentException("未知の shape: " + shape);
        }
    }

    // ---- 選択と変異(SPEC §6.2b) ----

    private boolean isSurface(int x, int y, int z) {
        for (int[] d : DISP_DIRS) {
            if (!grid.has(x + d[0], y + d[1], z + d[2])) {
                return true;
            }
        }
        return false;
    }

    private static void sortCells(List<int[]> a) {
        a.sort((p, q) -> {
            if (p[0] != q[0]) {
                return Integer.compare(p[0], q[0]);
            }
            if (p[1] != q[1]) {
                return Integer.compare(p[1], q[1]);
            }
            return Integer.compare(p[2], q[2]);
        });
    }

    private List<int[]> allCells() {
        List<int[]> a = new ArrayList<>();
        for (GrammarGrid.Cell c : grid.cells.values()) {
            a.add(new int[] {c.x, c.y, c.z});
        }
        return a;
    }

    private List<int[]> selectionCells(JsonObject reg) {
        String shape = reg == null ? "all" : (reg.has("shape") ? reg.get("shape").getAsString() : "all");
        List<int[]> out = new ArrayList<>();
        switch (shape) {
            case "all":
                out = allCells();
                break;
            case "palette": {
                Set<String> want = new HashSet<>();
                for (JsonElement k : reg.getAsJsonArray("keys")) {
                    Set<String> s = paletteStates.get(k.getAsString());
                    if (s != null) {
                        want.addAll(s);
                    }
                }
                for (GrammarGrid.Cell c : grid.cells.values()) {
                    if (want.contains(c.state)) {
                        out.add(new int[] {c.x, c.y, c.z});
                    }
                }
                break;
            }
            case "box": {
                JsonArray lo = reg.getAsJsonArray("min");
                JsonArray hi = reg.getAsJsonArray("max");
                int x0 = lo.get(0).getAsInt();
                int y0 = lo.get(1).getAsInt();
                int z0 = lo.get(2).getAsInt();
                int x1 = hi.get(0).getAsInt();
                int y1 = hi.get(1).getAsInt();
                int z1 = hi.get(2).getAsInt();
                for (GrammarGrid.Cell c : grid.cells.values()) {
                    if (c.x >= x0 && c.x <= x1 && c.y >= y0 && c.y <= y1 && c.z >= z0 && c.z <= z1) {
                        out.add(new int[] {c.x, c.y, c.z});
                    }
                }
                break;
            }
            case "surface": {
                String of = reg.has("of") ? reg.get("of").getAsString() : "all";
                List<int[]> pool = "all".equals(of) ? allCells()
                        : selections.getOrDefault(of, new ArrayList<>());
                for (int[] p : pool) {
                    if (grid.has(p[0], p[1], p[2]) && isSurface(p[0], p[1], p[2])) {
                        out.add(p);
                    }
                }
                break;
            }
            default:
                throw new IllegalArgumentException("select: 未知の region.shape " + shape);
        }
        sortCells(out);
        return out;
    }

    private void doDisplace(JsonObject step, Map<String, Double> scope) {
        double amount = num(step, "amount", scope, 0.3);
        double bias = num(step, "bias", scope, 0);
        int depth = Math.max(1, (int) Math.round(num(step, "depth", scope, 1)));
        double pGrow = (bias + 1) / 2;
        String fixedState = step.has("block") ? pickState(step.get("block").getAsString()) : null;
        boolean prot = step.has("block") && paletteIsProtected(step.get("block").getAsString());
        String overwrite = str(step, "overwrite", null);

        List<int[]> pool = step.has("select")
                ? selections.getOrDefault(step.get("select").getAsString(), new ArrayList<>())
                : selectionCells(null);
        List<int[]> targets = new ArrayList<>();
        for (int[] p : pool) {
            if (grid.has(p[0], p[1], p[2]) && isSurface(p[0], p[1], p[2])) {
                targets.add(p);
            }
        }
        sortCells(targets);

        for (int[] c : targets) {
            if (rng.nextUnit() >= amount) {
                continue;
            }
            boolean grow = rng.nextUnit() < pGrow;
            int[] dir = DISP_DIRS[rng.nextIntBounded(6)];
            if (grow) {
                if (!grid.has(c[0] + dir[0], c[1] + dir[1], c[2] + dir[2])) {
                    GrammarGrid.Cell cur = grid.get(c[0], c[1], c[2]);
                    String st = fixedState != null ? fixedState : (cur != null ? cur.state : null);
                    if (st != null) {
                        for (int g = 1; g <= depth; g++) {
                            grid.set(c[0] + dir[0] * g, c[1] + dir[1] * g, c[2] + dir[2] * g, st, prot, overwrite);
                        }
                    }
                }
            } else {
                for (int k = 0; k < depth; k++) {
                    int rx = c[0] + dir[0] * k;
                    int ry = c[1] + dir[1] * k;
                    int rz = c[2] + dir[2] * k;
                    if (grid.has(rx, ry, rz)) {
                        grid.remove(rx, ry, rz);
                    }
                }
            }
        }
    }

    private void doPlaceNbt(JsonObject step, Map<String, Double> scope) {
        double rot = 0;
        JsonElement rotE = step.get("rotation");
        if (rotE != null && rotE.isJsonPrimitive() && rotE.getAsJsonPrimitive().isString()
                && "random".equals(rotE.getAsString())) {
            rot = rng.nextIntBounded(4) * 90;
        } else if (rotE != null && !rotE.isJsonNull()) {
            rot = num(rotE, scope, 0);
        }
        int r = (int) (((Math.round(rot) % 360) + 360) % 360);
        int px = (int) Math.floor(turtle.pos[0]);
        int py = (int) Math.floor(turtle.pos[1]);
        int pz = (int) Math.floor(turtle.pos[2]);
        String mirror = step.has("mirror") ? step.get("mirror").getAsString() : "none";
        placements.add(new Placement(px, py, pz, step.get("id").getAsString(), r, mirror));

        if (step.has("preview_size")) {
            JsonArray ps = step.getAsJsonArray("preview_size");
            int w = Math.max(1, (int) Math.round(num(ps.get(0), scope, 1)));
            int hh = Math.max(1, (int) Math.round(num(ps.get(1), scope, 1)));
            int d = Math.max(1, (int) Math.round(num(ps.get(2), scope, 1)));
            for (int ex = 0; ex < w; ex++) {
                for (int ey = 0; ey < hh; ey++) {
                    for (int ez = 0; ez < d; ez++) {
                        int edges = (ex == 0 || ex == w - 1 ? 1 : 0)
                                + (ey == 0 || ey == hh - 1 ? 1 : 0)
                                + (ez == 0 || ez == d - 1 ? 1 : 0);
                        if (edges >= 2) {
                            grid.set(px + ex, py + ey, pz + ez, "minecraft:structure_block", false, "if_air");
                        }
                    }
                }
            }
        }
    }

    private void doScatter(JsonObject step, Map<String, Double> scope) {
        String block = step.get("block").getAsString();
        String state = pickState(block);
        boolean prot = paletteIsProtected(block);
        String overwrite = str(step, "overwrite", null);
        double density = num(step, "density", scope, 0.1);
        JsonObject reg = step.has("region") ? step.getAsJsonObject("region") : null;
        int cx = (int) Math.floor(turtle.pos[0]);
        int cy = (int) Math.floor(turtle.pos[1]);
        int cz = (int) Math.floor(turtle.pos[2]);
        if (reg != null && "sphere".equals(str(reg, "shape", "box"))) {
            int r = (int) Math.round(num(reg, "radius", scope, 2));
            double edge = (r + 0.5) * (r + 0.5);
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (dx * dx + dy * dy + dz * dz > edge) {
                            continue;
                        }
                        if (rng.nextUnit() < density) {
                            grid.set(cx + dx, cy + dy, cz + dz, state, prot, overwrite);
                        }
                    }
                }
            }
        } else {
            JsonArray s = reg != null ? reg.getAsJsonArray("size") : null;
            int sx = s != null ? (int) num(s.get(0), scope, 3) : 3;
            int sy = s != null ? (int) num(s.get(1), scope, 1) : 1;
            int sz = s != null ? (int) num(s.get(2), scope, 3) : 3;
            int x0 = cx - (int) Math.floor((sx - 1) / 2.0);
            int z0 = cz - (int) Math.floor((sz - 1) / 2.0);
            for (int x = 0; x < sx; x++) {
                for (int y = 0; y < sy; y++) {
                    for (int z = 0; z < sz; z++) {
                        if (rng.nextUnit() < density) {
                            grid.set(x0 + x, cy + y, z0 + z, state, prot, overwrite);
                        }
                    }
                }
            }
        }
    }

    private void doDecay(JsonObject step, Map<String, Double> scope) {
        double chance = num(step, "chance", scope, 0.2);
        List<GrammarGrid.Cell> targets = new ArrayList<>();
        if (step.has("match")) {
            Set<String> matchSet = new HashSet<>();
            for (JsonElement k : step.getAsJsonArray("match")) {
                Set<String> s = paletteStates.get(k.getAsString());
                if (s != null) {
                    matchSet.addAll(s);
                }
            }
            for (GrammarGrid.Cell c : grid.cells.values()) {
                if (matchSet.contains(c.state)) {
                    targets.add(c);
                }
            }
        } else {
            for (Long k : lastTouched) {
                GrammarGrid.Cell c = grid.cells.get(k);
                if (c != null) {
                    targets.add(c);
                }
            }
        }
        targets.sort((a, b) -> {
            if (a.x != b.x) {
                return Integer.compare(a.x, b.x);
            }
            if (a.y != b.y) {
                return Integer.compare(a.y, b.y);
            }
            return Integer.compare(a.z, b.z);
        });
        for (GrammarGrid.Cell c : targets) {
            if (rng.nextUnit() < chance) {
                grid.remove(c.x, c.y, c.z);
            }
        }
    }

    private void doBranch(JsonObject step, Map<String, Double> scope, int depth) {
        int count = (int) Math.max(0, Math.round(num(step, "count", scope, 1)));
        JsonObject cone = step.has("cone") ? step.getAsJsonObject("cone") : new JsonObject();
        boolean rollEven = step.has("roll_even") && step.get("roll_even").getAsBoolean();
        String rule = step.get("rule").getAsString();
        JsonObject args = step.getAsJsonObject("args");
        for (int i = 0; i < count; i++) {
            turtle.push();
            if (rollEven) {
                double base = 360.0 * i / count;
                double jit = (rng.nextUnit() - 0.5) * (360.0 / Math.max(1, count)) * 0.4;
                turtle.turn(0, 0, base + jit);
            }
            if (cone.has("yaw")) {
                JsonArray y = cone.getAsJsonArray("yaw");
                turtle.turn(y.get(0).getAsDouble() + rng.nextUnit() * (y.get(1).getAsDouble() - y.get(0).getAsDouble()), 0, 0);
            }
            if (cone.has("pitch")) {
                JsonArray p = cone.getAsJsonArray("pitch");
                turtle.turn(0, p.get(0).getAsDouble() + rng.nextUnit() * (p.get(1).getAsDouble() - p.get(0).getAsDouble()), 0);
            }
            callRule(rule, args, scope, depth + 1);
            turtle.pop();
        }
    }

    private void callRule(String name, JsonObject argSpec, Map<String, Double> callerScope, int depth) {
        JsonElement defE = rules.get(name);
        if (defE == null) {
            throw new IllegalArgumentException("ルール '" + name + "' が未定義");
        }
        JsonObject def = defE.getAsJsonObject();
        Map<String, Double> ns = new LinkedHashMap<>(rootScope);
        if (def.has("params")) {
            for (JsonElement pe : def.getAsJsonArray("params")) {
                String p = pe.getAsString();
                if (argSpec == null || !argSpec.has(p)) {
                    throw new IllegalArgumentException("ルール引数 '" + p + "' が未指定");
                }
                ns.put(p, GrammarValue.resolveNumber(argSpec.get(p), callerScope, rng));
            }
        }
        if (depth > maxDepth) {
            return;
        }
        exec(arr(def, "steps"), ns, depth);
    }

    private boolean evalCond(JsonObject cond, Map<String, Double> scope, int depth) {
        if (cond.has("chance")) {
            return rng.nextUnit() < num(cond, "chance", scope, 0);
        }
        if (cond.has("depth_lt")) {
            return depth < cond.get("depth_lt").getAsInt();
        }
        if (cond.has("depth_gte")) {
            return depth >= cond.get("depth_gte").getAsInt();
        }
        if (cond.has("tint_gte")) {
            return turtle.tint >= cond.get("tint_gte").getAsDouble();
        }
        if (cond.has("cmp")) {
            JsonArray c = cond.getAsJsonArray("cmp");
            double a = num(c.get(0), scope, 0);
            String op = c.get(1).getAsString();
            double b = num(c.get(2), scope, 0);
            switch (op) {
                case ">": return a > b;
                case ">=": return a >= b;
                case "<": return a < b;
                case "<=": return a <= b;
                case "==": return a == b;
                case "!=": return a != b;
                default: throw new IllegalArgumentException("cmp: 未知の演算子 " + op);
            }
        }
        if (cond.has("all")) {
            boolean r = true;
            for (JsonElement c : cond.getAsJsonArray("all")) {
                if (!evalCond(c.getAsJsonObject(), scope, depth)) {
                    r = false;
                }
            }
            return r;
        }
        if (cond.has("any")) {
            boolean r = false;
            for (JsonElement c : cond.getAsJsonArray("any")) {
                if (evalCond(c.getAsJsonObject(), scope, depth)) {
                    r = true;
                }
            }
            return r;
        }
        if (cond.has("not")) {
            return !evalCond(cond.getAsJsonObject("not"), scope, depth);
        }
        throw new IllegalArgumentException("未知の条件: " + cond);
    }

    // ---- 3D 線ラスタ(src/engine.js の line3 と一致) ----

    private static int[][] line3(double[] a, double[] b) {
        int x0 = (int) Math.floor(a[0]);
        int y0 = (int) Math.floor(a[1]);
        int z0 = (int) Math.floor(a[2]);
        int x1 = (int) Math.floor(b[0]);
        int y1 = (int) Math.floor(b[1]);
        int z1 = (int) Math.floor(b[2]);
        List<int[]> pts = new ArrayList<>();
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int x = x0;
        int y = y0;
        int z = z0;
        if (dx >= dy && dx >= dz) {
            int ey = 2 * dy - dx;
            int ez = 2 * dz - dx;
            for (int i = 0; i <= dx; i++) {
                pts.add(new int[] {x, y, z});
                if (ey > 0) {
                    y += sy;
                    ey -= 2 * dx;
                }
                if (ez > 0) {
                    z += sz;
                    ez -= 2 * dx;
                }
                ey += 2 * dy;
                ez += 2 * dz;
                x += sx;
            }
        } else if (dy >= dx && dy >= dz) {
            int ex = 2 * dx - dy;
            int ez = 2 * dz - dy;
            for (int j = 0; j <= dy; j++) {
                pts.add(new int[] {x, y, z});
                if (ex > 0) {
                    x += sx;
                    ex -= 2 * dy;
                }
                if (ez > 0) {
                    z += sz;
                    ez -= 2 * dy;
                }
                ex += 2 * dx;
                ez += 2 * dz;
                y += sy;
            }
        } else {
            int ex = 2 * dx - dz;
            int ey = 2 * dy - dz;
            for (int k = 0; k <= dz; k++) {
                pts.add(new int[] {x, y, z});
                if (ex > 0) {
                    x += sx;
                    ex -= 2 * dz;
                }
                if (ey > 0) {
                    y += sy;
                    ey -= 2 * dz;
                }
                ex += 2 * dx;
                ey += 2 * dy;
                z += sz;
            }
        }
        return pts.toArray(new int[0][]);
    }
}
