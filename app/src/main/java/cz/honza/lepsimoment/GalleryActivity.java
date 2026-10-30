package cz.honza.lepsimoment;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import java.util.*;
import java.util.concurrent.*;

/** Local gallery with a zoomable preview that replaces only the grid's viewport. */
public final class GalleryActivity extends ComponentActivity {
    private static final int BG=0xfff7f8f2,INK=0xff213c31,GREEN=0xff176b54;
    private record Entry(Uri uri,String name) {}
    private final ArrayList<Entry> entries=new ArrayList<>();
    private final ArrayList<Uri> imported=new ArrayList<>();
    private final LinkedHashSet<Uri> selected=new LinkedHashSet<>();
    private final ExecutorService thumbnails=Executors.newFixedThreadPool(2), loader=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final LruCache<Uri,Bitmap> cache=new LruCache<>(24*1024*1024) {
        @Override protected int sizeOf(Uri key,Bitmap value) { return value.getAllocationByteCount(); }
    };
    private GridView grid;
    private GridAdapter adapter;
    private FrameLayout viewport,expanded;
    private FacePreviewView fullPhoto;
    private CheckBox fullCheck;
    private TextView title,empty,fullStatus,position;
    private Button done,access;
    private Uri expandedUri;
    private Future<?> fullJob;
    private int queryGeneration=0,previewGeneration=0,restorePosition=0;
    private boolean destroyed,updatingCheck;
    private ActivityResultLauncher<String[]> permissions;
    private ActivityResultLauncher<PickVisualMediaRequest> systemPicker;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state!=null) {
            ArrayList<Uri> selection=state.getParcelableArrayList("selection"),extra=state.getParcelableArrayList("imported");
            if (selection!=null) selected.addAll(selection);
            if (extra!=null) imported.addAll(extra);
            expandedUri=state.getParcelable("expanded"); restorePosition=state.getInt("gridPosition");
        }
        permissions=registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),result->refreshGallery());
        systemPicker=registerForActivityResult(new ActivityResultContracts.PickMultipleVisualMedia(6),uris->{
            for (Uri uri:uris) {
                try { getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (SecurityException ignored) { }
                if (!imported.contains(uri)) imported.add(uri);
            }
            refreshGallery();
        });
        build();
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { if (expandedUri!=null) collapse(); else finish(); }
        });
        if (state==null && !hasGalleryAccess()) access.post(this::requestAccess);
    }
    @Override protected void onResume() { super.onResume(); if (grid!=null) refreshGallery(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putParcelableArrayList("selection",new ArrayList<>(selected));
        state.putParcelableArrayList("imported",imported); state.putParcelable("expanded",expandedUri);
        state.putInt("gridPosition",grid.getFirstVisiblePosition());
    }
    private void build() {
        LinearLayout page=column(); page.setBackgroundColor(BG); setContentView(page);
        page.setOnApplyWindowInsetsListener((v,insets)->{
            page.setPadding(dp(14)+insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                    dp(14)+insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets;
        });
        LinearLayout header=row(); header.setGravity(Gravity.CENTER_VERTICAL);
        Button back=button("‹",this::finish); back.setContentDescription("Zrušit výběr fotek"); header.addView(back,new LinearLayout.LayoutParams(dp(44),dp(52)));
        title=label("Vyberte 2–6 fotek",19); header.addView(title,new LinearLayout.LayoutParams(0,-2,1)); page.addView(header);
        TextView hint=label("Zvětšit: ikona vpravo nahoře · Přiblížit: dva prsty",12); hint.setPadding(0,0,0,dp(10)); page.addView(hint);
        LinearLayout sources=row();
        access=button("Přístup k fotkám",this::requestAccess); sources.addView(access,new LinearLayout.LayoutParams(0,dp(44),1));
        Button other=button("Další zdroj…",()->systemPicker.launch(new PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build()));
        sources.addView(other,new LinearLayout.LayoutParams(0,dp(44),1)); page.addView(sources);
        viewport=new FrameLayout(this); page.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
        grid=new GridView(this); grid.setNumColumns(3); grid.setHorizontalSpacing(dp(5)); grid.setVerticalSpacing(dp(5)); grid.setClipToPadding(false);
        grid.setPadding(0,dp(8),0,dp(8)); adapter=new GridAdapter(); grid.setAdapter(adapter); viewport.addView(grid,new FrameLayout.LayoutParams(-1,-1));
        empty=label("Načítám fotky…",15); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(20),dp(20),dp(20),dp(20)); viewport.addView(empty,new FrameLayout.LayoutParams(-1,-1));
        grid.setEmptyView(empty);
        expanded=new FrameLayout(this); expanded.setBackgroundColor(BG); expanded.setVisibility(View.GONE); viewport.addView(expanded,new FrameLayout.LayoutParams(-1,-1));
        fullPhoto=new FacePreviewView(this); fullPhoto.setFaceSelectionEnabled(false); fullPhoto.onHorizontalSwipe=this::step;
        fullPhoto.setContentDescription("Zvětšená fotka. Tahem doleva nebo doprava přepnete fotku. Přibližte dvěma prsty, přiblíženou fotku posouvejte jedním prstem.");
        expanded.addView(fullPhoto,new FrameLayout.LayoutParams(-1,-1));
        fullCheck=new CheckBox(this); fullCheck.setText("Vybrat fotku"); fullCheck.setTextColor(INK); fullCheck.setBackground(shape(0xeef7f8f2));
        FrameLayout.LayoutParams checkParams=new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.LEFT); checkParams.setMargins(dp(8),dp(8),0,0); expanded.addView(fullCheck,checkParams);
        fullCheck.setOnCheckedChangeListener((view,checked)->{ if (!updatingCheck && expandedUri!=null) toggle(expandedUri); });
        View shrink=new FrameIcon(false,this::collapse); FrameLayout.LayoutParams shrinkParams=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP|Gravity.RIGHT); shrinkParams.setMargins(0,dp(8),dp(8),0); expanded.addView(shrink,shrinkParams);
        fullStatus=label("",14); fullStatus.setGravity(Gravity.CENTER); fullStatus.setBackground(shape(0xeef7f8f2)); fullStatus.setVisibility(View.GONE);
        expanded.addView(fullStatus,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
        LinearLayout nav=row(); nav.setGravity(Gravity.CENTER_VERTICAL); nav.setBackground(shape(0xeef7f8f2));
        Button previous=button("‹",()->step(-1)); previous.setContentDescription("Předchozí fotka"); nav.addView(previous,new LinearLayout.LayoutParams(dp(56),dp(48)));
        position=label("",12); position.setGravity(Gravity.CENTER); nav.addView(position,new LinearLayout.LayoutParams(0,-2,1));
        Button next=button("›",()->step(1)); next.setContentDescription("Další fotka"); nav.addView(next,new LinearLayout.LayoutParams(dp(56),dp(48)));
        expanded.addView(nav,new FrameLayout.LayoutParams(-1,dp(48),Gravity.BOTTOM));
        done=button("Použít vybrané fotky (0/6)",()->{
            if (selected.size()<2 || selected.size()>6) return;
            setResult(RESULT_OK,new Intent().putParcelableArrayListExtra("photos",new ArrayList<>(selected))); finish();
        });
        done.setTextColor(Color.WHITE); done.setBackground(shape(GREEN));
        LinearLayout.LayoutParams footer=new LinearLayout.LayoutParams(-1,dp(52)); footer.topMargin=dp(10); footer.bottomMargin=dp(10); page.addView(done,footer);
        updateSelection();
    }
    private boolean hasGalleryAccess() {
        return Build.VERSION.SDK_INT>=33 ? checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)==PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT>=34 && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED) :
                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;
    }
    private void requestAccess() {
        if (Build.VERSION.SDK_INT>=34) permissions.launch(new String[]{Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED});
        else if (Build.VERSION.SDK_INT>=33) permissions.launch(new String[]{Manifest.permission.READ_MEDIA_IMAGES});
        else permissions.launch(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE});
    }
    private void refreshGallery() {
        int generation=++queryGeneration;
        boolean allowed=hasGalleryAccess();
        boolean partial=Build.VERSION.SDK_INT>=34 && checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)!=PackageManager.PERMISSION_GRANTED && allowed;
        access.setText(partial?"Změnit dostupné fotky":"Přístup k fotkám");
        access.setVisibility(allowed&&!partial?View.GONE:View.VISIBLE);
        ArrayList<Uri> extras=new ArrayList<>(imported);
        loader.execute(()->{
            ArrayList<Entry> result=new ArrayList<>(); String failure=null;
            try {
                for (Uri uri:extras) {
                    // Verify grants again after returning from background or permission changes.
                    try (var file=getContentResolver().openAssetFileDescriptor(uri,"r")) {
                        if (file!=null) result.add(new Entry(uri,"Přidaná fotka"));
                    } catch (Exception ignored) { }
                }
                if (allowed) {
                    Uri collection=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
                    try (Cursor cursor=getContentResolver().query(collection,new String[]{MediaStore.Images.Media._ID,MediaStore.Images.Media.DISPLAY_NAME},
                            MediaStore.Images.Media.IS_PENDING+"=0",null,MediaStore.Images.Media.DATE_ADDED+" DESC, "+MediaStore.Images.Media._ID+" DESC")) {
                        if (cursor!=null) while (cursor.moveToNext()) {
                            if (Thread.currentThread().isInterrupted()) return;
                            Uri uri=ContentUris.withAppendedId(collection,cursor.getLong(0));
                            if (!extras.contains(uri)) result.add(new Entry(uri,cursor.getString(1)));
                        }
                    }
                }
            } catch (RuntimeException e) { failure="Fotky nelze načíst. Zkontrolujte přístup k fotkám nebo použijte Další zdroj."; }
            String message=failure;
            main.post(()->{
                if (destroyed || generation!=queryGeneration) return;
                boolean changed=!entries.equals(result);
                entries.clear(); entries.addAll(result);
                Set<Uri> available=new HashSet<>(); for (Entry entry:entries) available.add(entry.uri());
                selected.retainAll(available);
                // Keep decoded thumbnails across gallery refreshes; release only inaccessible items.
                for (Uri cached:cache.snapshot().keySet()) if (!available.contains(cached)) cache.remove(cached);
                if (changed) adapter.notifyDataSetChanged();
                updateSelection();
                empty.setText(message!=null?message:allowed?"Zatím tu nejsou dostupné fotky. Přidejte je přes Další zdroj nebo změňte přístup k fotkám.":"Povolte přístup k fotkám nebo vyberte snímky přes Další zdroj. Potom je zde můžete zvětšit a označit.");
                if (restorePosition>0) { grid.setSelection(restorePosition); restorePosition=0; }
                if (expandedUri!=null) { if (available.contains(expandedUri)) expand(expandedUri); else collapse(); }
            });
        });
    }
    private void toggle(Uri uri) {
        if (!selected.remove(uri)) {
            if (selected.size()==6) Toast.makeText(this,"Lze vybrat nejvýše 6 fotek.",Toast.LENGTH_SHORT).show();
            else selected.add(uri);
        }
        updateSelection();
    }
    private void updateSelection() {
        // Selection does not change the data set or require rebinding/decoding photo cells.
        for (int i=0;i<grid.getChildCount();i++) ((Cell)grid.getChildAt(i)).updateCheck();
        done.setText("Použít vybrané fotky ("+selected.size()+"/6)"); done.setEnabled(selected.size()>=2); done.setAlpha(selected.size()>=2?1:.5f);
        updatingCheck=true; fullCheck.setChecked(expandedUri!=null && selected.contains(expandedUri)); updatingCheck=false;
        fullCheck.setContentDescription(fullCheck.isChecked()?"Zrušit výběr této fotky":"Vybrat tuto fotku");
    }
    private void expand(Uri uri) {
        if (fullJob!=null) fullJob.cancel(true);
        // INVISIBLE preserves the measured grid, its scroll position and attached thumbnails.
        expandedUri=uri; grid.setVisibility(View.INVISIBLE); empty.setVisibility(View.GONE); expanded.setVisibility(View.VISIBLE);
        int index=indexOf(uri); position.setText("Fotka "+(index+1)+" / "+entries.size()); updateSelection();
        int generation=++previewGeneration;
        fullPhoto.setContent(thumbnail(uri),List.of(),-1,Set.of(),uri.hashCode(),false);
        fullStatus.setVisibility(View.GONE);
        fullJob=loader.submit(()->{
            Bitmap image=null; String error=null;
            try { image=PhotoEngine.decode(getContentResolver(),uri,2560,false); }
            catch (Exception | OutOfMemoryError e) { error="Fotku nelze otevřít. Zkuste ji nejdřív stáhnout do telefonu nebo obnovit přístup."; }
            Bitmap loaded=image; String message=error;
            main.post(()->{
                if (destroyed || generation!=previewGeneration) { if (loaded!=null) loaded.recycle(); return; }
                if (loaded!=null) { fullPhoto.setContent(loaded,List.of(),-1,Set.of(),uri.hashCode(),false); fullStatus.setVisibility(View.GONE); }
                else { fullStatus.setText(message); fullStatus.setVisibility(View.VISIBLE); }
            });
        });
    }
    private Bitmap thumbnail(Uri uri) {
        // Visible cells pin their images even if the bounded LRU cache has evicted them.
        for (int i=0;i<grid.getChildCount();i++) {
            Cell cell=(Cell)grid.getChildAt(i);
            if (uri.equals(cell.uri) && cell.bitmap!=null) return cell.bitmap;
        }
        return cache.get(uri);
    }
    private int indexOf(Uri uri) { for (int i=0;i<entries.size();i++) if (entries.get(i).uri().equals(uri)) return i; return -1; }
    private void step(int direction) {
        int index=indexOf(expandedUri)+direction;
        if (index>=0 && index<entries.size()) expand(entries.get(index).uri());
    }
    private void collapse() {
        if (fullJob!=null) fullJob.cancel(true);
        expandedUri=null; ++previewGeneration; expanded.setVisibility(View.GONE);
        fullPhoto.setContent(null,List.of(),-1,Set.of(),-1,false);
        grid.setVisibility(entries.isEmpty()?View.GONE:View.VISIBLE); empty.setVisibility(entries.isEmpty()?View.VISIBLE:View.GONE);
    }
    private final class GridAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int index,View recycled,ViewGroup parent) {
            Cell cell=recycled instanceof Cell?(Cell)recycled:new Cell(); cell.bind(entries.get(index),index); return cell;
        }
    }
    private final class Cell extends FrameLayout {
        final ImageView photo;
        final CheckBox check;
        Future<?> job;
        Uri uri;
        Bitmap bitmap;
        int index;
        Cell() {
            super(GalleryActivity.this); setBackground(shape(0xffe5e9e1));
            photo=new ImageView(GalleryActivity.this); photo.setScaleType(ImageView.ScaleType.FIT_CENTER); addView(photo,new FrameLayout.LayoutParams(-1,-1));
            check=new CheckBox(GalleryActivity.this); check.setBackground(shape(0xddf7f8f2));
            addView(check,new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP|Gravity.LEFT));
            check.setClickable(false); check.setFocusable(false); check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            setOnClickListener(v->toggle(uri));
            View zoom=new FrameIcon(true,()->expand(uri)); addView(zoom,new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP|Gravity.RIGHT));
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec) { super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthSpec),MeasureSpec.EXACTLY)); }
        void bind(Entry entry,int index) {
            this.index=index;
            boolean same=entry.uri().equals(uri);
            if (!same && job!=null) { job.cancel(true); job=null; }
            uri=entry.uri(); updateCheck();
            photo.setContentDescription(entry.name());
            Bitmap hit=same && bitmap!=null?bitmap:cache.get(uri); bitmap=hit; photo.setImageBitmap(hit);
            if (hit!=null) return;
            if (same && job!=null && !job.isDone()) return;
            Uri requested=uri;
            job=thumbnails.submit(()->{
                Bitmap image;
                try { image=PhotoEngine.decode(getContentResolver(),requested,360,false); }
                catch (Exception | OutOfMemoryError e) { return; }
                if (Thread.currentThread().isInterrupted()) { image.recycle(); return; }
                cache.put(requested,image);
                main.post(()->{ if (!destroyed && requested.equals(uri)) { bitmap=image; photo.setImageBitmap(image); } });
            });
        }
        void updateCheck() {
            boolean chosen=selected.contains(uri);
            check.setChecked(chosen); check.setVisibility(chosen?View.VISIBLE:View.INVISIBLE);
            setSelected(chosen); setContentDescription("Fotka "+(index+1)+(chosen?", vybraná. Klepnutím zrušíte výběr.":", nevybraná. Klepnutím vyberete."));
        }
    }
    private final class FrameIcon extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final boolean enlarge;
        FrameIcon(boolean enlarge,Runnable action) {
            super(GalleryActivity.this); this.enlarge=enlarge; setAlpha(.65f);
            setContentDescription(enlarge?"Zvětšit fotku":"Zpět do mřížky fotek"); setFocusable(true); setOnClickListener(v->action.run());
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // Reduce only the visible icon by one third, retaining the easy-to-tap target.
            float size=Math.min(getWidth(),getHeight())*2f/3, left=(getWidth()-size)/2, top=(getHeight()-size)/2;
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xeef7f8f2);
            canvas.drawRoundRect(left,top,left+size,top+size,dp(8),dp(8),paint);
            paint.setColor(GREEN); paint.setStrokeWidth(dp(2)*2f/3); paint.setStyle(Paint.Style.STROKE);
            float a=size*.29f,b=size*.71f,d=dp(6)*2f/3;
            for (int x=0;x<2;x++) for (int y=0;y<2;y++) {
                float px=left+(x==0?a:b),py=top+(y==0?a:b),sx=x==0?1:-1,sy=y==0?1:-1;
                if (!enlarge) { px+=sx*d; py+=sy*d; sx=-sx; sy=-sy; }
                canvas.drawLine(px,py,px+sx*d,py,paint); canvas.drawLine(px,py,px,py+sy*d,paint);
            }
        }
    }
    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { return new LinearLayout(this); }
    private TextView label(String s,int size) { TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(INK); return t; }
    private Button button(String s,Runnable click) {
        Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(13); b.setTextColor(GREEN); b.setBackground(shape(BG)); b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(dp(8),0,dp(8),0); b.setStateListAnimator(null); b.setOnClickListener(v->click.run()); return b;
    }
    private GradientDrawable shape(int color) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(12)); return d; }
    @Override protected void onDestroy() { destroyed=true; thumbnails.shutdownNow(); loader.shutdownNow(); cache.evictAll(); super.onDestroy(); }
}
