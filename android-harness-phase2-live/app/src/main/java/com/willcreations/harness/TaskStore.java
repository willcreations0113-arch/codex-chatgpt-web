package com.willcreations.harness;
import android.content.*; import android.database.*; import android.database.sqlite.*;
public final class TaskStore extends SQLiteOpenHelper {
  private static final String DB="harness.db"; private static final int V=1;
  public TaskStore(Context c){super(c,DB,null,V);} public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE task(id INTEGER PRIMARY KEY,state TEXT NOT NULL,attempt INTEGER NOT NULL DEFAULT 0,updated INTEGER NOT NULL)"); db.execSQL("INSERT INTO task(id,state,attempt,updated) VALUES(1,'IDLE',0,strftime('%s','now'))");}
  public void onUpgrade(SQLiteDatabase db,int o,int n){}
  public synchronized void set(String state,int attempt){ContentValues v=new ContentValues();v.put("state",state);v.put("attempt",attempt);v.put("updated",System.currentTimeMillis());getWritableDatabase().update("task",v,"id=1",null);}
  public synchronized String state(){try(Cursor c=getReadableDatabase().rawQuery("SELECT state FROM task WHERE id=1",null)){return c.moveToFirst()?c.getString(0):"IDLE";}}
  public synchronized int attempt(){try(Cursor c=getReadableDatabase().rawQuery("SELECT attempt FROM task WHERE id=1",null)){return c.moveToFirst()?c.getInt(0):0;}}
}
