/*
 * Created on 11/24/19 4:11 PM.
 * Copyright © 2019 刘振林. All rights reserved.
 */

package com.liuzhenlin.texturevideoview;

import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Messenger;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.database.ExoDatabaseProvider;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.ads.AdsMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.FileDataSourceFactory;
import com.google.android.exoplayer2.upstream.cache.Cache;
import com.google.android.exoplayer2.upstream.cache.CacheDataSink;
import com.google.android.exoplayer2.upstream.cache.CacheDataSinkFactory;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.CacheDataSourceFactory;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;
import com.google.android.exoplayer2.util.Util;
import com.google.android.material.snackbar.Snackbar;
import com.liuzhenlin.texturevideoview.receiver.HeadsetEventsReceiver;
import com.liuzhenlin.texturevideoview.receiver.MediaButtonEventHandler;
import com.liuzhenlin.texturevideoview.receiver.MediaButtonEventReceiver;
import com.liuzhenlin.texturevideoview.utils.TimeUtil;
import com.liuzhenlin.texturevideoview.utils.Utils;

import java.io.File;

/**
 * A sub implementation class of {@link VideoPlayer} to deal with the audio/video playback logic
 * related to the media player component through an {@link com.google.android.exoplayer2.ExoPlayer} object.
 *
 * @author 刘振林
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
public class ExoVideoPlayer extends VideoPlayer {

    private static final String TAG = "ExoVideoPlayer";

    private String mUserAgent;

    private SimpleExoPlayer mExoPlayer;
    private AdsMediaSource.MediaSourceFactory mMediaSourceFactory;
    private AdsMediaSource.MediaSourceFactory mTmpMediaSourceFactory;
    private static DataSource.Factory sDefaultDataSourceFactory;

    private final AudioManager.OnAudioFocusChangeListener mOnAudioFocusChangeListener
            = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            switch (focusChange) {
                // Audio focus gained
                case AudioManager.AUDIOFOCUS_GAIN:
                    play(false);
                    break;

                // Loss of audio focus of unknown duration.
                // This usually happens when the user switches to another audio/video application
                // that causes our view to stop playing, so the video can be thought of as
                // being paused/closed by the user.
                case AudioManager.AUDIOFOCUS_LOSS:
                    if (mVideoView != null && mVideoView.isInForeground()) {
                        // If the view is still in the foreground, pauses the video only.
                        pause(true);
                    } else {
                        // But if this occurs during background playback, we must close the video
                        // to release the resources associated with it.
                        closeVideoInternal(true);
                    }
                    break;

                // Temporarily lose the audio focus and will probably gain it again soon.
                // Must stop the video playback but no need for releasing the ExoPlayer here.
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    pause(false);
                    break;

                // Temporarily lose the audio focus but the playback can continue.
                // The volume of the playback needs to be turned down.
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    if (mExoPlayer != null) {
                        mExoPlayer.setVolume(0.5f);
                    }
                    break;
            }
        }
    };
    private final AudioFocusRequest mAudioFocusRequest =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                    new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(sDefaultAudioAttrs.getAudioAttributesV21())
                            .setOnAudioFocusChangeListener(mOnAudioFocusChangeListener)
                            .setAcceptsDelayedFocusGain(true)
                            .build()
                    : null;

    public ExoVideoPlayer(@NonNull Context context) {
        super(context);
    }

    /**
     * @return the user (the person that may be using this class) specified
     * {@link AdsMediaSource.MediaSourceFactory} for reading the media content(s)
     */
    @Nullable
    public AdsMediaSource.MediaSourceFactory getMediaSourceFactory() {
        return mMediaSourceFactory;
    }

    /**
     * Sets a MediaSourceFactory for creating {@link com.google.android.exoplayer2.source.MediaSource}s
     * to play the provided media stream content (if any), or `null`, the MediaSourceFactory
     * with {@link DefaultDataSourceFactory} will be created to read the media, based on
     * the corresponding media stream type.
     *
     * @param factory a subclass instance of {@link AdsMediaSource.MediaSourceFactory}
     */
    public void setMediaSourceFactory(@Nullable AdsMediaSource.MediaSourceFactory factory) {
        mMediaSourceFactory = factory;
        if (mMediaSourceFactory != null) {
            mTmpMediaSourceFactory = null;
        }
    }

    /**
     * @return the default {@link DataSource.Factory} created by this class, which will be used for
     * various of {@link AdsMediaSource.MediaSourceFactory}s (if the user specified one is not set).
     */
    @NonNull
    public DataSource.Factory getDefaultDataSourceFactory() {
        if (sDefaultDataSourceFactory == null) {
            Cache cache = new SimpleCache(
                    new File(getBaseVideoCacheDirectory(), "exo"),
                    new LeastRecentlyUsedCacheEvictor(DEFAULT_MAXIMUM_CACHE_SIZE),
                    new ExoDatabaseProvider(mContext));
            DataSource.Factory upstreamFactory =
                    new DefaultDataSourceFactory(mContext,
                            new DefaultHttpDataSourceFactory(getUserAgent()));
            DataSource.Factory cacheReadDataSourceFactory = new FileDataSourceFactory();
            CacheDataSinkFactory cacheWriteDataSourceFactory =
                    new CacheDataSinkFactory(cache, CacheDataSink.DEFAULT_FRAGMENT_SIZE, 1024);
            sDefaultDataSourceFactory = new CacheDataSourceFactory(
                    cache,
                    upstreamFactory, cacheReadDataSourceFactory, cacheWriteDataSourceFactory,
                    CacheDataSource.FLAG_BLOCK_ON_CACHE
                            | CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR,
                    null);
        }
        return sDefaultDataSourceFactory;
    }

    /**
     * @return a user agent string based on the application name resolved from the context object
     * of the view this player is bound to and the `exoplayer-core` library version,
     * which can be used to create a {@link com.google.android.exoplayer2.upstream.DataSource.Factory}
     * instance for the {@link AdsMediaSource.MediaSourceFactory} subclasses.
     */
    @NonNull
    public String getUserAgent() {
        if (mUserAgent == null) {
            if (mVideoView != null)
                mUserAgent = mVideoView.mUserAgent;
            else
                mUserAgent = Util.getUserAgent(mContext,
                        mContext.getApplicationInfo().loadLabel(mContext.getPackageManager()).toString());
        }
        return mUserAgent;
    }

    @Override
    public void setVideoResourceId(int resId) {
        setVideoPath(resId == 0 ? null : "rawresource:///" + resId);
    }

    @Override
    protected void onVideoSurfaceChanged(@Nullable Surface surface) {
        if (mExoPlayer != null) {
            mExoPlayer.setVideoSurface(surface);
        }
    }

    @Override
    protected void openVideoInternal(@Nullable Surface surface) {
        if (mExoPlayer == null && mVideoUri != null
                && !(mVideoView != null && surface == null)
                && (mPrivateFlags & PFLAG_VIDEO_PAUSED_BY_USER) == 0) {
            mExoPlayer = ExoPlayerFactory.newSimpleInstance(mContext);
            mExoPlayer.setVideoSurface(surface);
            mExoPlayer.setAudioAttributes(sDefaultAudioAttrs);
            setPlaybackSpeed(mUserPlaybackSpeed);
            mExoPlayer.setRepeatMode(
                    isSingleVideoLoopPlayback() ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
            mExoPlayer.addListener(new Player.EventListener() {
                @Override
                public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                    if (mVideoView != null) {
                        mVideoView.showLoadingView(playbackState == Player.STATE_BUFFERING);
                    }

                    switch (playbackState) {
                        case Player.STATE_READY:
                            if (getPlaybackState() == PLAYBACK_STATE_PREPARING) {
                                if ((mPrivateFlags & PFLAG_VIDEO_INFO_RESOLVED) == 0) {
                                    final long duration = mExoPlayer.getDuration();
                                    if (duration == C.TIME_UNSET) {
                                        mVideoDuration = UNKNOWN_DURATION;
                                        mVideoDurationString = DEFAULT_STRING_VIDEO_DURATION;
                                    } else {
                                        mVideoDuration = (int) duration;
                                        mVideoDurationString = TimeUtil.formatTimeByColon(mVideoDuration);
                                    }
                                    mPrivateFlags |= PFLAG_VIDEO_INFO_RESOLVED;
                                }
                                setPlaybackState(PLAYBACK_STATE_PREPARED);
                                play(false);
                            }
                            break;

                        case Player.STATE_ENDED:
                            onPlaybackCompleted();
                            break;
                    }
                }

                @Override
                public void onSeekProcessed() {
                    onVideoSeekProcessed();
                }

                @Override
                public void onPlayerError(ExoPlaybackException error) {
                    if (InternalConsts.DEBUG) {
                        Log.e(TAG, "playback error", error);
                    }
                    final int stringRes;
                    if (error.type == ExoPlaybackException.TYPE_SOURCE) {
                        stringRes = R.string.failedToLoadThisVideo;
                    } else {
                        stringRes = R.string.unknownErrorOccurredWhenVideoIsPlaying;
                    }
                    if (mVideoView != null) {
                        Utils.showUserCancelableSnackbar(mVideoView, stringRes, Snackbar.LENGTH_SHORT);
                    } else {
                        Toast.makeText(mContext, stringRes, Toast.LENGTH_SHORT).show();
                    }

                    final boolean playing = isPlaying();
                    setPlaybackState(PLAYBACK_STATE_ERROR);
                    if (playing) {
                        pauseInternal(false);
                    }
                }
            });
            mExoPlayer.addVideoListener(new com.google.android.exoplayer2.video.VideoListener() {
                @Override
                public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                    ExoVideoPlayer.this.onVideoSizeChanged(width, height);
                }
            });
            startVideo();

            MediaButtonEventReceiver.setMediaButtonEventHandler(
                    new MediaButtonEventHandler(new Messenger(new MsgHandler(this))));
            mHeadsetEventsReceiver = new HeadsetEventsReceiver(mContext) {
                @Override
                public void onHeadsetPluggedOutOrBluetoothDisconnected() {
                    pause(true);
                }
            };
            mHeadsetEventsReceiver.register(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        }
    }

    private void startVideo() {
        if (mVideoUri != null) {
            setPlaybackState(PLAYBACK_STATE_PREPARING);
            mExoPlayer.prepare(obtainMediaSourceFactory(mVideoUri).createMediaSource(mVideoUri));
        } else {
            mExoPlayer.stop(true);
            setPlaybackState(PLAYBACK_STATE_IDLE);
        }
        if (mVideoView != null) {
            mVideoView.cancelDraggingVideoSeekBar();
        }
    }

    /*package*/ AdsMediaSource.MediaSourceFactory obtainMediaSourceFactory(Uri uri) {
        if (mMediaSourceFactory != null) return mMediaSourceFactory;

        @C.ContentType int type = Util.inferContentType(uri, null);
        switch (type) {
            case C.TYPE_DASH:
                if (mTmpMediaSourceFactory instanceof DashMediaSource.Factory) {
                    return mTmpMediaSourceFactory;
                }
                return mTmpMediaSourceFactory =
                        new DashMediaSource.Factory(getDefaultDataSourceFactory());

            case C.TYPE_SS:
                if (mTmpMediaSourceFactory instanceof SsMediaSource.Factory) {
                    return mTmpMediaSourceFactory;
                }
                return mTmpMediaSourceFactory =
                        new SsMediaSource.Factory(getDefaultDataSourceFactory());

            case C.TYPE_HLS:
                if (mTmpMediaSourceFactory instanceof HlsMediaSource.Factory) {
                    return mTmpMediaSourceFactory;
                }
                return mTmpMediaSourceFactory =
                        new HlsMediaSource.Factory(getDefaultDataSourceFactory());

            case C.TYPE_OTHER:
                if (mTmpMediaSourceFactory instanceof ProgressiveMediaSource.Factory) {
                    return mTmpMediaSourceFactory;
                }
                return mTmpMediaSourceFactory =
                        new ProgressiveMediaSource.Factory(getDefaultDataSourceFactory());

            default:
                throw new IllegalStateException("Unsupported media stream type: " + type);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * <strong>NOTE: </strong> If this method is called during the video being closed, it does
     * nothing other than setting the playback state to {@link #PLAYBACK_STATE_UNDEFINED}, so as
     * not to suppress the next call to the {@link #openVideo())} method if the current playback state
     * is {@link #PLAYBACK_STATE_COMPLETED}, and the state is usually needed to be updated in
     * this call, too. Thus for all of the above reasons, it is the best to switch over to.
     */
    @Override
    public void restartVideo() {
        // First, resets mSeekOnPlay to 0 in case the ExoPlayer object is (being) released.
        // This ensures the video to be started at its beginning position the next time it resumes.
        mSeekOnPlay = 0;
        if (mExoPlayer != null) {
            if ((mPrivateFlags & PFLAG_VIDEO_IS_CLOSING) != 0) {
                setPlaybackState(PLAYBACK_STATE_UNDEFINED);
            } else {
                // Not clear the PFLAG_VIDEO_INFO_RESOLVED flag
                mPrivateFlags &= ~(PFLAG_VIDEO_PAUSED_BY_USER
                        | PFLAG_CAN_GET_ACTUAL_POSITION_FROM_PLAYER);
                pause(false);
                startVideo();
            }
        }
    }

    @Override
    public void play(boolean fromUser) {
        if ((mPrivateFlags & PFLAG_VIDEO_IS_CLOSING) != 0) {
            // In case the video playback is closing
            return;
        }

        final int playbackState = getPlaybackState();

        if (mExoPlayer == null) {
            // Opens the video only if this is a user request
            if (fromUser) {
                // If the video playback finished, skip to the next video if possible
                if (playbackState == PLAYBACK_STATE_COMPLETED &&
                        skipToNextIfPossible() && mExoPlayer != null) {
                    return;
                }

                mPrivateFlags &= ~PFLAG_VIDEO_PAUSED_BY_USER;
                openVideo(true);
            } else {
                Log.w(TAG, "Cannot start playback programmatically before the video is opened");
            }
            return;
        }

        switch (playbackState) {
            case PLAYBACK_STATE_UNDEFINED:
            case PLAYBACK_STATE_IDLE: // no video is set
                // Already in the preparing or playing state
            case PLAYBACK_STATE_PREPARING:
            case PLAYBACK_STATE_PLAYING:
                break;

            case PLAYBACK_STATE_ERROR:
                // Retries the failed playback after error occurred
                setPlaybackState(PLAYBACK_STATE_PREPARING);
                mExoPlayer.retry();
                break;

            case PLAYBACK_STATE_COMPLETED:
                if (skipToNextIfPossible() && getPlaybackState() != PLAYBACK_STATE_COMPLETED) {
                    break;
                }
                // Starts the video only if we have prepared it for the player
            case PLAYBACK_STATE_PREPARED:
            case PLAYBACK_STATE_PAUSED:
                //@formatter:off
                final int result = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                      ? mAudioManager.requestAudioFocus(mAudioFocusRequest)
                      : mAudioManager.requestAudioFocus(mOnAudioFocusChangeListener,
                              AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                //@formatter:on
                switch (result) {
                    case AudioManager.AUDIOFOCUS_REQUEST_FAILED:
                        if (InternalConsts.DEBUG) {
                            Log.w(TAG, "Failed to request audio focus");
                        }
                        // Starts to play video even if the audio focus is not gained, but it is
                        // best not to happen.
                    case AudioManager.AUDIOFOCUS_REQUEST_GRANTED:
                        // Ensure the player's volume is at its maximum
                        if (mExoPlayer.getVolume() != 1.0f) {
                            mExoPlayer.setVolume(1.0f);
                        }
                        mExoPlayer.setPlayWhenReady(true);
                        if (mSeekOnPlay != 0) {
                            mExoPlayer.seekTo(mSeekOnPlay);
                            mSeekOnPlay = 0;
                        } else if (playbackState == PLAYBACK_STATE_COMPLETED) {
                            mExoPlayer.seekToDefaultPosition();
                        }
                        mPrivateFlags &= ~PFLAG_VIDEO_PAUSED_BY_USER;
                        mPrivateFlags |= PFLAG_CAN_GET_ACTUAL_POSITION_FROM_PLAYER;
                        onVideoStarted();

                        // Register MediaButtonEventReceiver every time the video starts, which
                        // will ensure it to be the sole receiver of MEDIA_BUTTON intents
                        mAudioManager.registerMediaButtonEventReceiver(sMediaButtonEventReceiverComponent);
                        break;

                    case AudioManager.AUDIOFOCUS_REQUEST_DELAYED:
                        // do nothing
                        break;
                }
                break;
        }
    }

    @Override
    public void pause(boolean fromUser) {
        if (isPlaying()) {
            pauseInternal(fromUser);
        }
    }

    /**
     * Similar to {@link #pause(boolean)}}, but does not check the playback state.
     */
    private void pauseInternal(boolean fromUser) {
        mExoPlayer.setPlayWhenReady(false);
        mPrivateFlags = mPrivateFlags & ~PFLAG_VIDEO_PAUSED_BY_USER
                | (fromUser ? PFLAG_VIDEO_PAUSED_BY_USER : 0);
        onVideoStopped();
    }

    @Override
    protected void closeVideoInternal(boolean fromUser) {
        if (mExoPlayer != null && (mPrivateFlags & PFLAG_VIDEO_IS_CLOSING) == 0) {
            mPrivateFlags |= PFLAG_VIDEO_IS_CLOSING;

            if (getPlaybackState() != PLAYBACK_STATE_COMPLETED) {
                mSeekOnPlay = getVideoProgress();
            }
            pause(fromUser);
            abandonAudioFocus();
            mExoPlayer.release();
            mExoPlayer = null;
            mTmpMediaSourceFactory = null;
            mPrivateFlags &= ~PFLAG_CAN_GET_ACTUAL_POSITION_FROM_PLAYER;
            // Resets the cached playback speed to prepare for the next resume of the video player
            mPlaybackSpeed = DEFAULT_PLAYBACK_SPEED;

            mHeadsetEventsReceiver.unregister();
            mHeadsetEventsReceiver = null;

            mPrivateFlags &= ~PFLAG_VIDEO_IS_CLOSING;

            if (mVideoView != null)
                mVideoView.showLoadingView(false);
        }
        if (mVideoView != null) {
            mVideoView.cancelDraggingVideoSeekBar();
        }
    }

    private void abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mAudioManager.abandonAudioFocusRequest(mAudioFocusRequest);
        } else {
            mAudioManager.abandonAudioFocus(mOnAudioFocusChangeListener);
        }
    }

    @Override
    public void seekTo(int positionMs, boolean fromUser) {
        if (isPlaying()) {
            mExoPlayer.seekTo(positionMs);
        } else {
            mSeekOnPlay = positionMs;
            play(fromUser);
        }
    }

    @Override
    public int getVideoProgress() {
        if ((mPrivateFlags & PFLAG_CAN_GET_ACTUAL_POSITION_FROM_PLAYER) != 0) {
            return (int) mExoPlayer.getCurrentPosition();
        }
        if (getPlaybackState() == PLAYBACK_STATE_COMPLETED) {
            // If the video completed and the ExoPlayer object was released, we would get 0.
            return mVideoDuration;
        }
        return mSeekOnPlay;
    }

    @Override
    public int getVideoBufferProgress() {
        if (mExoPlayer != null) {
            return (int) mExoPlayer.getBufferedPosition();
        }
        return 0;
    }

    @Override
    public void setSingleVideoLoopPlayback(boolean looping) {
        if (looping != isSingleVideoLoopPlayback()) {
            if (mExoPlayer != null) {
                mExoPlayer.setRepeatMode(looping ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
            }
            super.setSingleVideoLoopPlayback(looping);
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY)
    @Override
    public void setPlaybackSpeed(float speed) {
        if (speed != mPlaybackSpeed) {
            mUserPlaybackSpeed = speed;
            if (mExoPlayer != null) {
                mExoPlayer.setPlaybackParameters(new PlaybackParameters(speed));
                super.setPlaybackSpeed(speed);
            }
        }
    }

    @Override
    protected boolean isPlayerCreated() {
        return mExoPlayer != null;
    }

    @Override
    protected boolean onPlaybackCompleted() {
        final boolean closed = super.onPlaybackCompleted();
        if (closed) {
            // Since the playback completion state deters the pause(boolean) method from being called
            // within the closeVideoInternal(boolean) method, we need this extra step to add
            // the PFLAG_VIDEO_PAUSED_BY_USER flag into mPrivateFlags to denote that the user pauses
            // (closes) the video.
            mPrivateFlags |= PFLAG_VIDEO_PAUSED_BY_USER;
            onVideoStopped();
        }
        return closed;
    }
}