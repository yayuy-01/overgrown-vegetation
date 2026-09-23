package com.yayuy.overgrownvegetation.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.math.Transformation;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.CurledFernDetail;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.RenderTypeGroup;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import net.neoforged.neoforge.client.model.pipeline.QuadBakingVertexConsumer;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.loading.FileLoader;
import software.bernie.geckolib.loading.json.raw.Model;
import software.bernie.geckolib.loading.object.BakedModelFactory;
import software.bernie.geckolib.loading.object.GeometryTree;
import software.bernie.geckolib.util.RenderUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * GeckoLib 形式のモデル({@code geo.json})を、そのまま<b>静的なブロックモデル</b>として焼き込む
 * モデル読み込み器。丸まったシダ({@code overgrown_curled_fern})用。
 *
 * <p>モデル JSON の書き方:
 * <pre>{@code
 * {
 *   "parent": "minecraft:block/block",          // display(手持ち・GUI の見え方)を継承するため
 *   "loader": "overgrown_vegetation:geo_static",
 *   "render_type": "minecraft:cutout",
 *   "geo": "overgrown_vegetation:geo/overgrown_curled_fern.geo.json",
 *   "scale": 0.82,                               // 任意。ブロック底面中央を基準に一様に拡大
 *   "y_rotation": 45,                            // 任意。ブロック中心の鉛直軸まわりの回転(度)
 *   "reduced_skip_bones": ["fern3", "fern5"],    // 任意。品質 REDUCED のときに省くボーン(子ごと)
 *   "reduced_bone_groups": [                     // 任意。上より優先。下記参照
 *     ["fern7", "fern2"], ["fern3", "fern4"], ["fern6", "fern5"]
 *   ],
 *   "textures": { "texture": "...", "particle": "..." }
 * }
 * }</pre>
 *
 * <h2>品質 REDUCED でどの葉を残すか</h2>
 * <p>2 通りの書き方がある。どちらも<b>残す面数は同じ</b>で、違うのは「草ごとに変わるか」だけ。
 * <ul>
 *     <li>{@code reduced_skip_bones} —— 省くボーンを直接指定する。<b>どの草も同じ形</b>になる。</li>
 *     <li>{@code reduced_bone_groups} —— <b>各グループからちょうど 1 本ずつ</b>残す
 *     (残す本数 = グループの数)。どれを残すかは<b>ブロックの位置ごとにランダム</b>に決まるので、
 *     同じ面数のまま草ごとに形が変わる。仕組みは {@link RandomBoneGroupModel}。</li>
 * </ul>
 * <p>巻きシダは {@code reduced_bone_groups} 側で、6 本の葉を<b>生えている高さで 3 つのペア</b>に
 * 分けてある(低い: fern7 0.3 / fern2 2.0、中: fern3 3.3 / fern4 5.1、高い: fern6 6.8 / fern5 7.9)。
 * <b>ペアの組み方が重要</b>: 6 本から単純に 3 本選ぶと {fern4, fern6, fern5} のように上ばかり・
 * 下ばかりの組が出て、茎が剥げた草になってしまう。高さのペアから 1 本ずつ取れば
 * <b>どの組み合わせでも必ず低・中・高が 1 本ずつ</b>そろう。2 × 2 × 2 = 8 通りで、
 * blockstate 側の 64 通りの variants と掛かって 512 通りの見え方になる。
 *
 * <h2>GeckoLib 描画との同一性</h2>
 * <p>geo.json の解析は GeckoLib 自身の {@link FileLoader} / {@link BakedModelFactory} に任せ、
 * ボーンと直方体の変換行列も GeckoLib のレンダラー({@code GeoRenderer#renderRecursively} /
 * {@code renderCube})と同じ順で {@link RenderUtil} を使って組み立てる。そのため頂点の位置・UV・法線は
 * {@code GeoBlockRenderer} で描いていたときと同じ。違いは次の 2 点だけ:
 * <ul>
 *     <li>チャンクメッシュは裏面カリングされるので、両側から見える必要のある面は表(外側から見て反時計回り)と
 *     裏の 2 枚に焼いて GeckoLib のカリング無し描画と同じ見え方にする。</li>
 *     <li>陰影はブロック用(面の向きごとの固定値)になる。</li>
 * </ul>
 *
 * <h2>焼き込まない面(見た目を変えずに面数を減らす)</h2>
 * <p>Blockbench のモデルには「そこにあるが絶対に見えない面」が多く含まれる。チャンクメッシュは一度焼けば
 * 使い回せるぶん、焼いた面はそのままチャンクの頂点バッファを太らせ続けるので、次の面は焼かない:
 * <ul>
 *     <li><b>面積ゼロの面</b> — 厚み 0 の直方体の縁。</li>
 *     <li><b>UV が潰れている面</b> — {@code uv_size} の縦か横が 0。テクスチャの 1 ラインを引き伸ばすだけの面で、
 *     Blockbench で厚み 0 の板を作ると自動で付いてくる。<b>厚み 0.1 のような「ほぼ板」の縁もここで落ちる</b> ——
 *     Blockbench はその縁にも {@code uv_size} の片側が 0 の UV を書き出すため
 *     (巻きシダの {@code size: [8, 0.1, 4]} の葉 4 枚がこれ。面積ゼロ判定には引っかからないが、
 *     この UV 判定で拾えている。2026-09-17 に確認)。</li>
 *     <li><b>UV の範囲が全部透明な面</b> — 葉の下面 UV など。GeckoLib でも透明にしか描かれていなかったので
 *     見た目は変わらない(下から見えていたのは上面の裏側で、それは今までどおり焼く)。</li>
 *     <li><b>閉じた箱の裏面</b> — 6 面すべてが残る厚みのある直方体(茎など)は内側が見えないので表の 1 枚だけ。</li>
 * </ul>
 * <p>巻きシダの場合これで 1 本あたり 188 面 → 86 面になる(見た目は変わらない)。
 * さらに落としたいときは {@code curledFernDetail}({@link CurledFernDetail})で葉を間引く / 十字板にする。
 * FULL と REDUCED の切り替えはバニラのビデオ設定「シダをすべて表示する」({@link CurledFernOption})
 * からも行える(同じ設定を触っている)。
 */
public final class GeoStaticGeometry implements IUnbakedGeometry<GeoStaticGeometry> {

    public static final ResourceLocation LOADER_ID = OvergrownVegetation.id("geo_static");

    /** UV の端でとなりのピクセルを拾わないための余裕。 */
    private static final float UV_EPSILON = 1.0E-4F;
    /** この長さ(モデル単位)より短い辺は「厚みなし」とみなす。 */
    private static final double SOLID_EPSILON = 1.0E-4D;

    /**
     * 最後に焼き込んだときの品質。config を変えたときに何をすれば反映されるかの判定に使う
     * ({@link #applyDetailChange})。
     *
     * <p>{@link CurledFernDetail#FULL} と {@link CurledFernDetail#REDUCED} は<b>同じモデルを焼く</b>
     * ({@link RandomBoneGroupModel} が両方の面を持つ)ので、この 2 つの間で値が動いたときは
     * 焼き直さず、{@code allChanged()} のあとにこの値だけ進める。
     */
    private static volatile CurledFernDetail lastBakedDetail = null;

    /**
     * 葉を 1 本も省かずに描くか({@link CurledFernDetail#FULL} 相当)。
     * {@link RandomBoneGroupModel#getQuads} が<b>チャンク構築のワーカースレッドから</b>読むので、
     * config を直接読まずにここへ写して持つ。
     *
     * <p><b>{@code volatile} でなければならない</b>: {@code ModConfigSpec.ConfigValue#get()} が
     * 値を溜めている {@code cachedValue} は素のフィールドで、レンダースレッドが書き換えても
     * ワーカースレッドがそれを見る保証が無い。見落とすと、そのとき焼かれたチャンクだけ
     * <b>古い見た目のまま残り続ける</b>(次に何かで積み直されるまで直らない)。
     */
    private static volatile boolean showAllFronds = false;

    private final ResourceLocation geoLocation;
    private final float scale;
    private final float yRotationDegrees;
    /**
     * 品質 {@link CurledFernDetail#REDUCED} のときに丸ごと省くボーン名(子ボーンも一緒に省かれる)。
     * {@link #reducedBoneGroups} が指定されていればそちらが優先され、これは使われない。
     */
    private final Set<String> reducedSkipBones;
    /**
     * 品質 {@link CurledFernDetail#REDUCED} のときに<b>各グループからちょうど 1 本ずつ</b>だけ残すボーン名。
     * 残す本数はグループの数で決まり、どれを残すかは<b>ブロックの位置ごとにランダム</b>
     * ({@link RandomBoneGroupModel})。空なら {@link #reducedSkipBones} の固定指定に戻る。
     */
    private final List<List<String>> reducedBoneGroups;

    private GeoStaticGeometry(ResourceLocation geoLocation, float scale, float yRotationDegrees,
                              Set<String> reducedSkipBones, List<List<String>> reducedBoneGroups) {
        this.geoLocation = geoLocation;
        this.scale = scale;
        this.yRotationDegrees = yRotationDegrees;
        this.reducedSkipBones = reducedSkipBones;
        this.reducedBoneGroups = reducedBoneGroups;
    }

    /** {@code "loader": "overgrown_vegetation:geo_static"} の JSON を読む側。 */
    public static final class Loader implements IGeometryLoader<GeoStaticGeometry> {
        public static final Loader INSTANCE = new Loader();

        private Loader() {
        }

        @Override
        public GeoStaticGeometry read(JsonObject json, JsonDeserializationContext context) throws JsonParseException {
            ResourceLocation geo = ResourceLocation.parse(GsonHelper.getAsString(json, "geo"));
            float scale = GsonHelper.getAsFloat(json, "scale", 1.0F);
            float rotation = GsonHelper.getAsFloat(json, "y_rotation", 0.0F);

            Set<String> skip = new LinkedHashSet<>();
            JsonArray skipJson = GsonHelper.getAsJsonArray(json, "reduced_skip_bones", null);
            if (skipJson != null) {
                for (int i = 0; i < skipJson.size(); i++) {
                    skip.add(GsonHelper.convertToString(skipJson.get(i), "reduced_skip_bones[" + i + "]"));
                }
            }

            List<List<String>> groups = new ArrayList<>();
            JsonArray groupsJson = GsonHelper.getAsJsonArray(json, "reduced_bone_groups", null);
            if (groupsJson != null) {
                for (int i = 0; i < groupsJson.size(); i++) {
                    String where = "reduced_bone_groups[" + i + "]";
                    JsonArray groupJson = GsonHelper.convertToJsonArray(groupsJson.get(i), where);
                    List<String> group = new ArrayList<>(groupJson.size());
                    for (int j = 0; j < groupJson.size(); j++) {
                        group.add(GsonHelper.convertToString(groupJson.get(j), where + "[" + j + "]"));
                    }
                    if (!group.isEmpty()) {
                        groups.add(List.copyOf(group));
                    }
                }
            }
            return new GeoStaticGeometry(geo, scale, rotation, Set.copyOf(skip), List.copyOf(groups));
        }
    }

    /**
     * config の品質設定。config がまだ読まれていない段階(起動直後など)では<b>その設定の既定値</b>。
     *
     * <p>既定値をここに書き写さず {@code getDefault()} から取るのは、
     * {@code curledFernDetail} の既定を変えたとき(2026-09-17 に FULL → REDUCED)に
     * 「最初の焼き込みだけ古い品質」という食い違いが起きないようにするため。
     */
    public static CurledFernDetail detail() {
        if (!OvergrownVegetationConfig.SPEC.isLoaded()) {
            return OvergrownVegetationConfig.COMMON.curledFernDetail.getDefault();
        }
        return OvergrownVegetationConfig.COMMON.curledFernDetail.get();
    }

    /**
     * config の品質設定が変わったぶんを画面へ反映する。ゲーム中に設定を変えたときに呼ぶもので、
     * ビデオ設定のトグル({@link CurledFernOption})と、config ファイルを直接編集したとき
     * ({@code OvergrownVegetationClient#reloadOnCurledFernDetailChange})の両方から来る。
     *
     * <p>やることは変更の種類で 2 段階に分かれる。
     * <ul>
     *     <li><b>FULL ⇄ REDUCED</b> — {@link RandomBoneGroupModel} が両方の面を持っているので、
     *     焼いたモデルはそのまま使える。<b>チャンクメッシュの焼き直しだけ</b>
     *     ({@code levelRenderer.allChanged()}、バニラの「グラフィック描画」と同じ重さ)。</li>
     *     <li><b>SIMPLE との行き来</b> — 十字の板は形そのものが別で、モデルを焼く時点で
     *     決まっている。<b>リソース再読み込み</b>(数秒固まる)が要る。</li>
     * </ul>
     *
     * <p>一度も焼いていない(= まだリソース読み込み前)なら、次の読み込みで新しい値が使われるので何もしない。
     */
    public static void applyDetailChange() {
        CurledFernDetail baked = lastBakedDetail;
        CurledFernDetail current = detail();
        if (baked == null || baked == current) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        if (baked == CurledFernDetail.SIMPLE || current == CurledFernDetail.SIMPLE) {
            minecraft.execute(minecraft::reloadResourcePacks);
            return;
        }
        // FULL ⇄ REDUCED。焼いたモデルは両方を持っているので、チャンクを積み直せばよい。
        // 積み直しを頼む<b>前に</b>フラグを立てる —— 逆だと、走り出したワーカーが古い値で焼いてしまう。
        showAllFronds = current == CurledFernDetail.FULL;
        // lastBakedDetail は「どちらでも描ける状態で焼いてある」ことを表すよう現在値へ進めておく
        // (でないと次の呼び出しでも毎回ここに来てしまう)。
        lastBakedDetail = current;
        minecraft.execute(() -> {
            if (minecraft.levelRenderer != null) {
                minecraft.levelRenderer.allChanged();
            }
        });
    }

    /**
     * 葉を 1 本も省かずに描くフレームか。{@link RandomBoneGroupModel} が
     * チャンク構築のワーカースレッドから呼ぶ({@link #showAllFronds} の説明を参照)。
     */
    public static boolean showAllFronds() {
        return showAllFronds;
    }

    @Override
    public BakedModel bake(IGeometryBakingContext context, ModelBaker baker,
                           Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState,
                           ItemOverrides overrides) {
        CurledFernDetail detail = detail();
        lastBakedDetail = detail;
        showAllFronds = detail == CurledFernDetail.FULL;

        TextureAtlasSprite sprite = spriteGetter.apply(context.getMaterial("texture"));
        TextureAtlasSprite particle = context.hasMaterial("particle")
                ? spriteGetter.apply(context.getMaterial("particle"))
                : sprite;

        Transformation stateRotation = modelState.getRotation();
        boolean ambientOcclusion = context.useAmbientOcclusion();

        // GeoBlockRenderer と同じ出発点: ブロック底面中央へ移動 → 拡大 → 鉛直軸まわりの回転 → ボーン階層。
        PoseStack pose = new PoseStack();
        pose.translate(0.5D, 0.0D, 0.5D);
        if (scale != 1.0F) {
            pose.scale(scale, scale, scale);
        }
        if (yRotationDegrees != 0.0F) {
            pose.mulPose(Axis.YP.rotationDegrees(yRotationDegrees));
        }

        ResourceLocation renderTypeHint = context.getRenderTypeHint();
        RenderTypeGroup renderTypes = renderTypeHint != null ? context.getRenderType(renderTypeHint) : RenderTypeGroup.EMPTY;

        // reduced_bone_groups が指定されていれば、FULL / REDUCED <b>両方</b>の面を持つモデルを返す。
        // ビデオ設定の「シダをすべて表示する」はどちらを返すかの分岐だけなので、
        // 切り替えてもモデルの焼き直しが要らない(CurledFernOption 参照)。
        // SIMPLE は形そのものが別(十字の板)なので、ここには入らず下の従来の焼き方へ行く。
        // 空が返るのはボーン名が geo.json に 1 つも無かったときだけで、その場合も下へ落ちる。
        if (detail != CurledFernDetail.SIMPLE && !reducedBoneGroups.isEmpty()) {
            GroupBake groups = bakeGroupCombos(pose, sprite, stateRotation, ambientOcclusion);
            if (groups != null) {
                // 1 通りしか作れなかった(グループがどれも 1 本)なら、わざわざ包む意味がない。
                if (groups.combos().size() > 1) {
                    return new RandomBoneGroupModel(
                            buildSimple(context, overrides, particle, renderTypes, groups.allFronds()),
                            groups.allFronds(), groups.combos());
                }
                return buildSimple(context, overrides, particle, renderTypes,
                        detail == CurledFernDetail.FULL ? groups.allFronds() : groups.combos().get(0));
            }
        }

        List<BakedQuad> quads = new ArrayList<>();
        if (detail == CurledFernDetail.SIMPLE) {
            // 立体モデルをやめて、バニラの草と同じ十字の板 1 組(4面)だけにする。
            bakeCross(pose, particle, stateRotation, ambientOcclusion, quads);
        } else {
            // REDUCED でグループ指定が無いモデルは、従来どおり固定のボーンを省く。
            Set<String> skip = detail == CurledFernDetail.REDUCED ? reducedSkipBones : Set.of();
            bakeAllBones(pose, sprite, stateRotation, ambientOcclusion, skip, Map.of(), quads);
        }
        return buildSimple(context, overrides, particle, renderTypes, quads);
    }

    /** 焼き終えた面を {@link SimpleBakedModel} に詰める。面は全部「カリング無し」側に入れる(従来と同じ)。 */
    private static BakedModel buildSimple(IGeometryBakingContext context, ItemOverrides overrides,
                                          TextureAtlasSprite particle, RenderTypeGroup renderTypes,
                                          List<BakedQuad> quads) {
        SimpleBakedModel.Builder builder = new SimpleBakedModel.Builder(
                context.useAmbientOcclusion(), context.useBlockLight(), context.isGui3d(),
                context.getTransforms(), overrides).particle(particle);
        for (BakedQuad quad : quads) {
            builder.addUnculledFace(quad);
        }
        return builder.build(renderTypes);
    }

    /** geo.json を読み込み、最上位ボーンから順に焼く。 */
    private void bakeAllBones(PoseStack pose, TextureAtlasSprite sprite, Transformation stateRotation,
                              boolean ambientOcclusion, Set<String> skip,
                              Map<String, List<BakedQuad>> poolTargets, List<BakedQuad> out) {
        BakedGeoModel geo = loadGeoModel(geoLocation);
        for (GeoBone bone : geo.topLevelBones()) {
            bakeBone(pose, bone, sprite, stateRotation, ambientOcclusion, skip, poolTargets, out);
        }
    }

    /**
     * {@code reduced_bone_groups} の<b>各グループからちょうど 1 本ずつ</b>選んだ、
     * ありうる組み合わせすべてを焼く。返すリストの要素 1 つが「1 本の草の見た目」。
     *
     * <p>グループに属さない部分(茎など)は<b>1 回だけ</b>焼いて全組み合わせで共有する。
     * 葉 1 本ぶんの面も 1 回ずつしか焼かず、組み合わせは<b>同じ {@link BakedQuad} を
     * 参照するリストを並べ替えて作るだけ</b>なので、頂点データが組み合わせの数だけ増えることはない
     * (2 × 2 × 2 = 8 通りでも、焼く面の総数は FULL 1 本ぶんと変わらない)。
     */
    @Nullable
    private GroupBake bakeGroupCombos(PoseStack pose, TextureAtlasSprite sprite,
                                      Transformation stateRotation, boolean ambientOcclusion) {
        // 葉 1 本につき 1 つの受け皿を用意して、1 回の走査で振り分けながら焼く。
        Map<String, List<BakedQuad>> perBone = new LinkedHashMap<>();
        for (List<String> group : reducedBoneGroups) {
            for (String boneName : group) {
                perBone.put(boneName, new ArrayList<>());
            }
        }
        List<BakedQuad> shared = new ArrayList<>();
        bakeAllBones(pose, sprite, stateRotation, ambientOcclusion, Set.of(), perBone, shared);

        // 各グループから 1 本ずつ選ぶ直積。空のグループ(名前が geo.json に無かった等)は飛ばす。
        List<List<BakedQuad>> combos = new ArrayList<>();
        combos.add(shared);
        int contributed = 0;
        for (List<String> group : reducedBoneGroups) {
            List<String> present = new ArrayList<>();
            for (String boneName : group) {
                if (!perBone.get(boneName).isEmpty()) {
                    present.add(boneName);
                }
            }
            if (present.isEmpty()) {
                continue;
            }
            contributed++;
            List<List<BakedQuad>> next = new ArrayList<>(combos.size() * present.size());
            for (List<BakedQuad> base : combos) {
                for (String boneName : present) {
                    List<BakedQuad> combined = new ArrayList<>(base.size() + perBone.get(boneName).size());
                    combined.addAll(base);
                    combined.addAll(perBone.get(boneName));
                    next.add(combined);
                }
            }
            combos = next;
        }
        // どのグループも 1 本も見つからなかった = ボーン名の書き間違いか geo.json の差し替え。
        // このまま返すと「茎だけの草」になってしまうので、null を返して従来の焼き方へ落とす。
        if (contributed == 0) {
            return null;
        }

        List<List<BakedQuad>> immutable = new ArrayList<>(combos.size());
        for (List<BakedQuad> combo : combos) {
            immutable.add(List.copyOf(combo));
        }

        // 「シダをすべて表示する」が ON のとき返すぶん: 共有部分 + 葉を 1 本も省かない全部。
        List<BakedQuad> allFronds = new ArrayList<>(shared);
        for (List<BakedQuad> boneQuads : perBone.values()) {
            allFronds.addAll(boneQuads);
        }
        return new GroupBake(List.copyOf(allFronds), immutable);
    }

    /**
     * {@link #bakeGroupCombos} の結果。
     *
     * @param allFronds 葉を 1 本も省かない面の一覧({@link CurledFernDetail#FULL} 用)
     * @param combos    各グループから 1 本ずつ選んだ組み合わせ({@link CurledFernDetail#REDUCED} 用)。
     *                  中身は {@code allFronds} と<b>同じ {@link BakedQuad} を参照している</b>ので、
     *                  両方を持っても頂点データは 1 本ぶんのまま。
     */
    private record GroupBake(List<BakedQuad> allFronds, List<List<BakedQuad>> combos) {
    }

    /** geo.json を GeckoLib 自身の読み込み器で {@link BakedGeoModel} にする(GeckoLib のキャッシュには頼らない)。 */
    private static BakedGeoModel loadGeoModel(ResourceLocation location) {
        Model raw = FileLoader.loadModelFile(location, Minecraft.getInstance().getResourceManager());
        return BakedModelFactory.getForNamespace(location.getNamespace()).constructGeoModel(GeometryTree.fromModel(raw));
    }

    /**
     * {@code GeoRenderer#renderRecursively} と同じ順で行列を積み、直方体の面を焼き込む。
     *
     * @param skipBones   丸ごと省くボーン(子ごと)
     * @param poolTargets 「このボーンから下の面は {@code out} ではなく専用のリストへ入れる」対応表。
     *                    {@link #bakeGroupCombos} が葉 1 本ぶんの面を別々に集めるために使う。
     *                    対応表に無いボーンは、いま使っている {@code out} にそのまま入る。
     */
    private static void bakeBone(PoseStack pose, GeoBone bone, TextureAtlasSprite sprite, Transformation stateRotation,
                                 boolean ambientOcclusion, Set<String> skipBones, Map<String, List<BakedQuad>> poolTargets, List<BakedQuad> out) {
        if (skipBones.contains(bone.getName())) {
            return;
        }
        // このボーンが振り分け対象なら、ここから下(子ボーンも含む)は専用のリストへ。
        List<BakedQuad> own = poolTargets.get(bone.getName());
        if (own != null) {
            out = own;
        }

        pose.pushPose();
        RenderUtil.prepMatrixForBone(pose, bone);

        if (!bone.isHidden()) {
            for (GeoCube cube : bone.getCubes()) {
                bakeCube(pose, cube, sprite, stateRotation, ambientOcclusion, out);
            }
        }

        if (!bone.isHidingChildren()) {
            for (GeoBone child : bone.getChildBones()) {
                bakeBone(pose, child, sprite, stateRotation, ambientOcclusion, skipBones, poolTargets, out);
            }
        }
        pose.popPose();
    }

    private static void bakeCube(PoseStack pose, GeoCube cube, TextureAtlasSprite sprite, Transformation stateRotation,
                                 boolean ambientOcclusion, List<BakedQuad> out) {
        // 見える面だけ集める。捨てた面があるとその穴から中が覗けるので、裏面を焼く必要が残る。
        List<GeoQuad> visible = new ArrayList<>(6);
        for (GeoQuad quad : cube.quads()) {
            if (quad != null && !isFaceInvisible(quad, sprite)) {
                visible.add(quad);
            }
        }
        if (visible.isEmpty()) {
            return;
        }
        // 6 面すべてが残る厚みのある直方体は閉じているので、内側(裏面)は永久に見えない。
        boolean closed = visible.size() == 6 && hasThicknessOnEveryAxis(cube);

        pose.pushPose();
        RenderUtil.translateToPivotPoint(pose, cube);
        RenderUtil.rotateMatrixAroundCube(pose, cube);
        RenderUtil.translateAwayFromPivotPoint(pose, cube);

        Matrix4f poseMatrix = pose.last().pose();
        Matrix3f normalMatrix = pose.last().normal();
        for (GeoQuad quad : visible) {
            // GeckoLib が陰影に使う法線(ミラー・厚み 0 の直方体の補正込み)。
            Vector3f lightNormal = normalMatrix.transform(new Vector3f(quad.normal()));
            RenderUtil.fixInvertedFlatCube(cube, lightNormal);
            // その面が直方体の「外側」を向く方向(頂点順の判定用)。
            Vector3f outward = normalMatrix.transform(new Vector3f(quad.direction().step()));
            bakeQuad(quad, poseMatrix, lightNormal, outward, sprite, stateRotation, ambientOcclusion, !closed, out);
        }
        pose.popPose();
    }

    /** 3 辺すべてに厚みがある(= 中身の詰まった箱)か。{@code inflate} も込みで見る。 */
    private static boolean hasThicknessOnEveryAxis(GeoCube cube) {
        double inflate = 2.0D * cube.inflate();
        return cube.size().x() + inflate > SOLID_EPSILON
                && cube.size().y() + inflate > SOLID_EPSILON
                && cube.size().z() + inflate > SOLID_EPSILON;
    }

    /**
     * この面が「テクスチャ上どう転んでも見えない」か。UV が線に潰れている面と、UV の範囲が全部透明な面が
     * 該当する。厚み 0 の板に自動で付く縁の面や、葉の下面 UV がこれで落ちる。
     */
    private static boolean isFaceInvisible(GeoQuad quad, TextureAtlasSprite sprite) {
        float uMin = Float.MAX_VALUE;
        float uMax = -Float.MAX_VALUE;
        float vMin = Float.MAX_VALUE;
        float vMax = -Float.MAX_VALUE;
        for (GeoVertex vertex : quad.vertices()) {
            uMin = Math.min(uMin, vertex.texU());
            uMax = Math.max(uMax, vertex.texU());
            vMin = Math.min(vMin, vertex.texV());
            vMax = Math.max(vMax, vertex.texV());
        }

        SpriteContents contents = sprite.contents();
        int width = contents.width();
        int height = contents.height();
        // UV(0〜1)をスプライトのピクセル範囲へ。端でとなりを拾わないよう内側に少しだけ寄せる。
        int x0 = Mth.clamp(Mth.floor(uMin * width + UV_EPSILON), 0, width - 1);
        int x1 = Mth.clamp(Mth.ceil(uMax * width - UV_EPSILON) - 1, -1, width - 1);
        int y0 = Mth.clamp(Mth.floor(vMin * height + UV_EPSILON), 0, height - 1);
        int y1 = Mth.clamp(Mth.ceil(vMax * height - UV_EPSILON) - 1, -1, height - 1);
        if (x1 < x0 || y1 < y0) {
            // UV が線(または点)に潰れている面。
            return true;
        }

        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (!contents.isTransparent(0, x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void bakeQuad(GeoQuad quad, Matrix4f poseMatrix, Vector3f lightNormal, Vector3f outward,
                                 TextureAtlasSprite sprite, Transformation stateRotation, boolean ambientOcclusion,
                                 boolean doubleSided, List<BakedQuad> out) {
        GeoVertex[] vertices = quad.vertices();
        Vector3f[] positions = new Vector3f[4];
        float[] us = new float[4];
        float[] vs = new float[4];
        for (int i = 0; i < 4; i++) {
            Vector3f position = new Vector3f(vertices[i].position());
            poseMatrix.transformPosition(position);
            applyStateRotation(position, stateRotation);
            positions[i] = position;
            us[i] = vertices[i].texU();
            vs[i] = vertices[i].texV();
        }

        // 面積ゼロ(厚み 0 の直方体の縁)は捨てる。対角線同士の外積が面の幾何学的な法線。
        Vector3f geometricNormal = new Vector3f(positions[2]).sub(positions[0])
                .cross(new Vector3f(positions[3]).sub(positions[1]));
        if (geometricNormal.lengthSquared() < 1.0E-10F) {
            return;
        }

        Matrix3f stateNormal = stateRotation.getNormalMatrix();
        Vector3f shadingNormal = stateNormal.transform(new Vector3f(lightNormal)).normalize();
        Vector3f outwardFinal = stateNormal.transform(new Vector3f(outward));
        // チャンクメッシュは裏面カリングされるので、外側から見て反時計回りになるよう頂点順を揃える。
        boolean flip = geometricNormal.dot(outwardFinal) < 0.0F;
        int[] front = flip ? new int[] {0, 3, 2, 1} : new int[] {0, 1, 2, 3};
        int[] back = flip ? new int[] {0, 1, 2, 3} : new int[] {0, 3, 2, 1};
        Direction shadeDirection = Direction.getNearest(shadingNormal.x(), shadingNormal.y(), shadingNormal.z());

        out.add(bakeOneSide(positions, us, vs, front, sprite, shadeDirection, shadingNormal, ambientOcclusion));
        if (doubleSided) {
            // GeckoLib はカリング無し(entityCutoutNoCull)で描いていたので、どの面も裏側から見えていた。
            // 例: 葉(厚み 0 の直方体)の下面 UV は透明で、下から見えていたのは上面の裏側。
            // 同じ見た目にするため裏も焼く(頂点順を逆にしただけで、陰影・法線は表と同じ)。
            out.add(bakeOneSide(positions, us, vs, back, sprite, shadeDirection, shadingNormal, ambientOcclusion));
        }
    }

    /** 品質 {@link CurledFernDetail#SIMPLE} 用。バニラの {@code block/cross} と同じ十字の板を焼く。 */
    private static void bakeCross(PoseStack pose, TextureAtlasSprite sprite, Transformation stateRotation,
                                  boolean ambientOcclusion, List<BakedQuad> out) {
        // ブロック底面中央を原点とした、対角線上に立つ 2 枚の板(バニラの草と同じく縁を内側へ寄せる)。
        float r = 0.45F;
        float[][][] planes = {
                {{-r, 1.0F, -r}, {-r, 0.0F, -r}, {r, 0.0F, r}, {r, 1.0F, r}},
                {{r, 1.0F, -r}, {r, 0.0F, -r}, {-r, 0.0F, r}, {-r, 1.0F, r}},
        };
        float[] us = {0.0F, 0.0F, 1.0F, 1.0F};
        float[] vs = {0.0F, 1.0F, 1.0F, 0.0F};

        Matrix4f poseMatrix = pose.last().pose();
        for (float[][] plane : planes) {
            Vector3f[] positions = new Vector3f[4];
            for (int i = 0; i < 4; i++) {
                Vector3f position = new Vector3f(plane[i][0], plane[i][1], plane[i][2]);
                poseMatrix.transformPosition(position);
                applyStateRotation(position, stateRotation);
                positions[i] = position;
            }
            Vector3f normal = new Vector3f(positions[2]).sub(positions[0])
                    .cross(new Vector3f(positions[3]).sub(positions[1])).normalize();
            Direction shadeDirection = Direction.getNearest(normal.x(), normal.y(), normal.z());
            out.add(bakeOneSide(positions, us, vs, new int[] {0, 1, 2, 3},
                    sprite, shadeDirection, normal, ambientOcclusion));
            out.add(bakeOneSide(positions, us, vs, new int[] {0, 3, 2, 1},
                    sprite, shadeDirection, normal, ambientOcclusion));
        }
    }

    private static BakedQuad bakeOneSide(Vector3f[] positions, float[] us, float[] vs, int[] order,
                                         TextureAtlasSprite sprite, Direction shadeDirection, Vector3f shadingNormal,
                                         boolean ambientOcclusion) {
        QuadBakingVertexConsumer consumer = new QuadBakingVertexConsumer();
        consumer.setSprite(sprite);
        consumer.setDirection(shadeDirection);
        consumer.setShade(true);
        consumer.setTintIndex(-1);
        consumer.setHasAmbientOcclusion(ambientOcclusion);
        for (int index : order) {
            Vector3f p = positions[index];
            consumer.addVertex(p.x(), p.y(), p.z())
                    .setColor(255, 255, 255, 255)
                    .setUv(sprite.getU(us[index]), sprite.getV(vs[index]))
                    .setUv2(0, 0)
                    .setNormal(shadingNormal.x(), shadingNormal.y(), shadingNormal.z());
        }
        return consumer.bakeQuad();
    }

    /** blockstate の {@code x}/{@code y} 回転をバニラ({@code FaceBakery})と同じくブロック中心まわりに適用する。 */
    private static void applyStateRotation(Vector3f position, Transformation stateRotation) {
        Vector4f v = new Vector4f(position.x() - 0.5F, position.y() - 0.5F, position.z() - 0.5F, 1.0F);
        stateRotation.getMatrix().transform(v);
        position.set(v.x() + 0.5F, v.y() + 0.5F, v.z() + 0.5F);
    }
}
