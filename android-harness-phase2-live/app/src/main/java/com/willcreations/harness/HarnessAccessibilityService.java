package com.willcreations.harness;
import android.accessibilityservice.*; import android.view.accessibility.*; import java.util.*;
public class HarnessAccessibilityService extends AccessibilityService {
  private static final String ALLOWED="com.willcreations.harness"; private TaskStore store; private long lastClick=0;
  protected void onServiceConnected(){store=new TaskStore(this);}
  public void onAccessibilityEvent(AccessibilityEvent e){ if(e==null||e.getPackageName()==null||!ALLOWED.contentEquals(e.getPackageName())) return; AccessibilityNodeInfo root=getRootInActiveWindow(); if(root==null)return; if(find(root,"完了")!=null){store.set("PASSED",store.attempt());return;} if(!"WAIT_TARGET".equals(store.state())&&!"VERIFYING".equals(store.state())&&!"RECOVERY_PENDING".equals(store.state()))return; if(System.currentTimeMillis()-lastClick<3000)return; AccessibilityNodeInfo n=find(root,"操作対象"); if(n!=null&&n.isClickable()){int a=store.attempt()+1;if(a>5){store.set("FAILED",a);return;} if(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)){lastClick=System.currentTimeMillis();store.set("VERIFYING",a);}}
  }
  private AccessibilityNodeInfo find(AccessibilityNodeInfo n,String text){ if(n==null)return null; CharSequence t=n.getText(); if(t!=null&&text.contentEquals(t))return n; for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo f=find(n.getChild(i),text);if(f!=null)return f;}return null; }
  public void onInterrupt(){}
}
