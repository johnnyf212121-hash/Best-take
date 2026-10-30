package cz.honza.lepsimoment.core;

import java.util.ArrayList;
import java.util.List;

/** Coordinates are normalized to the upright, decoded image unless scaled(). */
public record FaceGeometry(Box box, Point leftEye, Point rightEye, Point nose,
                           List<Point> outline, double yaw, double pitch,
                           Float smile, Float leftOpen, Float rightOpen) {
    public record Point(double x, double y) {
        public Point scale(double xScale, double yScale) { return new Point(x * xScale, y * yScale); }
    }
    public record Box(double left, double top, double right, double bottom) {
        public double width() { return right - left; }
        public double height() { return bottom - top; }
        public double cx() { return (left + right) / 2; }
        public double cy() { return (top + bottom) / 2; }
        public Box scale(double x, double y) { return new Box(left*x, top*y, right*x, bottom*y); }
    }
    public boolean canAlign() {
        return leftEye != null && rightEye != null &&
                Math.hypot(leftEye.x - rightEye.x, leftEye.y - rightEye.y) > box.width() * 0.12;
    }
    public FaceGeometry scaled(double width, double height) {
        return new FaceGeometry(box.scale(width, height),
                leftEye == null ? null : leftEye.scale(width, height),
                rightEye == null ? null : rightEye.scale(width, height),
                nose == null ? null : nose.scale(width, height),
                outline.stream().map(p -> p.scale(width, height)).collect(java.util.stream.Collectors.toList()),
                yaw, pitch, smile, leftOpen, rightOpen);
    }
    public List<Point> maskOutline() {
        if (outline.size() >= 12) return outline;
        List<Point> ellipse = new ArrayList<>();
        double angle = canAlign() ? Math.atan2(rightEye.y-leftEye.y, rightEye.x-leftEye.x) : 0;
        if (Math.cos(angle) < 0) angle += Math.PI;
        for (int i=0; i<48; i++) {
            double t = i * Math.PI * 2 / 48;
            double x = Math.cos(t) * box.width() * 0.46;
            double y = Math.sin(t) * box.height() * 0.48;
            ellipse.add(new Point(box.cx() + x*Math.cos(angle)-y*Math.sin(angle),
                    box.cy() + x*Math.sin(angle)+y*Math.cos(angle)));
        }
        return ellipse;
    }
}
