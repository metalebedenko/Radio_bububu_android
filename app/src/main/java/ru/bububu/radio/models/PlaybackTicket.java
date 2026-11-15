package ru.bububu.radio.models;
/** Owner-thread identity and audio phase; preparing/ready are never started/done. */
public final class PlaybackTicket {
 public static final class Token {public final String id;private final long epoch;Token(String id,long e){this.id=id;epoch=e;}}
 private long epoch;private Token active;private int phase;
 public Token begin(String id){cancel();active=new Token(id,epoch);phase=0;return active;}
 public boolean current(Token token){return token!=null&&token==active&&token.epoch==epoch;}
 public boolean ready(Token token){if(!current(token)||phase!=0)return false;phase=1;return true;}
 public boolean audioProgress(Token token,long position){if(!current(token)||phase!=1||position<=0)return false;phase=2;return true;}
 public boolean playing(Token token){return current(token)&&phase==2;}
 public boolean done(Token token){if(!current(token)||phase!=2)return false;cancel();return true;}
 public void cancel(){epoch++;active=null;phase=0;}
}
