/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWeatherWatchFace extends CanvasWatchFaceService {

    private static final String TAG = "SunshineWatchFace";
    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    /*
    Constants for retreiving weather data through Data Api.
     */

    private static final String TEMP_HIGH = "com.sunshine.weather.hightemp";
    private static final String TEMP_LOW = "com.sunshine.weather.lowtemp";
    private static final String CONDITION = "com.sunshine.weather.condition";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWeatherWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWeatherWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWeatherWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
    GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        boolean mRegisteredTimeZoneReceiver = false;
        boolean mAmbient;
        Calendar mCalendar;
        private int specW, specH;
        private View mWatchFaceLayout;
        private TextView mTimeText, mDateText, mTempHighText, mTempLowText;
        private ImageView mWeatherIconView;
        private View mSeparatorView;
        private final Point displaySize = new Point();
        float mXOffset = 0;
        float mYOffset = 0;
        GoogleApiClient mGoogleApiClient;
        /**
         * Handler to update the time periodically in interactive mode.
         */
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs = INTERACTIVE_UPDATE_RATE_MS
                                    - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWeatherWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setHotwordIndicatorGravity(Gravity.CENTER_HORIZONTAL|Gravity.TOP)
                    .build());

            Resources resources = SunshineWeatherWatchFace.this.getResources();

            LayoutInflater inflater =
                    (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mWatchFaceLayout = inflater.inflate(R.layout.watchface, null);

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWeatherWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            mGoogleApiClient.connect();

            Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay();
            display.getSize(displaySize);

            specW = View.MeasureSpec.makeMeasureSpec(displaySize.x,
                    View.MeasureSpec.EXACTLY);
            specH = View.MeasureSpec.makeMeasureSpec(displaySize.y,
                    View.MeasureSpec.EXACTLY);

            mTimeText = (TextView) mWatchFaceLayout.findViewById(R.id.time_text);
            mDateText = (TextView) mWatchFaceLayout.findViewById(R.id.date_text);
            mTempHighText = (TextView) mWatchFaceLayout.findViewById(R.id.temp_high_text);
            mTempLowText = (TextView) mWatchFaceLayout.findViewById(R.id.temp_low_text);
            mWeatherIconView = (ImageView) mWatchFaceLayout.findViewById(R.id.weather_icon);
            mSeparatorView = (View) mWatchFaceLayout.findViewById(R.id.separator);

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onVisibilityChanged: " + visible);
            }

            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWeatherWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWeatherWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            if (insets.isRound()) {
                // Shrink the face to fit on a round screen
                mYOffset = mXOffset = displaySize.x * 0.1f;
                displaySize.y -= 2 * mXOffset;
                displaySize.x -= 2 * mXOffset;
            } else {
                mXOffset = mYOffset = 0;
            }

            specW = View.MeasureSpec.makeMeasureSpec(displaySize.x, View.MeasureSpec.EXACTLY);
            specH = View.MeasureSpec.makeMeasureSpec(displaySize.y, View.MeasureSpec.EXACTLY);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            Typeface font = Typeface.create("sans-serif-condensed",
                    inAmbientMode ? Typeface.NORMAL : Typeface.BOLD);

            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (inAmbientMode) {
                    mSeparatorView.setBackgroundColor(getResources().getColor(R.color.white));
                    mWatchFaceLayout.setBackgroundColor(getResources().getColor(R.color.black));
                    mDateText.setTextColor(getResources().getColor(R.color.white));
                    mTempHighText.setTextColor(getResources().getColor(R.color.white));
                    mTempLowText.setTextColor(getResources().getColor(R.color.white));
                    mWeatherIconView.setVisibility(View.GONE);

                } else {
                    mSeparatorView.setBackgroundColor(getResources().getColor(R.color.colorPrimaryLight));
                    mWatchFaceLayout.setBackgroundColor(getResources().getColor(R.color.background));
                    mDateText.setTextColor(getResources().getColor(R.color.colorPrimaryLight));
                    mTempHighText.setTextColor(getResources().getColor(R.color.primary_text_dark));
                    mTempLowText.setTextColor(getResources().getColor(R.color.colorPrimaryLight));
                    mWeatherIconView.setVisibility(View.VISIBLE);
                }

                mDateText.setTypeface(font);
                mTempHighText.setTypeface(font);
                mTempLowText.setTypeface(font);

                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String time = mAmbient
                    ? String.format("%s", new SimpleDateFormat("hh:mm a").format(mCalendar.getTimeInMillis()))
                    : String.format("%s", new SimpleDateFormat("HH:mm:ss").format(mCalendar.getTimeInMillis()));

            String date = String.format("%s, %s %02d %04d", new SimpleDateFormat("EEE").format(mCalendar.get(Calendar.DAY_OF_WEEK)), new SimpleDateFormat("MMM").format(mCalendar.get(Calendar.MONTH)), mCalendar.get(Calendar.DATE), mCalendar.get(Calendar.YEAR));

            mTimeText.setText(time);

            if (!mAmbient)
                mDateText.setText(date);

            mWatchFaceLayout.measure(specW, specH);
            mWatchFaceLayout.layout(0, 0, mWatchFaceLayout.getMeasuredWidth(), mWatchFaceLayout.getMeasuredHeight());

            int backgroundColor = mAmbient ? getResources().getColor(R.color.black) : getResources().getColor(R.color.background);
            canvas.drawColor(backgroundColor);
            canvas.translate(mXOffset, mYOffset);
            mWatchFaceLayout.draw(canvas);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            Log.w(TAG, "Wearable API connected.");
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            if (connectionResult.getErrorCode() == ConnectionResult.API_UNAVAILABLE) {
                Log.w(TAG, "Wearable API is unavailable.");
            }
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.w(TAG, "DataItem changed.");

            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo("/weather") == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();

                        final Asset weatherConditionAsset = dataMap.getAsset(CONDITION);
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                Bitmap bitmap = loadBitmapFromAsset(weatherConditionAsset);
                                mWeatherIconView.setImageBitmap(bitmap);
                            }
                        }).start();


                        mTempHighText.setText(String.format("%02d", (int) dataMap.getDouble(TEMP_HIGH)).concat("\u00b0"));
                        mTempLowText.setText(String.format("%02d", (int) dataMap.getDouble(TEMP_LOW)).concat("\u00b0"));
                        invalidate();

                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                    // DataItem deleted
                }
            }
        }

        public Bitmap loadBitmapFromAsset(Asset asset) {
            if (asset == null) {
                throw new IllegalArgumentException("Asset must be non-null");
            }

            ConnectionResult result =
                    mGoogleApiClient.blockingConnect(MSG_UPDATE_TIME, TimeUnit.MILLISECONDS);
            if (!result.isSuccess()) {
                return null;
            }
            // convert asset into a file descriptor and block until it's ready
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                    mGoogleApiClient, asset).await().getInputStream();

            if (assetInputStream == null) {
                Log.w(TAG, "Requested an unknown Asset.");
                return null;
            }
            // decode the stream into a bitmap
            return BitmapFactory.decodeStream(assetInputStream);
        }
    }
}
