package com.yayuy.overgrownvegetation.devbridge.impostor;

// 2026-11-18: parked/client/horde から写した(大群の軽量化 C の「絵と粗いモデルの重さ比べ」用。devbridge なので配布の jar に入らない)

import java.util.List;

/**
 * 1 種ぶんのスプライトの絵(2026-09-25、遠景の身代わり)。{@value #VIEWS} 方向(前から 45 度ずつ +Y 回り)のコマを横に並べた 1 枚と、
 * そのミップマップ {@value #MIP_LEVELS} 段。Minecraft の型を使わない({@link SpriteRasterizer} と同じく、起動せずに作って確かめられる)。
 *
 * <p>数値(仮決め 2026-09-25): 描かれる大きさ(体格 {@code SCALE} を掛けたあと)で 1 ブロック {@value #PIXELS_PER_WORLD_BLOCK} 画素
 * (バニラのテクスチャの細かさ。64 ブロック先の 1080p でほぼ等倍)、1 コマの幅は {@value #MAX_FRAME_PIXELS} 画素まで(大きな種は
 * 粗くする。大きな種はバニラが遠くまで本物を描くので、身代わりになるのはもっと遠く)、見下ろし {@value #PITCH_DEGREES} 度(遠くの群れは
 * 少し上から見ることが多い)、超標本化 {@value #SUPERSAMPLE} 倍、余白 {@value #PAD} 画素(ミップマップ 2 段でも隣のコマが混ざらない)。
 *
 * @param frameWidth     1 コマの幅(画素、余白込み、4 の倍数)
 * @param frameHeight    1 コマの高さ(画素、余白込み、4 の倍数)
 * @param originX        コマの中でモデルの足元が来る x(画素、左から)
 * @param originY        コマの中でモデルの足元が来る y(画素、上から)
 * @param pixelsPerBlock モデルの 1 ブロック(体格を掛ける前)あたりの画素
 * @param levels         ミップマップの各段(0 段目 = 原寸。ARGB、上の行から。幅 = frameWidth × VIEWS >> 段)
 */
public record SpriteSheet(int frameWidth, int frameHeight, float originX, float originY, float pixelsPerBlock, int[][] levels) {

    public static final int VIEWS = 8;
    public static final int MIP_LEVELS = 2;
    public static final float PIXELS_PER_WORLD_BLOCK = 16.0F;
    public static final int MAX_FRAME_PIXELS = 384;
    /** モデル 1 ブロックあたりの画素の下限(小さすぎる絵にしない)。 */
    public static final float MIN_PIXELS_PER_BLOCK = 4.0F;
    public static final double PITCH_DEGREES = 10.0D;
    public static final int SUPERSAMPLE = 4;
    public static final int PAD = 4;

    /** 1 枚目(原寸)の幅(画素)。 */
    public int width() {
        return this.frameWidth * VIEWS;
    }

    public int height() {
        return this.frameHeight;
    }

    /** コマの幅(モデルのブロック)。 */
    public float frameBlocksWide() {
        return this.frameWidth / this.pixelsPerBlock;
    }

    /** コマの高さ(モデルのブロック)。 */
    public float frameBlocksHigh() {
        return this.frameHeight / this.pixelsPerBlock;
    }

    /**
     * モデルの面とテクスチャから作る。面が 1 枚も無ければ null。
     *
     * @param baseScale その種のふだんの体格({@code getScale()})。描かれる大きさで 1 ブロック {@value #PIXELS_PER_WORLD_BLOCK} 画素にする
     */
    public static SpriteSheet build(List<SpriteRasterizer.Quad> quads, SpriteRasterizer.Texture texture, float baseScale) {
        if (quads.isEmpty()) {
            return null;
        }
        double pitch = Math.toRadians(PITCH_DEGREES);
        SpriteRasterizer.Bounds bounds = SpriteRasterizer.bounds(quads, VIEWS, pitch);
        float ppb = PIXELS_PER_WORLD_BLOCK * Math.max(0.05F, baseScale);
        float widest = Math.max(bounds.maxX() - bounds.minX(), bounds.maxY() - bounds.minY());
        if (widest * ppb + 2 * PAD > MAX_FRAME_PIXELS) {
            ppb = (MAX_FRAME_PIXELS - 2 * PAD) / widest;
        }
        ppb = Math.max(MIN_PIXELS_PER_BLOCK, ppb);
        int fw = bounds.pixelWidth(ppb, PAD);
        int fh = bounds.pixelHeight(ppb, PAD);
        int width = fw * VIEWS;
        int[] sheet = new int[width * fh];
        for (int view = 0; view < VIEWS; view++) {
            SpriteRasterizer.Frame frame = SpriteRasterizer.render(quads, texture, SpriteRasterizer.viewAngle(view, VIEWS), pitch,
                    bounds, ppb, PAD, SUPERSAMPLE);
            for (int y = 0; y < fh; y++) {
                System.arraycopy(frame.argb(), y * fw, sheet, y * width + view * fw, fw);
            }
        }
        int[][] levels = new int[MIP_LEVELS + 1][];
        levels[0] = sheet;
        int w = width;
        int h = fh;
        for (int level = 1; level <= MIP_LEVELS; level++) {
            levels[level] = SpriteRasterizer.halve(levels[level - 1], w, h);
            w /= 2;
            h /= 2;
        }
        return new SpriteSheet(fw, fh, bounds.originX(ppb, PAD), bounds.originY(ppb, PAD), ppb, levels);
    }

    /**
     * 見る向きのコマの番号。{@code relativeAngle} は「Mob の前向き」から「Mob からカメラへの向き」までの角度(ラジアン、+Y 回り、
     * 右手系 = 上から見て反時計回りが正)。
     */
    public static int viewIndex(double relativeAngle) {
        double step = Math.PI * 2.0D / VIEWS;
        long index = Math.round(relativeAngle / step);
        return (int) Math.floorMod(index, VIEWS);
    }

    /**
     * {@link #viewIndex} に渡す角度。{@code yawDegrees} は Mob の体の向き(Minecraft の yaw。前 = (−sin, 0, cos))、
     * {@code (toCameraX, toCameraZ)} は Mob からカメラへの水平の向き(長さは問わない)。
     */
    public static double relativeAngle(float yawDegrees, double toCameraX, double toCameraZ) {
        double yaw = Math.toRadians(yawDegrees);
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        // 前 F からカメラ C までの +Y 回りの角度: atan2((F × C).y, F · C)。
        return Math.atan2(fz * toCameraX - fx * toCameraZ, fx * toCameraX + fz * toCameraZ);
    }
}
