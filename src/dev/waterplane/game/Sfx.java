package dev.waterplane.game;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;

import java.util.Random;

/**
 * Every sound is synthesized here at startup; there are no audio files. One thread mixes the
 * one-shot clips with two continuous layers (propeller engine, water-skim hiss) and streams
 * the result through a single AudioTrack.
 */
final class Sfx implements Runnable {
    static final int RATE = 22050;
    static final int CLICK = 0, COLLECT = 1, SPLASH = 2, CRASH = 3, WIN = 4, WARN = 5;

    private final float[][] clips = new float[6][];
    private final float[][] voice = new float[8][];
    private final int[] voicePos = new int[8];
    private final float[] voiceGain = new float[8];

    volatile boolean muted;
    // 0 = off, 1 = idle, 2 = full throttle
    private volatile float engineTarget, skimTarget;
    private float engine, skim, phase, wobble, noiseLp;
    private final Random rnd = new Random(7);

    private Thread thread;
    private volatile boolean running;

    Sfx() {
        clips[CLICK] = tones(new float[]{1300}, 0.035f, 60, 0.25f);
        clips[COLLECT] = tones(new float[]{988, 1480}, 0.11f, 22, 0.32f);
        clips[WARN] = tones(new float[]{660}, 0.09f, 12, 0.18f);
        clips[WIN] = tones(new float[]{523, 659, 784, 1047, 1047}, 0.13f, 9, 0.28f);
        clips[SPLASH] = splash();
        clips[CRASH] = crash();
    }

    void play(int id, float gain) {
        if (muted) return;
        synchronized (voice) {
            int slot = 0;
            for (int i = 0; i < voice.length; i++) {
                if (voice[i] == null) { slot = i; break; }
                if (voicePos[i] > voicePos[slot]) slot = i;
            }
            voice[slot] = clips[id];
            voicePos[slot] = 0;
            voiceGain[slot] = gain;
        }
    }

    /** level: 0 off, 1 idle, 2 throttle. skimming: the hull is on the water. */
    void engine(float level, boolean skimming) {
        engineTarget = muted ? 0 : level;
        skimTarget = muted || !skimming ? 0 : 1;
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "sfx");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(300); } catch (InterruptedException ignored) { }
            thread = null;
        }
    }

    @Override
    public void run() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack.Builder b = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM);
        if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        AudioTrack track;
        try {
            track = b.build();
            track.play();
        } catch (RuntimeException e) {
            running = false; // no audio device: play silently
            return;
        }
        short[] out = new short[256];
        while (running) {
            synchronized (voice) { mix(out); }
            track.write(out, 0, out.length);
        }
        track.stop();
        track.release();
    }

    private void mix(short[] out) {
        for (int n = 0; n < out.length; n++) {
            // Engine: a soft two-harmonic tone, pulsed like a propeller
            engine += (engineTarget - engine) * 0.0006f;
            skim += (skimTarget - skim) * 0.002f;
            float s = 0;
            if (engine > 0.001f) {
                float freq = 70 + engine * 40;
                phase += freq / RATE; if (phase > 1) phase -= 1;
                wobble += (14 + engine * 9) / RATE; if (wobble > 1) wobble -= 1;
                float tone = (float) (Math.sin(phase * 6.2832) * 0.6 + Math.sin(phase * 12.566) * 0.3
                        + Math.sin(phase * 18.85) * 0.1);
                float prop = 0.7f + 0.3f * (float) Math.sin(wobble * 6.2832);
                s += tone * prop * Math.min(engine, 2) * 0.055f;
            }
            float noise = rnd.nextFloat() * 2 - 1;
            noiseLp += (noise - noiseLp) * 0.25f;
            if (skim > 0.001f) s += noiseLp * skim * 0.16f;
            for (int i = 0; i < voice.length; i++) {
                float[] c = voice[i];
                if (c == null) continue;
                if (voicePos[i] >= c.length) { voice[i] = null; continue; }
                s += c[voicePos[i]++] * voiceGain[i];
            }
            s = s / (1 + Math.abs(s)) * 1.6f; // soft clip
            out[n] = (short) (Math.max(-1, Math.min(1, s)) * 32000);
        }
    }

    // ---------------------------------------------------------------- synthesis

    /** Notes in a row, each a sine with a touch of octave and an exponential decay. */
    private static float[] tones(float[] freqs, float each, float decay, float gain) {
        int per = (int) (each * RATE), tail = (int) (0.25f * RATE);
        float[] o = new float[per * freqs.length + tail];
        for (int k = 0; k < freqs.length; k++) {
            int len = k == freqs.length - 1 ? per + tail : per * 2;
            for (int i = 0; i < len && k * per + i < o.length; i++) {
                float t = i / (float) RATE;
                float env = (float) Math.exp(-t * decay) * Math.min(1, t * 400);
                double ph = 2 * Math.PI * freqs[k] * t;
                o[k * per + i] += (float) (Math.sin(ph) + 0.25 * Math.sin(2 * ph)) * env * gain;
            }
        }
        return o;
    }

    private static float[] splash() {
        Random r = new Random(3);
        float[] o = new float[(int) (0.5f * RATE)];
        float lp = 0;
        for (int i = 0; i < o.length; i++) {
            float t = i / (float) RATE;
            float cut = 0.5f * (float) Math.exp(-t * 5) + 0.04f;
            lp += ((r.nextFloat() * 2 - 1) - lp) * cut;
            o[i] = lp * (float) Math.exp(-t * 7) * Math.min(1, t * 200) * 0.9f;
        }
        return o;
    }

    private static float[] crash() {
        Random r = new Random(5);
        float[] o = new float[(int) (1.0f * RATE)];
        float lp = 0, ph = 0;
        for (int i = 0; i < o.length; i++) {
            float t = i / (float) RATE;
            lp += ((r.nextFloat() * 2 - 1) - lp) * 0.08f;
            float f = 40 + 140 * (float) Math.exp(-t * 4);
            ph += f / RATE;
            o[i] = (lp * 1.6f * (float) Math.exp(-t * 3.5f)
                    + (float) Math.sin(ph * 6.2832) * 0.7f * (float) Math.exp(-t * 4.5f)) * Math.min(1, t * 300);
        }
        return o;
    }
}
