package com.jot.app.core
import java.time.*

object ScheduleTimes {
    fun resolve(date: LocalDate, time: LocalTime, zone: ZoneId = ZoneId.systemDefault()): Long {
        val local = LocalDateTime.of(date, time)
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.isNotEmpty()) { "That time is skipped by the clock change. Choose another time." }
        require(offsets.size == 1) { "That time occurs twice during the clock change. Choose another time." }
        return local.toInstant(offsets.single()).toEpochMilli()
    }
}
