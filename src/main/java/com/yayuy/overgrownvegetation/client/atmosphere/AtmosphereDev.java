package com.yayuy.overgrownvegetation.client.atmosphere;

/**
 * 開発用の口(Claude のテストクライアントの DevBridge から。撮り比べのためにこのセッションだけ値を替える)。ふだんの遊びでは使わない。
 */
public final class AtmosphereDev {

    /** 環境の胞子の梢より上の出し方({@link AmbientSpores#ceilingMode})。 */
    public static void sporeCeilingMode(int mode) {
        AmbientSpores.ceilingMode = Math.max(0, Math.min(3, mode));
    }

    /** 環境の胞子が日向で明るくなる割合。負で設計値に戻す。 */
    public static void sporeLit(float boost) {
        AmbientSpores.litOverride = boost;
    }

    /** 前線が過ぎたあとの粒の余韻(秒)。負で設計値に戻す。 */
    public static void afterglow(double seconds) {
        WindHistory.afterglowOverride = seconds;
    }

    /** 地下モードの点光源の数の上限(負で設計値。11-01 試作の重さの比べ)。 */
    public static void caveMaxLights(int max) {
        CaveLights.devMaxLights = max;
    }

    /** 地下モードの今の状態(1 行)。 */
    public static String caveStatus() {
        return CaveLights.describe();
    }

    /**
     * 地下の地図の点検(11-02 霧の剥がれ)。カメラのまわり ±32 列で、地図の「岩」が宙に浮いている列(その下に空気がある = 地上の断片の上に天井がある)を
     * 岩のブロックごとに数え、例の座標・厚み・その下の空の光を返す。地上の森で黒い霧が掛かる原因の候補。
     */
    public static String caveProbe(net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.world.phys.Vec3 eye) {
        java.util.Map<String, int[]> count = new java.util.TreeMap<>();
        java.util.Map<String, String> example = new java.util.HashMap<>();
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        int cx = net.minecraft.util.Mth.floor(eye.x);
        int cz = net.minecraft.util.Mth.floor(eye.z);
        int floating = 0;
        int thin = 0;
        java.util.List<String> thinList = new java.util.ArrayList<>();
        for (int dz = -32; dz < 32; dz++) {
            for (int dx = -32; dx < 32; dx++) {
                int x = cx + dx;
                int z = cz + dz;
                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                int rockY = Integer.MIN_VALUE;
                for (int y = top; y >= level.getMinBuildHeight(); y--) {
                    if (CaveMap.isRock(level.getBlockState(pos.set(x, y, z)))) {
                        rockY = y;
                        break;
                    }
                }
                if (rockY == Integer.MIN_VALUE) {
                    continue;
                }
                // 岩の下を下りて、岩でないマス(空気・植物・木)があるか。あれば宙の天井。
                int thick = 1;
                int y = rockY - 1;
                while (y > rockY - 40 && CaveMap.isRock(level.getBlockState(pos.set(x, y, z)))) {
                    thick++;
                    y--;
                }
                if (y <= rockY - 40) {
                    continue;
                }
                int gap = 0;
                int yy = y;
                while (yy > rockY - 60 && !CaveMap.isRock(level.getBlockState(pos.set(x, yy, z)))) {
                    gap++;
                    yy--;
                }
                if (gap == 0 || yy <= rockY - 60 || level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos.set(x, yy + 1, z)) < 1) {
                    continue;
                }
                floating++;
                net.minecraft.world.level.block.state.BlockState roof = level.getBlockState(pos.set(x, rockY, z));
                String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(roof.getBlock()).toString();
                int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos.set(x, yy + 1, z));
                int[] c = count.computeIfAbsent(id, k -> new int[3]);
                c[0]++;
                c[1] += thick;
                c[2] += sky;
                example.putIfAbsent(id, x + " " + rockY + " " + z + " 厚み " + thick + " 隙間 " + gap + " 床の空の光 " + sky);
                if (thick < CaveMap.MIN_ROOF) {
                    thin++;
                    if (gap >= 3 && sky >= 1 && sky <= 11 && thinList.size() < 12) {
                        thinList.add(id.substring(id.indexOf(':') + 1) + " " + x + " " + rockY + " " + z + " 床 " + (yy + 1) + " 空 " + sky);
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder("宙の天井の列 " + floating + " / 4096");
        sb.append(" うち " + CaveMap.MIN_ROOF + " 段未満 ").append(thin);
        thinList.forEach(s -> sb.append("\n  薄い ").append(s));
        count.forEach((id, c) -> sb.append("\n  ").append(id).append(" 列 ").append(c[0]).append(" 厚み平均 ")
                .append(String.format("%.1f", c[1] / (float) c[0])).append(" 床の空の光平均 ").append(String.format("%.1f", c[2] / (float) c[0]))
                .append(" 例 ").append(example.get(id)));
        // カメラ側: 天井の割合と、真上の列で天井にした最初のブロック。
        sb.append("\n  カメラ 天井の割合 ").append(String.format("%.2f", CaveLights.roofFraction(level, eye)))
                .append(" 空の光 ").append(level.getBrightness(net.minecraft.world.level.LightLayer.SKY, net.minecraft.core.BlockPos.containing(eye)));
        for (int y = net.minecraft.util.Mth.floor(eye.y) + 1; y < net.minecraft.util.Mth.floor(eye.y) + 64; y++) {
            net.minecraft.world.level.block.state.BlockState s = level.getBlockState(pos.set(cx, y, cz));
            if (!s.isAir() && s.canOcclude() && !com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.isTreePart(s)) {
                sb.append(" 真上の天井 ").append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock())).append(" y ").append(y);
                break;
            }
        }
        return sb.append("\n  ").append(CaveLights.describe()).toString();
    }

    /** 地下の地図の確かめ(11-02): thin on = 前の数え方(1 段でも天井)・tint on = 地下の係数を赤で塗る。 */
    public static String caveDev(String what) {
        String[] p = what.trim().split(" +");
        boolean on = p.length > 1 && p[1].equals("on");
        if (p[0].equals("thin")) {
            CaveMap.devThinRoof = on;
        } else if (p[0].equals("tint")) {
            CaveLights.devTint = on;
        }
        return "thin " + CaveMap.devThinRoof + " tint " + CaveLights.devTint;
    }

    private AtmosphereDev() {
    }
}
