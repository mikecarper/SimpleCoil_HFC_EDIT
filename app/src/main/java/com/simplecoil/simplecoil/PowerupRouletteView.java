package com.simplecoil.simplecoil;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Original arcade-style rotating mystery box and synthesized roulette chime. */
public final class PowerupRouletteView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator spin;
    private ToneGenerator tone;
    private float position;
    private int lastTick = -1;
    private String reward = "";
    private boolean revealed;

    public PowerupRouletteView(Context context, AttributeSet attrs) { super(context, attrs); }

    public void showReward(String name, Runnable announce) {
        cancel();
        reward = name;
        revealed = false;
        position = 0;
        lastTick = -1;
        setVisibility(VISIBLE);
        setContentDescription(getContext().getString(R.string.powerup_roulette_spinning));
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 85); }
        catch (RuntimeException ignored) { tone = null; }
        spin = ValueAnimator.ofFloat(0, 1);
        spin.setDuration(2200);
        spin.setInterpolator(new DecelerateInterpolator(1.5f));
        spin.addUpdateListener(animation -> {
            position = (float) animation.getAnimatedValue();
            int tick = (int) (position * 18);
            if (tick != lastTick) {
                lastTick = tick;
                if (tone != null) tone.startTone(ToneGenerator.TONE_DTMF_1 + tick % 8, 65);
            }
            invalidate();
        });
        spin.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (spin != animation) return;
                spin = null;
                revealed = true;
                setContentDescription(reward);
                if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_ACK, 450);
                invalidate();
                postDelayed(hideReward, 2200);
                // Announcing can start the next queued spin. Schedule this
                // hide first so that next spin's cancel() removes the old one.
                announce.run();
            }
        });
        spin.start();
    }

    private final Runnable hideReward = this::cancel;

    public void cancel() {
        removeCallbacks(hideReward);
        ValueAnimator previous = spin;
        spin = null;
        if (previous != null) previous.cancel();
        if (tone != null) { tone.release(); tone = null; }
        setVisibility(GONE);
    }

    @Override protected void onDetachedFromWindow() { cancel(); super.onDetachedFromWindow(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth(), height = getHeight();
        paint.setColor(0xee101820);
        canvas.drawRoundRect(0, 0, width, height, 14, 14, paint);
        float size = Math.min(width * .65f, height * .6f);
        float phase = position * 8 * (float) Math.PI;
        float faceWidth = revealed ? size : Math.max(size * .12f, size * Math.abs((float) Math.cos(phase)));
        paint.setColor(Color.HSVToColor(new float[]{revealed ? 45 : position * 1000 % 360, .8f, 1}));
        canvas.save();
        canvas.rotate(revealed ? 0 : 12 * (float) Math.sin(phase), width / 2, height * .4f);
        canvas.drawRoundRect((width - faceWidth) / 2, height * .1f,
                (width + faceWidth) / 2, height * .1f + size, 12, 12, paint);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(size * .65f);
        canvas.drawText("?", width / 2, height * .1f + size * .75f, paint);
        canvas.restore();
        paint.setTextSize(Math.min(18 * getResources().getDisplayMetrics().scaledDensity, width / 11));
        String label = revealed ? reward : getContext().getString(R.string.powerup_roulette_spinning);
        // Wrap short reward names within the compact HUD window.
        String[] words = label.split(" ");
        String line = "";
        float y = height * .81f;
        for (String word : words) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && paint.measureText(next) > width - 12) {
                canvas.drawText(line, width / 2, y, paint);
                y += paint.getTextSize() * 1.1f;
                line = word;
            } else line = next;
        }
        canvas.drawText(line, width / 2, y, paint);
    }
}
