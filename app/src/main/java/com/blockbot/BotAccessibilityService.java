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

    // Точные коэффициенты, вычисленные по видео с точкой касания:
    // 1) При касании слота фигура подскакивает вверх на 2.48 клетки
    // 2) По горизонтали фигура движется в 1.50 раза быстрее пальца
    // 3) По вертикали фигура движется в 1.65 раза быстрее пальца
    private float liftCells = 2.48f;
    private float dragScaleX = 1.50f;
    private float dragScaleY = 1.65f;

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
        b.setTextSize(11);
        b.setPadding(16, 6, 16, 6);
        b.setMinimumWidth(0);
        b.setMinWidth(0);
        b.setBackgroundColor(bgColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(5, 0, 5, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void showOverlay() {
        if (overlayView != null) return;

        overlayView = new LinearLayout(this);
        overlayView.setOrientation(LinearLayout.HORIZONTAL);
        overlayView.setBackgroundColor(Color.argb(220, 20, 25, 45));
        overlayView.setPadding(10, 8, 10, 8);
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
            liftCells -= 0.10f;
            statusText.setText(String.format("Y:%.2f", liftCells));
        });
        overlayView.addView(btnYMinus);

        Button btnYPlus = makeSmallBtn("Y+", Color.parseColor("#455A64"));
        btnYPlus.setOnClickListener(v -> {
            liftCells += 0.10f;
            statusText.setText(String.format("Y:%.2f", liftCells));
        });
        overlayView.addView(btnYPlus);

        Button btnXMinus = makeSmallBtn("X-", Color.parseColor("#37474F"));
        btnXMinus.setOnClickListener(v -> {
            dragScaleX -= 0.04f;
            statusText.setText(String.format("X:%.2f", dragScaleX));
        });
        overlayView.addView(btnXMinus);

        Button btnXPlus = makeSmallBtn("X+", Color.parseColor("#37474F"));
        btnXPlus.setOnClickListener(v -> {
            dragScaleX += 0.04f;
            statusText.setText(String.format("X:%.2f", dragScaleX));
        });
        overlayView.addView(btnXPlus);

        statusText = new TextView(this);
        statusText.setText(" Готов ");
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(11);
        statusText.setPadding(8, 0, 8, 0);
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

            Bitmap bmp = captureScreen();
            if (bmp == null) {
                handler.postDelayed(this, 300);
                return;
            }

            float gridLeft = screenW * 0.046f;
            float gridWidth = screenW * 0.908f;
            float cellSize = gridWidth / 8.0f;
            float gridTop = screenH * 0.232f;

            // Считываем поле 8x8 по нескольким точкам внутри каждой клетки
            int[][] board = new int[8][8];
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    int cx = (int) (gridLeft + (c + 0.5f) * cellSize);
                    int cy = (int) (gridTop + (r + 0.5f) * cellSize);
                    int filledVotes = 0;
                    int offset = (int) (cellSize * 0.18f);
                    if (isFilledBoardCell(bmp.getPixel(cx, cy))) filledVotes++;
                    if (isFilledBoardCell(bmp.getPixel(cx - offset, cy))) filledVotes++;
                    if (isFilledBoardCell(bmp.getPixel(cx + offset, cy))) filledVotes++;
                    if (isFilledBoardCell(bmp.getPixel(cx, cy - offset))) filledVotes++;
                    if (isFilledBoardCell(bmp.getPixel(cx, cy + offset))) filledVotes++;
                    board[r][c] = (filledVotes >= 3) ? 1 : 0;
                }
            }

            float[] slotX = { screenW * 0.208f, screenW * 0.500f, screenW * 0.792f };
            float slotY = screenH * 0.754f;
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

            // Центр целевой области на сетке 8x8
            float targetCenterX = gridLeft + (bestMove.col + bestMove.piece.cols / 2.0f) * cellSize;
            float targetCenterY = gridTop + (bestMove.row + bestMove.piece.rows / 2.0f) * cellSize;

            // Точка старта пальца — центр фигуры в слоте
            float startX = bestMove.piece.centerX;
            float startY = bestMove.piece.centerY;

            // Точный расчет конечной точки пальца по формуле из видео
            float liftPx = liftCells * cellSize;
            float endX = startX + (targetCenterX - startX) / dragScaleX;
            float endY = startY + (targetCenterY - (startY - liftPx)) / dragScaleY;

            // Проверяем, сгорит ли линия после этого хода (нужна ли пауза под анимацию Combo/+10)
            boolean clearsLine = willClearLine(board, bestMove.piece, bestMove.row, bestMove.col);
            long nextDelay = clearsLine ? 1250L : 650L;

            performSmoothDrag(startX, startY, endX, endY, () -> {
                if (isRunning) handler.postDelayed(botLoop, nextDelay);
            });
        }
    };

    // Единый непрерывный жест без continueStroke (не вызывает ACTION_CANCEL на HyperOS/MIUI)
    // В конце пути делает микро-удержание в целевой точке, чтобы фигура встала в сетку перед отпусканием
    private void performSmoothDrag(float sx, float sy, float ex, float ey, Runnable onDone) {
        ex = Math.max(25, Math.min(screenW - 25, ex));
        ey = Math.max(25, Math.min(screenH - 25, ey));

        Path path = new Path();
        path.moveTo(sx, sy);

        // Плавно подводим к цели (92% пути) и мягко доводим до точной координаты
        float midX = sx + (ex - sx) * 0.92f;
        float midY = sy + (ey - sy) * 0.92f;
        path.lineTo(midX, midY);
        path.lineTo(ex, ey);

        // Микро-фиксация пальца на месте (1.5 px), чтобы погасить инерцию фигуры перед ACTION_UP
        path.lineTo(ex + 1.5f, ey);
        path.lineTo(ex - 1.5f, ey);
        path.lineTo(ex, ey + 1.5f);
        path.lineTo(ex, ey);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 520);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);

        dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                onDone.run();
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

        return diff > 45 && (hsv[1] > 0.45f || hsv[2] > 0.62f);
    }

    private Piece parsePiece(Bitmap bmp, float cx, float cy, float step, int bgColor, int slotIdx) {
        int halfW = (int) (step * 2.85f);
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int count = 0;

        // Шаг 2 пикселя для точного захвата даже одиночных кубиков 1x1 и диагоналей
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
