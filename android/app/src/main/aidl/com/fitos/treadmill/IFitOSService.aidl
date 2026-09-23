package com.fitos.treadmill;

import com.fitos.treadmill.ITreadmillDataCallback;
import com.fitos.treadmill.TreadmillMetric;

interface IFitOSService {
    boolean registerCallback(ITreadmillDataCallback callback);
    boolean unregisterCallback(ITreadmillDataCallback callback);
    TreadmillMetric getCurrentMetric();
    boolean setTargetSpeed(float speedKmh);
    boolean setTargetIncline(float incline);
}
