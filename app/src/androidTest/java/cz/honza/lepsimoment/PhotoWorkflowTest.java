package cz.honza.lepsimoment;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.Uri;
import android.media.ExifInterface;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import cz.honza.lepsimoment.core.Similarity;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PhotoWorkflowTest {
    private final Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Uri file(Bitmap image,String name) throws Exception {
        File f=new File(app.getFilesDir(),name);
        try (OutputStream stream=new FileOutputStream(f)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,stream)); }
        return Uri.fromFile(f);
    }
    List<Uri> fixtures() throws Exception {
        Bitmap portrait;
        try (InputStream stream=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("astronaut.png")) {
            portrait=BitmapFactory.decodeStream(stream);
        }
        Bitmap group=Bitmap.createBitmap(1024,512,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(group); canvas.drawBitmap(portrait,0,0,null); canvas.drawBitmap(portrait,512,0,null);
        Uri donor=file(group,"test-donor.png");
        // Synthetic blemish is a regression probe, not an example of a real second expression.
        Paint paint=new Paint(); paint.setColor(Color.rgb(20,190,140));
        canvas.drawCircle(220,140,7,paint);
        Uri base=file(group,"test-base.png");
        portrait.recycle(); group.recycle(); return Arrays.asList(base,donor);
    }
    @Test public void offlineMultiFaceDetectionCompositeAndGalleryExport() throws Exception {
        assertEquals(PackageManager.PERMISSION_DENIED,app.checkSelfPermission("android.permission.INTERNET"));
        List<Uri> uris=fixtures();
        try (PhotoEngine engine=new PhotoEngine(app.getContentResolver())) {
            PhotoEngine.Photo base=engine.analyze(uris.get(0)), donor=engine.analyze(uris.get(1));
            assertEquals(2,base.faces().size()); assertEquals(2,donor.faces().size());
            assertTrue(base.faces().get(0).canAlign());
            assertTrue("Each face receives its own contour",base.faces().get(0).outline().size()>=12);
            assertTrue("The second face also receives a contour",base.faces().get(1).outline().size()>=12);
            List<PhotoEngine.Photo> photos=Arrays.asList(base,donor);
            Map<Integer,PhotoEngine.Edit> edits=new LinkedHashMap<>();
            edits.put(0,new PhotoEngine.Edit(1,0,Similarity.Adjustment.defaults()));
            Bitmap original=engine.decode(uris.get(0),1600,false);
            Bitmap result=engine.render(photos,0,edits,1600);
            assertEquals(1024,result.getWidth()); assertEquals(512,result.getHeight());
            assertNotEquals("Selected face changes",original.getPixel(220,140),result.getPixel(220,140));
            for (int y=0;y<512;y+=7) for (int x=512;x<1024;x+=7)
                assertEquals("Other person is untouched",original.getPixel(x,y),result.getPixel(x,y));
            assertEquals(original.getPixel(10,10),result.getPixel(10,10));
            PhotoEngine.Saved saved=engine.save(photos,0,edits);
            try {
                Bitmap exported=engine.decode(saved.uri(),4096,false);
                assertEquals(1024,exported.getWidth()); assertEquals(512,exported.getHeight());
                exported.recycle();
                assertEquals("image/jpeg",app.getContentResolver().getType(saved.uri()));
            } finally { app.getContentResolver().delete(saved.uri(),null,null); }
            Bitmap unchanged=engine.decode(uris.get(0),1600,false);
            assertEquals("Source file remains untouched",original.getPixel(220,140),unchanged.getPixel(220,140));
            original.recycle(); result.recycle(); unchanged.recycle();
        }
    }
    @Test public void editorReplacementUndoAndActivityRecreation() throws Exception {
        List<Uri> uris=fixtures();
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<EditorModel> ref=new AtomicReference<>();
            scenario.onActivity(activity->{ EditorModel m=new ViewModelProvider(activity).get(EditorModel.class); ref.set(m); m.load(uris,null); });
            idle(ref);
            scenario.onActivity(activity->{ assertEquals(2,ref.get().photos.size()); ref.get().selectFace(0); ref.get().replace(1,0); });
            idle(ref);
            scenario.onActivity(activity->{ assertEquals(1,ref.get().edits.size()); ref.get().undo(); });
            idle(ref);
            scenario.onActivity(activity->{ assertTrue(ref.get().edits.isEmpty()); ref.get().replace(1,0); });
            idle(ref);
            scenario.recreate();
            scenario.onActivity(activity->{
                EditorModel m=new ViewModelProvider(activity).get(EditorModel.class);
                assertEquals(2,m.photos.size()); assertEquals(1,m.edits.size()); assertEquals(0,m.selected);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            File out=new File(app.getExternalFilesDir(null),"editor-test.png");
            try (OutputStream stream=new FileOutputStream(out)) { screenshot.compress(Bitmap.CompressFormat.PNG,100,stream); }
            screenshot.recycle();
        }
    }
    @Test public void exifRotationIsAppliedBeforeDetection() throws Exception {
        List<Uri> uris=fixtures();
        try (PhotoEngine engine=new PhotoEngine(app.getContentResolver())) {
            Bitmap upright=engine.decode(uris.get(1),1600,false);
            Matrix matrix=new Matrix(); matrix.postRotate(-90);
            Bitmap sideways=Bitmap.createBitmap(upright,0,0,upright.getWidth(),upright.getHeight(),matrix,true);
            File file=new File(app.getFilesDir(),"test-rotated.jpg");
            try (OutputStream out=new FileOutputStream(file)) { sideways.compress(Bitmap.CompressFormat.JPEG,100,out); }
            ExifInterface exif=new ExifInterface(file);
            exif.setAttribute(ExifInterface.TAG_ORIENTATION,String.valueOf(ExifInterface.ORIENTATION_ROTATE_90)); exif.saveAttributes();
            Bitmap decoded=engine.decode(Uri.fromFile(file),1600,false);
            assertEquals(1024,decoded.getWidth()); assertEquals(512,decoded.getHeight());
            PhotoEngine.Photo photo=engine.analyze(Uri.fromFile(file));
            assertEquals(2,photo.faces().size());
            assertEquals(1024,photo.width()); assertEquals(512,photo.height());
            upright.recycle(); sideways.recycle(); decoded.recycle();
        }
    }
    private void idle(AtomicReference<EditorModel> ref) throws Exception {
        long deadline=System.currentTimeMillis()+90000;
        while (System.currentTimeMillis()<deadline) {
            boolean[] busy={true};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->busy[0]=ref.get().busy);
            if (!busy[0]) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(()->assertNotNull(ref.get().preview));
                return;
            }
            Thread.sleep(100);
        }
        fail("Photo operation timed out");
    }
}
