package com.willcreations.harness;
import android.app.*; import android.os.*; import android.content.*; import android.provider.Settings; import android.view.*; import android.widget.*;
public class MainActivity extends Activity {
  private TextView status; private TaskStore store;
  public void onCreate(Bundle b){super.onCreate(b);store=new TaskStore(this); LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(32,32,32,32); status=new TextView(this);Button access=new Button(this);access.setText("Accessibility設定");access.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));Button demo=new Button(this);demo.setText("自律ループを開始");demo.setOnClickListener(v->{store.set("WAIT_TARGET",0);startActivity(new Intent(this,DemoTargetActivity.class));}); l.addView(status);l.addView(access);l.addView(demo);setContentView(l);}
  protected void onResume(){super.onResume();status.setText("Task state: "+store.state()+" / attempt="+store.attempt());}
}
