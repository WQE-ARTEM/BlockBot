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
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
    private Button speedBtn;

    private MediaProjection projection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;

    private int screenW, screenH, screenDensity;
    private boolean isRunning = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private ServerSocket localServer;

    private float baseLiftCells = 2.38f;
    private float rowHeightLiftFactor = 0.16f;
    private float dragScaleX = 1.48f;
    private float dragScaleY = 1.28f;
    private boolean autoLearnRows = false;
    private int dragDurationMs = 650;
    private int holdDurationMs = 380;
    private boolean useTwoStageHold = true;

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
        prefs = getSharedPreferences("blockbot_cfg_v3", Context.MODE_PRIVATE);
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
            if (obj.has("autoLearnRows")) autoLearnRows = obj.getBoolean("autoLearnRows");
            if (obj.has("dragDurationMs")) dragDurationMs = obj.getInt("dragDurationMs");
            if (obj.has("holdDurationMs")) holdDurationMs = obj.getInt("holdDurationMs");
            if (obj.has("useTwoStageHold")) useTwoStageHold = obj.getBoolean("useTwoStageHold");
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
                if (statusText != null) statusText.setText("CFG OK!");
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
            obj.put("autoLearnRows", autoLearnRows);
            obj.put("dragDurationMs", dragDurationMs);
            obj.put("holdDurationMs", holdDurationMs);
            obj.put("useTwoStageHold", useTwoStageHold);
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

        speedBtn = makeSmallBtn("ПАУЗА:" + holdDurationMs, Color.parseColor("#1565C0"));
        speedBtn.setOnClickListener(v -> {
            if (holdDurationMs == 380) {
                dragDurationMs = 800;
                holdDurationMs = 520;
            } else if (holdDurationMs == 520) {
                dragDurationMs = 950;
                holdDurationMs = 650;
            } else {
                dragDurationMs = 650;
                holdDurationMs = 380;
            }
            speedBtn.setText("ПАУЗА:" + holdDurationMs);
            saveCurrentConfig();
        });
        overlayView.addView(speedBtn);

        Button btnYMinus = makeSmallBtn("Y-", Color.parseColor("#455A64"));
        btnYMinus.setOnClickListener(v -> {
            baseLiftCells -= 0.12f;
            saveCurrentConfig();
            statusText.setText(String.format("L:%.2f", baseLiftCells));
        });
        overlayView.addView(btnYMinus);

        Button btnYPlus = makeSmallBtn("Y+", Color.parseColor("#455A64"));
        btnYPlus.setOnClickListener(v -> {
            baseLiftCells += 0.12f;
            saveCurrentConfig();
            statusText.setText(String.format("L:%.2f", baseLiftCells));
        });
        overlayView.addView(btnYPlus);

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
            int bgSample = beforeBmp.getPixel((int) (screenW * 0.5f), (int) (screenH * 0.675f));

            List<Piece> pieces = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Piece p = parsePieceExact(beforeBmp, slotX[i], slotY, bgSample, i);
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

            float targetLeft = gridLeft + bestMove.col * cellSize;
            float targetTop = gridTop + bestMove.row * cellSize;
            float targetRight = targetLeft + bestMove.piece.cols * cellSize;
            float targetBottom = targetTop + bestMove.piece.rows * cellSize;
            RectF targetRect = new RectF(targetLeft, targetTop, targetRight, targetBottom);

            float targetCenterX = targetRect.centerX();
            float targetCenterY = targetRect.centerY();

            float startX = bestMove.piece.centerX;
            float startY = bestMove.piece.centerY;

            float effectiveLiftCells = baseLiftCells
                    + rowHeightLiftFactor * (bestMove.piece.rows - 1)
                    + rowBiasCells[bestMove.row];
            float liftPx = effectiveLiftCells * cellSize;

            float aimedTargetX = targetCenterX + colBiasCells[bestMove.col] * cellSize;
            float endX = startX + (aimedTargetX - startX) / dragScaleX;
            float endY = startY + (targetCenterY - (startY - liftPx)) / dragScaleY;

            statusText.setText("#" + (bestMove.piece.slotIndex + 1) +
                    "(" + bestMove.piece.rows + "x" + bestMove.piece.cols + ")" +
                    "->[" + (bestMove.row + 1) + "," + (bestMove.col + 1) + "]");

            boolean clearsLine = willClearLine(board, bestMove.piece, bestMove.row, bestMove.col);
            long nextDelay = clearsLine ? 1300L : 750L;

            executeMoveWithFullStop(beforeBmp, bestMove, targetRect, startX, startY, endX, endY, cellSize, () -> {
                beforeBmp.recycle();
                if (isRunning) handler.postDelayed(botLoop, nextDelay);
            });
        }
    };

    private void executeMoveWithFullStop(Bitmap beforeBmp, Move move, RectF targetRect,
                                         float sx, float sy, float ex, float ey, float cellSize,
                                         Runnable onDone) {
        final float clampedEx = Math.max(25, Math.min(screenW - 25, ex));
        final float clampedEy = Math.max(25, Math.min(screenH - 25, ey));

        if (sightView != null) {
            sightView.showSight(targetRect, null, clampedEx, clampedEy);
        }

        long snapshotTime = dragDurationMs + Math.max(80, holdDurationMs - 120);
        handler.postDelayed(() -> {
            if (!isRunning) return;
            Bitmap liveBmp = captureScreen();
            if (liveBmp != null) {
                RectF detected = detectFloatingPiece(beforeBmp, liveBmp, move.piece, cellSize);
                liveBmp.recycle();
                if (detected != null && sightView != null) {
                    sightView.showSight(targetRect, detected, clampedEx, clampedEy);
                    if (autoLearnRows) {
                        float errY = targetRect.centerY() - detected.centerY();
                        if (Math.abs(errY) < cellSize * 2.5f) {
                            rowBiasCells[move.row] -= (errY / cellSize) * 0.35f;
                            rowBiasCells[move.row] = Math.max(-2.0f, Math.min(2.0f, rowBiasCells[move.row]));
                            saveCurrentConfig();
                        }
                    }
                }
            }
        }, snapshotTime);

        if (useTwoStageHold) {
            Path dragPath = new Path();
            dragPath.moveTo(sx, sy);
            dragPath.lineTo(sx + (clampedEx - sx) * 0.85f, sy + (clampedEy - sy) * 0.85f);
            dragPath.lineTo(clampedEx, clampedEy);

            Path holdPath = new Path();
            holdPath.moveTo(clampedEx, clampedEy);
            holdPath.lineTo(clampedEx + 0.4f, clampedEy + 0.4f);

            final GestureDescription.StrokeDescription dragStroke =
                    new GestureDescription.StrokeDescription(dragPath, 0, dragDurationMs, true);
            final GestureDescription.StrokeDescription holdStroke =
                    dragStroke.continueStroke(holdPath, 0, holdDurationMs, false);

            GestureDescription.Builder dragBuilder = new GestureDescription.Builder();
            dragBuilder.addStroke(dragStroke);

            final GestureDescription holdGesture = new GestureDescription.Builder()
                    .addStroke(holdStroke)
                    .build();

            dispatchGesture(dragBuilder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gd) {
                    dispatchGesture(holdGesture, new GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gd2) {
                            if (sightView != null) sightView.clearSight();
                            onDone.run();
                        }
                        @Override
                        public void onCancelled(GestureDescription gd2) {
                            useTwoStageHold = false;
                            if (sightView != null) sightView.clearSight();
                            onDone.run();
                        }
                    }, null);
                }
                @Override
                public void onCancelled(GestureDescription gd) {
                    if (sightView != null) sightView.clearSight();
                    onDone.run();
                }
            }, null);
        } else {
            Path singlePath = buildMicroHoldPath(sx, sy, clampedEx, clampedEy);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(singlePath, 0, dragDurationMs + holdDurationMs);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(stroke);
            dispatchGesture(b.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gd) {
                    if (sightView != null) sightView.clearSight();
                    onDone.run();
                }
                @Override
                public void onCancelled(GestureDescription gd) {
                    if (sightView != null) sightView.clearSight();
                    onDone.run();
                }
            }, null);
        }
    }

    private Path buildMicroHoldPath(float sx, float sy, float ex, float ey) {
        Path p = new Path();
        p.moveTo(sx, sy);
        p.lineTo(ex, ey);
        float dist = (float) Math.hypot(ex - sx, ey - sy);
        float holdLengthNeeded = dist * ((float) holdDurationMs / Math.max(200, dragDurationMs));
        float box = 0.8f;
        int loops = Math.max(10, Math.min(90, (int) (holdLengthNeeded / (box * 4f))));
        for (int i = 0; i < loops; i++) {
            p.lineTo(ex + box, ey);
            p.lineTo(ex + box, ey + box);
            p.lineTo(ex, ey + box);
            p.lineTo(ex, ey);
        }
        return p;
    }

    private int classifySpanBlocks(float spanPx) {
        float ratio = spanPx / (float) screenW;
        if (ratio < 0.078f) return 1;
        if (ratio < 0.128f) return 2;
        if (ratio < 0.178f) return 3;
        if (ratio < 0.209f) return 4;
        return 5;
    }

    // НОВЫЙ БРОНЕБОЙНЫЙ АЛГОРИТМ ДЛЯ ЛЮБЫХ СКИНОВ (Пустые клетки всегда темные, блоки яркие)
    private boolean isFilledBoardCell(int color) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        // Отсекаем любой пустой фон (даже светлые скины). Блоки всегда имеют высокую яркость.
        return hsv[2] > 0.38f && (r + g + b) > 150;
    }

    private boolean isPiecePixel(int color, int bgColor) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        int bgR = Color.red(bgColor), bgG = Color.green(bgColor), bgB = Color.blue(bgColor);

        if (r <= bgR + 12 && g <= bgG + 12 && b <= bgB + 15) return false;

        int diff = Math.abs(r - bgR) + Math.abs(g - bgG) + Math.abs(b - bgB);
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);

        return diff > 35 && (hsv[1] > 0.35f || hsv[2] > 0.50f);
    }

    private Piece parsePieceExact(Bitmap bmp, float cx, float cy, int bgColor, int slotIdx) {
        int halfW = (int) (screenW * 0.142f);
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

        int cols = classifySpanBlocks(maxX - minX);
        int rows = classifySpanBlocks(maxY - minY);

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
                // Понизили порог до 4 для скинов с толстыми рамками между кубиками
                if (hits >= 4) {
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

    private RectF detectFloatingPiece(Bitmap beforeBmp, Bitmap liveBmp, Piece piece, float cellSize) {
        int topLimit = (int) (screenH * 0.16f);
        int bottomLimit = (int) (screenH * 0.66f);
        int leftLimit = (int) (screenW * 0.03f);
        int rightLimit = (int) (screenW * 0.97f);

        List<Integer> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();

        for (int y = topLimit; y <= bottomLimit; y += 5) {
            for (int x = leftLimit; x <= rightLimit; x += 5) {
                int cNow = liveBmp.getPixel(x, y);
                int cBefore = beforeBmp.getPixel(x, y);

                int diff = Math.abs(Color.red(cNow) - Color.red(cBefore))
                        + Math.abs(Color.green(cNow) - Color.green(cBefore))
                        + Math.abs(Color.blue(cNow) - Color.blue(cBefore));
                if (diff < 70) continue;

                float[] hsv = new float[3];
                Color.colorToHSV(cNow, hsv);
                if (hsv[1] > 0.42f && hsv[2] > 0.55f) {
                    xs.add(x);
                    ys.add(y);
                }
            }
        }

        if (xs.size() < 12) return null;
        Collections.sort(xs);
        Collections.sort(ys);

        int minX = xs.get((int) (xs.size() * 0.10f));
        int maxX = xs.get((int) (xs.size() * 0.90f));
        int minY = ys.get((int) (ys.size() * 0.10f));
        int maxY = ys.get((int) (ys.size() * 0.90f));

        if ((maxX - minX) > cellSize * 6.2f || (maxY - minY) > cellSize * 6.2f) return null;

        float cx = (minX + maxX) / 2.0f;
        float cy = (minY + maxY) / 2.0f;
        float halfW = (piece.cols * cellSize) / 2.0f;
        float halfH = (piece.rows * cellSize) / 2.0f;

        return new RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
    }

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
            targetPaint.setColor(Color.parseColor("#00E676"));

            detectedPaint.setStyle(Paint.Style.STROKE);
            detectedPaint.setStrokeWidth(5f);
            detectedPaint.setColor(Color.parseColor("#FFEA00"));

            fingerPaint.setStyle(Paint.Style.FILL);
            fingerPaint.setColor(Color.parseColor("#FF1744"));
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
