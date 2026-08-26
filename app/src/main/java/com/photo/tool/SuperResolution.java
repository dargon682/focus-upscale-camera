package com.photo.tool;

import android.graphics.Bitmap;

import java.util.List;

/**
 * 多帧超分辨率合成：
 * 1. 以中间帧为参考，估算每帧相对其中的整数平移（亚像素预对齐）；
 * 2. 每帧用 Catmull-Rom 双三次插值放大 scale 倍，并按平移偏移后累加到高分辨率网格；
 * 3. 多帧平均消除噪声、填补细节；
 * 4. Unsharp Mask 锐化，恢复对焦/上采样损失的高频细节。
 */
public final class SuperResolution {

    private SuperResolution() { }

    /** 配准搜索半径(full-res) */
    private static final int SEARCH_R = 8;

    /** 生成超分结果。所有 frame 应为相同宽高的 ARGB_8888 Bitmap。 */
    public static Bitmap process(List<Bitmap> frames, int scale, float sharpen) {
        if (frames == null || frames.size() < 2) return null;
        Bitmap ref = frames.get(frames.size() / 2);
        int sw = ref.getWidth();
        int sh = ref.getHeight();

        int dw = sw * scale;
        int dh = sh * scale;

        // 参考帧灰度用于配准
        int[] refPix = new int[sw * sh];
        ref.getPixels(refPix, 0, sw, 0, 0, sw, sh);
        byte[] refGray = toGray(refPix, sw, sh);

        // 累加缓冲
        float[] accR = new float[dw * dh];
        float[] accG = new float[dw * dh];
        float[] accB = new float[dw * dh];
        int[] count = new int[dw * dh];
        float[] tmp = new float[dw * dh];
        int[] curPix = new int[sw * sh];
        byte[] curGray = new byte[sw * sh];

        for (int f = 0; f < frames.size(); f++) {
            Bitmap bmp = frames.get(f);
            if (bmp.getWidth() != sw || bmp.getHeight() != sh) {
                bmp = Bitmap.createScaledBitmap(bmp, sw, sh, true);
            }
            bmp.getPixels(curPix, 0, sw, 0, 0, sw, sh);
            toGrayInto(curPix, sw, sh, curGray);
            float[] shift = findShift(refGray, curGray, sw, sh);
            float dx = shift[0];
            float dy = shift[1];

            scaleChannelAligned(curPix, sw, sh, 'R', dw, dh, scale, dx, dy, tmp);
            accumulate(tmp, accR, count);
            scaleChannelAligned(curPix, sw, sh, 'G', dw, dh, scale, dx, dy, tmp);
            accumulate(tmp, accG, count);
            scaleChannelAligned(curPix, sw, sh, 'B', dw, dh, scale, dx, dy, tmp);
            accumulate(tmp, accB, count);
        }

        int[] out = new int[dw * dh];
        for (int i = 0; i < dw * dh; i++) {
            int c = Math.max(1, count[i]);
            int r = (int) (accR[i] / c + 0.5f);
            int g = (int) (accG[i] / c + 0.5f);
            int b = (int) (accB[i] / c + 0.5f);
            out[i] = (0xFF << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
        }

        // 锐化（强度可调）
        out = unsharp(out, dw, dh, 2, sharpen);

        return Bitmap.createBitmap(out, dw, dh, Bitmap.Config.ARGB_8888);
    }

    private static void accumulate(float[] src, float[] acc, int[] count) {
        for (int i = 0; i < src.length; i++) {
            if (src[i] >= 0f) {
                acc[i] += src[i];
                count[i]++;
            }
        }
    }

    /** 将当前帧按缩放与偏移采样到 tmp[dw*dh]，越界像素标记为 -1（不参与累加）。 */
    private static void scaleChannelAligned(int[] src, int sw, int sh,
                                             char ch, int dw, int dh, int scale,
                                             float dx, float dy, float[] tmp) {
        for (int ty = 0; ty < dh; ty++) {
            float sy = ty / (float) scale + dy;
            int rowOff = ty * dw;
            for (int tx = 0; tx < dw; tx++) {
                float sx = tx / (float) scale + dx;
                float v;
                if (sx < 0 || sx > sw - 1 || sy < 0 || sy > sh - 1) {
                    v = -1f;
                } else {
                    v = bicubicSample(src, sw, sh, sx, sy, ch);
                }
                tmp[rowOff + tx] = v;
            }
        }
    }

    /** Catmull-Rom 双三次，采样浮点坐标上的单通道值。 */
    private static float bicubicSample(int[] pix, int w, int h, float x, float y, char ch) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        float fx = x - x0;
        float fy = y - y0;
        // 复用调用栈上的权重数组，避免每像素堆分配（4x4 双三次合成是主要耗时点）
        float sum = 0f;
        float t2 = fx * fx, t3 = t2 * fx;
        float w0 = -0.5f * t3 + t2 - 0.5f * fx;
        float w1 = 1.5f * t3 - 2.5f * t2 + 1f;
        float w2 = -1.5f * t3 + 2f * t2 + 0.5f * fx;
        float w3 = 0.5f * t3 - 0.5f * t2;
        t2 = fy * fy; t3 = t2 * fy;
        float v0 = -0.5f * t3 + t2 - 0.5f * fy;
        float v1 = 1.5f * t3 - 2.5f * t2 + 1f;
        float v2 = -1.5f * t3 + 2f * t2 + 0.5f * fy;
        float v3 = 0.5f * t3 - 0.5f * t2;
        for (int j = 0; j < 4; j++) {
            int yy = Math.max(0, Math.min(h - 1, y0 - 1 + j));
            float vy = j == 0 ? v0 : (j == 1 ? v1 : (j == 2 ? v2 : v3));
            float rowSum = 0f;
            for (int i = 0; i < 4; i++) {
                int xx = Math.max(0, Math.min(w - 1, x0 - 1 + i));
                float wx = i == 0 ? w0 : (i == 1 ? w1 : (i == 2 ? w2 : w3));
                rowSum += wx * channel(pix[yy * w + xx], ch);
            }
            sum += vy * rowSum;
        }
        return sum;
    }

    private static int channel(int argb, char ch) {
        switch (ch) {
            case 'R': return (argb >> 16) & 0xFF;
            case 'G': return (argb >> 8) & 0xFF;
            default:  return argb & 0xFF;
        }
    }

    // ---------- 配准 ----------

    private static byte[] toGray(int[] pix, int w, int h) {
        byte[] g = new byte[w * h];
        toGrayInto(pix, w, h, g);
        return g;
    }

    private static void toGrayInto(int[] pix, int w, int h, byte[] out) {
        for (int i = 0; i < w * h; i++) {
            int p = pix[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            out[i] = (byte) ((r * 299 + g * 587 + b * 114) / 1000);
        }
    }

    /**
     * 返回相对参考的亚像素平移 (dx, dy)：cur 采样点在 ref 坐标系中的偏移。
     * 先缩略粗搜整数，再整幅细搜整数，最后用三段 SSD 抛物线拟合求亚像素偏移，
     * 从而让不同帧落在高分辨率网格的不同亚像素位置，形成真正的超分辨率交叠采样。
     * 返回值 float[3] = {dx, dy, 最小SSD代价}，代价用于帧级置信度过滤。
     */
    private static float[] findShift(byte[] refGray, byte[] curGray, int w, int h) {
        // 下采样 8x：粗搜每 mv 对应 ±8 像素，整体搜索范围扩大至 ±64px（覆盖手持抖动），
        // 同时粗搜耗时比 scaleDown=4 更低。
        int scaleDown = 8;
        int cw = w / scaleDown;
        int ch = h / scaleDown;
        byte[] rS = shrink(refGray, w, h, cw, ch, scaleDown);
        byte[] cS = shrink(curGray, w, h, cw, ch, scaleDown);

        int bestCx = 0, bestCy = 0;
        long bestCost = Long.MAX_VALUE;
        for (int dy = -SEARCH_R; dy <= SEARCH_R; dy++) {
            for (int dx = -SEARCH_R; dx <= SEARCH_R; dx++) {
                long c = ssd(rS, cS, cw, ch, dx * scaleDown, dy * scaleDown);
                if (c < bestCost) {
                    bestCost = c;
                    bestCx = dx;
                    bestCy = dy;
                }
            }
        }
        bestCx *= scaleDown;
        bestCy *= scaleDown;

        // 细搜 ±3
        int bestX = bestCx, bestY = bestCy;
        long bestCost2 = Long.MAX_VALUE;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                long c = ssd(refGray, curGray, w, h, bestCx + dx, bestY + dy);
                if (c < bestCost2) {
                    bestCost2 = c;
                    bestX = bestCx + dx;
                    bestY = bestCy + dy;
                }
            }
        }

        // 亚像素抛物线拟合（SSD 在极小点附近近似二次，Δ=0.5*(c_prev-c_next)/(c_prev-2c+c_next)）
        float subX = parabolaMin(ssd(refGray, curGray, w, h, bestX - 1, bestY),
                ssd(refGray, curGray, w, h, bestX, bestY),
                ssd(refGray, curGray, w, h, bestX + 1, bestY));
        float subY = parabolaMin(ssd(refGray, curGray, w, h, bestX, bestY - 1),
                ssd(refGray, curGray, w, h, bestX, bestY),
                ssd(refGray, curGray, w, h, bestX, bestY + 1));

        // 附带返回细搜后的最小 SSD 代价，供调用方做帧级置信度过滤
        return new float[]{bestX + subX, bestY + subY, (float) bestCost2};
    }

    /** 用三点 (f(-1), f(0), f(1)) 拟合二次曲线极小点的亚像素偏移，范围限制在 [-1, 1]。 */
    private static float parabolaMin(long fm, long f0, long fp) {
        long denom = fm - 2 * f0 + fp;
        if (denom == 0) return 0f;
        double d = 0.5 * (double) (fm - fp) / denom;
        if (d > 1.0) d = 1.0;
        if (d < -1.0) d = -1.0;
        return (float) d;
    }

    /** 平移 (dx,dy) 后的 SSD 代价。 */
    private static long ssd(byte[] a, byte[] b, int w, int h, int dx, int dy) {
        long cost = 0;
        for (int y = 0; y < h; y++) {
            int by = y + dy;
            if (by < 0 || by >= h) continue;
            int rowA = y * w;
            int rowB = by * w;
            for (int x = 0; x < w; x++) {
                int bx = x + dx;
                if (bx < 0 || bx >= w) continue;
                int d = (a[rowA + x] & 0xFF) - (b[rowB + bx] & 0xFF);
                cost += d * d;
            }
        }
        return cost;
    }

    /** 平均下采样为 grayscale。 */
    private static byte[] shrink(byte[] src, int w, int h, int cw, int ch, int k) {
        byte[] out = new byte[cw * ch];
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                long sum = 0;
                for (int j = 0; j < k; j++) {
                    int sy = y * k + j;
                    if (sy >= h) break;
                    for (int i = 0; i < k; i++) {
                        int sx = x * k + i;
                        if (sx >= w) break;
                        sum += src[sy * w + sx] & 0xFF;
                    }
                }
                out[y * cw + x] = (byte) (sum / (k * k));
            }
        }
        return out;
    }

    // ---------- 锐化 ----------

    private static int[] unsharp(int[] img, int w, int h, int radius, float amount) {
        int[] blur = boxBlur(img, w, h, radius);
        int[] out = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            int p = img[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            int br = (blur[i] >> 16) & 0xFF;
            int bg = (blur[i] >> 8) & 0xFF;
            int bb = blur[i] & 0xFF;
            out[i] = (0xFF << 24)
                    | (clamp(Math.round(r + amount * (r - br))) << 16)
                    | (clamp(Math.round(g + amount * (g - bg))) << 8)
                    | clamp(Math.round(b + amount * (b - bb)));
        }
        return out;
    }

    private static int[] boxBlur(int[] src, int w, int h, int r) {
        int n = (2 * r + 1) * (2 * r + 1);
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                long rs = 0, gs = 0, bs = 0;
                for (int j = -r; j <= r; j++) {
                    int yy = Math.max(0, Math.min(h - 1, y + j));
                    for (int i = -r; i <= r; i++) {
                        int xx = Math.max(0, Math.min(w - 1, x + i));
                        int p = src[yy * w + xx];
                        rs += (p >> 16) & 0xFF;
                        gs += (p >> 8) & 0xFF;
                        bs += p & 0xFF;
                    }
                }
                out[y * w + x] = (0xFF << 24)
                        | ((int) (rs / n) << 16)
                        | ((int) (gs / n) << 8)
                        | (int) (bs / n);
            }
        }
        return out;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // ---------- 流式处理（内存友好） ----------
    // 与 process() 等价，但调用方随取随用：每帧解码→累加→立刻 recycle，
    // 全程仅持有 1 帧 + 参考帧的工作缓冲，避免同时加载 N 帧导致的 OOM。
    // 参考帧取首帧；其后每帧做亚像素配准并对齐累加。

    /** 累加上下文。 */
    public static final class Stream {
        public final int w, h, scale, dw, dh;
        int[] refPix;
        byte[] refGray;
        final float[] accR, accG, accB;
        final int[] count;
        final float[] tmp;
        final int[] curPix;
        final byte[] curGray;
        float refMean = 0f;
        int frames = 0;

        Stream(int w, int h, int scale) {
            this.w = w;
            this.h = h;
            this.scale = scale;
            this.dw = w * scale;
            this.dh = h * scale;
            int n = dw * dh;
            accR = new float[n]; accG = new float[n]; accB = new float[n];
            count = new int[n];
            tmp = new float[n];
            curPix = new int[w * h];
            curGray = new byte[w * h];
        }
    }

    /** 创建流式累加上下文。 */
    public static Stream beginStream(int w, int h, int scale) {
        return new Stream(w, h, scale);
    }

    /** 添加一帧。首帧作为参考（shift=0），其后各帧做亚像素配准、亮度归一化并对齐。 */
    public static void addFrame(Stream s, Bitmap bmp) {
        int w = s.w, h = s.h;
        if (bmp.getWidth() != w || bmp.getHeight() != h) {
            Bitmap nb = Bitmap.createScaledBitmap(bmp, w, h, true);
            bmp.recycle();
            bmp = nb;
        }
        bmp.getPixels(s.curPix, 0, w, 0, 0, w, h);

        if (s.refGray == null) {
            // 首帧即参考帧：全量加入，不做剔除/增益
            s.refPix = s.curPix.clone();
            s.refGray = toGray(s.refPix, w, h);
            s.refMean = meanGray(s.refGray);
            scaleChannelAligned(s.curPix, w, h, 'R', s.dw, s.dh, s.scale, 0f, 0f, s.tmp);
            accumulate(s.tmp, s.accR, s.count);
            scaleChannelAligned(s.curPix, w, h, 'G', s.dw, s.dh, s.scale, 0f, 0f, s.tmp);
            accumulate(s.tmp, s.accG, s.count);
            scaleChannelAligned(s.curPix, w, h, 'B', s.dw, s.dh, s.scale, 0f, 0f, s.tmp);
            accumulate(s.tmp, s.accB, s.count);
        } else {
            toGrayInto(s.curPix, w, h, s.curGray);
            // 帧间亮度归一化：消除快速连拍导致的欠曝/闪烁，避免合成后整体偏暗
            float curMean = meanGray(s.curGray);
            float gain = (s.refMean > 1f && curMean > 1f)
                    ? clampGain(s.refMean / curMean) : 1f;
            // 用亮度归一化后的灰度做配准：既避免亮度差干扰几何估计（配准更准→少重影），
            // 又能用其 SSD 代价做帧级置信度过滤
            byte[] eqGray = s.curGray;
            if (Math.abs(gain - 1f) > 0.02f) {
                eqGray = new byte[s.curGray.length];
                for (int ii = 0; ii < eqGray.length; ii++) {
                    eqGray[ii] = (byte) clamp(Math.round((s.curGray[ii] & 0xFF) * gain));
                }
            }
            float[] sh = findShift(s.refGray, eqGray, w, h);
            float dx = sh[0], dy = sh[1];
            // 帧级置信度过滤：配准失败或主体大幅运动导致残差过高时，跳过该帧，避免错位污染平均
            if (sh[2] / (float) (w * h) > FRAME_COST_THRESH) {
                s.frames++;
                return;
            }
            scaleChannelAlignedMasked(s.curPix, s.curGray, s.refGray, gain,
                    w, h, 'R', s.dw, s.dh, s.scale, dx, dy, s.tmp);
            accumulate(s.tmp, s.accR, s.count);
            scaleChannelAlignedMasked(s.curPix, s.curGray, s.refGray, gain,
                    w, h, 'G', s.dw, s.dh, s.scale, dx, dy, s.tmp);
            accumulate(s.tmp, s.accG, s.count);
            scaleChannelAlignedMasked(s.curPix, s.curGray, s.refGray, gain,
                    w, h, 'B', s.dw, s.dh, s.scale, dx, dy, s.tmp);
            accumulate(s.tmp, s.accB, s.count);
        }
        s.frames++;
    }

    /** 运动一致性剔除阈值：与参考帧对应点灰度差超过该值时视为运动区域，不累加（消除重影）。
     *  取值放宽以免过度剔除削弱有效超分（只在局部运动明显处剔除）。 */
    private static final int GHOST_THRESH = 64;

    /**
     * 帧级置信度过滤：配准后每像素平均 SSD 代价超过该值时视为配准失败或整帧主体大幅运动，
     * 该帧整体不参与累加，避免错位帧污染平均造成全局重影。单位：灰度平方差/像素。
     */
    private static final float FRAME_COST_THRESH = 1200f;

    /** 亮度增益安全区间，避免某帧极黑/极亮时增益失稳。 */
    private static float clampGain(float g) {
        return g < 0.35f ? 0.35f : (g > 2.8f ? 2.8f : g);
    }

    /** 带运动剔除 + 亮度归一化的对齐采样；与 scaleChannelAligned 等价但会跳过运动像素。 */
    private static void scaleChannelAlignedMasked(int[] src, byte[] srcGray, byte[] refGray,
                                                  float gain, int sw, int sh,
                                                  char ch, int dw, int dh, int scale,
                                                  float dx, float dy, float[] tmp) {
        for (int ty = 0; ty < dh; ty++) {
            float sy = ty / (float) scale + dy;
            int rowOff = ty * dw;
            for (int tx = 0; tx < dw; tx++) {
                float sx = tx / (float) scale + dx;
                if (sx < 0 || sx > sw - 1 || sy < 0 || sy > sh - 1) {
                    tmp[rowOff + tx] = -1f;
                    continue;
                }
                // 运动剔除：比较当前帧与参考帧在同一点的灰度，差异过大则跳过该像素
                int cx = Math.max(0, Math.min(sw - 1, Math.round(sx)));
                int cy = Math.max(0, Math.min(sh - 1, Math.round(sy)));
                int rx = Math.max(0, Math.min(sw - 1, Math.round(sx - dx)));
                int ry = Math.max(0, Math.min(sh - 1, Math.round(sy - dy)));
                int diff = Math.abs((srcGray[cy * sw + cx] & 0xFF)
                        - (refGray[ry * sw + rx] & 0xFF));
                if (diff > GHOST_THRESH) {
                    tmp[rowOff + tx] = -1f;
                    continue;
                }
                tmp[rowOff + tx] = bicubicSample(src, sw, sh, sx, sy, ch) * gain;
            }
        }
    }

    private static float meanGray(byte[] g) {
        long sum = 0;
        for (byte b : g) sum += b & 0xFF;
        return sum / (float) g.length;
    }

    /** 归一化、锐化并输出最终 Bitmap。调用后 Stream 即可丢弃。 */
    public static Bitmap finishStream(Stream s, float sharpen) {
        if (s.frames < 2) return null;
        int n = s.dw * s.dh;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int c = Math.max(1, s.count[i]);
            int r = (int) (s.accR[i] / c + 0.5f);
            int g = (int) (s.accG[i] / c + 0.5f);
            int b = (int) (s.accB[i] / c + 0.5f);
            out[i] = (0xFF << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
        }
        // 最终亮度对齐到参考帧（原图）亮度：多帧平均即使在帧间归一化后仍可能比原图偏暗，
        // 此处按整体灰度均值做一次保守线性对齐，保证超分结果不至于比原图更暗。
        float align = clampAlign(s.refMean / meanImage(out));
        if (Math.abs(align - 1f) > 0.02f) {
            for (int i = 0; i < n; i++) {
                int p = out[i];
                out[i] = (0xFF << 24)
                        | (clamp(Math.round(((p >> 16) & 0xFF) * align)) << 16)
                        | (clamp(Math.round(((p >> 8) & 0xFF) * align)) << 8)
                        | clamp(Math.round((p & 0xFF) * align));
            }
        }
        out = unsharp(out, s.dw, s.dh, 2, sharpen);
        return Bitmap.createBitmap(out, s.dw, s.dh, Bitmap.Config.ARGB_8888);
    }

    /** 图像整体灰度均值（ARGB_8888）。 */
    private static float meanImage(int[] img) {
        long sum = 0;
        for (int p : img) {
            sum += ((p >> 16) & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587 + (p & 0xFF) * 114;
        }
        return (sum / 1000f) / img.length;
    }

    /** 最终亮度对齐增益：保守区间 [0.6, 1.6]，避免矫正过度造成过曝/欠曝。 */
    private static float clampAlign(float g) {
        return g < 0.6f ? 0.6f : (g > 1.6f ? 1.6f : g);
    }
}