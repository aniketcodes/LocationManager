package com.yayandroid.locationmanager.providers.locationprovider;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.provider.Settings;
import android.support.annotation.NonNull;

import com.yayandroid.locationmanager.constants.FailType;
import com.yayandroid.locationmanager.constants.LogType;
import com.yayandroid.locationmanager.constants.RequestCode;
import com.yayandroid.locationmanager.helper.LocationUtils;
import com.yayandroid.locationmanager.helper.LogUtils;
import com.yayandroid.locationmanager.helper.continuoustask.ContinuousTask;
import com.yayandroid.locationmanager.helper.continuoustask.ContinuousTask.ContinuousTaskRunner;
import com.yayandroid.locationmanager.listener.DialogListener;
import com.yayandroid.locationmanager.providers.dialogprovider.DialogProvider;

@SuppressWarnings("ResourceType")
public class DefaultLocationProvider extends LocationProvider implements ContinuousTaskRunner, LocationListener {

    private static final String PROVIDER_SWITCH_TASK = "providerSwitchTask";
    private final ContinuousTask cancelTask = new ContinuousTask(PROVIDER_SWITCH_TASK, this);

    private String provider;
    private LocationManager locationManager;
    private UpdateRequest currentUpdateRequest;
    private Dialog gpsDialog;

    @Override
    public void onDestroy() {
        super.onDestroy();

        gpsDialog = null;

        if (currentUpdateRequest != null) {
            currentUpdateRequest.destroy();
            currentUpdateRequest = null;
        }

        if (locationManager != null) {
            locationManager.removeUpdates(this);
            locationManager = null;
        }
    }

    @Override
    public boolean requiresActivityResult() {
        // If we need to ask for enabling GPS then we'll need to get onActivityResult callback
        return configuration.defaultProviderConfiguration().askForGPSEnable();
    }

    @Override
    public boolean isDialogShowing() {
        return gpsDialog != null && gpsDialog.isShowing();
    }

    @Override
    public void get() {
        setWaiting(true);

        if (contextProcessor.getContext() != null) {
            locationManager = (LocationManager) contextProcessor.getContext().getSystemService(Context.LOCATION_SERVICE);
        } else {
            onLocationFailed(FailType.VIEW_DETACHED);
            return;
        }

        // First check for GPS
        if (isGPSProviderEnabled()) {
            LogUtils.logI("GPS is already enabled, getting location...", LogType.GENERAL);
            askForLocation(LocationManager.GPS_PROVIDER);
        } else {
            // GPS is not enabled,
            if (configuration.defaultProviderConfiguration().askForGPSEnable() && contextProcessor.getActivity() != null) {
                LogUtils.logI("GPS is not enabled, asking user to enable it...", LogType.GENERAL);
                askForEnableGPS();
            } else {
                LogUtils.logI("GPS is not enabled, moving on with Network...", LogType.GENERAL);
                getLocationByNetwork();
            }
        }
    }

    @Override
    public void cancel() {
        if (currentUpdateRequest != null) currentUpdateRequest.release();
        cancelTask.stop();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == RequestCode.GPS_ENABLE) {
            if (isGPSProviderEnabled()) {
                onGPSActivated();
            } else {
                LogUtils.logI("User didn't activate GPS, so continue with Network Provider", LogType.IMPORTANT);
                getLocationByNetwork();
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        if (currentUpdateRequest != null) {
            currentUpdateRequest.release();
        }

        cancelTask.pause();
    }

    @Override
    public void onResume() {
        super.onResume();

        if (currentUpdateRequest != null) {
            currentUpdateRequest.run();
        }

        if (isWaiting()) {
            cancelTask.resume();
        }

        if (isDialogShowing() && isGPSProviderEnabled()) {
            // User activated GPS by going settings manually
            gpsDialog.dismiss();
            onGPSActivated();
        }
    }

    private void askForEnableGPS() {
        DialogProvider gpsDialogProvider = configuration.defaultProviderConfiguration().getGpsDialogProvider();
        gpsDialogProvider.setDialogListener(new DialogListener() {
            @Override
            public void onPositiveButtonClick() {
                if (contextProcessor.getActivity() != null) {
                    contextProcessor.getActivity()
                          .startActivityForResult(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                                RequestCode.GPS_ENABLE);
                } else {
                    onLocationFailed(FailType.VIEW_DETACHED);
                }
            }

            @Override
            public void onNegativeButtonClick() {
                LogUtils.logI("User didn't want to enable GPS, so continue with Network Provider",
                      LogType.IMPORTANT);
                getLocationByNetwork();
            }
        });
        gpsDialog = gpsDialogProvider.getDialog(contextProcessor.getActivity());
        gpsDialog.show();
    }

    private void onGPSActivated() {
        LogUtils.logI("User activated GPS, listen for location", LogType.GENERAL);
        askForLocation(LocationManager.GPS_PROVIDER);
    }

    private void getLocationByNetwork() {
        if (isNetworkProviderEnabled() && isNetworkAvailable()) {
            LogUtils.logI("Network is enabled, getting location...", LogType.GENERAL);
            askForLocation(LocationManager.NETWORK_PROVIDER);
        } else {
            LogUtils.logI("Network is not enabled, calling fail...", LogType.GENERAL);
            onLocationFailed(FailType.NETWORK_NOT_AVAILABLE);
        }
    }

    private void askForLocation(String provider) {
        cancelTask.stop();
        this.provider = provider;

        boolean locationIsAlreadyAvailable = false;
        Location lastKnownLocation = locationManager.getLastKnownLocation(provider);

        if (LocationUtils.isUsable(configuration, lastKnownLocation)) {
            LogUtils.logI("LastKnowLocation is usable.", LogType.IMPORTANT);
            onLocationReceived(lastKnownLocation);
            locationIsAlreadyAvailable = true;
        } else {
            LogUtils.logI("LastKnowLocation is not usable.", LogType.GENERAL);
        }

        if (configuration.keepTracking() || !locationIsAlreadyAvailable) {
            LogUtils.logI("Ask for location update...", LogType.IMPORTANT);
            // Ask for immediate location update
            requestUpdateLocation(0, 0, !locationIsAlreadyAvailable);
        } else {
            LogUtils.logI("We got location, no need to ask for location updates.", LogType.GENERAL);
        }
    }

    private void requestUpdateLocation(long timeInterval, long distanceInterval, boolean setCancelTask) {
        if (setCancelTask) {
            cancelTask.delayed(getWaitPeriod());
        }

        currentUpdateRequest = new UpdateRequest(provider, timeInterval, distanceInterval, this);
        currentUpdateRequest.run();
    }

    private long getWaitPeriod() {
        return provider.equals(LocationManager.GPS_PROVIDER)
              ? configuration.defaultProviderConfiguration().gpsWaitPeriod()
              : configuration.defaultProviderConfiguration().networkWaitPeriod();
    }

    private boolean isNetworkAvailable() {
        return LocationUtils.isNetworkAvailable(contextProcessor.getContext());
    }

    private boolean isNetworkProviderEnabled() {
        return locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private boolean isGPSProviderEnabled() {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    private void onLocationReceived(Location location) {
        if (listener != null) {
            listener.onLocationChanged(location);
        }
        setWaiting(false);
    }

    private void onLocationFailed(int type) {
        if (listener != null) {
            listener.onLocationFailed(type);
        }
        setWaiting(false);
    }

    @Override
    public void onLocationChanged(Location location) {
        onLocationReceived(location);

        // Remove cancelLocationTask because we have already find location,
        // no need to switch or call fail
        cancelTask.stop();

        if (configuration.keepTracking()) {
            requestUpdateLocation(configuration.defaultProviderConfiguration().requiredTimeInterval(),
                  configuration.defaultProviderConfiguration().requiredDistanceInterval(), false);
        } else {
            locationManager.removeUpdates(this);
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        if (listener != null) {
            listener.onStatusChanged(provider, status, extras);
        }
    }

    @Override
    public void onProviderEnabled(String provider) {
        if (listener != null) {
            listener.onProviderEnabled(provider);
        }
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (listener != null) {
            listener.onProviderDisabled(provider);
        }
    }

    @Override
    public void runScheduledTask(@NonNull String taskId) {
        if (taskId.equals(PROVIDER_SWITCH_TASK)) {
            if (currentUpdateRequest != null) {
                currentUpdateRequest.release();
            }

            if (provider.equals(LocationManager.GPS_PROVIDER)) {
                LogUtils.logI("We waited enough for GPS, switching to Network provider...", LogType.IMPORTANT);
                getLocationByNetwork();
            } else {
                LogUtils.logI("Network Provider is not provide location in required period, calling fail...",
                      LogType.GENERAL);
                onLocationFailed(FailType.TIMEOUT);
            }
        }
    }

    private class UpdateRequest {

        private final String provider;
        private final long minTime;
        private final float minDistance;
        private LocationListener listener;

        public UpdateRequest(String provider, long minTime, float minDistance, LocationListener listener) {
            this.provider = provider;
            this.minTime = minTime;
            this.minDistance = minDistance;
            this.listener = listener;
        }

        public void run() {
            locationManager.requestLocationUpdates(provider, minTime, minDistance, listener);
        }

        public void release() {
            locationManager.removeUpdates(listener);
        }

        public void destroy() {
            release();
            listener = null;
        }
    }
}