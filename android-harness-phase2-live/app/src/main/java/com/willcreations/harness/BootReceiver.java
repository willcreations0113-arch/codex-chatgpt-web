package com.willcreations.harness;
import android.content.*;
public class BootReceiver extends BroadcastReceiver { public void onReceive(Context c,Intent i){ if(Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())){TaskStore s=new TaskStore(c); if(!"PASSED".equals(s.state()) && !"IDLE".equals(s.state())) s.set("RECOVERY_PENDING",s.attempt());} } }
