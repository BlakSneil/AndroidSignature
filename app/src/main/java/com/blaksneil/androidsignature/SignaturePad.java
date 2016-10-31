package com.blaksneil.androidsignature;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

import com.blaksneil.androidsignature.model.Bezier;
import com.blaksneil.androidsignature.model.ControlTimedPoints;
import com.blaksneil.androidsignature.model.TimedPoint;

public class SignaturePad extends View {
    private List<TimedPoint> points;
    private boolean isEmpty;
    private float lastTouchX;
    private float lastTouchY;
    private float lastVelocity;
    private float lastWidth;
    private RectF dirtyRect;   //Dirty rectangle to update only the changed portion of the view

    private OnSignedListener onSignedListener;

    private Paint paint = new Paint();
    private Path path = new Path();
    private Bitmap signatureBitmap = null;
    private Canvas signatureBitmapCanvas = null;

    private final boolean VARIABLE_STROKE = true;
    private final int STROKE_MIN_WIDTH = 3;
    private final int STROKE_MAX_WIDTH = 7;
    private final int STROKE_STANDARD_WIDTH = 3;
    private final float VELOCITY_FILTER_WEIGHT = 0.9f;

    private int minX, maxX, minY, maxY; // Store coordinates for optimizing bitmap cropping

    public SignaturePad(Context context, AttributeSet attrs) {
        super(context, attrs);

        paint.setColor(Color.BLACK);
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        clear();
        requestLayout();
    }

    public void clear() {

        dirtyRect = new RectF();

        points = new ArrayList<>();
        lastVelocity = 0;
        lastWidth = (STROKE_MIN_WIDTH + STROKE_MAX_WIDTH) / 2;
        path.reset();

        if (signatureBitmap != null) {
            signatureBitmap = null;
            ensureSignatureBitmap();
        }

        minX = getWidth();
        minY = getHeight();
        maxX = 0;
        maxY = 0;

        setIsEmpty(true);

        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled())
            return false;

        float eventX = event.getX();
        float eventY = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                points.clear();
                path.moveTo(eventX, eventY);
                lastTouchX = eventX;
                lastTouchY = eventY;
                addPoint(new TimedPoint(eventX, eventY));

            case MotionEvent.ACTION_MOVE:
                //checkCropCoordinates((int) eventX, (int) eventY);
                resetDirtyRect(eventX, eventY);
                addPoint(new TimedPoint(eventX, eventY));
                break;

            case MotionEvent.ACTION_UP:
                //checkCropCoordinates((int) eventX, (int) eventY);
                resetDirtyRect(eventX, eventY);
                addPoint(new TimedPoint(eventX, eventY));
                getParent().requestDisallowInterceptTouchEvent(true);
                setIsEmpty(false);
                break;

            default:
                return false;
        }

        invalidate(
                (int) (dirtyRect.left - STROKE_MAX_WIDTH),
                (int) (dirtyRect.top - STROKE_MAX_WIDTH),
                (int) (dirtyRect.right + STROKE_MAX_WIDTH),
                (int) (dirtyRect.bottom + STROKE_MAX_WIDTH));

        return true;
    }

    private void checkCropCoordinates(int x, int y) {

        // excluding points out of the View
        if (x < getX() || x > getX() + getWidth() || y < getY() || y > getY() + getHeight())
            return;

        if (x < minX)
            minX = x;
        if (x > maxX)
            maxX = x;
        if (y < minY)
            minY = y;
        if (y > maxY)
            maxY = y;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (signatureBitmap != null)
            canvas.drawBitmap(signatureBitmap, 0, 0, paint);
    }

    public void setOnSignedListener(OnSignedListener listener) {
        onSignedListener = listener;
    }

    public Bitmap getSignatureBitmap() {
        Bitmap originalBitmap = getTransparentSignatureBitmap();
        Bitmap whiteBgBitmap = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(whiteBgBitmap);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(originalBitmap, 0, 0, null);
        return whiteBgBitmap;
    }

    public void setSignatureBitmap(Bitmap signature) {
        clear();
        ensureSignatureBitmap();

        RectF tempSrc = new RectF();
        RectF tempDst = new RectF();

        int dWidth = signature.getWidth();
        int dHeight = signature.getHeight();
        int vWidth = getWidth();
        int vHeight = getHeight();

        // Generate the required transform.
        tempSrc.set(0, 0, dWidth, dHeight);
        tempDst.set(0, 0, vWidth, vHeight);

        Matrix drawMatrix = new Matrix();
        drawMatrix.setRectToRect(tempSrc, tempDst, Matrix.ScaleToFit.CENTER);

        Canvas canvas = new Canvas(signatureBitmap);
        canvas.drawBitmap(signature, drawMatrix, null);
        setIsEmpty(false);
        invalidate();
    }

    public Bitmap getTransparentSignatureBitmap() {
        ensureSignatureBitmap();
        return signatureBitmap;
    }

    private void validateCropCoordinates() {
        if (minX < 0)
            minX = 0;

        if (minY < 0)
            minY = 0;

        if (maxX - minX < 0) {
            minX = 0;
            maxX = getWidth();
        }

        if (maxY - minY < 0) {
            minY = 0;
            maxY = getHeight();
        }
    }

    public Bitmap getCroppedTransparentSignatureBitmap() {
        ensureSignatureBitmap();

        validateCropCoordinates();

        return Bitmap.createBitmap(signatureBitmap, minX, minY, maxX - minX, maxY - minY);
    }

    public Bitmap getTransparentSignatureBitmap(boolean trimBlankSpace) {

        if (!trimBlankSpace)
            return getTransparentSignatureBitmap();

        ensureSignatureBitmap();

        int imgHeight = signatureBitmap.getHeight();
        int imgWidth = signatureBitmap.getWidth();

        int backgroundColor = Color.TRANSPARENT;

        int xMin = Integer.MAX_VALUE,
            xMax = Integer.MIN_VALUE,
            yMin = Integer.MAX_VALUE,
            yMax = Integer.MIN_VALUE;

        boolean foundPixel = false;

        // Find xMin
        for (int x = 0; x < imgWidth; x++) {
            boolean stop = false;
            for (int y = 0; y < imgHeight; y++) {
                if (signatureBitmap.getPixel(x, y) != backgroundColor) {
                    xMin = x;
                    stop = true;
                    foundPixel = true;
                    break;
                }
            }
            if (stop)
                break;
        }

        // Image is empty...
        if (!foundPixel)
            return null;

        // Find yMin
        for (int y = 0; y < imgHeight; y++) {
            boolean stop = false;
            for (int x = xMin; x < imgWidth; x++) {
                if (signatureBitmap.getPixel(x, y) != backgroundColor) {
                    yMin = y;
                    stop = true;
                    break;
                }
            }
            if (stop)
                break;
        }

        // Find xMax
        for (int x = imgWidth - 1; x >= xMin; x--) {
            boolean stop = false;
            for (int y = yMin; y < imgHeight; y++) {
                if (signatureBitmap.getPixel(x, y) != backgroundColor) {
                    xMax = x;
                    stop = true;
                    break;
                }
            }
            if (stop)
                break;
        }

        // Find yMax
        for (int y = imgHeight - 1; y >= yMin; y--) {
            boolean stop = false;
            for (int x = xMin; x <= xMax; x++) {
                if (signatureBitmap.getPixel(x, y) != backgroundColor) {
                    yMax = y;
                    stop = true;
                    break;
                }
            }
            if (stop)
                break;
        }

        return Bitmap.createBitmap(signatureBitmap, xMin, yMin, xMax - xMin, yMax - yMin);
    }

    private void addPoint(TimedPoint newPoint) {
        points.add(newPoint);
        if (points.size() > 2) {
            // To reduce the initial lag make it work with 3 mPoints
            // by copying the first point to the beginning.
            if (points.size() == 3) points.add(0, points.get(0));

            ControlTimedPoints tmp = calculateCurveControlPoints(points.get(0), points.get(1), points.get(2));
            TimedPoint c2 = tmp.c2;
            tmp = calculateCurveControlPoints(points.get(1), points.get(2), points.get(3));
            TimedPoint c3 = tmp.c1;
            Bezier curve = new Bezier(points.get(1), c2, c3, points.get(2));

            TimedPoint startPoint = curve.startPoint;
            TimedPoint endPoint = curve.endPoint;

            float velocity = endPoint.velocityFrom(startPoint);
            velocity = Float.isNaN(velocity) ? 0.0f : velocity;

            velocity = VELOCITY_FILTER_WEIGHT * velocity + (1 - VELOCITY_FILTER_WEIGHT) * lastVelocity;

            // The new width is a function of the velocity. Higher velocities
            // correspond to thinner strokes.
            float newWidth = strokeWidth(velocity);

            // The Bezier's width starts out as last curve's final width, and
            // gradually changes to the stroke width just calculated. The new
            // width calculation is based on the velocity between the Bezier's
            // start and end mPoints.
            addBezier(curve, lastWidth, newWidth);

            lastVelocity = velocity;
            lastWidth = newWidth;

            // Remove the first element from the list,
            // so that we always have no more than 4 mPoints in mPoints array.
            points.remove(0);
        }
    }

    private void addBezier(Bezier curve, float startWidth, float endWidth) {
        ensureSignatureBitmap();
        float originalWidth = paint.getStrokeWidth();
        float widthDelta = endWidth - startWidth;
        float drawSteps = (float) Math.floor(curve.length());

        for (int i = 0; i < drawSteps; i++) {
            // Calculate the Bezier (x, y) coordinate for this step.
            float t = ((float) i) / drawSteps;
            float tt = t * t;
            float ttt = tt * t;
            float u = 1 - t;
            float uu = u * u;
            float uuu = uu * u;

            float x = uuu * curve.startPoint.x;
            x += 3 * uu * t * curve.control1.x;
            x += 3 * u * tt * curve.control2.x;
            x += ttt * curve.endPoint.x;

            float y = uuu * curve.startPoint.y;
            y += 3 * uu * t * curve.control1.y;
            y += 3 * u * tt * curve.control2.y;
            y += ttt * curve.endPoint.y;

            // Set the incremental stroke width and draw.
            paint.setStrokeWidth(startWidth + ttt * widthDelta);
            signatureBitmapCanvas.drawPoint(x, y, paint);
            expandDirtyRect(x, y);
        }

        paint.setStrokeWidth(originalWidth);
    }

    private ControlTimedPoints calculateCurveControlPoints(TimedPoint s1, TimedPoint s2, TimedPoint s3) {
        float dx1 = s1.x - s2.x;
        float dy1 = s1.y - s2.y;
        float dx2 = s2.x - s3.x;
        float dy2 = s2.y - s3.y;

        TimedPoint m1 = new TimedPoint((s1.x + s2.x) / 2.0f, (s1.y + s2.y) / 2.0f);
        TimedPoint m2 = new TimedPoint((s2.x + s3.x) / 2.0f, (s2.y + s3.y) / 2.0f);

        float l1 = (float) Math.sqrt(dx1 * dx1 + dy1 * dy1);
        float l2 = (float) Math.sqrt(dx2 * dx2 + dy2 * dy2);

        float dxm = (m1.x - m2.x);
        float dym = (m1.y - m2.y);
        float k = l2 / (l1 + l2);
        TimedPoint cm = new TimedPoint(m2.x + dxm * k, m2.y + dym * k);

        float tx = s2.x - cm.x;
        float ty = s2.y - cm.y;

        return new ControlTimedPoints(new TimedPoint(m1.x + tx, m1.y + ty), new TimedPoint(m2.x + tx, m2.y + ty));
    }

    private float strokeWidth(float velocity) {
        if (VARIABLE_STROKE)
            return Math.max(STROKE_MAX_WIDTH / (velocity + 1), STROKE_MIN_WIDTH);
        else
            return STROKE_STANDARD_WIDTH;
    }

    /**
     * Called when replaying history to ensure the dirty region includes all
     * mPoints.
     *
     * @param historicalX the previous x coordinate.
     * @param historicalY the previous y coordinate.
     */
    private void expandDirtyRect(float historicalX, float historicalY) {
        if (historicalX < dirtyRect.left) {
            dirtyRect.left = historicalX;
        } else if (historicalX > dirtyRect.right) {
            dirtyRect.right = historicalX;
        }
        if (historicalY < dirtyRect.top) {
            dirtyRect.top = historicalY;
        } else if (historicalY > dirtyRect.bottom) {
            dirtyRect.bottom = historicalY;
        }
    }

    /**
     * Resets the dirty region when the motion event occurs.
     *
     * @param eventX the event x coordinate.
     * @param eventY the event y coordinate.
     */
    private void resetDirtyRect(float eventX, float eventY) {

        // The lastTouchX and lastTouchY were set when the ACTION_DOWN motion event occurred.
        dirtyRect.left = Math.min(lastTouchX, eventX);
        dirtyRect.right = Math.max(lastTouchX, eventX);
        dirtyRect.top = Math.min(lastTouchY, eventY);
        dirtyRect.bottom = Math.max(lastTouchY, eventY);
    }

    private void setIsEmpty(boolean newValue) {
        isEmpty = newValue;
        if (onSignedListener != null) {
            if (isEmpty) {
                onSignedListener.onClear();
            } else {
                onSignedListener.onSigned();
            }
        }
    }

    private void ensureSignatureBitmap() {
        if (signatureBitmap == null) {
            signatureBitmap = Bitmap.createBitmap(getWidth(), getHeight(),
                    Bitmap.Config.ARGB_8888);
            signatureBitmapCanvas = new Canvas(signatureBitmap);
        }
    }

    public interface OnSignedListener {
        void onSigned();

        void onClear();
    }
}