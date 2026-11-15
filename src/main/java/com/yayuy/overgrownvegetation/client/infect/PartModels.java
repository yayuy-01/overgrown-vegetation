package com.yayuy.overgrownvegetation.client.infect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 汎用の感染体の部品の形({@code geo/parasite_parts/<id>.geo.json} を、動かない四角の集まりへ焼く。GeckoLib は通さない)。
 * 部品の約束: 原点 = 付け根(体の表面)、+Y = 表面から外、−Z = 重力の向き(面に沿った下)、1 単位 = 1/16 ブロック。
 * 骨の回転は GeckoLib と同じ向きに読む(ファイルの値で X と Z は逆回り、Y はそのまま、掛ける順は Z → Y → X)。
 */
final class PartModels {

    /** 焼いた形。四角 1 枚 = 位置 12(xyz × 4。単位は 1/16 ブロック)・絵の位置 8(0〜1)・面の向き 3。 */
    record Model(ResourceLocation texture, int quads, float[] pos, float[] uv, float[] normal) {
    }

    private static final Map<String, Optional<Model>> MODELS = new HashMap<>();

    private PartModels() {
    }

    static void clear() {
        MODELS.clear();
    }

    @Nullable
    static Model get(String id) {
        return MODELS.computeIfAbsent(id, PartModels::load).orElse(null);
    }

    private static Optional<Model> load(String id) {
        ResourceLocation geo = OvergrownVegetation.id("geo/parasite_parts/" + id + ".geo.json");
        try {
            var res = Minecraft.getInstance().getResourceManager().getResource(geo);
            if (res.isEmpty()) {
                OvergrownVegetation.LOGGER.warn("PartModels 部品の形が無い: {}", geo);
                return Optional.empty();
            }
            JsonObject root;
            try (Reader in = new InputStreamReader(res.get().open(), StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(in).getAsJsonObject();
            }
            JsonObject g = root.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
            JsonObject desc = g.getAsJsonObject("description");
            float tw = desc.get("texture_width").getAsFloat(), th = desc.get("texture_height").getAsFloat();
            Map<String, Matrix4f> bones = new HashMap<>();
            List<float[]> pos = new ArrayList<>(), uv = new ArrayList<>(), nrm = new ArrayList<>();
            // 親が先に並んでいる前提(生成器の書き出しはそうなっている)。親が見つからなければ根として扱う
            for (JsonElement be : g.getAsJsonArray("bones")) {
                JsonObject b = be.getAsJsonObject();
                Matrix4f m = new Matrix4f();
                if (b.has("parent")) {
                    Matrix4f parent = bones.get(b.get("parent").getAsString());
                    if (parent != null) {
                        m.set(parent);
                    }
                }
                float[] pivot = vec(b, "pivot"), rot = vec(b, "rotation");
                m.translate(pivot[0], pivot[1], pivot[2]);
                m.rotateZ((float) Math.toRadians(-rot[2]));
                m.rotateY((float) Math.toRadians(rot[1]));
                m.rotateX((float) Math.toRadians(-rot[0]));
                m.translate(-pivot[0], -pivot[1], -pivot[2]);
                bones.put(b.get("name").getAsString(), m);
                if (!b.has("cubes")) {
                    continue;
                }
                for (JsonElement ce : b.getAsJsonArray("cubes")) {
                    JsonObject c = ce.getAsJsonObject();
                    float[] o = vec(c, "origin"), s = vec(c, "size");
                    if (!c.has("uv") || !c.get("uv").isJsonObject()) {
                        continue;
                    }
                    JsonObject faces = c.getAsJsonObject("uv");
                    for (String name : faces.keySet()) {
                        float[][] corners = corners(name, o[0], o[1], o[2], o[0] + s[0], o[1] + s[1], o[2] + s[2]);
                        if (corners == null) {
                            continue;
                        }
                        JsonObject f = faces.getAsJsonObject(name);
                        JsonArray fu = f.getAsJsonArray("uv"), fs = f.getAsJsonArray("uv_size");
                        float u0 = fu.get(0).getAsFloat() / tw, v0 = fu.get(1).getAsFloat() / th;
                        float u1 = u0 + fs.get(0).getAsFloat() / tw, v1 = v0 + fs.get(1).getAsFloat() / th;
                        float[] p = new float[12];
                        Vector3f v = new Vector3f();
                        for (int i = 0; i < 4; i++) {
                            m.transformPosition(corners[i][0], corners[i][1], corners[i][2], v);
                            p[i * 3] = v.x;
                            p[i * 3 + 1] = v.y;
                            p[i * 3 + 2] = v.z;
                        }
                        m.transformDirection(corners[4][0], corners[4][1], corners[4][2], v).normalize();
                        pos.add(p);
                        uv.add(new float[]{u0, v0, u1, v0, u1, v1, u0, v1});
                        nrm.add(new float[]{v.x, v.y, v.z});
                    }
                }
            }
            int n = pos.size();
            float[] ap = new float[n * 12], au = new float[n * 8], an = new float[n * 3];
            for (int i = 0; i < n; i++) {
                System.arraycopy(pos.get(i), 0, ap, i * 12, 12);
                System.arraycopy(uv.get(i), 0, au, i * 8, 8);
                System.arraycopy(nrm.get(i), 0, an, i * 3, 3);
            }
            return Optional.of(new Model(OvergrownVegetation.id("textures/entity/parasite_parts/" + id + ".png"), n, ap, au, an));
        } catch (Exception ex) {
            OvergrownVegetation.LOGGER.warn("PartModels 部品の形を読めない: {}", geo, ex);
            return Optional.empty();
        }
    }

    private static float[] vec(JsonObject o, String key) {
        if (!o.has(key)) {
            return new float[3];
        }
        JsonArray a = o.getAsJsonArray(key);
        return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
    }

    /** 面の 4 隅(絵の 左上・右上・右下・左下 の順)と、5 つ目に面の向き。 */
    @Nullable
    private static float[][] corners(String face, float x0, float y0, float z0, float x1, float y1, float z1) {
        return switch (face) {
            case "north" -> new float[][]{{x1, y1, z0}, {x0, y1, z0}, {x0, y0, z0}, {x1, y0, z0}, {0, 0, -1}};
            case "south" -> new float[][]{{x0, y1, z1}, {x1, y1, z1}, {x1, y0, z1}, {x0, y0, z1}, {0, 0, 1}};
            case "east" -> new float[][]{{x1, y1, z1}, {x1, y1, z0}, {x1, y0, z0}, {x1, y0, z1}, {1, 0, 0}};
            case "west" -> new float[][]{{x0, y1, z0}, {x0, y1, z1}, {x0, y0, z1}, {x0, y0, z0}, {-1, 0, 0}};
            case "up" -> new float[][]{{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}, {0, 1, 0}};
            case "down" -> new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y0, z0}, {x0, y0, z0}, {0, -1, 0}};
            default -> null;
        };
    }
}
