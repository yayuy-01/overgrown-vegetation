package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>幹を伝う水</b>(一覧 41。設定: 熱帯の雨は葉から幹へ集まって流れ落ちる = 樹幹流)。2026-11-01 に作り直した(側面・底面の段、ユーザー「もう一度」)。
 * <p>前の作り(10-31。11-01 に U12 で parked へ退避)は、テクスチャの無い色だけの細い帯を CPU で蛇行させ、明るい粒を流し、道を世代で入れ替えていた。
 * 一晩で 4 回直したのち削除になった(理由は記録に無い)。今度は<b>絵を描いて流すだけ</b>にした:
 * <ul>
 *     <li>下絵の水の筋({@code textures/entity/trunk_water_streak.png}、6×32・半透明)を、幹の面に貼る細い板に縦に繰り返し、V をずらして下へ流す
 *     (1 画素 = 1/16 ブロックで地形と同じ細かさ。蛇行と滴は絵の中にある)。</li>
 *     <li>雨の間と雨上がり(濡れの時計 {@link MistFog#wetAfter}。止んで 15 秒で消える。照り {@link WetSheen} の節 {@code wet} とは別の {@link #AFTER_RAIN_MINUTES})。</li>
 *     <li>幹 1 本に {@link #MIN_PER_TREE}〜{@link #MAX_PER_TREE} 筋、空気に面した側面に、面から {@link #LIFT} 浮かせる。</li>
 * </ul>
 * 値はこのクラスの定数(仮決め。design json には戻していない)。設定画面の「水面の演出」({@code waterLife})で一緒に切れる。
 */
public final class TrunkRivulets {

    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/trunk_water_streak.png");
    /** 幹を探す半径(ブロック)。 */
    private static final int RADIUS = 16;
    /** 筋を付ける幹の数の上限。 */
    private static final int MAX_TREES = 40;
    private static final int MIN_PER_TREE = 1;
    private static final int MAX_PER_TREE = 3;
    /** 筋の板の幅(絵の 6 画素 = 6/16 ブロック)と、絵 1 枚の高さ(32 画素 = 2 ブロック)。 */
    private static final float WIDTH = 6.0F / 16.0F;
    private static final float TILE = 2.0F;
    /** 面から浮かせる量(1/32 ブロック)。 */
    private static final float LIFT = 1.0F / 32.0F;
    /** 流れる速さ(ブロック/秒)。10-31 ユーザー「速すぎる」の後の 0.45 の前後。 */
    private static final float SPEED_MIN = 0.35F;
    private static final float SPEED_MAX = 0.5F;
    /** 上下の端で薄れる長さ(ブロック)。 */
    private static final float FADE = 0.5F;
    /** 筋の長さの上限(根元から何段まで。目に入る幹の下の方。11-01 の確かめで「幹の上から 10 段」だと目線より上にしか出なかったので根元から)。 */
    private static final int MAX_LENGTH = 12;
    /** 雨上がりに流れが消えるまで(分)。15 秒(2026-11-06 ユーザー「3 分は長すぎる、15 秒くらい」)。葉の照り(節 wet)とは別にした。 */
    private static final float AFTER_RAIN_MINUTES = 0.25F;
    /** 1 tick に幹を探す列の数(3 では雨の 10 秒で 0〜5 筋しか見つからなかった)。 */
    private static final int TRIES_PER_TICK = 12;

    private static final RandomSource RANDOM = RandomSource.create();
    private static final List<Streak> STREAKS = new ArrayList<>();
    private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    private static float wet;

    /** 1 筋。原木の列 (x, z)・面の向き・面の中の位置(-0.5〜0.5)・上と下の端・速さ・絵の位相。 */
    private record Streak(int x, int z, Direction side, float along, double top, double bottom, float speed, float phase) {
    }

    /**
     * 幹の根元の水たまり(2026-11-06 雨の見え方。ユーザー提案「幹から水が垂れている所の地面に水が溜まって見える」)。本物の水ではなく見た目だけ
     * (遊びに影響しない、仮決め)。筋の根元の地面に、不規則な形の暗い濡れた斑を貼り、斜めから見るほど空の明るさがうっすら映る。
     * 雨の間に {@code rainLook.puddleIn} 秒で広がり、雨上がりは {@code puddleOut} 秒で乾く(幹の水の 15 秒より長く残る)。
     * 中心 (cx, cz)・地面の高さ y・形の種・大きさの倍率。
     */
    private record Puddle(int x, int z, double cx, double cz, double y, float seed, float size, boolean rain) {
    }

    private static final List<Puddle> PUDDLES = new ArrayList<>();
    /** 水たまりの広がり(0〜1)。 */
    private static float puddle;
    /** 水たまりの縁の角の数。 */
    private static final int PUDDLE_SEGMENTS = 14;
    /** 水たまりの、同じ濃さで塗る内側の半径の割合(外側は縁へ薄れる。仮決め)。 */
    private static final float PUDDLE_PLATEAU = 0.6F;
    /** 確かめ用: 前のフレームに描いた水たまりの数と、最後の 1 つの不透明さ。 */
    private static int drawnPuddles;
    private static float drawnAlpha;

    static void clear() {
        STREAKS.clear();
        PUDDLES.clear();
        wet = 0.0F;
        puddle = 0.0F;
    }

    /**
     * 確かめ用(devbridge {@code wind cavedev puddleview <trunk|rain> <高さ>}、11-06 夜): その種類の水たまりのうち、遮られずに見える視点を探して
     * 「x y z yaw pitch」で返す。目の位置は水たまりから高さ {@code rise}・水平 2〜4 ブロックの 8 方向、視線が水たまりの中心まで何にも当たらない所。
     */
    static String viewPoint(ClientLevel level, boolean rain, double rise) {
        net.minecraft.world.entity.Entity cam = Minecraft.getInstance().getCameraEntity();
        for (Puddle p : PUDDLES) {
            if (p.rain != rain) {
                continue;
            }
            Vec3 target = new Vec3(p.cx, p.y + 0.02, p.cz);
            for (double dist = 2.0; dist <= 4.0; dist += 1.0) {
                for (int k = 0; k < 8; k++) {
                    double a = k * Math.PI / 4.0;
                    Vec3 eye = target.add(Math.cos(a) * dist, rise, Math.sin(a) * dist);
                    if (!level.getBlockState(BlockPos.containing(eye)).isAir()) {
                        continue;
                    }
                    var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, target, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                            net.minecraft.world.level.ClipContext.Fluid.NONE, cam));
                    if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS && hit.getLocation().distanceTo(target) > 0.3) {
                        continue;
                    }
                    Vec3 d = target.subtract(eye);
                    float yaw = (float) (Math.toDegrees(Math.atan2(-d.x, d.z)));
                    float pitch = (float) (Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
                    // tp は足の位置: 目の高さ 1.62(観戦モード)を引く。
                    return String.format("%.2f %.2f %.2f %.1f %.1f", eye.x, eye.y - 1.62, eye.z, yaw, pitch);
                }
            }
        }
        return "なし";
    }

    static String describe() {
        String first = "";
        for (boolean rain : new boolean[]{false, true}) {
            for (Puddle p : PUDDLES) {
                if (p.rain == rain) {
                    first += String.format(" %s %.1f %.2f %.1f", rain ? "雨の例" : "幹の例", p.cx, p.y, p.cz);
                    break;
                }
            }
        }
        return String.format("幹の水 %.2f 筋 %d 水たまり %.2f %d 描いた %d 不透明 %.2f", wet, STREAKS.size(), puddle, PUDDLES.size(), drawnPuddles, drawnAlpha) + first;
    }

    static void tick(ClientLevel level, LocalPlayer player) {
        if (!VisualQuality.waterLife()) {
            clear();
            return;
        }
        wet = MistFog.wetAfter(AFTER_RAIN_MINUTES, level.getRainLevel(1.0F));
        double r2 = (double) RADIUS * RADIUS;
        STREAKS.removeIf(s -> {
            double dx = s.x + 0.5 - player.getX();
            double dz = s.z + 0.5 - player.getZ();
            return dx * dx + dz * dz > r2 * 1.5;
        });
        // 水たまり: 雨の間は広がり、雨上がりは乾く(1 tick = 0.05 秒)。乾ききったら消す。
        var look = com.yayuy.overgrownvegetation.data.DesignData.atmosphere().rainLook();
        boolean raining = level.getRainLevel(1.0F) > 0.01F;
        puddle = raining ? Math.min(1.0F, puddle + 0.05F / look.puddleIn()) : Math.max(0.0F, puddle - 0.05F / look.puddleOut());
        PUDDLES.removeIf(p -> {
            double dx = p.cx - player.getX();
            double dz = p.cz - player.getZ();
            return puddle <= 0.0F || dx * dx + dz * dz > r2 * 1.5;
        });
        if (raining && look.enabled()) {
            tryRainPuddles(level, player, look);
        }
        if (wet <= 0.01F) {
            STREAKS.clear();
            return;
        }
        if (countTrees() >= MAX_TREES) {
            return;
        }
        // 1 tick に TRIES_PER_TICK 列ずつ幹を探す。
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int tries = 0; tries < TRIES_PER_TICK; tries++) {
            int x = Mth.floor(player.getX() + (RANDOM.nextDouble() * 2.0 - 1.0) * RADIUS);
            int z = Mth.floor(player.getZ() + (RANDOM.nextDouble() * 2.0 - 1.0) * RADIUS);
            if (!level.hasChunkAt(pos.set(x, Mth.floor(player.getY()), z)) || hasTree(x, z)) {
                continue;
            }
            int y0 = Mth.floor(player.getY()) + 12;
            int top = Integer.MIN_VALUE;
            int bottom = Integer.MIN_VALUE;
            for (int y = y0; y > y0 - 24; y--) {
                boolean log = level.getBlockState(pos.set(x, y, z)).is(BlockTags.LOGS);
                if (log && top == Integer.MIN_VALUE) {
                    top = y;
                }
                if (!log && top != Integer.MIN_VALUE) {
                    bottom = y + 1;
                    break;
                }
            }
            if (top == Integer.MIN_VALUE || bottom == Integer.MIN_VALUE || top - bottom < 2
                    || !ModBiomes.isOvergrownLike(level.getBiome(pos.set(x, bottom, z)))) {
                continue;
            }
            double upper = Math.min(top + 1.0, bottom + MAX_LENGTH);
            int n = MIN_PER_TREE + RANDOM.nextInt(MAX_PER_TREE - MIN_PER_TREE + 1);
            for (int k = 0; k < n; k++) {
                Direction side = SIDES[RANDOM.nextInt(4)];
                // 空気に面した側だけ(隣も幹・下草の板でも塞がっていれば見えない)。根元の段で見る。
                if (level.getBlockState(pos.set(x + side.getStepX(), bottom, z + side.getStepZ())).blocksMotion()) {
                    continue;
                }
                float along = (RANDOM.nextFloat() - 0.5F) * (1.0F - WIDTH - 0.1F);
                STREAKS.add(new Streak(x, z, side, along, upper - RANDOM.nextFloat() * 0.5, bottom,
                        Mth.lerp(RANDOM.nextFloat(), SPEED_MIN, SPEED_MAX), RANDOM.nextFloat()));
                // 筋の根元の地面に水たまり(幹 1 本に 1 つ)。11-06 夕方 ユーザー「全く見えなかった」: 幹の隣のマスは下草で埋まっていることが多く、
                // 下に描いても見えない。見える地面(そのマスが空気で、下が固い同じ高さの地面)を、筋の側の隣 → その次 → ほかの 3 方向の隣 → その次の順に探す。
                if (!hasPuddle(x, z)) {
                    double[] cell = openGround(level, pos, x, z, bottom, side);
                    if (cell != null) {
                        PUDDLES.add(new Puddle(x, z, cell[0] + 0.5 + (RANDOM.nextFloat() - 0.5F) * 0.3, cell[1] + 0.5 + (RANDOM.nextFloat() - 0.5F) * 0.3,
                                cell[2], RANDOM.nextFloat() * 100.0F, 0.75F + RANDOM.nextFloat() * 0.5F, false));
                    }
                }
            }
        }
    }

    /**
     * 雨でできる水たまり(2026-11-06 夜 ユーザー「雨によって水たまりができるようにできないか。幹を伝う水の下側にできるやつを使って」)。
     * 幹の根元の水たまりと同じ一覧・描き方・時計で、雨の当たる地面(高さの地図の一番上のマス = 真上が空)に置く。世界全体(過繁茂の中も外も)。
     * 置く所(仮決め): 4 方向の隣の地面が同じ高さか高い所(平らな所・くぼみ)、地面が砂・砂利・雪・氷でない所(水が抜ける・凍る)、雨の降るバイオーム。
     * 苔の敷物・落ち葉など薄い層の上にも描く({@link #surfaceAt})。葉の上(林冠の上)には置かない。数はカメラのまわり {@code rainPuddleMax} まで、
     * 互いに {@code rainPuddleSpacing} ブロック離す。1 tick に {@code rainPuddleTries} 列を見る。
     */
    private static void tryRainPuddles(ClientLevel level, LocalPlayer player, com.yayuy.overgrownvegetation.data.AtmosphereLife.RainLookParams look) {
        int count = 0;
        for (Puddle p : PUDDLES) {
            if (p.rain) {
                count++;
            }
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double spacing2 = (double) look.rainPuddleSpacing() * look.rainPuddleSpacing();
        for (int tries = 0; tries < look.rainPuddleTries() && count < look.rainPuddleMax(); tries++) {
            int x = Mth.floor(player.getX() + (RANDOM.nextDouble() * 2.0 - 1.0) * RADIUS);
            int z = Mth.floor(player.getZ() + (RANDOM.nextDouble() * 2.0 - 1.0) * RADIUS);
            if (!level.hasChunkAt(pos.set(x, Mth.floor(player.getY()), z))) {
                continue;
            }
            int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
            var ground = level.getBlockState(pos.set(x, top - 1, z));
            // 木の上(林冠・枝・過繁茂の葉。過繁茂の葉はバニラの葉のタグに入っていない)には置かない。
            if (ground.is(BlockTags.LEAVES) || ground.is(BlockTags.LOGS) || com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.isTreePart(ground)
                    || ground.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_LEAVES.get()) || ground.is(BlockTags.SAND) || ground.is(net.minecraft.world.level.block.Blocks.GRAVEL)
                    || ground.is(BlockTags.SNOW) || ground.is(BlockTags.ICE)
                    || level.getBiome(pos.set(x, top, z)).value().getPrecipitationAt(pos) != net.minecraft.world.level.biome.Biome.Precipitation.RAIN) {
                continue;
            }
            // 平らな所・くぼみ: 4 方向の隣の一番上が同じ高さか高い(斜面の途中・段の縁には溜まらない)。
            boolean low = true;
            for (Direction d : SIDES) {
                if (level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x + d.getStepX(), z + d.getStepZ()) < top) {
                    low = false;
                    break;
                }
            }
            if (!low) {
                continue;
            }
            // 土の道・耕地は高さ 15/16 で上の面が「固い」扱いにならないので、その面の上にじかに描く(村の道の水たまり)。
            double surface = ground.is(net.minecraft.world.level.block.Blocks.DIRT_PATH) || ground.is(net.minecraft.world.level.block.Blocks.FARMLAND)
                    ? top - 1 + ground.getShape(level, pos.set(x, top - 1, z)).max(Direction.Axis.Y)
                    : surfaceAt(level, pos, x, top, z);
            if (Double.isNaN(surface)) {
                continue;
            }
            double cx = x + 0.5 + (RANDOM.nextFloat() - 0.5F) * 0.4;
            double cz = z + 0.5 + (RANDOM.nextFloat() - 0.5F) * 0.4;
            boolean near = false;
            for (Puddle p : PUDDLES) {
                double dx = p.cx - cx;
                double dz = p.cz - cz;
                if (dx * dx + dz * dz < spacing2) {
                    near = true;
                    break;
                }
            }
            if (near) {
                continue;
            }
            PUDDLES.add(new Puddle(x, z, cx, cz, surface, RANDOM.nextFloat() * 100.0F, 0.75F + RANDOM.nextFloat() * 0.5F, true));
            count++;
        }
    }

    private static int countTrees() {
        int n = 0;
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (Streak s : STREAKS) {
            if (s.x != lastX || s.z != lastZ) {
                n++;
                lastX = s.x;
                lastZ = s.z;
            }
        }
        return n;
    }

    /** 水たまりを上に描いてよい薄い層の高さの上限(苔の敷物・落ち葉・雪でない薄い覆い。背の高い草・シダは描かない)。 */
    private static final double LAYER_MAX = 0.3;

    /**
     * マス (x, y, z) の上の面に水たまりを描けるなら、その面の高さ(y + 薄い層の高さ)。描けなければ NaN。
     * 11-06 夜 ユーザー「今も全く見えない」: 過繁茂の林床は苔の敷物・落ち葉などの薄い層で覆われ、「そのマスが空気」の条件ではほぼ置けなかった。
     * 空気か、形の高さが {@link #LAYER_MAX} 以下の薄い層(水を含まない)なら、その上の面に描く。下のマスの上の面が固いこと。
     */
    private static double surfaceAt(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        net.minecraft.world.level.block.state.BlockState s = level.getBlockState(pos.set(x, y, z));
        double h = 0.0;
        if (!s.isAir()) {
            if (!s.getFluidState().isEmpty()) {
                return Double.NaN;
            }
            var shape = s.getShape(level, pos);
            h = shape.isEmpty() ? 0.0 : shape.max(Direction.Axis.Y);
            if (h > LAYER_MAX) {
                return Double.NaN;
            }
        }
        if (!level.getBlockState(pos.set(x, y - 1, z)).isFaceSturdy(level, pos, Direction.UP)) {
            return Double.NaN;
        }
        return y + h;
    }

    /**
     * 幹 (x, z) のまわりで、水たまりを置ける見える地面({x, z, 面の高さ})。高さは幹の根元 {@code bottom} の段({@link #surfaceAt})。
     * 筋の側 {@code first} を先に、隣(1 マス)→ その次(2 マス)の順。無ければ null。
     */
    private static double[] openGround(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int z, int bottom, Direction first) {
        Direction[] order = {first, first.getClockWise(), first.getCounterClockWise(), first.getOpposite()};
        for (Direction d : order) {
            for (int step = 1; step <= 2; step++) {
                int gx = x + d.getStepX() * step;
                int gz = z + d.getStepZ() * step;
                double surface = surfaceAt(level, pos, gx, bottom, gz);
                if (!Double.isNaN(surface)) {
                    return new double[]{gx, gz, surface};
                }
                // 隣が幹・岩などで塞がっていれば、その向きのその先は見ない(幹の陰になる)。
                if (level.getBlockState(pos.set(gx, bottom, gz)).blocksMotion()) {
                    break;
                }
            }
        }
        return null;
    }

    private static boolean hasPuddle(int x, int z) {
        for (Puddle p : PUDDLES) {
            if (p.x == x && p.z == z) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTree(int x, int z) {
        for (Streak s : STREAKS) {
            if (s.x == x && s.z == z) {
                return true;
            }
        }
        return false;
    }

    /**
     * 水たまりを描く(地面に貼る扇。半径の {@link #PUDDLE_PLATEAU} まで同じ濃さ、その外で縁へ薄れる。縁は角ごとに半径を揺らして不規則に)。
     * 11-06 夜 ユーザー「今も全く見えない」: 暗い濡れと空の映り込みを 1 枚の半透明の上書きで描いていたので、暗い林床では両方が打ち消し合い、
     * 地面とほぼ同じ明るさになっていた。2 枚に分けた: 1 枚目は暗くする(黒 × {@code puddleDarken})、2 枚目は足し算で空の明るさを映す
     * ({@code puddleSheen} × 斜めから見るほど強いフレネル × その所の空の明るさ)。真上からは暗い斑、目線の高さからは明るく光る面に見える。
     */
    private static void renderPuddles(RenderLevelStageEvent event, ClientLevel level) {
        drawnPuddles = 0;
        if (puddle <= 0.0F || PUDDLES.isEmpty() || !com.yayuy.overgrownvegetation.data.DesignData.atmosphere().rainLook().enabled()) {
            return;
        }
        var look = com.yayuy.overgrownvegetation.data.DesignData.atmosphere().rainLook();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        float skyDarken = level.getSkyDarken(partialTick);
        float grow = SunLight.smooth(puddle);
        BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        for (int pass = 0; pass < 2; pass++) {
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            for (Puddle p : PUDDLES) {
                double y = p.y + 0.012 + pass * 0.004;
                double dx = p.cx - cam.x;
                double dy = y - cam.y;
                double dz = p.cz - cam.z;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                float fog = 1.0F - MistView.fogAmount((float) dist);
                float r;
                float g;
                float b;
                float alpha;
                if (pass == 0) {
                    r = 0.0F;
                    g = 0.0F;
                    b = 0.0F;
                    alpha = look.puddleDarken() * grow * fog;
                    drawnAlpha = alpha;
                    drawnPuddles++;
                } else {
                    // 斜めから見るほど空が映る(フレネルの近似。真上 0.1・寝た角度 1)。林冠の下の暗い空の光にも下限を付ける。
                    float ndv = (float) Mth.clamp(-dy / Math.max(dist, 1.0e-3), 0.0, 1.0);
                    float fresnel = 0.1F + 0.9F * (1.0F - ndv) * (1.0F - ndv);
                    lightPos.set(Mth.floor(p.cx), Mth.floor(p.y + 0.5), Mth.floor(p.cz));
                    float sky = level.getBrightness(LightLayer.SKY, lightPos) / 15.0F * skyDarken;
                    float block = level.getBrightness(LightLayer.BLOCK, lightPos) / 15.0F;
                    float bright = Math.max(0.3F, Math.max(sky, block));
                    r = 0.80F * bright;
                    g = 0.84F * bright;
                    b = 0.86F * bright;
                    alpha = look.puddleSheen() * fresnel * grow * fog;
                }
                if (alpha <= 0.002F) {
                    continue;
                }
                disc(builder, (float) dx, (float) dy, (float) dz, look.puddleRadius() * p.size * (0.55F + 0.45F * grow), p.seed, r, g, b, alpha);
            }
            MeshData mesh = builder.build();
            if (mesh == null) {
                continue;
            }
            if (pass == 0) {
                RenderSystem.defaultBlendFunc();
            } else {
                RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA, com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
            }
            ShaderInstance shader = GameRenderer.getPositionColorShader();
            if (shader == null) {
                mesh.close();
            } else {
                shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, new Matrix4f(event.getModelViewMatrix()), new Matrix4f(event.getProjectionMatrix()),
                        Minecraft.getInstance().getWindow());
                shader.apply();
                BufferUploader.draw(mesh);
                shader.clear();
            }
        }
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    /** 水たまり 1 つの扇(中心 (cx, cy, cz)、半径 radius、形の種 seed)。内側 {@link #PUDDLE_PLATEAU} まで alpha、縁で 0。 */
    private static void disc(BufferBuilder builder, float cx, float cy, float cz, float radius, float seed, float r, float g, float b, float alpha) {
        float k = PUDDLE_PLATEAU;
        float prevX = 0.0F;
        float prevZ = 0.0F;
        for (int i = 0; i <= PUDDLE_SEGMENTS; i++) {
            double a = i % PUDDLE_SEGMENTS * (Math.PI * 2.0 / PUDDLE_SEGMENTS);
            float rr = radius * (float) (1.0 + 0.22 * Math.sin(a * 2.0 + seed) + 0.14 * Math.sin(a * 3.0 + seed * 1.7));
            float ex = (float) Math.cos(a) * rr;
            float ez = (float) Math.sin(a) * rr;
            if (i > 0) {
                builder.addVertex(cx, cy, cz).setColor(r, g, b, alpha);
                builder.addVertex(cx + ex * k, cy, cz + ez * k).setColor(r, g, b, alpha);
                builder.addVertex(cx + prevX * k, cy, cz + prevZ * k).setColor(r, g, b, alpha);
                builder.addVertex(cx + prevX * k, cy, cz + prevZ * k).setColor(r, g, b, alpha);
                builder.addVertex(cx + ex * k, cy, cz + ez * k).setColor(r, g, b, alpha);
                builder.addVertex(cx + ex, cy, cz + ez).setColor(r, g, b, 0.0F);
                builder.addVertex(cx + prevX * k, cy, cz + prevZ * k).setColor(r, g, b, alpha);
                builder.addVertex(cx + ex, cy, cz + ez).setColor(r, g, b, 0.0F);
                builder.addVertex(cx + prevX, cy, cz + prevZ).setColor(r, g, b, 0.0F);
            }
            prevX = ex;
            prevZ = ez;
        }
    }

    static void render(RenderLevelStageEvent event) {
        if (VisualQuality.waterLife() && Minecraft.getInstance().level != null) {
            renderPuddles(event, Minecraft.getInstance().level);
        }
        if (wet <= 0.01F || STREAKS.isEmpty() || !VisualQuality.waterLife()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double seconds = Atmosphere.time(partialTick) / 20.0;
        Vec3 cam = event.getCamera().getPosition();
        float skyDarken = level.getSkyDarken(partialTick);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();
        for (Streak s : STREAKS) {
            float nx = s.side.getStepX();
            float nz = s.side.getStepZ();
            // 面の上の点: 原木の中心から面まで 0.5 + 浮かせ。面に沿う向きは法線を 90° 回したもの。
            double fx = s.x + 0.5 + nx * (0.5 + LIFT);
            double fz = s.z + 0.5 + nz * (0.5 + LIFT);
            float tx = -nz;
            float tz = nx;
            double cx = fx + tx * s.along;
            double cz = fz + tz * s.along;
            lightPos.set(s.x + s.side.getStepX(), Mth.floor((s.top + s.bottom) * 0.5), s.z + s.side.getStepZ());
            float sky = level.getBrightness(LightLayer.SKY, lightPos) / 15.0F * skyDarken;
            float block = level.getBrightness(LightLayer.BLOCK, lightPos) / 15.0F;
            float shade = Math.max(0.12F, Math.max(sky, block));
            // V は -高さ / TILE(絵の上が上)。時間で V を引くと絵が下へ流れる。
            float scroll = (float) ((seconds * s.speed / TILE + s.phase) % 1.0);
            double fadeTop = s.top - FADE;
            double fadeBottom = s.bottom + FADE;
            // 上の端の薄れ・真ん中・下の端の薄れの 3 枚
            quad(builder, cam, cx, cz, tx, tz, s.top, fadeTop, 0.0F, 1.0F, scroll, shade);
            quad(builder, cam, cx, cz, tx, tz, fadeTop, fadeBottom, 1.0F, 1.0F, scroll, shade);
            quad(builder, cam, cx, cz, tx, tz, fadeBottom, s.bottom, 1.0F, 0.0F, scroll, shade);
        }
        MeshData mesh = builder.build();
        if (mesh == null) {
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShaderTexture(0, TEXTURE);
        ShaderInstance shader = GameRenderer.getPositionTexColorShader();
        if (shader == null) {
            mesh.close();
        } else {
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, new Matrix4f(event.getModelViewMatrix()), new Matrix4f(event.getProjectionMatrix()),
                    mc.getWindow());
            shader.apply();
            BufferUploader.draw(mesh);
            shader.clear();
        }
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    /** 高さ hi → lo の板 1 枚。透明度は上 aHi・下 aLo に濡れと霞を掛ける。 */
    private static void quad(BufferBuilder b, Vec3 cam, double cx, double cz, float tx, float tz, double hi, double lo, float aHi, float aLo,
                             float scroll, float shade) {
        if (hi <= lo) {
            return;
        }
        float half = WIDTH / 2.0F;
        float vHi = (float) (-hi / TILE) - scroll;
        float vLo = (float) (-lo / TILE) - scroll;
        vertex(b, cam, cx - tx * half, hi, cz - tz * half, 0.0F, vHi, shade, aHi);
        vertex(b, cam, cx + tx * half, hi, cz + tz * half, 1.0F, vHi, shade, aHi);
        vertex(b, cam, cx + tx * half, lo, cz + tz * half, 1.0F, vLo, shade, aLo);
        vertex(b, cam, cx - tx * half, lo, cz - tz * half, 0.0F, vLo, shade, aLo);
    }

    private static void vertex(BufferBuilder b, Vec3 cam, double x, double y, double z, float u, float v, float shade, float a) {
        float dx = (float) (x - cam.x);
        float dy = (float) (y - cam.y);
        float dz = (float) (z - cam.z);
        // この描き方は霧を受けないので、森の霞の分だけ薄める(LifeSprites と同じ)。
        float alpha = a * wet * (1.0F - MistView.fogAmount(Mth.sqrt(dx * dx + dy * dy + dz * dz)));
        b.addVertex(dx, dy, dz).setUv(u, v).setColor(shade, shade, shade, alpha);
    }

    private TrunkRivulets() {
    }
}
