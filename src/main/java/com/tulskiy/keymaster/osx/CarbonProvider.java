package com.tulskiy.keymaster.osx;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import com.tulskiy.keymaster.common.HotKey;
import com.tulskiy.keymaster.common.MediaKey;
import com.tulskiy.keymaster.common.Provider;

import javax.swing.*;
import java.awt.event.ActionListener;
import java.util.*;

import static com.tulskiy.keymaster.osx.CarbonLib.Lib;
import static com.tulskiy.keymaster.osx.CarbonLib.*;

/**
 * Author: Denis Tulskiy
 * Date: 6/17/11
 */
public class CarbonProvider extends Provider {
    private static final int kEventHotKeyPressed = 5;

    private static final int kEventClassKeyboard = OS_TYPE("keyb");
    private static final int typeEventHotKeyID = OS_TYPE("hkid");
    private static final int kEventParamDirectObject = OS_TYPE("----");

    private static int idSeq = 1;

    private Map<Integer, OSXHotKey> hotKeys = new HashMap<Integer, OSXHotKey>();
    private Queue<OSXHotKey> registerQueue = new LinkedList<OSXHotKey>();
    private final Object lock = new Object();
    private boolean listen;
    private boolean reset;

    private EventHandlerProcPtr keyListener;
    private PointerByReference eventHandlerReference;
    public Thread thread;


    public void init() {
        thread = new Thread(new Runnable() {
            public void run() {
                synchronized (lock) {
                    logger.info("Installing Event Handler");
                    eventHandlerReference = new PointerByReference();
                    keyListener = new EventHandler();

                    EventTypeSpec[] eventTypes = (EventTypeSpec[]) (new EventTypeSpec().toArray(1));
                    eventTypes[0].eventClass = kEventClassKeyboard;
                    eventTypes[0].eventKind = kEventHotKeyPressed;

                    int status = Lib.InstallEventHandler(Lib.GetEventDispatcherTarget(), Lib.NewEventHandlerUPP(keyListener), 1, eventTypes, null, eventHandlerReference); //fHandlerRef
                    if (status != 0) {
                        logger.warning("Could not register Event Handler, error code: " + status);
                    }

                    if (eventHandlerReference.getValue() == null) {
                        logger.warning("Event Handler reference is null");
                    }
                    listen = true;
                    while (listen) {
                        if (reset) {
                            resetAll();
                            reset = false;
                            lock.notify();
                        }

                        while (!registerQueue.isEmpty()) {
                            register(registerQueue.poll());
                        }

                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        });

        thread.start();
    }

    private void resetAll() {
        logger.info("Resetting hotkeys");
        for (OSXHotKey hotKey : hotKeys.values()) {
            int ret = Lib.UnregisterEventHotKey(hotKey.handler.getValue());
            if (ret != 0) {
                logger.warning("Could not unregister hotkey. Error code: " + ret);
            }
        }
        hotKeys.clear();
    }

    private void register(OSXHotKey hotKey) {
        KeyStroke keyCode = hotKey.keyStroke;
        EventHotKeyID.ByValue hotKeyReference = new EventHotKeyID.ByValue();
        int id = idSeq++;
        hotKeyReference.id = id;
        hotKeyReference.signature = OS_TYPE("hk" + String.format("%02d", id));
        PointerByReference gMyHotKeyRef = new PointerByReference();

        int status = Lib.RegisterEventHotKey(KeyMap.getKeyCode(keyCode), KeyMap.getModifier(keyCode), hotKeyReference, Lib.GetEventDispatcherTarget(), 0, gMyHotKeyRef);

        if (status != 0) {
            logger.warning("Could not register HotKey: " + keyCode + ". Error code: " + status);
            return;
        }

        if (gMyHotKeyRef.getValue() == null) {
            logger.warning("HotKey returned null handler reference");
            return;
        }
        hotKey.handler = gMyHotKeyRef;
        logger.info("Registered hotkey: " + keyCode);
        hotKeys.put(id, hotKey);
    }

    public void stop() {
        logger.info("Stopping now");
        try {
            synchronized (lock) {
                listen = false;
                lock.notify();
            }
            thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (eventHandlerReference.getValue() != null) {
            Lib.RemoveEventHandler(eventHandlerReference.getValue());
        }
    }

    public void reset() {
        synchronized (lock) {
            reset = true;
            lock.notify();
            try {
                lock.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void register(KeyStroke keyCode, ActionListener listener) {
        synchronized (lock) {
            registerQueue.add(new OSXHotKey(keyCode, listener));
            lock.notify();
        }
    }

    public void register(MediaKey mediaKey, ActionListener listener) {
        logger.warning("Media keys are not supported on this platform");
    }

    private static int OS_TYPE(String osType) {
        byte[] bytes = osType.getBytes();
        return (bytes[0] << 24) + (bytes[1] << 16) + (bytes[2] << 8) + bytes[3];
    }

    private class EventHandler implements CarbonLib.EventHandlerProcPtr {
        public int callback(Pointer inHandlerCallRef, Pointer inEvent, Pointer inUserData) {
            EventHotKeyID eventHotKeyID = new EventHotKeyID();
            int ret = Lib.GetEventParameter(inEvent, kEventParamDirectObject, typeEventHotKeyID, null, eventHotKeyID.size(), null, eventHotKeyID);
            if (ret != 0) {
                logger.warning("Could not get event parameters. Error code: " + ret);
            } else {
                int eventId = eventHotKeyID.id;
                logger.info("Received event id: " + eventId);
                fireEvent(hotKeys.get(eventId));
            }
            return 0;
        }
    }

    class OSXHotKey extends HotKey {
        PointerByReference handler;

        public OSXHotKey(KeyStroke keyStroke, ActionListener listener) {
            super(keyStroke, listener);
        }
    }

}
