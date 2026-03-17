package com.example.facemask;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "FaceMaskDetector";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = {Manifest.permission.CAMERA};

    private PreviewView viewFinder;
    private GraphicOverlay graphicOverlay;
    private ExecutorService cameraExecutor;
    private FaceDetector detector;
    private static boolean isNetReady = false;
    private int savedImagesCount = 0;
    private static final int MAX_DEBUG_IMAGES = 10; // 增加到10张，方便测试

    static {
        System.loadLibrary("facemask");
    }

    private void runStaticTest() {
        new Thread(() -> {
            try {
                String[] testFiles = {"mask.jpg", "unmask.jpg"};
                for (String fileName : testFiles) {
                    InputStream is = getAssets().open(fileName);
                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                    is.close();

                    if (bitmap != null) {
                        float score = predictMaskFromBitmap(bitmap);
                        Log.d(TAG, "Static Test [" + fileName + "] -> Score: " + score + 
                              (score >= 0.5 ? " (Masked)" : " (No Mask)"));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Static test failed", e);
            }
        }).start();
    }

    public native String stringFromJNI();
    public native boolean initNCNN(AssetManager mgr);
    public native float predictMaskStatus(byte[] yuv420sp, int width, int height, int rotation,
                                          int left, int top, int right, int bottom);
    public native float predictMaskFromBitmap(Bitmap bitmap);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (initNCNN(getAssets())) {
            Log.d(TAG, "NCNN Init Success");
            isNetReady = true;
            runStaticTest();
        } else {
            Log.e(TAG, "NCNN Init Failed");
        }

        viewFinder = findViewById(R.id.viewFinder);
        graphicOverlay = findViewById(R.id.graphicOverlay);

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build();
        detector = FaceDetection.getClient(options);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera initial failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeImage(ImageProxy imageProxy) {
        @SuppressWarnings("UnsafeOptInUsageError")
        Image mediaImage = imageProxy.getImage();
        if (mediaImage != null && isNetReady) {
            InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
            detector.process(image)
                    .addOnSuccessListener(faces -> {
                        graphicOverlay.clear();
                        
                        // 重要：同步图像尺寸到 Overlay，否则框的位置会缩放到 0
                        int rotation = imageProxy.getImageInfo().getRotationDegrees();
                        if (rotation == 90 || rotation == 270) {
                            graphicOverlay.setImageSourceInfo(mediaImage.getHeight(), mediaImage.getWidth(), true);
                        } else {
                            graphicOverlay.setImageSourceInfo(mediaImage.getWidth(), mediaImage.getHeight(), true);
                        }

                        for (Face face : faces) {
                            Rect boundingBox = face.getBoundingBox();
                            int imWidth = mediaImage.getWidth();
                            int imHeight = mediaImage.getHeight();

                            // 1. Convert Image to NV21
                            byte[] nv21 = yuv420888ToNv21(mediaImage);

                            // 2. Square Crop logic - 使用正方形裁剪防止拉伸变形，匹配训练集分布
                            int actualWidth = (rotation == 90 || rotation == 270) ? imHeight : imWidth;
                            int actualHeight = (rotation == 90 || rotation == 270) ? imWidth : imHeight;

                            int centerX = boundingBox.centerX();
                            int centerY = boundingBox.centerY();
                            int size = Math.max(boundingBox.width(), boundingBox.height());
                            int halfSize = size / 2;

                            Rect cropRect = new Rect(
                                    Math.max(0, centerX - halfSize),
                                    Math.max(0, centerY - halfSize),
                                    Math.min(actualWidth, centerX + halfSize),
                                    Math.min(actualHeight, centerY + halfSize)
                            );

                            // [深度对齐] 将竖屏坐标 (480x640) 转换回传感器的原始横屏坐标 (640x480)
                            // 针对 270 度旋转的前置摄像头：
                            // SensorX = imWidth - ScreenY
                            // SensorY = ScreenX
                            Rect sensorRect;
                            if (rotation == 270) {
                                sensorRect = new Rect(
                                        imWidth - cropRect.bottom,
                                        cropRect.left,
                                        imWidth - cropRect.top,
                                        cropRect.right
                                );
                            } else if (rotation == 90) {
                                sensorRect = new Rect(
                                        cropRect.top,
                                        imHeight - cropRect.right,
                                        cropRect.bottom,
                                        imHeight - cropRect.left
                                );
                            } else {
                                sensorRect = new Rect(cropRect);
                            }
                            
                            // 边界兜底检查
                            sensorRect.left = Math.max(0, sensorRect.left);
                            sensorRect.top = Math.max(0, sensorRect.top);
                            sensorRect.right = Math.min(imWidth, sensorRect.right);
                            sensorRect.bottom = Math.min(imHeight, sensorRect.bottom);

                            // 3. Send FULL frame and sensor-aligned crop coordinates to JNI
                            float maskScore = predictMaskStatus(nv21, imWidth, imHeight, rotation,
                                    sensorRect.left, sensorRect.top, sensorRect.right, sensorRect.bottom);

                            // 4. Update UI
                            FaceGraphic faceGraphic = new FaceGraphic(graphicOverlay, face);
                            faceGraphic.setMaskScore(maskScore);
                            graphicOverlay.add(faceGraphic);

                            // 调试：保存前几个正方形人脸截图，用于验证“正方形裁剪”和“对齐”
                            if (savedImagesCount < MAX_DEBUG_IMAGES) {
                                saveFaceImage(nv21, imWidth, imHeight, sensorRect);
                                savedImagesCount++;
                            }
                        }
                    })
                    .addOnFailureListener(e -> Log.e(TAG, "Face detection fail", e))
                    .addOnCompleteListener(task -> imageProxy.close());
        } else {
            imageProxy.close();
        }
    }

    private byte[] yuv420888ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane yPlane = image.getPlanes()[0];
        Image.Plane uPlane = image.getPlanes()[1];
        Image.Plane vPlane = image.getPlanes()[2];

        ByteBuffer yBuffer = yPlane.getBuffer();
        ByteBuffer uBuffer = uPlane.getBuffer();
        ByteBuffer vBuffer = vPlane.getBuffer();

        yBuffer.rewind(); uBuffer.rewind(); vBuffer.rewind();

        byte[] nv21 = new byte[width * height * 3 / 2];
        int yStride = yPlane.getRowStride();
        if (yStride == width) {
            yBuffer.get(nv21, 0, width * height);
        } else {
            for (int row = 0; row < height; row++) {
                yBuffer.position(row * yStride);
                yBuffer.get(nv21, row * width, width);
            }
        }

        int vStride = vPlane.getRowStride();
        int uStride = uPlane.getRowStride();
        int vPixelStride = vPlane.getPixelStride();
        int uPixelStride = uPlane.getPixelStride();

        if (vPixelStride == 2 && vStride == uStride) {
            vBuffer.get(nv21, width * height, vBuffer.remaining());
        } else {
            int nvIndex = width * height;
            for (int row = 0; row < height / 2; row++) {
                for (int col = 0; col < width / 2; col++) {
                    nv21[nvIndex++] = vBuffer.get(row * vStride + col * vPixelStride);
                    nv21[nvIndex++] = uBuffer.get(row * uStride + col * uPixelStride);
                }
            }
        }
        return nv21;
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) {
            startCamera();
        }
    }

    // 辅助测试：保存人脸截图到 SD 卡
    private void saveFaceImage(byte[] nv21, int width, int height, Rect rect) {
        try {
            java.io.File dir = getExternalFilesDir("faces");
            if (dir != null && !dir.exists()) dir.mkdirs();
            String fileName = "test_face_" + System.currentTimeMillis() + ".jpg";
            java.io.File file = new java.io.File(dir, fileName);
            android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null);
            
            // 为了让您在文件夹里看到的照片和模型看到的一样“糊”，我们在保存前也做一个简单的 5x5 模糊
            android.graphics.Bitmap bitmap = convertYuvToBitmap(yuvImage, rect);
            if (bitmap != null) {
                android.graphics.Bitmap blurred = blurBitmap(bitmap, 3);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                    blurred.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, fos);
                }
                Log.d("FaceMaskTest", "Saved Blurred Diagnostic Image: " + file.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e("FaceMaskTest", "Failed to save image", e);
        }
    }

    // 简单的 Bitmap 模糊函数 (Box Blur)
    private android.graphics.Bitmap blurBitmap(android.graphics.Bitmap b, int radius) {
        android.graphics.Bitmap out = b.copy(b.getConfig(), true);
        int w = b.getWidth();
        int h = b.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = 0, g = 0, bl = 0, count = 0;
                for (int dy = -radius/2; dy <= radius/2; dy++) {
                    for (int dx = -radius/2; dx <= radius/2; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                            int p = b.getPixel(nx, ny);
                            r += android.graphics.Color.red(p);
                            g += android.graphics.Color.green(p);
                            bl += android.graphics.Color.blue(p);
                            count++;
                        }
                    }
                }
                out.setPixel(x, y, android.graphics.Color.rgb(r/count, g/count, bl/count));
            }
        }
        return out;
    }

    private android.graphics.Bitmap convertYuvToBitmap(android.graphics.YuvImage yuvImage, Rect rect) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            yuvImage.compressToJpeg(rect, 100, out);
            byte[] imageBytes = out.toByteArray();
            return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}
