package cz.honza.lepsimoment.core;

import cz.honza.lepsimoment.core.FaceGeometry.Point;

/** A non-reflecting similarity preserves the donor's expression and proportions. */
public record Similarity(double a, double b, double tx, double ty) {
    public Point map(Point p) { return new Point(a*p.x()-b*p.y()+tx, b*p.x()+a*p.y()+ty); }
    public Point inverse(double x, double y) {
        double d = a*a+b*b;
        if (d < 1e-12) throw new IllegalArgumentException("Neplatné zarovnání obličeje.");
        return new Point((a*(x-tx)+b*(y-ty))/d, (-b*(x-tx)+a*(y-ty))/d);
    }
    public static Similarity align(FaceGeometry from, FaceGeometry to, Adjustment adjust) {
        if (!from.canAlign() || !to.canAlign())
            throw new IllegalArgumentException("Nevidím spolehlivě obě oči. Zkuste jiný, čelnější záběr.");
        double sx = from.rightEye().x()-from.leftEye().x();
        double sy = from.rightEye().y()-from.leftEye().y();
        double dx = to.rightEye().x()-to.leftEye().x();
        double dy = to.rightEye().y()-to.leftEye().y();
        double d = sx*sx+sy*sy;
        double a = (dx*sx+dy*sy)/d;
        double b = (dy*sx-dx*sy)/d;
        double rad = Math.toRadians(adjust.rotation());
        double aa = adjust.scale() * (a*Math.cos(rad)-b*Math.sin(rad));
        double bb = adjust.scale() * (b*Math.cos(rad)+a*Math.sin(rad));
        double scx = (from.leftEye().x()+from.rightEye().x())/2;
        double scy = (from.leftEye().y()+from.rightEye().y())/2;
        double tcx = (to.leftEye().x()+to.rightEye().x())/2 + adjust.offsetX()*to.box().width();
        double tcy = (to.leftEye().y()+to.rightEye().y())/2 + adjust.offsetY()*to.box().height();
        return new Similarity(aa, bb, tcx-aa*scx+bb*scy, tcy-bb*scx-aa*scy);
    }
    public record Adjustment(double offsetX, double offsetY, double scale, double rotation,
                             double feather, boolean matchColor, double maskExpansion) {
        public Adjustment(double x,double y,double scale,double rotation,double feather,boolean color) {
            this(x,y,scale,rotation,feather,color,0);
        }
        public static Adjustment defaults() { return new Adjustment(0, 0, 1, 0, 0.16, true, 0); }
    }
}
