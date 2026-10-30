package cz.honza.lepsimoment;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.lifecycle.ViewModelProvider;
import cz.honza.lepsimoment.core.FaceGeometry;
import cz.honza.lepsimoment.core.Similarity;
import java.util.*;

public final class MainActivity extends ComponentActivity {
    private static final int BG=0xfff7f8f2, INK=0xff213c31, MUTED=0xff6b786d, GREEN=0xff176b54,
            LIME=0xffe0edc4, BORDER=0xffdce2d6, WHITE=0xffffffff;
    private EditorModel model;
    private FrameLayout root;
    private FacePreviewView preview;
    private boolean comparing=false;
    private int insetTop=0,insetBottom=0,insetLeft=0,insetRight=0;
    private ActivityResultLauncher<Intent> picker;
    private FacePreviewView tunePreview;
    private TextView tuneStatus;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        model=new ViewModelProvider(this).get(EditorModel.class);
        picker=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{
            if (result.getResultCode()!=RESULT_OK || result.getData()==null) return;
            ArrayList<Uri> uris=result.getData().getParcelableArrayListExtra("photos");
            if (uris==null) return;
            if (uris.isEmpty()) return;
            if (new LinkedHashSet<>(uris).size()<2 || uris.size()>6) {
                model.report("Vyberte 2 až 6 různých fotek stejné skupiny."); return;
            }
            for (Uri uri:uris) try { getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { }
            comparing=false; model.load(uris,null);
        });
        root=new FrameLayout(this); root.setBackgroundColor(BG); setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            insetTop=insets.getSystemWindowInsetTop(); insetBottom=insets.getSystemWindowInsetBottom();
            insetLeft=insets.getSystemWindowInsetLeft(); insetRight=insets.getSystemWindowInsetRight();
            root.setPadding(insetLeft,insetTop,insetRight,insetBottom); return insets;
        });
        preview=new FacePreviewView(this); preview.onFaceSelected=index->{ comparing=false; model.selectFace(index); };
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){
            @Override public void handleOnBackPressed() {
                if (model.busy) { Toast.makeText(MainActivity.this,"Dokončuji zpracování…",Toast.LENGTH_SHORT).show(); return; }
                if (model.tuning) { model.finishTune(false); return; }
                if (!model.home) { model.home=true; model.refresh(); }
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });
        model.updates.observe(this,revision->{
            render();
            if (model.message!=null) {
                String message=model.message; model.message=null;
                new AlertDialog.Builder(this).setTitle("Lepší moment").setMessage(message).setPositiveButton("Rozumím",null).show();
            }
            if (model.saved!=null) {
                PhotoEngine.Saved saved=model.saved; model.saved=null;
                new AlertDialog.Builder(this).setTitle("Lepší moment je v galerii")
                        .setMessage("Nová fotka je ve složce Pictures / Lepsi moment.\n"+saved.width()+" × "+saved.height()+" px · JPG\n\nPůvodní fotky zůstaly beze změny.")
                        .setPositiveButton("Otevřít",(dialog,which)->openPhoto(saved.uri()))
                        .setNeutralButton("Sdílet",(dialog,which)->sharePhoto(saved.uri()))
                        .setNegativeButton("Hotovo",null).show();
            }
        });
        model.tuneImage.observe(this,image->{
            if (tunePreview!=null && image!=null) {
                tunePreview.setContent(image,List.of(),-1,Set.of(),model.selected,false);
                tuneStatus.setVisibility(View.GONE);
            }
        });
        model.tuneError.observe(this,error->{
            if (tuneStatus!=null && error!=null) { tuneStatus.setText(error); tuneStatus.setVisibility(View.VISIBLE); }
        });
    }
    private void render() {
        root.removeAllViews();
        tunePreview=null; tuneStatus=null;
        LinearLayout page=column(); page.setPadding(dp(20),0,dp(20),0);
        root.addView(page,new FrameLayout.LayoutParams(-1,-1));
        if (model.tuning) tunePage(page);
        else if (model.home || model.photos.isEmpty()) home(page);
        else if (getResources().getConfiguration().screenHeightDp<650 || getResources().getConfiguration().fontScale>1.25f) {
            ScrollView scroll=new ScrollView(this); LinearLayout content=column(); scroll.addView(content);
            page.addView(scroll,new LinearLayout.LayoutParams(-1,-1)); editor(content);
        } else editor(page);
        if (model.busy) {
            FrameLayout overlay=new FrameLayout(this); overlay.setBackgroundColor(0xeef7f8f2); overlay.setClickable(true); overlay.setFocusable(true);
            LinearLayout box=column(); box.setGravity(Gravity.CENTER); box.setPadding(dp(24),dp(24),dp(24),dp(24));
            ProgressBar spinner=new ProgressBar(this); spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(GREEN));
            box.addView(spinner,new LinearLayout.LayoutParams(dp(40),dp(40)));
            TextView text=text(model.progress,17,INK,true); text.setGravity(Gravity.CENTER); text.setPadding(0,dp(18),0,0); box.addView(text);
            TextView local=text("Zpracování probíhá v telefonu",12,MUTED,false); local.setPadding(0,dp(10),0,0); box.addView(local);
            FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER); overlay.addView(box,bp);
            root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
            overlay.announceForAccessibility(model.progress);
        }
    }
    private void home(LinearLayout page) {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout content=column(); content.setPadding(0,dp(14),0,dp(20)); scroll.addView(content); page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout header=row(); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=text("◉  LEPŠÍ MOMENT",12,GREEN,true); brand.setLetterSpacing(.12f); header.addView(brand,new LinearLayout.LayoutParams(0,dp(44),1));
        header.addView(button("?",false,this::help),new LinearLayout.LayoutParams(dp(48),dp(44))); content.addView(header);
        TextView tag=text("JEDNA FOTKA. VŠICHNI VE SVÉM.",10,MUTED,true); tag.setLetterSpacing(.12f); tag.setPadding(0,dp(27),0,dp(12)); content.addView(tag);
        TextView title=text("Každý má svůj\nnejlepší moment.",36,INK,true); title.setLineSpacing(dp(1),1); content.addView(title);
        TextView subtitle=text("Spojte nejlepší výrazy z několika snímků do jedné společné fotky.",16,MUTED,false); subtitle.setLineSpacing(dp(4),1); subtitle.setPadding(0,dp(16),dp(12),0); content.addView(subtitle);
        content.addView(new MomentArtwork(this),new LinearLayout.LayoutParams(-1,dp(224)));
        LinearLayout steps=column(); steps.setPadding(dp(18),dp(5),dp(18),dp(5)); steps.setBackground(bg(WHITE,20,BORDER));
        step(steps,"01","Vyberte společné fotky","2–6 podobných snímků krátce po sobě.");
        step(steps,"02","Najděte ten pravý výraz","Klepněte na člověka a vyberte lepší záběr.");
        step(steps,"03","Uložte lepší moment","Nová fotka se objeví přímo v galerii."); content.addView(steps);
        Button pick=button("Vybrat 2–6 fotek  ↗",true,this::pickPhotos); LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(56)); pp.topMargin=dp(10); page.addView(pick,pp);
        if (model.hasSavedSession() || !model.photos.isEmpty()) {
            Button resume=button("Pokračovat v poslední úpravě",false,()->{ comparing=false; model.restore(); });
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(48)); rp.topMargin=dp(8); page.addView(resume,rp);
        }
        TextView privacy=text("Jen ve vašem telefonu · Bez účtu · Bez nahrávání",11,MUTED,false); privacy.setGravity(Gravity.CENTER); privacy.setPadding(0,dp(12),0,dp(12)); page.addView(privacy);
    }
    private void step(LinearLayout parent,String number,String heading,String detail) {
        LinearLayout line=row(); line.setGravity(Gravity.CENTER_VERTICAL); line.setPadding(0,dp(12),0,dp(12));
        TextView n=text(number,13,GREEN,true); line.addView(n,new LinearLayout.LayoutParams(dp(35),-2));
        LinearLayout words=column(); words.addView(text(heading,14,INK,true));
        TextView d=text(detail,12,MUTED,false); d.setPadding(0,dp(3),0,0); words.addView(d); line.addView(words,new LinearLayout.LayoutParams(0,-2,1)); parent.addView(line);
    }
    private void editor(LinearLayout page) {
        LinearLayout header=row(); header.setGravity(Gravity.CENTER_VERTICAL);
        Button back=button("‹",false,()->{ model.home=true; model.refresh(); }); back.setTextSize(28); back.setContentDescription("Zpět na úvod");
        header.addView(back,new LinearLayout.LayoutParams(dp(40),dp(48)));
        TextView brand=text("Lepší moment",19,INK,true); brand.setPadding(dp(8),0,0,0); header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        Button help=button("?",false,this::help); help.setContentDescription("Nápověda"); header.addView(help,new LinearLayout.LayoutParams(dp(40),dp(44))); page.addView(header);
        LinearLayout section=row(); section.setGravity(Gravity.CENTER_VERTICAL);
        TextView label=text("1  HLAVNÍ FOTKA",10,MUTED,true); label.setLetterSpacing(.1f); section.addView(label,new LinearLayout.LayoutParams(0,dp(25),1));
        section.addView(text(model.photos.size()+" snímky",11,MUTED,false)); page.addView(section);
        LinearLayout frames=row();
        for (int i=0;i<model.photos.size();i++) {
            final int index=i; LinearLayout card=column(); card.setPadding(dp(3),dp(3),dp(3),dp(3));
            card.setBackground(bg(i==model.base?GREEN:WHITE,12,i==model.base?GREEN:BORDER));
            ImageView iv=new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); iv.setImageBitmap(model.photos.get(i).thumbnail());
            card.addView(iv,new LinearLayout.LayoutParams(dp(70),dp(44)));
            TextView caption=text((i==model.base?"✓ ":"")+"Fotka "+(i+1),10,i==model.base?WHITE:MUTED,i==model.base); caption.setGravity(Gravity.CENTER); card.addView(caption);
            card.setContentDescription("Fotka "+(i+1)+(i==model.base?", hlavní":", vybrat jako hlavní")); card.setOnClickListener(v->switchBase(index));
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(78),dp(68)); cp.rightMargin=dp(8); frames.addView(card,cp);
        }
        page.addView(horizontal(frames),new LinearLayout.LayoutParams(-1,dp(77)));
        LinearLayout instruction=row(); instruction.setGravity(Gravity.CENTER_VERTICAL);
        TextView select=text(model.selected<0?"2  Klepněte na obličej":"2  Osoba "+(model.selected+1),14,INK,true); instruction.addView(select,new LinearLayout.LayoutParams(0,dp(40),1));
        Button boxes=button(model.showFaceBoxes?"Rámečky ✓":"Rámečky",false,()->{ model.showFaceBoxes=!model.showFaceBoxes; render(); });
        boxes.setTextSize(10); boxes.setContentDescription(model.showFaceBoxes?"Skrýt rámečky obličejů":"Zobrazit rámečky obličejů");
        instruction.addView(boxes,new LinearLayout.LayoutParams(dp(92),dp(40)));
        Button compare=button(comparing?"Úprava":"Před / Po",false,()->{ comparing=!comparing; render(); }); compare.setTextSize(11); compare.setEnabled(!model.edits.isEmpty());
        instruction.addView(compare,new LinearLayout.LayoutParams(dp(80),dp(40))); page.addView(instruction);
        if (preview.getParent()!=null) ((ViewGroup)preview.getParent()).removeView(preview);
        preview.setContent(comparing?model.original:model.preview,model.photos.get(model.base).faces(),model.selected,model.edits.keySet(),model.photos.get(model.base).uri().hashCode(),model.showFaceBoxes&&!comparing);
        preview.setBottomLabel(comparing?"PŮVODNÍ FOTKA":null);
        preview.setFaceSelectionEnabled(!comparing);
        preview.setEnabled(!model.busy);
        if (getResources().getConfiguration().screenHeightDp<650 || getResources().getConfiguration().fontScale>1.25f)
            page.addView(preview,new LinearLayout.LayoutParams(-1,dp(270)));
        else page.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout people=row(); people.setPadding(0,dp(6),0,dp(4));
        var faces=model.photos.get(model.base).faces();
        if (faces.isEmpty()) {
            TextView empty=text("Nenašel jsem obličej. Zvolte jinou hlavní fotku.",12,MUTED,false); empty.setPadding(0,dp(8),0,dp(8)); page.addView(empty);
        } else {
            for (int i=0;i<faces.size();i++) {
                final int index=i; Button b=button((model.edits.containsKey(i)?"✓ ":"")+"Osoba "+(i+1),false,()->{ comparing=false; model.selectFace(index); });
                b.setTextSize(11); b.setBackground(bg(i==model.selected?LIME:WHITE,20,i==model.selected?GREEN:BORDER));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(91),dp(42)); lp.rightMargin=dp(6); people.addView(b,lp);
            }
            page.addView(horizontal(people),new LinearLayout.LayoutParams(-1,dp(52)));
        }
        candidates(page);
        LinearLayout footer=row(); footer.setGravity(Gravity.CENTER_VERTICAL); footer.setPadding(0,dp(8),0,dp(10));
        Button undo=button("↶ Zpět",false,()->{ comparing=false; model.undo(); }); undo.setEnabled(!model.undo.isEmpty());
        LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(dp(86),dp(50)); up.rightMargin=dp(10); footer.addView(undo,up);
        Button save=button("Uložit novou fotku  ↓",true,model::save); save.setEnabled(!model.edits.isEmpty()); footer.addView(save,new LinearLayout.LayoutParams(0,dp(50),1)); page.addView(footer);
    }
    private void candidates(LinearLayout page) {
        LinearLayout panel=column(); panel.setPadding(dp(12),dp(10),dp(12),dp(8)); panel.setBackground(bg(WHITE,18,BORDER));
        if (model.selected<0) {
            TextView heading=text("Každý může vypadat skvěle.",16,INK,true); panel.addView(heading);
            TextView text=text("Vyberte obličej na fotce nebo osobu v řádku nad tímto panelem. Pak zvolte její lepší výraz.",13,MUTED,false); text.setLineSpacing(dp(3),1); text.setPadding(0,dp(9),0,dp(10)); panel.addView(text);
            page.addView(panel,new LinearLayout.LayoutParams(-1,-2)); return;
        }
        if (!model.photos.get(model.base).faces().get(model.selected).canAlign()) {
            panel.addView(text("Pro tento obličej nejsou vidět obě oči.",14,INK,true));
            TextView help=text("Zvolte jinou hlavní fotku s pohledem více do objektivu.",12,MUTED,false); help.setPadding(0,dp(10),0,dp(10)); panel.addView(help); page.addView(panel); return;
        }
        LinearLayout title=row(); title.setGravity(Gravity.CENTER_VERTICAL);
        title.addView(text("3  Vyberte lepší výraz",14,INK,true),new LinearLayout.LayoutParams(0,dp(28),1));
        Button tune=button("Doladit",false,this::tune); tune.setTextSize(11); tune.setEnabled(model.edits.containsKey(model.selected)); title.addView(tune,new LinearLayout.LayoutParams(dp(72),dp(32))); panel.addView(title);
        TextView note=text("Nabídka podle polohy. Vyberte stejnou osobu.",10,MUTED,false); note.setPadding(0,0,0,dp(7)); panel.addView(note);
        LinearLayout options=row(); options.addView(candidate(model.base,model.selected,true,null));
        FaceGeometry target=model.photos.get(model.base).faces().get(model.selected);
        for (int i=0;i<model.photos.size();i++) {
            if (i==model.base) continue;
            var faces=model.photos.get(i).faces(); int best=-1; double score=Double.MAX_VALUE;
            for (int j=0;j<faces.size();j++) {
                if (!faces.get(j).canAlign()) continue;
                var b=faces.get(j).box();
                double distance=Math.hypot(b.cx()-target.box().cx(),b.cy()-target.box().cy());
                if (distance<score) { score=distance; best=j; }
            }
            PhotoEngine.Edit current=model.edits.get(model.selected);
            if (current!=null && current.photoIndex()==i) best=current.faceIndex();
            if (best>=0) options.addView(candidate(i,best,false,null));
        }
        panel.addView(horizontal(options),new LinearLayout.LayoutParams(-1,dp(94)));
        Button all=button("Vybrat z dalších obličejů…",false,this::allFaces); all.setTextSize(11); panel.addView(all,new LinearLayout.LayoutParams(-1,dp(34)));
        page.addView(panel,new LinearLayout.LayoutParams(-1,-2));
    }
    private View candidate(int photoIndex,int faceIndex,boolean original,Runnable after) {
        var photo=model.photos.get(photoIndex); var face=photo.faces().get(faceIndex);
        var chosen=model.edits.get(model.selected);
        boolean active=original?chosen==null:chosen!=null&&chosen.photoIndex()==photoIndex&&chosen.faceIndex()==faceIndex;
        LinearLayout card=column(); card.setGravity(Gravity.CENTER); card.setPadding(dp(4),dp(3),dp(4),dp(3));
        card.setBackground(bg(active?LIME:BG,13,active?GREEN:BORDER));
        ImageView thumb=new ImageView(this); thumb.setScaleType(ImageView.ScaleType.CENTER_CROP); thumb.setImageBitmap(faceThumbnail(photo,face));
        card.addView(thumb,new LinearLayout.LayoutParams(dp(62),dp(59)));
        TextView label=text(original?"Původní":"Fotka "+(photoIndex+1),10,active?GREEN:INK,active); label.setGravity(Gravity.CENTER); label.setPadding(0,dp(3),0,0); card.addView(label);
        card.setContentDescription((original?"Původní výraz":"Použít výraz osoby "+(faceIndex+1)+" z fotky "+(photoIndex+1))+(active?", vybráno":""));
        card.setOnClickListener(v->{ comparing=false; if (after!=null) after.run(); model.replace(photoIndex,faceIndex); });
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(76),dp(87)); cp.rightMargin=dp(7); card.setLayoutParams(cp); return card;
    }
    private Bitmap faceThumbnail(PhotoEngine.Photo photo,FaceGeometry face) {
        Bitmap source=photo.thumbnail(); var b=face.box();
        double cx=b.cx()*source.getWidth(),cy=b.cy()*source.getHeight();
        double size=Math.max(b.width()*source.getWidth(),b.height()*source.getHeight())*1.25;
        int left=Math.max(0,(int)(cx-size/2)),top=Math.max(0,(int)(cy-size/2));
        int right=Math.min(source.getWidth(),(int)Math.ceil(cx+size/2)),bottom=Math.min(source.getHeight(),(int)Math.ceil(cy+size/2));
        if (right<=left || bottom<=top) return source;
        Bitmap crop=Bitmap.createBitmap(source,left,top,right-left,bottom-top);
        Bitmap thumb=Bitmap.createScaledBitmap(crop,dp(80),dp(80),true);
        if (crop!=source && crop!=thumb) crop.recycle(); return thumb;
    }
    private void allFaces() {
        ScrollView scroll=new ScrollView(this); LinearLayout content=column(); content.setPadding(dp(16),dp(8),dp(16),dp(16)); scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Najděte stejnou osobu").setView(scroll).setNegativeButton("Zavřít",null).create();
        for (int i=0;i<model.photos.size();i++) {
            if (i==model.base) continue;
            TextView label=text("FOTKA "+(i+1),11,MUTED,true); label.setPadding(0,dp(16),0,dp(8)); content.addView(label);
            ImageView full=new ImageView(this); full.setAdjustViewBounds(true); full.setImageBitmap(model.photos.get(i).thumbnail()); full.setMaxHeight(dp(150)); content.addView(full,new LinearLayout.LayoutParams(-1,dp(130)));
            LinearLayout faces=row(); faces.setPadding(0,dp(8),0,0);
            for (int j=0;j<model.photos.get(i).faces().size();j++) if (model.photos.get(i).faces().get(j).canAlign()) faces.addView(candidate(i,j,false,dialog::dismiss));
            if (faces.getChildCount()==0) content.addView(text("Na tomto snímku není vhodný čelně viditelný obličej.",12,MUTED,false));
            else content.addView(horizontal(faces));
        }
        dialog.show();
    }
    private void tune() {
        comparing=false; model.startTune();
    }
    private void tunePage(LinearLayout page) {
        LinearLayout header=row(); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading=text("Doladit obličej",20,INK,true); heading.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(heading,new LinearLayout.LayoutParams(0,dp(54),1));
        header.addView(button("Výchozí",false,()->{ model.updateTune(Similarity.Adjustment.defaults()); render(); }),new LinearLayout.LayoutParams(dp(82),dp(44)));
        page.addView(header);
        FrameLayout picture=new FrameLayout(this);
        tunePreview=new FacePreviewView(this); tunePreview.setFaceSelectionEnabled(false);
        tunePreview.setContentDescription("Živý náhled upravovaného obličeje s okolím. Lze přiblížit dvěma prsty.");
        Bitmap frame=model.tuneImage.getValue();
        tunePreview.setContent(frame,List.of(),-1,Set.of(),model.selected,false);
        picture.addView(tunePreview,new FrameLayout.LayoutParams(-1,-1));
        tuneStatus=text(frame==null?"Připravuji živý náhled…":"",13,INK,true); tuneStatus.setGravity(Gravity.CENTER);
        tuneStatus.setPadding(dp(12),dp(8),dp(12),dp(8)); tuneStatus.setBackgroundColor(0xeef7f8f2);
        tuneStatus.setVisibility(frame==null?View.VISIBLE:View.GONE);
        picture.addView(tuneStatus,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        picture.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{ if (r>l && b>t) model.prepareTune((double)(r-l)/(b-t)); });
        page.addView(picture,new LinearLayout.LayoutParams(-1,0,1));
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout controls=column(); controls.setPadding(0,dp(6),0,dp(6)); scroll.addView(controls);
        TextView hint=text("Změny se zobrazují při pohybu posuvníků.",11,MUTED,false); controls.addView(hint);
        var a=model.draft;
        slider(controls,"Posun vodorovně",a.offsetX(),-.18,.18," %",100,v->setDraft(0,v));
        slider(controls,"Posun svisle",a.offsetY(),-.18,.18," %",100,v->setDraft(1,v));
        slider(controls,"Velikost obličeje",a.scale(),.8,1.2," %",100,v->setDraft(2,v));
        slider(controls,"Natočení",a.rotation(),-12,12,"°",1,v->setDraft(3,v));
        slider(controls,"Velikost masky",a.maskExpansion(),-.18,.18," %",100,v->setDraft(4,v));
        TextView maskHint=text("− zúží okraj masky · + rozšíří okraj masky",11,MUTED,false); controls.addView(maskHint);
        slider(controls,"Měkkost okrajů",a.feather(),.06,.32," %",100,v->setDraft(5,v));
        CheckBox color=new CheckBox(this); color.setText("Sladit barvy s hlavní fotkou"); color.setTextColor(INK); color.setChecked(a.matchColor());
        color.setOnCheckedChangeListener((v,checked)->setDraft(6,checked?1:0)); controls.addView(color);
        page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=row(); actions.setPadding(0,dp(6),0,dp(10));
        actions.addView(button("Zrušit",false,()->model.finishTune(false)),new LinearLayout.LayoutParams(0,dp(48),1));
        actions.addView(button("Použít",true,()->model.finishTune(true)),new LinearLayout.LayoutParams(0,dp(48),1)); page.addView(actions);
    }
    private void setDraft(int field,double value) {
        var a=model.draft;
        model.updateTune(new Similarity.Adjustment(field==0?value:a.offsetX(),field==1?value:a.offsetY(),
                field==2?value:a.scale(),field==3?value:a.rotation(),field==5?value:a.feather(),
                field==6?value==1:a.matchColor(),field==4?value:a.maskExpansion()));
    }
    private SeekBar slider(LinearLayout parent,String name,double current,double min,double max,String unit,double factor,java.util.function.DoubleConsumer change) {
        TextView label=text(name+" · "+Math.round(current*factor)+unit,13,INK,true); label.setPadding(0,dp(9),0,dp(4)); parent.addView(label);
        SeekBar bar=new SeekBar(this); bar.setMax(200); bar.setProgress((int)Math.round(200*(current-min)/(max-min))); bar.setContentDescription(name);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int v,boolean user) { double n=value(b,min,max); label.setText(name+" · "+Math.round(n*factor)+unit); change.accept(n); }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { }
        }); parent.addView(bar,new LinearLayout.LayoutParams(-1,dp(38))); return bar;
    }
    private double value(SeekBar bar,double min,double max) { return min+(max-min)*bar.getProgress()/bar.getMax(); }
    private void switchBase(int index) {
        if (index==model.base || model.busy) return;
        Runnable choose=()->{ comparing=false; model.chooseBase(index); };
        if (model.edits.isEmpty()) choose.run();
        else new AlertDialog.Builder(this).setTitle("Změnit hlavní fotku?").setMessage("Dosavadní výměny obličejů se tím zruší.")
                .setPositiveButton("Změnit",(d,w)->choose.run()).setNegativeButton("Zůstat",null).show();
    }
    private void pickPhotos() {
        picker.launch(new Intent(this,GalleryActivity.class));
    }
    private void help() {
        new AlertDialog.Builder(this).setTitle("Jak na přirozený výsledek")
                .setMessage("Vyberte 2–6 fotek stejné skupiny pořízených krátce po sobě. V mřížce klepněte na fotku pro výběr; zaškrtnutí se ukáže až po vybrání. Menší průhledná ikona vpravo nahoře zvětší fotku v oblasti mřížky. Ve zvětšeném náhledu zůstává checkbox stále viditelný. Tahem doleva nebo doprava přepínejte snímky. Přibližujte dvěma prsty; přiblíženou fotku jedním prstem posouvejte.\n\n1. Vyberte hlavní fotku.\n2. Klepněte na obličej. Tlačítkem Rámečky lze skrýt označení. I pak funguje výběr osoby a zoom.\n3. Zvolte výraz stejného člověka z další fotky. Nabídka vychází z polohy, nerozpoznává identitu. Pokud se lidé přesunuli, použijte další obličeje.\n4. Porovnejte Před / Po při stejném zvětšení a posunu; původní snímek poznáte podle průhledného popisku přes fotku. Doladit zobrazí nahoře obličej s okolím a dole posuvníky s živým náhledem. Velikost masky zúží (−) nebo rozšíří (+) oblast náhrady bez změny velikosti obličeje. Použít potvrdí, Zrušit vrátí předchozí stav.\n5. Uložte novou fotku.\n\nNejlépe fungují čelní, ostré tváře se stejným osvětlením. Velké otočení hlavy, vlasy nebo ruka před obličejem mohou zanechat viditelné okraje.\n\nZpracování je offline. Fotky ani obličeje se nikam neposílají. Ukládá se JPG (SDR), nejvýše 4096 px na delší straně, bez původních GPS údajů. Jde o testovací verzi, nikoli aplikaci od Googlu.")
                .setPositiveButton("Rozumím",null).show();
    }
    private void openPhoto(Uri uri) {
        try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
        catch (ActivityNotFoundException e) { model.report("Fotku najdete v Galerii ve složce Lepsi moment."); }
    }
    private void sharePhoto(Uri uri) {
        Intent intent=new Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("Lepší moment",uri)); startActivity(Intent.createChooser(intent,"Sdílet fotku"));
    }
    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private TextView text(String string,int size,int color,boolean bold) {
        TextView view=new TextView(this); view.setText(string); view.setTextSize(size); view.setTextColor(color); view.setIncludeFontPadding(false);
        view.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL)); return view;
    }
    private Button button(String title,boolean primary,Runnable action) {
        Button b=new Button(this); b.setText(title); b.setTextSize(13); b.setAllCaps(false); b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setTextColor(primary?WHITE:GREEN); b.setBackground(bg(primary?GREEN:BG,16,primary?GREEN:BG)); b.setMinWidth(0); b.setMinHeight(0); b.setMinimumWidth(0); b.setMinimumHeight(0);
        b.setPadding(dp(8),0,dp(8),0); b.setStateListAnimator(null);
        b.setOnClickListener(v->{ if (!model.busy) action.run(); });
        b.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{primary?0xffa6c0b4:0xffa2aca0,primary?WHITE:GREEN}));
        return b;
    }
    private GradientDrawable bg(int color,int radius,int stroke) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); d.setStroke(dp(1),stroke); return d;
    }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private HorizontalScrollView horizontal(LinearLayout child) {
        HorizontalScrollView scroll=new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false); scroll.setClipToPadding(false); scroll.addView(child); return scroll;
    }
}
