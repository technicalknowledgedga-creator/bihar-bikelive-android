package com.bihar.bikelive

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class BikeLocationService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(101, buildNotification("Bihar BikeLive GPS Active"))

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                sendLocationToFirestore(loc.latitude, loc.longitude, loc.speed * 3.6)
            }
        }
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L)
            .setMinUpdateIntervalMillis(2500L)
            .build()
        fusedClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    private fun sendLocationToFirestore(lat: Double, lng: Double, speedKmh: Float) {
        val prefs = getSharedPreferences("bihar_bikelive_prefs", Context.MODE_PRIVATE)
        val isActive = prefs.getBoolean("is_tracking_active", false)
        if (!isActive) return

        val bikeId = prefs.getString("bike_id", "bihar-bike") ?: "bihar-bike"
        val riderName = prefs.getString("rider_name", "Bihar Rider") ?: "Bihar Rider"
        val bikeModel = prefs.getString("bike_model", "Bike") ?: "Bike"
        val bikePlate = prefs.getString("bike_plate", "") ?: ""

        ioExecutor.execute {
            try {
                val placeName = resolvePlace(lat, lng)
                val nowMs = System.currentTimeMillis()
                val status = if (speedKmh >= 2.5f) "MOVING" else "PARKED"

                val endpoint = "https://firestore.googleapis.com/v1/projects/bihar-bikelive/databases/(default)/documents/bike_live_status/$bikeId?updateMask.fieldPath=bikeId&updateMask.fieldPath=riderName&updateMask.fieldPath=bikeModel&updateMask.fieldPath=bikePlate&updateMask.fieldPath=lat&updateMask.fieldPath=lng&updateMask.fieldPath=speedKmh&updateMask.fieldPath=movementStatus&updateMask.fieldPath=placeName&updateMask.fieldPath=isTracking&updateMask.fieldPath=updatedAt"

                val payload = """
                {
                  "fields": {
                    "bikeId": {"stringValue": "${escapeJson(bikeId)}"},
                    "riderName": {"stringValue": "${escapeJson(riderName)}"},
                    "bikeModel": {"stringValue": "${escapeJson(bikeModel)}"},
                    "bikePlate": {"stringValue": "${escapeJson(bikePlate)}"},
                    "lat": {"doubleValue": $lat},
                    "lng": {"doubleValue": $lng},
                    "speedKmh": {"doubleValue": $speedKmh},
                    "movementStatus": {"stringValue": "$status"},
                    "placeName": {"stringValue": "${escapeJson(placeName)}"},
                    "isTracking": {"booleanValue": true},
                    "updatedAt": {"integerValue": "$nowMs"}
                  }
                }
                """.trimIndent()

                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "PATCH"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()

                if (code in 200..299) {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(101, buildNotification("$bikeId • $placeName"))
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun resolvePlace(lat: Double, lng: Double): String {
        return try {
            val geocoder = Geocoder(this, Locale.getDefault())
            @Suppress("DEPRECATION")
            val list = geocoder.getFromLocation(lat, lng, 1)
            if (!list.isNullOrEmpty()) {
                val addr = list[0]
                val area = addr.subLocality ?: addr.locality ?: addr.subAdminArea ?: ""
                val dist = addr.subAdminArea ?: addr.adminArea ?: "Bihar"
                if (area.isNotBlank() && area != dist) "$area, $dist" else "$dist, Bihar"
            } else {
                "Bihar (${String.format(Locale.US, "%.4f", lat)}, ${String.format(Locale.US, "%.4f", lng)})"
            }
        } catch (_: Exception) {
            "Bihar, India"
        }
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("bike_live_ch", "Bihar BikeLive GPS", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, "bike_live_ch")
            .setContentTitle("Bihar BikeLive Tracking")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedClient.removeLocationUpdates(locationCallback)
        ioExecutor.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
