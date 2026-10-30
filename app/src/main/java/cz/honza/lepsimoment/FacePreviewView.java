package cz.honza.lepsimoment;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import cz.honza.lepsimoment.core.FaceGeometry;
import java.util.*;
import java.util.function.IntConsumer;

/** Fit/zoom/pan preview with hit testing in image coordinates. */
public final class FacePreviewView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final ScaleGestureDetector pinch;
    private Bitmap image;
    private List<FaceGeometry> faces=Collections.emptyList();
    private Set<Integer> edited=Collections.emptySet();
    private int selected=-1, photoKey=-1;
    private boolean showFaces=true, faceSelectionEnabled=true, moved, swipeEligible;
    private String bottomLabel;
    private float zoom=1, panX=0, panY=0, lastX,lastY,downX,downY;
    private final RectF imageRect=new RectF();
    private final RectF faceRect=new RectF(),clipRect=new RectF();
    private final Path clipPath=new Path();
    public IntConsumer onFaceSelected;
    public IntConsumer onHorizontalSwipe;

    public FacePreviewView(Context context) {
        super(context);
        setContentDescription("Náhled fotografie. Klepněte na obličej. Dvěma prsty můžete přiblížit. Osobu lze vybrat také tlačítky pod fotkou.");
        setLayerType(View.LAYER_TYPE_HARDWARE,null);
        pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float previous=zoom;
                zoom=Math.max(1,Math.min(16,zoom*detector.getScaleFactor()));
                float factor=zoom/previous;
                panX=(panX+getWidth()/2f-detector.getFocusX())*factor+detector.getFocusX()-getWidth()/2f;
                panY=(panY+getHeight()/2f-detector.getFocusY())*factor+detector.getFocusY()-getHeight()/2f;
                clampPan(); moved=true; invalidate(); return true;
            }
        });
        pinch.setQuickScaleEnabled(false);
    }
    public void setFaceSelectionEnabled(boolean enabled) { faceSelectionEnabled=enabled; }
    public void setShowFaces(boolean show) { showFaces=show; invalidate(); }
    public void setBottomLabel(String label) { bottomLabel=label; invalidate(); }
    String bottomLabel() { return bottomLabel; }
    RectF imageBounds() { return new RectF(imageRect); }
    float zoomFactor() { return zoom; }
    public void setContent(Bitmap bitmap,List<FaceGeometry> list,int chosen,Set<Integer> changed,int key,boolean overlays) {
        image=bitmap; faces=list; selected=chosen; edited=new HashSet<>(changed); showFaces=overlays;
        if (image==null) imageRect.setEmpty();
        if (photoKey!=key) { zoom=1; panX=panY=0; photoKey=key; }
        invalidate();
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) { clampPan(); }
    private float dp(float value) { return value*getResources().getDisplayMetrics().density; }
    private float fitScale() {
        if (image==null || getWidth()==0 || getHeight()==0) return 1;
        return Math.min((float)getWidth()/image.getWidth(),(float)getHeight()/image.getHeight());
    }
    private void clampPan() {
        if (image==null) { imageRect.setEmpty(); return; }
        float w=image.getWidth()*fitScale()*zoom, h=image.getHeight()*fitScale()*zoom;
        float maxX=Math.max(0,(w-getWidth())/2), maxY=Math.max(0,(h-getHeight())/2);
        panX=Math.max(-maxX,Math.min(maxX,panX)); panY=Math.max(-maxY,Math.min(maxY,panY));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setColor(Color.rgb(229,233,225)); paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(0,0,getWidth(),getHeight(),dp(18),dp(18),paint);
        if (image==null) return;
        clampPan();
        float s=fitScale()*zoom,w=image.getWidth()*s,h=image.getHeight()*s;
        imageRect.set((getWidth()-w)/2+panX,(getHeight()-h)/2+panY,(getWidth()+w)/2+panX,(getHeight()+h)/2+panY);
        canvas.save();
        clipRect.set(0,0,getWidth(),getHeight()); clipPath.reset(); clipPath.addRoundRect(clipRect,dp(18),dp(18),Path.Direction.CW);
        canvas.clipPath(clipPath);
        paint.setColor(Color.WHITE); canvas.drawBitmap(image,null,imageRect,paint);
        if (showFaces) for (int i=0;i<faces.size();i++) {
            var b=faces.get(i).box();
            RectF rect=faceRect;
            rect.set(imageRect.left+(float)b.left()*w,imageRect.top+(float)b.top()*h,
                    imageRect.left+(float)b.right()*w,imageRect.top+(float)b.bottom()*h);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(i==selected?2.5f:1.5f));
            paint.setColor(i==selected?Color.rgb(222,244,169):Color.WHITE);
            canvas.drawRoundRect(rect,dp(10),dp(10),paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(i==selected?Color.rgb(222,244,169):edited.contains(i)?Color.rgb(23,107,84):Color.WHITE);
            float cx=rect.left+dp(11),cy=Math.max(dp(12),rect.top);
            canvas.drawCircle(cx,cy,dp(11),paint);
            paint.setColor(edited.contains(i)&&i!=selected?Color.WHITE:Color.rgb(25,49,39));
            paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); paint.setTextSize(dp(11)); paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(String.valueOf(i+1),cx,cy-(paint.ascent()+paint.descent())/2,paint);
        }
        if (bottomLabel!=null) {
            // Anchor the caption to the visible photo edge, including letterboxed images.
            paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            paint.setTextSize(10*getResources().getDisplayMetrics().scaledDensity);
            float width=paint.measureText(bottomLabel)+dp(20), height=paint.descent()-paint.ascent()+dp(10);
            float bottom=Math.min(getHeight(),imageRect.bottom)-dp(8), cx=getWidth()/2f;
            paint.setColor(0x80213c31);
            canvas.drawRoundRect(cx-width/2,bottom-height,cx+width/2,bottom,dp(7),dp(7),paint);
            paint.setColor(0xddffffff);
            canvas.drawText(bottomLabel,cx,bottom-dp(5)-paint.descent(),paint);
        }
        canvas.restore();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (image==null || !isEnabled()) return false;
        pinch.onTouchEvent(event);
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX=downX=event.getX(); lastY=downY=event.getY(); moved=false;
                swipeEligible=zoom<=1.001f;
                intercept(true); return true;
            case MotionEvent.ACTION_POINTER_DOWN: moved=true; swipeEligible=false; return true;
            case MotionEvent.ACTION_MOVE:
                if (!pinch.isInProgress() && event.getPointerCount()==1) {
                    if (Math.hypot(event.getX()-downX,event.getY()-downY)>dp(6)) moved=true;
                    if (zoom>1) { panX+=event.getX()-lastX; panY+=event.getY()-lastY; clampPan(); invalidate(); }
                }
                lastX=event.getX(); lastY=event.getY(); return true;
            case MotionEvent.ACTION_POINTER_UP:
                int remaining=event.getActionIndex()==0?1:0;
                lastX=event.getX(remaining); lastY=event.getY(remaining); return true;
            case MotionEvent.ACTION_UP:
                float dx=event.getX()-downX,dy=event.getY()-downY;
                // A fitted photo can page; a zoomed photo pans. Never page after a pinch.
                if (swipeEligible && onHorizontalSwipe!=null && Math.abs(dx)>=dp(56) && Math.abs(dx)>Math.abs(dy)*1.5f) {
                    onHorizontalSwipe.accept(dx<0?1:-1);
                } else if (!moved && faceSelectionEnabled) {
                    double x=(event.getX()-imageRect.left)/imageRect.width();
                    double y=(event.getY()-imageRect.top)/imageRect.height();
                    int hit=-1; double smallest=Double.MAX_VALUE;
                    for (int i=0;i<faces.size();i++) {
                        var b=faces.get(i).box();
                        if (x>=b.left()-.01 && x<=b.right()+.01 && y>=b.top()-.01 && y<=b.bottom()+.01 && b.width()*b.height()<smallest) {
                            hit=i; smallest=b.width()*b.height();
                        }
                    }
                    if (hit>=0 && onFaceSelected!=null) onFaceSelected.accept(hit);
                    performClick();
                }
                intercept(false); return true;
            case MotionEvent.ACTION_CANCEL: swipeEligible=false; intercept(false); return true;
        }
        return true;
    }
    private void intercept(boolean disallow) { if (getParent()!=null) getParent().requestDisallowInterceptTouchEvent(disallow); }
    @Override public boolean performClick() { super.performClick(); return true; }
}
