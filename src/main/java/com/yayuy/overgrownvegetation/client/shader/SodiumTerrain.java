package com.yayuy.overgrownvegetation.client.shader;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.mixin.client.ShaderInstanceSamplerAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sodium が入っているときも、地形のシェーダーの演出(第 1 段は林冠の影と日向・森の中の青緑。第 2 段以降で葉の揺れ・霧・雲の影を足す)を出す
 * (2026-10-22、ユーザー「Sodium が無くても軽く、あったらさらに軽く」の続き。10-30 作り直しで戻した)。
 *
 * <h2>Sodium 0.8 の地形の描き方</h2>
 * Sodium はバニラの {@code rendertype_*} を使わず、3 つの層(固い・切り抜き・半透明)とも 1 つのシェーダー
 * {@code sodium:blocks/block_layer_opaque.{vsh,fsh}} で描く。中身は {@code ShaderLoader.getShaderSource} がクラスローダーから直に読む
 * (資源パックも MOD の {@code assets/minecraft} も効かない)。そこで:
 * <ol>
 *     <li>{@code getShaderSource} の戻り値を、この MOD の {@code assets/overgrown_vegetation/shaders/sodium/block_layer_opaque.*} に差し替える
 *     ({@code mixin/client/sodium/SodiumShaderLoaderMixin})。中の {@code #ov_import <ov_*.glsl>} はバニラ用と同じ include を
 *     {@code #version} 抜きで埋め込む(Sodium の {@code #import} は {@code #version} もそのまま足すので、2 つになって落ちる)。</li>
 *     <li>渡す前に自分で 1 回コンパイルとリンクを試し、失敗したら Sodium の元のシェーダーのまま(= 演出なし、落ちない)にしてログを 1 回出す。</li>
 *     <li>uniform は Sodium が各層の描き始めに呼ぶ {@code DefaultShaderInterface.setupState()} の後で流す
 *     ({@code SodiumShaderInterfaceMixin})。Sodium の set は {@code glUniform*} なのでプログラムが結ばれている間しか効かない。
 *     値は各演出のクラスがこれまでどおり AFTER_SKY でバニラの ShaderInstance に入れた分を写す(クラスごとの手直しが要らない)。
 *     自前のテクスチャ(第 1 段は林冠の日向の地図 1 枚)は空いている 2 番から(Sodium は 0 = ブロック、1 = ライトマップ)。</li>
 * </ol>
 * 頂点の透明度の印(揺れ・葉の色)は Sodium でも残る(焼いた面の色を透明度ごと写し、色付けの掛け算は 255 × a = a、明るさは RGB だけ)。
 * Sodium の頂点には法線が無いので、面の向きは fsh で位置の微分から出す。
 *
 * <p>効かせるのは Sodium 0.8.x だけ(試したのは 0.8.13。ユーザーと決めた)。Embeddium・シェーダーパックの入った Iris・
 * {@code -Dov.sodium.shaders=false}・差し込みの失敗のときは {@link #unsupported()} が true になり、各演出は今までどおり止まる。
 */
public final class SodiumTerrain {

    private static final ResourceLocation VSH = ResourceLocation.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque.vsh");
    private static final ResourceLocation FSH = ResourceLocation.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque.fsh");
    private static final Pattern OV_IMPORT = Pattern.compile("#ov_import <(.*)>");
    private static final Pattern SODIUM_IMPORT = Pattern.compile("#import <(.*):(.*)>");

    /** 地形の描画を差し替える MOD がいる(Sodium・Embeddium・Iris)。いなければ全部バニラの描画。 */
    private static final boolean OTHER_RENDERER = ModList.get().isLoaded("sodium") || ModList.get().isLoaded("embeddium")
            || ModList.get().isLoaded("iris") || ModList.get().isLoaded("oculus");
    /** Sodium 0.8.x だけで、Embeddium がおらず、切ってもいない。 */
    private static final boolean SODIUM_OK = OTHER_RENDERER && !ModList.get().isLoaded("embeddium")
            && !"false".equals(System.getProperty("ov.sodium.shaders")) && sodiumVersionOk();
    /** Iris の「シェーダーパックを使っているか」(無ければ null)。 */
    @Nullable
    private static final MethodHandle IRIS_PACK_IN_USE = irisPackInUseHandle();

    /** Mixin が 2 つとも当たっているか(初めて聞かれたときに確かめる)。 */
    private static volatile Boolean hooked;
    /** 差し込んだシェーダーのコンパイル・リンク・uniform の流し込みが失敗した。 */
    private static volatile boolean failed;
    /** 最後の自己試験で差し込んでよいと決まった中身(vsh を読むときに作り、続く fsh で使う)。 */
    @Nullable
    private static String patchedVsh;
    @Nullable
    private static String patchedFsh;
    /** Sodium のプログラムごとの、流す uniform とテクスチャの場所。 */
    private static final Int2ObjectOpenHashMap<Bindings> PROGRAMS = new Int2ObjectOpenHashMap<>();

    /**
     * 地形のシェーダーの演出を使えないか。true なら各演出は地図づくりも uniform の受け渡しも飛ばす(バニラの描画では false)。
     * チャンクを組むスレッド(葉の色のハンドラ)からも呼ばれる。シェーダーパックの切り替えに付いていくよう、Iris は毎回聞く。
     */
    public static boolean unsupported() {
        if (!OTHER_RENDERER) {
            return false;
        }
        return !SODIUM_OK || failed || !hooked() || irisPackInUse();
    }

    private static boolean hooked() {
        Boolean h = hooked;
        if (h == null) {
            h = hasHandler("net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader", "ov$sodiumSource")
                    && hasHandler("net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface", "ov$sodiumUniforms");
            hooked = h;
            if (!h) {
                OvergrownVegetation.LOGGER.warn("Sodium への差し込みの Mixin が当たっていないので、地形のシェーダーの演出は出さない");
            }
        }
        return h;
    }

    private static boolean hasHandler(String className, String handler) {
        try {
            for (Method m : Class.forName(className, false, SodiumTerrain.class.getClassLoader()).getDeclaredMethods()) {
                if (m.getName().contains(handler)) {
                    return true;
                }
            }
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
        return false;
    }

    private static boolean sodiumVersionOk() {
        return ModList.get().getModContainerById("sodium").map(c -> {
            // 「0.8.13+mc1.21.1」は Maven の版の読み方では数字に分けられない(0.0.0 になる)ので、文字列の頭で見る。
            String v = c.getModInfo().getVersion().toString();
            boolean ok = v.startsWith("0.8.");
            if (!ok) {
                OvergrownVegetation.LOGGER.info("Sodium {} は差し込みを試していない版(0.8.x だけ)なので、地形のシェーダーの演出は出さない", v);
            }
            return ok;
        }).orElse(false);
    }

    @Nullable
    private static MethodHandle irisPackInUseHandle() {
        if (!ModList.get().isLoaded("iris") && !ModList.get().isLoaded("oculus")) {
            return null;
        }
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            return MethodHandles.publicLookup().findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class)).bindTo(instance);
        } catch (ReflectiveOperationException | LinkageError e) {
            OvergrownVegetation.LOGGER.warn("Iris の API が見つからない。シェーダーパックを使っているものとして扱う", e);
            return MethodHandles.constant(boolean.class, true);
        }
    }

    private static boolean irisPackInUse() {
        if (IRIS_PACK_IN_USE == null) {
            return false;
        }
        try {
            return (boolean) IRIS_PACK_IN_USE.invokeExact();
        } catch (Throwable t) {
            return true;
        }
    }

    // ---- シェーダーの中身(SodiumShaderLoaderMixin から) ----

    /** Sodium のシェーダーの中身を差し替えるなら、その中身を返す(差し替えないなら null)。描画のスレッドで呼ばれる。 */
    @Nullable
    public static String patchSource(ResourceLocation name) {
        if (!SODIUM_OK || failed) {
            return null;
        }
        if (VSH.equals(name)) {
            prepare();
            return patchedVsh;
        }
        if (FSH.equals(name)) {
            return patchedFsh;
        }
        return null;
    }

    /** 両方の中身を作って試しにコンパイル・リンクする。失敗したら中身を null にし、以後は差し込まない。 */
    private static void prepare() {
        patchedVsh = null;
        patchedFsh = null;
        synchronized (PROGRAMS) {
            PROGRAMS.clear();   // プログラムを作り直す所(古い番号は消されて使い回される)
        }
        try {
            String vsh = expandOv(read(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "shaders/sodium/block_layer_opaque.vsh")));
            String fsh = expandOv(read(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "shaders/sodium/block_layer_opaque.fsh")));
            String error = selfTest(vsh, fsh);
            if (error != null) {
                fail("差し込む地形のシェーダーが試しのコンパイルで通らなかった: " + error, null);
                return;
            }
            patchedVsh = vsh;
            patchedFsh = fsh;
            OvergrownVegetation.LOGGER.info("Sodium の地形のシェーダーにこの MOD の演出を差し込んだ");
        } catch (IOException | RuntimeException e) {
            fail("Sodium の地形のシェーダーへの差し込みを作れなかった", e);
        }
    }

    private static String read(ResourceLocation location) throws IOException {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            throw new IOException("見つからない: " + location);
        }
        try (BufferedReader reader = resource.get().openAsReader()) {
            return reader.lines().reduce(new StringBuilder(), (b, l) -> b.append(l).append('\n'), StringBuilder::append).toString();
        }
    }

    /** {@code #ov_import <ov_x.glsl>} の行を、バニラ用の include({@code minecraft:shaders/include/ov_x.glsl})の中身(#version 抜き)に置き換える。 */
    private static String expandOv(String src) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : src.split("\n", -1)) {
            Matcher m = OV_IMPORT.matcher(line.trim());
            if (!m.matches()) {
                out.append(line).append('\n');
                continue;
            }
            out.append("// START ").append(line.trim()).append('\n');
            for (String inc : read(ResourceLocation.withDefaultNamespace("shaders/include/" + m.group(1))).split("\n", -1)) {
                if (!inc.trim().startsWith("#version")) {
                    out.append(inc).append('\n');
                }
            }
            out.append("// END ").append(line.trim()).append('\n');
        }
        return out.toString();
    }

    // ---- 自己試験(Sodium にコンパイルさせて失敗すると落ちるので、先に自分で試す) ----

    /** Sodium と同じ組み立て方(#import の展開・#version の直後に define)で、切り抜き層あり・なしの 2 通りを試す。失敗なら理由。 */
    @Nullable
    private static String selfTest(String vsh, String fsh) {
        for (boolean discard : new boolean[]{false, true}) {
            List<String> defines = new ArrayList<>();
            defines.add("#define USE_FOG");
            defines.add("#define USE_FOG_SMOOTH");
            if (discard) {
                defines.add("#define USE_FRAGMENT_DISCARD");
            }
            defines.add("#define USE_VERTEX_COMPRESSION");
            defines.add("#define MAX_TEXTURE_LOD_BIAS 4.0");
            String error = testProgram(assemble(vsh, defines), assemble(fsh, defines));
            if (error != null) {
                return (discard ? "[切り抜き] " : "[固い・半透明] ") + error;
            }
        }
        return null;
    }

    private static String assemble(String src, List<String> defines) {
        List<String> lines = new ArrayList<>();
        expandSodium(src, lines);
        lines.addAll(1, defines);
        return String.join("\n", lines);
    }

    private static void expandSodium(String src, List<String> out) {
        for (String line : src.split("\n", -1)) {
            Matcher m = SODIUM_IMPORT.matcher(line);
            if (line.startsWith("#import") && m.matches()) {
                expandSodium(sodiumOriginal(ResourceLocation.fromNamespaceAndPath(m.group(1), m.group(2))), out);
            } else {
                out.add(line);
            }
        }
    }

    /** Sodium の元の中身({@code ShaderLoader.getShaderSource}。型を参照しないよう反射で呼ぶ。include は差し替えないので元のまま返る)。 */
    private static String sodiumOriginal(ResourceLocation name) {
        try {
            Class<?> loader = Class.forName("net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader", false, SodiumTerrain.class.getClassLoader());
            return (String) loader.getMethod("getShaderSource", ResourceLocation.class).invoke(null, name);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Sodium のシェーダー " + name + " を読めない", e);
        }
    }

    @Nullable
    private static String testProgram(String vsh, String fsh) {
        int v = compile(GL20.GL_VERTEX_SHADER, vsh);
        int f = compile(GL20.GL_FRAGMENT_SHADER, fsh);
        int program = 0;
        try {
            String error = log(v);
            if (error != null) {
                return "vsh: " + error;
            }
            error = log(f);
            if (error != null) {
                return "fsh: " + error;
            }
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, v);
            GL20.glAttachShader(program, f);
            GL20.glBindAttribLocation(program, 0, "a_Position");
            GL20.glBindAttribLocation(program, 1, "a_Color");
            GL20.glBindAttribLocation(program, 2, "a_TexCoord");
            GL20.glBindAttribLocation(program, 3, "a_LightAndData");
            GL30.glBindFragDataLocation(program, 0, "fragColor");
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                return "link: " + GL20.glGetProgramInfoLog(program);
            }
            return null;
        } finally {
            if (program != 0) {
                GL20.glDeleteProgram(program);
            }
            GL20.glDeleteShader(v);
            GL20.glDeleteShader(f);
        }
    }

    private static int compile(int type, String src) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, src);
        GL20.glCompileShader(shader);
        return shader;
    }

    @Nullable
    private static String log(int shader) {
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            return GL20.glGetShaderInfoLog(shader);
        }
        return null;
    }

    private static void fail(String message, @Nullable Throwable e) {
        failed = true;
        patchedVsh = null;
        patchedFsh = null;
        if (e != null) {
            OvergrownVegetation.LOGGER.error(message + "。このセッションでは Sodium の元のシェーダーのまま(演出なし)", e);
        } else {
            OvergrownVegetation.LOGGER.error(message + "。このセッションでは Sodium の元のシェーダーのまま(演出なし)");
        }
        // 頂点の印(第 2 段の葉の揺れ・葉の色)を書いたまま組んだチャンクを組み直す(Sodium の元のシェーダーでは印が色に見える)。
        // execute ではなく tell(必ず後回し): ここは Sodium が層を描いている最中(compileProgram の中)なので、その場で allChanged すると
        // Sodium の描画の器が作り直されて「Tried to access device from unmanaged context」で落ちる(10-22 にわざと壊して確かめた)。
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            if (mc.levelRenderer != null) {
                mc.levelRenderer.allChanged();
            }
        });
    }

    // ---- uniform の流し込み(SodiumShaderInterfaceMixin から。Sodium のプログラムが結ばれている) ----

    private record Bindings(String[] names, int[] locations, String[] samplers, int[] samplerLocations, int passLocation) {
        boolean empty() {
            return this.names.length == 0 && this.samplers.length == 0;
        }
    }

    /**
     * 今描いている Sodium の層が半透明か(Mixin {@code SodiumTerrainPassMixin} が層の描き始めに入れ、描き終わりに null に戻す。2026-11-06)。
     * null(知らない)のときだけ GL のブレンドの状態で決める。
     */
    @Nullable
    private static volatile Boolean passTranslucent;

    /** 確かめ用(devbridge {@code wind cavedev blendleak on}・{@code passgl on}、11-06): 層の描き始めで GL のブレンドを直に入れる(ほかの MOD の漏れの再現)/ 前の決め方(ブレンドの状態)に戻す。 */
    public static boolean devBlendLeak;
    public static boolean devPassFromGl;

    public static void notePass(@Nullable Boolean translucent) {
        passTranslucent = translucent;
    }

    /** 今結ばれている Sodium の地形のプログラムへ、この MOD の uniform とテクスチャを流す。 */
    public static void upload() {
        if (patchedVsh == null || failed) {
            return;
        }
        try {
            int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            if (program == 0) {
                return;
            }
            Bindings b;
            synchronized (PROGRAMS) {
                b = PROGRAMS.computeIfAbsent(program, SodiumTerrain::scan);
            }
            if (b.empty()) {
                return;
            }
            ShaderInstance[] sources = {GameRenderer.getRendertypeSolidShader(), GameRenderer.getRendertypeCutoutMippedShader(),
                    GameRenderer.getRendertypeCutoutShader(), GameRenderer.getRendertypeTranslucentShader()};
            for (int i = 0; i < b.names.length; i++) {
                Uniform u = find(sources, b.names[i]);
                if (u != null) {
                    put(b.locations[i], u);
                }
            }
            int previous = GlStateManager._getActiveTexture();
            for (int i = 0; i < b.samplers.length; i++) {
                int unit = 2 + i;
                GL20.glUniform1i(b.samplerLocations[i], unit);
                int id = textureId(sources, b.samplers[i]);
                if (id > 0) {
                    // 11-05(林冠の影が地形に掛かっていなかった件): GlStateManager の覚えている結び先が実際と食い違うことがあり
                    // (ユニット 4〜6 で覚えは「結んである」のに実際は 0)、そのときは _bindTexture が何もしないで終わっていた。
                    // 覚えと実際をそろえたまま必ず結ぶ: 有効なユニットは GL でも直に選び、いったん 0 を結んでから結び直す。
                    GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
                    GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                    GlStateManager._bindTexture(0);
                    GlStateManager._bindTexture(id);
                }
            }
            GlStateManager._activeTexture(previous);
            GL13.glActiveTexture(previous);
            if (b.passLocation >= 0) {
                // 層を知っていればそれで(ブレンドの状態はほかの MOD が GL を直に触ると食い違う。11-06 陸の日光の照り)。
                if (devBlendLeak) {
                    GL11.glEnable(GL11.GL_BLEND);
                }
                Boolean translucent = devPassFromGl ? null : passTranslucent;
                boolean pass = translucent != null ? translucent : GL11.glIsEnabled(GL11.GL_BLEND);
                GL20.glUniform1i(b.passLocation, pass ? 1 : 0);
            }
        } catch (RuntimeException e) {
            fail("Sodium の地形のシェーダーへ uniform を流せなかった", e);
        }
    }

    /** プログラムの中の、名前が Ov で始まる uniform を拾う(sampler は 2 番からのテクスチャに、ほかはバニラのシェーダーから写す)。 */
    private static Bindings scan(int program) {
        List<String> names = new ArrayList<>();
        List<Integer> locations = new ArrayList<>();
        List<String> samplers = new ArrayList<>();
        List<Integer> samplerLocations = new ArrayList<>();
        int count = GL20.glGetProgrami(program, GL20.GL_ACTIVE_UNIFORMS);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer size = stack.mallocInt(1);
            IntBuffer type = stack.mallocInt(1);
            for (int i = 0; i < count; i++) {
                String name = GL20.glGetActiveUniform(program, i, size, type);
                if (!name.startsWith("Ov")) {
                    continue;
                }
                int location = GL20.glGetUniformLocation(program, name);
                if (location < 0) {
                    continue;
                }
                if (type.get(0) == GL20.GL_SAMPLER_2D) {
                    samplers.add(name);
                    samplerLocations.add(location);
                } else {
                    names.add(name);
                    locations.add(location);
                }
            }
        }
        return new Bindings(names.toArray(String[]::new), locations.stream().mapToInt(Integer::intValue).toArray(),
                samplers.toArray(String[]::new), samplerLocations.stream().mapToInt(Integer::intValue).toArray(),
                GL20.glGetUniformLocation(program, "ovSodiumPass"));
    }

    @Nullable
    private static Uniform find(ShaderInstance[] sources, String name) {
        for (ShaderInstance shader : sources) {
            if (shader != null) {
                Uniform u = shader.getUniform(name);
                if (u != null) {
                    return u;
                }
            }
        }
        return null;
    }

    private static void put(int location, Uniform u) {
        int type = u.getType();
        int count = u.getCount();
        if (type <= Uniform.UT_INT4) {
            IntBuffer buffer = u.getIntBuffer().duplicate();
            buffer.position(0).limit(count);
            switch (count) {
                case 1 -> GL20.glUniform1iv(location, buffer);
                case 2 -> GL20.glUniform2iv(location, buffer);
                case 3 -> GL20.glUniform3iv(location, buffer);
                default -> GL20.glUniform4iv(location, buffer);
            }
        } else if (type <= Uniform.UT_FLOAT4) {
            FloatBuffer buffer = u.getFloatBuffer().duplicate();
            buffer.position(0).limit(count);
            switch (count) {
                case 1 -> GL20.glUniform1fv(location, buffer);
                case 2 -> GL20.glUniform2fv(location, buffer);
                case 3 -> GL20.glUniform3fv(location, buffer);
                default -> GL20.glUniform4fv(location, buffer);
            }
        }
    }

    private static int textureId(ShaderInstance[] sources, String name) {
        for (ShaderInstance shader : sources) {
            if (shader == null) {
                continue;
            }
            Map<String, Object> map = ((ShaderInstanceSamplerAccessor) shader).ov$samplerMap();
            Object value = map.get(name);
            if (value instanceof Integer id) {
                return id;
            }
            if (value instanceof AbstractTexture texture) {
                return texture.getId();
            }
        }
        return 0;
    }

    private SodiumTerrain() {
    }
}
