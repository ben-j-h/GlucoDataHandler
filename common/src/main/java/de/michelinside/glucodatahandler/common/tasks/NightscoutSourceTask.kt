package de.michelinside.glucodatahandler.common.tasks

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import de.michelinside.glucodatahandler.common.utils.Log
import de.michelinside.glucodatahandler.common.BuildConfig
import de.michelinside.glucodatahandler.common.Constants
import de.michelinside.glucodatahandler.common.GlucoDataService
import de.michelinside.glucodatahandler.common.ReceiveData
import de.michelinside.glucodatahandler.common.database.GlucoseValue
import de.michelinside.glucodatahandler.common.database.dbAccess
import de.michelinside.glucodatahandler.common.notifier.DataSource
import de.michelinside.glucodatahandler.common.utils.GlucoDataUtils
import de.michelinside.glucodatahandler.common.utils.HttpRequest
import de.michelinside.glucodatahandler.common.utils.JsonUtils
import de.michelinside.glucodatahandler.common.utils.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.lang.NumberFormatException
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.time.OffsetDateTime
import kotlin.math.min

class NightscoutSourceTask: DataSourceTask(Constants.SHARED_PREF_NIGHTSCOUT_ENABLED, DataSource.NIGHTSCOUT) {
    companion object {
        private val LOG_ID = "GDH.Task.Source.NightscoutTask"
        private var url = ""
        private var secret = ""
        private var token = ""
        private var iob_cob_support = true
        private var lastIobCobTime = 0L
        const val PEBBLE_ENDPOINT = "/pebble"
        const val ENTRIES_ENDPOINT = "/api/v1/entries/current.json"
        const val GRAPHDATA_ENDPOINT = "/api/v1/entries/sgv.json?find[date][\$gt]=%d&count=%d"
        const val SITE_CHANGE_ENDPOINT = "/api/v1/treatments.json?find[eventType]=Site%20Change&count=10"
        private const val SITE_CHANGE_CHECK_INTERVAL_MS = 15 * 60 * 1000L

        private fun getTreatmentTime(entry: JSONObject): Long {
            if (entry.optLong("mills", 0L) > 0L)
                return entry.optLong("mills")
            val createdAt = entry.optString("created_at")
            if (createdAt.isNotEmpty()) {
                try {
                    // ISO 8601 - some uploaders use a space instead of the 'T'
                    return OffsetDateTime.parse(createdAt.trim().replaceFirst(' ', 'T')).toInstant().toEpochMilli()
                } catch (exc: Exception) {
                    Log.w(LOG_ID, "Invalid created_at in treatment: $createdAt")
                }
            }
            return entry.optLong("date", 0L)
        }

        // tconnectsync uploads cartridge, cannula and tubing fills all as "Site Change". Only the cannula fill
        // is a new infusion site, so if the newest entry is a cartridge or tubing fill, the newest cannula fill
        // is used instead. Entries without such a reason (e.g. entered in Nightscout) are used as they are.
        fun getSiteChangeTime(entries: JSONArray): Long {
            var newest = 0L
            var newestIsFillOnly = false
            var newestCannula = 0L
            val maxTime = System.currentTimeMillis() + 5 * 60 * 1000L
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val time = getTreatmentTime(entry)
                if (time <= 0L || time > maxTime)
                    continue
                val text = (entry.optString("reason") + " " + entry.optString("notes")).lowercase()
                if (text.contains("cannula") && time > newestCannula)
                    newestCannula = time
                if (time > newest) {
                    newest = time
                    newestIsFillOnly = text.contains("cartridge") || text.contains("tubing")
                }
            }
            return if (newestIsFillOnly && newestCannula > 0L) newestCannula else newest
        }

        fun parsePebbleIobCob(jsonObject: JSONObject, bundle: Bundle): Boolean {
            var result = false
            if (jsonObject.has("iob")) {
                bundle.putFloat(ReceiveData.IOB, JsonUtils.getFloat("iob", jsonObject))
                result = true
            }
            if (jsonObject.has("cob")) {
                bundle.putFloat(ReceiveData.COB, Utils.getCobValue(JsonUtils.getFloat("cob", jsonObject)))
                result = true
            }
            if(!result && jsonObject.has("bgs")) {
                val jsonEntries = jsonObject.optJSONArray("bgs")
                if (jsonEntries == null || jsonEntries.length() <= 0) {
                    Log.w(LOG_ID, "No entries in body: $jsonObject")
                    return false
                }
                return parsePebbleIobCob(jsonEntries.getJSONObject(0), bundle)
            }
            return result
        }

    }

    override fun hasIobCobSupport(): Boolean {
        if (ReceiveData.source == DataSource.NIGHTSCOUT) {
            Log.d(LOG_ID, "Ignore IOB/COB request, as the last data source was Nightscout")
            return false
        }
        if (active(1L) && iob_cob_support)
            return true
        return false
    }

    override fun getLastIobCobTime(): Long {
        return lastIobCobTime
    }

    override fun needsInternet(): Boolean {
        if(HttpRequest.isLocalHost(url)) {
            Log.v(LOG_ID, "Localhost detected!")
            return false
        }
        return true
    }

    private fun getGraphData(firstValueTime: Long): Boolean {
        val count = Utils.getElapsedTimeMinute(firstValueTime, RoundingMode.HALF_UP)
        Log.i(LOG_ID, "Getting up to $count graph data for time > ${Utils.getUiTimeStamp(firstValueTime)} - ($firstValueTime)")
        if(count > 0) {
            val (result, errorText) = handleEntriesResponse(httpGet(getUrl(GRAPHDATA_ENDPOINT.format(firstValueTime,count)), getHeader()), firstValueTime)
            if(errorText.isNotEmpty())
                Log.e(LOG_ID, "Error while getting graph data: $errorText")
            return result
        }
        return false
    }

    private var lastSiteChangeCheck = 0L

    // Reads the last pump site change. Uses its own request, so an error here (e.g. no permission to read
    // treatments) never affects the state of the glucose source.
    private fun checkSiteChange() {
        if (System.currentTimeMillis() - lastSiteChangeCheck < SITE_CHANGE_CHECK_INTERVAL_MS)
            return
        lastSiteChangeCheck = System.currentTimeMillis()
        try {
            val request = HttpRequest()
            if (request.get(getUrl(SITE_CHANGE_ENDPOINT), getHeader(), getTrustAllCertificates()) != HttpURLConnection.HTTP_OK || request.response.isNullOrEmpty()) {
                Log.w(LOG_ID, "Site change request failed: ${request.code} - ${request.responseMessage}")
                return
            }
            val changeTime = getSiteChangeTime(JSONArray(request.response))
            Log.d(LOG_ID, "Site change time: ${Utils.getUiTimeStamp(changeTime)}")
            if (changeTime > 0L)
                ReceiveData.setSiteChangeTime(GlucoDataService.context!!, changeTime)
        } catch (exc: Exception) {
            Log.e(LOG_ID, "checkSiteChange exception: " + exc.message.toString())
        }
    }

    override fun getValue() : Boolean {
        checkSiteChange()
        val firstNeededValue = getFirstNeedGraphValueTime(1, firstGetValue, true) // always use entries for getting data
        if (firstNeededValue > 0) {
            if(getGraphData(firstNeededValue) && !iob_cob_support) {
                // data received, also no iob/cob to request
                return true
            }
        }
        Log.v(LOG_ID, "Requesting data from pebble endpoint (firstNeededValue: $firstNeededValue)")
        if (!handlePebbleResponse(httpGet(getUrl(PEBBLE_ENDPOINT), getHeader()))) {
            if (!hasIobCobSupport() || ReceiveData.getElapsedTimeMinute() >= getIntervalMinute()) {
                // only check for new value, if there is no (otherwise it was only called for IOB/COB)
                val body = httpGet(getUrl(ENTRIES_ENDPOINT), getHeader())
                if (body == null && lastErrorCode >= 300)
                    return false
                val (result, errorText) = handleEntriesResponse(body)
                if (!result) {
                    setLastError(errorText)
                    return false
                }
                return true
            }
        }
        return false
    }

    override fun getTrustAllCertificates(): Boolean = true

    // explains, whether Dexcom Share (fallback) has data, while Nightscout has none
    override fun getNoNewValueInfo(time: Long): String = DexcomShareSourceTask.fallbackInfo

    override fun checkPreferenceChanged(sharedPreferences: SharedPreferences, key: String?, context: Context): Boolean {
        var result = false
        if (key == null) {
            url = sharedPreferences.getString(Constants.SHARED_PREF_NIGHTSCOUT_URL, "")!!.trim().trimEnd('/')
            if(BuildConfig.DEBUG && url.isEmpty())
                url = BuildConfig.NIGHTSCOUT_DEBUG_SERVER
            secret = Utils.encryptSHA1(sharedPreferences.getString(Constants.SHARED_PREF_NIGHTSCOUT_SECRET, ""))
            token = sharedPreferences.getString(Constants.SHARED_PREF_NIGHTSCOUT_TOKEN, "")!!
            iob_cob_support = sharedPreferences.getBoolean(Constants.SHARED_PREF_NIGHTSCOUT_IOB_COB, true)
            result = true
        } else {
            when(key) {
                Constants.SHARED_PREF_NIGHTSCOUT_URL -> {
                    url = sharedPreferences.getString(Constants.SHARED_PREF_NIGHTSCOUT_URL, "")!!.trim().trimEnd('/')
                    lastSiteChangeCheck = 0L
                    result = true
                }
                Constants.SHARED_PREF_NIGHTSCOUT_SECRET -> {
                    secret = Utils.encryptSHA1(sharedPreferences.getString(Constants.SHARED_PREF_NIGHTSCOUT_SECRET, ""))
                    result = true
                }
                Constants.SHARED_PREF_NIGHTSCOUT_TOKEN -> {
                    token = sharedPreferences.getString(Constants.SHARED_PREF_NIGHTSCOUT_TOKEN, "")!!
                    result = true
                }
                Constants.SHARED_PREF_NIGHTSCOUT_IOB_COB -> {
                    iob_cob_support = sharedPreferences.getBoolean(Constants.SHARED_PREF_NIGHTSCOUT_IOB_COB, true)
                    result = true
                }
            }
        }
        return super.checkPreferenceChanged(sharedPreferences, key, context) || result
    }

    private fun getUrl(endpoint: String): String {
        var resultUrl = url + endpoint
        if (token.isNotEmpty()) {
            if(resultUrl.contains("?"))
                resultUrl += "&token=" + token
            else
                resultUrl += "?token=" + token
        }
        return resultUrl
    }

    private fun getHeader(): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()
        if (secret.isNotEmpty()) {
            result["api-secret"] = secret
        }
        return result
    }

    private fun handleEntriesResponse(body: String?, firstValueTime: Long = 0) : Pair<Boolean, String> {
        if (!body.isNullOrEmpty()) {
            Log.d(LOG_ID, "Handle entries response: " + body.take(1000))
            val jsonEntries = JSONArray(body)
            if (jsonEntries.length() <= 0) {
                return Pair(false, "No entries in body: " + body)
            }

            var lastValueIndex = 0
            try {
                if(jsonEntries.length() > 1) {
                    var lastTime = 0L
                    val values = mutableListOf<GlucoseValue>()
                    for (i in 0 until jsonEntries.length()) {
                        val jsonEntry = jsonEntries.getJSONObject(i)
                        val glucose = JsonUtils.getFloat("sgv", jsonEntry)
                        val time = jsonEntry.getLong("date")
                        if(GlucoDataUtils.isGlucoseValid(glucose) && time > 0 && time >= firstValueTime) {
                            values.add(GlucoseValue(time, glucose.toInt(), getRate(jsonEntry)))
                            if(time > lastTime) {
                                lastTime = time
                                lastValueIndex = i
                            }
                        }
                    }
                    Log.i(LOG_ID, "Add ${values.size} values to database - new value index: $lastValueIndex")
                    dbAccess.addGlucoseValues(values)
                }
            } catch (exc: Exception) {
                Log.e(LOG_ID, "Exception while parsing entries response: " + exc.message)
            }

            val jsonObject = jsonEntries.getJSONObject(lastValueIndex)
            val type: String? = if (jsonObject.has("type") ) jsonObject.getString("type") else null
            if (type == null || type != "sgv") {
                return Pair(false, "Unsupported type '$type' found in response: $jsonObject")
            }

            if(!jsonObject.has("date") || !jsonObject.has("sgv"))
                return Pair(false, "Missing values in response: $jsonObject")

            val valueTime = jsonObject.getLong("date")
            setLatestServerValueTime(source, valueTime)
            if(valueTime < firstValueTime)
                return Pair(true, "")   // no new value
            val glucoExtras = Bundle()
            setSgv(glucoExtras, jsonObject, false)
            setRate(glucoExtras, jsonObject)
            glucoExtras.putLong(ReceiveData.TIME, valueTime)
            if(jsonObject.has("device"))
                glucoExtras.putString(ReceiveData.SERIAL, jsonObject.getString("device"))


            handleResult(glucoExtras)
            return Pair(true, "")
        }
        return Pair(false, "No data in response!")
    }

    private fun handlePebbleResponse(body: String?) : Boolean {
        try {
            if (!body.isNullOrEmpty()) {
                Log.d(LOG_ID, "Handle pebble response: " + body)
                val jsonBody = JSONObject(body)
                val jsonEntries = jsonBody.optJSONArray("bgs")
                if (jsonEntries == null || jsonEntries.length() <= 0) {
                    Log.w(LOG_ID, "No entries in body: " + body)
                    return false
                }

                val jsonObject = jsonEntries.getJSONObject(0)

                if (!jsonObject.has("datetime") || !jsonObject.has("sgv") || (!jsonObject.has("trend") && !jsonObject.has(
                        "direction"
                    ))
                ) {
                    Log.w(LOG_ID, "Missing values in response: " + body)
                    return false
                }
                val glucoExtras = Bundle()
                glucoExtras.putLong(ReceiveData.TIME, jsonObject.getLong("datetime"))
                setSgv(glucoExtras, jsonObject, true)
                setRate(glucoExtras, jsonObject)
                if (jsonObject.has("device"))
                    glucoExtras.putString(ReceiveData.SERIAL, jsonObject.getString("device"))
                if (iob_cob_support) {
                    if(parsePebbleIobCob(jsonObject, glucoExtras))
                        lastIobCobTime = System.currentTimeMillis()
                } else {
                    glucoExtras.putFloat(ReceiveData.IOB, Float.NaN)
                    glucoExtras.putFloat(ReceiveData.COB, Float.NaN)
                    glucoExtras.putLong(ReceiveData.IOBCOB_TIME, 0L)
                }
                if (!hasIobCobSupport() || ReceiveData.getElapsedTimeMinute() >= getIntervalMinute()) {
                    handleResult(glucoExtras)
                } else {
                    handleIobResult(glucoExtras)
                }
                return true
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "Exception while parsing pebble response " + body + " - " + exc.message)
        }
        return false
    }

    private fun setSgv( bundle: Bundle, jsonObject: JSONObject, fromPebble: Boolean) {
        val glucose = JsonUtils.getFloat("sgv", jsonObject)
        if (glucose.isNaN())
            throw NumberFormatException("Invalid sgv format '" + jsonObject.optString("sgv") + "'")
        if (fromPebble && GlucoDataUtils.isMmolValue(glucose)) {
            bundle.putInt(ReceiveData.MGDL, GlucoDataUtils.mmolToMg(glucose).toInt())
            bundle.putFloat(ReceiveData.GLUCOSECUSTOM, glucose)
        } else {
            // Nightscout always provides mg/dl raw values!
            bundle.putInt(ReceiveData.MGDL, glucose.toInt())
        }
    }

    private fun setRate( bundle: Bundle, jsonObject: JSONObject) {
        bundle.putFloat(ReceiveData.RATE, getRate(jsonObject))
    }

    private fun getRate(jsonObject: JSONObject): Float {
        if (jsonObject.has("trend"))
            return getRateFromTrend(jsonObject.getInt("trend"))
        else if (jsonObject.has("direction"))
            return GlucoDataUtils.getRateFromLabel(jsonObject.getString("direction"))
        else {
            Log.w(LOG_ID, "Missing direction/trend in response: " + jsonObject)
        }
        return Float.NaN
    }

    private fun getRateFromTrend(trend: Int): Float {
        return when(trend) {
            1 -> 4F
            2 -> 2F
            3 -> 1F
            4 -> 0F
            5 -> -1F
            6 -> -2F
            7 -> -4f
            else -> Float.NaN
        }
    }
}
