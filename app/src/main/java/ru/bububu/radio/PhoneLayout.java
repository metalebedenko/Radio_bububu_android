package ru.bububu.radio;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Layout depends on measured width and actual SP paints, never on player/content state. */
final class PhoneLayout {
    private PhoneLayout() {}
    private static final String[] PLAY={"Слушать","Пауза","Продолжить","Отменить запуск"};
    private static TextPaint paint(TextView view,int style){TextPaint p=new TextPaint(view.getPaint());p.setTypeface(Typeface.create(view.getTypeface(),style));p.setFakeBoldText(false);return p;}
    private static StaticLayout text(TextView view,String value,int width,int style){return StaticLayout.Builder.obtain(value,0,value.length(),paint(view,style),Math.max(1,width)).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(view.getIncludeFontPadding()).setLineSpacing(view.getLineSpacingExtra(),view.getLineSpacingMultiplier()).setBreakStrategy(view.getBreakStrategy()).build();}
    private static String[] labels(Button button,Button play){return button==play?PLAY:new String[]{button.getText().toString()};}
    private static int iconHeight(Button button){Drawable icon=button.getCompoundDrawables()[1];return icon==null?0:icon.getBounds().height()+button.getCompoundDrawablePadding();}
    private static int requiredHeight(Button button,Button play,int width){
        int h=0;for(String value:labels(button,play))for(int style:new int[]{Typeface.NORMAL,Typeface.BOLD})h=Math.max(h,text(button,value,width-button.getCompoundPaddingLeft()-button.getCompoundPaddingRight(),style).getHeight());
        return Math.max(UiStyle.dp(button.getContext(),48),h+button.getPaddingTop()+button.getPaddingBottom()+iconHeight(button)+2);
    }
    static Button navigation(Context context,String caption,String icon,Runnable action){
        Button button=UiStyle.button(context,caption,action);button.setTextSize(12);button.setTypeface(Typeface.DEFAULT,Typeface.NORMAL);button.setPadding(UiStyle.dp(context,2),UiStyle.dp(context,8),UiStyle.dp(context,2),UiStyle.dp(context,8));button.setCompoundDrawablesWithIntrinsicBounds(null,new UiIcon(context,icon,UiStyle.palette(context).brand,21),null,null);button.setCompoundDrawablePadding(UiStyle.dp(context,4));button.setMinHeight(UiStyle.dp(context,72));button.setMinimumHeight(UiStyle.dp(context,72));return button;
    }
    /** Keep one geometry for Play, Pause, Resume and Cancel, even when the font is enlarged. */
    @android.annotation.SuppressLint("ViewConstructor")
    static final class Transport extends ViewGroup {
        private final Button previous,play,next;
        private final int edge,gap;
        private boolean stacked;

        Transport(Context context,Button previous,Button play,Button next){
            super(context);
            this.previous=previous;
            this.play=play;
            this.next=next;
            edge=UiStyle.dp(context,48);
            gap=UiStyle.dp(context,8);
            play.setHorizontallyScrolling(false);
            play.setMaxLines(Integer.MAX_VALUE);
            play.setEllipsize(null);
            addView(previous);
            addView(play);
            addView(next);
        }

        private int preferredPlayWidth(){
            float widest=0;
            for(String caption:PLAY)widest=Math.max(widest,Layout.getDesiredWidth(caption,play.getPaint()));
            // ActionButton draws its left icon beside the actual TextView layout.
            return (int)Math.ceil(widest)+play.getCompoundPaddingLeft()+play.getCompoundPaddingRight()+2;
        }

        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int preferred=preferredPlayWidth();
            int width=resolveSize(getPaddingLeft()+getPaddingRight()+2*(edge+gap)+preferred,widthSpec);
            int available=Math.max(1,width-getPaddingLeft()-getPaddingRight());
            int center=available-2*(edge+gap);
            stacked=center<preferred;
            int playWidth=stacked?available:center;
            int playHeight=Math.max(UiStyle.dp(getContext(),56),requiredHeight(play,play,playWidth));
            measureButton(play,playWidth,playHeight);
            int firstWidth=stacked?Math.max(1,(available-gap)/2):edge;
            int lastWidth=stacked?Math.max(1,available-gap-firstWidth):edge;
            int arrowHeight=stacked?edge:playHeight;
            measureButton(previous,firstWidth,arrowHeight);
            measureButton(next,lastWidth,arrowHeight);
            int contentHeight=playHeight+(stacked?gap+arrowHeight:0);
            setMeasuredDimension(width,resolveSize(getPaddingTop()+contentHeight+getPaddingBottom(),heightSpec));
        }

        private static void measureButton(Button button,int width,int height){
            button.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY));
        }

        @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
            int top=getPaddingTop();
            if(stacked){
                place(play,0,top);
                top+=play.getMeasuredHeight()+gap;
                place(previous,0,top);
                place(next,previous.getMeasuredWidth()+gap,top);
            }else{
                place(previous,0,top);
                place(play,edge+gap,top);
                place(next,edge+gap+play.getMeasuredWidth()+gap,top);
            }
        }

        private void place(Button button,int start,int top){
            int width=button.getMeasuredWidth();
            int left=getLayoutDirection()==LAYOUT_DIRECTION_RTL
                    ?getWidth()-getPaddingRight()-start-width:getPaddingLeft()+start;
            button.layout(left,top,left+width,top+button.getMeasuredHeight());
        }
    }
    static final class Navigation extends LinearLayout {
        private final Button[] buttons;private int lastWidth=-1;private String paintKey="";
        Navigation(Context context,Button[] buttons){super(context);this.buttons=buttons.clone();
            // Equal-height cells must share their top edge; wrapped captions have different baselines.
            setBaselineAligned(false);
            setPadding(UiStyle.dp(context,2),UiStyle.dp(context,4),UiStyle.dp(context,2),UiStyle.dp(context,4));setBackgroundColor(0xffffffff);for(Button b:buttons){b.setMinWidth(0);b.setMinimumWidth(0);b.setMaxLines(Integer.MAX_VALUE);b.setEllipsize(null);addView(b,new LayoutParams(0,-2,1));}}
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int width=MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight();String key="";for(Button b:buttons)key+=b.getTextSize()+":";
            if(width>0&&(width!=lastWidth||!key.equals(paintKey))){lastWidth=width;paintKey=key;int height=UiStyle.dp(getContext(),72),cell=width/Math.max(1,buttons.length);for(Button b:buttons)height=Math.max(height,requiredHeight(b,null,cell));for(Button b:buttons){LayoutParams lp=(LayoutParams)b.getLayoutParams();lp.height=height;b.setLayoutParams(lp);}}
            super.onMeasure(widthSpec,heightSpec);
        }
    }
}
