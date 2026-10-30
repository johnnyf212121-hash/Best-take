package cz.honza.lepsimoment.core;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import cz.honza.lepsimoment.core.FaceGeometry.Point;

/** Pure Java image kernel. Uses small patches, never allocates another full photo. */
public final class PatchBlender {
    private PatchBlender() {}
    public record Patch(int[] pixels, int width, int height, int x, int y) {
        public Patch {
            if (width < 1 || height < 1 || pixels.length != width*height)
                throw new IllegalArgumentException("Invalid patch dimensions");
        }
    }

    public static int blend(Patch target, Patch source, FaceGeometry targetFace,
                            FaceGeometry sourceFace, Similarity.Adjustment adjustment) {
        Similarity transform = Similarity.align(sourceFace, targetFace, adjustment);
        int w=target.width, h=target.height, n=w*h;
        boolean[] inside = polygonMask(target, targetFace.maskOutline());
        boolean[] donorInside = polygonMask(target, sourceFace.maskOutline().stream().map(transform::map).collect(java.util.stream.Collectors.toList()));
        for (int i=0;i<n;i++) inside[i] &= donorInside[i];
        // Offset the mask boundary in image pixels; the donor transform stays unchanged.
        inside = morph(inside,w,h,targetFace.box().width()*adjustment.maskExpansion());
        int[] donor = new int[n];
        for (int y=0; y<h; y++) {
            checkCancelled();
            for (int x=0; x<w; x++) {
                int i=y*w+x;
                if (!inside[i]) continue;
                Point p=transform.inverse(target.x+x+0.5, target.y+y+0.5);
                // Pixel centers are at .5; bilinear sampler indexes centers at integer positions.
                double sx=p.x()-source.x-0.5, sy=p.y()-source.y-0.5;
                if (sx<0 || sy<0 || sx>=source.width-1 || sy>=source.height-1) {
                    inside[i]=false;
                } else donor[i]=bilinear(source, sx, sy);
            }
        }
        float[] distance=distanceToBoundary(inside, w, h);
        double feather=Math.max(2, targetFace.box().width()*adjustment.feather());
        double[] shift = new double[3];
        if (adjustment.matchColor()) {
            double[] sumSource=new double[3], sumTarget=new double[3];
            int count=0;
            // Estimate exposure/color at the seam, away from the mouth and eyes.
            for (int i=0; i<n; i++) if (inside[i] && distance[i]>feather*.3 && distance[i]<feather*1.3) {
                int s=donor[i], t=target.pixels[i];
                if (luma(s)<18 || luma(t)<18 || luma(s)>240 || luma(t)>240) continue;
                for (int c=0; c<3; c++) {
                    sumSource[c]+=channel(s,c); sumTarget[c]+=channel(t,c);
                }
                count++;
            }
            if (count>12) for (int c=0; c<3; c++)
                shift[c]=Math.max(-38, Math.min(38, (sumTarget[c]-sumSource[c])/count));
        }
        int changed=0;
        for (int i=0; i<n; i++) {
            if ((i & 8191)==0) checkCancelled();
            if (!inside[i]) continue;
            double a=Math.min(1, distance[i]/feather);
            a=a*a*(3-2*a);
            int rgb=0xff000000;
            for (int c=0; c<3; c++) {
                double v=Math.max(0, Math.min(255,channel(donor[i],c)+shift[c]));
                int value=(int)Math.round(a*v+(1-a)*channel(target.pixels[i],c));
                rgb |= value << (16-c*8);
            }
            target.pixels[i]=rgb;
            changed++;
        }
        if (changed<16) throw new IllegalArgumentException("Obličeje se nepodařilo zarovnat. Vyberte jiný záběr.");
        return changed;
    }

    private static boolean[] morph(boolean[] mask,int w,int h,double amount) {
        if (Math.abs(amount)<.01) return mask;
        float[] distance;
        if (amount<0) distance=distanceToBoundary(mask,w,h);
        else {
            distance=new float[mask.length];
            for (int i=0;i<mask.length;i++) distance[i]=mask[i]?0:Float.POSITIVE_INFINITY;
            for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
                int i=y*w+x;
                if (x>0) distance[i]=Math.min(distance[i],distance[i-1]+1);
                if (y>0) distance[i]=Math.min(distance[i],distance[i-w]+1);
                if (x>0 && y>0) distance[i]=Math.min(distance[i],distance[i-w-1]+1.4142f);
                if (x<w-1 && y>0) distance[i]=Math.min(distance[i],distance[i-w+1]+1.4142f);
            }
            for (int y=h-1;y>=0;y--) for (int x=w-1;x>=0;x--) {
                int i=y*w+x;
                if (x<w-1) distance[i]=Math.min(distance[i],distance[i+1]+1);
                if (y<h-1) distance[i]=Math.min(distance[i],distance[i+w]+1);
                if (x<w-1 && y<h-1) distance[i]=Math.min(distance[i],distance[i+w+1]+1.4142f);
                if (x>0 && y<h-1) distance[i]=Math.min(distance[i],distance[i+w-1]+1.4142f);
            }
        }
        boolean[] result=new boolean[mask.length];
        for (int i=0;i<mask.length;i++) result[i]=amount<0 ? mask[i] && distance[i]>-amount : distance[i]<=amount;
        return result;
    }

    private static boolean[] polygonMask(Patch patch, List<Point> points) {
        boolean[] result=new boolean[patch.width*patch.height];
        double[] intersections=new double[points.size()];
        for (int y=0; y<patch.height; y++) {
            double py=patch.y+y+0.5;
            int count=0;
            for (int i=0,j=points.size()-1; i<points.size(); j=i++) {
                Point a=points.get(j), b=points.get(i);
                if ((a.y()>py)!=(b.y()>py))
                    intersections[count++]=a.x()+(py-a.y())*(b.x()-a.x())/(b.y()-a.y());
            }
            Arrays.sort(intersections,0,count);
            for (int j=0; j+1<count; j+=2) {
                int start=Math.max(0,(int)Math.ceil(intersections[j]-patch.x-0.5));
                int end=Math.min(patch.width,(int)Math.ceil(intersections[j+1]-patch.x-0.5));
                if (start<end) Arrays.fill(result,y*patch.width+start,y*patch.width+end,true);
            }
        }
        return result;
    }

    private static float[] distanceToBoundary(boolean[] mask, int w, int h) {
        float[] d=new float[mask.length];
        for (int y=0; y<h; y++) for (int x=0; x<w; x++) {
            int i=y*w+x;
            d[i]=mask[i] ? Math.min(Math.min(x+1,w-x),Math.min(y+1,h-y)) : 0;
            if (x>0) d[i]=Math.min(d[i],d[i-1]+1);
            if (y>0) d[i]=Math.min(d[i],d[i-w]+1);
            if (x>0 && y>0) d[i]=Math.min(d[i],d[i-w-1]+1.4142f);
            if (x<w-1 && y>0) d[i]=Math.min(d[i],d[i-w+1]+1.4142f);
        }
        for (int y=h-1; y>=0; y--) for (int x=w-1; x>=0; x--) {
            int i=y*w+x;
            if (x<w-1) d[i]=Math.min(d[i],d[i+1]+1);
            if (y<h-1) d[i]=Math.min(d[i],d[i+w]+1);
            if (x<w-1 && y<h-1) d[i]=Math.min(d[i],d[i+w+1]+1.4142f);
            if (x>0 && y<h-1) d[i]=Math.min(d[i],d[i+w-1]+1.4142f);
        }
        return d;
    }
    private static int bilinear(Patch p,double x,double y) {
        int ix=(int)x, iy=(int)y;
        double fx=x-ix, fy=y-iy;
        int a=p.pixels[iy*p.width+ix], b=p.pixels[iy*p.width+ix+1];
        int c=p.pixels[(iy+1)*p.width+ix], d=p.pixels[(iy+1)*p.width+ix+1];
        int color=0xff000000;
        for (int k=0;k<3;k++) {
            double top=channel(a,k)*(1-fx)+channel(b,k)*fx;
            double bottom=channel(c,k)*(1-fx)+channel(d,k)*fx;
            color|=((int)Math.round(top*(1-fy)+bottom*fy)) << (16-8*k);
        }
        return color;
    }
    private static int channel(int color,int c) { return (color >>> (16-8*c)) & 255; }
    private static double luma(int c) { return channel(c,0)*.2126+channel(c,1)*.7152+channel(c,2)*.0722; }
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
