package com.photo.tool;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 结果展示页：显示合成结果并支持与原图对比、缩放查看、保存到相册。 */
public class ResultActivity extends Activity {

    private CompareView compareView;
    private TextView tvTitle;
    private TextView tvCompareHint;
    private Button btnSave, btnBack, btnCompare, btnViewSuper, btnViewOrig;
    private Bitmap image;
    private Bitmap baseSuper;
    private Bitmap origBitmap;
    private String title = "";
    private boolean pendingSave = false;

    /** 滤镜处理：单线程串行执行，避免并发写位图。 */
    private final ExecutorService filterExec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean applyingFilter = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemeManager.res(this));    // 主题插件换肤（须在 super 前）
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);

        compareView = findViewById(R.id.compareView);
        tvTitle = findViewById(R.id.tvTitle);
        tvCompareHint = findViewById(R.id.tvCompareHint);
        btnSave = findViewById(R.id.btnSave);
        btnBack = findViewById(R.id.btnBack);
        btnCompare = findViewById(R.id.btnCompare);
        btnViewSuper = findViewById(R.id.btnViewSuper);
        btnViewOrig = findViewById(R.id.btnViewOrig);

        title = getIntent().getStringExtra("title");
        if (title != null) tvTitle.setText(title);

        String path = getIntent().getStringExtra("path");
        Bitmap superBmp = path != null ? BitmapFactory.decodeFile(path) : null;
        String origPath = getIntent().getStringExtra("origPath");
        Bitmap origBmp = origPath != null ? BitmapFactory.decodeFile(origPath) : null;

        // 主保存目标 = 超分结果；baseSuper 为未加滤镜的原始超分，供滤镜切换时重建
        baseSuper = superBmp;
        image = superBmp;
        origBitmap = origBmp;
        compareView.setBitmaps(superBmp, origBmp);

        // 默认进入对比模式（若设置了对比开关且有原图），否则仅显超分
        boolean hasOrig = origBmp != null && Prefs.compareEnabled(this);
        if (hasOrig) {
            btnCompare.setSelected(true);
            setMode(CompareView.Mode.COMPARE);
        } else {
            btnViewSuper.setSelected(true);
            setMode(CompareView.Mode.SUPER);
        }
        btnViewOrig.setEnabled(hasOrig);
        tvCompareHint.setVisibility(hasOrig ? View.VISIBLE : View.GONE);

        btnCompare.setOnClickListener(v -> setMode(CompareView.Mode.COMPARE));
        btnViewSuper.setOnClickListener(v -> setMode(CompareView.Mode.SUPER));
        btnViewOrig.setOnClickListener(v -> setMode(CompareView.Mode.ORIG));

        btnSave.setOnClickListener(v -> trySave());
        btnBack.setOnClickListener(v -> finish());

        buildFilterBar();
    }

    /** 构建滤镜插件选择条；进入时按已保存的当前滤镜应用一次。 */
    private void buildFilterBar() {
        LinearLayout bar = findViewById(R.id.filterBar);
        List<FilterPlugin> list = FilterPluginRegistry.enabled(this);
        if (list.isEmpty()) return;

        String cur = Prefs.currentFilter(this);
        for (FilterPlugin f : list) {
            final String fId = f.id();
            TextView chip = new TextView(this);
            chip.setText(f.name());
            chip.setTextSize(12);
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            chip.setLayoutParams(lp);
            chip.setGravity(Gravity.CENTER);
            chip.setTag(fId);
            styleChip(chip, fId.equals(cur));
            chip.setOnClickListener(v -> {
                if (applyingFilter) return;
                Prefs.putCurrentFilter(this, fId);
                applyFilter(fId);
                refreshChips(fId);
            });
            bar.addView(chip);
        }

        // 启动时应用已保存的当前滤镜（若仍被启用）
        FilterPlugin saved = FilterPluginRegistry.byId(cur);
        if (saved != null && (saved.isNone() || Prefs.pluginOn(this, saved.id()))) {
            if (!saved.isNone()) applyFilter(saved.id());
            return;
        }
        // 已保存的滤镜被关闭 → 回退无滤镜
        Prefs.putCurrentFilter(this, FilterPlugin.ID_NONE);
        refreshChips(FilterPlugin.ID_NONE);
    }

    private void refreshChips(String selectedId) {
        LinearLayout bar = findViewById(R.id.filterBar);
        for (int i = 0; i < bar.getChildCount(); i++) {
            TextView chip = (TextView) bar.getChildAt(i);
            styleChip(chip, selectedId.equals(chip.getTag()));
        }
    }

    private void styleChip(TextView chip, boolean selected) {
        chip.setTextColor(selected
                ? ThemeManager.color(this, R.attr.tokTextOnAccent)
                : ThemeManager.color(this, R.attr.tokTextPrimary));
        chip.setBackgroundColor(selected
                ? ThemeManager.color(this, R.attr.tokAccent)
                : ThemeManager.color(this, R.attr.tokCardBg));
    }

    /** 在后台线程对原始超分 baseSuper 应用滤镜，完成后替换当前 image 并刷新对比视图。 */
    private void applyFilter(String id) {
        FilterPlugin f = FilterPluginRegistry.byId(id);
        if (f == null || applyingFilter) return;
        applyingFilter = true;

        filterExec.execute(() -> {
            Bitmap next;
            try {
                next = f.apply(baseSuper);
            } catch (Throwable t) {
                next = baseSuper;
            }
            final Bitmap n = next;
            main.post(() -> {
                // 回收被替换的滤镜结果（无滤镜时 image 即 baseSuper，不回收）
                if (image != null && image != baseSuper) image.recycle();
                image = n;
                compareView.setBitmaps(image != null ? image : baseSuper, origBitmap);
                applyingFilter = false;
            });
        });
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    @Override
    protected void onDestroy() {
        filterExec.shutdownNow();
        if (image != null && image != baseSuper) image.recycle();
        if (baseSuper != null) baseSuper.recycle();
        super.onDestroy();
    }

    private void setMode(CompareView.Mode m) {
        compareView.setMode(m);
        btnCompare.setSelected(m == CompareView.Mode.COMPARE);
        btnViewSuper.setSelected(m == CompareView.Mode.SUPER);
        btnViewOrig.setSelected(m == CompareView.Mode.ORIG);
    }

    private void trySave() {
        if (image == null) return;
        if (Build.VERSION.SDK_INT < 29) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingSave = true;
                requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 200);
                return;
            }
        }
        saveToGallery();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (pendingSave && requestCode == 200 && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingSave = false;
            saveToGallery();
        }
    }

    private void saveToGallery() {
        try {
            long now = System.currentTimeMillis();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "focus_upscale_" + now + ".png");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FocusUpscale");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri uri = getContentResolver().insert(collection, values);
            if (uri == null) throw new Exception("insert failed");
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new Exception("open failed");
            // 功能插件钩子：日期水印等功能在此对落盘位图做后处理
            Bitmap toSave = image;
            for (FeaturePlugin fp : PluginRegistry.features()) {
                toSave = fp.onSave(toSave, this);
            }
            toSave.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.flush();
            os.close();
            if (toSave != image) {                    // 插件返回了新位图，写入后回收
                toSave.recycle();
            }

            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);

            Toast.makeText(this, "已保存到相册 Pictures/FocusUpscale", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "保存失败:" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}