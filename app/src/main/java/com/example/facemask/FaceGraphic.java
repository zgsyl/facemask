package com.example.facemask;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import com.google.mlkit.vision.face.Face;

public class FaceGraphic extends GraphicOverlay.Graphic {
    private final Paint boxPaint;
    private volatile Face face;

    public FaceGraphic(GraphicOverlay overlay, Face face) {
        super(overlay);
        this.face = face;
        
        boxPaint = new Paint();
        boxPaint.setColor(Color.WHITE); // Default ML Kit box color before Mask calculation
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(8.0f);
    }

    public void updateFace(Face face) {
        this.face = face;
        postInvalidate();
    }

    @Override
    public void draw(Canvas canvas) {
        Face face = this.face;
        if (face == null) return;

        // Map ML Kit raw bounding box to Canvas screen coordinates
        float left = translateX(face.getBoundingBox().left);
        float top = translateY(face.getBoundingBox().top);
        float right = translateX(face.getBoundingBox().right);
        float bottom = translateY(face.getBoundingBox().bottom);

        canvas.drawRect(left, top, right, bottom, boxPaint);
    }
}
