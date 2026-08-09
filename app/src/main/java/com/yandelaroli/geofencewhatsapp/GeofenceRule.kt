package com.yandelaroli.geofencewhatsapp

data class GeofenceRule(
    val id: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val phone: String,
    val message: String,
    val enabled: Boolean = true
)
