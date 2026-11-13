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


    /** 確かめ用: 自然の洞窟の口を探す({@code cavedev findmouth}、11-07)。 */
    private static String findMouth(int r) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        var level = mc.level;
        if (level == null || mc.player == null) {
            return "なし";
        }
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        int cx = mc.player.getBlockX();
        int cz = mc.player.getBlockZ();
        int[][] dirs = {{1, 0, -90}, {-1, 0, 90}, {0, 1, 0}, {0, -1, 180}};
        java.util.List<int[]> found = new java.util.ArrayList<>();
        int[] stage = new int[3];
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                // 11-07: 口のすぐ内側の立てる所 = 地表から 24 段下までで、歩ける高さ 2 の空気・床あり・3 段以上の岩の天井の下・空の光 6〜11。
                // 向きは 3 マス先の空の光がいちばん明るい方(口の外を向く)。
                // クライアントの高さ地図は WORLD_SURFACE と MOTION_BLOCKING だけ(ほかは -64。メモリ client-heightmaps-only-two)
                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                for (int y = top - 1; y > top - 32; y--) {
                    if (!level.getBlockState(pos.set(x, y, z)).getCollisionShape(level, pos).isEmpty()
                            || !level.getBlockState(pos.set(x, y + 1, z)).getCollisionShape(level, pos).isEmpty()
                            || level.getBlockState(pos.set(x, y - 1, z)).getCollisionShape(level, pos).isEmpty()
                            || !level.getFluidState(pos.set(x, y, z)).isEmpty()) {
                        continue;
                    }
                    stage[0]++;
                    int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos.set(x, y, z));
                    if (sky >= 6 && sky <= 11) {
                        stage[1]++;
                    }
                    if (sky < 6 || sky > 11 || !CaveMap.thickRoofAbove(level, x, y + 2, z, 6)) {
                        continue;
                    }
                    int best = -1;
                    int bestYaw = 0;
                    for (int[] d : dirs) {
                        int s = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos.set(x + d[0] * 3, y, z + d[1] * 3));
                        if (s > best) {
                            best = s;
                            bestYaw = d[2];
                        }
                    }
                    stage[2]++;
                    if (best >= 13) {
                        found.add(new int[]{x, y, z, bestYaw, (x - cx) * (x - cx) + (z - cz) * (z - cz)});
                    }
                    break;
                }
            }
        }
        found.sort((a, b) -> Integer.compare(a[4], b[4]));
        StringBuilder sb = new StringBuilder("口 " + found.size() + " か所(空気 " + stage[0] + "・空の光 6〜11 " + stage[1] + "・天井も " + stage[2] + ")");
        for (int i = 0; i < Math.min(5, found.size()); i++) {
            int[] f = found.get(i);
            sb.append(" | ").append(f[0]).append(' ').append(f[1]).append(' ').append(f[2]).append(' ').append(f[3])
                    .append(' ').append(level.getBiome(pos.set(f[0], f[1], f[2])).unwrapKey().map(k -> k.location().getPath()).orElse("?"));
        }
        return sb.toString();
    }
    /** 地下の地図の確かめ(11-02): thin on = 前の数え方(1 段でも天井)・tint on = 地下の係数を赤で塗る。 */
    public static String caveDev(String what) {
        String[] p = what.trim().split(" +");
        boolean on = p.length > 1 && p[1].equals("on");
        if (p[0].equals("thin")) {
            CaveMap.devThinRoof = on;
        } else if (p[0].equals("tint")) {
            CaveLights.devTint = on;
        } else if (p[0].equals("selfparticles")) {
            // 11-06 一人称の胞子の粒: cavedev selfparticles on = 前の形(一人称でも自分の感染の粒を出す)。撮り比べ用
            com.yayuy.overgrownvegetation.client.SelfEffectParticles.devShowAll = on;
        } else if (p[0].equals("fade")) {
            // 11-06 光が急に灯る: cavedev fade off = 前の形(表の出入りを寄せない・重み 1・光る画素は表の中の光源だけ)。撮り比べ用
            CaveLights.devNoFade = !on;
        } else if (p[0].equals("sinktint")) {
            CaveLights.devTintSink = on;
        } else if (p[0].equals("worldwide")) {
            // 11-07 地下モードを世界全体へ: cavedev worldwide off = 前の形(過繁茂の地域の地下だけ)。撮り比べ用
            CaveLights.devOvergrownOnly = !on;
        } else if (p[0].equals("findmouth")) {
            // 11-07: 自然の洞窟の口のすぐ内側を探す(cavedev findmouth [半径])。歩ける高さ 2 の空気・3 段以上の岩の天井の下・空の光 6〜11 で、
            // 3 マス先が空の光 13 以上の所。近い順に 5 つ "x y z yaw バイオーム"(yaw は口の外を向く)。
            return findMouth(p.length > 1 ? Integer.parseInt(p[1]) : 96);
        } else if (p[0].equals("shafts")) {
            // 11-07 洞窟の天井の穴の光の筋: cavedev shafts off|on = 描かない・描く。cavedev shafts info = 本数と時間
            if (p.length > 1 && p[1].equals("info")) {
                return CaveShafts.describe();
            }
            CaveShafts.devOff = !on;
        } else if (p[0].equals("aim")) {
            // 11-09: 画面の真ん中に見えている面(カメラから 64 ブロックまで)の、面の前のマスの空の光・ブロックの光と、その列の地下の地図
            var mc = net.minecraft.client.Minecraft.getInstance();
            var cam = mc.gameRenderer.getMainCamera();
            net.minecraft.world.phys.Vec3 from = cam.getPosition();
            org.joml.Vector3f look = cam.getLookVector();
            net.minecraft.world.phys.Vec3 to = from.add(look.x() * 64.0, look.y() * 64.0, look.z() * 64.0);
            var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.VISUAL,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
                return "当たらない";
            }
            net.minecraft.core.BlockPos b = hit.getBlockPos();
            net.minecraft.core.BlockPos air = b.relative(hit.getDirection());
            float[] t = CaveMap.texel(air.getX(), air.getZ());
            return String.format("面 %s %s 向き %s 前のマス 空の光 %d ブロックの光 %d 距離 %.1f 地図 %s", b.toShortString(),
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(b).getBlock()), hit.getDirection().getName(),
                    mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, air), mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, air),
                    hit.getLocation().distanceTo(from), t == null ? "外" : String.format("岩の上面 %.0f 口まで %.1f 天井の下面 %.0f", t[0], t[2], t[3]));
        } else if (p[0].equals("foliage") && p.length > 1) {
            CaveLights.devFoliageFacing = p[1].equals("design") ? -1.0F : Float.parseFloat(p[1]);
            return "葉の面の向きの効き " + (CaveLights.devFoliageFacing < 0.0F ? "設計値" : CaveLights.devFoliageFacing);
        } else if (p[0].equals("overhanglight") && p.length > 2) {
            CaveLights.devOverhangLights = p[1].equals("design") ? -1.0F : Float.parseFloat(p[1]);
            CaveLights.devOverhangLift = p[2].equals("design") ? -1.0F : Float.parseFloat(p[2]);
            return "印の列の点光源 " + CaveLights.devOverhangLights + " 床の持ち上げ " + CaveLights.devOverhangLift;
        } else if (p[0].equals("overhangmap")) {
            var cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getBlockPosition();
            return CaveMap.overhangMap(cam.getX(), cam.getZ(), p.length > 1 ? Integer.parseInt(p[1]) : 20);
        } else if (p[0].equals("overhangwhy") && p.length > 2) {
            return CaveMap.explainOverhang(net.minecraft.client.Minecraft.getInstance().level, Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } else if (p[0].equals("overhang")) {
            // 11-09 空に浮かぶ構造物の下: cavedev overhang off = 前の形(開けた張り出しも地下)。cavedev overhang info = 地図の数と時間
            if (p.length > 2 && p[1].equals("camsec")) {
                CaveLights.devCamSeconds = Float.parseFloat(p[2]);
            } else if (p.length > 2 && p[1].equals("camspread")) {
                CaveLights.devCamSpread = Float.parseFloat(p[2]);
            } else
            if (p.length > 2 && p[1].equals("cam")) {
                CaveLights.devCamOverhang = p[2].equals("on");
            } else             if (p.length > 2 && p[1].equals("cut")) {
                CaveMap.devOverhangCut = p[2].equals("on");
            } else             if (!(p.length > 1 && p[1].equals("info"))) {
                CaveMap.devNoOverhang = !on;
            }
            return CaveMap.describe() + (CaveMap.devNoOverhang ? "(張り出しを見ない)" : "");
        } else if (p[0].equals("breath")) {
            // 11-08 洞窟の呼吸: cavedev breath off|on|info|in|out|auto(in・out = 風に関わらず吹き込み・吹き出し。撮影用)
            String sub = p.length > 1 ? p[1] : "info";
            switch (sub) {
                case "info" -> {
                    return CaveBreath.describe();
                }
                case "in" -> CaveBreath.devForce = 1;
                case "out" -> CaveBreath.devForce = -1;
                case "auto" -> CaveBreath.devForce = 0;
                case "oldcolor" -> CaveBreath.devOldHazeColor = p.length > 2 && p[2].equals("on");
                default -> CaveBreath.devOff = !on;
            }
            return "洞窟の呼吸 " + (CaveBreath.devOff ? "切" : "入") + " 強制 " + CaveBreath.devForce + " 前の霞の色 " + CaveBreath.devOldHazeColor;
        } else if (p[0].equals("held")) {
            // 11-07 手に持った明かり: cavedev held off|on
            CaveLights.devNoHeld = !on;
        } else if (p[0].equals("mobfog")) {
            // 11-08 Q1: cavedev mobfog off = Mob・粒の黒い霧を前の形(4 秒の mix)に戻す
            CaveLights.devMobFogMix = !on;
        } else if (p[0].equals("local")) {
            // 11-07 カメラ切り替えの廃止: cavedev local off = 前の形(カメラの所の mix で画面全体)
            CaveLights.devNoLocal = !on;
        } else if (p[0].equals("roof")) {
            // 11-07 天井の穴: cavedev roof off = 前の天井の割合の閾値(地下モードが半分で止まる)
            CaveLights.devHalfRoof = !on;
        } else if (p[0].equals("cavern")) {
            // 11-07 天井の穴: cavedev cavern off = 穴の下の面にも外の霞を掛ける(前の形)
            CaveLights.devNoCavern = !on;
        } else if (p[0].equals("open")) {
            // 11-07 判断の後の直し: cavedev open off = 過繁茂の外の洞窟の値(caveOpen)を使わない(判断の前の形)
            CaveLights.devNoOpen = !on;
        } else if (p[0].equals("adapt")) {
            // 11-07 目の慣れ: cavedev adapt off = 目の慣れを切る(撮り比べ用)・cavedev adapt reset = 慣れを今の明るさにそろえる
            if (p.length > 1 && p[1].equals("reset")) {
                EyeAdaptation.reset();
            } else {
                EyeAdaptation.devOff = !on;
            }
        } else if (p[0].equals("at") && p.length > 2) {
            // 地下の地図の列の中身(11-04): cavedev at <x> <z>
            try {
                float[] t = CaveMap.texel(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                return t == null ? "地図の外" : String.format("岩の上面 %.0f 過繁茂 %.0f 口まで %.1f 天井の下面 %.0f", t[0], t[1], t[2], t[3]);
            } catch (NumberFormatException e) {
                return "数でない";
            }
        } else if (p[0].equals("ray")) {
            // 視線の先(64 ブロック、液体も当てる)の面と、その面の地下の判定に使う列(面の外へ半ブロック)の地図の値(11-05 点検 16)
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null || mc.getCameraEntity() == null) {
                return "ワールドなし";
            }
            net.minecraft.world.phys.Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
            net.minecraft.world.phys.Vec3 to = eye.add(mc.getCameraEntity().getViewVector(1.0F).scale(64.0));
            var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, to, net.minecraft.world.level.ClipContext.Block.VISUAL,
                    net.minecraft.world.level.ClipContext.Fluid.ANY, mc.getCameraEntity()));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
                return "当たらない";
            }
            net.minecraft.core.BlockPos bp = hit.getBlockPos();
            net.minecraft.core.Direction d = hit.getDirection();
            net.minecraft.world.phys.Vec3 at = hit.getLocation();
            net.minecraft.world.phys.Vec3 q = at.add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5);
            int sky = mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, bp.relative(d));
            float[] f = CaveMap.texel(net.minecraft.util.Mth.floor(q.x), net.minecraft.util.Mth.floor(q.z));
            float[] c = CaveMap.coarseTexel(net.minecraft.util.Mth.floor(q.x), net.minecraft.util.Mth.floor(q.z));
            // 受けている光(11-06 光の点検 11): 面の外のブロックの光・空の光、面の向きの係数(バニラの getShade)、地下の光源の強さの和、
            // AO の目安(面の外の層の周り 8 マスのうち光を遮るブロックの数。バニラの AO はこれで暗くなる)。
            net.minecraft.core.BlockPos out = bp.relative(d);
            int blockLight = mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, out);
            int occluders = 0;
            for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
                if (side.getAxis() == d.getAxis()) {
                    continue;
                }
                for (net.minecraft.core.Direction side2 : net.minecraft.core.Direction.values()) {
                    if (side2.getAxis() == d.getAxis() || side2.getAxis() == side.getAxis() || side2.ordinal() < side.ordinal()) {
                        continue;
                    }
                    if (mc.level.getBlockState(out.relative(side).relative(side2)).isSolidRender(mc.level, out.relative(side).relative(side2))) {
                        occluders++;
                    }
                }
                if (mc.level.getBlockState(out.relative(side)).isSolidRender(mc.level, out.relative(side))) {
                    occluders++;
                }
            }
            String lightInfo = String.format(" / 光 ブロック %d 空 %d 面の向きの係数 %.2f 地下の光源の和 %.2f AO の目安 遮る %d/8", blockLight, sky,
                    mc.level.getShade(d, true), CaveLights.intensityAt(at.x, at.y, at.z), occluders);
            return CanopySunMap.debugAt(at.x, at.y, at.z) + lightInfo + " / " + String.format("当たり %s %s 面 %s 点 %.1f %.1f %.1f 距離 %.1f 面の外の空の光 %d / 見る列 %d %d: 細かい %s 粗い %s",
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(bp).getBlock()), bp.toShortString(), d,
                    at.x, at.y, at.z, at.distanceTo(eye), sky, net.minecraft.util.Mth.floor(q.x), net.minecraft.util.Mth.floor(q.z),
                    f == null ? "外" : String.format("岩 %.0f 過繁茂 %.0f", f[0], f[1]), c == null ? "外" : String.format("岩 %.0f 過繁茂 %.0f", c[0], c[1]));
        } else if (p[0].equals("puddleview") && p.length > 2) {
            // 水たまりの見える視点(11-06 夜): cavedev puddleview trunk|rain <高さ> → "VIEW x y z yaw pitch"(tp の足の位置)
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            return mc.level == null ? "ワールドなし" : "VIEW " + TrunkRivulets.viewPoint(mc.level, p[1].equals("drip") ? 2 : p[1].equals("rain") ? 1 : 0, Double.parseDouble(p[2]));
        } else if (p[0].equals("dust") && p.length > 1) {
            // 陸の水面の照りの再発の確かめ(11-09): カメラのまわり 7×7 チャンクに胞子の粉の降り積もり % を直に入れる(サーバーの次の便で上書きされる)。
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null || mc.getCameraEntity() == null) {
                return "ワールドなし";
            }
            int pct = Integer.parseInt(p[1]);
            int size = 7;
            int cx = (net.minecraft.util.Mth.floor(mc.getCameraEntity().getX()) >> 4) - size / 2;
            int cz = (net.minecraft.util.Mth.floor(mc.getCameraEntity().getZ()) >> 4) - size / 2;
            byte[] levels = new byte[size * size];
            java.util.Arrays.fill(levels, (byte) pct);
            com.yayuy.overgrownvegetation.client.ClientSporeDust.accept(new com.yayuy.overgrownvegetation.network.SporeDustPayload(cx, cz, size, levels, new int[size * size]));
            return "dust " + pct + "% チャンク " + cx + " " + cz + " から " + size + "×" + size;
        } else if (p[0].equals("blendleak") || p[0].equals("passgl")) {
            // 陸の日光の照りの確かめ(11-06): blendleak on = Sodium の層の描き始めで GL のブレンドを直に入れる / passgl on = 層の判定を前のブレンドの状態に戻す
            if (p[0].equals("blendleak")) {
                com.yayuy.overgrownvegetation.client.shader.SodiumTerrain.devBlendLeak = on;
            } else {
                com.yayuy.overgrownvegetation.client.shader.SodiumTerrain.devPassFromGl = on;
            }
            return "blendleak " + com.yayuy.overgrownvegetation.client.shader.SodiumTerrain.devBlendLeak + " passgl "
                    + com.yayuy.overgrownvegetation.client.shader.SodiumTerrain.devPassFromGl;
        } else if (p[0].equals("wet")) {
            // 雨の濡れの状態(11-06 バニラ地域の光 5): cavedev wet
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) {
                return "ワールドなし";
            }
            var cam = mc.gameRenderer.getMainCamera().getBlockPosition();
            var biome = mc.level.getBiome(cam);
            return String.format("外の濡れ %.2f 過繁茂 %.2f 胞子 %.0f%% %s %s 雨 %.2f 降り方 %s バイオーム %s", WetSheen.openWet(), ForestPresence.overgrown(),
                    AirHaze.sporePercent(), RainLook.describe(), TrunkRivulets.describe() + " " + CanopyDrips.describe(),
                    mc.level.getRainLevel(1.0F), biome.value().getPrecipitationAt(cam), biome.unwrapKey().map(k -> k.location().toString()).orElse("?"));
        } else if (p[0].equals("camstate")) {
            // 11-09 追記 9(動くときだけのチカチカ): カメラの位置で決まる値を一行に。地図の列の値(r 岩の上面・g・b 口までの距離・a 天井の下面)・印も
            var mc = net.minecraft.client.Minecraft.getInstance();
            var e = mc.gameRenderer.getMainCamera().getPosition();
            int cx = (int) Math.floor(e.x), cz = (int) Math.floor(e.z);
            float[] t = CaveMap.texel(cx, cz);
            MistView.devRecord = true;
            return CaveLights.camState() + String.format(" 列 %d %d 地図 %s 印 %s 下 %s 割合 %.2f 地下(森) %.3f 森の霧 %.3f 霧の濃さ %.4f 位置 %.2f %.2f %.2f", cx, cz,
                    t == null ? "-" : String.format("%.0f/%.0f/%.1f/%.0f", t[0], t[1], t[2], t[3]), CaveMap.overhangAt(cx, cz), CaveMap.cameraBelowOverhang(cx, cz, e.y),
                    CaveMap.overhangShare(cx, cz, 2), ForestPresence.underground(), ForestPresence.fogForest(), MistFog.density(), e.x, e.y, e.z) + MistView.devLast;
        } else if (p[0].equals("targetreset")) {
            CaveLights.targetNanos = 0L;
            CaveLights.targetCount = 0L;
            return "TARGETRESET";
        } else if (p[0].equals("fogstate")) {
            // 11-09 追記 7(ゆっくりした明るさの揺れ): 止まっている間に数秒で動く霧の値を一行に。cavedev fogstate
            return String.format("FOGSTATE 地下mix %.3f 霧mix %.3f 場所 %.3f 地下(森) %.3f 覆い %.3f 過繁茂 %.3f 森の霧 %.3f 霧の濃さ %.4f 深さ %.1f 中 %.3f 胞子 %.3f 胞子霧 %.3f 霞 %s 霧 %.1f〜%.1f 目標 %.1f µs/回(%d 回)",
                    CaveLights.mix(), CaveLights.fogMix(), CaveLights.camLocal(), ForestPresence.underground(), ForestPresence.cover(), ForestPresence.overgrown(),
                    ForestPresence.fogForest(), MistFog.density(), MistFog.depth(), MistFog.cameraInside(), SporeAir.level(), MistView.sporeFog(),
                    MistView.hazeActive(), CaveLights.fogStart(), CaveLights.fogEnd(), CaveLights.targetCount > 0 ? CaveLights.targetNanos / 1000.0 / CaveLights.targetCount : 0.0, CaveLights.targetCount);
        } else if (p[0].equals("air")) {
            // 胞子の空気(11-06): cavedev air [度合い 0〜1 | off] — カメラの所の濃度と度合い。数を渡すとカメラの所の度合いをその値に固定(撮り比べ)、off で戻す。
            if (p.length > 1) {
                SporeAir.devLevel = p[1].equals("off") ? -1.0F : Float.parseFloat(p[1]);
            }
            return SporeAir.describe() + String.format("・効き %.2f・森の霧 %.2f", MistView.airAmount(MistView.sporeFog(), CaveLights.mix()), ForestPresence.fogForest());
        } else if (p[0].equals("cat") && p.length > 2) {
            // 粗い地図の升の中身(11-05 点検 16): cavedev cat <x> <z>
            try {
                float[] t = CaveMap.coarseTexel(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                return t == null ? "粗い地図の外" : String.format("粗い 升 %.0f %.0f 岩の上面 %.0f 過繁茂 %.0f 読めた %.0f", t[3], t[4], t[0], t[1], t[2]);
            } catch (NumberFormatException e) {
                return "数でない";
            }
        } else if (p.length > 1) {
            // 11-04 地下の光の直し: 設計値をこのセッションだけ上書き(負で設計値)。falloff <倍> / ambient <値> / mouth <から> <まで> / lightsink <割合>
            try {
                float v = Float.parseFloat(p[1]);
                switch (p[0]) {
                    case "falloff" -> CaveLights.devFalloff = v;
                    case "ambient" -> CaveLights.devAmbient = v;
                    case "lightsink" -> CaveLights.devLightSink = v;
                    case "mouth" -> {
                        CaveLights.devMouthFrom = v;
                        CaveLights.devMouthTo = p.length > 2 ? Float.parseFloat(p[2]) : -1.0F;
                    }
                    default -> {
                    }
                }
            } catch (NumberFormatException ignored) {
                // 数でなければ何もしない
            }
        }
        return "thin " + CaveMap.devThinRoof + " tint " + CaveLights.devTint + " falloff " + CaveLights.devFalloff + " ambient " + CaveLights.devAmbient
                + " mouth " + CaveLights.devMouthFrom + ".." + CaveLights.devMouthTo + " lightsink " + CaveLights.devLightSink;
    }

    private AtmosphereDev() {
    }
}
