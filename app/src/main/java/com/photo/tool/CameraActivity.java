package com.photo.tool;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExposureState;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.FocusMeteringResult;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 相机主界面：预览、自动对焦、点按二次对焦、单张拍照、多帧超分合成，
 * 以及前后摄切换、闪光灯控制、设置入口、九宫格构图线与水平仪取景辅助。
 * 使用原生 Activity + 自实现 LifecycleOwner，以避免对 AppCompat 的依赖。
 */
public class CameraActivity extends Activity implements LifecycleOwner, SensorEventListener {

    private PreviewView previewView;
    private AidOverlayView aidOverlay;
    private Button btnEv, btnTimer, btnFlash, btnSwitchCamera, btnSettings, btnGallery, btnCancel, btnFrames;
    private Button btnModePhoto, btnModeSuper;
    private FrameLayout btnShutter;
    private FrameLayout imgRecent;
    private ImageView imgRecentThumb;
    private TextView tvStatus;
    private View focusBox;

    /** 后台 IO 单线程池：位图解码等耗主线程的操作移出主线程，避免冷启动/恢复卡顿。 */
    private final ExecutorService backgroundIo = Executors.newSingleThreadExecutor();
    /** CameraX 实例全程复用；在 onCreate 尽早预热，使相机初始化与界面布局并行。 */
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;

    /** 拍摄模式 0 拍照 / 1 超分 */
    private int mode = 0;

    private ImageCapture imageCapture;
    private Camera camera;
    private Handler mainHandler;

    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);

    private static final int REQ_CAMERA = 1001;
    /** 单帧采样默认宽度（均衡档），由画质档位决定实际值 */
    private static final int SAMPLE_WIDTH = 1200;

    /** 前后摄 */
    private boolean front = false;
    /** 闪光模式 0 关 / 1 自动 / 2 开 */
    private int flashMode = 0;

    /** 数字变焦 */
    private ScaleGestureDetector zoomDetector;
    private GestureDetector gestureDetector;
    private float zoomRatio = 1f;
    private float maxZoomRatio = 1f;
    private boolean zooming = false;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private boolean sensorRegistered = false;

    /** 超分连拍稳定性辅助：实时抖动监测，提示用户握稳相机提升动态捕捉成片率。 */
    private static final int SHAKE_WIN = 24;          // 加速度幅值采样窗
    private static final float SHAKE_WARN_G = 1.4f;   // 抖动报警阈值（偏离重力的RMS偏差，单位 g）
    private final float[] shakeSamples = new float[SHAKE_WIN];
    private int shakeIdx = 0;
    private int shakeCnt = 0;
    private boolean stabilityForSuper = false;        // 超分连拍期间强制开启监测

    private boolean capturing = false;
    /** 连拍取消标记 */
    private boolean cancelRequested = false;

    /** 曝光补偿档位序列（索引循环） */
    private static final int[] EV_VALUES = {0, 1, -1, 2, -2};
    private int evIdx = 0;
    /** 定时自拍延时（秒），0 = 关闭 */
    private int timerDelaySec = 0;
    /** 超分取帧数快捷挡位：取帧数越多信噪比越高（更稳），合成耗时略增。 */
    private static final int[] FRAME_VALUES = {7, 5, 9};
    private int framesIdx = 1;
    private static final int[] TIMER_VALUES = {0, 3, 5, 10};
    private int timerIdx = 0;
    /** 倒计时任务序号，避免多次计时交错 */
    private int timerSeq = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        // 启动性能：立即预热 CameraX 单例，使其初始化与下面的界面布局并行，缩短相机就绪时间。
        if (cameraProviderFuture == null) {
            cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        }
        setContentView(R.layout.activity_camera);
        mainHandler = new Handler(Looper.getMainLooper());

        previewView = findViewById(R.id.previewView);
        aidOverlay = findViewById(R.id.aidOverlay);
        btnEv = findViewById(R.id.btnEv);
        btnTimer = findViewById(R.id.btnTimer);
        btnFlash = findViewById(R.id.btnFlash);
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera);
        btnSettings = findViewById(R.id.btnSettings);
        btnGallery = findViewById(R.id.btnGallery);
        btnModePhoto = findViewById(R.id.btnModePhoto);
        btnModeSuper = findViewById(R.id.btnModeSuper);
        btnShutter = findViewById(R.id.btnShutter);
        btnCancel = findViewById(R.id.btnCancel);
        btnFrames = findViewById(R.id.btnFrames);
        imgRecent = findViewById(R.id.imgRecent);
        imgRecentThumb = findViewById(R.id.imgRecentThumb);
        tvStatus = findViewById(R.id.tvStatus);
        focusBox = findViewById(R.id.focusBox);
        applyModeButtons();

        // 取景辅助开关（从设置读取）
        aidOverlay.enable(Prefs.gridEnabled(this), Prefs.levelEnabled(this));

        initZoomGestures();

        // 点按预览画面：二次对焦（tap-to-focus）；双指缩放画面
        previewView.setOnTouchListener((v, ev) -> {
            zoomDetector.onTouchEvent(ev);
            gestureDetector.onTouchEvent(ev);
            if (ev.getPointerCount() >= 2) return true; // 双指变焦，不做对焦
            if (ev.getAction() == MotionEvent.ACTION_UP && !zooming) {
                final float x = ev.getX();
                final float y = ev.getY();
                mainExecutor().execute(() -> {
                    showFocusBox(x, y);
                    tapToFocus(x, y);
                });
            }
            return true;
        });

        btnShutter.setOnClickListener(v -> onShutterClick());
        btnModePhoto.setOnClickListener(v -> switchMode(0));
        btnModeSuper.setOnClickListener(v -> switchMode(1));
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        btnSwitchCamera.setOnClickListener(v -> switchCamera());
        btnFlash.setOnClickListener(v -> cycleFlash());
        btnGallery.setOnClickListener(v -> startActivity(new Intent(this, GalleryActivity.class)));
        imgRecent.setOnClickListener(v -> startActivity(new Intent(this, GalleryActivity.class)));
        btnCancel.setOnClickListener(v -> cancelSuper());
        btnCancel.setVisibility(View.GONE);
        btnEv.setOnClickListener(v -> cycleEv());
        btnTimer.setOnClickListener(v -> cycleTimer());
        btnFrames.setOnClickListener(v -> cycleFrames());
        syncFramesButton();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        if (!hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            startCamera();
        }
    }

    /** 快门点击：依据当前模式分派单张拍照或超分合成。 */
    private void onShutterClick() {
        if (mode == 1) {
            requestCapture(this::captureSuperResolution);
        } else {
            requestCapture(this::captureSingle);
        }
    }

    /** 切换拍摄模式（0 拍照 / 1 超分）并刷新选中态。 */
    private void switchMode(int m) {
        if (m == mode) return;
        mode = m;
        applyModeButtons();
        // 使取景辅助（若有网格样式依赖）重新绘制
        aidOverlay.invalidate();
    }

    private void applyModeButtons() {
        if (mode == 1) {
            btnModeSuper.setBackgroundResource(R.drawable.bg_icon_button_selected);
            btnModeSuper.setTextColor(ContextCompat.getColor(this, R.color.text_on_accent));
            btnModePhoto.setBackgroundResource(R.drawable.bg_capsule);
            btnModePhoto.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        } else {
            btnModePhoto.setBackgroundResource(R.drawable.bg_icon_button_selected);
            btnModePhoto.setTextColor(ContextCompat.getColor(this, R.color.text_on_accent));
            btnModeSuper.setBackgroundResource(R.drawable.bg_capsule);
            btnModeSuper.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private Executor mainExecutor() {
        return ContextCompat.getMainExecutor(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "需要相机权限才能使用相机", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------- 相机绑定 ----------

    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> future = cameraProviderFuture != null
                ? cameraProviderFuture
                : (cameraProviderFuture = ProcessCameraProvider.getInstance(this));
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                CameraSelector selector = front ? CameraSelector.DEFAULT_FRONT_CAMERA
                        : CameraSelector.DEFAULT_BACK_CAMERA;
                if (!provider.hasCamera(selector)) {
                    Toast.makeText(this, front ? "未检测到前置相机" : "未检测到后置相机", Toast.LENGTH_LONG).show();
                    return;
                }
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setFlashMode(flashToInt())
                        .setTargetResolution(new android.util.Size(SAMPLE_WIDTH, SAMPLE_WIDTH * 4 / 3))
                        .build();

                provider.unbindAll();
                camera = provider.bindToLifecycle(this, selector, preview, imageCapture);
                applyEv();
            } catch (ExecutionException | InterruptedException | CameraInfoUnavailableException e) {
                e.printStackTrace();
            }
        }, mainExecutor());
    }

    private void switchCamera() {
        front = !front;
        startCamera();
    }

    private void cycleFlash() {
        flashMode = (flashMode + 1) % 3;
        updateFlashButton();
        if (imageCapture != null) imageCapture.setFlashMode(flashToInt());
    }

    private int flashToInt() {
        switch (flashMode) {
            case 1: return ImageCapture.FLASH_MODE_AUTO;
            case 2: return ImageCapture.FLASH_MODE_ON;
            default: return ImageCapture.FLASH_MODE_OFF;
        }
    }

    private void updateFlashButton() {
        String s;
        switch (flashMode) {
            case 1: s = getString(R.string.btn_flash_auto); break;
            case 2: s = getString(R.string.btn_flash_on); break;
            default: s = getString(R.string.btn_flash_off); break;
        }
        btnFlash.setText(s);
    }

    // ---------- 曝光补偿 EV ----------

    private void cycleEv() {
        if (camera == null) return;
        ExposureState es = camera.getCameraInfo().getExposureState();
        if (!es.isExposureCompensationSupported()) {
            Toast.makeText(this, R.string.btn_ev_off, Toast.LENGTH_SHORT).show();
            return;
        }
        evIdx = (evIdx + 1) % EV_VALUES.length;
        applyEv();
    }

    private void applyEv() {
        if (camera == null) return;
        ExposureState es = camera.getCameraInfo().getExposureState();
        if (!es.isExposureCompensationSupported()) {
            btnEv.setText(R.string.btn_ev_off);
            return;
        }
        int v = EV_VALUES[evIdx];
        int lo = es.getExposureCompensationRange().getLower();
        int hi = es.getExposureCompensationRange().getUpper();
        int idx = Math.max(lo, Math.min(hi, v));
        camera.getCameraControl().setExposureCompensationIndex(idx);
        btnEv.setText(evLabel(idx));
    }

    private String evLabel(int v) {
        switch (v) {
            case 1: return getString(R.string.btn_ev_p1);
            case -1: return getString(R.string.btn_ev_n1);
            case 2: return getString(R.string.btn_ev_p2);
            case -2: return getString(R.string.btn_ev_n2);
            default: return getString(R.string.btn_ev_0);
        }
    }

    // ---------- 定时拍摄 ----------

    private void cycleTimer() {
        timerIdx = (timerIdx + 1) % TIMER_VALUES.length;
        timerDelaySec = TIMER_VALUES[timerIdx];
        timerSeq++; // 使正在进行的倒计时立即失效
        btnTimer.setText(timerLabel());
        if (timerDelaySec > 0) {
            Toast.makeText(this, timerLabel(), Toast.LENGTH_SHORT).show();
        }
    }

    private String timerLabel() {
        switch (timerDelaySec) {
            case 3: return getString(R.string.btn_timer_3);
            case 5: return getString(R.string.btn_timer_5);
            case 10: return getString(R.string.btn_timer_10);
            default: return getString(R.string.btn_timer_off);
        }
    }

    // ---------- 超分取帧数快捷挡 ----------

    private void cycleFrames() {
        framesIdx = (framesIdx + 1) % FRAME_VALUES.length;
        Prefs.putFrames(this, FRAME_VALUES[framesIdx]);
        syncFramesButton();
        Toast.makeText(this, framesLabel(), Toast.LENGTH_SHORT).show();
    }

    private void syncFramesButton() {
        // 与持久化存储当前值对齐（应用启动时从 Prefs 读取）
        int saved = Prefs.frames(this);
        framesIdx = 1; // 默认 7 的位置
        for (int i = 0; i < FRAME_VALUES.length; i++) {
            if (FRAME_VALUES[i] == saved) { framesIdx = i; break; }
        }
        btnFrames.setText(framesLabel());
    }

    private String framesLabel() {
        switch (FRAME_VALUES[framesIdx]) {
            case 5: return getString(R.string.btn_frames_5);
            case 9: return getString(R.string.btn_frames_9);
            default: return getString(R.string.btn_frames_7);
        }
    }

    /** 定时拍摄入口：延时>0 时先倒计时，再执行拍摄动作。 */
    private void requestCapture(Runnable shoot) {
        if (capturing) return;
        if (timerDelaySec <= 0) { shoot.run(); return; }
        final int seq = ++timerSeq;
        btnShutter.setEnabled(false);
        countdown(seq, timerDelaySec, shoot);
    }

    private void countdown(int seq, int remain, Runnable shoot) {
        if (seq != timerSeq) { // 被新指令或取消覆盖
            btnShutter.setEnabled(true);
            setStatus("");
            return;
        }
        if (remain <= 0) {
            btnShutter.setEnabled(true);
            setStatus("");
            if (seq == timerSeq) shoot.run();
            return;
        }
        setStatus(getString(R.string.timer_countdown, remain));
        mainHandler.postDelayed(() -> countdown(seq, remain - 1, shoot), 1000);
    }

    // ---------- 数字变焦 ----------

    private void initZoomGestures() {
        zoomDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                zooming = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                if (camera == null) return true;
                refreshMaxZoom();
                zoomRatio = clamp(zoomRatio * d.getScaleFactor(), 1f, Math.max(1f, maxZoomRatio));
                if (zoomRatio == 1f || zoomRatio == maxZoomRatio) zoomRatio = Math.round(zoomRatio);
                camera.getCameraControl().setZoomRatio(zoomRatio);
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                // 结束后稍延时复位，避免抬起时误触发对焦
                mainHandler.postDelayed(() -> zooming = false, 120);
            }
        });

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                resetZoom();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                // 长按锁定对焦与曝光：对触点触发 AF+AE 测光，自动取消时长延长至 30s
                if (camera == null) return;
                MeteringPoint point = previewView.getMeteringPointFactory().createPoint(e.getX(), e.getY());
                FocusMeteringAction action = new FocusMeteringAction.Builder(point,
                        FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                        .setAutoCancelDuration(30, TimeUnit.SECONDS).build();
                camera.getCameraControl().startFocusAndMetering(action);
                showFocusBox(e.getX(), e.getY());
                Toast.makeText(CameraActivity.this, R.string.lock_focus, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void refreshMaxZoom() {
        if (camera == null) return;
        ZoomState zs = camera.getCameraInfo().getZoomState().getValue();
        if (zs != null) maxZoomRatio = zs.getMaxZoomRatio();
    }

    private void resetZoom() {
        if (camera == null) return;
        zoomRatio = 1f;
        camera.getCameraControl().setZoomRatio(1f);
        Toast.makeText(this, "已复位到 1×", Toast.LENGTH_SHORT).show();
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---------- 对焦 ----------

    private void showFocusBox(float x, float y) {
        int s = focusBox.getWidth();
        focusBox.setX(x - s / 2f);
        focusBox.setY(y - s / 2f);
        focusBox.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hideBox);
        mainHandler.postDelayed(hideBox, 1200);
    }

    private final Runnable hideBox = () -> focusBox.setVisibility(View.INVISIBLE);

    private void tapToFocus(float x, float y) {
        if (camera == null) return;
        MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y);
        FocusMeteringAction action = new FocusMeteringAction.Builder(point,
                FocusMeteringAction.FLAG_AF).setAutoCancelDuration(2, TimeUnit.SECONDS).build();
        ListenableFuture<FocusMeteringResult> future =
                camera.getCameraControl().startFocusAndMetering(action);
        future.addListener(() -> {
            try {
                if (future.get() != null && future.get().isFocusSuccessful()) {
                    Toast.makeText(this, "已对焦", Toast.LENGTH_SHORT).show();
                }
            } catch (ExecutionException | InterruptedException ignored) { }
        }, mainExecutor());
    }

    private void autoFocusCenter() {
        if (camera == null || imageCapture == null) return;
        float cx = previewView.getWidth() / 2f;
        float cy = previewView.getHeight() / 2f;
        MeteringPoint point = previewView.getMeteringPointFactory().createPoint(cx, cy);
        FocusMeteringAction action = new FocusMeteringAction.Builder(point,
                FocusMeteringAction.FLAG_AF).build();
        camera.getCameraControl().startFocusAndMetering(action);
        Toast.makeText(this, "自动对焦中…", Toast.LENGTH_SHORT).show();
    }

    // ---------- 单张拍照 ----------

    private void captureSingle() {
        if (imageCapture == null || capturing) return;
        capturing = true;
        File dir = new File(getCacheDir(), "shot");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "single_" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions opts = new ImageCapture.OutputFileOptions.Builder(out).build();
        imageCapture.takePicture(opts, mainExecutor(),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                        Bitmap bmp = decodeSampled(out.getAbsolutePath(), qualityBaseWidth());
                        showResult(bmp, null, getString(R.string.btn_capture));
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        capturing = false;
                        Toast.makeText(CameraActivity.this, "拍照失败", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // ---------- 多帧超分 ----------

    private void captureSuperResolution() {
        if (imageCapture == null || capturing) return;
        final int scale = Prefs.scale(this);
        final int frames = Prefs.frames(this);
        final float sharpen = Prefs.sharpen(this);
        final int sample = effectiveSampleWidth(scale);

        String name = getString(R.string.mode_super) + " x" + scale;
        btnModeSuper.setText(name);
        capturing = true;
        cancelRequested = false;
        setStatus(getString(R.string.status_super));
        btnShutter.setEnabled(false);
        btnCancel.setVisibility(View.VISIBLE);
        btnCancel.setEnabled(true);

        stabilityForSuper = true;
        registerSensorIfNeeded();

        final List<File> files = new ArrayList<>();
        final File dir = new File(getCacheDir(), "super");
        if (!dir.exists()) dir.mkdirs();

        captureSeries(0, frames, dir, files, scale, sharpen, sample);
    }

    /** 连拍第 idx 帧；单帧失败时最多重试 maxRetry 次（部分机型快速连拍偶发暂时失败）。 */
    private void captureSeries(final int idx, final int total, final File dir,
                               final List<File> files, final int scale,
                               final float sharpen, final int sample) {
        captureSeriesTry(idx, total, dir, files, scale, sharpen, sample, 2);
    }

    private void captureSeriesTry(final int idx, final int total, final File dir,
                                  final List<File> files, final int scale,
                                  final float sharpen, final int sample,
                                  final int retryLeft) {
        if (cancelRequested) { endSuperCanceled(); return; }
        if (idx >= total) {
            btnCancel.setVisibility(View.GONE);
            setStatus(getString(R.string.status_processing));
            processSuper(files, scale, sharpen, sample);
            return;
        }
        final File f = new File(dir, "f_" + idx + "_" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions opts = new ImageCapture.OutputFileOptions.Builder(f).build();
        imageCapture.takePicture(opts, mainExecutor(),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                        files.add(f);
                        // 抖动过大时提示用户握稳，提升配准成功率
                        setStatus(currentShake() > SHAKE_WARN_G
                                ? getString(R.string.status_super_shaky, files.size(), total)
                                : "捕捉中 " + files.size() + "/" + total);
                        // 60ms 间隔，提高取帧速度
                        mainHandler.postDelayed(() -> captureSeries(idx + 1, total, dir, files,
                                scale, sharpen, sample), 60);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        if (cancelRequested) { endSuperCanceled(); return; }
                        if (retryLeft > 0) {
                            // 短暂退避后重试当前帧
                            mainHandler.postDelayed(() -> captureSeriesTry(idx, total, dir, files,
                                    scale, sharpen, sample, retryLeft - 1), 150);
                        } else {
                            finishSuperFail();
                        }
                    }
                });
    }

    private void cancelSuper() {
        if (!capturing) return;
        cancelRequested = true;
        btnCancel.setEnabled(false);
        setStatus("正在取消…");
    }

    private void endSuperCanceled() {
        finishSuperState();
        setStatus("");
        Toast.makeText(this, "已取消连拍", Toast.LENGTH_SHORT).show();
    }

    /** 复位界面控制状态（无 Toast，供成功/取消共用）。 */
    private void finishSuperState() {
        capturing = false;
        cancelRequested = false;
        stabilityForSuper = false;
        releaseAccelIfIdle();
        btnShutter.setEnabled(true);
        btnCancel.setVisibility(View.GONE);
    }

    private void processSuper(List<File> files, int scale, float sharpen, int sample) {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.execute(() -> {
            Bitmap orig = null;
            try {
                // 首帧为参考帧，同时作为对比用的原图
                orig = decodeSampled(files.get(0).getAbsolutePath(), sample);
                if (orig == null) { runOnUiThread(this::finishSuperFail); return; }

                SuperResolution.Stream st = SuperResolution.beginStream(
                        orig.getWidth(), orig.getHeight(), scale);
                SuperResolution.addFrame(st, orig);

                // 逐帧解码→累加→立即回收，控制峰值内存
                int used = 1;
                for (int i = 1; i < files.size() && used < 32; i++) {
                    Bitmap b = decodeSampled(files.get(i).getAbsolutePath(), sample);
                    if (b == null) continue;
                    try {
                        SuperResolution.addFrame(st, b);
                        used++;
                    } finally {
                        if (b != orig) b.recycle();
                    }
                }

                final Bitmap result = SuperResolution.finishStream(st, sharpen);
                final String t = "多帧超分 x" + scale + "（" + used + " 帧）";
                final Bitmap fResult = result;
                final Bitmap fOrig = orig;
                runOnUiThread(() -> {
                    if (fResult != null) {
                        showResult(fResult, fOrig, t);
                    } else {
                        finishSuperFail();
                    }
                });
            } catch (Throwable t) {
                t.printStackTrace();
                runOnUiThread(this::finishSuperFail);
            } finally {
                exec.shutdown();
            }
        });
    }

    private void finishSuperFail() {
        finishSuperState();
        setStatus("");
        Toast.makeText(this, "超分合成失败，请重试", Toast.LENGTH_SHORT).show();
    }

    /** 根据堆内存等级、超分倍数与画质档位，计算安全的采样宽度，防止低端机 OOM。 */
    private int effectiveSampleWidth(int scale) {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        int heapMb = 64;
        if (am != null) heapMb = am.getMemoryClass();
        return Prefs.safeSampleWidth(heapMb, scale, qualityBaseWidth());
    }

    /** 画质档位 → 基础采样宽度：0 流畅 / 1 均衡 / 2 清晰。 */
    private int qualityBaseWidth() {
        switch (Prefs.quality(this)) {
            case 0: return 960;
            case 2: return 1440;
            default: return SAMPLE_WIDTH;
        }
    }

    private void showResult(Bitmap result, Bitmap orig, String title) {
        finishSuperState();
        setStatus("");
        if (result == null) { Toast.makeText(this, "无结果", Toast.LENGTH_SHORT).show(); return; }
        Intent intent = new Intent(this, ResultActivity.class);
        intent.putExtra("title", title);
        try {
            File dir = new File(getCacheDir(), "result");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "super.png");
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            result.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            out.close();
            intent.putExtra("path", f.getAbsolutePath());

            if (orig != null) {
                File of = new File(dir, "orig.png");
                java.io.FileOutputStream oo = new java.io.FileOutputStream(of);
                orig.compress(Bitmap.CompressFormat.PNG, 100, oo);
                oo.flush();
                oo.close();
                intent.putExtra("origPath", of.getAbsolutePath());
            }
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "保存临时结果失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void setStatus(String s) {
        tvStatus.setText(s);
        tvStatus.setVisibility(s == null || s.isEmpty() ? View.INVISIBLE : View.VISIBLE);
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

    // ---------- 最近缩略角标 ----------

    /** 查询 Pictures/FocusUpscale 下最新一张本应用保存的照片，显示为左下角缩略角标。 */
    private void updateRecentThumb() {
        if (imgRecent == null || imgRecentThumb == null) return;
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            imgRecent.setVisibility(View.GONE);
            return;
        }
        String[] proj = { MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA };
        String selection = MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?";
        String[] selArgs = { "focus_upscale_%" };
        String path = null;
        try (Cursor c = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                proj, selection, selArgs,
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c != null && c.moveToFirst()) path = c.getString(1);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (path == null || path.isEmpty()) {
            imgRecent.setVisibility(View.GONE);
            return;
        }
        final String fp = path;
        // 解码移后台线程，避免冷启动/恢复时主线程读盘卡顿
        backgroundIo.execute(() -> {
            final Bitmap b = decodeSampled(fp, 160);
            mainHandler.post(() -> {
                if (b != null) {
                    imgRecentThumb.setImageBitmap(b);
                    imgRecent.setVisibility(View.VISIBLE);
                } else {
                    imgRecent.setVisibility(View.GONE);
                }
            });
        });
    }

    // ---------- 水平仪传感器 ----------

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float gx = event.values[0];
            float gy = event.values[1];
            float tilt = (float) Math.toDegrees(Math.atan2(gx, -gy));
            aidOverlay.setTilt(tilt);
            // 采集加速度幅值到环形缓冲，供超分连拍抖动监测使用
            float gz = event.values[2];
            shakeSamples[shakeIdx] = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
            shakeIdx = (shakeIdx + 1) % SHAKE_WIN;
            if (shakeCnt < SHAKE_WIN) shakeCnt++;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void registerSensorIfNeeded() {
        if (sensorRegistered) return;
        // 水平仪开启 或 超分连拍进行中 时，都需要加速度计
        if ((Prefs.levelEnabled(this) || stabilityForSuper) && accelerometer != null) {
            // 连拍抖动监测需要较高采样率，统一用 GAME 档
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            sensorRegistered = true;
            shakeCnt = 0;
        }
    }

    /** 连拍结束后：若水平仪也已关闭，及时释放加速度计以省电。 */
    private void releaseAccelIfIdle() {
        if (sensorRegistered && !stabilityForSuper && !Prefs.levelEnabled(this)) {
            sensorManager.unregisterListener(this);
            sensorRegistered = false;
        }
    }

    /** 最近抖动幅度（加速度幅值相对重力的 RMS 偏差，单位 g）；样本不足时返回 0。 */
    private float currentShake() {
        int n = Math.min(shakeCnt, SHAKE_WIN);
        if (n < 8) return 0f;
        float total = 0f;
        for (int i = 0; i < n; i++) {
            int idx = (shakeIdx - 1 - i + SHAKE_WIN) % SHAKE_WIN;
            float d = shakeSamples[idx] - 9.8f;
            total += d * d;
        }
        return (float) Math.sqrt(total / n);
    }

    private void unregisterSensor() {
        if (sensorRegistered) {
            sensorManager.unregisterListener(this);
            sensorRegistered = false;
        }
    }

    // ---------- LifecycleOwner ----------

    @Override
    public @NonNull Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    protected void onStart() {
        super.onStart();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);
    }

    @Override
    protected void onResume() {
        super.onResume();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
        // 从设置页返回后刷新取景辅助与参数
        aidOverlay.enable(Prefs.gridEnabled(this), Prefs.levelEnabled(this));
        registerSensorIfNeeded();
        updateRecentThumb();
    }

    @Override
    protected void onPause() {
        unregisterSensor();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
        super.onPause();
    }

    @Override
    protected void onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        backgroundIo.shutdown();
        super.onDestroy();
    }
}