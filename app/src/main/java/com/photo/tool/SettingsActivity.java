package com.photo.tool;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页：各功能开关与超分参数。 */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemeManager.res(this));    // 主题插件换肤（须在 super 前）
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        SettingRow rowGrid = findViewById(R.id.rowGrid);
        rowGrid.setLabel(getString(R.string.lbl_grid))
                .setChecked(Prefs.gridEnabled(this))
                .setOnToggled((b, on) -> Prefs.putGrid(this, on));

        SettingRow rowLevel = findViewById(R.id.rowLevel);
        rowLevel.setLabel(getString(R.string.lbl_level))
                .setChecked(Prefs.levelEnabled(this))
                .setOnToggled((b, on) -> Prefs.putLevel(this, on));

        SettingRow rowCompare = findViewById(R.id.rowCompare);
        rowCompare.setLabel(getString(R.string.lbl_compare))
                .setChecked(Prefs.compareEnabled(this))
                .setOnToggled((b, on) -> Prefs.putCompare(this, on));

        setupSpinner(R.id.spScale, R.array.scale_entries, R.array.scale_values,
                Prefs.scale(this), v -> Prefs.putScale(this, v.intValue()));
        setupSpinner(R.id.spFrames, R.array.frames_entries, R.array.frames_values,
                Prefs.frames(this), v -> Prefs.putFrames(this, v.intValue()));
        setupSpinner(R.id.spSharpen, R.array.sharpen_entries, R.array.sharpen_values,
                Math.round(Prefs.sharpen(this) * 10) / 10.0f, v -> Prefs.putSharpen(this, v));
        setupSpinner(R.id.spQuality, R.array.quality_entries, R.array.quality_values,
                Prefs.quality(this), v -> Prefs.putQuality(this, v.intValue()));
        setupSpinner(R.id.spGrid, R.array.grid_entries, R.array.grid_values,
                Prefs.gridStyle(this), v -> Prefs.putGridStyle(this, v.intValue()));
        setupSpinner(R.id.spMirror, R.array.mirror_entries, R.array.mirror_values,
                Prefs.downloadMirror(this), v -> Prefs.putDownloadMirror(this, v.intValue()));

        buildFilterPlugins();

        Button btnTestSpeed = findViewById(R.id.btnTestSpeed);
        btnTestSpeed.setOnClickListener(v -> testSpeed());

        Button btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        btnCheckUpdate.setOnClickListener(v -> checkUpdate());

        Button btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());
    }

    /** 触发一次更新检查：最新则提示，否则弹下载确认框。 */
    private void checkUpdate() {
        Button b = findViewById(R.id.btnCheckUpdate);
        b.setEnabled(false);
        b.setText(R.string.upd_checking);
        UpdateChecker.check(this, () -> runOnUiThread(() -> {
            b.setEnabled(true);
            b.setText(R.string.btn_check_update);
            Toast.makeText(this, R.string.upd_fail, Toast.LENGTH_LONG).show();
        }), (versionName, changeLog, apkUrl, isLatest) -> {
            b.setEnabled(true);
            b.setText(R.string.btn_check_update);
            if (isLatest || apkUrl.isEmpty()) {
                Toast.makeText(this, getString(R.string.upd_current,
                        BuildConfig.VERSION_NAME), Toast.LENGTH_LONG).show();
            } else {
                UpdateChecker.prompt(this, versionName, apkUrl, changeLog);
            }
        });
    }

    /** 测试各镜像源下载速度：自动选中最快源并更新下拉框与设置。 */
    private void testSpeed() {
        Button b = findViewById(R.id.btnTestSpeed);
        b.setEnabled(false);
        String orig = b.getText().toString();
        b.setText(R.string.speed_testing);
        UpdateChecker.testSpeeds(() -> runOnUiThread(() -> {
            b.setEnabled(true);
            b.setText(orig);
            Toast.makeText(this, R.string.speed_test_fail, Toast.LENGTH_LONG).show();
        }), (speeds, bestIdxFound) -> {
            b.setEnabled(true);
            b.setText(orig);
            // 自动切换到最快源（设置模式置为“自动”）
            Prefs.putDownloadMirror(this, 0);
            Spinner spMirror = findViewById(R.id.spMirror);
            spMirror.setSelection(0);

            StringBuilder sb = new StringBuilder();
            String[] names = getResources().getStringArray(R.array.mirror_entries);
            for (int i = 0; i < UpdateChecker.MIRROR_NAMES.length; i++) {
                sb.append(UpdateChecker.MIRROR_NAMES[i])
                        .append("：")
                        .append(speeds[i] > 0 ? String.format(java.util.Locale.US, "%.2f MB/s", speeds[i]) : "不可用")
                        .append("\n");
            }
            sb.append("\n").append(getString(R.string.speed_best,
                    UpdateChecker.MIRROR_NAMES[UpdateChecker.effectiveMirrorIndex(0)]));
            new AlertDialog.Builder(this)
                    .setTitle(R.string.speed_result_title)
                    .setMessage(sb.toString().trim())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
    }

    private void buildFilterPlugins() {
        LinearLayout container = findViewById(R.id.filterPlugins);

        // 功能插件开关
        for (FeaturePlugin fp : PluginRegistry.features()) {
            SettingRow row = new SettingRow(this, null);
            row.setLabel(fp.name())
                    .setChecked(Prefs.pluginOn(this, fp.id()))
                    .setOnToggled((b, on) -> Prefs.putPluginOn(this, fp.id(), on));
            container.addView(row,
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        // 主题插件单选
        LinearLayout themeRow = new LinearLayout(this);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView tl = new TextView(this);
        tl.setText(getString(R.string.lbl_theme));
        tl.setTextColor(ThemeManager.color(this, R.attr.tokTextPrimary));
        tl.setTextSize(14);
        themeRow.addView(tl, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        for (ThemePlugin tp : PluginRegistry.themes()) {
            Button b = new Button(this);
            b.setText(tp.name());
            b.setAllCaps(true);
            boolean sel = tp.id().equals(ThemeManager.current(this).id());
            styleThemeBtn(b, sel);
            b.setOnClickListener(v -> {
                if (ThemeManager.apply(this, tp.id())) recreate();   // 换肤后重建界面
            });
            themeRow.addView(b, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        container.addView(themeRow,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        // 滤镜插件开关
        TextView fh = new TextView(this);
        fh.setText(getString(R.string.filter_title));
        fh.setTextColor(ThemeManager.color(this, R.attr.tokTextSecondary));
        fh.setTextSize(12);
        fh.setPadding(0, dp(10), 0, dp(4));
        container.addView(fh, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        for (FilterPlugin p : FilterPluginRegistry.all()) {
            if (p.isNone()) continue;                // 无滤镜不可关闭
            SettingRow row = new SettingRow(this, null);
            row.setLabel(p.name())
                    .setChecked(Prefs.pluginOn(this, p.id()))
                    .setOnToggled((b, on) -> Prefs.putPluginOn(this, p.id(), on));
            container.addView(row,
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private void styleThemeBtn(Button b, boolean selected) {
        b.setTextColor(selected
                ? ThemeManager.color(this, R.attr.tokTextOnAccent)
                : ThemeManager.color(this, R.attr.tokTextPrimary));
        b.setBackgroundColor(selected
                ? ThemeManager.color(this, R.attr.tokAccent)
                : ThemeManager.color(this, R.attr.tokCardBg));
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    /** 用 entries + values 填充 Spinner，并让初值命中当前设置。 */
    private void setupSpinner(int id, int entriesRes, int valuesRes, float current,
                              java.util.function.Consumer<Float> onSelect) {
        Spinner sp = findViewById(id);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, entriesRes,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);

        String[] values = getResources().getStringArray(valuesRes);
        int sel = 0;
        for (int i = 0; i < values.length; i++) {
            if (Float.parseFloat(values[i]) == current) { sel = i; break; }
        }
        sp.setSelection(sel);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                onSelect.accept(Float.parseFloat(values[pos]));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }
}