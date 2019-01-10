/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.car.media.common.source;

import static androidx.lifecycle.Transformations.map;
import static androidx.lifecycle.Transformations.switchMap;

import static com.android.car.arch.common.LiveDataFunctions.coalesceNull;
import static com.android.car.arch.common.LiveDataFunctions.combine;
import static com.android.car.arch.common.LiveDataFunctions.dataOf;
import static com.android.car.arch.common.LiveDataFunctions.mapNonNull;
import static com.android.car.arch.common.LiveDataFunctions.nullLiveData;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Application;
import android.content.ComponentName;
import android.content.ContentValues;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.RemoteException;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Log;

import androidx.annotation.VisibleForTesting;
import androidx.arch.core.util.Function;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProviders;

import com.android.car.media.common.MediaConstants;
import com.android.car.media.common.source.MediaBrowserConnector.ConnectionState;
import com.android.car.media.common.source.MediaBrowserConnector.MediaBrowserState;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Contains observable data needed for displaying playback and browse UI
 */
public class MediaSourceViewModel extends AndroidViewModel {
    private static final String TAG = "MediaSourceViewModel";

    private final MutableLiveData<MediaSource> mPrimaryMediaSource = dataOf(null);

    private final LiveData<MediaBrowserCompat> mConnectedMediaBrowser;

    // Media controller for selected media source.
    private final LiveData<MediaControllerCompat> mMediaController;

    // Media controller for active media source, may not be the same as selected media source.
    private final LiveData<MediaControllerCompat> mTopActiveMediaController;

    private final LiveData<Boolean> mIsCurrentMediaSourcePlaying;

    /**
     * Factory for creating dependencies. Can be swapped out for testing.
     */
    @VisibleForTesting
    interface InputFactory {
        LiveData<MediaBrowserState> createMediaBrowserConnector(
                @NonNull ComponentName browseService);

        ActiveMediaSelector createActiveMediaSelector();

        LiveData<List<MediaControllerCompat>> createActiveMediaControllerData();

        MediaControllerCompat getControllerForSession(@Nullable MediaSessionCompat.Token session);

        MediaSource getSelectedSourceFromContentProvider();
    }

    /** Returns the MediaSourceViewModel tied to the given activity. */
    public static MediaSourceViewModel get(@NonNull FragmentActivity activity) {
        return ViewModelProviders.of(activity).get(MediaSourceViewModel.class);
    }

    /**
     * Create a new instance of MediaSourceViewModel
     *
     * @see AndroidViewModel
     */
    public MediaSourceViewModel(@NonNull Application application) {
        this(application, new InputFactory() {

            @Override
            public LiveData<MediaBrowserState> createMediaBrowserConnector(
                    @NonNull ComponentName browseService) {
                return new MediaBrowserConnector(application, browseService);
            }

            @Override
            public LiveData<List<MediaControllerCompat>> createActiveMediaControllerData() {
                return new ActiveMediaControllersLiveData(application);
            }

            @Override
            public ActiveMediaSelector createActiveMediaSelector() {
                return new ActiveMediaSelector(application);
            }

            @Override
            public MediaControllerCompat getControllerForSession(
                    @Nullable MediaSessionCompat.Token token) {
                if (token == null) return null;
                try {
                    return new MediaControllerCompat(application, token);
                } catch (RemoteException e) {
                    Log.e(TAG, "Couldn't get MediaControllerCompat", e);
                    return null;
                }
            }

            @Override
            public MediaSource getSelectedSourceFromContentProvider() {
                Cursor cursor = application.getContentResolver().query(
                        MediaConstants.URI_MEDIA_SOURCE, null, null, null);
                try {
                    if (cursor != null && cursor.moveToFirst()) {
                        return new MediaSource(application, cursor.getString(0));
                    }
                    return null;
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            }
        });
    }

    @VisibleForTesting
    MediaSourceViewModel(@NonNull Application application, @NonNull InputFactory inputFactory) {
        super(application);

        LiveData<List<MediaControllerCompat>> activeMediaControllers =
                inputFactory.createActiveMediaControllerData();
        ActiveMediaSelector mediaSelector = inputFactory.createActiveMediaSelector();
        mTopActiveMediaController = map(activeMediaControllers,
                new LoopbackFunction<>(mediaSelector::getTopMostMediaController));

        mPrimaryMediaSource.setValue(inputFactory.getSelectedSourceFromContentProvider());
        application.getContentResolver().registerContentObserver(
                MediaConstants.URI_MEDIA_SOURCE, false,
                new ContentObserver(new Handler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        mPrimaryMediaSource.setValue(
                                inputFactory.getSelectedSourceFromContentProvider());
                    }
                });

        LiveData<MediaBrowserState> mediaBrowserState = switchMap(mPrimaryMediaSource,
                (mediaSource) -> {
                    if (mediaSource == null) {
                        return nullLiveData();
                    }
                    ComponentName browseService = mediaSource.getBrowseServiceComponentName();
                    if (browseService == null) {
                        return nullLiveData();
                    }
                    return inputFactory.createMediaBrowserConnector(browseService);
                });
        mConnectedMediaBrowser = map(mediaBrowserState,
                state -> state != null && (state.mConnectionState == ConnectionState.CONNECTED)
                        ? state.mMediaBrowser : null);

        LiveData<MediaControllerCompat> controllerFromActiveList =
                combine(activeMediaControllers, mPrimaryMediaSource,
                        mediaSelector::getControllerForSource);
        LiveData<MediaControllerCompat> controllerFromMediaBrowser =
                mapNonNull(mConnectedMediaBrowser,
                        browser -> inputFactory.getControllerForSession(browser.getSessionToken()));
        // Prefer fetching MediaController from MediaSessionManager's active controller
        // list. Otherwise use controller from MediaBrowser (which requires connecting to it).
        mMediaController = coalesceNull(controllerFromActiveList, controllerFromMediaBrowser);

        mIsCurrentMediaSourcePlaying = combine(mTopActiveMediaController, mPrimaryMediaSource,
                (mediaController, mediaSource) ->
                        mediaController != null && mediaSource != null
                                && Objects.equals(mediaController.getPackageName(),
                                mediaSource.getPackageName()));
    }

    /**
     * Returns a LiveData that emits the MediaSource that is to be browsed or displayed.
     */
    public LiveData<MediaSource> getPrimaryMediaSource() {
        return mPrimaryMediaSource;
    }

    /**
     * Updates the primary media source, and notifies content provider of new source
     */
    public void setPrimaryMediaSource(MediaSource mediaSource) {
        ContentValues values = new ContentValues();
        values.put(MediaConstants.KEY_PACKAGE_NAME, mediaSource.getPackageName());
        getApplication().getContentResolver().update(
                MediaConstants.URI_MEDIA_SOURCE, values, null, null);
    }

    /**
     * Returns a LiveData that emits the currently connected MediaBrowser. Emits {@code null} if no
     * MediaSource is set, if the MediaSource does not support browsing, or if the MediaBrowser is
     * not connected. Observing the LiveData will attempt to connect to a media browse session if
     * possible.
     */
    public LiveData<MediaBrowserCompat> getConnectedMediaBrowser() {
        return mConnectedMediaBrowser;
    }

    /**
     * Returns a LiveData that emits a {@link MediaController} that allows controlling this media
     * source, or emits {@code null} if the media source doesn't support browsing or the browser is
     * not connected.
     */
    public LiveData<MediaControllerCompat> getMediaController() {
        return mMediaController;
    }

    /**
     * Returns a LiveData that emits a {@link MediaController} for the primary active media source.
     * Note that this may not be from the selected media source.
     */
    public LiveData<MediaControllerCompat> getTopActiveMediaController() {
        return mTopActiveMediaController;
    }

    /**
     * Emits {@code true} iff the selected media source is the active media source
     */
    public LiveData<Boolean> isCurrentMediaSourcePlaying() {
        return mIsCurrentMediaSourcePlaying;
    }

    private static class LoopbackFunction<P, V> implements Function<P, V> {

        private final BiFunction<P, V, V> mFunction;
        private V mLastValue;

        private LoopbackFunction(BiFunction<P, V, V> function) {
            mFunction = function;
        }

        @Override
        public V apply(P param) {
            mLastValue = mFunction.apply(param, mLastValue);
            return mLastValue;
        }
    }
}