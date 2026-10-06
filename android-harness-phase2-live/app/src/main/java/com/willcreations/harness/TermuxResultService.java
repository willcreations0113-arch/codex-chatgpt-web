package com.willcreations.harness;

import android.app.IntentService;
import android.content.Intent;

public final class TermuxResultService extends IntentService {
    public static final String EXTRA_EXECUTION_ID = "will_harness_execution_id";

    public TermuxResultService() {
        super("WillHarnessTermuxResults");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) return;
        int executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, 0);
        if (executionId == 0) return;
        TermuxCommandBridge.deliver(executionId, intent);
    }
}
