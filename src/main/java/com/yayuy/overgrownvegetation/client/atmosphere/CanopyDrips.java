package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereLife;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>巨大樹の傘からの雨だれ</b>(2026-11-06 夜 ユーザー採用、巨大樹の改善案 ②。理由: 巨大な傘は雨を溜めて縁からゆっくり落とすのが自然で、
 * 森の雨上がりの雰囲気になる。今日作った雨・滴・水たまりの仕組みをそのまま使う)。
 * <ul>
 *     <li><b>傘の縁</b>: カメラのまわり {@link #RADIUS} ブロックの列を毎 tick {@code dripTries} 列ずつ見て、高さの地図の一番上が葉で、
 *     その葉の連なりのいちばん下から地面まで {@code dripMinHeight} ブロック以上空いていて(高い傘)、2 マス先の隣のどれかが
 *     その高さより低い(傘の外 = 縁)列を、雨だれの点として覚える(上限 {@code dripMaxPoints})。{@code GiantSilhouettes} には頼らない
 *     (同時に別のセッションが巨大樹の一覧を足している。ぶつからないよう、この演出は自分で探す。小さな巨大樹でない木の高い傘にも出る)。</li>
 *     <li><b>滴</b>: 縁の葉の下から、バニラの落ちる水の滴({@code falling_water}。白い雨の見え方 {@link RainLook} で白い半透明)を落とす。
 *     雨の間は 1 点あたり毎秒 {@code dripRainRate} 粒(周りの雨より太い筋)、雨上がりは {@code dripAfterRate} × 残り。地面で跳ねる。</li>
 *     <li><b>雨上がり</b>: 止んでから {@code dripSeconds} 秒かけて残りが 1 → 0 へ減る(落ちる数も、輪の水たまりの広がりも)。</li>
 *     <li><b>輪の水たまり</b>: 縁の真下の地面に、水たまり({@link TrunkRivulets} の描き方)を {@code dripPuddleSpacing} おきに置く。傘の縁に沿って輪になる。</li>
 * </ul>
 * 値は {@code atmosphere.json} の節 {@code rainLook} の {@code drip*}(すべて仮決め)。
 */
final class CanopyDrips {

    /** 傘の縁を探す半径(ブロック)。 */
    static final int RADIUS = 32;

    private record Point(int x, int z, double y, double ground) {
    }

    private static final List<Point> POINTS = new ArrayList<>();

    /** 落ちている大粒の滴(位置・1 tick 前の高さ・落ちる速さ・着く地面の高さ)。 */
    private static final class Drop {
        final double x;
        final double z;
        final double ground;
        double y;
        double prevY;
        double vy;

        Drop(double x, double y, double z, double ground) {
            this.x = x;
            this.y = y;
            this.prevY = y;
            this.z = z;
            this.ground = ground;
        }
    }

    private static final List<Drop> DROPS = new ArrayList<>();
    /** 同時に落ちている滴の上限・重さ(1 tick に足す落ちる速さ)・速さの上限(ブロック/tick)・筋の太さ(仮決め)。 */
    private static final int MAX_DROPS = 600;
    private static final double GRAVITY = 0.06;
    private static final double MAX_SPEED = 1.2;
    private static final float WIDTH = 0.07F;

    /** 滴を落とし、地面に着いたら白い水しぶきの粒を出して消す(毎 tick)。 */
    private static void moveDrops(ClientLevel world) {
        for (java.util.Iterator<Drop> it = DROPS.iterator(); it.hasNext(); ) {
            Drop d = it.next();
            d.prevY = d.y;
            d.vy = Math.min(MAX_SPEED, d.vy + GRAVITY);
            d.y -= d.vy;
            if (d.y <= d.ground) {
                world.addParticle(ParticleTypes.SPLASH, d.x, d.ground + 0.05, d.z, 0.0, 0.0, 0.0);
                it.remove();
            }
        }
    }

    /**
     * 大粒の滴を描く(縦に伸びた白い半透明の板をカメラへ向ける。長さは速さに比例)。色は白い雨の見え方と同じ不透明さ({@code rainLook.alpha})、
     * 明るさはその所の空の光。森の霞の分だけ薄める。
     */
    static void render(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (DROPS.isEmpty()) {
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        ClientLevel world = mc.level;
        if (world == null) {
            return;
        }
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        net.minecraft.world.phys.Vec3 cam = event.getCamera().getPosition();
        float skyDarken = world.getSkyDarken(pt);
        float alphaBase = DesignData.atmosphere().rainLook().alpha();
        com.mojang.blaze3d.vertex.BufferBuilder b = com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
        BlockPos.MutableBlockPos lp = new BlockPos.MutableBlockPos();
        for (Drop d : DROPS) {
            double y = Mth.lerp(pt, d.prevY, d.y);
            float dx = (float) (d.x - cam.x);
            float dz = (float) (d.z - cam.z);
            float dy = (float) (y - cam.y);
            float dist = Mth.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > RADIUS + 16) {
                continue;
            }
            // 横向き(カメラへ向く縦の板の横の向き)。
            float h = Mth.sqrt(dx * dx + dz * dz);
            float rx = h < 1.0e-3F ? WIDTH : -dz / h * WIDTH;
            float rz = h < 1.0e-3F ? 0.0F : dx / h * WIDTH;
            float len = (float) Mth.clamp(d.vy * 1.6, 0.25, 1.6);
            lp.set(Mth.floor(d.x), Mth.floor(y), Mth.floor(d.z));
            float bright = Math.max(0.25F, Math.max(world.getBrightness(net.minecraft.world.level.LightLayer.SKY, lp) / 15.0F * skyDarken,
                    world.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, lp) / 15.0F)) * 0.9F;
            float a = alphaBase * (1.0F - MistView.fogAmount(dist));
            if (a <= 0.01F) {
                continue;
            }
            b.addVertex(dx - rx, dy, dz - rz).setColor(bright, bright, bright, a);
            b.addVertex(dx + rx, dy, dz + rz).setColor(bright, bright, bright, a);
            b.addVertex(dx + rx, dy + len, dz + rz).setColor(bright, bright, bright, 0.0F);
            b.addVertex(dx - rx, dy + len, dz - rz).setColor(bright, bright, bright, 0.0F);
        }
        com.mojang.blaze3d.vertex.MeshData mesh = b.build();
        if (mesh == null) {
            return;
        }
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
        net.minecraft.client.renderer.ShaderInstance shader = net.minecraft.client.renderer.GameRenderer.getPositionColorShader();
        if (shader == null) {
            mesh.close();
        } else {
            shader.setDefaultUniforms(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, new org.joml.Matrix4f(event.getModelViewMatrix()),
                    new org.joml.Matrix4f(event.getProjectionMatrix()), mc.getWindow());
            shader.apply();
            com.mojang.blaze3d.vertex.BufferUploader.draw(mesh);
            shader.clear();
        }
        com.mojang.blaze3d.systems.RenderSystem.enableCull();
        com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }
    private static final LongSet TAKEN = new LongOpenHashSet();
    private static final RandomSource RANDOM = RandomSource.create();
    /** 雨だれの残り(雨の間 1、止むと dripSeconds で 0 へ)。 */
    private static float level;

    private CanopyDrips() {
    }

    static float level() {
        return level;
    }

    static void clear() {
        POINTS.clear();
        DROPS.clear();
        TAKEN.clear();
        level = 0.0F;
    }

    static String describe() {
        int known = 0;
        int scanned = 0;
        for (GiantTrees.Info t : GiantTrees.all()) {
            known++;
            if (t.scanned()) {
                scanned++;
            }
        }
        return String.format("雨だれ %.2f 点 %d 滴 %d 巨大樹 %d(形あり %d)巨大樹の外で外した縁 %d", level, POINTS.size(), DROPS.size(), known, scanned, rejectedNotGiant);
    }

    static void tick(ClientLevel world, LocalPlayer player) {
        AtmosphereLife.RainLookParams p = DesignData.atmosphere().rainLook();
        if (!VisualQuality.waterLife() || !p.enabled() || p.dripMaxPoints() <= 0) {
            clear();
            return;
        }
        boolean raining = world.getRainLevel(1.0F) > 0.01F;
        level = raining ? Math.min(1.0F, level + 0.05F / 5.0F) : Math.max(0.0F, level - 0.05F / p.dripSeconds());
        double far = (RADIUS + 8.0) * (RADIUS + 8.0);
        POINTS.removeIf(pt -> {
            double dx = pt.x + 0.5 - player.getX();
            double dz = pt.z + 0.5 - player.getZ();
            boolean gone = dx * dx + dz * dz > far;
            if (gone) {
                TAKEN.remove(BlockPos.asLong(pt.x, 0, pt.z));
            }
            return gone;
        });
        moveDrops(world);
        if (level <= 0.0F) {
            POINTS.clear();
            TAKEN.clear();
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // 新しい縁は雨の間だけ探す(雨上がりは覚えた縁から落とし続ける)。
        if (raining) {
            for (int tries = 0; tries < p.dripTries() && POINTS.size() < p.dripMaxPoints(); tries++) {
                findPoint(world, player, pos, p);
            }
        }
        float perSecond = raining ? p.dripRainRate() : p.dripAfterRate() * level;
        float chance = perSecond * 0.05F;
        for (Point pt : POINTS) {
            float c = chance;
            while (c > 0.0F) {
                if (RANDOM.nextFloat() < c && DROPS.size() < MAX_DROPS) {
                    // 11-06 夜: バニラの落ちる滴の粒(1〜2 画素)では小さすぎて雨だれに見えなかったので、自前の大粒(縦に伸びた白い筋)にした。
                    DROPS.add(new Drop(pt.x + 0.2 + RANDOM.nextDouble() * 0.6, pt.y - 0.05, pt.z + 0.2 + RANDOM.nextDouble() * 0.6, pt.ground));
                }
                c -= 1.0F;
            }
        }
    }

    /**
     * 木の一部か(葉・原木・巨大樹の木部など)。巨大樹の木部 giant_overgrown_log はバニラの原木のタグにも isTreePart にも入っていなかったので、
     * 名前でも見る(11-06 夜: 傘の中の枝の上 y 235 に輪の水たまりを置いていた)。
     */
    static boolean isTreeBlock(BlockState s) {
        if (isLeaf(s) || s.is(BlockTags.LOGS) || com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.isTreePart(s)) {
            return true;
        }
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        return path.contains("log") || path.contains("wood") || path.contains("leaves") || path.contains("branch") || path.contains("bark");
    }

    /** 形がまだ入っていない巨大樹(サーバーの記録だけ)の、傘とみなす根元からの水平距離(ブロック。一覧の「同じ木」の距離 48 と同じ。仮決め)。 */
    private static final double UNSCANNED_RADIUS = 48.0;
    /** 傘の半径・下端に足すゆとり(縁は葉の外周なので、半径ちょうどより少し外まで。仮決め)。 */
    private static final double CROWN_MARGIN = 4.0;
    /** 確かめ用: 巨大樹の傘でないので外した縁の数。 */
    private static int rejectedNotGiant;

    /**
     * (x, z)・葉の下面 y が、どれかの巨大樹の傘の中か({@link GiantTrees#all}、giant-tree-marks の一覧。11-06 夜)。
     * 形のある木は幹の先の軸から傘の半径 + ゆとり以内、かつ下面が傘の下端の目安 − ゆとりより上。
     * 形がまだ入っていない木(scanned false)は、サーバーの根元から {@link #UNSCANNED_RADIUS} 以内なら傘とみなす(高さは見ない。
     * 傘の下面から地面まで dripMinHeight 以上の条件はもう満たしている)。一覧が空なら雨だれは出ない。
     */
    private static boolean inGiantCrown(double x, double z, double bottom) {
        for (GiantTrees.Info t : GiantTrees.all()) {
            if (t.scanned()) {
                double dx = x - t.axisX();
                double dz = z - t.axisZ();
                double r = t.crownRadius() + CROWN_MARGIN;
                if (dx * dx + dz * dz <= r * r && bottom >= t.crownBottomY() - CROWN_MARGIN) {
                    return true;
                }
            } else {
                double dx = x - t.originX();
                double dz = z - t.originZ();
                if (dx * dx + dz * dz <= UNSCANNED_RADIUS * UNSCANNED_RADIUS) {
                    return true;
                }
            }
        }
        rejectedNotGiant++;
        return false;
    }

    private static boolean isLeaf(BlockState s) {
        return s.is(BlockTags.LEAVES) || s.is(com.yayuy.overgrownvegetation.ModTags.OVERGROWN_LEAVES);
    }

    private static void findPoint(ClientLevel world, LocalPlayer player, BlockPos.MutableBlockPos pos, AtmosphereLife.RainLookParams p) {
        int x = Mth.floor(player.getX() + (RANDOM.nextDouble() * 2.0 - 1.0) * RADIUS);
        int z = Mth.floor(player.getZ() + (RANDOM.nextDouble() * 2.0 - 1.0) * RADIUS);
        long key = BlockPos.asLong(x, 0, z);
        if (TAKEN.contains(key) || !world.hasChunkAt(pos.set(x, Mth.floor(player.getY()), z))) {
            return;
        }
        int top = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        if (!isLeaf(world.getBlockState(pos.set(x, top - 1, z)))
                || world.getBiome(pos.set(x, top, z)).value().getPrecipitationAt(pos) != Biome.Precipitation.RAIN) {
            return;
        }
        // 葉の連なりのいちばん下(枝・幹が混じれば傘の真ん中なので外す)。
        int bottom = top - 1;
        while (bottom > top - 24 && isLeaf(world.getBlockState(pos.set(x, bottom - 1, z)))) {
            bottom--;
        }
        if (!world.getBlockState(pos.set(x, bottom - 1, z)).isAir()) {
            return;
        }
        // 真下の地面(空いた所を下りて、水たまりを描ける面)。
        double surface = Double.NaN;
        int groundY = Integer.MIN_VALUE;
        for (int y = bottom - 1; y > bottom - 80 && y > world.getMinBuildHeight(); y--) {
            BlockState s = world.getBlockState(pos.set(x, y, z));
            // 空気と、形の無いもの(光のブロックなど。水は地面として止まる)は通り抜ける。
            if (s.isAir() || s.getShape(world, pos).isEmpty() && s.getFluidState().isEmpty()) {
                continue;
            }
            // 下の葉・枝・幹(傘の下の段)に当たるなら地面ではない: この点は使わない(滴が傘の中に落ちる)。
            if (isTreeBlock(s)) {
                return;
            }
            groundY = y + 1;
            surface = TrunkRivulets.surfaceAt(world, pos, x, y + 1, z);
            if (Double.isNaN(surface)) {
                surface = TrunkRivulets.surfaceAt(world, pos, x, y, z);
            }
            break;
        }
        // 下草の上など水たまりを描けない所でも、滴は落とす(水たまりだけ置かない)。
        if (groundY == Integer.MIN_VALUE || bottom - groundY < p.dripMinHeight()) {
            return;
        }
        // 縁: 2 マス先の隣のどれかの一番上が、この傘の下面より低い(そこは傘の外)。
        boolean rim = false;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (world.getHeight(Heightmap.Types.MOTION_BLOCKING, x + d.getStepX() * 2, z + d.getStepZ() * 2) <= bottom) {
                rim = true;
                break;
            }
        }
        if (!rim) {
            return;
        }
        // 巨大樹の傘だけ(11-06 夜 ユーザー決定: 巨大樹でない高い木には出さない)。
        if (!inGiantCrown(x + 0.5, z + 0.5, bottom)) {
            return;
        }
        POINTS.add(new Point(x, z, bottom, groundY));
        TAKEN.add(key);
        if (!Double.isNaN(surface)) {
            TrunkRivulets.addDripPuddle(x + 0.5, z + 0.5, surface, p.dripPuddleSpacing(), p.dripPuddleMax());
        }
    }
}
