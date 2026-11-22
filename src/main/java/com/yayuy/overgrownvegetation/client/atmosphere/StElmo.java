package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.WeatherLookEye;
import com.yayuy.overgrownvegetation.network.CloudStrikePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * <b>セントエルモの火</b>(雷雨の強化の残り。2026-11-16。ユーザー決定: 高い木の先・巨大樹・プレイヤーの鉄の武器の先に青白い光がちらつく)。
 *
 * <p>現実のセントエルモの火は、雷雲の下の強い電場で、とがった物の先(帆柱・塔・木の梢)に出る青白い弱い放電。小さな炎のように揺れ、
 * ちらついては消える。ここでは雷雨の間({@code thunder} が {@code elmoFrom} 以上)と直前({@code preStorm} の終わり)に、
 * <ul>
 *     <li>まわり {@code elmoRange} ブロックの<b>高い木の先</b>(まわりより高い葉・幹の天辺)に、{@code elmoCount} か所まで。数秒ついては消え、別の先へ移る。</li>
 *     <li><b>巨大樹の梢</b>({@code elmoGiantRange} ブロック以内。遠くからも見える大きさ)。</li>
 *     <li>空の見える所で<b>鉄の武器・道具</b>(アイテムのタグ {@code overgrown_vegetation:st_elmo_conductors})を手に持つプレイヤーの、その先。</li>
 * </ul>
 * 葉そのものは光らせない(オーバーワールドの林冠の葉を光らせない決まり)。先端の上に加算の小さな光を置くだけ。音は付けていない。
 * 「落雷の前触れ」(落ちる直前にその木の先で強く光る)は、落雷の知らせが落ちた時に届くので今は作れない。数は節 {@code stormExtras} の elmo*(仮決め)。
 */
final class StElmo {

    private StElmo() {
    }

    static final TagKey<Item> CONDUCTORS = TagKey.create(Registries.ITEM, OvergrownVegetation.id("st_elmo_conductors"));

    private static final class Tip {
        double x, y, z;
        int born, life;
        long seed;
    }

    private static final List<Tip> TIPS = new ArrayList<>();
    private static final Random RANDOM = new Random();
    private static int ticks;
    private static float amount;

    static void clear() {
        TIPS.clear();
        OMENS.clear();
        amount = 0.0F;
    }

    /** 落雷の前触れ(11-17 ユーザー決定): 近くの木へ落ちる雷を少し遅らせ、その間、その木の先で強く光らせる。 */
    private static final class Omen {
        double x, y, z;
        int born, life;
        long seed;
        Runnable fire;
    }

    private static final List<Omen> OMENS = new ArrayList<>();

    /**
     * 落雷の知らせ {@code s} が、近く({@code elmoOmenRange} 以内)の木の先・巨大樹(当たった所が葉か幹)なら、{@code elmoOmenTicks} だけ遅らせて
     * {@code fire} を呼ぶことにして true。その間、当たる所でセントエルモの火が強まっていく。当てはまらなければ false(呼び手がすぐ渡す)。
     */
    static boolean omen(CloudStrikePayload s, Runnable fire) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        // 雰囲気の演出が切られている間は遅らせない(毎 tick の受け手が動かず、落雷を抱えたままになる)。
        if (level == null || mc.player == null || !Atmosphere.enabled(level) || s.hitY() == Integer.MIN_VALUE || OMENS.size() >= 6) {
            return false;
        }
        WeatherLookEye.Params p = WeatherLook.storm();
        int wait = Math.round(p.f("elmoOmenTicks"));
        double dx = s.x() - mc.player.getX(), dz = s.z() - mc.player.getZ();
        if (wait <= 0 || dx * dx + dz * dz > p.f("elmoOmenRange") * p.f("elmoOmenRange")) {
            return false;
        }
        BlockState top = level.getBlockState(BlockPos.containing(s.x(), s.hitY() - 1, s.z()));
        if (!(top.is(BlockTags.LEAVES) || top.is(ModTags.OVERGROWN_LEAVES) || top.is(BlockTags.LOGS) || top.is(ModTags.GIANT_WOOD))) {
            return false;
        }
        Omen o = new Omen();
        o.x = s.x();
        o.y = s.hitY() + 0.2;
        o.z = s.z();
        o.born = ticks;
        o.life = wait;
        o.seed = s.seed();
        o.fire = fire;
        OMENS.add(o);
        return true;
    }

    /** 毎 tick: 待ち終えた前触れの落雷を落とす。 */
    private static void tickOmens() {
        for (Iterator<Omen> it = OMENS.iterator(); it.hasNext(); ) {
            Omen o = it.next();
            if (ticks - o.born >= o.life) {
                it.remove();
                o.fire.run();
            }
        }
    }

    /** 前触れの光({@link WeatherLook} の加算の光の所から。雷雨の強さに関係なく描く)。落ちる瞬間へ向けて大きく明るく、震えが速くなる。 */
    static void renderOmens(RenderLevelStageEvent event, VertexConsumer c) {
        if (OMENS.isEmpty()) {
            return;
        }
        WeatherLookEye.Params p = WeatherLook.storm();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 eye = event.getCamera().getPosition();
        Vector3f left = event.getCamera().getLeftVector();
        Vector3f up = event.getCamera().getUpVector();
        double time = ticks + partial;
        float[] col = p.rgb("elmoColor");
        float gate = (1.0F - ForestPresence.underground()) * (1.0F - 0.7F * DenseFog.amount());
        for (Omen o : OMENS) {
            float k = Mth.clamp((float) (time - o.born) / Math.max(1.0F, o.life), 0.0F, 1.0F);
            float dist = (float) Math.sqrt(eye.distanceToSqr(o.x, o.y, o.z));
            float size = p.f("elmoOmenSize") * (0.35F + 0.65F * k * k) * (1.0F + dist / 60.0F);
            float a = gate * (0.35F + 0.65F * k) * (0.7F + 0.3F * flicker(o.seed, time * (1.0 + 3.0 * k)));
            glow(c, (float) (o.x - eye.x), (float) (o.y - eye.y), (float) (o.z - eye.z), size, a, col, left, up, o.seed, time * (1.0 + 2.0 * k));
        }
    }

    /** 毎 tick。{@code drive} = 雷雨の強さ(前触れ込み)0〜1。 */
    static void tick(ClientLevel level, LocalPlayer player, float drive) {
        ticks++;
        tickOmens();
        WeatherLookEye.Params p = WeatherLook.storm();
        float from = p.f("elmoFrom");
        amount = drive <= from ? 0.0F : Mth.clamp((drive - from) / Math.max(0.05F, 1.0F - from), 0.0F, 1.0F);
        for (Iterator<Tip> it = TIPS.iterator(); it.hasNext(); ) {
            Tip t = it.next();
            if (ticks - t.born > t.life) {
                it.remove();
            }
        }
        if (amount <= 0.0F) {
            return;
        }
        int want = Math.round(p.f("elmoCount") * (0.4F + 0.6F * amount));
        int range = Math.round(p.f("elmoRange"));
        for (int tries = 0; tries < 6 && TIPS.size() < want; tries++) {
            int x = Mth.floor(player.getX()) + RANDOM.nextInt(range * 2 + 1) - range;
            int z = Mth.floor(player.getZ()) + RANDOM.nextInt(range * 2 + 1) - range;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            if (y <= level.getMinBuildHeight() + 1) {
                continue;
            }
            BlockState top = level.getBlockState(new BlockPos(x, y - 1, z));
            if (!(top.is(BlockTags.LEAVES) || top.is(ModTags.OVERGROWN_LEAVES) || top.is(BlockTags.LOGS) || top.is(ModTags.GIANT_WOOD))) {
                continue;
            }
            // まわり(4 ブロック先の 8 方向)より高い先だけ。平らな林冠の上には出さない(1 つは 3 段以上低い)。
            boolean peak = true;
            int lowest = y;
            for (int i = 0; i < 8 && peak; i++) {
                int nx = x + Math.round(Mth.cos(i * Mth.PI / 4.0F) * 4.0F);
                int nz = z + Math.round(Mth.sin(i * Mth.PI / 4.0F) * 4.0F);
                int ny = level.getHeight(Heightmap.Types.MOTION_BLOCKING, nx, nz);
                peak = ny <= y;
                lowest = Math.min(lowest, ny);
            }
            if (!peak || y - lowest < 3) {
                continue;
            }
            boolean dup = false;
            for (Tip t : TIPS) {
                dup |= Math.abs(t.x - (x + 0.5)) < 3.0 && Math.abs(t.z - (z + 0.5)) < 3.0;
            }
            if (dup) {
                continue;
            }
            Tip t = new Tip();
            t.x = x + 0.5;
            t.y = y + 0.15;
            t.z = z + 0.5;
            t.born = ticks;
            t.life = 80 + RANDOM.nextInt(200);
            t.seed = RANDOM.nextLong();
            TIPS.add(t);
        }
    }

    /** ちらつき(0〜1): 細かく震え、ときどき一瞬消える。 */
    private static float flicker(long seed, double time) {
        double s = (seed & 0xFFFF) * 0.001;
        double a = 0.62 + 0.22 * Math.sin(time * 1.9 + s * 7.0) + 0.16 * Math.sin(time * 4.3 + s * 13.0);
        long step = (long) Math.floor(time / 2.0) * 0x9E3779B97F4A7C15L + seed;
        step ^= step >>> 29;
        step *= 0xBF58476D1CE4E5B9L;
        step ^= step >>> 32;
        float h = (step & 0xFFFF) / 65535.0F;
        return (float) Mth.clamp(a, 0.0, 1.0) * (h < 0.12F ? 0.15F : 1.0F);
    }

    /** {@link Atmosphere} の加算の光の所から。 */
    static void render(RenderLevelStageEvent event, VertexConsumer c) {
        if (amount <= 0.0F) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        WeatherLookEye.Params p = WeatherLook.storm();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float night = SunLight.night(level, partial);
        float gate = p.f("elmoAlpha") * amount * Mth.lerp(night, p.f("elmoDay"), 1.0F) * (1.0F - ForestPresence.underground()) * (1.0F - 0.7F * DenseFog.amount());
        if (gate <= 0.004F) {
            return;
        }
        Vec3 eye = event.getCamera().getPosition();
        Vector3f left = event.getCamera().getLeftVector();
        Vector3f up = event.getCamera().getUpVector();
        double time = ticks + partial;
        float size = p.f("elmoSize");
        float[] col = p.rgb("elmoColor");
        for (Tip t : TIPS) {
            float age = (float) (time - t.born);
            float env = Mth.clamp(age / 8.0F, 0.0F, 1.0F) * Mth.clamp((t.life - age) / 12.0F, 0.0F, 1.0F);
            float dist = (float) Math.sqrt(eye.distanceToSqr(t.x, t.y, t.z));
            // 遠い先は少し大きく(1〜2 画素の点にならないように)。
            glow(c, (float) (t.x - eye.x), (float) (t.y - eye.y), (float) (t.z - eye.z), size * (1.0F + dist / 20.0F), gate * env * flicker(t.seed, time),
                    col, left, up, t.seed, time);
        }
        // 巨大樹の梢。
        float giantRange = p.f("elmoGiantRange");
        if (giantRange > 0.0F) {
            for (GiantTrees.Info info : GiantTrees.all()) {
                double dx = info.axisX() - eye.x;
                double dz = info.axisZ() - eye.z;
                double flat = Math.hypot(dx, dz);
                if (flat > giantRange || info.topY() <= 0) {
                    continue;
                }
                long seed = info.originX() * 341873128712L + info.originZ() * 132897987541L;
                float dist = (float) Math.sqrt(dx * dx + dz * dz + (info.topY() - eye.y) * (info.topY() - eye.y));
                float far = 1.0F - SunLight.smooth(Mth.clamp((float) (flat / giantRange - 0.7) / 0.3F, 0.0F, 1.0F));
                glow(c, (float) dx, (float) (info.topY() + 1.2 - eye.y), (float) dz, p.f("elmoGiantSize") * (1.0F + dist / 90.0F),
                        gate * far * flicker(seed, time), col, left, up, seed, time);
            }
        }
        // 鉄の武器・道具の先(空の見える所で手に持っているプレイヤー)。
        for (Player pl : level.players()) {
            if (pl.isSpectator() || pl.isInvisible() || pl.distanceToSqr(eye) > 48.0 * 48.0 || !pl.getMainHandItem().is(CONDUCTORS)
                    || !level.canSeeSky(BlockPos.containing(pl.getX(), pl.getEyeY() + 0.5, pl.getZ()))) {
                continue;
            }
            Vec3 tip = heldTip(mc, event, pl, partial);
            boolean first = pl == mc.player && mc.options.getCameraType().isFirstPerson();
            glow(c, (float) (tip.x - eye.x), (float) (tip.y - eye.y), (float) (tip.z - eye.z), first ? size * 0.14F : size * 0.5F,
                    gate * flicker(pl.getId() * 7919L, time), col, left, up, pl.getId(), time);
        }
    }

    /** 手に持った物の先のおおよその位置(一人称は画面の右下の手の先・三人称は体の向きから)。 */
    private static Vec3 heldTip(Minecraft mc, RenderLevelStageEvent event, Player pl, float partial) {
        float side = pl.getMainArm() == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        if (pl == mc.player && mc.options.getCameraType().isFirstPerson()) {
            Vec3 eye = event.getCamera().getPosition();
            Vector3f look = event.getCamera().getLookVector();
            Vector3f left = event.getCamera().getLeftVector();
            Vector3f up = event.getCamera().getUpVector();
            float f = 0.62F, r = 0.33F * side, u = 0.05F;
            return new Vec3(eye.x + look.x() * f - left.x() * r + up.x() * u, eye.y + look.y() * f - left.y() * r + up.y() * u,
                    eye.z + look.z() * f - left.z() * r + up.z() * u);
        }
        float yaw = Mth.lerp(partial, pl.yBodyRotO, pl.yBodyRot) * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw);
        double rx = -Mth.cos(yaw), rz = -Mth.sin(yaw);
        Vec3 pos = pl.getPosition(partial);
        return new Vec3(pos.x + rx * 0.38 * side + fx * 0.5, pos.y + 1.45, pos.z + rz * 0.38 * side + fz * 0.5);
    }

    /** 1 つの火: 白い芯・青いにじみ・上へ伸びる細い房(2 tick ごとに形が変わる)。 */
    private static void glow(VertexConsumer c, float x, float y, float z, float size, float alpha, float[] col, Vector3f left, Vector3f up, long seed,
                             double time) {
        if (alpha <= 0.004F) {
            return;
        }
        float cr = Math.min(1.0F, col[0] + 0.3F), cg = Math.min(1.0F, col[1] + 0.2F), cb = Math.min(1.0F, col[2] + 0.1F);
        WeatherShapes.glowDisc(c, x, y + size * 0.4F, z, size * 4.5F, 1.25F, left, up, col[0], col[1], col[2], alpha * 0.22F);
        WeatherShapes.glowDisc(c, x, y + size * 0.3F, z, size * 1.3F, 1.3F, left, up, cr, cg, cb, alpha * 0.85F);
        Random r = new Random(seed * 31L + (long) Math.floor(time / 2.0));
        float[] c0 = {cr, cg, cb, alpha * 0.8F};
        float[] c1 = {col[0], col[1], col[2], 0.0F};
        for (int i = 0; i < 4; i++) {
            float a = (r.nextFloat() - 0.5F) * 1.5F;
            float len = size * (2.0F + 3.0F * r.nextFloat());
            float sx = Mth.sin(a) * len, sy = Mth.cos(a) * len;
            WeatherShapes.glowLine(c, x, y, z, x + left.x() * sx + up.x() * sy * 0.2F, y + sy, z + left.z() * sx + up.z() * sy * 0.2F, size * 0.45F,
                    size * 0.08F, c0, c1);
        }
    }

    /** 確かめ用: 一番近い木の先の位置と、そこを見る向き。 */
    static String nearest(LocalPlayer player) {
        Tip best = null;
        for (Tip t : TIPS) {
            if (best == null || player.distanceToSqr(t.x, t.y, t.z) < player.distanceToSqr(best.x, best.y, best.z)) {
                best = t;
            }
        }
        if (best == null) {
            return "木の先なし";
        }
        double dx = best.x - player.getX(), dy = best.y - player.getEyeY(), dz = best.z - player.getZ();
        return String.format("木の先 %.1f %.1f %.1f 距離 %.0f 見る向き yaw %.0f pitch %.0f", best.x, best.y, best.z, Math.sqrt(dx * dx + dy * dy + dz * dz),
                Math.toDegrees(Math.atan2(-dx, dz)), -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
    }

    static String describe() {
        return String.format("セントエルモ %.2f・木の先 %d", amount, TIPS.size());
    }
}
