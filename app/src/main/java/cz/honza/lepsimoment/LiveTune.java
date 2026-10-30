package cz.honza.lepsimoment;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import cz.honza.lepsimoment.core.Similarity;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** One render at a time, with only the most recent pending slider values retained. */
final class LiveTune implements AutoCloseable {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final BiConsumer<Bitmap,String> listener;
    private PhotoEngine.PreparedTune prepared;
    private Similarity.Adjustment pending;
    private boolean running=true,closed;

    LiveTune(PhotoEngine engine,List<PhotoEngine.Photo> photos,int base,Map<Integer,PhotoEngine.Edit> edits,
             int selected,double aspect,Similarity.Adjustment initial,BiConsumer<Bitmap,String> listener) {
        this.listener=listener; pending=initial;
        worker.execute(()->{
            try { prepared=engine.prepareTune(photos,base,edits,selected,aspect); drain(); }
            catch (Exception | OutOfMemoryError error) { deliver(null,"Náhled se nepodařilo připravit. Zkuste doladění otevřít znovu."); }
        });
    }
    synchronized void request(Similarity.Adjustment adjustment) {
        if (closed) return;
        pending=adjustment;
        if (!running && prepared!=null) { running=true; worker.execute(this::drain); }
    }
    private void drain() {
        while (true) {
            Similarity.Adjustment adjustment;
            synchronized (this) {
                if (closed || pending==null) { running=false; return; }
                adjustment=pending; pending=null;
            }
            try { deliver(prepared.render(adjustment),null); }
            catch (CancellationException ignored) { return; }
            catch (IllegalArgumentException e) { deliver(null,e.getMessage()); }
            catch (OutOfMemoryError e) { deliver(null,"Pro tento náhled není dost paměti. Zmenšete velikost masky nebo zvolte jinou fotku."); }
        }
    }
    private void deliver(Bitmap image,String error) {
        main.post(()->{
            synchronized (this) {
                if (closed) { if (image!=null) image.recycle(); return; }
            }
            listener.accept(image,error);
        });
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed=true; pending=null;
        // Release cached pixels on their own worker after an in-flight render has finished.
        worker.execute(()->{ if (prepared!=null) prepared.close(); });
        worker.shutdown();
    }
}
