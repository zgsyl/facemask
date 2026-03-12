package com.example.facemask;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class GraphicOverlay extends View {
    private final Object lock = new Object();
    private int imageWidth;
    private int imageHeight;
    private boolean isImageFlipped;
    private final List<Graphic> graphics = new ArrayList<>();

    public abstract static class Graphic {
        private GraphicOverlay overlay;
        public Graphic(GraphicOverlay overlay) { this.overlay = overlay; }
        public abstract void draw(Canvas canvas);

        public float scale(float imagePixel) {
            return imagePixel * overlay.getScaleFactor();
        }
        public float translateX(float x) {
            if (overlay.isImageFlipped) {
                return overlay.getWidth() - (scale(x) - overlay.postScaleWidthOffset());
            } else {
                return scale(x) - overlay.postScaleWidthOffset();
            }
        }
        public float translateY(float y) {
            return scale(y) - overlay.postScaleHeightOffset();
        }
        public void postInvalidate() { overlay.postInvalidate(); }
    }

    public GraphicOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void clear() {
        synchronized (lock) {
            graphics.clear();
        }
        postInvalidate();
    }

    public void add(Graphic graphic) {
        synchronized (lock) {
            graphics.add(graphic);
        }
        postInvalidate();
    }

    public void setImageSourceInfo(int imageWidth, int imageHeight, boolean isFlipped) {
        synchronized (lock) {
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.isImageFlipped = isFlipped;
        }
        postInvalidate();
    }

    private float getScaleFactor() {
        if (imageWidth == 0 || imageHeight == 0) return 1f;
        float scaleX = (float) getWidth() / (float) imageWidth;
        float scaleY = (float) getHeight() / (float) imageHeight;
        return Math.max(scaleX, scaleY); // CenterCrop scaling
    }

    private float postScaleWidthOffset() {
        return (imageWidth * getScaleFactor() - getWidth()) / 2f;
    }

    private float postScaleHeightOffset() {
        return (imageHeight * getScaleFactor() - getHeight()) / 2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        synchronized (lock) {
            if (imageWidth != 0 && imageHeight != 0) {
                for (Graphic graphic : graphics) {
                    graphic.draw(canvas);
                }
            }
        }
    }
}
