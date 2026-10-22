package com.blockbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BotAccessibilityService extends AccessibilityService {
    public static BotAccessibilityService instance;

    private WindowManager wm;
    private LinearLayout overlayView;
    private SightOverlayView sightView;
    private TextView statusText;
    private Button toggleBtn;
    private Button modeBtn;

    private MediaProjection projection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;

    private int screenW, screenH, screenDensity;
    private boolean isRunning = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private ServerSocket localServer;

    // Параметры управления (обновляются без переустановки через Termux 127.0.0.1:8765 или GitHub)
    private float baseLiftCells = 1.98f;
    private float rowHeightLiftFactor = 0.50f;
    private float dragScaleX = 1.48f;
    private float dragScaleY = 1.26f;
    private boolean trackAndSteer = true;
    private boolean autoLearnRows = true;
    private int swipeDurationMs = 540;

    // Индивидуальные поправки для каждого из 8 рядов и 8 столбцов (в клетках поля)
    private final float[] rowBiasCells = new float[8];
    private final float[] colBiasCells = new float[8];

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        screenDensity = dm.densityDpi;
        prefs = getSharedPreferences("blockbot_cfg", Context.MODE_PRIVATE);
        loadSavedConfig();
        startLocalHotUpdateServer();
    }

    private void loadSavedConfig() {
        try {
            String jsonStr = prefs.getString("json", null);
            if (jsonStr != null) applyJsonConfig(new JSONObject(jsonStr), false);
        } catch (Exception ignored) {}
    }

    private void applyJsonConfig(JSONObject obj, boolean save) {
        try {
            if (obj.has("baseLiftCells")) baseLiftCells = (float) obj.getDouble("baseLiftCells");
            if (obj.has("rowHeightLiftFactor")) rowHeightLiftFactor = (float) obj.getDouble("rowHeightLiftFactor");
            if (obj.has("dragScaleX")) dragScaleX = (float) obj.getDouble("dragScaleX");
            if (obj.has("dragScaleY")) dragScaleY = (float) obj.getDouble("dragScaleY");
            if (obj.has("trackAndSteer")) trackAndSteer = obj.getBoolean("trackAndSteer");
            if (obj.has("autoLearnRows")) autoLearnRows = obj.getBoolean("autoLearnRows");
            if (obj.has("swipeDurationMs")) swipeDurationMs = obj.getInt("swipeDurationMs");
            if (obj.has("resetBias") && obj.getBoolean("resetBias")) {
                for (int i = 0; i < 8; i++) { rowBiasCells[i] = 0f; colBiasCells[i] = 0f; }
            }
            if (obj.has("rowBiasCells")) {
                JSONArray arr = obj.getJSONArray("rowBiasCells");
                for (int i = 0; i < Math.min(8, arr.length()); i++) rowBiasCells[i] = (float) arr.getDouble(i);
            }
            if (obj.has("colBiasCells")) {
                JSONArray arr = obj.getJSONArray("colBiasCells");
                for (int i = 0; i < Math.min(8, arr.length()); i++) colBiasCells[i] = (float) arr.getDouble(i);
            }
            if (save) saveCurrentConfig();
            handler.post(() -> {
                if (statusText != null) statusText.setText(String.format("Обновлено! Y:%.2f", dragScaleY));
                if (modeBtn != null) modeBtn.setText(trackAndSteer ? "СЛЕД:ВКЛ" : "СЛЕД:АВТО");
            });
        } catch (Exception ignored) {}
    }

    private void saveCurrentConfig() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("baseLiftCells", baseLiftCells);
            obj.put("rowHeightLiftFactor", rowHeightLiftFactor);
            obj.put("dragScaleX", dragScaleX);
            obj.put("dragScaleY", dragScaleY);
            obj.put("trackAndSteer", trackAndSteer);
            obj.put("autoLearnRows", autoLearnRows);
            obj.put("swipeDurationMs", swipeDurationMs);
            JSONArray rArr = new JSONArray();
            JSONArray cArr = new JSONArray();
            for (int i = 0; i < 8; i++) {
                rArr.put(rowBiasCells[i]);
                cArr.put(colBiasCells[i]);
            }
            obj.put("rowBiasCells", rArr);
            obj.put("colBiasCells", cArr);
            prefs.edit().putString("json", obj.toString()).apply();
        } catch (Exception ignored) {}
    }

    // Локальный сервер 127.0.0.1:8765 для мгновенного обновления настроек из Termux без переустановки APK
    private void startLocalHotUpdateServer() {
        new Thread(() -> {
            try {
                if (localServer != null) localServer.close();
                localServer = new ServerSocket(8765, 5, InetAddress.getByName("127.0.0.1"));
                while (!localServer.isClosed()) {
                    Socket client = localServer.accept();
                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                    StringBuilder body = new StringBuilder();
                    String line;
                    int contentLength = 0;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            contentLength = Integer.parseInt(line.substring(15).trim());
                        }
                    }
                    if (contentLength > 0) {
                        char[] buf = new char[contentLength];
                        int read = 0;
                        while (read < contentLength) {
                            int r = in.read(buf, read, contentLength - read);
                            if (r == -1) break;
                            read += r;
                        }
                        body.append(buf, 0, read);
                    }
                    if (body.length() > 0) {
                        applyJsonConfig(new JSONObject(body.toString()), true);
                    }
                    OutputStream out = client.getOutputStream();
                    String resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nOK\n";
                    out.write(resp.getBytes());
                    out.flush();
                    client.close();
                }
            } catch (Exception ignored) {}
        }).start();
    }

    public void initProjection(MediaProjection mp) {
        stopProjection();
        this.projection = mp;
        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3);
        virtualDisplay = projection.createVirtualDisplay("BlockBotDisplay",
                screenW, screenH, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);
        showOverlay();
    }

    private void stopProjection() {
        isRunning = false;
        handler.removeCallbacksAndMessages(null);
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
    }

    private void closeOverlayAndStop() {
        stopProjection();
        if (overlayView != null && wm != null) {
            try { wm.removeView(overlayView); } catch (Exception ignored) {}
            overlayView = null;
        }
        if (sightView != null && wm != null) {
            try { wm.removeView(sightView); } catch (Exception ignored) {}
            sightView = null;
        }
        Toast.makeText(this, "BlockBot закрыт", Toast.LENGTH_SHORT).show();
    }

    private Button makeSmallBtn(String label, int bgColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(10);
        b.setPadding(12, 6, 12, 6);
        b.setMinimumWidth(0);
        b.setMinWidth(0);
        b.setBackgroundColor(bgColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(4, 0, 4, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void showOverlay() {
        if (sightView == null) {
            sightView = new SightOverlayView(this);
            WindowManager.LayoutParams sp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            wm.addView(sightView, sp);
        }

        if (overlayView != null) return;

        overlayView = new LinearLayout(this);
        overlayView.setOrientation(LinearLayout.HORIZONTAL);
        overlayView.setBackgroundColor(Color.argb(225, 20, 25, 45));
        overlayView.setPadding(10, 6, 10, 6);
        overlayView.setGravity(Gravity.CENTER_VERTICAL);

        toggleBtn = makeSmallBtn("СТАРТ", Color.parseColor("#2E7D32"));
        toggleBtn.setOnClickListener(v -> {
            isRunning = !isRunning;
            toggleBtn.setText(isRunning ? "СТОП" : "СТАРТ");
            toggleBtn.setBackgroundColor(Color.parseColor(isRunning ? "#C62828" : "#2E7D32"));
            if (!isRunning && sightView != null) sightView.clearSight();
            if (isRunning) handler.post(botLoop);
        });
        overlayView.addView(toggleBtn);

        modeBtn = makeSmallBtn(trackAndSteer ? "СЛЕД:ВКЛ" : "СЛЕД:АВТО", Color.parseColor("#1565C0"));
        modeBtn.setOnClickListener(v -> {
            trackAndSteer = !trackAndSteer;
            modeBtn.setText(trackAndSteer ? "СЛЕД:ВКЛ" : "СЛЕД:АВТО");
            saveCurrentConfig();
        });
        overlayView.addView(modeBtn);

        Button btnYMinus = makeSmallBtn("Y-", Color.parseColor("#455A64"));
        btnYMinus.setOnClickListener(v -> {
            dragScaleY += 0.04f; // чуть ниже подъем на верхних рядах
            saveCurrentConfig();
            statusText.setText(String.format("Sy:%.2f", dragScaleY));
        });
        overlayView.addView(btnYMinus);

        Button btnYPlus = makeSmallBtn("Y+", Color.parseColor("#455A64"));
        btnYPlus.setOnClickListener(v -> {
            dragScaleY -= 0.04f; // сильнее подъем на верхних рядах
            saveCurrentConfig();
            statusText.setText(String.format("Sy:%.2f", dragScaleY));
        });
        overlayView.addView(btnYPlus);

        // Кнопка сброса/обновления калибровки
        Button btnReset = makeSmallBtn("⟳", Color.parseColor("#00695C"));
        btnReset.setOnClickListener(v -> {
            for (int i = 0; i < 8; i++) { rowBiasCells[i] = 0f; colBiasCells[i] = 0f; }
            saveCurrentConfig();
            statusText.setText("Сброс рядов");
        });
        overlayView.addView(btnReset);

        statusText = new TextView(this);
        statusText.setText(" Готов ");
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(10);
        statusText.setPadding(6, 0, 6, 0);
        overlayView.addView(statusText);

        Button btnClose = makeSmallBtn("✕", Color.parseColor("#B71C1C"));
        btnClose.setOnClickListener(v -> closeOverlayAndStop());
        overlayView.addView(btnClose);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = 75;

        wm.addView(overlayView, params);
    }

    private final Runnable botLoop = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            if (sightView != null) sightView.clearSight();

            Bitmap beforeBmp = captureScreen();
            if (beforeBmp == null) {
                handler.postDelayed(this, 300);
                return;
            }

            float gridLeft = screenW * 0.046f;
            float gridWidth = screenW * 0.908f;
            float cellSize = gridWidth / 8.0f;
            float gridTop = screenH * 0.232f;

            int[][] board = new int[8][8];
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    int cx = (int) (gridLeft + (c + 0.5f) * cellSize);
                    int cy = (int) (gridTop + (r + 0.5f) * cellSize);
                    int filledVotes = 0;
                    int offset = (int) (cellSize * 0.18f);
                    if (isFilledBoardCell(beforeBmp.getPixel(cx, cy))) filledVotes++;
                    if (isFilledBoardCell(beforeBmp.getPixel(cx - offset, cy))) filledVotes++;
                    if (isFilledBoardCell(beforeBmp.getPixel(cx + offset, cy))) filledVotes++;
                    if (isFilledBoardCell(beforeBmp.getPixel(cx, cy - offset))) filledVotes++;
                    if (isFilledBoardCell(beforeBmp.getPixel(cx, cy + offset))) filledVotes++;
                    board[r][c] = (filledVotes >= 3) ? 1 : 0;
                }
            }

            float[] slotX = { screenW * 0.208f, screenW * 0.500f, screenW * 0.792f };
            float slotY = screenH * 0.754f;
            float dockStep = screenW * 0.050f;
            int bgSample = beforeBmp.getPixel((int) (screenW * 0.5f), (int) (screenH * 0.675f));

            List<Piece> pieces = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Piece p = parsePiece(beforeBmp, slotX[i], slotY, dockStep, bgSample, i);
                if (p != null) pieces.add(p);
            }

            if (pieces.isEmpty()) {
                beforeBmp.recycle();
                statusText.setText("Жду...");
                handler.postDelayed(this, 450);
                return;
            }

            Move bestMove = findBestMoveForAllPieces(board, pieces);
            if (bestMove == null) {
                beforeBmp.recycle();
                statusText.setText("Нет ходов");
                handler.postDelayed(this, 800);
                return;
            }

            // Целевой прямоугольник и его центр на сетке 8x8
            float targetLeft = gridLeft + bestMove.col * cellSize;
            float targetTop = gridTop + bestMove.row * cellSize;
            float targetRight = targetLeft + bestMove.piece.cols * cellSize;
            float targetBottom = targetTop + bestMove.piece.rows * cellSize;
            RectF targetRect = new RectF(targetLeft, targetTop, targetRight, targetBottom);

            float targetCenterX = targetRect.centerX();
            float targetCenterY = targetRect.centerY();

            float startX = bestMove.piece.centerX;
            float startY = bestMove.piece.centerY;

            // В Block Blast фиксируется расстояние от пальца до НИЖНЕГО края фигуры (+ 0.5 * rows до центра)
            // Плюс индивидуальная авто-поправка для конкретного ряда и столбца
            float effectiveLiftCells = baseLiftCells
                    + rowHeightLiftFactor * bestMove.piece.rows
                    + rowBiasCells[bestMove.row];
            float liftPx = effectiveLiftCells * cellSize;

            float aimedTargetX = targetCenterX + colBiasCells[bestMove.col] * cellSize;
            float endX = startX + (aimedTargetX - startX) / dragScaleX;
            float endY = startY + (targetCenterY - (startY - liftPx)) / dragScaleY;

            statusText.setText("#" + (bestMove.piece.slotIndex + 1) +
                    "->[" + (bestMove.row + 1) + "," + (bestMove.col + 1) + "]");

            boolean clearsLine = willClearLine(board, bestMove.piece, bestMove.row, bestMove.col);
            long nextDelay = clearsLine ? 1250L : 700L;

            executeTrackedMove(beforeBmp, bestMove, targetRect, startX, startY, endX, endY, cellSize, () -> {
                beforeBmp.recycle();
                if (isRunning) handler.postDelayed(botLoop, nextDelay);
            });
        }
    };

    // Выполняет ход со слежением за реальным положением фигуры на поле и авто-коррекцией
    private void executeTrackedMove(Bitmap beforeBmp, Move move, RectF targetRect,
                                    float sx, float sy, float ex, float ey, float cellSize,
                                    Runnable onDone) {
        final float clampedEx = Math.max(25, Math.min(screenW - 25, ex));
        final float clampedEy = Math.max(25, Math.min(screenH - 25, ey));

        if (trackAndSteer) {
            // Режим 1: Захват -> подвод к цели -> визуальная проверка -> доводка пальцем -> сброс
            Path step1Path = new Path();
            step1Path.moveTo(sx, sy);
            // Небольшая задержка на старте (12% длины в начале) + подвод к расчетной точке
            step1Path.lineTo(sx, sy - 6f);
            step1Path.lineTo(clampedEx, clampedEy);

            GestureDescription.StrokeDescription stroke1 =
                    new GestureDescription.StrokeDescription(step1Path, 0, 320, true);
            GestureDescription.Builder b1 = new GestureDescription.Builder();
            b1.addStroke(stroke1);

            dispatchGesture(b1.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gd) {
                    // Удерживаем палец 95 мс, чтобы фигура стабилизировалась, и снимаем кадр
                    Path holdPath = new Path();
                    holdPath.moveTo(clampedEx, clampedEy);
                    holdPath.lineTo(clampedEx + 1.5f, clampedEy + 1.5f);
                    GestureDescription.StrokeDescription strokeHold =
                            stroke1.continueStroke(holdPath, 0, 95, true);
                    GestureDescription.Builder bHold = new GestureDescription.Builder();
                    bHold.addStroke(strokeHold);

                    dispatchGesture(bHold.build(), new GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gd2) {
                            Bitmap liveBmp = captureScreen();
                            RectF detected = (liveBmp != null)
                                    ? detectFloatingPiece(beforeBmp, liveBmp, move.piece, cellSize)
                                    : null;
                            if (liveBmp != null) liveBmp.recycle();

                            float corrEx = clampedEx;
                            float corrEy = clampedEy;

                            if (detected != null) {
                                float errX = targetRect.centerX() - detected.centerX();
                                float errY = targetRect.centerY() - detected.centerY();

                                if (sightView != null) {
                                    sightView.showSight(targetRect, detected, corrEx, corrEy);
                                    handler.postDelayed(() -> {
                                        if (sightView != null) sightView.clearSight();
                                    }, 450);
                                }

                                // Авто-обучение поправки для этого ряда и столбца
                                if (autoLearnRows && Math.abs(errY) < cellSize * 3.5f) {
                                    rowBiasCells[move.row] -= (errY / cellSize) * 0.45f;
                                    rowBiasCells[move.row] = Math.max(-2.5f, Math.min(2.5f, rowBiasCells[move.row]));
                                    colBiasCells[move.col] += (errX / cellSize) * 0.35f;
                                    colBiasCells[move.col] = Math.max(-2.0f, Math.min(2.0f, colBiasCells[move.col]));
                                    saveCurrentConfig();
                                }

                                // Доводим палец в реальном времени, если фигура смещена больше чем на 0.22 клетки
                                if (Math.abs(errX) > cellSize * 0.22f || Math.abs(errY) > cellSize * 0.22f) {
                                    corrEx = Math.max(25, Math.min(screenW - 25, clampedEx + (errX / dragScaleX)));
                                    corrEy = Math.max(25, Math.min(screenH - 25, clampedEy + (errY / dragScaleY)));
                                }
                            }

                            // Финальная доводка + мягкая фиксация на месте перед отпусканием
                            Path finalPath = buildWiggleHoldPath(clampedEx + 1.5f, clampedEy + 1.5f, corrEx, corrEy, 6);
                            GestureDescription.StrokeDescription strokeFinal =
                                    strokeHold.continueStroke(finalPath, 0, 210, false);
                            GestureDescription.Builder bFinal = new GestureDescription.Builder();
                            bFinal.addStroke(strokeFinal);

                            dispatchGesture(bFinal.build(), new GestureResultCallback() {
                                @Override
                                public void onCompleted(GestureDescription gd3) { onDone.run(); }
                                @Override
                                public void onCancelled(GestureDescription gd3) { onDone.run(); }
                            }, null);
                        }

                        @Override
                        public void onCancelled(GestureDescription gd2) {
                            // Если система не поддерживает continueStroke, автоматически переключаемся на одиночный свайп с авто-калибровкой
                            trackAndSteer = false;
                            if (modeBtn != null) modeBtn.setText("СЛЕД:АВТО");
                            onDone.run();
                        }
                    }, null);
                }

                @Override
                public void onCancelled(GestureDescription gd) {
                    onDone.run();
                }
            }, null);
        } else {
            // Режим 2 (СЛЕД:АВТО): Единый непрерывный жест с настоящим удержанием 180 мс в конце пути
            // и фото-контролем на 400-й миллисекунде для авто-калибровки каждого ряда
            Path singlePath = buildWiggleHoldPath(sx, sy, clampedEx, clampedEy, 18);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(singlePath, 0, swipeDurationMs);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(stroke);

            // Снимаем кадр в момент, когда палец удерживает фигуру в конечной точке (за 110 мс до отпускания)
            handler.postDelayed(() -> {
                Bitmap liveBmp = captureScreen();
                if (liveBmp != null) {
                    RectF detected = detectFloatingPiece(beforeBmp, liveBmp, move.piece, cellSize);
                    liveBmp.recycle();
                    if (detected != null) {
                        float errX = targetRect.centerX() - detected.centerX();
                        float errY = targetRect.centerY() - detected.centerY();
                        if (sightView != null) {
                            sightView.showSight(targetRect, detected, clampedEx, clampedEy);
                            handler.postDelayed(() -> {
                                if (sightView != null) sightView.clearSight();
                            }, 420);
                        }
                        if (autoLearnRows && Math.abs(errY) < cellSize * 3.5f) {
                            rowBiasCells[move.row] -= (errY / cellSize) * 0.50f;
                            rowBiasCells[move.row] = Math.max(-2.5f, Math.min(2.5f, rowBiasCells[move.row]));
                            colBiasCells[move.col] += (errX / cellSize) * 0.35f;
                            colBiasCells[move.col] = Math.max(-2.0f, Math.min(2.0f, colBiasCells[move.col]));
                            saveCurrentConfig();
                        }
                    }
                }
            }, swipeDurationMs - 110);

            dispatchGesture(builder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gd) { onDone.run(); }
                @Override
                public void onCancelled(GestureDescription gd) { onDone.run(); }
            }, null);
        }
    }

    // Создает путь, который гарантированно удерживает палец в конечной точке (ex, ey) перед отпусканием
    private Path buildWiggleHoldPath(float sx, float sy, float ex, float ey, int holdLoops) {
        Path p = new Path();
        p.moveTo(sx, sy);
        p.lineTo(ex, ey);
        float dist = (float) Math.hypot(ex - sx, ey - sy);
        // Подбираем амплитуду микро-удержания так, чтобы PathMeasure выделил ~35% времени на фиксацию в (ex, ey)
        float step = Math.max(2.0f, Math.min(4.5f, (dist * 0.35f) / Math.max(1, holdLoops * 2)));
        for (int i = 0; i < holdLoops; i++) {
            p.lineTo(ex + step, ey);
            p.lineTo(ex - step, ey);
        }
        p.lineTo(ex, ey);
        return p;
    }

    // Находит реальное положение летящей фигуры на экране путем сравнения с кадром до начала хода
    private RectF detectFloatingPiece(Bitmap beforeBmp, Bitmap liveBmp, Piece piece, float cellSize) {
        int topLimit = (int) (screenH * 0.14f);
        int bottomLimit = (int) (screenH * 0.67f);
        int leftLimit = (int) (screenW * 0.02f);
        int rightLimit = (int) (screenW * 0.98f);

        List<Integer> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();

        for (int y = topLimit; y <= bottomLimit; y += 5) {
            for (int x = leftLimit; x <= rightLimit; x += 5) {
                int cNow = liveBmp.getPixel(x, y);
                int cBefore = beforeBmp.getPixel(x, y);

                int diff = Math.abs(Color.red(cNow) - Color.red(cBefore))
                        + Math.abs(Color.green(cNow) - Color.green(cBefore))
                        + Math.abs(Color.blue(cNow) - Color.blue(cBefore));
                if (diff < 65) continue;

                float[] hsv = new float[3];
                Color.colorToHSV(cNow, hsv);
                // Игнорируем серый кружок касания пальца (у него низкая насыщенность) и берем только яркие блоки фигуры
                if (hsv[1] > 0.42f && hsv[2] > 0.55f) {
                    xs.add(x);
                    ys.add(y);
                }
            }
        }

        if (xs.size() < 12) return null;
        Collections.sort(xs);
        Collections.sort(ys);

        // Берем 10%..90% квантили для защиты от случайных бликов
        int minX = xs.get((int) (xs.size() * 0.08f));
        int maxX = xs.get((int) (xs.size() * 0.92f));
        int minY = ys.get((int) (ys.size() * 0.08f));
        int maxY = ys.get((int) (ys.size() * 0.92f));

        float cx = (minX + maxX) / 2.0f;
        float cy = (minY + maxY) / 2.0f;
        float halfW = (piece.cols * cellSize) / 2.0f;
        float halfH = (piece.rows * cellSize) / 2.0f;

        return new RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
    }

    // Прозрачный прицел поверх экрана (Зеленый = куда ставим, Желтый = где бот видит фигуру)
    private static class SightOverlayView extends View {
        private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint detectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fingerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private RectF targetRect;
        private RectF detectedRect;
        private float fingerX, fingerY;

        public SightOverlayView(Context context) {
            super(context);
            targetPaint.setStyle(Paint.Style.STROKE);
            targetPaint.setStrokeWidth(5f);
            targetPaint.setColor(Color.parseColor("#00E676")); // Ярко-зеленый

            detectedPaint.setStyle(Paint.Style.STROKE);
            detectedPaint.setStrokeWidth(5f);
            detectedPaint.setColor(Color.parseColor("#FFEA00")); // Ярко-желтый

            fingerPaint.setStyle(Paint.Style.FILL);
            fingerPaint.setColor(Color.parseColor("#FF1744")); // Красная точка пальца
        }

        public void showSight(RectF target, RectF detected, float fx, float fy) {
            this.targetRect = target;
            this.detectedRect = detected;
            this.fingerX = fx;
            this.fingerY = fy;
            postInvalidate();
        }

        public void clearSight() {
            this.targetRect = null;
            this.detectedRect = null;
            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (targetRect != null) canvas.drawRect(targetRect, targetPaint);
            if (detectedRect != null) canvas.drawRect(detectedRect, detectedPaint);
            if (targetRect != null) canvas.drawCircle(fingerX, fingerY, 10f, fingerPaint);
        }
    }

    private boolean isFilledBoardCell(int color) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        return (r + g + b) > 140 || b > 110 || r > 90;
    }

    private boolean isPiecePixel(int color, int bgColor) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        int bgR = Color.red(bgColor), bgG = Color.green(bgColor), bgB = Color.blue(bgColor);

        if (r <= bgR + 8 && g <= bgG + 8 && b <= bgB + 12) return false;

        int diff = Math.abs(r - bgR) + Math.abs(g - bgG) + Math.abs(b - bgB);
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);

        return diff > 45 && (hsv[1] > 0.45f || hsv[2] > 0.62f);
    }

    private Piece parsePiece(Bitmap bmp, float cx, float cy, float step, int bgColor, int slotIdx) {
        int halfW = (int) (step * 2.85f);
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int count = 0;

        for (int y = (int) cy - halfW; y <= (int) cy + halfW; y += 2) {
            for (int x = (int) cx - halfW; x <= (int) cx + halfW; x += 2) {
                if (x < 0 || x >= screenW || y < 0 || y >= screenH) continue;
                if (isPiecePixel(bmp.getPixel(x, y), bgColor)) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                    count++;
                }
            }
        }
        if (count < 16) return null;

        int cols = Math.max(1, Math.min(5, Math.round((maxX - minX) / step + 0.12f)));
        int rows = Math.max(1, Math.min(5, Math.round((maxY - minY) / step + 0.12f)));

        int[][] shape = new int[rows][cols];
        float boxW = (float) (maxX - minX) / cols;
        float boxH = (float) (maxY - minY) / rows;
        int filledBlocks = 0;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int sx = (int) (minX + (c + 0.5f) * boxW);
                int sy = (int) (minY + (r + 0.5f) * boxH);
                int hits = 0;
                for (int dy = -6; dy <= 6; dy += 3) {
                    for (int dx = -6; dx <= 6; dx += 3) {
                        int px = Math.max(0, Math.min(screenW - 1, sx + dx));
                        int py = Math.max(0, Math.min(screenH - 1, sy + dy));
                        if (isPiecePixel(bmp.getPixel(px, py), bgColor)) hits++;
                    }
                }
                if (hits >= 6) {
                    shape[r][c] = 1;
                    filledBlocks++;
                }
            }
        }
        if (filledBlocks == 0) return null;

        Piece p = new Piece();
        p.slotIndex = slotIdx;
        p.rows = rows;
        p.cols = cols;
        p.shape = shape;
        p.centerX = (minX + maxX) / 2.0f;
        p.centerY = (minY + maxY) / 2.0f;
        return p;
    }

    private boolean willClearLine(int[][] board, Piece p, int row, int col) {
        int[][] next = new int[8][8];
        for (int r = 0; r < 8; r++) System.arraycopy(board[r], 0, next[r], 0, 8);
        for (int r = 0; r < p.rows; r++) {
            for (int c = 0; c < p.cols; c++) {
                if (p.shape[r][c] == 1) next[row + r][col + c] = 1;
            }
        }
        for (int r = 0; r < 8; r++) {
            boolean full = true;
            for (int c = 0; c < 8; c++) if (next[r][c] == 0) full = false;
            if (full) return true;
        }
        for (int c = 0; c < 8; c++) {
            boolean full = true;
            for (int r = 0; r < 8; r++) if (next[r][c] == 0) full = false;
            if (full) return true;
        }
        return false;
    }

    private Move findBestMoveForAllPieces(int[][] board, List<Piece> pieces) {
        List<int[]> orders = getPermutations(pieces.size());
        Move bestFirstMove = null;
        double bestTotalScore = -1e12;

        for (int[] order : orders) {
            Piece p1 = pieces.get(order[0]);
            List<State> step1States = new ArrayList<>();

            for (int r1 = 0; r1 <= 8 - p1.rows; r1++) {
                for (int c1 = 0; c1 <= 8 - p1.cols; c1++) {
                    if (!canPlace(board, p1, r1, c1)) continue;
                    int[][] b1 = placeAndClear(board, p1, r1, c1);
                    Move firstMove = new Move(p1, r1, c1);
                    step1States.add(new State(b1, evaluateBoard(b1), firstMove));
                }
            }
            if (step1States.isEmpty()) continue;

            Collections.sort(step1States, (a, b) -> Double.compare(b.score, a.score));
            if (step1States.size() > 14) step1States = step1States.subList(0, 14);

            if (pieces.size() == 1) {
                if (step1States.get(0).score > bestTotalScore) {
                    bestTotalScore = step1States.get(0).score;
                    bestFirstMove = step1States.get(0).firstMove;
                }
                continue;
            }

            Piece p2 = pieces.get(order[1]);
            List<State> step2States = new ArrayList<>();
            for (State s1 : step1States) {
                for (int r2 = 0; r2 <= 8 - p2.rows; r2++) {
                    for (int c2 = 0; c2 <= 8 - p2.cols; c2++) {
                        if (!canPlace(s1.board, p2, r2, c2)) continue;
                        int[][] b2 = placeAndClear(s1.board, p2, r2, c2);
                        step2States.add(new State(b2, evaluateBoard(b2), s1.firstMove));
                    }
                }
            }
            if (step2States.isEmpty()) {
                if (step1States.get(0).score - 50000 > bestTotalScore) {
                    bestTotalScore = step1States.get(0).score - 50000;
                    bestFirstMove = step1States.get(0).firstMove;
                }
                continue;
            }

            Collections.sort(step2States, (a, b) -> Double.compare(b.score, a.score));
            if (step2States.size() > 14) step2States = step2States.subList(0, 14);

            if (pieces.size() == 2) {
                if (step2States.get(0).score > bestTotalScore) {
                    bestTotalScore = step2States.get(0).score;
                    bestFirstMove = step2States.get(0).firstMove;
                }
                continue;
            }

            Piece p3 = pieces.get(order[2]);
            boolean foundStep3 = false;
            for (State s2 : step2States) {
                for (int r3 = 0; r3 <= 8 - p3.rows; r3++) {
                    for (int c3 = 0; c3 <= 8 - p3.cols; c3++) {
                        if (!canPlace(s2.board, p3, r3, c3)) continue;
                        foundStep3 = true;
                        int[][] b3 = placeAndClear(s2.board, p3, r3, c3);
                        double s3 = evaluateBoard(b3);
                        if (s3 > bestTotalScore) {
                            bestTotalScore = s3;
                            bestFirstMove = s2.firstMove;
                        }
                    }
                }
            }
            if (!foundStep3 && step2States.get(0).score - 25000 > bestTotalScore) {
                bestTotalScore = step2States.get(0).score - 25000;
                bestFirstMove = step2States.get(0).firstMove;
            }
        }
        return bestFirstMove;
    }

    private List<int[]> getPermutations(int n) {
        List<int[]> res = new ArrayList<>();
        if (n == 1) {
            res.add(new int[]{0});
        } else if (n == 2) {
            res.add(new int[]{0, 1});
            res.add(new int[]{1, 0});
        } else if (n == 3) {
            res.add(new int[]{0, 1, 2});
            res.add(new int[]{0, 2, 1});
            res.add(new int[]{1, 0, 2});
            res.add(new int[]{1, 2, 0});
            res.add(new int[]{2, 0, 1});
            res.add(new int[]{2, 1, 0});
        }
        return res;
    }

    private boolean canPlace(int[][] board, Piece p, int row, int col) {
        for (int r = 0; r < p.rows; r++) {
            for (int c = 0; c < p.cols; c++) {
                if (p.shape[r][c] == 1 && board[row + r][col + c] == 1) return false;
            }
        }
        return true;
    }

    private int[][] placeAndClear(int[][] board, Piece p, int row, int col) {
        int[][] next = new int[8][8];
        for (int r = 0; r < 8; r++) System.arraycopy(board[r], 0, next[r], 0, 8);

        for (int r = 0; r < p.rows; r++) {
            for (int c = 0; c < p.cols; c++) {
                if (p.shape[r][c] == 1) next[row + r][col + c] = 1;
            }
        }

        boolean[] fullRow = new boolean[8];
        boolean[] fullCol = new boolean[8];
        for (int r = 0; r < 8; r++) {
            boolean full = true;
            for (int c = 0; c < 8; c++) if (next[r][c] == 0) full = false;
            fullRow[r] = full;
        }
        for (int c = 0; c < 8; c++) {
            boolean full = true;
            for (int r = 0; r < 8; r++) if (next[r][c] == 0) full = false;
            fullCol[c] = full;
        }

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if (fullRow[r] || fullCol[c]) next[r][c] = 0;
            }
        }
        return next;
    }

    private double evaluateBoard(int[][] b) {
        int empty = 0, holes = 0, space3x3 = 0, clearLines5 = 0;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if (b[r][c] == 0) {
                    empty++;
                    int blockedNeighbors = 0;
                    if (r == 0 || b[r - 1][c] == 1) blockedNeighbors++;
                    if (r == 7 || b[r + 1][c] == 1) blockedNeighbors++;
                    if (c == 0 || b[r][c - 1] == 1) blockedNeighbors++;
                    if (c == 7 || b[r][c + 1] == 1) blockedNeighbors++;
                    if (blockedNeighbors >= 3) holes++;
                }
            }
        }
        for (int r = 0; r <= 5; r++) {
            for (int c = 0; c <= 5; c++) {
                boolean clear = true;
                for (int dr = 0; dr < 3 && clear; dr++)
                    for (int dc = 0; dc < 3 && clear; dc++)
                        if (b[r + dr][c + dc] == 1) clear = false;
                if (clear) space3x3++;
            }
        }
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c <= 3; c++) {
                if (b[r][c] == 0 && b[r][c+1] == 0 && b[r][c+2] == 0 && b[r][c+3] == 0 && b[r][c+4] == 0)
                    clearLines5++;
                if (b[c][r] == 0 && b[c+1][r] == 0 && b[c+2][r] == 0 && b[c+3][r] == 0 && b[c+4][r] == 0)
                    clearLines5++;
            }
        }
        return empty * 12.0 + space3x3 * 30.0 + clearLines5 * 8.0 - holes * 22.0;
    }

    private Bitmap captureScreen() {
        if (imageReader == null) return null;
        Image image = imageReader.acquireLatestImage();
        if (image == null) return null;
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * screenW;

        Bitmap bitmap = Bitmap.createBitmap(
                screenW + rowPadding / pixelStride, screenH, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        image.close();
        Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, screenW, screenH);
        if (cropped != bitmap) bitmap.recycle();
        return cropped;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    private static class Piece {
        int slotIndex, rows, cols;
        int[][] shape;
        float centerX, centerY;
    }

    private static class Move {
        Piece piece;
        int row, col;
        Move(Piece p, int r, int c) { piece = p; row = r; col = c; }
    }

    private static class State {
        int[][] board;
        double score;
        Move firstMove;
        State(int[][] b, double s, Move m) { board = b; score = s; firstMove = m; }
    }
}
