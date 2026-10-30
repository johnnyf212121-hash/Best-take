package cz.honza.lepsimoment;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import cz.honza.lepsimoment.core.Similarity;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class EditorInteractionTest {
    private final Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private void await(BooleanSupplier ready) throws Exception {
        long deadline=SystemClock.uptimeMillis()+60000;
        while (SystemClock.uptimeMillis()<deadline) {
            boolean[] result={false}; onMain(()->result[0]=ready.getAsBoolean());
            if (result[0]) return; Thread.sleep(40);
        }
        fail("Asynchronous UI operation timed out");
    }
    private void onMain(Runnable run) { InstrumentationRegistry.getInstrumentation().runOnMainSync(run); }
    private static View find(View view,java.util.function.Predicate<View> test) {
        if (test.test(view)) return view;
        if (view instanceof ViewGroup group) for (int i=0;i<group.getChildCount();i++) {
            View found=find(group.getChildAt(i),test); if (found!=null) return found;
        }
        return null;
    }
    private static View byText(View root,String text) { return find(root,v->v instanceof TextView && text.contentEquals(((TextView)v).getText())); }
    private static View byDescription(View root,String description) { return find(root,v->description.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())); }
    private void rendered() throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        java.util.concurrent.CountDownLatch frames=new java.util.concurrent.CountDownLatch(1);
        onMain(()->Choreographer.getInstance().postFrameCallback(t->Choreographer.getInstance().postFrameCallback(t2->frames.countDown())));
        assertTrue(frames.await(3,java.util.concurrent.TimeUnit.SECONDS));
    }
    private void capture(String name) throws Exception {
        rendered();
        // Activity/checkbox transitions run independently of the view's draw callbacks.
        Thread.sleep(500);
        Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        try (OutputStream output=new FileOutputStream(new File(app.getExternalFilesDir(null),name))) { screenshot.compress(Bitmap.CompressFormat.PNG,100,output); }
        screenshot.recycle();
    }
    private static void event(View view,long start,long time,int action,float span,int count) {
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[count];
        for (int i=0;i<count;i++) {
            properties[i]=new MotionEvent.PointerProperties(); properties[i].id=i; properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords(); coords[i].x=view.getWidth()/2f+(i==0?-span/2:span/2); coords[i].y=view.getHeight()/2f;
            coords[i].pressure=1; coords[i].size=1;
        }
        MotionEvent e=MotionEvent.obtain(start,time,action,count,properties,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        view.dispatchTouchEvent(e); e.recycle();
    }
    private static void pinch(FacePreviewView view) {
        long now=SystemClock.uptimeMillis(); float width=view.getWidth();
        event(view,now,now,MotionEvent.ACTION_DOWN,width*.25f,1);
        event(view,now,now+20,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),width*.25f,2);
        for (int i=1;i<=8;i++) event(view,now,now+20+i*20,MotionEvent.ACTION_MOVE,width*(.25f+i*.07f),2);
        event(view,now,now+200,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),width*.81f,2);
        event(view,now,now+220,MotionEvent.ACTION_UP,width*.81f,1);
    }
    private static void drag(View view,float fromX,float fromY,float toX,float toY,boolean cancel) {
        long now=SystemClock.uptimeMillis();
        for (int i=0;i<=10;i++) {
            int action=i==0?MotionEvent.ACTION_DOWN:i==10?(cancel?MotionEvent.ACTION_CANCEL:MotionEvent.ACTION_UP):MotionEvent.ACTION_MOVE;
            MotionEvent e=MotionEvent.obtain(now,now+i*20,action,view.getWidth()*(fromX+(toX-fromX)*i/10),view.getHeight()*(fromY+(toY-fromY)*i/10),0);
            view.dispatchTouchEvent(e); e.recycle();
        }
    }
    private static void swipe(View view,int direction) { drag(view,direction>0?.8f:.2f,.5f,direction>0?.2f:.8f,.5f,false); }
    private static Bitmap thumbnail(View cell) { return ((BitmapDrawable)((ImageView)find(cell,v->v instanceof ImageView)).getDrawable()).getBitmap(); }
    private static CheckBox check(View cell) { return (CheckBox)find(cell,v->v instanceof CheckBox); }
    @Test public void cachedLivePreviewMatchesFinalPixelsWithoutAccumulatingChanges() throws Exception {
        List<Uri> uris=new PhotoWorkflowTest().fixtures();
        try (PhotoEngine engine=new PhotoEngine(app.getContentResolver())) {
            List<PhotoEngine.Photo> photos=List.of(engine.analyze(uris.get(0)),engine.analyze(uris.get(1)));
            Map<Integer,PhotoEngine.Edit> edits=new LinkedHashMap<>();
            edits.put(0,new PhotoEngine.Edit(1,0,Similarity.Adjustment.defaults()));
            edits.put(1,new PhotoEngine.Edit(1,1,Similarity.Adjustment.defaults()));
            var adjustment=new Similarity.Adjustment(.04,-.03,1.04,2,.12,false,.12);
            try (PhotoEngine.PreparedTune prepared=engine.prepareTune(photos,0,edits,0,1.1)) {
                Bitmap first=prepared.render(adjustment);
                Bitmap intermediate=prepared.render(new Similarity.Adjustment(-.08,.08,.91,-4,.22,true,-.1));
                Bitmap again=prepared.render(adjustment); assertTrue(first.sameAs(again));
                edits.put(0,new PhotoEngine.Edit(1,0,adjustment)); Bitmap finalImage=engine.render(photos,0,edits,PhotoEngine.PREVIEW_EDGE);
                Rect r=prepared.viewport;
                for (int y=0;y<first.getHeight();y++) for (int x=0;x<first.getWidth();x++) {
                    int sx=r.left+x,sy=r.top+y;
                    if (sx>=0 && sy>=0 && sx<finalImage.getWidth() && sy<finalImage.getHeight())
                        assertEquals("Live preview must match final composition",finalImage.getPixel(sx,sy),first.getPixel(x,y));
                }
                first.recycle(); intermediate.recycle(); again.recycle(); finalImage.recycle();
            }
        }
    }
    @Test public void editorZoomHiddenBoxesAndLiveTuneApplyCancelUndo() throws Exception {
        List<Uri> uris=new PhotoWorkflowTest().fixtures(); AtomicReference<EditorModel> ref=new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{ EditorModel m=new ViewModelProvider(a).get(EditorModel.class); ref.set(m); m.load(uris,null); });
            await(()->!ref.get().busy && ref.get().preview!=null);
            scenario.onActivity(a->{
                View root=a.getWindow().getDecorView(); FacePreviewView preview=(FacePreviewView)find(root,v->v instanceof FacePreviewView);
                pinch(preview); assertTrue("Main photo pinch zoom",preview.zoomFactor()>1.5);
                byDescription(root,"Skrýt rámečky obličejů").performClick(); assertFalse(ref.get().showFaceBoxes);
                FacePreviewView same=(FacePreviewView)find(root,v->v instanceof FacePreviewView); assertSame(preview,same); assertTrue(same.zoomFactor()>1.5);
                ref.get().selectFace(0); ref.get().replace(1,0);
            });
            await(()->!ref.get().busy);
            int undoBefore=ref.get().undo.size(); Bitmap committed=ref.get().preview;
            scenario.onActivity(a->byText(a.getWindow().getDecorView(),"Doladit").performClick());
            await(()->ref.get().tuneImage.getValue()!=null);
            Bitmap before=ref.get().tuneImage.getValue();
            scenario.onActivity(a->{
                View root=a.getWindow().getDecorView();
                FacePreviewView face=(FacePreviewView)find(root,v->v instanceof FacePreviewView);
                ScrollView controls=(ScrollView)find(root,v->v instanceof ScrollView);
                assertEquals("Preview and sliders occupy equal areas",face.getHeight(),controls.getHeight(),2);
                ((SeekBar)byDescription(root,"Velikost masky")).setProgress(160);
                ((SeekBar)byDescription(root,"Posun vodorovně")).setProgress(145);
                ((SeekBar)byDescription(root,"Natočení")).setProgress(120);
            });
            await(()->ref.get().tuneImage.getValue()!=before);
            onMain(()->{ assertSame("Draft does not overwrite committed editor",committed,ref.get().preview); assertEquals(undoBefore,ref.get().undo.size()); });
            capture("tune-live-02.png");
            scenario.recreate();
            scenario.onActivity(a->{
                EditorModel model=new ViewModelProvider(a).get(EditorModel.class); assertTrue(model.tuning); assertEquals(.108,model.draft.maskExpansion(),.001);
                byText(a.getWindow().getDecorView(),"Použít").performClick();
            });
            await(()->!ref.get().busy);
            onMain(()->{ assertEquals(.108,ref.get().edits.get(0).adjustment().maskExpansion(),.001); assertEquals(undoBefore+1,ref.get().undo.size()); });
            org.json.JSONObject saved=new org.json.JSONObject(app.getSharedPreferences("editing_session",0).getString("session","{}"));
            assertEquals(.108,saved.getJSONArray("edits").getJSONObject(0).getDouble("mask"),.001);
            scenario.onActivity(a->ref.get().undo()); await(()->!ref.get().busy);
            onMain(()->assertEquals(0,ref.get().edits.get(0).adjustment().maskExpansion(),0));
            scenario.onActivity(a->{ ref.get().startTune(); ref.get().updateTune(new Similarity.Adjustment(0,0,1,0,.16,true,-.12)); ref.get().finishTune(false); });
            onMain(()->{ assertFalse(ref.get().tuning); assertEquals(0,ref.get().edits.get(0).adjustment().maskExpansion(),0); assertSame(null,ref.get().tuneImage.getValue()); });
        }
    }
    @Test public void galleryExpandSelectZoomCollapseAndRestoreSelection() throws Exception {
        ArrayList<Uri> created=new ArrayList<>(); boolean hadPermission=app.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)==PackageManager.PERMISSION_GRANTED;
        try {
            var automation=InstrumentationRegistry.getInstrumentation().getUiAutomation();
            if (!hadPermission) automation.grantRuntimePermission(app.getPackageName(),Manifest.permission.READ_MEDIA_IMAGES);
            Uri source=new PhotoWorkflowTest().fixtures().get(1);
            for (int i=0;i<7;i++) {
                ContentValues values=new ContentValues(); values.put(MediaStore.Images.Media.DISPLAY_NAME,"LepsiMoment_ui_test_"+i+".png");
                values.put(MediaStore.Images.Media.MIME_TYPE,"image/png"); values.put(MediaStore.Images.Media.IS_PENDING,1);
                Uri uri=app.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values); assertNotNull(uri); created.add(uri);
                try (InputStream in=app.getContentResolver().openInputStream(source); OutputStream out=app.getContentResolver().openOutputStream(uri)) { in.transferTo(out); }
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING,0); app.getContentResolver().update(uri,values,null,null);
            }
            try (ActivityScenario<GalleryActivity> scenario=ActivityScenario.launch(GalleryActivity.class)) {
                AtomicReference<GridView> grid=new AtomicReference<>();
                ArrayList<View> cells=new ArrayList<>(); ArrayList<Bitmap> thumbs=new ArrayList<>();
                scenario.onActivity(a->grid.set((GridView)find(a.getWindow().getDecorView(),v->v instanceof GridView)));
                await(()->{
                    if (grid.get().getChildCount()<7) return false;
                    for (int i=0;i<7;i++) if (((ImageView)find(grid.get().getChildAt(i),v->v instanceof ImageView)).getDrawable()==null) return false;
                    return true;
                });
                scenario.onActivity(a->{
                    for (int i=0;i<grid.get().getChildCount();i++) { cells.add(grid.get().getChildAt(i)); thumbs.add(thumbnail(cells.get(i))); }
                    View first=cells.get(0); assertEquals(View.INVISIBLE,check(first).getVisibility());
                    first.performClick(); assertTrue(check(first).isChecked()); assertEquals(View.VISIBLE,check(first).getVisibility());
                    first.performClick(); assertFalse(check(first).isChecked()); assertEquals(View.INVISIBLE,check(first).getVisibility());
                    cells.get(1).performClick();
                    assertTrue("Enlarge icon is translucent",byDescription(first,"Zvětšit fotku").getAlpha()<1);
                });
                capture("gallery-grid-03.png");
                scenario.onActivity(a->{
                    byDescription(cells.get(0),"Zvětšit fotku").performClick();
                    View root=a.getWindow().getDecorView(); assertEquals(View.INVISIBLE,grid.get().getVisibility());
                    assertSame("Hidden grid retains its attached cells",cells.get(0),grid.get().getChildAt(0));
                    CheckBox select=(CheckBox)byDescription(root,"Vybrat tuto fotku"); assertFalse(select.isChecked()); select.performClick(); assertTrue(select.isChecked());
                    assertEquals(View.VISIBLE,select.getVisibility());
                    assertNull("No loading caption in the enlarged photo",byText(root,"Načítám náhled…"));
                });
                rendered();
                scenario.onActivity(a->{
                    FacePreviewView photo=(FacePreviewView)find(a.getWindow().getDecorView(),v->v instanceof FacePreviewView);
                    assertEquals("Expanded image stays in the grid viewport",grid.get().getParent(),photo.getParent().getParent());
                    swipe(photo,1); assertNotNull(byText(a.getWindow().getDecorView(),"Fotka 2 / 7"));
                    assertTrue(((CheckBox)byDescription(a.getWindow().getDecorView(),"Zrušit výběr této fotky")).isChecked());
                    swipe(photo,-1); assertNotNull(byText(a.getWindow().getDecorView(),"Fotka 1 / 7"));
                    swipe(photo,-1); assertNotNull("No wrap before first photo",byText(a.getWindow().getDecorView(),"Fotka 1 / 7"));
                    drag(photo,.5f,.8f,.5f,.2f,false); assertNotNull("Vertical drag must not page",byText(a.getWindow().getDecorView(),"Fotka 1 / 7"));
                    drag(photo,.8f,.5f,.2f,.5f,true); assertNotNull("Canceled drag must not page",byText(a.getWindow().getDecorView(),"Fotka 1 / 7"));
                    pinch(photo); assertTrue(photo.zoomFactor()>1.5);
                    swipe(photo,1); assertNotNull("Pan of a zoomed photo must not page",byText(a.getWindow().getDecorView(),"Fotka 1 / 7"));
                });
                capture("gallery-fullscreen-03.png");
                scenario.onActivity(a->{
                    byDescription(a.getWindow().getDecorView(),"Zpět do mřížky fotek").performClick();
                    assertEquals(View.VISIBLE,grid.get().getVisibility());
                });
                rendered();
                scenario.onActivity(a->{
                    for (int i=0;i<cells.size();i++) {
                        assertSame("Returning does not rebuild grid cells",cells.get(i),grid.get().getChildAt(i));
                        assertSame("Returning keeps the decoded bitmap",thumbs.get(i),thumbnail(grid.get().getChildAt(i)));
                    }
                    byDescription(cells.get(0),"Zvětšit fotku").performClick();
                });
                scenario.recreate();
                scenario.onActivity(a->grid.set((GridView)find(a.getWindow().getDecorView(),v->v instanceof GridView)));
                await(()->grid.get().getCount()>=3);
                scenario.onActivity(a->{
                    View root=a.getWindow().getDecorView(); CheckBox check=(CheckBox)byDescription(root,"Zrušit výběr této fotky"); assertTrue(check.isChecked());
                    byDescription(root,"Zpět do mřížky fotek").performClick(); assertEquals(View.VISIBLE,grid.get().getVisibility());
                });
                await(()->grid.get().getChildCount()>=3);
                scenario.onActivity(a->{
                    assertTrue(check(grid.get().getChildAt(0)).isChecked());
                    assertTrue(byText(a.getWindow().getDecorView(),"Použít vybrané fotky (2/6)").isEnabled());
                    for (int i=2;i<6;i++) grid.get().getChildAt(i).performClick();
                    grid.get().getChildAt(6).performClick(); assertFalse(check(grid.get().getChildAt(6)).isChecked());
                    assertTrue(byText(a.getWindow().getDecorView(),"Použít vybrané fotky (6/6)").isEnabled());
                });
            }
        } finally {
            for (Uri uri:created) app.getContentResolver().delete(uri,null,null);
        }
    }
    @Test public void beforeAfterPreservesImageSizeZoomAndPan() throws Exception {
        List<Uri> uris=new PhotoWorkflowTest().fixtures(); AtomicReference<EditorModel> ref=new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{ EditorModel m=new ViewModelProvider(a).get(EditorModel.class); ref.set(m); m.load(uris,null); });
            await(()->!ref.get().busy && ref.get().preview!=null);
            scenario.onActivity(a->{ ref.get().selectFace(0); ref.get().replace(1,0); }); await(()->!ref.get().busy);
            AtomicReference<FacePreviewView> photo=new AtomicReference<>();
            for (int zoomed=0;zoomed<2;zoomed++) {
                final boolean enlarge=zoomed==1;
                scenario.onActivity(a->{
                    FacePreviewView v=(FacePreviewView)find(a.getWindow().getDecorView(),w->w instanceof FacePreviewView); photo.set(v);
                    if (enlarge) { pinch(v); drag(v,.65f,.5f,.3f,.5f,false); }
                });
                rendered(); RectF[] bounds={null}; int[] size=new int[2]; float[] zoom={0};
                scenario.onActivity(a->{ bounds[0]=photo.get().imageBounds(); size[0]=photo.get().getWidth(); size[1]=photo.get().getHeight(); zoom[0]=photo.get().zoomFactor(); });
                for (int toggle=0;toggle<4;toggle++) {
                    final boolean original=toggle%2==0;
                    scenario.onActivity(a->byText(a.getWindow().getDecorView(),original?"Před / Po":"Úprava").performClick());
                    rendered();
                    scenario.onActivity(a->{
                        FacePreviewView v=(FacePreviewView)find(a.getWindow().getDecorView(),w->w instanceof FacePreviewView);
                        assertSame(photo.get(),v); assertEquals(size[0],v.getWidth()); assertEquals(size[1],v.getHeight());
                        assertEquals(bounds[0],v.imageBounds()); assertEquals(zoom[0],v.zoomFactor(),0);
                        assertEquals(original?"PŮVODNÍ FOTKA":null,v.bottomLabel());
                        assertNull("Caption must not consume layout space",byText(a.getWindow().getDecorView(),"PŮVODNÍ FOTKA"));
                    });
                    if (toggle==0 && !enlarge) capture("before-overlay-03.png");
                }
            }
        }
    }
    @Test public void hiddenOverlaysStillAllowFaceSelectionButPinchNeverSelects() {
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<FacePreviewView> ref=new AtomicReference<>(); int[] hit={-1};
            scenario.onActivity(a->{
                FacePreviewView view=new FacePreviewView(a); ref.set(view);
                Bitmap bitmap=Bitmap.createBitmap(800,800,Bitmap.Config.ARGB_8888);
                var box=new cz.honza.lepsimoment.core.FaceGeometry.Box(.25,.25,.75,.75);
                var face=new cz.honza.lepsimoment.core.FaceGeometry(box,null,null,null,List.of(),0,0,null,null,null);
                view.setContent(bitmap,List.of(face),-1,Set.of(),1,false); view.onFaceSelected=i->hit[0]=i;
                a.setContentView(view);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(a->{ pinch(ref.get()); assertEquals(-1,hit[0]); assertTrue(ref.get().zoomFactor()>1.5); });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(a->{
                long now=SystemClock.uptimeMillis();
                event(ref.get(),now,now,MotionEvent.ACTION_DOWN,0,1); event(ref.get(),now,now+20,MotionEvent.ACTION_UP,0,1);
                assertEquals(0,hit[0]);
            });
        }
    }
}
