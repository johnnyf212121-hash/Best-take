package cz.honza.lepsimoment;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.graphics.PointF;
import android.graphics.Rect;
import android.net.Uri;
import android.provider.MediaStore;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.*;
import cz.honza.lepsimoment.core.*;
import cz.honza.lepsimoment.core.FaceGeometry.Box;
import cz.honza.lepsimoment.core.FaceGeometry.Point;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Call on a worker thread. Detection, compositing and encoding are entirely local. */
public final class PhotoEngine implements AutoCloseable {
    public static final int PREVIEW_EDGE=1600, EXPORT_EDGE=4096;
    private final ContentResolver resolver;
    private final FaceDetector detector=FaceDetection.getClient(new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.025f).build());
    private final FaceDetector contours=FaceDetection.getClient(new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL).build());

    public PhotoEngine(ContentResolver resolver) { this.resolver=resolver; }
    public record Photo(Uri uri, Bitmap thumbnail, List<FaceGeometry> faces, int width, int height) {}
    public record Edit(int photoIndex,int faceIndex,Similarity.Adjustment adjustment) {}
    public record Saved(Uri uri,int width,int height) {}

    public Bitmap decode(Uri uri,int maxEdge,boolean mutable) throws IOException {
        return decode(resolver,uri,maxEdge,mutable);
    }
    static Bitmap decode(ContentResolver resolver,Uri uri,int maxEdge,boolean mutable) throws IOException {
        Bitmap bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver,uri),(decoder,info,source)->{
            double scale=Math.min(1,(double)maxEdge/Math.max(info.getSize().getWidth(),info.getSize().getHeight()));
            decoder.setTargetSize(Math.max(1,(int)Math.round(info.getSize().getWidth()*scale)),
                    Math.max(1,(int)Math.round(info.getSize().getHeight()*scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMutableRequired(mutable);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
        });
        if (bitmap.getConfig()!=Bitmap.Config.ARGB_8888) {
            Bitmap copy=bitmap.copy(Bitmap.Config.ARGB_8888,mutable); bitmap.recycle(); return copy;
        }
        return bitmap;
    }

    public Photo analyze(Uri uri) throws Exception {
        int[] size=new int[2];
        Bitmap image=ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver,uri),(decoder,info,source)->{
            size[0]=info.getSize().getWidth(); size[1]=info.getSize().getHeight();
            double scale=Math.min(1,2048.0/Math.max(size[0],size[1]));
            decoder.setTargetSize(Math.max(1,(int)Math.round(size[0]*scale)),Math.max(1,(int)Math.round(size[1]*scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
        });
        if (image.getConfig()!=Bitmap.Config.ARGB_8888) {
            Bitmap compatible=image.copy(Bitmap.Config.ARGB_8888,false);
            image.recycle(); image=compatible;
        }
        try {
            List<Face> detected=Tasks.await(detector.process(InputImage.fromBitmap(image,0)),45,TimeUnit.SECONDS);
            detected=new ArrayList<>(detected);
            detected.sort(Comparator.comparingInt((Face f)->f.getBoundingBox().centerX())
                    .thenComparingInt(f->f.getBoundingBox().centerY()));
            List<FaceGeometry> faces=new ArrayList<>();
            for (Face face:detected) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                Rect r=face.getBoundingBox();
                List<Point> outline=new ArrayList<>();
                // Contour mode handles one prominent face: isolate each person first.
                Rect crop=new Rect(Math.max(0,r.left-r.width()/5),Math.max(0,r.top-r.height()/5),
                        Math.min(image.getWidth(),r.right+r.width()/5),Math.min(image.getHeight(),r.bottom+r.height()/5));
                if (crop.width()>0 && crop.height()>0) {
                    Bitmap cut=Bitmap.createBitmap(image,crop.left,crop.top,crop.width(),crop.height());
                    try {
                        List<Face> refined=Tasks.await(contours.process(InputImage.fromBitmap(cut,0)),20,TimeUnit.SECONDS);
                        Face closest=refined.stream().min(Comparator.comparingDouble(f->
                                Math.hypot(f.getBoundingBox().centerX()+crop.left-r.centerX(),
                                        f.getBoundingBox().centerY()+crop.top-r.centerY()))).orElse(null);
                        if (closest!=null && Math.hypot(closest.getBoundingBox().centerX()+crop.left-r.centerX(),
                                closest.getBoundingBox().centerY()+crop.top-r.centerY())<r.width()*.3) {
                            FaceContour contour=closest.getContour(FaceContour.FACE);
                            if (contour!=null) for (PointF p:contour.getPoints())
                                outline.add(new Point((p.x+crop.left)/image.getWidth(),(p.y+crop.top)/image.getHeight()));
                        }
                    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
                        // Landmarks still allow an oval fallback; one missing contour must not discard a photo.
                    } finally { if (cut!=image) cut.recycle(); }
                }
                faces.add(new FaceGeometry(new Box((double)r.left/image.getWidth(),(double)r.top/image.getHeight(),
                        (double)r.right/image.getWidth(),(double)r.bottom/image.getHeight()),
                        point(face,FaceLandmark.LEFT_EYE,image), point(face,FaceLandmark.RIGHT_EYE,image),
                        point(face,FaceLandmark.NOSE_BASE,image),outline,
                        face.getHeadEulerAngleY(),face.getHeadEulerAngleX(),face.getSmilingProbability(),
                        face.getLeftEyeOpenProbability(),face.getRightEyeOpenProbability()));
            }
            double scale=Math.min(1,800.0/Math.max(image.getWidth(),image.getHeight()));
            Bitmap thumbnail=Bitmap.createScaledBitmap(image,Math.max(1,(int)(image.getWidth()*scale)),
                    Math.max(1,(int)(image.getHeight()*scale)),true);
            // createScaledBitmap can return the original for small source images.
            if (thumbnail==image) thumbnail=image.copy(Bitmap.Config.ARGB_8888,false);
            return new Photo(uri,thumbnail,Collections.unmodifiableList(new ArrayList<>(faces)),size[0],size[1]);
        } finally { image.recycle(); }
    }

    private static Point point(Face face,int kind,Bitmap bitmap) {
        FaceLandmark landmark=face.getLandmark(kind);
        if (landmark==null) return null;
        return new Point(landmark.getPosition().x/bitmap.getWidth(),landmark.getPosition().y/bitmap.getHeight());
    }

    public Bitmap render(List<Photo> photos,int baseIndex,Map<Integer,Edit> edits,int edge) throws Exception {
        Photo base=photos.get(baseIndex);
        Bitmap output=decode(base.uri(),edge,true);
        try {
            for (Map.Entry<Integer,Edit> entry:edits.entrySet()) {
                Edit edit=entry.getValue();
                Photo source=photos.get(edit.photoIndex());
                Bitmap donor=decode(source.uri(),edge,false);
                try {
                    FaceGeometry target=base.faces().get(entry.getKey()).scaled(output.getWidth(),output.getHeight());
                    FaceGeometry from=source.faces().get(edit.faceIndex()).scaled(donor.getWidth(),donor.getHeight());
                    if (Math.abs(target.yaw()-from.yaw())>32 || Math.abs(target.pitch()-from.pitch())>28)
                        throw new IllegalArgumentException("Natočení hlavy je příliš jiné. Zvolte podobnější záběr.");
                    Rect targetRect=clip(target.box(),output,0.25);
                    Rect sourceRect=clip(from.box(),donor,0.5);
                    PatchBlender.Patch destination=patch(output,targetRect);
                    PatchBlender.blend(destination,patch(donor,sourceRect),target,from,edit.adjustment());
                    output.setPixels(destination.pixels(),0,targetRect.width(),targetRect.left,targetRect.top,
                            targetRect.width(),targetRect.height());
                } finally { donor.recycle(); }
            }
            return output;
        } catch (Exception | OutOfMemoryError e) { output.recycle(); throw e; }
    }

    /** Decode and compose static edits once. Slider updates only blend the affected patches. */
    PreparedTune prepareTune(List<Photo> photos,int baseIndex,Map<Integer,Edit> edits,int selected,double aspect) throws Exception {
        Photo base=photos.get(baseIndex);
        Box normalized=base.faces().get(selected).box();
        double initialScale=Math.min(1,(double)PREVIEW_EDGE/Math.max(base.width(),base.height()));
        double frameWidth=Math.max(normalized.width()*base.width()*2,normalized.height()*base.height()*2*aspect)*initialScale;
        double frameHeight=frameWidth/aspect;
        int edge=Math.max(128,(int)(PREVIEW_EDGE*Math.min(1,1600/Math.max(frameWidth,frameHeight))));
        Bitmap background=decode(base.uri(),edge,true);
        List<TunePatch> remaining=new ArrayList<>();
        try {
            boolean afterSelected=false;
            for (Map.Entry<Integer,Edit> entry:edits.entrySet()) {
                Edit edit=entry.getValue(); Photo source=photos.get(edit.photoIndex());
                Bitmap donor=decode(source.uri(),edge,false);
                try {
                    FaceGeometry target=photos.get(baseIndex).faces().get(entry.getKey()).scaled(background.getWidth(),background.getHeight());
                    FaceGeometry from=source.faces().get(edit.faceIndex()).scaled(donor.getWidth(),donor.getHeight());
                    Rect targetRect=clip(target.box(),background,.25);
                    TunePatch part=new TunePatch(targetRect,patch(donor,clip(from.box(),donor,.5)),target,from,edit.adjustment(),entry.getKey()==selected);
                    if (entry.getKey()==selected) afterSelected=true;
                    if (afterSelected) remaining.add(part);
                    else apply(background,part,edit.adjustment());
                } finally { donor.recycle(); }
            }
            Box face=photos.get(baseIndex).faces().get(selected).box().scale(background.getWidth(),background.getHeight());
            double width=Math.max(face.width()*2,face.height()*2*aspect),height=width/aspect;
            Rect viewport=new Rect((int)Math.floor(face.cx()-width/2),(int)Math.floor(face.cy()-height/2),
                    (int)Math.ceil(face.cx()+width/2),(int)Math.ceil(face.cy()+height/2));
            return new PreparedTune(background,remaining,viewport);
        } catch (Exception | OutOfMemoryError error) { background.recycle(); throw error; }
    }
    private record TunePatch(Rect rect,PatchBlender.Patch donor,FaceGeometry target,FaceGeometry from,
                             Similarity.Adjustment adjustment,boolean selected) {}
    private static void apply(Bitmap image,TunePatch part,Similarity.Adjustment adjustment) {
        PatchBlender.Patch destination=patch(image,part.rect());
        PatchBlender.blend(destination,part.donor(),part.target(),part.from(),adjustment);
        Rect r=part.rect(); image.setPixels(destination.pixels(),0,r.width(),r.left,r.top,r.width(),r.height());
    }
    static final class PreparedTune implements AutoCloseable {
        private final Bitmap background;
        private final List<TunePatch> parts;
        final Rect viewport;
        private final int[] clean;
        PreparedTune(Bitmap background,List<TunePatch> parts,Rect viewport) {
            this.background=background; this.parts=parts; this.viewport=viewport;
            clean=new int[viewport.width()*viewport.height()]; Arrays.fill(clean,0xffe5e9e1);
            Rect visible=new Rect(viewport);
            if (visible.intersect(0,0,background.getWidth(),background.getHeight())) {
                background.getPixels(clean,(visible.top-viewport.top)*viewport.width()+visible.left-viewport.left,
                        viewport.width(),visible.left,visible.top,visible.width(),visible.height());
            }
        }
        Bitmap render(Similarity.Adjustment adjustment) {
            // Each patch sees preceding edits, including overlapping faces, just like final export.
            List<PatchBlender.Patch> rendered=new ArrayList<>();
            int[] frame=clean.clone();
            for (TunePatch part:parts) {
                PatchBlender.Patch destination=patch(background,part.rect());
                for (PatchBlender.Patch earlier:rendered) copyPatch(earlier,destination);
                PatchBlender.blend(destination,part.donor(),part.target(),part.from(),part.selected()?adjustment:part.adjustment());
                rendered.add(destination);
                copyPatch(destination,new PatchBlender.Patch(frame,viewport.width(),viewport.height(),viewport.left,viewport.top));
            }
            return Bitmap.createBitmap(frame,viewport.width(),viewport.height(),Bitmap.Config.ARGB_8888);
        }
        private static void copyPatch(PatchBlender.Patch from,PatchBlender.Patch to) {
            int left=Math.max(from.x(),to.x()),top=Math.max(from.y(),to.y());
            int right=Math.min(from.x()+from.width(),to.x()+to.width()),bottom=Math.min(from.y()+from.height(),to.y()+to.height());
            if (right<=left) return;
            for (int y=top;y<bottom;y++) System.arraycopy(from.pixels(),(y-from.y())*from.width()+left-from.x(),
                    to.pixels(),(y-to.y())*to.width()+left-to.x(),right-left);
        }
        @Override public void close() { background.recycle(); }
    }

    private static Rect clip(Box b,Bitmap image,double padding) {
        int left=Math.max(0,(int)Math.floor(b.left()-b.width()*padding));
        int top=Math.max(0,(int)Math.floor(b.top()-b.height()*padding));
        int right=Math.min(image.getWidth(),(int)Math.ceil(b.right()+b.width()*padding));
        int bottom=Math.min(image.getHeight(),(int)Math.ceil(b.bottom()+b.height()*padding));
        if (right<=left || bottom<=top) throw new IllegalArgumentException("Obličej je mimo snímek.");
        return new Rect(left,top,right,bottom);
    }
    private static PatchBlender.Patch patch(Bitmap image,Rect r) {
        int[] pixels=new int[r.width()*r.height()];
        image.getPixels(pixels,0,r.width(),r.left,r.top,r.width(),r.height());
        return new PatchBlender.Patch(pixels,r.width(),r.height(),r.left,r.top);
    }
    public Saved save(List<Photo> photos,int base,Map<Integer,Edit> edits) throws Exception {
        Bitmap result=render(photos,base,edits,EXPORT_EDGE);
        Uri uri=null;
        boolean committed=false;
        try {
            ContentValues values=new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,"LepsiMoment_"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))+".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/Lepsi moment");
            values.put(MediaStore.Images.Media.IS_PENDING,1);
            uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
            if (uri==null) throw new IOException("Galerie nepovolila uložení.");
            try (OutputStream stream=resolver.openOutputStream(uri)) {
                if (stream==null || !result.compress(Bitmap.CompressFormat.JPEG,97,stream))
                    throw new IOException("Fotku se nepodařilo zapsat.");
            }
            ContentValues ready=new ContentValues(); ready.put(MediaStore.Images.Media.IS_PENDING,0);
            if (resolver.update(uri,ready,null,null)<1) throw new IOException("Fotku se nepodařilo dokončit.");
            committed=true;
            return new Saved(uri,result.getWidth(),result.getHeight());
        } finally {
            result.recycle();
            if (!committed && uri!=null) resolver.delete(uri,null,null);
        }
    }
    @Override public void close() { detector.close(); contours.close(); }
}
