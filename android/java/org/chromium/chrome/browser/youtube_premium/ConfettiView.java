package org.chromium.chrome.browser.youtube_premium;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ConfettiView extends View {
    private static class Particle {
        float x, y;
        float velocityX, velocityY;
        int color;
        float size;
        float rotation;
        float rotationSpeed;

        public Particle(float x, float y, int color, float size) {
            this.x = x;
            this.y = y;
            this.color = color;
            this.size = size;
            Random random = new Random();
            this.velocityX = (random.nextFloat() - 0.5f) * 20; // -10 to 10
            this.velocityY = (random.nextFloat() * 10) + 10;   // 10 to 20 (falling down)
            this.rotation = random.nextFloat() * 360;
            this.rotationSpeed = (random.nextFloat() - 0.5f) * 10;
        }
    }

    private final List<Particle> particles = new ArrayList<>();
    private final Paint paint = new Paint();
    private boolean isAnimating = false;
    private final int[] COLORS = {
        Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA,
        0xFFFFA500 // Orange
    };

    public ConfettiView(Context context) {
        super(context);
        init();
    }

    public ConfettiView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
    }

    public void startConfetti() {
        if (getWidth() == 0 || getHeight() == 0) return;
        
        particles.clear();
        Random random = new Random();
        for (int i = 0; i < 100; i++) {
            particles.add(new Particle(
                random.nextFloat() * getWidth(),
                -random.nextFloat() * getHeight() * 0.5f, // Start above screen
                COLORS[random.nextInt(COLORS.length)],
                10 + random.nextFloat() * 20
            ));
        }
        isAnimating = true;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isAnimating) return;

        boolean active = false;
        for (Particle p : particles) {
            if (p.y < getHeight()) {
                active = true;
                p.x += p.velocityX;
                p.y += p.velocityY;
                p.rotation += p.rotationSpeed;
                p.velocityY += 0.25f; // Gravity

                paint.setColor(p.color);
                canvas.save();
                canvas.rotate(p.rotation, p.x + p.size / 2, p.y + p.size / 2);
                canvas.drawRect(p.x, p.y, p.x + p.size, p.y + p.size, paint);
                canvas.restore();
            }
        }

        if (active) {
            invalidate();
        } else {
            isAnimating = false;
        }
    }
}
