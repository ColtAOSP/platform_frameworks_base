/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.R;

public class ThemeAccentUtils {
    public static final String TAG = "ThemeAccentUtils";

    private static final String[] ACCENTS = {
        "default_accent", // 0
        "com.accents.aqua", // 1
        "com.accents.darkred", // 2
        "com.accents.pink", // 3
        "com.accents.purple", // 4
        "com.accents.deeppurple", // 5
        "com.accents.indigo", // 6
        "com.accents.red", // 7
        "com.accents.lightblue", // 8
        "com.accents.cyan", // 9
        "com.accents.green", // 10
        "com.accents.lightgreen", // 11
        "com.accents.lime", // 12
        "com.accents.yellow", // 13
        "com.accents.amber", // 14
        "com.accents.orange", // 15
        "com.accents.deeporange", // 16
        "com.accents.brown", // 17
        "com.accents.grey", // 18
        "com.accents.bluegrey", // 19
        "com.accents.black", // 20
        "com.accents.white", // 21
    };

    private static final String[] getClocks(Context ctx) {
        final String list = ctx.getResources().getString(R.string.custom_clock_styles);
        return list.split(",");
    }

    private static final String[] DARK_THEMES = {
        "com.android.system.theme.dark", // 0
        "com.android.settings.theme.dark", // 1
        "com.android.dui.theme.dark", // 2
        "com.android.gboard.theme.dark", // 3
    };

    private static final String STOCK_DARK_THEME = "com.android.systemui.theme.dark";

    // Switches theme accent from one to another or back to stock
    public static void updateAccents(IOverlayManager om, int userId, int accentSetting) {
        if (accentSetting == 0) {
            unloadAccents(om, userId);
        } else if (accentSetting < 20) {
            try {
                om.setEnabled(ACCENTS[accentSetting],
                        true, userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't change theme", e);
            }
        } else if (accentSetting == 20) {
            try {
                // If using a dark theme we use the white accent, otherwise use the black accent
                if (isUsingDarkTheme(om, userId)) {
                    om.setEnabled(ACCENTS[21],
                            true, userId);
                } else {
                    om.setEnabled(ACCENTS[20],
                            true, userId);
                }
            } catch (RemoteException e) {
                Log.w(TAG, "Can't change theme", e);
            }
        }
    }

    // Unload all the theme accents
    public static void unloadAccents(IOverlayManager om, int userId) {
        // skip index 0
        for (int i = 1; i < ACCENTS.length; i++) {
            String accent = ACCENTS[i];
            try {
                om.setEnabled(accent,
                        false /*disable*/, userId);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    // Switches the analog clock from one to another or back to stock
    public static void updateClocks(IOverlayManager om, int userId, int clockSetting, Context ctx) {
        // all clock already unloaded due to StatusBar observer unloadClocks call
        // set the custom analog clock overlay
        if (clockSetting > 4) {
            try {
                final String[] clocks = getClocks(ctx);
                om.setEnabled(clocks[clockSetting],
                        true, userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't change analog clocks", e);
            }
        }
    }

    // Unload all the analog clocks
    public static void unloadClocks(IOverlayManager om, int userId, Context ctx) {
        // skip index 0
        final String[] clocks = getClocks(ctx);
        for (int i = 1; i < clocks.length; i++) {
            String clock = clocks[i];
            try {
                om.setEnabled(clock,
                        false /*disable*/, userId);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    // Check for the dark system theme
    public static boolean isUsingDarkTheme(IOverlayManager om, int userId) {
        OverlayInfo themeInfo = null;
        try {
            themeInfo = om.getOverlayInfo(DARK_THEMES[0],
                    userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return themeInfo != null && themeInfo.isEnabled();
    }

    public static void setLightDarkTheme(IOverlayManager om, int userId, boolean useDarkTheme) {
        for (String theme : DARK_THEMES) {
            try {
                om.setEnabled(theme,
                        useDarkTheme, userId);
                unfuckBlackWhiteAccent(om, userId);
                if (useDarkTheme) {
                    unloadStockDarkTheme(om, userId);
                }
            } catch (RemoteException e) {
                Log.w(TAG, "Can't change theme", e);
            }
        }
    }

    // Check for black and white accent overlays
    public static void unfuckBlackWhiteAccent(IOverlayManager om, int userId) {
        OverlayInfo themeInfo = null;
        try {
            if (isUsingDarkTheme(om, userId)) {
                themeInfo = om.getOverlayInfo(ACCENTS[20],
                        userId);
                if (themeInfo != null && themeInfo.isEnabled()) {
                    om.setEnabled(ACCENTS[20],
                            false /*disable*/, userId);
                    om.setEnabled(ACCENTS[21],
                            true, userId);
                }
            } else {
                themeInfo = om.getOverlayInfo(ACCENTS[21],
                        userId);
                if (themeInfo != null && themeInfo.isEnabled()) {
                    om.setEnabled(ACCENTS[21],
                            false /*disable*/, userId);
                    om.setEnabled(ACCENTS[20],
                            true, userId);
                }
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    // Unloads the stock dark theme
    public static void unloadStockDarkTheme(IOverlayManager om, int userId) {
        OverlayInfo themeInfo = null;
        try {
            themeInfo = om.getOverlayInfo(STOCK_DARK_THEME,
                    userId);
            if (themeInfo != null && themeInfo.isEnabled()) {
                om.setEnabled(STOCK_DARK_THEME,
                        false /*disable*/, userId);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
}
