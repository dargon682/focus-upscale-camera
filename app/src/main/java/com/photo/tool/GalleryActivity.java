package com.photo.tool;

import android.app.Activity;
import android.app.AlertDialog;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 历史相册：浏览已保存到系统相册 Pictures/FocusUpscale 的照片。
 * 网格缩略图展示，点击弹出全图查看；Android 10+ 免权限，低版本需运行时读权限。
 */
public class GalleryActivity extends Activity {

    private static final int REQ_READ = 300;
    private final ExecutorService exec = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> thumbCache =
            new LruCache<String, Bitmap>(8 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap bmp) { return bmp.getByteCount(); }
            };

    private GridView grid;
    private TextView emptyView;
    private Button btnBack;
    private final List<Item> items = new ArrayList<>();
    private boolean pendingPermission = false;

    private static final class Item {
        final long id;
        final String path;
        final String name;
        Item(long id, String path, String name) { this.id = id; this.path = path; this.name = name; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemeManager.res(this));    // 主题插件换肤（须在 super 前）
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);

        grid = findViewById(R.id.galleryGrid);
        emptyView = findViewById(R.id.galleryEmpty);
        btnBack = findViewById(R.id.btnGalleryBack);
        btnBack.setOnClickListener(v -> finish());
        grid.setOnItemClickListener((p, v, pos, id) -> showLarge(items.get(pos)));

        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(
                android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingPermission = true;
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_READ);
        } else {
            load();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (pendingPermission && requestCode == REQ_READ && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingPermission = false;
            load();
        } else if (pendingPermission) {
            pendingPermission = false;
            emptyView.setText(R.string.gallery_permission);
            emptyView.setVisibility(View.VISIBLE);
        }
    }

    /** 查询本应用结果目录下的照片，按保存时间倒序。 */
    private void load() {
        items.clear();
        String[] proj = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATA
        };
        // 仅匹配本应用保存的图片（文件名前缀 + 目录），避免展示相册全部内容
        String selection = MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?";
        String[] selArgs = {"focus_upscale_%"};
        try (Cursor c = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                proj, selection, selArgs,
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String name = c.getString(1);
                    String path = c.getString(2);
                    if (path == null || path.isEmpty()) continue;
                    items.add(new Item(id, path, name));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        emptyView.setText(R.string.gallery_empty);
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        grid.setAdapter(new ThumbAdapter());
    }

    private final class ThumbAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int pos) { return items.get(pos); }
        @Override public long getItemId(int pos) { return items.get(pos).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ImageView iv = convertView instanceof ImageView
                    ? (ImageView) convertView : new ImageView(GalleryActivity.this);
            iv.setScaleType(ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFF333333);
            iv.setTag(position);
            iv.setImageResource(android.R.color.darker_gray);

            final Item it = items.get(position);
            Bitmap cached = thumbCache.get(it.path);
            if (cached != null) {
                if ((Integer) iv.getTag() == position) iv.setImageBitmap(cached);
                return iv;
            }
            exec.execute(() -> {
                Bitmap b = decodeSampled(it.path, 240);
                if (b == null) return;
                thumbCache.put(it.path, b);
                runOnUiThread(() -> {
                    if ((Integer) iv.getTag() == position) iv.setImageBitmap(b);
                });
            });
            return iv;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (grid != null && !pendingPermission) load();
    }

    private void showLarge(final Item it) {
        exec.execute(() -> {
            final Bitmap big = decodeSampled(it.path, 2048);
            runOnUiThread(() -> {
                if (big == null) { Toast.makeText(this, "无法打开图片", Toast.LENGTH_SHORT).show(); return; }
                ImageView iv = new ImageView(GalleryActivity.this);
                iv.setImageBitmap(big);
                iv.setScaleType(ScaleType.FIT_CENTER);
                new AlertDialog.Builder(GalleryActivity.this)
                        .setTitle(it.name)
                        .setView(iv)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

    private Bitmap decodeSampled(String path, int reqWidth) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        while (bounds.outWidth / sample > reqWidth) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(path, o);
    }

    @Override
    protected void onDestroy() {
        exec.shutdownNow();
        super.onDestroy();
    }
}