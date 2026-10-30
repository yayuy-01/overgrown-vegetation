package com.yayuy.overgrownvegetation.base.plan;

import java.util.Random;

import static com.yayuy.overgrownvegetation.base.plan.Pal.*;

/**
 * 建物の設計図を組む(2026-10-01、すべて仮。作者が描き直すまでの下地)。手本は米軍の前線基地(FOB)と駐屯地:
 * 管理棟 = 鉄筋コンクリートの司令部(階数は基地レベル 2 / 3 / 4 階)、兵舎 = 砂色のかまぼこ形のテント(TEMPER テントの写し。小型と大型)、
 * 倉庫 = 天幕(テンション膜)と鉄骨、弾薬庫 = 土を被せたかまぼこ形の掩体(避雷針つき)、工廠 = 大扉の整備工場、
 * 見張り台 = 階段の塔(エレベーターは作らない、ユーザー指定)と土嚢の胸壁(外周が金網の間は木造、壁になったら鉄筋)、
 * 正門 = 門扉 + 両脇(金網の間は土嚢の詰所、壁になったら鉄筋の塔)、検問所 = ジグザグの車止め。
 *
 * <p>設計図は正面(入口)を +z(南)に向けて組み、最後に区画の向きへ回す。y = 0 は床(地面の高さ)。
 */
public final class Blueprints {

    private static final String DOOR = "minecraft:dark_oak_door";
    private static final String GATE = "minecraft:dark_oak_fence_gate";
    private static final String RC_SLAB = "overgrown_vegetation:reinforced_concrete_slab";
    private static final String TENT_STAIRS = "minecraft:smooth_sandstone_stairs";
    private static final String TENT = "minecraft:smooth_sandstone";
    private static final String TENT_SLAB = "minecraft:smooth_sandstone_slab";
    private static final String ROOF_STAIRS = "minecraft:dark_oak_stairs";
    private static final String ROOF_SLAB = "minecraft:dark_oak_slab";
    private static final String OLIVE = "minecraft:green_terracotta";
    private static final String[] CONTAINER_COLORS = {
            "minecraft:orange_terracotta", "minecraft:green_terracotta", "minecraft:blue_terracotta",
            "minecraft:brown_terracotta", "minecraft:red_terracotta", "minecraft:light_gray_terracotta",
            "minecraft:cyan_terracotta"};

    /** 区画 {@code p} の建物を、基地レベル {@code level} の姿で組んで区画の向きへ回した設計図。 */
    public static Blueprint build(Plot p, int level) {
        Random rng = new Random(p.seed());
        int w = p.width();
        int d = p.depth();
        Blueprint.Builder b = switch (p.kind()) {
            case HQ -> hq(level, rng);
            case BARRACKS_TENT -> barracksTent(rng);
            case BARRACKS_TENT_LARGE -> barracksTentLarge(rng);
            case WAREHOUSE_TENT -> warehouseTent(rng);
            case WAREHOUSE -> warehouse(rng);
            case CONTAINER_YARD -> containerYard(w, d, rng);
            case AMMO_BUNKER -> ammoBunker(w, d, rng);
            case WORKSHOP -> workshop(rng);
            case VEHICLE_SHED -> vehicleShed(rng);
            case PARKING -> parking(w, d, rng);
            case WATCHTOWER -> watchtower(level < BasePlan.WALL_LEVEL, rng);
            case GATEHOUSE -> level < BasePlan.WALL_LEVEL ? fenceGatehouse() : gatehouse();
            case CHECKPOINT -> checkpoint();
            case HELIPAD -> helipad();
            case FLAGPOLE -> flagpole();
            case SHELTER -> shelter();
            case GENERATOR -> generator();
            case WATER_TANK -> waterTank();
            case LATRINE -> latrine();
            case CONTAINER -> container(rng);
            case LIGHT_TOWER -> lightTower();
            case BARRIER -> barrier();
            case PURIFIER_MAST -> purifierMast();
            case PURIFIER_TOWER -> purifierTower();
            case MEDICAL_TENT -> medicalTent();
            case FIELD_LAB -> fieldLab();
            case OBSERVATION_POST -> observationPost();
            case ANNEX_LAB -> annexLab();
            case TEST_HALL -> testHall();
            case RESEARCH_WALKWAY -> walkwayBuilder(d, 0, 0); // 平らな姿(ゲームでは BaseEmitter が両端の棟の高さに合わせて作る)
        };
        Connections.resolveAll(b);
        return b.build().rotated(p.facing().turnsFromSouth());
    }

    /** 空き(触らない・空気)のマスだけ埋める。 */
    static void fillFree(Blueprint.Builder b, int x0, int y0, int z0, int x1, int y1, int z1, String state) {
        for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
                    String s = b.get(x, y, z);
                    if (b.inside(x, y, z) && (s.equals(KEEP) || s.equals(AIR))) {
                        b.set(x, y, z, state);
                    }
                }
            }
        }
    }

    /** 管理棟の階数(基地レベル 1〜2 = 2 階、3〜4 = 3 階、5 = 4 階。仮決め)。 */
    public static int hqFloors(int level) {
        return level >= 5 ? 4 : level >= 3 ? 3 : 2;
    }

    // ================= 管理棟 =================

    /** 15 × 11。本体は奥行 9、前の 2 マスは庇と土嚢の玄関。1 階に作戦室と管理端末、上の階は事務室、最上階に幹部の寝室。 */
    static Blueprint.Builder hq(int level, Random rng) {
        int floors = hqFloors(level);
        int w = 15;
        int d = 11;
        int bd = 9; // 本体の奥行(z 0..8)
        int roof = 4 * floors;
        Blueprint.Builder b = new Blueprint.Builder(w, roof + 7, d);
        b.fill(0, 1, 0, w - 1, b.h - 1, d - 1, AIR);
        // 土台と床
        b.fill(0, 0, 0, w - 1, 0, bd - 1, RC);
        b.fill(1, 0, 1, w - 2, 0, bd - 2, SMOOTH);
        for (int f = 1; f < floors; f++) {
            b.fill(1, 4 * f, 1, w - 2, 4 * f, bd - 2, SMOOTH);
        }
        // 外壁(薄灰のコンクリート)+ 鉄筋の柱と階ごとの帯
        b.ring(0, 1, 0, w - 1, roof, bd - 1, LGC);
        for (int x : new int[]{0, 4, 10, 14}) {
            b.fill(x, 1, 0, x, roof, 0, RC);
            b.fill(x, 1, bd - 1, x, roof, bd - 1, RC);
        }
        b.fill(0, 1, 4, 0, roof, 4, RC);
        b.fill(w - 1, 1, 4, w - 1, roof, 4, RC);
        for (int f = 1; f < floors; f++) {
            b.ring(0, 4 * f, 0, w - 1, 4 * f, bd - 1, LGC);
        }
        b.fill(0, roof, 0, w - 1, roof, bd - 1, GC);
        b.ring(0, roof + 1, 0, w - 1, roof + 1, bd - 1, RC_WALL);
        // 窓(灰色のガラス、各階 2 段)
        for (int f = 0; f < floors; f++) {
            int yb = 4 * f;
            for (int x = 1; x < w - 1; x++) {
                if (x == 4 || x == 10) {
                    continue;
                }
                b.set(x, yb + 2, 0, GLASS).set(x, yb + 3, 0, GLASS);
                if (!(f == 0 && x >= 5 && x <= 9)) {
                    b.set(x, yb + 2, bd - 1, GLASS).set(x, yb + 3, bd - 1, GLASS);
                }
            }
            for (int z : new int[]{2, 6}) {
                b.set(0, yb + 2, z, GLASS).set(0, yb + 3, z, GLASS);
                b.set(w - 1, yb + 2, z, GLASS).set(w - 1, yb + 3, z, GLASS);
            }
        }
        // 玄関: 扉 + 脇のガラス + 欄間、庇、土嚢、投光器
        b.fill(5, 1, bd - 1, 9, 3, bd - 1, LGC);
        b.set(7, 1, bd - 1, door(DOOR, Facing.NORTH, false, false));
        b.set(7, 2, bd - 1, door(DOOR, Facing.NORTH, true, false));
        b.fill(6, 1, bd - 1, 6, 3, bd - 1, GLASS);
        b.fill(8, 1, bd - 1, 8, 3, bd - 1, GLASS);
        b.set(7, 3, bd - 1, GLASS);
        b.fill(4, 0, bd, 10, 0, d - 1, SMOOTH);
        b.fill(4, 4, bd, 10, 4, d - 1, slab(RC_SLAB, "top"));
        b.fill(4, 1, d - 1, 4, 3, d - 1, RC_WALL);
        b.fill(10, 1, d - 1, 10, 3, d - 1, RC_WALL);
        b.fill(0, 1, d - 1, 2, 1, d - 1, SANDBAGS);
        b.fill(12, 1, d - 1, 14, 1, d - 1, SANDBAGS);
        b.set(5, 5, d - 1, floodlight(Facing.SOUTH));
        b.set(9, 5, d - 1, floodlight(Facing.SOUTH));
        // 階段(西の奥、折り返し)
        switchbackStairs(b, 1, 1, floors, "minecraft:stone_brick_stairs", IRON_BARS);
        // 1 階: 作戦室。奥に管理端末、真ん中に作戦机(地図台)、東の壁に無線機の棚、書類の樽
        b.set(7, 1, 1, TERMINAL);
        b.fill(6, 1, 3, 8, 1, 4, CARTOGRAPHY);
        b.set(5, 1, 3, stairs("minecraft:spruce_stairs", Facing.WEST, false));
        b.set(9, 1, 4, stairs("minecraft:spruce_stairs", Facing.EAST, false));
        b.fill(13, 1, 1, 13, 2, 3, facing("minecraft:observer", Facing.WEST));
        b.fill(13, 1, 5, 13, 1, 7, BARREL);
        b.set(4, 1, 1, BOOKSHELF).set(5, 1, 1, BOOKSHELF);
        hangLights(b, 0, 4, 10);
        // 上の階: 事務机と椅子・本棚・樽。最上階は幹部の寝室
        for (int f = 1; f < floors; f++) {
            int y = 4 * f + 1;
            boolean top = f == floors - 1;
            if (top && floors >= 2) {
                b.set(9, y, 1, bed("green", Facing.NORTH, true)).set(9, y, 2, bed("green", Facing.NORTH, false));
                b.set(12, y, 1, bed("green", Facing.NORTH, true)).set(12, y, 2, bed("green", Facing.NORTH, false));
                b.set(10, y, 1, chest(Facing.SOUTH)).set(13, y, 1, chest(Facing.SOUTH));
            } else {
                for (int x = 6; x <= 12; x += 3) {
                    b.set(x, y, 1, slab("minecraft:spruce_slab", "top"));
                    b.set(x, y, 2, stairs("minecraft:spruce_stairs", Facing.SOUTH, false));
                }
            }
            b.fill(13, y, 4, 13, y + 1, 7, BOOKSHELF);
            b.set(5, y, 7, BARREL).set(6, y, 7, BARREL);
            hangLights(b, f, 4, 10);
        }
        // 屋上: 空調の室外機・アンテナの柱・衛星アンテナ・土嚢の見張り
        int ry = roof + 1;
        b.set(10, ry, 2, LGC).set(11, ry, 2, LGC);
        b.set(10, ry + 1, 2, trapdoor("minecraft:iron_trapdoor", Facing.NORTH, false, false));
        b.set(11, ry + 1, 2, trapdoor("minecraft:iron_trapdoor", Facing.NORTH, false, false));
        b.fill(12, ry, 6, 12, ry + 3 + floors, 6, IRON_BARS);
        b.set(12, ry + 4 + floors, 6, LIGHTNING_ROD);
        b.set(3, ry, 5, WC);
        b.set(3, ry + 1, 5, slab("minecraft:smooth_quartz_slab", "bottom"));
        b.set(3, ry + 2, 5, LIGHTNING_ROD);
        b.fill(1, ry, 1, 2, ry, 1, SANDBAGS);
        return b;
    }

    /**
     * 折り返し階段(階の高さ 4、各階 4 段)。偶数階は列 {@code x0}(南へ上る)、奇数階は列 {@code x0 + 1}(北へ上る)。
     * 上の階の床は、下の階の階段の頭上だけ穴を空け、落ちないよう手すりを置く(頭がつかえるマスには置かない)。
     */
    static void switchbackStairs(Blueprint.Builder b, int x0, int z0, int floors, String stairId, String rail) {
        for (int f = 0; f < floors - 1; f++) {
            int yb = 4 * f;
            boolean even = (f & 1) == 0;
            int col = even ? x0 : x0 + 1;
            for (int i = 0; i < 4; i++) {
                int z = even ? z0 + 1 + i : z0 + 3 - i;
                b.set(col, yb + 1 + i, z, stairs(stairId, even ? Facing.SOUTH : Facing.NORTH, false));
                // 頭上の空き(段の上 2 マス)
                b.set(col, yb + 2 + i, z, AIR);
                b.set(col, yb + 3 + i, z, b.get(col, yb + 3 + i, z).startsWith(stairId) ? b.get(col, yb + 3 + i, z) : AIR);
            }
            // 次の階の床の穴(頭上)と手すり
            int ny = yb + 4;
            // 手すりは最初の段の上だけ。段を上がる間は体がまだ元の段に残るので、2 段目の上(次の階の床 + 1)も頭が通る
            // (ユーザー指摘 2026-10-02「階段の上に格子」: 2 段目の上の手すりで 3 段目へ上がれなかった)
            if (even) {
                b.set(col, ny, z0 + 1, AIR).set(col, ny, z0 + 2, AIR).set(col, ny, z0 + 3, AIR);
                b.set(col, ny + 1, z0 + 1, rail);
            } else {
                b.set(col, ny, z0 + 3, AIR).set(col, ny, z0 + 2, AIR).set(col, ny, z0 + 1, AIR);
                b.set(col, ny + 1, z0 + 3, rail);
            }
        }
    }

    /** 階 {@code f} の天井から吊る灯り(x を並べる)。天井(次の階の床)の真下に吊る。 */
    static void hangLights(Blueprint.Builder b, int f, int... xs) {
        int y = 4 * f + 3;
        for (int x : xs) {
            b.set(x, y, 4, LANTERN_HANGING);
        }
    }

    // ================= 兵舎(テント) =================

    /** 7 幅のかまぼこ形の膜(棟は z 方向、z0..z1)。高さ 4(外から 2.5 → 3 → 3.5 → 4 と半段ずつ上がる)。 */
    static void tentVault(Blueprint.Builder b, int z0, int z1) {
        for (int z = z0; z <= z1; z++) {
            b.set(0, 1, z, TENT).set(6, 1, z, TENT);
            b.set(0, 2, z, stairs(TENT_STAIRS, Facing.EAST, false)).set(6, 2, z, stairs(TENT_STAIRS, Facing.WEST, false));
            b.set(1, 3, z, stairs(TENT_STAIRS, Facing.EAST, false)).set(5, 3, z, stairs(TENT_STAIRS, Facing.WEST, false));
            b.fill(2, 3, z, 4, 3, z, slab(TENT_SLAB, "top"));
        }
    }

    /** 空調機(ECU、2 マス): 灰色の箱に送風口の格子。 */
    static void ecu(Blueprint.Builder b, int x, int z) {
        b.fill(x, 1, z, x + 1, 1, z, LGC);
        b.set(x, 2, z, trapdoor("minecraft:iron_trapdoor", Facing.NORTH, false, false));
        b.set(x + 1, 2, z, trapdoor("minecraft:iron_trapdoor", Facing.NORTH, false, false));
    }

    /**
     * 兵舎のテント(7 × 11、レベル 1〜3 の区画): 米軍の TEMPER テントの写し(ユーザー指定 2026-10-01「居住区は家というよりテントみたいな感じ」)。
     * 砂色の膜のかまぼこ屋根(棟は z 方向)、合板の床。入口は正面の妻の真ん中で、脇に巻き上げた幕(開いた落とし戸)。
     * 中は両側に簡易ベッドが 3 台ずつ(頭は奥)、奥にロッカー(樽)と灯り。入口の脇に空調機。
     */
    static Blueprint.Builder barracksTent(Random rng) {
        int w = 7;
        int d = 11;
        int len = 10; // 膜は z 0..9、z 10 は入口の前
        Blueprint.Builder b = new Blueprint.Builder(w, 5, d);
        b.fill(0, 1, 0, w - 1, 4, d - 1, AIR);
        b.fill(0, 0, 0, w - 1, 0, len - 1, SPRUCE);
        tentVault(b, 0, len - 1);
        for (int z : new int[]{0, len - 1}) { // 妻の膜
            b.fill(1, 1, z, 5, 2, z, TENT);
            b.fill(2, 3, z, 4, 3, z, TENT);
        }
        b.set(3, 1, len - 1, AIR).set(3, 2, len - 1, AIR);
        b.set(2, 2, len, trapdoor("minecraft:birch_trapdoor", Facing.SOUTH, true, true));
        b.fill(2, 0, len, 4, 0, len, slab("minecraft:spruce_slab", "top"));
        for (int z = 1; z <= 7; z += 3) {
            b.set(1, 1, z, bed("green", Facing.NORTH, true)).set(1, 1, z + 1, bed("green", Facing.NORTH, false));
            b.set(5, 1, z, bed("green", Facing.NORTH, true)).set(5, 1, z + 1, bed("green", Facing.NORTH, false));
        }
        b.set(3, 1, 1, BARREL).set(3, 2, 1, LANTERN);
        ecu(b, 5, len);
        return b;
    }

    /**
     * 大型テントの兵舎(15 × 11、レベル 4〜5 の区画): 長い TEMPER テント。棟は正面と平行(x 方向、高さ 5)、
     * 正面の真ん中に前室(入口)、前に空調機 2 台。中は奥と手前の壁ぎわに簡易ベッドの列(頭は壁)、両端にロッカーと灯り。
     */
    static Blueprint.Builder barracksTentLarge(Random rng) {
        int w = 15;
        int d = 11;
        Blueprint.Builder b = new Blueprint.Builder(w, 6, d);
        b.fill(0, 1, 0, w - 1, 5, d - 1, AIR);
        b.fill(0, 0, 0, w - 1, 0, 8, SPRUCE);
        for (int x = 0; x < w; x++) { // 膜は z 0..8
            b.set(x, 1, 0, TENT).set(x, 1, 8, TENT);
            b.set(x, 2, 0, stairs(TENT_STAIRS, Facing.SOUTH, false)).set(x, 2, 8, stairs(TENT_STAIRS, Facing.NORTH, false));
            b.set(x, 3, 1, stairs(TENT_STAIRS, Facing.SOUTH, false)).set(x, 3, 7, stairs(TENT_STAIRS, Facing.NORTH, false));
            b.set(x, 4, 2, stairs(TENT_STAIRS, Facing.SOUTH, false)).set(x, 4, 6, stairs(TENT_STAIRS, Facing.NORTH, false));
            b.fill(x, 4, 3, x, 4, 5, slab(TENT_SLAB, "top"));
        }
        for (int x : new int[]{0, w - 1}) { // 妻の膜
            b.fill(x, 1, 1, x, 2, 7, TENT);
            b.fill(x, 3, 2, x, 3, 6, TENT);
            b.fill(x, 4, 3, x, 4, 5, TENT);
        }
        // 前室: 膜を抜いて、正面に 3 幅の箱(屋根はハーフ)
        b.set(7, 1, 8, AIR).set(7, 2, 8, AIR);
        b.fill(6, 1, 9, 6, 2, 9, TENT);
        b.fill(8, 1, 9, 8, 2, 9, TENT);
        b.fill(6, 3, 8, 8, 3, 9, slab(TENT_SLAB, "bottom"));
        b.set(7, 0, 9, SPRUCE);
        b.set(7, 0, 10, slab("minecraft:spruce_slab", "top"));
        // ベッド: 奥の列(頭は北)と手前の列(頭は南、前室の前は空ける)
        for (int x = 1; x <= w - 2; x += 2) {
            b.set(x, 1, 1, bed("green", Facing.NORTH, true)).set(x, 1, 2, bed("green", Facing.NORTH, false));
            if (x != 7) {
                b.set(x, 1, 7, bed("green", Facing.SOUTH, true)).set(x, 1, 6, bed("green", Facing.SOUTH, false));
            }
        }
        b.set(1, 1, 4, BARREL).set(1, 2, 4, LANTERN);
        b.set(w - 2, 1, 4, BARREL).set(w - 2, 2, 4, LANTERN);
        ecu(b, 2, 9);
        ecu(b, 11, 9);
        return b;
    }

    // ================= 倉庫 =================

    /** 天幕の倉庫(11 × 11): 砂色の膜のかまぼこ屋根(z 方向)。正面は大きく開き、中はパレットに木箱と樽。 */
    static Blueprint.Builder warehouseTent(Random rng) {
        int w = 11;
        int d = 11;
        Blueprint.Builder b = new Blueprint.Builder(w, 8, d);
        b.fill(0, 1, 0, w - 1, 7, d - 1, AIR);
        b.fill(0, 0, 0, w - 1, 0, d - 1, GC);
        for (int z = 0; z < d; z++) {
            // 3 列おきに骨組み(灰色の安山岩)が膜の外に浮く
            boolean rib = z % 3 == 2;
            String full = rib ? "minecraft:polished_andesite" : TENT;
            String st = rib ? "minecraft:polished_andesite_stairs" : TENT_STAIRS;
            String sl = rib ? "minecraft:polished_andesite_slab" : TENT_SLAB;
            b.fill(0, 1, z, 0, 2, z, full);
            b.fill(10, 1, z, 10, 2, z, full);
            b.set(0, 3, z, stairs(st, Facing.EAST, false));
            b.set(10, 3, z, stairs(st, Facing.WEST, false));
            b.set(1, 4, z, stairs(st, Facing.EAST, false));
            b.set(9, 4, z, stairs(st, Facing.WEST, false));
            b.set(2, 5, z, stairs(st, Facing.EAST, false));
            b.set(8, 5, z, stairs(st, Facing.WEST, false));
            b.fill(3, 6, z, 7, 6, z, slab(sl, "bottom"));
        }
        for (int z : new int[]{0, d - 1}) { // 妻の膜
            b.fill(1, 1, z, 9, 3, z, TENT);
            b.fill(2, 4, z, 8, 4, z, TENT);
            b.fill(3, 5, z, 7, 5, z, TENT);
        }
        b.fill(3, 1, d - 1, 7, 4, d - 1, AIR); // 大きな入口
        b.fill(3, 5, d - 1, 7, 5, d - 1, TENT);
        // 中: パレット(トウヒのハーフ)に弾薬箱・樽・チェスト
        for (int z = 2; z <= 7; z += 5) {
            b.fill(1, 1, z, 3, 1, z + 1, slab("minecraft:spruce_slab", "bottom"));
            b.fill(7, 1, z, 9, 1, z + 1, slab("minecraft:spruce_slab", "bottom"));
        }
        b.set(1, 1, 1, crate(Facing.EAST)).set(1, 2, 1, crate(Facing.EAST));
        b.set(2, 1, 1, crate(Facing.SOUTH)).set(9, 1, 1, BARREL).set(9, 2, 1, BARREL).set(8, 1, 1, BARREL);
        b.set(3, 1, 1, chest(Facing.SOUTH)).set(7, 1, 1, chest(Facing.SOUTH));
        // 除染機(整備の使用済みフィルターを洗う。2026-10-04。計画は変わらない —— 前からの倉庫には工兵が補修材で置く)
        b.set(5, 1, 1, decontaminator(Facing.SOUTH));
        b.set(5, 5, 5, LANTERN_HANGING).set(5, 6, 5, slab(TENT_SLAB, "bottom"));
        return b;
    }

    /** 鉄骨の倉庫(17 × 11): 腰は灰のコンクリート、上は薄灰。緩い切妻の屋根、正面に 5 幅の搬入口。中は樽とチェストの棚。 */
    static Blueprint.Builder warehouse(Random rng) {
        int w = 17;
        int d = 11;
        int bd = 10;
        Blueprint.Builder b = new Blueprint.Builder(w, 10, d);
        b.fill(0, 1, 0, w - 1, 9, d - 1, AIR);
        b.fill(0, 0, 0, w - 1, 0, d - 1, GC);
        b.ring(0, 1, 0, w - 1, 2, bd - 1, GC);
        b.ring(0, 3, 0, w - 1, 5, bd - 1, LGC);
        for (int[] c : new int[][]{{0, 0}, {w - 1, 0}, {0, bd - 1}, {w - 1, bd - 1}, {8, 0}}) {
            b.fill(c[0], 1, c[1], c[0], 5, c[1], RC);
        }
        // 高窓
        for (int x = 2; x < w - 2; x += 3) {
            b.set(x, 4, 0, CLEAR_GLASS);
        }
        for (int z = 2; z < bd - 1; z += 3) {
            b.set(0, 4, z, CLEAR_GLASS).set(w - 1, 4, z, CLEAR_GLASS);
        }
        // 屋根(緩い切妻、棟は x 方向)
        String roofSlab = "minecraft:smooth_stone_slab";
        for (int x = 0; x < w; x++) {
            b.set(x, 6, 0, slab(roofSlab, "bottom")).set(x, 6, bd - 1, slab(roofSlab, "bottom"));
            b.set(x, 6, 1, slab(roofSlab, "top")).set(x, 6, bd - 2, slab(roofSlab, "top"));
            b.set(x, 7, 2, slab(roofSlab, "bottom")).set(x, 7, bd - 3, slab(roofSlab, "bottom"));
            b.set(x, 7, 3, slab(roofSlab, "top")).set(x, 7, bd - 4, slab(roofSlab, "top"));
            b.set(x, 8, 4, slab(roofSlab, "bottom")).set(x, 8, 5, slab(roofSlab, "bottom"));
        }
        for (int x : new int[]{0, w - 1}) { // 妻壁を屋根まで
            b.fill(x, 6, 2, x, 6, bd - 3, LGC);
            b.fill(x, 7, 4, x, 7, 5, LGC);
        }
        // 搬入口と通用口
        b.fill(6, 1, bd - 1, 10, 4, bd - 1, AIR);
        b.fill(6, 5, bd - 1, 10, 5, bd - 1, YC);
        b.set(7, 5, bd - 1, BLACK_C).set(9, 5, bd - 1, BLACK_C);
        b.set(2, 1, bd - 1, door(DOOR, Facing.NORTH, false, false)).set(2, 2, bd - 1, door(DOOR, Facing.NORTH, true, false));
        b.fill(5, 0, d - 1, 11, 0, d - 1, slab(RC_SLAB, "top"));
        // 棚: 樽 2 段とチェスト、弾薬箱
        for (int x = 1; x < w - 1; x++) {
            if (x == 8) {
                continue;
            }
            b.set(x, 1, 1, BARREL).set(x, 2, 1, x % 2 == 0 ? BARREL : chest(Facing.SOUTH));
        }
        // 除染機(整備の使用済みフィルターを洗う。2026-10-04。真ん中の柱の前の空き)
        b.set(8, 1, 1, decontaminator(Facing.SOUTH));
        for (int z = 3; z <= 6; z++) {
            b.set(1, 1, z, crate(Facing.EAST)).set(w - 2, 1, z, crate(Facing.WEST));
            if (z % 2 == 1) {
                b.set(1, 2, z, crate(Facing.EAST)).set(w - 2, 2, z, crate(Facing.WEST));
            }
        }
        b.set(4, 5, 5, LANTERN_HANGING).set(12, 5, 5, LANTERN_HANGING);
        return b;
    }

    /** コンテナ置き場(大きさは区画どおり): 3 × 7 × 3 のコンテナを並べ、ところどころ 2 段に積む。 */
    static Blueprint.Builder containerYard(int w, int d, Random rng) {
        Blueprint.Builder b = new Blueprint.Builder(w, 8, d);
        b.fill(0, 1, 0, w - 1, 7, d - 1, AIR);
        boolean alongX = w >= d;
        int len = 7;
        int wid = 3;
        int along = alongX ? w : d;
        int across = alongX ? d : w;
        int nAlong = Math.max(1, (along + 1) / (len + 1));
        int nAcross = Math.max(1, (across + 1) / (wid + 1));
        int offAlong = (along - (nAlong * (len + 1) - 1)) / 2;
        int offAcross = (across - (nAcross * (wid + 1) - 1)) / 2;
        for (int i = 0; i < nAlong; i++) {
            for (int j = 0; j < nAcross; j++) {
                if (rng.nextInt(6) == 0) {
                    continue; // 空き
                }
                int u0 = offAlong + i * (len + 1);
                int v0 = offAcross + j * (wid + 1);
                int stack = rng.nextInt(3) == 0 ? 2 : 1;
                for (int s = 0; s < stack; s++) {
                    containerBox(b, alongX, u0, v0, 1 + s * 3, CONTAINER_COLORS[rng.nextInt(CONTAINER_COLORS.length)]);
                }
            }
        }
        return b;
    }

    /** コンテナの箱 1 つ(7 × 3 × 3、両端は鉄の扉)。{@code u0}・{@code v0} は長い向き・短い向きの始まり、{@code y0} は底。 */
    static void containerBox(Blueprint.Builder b, boolean alongX, int u0, int v0, int y0, String color) {
        for (int u = u0; u < u0 + 7; u++) {
            for (int v = v0; v < v0 + 3; v++) {
                int x = alongX ? u : v;
                int z = alongX ? v : u;
                boolean end = u == u0 || u == u0 + 6;
                b.fill(x, y0, z, x, y0 + 2, z, end ? IRON_BLOCK : color);
            }
        }
    }

    /** コンテナ 1 つ(7 × 3、長い辺が正面)。3 つに 1 つは 2 段積み。 */
    static Blueprint.Builder container(Random rng) {
        int stack = rng.nextInt(3) == 0 ? 2 : 1;
        Blueprint.Builder b = new Blueprint.Builder(7, 1 + 3 * stack, 3);
        for (int s = 0; s < stack; s++) {
            containerBox(b, true, 0, 0, 1 + s * 3, CONTAINER_COLORS[rng.nextInt(CONTAINER_COLORS.length)]);
        }
        return b;
    }

    /** 照明塔(3 × 3): 黄色の台車、鎖の柱、てっぺんに道へ向けた投光器、脇に燃料の樽。 */
    static Blueprint.Builder lightTower() {
        Blueprint.Builder b = new Blueprint.Builder(3, 8, 3);
        b.fill(0, 1, 0, 2, 7, 2, AIR);
        b.fill(0, 1, 1, 2, 1, 1, YC);
        b.set(0, 1, 0, slab("minecraft:smooth_stone_slab", "bottom")).set(2, 1, 0, slab("minecraft:smooth_stone_slab", "bottom"));
        b.fill(1, 2, 1, 1, 5, 1, CHAIN_Y);
        b.set(1, 6, 1, LGC);
        b.set(1, 6, 2, floodlight(Facing.SOUTH)).set(0, 6, 1, floodlight(Facing.WEST)).set(2, 6, 1, floodlight(Facing.EAST));
        b.set(1, 1, 2, BARREL);
        return b;
    }

    /** 防爆壁(6 × 1): 鉄筋コンクリートの塀を 2 段(塀どうしがつながって低い壁になる)。 */
    static Blueprint.Builder barrier() {
        Blueprint.Builder b = new Blueprint.Builder(6, 3, 1);
        b.fill(0, 1, 0, 5, 2, 0, RC_WALL);
        return b;
    }

    // ================= 弾薬庫 =================

    /**
     * 弾薬庫(9 × 11、角の区画では金網で囲う): 鉄筋のかまぼこ形の掩体に土を被せ、正面は鉄筋の妻壁と扉、
     * 黄黒の縞と投光器。背の 2 隅に避雷針の柱。中は弾薬箱を積む。
     */
    static Blueprint.Builder ammoBunker(int w, int d, Random rng) {
        int bw = 9;
        int bdp = 11;
        int ox = Math.max(0, (w - bw) / 2);
        int oz = Math.max(0, d > bdp ? 0 : 0);
        Blueprint.Builder b = new Blueprint.Builder(Math.max(w, bw), 8, Math.max(d, bdp));
        b.fill(0, 1, 0, b.w - 1, 7, b.d - 1, AIR);
        // 掩体: 中は x 2..6、z 2..8
        b.fill(ox + 1, 0, oz + 1, ox + 7, 0, oz + 9, RC);
        b.fill(ox + 1, 1, oz + 1, ox + 7, 3, oz + 8, RC);
        b.fill(ox + 2, 1, oz + 2, ox + 6, 3, oz + 8, AIR);
        b.fill(ox + 1, 4, oz + 1, ox + 7, 4, oz + 8, RC);
        // 土を被せる: 横から見て 4 → 5 → 6 → 5 → 4 の丸い盛り土(背も 1 段低く)
        int[] heights = {4, 5, 6, 6, 6, 6, 6, 5, 4};
        for (int x = 0; x <= 8; x++) {
            int top = heights[x];
            for (int z = 0; z <= 8; z++) {
                int t = z == 0 ? top - 1 : top;
                for (int y = 1; y < t; y++) {
                    if (b.get(ox + x, y, oz + z).equals(AIR) || b.get(ox + x, y, oz + z).equals(KEEP)) {
                        boolean inside = x >= 2 && x <= 6 && z >= 2 && y <= 3;
                        if (!inside) {
                            b.set(ox + x, y, oz + z, DIRT);
                        }
                    }
                }
                b.set(ox + x, t, oz + z, GRASS);
            }
        }
        // 正面の妻壁(鉄筋)と扉、扉の上だけ黄黒の表示
        b.fill(ox, 1, oz + 9, ox + 8, 4, oz + 9, RC);
        b.set(ox + 4, 1, oz + 9, door(DOOR, Facing.NORTH, false, false));
        b.set(ox + 4, 2, oz + 9, door(DOOR, Facing.NORTH, true, false));
        b.set(ox + 3, 3, oz + 9, YC).set(ox + 4, 3, oz + 9, BLACK_C).set(ox + 5, 3, oz + 9, YC);
        b.set(ox + 4, 5, oz + 9, floodlight(Facing.SOUTH));
        b.fill(ox + 1, 0, oz + 10, ox + 7, 0, oz + 10, GC);
        // 避雷針の柱
        for (int x : new int[]{ox, ox + 8}) {
            b.fill(x, 3, oz, x, 6, oz, IRON_BARS);
            b.set(x, 7, oz, LIGHTNING_ROD);
        }
        // 中: 弾薬箱を 2 段
        for (int z = oz + 3; z <= oz + 7; z++) {
            b.set(ox + 2, 1, z, crate(Facing.EAST)).set(ox + 6, 1, z, crate(Facing.WEST));
            if (rng.nextBoolean()) {
                b.set(ox + 2, 2, z, crate(Facing.EAST));
            }
            if (rng.nextBoolean()) {
                b.set(ox + 6, 2, z, crate(Facing.WEST));
            }
        }
        b.set(ox + 4, 3, oz + 5, LANTERN_HANGING);
        // 角の区画: 金網で囲い、正面の真ん中に門扉 3 枚。四隅に投光器の柱
        if (b.w > bw || b.d > bdp) {
            int mx = b.w - 1;
            int mz = b.d - 1;
            for (int x = 0; x <= mx; x++) {
                fillFree(b, x, 1, 0, x, 2, 0, CHAIN_LINK);
                fillFree(b, x, 1, mz, x, 2, mz, CHAIN_LINK);
            }
            for (int z = 0; z <= mz; z++) {
                fillFree(b, 0, 1, z, 0, 2, z, CHAIN_LINK);
                fillFree(b, mx, 1, z, mx, 2, z, CHAIN_LINK);
            }
            int c = b.w / 2;
            for (int x = c - 1; x <= c + 1; x++) {
                b.set(x, 1, mz, fenceGate(GATE, Facing.SOUTH)).set(x, 2, mz, AIR);
            }
            for (int[] p : new int[][]{{0, 0}, {mx, 0}, {0, mz}, {mx, mz}}) {
                b.fill(p[0], 1, p[1], p[0], 3, p[1], RC_WALL);
                b.set(p[0], 4, p[1], floodlight(p[1] == 0 ? Facing.SOUTH : Facing.NORTH));
            }
        }
        return b;
    }

    // ================= 工廠 =================

    /** 工廠(15 × 11): 腰は縞鋼板、上は薄灰のコンクリート、7 幅の大扉と黄黒の縞。中は金床・鍛冶台・溶鉱炉・砥石と吊りフック。 */
    static Blueprint.Builder workshop(Random rng) {
        int w = 15;
        int d = 11;
        int bd = 10;
        Blueprint.Builder b = new Blueprint.Builder(w, 9, d);
        b.fill(0, 1, 0, w - 1, 8, d - 1, AIR);
        b.fill(0, 0, 0, w - 1, 0, d - 1, GC);
        b.ring(0, 1, 0, w - 1, 2, bd - 1, STEEL);
        b.ring(0, 3, 0, w - 1, 6, bd - 1, LGC);
        for (int[] c : new int[][]{{0, 0}, {w - 1, 0}, {0, bd - 1}, {w - 1, bd - 1}, {7, 0}}) {
            b.fill(c[0], 1, c[1], c[0], 6, c[1], RC);
        }
        b.fill(0, 7, 0, w - 1, 7, bd - 1, GC);
        b.ring(0, 8, 0, w - 1, 8, bd - 1, RC_WALL);
        for (int x = 3; x < w - 2; x += 4) { // 天窓
            b.fill(x, 7, 3, x, 7, 6, "minecraft:glass");
        }
        for (int z = 2; z < bd - 1; z += 2) { // 高窓
            b.set(0, 5, z, CLEAR_GLASS).set(w - 1, 5, z, CLEAR_GLASS);
        }
        // 大扉
        b.fill(4, 1, bd - 1, 10, 5, bd - 1, AIR);
        for (int x = 4; x <= 10; x++) {
            b.set(x, 6, bd - 1, x % 2 == 0 ? YC : BLACK_C);
        }
        b.fill(4, 0, d - 1, 10, 0, d - 1, slab(RC_SLAB, "top"));
        b.set(2, 5, d - 1, KEEP);
        b.set(3, 6, d - 1, floodlight(Facing.SOUTH));
        b.set(11, 6, d - 1, floodlight(Facing.SOUTH));
        // 作業場
        b.set(2, 1, 1, facing("minecraft:anvil", Facing.EAST));
        b.set(3, 1, 1, SMITHING).set(4, 1, 1, CRAFTING);
        b.set(6, 1, 1, facing("minecraft:blast_furnace", Facing.SOUTH)).set(8, 1, 1, facing("minecraft:blast_furnace", Facing.SOUTH));
        b.set(10, 1, 1, "minecraft:grindstone[face=floor,facing=south]");
        b.set(11, 1, 1, CRAFTING).set(12, 1, 1, facing("minecraft:anvil", Facing.EAST));
        b.fill(1, 1, 3, 1, 2, 7, BARREL);
        b.fill(13, 1, 3, 13, 1, 7, chest(Facing.WEST));
        // 吊りフック(鎖)
        b.fill(7, 4, 5, 7, 6, 5, CHAIN_Y);
        b.set(7, 3, 5, "minecraft:tripwire_hook[attached=false,facing=south,powered=false]");
        b.set(3, 6, 5, LANTERN_HANGING).set(11, 6, 5, LANTERN_HANGING);
        return b;
    }

    /** 車両の屋根(13 × 7): 鉄筋の柱と平らな屋根だけ。床はアスファルト、奥に工具棚とドラム缶。 */
    static Blueprint.Builder vehicleShed(Random rng) {
        int w = 13;
        int d = 7;
        Blueprint.Builder b = new Blueprint.Builder(w, 6, d);
        b.fill(0, 1, 0, w - 1, 5, d - 1, AIR);
        b.fill(0, 0, 0, w - 1, 0, d - 1, ASPHALT);
        for (int x = 0; x < w; x += 4) {
            b.fill(x, 1, 0, x, 3, 0, RC_WALL);
            b.fill(x, 1, d - 1, x, 3, d - 1, RC_WALL);
        }
        b.fill(0, 4, 0, w - 1, 4, d - 1, slab("minecraft:smooth_stone_slab", "bottom"));
        b.fill(1, 1, 0, w - 2, 2, 0, GC);
        for (int x = 0; x < w; x += 4) {
            b.set(x, 1, 0, RC_WALL).set(x, 2, 0, RC_WALL).set(x, 3, 0, RC_WALL);
        }
        b.set(2, 1, 1, "minecraft:red_concrete").set(3, 1, 1, CRAFTING);
        b.set(9, 1, 1, BARREL).set(10, 1, 1, BARREL).set(10, 2, 1, BARREL);
        for (int x = 2; x < w; x += 4) { // 車の枠の白線
            b.fill(x + 2, 0, 2, x + 2, 0, d - 1, WC);
        }
        b.set(6, 3, 3, LANTERN_HANGING);
        return b;
    }

    /** 駐車場(大きさは区画どおり): アスファルトに白線、隅に投光器の柱。 */
    static Blueprint.Builder parking(int w, int d, Random rng) {
        Blueprint.Builder b = new Blueprint.Builder(w, 5, d);
        b.fill(0, 1, 0, w - 1, 4, d - 1, AIR);
        b.fill(0, 0, 0, w - 1, 0, d - 1, ASPHALT);
        for (int x = 1; x < w - 1; x += 3) {
            b.fill(x, 0, 1, x, 0, Math.max(1, d / 2 - 1), WC);
        }
        b.fill(0, 1, 0, 0, 3, 0, RC_WALL);
        b.set(0, 4, 0, floodlight(Facing.SOUTH));
        if (w > 8) {
            b.fill(w - 1, 1, 0, w - 1, 3, 0, RC_WALL);
            b.set(w - 1, 4, 0, floodlight(Facing.SOUTH));
        }
        return b;
    }

    // ================= 防御 =================

    /**
     * 見張り台(5 × 5): 中はらせん階段(8 段で 1 周)、9 段目の床に土嚢の胸壁と屋根、外向きの投光器、12 段目から屋根の砲台へ。入口は正面。
     * {@code wood} なら木造(外周が金網のレベル 1〜3)、偽なら鉄筋(壁のレベル 4〜5)。
     */
    static Blueprint.Builder watchtower(boolean wood, Random rng) {
        Blueprint.Builder b = new Blueprint.Builder(5, 15, 5);
        tower(b, 0, 0, false, wood, rng.nextBoolean());
        return b;
    }

    /**
     * 塔 1 本を (ox, oz) から 5 × 5 で書く。{@code backDoor} なら入口を奥(z = 0 側)にする(正門の両脇の塔)。
     * らせん: 入口の内側 (2,3) から時計と逆に (1,3) 1 段目 → (1,2) → (1,1) → (2,1) → (3,1) → (3,2) → (3,3) → (2,3) → (1,3) 9 段目(見張りの床)→ (1,2) → (1,1) → (2,1) 12 段目(屋根の口の下)。
     * 木造はトウヒの板壁に丸太の隅柱・トウヒの階段・柵の屋根の柱、鉄筋は鉄筋コンクリートに石レンガの階段・塀の柱。
     * 屋根の真ん中に外へ向けた機関砲台、{@code purifier} なら見張り床の奥の東の隅に胞子除去装置(ユーザー指定 2026-10-02「壁や見張り塔に機関砲や胞子除去装置」)。
     */
    static void tower(Blueprint.Builder b, int ox, int oz, boolean backDoor, boolean wood, boolean purifier) {
        String wall = wood ? SPRUCE : RC;
        String core = wood ? SPRUCE_LOG : RC;
        String stair = wood ? "minecraft:spruce_stairs" : "minecraft:stone_brick_stairs";
        String post = wood ? "minecraft:spruce_fence" : RC_WALL;
        String roof = wood ? slab("minecraft:spruce_slab", "bottom") : slab(RC_SLAB, "bottom");
        java.util.function.IntUnaryOperator fz = z -> oz + (backDoor ? 4 - z : z);
        java.util.function.UnaryOperator<Facing> ff = f -> backDoor && f.dx == 0 ? f.opposite() : f;
        b.fill(ox, 1, oz, ox + 4, 14, oz + 4, AIR);
        b.fill(ox, 0, oz, ox + 4, 0, oz + 4, wall);
        b.ring(ox, 1, oz, ox + 4, 8, oz + 4, wall);
        if (wood) {
            for (int[] c : new int[][]{{0, 0}, {4, 0}, {0, 4}, {4, 4}}) {
                b.fill(ox + c[0], 1, oz + c[1], ox + c[0], 9, oz + c[1], SPRUCE_LOG);
            }
        }
        b.fill(ox + 2, 1, fz.applyAsInt(2), ox + 2, 8, fz.applyAsInt(2), core);
        int[][] steps = {{1, 3, 1}, {1, 2, 2}, {1, 1, 3}, {2, 1, 4}, {3, 1, 5}, {3, 2, 6}, {3, 3, 7}, {2, 3, 8}, {1, 3, 9}};
        Facing[] dirs = {Facing.WEST, Facing.NORTH, Facing.NORTH, Facing.EAST, Facing.EAST, Facing.SOUTH, Facing.SOUTH, Facing.WEST, Facing.WEST};
        for (int i = 0; i < steps.length; i++) {
            int[] s = steps[i];
            b.set(ox + s[0], s[2], fz.applyAsInt(s[1]), stairs(stair, ff.apply(dirs[i]), false));
        }
        // 入口(扉)と細い窓
        b.set(ox + 2, 1, fz.applyAsInt(4), door(DOOR, ff.apply(Facing.NORTH), false, false));
        b.set(ox + 2, 2, fz.applyAsInt(4), door(DOOR, ff.apply(Facing.NORTH), true, false));
        b.set(ox + 2, 4, fz.applyAsInt(0), IRON_BARS).set(ox + 2, 7, fz.applyAsInt(0), IRON_BARS);
        b.set(ox, 5, fz.applyAsInt(2), IRON_BARS).set(ox + 4, 5, fz.applyAsInt(2), IRON_BARS);
        // 見張りの床(9 段目)。段を上がる間は体がまだ元の段に残るので、6〜8 段目の頭上は穴にして、立っている段の上に 3 マス空ける
        // (ユーザー指摘 2026-10-02「階段の上に格子」「登れない階段」: 6 段目の上が床、7 段目の上が手すりの格子で頭がつかえた)
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                if (!(wood && (x == 0 || x == 4) && (z == 0 || z == 4))) {
                    b.set(ox + x, 9, oz + z, wall);
                }
            }
        }
        b.set(ox + 1, 9, fz.applyAsInt(3), stairs(stair, ff.apply(Facing.WEST), false));
        b.set(ox + 3, 9, fz.applyAsInt(2), AIR).set(ox + 3, 9, fz.applyAsInt(3), AIR).set(ox + 2, 9, fz.applyAsInt(3), AIR);
        b.set(ox + 3, 10, fz.applyAsInt(2), IRON_BARS); // 手すりは 6 段目の穴の上だけ(7・8 段目の上は頭が通る)
        // 屋根の砲台へ: らせんの続き(10〜12 段目)と、その頭上の屋根の口(ユーザー指摘 2026-10-02「たどり着く経路の無い機関砲台」)
        b.set(ox + 1, 10, fz.applyAsInt(2), stairs(stair, ff.apply(Facing.NORTH), false));
        b.set(ox + 1, 11, fz.applyAsInt(1), stairs(stair, ff.apply(Facing.NORTH), false));
        b.set(ox + 2, 12, fz.applyAsInt(1), stairs(stair, ff.apply(Facing.EAST), false));
        // 土嚢の胸壁・屋根の柱・屋根・投光器
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                boolean edge = x == 0 || x == 4 || z == 0 || z == 4;
                boolean corner = (x == 0 || x == 4) && (z == 0 || z == 4);
                if (corner) {
                    b.fill(ox + x, 10, oz + z, ox + x, 12, oz + z, post);
                } else if (edge) {
                    b.set(ox + x, 10, oz + z, SANDBAGS);
                }
            }
        }
        b.fill(ox, 13, oz, ox + 4, 13, oz + 4, roof);
        b.set(ox + 1, 13, fz.applyAsInt(2), AIR).set(ox + 1, 13, fz.applyAsInt(1), AIR).set(ox + 2, 13, fz.applyAsInt(1), AIR);
        b.set(ox + 2, 13, oz + 2, wall); // 砲台の台座
        b.set(ox + 2, 14, oz + 2, gunTurret(ff.apply(Facing.NORTH)));
        b.set(ox + 2, 11, fz.applyAsInt(0), floodlight(ff.apply(Facing.NORTH)));
        if (purifier) {
            b.set(ox + 3, 10, fz.applyAsInt(1), sporePurifier(ff.apply(Facing.SOUTH)));
        }
    }

    /** 正門(壁の時期、17 × 5): 両脇に鉄筋の塔(入口は基地の内側)、真ん中に 7 幅の門と、その上の渡り(土嚢の胸壁)。門扉は閉じた柵の門 7 枚。 */
    static Blueprint.Builder gatehouse() {
        Blueprint.Builder b = new Blueprint.Builder(17, 15, 5);
        tower(b, 0, 0, true, false, false);
        tower(b, 12, 0, true, false, false);
        b.fill(5, 1, 0, 11, 5, 4, AIR);
        b.fill(5, 0, 0, 11, 0, 4, ASPHALT);
        b.fill(5, 6, 0, 11, 7, 4, RC);
        b.fill(5, 8, 0, 11, 8, 0, SANDBAGS);
        b.fill(5, 8, 4, 11, 8, 4, SANDBAGS);
        for (int x = 5; x <= 11; x++) {
            b.set(x, 1, 2, fenceGate(GATE, Facing.SOUTH));
            b.set(x, 5, 4, x % 2 == 0 ? YC : BLACK_C);
        }
        b.set(8, 6, 4, wallBanner("green", Facing.SOUTH));
        b.set(6, 8, 2, floodlight(Facing.SOUTH)).set(10, 8, 2, floodlight(Facing.SOUTH));
        b.set(8, 4, 1, LANTERN_HANGING);
        return b;
    }

    /**
     * 正門(金網の時期、17 × 5): 両脇に土嚢の詰所(入口は基地の内側、屋根に投光器)、真ん中に 7 幅の門(閉じた柵の門 7 枚)、
     * 詰所の屋根に渡した梁に旗。金網は詰所の土嚢につながる。
     */
    static Blueprint.Builder fenceGatehouse() {
        Blueprint.Builder b = new Blueprint.Builder(17, 6, 5);
        b.fill(0, 1, 0, 16, 5, 4, AIR);
        booth(b, 0);
        booth(b, 12);
        b.fill(5, 0, 0, 11, 0, 4, ASPHALT);
        for (int x = 5; x <= 11; x++) {
            b.set(x, 1, 2, fenceGate(GATE, Facing.SOUTH));
        }
        b.fill(4, 4, 2, 12, 4, 2, log("minecraft:spruce_log", "x"));
        b.set(8, 3, 2, LANTERN_HANGING);
        b.set(8, 4, 3, wallBanner("green", Facing.SOUTH));
        return b;
    }

    /** 詰所(5 × 5): 腰までの土嚢の囲い、丸太の隅柱に板の屋根、入口は基地の内側(z = 0)、屋根に外向きの投光器。 */
    static void booth(Blueprint.Builder b, int ox) {
        b.fill(ox, 0, 0, ox + 4, 0, 4, SPRUCE);
        b.ring(ox, 1, 0, ox + 4, 2, 4, SANDBAGS);
        b.set(ox + 2, 1, 0, AIR).set(ox + 2, 2, 0, AIR);
        for (int[] c : new int[][]{{0, 0}, {4, 0}, {0, 4}, {4, 4}}) {
            b.fill(ox + c[0], 1, c[1], ox + c[0], 3, c[1], SPRUCE_LOG);
        }
        b.fill(ox, 4, 0, ox + 4, 4, 4, SPRUCE);
        b.set(ox + 2, 5, 3, floodlight(Facing.SOUTH));
        b.set(ox + 1, 1, 1, chest(Facing.SOUTH)).set(ox + 3, 1, 1, BARREL);
    }

    /** 検問所(15 × 11、正面 = 基地の外): 道(x 4..10)をジグザグに塞ぐ車止め 3 列、詰所、土嚢の銃座、投光器の柱。 */
    static Blueprint.Builder checkpoint() {
        Blueprint.Builder b = new Blueprint.Builder(15, 6, 11);
        b.fill(0, 1, 0, 14, 5, 10, AIR);
        b.fill(3, 1, 2, 7, 1, 2, RC_WALL);
        b.fill(7, 1, 5, 10, 1, 5, RC_WALL); // 詰所の扉の前(x = 11)は空ける
        b.fill(3, 1, 8, 7, 1, 8, RC_WALL);
        // 詰所
        b.fill(12, 0, 4, 14, 0, 6, SMOOTH);
        b.ring(12, 1, 4, 14, 2, 6, RC);
        b.set(12, 1, 5, door(DOOR, Facing.EAST, false, false)).set(12, 2, 5, door(DOOR, Facing.EAST, true, false));
        b.set(13, 2, 4, CLEAR_GLASS).set(13, 2, 6, CLEAR_GLASS).set(14, 2, 5, CLEAR_GLASS);
        b.fill(12, 3, 4, 14, 3, 6, slab(RC_SLAB, "bottom"));
        b.set(13, 1, 5, chest(Facing.WEST));
        // 土嚢の銃座
        b.fill(0, 1, 10, 2, 2, 10, SANDBAGS);
        b.fill(0, 1, 8, 0, 2, 9, SANDBAGS);
        b.fill(2, 1, 8, 2, 1, 9, SANDBAGS);
        // 投光器の柱
        b.fill(1, 1, 3, 1, 3, 3, RC_WALL);
        b.set(1, 4, 3, floodlight(Facing.EAST));
        b.fill(13, 1, 9, 13, 3, 9, RC_WALL);
        b.set(13, 4, 9, floodlight(Facing.WEST));
        return b;
    }

    // ================= 除去塔(浄化圏の芯) =================

    /**
     * 中継の除去塔(5 × 5、高さ 14): 四隅の塀の脚と真ん中の柱(南の面にはしご)、脚のあいだに鉄格子の帯、y = 11 の見張り床、
     * 床の真ん中(柱の上)に胞子除去装置、床の縁に塀の手すりと、四隅の上に外向きの投光器。浄化圏の真ん中の広場に立つ。
     */
    static Blueprint.Builder purifierMast() {
        Blueprint.Builder b = new Blueprint.Builder(5, 14, 5);
        b.fill(0, 1, 0, 4, 13, 4, AIR);
        b.fill(0, 0, 0, 4, 0, 4, RC);
        for (int[] c : new int[][]{{0, 0}, {4, 0}, {0, 4}, {4, 4}}) {
            b.fill(c[0], 1, c[1], c[0], 10, c[1], RC_WALL);
        }
        b.fill(2, 1, 2, 2, 11, 2, RC);
        b.fill(2, 1, 3, 2, 11, 3, facing("minecraft:ladder", Facing.SOUTH));
        for (int y : new int[]{4, 8}) {
            b.fill(1, y, 0, 3, y, 0, IRON_BARS);
            b.fill(1, y, 4, 3, y, 4, IRON_BARS);
            b.fill(0, y, 1, 0, y, 3, IRON_BARS);
            b.fill(4, y, 1, 4, y, 3, IRON_BARS);
        }
        b.fill(0, 11, 0, 4, 11, 4, RC);
        b.set(2, 11, 3, facing("minecraft:ladder", Facing.SOUTH));
        b.ring(0, 12, 0, 4, 12, 4, RC_WALL);
        b.set(2, 12, 2, sporePurifier(Facing.SOUTH));
        b.set(0, 13, 0, floodlight(Facing.NORTH)).set(4, 13, 4, floodlight(Facing.SOUTH))
                .set(4, 13, 0, floodlight(Facing.EAST)).set(0, 13, 4, floodlight(Facing.WEST));
        return b;
    }

    /**
     * 大除去塔(7 × 7、高さ 23): 中心の浄化圏の管理棟の裏に立つ基地の目印。3 × 3 の鉄筋の柱(南の面にはしご)と四隅の塀の脚、
     * 2 段の帯、y = 19 の見張り床、床の真ん中に胞子除去装置、塀の手すりと四隅の上に投光器、柱の天辺の灯り。
     */
    static Blueprint.Builder purifierTower() {
        Blueprint.Builder b = new Blueprint.Builder(7, 23, 7);
        b.fill(0, 1, 0, 6, 22, 6, AIR);
        b.fill(0, 0, 0, 6, 0, 6, RC);
        for (int[] c : new int[][]{{0, 0}, {6, 0}, {0, 6}, {6, 6}}) {
            b.fill(c[0], 1, c[1], c[0], 18, c[1], RC_WALL);
        }
        b.fill(2, 1, 2, 4, 18, 4, RC);
        b.fill(3, 1, 5, 3, 19, 5, facing("minecraft:ladder", Facing.SOUTH));
        for (int y : new int[]{6, 12}) {
            b.ring(0, y, 0, 6, y, 6, slab(RC_SLAB, "bottom"));
            b.set(3, y, 6, AIR);
        }
        b.set(3, 6, 5, facing("minecraft:ladder", Facing.SOUTH)).set(3, 12, 5, facing("minecraft:ladder", Facing.SOUTH));
        b.fill(2, 9, 0, 4, 9, 0, IRON_BARS);
        b.fill(0, 9, 2, 0, 9, 4, IRON_BARS);
        b.fill(6, 9, 2, 6, 9, 4, IRON_BARS);
        b.fill(0, 19, 0, 6, 19, 6, RC);
        b.set(3, 19, 5, facing("minecraft:ladder", Facing.SOUTH));
        b.ring(0, 20, 0, 6, 20, 6, RC_WALL);
        b.set(3, 20, 3, sporePurifier(Facing.SOUTH));
        b.set(0, 21, 0, floodlight(Facing.NORTH)).set(6, 21, 6, floodlight(Facing.SOUTH))
                .set(6, 21, 0, floodlight(Facing.EAST)).set(0, 21, 6, floodlight(Facing.WEST));
        b.set(1, 20, 1, SEA_LANTERN).set(5, 20, 5, SEA_LANTERN);
        b.set(3, 22, 3, AIR);
        return b;
    }

    // ================= 特別な施設(2026-10-02 夜、ユーザー指定「野戦病院は医療テント、研究は野外研究施設、観測には観測施設」。形と中身は仮) =================

    private static final String WOOL = "minecraft:white_wool";
    private static final String RED_WOOL = "minecraft:red_wool";
    private static final String MED_SLAB = "minecraft:smooth_quartz_slab";
    private static final String LAB_DOOR = "minecraft:birch_door";
    private static final String TEAL = "minecraft:cyan_terracotta";

    /**
     * 医療テント(11 × 11、野戦病院の医療区): 白い羊毛のかまぼこ屋根(棟は z 方向、高さ 5。羊毛には階段・ハーフが無いので段で丸める:
     * 外から 2 → 3 → 4 → 5)、床は薄灰のコンクリート。正面の妻の真ん中に幅 3 の入口(担架が通る)、その上に小さな赤十字を 1 つだけ
     * (ユーザー指定 2026-10-02 夜「赤十字の主張がつよすぎる」「白の羊毛を使用してください」)。
     * 中は両側の壁ぎわに簡易ベッド 4 台ずつ(白、頭は壁)、真ん中の通路に処置台、奥に薬の調合台(醸造台)・水の大釜・薬品の樽と灯り、天井から白い灯りの棒。
     */
    static Blueprint.Builder medicalTent() {
        int w = 11;
        int d = 11;
        int len = 10; // 膜は z 0..9、z 10 は入口の前
        Blueprint.Builder b = new Blueprint.Builder(w, 6, d);
        b.fill(0, 1, 0, w - 1, 5, d - 1, AIR);
        b.fill(0, 0, 0, w - 1, 0, len - 1, LGC);
        for (int z = 0; z < len; z++) { // 段のかまぼこ
            b.fill(0, 1, z, 0, 2, z, WOOL).fill(w - 1, 1, z, w - 1, 2, z, WOOL);
            b.set(1, 3, z, WOOL).set(w - 2, 3, z, WOOL);
            b.set(2, 4, z, WOOL).set(w - 3, 4, z, WOOL);
            b.fill(3, 5, z, w - 4, 5, z, WOOL);
        }
        for (int z : new int[]{0, len - 1}) { // 妻
            b.fill(1, 1, z, w - 2, 2, z, WOOL);
            b.fill(2, 3, z, w - 3, 3, z, WOOL);
            b.fill(3, 4, z, w - 4, 4, z, WOOL);
        }
        // 入口(幅 3・高さ 2)と前の段、入口の上の小さな赤十字
        b.fill(4, 1, len - 1, 6, 2, len - 1, AIR);
        b.fill(4, 0, len, 6, 0, len, slab("minecraft:birch_slab", "top"));
        b.set(5, 3, len - 1, RED_WOOL).set(4, 4, len - 1, RED_WOOL).set(5, 4, len - 1, RED_WOOL).set(6, 4, len - 1, RED_WOOL)
                .set(5, 5, len - 1, RED_WOOL);
        // 簡易ベッド(頭は壁)
        for (int z = 1; z <= 7; z += 2) {
            b.set(1, 1, z, bed("white", Facing.WEST, true)).set(2, 1, z, bed("white", Facing.WEST, false));
            b.set(w - 2, 1, z, bed("white", Facing.EAST, true)).set(w - 3, 1, z, bed("white", Facing.EAST, false));
        }
        // 奥: 薬の調合台・水の大釜・薬品の樽と灯り。真ん中: 処置台。天井: 白い灯りの棒
        b.set(4, 1, 1, BREWING_STAND);
        b.set(5, 1, 1, WATER_CAULDRON_FULL);
        b.set(6, 1, 1, BARREL).set(6, 2, 1, LANTERN);
        b.set(5, 1, 4, slab(MED_SLAB, "top")).set(5, 1, 5, slab(MED_SLAB, "top"));
        b.set(5, 4, 2, END_ROD_DOWN).set(5, 4, 7, END_ROD_DOWN);
        return b;
    }

    private static final String BREWING_STAND = "minecraft:brewing_stand[has_bottle_0=false,has_bottle_1=false,has_bottle_2=false]";
    private static final String WATER_CAULDRON_FULL = "minecraft:water_cauldron[level=3]";
    private static final String END_ROD_DOWN = "minecraft:end_rod[facing=down]";
    private static final String RESEARCH_TABLE = "overgrown_vegetation:research_table";

    /**
     * 野外研究施設(25 × 19、研究基地の出城の真ん中に 1 棟。ユーザー指定 2026-10-02 夜「即席のようなものではなくガッツリそこに構えて研究している感じ」
     * 「何棟も置くものではなく一棟だけ大きな施設」、同日その 3「施設横の植物を育てているような箇所はいらない」): 腰を据えた研究所の本館。
     * <ul>
     *     <li>真ん中の棟(x 7..17、3 階建て): 正面はガラスの壁と 3 枚の扉・庇、1 階は受付と標本の陳列(ガラスの囲いの中に苔の床とツツジ、天井から胞子の花)、
     *     2 階は研究室(研究台 3 台・実験台に醸造台・流し・標本の樽・書見台)、3 階は所長室と資料室(本棚・机・海図台)。階段は管理棟と同じ折り返し。
     *     屋上に天窓・パラボラアンテナ・アンテナの柱・空調機。</li>
     *     <li>東西の研究棟(x 0..6 と 18..24、2 階建て): 1 階は化学・分析の実験室(研究台・醸造台・流し・実験台・標本の樽)、2 階は標本庫と資料(本棚・書見台・箱)。
     *     外の壁の真ん中(奥行の真ん中)に 3 枚の扉 —— 出城が広がると、ここから増築した棟へ連絡通路がのびる。</li>
     * </ul>
     * 帯の色は青緑(研究の色。仮)。設備(機関砲台・除去装置)は置かない。
     */
    static Blueprint.Builder fieldLab() {
        int w = 25;
        int d = 19;
        Blueprint.Builder b = new Blueprint.Builder(w, 22, d);
        b.fill(0, 1, 0, w - 1, 21, d - 1, AIR);
        // ---- 土台(z 0..16)と正面の前の段(z 17..18)----
        b.fill(0, 0, 0, w - 1, 0, 16, RC);
        b.fill(1, 0, 1, w - 2, 0, 15, SMOOTH);
        b.fill(9, 0, 17, 15, 0, 18, slab("minecraft:smooth_stone_slab", "top"));
        // ---- 東西の研究棟(2 階建て、屋根 y = 8)----
        for (boolean west : new boolean[]{true, false}) {
            int x0 = west ? 0 : 17; // 真ん中の棟との境の壁(x 7 / 17)まで含めて組み、真ん中の棟の壁が上書きする
            int x1 = west ? 7 : w - 1;
            int outer = west ? 0 : w - 1;
            int mid = west ? 4 : 20;
            Facing in = west ? Facing.EAST : Facing.WEST;
            b.ring(x0, 1, 0, x1, 7, 16, LGC);
            b.ring(x0, 4, 0, x1, 4, 16, TEAL);
            for (int[] c : new int[][]{{outer, 0}, {outer, 6}, {outer, 12}, {outer, 16}, {mid, 0}, {mid, 16}}) {
                b.fill(c[0], 1, c[1], c[0], 8, c[1], RC);
            }
            b.fill(x0 + 1, 4, 1, x1 - 1, 4, 15, SMOOTH);
            b.fill(x0, 8, 0, x1, 8, 16, GC);
            b.ring(x0, 9, 0, x1, 9, 16, RC_WALL);
            for (int y : new int[]{2, 3, 6, 7}) {
                for (int z : new int[]{2, 3, 4, 13, 14, 15}) {
                    b.set(outer, y, z, GLASS);
                }
                for (int x = x0 + 1; x <= x1 - 1; x++) {
                    if (x != mid) {
                        b.set(x, y, 0, GLASS).set(x, y, 16, GLASS);
                    }
                }
            }
            for (int z = 8; z <= 10; z++) { // 外の壁の真ん中の 3 枚の扉(連絡通路の出入口)と欄間、2 階の窓
                b.set(outer, 1, z, door(LAB_DOOR, in, false, z == 10));
                b.set(outer, 2, z, door(LAB_DOOR, in, true, z == 10));
                b.set(outer, 3, z, GLASS).set(outer, 6, z, GLASS).set(outer, 7, z, GLASS);
            }
        }
        // 1 階: 西 = 化学の実験室、東 = 分析の実験室(外の扉の前の 1 列は空ける)
        for (boolean west : new boolean[]{true, false}) {
            java.util.function.IntUnaryOperator mx = x -> west ? x : w - 1 - x;
            b.set(mx.applyAsInt(2), 1, 1, facing(RESEARCH_TABLE, Facing.SOUTH)).set(mx.applyAsInt(4), 1, 1, facing(RESEARCH_TABLE, Facing.SOUTH));
            b.set(mx.applyAsInt(1), 1, 3, BREWING_STAND).set(mx.applyAsInt(1), 1, 4, WATER_CAULDRON_FULL).set(mx.applyAsInt(1), 1, 5, BREWING_STAND);
            b.fill(mx.applyAsInt(1), 1, 12, mx.applyAsInt(1), 1, 14, slab(MED_SLAB, "top"));
            b.set(mx.applyAsInt(1), 2, 12, BREWING_STAND).set(mx.applyAsInt(1), 2, 14, BREWING_STAND);
            b.fill(mx.applyAsInt(2), 1, 15, mx.applyAsInt(5), 1, 15, BARREL);
            b.set(mx.applyAsInt(2), 2, 15, LANTERN).set(mx.applyAsInt(5), 2, 15, LANTERN);
            b.set(mx.applyAsInt(4), 3, 5, LANTERN_HANGING).set(mx.applyAsInt(4), 3, 11, LANTERN_HANGING);
            // 2 階: 標本庫と資料
            b.fill(mx.applyAsInt(1), 5, 2, mx.applyAsInt(1), 6, 5, BOOKSHELF);
            b.fill(mx.applyAsInt(1), 5, 13, mx.applyAsInt(1), 6, 15, BOOKSHELF);
            b.set(mx.applyAsInt(3), 5, 8, "minecraft:lectern[facing=" + (west ? "west" : "east") + ",has_book=false,powered=false]");
            b.set(mx.applyAsInt(5), 5, 1, chest(Facing.SOUTH)).set(mx.applyAsInt(6), 5, 1, BARREL);
            b.fill(mx.applyAsInt(3), 5, 15, mx.applyAsInt(6), 5, 15, BARREL);
            b.set(mx.applyAsInt(4), 7, 5, LANTERN_HANGING).set(mx.applyAsInt(4), 7, 11, LANTERN_HANGING);
        }
        // ---- 真ん中の棟(x 7..17、3 階建て、屋根 y = 12)----
        b.ring(7, 1, 0, 17, 11, 16, LGC);
        b.ring(7, 4, 0, 17, 4, 16, RC);
        b.ring(7, 8, 0, 17, 8, 16, RC);
        b.ring(7, 11, 0, 17, 11, 16, TEAL);
        for (int[] c : new int[][]{{7, 0}, {17, 0}, {7, 16}, {17, 16}, {12, 0}}) {
            b.fill(c[0], 1, c[1], c[0], 12, c[1], RC);
        }
        b.fill(12, 4, 16, 12, 12, 16, RC); // 正面の真ん中の柱は扉の上から
        b.fill(8, 4, 1, 16, 4, 15, SMOOTH);
        b.fill(8, 8, 1, 16, 8, 15, SMOOTH);
        b.fill(7, 12, 0, 17, 12, 16, GC);
        b.fill(11, 12, 6, 13, 12, 10, "minecraft:glass"); // 天窓
        b.ring(7, 13, 0, 17, 13, 16, RC_WALL);
        // 正面: ガラスの壁と 3 枚の扉・欄間
        for (int y : new int[]{1, 2, 3, 5, 6, 7, 9, 10}) {
            for (int x = 8; x <= 16; x++) {
                if (x != 12 || y < 4) {
                    b.set(x, y, 16, GLASS);
                }
            }
        }
        for (int x = 11; x <= 13; x++) {
            b.set(x, 1, 16, door(LAB_DOOR, Facing.NORTH, false, x == 13));
            b.set(x, 2, 16, door(LAB_DOOR, Facing.NORTH, true, x == 13));
        }
        // 奥と、屋根より上に出た横の壁の窓
        for (int y : new int[]{2, 3, 6, 7, 9, 10}) {
            for (int x : new int[]{8, 9, 10, 11, 13, 14, 15, 16}) {
                b.set(x, y, 0, GLASS);
            }
        }
        for (int y : new int[]{9, 10}) {
            for (int z : new int[]{2, 3, 4, 5, 6, 10, 11, 12, 13, 14}) {
                b.set(7, y, z, GLASS).set(17, y, z, GLASS);
            }
        }
        // 東西の研究棟への出入口(扉の無い 3 × 3、1 階と 2 階)
        for (int x : new int[]{7, 17}) {
            b.fill(x, 1, 8, x, 3, 10, AIR);
            b.fill(x, 5, 8, x, 7, 10, AIR);
        }
        // 階段(管理棟と同じ折り返し。北西の隅、x 8..9)
        switchbackStairs(b, 8, 1, 3, "minecraft:stone_brick_stairs", IRON_BARS);
        // 1 階: 標本の陳列(ガラスの囲いの中に苔の床とツツジ、天井から胞子の花)と受付
        b.ring(10, 1, 4, 14, 3, 7, GLASS);
        b.fill(11, 1, 5, 13, 1, 6, "minecraft:moss_block");
        b.set(12, 2, 5, "minecraft:flowering_azalea").set(11, 2, 6, "minecraft:azalea").set(13, 2, 6, "minecraft:fern");
        b.set(12, 3, 6, "minecraft:spore_blossom");
        b.fill(14, 1, 13, 16, 1, 13, slab("minecraft:spruce_slab", "top"));
        b.set(15, 1, 12, stairs("minecraft:spruce_stairs", Facing.SOUTH, false));
        for (int[] l : new int[][]{{11, 2}, {15, 3}, {11, 13}, {15, 11}}) {
            b.set(l[0], 3, l[1], LANTERN_HANGING);
        }
        // 2 階: 研究室
        b.set(11, 5, 1, facing(RESEARCH_TABLE, Facing.SOUTH)).set(13, 5, 1, facing(RESEARCH_TABLE, Facing.SOUTH))
                .set(15, 5, 1, facing(RESEARCH_TABLE, Facing.SOUTH));
        b.fill(12, 5, 4, 12, 5, 6, slab(MED_SLAB, "top"));
        b.fill(12, 5, 12, 12, 5, 14, slab(MED_SLAB, "top"));
        b.set(12, 6, 5, BREWING_STAND).set(12, 6, 13, BREWING_STAND);
        b.set(16, 5, 4, WATER_CAULDRON_FULL).set(16, 5, 5, WATER_CAULDRON_FULL);
        b.fill(16, 5, 12, 16, 5, 14, BARREL);
        b.set(16, 6, 12, LANTERN);
        b.set(14, 5, 15, "minecraft:lectern[facing=south,has_book=false,powered=false]").set(10, 5, 15, CARTOGRAPHY);
        for (int[] l : new int[][]{{11, 4}, {15, 4}, {11, 12}, {15, 12}}) {
            b.set(l[0], 7, l[1], LANTERN_HANGING);
        }
        // 3 階: 所長室と資料室
        b.fill(11, 9, 1, 16, 10, 1, BOOKSHELF);
        b.fill(13, 9, 5, 14, 9, 5, slab("minecraft:spruce_slab", "top"));
        b.set(13, 9, 6, stairs("minecraft:spruce_stairs", Facing.NORTH, false));
        b.set(11, 9, 13, CARTOGRAPHY).set(12, 9, 13, CARTOGRAPHY);
        b.set(16, 9, 15, "minecraft:lectern[facing=west,has_book=false,powered=false]");
        b.fill(16, 9, 11, 16, 10, 13, BOOKSHELF);
        for (int[] l : new int[][]{{11, 4}, {15, 4}, {11, 14}, {15, 14}}) {
            b.set(l[0], 11, l[1], LANTERN_HANGING);
        }
        // 屋上: パラボラアンテナ・アンテナの柱・空調機
        b.fill(9, 13, 13, 9, 14, 13, RC_WALL);
        b.fill(8, 15, 12, 10, 15, 14, slab(MED_SLAB, "bottom"));
        b.set(9, 16, 13, LIGHTNING_ROD);
        b.fill(15, 13, 3, 15, 19, 3, IRON_BARS);
        b.set(15, 20, 3, LIGHTNING_ROD);
        b.fill(14, 13, 9, 15, 13, 10, LGC);
        b.set(14, 14, 9, trapdoor("minecraft:iron_trapdoor", Facing.NORTH, false, false)).set(15, 14, 9, trapdoor("minecraft:iron_trapdoor", Facing.NORTH, false, false));
        b.fill(2, 9, 6, 3, 9, 7, LGC).fill(21, 9, 6, 22, 9, 7, LGC); // 研究棟の屋上の空調機
        // ---- 正面の庇と灯り ----
        b.fill(10, 4, 17, 14, 4, 18, slab(RC_SLAB, "top"));
        b.fill(10, 1, 18, 10, 3, 18, RC_WALL);
        b.fill(14, 1, 18, 14, 3, 18, RC_WALL);
        b.set(12, 5, 18, floodlight(Facing.SOUTH));
        return b;
    }

    /**
     * 第 2 研究棟(27 × 19、研究の出城が広がった浄化圏の真ん中。2026-10-02 夜その 3、ユーザー指定「出城が拡張される際、研究施設も一緒に拡張される。
     * 縦に伸ばすのではなく、拡張された場所も使った巨大な施設群」): 本館と同じ造り(薄灰の壁・鉄筋の柱・青緑の帯)の 2 階建て。
     * 1 階は長い実験室(奥の壁ぎわの実験台・研究台、島の実験台、流し・箱)、2 階は研究員の事務室(机・本棚・書見台)、屋上に太陽光パネルと空調機・アンテナ。
     * 正面に 3 枚の扉と庇、左右の壁の真ん中(本館と同じ奥行)に連絡通路の出入口の 3 枚の扉。真ん中を左右に抜ける廊下は空けておく。
     */
    static Blueprint.Builder annexLab() {
        int w = 27;
        int d = 19;
        Blueprint.Builder b = new Blueprint.Builder(w, 16, d);
        b.fill(0, 1, 0, w - 1, 15, d - 1, AIR);
        b.fill(0, 0, 0, w - 1, 0, 16, RC);
        b.fill(1, 0, 1, w - 2, 0, 15, SMOOTH);
        b.fill(11, 0, 17, 15, 0, 18, slab("minecraft:smooth_stone_slab", "top"));
        b.ring(0, 1, 0, w - 1, 7, 16, LGC);
        b.ring(0, 4, 0, w - 1, 4, 16, TEAL);
        for (int x : new int[]{0, 6, 13, 20, w - 1}) {
            b.fill(x, 1, 0, x, 8, 0, RC);
            if (x != 13) {
                b.fill(x, 1, 16, x, 8, 16, RC);
            }
        }
        for (int z : new int[]{6, 12}) {
            b.fill(0, 1, z, 0, 8, z, RC).fill(w - 1, 1, z, w - 1, 8, z, RC);
        }
        b.fill(1, 4, 1, w - 2, 4, 15, SMOOTH);
        b.fill(0, 8, 0, w - 1, 8, 16, GC);
        b.ring(0, 9, 0, w - 1, 9, 16, RC_WALL);
        for (int y : new int[]{2, 3, 6, 7}) {
            for (int x = 1; x < w - 1; x++) {
                if (x != 6 && x != 13 && x != 20) {
                    b.set(x, y, 0, GLASS);
                    if (!(y <= 3 && x >= 12 && x <= 14)) {
                        b.set(x, y, 16, GLASS);
                    }
                }
            }
            for (int z : new int[]{2, 3, 4, 13, 14, 15}) {
                b.set(0, y, z, GLASS).set(w - 1, y, z, GLASS);
            }
        }
        // 正面の 3 枚の扉と庇、左右の連絡通路の出入口
        for (int x = 12; x <= 14; x++) {
            b.set(x, 1, 16, door(LAB_DOOR, Facing.NORTH, false, x == 14)).set(x, 2, 16, door(LAB_DOOR, Facing.NORTH, true, x == 14));
            b.set(x, 3, 16, GLASS);
        }
        b.fill(11, 4, 17, 15, 4, 18, slab(RC_SLAB, "top"));
        b.fill(11, 1, 18, 11, 3, 18, RC_WALL).fill(15, 1, 18, 15, 3, 18, RC_WALL);
        sideDoors(b, w, 8);
        for (int z = 8; z <= 10; z++) {
            b.set(0, 6, z, GLASS).set(0, 7, z, GLASS).set(w - 1, 6, z, GLASS).set(w - 1, 7, z, GLASS);
        }
        // 階段(北西の隅、x 1)
        switchbackStairs(b, 1, 1, 2, "minecraft:stone_brick_stairs", IRON_BARS);
        // 1 階: 長い実験室(真ん中の廊下 z 7..11 は空ける)
        b.fill(4, 1, 1, 11, 1, 1, slab(MED_SLAB, "top"));
        b.fill(15, 1, 1, 23, 1, 1, slab(MED_SLAB, "top"));
        b.set(12, 1, 1, facing(RESEARCH_TABLE, Facing.SOUTH)).set(14, 1, 1, facing(RESEARCH_TABLE, Facing.SOUTH));
        for (int x : new int[]{5, 9, 17, 21}) {
            b.set(x, 2, 1, BREWING_STAND);
        }
        b.fill(6, 1, 4, 20, 1, 4, slab(MED_SLAB, "top"));
        b.set(8, 2, 4, BREWING_STAND).set(13, 2, 4, LANTERN).set(18, 2, 4, BREWING_STAND);
        b.fill(6, 1, 13, 20, 1, 13, slab(MED_SLAB, "top"));
        b.set(10, 2, 13, BREWING_STAND).set(16, 2, 13, BREWING_STAND);
        b.set(23, 1, 13, WATER_CAULDRON_FULL).set(24, 1, 13, WATER_CAULDRON_FULL);
        b.fill(2, 1, 15, 5, 1, 15, BARREL).set(21, 1, 15, chest(Facing.NORTH)).fill(22, 1, 15, 24, 1, 15, BARREL);
        b.set(2, 2, 15, LANTERN);
        for (int x : new int[]{6, 13, 20}) {
            b.set(x, 3, 9, LANTERN_HANGING);
        }
        // 2 階: 研究員の事務室
        b.fill(4, 5, 1, 23, 6, 1, BOOKSHELF);
        for (int x : new int[]{6, 11, 16, 21}) {
            b.fill(x, 5, 4, x + 1, 5, 4, slab("minecraft:spruce_slab", "top"));
            b.set(x, 5, 5, stairs("minecraft:spruce_stairs", Facing.NORTH, false));
            b.fill(x, 5, 13, x + 1, 5, 13, slab("minecraft:spruce_slab", "top"));
            b.set(x, 5, 12, stairs("minecraft:spruce_stairs", Facing.SOUTH, false));
        }
        b.set(24, 5, 9, "minecraft:lectern[facing=west,has_book=false,powered=false]").set(13, 5, 15, CARTOGRAPHY);
        for (int x : new int[]{6, 13, 20}) {
            b.set(x, 7, 9, LANTERN_HANGING);
        }
        // 屋上: 太陽光パネル(日照センサー)と空調機・アンテナ
        for (int z : new int[]{3, 4, 6, 7, 10, 11, 13, 14}) {
            b.fill(3, 9, z, 10, 9, z, "minecraft:daylight_detector[inverted=false,power=0]");
            b.fill(16, 9, z, 23, 9, z, "minecraft:daylight_detector[inverted=false,power=0]");
        }
        b.fill(12, 9, 6, 14, 9, 7, LGC);
        b.set(12, 10, 6, trapdoor("minecraft:iron_trapdoor", Facing.NORTH, false, false)).set(14, 10, 6, trapdoor("minecraft:iron_trapdoor", Facing.NORTH, false, false));
        b.fill(13, 9, 12, 13, 13, 12, IRON_BARS);
        b.set(13, 14, 12, LIGHTNING_ROD);
        return b;
    }

    /**
     * 大型試験棟(27 × 19、研究の出城が広がった浄化圏の真ん中。2026-10-02 夜その 3): 天井の高い試験場(研究基地は戦闘ロボットを研究する設定)。
     * 正面に大扉(幅 9・高さ 7、黄と黒の縁)、天井に黄色の天井クレーン、真ん中に試作の戦闘ロボット(コンクリートの骨組み・水色の操縦席)と床の黄色い枠、
     * 奥の壁ぎわに作業台(鍛冶台・金床・作業台・箱)、隅にガラスの管制室(研究台・書見台)。左右の壁の真ん中に連絡通路の出入口の 3 枚の扉。
     */
    static Blueprint.Builder testHall() {
        int w = 27;
        int d = 19;
        String yel = "minecraft:yellow_concrete";
        String blk = "minecraft:black_concrete";
        Blueprint.Builder b = new Blueprint.Builder(w, 16, d);
        b.fill(0, 1, 0, w - 1, 15, d - 1, AIR);
        b.fill(0, 0, 0, w - 1, 0, 16, RC);
        b.fill(1, 0, 1, w - 2, 0, 15, SMOOTH);
        b.fill(8, 0, 17, 18, 0, 18, slab("minecraft:smooth_stone_slab", "top"));
        b.ring(0, 1, 0, w - 1, 11, 16, LGC);
        b.ring(0, 1, 0, w - 1, 2, 16, RC);
        b.ring(0, 11, 0, w - 1, 11, 16, TEAL);
        for (int x : new int[]{0, 6, 20, w - 1}) {
            b.fill(x, 1, 0, x, 12, 0, RC).fill(x, 1, 16, x, 12, 16, RC);
        }
        b.fill(13, 1, 0, 13, 12, 0, RC);
        for (int z : new int[]{6, 12}) {
            b.fill(0, 1, z, 0, 12, z, RC).fill(w - 1, 1, z, w - 1, 12, z, RC);
        }
        b.fill(0, 12, 0, w - 1, 12, 16, GC);
        b.ring(0, 13, 0, w - 1, 13, 16, RC_WALL);
        for (int x = 4; x <= 22; x++) { // 天窓の帯
            b.set(x, 12, 5, "minecraft:glass").set(x, 12, 11, "minecraft:glass");
        }
        for (int y : new int[]{9, 10}) { // 高窓
            for (int x = 1; x < w - 1; x++) {
                if (x != 6 && x != 13 && x != 20) {
                    b.set(x, y, 0, GLASS).set(x, y, 16, GLASS);
                }
            }
            for (int z = 1; z < 16; z++) {
                if (z != 6 && z != 12) {
                    b.set(0, y, z, GLASS).set(w - 1, y, z, GLASS);
                }
            }
        }
        // 正面の大扉(幅 9・高さ 7)と黄と黒の縁
        b.fill(9, 1, 16, 17, 7, 16, AIR);
        for (int y = 1; y <= 8; y++) {
            b.set(8, y, 16, (y & 1) == 0 ? yel : blk).set(18, y, 16, (y & 1) == 0 ? yel : blk);
        }
        for (int x = 8; x <= 18; x++) {
            b.set(x, 8, 16, (x & 1) == 0 ? yel : blk);
        }
        sideDoors(b, w, 8);
        // 天井クレーン: 左右の壁ぎわのレール(y 10)と、奥寄りに渡した桁・吊り下げのフック
        b.fill(1, 10, 1, 1, 10, 15, yel).fill(w - 2, 10, 1, w - 2, 10, 15, yel);
        b.fill(1, 10, 4, w - 2, 10, 4, yel);
        b.fill(13, 7, 4, 13, 9, 4, CHAIN_Y);
        b.set(13, 6, 4, GC);
        // 試作の戦闘ロボット(真ん中、正面 = 大扉の側)と床の黄色い枠
        String metal = LGC;
        String dark = GC;
        for (int x = 8; x <= 18; x++) {
            b.set(x, 0, 6, yel).set(x, 0, 14, yel);
        }
        for (int z = 6; z <= 14; z++) {
            b.set(8, 0, z, yel).set(18, 0, z, yel);
        }
        b.fill(11, 1, 10, 11, 4, 10, dark).fill(15, 1, 10, 15, 4, 10, dark); // 脚
        b.set(11, 1, 11, dark).set(15, 1, 11, dark); // つま先
        b.fill(11, 5, 10, 15, 5, 10, dark); // 腰
        b.fill(11, 6, 9, 15, 9, 11, metal); // 胴
        b.fill(12, 8, 11, 14, 8, 11, "minecraft:light_blue_stained_glass"); // 操縦席
        b.set(10, 9, 10, dark).set(16, 9, 10, dark); // 肩
        b.fill(10, 5, 10, 10, 8, 10, metal).fill(16, 5, 10, 16, 8, 10, metal); // 腕
        b.set(10, 4, 10, dark).set(16, 4, 10, dark); // 手
        b.fill(12, 10, 9, 14, 10, 11, metal); // 頭
        b.set(12, 10, 11, "minecraft:red_concrete").set(14, 10, 11, "minecraft:red_concrete"); // 目
        b.set(13, 11, 10, LIGHTNING_ROD); // アンテナ
        // 奥の壁ぎわの作業台と、隅のガラスの管制室
        b.set(3, 1, 1, SMITHING).set(5, 1, 1, "minecraft:anvil[facing=north]").set(7, 1, 1, CRAFTING)
                .set(9, 1, 1, "minecraft:grindstone[face=floor,facing=north]");
        b.set(17, 1, 1, chest(Facing.SOUTH)).fill(18, 1, 1, 19, 1, 1, BARREL).fill(21, 1, 1, 24, 1, 1, BARREL);
        b.ring(20, 1, 12, 24, 3, 15, GLASS);
        b.fill(20, 1, 12, 20, 2, 12, AIR); // 管制室の入口
        b.set(22, 1, 14, facing(RESEARCH_TABLE, Facing.NORTH)).set(23, 1, 14, "minecraft:lectern[facing=north,has_book=false,powered=false]");
        b.fill(20, 4, 12, 24, 4, 15, slab(RC_SLAB, "bottom"));
        for (int x : new int[]{4, 10, 16, 22}) {
            for (int z : new int[]{3, 9, 14}) {
                b.set(x, 12, z, SEA_LANTERN);
            }
        }
        // 屋上: 空調機とアンテナ
        b.fill(4, 13, 8, 6, 13, 9, LGC).fill(20, 13, 8, 22, 13, 9, LGC);
        b.fill(13, 13, 14, 13, 14, 14, RC_WALL);
        b.fill(12, 15, 13, 14, 15, 15, slab(MED_SLAB, "bottom"));
        return b;
    }

    /** 研究所の棟の左右の壁の真ん中(z {@code zc}..{@code zc + 2})に 3 枚の扉と欄間(連絡通路の出入口)。 */
    static void sideDoors(Blueprint.Builder b, int w, int zc) {
        for (int z = zc; z <= zc + 2; z++) {
            b.set(0, 1, z, door(LAB_DOOR, Facing.EAST, false, z == zc + 2)).set(0, 2, z, door(LAB_DOOR, Facing.EAST, true, z == zc + 2));
            b.set(w - 1, 1, z, door(LAB_DOOR, Facing.WEST, false, z == zc + 2)).set(w - 1, 2, z, door(LAB_DOOR, Facing.WEST, true, z == zc + 2));
            b.set(0, 3, z, GLASS).set(w - 1, 3, z, GLASS);
        }
    }

    /**
     * 連絡通路(幅 5 × 長さ {@code d}、正面 = 終わりの端): 床・ガラスの壁と鉄筋の柱・屋根。両端は開いていて、棟の左右の出入口につながる。
     * 床の高さは始めの端 {@code offStart} から終わりの端 {@code offEnd} へ 1 マスに 1 段まで(上がる所は階段)。床の下は鉄筋コンクリートで埋める。
     * 屋根は床に沿って上下するので、どこでも床の上に 3 マス空く(段を上がる間の頭の空きも足りる)。{@link BaseEmitter} が両端の棟の土台から作る。
     */
    static Blueprint.Builder walkwayBuilder(int d, int offStart, int offEnd) {
        int w = 5;
        int top = Math.max(0, Math.max(offStart, offEnd));
        Blueprint.Builder b = new Blueprint.Builder(w, top + 5, d);
        int[] h = new int[d];
        for (int z = 0; z < d; z++) {
            h[z] = Math.max(0, d == 1 ? offStart : offStart + (int) Math.round((offEnd - offStart) * (double) z / (d - 1)));
        }
        for (int z = 0; z < d; z++) {
            int y = h[z];
            if (y > 0) {
                b.fill(0, 0, z, w - 1, y - 1, z, RC);
            }
            b.fill(0, y, z, w - 1, y, z, RC);
            b.fill(1, y, z, w - 2, y, z, SMOOTH);
            b.fill(1, y + 1, z, w - 2, y + 3, z, AIR);
            boolean post = z % 4 == 0 || z == d - 1;
            b.fill(0, y + 1, z, 0, y + 3, z, post ? RC : GLASS);
            b.fill(w - 1, y + 1, z, w - 1, y + 3, z, post ? RC : GLASS);
            b.fill(0, y + 4, z, w - 1, y + 4, z, GC);
        }
        for (int z = 1; z < d; z++) {
            if (h[z] == h[z - 1] + 1) {
                b.fill(1, h[z], z, w - 2, h[z], z, stairs("minecraft:stone_brick_stairs", Facing.SOUTH, false));
            } else if (h[z] == h[z - 1] - 1) {
                b.fill(1, h[z - 1], z - 1, w - 2, h[z - 1], z - 1, stairs("minecraft:stone_brick_stairs", Facing.NORTH, false));
            }
        }
        for (int z = 2; z < d - 2; z += 6) {
            b.set(2, h[z] + 3, z, LANTERN_HANGING);
        }
        return b;
    }

    /** 連絡通路の設計図(床の高さを両端の棟に合わせたもの。区画の向きへ回す)。 */
    public static Blueprint walkway(Plot p, int offStart, int offEnd) {
        Blueprint.Builder b = walkwayBuilder(p.depth(), offStart, offEnd);
        Connections.resolveAll(b);
        return b.build().rotated(p.facing().turnsFromSouth());
    }

    /**
     * 観測施設(11 × 11、観測基地の出城): 奥に観測塔(5 × 5 の脚と真ん中の柱、柱の南の面にはしご、鉄格子の帯 2 段)、天辺に 7 × 7 の観測室
     * (四方がガラス、海図台・書見台・樽)と、その屋根のレドーム(白い球の覆い)、横にアンテナ。手前に計器の小屋(胞子警報器・フェーズ計測機・海図台、
     * 入口は広場の側)と発電機。観測室ははしごだけ(設備は置かない —— 兵士ははしごを登らないので、置くと兵士の届かない設備になる)。
     */
    static Blueprint.Builder observationPost() {
        int w = 11;
        int d = 11;
        Blueprint.Builder b = new Blueprint.Builder(w, 23, d);
        b.fill(0, 1, 0, w - 1, 22, d - 1, AIR);
        // ---- 観測塔(脚 x 3..7、z 1..5)----
        b.fill(3, 0, 1, 7, 0, 5, RC);
        for (int[] c : new int[][]{{3, 1}, {7, 1}, {3, 5}, {7, 5}}) {
            b.fill(c[0], 1, c[1], c[0], 13, c[1], RC_WALL);
        }
        b.fill(5, 1, 3, 5, 14, 3, RC);
        b.fill(5, 1, 4, 5, 14, 4, facing("minecraft:ladder", Facing.SOUTH));
        for (int y : new int[]{5, 10}) {
            b.fill(4, y, 1, 6, y, 1, IRON_BARS);
            b.fill(4, y, 5, 6, y, 5, IRON_BARS);
            b.fill(3, y, 2, 3, y, 4, IRON_BARS);
            b.fill(7, y, 2, 7, y, 4, IRON_BARS);
        }
        // 観測室(x 2..8、z 0..6、床 y = 14、窓 y 15..17、屋根 y = 18)
        b.fill(2, 14, 0, 8, 14, 6, RC);
        b.set(5, 14, 4, facing("minecraft:ladder", Facing.SOUTH));
        b.ring(2, 15, 0, 8, 17, 6, GLASS);
        for (int[] c : new int[][]{{2, 0}, {8, 0}, {2, 6}, {8, 6}}) {
            b.fill(c[0], 15, c[1], c[0], 17, c[1], RC);
        }
        b.fill(2, 18, 0, 8, 18, 6, RC);
        b.set(3, 15, 1, CARTOGRAPHY).set(7, 15, 1, "minecraft:lectern[facing=south,has_book=false,powered=false]");
        b.set(3, 15, 5, BARREL).set(3, 16, 5, LANTERN);
        // レドーム(白い球の覆い)と天辺の避雷針
        for (int y : new int[]{19, 20}) {
            b.fill(3, y, 2, 7, y, 4, WC);
            b.fill(4, y, 1, 6, y, 5, WC);
        }
        b.fill(4, 21, 2, 6, 21, 4, WC);
        b.set(5, 22, 3, LIGHTNING_ROD);
        // アンテナ(鉄格子の柱、天辺に避雷針)
        b.set(10, 0, 2, RC);
        b.fill(10, 1, 2, 10, 15, 2, IRON_BARS);
        b.set(10, 16, 2, LIGHTNING_ROD);
        // ---- 計器の小屋(x 0..6、z 7..10、入口は正面 = 広場の側)----
        b.fill(0, 0, 7, 6, 0, 10, LGC);
        b.ring(0, 1, 7, 6, 3, 10, OLIVE);
        b.fill(0, 4, 7, 6, 4, 10, slab(RC_SLAB, "bottom"));
        b.set(3, 1, 10, door(DOOR, Facing.NORTH, false, false));
        b.set(3, 2, 10, door(DOOR, Facing.NORTH, true, false));
        b.set(1, 2, 10, GLASS).set(5, 2, 10, GLASS).set(0, 2, 8, GLASS).set(6, 2, 9, GLASS);
        b.set(1, 1, 8, "overgrown_vegetation:spore_alarm");
        b.set(2, 1, 8, "overgrown_vegetation:phase_recorder[facing=south]");
        b.set(4, 1, 8, CARTOGRAPHY);
        b.set(5, 1, 8, BARREL).set(5, 2, 8, LANTERN);
        b.set(3, 5, 10, floodlight(Facing.SOUTH)); // 小屋の屋根の前の縁から広場を照らす
        // ---- 発電機(x 8..10、z 8..9)----
        b.fill(8, 1, 8, 9, 2, 9, OLIVE);
        b.set(10, 1, 8, IRON_BLOCK).set(10, 1, 9, BARREL).set(9, 3, 8, LIGHTNING_ROD);
        return b;
    }

    // ================= 小物 =================

    /** ヘリパッド(11 × 11): 灰色の舗装、白の H、黄色の縁、四隅と辺の真ん中に埋め込みの灯り。 */
    static Blueprint.Builder helipad() {
        Blueprint.Builder b = new Blueprint.Builder(11, 5, 11);
        b.fill(0, 1, 0, 10, 4, 10, AIR);
        b.fill(0, 0, 0, 10, 0, 10, GC);
        b.ring(0, 0, 0, 10, 0, 10, YC);
        b.fill(3, 0, 3, 3, 0, 7, WC);
        b.fill(7, 0, 3, 7, 0, 7, WC);
        b.fill(3, 0, 5, 7, 0, 5, WC);
        for (int[] p : new int[][]{{0, 0}, {10, 0}, {0, 10}, {10, 10}, {5, 0}, {5, 10}, {0, 5}, {10, 5}}) {
            b.set(p[0], 0, p[1], SEA_LANTERN);
        }
        return b;
    }

    /** 旗竿(3 × 3): 鉄筋の台座と柱、てっぺんに緑の旗(南向き)。 */
    static Blueprint.Builder flagpole() {
        Blueprint.Builder b = new Blueprint.Builder(3, 11, 3);
        b.fill(0, 1, 0, 2, 10, 2, AIR);
        b.fill(0, 0, 0, 2, 0, 2, RC);
        b.fill(1, 1, 1, 1, 9, 1, RC_WALL);
        b.set(1, 10, 1, LIGHTNING_ROD);
        b.set(1, 9, 2, wallBanner("green", Facing.SOUTH));
        return b;
    }

    /** 退避壕(5 × 3): 鉄筋の箱を土嚢で覆い、正面(長い辺)が開く。中に長椅子。 */
    static Blueprint.Builder shelter() {
        Blueprint.Builder b = new Blueprint.Builder(5, 5, 3);
        b.fill(0, 1, 0, 4, 4, 2, AIR);
        b.fill(0, 0, 0, 4, 0, 2, RC);
        b.fill(0, 1, 0, 4, 2, 0, RC);
        b.fill(0, 1, 0, 0, 2, 2, RC);
        b.fill(4, 1, 0, 4, 2, 2, RC);
        b.fill(0, 3, 0, 4, 3, 2, RC);
        b.fill(0, 4, 0, 4, 4, 2, SANDBAGS);
        b.fill(1, 1, 1, 3, 1, 1, slab("minecraft:spruce_slab", "bottom"));
        return b;
    }

    /** 発電機(5 × 3): オリーブ色の箱、放熱器の鉄、排気筒、燃料のドラム缶。 */
    static Blueprint.Builder generator() {
        Blueprint.Builder b = new Blueprint.Builder(5, 4, 3);
        b.fill(0, 1, 0, 4, 3, 2, AIR);
        b.fill(0, 0, 0, 4, 0, 2, GC);
        b.fill(0, 1, 0, 3, 2, 1, OLIVE);
        b.fill(0, 1, 0, 0, 2, 1, IRON_BLOCK);
        b.set(2, 3, 0, LIGHTNING_ROD);
        b.set(4, 1, 0, BARREL).set(4, 1, 1, BARREL);
        b.set(1, 1, 2, YC);
        return b;
    }

    /** 給水塔(5 × 5): 鉄筋の脚に白い水槽(角を落として丸く見せる)。 */
    static Blueprint.Builder waterTank() {
        Blueprint.Builder b = new Blueprint.Builder(5, 9, 5);
        b.fill(0, 1, 0, 4, 8, 4, AIR);
        for (int[] c : new int[][]{{0, 0}, {4, 0}, {0, 4}, {4, 4}}) {
            b.fill(c[0], 0, c[1], c[0], 3, c[1], RC_WALL);
        }
        b.fill(2, 1, 2, 2, 3, 2, CHAIN_Y);
        b.fill(0, 4, 0, 4, 6, 4, WC);
        for (int[] c : new int[][]{{0, 0}, {4, 0}, {0, 4}, {4, 4}}) {
            b.fill(c[0], 4, c[1], c[0], 6, c[1], AIR);
        }
        b.fill(1, 7, 1, 3, 7, 3, slab("minecraft:smooth_quartz_slab", "bottom"));
        b.set(2, 8, 2, LIGHTNING_ROD);
        return b;
    }

    /** 仮設トイレの列(5 × 2): 青い箱が 3 つ。 */
    static Blueprint.Builder latrine() {
        Blueprint.Builder b = new Blueprint.Builder(5, 4, 2);
        b.fill(0, 1, 0, 4, 3, 1, AIR);
        for (int x = 0; x <= 4; x += 2) {
            b.fill(x, 1, 0, x, 2, 0, "minecraft:blue_terracotta");
            b.set(x, 3, 0, slab("minecraft:smooth_quartz_slab", "bottom"));
            b.set(x, 1, 1, trapdoor("minecraft:spruce_trapdoor", Facing.SOUTH, false, true));
        }
        return b;
    }

    private Blueprints() {
    }
}
