package com.yayuy.overgrownvegetation.worldgen.grammar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;

/**
 * ゴールデンテスト本体。{@code ./gradlew grammarGoldenTest} から {@code JavaExec} で呼ばれる。
 *
 * <p>{@code tools/structure-grammar/fixtures/p1-baseline.json}(ブラウザ側 P1 が確定させた
 * SHA-256)と、この Java エンジンが {@code tools/structure-grammar/examples/*.grammar.json} から
 * 生成する出力を照合する。ズレたら「エンジンの移植ミス」か「examples/*.json と src/examples.js の
 * ドリフト」。Minecraft を一切ロードしない(engine 中核は MC 非依存)。
 */
public final class GrammarGoldenMain {

    private GrammarGoldenMain() {
    }

    public static void main(String[] args) throws Exception {
        Path root = toolRoot();
        JsonObject cases = JsonParser.parseString(
                Files.readString(root.resolve("fixtures/p1-baseline.json"))).getAsJsonObject()
                .getAsJsonObject("cases");

        int pass = 0;
        int fail = 0;
        StringBuilder log = new StringBuilder();
        for (Map.Entry<String, JsonElement> e : cases.entrySet()) {
            String id = e.getKey(); // "overgrown_bush@1/flat"
            JsonObject want = e.getValue().getAsJsonObject();
            String name = id.substring(0, id.indexOf('@'));
            long seed = Long.parseLong(id.substring(id.indexOf('@') + 1, id.indexOf('/')));
            String terrainName = id.substring(id.indexOf('/') + 1);

            JsonObject grammar = JsonParser.parseString(Files.readString(grammarFile(root, name))).getAsJsonObject();
            GrammarEngine.Result res = GrammarEngine.run(grammar, seed, GrammarTerrain.preset(terrainName), null);
            String got = sha256Hex(res.canonical());

            boolean ok = got.equals(want.get("sha256").getAsString())
                    && res.blockCount == want.get("blocks").getAsInt()
                    && res.draws == want.get("draws").getAsLong();
            if (ok) {
                pass++;
                log.append("OK  ").append(id).append('\n');
            } else {
                fail++;
                log.append("NG  ").append(id).append('\n')
                        .append("     want sha=").append(want.get("sha256").getAsString())
                        .append(" blocks=").append(want.get("blocks").getAsInt())
                        .append(" draws=").append(want.get("draws").getAsLong()).append('\n')
                        .append("     got  sha=").append(got)
                        .append(" blocks=").append(res.blockCount)
                        .append(" draws=").append(res.draws).append('\n');
                if (!res.warnings.isEmpty()) {
                    log.append("     warnings: ").append(res.warnings).append('\n');
                }
            }
        }

        // place_nbt スモークテスト(ブラウザで確認した値と一致すること)。
        String smoke = "{\"format\":\"structure_grammar\",\"version\":1,\"id\":\"t\",\"settings\":{},"
                + "\"palette\":{},\"axiom\":["
                + "{\"op\":\"move\",\"dist\":3},"
                + "{\"op\":\"place_nbt\",\"id\":\"overgrown_vegetation:demo_hut\",\"rotation\":\"random\","
                + "\"preview_size\":[5,4,5]}],\"rules\":{}}";
        GrammarEngine.Result sr = GrammarEngine.run(JsonParser.parseString(smoke).getAsJsonObject(),
                7L, GrammarTerrain.FLAT, null);
        boolean smokeOk = sr.placements.size() == 1
                && sr.placements.get(0).rotation() == 180
                && sr.placements.get(0).x() == 0 && sr.placements.get(0).y() == 3 && sr.placements.get(0).z() == 0
                && sr.blockCount == 40 && sr.draws == 1;
        System.out.println((smokeOk ? "OK  " : "NG  ") + "place_nbt smoke  rot=" + sr.placements.get(0).rotation()
                + " previewBlocks=" + sr.blockCount + " draws=" + sr.draws);
        if (!smokeOk) {
            fail++;
        } else {
            pass++;
        }

        System.out.println(log);
        System.out.println("structure-grammar golden: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** デモは tools/structure-grammar/examples/、MOD 同梱 grammar は data/.../grammar/。両方を探す。 */
    private static Path grammarFile(Path toolRoot, String name) {
        Path demo = toolRoot.resolve("examples/" + name + ".grammar.json");
        if (Files.exists(demo)) {
            return demo;
        }
        Path shipped = toolRoot.resolve("../../src/main/resources/data/overgrown_vegetation/grammar/" + name + ".json")
                .normalize();
        if (Files.exists(shipped)) {
            return shipped;
        }
        throw new IllegalStateException("grammar が見つかりません: " + name + " (" + demo + " / " + shipped + ")");
    }

    private static Path toolRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++) {
            Path cand = p.resolve("tools/structure-grammar");
            if (Files.isDirectory(cand)) {
                return cand;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("tools/structure-grammar が見つかりません (cwd="
                + Path.of("").toAbsolutePath() + ")");
    }

    private static String sha256Hex(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
