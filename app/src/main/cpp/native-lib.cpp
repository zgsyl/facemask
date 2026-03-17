#include <jni.h>
#include <string>
#include <cmath>
#include <algorithm>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include "ncnn/net.h"

#define TAG "FaceMaskJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static ncnn::Net maskNet;
static bool isNetReady = false;

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_facemask_MainActivity_initNCNN(JNIEnv *env, jobject thiz, jobject assetManager) {
    if (isNetReady) return JNI_TRUE;

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);

    // --- 更改点 1: 优化性能设置 ---
    // pnnx 转换的模型对 fp16 支持非常好，开启后速度提升一倍，且不影响精度
    // --- 强制高精度模式 ---
    maskNet.opt.use_vulkan_compute = false;
    maskNet.opt.num_threads = 4;
    maskNet.opt.use_fp16_packed = false;
    maskNet.opt.use_fp16_storage = false;
    maskNet.opt.use_fp16_arithmetic = false;

    int retParam = maskNet.load_param(mgr, "mask_model_sim.ncnn.param");
    int retBin = maskNet.load_model(mgr, "mask_model_sim.ncnn.bin");

    LOGD("NCNN Init status -> param:%d, bin:%d", retParam, retBin);

    if (retParam == 0 && retBin == 0) {
        isNetReady = true;
        return JNI_TRUE;
    } else {
        LOGE("Failed to load NCNN model. Please check assets filenames!");
        isNetReady = false;
        return JNI_FALSE;
    }
}

// 辅助函数：手动旋转和镜像
static void rotate_rgb_manual(const unsigned char* src, int w, int h, unsigned char* dst, int& out_w, int& out_h, int type, bool flip) {
    int tw, th;
    unsigned char* tmp = new unsigned char[w * h * 3];
    
    // 1. 旋转
    if (type == 1) { // 90 CW
        tw = h; th = w;
        for (int i = 0; i < h; i++) {
            for (int j = 0; j < w; j++) {
                int si = (i * w + j) * 3;
                int di = (j * h + (h - 1 - i)) * 3;
                tmp[di] = src[si]; tmp[di+1] = src[si+1]; tmp[di+2] = src[si+2];
            }
        }
    } else if (type == 2) { // 180
        tw = w; th = h;
        for (int i = 0; i < h; i++) {
            for (int j = 0; j < w; j++) {
                int si = (i * w + j) * 3;
                int di = ((h - 1 - i) * w + (w - 1 - j)) * 3;
                tmp[di] = src[si]; tmp[di+1] = src[si+1]; tmp[di+2] = src[si+2];
            }
        }
    } else if (type == 3) { // 270 CW (90 CCW)
        tw = h; th = w;
        for (int i = 0; i < h; i++) {
            for (int j = 0; j < w; j++) {
                int si = (i * w + j) * 3;
                int di = ((w - 1 - j) * h + i) * 3;
                tmp[di] = src[si]; tmp[di+1] = src[si+1]; tmp[di+2] = src[si+2];
            }
        }
    } else { // 0
        tw = w; th = h;
        memcpy(tmp, src, (size_t)w * h * 3);
    }

    // 2. 镜像
    if (flip) {
        out_w = tw; out_h = th;
        for (int i = 0; i < th; i++) {
            for (int j = 0; j < tw; j++) {
                int si = (i * tw + j) * 3;
                int di = (i * tw + (tw - 1 - j)) * 3;
                dst[di] = tmp[si]; dst[di+1] = tmp[si+1]; dst[di+2] = tmp[si+2];
            }
        }
    } else {
        out_w = tw; out_h = th;
        memcpy(dst, tmp, (size_t)tw * th * 3);
    }
    delete[] tmp;
}

extern "C"
JNIEXPORT jfloat JNICALL
Java_com_example_facemask_MainActivity_predictMaskStatus(JNIEnv *env, jobject thiz,
                                                         jbyteArray yuv420sp,
                                                         jint width, jint height, jint rotation,
                                                         jint left, jint top, jint right, jint bottom) {
    if (!isNetReady) return -1.0f;

    jbyte* yuv = (jbyte*)env->GetByteArrayElements(yuv420sp, 0);
    unsigned char* pixel_buffer = new unsigned char[width * height * 3];
    ncnn::yuv420sp2rgb((const unsigned char*)yuv, width, height, pixel_buffer);
    env->ReleaseByteArrayElements(yuv420sp, yuv, JNI_ABORT);

    ncnn::Mat full_frame = ncnn::Mat::from_pixels(pixel_buffer, ncnn::Mat::PIXEL_RGB, width, height);
    delete[] pixel_buffer;

    // 裁剪 (Sensor 空间)
    int c_left = std::max(0, (int)left), c_top = std::max(0, (int)top);
    int c_right = std::min(width, (int)right), c_bottom = std::min(height, (int)bottom);
    ncnn::Mat face_raw;
    ncnn::copy_cut_border(full_frame, face_raw, c_top, height - c_bottom, c_left, width - c_right);

    unsigned char* face_pixels = new unsigned char[face_raw.w * face_raw.h * 3];
    face_raw.to_pixels(face_pixels, ncnn::Mat::PIXEL_RGB);

    // --- 精简测试：仅测试红蓝通道和截取是否生效 ---
    int out_w, out_h;
    unsigned char* out_p = new unsigned char[face_raw.w * face_raw.h * 3];
    // 使用 R270-F0 (竖屏标准) 扶正人脸
    rotate_rgb_manual(face_pixels, face_raw.w, face_raw.h, out_p, out_w, out_h, 3, false);

    // --- 执行缩放 (先将人脸缩放到模型期望的大小) ---
    ncnn::Mat mat_raw = ncnn::Mat::from_pixels(out_p, ncnn::Mat::PIXEL_RGB, out_w, out_h);
    delete[] out_p;
    delete[] face_pixels;

    ncnn::Mat mat_resized;
    ncnn::resize_bilinear(mat_raw, mat_resized, 128, 128);

    // --- 在 128x128 空间执行 3x3 均值滤波 ---
    // 这样做的好处：无论人脸远近，模糊的强度相对于模型来说都是恒定的（解决距离偏差问题）
    unsigned char* resized_p = new unsigned char[128 * 128 * 3];
    mat_resized.to_pixels(resized_p, ncnn::Mat::PIXEL_RGB);
    
    unsigned char* smoothed_p = new unsigned char[128 * 128 * 3];
    for (int y = 0; y < 128; y++) {
        for (int x = 0; x < 128; x++) {
            for (int c = 0; c < 3; c++) {
                int sum = 0, count = 0;
                for (int ky = -1; ky <= 1; ky++) {
                    for (int kx = -1; kx <= 1; kx++) {
                        int iy = y + ky, ix = x + kx;
                        if (iy >= 0 && iy < 128 && ix >= 0 && ix < 128) {
                            sum += resized_p[(iy * 128 + ix) * 3 + c];
                            count++;
                        }
                    }
                }
                smoothed_p[(y * 128 + x) * 3 + c] = (unsigned char)(sum / count);
            }
        }
    }
    delete[] resized_p;

    ncnn::Mat in_norm = ncnn::Mat::from_pixels(smoothed_p, ncnn::Mat::PIXEL_RGB, 128, 128);
    delete[] smoothed_p;

    const float mean_vals[3] = {123.675f, 116.28f, 103.53f}; 
    const float norm_vals[3] = {0.01712475f, 0.017507f, 0.017429f};
    in_norm.substract_mean_normalize(mean_vals, norm_vals);

    // --- 执行最终推理 ---
    ncnn::Extractor ex = maskNet.create_extractor();
    ex.set_light_mode(true);
    ex.input("in0", in_norm);
    ncnn::Mat out;
    ex.extract("out0", out);

    float score_mask = out[0];
    float score_no_mask = out[1];
    float max_s = std::max(score_mask, score_no_mask);
    float prob = std::exp(score_mask - max_s) / (std::exp(score_mask - max_s) + std::exp(score_no_mask - max_s));

    LOGD("FaceMask Result -> Prob: %.4f", prob);

    return (jfloat)prob;
}

extern "C"
JNIEXPORT jfloat JNICALL
Java_com_example_facemask_MainActivity_predictMaskFromBitmap(JNIEnv *env, jobject thiz,
                                                             jobject bitmap) {
    if (!isNetReady) return -1.0f;

    // 1. Android Bitmap -> ncnn Mat
    ncnn::Mat in_raw = ncnn::Mat::from_android_bitmap(env, bitmap, ncnn::Mat::PIXEL_RGB);

    // 2. Resize to 128x128
    int target_w = 128;
    int target_h = 128;
    ncnn::Mat in_norm;
    ncnn::resize_bilinear(in_raw, in_norm, target_w, target_h);

    // 3. 归一化 (RGB 顺序: R=123.675, G=116.28, B=103.53)
    const float mean_vals[3] = {123.675f, 116.28f, 103.53f}; 
    const float norm_vals[3] = {0.01712475f, 0.017507f, 0.017429f};
    in_norm.substract_mean_normalize(mean_vals, norm_vals);

    // 4. 推理
    ncnn::Extractor ex = maskNet.create_extractor();
    ex.set_light_mode(true);
    ex.input("in0", in_norm);

    ncnn::Mat out;
    int ret = ex.extract("out0", out);

    if (ret != 0 || out.empty()) {
        LOGE("Static Test: Extract failed!");
        return -1.0f;
    }

    // 5. 结果分析 (Index 0: masked, Index 1: unmasked)
    float score_mask = out[0];
    float score_no_mask = out[1];

    float max_score = std::max(score_mask, score_no_mask);
    float exp_mask = std::exp(score_mask - max_score);
    float exp_nomask = std::exp(score_no_mask - max_score);
    float mask_probability = exp_mask / (exp_mask + exp_nomask);

    LOGD("### STATIC TEST RESULT ###");
    LOGD("Logits: Masked(0)=%.4f, NoMask(1)=%.4f | Prob: %.4f", 
         score_mask, score_no_mask, mask_probability);

    return (jfloat)mask_probability;
}
