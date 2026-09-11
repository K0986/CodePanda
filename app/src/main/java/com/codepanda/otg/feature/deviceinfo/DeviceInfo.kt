package com.codepanda.otg.feature.deviceinfo

/** A titled group of key/value facts about the device. */
data class InfoSection(
    val title: String,
    val entries: List<InfoEntry>,
)

data class InfoEntry(val label: String, val value: String)

/** Aggregated device information plus the raw `getprop` map for the power-user view. */
data class DeviceInfo(
    val sections: List<InfoSection>,
    val allProperties: List<InfoEntry>,
)
