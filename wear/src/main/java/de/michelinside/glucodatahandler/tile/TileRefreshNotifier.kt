package de.michelinside.glucodatahandler.tile

import android.content.Context
import androidx.wear.tiles.TileService
import de.michelinside.glucodatahandler.common.ReceiveData
import de.michelinside.glucodatahandler.common.database.dbSync
import de.michelinside.glucodatahandler.common.utils.Log

// Most CGMs (e.g. Dexcom) deliver a value every 5 minutes, so with a 2 minute threshold most tile
// view triggered a full 24h database sync from the phone. Only ask once a reading has actually been missed.
private const val STALE_DATA_THRESHOLD_MS = 6 * 60 * 1000L

// Nudges the phone for a fresh value when a tile becomes visible with data that is missing or more
// than 6 minutes old - the ordinary background sync may simply not have caught up yet. Cheap no-op
// if a sync is already in flight (dbSync.requestDbSync guards against overlapping requests).
internal fun requestFreshDataIfStale(context: Context) {
    if (ReceiveData.time == 0L || System.currentTimeMillis() - ReceiveData.time > STALE_DATA_THRESHOLD_MS)
        dbSync.requestDbSync(context)
}
