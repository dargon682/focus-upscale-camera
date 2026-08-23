package com.photo.tool;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** 设置页的一行：左侧标题 + 右侧开关。 */
public class SettingRow extends LinearLayout {

    private final TextView label;
    private final Switch sw;

    public SettingRow(Context c, AttributeSet attrs) {
        super(c, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        label = new TextView(c);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(15);
        addView(label, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        sw = new Switch(c);
        addView(sw, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    public SettingRow setLabel(String text) {
        label.setText(text);
        return this;
    }

    public SettingRow setChecked(boolean checked) {
        sw.setChecked(checked);
        return this;
    }

    public SettingRow setOnToggled(CompoundButton.OnCheckedChangeListener l) {
        sw.setOnCheckedChangeListener(l);
        return this;
    }
}