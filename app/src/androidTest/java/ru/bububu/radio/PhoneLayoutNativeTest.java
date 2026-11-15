package ru.bububu.radio;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Controlled native View measurement, not a claim that these are real device windows. No data/audio/network. */
@RunWith(AndroidJUnit4.class)
public final class PhoneLayoutNativeTest {
    private static final int[] WIDTHS={320,320,360,360,412,412,432,360};
    private static final int[] HEIGHTS={568,568,640,640,800,800,900,480};
    private static final float[] FONTS={1f,1.5f,1f,1.3f,1f,1.5f,1.3f,1.5f};
    private static void main(Runnable action){AtomicReference<Throwable> error=new AtomicReference<>();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{action.run();}catch(Throwable failure){error.set(failure);}});if(error.get()!=null)throw new AssertionError("Native phone-layout assertion",error.get());}
    private Context context(float font,boolean bubble){Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();Configuration configuration=new Configuration(base.getResources().getConfiguration());configuration.fontScale=font;return new ContextThemeWrapper(base.createConfigurationContext(configuration),bubble?R.style.BubbleTheme:R.style.NeoTheme);}
    private static void layout(View view,int width){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));view.layout(0,0,view.getMeasuredWidth(),view.getMeasuredHeight());}
    private static void window(View view,int width,int height){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,view.getMeasuredWidth(),view.getMeasuredHeight());}
    private static Button[] tabs(Context context){String[] names={"Радио","Заметки","Источники"},icons={"radio","mic","sources"};Button[] buttons=new Button[names.length];for(int j=0;j<buttons.length;j++)buttons[j]=PhoneLayout.navigation(context,names[j],icons[j],()->{});return buttons;}
    private static Rect bounds(View parent,View child){Rect rect=new Rect(0,0,child.getWidth(),child.getHeight());((android.view.ViewGroup)parent).offsetDescendantRectToMyCoords(child,rect);return rect;}
    private static void readable(Button button){
        int minimum=UiStyle.dp(button.getContext(),48);
        int innerWidth=button.getWidth()-button.getCompoundPaddingLeft()-button.getCompoundPaddingRight();
        String details="'"+button.getText()+"' fontScale="+button.getResources().getConfiguration().fontScale
                +" textPx="+button.getTextSize()+" width="+button.getWidth()+" textArea="+innerWidth;
        assertTrue("Minimum target width: "+details,button.getWidth()>=minimum);
        assertTrue("Minimum target height: "+details,button.getHeight()>=minimum);
        android.text.Layout text=button.getLayout();
        assertNotNull(details,text);
        assertTrue("All caption characters participate: "+details,
                text.getLineEnd(text.getLineCount()-1)>=button.getText().length());
        int innerHeight=button.getHeight()-button.getCompoundPaddingTop()-button.getCompoundPaddingBottom();
        assertTrue("Text height="+text.getHeight()+" available="+innerHeight+" "+details,text.getHeight()<=innerHeight);
        for(int line=0;line<text.getLineCount();line++){
            assertEquals("Ellipsis: "+details,0,text.getEllipsisCount(line));
            float lineWidth=text.getLineWidth(line);
            assertTrue("Line "+line+" width="+lineWidth+" layoutWidth="+text.getWidth()+" "+details,
                    lineWidth<=innerWidth+1);
        }
    }

    @Test public void inlineRadioTransportKeepsCaptionAndActionsAccessibleAtLargeFont() {
        main(() -> {
            for (int profileWidth:new int[]{320,384,412}) for (float font:new float[]{1f,1.5f,2f}) {
                Context c=context(font,false);
                Button previous=UiStyle.iconButton(c,"prev","Предыдущая новость",()->{});
                Button play=UiStyle.action(c,"Слушать","play",()->{});
                UiStyle.primary(play);
                play.setCompoundDrawablesWithIntrinsicBounds(new UiIcon(c,"play",UiStyle.iconColors(c,true),22),null,null,null);
                Button next=UiStyle.iconButton(c,"next","Следующая новость",()->{});
                PhoneLayout.Transport transport=new PhoneLayout.Transport(c,previous,play,next);
                int width=UiStyle.dp(c,profileWidth-40);
                List<Rect> initial=null;
                for(String caption:List.of("Слушать","Пауза","Продолжить","Отменить запуск")){
                    play.setText(caption);
                    layout(transport,width);
                    readable(play);
                    List<Rect> boxes=List.of(bounds(transport,previous),bounds(transport,play),bounds(transport,next));
                    Rect area=new Rect(0,0,width,transport.getHeight());
                    for(int j=0;j<boxes.size();j++){
                        assertTrue("Button lies inside transport "+boxes.get(j),area.contains(boxes.get(j)));
                        for(int k=0;k<j;k++)assertFalse(Rect.intersects(boxes.get(j),boxes.get(k)));
                    }
                    for(Button arrow:List.of(previous,next)){
                        assertTrue(arrow.getWidth()>=UiStyle.dp(c,48));
                        assertTrue(arrow.getHeight()>=UiStyle.dp(c,48));
                        assertNotNull(arrow.getContentDescription());
                    }
                    if(profileWidth==320 && font==2f){
                        assertEquals("Large font uses the full width",width,play.getWidth());
                        assertTrue("Arrows below CTA",previous.getTop()>=play.getBottom()&&next.getTop()>=play.getBottom());
                    } else if(profileWidth==412 && font==1f){
                        assertEquals("Normal wide profile stays inline",play.getTop(),previous.getTop());
                        assertEquals(play.getTop(),next.getTop());
                    }
                    if(initial==null)initial=boxes;else assertEquals("Changing state must not move hit targets",initial,boxes);
                }
            }
        });
    }
    @Test public void threeUx3NavigationLabelsKeepHeightAndBoundsAcrossSelectedBoldStates(){
        main(()->{
            for(boolean bubble:new boolean[]{false,true}){
                for(int i=0;i<WIDTHS.length+4;i++)for(int mode=0;mode<4;mode++){
                    // Include the actual 1080px / 2.8125 density = 384dp S24 width.
                    int profileWidth=i<WIDTHS.length?WIDTHS[i]:i==WIDTHS.length+3?320:384,profileHeight=i<HEIGHTS.length?HEIGHTS[i]:832;
                    float font=i<FONTS.length?FONTS[i]:new float[]{1f,1.3f,1.5f,2f}[i-WIDTHS.length];
                    Context c=context(font,bubble);String[] labels={"Радио","Заметки","Источники"};Button[] buttons=tabs(c);
                    PhoneLayout.Navigation navigation=new PhoneLayout.Navigation(c,buttons);int width=UiStyle.dp(c,profileWidth);layout(navigation,width);int naturalHeight=navigation.getHeight();
                    LinearLayout shell=new LinearLayout(c);shell.setOrientation(LinearLayout.VERTICAL);shell.setPadding(0,UiStyle.dp(c,24),0,UiStyle.dp(c,20));
                    if(mode==3){shell.addView(new ScrollView(c),new LinearLayout.LayoutParams(-1,0,1));shell.addView(navigation,new LinearLayout.LayoutParams(-1,-2));}
                    navigationLayout(navigation,shell,width,UiStyle.dp(c,profileHeight),naturalHeight,mode);
                    int height=navigation.getHeight();List<Rect> reference=new ArrayList<>();for(Button b:buttons)reference.add(bounds(navigation,b));navigationContained(navigation,buttons);
                    for(int selected=0;selected<buttons.length;selected++){
                        for(int j=0;j<buttons.length;j++)UiStyle.navigation(buttons[j],j==selected);
                        navigationLayout(navigation,shell,width,UiStyle.dp(c,profileHeight),naturalHeight,mode);assertEquals(height,navigation.getHeight());navigationContained(navigation,buttons);
                        if(mode==3){Rect visible=new Rect(shell.getPaddingLeft(),shell.getPaddingTop(),shell.getWidth()-shell.getPaddingRight(),shell.getHeight()-shell.getPaddingBottom());assertTrue("Navigation fits the constrained window",visible.contains(bounds(shell,navigation)));}
                        for(int j=0;j<buttons.length;j++){assertEquals(labels[j],buttons[j].getText().toString());readable(buttons[j]);assertEquals(reference.get(j),bounds(navigation,buttons[j]));}
                    }
                }
            }
        });
    }
    private static void navigationLayout(PhoneLayout.Navigation navigation,LinearLayout shell,int width,int windowHeight,int naturalHeight,int mode){
        if(mode==0){layout(navigation,width);return;}
        if(mode==3){window(shell,width,windowHeight);return;}
        int height=mode==1?naturalHeight+UiStyle.dp(navigation.getContext(),16):naturalHeight;
        navigation.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,mode==1?View.MeasureSpec.AT_MOST:View.MeasureSpec.EXACTLY));navigation.layout(0,0,navigation.getMeasuredWidth(),navigation.getMeasuredHeight());
    }
    private static void navigationContained(PhoneLayout.Navigation navigation,Button[] buttons){
        Rect content=new Rect(navigation.getPaddingLeft(),navigation.getPaddingTop(),navigation.getWidth()-navigation.getPaddingRight(),navigation.getHeight()-navigation.getPaddingBottom());List<Rect> previous=new ArrayList<>();
        for(Button button:buttons){Rect rect=bounds(navigation,button);assertTrue(button.getText()+" bounds "+rect+" must fit Navigation content "+content,content.contains(rect));for(Rect prior:previous)assertFalse("Navigation hit targets cannot overlap",Rect.intersects(prior,rect));previous.add(rect);}
    }
    @Test public void insetOwnershipDoesNotSumImeAndBarsOrAccumulateOnRedispatch(){
        main(()->{
            Context c=context(1f,false);LinearLayout shell=new LinearLayout(c);PhoneInsets.install(shell);
            if(Build.VERSION.SDK_INT>=30){
                int bars=WindowInsets.Type.systemBars(),cutout=WindowInsets.Type.displayCutout(),ime=WindowInsets.Type.ime();
                WindowInsets open=new WindowInsets.Builder().setInsets(bars,Insets.of(4,24,6,20)).setVisible(bars,true).setInsets(cutout,Insets.of(8,30,0,0)).setVisible(cutout,true).setInsets(ime,Insets.of(0,0,0,200)).setVisible(ime,true).build();
                assertTrue(shell.dispatchApplyWindowInsets(open).isConsumed());shell.dispatchApplyWindowInsets(open);assertEquals(8,shell.getPaddingLeft());assertEquals(30,shell.getPaddingTop());assertEquals(6,shell.getPaddingRight());assertEquals(200,shell.getPaddingBottom());
                shell.dispatchApplyWindowInsets(new WindowInsets.Builder(open).setInsets(ime,Insets.NONE).setVisible(ime,false).build());assertEquals(20,shell.getPaddingBottom());
            }else{assertEquals("Old fitting Decor owns padding",0,shell.getPaddingTop());assertEquals(0,shell.getPaddingBottom());}
        });
    }
}
