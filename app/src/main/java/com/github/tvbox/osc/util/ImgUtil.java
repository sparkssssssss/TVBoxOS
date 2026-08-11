package com.github.tvbox.osc.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.FitCenter;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class ImgUtil {
    /** 占位图/错误图缓存上限（按 Bitmap 字节计），避免无界增长造成内存压力 */
    private static final int MAX_PLACEHOLDER_MEMORY = 16 * 1024 * 1024;
    private static final LruCache<String, Drawable> drawableCache = new LruCache<String, Drawable>(MAX_PLACEHOLDER_MEMORY) {
        @Override
        protected int sizeOf(String key, Drawable value) {
            if (value instanceof BitmapDrawable) {
                Bitmap bitmap = ((BitmapDrawable) value).getBitmap();
                if (bitmap != null) return bitmap.getByteCount();
            }
            return 1;
        }
    };
    public static int defaultWidth = 244;
    public static int defaultHeight = 320;

    public static class Style {
        public float ratio;
        public String type;

        public Style(float ratio, String type) {
            this.ratio = ratio;
            this.type = type;
        }
    }

    public static boolean isBase64Image(String picUrl) {
        return picUrl != null && picUrl.startsWith("data:image");
    }

    public static Style initStyle() {
        String bStyle = ApiConfig.get().getHomeSourceBean().getStyle();
        if (!bStyle.isEmpty()) {
            try {
                JSONObject jsonObject = new JSONObject(bStyle);
                return new Style((float) jsonObject.getDouble("ratio"), jsonObject.getString("type"));
            } catch (JSONException ignored) {
            }
        }
        return null;
    }

    public static int spanCountByStyle(Style style, int defaultCount) {
        int spanCount = defaultCount;
        if ("rect".equals(style.type)) {
            if (style.ratio >= 1.7) {
                spanCount = 3;
            } else if (style.ratio >= 1.3) {
                spanCount = 4;
            }
        } else if ("list".equals(style.type)) {
            spanCount = 1;
        }
        return spanCount;
    }

    public static int getStyleDefaultWidth(Style style) {
        int styleDefaultWidth = 280;
        if (style.ratio < 1) styleDefaultWidth = 214;
        if (style.ratio > 1.7) styleDefaultWidth = 380;
        return styleDefaultWidth;
    }

    public static Bitmap decodeBase64ToBitmap(String base64Str) {
        String base64Data = base64Str.substring(base64Str.indexOf(",") + 1);
        byte[] decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
        return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
    }

    // ========== Base64 海报异步解码 ==========

    /** Base64 解码结果缓存（按 Bitmap 字节计量，避免无界增长） */
    private static final int MAX_BASE64_CACHE_BYTES = 32 * 1024 * 1024;
    private static final LruCache<String, Bitmap> base64BitmapCache = new LruCache<String, Bitmap>(MAX_BASE64_CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getByteCount();
        }
    };
    // 单线程后台解码；有界队列 + 丢弃拒绝策略，避免快速滚动时任务无限堆积
    private static final int BASE64_QUEUE_MAX = 128;
    private static final ExecutorService base64Executor;
    // 正在解码（或已在队列中）的 Base64 key，避免同一图片重复排队
    private static final Set<String> base64InFlight = new HashSet<>();

    static {
        ThreadPoolExecutor exec = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(BASE64_QUEUE_MAX));
        // 队列满时丢弃新任务；图片显示会暂时停留在占位图，但不会让 UI 线程因异常崩溃
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        base64Executor = exec;
    }

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final AtomicLong REQ_SEQ = new AtomicLong();

    /**
     * 为 ImageView 打上当前绑定标记，用于拦截旧异步结果，防止 ViewHolder 复用导致图片错乱。
     * 每次 convert 绑定（无论走 Base64/Glide/占位分支）都应先调用。
     */
    public static void markView(ImageView view) {
        long id = REQ_SEQ.incrementAndGet();
        view.setTag(R.id.tag_img_request_id, id);
    }

    /**
     * 异步加载 Base64 海报：命中缓存直接设置；未命中先显示占位，后台线程解码完成后回主线程设置。
     * 解码失败显示文字占位图。内部已做 ViewHolder 复用防错校验。
     */
    public static void loadBase64(String pic, ImageView view, String label) {
        final long reqId = REQ_SEQ.incrementAndGet();
        view.setTag(R.id.tag_img_request_id, reqId);

        Bitmap hit = base64BitmapCache.get(pic);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        // 未命中先显示占位，避免残留上一手内容；占位图走 drawableCache 有界缓存
        view.setImageDrawable(createTextDrawable(label));
        // 同一图片已在解码/排队则不再重复提交（防重复排队）
        synchronized (base64InFlight) {
            if (base64InFlight.contains(pic)) return;
            base64InFlight.add(pic);
        }
        try {
            base64Executor.execute(() -> {
                try {
                    // 执行前再查一次缓存：前面排队的任务可能已解出同一张图
                    Bitmap bmp = base64BitmapCache.get(pic);
                    if (bmp == null) {
                        try {
                            bmp = decodeBase64ToBitmap(pic);
                        } catch (Throwable ignored) {
                            // 损坏的 Base64 走占位图，避免主线程崩溃（原同步路径无保护）
                        }
                        if (bmp != null) {
                            base64BitmapCache.put(pic, bmp);
                        }
                    }
                    final Bitmap result = bmp;
                    mainHandler.post(() -> {
                        Object tag = view.getTag(R.id.tag_img_request_id);
                        if (tag instanceof Long && ((Long) tag).longValue() == reqId) {
                            view.setImageBitmap(result != null ? result : createTextDrawable(label));
                        }
                    });
                } finally {
                    synchronized (base64InFlight) {
                        base64InFlight.remove(pic);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // 队列满被丢弃：释放 inFlight 标记，图片保持占位
            synchronized (base64InFlight) {
                base64InFlight.remove(pic);
            }
        }
    }

    public static void load(String url, ImageView view, int roundingRadius) {
        load(url, view, roundingRadius, 0, 0, null);
    }

    public static void load(String url, ImageView view, int roundingRadius, int newWidth, int newHeight) {
        load(url, view, roundingRadius, newWidth, newHeight, null);
    }

    public static void load(String url, ImageView view, int roundingRadius, int newWidth, int newHeight, String label, ImageView.ScaleType scaleType) {
        view.setScaleType(scaleType);
        if (roundingRadius <= 0) roundingRadius = 1;
        Drawable fallback = createTextDrawable(TextUtils.isEmpty(label) ? "TVBox" : label, newWidth, newHeight, roundingRadius);
        Drawable placeholder = createImagePlaceholderDrawable(newWidth, newHeight, roundingRadius);
        if (isInvalidImageUrl(url)) {
            view.setImageDrawable(fallback);
            return;
        }
        RequestOptions options = new RequestOptions()
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .dontAnimate()
                .transform(new FitCenter(), new RoundedCorners(roundingRadius));
        if (newWidth > 0 && newHeight > 0) {
            options = options.override(newWidth, newHeight);
        }
        Glide.with(App.getInstance())
                .asBitmap()
                .load(getUrl(url))
                .placeholder(placeholder)
                .error(fallback)
                .listener(getListener(view, scaleType, fallback))
                .apply(options)
                .into(view);
    }

    public static void load(String url, ImageView view, int roundingRadius, int newWidth, int newHeight, String label) {
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (roundingRadius <= 0) roundingRadius = 1;
        Drawable fallback = createTextDrawable(TextUtils.isEmpty(label) ? "TVBox" : label, newWidth, newHeight, roundingRadius);
        Drawable placeholder = createImagePlaceholderDrawable(newWidth, newHeight, roundingRadius);
        if (isInvalidImageUrl(url)) {
            view.setImageDrawable(fallback);
            return;
        }
        RequestOptions options = new RequestOptions()
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .dontAnimate()
                .transform(new CenterCrop(), new RoundedCorners(roundingRadius));
        if (newWidth > 0 && newHeight > 0) {
            options = options.override(newWidth, newHeight);
        }
        Glide.with(App.getInstance())
                .asBitmap()
                .load(getUrl(url))
                .placeholder(placeholder)
                .error(fallback)
                .listener(getListener(view, ImageView.ScaleType.CENTER_CROP, fallback))
                .apply(options)
                .into(view);
    }

    public static void loadUrl(String url, ImageView view) {
        load(url, view, 10);
    }

    public static void loadVideoScreenshot(String uri, ImageView imageView, long frameTimeMicros) {
        RequestOptions requestOptions = RequestOptions.frameOf(frameTimeMicros * 1000)
                .transform(new CenterCrop(), new RoundedCorners(10));
        Glide.with(App.getInstance())
                .load(uri)
                .skipMemoryCache(true)
                .apply(requestOptions)
                .into(imageView);
    }

    public static int getRandomColor() {
        Random random = new Random();
        return Color.argb(255, random.nextInt(256), random.nextInt(256), random.nextInt(256));
    }

    public static Drawable createTextDrawable(String text) {
        return createTextDrawable(text, 0, 0, AutoSizeUtils.mm2px(App.getInstance(), 10));
    }

    private static Drawable createTextDrawable(String text, int width, int height, float cornerRadius) {
        if (TextUtils.isEmpty(text)) text = "TVBox";
        if (width <= 0) width = 180;
        if (height <= 0) height = 240;
        if (cornerRadius <= 0) cornerRadius = 1;
        text = text.substring(0, 1);
        String key = text + "_" + width + "x" + height + "_" + (int) cornerRadius;
        Drawable cached = drawableCache.get(key);
        if (cached != null) return cached;
        int randomColor = getRandomColor();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(randomColor);
        paint.setStyle(Paint.Style.FILL);
        RectF rectF = new RectF(0, 0, width, height);
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(60);
        paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fontMetrics = paint.getFontMetrics();
        float x = width / 2f;
        float y = (height - fontMetrics.bottom - fontMetrics.top) / 2f;
        canvas.drawText(text, x, y, paint);
        Drawable drawable = new BitmapDrawable(App.getInstance().getResources(), bitmap);
        drawableCache.put(key, drawable);
        return drawable;
    }

    private static Drawable createImagePlaceholderDrawable(int width, int height, float cornerRadius) {
        if (width <= 0) width = 180;
        if (height <= 0) height = 240;
        if (cornerRadius <= 0) cornerRadius = 1;
        String key = "placeholder_" + width + "x" + height + "_" + (int) cornerRadius;
        Drawable cached = drawableCache.get(key);
        if (cached != null) return cached;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Bitmap icon = BitmapFactory.decodeResource(App.getInstance().getResources(), com.github.tvbox.osc.R.drawable.icon_img_placeholder);
        if (icon != null) {
            float left = (width - icon.getWidth()) / 2f;
            float top = (height - icon.getHeight()) / 2f;
            canvas.drawBitmap(icon, left, top, null);
        }

        Drawable drawable = new BitmapDrawable(App.getInstance().getResources(), bitmap);
        drawableCache.put(key, drawable);
        return drawable;
    }

    public static void clearCache() {
        drawableCache.evictAll();
    }

    public static void clearMemoryCache() {
        clearCache();
        base64BitmapCache.evictAll();
        try {
            Glide.get(App.getInstance()).clearMemory();
            LOG.i("echo-img-clear-memory-cache");
        } catch (Throwable th) {
            LOG.i("echo-img-clear-memory-cache-error:" + th.getMessage());
        }
    }

    private static Object getUrl(String url) {
        if (url.startsWith("data:")) return url;
        String header = null;
        String referer = null;
        String ua = null;
        String cookie = null;
        if (url.contains("@Headers=")) {
            header = url.split("@Headers=")[1].split("@")[0];
            try {
                header = URLDecoder.decode(header, "UTF-8");
            } catch (UnsupportedEncodingException ignored) {
            }
        }
        if (url.contains("@Cookie=")) cookie = url.split("@Cookie=")[1].split("@")[0];
        if (url.contains("@User-Agent=")) ua = url.split("@User-Agent=")[1].split("@")[0];
        if (url.contains("@Referer=")) referer = url.split("@Referer=")[1].split("@")[0];
        url = url.split("@")[0];
        if (TextUtils.isEmpty(url)) return null;

        LazyHeaders.Builder builder = new LazyHeaders.Builder();
        Map<String, String> headers = new HashMap<>();
        if (!TextUtils.isEmpty(header)) {
            try {
                JsonObject jsonInfo = new Gson().fromJson(header, JsonObject.class);
                for (String key : jsonInfo.keySet()) {
                    putHeader(headers, key, jsonInfo.get(key).getAsString());
                }
            } catch (Throwable ignored) {
            }
        }
        putHeader(headers, "Cookie", cookie);
        if (!TextUtils.isEmpty(ua)) putHeader(headers, "User-Agent", ua);
        if (!TextUtils.isEmpty(referer)) putHeader(headers, "Referer", referer);
        for (Map.Entry<String, String> entry : headers.entrySet()) builder.setHeader(entry.getKey(), entry.getValue());
        return new GlideUrl(url, builder.build());
    }

    private static boolean isInvalidImageUrl(String url) {
        if (TextUtils.isEmpty(url)) return true;
        url = url.trim();
        if (TextUtils.isEmpty(url)) return true;
        return hasEmptyProxyParam(url, "img");
    }

    private static boolean hasEmptyProxyParam(String url, String key) {
        if (!url.startsWith("proxy://") && !url.contains("/proxy?")) return false;
        int queryIndex = url.indexOf('?');
        String query = queryIndex >= 0 ? url.substring(queryIndex + 1) : url.substring("proxy://".length());
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int eqIndex = pair.indexOf('=');
            if (eqIndex < 0) continue;
            if (key.equals(pair.substring(0, eqIndex)) && TextUtils.isEmpty(pair.substring(eqIndex + 1))) {
                return true;
            }
        }
        return false;
    }

    private static void putHeader(Map<String, String> headers, String key, String value) {
        if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) return;
        headers.put(key, value.trim());
    }

    private static RequestListener<Bitmap> getListener(final ImageView view, final ImageView.ScaleType scaleType, final Drawable fallback) {
        return new RequestListener<Bitmap>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                view.setScaleType(scaleType);
                view.setImageDrawable(fallback);
                return true;
            }

            @Override
            public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                view.setScaleType(scaleType);
                return false;
            }
        };
    }
}
