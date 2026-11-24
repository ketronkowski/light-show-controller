package com.ketronkowski.xlights.preshow

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronTrigger
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

/**
 * Registers one cron job per configured pass. Each job runs only on show nights
 * (inside the season) and only while the Home Assistant kill switch, if any, is on.
 */
@Component
class PreShowScheduler(
    private val taskScheduler: TaskScheduler,
    private val service: PreShowService,
    private val ha: HomeAssistantClient,
    private val props: PreShowProperties,
) {
    private val log = LoggerFactory.getLogger(PreShowScheduler::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun register() {
        if (!props.enabled) {
            log.info("Pre-show check: scheduled runs are off (preshow.enabled=false)")
            return
        }
        val zone = ZoneId.of(props.zone)
        for (pass in props.passes) {
            taskScheduler.schedule({ fire(pass) }, CronTrigger(pass.cron, zone))
            log.info("Pre-show check: pass '{}' scheduled at '{}' ({})", pass.name, pass.cron, props.zone)
        }
    }

    internal fun fire(pass: PreShowProperties.PassSchedule) {
        val today = LocalDate.now(ZoneId.of(props.zone))
        if (!inSeason(today)) {
            log.debug("Pre-show '{}' skipped: {} is outside the show season", pass.name, today)
            return
        }
        if (killSwitchOff()) {
            log.info("Pre-show '{}' skipped: turned off in Home Assistant ({})", pass.name, props.ha.enabledEntity)
            return
        }
        try {
            service.run(pass.name, NotifyPolicy(pass.pushAlways, pass.emailAlways), pass.layers)
        } catch (e: PreShowBusyException) {
            log.warn("Pre-show '{}' skipped: another run is still going", pass.name)
        } catch (e: Exception) {
            log.error("Pre-show '{}' failed", pass.name, e)
            ha.notify("Pre-show ${pass.name}: check crashed", "The pre-show check could not finish: ${e.brief()}")
        }
    }

    internal fun inSeason(day: LocalDate): Boolean =
        (props.seasonStart == null || !day.isBefore(props.seasonStart)) &&
            (props.seasonEnd == null || !day.isAfter(props.seasonEnd))

    /** Only an explicit "off" stops a run; if HA can't be read the check still runs. */
    private fun killSwitchOff(): Boolean {
        if (props.ha.enabledEntity.isBlank() || !ha.configured) return false
        return runCatching { ha.state(props.ha.enabledEntity).path("state").asText() == "off" }.getOrDefault(false)
    }
}
