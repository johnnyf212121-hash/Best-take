package cz.honza.lepsimoment;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Original vector illustration; no stock photos or network assets. */
final class MomentArtwork extends View {
    final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    MomentArtwork(Context c) { super(c); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
    void color(int c) { p.setColor(c); p.setStyle(Paint.Style.FILL); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); canvas.save();
        float scale=Math.min(getWidth()/340f,getHeight()/230f);
        canvas.translate((getWidth()-340*scale)/2,(getHeight()-230*scale)/2); canvas.scale(scale,scale);
        color(0xffe7eddf); canvas.drawCircle(170,113,100,p);
        canvas.save(); canvas.rotate(-9,160,118); photo(canvas,43,40,230,153,false); canvas.restore();
        canvas.save(); canvas.rotate(5,166,123); photo(canvas,52,46,230,153,true); canvas.restore();
        color(0xff176b54); canvas.drawRoundRect(238,36,302,100,18,18,p);
        color(0xffdfefba); canvas.drawCircle(270,68,21,p); face(canvas,270,68,true,.8f);
        color(0xff176b54); p.setStrokeWidth(3); p.setStrokeCap(Paint.Cap.ROUND); p.setStyle(Paint.Style.STROKE);
        canvas.drawLine(279,114,279,132,p); canvas.drawLine(270,123,288,123,p);
        p.setStyle(Paint.Style.FILL);
        color(0xffe9b99e); canvas.drawCircle(49,178,7,p);
        color(0xff176b54); canvas.drawCircle(38,161,3,p);
        canvas.restore();
    }
    void photo(Canvas c,float x,float y,float w,float h,boolean happy) {
        color(0x120e3627); c.drawRoundRect(x,y+5,x+w,y+h+5,14,14,p);
        color(Color.WHITE); c.drawRoundRect(x,y,x+w,y+h,14,14,p);
        c.save(); Path clip=new Path(); clip.addRoundRect(new RectF(x+9,y+9,x+w-9,y+h-24),8,8,Path.Direction.CW); c.clipPath(clip);
        color(0xffd6e6d8); c.drawRect(x,y,x+w,y+h,p);
        color(0xffafc7a4); c.drawOval(x-10,y+70,x+160,y+h+30,p);
        color(0xffbfd3b2); c.drawOval(x+70,y+60,x+w+60,y+h+40,p);
        person(c,x+62,y+84,0xffddaa8a,0xffe5b253,false,.85f);
        person(c,x+122,y+73,0xffedc5a7,0xff176b54,happy,1);
        person(c,x+180,y+89,0xffac7755,0xff818ead,true,.86f);
        c.restore(); color(0xffd5ddce); c.drawRoundRect(x+13,y+h-15,x+71,y+h-10,3,3,p);
        color(0xff176b54); c.drawCircle(x+w-19,y+h-13,3,p);
    }
    void person(Canvas c,float x,float y,int skin,int shirt,boolean smile,float scale) {
        c.save(); c.translate(x,y); c.scale(scale,scale);
        color(shirt); c.drawRoundRect(-27,14,27,78,24,24,p);
        color(skin); c.drawRoundRect(-21,-31,21,24,21,21,p);
        color(0xff394338); c.drawArc(-22,-37,22,5,180,180,true,p);
        face(c,0,-3,smile,1); c.restore();
    }
    void face(Canvas c,float x,float y,boolean smile,float scale) {
        c.save(); c.translate(x,y); c.scale(scale,scale);
        color(0xff253f32); c.drawCircle(-7,-4,2,p); c.drawCircle(7,-4,2,p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.4f); p.setStrokeCap(Paint.Cap.ROUND);
        if (smile) c.drawArc(-9,0,9,14,8,164,false,p); else c.drawLine(-7,8,7,6,p);
        p.setStyle(Paint.Style.FILL); c.restore();
    }
}
