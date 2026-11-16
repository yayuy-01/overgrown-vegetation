package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.data.AtmosphereScary;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;

import java.util.HashMap;
import java.util.Map;

/**
 * <b>霧の中のシルエット</b>(「美しいが怖い」E。2026-11-16。ユーザー決定: 濃霧の中に、木ではない影が立っている。<b>近づくと消えて、たどり着けない</b>。
 * 形は<b>G5 の輪郭</b>・<b>動かない</b>・<b>昼と朝だけ</b>。たまに本物の G5 が混ざる(本物を湧かせるのはサーバー側。ここは見た目だけの偽物)。
 *
 * <p>理由(設定): 濃霧({@link DenseFog})の日は見通しが落ち、遠征に危ない朝になる。霧の縁に「何かが立っている」気配を置き、本物が混ざる事で
 * 「どうせ幻だ」と油断させない。
 *
 * <h2>作り</h2>
 * <ul>
 *     <li><b>形</b>: 本物の G5 の立ち姿をそのまま影絵にする。その種の Mob をワールドに入れずに 1 体だけ作り、本物の描き手に描かせて頂点の位置だけを
 *     受け取り({@link #capture}。影 1 体につき 1 回)、暗い 1 色で描く。テクスチャは使わない。厚みの無い板(葉・羽根)は、絵の抜きが無いと
 *     四角い板になるので描かない。素材は増やさない。取れなかったときは、geo の動かす前の姿勢({@link #shape})を使う。
 *     種は本物(サーバーの fogLurker)と同じ: クリペイムネス 2・ファルキムネス 1 の重み。本物(タグ group_5)が近くにいる間は偽の影を出さず、
 *     出ていた影は 5 秒で溶かす(2 つ同時に見せない)。</li>
 *     <li><b>霧</b>: 距離の霞(地形・Mob と同じ式 {@link MistView#fogAmount})の分だけ透けさせる。霧の中の物は距離の霞を受ける決まりどおり。
 *     見通しの縁では霞が 9 割なので、影が受ける距離を {@code hazeEase} 倍に縮めて少しだけ濃く見せる(巨大樹のシルエットと同じ考え)。</li>
 *     <li><b>出る所</b>: 濃霧の度合いが {@code minFog} 以上・昼と朝・空の下で、見ている向きの左右 60° の中、見通しの {@code nearFraction}〜{@code farFraction}
 *     倍の距離の、地面の上・木の幹のそば・目から見通せる所。同時に 1 体。平均 {@code everySeconds} 秒に 1 体。</li>
 *     <li><b>消える</b>: 見通しの {@code vanishFraction} 倍より近づくと霧へ溶け始め、{@code vanishBand} ぶん近づくと消えきる(一度溶けたら戻らない)。
 *     近づかなくても {@code lifeSeconds} 秒で溶ける。霧が晴れても溶ける。当たり判定・音・サーバーとの通信は無い。</li>
 * </ul>
 * 数は design の節 {@code scary.figures}(すべて仮決め)。確かめは {@code /ov fx figure}(今見ている向きに 1 体出す。濃霧は {@code /ov weather fog 1})。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class FogFigures {

    private static final RandomSource RANDOM = RandomSource.create();
    /** 出てから濃くなりきるまで・消えるときの秒。 */
    private static final float FADE_IN = 4.0F;
    private static final float FADE_OUT = 5.0F;

    private static final class Figure {
        double x;
        double y;
        double z;
        float yaw;
        /** 立ち姿の三角形(モデルの座標、ブロック。9 個で 1 枚)。 */
        float[] tris;
        String species;
        /** 出てからの秒・今の濃さ(0〜1)・溶け始めたか。 */
        float age;
        float alpha;
        boolean leaving;
        /** 確かめ用に置いた影(近づいても時間がたっても消えない)。 */
        boolean pinned;
        /** 本物の立ち姿を取り終えたか({@link #capture})。 */
        boolean posed;
    }

    private static Figure figure;
    private static long nextAt = -1L;
    private static int shown;
    private static int vanishedByApproach;
    private static String lastFail = "";
    /** 置けなかった升の数(確かめ用): 幹のそばでない・見通せない。 */
    private static int failTrunk;
    private static int failSight;
    private static boolean broken;
    /** 確かめ用: 霧の度合い・昼夜の条件を見ない(devbridge)。 */
    public static boolean devAlways;
    private static final Map<String, float[]> SHAPES = new HashMap<>();

    private FogFigures() {
    }

    // ------------------------------------------------------------------ 毎 tick

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            figure = null;
            nextAt = -1L;
            return;
        }
        if (mc.isPaused() || broken) {
            return;
        }
        AtmosphereScary.Figures p = DesignData.atmosphere().scary().figures();
        boolean stage = stage(level, p);
        long now = level.getGameTime();
        Figure f = figure;
        if (f != null) {
            float visibility = visibility();
            double d = Math.sqrt(Mth.square(player.getX() - f.x) + Mth.square(player.getZ() - f.z));
            f.age += 0.05F;
            if (f.pinned) {
                f.alpha = 1.0F;
                return;
            }
            if (!f.leaving && d < visibility * p.vanishFraction()) {
                f.leaving = true;
                vanishedByApproach++;
            }
            if (!f.leaving && (f.age > p.lifeSeconds() || !stage || d > visibility * 1.6F)) {
                f.leaving = true;
            }
            // 本物の G5 が近くに現れたら、偽は 5 秒で溶ける(2 つ同時に見せない。11-16 enemy-spawns とのすり合わせ)。
            if (!f.leaving && now % 10L == 0L && realNear(level, player)) {
                f.leaving = true;
            }
            float target;
            if (f.leaving) {
                // 近づいた分だけ溶ける(離れても戻らない)+ 時間でも溶ける
                float byDistance = Mth.clamp((float) ((d / visibility - (p.vanishFraction() - p.vanishBand())) / p.vanishBand()), 0.0F, 1.0F);
                target = Math.min(f.alpha - 0.05F / FADE_OUT, byDistance);
            } else {
                target = Math.min(1.0F, f.alpha + 0.05F / FADE_IN);
            }
            f.alpha = Math.max(0.0F, target);
            if (f.leaving && f.alpha <= 0.001F) {
                figure = null;
                nextAt = now + wait(p);
            }
            return;
        }
        if (!stage || !p.enabled()) {
            nextAt = -1L;
            return;
        }
        if (nextAt < 0L) {
            // 霧に入ってすぐには出さない(半分〜全部の間を置く)
            nextAt = now + wait(p) / 2L;
        }
        if (now >= nextAt && now % 10L == 0L) {
            // 本物の G5 が霧の中に立っている間は、偽の影を休む(本物と偽物を同時に見せない。サーバーとの通信は要らない: 見えている Mob を数えるだけ)。
            if (realNear(level, player)) {
                lastFail = "本物の G5 が近くにいる";
                nextAt = now + 200L;
                return;
            }
            Figure made = place(level, player, p, 8);
            if (made != null) {
                figure = made;
                shown++;
            }
        }
    }

    /** 影を出してよい場面か: 濃霧・昼と朝・空の下・水の外・シェーダーパックなし。 */
    private static boolean stage(ClientLevel level, AtmosphereScary.Figures p) {
        if (devAlways) {
            return true;
        }
        // 昼と朝だけ: サーバーが本物を出す時間帯(dayTime 23000〜12000)と同じにする。
        long time = Math.floorMod(level.getDayTime(), 24000L);
        return DenseFog.amount() >= p.minFog() && (time >= 23000L || time <= 12000L) && ForestPresence.underground() < 0.3F
                && Atmosphere.enabled(level) && !ShaderPacks.active();
    }

    /** 本物の G5(タグ group_5)が、見通しの 1.6 倍の中にいるか。 */
    private static boolean realNear(ClientLevel level, LocalPlayer player) {
        double reach = visibility() * 1.6;
        return !level.getEntities((net.minecraft.world.entity.Entity) null, player.getBoundingBox().inflate(reach, 24.0, reach),
                e -> e.getType().is(com.yayuy.overgrownvegetation.ModTags.GROUP_5)).isEmpty();
    }

    private static long wait(AtmosphereScary.Figures p) {
        // 平均 everySeconds 秒(0.5〜1.5 倍の間でばらつかせる)
        return (long) (p.everySeconds() * 20.0F * (0.5F + RANDOM.nextFloat()));
    }

    /** 今の見通し(ブロック。濃霧の design の値。確かめ用で霧が無いときもこの値を使う)。 */
    private static float visibility() {
        return Math.max(8.0F, DesignData.atmosphere().denseFog().visibility());
    }

    // ------------------------------------------------------------------ 置く

    private static Figure place(ClientLevel level, LocalPlayer player, AtmosphereScary.Figures p, int tries) {
        float visibility = visibility();
        Vec3 eye = player.getEyePosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < tries; i++) {
            double angle = Math.toRadians(player.getYRot() + 90.0F + (RANDOM.nextFloat() - 0.5F) * 120.0F);
            double dist = visibility * Mth.lerp(RANDOM.nextFloat(), p.nearFraction(), Math.max(p.nearFraction(), p.farFraction()));
            double x = eye.x + Math.cos(angle) * dist;
            double z = eye.z + Math.sin(angle) * dist;
            int cx = Mth.floor(x);
            int cz = Mth.floor(z);
            if (!level.hasChunkAt(pos.set(cx, 0, cz))) {
                lastFail = "読み込まれていない";
                continue;
            }
            // 選んだ点のまわり 3 ブロックから、立てる所(地面・幹のそば・見通せる)を探す(本物の置き方と同じ探し方)。
            int bx = 0;
            int bz = 0;
            int ground = Integer.MIN_VALUE;
            String why = "立てる地面が無い";
            search:
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    int g = groundBelow(level, pos, cx + dx, Mth.floor(eye.y) + 6, cz + dz);
                    if (g == Integer.MIN_VALUE || Math.abs(g + 1 - eye.y) > 9.0) {
                        continue;
                    }
                    if (!trunkNear(level, pos, cx + dx, g + 1, cz + dz)) {
                        why = "幹のそばでない";
                        failTrunk++;
                        continue;
                    }
                    Vec3 chest = new Vec3(cx + dx + 0.5, g + 2.2, cz + dz + 0.5);
                    if (level.clip(new ClipContext(eye, chest, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player)).getType() != HitResult.Type.MISS) {
                        why = "見通せない";
                        failSight++;
                        continue;
                    }
                    bx = cx + dx;
                    bz = cz + dz;
                    ground = g;
                    break search;
                }
            }
            if (ground == Integer.MIN_VALUE) {
                lastFail = why;
                continue;
            }
            String species = p.species().get(RANDOM.nextInt(p.species().size()));
            float[] tris = shape(species);
            if (tris == null) {
                lastFail = "形が無い " + species;
                continue;
            }
            Figure f = new Figure();
            f.x = bx + 0.5;
            f.y = ground + 1.0;
            f.z = bz + 0.5;
            // こちらを向いて立つ(少しだけずらす)
            f.yaw = (float) (Math.atan2(eye.x - f.x, eye.z - f.z) + (RANDOM.nextFloat() - 0.5F) * 0.9F);
            f.tris = tris;
            f.species = species;
            lastFail = "";
            return f;
        }
        return null;
    }

    /** (x, z) の列を y0 から下へたどり、上 3 つが空いた固い地面の高さを返す(葉・木の上には立たせない)。無ければ MIN_VALUE。 */
    private static int groundBelow(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int y0, int z) {
        int open = 0;
        for (int y = y0; y > y0 - 20 && y > level.getMinBuildHeight(); y--) {
            BlockState state = level.getBlockState(pos.set(x, y, z));
            // 本物の側(TreeCanopyGroundFinder#findSpawnSurface)と同じ見方: 動きを止めるブロックで、木の一部でない物が地面。
            // (前は「不透明な立方体」だけを地面にしていて、汚染された草ブロックの林床で立てる所が見つからなかった。11-17)
            boolean empty = !state.blocksMotion() && state.getFluidState().isEmpty();
            if (empty) {
                open++;
                continue;
            }
            if (open >= 3 && state.getFluidState().isEmpty() && !state.is(BlockTags.LEAVES)
                    && !com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.isTreePart(state)) {
                return y;
            }
            open = 0;
        }
        return Integer.MIN_VALUE;
    }

    /** 幹のそばの、まわりが空いた地面か(本物の G5 と同じ置き方。開けた所にぽつんと立たせない)。 */
    private static boolean trunkNear(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        // 本物の置き方(サーバーの spawn/FogLurkers#besideTrunk)に寄せた決まり(11-17): まわり 4 方向(足と頭の高さ)が空いていて、
        // 近くに原木がある。幹に接した升には体の幅がはまるので、幹から 1 ブロックは空く。
        // y は足の高さ(地面の 1 つ上)。
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.set(x + d.getStepX(), y, z + d.getStepZ())).blocksMotion()
                    || level.getBlockState(pos.set(x + d.getStepX(), y + 1, z + d.getStepZ())).blocksMotion()) {
                return false;
            }
        }
        // 幹は水平 3 ブロック以内(足か頭の高さ)。本物は「東西南北の 1〜2 ブロック先」だが、同じにすると森の中でも置ける所がほとんど無かった
        // (200 回探して 0)。霧の中の 30 ブロック先では見分けが付かない差なので、ここは緩めてある。
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if ((dx != 0 || dz != 0) && (level.getBlockState(pos.set(x + dx, y, z + dz)).is(BlockTags.LOGS)
                        || level.getBlockState(pos.set(x + dx, y + 1, z + dz)).is(BlockTags.LOGS))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 形(G5 の立ち姿)

    /** 種の立ち姿の三角形(モデルの座標・ブロック)。まだ読み込まれていなければ null(次のときにまた試す)。 */
    private static float[] shape(String species) {
        float[] cached = SHAPES.get(species);
        if (cached != null) {
            return cached;
        }
        ResourceLocation id = OvergrownVegetation.id("geo/" + species + ".geo.json");
        BakedGeoModel model = GeckoLibCache.getBakedModels().get(id);
        if (model == null) {
            return null;
        }
        it.unimi.dsi.fastutil.floats.FloatArrayList out = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        for (GeoBone bone : model.topLevelBones()) {
            walk(bone, new Matrix4f(), out);
        }
        if (out.isEmpty()) {
            return null;
        }
        float[] tris = out.toFloatArray();
        float scale = renderScale(species);
        for (int i = 0; i < tris.length; i++) {
            tris[i] *= scale;
        }
        SHAPES.put(species, tris);
        return tris;
    }

    /** 本物の描画の倍率(種の Entity クラスの RENDER_SCALE。無ければ 1)。本物と同じ大きさの影にする。 */
    private static float renderScale(String species) {
        try {
            String name = Character.toUpperCase(species.charAt(0)) + species.substring(1) + "Entity";
            return Class.forName("com.yayuy.overgrownvegetation.entity." + name).getField("RENDER_SCALE").getFloat(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 1.0F;
        }
    }

    /** 骨をたどって、動かす前の姿勢(読み込んだときの回転)で箱の面を三角形にする。GeckoLib の描き方(RenderUtil.prepMatrixForBone)と同じ順。 */
    private static void walk(GeoBone bone, Matrix4f parent, it.unimi.dsi.fastutil.floats.FloatArrayList out) {
        BoneSnapshot rest = bone.getInitialSnapshot();
        float rx = rest != null ? rest.getRotX() : bone.getRotX();
        float ry = rest != null ? rest.getRotY() : bone.getRotY();
        float rz = rest != null ? rest.getRotZ() : bone.getRotZ();
        float px = bone.getPivotX() / 16.0F;
        float py = bone.getPivotY() / 16.0F;
        float pz = bone.getPivotZ() / 16.0F;
        Matrix4f m = new Matrix4f(parent).translate(px, py, pz).rotateZ(rz).rotateY(ry).rotateX(rx).translate(-px, -py, -pz);
        Vector3f v = new Vector3f();
        for (GeoCube cube : bone.getCubes()) {
            Vec3 size = cube.size();
            if (Math.min(size.x, Math.min(size.y, size.z)) < 0.02) {
                continue; // 厚みの無い板(葉・羽根): 絵の抜きが無いと四角い板になる
            }
            float cx = (float) cube.pivot().x / 16.0F;
            float cy = (float) cube.pivot().y / 16.0F;
            float cz = (float) cube.pivot().z / 16.0F;
            Matrix4f c = new Matrix4f(m).translate(cx, cy, cz).rotateZ((float) cube.rotation().z).rotateY((float) cube.rotation().y)
                    .rotateX((float) cube.rotation().x).translate(-cx, -cy, -cz);
            for (GeoQuad quad : cube.quads()) {
                if (quad == null) {
                    continue;
                }
                GeoVertex[] q = quad.vertices();
                int[] order = {0, 1, 2, 0, 2, 3};
                for (int k : order) {
                    c.transformPosition(q[k].position(), v);
                    out.add(v.x);
                    out.add(v.y);
                    out.add(v.z);
                }
            }
        }
        for (GeoBone child : bone.getChildBones()) {
            walk(child, m, out);
        }
    }

    // ------------------------------------------------------------------ 形(本物の立ち姿をなぞる)

    private static int captureFails;
    /** 形を取る前に進める tick(本物の姿勢が落ち着くまで。2 秒)。 */
    private static final int SETTLE_TICKS = 40;

    /**
     * 本物の<b>立ち姿</b>を取る(11-17。動かす前の姿勢は、アニメと脚の仕掛けが乗った本物の立ち姿と違っていた)。その種の Mob をワールドに入れずに
     * 1 体だけ作り、影の場所に置いて、<b>本物の描き手に 1 回だけ描かせ</b>、頂点の位置だけを受け取る(色・絵は捨てる)。受け取った面のうち、
     * 面積の無い面と、厚みの無い板(同じ場所に表と裏が重なる面。葉・羽根は絵の抜きが無いと四角い板になる)は捨てる。同じ向きで重なった面
     * (光る層などの描き直し)は 1 枚にする。取るのは影 1 体につき 1 回だけで、あとは止まったまま(動かない影)。描画のスレッドから呼ぶ。
     * 返すのは影の足元を原点にした三角形(9 個で 1 枚)。取れなければ null。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static float[] capture(Figure f) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || captureFails >= 3) {
            return null;
        }
        try {
            net.minecraft.world.entity.EntityType<?> type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getOptional(OvergrownVegetation.id(f.species)).orElse(null);
            net.minecraft.world.entity.Entity entity = type == null ? null : type.create(level);
            if (entity == null) {
                return null;
            }
            // 向きは最初から本物と同じに(影の向き f.yaw は「前 = (sin, cos)」のラジアン。Mob の向きは度で、前 = (−sin, cos))。
            // 向きを 0 で取って後から回すと、脚を地面へ合わせる仕掛けが別の向きの地面を見て、斜面で体が傾いた(11-17)。
            float yawDeg = (float) -Math.toDegrees(f.yaw);
            entity.moveTo(f.x, f.y, f.z, yawDeg, 0.0F);
            entity.setOldPosAndRot();
            entity.setYHeadRot(yawDeg);
            entity.setYBodyRot(yawDeg);
            if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
                living.yBodyRotO = yawDeg;
                living.yHeadRotO = yawDeg;
            }
            entity.setOnGround(true);
            entity.setNoGravity(f.pinned);
            net.minecraft.client.renderer.entity.EntityRenderer renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
            // 本物は、立ってからしばらくかけて姿勢が落ち着く(アニメの頭と、脚・体の仕掛けのならし)。同じだけ進めてから形を取る:
            // 2 秒ぶん、時計を 1 tick 進めては 1 回描かせる(絵は捨てる)。Mob の tick は呼ばない(ワールドの外の個体で粒や音を出さない。
            // 撮り比べで、tick の有り無しで形は変わらなかった)。
            for (int i = 0; i < SETTLE_TICKS; i++) {
                entity.tickCount++;
                renderer.render(entity, yawDeg, 1.0F, new com.mojang.blaze3d.vertex.PoseStack(), type1 -> new Catcher(), 0xF000F0);
            }
            Catcher catcher = new Catcher();
            renderer.render(entity, yawDeg, 1.0F, new com.mojang.blaze3d.vertex.PoseStack(), type1 -> catcher, 0xF000F0);
            entity.discard();
            float[] posed = catcher.triangles();
            if (posed != null && posed.length >= 9) {
                // 形にはもう向きが入っている(描くときは回さない)。
                f.yaw = 0.0F;
            }
            return posed;
        } catch (RuntimeException e) {
            captureFails++;
            OvergrownVegetation.LOGGER.warn("霧の中のシルエット: {} の立ち姿を取れなかった(動かす前の姿勢を使う)", f.species, e);
            return null;
        }
    }

    /** 描き手から頂点の位置と面の向きだけを受け取る口(4 つで 1 面)。 */
    private static final class Catcher implements com.mojang.blaze3d.vertex.VertexConsumer {
        private final it.unimi.dsi.fastutil.floats.FloatArrayList quads = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        private final it.unimi.dsi.fastutil.floats.FloatArrayList normals = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        private int vertices;

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
            this.quads.add(x);
            this.quads.add(y);
            this.quads.add(z);
            this.vertices++;
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) {
            if (this.vertices % 4 == 0 && this.vertices > 0 && this.normals.size() < this.vertices / 4 * 3) {
                this.normals.add(x);
                this.normals.add(y);
                this.normals.add(z);
            }
            return this;
        }

        float[] triangles() {
            int n = this.vertices / 4;
            float[] q = this.quads.elements();
            boolean hasNormals = this.normals.size() >= n * 3;
            float[] nr = this.normals.elements();
            boolean[] drop = new boolean[n];
            // 面の真ん中と向きを出し、面積の無い面を捨てる
            float[] mid = new float[n * 3];
            float[] nor = new float[n * 3];
            java.util.HashMap<Long, it.unimi.dsi.fastutil.ints.IntArrayList> cells = new java.util.HashMap<>();
            for (int i = 0; i < n; i++) {
                int o = i * 12;
                float ax = q[o + 3] - q[o];
                float ay = q[o + 4] - q[o + 1];
                float az = q[o + 5] - q[o + 2];
                float bx = q[o + 9] - q[o];
                float by = q[o + 10] - q[o + 1];
                float bz = q[o + 11] - q[o + 2];
                float cx = ay * bz - az * by;
                float cy = az * bx - ax * bz;
                float cz = ax * by - ay * bx;
                float area = Mth.sqrt(cx * cx + cy * cy + cz * cz);
                if (area < 1.0E-5F) {
                    drop[i] = true;
                    continue;
                }
                for (int k = 0; k < 3; k++) {
                    mid[i * 3 + k] = (q[o + k] + q[o + 3 + k] + q[o + 6 + k] + q[o + 9 + k]) * 0.25F;
                }
                if (hasNormals) {
                    nor[i * 3] = nr[i * 3];
                    nor[i * 3 + 1] = nr[i * 3 + 1];
                    nor[i * 3 + 2] = nr[i * 3 + 2];
                } else {
                    nor[i * 3] = cx / area;
                    nor[i * 3 + 1] = cy / area;
                    nor[i * 3 + 2] = cz / area;
                }
                long key = (long) Math.round(mid[i * 3] * 64.0F) * 73856093L ^ (long) Math.round(mid[i * 3 + 1] * 64.0F) * 19349663L
                        ^ (long) Math.round(mid[i * 3 + 2] * 64.0F) * 83492791L;
                cells.computeIfAbsent(key, k -> new it.unimi.dsi.fastutil.ints.IntArrayList()).add(i);
            }
            // 同じ場所に重なった面: 向きが逆なら板の表と裏(どちらも捨てる)、同じ向きなら描き直し(後の方を捨てる)
            for (it.unimi.dsi.fastutil.ints.IntArrayList same : cells.values()) {
                for (int a = 0; a < same.size(); a++) {
                    for (int b = a + 1; b < same.size(); b++) {
                        int i = same.getInt(a);
                        int j = same.getInt(b);
                        float d = Math.abs(mid[i * 3] - mid[j * 3]) + Math.abs(mid[i * 3 + 1] - mid[j * 3 + 1]) + Math.abs(mid[i * 3 + 2] - mid[j * 3 + 2]);
                        if (d > 0.004F) {
                            continue;
                        }
                        float dot = nor[i * 3] * nor[j * 3] + nor[i * 3 + 1] * nor[j * 3 + 1] + nor[i * 3 + 2] * nor[j * 3 + 2];
                        if (dot < -0.9F) {
                            drop[i] = true;
                            drop[j] = true;
                        } else if (dot > 0.9F) {
                            drop[j] = true;
                        }
                    }
                }
            }
            it.unimi.dsi.fastutil.floats.FloatArrayList out = new it.unimi.dsi.fastutil.floats.FloatArrayList();
            int[] order = {0, 1, 2, 0, 2, 3};
            for (int i = 0; i < n; i++) {
                if (drop[i]) {
                    continue;
                }
                for (int k : order) {
                    out.add(q[i * 12 + k * 3]);
                    out.add(q[i * 12 + k * 3 + 1]);
                    out.add(q[i * 12 + k * 3 + 2]);
                }
            }
            return out.toFloatArray();
        }
    }

    // ------------------------------------------------------------------ 描く

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        Figure f = figure;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || f == null || f.alpha <= 0.002F || broken
                || event.getCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }
        try {
            draw(event, f);
        } catch (RuntimeException e) {
            broken = true;
            figure = null;
            OvergrownVegetation.LOGGER.error("霧の中のシルエットで例外が出たので、このセッションでは出さない", e);
        }
    }

    private static void draw(RenderLevelStageEvent event, Figure f) {
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        if (shader == null) {
            return;
        }
        AtmosphereScary.Figures p = DesignData.atmosphere().scary().figures();
        Vec3 cam = event.getCamera().getPosition();
        float sin = Mth.sin(f.yaw);
        float cos = Mth.cos(f.yaw);
        float ox = (float) (f.x - cam.x);
        float oy = (float) (f.y - cam.y);
        float oz = (float) (f.z - cam.z);
        // 影は 1 体まるごと同じ濃さ(真ん中の距離の霞)。部分ごとに変えると、遠い側の手足だけ薄くなって立体に見える。
        float dist = Mth.sqrt(ox * ox + (oy + 1.5F) * (oy + 1.5F) + oz * oz);
        float alpha = f.alpha * p.darkness() * (1.0F - (devAlways && DenseFog.amount() < 0.01F ? 0.0F : MistView.fogAmount(dist * p.hazeEase())));
        if (alpha <= 0.003F) {
            return;
        }
        float night = Minecraft.getInstance().level != null ? SunLight.night(Minecraft.getInstance().level, 1.0F) : 0.0F;
        float bright = Mth.lerp(night, 1.0F, 0.3F);
        if (!f.posed) {
            // 最初の 1 回だけ、本物の描き方をなぞって立ち姿を取る(失敗したら、動かす前の姿勢のまま)。
            f.posed = true;
            float[] posed = capture(f);
            if (posed != null && posed.length >= 9) {
                f.tris = posed;
            }
        }
        float[] t = f.tris;
        Matrix4f modelView = new Matrix4f(event.getModelViewMatrix());
        Matrix4f projection = new Matrix4f(event.getProjectionMatrix());
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        // 1 回目: 奥行きだけを書く。2 回目: 奥行きが同じ所(いちばん手前の面)だけに色を重ねる。重なった手足が二重に濃くならない。
        for (int pass = 0; pass < 2; pass++) {
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            for (int i = 0; i < t.length; i += 3) {
                float x = t[i];
                float y = t[i + 1];
                float z = t[i + 2];
                builder.addVertex(ox + x * cos + z * sin, oy + y, oz - x * sin + z * cos).setColor(p.red() * bright, p.green() * bright, p.blue() * bright, alpha);
            }
            MeshData mesh = builder.build();
            if (mesh == null) {
                break;
            }
            if (pass == 0) {
                RenderSystem.colorMask(false, false, false, false);
                RenderSystem.depthMask(true);
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
                RenderSystem.disableBlend();
            } else {
                RenderSystem.colorMask(true, true, true, true);
                RenderSystem.depthMask(false);
                RenderSystem.depthFunc(GL11.GL_EQUAL);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
            }
            shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, modelView, projection, Minecraft.getInstance().getWindow());
            shader.apply();
            BufferUploader.draw(mesh);
            shader.clear();
        }
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    // ------------------------------------------------------------------ 確かめ用

    /**
     * 確かめ用({@code /ov fx figure}・devbridge): 今いる所から 1 体出す。species が空なら種はくじ。distance > 0 なら、見ている向きのまっすぐ先の
     * その距離へ、地面・幹・見通しの条件を見ずに置く(形の確かめ用)。
     */
    public static String force(String species, double distance) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return "ワールドの中で";
        }
        AtmosphereScary.Figures p = DesignData.atmosphere().scary().figures();
        Figure made;
        if (distance > 0.0) {
            String s = species == null || species.isEmpty() ? p.species().get(RANDOM.nextInt(p.species().size())) : species;
            float[] tris = shape(s);
            if (tris == null) {
                return "形が無い(まだ読み込まれていない種か、名前違い): " + s;
            }
            Vec3 look = mc.player.getLookAngle();
            double len = Math.max(0.001, Math.sqrt(look.x * look.x + look.z * look.z));
            made = new Figure();
            made.x = mc.player.getX() + look.x / len * distance;
            made.z = mc.player.getZ() + look.z / len * distance;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int ground = groundBelow(mc.level, pos, Mth.floor(made.x), Mth.floor(mc.player.getEyeY()) + 6, Mth.floor(made.z));
            made.y = ground == Integer.MIN_VALUE ? mc.player.getY() : ground + 1.0;
            made.yaw = (float) Math.atan2(mc.player.getX() - made.x, mc.player.getZ() - made.z);
            made.tris = tris;
            made.species = s;
            made.alpha = 1.0F;
            made.pinned = true;
        } else {
            made = place(mc.level, mc.player, p, 200);
            if (made == null) {
                return "置ける所が無かった(" + lastFail + ")";
            }
        }
        figure = made;
        shown++;
        return describe();
    }

    /** 今の影を消す(確かめ用)。 */
    public static void clear() {
        figure = null;
    }

    /** 確かめ用の 1 行。 */
    public static String describe() {
        Figure f = figure;
        Minecraft mc = Minecraft.getInstance();
        String now = "なし";
        if (f != null && mc.player != null) {
            now = String.format("%s (%.1f, %.1f, %.1f) 距離 %.1f 濃さ %.2f%s 三角 %d", f.species, f.x, f.y, f.z,
                    Math.sqrt(Mth.square(mc.player.getX() - f.x) + Mth.square(mc.player.getZ() - f.z)), f.alpha, f.leaving ? " 溶けている" : "", f.tris.length / 9);
        }
        return String.format("霧の影 %s(これまで %d 体・近づいて消えた %d・置けなかった理由 %s・外した升 幹 %d 見通し %d)", now, shown, vanishedByApproach,
                lastFail.isEmpty() ? "なし" : lastFail, failTrunk, failSight);
    }
}
