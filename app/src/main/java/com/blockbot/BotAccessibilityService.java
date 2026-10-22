package com.blockbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BotAccessibilityService extends AccessibilityService {
    public static BotAccessibilityService instance;

    private WindowManager wm;
    private LinearLayout overlayView;
    private TextView statusText;
    private Button toggleBtn;

    private MediaProjection projection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;

    private int screenW, screenH, screenDensity;
    private boolean isRunning = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Точная калибровка по видео:
    // 1) При касании фигура подскакивает вверх на 3.10 клетки
    // 2) По вертикали коэффициент смещения 1.12, по горизонтали (для боковых слотов) — 1.35
    private float liftCells = 3.10f;
    private float dragScaleY = 1.12f;
    private float dragScaleX = 1.35f;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        screenDensity = dm.densityDpi;
    }

    public void initProjection(MediaProjection mp) {
        stopProjection();
        this.projection = mp;
        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
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
            try {
                wm.removeView(overlayView);
            } catch (Exception ignored) {}
            overlayView = null;
        }
        Toast.makeText(this, "BlockBot закрыт", Toast.LENGTH_SHORT).show();
    }

    private Button makeSmallBtn(String label, int bgColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        b.setPadding(18, 8, 18, 8);
        b.setMinimumWidth(0);
        b.setMinWidth(0);
        b.setBackgroundColor(bgColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(6, 0, 6, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void showOverlay() {
        if (overlayView != null) return;

        overlayView = new LinearLayout(this);
        overlayView.setOrientation(LinearLayout.HORIZONTAL);
        overlayView.setBackgroundColor(Color.argb(220, 20, 25, 45));
        overlayView.setPadding(12, 8, 12, 8);
        overlayView.setGravity(Gravity.CENTER_VERTICAL);

        toggleBtn = makeSmallBtn("СТАРТ", Color.parseColor("#2E7D32"));
        toggleBtn.setOnClickListener(v -> {
            isRunning = !isRunning;
            toggleBtn.setText(isRunning ? "СТОП" : "СТАРТ");
            toggleBtn.setBackgroundColor(Color.parseColor(isRunning ? "#C62828" : "#2E7D32"));
            if (isRunning) handler.post(botLoop);
        });
        overlayView.addView(toggleBtn);

        Button btnYMinus = makeSmallBtn("Y-", Color.parseColor("#455A64"));
        btnYMinus.setOnClickListener(v -> {
            liftCells -= 0.12f;
            statusText.setText(String.format("Y:%.2f", liftCells));
        });
        overlayView.addView(btnYMinus);

        Button btnYPlus = makeSmallBtn("Y+", Color.parseColor("#455A64"));
        btnYPlus.setOnClickListener(v -> {
            liftCells += 0.12f;
            statusText.setText(String.format("Y:%.2f", liftCells));
        });
        overlayView.addView(btnYPlus);

        Button btnXMinus = makeSmallBtn("X-", Color.parseColor("#37474F"));
        btnXMinus.setOnClickListener(v -> {
            dragScaleX -= 0.05f;
            statusText.setText(String.format("X:%.2f", dragScaleX));
        });
        overlayView.addView(btnXMinus);

        Button btnXPlus = makeSmallBtn("X+", Color.parseColor("#37474F"));
        btnXPlus.setOnClickListener(v -> {
            dragScaleX += 0.05f;
            statusText.setText(String.format("X:%.2f", dragScaleX));
        });
        overlayView.addView(btnXPlus);

        statusText = new TextView(this);
        statusText.setText(" Готов ");
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(11);
        statusText.setPadding(8, 0, 8, 0);
        overlayView.addView(statusText);

        // Кнопка ЗАКРЫТЬ (X)
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

            Bitmap bmp = captureScreen();
            if (bmp == null) {
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
                    int px = (int) (gridLeft + (c + 0.5f) * cellSize);
                    int py = (int) (gridTop + (r + 0.5f) * cellSize);
                    board[r][c] = isFilledBoardCell(bmp.getPixel(px, py)) ? 1 : 0;
                }
            }

            float[] slotX = { screenW * 0.208f, screenW * 0.500f, screenW * 0.792f };
            float slotY = screenH * 0.753f;
            float dockStep = screenW * 0.050f;

            int bgSample = bmp.getPixel((int) (screenW * 0.5f), (int) (screenH * 0.675f));

            List<Piece> pieces = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Piece p = parsePiece(bmp, slotX[i], slotY, dockStep, bgSample, i);
                if (p != null) pieces.add(p);
            }
            bmp.recycle();

            if (pieces.isEmpty()) {
                statusText.setText("Жду...");
                handler.postDelayed(this, 450);
                return;
            }

            Move bestMove = findBestMoveForAllPieces(board, pieces);
            if (bestMove == null) {
                statusText.setText("Нет ходов");
                handler.postDelayed(this, 800);
                return;
            }

            statusText.setText("#" + (bestMove.piece.slotIndex + 1) +
                    " (" + bestMove.piece.rows + "x" + bestMove.piece.cols + ")" +
                    "->[" + (bestMove.row + 1) + "," + (bestMove.col + 1) + "]");

            // Центр целевой позиции фигуры на сетке 8x8
            float targetCenterX = gridLeft + (bestMove.col + bestMove.piece.cols / 2.0f) * cellSize;
            float targetCenterY = gridTop + (bestMove.row + bestMove.piece.rows / 2.0f) * cellSize;

            // Начинаем захват из фактического центра фигуры в слоте
            float startX = bestMove.piece.centerX;
            float startY = bestMove.piece.centerY;

            // Учитываем стартовый подскок фигуры вверх (liftCells) и разные коэффициенты по осям X и Y
            float liftPx = liftCells * cellSize;
            float endX = startX + (targetCenterX - startX) / dragScaleX;
            float endY = startY + (targetCenterY - (startY - liftPx)) / dragScaleY;

            performDragWithHold(startX, startY, endX, endY, () -> {
                if (isRunning) handler.postDelayed(botLoop, 650);
            });
        }
    };

    // Свайп + фиксация пальца в конечной точке на 130 мс, чтобы фигура долетела по инерции и встала в клетку
    private void performDragWithHold(float sx, float sy, float ex, float ey, Runnable onDone) {
        ex = Math.max(25, Math.min(screenW - 25, ex));
        ey = Math.max(25, Math.min(screenH - 25, ey));

        final float finalEx = ex;
        final float finalEy = ey;

        Path movePath = new Path();
        movePath.moveTo(sx, sy);
        movePath.lineTo(finalEx, finalEy);

        GestureDescription.StrokeDescription moveStroke =
                new GestureDescription.StrokeDescription(movePath, 0, 360, true);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(moveStroke);

        dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Path holdPath = new Path();
                holdPath.moveTo(finalEx, finalEy);
                holdPath.lineTo(finalEx + 0.5f, finalEy + 0.5f);

                GestureDescription.StrokeDescription holdStroke =
                        moveStroke.continueStroke(holdPath, 0, 130, false);

                GestureDescription.Builder holdBuilder = new GestureDescription.Builder();
                holdBuilder.addStroke(holdStroke);

                dispatchGesture(holdBuilder.build(), new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gd) {
                        onDone.run();
                    }
                    @Override
                    public void onCancelled(GestureDescription gd) {
                        onDone.run();
                    }
                }, null);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                onDone.run();
            }
        }, null);
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

        return diff > 48 && (hsv[1] > 0.48f || hsv[2] > 0.65f);
    }

    private Piece parsePiece(Bitmap bmp, float cx, float cy, float step, int bgColor, int slotIdx) {
        int halfW = (int) (step * 2.85f);
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int count = 0;

        for (int y = (int) cy - halfW; y <= (int) cy + halfW; y += 3) {
            for (int x = (int) cx - halfW; x <= (int) cx + halfW; x += 3) {
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
        if (count < 14) return null;

        int cols = Math.max(1, Math.min(5, Math.round((maxX - minX) / step)));
        int rows = Math.max(1, Math.min(5, Math.round((maxY - minY) / step)));

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
                if (hits >= 7) {
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
