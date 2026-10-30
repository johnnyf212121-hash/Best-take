package cz.honza.lepsimoment;

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import cz.honza.lepsimoment.core.Similarity;
import org.json.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EditorModel extends AndroidViewModel {
    final MutableLiveData<Integer> updates=new MutableLiveData<>(0);
    final MutableLiveData<Bitmap> tuneImage=new MutableLiveData<>();
    final MutableLiveData<String> tuneError=new MutableLiveData<>();
    final List<PhotoEngine.Photo> photos=new ArrayList<>();
    final Map<Integer,PhotoEngine.Edit> edits=new LinkedHashMap<>();
    final Deque<Map<Integer,PhotoEngine.Edit>> undo=new ArrayDeque<>();
    int base=0, selected=-1;
    Bitmap original, preview;
    boolean busy=false, home=true;
    boolean showFaceBoxes=true, tuning=false;
    Similarity.Adjustment draft;
    private LiveTune liveTune;
    private double tuneAspect;
    String progress="", message;
    PhotoEngine.Saved saved;
    private final PhotoEngine engine;
    private final SharedPreferences prefs;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean closed=false;
    private int revision=0;

    public EditorModel(@NonNull Application app) {
        super(app);
        engine=new PhotoEngine(app.getContentResolver());
        prefs=app.getSharedPreferences("editing_session",0);
    }
    void refresh() { updates.setValue(++revision); }
    boolean hasSavedSession() { return prefs.contains("session"); }
    void report(String text) { message=text; refresh(); }
    void selectFace(int index) { if (!busy) { selected=index; refresh(); } }

    void restore() {
        if (!photos.isEmpty()) { home=false; refresh(); return; }
        try {
            JSONObject state=new JSONObject(prefs.getString("session","{}"));
            List<Uri> uris=new ArrayList<>();
            JSONArray array=state.getJSONArray("photos");
            for (int i=0;i<array.length();i++) uris.add(Uri.parse(array.getString(i)));
            load(uris,state);
        } catch (JSONException e) { prefs.edit().remove("session").apply(); report("Vyberte prosím fotky znovu."); }
    }
    void load(List<Uri> selection,JSONObject restoreState) {
        if (busy) return;
        List<Uri> uris=new ArrayList<>(new LinkedHashSet<>(selection));
        if (uris.size()<2 || uris.size()>6) { report("Vyberte 2 až 6 různých fotek stejné skupiny."); return; }
        busy=true; progress="Připravuji fotky…"; refresh();
        worker.execute(()->{
            try {
                List<PhotoEngine.Photo> loaded=new ArrayList<>();
                for (int i=0;i<uris.size();i++) {
                    final int number=i+1;
                    main.post(()->{ if (!closed) { progress="Hledám obličeje · "+number+" / "+uris.size(); refresh(); } });
                    loaded.add(engine.analyze(uris.get(i)));
                }
                int baseIndex=restoreState==null ? 0 : Math.max(0,Math.min(loaded.size()-1,restoreState.optInt("base")));
                Map<Integer,PhotoEngine.Edit> restored=new LinkedHashMap<>();
                boolean restoreSkipped=false;
                if (restoreState!=null) {
                    JSONArray array=restoreState.optJSONArray("edits");
                    if (array!=null) for (int i=0;i<array.length();i++) {
                        JSONObject e=array.getJSONObject(i);
                        int target=e.getInt("target"), photo=e.getInt("photo"), face=e.getInt("face");
                        if (target<0 || target>=loaded.get(baseIndex).faces().size() || photo<0 || photo>=loaded.size() ||
                                photo==baseIndex || face<0 || face>=loaded.get(photo).faces().size()) { restoreSkipped=true; continue; }
                        var tb=loaded.get(baseIndex).faces().get(target).box();
                        var sb=loaded.get(photo).faces().get(face).box();
                        if (Math.hypot(tb.cx()-e.getDouble("tx"),tb.cy()-e.getDouble("ty"))>.025 ||
                                Math.hypot(sb.cx()-e.getDouble("sx"),sb.cy()-e.getDouble("sy"))>.025) { restoreSkipped=true; continue; }
                        restored.put(target,new PhotoEngine.Edit(photo,face,new Similarity.Adjustment(
                                e.getDouble("x"),e.getDouble("y"),e.getDouble("scale"),e.getDouble("rotation"),
                                e.getDouble("feather"),e.getBoolean("color"),e.optDouble("mask",0))));
                    }
                }
                Bitmap baseBitmap=engine.decode(loaded.get(baseIndex).uri(),PhotoEngine.PREVIEW_EDGE,false);
                Bitmap result=restored.isEmpty() ? baseBitmap : engine.render(loaded,baseIndex,restored,PhotoEngine.PREVIEW_EDGE);
                final boolean skipped=restoreSkipped;
                main.post(()->{
                    if (closed) return;
                    releaseOldGrants(uris);
                    photos.clear(); photos.addAll(loaded); base=baseIndex; selected=-1;
                    original=baseBitmap; preview=result; edits.clear(); edits.putAll(restored); undo.clear();
                    busy=false; home=false; persist();
                    if (skipped) message="Některé úpravy se nepodařilo obnovit. Zkontrolujte výběr obličejů.";
                    refresh();
                });
            } catch (Exception | OutOfMemoryError e) { fail(e); }
        });
    }
    void chooseBase(int index) {
        if (busy || index==base) return;
        busy=true; progress="Připravuji hlavní fotku…"; refresh();
        worker.execute(()->{
            try {
                Bitmap image=engine.decode(photos.get(index).uri(),PhotoEngine.PREVIEW_EDGE,false);
                main.post(()->{
                    if (closed) return;
                    base=index; selected=-1; edits.clear(); undo.clear(); original=image; preview=image;
                    busy=false; persist(); refresh();
                });
            } catch (Exception | OutOfMemoryError e) { fail(e); }
        });
    }
    void replace(int photo,int face) {
        if (busy || selected<0) return;
        Map<Integer,PhotoEngine.Edit> next=new LinkedHashMap<>(edits);
        if (photo==base) next.remove(selected);
        else next.put(selected,new PhotoEngine.Edit(photo,face,Similarity.Adjustment.defaults()));
        render(next,true);
    }
    void adjust(Similarity.Adjustment adjustment) {
        if (busy || !edits.containsKey(selected)) return;
        PhotoEngine.Edit previous=edits.get(selected);
        Map<Integer,PhotoEngine.Edit> next=new LinkedHashMap<>(edits);
        next.put(selected,new PhotoEngine.Edit(previous.photoIndex(),previous.faceIndex(),adjustment));
        render(next,true);
    }
    void startTune() {
        if (busy || !edits.containsKey(selected)) return;
        tuning=true; draft=edits.get(selected).adjustment();
        tuneImage.setValue(null); tuneError.setValue(null); refresh();
    }
    void prepareTune(double aspect) {
        if (!tuning || (liveTune!=null && Math.abs(aspect-tuneAspect)<.02)) return;
        if (liveTune!=null) liveTune.close();
        tuneAspect=aspect;
        liveTune=new LiveTune(engine,new ArrayList<>(photos),base,new LinkedHashMap<>(edits),selected,aspect,draft,(image,error)->{
            if (image!=null) tuneImage.setValue(image);
            tuneError.setValue(error);
        });
    }
    void updateTune(Similarity.Adjustment adjustment) {
        if (!tuning) return;
        draft=adjustment; if (liveTune!=null) liveTune.request(adjustment);
    }
    void finishTune(boolean apply) {
        if (!tuning) return;
        Similarity.Adjustment result=draft;
        tuning=false;
        if (liveTune!=null) { liveTune.close(); liveTune=null; }
        tuneImage.setValue(null); tuneError.setValue(null);
        if (apply && !result.equals(edits.get(selected).adjustment())) adjust(result);
        else refresh();
    }
    void undo() {
        if (busy || undo.isEmpty()) return;
        render(undo.peekLast(),false);
    }
    private void render(Map<Integer,PhotoEngine.Edit> requested,boolean remember) {
        Map<Integer,PhotoEngine.Edit> next=new LinkedHashMap<>(requested);
        busy=true; progress="Spojuji lepší moment…"; refresh();
        worker.execute(()->{
            try {
                Bitmap result=next.isEmpty() ? original : engine.render(photos,base,next,PhotoEngine.PREVIEW_EDGE);
                main.post(()->{
                    if (closed) return;
                    if (remember) {
                        undo.addLast(new LinkedHashMap<>(edits));
                        while (undo.size()>15) undo.removeFirst();
                    } else undo.pollLast();
                    edits.clear(); edits.putAll(next); preview=result; busy=false; persist(); refresh();
                });
            } catch (Exception | OutOfMemoryError e) { fail(e); }
        });
    }
    void save() {
        if (busy || photos.isEmpty() || edits.isEmpty()) return;
        busy=true; progress="Ukládám novou fotku do galerie…"; refresh();
        Map<Integer,PhotoEngine.Edit> snapshot=new LinkedHashMap<>(edits);
        worker.execute(()->{
            try {
                PhotoEngine.Saved result=engine.save(photos,base,snapshot);
                main.post(()->{ if (!closed) { busy=false; saved=result; refresh(); } });
            } catch (Exception | OutOfMemoryError e) { fail(e); }
        });
    }
    private void fail(Throwable error) {
        main.post(()->{
            if (closed) return;
            busy=false;
            if (error instanceof OutOfMemoryError) message="Telefon nemá dost volné paměti. Zkuste jen 2 fotky a zavřete další aplikace.";
            else if (error instanceof IllegalArgumentException) message=error.getMessage();
            else message="Fotku se nepodařilo zpracovat. Stáhněte ji do telefonu a zkuste znovu. ("+error.getClass().getSimpleName()+")";
            refresh();
        });
    }
    private void persist() {
        try {
            JSONObject state=new JSONObject(); JSONArray list=new JSONArray();
            for (PhotoEngine.Photo p:photos) list.put(p.uri().toString());
            state.put("photos",list); state.put("base",base);
            JSONArray changes=new JSONArray();
            for (Map.Entry<Integer,PhotoEngine.Edit> entry:edits.entrySet()) {
                PhotoEngine.Edit edit=entry.getValue(); var a=edit.adjustment();
                var tb=photos.get(base).faces().get(entry.getKey()).box();
                var sb=photos.get(edit.photoIndex()).faces().get(edit.faceIndex()).box();
                JSONObject e=new JSONObject(); e.put("target",entry.getKey()); e.put("photo",edit.photoIndex()); e.put("face",edit.faceIndex());
                e.put("tx",tb.cx()); e.put("ty",tb.cy()); e.put("sx",sb.cx()); e.put("sy",sb.cy());
                e.put("x",a.offsetX()); e.put("y",a.offsetY()); e.put("scale",a.scale()); e.put("rotation",a.rotation());
                e.put("feather",a.feather()); e.put("color",a.matchColor()); e.put("mask",a.maskExpansion()); changes.put(e);
            }
            state.put("edits",changes); prefs.edit().putString("session",state.toString()).apply();
        } catch (JSONException ignored) { /* Current in-memory session remains usable. */ }
    }
    private void releaseOldGrants(List<Uri> keep) {
        for (var grant:getApplication().getContentResolver().getPersistedUriPermissions()) {
            if (!keep.contains(grant.getUri())) try {
                getApplication().getContentResolver().releasePersistableUriPermission(grant.getUri(),Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }
        }
    }
    @Override protected void onCleared() { closed=true; if (liveTune!=null) liveTune.close(); worker.shutdownNow(); engine.close(); }
}
