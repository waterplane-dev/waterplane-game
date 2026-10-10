package dev.waterplane.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Random;

/** Waterplane: Harbor Run. Fly the logo plane through the storm, skim the sea for tokens. */
public class GameView extends View {
    // Brand palette (from the waterplane icon)
    static final int NIGHT = 0xFF0A1B28, NIGHT2 = 0xFF10324A, SEA = 0xFF0F5A66, DEEP = 0xFF061219;
    static final int AMBER = 0xFFF5A93F, CORAL = 0xFFEC6F55, FOAM = 0xFFE9F4F2;

    static final int MENU = 0, READY = 1, PLAY = 2, DEAD = 3, WIN = 4;
    static final String[] DIFF = {"EASY", "NORMAL", "HARD"};
    // Per-difficulty tuning, in u (1u = 1% of screen height) and seconds
    static final float[] SPEED = {30, 38, 47};
    static final float[] GAP = {44, 35, 28};
    static final float[] SPACING = {80, 66, 56};
    static final float[] BURN = {11, 16, 22};
    static final float[] CRASH_V = {64, 54, 45};
    static final float[] SHIFT = {20, 30, 40};
    static final float LEVEL_LEN = 2600;
    static final float GRAVITY = 95, THRUST = 235, PLANE = 15;

    static final int T_ROCK = 0, T_CLOUD = 1, T_CURSOR = 2, T_REEF = 3, T_FINISH = 4;

    static class Ob {
        int type;
        float x, w, top, bot, y, seed;
        boolean light, taken;
        Path path;
    }

    final Random rnd = new Random();
    final ArrayList<Ob> obs = new ArrayList<>();
    final SharedPreferences prefs;

    float W, H, u, seaY, PX;
    int state = MENU, diff = 1;
    float cam, py, vy, fuel, time, stateTime, shake, speed, spin, levelLen;
    float nextSpawn, prevGap, skimTime;
    boolean holding, skimming, paused, finishPlaced, sunk;
    // Steers itself through the gaps; set from the launch intent to record demos.
    boolean autopilot;
    final Sfx sfx = new Sfx();
    float warnTimer;
    int cursors, cursorsTotal, score, stars;
    boolean newBest;
    String reason = "";
    long last;

    // Particles (screen space, scrolled with the world)
    static final int NP = 600;
    final float[] ppx = new float[NP], ppy = new float[NP], pvx = new float[NP], pvy = new float[NP];
    final float[] pl = new float[NP], pml = new float[NP], psz = new float[NP], pg = new float[NP];
    final int[] pc = new int[NP];
    int pnext;

    final float[] starX = new float[50], starY = new float[50], starP = new float[50];

    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), sky = new Paint(), seaP = new Paint(), glow = new Paint();
    final Paint cursorGlow = new Paint(Paint.ANTI_ALIAS_FLAG), rockP = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint wing = new Paint(Paint.ANTI_ALIAS_FLAG), body = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint tail = new Paint(Paint.ANTI_ALIAS_FLAG), shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint beam = new Paint(Paint.ANTI_ALIAS_FLAG), fuelP = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path chev = new Path(), tailPath = new Path(), tmp = new Path(), star = new Path();
    final RectF r = new RectF();
    final RectF[] diffBtn = {new RectF(), new RectF(), new RectF()};
    final RectF playBtn = new RectF(), btnA = new RectF(), btnB = new RectF();
    final RectF muteBtn = new RectF();

    public GameView(Context c) {
        super(c);
        prefs = c.getSharedPreferences("waterplane", Context.MODE_PRIVATE);
        sfx.muted = prefs.getBoolean("mute", false);
        diff = prefs.getInt("diff", 1);
        Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        text.setTypeface(mono);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        for (Paint p : new Paint[]{wing, body, tail}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setShader(new LinearGradient(60, 140, 300, 320, AMBER, CORAL, Shader.TileMode.CLAMP));
        }
        wing.setStrokeWidth(50);
        body.setStrokeWidth(22);
        tail.setStrokeWidth(15);
        shine.setStyle(Paint.Style.STROKE);
        shine.setStrokeCap(Paint.Cap.ROUND);
        shine.setStrokeWidth(7);
        shine.setColor(0x47FFFFFF);
        chev.moveTo(128, 146); chev.lineTo(286, 228); chev.lineTo(128, 310);
        tailPath.moveTo(92, 204); tailPath.lineTo(104, 228); tailPath.lineTo(92, 252);
        for (int i = 0; i < starX.length; i++) {
            starX[i] = rnd.nextFloat(); starY[i] = rnd.nextFloat() * 0.62f; starP[i] = rnd.nextFloat() * 6.28f;
        }
        setHapticFeedbackEnabled(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        W = w; H = h; u = h / 100f;
        seaY = 80 * u; PX = W * 0.24f;
        levelLen = LEVEL_LEN * u;
        sky.setShader(new LinearGradient(0, 0, 0, seaY, new int[]{NIGHT, NIGHT2, 0xFF2B3550},
                new float[]{0, 0.62f, 1}, Shader.TileMode.CLAMP));
        seaP.setShader(new LinearGradient(0, seaY, 0, H, SEA, DEEP, Shader.TileMode.CLAMP));
        glow.setShader(new RadialGradient(W * 0.74f, seaY, H * 0.6f, new int[]{0x66F5A93F, 0x22EC6F55, 0x00F5A93F},
                new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
        cursorGlow.setShader(new RadialGradient(0, 0, 7 * u, 0x88F5A93F, 0x00F5A93F, Shader.TileMode.CLAMP));
        rockP.setShader(new LinearGradient(0, 20 * u, 0, seaY, 0xFF1C3F48, 0xFF08161C, Shader.TileMode.CLAMP));
        fuelP.setShader(new LinearGradient(4 * u, 0, 34 * u, 0, AMBER, CORAL, Shader.TileMode.CLAMP));
        if (state == MENU) py = 46 * u;
    }

    // ---------------------------------------------------------------- flow

    void startReady() {
        state = READY; stateTime = 0;
        cam = 0; py = 46 * u; vy = 0; fuel = 100; spin = 0; sunk = false;
        cursors = 0; cursorsTotal = 0; skimTime = 0; newBest = false;
        obs.clear(); finishPlaced = false;
        nextSpawn = W + 25 * u; prevGap = 46 * u;
        speed = SPEED[diff] * u;
        prefs.edit().putInt("diff", diff).apply();
    }

    void toMenu() { state = MENU; stateTime = 0; obs.clear(); paused = false; }

    void crash(String why, boolean water) {
        state = DEAD; stateTime = 0; reason = why; shake = 2.2f * u;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        for (int i = 0; i < 60; i++) {
            float a = rnd.nextFloat() * 6.28f, s = (8 + rnd.nextFloat() * 40) * u;
            emit(PX, py, (float) Math.cos(a) * s, (float) Math.sin(a) * s - 10 * u, 0.5f + rnd.nextFloat() * 0.7f,
                    (0.4f + rnd.nextFloat()) * u, i % 3 == 0 ? FOAM : (i % 2 == 0 ? AMBER : CORAL), 1);
        }
        sfx.play(Sfx.CRASH, 0.7f);
        if (water) { splash(PX, 50); sfx.play(Sfx.SPLASH, 1); }
        finish(false);
    }

    void win() {
        sfx.play(Sfx.WIN, 1);
        state = WIN; stateTime = 0;
        for (int i = 0; i < 90; i++) {
            float a = rnd.nextFloat() * 6.28f, s = (10 + rnd.nextFloat() * 45) * u;
            emit(PX + 10 * u, py, (float) Math.cos(a) * s, (float) Math.sin(a) * s, 0.8f + rnd.nextFloat(),
                    (0.4f + rnd.nextFloat() * 0.8f) * u, i % 2 == 0 ? AMBER : FOAM, 0.4f);
        }
        finish(true);
    }

    void finish(boolean won) {
        int meters = (int) (cam / u * 2);
        score = meters + cursors * 150 + (int) (skimTime * 40) + (won ? 2000 * (diff + 1) : 0);
        float ratio = cursorsTotal == 0 ? 1 : cursors / (float) cursorsTotal;
        stars = !won ? 0 : ratio >= 0.8f ? 3 : ratio >= 0.5f ? 2 : 1;
        SharedPreferences.Editor e = prefs.edit();
        if (score > prefs.getInt("best" + diff, 0)) { e.putInt("best" + diff, score); newBest = true; }
        if (stars > prefs.getInt("stars" + diff, 0)) e.putInt("stars" + diff, stars);
        e.apply();
    }

    public void pause() {
        sfx.engine(0, false);
        sfx.stop();
        holding = false;
        if (state == PLAY) paused = true;
    }

    public void resume() { sfx.start(); }

    public boolean back() {
        if (state == MENU) return false;
        toMenu();
        return true;
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) holding = false;
        if (a != MotionEvent.ACTION_DOWN && a != MotionEvent.ACTION_POINTER_DOWN) return true;
        holding = true;
        float x = e.getX(e.getActionIndex()), y = e.getY(e.getActionIndex());
        switch (state) {
            case MENU:
                if (hit(muteBtn, x, y)) {
                    sfx.muted = !sfx.muted;
                    prefs.edit().putBoolean("mute", sfx.muted).apply();
                    tick();
                }
                for (int i = 0; i < 3; i++) if (hit(diffBtn[i], x, y)) { diff = i; tick(); }
                if (hit(playBtn, x, y)) { tick(); startReady(); }
                break;
            case READY:
                state = PLAY; stateTime = 0; vy = -25 * u;
                break;
            case PLAY:
                paused = false;
                break;
            default:
                if (stateTime < 0.8f) break;
                if (hit(btnA, x, y)) { tick(); startReady(); }
                else if (hit(btnB, x, y)) { tick(); toMenu(); }
        }
        return true;
    }

    boolean hit(RectF b, float x, float y) {
        return x > b.left - 2 * u && x < b.right + 2 * u && y > b.top - 2 * u && y < b.bottom + 2 * u;
    }

    void tick() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        sfx.play(Sfx.CLICK, 1);
    }

    // ---------------------------------------------------------------- update

    void update(float dt) {
        time += dt; stateTime += dt;
        shake *= (float) Math.exp(-7 * dt);
        float before = cam;
        switch (state) {
            case MENU:
                cam += 14 * u * dt;
                py = 44 * u + (float) Math.sin(time * 1.4f) * 3 * u;
                vy = (float) Math.cos(time * 1.4f) * 4 * u;
                exhaust(false);
                break;
            case READY:
                cam += 10 * u * dt;
                py = 46 * u + (float) Math.sin(time * 2.2f) * 2 * u;
                vy = (float) Math.cos(time * 2.2f) * 4 * u;
                exhaust(false);
                break;
            case PLAY:
                play(dt);
                break;
            case DEAD:
                if (!sunk) {
                    vy += GRAVITY * u * dt; py += vy * dt; spin += 420 * dt;
                    if (py > seaY) { sunk = true; splash(PX, 30); sfx.play(Sfx.SPLASH, 0.8f); }
                }
                break;
            case WIN:
                cam += speed * dt;
                py -= 22 * u * dt * Math.min(1, stateTime);
                exhaust(true);
                break;
        }
        stepParticles(dt, cam - before);
        float level = state == PLAY ? (holding && fuel > 0 ? 2 : 1) : state == READY ? 1 : state == WIN ? 2 : 0;
        sfx.engine(level, state == PLAY && skimming);
    }

    void play(float dt) {
        float prog = Math.min(1, cam / levelLen);
        speed = SPEED[diff] * u * (1 + 0.25f * prog);
        cam += speed * dt;
        if (autopilot) holding = steer();
        boolean thrust = holding && fuel > 0;
        float acc = GRAVITY * u;
        if (thrust) {
            acc -= THRUST * u;
            fuel = Math.max(0, fuel - BURN[diff] * dt);
        }
        vy = clamp(vy + acc * dt, -62 * u, 85 * u);
        py += vy * dt;
        if (py < 11 * u) { py = 11 * u; if (vy < 0) vy = 0; }

        float skimY = seaY - 3.5f * u;
        if (py >= skimY) {
            if (vy > CRASH_V[diff] * u && !skimming) { crash("Nosedived into the sea", true); return; }
            py = skimY;
            if (vy > 0) vy = 0;
            if (!skimming) { splash(PX, 14); sfx.play(Sfx.SPLASH, 0.45f); }
            skimming = true;
            skimTime += dt;
            fuel = Math.min(100, fuel + 34 * dt);
            for (int i = 0; i < 2; i++)
                emit(PX - 4 * u + rnd.nextFloat() * 3 * u, seaY, -rnd.nextFloat() * 18 * u,
                        -(10 + rnd.nextFloat() * 26) * u, 0.55f, (0.3f + rnd.nextFloat() * 0.6f) * u, FOAM, 1);
        } else skimming = false;
        exhaust(thrust);
        warnTimer -= dt;
        if (!skimming && vy > CRASH_V[diff] * u * 0.8f && py > seaY - 22 * u && warnTimer <= 0) {
            sfx.play(Sfx.WARN, 1);
            warnTimer = 0.35f;
        }

        spawn();

        float cx = PX + 1.5f * u, rad = 4.3f * u;
        for (int i = obs.size() - 1; i >= 0; i--) {
            Ob o = obs.get(i);
            float sx = o.x - cam;
            if (sx + o.w < -30 * u) { obs.remove(i); continue; }
            switch (o.type) {
                case T_ROCK: {
                    float rockTop = o.light ? o.top + 9 * u : o.top;
                    if (circleRect(cx, py, rad, sx + 1.2f * u, rockTop + 1.2f * u, sx + o.w - 1.2f * u, seaY)
                            || (o.light && circleRect(cx, py, rad, sx + o.w / 2 - 2.2f * u, o.top, sx + o.w / 2 + 2.2f * u, rockTop))) {
                        crash(o.light ? "Clipped the lighthouse" : "Hit the rocks", false); return;
                    }
                    break;
                }
                case T_CLOUD:
                    if (circleRect(cx, py, rad, sx + 1.8f * u, -10 * u, sx + o.w - 1.8f * u, o.bot - 2.2f * u)) {
                        crash("Lost in the storm", false); return;
                    }
                    break;
                case T_REEF:
                    if (circleRect(cx, py, rad, sx + 1.2f * u, o.top + 1 * u, sx + o.w - 1.2f * u, seaY)) {
                        crash("Ran aground on a reef", false); return;
                    }
                    break;
                case T_CURSOR:
                    if (!o.taken && Math.hypot(sx - cx, o.y - py) < 7.5f * u) {
                        o.taken = true; cursors++;
                        fuel = Math.min(100, fuel + 22);
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        sfx.play(Sfx.COLLECT, 1);
                        for (int k = 0; k < 18; k++) {
                            float a = rnd.nextFloat() * 6.28f, s = (10 + rnd.nextFloat() * 22) * u;
                            emit(sx, o.y, (float) Math.cos(a) * s, (float) Math.sin(a) * s, 0.45f,
                                    (0.3f + rnd.nextFloat() * 0.5f) * u, AMBER, 0);
                        }
                    }
                    break;
                case T_FINISH:
                    if (sx < PX) { win(); return; }
                    break;
            }
        }
    }

    boolean steer() {
        float target = 46 * u, best = Float.MAX_VALUE;
        for (Ob o : obs) {
            float ahead = o.x + o.w - cam - PX;
            if (ahead < -2 * u || ahead >= best) continue;
            if (o.type == T_CLOUD) { best = ahead; target = o.y; }
            else if (o.type == T_REEF) { best = ahead; target = o.top - 9 * u; }
        }
        target = Math.min(target, seaY - 9 * u);
        return (py - target) + vy * 0.22f > 0;
    }

    void spawn() {
        while (!finishPlaced && nextSpawn < cam + W + 30 * u) {
            float x = nextSpawn;
            if (x >= levelLen - 20 * u) {
                Ob f = new Ob(); f.type = T_FINISH; f.x = Math.max(x, levelLen); f.w = 4 * u;
                obs.add(f); finishPlaced = true; break;
            }
            float prog = x / levelLen;
            float gap = GAP[diff] * u * (1 - 0.12f * prog);
            float lo = 13 * u + gap / 2, hi = seaY - gap / 2 + 3 * u;
            float g = clamp(prevGap + (rnd.nextFloat() * 2 - 1) * SHIFT[diff] * u, lo, hi);
            prevGap = g;
            float w = (13 + rnd.nextFloat() * 4) * u;

            Ob c = new Ob(); c.type = T_CLOUD; c.x = x; c.w = w; c.bot = g - gap / 2; c.seed = rnd.nextFloat() * 10;
            c.y = g;
            c.path = cloudPath(w, c.bot); obs.add(c);

            float top = g + gap / 2;
            if (top < seaY - 6 * u) {
                Ob k = new Ob(); k.type = T_ROCK; k.x = x; k.w = w; k.top = top; k.seed = rnd.nextFloat() * 10;
                k.light = top < seaY - 32 * u && rnd.nextFloat() < 0.4f;
                k.path = rockPath(w, k.light ? top + 9 * u : top); obs.add(k);
            }
            if (rnd.nextFloat() < 0.75f) addCursor(x + w / 2, g);

            float sp = SPACING[diff] * u * (0.92f + rnd.nextFloat() * 0.25f);
            float roll = rnd.nextFloat();
            if (roll < 0.22f + 0.1f * diff) {
                Ob rf = new Ob(); rf.type = T_REEF; rf.w = (9 + rnd.nextFloat() * 4) * u;
                rf.x = x + w + (sp - w) / 2 - rf.w / 2; rf.top = seaY - (5 + rnd.nextFloat() * 6) * u;
                rf.path = rockPath(rf.w, rf.top); obs.add(rf);
                if (rnd.nextFloat() < 0.6f) addCursor(rf.x + rf.w / 2, rf.top - 11 * u);
            } else if (roll < 0.6f) {
                addCursor(x + w + (sp - w) / 2, (18 + rnd.nextFloat() * 50) * u);
            }
            nextSpawn = x + sp;
        }
    }

    void addCursor(float x, float y) {
        Ob o = new Ob(); o.type = T_CURSOR; o.x = x; o.y = y; o.w = 4 * u; o.seed = rnd.nextFloat() * 6;
        obs.add(o); cursorsTotal++;
    }

    Path rockPath(float w, float top) {
        Path p = new Path();
        p.moveTo(-1.5f * u, seaY + 2 * u);
        int n = 5;
        for (int i = 1; i < n; i++) p.lineTo(rnd.nextFloat() * 2.4f * u, seaY - (seaY - top) * i / n);
        p.lineTo(w * 0.22f, top + 1.6f * u);
        p.lineTo(w * 0.45f, top);
        p.lineTo(w * 0.62f, top + 1.2f * u);
        p.lineTo(w * 0.8f, top + 0.4f * u);
        for (int i = n - 1; i > 0; i--) p.lineTo(w - rnd.nextFloat() * 2.4f * u, seaY - (seaY - top) * i / n);
        p.lineTo(w + 1.5f * u, seaY + 2 * u);
        p.close();
        return p;
    }

    Path cloudPath(float w, float bot) {
        Path p = new Path();
        p.addRect(0, -5 * u, w, bot - 3.5f * u, Path.Direction.CW);
        float rr = w * 0.3f;
        union(p, w * 0.2f, bot - rr, rr);
        union(p, w * 0.52f, bot - rr * 0.9f, rr * 1.05f);
        union(p, w * 0.82f, bot - rr * 1.1f, rr * 0.9f);
        for (float y = bot - 2.2f * rr; y > 0; y -= 2.2f * rr) {
            union(p, 0, y, rr * 0.7f);
            union(p, w, y - rr, rr * 0.7f);
        }
        return p;
    }

    static void union(Path p, float x, float y, float rad) {
        Path c = new Path();
        c.addCircle(x, y, rad, Path.Direction.CW);
        p.op(c, Path.Op.UNION);
    }

    static boolean circleRect(float cx, float cy, float rad, float l, float t, float rt, float b) {
        float nx = clamp(cx, l, rt), ny = clamp(cy, t, b);
        float dx = cx - nx, dy = cy - ny;
        return dx * dx + dy * dy < rad * rad;
    }

    static float clamp(float v, float lo, float hi) { return v < lo ? lo : v > hi ? hi : v; }

    float planeAngle() { return state == DEAD ? spin : clamp(vy / u * 0.45f, -28, 32); }

    void exhaust(boolean thrust) {
        double a = Math.toRadians(planeAngle());
        float d = 7.2f * u;
        float tx = PX + (state == WIN ? stateTime * stateTime * 18 * u : 0) - (float) Math.cos(a) * d;
        float ty = py - (float) Math.sin(a) * d;
        if (thrust) {
            for (int i = 0; i < 2; i++)
                emit(tx, ty + (rnd.nextFloat() - 0.5f) * u, -12 * u, (rnd.nextFloat() - 0.5f) * 6 * u, 0.32f,
                        (0.5f + rnd.nextFloat() * 0.5f) * u, rnd.nextBoolean() ? AMBER : CORAL, 0);
        } else if (rnd.nextFloat() < 0.6f) {
            emit(tx, ty, -6 * u, (rnd.nextFloat() - 0.5f) * 3 * u, 0.5f, 0.45f * u, 0x88E9F4F2, 0);
        }
    }

    void splash(float x, int n) {
        for (int i = 0; i < n; i++)
            emit(x + (rnd.nextFloat() - 0.5f) * 6 * u, seaY, (rnd.nextFloat() - 0.6f) * 30 * u,
                    -(12 + rnd.nextFloat() * 40) * u, 0.7f + rnd.nextFloat() * 0.4f, (0.4f + rnd.nextFloat() * 0.8f) * u, FOAM, 1);
    }

    void emit(float x, float y, float vx, float vy, float life, float size, int color, float grav) {
        int i = pnext; pnext = (pnext + 1) % NP;
        ppx[i] = x; ppy[i] = y; pvx[i] = vx; pvy[i] = vy; pl[i] = life; pml[i] = life; psz[i] = size; pc[i] = color; pg[i] = grav;
    }

    void stepParticles(float dt, float scroll) {
        for (int i = 0; i < NP; i++) {
            if (pl[i] <= 0) continue;
            pl[i] -= dt;
            pvy[i] += pg[i] * GRAVITY * u * dt;
            ppx[i] += pvx[i] * dt - scroll;
            ppy[i] += pvy[i] * dt;
        }
    }

    // ---------------------------------------------------------------- render

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = last == 0 ? 0 : Math.min((now - last) / 1e9f, 1 / 30f);
        last = now;
        if (W > 0) {
            if (!paused) update(dt);
            render(c);
        }
        postInvalidateOnAnimation();
    }

    void render(Canvas c) {
        c.save();
        if (shake > 0.05f) c.translate((rnd.nextFloat() - 0.5f) * shake * 2, (rnd.nextFloat() - 0.5f) * shake * 2);
        drawBackdrop(c);
        for (Ob o : obs) drawOb(c, o);
        drawSea(c);
        drawParticles(c);
        float planeX = PX + (state == WIN ? stateTime * stateTime * 18 * u : 0);
        if (!(state == DEAD && sunk)) {
            drawPlane(c, planeX, py, planeAngle(), 255);
            float h = seaY - py;
            if (h < 28 * u) {
                c.save();
                c.clipRect(0, seaY, W, H);
                c.scale(1, -1, 0, seaY);
                drawPlane(c, planeX, py, planeAngle(), (int) (90 * (1 - h / (28 * u))));
                c.restore();
            }
        }
        c.restore();

        switch (state) {
            case MENU: drawMenu(c); break;
            case READY: drawHud(c); drawReady(c); break;
            case PLAY: drawHud(c); if (paused) banner(c, "PAUSED", "tap to resume"); break;
            case DEAD: drawHud(c); if (stateTime > 0.7f) drawResult(c, false); break;
            case WIN: if (stateTime > 1f) drawResult(c, true); break;
        }
    }

    void drawBackdrop(Canvas c) {
        c.drawRect(0, 0, W, seaY + 1, sky);
        c.drawRect(0, 0, W, seaY + 1, glow);
        fill.setShader(null);
        for (int i = 0; i < starX.length; i++) {
            float tw = 0.35f + 0.35f * (float) Math.sin(time * 1.7f + starP[i]);
            fill.setColor(FOAM);
            fill.setAlpha((int) (tw * 200));
            float sx = ((starX[i] * W - cam * 0.02f) % W + W) % W;
            c.drawCircle(sx, starY[i] * seaY, (0.12f + (i % 3) * 0.08f) * u, fill);
        }
        islands(c, 0.06f, 9 * u, 0xFF132C3E, 0.7f);
        islands(c, 0.16f, 5.5f * u, 0xFF0C2131, 3.1f);
    }

    void islands(Canvas c, float par, float hgt, int color, float ph) {
        tmp.reset();
        tmp.moveTo(0, seaY + 1);
        float step = W / 90f, off = cam * par / u;
        for (float x = 0; x <= W + step; x += step) {
            float xn = x / u + off;
            float f = (float) (Math.sin(xn * 0.045 + ph) * 0.6 + Math.sin(xn * 0.11 + 1.3 + ph) * 0.4
                    + Math.sin(xn * 0.31) * 0.12 - 0.22);
            tmp.lineTo(x, seaY - Math.max(0, f) * hgt * 1.6f);
        }
        tmp.lineTo(W, seaY + 1);
        tmp.close();
        fill.setColor(color);
        c.drawPath(tmp, fill);
    }

    void drawSea(Canvas c) {
        c.drawRect(0, seaY, W, H, seaP);
        fill.setShader(null);
        // Sun-glow ripples, like the icon's reflection stripes
        float sunX = W * 0.74f;
        for (int k = 0; k < 9; k++) {
            float y = seaY + 1.6f * u + k * 2.2f * u;
            float hw = (20 - k * 1.9f) * u * (0.85f + 0.15f * (float) Math.sin(time * 2.1f + k * 1.3f));
            fill.setColor(AMBER);
            fill.setAlpha(Math.max(0, 70 - k * 7));
            float ox = (k % 2 == 0 ? -1.5f : 1.5f) * u;
            r.set(sunX - hw + ox, y, sunX + hw + ox, y + (1.1f - k * 0.05f) * u);
            c.drawRoundRect(r, u, u, fill);
        }
        stroke.setShader(null);
        for (int i = 0; i < 8; i++) {
            float y0 = seaY + (1.2f + i * 1.1f + i * i * 0.28f) * u;
            float amp = (0.25f + i * 0.08f) * u, wl = (16 + i * 6) * u, phase = (cam * (0.5f + i * 0.12f) + time * 8 * u);
            stroke.setColor(FOAM);
            stroke.setAlpha(Math.max(12, 70 - i * 7));
            stroke.setStrokeWidth((0.18f + i * 0.04f) * u);
            tmp.reset();
            for (float x = 0; x <= W + 8; x += 8) {
                float y = y0 + amp * (float) Math.sin((x + phase) / wl * 6.283f);
                if (x == 0) tmp.moveTo(x, y); else tmp.lineTo(x, y);
            }
            c.drawPath(tmp, stroke);
        }
        stroke.setColor(FOAM);
        stroke.setAlpha(110);
        stroke.setStrokeWidth(0.3f * u);
        c.drawLine(0, seaY, W, seaY, stroke);
    }

    void drawOb(Canvas c, Ob o) {
        float sx = o.x - cam;
        if (sx > W + 20 * u || sx + o.w < -20 * u) return;
        switch (o.type) {
            case T_CLOUD: {
                boolean flash = ((time * 0.6f + o.seed) % 4f) < 0.07f;
                c.save(); c.translate(sx, 0);
                if (flash) { fill.setShader(null); fill.setColor(0xFF8FA6C4); }
                else {
                    fill.setColor(0xFFFFFFFF);
                    fill.setShader(new LinearGradient(0, Math.max(0, o.bot - 32 * u), 0, o.bot, 0xFF1E2D3F, 0xFF56697F, Shader.TileMode.CLAMP));
                }
                c.drawPath(o.path, fill);
                fill.setShader(null);
                stroke.setColor(flash ? 0xCCE9F4F2 : 0x8896B4CC);
                stroke.setStrokeWidth(0.45f * u);
                c.drawPath(o.path, stroke);
                if (flash) {
                    stroke.setColor(0xFFFFF4D0); stroke.setStrokeWidth(0.5f * u);
                    float bx = o.w * 0.5f, by = o.bot - 2 * u;
                    tmp.reset(); tmp.moveTo(bx, by - 14 * u); tmp.lineTo(bx - 2 * u, by - 8 * u);
                    tmp.lineTo(bx + 1.5f * u, by - 6 * u); tmp.lineTo(bx - 1 * u, by);
                    c.drawPath(tmp, stroke);
                }
                c.restore();
                break;
            }
            case T_ROCK:
            case T_REEF: {
                c.save(); c.translate(sx, 0);
                c.drawPath(o.path, rockP);
                stroke.setColor(0x4436A3A8); stroke.setStrokeWidth(0.35f * u);
                c.drawPath(o.path, stroke);
                c.restore();
                // foam collar
                fill.setShader(null); fill.setColor(FOAM);
                fill.setAlpha(120 + (int) (60 * Math.sin(time * 4 + o.x)));
                r.set(sx - 2 * u, seaY - 0.5f * u, sx + o.w + 2 * u, seaY + 0.9f * u);
                c.drawRoundRect(r, u, u, fill);
                if (o.light) drawLighthouse(c, sx + o.w / 2, o.top);
                break;
            }
            case T_CURSOR: {
                if (o.taken) break;
                float bob = (float) Math.sin(time * 3 + o.seed) * 0.8f * u;
                float blink = ((time + o.seed) % 1.1f) < 0.62f ? 1f : 0.35f;
                c.save(); c.translate(sx, o.y + bob);
                cursorGlow.setAlpha((int) (255 * blink));
                c.drawCircle(0, 0, 7 * u, cursorGlow);
                fill.setShader(null); fill.setColor(AMBER); fill.setAlpha((int) (255 * blink));
                r.set(-3.6f * u, -1.1f * u, 3.6f * u, 1.1f * u);
                c.drawRoundRect(r, 0.5f * u, 0.5f * u, fill);
                c.restore();
                break;
            }
            case T_FINISH: {
                fill.setColor(0xFFFFFFFF);
                fill.setShader(new LinearGradient(0, 0, 0, seaY, 0x00F5A93F, 0xAAF5A93F, Shader.TileMode.CLAMP));
                c.drawRect(sx - 1.5f * u, 0, sx + 1.5f * u, seaY, fill);
                fill.setShader(null);
                for (int i = 0; i < 9; i++) {
                    fill.setColor(i % 2 == 0 ? AMBER : FOAM);
                    float y = 12 * u + i * 7.5f * u;
                    c.drawCircle(sx, y, 0.9f * u, fill);
                }
                text.setTextAlign(Paint.Align.CENTER);
                text.setTextSize(3.4f * u); text.setColor(AMBER);
                c.drawText("$ exit 0", sx, 9 * u, text);
                fill.setColor(CORAL);
                c.drawCircle(sx, seaY, 1.4f * u, fill);
                break;
            }
        }
    }

    void drawLighthouse(Canvas c, float cx, float top) {
        float hw = 2.1f * u, h = 9 * u;
        fill.setShader(null);
        // beam
        float ang = time * 1.6f;
        float reach = 34 * u;
        float strength = 0.5f + 0.5f * (float) Math.cos(ang);
        beam.setColor(AMBER);
        beam.setAlpha((int) (60 * strength));
        tmp.reset();
        tmp.moveTo(cx, top + 1.2f * u);
        float dir = (float) Math.sin(ang) > 0 ? 1 : -1;
        tmp.lineTo(cx + dir * reach, top - 6 * u);
        tmp.lineTo(cx + dir * reach, top + 7 * u);
        tmp.close();
        c.drawPath(tmp, beam);
        // tower
        fill.setColor(FOAM);
        r.set(cx - hw, top + 2.4f * u, cx + hw, top + h + 0.5f * u);
        c.drawRect(r, fill);
        fill.setColor(CORAL);
        c.drawRect(cx - hw, top + 4.4f * u, cx + hw, top + 5.8f * u, fill);
        c.drawRect(cx - hw, top + 7.2f * u, cx + hw, top + 8.6f * u, fill);
        fill.setColor(0xFF24343F);
        r.set(cx - hw - 0.4f * u, top + 0.2f * u, cx + hw + 0.4f * u, top + 0.9f * u);
        c.drawRect(r, fill);
        fill.setColor(AMBER);
        c.drawCircle(cx, top + 1.6f * u, 1.1f * u, fill);
    }

    void drawPlane(Canvas c, float x, float y, float deg, int alpha) {
        float s = PLANE * u / 214f;
        c.save();
        c.translate(x, y);
        c.rotate(deg);
        c.scale(s, s);
        c.translate(-190, -228);
        wing.setAlpha(alpha); body.setAlpha(alpha); tail.setAlpha(alpha);
        shine.setAlpha(alpha * 71 / 255);
        c.drawPath(chev, wing);
        c.drawLine(272, 228, 84, 228, body);
        c.drawPath(tailPath, tail);
        c.drawLine(140, 160, 270, 228, shine);
        fill.setShader(null); fill.setColor(NIGHT); fill.setAlpha(alpha / 2);
        c.drawCircle(250, 228, 6.5f, fill);
        c.restore();
    }

    void drawParticles(Canvas c) {
        fill.setShader(null);
        for (int i = 0; i < NP; i++) {
            if (pl[i] <= 0) continue;
            float k = pl[i] / pml[i];
            fill.setColor(pc[i]);
            fill.setAlpha((int) (Color.alpha(pc[i]) * k));
            c.drawCircle(ppx[i], ppy[i], psz[i] * (0.5f + 0.5f * k), fill);
        }
    }

    // ---------------------------------------------------------------- UI

    void drawHud(Canvas c) {
        float m = 4 * u;
        float prog = Math.min(1, cam / levelLen);
        fill.setShader(null);
        fill.setColor(0x33E9F4F2);
        r.set(m, 2.5f * u, W - m, 3.2f * u); c.drawRoundRect(r, u, u, fill);
        fill.setColor(AMBER);
        r.set(m, 2.5f * u, m + (W - 2 * m) * prog, 3.2f * u); c.drawRoundRect(r, u, u, fill);

        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(4.2f * u); text.setColor(FOAM);
        c.drawText("> " + (int) (cam / u * 2) + " m", m, 10 * u, text);

        text.setTextSize(2.4f * u); text.setColor(0xAAE9F4F2);
        c.drawText("TOKENS", m, 14.6f * u, text);
        fill.setColor(0x33E9F4F2);
        r.set(m, 15.6f * u, m + 30 * u, 17.2f * u); c.drawRoundRect(r, u, u, fill);
        boolean low = fuel < 25;
        if (!low || ((time * 4) % 1f) < 0.6f) {
            r.set(m, 15.6f * u, m + 30 * u * fuel / 100f, 17.2f * u);
            if (low) { fill.setColor(CORAL); c.drawRoundRect(r, u, u, fill); }
            else c.drawRoundRect(r, u, u, fuelP);
        }

        text.setTextAlign(Paint.Align.RIGHT);
        text.setTextSize(4.2f * u); text.setColor(AMBER);
        c.drawText("▌ " + cursors, W - m, 10 * u, text);
        text.setTextSize(2.4f * u); text.setColor(0xAAE9F4F2);
        c.drawText("LVL 1 · " + DIFF[diff], W - m, 14.6f * u, text);

        if (state == PLAY && !paused) {
            text.setTextAlign(Paint.Align.CENTER);
            if (fuel <= 0 && ((time * 3) % 1f) < 0.7f) {
                text.setTextSize(3.4f * u); text.setColor(CORAL);
                c.drawText("OUT OF TOKENS · skim the water to refill", W / 2, 24 * u, text);
            } else if (!skimming && vy > CRASH_V[diff] * u * 0.8f && py > seaY - 22 * u && ((time * 6) % 1f) < 0.6f) {
                text.setTextSize(5 * u); text.setColor(CORAL);
                c.drawText("PULL UP", W / 2, 24 * u, text);
            } else if (skimming) {
                text.setTextSize(3 * u); text.setColor(0xCCE9F4F2);
                c.drawText("~ skimming · tokens refilling ~", W / 2, 24 * u, text);
            }
        }
    }

    void drawReady(Canvas c) {
        text.setTextAlign(Paint.Align.CENTER);
        float a = 0.65f + 0.35f * (float) Math.sin(time * 4);
        text.setTextSize(6 * u); text.setColor(FOAM); text.setAlpha((int) (255 * a));
        c.drawText("HOLD TO CLIMB", W / 2, 25 * u, text);
        text.setAlpha(255);
        text.setTextSize(3 * u); text.setColor(0xCCE9F4F2);
        c.drawText("release to glide · thread the gaps · grab the ▌ cursors", W / 2, 31 * u, text);
        text.setColor(AMBER);
        c.drawText("climbing burns TOKENS — touch down gently on the water to refuel", W / 2, 36 * u, text);
    }

    void banner(Canvas c, String title, String sub) {
        fill.setShader(null); fill.setColor(0x99061219);
        c.drawRect(0, 0, W, H, fill);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(9 * u); text.setColor(FOAM);
        c.drawText(title, W / 2, 46 * u, text);
        text.setTextSize(3.4f * u); text.setColor(AMBER);
        c.drawText(sub, W / 2, 54 * u, text);
    }

    void drawMenu(Canvas c) {
        fill.setShader(null);
        fill.setColor(0x55061219);
        c.drawRect(0, 0, W, H, fill);
        drawMuteButton(c);
        float cx = W * 0.58f;
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(11 * u); text.setColor(FOAM);
        text.setShadowLayer(2 * u, 0, 0, 0x88F5A93F);
        c.drawText("WATERPLANE", cx, 22 * u, text);
        text.clearShadowLayer();
        text.setTextSize(3.6f * u); text.setColor(AMBER);
        String sub = "> level 1 · harbor run" + (((time % 1.1f) < 0.6f) ? "_" : " ");
        c.drawText(sub, cx, 30 * u, text);

        float bw = Math.min(W * 0.17f, 34 * u), bh = 10 * u, gapX = 3 * u;
        float total = bw * 3 + gapX * 2, x0 = cx - total / 2, by = 38 * u;
        for (int i = 0; i < 3; i++) {
            RectF b = diffBtn[i];
            b.set(x0 + i * (bw + gapX), by, x0 + i * (bw + gapX) + bw, by + bh);
            boolean sel = i == diff;
            if (sel) { fill.setColor(0x33F5A93F); c.drawRoundRect(b, 2 * u, 2 * u, fill); }
            stroke.setColor(sel ? AMBER : 0x66E9F4F2); stroke.setStrokeWidth(0.4f * u);
            c.drawRoundRect(b, 2 * u, 2 * u, stroke);
            text.setTextSize(3.8f * u); text.setColor(sel ? AMBER : FOAM);
            c.drawText(DIFF[i], b.centerX(), b.centerY() + 1.3f * u, text);
            int best = prefs.getInt("best" + i, 0), st = prefs.getInt("stars" + i, 0);
            text.setTextSize(2.4f * u); text.setColor(0x99E9F4F2);
            c.drawText(best > 0 ? "best " + best : "—", b.centerX(), b.bottom + 3.6f * u, text);
            for (int s = 0; s < 3; s++) drawStar(c, b.centerX() + (s - 1) * 3.4f * u, b.bottom + 7.2f * u, 1.3f * u, s < st);
        }

        float pw = 40 * u, ph = 11 * u, pyy = 62 * u;
        playBtn.set(cx - pw / 2, pyy, cx + pw / 2, pyy + ph);
        float pulse = 1 + 0.03f * (float) Math.sin(time * 4);
        c.save(); c.scale(pulse, pulse, playBtn.centerX(), playBtn.centerY());
        fill.setColor(0xFFFFFFFF);
        fill.setShader(new LinearGradient(playBtn.left, 0, playBtn.right, 0, AMBER, CORAL, Shader.TileMode.CLAMP));
        c.drawRoundRect(playBtn, ph / 2, ph / 2, fill);
        fill.setShader(null);
        text.setTextSize(4.6f * u); text.setColor(NIGHT);
        c.drawText("▶  FLY", playBtn.centerX(), playBtn.centerY() + 1.6f * u, text);
        c.restore();
    }

    void drawMuteButton(Canvas c) {
        float rad = 4.5f * u;
        muteBtn.set(W - 4 * u - 2 * rad, 4 * u, W - 4 * u, 4 * u + 2 * rad);
        stroke.setColor(0x66E9F4F2); stroke.setStrokeWidth(0.35f * u);
        c.drawCircle(muteBtn.centerX(), muteBtn.centerY(), rad, stroke);
        float x = muteBtn.centerX() - 1.2f * u, y = muteBtn.centerY();
        tmp.reset();
        tmp.moveTo(x - 2 * u, y - u); tmp.lineTo(x - 0.8f * u, y - u); tmp.lineTo(x + 0.8f * u, y - 2.4f * u);
        tmp.lineTo(x + 0.8f * u, y + 2.4f * u); tmp.lineTo(x - 0.8f * u, y + u); tmp.lineTo(x - 2 * u, y + u);
        tmp.close();
        fill.setShader(null); fill.setColor(FOAM);
        c.drawPath(tmp, fill);
        if (sfx.muted) {
            stroke.setColor(CORAL);
            c.drawLine(x + 2 * u, y - 1.3f * u, x + 4.6f * u, y + 1.3f * u, stroke);
            c.drawLine(x + 2 * u, y + 1.3f * u, x + 4.6f * u, y - 1.3f * u, stroke);
        } else {
            stroke.setColor(FOAM);
            for (int i = 1; i <= 2; i++) {
                float rr = 1.4f * u * i;
                r.set(x + 0.8f * u - rr, y - rr, x + 0.8f * u + rr, y + rr);
                c.drawArc(r, -45, 90, false, stroke);
            }
        }
    }

    void drawResult(Canvas c, boolean won) {
        float a = Math.min(1, (stateTime - (won ? 1f : 0.7f)) * 4);
        fill.setShader(null); fill.setColor(DEEP); fill.setAlpha((int) (170 * a));
        c.drawRect(0, 0, W, H, fill);
        float pw = Math.min(W * 0.8f, 96 * u), ph = 66 * u;
        r.set(W / 2 - pw / 2, 17 * u, W / 2 + pw / 2, 17 * u + ph);
        fill.setColor(NIGHT); fill.setAlpha((int) (235 * a));
        c.drawRoundRect(r, 3 * u, 3 * u, fill);
        stroke.setColor(won ? AMBER : CORAL); stroke.setAlpha((int) (200 * a)); stroke.setStrokeWidth(0.4f * u);
        c.drawRoundRect(r, 3 * u, 3 * u, stroke);

        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(7.5f * u); text.setColor(won ? AMBER : CORAL);
        c.drawText(won ? "LEVEL 1 CLEAR" : "CRASHED", W / 2, 28 * u, text);
        text.setTextSize(3.2f * u); text.setColor(0xCCE9F4F2);
        c.drawText(won ? "$ exit 0 · harbor run complete" : reason, W / 2, 34 * u, text);

        if (won) for (int s = 0; s < 3; s++) {
            float pop = clamp((stateTime - 1.3f - s * 0.25f) * 5, 0, 1);
            drawStar(c, W / 2 + (s - 1) * 9 * u, 42 * u, 3.2f * u * (0.6f + 0.4f * pop), s < stars && pop > 0);
        }
        float ly = won ? 52 * u : 45 * u;
        text.setTextSize(3.4f * u); text.setColor(FOAM);
        c.drawText((int) (cam / u * 2) + " m  ·  ▌ " + cursors + "/" + cursorsTotal + "  ·  skim " + (int) skimTime + "s",
                W / 2, ly, text);
        text.setTextSize(5.5f * u); text.setColor(AMBER);
        c.drawText((newBest ? "NEW BEST  " : "SCORE  ") + score, W / 2, ly + 8 * u, text);

        float bw = 26 * u, bh = 9 * u, by = 17 * u + ph - bh - 4 * u;
        btnA.set(W / 2 - bw - 2 * u, by, W / 2 - 2 * u, by + bh);
        btnB.set(W / 2 + 2 * u, by, W / 2 + 2 * u + bw, by + bh);
        fill.setColor(0xFFFFFFFF);
        fill.setShader(new LinearGradient(btnA.left, 0, btnA.right, 0, AMBER, CORAL, Shader.TileMode.CLAMP));
        c.drawRoundRect(btnA, bh / 2, bh / 2, fill);
        fill.setShader(null);
        stroke.setColor(0x99E9F4F2);
        c.drawRoundRect(btnB, bh / 2, bh / 2, stroke);
        text.setTextSize(3.6f * u); text.setColor(NIGHT);
        c.drawText(won ? "FLY AGAIN" : "RETRY", btnA.centerX(), btnA.centerY() + 1.2f * u, text);
        text.setColor(FOAM);
        c.drawText("MENU", btnB.centerX(), btnB.centerY() + 1.2f * u, text);
    }

    void drawStar(Canvas c, float x, float y, float rad, boolean on) {
        star.reset();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float rr = i % 2 == 0 ? rad : rad * 0.45f;
            float sx = x + (float) Math.cos(a) * rr, sy = y + (float) Math.sin(a) * rr;
            if (i == 0) star.moveTo(sx, sy); else star.lineTo(sx, sy);
        }
        star.close();
        if (on) { fill.setShader(null); fill.setColor(AMBER); c.drawPath(star, fill); }
        else { stroke.setColor(0x66E9F4F2); stroke.setStrokeWidth(0.25f * u); c.drawPath(star, stroke); }
    }
}
