package com.example.facemask;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import com.google.mlkit.vision.face.Face;

public class FaceGraphic extends GraphicOverlay.Graphic {
    private final Paint maskPaint;
    private final Paint noMaskPaint;
    private final Paint textPaint;
    private volatile Face face;
    private float maskScore = -1.0f;

    public FaceGraphic(GraphicOverlay overlay, Face face) {
        super(overlay);
        this.face = face;
        
        maskPaint = new Paint();
        maskPaint.setColor(Color.RED); // Masked -> Red
        maskPaint.setStyle(Paint.Style.STROKE);
        maskPaint.setStrokeWidth(8.0f);

        noMaskPaint = new Paint();
        noMaskPaint.setColor(Color.GREEN); // Unmasked -> Green
        noMaskPaint.setStyle(Paint.Style.STROKE);
        noMaskPaint.setStrokeWidth(8.0f);

        textPaint = new Paint();
        textPaint.setColor(Color.YELLOW);
        textPaint.setTextSize(50.0f);
    }

    public void setMaskScore(float score) {
        this.maskScore = score;
    }

    @Override
    public void draw(Canvas canvas) {
        Face face = this.face;
        if (face == null) return;

        float left = translateX(face.getBoundingBox().left);
        float top = translateY(face.getBoundingBox().top);
        float right = translateX(face.getBoundingBox().right);
        float bottom = translateY(face.getBoundingBox().bottom);

        // Score logic (fallback to white if inference failed)
        Paint boxPaint;
        String label;
        if (maskScore < 0) {
             boxPaint = maskPaint;
             boxPaint.setColor(Color.WHITE);
             label = "Detecting...";
        } else if (maskScore >= 0.5f) { // Masked
             boxPaint = maskPaint;
             label = String.format("Mask: %.1f%%", maskScore * 100);
        } else { // No Mask
             boxPaint = noMaskPaint;
             label = String.format("No Mask: %.1f%%", (1 - maskScore) * 100);
        }

        canvas.drawRect(left, top, right, bottom, boxPaint);
        canvas.drawText(label, left, top - 20, textPaint);
    }
}
