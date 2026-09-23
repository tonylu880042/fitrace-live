package com.fitos.treadmill;

import com.fitos.treadmill.TreadmillMetric;

oneway interface ITreadmillDataCallback {
    void onMetricUpdated(in TreadmillMetric metric);
    void onSafetyKeyTriggered(boolean isDetached);
}
