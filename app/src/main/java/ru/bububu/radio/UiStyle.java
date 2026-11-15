package ru.bububu.radio;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.SeekBar;

/** Native shared visual language; UI preferences never touch playback authority. */
final class UiStyle {
    static final String PREFS="demo_ui_v1";
    static final class Palette {
        final boolean bubble; final int background,ink,muted,brand,soft,border;
        Palette(boolean b){bubble=b;background=b?0xfff4f6f9:0xfffaf9f5;ink=b?0xff263843:0xff092d26;
            muted=b?0xff586b7d:0xff596c65;brand=b?0xff365f8b:0xff216a55;soft=b?0xffe5edf5:0xffeaf2ea;border=b?0xffd4dfea:0xffd4e0d9;}
    }
    static Palette palette(Context c){String skin="neo";try{skin=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("skin","neo");}catch(ClassCastException invalid){}return new Palette("bubble".equals(skin));}
    static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable background(Context c,int fill,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(c,radius));d.setStroke(dp(c,1),palette(c).border);return d;}
    static LinearLayout column(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(LinearLayout.VERTICAL);return v;}
    static LinearLayout card(Context c){LinearLayout v=column(c);v.setPadding(dp(c,16),dp(c,14),dp(c,16),dp(c,14));v.setBackground(background(c,0xffffffff,12));v.setTag("ui-card");return v;}
    static TextView label(Context c,String text,int size,boolean muted){TextView v=new TextView(c);v.setText(text);v.setTextSize(size);v.setIncludeFontPadding(false);v.setTextColor(muted?palette(c).muted:palette(c).ink);v.setLineSpacing(dp(c,2),1f);v.setPadding(0,0,0,0);v.setTag(muted?"ui-muted":"ui-text");return v;}
    static TextView heading(Context c,String text,int size){TextView v=label(c,text,size,false);v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    static Button button(Context c,String text,Runnable run){Button b=new ActionButton(c);b.setAllCaps(false);b.setText(text);b.setTextSize(14);b.setIncludeFontPadding(false);b.setSingleLine(false);b.setMinHeight(dp(c,48));b.setMinimumHeight(dp(c,48));b.setPadding(dp(c,12),dp(c,10),dp(c,12),dp(c,10));b.setTag("ui-button");styleButton(b);b.setOnClickListener(v->run.run());return b;}
    static Button action(Context c,String caption,String icon,Runnable run){Button b=button(c,caption,run);b.setCompoundDrawablesWithIntrinsicBounds(new UiIcon(c,icon,iconColors(c,false),20),null,null,null);b.setCompoundDrawablePadding(dp(c,8));return b;}
    static Button iconButton(Context c,String icon,String description,Runnable run){Button b=action(c,"",icon,run);b.setMinWidth(dp(c,48));b.setMinimumWidth(dp(c,48));b.setPadding(dp(c,8),dp(c,8),dp(c,8),dp(c,8));b.setContentDescription(description);return b;}
    static LinearLayout row(Context c){LinearLayout r=new LinearLayout(c);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);r.setBaselineAligned(false);return r;}
    static void divider(LinearLayout parent,int gap){View v=new View(parent.getContext());v.setBackgroundColor(palette(parent.getContext()).border);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(parent.getContext(),1));lp.topMargin=dp(parent.getContext(),gap);lp.bottomMargin=dp(parent.getContext(),gap);parent.addView(v,lp);}
    static TextView accentLabel(Context c,String text,int size){TextView v=label(c,text,size,false);v.setTag("ui-accent");v.setTextColor(palette(c).brand);return v;}
    static Button link(Context c,String text,String icon,Runnable action){Button b=action(c,text,icon,action);overflow(b);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setPadding(dp(c,4),dp(c,10),dp(c,4),dp(c,10));b.setCompoundDrawablesWithIntrinsicBounds(new UiIcon(c,icon,iconColors(c,false),22),null,new UiIcon(c,"arrow",iconColors(c,false),20),null);return b;}
    static void primary(Button b){b.setTag(R.id.ui_style_role,"primary");b.setTag("ui-primary");b.setTextSize(17);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setGravity(Gravity.CENTER);styleButton(b);}
    static void overflow(Button b){b.setTag(R.id.ui_style_role,"overflow");styleButton(b);}
    static void plain(Button b){b.setTag(R.id.ui_style_role,"plain");styleButton(b);}
    static void chip(Button b,boolean selected){b.setTag(R.id.ui_style_role,selected?"chip-selected":"chip");b.setSelected(selected);styleButton(b);}
    static void navigation(Button b,boolean selected){b.setTag(R.id.ui_style_role,selected?"nav-selected":"nav");b.setSelected(selected);b.setTypeface(Typeface.DEFAULT,selected?Typeface.BOLD:Typeface.NORMAL);styleButton(b);}
    static ColorStateList colors(Context c,boolean inverse){Palette p=palette(c);return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{p.muted,inverse?0xffffffff:p.ink});}
    static ColorStateList iconColors(Context c,boolean inverse){Palette p=palette(c);return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{p.muted,inverse?0xffffffff:p.brand});}
    private static String role(Button b){Object keyed=b.getTag(R.id.ui_style_role),tag=b.getTag();
        if("ui-circle-primary".equals(tag))return "circle-primary";if("ui-circle".equals(tag))return "circle";
        if(keyed instanceof String)return (String)keyed;
        return "ui-primary".equals(tag)||"notes-record".equals(tag)?"primary":"secondary";
    }
    /** Centre a lone icon or a centred icon/caption group; links still follow native start gravity. */
    private static final class ActionButton extends Button {
        ActionButton(Context context) { super(context); }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            Drawable[] icons = getCompoundDrawables();
            Drawable icon = icons[0];
            if (icon == null || icons[1] != null || icons[2] != null || icons[3] != null) {
                super.onDraw(canvas);
                return;
            }

            android.graphics.Rect bounds = icon.getBounds();
            if (length() == 0) {
                drawIcon(canvas, icon, (getWidth() - bounds.width()) / 2f);
                return;
            }
            android.text.Layout text = getLayout();
            if ((getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK) != Gravity.CENTER_HORIZONTAL || text == null) {
                super.onDraw(canvas);
                return;
            }
            float left = text.getWidth(), right = 0;
            for (int i = 0; i < text.getLineCount(); i++) {
                left = Math.min(left, text.getLineLeft(i));
                right = Math.max(right, text.getLineRight(i));
            }
            float textOffset = bounds.width() + getCompoundDrawablePadding();
            float width = textOffset + right - left;
            float x = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - width) / 2f;
            drawIcon(canvas, icon, x);
            getPaint().setColor(getCurrentTextColor());
            getPaint().drawableState = getDrawableState();
            canvas.save();
            canvas.translate(x + textOffset - left, (getHeight() - text.getHeight()) / 2f);
            text.draw(canvas);
            canvas.restore();
        }

        private void drawIcon(android.graphics.Canvas canvas, Drawable icon, float x) {
            android.graphics.Rect bounds = icon.getBounds();
            canvas.save();
            canvas.translate(x - bounds.left, (getHeight() - bounds.height()) / 2f - bounds.top);
            icon.draw(canvas);
            canvas.restore();
        }
    }
    /** A real status has separate text roles; bind() alone controls the existing check vector. */
    @android.annotation.SuppressLint("ViewConstructor")
    static final class StatusRow extends LinearLayout {
        private final TextView title,subtitle;private final android.widget.ImageView check;
        StatusRow(Context c,String heading,String icon,Runnable click){
            super(c);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setBaselineAligned(false);setMinimumHeight(dp(c,click==null?56:72));setPadding(dp(c,4),dp(c,8),dp(c,4),dp(c,8));
            android.widget.ImageView leading=new android.widget.ImageView(c);leading.setImageDrawable(new UiIcon(c,icon==null?"check":icon,palette(c).brand,22));leading.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);LayoutParams lead=new LayoutParams(dp(c,24),dp(c,24));lead.rightMargin=dp(c,12);addView(leading,lead);
            LinearLayout copy=column(c);title=label(c,heading,16,false);subtitle=label(c,"",13,true);copy.addView(title);spaced(copy,subtitle,5);addView(copy,new LayoutParams(0,-2,1));
            if(click==null){check=leading;}else{check=new android.widget.ImageView(c);check.setImageDrawable(new UiIcon(c,"check",palette(c).brand,18));GradientDrawable badge=new GradientDrawable();badge.setShape(GradientDrawable.OVAL);badge.setColor(palette(c).soft);check.setBackground(badge);check.setPadding(dp(c,5),dp(c,5),dp(c,5),dp(c,5));LayoutParams mark=new LayoutParams(dp(c,28),dp(c,28));mark.leftMargin=dp(c,8);addView(check,mark);android.widget.ImageView arrow=new android.widget.ImageView(c);arrow.setImageDrawable(new UiIcon(c,"arrow",palette(c).ink,20));LayoutParams edge=new LayoutParams(dp(c,20),dp(c,24));edge.leftMargin=dp(c,12);addView(arrow,edge);setOnClickListener(v->click.run());setFocusable(true);setBackground(new RippleDrawable(ColorStateList.valueOf(0x14216a55),new android.graphics.drawable.ColorDrawable(0xffffffff),null));}
            check.setVisibility(INVISIBLE);
        }
        void bind(String text,boolean confirmed){subtitle.setText(text);check.setVisibility(confirmed?VISIBLE:INVISIBLE);setContentDescription(title.getText()+". "+text);}
    }
    private static int darker(int color){return 0xff000000|Math.round(((color>>16)&255)*.9f)<<16|Math.round(((color>>8)&255)*.9f)<<8|Math.round((color&255)*.9f);}
    private static GradientDrawable fill(Context c,int color,int radius,boolean border){GradientDrawable d=background(c,color,radius);if(!border)d.setStroke(0,0);return d;}
    private static final class AppliedStyle {
        final boolean bubble; final String role; final RippleDrawable background;
        final Drawable content; final ColorStateList text,icons; final Drawable[] appliedIcons=new Drawable[4];
        AppliedStyle(Palette palette,String role,RippleDrawable background,ColorStateList text,ColorStateList icons){
            bubble=palette.bubble;this.role=role;this.background=background;
            // mutate() may replace the child; retain the actual installed content, not its source.
            content=background.getDrawable(0);this.text=text;this.icons=icons;
        }
        void sync(Button button){
            int[] state=button.getDrawableState().clone();
            // The outer Ripple can already cache this state while a cloned child still has [].
            content.setState(state);background.setState(state);
            if(button.getTextColors()!=text)button.setTextColor(text);
            Drawable[] current=button.getCompoundDrawables();
            for(int i=0;i<current.length;i++){
                if(current[i] instanceof UiIcon){
                    if(appliedIcons[i]!=current[i])((UiIcon)current[i]).setColors(icons);
                    current[i].setState(state);
                }
                appliedIcons[i]=current[i];
            }
        }
    }
    static void styleButton(Button b){Context c=b.getContext();Palette p=palette(c);String role=role(b);
        Object prior=b.getTag(R.id.ui_applied_style);
        if(prior instanceof AppliedStyle){AppliedStyle applied=(AppliedStyle)prior;
            if(applied.bubble==p.bubble&&applied.role.equals(role)&&applied.background==b.getBackground()&&b.getBackgroundTintList()==null){
                applied.sync(b);return;
            }
        }
        boolean primary=role.equals("primary")||role.equals("circle-primary"),circle=role.startsWith("circle"),quiet=role.equals("overflow")||role.equals("plain")||role.startsWith("nav");
        int radius=circle?100:primary?28:12,normal=primary?p.brand:role.equals("nav-selected")||role.equals("chip-selected")?p.soft:role.equals("plain")?0x00ffffff:0xffffffff;
        StateListDrawable states=new StateListDrawable();states.addState(new int[]{-android.R.attr.state_enabled},fill(c,p.soft,radius,!quiet));
        states.addState(new int[]{android.R.attr.state_pressed},fill(c,primary?darker(p.brand):p.soft,radius,!quiet));
        states.addState(new int[]{android.R.attr.state_focused},fill(c,primary?darker(p.brand):p.soft,radius,!quiet));
        states.addState(new int[]{},fill(c,normal,radius,!quiet));
        ColorStateList ripple=new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0,primary?0x12ffffff:0x14000000});
        // View applies even a cleared tint through mutate() after setting background state.
        // Mutate before attachment so that operation cannot clone/reset the content afterwards.
        RippleDrawable background=(RippleDrawable)new RippleDrawable(ripple,states,fill(c,0xffffffff,radius,false)).mutate();
        AppliedStyle applied=new AppliedStyle(p,role,background,colors(c,primary),iconColors(c,primary));
        applied.sync(b);b.setBackgroundTintList(null);b.setBackground(background);
        b.setTag(R.id.ui_applied_style,applied);applied.sync(b);
        b.setStateListAnimator(null);b.setElevation(0);b.setTranslationZ(0);
    }
    static void accent(View v){Context c=v.getContext();Palette p=palette(c);ColorStateList active=new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{p.muted,p.brand});
        if(v instanceof SeekBar){SeekBar b=(SeekBar)v;b.setProgressTintList(active);b.setThumbTintList(active);b.setProgressBackgroundTintList(ColorStateList.valueOf(p.border));}
        if(v instanceof CompoundButton){CompoundButton b=(CompoundButton)v;b.setButtonTintList(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{android.R.attr.state_checked},new int[]{}},new int[]{p.muted,p.brand,p.muted}));b.setTextColor(colors(c,false));}
    }
    static void spaced(LinearLayout p,View child,int gap){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(p.getContext(),gap);p.addView(child,lp);}
    static void themeTree(View view){Context c=view.getContext();Palette p=palette(c);Object tag=view.getTag();
        if(view instanceof Button){styleButton((Button)view);
        }else if(view instanceof EditText){((EditText)view).setTextColor(p.ink);((EditText)view).setHintTextColor(p.muted);if("ui-input".equals(tag)){view.setBackgroundTintList(null);view.setBackground(background(c,0xffffffff,12));}else ((EditText)view).setBackgroundTintList(ColorStateList.valueOf(p.brand));}
        else if(view instanceof TextView)((TextView)view).setTextColor("ui-muted".equals(tag)?p.muted:"ui-accent".equals(tag)?p.brand:p.ink);
        if("ui-card".equals(tag))view.setBackground(background(c,0xffffffff,12));
        if("ui-soft".equals(tag))view.setBackground(background(c,p.soft,12));
        accent(view);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)themeTree(((ViewGroup)view).getChildAt(i));
    }
}
