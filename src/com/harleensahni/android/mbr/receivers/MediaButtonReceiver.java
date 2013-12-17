/*
 * Copyright 2011 Harleen Sahni
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.harleensahni.android.mbr.receivers;

import static com.harleensahni.android.mbr.Constants.TAG;

import java.util.ArrayList;
import java.util.List;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;

import com.harleensahni.android.mbr.Constants;
import com.harleensahni.android.mbr.ReceiverSelector;
import com.harleensahni.android.mbr.ReceiverSelectorLocked;
import com.harleensahni.android.mbr.Utils;

/**
 * Handles routing media button intents to application that is playing music
 * 
 * @author Harleen Sahni
 */
public class MediaButtonReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (!preferences.getBoolean(Constants.ENABLED_PREF_KEY, true)) {
            return;
        }

        ActivityManager activityManager = ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE));

        if (Utils.isHandlingThroughSoleReceiver()) {
            // Try to figure out if our selector is currently open
            List<RunningTaskInfo> runningTasks = activityManager.getRunningTasks(1);
            if (runningTasks.size() > 0) {
                String className = runningTasks.get(0).topActivity.getClassName();
                if (className.equals(ReceiverSelector.class.getName())
                        || className.equals(ReceiverSelectorLocked.class.getName())) {
                    Log.d(TAG, "Selector is already open, rebroadcasting for selector only.");
                    Intent receiver_selector_intent = new Intent(Constants.INTENT_ACTION_VIEW_MEDIA_LIST_KEYPRESS);
                    receiver_selector_intent.putExtras(intent);
                    context.sendBroadcast(receiver_selector_intent);
                    if (isOrderedBroadcast()) {
                        abortBroadcast();
                    }
                    return;
                }
            }
        }

        // Sometimes we take too long finish and Android kills
        // us and forwards the intent to another broadcast receiver. If this
        // keeps being a problem, than we should always return immediately and
        // handle forwarding the intent in another thread

        // TODO Handle the case where there is only 0 or 1 media receivers
        // besides ourself by disabling our media receiver
        if (Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            /* COMMENTED OUT FOR MARKET RELEASE Log.i(TAG, "Media Button Receiver: received media button intent: " + intent); */

            KeyEvent keyEvent = (KeyEvent) intent.getExtras().get(Intent.EXTRA_KEY_EVENT);
            int keyCode = Utils.getAdjustedKeyCode(keyEvent);

            // Don't want to capture volume buttons
            if (Utils.isMediaButton(keyCode)) {
                /* COMMENTED OUT FOR MARKET RELEASE Log.i(TAG, "Media Button Receiver: handling legitimate media key event: " + keyEvent); */

                AudioManager audioManager = ((AudioManager) context.getSystemService(Context.AUDIO_SERVICE));

                if (audioManager.isMusicActive()) {
                    String last_media_button_receiver = preferences.getString(Constants.LAST_MEDIA_BUTTON_RECEIVER,
                            null);

                    if (last_media_button_receiver == null) {

                        // XXX Need to improve this behavior, somethings doesn't
                        // work. For instance, if you select "Listen" App, and
                        // then
                        // hit next,
                        // the built in music app handles it because it has a
                        // higher
                        // priority. If we could change priorities on app
                        // selection
                        // and have it stick,
                        // would probably be good enough to handle this.
                        // One thing to do would be to add specific classes that
                        // check for each knowhn app if our generic way doesn't
                        // work
                        // well for them
                        Log.d(TAG, "Media Button Receiver: may pass on event because music is already playing: "
                                + keyEvent);

                        // Try to best guess who is playing the music based off
                        // of
                        // running foreground services.

                        // XXX Move stuff like receivers to service so we can
                        // cache
                        // it. Doing too much stuff here
                        List<ResolveInfo> receivers = Utils.getMediaReceivers(context.getPackageManager(), false, null);

                        // Remove our app from the list so users can't select
                        // it.
                        if (receivers != null) {

                            List<RunningServiceInfo> runningServices = activityManager
                                    .getRunningServices(Integer.MAX_VALUE);
                            // Only need to look at services that are foreground
                            // and started
                            List<RunningServiceInfo> candidateServices = new ArrayList<ActivityManager.RunningServiceInfo>();
                            for (RunningServiceInfo runningService : runningServices) {
                                if (runningService.started && runningService.foreground) {
                                    candidateServices.add(runningService);
                                }
                            }

                            boolean matched = false;
                            for (ResolveInfo resolveInfo : receivers) {
                                if (MediaButtonReceiver.class.getName().equals(resolveInfo.activityInfo.name)) {
                                    continue;
                                }

                                // Find any service that's package matches that
                                // of a
                                // receivers.
                                for (RunningServiceInfo candidateService : candidateServices) {
                                    if (candidateService.foreground
                                            && candidateService.started
                                            && resolveInfo.activityInfo.packageName.equals(candidateService.service
                                                    .getPackageName())) {
                                        if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                                            Utils.forwardKeyCodeToComponent(context,
                                                    new ComponentName(resolveInfo.activityInfo.packageName,
                                                            resolveInfo.activityInfo.name), false, keyCode, null);
                                        }
                                        if (isOrderedBroadcast()) {
                                            abortBroadcast();
                                        }
                                        matched = true;
                                        /* COMMENTED OUT FOR MARKET RELEASE Log.i(TAG, "Media Button Receiver: Music playing and passed on event : "
                                                + keyEvent + " to " + resolveInfo.activityInfo.name); */
                                        break;
                                    }
                                }
                                if (matched) {
                                    // TODO Need to handle case with multiple
                                    // matches, maybe by showing selector
                                    break;
                                }
                            }
                            if (!matched) {
                                if (preferences.getBoolean(Constants.CONSERVATIVE_PREF_KEY, false)) {
                                    if (isOrderedBroadcast()) {
                                        abortBroadcast();
                                    }
                                    /* COMMENTED OUT FOR MARKET RELEASE Log.i(TAG,
                                            "Media Button Receiver: No Receivers found playing music. Intent broadcast will be aborted."); */
                                    if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                                        showSelector(context, intent, keyEvent);
                                    }

                                } else {
                                    /* COMMENTED OUT FOR MARKET RELEASE Log.i(TAG,
                                            "Media Button Receiver: No Receivers found playing music. Intent will use regular priorities."); */
                                }
                            }
                        }

                        return;
                    } else {
                        if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                            Utils.forwardKeyCodeToComponent(context,
                                    ComponentName.unflattenFromString(last_media_button_receiver), false, keyCode, null);
                        }
                        return;
                    }
                }

                // No music playing
                if (isOrderedBroadcast()) {
                    abortBroadcast();
                }

                if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                    List<ResolveInfo> receivers = Utils.getMediaReceivers(context.getPackageManager(), true, context);

                    if (receivers.size() == 2) {
                        for (ResolveInfo resolveInfo : receivers) {
                            if (!MediaButtonReceiver.class.getName().equals(resolveInfo.activityInfo.name)) {
                                // Not using last last_media_button_receiver
                                // since we want this feature to work just as
                                // well on Android version < 4.0
                                Utils.forwardKeyCodeToComponent(context, new ComponentName(
                                        resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name), false,
                                        keyCode, null);
                                break;
                            }
                        }
                    } else {
                        showSelector(context, intent, keyEvent);
                    }
                }
            }

        }
    }

    /**
     * Shows the selector dialog that allows the user to decide which music
     * player should receiver the media button press intent.
     * 
     * @param context
     *            The context.
     * @param intent
     *            The intent to forward.
     * @param keyEvent
     *            The key event
     */
    private void showSelector(Context context, Intent intent, KeyEvent keyEvent) {
        KeyguardManager manager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        boolean locked = manager.inKeyguardRestrictedInputMode();

        Intent showForwardView = new Intent(Constants.INTENT_ACTION_VIEW_MEDIA_BUTTON_LIST);
        showForwardView.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        showForwardView.putExtras(intent);
        showForwardView.setClassName(context,
                locked ? ReceiverSelectorLocked.class.getName() : ReceiverSelector.class.getName());

        /* COMMENTED OUT FOR MARKET RELEASE Log.i(TAG, "Media Button Receiver: starting selector activity for keyevent: " + keyEvent); */

        if (locked) {

            // XXX See if this actually makes a difference, might
            // not be needed if we move more things to onCreate?
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            // acquire temp wake lock
            WakeLock wakeLock = powerManager.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP
                    | PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, TAG);
            wakeLock.setReferenceCounted(false);

            // Our app better display within 3 seconds or we have
            // bigger issues.
            wakeLock.acquire(3000);
        }
        context.startActivity(showForwardView);
    }
}
