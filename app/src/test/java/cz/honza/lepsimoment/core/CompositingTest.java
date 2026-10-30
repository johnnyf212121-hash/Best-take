package cz.honza.lepsimoment.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import cz.honza.lepsimoment.core.FaceGeometry.*;

public class CompositingTest {
    private FaceGeometry face() {
        return new FaceGeometry(new Box(10,10,90,90),new Point(35,35),new Point(65,35),new Point(50,50),
                List.of(new Point(10,10),new Point(90,10),new Point(90,90),new Point(10,90),
                        new Point(10,89),new Point(10,88),new Point(10,87),new Point(10,86),new Point(10,85),
                        new Point(10,84),new Point(10,83),new Point(10,82)),0,0,null,null,null);
    }
    private int[] solid(int color) { int[] p=new int[10000]; Arrays.fill(p,color); return p; }
    private PatchBlender.Patch patch(int[] pixels) { return new PatchBlender.Patch(pixels,100,100,0,0); }
    @Test public void identityTransformPreservesEveryPixel() {
        int[] pixels=new int[10000];
        for (int i=0;i<pixels.length;i++) pixels[i]=0xff000000|((i*29)&0xffffff);
        int[] expected=pixels.clone();
        PatchBlender.blend(patch(pixels),patch(expected),face(),face(),Similarity.Adjustment.defaults());
        assertArrayEquals(expected,pixels);
    }
    @Test public void donorChangesInteriorButNeverOutsideFace() {
        int[] destination=solid(0xff304050),donor=solid(0xffb08060);
        PatchBlender.blend(patch(destination),patch(donor),face(),face(),new Similarity.Adjustment(0,0,1,0,.16,false));
        assertEquals(0xffb08060,destination[50*100+50]);
        assertEquals(0xff304050,destination[4*100+50]);
        assertEquals(0xff304050,destination[95*100+50]);
        int edge=destination[11*100+50];
        assertNotEquals(0xffb08060,edge);
        assertNotEquals(0xff304050,edge);
    }
    @Test public void eyeAlignmentHandlesScaleRotationAndTranslation() {
        FaceGeometry from=face();
        Similarity expected=new Similarity(1.2,.4,30,-20);
        FaceGeometry target=new FaceGeometry(from.box(),expected.map(from.leftEye()),expected.map(from.rightEye()),
                expected.map(from.nose()),List.of(),0,0,null,null,null);
        Similarity actual=Similarity.align(from,target,Similarity.Adjustment.defaults());
        assertEquals(expected.a(),actual.a(),1e-9); assertEquals(expected.b(),actual.b(),1e-9);
        assertEquals(expected.tx(),actual.tx(),1e-9); assertEquals(expected.ty(),actual.ty(),1e-9);
        Point p=new Point(41,62), mapped=actual.map(p), back=actual.inverse(mapped.x(),mapped.y());
        assertEquals(p.x(),back.x(),1e-9); assertEquals(p.y(),back.y(),1e-9);
    }
    @Test public void manualTransformIsCenteredOnTargetEyes() {
        Similarity a=Similarity.align(face(),face(),new Similarity.Adjustment(.1,-.1,1.2,9,.16,true));
        Point center=a.map(new Point(50,35));
        assertEquals(58,center.x(),1e-9); assertEquals(27,center.y(),1e-9);
    }
    @Test public void colorMatchRemovesUniformSeamColorShift() {
        int[] destination=solid(0xff708090), donor=solid(0xff607080);
        PatchBlender.blend(patch(destination),patch(donor),face(),face(),Similarity.Adjustment.defaults());
        assertArrayEquals(solid(0xff708090),destination);
    }
    @Test public void erosionAndDilationMoveMaskBoundaryWithoutScalingDonor() {
        int background=0xff304050;
        int[] source=new int[10000];
        for (int y=0;y<100;y++) for (int x=0;x<100;x++) source[y*100+x]=0xff800000 | (x+80)<<8 | y+80;
        int[] normal=solid(background),eroded=solid(background),dilated=solid(background);
        int unchanged=PatchBlender.blend(patch(normal),patch(source),face(),face(),new Similarity.Adjustment(0,0,1,0,.06,false,0));
        int smaller=PatchBlender.blend(patch(eroded),patch(source),face(),face(),new Similarity.Adjustment(0,0,1,0,.06,false,-.1));
        int larger=PatchBlender.blend(patch(dilated),patch(source),face(),face(),new Similarity.Adjustment(0,0,1,0,.06,false,.1));
        assertTrue(smaller<unchanged); assertTrue(larger>unchanged);
        assertEquals(background,normal[50*100+5]); assertNotEquals(background,dilated[50*100+5]);
        assertEquals(background,eroded[50*100+14]); assertNotEquals(background,normal[50*100+14]);
        assertEquals(source[50*100+50],eroded[50*100+50]); assertEquals(source[50*100+50],dilated[50*100+50]);
    }
    @Test public void expandedMaskStillRespectsDonorImageBoundary() {
        int[] target=solid(0xff405060),source=new int[30*30]; Arrays.fill(source,0xffb0c0d0);
        PatchBlender.blend(patch(target),new PatchBlender.Patch(source,30,30,40,40),face(),face(),
                new Similarity.Adjustment(0,0,1,0,.06,false,.18));
        assertEquals(0xff405060,target[50*100+38]); assertEquals(0xffb0c0d0,target[50*100+50]);
    }
    @Test public void croppedDonorDoesNotReadBeyondImageOrPaintUncoveredPixels() {
        int[] destination=solid(0xff405060);
        int[] source=new int[20*20]; Arrays.fill(source,0xffd0b0a0);
        PatchBlender.blend(patch(destination),new PatchBlender.Patch(source,20,20,40,40),face(),face(),
                new Similarity.Adjustment(0,0,1,0,.06,false));
        assertEquals(0xffd0b0a0,destination[50*100+50]);
        assertEquals(0xff405060,destination[35*100+35]);
    }
    @Test(expected=IllegalArgumentException.class) public void missingEyesAreRejected() {
        FaceGeometry incomplete=new FaceGeometry(face().box(),null,null,null,List.of(),0,0,null,null,null);
        Similarity.align(incomplete,face(),Similarity.Adjustment.defaults());
    }
    @Test public void normalizedGeometryScalesIndependentlyForPortraitAndLandscape() {
        FaceGeometry f=new FaceGeometry(new Box(.1,.2,.4,.6),new Point(.2,.3),new Point(.3,.3),null,List.of(),0,0,null,null,null);
        FaceGeometry scaled=f.scaled(4000,3000);
        assertEquals(1200,scaled.box().width(),1e-9); assertEquals(1200,scaled.box().height(),1e-9);
        assertEquals(800,scaled.leftEye().x(),1e-9); assertEquals(900,scaled.leftEye().y(),1e-9);
    }
}
