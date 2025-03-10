/*
 * Copyright (C) 2017 ABC rom
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

package com.android.systemui.qs.tiles;

import android.content.pm.ActivityInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.widget.Toast;

import com.android.internal.util.colt.ColtUtils;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.qs.QSHost;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.R;

/** Quick settings tile: PictureInPictureTile **/
public class PictureInPictureTile extends QSTileImpl<BooleanState> {

    public PictureInPictureTile(QSHost host) {
        super(host);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.COLT;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        mHost.collapsePanels();
        ActivityInfo ai = ColtUtils.getRunningActivityInfo(mContext);
        if (ai != null && !ai.supportsPictureInPicture()) {
            Toast.makeText(mContext, mContext.getString(
                    R.string.quick_settings_pip_tile_app_na), Toast.LENGTH_LONG).show();
            return;
        }
	ColtUtils.sendKeycode(171);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent().setComponent(new ComponentName(
            "com.android.settings", "com.android.settings.Settings$PictureInPictureSettingsActivity"));
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_pip_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = mContext.getString(R.string.quick_settings_pip_label);
        state.icon = ResourceIcon.get(R.drawable.ic_qs_pip);
        state.contentDescription =  mContext.getString(
                R.string.quick_settings_pip_label);
    }

    @Override
    public void handleSetListening(boolean listening) {
        // Do nothing
    }
}
