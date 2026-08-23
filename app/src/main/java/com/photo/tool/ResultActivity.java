package com.photo.tool;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** 结果展示页：显示合成结果并支持与原图对比、缩放查看、保存到相册。 */
public class ResultActivity extends Activity {

    private CompareView compareView;
    private TextView tvTitle;
    private TextView tvCompareHint;
    private Button btnSave, btnBack, btnCompare, btnViewSuper, btnViewOrig;
    private Bitmap image;
    private String title = "";
    private boolean pendingSave = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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

        // 主保存目标 = 超分结果
        image = superBmp;
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
            image.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.flush();
            os.close();

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