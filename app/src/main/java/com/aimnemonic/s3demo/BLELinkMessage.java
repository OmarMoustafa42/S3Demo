package com.aimnemonic.s3demo;

import android.util.Log;

import java.util.LinkedList;

public class BLELinkMessage {
    private static final String TAG = "tag";
    private static final LinkedList<BLELinkMessage> bleLinkQueue = new LinkedList();
    private String message;
    BLELinkMessage(String msg) {
        this.message = msg;
    }

    public static boolean addLinkQueue(BLELinkMessage e) {
        boolean s;
        if(bleLinkQueue.size() != 0 && bleLinkQueue.getLast().message.equals(e.message)) {
            Log.d(TAG, "The message is already created!");
            s = true;
        } else {
            s = bleLinkQueue.add(e);
            Log.i(TAG, "Added the message " + e.message + " to the link queue");
        }
        Log.i(TAG, "The size of link queue is : " + bleLinkQueue.size());
        return s;
    }

    public static String handleLink() {
        Log.i(TAG, "The size of link queue is : " + bleLinkQueue.size());
        BLELinkMessage e = bleLinkQueue.poll();
        Log.i(TAG, "The size of link queue is : " + bleLinkQueue.size());
        if(e == null) {
            Log.e(TAG, "BLE Link Queue is empty!");
            return "Do nothing!";
        }
        return e.message;
    }
}
