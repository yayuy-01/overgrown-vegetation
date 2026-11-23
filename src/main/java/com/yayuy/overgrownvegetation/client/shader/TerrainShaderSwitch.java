package com.yayuy.overgrownvegetation.client.shader;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownClientConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.fml.ModList;
import net.minecraft.server.packs.PathPackResources;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 段の「なし」で起動したら、地形のシェーダーの差し込みごと外す(2026-11-23、ユーザー決定「素のバニラ・他の MOD と組むとき用」)。
 *
 * <ul>
 *     <li>バニラの地形のシェーダーの差し替え({@code rendertype_solid / cutout / cutout_mipped / translucent})は、MOD の中の内蔵の資源パック
 *     {@code resourcepacks/terrain_shaders} に置き、ここで「いつも有効」のパックとして足す。起動時に「なし」なら足さない(= バニラのシェーダー)。</li>
 *     <li>Sodium 0.8 への差し込み({@link SodiumTerrain})も、起動時に「なし」なら差し込まない。</li>
 * </ul>
 *
 * <p><b>起動時の値で決め、その起動の間は変えない</b>({@link #stripped()} は最初に聞かれたときの値を覚える)。起動の途中で「なし」へ変えても、
 * 差し込みは中立の値のまま残り、次の起動で外れる。なし → 低以上へ戻したら、次の起動で差し込みが戻る(画面の一言は {@code client/RenderTierNotice})。
 * {@code fog.glsl} と {@code ov_*.glsl} は MOD のほかのシェーダーも読むので外さない(霧の印が無ければ fog.glsl はバニラと同じ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TerrainShaderSwitch {

    /** 起動時の「なし」(null = まだ決めていない)。 */
    private static volatile Boolean stripped;

    /** この起動では差し込みを外しているか(起動時に段が「なし」だった)。 */
    public static boolean stripped() {
        Boolean s = stripped;
        if (s == null) {
            s = readNone();
            stripped = s;
            if (s) {
                OvergrownVegetation.LOGGER.info("段「なし」で起動したので、地形のシェーダーの差し替えと Sodium への差し込みを外す");
            }
        }
        return s;
    }

    /** 設定の visualEffects がオフか。設定がまだ読まれていなければ、ファイルを直に読む(資源パックを集めるのは設定より先のことがある)。 */
    private static boolean readNone() {
        try {
            if (OvergrownClientConfig.SPEC.isLoaded()) {
                return !OvergrownClientConfig.CLIENT.visualEffects.get();
            }
        } catch (RuntimeException ignored) {
            // 読む前
        }
        try {
            Path file = FMLPaths.CONFIGDIR.get().resolve(OvergrownVegetation.MODID + "-client.toml");
            if (!Files.isRegularFile(file)) {
                return false;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith("visualEffects")) {
                    int eq = t.indexOf('=');
                    return eq >= 0 && t.substring(eq + 1).trim().startsWith("false");
                }
            }
        } catch (Exception e) {
            OvergrownVegetation.LOGGER.warn("クライアント設定の visualEffects を読めなかった(差し込みは入れる)", e);
        }
        return false;
    }

    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES || stripped()) {
            return;
        }
        // 1.20.1: Forge の AddPackFindersEvent に addPackFinders は無いので、MOD の jar の中のフォルダから「いつも有効」のパックを作って足す
        Path root = ModList.get().getModFileById(OvergrownVegetation.MODID).getFile().findResource("resourcepacks", "terrain_shaders");
        Pack pack = Pack.readMetaAndCreate("builtin/" + OvergrownVegetation.MODID + "_terrain_shaders",
                Component.translatable("pack.overgrown_vegetation.terrain_shaders"), true,
                id -> new PathPackResources(id, root, true), PackType.CLIENT_RESOURCES, Pack.Position.TOP, PackSource.BUILT_IN);
        if (pack == null) {
            OvergrownVegetation.LOGGER.warn("地形のシェーダーの内蔵パック(resourcepacks/terrain_shaders)を読めなかった");
            return;
        }
        event.addRepositorySource(consumer -> consumer.accept(pack));
    }

    private TerrainShaderSwitch() {
    }
}
